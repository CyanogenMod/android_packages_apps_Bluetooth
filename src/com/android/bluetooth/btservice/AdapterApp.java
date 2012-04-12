/*
 * Copyright (C) 2012 Google Inc.
 */

/**
 * @hide
 */

package com.android.bluetooth.btservice;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.content.IntentFilter;
import android.util.Log;

public class AdapterApp extends Application {
    private static final String TAG = "BluetoothAdapterApp";
    private static final boolean DBG = true;

    static final String BLUETOOTH_ADMIN_PERM =
        android.Manifest.permission.BLUETOOTH_ADMIN;
    static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;


    @Override
    public void onCreate() {
        super.onCreate();
        if (DBG) Log.d(TAG, "onCreate");
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        if (DBG) Log.d(TAG, "finalize");
    }
}
