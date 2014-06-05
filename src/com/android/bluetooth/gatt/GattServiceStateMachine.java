/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.gatt;

import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisementData;
import android.bluetooth.le.ScanFilter;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteException;

import com.android.bluetooth.btservice.AdapterService;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The state machine that handles state transitions for GATT related operations, including BLE scan,
 * advertising and connection.
 * <p>
 * Scan state transitions are Idle -> ScanStarting -> Scanning -> Idle.
 * <p>
 * TODO:add connection states. move scan clients and related callbacks to state machine.
 *
 * @hide
 */
final class GattServiceStateMachine extends StateMachine {

    /**
     * Message to start BLE scan.
     */
    static final int START_BLE_SCAN = 1;
    /**
     * Message to stop BLE scan.
     */
    static final int STOP_BLE_SCAN = 2;

    static final int START_ADVERTISING = 3;
    static final int STOP_ADVERTISING = 4;

    // Message for internal state transitions.
    static final int ENABLE_BLE_SCAN = 11;
    static final int ENABLE_ADVERTISING = 12;
    static final int SET_ADVERTISING_DATA = 13;
    static final int CANCEL_ADVERTISING = 14;
    static final int CLEAR_SCAN_FILTER = 15;
    static final int ADD_SCAN_FILTER = 16;
    static final int ENABLE_SCAN_FILTER = 17;

    // TODO: Remove this once stack callback is stable.
    private static final int OPERATION_TIMEOUT = 101;
    private static final int TIMEOUT_MILLIS = 3000;

    private static final String TAG = "GattServiceStateMachine";
    private static final boolean DBG = true;

    private static final int ADVERTISING_INTERVAL_HIGH_MILLS = 1000;
    private static final int ADVERTISING_INTERVAL_MEDIUM_MILLS = 250;
    private static final int ADVERTISING_INTERVAL_LOW_MILLS = 100;
    // Add some randomness to the advertising min/max interval so the controller can do some
    // optimization.
    private static final int ADVERTISING_INTERVAL_DELTA_UNIT = 10;
    private static final int ADVERTISING_INTERVAL_MICROS_PER_UNIT = 625;

    // The following constants should be kept the same as those defined in bt stack.
    private static final int ADVERTISING_CHANNEL_37 = 1 << 0;
    private static final int ADVERTISING_CHANNEL_38 = 1 << 1;
    private static final int ADVERTISING_CHANNEL_39 = 1 << 2;
    private static final int ADVERTISING_CHANNEL_ALL =
            ADVERTISING_CHANNEL_37 | ADVERTISING_CHANNEL_38 | ADVERTISING_CHANNEL_39;

    private static final int ADVERTISING_TX_POWER_MIN = 0;
    private static final int ADVERTISING_TX_POWER_LOW = 1;
    private static final int ADVERTISING_TX_POWER_MID = 2;
    private static final int ADVERTISING_TX_POWER_UPPER = 3;
    private static final int ADVERTISING_TX_POWER_MAX = 4;

    // Note we don't expose connectable directed advertising to API.
    private static final int ADVERTISING_EVENT_TYPE_CONNECTABLE = 0;
    private static final int ADVERTISING_EVENT_TYPE_SCANNABLE = 2;
    private static final int ADVERTISING_EVENT_TYPE_NON_CONNECTABLE = 3;

    private final GattService mService;
    private final Map<Integer, AdvertiseClient> mAdvertiseClients;
    private final ScanFilterQueue mScanFilterQueue;
    // Keep track of whether scan filters exist.
    private boolean hasFilter = false;

    // All states for the state machine.
    private final Idle mIdle;
    private final ScanStarting mScanStarting;
    private final AdvertiseStarting mAdvertiseStarting;

    private GattServiceStateMachine(GattService context) {
        super(TAG);
        mService = context;

        // Add all possible states to the state machine.
        mScanStarting = new ScanStarting();
        mIdle = new Idle();
        mAdvertiseStarting = new AdvertiseStarting();
        mAdvertiseClients = new HashMap<Integer, AdvertiseClient>();
        mScanFilterQueue = new ScanFilterQueue();

        addState(mIdle);
        addState(mScanStarting);
        addState(mAdvertiseStarting);

        // Initial state is idle.
        setInitialState(mIdle);
    }

    /**
     * Make a {@link GattServiceStateMachine} object from {@link GattService} and start the machine
     * after it's created.
     */
    static GattServiceStateMachine make(GattService context) {
        GattServiceStateMachine stateMachine = new GattServiceStateMachine(context);
        stateMachine.start();
        return stateMachine;
    }

    void doQuit() {
        quitNow();
    }

    void cleanup() {
    }

