/*
 * Copyright (C) 2012 Google Inc.
 */

package com.android.bluetooth.hfp;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import android.content.pm.PackageManager;
import com.android.bluetooth.btservice.AdapterService;

/**
 * Provides Bluetooth Headset and Handsfree profile, as a service in
 * the Bluetooth application.
 * @hide
 */
public class HeadsetService extends Service {
    private static final String TAG = "BluetoothHeadsetService";
    private static final boolean DBG = true;

    static final String BLUETOOTH_ADMIN_PERM =
        android.Manifest.permission.BLUETOOTH_ADMIN;
    static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private BluetoothAdapter mAdapter;
    private HeadsetStateMachine mStateMachine;

    @Override
    public void onCreate() {
        log("onCreate");
        super.onCreate();
        mAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @Override
    public IBinder onBind(Intent intent) {
        log("onBind");
        return mBinder;
    }

    public void onStart(Intent intent, int startId) {
        log("onStart");

        if (mAdapter == null) {
            Log.w(TAG, "Stopping profile service: device does not have BT");
            stop();
        }

        if (checkCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM)!=PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission denied!");
            return;
        }

        String action = intent.getStringExtra(AdapterService.EXTRA_ACTION);
        if (!AdapterService.ACTION_SERVICE_STATE_CHANGED.equals(action)) {
            Log.e(TAG, "Invalid action " + action);
            return;
        }

        int state= intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
        if(state==BluetoothAdapter.STATE_OFF) {
            stop();
        } else if (state== BluetoothAdapter.STATE_ON){
            start();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DBG) log("Destroying service.");
        if(mAdapter != null)
            mAdapter = null;
        if(mStateMachine != null)
        {
            mStateMachine.quit();
            mStateMachine = null;
        }
    }

    private void start() {
        mStateMachine = new HeadsetStateMachine(this);
        mStateMachine.start();
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
        registerReceiver(mHeadsetReceiver, filter);

        //Notify adapter service
        AdapterService sAdapter = AdapterService.getAdapterService();
        if (sAdapter!= null) {
            sAdapter.onProfileServiceStateChanged(getClass().getName(), BluetoothAdapter.STATE_ON);
        }
    }

    private void stop() {
        if (DBG) log("stopService()");
        unregisterReceiver(mHeadsetReceiver);

        if (mStateMachine!= null) {
            mStateMachine.quit();
            mStateMachine.cleanup();
            mStateMachine=null;
        }

        //Notify adapter service
        AdapterService sAdapter = AdapterService.getAdapterService();
        if (sAdapter!= null) {
            sAdapter.onProfileServiceStateChanged(getClass().getName(), BluetoothAdapter.STATE_OFF);
        }
        stopSelf();
    }

    private final BroadcastReceiver mHeadsetReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                mStateMachine.sendMessage(HeadsetStateMachine.INTENT_BATTERY_CHANGED, intent);
            } else if (action.equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                if (streamType == AudioManager.STREAM_BLUETOOTH_SCO) {
                    mStateMachine.sendMessage(HeadsetStateMachine.INTENT_SCO_VOLUME_CHANGED,
                                              intent);
                }
            }
        }
    };

    /**
     * Handlers for incoming service calls
     */
    private final IBluetoothHeadset.Stub mBinder = new IBluetoothHeadset.Stub() {

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

            mStateMachine.sendMessage(HeadsetStateMachine.CONNECT, device);
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

            mStateMachine.sendMessage(HeadsetStateMachine.DISCONNECT, device);
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
                Settings.Secure.getBluetoothHeadsetPriorityKey(device.getAddress()),
                priority);
            if (DBG) log("Saved priority " + device + " = " + priority);
            return true;
        }

        public int getPriority(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            int priority = Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.getBluetoothHeadsetPriorityKey(device.getAddress()),
                BluetoothProfile.PRIORITY_UNDEFINED);
            return priority;
        }

        public boolean startVoiceRecognition(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            int connectionState = mStateMachine.getConnectionState(device);
            if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
                return false;
            }
            mStateMachine.sendMessage(HeadsetStateMachine.VOICE_RECOGNITION_START);
            return true;
        }

        public boolean stopVoiceRecognition(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            // It seem that we really need to check the AudioOn state.
            // But since we allow startVoiceRecognition in STATE_CONNECTED and
            // STATE_CONNECTING state, we do these 2 in this method
            int connectionState = mStateMachine.getConnectionState(device);
            if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
                return false;
            }
            mStateMachine.sendMessage(HeadsetStateMachine.VOICE_RECOGNITION_STOP);
            // TODO is this return correct when the voice recognition is not on?
            return true;
        }

        public boolean isAudioOn() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mStateMachine.isAudioOn();
        }

        public boolean isAudioConnected(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mStateMachine.isAudioConnected(device);
        }

        public int getBatteryUsageHint(BluetoothDevice device) {
            // TODO(BT) ask for BT stack support?
            return 0;
        }

        public boolean acceptIncomingConnect(BluetoothDevice device) {
            // TODO(BT) remove it if stack does access control
            return false;
        }

        public boolean rejectIncomingConnect(BluetoothDevice device) {
            // TODO(BT) remove it if stack does access control
            return false;
        }

        public int getAudioState(BluetoothDevice device) {
            return mStateMachine.getAudioState(device);
        }

        public boolean connectAudio() {
            // TODO(BT) BLUETOOTH or BLUETOOTH_ADMIN permission
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            if (!mStateMachine.isConnected()) {
                return false;
            }
            if (mStateMachine.isAudioOn()) {
                return false;
            }
            mStateMachine.sendMessage(HeadsetStateMachine.CONNECT_AUDIO);
            return true;
        }

        public boolean disconnectAudio() {
            // TODO(BT) BLUETOOTH or BLUETOOTH_ADMIN permission
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            if (!mStateMachine.isAudioOn()) {
                return false;
            }
            mStateMachine.sendMessage(HeadsetStateMachine.DISCONNECT_AUDIO);
            return true;
        }

        public boolean startScoUsingVirtualVoiceCall(BluetoothDevice device) {
            // TODO(BT) Is this right?
            mStateMachine.sendMessage(HeadsetStateMachine.CONNECT_AUDIO, device);
            return true;
        }

        public boolean stopScoUsingVirtualVoiceCall(BluetoothDevice device) {
            // TODO(BT) Is this right?
            mStateMachine.sendMessage(HeadsetStateMachine.DISCONNECT_AUDIO, device);
            return true;
        }

        public void phoneStateChanged(int numActive, int numHeld, int callState,
                                      String number, int type) {
            mStateMachine.sendMessage(HeadsetStateMachine.CALL_STATE_CHANGED,
                new HeadsetCallState(numActive, numHeld, callState, number, type));
        }

        public void roamChanged(boolean roam) {
            mStateMachine.sendMessage(HeadsetStateMachine.ROAM_CHANGED, roam);
        }

        public void clccResponse(int index, int direction, int status, int mode, boolean mpty,
                                 String number, int type) {
            mStateMachine.sendMessage(HeadsetStateMachine.SEND_CCLC_RESPONSE,
                new HeadsetClccResponse(index, direction, status, mode, mpty, number, type));
        }
    };

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
