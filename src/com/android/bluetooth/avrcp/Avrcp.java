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

package com.android.bluetooth.avrcp;

import java.util.Timer;
import java.util.TimerTask;

import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAvrcp;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
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

import com.android.bluetooth.R;
import android.content.BroadcastReceiver;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.Utils;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Iterator;

import android.provider.MediaStore;
import android.content.ContentResolver;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;

import android.app.Notification;
import android.app.NotificationManager;

import android.media.AudioManager;

/**
 * support Bluetooth AVRCP profile.
 * support metadata, play status and event notification
 */
public final class Avrcp {
    private static final boolean DEBUG = true;
    private static final String TAG = "Avrcp";
    private static final String ABSOLUTE_VOLUME_BLACKLIST = "absolute_volume_blacklist";

    private Context mContext;
    private final AudioManager mAudioManager;
    private A2dpService mA2dpService;
    private AvrcpMessageHandler mHandler;
    private MediaSessionManager mMediaSessionManager;
    private MediaSessionChangeListener mSessionChangeListener;
    private MediaController mMediaController;
    private MediaControllerListener mMediaControllerCb;
    private MediaAttributes mMediaAttributes;
    private int mTransportControlFlags;
    private PlaybackState mCurrentPlayerState;
    private int mPlayStatusChangedNT;
    private int mTrackChangedNT;
    private long mCurrentPosMs;
    private long mPlayStartTimeMs;
    private long mTrackNumber;
    private long mSongLengthMs;
    private long mPlaybackIntervalMs;
    private int mPlayPosChangedNT;
    private long mSkipStartTime;
    private int mFeatures;
    
    Resources mResources;

    private int mLastDirection;
    private int mVolumeStep;
    private final int mAudioStreamMax;
    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;
    private int mSkipAmount;
    private final BluetoothAdapter mAdapter;
    private static Uri mMediaUriStatic;
    private static long currentTrackPos;
    private static boolean updatePlayTime;
    private static boolean updateValues;
    private int mAddressedPlayerId;

    /* BTRC features */
    public static final int BTRC_FEAT_METADATA = 0x01;
    public static final int BTRC_FEAT_ABSOLUTE_VOLUME = 0x02;
    public static final int BTRC_FEAT_BROWSE = 0x04;
    public static final int BTRC_FEAT_AVRC_UI_UPDATE = 0x08;

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

    private CachedRequest mCachedRequest = null;

    private static final int MSG_UPDATE_AVAILABLE_PLAYERS = 201;
    private static final int MSG_UPDATE_ADDRESSED_PLAYER = 202;
    private static final int MSG_UPDATE_RCC_CHANGE = 203;
    private static final int MSG_UPDATE_BROWSED_PLAYER_FOLDER = 204;
    private static final int MSG_UPDATE_NOW_PLAYING_CONTENT_CHANGED = 205;
    private static final int MSG_PLAY_ITEM_RESPONSE = 206;
    private static final int MSG_NOW_PLAYING_ENTRIES_RECEIVED = 207;

    private MediaPlayerInfo mediaPlayerInfo1;

    private static final int BUTTON_TIMEOUT_TIME = 2000;
    private static final int BASE_SKIP_AMOUNT = 2000;
    private static final int KEY_STATE_PRESS = 1;
    private static final int KEY_STATE_RELEASE = 0;
    private static final int SKIP_PERIOD = 400;
    private static final int SKIP_DOUBLE_INTERVAL = 3000;
    private static final long MAX_MULTIPLIER_VALUE = 128L;
    private static final int CMD_TIMEOUT_DELAY = 2000;
    private static final int MAX_ERROR_RETRY_TIMES = 6;
    private static final int AVRCP_MAX_VOL = 127;
    private static final int AVRCP_BASE_VOLUME_STEP = 1;
    private final static int MESSAGE_PLAYERSETTINGS_TIMEOUT = 602;

    private static final int AVRCP_CONNECTED = 1;
    public  static final int KEY_STATE_PRESSED = 0;
    public  static final int KEY_STATE_RELEASED = 1;

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
    private static final String PLAYERSETTINGS_REQUEST =
            "org.codeaurora.music.playersettingsrequest";
    private static final String PLAYERSETTINGS_RESPONSE =
           "org.codeaurora.music.playersettingsresponse";
    // Max number of Avrcp connections at any time
    private int maxAvrcpConnections = 1;
    BluetoothDevice mBrowserDevice = null;
    private static final int INVALID_DEVICE_INDEX = 0xFF;
    // codes for reset of of notifications
    private static final int PLAY_POSITION_CHANGE_NOTIFICATION = 101;
    private static final int PLAY_STATUS_CHANGE_NOTIFICATION = 102;
    private static final int TRACK_CHANGE_NOTIFICATION = 103;
    private static final int NOW_PALYING_CONTENT_CHANGED_NOTIFICATION = 104;

    private static final int INVALID_ADDRESSED_PLAYER_ID = -1;
    // Device dependent registered Notification & Variables
    private class DeviceDependentFeature {
        private BluetoothDevice mCurrentDevice;
        private PlaybackState mCurrentPlayState;
        private int mPlayStatusChangedNT;
        private int mPlayerStatusChangeNT;
        private int mTrackChangedNT;
        private long mNextPosMs;
        private long mPrevPosMs;
        private long mPlaybackIntervalMs;
        private int mPlayPosChangedNT;
        private int mFeatures;
        private int mAbsoluteVolume;
        private int mLastSetVolume;
        private int mLastDirection;
        private boolean mVolCmdSetInProgress;
        private boolean mVolCmdAdjustInProgress;
        private int mAbsVolRetryTimes;
        private int keyPressState;
        private int mAddressedPlayerChangedNT;
        private int mAvailablePlayersChangedNT;
        private int mNowPlayingContentChangedNT;
        private String mRequestedAddressedPlayerPackageName;
        private String mCurrentPath;
        private String mCurrentPathUid;
        private Uri mMediaUri;
        private boolean isMusicAppResponsePending;
        private boolean isBrowsingSupported;
        private boolean isAbsoluteVolumeSupportingDevice;

        private int mRemoteVolume;
        private int mLastRemoteVolume;
        private int mInitialRemoteVolume;

        /* Local volume in audio index 0-15 */
        private int mLocalVolume;
        private int mLastLocalVolume;
        private int mAbsVolThreshold;
        private HashMap<Integer, Integer> mVolumeMapping;

        public DeviceDependentFeature() {
            mCurrentDevice = null;
            mCurrentPlayState = new PlaybackState.Builder().setState(PlaybackState.STATE_NONE, -1L, 0.0f).build();;
            mPlayStatusChangedNT = NOTIFICATION_TYPE_CHANGED;
            mPlayerStatusChangeNT = NOTIFICATION_TYPE_CHANGED;
            mTrackChangedNT = NOTIFICATION_TYPE_CHANGED;
            mPlaybackIntervalMs = 0L;
            mPlayPosChangedNT = NOTIFICATION_TYPE_CHANGED;
            mFeatures = 0;
            mAbsoluteVolume = -1;
            mLastSetVolume = -1;
            mLastDirection = 0;
            mVolCmdAdjustInProgress = false;
            mVolCmdSetInProgress = false;
            mAbsVolRetryTimes = 0;
            keyPressState = KEY_STATE_RELEASE; //Key release state
            mAddressedPlayerChangedNT = NOTIFICATION_TYPE_CHANGED;
            mAvailablePlayersChangedNT = NOTIFICATION_TYPE_CHANGED;
            mNowPlayingContentChangedNT = NOTIFICATION_TYPE_CHANGED;
            mRequestedAddressedPlayerPackageName = null;
            mCurrentPath = PATH_INVALID;
            mCurrentPathUid = null;
            mMediaUri = Uri.EMPTY;
            isMusicAppResponsePending = false;
            isBrowsingSupported = false;
            isAbsoluteVolumeSupportingDevice = false;
            mRemoteVolume = -1;
            mInitialRemoteVolume = -1;
            mLastRemoteVolume = -1;
            mLastDirection = 0;
            mVolCmdAdjustInProgress = false;
            mVolCmdSetInProgress = false;
            mAbsVolRetryTimes = 0;
            mLocalVolume = -1;
            mLastLocalVolume = -1;
            mAbsVolThreshold = 0;
            mVolumeMapping = new HashMap<Integer, Integer>();
            if (mResources != null) {
                mAbsVolThreshold = mResources.getInteger(R.integer.a2dp_absolute_volume_initial_threshold);
            }
        }
    };

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
    DeviceDependentFeature[] deviceFeatures;

    static {
        classInitNative();
    }

    private Avrcp(Context context, A2dpService svc, int maxConnections ) {
        if (DEBUG)
            Log.v(TAG, "Avrcp");
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mMediaAttributes = new MediaAttributes(null);
        mCurrentPlayerState = new PlaybackState.Builder().setState(PlaybackState.STATE_NONE, -1L, 0.0f).build();
        mTrackNumber = -1L;
        mCurrentPosMs = PlaybackState.PLAYBACK_POSITION_UNKNOWN;
        mPlayStartTimeMs = -1L;
        mSongLengthMs = 0L;
        mA2dpService = svc;
        maxAvrcpConnections = maxConnections;
        deviceFeatures = new DeviceDependentFeature[maxAvrcpConnections];
        mAddressedPlayerId = INVALID_ADDRESSED_PLAYER_ID;
        for(int i = 0; i < maxAvrcpConnections; i++) {
            deviceFeatures[i] = new DeviceDependentFeature();
        }
        mContext = context;

        initNative(maxConnections);

        mMediaSessionManager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mResources = context.getResources();
        mAudioStreamMax = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        mVolumeStep = Math.max(AVRCP_BASE_VOLUME_STEP, AVRCP_MAX_VOL/mAudioStreamMax);
    }

    private void start() {
        if (DEBUG)
            Log.v(TAG, "start");
        HandlerThread thread = new HandlerThread("BluetoothAvrcpHandler");
        thread.start();
        Looper looper = thread.getLooper();
        mHandler = new AvrcpMessageHandler(looper);

        mSessionChangeListener = new MediaSessionChangeListener();
        mMediaSessionManager.addOnActiveSessionsChangedListener(mSessionChangeListener, null, mHandler);
        List<MediaController> sessions = mMediaSessionManager.getActiveSessions(null);
        mMediaControllerCb = new MediaControllerListener();
        if (sessions.size() > 0) {
            final Iterator<MediaController> mcIterator = sessions.iterator();
            while (mcIterator.hasNext()) {
                final MediaController player = mcIterator.next();
                if (mHandler != null) {
                    Log.v(TAG, "Availability change for player: " + player.getPackageName());
                    mHandler.obtainMessage(MSG_UPDATE_RCC_CHANGE, 0, 1,
                                        player.getPackageName()).sendToTarget();
                }
            }
            updateCurrentMediaController(sessions.get(0));
        }
        mPendingCmds = new ArrayList<Integer>();
        mPendingSetAttributes = new ArrayList<Integer>();
        // clear path for all devices
        for (int i = 0; i < maxAvrcpConnections; i++) {
           deviceFeatures[i].mCurrentPath = PATH_INVALID;
           deviceFeatures[i].mCurrentPathUid = null;
           deviceFeatures[i].mMediaUri = Uri.EMPTY;
        }
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
                String callingPackageName =
                        intent.getStringExtra(AudioManager.EXTRA_CALLING_PACKAGE_NAME);
                boolean isFocussed =
                        intent.getBooleanExtra(AudioManager.EXTRA_FOCUS_CHANGED_VALUE,
                        false);
                boolean isAvailable =
                        intent.getBooleanExtra(AudioManager.EXTRA_AVAILABLITY_CHANGED_VALUE,
                        false);
                if (isFocussed)
                    isRCCFocussed = 1;
                if (isAvailable)
                    isRCCAvailable = 1;
                Log.v(TAG, "focus: " + isFocussed + " , availability: " + isAvailable);
                if (mHandler != null) {
                    mHandler.obtainMessage(MSG_UPDATE_RCC_CHANGE, isRCCFocussed,
                            isRCCAvailable, callingPackageName).sendToTarget();
                }
            } else if (action.equals(PLAYERSETTINGS_RESPONSE)) {
                int getResponse = intent.getIntExtra(EXTRA_GET_RESPONSE,
                                                      GET_INVALID);
                byte [] data;
                String [] text;
                boolean isSetAttrValRsp = false;
                BluetoothDevice device = null;

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
                for (int i = 0; i < maxAvrcpConnections; i++) {
                    if (deviceFeatures[i].isMusicAppResponsePending ==
                            true) {
                        device = deviceFeatures[i].mCurrentDevice;
                        deviceFeatures[i].isMusicAppResponsePending = false;
                        break;
                    }
                }

                if (DEBUG)
                    Log.v(TAG,"getResponse" + getResponse);
                switch (getResponse) {
                    case GET_ATTRIBUTE_IDS:
                        if (device == null) {
                            Log.e(TAG,"ERROR!!! device is null");
                            return;
                        }
                        data = intent.getByteArrayExtra(EXTRA_ATTIBUTE_ID_ARRAY);
                        byte numAttr = (byte) data.length;
                        if (DEBUG)
                            Log.v(TAG,"GET_ATTRIBUTE_IDS");
                        getListPlayerappAttrRspNative(numAttr ,
                                data ,getByteAddress(device));

                    break;
                    case GET_VALUE_IDS:
                        if (device == null) {
                            Log.e(TAG,"ERROR!!! device is null");
                            return;
                        }
                        data = intent.getByteArrayExtra(EXTRA_VALUE_ID_ARRAY);
                        numAttr = (byte) data.length;
                        if (DEBUG)
                            Log.v(TAG,"GET_VALUE_IDS" + numAttr);
                        getPlayerAppValueRspNative(numAttr, data,
                                getByteAddress(device));
                    break;
                    case GET_ATTRIBUTE_VALUES:
                        if (device == null) {
                            Log.e(TAG,"ERROR!!! device is null");
                            return;
                        }
                        data = intent.getByteArrayExtra(EXTRA_ATTRIB_VALUE_PAIRS);
                        updateLocalPlayerSettings(data);
                        numAttr = (byte) data.length;
                        if (DEBUG)
                            Log.v(TAG,"GET_ATTRIBUTE_VALUES" + numAttr);
                        SendCurrentPlayerValueRspNative(numAttr ,
                                data, getByteAddress(device));
                    break;
                    case SET_ATTRIBUTE_VALUES:
                        data = intent.getByteArrayExtra(EXTRA_ATTRIB_VALUE_PAIRS);
                        updateLocalPlayerSettings(data);
                        if (isSetAttrValRsp) {
                            isSetAttrValRsp = false;
                            for (int i = 0; i < maxAvrcpConnections; i++) {
                                if (deviceFeatures[i].mCurrentDevice != null)  {
                                    Log.v(TAG,"Respond to SET_ATTRIBUTE_VALUES request");
                                    if (checkPlayerAttributeResponse(data)) {
                                        SendSetPlayerAppRspNative(OPERATION_SUCCESSFUL,
                                                getByteAddress(deviceFeatures[i].mCurrentDevice));
                                    } else {
                                        SendSetPlayerAppRspNative(INTERNAL_ERROR,
                                                getByteAddress(deviceFeatures[i].mCurrentDevice));
                                    }
                                }
                            }
                            mPendingSetAttributes.clear();
                        }
                        for (int i = 0; i < maxAvrcpConnections; i++) {
                            if (deviceFeatures[i].mPlayerStatusChangeNT ==
                                    NOTIFICATION_TYPE_INTERIM) {
                                Log.v(TAG,"device has registered for"+
                                    "mPlayerStatusChangeNT");
                                deviceFeatures[i].mPlayerStatusChangeNT =
                                        NOTIFICATION_TYPE_CHANGED;
                                sendPlayerAppChangedRsp(deviceFeatures[i].mPlayerStatusChangeNT,
                                        deviceFeatures[i].mCurrentDevice);
                               } else {
                                   Log.v(TAG,"Drop Set Attr Val update from media player");
                            }
                        }
                    break;
                    case GET_ATTRIBUTE_TEXT:
                        text = intent.getStringArrayExtra(EXTRA_ATTRIBUTE_STRING_ARRAY);
                        if (device == null) {
                            Log.e(TAG,"ERROR!!! device is null");
                            return;
                        }
                        sendSettingsTextRspNative(mPlayerSettings.attrIds.length ,
                                mPlayerSettings.attrIds ,text.length,
                                text, getByteAddress(device));
                        if (DEBUG)
                            Log.v(TAG,"mPlayerSettings.attrIds"
                                    + mPlayerSettings.attrIds.length);
                    break;
                    case GET_VALUE_TEXT:
                        text = intent.getStringArrayExtra(EXTRA_VALUE_STRING_ARRAY);
                        if (device == null) {
                            Log.e(TAG,"ERROR!!! device is null");
                            return;
                        }
                        sendValueTextRspNative(mPlayerSettings.attrIds.length ,
                                mPlayerSettings.attrIds,
                                text.length, text,
                                getByteAddress(device));
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
        if (DEBUG)
            Log.v(TAG, "registerMediaPlayers");
        int[] featureMasks = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

        byte[] playerName1 = {0x4d, 0x75, 0x73, 0x69, 0x63}/*Music*/;

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

        mediaPlayerInfo1 = new MediaPlayerInfo ((short)0x0001,
                    MAJOR_TYPE_AUDIO,
                    SUB_TYPE_NONE,
                    (byte)PlaybackState.STATE_PAUSED,
                    CHAR_SET_UTF8,
                    (short)0x05,
                    playerName1,
                    "com.android.music",
                    true,
                    featureMasks);
        mMediaPlayers.add(mediaPlayerInfo1);
    }

    public static Avrcp make(Context context, A2dpService svc,
            int maxConnections) {
        if (DEBUG)
            Log.v(TAG, "make");
        Avrcp ar = new Avrcp(context, svc, maxConnections);
        ar.start();
        return ar;
    }

    public void doQuit() {
        if (DEBUG)
            Log.v(TAG, "doQuit");
        mHandler.removeCallbacksAndMessages(null);
        Looper looper = mHandler.getLooper();
        if (looper != null) {
            looper.quit();
        }
        mMediaSessionManager.removeOnActiveSessionsChangedListener(mSessionChangeListener);
        clearDeviceDependentFeature();
        for (int i = 0; i < maxAvrcpConnections; i++) {
            cleanupDeviceFeaturesIndex(i);
        }
        try {
            mContext.unregisterReceiver(mIntentReceiver);
        } catch (Exception e) {
            Log.e(TAG,"Unable to unregister Avrcp receiver", e);
        }
        mMediaPlayers.clear();
        if (mHandler.hasMessages(MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT)) {
            mHandler.removeMessages(MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT);
            if (DEBUG)
                Log.v(TAG, "Addressed player message cleanup as part of doQuit");
        }
    }

    public void clearDeviceDependentFeature() {
        for (int i = 0; i < maxAvrcpConnections; i++) {
            deviceFeatures[i].keyPressState = KEY_STATE_RELEASE; //Key release state
            deviceFeatures[i].mCurrentPath = PATH_INVALID;
            deviceFeatures[i].mMediaUri = Uri.EMPTY;
            deviceFeatures[i].mCurrentPathUid = null;
            deviceFeatures[i].mRequestedAddressedPlayerPackageName = null;
            if (deviceFeatures[i].mVolumeMapping != null)
                deviceFeatures[i].mVolumeMapping.clear();
        }
    }

    public void cleanup() {
        if (DEBUG)
            Log.v(TAG, "cleanup");
        cleanupNative();
    }

    private class MediaControllerListener extends MediaController.Callback {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            Log.v(TAG, "MediaController metadata changed");
            updateMetadata(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            Log.v(TAG, "MediaController playback changed: " + state.toString());
            updatePlayPauseState(state, null);
        }

        @Override
        public void onSessionDestroyed() {
            Log.v(TAG, "MediaController session destroyed");
        }

        @Override
        public void onUpdateFolderInfoBrowsedPlayer(String stringUri) {
            Log.v(TAG, "onClientFolderInfoBrowsedPlayer: stringUri: " + stringUri);
            if (stringUri != null) {
                String[] ExternalPath = stringUri.split("/");
                if (ExternalPath.length < 4) {
                    Log.d(TAG, "Wrong entries.");
                    mHandler.obtainMessage(MSG_UPDATE_BROWSED_PLAYER_FOLDER, 0, INTERNAL_ERROR,
                                                                  null).sendToTarget();
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
                mMediaUriStatic = uri;
                if (mHandler != null) {
                    mHandler.obtainMessage(MSG_UPDATE_BROWSED_PLAYER_FOLDER, NUM_ROOT_ELEMENTS,
                                                OPERATION_SUCCESSFUL, SplitPath).sendToTarget();
                }
            } else {
                mHandler.obtainMessage(MSG_UPDATE_BROWSED_PLAYER_FOLDER, 0, INTERNAL_ERROR,
                                                                  null).sendToTarget();
            }
        }

        @Override
        public void onUpdateNowPlayingEntries(long[] playList) {
            Log.v(TAG, "onClientUpdateNowPlayingEntries");
            if (mHandler != null) {
                mHandler.obtainMessage(MSG_NOW_PLAYING_ENTRIES_RECEIVED, 0, 0,
                                                            playList).sendToTarget();
            }
        }

        @Override
        public void onUpdateNowPlayingContentChange() {
            Log.v(TAG, "onClientNowPlayingContentChange");
            if (mHandler != null) {
                mHandler.obtainMessage(MSG_UPDATE_NOW_PLAYING_CONTENT_CHANGED).sendToTarget();
            }
        }

        @Override
        public void onPlayItemResponse(boolean success) {
            Log.v(TAG, "onClientPlayItemResponse");
            if (mHandler != null) {
                mHandler.obtainMessage(MSG_PLAY_ITEM_RESPONSE, 0, 0, new Boolean(success))
                                                                            .sendToTarget();
            }
        }
    }

    private class MediaSessionChangeListener implements MediaSessionManager.OnActiveSessionsChangedListener {
        public MediaSessionChangeListener() {
        }

        @Override
        public void onActiveSessionsChanged(List<MediaController> controllers) {
            Log.v(TAG, "Active sessions changed, " + controllers.size() + " sessions");
            if (controllers.size() > 0) {
                updateCurrentMediaController(controllers.get(0));
            }
        }
    }

    private void updateCurrentMediaController(MediaController controller) {
        Log.v(TAG, "Updating media controller to " + controller);
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mMediaControllerCb);
        }
        mMediaController = controller;
        if (mMediaController == null) {
            updateMetadata(null);
            updatePlayPauseState(null, null);
            return;
        }
        mMediaController.registerCallback(mMediaControllerCb, mHandler);
        updateMetadata(mMediaController.getMetadata());
        updatePlayPauseState(mMediaController.getPlaybackState(),null);
        if (mHandler != null) {
            Log.v(TAG, "Focus gained for player: " + mMediaController.getPackageName());
            mHandler.obtainMessage(MSG_UPDATE_RCC_CHANGE, 1, 1,
                                mMediaController.getPackageName()).sendToTarget();
        }
    }


