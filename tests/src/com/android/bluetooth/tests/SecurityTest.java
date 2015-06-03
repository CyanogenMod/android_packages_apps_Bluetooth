package com.android.bluetooth.tests;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothUuid;
import android.test.AndroidTestCase;
import android.util.Log;

import java.io.IOException;

public class SecurityTest extends AndroidTestCase {
    static final String TAG = "SecurityTest";

    public void connectSapNoSec() {
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if(bt == null) {
            Log.e(TAG,"No Bluetooth Device!");
            assertTrue(false);
        }

        BluetoothTestUtils.enableBt(bt);
        BluetoothDevice serverDevice = bt.getRemoteDevice(ObexTest.SERVER_ADDRESS);
        try {
            serverDevice.createInsecureRfcommSocketToServiceRecord(BluetoothUuid.SAP.getUuid());
        } catch (IOException e) {
            Log.e(TAG, "Failed to create connection", e);
        }

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Log.w(TAG, "Sleep interrupted", e);
        }
    }
}
