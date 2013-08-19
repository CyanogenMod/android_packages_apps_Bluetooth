/*
 * Copyright (C) 2013 The Linux Foundation. All rights reserved
 * Not a Contribution.
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
import android.bluetooth.BluetoothHandsfreeClient;
import android.bluetooth.BluetoothHandsfreeClientCall;
import android.bluetooth.IBluetoothHandsfreeClient;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.Utils;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides Bluetooth Handsfree Client (HF Role) profile, as a service in the
 * Bluetooth application.
 *
 * @hide
 */
public class HandsfreeClientService extends ProfileService {
    private static final boolean DBG = false;
    private static final String TAG = "HandsfreeClientService";

    private HandsfreeClientStateMachine mStateMachine;
    private static HandsfreeClientService sHandsfreeClientService;

    @Override
    protected String getName() {
        return TAG;
    }

    @Override
    public IProfileServiceBinder initBinder() {
        return new BluetoothHandsfreeClientBinder(this);
    }

    @Override
    protected boolean start() {
        mStateMachine = HandsfreeClientStateMachine.make(this);
        IntentFilter filter = new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION);
        filter.addAction(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY);
        try {
            registerReceiver(mBroadcastReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG, "Unable to register broadcat receiver", e);
        }
        setHandsfreeClientService(this);
        return true;
    }

    @Override
    protected boolean stop() {
        try {
            unregisterReceiver(mBroadcastReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Unable to unregister broadcast receiver", e);
        }
        mStateMachine.doQuit();
        return true;
    }

    @Override
    protected boolean cleanup() {
        if (mStateMachine != null) {
            mStateMachine.cleanup();
        }
        clearHandsfreeClientService();
        return true;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                if (streamType == AudioManager.STREAM_BLUETOOTH_SCO) {
                    int streamValue = intent
                            .getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, -1);
                    int streamPrevValue = intent.getIntExtra(
                            AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, -1);

                    if (streamValue != -1 && streamValue != streamPrevValue) {
                        mStateMachine.sendMessage(mStateMachine.obtainMessage(
                                HandsfreeClientStateMachine.SET_SPEAKER_VOLUME, streamValue, 0));
                    }
                }
            }
        }
    };

    /**
     * Handlers for incoming service calls
     */
    private static class BluetoothHandsfreeClientBinder extends IBluetoothHandsfreeClient.Stub
            implements IProfileServiceBinder {
        private HandsfreeClientService mService;

        public BluetoothHandsfreeClientBinder(HandsfreeClientService svc) {
            mService = svc;
        }

        @Override
        public boolean cleanup() {
            mService = null;
            return true;
        }

        private HandsfreeClientService getService() {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "HandsfreeClient call not allowed for non-active user");
                return null;
            }

            if (mService != null && mService.isAvailable()) {
                return mService;
            }
            return null;
        }

        @Override
        public boolean connect(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.connect(device);
        }

        @Override
        public boolean disconnect(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.disconnect(device);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices() {
            HandsfreeClientService service = getService();
            if (service == null) {
                return new ArrayList<BluetoothDevice>(0);
            }
            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return new ArrayList<BluetoothDevice>(0);
            }
            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
            return service.getConnectionState(device);
        }

        @Override
        public boolean setPriority(BluetoothDevice device, int priority) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.setPriority(device, priority);
        }

        @Override
        public int getPriority(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return BluetoothProfile.PRIORITY_UNDEFINED;
            }
            return service.getPriority(device);
        }

        @Override
        public boolean startVoiceRecognition(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.startVoiceRecognition(device);
        }

        @Override
        public boolean stopVoiceRecognition(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.stopVoiceRecognition(device);
        }

        @Override
        public boolean acceptIncomingConnect(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.acceptIncomingConnect(device);
        }

        @Override
        public boolean rejectIncomingConnect(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.rejectIncomingConnect(device);
        }

        @Override
        public int getAudioState(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return BluetoothHandsfreeClient.STATE_AUDIO_DISCONNECTED;
            }
            return service.getAudioState(device);
        }

        @Override
        public boolean connectAudio() {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.connectAudio();
        }

        @Override
        public boolean disconnectAudio() {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.disconnectAudio();
        }

        @Override
        public boolean acceptCall(BluetoothDevice device, int flag) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.acceptCall(device, flag);
        }

        @Override
        public boolean rejectCall(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.rejectCall(device);
        }

        @Override
        public boolean holdCall(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.holdCall(device);
        }

        @Override
        public boolean terminateCall(BluetoothDevice device, int index) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.terminateCall(device, index);
        }

        @Override
        public boolean explicitCallTransfer(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.explicitCallTransfer(device);
        }

        @Override
        public boolean enterPrivateMode(BluetoothDevice device, int index) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.enterPrivateMode(device, index);
        }

        @Override
        public boolean redial(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.redial(device);
        }

        @Override
        public boolean dial(BluetoothDevice device, String number) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.dial(device, number);
        }

        @Override
        public boolean dialMemory(BluetoothDevice device, int location) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.dialMemory(device, location);
        }

        @Override
        public List<BluetoothHandsfreeClientCall> getCurrentCalls(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return null;
            }
            return service.getCurrentCalls(device);
        }

        @Override
        public boolean sendDTMF(BluetoothDevice device, byte code) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.sendDTMF(device, code);
        }

        @Override
        public boolean getLastVoiceTagNumber(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.getLastVoiceTagNumber(device);
        }

        @Override
        public Bundle getCurrentAgEvents(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return null;
            }
            return service.getCurrentAgEvents(device);
        }

        @Override
        public Bundle getCurrentAgFeatures(BluetoothDevice device) {
            HandsfreeClientService service = getService();
            if (service == null) {
                return null;
            }
            return service.getCurrentAgFeatures(device);
        }
    };

    // API methods
    public static synchronized HandsfreeClientService getHandsfreeClientService() {
        if (sHandsfreeClientService != null && sHandsfreeClientService.isAvailable()) {
            if (DBG) {
                Log.d(TAG, "getHandsfreeClientService(): returning " + sHandsfreeClientService);
            }
            return sHandsfreeClientService;
        }
        if (DBG) {
            if (sHandsfreeClientService == null) {
                Log.d(TAG, "getHandsfreeClientService(): service is NULL");
            } else if (!(sHandsfreeClientService.isAvailable())) {
                Log.d(TAG, "getHandsfreeClientService(): service is not available");
            }
        }
        return null;
    }

    private static synchronized void setHandsfreeClientService(HandsfreeClientService instance) {
        if (instance != null && instance.isAvailable()) {
            if (DBG) {
                Log.d(TAG, "setHandsfreeClientService(): set to: " + sHandsfreeClientService);
            }
            sHandsfreeClientService = instance;
        } else {
            if (DBG) {
                if (sHandsfreeClientService == null) {
                    Log.d(TAG, "setHandsfreeClientService(): service not available");
                } else if (!sHandsfreeClientService.isAvailable()) {
                    Log.d(TAG, "setHandsfreeClientService(): service is cleaning up");
                }
            }
        }
    }

    private static synchronized void clearHandsfreeClientService() {
        sHandsfreeClientService = null;
    }

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

        mStateMachine.sendMessage(HandsfreeClientStateMachine.CONNECT, device);
        return true;
    }

    boolean disconnect(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH ADMIN permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        mStateMachine.sendMessage(HandsfreeClientStateMachine.DISCONNECT, device);
        return true;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mStateMachine.getConnectedDevices();
    }

    private List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mStateMachine.getDevicesMatchingConnectionStates(states);
    }

    int getConnectionState(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mStateMachine.getConnectionState(device);
    }

    // TODO Should new setting for HandsfreeClient priority be created?
    public boolean setPriority(BluetoothDevice device, int priority) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH_ADMIN permission");
        Settings.Global.putInt(getContentResolver(),
                Settings.Global.getBluetoothHeadsetPriorityKey(device.getAddress()),
                priority);
        if (DBG) {
            Log.d(TAG, "Saved priority " + device + " = " + priority);
        }
        return true;
    }

    public int getPriority(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH_ADMIN permission");
        int priority = Settings.Global.getInt(getContentResolver(),
                Settings.Global.getBluetoothHeadsetPriorityKey(device.getAddress()),
                BluetoothProfile.PRIORITY_UNDEFINED);
        return priority;
    }

    boolean startVoiceRecognition(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        mStateMachine.sendMessage(HandsfreeClientStateMachine.VOICE_RECOGNITION_START);
        return true;
    }

    boolean stopVoiceRecognition(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        // It seem that we really need to check the AudioOn state.
        // But since we allow startVoiceRecognition in STATE_CONNECTED and
        // STATE_CONNECTING state, we do these 2 in this method
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        mStateMachine.sendMessage(HandsfreeClientStateMachine.VOICE_RECOGNITION_STOP);
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

    boolean connectAudio() {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        if (!mStateMachine.isConnected()) {
            return false;
        }
        if (mStateMachine.isAudioOn()) {
            return false;
        }
        mStateMachine.sendMessage(HandsfreeClientStateMachine.CONNECT_AUDIO);
        return true;
    }

    boolean disconnectAudio() {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        if (!mStateMachine.isAudioOn()) {
            return false;
        }
        mStateMachine.sendMessage(HandsfreeClientStateMachine.DISCONNECT_AUDIO);
        return true;
    }

    boolean holdCall(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = mStateMachine.obtainMessage(HandsfreeClientStateMachine.HOLD_CALL);
        mStateMachine.sendMessage(msg);
        return true;
    }

    boolean acceptCall(BluetoothDevice device, int flag) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = mStateMachine.obtainMessage(HandsfreeClientStateMachine.ACCEPT_CALL);
        msg.arg1 = flag;
        mStateMachine.sendMessage(msg);
        return true;
    }

    boolean rejectCall(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        Message msg = mStateMachine.obtainMessage(HandsfreeClientStateMachine.REJECT_CALL);
        mStateMachine.sendMessage(msg);
        return true;
    }

    boolean terminateCall(BluetoothDevice device, int index) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        Message msg = mStateMachine.obtainMessage(HandsfreeClientStateMachine.TERMINATE_CALL);
        msg.arg1 = index;
        mStateMachine.sendMessage(msg);
        return true;
    }

    boolean enterPrivateMode(BluetoothDevice device, int index) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        Message msg = mStateMachine.obtainMessage(HandsfreeClientStateMachine.ENTER_PRIVATE_MODE);
        msg.arg1 = index;
        mStateMachine.sendMessage(msg);
        return true;
    }

    boolean redial(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        Message msg = mStateMachine.obtainMessage(HandsfreeClientStateMachine.REDIAL);
        mStateMachine.sendMessage(msg);
        return true;
    }

    boolean dial(BluetoothDevice device, String number) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        Message msg = mStateMachine.obtainMessage(HandsfreeClientStateMachine.DIAL_NUMBER);
        msg.obj = number;
        mStateMachine.sendMessage(msg);
        return true;
    }

    boolean dialMemory(BluetoothDevice device, int location) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = mStateMachine.obtainMessage(HandsfreeClientStateMachine.DIAL_MEMORY);
        msg.arg1 = location;
        mStateMachine.sendMessage(msg);
        return true;
    }

    public boolean sendDTMF(BluetoothDevice device, byte code) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = mStateMachine.obtainMessage(HandsfreeClientStateMachine.SEND_DTMF);
        msg.arg1 = code;
        mStateMachine.sendMessage(msg);
        return true;
    }

    public boolean getLastVoiceTagNumber(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = mStateMachine.obtainMessage(HandsfreeClientStateMachine.LAST_VTAG_NUMBER);
        mStateMachine.sendMessage(msg);
        return true;
    }

    public List<BluetoothHandsfreeClientCall> getCurrentCalls(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return null;
        }
        return mStateMachine.getCurrentCalls();
    }

    public boolean explicitCallTransfer(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED &&
                connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = mStateMachine
                .obtainMessage(HandsfreeClientStateMachine.EXPLICIT_CALL_TRANSFER);
        mStateMachine.sendMessage(msg);
        return true;
    }

    public Bundle getCurrentAgEvents(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return null;
        }
        return mStateMachine.getCurrentAgEvents();
    }

    public Bundle getCurrentAgFeatures(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int connectionState = mStateMachine.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return null;
        }
        return mStateMachine.getCurrentAgFeatures();
    }
}
