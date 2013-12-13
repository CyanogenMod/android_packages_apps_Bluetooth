/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
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
 * Bluetooth Handset StateMachine
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
 *             CONNECT_AUDIO |    | DISCONNECT_AUDIO
 *                           V    |
 *                         (AudioOn)
 */
package com.android.bluetooth.hfp;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAssignedNumbers;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothHeadsetPhone;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.ActivityNotFoundException;
import android.media.AudioManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.telephony.PhoneNumberUtils;
import android.util.Log;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import android.os.SystemProperties;


final class HeadsetStateMachine extends StateMachine {
    private static final String TAG = "HeadsetStateMachine";
    private static final boolean DBG = false;
    //For Debugging only
    private static int sRefCount=0;

    private static final String HEADSET_NAME = "bt_headset_name";
    private static final String HEADSET_NREC = "bt_headset_nrec";
    private static final String HEADSET_SAMPLERATE = "bt_samplerate";

    private static final int VERSION_1_5 = 105;
    private static final int VERSION_1_6 = 106;
    private static final String PROP_VERSION_KEY = "ro.bluetooth.hfp.ver";
    private static final String PROP_VERSION_1_6 = "1.6";

    private static final int mVersion;

    static final int CONNECT = 1;
    static final int DISCONNECT = 2;
    static final int CONNECT_AUDIO = 3;
    static final int DISCONNECT_AUDIO = 4;
    static final int VOICE_RECOGNITION_START = 5;
    static final int VOICE_RECOGNITION_STOP = 6;

    // message.obj is an intent AudioManager.VOLUME_CHANGED_ACTION
    // EXTRA_VOLUME_STREAM_TYPE is STREAM_BLUETOOTH_SCO
    static final int INTENT_SCO_VOLUME_CHANGED = 7;
    static final int SET_MIC_VOLUME = 8;
    static final int CALL_STATE_CHANGED = 9;
    static final int INTENT_BATTERY_CHANGED = 10;
    static final int DEVICE_STATE_CHANGED = 11;
    static final int SEND_CCLC_RESPONSE = 12;
    static final int SEND_VENDOR_SPECIFIC_RESULT_CODE = 13;

    static final int VIRTUAL_CALL_START = 14;
    static final int VIRTUAL_CALL_STOP = 15;
    static final int UPDATE_A2DP_PLAY_STATE = 16;
    static final int UPDATE_A2DP_CONN_STATE = 17;

    private static final int STACK_EVENT = 101;
    private static final int DIALING_OUT_TIMEOUT = 102;
    private static final int START_VR_TIMEOUT = 103;

    private static final int CONNECT_TIMEOUT = 201;

    private static final int DIALING_OUT_TIMEOUT_VALUE = 10000;
    private static final int START_VR_TIMEOUT_VALUE = 5000;

    // Keys are AT commands, and values are the company IDs.
    private static final Map<String, Integer> VENDOR_SPECIFIC_AT_COMMAND_COMPANY_ID;

    /* Constants from Bluetooth Specification Hands-Free profile version 1.6 */
    private static final int BRSF_AG_THREE_WAY_CALLING = 1 << 0;
    private static final int BRSF_AG_EC_NR = 1 << 1;
    private static final int BRSF_AG_VOICE_RECOG = 1 << 2;
    private static final int BRSF_AG_IN_BAND_RING = 1 << 3;
    private static final int BRSF_AG_VOICE_TAG_NUMBE = 1 << 4;
    private static final int BRSF_AG_REJECT_CALL = 1 << 5;
    private static final int BRSF_AG_ENHANCED_CALL_STATUS = 1 <<  6;
    private static final int BRSF_AG_ENHANCED_CALL_CONTROL = 1 << 7;
    private static final int BRSF_AG_ENHANCED_ERR_RESULT_CODES = 1 << 8;
    private static final int BRSF_AG_CODEC_NEGOTIATION = 1 << 9;

    private static final int BRSF_HF_EC_NR = 1 << 0;
    private static final int BRSF_HF_CW_THREE_WAY_CALLING = 1 << 1;
    private static final int BRSF_HF_CLIP = 1 << 2;
    private static final int BRSF_HF_VOICE_REG_ACT = 1 << 3;
    private static final int BRSF_HF_REMOTE_VOL_CONTROL = 1 << 4;
    private static final int BRSF_HF_ENHANCED_CALL_STATUS = 1 <<  5;
    private static final int BRSF_HF_ENHANCED_CALL_CONTROL = 1 << 6;
    private static final int BRSF_HF_CODEC_NEGOTIATION = 1 << 7;

    private static final int CODEC_NONE = 0;
    private static final int CODEC_CVSD = 1;
    private static final int CODEC_MSBC = 2;

    private int mLocalBrsf = 0;
    private int mCodec = CODEC_NONE;

    private static final ParcelUuid[] HEADSET_UUIDS = {
        BluetoothUuid.HSP,
        BluetoothUuid.Handsfree,
    };

    private Disconnected mDisconnected;
    private Pending mPending;
    private Connected mConnected;
    private AudioOn mAudioOn;

    private HeadsetService mService;
    private PowerManager mPowerManager;
    private boolean mVirtualCallStarted = false;
    private boolean mVoiceRecognitionStarted = false;
    private boolean mWaitingForVoiceRecognition = false;
    private WakeLock mStartVoiceRecognitionWakeLock;  // held while waiting for voice recognition

    private boolean mDialingOut = false;
    private AudioManager mAudioManager;
    private AtPhonebook mPhonebook;

    private static Intent sVoiceCommandIntent;

    private HeadsetPhoneState mPhoneState;
    private int mAudioState;
    private BluetoothAdapter mAdapter;
    private IBluetoothHeadsetPhone mPhoneProxy;
    private boolean mNativeAvailable;

    private boolean mA2dpSuspend;
    private int mA2dpPlayState;
    private int mA2dpState;
    private boolean mPendingCiev;

    // mCurrentDevice is the device connected before the state changes
    // mTargetDevice is the device to be connected
    // mIncomingDevice is the device connecting to us, valid only in Pending state
    //                when mIncomingDevice is not null, both mCurrentDevice
    //                  and mTargetDevice are null
    //                when either mCurrentDevice or mTargetDevice is not null,
    //                  mIncomingDevice is null
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
    private BluetoothDevice mCurrentDevice = null;
    private BluetoothDevice mTargetDevice = null;
    private BluetoothDevice mIncomingDevice = null;

    static {
        classInitNative();

        VENDOR_SPECIFIC_AT_COMMAND_COMPANY_ID = new HashMap<String, Integer>();
        VENDOR_SPECIFIC_AT_COMMAND_COMPANY_ID.put("+XEVENT", BluetoothAssignedNumbers.PLANTRONICS);
        VENDOR_SPECIFIC_AT_COMMAND_COMPANY_ID.put("+ANDROID", BluetoothAssignedNumbers.GOOGLE);
    }

    static {
        if (PROP_VERSION_1_6.equals(SystemProperties.get(PROP_VERSION_KEY))) {
            mVersion = VERSION_1_6;
            Log.d(TAG, "Version 1.6");
        } else {
            mVersion = VERSION_1_5;
            Log.d(TAG, "Version 1.5");
        }
    }

    private HeadsetStateMachine(HeadsetService context) {
        super(TAG);
        mService = context;
        mVoiceRecognitionStarted = false;
        mWaitingForVoiceRecognition = false;

        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mStartVoiceRecognitionWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                                       TAG + ":VoiceRecognition");
        mStartVoiceRecognitionWakeLock.setReferenceCounted(false);

        mDialingOut = false;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mPhonebook = new AtPhonebook(mService, this);
        mPhoneState = new HeadsetPhoneState(context, this);
        mAudioState = BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        Intent intent = new Intent(IBluetoothHeadsetPhone.class.getName());
        intent.setComponent(intent.resolveSystemService(context.getPackageManager(), 0));
        if (intent.getComponent() == null || !context.bindService(intent, mConnection, 0)) {
            Log.e(TAG, "Could not bind to Bluetooth Headset Phone Service");
        }
        initializeNative();
        mNativeAvailable=true;

        mLocalBrsf = BRSF_AG_THREE_WAY_CALLING |
                     BRSF_AG_EC_NR |
                     BRSF_AG_REJECT_CALL |
                     BRSF_AG_ENHANCED_CALL_STATUS;

        mDisconnected = new Disconnected();
        mPending = new Pending();
        mConnected = new Connected();
        mAudioOn = new AudioOn();

        if (sVoiceCommandIntent == null) {
            sVoiceCommandIntent = new Intent(Intent.ACTION_VOICE_COMMAND);
            sVoiceCommandIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if (context.getPackageManager().resolveActivity(sVoiceCommandIntent,0) != null
            && BluetoothHeadset.isBluetoothVoiceDialingEnabled(context)) {
            mLocalBrsf |= BRSF_AG_VOICE_RECOG;
        }

        if (mVersion == VERSION_1_6) {
            if (DBG) Log.d(TAG, "BRSF_AG_CODEC_NEGOTIATION is enabled!");
            mLocalBrsf |= BRSF_AG_CODEC_NEGOTIATION;
        } else {
            if (DBG) Log.d(TAG, "BRSF_AG_CODEC_NEGOTIATION is disabled");
        }
        initializeFeaturesNative(mLocalBrsf);
        addState(mDisconnected);
        addState(mPending);
        addState(mConnected);
        addState(mAudioOn);

        setInitialState(mDisconnected);
        mPhoneState.listenForPhoneState(true);
    }

    static HeadsetStateMachine make(HeadsetService context) {
        Log.d(TAG, "make");
        HeadsetStateMachine hssm = new HeadsetStateMachine(context);
        hssm.start();
        return hssm;
    }


    public void doQuit() {
        quitNow();
    }

