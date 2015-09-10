/*
 * Copyright (c) 2015, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 * Copyright (C) 2014 Samsung System LSI
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
package com.android.bluetooth.map;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentFilter.MalformedMimeTypeException;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.Telephony;
import android.provider.Telephony.Mms;
import android.provider.Telephony.MmsSms;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Sms.Inbox;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Xml;
import android.text.TextUtils;
import android.text.format.Time;

import org.xmlpull.v1.XmlSerializer;

import com.android.bluetooth.map.BluetoothMapUtils.TYPE;
import com.android.bluetooth.map.BluetoothMapbMessageMime.MimePart;
import com.android.bluetooth.mapapi.BluetoothMapContract;
import com.android.bluetooth.mapapi.BluetoothMapEmailContract;
import com.android.bluetooth.mapapi.BluetoothMapContract.MessageColumns;
import com.google.android.mms.pdu.PduHeaders;
import android.database.sqlite.SQLiteException;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.obex.ResponseCodes;

@TargetApi(19)
public class BluetoothMapContentObserverEmail extends BluetoothMapContentObserver {
    private static final String TAG = "BluetoothMapContentObserverEmail";

    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = Log.isLoggable(BluetoothMapService.LOG_TAG, Log.VERBOSE);

    private static final String EVENT_TYPE_NEW              = "NewMessage";
    private static final String EVENT_TYPE_DELETE           = "MessageDeleted";
    private static final String EVENT_TYPE_REMOVED          = "MessageRemoved";
    private static final String EVENT_TYPE_SHIFT            = "MessageShift";
    private static final String EVENT_TYPE_DELEVERY_SUCCESS = "DeliverySuccess";
    private static final String EVENT_TYPE_SENDING_SUCCESS  = "SendingSuccess";
    private static final String EVENT_TYPE_SENDING_FAILURE  = "SendingFailure";
    private static final String EVENT_TYPE_DELIVERY_FAILURE = "DeliveryFailure";
    private static final String EVENT_TYPE_READ_STATUS      = "ReadStatusChanged";
    private static final String EVENT_TYPE_CONVERSATION     = "ConversationChanged";
    private static final String EVENT_TYPE_PRESENCE         = "ParticipantPresenceChanged";
    private static final String EVENT_TYPE_CHAT_STATE       = "ParticipantChatStateChanged";

    private static final long EVENT_FILTER_NEW_MESSAGE                  = 1L;
    private static final long EVENT_FILTER_MESSAGE_DELETED              = 1L<<1;
    private static final long EVENT_FILTER_MESSAGE_SHIFT                = 1L<<2;
    private static final long EVENT_FILTER_SENDING_SUCCESS              = 1L<<3;
    private static final long EVENT_FILTER_SENDING_FAILED               = 1L<<4;
    private static final long EVENT_FILTER_DELIVERY_SUCCESS             = 1L<<5;
    private static final long EVENT_FILTER_DELIVERY_FAILED              = 1L<<6;
    private static final long EVENT_FILTER_MEMORY_FULL                  = 1L<<7; // Unused
    private static final long EVENT_FILTER_MEMORY_AVAILABLE             = 1L<<8; // Unused
    private static final long EVENT_FILTER_READ_STATUS_CHANGED          = 1L<<9;
    private static final long EVENT_FILTER_CONVERSATION_CHANGED         = 1L<<10;
    private static final long EVENT_FILTER_PARTICIPANT_PRESENCE_CHANGED = 1L<<11;
    private static final long EVENT_FILTER_PARTICIPANT_CHATSTATE_CHANGED= 1L<<12;
    private static final long EVENT_FILTER_MESSAGE_REMOVED              = 1L<<13;

    // TODO: If we are requesting a large message from the network, on a slow connection
    //       20 seconds might not be enough... But then again 20 seconds is long for other
    //       cases.
    private static final long PROVIDER_ANR_TIMEOUT = 20 * DateUtils.SECOND_IN_MILLIS;

    private Context mContext;
    private ContentResolver mResolver;
    private ContentProviderClient mProviderClient = null;
    private BluetoothMnsObexClient mMnsClient;
    private BluetoothMapMasInstance mMasInstance = null;
    private int mMasId;
    private boolean mEnableSmsMms = false;
    private boolean mObserverRegistered = false;
    private BluetoothMapAccountItem mAccount;
    private String mAuthority = null;

    // Default supported feature bit mask is 0x1f
    private int mMapSupportedFeatures = BluetoothMapUtils.MAP_FEATURE_DEFAULT_BITMASK;
    // Default event report version is 1.0
    private int mMapEventReportVersion = BluetoothMapUtils.MAP_EVENT_REPORT_V10;

    private BluetoothMapFolderElement mFolders =
            new BluetoothMapFolderElement("DUMMY", null); // Will be set by the MAS when generated.
    private Uri mMessageUri = null;
    private Uri mContactUri = null;

    private boolean mTransmitEvents = true;

    /* To make the filter update atomic, we declare it volatile.
     * To avoid a penalty when using it, copy the value to a local
     * non-volatile variable when used more than once.
     * Actually we only ever use the lower 4 bytes of this variable,
     * hence we could manage without the volatile keyword, but as
     * we tend to copy ways of doing things, we better do it right:-) */
    private volatile long mEventFilter = 0xFFFFFFFFL;

    public static final int DELETED_THREAD_ID = -1;

    // X-Mms-Message-Type field types. These are from PduHeaders.java
    public static final int MESSAGE_TYPE_RETRIEVE_CONF = 0x84;

    // Text only MMS converted to SMS if sms parts less than or equal to defined count
    private static final int CONVERT_MMS_TO_SMS_PART_COUNT = 10;

    private TYPE mSmsType;

    private static final String ACTION_MESSAGE_DELIVERY =
            "com.android.bluetooth.BluetoothMapContentObserver.action.MESSAGE_DELIVERY";
    /*package*/ static final String ACTION_MESSAGE_SENT =
        "com.android.bluetooth.BluetoothMapContentObserver.action.MESSAGE_SENT";

    public static final String EXTRA_MESSAGE_SENT_HANDLE = "HANDLE";
    public static final String EXTRA_MESSAGE_SENT_RESULT = "result";
    public static final String EXTRA_MESSAGE_SENT_MSG_TYPE = "type";
    public static final String EXTRA_MESSAGE_SENT_URI = "uri";
    public static final String EXTRA_MESSAGE_SENT_RETRY = "retry";
    public static final String EXTRA_MESSAGE_SENT_TRANSPARENT = "transparent";
    public static final String EXTRA_MESSAGE_SENT_TIMESTAMP = "timestamp";

    static final String[] MSG_PROJECTION_SHORT = new String[] {
        BluetoothMapContract.MessageColumns._ID,
        BluetoothMapContract.MessageColumns.FOLDER_ID,
        BluetoothMapContract.MessageColumns.FLAG_READ
    };

    static final String[] MSG_PROJECTION_SHORT_EXT = new String[] {
        BluetoothMapContract.MessageColumns._ID,
        BluetoothMapContract.MessageColumns.FOLDER_ID,
        BluetoothMapContract.MessageColumns.FLAG_READ,
        BluetoothMapContract.MessageColumns.DATE,
        BluetoothMapContract.MessageColumns.SUBJECT,
        BluetoothMapContract.MessageColumns.FROM_LIST,
        BluetoothMapContract.MessageColumns.FLAG_HIGH_PRIORITY
    };

    static final String[] MSG_PROJECTION_SHORT_EXT2 = new String[] {
        BluetoothMapContract.MessageColumns._ID,
        BluetoothMapContract.MessageColumns.FOLDER_ID,
        BluetoothMapContract.MessageColumns.FLAG_READ,
        BluetoothMapContract.MessageColumns.DATE,
        BluetoothMapContract.MessageColumns.SUBJECT,
        BluetoothMapContract.MessageColumns.FROM_LIST,
        BluetoothMapContract.MessageColumns.FLAG_HIGH_PRIORITY,
        BluetoothMapContract.MessageColumns.THREAD_ID,
        BluetoothMapContract.MessageColumns.THREAD_NAME
    };
    private boolean mInitialized = false;

    public BluetoothMapContentObserverEmail(final Context context,
            BluetoothMnsObexClient mnsClient,
            BluetoothMapMasInstance masInstance,
            BluetoothMapAccountItem account,
            boolean enableSmsMms) throws RemoteException {
        super(context, mnsClient, masInstance, null, enableSmsMms);
        mContext = context;
        mResolver = mContext.getContentResolver();
        mAccount = account;
        mMasInstance = masInstance;
        mMasId = mMasInstance.getMasId();
        mMapSupportedFeatures = mMasInstance.getRemoteFeatureMask();
        if (D) Log.d(TAG, "BluetoothMapContentObserverEmail: Supported features " +
                Integer.toHexString(mMapSupportedFeatures) ) ;

        if((BluetoothMapUtils.MAP_FEATURE_EXTENDED_EVENT_REPORT_11_BIT
                & mMapSupportedFeatures) != 0){
            mMapEventReportVersion = BluetoothMapUtils.MAP_EVENT_REPORT_V11;
        }
        // Make sure support for all formats result in latest version returned
        if((BluetoothMapUtils.MAP_FEATURE_EVENT_REPORT_V12_BIT
                & mMapSupportedFeatures) != 0){
            mMapEventReportVersion = BluetoothMapUtils.MAP_EVENT_REPORT_V12;
        }

        if(account != null) {
            mAuthority = Uri.parse(account.mBase_uri).getAuthority();
            Log.d(TAG,"mAuthority: "+mAuthority);
            if (mAccount.getType() == TYPE.EMAIL ) {
                mMessageUri =  BluetoothMapEmailContract
                                  .buildEmailMessageUri(BluetoothMapEmailContract.EMAIL_AUTHORITY);
            }
            Log.d(TAG,"mMessageUri: "+mMessageUri.toString());
            // TODO: We need to release this again!
            mProviderClient = mResolver.acquireUnstableContentProviderClient(mAuthority);
            if (mProviderClient == null) {
                throw new RemoteException("Failed to acquire provider for " + mAuthority);
            }
            mProviderClient.setDetectNotResponding(PROVIDER_ANR_TIMEOUT);
            mMsgListMsg = mMasInstance.getMsgListMsg();
            if(mMsgListMsg == null) {
                setMsgListMsg(new HashMap<Long, Msg>(), false);
                initMsgList();
            }
        }
        mEnableSmsMms = enableSmsMms;
        mMnsClient = mnsClient;
     }

    private Map<Long, Msg> getMsgListMsg() {
        return mMsgListMsg;
    }

    private void setMsgListMsg(Map<Long, Msg> msgListMsg, boolean changesDetected) {
        mMsgListMsg = msgListMsg;
        if(changesDetected) {
            mMasInstance.updateFolderVersionCounter();
        }
        mMasInstance.setMsgListMsg(msgListMsg);
    }

    public int getObserverRemoteFeatureMask() {
        if (V) Log.v(TAG, "getObserverRemoteFeatureMask Email: " + mMapEventReportVersion
            + " mMapSupportedFeatures Email: " + mMapSupportedFeatures);
        return mMapSupportedFeatures;
    }

    public void setObserverRemoteFeatureMask( int remoteSupportedFeatures) {
        mMapSupportedFeatures = remoteSupportedFeatures;
        if ((BluetoothMapUtils.MAP_FEATURE_EXTENDED_EVENT_REPORT_11_BIT
                & mMapSupportedFeatures) != 0) {
            mMapEventReportVersion = BluetoothMapUtils.MAP_EVENT_REPORT_V11;
        }
        // Make sure support for all formats result in latest version returned
        if ((BluetoothMapUtils.MAP_FEATURE_EVENT_REPORT_V12_BIT
                & mMapSupportedFeatures) != 0) {
            mMapEventReportVersion = BluetoothMapUtils.MAP_EVENT_REPORT_V12;
        }
        if (V) Log.d(TAG, "setObserverRemoteFeatureMask Email: " + mMapEventReportVersion
            + " mMapSupportedFeatures Email: " + mMapSupportedFeatures);
    }

    private static boolean sendEventNewMessage(long eventFilter) {
        return ((eventFilter & EVENT_FILTER_NEW_MESSAGE) > 0);
    }

    private static boolean sendEventMessageDeleted(long eventFilter) {
        return ((eventFilter & EVENT_FILTER_MESSAGE_DELETED) > 0);
    }

    private static boolean sendEventMessageShift(long eventFilter) {
        return ((eventFilter & EVENT_FILTER_MESSAGE_SHIFT) > 0);
    }

    private static boolean sendEventSendingSuccess(long eventFilter) {
        return ((eventFilter & EVENT_FILTER_SENDING_SUCCESS) > 0);
    }

    private static boolean sendEventSendingFailed(long eventFilter) {
        return ((eventFilter & EVENT_FILTER_SENDING_FAILED) > 0);
    }

    private static boolean sendEventDeliverySuccess(long eventFilter) {
        return ((eventFilter & EVENT_FILTER_DELIVERY_SUCCESS) > 0);
    }

    private static boolean sendEventDeliveryFailed(long eventFilter) {
        return ((eventFilter & EVENT_FILTER_DELIVERY_FAILED) > 0);
    }

    private static boolean sendEventReadStatusChanged(long eventFilter) {
        return ((eventFilter & EVENT_FILTER_READ_STATUS_CHANGED) > 0);
    }

    private static boolean sendEventMessageRemoved(long eventFilter) {
        return ((eventFilter & EVENT_FILTER_MESSAGE_REMOVED) > 0);
    }

    private final ContentObserver mObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if(uri == null) {
                Log.w(TAG, "onChange() with URI == null - not handled.");
                return;
            }
            if (V) Log.d(TAG, "onChange on thread: " + Thread.currentThread().getId()
                    + " Uri: " + uri.toString() + " selfchange: " + selfChange);

                handleMsgListChanges(uri);
        }
    };

    /**
     * Set the folder structure to be used for this instance.
     * @param folderStructure
     */
    public void setFolderStructure(BluetoothMapFolderElement folderStructure) {
        this.mFolders = folderStructure;
    }
    private Map<Long, Msg> mMsgListMsg = null;


    @Override
    public void registerObserver() throws RemoteException {
        if (V) Log.d(TAG, "registerObserver");

        if (mObserverRegistered)
            return;

        Uri EMAIL_URI = BluetoothMapEmailContract.buildEmailAccountUriWithId(mAuthority,
                            Long.toString(mAccount.getAccountId()));
        if (V) Log.d(TAG, "registerObserver EMAIL_URI: "+ EMAIL_URI.toString());
        if (mAccount!= null && mAccount.getType() == TYPE.EMAIL) {
            mProviderClient = mResolver.acquireUnstableContentProviderClient(mAuthority);
            if (mProviderClient == null) {
                throw new RemoteException("Failed to acquire provider for " + mAuthority);
            }
            mProviderClient.setDetectNotResponding(PROVIDER_ANR_TIMEOUT);
            initMsgList();
            try {
                mResolver.registerContentObserver(EMAIL_URI, false, mObserver);
                mObserverRegistered = true;
            } catch (SQLiteException e) {
                Log.e(TAG, "SQLite exception: " + e);
            }
        }
    }
    public void unregisterObserver() {
        if (V) Log.d(TAG, "unregisterObserver");
        mResolver.unregisterContentObserver(mObserver);
        mObserverRegistered = false;
        if(mProviderClient != null){
            mProviderClient.release();
            mProviderClient = null;
        }
    }

    private void sendEvent(Event evt) {

        if(mTransmitEvents == false) {
            if(V) Log.v(TAG, "mTransmitEvents == false - don't send event.");
            return;
        }

        if(D)Log.d(TAG, "sendEvent: " + evt.eventType + " " + evt.handle + " " + evt.folder + " "
                + evt.oldFolder + " " + evt.msgType.name() + " " + evt.datetime + " "
                + evt.subject + " " + evt.senderName + " " + evt.priority );

        if (mMnsClient == null || mMnsClient.isConnected() == false) {
            Log.d(TAG, "sendEvent: No MNS client registered or connected- don't send event");
            return;
        }

        /* Enable use of the cache for checking the filter */
        long eventFilter = mEventFilter;

        /* This should have been a switch on the string, but it is not allowed in Java 1.6 */
        /* WARNING: Here we do pointer compare for the string to speed up things, that is.
         * HENCE: always use the EVENT_TYPE_"defines" */
        if(evt.eventType == EVENT_TYPE_NEW) {
            if(!sendEventNewMessage(eventFilter)) {
                if(D)Log.d(TAG, "Skip sending event of type: " + evt.eventType);
                return;
            }
        } else if(evt.eventType == EVENT_TYPE_DELETE) {
            if(!sendEventMessageDeleted(eventFilter)) {
                if(D)Log.d(TAG, "Skip sending event of type: " + evt.eventType);
                return;
            }
        } else if(evt.eventType == EVENT_TYPE_REMOVED) {
            if(!sendEventMessageRemoved(eventFilter)) {
                if(D)Log.d(TAG, "Skip sending event of type: " + evt.eventType);
                return;
            }
        } else if(evt.eventType == EVENT_TYPE_SHIFT) {
            if(!sendEventMessageShift(eventFilter)) {
                if(D)Log.d(TAG, "Skip sending event of type: " + evt.eventType);
                return;
            }
        } else if(evt.eventType == EVENT_TYPE_DELEVERY_SUCCESS) {
            if(!sendEventDeliverySuccess(eventFilter)) {
                if(D)Log.d(TAG, "Skip sending event of type: " + evt.eventType);
                return;
            }
        } else if(evt.eventType == EVENT_TYPE_SENDING_SUCCESS) {
            if(!sendEventSendingSuccess(eventFilter)) {
                if(D)Log.d(TAG, "Skip sending event of type: " + evt.eventType);
                return;
            }
        } else if(evt.eventType == EVENT_TYPE_SENDING_FAILURE) {
            if(!sendEventSendingFailed(eventFilter)) {
                if(D)Log.d(TAG, "Skip sending event of type: " + evt.eventType);
                return;
            }
        } else if(evt.eventType == EVENT_TYPE_DELIVERY_FAILURE) {
            if(!sendEventDeliveryFailed(eventFilter)) {
                if(D)Log.d(TAG, "Skip sending event of type: " + evt.eventType);
                return;
            }
        } else if(evt.eventType == EVENT_TYPE_READ_STATUS) {
            if(!sendEventReadStatusChanged(eventFilter)) {
                if(D)Log.d(TAG, "Skip sending event of type: " + evt.eventType);
                return;
            }
       }

        try {
            mMnsClient.sendEvent(evt.encode(), mMasId);
        } catch (UnsupportedEncodingException ex) {
            /* do nothing */
            if (D) Log.e(TAG, "Exception - should not happen: ",ex);
        }
    }

    private void initMsgList() throws RemoteException {
        if (V) Log.d(TAG, "initMsgList");

        if(mAccount != null) {
            HashMap<Long, Msg> msgList = new HashMap<Long, Msg>();
            Uri uri = mMessageUri;
            String where = BluetoothMapEmailContract.ExtEmailMessageColumns.ACCOUNT_KEY + "="
                               + mAccount.getAccountId();
            Cursor c = mProviderClient.query(uri, BluetoothMapEmailContract
                            .BT_EMAIL_MSG_PROJECTION_SHORT, where, null, null);

            try {
                if (c != null && c.moveToFirst()) {
                    do {
                        long id = c.getLong(c.getColumnIndex(BluetoothMapEmailContract
                                    .ExtEmailMessageColumns.RECORD_ID));
                        long folderId = c.getInt(c.getColumnIndex(
                                BluetoothMapEmailContract.ExtEmailMessageColumns.MAILBOX_KEY));
                        int readFlag = c.getInt(c.getColumnIndex(
                                BluetoothMapEmailContract.ExtEmailMessageColumns.EMAIL_FLAG_READ));
                        Msg msg = new Msg(id, folderId, readFlag);
                        msgList.put(id, msg);
                    } while (c.moveToNext());
                }
            } finally {
                if (c != null) c.close();
            }

            synchronized(getMsgListMsg()) {
                getMsgListMsg().clear();
                setMsgListMsg(msgList, true);
            }
        }
    }

    private void handleMsgListChangesMsg(Uri uri)  throws RemoteException{

        // TODO: Change observer to handle accountId and message ID if present

        HashMap<Long, Msg> msgList = new HashMap<Long, Msg>();
        if(mAccount == null) {
            return;
        }
        Cursor c;
        String where = BluetoothMapEmailContract.ExtEmailMessageColumns.ACCOUNT_KEY + "="
                           + mAccount.getAccountId();
        boolean listChanged = false;
        if (V) Log.d(TAG, "handleMsgListChangesMsg Email: " + mMapEventReportVersion
            + "mMapSupportedFeatures Email: " + mMapSupportedFeatures);
        if (mMapEventReportVersion == BluetoothMapUtils.MAP_EVENT_REPORT_V10) {
            c = mProviderClient.query(mMessageUri, BluetoothMapEmailContract
                               .BT_EMAIL_MSG_PROJECTION_SHORT, where, null, null);
        } else if (mMapEventReportVersion == BluetoothMapUtils.MAP_EVENT_REPORT_V11) {
            c = mProviderClient.query(mMessageUri, BluetoothMapEmailContract
                                .BT_EMAIL_MSG_PROJECTION_SHORT_EXT, where, null, null);
        } else {
            c = mProviderClient.query(mMessageUri, BluetoothMapEmailContract
                               .BT_EMAIL_MESSAGE_PROJECTION, where, null, null);
        }
        if (V) Log.v(TAG, "handleMsgListChangesMsg uri: " + uri.toString());
        if (V) Log.v(TAG, "handleMsgListChangesMsg where: " + where);
        synchronized(getMsgListMsg()) {
            try {
                if (c != null && c.moveToFirst()) {
                    do {
                        long id = c.getLong(c.getColumnIndex(
                                BluetoothMapEmailContract.ExtEmailMessageColumns.RECORD_ID));
                        int folderId = c.getInt(c.getColumnIndex(
                                BluetoothMapEmailContract.ExtEmailMessageColumns.MAILBOX_KEY));
                        int readFlag = c.getInt(c.getColumnIndex(BluetoothMapEmailContract
                                        .ExtEmailMessageColumns.EMAIL_FLAG_READ));
                        Msg msg = getMsgListMsg().remove(id);
                        BluetoothMapFolderElement folderElement = mFolders.getFolderById(folderId);
                        String newFolder;
                        if(folderElement != null) {
                            newFolder = folderElement.getFullPath();
                        } else {
                            // This can happen if a new folder is created while connected
                            newFolder = "unknown";
                        }
                        /* We must filter out any actions made by the MCE, hence do not send e.g.
                         * a message deleted and/or MessageShift for messages deleted by the MCE. */
                        if (msg == null) {
                            if(V) Log.v(TAG, "handleMsgListChangesMsg id: " + id + "folderId: "
                                + folderId + " newFolder: " +newFolder);
                            listChanged = true;
                            /* New message - created with message unread */
                            msg = new Msg(id, folderId, readFlag);
                            msgList.put(id, msg);
                            Event evt;
                            /* Incoming message from the network */
                            if (mMapEventReportVersion == BluetoothMapUtils.MAP_EVENT_REPORT_V11) {
                                String date = BluetoothMapUtils.getDateTimeString(c.getLong(c
                                    .getColumnIndex(BluetoothMapEmailContract
                                    .ExtEmailMessageColumns.TIMESTAMP)));
                                String subject = c.getString(c.getColumnIndex(
                                        BluetoothMapContract.MessageColumns.SUBJECT));
                                String address = c.getString(
                                        c.getColumnIndex(BluetoothMapEmailContract
                                        .ExtEmailMessageColumns.EMAIL_FROM_LIST));
                                evt = new Event(EVENT_TYPE_NEW, id, newFolder,
                                            mAccount.getType(), date, subject, address, "no");
                            } else {
                                evt = new Event(EVENT_TYPE_NEW, id, newFolder, null, TYPE.EMAIL);
                            }
                            sendEvent(evt);
                        } else {
                            if(V) Log.v(TAG, "handleMsgListChangesMsg id: " + id + "folderId: "
                                    + folderId + " newFolder: " +newFolder + "oldFolder: "
                                    + msg.folderId);
                            /* Existing message */
                            if (folderId != msg.folderId && msg.folderId != -1) {
                                BluetoothMapFolderElement oldFolderElement =
                                        mFolders.getFolderById(msg.folderId);
                                String oldFolder;
                                listChanged = true;
                                if(oldFolderElement != null) {
                                    oldFolder = oldFolderElement.getFullPath();
                                } else {
                                    // This can happen if a new folder is created while connected
                                    oldFolder = "unknown";
                                }
                                BluetoothMapFolderElement deletedFolder =
                                        mFolders.getFolderByName(
                                                BluetoothMapContract.FOLDER_NAME_DELETED);
                                BluetoothMapFolderElement sentFolder =
                                        mFolders.getFolderByName(
                                                BluetoothMapContract.FOLDER_NAME_SENT);
                                /*
                                 *  If the folder is now 'deleted', send a deleted-event in stead of
                                 *  a shift or if message is sent initiated by MAP Client, then send
                                 *  sending-success otherwise send folderShift
                                 */
                                if(deletedFolder != null && deletedFolder.getFolderId()
                                        == folderId) {
                                    if(msg.localInitiatedShift == false ) {
                                    // "old_folder" used only for MessageShift event
                                    Event evt = new Event(EVENT_TYPE_DELETE, msg.id, oldFolder,
                                            null, mAccount.getType());
                                    sendEvent(evt);
                                    } else {
                                        if(V) Log.v(TAG, " Ignore MCE initiated Shift/Delete");
                                        msg.localInitiatedShift = false;
                                    }
                                } else if(sentFolder != null
                                        && sentFolder.getFolderId() == folderId
                                        && msg.localInitiatedSend == true) {
                                    if(msg.transparent) {
                                        mResolver.delete(
                                                ContentUris.withAppendedId(mMessageUri, id),
                                                null, null);
                                    } else {
                                        msg.localInitiatedSend = false;
                                        //Send both MessageShift and SendingSucess for pushMsg case
                                        Event evt_shift = new Event(EVENT_TYPE_SHIFT, msg.id,
                                                newFolder, oldFolder, mAccount.getType());
                                        sendEvent(evt_shift);
                                        Event evt_send = new Event(EVENT_TYPE_SENDING_SUCCESS,
                                                msg.id, newFolder, null, mAccount.getType());
                                        sendEvent(evt_send);
                                    }
                                } else {
                                    if (!oldFolder.equalsIgnoreCase("root")) {
                                        if(msg.localInitiatedShift == false) {
                                            Event evt = new Event(EVENT_TYPE_SHIFT, id, newFolder,
                                                    oldFolder, mAccount.getType());
                                            sendEvent(evt);
                                        } else {
                                            if(V) Log.v(TAG, " Ignore MCE initiated shift");
                                            msg.localInitiatedShift = false;
                                        }
                                    }
                                }
                                msg.folderId = folderId;
                            }
                            if(readFlag != msg.flagRead) {
                                listChanged = true;

                                if (mMapEventReportVersion >
                                BluetoothMapUtils.MAP_EVENT_REPORT_V10) {
                                    if(msg.localInitiatedReadStatus == false) {
                                    Event evt = new Event(EVENT_TYPE_READ_STATUS, id, newFolder,
                                            mAccount.getType());
                                    sendEvent(evt);
                                    } else {
                                        if(V) Log.v(TAG, " Ignore MCE initiated ReadStatus change");
                                        msg.localInitiatedReadStatus = false;
                                    }
                                    msg.flagRead = readFlag;
                                }
                            }

                            msgList.put(id, msg);
                        }
                    } while (c.moveToNext());
                }
            } finally {
                if (c != null) c.close();
            }
            // For all messages no longer in the database send a delete notification
            for (Msg msg : getMsgListMsg().values()) {
                BluetoothMapFolderElement oldFolderElement = mFolders.getFolderById(msg.folderId);
                String oldFolder;
                listChanged = true;
                if(oldFolderElement != null) {
                    oldFolder = oldFolderElement.getFullPath();
                } else {
                    oldFolder = "unknown";
                }
                /* Some e-mail clients delete the message after sending, and creates a
                 * new message in sent. We cannot track the message anymore, hence send both a
                 * send success and delete message.
                 */
                if(msg.localInitiatedSend == true) {
                    msg.localInitiatedSend = false;
                    // If message is send with transparency don't set folder as message is deleted
                    if (msg.transparent)
                        oldFolder = null;
                    Event evt = new Event(EVENT_TYPE_SENDING_SUCCESS, msg.id, oldFolder, null,
                            mAccount.getType());
                    sendEvent(evt);
                }
                /* As this message deleted is only send on a real delete - don't set folder.
                 *  - only send delete event if message is not sent with transparency
                 */
                if (!msg.transparent ) {
                    if(msg.localInitiatedShift == false ) {
                        // "old_folder" used only for MessageShift event
                        Event evt = new Event(EVENT_TYPE_DELETE, msg.id, oldFolder,
                            null, mAccount.getType());
                        sendEvent(evt);
                    } else {
                        if(V) Log.v(TAG, " Ignore MCE initiated shift/delete");
                        msg.localInitiatedShift = false;
                    }
                }
            }
            setMsgListMsg(msgList, listChanged);
        }
    }

    private void handleMsgListChanges(Uri uri) {
        if(uri.getAuthority().equals(mAuthority)) {
            try {
                if(D) Log.d(TAG, "handleMsgListChanges: account type = " +
                    mAccount.getType().toString() + "account Id: "+
                    mAccount.getAccountId() + "masID: " + mMasId);
                handleMsgListChangesMsg(uri);
            } catch(RemoteException e) {
                mMasInstance.restartObexServerSession();
                Log.w(TAG, "Problems contacting the ContentProvider in mas Instance "
                        + mMasId + " restaring ObexServerSession");
            }

        }
    }
 @Override
    /**
     *
     * @param handle
     * @param type
     * @param mCurrentFolder
     * @param uriStr
     * @param statusValue
     * @return true is success
     */
    public boolean setMessageStatusDeleted(long handle, TYPE type,
            BluetoothMapFolderElement mCurrentFolder, String uriStr, int statusValue) {
           if (D) Log.d(TAG, "setMessageStatusDeleted: EMAIL handle " + handle
               + " type " + type + " value " + statusValue + " URI: " +uriStr);
        boolean res = false;
        long accountId = mAccount.getAccountId();
        Uri uri = Uri.withAppendedPath(mMessageUri, Long.toString(handle));
        Log.d(TAG,"URI print: " + uri.toString());
        Cursor crEmail = mResolver.query(uri, null, null, null, null);

        if (crEmail != null && crEmail.moveToFirst()) {
           if (V) Log.d(TAG, "setMessageStatusDeleted: EMAIL handle " + handle
               + " type " + type + " value " + statusValue + "accountId: "+accountId);
           Intent emailIn = new Intent();
           Msg msg = null;
           synchronized(getMsgListMsg()) {
               msg = getMsgListMsg().get(handle);
           }
           if (statusValue == BluetoothMapAppParams.STATUS_VALUE_YES) {
              emailIn.setAction(BluetoothMapEmailContract.ACTION_DELETE_MESSAGE);
           } else {
              emailIn.setAction(BluetoothMapEmailContract.ACTION_MOVE_MESSAGE);
              //Undelete - Move the message to Inbox
              long folderId = -1;
              BluetoothMapFolderElement inboxFolder = mCurrentFolder
                      .getFolderByName(BluetoothMapContract.FOLDER_NAME_INBOX);
              if(folderId == -1) folderId = BluetoothMapEmailContract.TYPE_INBOX;
              emailIn.putExtra(BluetoothMapEmailContract.EXTRA_MESSAGE_INFO, folderId);
           }
           emailIn.putExtra(BluetoothMapEmailContract.EXTRA_ACCOUNT, accountId);
           emailIn.putExtra(BluetoothMapEmailContract.EXTRA_MESSAGE_ID, handle);
           mContext.sendBroadcast(emailIn);
           res = true;
           //Mark local initiated message delete to avoid notification
           if (msg != null) msg.localInitiatedShift = true;
        } else {
           if(V) Log.v(TAG,"Returning from setMessage Status Deleted");
        }
        if (crEmail != null) {
            crEmail.close();
        }
        if(V) Log.d(TAG, " END setMessageStatusDeleted: EMAIL handle " + handle + " type " + type
               + " value " + statusValue + "accountId: "+accountId);
        return res;

    }
    /**
     *
     * @param handle
     * @param type
     * @param uriStr
     * @param statusValue
     * @return true at success
     */
    @Override
    public boolean setMessageStatusRead(long handle, TYPE type, String uriStr, int statusValue)
            throws RemoteException{
       boolean res = true;

        Intent emailIn = new Intent();
        long accountId = mAccount.getAccountId();
        Msg msg = null;
        synchronized(getMsgListMsg()) {
            msg = getMsgListMsg().get(handle);
        }
        if (D) Log.d(TAG, "setMessageStatusRead: EMAIL handle " + handle
            + " type " + type + " value " + statusValue+ "accounId: " +accountId);
         emailIn.setAction(BluetoothMapEmailContract.ACTION_MESSAGE_READ);
         emailIn.putExtra(BluetoothMapEmailContract.EXTRA_MESSAGE_INFO,statusValue);
         emailIn.putExtra(BluetoothMapEmailContract.EXTRA_ACCOUNT, accountId);
         emailIn.putExtra(BluetoothMapEmailContract.EXTRA_MESSAGE_ID, handle);
         //Mark local initiage message status change to avoid notification
         if (msg != null ) msg.localInitiatedReadStatus = true;
         mContext.sendBroadcast(emailIn);
        return res;

    }

    private class PushMsgInfo {
        long id;
        int transparent;
        int retry;
        String phone;
        Uri uri;
        long timestamp;
        int parts;
        int partsSent;
        int partsDelivered;
        boolean resend;
        boolean sendInProgress;
        boolean failedSent; // Set to true if a single part sent fail is received.
        int statusDelivered; // Set to != 0 if a single part deliver fail is received.

        public PushMsgInfo(long id, int transparent,
                int retry, String phone, Uri uri) {
            this.id = id;
            this.transparent = transparent;
            this.retry = retry;
            this.phone = phone;
            this.uri = uri;
            this.resend = false;
            this.sendInProgress = false;
            this.failedSent = false;
            this.statusDelivered = 0; /* Assume success */
            this.timestamp = 0;
        };
    }

    private Map<Long, PushMsgInfo> mPushMsgList =
            Collections.synchronizedMap(new HashMap<Long, PushMsgInfo>());
    @Override
    public long pushMessage(BluetoothMapbMessage msg, BluetoothMapFolderElement folderElement,
            BluetoothMapAppParams ap, String emailBaseUri) throws IllegalArgumentException,
            RemoteException, IOException {
        if (D) Log.d(TAG, "pushMessage emailBaseUri: "+emailBaseUri);
        ArrayList<BluetoothMapbMessage.vCard> recipientList = msg.getRecipients();
        ArrayList<BluetoothMapbMessage.vCard> originatorList = msg.getOriginators();
        int transparent = (ap.getTransparent() == BluetoothMapAppParams.INVALID_VALUE_PARAMETER) ?
                0 : ap.getTransparent();
        int retry = ap.getRetry();
        int charset = ap.getCharset();
        long handle = -1;
        long folderId = -1;
        if (recipientList == null) {
            if (D) Log.d(TAG, "empty recipient list");
            return -1;
        }
        if ( msg.getType().equals(TYPE.EMAIL) ) {
            String folder = folderElement.getName();
            if (folder != null && !(folder.equalsIgnoreCase(BluetoothMapContract.FOLDER_NAME_OUTBOX)
                 ||  folder.equalsIgnoreCase(BluetoothMapEmailContract.FOLDER_NAME_DRAFTS)
                 ||  folder.equalsIgnoreCase(BluetoothMapContract.FOLDER_NAME_DRAFT))) {
                 /* not allowed to push mms to anything but outbox/draft */
                 throw  new IllegalArgumentException("Cannot push message to other " +
                         "folders than outbox/draft");
            }
            /// Write the message to the database
            String msgBody = ((BluetoothMapbMessageExtEmail)msg).getEmailBody();
            if (V) {
                int length = msgBody.length();
                Log.v(TAG, "pushMessage: message string length = " + length);
                String messages[] = msgBody.split("\r\n");
                Log.v(TAG, "pushMessage: messages count=" + messages.length);
                for(int i = 0; i < messages.length; i++) {
                    Log.v(TAG, "part " + i + ":" + messages[i]);
                }
            }
            String toAddress[] = null;
            for (BluetoothMapbMessage.vCard recipient : recipientList) {
                if(recipient.getEnvLevel() == 0) // Only send the message to the top level recipient
                    toAddress = ((BluetoothMapbMessage.vCard)recipient).getEmailAddresses();
                Uri uriInsert = BluetoothMapEmailContract
                        .buildEmailMessageUri(BluetoothMapEmailContract.EMAIL_AUTHORITY);
                if (D) Log.d(TAG, "pushMessage - uriInsert= " + uriInsert.toString() +
                        ", intoFolder id=" + folderElement.getFolderId());
                synchronized(getMsgListMsg()) {
                    // Now insert the empty message into folder
                    ContentValues values = new ContentValues();
                    Time timeObj = new Time();
                    timeObj.setToNow();
                    folderId = folderElement.getFolderId();
                    values.put(BluetoothMapEmailContract.ExtEmailMessageColumns.MAILBOX_KEY,
                            folderId);
                    if(((BluetoothMapbMessageExtEmail)msg).getSubject() != null) {
                        values.put(BluetoothMapContract.MessageColumns.SUBJECT,
                                ((BluetoothMapbMessageExtEmail)msg).getSubject());
                    } else {
                        values.put(BluetoothMapContract.MessageColumns.SUBJECT, "");
                    }
                    values.put("syncServerTimeStamp", 0);
                    values.put("syncServerId", "5:65");
                    values.put("timeStamp", timeObj.toMillis(false));
                    values.put("flagLoaded", "1");
                    values.put("flagFavorite", "0");
                    values.put("flagAttachment", "0");
                    if(folderElement.getName().equalsIgnoreCase(BluetoothMapContract
                        .FOLDER_NAME_DRAFT) || folderElement.getName()
                            .equalsIgnoreCase(BluetoothMapEmailContract.FOLDER_NAME_DRAFTS)) {
                        values.put("flags", "1179648");
                    } else
                        values.put("flags", "0");
                    String splitStr[] = emailBaseUri.split("/");
                    for (String str: splitStr)
                        Log.d(TAG,"seg for mBaseUri: "+ str);
                    if (mAccount != null) {
                        values.put("accountKey", mAccount.getAccountId());
                        values.put("displayName", mAccount.getDisplayName());
                        values.put("fromList", mAccount.getEmailAddress());
                    }
                    values.put("mailboxKey", folderId);
                    StringBuilder address = new StringBuilder();
                    for (String  s : toAddress) {
                        address.append(s);
                        address.append(";");
                    }
                    values.put("toList", address.toString().trim());
                    values.put("flagRead", 0);
                    Uri uriNew = mProviderClient.insert(uriInsert, values);
                    if (D) Log.d(TAG, "pushMessage - uriNew= " + uriNew.toString());
                    handle =  Long.parseLong(uriNew.getLastPathSegment());
                    if (V) {
                        Log.v(TAG, " NEW HANDLE " + handle);
                    }
                    if(handle == -1) {
                       Log.v(TAG, " Inavlid Handle ");
                       return -1;
                    }
                    //Insert msgBody in DB Provider BODY TABLE
                    ContentValues valuesBody = new ContentValues();
                    valuesBody.put("messageKey", String.valueOf(handle));
                    valuesBody.put("textContent", msgBody);
                    Uri uriMsgBdyInsert = BluetoothMapEmailContract
                            .buildEmailMessageBodyUri(BluetoothMapEmailContract.EMAIL_AUTHORITY);
                    Log.d(TAG, "pushMessage - uriMsgBdyInsert = " + uriMsgBdyInsert.toString());
                    mProviderClient.insert(uriMsgBdyInsert, valuesBody);
                    // Extract the data for the inserted message, and store in local mirror, to
                    // avoid sending a NewMessage Event.
                    //TODO: We need to add the new 1.1 parameter as well:-) e.g. read
                    Msg newMsg = new Msg(handle, folderId, 1); // TODO: Create define for read-state
                    newMsg.transparent = (transparent == 1) ? true : false;
                    newMsg.localInitiatedSend = true;
                    if ( folderId == folderElement.getFolderByName(
                        BluetoothMapContract.FOLDER_NAME_OUTBOX).getFolderId() ) {
                        //Trigger Email App to send the message over network.
                        Intent emailIn = new Intent();
                        long accountId = mAccount.getAccountId();
                        if(V) Log.d(TAG, "sendIntent SEND: " + handle + "accounId: " +accountId);
                        emailIn.setAction(BluetoothMapEmailContract.ACTION_SEND_PENDING_MAIL);
                        emailIn.putExtra(BluetoothMapEmailContract.EXTRA_ACCOUNT, accountId);
                        mContext.sendBroadcast(emailIn);
                    }
                    getMsgListMsg().put(handle, newMsg);
                }
            }
        }
        // If multiple recipients return handle of last
        return handle;
    }

    public void init() {
        mInitialized = true;
    }

    public void deinit() {
        mInitialized = false;
        unregisterObserver();
        //failPendingMessages();
        //removeDeletedMessages();
    }
}
