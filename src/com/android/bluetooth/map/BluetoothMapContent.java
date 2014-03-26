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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import android.text.Html;
import android.text.format.Time;


import org.apache.http.util.ByteArrayBuffer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Threads;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.text.format.Time;
import android.util.TimeFormatException;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;


import com.android.bluetooth.map.BluetoothMapUtils.TYPE;
import com.google.android.mms.pdu.CharacterSets;
import com.google.android.mms.pdu.PduHeaders;
import android.database.sqlite.SQLiteException;
import java.util.List;
import java.util.ArrayList;
import java.util.*;
import org.apache.commons.codec.net.QuotedPrintableCodec;
import org.apache.commons.codec.DecoderException;


public class BluetoothMapContent {
    private static final String TAG = "BluetoothMapContent";

    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = BluetoothMapService.VERBOSE;

    private static final int MASK_SUBJECT = 0x1;
    private static final int MASK_DATETIME = 0x2;
    private static final int MASK_SENDER_NAME = 0x4;
    private static final int MASK_SENDER_ADDRESSING = 0x8;

    private static final int MASK_RECIPIENT_NAME = 0x10;
    private static final int MASK_RECIPIENT_ADDRESSING = 0x20;
    private static final int MASK_TYPE = 0x40;
    private static final int MASK_SIZE = 0x80;

    private static final int MASK_RECEPTION_STATUS = 0x100;
    private static final int MASK_TEXT = 0x200;
    private static final int MASK_ATTACHMENT_SIZE = 0x400;
    private static final int MASK_PRIORITY = 0x800;

    private static final int MASK_READ = 0x1000;
    private static final int MASK_SENT = 0x2000;
    private static final int MASK_PROTECTED = 0x4000;
    private static final int MASK_REPLYTO_ADDRESSING = 0x8000;

    /* Type of MMS address. From Telephony.java it must be one of PduHeaders.BCC, */
    /* PduHeaders.CC, PduHeaders.FROM, PduHeaders.TO. These are from PduHeaders.java */
    public static final int MMS_FROM = 0x89;
    public static final int MMS_TO = 0x97;
    public static final int MMS_BCC = 0x81;
    public static final int MMS_CC = 0x82;

   /* Type of Email address. From Telephony.java it must be one of PduHeaders.BCC, */
   /* PduHeaders.CC, PduHeaders.FROM, PduHeaders.TO. These are from PduHeaders.java */
    public static final int EMAIL_FROM = 0x89;
    public static final int EMAIL_TO = 0x97;
    public static final int EMAIL_BCC = 0x81;
    public static final int EMAIL_CC = 0x82;
    public static final String AUTHORITY = "com.android.email.provider";
    public static final Uri EMAIL_URI = Uri.parse("content://" + AUTHORITY);
    public static final Uri EMAIL_ACCOUNT_URI = Uri.withAppendedPath(EMAIL_URI, "account");
    public static final Uri EMAIL_BOX_URI = Uri.withAppendedPath(EMAIL_URI, "mailbox");
    public static final Uri EMAIL_MESSAGE_URI = Uri.withAppendedPath(EMAIL_URI, "message");
    public static final String RECORD_ID = "_id";
    public static final String DISPLAY_NAME = "displayName";
    public static final String SERVER_ID = "serverId";
    public static final String ACCOUNT_KEY = "accountKey";
    public static final String MAILBOX_KEY = "mailboxKey";
    public static final String EMAIL_ADDRESS = "emailAddress";
    public static final String IS_DEFAULT = "isDefault";
    public static final String EMAIL_TYPE = "type";
    public String msgListingFolder = null;
    public static final String[] EMAIL_BOX_PROJECTION = new String[] {
             RECORD_ID, DISPLAY_NAME, ACCOUNT_KEY, EMAIL_TYPE };

    private Context mContext;
    private ContentResolver mResolver;
    private static final String[] ACCOUNT_ID_PROJECTION = new String[] {
                         RECORD_ID, EMAIL_ADDRESS, IS_DEFAULT
    };

