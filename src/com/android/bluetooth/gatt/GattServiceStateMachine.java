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
import android.bluetooth.le.ScanSettings;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteException;

import com.android.bluetooth.btservice.AdapterService;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
public class GattServiceStateMachine extends StateMachine {

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
    // The order to add BLE filters is enable filter -> add filter -> config filter.
    static final int ADD_BLE_SCAN_FILTER = 15;

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

    // Result type defined in bt stack.
    static final int SCAN_RESULT_TYPE_TRUNCATED = 1;
    static final int SCAN_RESULT_TYPE_FULL = 2;

    // Delivery mode defined in bt stack.
    private static final int DELIVERY_MODE_IMMEDIATE = 0;
    private static final int DELIVERY_MODE_ON_FOUND = 1;
    private static final int DELIVERY_MODE_BATCH = 2;

    private final GattService mService;
    private final Map<Integer, AdvertiseClient> mAdvertiseClients;
    // Keep track of whether scan filters exist.
    private boolean hasFilter = false;

    // All states for the state machine.
    private final Idle mIdle;
    private final ScanStarting mScanStarting;
    private final AdvertiseStarting mAdvertiseStarting;
    // A count down latch used to block on stack callback. MUST reset before use.
    private CountDownLatch mCallbackLatch;

    // It's sad we need to maintain this.
    private final Deque<Integer> mFilterIndexStack;
    // A map of client->scanFilterIndex map.
    private final Map<Integer, Deque<Integer>> mClientFilterIndexMap;