    /** Handles Avrcp messages. */
    private final class AvrcpMessageHandler extends Handler {
        private AvrcpMessageHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            int deviceIndex  = INVALID_DEVICE_INDEX;
            switch (msg.what) {
                case MESSAGE_PLAYERSETTINGS_TIMEOUT:
                    Log.e(TAG, "**MESSAGE_PLAYSTATUS_TIMEOUT: Addr: " +
                                (String)msg.obj + " Msg: " + msg.arg1);
                    synchronized (mPendingCmds) {
                        Integer val = new Integer(msg.arg1);
                        if (!mPendingCmds.contains(val)) {
                            break;
                        }
                        mPendingCmds.remove(val);
                    }
                    switch (msg.arg1) {
                    case GET_ATTRIBUTE_IDS:
                        getListPlayerappAttrRspNative((byte)def_attrib.length ,
                                def_attrib, getByteAddress(
                                mAdapter.getRemoteDevice((String) msg.obj)));
                    break;
                    case GET_VALUE_IDS:
                        switch (mPlayerSettings.attr) {
                            case ATTRIBUTE_REPEATMODE:
                                getPlayerAppValueRspNative((byte)value_repmode.length,
                                        value_repmode,
                                        getByteAddress(mAdapter.getRemoteDevice(
                                        (String) msg.obj)));
                            break;
                            case ATTRIBUTE_SHUFFLEMODE:
                                getPlayerAppValueRspNative((byte)value_shufmode.length,
                                        value_shufmode,
                                        getByteAddress(mAdapter.getRemoteDevice(
                                        (String) msg.obj)));
                            break;
                            default:
                                getPlayerAppValueRspNative((byte)value_default.length,
                                        value_default,
                                        getByteAddress(mAdapter.getRemoteDevice(
                                        (String) msg.obj)));
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
                        SendCurrentPlayerValueRspNative((byte)retVal.length ,
                                retVal, getByteAddress(mAdapter.getRemoteDevice(
                                (String) msg.obj)));
                    break;
                    case SET_ATTRIBUTE_VALUES :
                        SendSetPlayerAppRspNative(INTERNAL_ERROR, getByteAddress(
                                mAdapter.getRemoteDevice((String) msg.obj)));
                    break;
                    case GET_ATTRIBUTE_TEXT:
                        String [] attribText = new String [mPlayerSettings.attrIds.length];
                        for (int i = 0; i < mPlayerSettings.attrIds.length; i++) {
                            attribText[i] = "";
                        }
                        sendSettingsTextRspNative(mPlayerSettings.attrIds.length ,
                                mPlayerSettings.attrIds, attribText.length,
                                attribText, getByteAddress(mAdapter.getRemoteDevice(
                                (String) msg.obj)));
                    break;
                    case GET_VALUE_TEXT:
                        String [] valueText = new String [mPlayerSettings.attrIds.length];
                        for (int i = 0; i < mPlayerSettings.attrIds.length; i++) {
                            valueText[i] = "";
                        }
                        sendValueTextRspNative(mPlayerSettings.attrIds.length ,
                                mPlayerSettings.attrIds, valueText.length,
                                valueText,getByteAddress(mAdapter.getRemoteDevice(
                                (String) msg.obj)));
                    break;
                    default :
                        Log.e(TAG,"in default case");
                    break;
                }
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

            case MESSAGE_GET_RC_FEATURES:
            {
                String address = (String) msg.obj;
                if (DEBUG)
                    Log.v(TAG, "MESSAGE_GET_RC_FEATURES: address="+address+
                            ", features="+msg.arg1);
                BluetoothDevice device = mAdapter.getRemoteDevice(address);
                deviceIndex = getIndexForDevice(device);
                if (deviceIndex == INVALID_DEVICE_INDEX) {
                    Log.v(TAG,"device entry not present, bailing out");
                    return;
                }
                deviceFeatures[deviceIndex].mFeatures = msg.arg1;
                deviceFeatures[deviceIndex].mFeatures = 
                    modifyRcFeatureFromBlacklist(deviceFeatures[deviceIndex].mFeatures,
                    address);
                deviceFeatures[deviceIndex].isAbsoluteVolumeSupportingDevice =
                        ((deviceFeatures[deviceIndex].mFeatures &
                        BTRC_FEAT_ABSOLUTE_VOLUME) != 0);
                mAudioManager.avrcpSupportsAbsoluteVolume(device.getAddress(),
                        isAbsoluteVolumeSupported());
                Log.v(TAG," update audio manager for abs vol state = "
                        + isAbsoluteVolumeSupported());
                deviceFeatures[deviceIndex].mLastLocalVolume = -1;
                deviceFeatures[deviceIndex].mRemoteVolume = -1;
                deviceFeatures[deviceIndex].mLocalVolume = -1;
                deviceFeatures[deviceIndex].mInitialRemoteVolume = -1;
                if (deviceFeatures[deviceIndex].mVolumeMapping != null)
                    deviceFeatures[deviceIndex].mVolumeMapping.clear();

                if ((deviceFeatures[deviceIndex].mFeatures &
                        BTRC_FEAT_AVRC_UI_UPDATE) != 0)
                {
                    int NOTIFICATION_ID = android.R.drawable.stat_sys_data_bluetooth;
                    Notification notification = new Notification.Builder(mContext)
                        .setContentTitle("Bluetooth Media Browsing")
                        .setContentText("Peer supports advanced feature")
                        .setSubText("Re-pair from peer to enable it")
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setDefaults(Notification.DEFAULT_ALL)
                        .build();
                    NotificationManager manager = (NotificationManager)
                        mContext.getSystemService(Context.NOTIFICATION_SERVICE);
                    manager.notify(NOTIFICATION_ID, notification);
                    Log.v(TAG," update notification manager on remote repair request");
                }
                break;
            }
            case MESSAGE_GET_PLAY_STATUS:
            {
                BluetoothDevice device;
                int playState, position;
                if (DEBUG)
                    Log.v(TAG, "MESSAGE_GET_PLAY_STATUS");
                Log.v(TAG, "Event for device address " + (String)msg.obj);

                device = mAdapter.getRemoteDevice((String) msg.obj);
                deviceIndex = getIndexForDevice(device);
                if (deviceIndex == INVALID_DEVICE_INDEX) {
                    Log.e(TAG,"Invalid device index for play status");
                    break;
                }
                playState = convertPlayStateToPlayStatus(deviceFeatures[deviceIndex].mCurrentPlayState);
                position = (int)getPlayPosition(device);
                Log.v(TAG, "Play Status for : " + device.getName() +
                    " state: " + playState + " position: " + position);
                if (position == -1) {
                    Log.v(TAG, "Force play postion to 0, for getPlayStatus Rsp");
                    position = 0;
                }

                getPlayStatusRspNative(playState, (int)mSongLengthMs, position,
                        getByteAddress(device));
                break;
            }
            case MESSAGE_GET_ELEM_ATTRS:
                String[] textArray;
                int[] attrIds;
                byte numAttr = (byte) msg.arg1;
                ItemAttr itemAttr = (ItemAttr)msg.obj;
                Log.v(TAG, "event for device address " + itemAttr.mAddress);
                ArrayList<Integer> attrList = itemAttr.mAttrList;
                if (DEBUG)
                    Log.v(TAG, "MESSAGE_GET_ELEM_ATTRS:numAttr=" + numAttr);
                attrIds = new int[numAttr];
                textArray = new String[numAttr];
                for (int i = 0; i < numAttr; ++i) {
                    attrIds[i] = attrList.get(i).intValue();
                    textArray[i] = mMediaAttributes.getString(attrIds[i]);
                    Log.v(TAG, "getAttributeString:attrId=" + attrIds[i] +
                               " str=" + textArray[i]);
                }
                getElementAttrRspNative(numAttr ,attrIds ,textArray ,
                        getByteAddress(mAdapter.getRemoteDevice(itemAttr.mAddress)));
                break;

            case MESSAGE_REGISTER_NOTIFICATION:
                if (DEBUG)
                    Log.v(TAG, "MESSAGE_REGISTER_NOTIFICATION:event=" + msg.arg1 +
                                      " param=" + msg.arg2);
                processRegisterNotification(msg.arg1, msg.arg2, (String) msg.obj);
                break;

            case MESSAGE_PLAY_INTERVAL_TIMEOUT:
                if (DEBUG)
                    Log.v(TAG, "MESSAGE_PLAY_INTERVAL_TIMEOUT");
                Log.v(TAG, "event for device address " + (BluetoothDevice)msg.obj);
                deviceIndex = getIndexForDevice((BluetoothDevice) msg.obj);
                if (deviceIndex == INVALID_DEVICE_INDEX) {
                    Log.e(TAG,"invalid index for device");
                    break;
                }
                deviceFeatures[deviceIndex].mPlayPosChangedNT =
                         NOTIFICATION_TYPE_CHANGED;
                Log.v(TAG, "event for device address " + (BluetoothDevice) msg.obj);
                registerNotificationRspPlayPosNative(deviceFeatures[deviceIndex].mPlayPosChangedNT,
                        (int)getPlayPosition((BluetoothDevice) msg.obj) ,
                        getByteAddress((BluetoothDevice) msg.obj));
                break;

            case MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT:
                if (DEBUG)
                    Log.v(TAG, "setAddressedPlayer fails, Times out");
                deviceIndex = getIndexForDevice(mAdapter.getRemoteDevice((String) msg.obj));
                if (deviceIndex == INVALID_DEVICE_INDEX) {
                    Log.e(TAG,"invalid device index");
                    break;
                }
                Log.v(TAG, "event for device address " + (String)msg.obj);
                setAdressedPlayerRspNative((byte)PLAYER_NOT_ADDRESSED,
                            getByteAddress(mAdapter.getRemoteDevice((String) msg.obj)));
                deviceFeatures[deviceIndex].mRequestedAddressedPlayerPackageName = null;
                break;

            case MESSAGE_VOLUME_CHANGED:
                if (!isAbsoluteVolumeSupported()) {
                    if (DEBUG) Log.v(TAG, "ignore MESSAGE_VOLUME_CHANGED");
                    break;
                }
                if (DEBUG)
                    Log.v(TAG, "MESSAGE_VOLUME_CHANGED: volume=" + ((byte)msg.arg1 & 0x7f)
                                                        + " ctype=" + msg.arg2);
                Log.v(TAG, "event for device address " + (String)msg.obj);
                deviceIndex = getIndexForDevice(mAdapter.getRemoteDevice((String) msg.obj));
                if (deviceIndex == INVALID_DEVICE_INDEX) {
                    Log.e(TAG,"invalid index for device");
                    break;
                }
                boolean volAdj = false;
                if (msg.arg2 == AVRC_RSP_ACCEPT || msg.arg2 == AVRC_RSP_REJ) {
                    if ((deviceFeatures[deviceIndex].mVolCmdSetInProgress == false) &&
                        (deviceFeatures[deviceIndex].mVolCmdAdjustInProgress == false)){
                        Log.e(TAG, "Unsolicited response, ignored");
                        break;
                    }
                    removeMessages(MESSAGE_ABS_VOL_TIMEOUT);
                    volAdj = deviceFeatures[deviceIndex].mVolCmdAdjustInProgress;
                    deviceFeatures[deviceIndex].mVolCmdSetInProgress = false;
                    deviceFeatures[deviceIndex].mVolCmdAdjustInProgress = false;
                    deviceFeatures[deviceIndex].mAbsVolRetryTimes = 0;
                }
                byte absVol = (byte)((byte)msg.arg1 & 0x7f); // discard MSB as it is RFD
                // convert remote volume to local volume
                int volIndex = convertToAudioStreamVolume(absVol);
                if (deviceFeatures[deviceIndex].mInitialRemoteVolume == -1) {
                    deviceFeatures[deviceIndex].mInitialRemoteVolume = absVol;
                    if (deviceFeatures[deviceIndex].mAbsVolThreshold > 0 &&
                        deviceFeatures[deviceIndex].mAbsVolThreshold < 
                        mAudioStreamMax &&
                        volIndex > deviceFeatures[deviceIndex].mAbsVolThreshold) {
                        if (DEBUG) Log.v(TAG, "remote inital volume too high " + volIndex + ">" +
                            deviceFeatures[deviceIndex].mAbsVolThreshold);
                        Message msg1 = mHandler.obtainMessage(MESSAGE_SET_ABSOLUTE_VOLUME,
                            deviceFeatures[deviceIndex].mAbsVolThreshold , 0);
                        mHandler.sendMessage(msg1);
                        deviceFeatures[deviceIndex].mRemoteVolume = absVol;
                        deviceFeatures[deviceIndex].mLocalVolume = volIndex;
                        break;
                    }
                }
                if (deviceFeatures[deviceIndex].mLocalVolume != volIndex &&
                                                (msg.arg2 == AVRC_RSP_ACCEPT ||
                                                 msg.arg2 == AVRC_RSP_CHANGED ||
                                                 msg.arg2 == AVRC_RSP_INTERIM)) {
                    /* If the volume has successfully changed */
                    deviceFeatures[deviceIndex].mLocalVolume = volIndex;
                    if (deviceFeatures[deviceIndex].mLastLocalVolume != -1
                        && msg.arg2 == AVRC_RSP_ACCEPT) {
                        if (deviceFeatures[deviceIndex].mLastLocalVolume != volIndex) {
                            /* remote volume changed more than requested due to
                             * local and remote has different volume steps */
                            if (DEBUG) Log.d(TAG, "Remote returned volume does not match desired volume "
                                + deviceFeatures[deviceIndex].mLastLocalVolume + " vs "
                                + volIndex);
                            deviceFeatures[deviceIndex].mLastLocalVolume =
                                deviceFeatures[deviceIndex].mLocalVolume;
                        }
                    }
                    // remember the remote volume value, as it's the one supported by remote
                    if (volAdj) {
                        synchronized (deviceFeatures[deviceIndex].mVolumeMapping) {
                            deviceFeatures[deviceIndex].mVolumeMapping.put(volIndex, (int)absVol);
                            if (DEBUG) Log.v(TAG, "remember volume mapping " +volIndex+ "-"+absVol);
                        }
                    }
                    notifyVolumeChanged(deviceFeatures[deviceIndex].mLocalVolume,
                        deviceFeatures[deviceIndex].mCurrentDevice);
                    deviceFeatures[deviceIndex].mRemoteVolume = absVol;
                    long pecentVolChanged = ((long)absVol * 100) / 0x7f;
                    Log.e(TAG, "percent volume changed: " + pecentVolChanged + "%");
                } else if (msg.arg2 == AVRC_RSP_REJ) {
                    Log.e(TAG, "setAbsoluteVolume call rejected");
                } else if (volAdj && deviceFeatures[deviceIndex].mLastRemoteVolume > 0
                            && deviceFeatures[deviceIndex].mLastRemoteVolume < AVRCP_MAX_VOL &&
                            deviceFeatures[deviceIndex].mLocalVolume == volIndex &&
                            (msg.arg2 == AVRC_RSP_ACCEPT )) {
                    /* oops, the volume is still same, remote does not like the value
                     * retry a volume one step up/down */
                    if (DEBUG) Log.d(TAG, "Remote device didn't tune volume, let's try one more step.");
                    int retry_volume = Math.min(AVRCP_MAX_VOL,
                            Math.max(0, deviceFeatures[deviceIndex].mLastRemoteVolume + mLastDirection));
                    if (setVolumeNative(retry_volume,
                            getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice))) {
                        deviceFeatures[deviceIndex].mLastRemoteVolume = retry_volume;
                        sendMessageDelayed(obtainMessage(MESSAGE_ABS_VOL_TIMEOUT,
                            0, 0, deviceFeatures[deviceIndex].mCurrentDevice), CMD_TIMEOUT_DELAY);
                        deviceFeatures[deviceIndex].mVolCmdAdjustInProgress = true;
                    }
                } else if (msg.arg2 == AVRC_RSP_REJ) {
                    if (DEBUG)
                        Log.v(TAG, "setAbsoluteVolume call rejected");
                }
                break;

            case MESSAGE_ADJUST_VOLUME:
            {
                if (!isAbsoluteVolumeSupported()) {
                    if (DEBUG) Log.v(TAG, "ignore MESSAGE_ADJUST_VOLUME");
                    break;
                }
                List<BluetoothDevice> playingDevice = mA2dpService.getA2dpPlayingDevice();
                if (DEBUG)
                    Log.d(TAG, "MESSAGE_ADJUST_VOLUME: direction=" + msg.arg1);
                for (int i = 0; i < playingDevice.size(); i++) {
                    Log.v(TAG, "event for device address " +
                            playingDevice.get(i).getAddress());
                    deviceIndex = getIndexForDevice(playingDevice.get(i));
                    if (deviceIndex == INVALID_DEVICE_INDEX) {
                        Log.e(TAG,"Unkown playing device");
                        sendAdjustVolume(msg.arg1);
                        continue;
                    }
                    if ((deviceFeatures[deviceIndex].mVolCmdAdjustInProgress) ||
                        (deviceFeatures[deviceIndex].mVolCmdSetInProgress)){
                        if (DEBUG)
                            Log.w(TAG, "already a volume command in progress" +
                                    "for this device.");
                        continue;
                    }
                    if (deviceFeatures[deviceIndex].mInitialRemoteVolume == -1) {
                        if (DEBUG) Log.d(TAG, "remote never tell us initial volume, black list it.");
                        blackListCurrentDevice(deviceIndex);
                        break;
                    }
                    // Wait on verification on volume from device, before changing the volume.
                    if (deviceFeatures[deviceIndex].mRemoteVolume != -1 &&
                            (msg.arg1 == -1 || msg.arg1 == 1)) {
                        int setVol = -1;
                        int targetVolIndex = -1;
                        if (deviceFeatures[deviceIndex].mLocalVolume == 0 && msg.arg1 == -1) {
                            if (DEBUG) Log.w(TAG, "No need to Vol down from 0.");
                            break;
                        }
                        if (deviceFeatures[deviceIndex].mLocalVolume == 
                            mAudioStreamMax && msg.arg1 == 1) {
                            if (DEBUG) Log.w(TAG, "No need to Vol up from max.");
                            break;
                        }

                        targetVolIndex = deviceFeatures[deviceIndex].mLocalVolume + msg.arg1;
                        if (DEBUG) Log.d(TAG, "Adjusting volume to  " + targetVolIndex);

                        Integer j;
                        synchronized (deviceFeatures[deviceIndex].mVolumeMapping) {
                            j = deviceFeatures[deviceIndex].mVolumeMapping.get(targetVolIndex);
                        }

                        if (j != null) {
                            /* if we already know this volume mapping, use it */
                            setVol = j.byteValue();
                            if (setVol == deviceFeatures[deviceIndex].mRemoteVolume) {
                                if (DEBUG) Log.d(TAG, "got same volume from mapping for " +
                                    targetVolIndex + ", ignore.");
                                setVol = -1;
                            }
                            if (DEBUG) Log.d(TAG, "set volume from mapping " + targetVolIndex + "-" + setVol);
                        }

                        if (setVol == -1) {
                            /* otherwise use phone steps */
                            setVol = Math.min(AVRCP_MAX_VOL,
                                     convertToAvrcpVolume(Math.max(0, targetVolIndex)));
                            if (DEBUG) Log.d(TAG, "set volume from local volume "+ targetVolIndex+"-"+ setVol);
                        }

                        boolean isSetVol = setVolumeNative(setVol ,
                                getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                        if (isSetVol) {
                            sendMessageDelayed(obtainMessage(MESSAGE_ABS_VOL_TIMEOUT,
                            0, 0, deviceFeatures[deviceIndex].mCurrentDevice), CMD_TIMEOUT_DELAY);
                            deviceFeatures[deviceIndex].mVolCmdAdjustInProgress = true;
                            deviceFeatures[deviceIndex].mLastDirection = msg.arg1;
                            deviceFeatures[deviceIndex].mLastRemoteVolume = setVol;
                            deviceFeatures[deviceIndex].mLastLocalVolume = targetVolIndex;
                        } else {
                            if (DEBUG) Log.d(TAG, "adjustVolumeNative failed");
                        }
                    } else {
                        Log.e(TAG, "Unknown direction in MESSAGE_ADJUST_VOLUME");
                    }
                }
                break;
            }
            case MESSAGE_SET_ABSOLUTE_VOLUME:
            {
                if (!isAbsoluteVolumeSupported()) {
                if (DEBUG) Log.v(TAG, "ignore MESSAGE_SET_ABSOLUTE_VOLUME");
                break;
                }
                if (DEBUG)
                    Log.v(TAG, "MESSAGE_SET_ABSOLUTE_VOLUME");
                List<BluetoothDevice> playingDevice = mA2dpService.getA2dpPlayingDevice();
                if (playingDevice.size() == 0) {
                    Log.e(TAG,"Volume cmd without a2dp playing");
                }
                int avrcpVolume = convertToAvrcpVolume(msg.arg1);
                avrcpVolume = Math.min(AVRCP_MAX_VOL, Math.max(0, avrcpVolume));
                for (int i = 0; i < playingDevice.size(); i++) {
                    deviceIndex = getIndexForDevice(playingDevice.get(i));
                    if (deviceIndex == INVALID_DEVICE_INDEX) {
                        Log.e(TAG,"Unkown playing device for SetAbsVol");
                        sendSetAbsoluteVolume(msg.arg1);
                        continue;
                    }
                    Log.v(TAG, "event for device address " +
                            playingDevice.get(i).getAddress());
                    if ((deviceFeatures[deviceIndex].mVolCmdSetInProgress) ||
                        (deviceFeatures[deviceIndex].mVolCmdAdjustInProgress)){
                        if (DEBUG)
                            Log.w(TAG, "There is already a volume command in progress.");
                        continue;
                    }
                    if (deviceFeatures[deviceIndex].mInitialRemoteVolume == -1) {
                        if (DEBUG) Log.d(TAG, "remote never tell us initial volume, black list it.");
                        blackListCurrentDevice(deviceIndex);
                        break;
                    }
                    Log.v(TAG, "event for device address " + (String)msg.obj);
                    boolean isSetVol = setVolumeNative(avrcpVolume ,
                            getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                    if (isSetVol) {
                        sendMessageDelayed(obtainMessage(MESSAGE_ABS_VOL_TIMEOUT,
                                0, 0, deviceFeatures[deviceIndex].mCurrentDevice),
                                CMD_TIMEOUT_DELAY);
                        deviceFeatures[deviceIndex].mVolCmdSetInProgress = true;
                        deviceFeatures[deviceIndex].mLastRemoteVolume = avrcpVolume;
                        deviceFeatures[deviceIndex].mLastLocalVolume = msg.arg1;
                    } else {
                        if (DEBUG) Log.d(TAG, "setVolumeNative failed");
                    }
                }
                break;
            }
            case MESSAGE_ABS_VOL_TIMEOUT:
                if (DEBUG)
                    Log.v(TAG, "MESSAGE_ABS_VOL_TIMEOUT: Volume change cmd timed out.");
                deviceIndex = getIndexForDevice((BluetoothDevice) msg.obj);
                if (deviceIndex == INVALID_DEVICE_INDEX) {
                    Log.e(TAG,"invalid device index for abs vol timeout");
                    for (int i = 0; i < maxAvrcpConnections; i++) {
                        if (deviceFeatures[i].mVolCmdSetInProgress == true)
                            deviceFeatures[i].mVolCmdSetInProgress = false;
                        if (deviceFeatures[i].mVolCmdAdjustInProgress == true)
                            deviceFeatures[i].mVolCmdAdjustInProgress = false;
                    }
                    break;
                }
                deviceFeatures[deviceIndex].mVolCmdSetInProgress = false;
                deviceFeatures[deviceIndex].mVolCmdAdjustInProgress = false;
                blackListCurrentDevice(deviceIndex);
                Log.v(TAG, "event for device address " + (BluetoothDevice)msg.obj);
                if (deviceFeatures[deviceIndex].mAbsVolRetryTimes >= MAX_ERROR_RETRY_TIMES) {
                    deviceFeatures[deviceIndex].mAbsVolRetryTimes = 0;
                } else {
                    deviceFeatures[deviceIndex].mAbsVolRetryTimes += 1;
                    boolean isSetVol = setVolumeNative(deviceFeatures[deviceIndex].mLastSetVolume ,
                            getByteAddress((BluetoothDevice) msg.obj));
                    if (isSetVol) {
                        sendMessageDelayed(obtainMessage(MESSAGE_ABS_VOL_TIMEOUT,
                                0, 0, msg.obj), CMD_TIMEOUT_DELAY);
                        deviceFeatures[deviceIndex].mVolCmdSetInProgress = true;
                        deviceFeatures[deviceIndex].mVolCmdAdjustInProgress = true;
                    }
                }
                break;

            case MESSAGE_FAST_FORWARD:
            case MESSAGE_REWIND:
                if (msg.what == MESSAGE_FAST_FORWARD) {
                    if ((deviceFeatures[deviceIndex].mCurrentPlayState.getActions() &
                                PlaybackState.ACTION_FAST_FORWARD) != 0) {
                        int keyState = msg.arg1 == KEY_STATE_PRESS ?
                                KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
                        KeyEvent keyEvent =
                                new KeyEvent(keyState, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
                        mMediaController.dispatchMediaButtonEvent(keyEvent);
                        break;
                    }
                } else if ((deviceFeatures[deviceIndex].mCurrentPlayState.getActions() &
                            PlaybackState.ACTION_REWIND) != 0) {
                    int keyState = msg.arg1 == KEY_STATE_PRESS ?
                            KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
                    KeyEvent keyEvent =
                            new KeyEvent(keyState, KeyEvent.KEYCODE_MEDIA_REWIND);
                    mMediaController.dispatchMediaButtonEvent(keyEvent);
                    break;
                }

                int skipAmount;
                if (msg.what == MESSAGE_FAST_FORWARD) {
                    if (DEBUG)
                        Log.v(TAG, "MESSAGE_FAST_FORWARD");
                    removeMessages(MESSAGE_FAST_FORWARD);
                    skipAmount = BASE_SKIP_AMOUNT;
                } else {
                    if (DEBUG)
                        Log.v(TAG, "MESSAGE_REWIND");
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
                    changePositionBy(mSkipAmount * getSkipMultiplier(),
                            (String)msg.obj);
                    Message posMsg = obtainMessage(MESSAGE_CHANGE_PLAY_POS,
                            0, 0, msg.obj);
                    posMsg.arg1 = 1;
                    sendMessageDelayed(posMsg, SKIP_PERIOD);
                }

                break;

            case MESSAGE_CHANGE_PLAY_POS:
                if (DEBUG)
                    Log.v(TAG, "MESSAGE_CHANGE_PLAY_POS:" + msg.arg1);
                changePositionBy(mSkipAmount * getSkipMultiplier(),
                        (String)msg.obj);
                if (msg.arg1 * SKIP_PERIOD < BUTTON_TIMEOUT_TIME) {
                    Message posMsg = obtainMessage(MESSAGE_CHANGE_PLAY_POS,
                            0, 0, msg.obj);
                    posMsg.arg1 = msg.arg1 + 1;
                    sendMessageDelayed(posMsg, SKIP_PERIOD);
                }
                break;

            case MESSAGE_SET_A2DP_AUDIO_STATE:
                if (DEBUG)
                    Log.v(TAG, "MESSAGE_SET_A2DP_AUDIO_STATE:" + msg.arg1);
                BluetoothDevice playStateChangeDevice =
                        (BluetoothDevice)msg.obj;
                Log.v(TAG, "event for device address " +
                        playStateChangeDevice.getAddress());
                deviceIndex = getIndexForDevice(playStateChangeDevice);
                if (deviceIndex == INVALID_DEVICE_INDEX) {
                    Log.e(TAG,"in valid index for device");
                    break;
                }
                updateA2dpAudioState(msg.arg1, (BluetoothDevice)msg.obj);
                break;

            case MSG_UPDATE_RCC_CHANGE:
                Log.v(TAG, "MSG_UPDATE_RCC_CHANGE");
                String callingPackageName = (String)msg.obj;
                int isFocussed = msg.arg1;
                int isAvailable = msg.arg2;
                processRCCStateChange(callingPackageName, isFocussed, isAvailable);
                break;

            case MESSAGE_SET_ADDR_PLAYER:
                processSetAddressedPlayer(msg.arg1, (String) msg.obj);
                break;
            case MESSAGE_SET_BROWSED_PLAYER:
                processSetBrowsedPlayer(msg.arg1, (String) msg.obj);
                break;
            case MESSAGE_CHANGE_PATH:
                itemAttr = (ItemAttr)msg.obj;
                processChangePath(msg.arg1, itemAttr.mUid, itemAttr.mAddress);
                break;
            case MESSAGE_PLAY_ITEM:
                itemAttr = (ItemAttr)msg.obj;
                processPlayItem(msg.arg1, itemAttr.mUid, itemAttr.mAddress);
                break;
            case MESSAGE_GET_ITEM_ATTRS:
                itemAttr = (ItemAttr)msg.obj;
                attrIds = new int[msg.arg1];
                for (int i = 0; i < msg.arg1; ++i) {
                    attrIds[i] = itemAttr.mAttrList.get(i).intValue();
                }
                processGetItemAttr((byte)msg.arg2, itemAttr.mUid, (byte)msg.arg1,
                        attrIds, itemAttr.mAddress);
                break;
            case MESSAGE_GET_FOLDER_ITEMS:
                FolderListEntries folderListEntries = (FolderListEntries)msg.obj;
                attrIds = new int[folderListEntries.mNumAttr];
                for (int i = 0; i < folderListEntries.mNumAttr; ++i) {
                    attrIds[i] = folderListEntries.mAttrList.get(i).intValue();
                }
                processGetFolderItems(folderListEntries.mScope, folderListEntries.mStart,
                    folderListEntries.mEnd, folderListEntries.mAttrCnt,
                    folderListEntries.mNumAttr, attrIds, folderListEntries.mAddress);
                break;
            }
        }
    }

    private void sendAdjustVolume(int val) {
        Log.v(TAG, "in sendAdjustVolume" + " " + val);
        for (int i = 0; i < maxAvrcpConnections; i++) {
            if (deviceFeatures[i].mCurrentDevice != null &&
                    ((deviceFeatures[i].mFeatures &
                    BTRC_FEAT_ABSOLUTE_VOLUME) != 0)) {
                if (deviceFeatures[i].mAbsoluteVolume != -1 &&
                        (val == -1 || val == 1)) {
                    int setVol = Math.min(AVRCP_MAX_VOL,
                                 Math.max(0, deviceFeatures[i].mAbsoluteVolume +
                                 val*mVolumeStep));
                    boolean isSetVol = setVolumeNative(setVol ,
                            getByteAddress((deviceFeatures[i].mCurrentDevice)));
                    if (isSetVol) {
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(MESSAGE_ABS_VOL_TIMEOUT,
                        0, 0, deviceFeatures[i].mCurrentDevice),
                                CMD_TIMEOUT_DELAY);
                        deviceFeatures[i].mVolCmdAdjustInProgress = true;
                        deviceFeatures[i].mLastDirection = val;
                        deviceFeatures[i].mLastSetVolume = setVol;
                    }
                } else {
                    Log.e(TAG, "Unknown direction in MESSAGE_ADJUST_VOLUME");
                }
            }
        }
    }

