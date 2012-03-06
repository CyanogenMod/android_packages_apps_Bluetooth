/*
 * Copyright (C) 2012 Google Inc.
 */

package com.android.bluetooth.btservice;

/*
 * @hide
 */

final public class AbstractionLayer {
    // Do not modify without upating the HAL files.

    // TODO: Some of the constants are repeated from BluetoothAdapter.java.
    // Get rid of them and maintain just one.
    static final int BT_STATE_OFF = 0x00;
    static final int BT_STATE_ON = 0x01;

    static final int BT_SCAN_MODE_NONE = 0x00;
    static final int BT_SCAN_MODE_CONNECTABLE = 0x01;
    static final int BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE = 0x02;

    static final int BT_PROPERTY_BDNAME = 0x01;
    static final int BT_PROPERTY_BDADDR = 0x02;
    static final int BT_PROPERTY_UUIDS = 0x03;
    static final int BT_PROPERTY_CLASS_OF_DEVICE = 0x04;
    static final int BT_PROPERTY_TYPE_OF_DEVICE = 0x05;
    static final int BT_PROPERTY_SERVICE_RECORD = 0x06;
    static final int BT_PROPERTY_ADAPTER_SCAN_MODE = 0x07;
    static final int BT_PROPERTY_ADAPTER_BONDED_DEVICES = 0x08;
    static final int BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT = 0x09;

    static final int BT_PROPERTY_REMOTE_FRIENDLY_NAME = 0x10;
    static final int BT_PROPERTY_REMOTE_RSSI = 0x11;

    static final int BT_DEVICE_TYPE_BREDR = 0x01;
    static final int BT_DEVICE_TYPE_BLE = 0x02;
    static final int BT_DEVICE_TYPE_DUAL = 0x03;

    static final int BT_BOND_STATE_NONE = 0x00;
    static final int BT_BOND_STATE_BONDED = 0x01;

    static final int BT_SSP_VARIANT_PASSKEY_CONFIRMATION = 0x00;
    static final int BT_SSP_VARIANT_PASSKEY_ENTRY = 0x01;
    static final int BT_SSP_VARIANT_CONSENT = 0x02;
    static final int BT_SSP_VARIANT_DISPLAY_PASSKEY = 0x03;

    static final int BT_DISCOVERY_STOPPED = 0x00;
    static final int BT_DISCOVERY_STARTED = 0x01;

    static final int BT_UUID_SIZE = 16; // bytes
}
