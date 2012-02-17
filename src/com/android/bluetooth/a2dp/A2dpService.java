/*
 * Copyright (C) 2012 Google Inc.
 */
package com.android.bluetooth.a2dp;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothA2dp;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import java.util.List;

/**
 * Provides Bluetooth A2DP profile, as a service in the Bluetooth application.
 * @hide
 */
public class A2dpService extends Service {
    private static final String TAG = "BluetoothA2dpService";
    private static final boolean DBG = true;

    static final String BLUETOOTH_ADMIN_PERM =
        android.Manifest.permission.BLUETOOTH_ADMIN;
    static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private BluetoothAdapter mAdapter;
    private A2dpStateMachine mStateMachine;

    @Override
    public void onCreate() {
        log("onCreate");
        super.onCreate();
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mAdapter == null) {
            // no bluetooth on this device
            return;
        }
        mStateMachine = new A2dpStateMachine(this);
        mStateMachine.start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        log("onBind");
        return mBinder;
    }

    public void onStart(Intent intent, int startId) {
        log("onStart");
        if (mAdapter == null) {
            Log.w(TAG, "Stopping Bluetooth A2dpService: device does not have BT");
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DBG) log("Stopping Bluetooth A2dpService");
    }

    /**
     * Handlers for incoming service calls
     */
    private final IBluetoothA2dp.Stub mBinder = new IBluetoothA2dp.Stub() {

        public boolean connect(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH ADMIN permission");

            if (getPriority(device) == BluetoothProfile.PRIORITY_OFF) {
                return false;
            }

            int connectionState = mStateMachine.getConnectionState(device);
            if (connectionState == BluetoothProfile.STATE_CONNECTED ||
                connectionState == BluetoothProfile.STATE_CONNECTING) {
                return false;
            }

            mStateMachine.sendMessage(A2dpStateMachine.CONNECT, device);
            return true;
        }

        public boolean disconnect(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH ADMIN permission");
            int connectionState = mStateMachine.getConnectionState(device);
            if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
                return false;
            }

            mStateMachine.sendMessage(A2dpStateMachine.DISCONNECT, device);
            return true;
        }

        public List<BluetoothDevice> getConnectedDevices() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mStateMachine.getConnectedDevices();
        }

        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mStateMachine.getDevicesMatchingConnectionStates(states);
        }

        public int getConnectionState(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mStateMachine.getConnectionState(device);
        }

        public boolean setPriority(BluetoothDevice device, int priority) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            Settings.Secure.putInt(getContentResolver(),
                Settings.Secure.getBluetoothA2dpSinkPriorityKey(device.getAddress()),
                priority);
            if (DBG) log("Saved priority " + device + " = " + priority);
            return true;
        }

        public int getPriority(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            int priority = Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.getBluetoothA2dpSinkPriorityKey(device.getAddress()),
                BluetoothProfile.PRIORITY_UNDEFINED);
            return priority;
        }

        public synchronized boolean isA2dpPlaying(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                           "Need BLUETOOTH permission");
            if (DBG) log("isA2dpPlaying(" + device + ")");
            return mStateMachine.isPlaying(device);
        }
    };

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