    private void sendSetAbsoluteVolume(int val) {
        Log.v(TAG, "in sendSetAbsoluteVolume " + " " + val);
        for (int i = 0; i < maxAvrcpConnections; i++) {
            if (deviceFeatures[i].mCurrentDevice != null &&
                    ((deviceFeatures[i].mFeatures &
                    BTRC_FEAT_ABSOLUTE_VOLUME) != 0)) {
                Log.v(TAG, "in sending for device " + deviceFeatures[i].mCurrentDevice);
                boolean isSetVol = setVolumeNative(val ,
                        getByteAddress((deviceFeatures[i].mCurrentDevice)));
                if (isSetVol) {
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(MESSAGE_ABS_VOL_TIMEOUT,
                            0, 0, deviceFeatures[i].mCurrentDevice),
                            CMD_TIMEOUT_DELAY);
                    deviceFeatures[i].mVolCmdSetInProgress = true;
                    deviceFeatures[i].mLastSetVolume = val;
                }
            }
        }
    }

    private void updateA2dpAudioState(int state, BluetoothDevice device) {
        boolean isPlaying = (state == BluetoothA2dp.STATE_PLAYING);

        Log.v(TAG,"updateA2dpAudioState");
        if ((isPlaying) && !mAudioManager.isMusicActive()) {
            /* Play state to be updated only for music streaming, not touchtone */
            Log.v(TAG,"updateA2dpAudioState: Stream state not active ");
            return;
        }
        for (int i = 0; i < maxAvrcpConnections; i++) {
            if ((isPlaying != isPlayingState(deviceFeatures[i].mCurrentPlayState)) &&
                    (device.equals(deviceFeatures[i].mCurrentDevice))) {
                PlaybackState.Builder builder = new PlaybackState.Builder();
                if (isPlaying) {
                    builder.setState(PlaybackState.STATE_PLAYING,
                                     PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f);
                } else {
                    builder.setState(PlaybackState.STATE_PAUSED,
                                     PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0.0f);
                }
                updatePlayPauseState(builder.build(), device);
                break;
            }
        }
    }

    private void updateResetNotificationForDevice(BluetoothDevice device, int index) {
        Log.i(TAG,"in updateResetNotificationForDevice " + device + " index " +
                index);
        if (deviceFeatures[index].mPlayPosChangedNT ==
                NOTIFICATION_TYPE_INTERIM) {
            if (DEBUG)
                Log.v(TAG, "send Play Position reject to stack");
            deviceFeatures[index].mPlayPosChangedNT =
                    NOTIFICATION_TYPE_REJECT;
            registerNotificationRspPlayPosNative(deviceFeatures[index].mPlayPosChangedNT,
                    -1 ,getByteAddress(device));
            mHandler.removeMessages(MESSAGE_PLAY_INTERVAL_TIMEOUT);
        } else {
            Log.v(TAG,"index " + index + " status is"+
                    deviceFeatures[index].mPlayPosChangedNT);
        }
    }

    private void updatePlayPauseState(PlaybackState state, BluetoothDevice device) {
        Log.v(TAG,"updatePlayPauseState, state: " + state + " device: " + device);
        for (int i = 0; i < maxAvrcpConnections; i++) {
            Log.v(TAG,"Device: " + ((deviceFeatures[i].mCurrentDevice == null) ?
                "no name: " : deviceFeatures[i].mCurrentDevice.getName() +
                " : old state: " + deviceFeatures[i].mCurrentPlayState));
        }
        if (device == null) {
            /*Called because of player state change*/
            updatePlayerStateAndPosition(state);
            return;
        } else {
            int deviceIndex = getIndexForDevice(device);
            if (deviceIndex == INVALID_DEVICE_INDEX) {
                Log.w(TAG,"invalid device index" +
                        "Play status change for not connected device");
            } else {
                Log.v(TAG, "old state: " + deviceFeatures[deviceIndex].mCurrentPlayState
                            + " new state: " + state + " device: " +
                            device + " index: " + deviceIndex);
                updatePlayStatusForDevice(deviceIndex, state);
            }
        }
    }

