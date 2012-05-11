/*
 * Copyright (C) 2012 Google Inc.
 */

/**
 * @hide
 */

package com.android.bluetooth.btservice;

import android.app.Application;
import android.util.Log;

public class AdapterApp extends Application {
    private static final String TAG = "BluetoothAdapterApp";
    private static final boolean DBG = true;
    //For Debugging only
    private static int sRefCount=0;

    static {
        if (DBG) Log.d(TAG,"Loading JNI Library");
        System.loadLibrary("bluetooth_jni");
    }

    public AdapterApp() {
        super();
        if (DBG) {
            synchronized (AdapterApp.class) {
                sRefCount++;
                Log.d(TAG, "REFCOUNT: Constructed "+ this + " Instance Count = " + sRefCount);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (DBG) Log.d(TAG, "onCreate");
        Config.init(this);
    }

    @Override
    protected void finalize() {
        if (DBG) {
            synchronized (AdapterApp.class) {
                sRefCount--;
                Log.d(TAG, "REFCOUNT: Finalized: " + this +", Instance Count = " + sRefCount);
            }
        }
    }
}
