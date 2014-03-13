/*
 * Copyright (c) 2015, The Linux Foundation. All rights reserved.
 * Not a Contribution
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
import android.bluetooth.BluetoothAvrcpInfo;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothAvrcpController;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ContentValues;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.media.AudioManager;
import android.net.Uri;
import android.database.Cursor;

import com.android.bluetooth.avrcp.Avrcp.Metadata;
import com.android.bluetooth.a2dp.A2dpSinkService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import android.util.Log;
import java.nio.charset.Charset;
import java.nio.ByteBuffer;
/**
 * Provides Bluetooth AVRCP Controller profile, as a service in the Bluetooth application.
 * @hide
 */
public class AvrcpControllerService extends ProfileService {
    private static final boolean DBG = true;
    private static final String TAG = "AvrcpControllerService";

    //private Context mContext;
/*
 *  Messages handled by mHandler
 */
    private static final int MESSAGE_SEND_PASS_THROUGH_CMD = 1;
    private static final int MESSAGE_GET_SUPPORTED_COMPANY_ID = 2;
    private static final int MESSAGE_GET_SUPPORTED_EVENTS = 3;
    private static final int MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_ATTRIB = 4;
    private static final int MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_VALUES = 5;
    private static final int MESSAGE_GET_CURRENT_PLAYER_APPLICATION_SETTINGS = 6;
    private static final int MESSAGE_SET_CURRENT_PLAYER_APPLICATION_SETTINGS = 7;
    private static final int MESSAGE_GET_ELEMENT_ATTRIBUTE = 8;
    private static final int MESSAGE_GET_PLAY_STATUS = 9;
    private static final int MESSAGE_DEINIT_AVRCP_DATABASE = 10;
    private static final int MESSAGE_REGISTER_NOTIFICATION = 11;

    private static final int MESSAGE_CMD_TIMEOUT = 100;
    /* Timeout Defined as per Spec */
    private static final int TIMEOUT_MTP = 1000;
    private static final int TIMEOUT_MTC = 200;
    private static final int TIMEOUT_RCP = 100;
    /* we add 1000ms extra */
    private static final int MSG_TIMEOUT_MTP = 1000 + TIMEOUT_MTP;
    private static final int MSG_TIMEOUT_MTC = 1000 + TIMEOUT_MTC;
    private static final int MSG_TIMEOUT_RCP = 1000 + TIMEOUT_RCP;
    private static final int GET_PLAY_STATUS_INTERVAL = 5000; // every 5sec


    private static final int MESSAGE_PROCESS_SUPPORTED_COMPANY_ID = 1002;
    private static final int MESSAGE_PROCESS_SUPPORTED_EVENTS = 1003;
    private static final int MESSAGE_PROCESS_PLAYER_APPLICATION_SETTINGS_ATTRIB = 1004;
    private static final int MESSAGE_PROCESS_PLAYER_APPLICATION_SETTINGS_VALUES = 1005;
    private static final int MESSAGE_PROCESS_CURRENT_PLAYER_APPLICATION_SETTINGS = 1006;
    private static final int MESSAGE_PROCESS_ELEMENT_ATTRIBUTE = 1008;
    private static final int MESSAGE_PROCESS_PLAY_STATUS = 1009;
    private static final int MESSAGE_REGISTER_PLAYBACK_STATUS_CHANGED = 1010;
    private static final int MESSAGE_REGISTER_TRACK_CHANGED = 1011;
    private static final int MESSAGE_REGISTER_PLAYBACK_POS_CHANGED = 1012;
    private static final int MESSAGE_REGISTER_PLAYER_APPLICATION_SETTINGS_CHANGED = 1013;
    private static final int MESSAGE_REGISTER_PLAYER_APPLICATION_VOLUME_CHANGED = 1014;
    private static final int MESSAGE_PROCESS_NOTIFICATION_RESPONSE = 1015;
    private static final int MESSAGE_PROCESS_SET_ABS_VOL_CMD = 1016;
    private static final int MESSAGE_PROCESS_REGISTER_ABS_VOL_REQUEST = 1017;

    private static final int MESSAGE_PROCESS_RC_FEATURES = 1100;
    private static final int MESSAGE_PROCESS_CONNECTION_CHANGE = 1200;
    private static final int ABORT_FETCH_ELEMENT_ATTRIBUTE = 4000 + MESSAGE_GET_ELEMENT_ATTRIBUTE;


/*
 *  Capability IDs for GetCapabilities
 */
    private static final int COMPANY_ID = 2;
    private static final int EVENTS_SUPPORTED = 3;

    private static final int NOTIFICATION_RSP_TYPE_INTERIM = 0x0f;
    private static final int NOTIFICATION_RSP_TYPE_CHANGED = 0x0d;

    private static final int PLAYBACK_POS_INTERVAL = 1; // time in seconds
/*
 * Constants for Events Supported
 */
    private static final byte EVENT_NOTIFICAION_ID_NONE = 0x00;
    private static final byte EVENT_PLAYBACK_STATUS_CHANGED = 0x01;
    private static final byte EVENT_TRACK_CHANGED = 0x02;
    private static final byte EVENT_PLAYBACK_POS_CHANGED = 0x05;
    private static final byte EVENT_PLAYER_APPLICATION_SETTINGS_CHANGED = 0x08;
    private static final byte EVENT_VOLUME_CHANGED = 0x0d;
/*
 * Timeout values for Events Supported
 */
    private static final int MESSAGE_TIMEOUT_PLAYBACK_STATUS_CHANGED = 2001;
    private static final int MESSAGE_TIMEOUT_TRACK_CHANGED = 2002;
    private static final int MESSAGE_TIMEOUT_PLAYBACK_POS_CHNAGED = 2005;
    private static final int MESSAGE_TIMEOUT_APPL_SETTINGS_CHANGED = 2008;
    private static final int MESSAGE_TIMEOUT_VOLUME_CHANGED = 2013;


    private static final byte ATTRIB_EQUALIZER_STATUS = 0x01;
    private static final byte ATTRIB_REPEAT_STATUS = 0x02;
    private static final byte ATTRIB_SHUFFLE_STATUS = 0x03;
    private static final byte ATTRIB_SCAN_STATUS = 0x04;

/*
 *  EQUALIZER State Values
 */
    private static final byte EQUALIZER_STATUS_OFF = 0x01;
    private static final byte EQUALIZER_STATUS_ON = 0x02;

/*
 *  REPEAT State Values
 */
    private static final byte REPEAT_STATUS_OFF = 0x01;
    private static final byte REPEAT_STATUS_SINGLE_TRACK_REPEAT = 0x02;
    private static final byte REPEAT_STATUS_ALL_TRACK_REPEAT = 0x03;
    private static final byte REPEAT_STATUS_GROUP_REPEAT = 0x04;
/*
 *  SHUFFLE State Values
 */
    private static final byte SHUFFLE_STATUS_OFF = 0x01;
    private static final byte SHUFFLE_STATUS_ALL_TRACK_SHUFFLE = 0x02;
    private static final byte SHUFFLE_STATUS_GROUP_SHUFFLE = 0x03;

/*
 *  Scan State Values
 */
    private static final byte SCAN_STATUS_OFF = 0x01;
    private static final byte SCAN_STATUS_ALL_TRACK_SCAN = 0x02;
    private static final byte SCAN_STATUS_GROUP_SCAN = 0x03;

/*
 *  Play State Values
 */
    private static final byte PLAY_STATUS_STOPPED = 0x00;
    private static final byte PLAY_STATUS_PLAYING = 0x01;
    private static final byte PLAY_STATUS_PAUSED = 0x02;
    private static final byte PLAY_STATUS_FWD_SEEK = 0x03;
    private static final byte PLAY_STATUS_REV_SEEK = 0x04;
/*
 *  values possible for notify_state
 */
    private static final byte NOTIFY_NOT_NOTIFIED = 0x01;
    private static final byte NOTIFY_INTERIM_EXPECTED = 0x02;
    private static final byte NOTIFY_CHANGED_EXPECTED = 0x03;
    /*
     * For Absolute volume we are in a TG role.
     * Where we will receive notification and we have
     * to send an interim/Chnaged response. So keeping
     * those states seperate.
     * Also we shld not send Chnaged response if change
     * in volume  is because of setabsvol command send from
     * remote.
     */
    private static final byte NOTIFY_NOT_REGISTERED = 0x01;
    private static final byte NOTIFY_RSP_INTERIM_SENT = 0x02;
    private static final byte NOTIFY_RSP_ABS_VOL_DEFERRED = 0x03;

/*
 *  Constants for GetElement Attribute
 */
    private static final int MEDIA_ATTRIBUTE_ALL = 0x00;
    private static final int MEDIA_ATTRIBUTE_TITLE = 0x01;
    private static final int MEDIA_ATTRIBUTE_ARTIST_NAME = 0x02;
    private static final int MEDIA_ATTRIBUTE_ALBUM_NAME = 0x03;
    private static final int MEDIA_ATTRIBUTE_TRACK_NUMBER = 0x04;
    private static final int MEDIA_ATTRIBUTE_TOTAL_TRACK_NUMBER = 0x05;
    private static final int MEDIA_ATTRIBUTE_GENRE = 0x06;
    private static final int MEDIA_ATTRIBUTE_PLAYING_TIME = 0x07;

    private static final int MEDIA_PLAYSTATUS_ALL = 0x08;
    private static final int MEDIA_PLAYSTATUS_SONG_TOTAL_LEN = 0x09;
    private static final int MEDIA_PLAYSTATUS_SONG_CUR_POS = 0x0a;
    private static final int MEDIA_PLAYSTATUS_SONG_PLAY_STATUS = 0x0b;
    private static final int MEDIA_PLAYER_APPLICAITON_SETTING = 0x0c;

    /*
     * Timeout values for GetElement Attribute
     */
    private static final int GET_ELEMENT_ATTR_TIMEOUT_BASE = 3000;
    private static final int MESSAGE_TIMEOUT_ATTRIBUTE_TITLE =
                             GET_ELEMENT_ATTR_TIMEOUT_BASE + MEDIA_ATTRIBUTE_TITLE;
    private static final int MESSAGE_TIMEOUT_ATTRIBUTE_ARTIST_NAME =
                       GET_ELEMENT_ATTR_TIMEOUT_BASE + MEDIA_ATTRIBUTE_ARTIST_NAME;
    private static final int MESSAGE_TIMEOUT_ATTRIBUTE_ALBUM_NAME =
                        GET_ELEMENT_ATTR_TIMEOUT_BASE + MEDIA_ATTRIBUTE_ALBUM_NAME;
    private static final int MESSAGE_TIMEOUT_ATTRIBUTE_TRACK_NUMBER =
                      GET_ELEMENT_ATTR_TIMEOUT_BASE + MEDIA_ATTRIBUTE_TRACK_NUMBER;
    private static final int MESSAGE_TIMEOUT_ATTRIBUTE_TOTAL_TRACK_NUMBER =
                 GET_ELEMENT_ATTR_TIMEOUT_BASE + MEDIA_ATTRIBUTE_TOTAL_TRACK_NUMBER;
    private static final int MESSAGE_TIMEOUT_ATTRIBUTE_GENRE =
                              GET_ELEMENT_ATTR_TIMEOUT_BASE + MEDIA_ATTRIBUTE_GENRE;
    private static final int MESSAGE_TIMEOUT_ATTRIBUTE_PLAYING_TIME =
                       GET_ELEMENT_ATTR_TIMEOUT_BASE + MEDIA_ATTRIBUTE_PLAYING_TIME;

    private static final int ATTRIBUTE_FETCH_CONTINUE = 0;
    private static final int ATTRIBUTE_FETCH_FRESH = 1; // discard all if we are already fetching
    private static final int ATTRIBUTE_FETCH_SKIP = 3;// discard only current one

