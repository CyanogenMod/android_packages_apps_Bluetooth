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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Class that handles Bluetooth LE scan related operations.
 *
 * @hide
 */
public class ScanManager {
    private static final boolean DBG = GattServiceConfig.DBG;
    private static final String TAG = GattServiceConfig.TAG_PREFIX + "ScanManager";

    // Result type defined in bt stack. Need to be accessed by GattService.
    static final int SCAN_RESULT_TYPE_TRUNCATED = 1;
    static final int SCAN_RESULT_TYPE_FULL = 2;

    // Internal messages for handling BLE scan operations.
    private static final int MSG_START_BLE_SCAN = 0;
    private static final int MSG_STOP_BLE_SCAN = 1;
    private static final int MSG_FLUSH_BATCH_RESULTS = 2;

    private static final String ACTION_REFRESH_BATCHED_SCAN =
            "com.android.bluetooth.gatt.REFRESH_BATCHED_SCAN";

    // Timeout for each controller operation.
    private static final int OPERATION_TIME_OUT_MILLIS = 500;

    private static int lastConfiguredScanSetting = Integer.MIN_VALUE;

    private GattService mService;
    private ScanNative mScanNative;
    private ClientHandler mHandler;

    private Set<ScanClient> mRegularScanClients;
    private Set<ScanClient> mBatchClients;

    private CountDownLatch mLatch;

    ScanManager(GattService service) {
        mRegularScanClients = new HashSet<ScanClient>();
        mBatchClients = new HashSet<ScanClient>();
        mService = service;
        mScanNative = new ScanNative();
    }

    void start() {
        HandlerThread thread = new HandlerThread("BluetoothScanManager");
        thread.start();
        mHandler = new ClientHandler(thread.getLooper());
    }

    void cleanup() {
        mRegularScanClients.clear();
        mBatchClients.clear();
        mScanNative.cleanup();
    }

    /**
     * Returns the combined scan queue of regular scans and batch scans.
     */
    List<ScanClient> scanQueue() {
        List<ScanClient> clients = new ArrayList<>();
        clients.addAll(mRegularScanClients);
        clients.addAll(mBatchClients);
        return clients;
    }

    void startScan(ScanClient client) {
        sendMessage(MSG_START_BLE_SCAN, client);
    }

    void stopScan(ScanClient client) {
        sendMessage(MSG_STOP_BLE_SCAN, client);
    }

    void flushBatchScanResults(ScanClient client) {
        sendMessage(MSG_FLUSH_BATCH_RESULTS, client);
    }

    void callbackDone(int clientIf, int status) {
        logd("callback done for clientIf - " + clientIf + " status - " + status);
        if (status == 0) {
            mLatch.countDown();
        }
        // TODO: add a callback for scan failure.
    }

    private void sendMessage(int what, ScanClient client) {
        Message message = new Message();
        message.what = what;
        message.obj = client;
        mHandler.sendMessage(message);
    }

