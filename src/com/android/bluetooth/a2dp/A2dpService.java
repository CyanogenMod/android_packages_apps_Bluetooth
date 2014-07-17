/*
 * Copyright (C) 2013-2014, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
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

package com.android.bluetooth.a2dp;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothA2dp;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.os.SystemProperties;
import android.util.Log;
import com.android.bluetooth.avrcp.Avrcp;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.Utils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Provides Bluetooth A2DP profile, as a service in the Bluetooth application.
 * @hide
 */
public class A2dpService extends ProfileService {
    private static final boolean DBG = true;
    private static final String TAG="A2dpService";

    private A2dpStateMachine mStateMachine;
    private Avrcp mAvrcp;
    private AudioManager mAudioManager;
    private static A2dpService sAd2dpService;
    static final ParcelUuid[] A2DP_SOURCE_UUID = {
        BluetoothUuid.AudioSource
    };
    static final ParcelUuid[] A2DP_SOURCE_SINK_UUIDS = {
        BluetoothUuid.AudioSource,
        BluetoothUuid.AudioSink
    };

    protected String getName() {
        return TAG;
    }

    protected IProfileServiceBinder initBinder() {
        return new BluetoothA2dpBinder(this);
    }

    protected boolean start() {
        int maxConnections = 1;
        int multiCastState = 0;
        int maxA2dpConnection =
                SystemProperties.getInt("persist.bt.max.a2dp.connections", 1);
        int a2dpMultiCastState =
                SystemProperties.getInt("persist.bt.enable.multicast", 0);
        if (a2dpMultiCastState == 1)
                multiCastState = a2dpMultiCastState;
        if (maxA2dpConnection == 2)
                maxConnections = maxA2dpConnection;
        // enable soft hands-off also when multicast is enabled.
        if (multiCastState == 1 && maxConnections != 2) {
            Log.i(TAG,"Enable soft handsoff as multicast is enabled");
            maxConnections = 2;
        }
        log( "maxA2dpConnections = " + maxConnections);
        log( "multiCastState = " + multiCastState);
        mAvrcp = Avrcp.make(this, this, maxConnections);
        mStateMachine = A2dpStateMachine.make(this, this,
                maxConnections, multiCastState);
        setA2dpService(this);
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        return true;
    }

    protected boolean stop() {
        if (mStateMachine != null) {
            mStateMachine.doQuit();
        }
        if (mAvrcp != null) {
            mAvrcp.doQuit();
        }
        return true;
    }

    protected boolean cleanup() {
        if (mStateMachine!= null) {
            mStateMachine.cleanup();
        }
        if (mAvrcp != null) {
            mAvrcp.cleanup();
            mAvrcp = null;
        }
        clearA2dpService();
        return true;
    }

    //API Methods

    public static synchronized A2dpService getA2dpService(){
        if (sAd2dpService != null && sAd2dpService.isAvailable()) {
            if (DBG) Log.d(TAG, "getA2DPService(): returning " + sAd2dpService);
            return sAd2dpService;
        }
        if (DBG)  {
            if (sAd2dpService == null) {
                Log.d(TAG, "getA2dpService(): service is NULL");
            } else if (!(sAd2dpService.isAvailable())) {
                Log.d(TAG,"getA2dpService(): service is not available");
            }
        }
        return null;
    }

    private static synchronized void setA2dpService(A2dpService instance) {
        if (instance != null && instance.isAvailable()) {
            if (DBG) Log.d(TAG, "setA2dpService(): set to: " + sAd2dpService);
            sAd2dpService = instance;
        } else {
            if (DBG)  {
                if (sAd2dpService == null) {
                    Log.d(TAG, "setA2dpService(): service not available");
                } else if (!sAd2dpService.isAvailable()) {
                    Log.d(TAG,"setA2dpService(): service is cleaning up");
                }
            }
        }
    }

    private static synchronized void clearA2dpService() {
        sAd2dpService = null;
    }

    public boolean connect(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                       "Need BLUETOOTH ADMIN permission");

