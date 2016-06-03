/*
 * Copyright (C) 2016 The Linux Foundation. All rights reserved
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.bluetooth.btservice;

import android.util.Log;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;

final class Vendor {
    private static final String TAG = "BluetoothVendorService";
    private AdapterService mService;

    static {
        classInitNative();
    }

    public Vendor(AdapterService service) {
        mService = service;
    }

    public void init(){
        initNative();
    }

    public void ssrCleanup(boolean cleanup) {
        ssrcleanupNative(cleanup);
    }

    public void bredrCleanup() {
        bredrcleanupNative();
    }

    public void cleanup() {
        cleanupNative();
    }

   private void onBredrCleanup(boolean status) {
        Log.d(TAG,"BREDR cleanup done");
        mService.startBluetoothDisable();
    }

    private native void ssrcleanupNative(boolean cleanup);
    private native void bredrcleanupNative();
    private native void initNative();
    private native static void classInitNative();
    private native void cleanupNative();
}