    private GattServiceStateMachine(GattService context) {
        super(TAG);
        mService = context;

        // Add all possible states to the state machine.
        mScanStarting = new ScanStarting();
        mIdle = new Idle();
        mAdvertiseStarting = new AdvertiseStarting();
        mAdvertiseClients = new HashMap<Integer, AdvertiseClient>();
        mFilterIndexStack = new ArrayDeque<Integer>();
        mClientFilterIndexMap = new HashMap<Integer, Deque<Integer>>();
        initFilterIndexStack();

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

    void initFilterIndexStack() {
        int maxFiltersSupported =
                AdapterService.getAdapterService().getNumOfOffloadedScanFilterSupported();
        for (int i = 1; i < maxFiltersSupported; ++i) {
            mFilterIndexStack.add(i);
        }
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
                    // Send the enable scan message to starting state for processing.
                    Message newMessage;
                    newMessage = obtainMessage(ADD_BLE_SCAN_FILTER);
                    newMessage.obj = message.obj;
                    newMessage.arg1 = message.arg1;
                    sendMessage(newMessage);
                    sendMessageDelayed(OPERATION_TIMEOUT, TIMEOUT_MILLIS);
                    transitionTo(mScanStarting);
                    break;
                case STOP_BLE_SCAN:
                    int clientIf = message.arg1;
                    resetCallbackLatch();
                    gattClientScanFilterParamClearAllNative(clientIf);
                    waitForCallback();
                    Deque<Integer> filterIndices = mClientFilterIndexMap.remove(clientIf);
                    if (filterIndices != null) {
                        mFilterIndexStack.addAll(filterIndices);
                    }
                    if (mClientFilterIndexMap.isEmpty()) {
                        gattClientScanNative(false);
                    }
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
                    clientIf = message.arg1;
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
                case ADD_BLE_SCAN_FILTER:
                    int clientIf = message.arg1;
                    resetCallbackLatch();
                    gattClientScanFilterEnableNative(clientIf, true);
                    waitForCallback();
                    ScanClient client = (ScanClient) message.obj;
                    if (client != null) {
                        Set<ScanFilter> filters = new HashSet<ScanFilter>(client.filters);
                        // TODO: add ALLOW_ALL filter.
                        if (filters != null && !filters.isEmpty() &&
                                filters.size() <= mFilterIndexStack.size()) {
                            Deque<Integer> clientFilterIndices = new ArrayDeque<Integer>();
                            for (ScanFilter filter : filters) {
                                ScanFilterQueue queue = new ScanFilterQueue();
                                queue.addScanFilter(filter);
                                int featureSelection = queue.getFeatureSelection();
                                int filterIndex = mFilterIndexStack.pop();
                                while (!queue.isEmpty()) {
                                    resetCallbackLatch();
                                    addFilterToController(clientIf, queue.pop(), filterIndex);
                                    waitForCallback();
                                }
                                resetCallbackLatch();
                                int deliveryMode = getDeliveryMode(client.settings);
                                logd("deliveryMode : " + deliveryMode);
                                int listLogicType = 0x1111111;
                                int filterLogicType = 1;
                                int rssiThreshold = Byte.MIN_VALUE;
                                gattClientScanFilterParamAddNative(
                                        clientIf, filterIndex, featureSelection, listLogicType,
                                        filterLogicType, rssiThreshold, rssiThreshold, deliveryMode,
                                        0, 0, 0);
                                waitForCallback();
                                clientFilterIndices.add(filterIndex);
                            }
                            mClientFilterIndexMap.put(clientIf, clientFilterIndices);
                        }
                    }
                    sendMessage(ENABLE_BLE_SCAN, client);
                    break;
                case ENABLE_BLE_SCAN:
                    client = (ScanClient) message.obj;
                    enableBleScan(client);
                    removeMessages(OPERATION_TIMEOUT);
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

    private void enableBleScan(ScanClient client) {
        if (client == null || client.settings == null
                || client.settings.getReportDelayNanos() == 0) {
            logd("enabling ble scan, appIf " + client.appIf);
            gattClientScanNative(true);
            return;
        }
        int fullScanPercent = 20;
        int notifyThreshold = 95;
        resetCallbackLatch();
        if (DBG)
            logd("configuring batch scan storage, appIf " + client.appIf);
        gattClientConfigBatchScanStorageNative(client.appIf, fullScanPercent,
                100 - fullScanPercent, notifyThreshold);
        waitForCallback();
        int scanMode = getResultType(client.settings);
        // TODO: configure scan parameters.
        int scanIntervalUnit = 8;
        int scanWindowUnit = 8;
        int discardRule = 2;
        int addressType = 0;
        logd("Starting BLE batch scan");
        gattClientStartBatchScanNative(client.appIf, scanMode, scanIntervalUnit, scanWindowUnit,
                addressType,
                discardRule);
    }

    private void resetCallbackLatch() {
        mCallbackLatch = new CountDownLatch(1);
    }

    private void waitForCallback() {
        try {
            mCallbackLatch.await();
        } catch (InterruptedException e) {
            // just ignore.
        }
    }

    void callbackDone() {
        mCallbackLatch.countDown();
    }

    private void addFilterToController(int clientIf, ScanFilterQueue.Entry entry, int filterIndex) {
        log("addFilterToController: " + entry.type);
        switch (entry.type) {
            case ScanFilterQueue.TYPE_DEVICE_ADDRESS:
                log("add address " + entry.address);
                gattClientScanFilterAddNative(clientIf, entry.type, filterIndex, 0, 0, 0, 0, 0, 0,
                        "", entry.address, (byte) 0, new byte[0], new byte[0]);
                break;

            case ScanFilterQueue.TYPE_SERVICE_DATA:
                gattClientScanFilterAddNative(clientIf, entry.type, filterIndex, 0, 0, 0, 0, 0, 0,
                        "", "", (byte) 0, entry.data, entry.data_mask);
                break;

            case ScanFilterQueue.TYPE_SERVICE_UUID:
            case ScanFilterQueue.TYPE_SOLICIT_UUID:
                gattClientScanFilterAddNative(clientIf, entry.type, filterIndex, 0, 0,
                        entry.uuid.getLeastSignificantBits(),
                        entry.uuid.getMostSignificantBits(),
                        entry.uuid_mask.getLeastSignificantBits(),
                        entry.uuid_mask.getMostSignificantBits(),
                        "", "", (byte) 0, new byte[0], new byte[0]);
                break;

            case ScanFilterQueue.TYPE_LOCAL_NAME:
                loge("adding filters: " + entry.name);
                gattClientScanFilterAddNative(clientIf, entry.type, filterIndex, 0, 0, 0, 0, 0, 0,
                        entry.name, "", (byte) 0, new byte[0], new byte[0]);
                break;

            case ScanFilterQueue.TYPE_MANUFACTURER_DATA:
            {
                int len = entry.data.length;
                if (entry.data_mask.length != len)
                    return;
                gattClientScanFilterAddNative(clientIf, entry.type, filterIndex, entry.company,
                        entry.company_mask, 0, 0, 0, 0, "", "", (byte) 0,
                        entry.data, entry.data_mask);
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
                return millsToUnit(ADVERTISING_INTERVAL_HIGH_MILLS);
            case AdvertiseSettings.ADVERTISE_MODE_BALANCED:
                return millsToUnit(ADVERTISING_INTERVAL_MEDIUM_MILLS);
            case AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY:
                return millsToUnit(ADVERTISING_INTERVAL_LOW_MILLS);
            default:
                // Shouldn't happen, just in case.
                return millsToUnit(ADVERTISING_INTERVAL_HIGH_MILLS);
        }
    }

    /**
     * Return batch scan result type value defined in bt stack.
     */
    int getResultType(ScanSettings settings) {
        return settings.getScanResultType() == ScanSettings.SCAN_RESULT_TYPE_FULL ?
                SCAN_RESULT_TYPE_FULL : SCAN_RESULT_TYPE_TRUNCATED;
    }

    // Get delivery mode based on scan settings.
    private int getDeliveryMode(ScanSettings settings) {
        if (settings == null) {
            return DELIVERY_MODE_IMMEDIATE;
        }
        // TODO: double check whether it makes sense to use the same delivery mode for found and
        // lost.
        if (settings.getCallbackType() == ScanSettings.CALLBACK_TYPE_ON_FOUND ||
                settings.getCallbackType() == ScanSettings.CALLBACK_TYPE_ON_LOST) {
            return DELIVERY_MODE_ON_FOUND;
        }
        return settings.getReportDelayNanos() == 0 ? DELIVERY_MODE_IMMEDIATE : DELIVERY_MODE_BATCH;
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

    private native void gattClientScanFilterAddNative(int client_if,
            int filter_type, int filter_index, int company_id,
            int company_id_mask, long uuid_lsb, long uuid_msb,
            long uuid_mask_lsb, long uuid_mask_msb, String name,
            String address, byte addr_type, byte[] data, byte[] mask);

    private native void gattClientScanFilterDeleteNative(int client_if,
            int filter_type, int filter_index, int company_id,
            int company_id_mask, long uuid_lsb, long uuid_msb,
            long uuid_mask_lsb, long uuid_mask_msb, String name,
            String address, byte addr_type, byte[] data, byte[] mask);

    private native void gattClientScanFilterParamClearAllNative(
            int client_if);

    private native void gattClientScanFilterParamAddNative(
            int client_if, int filt_index, int feat_seln,
            int list_logic_type, int filt_logic_type, int rssi_high_thres,
            int rssi_low_thres, int dely_mode, int found_timeout,
            int lost_timeout, int found_timeout_cnt);

    private native void gattClientScanFilterParamDeleteNative(
            int client_if, int filt_index);

    private native void gattClientScanFilterClearNative(int client_if,
            int filter_index);

    private native void gattClientScanFilterEnableNative(int client_if,
            boolean enable);

    // Below are batch scan related methods.
    private native void gattClientConfigBatchScanStorageNative(int client_if,
            int max_full_reports_percent, int max_truncated_reports_percent,
            int notify_threshold_percent);

    private native void gattClientStartBatchScanNative(int client_if, int scan_mode,
            int scan_interval_unit, int scan_window_unit, int address_type, int discard_rule);

    private native void gattClientStopBatchScanNative(int client_if);
}