    /* AVRCP Rsp Status */
    private static final int  AVRC_RSP_NOT_IMPL =  8;
    private static final int  AVRC_RSP_ACCEPT   =  9;
    private static final int  AVRC_RSP_REJ      =  10;
    private static final int  AVRC_RSP_IN_TRANS =  11;
    private static final int  AVRC_RSP_IMPL_STBL = 12;
    private static final int  AVRC_RSP_CHANGED  =  13;
    private static final int  AVRC_RSP_INTERIM  =  15;

    /*
     * AVRCP Key event
     */
    public static final int AVRC_ID_PLAY = 0x44;
    public static final int AVRC_ID_PAUSE = 0x46;
    public static final int AVRC_ID_STOP = 0x45;
    public static final int AVRC_ID_FF = 0x49;
    public static final int AVRC_ID_REWIND = 0x48;
    public static final int AVRC_ID_FORWARD = 0x4B;
    public static final int AVRC_ID_BACKWARD = 0x4C;
    public static final int AVRC_ID_VOL_UP = 0x41;
    public static final int AVRC_ID_VOL_DOWN = 0x42;

    public static final int ABS_VOL_BASE = 127;
/*
 *  Constant Variables Defined
 */
    private final int BTSIG_COMPANY_ID = 0x001958;

    public static final int BTRC_FEAT_METADATA = 0x01;
    public static final int BTRC_FEAT_ABSOLUTE_VOLUME = 0x02;
    public static final int BTRC_FEAT_BROWSE = 0x04;

    private class PlayerSettings
    {
        public byte attr_Id;
        public byte attr_val;
        public byte [] supported_values;
    };
    private class NotifyEvents
    {
        public byte notify_event_id; // these values will be one of Supported Events
        public byte notify_state; // Current State of Notification
    };
    private class Metadata {
        private String artist;
        private String trackTitle;
        private String albumTitle;
        private String genre;
        private long trackNum;
        private long totalTrackNum;
        private byte playStatus;
        private long playTime;
        private long totalTrackLen;
        private int attributesFetchedId;

        public Metadata() {
            resetMetaData();
        }
       public void resetMetaData() {
           artist = BluetoothAvrcpInfo.ARTIST_NAME_INVALID;
           trackTitle = BluetoothAvrcpInfo.TITLE_INVALID;
           albumTitle = BluetoothAvrcpInfo.ALBUM_NAME_INVALID;
           genre = BluetoothAvrcpInfo.GENRE_INVALID;
           trackNum = BluetoothAvrcpInfo.TRACK_NUM_INVALID;
           totalTrackNum = BluetoothAvrcpInfo.TOTAL_TRACKS_INVALID;
           playStatus = PLAY_STATUS_STOPPED;
           playTime = BluetoothAvrcpInfo.PLAYING_TIME_INVALID;
           totalTrackLen = BluetoothAvrcpInfo.TOTAL_TRACK_TIME_INVALID;
           attributesFetchedId = -1; // id of the attribute being fetched, initialized by -1
       }
        public String toString() {
            return "Metadata [artist=" + artist + " trackTitle= " + trackTitle + " albumTitle= " +
            albumTitle + " genre= " +genre+" trackNum= "+Long.toString(trackNum) + " cur_time: "+
            Long.toString(playTime)  + " total_time = "+ Long.toString(totalTrackLen) + "]";
        }
    };

    private final class RemoteAvrcpData {
        private ArrayList <Integer> mCompanyIDSupported; // company ID
        private ArrayList <Byte> mEventsSupported;
        private ArrayList <PlayerSettings> mSupportedApplicationSettingsAttribute;
        private ArrayList <NotifyEvents> mNotifyEvent;
        private Metadata mMetadata;
        int mRemoteFeatures;
        int absVolNotificationState;
        int playerSettingAttribIdFetch;
    };

    RemoteAvrcpData mRemoteData;
    int[] requestedElementAttribs;

