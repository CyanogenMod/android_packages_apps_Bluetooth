/*
 * Copyright (c) 2014 The Android Open Source Project
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

package com.android.bluetooth.hfpclient;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothHeadsetClientCall;
import android.bluetooth.IBluetoothHeadsetClient;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.hfpclient.connserv.HfpClientConnectionService;
import com.android.bluetooth.Utils;
import java.util.ArrayList;
import java.util.List;


/**
 * Provides Bluetooth Headset Client (HF Role) profile, as a service in the
 * Bluetooth application.
 *
 * @hide
 */
public class HeadsetClientService extends ProfileService {
    private static final boolean DBG = false;
    private static final String TAG = "HeadsetClientService";

    private HeadsetClientStateMachine mStateMachine;
    private static HeadsetClientService sHeadsetClientService;

    public static String HFP_CLIENT_STOP_TAG = "hfp_client_stop_tag";

    @Override
    protected String getName() {
        return TAG;
    }

    @Override
    public IProfileServiceBinder initBinder() {
        return new BluetoothHeadsetClientBinder(this);
    }

    @Override
    protected boolean start() {
        Log.d(TAG, "start");
        mStateMachine = HeadsetClientStateMachine.make(this);
        IntentFilter filter = new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION);
        filter.addAction(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY);
        try {
            registerReceiver(mBroadcastReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG, "Unable to register broadcat receiver", e);
        }
        setHeadsetClientService(this);

        // Start the HfpClientConnectionService to create connection with telecom when HFP
        // connection is available.
        Intent startIntent = new Intent(this, HfpClientConnectionService.class);
        startService(startIntent);

