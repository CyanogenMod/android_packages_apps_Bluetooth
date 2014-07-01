/*
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

import java.util.Timer;
import java.util.TimerTask;

import android.app.PendingIntent;
import android.bluetooth.BluetoothA2dp;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.IRemoteControlDisplay;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;
import android.content.BroadcastReceiver;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.Utils;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Iterator;

/**
 * support Bluetooth AVRCP profile.
 * support metadata, play status and event notification
 */
final class Avrcp {
    private static final boolean DEBUG = true;
    private static final String TAG = "Avrcp";

    private Context mContext;
    private final AudioManager mAudioManager;
    private AvrcpMessageHandler mHandler;
    private IRemoteControlDisplayWeak mRemoteControlDisplay;
    private int mClientGeneration;
    private Metadata mMetadata;
    private int mTransportControlFlags;
    private int mCurrentPlayState;
    private int mPlayStatusChangedNT;
    private int mPlayerStatusChangeNT;
    private int mTrackChangedNT;
    private long mTrackNumber;
    private long mCurrentPosMs;
    private long mPlayStartTimeMs;
    private long mSongLengthMs;
    private long mPlaybackIntervalMs;
    private int mPlayPosChangedNT;
    private long mNextPosMs;
    private long mPrevPosMs;
    private long mSkipStartTime;
    private int mFeatures;
    private int mAbsoluteVolume;
    private int mLastSetVolume;
    private int mLastDirection;
    private final int mVolumeStep;
    private final int mAudioStreamMax;
    private boolean mVolCmdInProgress;
    private int mAbsVolRetryTimes;
    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;
    private int mSkipAmount;
    private int keyPressState;

    /* AVRC IDs from avrc_defs.h */
    private static final int AVRC_ID_REWIND = 0x48;
    private static final int AVRC_ID_FAST_FOR = 0x49;

    /* BTRC features */
    public static final int BTRC_FEAT_METADATA = 0x01;
    public static final int BTRC_FEAT_ABSOLUTE_VOLUME = 0x02;
    public static final int BTRC_FEAT_BROWSE = 0x04;

    /* AVRC response codes, from avrc_defs */
    private static final int AVRC_RSP_NOT_IMPL = 8;
    private static final int AVRC_RSP_ACCEPT = 9;
    private static final int AVRC_RSP_REJ = 10;
    private static final int AVRC_RSP_IN_TRANS = 11;
    private static final int AVRC_RSP_IMPL_STBL = 12;
    private static final int AVRC_RSP_CHANGED = 13;
    private static final int AVRC_RSP_INTERIM = 15;

    private static final int MESSAGE_GET_RC_FEATURES = 1;
    private static final int MESSAGE_GET_PLAY_STATUS = 2;
    private static final int MESSAGE_GET_ELEM_ATTRS = 3;
    private static final int MESSAGE_REGISTER_NOTIFICATION = 4;
    private static final int MESSAGE_PLAY_INTERVAL_TIMEOUT = 5;
    private static final int MESSAGE_VOLUME_CHANGED = 6;
    private static final int MESSAGE_ADJUST_VOLUME = 7;
    private static final int MESSAGE_SET_ABSOLUTE_VOLUME = 8;
    private static final int MESSAGE_ABS_VOL_TIMEOUT = 9;
    private static final int MESSAGE_FAST_FORWARD = 10;
    private static final int MESSAGE_REWIND = 11;
    private static final int MESSAGE_CHANGE_PLAY_POS = 12;
    private static final int MESSAGE_SET_A2DP_AUDIO_STATE = 13;

    private static final int MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT = 14;
    private static final int SET_ADDR_PLAYER_TIMEOUT = 2000;

    private int mAddressedPlayerChangedNT;
    private int mAvailablePlayersChangedNT;
    private int mAddressedPlayerId;
    private String mRequestedAddressedPlayerPackageName;

    private static final int MSG_UPDATE_STATE = 100;
    private static final int MSG_SET_METADATA = 101;
    private static final int MSG_SET_TRANSPORT_CONTROLS = 102;
    private static final int MSG_SET_ARTWORK = 103;
    private static final int MSG_SET_GENERATION_ID = 104;
    private static final int MSG_UPDATE_AVAILABLE_PLAYERS = 201;
    private static final int MSG_UPDATE_ADDRESSED_PLAYER = 202;
    private static final int MSG_UPDATE_RCC_CHANGE = 203;
    private MediaPlayerInfo mediaPlayerInfo1;
    private MediaPlayerInfo mediaPlayerInfo2;

    private static final int BUTTON_TIMEOUT_TIME = 2000;
    private static final int BASE_SKIP_AMOUNT = 2000;
    private static final int KEY_STATE_PRESS = 1;
    private static final int KEY_STATE_RELEASE = 0;
    private static final int SKIP_PERIOD = 400;
    private static final int SKIP_DOUBLE_INTERVAL = 3000;
    private static final long MAX_MULTIPLIER_VALUE = 128L;
    private static final int CMD_TIMEOUT_DELAY = 2000;
    private static final int MAX_ERROR_RETRY_TIMES = 3;
    private static final int AVRCP_MAX_VOL = 127;
    private static final int AVRCP_BASE_VOLUME_STEP = 1;
    private final static int MESSAGE_PLAYERSETTINGS_TIMEOUT = 602;

    private static final int ERR_INVALID_PLAYER_ID = 0x11;
    private static final int ERR_ADDR_PLAYER_FAILS = 0x13;
    private static final int ERR_ADDR_PLAYER_SUCCEEDS = 0x04;

    //Intents for PlayerApplication Settings
    private static final String PLAYERSETTINGS_REQUEST = "org.codeaurora.music.playersettingsrequest";
    private static final String PLAYERSETTINGS_RESPONSE =
       "org.codeaurora.music.playersettingsresponse";

    private class PlayerSettings {
        public byte attr;
        public byte [] attrIds;
        public String path;
    };

    private PlayerSettings mPlayerSettings = new PlayerSettings();
    private class localPlayerSettings {
        public byte eq_value = 0x01;
        public byte repeat_value = 0x01;
        public byte shuffle_value = 0x01;
        public byte scan_value = 0x01;
    };
    private localPlayerSettings settingValues = new localPlayerSettings();
    private static final String COMMAND = "command";
    private static final String CMDGET = "get";
    private static final String CMDSET = "set";
    private static final String EXTRA_GET_COMMAND = "commandExtra";
    private static final String EXTRA_GET_RESPONSE = "Response";

    private static final int GET_ATTRIBUTE_IDS = 0;
    private static final int GET_VALUE_IDS = 1;
    private static final int GET_ATTRIBUTE_TEXT = 2;
    private static final int GET_VALUE_TEXT     = 3;
    private static final int GET_ATTRIBUTE_VALUES = 4;
    private static final int NOTIFY_ATTRIBUTE_VALUES = 5;
    private static final int SET_ATTRIBUTE_VALUES  = 6;
    private static final int GET_INVALID = 0xff;

    private static final String EXTRA_ATTRIBUTE_ID = "Attribute";
    private static final String EXTRA_VALUE_STRING_ARRAY = "ValueStrings";
    private static final String EXTRA_ATTRIB_VALUE_PAIRS = "AttribValuePairs";
    private static final String EXTRA_ATTRIBUTE_STRING_ARRAY = "AttributeStrings";
    private static final String EXTRA_VALUE_ID_ARRAY = "Values";
    private static final String EXTRA_ATTIBUTE_ID_ARRAY = "Attributes";

    public static final int VALUE_SHUFFLEMODE_OFF = 1;
    public static final int VALUE_SHUFFLEMODE_ALL = 2;
    public static final int VALUE_REPEATMODE_OFF = 1;
    public static final int VALUE_REPEATMODE_SINGLE = 2;
    public static final int VALUE_REPEATMODE_ALL = 3;
    public static final int VALUE_INVALID = 0;

    public static final int ATTRIBUTE_EQUALIZER = 1;
    public static final int ATTRIBUTE_REPEATMODE = 2;
    public static final int ATTRIBUTE_SHUFFLEMODE = 3;
    public static final int ATTRIBUTE_SCANMODE = 4;
    public static final int NUMPLAYER_ATTRIBUTE = 2;


    private byte [] def_attrib = new byte [] {ATTRIBUTE_REPEATMODE, ATTRIBUTE_SHUFFLEMODE};
    private byte [] value_repmode = new byte [] { VALUE_REPEATMODE_OFF,
                                                  VALUE_REPEATMODE_SINGLE,
                                                  VALUE_REPEATMODE_ALL };

    private byte [] value_shufmode = new byte [] { VALUE_SHUFFLEMODE_OFF,
                                                  VALUE_SHUFFLEMODE_ALL };
    private byte [] value_default = new byte [] {0};
    private final String UPDATE_ATTRIBUTES = "UpdateSupportedAttributes";
    private final String UPDATE_VALUES = "UpdateSupportedValues";
    private final String UPDATE_ATTRIB_VALUE = "UpdateCurrentValues";
    private final String UPDATE_ATTRIB_TEXT = "UpdateAttributesText";
    private final String UPDATE_VALUE_TEXT = "UpdateValuesText";
    private ArrayList <Integer> mPendingCmds;

    static {
        classInitNative();
    }

    private Avrcp(Context context) {
        if (DEBUG) Log.v(TAG, "Avrcp");
        mMetadata = new Metadata();
        mCurrentPlayState = RemoteControlClient.PLAYSTATE_NONE; // until we get a callback
        mPlayStatusChangedNT = NOTIFICATION_TYPE_CHANGED;
        mTrackChangedNT = NOTIFICATION_TYPE_CHANGED;
        mPlayerStatusChangeNT = NOTIFICATION_TYPE_CHANGED;
        mAddressedPlayerChangedNT = NOTIFICATION_TYPE_CHANGED;
        mAvailablePlayersChangedNT = NOTIFICATION_TYPE_CHANGED;
        mTrackNumber = -1L;
        mCurrentPosMs = 0L;
        mPlayStartTimeMs = -1L;
        mSongLengthMs = 0L;
        mPlaybackIntervalMs = 0L;
        mAddressedPlayerId = 0; //  0 signifies bad entry
        mPlayPosChangedNT = NOTIFICATION_TYPE_CHANGED;
        mFeatures = 0;
        mAbsoluteVolume = -1;
        mLastSetVolume = -1;
        mLastDirection = 0;
        mVolCmdInProgress = false;
        mAbsVolRetryTimes = 0;
        keyPressState = KEY_STATE_RELEASE; //Key release state
        mContext = context;

        initNative();

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mAudioStreamMax = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        mVolumeStep = Math.max(AVRCP_BASE_VOLUME_STEP, AVRCP_MAX_VOL/mAudioStreamMax);
    }

    private void start() {
        if (DEBUG) Log.v(TAG, "start");
        HandlerThread thread = new HandlerThread("BluetoothAvrcpHandler");
        thread.start();
        Looper looper = thread.getLooper();
        mHandler = new AvrcpMessageHandler(looper);
        mRemoteControlDisplay = new IRemoteControlDisplayWeak(mHandler);
        mAudioManager.registerRemoteControlDisplay(mRemoteControlDisplay);
        mAudioManager.remoteControlDisplayWantsPlaybackPositionSync(
                      mRemoteControlDisplay, true);
        mPendingCmds = new ArrayList<Integer>();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(AudioManager.RCC_CHANGED_ACTION);
        intentFilter.addAction(PLAYERSETTINGS_RESPONSE);
        try {
            mContext.registerReceiver(mIntentReceiver, intentFilter);
        }catch (Exception e) {
            Log.e(TAG,"Unable to register Avrcp receiver", e);
        }
        registerMediaPlayers();
    }