    private AvrcpMessageHandler mHandler;
    private static AvrcpControllerService sAvrcpControllerService;
    private static AudioManager mAudioManager;
    private static boolean mDbInitialized = false;

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
        Log.d(TAG," initBinder Called ");
        return new BluetoothAvrcpControllerBinder(this);
    }

    protected boolean start() {
        HandlerThread thread = new HandlerThread("BluetoothAvrcpHandler");
        thread.start();
        Looper looper = thread.getLooper();
        mHandler = new AvrcpMessageHandler(looper);
        mRemoteData = null;
        setAvrcpControllerService(this);
        mAudioManager = (AudioManager)sAvrcpControllerService.
                                  getSystemService(Context.AUDIO_SERVICE);
        IntentFilter filter = new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION);
        registerReceiver(mBroadcastReceiver, filter);
        return true;
    }

    protected boolean stop() {
        unregisterReceiver(mBroadcastReceiver);
         try {
             deinitDatabase();
             if (mRemoteData != null) {
                 mRemoteData.mCompanyIDSupported.clear();
                 mRemoteData.mEventsSupported.clear();
                 mRemoteData.mMetadata.resetMetaData();
                 mRemoteData.mNotifyEvent.clear();
                 mRemoteData.mSupportedApplicationSettingsAttribute.clear();
                 mRemoteData.absVolNotificationState = NOTIFY_NOT_REGISTERED;
                 mRemoteData.mRemoteFeatures = 0;
                 Log.d(TAG," RC_features, STOP " + mRemoteData.mRemoteFeatures);
                 mRemoteData.playerSettingAttribIdFetch = 0;
                 mRemoteData = null;
             }
         } catch (Exception e) {
             Log.e(TAG, "Cleanup failed", e);
         }
        return true;
    }
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                if (streamType == AudioManager.STREAM_MUSIC) {
                    int streamValue = intent
                            .getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, -1);
                    int streamPrevValue = intent.getIntExtra(
                            AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, -1);
                    if (streamValue != -1 && streamValue != streamPrevValue) {
                        if ((mRemoteData == null)
                            ||((mRemoteData.mRemoteFeatures & BTRC_FEAT_ABSOLUTE_VOLUME) == 0)
                            ||(mConnectedDevices.isEmpty()))
                            return;
                        if(mRemoteData.absVolNotificationState == NOTIFY_RSP_INTERIM_SENT) {
                            int maxVol = mAudioManager.
                                                  getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                            int currIndex = mAudioManager.
                                                  getStreamVolume(AudioManager.STREAM_MUSIC);
                            int percentageVol = ((currIndex*ABS_VOL_BASE)/maxVol);
                            byte rspType = NOTIFICATION_RSP_TYPE_CHANGED;
                            Log.d(TAG," Abs Vol Notify Rsp Changed val = "+ percentageVol);
                            mRemoteData.absVolNotificationState = NOTIFY_NOT_REGISTERED;
                            sendRegisterAbsVolRspNative(rspType,percentageVol);
                        }
                        else if (mRemoteData.absVolNotificationState == NOTIFY_RSP_ABS_VOL_DEFERRED) {
                            Log.d(TAG," Don't Complete Notification Rsp. ");
                            mRemoteData.absVolNotificationState = NOTIFY_RSP_INTERIM_SENT;
                        }
                    }
                }
            }
        }
    };
    protected boolean cleanup() {
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            Looper looper = mHandler.getLooper();
            if (looper != null) looper.quit();
            if (looper != null) {
                looper.quit();
            }
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
        if (!(mConnectedDevices.contains(device))) {
            Log.d(TAG," Device does not match");
            return;
        }
        if ((mRemoteData == null)||(mRemoteData.mMetadata == null)) {
            Log.d(TAG," Device connected but PlayState not present ");
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            Message msg = mHandler.obtainMessage(MESSAGE_SEND_PASS_THROUGH_CMD,
                    keyCode, keyState, device);
            mHandler.sendMessage(msg);
            return;
        }
        boolean sendCommand = false;
        switch(keyCode) {
            case AVRC_ID_PLAY:
                sendCommand  = (mRemoteData.mMetadata.playStatus == PLAY_STATUS_STOPPED)||
                               (mRemoteData.mMetadata.playStatus == PLAY_STATUS_PAUSED);
                break;
            case AVRC_ID_PAUSE:
                sendCommand  = (mRemoteData.mMetadata.playStatus == PLAY_STATUS_PLAYING)||
                               (mRemoteData.mMetadata.playStatus == PLAY_STATUS_FWD_SEEK)||
                               (mRemoteData.mMetadata.playStatus == PLAY_STATUS_STOPPED)||
                               (mRemoteData.mMetadata.playStatus == PLAY_STATUS_REV_SEEK);
                break;
            case AVRC_ID_STOP:
                sendCommand  = (mRemoteData.mMetadata.playStatus == PLAY_STATUS_PLAYING)||
                               (mRemoteData.mMetadata.playStatus == PLAY_STATUS_FWD_SEEK)||
                               (mRemoteData.mMetadata.playStatus == PLAY_STATUS_REV_SEEK)||
                               (mRemoteData.mMetadata.playStatus == PLAY_STATUS_STOPPED)||
                               (mRemoteData.mMetadata.playStatus == PLAY_STATUS_PAUSED);
                break;
            case AVRC_ID_VOL_DOWN:
            case AVRC_ID_VOL_UP:
            case AVRC_ID_BACKWARD:
            case AVRC_ID_FORWARD:
            case AVRC_ID_FF:
            case AVRC_ID_REWIND:
                sendCommand = true; // we can send this command in all states
                break;
        }
        if (sendCommand) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            Message msg = mHandler.obtainMessage(MESSAGE_SEND_PASS_THROUGH_CMD,
                keyCode, keyState, device);
            mHandler.sendMessage(msg);
        }
        else {
            Log.e(TAG," Not in right state, don't send Pass Thru cmd ");
        }
    }
    public void getMetaData(int[] attributeIds) {
        Log.d(TAG, "num getMetaData = "+ attributeIds.length);
        if (mRemoteData == null) {
            return;
        }
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        /*
         * Check if GetElementAttribute is in progress
         * Check if it already does not contain all Attributes
         * If it is, append any missing attribute
         */
        if ((mRemoteData.mMetadata.attributesFetchedId != -1) &&
           (requestedElementAttribs != null)&&
           (requestedElementAttribs.length < 7)) {

            int currAttributeId =
                      requestedElementAttribs[mRemoteData.mMetadata.attributesFetchedId];
            if (mHandler.hasMessages(GET_ELEMENT_ATTR_TIMEOUT_BASE + currAttributeId)) {
                mHandler.removeMessages(GET_ELEMENT_ATTR_TIMEOUT_BASE + currAttributeId);
                Log.d(TAG," Timeout CMD dequeued ID " + currAttributeId);
            }
            mHandler.sendEmptyMessage(ABORT_FETCH_ELEMENT_ATTRIBUTE);
            requestedElementAttribs = Arrays.copyOf(attributeIds, attributeIds.length);
            return;
        }
        else if ((attributeIds.length >= 0)&&(attributeIds[0] != MEDIA_ATTRIBUTE_ALL)) {
            /* Subset sent by App, use them */
            requestedElementAttribs = Arrays.copyOf(attributeIds, attributeIds.length);
        }
        else {
            /* Use SuperSet */
            requestedElementAttribs = new int [7];
            for (int xx = 0; xx < 7; xx++)
               requestedElementAttribs[xx] = xx+1;
        }
        Arrays.sort(requestedElementAttribs);
        boolean mMetaDataPresent = true;
        for (int attributeId: requestedElementAttribs) {
            if (!isMetaDataPresent(attributeId)) {
                mMetaDataPresent = false;
                break;
            }
        }
        Log.d(TAG," MetaDataPresent " + mMetaDataPresent);
        if(mMetaDataPresent)
            triggerNotification();
        else
            mHandler.sendEmptyMessage(MESSAGE_GET_ELEMENT_ATTRIBUTE);
    }
    public void getPlayStatus(int[] playStatusIds) {
        if (DBG) Log.d(TAG, "num getPlayStatus ID = "+ playStatusIds.length);
        if (mRemoteData == null) {
            return;
        }
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int[] getApRequestedPlayStatusAttrib;
        if ((playStatusIds.length >= 0)&&(playStatusIds[0] != MEDIA_PLAYSTATUS_ALL)) {
            /* Subset sent by App, use them */
            getApRequestedPlayStatusAttrib = Arrays.copyOf(playStatusIds, playStatusIds.length);
        }
        else {
            /* Use SuperSet */
            getApRequestedPlayStatusAttrib = new int [3];
            for (int xx = 0; xx < 3; xx++)
                getApRequestedPlayStatusAttrib[xx] = 9 + xx;
        }
        Arrays.sort(getApRequestedPlayStatusAttrib);
        boolean mMetaDataPresent = true;
        for (int attributeId: getApRequestedPlayStatusAttrib) {
            if (!isMetaDataPresent(attributeId)) {
                mMetaDataPresent = false;
                break;
            }
        }
        if(mMetaDataPresent)
            triggerNotification();
        else {
            Log.d(TAG," Metadata not present");
            mHandler.sendEmptyMessage(MESSAGE_GET_PLAY_STATUS);
        }
    }
    public void getPlayerApplicationSetting() {
        if (DBG) Log.d(TAG, "getPlayerApplicationSetting ");
        if (mRemoteData == null) {
            return;
        }
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if(isMetaDataPresent(MEDIA_PLAYER_APPLICAITON_SETTING)) {
            triggerNotification();
        }
        else {
            Log.d(TAG," Metadata not present, fetch it");
            mHandler.sendEmptyMessage(MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_ATTRIB);
        }
    }
    public void setPlayerApplicationSetting(int attributeId, int attributeVal) {
        if (mRemoteData == null) {
            return;
        }
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        Message msg = mHandler.obtainMessage(MESSAGE_SET_CURRENT_PLAYER_APPLICATION_SETTINGS,
                                                                     attributeId, attributeVal);
        mHandler.sendMessage(msg);
    }
    public BluetoothAvrcpInfo getSupportedPlayerAppSetting(BluetoothDevice device) {
        if((mRemoteData == null)||(mRemoteData.mSupportedApplicationSettingsAttribute.isEmpty())
                                ||(!mConnectedDevices.contains(device)))
            return null;
        byte[] attribIds = new byte[mRemoteData.mSupportedApplicationSettingsAttribute.size()];
        byte[] numAttribVals = new byte[mRemoteData.mSupportedApplicationSettingsAttribute.size()];
        ArrayList<Byte> supportedVals = new ArrayList<Byte>();
        int index = 0;
        for (PlayerSettings plSetting: mRemoteData.mSupportedApplicationSettingsAttribute) {
            attribIds[index] = plSetting.attr_Id;
            numAttribVals[index] = Integer.valueOf(plSetting.supported_values.length).byteValue();
            for (int xx = 0; xx < numAttribVals[index]; xx++)
                supportedVals.add(plSetting.supported_values[xx]);
            index++;
        }
        byte[] supportedPlSettingsVals = new byte[supportedVals.size()];
        for (int zz = 0; zz < supportedVals.size(); zz++)
            supportedPlSettingsVals[zz] = supportedVals.get(zz);
        if ((attribIds == null)||(numAttribVals == null)||(supportedPlSettingsVals == null)||
           (attribIds.length == 0)||(numAttribVals.length == 0))
            return null;
        BluetoothAvrcpInfo btAvrcpMetaData = new BluetoothAvrcpInfo(attribIds,
                                        numAttribVals, supportedPlSettingsVals);
        return btAvrcpMetaData;
    }
    public int getSupportedFeatures(BluetoothDevice device) {
        if (!mConnectedDevices.contains(device)||(mRemoteData == null)) {
            Log.e(TAG," req Device " + device + " Internal List " + mConnectedDevices.get(0));
            Log.e(TAG," remoteData " + mRemoteData);
            if (mRemoteData != null)
            Log.e(TAG," getSupportedFeatures returning  from here "+ mRemoteData.mRemoteFeatures);
            return 0;
        }
        Log.d(TAG," getSupportedFeatures returning " + mRemoteData.mRemoteFeatures);
        return mRemoteData.mRemoteFeatures;
    }
    private void triggerNotification() {
        Uri avrcpDataUri = BluetoothAvrcpInfo.CONTENT_URI;
        sAvrcpControllerService.getContentResolver().notifyChange(avrcpDataUri, null);
    }
    private boolean isMetaDataPresent(int attributeId) {
        Uri avrcpDataUri = BluetoothAvrcpInfo.CONTENT_URI;
        Cursor cursor = sAvrcpControllerService.getContentResolver().query(avrcpDataUri,
                                               null, null, null,BluetoothAvrcpInfo._ID);
        if ((cursor == null) || (!cursor.moveToFirst())) {
            Log.d(TAG," isMetaDataPresent cursor not valid, returing");
            return false;
        }
        int index = 0;
        boolean metaDataPresent = false;
        switch(attributeId)
        {
        case MEDIA_ATTRIBUTE_ALBUM_NAME:
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.ALBUM_NAME);
            if(cursor.getString(index) !=
                BluetoothAvrcpInfo.ALBUM_NAME_INVALID)
                metaDataPresent = true;
            break;
        case MEDIA_ATTRIBUTE_ARTIST_NAME:
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.ARTIST_NAME);
            if(cursor.getString(index) !=
                BluetoothAvrcpInfo.ARTIST_NAME_INVALID)
                metaDataPresent = true;
            break;
        case MEDIA_ATTRIBUTE_GENRE:
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.GENRE);
            if(cursor.getString(index) !=
                BluetoothAvrcpInfo.GENRE_INVALID)
                metaDataPresent = true;
            break;
        case MEDIA_ATTRIBUTE_TITLE:
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.TITLE);
            if(cursor.getString(index) !=
                BluetoothAvrcpInfo.TITLE_INVALID)
                metaDataPresent = true;
            break;
        case MEDIA_PLAYSTATUS_SONG_CUR_POS:
        case MEDIA_ATTRIBUTE_PLAYING_TIME:
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.PLAYING_TIME);
            if(cursor.getInt(index) !=
                    BluetoothAvrcpInfo.PLAYING_TIME_INVALID)
                    metaDataPresent = true;
            break;
        case MEDIA_ATTRIBUTE_TOTAL_TRACK_NUMBER:
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.TOTAL_TRACKS);
            if(cursor.getInt(index) !=
                    BluetoothAvrcpInfo.TOTAL_TRACKS_INVALID)
                    metaDataPresent = true;
            break;
        case MEDIA_ATTRIBUTE_TRACK_NUMBER:
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.TRACK_NUM);
            if(cursor.getInt(index) !=
                    BluetoothAvrcpInfo.TRACK_NUM_INVALID)
                    metaDataPresent = true;
            break;
        case MEDIA_ATTRIBUTE_ALL: // check all attribute
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.TRACK_NUM);
            if(cursor.getInt(index) ==
                    BluetoothAvrcpInfo.TRACK_NUM_INVALID)
                break;
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.TOTAL_TRACKS);
            if(cursor.getInt(index) ==
                    BluetoothAvrcpInfo.TOTAL_TRACKS_INVALID)
                break;
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.PLAYING_TIME);
            if(cursor.getInt(index) ==
                    BluetoothAvrcpInfo.PLAYING_TIME_INVALID)
                break;
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.TITLE);
            if(cursor.getString(index) ==
                BluetoothAvrcpInfo.TITLE_INVALID)
                break;
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.GENRE);
            if(cursor.getString(index) ==
                BluetoothAvrcpInfo.GENRE_INVALID)
                break;
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.ARTIST_NAME);
            if(cursor.getString(index) ==
                BluetoothAvrcpInfo.ARTIST_NAME_INVALID)
                break;
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.ALBUM_NAME);
            if(cursor.getString(index) ==
                BluetoothAvrcpInfo.ALBUM_NAME_INVALID)
                break;

            metaDataPresent = true;
            break;
        case MEDIA_PLAYSTATUS_SONG_PLAY_STATUS:
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.PLAY_STATUS);
            if(cursor.getString(index) !=
                    BluetoothAvrcpInfo.PLAY_STATUS_INVALID)
                    metaDataPresent = true;
            break;
        case MEDIA_PLAYSTATUS_SONG_TOTAL_LEN:
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.TOTAL_TRACK_TIME);
            if(cursor.getInt(index) !=
                    BluetoothAvrcpInfo.TOTAL_TRACK_TIME_INVALID)
                    metaDataPresent = true;
            break;
        case MEDIA_PLAYSTATUS_ALL:
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.PLAY_STATUS);
            if(cursor.getString(index) ==
                    BluetoothAvrcpInfo.PLAY_STATUS_INVALID)
                break;
            index = cursor.getColumnIndex(BluetoothAvrcpInfo.TOTAL_TRACK_TIME);
            if(cursor.getInt(index) ==
                    BluetoothAvrcpInfo.TOTAL_TRACK_TIME_INVALID)
                break;

            metaDataPresent = true;
            break;
        case MEDIA_PLAYER_APPLICAITON_SETTING:
            boolean plSettingSupported = true;
            for (PlayerSettings plSetting: mRemoteData.mSupportedApplicationSettingsAttribute) {
                switch(plSetting.attr_Id)
                {
                case ATTRIB_REPEAT_STATUS:
                    index = cursor.getColumnIndex(BluetoothAvrcpInfo.REPEAT_STATUS);
                    if(cursor.getString(index) ==
                        BluetoothAvrcpInfo.REPEAT_STATUS_INVALID)
                        plSettingSupported = false;
                    break;
                case ATTRIB_EQUALIZER_STATUS:
                    index = cursor.getColumnIndex(BluetoothAvrcpInfo.EQUALIZER_STATUS);
                    if(cursor.getString(index) ==
                        BluetoothAvrcpInfo.EQUALIZER_STATUS_INVALID)
                        plSettingSupported = false;
                    break;
                case ATTRIB_SCAN_STATUS:
                    index = cursor.getColumnIndex(BluetoothAvrcpInfo.SCAN_STATUS);
                    if(cursor.getString(index) ==
                        BluetoothAvrcpInfo.SCAN_STATUS_INVALID)
                        plSettingSupported = false;
                    break;
                case ATTRIB_SHUFFLE_STATUS:
                    index = cursor.getColumnIndex(BluetoothAvrcpInfo.SHUFFLE_STATUS);
                    if(cursor.getString(index) ==
                        BluetoothAvrcpInfo.SHUFFLE_STATUS_INVALID)
                        plSettingSupported = false;
                    break;
                }
                if(!plSettingSupported)
                    break;
            }
            metaDataPresent = plSettingSupported;
            break;
        }
        cursor.close();
        Log.d(TAG," returning " + metaDataPresent + "for attrib " + attributeId);
        return metaDataPresent;
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

        public void getMetaData(int[] attributeIds) {
            Log.v(TAG,"Binder Call: num getMetaData ID = "+ attributeIds.length);
            AvrcpControllerService service = getService();
            if (service == null) return;
            service.getMetaData(attributeIds);
        }
        public void getPlayStatus(int[] playStatusIds) {
            Log.v(TAG,"Binder Call: num getPlayStatus ID = "+ playStatusIds.length);
            AvrcpControllerService service = getService();
            if (service == null) return;
            service.getPlayStatus(playStatusIds);
        }
        public void getPlayerApplicationSetting() {
            Log.v(TAG,"Binder Call: getPlayerApplicationSetting ");
            AvrcpControllerService service = getService();
            if (service == null) return;
            service.getPlayerApplicationSetting();
        }
        public void setPlayerApplicationSetting(int attributeId, int attributeVal) {
            Log.v(TAG,"Binder Call: setPlayerApplicationSetting ID = "
                              + attributeId +" attributeVal ="+ attributeVal);
            AvrcpControllerService service = getService();
            if (service == null) return;
            service.setPlayerApplicationSetting(attributeId, attributeVal);
        }
        public BluetoothAvrcpInfo getSupportedPlayerAppSetting(BluetoothDevice device) {
            Log.v(TAG,"Binder Call: getSupportedPlayerAppSetting Dev = " + device);
            AvrcpControllerService service = getService();
            if (service == null) return null;
            return service.getSupportedPlayerAppSetting(device);
        }
        public int getSupportedFeatures(BluetoothDevice device) {
            Log.v(TAG,"Binder Call: getSupportedFeatures Dev = " + device);
            AvrcpControllerService service = getService();
            if (service == null) return 0;
            return service.getSupportedFeatures(device);
        }
    };

    private void getSupportedCapabilities(int capability_id)
    {
       Message msg = mHandler.obtainMessage(MESSAGE_CMD_TIMEOUT,0,0,capability_id);
       mHandler.sendMessageDelayed(msg, MSG_TIMEOUT_MTP);
       getCapabilitiesNative(capability_id);
    }
    private void getPlayerApplicationSettingsAttrib()
    {
        Message msg = mHandler.obtainMessage(MESSAGE_CMD_TIMEOUT,
                                 0,0,MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_ATTRIB);
        mHandler.sendMessageDelayed(msg, MSG_TIMEOUT_MTP);
        listPlayerApplicationSettingAttributeNative();
    }
    private void getCurrentPlayerApplicationSettingsValues()
    {
        int count = 0;
        if ((mRemoteData == null)||
            (mRemoteData.mSupportedApplicationSettingsAttribute == null)||
            (mRemoteData.mSupportedApplicationSettingsAttribute.size() == 0)) {
            Log.w(TAG," PlayerAppSettings not supporterd, returning");
            return;
        }
        byte[] supported_attrib =
            new byte[mRemoteData.mSupportedApplicationSettingsAttribute.size()];
        byte numAttrib = Byte.valueOf((Integer.valueOf
                    (mRemoteData.mSupportedApplicationSettingsAttribute.size())).byteValue());
        for (PlayerSettings plSetting: mRemoteData.mSupportedApplicationSettingsAttribute)
        {
            supported_attrib[count++] = plSetting.attr_Id;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_CMD_TIMEOUT,
                          0,0,MESSAGE_GET_CURRENT_PLAYER_APPLICATION_SETTINGS);
        mHandler.sendMessageDelayed(msg, MSG_TIMEOUT_MTP);
        getPlayerApplicationSettingValuesNative(numAttrib,supported_attrib);
    }
    private void setCurrentPlayerApplicationSettingsValues(int attribId, int attribVal)
    {
        Message msg = mHandler.obtainMessage(MESSAGE_CMD_TIMEOUT,
                                   0,0,MESSAGE_SET_CURRENT_PLAYER_APPLICATION_SETTINGS);
        mHandler.sendMessageDelayed(msg, MSG_TIMEOUT_MTP);
        byte numAttrib = 1;
        byte[] attributeId = new byte[1];
        byte[] attributeVal = new byte[1];
        attributeId[0] = (byte)attribId;
        attributeVal[0] = (byte)attribVal;
        setPlayerApplicationSettingValuesNative(numAttrib, attributeId, attributeVal);
    }
    private void getFurtherElementAttribute(int operationId)
    {
        if ((requestedElementAttribs == null)||(requestedElementAttribs.length == 0))
        {
            Log.d(TAG," Applicaiton has not yet requested element attributes");
            return;
        }
        Log.d(TAG," getFurtherElementAttribute  op_Id = "
                 + operationId + " requestedIdLen = " + requestedElementAttribs.length);
        byte numAttrib = 1;
        if (operationId == ATTRIBUTE_FETCH_FRESH)
            mRemoteData.mMetadata.attributesFetchedId = 0;  // reset fetched_id
        else if ((operationId == ATTRIBUTE_FETCH_SKIP)||(operationId == ATTRIBUTE_FETCH_CONTINUE))
            mRemoteData.mMetadata.attributesFetchedId += 1;  // skip this one, fetch next
        if (mRemoteData.mMetadata.attributesFetchedId >= requestedElementAttribs.length)
        {
            /*
             * we reached to last attribute. Update database now
             */
            mRemoteData.mMetadata.attributesFetchedId = -1;
            updateElementAttribute();
            mHandler.sendEmptyMessage(MESSAGE_GET_PLAY_STATUS);
            return;
        }
        Message msg = mHandler.obtainMessage(GET_ELEMENT_ATTR_TIMEOUT_BASE
                + requestedElementAttribs[mRemoteData.mMetadata.attributesFetchedId]);
        mHandler.sendMessageDelayed(msg, MSG_TIMEOUT_MTP);
        Log.d(TAG," getElemAttrReq numAttr "+ numAttrib + " Id "
             + requestedElementAttribs[mRemoteData.mMetadata.attributesFetchedId]);
        getElementAttributeNative(numAttrib,
                  requestedElementAttribs[mRemoteData.mMetadata.attributesFetchedId]);
    }
    private void getFurtherPlayerSettingAttrib(int operationId)
    {
        Log.d(TAG," getFurtherPlayerSettingAttrib  Id = " + operationId);
        if (mRemoteData == null)
            return;
        if (operationId == ATTRIBUTE_FETCH_FRESH)
            mRemoteData.playerSettingAttribIdFetch = 0;
        else if(operationId == ATTRIBUTE_FETCH_SKIP)
            mRemoteData.playerSettingAttribIdFetch ++;
        int fetch_id = mRemoteData.playerSettingAttribIdFetch;
        if (fetch_id >= mRemoteData.mSupportedApplicationSettingsAttribute.size())
        {
            Log.d(TAG," All Attrib Fetched " + fetch_id);
            mHandler.sendEmptyMessage(MESSAGE_GET_CURRENT_PLAYER_APPLICATION_SETTINGS);
            return;
        }
        Log.d(TAG," fetching_id = " + fetch_id);
        Message msg = mHandler.obtainMessage(MESSAGE_CMD_TIMEOUT,
                             0,0,MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_VALUES);
        mHandler.sendMessageDelayed(msg, MSG_TIMEOUT_MTP);
        listPlayerApplicationSettingValueNative(mRemoteData.
                   mSupportedApplicationSettingsAttribute.get(fetch_id).attr_Id);
    }
    /*
     * Api to register notification.
     * exemptNotificaionId = notificaiton ID for which notification will not be sent.
     * in case of we have other notifications to be sent we will do nothing for
     * exemptedID. In case exemptedId is the only one left we will send notification
     * request for this one.
     */
    private void registerFurtherNotification(int exemptNotificaionId)
    {
        Log.d(TAG," ExemptedNotificationId " + exemptNotificaionId);
        /*
         * check through the list and send notification
         * request if state is not waiting for changed
         */
        boolean notificationSent = false;
        Message msg;
        for (NotifyEvents notifyEvent: mRemoteData.mNotifyEvent)
        {
            Log.d(TAG," ID = " + notifyEvent.notify_event_id + " state = "
                                                   + notifyEvent.notify_state);
            if (notifyEvent.notify_event_id == exemptNotificaionId)
                continue;
            if (notifyEvent.notify_state == NOTIFY_NOT_NOTIFIED)
            {
                notificationSent = true;
                notifyEvent.notify_state = NOTIFY_INTERIM_EXPECTED;
                Log.d(TAG," queing notificaiton request id  " +
                                         (2000+notifyEvent.notify_event_id));
                msg = mHandler.obtainMessage(2000 + notifyEvent.notify_event_id);
                mHandler.sendMessageDelayed(msg, MSG_TIMEOUT_MTP);
                if (notifyEvent.notify_event_id == EVENT_PLAYBACK_POS_CHANGED)
                    registerNotificationNative(notifyEvent.notify_event_id, PLAYBACK_POS_INTERVAL);
                else
                    registerNotificationNative(notifyEvent.notify_event_id, 0);
                break;
            }
        }
        if ((!notificationSent) && (exemptNotificaionId != EVENT_NOTIFICAION_ID_NONE))
        {
            /*
             * exempted value was valid and only this was not notified.
             */
            for (NotifyEvents notifyEvent: mRemoteData.mNotifyEvent)
            {
                if (notifyEvent.notify_event_id == exemptNotificaionId)
                {
                    notificationSent = true;
                    notifyEvent.notify_state = NOTIFY_INTERIM_EXPECTED;
                    msg = mHandler.obtainMessage(2000 + notifyEvent.notify_event_id);
                    mHandler.sendMessageDelayed(msg, MSG_TIMEOUT_MTP);
                    if (notifyEvent.notify_event_id == EVENT_PLAYBACK_POS_CHANGED)
                        registerNotificationNative(notifyEvent.notify_event_id,
                                           PLAYBACK_POS_INTERVAL);
                    else
                        registerNotificationNative(notifyEvent.notify_event_id, 0);
                    break;
                }
            }
        }
    }
    private void initializeDatabase()
    {
        if (mDbInitialized)
            return;
        ContentValues values = new ContentValues();
        values.put(BluetoothAvrcpInfo.TRACK_NUM, BluetoothAvrcpInfo.TRACK_NUM_INVALID);
        values.put(BluetoothAvrcpInfo.TITLE, BluetoothAvrcpInfo.TITLE_INVALID);
        values.put(BluetoothAvrcpInfo.ARTIST_NAME, BluetoothAvrcpInfo.ARTIST_NAME_INVALID);
        values.put(BluetoothAvrcpInfo.ALBUM_NAME, BluetoothAvrcpInfo.ALBUM_NAME_INVALID);
        values.put(BluetoothAvrcpInfo.TOTAL_TRACKS, BluetoothAvrcpInfo.TOTAL_TRACKS_INVALID);
        values.put(BluetoothAvrcpInfo.GENRE, BluetoothAvrcpInfo.GENRE_INVALID);
        values.put(BluetoothAvrcpInfo.PLAYING_TIME, BluetoothAvrcpInfo.PLAYING_TIME_INVALID);
        values.put(BluetoothAvrcpInfo.TOTAL_TRACK_TIME,
                                                BluetoothAvrcpInfo.TOTAL_TRACK_TIME_INVALID);
        values.put(BluetoothAvrcpInfo.PLAY_STATUS, BluetoothAvrcpInfo.PLAY_STATUS_INVALID);
        values.put(BluetoothAvrcpInfo.REPEAT_STATUS, BluetoothAvrcpInfo.REPEAT_STATUS_INVALID);
        values.put(BluetoothAvrcpInfo.SHUFFLE_STATUS, BluetoothAvrcpInfo.SHUFFLE_STATUS_INVALID);
        values.put(BluetoothAvrcpInfo.SCAN_STATUS, BluetoothAvrcpInfo.SCAN_STATUS_INVALID);
        values.put(BluetoothAvrcpInfo.EQUALIZER_STATUS,
                                              BluetoothAvrcpInfo.EQUALIZER_STATUS_INVALID);
        Cursor cursor = getContentResolver().query(BluetoothAvrcpInfo.CONTENT_URI, null, null, null,
                BluetoothAvrcpInfo._ID);
        if((cursor != null)&&(cursor.getCount() > 0)) {
            int rowsUpdated = sAvrcpControllerService.getContentResolver().
                                 update(BluetoothAvrcpInfo.CONTENT_URI, values, null, null);
            Log.d(TAG," initializeDataBase num_rows_updated " + rowsUpdated);
            mDbInitialized = true;
            cursor.close();
            return;
        }
        Uri contentUri = sAvrcpControllerService.getContentResolver().
                              insert(BluetoothAvrcpInfo.CONTENT_URI, values);
        Log.d(TAG," InitializeDatabase uri " + contentUri);
        mDbInitialized = true;
    }
    private void deinitDatabase()
    {
        mDbInitialized = false;
        int rows_deleted = sAvrcpControllerService.getContentResolver().
                               delete(BluetoothAvrcpInfo.CONTENT_URI, null, null);
        Log.d(TAG, " DeinitDatabase rows_deleted "+ rows_deleted);
    }
    private String getPlayStatusString(byte playStatus)
    {
        switch(playStatus)
        {
        case PLAY_STATUS_STOPPED:
             return "STOPPED";
        case PLAY_STATUS_PLAYING:
            return "PLAYING";
        case PLAY_STATUS_PAUSED:
            return "PAUSED";
        case PLAY_STATUS_FWD_SEEK:
            return "FWD_SEEK";
        case PLAY_STATUS_REV_SEEK:
            return "REV_SEEK";
        }
        return BluetoothAvrcpInfo.PLAY_STATUS_INVALID;
    }
    private String getShuffleStatusString()
    {
        for (PlayerSettings plSettings: mRemoteData.mSupportedApplicationSettingsAttribute)
        {
            if (plSettings.attr_Id == ATTRIB_SHUFFLE_STATUS)
            {
                switch(plSettings.attr_val)
                {
                case SHUFFLE_STATUS_OFF:
                     return "SHUFFLE_OFF";
                case SHUFFLE_STATUS_GROUP_SHUFFLE:
                    return "SHUFFLE_GROUP_SHUFFLE";
                case SHUFFLE_STATUS_ALL_TRACK_SHUFFLE:
                    return "SHUFFLE_ALL_TRACK_SHUFFLE";
                }
            }
        }
        return BluetoothAvrcpInfo.SHUFFLE_STATUS_INVALID;
    }
    private String getScanStatusString()
    {
        for (PlayerSettings plSettings: mRemoteData.mSupportedApplicationSettingsAttribute)
        {
            if (plSettings.attr_Id == ATTRIB_SCAN_STATUS)
            {
                switch(plSettings.attr_val)
                {
                case SCAN_STATUS_OFF:
                     return "SCAN_OFF";
                case SCAN_STATUS_GROUP_SCAN:
                    return "SCAN_GROUP_SCAN";
                case SCAN_STATUS_ALL_TRACK_SCAN:
                    return "SCAN_ALL_TRACK_SCAN";
                }
            }
        }
        return BluetoothAvrcpInfo.SCAN_STATUS_INVALID;
    }
    private String getEqualizerStatusString()
    {
        for (PlayerSettings plSettings: mRemoteData.mSupportedApplicationSettingsAttribute)
        {
            if (plSettings.attr_Id == ATTRIB_EQUALIZER_STATUS)
            {
                switch(plSettings.attr_val)
                {
                case EQUALIZER_STATUS_OFF:
                     return "EQUALIZER_OFF";
                case EQUALIZER_STATUS_ON:
                    return "EQUALIZER_ON";
                }
            }
        }
        return BluetoothAvrcpInfo.EQUALIZER_STATUS_INVALID;
    }
    private String getRepeatStatusString()
    {
        for (PlayerSettings plSettings: mRemoteData.mSupportedApplicationSettingsAttribute)
        {
            if (plSettings.attr_Id == ATTRIB_REPEAT_STATUS)
            {
                switch(plSettings.attr_val)
                {
                case REPEAT_STATUS_OFF:
                     return "REPEAT_OFF";
                case REPEAT_STATUS_SINGLE_TRACK_REPEAT:
                    return "REPEAT_SINGLE_TRACK_REPEAT";
                case REPEAT_STATUS_GROUP_REPEAT:
                    return "REPEAT_GROUP_REPEAT";
                case REPEAT_STATUS_ALL_TRACK_REPEAT:
                    return "REPEAT_ALL_TRACK_REPEAT";
                }
            }
        }
        return  BluetoothAvrcpInfo.REPEAT_STATUS_INVALID;
    }

    private void updateElementAttribute()
    {
        Log.d(TAG," updateElementAttribute " + mRemoteData.mMetadata.toString());
        ContentValues values = new ContentValues();
        values.put(BluetoothAvrcpInfo.TITLE, mRemoteData.mMetadata.trackTitle);
        values.put(BluetoothAvrcpInfo.ARTIST_NAME, mRemoteData.mMetadata.artist);
        values.put(BluetoothAvrcpInfo.ALBUM_NAME, mRemoteData.mMetadata.albumTitle);
        values.put(BluetoothAvrcpInfo.TRACK_NUM, mRemoteData.mMetadata.trackNum);
        values.put(BluetoothAvrcpInfo.TOTAL_TRACKS, mRemoteData.mMetadata.totalTrackNum);
        values.put(BluetoothAvrcpInfo.GENRE, mRemoteData.mMetadata.genre);
        values.put(BluetoothAvrcpInfo.PLAYING_TIME, mRemoteData.mMetadata.playTime);
        values.put(BluetoothAvrcpInfo.TOTAL_TRACK_TIME, mRemoteData.mMetadata.totalTrackLen);
        int rowsUpdated = sAvrcpControllerService.getContentResolver().
                                  update(BluetoothAvrcpInfo.CONTENT_URI, values, null, null);
        Log.d(TAG," updateElementAttribute num_rows_updated " + rowsUpdated);
    }
    private void updateTrackNum()
    {
        ContentValues values = new ContentValues();
        values.put(BluetoothAvrcpInfo.TRACK_NUM, mRemoteData.mMetadata.trackNum);
        int rowsUpdated = sAvrcpControllerService.getContentResolver().
                                  update(BluetoothAvrcpInfo.CONTENT_URI, values, null, null);
        Log.d(TAG," updateTrackNum num_rows_updated " + rowsUpdated);
    }
    private void updatePlayTime()
    {
        ContentValues values = new ContentValues();
        values.put(BluetoothAvrcpInfo.PLAYING_TIME, mRemoteData.mMetadata.playTime);
        int rowsUpdated = sAvrcpControllerService.getContentResolver().
                                  update(BluetoothAvrcpInfo.CONTENT_URI, values, null, null);
        Log.d(TAG," updatePlayTime num_rows_updated " + rowsUpdated);
    }
    private void updatePlayerApplicationSettings()
    {
        ContentValues values =  new ContentValues();
        values.put(BluetoothAvrcpInfo.REPEAT_STATUS, getRepeatStatusString());
        values.put(BluetoothAvrcpInfo.SHUFFLE_STATUS, getShuffleStatusString());
        values.put(BluetoothAvrcpInfo.SCAN_STATUS, getScanStatusString());
        values.put(BluetoothAvrcpInfo.EQUALIZER_STATUS, getEqualizerStatusString());
        int rowsUpdated = sAvrcpControllerService.getContentResolver().
                                    update(BluetoothAvrcpInfo.CONTENT_URI,values, null, null);
        Log.d(TAG," updatePlayerApplicationSettings num_rows_updated " + rowsUpdated);
    }
    private void updatePlayStatus()
    {
        ContentValues values =  new ContentValues();
        Log.d(TAG," updatePlayStatus " + mRemoteData.mMetadata.toString());
        values.put(BluetoothAvrcpInfo.PLAY_STATUS,
                          getPlayStatusString(mRemoteData.mMetadata.playStatus));
        values.put(BluetoothAvrcpInfo.PLAYING_TIME, mRemoteData.mMetadata.playTime);
        values.put(BluetoothAvrcpInfo.TOTAL_TRACK_TIME, mRemoteData.mMetadata.totalTrackLen);
        int rowsUpdated = sAvrcpControllerService.getContentResolver().
                                   update(BluetoothAvrcpInfo.CONTENT_URI,values, null, null);
        Log.d(TAG," updatePlayStatus num_rows_updated " + rowsUpdated);
    }
    private boolean isEventSupported(byte eventId)
    {
        if ((eventId == EVENT_PLAYBACK_STATUS_CHANGED)||
            (eventId == EVENT_PLAYBACK_POS_CHANGED)||
            (eventId == EVENT_PLAYER_APPLICATION_SETTINGS_CHANGED)||
            (eventId == EVENT_TRACK_CHANGED))
            return true;
        else
            return false;
    }
    private void handleNotificationTimeout(int cmd)
    {
        Log.d(TAG," handleNotificationTimeout cmd " + cmd);
        int notificaitonId = cmd - 2000;
        int index;
        for (index = 0; index < mRemoteData.mNotifyEvent.size(); index++) {
            if (notificaitonId == mRemoteData.mNotifyEvent.get(index).notify_event_id)
                break;
        }
        if (index == mRemoteData.mNotifyEvent.size())
            return;
        NotifyEvents notifyEvent = mRemoteData.mNotifyEvent.get(index);
        if ((notifyEvent.notify_event_id == notificaitonId) &&
            (notifyEvent.notify_state == NOTIFY_INTERIM_EXPECTED))
        {
            notifyEvent.notify_state = NOTIFY_NOT_NOTIFIED;
            registerFurtherNotification(notificaitonId);
        }
    }
    private void handleCmdTimeout(int cmd)
    {
        Log.d(TAG," CMD " + cmd + " Timeout Happened");
        switch(cmd)
        {
        case MESSAGE_GET_SUPPORTED_COMPANY_ID:
            mHandler.sendEmptyMessage(MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_ATTRIB);
            break;
        case MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_ATTRIB:
        case MESSAGE_GET_CURRENT_PLAYER_APPLICATION_SETTINGS:
            mHandler.sendEmptyMessage(MESSAGE_GET_SUPPORTED_EVENTS);
            break;
        case MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_VALUES:
            getFurtherPlayerSettingAttrib(ATTRIBUTE_FETCH_SKIP);
            break;
        case MESSAGE_GET_SUPPORTED_EVENTS:
            /*
             * If Timeout for these command happened, We would not do anything
             * We will wait for Application to send request for
             * ElementAttributes/PlayStatus. Only after that MetaData/Playstatus will resume
             */
            break;
        case MESSAGE_TIMEOUT_APPL_SETTINGS_CHANGED:
        case MESSAGE_TIMEOUT_PLAYBACK_POS_CHNAGED:
        case MESSAGE_TIMEOUT_PLAYBACK_STATUS_CHANGED:
        case MESSAGE_TIMEOUT_TRACK_CHANGED:
        case MESSAGE_TIMEOUT_VOLUME_CHANGED:
            handleNotificationTimeout(cmd);
            break;
        case MESSAGE_TIMEOUT_ATTRIBUTE_TITLE:
        case MESSAGE_TIMEOUT_ATTRIBUTE_ARTIST_NAME:
        case MESSAGE_TIMEOUT_ATTRIBUTE_ALBUM_NAME:
        case MESSAGE_TIMEOUT_ATTRIBUTE_GENRE:
        case MESSAGE_TIMEOUT_ATTRIBUTE_PLAYING_TIME:
        case MESSAGE_TIMEOUT_ATTRIBUTE_TOTAL_TRACK_NUMBER:
        case MESSAGE_TIMEOUT_ATTRIBUTE_TRACK_NUMBER:
            getFurtherElementAttribute(ATTRIBUTE_FETCH_SKIP);
            break;
        case MESSAGE_GET_PLAY_STATUS:
            mHandler.sendEmptyMessage(MESSAGE_GET_PLAY_STATUS); // reque this command
            break;
        case MESSAGE_SET_CURRENT_PLAYER_APPLICATION_SETTINGS:
            // do nothing here
            break;
        }
    }
    private String utf8ToString(byte[] input)
    {
        Charset UTF8_CHARSET = Charset.forName("UTF-8");
        return new String(input,UTF8_CHARSET);
    }
    private int asciiToInt(int len, byte[] array)
    {
        return Integer.parseInt(utf8ToString(array));
    }
    private void resetElementAttribute(int attributeId, int asciiStringLen) {
        if(asciiStringLen == 0) {
            switch(attributeId)
            {
            case MEDIA_ATTRIBUTE_TITLE:
                mRemoteData.mMetadata.trackTitle = BluetoothAvrcpInfo.TITLE_INVALID;
                break;
            case MEDIA_ATTRIBUTE_ARTIST_NAME:
                mRemoteData.mMetadata.artist = BluetoothAvrcpInfo.ARTIST_NAME_INVALID;
                break;
            case MEDIA_ATTRIBUTE_ALBUM_NAME:
                mRemoteData.mMetadata.albumTitle = BluetoothAvrcpInfo.ALBUM_NAME_INVALID;
                break;
            case MEDIA_ATTRIBUTE_GENRE:
                mRemoteData.mMetadata.genre = BluetoothAvrcpInfo.GENRE_INVALID;
                break;
            case MEDIA_ATTRIBUTE_TRACK_NUMBER:
                mRemoteData.mMetadata.trackNum = BluetoothAvrcpInfo.TRACK_NUM_INVALID;
                break;
            case MEDIA_ATTRIBUTE_TOTAL_TRACK_NUMBER:
                mRemoteData.mMetadata.totalTrackNum = BluetoothAvrcpInfo.TOTAL_TRACKS_INVALID;
                break;
            case MEDIA_ATTRIBUTE_PLAYING_TIME:
                mRemoteData.mMetadata.totalTrackLen = BluetoothAvrcpInfo.TOTAL_TRACK_TIME_INVALID;
                break;
            }
        }
    }
    private int parseElementAttributes(int currentIndex, int attributeId, ByteBuffer attribBuffer)
    {
        Log.d(TAG,"parseElementAttributes Id = " + attributeId);
        currentIndex += 2; // for character set
        int asciiStringLen = attribBuffer.getChar(currentIndex);
        Log.d(TAG," asciiStringLen "+ asciiStringLen);
        currentIndex += 2;// for string len
        if((asciiStringLen <= 0) || ((currentIndex + asciiStringLen) > attribBuffer.capacity()))
        {
            Log.d(TAG," parseElementAttribute wrong buffer");
            resetElementAttribute(attributeId, asciiStringLen);
            return currentIndex;
        }
        byte[] asciiString = new byte[asciiStringLen];
        attribBuffer.position(currentIndex);
        attribBuffer.get(asciiString, 0, asciiStringLen);
        currentIndex += asciiStringLen;
        switch(attributeId)
        {
        case MEDIA_ATTRIBUTE_TITLE:
            mRemoteData.mMetadata.trackTitle = utf8ToString(asciiString);
            break;
        case MEDIA_ATTRIBUTE_ARTIST_NAME:
            mRemoteData.mMetadata.artist = utf8ToString(asciiString);
            break;
        case MEDIA_ATTRIBUTE_ALBUM_NAME:
            mRemoteData.mMetadata.albumTitle = utf8ToString(asciiString);
            break;
        case MEDIA_ATTRIBUTE_GENRE:
            mRemoteData.mMetadata.genre = utf8ToString(asciiString);
            break;
        case MEDIA_ATTRIBUTE_TRACK_NUMBER:
            mRemoteData.mMetadata.trackNum = asciiToInt(asciiStringLen,asciiString);
            break;
        case MEDIA_ATTRIBUTE_TOTAL_TRACK_NUMBER:
            mRemoteData.mMetadata.totalTrackNum = asciiToInt(asciiStringLen,asciiString);
            break;
        case MEDIA_ATTRIBUTE_PLAYING_TIME:
            mRemoteData.mMetadata.totalTrackLen = asciiToInt(asciiStringLen,asciiString);
            break;
        }
        return currentIndex;
    }
    private void handleProcessGetElementAttribute(int numAttributes, ByteBuffer attribBuffer)
    {
        Log.d(TAG,"handleProcessGetElementAttribute numAttrib ="+ numAttributes);
        int attributeId = 0;
        int currentIndex = 0;
        for (int count = 0; count < numAttributes; count++)
        {
            /*
             * In case remote sends rsp for more attributes that we can accomodate.
             * Basically to avoid erroneous conditions.
             */
            if (mRemoteData.mMetadata.attributesFetchedId >= requestedElementAttribs.length)
                continue;
            attributeId = attribBuffer.getInt(currentIndex);
            if (requestedElementAttribs[mRemoteData.mMetadata.attributesFetchedId] != attributeId)
            {
                Log.e(TAG," Received Rsp for attributeId "+ attributeId +" Requested ID = " +
                       requestedElementAttribs[mRemoteData.mMetadata.attributesFetchedId]);
                break;
            }
            currentIndex += 4; // 4 bytes for Int
            Log.d(TAG," attributeID = "+ attributeId);
            /*
             * remove timeout message if it is present already in que.
             */
            if (mHandler.hasMessages(GET_ELEMENT_ATTR_TIMEOUT_BASE + attributeId))
            {
                mHandler.removeMessages(GET_ELEMENT_ATTR_TIMEOUT_BASE + attributeId);
                Log.d(TAG," Timeout CMD = " + attributeId + "dequed");
            }
            currentIndex = parseElementAttributes(currentIndex,attributeId,attribBuffer);
        }
        getFurtherElementAttribute(ATTRIBUTE_FETCH_CONTINUE);
    }
    private void handleProcessNotificationResponse(int notificationId, int notificationType,
                                             ByteBuffer notificationRsp)
    {
        Log.d(TAG,"handleProcessNotificationResponse id " + notificationId +
                   " type = " + notificationType);
        /*
         * First remove timeout if already queued
         */
        byte oldState = NOTIFY_NOT_NOTIFIED;
        if (mHandler.hasMessages(2000 + notificationId))
        {
            mHandler.removeMessages(2000 + notificationId);
            Log.d(TAG," Timeout Notification CMD dequeued ");
        }
        for (NotifyEvents notifyEvent: mRemoteData.mNotifyEvent)
        {
            if (notificationId != notifyEvent.notify_event_id)
                continue;
            oldState = notifyEvent.notify_state;
            if ((oldState == NOTIFY_INTERIM_EXPECTED) &&
                (notificationType == NOTIFICATION_RSP_TYPE_INTERIM)) {
                notifyEvent.notify_state = NOTIFY_CHANGED_EXPECTED;
            }
            else if ((oldState == NOTIFY_CHANGED_EXPECTED) &&
                    (notificationType == NOTIFICATION_RSP_TYPE_CHANGED)) {
                 notifyEvent.notify_state = NOTIFY_NOT_NOTIFIED;
            }
            break;
        }
            switch(notificationId)
            {
            case EVENT_PLAYBACK_STATUS_CHANGED:
                byte oldPlayStatus = mRemoteData.mMetadata.playStatus;
                mRemoteData.mMetadata.playStatus = notificationRsp.get(1);
                /*
                 * Check if there is a transition from Stopped/Paused to Playing
                 * and Remote does not support EVENT_PLAYBACK_POS
                 * We need to Que GetPlayBackStatus command.
                 */
                if (((oldPlayStatus == PLAY_STATUS_STOPPED)||
                   (oldPlayStatus == PLAY_STATUS_PAUSED))&&
                   (mRemoteData.mMetadata.playStatus == PLAY_STATUS_PLAYING)) {
                    if (!(mRemoteData.mEventsSupported.contains(EVENT_PLAYBACK_POS_CHANGED))||
                        !(mRemoteData.mEventsSupported.contains(EVENT_PLAYBACK_STATUS_CHANGED))) {
                        Log.d(TAG," State Transition Triggered, Que GetPlayStatus ");
                        mHandler.sendEmptyMessage(MESSAGE_GET_PLAY_STATUS);
                    }
                }
                updatePlayStatus();
                break;
            case EVENT_PLAYBACK_POS_CHANGED:
                mRemoteData.mMetadata.playTime = notificationRsp.getInt(1);
                updatePlayTime();
                break;
            case EVENT_PLAYER_APPLICATION_SETTINGS_CHANGED:
                int numPlayerSettingAttribs = notificationRsp.get(1);
                int attribIndex = 2; // first attribute will be at 2 index
                for (int count = 0; count < numPlayerSettingAttribs; count ++)
                {
                    Byte attributeId = notificationRsp.get(attribIndex);
                    for (PlayerSettings plSettings:
                         mRemoteData.mSupportedApplicationSettingsAttribute)
                    {
                        if (plSettings.attr_Id == attributeId)
                        {
                            plSettings.attr_val = notificationRsp.get(attribIndex+1);
                        }
                    }
                    attribIndex = attribIndex + 2;
                }
                updatePlayerApplicationSettings();
                if ((oldState == NOTIFY_CHANGED_EXPECTED) &&
                    (notificationType == NOTIFICATION_RSP_TYPE_CHANGED))
                {
                    mHandler.sendEmptyMessage(MESSAGE_GET_CURRENT_PLAYER_APPLICATION_SETTINGS);
                }
                break;
            case EVENT_TRACK_CHANGED:
                if ((oldState == NOTIFY_CHANGED_EXPECTED) &&
                    (notificationType == NOTIFICATION_RSP_TYPE_CHANGED))
                {
                    Log.d(TAG," Track change Happened, que GetElement, PlayerSetting ");
                    mHandler.sendEmptyMessage(MESSAGE_GET_ELEMENT_ATTRIBUTE);
                    mHandler.sendEmptyMessage(MESSAGE_GET_CURRENT_PLAYER_APPLICATION_SETTINGS);
                }
                break;
            }
        registerFurtherNotification(EVENT_NOTIFICAION_ID_NONE);
    }
    private void handleProcessPlayStatus(ByteBuffer bb)
    {
        int currentIndex = 0;
        int bufSize = bb.capacity();
        mRemoteData.mMetadata.totalTrackLen = bb.getInt(currentIndex);
        currentIndex += 4;
        mRemoteData.mMetadata.playTime = bb.getInt(currentIndex);
        currentIndex += 4;
        mRemoteData.mMetadata.playStatus = bb.get(currentIndex);
        updatePlayStatus();
        if (!(mRemoteData.mEventsSupported.contains(EVENT_PLAYBACK_POS_CHANGED))||
           !(mRemoteData.mEventsSupported.contains(EVENT_PLAYBACK_STATUS_CHANGED)))
        {
            if ((mRemoteData.mMetadata.playStatus != PLAY_STATUS_STOPPED)&&
                (mRemoteData.mMetadata.playStatus != PLAY_STATUS_PAUSED)) {
                Log.d(TAG," POS and Status changed not supported");
                mHandler.sendEmptyMessageDelayed(MESSAGE_GET_PLAY_STATUS, GET_PLAY_STATUS_INTERVAL);
            }
        }
    }
    private void setAbsVolume(int absVol)
    {
        int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int currIndex = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int newIndex = (maxVolume*absVol)/ABS_VOL_BASE;
        Log.d(TAG," setAbsVolume ="+absVol + " maxVol = " + maxVolume + " cur = " + currIndex +
                                              " new = "+newIndex);
        /*
         * In some cases change in percentage is not sufficient enough to warrant
         * change in index values which are in range of 0-15. For such cases
         * no action is required
         */
        if (newIndex != currIndex) {
            if (mRemoteData.absVolNotificationState == NOTIFY_RSP_INTERIM_SENT)
                mRemoteData.absVolNotificationState = NOTIFY_RSP_ABS_VOL_DEFERRED;
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newIndex, 0);
        }
        sendAbsVolRspNative(absVol);
    }
    private void handleProcessAbsVolNotification()
    {
        Log.d(TAG," handleProcessAbsVolNotification ");
        if(mRemoteData == null)
            return;
        if(mRemoteData.absVolNotificationState == NOTIFY_NOT_REGISTERED)
        {
            int maxVol = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int currIndex = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int percentageVol = ((currIndex*ABS_VOL_BASE)/maxVol);
            Log.d(TAG," maxVol ="+maxVol+" currentIndex ="+currIndex+
                                                   " percentageVol ="+percentageVol);
            byte rspType = NOTIFICATION_RSP_TYPE_INTERIM;
            mRemoteData.absVolNotificationState = NOTIFY_RSP_INTERIM_SENT;
            sendRegisterAbsVolRspNative(rspType,percentageVol);
        }
    }
    /** Handles Avrcp messages. */
    private final class AvrcpMessageHandler extends Handler {
        private AvrcpMessageHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG," HandleMessage: "+ msg.what +
                  " Remote Connected " + !mConnectedDevices.isEmpty());
            switch (msg.what) {
            case MESSAGE_SEND_PASS_THROUGH_CMD:
                BluetoothDevice device = (BluetoothDevice)msg.obj;
                sendPassThroughCommandNative(getByteAddress(device), msg.arg1, msg.arg2);
                break;
            case MESSAGE_GET_SUPPORTED_COMPANY_ID: //first command in AVRCP 1.3 that we send.
                /*
                 * If Sink is not active we won't allow AVRCP MetaData
                 */
                A2dpSinkService a2dpSinkService = A2dpSinkService.getA2dpSinkService();
                if (a2dpSinkService == null) {
                    Log.e(TAG," A2DP Sink service not started, MetaData will not proceed");
                    return;
                }
                initializeDatabase();
                getSupportedCapabilities(COMPANY_ID);
                break;
            case MESSAGE_GET_SUPPORTED_EVENTS:
                if ((mRemoteData.mNotifyEvent != null)&&(mRemoteData.mNotifyEvent.isEmpty()))
                    getSupportedCapabilities(EVENTS_SUPPORTED);
                break;
            case MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_ATTRIB:
                getPlayerApplicationSettingsAttrib();
                break;
            case MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_VALUES:
                getFurtherPlayerSettingAttrib(ATTRIBUTE_FETCH_FRESH);
                break;
            case MESSAGE_GET_CURRENT_PLAYER_APPLICATION_SETTINGS:
                if (!(mHandler.hasMessages(MESSAGE_GET_CURRENT_PLAYER_APPLICATION_SETTINGS)))
                    getCurrentPlayerApplicationSettingsValues();
                break;
            case MESSAGE_SET_CURRENT_PLAYER_APPLICATION_SETTINGS:
                setCurrentPlayerApplicationSettingsValues(msg.arg1, msg.arg2);
                break;
            case MESSAGE_DEINIT_AVRCP_DATABASE:
                deinitDatabase();
                break;
            case MESSAGE_CMD_TIMEOUT:
                int cmd = (Integer)msg.obj;
                handleCmdTimeout(cmd);
                break;
            case MESSAGE_TIMEOUT_APPL_SETTINGS_CHANGED:
            case MESSAGE_TIMEOUT_PLAYBACK_POS_CHNAGED:
            case MESSAGE_TIMEOUT_PLAYBACK_STATUS_CHANGED:
            case MESSAGE_TIMEOUT_TRACK_CHANGED:
            case MESSAGE_TIMEOUT_VOLUME_CHANGED:
            case MESSAGE_TIMEOUT_ATTRIBUTE_TITLE:
            case MESSAGE_TIMEOUT_ATTRIBUTE_ARTIST_NAME:
            case MESSAGE_TIMEOUT_ATTRIBUTE_ALBUM_NAME:
            case MESSAGE_TIMEOUT_ATTRIBUTE_GENRE:
            case MESSAGE_TIMEOUT_ATTRIBUTE_PLAYING_TIME:
            case MESSAGE_TIMEOUT_ATTRIBUTE_TOTAL_TRACK_NUMBER:
            case MESSAGE_TIMEOUT_ATTRIBUTE_TRACK_NUMBER:
                handleCmdTimeout(msg.what);
                break;

            case MESSAGE_GET_ELEMENT_ATTRIBUTE:
                getFurtherElementAttribute(ATTRIBUTE_FETCH_FRESH);
                break;
            case ABORT_FETCH_ELEMENT_ATTRIBUTE:
                if ((mRemoteData != null) && (mRemoteData.mMetadata != null))
                    mRemoteData.mMetadata.attributesFetchedId = -1; // reset it to -1 again.
                break;
            case MESSAGE_GET_PLAY_STATUS:
                if (mHandler.hasMessages(MESSAGE_GET_PLAY_STATUS)) {
                    Log.d(TAG," Get Play Status Already There, return ");
                    return;
                }
                Message messsag = mHandler.obtainMessage(MESSAGE_CMD_TIMEOUT,
                                           0,0,MESSAGE_GET_PLAY_STATUS);
                mHandler.sendMessageDelayed(messsag, MSG_TIMEOUT_MTP);
                getPlayStatusNative();
                break;
            case MESSAGE_PROCESS_RC_FEATURES:
                /* check if we have already initiated MetaData Procedure */
               if ((mRemoteData != null)&&(mRemoteData.mRemoteFeatures != 0))
                   break;
               /* this is first time */
                int remoteFeatures = msg.arg1;
                BluetoothDevice remoteDevice =  (BluetoothDevice)msg.obj;
                if (mConnectedDevices.contains(remoteDevice)) {
                    mRemoteData.mRemoteFeatures = remoteFeatures;
                    if ((mRemoteData.mRemoteFeatures & BTRC_FEAT_METADATA) != 0)
                        mHandler.sendMessage(mHandler.
                        obtainMessage(MESSAGE_GET_SUPPORTED_COMPANY_ID,0, 0, remoteDevice));
                }
                break;
            case MESSAGE_PROCESS_CONNECTION_CHANGE:
                int connected = msg.arg1;
                BluetoothDevice rtDevice =  (BluetoothDevice)msg.obj;
                if (connected == 1)
                {
                    /*
                     * Connection up but RC features not received yet. We will
                     * send get_company_ID later.
                     */
                    if (mRemoteData == null)
                        mRemoteData = new RemoteAvrcpData();
                    mRemoteData.mCompanyIDSupported = new ArrayList<Integer>();
                    mRemoteData.mEventsSupported = new ArrayList<Byte>();
                    mRemoteData.mMetadata = new Metadata();
                    mRemoteData.mNotifyEvent = new ArrayList<NotifyEvents>();
                    mRemoteData.mSupportedApplicationSettingsAttribute =
                                             new ArrayList<PlayerSettings>();
                    mRemoteData.absVolNotificationState = NOTIFY_NOT_REGISTERED;
                    mRemoteData.playerSettingAttribIdFetch = 0;
                    mRemoteData.mRemoteFeatures = 0;
                }
                else
                {
                    mHandler.removeCallbacksAndMessages(null);
                    mHandler.sendEmptyMessage(MESSAGE_DEINIT_AVRCP_DATABASE);
                    if (mRemoteData != null)
                    {
                        mRemoteData.mCompanyIDSupported.clear();
                        mRemoteData.mEventsSupported.clear();
                        mRemoteData.mMetadata.resetMetaData();
                        mRemoteData.mNotifyEvent.clear();
                        mRemoteData.mSupportedApplicationSettingsAttribute.clear();
                        mRemoteData.absVolNotificationState = NOTIFY_NOT_REGISTERED;
                        mRemoteData.mRemoteFeatures = 0;
                        Log.d(TAG," RC_features, conn_change down " + mRemoteData.mRemoteFeatures);
                        mRemoteData.playerSettingAttribIdFetch = 0;
                        mRemoteData = null;
                    }
                }
                break;
            case MESSAGE_PROCESS_SUPPORTED_COMPANY_ID:
                List<Integer> company_ids = (List<Integer>)msg.obj;
                mRemoteData.mCompanyIDSupported.addAll(company_ids);
                mHandler.sendEmptyMessage(MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_ATTRIB);
                break;
            case MESSAGE_PROCESS_SUPPORTED_EVENTS:
                List<Byte> events_supported = (List<Byte>)msg.obj;
                mRemoteData.mEventsSupported.addAll(events_supported);
                for (Byte event: mRemoteData.mEventsSupported)
                {
                    NotifyEvents notifyevent = new NotifyEvents();
                    notifyevent.notify_event_id = event;
                    notifyevent.notify_state = NOTIFY_NOT_NOTIFIED;
                    mRemoteData.mNotifyEvent.add(notifyevent);
                }
                registerFurtherNotification(EVENT_NOTIFICAION_ID_NONE);
                break;
            case MESSAGE_PROCESS_PLAYER_APPLICATION_SETTINGS_ATTRIB:
                List<PlayerSettings> player_settings_supported = (List<PlayerSettings>)msg.obj;
                mRemoteData.mSupportedApplicationSettingsAttribute.
                                     addAll(player_settings_supported);
                mHandler.sendEmptyMessage(MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_VALUES);
                break;
            case MESSAGE_PROCESS_PLAYER_APPLICATION_SETTINGS_VALUES:
                mRemoteData.playerSettingAttribIdFetch ++;
                getFurtherPlayerSettingAttrib(msg.arg1);
                break;
            case MESSAGE_PROCESS_CURRENT_PLAYER_APPLICATION_SETTINGS:
                updatePlayerApplicationSettings();
                if ((mRemoteData.mNotifyEvent != null)&&(mRemoteData.mNotifyEvent.isEmpty()))
                    mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_GET_SUPPORTED_EVENTS));
                break;
            case MESSAGE_PROCESS_NOTIFICATION_RESPONSE:
                int notificationId = msg.arg1;
                int notificationType = msg.arg2;
                ByteBuffer bb = (ByteBuffer)msg.obj;
                handleProcessNotificationResponse(notificationId, notificationType, bb);
                break;
            case MESSAGE_PROCESS_ELEMENT_ATTRIBUTE:
                if ((mRemoteData.mMetadata.attributesFetchedId == -1) ||
                   (mHandler.hasMessages(ABORT_FETCH_ELEMENT_ATTRIBUTE))) {
                   Log.d(TAG," ID reset, Fetch from Fresh");
                   mHandler.sendEmptyMessage(MESSAGE_GET_ELEMENT_ATTRIBUTE);
                   break;
                }
                int processMode = msg.arg2;
                if (processMode == ATTRIBUTE_FETCH_CONTINUE)
                {
                    int numAttributes = msg.arg1;
                    ByteBuffer bbRsp = (ByteBuffer)msg.obj;
                    handleProcessGetElementAttribute(numAttributes, bbRsp);
                }
                else if(processMode == ATTRIBUTE_FETCH_SKIP)
                    getFurtherElementAttribute(ATTRIBUTE_FETCH_SKIP);
                break;
            case MESSAGE_PROCESS_PLAY_STATUS:
                ByteBuffer playStatusRsp = (ByteBuffer)msg.obj;
                handleProcessPlayStatus(playStatusRsp);
                break;
            case MESSAGE_PROCESS_SET_ABS_VOL_CMD:
                setAbsVolume(msg.arg1);
                break;
            case MESSAGE_PROCESS_REGISTER_ABS_VOL_REQUEST:
                handleProcessAbsVolNotification();
            }
        }
    }

    private void onConnectionStateChanged(boolean connected, byte[] address) {
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
            (Utils.getAddressStringFromByte(address));
        if (device == null)
            return;
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
            Message msg =  mHandler.obtainMessage(MESSAGE_PROCESS_CONNECTION_CHANGE, 1, 0, device);
            mHandler.sendMessage(msg);
        } else if (!connected && oldState == BluetoothProfile.STATE_CONNECTED) {
            mConnectedDevices.remove(device);
            Message msg =  mHandler.obtainMessage(MESSAGE_PROCESS_CONNECTION_CHANGE, 0, 0, device);
            mHandler.sendMessage(msg);
        }
    }

    private void getRcFeatures(byte[] address, int features) {
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        Message msg = mHandler.obtainMessage(MESSAGE_PROCESS_RC_FEATURES, features, 0,
                                             device);
        mHandler.sendMessage(msg);
    }

    private void handlePassthroughRsp(int id, int keyState) {
        Log.d(TAG, "passthrough response received as: key: "
                                + id + " state: " + keyState);
    }
    private void handleGetCapabilitiesResponse(byte[] address, int capability_id,
                                           int[] supported_values,int num_supported, byte rsp_type)
    {
        Log.d(TAG, "handleGetCapabilitiesResponse cap_id" + capability_id + " num_supported "
                                                          + num_supported+ "rsp_type " + rsp_type);
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        if (num_supported <= 0) {
            /*
             *  supported values are not reliable. Drop further processing
             *  Remove Timeout Commands
             */
             if (mHandler.hasMessages(MESSAGE_CMD_TIMEOUT,COMPANY_ID))
                 mHandler.removeMessages(MESSAGE_CMD_TIMEOUT, COMPANY_ID);
             else if (mHandler.hasMessages(MESSAGE_CMD_TIMEOUT,EVENTS_SUPPORTED))
                mHandler.removeMessages(MESSAGE_CMD_TIMEOUT, EVENTS_SUPPORTED);
             return;
        }
        if (mHandler.hasMessages(MESSAGE_CMD_TIMEOUT, capability_id))
        {
            mHandler.removeMessages(MESSAGE_CMD_TIMEOUT, capability_id);
            Log.d(TAG," Timeout CMD dequeued ");
        }
        if (rsp_type != AVRC_RSP_IMPL_STBL)
        {
            if(capability_id == COMPANY_ID)
                mHandler.sendEmptyMessage(MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_ATTRIB);
            /* for events supported failure, we don't do anything */
            return;
        }
        Message msg;
        switch(capability_id)
        {
            case COMPANY_ID:
                ArrayList<Integer> supportedCompanyIds = new ArrayList<Integer>();
                for (int count = 0; count<num_supported; ++count)
                supportedCompanyIds.add(supported_values[count]);
                msg = mHandler.
                    obtainMessage(MESSAGE_PROCESS_SUPPORTED_COMPANY_ID,supportedCompanyIds);
                mHandler.sendMessage(msg);
                break;
            case EVENTS_SUPPORTED:
                ArrayList<Byte> supportedEvents = new ArrayList<Byte>();
                for (int count = 0; count<num_supported; ++count)
                {
                    Byte supported_event =
                      Byte.valueOf((Integer.valueOf(supported_values[count])).byteValue());
                    if (!isEventSupported(supported_event))
                        continue;
                    supportedEvents.add(supported_event);
                }
                msg = mHandler.obtainMessage(MESSAGE_PROCESS_SUPPORTED_EVENTS,supportedEvents);
                mHandler.sendMessage(msg);
                break;
        }
    }

    private void handleListPlayerApplicationSettingsAttrib(byte[] address,
            byte[] supported_setting_attrib,int num_attrib, byte rsp_type)
    {
        Log.d(TAG, "handleListPlayerApplicationSettingsAttrib "
             + num_attrib + " rsp_type " + rsp_type);
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        if (mHandler.hasMessages(MESSAGE_CMD_TIMEOUT,
                 MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_ATTRIB))
        {
            mHandler.removeMessages(MESSAGE_CMD_TIMEOUT,
                 MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_ATTRIB);
            Log.d(TAG," Timeout CMD dequeued ");
        }
        if ((rsp_type != AVRC_RSP_IMPL_STBL)||(num_attrib <= 0))
        {
            mHandler.sendEmptyMessage(MESSAGE_GET_SUPPORTED_EVENTS);
            return;
        }
        ArrayList<PlayerSettings> supported_attrib = new ArrayList<PlayerSettings>();
        for (int count = 0; count < num_attrib; count++)
        {
            PlayerSettings attrib = new PlayerSettings();
            attrib.attr_Id = supported_setting_attrib[count];
            attrib.supported_values = null;
            supported_attrib.add(attrib);
        }
        mHandler.sendMessage(mHandler.
            obtainMessage(MESSAGE_PROCESS_PLAYER_APPLICATION_SETTINGS_ATTRIB,supported_attrib));
    }
    private byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
    }

    private void handleListPlayerApplicationSettingValue(byte[] address, byte[] supported_val,
                        byte num_supported_val, byte rsp_type)
    {
        Log.d(TAG,"handleListPlayerApplicationSettingValue num_supported " + num_supported_val
             + " rsp_type " + rsp_type);
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        if (mHandler.hasMessages(MESSAGE_CMD_TIMEOUT,
             MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_VALUES))
        {
            mHandler.removeMessages(MESSAGE_CMD_TIMEOUT,
                MESSAGE_GET_PLAYER_APPLICATION_SETTINGS_VALUES);
            Log.d(TAG," Timeout CMD dequeued ");
        }
        if ((rsp_type != AVRC_RSP_IMPL_STBL)||(num_supported_val <= 0))
        {
            mHandler.sendMessage(mHandler.obtainMessage(
                MESSAGE_PROCESS_PLAYER_APPLICATION_SETTINGS_VALUES, ATTRIBUTE_FETCH_SKIP, 0));
            return;
        }
        int fetch_id = mRemoteData.playerSettingAttribIdFetch;
        PlayerSettings plSetting = mRemoteData.mSupportedApplicationSettingsAttribute.get(fetch_id);
        plSetting.supported_values = new byte [num_supported_val];
        for (int count = 0; count < num_supported_val; ++count)
            plSetting.supported_values[count] = supported_val[count];
        mHandler.sendMessage(mHandler.obtainMessage(
              MESSAGE_PROCESS_PLAYER_APPLICATION_SETTINGS_VALUES, ATTRIBUTE_FETCH_CONTINUE, 0));
    }
    private void handleCurrentPlayerApplicationSettingsResponse(byte[] address,
                                     byte[] ids, byte[] values,byte num_attrib, byte rsp_type)
    {
        Log.d(TAG,"handleCurrentPlayerApplicationSettingsResponse num_attrib =" + num_attrib);
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        if (mHandler.hasMessages(MESSAGE_CMD_TIMEOUT,
               MESSAGE_GET_CURRENT_PLAYER_APPLICATION_SETTINGS))
        {
            mHandler.removeMessages(MESSAGE_CMD_TIMEOUT,
                MESSAGE_GET_CURRENT_PLAYER_APPLICATION_SETTINGS);
            Log.d(TAG," Timeout CMD dequeued ");
        }
        if ((rsp_type != AVRC_RSP_IMPL_STBL)||(num_attrib <= 0))
        {
            mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_GET_SUPPORTED_EVENTS));
            return;
        }
        for (int count = 0; count < num_attrib; count++)
        {
            byte attribute = ids[count]; // count increment to point to value of attrib_id.
            for (PlayerSettings plSetting: mRemoteData.mSupportedApplicationSettingsAttribute)
            {
                if (plSetting.attr_Id == attribute) {
                    plSetting.attr_val = values[count];
                }
            }
        }
        mHandler.sendEmptyMessage(MESSAGE_PROCESS_CURRENT_PLAYER_APPLICATION_SETTINGS);
    }
    /*
     * response contains array from Event-ID ( first byte of the array ).
     * rspLen is same as Parameter Length field of PDU.
     * rspType - either Interim or Notify.
     */
    private void handleNotificationRsp(byte[] address, byte rspType, int rspLen, byte[] response)
    {
        Log.d(TAG,"handleNotificationRsp ");
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if ((!mConnectedDevices.contains(device))||(rspLen <= 0))
            return;
        int notificationEventId = response[0];
        int notificaionRspType = rspType;
        Log.d(TAG," rsp_type " + rspType +" notificationId " + notificationEventId);
        if ((rspType != AVRC_RSP_INTERIM)&&(rspType != AVRC_RSP_CHANGED))
        {
            if (mHandler.hasMessages(2000 + notificationEventId))
            {
                mHandler.removeMessages(2000 + notificationEventId);
                Log.d(TAG," Timeout CMD dequeued ");
            }
            handleNotificationTimeout(2000 + notificationEventId);
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(response, 0, rspLen);
        mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_PROCESS_NOTIFICATION_RESPONSE,
              notificationEventId, notificaionRspType, bb));
    }
    /*
     * attribRsp contains array after Number of Attributes
     * First byte would be attribute ID ( 4 octets )of first attribute.
     * attribRspLen - total length of attribRsp
     */
    private void handleGetElementAttributes(byte[] address, byte numAttributes, int attribRspLen,
                                                  byte[] attribRsp, byte rsp_type)
    {
        Log.d(TAG,"handleGetElementAttributes ");
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if ((!mConnectedDevices.contains(device))||(attribRspLen <= 0))
            return;

        ByteBuffer bb = ByteBuffer.wrap(attribRsp, 0, attribRspLen);
        int attributeId = bb.getInt(0);
        Log.d(TAG," numAttrib " + numAttributes + " attribRspLen " + attribRspLen +
                  " rsp_type " + rsp_type + " attribId " + attributeId);
        if (rsp_type != AVRC_RSP_IMPL_STBL)
        {
            if (mHandler.hasMessages(GET_ELEMENT_ATTR_TIMEOUT_BASE + attributeId))
            {
                mHandler.removeMessages(GET_ELEMENT_ATTR_TIMEOUT_BASE + attributeId);
                Log.d(TAG," Timeout CMD dequeued ");
            }
            mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_PROCESS_ELEMENT_ATTRIBUTE,
                                                                  0, ATTRIBUTE_FETCH_SKIP));
            return;
        }
        Message msg = mHandler.obtainMessage(MESSAGE_PROCESS_ELEMENT_ATTRIBUTE, numAttributes,
                                                                  ATTRIBUTE_FETCH_CONTINUE, bb);
        mHandler.sendMessage(msg);
    }
    /*
     * playStatusRsp will be after Parameter Length Feild
     * first byte will be from Total Song Length
     */
    private void handleGetPlayStatus(byte[] address, int paramLen,
                                    byte[] playStatusRsp, byte rsp_type)
    {
        Log.d(TAG,"handleGetPlayStatus ");
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        if (mHandler.hasMessages(MESSAGE_CMD_TIMEOUT, MESSAGE_GET_PLAY_STATUS))
        {
            mHandler.removeMessages(MESSAGE_CMD_TIMEOUT, MESSAGE_GET_PLAY_STATUS);
            Log.d(TAG," Timeout CMD dequeued ");
        }
        if ((rsp_type!= AVRC_RSP_IMPL_STBL)||(paramLen <= 0)) {
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(playStatusRsp, 0, paramLen);
        Message msg = mHandler.obtainMessage(MESSAGE_PROCESS_PLAY_STATUS, 0, 0, bb);
        mHandler.sendMessage(msg);
    }
    private void handleSetAbsVolume(byte[] address, byte absVol)
    {
        Log.d(TAG,"handleSetAbsVolume ");
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        Message msg = mHandler.obtainMessage(MESSAGE_PROCESS_SET_ABS_VOL_CMD, absVol, 0);
        mHandler.sendMessage(msg);
    }
    private void handleRegisterNotificationAbsVol(byte[] address)
    {
        Log.d(TAG,"handleRegisterNotificationAbsVol ");
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        mHandler.sendEmptyMessage(MESSAGE_PROCESS_REGISTER_ABS_VOL_REQUEST);
    }
    private void handleSetPlayerApplicationResponse(byte[] address, byte rsp_type)
    {
        Log.d(TAG,"handleSetPlayerApplicationResponse rsp_type = "+ rsp_type);
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        if (mHandler.hasMessages(MESSAGE_CMD_TIMEOUT,
                    MESSAGE_SET_CURRENT_PLAYER_APPLICATION_SETTINGS))
        {
            mHandler.removeMessages(MESSAGE_CMD_TIMEOUT,
                    MESSAGE_SET_CURRENT_PLAYER_APPLICATION_SETTINGS);
            Log.d(TAG," Timeout CMD dequeued ");
        }
        /*
         * Send GetPlayerAppSetting if we do not support EVENT_PLAYER_APPLICATION_SETTINGS_CHANGED
         * Some Devices don't reply with Changed. So we have to send this command, though its a
         * overhead for other devices.
         */
         mHandler.sendEmptyMessage(MESSAGE_GET_CURRENT_PLAYER_APPLICATION_SETTINGS);
    }
    private native static void classInitNative();
    private native void initNative();
    private native void cleanupNative();
    private native boolean sendPassThroughCommandNative(byte[] address, int keyCode, int keyState);
    private native void getCapabilitiesNative(int capId);
    private native void listPlayerApplicationSettingAttributeNative();
    private native void listPlayerApplicationSettingValueNative(byte attribId);
    private native void getPlayerApplicationSettingValuesNative(byte numAttrib, byte[] attribIds);
    private native void setPlayerApplicationSettingValuesNative(byte numAttrib,
                                                  byte[] atttibIds, byte[]attribVal);
    private native void registerNotificationNative(byte eventId, int value);
    private native void getElementAttributeNative(byte numAttributes, int attribId);
    private native void getPlayStatusNative();
    private native void sendAbsVolRspNative(int absVol);
    private native void sendRegisterAbsVolRspNative(byte rspType, int absVol);
}
