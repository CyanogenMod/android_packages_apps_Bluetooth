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

package com.android.bluetooth.avrcp;

import java.util.Timer;
import java.util.TimerTask;

import android.app.PendingIntent;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAvrcp;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.IRemoteControlDisplay;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.media.RemoteController;
import android.media.RemoteController.MetadataEditor;
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
import android.view.KeyEvent;

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

import android.provider.MediaStore;
import android.content.ContentResolver;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;

/**
 * support Bluetooth AVRCP profile.
 * support metadata, play status and event notification
 */
public final class Avrcp {
    private static final boolean DEBUG = true;
    private static final String TAG = "Avrcp";

    private Context mContext;
    private final AudioManager mAudioManager;
    private AvrcpMessageHandler mHandler;
    private RemoteController mRemoteController;
    private RemoteControllerWeak mRemoteControllerCb;
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

    private static final int AVRCP_BR_RSP_TIMEOUT = 2000;
    private static final int MESSAGE_SEND_PASS_THROUGH_CMD = 2001;
    private static final int MESSAGE_SET_ADDR_PLAYER = 2002;
    private static final int MESSAGE_GET_FOLDER_ITEMS = 2003;
    private static final int MESSAGE_SET_BROWSED_PLAYER = 2004;
    private static final int MESSAGE_CHANGE_PATH = 2005;
    private static final int MESSAGE_PLAY_ITEM = 2006;
    private static final int MESSAGE_GET_ITEM_ATTRS = 2007;

    private int mAddressedPlayerChangedNT;
    private int mAvailablePlayersChangedNT;
    private int mNowPlayingContentChangedNT;
    private int mAddressedPlayerId;
    private String mRequestedAddressedPlayerPackageName;

    private CachedRequest mCachedRequest = null;

    private static final int MSG_UPDATE_STATE = 100;
    private static final int MSG_SET_METADATA = 101;
    private static final int MSG_SET_TRANSPORT_CONTROLS = 102;
    private static final int MSG_SET_GENERATION_ID = 104;
    private static final int MSG_UPDATE_AVAILABLE_PLAYERS = 201;
    private static final int MSG_UPDATE_ADDRESSED_PLAYER = 202;
    private static final int MSG_UPDATE_RCC_CHANGE = 203;
    private static final int MSG_UPDATE_BROWSED_PLAYER_FOLDER = 204;
    private static final int MSG_UPDATE_NOW_PLAYING_CONTENT_CHANGED = 205;
    private static final int MSG_PLAY_ITEM_RESPONSE = 206;
    private static final int MSG_NOW_PLAYING_ENTRIES_RECEIVED = 207;

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

    private static final int AVRCP_CONNECTED = 1;
    public  static final int KEY_STATE_PRESSED = 0;
    public  static final int KEY_STATE_RELEASED = 1;

    private String mCurrentPath;
    private String mCurrentPathUid;
    private static Uri mMediaUri;

    private final static int TYPE_MEDIA_PLAYER_ITEM = 0x01;
    private final static int TYPE_FOLDER_ITEM = 0x02;
    private final static int TYPE_MEDIA_ELEMENT_ITEM = 0x03;

    private final static int FOLDER_UP = 0x00;
    private final static int FOLDER_DOWN = 0x01;

    private static final String PATH_INVALID = "invalid";
    private static final String PATH_ROOT = "root";
    private static final String PATH_TITLES = "titles";
    private static final String PATH_ALBUMS = "albums";
    private static final String PATH_ARTISTS = "artists";
    private static final String PATH_PLAYLISTS = "playlists";

    private final static long UID_TITLES = 0x01;
    private final static long UID_ALBUM = 0x02;
    private final static long UID_ARTIST = 0x03;
    private final static long UID_PLAYLIST = 0x04;
    private final static int NUM_ROOT_ELEMENTS = 0x04;

    private static final int INTERNAL_ERROR = 0x03;
    private static final int OPERATION_SUCCESSFUL = 0x04;
    private static final int INVALID_DIRECTION = 0x07;
    private static final int NOT_A_DIRECTORY = 0x08;
    private static final int DOES_NOT_EXIST = 0x09;
    private static final int INVALID_SCOPE = 0x0a;
    private static final int RANGE_OUT_OF_BOUNDS = 0x0b;
    private static final int UID_A_DIRECTORY = 0x0c;
    private static final int MEDIA_IN_USE = 0x0d;
    private static final int INVALID_PLAYER_ID = 0x11;
    private static final int PLAYER_NOT_BROWSABLE = 0x12;
    private static final int PLAYER_NOT_ADDRESSED = 0x13;

    private static final int FOLDER_TYPE_MIXED = 0x00;
    private static final int FOLDER_TYPE_TITLES = 0x01;
    private static final int FOLDER_TYPE_ALBUMS = 0x02;
    private static final int FOLDER_TYPE_ARTISTS = 0x03;
    private static final int FOLDER_TYPE_GENRES = 0x04;
    private static final int FOLDER_TYPE_PLAYLISTS = 0x05;

    private static final int MEDIA_TYPE_AUDIO = 0X00;
    private static final int MEDIA_TYPE_VIDEO = 0X01;

    private static final int MAX_BROWSE_ITEM_TO_SEND = 0x03;
    private static final int MAX_ATTRIB_COUNT = 0x07;

    private final static int ALBUMS_ITEM_INDEX = 0;
    private final static int ARTISTS_ITEM_INDEX = 1;
    private final static int PLAYLISTS_ITEM_INDEX = 2;
    private final static int TITLES_ITEM_INDEX = 3;

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
    public static final int ATTRIBUTE_NOTSUPPORTED = -1;

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
    private ArrayList <Integer> mPendingSetAttributes;

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
        mNowPlayingContentChangedNT = NOTIFICATION_TYPE_CHANGED;
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
        mCurrentPath = PATH_INVALID;
        mCurrentPathUid = null;
        mMediaUri = Uri.EMPTY;

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
        mPendingCmds = new ArrayList<Integer>();
        mPendingSetAttributes = new ArrayList<Integer>();
        mCurrentPath = PATH_INVALID;
        mCurrentPathUid = null;
        mMediaUri = Uri.EMPTY;
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(AudioManager.RCC_CHANGED_ACTION);
        intentFilter.addAction(PLAYERSETTINGS_RESPONSE);
        try {
            mContext.registerReceiver(mIntentReceiver, intentFilter);
        }catch (Exception e) {
            Log.e(TAG,"Unable to register Avrcp receiver", e);
        }
        registerMediaPlayers();
        mRemoteControllerCb = new RemoteControllerWeak(mHandler);
        mRemoteController = new RemoteController(mContext, mRemoteControllerCb);
        mAudioManager.registerRemoteController(mRemoteController);
        mRemoteController.setSynchronizationMode(RemoteController.POSITION_SYNCHRONIZATION_CHECK);
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
                boolean isSetAttrValRsp = false;
                synchronized (mPendingCmds) {
                    Integer val = new Integer(getResponse);
                    if (mPendingCmds.contains(val)) {
                        if (getResponse == SET_ATTRIBUTE_VALUES) {
                            isSetAttrValRsp = true;
                            if (DEBUG) Log.v(TAG,"Response received for SET_ATTRIBUTE_VALUES");
                        }
                        mHandler.removeMessages(MESSAGE_PLAYERSETTINGS_TIMEOUT);
                        mPendingCmds.remove(val);
                    }
                }
                if (DEBUG) Log.v(TAG,"getResponse " + getResponse);
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
                        if (DEBUG) Log.v(TAG,"GET_VALUE_IDS " + numAttr);
                        getPlayerAppValueRspNative(numAttr, data);
                    break;
                    case GET_ATTRIBUTE_VALUES:
                        data = intent.getByteArrayExtra(EXTRA_ATTRIB_VALUE_PAIRS);
                        updateLocalPlayerSettings(data);
                        numAttr = (byte) data.length;
                        if (DEBUG) Log.v(TAG,"GET_ATTRIBUTE_VALUES " + numAttr);
                        SendCurrentPlayerValueRspNative(numAttr, data);
                    break;
                    case SET_ATTRIBUTE_VALUES:
                        data = intent.getByteArrayExtra(EXTRA_ATTRIB_VALUE_PAIRS);
                        updateLocalPlayerSettings(data);
                        Log.v(TAG,"SET_ATTRIBUTE_VALUES: ");
                        if (isSetAttrValRsp){
                            isSetAttrValRsp = false;
                            Log.v(TAG,"Respond to SET_ATTRIBUTE_VALUES request");
                            if (checkPlayerAttributeResponse(data)) {
                               SendSetPlayerAppRspNative(OPERATION_SUCCESSFUL);
                            } else {
                               SendSetPlayerAppRspNative(INTERNAL_ERROR);
                            }
                        }
                        if (mPlayerStatusChangeNT == NOTIFICATION_TYPE_INTERIM) {
                            Log.v(TAG,"Send Player appl attribute changed response");
                            mPlayerStatusChangeNT = NOTIFICATION_TYPE_CHANGED;
                            sendPlayerAppChangedRsp(mPlayerStatusChangeNT);
                        } else {
                            Log.v(TAG,"Drop Set Attr Val update from media player");
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
        int[] featureMasks = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        int[] featureMasks2 = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        byte[] playerName1 = {0x4d, 0x75, 0x73, 0x69, 0x63}/*Music*/;
        byte[] playerName2 = {0x4d, 0x75, 0x73, 0x69, 0x63, 0x32}/*Music2*/;

        featureMasks[FEATURE_MASK_PLAY_OFFSET] =
            featureMasks[FEATURE_MASK_PLAY_OFFSET] | FEATURE_MASK_PLAY_MASK;
        featureMasks[FEATURE_MASK_PAUSE_OFFSET] =
            featureMasks[FEATURE_MASK_PAUSE_OFFSET] | FEATURE_MASK_PAUSE_MASK;
        featureMasks[FEATURE_MASK_STOP_OFFSET] =
            featureMasks[FEATURE_MASK_STOP_OFFSET] | FEATURE_MASK_STOP_MASK;
        featureMasks[FEATURE_MASK_PAGE_UP_OFFSET] =
            featureMasks[FEATURE_MASK_PAGE_UP_OFFSET] | FEATURE_MASK_PAGE_UP_MASK;
        featureMasks[FEATURE_MASK_PAGE_DOWN_OFFSET] =
            featureMasks[FEATURE_MASK_PAGE_DOWN_OFFSET] | FEATURE_MASK_PAGE_DOWN_MASK;
        featureMasks[FEATURE_MASK_REWIND_OFFSET] =
            featureMasks[FEATURE_MASK_REWIND_OFFSET] | FEATURE_MASK_REWIND_MASK;
        featureMasks[FEATURE_MASK_FAST_FWD_OFFSET] =
            featureMasks[FEATURE_MASK_FAST_FWD_OFFSET] | FEATURE_MASK_FAST_FWD_MASK;
        featureMasks[FEATURE_MASK_VENDOR_OFFSET] =
            featureMasks[FEATURE_MASK_VENDOR_OFFSET] | FEATURE_MASK_VENDOR_MASK;
        featureMasks[FEATURE_MASK_ADV_CTRL_OFFSET] =
            featureMasks[FEATURE_MASK_ADV_CTRL_OFFSET] | FEATURE_MASK_ADV_CTRL_MASK;
        featureMasks[FEATURE_MASK_BROWSE_OFFSET] =
            featureMasks[FEATURE_MASK_BROWSE_OFFSET] | FEATURE_MASK_BROWSE_MASK;
        featureMasks[FEATURE_MASK_NOW_PLAY_OFFSET] =
            featureMasks[FEATURE_MASK_NOW_PLAY_OFFSET] | FEATURE_MASK_NOW_PLAY_MASK;
        featureMasks[FEATURE_MASK_BR_WH_ADDR_OFFSET] =
            featureMasks[FEATURE_MASK_BR_WH_ADDR_OFFSET] | FEATURE_MASK_BR_WH_ADDR_MASK;

        /*Google player does not support browsing and now playing,
            hence updated the masks properly*/
        featureMasks2[FEATURE_MASK_PLAY_OFFSET] =
            featureMasks2[FEATURE_MASK_PLAY_OFFSET] | FEATURE_MASK_PLAY_MASK;
        featureMasks2[FEATURE_MASK_PAUSE_OFFSET] =
            featureMasks2[FEATURE_MASK_PAUSE_OFFSET] | FEATURE_MASK_PAUSE_MASK;
        featureMasks2[FEATURE_MASK_STOP_OFFSET] =
            featureMasks2[FEATURE_MASK_STOP_OFFSET] | FEATURE_MASK_STOP_MASK;
        featureMasks2[FEATURE_MASK_PAGE_UP_OFFSET] =
            featureMasks2[FEATURE_MASK_PAGE_UP_OFFSET] | FEATURE_MASK_PAGE_UP_MASK;
        featureMasks2[FEATURE_MASK_PAGE_DOWN_OFFSET] =
            featureMasks2[FEATURE_MASK_PAGE_DOWN_OFFSET] | FEATURE_MASK_PAGE_DOWN_MASK;
        featureMasks2[FEATURE_MASK_REWIND_OFFSET] =
            featureMasks2[FEATURE_MASK_REWIND_OFFSET] | FEATURE_MASK_REWIND_MASK;
        featureMasks2[FEATURE_MASK_FAST_FWD_OFFSET] =
            featureMasks2[FEATURE_MASK_FAST_FWD_OFFSET] | FEATURE_MASK_FAST_FWD_MASK;
        featureMasks2[FEATURE_MASK_VENDOR_OFFSET] =
            featureMasks2[FEATURE_MASK_VENDOR_OFFSET] | FEATURE_MASK_VENDOR_MASK;
        featureMasks2[FEATURE_MASK_ADV_CTRL_OFFSET] =
            featureMasks2[FEATURE_MASK_ADV_CTRL_OFFSET] | FEATURE_MASK_ADV_CTRL_MASK;

        mediaPlayerInfo1 = new MediaPlayerInfo ((short)0x0001,
                    MAJOR_TYPE_AUDIO,
                    SUB_TYPE_NONE,
                    (byte)RemoteControlClient.PLAYSTATE_PAUSED,
                    CHAR_SET_UTF8,
                    (short)0x05,
                    playerName1,
                    "com.android.music",
                    true,
                    featureMasks);

        mediaPlayerInfo2 = new MediaPlayerInfo ((short)0x0002,
                    MAJOR_TYPE_AUDIO,
                    SUB_TYPE_NONE,
                    (byte)RemoteControlClient.PLAYSTATE_PAUSED,
                    CHAR_SET_UTF8,
                    (short)0x06,
                    playerName2,
                    "com.google.android.music",
                    false,
                    featureMasks2);

        mMediaPlayers.add(mediaPlayerInfo1);
        mMediaPlayers.add(mediaPlayerInfo2);
    }