        return true;
    }

    @Override
    protected boolean stop() {
        Log.d(TAG, "stop");
        try {
            unregisterReceiver(mBroadcastReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Unable to unregister broadcast receiver", e);
        }
        mStateMachine.doQuit();

        // Stop the HfpClientConnectionService.
        Intent stopIntent = new Intent(this, HfpClientConnectionService.class);
        stopIntent.putExtra(HFP_CLIENT_STOP_TAG, true);
        startService(stopIntent);

        return true;
    }

    @Override
    protected boolean cleanup() {
        Log.d(TAG, "cleanup");
        if (mStateMachine != null) {
            mStateMachine.cleanup();
        }
        clearHeadsetClientService();
        return true;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            // We handle the volume changes for Voice calls here since HFP audio volume control does
            // not go through audio manager (audio mixer). We check if the voice call volume has
            // changed and subsequently change the SCO volume see
            // ({@link HeadsetClientStateMachine#SET_SPEAKER_VOLUME} in
            // {@link HeadsetClientStateMachine} for details.
            if (action.equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                Log.d(TAG, "Volume changed for stream: " +
                    intent.getExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE));
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                if (streamType == AudioManager.STREAM_BLUETOOTH_SCO) {
                    int streamValue = intent
                            .getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, -1);
                    int streamPrevValue = intent.getIntExtra(
                            AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, -1);

                    if (streamValue != -1 && streamValue != streamPrevValue) {
                        mStateMachine.sendMessage(mStateMachine.obtainMessage(
                                HeadsetClientStateMachine.SET_SPEAKER_VOLUME, streamValue, 0));
                    }
                }
            }
        }
    };

    /**
     * Handlers for incoming service calls
     */
    private static class BluetoothHeadsetClientBinder extends IBluetoothHeadsetClient.Stub
            implements IProfileServiceBinder {
        private HeadsetClientService mService;

        public BluetoothHeadsetClientBinder(HeadsetClientService svc) {
            mService = svc;
        }

        @Override
        public boolean cleanup() {
            mService = null;
            return true;
        }

        private HeadsetClientService getService() {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "HeadsetClient call not allowed for non-active user");
                return null;
            }

            if (mService != null && mService.isAvailable()) {
                return mService;
            }
            return null;
        }

        @Override
        public boolean connect(BluetoothDevice device) {
            HeadsetClientService service = getService();
            Log.d(TAG, "connect");
            if (service == null) {
                return false;
            }
            return service.connect(device);
        }

        @Override
        public boolean disconnect(BluetoothDevice device) {
            HeadsetClientService service = getService();
            Log.d(TAG, "disconnect");
            if (service == null) {
                return false;
            }
            return service.disconnect(device);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices() {
            HeadsetClientService service = getService();
            if (service == null) {
                return new ArrayList<BluetoothDevice>(0);
            }
            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            HeadsetClientService service = getService();
            if (service == null) {
                return new ArrayList<BluetoothDevice>(0);
            }
            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
            return service.getConnectionState(device);
        }

        @Override
        public boolean setPriority(BluetoothDevice device, int priority) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.setPriority(device, priority);
        }

        @Override
        public int getPriority(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return BluetoothProfile.PRIORITY_UNDEFINED;
            }
            return service.getPriority(device);
        }

        @Override
        public boolean startVoiceRecognition(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.startVoiceRecognition(device);
        }

        @Override
        public boolean stopVoiceRecognition(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.stopVoiceRecognition(device);
        }

        @Override
        public boolean acceptIncomingConnect(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.acceptIncomingConnect(device);
        }

        @Override
        public boolean rejectIncomingConnect(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.rejectIncomingConnect(device);
        }

        @Override
        public int getAudioState(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED;
            }
            return service.getAudioState(device);
        }

        @Override
        public void setAudioRouteAllowed(boolean allowed) {
            HeadsetClientService service = getService();
            if (service != null) {
                service.setAudioRouteAllowed(allowed);
            }
        }

        @Override
        public boolean getAudioRouteAllowed() {
            HeadsetClientService service = getService();
            if (service != null) {
                return service.getAudioRouteAllowed();
            }

            return false;
        }

        @Override
        public boolean connectAudio() {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.connectAudio();
        }

        @Override
        public boolean disconnectAudio() {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.disconnectAudio();
        }

        @Override
        public boolean acceptCall(BluetoothDevice device, int flag) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.acceptCall(device, flag);
        }

        @Override
        public boolean rejectCall(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.rejectCall(device);
        }

        @Override
        public boolean holdCall(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.holdCall(device);
        }

        @Override
        public boolean terminateCall(BluetoothDevice device, int index) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.terminateCall(device, index);
        }

        @Override
        public boolean explicitCallTransfer(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.explicitCallTransfer(device);
        }

        @Override
        public boolean enterPrivateMode(BluetoothDevice device, int index) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.enterPrivateMode(device, index);
        }

        @Override
        public boolean redial(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.redial(device);
        }

        @Override
        public boolean dial(BluetoothDevice device, String number) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.dial(device, number);
        }

        @Override
        public boolean dialMemory(BluetoothDevice device, int location) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.dialMemory(device, location);
        }

        @Override
        public List<BluetoothHeadsetClientCall> getCurrentCalls(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return null;
            }
            return service.getCurrentCalls(device);
        }

        @Override
        public boolean sendDTMF(BluetoothDevice device, byte code) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.sendDTMF(device, code);
        }

        @Override
        public boolean getLastVoiceTagNumber(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.getLastVoiceTagNumber(device);
        }

        @Override
        public Bundle getCurrentAgEvents(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return null;
            }
            return service.getCurrentAgEvents(device);
        }

        @Override
        public Bundle getCurrentAgFeatures(BluetoothDevice device) {
            HeadsetClientService service = getService();
            if (service == null) {
                return null;
            }
            return service.getCurrentAgFeatures(device);
        }
    };

    // API methods
    public static synchronized HeadsetClientService getHeadsetClientService() {
        if (sHeadsetClientService != null && sHeadsetClientService.isAvailable()) {
            if (DBG) {
                Log.d(TAG, "getHeadsetClientService(): returning " + sHeadsetClientService);
            }
            return sHeadsetClientService;
        }
        if (DBG) {
            if (sHeadsetClientService == null) {
                Log.d(TAG, "getHeadsetClientService(): service is NULL");
            } else if (!(sHeadsetClientService.isAvailable())) {
                Log.d(TAG, "getHeadsetClientService(): service is not available");
            }
        }
        return null;
    }

    private static synchronized void setHeadsetClientService(HeadsetClientService instance) {
        if (instance != null && instance.isAvailable()) {
            if (DBG) {
                Log.d(TAG, "setHeadsetClientService(): set to: " + sHeadsetClientService);
            }
            sHeadsetClientService = instance;
        } else {
            if (DBG) {
                if (sHeadsetClientService == null) {
                    Log.d(TAG, "setHeadsetClientService(): service not available");
                } else if (!sHeadsetClientService.isAvailable()) {
                    Log.d(TAG, "setHeadsetClientService(): service is cleaning up");
                }
            }
        }
    }

    private static synchronized void clearHeadsetClientService() {
        sHeadsetClientService = null;
    }

    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "Enter connect");
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH ADMIN permission");

        if (getPriority(device) == BluetoothProfile.PRIORITY_OFF) {
            return false;
        }

        A2dpService a2dpService = A2dpService.getA2dpService();
        //do not allow new connections with active multicast
        if (a2dpService != null &&
                (a2dpService.isMulticastOngoing(device))) {
            Log.i(TAG,"A2dp Multicast is Ongoing, ignore Connection Request");
            return false;
        }

        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState == BluetoothProfile.STATE_CONNECTED ||
                connectionState == BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        mStateMachine.sendMessage(HeadsetClientStateMachine.CONNECT, device);
        Log.d(TAG, "Exit connect");
        return true;
    }

    boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "Enter disconnect");
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH ADMIN permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        mStateMachine.sendMessage(HeadsetClientStateMachine.DISCONNECT, device);
        Log.d(TAG, "Exit disconnect");
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

    int getConnectionState(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mStateMachine.getConnectionState(device);
    }

    // TODO Should new setting for HeadsetClient priority be created?
    public boolean setPriority(BluetoothDevice device, int priority) {
        Log.d(TAG, "Enter setPriority");
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH_ADMIN permission");
        Settings.Global.putInt(getContentResolver(),
                Settings.Global.getBluetoothHeadsetPriorityKey(device.getAddress()),
                priority);
        if (DBG) {
            Log.d(TAG, "Saved priority " + device + " = " + priority);
        }
        Log.d(TAG, "Exit setPriority");
        return true;
    }

    public int getPriority(BluetoothDevice device) {
        Log.d(TAG, "Enter getPriority");
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH_ADMIN permission");
        int priority = Settings.Global.getInt(getContentResolver(),
                Settings.Global.getBluetoothHeadsetPriorityKey(device.getAddress()),
                BluetoothProfile.PRIORITY_UNDEFINED);
        Log.d(TAG, "Exit getPriority");
        return priority;
    }

    boolean startVoiceRecognition(BluetoothDevice device) {
        Log.d(TAG, "Enter startVoiceRecognition");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        mStateMachine.sendMessage(HeadsetClientStateMachine.VOICE_RECOGNITION_START);
        Log.d(TAG, "Exit startVoiceRecognition");
        return true;
    }

    boolean stopVoiceRecognition(BluetoothDevice device) {
        Log.d(TAG, "Enter stopVoiceRecognition");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        // It seem that we really need to check the AudioOn state.
        // But since we allow startVoiceRecognition in STATE_CONNECTED and
        // STATE_CONNECTING state, we do these 2 in this method
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        mStateMachine.sendMessage(HeadsetClientStateMachine.VOICE_RECOGNITION_STOP);
        Log.d(TAG, "Exit stopVoiceRecognition");
        return true;
    }

    boolean acceptIncomingConnect(BluetoothDevice device) {
        // TODO(BT) remove it if stack does access control
        return false;
    }

    boolean rejectIncomingConnect(BluetoothDevice device) {
        // TODO(BT) remove it if stack does access control
        return false;
    }

    int getAudioState(BluetoothDevice device) {
        return mStateMachine.getAudioState(device);
    }

    public void setAudioRouteAllowed(boolean allowed) {
        mStateMachine.setAudioRouteAllowed(allowed);
    }

    public boolean getAudioRouteAllowed() {
        return mStateMachine.getAudioRouteAllowed();
    }

    boolean connectAudio() {
        Log.d(TAG, "Enter connectAudio");
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        if (!mStateMachine.isConnected()) {
            return false;
        }
        if (mStateMachine.isAudioOn()) {
            return false;
        }
        mStateMachine.sendMessage(HeadsetClientStateMachine.CONNECT_AUDIO);
        Log.d(TAG, "Exit connectAudio");
        return true;
    }

    boolean disconnectAudio() {
        Log.d(TAG, "Enter disconnectAudio");
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        if (!mStateMachine.isAudioOn()) {
            return false;
        }
        mStateMachine.sendMessage(HeadsetClientStateMachine.DISCONNECT_AUDIO);
        Log.d(TAG, "Exit disconnectAudio");
        return true;
    }

    boolean holdCall(BluetoothDevice device) {
        Log.d(TAG, "Enter holdCall");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = mStateMachine.obtainMessage(HeadsetClientStateMachine.HOLD_CALL);
        mStateMachine.sendMessage(msg);
        Log.d(TAG, "Exit holdCall");
        return true;
    }

    boolean acceptCall(BluetoothDevice device, int flag) {
        Log.d(TAG, "Enter acceptCall");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = mStateMachine.obtainMessage(HeadsetClientStateMachine.ACCEPT_CALL);
        msg.arg1 = flag;
        mStateMachine.sendMessage(msg);
        Log.d(TAG, "Exit acceptCall");
        return true;
    }

    boolean rejectCall(BluetoothDevice device) {
        Log.d(TAG, "Enter rejectCall");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        Message msg = mStateMachine.obtainMessage(HeadsetClientStateMachine.REJECT_CALL);
        mStateMachine.sendMessage(msg);
        Log.d(TAG, "Exit rejectCall");
        return true;
    }

    boolean terminateCall(BluetoothDevice device, int index) {
        Log.d(TAG, "Enter terminateCall");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        Message msg = mStateMachine.obtainMessage(HeadsetClientStateMachine.TERMINATE_CALL);
        msg.arg1 = index;
        mStateMachine.sendMessage(msg);
        Log.d(TAG, "Exit terminateCall");
        return true;
    }

    boolean enterPrivateMode(BluetoothDevice device, int index) {
        Log.d(TAG, "Enter enterPrivateMode");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        Message msg = mStateMachine.obtainMessage(HeadsetClientStateMachine.ENTER_PRIVATE_MODE);
        msg.arg1 = index;
        mStateMachine.sendMessage(msg);
        Log.d(TAG, "Exit enterPrivateMode");
        return true;
    }

    boolean redial(BluetoothDevice device) {
        Log.d(TAG, "Enter redial");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        Message msg = mStateMachine.obtainMessage(HeadsetClientStateMachine.REDIAL);
        mStateMachine.sendMessage(msg);
        Log.d(TAG, "Exit redial");
        return true;
    }

    boolean dial(BluetoothDevice device, String number) {
        Log.d(TAG, "Enter dial");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        Message msg = mStateMachine.obtainMessage(HeadsetClientStateMachine.DIAL_NUMBER);
        msg.obj = number;
        mStateMachine.sendMessage(msg);
        Log.d(TAG, "Exit dial");
        return true;
    }

    boolean dialMemory(BluetoothDevice device, int location) {
        Log.d(TAG, "Enter dialMemory");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = mStateMachine.obtainMessage(HeadsetClientStateMachine.DIAL_MEMORY);
        msg.arg1 = location;
        mStateMachine.sendMessage(msg);
        Log.d(TAG, "Exit dialMemory");
        return true;
    }

    public boolean sendDTMF(BluetoothDevice device, byte code) {
        Log.d(TAG, "Enter sendDTMF");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = mStateMachine.obtainMessage(HeadsetClientStateMachine.SEND_DTMF);
        msg.arg1 = code;
        mStateMachine.sendMessage(msg);
        Log.d(TAG, "Exit sendDTMF");
        return true;
    }

    public boolean getLastVoiceTagNumber(BluetoothDevice device) {
        Log.d(TAG, "Enter getLastVoiceTagNumber");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = mStateMachine.obtainMessage(HeadsetClientStateMachine.LAST_VTAG_NUMBER);
        mStateMachine.sendMessage(msg);
        Log.d(TAG, "Exit getLastVoiceTagNumber");
        return true;
    }

    public List<BluetoothHeadsetClientCall> getCurrentCalls(BluetoothDevice device) {
        Log.d(TAG, "Enter getCurrentCalls");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return null;
        }
        Log.d(TAG, "Exit getCurrentCalls");
        return mStateMachine.getCurrentCalls();
    }

    public boolean explicitCallTransfer(BluetoothDevice device) {
        Log.d(TAG, "Enter explicitCallTransfer");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = mStateMachine
                .obtainMessage(HeadsetClientStateMachine.EXPLICIT_CALL_TRANSFER);
        mStateMachine.sendMessage(msg);
        Log.d(TAG, "Exit explicitCallTransfer");
        return true;
    }

    public Bundle getCurrentAgEvents(BluetoothDevice device) {
        Log.d(TAG, "Enter getCurrentAgEvents");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return null;
        }
        Log.d(TAG, "Exit getCurrentAgEvents");
        return mStateMachine.getCurrentAgEvents();
    }

    public Bundle getCurrentAgFeatures(BluetoothDevice device) {
        Log.d(TAG, "Enter getCurrentAgFeatures");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return null;
        }
        Log.d(TAG, "Exit getCurrentAgFeatures");
        return mStateMachine.getCurrentAgFeatures();
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        if (mStateMachine != null) {
            mStateMachine.dump(sb);
        }
    }
}
