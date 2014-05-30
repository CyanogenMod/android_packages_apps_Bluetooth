/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.bluetooth.le.ScanSettings;
import android.bluetooth.le.ScanFilter;

import java.util.List;
import java.util.UUID;

/**
 * Helper class identifying a client that has requested LE scan results.
 *
 * @hide
 */
/* package */class ScanClient {

    /**
     * Default scan window value
     */
    private static final int LE_SCAN_WINDOW_MS = 100;

    /**
     * Default scan interval value
     */
    private static final int LE_SCAN_INTERVAL_MS = 100;

    /**
     * Scan params corresponding to scan setting
     */
    private static final int SCAN_MODE_LOW_POWER_WINDOW_MS = 500;
    private static final int SCAN_MODE_LOW_POWER_INTERVAL_MS = 5000;
    private static final int SCAN_MODE_BALANCED_WINDOW_MS = 1000;
    private static final int SCAN_MODE_BALANCED_INTERVAL_MS = 5000;
    private static final int SCAN_MODE_LOW_LATENCY_WINDOW_MS = 2500;
    private static final int SCAN_MODE_LOW_LATENCY_INTERVAL_MS = 5000;

    int appIf;
    boolean isServer;
    UUID[] uuids;
    int scanWindow, scanInterval;
    ScanSettings settings;
    List<ScanFilter> filters;

    ScanClient(int appIf, boolean isServer) {
        this(appIf, isServer, new UUID[0], LE_SCAN_WINDOW_MS, LE_SCAN_INTERVAL_MS);
    }

    ScanClient(int appIf, boolean isServer, UUID[] uuids) {
        this(appIf, isServer, uuids, LE_SCAN_WINDOW_MS, LE_SCAN_INTERVAL_MS);
    }

    ScanClient(int appIf, boolean isServer, UUID[] uuids, int scanWindow, int scanInterval) {
        this(appIf, isServer, uuids, scanWindow, scanInterval, null, null);
    }

    ScanClient(int appIf, boolean isServer, ScanSettings settings,
            List<ScanFilter> filters) {
        this(appIf, isServer, new UUID[0], LE_SCAN_WINDOW_MS, LE_SCAN_INTERVAL_MS,
                settings, filters);
    }

    private ScanClient(int appIf, boolean isServer, UUID[] uuids, int scanWindow, int scanInterval,
            ScanSettings settings, List<ScanFilter> filters) {
        this.appIf = appIf;
        this.isServer = isServer;
        this.uuids = uuids;
        this.scanWindow = scanWindow;
        this.scanInterval = scanInterval;
        this.settings = settings;
        this.filters = filters;
        if (settings != null) {
            switch (settings.getScanMode()) {
                case BluetoothLeScanSettings.SCAN_MODE_LOW_POWER:
                    this.scanWindow = SCAN_MODE_LOW_POWER_WINDOW_MS;
                    this.scanInterval = SCAN_MODE_LOW_POWER_INTERVAL_MS;
                    break;
                case BluetoothLeScanSettings.SCAN_MODE_BALANCED:
                    this.scanWindow = SCAN_MODE_BALANCED_WINDOW_MS;
                    this.scanInterval = SCAN_MODE_BALANCED_INTERVAL_MS;
                    break;
                case BluetoothLeScanSettings.SCAN_MODE_LOW_LATENCY:
                    this.scanWindow = SCAN_MODE_LOW_LATENCY_WINDOW_MS;
                    this.scanInterval = SCAN_MODE_LOW_LATENCY_INTERVAL_MS;
                    break;
                default:
                    this.scanWindow = SCAN_MODE_BALANCED_WINDOW_MS;
                    this.scanInterval = SCAN_MODE_BALANCED_INTERVAL_MS;
                    break;
            }
        }
    }
}