    public static Avrcp make(Context context) {
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
        mAudioManager.unregisterRemoteController(mRemoteController);
        keyPressState = KEY_STATE_RELEASE; //Key release state
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
        mCurrentPath = PATH_INVALID;
        mMediaUri = Uri.EMPTY;
        mCurrentPathUid = null;
    }

    public void cleanup() {
        if (DEBUG) Log.v(TAG, "cleanup");
        cleanupNative();
    }

    private static class RemoteControllerWeak implements RemoteController.OnClientUpdateListener {
        private final WeakReference<Handler> mLocalHandler;

        public RemoteControllerWeak(Handler handler) {
            mLocalHandler = new WeakReference<Handler>(handler);
        }

        @Override
        public void onClientChange(boolean clearing) {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_SET_GENERATION_ID,
                        0, (clearing ? 1 : 0), null).sendToTarget();
            }
        }

        @Override
        public void onClientPlaybackStateUpdate(int state) {
            // Should never be called with the existing code, but just in case
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_UPDATE_STATE, 0, state,
                        new Long(RemoteControlClient.PLAYBACK_POSITION_INVALID)).sendToTarget();
            }
        }

        @Override
        public void onClientPlaybackStateUpdate(int state, long stateChangeTimeMs,
                long currentPosMs, float speed) {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_UPDATE_STATE, 0, state,
                        new Long(currentPosMs)).sendToTarget();
            }
        }

        @Override
        public void onClientTransportControlUpdate(int transportControlFlags) {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_SET_TRANSPORT_CONTROLS, 0, transportControlFlags)
                        .sendToTarget();
            }
        }

        @Override
        public void onClientMetadataUpdate(MetadataEditor metadataEditor) {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_SET_METADATA, 0, 0, metadataEditor).sendToTarget();
            }
        }

        @Override
        public void onClientFolderInfoBrowsedPlayer(String stringUri) {
            Log.v(TAG, "onClientFolderInfoBrowsedPlayer: stringUri: " + stringUri);
            Handler handler = mLocalHandler.get();
            if (stringUri != null) {
                String[] ExternalPath = stringUri.split("/");
                if (ExternalPath.length < 4) {
                    Log.d(TAG, "Wrong entries.");
                    handler.obtainMessage(MSG_UPDATE_BROWSED_PLAYER_FOLDER, 0, 0, null)
                                                                        .sendToTarget();
                    return;
                }
                Uri uri = Uri.parse(stringUri);
                Log.v(TAG, "URI received: " + uri);
                String[] SplitPath = new String[ExternalPath.length - 3];
                for (int count = 2; count < (ExternalPath.length - 1); count++) {
                    SplitPath[count - 2] = ExternalPath[count];
                    Log.d(TAG, "SplitPath[" + (count - 2) + "] = " + SplitPath[count - 2]);
                }
                Log.v(TAG, "folderDepth: " + SplitPath.length);
                for (int count = 0; count < SplitPath.length; count++) {
                    Log.v(TAG, "folderName: " + SplitPath[count]);
                }
                mMediaUri = uri;
                if (handler != null) {
                    handler.obtainMessage(MSG_UPDATE_BROWSED_PLAYER_FOLDER, NUM_ROOT_ELEMENTS,
                                                SplitPath.length, SplitPath).sendToTarget();
                }
            } else {
                handler.obtainMessage(MSG_UPDATE_BROWSED_PLAYER_FOLDER, 0, 0, null)
                                                                    .sendToTarget();
            }
        }

        @Override
        public void onClientUpdateNowPlayingEntries(long[] playList) {
            Log.v(TAG, "onClientUpdateNowPlayingEntries");
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_NOW_PLAYING_ENTRIES_RECEIVED, 0, 0,
                                                            playList).sendToTarget();
            }
        }

        @Override
        public void onClientNowPlayingContentChange() {
            Log.v(TAG, "onClientNowPlayingContentChange");
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_UPDATE_NOW_PLAYING_CONTENT_CHANGED).sendToTarget();
            }
        }

        @Override
        public void onClientPlayItemResponse(boolean success) {
            Log.v(TAG, "onClientPlayItemResponse");
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_PLAY_ITEM_RESPONSE, 0, 0, new Boolean(success))
                                                                            .sendToTarget();
            }
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
                        SendSetPlayerAppRspNative(INTERNAL_ERROR);
                    break;
                    case GET_ATTRIBUTE_TEXT:
                    case GET_VALUE_TEXT:
                        String [] values = new String [mPlayerSettings.attrIds.length];
                        String msgVal = (msg.what == GET_ATTRIBUTE_TEXT) ? UPDATE_ATTRIB_TEXT :
                                                                                 UPDATE_VALUE_TEXT;
                        for (int i = 0; i < mPlayerSettings.attrIds.length; i++) {
                            values[i] = "";
                        }
                        if (msg.arg1 == GET_ATTRIBUTE_TEXT) {
                            sendSettingsTextRspNative(mPlayerSettings.attrIds.length,
                                                    mPlayerSettings.attrIds, values.length, values);
                        } else {
                            sendValueTextRspNative(mPlayerSettings.attrIds.length,
                                                   mPlayerSettings.attrIds, values.length, values);
                        }
                    break;
                    default :
                    break;
                }
                break;
            case MSG_UPDATE_STATE:
                    updatePlayPauseState(msg.arg2, ((Long) msg.obj).longValue());
                break;

            case MSG_SET_METADATA:
                    updateMetadata((MetadataEditor) msg.obj);
                break;

            case MSG_UPDATE_AVAILABLE_PLAYERS:
                updateAvailableMediaPlayers();
                break;

            case MSG_UPDATE_ADDRESSED_PLAYER:
                updateAddressedMediaPlayer(msg.arg1);
                break;

            case MSG_UPDATE_BROWSED_PLAYER_FOLDER:
                Log.v(TAG, "MSG_UPDATE_BROWSED_PLAYER_FOLDER");
                updateBrowsedPlayerFolder(msg.arg1, msg.arg2, (String [])msg.obj);
                break;

            case MSG_UPDATE_NOW_PLAYING_CONTENT_CHANGED:
                Log.v(TAG, "MSG_UPDATE_NOW_PLAYING_CONTENT_CHANGED");
                updateNowPlayingContentChanged();
                break;

            case MSG_PLAY_ITEM_RESPONSE:
                Log.v(TAG, "MSG_PLAY_ITEM_RESPONSE");
                boolean success = ((Boolean)msg.obj).booleanValue();
                Log.v(TAG, "success: " + success);
                updatePlayItemResponse(success);
                break;

            case MSG_NOW_PLAYING_ENTRIES_RECEIVED:
                Log.v(TAG, "MSG_NOW_PLAYING_ENTRIES_RECEIVED");
                updateNowPlayingEntriesReceived((long [])msg.obj);
                break;

            case MSG_SET_TRANSPORT_CONTROLS:
                    updateTransportControls(msg.arg2);
                break;

            case MSG_SET_GENERATION_ID:
                if (DEBUG) Log.v(TAG, "New genId = " + msg.arg1 + ", clearing = " + msg.arg2);
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
                setAdressedPlayerRspNative ((byte)PLAYER_NOT_ADDRESSED);
                mRequestedAddressedPlayerPackageName = null;
                break;

            case MESSAGE_VOLUME_CHANGED:
                if (DEBUG) Log.v(TAG, "MESSAGE_VOLUME_CHANGED: volume=" + ((byte)msg.arg1 & 0x7f)
                                                        + " ctype=" + msg.arg2);

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
                    byte absVol = (byte)((byte)msg.arg1 & 0x7f); // discard MSB as it is RFD
                    notifyVolumeChanged(absVol);
                    mAbsoluteVolume = absVol;
                    long pecentVolChanged = ((long)absVol * 100) / 0x7f;
                    Log.e(TAG, "percent volume changed: " + pecentVolChanged + "%");
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
                if(msg.what == MESSAGE_FAST_FORWARD) {
                    if((mTransportControlFlags &
                        RemoteControlClient.FLAG_KEY_MEDIA_FAST_FORWARD) != 0) {
                    int keyState = msg.arg1 == KEY_STATE_PRESS ?
                        KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
                    KeyEvent keyEvent =
                        new KeyEvent(keyState, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
                    mRemoteController.sendMediaKeyEvent(keyEvent);
                    break;
                    }
                } else if((mTransportControlFlags &
                        RemoteControlClient.FLAG_KEY_MEDIA_REWIND) != 0) {
                    int keyState = msg.arg1 == KEY_STATE_PRESS ?
                        KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
                    KeyEvent keyEvent =
                        new KeyEvent(keyState, KeyEvent.KEYCODE_MEDIA_REWIND);
                    mRemoteController.sendMediaKeyEvent(keyEvent);
                    break;
                }

                int skipAmount;
                if (msg.what == MESSAGE_FAST_FORWARD) {
                    if (DEBUG) Log.v(TAG, "MESSAGE_FAST_FORWARD");
                    removeMessages(MESSAGE_FAST_FORWARD);
                    skipAmount = BASE_SKIP_AMOUNT;
                } else {
                    if (DEBUG) Log.v(TAG, "MESSAGE_REWIND");
                    removeMessages(MESSAGE_REWIND);
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

            case MESSAGE_SET_A2DP_AUDIO_STATE:
                if (DEBUG) Log.v(TAG, "MESSAGE_SET_A2DP_AUDIO_STATE:" + msg.arg1);
                updateA2dpAudioState(msg.arg1);
                break;

            case MSG_UPDATE_RCC_CHANGE:
                Log.v(TAG, "MSG_UPDATE_RCC_CHANGE");
                String callingPackageName = (String)msg.obj;
                int isFocussed = msg.arg1;
                int isAvailable = msg.arg2;
                processRCCStateChange(callingPackageName, isFocussed, isAvailable);
                break;

            case MESSAGE_SET_ADDR_PLAYER:
                processSetAddressedPlayer(msg.arg1);
                break;
            case MESSAGE_SET_BROWSED_PLAYER:
                processSetBrowsedPlayer(msg.arg1);
                break;
            case MESSAGE_CHANGE_PATH:
                processChangePath(msg.arg1, ((Long)msg.obj).longValue());
                break;
            case MESSAGE_PLAY_ITEM:
                processPlayItem(msg.arg1, ((Long)msg.obj).longValue());
                break;
            case MESSAGE_GET_ITEM_ATTRS:
                int[] attrIds;
                ItemAttr itemAttr = (ItemAttr)msg.obj;
                attrIds = new int[msg.arg1];
                for (int i = 0; i < msg.arg1; ++i) {
                    attrIds[i] = itemAttr.mAttrList.get(i).intValue();
                }
                processGetItemAttr((byte)msg.arg2, itemAttr.mUid, (byte)msg.arg1, attrIds);
                break;
            case MESSAGE_GET_FOLDER_ITEMS:
                FolderListEntries folderListEntries = (FolderListEntries)msg.obj;
                attrIds = new int[folderListEntries.mNumAttr];
                for (int i = 0; i < folderListEntries.mNumAttr; ++i) {
                    attrIds[i] = folderListEntries.mAttrList.get(i).intValue();
                }
                processGetFolderItems(folderListEntries.mScope, folderListEntries.mStart,
                    folderListEntries.mEnd, folderListEntries.mAttrCnt,
                    folderListEntries.mNumAttr, attrIds);
                break;
            }
        }
    }

    private void updateA2dpAudioState(int state) {
        boolean isPlaying = (state == BluetoothA2dp.STATE_PLAYING);
        if (isPlaying != isPlayingState(mCurrentPlayState)) {
            /* if a2dp is streaming, check to make sure music is active */
            if ( (isPlaying) && !mAudioManager.isMusicActive())
                return;
            updatePlayPauseState(isPlaying ? RemoteControlClient.PLAYSTATE_PLAYING :
                                 RemoteControlClient.PLAYSTATE_PAUSED,
                                 RemoteControlClient.PLAYBACK_POSITION_INVALID);
        }
    }

    private void updatePlayPauseState(int state, long currentPosMs) {
        if (DEBUG) Log.v(TAG,
                "updatePlayPauseState, old=" + mCurrentPlayState + ", state=" + state);
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

        if ((state == RemoteControlClient.PLAYSTATE_PLAYING) && (mCurrentPlayState != state)) {
            mPlayStartTimeMs = SystemClock.elapsedRealtime();
            Log.d(TAG, "Update mPlayStartTimeMs to " + mPlayStartTimeMs);
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

        if (!(RemoteControlClient.PLAYSTATE_PLAYING == mCurrentPlayState
                                             && mCurrentPosMs == currentPosMs)) {
            if (currentPosMs != RemoteControlClient.PLAYBACK_POSITION_INVALID) {
                mCurrentPosMs = currentPosMs;
                mPlayStartTimeMs = SystemClock.elapsedRealtime();
                Log.d(TAG, "Update mPlayStartTimeMs: " + mPlayStartTimeMs + " mCurrentPosMs: "
                                                                                + mCurrentPosMs);
            }
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
            if (DEBUG) Log.v(TAG, "Do not reset notifications, ADDR_PLAYR_CHNGD not registered");
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
        if (mNowPlayingContentChangedNT == NOTIFICATION_TYPE_INTERIM) {
            if (DEBUG) Log.v(TAG, "send Now playing changed reject to stack");
            mNowPlayingContentChangedNT = NOTIFICATION_TYPE_REJECT;
            registerNotificationRspNowPlayingContentChangedNative(mNowPlayingContentChangedNT);
        }
    }

    void updateBrowsedPlayerFolder(int numOfItems, int folderDepth, String[] folderNames) {
        Log.v(TAG, "updateBrowsedPlayerFolder: folderDepth: " + folderDepth);
        mCurrentPath = PATH_ROOT;
        mCurrentPathUid = null;
        if (folderDepth > 0) {
            setBrowsedPlayerRspNative((byte)OPERATION_SUCCESSFUL, 0x0, numOfItems,
                                            folderDepth, CHAR_SET_UTF8, folderNames);
        } else {
            setBrowsedPlayerRspNative((byte)INTERNAL_ERROR, 0x0, numOfItems,
                                            folderDepth, CHAR_SET_UTF8, folderNames);
        }
    }

    void updateNowPlayingContentChanged() {
        Log.v(TAG, "updateNowPlayingContentChanged");
        if (mNowPlayingContentChangedNT == NOTIFICATION_TYPE_INTERIM) {
            Log.v(TAG, "Notify peer on updateNowPlayingContentChanged");
            mNowPlayingContentChangedNT = NOTIFICATION_TYPE_CHANGED;
            registerNotificationRspNowPlayingContentChangedNative(mNowPlayingContentChangedNT);
        }
    }

    void updatePlayItemResponse(boolean success) {
        Log.v(TAG, "updatePlayItemResponse: success: " + success);
        if (success) {
            playItemRspNative(OPERATION_SUCCESSFUL);
        } else {
            playItemRspNative(INTERNAL_ERROR);
        }
    }

    void updateNowPlayingEntriesReceived(long[] playList) {
        int status = OPERATION_SUCCESSFUL;
        int numItems = 0;
        long reqItems = (mCachedRequest.mEnd - mCachedRequest.mStart) + 1;
        long availableItems = 0;
        Cursor cursor = null;
        int[] itemType = new int[MAX_BROWSE_ITEM_TO_SEND];
        long[] uid = new long[MAX_BROWSE_ITEM_TO_SEND];
        int[] type = new int[MAX_BROWSE_ITEM_TO_SEND];
        byte[] playable = new byte[MAX_BROWSE_ITEM_TO_SEND];
        String[] displayName = new String[MAX_BROWSE_ITEM_TO_SEND];
        byte[] numAtt = new byte[MAX_BROWSE_ITEM_TO_SEND];
        String[] attValues = new String[MAX_BROWSE_ITEM_TO_SEND * 7];
        int[] attIds = new int[MAX_BROWSE_ITEM_TO_SEND * 7];
        int index;

        Log.v(TAG, "updateNowPlayingEntriesReceived");

        // Item specific attribute's entry starts from index*7, reset all such entries to 0 for now
        for (int count = 0; count < (MAX_BROWSE_ITEM_TO_SEND * 7); count++) {
            attValues[count] = "";
            attIds[count] = 0;
        }

        availableItems = playList.length;
        if ((mCachedRequest.mStart + 1) > availableItems) {
            Log.i(TAG, "startIteam exceeds the available item index");
            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems, itemType, uid, type,
                                            playable, displayName, numAtt, attValues, attIds);
            return;
        }

        if ((mCachedRequest.mStart < 0) || (mCachedRequest.mEnd < 0) ||
                            (mCachedRequest.mStart > mCachedRequest.mEnd)) {
            Log.i(TAG, "wrong start / end index");
            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems, itemType, uid, type,
                                        playable, displayName, numAtt, attValues, attIds);
            return;
        }

        availableItems = availableItems - mCachedRequest.mStart;
        Log.i(TAG, "start Index: " + mCachedRequest.mStart);
        Log.i(TAG, "end Index: " + mCachedRequest.mEnd);
        Log.i(TAG, "availableItems: " + availableItems);
        if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
            availableItems = MAX_BROWSE_ITEM_TO_SEND;
        if (reqItems > availableItems)
            reqItems = availableItems;
        Log.i(TAG, "reqItems: " + reqItems);

        for (index = 0; index < reqItems; index++) {
            try {
                cursor = mContext.getContentResolver().query(
                     mMediaUri, mCursorCols,
                     MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id=" +
                         playList[index + (int)mCachedRequest.mStart], null, null);
                if (cursor != null) {
                    int validAttrib = 0;
                    cursor.moveToFirst();
                    itemType[index] = TYPE_MEDIA_ELEMENT_ITEM;
                    uid[index] = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
                    type[index] = MEDIA_TYPE_AUDIO;
                    playable[index] = 0;
                    displayName[index] = cursor.getString(cursor.getColumnIndexOrThrow(
                                                            MediaStore.Audio.Media.TITLE));
                    for (int attIndex = 0; attIndex < mCachedRequest.mAttrCnt; attIndex++) {
                        int attr = mCachedRequest.mAttrList.get(attIndex).intValue();
                        if ((attr <= MEDIA_ATTR_MAX) && (attr >= MEDIA_ATTR_MIN)) {
                            attValues[(7 * index) + attIndex] =
                                getAttributeStringFromCursor(cursor, attr);
                            attIds[(7 * index) + attIndex] = attr;
                            validAttrib ++;
                        }
                    }
                    numAtt[index] = (byte)validAttrib;
                }
            } catch(Exception e) {
                Log.i(TAG, "Exception "+ e);
                getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType,
                            uid, type, playable, displayName, numAtt, attValues, attIds);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        numItems = index;
        getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL, numItems, itemType, uid,
                        type, playable, displayName, numAtt, attValues, attIds);
    }

    class CachedRequest {
        long mStart;
        long mEnd;
        byte mAttrCnt;
        ArrayList<Integer> mAttrList;
        public CachedRequest(long start, long end, byte attrCnt, int[] attrs) {
            mStart = start;
            mEnd = end;
            mAttrCnt = attrCnt;
            mAttrList = new ArrayList<Integer>();
            for (int i = 0; i < attrCnt; ++i) {
                mAttrList.add(new Integer(attrs[i]));
            }
        }
    }

    class FolderListEntries {
        byte mScope;
        long mStart;
        long mEnd;
        int mAttrCnt;
        int mNumAttr;
        ArrayList<Integer> mAttrList;
        public FolderListEntries(byte scope, long start, long end, int attrCnt, int numAttr,
                                                                                int[] attrs) {
            mScope = scope;
            mStart = start;
            mEnd = end;
            mAttrCnt = attrCnt;
            mNumAttr = numAttr;
            int i;
            mAttrList = new ArrayList<Integer>();
            for (i = 0; i < numAttr; ++i) {
                mAttrList.add(new Integer(attrs[i]));
            }
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

    private void updateMetadata(MetadataEditor data) {
        if (DEBUG) Log.v(TAG, "updateMetadata");
        if (mMediaPlayers.size() > 0) {
            final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
            while (rccIterator.hasNext()) {
                final MediaPlayerInfo di = rccIterator.next();
                if (di.GetPlayerFocus()) {
                    if (DEBUG) Log.v(TAG, "resetting current MetaData");
                    mMetadata.artist = di.GetMetadata().artist;
                    mMetadata.trackTitle = di.GetMetadata().trackTitle;
                    mMetadata.albumTitle = di.GetMetadata().albumTitle;
                    mMetadata.genre = di.GetMetadata().genre;
                    mMetadata.tracknum = di.GetMetadata().tracknum;
                    break;
                }
            }
        }

        String oldMetadata = mMetadata.toString();
        mMetadata.artist = data.getString(MediaMetadataRetriever.METADATA_KEY_ARTIST, null);
        mMetadata.trackTitle = data.getString(MediaMetadataRetriever.METADATA_KEY_TITLE, null);
        mMetadata.albumTitle = data.getString(MediaMetadataRetriever.METADATA_KEY_ALBUM, null);
        mMetadata.genre = data.getString(MediaMetadataRetriever.METADATA_KEY_GENRE, null);
        mTrackNumber = data.getLong(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS, -1L);
        mMetadata.tracknum = data.getLong(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER, -1L);

        Log.v(TAG,"old Metadata = " + oldMetadata);
        Log.v(TAG,"new MetaData " + mMetadata.toString());

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

        mSongLengthMs = data.getLong(MediaMetadataRetriever.METADATA_KEY_DURATION,
                RemoteControlClient.PLAYBACK_POSITION_INVALID);
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

    private void setBrowsedPlayer(int playerId) {
        if (DEBUG) Log.v(TAG, "setBrowsedPlayer: PlayerID: " + playerId);
        Message msg = mHandler.obtainMessage(MESSAGE_SET_BROWSED_PLAYER, playerId, 0, 0);
        mHandler.sendMessage(msg);
    }

    private void processSetBrowsedPlayer(int playerId) {
        String packageName = null;
        byte retError = INVALID_PLAYER_ID;
        /*Following gets updated if SetBrowsed Player succeeds*/
        mCurrentPath = PATH_INVALID;
        mMediaUri = Uri.EMPTY;
        mCurrentPathUid = null;
        if (DEBUG) Log.v(TAG, "processSetBrowsedPlayer: PlayerID: " + playerId);
        if (mMediaPlayers.size() > 0) {
            final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
            while (rccIterator.hasNext()) {
                final MediaPlayerInfo di = rccIterator.next();
                if (di.RetrievePlayerId() == playerId) {
                    if (di.GetPlayerAvailablility()) {
                        if (DEBUG) Log.v(TAG, "player found and available");
                        if (di.IsPlayerBrowsable()) {
                            if (di.IsPlayerBrowsableWhenAddressed()) {
                                if (di.GetPlayerFocus()) {
                                    packageName = di.RetrievePlayerPackageName();
                                    if (DEBUG) Log.v(TAG, "player addressed hence browsable");
                                } else {
                                    if (DEBUG) Log.v(TAG, "Reject: player browsable only" +
                                                                            "when addressed");
                                    retError = PLAYER_NOT_ADDRESSED;
                                }
                            } else {
                                packageName = di.RetrievePlayerPackageName();
                            }
                        } else {
                            retError = PLAYER_NOT_BROWSABLE;
                        }
                    }
                }
            }
        }
        if (packageName != null) {
            mRemoteController.setRemoteControlClientBrowsedPlayer();
        } else {
            if (DEBUG) Log.v(TAG, "player not available for browse");
            setBrowsedPlayerRspNative(retError, 0x0, 0, 0, 0, null);
        }
    }

    private void fastForward(int keyState) {
        if ((keyState == keyPressState) && (keyState == KEY_STATE_RELEASE)) {
            Log.e(TAG, "Ignore key release event");
        } else {
            Message msg = mHandler.obtainMessage(MESSAGE_FAST_FORWARD, keyState, 0);
            mHandler.sendMessage(msg);
            keyPressState = keyState;
        }
    }

    private void rewind(int keyState) {
        if ((keyState == keyPressState) && (keyState == KEY_STATE_RELEASE)) {
            Log.e(TAG, "Ignore key release event");
        } else {
            Message msg = mHandler.obtainMessage(MESSAGE_REWIND, keyState, 0);
            mHandler.sendMessage(msg);
            keyPressState = keyState;
        }
    }

    private void changePath(byte direction, long uid) {
        if (DEBUG) Log.v(TAG, "changePath: direction: " + direction + " uid:" + uid);
        Message msg = mHandler.obtainMessage(MESSAGE_CHANGE_PATH, direction, 0, uid);
        mHandler.sendMessage(msg);
    }

    private void processChangePath(int direction, long folderUid) {
        if (DEBUG) Log.v(TAG, "processChangePath: direction: " + direction +
                                                                " uid:" + folderUid);
        long numberOfItems = 0;
        int status = OPERATION_SUCCESSFUL;
        if (mCurrentPath.equals(PATH_ROOT)){
            switch (direction) {
                case FOLDER_UP:
                    status = DOES_NOT_EXIST;
                    break;
                case FOLDER_DOWN:
                    if (folderUid == UID_TITLES) {
                        mCurrentPath = PATH_TITLES;
                        numberOfItems = getNumItems(PATH_TITLES,
                            MediaStore.Audio.Media.TITLE);
                    } else if (folderUid == UID_ALBUM) {
                        mCurrentPath = PATH_ALBUMS;
                        numberOfItems = getNumItems(PATH_ALBUMS,
                            MediaStore.Audio.Media.ALBUM_ID);
                    } else if (folderUid == UID_ARTIST) {
                        mCurrentPath = PATH_ARTISTS;
                        numberOfItems = getNumItems(PATH_ARTISTS,
                            MediaStore.Audio.Media.ARTIST_ID);
                    } else if (folderUid == UID_PLAYLIST) {
                        mCurrentPath = PATH_PLAYLISTS;
                        numberOfItems = getNumPlaylistItems();
                    } else {
                        status = DOES_NOT_EXIST;
                    }
                    break;
                default:
                    status = INVALID_DIRECTION;
                    break;
            }
        } else if (mCurrentPath.equals(PATH_TITLES)) {
            switch (direction) {
                case FOLDER_UP:
                    mCurrentPath = PATH_ROOT;
                    numberOfItems = NUM_ROOT_ELEMENTS;
                    break;
                case FOLDER_DOWN:
                    Cursor cursor = null;
                    try {
                        cursor = mContext.getContentResolver().query(
                            mMediaUri,
                            new String[] {MediaStore.Audio.Media.TITLE},
                            MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id="
                            + folderUid, null, null);
                        if (cursor != null)
                            status = NOT_A_DIRECTORY;
                        else
                            status = DOES_NOT_EXIST;
                    } catch (Exception e) {
                        Log.e(TAG, "Exception " + e);
                        changePathRspNative(INTERNAL_ERROR, numberOfItems);
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                    break;
                default:
                    status = INVALID_DIRECTION;
                    break;
            }
        } else if (mCurrentPath.equals(PATH_ALBUMS)) {
            switch (direction) {
                case FOLDER_UP:
                    if (mCurrentPathUid == null) { // Path @ Album
                        mCurrentPath = PATH_ROOT;
                        numberOfItems = NUM_ROOT_ELEMENTS;
                    } else { // Path @ individual album id
                        mCurrentPath = PATH_ALBUMS;
                        mCurrentPathUid = null;
                        numberOfItems = getNumItems(PATH_ALBUMS,
                            MediaStore.Audio.Media.ALBUM_ID);
                    }
                    break;
                case FOLDER_DOWN:
                    if (mCurrentPathUid == null) { // Path @ Album
                        Cursor cursor = null;
                        try {
                            cursor = mContext.getContentResolver().query(
                                mMediaUri,
                                new String[] {MediaStore.Audio.Media.ALBUM},
                                MediaStore.Audio.Media.IS_MUSIC + "=1 AND " +
                                MediaStore.Audio.Media.ALBUM_ID + "=" + folderUid,
                                null, null);
                            if ((cursor == null) || (cursor.getCount() == 0)) {
                                status = DOES_NOT_EXIST;
                            } else{
                                numberOfItems = cursor.getCount();
                                mCurrentPathUid = String.valueOf(folderUid);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Exception " + e);
                            changePathRspNative(INTERNAL_ERROR, numberOfItems);
                        } finally {
                            if (cursor != null) {
                                cursor.close();
                            }
                        }
                    } else { // Path @ Individual Album id
                        Cursor cursor = null;
                        try {
                            cursor = mContext.getContentResolver().query(
                                mMediaUri,
                                new String[] {MediaStore.Audio.Media.TITLE},
                                MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id=" + folderUid,
                                null, null);
                            // As Individual Album path can not have any folder in it hence return
                            // the error as applicable, depending on whether uid passed exists.
                            if (cursor != null)
                                status = NOT_A_DIRECTORY;
                            else
                                status = DOES_NOT_EXIST;
                        } catch (Exception e) {
                            Log.e(TAG, "Exception " + e);
                            changePathRspNative(INTERNAL_ERROR, numberOfItems);
                        } finally {
                            if (cursor != null) {
                                cursor.close();
                            }
                        }
                    }
                    break;
                default:
                    status = INVALID_DIRECTION;
                    break;
            }
        } else if (mCurrentPath.equals(PATH_ARTISTS)) {
            switch(direction) {
                case FOLDER_UP:
                    if (mCurrentPathUid == null) {
                        mCurrentPath = PATH_ROOT;
                        numberOfItems = NUM_ROOT_ELEMENTS;
                    } else {
                        mCurrentPath = PATH_ARTISTS;
                        mCurrentPathUid = null;
                        numberOfItems = getNumItems(PATH_ARTISTS,
                            MediaStore.Audio.Media.ARTIST_ID);
                    }
                    break;
                case FOLDER_DOWN:
                    if (mCurrentPathUid == null) {
                        Cursor cursor = null;
                        try {
                            cursor = mContext.getContentResolver().query(
                                mMediaUri,
                                new String[] {MediaStore.Audio.Media.ARTIST},
                                MediaStore.Audio.Media.IS_MUSIC + "=1 AND " +
                                MediaStore.Audio.Media.ARTIST_ID + "=" + folderUid,
                                null, null);
                            if ((cursor == null) || (cursor.getCount() == 0)) {
                                status = DOES_NOT_EXIST;
                            } else{
                                numberOfItems = cursor.getCount();
                                mCurrentPathUid = String.valueOf(folderUid);
                                mCurrentPath = PATH_ARTISTS;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Exception " + e);
                            changePathRspNative(INTERNAL_ERROR, numberOfItems);
                        } finally {
                            if (cursor != null) {
                                cursor.close();
                            }
                        }
                    } else {
                        Cursor cursor = null;
                        try {
                            cursor = mContext.getContentResolver().query(
                                mMediaUri,
                                new String[] {MediaStore.Audio.Media.TITLE},
                                MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id="
                                + folderUid, null, null);
                            if (cursor != null)
                                status = NOT_A_DIRECTORY;
                            else
                                status = DOES_NOT_EXIST;
                        } catch (Exception e) {
                            Log.e(TAG, "Exception " + e);
                            changePathRspNative(INTERNAL_ERROR, numberOfItems);
                        } finally {
                            if (cursor != null) {
                                cursor.close();
                            }
                        }
                    }
                    break;
                default:
                    status = INVALID_DIRECTION;
                    break;
            }
        } else if (mCurrentPath.equals(PATH_PLAYLISTS)) {
            switch(direction) {
                case FOLDER_UP:
                    if (mCurrentPathUid == null) {
                        mCurrentPath = PATH_ROOT;
                        numberOfItems = NUM_ROOT_ELEMENTS;
                    } else {
                        mCurrentPath = PATH_PLAYLISTS;
                        mCurrentPathUid = null;
                        numberOfItems = getNumPlaylistItems();
                    }
                    break;
                case FOLDER_DOWN:
                    if (mCurrentPathUid == null) {
                        Cursor cursor = null;
                        try {
                            String[] cols = new String[] {
                                MediaStore.Audio.Playlists._ID,
                                MediaStore.Audio.Playlists.NAME
                            };

                            StringBuilder where = new StringBuilder();
                            where.append(MediaStore.Audio.Playlists.NAME + " != ''");

                            cursor = mContext.getContentResolver().query(
                                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                                cols, MediaStore.Audio.Playlists._ID + "=" + folderUid,
                                null, null);

                            if ((cursor == null) || (cursor.getCount() == 0)) {
                                status = DOES_NOT_EXIST;
                            } else{
                                numberOfItems = cursor.getCount();
                                mCurrentPathUid = String.valueOf(folderUid);
                                mCurrentPath = PATH_PLAYLISTS;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Exception " + e);
                            changePathRspNative(INTERNAL_ERROR, numberOfItems);
                        } finally {
                            if (cursor != null) {
                                cursor.close();
                            }
                        }
                    } else {
                        numberOfItems = 0;
                        status = DOES_NOT_EXIST;
                    }
                    break;
                default:
                    status = INVALID_DIRECTION;
                    break;
            }
        } else {
            Log.i(TAG, "Current Path not set");
            status = DOES_NOT_EXIST;
        }
        Log.i(TAG, "Number of items " + numberOfItems + ", status: " + status);
        changePathRspNative(status, numberOfItems);
    }

    private long getNumPlaylistItems() {
        Cursor cursor = null;
        String[] cols = new String[] {
                MediaStore.Audio.Playlists._ID,
                MediaStore.Audio.Playlists.NAME
        };
        try {
            cursor = mContext.getContentResolver().query(
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                cols, MediaStore.Audio.Playlists.NAME + " != ''", null,
                MediaStore.Audio.Playlists.NAME);

            if ((cursor == null) || (cursor.getCount() == 0)) {
                return 0;
            } else {
                long count = cursor.getCount();
                return count;
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception " + e);
            return 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private long getNumItems(String path, String element) {
        if (path == null || element == null)
            return 0;
        Cursor cursor = null;
        try {
            cursor = mContext.getContentResolver().query(
                mMediaUri,
                new String[] {element},
                MediaStore.Audio.Media.IS_MUSIC + "=1", null,
                element);
            if ((cursor == null) || (cursor.getCount() == 0)) {
                return 0;
            } else if (path.equals(PATH_TITLES)) {
                long count = cursor.getCount();
                return count;
            } else if (path.equals(PATH_ALBUMS) || path.equals(PATH_ARTISTS)){
                long elemCount = 0;
                cursor.moveToFirst();
                long count = cursor.getCount();
                long prevElem = 0;
                long curElem = 0;
                while (count > 0) {
                    curElem = cursor.getLong(cursor.getColumnIndexOrThrow(element));
                    Log.i(TAG, "curElem "+ curElem + "preElem " + prevElem);
                    if (curElem != prevElem) {
                        elemCount++;
                    }
                    prevElem = curElem;
                    cursor.moveToNext();
                    count--;
                }
                Log.i(TAG, "element Count is "+ elemCount);
                return elemCount;
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception " + e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0;
    }

    private void playItem(byte scope, long uid) {
        if (DEBUG) Log.v(TAG, "playItem: scope: " + scope + " uid:" + uid);
        Message msg = mHandler.obtainMessage(MESSAGE_PLAY_ITEM, scope, 0, uid);
        mHandler.sendMessage(msg);
    }

    private void processPlayItem(int scope, long uid) {
        if (DEBUG) Log.v(TAG, "processPlayItem: scope: " + scope + " uid:" + uid);
        if (uid < 0) {
            Log.e(TAG, "invalid uid");
            playItemRspNative(DOES_NOT_EXIST);
            return;
        }
        if (mMediaPlayers.size() > 0) {
            final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
            while (rccIterator.hasNext()) {
                final MediaPlayerInfo di = rccIterator.next();
                if (di.GetPlayerFocus()) {
                    if (!di.IsRemoteAddressable()) {
                        playItemRspNative(INTERNAL_ERROR);
                        Log.e(TAG, "Play Item fails: Player not remote addressable");
                        return;
                    }
                }
            }
        }
        if (scope == SCOPE_VIRTUAL_FILE_SYS) {
            if (mCurrentPath.equals(PATH_ROOT)) {
                playItemRspNative(UID_A_DIRECTORY);
            } else if (mCurrentPath.equals(PATH_TITLES)) {
                Cursor cursor = null;
                try {
                    cursor = mContext.getContentResolver().query(
                        mMediaUri,
                        new String[] {MediaStore.Audio.Media.TITLE},
                        MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id=" + uid,
                        null, null);
                    if ((cursor == null) || (cursor.getCount() == 0)) {
                        Log.e(TAG, "No such track");
                        playItemRspNative(DOES_NOT_EXIST);
                    } else {
                        Log.i(TAG, "Play uid:" + uid);
                        mRemoteController.setRemoteControlClientPlayItem(uid, scope);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception " + e);
                    playItemRspNative(INTERNAL_ERROR);
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            } else if (mCurrentPath.equals(PATH_ALBUMS)) {
                if (mCurrentPathUid == null) {
                    playItemRspNative(UID_A_DIRECTORY);
                } else {
                    Cursor cursor = null;
                    try {
                        cursor = mContext.getContentResolver().query(
                            mMediaUri,
                            new String[] {MediaStore.Audio.Media.TITLE},
                            MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id=" + uid + " AND " +
                            MediaStore.Audio.Media.ALBUM_ID + "=" + mCurrentPathUid,
                            null, null);
                        if ((cursor == null) || (cursor.getCount() == 0)) {
                            Log.i(TAG, "No such track");
                            playItemRspNative(DOES_NOT_EXIST);
                        } else {
                            Log.i(TAG, "Play uid:" + uid);
                            mRemoteController.setRemoteControlClientPlayItem(uid, scope);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception " + e);
                        playItemRspNative(INTERNAL_ERROR);
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            } else if (mCurrentPath.equals(PATH_ARTISTS)) {
                if (mCurrentPathUid == null) {
                    playItemRspNative(UID_A_DIRECTORY);
                } else {
                    Cursor cursor = null;
                    try {
                        cursor = mContext.getContentResolver().query(
                            mMediaUri,
                            new String[] {MediaStore.Audio.Media.TITLE},
                            MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id=" + uid + " AND " +
                            MediaStore.Audio.Media.ARTIST_ID + "=" + mCurrentPathUid,
                            null, null);
                        if ((cursor == null) || (cursor.getCount() == 0)) {
                            Log.i(TAG, "No such track");
                            playItemRspNative(DOES_NOT_EXIST);
                        } else {
                            Log.i(TAG, "Play uid:" + uid);
                            mRemoteController.setRemoteControlClientPlayItem(uid, scope);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception " + e);
                        playItemRspNative(INTERNAL_ERROR);
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            } else if (mCurrentPath.equals(PATH_PLAYLISTS)) {
                if (mCurrentPathUid == null) {
                    playItemRspNative(UID_A_DIRECTORY);
                } else {
                    Cursor cursor = null;
                    try {
                        String[] playlistMemberCols = new String[] {
                                MediaStore.Audio.Playlists.Members._ID,
                                MediaStore.Audio.Media.TITLE,
                                MediaStore.Audio.Media.DATA,
                                MediaStore.Audio.Media.ALBUM,
                                MediaStore.Audio.Media.ARTIST,
                                MediaStore.Audio.Media.DURATION,
                                MediaStore.Audio.Playlists.Members.PLAY_ORDER,
                                MediaStore.Audio.Playlists.Members.AUDIO_ID,
                                MediaStore.Audio.Media.IS_MUSIC
                        };
                        Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external",
                                                                Long.parseLong(mCurrentPathUid));
                        StringBuilder where = new StringBuilder();
                        where.append(MediaStore.Audio.Playlists.Members.AUDIO_ID + "=" + uid);
                        cursor = mContext.getContentResolver().query(uri, playlistMemberCols,
                                    where.toString(), null, MediaStore.Audio.Playlists.Members.
                                                                            DEFAULT_SORT_ORDER);

                        if ((cursor == null) || (cursor.getCount() == 0)) {
                            Log.i(TAG, "No such track");
                            playItemRspNative(DOES_NOT_EXIST);
                        } else {
                            Log.i(TAG, "Play uid:" + uid);
                            mRemoteController.setRemoteControlClientPlayItem(uid, scope);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception " + e);
                        playItemRspNative(INTERNAL_ERROR);
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            } else {
                playItemRspNative(DOES_NOT_EXIST);
            }
        } else if (scope == SCOPE_NOW_PLAYING) {
            mRemoteController.setRemoteControlClientPlayItem(uid, scope);
        } else {
            playItemRspNative(DOES_NOT_EXIST);
            Log.e(TAG, "Play Item fails: Invalid scope");
        }
    }

    private void getItemAttr(byte scope, long uid, byte numAttr, int[] attrs) {
        if (DEBUG) Log.v(TAG, "getItemAttr: scope: " + scope + " uid:" + uid +
                                                            " numAttr:" + numAttr);
        int i;
        ArrayList<Integer> attrList = new ArrayList<Integer>();
        for (i = 0; i < numAttr; ++i) {
            attrList.add(attrs[i]);
            if (DEBUG) Log.v(TAG, "attrs[" + i + "] = " + attrs[i]);
        }
        ItemAttr itemAttr = new ItemAttr(attrList, uid);
        Message msg = mHandler.obtainMessage(MESSAGE_GET_ITEM_ATTRS, (int)numAttr,
                                                                (int)scope, itemAttr);
        mHandler.sendMessage(msg);
    }

    private String[] mCursorCols = new String[] {
                    "audio._id AS _id",
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.ARTIST_ID,
                    MediaStore.Audio.Media.IS_PODCAST,
                    MediaStore.Audio.Media.BOOKMARK
    };

    private void processGetItemAttr(byte scope, long uid, byte numAttr, int[] attrs) {
        if (DEBUG) Log.v(TAG, "processGetItemAttr: scope: " + scope + " uid:" + uid +
                                                                    " numAttr:" + numAttr);
        String[] textArray;
        textArray = new String[numAttr];
        if ((scope == SCOPE_VIRTUAL_FILE_SYS) || (scope == SCOPE_NOW_PLAYING)) {
            Cursor cursor = null;
            try {
                if ((mMediaUri == Uri.EMPTY) || (mCurrentPath.equals(PATH_INVALID))) {
                    Log.e(TAG, "Browsed player not set, getItemAttr can not be processed");
                    getItemAttrRspNative((byte)0, attrs, textArray);
                    return;
                }
                cursor = mContext.getContentResolver().query(
                     mMediaUri, mCursorCols,
                     MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id=" + uid, null, null);
                if ((cursor == null) || (cursor.getCount() == 0)) {
                    Log.i(TAG, "Invalid track UID");
                    getItemAttrRspNative((byte)0, attrs, textArray);
                } else {
                    int validAttrib = 0;
                    cursor.moveToFirst();
                    for (int i = 0; i < numAttr; ++i) {
                        if ((attrs[i] <= MEDIA_ATTR_MAX) && (attrs[i] >= MEDIA_ATTR_MIN)) {
                            textArray[i] = getAttributeStringFromCursor(cursor, attrs[i]);
                            validAttrib ++;
                        }
                    }
                    getItemAttrRspNative((byte)validAttrib, attrs, textArray);
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception " + e);
                getItemAttrRspNative((byte)0, attrs, textArray);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        } else {
            Log.i(TAG, "Invalid scope");
            getItemAttrRspNative((byte)0, attrs, textArray);
        }
    }

    private class ItemAttr {
        ArrayList<Integer> mAttrList;
        long mUid;
        public ItemAttr (ArrayList<Integer> attrList, long uid) {
            mAttrList = attrList;
            mUid = uid;
        }
    }

    private void setAddressedPlayer(int playerId) {
        if (DEBUG) Log.v(TAG, "setAddressedPlayer: PlayerID: " + playerId);
        Message msg = mHandler.obtainMessage(MESSAGE_SET_ADDR_PLAYER, playerId, 0, 0);
        mHandler.sendMessage(msg);
    }

    private void processSetAddressedPlayer(int playerId) {
        if (DEBUG) Log.v(TAG, "processSetAddressedPlayer: PlayerID: " + playerId);
        String packageName = null;
        if (mRequestedAddressedPlayerPackageName != null) {
            if (DEBUG) Log.v(TAG, "setAddressedPlayer: Request in progress, Reject this Request");
            setAdressedPlayerRspNative ((byte)PLAYER_NOT_ADDRESSED);
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
                setAdressedPlayerRspNative ((byte)OPERATION_SUCCESSFUL);
                return;
            }
            String newPackageName = packageName.replace("com.android", "org.codeaurora");
            Intent mediaIntent = new Intent(newPackageName + ".setaddressedplayer");
            mediaIntent.setPackage(packageName);
            mContext.sendBroadcast(mediaIntent); // This needs to be caught in respective media players
            if (DEBUG) Log.v(TAG, "Intent Broadcasted: " + newPackageName + ".setaddressedplayer");
            mRequestedAddressedPlayerPackageName = packageName;
            Message msg = mHandler.obtainMessage(MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT);
            mHandler.sendMessageDelayed(msg, AVRCP_BR_RSP_TIMEOUT);
            Log.v(TAG, "Post MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT");
        } else {
            if (DEBUG) Log.v(TAG, "setAddressedPlayer fails: No such media player available");
            setAdressedPlayerRspNative ((byte)INVALID_PLAYER_ID);
        }
    }

    private void getFolderItems(byte scope, long start, long end, int attrCnt,
                                                        int numAttr, int[] attrs) {
        if (DEBUG) Log.v(TAG, "getFolderItems");
        if (DEBUG) Log.v(TAG, "scope: " + scope + " attrCnt: " + attrCnt);
        if (DEBUG) Log.v(TAG, "start: " + start + " end: " + end);
        for (int i = 0; i < numAttr; ++i) {
            if (DEBUG) Log.v(TAG, "attrs[" + i + "] = " + attrs[i]);
        }

        FolderListEntries folderListEntries = new FolderListEntries (scope, start, end, attrCnt,
                                                                                    numAttr, attrs);
        Message msg = mHandler.obtainMessage(MESSAGE_GET_FOLDER_ITEMS, 0, 0, folderListEntries);
        mHandler.sendMessage(msg);
    }

    private void processGetFolderItems(byte scope, long start, long end, int size,
                                                                int numAttr, int[] attrs) {
        if (DEBUG) Log.v(TAG, "processGetFolderItems");
        if (DEBUG) Log.v(TAG, "scope: " + scope + " size: " + size);
        if (DEBUG) Log.v(TAG, "start: " + start + " end: " + end + " numAttr: " + numAttr);
        if (scope == SCOPE_PLAYER_LIST) { // populate mediaplayer item list here
            processGetMediaPlayerItems(scope, start, end, size, numAttr, attrs);
        } else if ((scope == SCOPE_VIRTUAL_FILE_SYS) || (scope == SCOPE_NOW_PLAYING)) {
            for (int i = 0; i < numAttr; ++i) {
                if (DEBUG) Log.v(TAG, "attrs[" + i + "] = " + attrs[i]);
            }
            processGetFolderItemsInternal(scope, start, end, size, (byte)numAttr, attrs);
        }
    }

    private void processGetMediaPlayerItems(byte scope, long start, long end, int size,
                                                                int numAttr, int[] attrs) {
        byte[] folderItems = new byte[size];
        int[] folderItemLengths = new int[32];
        int availableMediaPlayers = 0;
        int count = 0;
        int positionItemStart = 0;
        if (mMediaPlayers.size() > 0) {
            final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
            while (rccIterator.hasNext()) {
                final MediaPlayerInfo di = rccIterator.next();
                if (di.GetPlayerAvailablility()) {
                    if (start == 0) {
                        byte[] playerEntry = di.RetrievePlayerItemEntry();
                        int length = di.RetrievePlayerEntryLength();
                        folderItemLengths[availableMediaPlayers ++] = length;
                        for (count = 0; count < length; count ++) {
                            folderItems[positionItemStart + count] = playerEntry[count];
                        }
                        positionItemStart += length; // move start to next item start
                    } else if (start > 0) {
                        --start;
                    }
                }
            }
        }
        if (DEBUG) Log.v(TAG, "Number of available MediaPlayers = " + availableMediaPlayers);
        getMediaPlayerListRspNative ((byte)OPERATION_SUCCESSFUL, 0x1357,
                    availableMediaPlayers, folderItems, folderItemLengths);
    }

    private boolean isCurrentPathValid () {
        if (mCurrentPath.equals(PATH_ROOT) || mCurrentPath.equals(PATH_TITLES) ||
            mCurrentPath.equals(PATH_ALBUMS) || mCurrentPath.equals(PATH_ARTISTS) ||
            mCurrentPath.equals(PATH_PLAYLISTS)){
            return true;
        }
        return false;
    }

    private void processGetFolderItemsInternal(byte scope, long start, long end, long size,
                                                                    byte numAttr, int[] attrs) {

        int status = OPERATION_SUCCESSFUL;
        long numItems = 0;
        long reqItems = (end - start) + 1;
        int[] itemType = new int[MAX_BROWSE_ITEM_TO_SEND];
        long[] uid = new long[MAX_BROWSE_ITEM_TO_SEND];
        int[] type = new int[MAX_BROWSE_ITEM_TO_SEND];
        byte[] playable = new byte[MAX_BROWSE_ITEM_TO_SEND];
        String[] displayName = new String[MAX_BROWSE_ITEM_TO_SEND];
        byte[] numAtt = new byte[MAX_BROWSE_ITEM_TO_SEND];
        String[] attValues = new String[MAX_BROWSE_ITEM_TO_SEND * 7];
        int[] attIds = new int[MAX_BROWSE_ITEM_TO_SEND * 7];

        if (DEBUG) Log.v(TAG, "processGetFolderItemsInternal");

        if (DEBUG) Log.v(TAG, "requested attribute count" + numAttr);
        for (int count = 0; count < numAttr; count++) {
            if (DEBUG) Log.v(TAG, "attr[" + count + "] = " + attrs[count]);
        }

        if (scope == SCOPE_VIRTUAL_FILE_SYS) {
            // Item specific attribute's entry starts from index*7
            for (int count = 0; count < (MAX_BROWSE_ITEM_TO_SEND * 7); count++) {
                attValues[count] = "";
                attIds[count] = 0;
            }

            if (DEBUG) Log.v(TAG, "mCurrentPath: " + mCurrentPath);
            if (DEBUG) Log.v(TAG, "mCurrentPathUID: " + mCurrentPathUid);
            if (!isCurrentPathValid()) {
                getFolderItemsRspNative((byte)DOES_NOT_EXIST, numItems, itemType, uid, type,
                                            playable, displayName, numAtt, attValues, attIds);
                Log.e(TAG, "Current path not set");
                return;
            }

            if ((start < 0) || (end < 0) || (start > end)) {
                getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems, itemType, uid, type,
                                        playable, displayName, numAtt, attValues, attIds);
                Log.e(TAG, "Wrong start/end index");
                return;
            }

            if (mCurrentPath.equals(PATH_ROOT)) {
                long availableItems = NUM_ROOT_ELEMENTS;
                if (start >= availableItems) {
                    Log.i(TAG, "startIteam exceeds the available item index");
                    getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems, itemType, uid,
                                        type, playable, displayName, numAtt, attValues, attIds);
                    return;
                }
                if (DEBUG) Log.v(TAG, "availableItems: " + availableItems);
                if (DEBUG) Log.v(TAG, "reqItems: " + reqItems);
                availableItems = availableItems - start;
                if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                    availableItems = MAX_BROWSE_ITEM_TO_SEND;
                if (reqItems > availableItems)
                    reqItems = availableItems;
                if (DEBUG) Log.v(TAG, "revised reqItems: " + reqItems);

                numItems = reqItems;

                for (int count = 0; count < reqItems; count ++) {
                    long index = start + count;
                    switch ((int)index) {
                        case ALBUMS_ITEM_INDEX:
                            itemType[count] = TYPE_FOLDER_ITEM;
                            uid[count] = UID_ALBUM;
                            type[count] = FOLDER_TYPE_ALBUMS;
                            playable[count] = 0;
                            displayName[count] = PATH_ALBUMS;
                            numAtt[count] = 0;
                            break;
                        case ARTISTS_ITEM_INDEX:
                            itemType[count] = TYPE_FOLDER_ITEM;
                            uid[count] = UID_ARTIST;
                            type[count] = FOLDER_TYPE_ARTISTS;
                            playable[count] = 0;
                            displayName[count] = PATH_ARTISTS;
                            numAtt[count] = 0;
                            break;
                        case PLAYLISTS_ITEM_INDEX:
                            itemType[count] = TYPE_FOLDER_ITEM;
                            uid[count] = UID_PLAYLIST;
                            type[count] = FOLDER_TYPE_PLAYLISTS;
                            playable[count] = 0;
                            displayName[count] = PATH_PLAYLISTS;
                            numAtt[count] = 0;
                            break;
                        case TITLES_ITEM_INDEX:
                            itemType[count] = TYPE_FOLDER_ITEM;
                            uid[count] = UID_TITLES;
                            type[count] = FOLDER_TYPE_TITLES;
                            playable[count] = 0;
                            displayName[count] = PATH_TITLES;
                            numAtt[count] = 0;
                            break;
                        default:
                            Log.i(TAG, "wrong index");
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems,
                            itemType, uid, type, playable, displayName, numAtt, attValues,
                                                                                    attIds);
                    }
                }

                for (int count = 0; count < numItems; count++) {
                    Log.v(TAG, itemType[count] + "," + uid[count] + "," + type[count]);
                }
                getFolderItemsRspNative((byte)status, numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds);
            } else if (mCurrentPath.equals(PATH_TITLES)) {
                long availableItems = 0;
                Cursor cursor = null;
                try {
                    cursor = mContext.getContentResolver().query(
                            mMediaUri,
                            mCursorCols, MediaStore.Audio.Media.IS_MUSIC + "=1", null,
                            MediaStore.Audio.Media.DEFAULT_SORT_ORDER);
                    if (cursor != null) {
                        availableItems = cursor.getCount();
                        if (start >= availableItems) {
                            Log.i(TAG, "startIteam exceeds the available item index");
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems,
                            itemType, uid, type, playable, displayName, numAtt, attValues,
                                                                                    attIds);
                            return;
                        }
                        cursor.moveToFirst();
                        for (int i = 0; i < start; i++) {
                            cursor.moveToNext();
                        }
                    } else {
                        Log.i(TAG, "Error: could not fetch the elements");
                        getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType,
                                uid, type, playable, displayName, numAtt, attValues, attIds);
                        return;
                    }
                    if (DEBUG) Log.v(TAG, "availableItems: " + availableItems);
                    if (DEBUG) Log.v(TAG, "reqItems: " + reqItems);
                    availableItems = availableItems - start;
                    if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                        availableItems = MAX_BROWSE_ITEM_TO_SEND;
                    if (reqItems > availableItems)
                        reqItems = availableItems;
                    if (DEBUG) Log.v(TAG, "revised reqItems: " + reqItems);

                    int attIndex;
                    int index;
                    for (index = 0; index < reqItems; index++) {
                        itemType[index] = TYPE_MEDIA_ELEMENT_ITEM;
                        uid[index] = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
                        type[index] = MEDIA_TYPE_AUDIO;
                        playable[index] = 0;
                        displayName[index] =
                            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.
                                                                    Audio.Media.TITLE));
                        int validAttrib = 0;
                        for (attIndex = 0; attIndex < numAttr; attIndex++) {
                            if ((attrs[attIndex] <= MEDIA_ATTR_MAX) &&
                                        (attrs[attIndex] >= MEDIA_ATTR_MIN)) {
                                attValues[(7 * index) + attIndex] =
                                    getAttributeStringFromCursor(cursor, attrs[attIndex]);
                                attIds[(7 * index) + attIndex] = attrs[attIndex];
                                validAttrib ++;
                            }
                        }
                        numAtt[index] = (byte)validAttrib;
                        cursor.moveToNext();
                    }
                    numItems = index;
                    getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL, numItems, itemType, uid,
                                        type, playable, displayName, numAtt, attValues, attIds);
                } catch(Exception e) {
                    Log.i(TAG, "Exception e" + e);
                    getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType, uid, type,
                                            playable, displayName, numAtt, attValues, attIds);
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            } else if (mCurrentPath.equals(PATH_ALBUMS)) {
                if (mCurrentPathUid == null) {
                    long availableItems = 0;
                    Cursor cursor = null;
                    try {
                        availableItems = getNumItems(PATH_ALBUMS, MediaStore.Audio.Media.ALBUM_ID);
                        if (start >= availableItems) {
                            Log.i(TAG, "startIteam exceeds the available item index");
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems, itemType,
                                    uid, type, playable, displayName, numAtt, attValues, attIds);
                            return;
                        }
                        if (DEBUG) Log.v(TAG, "availableItems: " + availableItems);
                        if (DEBUG) Log.v(TAG, "reqItems: " + reqItems);

                        availableItems = availableItems - start;
                        if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                            availableItems = MAX_BROWSE_ITEM_TO_SEND;
                        if (reqItems > availableItems)
                            reqItems = (int)availableItems;
                        Log.i(TAG, "revised reqItems: " + reqItems);

                        cursor = mContext.getContentResolver().query(
                                            mMediaUri, mCursorCols,
                                            MediaStore.Audio.Media.IS_MUSIC + "=1", null,
                                            MediaStore.Audio.Albums.DEFAULT_SORT_ORDER);

                        int count = 0;
                        if (cursor != null) {
                            count = cursor.getCount();
                        } else {
                            Log.i(TAG, "Error: could not fetch the elements");
                            getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType,
                                uid, type, playable, displayName, numAtt, attValues, attIds);
                            return;
                        }
                        if (count < reqItems) {
                            reqItems = count;
                        }
                        cursor.moveToFirst();
                        int index = 0;
                        long prevElem = -1;
                        long curElem = -1;
                        while ((reqItems > 0) && (count > 0)) {
                            curElem = cursor.getLong(cursor.getColumnIndexOrThrow(
                                                    MediaStore.Audio.Media.ALBUM_ID));
                            if (curElem != prevElem) {
                                if (start > 0) {
                                    --start;
                                } else {
                                    itemType[index] = TYPE_FOLDER_ITEM;
                                    uid[index] = cursor.getLong(cursor.getColumnIndexOrThrow(
                                                        MediaStore.Audio.Media.ALBUM_ID));
                                    type[index] = FOLDER_TYPE_ALBUMS;
                                    playable[index] = 0;
                                    displayName[index] = cursor.getString(
                                                cursor.getColumnIndexOrThrow(
                                                MediaStore.Audio.Media.ALBUM));
                                    numAtt[index] = 0;
                                    index++;
                                    reqItems--;
                                }
                            }
                            prevElem = curElem;
                            cursor.moveToNext();
                            count--;
                        }
                        if (index > 0) {
                            numItems = index;
                            getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL, numItems,
                                itemType, uid, type, playable, displayName, numAtt, attValues,
                                attIds);
                        } else {
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems,
                                itemType, uid, type, playable, displayName, numAtt, attValues,
                                attIds);
                        }
                    } catch(Exception e) {
                        Log.i(TAG, "Exception e" + e);
                        getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType, uid, type,
                                        playable, displayName, numAtt, attValues, attIds);
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                } else {
                    long folderUid = Long.valueOf(mCurrentPathUid);
                    long availableItems = 0;
                    Cursor cursor = null;
                    try {
                        cursor = mContext.getContentResolver().query(
                            mMediaUri,
                            mCursorCols, MediaStore.Audio.Media.IS_MUSIC + "=1 AND " +
                            MediaStore.Audio.Media.ALBUM_ID + "=" + folderUid, null,
                                            MediaStore.Audio.Albums.DEFAULT_SORT_ORDER);

                        if (cursor != null) {
                            availableItems = cursor.getCount();
                            if (start >= availableItems) {
                                Log.i(TAG, "startIteam exceeds the available item index");
                                getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems,
                                    itemType, uid, type, playable, displayName, numAtt,
                                    attValues, attIds);
                                return;
                            }
                            cursor.moveToFirst();
                            for (int i = 0; i < start; i++) {
                                cursor.moveToNext();
                            }
                        } else {
                            Log.i(TAG, "Error: could not fetch the elements");
                            getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems,
                                itemType, uid, type, playable, displayName, numAtt,
                                attValues, attIds);
                            return;
                        }

                        if (DEBUG) Log.v(TAG, "availableItems: " + availableItems);
                        if (DEBUG) Log.v(TAG, "reqItems: " + reqItems);
                        availableItems = availableItems - start;
                        if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                            availableItems = MAX_BROWSE_ITEM_TO_SEND;
                        if (reqItems > availableItems)
                            reqItems = availableItems;
                        if (DEBUG) Log.v(TAG, "revised reqItems: " + reqItems);

                        int attIndex;
                        int index;
                        for (index = 0; index < reqItems; index++) {
                            itemType[index] = TYPE_MEDIA_ELEMENT_ITEM;
                            uid[index] = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
                            type[index] = MEDIA_TYPE_AUDIO;
                            playable[index] = 0;
                            displayName[index] =
                                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.
                                                                        Audio.Media.TITLE));                            
                            int validAttrib = 0;
                            for (attIndex = 0; attIndex < numAttr; attIndex++) {
                                if ((attrs[attIndex] <= MEDIA_ATTR_MAX) &&
                                            (attrs[attIndex] >= MEDIA_ATTR_MIN)) {
                                    attValues[(7 * index) + attIndex] =
                                        getAttributeStringFromCursor(cursor, attrs[attIndex]);
                                    attIds[(7 * index) + attIndex] = attrs[attIndex];
                                    validAttrib ++;
                                }
                            }
                            numAtt[index] = (byte)validAttrib;
                            cursor.moveToNext();
                        }
                        numItems = index;
                        getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL, numItems, itemType, uid,
                                            type, playable, displayName, numAtt, attValues, attIds);
                    } catch(Exception e) {
                        Log.i(TAG, "Exception e" + e);
                        getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType, uid, type,
                                        playable, displayName, numAtt, attValues, attIds);
                    } finally {
                        if (cursor != null) {
                        cursor.close();
                        }
                    }
                }
            } else if (mCurrentPath.equals(PATH_ARTISTS)) {
                if (mCurrentPathUid == null) {
                    long availableItems = 0;
                    Cursor cursor = null;
                    try {
                        availableItems = getNumItems(PATH_ARTISTS,
                                    MediaStore.Audio.Media.ARTIST_ID);
                        if (start >= availableItems) {
                            Log.i(TAG, "startIteam exceeds the available item index");
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems, itemType,
                                    uid, type, playable, displayName, numAtt, attValues, attIds);
                            return;
                        }

                        if (DEBUG) Log.v(TAG, "availableItems: " + availableItems);
                        if (DEBUG) Log.v(TAG, "reqItems: " + reqItems);
                        availableItems = availableItems - start;
                        if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                            availableItems = MAX_BROWSE_ITEM_TO_SEND;
                        if (reqItems > availableItems)
                            reqItems = (int)availableItems;
                        if (DEBUG) Log.v(TAG, "revised reqItems: " + reqItems);

                        cursor = mContext.getContentResolver().query(
                            mMediaUri, mCursorCols,
                            MediaStore.Audio.Media.IS_MUSIC + "=1", null,
                            MediaStore.Audio.Artists.DEFAULT_SORT_ORDER);

                        int count = 0;
                        if (cursor != null) {
                            count = cursor.getCount();
                        } else {
                            Log.i(TAG, "Error: could not fetch the elements");
                            getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType,
                                    uid, type, playable, displayName, numAtt, attValues, attIds);
                            return;
                        }
                        if (count < reqItems) {
                            reqItems = count;
                        }
                        cursor.moveToFirst();
                        int index = 0;
                        long prevElem = -1;
                        long curElem = -1;
                        while ((reqItems > 0) && (count > 0)) {
                            curElem = cursor.getLong(cursor.getColumnIndexOrThrow(
                                                    MediaStore.Audio.Media.ARTIST_ID));
                            if (curElem != prevElem) {
                                if (start > 0) {
                                    --start;
                                } else {
                                    itemType[index] = TYPE_FOLDER_ITEM;
                                    uid[index] = cursor.getLong(cursor.getColumnIndexOrThrow(
                                                        MediaStore.Audio.Media.ARTIST_ID));
                                    type[index] = FOLDER_TYPE_ARTISTS;
                                    playable[index] = 0;
                                    displayName[index] = cursor.getString(
                                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                                    numAtt[index] = 0;
                                    index++;
                                    reqItems--;
                                }
                            }
                            prevElem = curElem;
                            cursor.moveToNext();
                            count--;
                        }
                        if (index > 0) {
                            numItems = index;
                            getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL, numItems, itemType,
                                uid, type, playable, displayName, numAtt, attValues, attIds);
                        } else {
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems, itemType,
                                uid, type, playable, displayName, numAtt, attValues, attIds);
                        }
                    } catch(Exception e) {
                        Log.i(TAG, "Exception e" + e);
                        getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType, uid, type,
                                        playable, displayName, numAtt, attValues, attIds);
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                } else {
                    long folderUid = Long.valueOf(mCurrentPathUid);
                    long availableItems = 0;
                    Cursor cursor = null;
                    try {
                        cursor = mContext.getContentResolver().query(
                            mMediaUri,
                            mCursorCols, MediaStore.Audio.Media.IS_MUSIC + "=1 AND " +
                            MediaStore.Audio.Media.ARTIST_ID + "=" + folderUid, null,
                            MediaStore.Audio.Artists.DEFAULT_SORT_ORDER);

                        if (cursor != null) {
                            availableItems = cursor.getCount();
                            if (start >= availableItems) {
                                Log.i(TAG, "startIteam exceeds the available item index");
                                getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems,
                                itemType, uid, type, playable, displayName, numAtt, attValues,
                                                                                        attIds);
                                return;
                            }
                            cursor.moveToFirst();
                            for (int i = 0; i < start; i++) {
                                cursor.moveToNext();
                            }
                        } else {
                            Log.i(TAG, "Error: could not fetch the elements");
                            getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType,
                                    uid, type, playable, displayName, numAtt, attValues, attIds);
                            return;
                        }

                        if (DEBUG) Log.v(TAG, "availableItems: " + availableItems);
                        if (DEBUG) Log.v(TAG, "reqItems: " + reqItems);
                        availableItems = availableItems - start;
                        if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                            availableItems = MAX_BROWSE_ITEM_TO_SEND;
                        if (reqItems > availableItems)
                            reqItems = availableItems;
                        if (DEBUG) Log.v(TAG, "revised reqItems: " + reqItems);

                        int attIndex;
                        int index;
                        for (index = 0; index < reqItems; index++) {
                            itemType[index] = TYPE_MEDIA_ELEMENT_ITEM;
                            uid[index] = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
                            type[index] = MEDIA_TYPE_AUDIO;
                            playable[index] = 0;
                            displayName[index] =
                                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.
                                                                        Audio.Media.TITLE));
                            int validAttrib = 0;
                            for (attIndex = 0; attIndex < numAttr; attIndex++) {
                                if ((attrs[attIndex] <= MEDIA_ATTR_MAX) &&
                                            (attrs[attIndex] >= MEDIA_ATTR_MIN)) {
                                    attValues[(7 * index) + attIndex] =
                                        getAttributeStringFromCursor(cursor, attrs[attIndex]);
                                    attIds[(7 * index) + attIndex] = attrs[attIndex];
                                    validAttrib ++;
                                }
                            }
                            numAtt[index] = (byte)validAttrib;
                            cursor.moveToNext();
                        }
                        numItems = index;
                        getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL, numItems, itemType,
                            uid, type, playable, displayName, numAtt, attValues, attIds);
                    } catch(Exception e) {
                        Log.i(TAG, "Exception e" + e);
                        getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType, uid,
                                        type, playable, displayName, numAtt, attValues, attIds);
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            } else if (mCurrentPath.equals(PATH_PLAYLISTS)) {
                if (mCurrentPathUid == null) {
                    long availableItems = 0;
                    Cursor cursor = null;
                    try {
                        availableItems = getNumPlaylistItems();
                        if (start >= availableItems) {
                            Log.i(TAG, "startIteam exceeds the available item index");
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems, itemType,
                                    uid, type, playable, displayName, numAtt, attValues, attIds);
                            return;
                        }

                        if (DEBUG) Log.v(TAG, "availableItems: " + availableItems);
                        if (DEBUG) Log.v(TAG, "reqItems: " + reqItems);
                        availableItems = availableItems - start;
                        if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                            availableItems = MAX_BROWSE_ITEM_TO_SEND;
                        if (reqItems > availableItems)
                            reqItems = (int)availableItems;
                        if (DEBUG) Log.v(TAG, "revised reqItems: " + reqItems);

                        String[] cols = new String[] {
                                MediaStore.Audio.Playlists._ID,
                                MediaStore.Audio.Playlists.NAME
                        };

                        cursor = mContext.getContentResolver().query(
                            MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                            cols, MediaStore.Audio.Playlists.NAME + " != ''", null,
                            MediaStore.Audio.Playlists.DEFAULT_SORT_ORDER);

                        int count = 0;
                        if (cursor != null) {
                            count = cursor.getCount();
                        } else {
                            Log.i(TAG, "Error: could not fetch the elements");
                            getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType,
                                uid, type, playable, displayName, numAtt, attValues, attIds);
                            return;
                        }
                        if (count < reqItems) {
                            reqItems = count;
                        }
                        cursor.moveToFirst();
                        int index = 0;
                        for (index = 0; index < reqItems; index++) {
                            itemType[index] = TYPE_FOLDER_ITEM;
                            uid[index] = cursor.getLong(cursor.getColumnIndexOrThrow(
                                                        MediaStore.Audio.Playlists._ID));
                            type[index] = FOLDER_TYPE_PLAYLISTS;
                            playable[index] = 0;
                            displayName[index] =
                                cursor.getString(cursor.getColumnIndexOrThrow(
                                                MediaStore.Audio.Playlists.NAME));
                            cursor.moveToNext();
                        }
                        numItems = index;

                        if (index > 0) {
                            numItems = index;
                            getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL, numItems,
                                itemType, uid, type, playable, displayName, numAtt,
                                attValues, attIds);
                        } else {
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems,
                                itemType, uid, type, playable, displayName, numAtt,
                                attValues, attIds);
                        }
                    } catch(Exception e) {
                        Log.i(TAG, "Exception e" + e);
                        getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType,
                            uid, type, playable, displayName, numAtt, attValues, attIds);
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                } else {
                    long folderUid = Long.valueOf(mCurrentPathUid);
                    long availableItems = 0;
                    Cursor cursor = null;

                    String[] playlistMemberCols = new String[] {
                            MediaStore.Audio.Playlists.Members._ID,
                            MediaStore.Audio.Media.TITLE,
                            MediaStore.Audio.Media.DATA,
                            MediaStore.Audio.Media.ALBUM,
                            MediaStore.Audio.Media.ARTIST,
                            MediaStore.Audio.Media.DURATION,
                            MediaStore.Audio.Playlists.Members.PLAY_ORDER,
                            MediaStore.Audio.Playlists.Members.AUDIO_ID,
                            MediaStore.Audio.Media.IS_MUSIC
                    };

                    try {
                        Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external",
                                                                                    folderUid);
                        StringBuilder where = new StringBuilder();
                        where.append(MediaStore.Audio.Media.TITLE + " != ''");
                        cursor = mContext.getContentResolver().query(uri, playlistMemberCols,
                                        where.toString(), null,
                                        MediaStore.Audio.Playlists.Members.DEFAULT_SORT_ORDER);

                        if (cursor != null) {
                            availableItems = cursor.getCount();
                            if (start >= availableItems) {
                                Log.i(TAG, "startIteam exceeds the available item index");
                                getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS, numItems,
                                    itemType, uid, type, playable, displayName, numAtt,
                                    attValues, attIds);
                                return;
                            }
                            cursor.moveToFirst();
                            for (int i = 0; i < start; i++) {
                                cursor.moveToNext();
                            }
                        } else {
                            Log.i(TAG, "Error: could not fetch the elements");
                            getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType,
                                    uid, type, playable, displayName, numAtt, attValues, attIds);
                            return;
                        }

                        if (DEBUG) Log.v(TAG, "availableItems: " + availableItems);
                        if (DEBUG) Log.v(TAG, "reqItems: " + reqItems);
                        availableItems = availableItems - start;
                        if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                            availableItems = MAX_BROWSE_ITEM_TO_SEND;
                        if (reqItems > availableItems)
                            reqItems = availableItems;
                        if (DEBUG) Log.v(TAG, "revised reqItems: " + reqItems);

                        int attIndex;
                        int index;
                        for (index = 0; index < reqItems; index++) {
                            itemType[index] = TYPE_MEDIA_ELEMENT_ITEM;
                            uid[index] = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.
                                                                Audio.Playlists.Members.AUDIO_ID));
                            type[index] = MEDIA_TYPE_AUDIO;
                            playable[index] = 0;
                            displayName[index] =
                                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.
                                                                        Audio.Media.TITLE));
                            int validAttrib = 0;
                            for (attIndex = 0; attIndex < numAttr; attIndex++) {
                                if ((attrs[attIndex] <= MEDIA_ATTR_MAX) &&
                                            (attrs[attIndex] >= MEDIA_ATTR_MIN)) {
                                    attValues[(7 * index) + attIndex] =
                                        getAttributeStringFromCursor(cursor, attrs[attIndex]);
                                    attIds[(7 * index) + attIndex] = attrs[attIndex];
                                    validAttrib ++;
                                }
                            }
                            numAtt[index] = (byte)validAttrib;
                            cursor.moveToNext();
                        }
                        numItems = index;
                        getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL, numItems, itemType, uid,
                                            type, playable, displayName, numAtt, attValues, attIds);
                    } catch(Exception e) {
                        Log.e(TAG, "Exception e" + e);
                        getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType, uid, type,
                                        playable, displayName, numAtt, attValues, attIds);
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            } else {
                getFolderItemsRspNative((byte)DOES_NOT_EXIST, numItems, itemType, uid, type,
                                playable, displayName, numAtt, attValues, attIds);
                Log.e(TAG, "GetFolderItems fail as player is not browsable");
            }
        } else if (scope == SCOPE_NOW_PLAYING) {
            if (mMediaPlayers.size() > 0) {
                final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
                while (rccIterator.hasNext()) {
                    final MediaPlayerInfo di = rccIterator.next();
                    if (di.GetPlayerFocus()) {
                        if (!di.IsRemoteAddressable()) {
                            getFolderItemsRspNative((byte)INTERNAL_ERROR, numItems, itemType,
                                uid, type, playable, displayName, numAtt, attValues, attIds);
                            Log.e(TAG, "GetFolderItems fails: addressed player is not browsable");
                            return;
                        }
                    }
                }
            }
            mRemoteController.getRemoteControlClientNowPlayingEntries();
            mCachedRequest = new CachedRequest(start, end, numAttr, attrs);
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
                    setAdressedPlayerRspNative ((byte)OPERATION_SUCCESSFUL);
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

            case EVT_NOW_PLAYING_CONTENT_CHANGED:
                if (DEBUG) Log.v(TAG, "Process EVT_NOW_PLAYING_CONTENT_CHANGED Interim");
                mNowPlayingContentChangedNT = NOTIFICATION_TYPE_INTERIM;
                registerNotificationRspNowPlayingContentChangedNative(mNowPlayingContentChangedNT);
                break;

            default:
                Log.v(TAG, "processRegisterNotification: Unhandled Type: " + eventId);
                break;
        }
    }

    private void handlePassthroughCmd(int id, int keyState) {
        switch (id) {
            case BluetoothAvrcp.PASSTHROUGH_ID_REWIND:
                rewind(keyState);
                break;
            case BluetoothAvrcp.PASSTHROUGH_ID_FAST_FOR:
                fastForward(keyState);
                break;
        }
    }

    private void changePositionBy(long amount) {
        long currentPosMs = getPlayPosition();
        if (currentPosMs == -1L) return;
        long newPosMs = Math.max(0L, currentPosMs + amount);
        mRemoteController.seekTo(newPosMs);
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

    private String getAttributeStringFromCursor(Cursor cursor, int attrId) {
        String attrStr = "<unknown>";
        switch (attrId) {
            case MEDIA_ATTR_TITLE:
                attrStr = cursor.getString(cursor.getColumnIndexOrThrow(
                                        MediaStore.Audio.Media.TITLE));
                break;
            case MEDIA_ATTR_ARTIST:
                attrStr = cursor.getString(cursor.getColumnIndexOrThrow(
                                        MediaStore.Audio.Media.ARTIST));
                break;
            case MEDIA_ATTR_ALBUM:
                attrStr = cursor.getString(cursor.getColumnIndexOrThrow(
                                        MediaStore.Audio.Media.ALBUM));
                break;
            case MEDIA_ATTR_PLAYING_TIME:
                attrStr = cursor.getString(cursor.getColumnIndexOrThrow(
                                        MediaStore.Audio.Media.DURATION));
                break;
            case MEDIA_ATTR_TRACK_NUM:
                if (mCurrentPath.equals(PATH_PLAYLISTS)) {
                    attrStr = cursor.getString(cursor.getColumnIndexOrThrow(
                                    MediaStore.Audio.Playlists.Members._ID));
                } else {
                    attrStr = String.valueOf(cursor.getLong(
                                cursor.getColumnIndexOrThrow("_id")));
                }
                break;
            case MEDIA_ATTR_NUM_TRACKS:
                attrStr = String.valueOf(cursor.getCount());
                break;
            case MEDIA_ATTR_GENRE:
                attrStr = "<unknown>"; // GENRE is not supported
                break;
            default:
                Log.v(TAG, "getAttributeStringFromCursor: wrong attribute: attrId = "
                                                                            + attrId);
                break;
        }
        if (attrStr == null) {
            attrStr = new String();
        }
        if (DEBUG) Log.v(TAG, "getAttributeStringFromCursor: attrId = "
                                            + attrId + " str = " + attrStr);
        return attrStr;
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
        if (DEBUG) Log.v(TAG, "updateLocalPlayerSettings");
        for (int i = 0; i < data.length; i += 2) {
            if (DEBUG) Log.v(TAG, "ID: " + data[i] + " Value: " + data[i+1]);
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

    private boolean checkPlayerAttributeResponse( byte[] data) {
        boolean ret = false;
        if (DEBUG) Log.v(TAG, "checkPlayerAttributeResponse");
        for (int i = 0; i < data.length; i += 2) {
            if (DEBUG) Log.v(TAG, "ID: " + data[i] + " Value: " + data[i+1]);
            switch (data[i]) {
                case ATTRIBUTE_EQUALIZER:
                    if (mPendingSetAttributes.contains(new Integer(ATTRIBUTE_EQUALIZER))) {
                        if(data[i+1] == ATTRIBUTE_NOTSUPPORTED) {
                            ret = false;
                        } else {
                            ret = true;
                        }
                    }
                break;
                case ATTRIBUTE_REPEATMODE:
                    if (mPendingSetAttributes.contains(new Integer(ATTRIBUTE_REPEATMODE))) {
                        if(data[i+1] == ATTRIBUTE_NOTSUPPORTED) {
                            ret = false;
                        } else {
                            ret = true;
                        }
                    }
                break;
                case ATTRIBUTE_SHUFFLEMODE:
                    if (mPendingSetAttributes.contains(new Integer(ATTRIBUTE_SHUFFLEMODE))) {
                        if(data[i+1] == ATTRIBUTE_NOTSUPPORTED) {
                            ret = false;
                        } else {
                            ret = true;
                        }
                    }
                break;
            }
        }
        mPendingSetAttributes.clear();
        return ret;
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
        if (DEBUG) Log.v(TAG, "setPlayerAppSetting " + num );
        byte[] array = new byte[num*2];
        for ( int i = 0; i < num; i++)
        {
            array[i] = attr_id[i] ;
            array[i+1] = attr_val[i];
            mPendingSetAttributes.add(new Integer(attr_id[i]));
        }
        Intent intent = new Intent(PLAYERSETTINGS_REQUEST);
        intent.putExtra(COMMAND, CMDSET);
        intent.putExtra(EXTRA_ATTRIB_VALUE_PAIRS, array);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        Message msg = mHandler.obtainMessage();
        msg.what = MESSAGE_PLAYERSETTINGS_TIMEOUT;
        msg.arg1 = SET_ATTRIBUTE_VALUES;
        mPendingCmds.add(new Integer(msg.arg1));
        mHandler.sendMessageDelayed(msg, 500);
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

    //PDU 0x16
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
    final static int MEDIA_ATTR_MIN = 1;
    final static int MEDIA_ATTR_TITLE = 1;
    final static int MEDIA_ATTR_ARTIST = 2;
    final static int MEDIA_ATTR_ALBUM = 3;
    final static int MEDIA_ATTR_TRACK_NUM = 4;
    final static int MEDIA_ATTR_NUM_TRACKS = 5;
    final static int MEDIA_ATTR_GENRE = 6;
    final static int MEDIA_ATTR_PLAYING_TIME = 7;
    final static int MEDIA_ATTR_MAX = 7;

    // match up with btrc_event_id_t enum of bt_rc.h
    final static int EVT_PLAY_STATUS_CHANGED = 1;
    final static int EVT_TRACK_CHANGED = 2;
    final static int EVT_TRACK_REACHED_END = 3;
    final static int EVT_TRACK_REACHED_START = 4;
    final static int EVT_PLAY_POS_CHANGED = 5;
    final static int EVT_BATT_STATUS_CHANGED = 6;
    final static int EVT_SYSTEM_STATUS_CHANGED = 7;
    final static int EVT_APP_SETTINGS_CHANGED = 8;
    final static int EVT_NOW_PLAYING_CONTENT_CHANGED = 9;
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
    final static int SCOPE_VIRTUAL_FILE_SYS = 0x01;
    final static int SCOPE_NOW_PLAYING = 0x03;

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
    private native boolean sendPassThroughCommandNative(int keyCode, int keyState);
    private native boolean registerNotificationRspAddressedPlayerChangedNative(
                                                                    int type, int playerId);
    private native boolean registerNotificationRspAvailablePlayersChangedNative(int type);
    private native boolean registerNotificationRspNowPlayingContentChangedNative(int type);
    private native boolean setAdressedPlayerRspNative(byte statusCode);
    private native boolean getMediaPlayerListRspNative(byte statusCode, int uidCounter,
                                    int itemCount, byte[] folderItems, int[] folderItemLengths);
    private native boolean getFolderItemsRspNative(byte statusCode, long numItems,
        int[] itemType, long[] uid, int[] type, byte[] playable, String[] displayName,
        byte[] numAtt, String[] attValues, int[] attIds);
    private native boolean getListPlayerappAttrRspNative(byte attr, byte[] attrIds);
    private native boolean getPlayerAppValueRspNative(byte numberattr, byte[]values );
    private native boolean SendCurrentPlayerValueRspNative(byte numberattr, byte[]attr );
    private native boolean SendSetPlayerAppRspNative(int attr_status);
    private native boolean sendSettingsTextRspNative(int num_attr, byte[] attr,
        int length, String[]text);
    private native boolean sendValueTextRspNative(int num_attr, byte[] attr,
        int length, String[]text);
    private native boolean registerNotificationPlayerAppRspNative(int type,
        byte numberattr, byte[]attr);
    private native boolean setBrowsedPlayerRspNative(byte statusCode, int uidCounter,
                            int itemCount, int folderDepth, int charId, String[] folderItems);
    private native boolean changePathRspNative(int status, long itemCount);
    private native boolean playItemRspNative(int status);
    private native boolean getItemAttrRspNative(byte numAttr, int[] attrIds,
        String[] textArray);

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
        private boolean mIsRemoteAddressable;

        // need to have the featuremask elements as int instead of byte, else MSB would be lost. Later need to take only
        // 8 applicable bits from LSB.
        private int[] mFeatureMask;
        private short mItemLength;
        private short mEntryLength;
        public MediaPlayerInfo(short playerId, byte majorPlayerType,
                    int playerSubType, byte playState, short charsetId,
                    short displayableNameLength, byte[] displayableName,
                    String playerPackageName, boolean isRemoteAddressable,
                    int[] featureMask ) {
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
            mIsRemoteAddressable = isRemoteAddressable;
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
                Log.v(TAG, "MediaPlayerInfo: mPlayerId = " + mPlayerId);
                Log.v(TAG, "mMajorPlayerType = " + mMajorPlayerType + " mPlayerSubType = "
                                                                        + mPlayerSubType);
                Log.v(TAG, "mPlayState = " + mPlayState + " mCharsetId = " + mCharsetId);
                Log.v(TAG, "mPlayerPackageName = " + mPlayerPackageName +
                            " mDisplayableNameLength = " + mDisplayableNameLength);
                Log.v(TAG, "mItemLength = " + mItemLength + " mEntryLength = " + mEntryLength);
                Log.v(TAG, "mFeatureMask = ");
                for (int count = 0; count < FEATURE_BITMASK_FIELD_LENGTH; count ++) {
                    Log.v(TAG, "mFeatureMask[" + count + "] = " + mFeatureMask[count]);
                }
                Log.v(TAG, "mDisplayableName=");
                for (int count = 0; count < mDisplayableNameLength; count ++) {
                    Log.v(TAG, "mDisplayableName[" + count + "] = " + mDisplayableName[count]);
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
            mMetadata.genre = metaData.genre;
            mMetadata.tracknum = metaData.tracknum;
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

        public boolean IsPlayerBrowsable() {
            if ((mFeatureMask[FEATURE_MASK_BROWSE_OFFSET] & FEATURE_MASK_BROWSE_MASK)
                                                                                != 0) {
                Log.v(TAG, "Player ID: " + mPlayerId + "is Browsable!");
                return true;
            } else {
                Log.v(TAG, "Player ID: " + mPlayerId + "is not Browsable!");
                return false;
            }
        }

        public boolean IsPlayerBrowsableWhenAddressed() {
            if ((mFeatureMask[FEATURE_MASK_BR_WH_ADDR_OFFSET] &
                                    FEATURE_MASK_BR_WH_ADDR_MASK) != 0) {
                Log.v(TAG, "Player ID: " + mPlayerId + "is Browsable only when addressed!");
                return true;
            } else {
                Log.v(TAG, "Player ID: " + mPlayerId + "is always Browsable!");
                return false;
            }
        }
        /*This is set for the players which can be addressed by peer using setAddressedPlayer
            command*/
        public boolean IsRemoteAddressable() {
            return mIsRemoteAddressable;
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
