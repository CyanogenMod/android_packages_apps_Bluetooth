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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
    static final int SCAN_RESULT_TYPE_BOTH = 3;

    // Internal messages for handling BLE scan operations.
    private static final int MSG_START_BLE_SCAN = 0;
    private static final int MSG_STOP_BLE_SCAN = 1;
    private static final int MSG_FLUSH_BATCH_RESULTS = 2;

    private static final String ACTION_REFRESH_BATCHED_SCAN =
            "com.android.bluetooth.gatt.REFRESH_BATCHED_SCAN";

    // Timeout for each controller operation.
    private static final int OPERATION_TIME_OUT_MILLIS = 500;

    private int mLastConfiguredScanSetting = Integer.MIN_VALUE;
    // Scan parameters for batch scan.
    private BatchScanParams mBatchScanParms;

    private GattService mService;
    private BroadcastReceiver mBatchAlarmReceiver;
    private boolean mBatchAlarmReceiverRegistered;
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

        if (mHandler != null) {
            // Shut down the thread
            mHandler.removeCallbacksAndMessages(null);
            Looper looper = mHandler.getLooper();
            if (looper != null) {
                looper.quit();
            }
            mHandler = null;
        }
    }

    /**
     * Returns the regular scan queue.
     */
    Set<ScanClient> getRegularScanQueue() {
        return mRegularScanClients;
    }

    /**
     * Returns batch scan queue.
     */
    Set<ScanClient> getBatchScanQueue() {
        return mBatchClients;
    }

    /**
     * Returns a set of full batch scan clients.
     */
    Set<ScanClient> getFullBatchScanQueue() {
        // TODO: split full batch scan clients and truncated batch clients so we don't need to
        // construct this every time.
        Set<ScanClient> fullBatchClients = new HashSet<ScanClient>();
        for (ScanClient client : mBatchClients) {
            if (client.settings.getScanResultType() == ScanSettings.SCAN_RESULT_TYPE_FULL) {
                fullBatchClients.add(client);
            }
        }
        return fullBatchClients;
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
            if (client == null) return;
            if (mRegularScanClients.contains(client)) {
                mScanNative.stopRegularScan(client);
                mScanNative.configureRegularScanParams();
            } else {
                mScanNative.stopBatchScan(client);
            }
            if (client.appDied) {
                logd("app died, unregister client - " + client.clientIf);
                mService.unregisterClient(client.clientIf);
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

    /**
     * Parameters for batch scans.
     */
    class BatchScanParams {
        int scanMode;
        int fullScanClientIf;
        int truncatedScanClientIf;

        BatchScanParams() {
            scanMode = -1;
            fullScanClientIf = -1;
            truncatedScanClientIf = -1;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            BatchScanParams other = (BatchScanParams) obj;
            return scanMode == other.scanMode && fullScanClientIf == other.fullScanClientIf
                    && truncatedScanClientIf == other.truncatedScanClientIf;

        }
    }

    private class ScanNative {

        // Delivery mode defined in bt stack.
        private static final int DELIVERY_MODE_IMMEDIATE = 0;
        private static final int DELIVERY_MODE_ON_FOUND_LOST = 1;
        private static final int DELIVERY_MODE_BATCH = 2;

        private static final int DEFAULT_ONLOST_ONFOUND_TIMEOUT_MILLIS = 1000;
        private static final int ONFOUND_SIGHTINGS = 2;

        private static final int ALL_PASS_FILTER_INDEX_REGULAR_SCAN = 1;
        private static final int ALL_PASS_FILTER_INDEX_BATCH_SCAN = 2;
        private static final int ALL_PASS_FILTER_SELECTION = 0;

        private static final int DISCARD_OLDEST_WHEN_BUFFER_FULL = 0;

        /**
         * Scan params corresponding to regular scan setting
         */
        private static final int SCAN_MODE_LOW_POWER_WINDOW_MS = 500;
        private static final int SCAN_MODE_LOW_POWER_INTERVAL_MS = 5000;
        private static final int SCAN_MODE_BALANCED_WINDOW_MS = 2000;
        private static final int SCAN_MODE_BALANCED_INTERVAL_MS = 5000;
        private static final int SCAN_MODE_LOW_LATENCY_WINDOW_MS = 5000;
        private static final int SCAN_MODE_LOW_LATENCY_INTERVAL_MS = 5000;

        /**
         * Scan params corresponding to batch scan setting
         */
        private static final int SCAN_MODE_BATCH_LOW_POWER_WINDOW_MS = 1500;
        private static final int SCAN_MODE_BATCH_LOW_POWER_INTERVAL_MS = 150000;
        private static final int SCAN_MODE_BATCH_BALANCED_WINDOW_MS = 1500;
        private static final int SCAN_MODE_BATCH_BALANCED_INTERVAL_MS = 15000;
        private static final int SCAN_MODE_BATCH_LOW_LATENCY_WINDOW_MS = 1500;
        private static final int SCAN_MODE_BATCH_LOW_LATENCY_INTERVAL_MS = 5000;

        // The logic is AND for each filter field.
        private static final int LIST_LOGIC_TYPE = 0x1111111;
        private static final int FILTER_LOGIC_TYPE = 1;
        // Filter indices that are available to user. It's sad we need to maintain filter index.
        private final Deque<Integer> mFilterIndexStack;
        // Map of clientIf and Filter indices used by client.
        private final Map<Integer, Deque<Integer>> mClientFilterIndexMap;
        // Keep track of the clients that uses ALL_PASS filters.
        private final Set<Integer> mAllPassRegularClients = new HashSet<>();
        private final Set<Integer> mAllPassBatchClients = new HashSet<>();

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
            mBatchAlarmReceiver = new BroadcastReceiver() {
                    @Override
                public void onReceive(Context context, Intent intent) {
                    Log.d(TAG, "awakened up at time " + SystemClock.elapsedRealtime());
                    String action;
                    if (null != (action = intent.getAction())) {
                        if (action.equals(ACTION_REFRESH_BATCHED_SCAN)) {
                            if (mBatchClients.isEmpty()) {
                                return;
                            }
                            // Note this actually flushes all pending batch data.
                            flushBatchScanResults(mBatchClients.iterator().next());
                        }
                    }
                }
            };
            mService.registerReceiver(mBatchAlarmReceiver, filter);
            mBatchAlarmReceiverRegistered = true;
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
            logd("configureRegularScanParams() - queue=" + mRegularScanClients.size());
            int curScanSetting = Integer.MIN_VALUE;
            ScanClient client = getAggressiveClient(mRegularScanClients);
            if (client != null) {
                curScanSetting = client.settings.getScanMode();
            }

            logd("configureRegularScanParams() - ScanSetting Scan mode=" + curScanSetting +
                    " mLastConfiguredScanSetting=" + mLastConfiguredScanSetting);

            if (curScanSetting != Integer.MIN_VALUE) {
                if (curScanSetting != mLastConfiguredScanSetting) {
                    int scanWindow, scanInterval;
                    switch (curScanSetting) {
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
                    scanWindow = Utils.millsToUnit(scanWindow);
                    scanInterval = Utils.millsToUnit(scanInterval);
                    gattClientScanNative(false);
                    gattSetScanParametersNative(scanInterval, scanWindow);
                    gattClientScanNative(true);
                    mLastConfiguredScanSetting = curScanSetting;
                }
            } else {
                mLastConfiguredScanSetting = curScanSetting;
                logd("configureRegularScanParams() - queue emtpy, scan stopped");
            }
        }

        ScanClient getAggressiveClient(Set<ScanClient> cList) {
            ScanClient result = null;
            int curScanSetting = Integer.MIN_VALUE;
            for (ScanClient client : cList) {
                // ScanClient scan settings are assumed to be monotonically increasing in value for
                // more power hungry(higher duty cycle) operation.
                if (client.settings.getScanMode() > curScanSetting) {
                    result = client;
                    curScanSetting = client.settings.getScanMode();
                }
            }
            return result;
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
            // Reset batch scan. May need to stop the existing batch scan and update scan params.
            resetBatchScan(client);
        }

        private void resetBatchScan(ScanClient client) {
            int clientIf = client.clientIf;
            BatchScanParams batchScanParams = getBatchScanParams();
            // Stop batch if batch scan params changed and previous params is not null.
            if (mBatchScanParms != null && (!mBatchScanParms.equals(batchScanParams))) {
                logd("stopping BLe Batch");
                resetCountDownLatch();
                gattClientStopBatchScanNative(clientIf);
                waitForCallback();
                // Clear pending results as it's illegal to config storage if there are still
                // pending results.
                flushBatchResults(clientIf);
            }
            // Start batch if batchScanParams changed and current params is not null.
            if (batchScanParams != null && (!batchScanParams.equals(mBatchScanParms))) {
                int notifyThreshold = 95;
                logd("Starting BLE batch scan");
                int resultType = getResultType(batchScanParams);
                int fullScanPercent = getFullScanStoragePercent(resultType);
                resetCountDownLatch();
                logd("configuring batch scan storage, appIf " + client.clientIf);
                gattClientConfigBatchScanStorageNative(client.clientIf, fullScanPercent,
                        100 - fullScanPercent, notifyThreshold);
                waitForCallback();
                resetCountDownLatch();
                int scanInterval =
                        Utils.millsToUnit(getBatchScanIntervalMillis(batchScanParams.scanMode));
                int scanWindow =
                        Utils.millsToUnit(getBatchScanWindowMillis(batchScanParams.scanMode));
                gattClientStartBatchScanNative(clientIf, resultType, scanInterval,
                        scanWindow, 0, DISCARD_OLDEST_WHEN_BUFFER_FULL);
                waitForCallback();
            }
            mBatchScanParms = batchScanParams;
            setBatchAlarm();
        }

        private int getFullScanStoragePercent(int resultType) {
            switch (resultType) {
                case SCAN_RESULT_TYPE_FULL:
                    return 100;
                case SCAN_RESULT_TYPE_TRUNCATED:
                    return 0;
                case SCAN_RESULT_TYPE_BOTH:
                    return 50;
                default:
                    return 50;
            }
        }

        private BatchScanParams getBatchScanParams() {
            if (mBatchClients.isEmpty()) {
                return null;
            }
            BatchScanParams params = new BatchScanParams();
            // TODO: split full batch scan results and truncated batch scan results to different
            // collections.
            for (ScanClient client : mBatchClients) {
                params.scanMode = Math.max(params.scanMode, client.settings.getScanMode());
                if (client.settings.getScanResultType() == ScanSettings.SCAN_RESULT_TYPE_FULL) {
                    params.fullScanClientIf = client.clientIf;
                } else {
                    params.truncatedScanClientIf = client.clientIf;
                }
            }
            return params;
        }

        private int getBatchScanWindowMillis(int scanMode) {
            switch (scanMode) {
                case ScanSettings.SCAN_MODE_LOW_LATENCY:
                    return SCAN_MODE_BATCH_LOW_LATENCY_WINDOW_MS;
                case ScanSettings.SCAN_MODE_BALANCED:
                    return SCAN_MODE_BATCH_BALANCED_WINDOW_MS;
                case ScanSettings.SCAN_MODE_LOW_POWER:
                    return SCAN_MODE_BATCH_LOW_POWER_WINDOW_MS;
                default:
                    return SCAN_MODE_BATCH_LOW_POWER_WINDOW_MS;
            }
        }

        private int getBatchScanIntervalMillis(int scanMode) {
            switch (scanMode) {
                case ScanSettings.SCAN_MODE_LOW_LATENCY:
                    return SCAN_MODE_BATCH_LOW_LATENCY_INTERVAL_MS;
                case ScanSettings.SCAN_MODE_BALANCED:
                    return SCAN_MODE_BATCH_BALANCED_INTERVAL_MS;
                case ScanSettings.SCAN_MODE_LOW_POWER:
                    return SCAN_MODE_BATCH_LOW_POWER_INTERVAL_MS;
                default:
                    return SCAN_MODE_BATCH_LOW_POWER_INTERVAL_MS;
            }
        }

        // Set the batch alarm to be triggered within a short window after batch interval. This
        // allows system to optimize wake up time while still allows a degree of precise control.
        private void setBatchAlarm() {
            // Cancel any pending alarm just in case.
            mAlarmManager.cancel(mBatchScanIntervalIntent);
            if (mBatchClients.isEmpty()) {
                return;
            }
            long batchTriggerIntervalMillis = getBatchTriggerIntervalMillis();
            // Allows the alarm to be triggered within
            // [batchTriggerIntervalMillis, 1.1 * batchTriggerIntervalMillis]
            long windowLengthMillis = batchTriggerIntervalMillis / 10;
            long windowStartMillis = SystemClock.elapsedRealtime() + batchTriggerIntervalMillis;
            mAlarmManager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    windowStartMillis, windowLengthMillis,
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
            mBatchClients.remove(client);
            removeScanFilters(client.clientIf);
            resetBatchScan(client);
        }

        void flushBatchResults(int clientIf) {
            logd("flushPendingBatchResults - clientIf = " + clientIf);
            if (mBatchScanParms.fullScanClientIf != -1) {
                resetCountDownLatch();
                gattClientReadScanReportsNative(mBatchScanParms.fullScanClientIf,
                        SCAN_RESULT_TYPE_FULL);
                waitForCallback();
            }
            if (mBatchScanParms.truncatedScanClientIf != -1) {
                resetCountDownLatch();
                gattClientReadScanReportsNative(mBatchScanParms.truncatedScanClientIf,
                        SCAN_RESULT_TYPE_TRUNCATED);
                waitForCallback();
            }
            setBatchAlarm();
        }

        void cleanup() {
            mAlarmManager.cancel(mBatchScanIntervalIntent);
            // Protect against multiple calls of cleanup.
            if (mBatchAlarmReceiverRegistered) {
                mService.unregisterReceiver(mBatchAlarmReceiver);
            }
            mBatchAlarmReceiverRegistered = false;
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
        // If no offload filter can/needs to be set, set ALL_PASS filter.
        // Otherwise offload all filters to hardware and enable all filters.
        private void configureScanFilters(ScanClient client) {
            int clientIf = client.clientIf;
            int deliveryMode = getDeliveryMode(client);
            if (!shouldAddAllPassFilterToController(client, deliveryMode)) {
                return;
            }

            resetCountDownLatch();
            gattClientScanFilterEnableNative(clientIf, true);
            waitForCallback();

            if (shouldUseAllPassFilter(client)) {
                int filterIndex = (deliveryMode == DELIVERY_MODE_BATCH) ?
                        ALL_PASS_FILTER_INDEX_BATCH_SCAN : ALL_PASS_FILTER_INDEX_REGULAR_SCAN;
                resetCountDownLatch();
                configureFilterParamter(clientIf, client, ALL_PASS_FILTER_SELECTION, filterIndex);
                waitForCallback();
            } else {
                Deque<Integer> clientFilterIndices = new ArrayDeque<Integer>();
                int featureSelection;
                int filterIndex;
                for (ScanFilter filter : client.filters) {
                    ScanFilterQueue queue = new ScanFilterQueue();
                    if (null != queue) {
                        queue.addScanFilter(filter);
                        featureSelection = queue.getFeatureSelection();
                        filterIndex = mFilterIndexStack.pop();
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
                }
                mClientFilterIndexMap.put(clientIf, clientFilterIndices);
            }
        }

        // Check whether the filter should be added to controller.
        // Note only on ALL_PASS filter should be added.
        private boolean shouldAddAllPassFilterToController(ScanClient client, int deliveryMode) {
            // Not an ALL_PASS client, need to add filter.
            if (!shouldUseAllPassFilter(client)) {
                return true;
            }

            if (deliveryMode == DELIVERY_MODE_BATCH) {
                mAllPassBatchClients.add(client.clientIf);
                return mAllPassBatchClients.size() == 1;
            } else {
                mAllPassRegularClients.add(client.clientIf);
                return mAllPassRegularClients.size() == 1;
            }
        }

        private void removeScanFilters(int clientIf) {
            Deque<Integer> filterIndices = mClientFilterIndexMap.remove(clientIf);
            if (filterIndices != null) {
                mFilterIndexStack.addAll(filterIndices);
                for (Integer filterIndex : filterIndices) {
                    resetCountDownLatch();
                    gattClientScanFilterParamDeleteNative(clientIf, filterIndex);
                    waitForCallback();
                }
            }
            // Remove if ALL_PASS filters are used.
            removeFilterIfExisits(mAllPassRegularClients, clientIf,
                    ALL_PASS_FILTER_INDEX_REGULAR_SCAN);
            removeFilterIfExisits(mAllPassBatchClients, clientIf,
                    ALL_PASS_FILTER_INDEX_BATCH_SCAN);
        }

        private void removeFilterIfExisits(Set<Integer> clients, int clientIf, int filterIndex) {
            if (!clients.contains(clientIf)) {
                return;
            }
            clients.remove(clientIf);
            // Remove ALL_PASS filter iff no app is using it.
            if (clients.isEmpty()) {
                resetCountDownLatch();
                gattClientScanFilterParamDeleteNative(clientIf, filterIndex);
                waitForCallback();
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
        private int getResultType(BatchScanParams params) {
            if (params.fullScanClientIf != -1 && params.truncatedScanClientIf != -1) {
                return SCAN_RESULT_TYPE_BOTH;
            }
            if (params.truncatedScanClientIf != -1) {
                return SCAN_RESULT_TYPE_TRUNCATED;
            }
            if (params.fullScanClientIf != -1) {
                return SCAN_RESULT_TYPE_FULL;
            }
            return -1;
        }

        // Check if ALL_PASS filter should be used for the client.
        private boolean shouldUseAllPassFilter(ScanClient client) {
            if (client == null) {
                return true;
            }
            if (client.filters == null || client.filters.isEmpty()) {
                return true;
            }
            return false;
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
            AdapterService adapterService;
            if (null != (adapterService = AdapterService.getAdapterService())) {
                int maxFiltersSupported = adapterService.getNumOfOffloadedScanFilterSupported();
                // Start from index 3 as:
                // index 0 is reserved for ALL_PASS filter in Settings app.
                // index 1 is reserved for ALL_PASS filter for regular scan apps.
                // index 2 is reserved for ALL_PASS filter for batch scan apps.
                for (int i = 3; i < maxFiltersSupported; ++i) {
                    mFilterIndexStack.add(i);
                }
            }
        }

        // Configure filter parameters.
        private void configureFilterParamter(int clientIf, ScanClient client, int featureSelection,
                int filterIndex) {
            int deliveryMode = getDeliveryMode(client);
            int rssiThreshold = Byte.MIN_VALUE;
            int timeout = getOnfoundLostTimeout(client);
            gattClientScanFilterParamAddNative(
                    clientIf, filterIndex, featureSelection, LIST_LOGIC_TYPE,
                    FILTER_LOGIC_TYPE, rssiThreshold, rssiThreshold, deliveryMode,
                    timeout, timeout, ONFOUND_SIGHTINGS);
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
            if ((settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0
                    || (settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_MATCH_LOST) != 0) {
                return DELIVERY_MODE_ON_FOUND_LOST;
            }
            return settings.getReportDelayMillis() == 0 ? DELIVERY_MODE_IMMEDIATE
                    : DELIVERY_MODE_BATCH;
        }

        // Get onfound and onlost timeouts in ms
        private int getOnfoundLostTimeout(ScanClient client) {
            if (client == null) {
                return DEFAULT_ONLOST_ONFOUND_TIMEOUT_MILLIS;
            }
            ScanSettings settings = client.settings;
            if (settings == null) {
                return DEFAULT_ONLOST_ONFOUND_TIMEOUT_MILLIS;
            }
            return (int) settings.getReportDelayMillis();
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
        if (DBG) Log.d(TAG, s);
    }
}
