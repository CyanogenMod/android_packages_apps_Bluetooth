/*
* Copyright (c) 2013, The Linux Foundation. All rights reserved.
* Not a Contribution.
* Copyright (C) 2013 Samsung System LSI
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

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Collection;
import org.xmlpull.v1.XmlSerializer;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.provider.BaseColumns;
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
import android.database.sqlite.SQLiteException;
import android.util.Log;
import android.util.Xml;
import android.os.Looper;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.Mailbox;

import com.android.bluetooth.map.BluetoothMapUtils.TYPE;
import com.android.bluetooth.map.BluetoothMapbMessageMmsEmail.MimePart;
import com.google.android.mms.pdu.PduHeaders;
import android.text.format.Time;

public class BluetoothMapContentEmailObserver extends BluetoothMapContentObserver {
    private static final String TAG = "BluetoothMapContentEmailObserver";
    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = BluetoothMapService.VERBOSE;

    private HashMap<Long, EmailBox> mEmailBoxList = new HashMap<Long, EmailBox>();
    private HashMap<Long, EmailMessage> mEmailList = new HashMap<Long, EmailMessage>();
    /** List of deleted message, do not notify */
    private HashMap<Long, EmailMessage> mDeletedList = new HashMap<Long, EmailMessage>();
    private HashMap<Long, EmailMessage> mEmailAddedList = new HashMap<Long, EmailMessage>();
    /** List of newly deleted message, notify */
    private HashMap<Long, EmailMessage> mEmailDeletedList = new HashMap<Long, EmailMessage>();
    public static final int EMAIL_BOX_COLUMN_RECORD_ID = 0;
    public static final int EMAIL_BOX_COLUMN_DISPLAY_NAME = 1;
    public static final int EMAIL_BOX_COLUMN_ACCOUNT_KEY = 2;
    public static final int EMAIL_BOX_COLUMN_TYPE = 3;
    public static final String AUTHORITY = "com.android.email.provider";
    public static final Uri EMAIL_URI = Uri.parse("content://" + AUTHORITY);
    public static final Uri EMAIL_ACCOUNT_URI = Uri.withAppendedPath(EMAIL_URI, "account");
    public static final Uri EMAIL_BOX_URI = Uri.withAppendedPath(EMAIL_URI, "mailbox");
    public static final Uri EMAIL_MESSAGE_URI = Uri.withAppendedPath(EMAIL_URI, "message");
    public static final int MSG_COL_RECORD_ID = 0;
    public static final int MSG_COL_MAILBOX_KEY = 1;
    public static final int MSG_COL_ACCOUNT_KEY = 2;
    private static final String EMAIL_TO_MAP[] = {
        "inbox",    // TYPE_INBOX = 0;
        "",         // TYPE_MAIL = 1;
        "",         // TYPE_PARENT = 2;
        "draft",    // TYPE_DRAFTS = 3;
        "outbox",   // TYPE_OUTBOX = 4;
        "sent",     // TYPE_SENT = 5;
        "deleted",  // TYPE_TRASH = 6;
        ""          // TYPE_JUNK = 7;
    };

    // Types of mailboxes. From EmailContent.java
    // inbox
    public static final int TYPE_INBOX = 0;
    // draft
    public static final int TYPE_DRAFT = 3;
    // outbox
    public static final int TYPE_OUTBOX = 4;
    // sent
    public static final int TYPE_SENT = 5;
    // deleted
    public static final int TYPE_DELETED = 6;

    private long mAccountKey;
    private Handler mCallback = null;
    private static final int UPDATE = 0;
    private static final int THRESHOLD = 3000;  // 3 sec

    public static final String RECORD_ID = "_id";
    public static final String ACCOUNT_KEY = "accountKey";
    public static final String DISPLAY_NAME = "displayName";
    public static final String EMAILTYPE = "type";
    public static final String MAILBOX_KEY = "mailboxKey";
    public static final String EMAIL_ADDRESS = "emailAddress";
    public static final String IS_DEFAULT = "isDefault";
    private static final String[] ACCOUNT_ID_PROJECTION = new String[] {
        RECORD_ID, EMAIL_ADDRESS, IS_DEFAULT, DISPLAY_NAME
    };
    public static final String[] EMAIL_MESSAGE_PROJECTION = new String[] {
        RECORD_ID, MAILBOX_KEY, ACCOUNT_KEY
    };
    public static final String[] EMAIL_BOX_PROJECTION = new String[] {
        RECORD_ID, DISPLAY_NAME, ACCOUNT_KEY, EMAILTYPE
    };
    public long id;
    public String folderName;

    public BluetoothMapContentEmailObserver(final Context context, Handler callback ) {
        super(context);
        mCallback =callback;
    }

    private final ContentObserver mEmailAccountObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
           if (V) Log.v(TAG, "onChange on thread");
           if (BluetoothMapUtils.getEmailAccountId(mContext) == -1) {
               if (mCallback != null) {
                   Message msg = Message.obtain(mCallback);
                   msg.what = BluetoothMapService.MSG_SERVERSESSION_CLOSE;
                   msg.arg1 = 1;
                   msg.sendToTarget();
                   if (D) Log.d(TAG, "onClose(): msg MSG_SERVERSESSION_CLOSE sent out.");
               }
           }
           super.onChange(selfChange);
        }
    };

    private final ContentObserver mObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (V) Log.v(TAG, "onChange on thread: " + Thread.currentThread().getId()
                + " Uri: " + uri.toString() + " selfchange: " + selfChange);

             if (mHandler.hasMessages(UPDATE)) {
                 mHandler.removeMessages(UPDATE);
             }
            mHandler.sendEmptyMessageDelayed(UPDATE, THRESHOLD);
        }
        private Handler mHandler = new Handler(Looper.getMainLooper()) {
            private static final String TAG = "EmailContentObserver.Hanlder";
            @Override
            public void handleMessage(android.os.Message msg) {
                if (V) Log.v(TAG, "handleMessage(" + msg.what + ") mas Id: " + mMasId);
                switch (msg.what) {
                    case UPDATE:
                        new Thread(new Runnable() {
                            public void run() {
                                updateEmailBox();
                                update(false);
                                sendEvents();
                            }
                        }, "Email Content Observer Thread").start();
                        break;
                }
            }
        };
    };

    private boolean isMapFolder(int type) {
        if (type == TYPE_INBOX || type == TYPE_OUTBOX || type == TYPE_SENT ||
                         type == TYPE_DRAFT || type == TYPE_DELETED) {
            return true;
        }
        return false;
    }

    static class EmailBox {
        long mId;
        String mDisplayName;
        long mAccountKey;
        int mType;

        public EmailBox(long id, String displayName, long accountKey, int type) {
               mId = id;
               mDisplayName = displayName;
               mAccountKey = accountKey;
               mType = type;
        }

        @Override
        public String toString() {
               return "[id:" + mId + ", display name:" + mDisplayName + ", account key:" +
                               mAccountKey + ", type:" + mType + "]";
         }
    }

    static class EmailMessage {
         long mId;
         long mAccountKey;
         String mFolderName;
         int mType;

         public EmailMessage(long id, long accountKey, String folderName, int type) {
                mId = id;
                mAccountKey = accountKey;
                mFolderName = folderName;
                mType = type;
         }

         @Override
         public String toString() {
                return "[id:" + mId + ", folder name:" + mFolderName + ", account key:" + mAccountKey +
                                      ", type:" + mType + "]";
         }
    }

    @Override
    public void registerObserver(BluetoothMnsObexClient mns, int masId) {
        if (D) Log.d(TAG, "registerObserver");
        mAccountKey = BluetoothMapUtils.getEmailAccountId(mContext);
        mMasId = masId;
        mMnsClient = mns;
        updateEmailBox();
        update(true);
        try {
            mResolver.registerContentObserver(EMAIL_URI, true, mObserver);
        } catch (SQLiteException e) {
            Log.e(TAG, "SQLite exception: " + e);
        }
    }

    void updateEmailBox() {
        if (D) Log.d(TAG, "updateEmailBox");
        mEmailBoxList.clear();
        final ContentResolver resolver = mContext.getContentResolver();
        Cursor crBox;
        try {
            crBox = resolver.query(EMAIL_BOX_URI, EMAIL_BOX_PROJECTION, null, null, null);
            if (crBox != null) {
                if (crBox.moveToFirst()) {
                    do {
                       final long id = crBox.getLong(EMAIL_BOX_COLUMN_RECORD_ID);
                       final String displayName = crBox.getString(EMAIL_BOX_COLUMN_DISPLAY_NAME);
                       final long accountKey = crBox.getLong(EMAIL_BOX_COLUMN_ACCOUNT_KEY);
                       final int type = crBox.getInt(EMAIL_BOX_COLUMN_TYPE);
                       final EmailBox box = new EmailBox(id, displayName, accountKey, type);
                       mEmailBoxList.put(id, box);
                       if (V) Log.v(TAG, box.toString());
                    } while (crBox.moveToNext());
                }
                crBox.close();
            }
        } catch (SQLiteException e) {
                    crBox = null;
                    Log.e(TAG, "SQLite exception: " + e);
         }
    }

    private void clear() {
        mEmailList.clear();
        mDeletedList.clear();
        mEmailAddedList.clear();
        mEmailDeletedList.clear();
    }

    void update(boolean init) {
        if (V) Log.d(TAG, "update");
        if (init) {
            clear();
        }
        mEmailAddedList.clear();
        mEmailDeletedList.clear();

        final ContentResolver resolver = mContext.getContentResolver();
        Cursor crEmail;
        try {
        crEmail = resolver.query(EMAIL_MESSAGE_URI, EMAIL_MESSAGE_PROJECTION,
                null, null, null);
        if (crEmail != null) {
            if (crEmail.moveToFirst()) {
                final HashMap<Long, EmailBox> boxList = mEmailBoxList;
                HashMap<Long, EmailMessage> oldEmailList = mEmailList;
                HashMap<Long, EmailMessage> emailList = new HashMap<Long, EmailMessage>();
                do {
                    final long accountKey = crEmail.getLong(MSG_COL_ACCOUNT_KEY);
                    if (accountKey != mAccountKey) {
                        continue;
                    }
                    id = crEmail.getLong(MSG_COL_RECORD_ID);
                    final long mailboxKey = crEmail.getLong(MSG_COL_MAILBOX_KEY);
                    if (boxList.containsKey(mailboxKey)) {
                        final EmailBox box = boxList.get(mailboxKey);
                        if (box == null) {
                            continue;
                        }
                        folderName = isMapFolder(box.mType)
                                   ? EMAIL_TO_MAP[box.mType] : box.mDisplayName;
                        final EmailMessage msg = new EmailMessage(id, accountKey,
                            folderName, box.mType);
                        if (box.mType == TYPE_DELETED) {
                            if (init) {
                                mDeletedList.put(id, msg);
                            } else if (!mDeletedList.containsKey(id) &&
                                        !mEmailDeletedList.containsKey(id)) {
                                if(V) Log.v(TAG,"Putting in deleted list");
                                mEmailDeletedList.put(id, msg);
                            }
                        } else if (box.mType == TYPE_OUTBOX) {
                                // Do nothing got outbox folder
                        } else {
                                emailList.put(id, msg);
                                if (!oldEmailList.containsKey(id) && !init &&
                                        !mEmailAddedList.containsKey(id)) {
                                    Log.v(TAG,"Putting in added list");
                                    mEmailAddedList.put(id, msg);
                                }
                        }
                    } else {
                    Log.e(TAG, "Mailbox is not updated");
                    }
                } while (crEmail.moveToNext());
                mEmailList = emailList;
            }
            crEmail.close();
        }
        } catch (SQLiteException e) {
            crEmail = null;
            Log.e(TAG, "SQLite exception: " + e);
        }
    }

    private void sendEvents() {
       if (mEmailAddedList.size() > 0) {
           Event evt;
           Collection<EmailMessage> values = mEmailAddedList.values();
           if(values != null) {
           for (EmailMessage email : values) {
                if (email.mFolderName.equalsIgnoreCase("sent")) {
                    if(D) Log.d(TAG,"sending SendingSuccess mns event");
                    if(D) Log.d(TAG,"email.mId is "+email.mId);
                    if(D) Log.d(TAG,"email.mType is "+email.mType);
                    if(D) Log.d(TAG,"folder name is "+email.mFolderName);
                    evt = new Event("SendingSuccess", email.mId, email.mFolderName,
                                              null, TYPE.EMAIL);
                    sendEvent(evt);
                } else {
                    if(D) Log.d(TAG,"sending NewMessage mns event");
                    if(D) Log.d(TAG,"email.mId is "+email.mId);
                    if(D) Log.d(TAG,"email.mType is "+email.mType);
                    if(D) Log.d(TAG,"folder name is "+email.mFolderName);
                    evt = new Event("NewMessage", email.mId, folderName,
                                     null, TYPE.EMAIL);
                    sendEvent(evt);
                }
              }
           }
          mEmailAddedList.clear();
       }

       if (mEmailDeletedList.size() > 0) {
           mDeletedList.putAll(mEmailDeletedList);
           Collection<EmailMessage> values = mEmailDeletedList.values();
            for (EmailMessage email : values) {
                 if(D) Log.d(TAG,"sending MessageDeleted mns event");
                        if(D) Log.d(TAG,"email.mId is "+email.mId);
                        if(D) Log.d(TAG,"email.mType is "+email.mType);
                        if(D) Log.d(TAG,"folder name is "+email.mFolderName);

            Event evt = new Event("MessageDeleted", email.mId, "deleted",
                null, TYPE.EMAIL);
                        sendEvent(evt);
            }
            mEmailDeletedList.clear();
       }
    }

    public void onConnect() {
        if (V) Log.v(TAG, "onConnect() registering email account content observer");
        try {
            mResolver.registerContentObserver(
                EMAIL_ACCOUNT_URI, true, mEmailAccountObserver);
        } catch (SQLiteException e) {
            Log.e(TAG, "SQLite exception: " + e);
        }

    }

    public void onDisconnect() {
        if (V) Log.v(TAG, "onDisconnect() unregistering email account content observer");
        try {
            mResolver.unregisterContentObserver(mEmailAccountObserver);
        } catch (SQLiteException e) {
            Log.e(TAG, "SQLite exception: " + e);
        }
    }

    @Override
    public void unregisterObserver() {
        if (D) Log.d(TAG, "unregisterObserver");
        mResolver.unregisterContentObserver(mObserver);
        mMnsClient = null;
    }

 @Override
    public boolean setMessageStatusDeleted(long handle, TYPE type, int statusValue) {
        boolean res = true;

         long accountId = BluetoothMapUtils.getEmailAccountId(mContext);
        if (D) Log.d(TAG, "setMessageStatusDeleted: EMAIL handle " + handle
            + " type " + type + " value " + statusValue + "accountId: "+accountId);
         Intent emailIn = new Intent();
         if(statusValue == 1){
             addMceInitiatedOperation(Long.toString(handle));
             emailIn.setAction("org.codeaurora.email.intent.action.MAIL_SERVICE_DELETE_MESSAGE");
         }else {
             emailIn.setAction("org.codeaurora.email.intent.action.MAIL_SERVICE_MOVE_MESSAGE");
              emailIn.putExtra("org.codeaurora.email.intent.extra.MESSAGE_INFO", Mailbox.TYPE_INBOX);
         }

         emailIn.putExtra("com.android.email.intent.extra.ACCOUNT", accountId);
         emailIn.putExtra("org.codeaurora.email.intent.extra.MESSAGE_ID", handle);
         mContext.startService(emailIn);
        return res;
    }

    @Override
    public boolean setMessageStatusRead(long handle, TYPE type, int statusValue) {
        boolean res = true;


         Intent emailIn = new Intent();
        long accountId = BluetoothMapUtils.getEmailAccountId(mContext);
        if (D) Log.d(TAG, "setMessageStatusRead: EMAIL handle " + handle
            + " type " + type + " value " + statusValue+ "accounId: " +accountId);

         emailIn.setAction("org.codeaurora.email.intent.action.MAIL_SERVICE_MESSAGE_READ");
         emailIn.putExtra("org.codeaurora.email.intent.extra.MESSAGE_INFO",statusValue);
         emailIn.putExtra("com.android.email.intent.extra.ACCOUNT", accountId);
         emailIn.putExtra("org.codeaurora.email.intent.extra.MESSAGE_ID", handle);
         mContext.startService(emailIn);
        return res;

    }

    /**
     * Adds an Email to the Email ContentProvider
     */
    private long pushEmailToFolder( String folder, String toAddress, BluetoothMapbMessageMmsEmail msg) {
        String msgBody = msg.getEmailBody();
        int folderType = BluetoothMapUtils.getSystemMailboxGuessType(folder);
        int folderId = -1;
        long accountId =  -1;
        String originatorName = "";
        String originatorEmail = "";
        Time timeObj = new Time();
        timeObj.setToNow();
        //Fetch AccountId, originator email and displayName from DB.
        Cursor cr =  mContext.getContentResolver().query(EMAIL_ACCOUNT_URI,
                ACCOUNT_ID_PROJECTION, null, null, null);
        if (cr != null) {
            if (cr.moveToFirst()) {
                    accountId = cr.getLong(0);
                    Log.v(TAG, "id = " + accountId);
                    originatorEmail = cr.getString(1);
                    Log.v(TAG, "email = " + originatorEmail);
                    originatorName = cr.getString(3);
                    Log.v(TAG, "Name = " + originatorName);
            }
            cr.close();
        } else {
           Log.v(TAG, "Account CURSOR NULL");
        }
        if (accountId == -1) {
            Log.v(TAG, "INTERNAL ERROR For ACCNT ID");
            return -1;
        }
        cr=null;
        //Fetch FolderId for Folder Type
        String whereClause = "TYPE = '"+folderType+"'";
        cr = mContext.getContentResolver().query(
                Uri.parse("content://com.android.email.provider/mailbox"),
                null, whereClause, null, null);
        if (cr != null) {
            if (cr.getCount() > 0) {
                cr.moveToFirst();
                folderId = cr.getInt(cr.getColumnIndex("_id"));
            }
            cr.close();
        }
        if (folderId == -1) {
            Log.v(TAG, "INTERNAL ERROR For Folder ID ");
            return -1;
        }
        if (V){
            Log.v(TAG, "-------------");
            Log.v(TAG, "To address " + toAddress);
            Log.v(TAG, "Text " + msgBody);
            Log.v(TAG, "Originator email address:: " + originatorEmail);
            Log.v(TAG, "Originator email name:: " + originatorName);
            Log.v(TAG, "Time Stamp:: " + timeObj.toMillis(false));
            Log.v(TAG, "Account Key:: " + accountId);
            Log.v(TAG, "Folder Id:: " + folderId);
            Log.v(TAG, "Folder Name:: " + folder);
            Log.v(TAG, "Subject" + msg.getSubject());
        }
        ContentValues values = new ContentValues();
        values.put("syncServerTimeStamp", 0);
        values.put("syncServerId", "5:65");
        values.put("displayName", originatorName);
        values.put("timeStamp", timeObj.toMillis(false));
        values.put("subject", msg.getSubject().trim());
        values.put("flagLoaded", "1");
        values.put("flagFavorite", "0");
        values.put("flagAttachment", "0");
		if(folderType == Mailbox.TYPE_DRAFTS)
	        values.put("flags", "1179648");
		else
			values.put("flags", "0");
        values.put("accountKey", accountId);
        values.put("fromList", originatorEmail.trim());

        values.put("mailboxKey", folderId);
        values.put("toList", toAddress.trim());
        values.put("flagRead", 0);
        Uri uri = mContext.getContentResolver().insert(
                Uri.parse("content://com.android.email.provider/message"), values);
        if (V){
            Log.v(TAG, " NEW URI " + (uri == null ? "null" : uri.toString()));
        }
        if (uri == null) {
            Log.v(TAG, "INTERNAL ERROR : NEW URI NULL");
            return -1;
        }
        String str = uri.toString();
        Log.v(TAG, " CREATE URI " + str);
        String[] splitStr = str.split("/");
        if (splitStr.length < 5) {
            return -1;
        }
        if (V){
            Log.v(TAG, " NEW HANDLE " + splitStr[4]);
        }
        //Insert msgBody in DB Provider BODY TABLE
        ContentValues valuesBody = new ContentValues();
        valuesBody.put("messageKey", splitStr[4]);
        valuesBody.put("textContent", msgBody);

        mContext.getContentResolver().insert(
        Uri.parse("content://com.android.email.provider/body"), valuesBody);
        long msgId;
        msgId = Long.valueOf(splitStr[4]);
		return msgId;

    }

    @Override
    public long sendEmailMessage(String folder, String[] toList, BluetoothMapbMessageMmsEmail msg) {
        Log.d(TAG, "sendMessage for " + folder);
        /*
         *strategy:
         *1) parse message into parts
         *if folder is drafts:
         *2) push message to draft
         *if folder is outbox:
         *3) push message to outbox (to trigger the email app to add msg to pending_messages list)
         *4) send intent to email app in order to wake it up.
         *else if folder draft(s):
         *1) push message to folder
         * */
        if (folder != null && (folder.equalsIgnoreCase("outbox")||  folder.equalsIgnoreCase("drafts"))) {
            //Consolidate All recipients as toList
            StringBuilder address = new StringBuilder();
            for(String s : toList) {
              address.append(s);
              address.append(";");
            }
            long handle = pushEmailToFolder(folder, address.toString(), msg);
            addMceInitiatedOperation("+");
            /* if invalid handle (-1) then just return the handle - else continue sending (if folder is outbox) */
            if (BluetoothMapAppParams.INVALID_VALUE_PARAMETER != handle && folder.equalsIgnoreCase("outbox")) {
               Intent emailIn = new Intent();
                long accountId = BluetoothMapUtils.getEmailAccountId(mContext);
                Log.d(TAG, "pushToEmail : handle SEND MAIL" + handle + "accounId: " +accountId);
               emailIn.setAction("org.codeaurora.email.intent.action.MAIL_SERVICE_SEND_PENDING");
               emailIn.putExtra("com.android.email.intent.extra.ACCOUNT", accountId);
               mContext.startService(emailIn);
            }
            return handle;
        } else {
            /* not allowed to push email to anything but outbox/drafts */
            throw  new IllegalArgumentException("Cannot push email message to other folders than outbox/drafts");
        }
    }
    @Override
    public void init() {
        Log.d(TAG, "init ");
        onConnect();
    }
    @Override
    public void deinit() {
        Log.d(TAG, "deinit ");
        onDisconnect();
    }

}