        if (getPriority(device) == BluetoothProfile.PRIORITY_OFF) {
            return false;
        }
        ParcelUuid[] featureUuids = device.getUuids();
        if ((BluetoothUuid.containsAnyUuid(featureUuids, A2DP_SOURCE_UUID)) &&
            !(BluetoothUuid.containsAllUuids(featureUuids ,A2DP_SOURCE_SINK_UUIDS))) {
            Log.e(TAG,"Remote does not have A2dp Sink UUID");
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

    boolean disconnect(BluetoothDevice device) {
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

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mStateMachine.getDevicesMatchingConnectionStates(states);
    }

    int getConnectionState(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mStateMachine.getConnectionState(device);
    }

    public boolean setPriority(BluetoothDevice device, int priority) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                       "Need BLUETOOTH_ADMIN permission");
        Settings.Global.putInt(getContentResolver(),
            Settings.Global.getBluetoothA2dpSinkPriorityKey(device.getAddress()),
            priority);
        if (DBG) Log.d(TAG,"Saved priority " + device + " = " + priority);
        return true;
    }

    public int getPriority(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                       "Need BLUETOOTH_ADMIN permission");
        int priority = Settings.Global.getInt(getContentResolver(),
            Settings.Global.getBluetoothA2dpSinkPriorityKey(device.getAddress()),
            BluetoothProfile.PRIORITY_UNDEFINED);
        return priority;
    }

    /* Absolute volume implementation */
    public boolean isAvrcpAbsoluteVolumeSupported() {
        return mAvrcp.isAbsoluteVolumeSupported();
    }

    public void adjustAvrcpAbsoluteVolume(int direction) {
        mAvrcp.adjustVolume(direction);
    }

    public void setAvrcpAbsoluteVolume(int volume) {
        mAvrcp.setAbsoluteVolume(volume);
    }

    public void setAvrcpAudioState(int state, BluetoothDevice device) {
        mAvrcp.setA2dpAudioState(state, device);
    }

    public List<BluetoothDevice> getA2dpPlayingDevice() {
        return mStateMachine.getPlayingDevice();
    }

    public boolean isMulticastEnabled() {
        return mStateMachine.isMulticastEnabled();
    }

    public boolean isMulticastFeatureEnabled() {
        return mStateMachine.isMulticastFeatureEnabled();
    }

    // return status of multicast,needed for blocking outgoing connections
    public boolean isMulticastOngoing(BluetoothDevice device) {

        Log.i(TAG,"audio isMusicActive is " + mAudioManager.isMusicActive());
        // we should never land is case where playing device size is bigger
        // than 2 still have safe check.
        if (device == null) {
            if ((getA2dpPlayingDevice().size() >= 2) &&
                    (mAudioManager.isMusicActive())) {
                return true;
            } else {
                return false;
            }
        }
        if ((getA2dpPlayingDevice().size() >= 2) &&
                mAudioManager.isMusicActive() &&
                !(getA2dpPlayingDevice().contains(device))) {
            return true;
        } else {
            return false;
        }
    }

    synchronized boolean isA2dpPlaying(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                       "Need BLUETOOTH permission");
        if (DBG) Log.d(TAG, "isA2dpPlaying(" + device + ")");
        return mStateMachine.isPlaying(device);
    }

    //Binder object: Must be static class or memory leak may occur 
    private static class BluetoothA2dpBinder extends IBluetoothA2dp.Stub 
        implements IProfileServiceBinder {
        private A2dpService mService;

        private A2dpService getService() {
            if (!Utils.checkCaller()) {
                Log.w(TAG,"A2dp call not allowed for non-active user");
                return null;
            }

            if (mService != null && mService.isAvailable()) {
                return mService;
            }
            return null;
        }

        BluetoothA2dpBinder(A2dpService svc) {
            mService = svc;
        }

        public boolean cleanup()  {
            mService = null;
            return true;
        }

        public boolean connect(BluetoothDevice device) {
            A2dpService service = getService();
            if (service == null) return false;
            //do not allow new connections with active multicast
            if (service.isMulticastOngoing(device)) {
                Log.i(TAG,"A2dp Multicast is Ongoing, ignore Connection Request");
                return false;
            }
            return service.connect(device);
        }

        public boolean disconnect(BluetoothDevice device) {
            A2dpService service = getService();
            if (service == null) return false;
            return service.disconnect(device);
        }

        public List<BluetoothDevice> getConnectedDevices() {
            A2dpService service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>(0);
            return service.getConnectedDevices();
        }

        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            A2dpService service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>(0);
            return service.getDevicesMatchingConnectionStates(states);
        }

        public int getConnectionState(BluetoothDevice device) {
            A2dpService service = getService();
            if (service == null) return BluetoothProfile.STATE_DISCONNECTED;
            return service.getConnectionState(device);
        }

        public boolean setPriority(BluetoothDevice device, int priority) {
            A2dpService service = getService();
            if (service == null) return false;
            return service.setPriority(device, priority);
        }

        public int getPriority(BluetoothDevice device) {
            A2dpService service = getService();
            if (service == null) return BluetoothProfile.PRIORITY_UNDEFINED;
            return service.getPriority(device);
        }

        public boolean isAvrcpAbsoluteVolumeSupported() {
            A2dpService service = getService();
            if (service == null) return false;
            return service.isAvrcpAbsoluteVolumeSupported();
        }

        public void adjustAvrcpAbsoluteVolume(int direction) {
            A2dpService service = getService();
            if (service == null) return;
            service.adjustAvrcpAbsoluteVolume(direction);
        }

        public void setAvrcpAbsoluteVolume(int volume) {
            A2dpService service = getService();
            if (service == null) return;
            service.setAvrcpAbsoluteVolume(volume);
        }

        public boolean isA2dpPlaying(BluetoothDevice device) {
            A2dpService service = getService();
            if (service == null) return false;
            return service.isA2dpPlaying(device);
        }
    };

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        if (mStateMachine != null) {
            mStateMachine.dump(sb);
        }
        if (mAvrcp != null) {
            mAvrcp.dump(sb);
        }
    }
}
