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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.os.Message;

import com.android.bluetooth.btservice.AdapterService;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * The state machine that handles state transitions for BLE scan/
 * <p>
 * Scan state transitions are Idle -> ScanStarting -> Scanning -> Idle.
 * <p>
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

    // Message for internal state transitions.
    static final int ENABLE_BLE_SCAN = 11;
    // The order to add BLE filters is enable filter -> add filter -> config filter.
    static final int ADD_BLE_SCAN_FILTER = 15;

    // TODO: Remove this once stack callback is stable.
    private static final int OPERATION_TIMEOUT = 101;
    private static final int TIMEOUT_MILLIS = 3000;

    private static final String TAG = "GattServiceStateMachine";
    private static final boolean DBG = true;

    // Result type defined in bt stack.
    static final int SCAN_RESULT_TYPE_TRUNCATED = 1;
    static final int SCAN_RESULT_TYPE_FULL = 2;

    // Delivery mode defined in bt stack.
    private static final int DELIVERY_MODE_IMMEDIATE = 0;
    private static final int DELIVERY_MODE_ON_FOUND = 1;
    private static final int DELIVERY_MODE_BATCH = 2;

    private static final int ALLOW_ALL_FILTER_INDEX = 1;
    private static final int ALLOW_ALL_FILTER_SELECTION = 0;

    private final GattService mService;
    // Keep track of whether scan filters exist.
    private boolean hasFilter = false;

    // All states for the state machine.
    private final Idle mIdle;
    private final ScanStarting mScanStarting;
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
        mFilterIndexStack = new ArrayDeque<Integer>();
        mClientFilterIndexMap = new HashMap<Integer, Deque<Integer>>();

        addState(mIdle);
        addState(mScanStarting);

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
        // Start from index 2 as index 0 is reserved for ALLOW_ALL filter in Settings app and
        // index 1 is reserved for ALLOW_ALL filter for regular apps.
        for (int i = 2; i < maxFiltersSupported; ++i) {
            mFilterIndexStack.add(i);
        }
    }

    /**
     * {@link Idle} state is the state where there is no scanning activity.
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
            // TODO: find a better place for this.
            if (mFilterIndexStack.isEmpty()) {
                initFilterIndexStack();
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
                    // Add scan filters. The logic is:
                    // 1) If scan filter is not supported in hardware, start scan directly.
                    // 2) Otherwise if no offload filter can/needs to be set, set ALLOW_ALL filter.
                    // 3) Otherwise offload all filters to hardware and enable all filters.
                    ScanClient client = (ScanClient) message.obj;
                    // Start scan without setting hardware filters if offloaded filters are not
                    // supported by controller.
                    if (!isHardwareScanFilterSupported()) {
                        sendMessage(ENABLE_BLE_SCAN, client);
                        break;
                    }
                    int clientIf = message.arg1;
                    resetCallbackLatch();
                    gattClientScanFilterEnableNative(clientIf, true);
                    waitForCallback();
                    // Set ALLOW_ALL filter if needed.
                    if (shouldUseAllowAllFilter(client)) {
                        resetCallbackLatch();
                        configureFilterParamter(clientIf, client, ALLOW_ALL_FILTER_SELECTION,
                                ALLOW_ALL_FILTER_INDEX);
                        waitForCallback();
                    } else {
                        Deque<Integer> clientFilterIndices = new ArrayDeque<Integer>();
                        for (ScanFilter filter : client.filters) {
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
                            configureFilterParamter(clientIf, client, featureSelection,
                                    filterIndex);
                            waitForCallback();
                            clientFilterIndices.add(filterIndex);
                        }
                        mClientFilterIndexMap.put(clientIf, clientFilterIndices);
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

        // Configure filter parameters.
        private void configureFilterParamter(int clientIf, ScanClient client, int featureSelection,
                int filterIndex) {
            int deliveryMode = getDeliveryMode(client);
            int listLogicType = 0x1111111;
            int filterLogicType = 1;
            int rssiThreshold = Byte.MIN_VALUE;
            gattClientScanFilterParamAddNative(
                    clientIf, filterIndex, featureSelection, listLogicType,
                    filterLogicType, rssiThreshold, rssiThreshold, deliveryMode,
                    0, 0, 0);
        }
    }

    // Returns true if the controller supports scan filters.
    private boolean isHardwareScanFilterSupported() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter.isOffloadedFilteringSupported();
    }

    // Check if allow all filters should be used for the app.
    private boolean shouldUseAllowAllFilter(ScanClient client) {
        if (client == null) {
            return true;
        }
        if (client.filters == null || client.filters.isEmpty()) {
            return true;
        }
        return client.filters.size() < mClientFilterIndexMap.size();
    }

    private void enableBleScan(ScanClient client) {
        if (client == null || client.settings == null
                || client.settings.getReportDelaySeconds() == 0) {
            gattClientScanNative(true);
            return;
        }
        logd("enabling ble scan, appIf " + client.appIf);
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

    /**
     * Return batch scan result type value defined in bt stack.
     */
    int getResultType(ScanSettings settings) {
        return settings.getScanResultType() == ScanSettings.SCAN_RESULT_TYPE_FULL ?
                SCAN_RESULT_TYPE_FULL : SCAN_RESULT_TYPE_TRUNCATED;
    }

    // Get delivery mode based on scan settings.
    private int getDeliveryMode(ScanClient client) {
        if (client == null) {
            return DELIVERY_MODE_IMMEDIATE;
        }
        ScanSettings settings = client.settings;
        if (settings == null) {
            return DELIVERY_MODE_IMMEDIATE;
        }
        // TODO: double check whether it makes sense to use the same delivery mode for found and
        // lost.
        if ((settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0
                || (settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_MATCH_LOST) != 0) {
            return DELIVERY_MODE_ON_FOUND;
        }
        return settings.getReportDelaySeconds() == 0 ? DELIVERY_MODE_IMMEDIATE
                : DELIVERY_MODE_BATCH;
    }

    // Native functions.
    private native void gattClientScanNative(boolean start);

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