    private void updatePlayStatusForDevice(int deviceIndex, PlaybackState state) {
        if (state == null) {
            state = new PlaybackState.Builder().setState(PlaybackState.STATE_NONE,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0.0f).build();
        }
        Log.i(TAG,"updatePlayStatusForDevice: device: " +
                    deviceFeatures[deviceIndex].mCurrentDevice);
        int oldPlayStatus = convertPlayStateToPlayStatus(
                    deviceFeatures[deviceIndex].mCurrentPlayState);
        int newPlayStatus = convertPlayStateToPlayStatus(state);
        if (DEBUG)
            Log.v(TAG, "oldPlayStatus " + oldPlayStatus);
        if (DEBUG)
            Log.v(TAG, "newPlayStatus " + newPlayStatus);

        deviceFeatures[deviceIndex].mCurrentPlayState = state;

        if ((deviceFeatures[deviceIndex].mPlayStatusChangedNT ==
                NOTIFICATION_TYPE_INTERIM) && (oldPlayStatus != newPlayStatus)) {
            deviceFeatures[deviceIndex].mPlayStatusChangedNT = NOTIFICATION_TYPE_CHANGED;
            registerNotificationRspPlayStatusNative(
                    deviceFeatures[deviceIndex].mPlayStatusChangedNT,
                    newPlayStatus,
                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
        }
    }

    private void updatePlayerStateAndPosition(PlaybackState state) {
        if (DEBUG) Log.v(TAG, "updatePlayerPlayPauseState, old=" +
                            mCurrentPlayerState + ", state=" + state);
        if (state == null) {
            state = new PlaybackState.Builder().setState(PlaybackState.STATE_NONE,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0.0f).build();
        }
        boolean oldPosValid = (mCurrentPosMs !=
                               PlaybackState.PLAYBACK_POSITION_UNKNOWN);

        if (DEBUG) Log.v(TAG, "old state = " + mCurrentPlayerState + ", new state= " + state);
        int oldPlayStatus = convertPlayStateToPlayStatus(mCurrentPlayerState);
        int newPlayStatus = convertPlayStateToPlayStatus(state);

        if ((mCurrentPlayerState.getState() == PlaybackState.STATE_PLAYING) &&
            (mCurrentPlayerState != state) && oldPosValid) {
            mCurrentPosMs = getPlayPosition(null);
            Log.d(TAG, "Update mCurrentPosMs to " + mCurrentPosMs);
        }

        if ((state.getState() == PlaybackState.STATE_PLAYING) && (mCurrentPlayerState != state)) {
            mPlayStartTimeMs = SystemClock.elapsedRealtime();
            Log.d(TAG, "Update mPlayStartTimeMs to " + mPlayStartTimeMs);
        }

        mCurrentPlayerState = state;

        if (!(mCurrentPlayerState.getState() == PlaybackState.STATE_PLAYING
                                             && mCurrentPosMs == state.getPosition())) {
            if (state.getPosition() != PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
                mCurrentPosMs = state.getPosition();
                mPlayStartTimeMs = SystemClock.elapsedRealtime();
                Log.d(TAG, "Update mPlayStartTimeMs: " + mPlayStartTimeMs +
                                            " mCurrentPosMs: " + mCurrentPosMs);
            }
        }

        boolean newPosValid = (mCurrentPosMs != PlaybackState.STATE_PLAYING);
        long playPosition = getPlayPosition(null);
        mHandler.removeMessages(MESSAGE_PLAY_INTERVAL_TIMEOUT);
        for (int deviceIndex = 0; deviceIndex < maxAvrcpConnections; deviceIndex++) {
            if (deviceFeatures[deviceIndex].mCurrentDevice != null &&
                    ((deviceFeatures[deviceIndex].mPlayPosChangedNT == NOTIFICATION_TYPE_INTERIM) &&
                    ((oldPlayStatus != newPlayStatus) || (oldPosValid != newPosValid) ||
                    (newPosValid && ((playPosition >= deviceFeatures[deviceIndex].mNextPosMs) ||
                    (playPosition <= deviceFeatures[deviceIndex].mPrevPosMs)))))) {
                deviceFeatures[deviceIndex].mPlayPosChangedNT = NOTIFICATION_TYPE_CHANGED;
                registerNotificationRspPlayPosNative(deviceFeatures[deviceIndex].mPlayPosChangedNT,
                        (int)playPosition,
                        getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
            }
            if (deviceFeatures[deviceIndex].mCurrentDevice != null &&
                    ((deviceFeatures[deviceIndex].mPlayPosChangedNT ==
                    NOTIFICATION_TYPE_INTERIM) && newPosValid &&
                    (state.getState() == PlaybackState.STATE_PLAYING))) {
                Message msg = mHandler.obtainMessage(MESSAGE_PLAY_INTERVAL_TIMEOUT,
                    0, 0, deviceFeatures[deviceIndex].mCurrentDevice);
                mHandler.sendMessageDelayed(msg, deviceFeatures[deviceIndex].mNextPosMs
                                                                            - playPosition);
            }
            /*Discretion is required only when updating play state changed as playing*/
            if ((state.getState() != PlaybackState.STATE_PLAYING) ||
                                isPlayStateToBeUpdated(deviceIndex)) {
                updatePlayStatusForDevice(deviceIndex, state);
            }
        }
    }

    private boolean isPlayStateToBeUpdated(int deviceIndex) {
        Log.v(TAG, "isPlayStateTobeUpdated: device: "  +
                    deviceFeatures[deviceIndex].mCurrentDevice);
        if (maxAvrcpConnections < 2) {
            Log.v(TAG, "maxAvrcpConnections: " + maxAvrcpConnections);
            return true;
        } else if(mA2dpService.isMulticastFeatureEnabled()) {
            if (!areMultipleDevicesConnected()) {
                Log.v(TAG, "Single connection exists");
                return true;
            } else if (mA2dpService.isMulticastEnabled()) {
                Log.v(TAG, "Multicast is Enabled");
                return true;
            } else {
                Log.v(TAG, "Multiple connection exists, Multicast not enabled");
                if(isDeviceActiveInHandOffNative(getByteAddress(
                            deviceFeatures[deviceIndex].mCurrentDevice))) {
                    Log.v(TAG, "Device Active in handoff scenario");
                    return true;
                } else {
                    Log.v(TAG, "Device Not Active in handoff scenario");
                    return false;
                }
            }
        } else {
            if (!areMultipleDevicesConnected()) {
                Log.v(TAG, "Single connection exists");
                return true;
            } else {
                Log.v(TAG, "Multiple connection exists in handoff");
                if(isDeviceActiveInHandOffNative(getByteAddress(
                            deviceFeatures[deviceIndex].mCurrentDevice))) {
                    Log.v(TAG, "Device Active in handoff scenario");
                    return true;
                } else {
                    Log.v(TAG, "Device Not Active in handoff scenario");
                    return false;
                }
            }
        }
    }

    private boolean areMultipleDevicesConnected() {
        for (int deviceIndex = 0; deviceIndex < maxAvrcpConnections; deviceIndex++) {
            if (deviceFeatures[deviceIndex].mCurrentDevice == null) {
                return false;
            }
        }
        return true;
    }

    private void updateTransportControls(int transportControlFlags) {
        mTransportControlFlags = transportControlFlags;
    }
    private void updateAvailableMediaPlayers() {
        /* for registerged notification check for all devices which has
         * registered for change notification */
        if (DEBUG)
            Log.v(TAG, "updateAvailableMediaPlayers");
        for (int i = 0; i < maxAvrcpConnections; i++) {
            if (deviceFeatures[i].mAvailablePlayersChangedNT ==
                    NOTIFICATION_TYPE_INTERIM) {
                deviceFeatures[i].mAvailablePlayersChangedNT = NOTIFICATION_TYPE_CHANGED;
                if (DEBUG)
                    Log.v(TAG, "send AvailableMediaPlayers to stack");
                registerNotificationRspAvailablePlayersChangedNative(
                        deviceFeatures[i].mAvailablePlayersChangedNT,
                        getByteAddress(deviceFeatures[i].mCurrentDevice));
            }
        }
    }
    private void updateAddressedMediaPlayer(int playerId) {
        Log.v(TAG, "updateAddressedMediaPlayer");
        Log.v(TAG, "current Player: " + mAddressedPlayerId);
        Log.v(TAG, "Requested Player: " + playerId);

        int previousAddressedPlayerId;
        for (int i = 0; i < maxAvrcpConnections; i++) {
            if ((deviceFeatures[i].mAddressedPlayerChangedNT ==
                    NOTIFICATION_TYPE_INTERIM) &&
                    (mAddressedPlayerId != playerId)) {
                if (DEBUG)
                    Log.v(TAG, "send AddressedMediaPlayer to stack: playerId" + playerId);
                previousAddressedPlayerId = mAddressedPlayerId;
                deviceFeatures[i].mAddressedPlayerChangedNT = NOTIFICATION_TYPE_CHANGED;
                registerNotificationRspAddressedPlayerChangedNative(
                        deviceFeatures[i].mAddressedPlayerChangedNT,
                        playerId, getByteAddress(deviceFeatures[i].mCurrentDevice));
                if (previousAddressedPlayerId != INVALID_ADDRESSED_PLAYER_ID) {
                    resetAndSendPlayerStatusReject();
                }
            } else {
                if (DEBUG)
                    Log.v(TAG, "Do not reset notifications, ADDR_PLAYR_CHNGD not registered");
            }
        }
        mAddressedPlayerId = playerId;
    }

    public void updateResetNotification(int notificationType) {
        Log.v(TAG,"notificationType " + notificationType);
        for (int i = 0; i < maxAvrcpConnections; i++) {
            switch (notificationType) {
                case PLAY_STATUS_CHANGE_NOTIFICATION:
                    if (deviceFeatures[i].mPlayStatusChangedNT ==
                            NOTIFICATION_TYPE_INTERIM) {
                        deviceFeatures[i].mPlayStatusChangedNT =
                                NOTIFICATION_TYPE_REJECT;
                        registerNotificationRspPlayStatusNative(
                                deviceFeatures[i].mPlayStatusChangedNT,
                                PLAYSTATUS_STOPPED,
                                getByteAddress(deviceFeatures[i].mCurrentDevice));
                    } else {
                        Log.v(TAG,"i " + i + " status is"+
                            deviceFeatures[i].mPlayStatusChangedNT);
                    }
                    break;
                case PLAY_POSITION_CHANGE_NOTIFICATION:
                    if (deviceFeatures[i].mPlayPosChangedNT ==
                            NOTIFICATION_TYPE_INTERIM) {
                        if (DEBUG)
                            Log.v(TAG, "send Play Position reject to stack");
                        deviceFeatures[i].mPlayPosChangedNT =
                                NOTIFICATION_TYPE_REJECT;
                        registerNotificationRspPlayPosNative(
                                deviceFeatures[i].mPlayPosChangedNT,
                                -1 ,getByteAddress(deviceFeatures[i].mCurrentDevice));
                        mHandler.removeMessages(MESSAGE_PLAY_INTERVAL_TIMEOUT);
                    } else {
                        Log.v(TAG,"i " + i + " status is"+
                            deviceFeatures[i].mPlayPosChangedNT);
                    }

                    break;
                case TRACK_CHANGE_NOTIFICATION:
                    if (deviceFeatures[i].mTrackChangedNT ==
                            NOTIFICATION_TYPE_INTERIM) {
                             if (DEBUG)
                                Log.v(TAG, "send Track Changed reject to stack");
                             deviceFeatures[i].mTrackChangedNT =
                                    NOTIFICATION_TYPE_REJECT;
                             byte[] track = new byte[TRACK_ID_SIZE];
                             /* track is stored in big endian format */
                             for (int j = 0; j < TRACK_ID_SIZE; ++j) {
                                 track[j] = (byte) (mTrackNumber >> (56 - 8 * j));
                             }
                             registerNotificationRspTrackChangeNative(
                                     deviceFeatures[i].mTrackChangedNT ,
                                     track ,getByteAddress(deviceFeatures[i].mCurrentDevice));
                    } else {
                        Log.v(TAG,"i " + i + " status is"+
                            deviceFeatures[i].mTrackChangedNT);
                    }

                    break;
                case NOW_PALYING_CONTENT_CHANGED_NOTIFICATION:
                    if (deviceFeatures[i].mNowPlayingContentChangedNT ==
                            NOTIFICATION_TYPE_INTERIM) {
                        if (DEBUG)
                            Log.v(TAG, "send Now playing changed reject to stack");
                        deviceFeatures[i].mNowPlayingContentChangedNT =
                                NOTIFICATION_TYPE_REJECT;
                        registerNotificationRspNowPlayingContentChangedNative(
                                deviceFeatures[i].mNowPlayingContentChangedNT ,
                                getByteAddress(deviceFeatures[i].mCurrentDevice));
                    } else {
                        Log.v(TAG,"i " + i + " status is"+
                            deviceFeatures[i].mNowPlayingContentChangedNT);
                    }

                    break;
                default :
                    Log.e(TAG,"Invalid Notification type ");
            }
        }
    }

    private void resetAndSendPlayerStatusReject() {
        if (DEBUG)
            Log.v(TAG, "resetAndSendPlayerStatusReject");
        updateResetNotification(PLAY_STATUS_CHANGE_NOTIFICATION);
        updateResetNotification(PLAY_POSITION_CHANGE_NOTIFICATION);
        updateResetNotification(TRACK_CHANGE_NOTIFICATION);
        updateResetNotification(NOW_PALYING_CONTENT_CHANGED_NOTIFICATION);
    }

    void updateBrowsedPlayerFolder(int numOfItems, int status, String[] folderNames) {
        Log.v(TAG, "updateBrowsedPlayerFolder: numOfItems =  " + numOfItems
              + " status = " + status);
        if (mBrowserDevice == null) {
            Log.e(TAG,"mBrowserDevice is null for music player called api");
        }
        BluetoothDevice device = mBrowserDevice;
        int deviceIndex = getIndexForDevice(device);
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.e(TAG,"invalid index for device");
            return;
        }
        deviceFeatures[deviceIndex].mCurrentPath = PATH_ROOT;
        deviceFeatures[deviceIndex].mCurrentPathUid = null;
        deviceFeatures[deviceIndex].mMediaUri = mMediaUriStatic;
        mMediaUriStatic = null;

        setBrowsedPlayerRspNative((byte)status, 0x0, numOfItems, 0x0, CHAR_SET_UTF8,
                                   folderNames, getByteAddress(device));
    }

    void updateNowPlayingContentChanged() {
        Log.v(TAG, "updateNowPlayingContentChanged");
        for (int i = 0; i < maxAvrcpConnections; i++) {
            if (deviceFeatures[i].mNowPlayingContentChangedNT ==
                    NOTIFICATION_TYPE_INTERIM) {
                Log.v(TAG, "Notify peer on updateNowPlayingContentChanged");
                deviceFeatures[i].mNowPlayingContentChangedNT = NOTIFICATION_TYPE_CHANGED;
                registerNotificationRspNowPlayingContentChangedNative(
                        deviceFeatures[i].mNowPlayingContentChangedNT ,
                        getByteAddress(deviceFeatures[i].mCurrentDevice));
            }
        }
    }

    void updatePlayItemResponse(boolean success) {
        Log.v(TAG, "updatePlayItemResponse: success: " + success);
        if (mBrowserDevice == null) {
            Log.e(TAG,"mBrowserDevice is null for music player called api");
        }
        BluetoothDevice device = mBrowserDevice;
        int deviceIndex = getIndexForDevice(device);
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.e(TAG,"invalid index for device");
            return;
        }
        /* add member for getting current setting get play item pending rsp
         * and clear it when this is recieved */
        if (success) {
            playItemRspNative(OPERATION_SUCCESSFUL ,
                    getByteAddress(device));

        } else {
            playItemRspNative(INTERNAL_ERROR ,
                    getByteAddress(device));
        }
    }

    void updateNowPlayingEntriesReceived(long[] playList) {
        Log.e(TAG,"updateNowPlayingEntriesReceived called");
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
        if (mBrowserDevice == null) {
            Log.e(TAG,"mBrowserDevice is null for music app called API");
        }
        BluetoothDevice device = mBrowserDevice;
        int deviceIndex = getIndexForDevice(device);
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.e(TAG,"invalid device index");
            return;
        }

        Log.v(TAG, "updateNowPlayingEntriesReceived");

        // Item specific attribute's entry starts from index*8, reset all such entries to 0 for now
        for (int count = 0; count < (MAX_BROWSE_ITEM_TO_SEND * 7); count++) {
            attValues[count] = "";
            attIds[count] = 0;
        }

        availableItems = playList.length;
        if ((mCachedRequest.mStart + 1) > availableItems) {
            Log.i(TAG, "startIteam exceeds the available item index");
            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                    numItems, itemType, uid, type,
                    playable, displayName, numAtt, attValues, attIds,
                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
            return;
        }

