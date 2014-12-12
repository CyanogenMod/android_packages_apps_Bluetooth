/*
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

import java.io.Closeable;
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

import org.xmlpull.v1.XmlSerializer;

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
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.BaseColumns;
import com.android.bluetooth.mapapi.BluetoothMapContract;
import com.android.bluetooth.mapapi.BluetoothMapContract.MessageColumns;
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
import android.os.Looper;

import com.android.bluetooth.map.BluetoothMapUtils.TYPE;
import com.android.bluetooth.map.BluetoothMapbMessageMms.MimePart;
import com.google.android.mms.pdu.PduHeaders;

public class BluetoothMapContentObserver {
    private static final String TAG = "BluetoothMapContentObserver";

    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = BluetoothMapService.VERBOSE;

    private static final String EVENT_TYPE_DELETE = "MessageDeleted";
    private static final String EVENT_TYPE_SHIFT  = "MessageShift";
    private static final String EVENT_TYPE_NEW    = "NewMessage";
    private static final String EVENT_TYPE_DELEVERY_SUCCESS = "DeliverySuccess";
    private static final String EVENT_TYPE_SENDING_SUCCESS  = "SendingSuccess";
    private static final String EVENT_TYPE_SENDING_FAILURE  = "SendingFailure";
    private static final String EVENT_TYPE_DELIVERY_FAILURE = "DeliveryFailure";


    private static final long PROVIDER_ANR_TIMEOUT = 20 * DateUtils.SECOND_IN_MILLIS;

    private Context mContext;
    private ContentResolver mResolver;
    private ContentProviderClient mProviderClient = null;
    private BluetoothMnsObexClient mMnsClient;
    private BluetoothMapMasInstance mMasInstance = null;
    private int mMasId;
    private boolean mEnableSmsMms = false;
    private boolean mObserverRegistered = false;
    private BluetoothMapEmailSettingsItem mAccount;
    private String mAuthority = null;

    private BluetoothMapFolderElement mFolders =
            new BluetoothMapFolderElement("DUMMY", null); // Will be set by the MAS when generated.
    private Uri mMessageUri = null;

    public static final int DELETED_THREAD_ID = -1;

    // X-Mms-Message-Type field types. These are from PduHeaders.java
    public static final int MESSAGE_TYPE_RETRIEVE_CONF = 0x84;

    // Text only MMS converted to SMS if sms parts less than or equal to defined count
    private static final int CONVERT_MMS_TO_SMS_PART_COUNT = 10;

    private TYPE mSmsType;

    private static void close(Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException e) {
        }
    }

    static final String[] SMS_PROJECTION = new String[] {
        Sms._ID,
        Sms.THREAD_ID,
        Sms.ADDRESS,
        Sms.BODY,
        Sms.DATE,
        Sms.READ,
        Sms.TYPE,
        Sms.STATUS,
        Sms.LOCKED,
        Sms.ERROR_CODE
    };

    static final String[] SMS_PROJECTION_SHORT = new String[] {
        Sms._ID,
        Sms.THREAD_ID,
        Sms.TYPE
    };

    static final String[] MMS_PROJECTION_SHORT = new String[] {
        Mms._ID,
        Mms.THREAD_ID,
        Mms.MESSAGE_TYPE,
        Mms.MESSAGE_BOX
    };

    static final String[] EMAIL_PROJECTION_SHORT = new String[] {
        BluetoothMapContract.MessageColumns._ID,
        BluetoothMapContract.MessageColumns.FOLDER_ID,
        BluetoothMapContract.MessageColumns.FLAG_READ
    };


    public BluetoothMapContentObserver(final Context context,
                                       BluetoothMnsObexClient mnsClient,
                                       BluetoothMapMasInstance masInstance,
                                       BluetoothMapEmailSettingsItem account,
                                       boolean enableSmsMms) throws RemoteException {
        mContext = context;
        mResolver = mContext.getContentResolver();
        mAccount = account;
        mMasInstance = masInstance;
        mMasId = mMasInstance.getMasId();
        if(account != null) {
            mAuthority = Uri.parse(account.mBase_uri).getAuthority();
            mMessageUri = Uri.parse(account.mBase_uri + "/" + BluetoothMapContract.TABLE_MESSAGE);
            mProviderClient = mResolver.acquireUnstableContentProviderClient(mAuthority);
            if (mProviderClient == null) {
                throw new RemoteException("Failed to acquire provider for " + mAuthority);
            }
            mProviderClient.setDetectNotResponding(PROVIDER_ANR_TIMEOUT);
        }

        mEnableSmsMms = enableSmsMms;
        mSmsType = getSmsType();
        mMnsClient = mnsClient;
    }

    /**
     * Set the folder structure to be used for this instance.
     * @param folderStructure
     */
    public void setFolderStructure(BluetoothMapFolderElement folderStructure) {
        this.mFolders = folderStructure;
    }

    private TYPE getSmsType() {
        TYPE smsType = null;
        TelephonyManager tm = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);

        if (tm.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
            smsType = TYPE.SMS_GSM;
        } else if (tm.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
            smsType = TYPE.SMS_CDMA;
        }

        return smsType;
    }

    private final ContentObserver mObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (V) Log.d(TAG, "onChange on thread: " + Thread.currentThread().getId()
                + " Uri: " + uri.toString() + " selfchange: " + selfChange);

            handleMsgListChanges(uri);
        }
    };

    private static final String folderSms[] = {
        "",
        BluetoothMapContract.FOLDER_NAME_INBOX,
        BluetoothMapContract.FOLDER_NAME_SENT,
        BluetoothMapContract.FOLDER_NAME_DRAFT,
        BluetoothMapContract.FOLDER_NAME_OUTBOX,
        BluetoothMapContract.FOLDER_NAME_OUTBOX,
        BluetoothMapContract.FOLDER_NAME_OUTBOX,
        BluetoothMapContract.FOLDER_NAME_INBOX,
        BluetoothMapContract.FOLDER_NAME_INBOX,
    };

    private static final String folderMms[] = {
        "",
        BluetoothMapContract.FOLDER_NAME_INBOX,
        BluetoothMapContract.FOLDER_NAME_SENT,
        BluetoothMapContract.FOLDER_NAME_DRAFT,
        BluetoothMapContract.FOLDER_NAME_OUTBOX,
    };

    private class Event {
        String eventType;
        long handle;
        String folder;
        String oldFolder;
        TYPE msgType;

        final static String PATH = "telecom/msg/";

        public Event(String eventType, long handle, String folder,
            String oldFolder, TYPE msgType) {

            this.eventType = eventType;
            this.handle = handle;
            if (folder != null) {
                if(msgType == TYPE.EMAIL) {
                    this.folder = folder;
                } else {
                    this.folder = PATH + folder;
                }
            } else {
                this.folder = null;
            }
            if (oldFolder != null) {
                if(msgType == TYPE.EMAIL) {
                    this.oldFolder = oldFolder;
                } else {
                    this.oldFolder = PATH + oldFolder;
                }
            } else {
                this.oldFolder = null;
            }
            this.msgType = msgType;
        }

        public byte[] encode() throws UnsupportedEncodingException {
            StringWriter sw = new StringWriter();
            XmlSerializer xmlEvtReport = Xml.newSerializer();
            try {
                xmlEvtReport.setOutput(sw);
                xmlEvtReport.startDocument(null, null);
                xmlEvtReport.text("\r\n");
                xmlEvtReport.startTag("", "MAP-event-report");
                xmlEvtReport.attribute("", "version", "1.0");

                xmlEvtReport.startTag("", "event");
                xmlEvtReport.attribute("", "type", eventType);
                xmlEvtReport.attribute("", "handle", BluetoothMapUtils.getMapHandle(handle, msgType));
                if (folder != null) {
                    xmlEvtReport.attribute("", "folder", folder);
                }
                if (oldFolder != null) {
                    xmlEvtReport.attribute("", "old_folder", oldFolder);
                }
                xmlEvtReport.attribute("", "msg_type", msgType.name());
                xmlEvtReport.endTag("", "event");

                xmlEvtReport.endTag("", "MAP-event-report");
                xmlEvtReport.endDocument();
            } catch (IllegalArgumentException e) {
                if(D) Log.w(TAG,e);
            } catch (IllegalStateException e) {
                if(D) Log.w(TAG,e);
            } catch (IOException e) {
                if(D) Log.w(TAG,e);
            }

            if (V) Log.d(TAG, sw.toString());

            return sw.toString().getBytes("UTF-8");
        }
    }

    private class Msg {
        long id;
        int type;               // Used as folder for SMS/MMS
        int threadId;           // Used for SMS/MMS at delete
        long folderId = -1;     // Email folder ID
        long oldFolderId = -1;  // Used for email undelete
        boolean localInitiatedSend = false; // Used for MMS to filter out events
        boolean transparent = false; // Used for EMAIL to delete message sent with transparency

        public Msg(long id, int type, int threadId) {
            this.id = id;
            this.type = type;
            this.threadId = threadId;
        }
        public Msg(long id, long folderId) {
            this.id = id;
            this.folderId = folderId;
        }

        /* Eclipse generated hashCode() and equals() to make
         * hashMap lookup work independent of whether the obj
         * is used for email or SMS/MMS and whether or not the
         * oldFolder is set. */
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (int) (id ^ (id >>> 32));
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Msg other = (Msg) obj;
            if (id != other.id)
                return false;
            return true;
        }
    }

    private Map<Long, Msg> mMsgListSms = new HashMap<Long, Msg>();

    private Map<Long, Msg> mMsgListMms = new HashMap<Long, Msg>();

    private Map<Long, Msg> mMsgListEmail = new HashMap<Long, Msg>();

    public int setNotificationRegistration(int notificationStatus) throws RemoteException {
        // Forward the request to the MNS thread as a message - including the MAS instance ID.
        if(D) Log.d(TAG,"setNotificationRegistration() enter");
        Handler mns = mMnsClient.getMessageHandler();
        if(mns != null) {
            Message msg = mns.obtainMessage();
            msg.what = BluetoothMnsObexClient.MSG_MNS_NOTIFICATION_REGISTRATION;
            msg.arg1 = mMasId;
            msg.arg2 = notificationStatus;
            mns.sendMessageDelayed(msg, 10); // Send message without forcing a context switch
            /* Some devices - e.g. PTS needs to get the unregister confirm before we actually
             * disconnect the MNS. */
            if(D) Log.d(TAG,"setNotificationRegistration() MSG_MNS_NOTIFICATION_REGISTRATION send to MNS");
        } else {
            // This should not happen except at shutdown.
            if(D) Log.d(TAG,"setNotificationRegistration() Unable to send registration request");
            return ResponseCodes.OBEX_HTTP_UNAVAILABLE;
        }
        if(notificationStatus == BluetoothMapAppParams.NOTIFICATION_STATUS_YES) {
            registerObserver();
        } else {
            unregisterObserver();
        }
        return ResponseCodes.OBEX_HTTP_OK;
    }

    public void registerObserver() throws RemoteException{
        if (V) Log.d(TAG, "registerObserver");

        if (mObserverRegistered)
            return;

        /* Use MmsSms Uri since the Sms Uri is not notified on deletes */
        if(mEnableSmsMms){
            //this is sms/mms
            mResolver.registerContentObserver(MmsSms.CONTENT_URI, false, mObserver);
            mObserverRegistered = true;
        }
        if(mAccount != null) {

            mProviderClient = mResolver.acquireUnstableContentProviderClient(mAuthority);
            if (mProviderClient == null) {
                throw new RemoteException("Failed to acquire provider for " + mAuthority);
            }
            mProviderClient.setDetectNotResponding(PROVIDER_ANR_TIMEOUT);

            /* For URI's without account ID */
            Uri uri = Uri.parse(mAccount.mBase_uri_no_account + "/" + BluetoothMapContract.TABLE_MESSAGE);
            if(D) Log.d(TAG, "Registering observer for: " + uri);
            mResolver.registerContentObserver(uri, true, mObserver);

            /* For URI's with account ID - is handled the same way as without ID, but is
             * only triggered for MAS instances with matching account ID. */
            uri = Uri.parse(mAccount.mBase_uri + "/" + BluetoothMapContract.TABLE_MESSAGE);
            if(D) Log.d(TAG, "Registering observer for: " + uri);
            mResolver.registerContentObserver(uri, true, mObserver);
            mObserverRegistered = true;
        }
        initMsgList();
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
        Log.d(TAG, "sendEvent: " + evt.eventType + " " + evt.handle + " "
        + evt.folder + " " + evt.oldFolder + " " + evt.msgType.name());

        if (mMnsClient == null || mMnsClient.isConnected() == false) {
            Log.d(TAG, "sendEvent: No MNS client registered or connected- don't send event");
            return;
        }

        try {
            mMnsClient.sendEvent(evt.encode(), mMasId);
        } catch (UnsupportedEncodingException ex) {
            /* do nothing */
        }
    }

    private void initMsgList() throws RemoteException {
        if (V) Log.d(TAG, "initMsgList");

        if(mEnableSmsMms) {

            HashMap<Long, Msg> msgListSms = new HashMap<Long, Msg>();

            Cursor c = mResolver.query(Sms.CONTENT_URI,
                SMS_PROJECTION_SHORT, null, null, null);

            try {
                while (c != null && c.moveToNext()) {
                    long id = c.getLong(c.getColumnIndex(Sms._ID));
                    int type = c.getInt(c.getColumnIndex(Sms.TYPE));
                    int threadId = c.getInt(c.getColumnIndex(Sms.THREAD_ID));

                    Msg msg = new Msg(id, type, threadId);
                    msgListSms.put(id, msg);
                }
            } finally {
                close(c);
            }

            synchronized(mMsgListSms) {
                mMsgListSms.clear();
                mMsgListSms = msgListSms;
            }

            HashMap<Long, Msg> msgListMms = new HashMap<Long, Msg>();

            c = mResolver.query(Mms.CONTENT_URI, MMS_PROJECTION_SHORT, null, null, null);

            try {
                while (c != null && c.moveToNext()) {
                    long id = c.getLong(c.getColumnIndex(Mms._ID));
                    int type = c.getInt(c.getColumnIndex(Mms.MESSAGE_BOX));
                    int threadId = c.getInt(c.getColumnIndex(Mms.THREAD_ID));

                    Msg msg = new Msg(id, type, threadId);
                    msgListMms.put(id, msg);
                }
            } finally {
                close(c);
            }

            synchronized(mMsgListMms) {
                mMsgListMms.clear();
                mMsgListMms = msgListMms;
            }
        }

        if(mAccount != null) {
            HashMap<Long, Msg> msgListEmail = new HashMap<Long, Msg>();
            Uri uri = mMessageUri;
            Cursor c = mProviderClient.query(uri, EMAIL_PROJECTION_SHORT, null, null, null);

            try {
                while (c != null && c.moveToNext()) {
                    long id = c.getLong(c.getColumnIndex(MessageColumns._ID));
                    long folderId = c.getInt(c.getColumnIndex(BluetoothMapContract.MessageColumns.FOLDER_ID));

                    Msg msg = new Msg(id, folderId);
                    msgListEmail.put(id, msg);
                }
            } finally {
                close(c);
            }

            synchronized(mMsgListEmail) {
                mMsgListEmail.clear();
                mMsgListEmail = msgListEmail;
            }
        }
    }

    private void handleMsgListChangesSms() {
        if (V) Log.d(TAG, "handleMsgListChangesSms");

        HashMap<Long, Msg> msgListSms = new HashMap<Long, Msg>();

        Cursor c = mResolver.query(Sms.CONTENT_URI,
            SMS_PROJECTION_SHORT, null, null, null);

        synchronized(mMsgListSms) {
            try {
                while (c != null && c.moveToNext()) {
                    long id = c.getLong(c.getColumnIndex(Sms._ID));
                    int type = c.getInt(c.getColumnIndex(Sms.TYPE));
                    int threadId = c.getInt(c.getColumnIndex(Sms.THREAD_ID));

                    Msg msg = mMsgListSms.remove(id);

                    /* We must filter out any actions made by the MCE, hence do not send e.g. a message
                     * deleted and/or MessageShift for messages deleted by the MCE. */

                    if (msg == null) {
                        /* New message */
                        msg = new Msg(id, type, threadId);
                        msgListSms.put(id, msg);

                        /* Incoming message from the network */
                        Event evt = new Event(EVENT_TYPE_NEW, id, folderSms[type],
                            null, mSmsType);
                        sendEvent(evt);
                    } else {
                        /* Existing message */
                        if (type != msg.type) {
                            Log.d(TAG, "new type: " + type + " old type: " + msg.type);
                            String oldFolder = folderSms[msg.type];
                            String newFolder = folderSms[type];
                            // Filter out the intermediate outbox steps
                            if(!oldFolder.equals(newFolder)) {
                                Event evt = new Event(EVENT_TYPE_SHIFT, id, folderSms[type],
                                    oldFolder, mSmsType);
                                sendEvent(evt);
                            }
                            msg.type = type;
                        } else if(threadId != msg.threadId) {
                            Log.d(TAG, "Message delete change: type: " + type + " old type: " + msg.type
                                    + "\n    threadId: " + threadId + " old threadId: " + msg.threadId);
                            if(threadId == DELETED_THREAD_ID) { // Message deleted
                                Event evt = new Event(EVENT_TYPE_DELETE, id, BluetoothMapContract.FOLDER_NAME_DELETED,
                                    folderSms[msg.type], mSmsType);
                                sendEvent(evt);
                                msg.threadId = threadId;
                            } else { // Undelete
                                Event evt = new Event(EVENT_TYPE_SHIFT, id, folderSms[msg.type],
                                    BluetoothMapContract.FOLDER_NAME_DELETED, mSmsType);
                                sendEvent(evt);
                                msg.threadId = threadId;
                            }
                        }
                        msgListSms.put(id, msg);
                    }
                }
            } finally {
                close(c);
            }

            for (Msg msg : mMsgListSms.values()) {
                Event evt = new Event(EVENT_TYPE_DELETE, msg.id,
                                        BluetoothMapContract.FOLDER_NAME_DELETED,
                                        folderSms[msg.type], mSmsType);
                sendEvent(evt);
            }

            mMsgListSms = msgListSms;
        }
    }

    private void handleMsgListChangesMms() {
        if (V) Log.d(TAG, "handleMsgListChangesMms");

        HashMap<Long, Msg> msgListMms = new HashMap<Long, Msg>();

        Cursor c = mResolver.query(Mms.CONTENT_URI,
            MMS_PROJECTION_SHORT, null, null, null);

        synchronized(mMsgListMms) {
            try {
                while (c != null && c.moveToNext()) {
                    long id = c.getLong(c.getColumnIndex(Mms._ID));
                    int type = c.getInt(c.getColumnIndex(Mms.MESSAGE_BOX));
                    int mtype = c.getInt(c.getColumnIndex(Mms.MESSAGE_TYPE));
                    int threadId = c.getInt(c.getColumnIndex(Mms.THREAD_ID));

                    Msg msg = mMsgListMms.remove(id);

                    /* We must filter out any actions made by the MCE, hence do not send e.g. a message
                     * deleted and/or MessageShift for messages deleted by the MCE. */

                    if (msg == null) {
                        /* New message - only notify on retrieve conf */
                        if (folderMms[type].equals(BluetoothMapContract.FOLDER_NAME_INBOX) &&
                            mtype != MESSAGE_TYPE_RETRIEVE_CONF) {
                                continue;
                        }

                        msg = new Msg(id, type, threadId);
                        msgListMms.put(id, msg);

                        /* Incoming message from the network */
                        Event evt = new Event(EVENT_TYPE_NEW, id, folderMms[type],
                                null, TYPE.MMS);
                        sendEvent(evt);
                    } else {
                        /* Existing message */
                        if (type != msg.type) {
                            Log.d(TAG, "new type: " + type + " old type: " + msg.type);
                            Event evt;
                            if(msg.localInitiatedSend == false) {
                                // Only send events about local initiated changes
                                evt = new Event(EVENT_TYPE_SHIFT, id, folderMms[type],
                                        folderMms[msg.type], TYPE.MMS);
                                sendEvent(evt);
                            }
                            msg.type = type;

                            if (folderMms[type].equals(BluetoothMapContract.FOLDER_NAME_SENT)
                                    && msg.localInitiatedSend == true) {
                                msg.localInitiatedSend = false; // Stop tracking changes for this message
                                evt = new Event(EVENT_TYPE_SENDING_SUCCESS, id,
                                    folderSms[type], null, TYPE.MMS);
                                sendEvent(evt);
                            }
                        } else if(threadId != msg.threadId) {
                            Log.d(TAG, "Message delete change: type: " + type + " old type: " + msg.type
                                    + "\n    threadId: " + threadId + " old threadId: " + msg.threadId);
                            if(threadId == DELETED_THREAD_ID) { // Message deleted
                                Event evt = new Event(EVENT_TYPE_DELETE, id, BluetoothMapContract.FOLDER_NAME_DELETED,
                                    folderMms[msg.type], TYPE.MMS);
                                sendEvent(evt);
                                msg.threadId = threadId;
                            } else { // Undelete
                                Event evt = new Event(EVENT_TYPE_SHIFT, id, folderMms[msg.type],
                                    BluetoothMapContract.FOLDER_NAME_DELETED, TYPE.MMS);
                                sendEvent(evt);
                                msg.threadId = threadId;
                            }
                        }
                        msgListMms.put(id, msg);
                    }
                }
            } finally {
                close(c);
            }

            for (Msg msg : mMsgListMms.values()) {
                Event evt = new Event(EVENT_TYPE_DELETE, msg.id,
                                        BluetoothMapContract.FOLDER_NAME_DELETED,
                                        folderMms[msg.type], TYPE.MMS);
                sendEvent(evt);
            }
            mMsgListMms = msgListMms;
        }
    }

    private void handleMsgListChangesEmail(Uri uri)  throws RemoteException{
        if (V) Log.v(TAG, "handleMsgListChangesEmail uri: " + uri.toString());

        // TODO: Change observer to handle accountId and message ID if present

        HashMap<Long, Msg> msgListEmail = new HashMap<Long, Msg>();

        Cursor c = mProviderClient.query(mMessageUri, EMAIL_PROJECTION_SHORT, null, null, null);

        synchronized(mMsgListEmail) {
            try {
                while (c != null && c.moveToNext()) {
                    long id = c.getLong(c.getColumnIndex(BluetoothMapContract.MessageColumns._ID));
                    int folderId = c.getInt(c.getColumnIndex(
                            BluetoothMapContract.MessageColumns.FOLDER_ID));
                    Msg msg = mMsgListEmail.remove(id);
                    BluetoothMapFolderElement folderElement = mFolders.getEmailFolderById(folderId);
                    String newFolder;
                    if(folderElement != null) {
                        newFolder = folderElement.getFullPath();
                    } else {
                        newFolder = "unknown"; // This can happen if a new folder is created while connected
                    }

                    /* We must filter out any actions made by the MCE, hence do not send e.g. a message
                     * deleted and/or MessageShift for messages deleted by the MCE. */

                    if (msg == null) {
                        /* New message */
                        msg = new Msg(id, folderId);
                        msgListEmail.put(id, msg);
                        Event evt = new Event(EVENT_TYPE_NEW, id, newFolder,
                            null, TYPE.EMAIL);
                        sendEvent(evt);
                    } else {
                        /* Existing message */
                        if (folderId != msg.folderId) {
                            if (D) Log.d(TAG, "new folderId: " + folderId + " old folderId: " + msg.folderId);
                            BluetoothMapFolderElement oldFolderElement = mFolders.getEmailFolderById(msg.folderId);
                            String oldFolder;
                            if(oldFolderElement != null) {
                                oldFolder = oldFolderElement.getFullPath();
                            } else {
                                // This can happen if a new folder is created while connected
                                oldFolder = "unknown";
                            }
                            BluetoothMapFolderElement deletedFolder =
                                    mFolders.getEmailFolderByName(BluetoothMapContract.FOLDER_NAME_DELETED);
                            BluetoothMapFolderElement sentFolder =
                                    mFolders.getEmailFolderByName(BluetoothMapContract.FOLDER_NAME_SENT);
                            /*
                             *  If the folder is now 'deleted', send a deleted-event in stead of a shift
                             *  or if message is sent initiated by MAP Client, then send sending-success
                             *  otherwise send folderShift
                             */
                            if(deletedFolder != null && deletedFolder.getEmailFolderId() == folderId) {
                                Event evt = new Event(EVENT_TYPE_DELETE, msg.id, newFolder,
                                        oldFolder, TYPE.EMAIL);
                                sendEvent(evt);
                            } else if(sentFolder != null
                                      && sentFolder.getEmailFolderId() == folderId
                                      && msg.localInitiatedSend == true) {
                                if(msg.transparent) {
                                    mResolver.delete(ContentUris.withAppendedId(mMessageUri, id), null, null);
                                } else {
                                    msg.localInitiatedSend = false;
                                    Event evt = new Event(EVENT_TYPE_SENDING_SUCCESS, msg.id,
                                                          oldFolder, null, TYPE.EMAIL);
                                    sendEvent(evt);
                                }
                            } else {
                                Event evt = new Event(EVENT_TYPE_SHIFT, id, newFolder,
                                                      oldFolder, TYPE.EMAIL);
                                sendEvent(evt);
                            }
                            msg.folderId = folderId;
                        }
                        msgListEmail.put(id, msg);
                    }
                }
            } finally {
                close(c);
            }

            // For all messages no longer in the database send a delete notification
            for (Msg msg : mMsgListEmail.values()) {
                BluetoothMapFolderElement oldFolderElement = mFolders.getEmailFolderById(msg.folderId);
                String oldFolder;
                if(oldFolderElement != null) {
                    oldFolder = oldFolderElement.getFullPath();
                } else {
                    oldFolder = "unknown";
                }
                /* Some e-mail clients delete the message after sending, and creates a new message in sent.
                 * We cannot track the message anymore, hence send both a send success and delete message.
                 */
                if(msg.localInitiatedSend == true) {
                    msg.localInitiatedSend = false;
                    // If message is send with transparency don't set folder as message is deleted
                    if (msg.transparent)
                        oldFolder = null;
                    Event evt = new Event(EVENT_TYPE_SENDING_SUCCESS, msg.id, oldFolder, null, TYPE.EMAIL);
                    sendEvent(evt);
                }
                /* As this message deleted is only send on a real delete - don't set folder.
                 *  - only send delete event if message is not sent with transparency
                 */
                if (!msg.transparent) {

                    Event evt = new Event(EVENT_TYPE_DELETE, msg.id, null, oldFolder, TYPE.EMAIL);
                    sendEvent(evt);
                }
            }
            mMsgListEmail = msgListEmail;
        }
    }

    private void handleMsgListChanges(Uri uri) {
        if(uri.getAuthority().equals(mAuthority)) {
            try {
                handleMsgListChangesEmail(uri);
            }catch(RemoteException e){
                mMasInstance.restartObexServerSession();
                Log.w(TAG, "Problems contacting the ContentProvider in mas Instance "+mMasId+" restaring ObexServerSession");
            }

        } else {
            handleMsgListChangesSms();
            handleMsgListChangesMms();
        }
    }

    private boolean setEmailMessageStatusDelete(BluetoothMapFolderElement mCurrentFolder,
            String uriStr, long handle, int status) {
        boolean res = false;
        Uri uri = Uri.parse(uriStr + BluetoothMapContract.TABLE_MESSAGE);

        int updateCount = 0;
        ContentValues contentValues = new ContentValues();
        BluetoothMapFolderElement deleteFolder = mFolders.
                getEmailFolderByName(BluetoothMapContract.FOLDER_NAME_DELETED);
        contentValues.put(BluetoothMapContract.MessageColumns._ID, handle);
        synchronized(mMsgListEmail) {
            Msg msg = mMsgListEmail.get(handle);
            if (status == BluetoothMapAppParams.STATUS_VALUE_YES) {
                /* Set deleted folder id */
                long folderId = -1;
                if(deleteFolder != null) {
                    folderId = deleteFolder.getEmailFolderId();
                }
                contentValues.put(BluetoothMapContract.MessageColumns.FOLDER_ID,folderId);
                updateCount = mResolver.update(uri, contentValues, null, null);
                /* The race between updating the value in our cached values and the database
                 * is handled by the synchronized statement. */
                if(updateCount > 0) {
                    res = true;
                    if (msg != null) {
                        msg.oldFolderId = msg.folderId;
                        // Update the folder ID to avoid triggering an event for MCE initiated actions.
                        msg.folderId = folderId;
                    }
                    if(D) Log.d(TAG, "Deleted MSG: " + handle + " from folderId: " + folderId);
                } else {
                    Log.w(TAG, "Msg: " + handle + " - Set delete status " + status
                            + " failed for folderId " + folderId);
                }
            } else if (status == BluetoothMapAppParams.STATUS_VALUE_NO) {
                /* Undelete message. move to old folder if we know it,
                 * else move to inbox - as dictated by the spec. */
                if(msg != null && deleteFolder != null &&
                        msg.folderId == deleteFolder.getEmailFolderId()) {
                    /* Only modify messages in the 'Deleted' folder */
                    long folderId = -1;
                    if (msg != null && msg.oldFolderId != -1) {
                        folderId = msg.oldFolderId;
                    } else {
                        BluetoothMapFolderElement inboxFolder = mCurrentFolder.
                                getEmailFolderByName(BluetoothMapContract.FOLDER_NAME_INBOX);
                        if(inboxFolder != null) {
                            folderId = inboxFolder.getEmailFolderId();
                        }
                        if(D)Log.d(TAG,"We did not delete the message, hence the old folder is unknown. Moving to inbox.");
                    }
                    contentValues.put(BluetoothMapContract.MessageColumns.FOLDER_ID, folderId);
                    updateCount = mResolver.update(uri, contentValues, null, null);
                    if(updateCount > 0) {
                        res = true;
                        // Update the folder ID to avoid triggering an event for MCE initiated actions.
                        msg.folderId = folderId;
                    } else {
                        if(D)Log.d(TAG,"We did not delete the message, hence the old folder is unknown. Moving to inbox.");
                    }
                }
            }
            if(V) {
                BluetoothMapFolderElement folderElement;
                String folderName = "unknown";
                if (msg != null) {
                    folderElement = mCurrentFolder.getEmailFolderById(msg.folderId);
                    if(folderElement != null) {
                        folderName = folderElement.getName();
                    }
                }
                Log.d(TAG,"setEmailMessageStatusDelete: " + handle + " from " + folderName
                        + " status: " + status);
            }
        }
        if(res == false) {
            Log.w(TAG, "Set delete status " + status + " failed.");
        }
        return res;
    }

    private void updateThreadId(Uri uri, String valueString, long threadId) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(valueString, threadId);
        mResolver.update(uri, contentValues, null, null);
    }

    private boolean deleteMessageMms(long handle) {
        boolean res = false;
        Uri uri = ContentUris.withAppendedId(Mms.CONTENT_URI, handle);
        Cursor c = mResolver.query(uri, null, null, null, null);

        try {
            if (c != null && c.moveToFirst()) {
                /* Move to deleted folder, or delete if already in deleted folder */
                int threadId = c.getInt(c.getColumnIndex(Mms.THREAD_ID));
                if (threadId != DELETED_THREAD_ID) {
                    /* Set deleted thread id */
                    synchronized(mMsgListMms) {
                        Msg msg = mMsgListMms.get(handle);
                        if(msg != null) { // This will always be the case
                            msg.threadId = DELETED_THREAD_ID;
                        }
                    }
                    updateThreadId(uri, Mms.THREAD_ID, DELETED_THREAD_ID);
                } else {
                    /* Delete from observer message list to avoid delete notifications */
                    synchronized(mMsgListMms) {
                        mMsgListMms.remove(handle);
                    }
                    /* Delete message */
                    mResolver.delete(uri, null, null);
                }
                res = true;
            }
        } finally {
            close(c);
        }

        return res;
    }

    private boolean unDeleteMessageMms(long handle) {
        boolean res = false;
        Uri uri = ContentUris.withAppendedId(Mms.CONTENT_URI, handle);
        Cursor c = mResolver.query(uri, null, null, null, null);

        try {
            if (c != null && c.moveToFirst()) {
                int threadId = c.getInt(c.getColumnIndex(Mms.THREAD_ID));
                if (threadId == DELETED_THREAD_ID) {
                    /* Restore thread id from address, or if no thread for address
                    * create new thread by insert and remove of fake message */
                    String address;
                    long id = c.getLong(c.getColumnIndex(Mms._ID));
                    int msgBox = c.getInt(c.getColumnIndex(Mms.MESSAGE_BOX));
                    if (msgBox == Mms.MESSAGE_BOX_INBOX) {
                        address = BluetoothMapContent.getAddressMms(mResolver, id,
                            BluetoothMapContent.MMS_FROM);
                    } else {
                        address = BluetoothMapContent.getAddressMms(mResolver, id,
                            BluetoothMapContent.MMS_TO);
                    }
                    Set<String> recipients = new HashSet<String>();
                    recipients.addAll(Arrays.asList(address));
                    Long oldThreadId = Telephony.Threads.getOrCreateThreadId(mContext, recipients);
                    synchronized(mMsgListMms) {
                        Msg msg = mMsgListMms.get(handle);
                        if(msg != null) { // This will always be the case
                            msg.threadId = oldThreadId.intValue();
                        }
                    }
                    updateThreadId(uri, Mms.THREAD_ID, oldThreadId);
                } else {
                    Log.d(TAG, "Message not in deleted folder: handle " + handle
                        + " threadId " + threadId);
                }
                res = true;
            }
        } finally {
            close(c);
        }

        return res;
    }

    private boolean deleteMessageSms(long handle) {
        boolean res = false;
        Uri uri = ContentUris.withAppendedId(Sms.CONTENT_URI, handle);
        Cursor c = mResolver.query(uri, null, null, null, null);

        try {
            if (c != null && c.moveToFirst()) {
                /* Move to deleted folder, or delete if already in deleted folder */
                int threadId = c.getInt(c.getColumnIndex(Sms.THREAD_ID));
                if (threadId != DELETED_THREAD_ID) {
                    synchronized(mMsgListSms) {
                        Msg msg = mMsgListSms.get(handle);
                        if(msg != null) { // This will always be the case
                            msg.threadId = DELETED_THREAD_ID;
                        }
                    }
                    /* Set deleted thread id */
                    updateThreadId(uri, Sms.THREAD_ID, DELETED_THREAD_ID);
                } else {
                    /* Delete from observer message list to avoid delete notifications */
                    synchronized(mMsgListSms) {
                        mMsgListSms.remove(handle);
                    }
                    /* Delete message */
                    mResolver.delete(uri, null, null);
                }
                res = true;
            }
        } finally {
            close(c);
        }

        return res;
    }

    private boolean unDeleteMessageSms(long handle) {
        boolean res = false;
        Uri uri = ContentUris.withAppendedId(Sms.CONTENT_URI, handle);
        Cursor c = mResolver.query(uri, null, null, null, null);

        try {
            if (c != null && c.moveToFirst()) {
                int threadId = c.getInt(c.getColumnIndex(Sms.THREAD_ID));
                if (threadId == DELETED_THREAD_ID) {
                    String address = c.getString(c.getColumnIndex(Sms.ADDRESS));
                    Set<String> recipients = new HashSet<String>();
                    recipients.addAll(Arrays.asList(address));
                    Long oldThreadId = Telephony.Threads.getOrCreateThreadId(mContext, recipients);
                    synchronized(mMsgListSms) {
                        Msg msg = mMsgListSms.get(handle);
                        if(msg != null) { // This will always be the case
                            msg.threadId = oldThreadId.intValue(); // The threadId is specified as an int, so it is safe to truncate
                        }
                    }
                    updateThreadId(uri, Sms.THREAD_ID, oldThreadId);
                } else {
                    Log.d(TAG, "Message not in deleted folder: handle " + handle
                        + " threadId " + threadId);
                }
                res = true;
            }
        } finally {
            close(c);
        }

        return res;
    }

    public boolean setMessageStatusDeleted(long handle, TYPE type,
            BluetoothMapFolderElement mCurrentFolder, String uriStr, int statusValue) {
        boolean res = false;
        if (D) Log.d(TAG, "setMessageStatusDeleted: handle " + handle
            + " type " + type + " value " + statusValue);

        if (type == TYPE.EMAIL) {
            res = setEmailMessageStatusDelete(mCurrentFolder, uriStr, handle, statusValue);
        } else {
            if (statusValue == BluetoothMapAppParams.STATUS_VALUE_YES) {
                if (type == TYPE.SMS_GSM || type == TYPE.SMS_CDMA) {
                    res = deleteMessageSms(handle);
                } else if (type == TYPE.MMS) {
                    res = deleteMessageMms(handle);
                }
            } else if (statusValue == BluetoothMapAppParams.STATUS_VALUE_NO) {
                if (type == TYPE.SMS_GSM || type == TYPE.SMS_CDMA) {
                    res = unDeleteMessageSms(handle);
                } else if (type == TYPE.MMS) {
                    res = unDeleteMessageMms(handle);
                }
            }
        }

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
    public boolean setMessageStatusRead(long handle, TYPE type, String uriStr, int statusValue) throws RemoteException{
        int count = 0;

        if (D) Log.d(TAG, "setMessageStatusRead: handle " + handle
            + " type " + type + " value " + statusValue);

        /* Approved MAP spec errata 3445 states that read status initiated */
        /* by the MCE shall change the MSE read status. */

        if (type == TYPE.SMS_GSM || type == TYPE.SMS_CDMA) {
            Uri uri = Sms.Inbox.CONTENT_URI;//ContentUris.withAppendedId(Sms.CONTENT_URI, handle);
            ContentValues contentValues = new ContentValues();
            contentValues.put(Sms.READ, statusValue);
            contentValues.put(Sms.SEEN, statusValue);
            String where = Sms._ID+"="+handle;
            String values = contentValues.toString();
            if (D) Log.d(TAG, " -> SMS Uri: " + uri.toString() + " Where " + where + " values " + values);
            count = mResolver.update(uri, contentValues, where, null);
            if (D) Log.d(TAG, " -> "+count +" rows updated!");

        } else if (type == TYPE.MMS) {
            Uri uri = ContentUris.withAppendedId(Mms.CONTENT_URI, handle);
            if (D) Log.d(TAG, " -> MMS Uri: " + uri.toString());
            ContentValues contentValues = new ContentValues();
            contentValues.put(Mms.READ, statusValue);
            count = mResolver.update(uri, contentValues, null, null);
            if (D) Log.d(TAG, " -> "+count +" rows updated!");

        } if (type == TYPE.EMAIL) {
            Uri uri = mMessageUri;
            ContentValues contentValues = new ContentValues();
            contentValues.put(BluetoothMapContract.MessageColumns.FLAG_READ, statusValue);
            contentValues.put(BluetoothMapContract.MessageColumns._ID, handle);
            count = mProviderClient.update(uri, contentValues, null, null);
        }

        return (count > 0);
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

    public long pushMessage(BluetoothMapbMessage msg, BluetoothMapFolderElement folderElement,
            BluetoothMapAppParams ap, String emailBaseUri)
                    throws IllegalArgumentException, RemoteException, IOException {
        if (D) Log.d(TAG, "pushMessage");
        ArrayList<BluetoothMapbMessage.vCard> recipientList = msg.getRecipients();
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
            /* Write the message to the database */
            String msgBody = ((BluetoothMapbMessageEmail) msg).getEmailBody();
            if (V) {
                int length = msgBody.length();
                Log.v(TAG, "pushMessage: message string length = " + length);
                String messages[] = msgBody.split("\r\n");
                Log.v(TAG, "pushMessage: messages count=" + messages.length);
                for(int i = 0; i < messages.length; i++) {
                    Log.v(TAG, "part " + i + ":" + messages[i]);
                }
            }
            FileOutputStream os = null;
            ParcelFileDescriptor fdOut = null;
            Uri uriInsert = Uri.parse(emailBaseUri + BluetoothMapContract.TABLE_MESSAGE);
            if (D) Log.d(TAG, "pushMessage - uriInsert= " + uriInsert.toString() +
                    ", intoFolder id=" + folderElement.getEmailFolderId());

            synchronized(mMsgListEmail) {
                // Now insert the empty message into folder
                ContentValues values = new ContentValues();
                folderId = folderElement.getEmailFolderId();
                values.put(BluetoothMapContract.MessageColumns.FOLDER_ID, folderId);
                Uri uriNew = mProviderClient.insert(uriInsert, values);
                if (D) Log.d(TAG, "pushMessage - uriNew= " + uriNew.toString());
                handle =  Long.parseLong(uriNew.getLastPathSegment());

                try {
                    fdOut = mProviderClient.openFile(uriNew, "w");
                    os = new FileOutputStream(fdOut.getFileDescriptor());
                    // Write Email to DB
                    os.write(msgBody.getBytes(), 0, msgBody.getBytes().length);
                } catch (FileNotFoundException e) {
                    Log.w(TAG, e);
                    throw(new IOException("Unable to open file stream"));
                } catch (NullPointerException e) {
                    Log.w(TAG, e);
                    throw(new IllegalArgumentException("Unable to parse message."));
                } finally {
                    try {
                        if(os != null)
                            os.close();
                    } catch (IOException e) {Log.w(TAG, e);}
                    try {
                        if(fdOut != null)
                             fdOut.close();
                    } catch (IOException e) {Log.w(TAG, e);}
                }

                /* Extract the data for the inserted message, and store in local mirror, to
                 * avoid sending a NewMessage Event. */
                Msg newMsg = new Msg(handle, folderId);
                newMsg.transparent = (transparent == 1) ? true : false;
                if ( folderId == folderElement.getEmailFolderByName(
                        BluetoothMapContract.FOLDER_NAME_OUTBOX).getEmailFolderId() ) {
                    newMsg.localInitiatedSend = true;
                }
                mMsgListEmail.put(handle, newMsg);
            }
        } else { // type SMS_* of MMS
            for (BluetoothMapbMessage.vCard recipient : recipientList) {
                if(recipient.getEnvLevel() == 0) // Only send the message to the top level recipient
                {
                    /* Only send to first address */
                    String phone = recipient.getFirstPhoneNumber();
                    String email = recipient.getFirstEmail();
                    String folder = folderElement.getName();
                    boolean read = false;
                    boolean deliveryReport = true;
                    String msgBody = null;

                    /* If MMS contains text only and the size is less than ten SMS's
                     * then convert the MMS to type SMS and then proceed
                     */
                    if (msg.getType().equals(TYPE.MMS) &&
                            (((BluetoothMapbMessageMms) msg).getTextOnly() == true)) {
                        msgBody = ((BluetoothMapbMessageMms) msg).getMessageAsText();
                        SmsManager smsMng = SmsManager.getDefault();
                        ArrayList<String> parts = smsMng.divideMessage(msgBody);
                        int smsParts = parts.size();
                        if (smsParts  <= CONVERT_MMS_TO_SMS_PART_COUNT ) {
                            if (D) Log.d(TAG, "pushMessage - converting MMS to SMS, sms parts=" + smsParts );
                            msg.setType(mSmsType);
                        } else {
                            if (D) Log.d(TAG, "pushMessage - MMS text only but to big to convert to SMS");
                            msgBody = null;
                        }

                    }

                    if (msg.getType().equals(TYPE.MMS)) {
                        /* Send message if folder is outbox else just store in draft*/
                        handle = sendMmsMessage(folder, phone, (BluetoothMapbMessageMms)msg);
                    } else if (msg.getType().equals(TYPE.SMS_GSM) ||
                            msg.getType().equals(TYPE.SMS_CDMA) ) {
                        /* Add the message to the database */
                        if(msgBody == null)
                            msgBody = ((BluetoothMapbMessageSms) msg).getSmsBody();

                        /* We need to lock the SMS list while updating the database, to avoid sending
                         * events on MCE initiated operation. */
                        Uri contentUri = Uri.parse(Sms.CONTENT_URI+ "/" + folder);
                        Uri uri;
                        synchronized(mMsgListSms) {
                            uri = Sms.addMessageToUri(mResolver, contentUri, phone, msgBody,
                                "", System.currentTimeMillis(), read, deliveryReport);

                            if(V) Log.v(TAG, "Sms.addMessageToUri() returned: " + uri);
                            if (uri == null) {
                                if (D) Log.d(TAG, "pushMessage - failure on add to uri " + contentUri);
                                return -1;
                            }
                            Cursor c = mResolver.query(uri, SMS_PROJECTION_SHORT, null, null, null);
                            try {
                                /* Extract the data for the inserted message, and store in local mirror, to
                                * avoid sending a NewMessage Event. */
                                if (c != null && c.moveToFirst()) {
                                    long id = c.getLong(c.getColumnIndex(Sms._ID));
                                    int type = c.getInt(c.getColumnIndex(Sms.TYPE));
                                    int threadId = c.getInt(c.getColumnIndex(Sms.THREAD_ID));
                                    Msg newMsg = new Msg(id, type, threadId);
                                    mMsgListSms.put(id, newMsg);
                                } else {
                                    return -1; // This can only happen, if the message is deleted just as it is added
                                }
                            } finally {
                                close(c);
                            }

                            handle = Long.parseLong(uri.getLastPathSegment());

                            /* Send message if folder is outbox */
                            if (folder.equals(BluetoothMapContract.FOLDER_NAME_OUTBOX)) {
                                PushMsgInfo msgInfo = new PushMsgInfo(handle, transparent,
                                    retry, phone, uri);
                                mPushMsgList.put(handle, msgInfo);
                                sendMessage(msgInfo, msgBody);
                                if(V) Log.v(TAG, "sendMessage returned...");
                            }
                            /* sendMessage causes the message to be deleted and reinserted, hence we need to lock
                             * the list while this is happening. */
                        }
                    } else {
                        if (D) Log.d(TAG, "pushMessage - failure on type " );
                        return -1;
                    }
                }
            }
        }

        /* If multiple recipients return handle of last */
        return handle;
    }

    public long sendMmsMessage(String folder, String to_address, BluetoothMapbMessageMms msg) {
        /*
         *strategy:
         *1) parse message into parts
         *if folder is outbox/drafts:
         *2) push message to draft
         *if folder is outbox:
         *3) move message to outbox (to trigger the mms app to add msg to pending_messages list)
         *4) send intent to mms app in order to wake it up.
         *else if folder !outbox:
         *1) push message to folder
         * */
        if (folder != null && (folder.equalsIgnoreCase(BluetoothMapContract.FOLDER_NAME_OUTBOX)
                ||  folder.equalsIgnoreCase(BluetoothMapContract.FOLDER_NAME_DRAFT))) {
            long handle = pushMmsToFolder(Mms.MESSAGE_BOX_DRAFTS, to_address, msg);
            /* if invalid handle (-1) then just return the handle - else continue sending (if folder is outbox) */
            if (BluetoothMapAppParams.INVALID_VALUE_PARAMETER != handle && folder.equalsIgnoreCase(BluetoothMapContract.FOLDER_NAME_OUTBOX)) {
                moveDraftToOutbox(handle);
                Intent sendIntent = new Intent("android.intent.action.MMS_SEND_OUTBOX_MSG");
                if (D) Log.d(TAG, "broadcasting intent: "+sendIntent.toString());
                mContext.sendBroadcast(sendIntent);
            }
            return handle;
        } else {
            /* not allowed to push mms to anything but outbox/draft */
            throw  new IllegalArgumentException("Cannot push message to other folders than outbox/draft");
        }
    }

    private void moveDraftToOutbox(long handle) {
        /*Move message by changing the msg_box value in the content provider database */
        if (handle != -1) return;

        String whereClause = " _id= " + handle;
        Uri uri = Mms.CONTENT_URI;
        Cursor queryResult = mResolver.query(uri, null, whereClause, null, null);
        try {
            if (queryResult != null && queryResult.moveToFirst()) {
                ContentValues data = new ContentValues();
                /* set folder to be outbox */
                data.put(Mms.MESSAGE_BOX, Mms.MESSAGE_BOX_OUTBOX);
                mResolver.update(uri, data, whereClause, null);
                if (D) Log.d(TAG, "Moved draft MMS to outbox");
            } else {
                if (D) Log.d(TAG, "Could not move draft to outbox ");
            }
        } finally {
            queryResult.close();
        }
    }

    private long pushMmsToFolder(int folder, String to_address, BluetoothMapbMessageMms msg) {
        /**
         * strategy:
         * 1) parse msg into parts + header
         * 2) create thread id (abuse the ease of adding an SMS to get id for thread)
         * 3) push parts into content://mms/parts/ table
         * 3)
         */

        ContentValues values = new ContentValues();
        values.put(Mms.MESSAGE_BOX, folder);
        values.put(Mms.READ, 0);
        values.put(Mms.SEEN, 0);
        if(msg.getSubject() != null) {
            values.put(Mms.SUBJECT, msg.getSubject());
        } else {
            values.put(Mms.SUBJECT, "");
        }

        if(msg.getSubject() != null && msg.getSubject().length() > 0) {
            values.put(Mms.SUBJECT_CHARSET, 106);
        }
        values.put(Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related");
        values.put(Mms.EXPIRY, 604800);
        values.put(Mms.MESSAGE_CLASS, PduHeaders.MESSAGE_CLASS_PERSONAL_STR);
        values.put(Mms.MESSAGE_TYPE, PduHeaders.MESSAGE_TYPE_SEND_REQ);
        values.put(Mms.MMS_VERSION, PduHeaders.CURRENT_MMS_VERSION);
        values.put(Mms.PRIORITY, PduHeaders.PRIORITY_NORMAL);
        values.put(Mms.READ_REPORT, PduHeaders.VALUE_NO);
        values.put(Mms.TRANSACTION_ID, "T"+ Long.toHexString(System.currentTimeMillis()));
        values.put(Mms.DELIVERY_REPORT, PduHeaders.VALUE_NO);
        values.put(Mms.LOCKED, 0);
        if(msg.getTextOnly() == true)
            values.put(Mms.TEXT_ONLY, true);
        values.put(Mms.MESSAGE_SIZE, msg.getSize());

        // Get thread id
        Set<String> recipients = new HashSet<String>();
        recipients.addAll(Arrays.asList(to_address));
        values.put(Mms.THREAD_ID, Telephony.Threads.getOrCreateThreadId(mContext, recipients));
        Uri uri = Mms.CONTENT_URI;

        synchronized (mMsgListMms) {
            uri = mResolver.insert(uri, values);

            if (uri == null) {
                // unable to insert MMS
                Log.e(TAG, "Unabled to insert MMS " + values + "Uri: " + uri);
                return -1;
            }
            /* As we already have all the values we need, we could skip the query, but
               doing the query ensures we get any changes made by the content provider
               at insert. */
            Cursor c = mResolver.query(uri, MMS_PROJECTION_SHORT, null, null, null);
            try {
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(c.getColumnIndex(Mms._ID));
                    int type = c.getInt(c.getColumnIndex(Mms.MESSAGE_BOX));
                    int threadId = c.getInt(c.getColumnIndex(Mms.THREAD_ID));

                    /* We must filter out any actions made by the MCE. Add the new message to
                     * the list of known messages. */

                    Msg newMsg = new Msg(id, type, threadId);
                    newMsg.localInitiatedSend = true;
                    mMsgListMms.put(id, newMsg);
                }
            } finally {
                close(c);
            }
        } // Done adding changes, unlock access to mMsgListMms to allow sending MMS events again

        long handle = Long.parseLong(uri.getLastPathSegment());
        if (V) Log.v(TAG, " NEW URI " + uri.toString());

        try {
            if(msg.getMimeParts() == null) {
                /* Perhaps this message have been deleted, and no longer have any content, but only headers */
                Log.w(TAG, "No MMS parts present...");
            } else {
                if(V) Log.v(TAG, "Adding " + msg.getMimeParts().size() + " parts to the data base.");
                int count = 0;
                for(MimePart part : msg.getMimeParts()) {
                    ++count;
                    values.clear();
                    if(part.mContentType != null &&  part.mContentType.toUpperCase().contains("TEXT")) {
                        values.put(Mms.Part.CONTENT_TYPE, "text/plain");
                        values.put(Mms.Part.CHARSET, 106);
                        if(part.mPartName != null) {
                            values.put(Mms.Part.FILENAME, part.mPartName);
                            values.put(Mms.Part.NAME, part.mPartName);
                        } else {
                            values.put(Mms.Part.FILENAME, "text_" + count +".txt");
                            values.put(Mms.Part.NAME, "text_" + count +".txt");
                        }
                        // Ensure we have "ci" set
                        if(part.mContentId != null) {
                            values.put(Mms.Part.CONTENT_ID, part.mContentId);
                        } else {
                            if(part.mPartName != null) {
                                values.put(Mms.Part.CONTENT_ID, "<" + part.mPartName + ">");
                            } else {
                                values.put(Mms.Part.CONTENT_ID, "<text_" + count + ">");
                            }
                        }
                        // Ensure we have "cl" set
                        if(part.mContentLocation != null) {
                            values.put(Mms.Part.CONTENT_LOCATION, part.mContentLocation);
                        } else {
                            if(part.mPartName != null) {
                                values.put(Mms.Part.CONTENT_LOCATION, part.mPartName + ".txt");
                            } else {
                                values.put(Mms.Part.CONTENT_LOCATION, "text_" + count + ".txt");
                            }
                        }

                        if(part.mContentDisposition != null) {
                            values.put(Mms.Part.CONTENT_DISPOSITION, part.mContentDisposition);
                        }
                        values.put(Mms.Part.TEXT, part.getDataAsString());
                        uri = Uri.parse(Mms.CONTENT_URI + "/" + handle + "/part");
                        uri = mResolver.insert(uri, values);
                        if(V) Log.v(TAG, "Added TEXT part");

                    } else if (part.mContentType != null &&  part.mContentType.toUpperCase().contains("SMIL")){

                        values.put(Mms.Part.SEQ, -1);
                        values.put(Mms.Part.CONTENT_TYPE, "application/smil");
                        if(part.mContentId != null) {
                            values.put(Mms.Part.CONTENT_ID, part.mContentId);
                        } else {
                            values.put(Mms.Part.CONTENT_ID, "<smil_" + count + ">");
                        }
                        if(part.mContentLocation != null) {
                            values.put(Mms.Part.CONTENT_LOCATION, part.mContentLocation);
                        } else {
                            values.put(Mms.Part.CONTENT_LOCATION, "smil_" + count + ".xml");
                        }

                        if(part.mContentDisposition != null)
                            values.put(Mms.Part.CONTENT_DISPOSITION, part.mContentDisposition);
                        values.put(Mms.Part.FILENAME, "smil.xml");
                        values.put(Mms.Part.NAME, "smil.xml");
                        values.put(Mms.Part.TEXT, new String(part.mData, "UTF-8"));

                        uri = Uri.parse(Mms.CONTENT_URI+ "/" + handle + "/part");
                        uri = mResolver.insert(uri, values);
                        if (V) Log.v(TAG, "Added SMIL part");

                    }else /*VIDEO/AUDIO/IMAGE*/ {
                        writeMmsDataPart(handle, part, count);
                        if (V) Log.v(TAG, "Added OTHER part");
                    }
                    if (uri != null){
                        if (V) Log.v(TAG, "Added part with content-type: "+ part.mContentType + " to Uri: " + uri.toString());
                    }
                }
            }
        } catch (UnsupportedEncodingException e) {
            Log.w(TAG, e);
        } catch (IOException e) {
            Log.w(TAG, e);
        }

        values.clear();
        values.put(Mms.Addr.CONTACT_ID, "null");
        values.put(Mms.Addr.ADDRESS, "insert-address-token");
        values.put(Mms.Addr.TYPE, BluetoothMapContent.MMS_FROM);
        values.put(Mms.Addr.CHARSET, 106);

        uri = Uri.parse(Mms.CONTENT_URI + "/"  + handle + "/addr");
        uri = mResolver.insert(uri, values);
        if (uri != null && V){
            Log.v(TAG, " NEW URI " + uri.toString());
        }

        values.clear();
        values.put(Mms.Addr.CONTACT_ID, "null");
        values.put(Mms.Addr.ADDRESS, to_address);
        values.put(Mms.Addr.TYPE, BluetoothMapContent.MMS_TO);
        values.put(Mms.Addr.CHARSET, 106);

        uri = Uri.parse(Mms.CONTENT_URI + "/"  + handle + "/addr");
        uri = mResolver.insert(uri, values);
        if (uri != null && V){
            Log.v(TAG, " NEW URI " + uri.toString());
        }
        return handle;
    }


    private void writeMmsDataPart(long handle, MimePart part, int count) throws IOException{
        ContentValues values = new ContentValues();
        values.put(Mms.Part.MSG_ID, handle);
        if(part.mContentType != null) {
            values.put(Mms.Part.CONTENT_TYPE, part.mContentType);
        } else {
            Log.w(TAG, "MMS has no CONTENT_TYPE for part " + count);
        }
        if(part.mContentId != null) {
            values.put(Mms.Part.CONTENT_ID, part.mContentId);
        } else {
            if(part.mPartName != null) {
                values.put(Mms.Part.CONTENT_ID, "<" + part.mPartName + ">");
            } else {
                values.put(Mms.Part.CONTENT_ID, "<part_" + count + ">");
            }
        }

        if(part.mContentLocation != null) {
            values.put(Mms.Part.CONTENT_LOCATION, part.mContentLocation);
        } else {
            if(part.mPartName != null) {
                values.put(Mms.Part.CONTENT_LOCATION, part.mPartName + ".dat");
            } else {
                values.put(Mms.Part.CONTENT_LOCATION, "part_" + count + ".dat");
            }
        }
        if(part.mContentDisposition != null)
            values.put(Mms.Part.CONTENT_DISPOSITION, part.mContentDisposition);
        if(part.mPartName != null) {
            values.put(Mms.Part.FILENAME, part.mPartName);
            values.put(Mms.Part.NAME, part.mPartName);
        } else {
            /* We must set at least one part identifier */
            values.put(Mms.Part.FILENAME, "part_" + count + ".dat");
            values.put(Mms.Part.NAME, "part_" + count + ".dat");
        }
        Uri partUri = Uri.parse(Mms.CONTENT_URI + "/" + handle + "/part");
        Uri res = mResolver.insert(partUri, values);

        // Add data to part
        OutputStream os = mResolver.openOutputStream(res);
        os.write(part.mData);
        os.close();
    }


    public void sendMessage(PushMsgInfo msgInfo, String msgBody) {

        SmsManager smsMng = SmsManager.getDefault();
        ArrayList<String> parts = smsMng.divideMessage(msgBody);
        msgInfo.parts = parts.size();
        // We add a time stamp to differentiate delivery reports from each other for resent messages
        msgInfo.timestamp = Calendar.getInstance().getTime().getTime();
        msgInfo.partsDelivered = 0;
        msgInfo.partsSent = 0;

        ArrayList<PendingIntent> deliveryIntents = new ArrayList<PendingIntent>(msgInfo.parts);
        ArrayList<PendingIntent> sentIntents = new ArrayList<PendingIntent>(msgInfo.parts);

        /*       We handle the SENT intent in the MAP service, as this object
         *       is destroyed at disconnect, hence if a disconnect occur while sending
         *       a message, there is no intent handler to move the message from outbox
         *       to the correct folder.
         *       The correct solution would be to create a service that will start based on
         *       the intent, if BT is turned off. */

        for (int i = 0; i < msgInfo.parts; i++) {
            Intent intentDelivery, intentSent;

            intentDelivery = new Intent(ACTION_MESSAGE_DELIVERY, null);
            /* Add msgId and part number to ensure the intents are different, and we
             * thereby get an intent for each msg part.
             * setType is needed to create different intents for each message id/ time stamp,
             * as the extras are not used when comparing. */
            intentDelivery.setType("message/" + Long.toString(msgInfo.id) + msgInfo.timestamp + i);
            intentDelivery.putExtra(EXTRA_MESSAGE_SENT_HANDLE, msgInfo.id);
            intentDelivery.putExtra(EXTRA_MESSAGE_SENT_TIMESTAMP, msgInfo.timestamp);
            PendingIntent pendingIntentDelivery = PendingIntent.getBroadcast(mContext, 0,
                    intentDelivery, PendingIntent.FLAG_UPDATE_CURRENT);

            intentSent = new Intent(ACTION_MESSAGE_SENT, null);
            /* Add msgId and part number to ensure the intents are different, and we
             * thereby get an intent for each msg part.
             * setType is needed to create different intents for each message id/ time stamp,
             * as the extras are not used when comparing. */
            intentSent.setType("message/" + Long.toString(msgInfo.id) + msgInfo.timestamp + i);
            intentSent.putExtra(EXTRA_MESSAGE_SENT_HANDLE, msgInfo.id);
            intentSent.putExtra(EXTRA_MESSAGE_SENT_URI, msgInfo.uri.toString());
            intentSent.putExtra(EXTRA_MESSAGE_SENT_RETRY, msgInfo.retry);
            intentSent.putExtra(EXTRA_MESSAGE_SENT_TRANSPARENT, msgInfo.transparent);

            PendingIntent pendingIntentSent = PendingIntent.getBroadcast(mContext, 0,
                    intentSent, PendingIntent.FLAG_UPDATE_CURRENT);

            // We use the same pending intent for all parts, but do not set the one shot flag.
            deliveryIntents.add(pendingIntentDelivery);
            sentIntents.add(pendingIntentSent);
        }

        Log.d(TAG, "sendMessage to " + msgInfo.phone);

        smsMng.sendMultipartTextMessage(msgInfo.phone, null, parts, sentIntents,
            deliveryIntents);
    }

    private static final String ACTION_MESSAGE_DELIVERY =
        "com.android.bluetooth.BluetoothMapContentObserver.action.MESSAGE_DELIVERY";
    public static final String ACTION_MESSAGE_SENT =
        "com.android.bluetooth.BluetoothMapContentObserver.action.MESSAGE_SENT";

    public static final String EXTRA_MESSAGE_SENT_HANDLE = "HANDLE";
    public static final String EXTRA_MESSAGE_SENT_RESULT = "result";
    public static final String EXTRA_MESSAGE_SENT_URI = "uri";
    public static final String EXTRA_MESSAGE_SENT_RETRY = "retry";
    public static final String EXTRA_MESSAGE_SENT_TRANSPARENT = "transparent";
    public static final String EXTRA_MESSAGE_SENT_TIMESTAMP = "timestamp";

    private SmsBroadcastReceiver mSmsBroadcastReceiver = new SmsBroadcastReceiver();

    private boolean mInitialized = false;

    private class SmsBroadcastReceiver extends BroadcastReceiver {
        private final String[] ID_PROJECTION = new String[] { Sms._ID };
        private final Uri UPDATE_STATUS_URI = Uri.withAppendedPath(Sms.CONTENT_URI, "/status");

        public void register() {
            Handler handler = new Handler(Looper.getMainLooper());

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ACTION_MESSAGE_DELIVERY);
            /* The reception of ACTION_MESSAGE_SENT have been moved to the MAP
             * service, to be able to handle message sent events after a disconnect. */
            //intentFilter.addAction(ACTION_MESSAGE_SENT);
            try{
                intentFilter.addDataType("message/*");
            } catch (MalformedMimeTypeException e) {
                Log.e(TAG, "Wrong mime type!!!", e);
            }

            mContext.registerReceiver(this, intentFilter, null, handler);
        }

        public void unregister() {
            try {
                mContext.unregisterReceiver(this);
            } catch (IllegalArgumentException e) {
                /* do nothing */
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            long handle = intent.getLongExtra(EXTRA_MESSAGE_SENT_HANDLE, -1);
            PushMsgInfo msgInfo = mPushMsgList.get(handle);

            Log.d(TAG, "onReceive: action"  + action);

            if (msgInfo == null) {
                Log.d(TAG, "onReceive: no msgInfo found for handle " + handle);
                return;
            }

            if (action.equals(ACTION_MESSAGE_SENT)) {
                int result = intent.getIntExtra(EXTRA_MESSAGE_SENT_RESULT, Activity.RESULT_CANCELED);
                msgInfo.partsSent++;
                if(result != Activity.RESULT_OK) {
                    // If just one of the parts in the message fails, we need to send the entire message again
                    msgInfo.failedSent = true;
                }
                if(D) Log.d(TAG, "onReceive: msgInfo.partsSent = " + msgInfo.partsSent
                        + ", msgInfo.parts = " + msgInfo.parts + " result = " + result);

                if (msgInfo.partsSent == msgInfo.parts) {
                    actionMessageSent(context, intent, msgInfo);
                }
            } else if (action.equals(ACTION_MESSAGE_DELIVERY)) {
                long timestamp = intent.getLongExtra(EXTRA_MESSAGE_SENT_TIMESTAMP, 0);
                int status = -1;
                if(msgInfo.timestamp == timestamp) {
                    msgInfo.partsDelivered++;
                    byte[] pdu = intent.getByteArrayExtra("pdu");
                    String format = intent.getStringExtra("format");

                    SmsMessage message = SmsMessage.createFromPdu(pdu, format);
                    if (message == null) {
                        Log.d(TAG, "actionMessageDelivery: Can't get message from pdu");
                        return;
                    }
                    status = message.getStatus();
                    if(status != 0/*0 is success*/) {
                        msgInfo.statusDelivered = status;
                    }
                }
                if (msgInfo.partsDelivered == msgInfo.parts) {
                    actionMessageDelivery(context, intent, msgInfo);
                }
            } else {
                Log.d(TAG, "onReceive: Unknown action " + action);
            }
        }

        private void actionMessageSent(Context context, Intent intent, PushMsgInfo msgInfo) {
            /* As the MESSAGE_SENT intent is forwarded from the MAP service, we use the intent
             * to carry the result, as getResult() will not return the correct value.
             */
            boolean delete = false;

            if(D) Log.d(TAG,"actionMessageSent(): msgInfo.failedSent = " + msgInfo.failedSent);

            msgInfo.sendInProgress = false;

            if (msgInfo.failedSent == false) {
                if(D) Log.d(TAG, "actionMessageSent: result OK");
                if (msgInfo.transparent == 0) {
                    if (!Sms.moveMessageToFolder(context, msgInfo.uri,
                            Sms.MESSAGE_TYPE_SENT, 0)) {
                        Log.w(TAG, "Failed to move " + msgInfo.uri + " to SENT");
                    }
                } else {
                    delete = true;
                }

                Event evt = new Event(EVENT_TYPE_SENDING_SUCCESS, msgInfo.id,
                    folderSms[Sms.MESSAGE_TYPE_SENT], null, mSmsType);
                sendEvent(evt);

            } else {
                if (msgInfo.retry == 1) {
                    /* Notify failure, but keep message in outbox for resending */
                    msgInfo.resend = true;
                    msgInfo.partsSent = 0; // Reset counter for the retry
                    msgInfo.failedSent = false;
                    Event evt = new Event(EVENT_TYPE_SENDING_FAILURE, msgInfo.id,
                        folderSms[Sms.MESSAGE_TYPE_OUTBOX], null, mSmsType);
                    sendEvent(evt);
                } else {
                    if (msgInfo.transparent == 0) {
                        if (!Sms.moveMessageToFolder(context, msgInfo.uri,
                                Sms.MESSAGE_TYPE_FAILED, 0)) {
                            Log.w(TAG, "Failed to move " + msgInfo.uri + " to FAILED");
                        }
                    } else {
                        delete = true;
                    }

                    Event evt = new Event(EVENT_TYPE_SENDING_FAILURE, msgInfo.id,
                        folderSms[Sms.MESSAGE_TYPE_FAILED], null, mSmsType);
                    sendEvent(evt);
                }
            }

            if (delete == true) {
                /* Delete from Observer message list to avoid delete notifications */
                synchronized(mMsgListSms) {
                    mMsgListSms.remove(msgInfo.id);
                }

                /* Delete from DB */
                mResolver.delete(msgInfo.uri, null, null);
            }
        }

        private void actionMessageDelivery(Context context, Intent intent, PushMsgInfo msgInfo) {
            Uri messageUri = intent.getData();
            msgInfo.sendInProgress = false;

            Cursor cursor = mResolver.query(msgInfo.uri, ID_PROJECTION, null, null, null);

            try {
                if (cursor.moveToFirst()) {
                    int messageId = cursor.getInt(0);

                    Uri updateUri = ContentUris.withAppendedId(UPDATE_STATUS_URI, messageId);

                    if(D) Log.d(TAG, "actionMessageDelivery: uri=" + messageUri + ", status=" + msgInfo.statusDelivered);

                    ContentValues contentValues = new ContentValues(2);

                    contentValues.put(Sms.STATUS, msgInfo.statusDelivered);
                    contentValues.put(Inbox.DATE_SENT, System.currentTimeMillis());
                    mResolver.update(updateUri, contentValues, null, null);
                } else {
                    Log.d(TAG, "Can't find message for status update: " + messageUri);
                }
            } finally {
                cursor.close();
            }

            if (msgInfo.statusDelivered == 0) {
                Event evt = new Event(EVENT_TYPE_DELEVERY_SUCCESS, msgInfo.id,
                    folderSms[Sms.MESSAGE_TYPE_SENT], null, mSmsType);
                sendEvent(evt);
            } else {
                Event evt = new Event(EVENT_TYPE_SENDING_FAILURE, msgInfo.id,
                    folderSms[Sms.MESSAGE_TYPE_SENT], null, mSmsType);
                sendEvent(evt);
            }

            mPushMsgList.remove(msgInfo.id);
        }
    }

    static public void actionMessageSentDisconnected(Context context, Intent intent, int result) {
        boolean delete = false;
        //int retry = intent.getIntExtra(EXTRA_MESSAGE_SENT_RETRY, 0);
        int transparent = intent.getIntExtra(EXTRA_MESSAGE_SENT_TRANSPARENT, 0);
        String uriString = intent.getStringExtra(EXTRA_MESSAGE_SENT_URI);
        if(uriString == null) {
            // Nothing we can do about it, just bail out
            return;
        }
        Uri uri = Uri.parse(uriString);

        if (result == Activity.RESULT_OK) {
            Log.d(TAG, "actionMessageSentDisconnected: result OK");
            if (transparent == 0) {
                if (!Sms.moveMessageToFolder(context, uri,
                        Sms.MESSAGE_TYPE_SENT, 0)) {
                    Log.d(TAG, "Failed to move " + uri + " to SENT");
                }
            } else {
                delete = true;
            }
        } else {
            /*if (retry == 1) {
                 The retry feature only works while connected, else we fail the send,
                 * and move the message to failed, to let the user/app resend manually later.
            } else */{
                if (transparent == 0) {
                    if (!Sms.moveMessageToFolder(context, uri,
                            Sms.MESSAGE_TYPE_FAILED, 0)) {
                        Log.d(TAG, "Failed to move " + uri + " to FAILED");
                    }
                } else {
                    delete = true;
                }
            }
        }

        if (delete == true) {
            /* Delete from DB */
            ContentResolver resolver = context.getContentResolver();
            if(resolver != null) {
                resolver.delete(uri, null, null);
            } else {
                Log.w(TAG, "Unable to get resolver");
            }
        }
    }

    private void registerPhoneServiceStateListener() {
        TelephonyManager tm = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
        tm.listen(mPhoneListener, PhoneStateListener.LISTEN_SERVICE_STATE);
    }

    private void unRegisterPhoneServiceStateListener() {
        TelephonyManager tm = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
        tm.listen(mPhoneListener, PhoneStateListener.LISTEN_NONE);
    }

    private void resendPendingMessages() {
        /* Send pending messages in outbox */
        String where = "type = " + Sms.MESSAGE_TYPE_OUTBOX;
        Cursor c = mResolver.query(Sms.CONTENT_URI, SMS_PROJECTION, where, null, null);

        try {
            while (c!= null && c.moveToNext()) {
                long id = c.getLong(c.getColumnIndex(Sms._ID));
                String msgBody = c.getString(c.getColumnIndex(Sms.BODY));
                PushMsgInfo msgInfo = mPushMsgList.get(id);
                if (msgInfo == null || msgInfo.resend == false || msgInfo.sendInProgress == true) {
                    continue;
                }
                msgInfo.sendInProgress = true;
                sendMessage(msgInfo, msgBody);
            }
        } finally {
            close(c);
        }
    }

    private void failPendingMessages() {
        /* Move pending messages from outbox to failed */
        String where = "type = " + Sms.MESSAGE_TYPE_OUTBOX;
        Cursor c = mResolver.query(Sms.CONTENT_URI, SMS_PROJECTION, where, null, null);
        if (c == null) return;

        try {
            while (c!= null && c.moveToNext()) {
                long id = c.getLong(c.getColumnIndex(Sms._ID));
                String msgBody = c.getString(c.getColumnIndex(Sms.BODY));
                PushMsgInfo msgInfo = mPushMsgList.get(id);
                if (msgInfo == null || msgInfo.resend == false) {
                    continue;
                }
                Sms.moveMessageToFolder(mContext, msgInfo.uri,
                    Sms.MESSAGE_TYPE_FAILED, 0);
            }
        } finally {
            close(c);
        }
    }

    private void removeDeletedMessages() {
        /* Remove messages from virtual "deleted" folder (thread_id -1) */
        mResolver.delete(Sms.CONTENT_URI,
                "thread_id = " + DELETED_THREAD_ID, null);
    }

    private PhoneStateListener mPhoneListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            Log.d(TAG, "Phone service state change: " + serviceState.getState());
            if (serviceState.getState() == ServiceState.STATE_IN_SERVICE) {
                resendPendingMessages();
            }
        }
    };

    public void init() {
        mSmsBroadcastReceiver.register();
        registerPhoneServiceStateListener();
        mInitialized = true;
    }

    public void deinit() {
        mInitialized = false;
        unregisterObserver();
        mSmsBroadcastReceiver.unregister();
        unRegisterPhoneServiceStateListener();
        failPendingMessages();
        removeDeletedMessages();
    }

    public boolean handleSmsSendIntent(Context context, Intent intent){
        if(mInitialized) {
            mSmsBroadcastReceiver.onReceive(context, intent);
            return true;
        }
        return false;
    }
}
