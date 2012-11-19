/*
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

import java.util.HashMap;

import com.android.bluetooth.Utils;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

public abstract class ProfileService extends Service {
    private static final boolean DBG = false;
    //For Debugging only
    private static HashMap<String, Integer> sReferenceCount = new HashMap<String,Integer>();

    public static final String BLUETOOTH_ADMIN_PERM =
            android.Manifest.permission.BLUETOOTH_ADMIN;
    public static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    public static interface IProfileServiceBinder extends IBinder {
        public boolean cleanup();
    }
    //Profile services will not be automatically restarted.
    //They must be explicitly restarted by AdapterService
    private static final int PROFILE_SERVICE_MODE=Service.START_NOT_STICKY;
    protected String mName;
    protected BluetoothAdapter mAdapter;
    protected IProfileServiceBinder mBinder;
    protected boolean mStartError=false;
    private boolean mCleaningUp = false;

    protected String getName() {
        return getClass().getSimpleName();
    }

    protected boolean isAvailable() {
        return !mStartError && !mCleaningUp;
    }

    protected abstract IProfileServiceBinder initBinder();
    protected abstract boolean start();
    protected abstract boolean stop();
    protected boolean cleanup() {
        return true;
    }

    protected ProfileService() {
        mName = getName();
        if (DBG) {
            synchronized (sReferenceCount) {
                Integer refCount = sReferenceCount.get(mName);
                if (refCount==null) {
                    refCount = 1;
                } else {
                    refCount = refCount+1;
                }
                sReferenceCount.put(mName, refCount);
                log("REFCOUNT: CREATED. INSTANCE_COUNT=" +refCount);
            }
        }
    }

    protected void finalize() {
        if (DBG) {
            synchronized (sReferenceCount) {
                Integer refCount = sReferenceCount.get(mName);
                if (refCount!=null) {
                    refCount = refCount-1;
                } else {
                    refCount = 0;
                }
                sReferenceCount.put(mName, refCount);
                log("REFCOUNT: FINALIZED. INSTANCE_COUNT=" +refCount);
            }
        }
    }

    @Override
    public void onCreate() {
        log("onCreate");
        super.onCreate();
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mBinder = initBinder();
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        log("onStartCommand()");
        if (mStartError || mAdapter == null) {
            Log.w(mName, "Stopping profile service: device does not have BT");
            doStop(intent);
            return PROFILE_SERVICE_MODE;
        }

        if (checkCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM)!=PackageManager.PERMISSION_GRANTED) {
            Log.e(mName, "Permission denied!");
            return PROFILE_SERVICE_MODE;
        }

        if (intent == null) {
            Log.d(mName, "Restarting profile service...");
            return PROFILE_SERVICE_MODE;
        } else {
            String action = intent.getStringExtra(AdapterService.EXTRA_ACTION);
            if (AdapterService.ACTION_SERVICE_STATE_CHANGED.equals(action)) {
                int state= intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if(state==BluetoothAdapter.STATE_OFF) {
                    Log.d(mName, "Received stop request...Stopping profile...");
                    doStop(intent);
                } else if (state == BluetoothAdapter.STATE_ON) {
                    Log.d(mName, "Received start request. Starting profile...");
                    doStart(intent);
                }
            }
        }
        return PROFILE_SERVICE_MODE;
    }

    public IBinder onBind(Intent intent) {
        log("onBind");
        return mBinder;
    }

    public boolean onUnbind(Intent intent) {
        log("onUnbind");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        log("Destroying service.");
        if (mCleaningUp) {
            log("Cleanup already started... Skipping cleanup()...");
        } else {
            log("cleanup()");
            mCleaningUp = true;
            cleanup();
            if (mBinder != null) {
                mBinder.cleanup();
                mBinder= null;
            }
        }
        super.onDestroy();
        mAdapter = null;
    }

    private void doStart(Intent intent) {
        //Start service
        if (mAdapter == null) {
            Log.e(mName, "Error starting profile. BluetoothAdapter is null");
        } else {
            log("start()");
            mStartError = !start();
            if (!mStartError) {
                notifyProfileServiceStateChanged(BluetoothAdapter.STATE_ON);
            } else {
                Log.e(mName, "Error starting profile. BluetoothAdapter is null");
            }
        }
    }

    private void doStop(Intent intent) {
        if (stop()) {
            log("stop()");
            notifyProfileServiceStateChanged(BluetoothAdapter.STATE_OFF);
            stopSelf();
        } else {
            Log.e(mName, "Unable to stop profile");
        }
    }

    protected void notifyProfileServiceStateChanged(int state) {
        //Notify adapter service
        AdapterService sAdapter = AdapterService.getAdapterService();
        if (sAdapter!= null) {
            sAdapter.onProfileServiceStateChanged(getClass().getName(), state);
        }
    }

    public void notifyProfileConnectionStateChanged(BluetoothDevice device,
            int profileId, int newState, int prevState) {
        AdapterService svc = AdapterService.getAdapterService();
        if (svc != null) {
            svc.onProfileConnectionStateChanged(device, profileId, newState, prevState);
        }
    }

    protected BluetoothDevice getDevice(byte[] address) {
        return mAdapter.getRemoteDevice(Utils.getAddressStringFromByte(address));
    }

    protected void log(String msg) {
        Log.d(mName, msg);
    }
}
