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
import android.os.Bundle;
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
    private static final int MESSAGE_GET_PROTOCOL_MODE = 4;
    private static final int MESSAGE_VIRTUAL_UNPLUG = 5;
    private static final int MESSAGE_ON_GET_PROTOCOL_MODE = 6;
    private static final int MESSAGE_SET_PROTOCOL_MODE = 7;
    private static final int MESSAGE_GET_REPORT = 8;
    private static final int MESSAGE_ON_GET_REPORT = 9;
    private static final int MESSAGE_SET_REPORT = 10;
    private static final int MESSAGE_SEND_DATA = 11;
    private static final int MESSAGE_ON_VIRTUAL_UNPLUG = 12;

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
                case MESSAGE_GET_PROTOCOL_MODE:
                {
                    BluetoothDevice device = (BluetoothDevice) msg.obj;
                    if(!getProtocolModeNative(getByteAddress(device)) ) {
                        Log.e(TAG, "Error: get protocol mode native returns false");
                    }
                }
                break;

                case MESSAGE_ON_GET_PROTOCOL_MODE:
                {
                    BluetoothDevice device = getDevice((byte[]) msg.obj);
                    int protocolMode = msg.arg1;
                    broadcastProtocolMode(device, protocolMode);
                }
                break;
                case MESSAGE_VIRTUAL_UNPLUG:
                {
                    BluetoothDevice device = (BluetoothDevice) msg.obj;
                    if(!virtualUnPlugNative(getByteAddress(device))) {
                        Log.e(TAG, "Error: virtual unplug native returns false");
                    }
                }
                break;
                case MESSAGE_SET_PROTOCOL_MODE:
                {
                    BluetoothDevice device = (BluetoothDevice) msg.obj;
                    byte protocolMode = (byte) msg.arg1;
                    log("sending set protocol mode(" + protocolMode + ")");
                    if(!setProtocolModeNative(getByteAddress(device), protocolMode)) {
                        Log.e(TAG, "Error: set protocol mode native returns false");
                    }
                }
                break;
                case MESSAGE_GET_REPORT:
                {
                    BluetoothDevice device = (BluetoothDevice) msg.obj;
                    Bundle data = msg.getData();
                    byte reportType = data.getByte(BluetoothInputDevice.EXTRA_REPORT_TYPE);
                    byte reportId = data.getByte(BluetoothInputDevice.EXTRA_REPORT_ID);
                    int bufferSize = data.getInt(BluetoothInputDevice.EXTRA_REPORT_BUFFER_SIZE);
                    if(!getReportNative(getByteAddress(device), reportType, reportId, bufferSize)) {
                        Log.e(TAG, "Error: get report native returns false");
                    }
                }
                break;
                case MESSAGE_SET_REPORT:
                {
                    BluetoothDevice device = (BluetoothDevice) msg.obj;
                    Bundle data = msg.getData();
                    byte reportType = data.getByte(BluetoothInputDevice.EXTRA_REPORT_TYPE);
                    String report = data.getString(BluetoothInputDevice.EXTRA_REPORT);
                    if(!setReportNative(getByteAddress(device), reportType, report)) {
                        Log.e(TAG, "Error: set report native returns false");
                    }
                }
                break;
                case MESSAGE_SEND_DATA:
                {
                    BluetoothDevice device = (BluetoothDevice) msg.obj;
                    Bundle data = msg.getData();
                    String report = data.getString(BluetoothInputDevice.EXTRA_REPORT);
                    if(!sendDataNative(getByteAddress(device), report)) {
                        Log.e(TAG, "Error: send data native returns false");
                    }
                }
                break;
                case MESSAGE_ON_VIRTUAL_UNPLUG:
                {
                    BluetoothDevice device = getDevice((byte[]) msg.obj);
                    int status = msg.arg1;
                    broadcastVirtualUnplugStatus(device, status);
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

        public boolean getProtocolMode(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            int state = this.getConnectionState(device);
            if (state != BluetoothInputDevice.STATE_CONNECTED) {
                return false;
            }
            Message msg = mHandler.obtainMessage(MESSAGE_GET_PROTOCOL_MODE);
            msg.obj = device;
            mHandler.sendMessage(msg);
            return true;
            /* String objectPath = getObjectPathFromAddress(device.getAddress());
                return getProtocolModeInputDeviceNative(objectPath);*/
        }

        public boolean virtualUnplug(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            int state = this.getConnectionState(device);
            if (state != BluetoothInputDevice.STATE_CONNECTED) {
                return false;
            }
            Message msg = mHandler.obtainMessage(MESSAGE_VIRTUAL_UNPLUG);
            msg.obj = device;
            mHandler.sendMessage(msg);

            return true;
        }

        public boolean setProtocolMode(BluetoothDevice device, int protocolMode) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            int state = this.getConnectionState(device);
            if (state != BluetoothInputDevice.STATE_CONNECTED) {
                return false;
            }
            Message msg = mHandler.obtainMessage(MESSAGE_SET_PROTOCOL_MODE);
            msg.obj = device;
            msg.arg1 = protocolMode;
            mHandler.sendMessage(msg);
            return true ;
        }

        public boolean getReport(BluetoothDevice device, byte reportType, byte reportId, int bufferSize) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            int state = this.getConnectionState(device);
            if (state != BluetoothInputDevice.STATE_CONNECTED) {
                return false;
            }
            Message msg = mHandler.obtainMessage(MESSAGE_GET_REPORT);
            msg.obj = device;
            Bundle data = new Bundle();
            data.putByte(BluetoothInputDevice.EXTRA_REPORT_TYPE, reportType);
            data.putByte(BluetoothInputDevice.EXTRA_REPORT_ID, reportId);
            data.putInt(BluetoothInputDevice.EXTRA_REPORT_BUFFER_SIZE, bufferSize);
            msg.setData(data);
            mHandler.sendMessage(msg);
            return true ;
        }

        public boolean setReport(BluetoothDevice device, byte reportType, String report) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                       "Need BLUETOOTH_ADMIN permission");
            int state = this.getConnectionState(device);
            if (state != BluetoothInputDevice.STATE_CONNECTED) {
                return false;
            }
            Message msg = mHandler.obtainMessage(MESSAGE_SET_REPORT);
            msg.obj = device;
            Bundle data = new Bundle();
            data.putByte(BluetoothInputDevice.EXTRA_REPORT_TYPE, reportType);
            data.putString(BluetoothInputDevice.EXTRA_REPORT, report);
            msg.setData(data);
            mHandler.sendMessage(msg);
            return true ;

        }

        public boolean sendData(BluetoothDevice device, String report) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                                       "Need BLUETOOTH_ADMIN permission");
            int state = this.getConnectionState(device);
            if (state != BluetoothInputDevice.STATE_CONNECTED) {
                return false;
            }

            return sendDataNative(getByteAddress(device), report);
            /*Message msg = mHandler.obtainMessage(MESSAGE_SEND_DATA);
            msg.obj = device;
            Bundle data = new Bundle();
            data.putString(BluetoothInputDevice.EXTRA_REPORT, report);
            msg.setData(data);
            mHandler.sendMessage(msg);
            return true ;*/
        }
    };

    private void onGetProtocolMode(byte[] address, int mode) {
        Message msg = mHandler.obtainMessage(MESSAGE_ON_GET_PROTOCOL_MODE);
        msg.obj = address;
        msg.arg1 = mode;
        mHandler.sendMessage(msg);
    }

    private void onVirtualUnplug(byte[] address, int status) {
        Message msg = mHandler.obtainMessage(MESSAGE_ON_VIRTUAL_UNPLUG);
        msg.obj = address;
        msg.arg1 = status;
        mHandler.sendMessage(msg);
    }

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

    private void broadcastProtocolMode(BluetoothDevice device, int protocolMode) {
        Intent intent = new Intent(BluetoothInputDevice.ACTION_PROTOCOL_MODE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothInputDevice.EXTRA_PROTOCOL_MODE, protocolMode);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        sendBroadcast(intent, BLUETOOTH_PERM);
        if (DBG) log("Protocol Mode (" + device + "): " + protocolMode);
    }

    private void broadcastVirtualUnplugStatus(BluetoothDevice device, int status) {
        Intent intent = new Intent(BluetoothInputDevice.ACTION_VIRTUAL_UNPLUG_STATUS);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothInputDevice.EXTRA_VIRTUAL_UNPLUG_STATUS, status);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        sendBroadcast(intent, BLUETOOTH_PERM);
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
    private native boolean getProtocolModeNative(byte[] btAddress);
    private native boolean virtualUnPlugNative(byte[] btAddress);
    private native boolean setProtocolModeNative(byte[] btAddress, byte protocolMode);
    private native boolean getReportNative(byte[]btAddress, byte reportType, byte reportId, int bufferSize);
    private native boolean setReportNative(byte[] btAddress, byte reportType, String report);
    private native boolean sendDataNative(byte[] btAddress, String report);
}
