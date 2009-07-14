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

import com.android.bluetooth.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;
import android.util.Log;
import android.util.Config;

import java.util.ArrayList;
import java.util.List;

public class BluetoothDevicePickerManager {
    private static final boolean DEBUG = true;

    private static final boolean LOG_ENABLED = DEBUG ? Config.LOGD : Config.LOGV;

    private static final String TAG = "-------------BluetoothDevicePickerManager";

    static final boolean V = true;

    public static final String EXTENDED_BLUETOOTH_STATE_CHANGED_ACTION = "com.android.settings.bluetooth.intent.action.EXTENDED_BLUETOOTH_STATE_CHANGED";

    private static final String SHARED_PREFERENCES_NAME = "bluetooth_settings";

    private static BluetoothDevicePickerManager INSTANCE;

    /** Used when obtaining a reference to the singleton instance. */
    private static Object INSTANCE_LOCK = new Object();

    private boolean mInitialized;

    private boolean mEventListenerRunning;

    private Context mContext;

    /** If a BT-related activity is in the foreground, this will be it. */
    private Activity mForegroundActivity;

    private BluetoothDevice mManager;

    private BluetoothDevicePickerDeviceManager mLocalDeviceManager;

    private BluetoothDevicePickerListener mEventListener;

    public static enum ExtendedBluetoothState {
        ENABLED, ENABLING, DISABLED, DISABLING, UNKNOWN
    }

    private ExtendedBluetoothState mState = ExtendedBluetoothState.UNKNOWN;

    private List<Callback> mCallbacks = new ArrayList<Callback>();

    private static final int SCAN_EXPIRATION_MS = 5 * 60 * 1000; // 5 mins

    private long mLastScan;

    public static BluetoothDevicePickerManager getInstance(Context context) {
        synchronized (INSTANCE_LOCK) {
            if (INSTANCE == null) {
                INSTANCE = new BluetoothDevicePickerManager();
            }

            if (!INSTANCE.init(context)) {
                return null;
            }

            return INSTANCE;
        }
    }

    private boolean init(Context context) {
        if (mInitialized)
            return true;
        mInitialized = true;
        log("init");

        // This will be around as long as this process is
        mContext = context.getApplicationContext();

        mManager = (BluetoothDevice)context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (mManager == null) {
            log("has no bluetooth service, exit");
            return false;
        }

        mLocalDeviceManager = new BluetoothDevicePickerDeviceManager(this);

        mEventListener = new BluetoothDevicePickerListener(this);
        if (startBluetoothEventListener() != 0)
            return false;

        return true;
    }

    public BluetoothDevice getBluetoothManager() {
        return mManager;
    }

    public int stopBluetoothEventListener() {
        if (!mEventListenerRunning)
            return 0;
        mEventListenerRunning = false;

        if (mEventListener != null) {
            log("stopBluetoothEventListener");
            mEventListener.stop();
            return 0;
        }

        return -1;
    }

    public int startBluetoothEventListener() {
        if (mEventListenerRunning)
            return 0;
        mEventListenerRunning = true;

        if (mEventListener != null) {
            log("startBluetoothEventListener");
            mEventListener.start();
            return 0;
        }

        return -1;
    }

    public Context getContext() {
        return mContext;
    }

    public Activity getForegroundActivity() {
        return mForegroundActivity;
    }

    public void setForegroundActivity(Activity activity) {
        mForegroundActivity = activity;
    }

    public SharedPreferences getSharedPreferences() {
        return mContext.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public BluetoothDevicePickerDeviceManager getLocalDeviceManager() {
        return mLocalDeviceManager;
    }

    List<Callback> getCallbacks() {
        return mCallbacks;
    }

    public void registerCallback(Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.add(callback);
        }
    }

    public void unregisterCallback(Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
        }
    }

    public void startScanning(boolean force) {
        log("startScanning1");
        if (mManager.isDiscovering()) {
            log("startScanning2");
            dispatchScanningStateChanged(true);
            log("startScanning3");
        } else {
            log("startScanning4");
            if (!force && mLastScan + SCAN_EXPIRATION_MS > System.currentTimeMillis())
                return;

            log("startScanning5");
            if (mManager.startDiscovery()) {
                mLastScan = System.currentTimeMillis();
            }
        }
    }

    public ExtendedBluetoothState getBluetoothState() {

        if (mState == ExtendedBluetoothState.UNKNOWN) {
            syncBluetoothState();
        }

        return mState;
    }

    void setBluetoothStateInt(ExtendedBluetoothState state) {
        mState = state;

        mContext.sendBroadcast(new Intent(EXTENDED_BLUETOOTH_STATE_CHANGED_ACTION));

        if (state == ExtendedBluetoothState.ENABLED || state == ExtendedBluetoothState.DISABLED) {
            // mLocalDeviceManager.onBluetoothStateChanged(state ==
            // ExtendedBluetoothState.ENABLED);
        }
    }

    private void syncBluetoothState() {
        setBluetoothStateInt(mManager.isEnabled() ? ExtendedBluetoothState.ENABLED
                : ExtendedBluetoothState.DISABLED);
    }

    public void setBluetoothEnabled(boolean enabled) {
        boolean wasSetStateSuccessful = enabled ? mManager.enable() : mManager.disable();

        if (wasSetStateSuccessful) {
            setBluetoothStateInt(enabled ? ExtendedBluetoothState.ENABLING
                    : ExtendedBluetoothState.DISABLING);
        } else {
            if (V) {
                Log.v(TAG, "setBluetoothEnabled call, manager didn't return success for enabled: "
                        + enabled);
            }

            syncBluetoothState();
        }
    }

    void onScanningStateChanged(boolean started) {
        mLocalDeviceManager.onScanningStateChanged(started);
        log("onScanningStateChanged");
        dispatchScanningStateChanged(started);
    }

    void onBondingStateChanged(String address, boolean created) {
        log("onBondStateChanged1");
        synchronized (mCallbacks) {
            for (Callback callback : mCallbacks) {
                log("onBondStateChanged2");
                callback.onBondingStateChanged(address, created);
            }
        }
    }

    private void dispatchScanningStateChanged(boolean started) {
        log("dispatchScanningStateChanged1");
        synchronized (mCallbacks) {
            for (Callback callback : mCallbacks) {
                log("dispatchScanningStateChanged2");
                callback.onScanningStateChanged(started);
            }
        }
    }

    public boolean createBonding(String address) {
        return mManager.createBond(address);
    }

    public void showError(String address, int titleResId, int messageResId) {
        BluetoothDevicePickerDevice device = mLocalDeviceManager.findDevice(address);
        if (device == null)
            return;

        String name = device.getName();
        String message = mContext.getString(messageResId, name);

        if (mForegroundActivity != null) {
            // Need an activity context to show a dialog
            AlertDialog ad = new AlertDialog.Builder(mForegroundActivity).setIcon(
                    android.R.drawable.ic_dialog_alert).setTitle(titleResId).setMessage(message)
                    .setPositiveButton(android.R.string.ok, null).show();
        } else {
            // Fallback on a toast
            Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
        }
    }

    public interface Callback {
        void onScanningStateChanged(boolean started);

        void onDeviceAdded(BluetoothDevicePickerDevice device);

        void onDeviceDeleted(BluetoothDevicePickerDevice device);

        void onBondingStateChanged(String address, boolean created);
    }

    static void log(String message) {
        if (LOG_ENABLED) {
            Log.v(TAG, message);
        }
    }
}