    static final String[] EMAIL_PROJECTION = new String[] {
        EmailContent.RECORD_ID,
        MessageColumns.DISPLAY_NAME, MessageColumns.TIMESTAMP,
        MessageColumns.SUBJECT, MessageColumns.FLAG_READ,
        MessageColumns.FLAG_ATTACHMENT, MessageColumns.FLAGS,
        SyncColumns.SERVER_ID, MessageColumns.DRAFT_INFO,
        MessageColumns.MESSAGE_ID, MessageColumns.MAILBOX_KEY,
        MessageColumns.ACCOUNT_KEY, MessageColumns.FROM_LIST,
        MessageColumns.TO_LIST, MessageColumns.CC_LIST,
        MessageColumns.BCC_LIST, MessageColumns.REPLY_TO_LIST,
        SyncColumns.SERVER_TIMESTAMP, MessageColumns.MEETING_INFO,
        MessageColumns.SNIPPET, MessageColumns.PROTOCOL_SEARCH_INFO,
        MessageColumns.THREAD_TOPIC
    };

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
        Mms.STATUS,
    };

    private class FilterInfo {
        public static final int TYPE_SMS = 0;
        public static final int TYPE_MMS = 1;
        public static final int TYPE_EMAIL = 2;

        int msgType = TYPE_SMS;
        int phoneType = 0;
        String phoneNum = null;
        String phoneAlphaTag = null;
    }

    public BluetoothMapContent(final Context context) {
        mContext = context;
        mResolver = mContext.getContentResolver();
        if (mResolver == null) {
            Log.e(TAG, "getContentResolver failed");
        }
    }

    private void addSmsEntry() {
        if (D) Log.d(TAG, "*** Adding dummy sms ***");

        ContentValues mVal = new ContentValues();
        mVal.put(Sms.ADDRESS, "1234");
        mVal.put(Sms.BODY, "Hello!!!");
        mVal.put(Sms.DATE, System.currentTimeMillis());
        mVal.put(Sms.READ, "0");

        Uri mUri = mResolver.insert(Sms.CONTENT_URI, mVal);
    }

    private BluetoothMapAppParams buildAppParams() {
        BluetoothMapAppParams ap = new BluetoothMapAppParams();
        try {
            int paramMask = (MASK_SUBJECT
                | MASK_DATETIME
                | MASK_SENDER_NAME
                | MASK_SENDER_ADDRESSING
                | MASK_RECIPIENT_NAME
                | MASK_RECIPIENT_ADDRESSING
                | MASK_TYPE
                | MASK_SIZE
                | MASK_RECEPTION_STATUS
                | MASK_TEXT
                | MASK_ATTACHMENT_SIZE
                | MASK_PRIORITY
                | MASK_READ
                | MASK_SENT
                | MASK_PROTECTED
                );
            ap.setMaxListCount(5);
            ap.setStartOffset(0);
            ap.setFilterMessageType(0);
            ap.setFilterPeriodBegin("20130101T000000");
            ap.setFilterPeriodEnd("20131230T000000");
            ap.setFilterReadStatus(0);
            ap.setParameterMask(paramMask);
            ap.setSubjectLength(10);
            /* ap.setFilterOriginator("Sms*"); */
            /* ap.setFilterRecipient("41*"); */
        } catch (ParseException e) {
            return null;
        }
        return ap;
    }

    private void printSms(Cursor c) {
        String body = c.getString(c.getColumnIndex(Sms.BODY));
        if (D) Log.d(TAG, "printSms " + BaseColumns._ID + ": " + c.getLong(c.getColumnIndex(BaseColumns._ID)) +
                " " + Sms.THREAD_ID + " : " + c.getLong(c.getColumnIndex(Sms.THREAD_ID)) +
                " " + Sms.ADDRESS + " : " + c.getString(c.getColumnIndex(Sms.ADDRESS)) +
                " " + Sms.BODY + " : " + body.substring(0, Math.min(body.length(), 8)) +
                " " + Sms.DATE + " : " + c.getLong(c.getColumnIndex(Sms.DATE)) +
                " " + Sms.TYPE + " : " + c.getInt(c.getColumnIndex(Sms.TYPE)));
    }

    private void printMms(Cursor c) {
        if (D) Log.d(TAG, "printMms " + BaseColumns._ID + ": " + c.getLong(c.getColumnIndex(BaseColumns._ID)) +
                "\n   " + Mms.THREAD_ID + " : " + c.getLong(c.getColumnIndex(Mms.THREAD_ID)) +
                "\n   " + Mms.MESSAGE_ID + " : " + c.getString(c.getColumnIndex(Mms.MESSAGE_ID)) +
                "\n   " + Mms.SUBJECT + " : " + c.getString(c.getColumnIndex(Mms.SUBJECT)) +
                "\n   " + Mms.CONTENT_TYPE + " : " + c.getString(c.getColumnIndex(Mms.CONTENT_TYPE)) +
                "\n   " + Mms.TEXT_ONLY + " : " + c.getInt(c.getColumnIndex(Mms.TEXT_ONLY)) +
                "\n   " + Mms.DATE + " : " + c.getLong(c.getColumnIndex(Mms.DATE)) +
                "\n   " + Mms.DATE_SENT + " : " + c.getLong(c.getColumnIndex(Mms.DATE_SENT)) +
                "\n   " + Mms.READ + " : " + c.getInt(c.getColumnIndex(Mms.READ)) +
                "\n   " + Mms.MESSAGE_BOX + " : " + c.getInt(c.getColumnIndex(Mms.MESSAGE_BOX)) +
                "\n   " + Mms.STATUS + " : " + c.getInt(c.getColumnIndex(Mms.STATUS)));
    }

    private void printMmsAddr(long id) {
        final String[] projection = null;
        String selection = new String("msg_id=" + id);
        String uriStr = String.format("content://mms/%d/addr", id);
        Uri uriAddress = Uri.parse(uriStr);
        Cursor c = mResolver.query(
            uriAddress,
            projection,
            selection,
            null, null);

        if (c !=null && c.moveToFirst()) {
            do {
                String add = c.getString(c.getColumnIndex("address"));
                Integer type = c.getInt(c.getColumnIndex("type"));
                if (type == MMS_TO) {
                    if (D) Log.d(TAG, "   recipient: " + add + " (type: " + type + ")");
                } else if (type == MMS_FROM) {
                    if (D) Log.d(TAG, "   originator: " + add + " (type: " + type + ")");
                } else {
                    if (D) Log.d(TAG, "   address other: " + add + " (type: " + type + ")");
                }

            } while(c.moveToNext());
        }
    }

    private void printMmsPartImage(long partid) {
        String uriStr = String.format("content://mms/part/%d", partid);
        Uri uriAddress = Uri.parse(uriStr);
        int ch;
        StringBuffer sb = new StringBuffer("");
        InputStream is = null;

        try {
            is = mResolver.openInputStream(uriAddress);

            while ((ch = is.read()) != -1) {
                sb.append((char)ch);
            }
            if (D) Log.d(TAG, sb.toString());

        } catch (IOException e) {
            // do nothing for now
            e.printStackTrace();
        }
    }

    private void printMmsParts(long id) {
        final String[] projection = null;
        String selection = new String("mid=" + id);
        String uriStr = String.format("content://mms/%d/part", id);
        Uri uriAddress = Uri.parse(uriStr);
        Cursor c = mResolver.query(
            uriAddress,
            projection,
            selection,
            null, null);

        if (D) Log.d(TAG, "   parts:");
        if (c !=null && c.moveToFirst()) {
            do {
                Long partid = c.getLong(c.getColumnIndex(BaseColumns._ID));
                String ct = c.getString(c.getColumnIndex("ct"));
                String name = c.getString(c.getColumnIndex("name"));
                String charset = c.getString(c.getColumnIndex("chset"));
                String filename = c.getString(c.getColumnIndex("fn"));
                String text = c.getString(c.getColumnIndex("text"));
                Integer fd = c.getInt(c.getColumnIndex("_data"));
                String cid = c.getString(c.getColumnIndex("cid"));
                String cl = c.getString(c.getColumnIndex("cl"));
                String cdisp = c.getString(c.getColumnIndex("cd"));

                if (D) Log.d(TAG, "     _id : " + partid +
                    "\n     ct : " + ct +
                    "\n     partname : " + name +
                    "\n     charset : " + charset +
                    "\n     filename : " + filename +
                    "\n     text : " + text +
                    "\n     fd : " + fd +
                    "\n     cid : " + cid +
                    "\n     cl : " + cl +
                    "\n     cdisp : " + cdisp);

                /* if (ct.equals("image/jpeg")) { */
                /*     printMmsPartImage(partid); */
                /* } */
            } while(c.moveToNext());
        }
    }

    public void dumpMmsTable() {
        if (D) Log.d(TAG, "**** Dump of mms table ****");
        Cursor c = mResolver.query(Mms.CONTENT_URI,
                MMS_PROJECTION, null, null, "_id DESC");
        if (c != null) {
            if (D) Log.d(TAG, "c.getCount() = " + c.getCount());
            c.moveToPosition(-1);
            while (c.moveToNext()) {
                printMms(c);
                long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                printMmsAddr(id);
                printMmsParts(id);
            }
            c.close();
        } else {
            Log.d(TAG, "query failed");
        }
    }

    public void dumpSmsTable() {
        addSmsEntry();
        if (D) Log.d(TAG, "**** Dump of sms table ****");
        Cursor c = mResolver.query(Sms.CONTENT_URI,
                SMS_PROJECTION, null, null, "_id DESC");
        if (c != null) {
            if (D) Log.d(TAG, "c.getCount() = " + c.getCount());
            c.moveToPosition(-1);
            while (c.moveToNext()) {
                printSms(c);
            }
            c.close();
        } else {
            Log.d(TAG, "query failed");
        }

    }

    /**
     * Get SMS RecipientAddresses for DRAFT folder based on threadId
     *
    */
    private String getMessageSmsRecipientAddress(int threadId){
       String [] RECIPIENT_ID_PROJECTION = { "recipient_ids" };
        /*
         1. Get Recipient Ids from Threads.CONTENT_URI
         2. Get Recipient Address for corresponding Id from canonical-addresses table.
        */

        Uri sAllCanonical = Uri.parse("content://mms-sms/canonical-addresses");
        Uri sAllThreadsUri =  Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build();
        Cursor cr = null;
        String recipientAddress= "";
        String recipientIds = null;
        String whereClause = "_id="+threadId;
        if (V) Log.v(TAG, "whereClause is "+ whereClause);
        cr = mContext.getContentResolver().query(sAllThreadsUri, RECIPIENT_ID_PROJECTION, whereClause, null,
                null);
        if (cr != null && cr.moveToFirst()) {
            recipientIds = cr.getString(0);
            if (V) Log.v(TAG, "cursor.getCount(): " + cr.getCount() + "recipientIds: "+ recipientIds +"whrClus: "+ whereClause );
        }
        if(cr != null){
            cr.close();
            cr = null;
        }

        if (V) Log.v(TAG, "recipientIds with spaces: "+ recipientIds +"\n");

        if(recipientIds != null) {
           String recipients[] = null;
           whereClause = "";
           recipients = recipientIds.split(" ");
           for (String id: recipients) {
                if(whereClause.length() != 0)
                   whereClause +=" OR ";
                whereClause +="_id="+id;
           }
           if (V) Log.v(TAG, "whereClause is "+ whereClause);
           cr = mContext.getContentResolver().query(sAllCanonical , null, whereClause, null, null);
           if (cr != null && cr.moveToFirst()) {
              do {
                //TODO: Multiple Recipeints are appended with ";" for now.
                if(recipientAddress.length() != 0 )
                   recipientAddress+=";";
                recipientAddress+=cr.getString(cr.getColumnIndex("address"));
              } while(cr.moveToNext());
           }
           if(cr != null)
              cr.close();

        }

        if(V) Log.v(TAG,"Final recipientAddress : "+ recipientAddress);
        return recipientAddress;

     }

    public void dumpMessages() {
        dumpSmsTable();
        dumpMmsTable();

        BluetoothMapAppParams ap = buildAppParams();
        if (D) Log.d(TAG, "message listing size = " + msgListingSize("inbox", ap));
        BluetoothMapMessageListing mList = msgListing("inbox", ap);
        try {
            mList.encode();
        } catch (UnsupportedEncodingException ex) {
            /* do nothing */
        }
        mList = msgListing("sent", ap);
        try {
            mList.encode();
        } catch (UnsupportedEncodingException ex) {
            /* do nothing */
        }
    }

    public static String decodeEncodedWord(String checkEncoded) {
        if (checkEncoded != null && (checkEncoded.contains("=?") == false)) {
            if(V) Log.v(TAG, " Decode NotRequired" + checkEncoded);
            return checkEncoded;
        }

        int begin = checkEncoded.indexOf("=?", 0);

        int endScan = begin + 2;
        if (begin != -1) {
            int qm1 = checkEncoded.indexOf('?', endScan + 2);
            int qm2 = checkEncoded.indexOf('?', qm1 + 1);
            if (qm2 != -1) {
               endScan = qm2 + 1;
            }
        }

        int end = begin == -1 ? -1 : checkEncoded.indexOf("?=", endScan);
        if (end == -1)
           return checkEncoded;
        checkEncoded = checkEncoded.substring((endScan - 1), (end + 1));

        // TODO: Handle encoded words as defined by MIME standard RFC 2047
        // Encoded words will have the form =?charset?enc?Encoded word?= where
        // enc is either 'Q' or 'q' for quoted-printable and 'B' or 'b' for Base64
         QuotedPrintableCodec qpDecode = new QuotedPrintableCodec("UTF-8");
         String decoded = null;
         try {
             decoded = qpDecode.decode(checkEncoded);
         }catch (DecoderException e ){
             if(V) Log.v(TAG, "decode exception");
             return checkEncoded;
         }
         if (decoded == null) {
            return checkEncoded;
         }
         return decoded;
    }

    private void setProtected(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_PROTECTED) != 0) {
            String protect = "no";
            if (D) Log.d(TAG, "setProtected: " + protect);
            e.setProtect(protect);
        }
    }

    private void setSent(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        String sent = null;
        if ((ap.getParameterMask() & MASK_SENT) != 0) {
            int msgType = 0;
            if (fi.msgType == FilterInfo.TYPE_SMS) {
                msgType = c.getInt(c.getColumnIndex(Sms.TYPE));
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                msgType = c.getInt(c.getColumnIndex(Mms.MESSAGE_BOX));
            } else {
                msgType = c.getInt(c.getColumnIndex(MessageColumns.MAILBOX_KEY));
                if (msgType == 4) {
                    sent = "yes";
                } else {
                    sent = "no";
                }
                if (D) Log.d(TAG, "setSent: " + sent);
                e.setSent(sent);
                return;
            }
            if (msgType == 2) {
                sent = "yes";
            } else {
                sent = "no";
            }
            if (D) Log.d(TAG, "setSent: " + sent);
            e.setSent(sent);
        }
    }

    private void setRead(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        int read = 0;
        if (fi.msgType == FilterInfo.TYPE_SMS) {
            read = c.getInt(c.getColumnIndex(Sms.READ));
        } else if (fi.msgType == FilterInfo.TYPE_MMS) {
            read = c.getInt(c.getColumnIndex(Mms.READ));
        } else {
            read = c.getInt(c.getColumnIndex(MessageColumns.FLAG_READ));
        }
        String setread = null;
        if (read == 1) {
            setread = "yes";
        } else {
            setread = "no";
        }
        if (D) Log.d(TAG, "setRead: " + setread);
        e.setRead(setread, ((ap.getParameterMask() & MASK_READ) != 0));
    }

    private void setPriority(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        String priority = "no";
        if ((ap.getParameterMask() & MASK_PRIORITY) != 0) {
            int pri = 0;
            if (fi.msgType == FilterInfo.TYPE_MMS) {
                pri = c.getInt(c.getColumnIndex(Mms.PRIORITY));
            }
            if (pri == PduHeaders.PRIORITY_HIGH) {
                priority = "yes";
            }
            if (D) Log.d(TAG, "setPriority: " + priority);
            e.setPriority(priority);
        }
    }

    /**
     * For SMS we set the attachment size to 0, as all data will be text data, hence
     * attachments for SMS is not possible.
     * For MMS all data is actually attachments, hence we do set the attachment size to
     * the total message size. To provide a more accurate attachment size, one could
     * extract the length (in bytes) of the text parts.
     */
    private void setAttachmentSize(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_ATTACHMENT_SIZE) != 0) {
            int size = 0;
            if (fi.msgType == FilterInfo.TYPE_MMS) {
                size = c.getInt(c.getColumnIndex(Mms.MESSAGE_SIZE));
            } else if (fi.msgType == FilterInfo.TYPE_EMAIL) {
                Uri uri = Uri.parse("content://com.android.email.provider/attachment");
                long msgId = Long.valueOf(c.getString(c.getColumnIndex("_id")));
                String where = setWhereFilterMessagekey(msgId);
                Cursor cr = mResolver.query(
                                uri, new String[]{"size"}, where , null, null);
                if (cr != null && cr.moveToFirst()) {
                    do {
                       size += cr.getInt(0);
                    } while (cr.moveToNext());
                }
                if (cr != null) {
                    cr.close();
                }
            }
            if (D) Log.d(TAG, "setAttachmentSize: " + size);
            e.setAttachmentSize(size);
        }
    }

    private void setText(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_TEXT) != 0) {
            String hasText = "";
            if (fi.msgType == FilterInfo.TYPE_SMS) {
                hasText = "yes";
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                int textOnly = c.getInt(c.getColumnIndex(Mms.TEXT_ONLY));
                if (textOnly == 1) {
                    hasText = "yes";
                } else {
                    long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                    String text = getTextPartsMms(id);
                    if (text != null && text.length() > 0) {
                        hasText = "yes";
                    } else {
                        hasText = "no";
                    }
                }
            } else {
                 hasText = "yes";
            }
            if (D) Log.d(TAG, "setText: " + hasText);
            e.setText(hasText);
        }
    }

    private void setReceptionStatus(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_RECEPTION_STATUS) != 0) {
            String status = "complete";
            if (D) Log.d(TAG, "setReceptionStatus: " + status);
            e.setReceptionStatus(status);
        }
    }

    private void setSize(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_SIZE) != 0) {
            int size = 0;
            if (fi.msgType == FilterInfo.TYPE_SMS) {
                String subject = c.getString(c.getColumnIndex(Sms.BODY));
                size = subject.length();
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                size = c.getInt(c.getColumnIndex(Mms.MESSAGE_SIZE));
            } else {
                long msgId = Long.valueOf(c.getString(c.getColumnIndex("_id")));
                String[] EMAIL_MSGSIZE_PROJECTION = new String[] { "LENGTH(textContent)", "LENGTH(htmlContent)" };
                String textContent, htmlContent;
                Uri uri = Uri.parse("content://com.android.email.provider/body");
                String where = setWhereFilterMessagekey(msgId);
                Cursor cr = mResolver.query(
                    uri, EMAIL_MSGSIZE_PROJECTION, where , null, null);
                if (cr != null && cr.moveToFirst()) {
                    do {
                       size = cr.getInt(0);
                       if(size == -1 || size == 0)
                          size = cr.getInt(1);
                          break;
                    } while (cr.moveToNext());
                }
                if (cr != null) {
                    cr.close();
                }
            }
            if (D) Log.d(TAG, "setSize: " + size);
            e.setSize(size);
        }
    }

    private void setType(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_TYPE) != 0) {
            TYPE type = null;
            if (fi.msgType == FilterInfo.TYPE_SMS) {
                if (fi.phoneType == TelephonyManager.PHONE_TYPE_GSM) {
                    type = TYPE.SMS_GSM;
                } else if (fi.phoneType == TelephonyManager.PHONE_TYPE_CDMA) {
                    type = TYPE.SMS_CDMA;
                }
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                type = TYPE.MMS;
            } else {
                type = TYPE.EMAIL;
            }
            if (D) Log.d(TAG, "setType: " + type);
            e.setType(type);
        }
    }

    private void setRecipientAddressing(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_RECIPIENT_ADDRESSING) != 0) {
            String address = null;
            if (fi.msgType == FilterInfo.TYPE_SMS) {
                int msgType = c.getInt(c.getColumnIndex(Sms.TYPE));
                if (msgType == 1) {
                    address = fi.phoneNum;
                } else {
                    address = c.getString(c.getColumnIndex(Sms.ADDRESS));
                }
                if ((address == null) && msgListingFolder.equalsIgnoreCase("draft")) {
                    int threadIdInd = c.getColumnIndex("thread_id");
                    String threadIdStr = c.getString(threadIdInd);
                    address = getMessageSmsRecipientAddress(Integer.valueOf(threadIdStr));
                    if(V)  Log.v(TAG, "threadId = " + threadIdStr + " adress:" + address +"\n");
                }
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                address = getAddressMms(mResolver, id, MMS_TO);
            } else {
                int toIndex = c.getColumnIndex(MessageColumns.TO_LIST);
                address = c.getString(toIndex);
                if (address != null && address.contains("")) {
                    String[] recepientAddrStr = address.split("");
                    if (recepientAddrStr !=null && recepientAddrStr.length > 0) {
                        if (V){
                            Log.v(TAG, " ::Recepient addressing split String 0:: " + recepientAddrStr[0]
                                    + "::Recepient addressing split String 1:: " + recepientAddrStr[1]);
                        }
                        e.setRecipientAddressing(recepientAddrStr[0].trim());                    }
                } else {
                    if (D) Log.d(TAG, "setRecipientAddressing: " + address);
                    e.setRecipientAddressing(address);
                }
                return;
            }
            if (D) Log.d(TAG, "setRecipientAddressing: " + address);
            e.setRecipientAddressing(address);
        }
    }

    private void setRecipientName(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_RECIPIENT_NAME) != 0) {
            String name = null;
            String firstRecipient = null;
            if (fi.msgType == FilterInfo.TYPE_SMS) {
                int msgType = c.getInt(c.getColumnIndex(Sms.TYPE));
                if (msgType != 1) {
                    String phone = c.getString(c.getColumnIndex(Sms.ADDRESS));
                    name = getContactNameFromPhone(phone);
                } else {
                    name = fi.phoneAlphaTag;
                }
                if ((name == null) && msgListingFolder.equalsIgnoreCase("draft")) {
                    int threadIdInd = c.getColumnIndex("thread_id");
                    String threadIdStr = c.getString(threadIdInd);
                    firstRecipient = getMessageSmsRecipientAddress(Integer.valueOf(threadIdStr));
                    if(V)  Log.v(TAG, "threadId = " + threadIdStr + " address:" + firstRecipient +"\n");
                    if (firstRecipient != null) {
                        // Get first Recipient Name for multiple recipient addressing
                        if(firstRecipient.contains(";") ){
                           firstRecipient = firstRecipient.split(";")[0];
                        } else if (firstRecipient.contains(",")){
                           firstRecipient = firstRecipient.split(",")[0];
                        }
                        name = getContactNameFromPhone(firstRecipient);
                    }
                }

            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                String phone = getAddressMms(mResolver, id, MMS_TO);
                name = getContactNameFromPhone(phone);
            } else {
                int toIndex = c.getColumnIndex(MessageColumns.TO_LIST);
                if (D) Log.d(TAG, "setRecipientName: " +c.getString(toIndex));
                name = c.getString(toIndex);
            }
            if (D) Log.d(TAG, "setRecipientName: " + name);
            e.setRecipientName(name);
        }
    }

    private void setReplytoAddressing(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
            String address = null;
            if (fi.msgType == FilterInfo.TYPE_EMAIL) {
                int replyToInd = c.getColumnIndex(MessageColumns.REPLY_TO_LIST);
                if (D) Log.d(TAG, "setReplytoAddressing: " +c.getString(replyToInd));
                address = c.getString(replyToInd);
            }
            if (D) Log.d(TAG, "setReplytoAddressing: " + address);
            e.setReplytoAddressing(address);
    }

    private void setSenderAddressing(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_SENDER_ADDRESSING) != 0) {
            String address = null;
            if (fi.msgType == FilterInfo.TYPE_SMS) {
                int msgType = c.getInt(c.getColumnIndex(Sms.TYPE));
                if (msgType == 1) {
                    address = c.getString(c.getColumnIndex(Sms.ADDRESS));
                } else {
                    address = fi.phoneNum;
                }
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                address = getAddressMms(mResolver, id, MMS_FROM);
            } else {
                int fromIndex = c.getColumnIndex(MessageColumns.FROM_LIST);
                address = c.getString(fromIndex);
                if(address != null) {
                   if(address.contains("")){
                      String[] senderAddrStr = address.split("");
                      if(senderAddrStr !=null && senderAddrStr.length > 0){
                         if (V){
                             Log.v(TAG, " ::Sender Addressing split String 0:: " + senderAddrStr[0]
                                   + "::Sender Addressing split String 1:: " + senderAddrStr[1]);
                         }
                         e.setEmailSenderAddressing(senderAddrStr[0].trim());
                      }
                   } else{
                         if (D) Log.d(TAG, "setSenderAddressing: " + address);
                         e.setEmailSenderAddressing(address.trim());
                   }
                }
                return;
            }
            if (D) Log.d(TAG, "setSenderAddressing: " + address);
            e.setSenderAddressing(address);
        }
    }

    private void setSenderName(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_SENDER_NAME) != 0) {
            String name = null;
            if (fi.msgType == FilterInfo.TYPE_SMS) {
                int msgType = c.getInt(c.getColumnIndex(Sms.TYPE));
                if (msgType == 1) {
                    String phone = c.getString(c.getColumnIndex(Sms.ADDRESS));
                    name = getContactNameFromPhone(phone);
                } else {
                    name = fi.phoneAlphaTag;
                }
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                String phone = getAddressMms(mResolver, id, MMS_FROM);
                name = getContactNameFromPhone(phone);
            } else { //email case
                int displayNameIndex = c.getColumnIndex(MessageColumns.DISPLAY_NAME);
                if (D) Log.d(TAG, "setSenderName: " +c.getString(displayNameIndex));
                name = c.getString(displayNameIndex);
                if(name != null && name.contains("")){
                   String[] senderStr = name.split("");
                   if(senderStr !=null && senderStr.length > 0){
                      if (V){
                         Log.v(TAG, " ::Sender name split String 0:: " + senderStr[0]
                                    + "::Sender name split String 1:: " + senderStr[1]);
                      }
                      name = senderStr[1];
                   }
                }
                if (name != null) {
                    name = decodeEncodedWord(name);
                    name = name.trim();
                }

            }
            if (D) Log.d(TAG, "setSenderName: " + name);
            e.setSenderName(name);
        }
    }

    private void setDateTime(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_DATETIME) != 0) {
            long date = 0;
            int timeStamp = 0;

            if (fi.msgType == FilterInfo.TYPE_SMS) {
                date = c.getLong(c.getColumnIndex(Sms.DATE));
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                /* Use Mms.DATE for all messages. Although contract class states */
                /* Mms.DATE_SENT are for outgoing messages. But that is not working. */
                date = c.getLong(c.getColumnIndex(Mms.DATE)) * 1000L;

                /* int msgBox = c.getInt(c.getColumnIndex(Mms.MESSAGE_BOX)); */
                /* if (msgBox == Mms.MESSAGE_BOX_INBOX) { */
                /*     date = c.getLong(c.getColumnIndex(Mms.DATE)) * 1000L; */
                /* } else { */
                /*     date = c.getLong(c.getColumnIndex(Mms.DATE_SENT)) * 1000L; */
                /* } */
            } else {
                timeStamp = c.getColumnIndex(MessageColumns.TIMESTAMP);
                String timestamp = c.getString(timeStamp);
                date =Long.valueOf(timestamp);
            }
            e.setDateTime(date);
        }
    }

    private String getTextPartsMms(long id) {
        String text = "";
        String selection = new String("mid=" + id);
        String uriStr = String.format("content://mms/%d/part", id);
        Uri uriAddress = Uri.parse(uriStr);
        Cursor c = mResolver.query(uriAddress, null, selection,
            null, null);

        if (c != null && c.moveToFirst()) {
            do {
                String ct = c.getString(c.getColumnIndex("ct"));
                if (ct.equals("text/plain")) {
                    text += c.getString(c.getColumnIndex("text"));
                }
            } while(c.moveToNext());
        }
        if (c != null) {
            c.close();
        }
        return text;
    }


    private String getTextPartsEmail(long id) {
        String text = "";
        String selection = new String("mid=" + id);
        String uriStr = String.format("content://mms/%d/part", id);
        Uri uriAddress = Uri.parse(uriStr);
        Cursor c = mResolver.query(uriAddress, null, selection,
            null, null);

        if (c != null && c.moveToFirst()) {
            do {
                String ct = c.getString(c.getColumnIndex("ct"));
                if (ct.equals("text/plain")) {
                    text += c.getString(c.getColumnIndex("text"));
                }
            } while(c.moveToNext());
        }
        if (c != null) {
            c.close();
        }
        return text;
    }

    private void setSubject(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        String subject = "";
        int subLength = ap.getSubjectLength();
        if(subLength == BluetoothMapAppParams.INVALID_VALUE_PARAMETER)
            subLength = 256;

        if ((ap.getParameterMask() & MASK_SUBJECT) != 0) {
            if (fi.msgType == FilterInfo.TYPE_SMS) {
                subject = c.getString(c.getColumnIndex(Sms.BODY));
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                subject = c.getString(c.getColumnIndex(Mms.SUBJECT));
                if (subject == null || subject.length() == 0) {
                    /* Get subject from mms text body parts - if any exists */
                    long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                    subject = getTextPartsMms(id);
                }
            } else {
                subject = c.getString(c.getColumnIndex(MessageColumns.SUBJECT));
                if (subject == null || subject.length() == 0) {
                    /* Get subject from mms text body parts - if any exists */
                    long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                    subject = getTextPartsEmail(id);
                }
            }
            if (subject != null) {
                subject = subject.substring(0, Math.min(subject.length(),
                    subLength));
            }
            if (D) Log.d(TAG, "setSubject: " + subject);
            e.setSubject(subject);
        }
    }

    private void setHandle(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        long handle = c.getLong(c.getColumnIndex(BaseColumns._ID));
        TYPE type = null;
        if (fi.msgType == FilterInfo.TYPE_SMS) {
            if (fi.phoneType == TelephonyManager.PHONE_TYPE_GSM) {
                type = TYPE.SMS_GSM;
            } else if (fi.phoneType == TelephonyManager.PHONE_TYPE_CDMA) {
                type = TYPE.SMS_CDMA;
            }
        } else if (fi.msgType == FilterInfo.TYPE_MMS) {
            type = TYPE.MMS;
        } else {
            type = TYPE.EMAIL;
        }
        if (D) Log.d(TAG, "setHandle: " + handle + " - Type: " + type.name());
        e.setHandle(handle, type);
    }

    private BluetoothMapMessageListingElement element(Cursor c, FilterInfo fi,
        BluetoothMapAppParams ap) {
        BluetoothMapMessageListingElement e = new BluetoothMapMessageListingElement();

        setHandle(e, c, fi, ap);
        setSubject(e, c, fi, ap);
        setDateTime(e, c, fi, ap);
        setSenderName(e, c, fi, ap);
        setSenderAddressing(e, c, fi, ap);
        //setReplytoAddressing(e, c, fi, ap);
        setRecipientName(e, c, fi, ap);
        setRecipientAddressing(e, c, fi, ap);
        setType(e, c, fi, ap);
        setSize(e, c, fi, ap);
        setReceptionStatus(e, c, fi, ap);
        setText(e, c, fi, ap);
        setAttachmentSize(e, c, fi, ap);
        setPriority(e, c, fi, ap);
        setRead(e, c, fi, ap);
        setSent(e, c, fi, ap);
        setProtected(e, c, fi, ap);
        return e;
    }

    private String getContactNameFromPhone(String phone) {
        String name = null;

        Uri uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phone));

        String[] projection = {Contacts._ID, Contacts.DISPLAY_NAME};
        String selection = Contacts.IN_VISIBLE_GROUP + "=1";
        String orderBy = Contacts.DISPLAY_NAME + " ASC";

        Cursor c = mResolver.query(uri, projection, selection, null, orderBy);

        if (c != null && c.getCount() >= 1) {
            c.moveToFirst();
            name = c.getString(c.getColumnIndex(Contacts.DISPLAY_NAME));
        }

        if (c != null) {
           c.close();
        }
        return name;
    }

    static public String getAddressMms(ContentResolver r, long id, int type) {
        String selection = new String("msg_id=" + id + " AND type=" + type);
        String uriStr = String.format("content://mms/%d/addr", id);
        Uri uriAddress = Uri.parse(uriStr);
        String addr = null;
        Cursor c = r.query(uriAddress, null, selection, null, null);

        if (c != null && c.moveToFirst()) {
            addr = c.getString(c.getColumnIndex("address"));
        }

        if (c != null) {
            c.close();
        }
        return addr;
    }

    private boolean matchRecipientEmail(Cursor c, FilterInfo fi, String recip) {
        boolean res = false;
        String name = null;
        int toIndex = c.getColumnIndex("toList");
        name = c.getString(toIndex);

        if (name != null && name.length() > 0) {
            if (name.matches(recip)) {
                if (D) Log.d(TAG, "match recipient name = " + name);
                res = true;
            }  else {
            res = false;
            }
        }
        return res;
    }
    private boolean matchRecipientMms(Cursor c, FilterInfo fi, String recip) {
        boolean res;
        long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
        String phone = getAddressMms(mResolver, id, MMS_TO);
        if (phone != null && phone.length() > 0) {
            if (phone.matches(recip)) {
                if (D) Log.d(TAG, "match recipient phone = " + phone);
                res = true;
            } else {
                String name = getContactNameFromPhone(phone);
                if (name != null && name.length() > 0 && name.matches(recip)) {
                    if (D) Log.d(TAG, "match recipient name = " + name);
                    res = true;
                } else {
                    res = false;
                }
            }
        } else {
            res = false;
        }
        return res;
    }

    private boolean matchRecipientSms(Cursor c, FilterInfo fi, String recip) {
        boolean res;
        int msgType = c.getInt(c.getColumnIndex(Sms.TYPE));
        if (msgType == 1) {
            String phone = fi.phoneNum;
            String name = fi.phoneAlphaTag;
            if (phone != null && phone.length() > 0 && phone.replaceAll("\\s","").matches(recip)) {
                if (D) Log.d(TAG, "match recipient phone = " + phone);
                res = true;
            } else if (name != null && name.length() > 0 && name.matches(recip)) {
                if (D) Log.d(TAG, "match recipient name = " + name);
                res = true;
            } else {
                res = false;
            }
        }
        else {
            String phone = c.getString(c.getColumnIndex(Sms.ADDRESS));
            if (phone != null && phone.length() > 0) {
                if (phone.replaceAll("\\s","").matches(recip)) {
                    if (D) Log.d(TAG, "match recipient phone = " + phone);
                    res = true;
                } else {
                    String name = getContactNameFromPhone(phone);
                    if (name != null && name.length() > 0 && name.matches(recip)) {
                        if (D) Log.d(TAG, "match recipient name = " + name);
                        res = true;
                    } else {
                        res = false;
                    }
                }
            } else {
                res = false;
            }
        }
        return res;
    }

    private boolean matchRecipient(Cursor c, FilterInfo fi, BluetoothMapAppParams ap) {
        boolean res;
        String recip = ap.getFilterRecipient();
        if (recip != null && recip.length() > 0) {
            recip = recip.replace("*", ".*");
            recip = ".*" + recip + ".*";
            if (fi.msgType == FilterInfo.TYPE_SMS) {
                res = matchRecipientSms(c, fi, recip);
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                res = matchRecipientMms(c, fi, recip);
            } else if (fi.msgType == FilterInfo.TYPE_EMAIL) {
                res = matchRecipientEmail(c, fi, recip);
            } else {
                if (D) Log.d(TAG, "Unknown msg type: " + fi.msgType);
                res = false;
            }
        } else {
            res = true;
        }
        return res;
    }

    private boolean matchOriginatorEmail(Cursor c, FilterInfo fi, String orig) {
        boolean res = false;
        String name;
        int displayNameIndex = c.getColumnIndex("displayName");
        name = c.getString(displayNameIndex);

        if (name != null && name.length() > 0) {
            if (name.matches(orig)) {
                if (D) Log.d(TAG, "match originator name = " + name);
                res = true;
            } else {
            res = false;
        }
        }
        return res;
    }

    private boolean matchOriginatorMms(Cursor c, FilterInfo fi, String orig) {
        boolean res;
        long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
        String phone = getAddressMms(mResolver, id, MMS_FROM);
        if (phone != null && phone.length() > 0) {
            if (phone.matches(orig)) {
                if (D) Log.d(TAG, "match originator phone = " + phone);
                res = true;
            } else {
                String name = getContactNameFromPhone(phone);
                if (name != null && name.length() > 0 && name.matches(orig)) {
                    if (D) Log.d(TAG, "match originator name = " + name);
                    res = true;
                } else {
                    res = false;
                }
            }
        } else {
            res = false;
        }
        return res;
    }

    private boolean matchOriginatorSms(Cursor c, FilterInfo fi, String orig) {
        boolean res;
        int msgType = c.getInt(c.getColumnIndex(Sms.TYPE));
        if (msgType == 1) {
            String phone = c.getString(c.getColumnIndex(Sms.ADDRESS));
            if (phone !=null && phone.length() > 0) {
                if (phone.replaceAll("\\s","").matches(orig)) {
                    if (D) Log.d(TAG, "match originator phone = " + phone);
                    res = true;
                } else {
                    String name = getContactNameFromPhone(phone);
                    if (name != null && name.length() > 0 && name.matches(orig)) {
                        if (D) Log.d(TAG, "match originator name = " + name);
                        res = true;
                    } else {
                        res = false;
                    }
                }
            } else {
                res = false;
            }
        }
        else {
            String phone = fi.phoneNum;
            String name = fi.phoneAlphaTag;
            if (phone != null && phone.length() > 0 && phone.replaceAll("\\s","").matches(orig)) {
                if (D) Log.d(TAG, "match originator phone = " + phone);
                res = true;
            } else if (name != null && name.length() > 0 && name.matches(orig)) {
                if (D) Log.d(TAG, "match originator name = " + name);
                res = true;
            } else {
                res = false;
            }
        }
        return res;
    }

   private boolean matchOriginator(Cursor c, FilterInfo fi, BluetoothMapAppParams ap) {
        boolean res;
        String orig = ap.getFilterOriginator();
        if (orig != null && orig.length() > 0) {
            orig = orig.replace("*", ".*");
            orig = ".*" + orig + ".*";
            if (fi.msgType == FilterInfo.TYPE_SMS) {
                res = matchOriginatorSms(c, fi, orig);
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                res = matchOriginatorMms(c, fi, orig);
            } else if (fi.msgType == FilterInfo.TYPE_EMAIL) {
                res = matchOriginatorEmail(c, fi, orig);
            } else {
                Log.d(TAG, "Unknown msg type: " + fi.msgType);
                res = false;
            }
        } else {
            res = true;
        }
        return res;
    }

    private boolean matchAddresses(Cursor c, FilterInfo fi, BluetoothMapAppParams ap) {
        if (matchOriginator(c, fi, ap) && matchRecipient(c, fi, ap)) {
            return true;
        } else {
            return false;
        }
    }


    private String getQueryWithMailBoxKey(String folder, long id) {
        Log.d(TAG, "Inside getQueryWithMailBoxKey ");
        String query = "mailboxKey = -1";;
        String folderId;
        Uri uri = Uri.parse("content://com.android.email.provider/mailbox");
        if (folder == null) {
            return null;
        }
        if (folder.contains("'")){
            folder = folder.replace("'", "''");
        }
        Cursor cr = mResolver.query(
                uri, null, "(" + ACCOUNT_KEY + "=" + id +
                ") AND (UPPER(displayName) = '"+ folder.toUpperCase()+"')" , null, null);
        if (cr != null) {
            if ( cr.moveToFirst()) {
                do {
                    folderId = cr.getString(cr.getColumnIndex("_id"));
                    query = "mailboxKey = "+ folderId;
                    break;
                } while ( cr.moveToNext());
            }
            cr.close();
        }
        if(D) Log.d(TAG, "Returning  "+query);
        return query;
    }

    private String setWhereFilterFolderTypeEmail(String folder) {
        String where = "";
        Log.d(TAG, "Inside setWhereFilterFolderTypeEmail ");
        Log.d(TAG, "folder is  " + folder);
        where = getQueryWithMailBoxKey(folder,BluetoothMapUtils.getEmailAccountId(mContext)/*1*/);
        if(D) Log.d(TAG, "where query is  " + where);
        return where;
    }

    private String setWhereFilterFolderTypeSms(String folder) {
        String where = "";
        if ("inbox".equalsIgnoreCase(folder)) {
            where = "type = 1 AND thread_id <> -1";
        }
        else if ("outbox".equalsIgnoreCase(folder)) {
            where = "(type = 4 OR type = 5 OR type = 6) AND thread_id <> -1";
        }
        else if ("sent".equalsIgnoreCase(folder)) {
            where = "type = 2 AND thread_id <> -1";
        }
        else if ("draft".equalsIgnoreCase(folder)) {
            where = "type = 3 AND thread_id <> -1";
        }
        else if ("deleted".equalsIgnoreCase(folder)) {
            where = "thread_id = -1";
        }

        return where;
    }

    private String setWhereFilterFolderTypeMms(String folder) {
        String where = "";
        if ("inbox".equalsIgnoreCase(folder)) {
            where = "msg_box = 1 AND thread_id <> -1";
        }
        else if ("outbox".equalsIgnoreCase(folder)) {
            where = "msg_box = 4 AND thread_id <> -1";
        }
        else if ("sent".equalsIgnoreCase(folder)) {
            where = "msg_box = 2 AND thread_id <> -1";
        }
        else if ("draft".equalsIgnoreCase(folder)) {
            where = "msg_box = 3 AND thread_id <> -1";
        }
        else if ("deleted".equalsIgnoreCase(folder)) {
            where = "thread_id = -1";
        }

        return where;
    }

    private String setWhereFilterFolderType(String folder, FilterInfo fi) {
        String where = "";
        if (fi.msgType == FilterInfo.TYPE_SMS) {
            where = setWhereFilterFolderTypeSms(folder);
        } else if (fi.msgType == FilterInfo.TYPE_MMS) {
            where = setWhereFilterFolderTypeMms(folder);
        } else {
            where = setWhereFilterFolderTypeEmail(folder);

        }

        return where;
    }

    private String setWhereFilterReadStatus(BluetoothMapAppParams ap, FilterInfo fi) {
        String where = "";
        if (ap.getFilterReadStatus() != -1) {
            if ((fi.msgType == FilterInfo.TYPE_SMS) || (fi.msgType == FilterInfo.TYPE_MMS)) {
               if ((ap.getFilterReadStatus() & 0x01) != 0) {
                   where = " AND read=0 ";
               }

               if ((ap.getFilterReadStatus() & 0x02) != 0) {
                   where = " AND read=1 ";
               }
            } else {
               if ((ap.getFilterReadStatus() & 0x01) != 0) {
                    where = " AND flagRead=0 ";
               }

               if ((ap.getFilterReadStatus() & 0x02) != 0) {
                    where = " AND flagRead=1 ";
               }
            }
        }

        return where;
    }

    private String setWhereFilterPeriod(BluetoothMapAppParams ap, FilterInfo fi) {
        String where = "";
        if ((ap.getFilterPeriodBegin() != -1)) {
            if (fi.msgType == FilterInfo.TYPE_SMS) {
            where = " AND date >= " + ap.getFilterPeriodBegin();
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                where = " AND date >= " + (ap.getFilterPeriodBegin() / 1000L);
            }else {
                Time time = new Time();
                try {
                    time.parse(ap.getFilterPeriodBeginString().trim());
                    where += " AND timeStamp >= " + time.toMillis(false);
                } catch (TimeFormatException e) {
                    Log.d(TAG, "Bad formatted FilterPeriodBegin, Ignore"
                          + ap.getFilterPeriodBeginString());
                }
            }
        }

        if ((ap.getFilterPeriodEnd() != -1)) {
            if (fi.msgType == FilterInfo.TYPE_SMS) {
            where += " AND date <= " + ap.getFilterPeriodEnd();
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                where += " AND date <= " + (ap.getFilterPeriodEnd() / 1000L);
            } else {
                Time time = new Time();
                try {
                    time.parse(ap.getFilterPeriodEndString().trim());
                    where += " AND timeStamp <= " + time.toMillis(false);
                } catch (TimeFormatException e) {
                    Log.d(TAG, "Bad formatted FilterPeriodEnd, Ignore"
                          + ap.getFilterPeriodEndString());
                }
            }
        }

        return where;
    }

    private String setWhereFilterPhones(String str) {
        String where = "";
        str = str.replace("*", "%");

        Cursor c = mResolver.query(ContactsContract.Contacts.CONTENT_URI, null,
            ContactsContract.Contacts.DISPLAY_NAME + " like ?",
            new String[]{str},
            ContactsContract.Contacts.DISPLAY_NAME + " ASC");

        while (c != null && c.moveToNext()) {
            String contactId = c.getString(c.getColumnIndex(ContactsContract.Contacts._ID));

            Cursor p = mResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                new String[]{contactId},
                null);

            while (p != null && p.moveToNext()) {
                String number = p.getString(
                    p.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));

                where += " address = " + "'" + number + "'";
                if (!p.isLast()) {
                    where += " OR ";
                }
            }
            if (!c.isLast()) {
                where += " OR ";
            }
            if (p != null) {
               p.close();
            }
        }
        if (c != null) {
           c.close();
        }

        if (str != null && str.length() > 0) {
            if (where.length() > 0) {
                where += " OR ";
            }
            where += " address like " + "'" + str + "'";
        }

        return where;
    }

    private String setWhereFilterOriginator(BluetoothMapAppParams ap,
        FilterInfo fi) {
        String where = "";
        String orig = ap.getFilterOriginator();

        if (orig != null && orig.length() > 0) {
            String phones = setWhereFilterPhones(orig);

            if (phones.length() > 0) {
                where = " AND ((type <> 1) OR ( " + phones + " ))";
            } else {
                where = " AND (type <> 1)";
            }

            orig = orig.replace("*", ".*");
            orig = ".*" + orig + ".*";

            boolean localPhoneMatchOrig = false;
            if (fi.phoneNum != null && fi.phoneNum.length() > 0
                && fi.phoneNum.matches(orig)) {
                localPhoneMatchOrig = true;
            }

            if (fi.phoneAlphaTag != null && fi.phoneAlphaTag.length() > 0
                && fi.phoneAlphaTag.matches(orig)) {
                localPhoneMatchOrig = true;
            }

            if (!localPhoneMatchOrig) {
                where += " AND (type = 1)";
            }
        }

        return where;
    }
    private String setWhereFilterPriority(BluetoothMapAppParams ap, FilterInfo fi) {
        String where = "";
        int pri = ap.getFilterPriority();
        /*only MMS have priority info */
        if(fi.msgType == FilterInfo.TYPE_MMS)
        {
            if(pri == 0x0002)
            {
                where += " AND " + Mms.PRIORITY + "<=" +
                    Integer.toString(PduHeaders.PRIORITY_NORMAL);
            }else if(pri == 0x0001) {
                where += " AND " + Mms.PRIORITY + "=" +
                    Integer.toString(PduHeaders.PRIORITY_HIGH);
            }
        }
        return where;
    }

    private String setWhereFilterRecipient(BluetoothMapAppParams ap,
        FilterInfo fi) {
        String where = "";
        String recip = ap.getFilterRecipient();

        if (recip != null && recip.length() > 0) {
            String phones = setWhereFilterPhones(recip);

            if (phones.length() > 0) {
                where = " AND ((type = 1) OR ( " + phones + " ))";
            } else {
                where = " AND (type = 1)";
            }

            recip = recip.replace("*", ".*");
            recip = ".*" + recip + ".*";

            boolean localPhoneMatchOrig = false;
            if (fi.phoneNum != null && fi.phoneNum.length() > 0
                && fi.phoneNum.matches(recip)) {
                localPhoneMatchOrig = true;
            }

            if (fi.phoneAlphaTag != null && fi.phoneAlphaTag.length() > 0
                && fi.phoneAlphaTag.matches(recip)) {
                localPhoneMatchOrig = true;
            }

            if (!localPhoneMatchOrig) {
                where += " AND (type <> 1)";
            }
        }

        return where;
    }

    private String setWhereFilterAccountKey(long id) {
        String where = "";
        where = "accountkey=" + id;
        return where;
    }

    private String setWhereFilterMessagekey(long id) {
        String where = "";
        where = " messageKey = " + id;
        return where;
    }

    private String setWhereFilterServerId(String path) {
        String where = "";
        if(path.equals("msg")) {
           where = "serverId =" + DISPLAY_NAME;
        }
        else {
           where = "serverId LIKE '%" + path + "/%'";
        }
        return where;
    }

    private String setWhereFilter(String folder, FilterInfo fi, BluetoothMapAppParams ap) {
        String where = "";

        where += setWhereFilterFolderType(folder, fi);
        where += setWhereFilterReadStatus(ap, fi);
        where += setWhereFilterPeriod(ap, fi);
        where += setWhereFilterPriority(ap,fi);
        /* where += setWhereFilterOriginator(ap, fi); */
        /* where += setWhereFilterRecipient(ap, fi); */

        if (D) Log.d(TAG, "where: " + where);

        return where;
    }

    private boolean smsSelected(FilterInfo fi, BluetoothMapAppParams ap) {
        int msgType = ap.getFilterMessageType();
        int phoneType = fi.phoneType;

        if (msgType == -1)
            return true;
        if ((msgType & 0x03) == 0)
            return true;

        if (((msgType & 0x01) == 0) && (phoneType == TelephonyManager.PHONE_TYPE_GSM))
            return true;

        if (((msgType & 0x02) == 0) && (phoneType == TelephonyManager.PHONE_TYPE_CDMA))
            return true;

        return false;
    }

    private boolean mmsSelected(FilterInfo fi, BluetoothMapAppParams ap) {
        int msgType = ap.getFilterMessageType();

        if (msgType == -1)
            return true;

        if ((msgType & 0x08) == 0)
            return true;

        return false;
    }

    private void setFilterInfo(FilterInfo fi) {
        TelephonyManager tm = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            fi.phoneType = tm.getPhoneType();
            fi.phoneNum = tm.getLine1Number();
            fi.phoneAlphaTag = tm.getLine1AlphaTag();
            if (D) Log.d(TAG, "phone type = " + fi.phoneType +
                " phone num = " + fi.phoneNum +
                " phone alpha tag = " + fi.phoneAlphaTag);
        }
    }

    /**
     * Return Email sub folder list for the id and serverId path
     * @param context the calling Context
     * @param id the email account id
     * @return the list of	server id's of Email sub folder
     */
     public List<String> getEmailFolderListAtPath(Context context, long id, String path) {
         if (V) Log.v(TAG, "getEmailFolderListAtPath: id = " + id + "path: " + path);
         String where = "";
         ArrayList<String> list = new ArrayList<String>();
         where += setWhereFilterAccountKey(id);
         where += " AND ";
         where += setWhereFilterServerId(path);
         Cursor cr = context.getContentResolver().query(EMAIL_BOX_URI, null, where, null, null);
         if (cr != null) {
             if (cr.moveToFirst()) {
                 final int columnIndex = cr.getColumnIndex(SERVER_ID);
                 do {
                    final String value = cr.getString(columnIndex);
                    list.add(value);
                    if (V) Log.v(TAG, "adding: " + value);
                  } while (cr.moveToNext());
              }
              cr.close();
          }
          return list;
       }

    private boolean emailSelected(FilterInfo fi, BluetoothMapAppParams ap) {
        int msgType = ap.getFilterMessageType();
        if(D) Log.d(TAG, "emailSelected, msgType : " + msgType);

        if (msgType == -1)
            return true;

        if ((msgType & 0x04) == 0) {
           return true;
        } else {
           if (V) Log.v(TAG, "Invalid Message Filter");
           return false;
        }

    }

    private void printEmail(Cursor c) {
        if (D) Log.d(TAG, "printEmail " +
            c.getLong(c.getColumnIndex("_id")) +
            "\n   " + "displayName" + " : " + c.getString(c.getColumnIndex("displayName")) +
            "\n   " + "subject" + " : " + c.getString(c.getColumnIndex("subject")) +
            "\n   " + "flagRead" + " : " + c.getString(c.getColumnIndex("flagRead")) +
            "\n   " + "flagAttachment" + " : " + c.getInt(c.getColumnIndex("flagAttachment")) +
            "\n   " + "flags" + " : " + c.getInt(c.getColumnIndex("flags")) +
            "\n   " + "syncServerId" + " : " + c.getInt(c.getColumnIndex("syncServerId")) +
            "\n   " + "clientId" + " : " + c.getInt(c.getColumnIndex("clientId")) +
            "\n   " + "messageId" + " : " + c.getInt(c.getColumnIndex("messageId")) +
            "\n   " + "timeStamp" + " : " + c.getInt(c.getColumnIndex("timeStamp")) +
            "\n   " + "mailboxKey" + " : " + c.getInt(c.getColumnIndex("mailboxKey")) +
            "\n   " + "accountKey" + " : " + c.getInt(c.getColumnIndex("accountKey")) +
            "\n   " + "fromList" + " : " + c.getString(c.getColumnIndex("fromList")) +
            "\n   " + "toList" + " : " + c.getString(c.getColumnIndex("toList")) +
            "\n   " + "ccList" + " : " + c.getString(c.getColumnIndex("ccList")) +
            "\n   " + "bccList" + " : " + c.getString(c.getColumnIndex("bccList")) +
            "\n   " + "replyToList" + " : " + c.getString(c.getColumnIndex("replyToList")) +
            "\n   " + "meetingInfo" + " : " + c.getString(c.getColumnIndex("meetingInfo")) +
            "\n   " + "snippet" + " : " + c.getString(c.getColumnIndex("snippet")) +
            "\n   " + "protocolSearchInfo" + " : " + c.getString(c.getColumnIndex("protocolSearchInfo")) +
            "\n   " + "threadTopic" + " : " + c.getString(c.getColumnIndex("threadTopic")));
    }

    /**
     * Get the folder name (MAP representation) for Email based on the
     * mailboxKey value in message table
     */
    private String getContainingFolderEmail(int folderId) {
        Cursor cr;
        String folderName = null;
        String whereClause = "_id = " + folderId;
        cr = mResolver.query(
                Uri.parse("content://com.android.email.provider/mailbox"),
                null, whereClause, null, null);
        if (cr != null) {
            if (cr.getCount() > 0) {
                cr.moveToFirst();
                folderName = cr.getString(cr.getColumnIndex(MessageColumns.DISPLAY_NAME));
            }
            cr.close();
        }
        if (V) Log.v(TAG, "getContainingFolderEmail returning " + folderName);
        return folderName;
    }


    private void extractEmailAddresses(long id, BluetoothMapbMessageMmsEmail message) {
        if (V) Log.v(TAG, "extractEmailAddresses with id " + id);
        String urlEmail = "content://com.android.email.provider/message";
        Uri uriEmail = Uri.parse(urlEmail);
        StringTokenizer emailId;
        String tempEmail = null;
        Cursor c = mResolver.query(uriEmail, EMAIL_PROJECTION, "_id = " + id, null, null);
        if (c != null && c.moveToFirst()) {
            String senderName = null;
            if((senderName = c.getString(c.getColumnIndex(MessageColumns.FROM_LIST))) != null ) {
                if(V) Log.v(TAG, " senderName " + senderName);
                if(senderName.contains("")){
                   String[] senderStr = senderName.split("");
                   if(senderStr !=null && senderStr.length > 0){
                      if(V) Log.v(TAG, " senderStr[1] " + senderStr[1].trim());
                      if(V) Log.v(TAG, " senderStr[0] " + senderStr[0].trim());
                      setVCardFromEmailAddress(message, senderStr[1].trim(), true);
                      message.addFrom(null, senderStr[0].trim());
                   }
                } else {
                       if(V) Log.v(TAG, " senderStr is" + senderName.trim());
                       setVCardFromEmailAddress(message, senderName.trim(), true);
                        message.addFrom(null, senderName.trim());
                }
            }
            String recipientName = null;
            String multiRecepients = null;
            if((recipientName = c.getString(c.getColumnIndex(MessageColumns.TO_LIST))) != null){
                if(V) Log.v(TAG, " recipientName " + recipientName);
                if(recipientName.contains("")){
                   String[] recepientStr = recipientName.split("");
                   if(recepientStr !=null && recepientStr.length > 0){
                      if (V){
                          Log.v(TAG, " recepientStr[1] " + recepientStr[1].trim());
                          Log.v(TAG, " recepientStr[0] " + recepientStr[0].trim());
                      }
                      setVCardFromEmailAddress(message, recepientStr[1].trim(), false);
                      message.addTo(recepientStr[1].trim(), recepientStr[0].trim());
                   }
                } else if(recipientName.contains("")){
                      multiRecepients = recipientName.replace('', ';');
                      if(multiRecepients != null){
                         if (V){
                             Log.v(TAG, " Setting ::Recepient name :: " + multiRecepients.trim());
                         }
                         emailId = new StringTokenizer(multiRecepients.trim(),";");
                         do {
                            setVCardFromEmailAddress(message, emailId.nextElement().toString(), false);
                         } while(emailId.hasMoreElements());

                            message.addTo(multiRecepients.trim(), multiRecepients.trim());
                      }
                } else if(recipientName.contains(",")){
                      multiRecepients = recipientName.replace(", \"", "; \"");
                      if(multiRecepients != null){
                         if (V){
                             Log.v(TAG, "Setting ::Recepient name :: " + multiRecepients.trim());
                         }
                         emailId = new StringTokenizer(multiRecepients.trim(),";");
                         do {
                            tempEmail = emailId.nextElement().toString();
                            setVCardFromEmailAddress(message, tempEmail, false);
                            message.addTo(null, tempEmail);
                         } while(emailId.hasMoreElements());
                      }
                } else {
                      Log.v(TAG, " Setting ::Recepient name :: " + recipientName.trim());
                      setVCardFromEmailAddress(message, recipientName.trim(), false);
                      message.addTo(null, recipientName.trim());
                 }
             }

            recipientName = null;
            multiRecepients = null;
            if((recipientName = c.getString(c.getColumnIndex(MessageColumns.CC_LIST))) != null){
                if(V) Log.v(TAG, " recipientName " + recipientName);
                if(recipientName.contains("^B")){
                   String[] recepientStr = recipientName.split("^B");
                   if(recepientStr !=null && recepientStr.length > 0){
                      if (V){
                          Log.v(TAG, " recepientStr[1] " + recepientStr[1].trim());
                          Log.v(TAG, " recepientStr[0] " + recepientStr[0].trim());
                      }
                } else if(recipientName.contains("")){
                      multiRecepients = recipientName.replace('', ';');
                      setVCardFromEmailAddress(message, recepientStr[1].trim(), false);
                      message.addCc(recepientStr[1].trim(), recepientStr[0].trim());
                   }
                      if(multiRecepients != null){
                         if (V){
                             Log.v(TAG, " Setting ::Recepient name :: " + multiRecepients.trim());
                         }
                         emailId = new StringTokenizer(multiRecepients.trim(),";");
                         do {
                            setVCardFromEmailAddress(message, emailId.nextElement().toString(), false);
                         } while(emailId.hasMoreElements());

                            message.addCc(multiRecepients.trim(), multiRecepients.trim());
                      }
                } else if(recipientName.contains(",")){
                      multiRecepients = recipientName.replace(", \"", "; \"");
if(V) Log.v(TAG, " After replacing  " + multiRecepients);

                      if(multiRecepients != null){
                         if (V){
                             Log.v(TAG, "Setting ::Recepient name :: " + multiRecepients.trim());
                         }
                         emailId = new StringTokenizer(multiRecepients.trim(),";");
                         do {
                            tempEmail = emailId.nextElement().toString();
                            setVCardFromEmailAddress(message, tempEmail, false);
                            message.addCc(null, tempEmail);
                         } while(emailId.hasMoreElements());
                      }
                } else {
                      Log.v(TAG, " Setting ::Recepient name :: " + recipientName.trim());
                      setVCardFromEmailAddress(message, recipientName.trim(), false);
                      message.addCc(null, recipientName.trim());
                 }
             }
        }
    }

    /**
     * Read out a mms data part and return the data in a byte array.
     * @param partid the content provider id of the mms.
     * @return
     */
    private byte[] readEmailDataPart(long partid) {
        String uriStr = String.format("content://mms/part/%d", partid);
        Uri uriAddress = Uri.parse(uriStr);
        InputStream is = null;
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        int bufferSize = 8192;
        byte[] buffer = new byte[bufferSize];
        byte[] retVal = null;

        try {
            is = mResolver.openInputStream(uriAddress);
            int len = 0;
            while ((len = is.read(buffer)) != -1) {
              os.write(buffer, 0, len); // We need to specify the len, as it can be != bufferSize
            }
            retVal = os.toByteArray();
        } catch (IOException e) {
            // do nothing for now
            Log.w(TAG,"Error reading part data",e);
        } finally {
            try {
                os.close();
                is.close();
            } catch (IOException e) {
            }
        }
        return retVal;
    }


    /**
     * Read out the mms parts and update the bMessage object provided i {@linkplain message}
     * @param id the content provider ID of the message
     * @param message the bMessage object to add the information to
     */
    private void extractEmailParts(long id, BluetoothMapbMessageMmsEmail message)
    {
        if (V) Log.v(TAG, "extractEmailParts with id " + id);
        String where = setWhereFilterMessagekey(id);
        String emailBody = "";
        Uri uriAddress = Uri.parse("content://com.android.email.provider/body");
        BluetoothMapbMessageMmsEmail.MimePart part;
        Cursor c = mResolver.query(
            uriAddress,
            null,
            where,
            null, null);

        if(c != null) {
           if (V) Log.v(TAG, "cursor not null");
           if (c.moveToFirst()) {
               emailBody = c.getString(c.getColumnIndex("textContent"));
               if (emailBody == null || emailBody.length() == 0) {
                   String msgBody = c.getString(c.getColumnIndex("htmlContent"));
                   if (msgBody != null) {
                       msgBody = msgBody.replaceAll("(?s)(<title>)(.*?)(</title>)", "");
                       msgBody = msgBody.replaceAll("(?s)(<style type=\"text/css\".*?>)(.*?)(</style>)", "");
                       CharSequence msgText = Html.fromHtml(msgBody);
                       emailBody = msgText.toString();
                       emailBody = emailBody.replaceAll("(?s)(<!--)(.*?)(-->)", "");
                       // Solves problem with Porche Car-kit and Gmails.
                       // Changes unix style line conclusion to DOS style
                       emailBody = emailBody.replaceAll("(?s)(\\r)", "");
                       emailBody = emailBody.replaceAll("(?s)(\\n)", "\r\n");
                   }
                   message.setEmailBody(emailBody);
               }

                Long partId = c.getLong(c.getColumnIndex(BaseColumns._ID));
                String contentType = "Content-Type: text/plain; charset=\"UTF-8\"";
                String name = null;//c.getString(c.getColumnIndex("displayName"));
                  String text = null;

                if(D) Log.d(TAG, "     _id : " + partId +
                        "\n     ct : " + contentType +
                        "\n     partname : " + name);

                part = message.addMimePart();
                part.contentType = contentType;
                part.partName = name;

                try {
                    if(emailBody != null) {
                        part.data = emailBody.getBytes("UTF-8");
                        part.charsetName = "utf-8";
                    }
                } catch (NumberFormatException e) {
                    Log.d(TAG,"extractEmailParts",e);
                    part.data = null;
                    part.charsetName = null;
                } catch (UnsupportedEncodingException e) {
                    Log.d(TAG,"extractEmailParts",e);
                    part.data = null;
                    part.charsetName = null;
                } finally {
                }
           }
       }
        message.updateCharset();
        message.setEncoding("8BIT");
    }

    /**
     *
     * @param id the content provider id for the message to fetch.
     * @param appParams The application parameter object received from the client.
     * @return a byte[] containing the utf-8 encoded bMessage to send to the client.
     * @throws UnsupportedEncodingException if UTF-8 is not supported,
     * which is guaranteed to be supported on an android device
     */
    public byte[] getEmailMessage(long id, BluetoothMapAppParams appParams) throws UnsupportedEncodingException {
        if (V) Log.v(TAG, "getEmailMessage with is " + id);
        int msgBox, threadId;
        String urlEmail = "content://com.android.email.provider/message";
        Uri uriEmail = Uri.parse(urlEmail);
        BluetoothMapbMessageMmsEmail message = new BluetoothMapbMessageMmsEmail();
        Cursor c = mResolver.query(uriEmail, EMAIL_PROJECTION, "_id = " + id, null, null);
        if(c != null && c.moveToFirst())
        {
            message.setType(TYPE.EMAIL);

            // The EMAIL info:
            if (c.getString(c.getColumnIndex(MessageColumns.FLAG_READ)).equalsIgnoreCase("1"))
                message.setStatus(true);
            else
                message.setStatus(false);

            msgBox = c.getInt(c.getColumnIndex(MessageColumns.MAILBOX_KEY));
            message.setFolder(getContainingFolderEmail(msgBox));

            message.setSubject(c.getString(c.getColumnIndex(MessageColumns.SUBJECT)));
            message.setContentType("Content-Type: text/plain; charset=\"UTF-8\"");
            message.setDate(c.getLong(c.getColumnIndex(MessageColumns.TIMESTAMP)));
            message.setIncludeAttachments(appParams.getAttachment() == 0 ? false : true);

            // The parts
            extractEmailParts(id, message);

            // The addresses
            extractEmailAddresses(id, message);

            c.close();

            return message.encodeEmail();
        }
        else if(c != null) {
            c.close();
        }

        throw new IllegalArgumentException("MMS handle not found");
    }

    public BluetoothMapMessageListing msgListingEmail(String folder, BluetoothMapAppParams ap) {
        Log.d(TAG, "msgListing: folder = " + folder);
        String urlEmail = "content://com.android.email.provider/message";
        Uri uriEmail = Uri.parse(urlEmail);
        BluetoothMapMessageListing bmList = new BluetoothMapMessageListing();
        BluetoothMapMessageListingElement e = null;

        /* Cache some info used throughout filtering */
        FilterInfo fi = new FilterInfo();
        setFilterInfo(fi);

        if (emailSelected(fi, ap)) {
            fi.msgType = FilterInfo.TYPE_EMAIL;

            String where = setWhereFilter(folder, fi, ap);
            Log.d(TAG, "where clause is = " + where);
            try {
                Cursor c = mResolver.query(uriEmail,
                EMAIL_PROJECTION, where, null, "timeStamp desc");
                if(c == null) {
                   Log.e(TAG, "Cursor is null. Returning from here");
                }

                if (c != null) {
                    while (c.moveToNext()) {
                        if (matchAddresses(c, fi, ap)) {
                        printEmail(c);
                        e = element(c, fi, ap);
                        bmList.add(e);
                        }
                    }
                c.close();
                }
            } catch (SQLiteException es) {
             Log.e(TAG, "SQLite exception: " + es);
            }
        }

        /* Enable this if post sorting and segmenting needed */
        bmList.sort();
        bmList.segment(ap.getMaxListCount(), ap.getStartOffset());

        return bmList;
    }

    public int msgListingSizeEmail(String folder, BluetoothMapAppParams ap) {
        if (D) Log.d(TAG, "msgListingSize: folder = " + folder);
        int cnt = 0;
        String urlEmail = "content://com.android.email.provider/message";
        Uri uriEmail = Uri.parse(urlEmail);

        /* Cache some info used throughout filtering */
        FilterInfo fi = new FilterInfo();
        setFilterInfo(fi);

        if (emailSelected(fi, ap)) {
            fi.msgType = FilterInfo.TYPE_EMAIL;
            String where = setWhereFilter(folder, fi, ap);
            Cursor c = mResolver.query(uriEmail,
                EMAIL_PROJECTION, where, null, "timeStamp desc");

            if (c != null) {
                cnt += c.getCount();
                c.close();
            }
        }

        if (D) Log.d(TAG, "msgListingSize: size = " + cnt);
        return cnt;
    }
    /**
     * Return true if there are unread messages in the requested list of messages
     * @param folder folder where the message listing should come from
     * @param ap application parameter object
     * @return true if unread messages are in the list, else false
     */
    public boolean msgListingHasUnreadEmail(String folder, BluetoothMapAppParams ap) {
        if (D) Log.d(TAG, "msgListingHasUnread: folder = " + folder);
        int cnt = 0;
        String urlEmail = "content://com.android.email.provider/message";
        Uri uriEmail = Uri.parse(urlEmail);

        /* Cache some info used throughout filtering */
        FilterInfo fi = new FilterInfo();
        setFilterInfo(fi);

        if (emailSelected(fi, ap)) {
            fi.msgType = FilterInfo.TYPE_EMAIL;
            String where = setWhereFilterFolderType(folder, fi);
            where += " AND flagRead=0 ";
            where += setWhereFilterPeriod(ap, fi);
            Cursor c = mResolver.query(uriEmail,
                EMAIL_PROJECTION, where, null, "timeStamp desc");

            if (c != null) {
                cnt += c.getCount();
                c.close();
            }
        }

        if (D) Log.d(TAG, "msgListingHasUnread: numUnread = " + cnt);
        return (cnt>0)?true:false;
    }

    public BluetoothMapMessageListing msgListing(String folder, BluetoothMapAppParams ap) {
        Log.d(TAG, "msgListing: folder = " + folder);
        BluetoothMapMessageListing bmList = new BluetoothMapMessageListing();
        BluetoothMapMessageListingElement e = null;
        msgListingFolder = folder;
        Log.d(TAG, "msgListingFolder = " + msgListingFolder);

        /* Cache some info used throughout filtering */
        FilterInfo fi = new FilterInfo();
        setFilterInfo(fi);

        if (smsSelected(fi, ap)) {
            fi.msgType = FilterInfo.TYPE_SMS;
            if(ap.getFilterPriority() != 1){ /*SMS cannot have high priority*/
                String where = setWhereFilter(folder, fi, ap);

                Cursor c = mResolver.query(Sms.CONTENT_URI,
                    SMS_PROJECTION, where, null, "date DESC");

                if (c != null) {
                    while (c.moveToNext()) {
                        if (matchAddresses(c, fi, ap)) {
                            printSms(c);
                            e = element(c, fi, ap);
                            bmList.add(e);
                        }
                    }
                    c.close();
                }
            }
        }

        if (mmsSelected(fi, ap)) {
            fi.msgType = FilterInfo.TYPE_MMS;

            String where = setWhereFilter(folder, fi, ap);

            Cursor c = mResolver.query(Mms.CONTENT_URI,
                MMS_PROJECTION, where, null, "date DESC");

            if (c != null) {
                int cnt = 0;
                while (c.moveToNext()) {
                    if (matchAddresses(c, fi, ap)) {
                        printMms(c);
                        e = element(c, fi, ap);
                        bmList.add(e);
                    }
                }
                c.close();
            }
        }

        /* Enable this if post sorting and segmenting needed */
        bmList.sort();
        bmList.segment(ap.getMaxListCount(), ap.getStartOffset());

        return bmList;
    }

    public int msgListingSize(String folder, BluetoothMapAppParams ap) {
        if (D) Log.d(TAG, "msgListingSize: folder = " + folder);
        int cnt = 0;

        /* Cache some info used throughout filtering */
        FilterInfo fi = new FilterInfo();
        setFilterInfo(fi);

        if (smsSelected(fi, ap)) {
            fi.msgType = FilterInfo.TYPE_SMS;
            String where = setWhereFilter(folder, fi, ap);
            Cursor c = mResolver.query(Sms.CONTENT_URI,
                SMS_PROJECTION, where, null, "date DESC");

            if (c != null) {
                cnt = c.getCount();
                c.close();
            }
        }

        if (mmsSelected(fi, ap)) {
            fi.msgType = FilterInfo.TYPE_MMS;
            String where = setWhereFilter(folder, fi, ap);
            Cursor c = mResolver.query(Mms.CONTENT_URI,
                MMS_PROJECTION, where, null, "date DESC");

            if (c != null) {
                cnt += c.getCount();
                c.close();
            }
        }

        if (D) Log.d(TAG, "msgListingSize: size = " + cnt);
        return cnt;
    }
    /**
     * Return true if there are unread messages in the requested list of messages
     * @param folder folder where the message listing should come from
     * @param ap application parameter object
     * @return true if unread messages are in the list, else false
     */
    public boolean msgListingHasUnread(String folder, BluetoothMapAppParams ap) {
        if (D) Log.d(TAG, "msgListingHasUnread: folder = " + folder);
        int cnt = 0;

        /* Cache some info used throughout filtering */
        FilterInfo fi = new FilterInfo();
        setFilterInfo(fi);

        if (smsSelected(fi, ap)) {
            fi.msgType = FilterInfo.TYPE_SMS;
            String where = setWhereFilterFolderType(folder, fi);
            where += " AND read=0 ";
            where += setWhereFilterPeriod(ap, fi);
            Cursor c = mResolver.query(Sms.CONTENT_URI,
                    SMS_PROJECTION, where, null, "date DESC");

            if (c != null) {
                cnt = c.getCount();
                c.close();
            }
        }

        if (mmsSelected(fi, ap)) {
            fi.msgType = FilterInfo.TYPE_MMS;
            String where = setWhereFilterFolderType(folder, fi);
            where += " AND read=0 ";
            where += setWhereFilterPeriod(ap, fi);
            Cursor c = mResolver.query(Mms.CONTENT_URI,
                MMS_PROJECTION, where, null, "date DESC");

            if (c != null) {
                cnt += c.getCount();
                c.close();
            }
        }

        if (D) Log.d(TAG, "msgListingHasUnread: numUnread = " + cnt);
        return (cnt>0)?true:false;
    }

    /**
     * Get the folder name of an SMS message or MMS message.
     * @param c the cursor pointing at the message
     * @return the folder name.
     */
    private String getFolderName(int type, int threadId) {

        if(threadId == -1)
            return "deleted";

        switch(type) {
        case 1:
            return "inbox";
        case 2:
            return "sent";
        case 3:
            return "draft";
        case 4: // Just name outbox, failed and queued "outbox"
        case 5:
        case 6:
            return "outbox";
        }
        return "";
    }
    public void msgUpdate() {
        if (D) Log.d(TAG, "Message Update");
        long accountId = BluetoothMapUtils.getEmailAccountId(mContext);
        if (V) Log.v(TAG, " Account id for Inbox Update: " +accountId);
        Intent emailIn = new Intent();
        emailIn.setAction("com.android.email.intent.action.MAIL_SERVICE_WAKEUP");
        emailIn.putExtra("com.android.email.intent.extra.ACCOUNT", accountId);
        mContext.sendBroadcast(emailIn);
    }

    public byte[] getMessage(String handle, BluetoothMapAppParams appParams) throws UnsupportedEncodingException{
        TYPE type = BluetoothMapUtils.getMsgTypeFromHandle(handle);
        long id = BluetoothMapUtils.getCpHandle(handle);
        switch(type) {
        case SMS_GSM:
        case SMS_CDMA:
            return getSmsMessage(id, appParams.getCharset());
        case MMS:
            if(appParams.getCharset()== MAP_MESSAGE_CHARSET_NATIVE) {
                throw new IllegalArgumentException("Invalid Charset: Native for Message Type MMS");
            }
            return getMmsMessage(id, appParams);
        case EMAIL:
            if(appParams.getCharset()== MAP_MESSAGE_CHARSET_NATIVE) {
                throw new IllegalArgumentException("Invalid Charset: Native for Message Type Email");
            }
            return getEmailMessage(id, appParams);
        }
        throw new IllegalArgumentException("Invalid message handle.");
    }

    private void setVCardFromEmailAddress(BluetoothMapbMessage message, String emailAddr, boolean incoming) {
        if(D) Log.d(TAG, "setVCardFromEmailAddress, emailAdress is " +emailAddr);
        String contactId = null, contactName = null;
        String[] phoneNumbers = {""};
        String[] emailAddresses = new String[1];
        StringTokenizer emailId;
        Cursor p;

        if(incoming == true) {
           emailAddresses[0] = emailAddr;
           if(V) Log.v(TAG,"Adding addOriginator " + emailAddresses[0]);
            message.addOriginator(emailAddr, phoneNumbers, emailAddresses);
        }
        else
        {
            emailAddresses[0] = emailAddr;
           if(V) Log.v(TAG,"Adding Receipient " + emailAddresses[0]);
            message.addRecipient(emailAddr, phoneNumbers, emailAddresses);
        }
    }

    private void setVCardFromPhoneNumber(BluetoothMapbMessage message, String phone, boolean incoming) {
        String contactId = null, contactName = null;
        String[] phoneNumbers = null;
        String[] emailAddresses = null;
        Cursor p;

        Uri uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phone));

        String[] projection = {Contacts._ID, Contacts.DISPLAY_NAME};
        String selection = Contacts.IN_VISIBLE_GROUP + "=1";
        String orderBy = Contacts._ID + " ASC";

        // Get the contact _ID and name
        p = mResolver.query(uri, projection, selection, null, orderBy);
        if (p != null && p.getCount() >= 1) {
            p.moveToFirst();
            contactId = p.getString(p.getColumnIndex(Contacts._ID));
            contactName = p.getString(p.getColumnIndex(Contacts.DISPLAY_NAME));
        }
        if (p != null)
           p.close();

        // The phone number we got is the one we will use
        phoneNumbers = new String[1];
        phoneNumbers[0] = phone;

        // Get emails only if we have a contact
        if(contactId != null) {
            // Fetch contact e-mail addresses
            p = mResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, null,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                    new String[]{contactId},
                    null);
            if(p != null) {
                int i = 0;
                emailAddresses = new String[p.getCount()];
                while (p != null && p.moveToNext()) {
                    String emailAddress = p.getString(
                        p.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS));
                    emailAddresses[i++] = emailAddress;
                }
                p.close();
            }
        }
        if(incoming == true)
            message.addOriginator(contactName, contactName, phoneNumbers, emailAddresses); // Use version 3.0 as we only have a formatted name
        else
            message.addRecipient(contactName, contactName, phoneNumbers, emailAddresses); // Use version 3.0 as we only have a formatted name
    }

    public static final int MAP_MESSAGE_CHARSET_NATIVE = 0;
    public static final int MAP_MESSAGE_CHARSET_UTF8 = 1;

    public byte[] getSmsMessage(long id, int charset) throws UnsupportedEncodingException{
        int type, threadId;
        long time = -1;
        String msgBody;
        BluetoothMapbMessageSms message = new BluetoothMapbMessageSms();
        TelephonyManager tm = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
        Cursor c = mResolver.query(Sms.CONTENT_URI, SMS_PROJECTION, "_ID = " + id, null, null);

        if(c != null && c.moveToFirst())
        {

            if(V) Log.v(TAG,"c.count: " + c.getCount());

            if (tm.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
                message.setType(TYPE.SMS_GSM);
            } else if (tm.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
                message.setType(TYPE.SMS_CDMA);
            }

            String read = c.getString(c.getColumnIndex(Sms.READ));
            if (read.equalsIgnoreCase("1"))
                message.setStatus(true);
            else
                message.setStatus(false);

            type = c.getInt(c.getColumnIndex(Sms.TYPE));
            threadId = c.getInt(c.getColumnIndex(Sms.THREAD_ID));
            message.setFolder(getFolderName(type, threadId));

            msgBody = c.getString(c.getColumnIndex(Sms.BODY));

            String phone = c.getString(c.getColumnIndex(Sms.ADDRESS));

            time = c.getLong(c.getColumnIndex(Sms.DATE));
            if(type == 1) // Inbox message needs to set the vCard as originator
                setVCardFromPhoneNumber(message, phone, true);
            else          // Other messages sets the vCard as the recipient
                setVCardFromPhoneNumber(message, phone, false);

            if(charset == MAP_MESSAGE_CHARSET_NATIVE) {
                if(type == 1) //Inbox
                    message.setSmsBodyPdus(BluetoothMapSmsPdu.getDeliverPdus(msgBody, phone, time));
                else
                    message.setSmsBodyPdus(BluetoothMapSmsPdu.getSubmitPdus(msgBody, phone));
            } else /*if (charset == MAP_MESSAGE_CHARSET_UTF8)*/ {
                message.setSmsBody(msgBody);
            }

            c.close();

            return message.encode();
        }
        throw new IllegalArgumentException("SMS handle not found");
    }

    private void extractMmsAddresses(long id, BluetoothMapbMessageMmsEmail message) {
        final String[] projection = null;
        String selection = new String("msg_id=" + id);
        String uriStr = String.format("content://mms/%d/addr", id);
        Uri uriAddress = Uri.parse(uriStr);
        Cursor c = mResolver.query(
            uriAddress,
            projection,
            selection,
            null, null);
        /* TODO: Change the setVCard...() to return the vCard, and use the name in message.addXxx() */
        if (c != null && c.moveToFirst()) {
            do {
                String address = c.getString(c.getColumnIndex("address"));
                Integer type = c.getInt(c.getColumnIndex("type"));
                switch(type) {
                case MMS_FROM:
                    setVCardFromPhoneNumber(message, address, true);
                    message.addFrom(null, address);
                    break;
                case MMS_TO:
                    setVCardFromPhoneNumber(message, address, false);
                    message.addTo(null, address);
                    break;
                case MMS_CC:
                    setVCardFromPhoneNumber(message, address, false);
                    message.addCc(null, address);
                    break;
                case MMS_BCC:
                    setVCardFromPhoneNumber(message, address, false);
                    message.addBcc(null, address);
                default:
                    break;
                }
            } while(c.moveToNext());
        }
    }

    /**
     * Read out a mms data part and return the data in a byte array.
     * @param partid the content provider id of the mms.
     * @return
     */
    private byte[] readMmsDataPart(long partid) {
        String uriStr = String.format("content://mms/part/%d", partid);
        Uri uriAddress = Uri.parse(uriStr);
        InputStream is = null;
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        int bufferSize = 8192;
        byte[] buffer = new byte[bufferSize];
        byte[] retVal = null;

        try {
            is = mResolver.openInputStream(uriAddress);
            int len = 0;
            while ((len = is.read(buffer)) != -1) {
              os.write(buffer, 0, len); // We need to specify the len, as it can be != bufferSize
            }
            retVal = os.toByteArray();
        } catch (IOException e) {
            // do nothing for now
            Log.w(TAG,"Error reading part data",e);
        } finally {
            try {
                os.close();
                is.close();
            } catch (IOException e) {
            }
        }
        return retVal;
    }

    /**
     * Read out the mms parts and update the bMessage object provided i {@linkplain message}
     * @param id the content provider ID of the message
     * @param message the bMessage object to add the information to
     */
    private void extractMmsParts(long id, BluetoothMapbMessageMmsEmail message)
    {
        /* TODO: If the attachment appParam is set to "no", only add the text parts.
         * (content type contains "text" - case insensitive) */
        final String[] projection = null;
        String selection = new String("mid=" + id);
        String uriStr = String.format("content://mms/%d/part", id);
        Uri uriAddress = Uri.parse(uriStr);
        BluetoothMapbMessageMmsEmail.MimePart part;
        Cursor c = mResolver.query(
            uriAddress,
            projection,
            selection,
            null, null);

        if (c != null && c.moveToFirst()) {
            do {
                Long partId = c.getLong(c.getColumnIndex(BaseColumns._ID));
                String contentType = c.getString(c.getColumnIndex("ct"));
                String name = c.getString(c.getColumnIndex("name"));
                String charset = c.getString(c.getColumnIndex("chset"));
                String filename = c.getString(c.getColumnIndex("fn"));
                String text = c.getString(c.getColumnIndex("text"));
                Integer fd = c.getInt(c.getColumnIndex("_data"));
                String cid = c.getString(c.getColumnIndex("cid"));
                String cl = c.getString(c.getColumnIndex("cl"));
                String cdisp = c.getString(c.getColumnIndex("cd"));

                if(D) Log.d(TAG, "     _id : " + partId +
                        "\n     ct : " + contentType +
                        "\n     partname : " + name +
                        "\n     charset : " + charset +
                        "\n     filename : " + filename +
                        "\n     text : " + text +
                        "\n     fd : " + fd +
                        "\n     cid : " + cid +
                        "\n     cl : " + cl +
                        "\n     cdisp : " + cdisp);

                part = message.addMimePart();
                part.contentType = contentType;
                part.partName = name;
                part.contentId = cid;
                part.contentLocation = cl;
                part.contentDisposition = cdisp;

                try {
                    if(text != null) {
                        part.data = text.getBytes("UTF-8");
                        part.charsetName = "utf-8";
                    }
                    else {
                        part.data = readMmsDataPart(partId);
                        if(charset != null)
                            part.charsetName = CharacterSets.getMimeName(Integer.parseInt(charset));
                    }
                } catch (NumberFormatException e) {
                    Log.d(TAG,"extractMmsParts",e);
                    part.data = null;
                    part.charsetName = null;
                } catch (UnsupportedEncodingException e) {
                    Log.d(TAG,"extractMmsParts",e);
                    part.data = null;
                    part.charsetName = null;
                } finally {
                }
                part.fileName = filename;
            } while(c.moveToNext());
        }
        message.updateCharset();
    }

    /**
     *
     * @param id the content provider id for the message to fetch.
     * @param appParams The application parameter object received from the client.
     * @return a byte[] containing the utf-8 encoded bMessage to send to the client.
     * @throws UnsupportedEncodingException if UTF-8 is not supported,
     * which is guaranteed to be supported on an android device
     */
    public byte[] getMmsMessage(long id, BluetoothMapAppParams appParams) throws UnsupportedEncodingException {
        int msgBox, threadId;
        BluetoothMapbMessageMmsEmail message = new BluetoothMapbMessageMmsEmail();
        Cursor c = mResolver.query(Mms.CONTENT_URI, MMS_PROJECTION, "_ID = " + id, null, null);
        if(c != null && c.moveToFirst())
        {
            message.setType(TYPE.MMS);

            // The MMS info:
            String read = c.getString(c.getColumnIndex(Mms.READ));
            if (read.equalsIgnoreCase("1"))
                message.setStatus(true);
            else
                message.setStatus(false);

            msgBox = c.getInt(c.getColumnIndex(Mms.MESSAGE_BOX));
            threadId = c.getInt(c.getColumnIndex(Mms.THREAD_ID));
            message.setFolder(getFolderName(msgBox, threadId));

            message.setSubject(c.getString(c.getColumnIndex(Mms.SUBJECT)));
            message.setMessageId(c.getString(c.getColumnIndex(Mms.MESSAGE_ID)));
            message.setContentType(c.getString(c.getColumnIndex(Mms.CONTENT_TYPE)));
            message.setDate(c.getLong(c.getColumnIndex(Mms.DATE)) * 1000L);
            message.setTextOnly(c.getInt(c.getColumnIndex(Mms.TEXT_ONLY)) == 0 ? false : true); // - TODO: Do we need this - yes, if we have only text, we should not make this a multipart message
            message.setIncludeAttachments(appParams.getAttachment() == 0 ? false : true);
            // c.getLong(c.getColumnIndex(Mms.DATE_SENT)); - this is never used
            // c.getInt(c.getColumnIndex(Mms.STATUS)); - don't know what this is

            // The parts
            extractMmsParts(id, message);

            // The addresses
            extractMmsAddresses(id, message);

            c.close();

            return message.encode();
        }
        else if(c != null) {
            c.close();
        }

        throw new IllegalArgumentException("MMS handle not found");
    }

}
