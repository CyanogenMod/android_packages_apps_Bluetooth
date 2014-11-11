/*
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
import java.util.List;

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
import android.text.format.Time;
import android.os.Handler;
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
import android.util.Log;
import android.util.Xml;
import android.os.Looper;
import android.os.Message;

import com.android.bluetooth.map.BluetoothMapUtils.TYPE;
import com.android.bluetooth.map.BluetoothMapbMessageMmsEmail.MimePart;
import com.google.android.mms.pdu.PduHeaders;

public class BluetoothMapContentObserver {
    private static final String TAG = "BluetoothMapContentObserver";

    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = Log.isLoggable(BluetoothMapService.LOG_TAG, Log.VERBOSE) ? true : false;

    protected Context mContext;
    protected ContentResolver mResolver;
    protected BluetoothMnsObexClient mMnsClient;
    protected int mMasId;

    public static final int DELETED_THREAD_ID = -1;

    /* X-Mms-Message-Type field types. These are from PduHeaders.java */
    public static final int MESSAGE_TYPE_RETRIEVE_CONF = 0x84;

    private TYPE mSmsType;

    static final String[] SMS_PROJECTION = new String[] {
        BaseColumns._ID,
        Sms.THREAD_ID,
        Sms.ADDRESS,
        Sms.BODY,
        Sms.DATE,
        Sms.READ,
        Sms.TYPE,
        Sms.STATUS,
        Sms.LOCKED,
        Sms.ERROR_CODE,
    };

    static final String[] MMS_PROJECTION = new String[] {
        BaseColumns._ID,
        Mms.THREAD_ID,
        Mms.MESSAGE_ID,
        Mms.MESSAGE_SIZE,
        Mms.SUBJECT,
        Mms.CONTENT_TYPE,
        Mms.TEXT_ONLY,
        Mms.DATE,
        Mms.DATE_SENT,
        Mms.READ,
        Mms.MESSAGE_BOX,
        Mms.MESSAGE_TYPE,
        Mms.STATUS,
    };

    public BluetoothMapContentObserver(final Context context) {
        mContext = context;
        mResolver = mContext.getContentResolver();

        mSmsType = getSmsType();
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
            if (V && uri!= null)
                Log.d(TAG, "onChange on thread: " + Thread.currentThread().getId()
                   + " Uri: " + uri.toString() + " selfchange: " + selfChange);

            handleMsgListChanges();
        }
    };

    private static final String folderSms[] = {
        "",
        "inbox",
        "sent",
        "draft",
        "outbox",
        "outbox",
        "outbox",
        "inbox",
        "inbox",
    };

    private static final String folderMms[] = {
        "",
        "inbox",
        "sent",
        "draft",
        "outbox",
    };

    public class Event {
        String eventType;
        long handle;
        String folder;
        String oldFolder;
        TYPE msgType;

        public Event(String eventType, long handle, String folder,
            String oldFolder, TYPE msgType) {
            String PATH = "telecom/msg/";
            this.eventType = eventType;
            this.handle = handle;
            if (folder != null) {
                this.folder = PATH + folder;
            } else {
                this.folder = null;
            }
            if (oldFolder != null) {
                this.oldFolder = PATH + oldFolder;
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
                xmlEvtReport.text("\n");
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
                e.printStackTrace();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (V) System.out.println(sw.toString());

            return sw.toString().getBytes("UTF-8");
        }
    }

    public class Msg {
        long id;
        int type;

        public Msg(long id, int type) {
            this.id = id;
            this.type = type;
        }
    }

    private Map<Long, Msg> mMsgListSms =
        Collections.synchronizedMap(new HashMap<Long, Msg>());

    private Map<Long, Msg> mMsgListMms =
        Collections.synchronizedMap(new HashMap<Long, Msg>());

    /*
     * Class to hold message handle for MCE Initiated operation
     */
    public class BluetoothMnsMsgHndlMceInitOp {
        public String msgHandle;
        Time time;
    }

    /*
     * Keep track of Message Handles on which the operation was
     * initiated by MCE
     */
    List<BluetoothMnsMsgHndlMceInitOp> opList = new ArrayList<BluetoothMnsMsgHndlMceInitOp>();

    /*
     * Adds the Message Handle to the list for tracking
     * MCE initiated operation
     */
    public void addMceInitiatedOperation(String msgHandle) {
        if (V) Log.v(TAG, "addMceInitiatedOperation for handle " + msgHandle);
        BluetoothMnsMsgHndlMceInitOp op = new BluetoothMnsMsgHndlMceInitOp();
        op.msgHandle = msgHandle;
        op.time = new Time();
        op.time.setToNow();
        opList.add(op);
    }
    /*
     * Removes the Message Handle from the list for tracking
     * MCE initiated operation
     */
    public void removeMceInitiatedOperation(int location) {
        if (V) Log.v(TAG, "removeMceInitiatedOperation for location " + location);
        opList.remove(location);
    }

    /*
     * Finds the location in the list of the given msgHandle, if
     * available. "+" indicates the next (any) operation
     */
    public int findLocationMceInitiatedOperation( String msgHandle) {
        int location = -1;

        Time currentTime = new Time();
        currentTime.setToNow();

        if (V) Log.v(TAG, "findLocationMceInitiatedOperation " + msgHandle);

        for (BluetoothMnsMsgHndlMceInitOp op: opList) {
            if (op.msgHandle.equalsIgnoreCase(msgHandle)){
                location = opList.indexOf(op);
                opList.remove(op);
                break;
            }
        }

        if (location == -1) {
            for (BluetoothMnsMsgHndlMceInitOp op: opList) {
                if (op.msgHandle.equalsIgnoreCase("+")) {
                    location = opList.indexOf(op);
                    break;
                }
            }
        }
        if (V) Log.v(TAG, "findLocationMce loc" + location);
        return location;
    }


    public void registerObserver(BluetoothMnsObexClient mns, int masId) {
        if (V) Log.d(TAG, "registerObserver");
        /* Use MmsSms Uri since the Sms Uri is not notified on deletes */
        mMasId = masId;
        mMnsClient = mns;
        mResolver.registerContentObserver(MmsSms.CONTENT_URI, false, mObserver);
        initMsgList();
    }

    public void unregisterObserver() {
        if (V) Log.d(TAG, "unregisterObserver");
        mResolver.unregisterContentObserver(mObserver);
        mMnsClient = null;
    }

    public void sendEvent(Event evt) {
        Log.d(TAG, "sendEvent: " + evt.eventType + " " + evt.handle + " "
        + evt.folder + " " + evt.oldFolder + " " + evt.msgType.name());
        int location = -1;

        if (mMnsClient == null || mMnsClient.isConnected() == false) {
            Log.d(TAG, "sendEvent: No MNS client registered or connected- don't send event");
            return;
        }
        String msgHandle = BluetoothMapUtils.getMapHandle(evt.handle,evt.msgType);
        Log.d(TAG, "msgHandle is "+msgHandle);
        location = findLocationMceInitiatedOperation(Long.toString(evt.handle));
        Log.d(TAG, "location is "+location);
        // 'SendingSuccess' is triggered only for MCE initiated case
        if(location == -1 || evt.eventType.equalsIgnoreCase("SendingSuccess")) {
            try {
                mMnsClient.sendEvent(evt.encode(), mMasId);
            } catch (UnsupportedEncodingException ex) {
                Log.w(TAG, ex);
            }
        } else {
            Log.d(TAG, "Not MCE initiated operation" +location);
            return;
        }
    }

    private void initMsgList() {
        if (V) Log.d(TAG, "initMsgList");

        mMsgListSms.clear();
        mMsgListMms.clear();

        HashMap<Long, Msg> msgListSms = new HashMap<Long, Msg>();

        Cursor c = mResolver.query(Sms.CONTENT_URI,
            SMS_PROJECTION, null, null, null);

        if (c != null && c.moveToFirst()) {
            do {
                long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                int type = c.getInt(c.getColumnIndex(Sms.TYPE));

                Msg msg = new Msg(id, type);
                msgListSms.put(id, msg);
            } while (c.moveToNext());
            c.close();
        }

        mMsgListSms = msgListSms;

        HashMap<Long, Msg> msgListMms = new HashMap<Long, Msg>();

        c = mResolver.query(Mms.CONTENT_URI,
            MMS_PROJECTION, null, null, null);

        if (c != null && c.moveToFirst()) {
            do {
                long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                int type = c.getInt(c.getColumnIndex(Mms.MESSAGE_BOX));

                Msg msg = new Msg(id, type);
                msgListMms.put(id, msg);
            } while (c.moveToNext());
            c.close();
        }

        mMsgListMms = msgListMms;
    }

    private void handleMsgListChangesSms() {
        if (V) Log.d(TAG, "handleMsgListChangesSms");

        HashMap<Long, Msg> msgListSms = new HashMap<Long, Msg>();

        Cursor c = mResolver.query(Sms.CONTENT_URI,
            SMS_PROJECTION, null, null, null);

        synchronized(mMsgListSms) {
            if (c != null && c.moveToFirst()) {
                do {
                    long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                    int type = c.getInt(c.getColumnIndex(Sms.TYPE));

                    Msg msg = mMsgListSms.remove(id);

                    if (msg == null) {
                        /* New message */
                        msg = new Msg(id, type);
                        msgListSms.put(id, msg);

                        if (folderSms[type].equals("inbox")) {
                            Event evt = new Event("NewMessage", id, folderSms[type],
                                null, mSmsType);
                            sendEvent(evt);
                        }
                    } else {
                        /* Existing message */
                        if (type != msg.type) {
                            Log.d(TAG, "new type: " + type + " old type: " + msg.type);
                            Event evt = new Event("MessageShift", id, folderSms[type],
                                folderSms[msg.type], mSmsType);
                            sendEvent(evt);
                            msg.type = type;
                        }
                        msgListSms.put(id, msg);
                    }
                } while (c.moveToNext());
                c.close();
            }

            for (Msg msg : mMsgListSms.values()) {
                Event evt = new Event("MessageDeleted", msg.id, "deleted",
                    null, mSmsType);
                sendEvent(evt);
            }

            mMsgListSms = msgListSms;
        }
    }

    private void handleMsgListChangesMms() {
        if (V) Log.d(TAG, "handleMsgListChangesMms");

        HashMap<Long, Msg> msgListMms = new HashMap<Long, Msg>();

        Cursor c = mResolver.query(Mms.CONTENT_URI,
            MMS_PROJECTION, null, null, null);

        synchronized(mMsgListMms) {
            if (c != null && c.moveToFirst()) {
                do {
                    long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                    int type = c.getInt(c.getColumnIndex(Mms.MESSAGE_BOX));
                    int mtype = c.getInt(c.getColumnIndex(Mms.MESSAGE_TYPE));

                    Msg msg = mMsgListMms.remove(id);

                    if (msg == null) {
                        /* New message - only notify on retrieve conf */
                        if (folderMms[type].equals("inbox") &&
                            mtype != MESSAGE_TYPE_RETRIEVE_CONF) {
                                continue;
                        }

                        msg = new Msg(id, type);
                        msgListMms.put(id, msg);

                        if (folderMms[type].equals("inbox")) {
                            Event evt = new Event("NewMessage", id, folderMms[type],
                                null, TYPE.MMS);
                            sendEvent(evt);
                        }
                    } else {
                        /* Existing message */
                        if (type != msg.type) {
                            Log.d(TAG, "new type: " + type + " old type: " + msg.type);
                            Event evt = new Event("MessageShift", id, folderMms[type],
                                folderMms[msg.type], TYPE.MMS);
                            sendEvent(evt);
                            msg.type = type;

                            // Trigger 'SendingSuccess' for MMS ONLY when local initiated
                            int loc = findLocationMceInitiatedOperation(Long.toString(id));
                            if (folderMms[type].equals("sent")&& loc != -1) {
                                evt = new Event("SendingSuccess", id,
                                    folderMms[type], null, TYPE.MMS);
                                sendEvent(evt);
                                removeMceInitiatedOperation(loc);
                            }
                        }
                        msgListMms.put(id, msg);
                    }
                } while (c.moveToNext());
                c.close();
            }

            for (Msg msg : mMsgListMms.values()) {
                Event evt = new Event("MessageDeleted", msg.id, "deleted",
                    null, TYPE.MMS);
                sendEvent(evt);
            }

            mMsgListMms = msgListMms;
        }
    }

    private void handleMsgListChanges() {
        handleMsgListChangesSms();
        handleMsgListChangesMms();
    }

    private boolean deleteMessageMms(long handle) {
        boolean res = false;
        Uri uri = ContentUris.withAppendedId(Mms.CONTENT_URI, handle);
        Cursor c = mResolver.query(uri, null, null, null, null);
        if (c != null && c.moveToFirst()) {
            /* Move to deleted folder, or delete if already in deleted folder */
            int threadId = c.getInt(c.getColumnIndex(Mms.THREAD_ID));
            if (threadId != DELETED_THREAD_ID) {
                /* Set deleted thread id */
                ContentValues contentValues = new ContentValues();
                contentValues.put(Mms.THREAD_ID, DELETED_THREAD_ID);
                mResolver.update(uri, contentValues, null, null);
            } else {
                /* Delete from observer message list to avoid delete notifications */
                mMsgListMms.remove(handle);
                /* Delete message */
                mResolver.delete(uri, null, null);
            }
            res = true;
        }
        if (c != null) {
            c.close();
        }
        return res;
    }

    private void updateThreadIdMms(Uri uri, long threadId) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(Mms.THREAD_ID, threadId);
        mResolver.update(uri, contentValues, null, null);
    }

    private boolean unDeleteMessageMms(long handle) {
        boolean res = false;
        Uri uri = ContentUris.withAppendedId(Mms.CONTENT_URI, handle);
        Cursor c = mResolver.query(uri, null, null, null, null);

        if (c != null && c.moveToFirst()) {
            int threadId = c.getInt(c.getColumnIndex(Mms.THREAD_ID));
            if (threadId == DELETED_THREAD_ID) {
                /* Restore thread id from address, or if no thread for address
                 * create new thread by insert and remove of fake message */
                String address;
                long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
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
                updateThreadIdMms(uri, Telephony.Threads.getOrCreateThreadId(mContext, recipients));
            } else {
                Log.d(TAG, "Message not in deleted folder: handle " + handle
                    + " threadId " + threadId);
            }
            res = true;
        }
        if (c != null) {
            c.close();
        }
        return res;
    }

    private boolean deleteMessageSms(long handle) {
        boolean res = false;
        Uri uri = ContentUris.withAppendedId(Sms.CONTENT_URI, handle);
        Cursor c = mResolver.query(uri, null, null, null, null);

        if (c != null && c.moveToFirst()) {
            /* Move to deleted folder, or delete if already in deleted folder */
            int threadId = c.getInt(c.getColumnIndex(Sms.THREAD_ID));
            if (threadId != DELETED_THREAD_ID) {
                /* Set deleted thread id */
                ContentValues contentValues = new ContentValues();
                contentValues.put(Sms.THREAD_ID, DELETED_THREAD_ID);
                mResolver.update(uri, contentValues, null, null);
            } else {
                /* Delete from observer message list to avoid delete notifications */
                mMsgListSms.remove(handle);
                /* Delete message */
                mResolver.delete(uri, null, null);
            }
            res = true;
        }
        if (c != null) {
            c.close();
        }
        return res;
    }

    private void updateThreadIdSms(Uri uri, long threadId) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(Sms.THREAD_ID, threadId);
        mResolver.update(uri, contentValues, null, null);
    }

    private boolean unDeleteMessageSms(long handle) {
        boolean res = false;
        Uri uri = ContentUris.withAppendedId(Sms.CONTENT_URI, handle);
        Cursor c = mResolver.query(uri, null, null, null, null);

        if (c != null && c.moveToFirst()) {
            int threadId = c.getInt(c.getColumnIndex(Sms.THREAD_ID));
            if (threadId == DELETED_THREAD_ID) {
                String address = c.getString(c.getColumnIndex(Sms.ADDRESS));
                Set<String> recipients = new HashSet<String>();
                recipients.addAll(Arrays.asList(address));
                updateThreadIdSms(uri, Telephony.Threads.getOrCreateThreadId(mContext, recipients));
            } else {
                Log.d(TAG, "Message not in deleted folder: handle " + handle
                    + " threadId " + threadId);
            }
            res = true;
        }
        if (c != null) {
            c.close();
        }
        return res;
    }

    public boolean setMessageStatusDeleted(long handle, TYPE type, int statusValue) {
        boolean res = false;
        if (D) Log.d(TAG, "setMessageStatusDeleted: handle " + handle
            + " type " + type + " value " + statusValue);

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
        return res;
    }

    public boolean setMessageStatusRead(long handle, TYPE type, int statusValue) {
        boolean res = true;

        Cursor c = null;
        if (D) Log.d(TAG, "setMessageStatusRead: handle " + handle
            + " type " + type + " value " + statusValue);

        /* Approved MAP spec errata 3445 states that read status initiated */
        /* by the MCE shall change the MSE read status. */

        if (type == TYPE.SMS_GSM || type == TYPE.SMS_CDMA) {
            Uri uri = ContentUris.withAppendedId(Sms.CONTENT_URI, handle);
            c = mResolver.query(uri, null, null, null, null);

            ContentValues contentValues = new ContentValues();
            contentValues.put(Sms.READ, statusValue);
            mResolver.update(uri, contentValues, null, null);
        } else if (type == TYPE.MMS) {
            Uri uri = ContentUris.withAppendedId(Mms.CONTENT_URI, handle);
            c = mResolver.query(uri, null, null, null, null);

            ContentValues contentValues = new ContentValues();
            contentValues.put(Mms.READ, statusValue);
            mResolver.update(uri, contentValues, null, null);
        }

        if (c !=null)
            c.close();
        return res;
    }

    protected class PushMsgInfo {
        long id;
        int transparent;
        int retry;
        String phone;
        Uri uri;
        int parts;
        int partsSent;
        int partsDelivered;
        boolean resend;

        public PushMsgInfo(long id, int transparent,
            int retry, String phone, Uri uri) {
            this.id = id;
            this.transparent = transparent;
            this.retry = retry;
            this.phone = phone;
            this.uri = uri;
            this.resend = false;
        };
    }

    private Map<Long, PushMsgInfo> mPushMsgList =
        Collections.synchronizedMap(new HashMap<Long, PushMsgInfo>());

    public long pushMessage(BluetoothMapbMessage msg, String folder,
        BluetoothMapAppParams ap) throws IllegalArgumentException {
        if (D) Log.d(TAG, "pushMessage");
        ArrayList<BluetoothMapbMessage.vCard> recipientList = msg.getRecipients();
        int transparent = (ap.getTransparent() == BluetoothMapAppParams.INVALID_VALUE_PARAMETER) ?
                0 : ap.getTransparent();
        int retry = ap.getRetry();
        int charset = ap.getCharset();
        long handle = -1;

        if (recipientList == null) {
            Log.d(TAG, "empty recipient list");
            return -1;
        }

        for (BluetoothMapbMessage.vCard recipient : recipientList) {
            if(recipient.getEnvLevel() == 0) // Only send the message to the top level recipient
            {
                /* Only send to first address */
                boolean read = false;
                boolean deliveryReport = true;

                switch(msg.getType()){
                    case MMS:
                    {
                        /* Send message if folder is outbox */
                        /* to do, support MMS in the future */
                        String phone = recipient.getFirstPhoneNumber();
                        handle = sendMmsMessage(folder, phone, (BluetoothMapbMessageMmsEmail)msg);
                        break;
                    }
                    case SMS_GSM: //fall-through
                    case SMS_CDMA:
                    {
                        /* Add the message to the database */
                        String phone = recipient.getFirstPhoneNumber();
                        String msgBody = ((BluetoothMapbMessageSms) msg).getSmsBody();
                        Uri contentUri = Uri.parse("content://sms/" + folder);
                        Uri uri = Sms.addMessageToUri(mResolver, contentUri, phone, msgBody,
                            "", System.currentTimeMillis(), read, deliveryReport);

                        if (uri == null) {
                            Log.d(TAG, "pushMessage - failure on add to uri " + contentUri);
                            return -1;
                        }

                        handle = Long.parseLong(uri.getLastPathSegment());

                        /* Send message if folder is outbox */
                        if (folder.equals("outbox")) {
                            PushMsgInfo msgInfo = new PushMsgInfo(handle, transparent,
                                retry, phone, uri);
                            mPushMsgList.put(handle, msgInfo);
                            sendMessage(msgInfo, msgBody);
                        }
                        break;
                    }
                    case EMAIL:
                    {
                        /* Send message if folder is outbox */
                        /* to do, support EMAIL in the future */
                        Log.d(TAG, "AccountId " + BluetoothMapUtils.getEmailAccountId(mContext));
                        if(folder.equalsIgnoreCase("draft"))
                           folder="Drafts";
                        handle = sendEmailMessage(folder, recipient.getEmailAddresses(),
                            (BluetoothMapbMessageMmsEmail)msg);
                        break;
                    }
                }

            }
        }

        /* If multiple recipients return handle of last */
        return handle;
    }


    public long sendEmailMessage(String folder,String[] toList, BluetoothMapbMessageMmsEmail msg) {
        return -1;
    }


    public long sendMmsMessage(String folder,String to_address, BluetoothMapbMessageMmsEmail msg) {
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
        if (folder != null && (folder.equalsIgnoreCase("outbox")||  folder.equalsIgnoreCase("drafts") || folder.equalsIgnoreCase("draft"))) {
        long handle = pushMmsToFolder(Mms.MESSAGE_BOX_DRAFTS, to_address, msg);
        /* if invalid handle (-1) then just return the handle - else continue sending (if folder is outbox) */
        if (BluetoothMapAppParams.INVALID_VALUE_PARAMETER != handle && folder.equalsIgnoreCase("outbox")) {
            moveDraftToOutbox(handle);

            Intent sendIntent = new Intent("android.intent.action.MMS_SEND_OUTBOX_MSG");
            Log.d(TAG, "broadcasting intent: "+sendIntent.toString());
            mContext.sendBroadcast(sendIntent);
            addMceInitiatedOperation(Long.toString(handle));
        }
        return handle;
        } else {
            /* not allowed to push mms to anything but outbox/drafts */
            throw  new IllegalArgumentException("Cannot push message to other folders than outbox/drafts");
        }

    }


    private void moveDraftToOutbox(long handle) {
        ContentResolver contentResolver = mContext.getContentResolver();
        /*Move message by changing the msg_box value in the content provider database */
        if (handle != -1) {
            String whereClause = " _id= " + handle;
            Uri uri = Uri.parse("content://mms");
            Cursor queryResult = contentResolver.query(uri, null, whereClause, null, null);
            if (queryResult != null) {
                if (queryResult.getCount() > 0) {
                    queryResult.moveToFirst();
                    ContentValues data = new ContentValues();
                    /* set folder to be outbox */
                    data.put("msg_box", Mms.MESSAGE_BOX_OUTBOX);
                    contentResolver.update(uri, data, whereClause, null);
                    Log.d(TAG, "moved draft MMS to outbox");
                }
                queryResult.close();
                addMceInitiatedOperation(Long.toString(handle));
            }else {
                Log.d(TAG, "Could not move draft to outbox ");
            }
        }
    }
    private long pushMmsToFolder(int folder, String to_address, BluetoothMapbMessageMmsEmail msg) {
        /**
         * strategy:
         * 1) parse msg into parts + header
         * 2) create thread id (abuse the ease of adding an SMS to get id for thread)
         * 3) push parts into content://mms/parts/ table
         * 3)
         */

        ContentValues values = new ContentValues();
        values.put("msg_box", folder);

        values.put("read", 0);
        values.put("seen", 0);
        values.put("sub", msg.getSubject());
        values.put("sub_cs", 106);
        values.put("ct_t", "application/vnd.wap.multipart.related");
        values.put("exp", 604800);
        values.put("m_cls", PduHeaders.MESSAGE_CLASS_PERSONAL_STR);
        values.put("m_type", PduHeaders.MESSAGE_TYPE_SEND_REQ);
        values.put("v", PduHeaders.CURRENT_MMS_VERSION);
        values.put("pri", PduHeaders.PRIORITY_NORMAL);
        values.put("rr", PduHeaders.VALUE_NO);
        values.put("tr_id", "T"+ Long.toHexString(System.currentTimeMillis()));
        values.put("d_rpt", PduHeaders.VALUE_NO);
        values.put("locked", 0);
        if(msg.getTextOnly() == true)
            values.put("text_only", true);

        values.put("m_size", msg.getSize());

     // Get thread id
        Set<String> recipients = new HashSet<String>();
        recipients.addAll(Arrays.asList(to_address));
        values.put("thread_id", Telephony.Threads.getOrCreateThreadId(mContext, recipients));
        Uri uri = Uri.parse("content://mms");

        ContentResolver cr = mContext.getContentResolver();
        uri = cr.insert(uri, values);

        if (uri == null) {
            // unable to insert MMS
            Log.e(TAG, "Unabled to insert MMS " + values + "Uri: " + uri);
            return -1;
        }

        long handle = Long.parseLong(uri.getLastPathSegment());
        ArrayList<MimePart> parts = msg.getMimeParts();
        if (parts != null) {
            Log.v(TAG, " NEW URI " + uri.toString());
            try {
                for(MimePart part : parts) {
                    int count = 0;
                    count++;
                    values.clear();
                    if(part.contentType != null &&
                       part.contentType.toUpperCase().contains("TEXT")) {
                       values.put("ct", "text/plain");
                       values.put("chset", 106);
                       if(part.partName != null) {
                          values.put("fn", part.partName);
                          values.put("name", part.partName);
                       } else if(part.contentId == null && part.contentLocation == null) {
                          /* We must set at least one part identifier */
                          values.put("fn", "text_" + count +".txt");
                          values.put("name", "text_" + count +".txt");
                       }
                       if(part.contentId != null) {
                          values.put("cid", part.contentId);
                       }
                       if(part.contentLocation != null)
                          values.put("cl", part.contentLocation);
                       if(part.contentDisposition != null)
                          values.put("cd", part.contentDisposition);
                       values.put("text", new String(part.data, "UTF-8"));
                       uri = Uri.parse("content://mms/" + handle + "/part");
                       uri = cr.insert(uri, values);
                       if(V) Log.v(TAG, "Added TEXT part");
                    } else if (part.contentType != null &&
                               part.contentType.toUpperCase().contains("SMIL")) {
                      values.put("seq", -1);
                      values.put("ct", "application/smil");
                      if(part.contentId != null)
                         values.put("cid", part.contentId);
                      if(part.contentLocation != null)
                         values.put("cl", part.contentLocation);
                      if(part.contentDisposition != null)
                         values.put("cd", part.contentDisposition);
                      values.put("fn", "smil.xml");
                      values.put("name", "smil.xml");
                      values.put("text", new String(part.data, "UTF-8"));

                      uri = Uri.parse("content://mms/" + handle + "/part");
                      uri = cr.insert(uri, values);
                      if(V) Log.v(TAG, "Added SMIL part");
                    } else /*VIDEO/AUDIO/IMAGE*/ {
                      writeMmsDataPart(handle, part, count);
                      if(V) Log.v(TAG, "Added OTHER part");
                    }
                    if (uri != null && V){
                        Log.v(TAG, "Added part with content-type: "+ part.contentType +
                              " to Uri: " + uri.toString());
                    }
                }
                addMceInitiatedOperation("+");
            }   catch (UnsupportedEncodingException e) {
                Log.w(TAG, e);
            } catch (IOException e) {
                Log.w(TAG, e);
            }
        }
        values.clear();
        values.put("contact_id", "null");
        values.put("address", "insert-address-token");
        values.put("type", BluetoothMapContent.MMS_FROM);
        values.put("charset", 106);

        uri = Uri.parse("content://mms/" + handle + "/addr");
        uri = cr.insert(uri, values);
        if (uri != null && V){
            Log.v(TAG, " NEW URI " + uri.toString());
        }

        values.clear();
        values.put("contact_id", "null");
        values.put("address", to_address);
        values.put("type", BluetoothMapContent.MMS_TO);
        values.put("charset", 106);

        uri = Uri.parse("content://mms/" + handle + "/addr");
        uri = cr.insert(uri, values);
        if (uri != null && V){
            Log.v(TAG, " NEW URI " + uri.toString());
        }
        return handle;
    }


    private void writeMmsDataPart(long handle, MimePart part, int count) throws IOException{
        ContentValues values = new ContentValues();
        values.put("mid", handle);
        if(part.contentType != null){
            //Remove last char if ';' from contentType
            if(part.contentType.charAt(part.contentType.length() - 1) == ';') {
               part.contentType = part.contentType.substring(0,part.contentType.length() -1);
            }
            values.put("ct", part.contentType);
        }
        if(part.contentId != null)
            values.put("cid", part.contentId);
        if(part.contentLocation != null)
            values.put("cl", part.contentLocation);
        if(part.contentDisposition != null)
            values.put("cd", part.contentDisposition);
        if(part.partName != null) {
            values.put("fn", part.partName);
            values.put("name", part.partName);
        } else if(part.contentId == null && part.contentLocation == null) {
            /* We must set at least one part identifier */
            values.put("fn", "part_" + count + ".dat");
            values.put("name", "part_" + count + ".dat");
        }
        Uri partUri = Uri.parse("content://mms/" + handle + "/part");
        Uri res = mResolver.insert(partUri, values);

        // Add data to part
        OutputStream os = mResolver.openOutputStream(res);
        os.write(part.data);
        os.close();
    }


    public void sendMessage(PushMsgInfo msgInfo, String msgBody) {

        SmsManager smsMng = SmsManager.getDefault();
        ArrayList<String> parts = smsMng.divideMessage(msgBody);
        msgInfo.parts = parts.size();

        ArrayList<PendingIntent> deliveryIntents = new ArrayList<PendingIntent>(msgInfo.parts);
        ArrayList<PendingIntent> sentIntents = new ArrayList<PendingIntent>(msgInfo.parts);

        for (int i = 0; i < msgInfo.parts; i++) {
            Intent intent;
            intent = new Intent(ACTION_MESSAGE_DELIVERY, null);
            intent.putExtra("HANDLE", msgInfo.id);
            deliveryIntents.add(PendingIntent.getBroadcast(mContext,(int)System.currentTimeMillis(),
                intent, PendingIntent.FLAG_UPDATE_CURRENT));

            intent = new Intent(ACTION_MESSAGE_SENT, null);
            intent.putExtra("HANDLE", msgInfo.id);
            sentIntents.add(PendingIntent.getBroadcast(mContext, (int)System.currentTimeMillis(),
                intent,PendingIntent.FLAG_UPDATE_CURRENT));
        }

        Log.d(TAG, "sendMessage to " + msgInfo.phone);

        smsMng.sendMultipartTextMessage(msgInfo.phone, null, parts, sentIntents,
            deliveryIntents);
    }

    private static final String ACTION_MESSAGE_DELIVERY =
        "com.android.bluetooth.BluetoothMapContentObserver.action.MESSAGE_DELIVERY";
    private static final String ACTION_MESSAGE_SENT =
        "com.android.bluetooth.BluetoothMapContentObserver.action.MESSAGE_SENT";

    private SmsBroadcastReceiver mSmsBroadcastReceiver = new SmsBroadcastReceiver();

    private class SmsBroadcastReceiver extends BroadcastReceiver {
        private final String[] ID_PROJECTION = new String[] { Sms._ID };
        private final Uri UPDATE_STATUS_URI = Uri.parse("content://sms/status");

        public void register() {
            Handler handler = new Handler(Looper.getMainLooper());

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ACTION_MESSAGE_DELIVERY);
            intentFilter.addAction(ACTION_MESSAGE_SENT);
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
            long handle = intent.getLongExtra("HANDLE", -1);
            PushMsgInfo msgInfo = mPushMsgList.get(handle);

            Log.d(TAG, "onReceive: action"  + action);

            if (msgInfo == null) {
                Log.d(TAG, "onReceive: no msgInfo found for handle " + handle);
                return;
            }

            if (action.equals(ACTION_MESSAGE_SENT)) {
                msgInfo.partsSent++;
                if (msgInfo.partsSent == msgInfo.parts) {
                    actionMessageSent(context, intent, msgInfo);
                }
            } else if (action.equals(ACTION_MESSAGE_DELIVERY)) {
                msgInfo.partsDelivered++;
                if (msgInfo.partsDelivered == msgInfo.parts) {
                    actionMessageDelivery(context, intent, msgInfo);
                }
            } else {
                Log.d(TAG, "onReceive: Unknown action " + action);
            }
        }

        private void actionMessageSent(Context context, Intent intent,
            PushMsgInfo msgInfo) {
            int result = getResultCode();
            boolean delete = false;

            if (result == Activity.RESULT_OK) {
                Log.d(TAG, "actionMessageSent: result OK");
                if (msgInfo.transparent == 0) {
                    if (!Sms.moveMessageToFolder(context, msgInfo.uri,
                            Sms.MESSAGE_TYPE_SENT, 0)) {
                        Log.d(TAG, "Failed to move " + msgInfo.uri + " to SENT");
                    }
                } else {
                    delete = true;
                }

                Event evt = new Event("SendingSuccess", msgInfo.id,
                    folderSms[Sms.MESSAGE_TYPE_SENT], null, mSmsType);
                sendEvent(evt);

            } else {
                if (msgInfo.retry == 1) {
                    /* Notify failure, but keep message in outbox for resending */
                    msgInfo.resend = true;
                    Event evt = new Event("SendingFailure", msgInfo.id,
                        folderSms[Sms.MESSAGE_TYPE_OUTBOX], null, mSmsType);
                    sendEvent(evt);
                } else {
                    if (msgInfo.transparent == 0) {
                        if (!Sms.moveMessageToFolder(context, msgInfo.uri,
                                Sms.MESSAGE_TYPE_FAILED, 0)) {
                            Log.d(TAG, "Failed to move " + msgInfo.uri + " to FAILED");
                        }
                    } else {
                        delete = true;
                    }

                    Event evt = new Event("SendingFailure", msgInfo.id,
                        folderSms[Sms.MESSAGE_TYPE_FAILED], null, mSmsType);
                    sendEvent(evt);
                }
            }

            if (delete == true) {
                /* Delete from Observer message list to avoid delete notifications */
                mMsgListSms.remove(msgInfo.id);

                /* Delete from DB */
                mResolver.delete(msgInfo.uri, null, null);
            }
        }

        private void actionMessageDelivery(Context context, Intent intent,
            PushMsgInfo msgInfo) {
            Uri messageUri = intent.getData();
            byte[] pdu = intent.getByteArrayExtra("pdu");
            String format = intent.getStringExtra("format");

            SmsMessage message = SmsMessage.createFromPdu(pdu, format);
            if (message == null) {
                Log.d(TAG, "actionMessageDelivery: Can't get message from pdu");
                return;
            }
            int status = message.getStatus();

            Cursor cursor = mResolver.query(msgInfo.uri, ID_PROJECTION, null, null, null);

            try {
                if (cursor != null && cursor.moveToFirst()) {
                    int messageId = cursor.getInt(0);

                    Uri updateUri = ContentUris.withAppendedId(UPDATE_STATUS_URI, messageId);
                    boolean isStatusReport = message.isStatusReportMessage();

                    Log.d(TAG, "actionMessageDelivery: uri=" + messageUri + ", status=" + status +
                                ", isStatusReport=" + isStatusReport);

                    ContentValues contentValues = new ContentValues(2);

                    contentValues.put(Sms.STATUS, status);
                    contentValues.put(Inbox.DATE_SENT, System.currentTimeMillis());
                    mResolver.update(updateUri, contentValues, null, null);
                } else {
                    Log.d(TAG, "Can't find message for status update: " + messageUri);
                }
            } finally {
                if (cursor != null)
                    cursor.close();
            }

            if (status == 0) {
                Event evt = new Event("DeliverySuccess", msgInfo.id,
                    folderSms[Sms.MESSAGE_TYPE_SENT], null, mSmsType);
                sendEvent(evt);
            } else {
                Event evt = new Event("DeliveryFailure", msgInfo.id,
                    folderSms[Sms.MESSAGE_TYPE_SENT], null, mSmsType);
                sendEvent(evt);
            }

            mPushMsgList.remove(msgInfo.id);
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
        Cursor c = mResolver.query(Sms.CONTENT_URI, SMS_PROJECTION, where, null,
            null);

        if (c != null && c.moveToFirst()) {
            do {
                long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                String msgBody = c.getString(c.getColumnIndex(Sms.BODY));
                PushMsgInfo msgInfo = mPushMsgList.get(id);
                if (msgInfo == null || msgInfo.resend == false) {
                    continue;
                }
                sendMessage(msgInfo, msgBody);
            } while (c.moveToNext());
            c.close();
        }
    }

    private void failPendingMessages() {
        /* Move pending messages from outbox to failed */
        String where = "type = " + Sms.MESSAGE_TYPE_OUTBOX;
        Cursor c = mResolver.query(Sms.CONTENT_URI, SMS_PROJECTION, where, null,
            null);

        if (c != null && c.moveToFirst()) {
            do {
                long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                String msgBody = c.getString(c.getColumnIndex(Sms.BODY));
                PushMsgInfo msgInfo = mPushMsgList.get(id);
                if (msgInfo == null || msgInfo.resend == false) {
                    continue;
                }
                Sms.moveMessageToFolder(mContext, msgInfo.uri,
                    Sms.MESSAGE_TYPE_FAILED, 0);
            } while (c.moveToNext());
        }
        if (c != null) c.close();
    }

    private void removeDeletedMessages() {
        /* Remove messages from virtual "deleted" folder (thread_id -1) */
        mResolver.delete(Uri.parse("content://sms/"),
                "thread_id = " + DELETED_THREAD_ID, null);
    }

    private PhoneStateListener mPhoneListener = new PhoneStateListener (Long.MAX_VALUE - 1,
                                                         Looper.getMainLooper())  {
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
    }

    public void deinit() {
        mSmsBroadcastReceiver.unregister();
        unRegisterPhoneServiceStateListener();
        failPendingMessages();
        removeDeletedMessages();
    }
}
