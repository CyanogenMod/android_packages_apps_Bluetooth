/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.opp;

/**
 * BluetoothDevicePickerBtStatus is a helper class that contains constants for
 * various status codes.
 */
public class BluetoothDevicePickerBtStatus {
    private static final String TAG = "BluetoothDevicePickerBtStatus";

    // Connection status

    public static final int CONNECTION_STATUS_UNKNOWN = 0;

    public static final int CONNECTION_STATUS_ACTIVE = 1;

    /** Use {@link #isConnected} to check for the connected state */
    public static final int CONNECTION_STATUS_CONNECTED = 2;

    public static final int CONNECTION_STATUS_CONNECTING = 3;

    public static final int CONNECTION_STATUS_DISCONNECTED = 4;

    public static final int CONNECTION_STATUS_DISCONNECTING = 5;

    public static final int getConnectionStatusSummary(int connectionStatus) {
        switch (connectionStatus) {
            case CONNECTION_STATUS_ACTIVE:
                return R.string.bluetooth_connected;
            case CONNECTION_STATUS_CONNECTED:
                return R.string.bluetooth_connected;
            case CONNECTION_STATUS_CONNECTING:
                return R.string.bluetooth_connecting;
            case CONNECTION_STATUS_DISCONNECTED:
                return R.string.bluetooth_disconnected;
            case CONNECTION_STATUS_DISCONNECTING:
                return R.string.bluetooth_disconnecting;
            case CONNECTION_STATUS_UNKNOWN:
                return R.string.bluetooth_unknown;
            default:
                return 0;
        }
    }

    public static final boolean isConnectionStatusConnected(int connectionStatus) {
        return connectionStatus == CONNECTION_STATUS_ACTIVE
                || connectionStatus == CONNECTION_STATUS_CONNECTED;
    }

    public static final boolean isConnectionStatusBusy(int connectionStatus) {
        return connectionStatus == CONNECTION_STATUS_CONNECTING
                || connectionStatus == CONNECTION_STATUS_DISCONNECTING;
    }

    // Pairing status

    public static final int PAIRING_STATUS_UNPAIRED = 0;

    public static final int PAIRING_STATUS_PAIRED = 1;

    public static final int PAIRING_STATUS_PAIRING = 2;

    public static final int getPairingStatusSummary(int pairingStatus) {
        switch (pairingStatus) {
            case PAIRING_STATUS_PAIRED:
                return R.string.bluetooth_paired;
            case PAIRING_STATUS_PAIRING:
                return R.string.bluetooth_pairing;
            case PAIRING_STATUS_UNPAIRED:
                return R.string.bluetooth_not_connected;
            default:
                return 0;
        }
    }
}