    /**
     * {@link Idle} state is the state where there is no scanning or advertising activity.
     */
    private class Idle extends State {
        @Override
        public void enter() {
            if (DBG) {
                log("enter scan idle state: " + getCurrentMessage().what);
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean processMessage(Message message) {
            if (DBG) {
                log("idle processing message: " + getCurrentMessage().what);
            }

            switch (message.what) {
                case START_BLE_SCAN:
                    // TODO: check whether scan is already started for the app.
                    // Send the enable scan message to starting state for processing.
                    Message newMessage;
                    if (mService.isScanFilterSupported()) {
                        newMessage = obtainMessage(CLEAR_SCAN_FILTER);
                    } else {
                        newMessage = obtainMessage(ENABLE_BLE_SCAN);
                    }
                    newMessage.obj = message.obj;
                    sendMessage(newMessage);
                    transitionTo(mScanStarting);
                    break;
                case STOP_BLE_SCAN:
                    // Note this should only happen no client is doing scans any more.
                    gattClientScanNative(false);
                    break;
                case START_ADVERTISING:
                    AdvertiseClient client = (AdvertiseClient) message.obj;
                    if (mAdvertiseClients.containsKey(client.clientIf)) {
                        // do something.
                        loge("advertising already started for client : " + client.clientIf);
                        try {
                            mService.onMultipleAdvertiseCallback(client.clientIf,
                                    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED);
                        } catch (RemoteException e) {
                            loge("failed to start advertising", e);
                        }
                        transitionTo(mIdle);
                        break;
                    }
                    AdapterService adapter = AdapterService.getAdapterService();
                    int numOfAdvtInstances = adapter.getNumOfAdvertisementInstancesSupported();
                    if (mAdvertiseClients.size() >= numOfAdvtInstances) {
                        loge("too many advertisier, current size : " + mAdvertiseClients.size());
                        try {
                            mService.onMultipleAdvertiseCallback(client.clientIf,
                                    AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS);
                        } catch (RemoteException e) {
                            loge("failed to start advertising", e);
                        }
                        transitionTo(mIdle);
                        break;
                    }
                    newMessage = obtainMessage(ENABLE_ADVERTISING);
                    newMessage.obj = message.obj;
                    sendMessage(newMessage);
                    transitionTo(mAdvertiseStarting);
                    break;
                case STOP_ADVERTISING:
                    int clientIf = message.arg1;
                    if (!mAdvertiseClients.containsKey(clientIf)) {
                        try {
                            mService.onMultipleAdvertiseCallback(clientIf,
                                    AdvertiseCallback.ADVERTISE_FAILED_NOT_STARTED);
                        } catch (RemoteException e) {
                            loge("failed to stop advertising", e);
                        }
                    }
                    log("disabling client" + clientIf);
                    gattClientDisableAdvNative(clientIf);
                    mAdvertiseClients.remove(clientIf);
                    break;

                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    /**
     * State where scan status is being changing.
     */
    private class ScanStarting extends State {

        @Override
        public void enter() {
            if (DBG) {
                log("enter scan starting state: " + getCurrentMessage().what);
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean processMessage(Message message) {
            if (DBG) {
                log("Staring process message: " + message.what);
            }
            switch (message.what) {
                case START_BLE_SCAN: // fall through
                case STOP_BLE_SCAN:
                    // In scan starting state we don't handle start or stop scans. Defer processing
                    // the message until the state changes.
                    deferMessage(message);
                    break;
                case CLEAR_SCAN_FILTER:
                    // TODO: Don't change anything if the filter did not change.
                    Object obj = message.obj;
                    if (obj == null || ((Set<ScanFilter>) obj).isEmpty()) {
                        mScanFilterQueue.clear();
                        hasFilter = false;
                    } else {
                        mScanFilterQueue.addAll((Set<ScanFilter>) message.obj);
                        hasFilter = true;
                    }
                    gattClientScanFilterClearNative();
                    sendMessageDelayed(OPERATION_TIMEOUT, TIMEOUT_MILLIS);
                    break;
                case ADD_SCAN_FILTER:
                    if (mScanFilterQueue.isEmpty()) {
                        if (hasFilter) {
                            // Note we can only enable filter if there are filters added.
                            // TODO: Use callback action to detect if any filter has been added
                            // after stack provides different callback actions between filter
                            // cleared and filter added.
                            message = obtainMessage(ENABLE_SCAN_FILTER);
                        } else {
                            message = obtainMessage(ENABLE_BLE_SCAN);
                        }
                        sendMessage(message);
                    } else {
                        addFilterToController(mScanFilterQueue.pop());
                    }
                    break;
                case ENABLE_SCAN_FILTER:
                    gattClientScanFilterEnableNative(true);
                    break;
                case ENABLE_BLE_SCAN:
                    gattClientScanNative(true);
                    if (mService.isScanFilterSupported()) {
                        removeMessages(OPERATION_TIMEOUT);
                    }
                    transitionTo(mIdle);
                    break;
                case OPERATION_TIMEOUT:
                    transitionTo(mIdle);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private void addFilterToController(ScanFilterQueue.Entry entry) {
        switch (entry.type) {
            case ScanFilterQueue.TYPE_DEVICE_ADDRESS:
                gattClientScanFilterAddNative(entry.type, 0, 0, 0, 0, 0, 0,
                        "", entry.address, entry.addr_type, new byte[0]);
                break;

            case ScanFilterQueue.TYPE_SERVICE_DATA:
                gattClientScanFilterAddNative(entry.type, 0, 0, 0, 0, 0, 0, "",
                        "", (byte) 0, new byte[0]);
                break;

            case ScanFilterQueue.TYPE_SERVICE_UUID:
            case ScanFilterQueue.TYPE_SOLICIT_UUID:
                gattClientScanFilterAddNative(entry.type, 0, 0,
                        entry.uuid.getLeastSignificantBits(),
                        entry.uuid.getMostSignificantBits(),
                        entry.uuid_mask.getLeastSignificantBits(),
                        entry.uuid_mask.getMostSignificantBits(),
                        "", "", (byte) 0, new byte[0]);
                break;

            case ScanFilterQueue.TYPE_LOCAL_NAME:
                gattClientScanFilterAddNative(entry.type, 0, 0, 0, 0, 0, 0,
                        entry.name, "", (byte) 0, new byte[0]);
                break;

            case ScanFilterQueue.TYPE_MANUFACTURER_DATA:
            {
                int len = entry.data.length;
                byte[] data = new byte[len * 2];
                for (int i = 0; i != len; ++i)
                {
                    data[i] = entry.data[i];
                    if (entry.data_mask.length == len) {
                        data[i + len] = entry.data_mask[i];
                    } else {
                        data[i + len] = (byte) 0xFF;
                    }
                }
                gattClientScanFilterAddNative(entry.type, entry.company, entry.company_mask, 0, 0,
                        0,
                        0, "", "", (byte) 0, entry.data);
                break;
            }
        }
    }

    private class AdvertiseStarting extends State {

        @Override
        public void enter() {
            if (DBG) {
                log("enter advertising state: " + getCurrentMessage().what);
            }
        }

        @Override
        public boolean processMessage(Message message) {
            log("advertising starting " + message.what);
            switch (message.what) {
                case START_ADVERTISING:
                case STOP_ADVERTISING:
                    deferMessage(message);
                    break;
                case ENABLE_ADVERTISING:
                    AdvertiseClient client = (AdvertiseClient) message.obj;
                    mAdvertiseClients.put(client.clientIf, client);
                    enableAdvertising(client);
                    sendMessageDelayed(OPERATION_TIMEOUT, client.clientIf, TIMEOUT_MILLIS);
                    break;
                case SET_ADVERTISING_DATA:
                    int clientIf = message.arg1;
                    log("setting advertisement: " + clientIf);
                    client = mAdvertiseClients.get(clientIf);
                    setAdvertisingData(clientIf, client.advertiseData, false);
                    if (client.scanResponse != null) {
                        setAdvertisingData(clientIf, client.scanResponse, true);
                    }
                    removeMessages(OPERATION_TIMEOUT);
                    transitionTo(mIdle);
                    break;
                case CANCEL_ADVERTISING:
                case OPERATION_TIMEOUT:
                    clientIf = message.arg1;
                    try {
                        mService.onMultipleAdvertiseCallback(clientIf,
                                AdvertiseCallback.ADVERTISE_FAILED_CONTROLLER_FAILURE);
                    } catch (RemoteException e) {
                        loge("failed to start advertising", e);
                    }
                    transitionTo(mIdle);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    private void setAdvertisingData(int clientIf, AdvertisementData data, boolean isScanResponse) {
        if (data == null) {
            return;
        }
        boolean includeName = false;
        boolean includeTxPower = data.getIncludeTxPowerLevel();
        int appearance = 0;
        byte[] manufacturerData = data.getManufacturerSpecificData() == null ? new byte[0]
                : data.getManufacturerSpecificData();
        byte[] serviceData = data.getServiceData() == null ? new byte[0] : data.getServiceData();

        byte[] serviceUuids;
        if (data.getServiceUuids() == null) {
            serviceUuids = new byte[0];
        } else {
            ByteBuffer advertisingUuidBytes = ByteBuffer.allocate(
                    data.getServiceUuids().size() * 16)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (ParcelUuid parcelUuid : data.getServiceUuids()) {
                UUID uuid = parcelUuid.getUuid();
                // Least significant bits first as the advertising uuid should be in little-endian.
                advertisingUuidBytes.putLong(uuid.getLeastSignificantBits())
                        .putLong(uuid.getMostSignificantBits());
            }
            serviceUuids = advertisingUuidBytes.array();
        }
        log("isScanResponse " + isScanResponse + " manu data " + Arrays.toString(manufacturerData));
        log("include tx power " + includeTxPower);
        gattClientSetAdvDataNative(clientIf, isScanResponse, includeName, includeTxPower,
                appearance,
                manufacturerData, serviceData, serviceUuids);
    }

    private void enableAdvertising(AdvertiseClient client) {
        int clientIf = client.clientIf;
        log("enabling advertisement: " + clientIf);
        int minAdvertiseUnit = (int) getAdvertisingIntervalUnit(client.settings);
        int maxAdvertiseUnit = minAdvertiseUnit + ADVERTISING_INTERVAL_DELTA_UNIT;
        log("enabling advertising: " + clientIf + "minAdvertisingMills " + minAdvertiseUnit);
        gattClientEnableAdvNative(
                clientIf,
                minAdvertiseUnit, maxAdvertiseUnit,
                getAdvertisingEventType(client.settings),
                ADVERTISING_CHANNEL_ALL,
                getTxPowerLevel(client.settings));
    }

    // Convert settings tx power level to stack tx power level.
    private int getTxPowerLevel(AdvertiseSettings settings) {
        switch (settings.getTxPowerLevel()) {
            case AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW:
                return ADVERTISING_TX_POWER_MIN;
            case AdvertiseSettings.ADVERTISE_TX_POWER_LOW:
                return ADVERTISING_TX_POWER_LOW;
            case AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM:
                return ADVERTISING_TX_POWER_MID;
            case AdvertiseSettings.ADVERTISE_TX_POWER_HIGH:
                return ADVERTISING_TX_POWER_UPPER;
            default:
                // Shouldn't happen, just in case.
                return ADVERTISING_TX_POWER_MID;
        }
    }

    // Convert advertising event type to stack advertising event type.
    private int getAdvertisingEventType(AdvertiseSettings settings) {
        switch (settings.getType()) {
            case AdvertiseSettings.ADVERTISE_TYPE_CONNECTABLE:
                return ADVERTISING_EVENT_TYPE_CONNECTABLE;
            case AdvertiseSettings.ADVERTISE_TYPE_SCANNABLE:
                return ADVERTISING_EVENT_TYPE_SCANNABLE;
            case AdvertiseSettings.ADVERTISE_TYPE_NON_CONNECTABLE:
                return ADVERTISING_EVENT_TYPE_NON_CONNECTABLE;
            default:
                // Should't happen, just in case.
                return ADVERTISING_EVENT_TYPE_NON_CONNECTABLE;
        }
    }

    // Convert advertising milliseconds to advertising units(one unit is 0.625 millisecond).
    private long getAdvertisingIntervalUnit(AdvertiseSettings settings) {
        switch (settings.getMode()) {
            case AdvertiseSettings.ADVERTISE_MODE_LOW_POWER:
                return millsToUnit(ADVERTISING_INTERVAL_LOW_MILLS);
            case AdvertiseSettings.ADVERTISE_MODE_BALANCED:
                return millsToUnit(ADVERTISING_INTERVAL_MEDIUM_MILLS);
            case AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY:
                return millsToUnit(ADVERTISING_INTERVAL_HIGH_MILLS);
            default:
                // Shouldn't happen, just in case.
                return millsToUnit(ADVERTISING_INTERVAL_LOW_MILLS);
        }
    }

    private long millsToUnit(int millisecond) {
        return TimeUnit.MILLISECONDS.toMicros(millisecond) / ADVERTISING_INTERVAL_MICROS_PER_UNIT;
    }

    // Native functions.
    private native void gattClientScanNative(boolean start);

    private native void gattClientEnableAdvNative(int client_if,
            int min_interval, int max_interval, int adv_type, int chnl_map,
            int tx_power);

    private native void gattClientUpdateAdvNative(int client_if,
            int min_interval, int max_interval, int adv_type, int chnl_map,
            int tx_power);

    private native void gattClientSetAdvDataNative(int client_if,
            boolean set_scan_rsp, boolean incl_name, boolean incl_txpower, int appearance,
            byte[] manufacturer_data, byte[] service_data, byte[] service_uuid);

    private native void gattClientDisableAdvNative(int client_if);

    private native void gattClientScanFilterAddNative(int type,
            int company_id, int company_mask, long uuid_lsb, long uuid_msb,
            long uuid_mask_lsb, long uuid_mask_msb,
            String name, String address, byte addr_type, byte[] data);

    private native void gattClientScanFilterEnableNative(boolean enable);

    private native void gattClientScanFilterClearNative();
}
