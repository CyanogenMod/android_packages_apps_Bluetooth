/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.bluetooth.avrcp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAvrcpController;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothAvrcpController;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;

/**
 * Provides Bluetooth AVRCP Controller profile, as a service in the Bluetooth application.
 * @hide
 */
public class AvrcpControllerService extends ProfileService {
    private static final boolean DBG = false;
    private static final String TAG = "AvrcpControllerService";

    private static final int MESSAGE_SEND_PASS_THROUGH_CMD = 1;

    private AvrcpMessageHandler mHandler;
    private static AvrcpControllerService sAvrcpControllerService;

    private final ArrayList<BluetoothDevice> mConnectedDevices
            = new ArrayList<BluetoothDevice>();

    static {
        classInitNative();
    }

    public AvrcpControllerService() {
        initNative();
    }

    protected String getName() {
        return TAG;
    }

    protected IProfileServiceBinder initBinder() {
        return new BluetoothAvrcpControllerBinder(this);
    }

    protected boolean start() {
        HandlerThread thread = new HandlerThread("BluetoothAvrcpHandler");
        thread.start();
        Looper looper = thread.getLooper();
        mHandler = new AvrcpMessageHandler(looper);

        setAvrcpControllerService(this);
        return true;
    }

    protected boolean stop() {
        return true;
    }

    protected boolean cleanup() {
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            Looper looper = mHandler.getLooper();
            if (looper != null) looper.quit();
        }

        clearAvrcpControllerService();
        cleanupNative();

        return true;
    }

    //API Methods

    public static synchronized AvrcpControllerService getAvrcpControllerService(){
        if (sAvrcpControllerService != null && sAvrcpControllerService.isAvailable()) {
            if (DBG) Log.d(TAG, "getAvrcpControllerService(): returning "
                    + sAvrcpControllerService);
            return sAvrcpControllerService;
        }
        if (DBG)  {
            if (sAvrcpControllerService == null) {
                Log.d(TAG, "getAvrcpControllerService(): service is NULL");
            } else if (!(sAvrcpControllerService.isAvailable())) {
                Log.d(TAG,"getAvrcpControllerService(): service is not available");
            }
        }
        return null;
    }

    private static synchronized void setAvrcpControllerService(AvrcpControllerService instance) {
        if (instance != null && instance.isAvailable()) {
            if (DBG) Log.d(TAG, "setAvrcpControllerService(): set to: " + sAvrcpControllerService);
            sAvrcpControllerService = instance;
        } else {
            if (DBG)  {
                if (sAvrcpControllerService == null) {
                    Log.d(TAG, "setAvrcpControllerService(): service not available");
                } else if (!sAvrcpControllerService.isAvailable()) {
                    Log.d(TAG,"setAvrcpControllerService(): service is cleaning up");
                }
            }
        }
    }

    private static synchronized void clearAvrcpControllerService() {
        sAvrcpControllerService = null;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mConnectedDevices;
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        for (int i = 0; i < states.length; i++) {
            if (states[i] == BluetoothProfile.STATE_CONNECTED) {
                return mConnectedDevices;
            }
        }
        return new ArrayList<BluetoothDevice>();
    }

    int getConnectionState(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return (mConnectedDevices.contains(device) ? BluetoothProfile.STATE_CONNECTED
                                                : BluetoothProfile.STATE_DISCONNECTED);
    }

    public void sendPassThroughCmd(BluetoothDevice device, int keyCode, int keyState) {
        if (DBG) Log.d(TAG, "sendPassThroughCmd");
        Log.v(TAG, "keyCode: " + keyCode + " keyState: " + keyState);
        if (device == null) {
            throw new NullPointerException("device == null");
        }
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        Message msg = mHandler.obtainMessage(MESSAGE_SEND_PASS_THROUGH_CMD,
                keyCode, keyState, device);
        mHandler.sendMessage(msg);
    }

    //Binder object: Must be static class or memory leak may occur
    private static class BluetoothAvrcpControllerBinder extends IBluetoothAvrcpController.Stub
        implements IProfileServiceBinder {
        private AvrcpControllerService mService;

        private AvrcpControllerService getService() {
            if (!Utils.checkCaller()) {
                Log.w(TAG,"AVRCP call not allowed for non-active user");
                return null;
            }

            if (mService != null && mService.isAvailable()) {
                return mService;
            }
            return null;
        }

        BluetoothAvrcpControllerBinder(AvrcpControllerService svc) {
            mService = svc;
        }

        public boolean cleanup()  {
            mService = null;
            return true;
        }

        public List<BluetoothDevice> getConnectedDevices() {
            AvrcpControllerService service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>(0);
            return service.getConnectedDevices();
        }

        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            AvrcpControllerService service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>(0);
            return service.getDevicesMatchingConnectionStates(states);
        }

        public int getConnectionState(BluetoothDevice device) {
            AvrcpControllerService service = getService();
            if (service == null) return BluetoothProfile.STATE_DISCONNECTED;
            return service.getConnectionState(device);
        }

        public void sendPassThroughCmd(BluetoothDevice device, int keyCode, int keyState) {
            Log.v(TAG,"Binder Call: sendPassThroughCmd");
            AvrcpControllerService service = getService();
            if (service == null) return;
            service.sendPassThroughCmd(device, keyCode, keyState);
        }
    };

    /** Handles Avrcp messages. */
    private final class AvrcpMessageHandler extends Handler {
        private AvrcpMessageHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_SEND_PASS_THROUGH_CMD:
                if (DBG) Log.v(TAG, "MESSAGE_SEND_PASS_THROUGH_CMD");
                BluetoothDevice device = (BluetoothDevice)msg.obj;
                sendPassThroughCommandNative(getByteAddress(device), msg.arg1, msg.arg2);
                break;
            }
        }
    }

    private void onConnectionStateChanged(boolean connected, byte[] address) {
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
            (Utils.getAddressStringFromByte(address));
        Log.d(TAG, "onConnectionStateChanged " + connected + " " + device);
        Intent intent = new Intent(BluetoothAvrcpController.ACTION_CONNECTION_STATE_CHANGED);
        int oldState = (mConnectedDevices.contains(device) ? BluetoothProfile.STATE_CONNECTED
                                                        : BluetoothProfile.STATE_DISCONNECTED);
        int newState = (connected ? BluetoothProfile.STATE_CONNECTED
                                  : BluetoothProfile.STATE_DISCONNECTED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, oldState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
//        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
        if (connected && oldState == BluetoothProfile.STATE_DISCONNECTED) {
            mConnectedDevices.add(device);
        } else if (!connected && oldState == BluetoothProfile.STATE_CONNECTED) {
            mConnectedDevices.remove(device);
        }
    }

    private void handlePassthroughRsp(int id, int keyState) {
        Log.d(TAG, "passthrough response received as: key: "
                                + id + " state: " + keyState);
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
    }

    private native static void classInitNative();
    private native void initNative();
    private native void cleanupNative();
    private native boolean sendPassThroughCommandNative(byte[] address, int keyCode, int keyState);
}