        if ((mCachedRequest.mStart < 0) || (mCachedRequest.mEnd < 0) ||
                            (mCachedRequest.mStart > mCachedRequest.mEnd)) {
            Log.i(TAG, "wrong start / end index");
            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                    numItems, itemType, uid, type,
                    playable, displayName, numAtt, attValues, attIds,
                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
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
                     deviceFeatures[deviceIndex].mMediaUri, mCursorCols,
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
                            attValues[(7 * index) + attIndex] = getAttributeStringFromCursor(
                                    cursor, attr, deviceIndex);
                            attIds[(7 * index) + attIndex] = attr;
                            validAttrib ++;
                        }
                    }
                    numAtt[index] = (byte)validAttrib;
                }
            } catch(Exception e) {
                Log.i(TAG, "Exception e"+ e);
                getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                        numItems, itemType, uid, type,
                        playable, displayName, numAtt, attValues, attIds,
                        getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        numItems = index;
        getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL ,
                numItems, itemType, uid, type,
                playable, displayName, numAtt, attValues, attIds,
                getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
        mBrowserDevice = null;
    }

    class CachedRequest {
        long mStart;
        long mEnd;
        byte mAttrCnt;
        ArrayList<Integer> mAttrList;
        int mSize;
        boolean mIsGetFolderItems;
        public CachedRequest(long start, long end, byte attrCnt, int[] attrs,
                boolean isGetFolderItems) {
            mStart = start;
            mEnd = end;
            mAttrCnt = attrCnt;
            mIsGetFolderItems = isGetFolderItems;
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
        String mAddress;
        ArrayList<Integer> mAttrList;
        public FolderListEntries(byte scope, long start, long end, int attrCnt, int numAttr,
                int[] attrs, String deviceAddress) {
            mScope = scope;
            mStart = start;
            mEnd = end;
            mAttrCnt = attrCnt;
            mNumAttr = numAttr;
            mAddress = deviceAddress;
            int i;
            mAttrList = new ArrayList<Integer>();
            for (i = 0; i < numAttr; ++i) {
                mAttrList.add(new Integer(attrs[i]));
            }
        }
    }

    class MediaAttributes {
        private boolean exists;
        private String title;
        private String artistName;
        private String albumName;
        private String mediaNumber;
        private String mediaTotalNumber;
        private String genre;
        private String playingTimeMs;

        private static final int ATTR_TITLE = 1;
        private static final int ATTR_ARTIST_NAME = 2;
        private static final int ATTR_ALBUM_NAME = 3;
        private static final int ATTR_MEDIA_NUMBER = 4;
        private static final int ATTR_MEDIA_TOTAL_NUMBER = 5;
        private static final int ATTR_GENRE = 6;
        private static final int ATTR_PLAYING_TIME_MS = 7;


        public MediaAttributes(MediaMetadata data) {
            exists = data != null;
            if (!exists)
                return;

            artistName = stringOrBlank(data.getString(MediaMetadata.METADATA_KEY_ARTIST));
            albumName = stringOrBlank(data.getString(MediaMetadata.METADATA_KEY_ALBUM));
            mediaNumber = longStringOrBlank(data.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER));
            mediaTotalNumber = longStringOrBlank(data.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS));
            genre = stringOrBlank(data.getString(MediaMetadata.METADATA_KEY_GENRE));
            playingTimeMs = longStringOrBlank(data.getLong(MediaMetadata.METADATA_KEY_DURATION));

            // Try harder for the title.
            title = data.getString(MediaMetadata.METADATA_KEY_TITLE);

            if (title == null) {
                MediaDescription desc = data.getDescription();
                if (desc != null) {
                    CharSequence val = desc.getDescription();
                    if (val != null)
                        title = val.toString();
                }
            }

            if (title == null)
                title = new String();
       }

        public boolean equals(MediaAttributes other) {
            if (other == null)
                return false;

            if (exists != other.exists)
                return false;

            if (exists == false)
                return true;

            return (title.equals(other.title)) &&
                (artistName.equals(other.artistName)) &&
                (albumName.equals(other.albumName)) &&
                (mediaNumber.equals(other.mediaNumber)) &&
                (mediaTotalNumber.equals(other.mediaTotalNumber)) &&
                (genre.equals(other.genre)) &&
                (playingTimeMs.equals(other.playingTimeMs));
        }

        public String getString(int attrId) {
            if (!exists)
                return new String();

            switch (attrId) {
                case ATTR_TITLE:
                    return title;
                case ATTR_ARTIST_NAME:
                    return artistName;
                case ATTR_ALBUM_NAME:
                    return albumName;
                case ATTR_MEDIA_NUMBER:
                    return mediaNumber;
                case ATTR_MEDIA_TOTAL_NUMBER:
                    return mediaTotalNumber;
                case ATTR_GENRE:
                    return genre;
                case ATTR_PLAYING_TIME_MS:
                    return playingTimeMs;
                default:
                    return new String();
            }
        }

        private String stringOrBlank(String s) {
            return s == null ? new String() : s;
        }

        private String longStringOrBlank(Long s) {
            return s == null ? new String() : s.toString();
        }
        public String toString() {
        if (!exists)
        return "[MediaAttributes: none]";

        return "[MediaAttributes: " + title + " - " + albumName + " by "
        + artistName + " (" + mediaNumber + "/" + mediaTotalNumber + ") "
        + genre + "]";
        }
    }

    private void updateMetadata(MediaMetadata data) {
        if (DEBUG)
            Log.v(TAG, "updateMetadata");

        MediaAttributes oldAttributes = mMediaAttributes;
        mMediaAttributes = new MediaAttributes(data);
        if (data == null) {
            mSongLengthMs = 0L;
        } else {
            mSongLengthMs = data.getLong(MediaMetadata.METADATA_KEY_DURATION);
            mTrackNumber = data.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS);
        }
        if (!oldAttributes.equals(mMediaAttributes)) {
            Log.v(TAG, "MediaAttributes Changed to " + mMediaAttributes.toString());
            for (int i = 0; i < maxAvrcpConnections; i++) {
                if ((deviceFeatures[i].mCurrentDevice != null) &&
                    (deviceFeatures[i].mTrackChangedNT == NOTIFICATION_TYPE_INTERIM)) {
                    deviceFeatures[i].mTrackChangedNT = NOTIFICATION_TYPE_CHANGED;
                    Log.v(TAG,"sending track change for device " + i);
                    sendTrackChangedRsp(deviceFeatures[i].mCurrentDevice);
                }
            }
            if (mCurrentPosMs != PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
                for (int i = 0; i < maxAvrcpConnections; i++) {
                    if ((deviceFeatures[i].mCurrentDevice != null) &&
                        isPlayingState(deviceFeatures[i].mCurrentPlayState)) {
                        Log.i(TAG,"updated mPlayStartTimeMs");
                        mPlayStartTimeMs = SystemClock.elapsedRealtime();
                        break;
                    }
                }
            }
            /* need send play position changed notification when track is changed */
            for (int i = 0; i < maxAvrcpConnections; i++) {
                Log.v(TAG,i + " mCurrentPlayState " + deviceFeatures[i].mCurrentPlayState);
                if (deviceFeatures[i].mPlayPosChangedNT == NOTIFICATION_TYPE_INTERIM &&
                        isPlayingState(deviceFeatures[i].mCurrentPlayState)) {
                    Log.v(TAG,"sending play pos change for device " + i);
                    deviceFeatures[i].mPlayPosChangedNT = NOTIFICATION_TYPE_CHANGED;
                    registerNotificationRspPlayPosNative(deviceFeatures[i].mPlayPosChangedNT,
                            (int)getPlayPosition(deviceFeatures[i].mCurrentDevice) ,
                            getByteAddress(deviceFeatures[i].mCurrentDevice));
                    mHandler.removeMessages(MESSAGE_PLAY_INTERVAL_TIMEOUT);
                }
            }
        } else {
          Log.v(TAG, "Metadata updated but no change!");
        }
    }

    private void getRcFeatures(byte[] address, int features) {
        Message msg = mHandler.obtainMessage(MESSAGE_GET_RC_FEATURES, features, 0,
                                             Utils.getAddressStringFromByte(address));
        mHandler.sendMessage(msg);
    }

    private void getPlayStatus(byte[] address) {
        Message msg = mHandler.obtainMessage(MESSAGE_GET_PLAY_STATUS, 0, 0,
                Utils.getAddressStringFromByte(address));
        mHandler.sendMessage(msg);
    }

    private void getElementAttr(byte numAttr, int[] attrs, byte[] address) {
        int i;
        ArrayList<Integer> attrList = new ArrayList<Integer>();
        for (i = 0; i < numAttr; ++i) {
            attrList.add(attrs[i]);
        }
        ItemAttr itemAttr = new ItemAttr(attrList, 0,
                Utils.getAddressStringFromByte(address));
        Message msg = mHandler.obtainMessage(MESSAGE_GET_ELEM_ATTRS, (int)numAttr, 0,
                itemAttr);
        mHandler.sendMessage(msg);
    }

    private void setBrowsedPlayer(int playerId, byte[] address) {
        if (DEBUG)
            Log.v(TAG, "setBrowsedPlayer: PlayerID: " + playerId);
        Message msg = mHandler.obtainMessage(MESSAGE_SET_BROWSED_PLAYER, playerId, 0,
                Utils.getAddressStringFromByte(address));
        mHandler.sendMessage(msg);
    }

    private void processSetBrowsedPlayer(int playerId, String deviceAddress) {
        String packageName = null;
        byte retError = INVALID_PLAYER_ID;
        BluetoothDevice device = mAdapter.getRemoteDevice(deviceAddress);
        int deviceIndex = getIndexForDevice(device);
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.v(TAG,"device entry not present, bailing out");
            return;
        }
        /* Following gets updated if SetBrowsed Player succeeds */
        deviceFeatures[deviceIndex].mCurrentPath = PATH_INVALID;
        deviceFeatures[deviceIndex].mMediaUri = Uri.EMPTY;
        deviceFeatures[deviceIndex].mCurrentPathUid = null;
        if (DEBUG)
            Log.v(TAG, "processSetBrowsedPlayer: PlayerID: " + playerId);
        if (mMediaPlayers.size() > 0) {
            final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
            while (rccIterator.hasNext()) {
                final MediaPlayerInfo di = rccIterator.next();
                if (di.RetrievePlayerId() == playerId) {
                    if (di.GetPlayerAvailablility()) {
                        if (DEBUG)
                            Log.v(TAG, "player found and available");
                        if (di.IsPlayerBrowsable()) {
                            if (di.IsPlayerBrowsableWhenAddressed()) {
                                if (di.GetPlayerFocus()) {
                                    packageName = di.RetrievePlayerPackageName();
                                    if (DEBUG)
                                        Log.v(TAG, "player addressed hence browsable");
                                } else {
                                    if (DEBUG)
                                        Log.v(TAG, "Reject: player browsable only" +
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
            mMediaController.getTransportControls().setRemoteControlClientBrowsedPlayer();
            mBrowserDevice = device;
        } else {
            if (DEBUG)
                Log.v(TAG, "player not available for browse");
            setBrowsedPlayerRspNative(retError ,
                    0x0, 0x0, 0x0, 0x0,
                    null, getByteAddress(device));
        }
    }

    private void fastForward(int keyState, String deviceAddress) {
        BluetoothDevice device = mAdapter.getRemoteDevice(deviceAddress);
        int deviceIndex = getIndexForDevice(device);
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.e(TAG,"invalid index for device");
            return;
        }
        if ((keyState == deviceFeatures[deviceIndex].keyPressState) &&
                (keyState == KEY_STATE_RELEASE)) {
            Log.e(TAG, "Ignore key release event");
        } else {
            Message msg = mHandler.obtainMessage(MESSAGE_FAST_FORWARD, keyState,
                    0, deviceAddress);
            mHandler.sendMessage(msg);
            deviceFeatures[deviceIndex].keyPressState = keyState;
        }
    }

    private void rewind(int keyState, String deviceAddress) {
        BluetoothDevice device = mAdapter.getRemoteDevice(deviceAddress);
        int deviceIndex = getIndexForDevice(device);
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.e(TAG,"invalid index for device");
            return;
        }
        if ((keyState == deviceFeatures[deviceIndex].keyPressState) &&
                (keyState == KEY_STATE_RELEASE)) {
            Log.e(TAG, "Ignore key release event");
        } else {
            Message msg = mHandler.obtainMessage(MESSAGE_REWIND, keyState, 0,
                    deviceAddress);
            mHandler.sendMessage(msg);
            deviceFeatures[deviceIndex].keyPressState = keyState;
        }
    }

    private void changePath(byte direction, long uid, byte[] address) {
        if (DEBUG)
            Log.v(TAG, "changePath: direction: " + direction + " uid:" + uid);
        ItemAttr itemAttr = new ItemAttr(null, uid,
                Utils.getAddressStringFromByte(address));
        Message msg = mHandler.obtainMessage(MESSAGE_CHANGE_PATH, direction, 0, itemAttr);
        mHandler.sendMessage(msg);
    }

    private void processChangePath(int direction, long folderUid,
            String deviceAddress) {
        long numberOfItems = 0;
        int status = OPERATION_SUCCESSFUL;
        BluetoothDevice device = mAdapter.getRemoteDevice(deviceAddress);
        int deviceIndex = getIndexForDevice(device);
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.v(TAG,"device entry not present, bailing out");
            return;
        }
        if (DEBUG)
            Log.v(TAG, "processChangePath: direction: " + direction +
                    " folderuid: " + folderUid + " mCurrentPath: " +
                    deviceFeatures[deviceIndex].mCurrentPath + " mCurrentPathUID: " +
                    deviceFeatures[deviceIndex].mCurrentPathUid);

        if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_ROOT)){
            switch (direction) {
                case FOLDER_UP:
                    status = DOES_NOT_EXIST;
                    break;
                case FOLDER_DOWN:
                    if (folderUid == UID_TITLES) {
                        deviceFeatures[deviceIndex].mCurrentPath = PATH_TITLES;
                        numberOfItems = getNumItems(PATH_TITLES,
                            MediaStore.Audio.Media.TITLE, deviceIndex);
                    } else if (folderUid == UID_ALBUM) {
                        deviceFeatures[deviceIndex].mCurrentPath = PATH_ALBUMS;
                        numberOfItems = getNumItems(PATH_ALBUMS,
                            MediaStore.Audio.Media.ALBUM_ID, deviceIndex);
                    } else if (folderUid == UID_ARTIST) {
                        deviceFeatures[deviceIndex].mCurrentPath = PATH_ARTISTS;
                        numberOfItems = getNumItems(PATH_ARTISTS,
                            MediaStore.Audio.Media.ARTIST_ID, deviceIndex);
                    } else if (folderUid == UID_PLAYLIST) {
                        deviceFeatures[deviceIndex].mCurrentPath = PATH_PLAYLISTS;
                        numberOfItems = getNumPlaylistItems();
                    } else {
                        status = DOES_NOT_EXIST;
                    }
                    break;
                default:
                    status = INVALID_DIRECTION;
                    break;
            }
        } else if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_TITLES)) {
            switch (direction) {
                case FOLDER_UP:
                    deviceFeatures[deviceIndex].mCurrentPath = PATH_ROOT;
                    numberOfItems = NUM_ROOT_ELEMENTS;
                    break;
                case FOLDER_DOWN:
                    Cursor cursor = null;
                    try {
                        cursor = mContext.getContentResolver().query(
                            deviceFeatures[deviceIndex].mMediaUri,
                            new String[] {MediaStore.Audio.Media.TITLE},
                            MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id="
                            + folderUid, null, null);
                        if (cursor != null)
                            status = NOT_A_DIRECTORY;
                        else
                            status = DOES_NOT_EXIST;
                    } catch (Exception e) {
                        Log.e(TAG, "Exception " + e);
                        changePathRspNative(INTERNAL_ERROR ,
                                numberOfItems ,
                                getByteAddress(device));
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
        } else if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_ALBUMS)) {
            switch (direction) {
                case FOLDER_UP:
                    if (deviceFeatures[deviceIndex].mCurrentPathUid == null) { // Path @ Album
                        deviceFeatures[deviceIndex].mCurrentPath = PATH_ROOT;
                        numberOfItems = NUM_ROOT_ELEMENTS;
                    } else { // Path @ individual album id
                        deviceFeatures[deviceIndex].mCurrentPath = PATH_ALBUMS;
                        deviceFeatures[deviceIndex].mCurrentPathUid = null;
                        numberOfItems = getNumItems(PATH_ALBUMS,
                            MediaStore.Audio.Media.ALBUM_ID, deviceIndex);
                    }
                    break;
                case FOLDER_DOWN:
                    if (deviceFeatures[deviceIndex].mCurrentPathUid == null) { // Path @ Album
                        Cursor cursor = null;
                        try {
                            cursor = mContext.getContentResolver().query(
                                deviceFeatures[deviceIndex].mMediaUri,
                                new String[] {MediaStore.Audio.Media.ALBUM},
                                MediaStore.Audio.Media.IS_MUSIC + "=1 AND " +
                                MediaStore.Audio.Media.ALBUM_ID + "=" + folderUid,
                                null, null);
                            if ((cursor == null) || (cursor.getCount() == 0)) {
                                status = DOES_NOT_EXIST;
                            } else{
                                numberOfItems = cursor.getCount();
                                deviceFeatures[deviceIndex].mCurrentPathUid =
                                        String.valueOf(folderUid);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Exception " + e);
                            changePathRspNative(INTERNAL_ERROR ,
                                    numberOfItems ,
                                    getByteAddress(device));
                        } finally {
                            if (cursor != null) {
                                cursor.close();
                            }
                        }
                    } else { // Path @ Individual Album id
                        Cursor cursor = null;
                        try {
                            cursor = mContext.getContentResolver().query(
                                deviceFeatures[deviceIndex].mMediaUri,
                                new String[] {MediaStore.Audio.Media.TITLE},
                                MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id=" + folderUid,
                                null, null);
                            /* As Individual Album path can not have any folder in it hence return
                             * the error as applicable, depending on whether uid passed
                             * exists. */
                            if (cursor != null)
                                status = NOT_A_DIRECTORY;
                            else
                                status = DOES_NOT_EXIST;
                        } catch (Exception e) {
                            Log.e(TAG, "Exception " + e);
                            changePathRspNative(INTERNAL_ERROR ,
                                    numberOfItems ,
                                    getByteAddress(device));
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
        } else if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_ARTISTS)) {
            switch(direction) {
                case FOLDER_UP:
                    if (deviceFeatures[deviceIndex].mCurrentPathUid == null) {
                        deviceFeatures[deviceIndex].mCurrentPath = PATH_ROOT;
                        numberOfItems = NUM_ROOT_ELEMENTS;
                    } else {
                        deviceFeatures[deviceIndex].mCurrentPath = PATH_ARTISTS;
                        deviceFeatures[deviceIndex].mCurrentPathUid = null;
                        numberOfItems = getNumItems(PATH_ARTISTS,
                            MediaStore.Audio.Media.ARTIST_ID, deviceIndex);
                    }
                    break;
                case FOLDER_DOWN:
                    if (deviceFeatures[deviceIndex].mCurrentPathUid == null) {
                        Cursor cursor = null;
                        try {
                            cursor = mContext.getContentResolver().query(
                                deviceFeatures[deviceIndex].mMediaUri,
                                new String[] {MediaStore.Audio.Media.ARTIST},
                                MediaStore.Audio.Media.IS_MUSIC + "=1 AND " +
                                MediaStore.Audio.Media.ARTIST_ID + "=" + folderUid,
                                null, null);
                            if ((cursor == null) || (cursor.getCount() == 0)) {
                                status = DOES_NOT_EXIST;
                            } else{
                                numberOfItems = cursor.getCount();
                                deviceFeatures[deviceIndex].mCurrentPathUid =
                                        String.valueOf(folderUid);
                                deviceFeatures[deviceIndex].mCurrentPath = PATH_ARTISTS;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Exception " + e);
                            changePathRspNative(INTERNAL_ERROR ,
                                    numberOfItems ,
                                    getByteAddress(device));
                        } finally {
                            if (cursor != null) {
                                cursor.close();
                            }
                        }
                    } else {
                        Cursor cursor = null;
                        try {
                            cursor = mContext.getContentResolver().query(
                                deviceFeatures[deviceIndex].mMediaUri,
                                new String[] {MediaStore.Audio.Media.TITLE},
                                MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id="
                                + folderUid, null, null);
                            if (cursor != null)
                                status = NOT_A_DIRECTORY;
                            else
                                status = DOES_NOT_EXIST;
                        } catch (Exception e) {
                            Log.e(TAG, "Exception " + e);
                            changePathRspNative(INTERNAL_ERROR ,
                                    numberOfItems ,
                                    getByteAddress(device));
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
        } else if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_PLAYLISTS)) {
            switch(direction) {
                case FOLDER_UP:
                    if (deviceFeatures[deviceIndex].mCurrentPathUid == null) {
                        deviceFeatures[deviceIndex].mCurrentPath = PATH_ROOT;
                        numberOfItems = NUM_ROOT_ELEMENTS;
                    } else {
                        deviceFeatures[deviceIndex].mCurrentPath = PATH_PLAYLISTS;
                        deviceFeatures[deviceIndex].mCurrentPathUid = null;
                        numberOfItems = getNumPlaylistItems();
                    }
                    break;
                case FOLDER_DOWN:
                    if (deviceFeatures[deviceIndex].mCurrentPathUid == null) {
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
                                numberOfItems =  cursor.getCount();
                                deviceFeatures[deviceIndex].mCurrentPathUid =
                                        String.valueOf(folderUid);
                                deviceFeatures[deviceIndex].mCurrentPath =
                                        PATH_PLAYLISTS;
                            } else {
                                status = DOES_NOT_EXIST;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Exception " + e);
                            changePathRspNative(INTERNAL_ERROR ,
                                    numberOfItems ,
                                    getByteAddress(device));
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
        changePathRspNative(status ,
                numberOfItems ,
                getByteAddress(device));
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

    private long getNumItems(String path, String element, int deviceIndex) {
        if (path == null || element == null)
            return 0;
        Cursor cursor = null;
        try {
            cursor = mContext.getContentResolver().query(
                deviceFeatures[deviceIndex].mMediaUri,
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

    private void playItem(byte scope, long uid, byte[] address) {
        if (DEBUG)
            Log.v(TAG, "playItem: scope: " + scope + " uid:" + uid);
        ItemAttr itemAttr = new ItemAttr(null, uid,
                Utils.getAddressStringFromByte(address));
        Message msg = mHandler.obtainMessage(MESSAGE_PLAY_ITEM, scope, 0, itemAttr);
        mHandler.sendMessage(msg);
    }

    private void processPlayItem(int scope, long uid,
            String deviceAddress) {
        if (DEBUG)
            Log.v(TAG, "processPlayItem: scope: " + scope + " uid:" + uid);
        BluetoothDevice device = mAdapter.getRemoteDevice(deviceAddress);
        int deviceIndex = getIndexForDevice(device);
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.v(TAG,"device entry not present, bailing out");
            return;
        }
        if (uid < 0) {
            if (DEBUG)
                Log.v(TAG, "invalid uid");
            playItemRspNative(DOES_NOT_EXIST ,
                    getByteAddress(device));
            return;
        }
        if (mMediaPlayers.size() > 0) {
            final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
            while (rccIterator.hasNext()) {
                final MediaPlayerInfo di = rccIterator.next();
                if (di.GetPlayerFocus()) {
                    if (!di.IsRemoteAddressable()) {
                        playItemRspNative(INTERNAL_ERROR ,
                                getByteAddress(device));
                        if (DEBUG)
                            Log.v(TAG, "Play Item fails: Player not remote" +
                                    "addressable");
                        return;
                    }
                }
            }
        }
        if (scope == SCOPE_VIRTUAL_FILE_SYS) {
            if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_ROOT)) {
                playItemRspNative(UID_A_DIRECTORY ,
                        getByteAddress(device));
            } else if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_TITLES)) {
                Cursor cursor = null;
                try {
                    cursor = mContext.getContentResolver().query(
                        deviceFeatures[deviceIndex].mMediaUri,
                        new String[] {MediaStore.Audio.Media.TITLE},
                        MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id=" + uid,
                        null, null);
                    if ((cursor == null) || (cursor.getCount() == 0)) {
                        Log.i(TAG, "No such track");
                        playItemRspNative(DOES_NOT_EXIST ,
                                getByteAddress(device));
                    } else {
                        Log.i(TAG, "Play uid:" + uid);
                        cursor.close();
                        mMediaController.getTransportControls().setRemoteControlClientPlayItem(uid, scope);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception " + e);
                    playItemRspNative(INTERNAL_ERROR ,
                            getByteAddress(device));
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            } else if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_ALBUMS)) {
                if (deviceFeatures[deviceIndex].mCurrentPathUid == null) {
                    playItemRspNative(UID_A_DIRECTORY ,
                            getByteAddress(device));
                } else {
                    Cursor cursor = null;
                    try {
                        cursor = mContext.getContentResolver().query(
                            deviceFeatures[deviceIndex].mMediaUri,
                            new String[] {MediaStore.Audio.Media.TITLE},
                            MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id=" + uid + " AND " +
                            MediaStore.Audio.Media.ALBUM_ID + "=" +
                            deviceFeatures[deviceIndex].mCurrentPathUid,
                            null, null);
                        if ((cursor == null) || (cursor.getCount() == 0)) {
                            Log.i(TAG, "No such track");
                            playItemRspNative(DOES_NOT_EXIST ,
                                    getByteAddress(device));
                        } else {
                            Log.i(TAG, "Play uid:" + uid);
                            cursor.close();
                            mMediaController.getTransportControls().setRemoteControlClientPlayItem(uid, scope);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception " + e);
                        playItemRspNative(INTERNAL_ERROR ,
                                getByteAddress(device));
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            } else if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_ARTISTS)) {
                if (deviceFeatures[deviceIndex].mCurrentPathUid == null) {
                    playItemRspNative(UID_A_DIRECTORY ,
                            getByteAddress(device));
                } else {
                    Cursor cursor = null;
                    try {
                        cursor = mContext.getContentResolver().query(
                            deviceFeatures[deviceIndex].mMediaUri,
                            new String[] {MediaStore.Audio.Media.TITLE},
                            MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id=" + uid + " AND " +
                            MediaStore.Audio.Media.ARTIST_ID + "=" +
                            deviceFeatures[deviceIndex].mCurrentPathUid,
                            null, null);
                        if ((cursor == null) || (cursor.getCount() == 0)) {
                            Log.i(TAG, "No such track");
                            playItemRspNative(DOES_NOT_EXIST ,
                                    getByteAddress(device));
                        } else {
                            Log.i(TAG, "Play uid:" + uid);
                            cursor.close();
                            mMediaController.getTransportControls().setRemoteControlClientPlayItem(uid, scope);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception " + e);
                        playItemRspNative(INTERNAL_ERROR ,
                                getByteAddress(device));
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            } else if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_PLAYLISTS)) {
                if (deviceFeatures[deviceIndex].mCurrentPathUid == null) {
                    playItemRspNative(UID_A_DIRECTORY ,
                            getByteAddress(device));
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
                                Long.parseLong(deviceFeatures[deviceIndex].mCurrentPathUid));
                        StringBuilder where = new StringBuilder();
                        where.append(MediaStore.Audio.Playlists.Members.AUDIO_ID + "=" + uid);
                        cursor = mContext.getContentResolver().query(uri, playlistMemberCols,
                                    where.toString(), null, MediaStore.Audio.Playlists.Members.
                                                                            DEFAULT_SORT_ORDER);

                        if ((cursor == null) || (cursor.getCount() == 0)) {
                            Log.i(TAG, "No such track");
                            playItemRspNative(DOES_NOT_EXIST ,
                                    getByteAddress(device));
                        } else {
                            Log.i(TAG, "Play uid:" + uid);
                            cursor.close();
                            mMediaController.getTransportControls().setRemoteControlClientPlayItem(uid, scope);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception " + e);
                        playItemRspNative(INTERNAL_ERROR ,
                                getByteAddress(device));
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            } else {
                playItemRspNative(DOES_NOT_EXIST ,
                        getByteAddress(device));

            }
        } else if (scope == SCOPE_NOW_PLAYING) {
            mMediaController.getTransportControls().setRemoteControlClientPlayItem(uid, scope);
        } else {
            playItemRspNative(DOES_NOT_EXIST ,
                    getByteAddress(device));
            Log.v(TAG, "Play Item fails: Invalid scope");
        }
    }

    private void getItemAttr(byte scope, long uid, byte numAttr, int[] attrs, byte[] address) {
        if (DEBUG)
            Log.v(TAG, "getItemAttr: scope: " + scope + " uid:" + uid +
                    " numAttr:" + numAttr);
        int i;
        ArrayList<Integer> attrList = new ArrayList<Integer>();
        for (i = 0; i < numAttr; ++i) {
            attrList.add(attrs[i]);
            if (DEBUG)
                Log.v(TAG, "attrs[" + i + "] = " + attrs[i]);
        }
        ItemAttr itemAttr = new ItemAttr(attrList, uid,
                Utils.getAddressStringFromByte(address));
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

    private void processGetItemAttr(byte scope, long uid, byte numAttr, int[] attrs,
                String deviceAddress) {
        if (DEBUG)
            Log.v(TAG, "processGetItemAttr: scope: " + scope + " uid:" + uid +
                    " numAttr:" + numAttr);
        String[] textArray;
        BluetoothDevice device = mAdapter.getRemoteDevice(deviceAddress);
        int deviceIndex = getIndexForDevice(device);
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.v(TAG,"device entry not present, bailing out");
            return;
        }
        textArray = new String[numAttr];
        if ((scope == SCOPE_VIRTUAL_FILE_SYS) || (scope == SCOPE_NOW_PLAYING)) {
            Cursor cursor = null;
            try {
                if ((deviceFeatures[deviceIndex].mMediaUri == Uri.EMPTY) ||
                        (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_INVALID))) {
                    if (DEBUG)
                        Log.v(TAG, "Browsed player not set, getItemAttr can not be processed");
                    getItemAttrRspNative((byte)0 ,attrs ,
                            textArray ,getByteAddress(device));
                    return;
                }
                cursor = mContext.getContentResolver().query(
                     deviceFeatures[deviceIndex].mMediaUri, mCursorCols,
                     MediaStore.Audio.Media.IS_MUSIC + "=1 AND _id=" + uid, null, null);
                if ((cursor == null) || (cursor.getCount() == 0)) {
                    Log.i(TAG, "Invalid track UID");
                    Log.i(TAG, "cursor is " + cursor);
                    if (cursor != null)
                        Log.i(TAG, "cursor.getCount() " + cursor.getCount());
                    getItemAttrRspNative((byte)0 ,attrs ,
                            textArray ,getByteAddress(device));
                } else {
                    int validAttrib = 0;
                    cursor.moveToFirst();
                    for (int i = 0; i < numAttr; ++i) {
                        if ((attrs[i] <= MEDIA_ATTR_MAX) && (attrs[i] >= MEDIA_ATTR_MIN)) {
                            textArray[i] = getAttributeStringFromCursor(
                                    cursor, attrs[i], deviceIndex);
                            Log.i(TAG, "textArray[" + i + "] = " + textArray[i]);
                            validAttrib ++;
                        }
                    }
                    getItemAttrRspNative(numAttr ,attrs ,
                            textArray ,getByteAddress(device));
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception " + e);
                getItemAttrRspNative((byte)0 ,attrs ,
                        textArray ,getByteAddress(device));
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        } else {
            Log.i(TAG, "Invalid scope");
            getItemAttrRspNative((byte)0 ,attrs ,
                    textArray ,getByteAddress(device));
        }
    }

    private class ItemAttr {
        ArrayList<Integer> mAttrList;
        long mUid;
        String mAddress;
        public ItemAttr (ArrayList<Integer> attrList, long uid,
                String deviceAddress) {
            mAttrList = attrList;
            mUid = uid;
            mAddress = deviceAddress;
        }
    }

    private void setAddressedPlayer(int playerId, byte[] address) {
        if (DEBUG)
            Log.v(TAG, "setAddressedPlayer: PlayerID: " + playerId);
        Message msg = mHandler.obtainMessage(MESSAGE_SET_ADDR_PLAYER, playerId, 0,
                Utils.getAddressStringFromByte(address));
        mHandler.sendMessage(msg);
    }

    private void processSetAddressedPlayer(int playerId, String deviceAddress) {
        if (DEBUG)
            Log.v(TAG, "processSetAddressedPlayer: PlayerID: " + playerId);
        String packageName = null;
        BluetoothDevice device = mAdapter.getRemoteDevice(deviceAddress);
        int deviceIndex = getIndexForDevice(device);
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.v(TAG,"device entry not present, bailing out");
            return;
        }
        if (deviceFeatures[deviceIndex].mRequestedAddressedPlayerPackageName !=
                null) {
            if (DEBUG)
                Log.v(TAG, "setAddressedPlayer: Request in progress, Reject this Request");
            setAdressedPlayerRspNative((byte)PLAYER_NOT_ADDRESSED,
                        getByteAddress(mAdapter.getRemoteDevice(deviceAddress)));
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
                if (DEBUG)
                    Log.v(TAG, "setAddressedPlayer: Already addressed, sending success");
                setAdressedPlayerRspNative((byte)OPERATION_SUCCESSFUL,
                            getByteAddress(mAdapter.getRemoteDevice(deviceAddress)));
                return;
            }
            String newPackageName = packageName.replace("com.android", "org.codeaurora");
            Intent mediaIntent = new Intent(newPackageName + ".setaddressedplayer");
            mediaIntent.setPackage(packageName);
            // This needs to be caught in respective media players
            mContext.sendBroadcast(mediaIntent);
            if (DEBUG)
                Log.v(TAG, "Intent Broadcasted: " + newPackageName +
                    ".setaddressedplayer");
            deviceFeatures[deviceIndex].mRequestedAddressedPlayerPackageName = packageName;
            deviceFeatures[deviceIndex].isMusicAppResponsePending = true;
            Message msg = mHandler.obtainMessage(MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT,
                    0, 0, deviceAddress);
            mHandler.sendMessageDelayed(msg, AVRCP_BR_RSP_TIMEOUT);
            Log.v(TAG, "Post MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT");
        } else {
            if (DEBUG)
                Log.v(TAG, "setAddressedPlayer fails: No such media player available");
            setAdressedPlayerRspNative((byte)INVALID_PLAYER_ID,
                        getByteAddress(mAdapter.getRemoteDevice(deviceAddress)));
        }
    }

    private void getFolderItems(byte scope, long start, long end, int attrCnt,
            int numAttr, int[] attrs, byte[] address) {
        if (DEBUG)
            Log.v(TAG, "getFolderItems");
        if (DEBUG)
            Log.v(TAG, "scope: " + scope + " attrCnt: " + attrCnt);
        if (DEBUG)
            Log.v(TAG, "start: " + start + " end: " + end);
        for (int i = 0; i < numAttr; ++i) {
            if (DEBUG)
                Log.v(TAG, "attrs[" + i + "] = " + attrs[i]);
        }

        FolderListEntries folderListEntries = new FolderListEntries (scope, start, end, attrCnt,
                numAttr, attrs, Utils.getAddressStringFromByte(address));
        Message msg = mHandler.obtainMessage(MESSAGE_GET_FOLDER_ITEMS, 0, 0, folderListEntries);
        mHandler.sendMessage(msg);
    }

    private void processGetFolderItems(byte scope, long start, long end, int size,
            int numAttr, int[] attrs, String deviceAddress) {
        if (DEBUG)
            Log.v(TAG, "processGetFolderItems");
        if (DEBUG)
            Log.v(TAG, "scope: " + scope + " size: " + size);
        if (DEBUG)
            Log.v(TAG, "start: " + start + " end: " + end + " numAttr: " + numAttr);
        if (scope == SCOPE_PLAYER_LIST) { // populate mediaplayer item list here
            processGetMediaPlayerItems(scope, start, end, size, numAttr, attrs,
                    deviceAddress);
        } else if ((scope == SCOPE_VIRTUAL_FILE_SYS) || (scope == SCOPE_NOW_PLAYING)) {
            for (int i = 0; i < numAttr; ++i) {
                if (DEBUG)
                    Log.v(TAG, "attrs[" + i + "] = " + attrs[i]);
            }
            processGetFolderItemsInternal(scope, start, end, size, (byte)numAttr,
                    attrs, deviceAddress);
        }
    }

    private void processGetMediaPlayerItems(byte scope, long start, long end, int size,
            int numAttr, int[] attrs, String deviceAddress) {
        byte[] folderItems = new byte[size];
        int[] folderItemLengths = new int[32];
        int availableMediaPlayers = 0;
        int count = 0;
        int positionItemStart = 0;
        BluetoothDevice device = mAdapter.getRemoteDevice(deviceAddress);
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
        if (DEBUG)
            Log.v(TAG, "Number of available MediaPlayers = " +
                    availableMediaPlayers);
        getMediaPlayerListRspNative((byte)OPERATION_SUCCESSFUL, 0x0,
                availableMediaPlayers, folderItems,
                folderItemLengths, getByteAddress(device));
    }

    private boolean isCurrentPathValid (int deviceIndex) {
        if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_ROOT) ||
            deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_TITLES) ||
            deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_ALBUMS) ||
            deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_ARTISTS) ||
            deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_PLAYLISTS)) {
            return true;
        }
        return false;
    }

    private void processGetFolderItemsInternal(byte scope, long start, long end, int size,
            byte numAttr, int[] attrs, String deviceAddress) {
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
        BluetoothDevice device = mAdapter.getRemoteDevice(deviceAddress);
        mBrowserDevice = device;

        int deviceIndex = getIndexForDevice(device);
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.v(TAG,"device entry not present, bailing out");
            return;
        }
        if (DEBUG)
            Log.v(TAG, "processGetFolderItemsInternal");
        if (DEBUG)
            Log.v(TAG, "requested attribute count" + numAttr);
        for (int count = 0; count < numAttr; count++) {
            if (DEBUG)
                Log.v(TAG, "attr[" + count + "] = " + attrs[count]);
        }

        if (scope == SCOPE_VIRTUAL_FILE_SYS) {
            // Item specific attribute's entry starts from index*7
            for (int count = 0; count < (MAX_BROWSE_ITEM_TO_SEND * 7); count++) {
                attValues[count] = "";
                attIds[count] = 0;
            }

            if (DEBUG)
                Log.v(TAG, "mCurrentPath: " +
                    deviceFeatures[deviceIndex].mCurrentPath);
            if (DEBUG)
                Log.v(TAG, "mCurrentPathUID: " +
                    deviceFeatures[deviceIndex].mCurrentPathUid);
            if (!isCurrentPathValid(deviceIndex)) {
                getFolderItemsRspNative((byte)DOES_NOT_EXIST ,
                        numItems, itemType, uid, type,
                        playable, displayName, numAtt, attValues, attIds,
                        getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                Log.v(TAG, "Current path not set");
                return;
            }

            if ((start < 0) || (end < 0) || (start > end)) {
                getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                        numItems, itemType, uid, type,
                        playable, displayName, numAtt, attValues, attIds,
                        getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                Log.e(TAG, "Wrong start/end index");
                return;
            }

            if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_ROOT)) {
                long availableItems = NUM_ROOT_ELEMENTS;
                if (start >= availableItems) {
                    Log.i(TAG, "startIteam exceeds the available item index");
                    getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                            numItems, itemType, uid, type,
                            playable, displayName, numAtt, attValues, attIds,
                            getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                    return;
                }
                if (DEBUG)
                    Log.v(TAG, "availableItems: " + availableItems);
                if (DEBUG)
                    Log.v(TAG, "reqItems: " + reqItems);
                availableItems = availableItems - start;
                if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                    availableItems = MAX_BROWSE_ITEM_TO_SEND;
                if (reqItems > availableItems)
                    reqItems = availableItems;
                if (DEBUG)
                    Log.v(TAG, "revised reqItems: " + reqItems);

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
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                    }
                }

                for (int count = 0; count < numItems; count++) {
                    Log.v(TAG, itemType[count] + "," + uid[count] + "," + type[count]);
                }
                getFolderItemsRspNative((byte)status ,
                        numItems, itemType, uid, type,
                        playable, displayName, numAtt, attValues, attIds,
                        getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
            } else if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_TITLES)) {
                long availableItems = 0;
                Cursor cursor = null;
                try {
                    cursor = mContext.getContentResolver().query(
                            deviceFeatures[deviceIndex].mMediaUri,
                            mCursorCols, MediaStore.Audio.Media.IS_MUSIC + "=1", null,
                            MediaStore.Audio.Media.DEFAULT_SORT_ORDER);
                    if (cursor != null) {
                        availableItems = cursor.getCount();
                        if (start >= availableItems) {
                            Log.i(TAG, "startIteam exceeds the available item index");
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                            return;
                        }
                        cursor.moveToFirst();
                        for (int i = 0; i < start; i++) {
                            cursor.moveToNext();
                        }
                    } else {
                        Log.i(TAG, "Error: could not fetch the elements");
                        getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                                numItems, itemType, uid, type,
                                playable, displayName, numAtt, attValues, attIds,
                                getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                        return;
                    }
                    if (DEBUG)
                        Log.v(TAG, "availableItems: " + availableItems);
                    if (DEBUG)
                        Log.v(TAG, "reqItems: " + reqItems);
                    availableItems = availableItems - start;
                    if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                        availableItems = MAX_BROWSE_ITEM_TO_SEND;
                    if (reqItems > availableItems)
                        reqItems = availableItems;
                    if (DEBUG)
                        Log.v(TAG, "revised reqItems: " + reqItems);
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
                                        getAttributeStringFromCursor(
                                        cursor, attrs[attIndex], deviceIndex);
                                attIds[(7 * index) + attIndex] = attrs[attIndex];
                                validAttrib ++;
                            }
                        }
                        numAtt[index] = (byte)validAttrib;
                        cursor.moveToNext();
                    }
                    numItems = index;
                    getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL ,
                            numItems, itemType, uid, type,
                            playable, displayName, numAtt, attValues, attIds,
                            getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                } catch(Exception e) {
                    Log.i(TAG, "Exception e" + e);
                    getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                            numItems, itemType, uid, type,
                            playable, displayName, numAtt, attValues, attIds,
                            getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            } else if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_ALBUMS)) {
                if (deviceFeatures[deviceIndex].mCurrentPathUid == null) {
                    long availableItems = 0;
                    Cursor cursor = null;
                    try {
                        availableItems = getNumItems(PATH_ALBUMS,
                                MediaStore.Audio.Media.ALBUM_ID, deviceIndex);
                        if (start >= availableItems) {
                            Log.i(TAG, "startIteam exceeds the available item index");
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                            return;
                        }
                        if (DEBUG)
                            Log.v(TAG, "availableItems: " + availableItems);
                        if (DEBUG)
                            Log.v(TAG, "reqItems: " + reqItems);

                        availableItems = availableItems - start;
                        if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                            availableItems = MAX_BROWSE_ITEM_TO_SEND;
                        if (reqItems > availableItems)
                            reqItems = (int)availableItems;
                        Log.i(TAG, "revised reqItems: " + reqItems);

                        cursor = mContext.getContentResolver().query(
                                            deviceFeatures[deviceIndex].mMediaUri, mCursorCols,
                                            MediaStore.Audio.Media.IS_MUSIC + "=1", null,
                                            MediaStore.Audio.Albums.DEFAULT_SORT_ORDER);

                        int count = 0;
                        if (cursor != null) {
                            count = cursor.getCount();
                        } else {
                            Log.i(TAG, "Error: could not fetch the elements");
                            getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
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
                            getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                        } else {
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                        }
                    } catch(Exception e) {
                        Log.i(TAG, "Exception e" + e);
                        getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                                numItems, itemType, uid, type,
                                playable, displayName, numAtt, attValues, attIds,
                                getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                } else {
                    long folderUid = Long.valueOf(deviceFeatures[deviceIndex].mCurrentPathUid);
                    long availableItems = 0;
                    Cursor cursor = null;
                    try {
                        cursor = mContext.getContentResolver().query(
                            deviceFeatures[deviceIndex].mMediaUri,
                            mCursorCols, MediaStore.Audio.Media.IS_MUSIC + "=1 AND " +
                            MediaStore.Audio.Media.ALBUM_ID + "=" + folderUid, null,
                                            MediaStore.Audio.Albums.DEFAULT_SORT_ORDER);

                        if (cursor != null) {
                            availableItems = cursor.getCount();
                            if (start >= availableItems) {
                                Log.i(TAG, "startIteam exceeds the available item index");
                                getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                                        numItems, itemType, uid, type,
                                        playable, displayName, numAtt, attValues, attIds,
                                        getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                                return;
                            }
                            cursor.moveToFirst();
                            for (int i = 0; i < start; i++) {
                                cursor.moveToNext();
                            }
                        } else {
                            Log.i(TAG, "Error: could not fetch the elements");
                            getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                            return;
                        }

                        if (DEBUG)
                            Log.v(TAG, "availableItems: " + availableItems);
                        if (DEBUG)
                            Log.v(TAG, "reqItems: " + reqItems);
                        availableItems = availableItems - start;
                        if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                            availableItems = MAX_BROWSE_ITEM_TO_SEND;
                        if (reqItems > availableItems)
                            reqItems = availableItems;
                        if (DEBUG)
                            Log.v(TAG, "revised reqItems: " + reqItems);

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
                                            getAttributeStringFromCursor(
                                            cursor, attrs[attIndex], deviceIndex);
                                    attIds[(7 * index) + attIndex] = attrs[attIndex];
                                    validAttrib ++;
                                }
                            }
                            numAtt[index] = (byte)validAttrib;
                            cursor.moveToNext();
                        }
                        numItems = index;
                        getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL ,
                                numItems, itemType, uid, type,
                                playable, displayName, numAtt, attValues, attIds,
                                getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                    } catch(Exception e) {
                        Log.i(TAG, "Exception e" + e);
                        getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                                numItems, itemType, uid, type,
                                playable, displayName, numAtt, attValues, attIds,
                                getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            } else if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_ARTISTS)) {
                if (deviceFeatures[deviceIndex].mCurrentPathUid == null) {
                    long availableItems = 0;
                    Cursor cursor = null;
                    try {
                        availableItems = getNumItems(PATH_ARTISTS,
                                    MediaStore.Audio.Media.ARTIST_ID, deviceIndex);
                        if (start >= availableItems) {
                            Log.i(TAG, "startIteam exceeds the available item index");
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                            return;
                        }

                        if (DEBUG)
                            Log.v(TAG, "availableItems: " + availableItems);
                        if (DEBUG)
                            Log.v(TAG, "reqItems: " + reqItems);
                        availableItems = availableItems - start;
                        if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                            availableItems = MAX_BROWSE_ITEM_TO_SEND;
                        if (reqItems > availableItems)
                            reqItems = (int)availableItems;
                        if (DEBUG)
                            Log.v(TAG, "revised reqItems: " + reqItems);

                        cursor = mContext.getContentResolver().query(
                            deviceFeatures[deviceIndex].mMediaUri, mCursorCols,
                            MediaStore.Audio.Media.IS_MUSIC + "=1", null,
                            MediaStore.Audio.Artists.DEFAULT_SORT_ORDER);

                        int count = 0;
                        if (cursor != null) {
                            count = cursor.getCount();
                        } else {
                            Log.i(TAG, "Error: could not fetch the elements");
                            getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
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
                            getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                        } else {
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                        }
                    } catch(Exception e) {
                        Log.i(TAG, "Exception e" + e);
                        getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                                numItems, itemType, uid, type,
                                playable, displayName, numAtt, attValues, attIds,
                                getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                } else {
                    long folderUid = Long.valueOf(deviceFeatures[deviceIndex].mCurrentPathUid);
                    long availableItems = 0;
                    Cursor cursor = null;
                    try {
                        cursor = mContext.getContentResolver().query(
                            deviceFeatures[deviceIndex].mMediaUri,
                            mCursorCols, MediaStore.Audio.Media.IS_MUSIC + "=1 AND " +
                            MediaStore.Audio.Media.ARTIST_ID + "=" + folderUid, null,
                            MediaStore.Audio.Artists.DEFAULT_SORT_ORDER);

                        if (cursor != null) {
                            availableItems = cursor.getCount();
                            if (start >= availableItems) {
                                Log.i(TAG, "startIteam exceeds the available item index");
                                getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                                        numItems, itemType, uid, type,
                                        playable, displayName, numAtt, attValues, attIds,
                                        getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                                return;
                            }
                            cursor.moveToFirst();
                            for (int i = 0; i < start; i++) {
                                cursor.moveToNext();
                            }
                        } else {
                            Log.i(TAG, "Error: could not fetch the elements");
                            getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                            return;
                        }

                        if (DEBUG)
                            Log.v(TAG, "availableItems: " + availableItems);
                        if (DEBUG)
                            Log.v(TAG, "reqItems: " + reqItems);
                        availableItems = availableItems - start;
                        if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                            availableItems = MAX_BROWSE_ITEM_TO_SEND;
                        if (reqItems > availableItems)
                            reqItems = availableItems;
                        if (DEBUG)
                            Log.v(TAG, "revised reqItems: " + reqItems);

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
                                                getAttributeStringFromCursor(
                                                cursor, attrs[attIndex], deviceIndex);
                                    attIds[(7 * index) + attIndex] = attrs[attIndex];
                                    validAttrib ++;
                                }
                            }
                            numAtt[index] = (byte)validAttrib;
                            cursor.moveToNext();
                        }
                        numItems = index;
                        getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL ,
                                numItems, itemType, uid, type,
                                playable, displayName, numAtt, attValues, attIds,
                                getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                    } catch(Exception e) {
                        Log.i(TAG, "Exception e" + e);
                        getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                                numItems, itemType, uid, type,
                                playable, displayName, numAtt, attValues, attIds,
                                getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            } else if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_PLAYLISTS)) {
                if (deviceFeatures[deviceIndex].mCurrentPathUid == null) {
                    long availableItems = 0;
                    Cursor cursor = null;
                    try {
                        availableItems = getNumPlaylistItems();
                        if (start >= availableItems) {
                            Log.i(TAG, "startIteam exceeds the available item index");
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                            return;
                        }
                        if (DEBUG)
                            Log.v(TAG, "availableItems: " + availableItems);
                        if (DEBUG)
                            Log.v(TAG, "reqItems: " + reqItems);
                        availableItems = availableItems - start;
                        if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                            availableItems = MAX_BROWSE_ITEM_TO_SEND;
                        if (reqItems > availableItems)
                            reqItems = (int)availableItems;
                        if (DEBUG)
                            Log.v(TAG, "revised reqItems: " + reqItems);

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
                            getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                            return;
                        }
                        if (count < reqItems) {
                            reqItems = count;
                        }
                        cursor.moveToFirst();
                        for (int i = 0; i < start; i++) {
                            cursor.moveToNext();
                        }
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
                            getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                        } else {
                            getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                        }
                    } catch(Exception e) {
                        Log.i(TAG, "Exception e" + e);
                        getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                                numItems, itemType, uid, type,
                                playable, displayName, numAtt, attValues, attIds,
                                getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                } else {
                    long folderUid = Long.valueOf(deviceFeatures[deviceIndex].mCurrentPathUid);
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
                                getFolderItemsRspNative((byte)RANGE_OUT_OF_BOUNDS ,
                                        numItems, itemType, uid, type,
                                        playable, displayName, numAtt, attValues, attIds,
                                        getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                                return;
                            }
                            cursor.moveToFirst();
                            for (int i = 0; i < start; i++) {
                                cursor.moveToNext();
                            }
                        } else {
                            Log.i(TAG, "Error: could not fetch the elements");
                            getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                            return;
                        }

                        if (DEBUG)
                            Log.v(TAG, "availableItems: " + availableItems);
                        if (DEBUG)
                            Log.v(TAG, "reqItems: " + reqItems);
                        availableItems = availableItems - start;
                        if (availableItems > MAX_BROWSE_ITEM_TO_SEND)
                            availableItems = MAX_BROWSE_ITEM_TO_SEND;
                        if (reqItems > availableItems)
                            reqItems = availableItems;
                        if (DEBUG)
                            Log.v(TAG, "revised reqItems: " + reqItems);

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
                                            getAttributeStringFromCursor(
                                            cursor, attrs[attIndex], deviceIndex);
                                    attIds[(7 * index) + attIndex] = attrs[attIndex];
                                    validAttrib ++;
                                }
                            }
                            numAtt[index] = (byte)validAttrib;
                            cursor.moveToNext();
                        }
                        numItems = index;
                        getFolderItemsRspNative((byte)OPERATION_SUCCESSFUL ,
                                numItems, itemType, uid, type,
                                playable, displayName, numAtt, attValues, attIds,
                                getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                    } catch(Exception e) {
                        Log.e(TAG, "Exception e" + e);
                        getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                                numItems, itemType, uid, type,
                                playable, displayName, numAtt, attValues, attIds,
                                getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            } else {
                getFolderItemsRspNative((byte)DOES_NOT_EXIST ,
                        numItems, itemType, uid, type,
                        playable, displayName, numAtt, attValues, attIds,
                        getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                Log.v(TAG, "GetFolderItems fail as player is not browsable");
            }
        } else if (scope == SCOPE_NOW_PLAYING) {
            if (mMediaPlayers.size() > 0) {
                final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
                while (rccIterator.hasNext()) {
                    final MediaPlayerInfo di = rccIterator.next();
                    if (di.GetPlayerFocus()) {
                        if (!di.IsRemoteAddressable() ||
                             deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_INVALID)) {
                            getFolderItemsRspNative((byte)INTERNAL_ERROR ,
                                    numItems, itemType, uid, type,
                                    playable, displayName, numAtt, attValues, attIds,
                                    getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                            Log.e(TAG, "GetFolderItems fails: addressed player is not browsable");
                            return;
                        }
                    }
                }
            }
            mMediaController.getTransportControls().getRemoteControlClientNowPlayingEntries();
            mCachedRequest = new CachedRequest(start, end, numAttr, attrs, true);
        }
    }

    private void registerNotification(int eventId, int param, byte[] address) {
        Message msg = mHandler.obtainMessage(MESSAGE_REGISTER_NOTIFICATION, eventId,
                param, Utils.getAddressStringFromByte(address));
        mHandler.sendMessage(msg);
    }

    private void processRCCStateChange(String callingPackageName, int isFocussed, int isAvailable) {
        Log.v(TAG, "processRCCStateChange: " + callingPackageName);
        boolean available = false;
        boolean focussed = false;
        boolean isResetFocusRequired = false;
        BluetoothDevice device = null;
        if (isFocussed == 1)
            focussed = true;
        if (isAvailable == 1)
            available = true;

        if (focussed) {
            isResetFocusRequired = true;
            for (int i = 0; i < maxAvrcpConnections; i++) {
                if (deviceFeatures[i].mRequestedAddressedPlayerPackageName != null) {
                    if (callingPackageName.equals(
                            deviceFeatures[i].mRequestedAddressedPlayerPackageName)) {
                        mHandler.removeMessages(MESSAGE_SET_ADDR_PLAYER_REQ_TIMEOUT);
                        if (deviceFeatures[i].isMusicAppResponsePending ==
                                true) {
                            device = deviceFeatures[i].mCurrentDevice;
                            deviceFeatures[i].isMusicAppResponsePending = false;
                        }
                        if (device == null) {
                            Log.e(TAG,"ERROR!!!! device is null");
                            return;
                        }

                        if (DEBUG)
                            Log.v(TAG, "SetAddressedPlayer succeeds for: "
                                + deviceFeatures[i].mRequestedAddressedPlayerPackageName);
                        deviceFeatures[i].mRequestedAddressedPlayerPackageName = null;
                        setAdressedPlayerRspNative((byte)OPERATION_SUCCESSFUL,
                                    getByteAddress(deviceFeatures[i].mCurrentDevice));

                    } else {
                        if (DEBUG)
                            Log.v(TAG, "SetaddressedPlayer package mismatch with: "
                                + deviceFeatures[i].mRequestedAddressedPlayerPackageName);
                    }
                } else {
                    if (DEBUG)
                        Log.v(TAG, "SetaddressedPlayer request is not in progress");
                }
            }
        }

        if (mMediaPlayers.size() > 0) {
            final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
            while (rccIterator.hasNext()) {
                final MediaPlayerInfo di = rccIterator.next();
                if (di.RetrievePlayerPackageName().equals(callingPackageName)) {
                    isResetFocusRequired = false;
                    if (di.GetPlayerAvailablility() != available) {
                        di.SetPlayerAvailablility(available);
                        if (DEBUG)
                            Log.v(TAG, "setting " + callingPackageName +
                                    " availability: " + available);
                        if (mHandler != null) {
                            if (DEBUG)
                                Log.v(TAG, "Send MSG_UPDATE_AVAILABLE_PLAYERS");
                            mHandler.obtainMessage(MSG_UPDATE_AVAILABLE_PLAYERS,
                                    0, 0, 0).sendToTarget();
                        }
                    }
                    if (di.GetPlayerFocus() != focussed) {
                        di.SetPlayerFocus(focussed);
                        if (DEBUG)
                            Log.v(TAG, "setting " + callingPackageName + " focus: " + focussed);
                        if(focussed) {
                            if (mHandler != null) {
                                if (DEBUG)
                                    Log.v(TAG, "Send MSG_UPDATE_ADDRESSED_PLAYER: " +
                                    di.RetrievePlayerId());
                                mHandler.obtainMessage(MSG_UPDATE_ADDRESSED_PLAYER,
                                        di.RetrievePlayerId(), 0, 0).sendToTarget();
                            }
                        }
                    }
                    break;
                }
            }
        }

        if (DEBUG)
            Log.v(TAG, "isResetFocusRequired: " + isResetFocusRequired);

        if (focussed) {
            // this is applicable only if list contains more than one media players
            if (mMediaPlayers.size() > 0) {
                final Iterator<MediaPlayerInfo> rccIterator = mMediaPlayers.iterator();
                while (rccIterator.hasNext()) {
                    final MediaPlayerInfo di = rccIterator.next();
                    if (!(di.RetrievePlayerPackageName().equals(callingPackageName))) {
                        if (DEBUG)
                            Log.v(TAG, "setting " +
                                    callingPackageName + " focus: false");
                        di.SetPlayerFocus(false); // reset focus for all other players
                    }
                }
            }
        }

        if(isResetFocusRequired) {
            for (int i = 0; i < maxAvrcpConnections; i++) {
                if (mHandler != null) {
                    if (DEBUG)
                        Log.v(TAG, "Send MSG_UPDATE_ADDRESSED_PLAYER: 0");
                    mHandler.obtainMessage(MSG_UPDATE_ADDRESSED_PLAYER,
                            0, 0, 0).sendToTarget();
                }
            }
        }
    }

    private void processRegisterNotification(int eventId, int param,
            String deviceAddress) {
        BluetoothDevice device = mAdapter.getRemoteDevice(deviceAddress);
        int deviceIndex = getIndexForDevice(device);
        Log.v(TAG,"processRegisterNotification: eventId" + eventId);
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.v(TAG,"device entry not present, bailing out");
            return;
        }
        switch (eventId) {
            case EVT_PLAY_STATUS_CHANGED:
                deviceFeatures[deviceIndex].mPlayStatusChangedNT =
                        NOTIFICATION_TYPE_INTERIM;
                registerNotificationRspPlayStatusNative(
                        deviceFeatures[deviceIndex].mPlayStatusChangedNT,
                        convertPlayStateToPlayStatus(
                        deviceFeatures[deviceIndex].mCurrentPlayState),
                        getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                break;

            case EVT_TRACK_CHANGED:
                deviceFeatures[deviceIndex].mTrackChangedNT =
                        NOTIFICATION_TYPE_INTERIM;
                sendTrackChangedRsp(device);
                break;

            case EVT_PLAY_POS_CHANGED:
                long songPosition = getPlayPosition(deviceFeatures[deviceIndex].mCurrentDevice);
                deviceFeatures[deviceIndex].mPlayPosChangedNT = NOTIFICATION_TYPE_INTERIM;
                deviceFeatures[deviceIndex].mPlaybackIntervalMs = (long)param * 1000L;
                if (mCurrentPosMs != PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
                    deviceFeatures[deviceIndex].mNextPosMs = songPosition +
                                deviceFeatures[deviceIndex].mPlaybackIntervalMs;
                    deviceFeatures[deviceIndex].mPrevPosMs = songPosition -
                                deviceFeatures[deviceIndex].mPlaybackIntervalMs;
                    if (isPlayingState(deviceFeatures[deviceIndex].mCurrentPlayState)) {
                        Message msg = mHandler.obtainMessage(MESSAGE_PLAY_INTERVAL_TIMEOUT,
                                0, 0, deviceFeatures[deviceIndex].mCurrentDevice);
                        mHandler.sendMessageDelayed(msg,
                                deviceFeatures[deviceIndex].mPlaybackIntervalMs);
                    }
                }
                registerNotificationRspPlayPosNative(deviceFeatures[deviceIndex].mPlayPosChangedNT,
                        (int)getPlayPosition(deviceFeatures[deviceIndex].mCurrentDevice) ,
                        getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                if (DEBUG)
                    Log.v(TAG,"mPlayPosChangedNT updated for index " +
                        deviceFeatures[deviceIndex].mPlayPosChangedNT +
                        " index " + deviceIndex);
                break;

            case EVT_APP_SETTINGS_CHANGED:
                deviceFeatures[deviceIndex].mPlayerStatusChangeNT = NOTIFICATION_TYPE_INTERIM;
                sendPlayerAppChangedRsp(deviceFeatures[deviceIndex].mPlayerStatusChangeNT,
                        device);
                break;

            case EVT_ADDRESSED_PLAYER_CHANGED:
                if (DEBUG)
                    Log.v(TAG, "Process EVT_ADDRESSED_PLAYER_CHANGED Interim: Player ID: "
                            + mAddressedPlayerId);
                deviceFeatures[deviceIndex].mAddressedPlayerChangedNT = NOTIFICATION_TYPE_INTERIM;
                registerNotificationRspAddressedPlayerChangedNative(
                        deviceFeatures[deviceIndex].mAddressedPlayerChangedNT ,
                        mAddressedPlayerId ,
                        getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                break;

            case EVT_AVAILABLE_PLAYERS_CHANGED:
                deviceFeatures[deviceIndex].mAvailablePlayersChangedNT = NOTIFICATION_TYPE_INTERIM;
                registerNotificationRspAvailablePlayersChangedNative(
                        deviceFeatures[deviceIndex].mAvailablePlayersChangedNT,
                        getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                break;

            case EVT_NOW_PLAYING_CONTENT_CHANGED:
                deviceFeatures[deviceIndex].mNowPlayingContentChangedNT = NOTIFICATION_TYPE_INTERIM;
                registerNotificationRspNowPlayingContentChangedNative(
                        deviceFeatures[deviceIndex].mNowPlayingContentChangedNT ,
                        getByteAddress(deviceFeatures[deviceIndex].mCurrentDevice));
                break;

            default:
                Log.v(TAG, "processRegisterNotification: Unhandled Type: " + eventId);
                break;
        }
    }

    private void handlePassthroughCmd(int id, int keyState,
                byte[] address) {
        switch (id) {
            case BluetoothAvrcp.PASSTHROUGH_ID_REWIND:
                rewind(keyState, Utils.getAddressStringFromByte(address));
                break;
            case BluetoothAvrcp.PASSTHROUGH_ID_FAST_FOR:
                fastForward(keyState, Utils.getAddressStringFromByte(address));
                break;
        }
    }

    private void changePositionBy(long amount, String deviceAddress) {
        long currentPosMs = getPlayPosition(mAdapter.getRemoteDevice(deviceAddress));
        if (currentPosMs == -1L) return;
        long newPosMs = Math.max(0L, currentPosMs + amount);
        mMediaController.getTransportControls().seekTo(newPosMs);
    }

    private int getSkipMultiplier() {
        long currentTime = SystemClock.elapsedRealtime();
        long multi = (long) Math.pow(2, (currentTime - mSkipStartTime)/SKIP_DOUBLE_INTERVAL);
        return (int) Math.min(MAX_MULTIPLIER_VALUE, multi);
    }

    private void sendTrackChangedRsp(BluetoothDevice device) {
        byte[] track = new byte[TRACK_ID_SIZE];
        long TrackNumberRsp = -1L;
        int deviceIndex = getIndexForDevice(device);
        if(DEBUG) Log.v(TAG,"mCurrentPlayState" +
                deviceFeatures[deviceIndex].mCurrentPlayState );

        TrackNumberRsp = mTrackNumber;

        /* track is stored in big endian format */
        for (int i = 0; i < TRACK_ID_SIZE; ++i) {
            track[i] = (byte) (TrackNumberRsp >> (56 - 8 * i));
        }
        registerNotificationRspTrackChangeNative(deviceFeatures[deviceIndex].mTrackChangedNT ,
                track ,getByteAddress(device));

    }

    private void sendPlayerAppChangedRsp(int rsptype, BluetoothDevice device) {
        int j = 0;
        byte i = NUMPLAYER_ATTRIBUTE*2;
        byte [] retVal = new byte [i];
        retVal[j++] = ATTRIBUTE_REPEATMODE;
        retVal[j++] = settingValues.repeat_value;
        retVal[j++] = ATTRIBUTE_SHUFFLEMODE;
        retVal[j++] = settingValues.shuffle_value;
        registerNotificationPlayerAppRspNative(rsptype,
                i, retVal,
                getByteAddress(device));
    }

    private long getPlayPosition(BluetoothDevice device) {
        long songPosition = -1L;
        if (device != null) {
            int deviceIndex = getIndexForDevice(device);
            if (deviceIndex == INVALID_DEVICE_INDEX) {
                Log.e(TAG,"Device index is not valid in getPlayPosition");
                return songPosition;
            }
            if (mCurrentPosMs != PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
                if (deviceFeatures[deviceIndex].mCurrentPlayState.getState()
                    == PlaybackState.STATE_PLAYING) {
                    songPosition = SystemClock.elapsedRealtime() - mPlayStartTimeMs +
                                            mCurrentPosMs;
                } else {
                    songPosition = mCurrentPosMs;
                }
            }
        } else {
            if (mCurrentPosMs != PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
                if (mCurrentPlayerState.getState() == PlaybackState.STATE_PLAYING) {
                    songPosition = SystemClock.elapsedRealtime() -
                                   mPlayStartTimeMs + mCurrentPosMs;
                } else {
                    songPosition = mCurrentPosMs;
                }
            }
        }
        if (DEBUG) Log.v(TAG, "getPlayPosition position: " + songPosition + " Device:"
                                                                                + device);
        return songPosition;
    }

    private String getAttributeStringFromCursor(Cursor cursor, int attrId, int deviceIndex) {
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
                if (deviceFeatures[deviceIndex].mCurrentPath.equals(PATH_PLAYLISTS)) {
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
        if (DEBUG)
            Log.v(TAG, "getAttributeStringFromCursor: attrId = "
                    + attrId + " str = " + attrStr);
        return attrStr;
    }

    private int convertPlayStateToPlayStatus(PlaybackState state) {
        int playStatus = PLAYSTATUS_ERROR;
        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
                playStatus = PLAYSTATUS_PLAYING;
                break;

            case PlaybackState.STATE_STOPPED:
            case PlaybackState.STATE_NONE:
                playStatus = PLAYSTATUS_STOPPED;
                break;

            case PlaybackState.STATE_PAUSED:
                playStatus = PLAYSTATUS_PAUSED;
                break;

            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
                playStatus = PLAYSTATUS_FWD_SEEK;
                break;

            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
                playStatus = PLAYSTATUS_REV_SEEK;
                break;

            case PlaybackState.STATE_ERROR:
                playStatus = PLAYSTATUS_ERROR;
                break;

        }
        return playStatus;
    }

    private boolean isPlayingState(PlaybackState state) {
        return (state.getState() == PlaybackState.STATE_PLAYING) ||
                (state.getState() == PlaybackState.STATE_BUFFERING);
    }

    /**
     * This is called from AudioService. It will return whether this device supports abs volume.
     * NOT USED AT THE MOMENT.
     * returns true only when both playing devices support absolute volume
     */
    public boolean isAbsoluteVolumeSupported() {
        List<Byte> absVolumeSupported = new ArrayList<Byte>();
        for (int i = 0; i < maxAvrcpConnections; i++ ) {
            if (deviceFeatures[i].mCurrentDevice != null) {
                // add 1 in byte list if absolute volume is supported
                // add 0 in byte list if absolute volume not supported
                if ((deviceFeatures[i].mFeatures &
                        BTRC_FEAT_ABSOLUTE_VOLUME) != 0) {
                    Log.v(TAG, "isAbsoluteVolumeSupported: yes, for dev: " + i);
                    absVolumeSupported.add((byte)1);
                } else {
                    Log.v(TAG, "isAbsoluteVolumeSupported: no, for dev: " + i);
                    absVolumeSupported.add((byte)0);
                }
            }
        }
        return !(absVolumeSupported.contains((byte)0) || absVolumeSupported.isEmpty());
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
        mHandler.removeMessages(MESSAGE_ADJUST_VOLUME);
        Message msg = mHandler.obtainMessage(MESSAGE_SET_ABSOLUTE_VOLUME, volume, 0);
        mHandler.sendMessage(msg);
    }

    /* Called in the native layer as a btrc_callback to return the volume set on the carkit in the
     * case when the volume is change locally on the carkit. This notification is not called when
     * the volume is changed from the phone.
     *
     * This method will send a message to our handler to change the local stored volume and notify
     * AudioService to update the UI
     */
    private void volumeChangeCallback(int volume, int ctype, byte[] address) {
        Message msg = mHandler.obtainMessage(MESSAGE_VOLUME_CHANGED, volume,
                ctype, Utils.getAddressStringFromByte(address));
        mHandler.sendMessage(msg);
    }

    private void notifyVolumeChanged(int volume, BluetoothDevice device) {
        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume,
                      AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_BLUETOOTH_ABS_VOLUME);
    }

    private int convertToAudioStreamVolume(int volume) {
        // Rescale volume to match AudioSystem's volume
        return (int) Math.floor((double) volume*mAudioStreamMax/AVRCP_MAX_VOL);
    }

    private int convertToAvrcpVolume(int volume) {
        return (int) Math.ceil((double) volume*AVRCP_MAX_VOL/mAudioStreamMax);
    }

    private void blackListCurrentDevice(int i) {
        String mAddress = null;
        if (deviceFeatures[i].mCurrentDevice == null) {
            Log.v(TAG, "blackListCurrentDevice: Device is null");
            return;
        }
        mAddress  = deviceFeatures[i].mCurrentDevice.getAddress();
        mFeatures &= ~BTRC_FEAT_ABSOLUTE_VOLUME;
        mAudioManager.avrcpSupportsAbsoluteVolume(mAddress, isAbsoluteVolumeSupported());

        SharedPreferences pref = mContext.getSharedPreferences(ABSOLUTE_VOLUME_BLACKLIST,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        editor.putBoolean(mAddress, true);
        editor.commit();
    }

    private int modifyRcFeatureFromBlacklist(int feature, String address) {
        SharedPreferences pref = mContext.getSharedPreferences(ABSOLUTE_VOLUME_BLACKLIST,
                Context.MODE_PRIVATE);
        if (!pref.contains(address)) {
            return feature;
        }
        if (pref.getBoolean(address, false)) {
            feature &= ~BTRC_FEAT_ABSOLUTE_VOLUME;
        }
        return feature;
    }

    public void resetBlackList(String address) {
        SharedPreferences pref = mContext.getSharedPreferences(ABSOLUTE_VOLUME_BLACKLIST,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        editor.remove(address);
        editor.commit();
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
                        Log.v(TAG, "Pending SetAttribute contains Equalizer");
                        if(data[i+1] == ATTRIBUTE_NOTSUPPORTED) {
                            ret = false;
                        } else {
                            ret = true;
                        }
                    }
                break;
                case ATTRIBUTE_REPEATMODE:
                    if (mPendingSetAttributes.contains(new Integer(ATTRIBUTE_REPEATMODE))) {
                        Log.v(TAG, "Pending SetAttribute contains Repeat");
                        if(data[i+1] == ATTRIBUTE_NOTSUPPORTED) {
                            ret = false;
                        } else {
                            ret = true;
                        }
                    }
                break;
                case ATTRIBUTE_SHUFFLEMODE:
                    if (mPendingSetAttributes.contains(new Integer(ATTRIBUTE_SHUFFLEMODE))) {
                        Log.v(TAG, "Pending SetAttribute contains Shuffle");
                        if(data[i+1] == ATTRIBUTE_NOTSUPPORTED) {
                            ret = false;
                        } else {
                            ret = true;
                        }
                    }
                break;
            }
        }
        return ret;
    }

    //PDU ID 0x11
    private void onListPlayerAttributeRequest(byte[] address) {
        if (DEBUG)
            Log.v(TAG, "onListPlayerAttributeRequest");
        Intent intent = new Intent(PLAYERSETTINGS_REQUEST);
        intent.putExtra(COMMAND, CMDGET);
        intent.putExtra(EXTRA_GET_COMMAND, GET_ATTRIBUTE_IDS);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        int deviceIndex =
                getIndexForDevice(mAdapter.getRemoteDevice(
                        Utils.getAddressStringFromByte(address)));
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.e(TAG,"invalid index for device");
            return;
        }
        deviceFeatures[deviceIndex].isMusicAppResponsePending = true;
        Message msg = mHandler.obtainMessage(MESSAGE_PLAYERSETTINGS_TIMEOUT,
                GET_ATTRIBUTE_IDS,0 ,
                Utils.getAddressStringFromByte(address));
        mPendingCmds.add(new Integer(msg.arg1));
        mHandler.sendMessageDelayed(msg, 500);
    }

    //PDU ID 0x12
    private void onListPlayerAttributeValues (byte attr, byte[] address) {
        if (DEBUG)Log.v(TAG, "onListPlayerAttributeValues");
        Intent intent = new Intent(PLAYERSETTINGS_REQUEST);
        intent.putExtra(COMMAND, CMDGET);
        intent.putExtra(EXTRA_GET_COMMAND, GET_VALUE_IDS);
        intent.putExtra(EXTRA_ATTRIBUTE_ID, attr);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        mPlayerSettings.attr = attr;
        int deviceIndex =
                getIndexForDevice(mAdapter.getRemoteDevice(
                Utils.getAddressStringFromByte(address)));
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.e(TAG,"invalid index for device");
            return;
        }

        deviceFeatures[deviceIndex].isMusicAppResponsePending = true;

        Message msg = mHandler.obtainMessage();
        msg.what = MESSAGE_PLAYERSETTINGS_TIMEOUT;
        msg.arg1 = GET_VALUE_IDS;
        msg.arg2 = 0;
        msg.obj = Utils.getAddressStringFromByte(address);
        mPendingCmds.add(new Integer(msg.arg1));
        mHandler.sendMessageDelayed(msg, 500);
    }


    //PDU ID 0x13
    private void onGetPlayerAttributeValues (byte attr ,int[] arr ,
            byte[] address)
    {
        if (DEBUG)
            Log.v(TAG, "onGetPlayerAttributeValues: num of attrib " + attr );
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
        int deviceIndex =
                getIndexForDevice(mAdapter.getRemoteDevice(
                Utils.getAddressStringFromByte(address)));
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.e(TAG,"invalid index for device");
            return;
        }
        deviceFeatures[deviceIndex].isMusicAppResponsePending = true;

        Message msg = mHandler.obtainMessage();
        msg.what = MESSAGE_PLAYERSETTINGS_TIMEOUT;
        msg.arg1 = GET_ATTRIBUTE_VALUES;
        msg.arg2 = 0;
        msg.obj = Utils.getAddressStringFromByte(address);
        mPendingCmds.add(new Integer(msg.arg1));
        mHandler.sendMessageDelayed(msg, 500);
    }

    //PDU 0x14
    private void setPlayerAppSetting( byte num, byte [] attr_id, byte [] attr_val,
            byte[] address)
    {
        if (DEBUG)
            Log.v(TAG, "setPlayerAppSetting: number of attributes" + num );
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
        int deviceIndex =
                getIndexForDevice(mAdapter.getRemoteDevice(
                Utils.getAddressStringFromByte(address)));
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.e(TAG,"invalid index for device");
            return;
        }

        deviceFeatures[deviceIndex].isMusicAppResponsePending = true;

        Message msg = mHandler.obtainMessage();
        msg.what = MESSAGE_PLAYERSETTINGS_TIMEOUT;
        msg.arg1 = SET_ATTRIBUTE_VALUES;
        msg.arg2 = 0;
        msg.obj = Utils.getAddressStringFromByte(address);
        mPendingCmds.add(new Integer(msg.arg1));
        mHandler.sendMessageDelayed(msg, 500);
    }

    //PDU 0x15
    private void getplayerattribute_text(byte attr , byte [] attrIds,
            byte[] address)
    {
        if(DEBUG) Log.d(TAG, "getplayerattribute_text " + attr +" attrIDsNum "
                                                        + attrIds.length);
        Intent intent = new Intent(PLAYERSETTINGS_REQUEST);
        Message msg = mHandler.obtainMessage();
        intent.putExtra(COMMAND, CMDGET);
        intent.putExtra(EXTRA_GET_COMMAND, GET_ATTRIBUTE_TEXT);
        intent.putExtra(EXTRA_ATTIBUTE_ID_ARRAY, attrIds);
        mPlayerSettings.attrIds = new byte [attr];
        for (int i = 0; i < attr; i++)
            mPlayerSettings.attrIds[i] = attrIds[i];
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        int deviceIndex =
                getIndexForDevice(mAdapter.getRemoteDevice(
                Utils.getAddressStringFromByte(address)));
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.e(TAG,"invalid index for device");
            return;
        }

        deviceFeatures[deviceIndex].isMusicAppResponsePending = true;

        msg.what = MESSAGE_PLAYERSETTINGS_TIMEOUT;
        msg.arg1 = GET_ATTRIBUTE_TEXT;
        msg.arg2 = 0;
        msg.obj = Utils.getAddressStringFromByte(address);
        mPendingCmds.add(new Integer(msg.arg1));
        mHandler.sendMessageDelayed(msg, 500);
   }

    //PDU 0x15
    private void getplayervalue_text(byte attr_id , byte num_value , byte [] value,
            byte[] address)
    {
        if(DEBUG) Log.d(TAG, "getplayervalue_text id " + attr_id +" num_value " + num_value
                                                           +" length " + value.length);
        Intent intent = new Intent(PLAYERSETTINGS_REQUEST);
        Message msg = mHandler.obtainMessage();
        intent.putExtra(COMMAND, CMDGET);
        intent.putExtra(EXTRA_GET_COMMAND, GET_VALUE_TEXT);
        intent.putExtra(EXTRA_ATTRIBUTE_ID, attr_id);
        intent.putExtra(EXTRA_VALUE_ID_ARRAY, value);
        mPlayerSettings.attrIds = new byte [num_value];
        int deviceIndex =
                getIndexForDevice(mAdapter.getRemoteDevice(
                Utils.getAddressStringFromByte(address)));
        if (deviceIndex == INVALID_DEVICE_INDEX) {
            Log.e(TAG,"invalid index for device");
            return;
        }
        deviceFeatures[deviceIndex].isMusicAppResponsePending = true;

        for (int i = 0; i < num_value; i++)
            mPlayerSettings.attrIds[i] = value[i];
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        msg.what = MESSAGE_PLAYERSETTINGS_TIMEOUT;
        msg.arg1 = GET_VALUE_TEXT;
        msg.arg2 = 0;
        msg.obj = Utils.getAddressStringFromByte(address);
        mPendingCmds.add(new Integer(msg.arg1));
        mHandler.sendMessageDelayed(msg, 500);
    }

    /**
     * This is called from A2dpStateMachine to set A2dp audio state.
     */
    public void setA2dpAudioState(int state, BluetoothDevice device) {
        Message msg = mHandler.obtainMessage(MESSAGE_SET_A2DP_AUDIO_STATE, state,
                0, device);
        mHandler.sendMessage(msg);
    }

    public void setAvrcpConnectedDevice(BluetoothDevice device) {
        Log.i(TAG,"Device added is " + device);
        for (int i = 0; i < maxAvrcpConnections; i++) {
            if (deviceFeatures[i].mCurrentDevice != null &&
                    deviceFeatures[i].mCurrentDevice.equals(device)) {
                Log.v(TAG,"device is already added in connected list, ignore now");
                return;
            }
        }
        for (int i = 0; i < maxAvrcpConnections; i++ ) {
            if (deviceFeatures[i].mCurrentDevice == null) {
                deviceFeatures[i].mCurrentDevice = device;
                /*Playstate is explicitly updated here to take care of cases
                        where play state update is missed because of that happening
                        even before Avrcp connects*/
                deviceFeatures[i].mCurrentPlayState = mCurrentPlayerState;
                Log.i(TAG,"play status updated on Avrcp connection as: " +
                                                    mCurrentPlayerState);
                Log.i(TAG,"device added at " + i);
                break;
            }
        }
    }

    public boolean isAvrcpConnected() {
        boolean ret = false;
        for (int i = 0; i < maxAvrcpConnections; i++) {
            if (deviceFeatures[i].mCurrentDevice != null) {
                ret = true;
                break;
            }
        }
        Log.i(TAG,"isAvrcpConnected: " + ret);
        return ret;
    }

    private int getIndexForDevice(BluetoothDevice device) {
        for (int i = 0; i < maxAvrcpConnections; i++) {
            if (deviceFeatures[i].mCurrentDevice != null &&
                    deviceFeatures[i].mCurrentDevice.equals(device)) {
                Log.i(TAG,"device found at index " + i);
                return i;
            }
        }
        Log.e(TAG, "returning invalid index");
        return INVALID_DEVICE_INDEX;
    }

    public void cleanupDeviceFeaturesIndex (int index) {
        Log.i(TAG,"cleanupDeviceFeaturesIndex index:" + index);
        deviceFeatures[index].mCurrentDevice = null;
        deviceFeatures[index].mCurrentPlayState = new PlaybackState.Builder().setState(PlaybackState.STATE_NONE, -1L, 0.0f).build();;
        deviceFeatures[index].mPlayStatusChangedNT = NOTIFICATION_TYPE_CHANGED;
        deviceFeatures[index].mPlayerStatusChangeNT = NOTIFICATION_TYPE_CHANGED;
        deviceFeatures[index].mTrackChangedNT = NOTIFICATION_TYPE_CHANGED;
        deviceFeatures[index].mPlaybackIntervalMs = 0L;
        deviceFeatures[index].mPlayPosChangedNT = NOTIFICATION_TYPE_CHANGED;
        deviceFeatures[index].mFeatures = 0;
        deviceFeatures[index].mAbsoluteVolume = -1;
        deviceFeatures[index].mLastSetVolume = -1;
        deviceFeatures[index].mLastDirection = 0;
        deviceFeatures[index].mVolCmdSetInProgress = false;
        deviceFeatures[index].mVolCmdAdjustInProgress = false;
        deviceFeatures[index].mAbsVolRetryTimes = 0;
        deviceFeatures[index].keyPressState = KEY_STATE_RELEASE; //Key release state
        deviceFeatures[index].mAddressedPlayerChangedNT = NOTIFICATION_TYPE_CHANGED;
        deviceFeatures[index].mAvailablePlayersChangedNT = NOTIFICATION_TYPE_CHANGED;
        deviceFeatures[index].mNowPlayingContentChangedNT = NOTIFICATION_TYPE_CHANGED;
        deviceFeatures[index].mRequestedAddressedPlayerPackageName = null;
        deviceFeatures[index].mCurrentPath = PATH_INVALID;
        deviceFeatures[index].mCurrentPathUid = null;
        deviceFeatures[index].mMediaUri = Uri.EMPTY;
        deviceFeatures[index].isMusicAppResponsePending = false;
        deviceFeatures[index].isBrowsingSupported = false;
        deviceFeatures[index].isAbsoluteVolumeSupportingDevice = false;
    }
    /**
     * This is called from A2dpStateMachine to set A2dp Connected device to null on disconnect.
     */
    public void setAvrcpDisconnectedDevice(BluetoothDevice device) {
        for (int i = 0; i < maxAvrcpConnections; i++ ) {
            if (deviceFeatures[i].mCurrentDevice !=null &&
                    deviceFeatures[i].mCurrentDevice.equals(device)) {
                // initiate cleanup for all variables;
                Log.i(TAG,"Device removed is " + device);
                Log.i(TAG,"removed at " + i);
                deviceFeatures[i].mCurrentDevice = null;
                cleanupDeviceFeaturesIndex(i);
                /* device is disconnect and some response form music app was
                 * pending for this device clear it.*/
                if (mBrowserDevice != null &&
                        mBrowserDevice.equals(device)) {
                    Log.i(TAG,"clearing mBrowserDevice on disconnect");
                    mBrowserDevice = null;
                }
                break;
            }
        }
        mAudioManager.avrcpSupportsAbsoluteVolume(device.getAddress(),
                isAbsoluteVolumeSupported());
        Log.v(TAG," update audio manager for abs vol state = "
                + isAbsoluteVolumeSupported());
        for (int i = 0; i < maxAvrcpConnections; i++ ) {
            if (deviceFeatures[i].mCurrentDevice != null) {
                if (isAbsoluteVolumeSupported() &&
                        deviceFeatures[i].mAbsoluteVolume != -1) {
                    notifyVolumeChanged(deviceFeatures[i].mAbsoluteVolume,
                            deviceFeatures[i].mCurrentDevice);
                    Log.v(TAG," update audio manager for abs vol  = "
                            + deviceFeatures[i].mAbsoluteVolume);
                }
                break;
            }
        }
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
    }

    private void onConnectionStateChanged(boolean connected, byte[] address) {
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
            (Utils.getAddressStringFromByte(address));
        Log.d(TAG, "onConnectionStateChanged state: " + connected + " Addr: " + device);
        if (connected) {
            setAvrcpConnectedDevice(device);
        } else {
            setAvrcpDisconnectedDevice(device);
        }
    }

    public void dump(StringBuilder sb) {
        sb.append("AVRCP:\n");
        for (int i = 0; i < maxAvrcpConnections; i++) {
            Log.v(TAG,"for index " + i);
            ProfileService.println(sb, "mTransportControlFlags: " + mTransportControlFlags);
            ProfileService.println(sb, "mCurrentPlayState: " + deviceFeatures[i].mCurrentPlayState);
            ProfileService.println(sb, "mPlayStatusChangedNT: " + deviceFeatures[i].mPlayStatusChangedNT);
            ProfileService.println(sb, "mTrackChangedNT: " + deviceFeatures[i].mTrackChangedNT);
            ProfileService.println(sb, "mTrackNumber: " + mTrackNumber);
            ProfileService.println(sb, "mCurrentPosMs: " + mCurrentPosMs);
            ProfileService.println(sb, "mPlayStartTimeMs: " + mPlayStartTimeMs);
            ProfileService.println(sb, "mSongLengthMs: " + mSongLengthMs);
            ProfileService.println(sb, "mPlaybackIntervalMs: " + deviceFeatures[i].mPlaybackIntervalMs);
            ProfileService.println(sb, "mPlayPosChangedNT: " + deviceFeatures[i].mPlayPosChangedNT);
            ProfileService.println(sb, "mNextPosMs: " + deviceFeatures[i].mNextPosMs);
            ProfileService.println(sb, "mPrevPosMs: " + deviceFeatures[i].mPrevPosMs);
            ProfileService.println(sb, "mSkipStartTime: " + mSkipStartTime);
            ProfileService.println(sb, "mFeatures: " + deviceFeatures[i].mFeatures);
            ProfileService.println(sb, "mAbsoluteVolume: " + deviceFeatures[i].mAbsoluteVolume);
            ProfileService.println(sb, "mLastSetVolume: " + deviceFeatures[i].mLastSetVolume);
            ProfileService.println(sb, "mLastDirection: " + deviceFeatures[i].mLastDirection);
            ProfileService.println(sb, "mVolumeStep: " + mVolumeStep);
            ProfileService.println(sb, "mAudioStreamMax: " + mAudioStreamMax);
            ProfileService.println(sb, "mVolCmdInProgress: " + deviceFeatures[i].mVolCmdSetInProgress);
            ProfileService.println(sb, "mVolCmdInProgress: " + deviceFeatures[i].mVolCmdAdjustInProgress);
            ProfileService.println(sb, "mAbsVolRetryTimes: " + deviceFeatures[i].mAbsVolRetryTimes);
            ProfileService.println(sb, "mSkipAmount: " + mSkipAmount);
        }
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
    private native void initNative(int maxConnections);
    private native void cleanupNative();
    private native boolean getPlayStatusRspNative(int playStatus, int songLen, int
            songPos, byte[] address);
    private native boolean getElementAttrRspNative(byte numAttr, int[] attrIds, String[]
            textArray, byte[] address);
    private native boolean registerNotificationRspPlayStatusNative(int type, int
            playStatus, byte[] address);
    private native boolean registerNotificationRspTrackChangeNative(int type, byte[]
            track, byte[] address);
    private native boolean registerNotificationRspPlayPosNative(int type, int
            playPos, byte[] address);
    private native boolean setVolumeNative(int volume, byte[] address);
    private native boolean registerNotificationRspAddressedPlayerChangedNative(
           int type, int playerId, byte[] address);
    private native boolean registerNotificationRspAvailablePlayersChangedNative(
            int type, byte[] address);
    private native boolean registerNotificationRspNowPlayingContentChangedNative(
        int type, byte[] address);
    private native boolean setAdressedPlayerRspNative(byte statusCode, byte[] address);
    private native boolean getMediaPlayerListRspNative(byte statusCode, int uidCounter,
                                    int itemCount, byte[] folderItems, int[]
                                    folderItemLengths, byte[] address);
    private native boolean getFolderItemsRspNative(byte statusCode, long numItems,
        int[] itemType, long[] uid, int[] type, byte[] playable, String[] displayName,
        byte[] numAtt, String[] attValues, int[] attIds, byte[] address);
    private native boolean getListPlayerappAttrRspNative(byte attr,
            byte[] attrIds, byte[] address);
    private native boolean getPlayerAppValueRspNative(byte numberattr,
            byte[]values, byte[] address );
    private native boolean SendCurrentPlayerValueRspNative(byte numberattr,
            byte[]attr, byte[] address );
    private native boolean SendSetPlayerAppRspNative(int attr_status, byte[] address);
    private native boolean sendSettingsTextRspNative(int num_attr, byte[] attr,
        int length, String[]text, byte[] address);
    private native boolean sendValueTextRspNative(int num_attr, byte[] attr,
        int length, String[]text, byte[] address);
    private native boolean registerNotificationPlayerAppRspNative(int type,
        byte numberattr, byte[]attr, byte[] address);
    private native boolean setBrowsedPlayerRspNative(byte statusCode, int uidCounter,
            int itemCount, int folderDepth, int charId, String[] folderItems,
            byte[] address);
    private native boolean changePathRspNative(int status, long itemCount, byte[] address);
    private native boolean playItemRspNative(int status, byte[] address);
    private native boolean getItemAttrRspNative(byte numAttr, int[] attrIds,
        String[] textArray, byte[] address);
    private native boolean isDeviceActiveInHandOffNative(byte[] address);

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
            playerEntry[position] = (byte)mPlayState; position++;
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
