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

/**
 * Bluetooth A2dp StateMachine
 *                      (Disconnected)
 *                           |    ^
 *                   CONNECT |    | DISCONNECTED
 *                           V    |
 *                         (Pending)
 *                           |    ^
 *                 CONNECTED |    | CONNECT
 *                           V    |
 *                        (Connected)
 *                           |    ^
 *  CONNECTING/DISCONNECTING |    | CONNECTED/DISCONNECTED
 *                           V    |
 *                  (MultiConnectionpending)

 */
package com.android.bluetooth.a2dp;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetooth;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.content.Intent;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ParcelUuid;
import android.util.Log;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class A2dpStateMachine extends StateMachine {
    private static final boolean DBG = true;
    private static final String TAG="A2dpStateMachine";

    static final int CONNECT = 1;
    static final int DISCONNECT = 2;
    private static final int STACK_EVENT = 101;
    private static final int CONNECT_TIMEOUT = 201;
    /* Allow time for possible LMP response timeout + Page timeout */
    private static final int CONNECT_TIMEOUT_SEC = 38000;


    // Max number of A2dp connections at any time
    private int maxA2dpConnections = 1;

    private static final int IS_INVALID_DEVICE = 0;
    private static final int IS_VALID_DEVICE = 1;

    // enable disable multicast
    private static final int DISABLE_MULTICAST = 0;
    private static final int ENABLE_MULTICAST = 1;
    private static boolean isMultiCastEnabled = false;
    private static boolean isScanDisabled = false;
    private static boolean isMultiCastFeatureEnabled = false;

    private Disconnected mDisconnected;
    private Pending mPending;
    private Connected mConnected;
    // Multi A2dp: add new class object
    private MultiConnectionPending mMultiConnectionPending;

    private A2dpService mService;
    private Context mContext;
    private BluetoothAdapter mAdapter;
    private final AudioManager mAudioManager;
    private IntentBroadcastHandler mIntentBroadcastHandler;
    private final WakeLock mWakeLock;

    private static final int MSG_CONNECTION_STATE_CHANGED = 0;

    // mCurrentDevice is the device connected before the state changes
    // mTargetDevice is the device to be connected
    // mIncomingDevice is the device connecting to us, valid only in Pending state
    //                when mIncomingDevice is not null, both mCurrentDevice
    //                  and mTargetDevice are null
    //                when either mCurrentDevice or mTargetDevice is not null,
    //                  mIncomingDevice is null
    // mMultiDisconnectDevice is the device for which disconnect is initiated
    // in connected state.It is cleared when disconnected event is recieved
    // from stack.
    // Stable states
    //   No connection, Disconnected state
    //                  both mCurrentDevice and mTargetDevice are null
    //   Connected, Connected state
    //              mCurrentDevice is not null, mTargetDevice is null
    // Interim states
    //   Connecting to a device, Pending
    //                           mCurrentDevice is null, mTargetDevice is not null
    //   Disconnecting device, Connecting to new device
    //     Pending
    //     Both mCurrentDevice and mTargetDevice are not null
    //   Disconnecting device Pending
    //                        mCurrentDevice is not null, mTargetDevice is null
    //   Incoming connections Pending
    //                        Both mCurrentDevice and mTargetDevice are null
    // MultiConnectionPending to hanle connection/disconnection for new device
    // when already connected to one device

    private BluetoothDevice mCurrentDevice = null;
    private BluetoothDevice mTargetDevice = null;
    private BluetoothDevice mIncomingDevice = null;
    private BluetoothDevice mMultiDisconnectDevice = null;
    // Multi A2dp: Connected devices list holds all currently connected headsets
    private ArrayList<BluetoothDevice> mConnectedDevicesList =
            new ArrayList<BluetoothDevice>();
    private ArrayList<BluetoothDevice> mPlayingA2dpDevice =
            new ArrayList<BluetoothDevice>();

    static {
        classInitNative();
    }

    private A2dpStateMachine(A2dpService svc, Context context, int
            maxConnections, int multiCastState) {
        super("A2dpStateMachine");
        mService = svc;
        mContext = context;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        maxA2dpConnections = maxConnections;
        // By default isMultiCastEnabled is set to false, value changes based on stack update
        isMultiCastEnabled = false;
        if (multiCastState == 1) {
            isMultiCastFeatureEnabled = true;
        } else {
            isMultiCastFeatureEnabled = false;
        }

        initNative(maxA2dpConnections, multiCastState);

        mDisconnected = new Disconnected();
        mPending = new Pending();
        mConnected = new Connected();
        // Multi A2dp: initialise new class variable
        mMultiConnectionPending = new MultiConnectionPending();

        addState(mDisconnected);
        addState(mPending);
        addState(mConnected);
        // Multi A2dp: add State
        addState(mMultiConnectionPending);

        setInitialState(mDisconnected);

        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BluetoothA2dpService");

        mIntentBroadcastHandler = new IntentBroadcastHandler();

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    static A2dpStateMachine make(A2dpService svc, Context context,
             int maxConnections, int multiCastState) {
        Log.d("A2dpStateMachine", "make");
        A2dpStateMachine a2dpSm = new A2dpStateMachine(svc, context,
                 maxConnections, multiCastState);
        a2dpSm.start();
        return a2dpSm;
    }

    public void doQuit() {
        if ((mTargetDevice != null) &&
            (getConnectionState(mTargetDevice) == BluetoothProfile.STATE_CONNECTING)) {
            log("doQuit()- Move A2DP State to DISCONNECTED");
            broadcastConnectionState(mTargetDevice, BluetoothProfile.STATE_DISCONNECTED,
                                     BluetoothProfile.STATE_CONNECTING);
        }

        if ((mIncomingDevice!= null) &&
            (getConnectionState(mIncomingDevice) == BluetoothProfile.STATE_CONNECTING)) {
            log("doQuit()- Move A2DP State to DISCONNECTED");
            broadcastConnectionState(mIncomingDevice, BluetoothProfile.STATE_DISCONNECTED,
                                     BluetoothProfile.STATE_CONNECTING);
        }

        quitNow();
    }

    public void cleanup() {
        cleanupNative();
    }

        private class Disconnected extends State {
        @Override
        public void enter() {
            log("Enter Disconnected: " + getCurrentMessage().what);
            log("mConnectedDevicesList size: " + mConnectedDevicesList.size());
            // Remove Timeout msg when moved to stable state
            removeMessages(CONNECT_TIMEOUT);
            mCurrentDevice = null;
        }

        @Override
        public boolean processMessage(Message message) {
            log("Disconnected process message: " + message.what);
            log("mConnectedDevicesList size: " + mConnectedDevicesList.size());
            if (mCurrentDevice != null || mTargetDevice != null  || mIncomingDevice != null
                    || mConnectedDevicesList.size() != 0) {
                loge("ERROR: mConnectedDevicesList is not empty," +
                        "current, target, or mIncomingDevice not null in Disconnected");
                loge("mCurrentDevice is " + mCurrentDevice);
                loge("mTargetDevice is " + mTargetDevice);
                loge("mIncomingDevice is " + mIncomingDevice);
                loge("mConnectedDevicesList.size() is " + mConnectedDevicesList.size());
                return NOT_HANDLED;
            }

            boolean retValue = HANDLED;
            switch(message.what) {
                case CONNECT:
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                   BluetoothProfile.STATE_DISCONNECTED);

                    if (!connectA2dpNative(getByteAddress(device)) ) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                       BluetoothProfile.STATE_CONNECTING);
                        break;
                    }

                    synchronized (A2dpStateMachine.this) {
                        mTargetDevice = device;
                        transitionTo(mPending);
                    }
                    // TODO(BT) remove CONNECT_TIMEOUT when the stack
                    //          sends back events consistently
                    Message m = obtainMessage(CONNECT_TIMEOUT);
                    m.obj = device;
                    sendMessageDelayed(m, CONNECT_TIMEOUT_SEC);
                    break;
                case DISCONNECT:
                    // ignore
                    break;
                case STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        default:
                            loge("Unexpected stack event: " + event.type);
                            break;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

        @Override
        public void exit() {
            log("Exit Disconnected: " + getCurrentMessage().what);
        }

        // in Disconnected state
        private void processConnectionEvent(int state, BluetoothDevice device) {
            log("processConnectionEvent state = " + state +
                    ", device = " + device);
            switch (state) {
            case CONNECTION_STATE_DISCONNECTED:
                logw("Ignore A2DP DISCONNECTED event, device: " + device);
                break;
            case CONNECTION_STATE_CONNECTING:
                if (okToConnect(device)) {
                    logi("Incoming A2DP accepted");
                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                             BluetoothProfile.STATE_DISCONNECTED);
                    synchronized (A2dpStateMachine.this) {
                        mIncomingDevice = device;
                        transitionTo(mPending);
                    }
                } else {
                    //reject the connection and stay in Disconnected state itself
                    logi("Incoming A2DP rejected");
                    disconnectA2dpNative(getByteAddress(device));
                    // the other profile connection should be initiated
                    AdapterService adapterService = AdapterService.getAdapterService();
                    if (adapterService != null) {
                        adapterService.connectOtherProfile(device,
                                AdapterService.PROFILE_CONN_REJECTED);
                    }
                }
                break;
            case CONNECTION_STATE_CONNECTED:
                logw("A2DP Connected from Disconnected state");
                if (okToConnect(device)) {
                    logi("Incoming A2DP accepted");
                    synchronized (A2dpStateMachine.this) {
                        if (!mConnectedDevicesList.contains(device)) {
                            mConnectedDevicesList.add(device);
                            log( "device " + device.getAddress() +
                                    " is adding in Disconnected state");
                        }
                        mCurrentDevice = device;
                        transitionTo(mConnected);
                    }
                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                             BluetoothProfile.STATE_DISCONNECTED);
                } else {
                    //reject the connection and stay in Disconnected state itself
                    logi("Incoming A2DP rejected");
                    disconnectA2dpNative(getByteAddress(device));
                    // the other profile connection should be initiated
                    AdapterService adapterService = AdapterService.getAdapterService();
                    if (adapterService != null) {
                        adapterService.connectOtherProfile(device,
                                                           AdapterService.PROFILE_CONN_REJECTED);
                    }
                }
                break;
            case CONNECTION_STATE_DISCONNECTING:
                logw("Ignore A2dp DISCONNECTING event, device: " + device);
                break;
            default:
                loge("Incorrect state: " + state);
                break;
            }
        }
    }

    private class Pending extends State {
        @Override
        public void enter() {
            log("Enter Pending: " + getCurrentMessage().what);
        }

        @Override
        public boolean processMessage(Message message) {
            log("Pending process message: " + message.what + ", size: "
                    + mConnectedDevicesList.size());

            boolean retValue = HANDLED;
            switch(message.what) {
                case CONNECT:
                    deferMessage(message);
                    break;
                case CONNECT_TIMEOUT:
                    // This is always for Outgoing connection
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    // getByteAddress has no null check
                    Log.v(TAG,"device for timeout is " + device);
                    if (device != null && (mTargetDevice == null ||
                            !mTargetDevice.equals(device))) {
                        log("Timeout for unknown device " + device);
                        onConnectionStateChanged(CONNECTION_STATE_DISCONNECTED,
                                getByteAddress(device));
                        break;
                    }
                    disconnectA2dpNative(getByteAddress(mTargetDevice));
                    onConnectionStateChanged(CONNECTION_STATE_DISCONNECTED,
                                             getByteAddress(mTargetDevice));
                    break;
                case DISCONNECT:
                    BluetoothDevice dev = (BluetoothDevice) message.obj;
                    if (mCurrentDevice != null && mTargetDevice != null &&
                        mTargetDevice.equals(dev) ) {
                        // cancel connection to the mTargetDevice
                        broadcastConnectionState(dev, BluetoothProfile.STATE_DISCONNECTED,
                                       BluetoothProfile.STATE_CONNECTING);
                        synchronized (A2dpStateMachine.this) {
                            mTargetDevice = null;
                        }
                    } else {
                        deferMessage(message);
                    }
                    break;
                case STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        default:
                            loge("Unexpected stack event: " + event.type);
                            break;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

        // in Pending state
        private void processConnectionEvent(int state, BluetoothDevice device) {
            log("processConnectionEvent state = " + state +
                    ", device = " + device);
            switch (state) {
                case CONNECTION_STATE_DISCONNECTED:
                    // remove this device from playing device list
                    if (mPlayingA2dpDevice.size() != 0 &&
                            mPlayingA2dpDevice.contains(device)) {
                        log ("Playing A2dp Device is disconnected, setting it to null");
                        broadcastAudioState(device,
                                BluetoothA2dp.STATE_NOT_PLAYING,
                                BluetoothA2dp.STATE_PLAYING);
                        mPlayingA2dpDevice.remove(device);
                    }
                    // Reset scan mode if it set due to multicast
                    Log.i(TAG,"getScanMode " + mAdapter.getScanMode() +
                        " isScanDisabled: " + isScanDisabled);
                    if (mPlayingA2dpDevice.size() <= 1 &&
                            (mAdapter.getScanMode() ==
                            BluetoothAdapter.SCAN_MODE_NONE) &&
                            isScanDisabled) {
                        isScanDisabled = false;
                        AdapterService adapterService =
                                AdapterService.getAdapterService();
                        if (adapterService != null) {
                            adapterService.restoreScanMode();
                        }
                    }
                    if (mConnectedDevicesList.contains(device)) {
                        synchronized (A2dpStateMachine.this) {
                            mConnectedDevicesList.remove(device);
                            log( "device " + device.getAddress() +
                                    " is removed in Pending state");
                        }
                        broadcastConnectionState(device,
                                BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_DISCONNECTING);
                        synchronized (A2dpStateMachine.this) {
                            mCurrentDevice = null;
                        }
                        log("disconnected for target in pending state " + mTargetDevice);
                        if (mTargetDevice != null) {
                            if (!connectA2dpNative(getByteAddress(mTargetDevice))) {
                                broadcastConnectionState(mTargetDevice,
                                        BluetoothProfile.STATE_DISCONNECTED,
                                        BluetoothProfile.STATE_CONNECTING);
                                synchronized (A2dpStateMachine.this) {
                                    mTargetDevice = null;
                                    transitionTo(mDisconnected);
                                }
                            }
                        } else {
                            synchronized (A2dpStateMachine.this) {
                                mIncomingDevice = null;
                                if (mConnectedDevicesList.size() == 0) {
                                    transitionTo(mDisconnected);
                                } else {
                                    processMultiA2dpDisconnected(device);
                                }
                            }
                        }
                    } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                        // outgoing connection failed
                        broadcastConnectionState(mTargetDevice,
                                BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_CONNECTING);
                        // check if there is some incoming connection request
                        if (mIncomingDevice != null) {
                            logi("disconnect for outgoing in pending state");
                            synchronized (A2dpStateMachine.this) {
                                mTargetDevice = null;
                            }
                            break;
                        }
                        synchronized (A2dpStateMachine.this) {
                            mTargetDevice = null;
                            if (mConnectedDevicesList.size() == 0) {
                                transitionTo(mDisconnected);
                            } else {
                                transitionTo(mConnected);
                            }
                        }
                    } else if (mIncomingDevice != null && mIncomingDevice.equals(device)) {
                        broadcastConnectionState(mIncomingDevice,
                               BluetoothProfile.STATE_DISCONNECTED,
                               BluetoothProfile.STATE_CONNECTING);
                        // check if there is some outgoing connection request
                        if (mTargetDevice != null) {
                            logi("disconnect for incoming in pending state");
                            synchronized (A2dpStateMachine.this) {
                                mIncomingDevice = null;
                            }
                            break;
                        }
                        synchronized (A2dpStateMachine.this) {
                            mIncomingDevice = null;
                            if (mConnectedDevicesList.size() == 0) {
                                transitionTo(mDisconnected);
                            } else {
                                transitionTo(mConnected);
                            }
                        }
                    } else {
                        loge("Unknown device Disconnected: " + device);
                    }
                    break;
            case CONNECTION_STATE_CONNECTED:
                if (mConnectedDevicesList.contains(device)) {
                    // disconnection failed
                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                            BluetoothProfile.STATE_DISCONNECTING);
                    if (mTargetDevice != null) {
                        broadcastConnectionState(mTargetDevice, BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_CONNECTING);
                    }
                    synchronized (A2dpStateMachine.this) {
                        mTargetDevice = null;
                        transitionTo(mConnected);
                    }
                } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                    synchronized (A2dpStateMachine.this) {
                        mCurrentDevice = mTargetDevice;
                        mConnectedDevicesList.add(mTargetDevice);
                        mTargetDevice = null;
                        log( "device " + device.getAddress() +
                                " is added in Pending state");
                        if (mIncomingDevice == null ||
                                (mIncomingDevice != null && !okToConnect(mIncomingDevice)))
                            transitionTo(mConnected);
                    }
                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                            BluetoothProfile.STATE_CONNECTING);
                } else if (mIncomingDevice != null && mIncomingDevice.equals(device)) {
                    // check for a2dp connection allowed for this device in race condition
                    if (okToConnect(mIncomingDevice)) {
                        logi("Ready to connect incoming Connection from pending state");
                        synchronized (A2dpStateMachine.this) {
                            mCurrentDevice = mIncomingDevice;
                            mConnectedDevicesList.add(mIncomingDevice);
                            mIncomingDevice = null;
                            if (mTargetDevice == null)
                                transitionTo(mConnected);
                            log( "device " + device.getAddress() +
                                    " is added in Pending state");
                        }
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                               BluetoothProfile.STATE_CONNECTING);
                    } else {
                        // A2dp connection unchecked for this device
                        loge("Incoming A2DP rejected from pending state");
                        disconnectA2dpNative(getByteAddress(device));
                        // the other profile connection should be initiated
                        AdapterService adapterService = AdapterService.getAdapterService();
                        if (adapterService != null) {
                            adapterService.connectOtherProfile(device,
                                     AdapterService.PROFILE_CONN_REJECTED);
                        }
                    }
                } else {
                    loge("Unknown device Connected: " + device);
                    // something is wrong here, but sync our state with stack
                    if (okToConnect(device)) {
                        synchronized (A2dpStateMachine.this) {
                            mConnectedDevicesList.add(device);
                            if (mTargetDevice != null) {
                                log("Waiting for Connected event for mTargetDevice");
                            } else if (mIncomingDevice != null) {
                                log("Waiting for Connected event for mIncomingDevice");
                            }
                            log( "device " + device.getAddress() +
                                    " is added in Pending state");
                        }
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                BluetoothProfile.STATE_DISCONNECTED);
                    } else {
                        //reject the connection and stay in Pending state itself
                        Log.i(TAG,"Incoming A2dp rejected. priority=" +
                                mService.getPriority(device) + " bondState=" +
                                device.getBondState());
                        disconnectA2dpNative(getByteAddress(device));
                        // the other profile connection should be initiated
                        AdapterService adapterService = AdapterService.getAdapterService();
                        if (adapterService != null) {
                            adapterService.connectOtherProfile(device,
                                     AdapterService.PROFILE_CONN_REJECTED);
                        }
                    }
                }
                break;
            case CONNECTION_STATE_CONNECTING:
                if (mConnectedDevicesList.contains(device)) {
                    log("current device tries to connect back");
                    // TODO(BT) ignore or reject
                } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                    // The stack is connecting to target device or
                    // there is an incoming connection from the target device at the same time
                    // we already broadcasted the intent, doing nothing here
                    log("Stack and target device are connecting");
                }
                else if (mIncomingDevice != null) {
                    if (mIncomingDevice.equals(device)) {
                        loge("Connecting for same device");
                    } else {
                        log("Processing incoming " + mIncomingDevice +
                                " Rejecting incoming " + device);
                        disconnectA2dpNative(getByteAddress(device));
                    }
                } else {
                    // We get an incoming connecting request while Pending
                    // TODO(BT) is stack handing this case? let's ignore it for now
                    log("Incoming connection while pending, accept it");
                    if (okToConnect(device)) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                BluetoothProfile.STATE_DISCONNECTED);
                        mIncomingDevice = device;
                    } else {
                        disconnectA2dpNative(getByteAddress(device));
                        // the other profile connection should be initiated
                        AdapterService adapterService = AdapterService.getAdapterService();
                        if (adapterService != null) {
                            adapterService.connectOtherProfile(device,
                                    AdapterService.PROFILE_CONN_REJECTED);
                        }
                    }
                }
                break;
            case CONNECTION_STATE_DISCONNECTING:
                if ((mCurrentDevice != null) && mCurrentDevice.equals(device)) {
                    // we already broadcasted the intent, doing nothing here
                    if (DBG) {
                        log("stack is disconnecting mCurrentDevice");
                    }
                } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                    loge("TargetDevice is getting disconnected");
                } else if (mIncomingDevice != null && mIncomingDevice.equals(device)) {
                    loge("IncomingDevice is getting disconnected");
                } else {
                    loge("Disconnecting unknow device: " + device);
                }
                break;
            default:
                loge("Incorrect state: " + state);
                break;
            }
        }

        private void processMultiA2dpDisconnected(BluetoothDevice device) {
            log("Pending state: processMultiA2dpDisconnected");
            /* Assign the current activedevice again if the disconnected
            device equals to the current active device*/
            if (mCurrentDevice != null && mCurrentDevice.equals(device)) {
                int deviceSize = mConnectedDevicesList.size();
                mCurrentDevice = mConnectedDevicesList.get(deviceSize-1);
            }
            transitionTo(mConnected);
            log("processMultiA2dpDisconnected , the latest mCurrentDevice is:"
                    + mCurrentDevice);
            log("Pending state: processMultiA2dpDisconnected ," +
                    "fake broadcasting for new mCurrentDevice");
            broadcastConnectionState(mCurrentDevice, BluetoothProfile.STATE_CONNECTED,
                    BluetoothProfile.STATE_DISCONNECTED);
        }
    }

    private class Connected extends State {
        @Override
        public void enter() {
            // Remove pending connection attempts that were deferred during the pending
            // state. This is to prevent auto connect attempts from disconnecting
            // devices that previously successfully connected.
            // TODO: This needs to check for multiple A2DP connections, once supported...
            log("Enter Connected: " + getCurrentMessage().what +
                    ", size: " + mConnectedDevicesList.size());
            // remove timeout for connected device only.
            if (getDeviceForMessage(CONNECT_TIMEOUT) == null) {
                removeMessages(CONNECT_TIMEOUT);
            }
            // Upon connected, the audio starts out as stopped
            broadcastAudioState(mCurrentDevice, BluetoothA2dp.STATE_NOT_PLAYING,
                                BluetoothA2dp.STATE_PLAYING);
        }

        @Override
        public boolean processMessage(Message message) {
            log("Connected process message: " + message.what +
                     ", size: " + mConnectedDevicesList.size());
            if (mCurrentDevice == null) {
                loge("ERROR: mCurrentDevice is null in Connected");
                return NOT_HANDLED;
            }

            boolean retValue = HANDLED;
            switch(message.what) {
                case CONNECT:
                {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (device == null) {
                        Log.e(TAG,"device is NULL");
                        break;
                    }
                    if (mConnectedDevicesList.contains(device)) {
                        Log.e(TAG, "ERROR: Connect received for already connected device, Ignore");
                        break;
                    }
                    if (mConnectedDevicesList.size() >= maxA2dpConnections) {
                        BluetoothDevice disconnectConnectedDevice = null;
                        log( "Reach to max size, disconnect one of them first");
                        disconnectConnectedDevice = mConnectedDevicesList.get(0);
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                BluetoothProfile.STATE_DISCONNECTED);
                        if (!disconnectA2dpNative(getByteAddress(disconnectConnectedDevice))) {
                            broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                    BluetoothProfile.STATE_CONNECTING);
                            break;
                        } else {
                            broadcastConnectionState(disconnectConnectedDevice,
                                    BluetoothProfile.STATE_DISCONNECTING,
                                    BluetoothProfile.STATE_CONNECTED);
                        }
                        synchronized (A2dpStateMachine.this) {
                            mTargetDevice = device;
                            if (maxA2dpConnections == 1) {
                                transitionTo(mPending);
                            } else {
                                mMultiDisconnectDevice = disconnectConnectedDevice;
                                transitionTo(mMultiConnectionPending);
                            }
                            disconnectConnectedDevice = null;
                        }
                    } else if (mConnectedDevicesList.size() < maxA2dpConnections) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                BluetoothProfile.STATE_DISCONNECTED);
                        if (!connectA2dpNative(getByteAddress(device))) {
                            broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                    BluetoothProfile.STATE_CONNECTING);
                            break;
                        }
                        synchronized (A2dpStateMachine.this) {
                            mTargetDevice = device;
                            // Transtion to MultiConnectionPending state for
                            // Multi A2dp connection
                            transitionTo(mMultiConnectionPending);
                        }
                    }
                    Message m = obtainMessage(CONNECT_TIMEOUT);
                    m.obj = device;
                    sendMessageDelayed(m, CONNECT_TIMEOUT_SEC);

                }
                    break;
                case DISCONNECT:
                {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mConnectedDevicesList.contains(device)) {
                        log("device not connected " + device);
                        break;
                    }
                    /* do not remove playing device here, wait for
                     * disconnected event from stack to remove from palying
                     * device.*/
                    broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTING,
                            BluetoothProfile.STATE_CONNECTED);
                    if (!disconnectA2dpNative(getByteAddress(device))) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                BluetoothProfile.STATE_DISCONNECTING);
                        break;
                    }
                    if (mConnectedDevicesList.size() > 1) {
                        mMultiDisconnectDevice = device;
                        transitionTo(mMultiConnectionPending);
                    } else {
                        transitionTo(mPending);
                    }
                }
                    break;
                case STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        case EVENT_TYPE_AUDIO_STATE_CHANGED:
                            processAudioStateEvent(event.valueInt, event.device);
                            break;
                        default:
                            loge("Unexpected stack event: " + event.type);
                            break;
                    }
                    break;
                case CONNECT_TIMEOUT:
                    BluetoothDevice timedOutDevice = getDeviceForMessage(CONNECT_TIMEOUT);
                    if ((mTargetDevice == null) || (timedOutDevice == null)) {
                        loge("CONNECT_TIMEOUT received : targetDevice : " +
                            mTargetDevice + " : timedout device : " + timedOutDevice);
                    } else if(mTargetDevice.equals(timedOutDevice)) {
                        loge("CONNECT_TIMEOUT received : connected devices : " +
                            mConnectedDevicesList.size() +
                            " : timedout device : " + timedOutDevice);
                        broadcastConnectionState(mTargetDevice,
                                BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_CONNECTING);
                        mTargetDevice = null;
                    } else {
                        /* Should not hit this
                         */
                        loge("CONNECT_TIMEOUT received : connected devices : " +
                            mConnectedDevicesList.size() +
                            " : targetDevice : " + mTargetDevice +
                            " : timedout device : " + timedOutDevice);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

        // in Connected state
        private void processConnectionEvent(int state, BluetoothDevice device) {
            log( "processConnectionEvent state = " + state + ", device = "
                    + device);
            switch (state) {
                case CONNECTION_STATE_DISCONNECTED:
                    if (mConnectedDevicesList.contains(device)) {
                        // if device is playing then remove it from playing
                        // device list.
                        if (mPlayingA2dpDevice.size() != 0 &&
                                mPlayingA2dpDevice.contains(device)) {
                            log ("Playing A2dp Device is disconnected, setting it to null");
                            broadcastAudioState(device,
                                    BluetoothA2dp.STATE_NOT_PLAYING,
                                    BluetoothA2dp.STATE_PLAYING);
                            mPlayingA2dpDevice.remove(device);
                        }
                        // Reset scan mode if it set due to multicast
                        Log.i(TAG,"getScanMode: " + mAdapter.getScanMode() +
                            " isScanDisabled: " + isScanDisabled);
                        if (mPlayingA2dpDevice.size() <= 1 &&
                                (mAdapter.getScanMode() ==
                                BluetoothAdapter.SCAN_MODE_NONE) &&
                                isScanDisabled) {
                            isScanDisabled = false;
                            AdapterService adapterService =
                                    AdapterService.getAdapterService();
                            if (adapterService != null) {
                                adapterService.restoreScanMode();
                            }
                        }
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                             BluetoothProfile.STATE_CONNECTED);
                        synchronized (A2dpStateMachine.this) {
                            mConnectedDevicesList.remove(device);
                            log( "device " + device.getAddress() +
                                    " is removed in Connected state");
                            if (mConnectedDevicesList.size() == 0) {
                                mCurrentDevice = null;
                                // cleanup mMultiDisconnectDevice, if incoming
                                // disconnect is processed first.
                                if (mMultiDisconnectDevice != null)
                                    mMultiDisconnectDevice = null;
                                transitionTo(mDisconnected);
                            } else {
                                processMultiA2dpDisconnected(device);
                            }
                        }
                    } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                                 BluetoothProfile.STATE_CONNECTING);
                        synchronized (A2dpStateMachine.this) {
                            mTargetDevice = null;
                        }
                        logi("Disconnected from mTargetDevice in connected state device: " +
                                device);
                    } else {
                        loge("Disconnected from unknown device: " + device);
                    }
                    break;
                case CONNECTION_STATE_CONNECTING:
                    // 2nd incoming connection
                    log("Incoming connection in Connected State for device " +
                            device);
                    if (mConnectedDevicesList.contains(device)) {
                        log("device is already connected ");
                        mIncomingDevice = null;
                        mTargetDevice = null;
                        break;
                    }
                    // this should be never be case, as we will move to MPC
                    if (mTargetDevice != null) {
                        log("Outgoing initiated before incoming");
                        disconnectA2dpNative(getByteAddress(device));
                        // the other profile connection should be initiated
                        AdapterService adapterService = AdapterService.getAdapterService();
                        if (adapterService != null) {
                            adapterService.connectOtherProfile(device,
                                    AdapterService.PROFILE_CONN_REJECTED);
                        }
                        break;
                    }
                    if (okToConnect(device) &&
                            (mConnectedDevicesList.size() < maxA2dpConnections)) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                BluetoothProfile.STATE_DISCONNECTED);
                        mIncomingDevice = device;
                        transitionTo(mMultiConnectionPending);
                        Message m = obtainMessage(CONNECT_TIMEOUT);
                        m.obj = device;
                        sendMessageDelayed(m, CONNECT_TIMEOUT_SEC);
                    } else {
                        disconnectA2dpNative(getByteAddress(device));
                        // the other profile connection should be initiated
                        AdapterService adapterService = AdapterService.getAdapterService();
                        if (adapterService != null) {
                            adapterService.connectOtherProfile(device,
                                    AdapterService.PROFILE_CONN_REJECTED);
                        }
                    }

                    break;
                case CONNECTION_STATE_CONNECTED:
                    // 2nd incoming connection
                    log("Connected event for device " + device);
                    if (mConnectedDevicesList.contains(device)) {
                        log("device already connected ");
                        mIncomingDevice = null;
                        mTargetDevice = null;
                        break;
                    }
                    if (mMultiDisconnectDevice != null) {
                        log("disconnection failed for device");
                        mMultiDisconnectDevice = null;
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                BluetoothProfile.STATE_DISCONNECTING);
                        break;
                    }
                    if (mTargetDevice != null && mTargetDevice.equals(device) &&
                            (mConnectedDevicesList.size() < maxA2dpConnections)) {
                        synchronized (A2dpStateMachine.this) {
                            mCurrentDevice = mTargetDevice;
                            mConnectedDevicesList.add(mTargetDevice);
                            mTargetDevice = null;
                            log( "device " + device.getAddress() +
                                    " is added in Connected state");
                        }
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                BluetoothProfile.STATE_CONNECTING);
                        break;
                    }
                    Log.i(TAG,"okToConnect in connected state " + okToConnect(device));
                    if (okToConnect(device) &&
                            (mConnectedDevicesList.size() < maxA2dpConnections)) {
                        synchronized (A2dpStateMachine.this) {
                            mCurrentDevice = device;
                            mConnectedDevicesList.add(device);
                            mIncomingDevice= null;
                            log( "device " + device.getAddress() +
                                    " is added in Connected state");
                        }
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                BluetoothProfile.STATE_DISCONNECTED);
                    } else {
                        disconnectA2dpNative(getByteAddress(device));
                        // the other profile connection should be initiated
                        AdapterService adapterService = AdapterService.getAdapterService();
                        if (adapterService != null) {
                            adapterService.connectOtherProfile(device,
                                    AdapterService.PROFILE_CONN_REJECTED);
                        }
                    }
                    break;
                    // this case would never happen
                case CONNECTION_STATE_DISCONNECTING:
                    loge("Disconnecting in Connected State ignore it");
                    break;

              default:
                  loge("Connection State Device: " + device + " bad state: " + state);
                  break;
            }
        }
        private void processMultiA2dpDisconnected(BluetoothDevice device) {
            log("Connect state: processMultiA2dpDisconnected");
            /* Assign the current activedevice again if the disconnected
            device equals to the current active device */
            if (mCurrentDevice != null && mCurrentDevice.equals(device)) {
                int deviceSize = mConnectedDevicesList.size();
                mCurrentDevice = mConnectedDevicesList.get(deviceSize-1);
            }
            log("processMultiA2dpDisconnected, the latest mCurrentDevice is:" +
                    mCurrentDevice);
            log("Connect state: processMultiA2dpDisconnected ," +
                    "fake broadcasting for mCurrentDevice");
            broadcastConnectionState(mCurrentDevice, BluetoothProfile.STATE_CONNECTED,
                   BluetoothProfile.STATE_DISCONNECTED);
        }

        private void processAudioStateEvent(int state, BluetoothDevice device) {
            if (!mConnectedDevicesList.contains(device)) {
                loge("Audio State Device:" + device + "is not in mConnectedDevicesList" +
                        mCurrentDevice);
                return;
            }
            log("connectedState: processAudioStateEvent state: " + state + " device "
                    + device);
            log("mPlayingA2dpDevice size is " + mPlayingA2dpDevice.size());
            log("mConnectedDevicesList size is " +
                    mConnectedDevicesList.contains(device));
            switch (state) {
                case AUDIO_STATE_STARTED:
                    synchronized (A2dpStateMachine.this) {
                        if (mConnectedDevicesList.contains(device) &&
                                !(mPlayingA2dpDevice.size() != 0 &&
                                mPlayingA2dpDevice.contains(device))) {
                            /* set scan mode before adding device to mPlayingA2dpDevice
                             * so that scan mode is set to last set mode after multicast
                             * is stopped. */
                            if (mPlayingA2dpDevice.size() == 1) {
                                Log.i(TAG,"setScanMode:SCAN_MODE_NONE");
                                isScanDisabled = true;
                                mAdapter.setScanMode(BluetoothAdapter.SCAN_MODE_NONE);
                            }
                            mPlayingA2dpDevice.add(device);
                            mService.setAvrcpAudioState(BluetoothA2dp.STATE_PLAYING, device);
                            broadcastAudioState(device, BluetoothA2dp.STATE_PLAYING,
                                    BluetoothA2dp.STATE_NOT_PLAYING);
                        }
                        /* cancel any discovery if in progress and scan mode to
                         * none when multicast is active.Set flag to reset
                         * scan mode if changed due to multicast.*/
                        if (mPlayingA2dpDevice.size() == 2) {
                            if (mAdapter.isDiscovering()) {
                                mAdapter.cancelDiscovery();
                            }
                        }
                    }
                    break;
                case AUDIO_STATE_STOPPED:
                case AUDIO_STATE_REMOTE_SUSPEND:
                    synchronized (A2dpStateMachine.this) {
                        if (mPlayingA2dpDevice.size() != 0 &&
                                mPlayingA2dpDevice.contains(device)) {
                            mPlayingA2dpDevice.remove(device);
                            mService.setAvrcpAudioState(BluetoothA2dp.STATE_NOT_PLAYING, device);
                            broadcastAudioState(device, BluetoothA2dp.STATE_NOT_PLAYING,
                                     BluetoothA2dp.STATE_PLAYING);
                        }
                        // Reset scan mode if it set due to multicast
                        Log.i(TAG,"getScanMode: " + mAdapter.getScanMode() +
                            " isScanDisabled: " + isScanDisabled);
                        if (mPlayingA2dpDevice.size() <= 1 &&
                                (mAdapter.getScanMode() == BluetoothAdapter.SCAN_MODE_NONE) &&
                                isScanDisabled) {
                            isScanDisabled = false;
                            AdapterService adapterService = AdapterService.getAdapterService();
                            if (adapterService != null) {
                                adapterService.restoreScanMode();
                            }
                        }
                    }
                    break;
                default:
                  loge("Audio State Device: " + device + " bad state: " + state);
                  break;
            }
        }
    }
    /* Add MultiConnectionPending state when atleast 1 HS is connected
        and disconnect/connect is initiated for new HS */
    private class MultiConnectionPending extends State {
        @Override
        public void enter() {
            log("Enter MultiConnectionPending: " + getCurrentMessage().what +
                    ", size: " + mConnectedDevicesList.size());
        }

        public boolean processMessage(Message message) {
            log( "MultiConnectionPending process message: " + message.what +
                    ", size: " + mConnectedDevicesList.size());
            boolean retValue = HANDLED;
            switch(message.what) {
                case CONNECT:
                    deferMessage(message);
                     break;
                case DISCONNECT:
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (mConnectedDevicesList.contains(device) &&
                            mTargetDevice != null && mTargetDevice.equals(device)) {
                        // cancel connection to the mTargetDevice
                        broadcastConnectionState(device,
                                BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_CONNECTING);
                        synchronized (A2dpStateMachine.this) {
                            mTargetDevice = null;
                        }
                    } else {
                        deferMessage(message);
                    }
                    break;
                case CONNECT_TIMEOUT:
                    // This is always for Outgoing connection
                    BluetoothDevice dev = (BluetoothDevice)message.obj;
                    // getByteAddress has no null check
                    log("Timeout for device in MCP " + dev);
                    if ((dev != null) && (mTargetDevice == null ||
                            !mTargetDevice.equals(dev))) {
                           log("Timeout for incoming device " + dev);
                        onConnectionStateChanged(CONNECTION_STATE_DISCONNECTED,
                                getByteAddress(dev));
                        break;
                    }
                    disconnectA2dpNative(getByteAddress(mTargetDevice));
                    onConnectionStateChanged(CONNECTION_STATE_DISCONNECTED,
                            getByteAddress(mTargetDevice));
                    break;
                case STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        case EVENT_TYPE_AUDIO_STATE_CHANGED:
                            processAudioStateEvent(event.valueInt, event.device);
                            break;
                        default:
                            loge("Unexpected stack event: " + event.type);
                            break;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

        @Override
        public void exit() {
            log("Exit MultiConnectionPending: " + getCurrentMessage().what);
        }

        // in MultiConnectionPending state
        private void processConnectionEvent(int state, BluetoothDevice device) {
            log("processConnectionEvent state = " + state +
                    ", device = " + device);
            switch (state) {
                case CONNECTION_STATE_DISCONNECTED:
                    if (mConnectedDevicesList.contains(device)) {
                        if (mPlayingA2dpDevice.size() != 0 &&
                                mPlayingA2dpDevice.contains(device)) {
                            log ("mPlayingA2dpDevice is disconnected, setting it to null");
                            broadcastAudioState(device, BluetoothA2dp.STATE_NOT_PLAYING,
                                    BluetoothA2dp.STATE_PLAYING);
                            mPlayingA2dpDevice.remove(device);
                        }
                        // Reset scan mode if it set due to multicast
                        Log.i(TAG,"getScanMode: " + mAdapter.getScanMode() +
                            " isScanDisabled: " + isScanDisabled);
                        if (mPlayingA2dpDevice.size() <= 1 &&
                                (mAdapter.getScanMode() ==
                                BluetoothAdapter.SCAN_MODE_NONE) &&
                                isScanDisabled) {
                            isScanDisabled = false;
                            AdapterService adapterService =
                                    AdapterService.getAdapterService();
                            if (adapterService != null) {
                                adapterService.restoreScanMode();
                            }
                        }
                        if (mMultiDisconnectDevice != null &&
                                mMultiDisconnectDevice.equals(device)) {
                            mMultiDisconnectDevice = null;
                            synchronized (A2dpStateMachine.this) {
                                mConnectedDevicesList.remove(device);
                                log( "device " + device.getAddress() +
                                        " is removed in MultiConnectionPending state");
                            }
                            broadcastConnectionState(device,
                                    BluetoothProfile.STATE_DISCONNECTED,
                                    BluetoothProfile.STATE_DISCONNECTING);
                            if (mTargetDevice != null) {
                                if (!connectA2dpNative(getByteAddress(mTargetDevice))) {
                                    broadcastConnectionState(mTargetDevice,
                                            BluetoothProfile.STATE_DISCONNECTED,
                                            BluetoothProfile.STATE_CONNECTING);
                                    synchronized (A2dpStateMachine.this) {
                                        mTargetDevice = null;
                                        if (mConnectedDevicesList.size() == 0) {
                                            // Should be not in this state since it has at least
                                            // one HF connected in MultiHFPending state
                                            log( "Should be not in this state, error handling");
                                            transitionTo(mDisconnected);

                                        } else {
                                            processMultiA2dpDisconnected(device);
                                        }
                                    }
                                }
                            }  else {
                                synchronized (A2dpStateMachine.this) {
                                    mIncomingDevice = null;
                                    if (mConnectedDevicesList.size() == 0) {
                                        transitionTo(mDisconnected);
                                    } else {
                                        processMultiA2dpDisconnected(device);
                                    }
                                }
                            }
                        } else {
                            /* HS disconnected, when other HS is connected */
                            synchronized (A2dpStateMachine.this) {
                                mConnectedDevicesList.remove(device);

                                log( "device " + device.getAddress() +
                                        " is removed in MultiConnectionPending state");
                            }
                            broadcastConnectionState(device,
                                    BluetoothProfile.STATE_DISCONNECTED,
                                    BluetoothProfile.STATE_CONNECTED);
                        }
                    } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                        broadcastConnectionState(mTargetDevice,
                                BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_CONNECTING);
                        synchronized (A2dpStateMachine.this) {
                            mTargetDevice = null;
                            if (mConnectedDevicesList.size() == 0) {
                                transitionTo(mDisconnected);
                            } else {
                                transitionTo(mConnected);
                            }
                        }
                    } else if (mIncomingDevice!= null && mIncomingDevice.equals(device)) {
                        // incoming connection failure
                        broadcastConnectionState(mIncomingDevice,
                                BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_CONNECTING);
                        synchronized (A2dpStateMachine.this) {
                            mIncomingDevice = null;
                            if (mConnectedDevicesList.size() == 0) {
                                transitionTo(mDisconnected);
                            } else {
                                transitionTo(mConnected);
                            }
                        }
                    } else {
                        // mTargetDevice & mIncomingDevice is made null when
                        // 3rd device is connected, hence move to connected
                        // state if mConnectedDevicesList size is 2 and other
                        // device are null
                        if (mTargetDevice == null && mIncomingDevice == null &&
                                (mConnectedDevicesList.size() ==
                                maxA2dpConnections)) {
                            transitionTo(mConnected);
                        }
                        Log.e(TAG, "Unknown device Disconnected: " + device);
                    }

                    break;
                case CONNECTION_STATE_CONNECTING:
                    if (mTargetDevice != null && mTargetDevice.equals(device)) {
                        log("Outgoing Connection initiated, Ignore it");
                    } else if (mIncomingDevice!= null &&
                            mIncomingDevice.equals(device)) {
                        log("Incoming connection from same device, Ignore it");
                    } else if (mConnectedDevicesList.contains(device)) {
                        log("current device tries to connect back");
                    } else {
                        log("Connection event from new device " + device);
                        if (okToConnect(device) &&
                                (mConnectedDevicesList.size() < maxA2dpConnections)) {
                            broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                    BluetoothProfile.STATE_DISCONNECTED);
                            mIncomingDevice = device;
                        } else {
                            disconnectA2dpNative(getByteAddress(device));
                            // the other profile connection should be initiated
                            AdapterService adapterService = AdapterService.getAdapterService();
                            if (adapterService != null) {
                                adapterService.connectOtherProfile(device,
                                        AdapterService.PROFILE_CONN_REJECTED);
                            }
                        }
                    }
                    break;
                case CONNECTION_STATE_CONNECTED:
                    log("Connected event for device " + device);
                    if (mConnectedDevicesList.size() == maxA2dpConnections) {

                        // Unkown device connected, check for target and
                        // incoming devices, make them null and broadcast
                        // disconnection for them
                        if (mTargetDevice != null && mTargetDevice.equals(device)) {
                            log("mTargetDevice, connected list is full");
                            broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                   BluetoothProfile.STATE_CONNECTING);
                            synchronized (A2dpStateMachine.this) {
                                mTargetDevice = null;
                            }
                            disconnectA2dpNative(getByteAddress(device));
                            onConnectionStateChanged(CONNECTION_STATE_DISCONNECTED,
                                    getByteAddress(device));
                        }
                        if (mIncomingDevice != null && mIncomingDevice.equals(device)) {
                            log("mIncomingDevice connected list is full");
                            broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                    BluetoothProfile.STATE_CONNECTING);
                            synchronized (A2dpStateMachine.this) {
                                mIncomingDevice = null;
                            }

                            disconnectA2dpNative(getByteAddress(device));
                            onConnectionStateChanged(CONNECTION_STATE_DISCONNECTED,
                                    getByteAddress(device));
                        }
                    }
                    if (mConnectedDevicesList.contains(device)) {
                        log("Disconnection failed event for device " + device);
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                BluetoothProfile.STATE_DISCONNECTING);
                        if (mTargetDevice != null) {
                            broadcastConnectionState(mTargetDevice,
                                    BluetoothProfile.STATE_DISCONNECTED,
                                    BluetoothProfile.STATE_CONNECTING);
                        }
                        synchronized (A2dpStateMachine.this) {
                            mTargetDevice = null;
                            transitionTo(mConnected);
                        }
                    } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                        synchronized (A2dpStateMachine.this) {
                            mCurrentDevice = device;
                            mConnectedDevicesList.add(device);
                            log( "device " + device.getAddress() +
                                    " is added in MultiConnectionPending state");
                            mTargetDevice = null;
                            transitionTo(mConnected);
                        }
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                BluetoothProfile.STATE_CONNECTING);
                    } else if (mIncomingDevice!= null && mIncomingDevice.equals(device)) {
                        synchronized (A2dpStateMachine.this) {
                            mCurrentDevice = device;
                            mConnectedDevicesList.add(device);
                            log( "device " + device.getAddress() +
                                    " is added in MultiConnectionPending state");
                            mIncomingDevice = null;
                            transitionTo(mConnected);
                        }
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                BluetoothProfile.STATE_CONNECTING);

                    } else {
                        log("Unknown Device connected");
                        if (okToConnect(device) &&
                                (mConnectedDevicesList.size() < maxA2dpConnections)) {
                            mCurrentDevice = device;
                            mConnectedDevicesList.add(device);
                            log( "device " + device.getAddress() +
                                    " is added in MultiConnectionPending state");
                            broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                    BluetoothProfile.STATE_DISCONNECTED);

                        } else {
                            disconnectA2dpNative(getByteAddress(device));
                        }

                    }
                    break;
                case CONNECTION_STATE_DISCONNECTING:
                    if (mConnectedDevicesList.contains(device)) {
                        // we already broadcasted the intent, doing nothing here
                        log("stack is disconnecting mCurrentDevice");
                    } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                        loge("TargetDevice is getting disconnected");
                    } else if (mIncomingDevice != null && mIncomingDevice.equals(device)) {
                        loge("mIncomingDevice is getting disconnected");
                    } else {
                        loge("Disconnecting unknow device: " + device);
                    }
                    break;
                default:
                    loge("Incorrect state: " + state);
                    break;

            }

        }

        private void processAudioStateEvent(int state, BluetoothDevice device) {
            if (!mConnectedDevicesList.contains(device)) {
                loge("Audio State Device:" + device + "is not in mConnectedDevicesList" +
                        mCurrentDevice);
                return;
            }
            log("MultiPendingState: processAudioStateEvent state: " + state + " device "
                    + device.getName());
            log("mPlayingA2dpDevice size is " + mPlayingA2dpDevice.size());
            log("mConnectedDevicesList size is " + mConnectedDevicesList.size());
            switch (state) {
                case AUDIO_STATE_STARTED:
                    synchronized (A2dpStateMachine.this) {
                        if (mConnectedDevicesList.contains(device) &&
                                !(mPlayingA2dpDevice.size()!= 0 &&
                                mPlayingA2dpDevice.contains(device))) {
                            /* set scan mode before adding device to mPlayingA2dpDevice
                             * so that scan mode is set to last set mode after multicast
                             * is stopped. */
                            if (mPlayingA2dpDevice.size() == 1) {
                                Log.i(TAG,"setScanMode:SCAN_MODE_NONE");
                                isScanDisabled = true;
                                mAdapter.setScanMode(BluetoothAdapter.SCAN_MODE_NONE);
                            }
                            mPlayingA2dpDevice.add(device);
                            mService.setAvrcpAudioState(BluetoothA2dp.STATE_PLAYING, device);
                            broadcastAudioState(device, BluetoothA2dp.STATE_PLAYING,
                                    BluetoothA2dp.STATE_NOT_PLAYING);
                        }
                        /* cancel any discovery if in progress and scan mode to
                         * none when multicast is active.Set flag to reset
                         * scan mode if changed due to multicast.*/
                        if (mPlayingA2dpDevice.size() == 2) {
                            if (mAdapter.isDiscovering()) {
                                mAdapter.cancelDiscovery();
                            }
                        }
                    }
                    break;
                case AUDIO_STATE_REMOTE_SUSPEND:
                case AUDIO_STATE_STOPPED:
                    synchronized (A2dpStateMachine.this) {
                        if (mPlayingA2dpDevice.size() != 0 &&
                                mPlayingA2dpDevice.contains(device)) {
                            mPlayingA2dpDevice.remove(device);
                            mService.setAvrcpAudioState(BluetoothA2dp.STATE_NOT_PLAYING, device);
                            broadcastAudioState(device, BluetoothA2dp.STATE_NOT_PLAYING,
                                    BluetoothA2dp.STATE_PLAYING);
                     }
                        // Reset scan mode if it set due to multicast
                        Log.i(TAG,"getScanMode: " + mAdapter.getScanMode() +
                            " isScanDisabled: " + isScanDisabled);
                        if (mPlayingA2dpDevice.size() <= 1 &&
                                (mAdapter.getScanMode() == BluetoothAdapter.SCAN_MODE_NONE) &&
                                isScanDisabled) {
                            isScanDisabled = false;
                            AdapterService adapterService = AdapterService.getAdapterService();
                            if (adapterService != null) {
                                adapterService.restoreScanMode();
                            }
                        }
                    }
                    break;
                default:
                  loge("Audio State Device: " + device + " bad state: " + state);
                  break;
            }
        }

        private void processMultiA2dpDisconnected(BluetoothDevice device) {
            log("processMultiA2dpDisconnected state: processMultiA2dpDisconnected");

            if (mCurrentDevice != null && mCurrentDevice.equals(device)) {
                int deviceSize = mConnectedDevicesList.size();
                mCurrentDevice = mConnectedDevicesList.get(deviceSize-1);
            }
            transitionTo(mConnected);
            log("processMultiA2dpDisconnected , the latest mCurrentDevice is:"
                    + mCurrentDevice);
            log("MultiA2dpPending state: processMultiA2dpDisconnected ," +
                    "fake broadcasting for mCurrentDevice");
            broadcastConnectionState(mCurrentDevice, BluetoothProfile.STATE_CONNECTED,
                    BluetoothProfile.STATE_DISCONNECTED);
        }
    }

    int getConnectionState(BluetoothDevice device) {
        if (getCurrentState() == mDisconnected) {
            log( "currentState is Disconnected");
            return BluetoothProfile.STATE_DISCONNECTED;
        }

        synchronized (this) {
            IState currentState = getCurrentState();
            log( "currentState = " + currentState);
            if (currentState == mPending) {
                if ((mTargetDevice != null) && mTargetDevice.equals(device)) {
                    return BluetoothProfile.STATE_CONNECTING;
                }
                if (mConnectedDevicesList.contains(device)) {
                    return BluetoothProfile.STATE_DISCONNECTING;
                }
                if ((mIncomingDevice != null) && mIncomingDevice.equals(device)) {
                    return BluetoothProfile.STATE_CONNECTING; // incoming connection
                }
                return BluetoothProfile.STATE_DISCONNECTED;
            }

            if (currentState == mMultiConnectionPending) {
                if ((mTargetDevice != null) && mTargetDevice.equals(device)) {
                    return BluetoothProfile.STATE_CONNECTING;
                }
                if ((mIncomingDevice != null) && mIncomingDevice.equals(device)) {
                    return BluetoothProfile.STATE_CONNECTING; // incoming connection
                }
                if (mConnectedDevicesList.contains(device)) {
                    if ((mMultiDisconnectDevice != null) &&
                            (!mMultiDisconnectDevice.equals(device))) {
                        // The device is still connected
                         return BluetoothProfile.STATE_CONNECTED;
                    }
                    return BluetoothProfile.STATE_DISCONNECTING;
                }
                return BluetoothProfile.STATE_DISCONNECTED;
            }

            if (currentState == mConnected) {
                if (mConnectedDevicesList.contains(device)) {
                    return BluetoothProfile.STATE_CONNECTED;
                }
                return BluetoothProfile.STATE_DISCONNECTED;
            } else {
                loge("Bad currentState: " + currentState);
                return BluetoothProfile.STATE_DISCONNECTED;
            }
        }
    }

    List<BluetoothDevice> getConnectedDevices() {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        Log.i(TAG,"mConnectedDevicesList size is " +
                mConnectedDevicesList.size());
        synchronized(this) {
            /* If connected and mCurrentDevice is not null*/
            for (int i = 0; i < mConnectedDevicesList.size(); i++) {
                devices.add(mConnectedDevicesList.get(i));
            }
        }
        return devices;
    }

    boolean isPlaying(BluetoothDevice device) {
        synchronized(this) {
            if (mPlayingA2dpDevice.contains(device)) {
                return true;
            }
        }
        return false;
    }

    public List<BluetoothDevice> getPlayingDevice() {
        return mPlayingA2dpDevice;
    }

    public boolean isMulticastEnabled() {
        return isMultiCastEnabled;
    }

    public boolean isMulticastFeatureEnabled() {
        return isMultiCastFeatureEnabled;
    }

    boolean okToConnect(BluetoothDevice device) {
        AdapterService adapterService = AdapterService.getAdapterService();
        int priority = mService.getPriority(device);
        boolean ret = false;
        //check if this is an incoming connection in Quiet mode.
        if((adapterService == null) ||
           ((adapterService.isQuietModeEnabled() == true) &&
           (mTargetDevice == null))){
            ret = false;
        }
        // check priority and accept or reject the connection. if priority is undefined
        // it is likely that our SDP has not completed and peer is initiating the
        // connection. Allow this connection, provided the device is bonded
        else if((BluetoothProfile.PRIORITY_OFF < priority) ||
                ((BluetoothProfile.PRIORITY_UNDEFINED == priority) &&
                (device.getBondState() != BluetoothDevice.BOND_NONE))){
            ret= true;
        }
        return ret;
    }

    synchronized List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>();
        Set<BluetoothDevice> bondedDevices = mAdapter.getBondedDevices();
        int connectionState;

        for (BluetoothDevice device : bondedDevices) {
            ParcelUuid[] featureUuids = device.getUuids();
            if (!BluetoothUuid.isUuidPresent(featureUuids, BluetoothUuid.AudioSink)) {
                continue;
            }
            connectionState = getConnectionState(device);
            for(int i = 0; i < states.length; i++) {
                if (connectionState == states[i]) {
                    deviceList.add(device);
                }
            }
        }
        return deviceList;
    }

    private BluetoothDevice getDeviceForMessage(int what) {
        if (what == CONNECT_TIMEOUT) {
            log("getDeviceForMessage: returning mTargetDevice for what=" + what);
            return mTargetDevice;
        }
        if (mConnectedDevicesList.size() == 0) {
            log("getDeviceForMessage: No connected device. what=" + what);
            return null;
        }
        for (BluetoothDevice device : mConnectedDevicesList){
            if (getHandler().hasMessages(what, device)) {
                log("getDeviceForMessage: returning " + device + "for what " +
                        what);
                return device;
            }
        }
        log("getDeviceForMessage: No matching device for " + what + ". Returning null");
        return null;
    }


    // This method does not check for error conditon (newState == prevState)
    private void broadcastConnectionState(BluetoothDevice device, int newState, int prevState) {

        int delay = mAudioManager.setBluetoothA2dpDeviceConnectionState(device, newState,
                BluetoothProfile.A2DP);
        Log.i(TAG,"connectoin state change " + device + " state " + newState);

        if (newState == BluetoothProfile.STATE_DISCONNECTING ||
                newState == BluetoothProfile.STATE_CONNECTING) {
            delay = 0;
        }
        mWakeLock.acquire();
        mIntentBroadcastHandler.sendMessageDelayed(mIntentBroadcastHandler.obtainMessage(
                                                        MSG_CONNECTION_STATE_CHANGED,
                                                        prevState,
                                                        newState,
                                                        device),
                                                        delay);
    }

    private void broadcastAudioState(BluetoothDevice device, int state, int prevState) {
        Intent intent = new Intent(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, state);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcast(intent, A2dpService.BLUETOOTH_PERM);

        log("A2DP Playing state : device: " + device + " State:" + prevState + "->" + state);
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
    }

    private void onConnectionStateChanged(int state, byte[] address) {
        StackEvent event = new StackEvent(EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt = state;
        event.device = getDevice(address);
        sendMessage(STACK_EVENT, event);
    }

    private void onAudioStateChanged(int state, byte[] address) {
        StackEvent event = new StackEvent(EVENT_TYPE_AUDIO_STATE_CHANGED);
        event.valueInt = state;
        event.device = getDevice(address);
        sendMessage(STACK_EVENT, event);
    }

    private void onCheckConnectionPriority(byte[] address) {
        BluetoothDevice device = getDevice(address);
        logw(" device " + device + " okToConnect " + okToConnect(device));
        if (okToConnect(device)) {
            // if connection is allowed then go ahead and connect
            allowConnectionNative(IS_VALID_DEVICE, getByteAddress(device));
        } else {
            // if connection is not allowed DO NOT CONNECT
            allowConnectionNative(IS_INVALID_DEVICE, getByteAddress(device));
        }
    }

    private void onMulticastStateChanged(int state) {
        if (state == ENABLE_MULTICAST) {
            Log.i(TAG,"A2dp Multicast is Enabled");
            isMultiCastEnabled = true;
        } else {
            Log.i(TAG,"A2dp Multicast is Disabled");
            isMultiCastEnabled = false;
        }
    }

    private BluetoothDevice getDevice(byte[] address) {
        return mAdapter.getRemoteDevice(Utils.getAddressStringFromByte(address));
    }

    private class StackEvent {
        int type = EVENT_TYPE_NONE;
        int valueInt = 0;
        BluetoothDevice device = null;

        private StackEvent(int type) {
            this.type = type;
        }
    }
    /** Handles A2DP connection state change intent broadcasts. */
    private class IntentBroadcastHandler extends Handler {

        private void onConnectionStateChanged(BluetoothDevice device, int prevState, int state) {
            Intent intent = new Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
            intent.putExtra(BluetoothProfile.EXTRA_STATE, state);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mContext.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
            log("Connection state " + device + ": " + prevState + "->" + state);
            mService.notifyProfileConnectionStateChanged(device, BluetoothProfile.A2DP, state, prevState);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CONNECTION_STATE_CHANGED:
                    onConnectionStateChanged((BluetoothDevice) msg.obj, msg.arg1, msg.arg2);
                    mWakeLock.release();
                    break;
            }
        }
    }

    public void dump(StringBuilder sb) {
        ProfileService.println(sb, "mCurrentDevice: " + mCurrentDevice);
        ProfileService.println(sb, "mTargetDevice: " + mTargetDevice);
        ProfileService.println(sb, "mIncomingDevice: " + mIncomingDevice);
        ProfileService.println(sb, "mPlayingA2dpDevice: " + mPlayingA2dpDevice);
        ProfileService.println(sb, "StateMachine: " + this.toString());
    }

    // Event types for STACK_EVENT message
    final private static int EVENT_TYPE_NONE = 0;
    final private static int EVENT_TYPE_CONNECTION_STATE_CHANGED = 1;
    final private static int EVENT_TYPE_AUDIO_STATE_CHANGED = 2;

   // Do not modify without updating the HAL bt_av.h files.

    // match up with btav_connection_state_t enum of bt_av.h
    final static int CONNECTION_STATE_DISCONNECTED = 0;
    final static int CONNECTION_STATE_CONNECTING = 1;
    final static int CONNECTION_STATE_CONNECTED = 2;
    final static int CONNECTION_STATE_DISCONNECTING = 3;

    // match up with btav_audio_state_t enum of bt_av.h
    final static int AUDIO_STATE_REMOTE_SUSPEND = 0;
    final static int AUDIO_STATE_STOPPED = 1;
    final static int AUDIO_STATE_STARTED = 2;

    private native static void classInitNative();
    private native void initNative(int maxA2dpConnectionsAllowed,
            int multiCastState);
    private native void cleanupNative();
    private native boolean connectA2dpNative(byte[] address);
    private native boolean disconnectA2dpNative(byte[] address);
    private native void allowConnectionNative(int isValid, byte[] address);
}