    //Listen to intents from MediaPlayer and Audio Manager and update data structures
    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(AudioManager.RCC_CHANGED_ACTION)) {
                Log.v(TAG, "received RCC_CHANGED_ACTION");
                int isRCCFocussed = 0;
                int isRCCAvailable = 0;
                String callingPackageName = intent.getStringExtra(AudioManager.EXTRA_CALLING_PACKAGE_NAME);
                boolean isFocussed = intent.getBooleanExtra(AudioManager.EXTRA_FOCUS_CHANGED_VALUE,false);
                boolean isAvailable = intent.getBooleanExtra(AudioManager.EXTRA_AVAILABLITY_CHANGED_VALUE, false);
                if (isFocussed)
                    isRCCFocussed = 1;
                if (isAvailable)
                    isRCCAvailable = 1;
                Log.v(TAG, "focus: " + isFocussed + " , availability: " + isAvailable);
                if (mHandler != null) {
                    mHandler.obtainMessage(MSG_UPDATE_RCC_CHANGE, isRCCFocussed, isRCCAvailable, callingPackageName).sendToTarget();
                }
            } else if (action.equals(PLAYERSETTINGS_RESPONSE)) {
                int getResponse = intent.getIntExtra(EXTRA_GET_RESPONSE,
                                                      GET_INVALID);
                byte [] data;
                String [] text;
                synchronized (mPendingCmds) {
                    Integer val = new Integer(getResponse);
                    if (mPendingCmds.contains(val)) {
                        mHandler.removeMessages(MESSAGE_PLAYERSETTINGS_TIMEOUT);
                        mPendingCmds.remove(val);
                    }
                }
                if (DEBUG) Log.v(TAG,"getResponse" + getResponse);
                switch (getResponse) {
                    case GET_ATTRIBUTE_IDS:
                        data = intent.getByteArrayExtra(EXTRA_ATTIBUTE_ID_ARRAY);
                        byte numAttr = (byte) data.length;
                        if (DEBUG) Log.v(TAG,"GET_ATTRIBUTE_IDS");
                        getListPlayerappAttrRspNative(numAttr,data);
                    break;
                    case GET_VALUE_IDS:
                        data = intent.getByteArrayExtra(EXTRA_VALUE_ID_ARRAY);
                        numAttr = (byte) data.length;
                        if (DEBUG) Log.v(TAG,"GET_VALUE_IDS" + numAttr);
                        getPlayerAppValueRspNative(numAttr, data);
                    break;
                    case GET_ATTRIBUTE_VALUES:
                    case NOTIFY_ATTRIBUTE_VALUES:
                        data = intent.getByteArrayExtra(EXTRA_ATTRIB_VALUE_PAIRS);
                        updateLocalPlayerSettings(data);
                        numAttr = (byte) data.length;
                        if (DEBUG) Log.v(TAG,"GET_ATTRIBUTE_VALUES" + numAttr);
                        if (mPlayerStatusChangeNT == NOTIFICATION_TYPE_INTERIM && getResponse
                                                                  == NOTIFY_ATTRIBUTE_VALUES) {
                        mPlayerStatusChangeNT = NOTIFICATION_TYPE_CHANGED;
                        sendPlayerAppChangedRsp(mPlayerStatusChangeNT);
                        }
                        else {
                            SendCurrentPlayerValueRspNative(numAttr, data);
                        }
                    break;
                    case GET_ATTRIBUTE_TEXT:
                        text = intent.getStringArrayExtra(EXTRA_ATTRIBUTE_STRING_ARRAY);
                        sendSettingsTextRspNative(mPlayerSettings.attrIds.length ,
                                                     mPlayerSettings.attrIds, text.length,text);
                        if (DEBUG) Log.v(TAG,"mPlayerSettings.attrIds"
                                        + mPlayerSettings.attrIds.length);
                    break;
                    case GET_VALUE_TEXT:
                        text = intent.getStringArrayExtra(EXTRA_VALUE_STRING_ARRAY);
                        sendValueTextRspNative(mPlayerSettings.attrIds.length ,
                                               mPlayerSettings.attrIds, text.length , text);
                    break;
                }
            }

        }
    };

    /* This method is used for create entries of existing media players on RCD start
       * Later when media players become avaialable corresponding entries
       * are marked accordingly and similarly when media players changes focus
       * the corresponding fields are modified */
    private void registerMediaPlayers () {
        if (DEBUG) Log.v(TAG, "registerMediaPlayers");
        int[] featureMasks = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        byte[] playerName1 = {0x4d, 0x75, 0x73, 0x69, 0x63}/*Music*/;
        byte[] playerName2 = {0x4d, 0x75, 0x73, 0x69, 0x63, 0x32}/*Music2*/;

        featureMasks[FEATURE_MASK_PLAY_OFFSET] = featureMasks[FEATURE_MASK_PLAY_OFFSET] | FEATURE_MASK_PLAY_MASK;
        featureMasks[FEATURE_MASK_PAUSE_OFFSET] = featureMasks[FEATURE_MASK_PAUSE_OFFSET] | FEATURE_MASK_PAUSE_MASK;
        featureMasks[FEATURE_MASK_STOP_OFFSET] = featureMasks[FEATURE_MASK_STOP_OFFSET] | FEATURE_MASK_STOP_MASK;
        featureMasks[FEATURE_MASK_PAGE_UP_OFFSET] = featureMasks[FEATURE_MASK_PAGE_UP_OFFSET] | FEATURE_MASK_PAGE_UP_MASK;
        featureMasks[FEATURE_MASK_PAGE_DOWN_OFFSET] = featureMasks[FEATURE_MASK_PAGE_DOWN_OFFSET] | FEATURE_MASK_PAGE_DOWN_MASK;
        featureMasks[FEATURE_MASK_REWIND_OFFSET] = featureMasks[FEATURE_MASK_REWIND_OFFSET] | FEATURE_MASK_REWIND_MASK;
        featureMasks[FEATURE_MASK_FAST_FWD_OFFSET] = featureMasks[FEATURE_MASK_FAST_FWD_OFFSET] | FEATURE_MASK_FAST_FWD_MASK;
        featureMasks[FEATURE_MASK_VENDOR_OFFSET] = featureMasks[FEATURE_MASK_VENDOR_OFFSET] | FEATURE_MASK_VENDOR_MASK;
        featureMasks[FEATURE_MASK_ADV_CTRL_OFFSET] = featureMasks[FEATURE_MASK_ADV_CTRL_OFFSET] | FEATURE_MASK_ADV_CTRL_MASK;

        mediaPlayerInfo1 = new MediaPlayerInfo ((short)0x0001,
                    MAJOR_TYPE_AUDIO,
                    SUB_TYPE_NONE,
                    (byte)RemoteControlClient.PLAYSTATE_PAUSED,
                    CHAR_SET_UTF8,
                    (short)0x05,
                    playerName1,
                    "com.android.music",
                    featureMasks);

        mediaPlayerInfo2 = new MediaPlayerInfo ((short)0x0002,
                    MAJOR_TYPE_AUDIO,
                    SUB_TYPE_NONE,
                    (byte)RemoteControlClient.PLAYSTATE_PAUSED,
                    CHAR_SET_UTF8,
                    (short)0x06,
                    playerName2,
                    "com.google.android.music",
                    featureMasks);

        mMediaPlayers.add(mediaPlayerInfo1);
        mMediaPlayers.add(mediaPlayerInfo2);
    }

    static Avrcp make(Context context) {
        if (DEBUG) Log.v(TAG, "make");
        Avrcp ar = new Avrcp(context);
        ar.start();
        return ar;
    }

    public void doQuit() {
        if (DEBUG) Log.v(TAG, "doQuit");
        mHandler.removeCallbacksAndMessages(null);
        Looper looper = mHandler.getLooper();
        if (looper != null) {
            looper.quit();
        }
        mAudioManager.unregisterRemoteControlDisplay(mRemoteControlDisplay);
        try {
            mContext.unregisterReceiver(mIntentReceiver);
        }catch (Exception e) {
            Log.e(TAG,"Unable to unregister Avrcp receiver", e);
        }
        mMediaPlayers.clear();
        if (mHandler.hasMessages(MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT)) {
            mHandler.removeMessages(MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT);
            mRequestedAddressedPlayerPackageName = null;
            if (DEBUG) Log.v(TAG, "Addressed player message cleanup as part of doQuit");
        }
        keyPressState = KEY_STATE_RELEASE; //Key release state
    }

    public void cleanup() {
        if (DEBUG) Log.v(TAG, "cleanup");
        cleanupNative();
    }

    private static class IRemoteControlDisplayWeak extends IRemoteControlDisplay.Stub {
        private WeakReference<Handler> mLocalHandler;
        IRemoteControlDisplayWeak(Handler handler) {
            mLocalHandler = new WeakReference<Handler>(handler);
        }

        @Override
        public void setPlaybackState(int generationId, int state, long stateChangeTimeMs,
                long currentPosMs, float speed) {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_UPDATE_STATE, generationId, state,
                                      new Long(currentPosMs)).sendToTarget();
            }
        }

        @Override
        public void setMetadata(int generationId, Bundle metadata) {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_SET_METADATA, generationId, 0, metadata).sendToTarget();
            }
        }

        @Override
        public void setTransportControlInfo(int generationId, int flags, int posCapabilities) {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_SET_TRANSPORT_CONTROLS, generationId, flags)
                        .sendToTarget();
            }
        }

        @Override
        public void setArtwork(int generationId, Bitmap bitmap) {
        }

        @Override
        public void setAllMetadata(int generationId, Bundle metadata, Bitmap bitmap) {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_SET_METADATA, generationId, 0, metadata).sendToTarget();
                handler.obtainMessage(MSG_SET_ARTWORK, generationId, 0, bitmap).sendToTarget();
            }
        }

        @Override
        public void setCurrentClientId(int clientGeneration, PendingIntent mediaIntent,
                boolean clearing) throws RemoteException {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_SET_GENERATION_ID,
                    clientGeneration, (clearing ? 1 : 0), mediaIntent).sendToTarget();
            }
        }

        @Override
        public void setEnabled(boolean enabled) {
            // no-op: this RemoteControlDisplay is not subject to being disabled.
        }
    }

    /** Handles Avrcp messages. */
    private final class AvrcpMessageHandler extends Handler {
        private AvrcpMessageHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                    case MESSAGE_PLAYERSETTINGS_TIMEOUT:
                    if (DEBUG) Log.v(TAG, "**MESSAGE_PLAYSTATUS_TIMEOUT");
                    synchronized (mPendingCmds) {
                    Integer val = new Integer(msg.arg1);
                    if (!mPendingCmds.contains(val)) {
                        break;
                    }
                    mPendingCmds.remove(val);
                }
                switch (msg.arg1) {
                    case GET_ATTRIBUTE_IDS:
                        getListPlayerappAttrRspNative((byte)def_attrib.length, def_attrib);
                    break;
                    case GET_VALUE_IDS:
                        if (DEBUG) Log.v(TAG, "GET_VALUE_IDS");
                        switch (mPlayerSettings.attr) {
                            case ATTRIBUTE_REPEATMODE:
                                getPlayerAppValueRspNative((byte)value_repmode.length, value_repmode);
                            break;
                            case ATTRIBUTE_SHUFFLEMODE:
                                getPlayerAppValueRspNative((byte)value_shufmode.length, value_shufmode);
                            break;
                            default:
                                getPlayerAppValueRspNative((byte)value_default.length, value_default);
                            break;
                        }
                    break;
                    case GET_ATTRIBUTE_VALUES:
                        int j = 0;
                        byte [] retVal = new byte [mPlayerSettings.attrIds.length*2];
                        for (int i = 0; i < mPlayerSettings.attrIds.length; i++) {
                            retVal[j++] = mPlayerSettings.attrIds[i];
                            if (mPlayerSettings.attrIds[i] == ATTRIBUTE_REPEATMODE) {
                                retVal[j++] = settingValues.repeat_value;
                            } else if (mPlayerSettings.attrIds[i] == ATTRIBUTE_SHUFFLEMODE) {
                                retVal[j++] = settingValues.shuffle_value;
                             } else {
                                retVal[j++] = 0x0;
                             }
                        }
                        SendCurrentPlayerValueRspNative((byte)retVal.length, retVal);
                    break;
                    case SET_ATTRIBUTE_VALUES :
                        SendSetPlayerAppRspNative();
                    break;
                    case GET_ATTRIBUTE_TEXT:
                    case GET_VALUE_TEXT:
                        String [] values = new String [mPlayerSettings.attrIds.length];
                        String msgVal = (msg.what == GET_ATTRIBUTE_TEXT) ? UPDATE_ATTRIB_TEXT :
                                                                                 UPDATE_VALUE_TEXT;
                        for (int i = 0; i < mPlayerSettings.attrIds.length; i++) {
                            values[i] = "";
                        }
                        sendSettingsTextRspNative(mPlayerSettings.attrIds.length ,
                                                    mPlayerSettings.attrIds, values.length,values);
                    break;
                    default :
                    break;
                }
                break;
            case MSG_UPDATE_STATE:
                if (mClientGeneration == msg.arg1) {
                    updatePlayPauseState(msg.arg2, ((Long)msg.obj).longValue());
                }
                break;

            case MSG_SET_METADATA:
                if (mClientGeneration == msg.arg1) updateMetadata((Bundle) msg.obj);
                break;

            case MSG_UPDATE_AVAILABLE_PLAYERS:
                updateAvailableMediaPlayers();
                break;

            case MSG_UPDATE_ADDRESSED_PLAYER:
                updateAddressedMediaPlayer(msg.arg1);
                break;

            case MSG_SET_TRANSPORT_CONTROLS:
                if (mClientGeneration == msg.arg1) updateTransportControls(msg.arg2);
                break;

            case MSG_SET_ARTWORK:
                if (mClientGeneration == msg.arg1) {
                }
                break;

            case MSG_SET_GENERATION_ID:
                if (DEBUG) Log.v(TAG, "New genId = " + msg.arg1 + ", clearing = " + msg.arg2);
                mClientGeneration = msg.arg1;
                break;

            case MESSAGE_GET_RC_FEATURES:
                String address = (String) msg.obj;
                if (DEBUG) Log.v(TAG, "MESSAGE_GET_RC_FEATURES: address="+address+
                                                             ", features="+msg.arg1);
                mFeatures = msg.arg1;
                mAudioManager.avrcpSupportsAbsoluteVolume(address, isAbsoluteVolumeSupported());
                break;

            case MESSAGE_GET_PLAY_STATUS:
                if (DEBUG) Log.v(TAG, "MESSAGE_GET_PLAY_STATUS");
                getPlayStatusRspNative(convertPlayStateToPlayStatus(mCurrentPlayState),
                                       (int)mSongLengthMs, (int)getPlayPosition());
                break;

            case MESSAGE_GET_ELEM_ATTRS:
            {
                String[] textArray;
                int[] attrIds;
                byte numAttr = (byte) msg.arg1;
                ArrayList<Integer> attrList = (ArrayList<Integer>) msg.obj;
                if (DEBUG) Log.v(TAG, "MESSAGE_GET_ELEM_ATTRS:numAttr=" + numAttr);
                attrIds = new int[numAttr];
                textArray = new String[numAttr];
                for (int i = 0; i < numAttr; ++i) {
                    attrIds[i] = attrList.get(i).intValue();
                    textArray[i] = getAttributeString(attrIds[i]);
                }
                getElementAttrRspNative(numAttr, attrIds, textArray);
                break;
            }
            case MESSAGE_REGISTER_NOTIFICATION:
                if (DEBUG) Log.v(TAG, "MESSAGE_REGISTER_NOTIFICATION:event=" + msg.arg1 +
                                      " param=" + msg.arg2);
                processRegisterNotification(msg.arg1, msg.arg2);
                break;

            case MESSAGE_PLAY_INTERVAL_TIMEOUT:
                if (DEBUG) Log.v(TAG, "MESSAGE_PLAY_INTERVAL_TIMEOUT");
                mPlayPosChangedNT = NOTIFICATION_TYPE_CHANGED;
                registerNotificationRspPlayPosNative(mPlayPosChangedNT, (int)getPlayPosition());
                break;

            case MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT:
                if (DEBUG) Log.v(TAG, "setAddressedPlayer fails, Times out");
                setAdressedPlayerRspNative ((byte)ERR_ADDR_PLAYER_FAILS);
                mRequestedAddressedPlayerPackageName = null;
                break;

            case MESSAGE_VOLUME_CHANGED:
                if (DEBUG) Log.v(TAG, "MESSAGE_VOLUME_CHANGED: volume=" + msg.arg1 +
                                                              " ctype=" + msg.arg2);

                if (msg.arg2 == AVRC_RSP_ACCEPT || msg.arg2 == AVRC_RSP_REJ) {
                    if (mVolCmdInProgress == false) {
                        Log.e(TAG, "Unsolicited response, ignored");
                        break;
                    }
                    removeMessages(MESSAGE_ABS_VOL_TIMEOUT);
                    mVolCmdInProgress = false;
                    mAbsVolRetryTimes = 0;
                }
                if (mAbsoluteVolume != msg.arg1 && (msg.arg2 == AVRC_RSP_ACCEPT ||
                                                    msg.arg2 == AVRC_RSP_CHANGED ||
                                                    msg.arg2 == AVRC_RSP_INTERIM)) {
                    notifyVolumeChanged(msg.arg1);
                    mAbsoluteVolume = msg.arg1;
                } else if (msg.arg2 == AVRC_RSP_REJ) {
                    Log.e(TAG, "setAbsoluteVolume call rejected");
                }
                break;

            case MESSAGE_ADJUST_VOLUME:
                if (DEBUG) Log.d(TAG, "MESSAGE_ADJUST_VOLUME: direction=" + msg.arg1);
                if (mVolCmdInProgress) {
                    if (DEBUG) Log.w(TAG, "There is already a volume command in progress.");
                    break;
                }
                // Wait on verification on volume from device, before changing the volume.
                if (mAbsoluteVolume != -1 && (msg.arg1 == -1 || msg.arg1 == 1)) {
                    int setVol = Math.min(AVRCP_MAX_VOL,
                                 Math.max(0, mAbsoluteVolume + msg.arg1*mVolumeStep));
                    if (setVolumeNative(setVol)) {
                        sendMessageDelayed(obtainMessage(MESSAGE_ABS_VOL_TIMEOUT),
                                           CMD_TIMEOUT_DELAY);
                        mVolCmdInProgress = true;
                        mLastDirection = msg.arg1;
                        mLastSetVolume = setVol;
                    }
                } else {
                    Log.e(TAG, "Unknown direction in MESSAGE_ADJUST_VOLUME");
                }
                break;

            case MESSAGE_SET_ABSOLUTE_VOLUME:
                if (DEBUG) Log.v(TAG, "MESSAGE_SET_ABSOLUTE_VOLUME");
                if (mVolCmdInProgress) {
                    if (DEBUG) Log.w(TAG, "There is already a volume command in progress.");
                    break;
                }
                if (setVolumeNative(msg.arg1)) {
                    sendMessageDelayed(obtainMessage(MESSAGE_ABS_VOL_TIMEOUT), CMD_TIMEOUT_DELAY);
                    mVolCmdInProgress = true;
                    mLastSetVolume = msg.arg1;
                }
                break;

            case MESSAGE_ABS_VOL_TIMEOUT:
                if (DEBUG) Log.v(TAG, "MESSAGE_ABS_VOL_TIMEOUT: Volume change cmd timed out.");
                mVolCmdInProgress = false;
                if (mAbsVolRetryTimes >= MAX_ERROR_RETRY_TIMES) {
                    mAbsVolRetryTimes = 0;
                } else {
                    mAbsVolRetryTimes += 1;
                    if (setVolumeNative(mLastSetVolume)) {
                        sendMessageDelayed(obtainMessage(MESSAGE_ABS_VOL_TIMEOUT),
                                           CMD_TIMEOUT_DELAY);
                        mVolCmdInProgress = true;
                    }
                }
                break;

            case MESSAGE_FAST_FORWARD:
            case MESSAGE_REWIND:
                int skipAmount;
                if (msg.what == MESSAGE_FAST_FORWARD) {
                    if (DEBUG) Log.v(TAG, "MESSAGE_FAST_FORWARD");
                    skipAmount = BASE_SKIP_AMOUNT;
                } else {
                    if (DEBUG) Log.v(TAG, "MESSAGE_REWIND");
                    skipAmount = -BASE_SKIP_AMOUNT;
                }

                if (hasMessages(MESSAGE_CHANGE_PLAY_POS) &&
                        (skipAmount != mSkipAmount)) {
                    Log.w(TAG, "missing release button event:" + mSkipAmount);
                }

                if ((!hasMessages(MESSAGE_CHANGE_PLAY_POS)) ||
                        (skipAmount != mSkipAmount)) {
                    mSkipStartTime = SystemClock.elapsedRealtime();
                }

                removeMessages(MESSAGE_CHANGE_PLAY_POS);
                if (msg.arg1 == KEY_STATE_PRESS) {
                    mSkipAmount = skipAmount;
                    changePositionBy(mSkipAmount * getSkipMultiplier());
                    Message posMsg = obtainMessage(MESSAGE_CHANGE_PLAY_POS);
                    posMsg.arg1 = 1;
                    sendMessageDelayed(posMsg, SKIP_PERIOD);
                }
                break;

            case MESSAGE_CHANGE_PLAY_POS:
                if (DEBUG) Log.v(TAG, "MESSAGE_CHANGE_PLAY_POS:" + msg.arg1);
                changePositionBy(mSkipAmount * getSkipMultiplier());
                if (msg.arg1 * SKIP_PERIOD < BUTTON_TIMEOUT_TIME) {
                    Message posMsg = obtainMessage(MESSAGE_CHANGE_PLAY_POS);
                    posMsg.arg1 = msg.arg1 + 1;
                    sendMessageDelayed(posMsg, SKIP_PERIOD);
                }
                break;
            case MSG_UPDATE_RCC_CHANGE:
                Log.v(TAG, "MSG_UPDATE_RCC_CHANGE");
                String callingPackageName = (String)msg.obj;
                int isFocussed = msg.arg1;
                int isAvailable = msg.arg2;
                processRCCStateChange(callingPackageName, isFocussed, isAvailable);
                break;

            case MESSAGE_SET_A2DP_AUDIO_STATE:
                if (DEBUG) Log.v(TAG, "MESSAGE_SET_A2DP_AUDIO_STATE:" + msg.arg1);
                updateA2dpAudioState(msg.arg1);
                break;
            }
        }
    }

    private void updateA2dpAudioState(int state) {
        boolean isPlaying = (state == BluetoothA2dp.STATE_PLAYING);
        if (isPlaying != isPlayingState(mCurrentPlayState)) {
            updatePlayPauseState(isPlaying ? RemoteControlClient.PLAYSTATE_PLAYING :
                                 RemoteControlClient.PLAYSTATE_PAUSED,
                                 RemoteControlClient.PLAYBACK_POSITION_INVALID);
        }
    }

    private void updatePlayPauseState(int state, long currentPosMs) {
        if (DEBUG) Log.v(TAG,"updatePlayPauseState");
        boolean oldPosValid = (mCurrentPosMs !=
                               RemoteControlClient.PLAYBACK_POSITION_ALWAYS_UNKNOWN);
        if (state == RemoteControlClient.PLAYSTATE_PLAYING) { // may be change in player
            if (mMediaPlayers.size() > 0) {
                final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
                while (rccIterator.hasNext()) {
                    final MediaPlayerInfo di = rccIterator.next();
                    if (di.GetPlayerFocus()) { // may be change in player, update with player specific state
                        if (DEBUG) Log.v(TAG, "reset " + di.getPlayerPackageName() + " playbackState as: " + di.GetPlayState());
                        mCurrentPlayState = di.GetPlayState();
                        break;
                    }
                }
            }
        }
        if (DEBUG) Log.v(TAG, "old state = " + mCurrentPlayState + ", new state= " + state);
        int oldPlayStatus = convertPlayStateToPlayStatus(mCurrentPlayState);
        int newPlayStatus = convertPlayStateToPlayStatus(state);

        if ((mCurrentPlayState == RemoteControlClient.PLAYSTATE_PLAYING) &&
            (mCurrentPlayState != state) && oldPosValid) {
            mCurrentPosMs = getPlayPosition();
        }

        mCurrentPlayState = state;

        if (mMediaPlayers.size() > 0) {
            final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
            while (rccIterator.hasNext()) {
                final MediaPlayerInfo di = rccIterator.next();
                if (state == RemoteControlClient.PLAYSTATE_PLAYING) {
                    if (di.GetPlayerFocus()) { // may be change in player, update player specific variables
                        if (DEBUG) Log.v(TAG, "update " + di.getPlayerPackageName() + " playbackState as: " + mCurrentPlayState);
                        di.SetPlayState((byte)mCurrentPlayState);
                    } else { // reset the other players state as paused (default state)
                        if (DEBUG) Log.v(TAG, "update " + di.getPlayerPackageName() + " playbackState as: Paused");
                        di.SetPlayState((byte)RemoteControlClient.PLAYSTATE_PAUSED);
                    }
                } else {
                    if (di.GetPlayerFocus()) {
                        if (DEBUG) Log.v(TAG, "update " + di.getPlayerPackageName() + " playbackState as: " + mCurrentPlayState);
                        di.SetPlayState((byte)mCurrentPlayState);
                        break;
                    }
                }
            }
        }

        if (currentPosMs != RemoteControlClient.PLAYBACK_POSITION_INVALID) {
            mCurrentPosMs = currentPosMs;
        }
        if (state == RemoteControlClient.PLAYSTATE_PLAYING) {
            mPlayStartTimeMs = SystemClock.elapsedRealtime();
        }

        boolean newPosValid = (mCurrentPosMs !=
                               RemoteControlClient.PLAYBACK_POSITION_ALWAYS_UNKNOWN);
        long playPosition = getPlayPosition();
        mHandler.removeMessages(MESSAGE_PLAY_INTERVAL_TIMEOUT);
        /* need send play position changed notification when play status is changed */
        if ((mPlayPosChangedNT == NOTIFICATION_TYPE_INTERIM) &&
            ((oldPlayStatus != newPlayStatus) || (oldPosValid != newPosValid) ||
             (newPosValid && ((playPosition >= mNextPosMs) || (playPosition <= mPrevPosMs))))) {
            mPlayPosChangedNT = NOTIFICATION_TYPE_CHANGED;
            registerNotificationRspPlayPosNative(mPlayPosChangedNT, (int)playPosition);
        }
        if ((mPlayPosChangedNT == NOTIFICATION_TYPE_INTERIM) && newPosValid &&
            (state == RemoteControlClient.PLAYSTATE_PLAYING)) {
            Message msg = mHandler.obtainMessage(MESSAGE_PLAY_INTERVAL_TIMEOUT);
            mHandler.sendMessageDelayed(msg, mNextPosMs - playPosition);
        }

        if ((mPlayStatusChangedNT == NOTIFICATION_TYPE_INTERIM) && (oldPlayStatus != newPlayStatus)) {
            mPlayStatusChangedNT = NOTIFICATION_TYPE_CHANGED;
            registerNotificationRspPlayStatusNative(mPlayStatusChangedNT, newPlayStatus);
        }
    }

    private void updateTransportControls(int transportControlFlags) {
        mTransportControlFlags = transportControlFlags;
    }

    private void updateAvailableMediaPlayers() {
        if (DEBUG) Log.v(TAG, "updateAvailableMediaPlayers");
        if (mAvailablePlayersChangedNT == NOTIFICATION_TYPE_INTERIM) {
            mAvailablePlayersChangedNT = NOTIFICATION_TYPE_CHANGED;
            if (DEBUG) Log.v(TAG, "send AvailableMediaPlayers to stack");
            registerNotificationRspAvailablePlayersChangedNative(mAvailablePlayersChangedNT);
        }
    }
    private void updateAddressedMediaPlayer(int playerId) {
        if (DEBUG) Log.v(TAG, "updateAddressedMediaPlayer");
        int previousAddressedPlayerId = mAddressedPlayerId;
        if ((mAddressedPlayerChangedNT == NOTIFICATION_TYPE_INTERIM) && (mAddressedPlayerId != playerId)) {
            if (DEBUG) Log.v(TAG, "send AddressedMediaPlayer to stack: playerId" + playerId);
            mAddressedPlayerId = playerId;
            mAddressedPlayerChangedNT = NOTIFICATION_TYPE_CHANGED;
            registerNotificationRspAddressedPlayerChangedNative(mAddressedPlayerChangedNT, mAddressedPlayerId);
            if (previousAddressedPlayerId != 0) {
                resetAndSendPlayerStatusReject();
            }
        } else {
            mAddressedPlayerId = playerId;
        }
    }

    private void resetAndSendPlayerStatusReject() {
        if (DEBUG) Log.v(TAG, "resetAndSendPlayerStatusReject");

        if (mPlayStatusChangedNT == NOTIFICATION_TYPE_INTERIM) {
            if (DEBUG) Log.v(TAG, "send Play Status reject to stack");
            mPlayStatusChangedNT = NOTIFICATION_TYPE_REJECT;
            registerNotificationRspPlayStatusNative(mPlayStatusChangedNT, PLAYSTATUS_STOPPED);
        }
        if (mPlayPosChangedNT == NOTIFICATION_TYPE_INTERIM) {
            if (DEBUG) Log.v(TAG, "send Play Position reject to stack");
            mPlayPosChangedNT = NOTIFICATION_TYPE_REJECT;
            registerNotificationRspPlayPosNative(mPlayPosChangedNT, -1);
            mHandler.removeMessages(MESSAGE_PLAY_INTERVAL_TIMEOUT);
        }
        if (mTrackChangedNT == NOTIFICATION_TYPE_INTERIM) {
            if (DEBUG) Log.v(TAG, "send Track Changed reject to stack");
            mTrackChangedNT = NOTIFICATION_TYPE_REJECT;
            byte[] track = new byte[TRACK_ID_SIZE];
            /* track is stored in big endian format */
            for (int i = 0; i < TRACK_ID_SIZE; ++i) {
                track[i] = (byte) (mTrackNumber >> (56 - 8 * i));
            }
            registerNotificationRspTrackChangeNative(mTrackChangedNT, track);
        }
    }

    class Metadata {
        private String artist;
        private String trackTitle;
        private String albumTitle;
        private String genre;
        private long tracknum;

        public Metadata() {
            artist = null;
            trackTitle = null;
            albumTitle = null;
            genre = null;
            tracknum = 0;
        }

        public String toString() {
            return "Metadata[artist=" + artist + " trackTitle=" + trackTitle + " albumTitle=" +
                   albumTitle + " genre=" + genre + " tracknum=" + Long.toString(tracknum) + "]";
        }
    }

    private String getMdString(Bundle data, int id) {
        return data.getString(Integer.toString(id));
    }

    private long getMdLong(Bundle data, int id) {
        return data.getLong(Integer.toString(id));
    }

    private void updateTrackNumber() {
        if (DEBUG) Log.v(TAG, "updateTrackNumber");
        if (mMediaPlayers.size() > 0) {
            final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
            while (rccIterator.hasNext()) {
                final MediaPlayerInfo di = rccIterator.next();
                if (di.GetPlayerFocus()) {
                    di.SetTrackNumber(mTrackNumber);
                    break;
                }
            }
        }
    }
    private void updateMetadata(Bundle data) {
        if (DEBUG) Log.v(TAG, "updateMetadata");
        if (mMediaPlayers.size() > 0) {
            final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
            while (rccIterator.hasNext()) {
                final MediaPlayerInfo di = rccIterator.next();
                if (di.GetPlayerFocus()) {
                    if (DEBUG) Log.v(TAG, "resetting current MetaData");
                    mMetadata = di.GetMetadata();
                    break;
                }
            }
        }
        String oldMetadata = mMetadata.toString();
        mMetadata.artist = getMdString(data, MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
        mMetadata.trackTitle = getMdString(data, MediaMetadataRetriever.METADATA_KEY_TITLE);
        mMetadata.albumTitle = getMdString(data, MediaMetadataRetriever.METADATA_KEY_ALBUM);
        mMetadata.genre = getMdString(data, MediaMetadataRetriever.METADATA_KEY_GENRE);
        mTrackNumber = getMdLong(data, MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS);
        mMetadata.tracknum = getMdLong(data, MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER);

        Log.v(TAG,"mMetadata.toString() = " + mMetadata.toString());

        if (mMediaPlayers.size() > 0) {
            final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
            while (rccIterator.hasNext()) {
                final MediaPlayerInfo di = rccIterator.next();
                if (di.GetPlayerFocus()) {
                    if (DEBUG) Log.v(TAG, "updating List MetaData");
                    di.SetMetadata(mMetadata);
                    break;
                }
            }
        }
        if (!oldMetadata.equals(mMetadata.toString())) {
            updateTrackNumber();
            Log.v(TAG,"new mMetadata, mTrackNumber update to " + mTrackNumber);

            if (mTrackChangedNT == NOTIFICATION_TYPE_INTERIM) {
                mTrackChangedNT = NOTIFICATION_TYPE_CHANGED;
                sendTrackChangedRsp();
            }

            if (mCurrentPosMs != RemoteControlClient.PLAYBACK_POSITION_ALWAYS_UNKNOWN) {
                mCurrentPosMs = 0L;
                if (mCurrentPlayState == RemoteControlClient.PLAYSTATE_PLAYING) {
                    mPlayStartTimeMs = SystemClock.elapsedRealtime();
                }
            }
            /* need send play position changed notification when track is changed */
            if (mPlayPosChangedNT == NOTIFICATION_TYPE_INTERIM) {
                mPlayPosChangedNT = NOTIFICATION_TYPE_CHANGED;
                registerNotificationRspPlayPosNative(mPlayPosChangedNT,
                                                     (int)getPlayPosition());
                mHandler.removeMessages(MESSAGE_PLAY_INTERVAL_TIMEOUT);
            }
        }
        if (DEBUG) Log.v(TAG, "mMetadata=" + mMetadata.toString());

        mSongLengthMs = getMdLong(data, MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (DEBUG) Log.v(TAG, "duration=" + mSongLengthMs);
    }

    private void getRcFeatures(byte[] address, int features) {
        Message msg = mHandler.obtainMessage(MESSAGE_GET_RC_FEATURES, features, 0,
                                             Utils.getAddressStringFromByte(address));
        mHandler.sendMessage(msg);
    }

    private void getPlayStatus() {
        Message msg = mHandler.obtainMessage(MESSAGE_GET_PLAY_STATUS);
        mHandler.sendMessage(msg);
    }

    private void getElementAttr(byte numAttr, int[] attrs) {
        int i;
        ArrayList<Integer> attrList = new ArrayList<Integer>();
        for (i = 0; i < numAttr; ++i) {
            attrList.add(attrs[i]);
        }
        Message msg = mHandler.obtainMessage(MESSAGE_GET_ELEM_ATTRS, (int)numAttr, 0, attrList);
        mHandler.sendMessage(msg);
    }

    private void setAddressedPlayer(int playerId) {
        if (DEBUG) Log.v(TAG, "setAddressedPlayer");
        String packageName = null;
        if (mRequestedAddressedPlayerPackageName != null) {
            if (DEBUG) Log.v(TAG, "setAddressedPlayer: Request in progress, Reject this Request");
            setAdressedPlayerRspNative ((byte)ERR_ADDR_PLAYER_FAILS);
            return;
        }
        if (mMediaPlayers.size() > 0) {
            final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
            while (rccIterator.hasNext()) {
                final MediaPlayerInfo di = rccIterator.next();
                if (di.RetrievePlayerId() == playerId) {
                    packageName = di.RetrievePlayerPackageName();
                }
            }
        }
        if(packageName != null) {
            if (playerId == mAddressedPlayerId) {
                if (DEBUG) Log.v(TAG, "setAddressedPlayer: Already addressed, sending success");
                setAdressedPlayerRspNative ((byte)ERR_ADDR_PLAYER_SUCCEEDS);
                return;
            }
            String newPackageName = packageName.replace("com.android", "org.codeaurora");
            Intent mediaIntent = new Intent(newPackageName + ".setaddressedplayer");
            mediaIntent.setPackage(packageName);
            mContext.sendBroadcast(mediaIntent); // This needs to be caught in respective media players
            if (DEBUG) Log.v(TAG, "Intent Broadcasted: " + newPackageName + ".setaddressedplayer");
            mRequestedAddressedPlayerPackageName = packageName;
            Message msg = mHandler.obtainMessage(MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT);
            mHandler.sendMessageDelayed(msg, SET_ADDR_PLAYER_TIMEOUT);
            Log.v(TAG, "Post MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT");
        } else {
            if (DEBUG) Log.v(TAG, "setAddressedPlayer fails: No such media player available");
            setAdressedPlayerRspNative ((byte)ERR_INVALID_PLAYER_ID);
        }
    }
    private void getFolderItems(byte scope, int start, int end, int attrCnt) {
        if (DEBUG) Log.v(TAG, "getFolderItems");
        if (scope == 0x00) { // populate mediaplayer item list here
            byte[] folderItems = new byte[attrCnt]; // this value needs to be configured as per the Max pckt size received in request frame from stack
            int[] folderItemLengths = new int[32]; // need to check if we can configure this dynamically
            int availableMediaPlayers = 0;
            int count = 0;
            int positionItemStart = 0;
            if (mMediaPlayers.size() > 0) {
                final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
                while (rccIterator.hasNext()) {
                    final MediaPlayerInfo di = rccIterator.next();
                    if (di.GetPlayerAvailablility()) {
                        byte[] playerEntry = di.RetrievePlayerItemEntry();
                        int length = di.RetrievePlayerEntryLength();
                        folderItemLengths[availableMediaPlayers ++] = length;
                        for (count = 0; count < length; count ++) {
                            folderItems[positionItemStart + count] = playerEntry[count];
                        }
                        positionItemStart += length; // move start to next item start
                    }
                }
            }
            if (DEBUG) Log.v(TAG, "Number of available MediaPlayers = " + availableMediaPlayers);
            getFolderItemsRspNative ((byte)0x04, 0x1357, availableMediaPlayers, folderItems, folderItemLengths);
        }
    }
    private void registerNotification(int eventId, int param) {
        Message msg = mHandler.obtainMessage(MESSAGE_REGISTER_NOTIFICATION, eventId, param);
        mHandler.sendMessage(msg);
    }
    private void processRCCStateChange(String callingPackageName, int isFocussed, int isAvailable) {
        if (DEBUG) Log.v(TAG, "processRCCStateChange");
        boolean available = false;
        boolean focussed = false;
        boolean isResetFocusRequired = false;

        if (isFocussed == 1)
            focussed = true;
        if (isAvailable == 1)
            available = true;

        if (focussed) {
            isResetFocusRequired = true; // need to reset other player's focus.
            if (mRequestedAddressedPlayerPackageName != null) {
                if (callingPackageName.equals(mRequestedAddressedPlayerPackageName)) {
                    mHandler.removeMessages(MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT);
                    if (DEBUG) Log.v(TAG, "SetAddressedPlayer succeeds for: "
                                                + mRequestedAddressedPlayerPackageName);
                    mRequestedAddressedPlayerPackageName = null;
                    setAdressedPlayerRspNative ((byte)ERR_ADDR_PLAYER_SUCCEEDS);
                } else {
                    if (DEBUG) Log.v(TAG, "SetaddressedPlayer package mismatch with: "
                                                + mRequestedAddressedPlayerPackageName);
                }
            } else {
                if (DEBUG) Log.v(TAG, "SetaddressedPlayer request is not in progress");
            }
        }

        if (mMediaPlayers.size() > 0) {
            final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
            while (rccIterator.hasNext()) {
                final MediaPlayerInfo di = rccIterator.next();
                if (di.RetrievePlayerPackageName().equals(callingPackageName)) {
                    if (di.GetPlayerAvailablility() != available) {
                        di.SetPlayerAvailablility(available);
                        if (DEBUG) Log.v(TAG, "setting " + callingPackageName + " availability: " + available);
                        if (mHandler != null) {
                            if (DEBUG) Log.v(TAG, "Send MSG_UPDATE_AVAILABLE_PLAYERS");
                            mHandler.obtainMessage(MSG_UPDATE_AVAILABLE_PLAYERS, 0, 0, 0).sendToTarget();
                        }
                    }
                    if (di.GetPlayerFocus() != focussed) {
                        di.SetPlayerFocus(focussed);
                        if (DEBUG) Log.v(TAG, "setting " + callingPackageName + " focus: " + focussed);
                        if(isResetFocusRequired) { // this ensures we got this message for fous on.
                            if (mHandler != null) {
                                if (DEBUG) Log.v(TAG, "Send MSG_UPDATE_ADDRESSED_PLAYER");
                                mHandler.obtainMessage(MSG_UPDATE_ADDRESSED_PLAYER, di.RetrievePlayerId(), 0, 0).sendToTarget();
                            }
                        }
                    }
                    break;
                }
            }
        }

        if (DEBUG) Log.v(TAG, "isResetFocusRequired: " + isResetFocusRequired);

        if (isResetFocusRequired) {
            if (mMediaPlayers.size() > 1) { // this is applicable only if list contains more than one media players
                final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
                while (rccIterator.hasNext()) {
                    final MediaPlayerInfo di = rccIterator.next();
                    if (!(di.RetrievePlayerPackageName().equals(callingPackageName))) {
                        if (DEBUG) Log.v(TAG, "setting " + callingPackageName + " focus: false");
                        di.SetPlayerFocus(false); // reset focus for all other players
                    }
                }
            }
        }
    }
    private void processRegisterNotification(int eventId, int param) {
        switch (eventId) {
            case EVT_PLAY_STATUS_CHANGED:
                mPlayStatusChangedNT = NOTIFICATION_TYPE_INTERIM;
                registerNotificationRspPlayStatusNative(mPlayStatusChangedNT,
                                       convertPlayStateToPlayStatus(mCurrentPlayState));
                break;

            case EVT_TRACK_CHANGED:
                mTrackChangedNT = NOTIFICATION_TYPE_INTERIM;
                sendTrackChangedRsp();
                break;

            case EVT_PLAY_POS_CHANGED:
                long songPosition = getPlayPosition();
                mPlayPosChangedNT = NOTIFICATION_TYPE_INTERIM;
                mPlaybackIntervalMs = (long)param * 1000L;
                if (mCurrentPosMs != RemoteControlClient.PLAYBACK_POSITION_ALWAYS_UNKNOWN) {
                    mNextPosMs = songPosition + mPlaybackIntervalMs;
                    mPrevPosMs = songPosition - mPlaybackIntervalMs;
                    if (mCurrentPlayState == RemoteControlClient.PLAYSTATE_PLAYING) {
                        Message msg = mHandler.obtainMessage(MESSAGE_PLAY_INTERVAL_TIMEOUT);
                        mHandler.sendMessageDelayed(msg, mPlaybackIntervalMs);
                    }
                }
                registerNotificationRspPlayPosNative(mPlayPosChangedNT, (int)songPosition);
                break;


            case EVT_APP_SETTINGS_CHANGED:
                mPlayerStatusChangeNT = NOTIFICATION_TYPE_INTERIM;
                sendPlayerAppChangedRsp(mPlayerStatusChangeNT);
                break;

            case EVT_ADDRESSED_PLAYER_CHANGED:
                if (DEBUG) Log.v(TAG, "Process EVT_ADDRESSED_PLAYER_CHANGED Interim: Player ID: " + mAddressedPlayerId);
                mAddressedPlayerChangedNT = NOTIFICATION_TYPE_INTERIM;
                registerNotificationRspAddressedPlayerChangedNative(mAddressedPlayerChangedNT, mAddressedPlayerId);
                break;

            case EVT_AVAILABLE_PLAYERS_CHANGED:
                if (DEBUG) Log.v(TAG, "Process EVT_AVAILABLE_PLAYERS_CHANGED Interim");
                mAvailablePlayersChangedNT = NOTIFICATION_TYPE_INTERIM;
                registerNotificationRspAvailablePlayersChangedNative(mAvailablePlayersChangedNT);
                break;

            default:
                Log.v(TAG, "processRegisterNotification: Unhandled Type: " + eventId);
                break;
        }
    }

    private void handlePassthroughCmd(int id, int keyState) {
        switch (id) {
            case AVRC_ID_REWIND:
                rewind(keyState);
                break;
            case AVRC_ID_FAST_FOR:
                fastForward(keyState);
                break;
        }
    }

    private void fastForward(int keyState) {
        if ((keyState == keyPressState) && (keyState == KEY_STATE_RELEASE)) {
            Log.e(TAG, "Ignore key release event");
        }
        else {
            Message msg = mHandler.obtainMessage(MESSAGE_FAST_FORWARD, keyState, 0);
            mHandler.sendMessage(msg);
            keyPressState = keyState;
        }
    }

    private void rewind(int keyState) {
        if ((keyState == keyPressState) && (keyState == KEY_STATE_RELEASE)) {
            Log.e(TAG, "Ignore key release event");
        }
        else {
            Message msg = mHandler.obtainMessage(MESSAGE_REWIND, keyState, 0);
            mHandler.sendMessage(msg);
            keyPressState = keyState;
        }
    }

    private void changePositionBy(long amount) {
        long currentPosMs = getPlayPosition();
        if (currentPosMs == -1L) return;
        long newPosMs = Math.max(0L, currentPosMs + amount);
        mAudioManager.setRemoteControlClientPlaybackPosition(mClientGeneration,
                newPosMs);
    }

    private int getSkipMultiplier() {
        long currentTime = SystemClock.elapsedRealtime();
        long multi = (long) Math.pow(2, (currentTime - mSkipStartTime)/SKIP_DOUBLE_INTERVAL);
        return (int) Math.min(MAX_MULTIPLIER_VALUE, multi);
    }

    private void sendTrackChangedRsp() {
        byte[] track = new byte[TRACK_ID_SIZE];
        long TrackNumberRsp = -1L;

        if(DEBUG) Log.v(TAG,"mCurrentPlayState" + mCurrentPlayState );
        /*As per spec 6.7.2 Register Notification
          If no track is currently selected, then return
         0xFFFFFFFFFFFFFFFF in the interim response */
        if (mCurrentPlayState == RemoteControlClient.PLAYSTATE_PLAYING)
            TrackNumberRsp = mMetadata.tracknum ;
        /* track is stored in big endian format */
        for (int i = 0; i < TRACK_ID_SIZE; ++i) {
            track[i] = (byte) (TrackNumberRsp >> (56 - 8 * i));
        }
        registerNotificationRspTrackChangeNative(mTrackChangedNT, track);
    }

    private void sendPlayerAppChangedRsp(int rsptype) {
        int j = 0;
        byte i = NUMPLAYER_ATTRIBUTE*2;
        byte [] retVal = new byte [i];
        retVal[j++] = ATTRIBUTE_REPEATMODE;
        retVal[j++] = settingValues.repeat_value;
        retVal[j++] = ATTRIBUTE_SHUFFLEMODE;
        retVal[j++] = settingValues.shuffle_value;
        registerNotificationPlayerAppRspNative(rsptype, i, retVal);
    }

    private long getPlayPosition() {
        long songPosition = -1L;
        if (mCurrentPosMs != RemoteControlClient.PLAYBACK_POSITION_ALWAYS_UNKNOWN) {
            if (mCurrentPlayState == RemoteControlClient.PLAYSTATE_PLAYING) {
                songPosition = SystemClock.elapsedRealtime() -
                               mPlayStartTimeMs + mCurrentPosMs;
            } else {
                songPosition = mCurrentPosMs;
            }
        }
        if (DEBUG) Log.v(TAG, "position=" + songPosition);
        return songPosition;
    }

    private String getAttributeString(int attrId) {
        String attrStr = null;
        switch (attrId) {
            case MEDIA_ATTR_TITLE:
                attrStr = mMetadata.trackTitle;
                break;

            case MEDIA_ATTR_ARTIST:
                attrStr = mMetadata.artist;
                break;

            case MEDIA_ATTR_ALBUM:
                attrStr = mMetadata.albumTitle;
                break;

            case MEDIA_ATTR_PLAYING_TIME:
                if (mSongLengthMs != 0L) {
                    attrStr = Long.toString(mSongLengthMs);
                }
                break;

            case MEDIA_ATTR_TRACK_NUM:
                attrStr = Long.toString(mMetadata.tracknum);
                break;

            case MEDIA_ATTR_NUM_TRACKS:
                attrStr = Long.toString(mTrackNumber);
                break;

             case MEDIA_ATTR_GENRE:
                attrStr = mMetadata.genre;
                break;
        }
        if (attrStr == null) {
            attrStr = new String();
        }
        if (DEBUG) Log.v(TAG, "getAttributeString:attrId=" + attrId + " str=" + attrStr);
        return attrStr;
    }

    private int convertPlayStateToPlayStatus(int playState) {
        int playStatus = PLAYSTATUS_ERROR;
        switch (playState) {
            case RemoteControlClient.PLAYSTATE_PLAYING:
            case RemoteControlClient.PLAYSTATE_BUFFERING:
                playStatus = PLAYSTATUS_PLAYING;
                break;

            case RemoteControlClient.PLAYSTATE_STOPPED:
            case RemoteControlClient.PLAYSTATE_NONE:
                playStatus = PLAYSTATUS_STOPPED;
                break;

            case RemoteControlClient.PLAYSTATE_PAUSED:
                playStatus = PLAYSTATUS_PAUSED;
                break;

            case RemoteControlClient.PLAYSTATE_FAST_FORWARDING:
            case RemoteControlClient.PLAYSTATE_SKIPPING_FORWARDS:
                playStatus = PLAYSTATUS_FWD_SEEK;
                break;

            case RemoteControlClient.PLAYSTATE_REWINDING:
            case RemoteControlClient.PLAYSTATE_SKIPPING_BACKWARDS:
                playStatus = PLAYSTATUS_REV_SEEK;
                break;

            case RemoteControlClient.PLAYSTATE_ERROR:
                playStatus = PLAYSTATUS_ERROR;
                break;

        }
        return playStatus;
    }

    private boolean isPlayingState(int playState) {
        boolean isPlaying = false;
        switch (playState) {
            case RemoteControlClient.PLAYSTATE_PLAYING:
            case RemoteControlClient.PLAYSTATE_BUFFERING:
                isPlaying = true;
                break;
            default:
                isPlaying = false;
                break;
        }
        return isPlaying;
    }

    /**
     * This is called from AudioService. It will return whether this device supports abs volume.
     * NOT USED AT THE MOMENT.
     */
    public boolean isAbsoluteVolumeSupported() {
        return ((mFeatures & BTRC_FEAT_ABSOLUTE_VOLUME) != 0);
    }

    /**
     * We get this call from AudioService. This will send a message to our handler object,
     * requesting our handler to call setVolumeNative()
     */
    public void adjustVolume(int direction) {
        Message msg = mHandler.obtainMessage(MESSAGE_ADJUST_VOLUME, direction, 0);
        mHandler.sendMessage(msg);
    }

    public void setAbsoluteVolume(int volume) {
        int avrcpVolume = convertToAvrcpVolume(volume);
        avrcpVolume = Math.min(AVRCP_MAX_VOL, Math.max(0, avrcpVolume));
        mHandler.removeMessages(MESSAGE_ADJUST_VOLUME);
        Message msg = mHandler.obtainMessage(MESSAGE_SET_ABSOLUTE_VOLUME, avrcpVolume, 0);
        mHandler.sendMessage(msg);

    }

    /* Called in the native layer as a btrc_callback to return the volume set on the carkit in the
     * case when the volume is change locally on the carkit. This notification is not called when
     * the volume is changed from the phone.
     *
     * This method will send a message to our handler to change the local stored volume and notify
     * AudioService to update the UI
     */
    private void volumeChangeCallback(int volume, int ctype) {
        Message msg = mHandler.obtainMessage(MESSAGE_VOLUME_CHANGED, volume, ctype);
        mHandler.sendMessage(msg);
    }

    private void notifyVolumeChanged(int volume) {
        volume = convertToAudioStreamVolume(volume);
        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume,
                      AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_BLUETOOTH_ABS_VOLUME);
    }

    private int convertToAudioStreamVolume(int volume) {
        // Rescale volume to match AudioSystem's volume
        return (int) Math.round((double) volume*mAudioStreamMax/AVRCP_MAX_VOL);
    }

    private int convertToAvrcpVolume(int volume) {
        return (int) Math.ceil((double) volume*AVRCP_MAX_VOL/mAudioStreamMax);
    }


private void updateLocalPlayerSettings( byte[] data) {
        for (int i = 0; i < data.length; i += 2) {
            switch (data[i]) {
                case ATTRIBUTE_EQUALIZER:
                    settingValues.eq_value = data[i+1];
                break;
                case ATTRIBUTE_REPEATMODE:
                    settingValues.repeat_value = data[i+1];
                break;
                case ATTRIBUTE_SHUFFLEMODE:
                    settingValues.shuffle_value = data[i+1];
                break;
                case ATTRIBUTE_SCANMODE:
                    settingValues.scan_value = data[i+1];
                break;
            }
        }
    }

    //PDU ID 0x11
    private void onListPlayerAttributeRequest() {
        if (DEBUG) Log.v(TAG, "onListPlayerAttributeRequest");
        Intent intent = new Intent(PLAYERSETTINGS_REQUEST);
        intent.putExtra(COMMAND, CMDGET);
        intent.putExtra(EXTRA_GET_COMMAND, GET_ATTRIBUTE_IDS);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);

        Message msg = mHandler.obtainMessage(MESSAGE_PLAYERSETTINGS_TIMEOUT ,GET_ATTRIBUTE_IDS );
        mPendingCmds.add(new Integer(msg.arg1));
        mHandler.sendMessageDelayed(msg, 130);
    }

    //PDU ID 0x12
    private void onListPlayerAttributeValues (byte attr ) {
        if (DEBUG) Log.v(TAG, "onListPlayerAttributeValues");
        Intent intent = new Intent(PLAYERSETTINGS_REQUEST);
        intent.putExtra(COMMAND, CMDGET);
        intent.putExtra(EXTRA_GET_COMMAND, GET_VALUE_IDS);
        intent.putExtra(EXTRA_ATTRIBUTE_ID, attr);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        mPlayerSettings.attr = attr;
        Message msg = mHandler.obtainMessage();
        msg.what = MESSAGE_PLAYERSETTINGS_TIMEOUT;
        msg.arg1 = GET_VALUE_IDS;
        mPendingCmds.add(new Integer(msg.arg1));
        mHandler.sendMessageDelayed(msg, 130);
    }


    //PDU ID 0x13
    private void onGetPlayerAttributeValues (byte attr ,int[] arr )
    {
        if (DEBUG) Log.v(TAG, "onGetPlayerAttributeValues" + attr );
        int i ;
        byte[] barray = new byte[attr];
        for(i =0 ; i<attr ; ++i)
            barray[i] = (byte)arr[i];
        mPlayerSettings.attrIds = new byte [attr];
        for ( i = 0; i < attr; i++)
            mPlayerSettings.attrIds[i] = barray[i];
        Intent intent = new Intent(PLAYERSETTINGS_REQUEST);
        intent.putExtra(COMMAND, CMDGET);
        intent.putExtra(EXTRA_GET_COMMAND, GET_ATTRIBUTE_VALUES);
        intent.putExtra(EXTRA_ATTIBUTE_ID_ARRAY, barray);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        Message msg = mHandler.obtainMessage();
        msg.what = MESSAGE_PLAYERSETTINGS_TIMEOUT;
        msg.arg1 = GET_ATTRIBUTE_VALUES;
        mPendingCmds.add(new Integer(msg.arg1));
        mHandler.sendMessageDelayed(msg, 130);
    }

    //PDU 0x14
    private void setPlayerAppSetting( byte num , byte [] attr_id , byte [] attr_val )
    {
        if (DEBUG) Log.v(TAG, "setPlayerAppSetting" + num );
        byte[] array = new byte[num*2];
        for ( int i = 0; i < num; i++)
        {
            array[i] = attr_id[i] ;
            array[i+1] = attr_val[i];
        }
        Intent intent = new Intent(PLAYERSETTINGS_REQUEST);
        intent.putExtra(COMMAND, CMDSET);
        intent.putExtra(EXTRA_ATTRIB_VALUE_PAIRS, array);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        Message msg = mHandler.obtainMessage();
        msg.what = MESSAGE_PLAYERSETTINGS_TIMEOUT;
        msg.arg1 = SET_ATTRIBUTE_VALUES;
        mPendingCmds.add(new Integer(msg.arg1));
        mHandler.sendMessageDelayed(msg, 130);
    }

    //PDU 0x15
    private void getplayerattribute_text(byte attr , byte [] attrIds)
    {
        if(DEBUG) Log.d(TAG, "getplayerattribute_text" + attr +"attrIDsNum" + attrIds.length);
        Intent intent = new Intent(PLAYERSETTINGS_REQUEST);
        Message msg = mHandler.obtainMessage();
        intent.putExtra(COMMAND, CMDGET);
        intent.putExtra(EXTRA_GET_COMMAND, GET_ATTRIBUTE_TEXT);
        intent.putExtra(EXTRA_ATTIBUTE_ID_ARRAY, attrIds);
        mPlayerSettings.attrIds = new byte [attr];
        for (int i = 0; i < attr; i++)
            mPlayerSettings.attrIds[i] = attrIds[i];
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        msg.what = MESSAGE_PLAYERSETTINGS_TIMEOUT;
        msg.arg1 = GET_ATTRIBUTE_TEXT;
        mPendingCmds.add(new Integer(msg.arg1));
        mHandler.sendMessageDelayed(msg, 130);
   }

    //PDU 0x15
    private void getplayervalue_text(byte attr_id , byte num_value , byte [] value)
    {
        if(DEBUG) Log.d(TAG, "getplayervalue_text id" + attr_id +"num_value" + num_value
                                                           +"value.lenght" + value.length);
        Intent intent = new Intent(PLAYERSETTINGS_REQUEST);
        Message msg = mHandler.obtainMessage();
        intent.putExtra(COMMAND, CMDGET);
        intent.putExtra(EXTRA_GET_COMMAND, GET_VALUE_TEXT);
        intent.putExtra(EXTRA_ATTRIBUTE_ID, attr_id);
        intent.putExtra(EXTRA_VALUE_ID_ARRAY, value);
        mPlayerSettings.attrIds = new byte [num_value];

        for (int i = 0; i < num_value; i++)
            mPlayerSettings.attrIds[i] = value[i];
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        msg.what = MESSAGE_PLAYERSETTINGS_TIMEOUT;
        msg.arg1 = GET_VALUE_TEXT;
        mPendingCmds.add(new Integer(msg.arg1));
        mHandler.sendMessageDelayed(msg, 130);
    }

    /**
     * This is called from A2dpStateMachine to set A2dp audio state.
     */
    public void setA2dpAudioState(int state) {
        Message msg = mHandler.obtainMessage(MESSAGE_SET_A2DP_AUDIO_STATE, state, 0);
        mHandler.sendMessage(msg);
    }

    // Do not modify without updating the HAL bt_rc.h files.

    // match up with btrc_play_status_t enum of bt_rc.h
    final static byte PLAYSTATUS_STOPPED = 0;
    final static byte PLAYSTATUS_PLAYING = 1;
    final static byte PLAYSTATUS_PAUSED = 2;
    final static byte PLAYSTATUS_FWD_SEEK = 3;
    final static byte PLAYSTATUS_REV_SEEK = 4;
    final static short PLAYSTATUS_ERROR = 255;

    // match up with btrc_media_attr_t enum of bt_rc.h
    final static int MEDIA_ATTR_TITLE = 1;
    final static int MEDIA_ATTR_ARTIST = 2;
    final static int MEDIA_ATTR_ALBUM = 3;
    final static int MEDIA_ATTR_TRACK_NUM = 4;
    final static int MEDIA_ATTR_NUM_TRACKS = 5;
    final static int MEDIA_ATTR_GENRE = 6;
    final static int MEDIA_ATTR_PLAYING_TIME = 7;

    // match up with btrc_event_id_t enum of bt_rc.h
    final static int EVT_PLAY_STATUS_CHANGED = 1;
    final static int EVT_TRACK_CHANGED = 2;
    final static int EVT_TRACK_REACHED_END = 3;
    final static int EVT_TRACK_REACHED_START = 4;
    final static int EVT_PLAY_POS_CHANGED = 5;
    final static int EVT_BATT_STATUS_CHANGED = 6;
    final static int EVT_SYSTEM_STATUS_CHANGED = 7;
    final static int EVT_APP_SETTINGS_CHANGED = 8;

    final static int EVT_AVAILABLE_PLAYERS_CHANGED = 10; //0x0a
    final static int EVT_ADDRESSED_PLAYER_CHANGED = 11; //0x0b
    // match up with btrc_notification_type_t enum of bt_rc.h
    final static int NOTIFICATION_TYPE_INTERIM = 0;
    final static int NOTIFICATION_TYPE_CHANGED = 1;
    final static int NOTIFICATION_TYPE_REJECT = 2;

    // match up with BTRC_UID_SIZE of bt_rc.h
    final static int TRACK_ID_SIZE = 8;

    final static byte ITEM_PLAYER = 0x01;

    final static int SCOPE_PLAYER_LIST = 0x00;

    final static int FOLDER_ITEM_COUNT_NONE = 0xFF;

    final static short CHAR_SET_UTF8 = 0x006A;

    // major player type
    final static byte MAJOR_TYPE_AUDIO = 0x01;  /* Audio */
    final static byte MAJOR_TYPE_VIDEO = 0x02;  /* Video */
    final static byte MAJOR_TYPE_BC_AUDIO = 0x04;  /* Broadcasting Audio */
    final static byte MAJOR_TYPE_BC_VIDEO = 0x08;  /* Broadcasting Video */
    final static short MAJOR_TYPE_INVALID = 0xF0;

    // player sub type
    final static int SUB_TYPE_NONE = 0x0000;
    final static int SUB_TYPE_AUDIO_BOOK = 0x0001;  /* Audio Book */
    final static int SUB_TYPE_PODCAST = 0x0002;  /* Podcast */
    final static int SUB_TYPE_INVALID = 0x00FC;

    // supported feature-mask
    final static int FEATURE_MASK_SELECT_BIT_NO = 0;
    final static int FEATURE_MASK_SELECT_MASK = 0x01;
    final static int FEATURE_MASK_SELECT_OFFSET = 0;

    final static int FEATURE_MASK_UP_BIT_NO = 1;
    final static int FEATURE_MASK_UP_MASK = 0x02;
    final static int FEATURE_MASK_UP_OFFSET = 0;

    final static int FEATURE_MASK_DOWN_BIT_NO = 2;
    final static int FEATURE_MASK_DOWN_MASK = 0x04;
    final static int FEATURE_MASK_DOWN_OFFSET = 0;

    final static int FEATURE_MASK_LEFT_BIT_NO = 3;
    final static int FEATURE_MASK_LEFT_MASK = 0x08;
    final static int FEATURE_MASK_LEFT_OFFSET = 0;

    final static int FEATURE_MASK_RIGHT_BIT_NO = 4;
    final static int FEATURE_MASK_RIGHT_MASK = 0x10;
    final static int FEATURE_MASK_RIGHT_OFFSET = 0;

    final static int FEATURE_MASK_RIGHTUP_BIT_NO = 5;
    final static int FEATURE_MASK_RIGHTUP_MASK = 0x20;
    final static int FEATURE_MASK_RIGHTUP_OFFSET = 0;

    final static int FEATURE_MASK_RIGHTDOWN_BIT_NO = 6;
    final static int FEATURE_MASK_RIGHTDOWN_MASK = 0x40;
    final static int FEATURE_MASK_RIGHTDOWN_OFFSET = 0;

    final static int FEATURE_MASK_LEFTUP_BIT_NO = 7;
    final static int FEATURE_MASK_LEFTUP_MASK = 0x80;
    final static int FEATURE_MASK_LEFTUP_OFFSET = 0;

    final static int FEATURE_MASK_LEFTDOWN_BIT_NO = 8;
    final static int FEATURE_MASK_LEFTDOWN_MASK = 0x01;
    final static int FEATURE_MASK_LEFTDOWN_OFFSET = 1;

    final static int FEATURE_MASK_ROOT_MENU_BIT_NO = 9;
    final static int FEATURE_MASK_ROOT_MENU_MASK = 0x02;
    final static int FEATURE_MASK_ROOT_MENU_OFFSET = 1;

    final static int FEATURE_MASK_SETUP_MENU_BIT_NO = 10;
    final static int FEATURE_MASK_SETUP_MENU_MASK = 0x04;
    final static int FEATURE_MASK_SETUP_MENU_OFFSET = 1;

    final static int FEATURE_MASK_CONTENTS_MENU_BIT_NO = 11;
    final static int FEATURE_MASK_CONTENTS_MENU_MASK = 0x08;
    final static int FEATURE_MASK_CONTENTS_MENU_OFFSET = 1;

    final static int FEATURE_MASK_FAVORITE_MENU_BIT_NO = 12;
    final static int FEATURE_MASK_FAVORITE_MENU_MASK = 0x10;
    final static int FEATURE_MASK_FAVORITE_MENU_OFFSET = 1;

    final static int FEATURE_MASK_EXIT_BIT_NO = 13;
    final static int FEATURE_MASK_EXIT_MASK = 0x20;
    final static int FEATURE_MASK_EXIT_OFFSET = 1;

    final static int FEATURE_MASK_0_BIT_NO = 14;
    final static int FEATURE_MASK_0_MASK = 0x40;
    final static int FEATURE_MASK_0_OFFSET = 1;

    final static int FEATURE_MASK_1_BIT_NO = 15;
    final static int FEATURE_MASK_1_MASK = 0x80;
    final static int FEATURE_MASK_1_OFFSET = 1;

    final static int FEATURE_MASK_2_BIT_NO = 16;
    final static int FEATURE_MASK_2_MASK = 0x01;
    final static int FEATURE_MASK_2_OFFSET = 2;

    final static int FEATURE_MASK_3_BIT_NO = 17;
    final static int FEATURE_MASK_3_MASK = 0x02;
    final static int FEATURE_MASK_3_OFFSET = 2;

    final static int FEATURE_MASK_4_BIT_NO = 18;
    final static int FEATURE_MASK_4_MASK = 0x04;
    final static int FEATURE_MASK_4_OFFSET = 2;

    final static int FEATURE_MASK_5_BIT_NO = 19;
    final static int FEATURE_MASK_5_MASK = 0x08;
    final static int FEATURE_MASK_5_OFFSET = 2;

    final static int FEATURE_MASK_6_BIT_NO = 20;
    final static int FEATURE_MASK_6_MASK = 0x10;
    final static int FEATURE_MASK_6_OFFSET = 2;

    final static int FEATURE_MASK_7_BIT_NO = 21;
    final static int FEATURE_MASK_7_MASK = 0x20;
    final static int FEATURE_MASK_7_OFFSET = 2;

    final static int FEATURE_MASK_8_BIT_NO = 22;
    final static int FEATURE_MASK_8_MASK = 0x40;
    final static int FEATURE_MASK_8_OFFSET = 2;

    final static int FEATURE_MASK_9_BIT_NO = 23;
    final static int FEATURE_MASK_9_MASK = 0x80;
    final static int FEATURE_MASK_9_OFFSET = 2;

    final static int FEATURE_MASK_DOT_BIT_NO = 24;
    final static int FEATURE_MASK_DOT_MASK = 0x01;
    final static int FEATURE_MASK_DOT_OFFSET = 3;

    final static int FEATURE_MASK_ENTER_BIT_NO = 25;
    final static int FEATURE_MASK_ENTER_MASK = 0x02;
    final static int FEATURE_MASK_ENTER_OFFSET = 3;

    final static int FEATURE_MASK_CLEAR_BIT_NO = 26;
    final static int FEATURE_MASK_CLEAR_MASK = 0x04;
    final static int FEATURE_MASK_CLEAR_OFFSET = 3;

    final static int FEATURE_MASK_CHNL_UP_BIT_NO = 27;
    final static int FEATURE_MASK_CHNL_UP_MASK = 0x08;
    final static int FEATURE_MASK_CHNL_UP_OFFSET = 3;

    final static int FEATURE_MASK_CHNL_DOWN_BIT_NO = 28;
    final static int FEATURE_MASK_CHNL_DOWN_MASK = 0x10;
    final static int FEATURE_MASK_CHNL_DOWN_OFFSET = 3;

    final static int FEATURE_MASK_PREV_CHNL_BIT_NO = 29;
    final static int FEATURE_MASK_PREV_CHNL_MASK = 0x20;
    final static int FEATURE_MASK_PREV_CHNL_OFFSET = 3;

    final static int FEATURE_MASK_SOUND_SEL_BIT_NO = 30;
    final static int FEATURE_MASK_SOUND_SEL_MASK = 0x40;
    final static int FEATURE_MASK_SOUND_SEL_OFFSET = 3;

    final static int FEATURE_MASK_INPUT_SEL_BIT_NO = 31;
    final static int FEATURE_MASK_INPUT_SEL_MASK = 0x80;
    final static int FEATURE_MASK_INPUT_SEL_OFFSET = 3;

    final static int FEATURE_MASK_DISP_INFO_BIT_NO = 32;
    final static int FEATURE_MASK_DISP_INFO_MASK = 0x01;
    final static int FEATURE_MASK_DISP_INFO_OFFSET = 4;

    final static int FEATURE_MASK_HELP_BIT_NO = 33;
    final static int FEATURE_MASK_HELP_MASK = 0x02;
    final static int FEATURE_MASK_HELP_OFFSET = 4;

    final static int FEATURE_MASK_PAGE_UP_BIT_NO = 34;
    final static int FEATURE_MASK_PAGE_UP_MASK = 0x04;
    final static int FEATURE_MASK_PAGE_UP_OFFSET = 4;

    final static int FEATURE_MASK_PAGE_DOWN_BIT_NO = 35;
    final static int FEATURE_MASK_PAGE_DOWN_MASK = 0x08;
    final static int FEATURE_MASK_PAGE_DOWN_OFFSET = 4;

    final static int FEATURE_MASK_POWER_BIT_NO = 36;
    final static int FEATURE_MASK_POWER_MASK = 0x10;
    final static int FEATURE_MASK_POWER_OFFSET = 4;

    final static int FEATURE_MASK_VOL_UP_BIT_NO = 37;
    final static int FEATURE_MASK_VOL_UP_MASK = 0x20;
    final static int FEATURE_MASK_VOL_UP_OFFSET = 4;

    final static int FEATURE_MASK_VOL_DOWN_BIT_NO = 38;
    final static int FEATURE_MASK_VOL_DOWN_MASK = 0x40;
    final static int FEATURE_MASK_VOL_DOWN_OFFSET = 4;

    final static int FEATURE_MASK_MUTE_BIT_NO = 39;
    final static int FEATURE_MASK_MUTE_MASK = 0x80;
    final static int FEATURE_MASK_MUTE_OFFSET = 4;

    final static int FEATURE_MASK_PLAY_BIT_NO = 40;
    final static int FEATURE_MASK_PLAY_MASK = 0x01;
    final static int FEATURE_MASK_PLAY_OFFSET = 5;

    final static int FEATURE_MASK_STOP_BIT_NO = 41;
    final static int FEATURE_MASK_STOP_MASK = 0x02;
    final static int FEATURE_MASK_STOP_OFFSET = 5;

    final static int FEATURE_MASK_PAUSE_BIT_NO = 42;
    final static int FEATURE_MASK_PAUSE_MASK = 0x04;
    final static int FEATURE_MASK_PAUSE_OFFSET = 5;

    final static int FEATURE_MASK_RECORD_BIT_NO = 43;
    final static int FEATURE_MASK_RECORD_MASK = 0x08;
    final static int FEATURE_MASK_RECORD_OFFSET = 5;

    final static int FEATURE_MASK_REWIND_BIT_NO = 44;
    final static int FEATURE_MASK_REWIND_MASK = 0x10;
    final static int FEATURE_MASK_REWIND_OFFSET = 5;

    final static int FEATURE_MASK_FAST_FWD_BIT_NO = 45;
    final static int FEATURE_MASK_FAST_FWD_MASK = 0x20;
    final static int FEATURE_MASK_FAST_FWD_OFFSET = 5;

    final static int FEATURE_MASK_EJECT_BIT_NO = 46;
    final static int FEATURE_MASK_EJECT_MASK = 0x40;
    final static int FEATURE_MASK_EJECT_OFFSET = 5;

    final static int FEATURE_MASK_FORWARD_BIT_NO = 47;
    final static int FEATURE_MASK_FORWARD_MASK = 0x80;
    final static int FEATURE_MASK_FORWARD_OFFSET = 5;

    final static int FEATURE_MASK_BACKWARD_BIT_NO = 48;
    final static int FEATURE_MASK_BACKWARD_MASK = 0x01;
    final static int FEATURE_MASK_BACKWARD_OFFSET = 6;

    final static int FEATURE_MASK_ANGLE_BIT_NO = 49;
    final static int FEATURE_MASK_ANGLE_MASK = 0x02;
    final static int FEATURE_MASK_ANGLE_OFFSET = 6;

    final static int FEATURE_MASK_SUBPICTURE_BIT_NO = 50;
    final static int FEATURE_MASK_SUBPICTURE_MASK = 0x04;
    final static int FEATURE_MASK_SUBPICTURE_OFFSET = 6;

    final static int FEATURE_MASK_F1_BIT_NO = 51;
    final static int FEATURE_MASK_F1_MASK = 0x08;
    final static int FEATURE_MASK_F1_OFFSET = 6;

    final static int FEATURE_MASK_F2_BIT_NO = 52;
    final static int FEATURE_MASK_F2_MASK = 0x10;
    final static int FEATURE_MASK_F2_OFFSET = 6;

    final static int FEATURE_MASK_F3_BIT_NO = 53;
    final static int FEATURE_MASK_F3_MASK = 0x20;
    final static int FEATURE_MASK_F3_OFFSET = 6;

    final static int FEATURE_MASK_F4_BIT_NO = 54;
    final static int FEATURE_MASK_F4_MASK = 0x40;
    final static int FEATURE_MASK_F4_OFFSET = 6;

    final static int FEATURE_MASK_F5_BIT_NO = 55;
    final static int FEATURE_MASK_F5_MASK = 0x80;
    final static int FEATURE_MASK_F5_OFFSET = 6;

    final static int FEATURE_MASK_VENDOR_BIT_NO = 56;
    final static int FEATURE_MASK_VENDOR_MASK = 0x01;
    final static int FEATURE_MASK_VENDOR_OFFSET = 7;

    final static int FEATURE_MASK_GROUP_NAVI_BIT_NO = 57;
    final static int FEATURE_MASK_GROUP_NAVI_MASK = 0x02;
    final static int FEATURE_MASK_GROUP_NAVI_OFFSET = 7;

    final static int FEATURE_MASK_ADV_CTRL_BIT_NO = 58;
    final static int FEATURE_MASK_ADV_CTRL_MASK = 0x04;
    final static int FEATURE_MASK_ADV_CTRL_OFFSET = 7;

    final static int FEATURE_MASK_BROWSE_BIT_NO = 59;
    final static int FEATURE_MASK_BROWSE_MASK = 0x08;
    final static int FEATURE_MASK_BROWSE_OFFSET = 7;

    final static int FEATURE_MASK_SEARCH_BIT_NO = 60;
    final static int FEATURE_MASK_SEARCH_MASK = 0x10;
    final static int FEATURE_MASK_SEARCH_OFFSET = 7;

    final static int FEATURE_MASK_ADD2NOWPLAY_BIT_NO = 61;
    final static int FEATURE_MASK_ADD2NOWPLAY_MASK = 0x20;
    final static int FEATURE_MASK_ADD2NOWPLAY_OFFSET = 7;

    final static int FEATURE_MASK_UID_UNIQUE_BIT_NO = 62;
    final static int FEATURE_MASK_UID_UNIQUE_MASK = 0x40;
    final static int FEATURE_MASK_UID_UNIQUE_OFFSET = 7;

    final static int FEATURE_MASK_BR_WH_ADDR_BIT_NO = 63;
    final static int FEATURE_MASK_BR_WH_ADDR_MASK = 0x80;
    final static int FEATURE_MASK_BR_WH_ADDR_OFFSET = 7;

    final static int FEATURE_MASK_SEARCH_WH_ADDR_BIT_NO = 64;
    final static int FEATURE_MASK_SEARCH_WH_ADDR_MASK = 0x01;
    final static int FEATURE_MASK_SEARCH_WH_ADDR_OFFSET = 8;

    final static int FEATURE_MASK_NOW_PLAY_BIT_NO = 65;
    final static int FEATURE_MASK_NOW_PLAY_MASK = 0x02;
    final static int FEATURE_MASK_NOW_PLAY_OFFSET = 8;

    final static int FEATURE_MASK_UID_PERSIST_BIT_NO = 66;
    final static int FEATURE_MASK_UID_PERSIST_MASK = 0x04;
    final static int FEATURE_MASK_UID_PERSIST_OFFSET = 8;

    final static short FEATURE_BITMASK_FIELD_LENGTH = 16;
    final static short PLAYER_ID_FIELD_LENGTH = 2;
    final static short MAJOR_PLAYER_TYPE_FIELD_LENGTH = 1;
    final static short PLAYER_SUBTYPE_FIELD_LENGTH = 4;
    final static short PLAY_STATUS_FIELD_LENGTH = 1;
    final static short CHARSET_ID_FIELD_LENGTH = 2;
    final static short DISPLAYABLE_NAME_LENGTH_FIELD_LENGTH = 2;
    final static short ITEM_TYPE_LENGTH = 1;
    final static short ITEM_LENGTH_LENGTH = 2;
    private native static void classInitNative();
    private native void initNative();
    private native void cleanupNative();
    private native boolean getPlayStatusRspNative(int playStatus, int songLen, int songPos);
    private native boolean getElementAttrRspNative(byte numAttr, int[] attrIds, String[] textArray);
    private native boolean registerNotificationRspPlayStatusNative(int type, int playStatus);
    private native boolean registerNotificationRspTrackChangeNative(int type, byte[] track);
    private native boolean registerNotificationRspPlayPosNative(int type, int playPos);
    private native boolean setVolumeNative(int volume);
    private native boolean registerNotificationRspAddressedPlayerChangedNative(int type, int playerId);
    private native boolean registerNotificationRspAvailablePlayersChangedNative (int type);
    private native boolean setAdressedPlayerRspNative(byte statusCode);
    private native boolean getFolderItemsRspNative(byte statusCode, int uidCounter, int itemCount, byte[] folderItems, int[] folderItemLengths);
    private native boolean getListPlayerappAttrRspNative(byte attr, byte[] attrIds);
    private native boolean getPlayerAppValueRspNative(byte numberattr, byte[]values );
    private native boolean SendCurrentPlayerValueRspNative(byte numberattr, byte[]attr );
    private native boolean SendSetPlayerAppRspNative();
    private native boolean sendSettingsTextRspNative(int num_attr, byte[] attr, int length, String[]text);
    private native boolean sendValueTextRspNative(int num_attr, byte[] attr, int length, String[]text);
    private native boolean registerNotificationPlayerAppRspNative(int type, byte numberattr, byte[]attr);

    /**
      * A class to encapsulate all the information about a media player.
      * static record will be maintained for all applicable media players
      * only the isavailable and isFocussed field will be changed as and when applicable
      */
    private class MediaPlayerInfo {
        private short mPlayerId;
        private byte mMajorPlayerType;
        private int mPlayerSubType;
        private byte mPlayState;
        private short mCharsetId;
        private short mDisplayableNameLength;
        private byte[] mDisplayableName;
        private String mPlayerPackageName;
        private boolean mIsAvailable;
        private boolean mIsFocussed;
        private byte mItemType;
        private Metadata mMetadata;
        private long mTrackNumber;

        // need to have the featuremask elements as int instead of byte, else MSB would be lost. Later need to take only
        // 8 applicable bits from LSB.
        private int[] mFeatureMask;
        private short mItemLength;
        private short mEntryLength;
        public MediaPlayerInfo(short playerId, byte majorPlayerType,
                    int playerSubType, byte playState, short charsetId,
                    short displayableNameLength, byte[] displayableName,
                    String playerPackageName, int[] featureMask ) {
            mPlayerId = playerId;
            mMajorPlayerType = majorPlayerType;
            mPlayerSubType = playerSubType;
            mPlayState = playState;
            mCharsetId = charsetId;
            mDisplayableNameLength = displayableNameLength;
            mPlayerPackageName = playerPackageName;
            mIsAvailable = false; // by default it is false, its toggled whenever applicable
            mIsFocussed = false; // by default it is false, its toggled whenever applicable
            mItemType = ITEM_PLAYER;
            mFeatureMask = new int[FEATURE_BITMASK_FIELD_LENGTH];
            mMetadata = new Metadata();
            mTrackNumber = -1L;
            for (int count = 0; count < FEATURE_BITMASK_FIELD_LENGTH; count ++) {
                mFeatureMask[count] = featureMask[count];
            }

            mDisplayableName = new byte[mDisplayableNameLength];
            for (int count = 0; count < mDisplayableNameLength; count ++) {
                mDisplayableName[count] = displayableName[count];
            }

            mItemLength = (short)(mDisplayableNameLength + PLAYER_ID_FIELD_LENGTH +
                MAJOR_PLAYER_TYPE_FIELD_LENGTH + PLAYER_SUBTYPE_FIELD_LENGTH +
                PLAY_STATUS_FIELD_LENGTH + CHARSET_ID_FIELD_LENGTH +
                DISPLAYABLE_NAME_LENGTH_FIELD_LENGTH + FEATURE_BITMASK_FIELD_LENGTH);
            mEntryLength = (short)(mItemLength + /* ITEM_LENGTH_LENGTH +*/ ITEM_TYPE_LENGTH);
            if (DEBUG) {
                Log.v(TAG, "MediaPlayerInfo: mPlayerId=" + mPlayerId);
                Log.v(TAG, "mMajorPlayerType=" + mMajorPlayerType + " mPlayerSubType=" + mPlayerSubType);
                Log.v(TAG, "mPlayState=" + mPlayState + " mCharsetId=" + mCharsetId);
                Log.v(TAG, "mPlayerPackageName=" + mPlayerPackageName + " mDisplayableNameLength=" + mDisplayableNameLength);
                Log.v(TAG, "mItemLength=" + mItemLength + "mEntryLength=" + mEntryLength);
                Log.v(TAG, "mFeatureMask=");
                for (int count = 0; count < FEATURE_BITMASK_FIELD_LENGTH; count ++) {
                    Log.v(TAG, "" + mFeatureMask[count]);
                }
                Log.v(TAG, "mDisplayableName=");
                for (int count = 0; count < mDisplayableNameLength; count ++) {
                    Log.v(TAG, "" + mDisplayableName[count]);
                }
            }
        }

        public String getPlayerPackageName() {
            return mPlayerPackageName;
        }

        public Metadata GetMetadata() {
            return mMetadata;
        }

        public void SetMetadata(Metadata metaData) {
            mMetadata.albumTitle = metaData.albumTitle;
            mMetadata.artist = metaData.artist;
            mMetadata.trackTitle = metaData.trackTitle;
        }
        public byte GetPlayState() {
            return mPlayState;
        }

        public void SetPlayState(byte playState) {
            mPlayState = playState;
        }

        public long GetTrackNumber() {
            return mTrackNumber;
        }

        public void SetTrackNumber(long trackNumber) {
            mTrackNumber = trackNumber;
        }

        public void SetPlayerAvailablility(boolean isAvailable) {
            mIsAvailable = isAvailable;
        }

        public void SetPlayerFocus(boolean isFocussed) {
            mIsFocussed = isFocussed;
        }

        public boolean GetPlayerAvailablility() {
            return mIsAvailable;
        }

        public boolean GetPlayerFocus() {
            return mIsFocussed;
        }

        /*below apis are required while seraching for id by package name received from media players*/
        public short RetrievePlayerId () {
           return mPlayerId;
        }

        public String RetrievePlayerPackageName () {
            return mPlayerPackageName;
        }

        public int RetrievePlayerEntryLength() {
            return mEntryLength;
        }

        public byte[] RetrievePlayerItemEntry () {
            byte[] playerEntry = new byte[mEntryLength];
            int position =0;
            int count;
            playerEntry[position] = (byte)mItemType; position++;
            playerEntry[position] = (byte)(mPlayerId & 0xff); position++;
            playerEntry[position] = (byte)((mPlayerId >> 8) & 0xff); position++;
            playerEntry[position] = (byte)mMajorPlayerType; position++;
            for (count = 0; count < PLAYER_SUBTYPE_FIELD_LENGTH; count++) {
                playerEntry[position] = (byte)((mPlayerSubType >> (8 * count)) & 0xff); position++;
            }
            playerEntry[position] = (byte)convertPlayStateToPlayStatus(mPlayState); position++;
            for (count = 0; count < FEATURE_BITMASK_FIELD_LENGTH; count++) {
                playerEntry[position] = (byte)mFeatureMask[count]; position++;
            }
            playerEntry[position] = (byte)(mCharsetId & 0xff); position++;
            playerEntry[position] = (byte)((mCharsetId >> 8) & 0xff); position++;
            playerEntry[position] = (byte)(mDisplayableNameLength & 0xff); position++;
            playerEntry[position] = (byte)((mDisplayableNameLength >> 8) & 0xff); position++;
            for (count = 0; count < mDisplayableNameLength; count++) {
                playerEntry[position] = (byte)mDisplayableName[count]; position++;
            }
            if (position != mEntryLength) {
                Log.e(TAG, "ERROR populating PlayerItemEntry: position:" +  position + "mEntryLength:" + mEntryLength);
            }
            if (DEBUG) {
                Log.v(TAG, "MediaPlayerInfo: mPlayerId=" + mPlayerId);
                Log.v(TAG, "mMajorPlayerType=" + mMajorPlayerType + " mPlayerSubType=" + mPlayerSubType);
                Log.v(TAG, "mPlayState=" + mPlayState + " mCharsetId=" + mCharsetId);
                Log.v(TAG, "mPlayerPackageName=" + mPlayerPackageName + " mDisplayableNameLength=" + mDisplayableNameLength);
                Log.v(TAG, "mItemLength=" + mItemLength + "mEntryLength=" + mEntryLength);
                Log.v(TAG, "mFeatureMask=");
                for (count = 0; count < FEATURE_BITMASK_FIELD_LENGTH; count ++) {
                    Log.v(TAG, "" + mFeatureMask[count]);
                }
                Log.v(TAG, "mDisplayableName=");
                for (count = 0; count < mDisplayableNameLength; count ++) {
                    Log.v(TAG, "" + mDisplayableName[count]);
                }
                Log.v(TAG, "playerEntry item is populated as below:=");
                for (count = 0; count < position; count ++) {
                    Log.v(TAG, "" + playerEntry[count]);
                }
            }
            return playerEntry;
        }
    }

    /**
      * The media player instances
      */
    private ArrayList<MediaPlayerInfo> mMediaPlayers = new ArrayList<MediaPlayerInfo>(1);

}
