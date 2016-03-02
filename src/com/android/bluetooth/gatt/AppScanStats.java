/*
 * Copyright (C) 2016 The Android Open Source Project
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
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import com.android.bluetooth.btservice.BluetoothProto;
/**
 * ScanStats class helps keep track of information about scans
 * on a per application basis.
 * @hide
 */
/*package*/ class AppScanStats {
    static final DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

    /* ContextMap here is needed to grab Apps and Connections */
    ContextMap contextMap;

    class LastScan {
        long duration;
        long timestamp;
        boolean opportunistic;
        boolean background;

        public LastScan(long timestamp, long duration,
                        boolean opportunistic, boolean background) {
            this.duration = duration;
            this.timestamp = timestamp;
            this.opportunistic = opportunistic;
            this.background = background;
        }
    }

    static final int NUM_SCAN_DURATIONS_KEPT = 5;

    String appName;
    int scansStarted = 0;
    int scansStopped = 0;
    boolean isScanning = false;
    boolean isRegistered = false;
    long minScanTime = Long.MAX_VALUE;
    long maxScanTime = 0;
    long totalScanTime = 0;
    List<LastScan> lastScans = new ArrayList<LastScan>(NUM_SCAN_DURATIONS_KEPT + 1);
    long startTime = 0;
    long stopTime = 0;

    public AppScanStats(String name, ContextMap map) {
        appName = name;
        contextMap = map;
    }

    synchronized void recordScanStart(ScanSettings settings) {
        if (isScanning)
            return;

        this.scansStarted++;
        isScanning = true;
        startTime = System.currentTimeMillis();

        LastScan scan = new LastScan(startTime, 0, false, false);
        if (settings != null) {
          scan.opportunistic = settings.getScanMode() == ScanSettings.SCAN_MODE_OPPORTUNISTIC;
          scan.background = (settings.getCallbackType() & ScanSettings.CALLBACK_TYPE_FIRST_MATCH) != 0;
        }
        lastScans.add(scan);

        BluetoothProto.ScanEvent scanEvent = new BluetoothProto.ScanEvent();
        scanEvent.setScanEventType(BluetoothProto.ScanEvent.SCAN_EVENT_START);
        scanEvent.setScanTechnologyType(BluetoothProto.ScanEvent.SCAN_TECH_TYPE_LE);
        scanEvent.setInitiator(appName);
        scanEvent.setEventTimeMillis(System.currentTimeMillis());
        contextMap.addScanEvent(scanEvent);
    }

    synchronized void recordScanStop() {
        if (!isScanning)
          return;

        this.scansStopped++;
        isScanning = false;
        stopTime = System.currentTimeMillis();
        long scanDuration = stopTime - startTime;

        minScanTime = Math.min(scanDuration, minScanTime);
        maxScanTime = Math.max(scanDuration, maxScanTime);
        totalScanTime += scanDuration;

        LastScan curr = lastScans.get(lastScans.size() - 1);
        curr.duration = scanDuration;

        if (lastScans.size() > NUM_SCAN_DURATIONS_KEPT) {
            lastScans.remove(0);
        }

        BluetoothProto.ScanEvent scanEvent = new BluetoothProto.ScanEvent();
        scanEvent.setScanEventType(BluetoothProto.ScanEvent.SCAN_EVENT_STOP);
        scanEvent.setScanTechnologyType(BluetoothProto.ScanEvent.SCAN_TECH_TYPE_LE);
        scanEvent.setInitiator(appName);
        scanEvent.setEventTimeMillis(System.currentTimeMillis());
        contextMap.addScanEvent(scanEvent);
    }

    synchronized void dumpToString(StringBuilder sb) {
        long currTime = System.currentTimeMillis();
        long maxScan = maxScanTime;
        long minScan = minScanTime;
        long scanDuration = 0;

        if (lastScans.isEmpty())
            return;

        if (isScanning) {
            scanDuration = currTime - startTime;
            minScan = Math.min(scanDuration, minScan);
            maxScan = Math.max(scanDuration, maxScan);
        }

        if (minScan == Long.MAX_VALUE) {
            minScan = 0;
        }

        long avgScan = 0;
        if (scansStarted > 0) {
            avgScan = (totalScanTime + scanDuration) / scansStarted;
        }

        LastScan lastScan = lastScans.get(lastScans.size() - 1);
        sb.append("  " + appName);
        if (isRegistered) sb.append(" (Registered)");
        if (lastScan.opportunistic) sb.append(" (Opportunistic)");
        if (lastScan.background) sb.append(" (Background)");
        sb.append("\n");

        sb.append("  LE scans (started/stopped)         : " +
                  scansStarted + " / " +
                  scansStopped + "\n");
        sb.append("  Scan time in ms (min/max/avg/total): " +
                  minScan + " / " +
                  maxScan + " / " +
                  avgScan + " / " +
                  totalScanTime + "\n");

        if (lastScans.size() != 0) {
            int lastScansSize = scansStopped < NUM_SCAN_DURATIONS_KEPT ?
                                scansStopped : NUM_SCAN_DURATIONS_KEPT;
            sb.append("  Last " + lastScansSize +
                      " scans                       :\n");

            for (int i = 0; i < lastScansSize; i++) {
                LastScan scan = lastScans.get(i);
                Date timestamp = new Date(scan.timestamp);
                sb.append("    " + dateFormat.format(timestamp) + " - ");
                sb.append(scan.duration + "ms ");
                if (scan.opportunistic) sb.append("Opp ");
                if (scan.background) sb.append("Back");
                sb.append("\n");
            }
        }

        if (isRegistered) {
            ContextMap.App appEntry = contextMap.getByName(appName);
            sb.append("  Application ID                     : " +
                      appEntry.id + "\n");
            sb.append("  UUID                               : " +
                      appEntry.uuid + "\n");

            if (isScanning) {
                sb.append("  Current scan duration in ms        : " +
                          scanDuration + "\n");
            }

            List<ContextMap.Connection> connections =
              contextMap.getConnectionByApp(appEntry.id);

            sb.append("  Connections: " + connections.size() + "\n");

            Iterator<ContextMap.Connection> ii = connections.iterator();
            while(ii.hasNext()) {
                ContextMap.Connection connection = ii.next();
                long connectionTime = System.currentTimeMillis() - connection.startTime;
                sb.append("    " + connection.connId + ": " +
                          connection.address + " " + connectionTime + "ms\n");
            }
        }
        sb.append("\n");
    }
}
