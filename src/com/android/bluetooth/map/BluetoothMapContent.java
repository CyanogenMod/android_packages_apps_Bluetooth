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

import org.apache.http.util.ByteArrayBuffer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.bluetooth.map.BluetoothMapUtils.TYPE;
import com.google.android.mms.pdu.CharacterSets;

public class BluetoothMapContent {
    private static final String TAG = "BluetoothMapContent";

    private static final boolean D = true;
    private static final boolean V = true;

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

    private Context mContext;
    private ContentResolver mResolver;

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

        int msgType = TYPE_SMS;
        int phoneType = 0;
        String phoneNum = null;
        String phoneAlphaTag = null;
    }

    public BluetoothMapContent(final Context context) {
        mContext = context;
        mResolver = mContext.getContentResolver();
        if (mResolver == null) {
            Log.d(TAG, "getContentResolver failed");
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

        if (c.moveToFirst()) {
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
        if (c.moveToFirst()) {
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
        } else {
            Log.d(TAG, "query failed");
            c.close();
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
        } else {
            Log.d(TAG, "query failed");
            c.close();
        }

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
        if ((ap.getParameterMask() & MASK_SENT) != 0) {
            int msgType = 0;
            if (fi.msgType == FilterInfo.TYPE_SMS) {
                msgType = c.getInt(c.getColumnIndex(Sms.TYPE));
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                msgType = c.getInt(c.getColumnIndex(Mms.MESSAGE_BOX));
            }
            String sent = null;
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
        if ((ap.getParameterMask() & MASK_PRIORITY) != 0) {
            String priority = "no";
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
            }
            if (D) Log.d(TAG, "setType: " + type);
            e.setType(type);
        }
    }

    private void setRecipientAddressing(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_RECIPIENT_ADDRESSING) != 0) {
            String address = "";
            if (fi.msgType == FilterInfo.TYPE_SMS) {
                int msgType = c.getInt(c.getColumnIndex(Sms.TYPE));
                if (msgType == 1) {
                    address = fi.phoneNum;
                } else {
                    address = c.getString(c.getColumnIndex(Sms.ADDRESS));
                }
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                address = getAddressMms(mResolver, id, MMS_TO);
            }
            if (D) Log.d(TAG, "setRecipientAddressing: " + address);
            e.setRecipientAddressing(address);
        }
    }

    private void setRecipientName(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_RECIPIENT_NAME) != 0) {
            String name = "";
            if (fi.msgType == FilterInfo.TYPE_SMS) {
                int msgType = c.getInt(c.getColumnIndex(Sms.TYPE));
                if (msgType != 1) {
                    String phone = c.getString(c.getColumnIndex(Sms.ADDRESS));
                    name = getContactNameFromPhone(phone);
                } else {
                    name = fi.phoneAlphaTag;
                }
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                String phone = getAddressMms(mResolver, id, MMS_TO);
                name = getContactNameFromPhone(phone);
            }
            if (D) Log.d(TAG, "setRecipientName: " + name);
            e.setRecipientName(name);
        }
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
            }
            if (D) Log.d(TAG, "setSenderAddressing: " + address);
            e.setSenderAddressing(address);
        }
    }

    private void setSenderName(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_SENDER_NAME) != 0) {
            String name = "";
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
            }
            if (D) Log.d(TAG, "setSenderName: " + name);
            e.setSenderName(name);
        }
    }

    private void setDateTime(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        long date = 0;

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
        }
        e.setDateTime(date);
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
        String name = "";

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

        c.close();
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
            if (phone != null && phone.length() > 0 && phone.matches(recip)) {
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
            } else {
                if (D) Log.d(TAG, "Unknown msg type: " + fi.msgType);
                res = false;
            }
        } else {
            res = true;
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
        }
        else {
            String phone = fi.phoneNum;
            String name = fi.phoneAlphaTag;
            if (phone != null && phone.length() > 0 && phone.matches(orig)) {
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
        }

        return where;
    }

    private String setWhereFilterReadStatus(BluetoothMapAppParams ap) {
        String where = "";
        if (ap.getFilterReadStatus() != -1) {
            if ((ap.getFilterReadStatus() & 0x01) != 0) {
                where = " AND read=0 ";
            }

            if ((ap.getFilterReadStatus() & 0x02) != 0) {
                where = " AND read=1 ";
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
            }
        }

        if ((ap.getFilterPeriodEnd() != -1)) {
            if (fi.msgType == FilterInfo.TYPE_SMS) {
            where += " AND date <= " + ap.getFilterPeriodEnd();
            } else if (fi.msgType == FilterInfo.TYPE_MMS) {
                where += " AND date <= " + (ap.getFilterPeriodEnd() / 1000L);
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
            p.close();
        }
        c.close();

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

    private String setWhereFilter(String folder, FilterInfo fi, BluetoothMapAppParams ap) {
        String where = "";

        where += setWhereFilterFolderType(folder, fi);
        where += setWhereFilterReadStatus(ap);
        where += setWhereFilterPeriod(ap, fi);
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

    public BluetoothMapMessageListing msgListing(String folder, BluetoothMapAppParams ap) {
        Log.d(TAG, "msgListing: folder = " + folder);
        BluetoothMapMessageListing bmList = new BluetoothMapMessageListing();
        BluetoothMapMessageListingElement e = null;

        /* Cache some info used throughout filtering */
        FilterInfo fi = new FilterInfo();
        setFilterInfo(fi);

        if (smsSelected(fi, ap)) {
            fi.msgType = FilterInfo.TYPE_SMS;

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

    public byte[] getMessage(String handle, BluetoothMapAppParams appParams) throws UnsupportedEncodingException{
        TYPE type = BluetoothMapUtils.getMsgTypeFromHandle(handle);
        long id = BluetoothMapUtils.getCpHandle(handle);
        switch(type) {
        case SMS_GSM:
        case SMS_CDMA:
            return getSmsMessage(id, appParams.getCharset());
        case MMS:
            return getMmsMessage(id, appParams);
        case EMAIL:
            throw new IllegalArgumentException("Email not implemented - invalid message handle.");
        }
        throw new IllegalArgumentException("Invalid message handle.");
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
        p.close();

        // Bail out if we are unable to find a contact, based on the phone number
        if(contactId == null) {
            phoneNumbers = new String[1];
            phoneNumbers[0] = phone;
        }
        else {
            // Fetch all contact phone numbers
            p = mResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                new String[]{contactId},
                null);
            if(p != null) {
                int i = 0;
                phoneNumbers = new String[p.getCount()];
                while (p != null && p.moveToNext()) {
                    String number = p.getString(
                        p.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                    phoneNumbers[i++] = number;
                }
                p.close();
            }

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

            if(V) Log.d(TAG,"c.count: " + c.getCount());

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
        if (c.moveToFirst()) {
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

        if (c.moveToFirst()) {
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