    private boolean isFilteringSupported() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter.isOffloadedFilteringSupported();
    }

    // Handler class that handles BLE scan operations.
    private class ClientHandler extends Handler {

        ClientHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            ScanClient client = (ScanClient) msg.obj;
            switch (msg.what) {
                case MSG_START_BLE_SCAN:
                    handleStartScan(client);
                    break;
                case MSG_STOP_BLE_SCAN:
                    handleStopScan(client);
                    break;
                case MSG_FLUSH_BATCH_RESULTS:
                    handleFlushBatchResults(client);
                    break;
                default:
                    // Shouldn't happen.
                    Log.e(TAG, "received an unkown message : " + msg.what);
            }
        }

        void handleStartScan(ScanClient client) {
            Utils.enforceAdminPermission(mService);
            logd("handling starting scan");

            if (!isScanSupported(client)) {
                Log.e(TAG, "Scan settings not supported");
                return;
            }

            if (mRegularScanClients.contains(client) || mBatchClients.contains(client)) {
                Log.e(TAG, "Scan already started");
                return;
            }
            // Begin scan operations.
            if (isBatchClient(client)) {
                mBatchClients.add(client);
                mScanNative.startBatchScan(client);
            } else {
                mRegularScanClients.add(client);
                mScanNative.startRegularScan(client);
                mScanNative.configureRegularScanParams();
            }
        }

        void handleStopScan(ScanClient client) {
            Utils.enforceAdminPermission(mService);
            if (mRegularScanClients.contains(client)) {
                mRegularScanClients.remove(client);
                mScanNative.configureRegularScanParams();
                mScanNative.stopRegularScan(client);
            } else {
                mBatchClients.remove(client);
                mScanNative.stopBatchScan(client);
            }
        }

        void handleFlushBatchResults(ScanClient client) {
            Utils.enforceAdminPermission(mService);
            if (!mBatchClients.contains(client)) {
                return;
            }
            mScanNative.flushBatchResults(client.clientIf);
        }

        private boolean isBatchClient(ScanClient client) {
            if (client == null || client.settings == null) {
                return false;
            }
            ScanSettings settings = client.settings;
            return settings.getCallbackType() == ScanSettings.CALLBACK_TYPE_ALL_MATCHES &&
                    settings.getReportDelayMillis() != 0;
        }

        private boolean isScanSupported(ScanClient client) {
            if (client == null || client.settings == null) {
                return true;
            }
            ScanSettings settings = client.settings;
            if (isFilteringSupported()) {
                return true;
            }
            return settings.getCallbackType() == ScanSettings.CALLBACK_TYPE_ALL_MATCHES &&
                    settings.getReportDelayMillis() == 0;
        }
    }

    private class ScanNative {

        // Delivery mode defined in bt stack.
        private static final int DELIVERY_MODE_IMMEDIATE = 0;
        private static final int DELIVERY_MODE_ON_FOUND = 1;
        private static final int DELIVERY_MODE_BATCH = 2;

        private static final int ALLOW_ALL_FILTER_INDEX = 1;
        private static final int ALLOW_ALL_FILTER_SELECTION = 0;

        /**
         * Scan params corresponding to scan setting
         */
        private static final int SCAN_MODE_LOW_POWER_WINDOW_MS = 500;
        private static final int SCAN_MODE_LOW_POWER_INTERVAL_MS = 5000;
        private static final int SCAN_MODE_BALANCED_WINDOW_MS = 2000;
        private static final int SCAN_MODE_BALANCED_INTERVAL_MS = 5000;
        private static final int SCAN_MODE_LOW_LATENCY_WINDOW_MS = 5000;
        private static final int SCAN_MODE_LOW_LATENCY_INTERVAL_MS = 5000;


        // The logic is AND for each filter field.
        private static final int LIST_LOGIC_TYPE = 0x1111111;
        private static final int FILTER_LOGIC_TYPE = 1;
        // Filter indices that are available to user. It's sad we need to maintain filter index.
        private final Deque<Integer> mFilterIndexStack;
        // Map of clientIf and Filter indices used by client.
        private final Map<Integer, Deque<Integer>> mClientFilterIndexMap;
        private AlarmManager mAlarmManager;
        private PendingIntent mBatchScanIntervalIntent;

        ScanNative() {
            mFilterIndexStack = new ArrayDeque<Integer>();
            mClientFilterIndexMap = new HashMap<Integer, Deque<Integer>>();

            mAlarmManager = (AlarmManager) mService.getSystemService(Context.ALARM_SERVICE);
            Intent batchIntent = new Intent(ACTION_REFRESH_BATCHED_SCAN, null);
            mBatchScanIntervalIntent = PendingIntent.getBroadcast(mService, 0, batchIntent, 0);
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_REFRESH_BATCHED_SCAN);
            mService.registerReceiver(
                    new BroadcastReceiver() {
                    @Override
                        public void onReceive(Context context, Intent intent) {
                            Log.d(TAG, "awakened up at time " + SystemClock.elapsedRealtime());
                            String action = intent.getAction();

                            if (action.equals(ACTION_REFRESH_BATCHED_SCAN)) {
                                if (mBatchClients.isEmpty()) {
                                    return;
                                }
                                // TODO: find out if we need to flush all clients at once.
                                flushBatchScanResults(mBatchClients.iterator().next());
                            }
                        }
                    }, filter);
        }

        private void resetCountDownLatch() {
            mLatch = new CountDownLatch(1);
        }

        // Returns true if mLatch reaches 0, false if timeout or interrupted.
        private boolean waitForCallback() {
            try {
                return mLatch.await(OPERATION_TIME_OUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        }

        void configureRegularScanParams() {
            if (DBG) Log.d(TAG, "configureRegularScanParams() - queue=" + mRegularScanClients.size());
            int curScanSetting = Integer.MIN_VALUE;

            for(ScanClient client : mRegularScanClients) {
                // ScanClient scan settings are assumed to be monotonically increasing in value for more
                // power hungry(higher duty cycle) operation
                if (client.settings.getScanMode() > curScanSetting) {
                    curScanSetting = client.settings.getScanMode();
                }
            }

            if (DBG) Log.d(TAG, "configureRegularScanParams() - ScanSetting Scan mode=" + curScanSetting +
                    " lastConfiguredScanSetting=" + lastConfiguredScanSetting);

            if (curScanSetting != Integer.MIN_VALUE) {
                if (curScanSetting != lastConfiguredScanSetting) {
                    int scanWindow, scanInterval;
                    switch (curScanSetting){
                        case ScanSettings.SCAN_MODE_LOW_POWER:
                            scanWindow = SCAN_MODE_LOW_POWER_WINDOW_MS;
                            scanInterval = SCAN_MODE_LOW_POWER_INTERVAL_MS;
                            break;
                        case ScanSettings.SCAN_MODE_BALANCED:
                            scanWindow = SCAN_MODE_BALANCED_WINDOW_MS;
                            scanInterval = SCAN_MODE_BALANCED_INTERVAL_MS;
                            break;
                        case ScanSettings.SCAN_MODE_LOW_LATENCY:
                            scanWindow = SCAN_MODE_LOW_LATENCY_WINDOW_MS;
                            scanInterval = SCAN_MODE_LOW_LATENCY_INTERVAL_MS;
                            break;
                        default:
                            Log.e(TAG, "Invalid value for curScanSetting " + curScanSetting);
                            scanWindow = SCAN_MODE_LOW_POWER_WINDOW_MS;
                            scanInterval = SCAN_MODE_LOW_POWER_INTERVAL_MS;
                            break;
                    }
                    // convert scanWindow and scanInterval from ms to LE scan units(0.625ms)
                    scanWindow = (scanWindow * 1000)/625;
                    scanInterval = (scanInterval * 1000)/625;
                    gattClientScanNative(false);
                    gattSetScanParametersNative(scanInterval, scanWindow);
                    gattClientScanNative(true);
                    lastConfiguredScanSetting = curScanSetting;
                }
            } else {
                lastConfiguredScanSetting = curScanSetting;
                if (DBG) Log.d(TAG, "configureRegularScanParams() - queue emtpy, scan stopped");
            }
        }


        void startRegularScan(ScanClient client) {
            if (mFilterIndexStack.isEmpty() && isFilteringSupported()) {
                initFilterIndexStack();
            }
            if (isFilteringSupported()) {
                configureScanFilters(client);
            }
            // Start scan native only for the first client.
            if (mRegularScanClients.size() == 1) {
                gattClientScanNative(true);
            }
        }

        void startBatchScan(ScanClient client) {
            if (mFilterIndexStack.isEmpty() && isFilteringSupported()) {
                initFilterIndexStack();
            }
            configureScanFilters(client);
            int fullScanPercent = 50;
            int notifyThreshold = 95;
            resetCountDownLatch();
            logd("configuring batch scan storage, appIf " + client.clientIf);
            gattClientConfigBatchScanStorageNative(client.clientIf, fullScanPercent,
                    100 - fullScanPercent, notifyThreshold);
            waitForCallback();
            int scanMode = getResultType(client.settings);
            // TODO: configure scan parameters.
            int scanIntervalUnit = 8;
            int scanWindowUnit = 8;
            int discardRule = 2;
            int addressType = 0;
            gattClientStartBatchScanNative(client.clientIf, scanMode, scanIntervalUnit,
                    scanWindowUnit, addressType, discardRule);
            logd("Starting BLE batch scan, scanMode -" + scanMode);
            gattClientStartBatchScanNative(client.clientIf, scanMode, scanIntervalUnit,
                    scanWindowUnit, addressType, discardRule);
            setBatchAlarm();
        }

        private void setBatchAlarm() {
            if (mBatchClients.isEmpty()) {
                mAlarmManager.cancel(mBatchScanIntervalIntent);
                return;
            }
            long batchTriggerIntervalMillis = getBatchTriggerIntervalMillis();
            mAlarmManager.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + batchTriggerIntervalMillis,
                    batchTriggerIntervalMillis,
                    mBatchScanIntervalIntent);
        }

        void stopRegularScan(ScanClient client) {
            // Remove scan filters and recycle filter indices.
            removeScanFilters(client.clientIf);
            mRegularScanClients.remove(client);
            if (mRegularScanClients.isEmpty()) {
                logd("stop scan");
                gattClientScanNative(false);
            }
        }

        void stopBatchScan(ScanClient client) {
            removeScanFilters(client.clientIf);
            mBatchClients.remove(client);
            gattClientStopBatchScanNative(client.clientIf);
            setBatchAlarm();
        }

        void flushBatchResults(int clientIf) {
            logd("flushPendingBatchResults - clientIf = " + clientIf);
            ScanClient client = getBatchScanClient(clientIf);
            if (client == null) {
                logd("unknown client : " + clientIf);
                return;
            }
            int resultType = getResultType(client.settings);
            gattClientReadScanReportsNative(client.clientIf, resultType);
        }

        void cleanup() {
            mAlarmManager.cancel(mBatchScanIntervalIntent);
        }

        private long getBatchTriggerIntervalMillis() {
            long intervalMillis = Long.MAX_VALUE;
            for (ScanClient client : mBatchClients) {
                if (client.settings != null && client.settings.getReportDelayMillis() > 0) {
                    intervalMillis = Math.min(intervalMillis,
                            client.settings.getReportDelayMillis());
                }
            }
            return intervalMillis;
        }

        // Add scan filters. The logic is:
        // If no offload filter can/needs to be set, set ALLOW_ALL filter.
        // Otherwise offload all filters to hardware and enable all filters.
        private void configureScanFilters(ScanClient client) {
            int clientIf = client.clientIf;
            resetCountDownLatch();
            gattClientScanFilterEnableNative(clientIf, true);
            waitForCallback();

            if (shouldUseAllowAllFilter(client)) {
                resetCountDownLatch();
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
                        resetCountDownLatch();
                        addFilterToController(clientIf, queue.pop(), filterIndex);
                        waitForCallback();
                    }
                    resetCountDownLatch();
                    configureFilterParamter(clientIf, client, featureSelection, filterIndex);
                    waitForCallback();
                    clientFilterIndices.add(filterIndex);
                }
                mClientFilterIndexMap.put(clientIf, clientFilterIndices);
            }
        }

        private void removeScanFilters(int clientIf) {
            logd("removeScanFilters, clientIf - " + clientIf);
            Deque<Integer> filterIndices = mClientFilterIndexMap.remove(clientIf);
            if (filterIndices != null) {
                mFilterIndexStack.addAll(filterIndices);
                for (Integer filterIndex : filterIndices) {
                    resetCountDownLatch();
                    gattClientScanFilterParamDeleteNative(clientIf, filterIndex);
                    waitForCallback();
                }
            }
        }

        private ScanClient getBatchScanClient(int clientIf) {
            for (ScanClient client : mBatchClients) {
                if (client.clientIf == clientIf) {
                    return client;
                }
            }
            return null;
        }

        /**
         * Return batch scan result type value defined in bt stack.
         */
        private int getResultType(ScanSettings settings) {
            return settings.getScanResultType() == ScanSettings.SCAN_RESULT_TYPE_FULL ?
                    SCAN_RESULT_TYPE_FULL : SCAN_RESULT_TYPE_TRUNCATED;
        }

        // Check if ALLOW_FILTER should be used for the client.
        private boolean shouldUseAllowAllFilter(ScanClient client) {
            if (client == null) {
                return true;
            }
            if (client.filters == null || client.filters.isEmpty()) {
                return true;
            }
            return client.filters.size() < mClientFilterIndexMap.size();
        }

        private void addFilterToController(int clientIf, ScanFilterQueue.Entry entry,
                int filterIndex) {
            logd("addFilterToController: " + entry.type);
            switch (entry.type) {
                case ScanFilterQueue.TYPE_DEVICE_ADDRESS:
                    logd("add address " + entry.address);
                    gattClientScanFilterAddNative(clientIf, entry.type, filterIndex, 0, 0, 0, 0, 0,
                            0,
                            "", entry.address, (byte) 0, new byte[0], new byte[0]);
                    break;

                case ScanFilterQueue.TYPE_SERVICE_DATA:
                    gattClientScanFilterAddNative(clientIf, entry.type, filterIndex, 0, 0, 0, 0, 0,
                            0,
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
                    logd("adding filters: " + entry.name);
                    gattClientScanFilterAddNative(clientIf, entry.type, filterIndex, 0, 0, 0, 0, 0,
                            0,
                            entry.name, "", (byte) 0, new byte[0], new byte[0]);
                    break;

                case ScanFilterQueue.TYPE_MANUFACTURER_DATA:
                    int len = entry.data.length;
                    if (entry.data_mask.length != len)
                        return;
                    gattClientScanFilterAddNative(clientIf, entry.type, filterIndex, entry.company,
                            entry.company_mask, 0, 0, 0, 0, "", "", (byte) 0,
                            entry.data, entry.data_mask);
                    break;
            }
        }

        private void initFilterIndexStack() {
            int maxFiltersSupported =
                    AdapterService.getAdapterService().getNumOfOffloadedScanFilterSupported();
            // Start from index 2 as index 0 is reserved for ALLOW_ALL filter in Settings app and
            // index 1 is reserved for ALLOW_ALL filter for regular apps.
            for (int i = 2; i < maxFiltersSupported; ++i) {
                mFilterIndexStack.add(i);
            }
        }

        // Configure filter parameters.
        private void configureFilterParamter(int clientIf, ScanClient client, int featureSelection,
                int filterIndex) {
            int deliveryMode = getDeliveryMode(client);
            int rssiThreshold = Byte.MIN_VALUE;
            gattClientScanFilterParamAddNative(
                    clientIf, filterIndex, featureSelection, LIST_LOGIC_TYPE,
                    FILTER_LOGIC_TYPE, rssiThreshold, rssiThreshold, deliveryMode,
                    0, 0, 0);
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
            return settings.getReportDelayMillis() == 0 ? DELIVERY_MODE_IMMEDIATE
                    : DELIVERY_MODE_BATCH;
        }

        /************************** Regular scan related native methods **************************/
        private native void gattClientScanNative(boolean start);

        private native void gattSetScanParametersNative(int scan_interval,
                int scan_window);

        /************************** Filter related native methods ********************************/
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

        private native void gattClientScanFilterParamAddNative(
                int client_if, int filt_index, int feat_seln,
                int list_logic_type, int filt_logic_type, int rssi_high_thres,
                int rssi_low_thres, int dely_mode, int found_timeout,
                int lost_timeout, int found_timeout_cnt);

        // Note this effectively remove scan filters for ALL clients.
        private native void gattClientScanFilterParamClearAllNative(
                int client_if);

        private native void gattClientScanFilterParamDeleteNative(
                int client_if, int filt_index);

        private native void gattClientScanFilterClearNative(int client_if,
                int filter_index);

        private native void gattClientScanFilterEnableNative(int client_if,
                boolean enable);

        /************************** Batch related native methods *********************************/
        private native void gattClientConfigBatchScanStorageNative(int client_if,
                int max_full_reports_percent, int max_truncated_reports_percent,
                int notify_threshold_percent);

        private native void gattClientStartBatchScanNative(int client_if, int scan_mode,
                int scan_interval_unit, int scan_window_unit, int address_type, int discard_rule);

        private native void gattClientStopBatchScanNative(int client_if);

        private native void gattClientReadScanReportsNative(int client_if, int scan_type);
    }

    private void logd(String s) {
        Log.d(TAG, s);
    }
}
