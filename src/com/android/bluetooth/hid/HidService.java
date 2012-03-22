/*
 * Copyright (C) 2012 Google Inc.
 */

package com.android.bluetooth.hid;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothInputDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothInputDevice;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.android.bluetooth.Utils;

/**
 * Provides Bluetooth Hid Device profile, as a service in
 * the Bluetooth application.
 * @hide
 */
public class HidService extends Service {
    private static final String TAG = "BluetoothHidService";
    private static final boolean DBG = true;

    static final String BLUETOOTH_ADMIN_PERM =
        android.Manifest.permission.BLUETOOTH_ADMIN;
    static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private BluetoothAdapter mAdapter;
    private IBluetooth mAdapterService;
    private Map<BluetoothDevice, Integer> mInputDevices;

    private static final int MESSAGE_CONNECT = 1;
    private static final int MESSAGE_DISCONNECT = 2;
    private static final int MESSAGE_CONNECT_STATE_CHANGED = 3;

    static {
        classInitNative();
    }

    @Override
    public void onCreate() {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mAdapterService = IBluetooth.Stub.asInterface(ServiceManager.getService("bluetooth"));
        mInputDevices = Collections.synchronizedMap(new HashMap<BluetoothDevice, Integer>());
        initializeNativeDataNative();
    }

    @Override
    public IBinder onBind(Intent intent) {
        log("onBind");
        return mBinder;
    }

    @Override
    public void onStart(Intent intent, int startId) {
        log("onStart");
        if (mAdapter == null) {
            Log.w(TAG, "Stopping Bluetooth HidService: device does not have BT");
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DBG) log("Stopping Bluetooth HidService");
        // TBD native cleanup
    }

    private final Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_CONNECT:
                {
                    BluetoothDevice device = (BluetoothDevice) msg.obj;
                    if (!connectHidNative(getByteAddress(device)) ) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTING);
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED);
                        break;
                    }
                }
                    break;
                case MESSAGE_DISCONNECT:
                {
                    BluetoothDevice device = (BluetoothDevice) msg.obj;
                    if (!disconnectHidNative(getByteAddress(device)) ) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTING);
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED);
                        break;
                    }
                }
                    break;
                case MESSAGE_CONNECT_STATE_CHANGED:
                {
                    BluetoothDevice device = getDevice((byte[]) msg.obj);
                    int halState = msg.arg1;
                    broadcastConnectionState(device, convertHalState(halState));
                }
                    break;
            }
        }
    };

    /**
     * Handlers for incoming service calls
     */
    private final IBluetoothInputDevice.Stub mBinder = new IBluetoothInputDevice.Stub() {
        public boolean connect(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            if (getConnectionState(device) != BluetoothInputDevice.STATE_DISCONNECTED) {
                Log.e(TAG, "Hid Device not disconnected: " + device);
                return false;
            }
            if (getPriority(device) == BluetoothInputDevice.PRIORITY_OFF) {
                Log.e(TAG, "Hid Device PRIORITY_OFF: " + device);
                return false;
            }

            Message msg = mHandler.obtainMessage(MESSAGE_CONNECT);
            msg.obj = device;
            mHandler.sendMessage(msg);
            return true;
        }

        public boolean disconnect(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            Message msg = mHandler.obtainMessage(MESSAGE_DISCONNECT);
            msg.obj = device;
            mHandler.sendMessage(msg);
            return true;
        }

        public int getConnectionState(BluetoothDevice device) {
            if (mInputDevices.get(device) == null) {
                return BluetoothInputDevice.STATE_DISCONNECTED;
            }
            return mInputDevices.get(device);
        }

        public List<BluetoothDevice> getConnectedDevices() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            List<BluetoothDevice> devices = getDevicesMatchingConnectionStates(
                    new int[] {BluetoothProfile.STATE_CONNECTED});
            return devices;
        }

        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            List<BluetoothDevice> inputDevices = new ArrayList<BluetoothDevice>();

            for (BluetoothDevice device: mInputDevices.keySet()) {
                int inputDeviceState = getConnectionState(device);
                for (int state : states) {
                    if (state == inputDeviceState) {
                        inputDevices.add(device);
                        break;
                    }
                }
            }
            return inputDevices;
        }

        public boolean setPriority(BluetoothDevice device, int priority) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            Settings.Secure.putInt(getContentResolver(),
                Settings.Secure.getBluetoothInputDevicePriorityKey(device.getAddress()),
                priority);
            if (DBG) log("Saved priority " + device + " = " + priority);
            return true;
        }

        public int getPriority(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            int priority = Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.getBluetoothInputDevicePriorityKey(device.getAddress()),
                BluetoothProfile.PRIORITY_UNDEFINED);
            return priority;
        }

    };

    private void onConnectStateChanged(byte[] address, int state) {
        Message msg = mHandler.obtainMessage(MESSAGE_CONNECT_STATE_CHANGED);
        msg.obj = address;
        msg.arg1 = state;
        mHandler.sendMessage(msg);
    }

    // This method does not check for error conditon (newState == prevState)
    private void broadcastConnectionState(BluetoothDevice device, int newState) {
        Integer prevStateInteger = mInputDevices.get(device);
        int prevState = (prevStateInteger == null) ? BluetoothInputDevice.STATE_DISCONNECTED : 
                                                     prevStateInteger;
        if (prevState == newState) {
            Log.w(TAG, "no state change: " + newState);
            return;
        }
        mInputDevices.put(device, newState);

        Intent intent = new Intent(BluetoothInputDevice.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        sendBroadcast(intent, BLUETOOTH_PERM);
        if (DBG) log("Connection state " + device + ": " + prevState + "->" + newState);
        try {
            mAdapterService.sendConnectionStateChange(device, BluetoothProfile.INPUT_DEVICE, newState,
                                                      prevState);
        } catch (RemoteException e) {
            Log.e(TAG, Log.getStackTraceString(new Throwable()));
        }
    }

    private BluetoothDevice getDevice(byte[] address) {
        return mAdapter.getRemoteDevice(Utils.getAddressStringFromByte(address));
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
    }

    private int convertHalState(int halState) {
        switch (halState) {
            case CONN_STATE_CONNECTED:
                return BluetoothProfile.STATE_CONNECTED;
            case CONN_STATE_CONNECTING:
                return BluetoothProfile.STATE_CONNECTING;
            case CONN_STATE_DISCONNECTED:
                return BluetoothProfile.STATE_DISCONNECTED;
            case CONN_STATE_DISCONNECTING:
                return BluetoothProfile.STATE_DISCONNECTING;
            default:
                Log.e(TAG, "bad hid connection state: " + halState);
                return BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }

    // Constants matching Hal header file bt_hh.h
    // bthh_connection_state_t
    private final static int CONN_STATE_CONNECTED = 0;
    private final static int CONN_STATE_CONNECTING = 1;
    private final static int CONN_STATE_DISCONNECTED = 2;
    private final static int CONN_STATE_DISCONNECTING = 3;

    private native static void classInitNative();
    private native void initializeNativeDataNative();
    private native boolean connectHidNative(byte[] btAddress);
    private native boolean disconnectHidNative(byte[] btAddress);
}