    public void cleanup() {
        if (mPhoneProxy != null) {
            if (DBG) Log.d(TAG,"Unbinding service...");
            synchronized (mConnection) {
                try {
                    mPhoneProxy = null;
                    mService.unbindService(mConnection);
                } catch (Exception re) {
                    Log.e(TAG,"Error unbinding from IBluetoothHeadsetPhone",re);
                }
            }
        }
        if (mPhoneState != null) {
            mPhoneState.listenForPhoneState(false);
            mPhoneState.cleanup();
        }
        if (mPhonebook != null) {
            mPhonebook.cleanup();
        }
        if (mNativeAvailable) {
            cleanupNative();
            mNativeAvailable = false;
        }
    }

    private class Disconnected extends State {
        @Override
        public void enter() {
            log("Enter Disconnected: " + getCurrentMessage().what);
            mPhonebook.resetAtState();
            mPhoneState.listenForPhoneState(false);
        }

        @Override
        public boolean processMessage(Message message) {
            log("Disconnected process message: " + message.what);
            if (mCurrentDevice != null || mTargetDevice != null || mIncomingDevice != null) {
                Log.e(TAG, "ERROR: current, target, or mIncomingDevice not null in Disconnected");
                return NOT_HANDLED;
            }

            boolean retValue = HANDLED;
            switch(message.what) {
                case CONNECT:
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                   BluetoothProfile.STATE_DISCONNECTED);

                    if (!connectHfpNative(getByteAddress(device)) ) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                       BluetoothProfile.STATE_CONNECTING);
                        break;
                    }
                    if (mPhoneProxy != null) {
                        try {
                            log("Query the phonestates");
                            mPhoneProxy.queryPhoneState();
                        } catch (RemoteException e) {
                            Log.e(TAG, Log.getStackTraceString(new Throwable()));
                        }
                    } else Log.e(TAG, "Phone proxy null for query phone state");
                    synchronized (HeadsetStateMachine.this) {
                        mTargetDevice = device;
                        transitionTo(mPending);
                    }
                    // TODO(BT) remove CONNECT_TIMEOUT when the stack
                    //          sends back events consistently
                    sendMessageDelayed(CONNECT_TIMEOUT, 30000);
                    break;
                case DISCONNECT:
                    // ignore
                    break;
                case INTENT_BATTERY_CHANGED:
                    processIntentBatteryChanged((Intent) message.obj);
                    break;
                case CALL_STATE_CHANGED:
                    processCallState((HeadsetCallState) message.obj,
                        ((message.arg1 == 1)?true:false));
                    break;
                case UPDATE_A2DP_PLAY_STATE:
                    processIntentA2dpPlayStateChanged((Intent) message.obj);
                    break;
                case UPDATE_A2DP_CONN_STATE:
                    processIntentA2dpStateChanged((Intent) message.obj);
                    break;
                case STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    if (DBG) {
                        log("event type: " + event.type);
                    }
                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        default:
                            Log.e(TAG, "Unexpected stack event: " + event.type);
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
            switch (state) {
            case HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED:
                Log.w(TAG, "Ignore HF DISCONNECTED event, device: " + device);
                break;
            case HeadsetHalConstants.CONNECTION_STATE_CONNECTING:
                if (okToConnect(device)){
                    Log.i(TAG,"Incoming Hf accepted");
                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                             BluetoothProfile.STATE_DISCONNECTED);
                    synchronized (HeadsetStateMachine.this) {
                        mIncomingDevice = device;
                        transitionTo(mPending);
                    }
                } else {
                    Log.i(TAG,"Incoming Hf rejected. priority=" + mService.getPriority(device)+
                              " bondState=" + device.getBondState());
                    //reject the connection and stay in Disconnected state itself
                    disconnectHfpNative(getByteAddress(device));
                    // the other profile connection should be initiated
                    AdapterService adapterService = AdapterService.getAdapterService();
                    if ( adapterService != null) {
                        adapterService.connectOtherProfile(device,
                                                           AdapterService.PROFILE_CONN_REJECTED);
                    }
                }
                break;
            case HeadsetHalConstants.CONNECTION_STATE_CONNECTED:
                Log.w(TAG, "HFP Connected from Disconnected state");
                if (okToConnect(device)){
                    Log.i(TAG,"Incoming Hf accepted");
                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                             BluetoothProfile.STATE_DISCONNECTED);
                    synchronized (HeadsetStateMachine.this) {
                        mCurrentDevice = device;
                        transitionTo(mConnected);
                    }
                    configAudioParameters();
                } else {
                    //reject the connection and stay in Disconnected state itself
                    Log.i(TAG,"Incoming Hf rejected. priority=" + mService.getPriority(device) +
                              " bondState=" + device.getBondState());
                    disconnectHfpNative(getByteAddress(device));
                    // the other profile connection should be initiated
                    AdapterService adapterService = AdapterService.getAdapterService();
                    if ( adapterService != null) {
                        adapterService.connectOtherProfile(device,
                                                           AdapterService.PROFILE_CONN_REJECTED);
                    }
                }
                break;
            case HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING:
                Log.w(TAG, "Ignore HF DISCONNECTING event, device: " + device);
                break;
            default:
                Log.e(TAG, "Incorrect state: " + state);
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
            log("Pending process message: " + message.what);

            boolean retValue = HANDLED;
            switch(message.what) {
                case CONNECT:
                case CONNECT_AUDIO:
                    deferMessage(message);
                    break;
                case CONNECT_TIMEOUT:
                    onConnectionStateChanged(HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED,
                                             getByteAddress(mTargetDevice));
                    break;
                case DISCONNECT:
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (mCurrentDevice != null && mTargetDevice != null &&
                        mTargetDevice.equals(device) ) {
                        // cancel connection to the mTargetDevice
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                       BluetoothProfile.STATE_CONNECTING);
                        synchronized (HeadsetStateMachine.this) {
                            mTargetDevice = null;
                        }
                    } else {
                        deferMessage(message);
                    }
                    break;
                case INTENT_BATTERY_CHANGED:
                    processIntentBatteryChanged((Intent) message.obj);
                    break;
                case CALL_STATE_CHANGED:
                    processCallState((HeadsetCallState) message.obj,
                        ((message.arg1 == 1)?true:false));
                    break;
                case STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    if (DBG) {
                        log("event type: " + event.type);
                    }
                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            removeMessages(CONNECT_TIMEOUT);
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        default:
                            Log.e(TAG, "Unexpected event: " + event.type);
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
            switch (state) {
                case HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED:
                    if ((mCurrentDevice != null) && mCurrentDevice.equals(device)) {
                        broadcastConnectionState(mCurrentDevice,
                                                 BluetoothProfile.STATE_DISCONNECTED,
                                                 BluetoothProfile.STATE_DISCONNECTING);
                        synchronized (HeadsetStateMachine.this) {
                            mCurrentDevice = null;
                        }

                        if (mTargetDevice != null) {
                            if (!connectHfpNative(getByteAddress(mTargetDevice))) {
                                broadcastConnectionState(mTargetDevice,
                                                         BluetoothProfile.STATE_DISCONNECTED,
                                                         BluetoothProfile.STATE_CONNECTING);
                                synchronized (HeadsetStateMachine.this) {
                                    mTargetDevice = null;
                                    transitionTo(mDisconnected);
                                }
                            }
                        } else {
                            synchronized (HeadsetStateMachine.this) {
                                mIncomingDevice = null;
                                transitionTo(mDisconnected);
                            }
                        }
                    } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                        // outgoing connection failed
                        broadcastConnectionState(mTargetDevice, BluetoothProfile.STATE_DISCONNECTED,
                                                 BluetoothProfile.STATE_CONNECTING);
                        synchronized (HeadsetStateMachine.this) {
                            mTargetDevice = null;
                            transitionTo(mDisconnected);
                        }
                    } else if (mIncomingDevice != null && mIncomingDevice.equals(device)) {
                        broadcastConnectionState(mIncomingDevice,
                                                 BluetoothProfile.STATE_DISCONNECTED,
                                                 BluetoothProfile.STATE_CONNECTING);
                        synchronized (HeadsetStateMachine.this) {
                            mIncomingDevice = null;
                            transitionTo(mDisconnected);
                        }
                    } else {
                        Log.e(TAG, "Unknown device Disconnected: " + device);
                    }
                    break;
            case HeadsetHalConstants.CONNECTION_STATE_CONNECTED:
                if ((mCurrentDevice != null) && mCurrentDevice.equals(device)) {
                    // disconnection failed
                    broadcastConnectionState(mCurrentDevice, BluetoothProfile.STATE_CONNECTED,
                                             BluetoothProfile.STATE_DISCONNECTING);
                    if (mTargetDevice != null) {
                        broadcastConnectionState(mTargetDevice, BluetoothProfile.STATE_DISCONNECTED,
                                                 BluetoothProfile.STATE_CONNECTING);
                    }
                    synchronized (HeadsetStateMachine.this) {
                        mTargetDevice = null;
                        transitionTo(mConnected);
                    }
                } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                    broadcastConnectionState(mTargetDevice, BluetoothProfile.STATE_CONNECTED,
                                             BluetoothProfile.STATE_CONNECTING);
                    synchronized (HeadsetStateMachine.this) {
                        mCurrentDevice = mTargetDevice;
                        mTargetDevice = null;
                        transitionTo(mConnected);
                    }
                } else if (mIncomingDevice != null && mIncomingDevice.equals(device)) {
                    broadcastConnectionState(mIncomingDevice, BluetoothProfile.STATE_CONNECTED,
                                             BluetoothProfile.STATE_CONNECTING);
                    synchronized (HeadsetStateMachine.this) {
                        mCurrentDevice = mIncomingDevice;
                        mIncomingDevice = null;
                        transitionTo(mConnected);
                    }
                } else {
                    Log.e(TAG, "Unknown device Connected: " + device);
                    // something is wrong here, but sync our state with stack
                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                             BluetoothProfile.STATE_DISCONNECTED);
                    synchronized (HeadsetStateMachine.this) {
                        mCurrentDevice = device;
                        mTargetDevice = null;
                        mIncomingDevice = null;
                        transitionTo(mConnected);
                    }
                }
                configAudioParameters();
                break;
            case HeadsetHalConstants.CONNECTION_STATE_CONNECTING:
                if ((mCurrentDevice != null) && mCurrentDevice.equals(device)) {
                    log("current device tries to connect back");
                    // TODO(BT) ignore or reject
                } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                    // The stack is connecting to target device or
                    // there is an incoming connection from the target device at the same time
                    // we already broadcasted the intent, doing nothing here
                    if (DBG) {
                        log("Stack and target device are connecting");
                    }
                }
                else if (mIncomingDevice != null && mIncomingDevice.equals(device)) {
                    Log.e(TAG, "Another connecting event on the incoming device");
                } else {
                    // We get an incoming connecting request while Pending
                    // TODO(BT) is stack handing this case? let's ignore it for now
                    log("Incoming connection while pending, ignore");
                }
                break;
            case HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING:
                if ((mCurrentDevice != null) && mCurrentDevice.equals(device)) {
                    // we already broadcasted the intent, doing nothing here
                    if (DBG) {
                        log("stack is disconnecting mCurrentDevice");
                    }
                } else if (mTargetDevice != null && mTargetDevice.equals(device)) {
                    Log.e(TAG, "TargetDevice is getting disconnected");
                } else if (mIncomingDevice != null && mIncomingDevice.equals(device)) {
                    Log.e(TAG, "IncomingDevice is getting disconnected");
                } else {
                    Log.e(TAG, "Disconnecting unknow device: " + device);
                }
                break;
            default:
                Log.e(TAG, "Incorrect state: " + state);
                break;
            }
        }

    }

    private class Connected extends State {
        @Override
        public void enter() {
            log("Enter Connected: " + getCurrentMessage().what);
        }

        @Override
        public boolean processMessage(Message message) {
            log("Connected process message: " + message.what);
            if (DBG) {
                if (mCurrentDevice == null) {
                    log("ERROR: mCurrentDevice is null in Connected");
                    return NOT_HANDLED;
                }
            }

            boolean retValue = HANDLED;
            switch(message.what) {
                case CONNECT:
                {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (mCurrentDevice.equals(device)) {
                        break;
                    }

                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                   BluetoothProfile.STATE_DISCONNECTED);
                    if (!disconnectHfpNative(getByteAddress(mCurrentDevice))) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                       BluetoothProfile.STATE_CONNECTING);
                        break;
                    } else {
                            broadcastConnectionState(mCurrentDevice, BluetoothProfile.STATE_DISCONNECTING,
                                       BluetoothProfile.STATE_CONNECTED);
                    }

                    synchronized (HeadsetStateMachine.this) {
                        mTargetDevice = device;
                        transitionTo(mPending);
                    }
                }
                    break;
                case DISCONNECT:
                {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mCurrentDevice.equals(device)) {
                        break;
                    }
                    broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTING,
                                   BluetoothProfile.STATE_CONNECTED);
                    if (!disconnectHfpNative(getByteAddress(device))) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                       BluetoothProfile.STATE_DISCONNECTING);
                        break;
                    }
                    transitionTo(mPending);
                }
                    break;
                case CONNECT_AUDIO:
                    // TODO(BT) when failure, broadcast audio connecting to disconnected intent
                    //          check if device matches mCurrentDevice
                    connectAudioNative(getByteAddress(mCurrentDevice));
                    break;
                case VOICE_RECOGNITION_START:
                    processLocalVrEvent(HeadsetHalConstants.VR_STATE_STARTED);
                    break;
                case VOICE_RECOGNITION_STOP:
                    processLocalVrEvent(HeadsetHalConstants.VR_STATE_STOPPED);
                    break;
                case CALL_STATE_CHANGED:
                    processCallState((HeadsetCallState) message.obj, ((message.arg1==1)?true:false));
                    break;
                case INTENT_BATTERY_CHANGED:
                    processIntentBatteryChanged((Intent) message.obj);
                    break;
                case DEVICE_STATE_CHANGED:
                    processDeviceStateChanged((HeadsetDeviceState) message.obj);
                    break;
                case SEND_CCLC_RESPONSE:
                    processSendClccResponse((HeadsetClccResponse) message.obj);
                    break;
                case SEND_VENDOR_SPECIFIC_RESULT_CODE:
                    processSendVendorSpecificResultCode(
                            (HeadsetVendorSpecificResultCode) message.obj);
                    break;
                case DIALING_OUT_TIMEOUT:
                    if (mDialingOut) {
                        mDialingOut= false;
                        atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
                    }
                    break;
                case VIRTUAL_CALL_START:
                    initiateScoUsingVirtualVoiceCall();
                    break;
                case VIRTUAL_CALL_STOP:
                    terminateScoUsingVirtualVoiceCall();
                    break;
                case UPDATE_A2DP_PLAY_STATE:
                    processIntentA2dpPlayStateChanged((Intent) message.obj);
                    break;
                case UPDATE_A2DP_CONN_STATE:
                    processIntentA2dpStateChanged((Intent) message.obj);
                    break;
                case START_VR_TIMEOUT:
                    if (mWaitingForVoiceRecognition) {
                        mWaitingForVoiceRecognition = false;
                        Log.e(TAG, "Timeout waiting for voice recognition to start");
                        atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
                    }
                    break;
                case STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    if (DBG) {
                        log("event type: " + event.type);
                    }
                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        case EVENT_TYPE_AUDIO_STATE_CHANGED:
                            processAudioEvent(event.valueInt, event.device);
                            break;
                        case EVENT_TYPE_VR_STATE_CHANGED:
                            processVrEvent(event.valueInt);
                            break;
                        case EVENT_TYPE_ANSWER_CALL:
                            // TODO(BT) could answer call happen on Connected state?
                            processAnswerCall();
                            break;
                        case EVENT_TYPE_HANGUP_CALL:
                            // TODO(BT) could hangup call happen on Connected state?
                            processHangupCall();
                            break;
                        case EVENT_TYPE_VOLUME_CHANGED:
                            processVolumeEvent(event.valueInt, event.valueInt2);
                            break;
                        case EVENT_TYPE_DIAL_CALL:
                            processDialCall(event.valueString);
                            break;
                        case EVENT_TYPE_SEND_DTMF:
                            processSendDtmf(event.valueInt);
                            break;
                        case EVENT_TYPE_NOICE_REDUCTION:
                            processNoiceReductionEvent(event.valueInt);
                            break;
                        case EVENT_TYPE_AT_CHLD:
                            processAtChld(event.valueInt);
                            break;
                        case EVENT_TYPE_SUBSCRIBER_NUMBER_REQUEST:
                            processSubscriberNumberRequest();
                            break;
                        case EVENT_TYPE_AT_CIND:
                            processAtCind();
                            break;
                        case EVENT_TYPE_AT_COPS:
                            processAtCops();
                            break;
                        case EVENT_TYPE_AT_CLCC:
                            processAtClcc();
                            break;
                        case EVENT_TYPE_UNKNOWN_AT:
                            processUnknownAt(event.valueString);
                            break;
                        case EVENT_TYPE_KEY_PRESSED:
                            processKeyPressed();
                            break;
                        default:
                            Log.e(TAG, "Unknown stack event: " + event.type);
                            break;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

        // in Connected state
        private void processConnectionEvent(int state, BluetoothDevice device) {
            switch (state) {
                case HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED:
                    if (mCurrentDevice.equals(device)) {
                        broadcastConnectionState(mCurrentDevice, BluetoothProfile.STATE_DISCONNECTED,
                                                 BluetoothProfile.STATE_CONNECTED);
                        synchronized (HeadsetStateMachine.this) {
                            mCurrentDevice = null;
                            transitionTo(mDisconnected);
                        }
                    } else {
                        Log.e(TAG, "Disconnected from unknown device: " + device);
                    }
                    break;
                case HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED:
                    processSlcConnected();
                    break;
              default:
                  Log.e(TAG, "Connection State Device: " + device + " bad state: " + state);
                  break;
            }
        }

        // in Connected state
        private void processAudioEvent(int state, BluetoothDevice device) {
            if (!mCurrentDevice.equals(device)) {
                Log.e(TAG, "Audio changed on disconnected device: " + device);
                return;
            }

            switch (state) {
                case HeadsetHalConstants.AUDIO_STATE_CONNECTED:
                    // TODO(BT) should I save the state for next broadcast as the prevState?
                    mAudioState = BluetoothHeadset.STATE_AUDIO_CONNECTED;
                    setAudioSamplerate(); /*Set proper sample rate.*/
                    mAudioManager.setBluetoothScoOn(true);
                    broadcastAudioState(device, BluetoothHeadset.STATE_AUDIO_CONNECTED,
                                        BluetoothHeadset.STATE_AUDIO_CONNECTING);
                    transitionTo(mAudioOn);
                    break;
                case HeadsetHalConstants.AUDIO_STATE_CONNECTING:
                    mAudioState = BluetoothHeadset.STATE_AUDIO_CONNECTING;
                    broadcastAudioState(device, BluetoothHeadset.STATE_AUDIO_CONNECTING,
                                        BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
                    break;
                    // TODO(BT) process other states
                default:
                    Log.e(TAG, "Audio State Device: " + device + " bad state: " + state);
                    break;
            }
        }

        private void processSlcConnected() {
            if (mPhoneProxy != null) {
                try {
                    // start phone state listener here, instead of on disconnected exit()
                    // On BT off, exitting SM sends a SM exit() call which incorrectly forces
                    // a listenForPhoneState(true).
                    // Additionally, no indicator updates should be sent prior to SLC setup
                    mPhoneState.listenForPhoneState(true);
                    mPhoneProxy.queryPhoneState();
                    mCodec = CODEC_NONE;
                    mA2dpSuspend = false;/*Reset at SLC*/
                    mPendingCiev = false;
                    if ((isInCall()) && (mA2dpState == BluetoothProfile.STATE_CONNECTED)) {
                        if (DBG) {
                             log("Headset connected while we are in some call state");
                             log("Make A2dpSuspended=true here");
                         }
                        mAudioManager.setParameters("A2dpSuspended=true");
                        mA2dpSuspend = true;
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, Log.getStackTraceString(new Throwable()));
                }
            } else {
                Log.e(TAG, "Handsfree phone proxy null for query phone state");
            }

        }
    }

    private class AudioOn extends State {

        @Override
        public void enter() {
            log("Enter AudioOn: " + getCurrentMessage().what);
        }

        @Override
        public boolean processMessage(Message message) {
            log("AudioOn process message: " + message.what);
            if (DBG) {
                if (mCurrentDevice == null) {
                    log("ERROR: mCurrentDevice is null in AudioOn");
                    return NOT_HANDLED;
                }
            }

            boolean retValue = HANDLED;
            switch(message.what) {
                case CONNECT:
                {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (mCurrentDevice.equals(device)) {
                        break;
                    }
                    deferMessage(obtainMessage(DISCONNECT, mCurrentDevice));
                    deferMessage(obtainMessage(CONNECT, message.obj));
                    if (disconnectAudioNative(getByteAddress(mCurrentDevice))) {
                        log("Disconnecting SCO audio");
                    } else {
                        Log.e(TAG, "disconnectAudioNative failed");
                    }
                }
                break;
                case DISCONNECT:
                {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mCurrentDevice.equals(device)) {
                        break;
                    }
                    deferMessage(obtainMessage(DISCONNECT, message.obj));
                }
                // fall through
                case DISCONNECT_AUDIO:
                    if (disconnectAudioNative(getByteAddress(mCurrentDevice))) {
                        log("Disconnecting SCO audio");
                    } else {
                        Log.e(TAG, "disconnectAudioNative failed");
                    }
                    break;
                case VOICE_RECOGNITION_START:
                    processLocalVrEvent(HeadsetHalConstants.VR_STATE_STARTED);
                    break;
                case VOICE_RECOGNITION_STOP:
                    processLocalVrEvent(HeadsetHalConstants.VR_STATE_STOPPED);
                    break;
                case INTENT_SCO_VOLUME_CHANGED:
                    processIntentScoVolume((Intent) message.obj);
                    break;
                case CALL_STATE_CHANGED:
                    processCallState((HeadsetCallState) message.obj, ((message.arg1 == 1)?true:false));
                    break;
                case INTENT_BATTERY_CHANGED:
                    processIntentBatteryChanged((Intent) message.obj);
                    break;
                case DEVICE_STATE_CHANGED:
                    processDeviceStateChanged((HeadsetDeviceState) message.obj);
                    break;
                case SEND_CCLC_RESPONSE:
                    processSendClccResponse((HeadsetClccResponse) message.obj);
                    break;
                case SEND_VENDOR_SPECIFIC_RESULT_CODE:
                    processSendVendorSpecificResultCode(
                            (HeadsetVendorSpecificResultCode) message.obj);
                    break;

                case VIRTUAL_CALL_START:
                    initiateScoUsingVirtualVoiceCall();
                    break;
                case VIRTUAL_CALL_STOP:
                    terminateScoUsingVirtualVoiceCall();
                    break;
                case UPDATE_A2DP_PLAY_STATE:
                    processIntentA2dpPlayStateChanged((Intent) message.obj);
                    break;
                case UPDATE_A2DP_CONN_STATE:
                    processIntentA2dpStateChanged((Intent) message.obj);
                    break;
                case DIALING_OUT_TIMEOUT:
                    if (mDialingOut) {
                        mDialingOut= false;
                        atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
                    }
                    break;
                case START_VR_TIMEOUT:
                    if (mWaitingForVoiceRecognition) {
                        mWaitingForVoiceRecognition = false;
                        Log.e(TAG, "Timeout waiting for voice recognition to start");
                        atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
                    }
                    break;
                case STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    if (DBG) {
                        log("event type: " + event.type);
                    }
                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        case EVENT_TYPE_AUDIO_STATE_CHANGED:
                            processAudioEvent(event.valueInt, event.device);
                            break;
                        case EVENT_TYPE_VR_STATE_CHANGED:
                            processVrEvent(event.valueInt);
                            break;
                        case EVENT_TYPE_ANSWER_CALL:
                            processAnswerCall();
                            break;
                        case EVENT_TYPE_HANGUP_CALL:
                            processHangupCall();
                            break;
                        case EVENT_TYPE_VOLUME_CHANGED:
                            processVolumeEvent(event.valueInt, event.valueInt2);
                            break;
                        case EVENT_TYPE_DIAL_CALL:
                            processDialCall(event.valueString);
                            break;
                        case EVENT_TYPE_SEND_DTMF:
                            processSendDtmf(event.valueInt);
                            break;
                        case EVENT_TYPE_NOICE_REDUCTION:
                            processNoiceReductionEvent(event.valueInt);
                            break;
                        case EVENT_TYPE_AT_CHLD:
                            processAtChld(event.valueInt);
                            break;
                        case EVENT_TYPE_SUBSCRIBER_NUMBER_REQUEST:
                            processSubscriberNumberRequest();
                            break;
                        case EVENT_TYPE_AT_CIND:
                            processAtCind();
                            break;
                        case EVENT_TYPE_AT_COPS:
                            processAtCops();
                            break;
                        case EVENT_TYPE_AT_CLCC:
                            processAtClcc();
                            break;
                        case EVENT_TYPE_UNKNOWN_AT:
                            processUnknownAt(event.valueString);
                            break;
                        case EVENT_TYPE_KEY_PRESSED:
                            processKeyPressed();
                            break;
                        default:
                            Log.e(TAG, "Unknown stack event: " + event.type);
                            break;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

        // in AudioOn state. Some headsets disconnect RFCOMM prior to SCO down. Handle this
        private void processConnectionEvent(int state, BluetoothDevice device) {
            switch (state) {
                case HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED:
                    if (mCurrentDevice.equals(device)) {
                        processAudioEvent (HeadsetHalConstants.AUDIO_STATE_DISCONNECTED, device);
                        broadcastConnectionState(mCurrentDevice, BluetoothProfile.STATE_DISCONNECTED,
                                                 BluetoothProfile.STATE_CONNECTED);
                        synchronized (HeadsetStateMachine.this) {
                            mCurrentDevice = null;
                            transitionTo(mDisconnected);
                        }
                    } else {
                        Log.e(TAG, "Disconnected from unknown device: " + device);
                    }
                    break;
              default:
                  Log.e(TAG, "Connection State Device: " + device + " bad state: " + state);
                  break;
            }
        }

        // in AudioOn state
        private void processAudioEvent(int state, BluetoothDevice device) {
            if (!mCurrentDevice.equals(device)) {
                Log.e(TAG, "Audio changed on disconnected device: " + device);
                return;
            }

            switch (state) {
                case HeadsetHalConstants.AUDIO_STATE_DISCONNECTED:
                    if (mAudioState != BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                        mAudioState = BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
                    if (mAudioManager.isSpeakerphoneOn()) {
                        // User option might be speaker as sco disconnection
                        // is delayed setting back the speaker option.
                        mAudioManager.setBluetoothScoOn(false);
                        mAudioManager.setSpeakerphoneOn(true);
                    } else {
                        mAudioManager.setBluetoothScoOn(false);
                    }
                        if (mA2dpSuspend) {
                            if ((!isInCall()) && (mPhoneState.getNumber().isEmpty())) {
                                log("Audio is closed,Set A2dpSuspended=false");
                                mAudioManager.setParameters("A2dpSuspended=false");
                                mA2dpSuspend = false;
                            }
                        }
                        broadcastAudioState(device, BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                                            BluetoothHeadset.STATE_AUDIO_CONNECTED);
                    }
                    transitionTo(mConnected);
                    break;
                case HeadsetHalConstants.AUDIO_STATE_DISCONNECTING:
                    // TODO(BT) adding STATE_AUDIO_DISCONNECTING in BluetoothHeadset?
                    //broadcastAudioState(device, BluetoothHeadset.STATE_AUDIO_DISCONNECTING,
                    //                    BluetoothHeadset.STATE_AUDIO_CONNECTED);
                    break;
                default:
                    Log.e(TAG, "Audio State Device: " + device + " bad state: " + state);
                    break;
            }
        }

        private void processIntentScoVolume(Intent intent) {
            int volumeValue = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 0);
            if (mPhoneState.getSpeakerVolume() != volumeValue) {
                mPhoneState.setSpeakerVolume(volumeValue);
                setVolumeNative(HeadsetHalConstants.VOLUME_TYPE_SPK, volumeValue);
            }
        }
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            if (DBG) Log.d(TAG, "Proxy object connected");
            mPhoneProxy = IBluetoothHeadsetPhone.Stub.asInterface(service);
        }

        public void onServiceDisconnected(ComponentName className) {
            if (DBG) Log.d(TAG, "Proxy object disconnected");
            mPhoneProxy = null;
        }
    };

    // HFP Connection state of the device could be changed by the state machine
    // in separate thread while this method is executing.
    int getConnectionState(BluetoothDevice device) {
        if (getCurrentState() == mDisconnected) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }

        synchronized (this) {
            IState currentState = getCurrentState();
            if (currentState == mPending) {
                if ((mTargetDevice != null) && mTargetDevice.equals(device)) {
                    return BluetoothProfile.STATE_CONNECTING;
                }
                if ((mCurrentDevice != null) && mCurrentDevice.equals(device)) {
                    return BluetoothProfile.STATE_DISCONNECTING;
                }
                if ((mIncomingDevice != null) && mIncomingDevice.equals(device)) {
                    return BluetoothProfile.STATE_CONNECTING; // incoming connection
                }
                return BluetoothProfile.STATE_DISCONNECTED;
            }

            if (currentState == mConnected || currentState == mAudioOn) {
                // Added safe check for mCurrentDevice as voice call use
                // cases can call this function with valid device due to
                // delay in state transition from connected to disconnected.
                // This may trigger null pointer exception here since
                // we set mCurrentDevice to null soon after disconnect,
                // but it can be calld before we move to disconnected state
                // in BT regression tests.
                if (mCurrentDevice != null)
                    if (mCurrentDevice.equals(device)) {
                        return BluetoothProfile.STATE_CONNECTED;
                    }
                return BluetoothProfile.STATE_DISCONNECTED;
            } else {
                Log.e(TAG, "Bad currentState: " + currentState);
                return BluetoothProfile.STATE_DISCONNECTED;
            }
        }
    }

    List<BluetoothDevice> getConnectedDevices() {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        synchronized(this) {
            if (isConnected() && (mCurrentDevice != null)) { /* Check for mCurrentDevice too*/
                devices.add(mCurrentDevice);
            }
        }
        return devices;
    }

    boolean isAudioOn() {
        return (getCurrentState() == mAudioOn);
    }

    boolean isAudioConnected(BluetoothDevice device) {
        synchronized(this) {

            /*  Additional check for audio state included for the case when PhoneApp queries
            Bluetooth Audio state, before we receive the close event from the stack for the
            sco disconnect issued in AudioOn state. This was causing a mismatch in the
            Incall screen UI. */

            if (getCurrentState() == mAudioOn && mCurrentDevice.equals(device)
                && mAudioState != BluetoothHeadset.STATE_AUDIO_DISCONNECTED)
            {
                return true;
            }
        }
        return false;
    }

    int getAudioState(BluetoothDevice device) {
        synchronized(this) {
            if (mCurrentDevice == null || !mCurrentDevice.equals(device)) {
                return BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
            }
        }
        return mAudioState;
    }

    private void processVrEvent(int state) {
        Log.d(TAG, "processVrEvent: state=" + state + " mVoiceRecognitionStarted: " +
            mVoiceRecognitionStarted + " mWaitingforVoiceRecognition: " + mWaitingForVoiceRecognition +
            " isInCall: " + isInCall());
        if (state == HeadsetHalConstants.VR_STATE_STARTED) {
            if (!isVirtualCallInProgress() &&
                !isInCall())
            {
                try {
                    mService.startActivity(sVoiceCommandIntent);
                } catch (ActivityNotFoundException e) {
                    atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
                    return;
                }
                expectVoiceRecognition();
            }
        } else if (state == HeadsetHalConstants.VR_STATE_STOPPED) {
            if (mVoiceRecognitionStarted || mWaitingForVoiceRecognition)
            {
                atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_OK, 0);
                mVoiceRecognitionStarted = false;
                mWaitingForVoiceRecognition = false;
                if (!isInCall()) {
                    disconnectAudioNative(getByteAddress(mCurrentDevice));
                    mAudioManager.setParameters("A2dpSuspended=false");
                }
            }
            else
            {
                atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
            }
        } else {
            Log.e(TAG, "Bad Voice Recognition state: " + state);
        }
    }

    private void processLocalVrEvent(int state)
    {
        if (state == HeadsetHalConstants.VR_STATE_STARTED)
        {
            boolean needAudio = true;
            if (mVoiceRecognitionStarted || isInCall())
            {
                Log.e(TAG, "Voice recognition started when call is active. isInCall:" + isInCall() + 
                    " mVoiceRecognitionStarted: " + mVoiceRecognitionStarted);
                return;
            }
            mVoiceRecognitionStarted = true;

            if (mWaitingForVoiceRecognition)
            {
                Log.d(TAG, "Voice recognition started successfully");
                mWaitingForVoiceRecognition = false;
                atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_OK, 0);
                removeMessages(START_VR_TIMEOUT);
            }
            else
            {
                Log.d(TAG, "Voice recognition started locally");
                needAudio = startVoiceRecognitionNative();
            }

            if (needAudio && !isAudioOn())
            {
                Log.d(TAG, "Initiating audio connection for Voice Recognition");
                // At this stage, we need to be sure that AVDTP is not streaming. This is needed
                // to be compliant with the AV+HFP Whitepaper as we cannot have A2DP in
                // streaming state while a SCO connection is established.
                // This is needed for VoiceDial scenario alone and not for
                // incoming call/outgoing call scenarios as the phone enters MODE_RINGTONE
                // or MODE_IN_CALL which shall automatically suspend the AVDTP stream if needed.
                // Whereas for VoiceDial we want to activate the SCO connection but we are still
                // in MODE_NORMAL and hence the need to explicitly suspend the A2DP stream
                mAudioManager.setParameters("A2dpSuspended=true");
                connectAudioNative(getByteAddress(mCurrentDevice));
            }

            if (mStartVoiceRecognitionWakeLock.isHeld()) {
                mStartVoiceRecognitionWakeLock.release();
            }
        }
        else
        {
            Log.d(TAG, "Voice Recognition stopped. mVoiceRecognitionStarted: " + mVoiceRecognitionStarted +
                " mWaitingForVoiceRecognition: " + mWaitingForVoiceRecognition);
            if (mVoiceRecognitionStarted || mWaitingForVoiceRecognition)
            {
                mVoiceRecognitionStarted = false;
                mWaitingForVoiceRecognition = false;

                if (stopVoiceRecognitionNative() && !isInCall()) {
                    disconnectAudioNative(getByteAddress(mCurrentDevice));
                    mAudioManager.setParameters("A2dpSuspended=false");
                }
            }
        }
    }

    private synchronized void expectVoiceRecognition() {
        mWaitingForVoiceRecognition = true;
        sendMessageDelayed(START_VR_TIMEOUT, START_VR_TIMEOUT_VALUE);
        if (!mStartVoiceRecognitionWakeLock.isHeld()) {
            mStartVoiceRecognitionWakeLock.acquire(START_VR_TIMEOUT_VALUE);
        }
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>();
        Set<BluetoothDevice> bondedDevices = mAdapter.getBondedDevices();
        int connectionState;
        synchronized (this) {
            for (BluetoothDevice device : bondedDevices) {
                ParcelUuid[] featureUuids = device.getUuids();
                if (!BluetoothUuid.containsAnyUuid(featureUuids, HEADSET_UUIDS)) {
                    continue;
                }
                connectionState = getConnectionState(device);
                for(int i = 0; i < states.length; i++) {
                    if (connectionState == states[i]) {
                        deviceList.add(device);
                    }
                }
            }
        }
        return deviceList;
    }

    // This method does not check for error conditon (newState == prevState)
    private void broadcastConnectionState(BluetoothDevice device, int newState, int prevState) {
        log("Connection state " + device + ": " + prevState + "->" + newState);
        if(prevState == BluetoothProfile.STATE_CONNECTED) {
            // Headset is disconnecting, stop Virtual call if active.
            terminateScoUsingVirtualVoiceCall();
        }

        /* Notifying the connection state change of the profile before sending the intent for
           connection state change, as it was causing a race condition, with the UI not being
           updated with the correct connection state. */
        mService.notifyProfileConnectionStateChanged(device, BluetoothProfile.HEADSET,
                                                     newState, prevState);
        Intent intent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mService.sendBroadcast(intent, HeadsetService.BLUETOOTH_PERM);
    }

    private void broadcastAudioState(BluetoothDevice device, int newState, int prevState) {
        if(prevState == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
            // When SCO gets disconnected during call transfer, Virtual call
            //needs to be cleaned up.So call terminateScoUsingVirtualVoiceCall.
            terminateScoUsingVirtualVoiceCall();
        }
        Intent intent = new Intent(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mService.sendBroadcast(intent, HeadsetService.BLUETOOTH_PERM);
        log("Audio state " + device + ": " + prevState + "->" + newState);
    }

    /*
     * Put the AT command, company ID, arguments, and device in an Intent and broadcast it.
     */
    private void broadcastVendorSpecificEventIntent(String command,
                                                    int companyId,
                                                    int commandType,
                                                    Object[] arguments,
                                                    BluetoothDevice device) {
        log("broadcastVendorSpecificEventIntent(" + command + ")");
        Intent intent =
                new Intent(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD, command);
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE,
                        commandType);
        // assert: all elements of args are Serializable
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS, arguments);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);

        intent.addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY
            + "." + Integer.toString(companyId));

        mService.sendBroadcast(intent, HeadsetService.BLUETOOTH_PERM);
    }

    private void configAudioParameters()
    {
        // Reset NREC on connect event. Headset will override later
        mAudioManager.setParameters(HEADSET_NAME + "=" + getCurrentDeviceName() + ";" +
                                    HEADSET_NREC + "=on");
    }

    private void setAudioSamplerate()
    {
        if (mCodec != CODEC_MSBC) {
            Log.d(TAG, "Set sample rate: 8000");
            mAudioManager.setParameters(HEADSET_SAMPLERATE + "=8000");
        } else {
            Log.d(TAG, "Set sample rate: 16000");
            mAudioManager.setParameters(HEADSET_SAMPLERATE + "=16000");
        }
    }

    private String parseUnknownAt(String atString)
    {
        StringBuilder atCommand = new StringBuilder(atString.length());
        String result = null;

        for (int i = 0; i < atString.length(); i++) {
            char c = atString.charAt(i);
            if (c == '"') {
                int j = atString.indexOf('"', i + 1 );  // search for closing "
                if (j == -1) {  // unmatched ", insert one.
                    atCommand.append(atString.substring(i, atString.length()));
                    atCommand.append('"');
                    break;
                }
                atCommand.append(atString.substring(i, j + 1));
                i = j;
            } else if (c != ' ') {
                atCommand.append(Character.toUpperCase(c));
            }
        }
        result = atCommand.toString();
        return result;
    }

    private int getAtCommandType(String atCommand)
    {
        int commandType = mPhonebook.TYPE_UNKNOWN;
        String atString = null;
        atCommand = atCommand.trim();
        if (atCommand.length() > 5)
        {
            atString = atCommand.substring(5);
            if (atString.startsWith("?"))     // Read
                commandType = mPhonebook.TYPE_READ;
            else if (atString.startsWith("=?"))   // Test
                commandType = mPhonebook.TYPE_TEST;
            else if (atString.startsWith("="))   // Set
                commandType = mPhonebook.TYPE_SET;
            else
                commandType = mPhonebook.TYPE_UNKNOWN;
        }
        return commandType;
    }

    /* Method to check if Virtual Call in Progress */
    private boolean isVirtualCallInProgress() {
        return mVirtualCallStarted;
    }

    void setVirtualCallInProgress(boolean state) {
        mVirtualCallStarted = state;
    }

    /* NOTE: Currently the VirtualCall API does not support handling of
    call transfers. If it is initiated from the handsfree device,
    HeadsetStateMachine will end the virtual call by calling
    terminateScoUsingVirtualVoiceCall() in broadcastAudioState() */
    synchronized boolean initiateScoUsingVirtualVoiceCall() {
        if (DBG) log("initiateScoUsingVirtualVoiceCall: Received");
        // 1. Check if the SCO state is idle
        if (isInCall() || mVoiceRecognitionStarted) {
            Log.e(TAG, "initiateScoUsingVirtualVoiceCall: Call in progress.");
            return false;
        }
        setVirtualCallInProgress(true);
        if (mA2dpState == BluetoothProfile.STATE_CONNECTED) {
            mAudioManager.setParameters("A2dpSuspended=true");
            mA2dpSuspend = true;
            if (mA2dpPlayState == BluetoothA2dp.STATE_PLAYING) {
                log("suspending A2DP stream for SCO");
                mPendingCiev = true;
                return true;
            }
        }

        // 2. Send virtual phone state changed to initialize SCO
        processCallState(new HeadsetCallState(0, 0,
            HeadsetHalConstants.CALL_STATE_DIALING, "", 0), true);
        processCallState(new HeadsetCallState(0, 0,
            HeadsetHalConstants.CALL_STATE_ALERTING, "", 0), true);
        processCallState(new HeadsetCallState(1, 0,
            HeadsetHalConstants.CALL_STATE_IDLE, "", 0), true);
        // Done
        if (DBG) log("initiateScoUsingVirtualVoiceCall: Done");
        return true;
    }

    synchronized boolean terminateScoUsingVirtualVoiceCall() {
        if (DBG) log("terminateScoUsingVirtualVoiceCall: Received");

        if (!isVirtualCallInProgress()) {
            Log.e(TAG, "terminateScoUsingVirtualVoiceCall:"+
                "No present call to terminate");
            return false;
        }

        // 2. Send virtual phone state changed to close SCO
        processCallState(new HeadsetCallState(0, 0,
            HeadsetHalConstants.CALL_STATE_IDLE, "", 0), true);
        setVirtualCallInProgress(false);
        // Virtual call is Ended set A2dpSuspended to false
        if (mA2dpSuspend) {
            mAudioManager.setParameters("A2dpSuspended=false");
            mA2dpSuspend = false;
        }

        // Done
        if (DBG) log("terminateScoUsingVirtualVoiceCall: Done");
        return true;
    }

    /* Check for a2dp state change.mA2dpSuspend is set if we had suspended stream and process only in
       that condition A2dp state could be in playing soon after connection if Headset got
       connected while in call and music was played before that (Special case
       to handle RINGER VOLUME zero + music + call) */
    private void processIntentA2dpStateChanged(Intent intent) {

        int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                           BluetoothProfile.STATE_DISCONNECTED);
        int oldState = intent.getIntExtra(BluetoothProfile.
                       EXTRA_PREVIOUS_STATE,BluetoothProfile.STATE_DISCONNECTED);
        if (DBG) {
            Log.v(TAG, "A2dp State Changed: Current State: " + state +
                  "Prev State: " + oldState + "A2pSuspend: " + mA2dpSuspend);
        }
        mA2dpState = state;
    }

    private void processIntentA2dpPlayStateChanged(Intent intent) {

        int currState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                                   BluetoothA2dp.STATE_NOT_PLAYING);
        int prevState = intent.getIntExtra(
                                   BluetoothProfile.EXTRA_PREVIOUS_STATE,
                                   BluetoothA2dp.STATE_NOT_PLAYING);
        if (DBG) {
            Log.v(TAG, "A2dp Play State Changed: Current State: " + currState +
                  "Prev State: " + prevState + "A2pSuspend: " + mA2dpSuspend);
        }
        mA2dpPlayState = currState;

        if (prevState == BluetoothA2dp.STATE_PLAYING) {
            if (mA2dpSuspend && mPendingCiev) {
                if (isVirtualCallInProgress()) {
                    //Send virtual phone state changed to initialize SCO
                    processCallState(new HeadsetCallState(0, 0,
                          HeadsetHalConstants.CALL_STATE_DIALING, "", 0),
                          true);
                    processCallState(new HeadsetCallState(0, 0,
                          HeadsetHalConstants.CALL_STATE_ALERTING, "", 0),
                          true);
                    processCallState(new HeadsetCallState(1, 0,
                          HeadsetHalConstants.CALL_STATE_IDLE, "", 0),
                          true);
                } else {
                    //send incomming phone status to remote device
                    log("A2dp is suspended, updating phone status if any");
                    phoneStateChangeNative( mPhoneState.getNumActiveCall(),
                                            mPhoneState.getNumHeldCall(),mPhoneState.getCallState(),
                                            mPhoneState.getNumber(),mPhoneState.getType());
                }
                mPendingCiev = false;
            }
        }
        else if (prevState == BluetoothA2dp.STATE_NOT_PLAYING) {
             Log.v(TAG,"A2dp Started " + currState);
            if ((isInCall() || isVirtualCallInProgress()) && isConnected()) {
                if(mA2dpSuspend)
                    Log.e(TAG,"A2dp started while in call, ERROR");
                else {
                    log("Suspend A2dp");
                    mA2dpSuspend = true;
                    mAudioManager.setParameters("A2dpSuspended=true");
                }
            }
        }
    }

    private void processAnswerCall() {
        if (mPhoneProxy != null) {
            try {
                mPhoneProxy.answerCall();
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
            }
        } else {
            Log.e(TAG, "Handsfree phone proxy null for answering call");
        }
    }

    private void processHangupCall() {
        // Close the virtual call if active. Virtual call should be
        // terminated for CHUP callback event
        if (isVirtualCallInProgress()) {
            terminateScoUsingVirtualVoiceCall();
        } else {
            if (mPhoneProxy != null) {
                try {
                    mPhoneProxy.hangupCall();
                } catch (RemoteException e) {
                    Log.e(TAG, Log.getStackTraceString(new Throwable()));
                }
            } else {
                Log.e(TAG, "Handsfree phone proxy null for hanging up call");
            }
        }
    }

    private void processDialCall(String number) {
        String dialNumber;
        if (mDialingOut) {
            if (DBG) log("processDialCall, already dialling");
            atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
            return;
        }
        if ((number == null) || (number.length() == 0)) {
            dialNumber = mPhonebook.getLastDialledNumber();
            if (dialNumber == null) {
                if (DBG) log("processDialCall, last dial number null");
                atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
                return;
            }
        } else if (number.charAt(0) == '>') {
            // Yuck - memory dialling requested.
            // Just dial last number for now
            if (number.startsWith(">9999")) {   // for PTS test
                atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
                return;
            }
            if (DBG) log("processDialCall, memory dial do last dial for now");
            dialNumber = mPhonebook.getLastDialledNumber();
            if (dialNumber == null) {
                if (DBG) log("processDialCall, last dial number null");
                atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
                return;
            }
        } else {
            // Remove trailing ';'
            if (number.charAt(number.length() - 1) == ';') {
                number = number.substring(0, number.length() - 1);
            }

            dialNumber = PhoneNumberUtils.convertPreDial(number);
        }
        // Check for virtual call to terminate before sending Call Intent
        terminateScoUsingVirtualVoiceCall();

        Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                                   Uri.fromParts(SCHEME_TEL, dialNumber, null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mService.startActivity(intent);
        // TODO(BT) continue send OK reults code after call starts
        //          hold wait lock, start a timer, set wait call flag
        //          Get call started indication from bluetooth phone
        mDialingOut = true;
        sendMessageDelayed(DIALING_OUT_TIMEOUT, DIALING_OUT_TIMEOUT_VALUE);
    }

    private void processVolumeEvent(int volumeType, int volume) {
        if (volumeType == HeadsetHalConstants.VOLUME_TYPE_SPK) {
            mPhoneState.setSpeakerVolume(volume);
            int flag = (getCurrentState() == mAudioOn) ? AudioManager.FLAG_SHOW_UI : 0;
            mAudioManager.setStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO, volume, flag);
        } else if (volumeType == HeadsetHalConstants.VOLUME_TYPE_MIC) {
            mPhoneState.setMicVolume(volume);
        } else {
            Log.e(TAG, "Bad voluem type: " + volumeType);
        }
    }

    private void processSendDtmf(int dtmf) {
        if (mPhoneProxy != null) {
            try {
                mPhoneProxy.sendDtmf(dtmf);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
            }
        } else {
            Log.e(TAG, "Handsfree phone proxy null for sending DTMF");
        }
    }

    private void processCallState(HeadsetCallState callState) {
        processCallState(callState, false);
    }

    private void processCallState(HeadsetCallState callState,
        boolean isVirtualCall) {
        mPhoneState.setNumActiveCall(callState.mNumActive);
        mPhoneState.setNumHeldCall(callState.mNumHeld);
        mPhoneState.setCallState(callState.mCallState);
        mPhoneState.setNumber(callState.mNumber);
        mPhoneState.setType(callState.mType);
        if (mDialingOut && callState.mCallState ==
            HeadsetHalConstants.CALL_STATE_DIALING) {
                atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_OK, 0);
                removeMessages(DIALING_OUT_TIMEOUT);
                mDialingOut = false;
        }
        log("mNumActive: " + callState.mNumActive + " mNumHeld: " +
            callState.mNumHeld +" mCallState: " + callState.mCallState);
        log("mNumber: " + callState.mNumber + " mType: " + callState.mType);
        if(!isVirtualCall) {
            /* Not a Virtual call request. End the virtual call, if running,
            before sending phoneStateChangeNative to BTIF */
            terminateScoUsingVirtualVoiceCall();
        }
        processA2dpState(callState);
    }

    /* This function makes sure that we send a2dp suspend before updating on Incomming call status.
       There may problem with some headsets if send ring and a2dp is not suspended,
       so here we suspend stream if active before updating remote.We resume streaming once
       callstate is idle and there are no active or held calls. */

    private void processA2dpState(HeadsetCallState callState) {
        if (DBG) {
            log("mA2dpPlayState " + mA2dpPlayState + " mA2dpSuspend  " + mA2dpSuspend );
        }
        if ((isInCall()) && (isConnected()) &&
            (mA2dpState == BluetoothProfile.STATE_CONNECTED) && (!mA2dpSuspend)) {
            mAudioManager.setParameters("A2dpSuspended=true");
            mA2dpSuspend = true;
            if (mA2dpPlayState == BluetoothA2dp.STATE_PLAYING) {
                log("suspending A2DP stream for Call");
                mPendingCiev = true;
                return ;
            }
        }
        if (getCurrentState() != mDisconnected) {
            if (DBG) {
                log("No A2dp playing to suspend");
            }
            phoneStateChangeNative(callState.mNumActive, callState.mNumHeld,
                callState.mCallState, callState.mNumber, callState.mType);
        }
        if (mA2dpSuspend && (!isAudioOn())) {
            if ((!isInCall()) && (callState.mNumber.isEmpty())) {
                log("Set A2dpSuspended=false to reset the a2dp state to standby");
                mAudioManager.setParameters("A2dpSuspended=false");
                mA2dpSuspend = false;
            }
        }
    }

    // enable 1 enable noice reduction
    //        0 disable noice reduction
    private void processNoiceReductionEvent(int enable) {
        if (enable == 1) {
            mAudioManager.setParameters(HEADSET_NREC + "=on");
        } else {
            mAudioManager.setParameters(HEADSET_NREC + "=off");
        }
    }

    private void processAtChld(int chld) {
        if (mPhoneProxy != null) {
            try {
                if (mPhoneProxy.processChld(chld)) {
                    atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_OK, 0);
                } else {
                    atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
                }
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
            }
        } else {
            Log.e(TAG, "Handsfree phone proxy null for At+Chld");
            atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        }
    }

    private void processSubscriberNumberRequest() {
        if (mPhoneProxy != null) {
            try {
                String number = mPhoneProxy.getSubscriberNumber();
                if (number != null) {
                    atResponseStringNative("+CNUM: ,\"" + number + "\"," +
                                           PhoneNumberUtils.toaFromString(number) + ",,4");
                    atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_OK, 0);
                }
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
            }
        } else {
            Log.e(TAG, "Handsfree phone proxy null for At+CNUM");
        }
    }

    private void processAtCind() {
        int call, call_setup;

        /* Handsfree carkits expect that +CIND is properly responded to
         Hence we ensure that a proper response is sent
         for the virtual call too.*/
        if (isVirtualCallInProgress()) {
            call = 1;
            call_setup = 0;
        } else {
            // regular phone call
            call = mPhoneState.getNumActiveCall();
            call_setup = mPhoneState.getNumHeldCall();
        }

        cindResponseNative(mPhoneState.getService(), call,
                           call_setup, mPhoneState.getCallState(),
                           mPhoneState.getSignal(), mPhoneState.getRoam(),
                           mPhoneState.getBatteryCharge());
    }

    private void processAtCops() {
        if (mPhoneProxy != null) {
            try {
                String operatorName = mPhoneProxy.getNetworkOperator();
                if (operatorName == null) {
                    operatorName = "";
                } 
                copsResponseNative(operatorName);
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                copsResponseNative("");
            }
        } else {
            Log.e(TAG, "Handsfree phone proxy null for At+COPS");
            copsResponseNative("");
        }
    }

    private void processAtClcc() {
        if (mPhoneProxy != null) {
            try {
                if(isVirtualCallInProgress()) {
                    String phoneNumber = "";
                    int type = PhoneNumberUtils.TOA_Unknown;
                    try {
                        phoneNumber = mPhoneProxy.getSubscriberNumber();
                        type = PhoneNumberUtils.toaFromString(phoneNumber);
                    } catch (RemoteException ee) {
                        Log.e(TAG, "Unable to retrieve phone number"+
                            "using IBluetoothHeadsetPhone proxy");
                        phoneNumber = "";
                    }
                    clccResponseNative(1, 0, 0, 0, false, phoneNumber, type);
                    clccResponseNative(0, 0, 0, 0, false, "", 0);
                }
                else if (!mPhoneProxy.listCurrentCalls()) {
                    clccResponseNative(0, 0, 0, 0, false, "", 0);
                }
            } catch (RemoteException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
                clccResponseNative(0, 0, 0, 0, false, "", 0);
            }
        } else {
            Log.e(TAG, "Handsfree phone proxy null for At+CLCC");
            clccResponseNative(0, 0, 0, 0, false, "", 0);
        }
    }

    private void processAtCscs(String atString, int type) {
        log("processAtCscs - atString = "+ atString);
        if(mPhonebook != null) {
            mPhonebook.handleCscsCommand(atString, type);
        }
        else {
            Log.e(TAG, "Phonebook handle null for At+CSCS");
            atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        }
    }

    private void processAtCpbs(String atString, int type) {
        log("processAtCpbs - atString = "+ atString);
        if(mPhonebook != null) {
            mPhonebook.handleCpbsCommand(atString, type);
        }
        else {
            Log.e(TAG, "Phonebook handle null for At+CPBS");
            atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        }
    }

    private void processAtCpbr(String atString, int type, BluetoothDevice mCurrentDevice) {
        log("processAtCpbr - atString = "+ atString);
        if(mPhonebook != null) {
            mPhonebook.handleCpbrCommand(atString, type, mCurrentDevice);
        }
        else {
            Log.e(TAG, "Phonebook handle null for At+CPBR");
            atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        }
    }

    /**
     * Find a character ch, ignoring quoted sections.
     * Return input.length() if not found.
     */
    static private int findChar(char ch, String input, int fromIndex) {
        for (int i = fromIndex; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                i = input.indexOf('"', i + 1);
                if (i == -1) {
                    return input.length();
                }
            } else if (c == ch) {
                return i;
            }
        }
        return input.length();
    }

    /**
     * Break an argument string into individual arguments (comma delimited).
     * Integer arguments are turned into Integer objects. Otherwise a String
     * object is used.
     */
    static private Object[] generateArgs(String input) {
        int i = 0;
        int j;
        ArrayList<Object> out = new ArrayList<Object>();
        while (i <= input.length()) {
            j = findChar(',', input, i);

            String arg = input.substring(i, j);
            try {
                out.add(new Integer(arg));
            } catch (NumberFormatException e) {
                out.add(arg);
            }

            i = j + 1; // move past comma
        }
        return out.toArray();
    }

    /**
     * @return {@code true} if the given string is a valid vendor-specific AT command.
     */
    private boolean processVendorSpecificAt(String atString) {
        log("processVendorSpecificAt - atString = " + atString);

        // Currently we accept only SET type commands.
        int indexOfEqual = atString.indexOf("=");
        if (indexOfEqual == -1) {
            Log.e(TAG, "processVendorSpecificAt: command type error in " + atString);
            return false;
        }

        String command = atString.substring(0, indexOfEqual);
        Integer companyId = VENDOR_SPECIFIC_AT_COMMAND_COMPANY_ID.get(command);
        if (companyId == null) {
            Log.e(TAG, "processVendorSpecificAt: unsupported command: " + atString);
            return false;
        }

        String arg = atString.substring(indexOfEqual + 1);
        if (arg.startsWith("?")) {
            Log.e(TAG, "processVendorSpecificAt: command type error in " + atString);
            return false;
        }

        Object[] args = generateArgs(arg);
        broadcastVendorSpecificEventIntent(command,
                                           companyId,
                                           BluetoothHeadset.AT_CMD_TYPE_SET,
                                           args,
                                           mCurrentDevice);
        atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_OK, 0);
        return true;
    }

    private void processUnknownAt(String atString) {
        // TODO (BT)
        log("processUnknownAt - atString = "+ atString);
        String atCommand = parseUnknownAt(atString);
        int commandType = getAtCommandType(atCommand);
        if (atCommand.startsWith("+CSCS"))
            processAtCscs(atCommand.substring(5), commandType);
        else if (atCommand.startsWith("+CPBS"))
            processAtCpbs(atCommand.substring(5), commandType);
        else if (atCommand.startsWith("+CPBR"))
            processAtCpbr(atCommand.substring(5), commandType, mCurrentDevice);
        else if (!processVendorSpecificAt(atCommand))
            atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
    }

    private void processKeyPressed() {
        if (mPhoneState.getCallState() == HeadsetHalConstants.CALL_STATE_INCOMING) {
            if (mPhoneProxy != null) {
                try {
                    mPhoneProxy.answerCall();
                } catch (RemoteException e) {
                    Log.e(TAG, Log.getStackTraceString(new Throwable()));
                }
            } else {
                Log.e(TAG, "Handsfree phone proxy null for answering call");
            }
        } else if (mPhoneState.getNumActiveCall() > 0) {
            if (!isAudioOn())
            {
                connectAudioNative(getByteAddress(mCurrentDevice));
            }
            else
            {
                if (mPhoneProxy != null) {
                    try {
                        mPhoneProxy.hangupCall();
                    } catch (RemoteException e) {
                        Log.e(TAG, Log.getStackTraceString(new Throwable()));
                    }
                } else {
                    Log.e(TAG, "Handsfree phone proxy null for hangup call");
                }
            }
        } else {
            String dialNumber = mPhonebook.getLastDialledNumber();
            if (dialNumber == null) {
                if (DBG) log("processKeyPressed, last dial number null");
                return;
            }
            Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                                       Uri.fromParts(SCHEME_TEL, dialNumber, null));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mService.startActivity(intent);
        }
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

    private void onVrStateChanged(int state) {
        StackEvent event = new StackEvent(EVENT_TYPE_VR_STATE_CHANGED);
        event.valueInt = state;
        sendMessage(STACK_EVENT, event);
    }

    private void onAnswerCall() {
        StackEvent event = new StackEvent(EVENT_TYPE_ANSWER_CALL);
        sendMessage(STACK_EVENT, event);
    }

    private void onHangupCall() {
        StackEvent event = new StackEvent(EVENT_TYPE_HANGUP_CALL);
        sendMessage(STACK_EVENT, event);
    }

    private void onVolumeChanged(int type, int volume) {
        StackEvent event = new StackEvent(EVENT_TYPE_VOLUME_CHANGED);
        event.valueInt = type;
        event.valueInt2 = volume;
        sendMessage(STACK_EVENT, event);
    }

    private void onDialCall(String number) {
        StackEvent event = new StackEvent(EVENT_TYPE_DIAL_CALL);
        event.valueString = number;
        sendMessage(STACK_EVENT, event);
    }

    private void onSendDtmf(int dtmf) {
        StackEvent event = new StackEvent(EVENT_TYPE_SEND_DTMF);
        event.valueInt = dtmf;
        sendMessage(STACK_EVENT, event);
    }

    private void onNoiceReductionEnable(boolean enable) {
        StackEvent event = new StackEvent(EVENT_TYPE_NOICE_REDUCTION);
        event.valueInt = enable ? 1 : 0;
        sendMessage(STACK_EVENT, event);
    }

    private void onAtChld(int chld) {
        StackEvent event = new StackEvent(EVENT_TYPE_AT_CHLD);
        event.valueInt = chld;
        sendMessage(STACK_EVENT, event);
    }

    private void onAtCnum() {
        StackEvent event = new StackEvent(EVENT_TYPE_SUBSCRIBER_NUMBER_REQUEST);
        sendMessage(STACK_EVENT, event);
    }

    private void onAtCind() {
        StackEvent event = new StackEvent(EVENT_TYPE_AT_CIND);
        sendMessage(STACK_EVENT, event);
    }

    private void onAtCops() {
        StackEvent event = new StackEvent(EVENT_TYPE_AT_COPS);
        sendMessage(STACK_EVENT, event);
    }

    private void onAtClcc() {
        StackEvent event = new StackEvent(EVENT_TYPE_AT_CLCC);
        sendMessage(STACK_EVENT, event);
    }

    private void onUnknownAt(String atString) {
        StackEvent event = new StackEvent(EVENT_TYPE_UNKNOWN_AT);
        event.valueString = atString;
        sendMessage(STACK_EVENT, event);
    }

    private void onKeyPressed() {
        StackEvent event = new StackEvent(EVENT_TYPE_KEY_PRESSED);
        sendMessage(STACK_EVENT, event);
    }

    private void onCodecNegotiated(int codec_type){
        Log.d(TAG, "onCodecNegotiated: The value is: " + codec_type);
        mCodec = codec_type;
    }

    private void processIntentBatteryChanged(Intent intent) {
        int batteryLevel = intent.getIntExtra("level", -1);
        int scale = intent.getIntExtra("scale", -1);
        if (batteryLevel == -1 || scale == -1 || scale == 0) {
            Log.e(TAG, "Bad Battery Changed intent: " + batteryLevel + "," + scale);
            return;
        }
        batteryLevel = batteryLevel * 5 / scale;
        mPhoneState.setBatteryCharge(batteryLevel);
    }

    private void processDeviceStateChanged(HeadsetDeviceState deviceState) {
        notifyDeviceStatusNative(deviceState.mService, deviceState.mRoam, deviceState.mSignal,
                                 deviceState.mBatteryCharge);
    }

    private void processSendClccResponse(HeadsetClccResponse clcc) {
        clccResponseNative(clcc.mIndex, clcc.mDirection, clcc.mStatus, clcc.mMode, clcc.mMpty,
                           clcc.mNumber, clcc.mType);
    }

    private void processSendVendorSpecificResultCode(HeadsetVendorSpecificResultCode resultCode) {
        String stringToSend = resultCode.mCommand + ": ";
        if (resultCode.mArg != null) {
            stringToSend += resultCode.mArg;
        }
        atResponseStringNative(stringToSend);
    }

    private String getCurrentDeviceName() {
        String defaultName = "<unknown>";
        if (mCurrentDevice == null) {
            return defaultName;
        }
        String deviceName = mCurrentDevice.getName();
        if (deviceName == null) {
            return defaultName;
        }
        return deviceName;
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
    }

    private BluetoothDevice getDevice(byte[] address) {
        return mAdapter.getRemoteDevice(Utils.getAddressStringFromByte(address));
    }

    private boolean isInCall() {
        return ((mPhoneState.getNumActiveCall() > 0) || (mPhoneState.getNumHeldCall() > 0) ||
                (mPhoneState.getCallState() != HeadsetHalConstants.CALL_STATE_IDLE));
    }

    boolean isConnected() {
        IState currentState = getCurrentState();
        return (currentState == mConnected || currentState == mAudioOn);
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

    @Override
    protected void log(String msg) {
        if (DBG) {
            super.log(msg);
        }
    }

    public void handleAccessPermissionResult(Intent intent) {
        log("handleAccessPermissionResult");
        if(mPhonebook != null) {
            if (!mPhonebook.getCheckingAccessPermission()) {
                return;
            }
            int atCommandResult = 0;
            int atCommandErrorCode = 0;
            //HeadsetBase headset = mHandsfree.getHeadset();
            // ASSERT: (headset != null) && headSet.isConnected()
            // REASON: mCheckingAccessPermission is true, otherwise resetAtState
            // has set mCheckingAccessPermission to false
            if (intent.getAction().equals(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY)) {
                if (intent.getIntExtra(BluetoothDevice.EXTRA_CONNECTION_ACCESS_RESULT,
                    BluetoothDevice.CONNECTION_ACCESS_NO) ==
                    BluetoothDevice.CONNECTION_ACCESS_YES) {
                    if (intent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)) {
                        mCurrentDevice.setTrust(true);
                    }
                    atCommandResult = mPhonebook.processCpbrCommand();
                }
            }
            mPhonebook.setCpbrIndex(-1);
            mPhonebook.setCheckingAccessPermission(false);

            if (atCommandResult >= 0) {
                atResponseCodeNative(atCommandResult, atCommandErrorCode);
            }
            else
                log("handleAccessPermissionResult - RESULT_NONE");
        }
        else {
            Log.e(TAG, "Phonebook handle null");
            atResponseCodeNative(HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        }
    }

    private static final String SCHEME_TEL = "tel";

    // Event types for STACK_EVENT message
    final private static int EVENT_TYPE_NONE = 0;
    final private static int EVENT_TYPE_CONNECTION_STATE_CHANGED = 1;
    final private static int EVENT_TYPE_AUDIO_STATE_CHANGED = 2;
    final private static int EVENT_TYPE_VR_STATE_CHANGED = 3;
    final private static int EVENT_TYPE_ANSWER_CALL = 4;
    final private static int EVENT_TYPE_HANGUP_CALL = 5;
    final private static int EVENT_TYPE_VOLUME_CHANGED = 6;
    final private static int EVENT_TYPE_DIAL_CALL = 7;
    final private static int EVENT_TYPE_SEND_DTMF = 8;
    final private static int EVENT_TYPE_NOICE_REDUCTION = 9;
    final private static int EVENT_TYPE_AT_CHLD = 10;
    final private static int EVENT_TYPE_SUBSCRIBER_NUMBER_REQUEST = 11;
    final private static int EVENT_TYPE_AT_CIND = 12;
    final private static int EVENT_TYPE_AT_COPS = 13;
    final private static int EVENT_TYPE_AT_CLCC = 14;
    final private static int EVENT_TYPE_UNKNOWN_AT = 15;
    final private static int EVENT_TYPE_KEY_PRESSED = 16;

    private class StackEvent {
        int type = EVENT_TYPE_NONE;
        int valueInt = 0;
        int valueInt2 = 0;
        String valueString = null;
        BluetoothDevice device = null;

        private StackEvent(int type) {
            this.type = type;
        }
    }

    /*package*/native boolean atResponseCodeNative(int responseCode, int errorCode);
    /*package*/ native boolean atResponseStringNative(String responseString);

    private native static void classInitNative();
    private native void initializeNative();
    private native void initializeFeaturesNative(int feature_bitmask);
    private native void cleanupNative();
    private native boolean connectHfpNative(byte[] address);
    private native boolean disconnectHfpNative(byte[] address);
    private native boolean connectAudioNative(byte[] address);
    private native boolean disconnectAudioNative(byte[] address);
    private native boolean startVoiceRecognitionNative();
    private native boolean stopVoiceRecognitionNative();
    private native boolean setVolumeNative(int volumeType, int volume);
    private native boolean cindResponseNative(int service, int numActive, int numHeld,
                                              int callState, int signal, int roam,
                                              int batteryCharge);
    private native boolean notifyDeviceStatusNative(int networkState, int serviceType, int signal,
                                                    int batteryCharge);

    private native boolean clccResponseNative(int index, int dir, int status, int mode,
                                              boolean mpty, String number, int type);
    private native boolean copsResponseNative(String operatorName);

    private native boolean phoneStateChangeNative(int numActive, int numHeld, int callState,
                                                  String number, int type);
}
