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

import org.apache.http.util.ByteArrayBuffer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Debug;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import com.android.bluetooth.mapapi.BluetoothMapContract;
import com.android.bluetooth.mapapi.BluetoothMapContract.MessageColumns;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;
import android.util.Log;

import com.android.bluetooth.map.BluetoothMapSmsPdu.SmsPdu;
import com.android.bluetooth.map.BluetoothMapUtils.TYPE;
import com.google.android.mms.pdu.CharacterSets;
import com.google.android.mms.pdu.PduHeaders;
import com.android.bluetooth.map.BluetoothMapAppParams;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

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

    public static final String INSERT_ADDRES_TOKEN = "insert-address-token";

    private Context mContext;
    private ContentResolver mResolver;
    private String mBaseEmailUri = null;

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
        Sms.ERROR_CODE
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
        Mms.PRIORITY
    };

    private class FilterInfo {
        public static final int TYPE_SMS = 0;
        public static final int TYPE_MMS = 1;
        public static final int TYPE_EMAIL = 2;

        int mMsgType = TYPE_SMS;
        int mPhoneType = 0;
        String mPhoneNum = null;
        String mPhoneAlphaTag = null;
        /*column indices used to optimize queries */
        public int mEmailColThreadId        = -1;
        public int mEmailColProtected       = -1;
        public int mEmailColFolder          = -1;
        public int mMmsColFolder            = -1;
        public int mSmsColFolder            = -1;
        public int mEmailColRead            = -1;
        public int mSmsColRead              = -1;
        public int mMmsColRead              = -1;
        public int mEmailColPriority        = -1;
        public int mMmsColAttachmentSize    = -1;
        public int mEmailColAttachment      = -1;
        public int mEmailColAttachementSize = -1;
        public int mMmsColTextOnly          = -1;
        public int mMmsColId                = -1;
        public int mSmsColId                = -1;
        public int mEmailColSize            = -1;
        public int mSmsColSubject           = -1;
        public int mMmsColSize              = -1;
        public int mEmailColToAddress       = -1;
        public int mEmailColCcAddress       = -1;
        public int mEmailColBccAddress      = -1;
        public int mSmsColAddress           = -1;
        public int mSmsColDate              = -1;
        public int mMmsColDate              = -1;
        public int mEmailColDate            = -1;
        public int mMmsColSubject           = -1;
        public int mEmailColSubject         = -1;
        public int mSmsColType              = -1;
        public int mEmailColFromAddress     = -1;
        public int mEmailColId              = -1;


        public void setEmailColumns(Cursor c) {
            mEmailColThreadId        = c.getColumnIndex(BluetoothMapContract.MessageColumns.THREAD_ID);
            mEmailColProtected       = c.getColumnIndex(BluetoothMapContract.MessageColumns.FLAG_PROTECTED);
            mEmailColFolder          = c.getColumnIndex(BluetoothMapContract.MessageColumns.FOLDER_ID);
            mEmailColRead            = c.getColumnIndex(BluetoothMapContract.MessageColumns.FLAG_READ);
            mEmailColPriority        = c.getColumnIndex(BluetoothMapContract.MessageColumns.FLAG_HIGH_PRIORITY);
            mEmailColAttachment      = c.getColumnIndex(BluetoothMapContract.MessageColumns.FLAG_ATTACHMENT);
            mEmailColAttachementSize = c.getColumnIndex(BluetoothMapContract.MessageColumns.ATTACHMENT_SIZE);
            mEmailColSize            = c.getColumnIndex(BluetoothMapContract.MessageColumns.MESSAGE_SIZE);
            mEmailColToAddress       = c.getColumnIndex(BluetoothMapContract.MessageColumns.TO_LIST);
            mEmailColCcAddress       = c.getColumnIndex(BluetoothMapContract.MessageColumns.CC_LIST);
            mEmailColBccAddress      = c.getColumnIndex(BluetoothMapContract.MessageColumns.BCC_LIST);
            mEmailColDate            = c.getColumnIndex(BluetoothMapContract.MessageColumns.DATE);
            mEmailColSubject         = c.getColumnIndex(BluetoothMapContract.MessageColumns.SUBJECT);
            mEmailColFromAddress     = c.getColumnIndex(BluetoothMapContract.MessageColumns.FROM_LIST);
            mEmailColId              = c.getColumnIndex(BluetoothMapContract.MessageColumns._ID);
        }

        public void setSmsColumns(Cursor c) {
            mSmsColId      = c.getColumnIndex(BaseColumns._ID);
            mSmsColFolder  = c.getColumnIndex(Sms.TYPE);
            mSmsColRead    = c.getColumnIndex(Sms.READ);
            mSmsColSubject = c.getColumnIndex(Sms.BODY);
            mSmsColAddress = c.getColumnIndex(Sms.ADDRESS);
            mSmsColDate      = c.getColumnIndex(Sms.DATE);
            mSmsColType      = c.getColumnIndex(Sms.TYPE);
        }

        public void setMmsColumns(Cursor c) {
            mMmsColId              = c.getColumnIndex(BaseColumns._ID);
            mMmsColFolder          = c.getColumnIndex(Mms.MESSAGE_BOX);
            mMmsColRead            = c.getColumnIndex(Mms.READ);
            mMmsColAttachmentSize  = c.getColumnIndex(Mms.MESSAGE_SIZE);
            mMmsColTextOnly        = c.getColumnIndex(Mms.TEXT_ONLY);
            mMmsColSize            = c.getColumnIndex(Mms.MESSAGE_SIZE);
            mMmsColDate            = c.getColumnIndex(Mms.DATE);
            mMmsColSubject         = c.getColumnIndex(Mms.SUBJECT);

        }
    }

    public BluetoothMapContent(final Context context, String emailBaseUri) {
        mContext = context;
        mResolver = mContext.getContentResolver();
        if (mResolver == null) {
            if (D) Log.d(TAG, "getContentResolver failed");
        }
        mBaseEmailUri = emailBaseUri;
    }

    private static void close(Closeable c) {
        try {
          if (c != null) c.close();
        } catch (IOException e) {
        }
    }

    private void setProtected(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_PROTECTED) != 0) {
            String protect = "no";
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                int flagProtected = c.getInt(fi.mEmailColProtected);
                if (flagProtected == 1) {
                    protect = "yes";
                }
            }
            if (V) Log.d(TAG, "setProtected: " + protect + "\n");
            e.setProtect(protect);
        }
    }

    /**
     * Email only
     */
    private void setThreadId(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
            long threadId = c.getLong(fi.mEmailColThreadId);
            e.setThreadId(threadId);
            if (V) Log.d(TAG, "setThreadId: " + threadId + "\n");
        }
    }

    private void setSent(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_SENT) != 0) {
            int msgType = 0;
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
                msgType = c.getInt(fi.mSmsColFolder);
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                msgType = c.getInt(fi.mMmsColFolder);
            } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                msgType = c.getInt(fi.mEmailColFolder);
            }
            String sent = null;
            if (msgType == 2) {
                sent = "yes";
            } else {
                sent = "no";
            }
            if (V) Log.d(TAG, "setSent: " + sent);
            e.setSent(sent);
        }
    }

    private void setRead(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        int read = 0;
        if (fi.mMsgType == FilterInfo.TYPE_SMS) {
            read = c.getInt(fi.mSmsColRead);
        } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
            read = c.getInt(fi.mMmsColRead);
        } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
            read = c.getInt(fi.mEmailColRead);
        }
        String setread = null;

        if (V) Log.d(TAG, "setRead: " + setread);
        e.setRead((read==1?true:false), ((ap.getParameterMask() & MASK_READ) != 0));
    }

    private void setPriority(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_PRIORITY) != 0) {
            String priority = "no";
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                int highPriority = c.getInt(fi.mEmailColPriority);
                if (highPriority == 1) {
                    priority = "yes";
                }
            }
            int pri = 0;
            if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                pri = c.getInt(c.getColumnIndex(Mms.PRIORITY));
            }
            if (pri == PduHeaders.PRIORITY_HIGH) {
                priority = "yes";
            }
            if (V) Log.d(TAG, "setPriority: " + priority);
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
            if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                if(c.getInt(fi.mMmsColTextOnly) == 0) {
                    size = c.getInt(fi.mMmsColAttachmentSize);
                    if(size <= 0) {
                        // We know there are attachments, since it is not TextOnly
                        // Hence the size in the database must be wrong.
                        // Set size to 1 to indicate to the client, that attachments are present
                        if (D) Log.d(TAG, "Error in message database, size reported as: " + size
                                + " Changing size to 1");
                        size = 1;
                    }
                }
            } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                int attachment = c.getInt(fi.mEmailColAttachment);
                size = c.getInt(fi.mEmailColAttachementSize);
                if(attachment == 1 && size == 0) {
                    if (D) Log.d(TAG, "Error in message database, attachment size reported as: " + size
                            + " Changing size to 1");
                    size = 1; /* Ensure we indicate we have attachments in the size, if the
                                 message has attachments, in case the e-mail client do not
                                 report a size */
                }
            }
            if (V) Log.d(TAG, "setAttachmentSize: " + size);
            e.setAttachmentSize(size);
        }
    }

    private void setText(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_TEXT) != 0) {
            String hasText = "";
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
                hasText = "yes";
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                int textOnly = c.getInt(fi.mMmsColTextOnly);
                if (textOnly == 1) {
                    hasText = "yes";
                } else {
                    long id = c.getLong(fi.mMmsColId);
                    String text = getTextPartsMms(id);
                    if (text != null && text.length() > 0) {
                        hasText = "yes";
                    } else {
                        hasText = "no";
                    }
                }
            } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                hasText = "yes";
            }
            if (V) Log.d(TAG, "setText: " + hasText);
            e.setText(hasText);
        }
    }

    private void setReceptionStatus(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_RECEPTION_STATUS) != 0) {
            String status = "complete";
            if (V) Log.d(TAG, "setReceptionStatus: " + status);
            e.setReceptionStatus(status);
        }
    }

    private void setSize(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_SIZE) != 0) {
            int size = 0;
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
                String subject = c.getString(fi.mSmsColSubject);
                size = subject.length();
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                size = c.getInt(fi.mMmsColSize);
            } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                size = c.getInt(fi.mEmailColSize);
            }
            if(size <= 0) {
                // A message cannot have size 0
                // Hence the size in the database must be wrong.
                // Set size to 1 to indicate to the client, that the message has content.
                if (D) Log.d(TAG, "Error in message database, size reported as: " + size
                        + " Changing size to 1");
                size = 1;
            }
            if (V) Log.d(TAG, "setSize: " + size);
            e.setSize(size);
        }
    }

    private void setType(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_TYPE) != 0) {
            TYPE type = null;
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
                if (fi.mPhoneType == TelephonyManager.PHONE_TYPE_GSM) {
                    type = TYPE.SMS_GSM;
                } else if (fi.mPhoneType == TelephonyManager.PHONE_TYPE_CDMA) {
                    type = TYPE.SMS_CDMA;
                }
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                type = TYPE.MMS;
            } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                type = TYPE.EMAIL;
            }
            if (V) Log.d(TAG, "setType: " + type);
            e.setType(type);
        }
    }

    private String setRecipientAddressingEmail(BluetoothMapMessageListingElement e, Cursor c, FilterInfo fi) {
        String toAddress, ccAddress, bccAddress;
        toAddress = c.getString(fi.mEmailColToAddress);
        ccAddress = c.getString(fi.mEmailColCcAddress);
        bccAddress = c.getString(fi.mEmailColBccAddress);

        String address = "";
        if (toAddress != null) {
            address += toAddress;
            if (ccAddress != null) {
                address += ",";
            }
        }
        if (ccAddress != null) {
            address += ccAddress;
            if (bccAddress != null) {
                address += ",";
            }
        }
        if (bccAddress != null) {
            address += bccAddress;
        }
        return address;
    }

    private void setRecipientAddressing(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_RECIPIENT_ADDRESSING) != 0) {
            String address = null;
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
                int msgType = c.getInt(fi.mSmsColType);
                if (msgType == 1) {
                    address = fi.mPhoneNum;
                } else {
                    address = c.getString(c.getColumnIndex(Sms.ADDRESS));
                }
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
                address = getAddressMms(mResolver, id, MMS_TO);
            } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                /* Might be another way to handle addresses */
                address = setRecipientAddressingEmail(e, c,fi);
            }
            if (V) Log.v(TAG, "setRecipientAddressing: " + address);
            if(address == null)
                address = "";
            e.setRecipientAddressing(address);
        }
    }

    private void setRecipientName(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_RECIPIENT_NAME) != 0) {
            String name = null;
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
                int msgType = c.getInt(fi.mSmsColType);
                if (msgType != 1) {
                    String phone = c.getString(fi.mSmsColAddress);
                    if (phone != null && !phone.isEmpty())
                        name = getContactNameFromPhone(phone);
                } else {
                    name = fi.mPhoneAlphaTag;
                }
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                long id = c.getLong(fi.mMmsColId);
                String phone;
                if(e.getRecipientAddressing() != null){
                    phone = getAddressMms(mResolver, id, MMS_TO);
                } else {
                    phone = e.getRecipientAddressing();
                }
                if (phone != null && !phone.isEmpty())
                    name = getContactNameFromPhone(phone);
            } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                /* Might be another way to handle address and names */
                name = setRecipientAddressingEmail(e,c,fi);
            }
            if (V) Log.v(TAG, "setRecipientName: " + name);
            if(name == null)
                name = "";
            e.setRecipientName(name);
        }
    }

    private void setSenderAddressing(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_SENDER_ADDRESSING) != 0) {
            String address = null;
            String tempAddress;
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
                int msgType = c.getInt(fi.mSmsColType);
                if (msgType == 1) { // INBOX
                    tempAddress = c.getString(fi.mSmsColAddress);
                } else {
                    tempAddress = fi.mPhoneNum;
                }
                if(tempAddress == null) {
                    /* This can only happen on devices with no SIM -
                       hence will typically not have any SMS messages. */
                } else {
                    address = PhoneNumberUtils.extractNetworkPortion(tempAddress);
                    /* extractNetworkPortion can return N if the number is a service "number" = a string
                     * with the a name in (i.e. "Some-Tele-company" would return N because of the N in compaNy)
                     * Hence we need to check if the number is actually a string with alpha chars.
                     * */
                    Boolean alpha = PhoneNumberUtils.stripSeparators(tempAddress).matches("[0-9]*[a-zA-Z]+[0-9]*");

                    if(address == null || address.length() < 2 || alpha) {
                        address = tempAddress; // if the number is a service acsii text just use it
                    }
                }
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                long id = c.getLong(fi.mMmsColId);
                tempAddress = getAddressMms(mResolver, id, MMS_FROM);
                address = PhoneNumberUtils.extractNetworkPortion(tempAddress);
                if(address == null || address.length() < 1){
                    address = tempAddress; // if the number is a service acsii text just use it
                }
            } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                address = c.getString(fi.mEmailColFromAddress);
            }
            if (V) Log.v(TAG, "setSenderAddressing: " + address);
            if(address == null)
                address = "";
            e.setSenderAddressing(address);
        }
    }

    private void setSenderName(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_SENDER_NAME) != 0) {
            String name = null;
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
                int msgType = c.getInt(c.getColumnIndex(Sms.TYPE));
                if (msgType == 1) {
                    String phone = c.getString(fi.mSmsColAddress);
                    if (phone != null && !phone.isEmpty())
                        name = getContactNameFromPhone(phone);
                } else {
                    name = fi.mPhoneAlphaTag;
                }
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                long id = c.getLong(fi.mMmsColId);
                String phone;
                if(e.getSenderAddressing() != null){
                    phone = getAddressMms(mResolver, id, MMS_FROM);
                } else {
                    phone = e.getSenderAddressing();
                }
                if (phone != null && !phone.isEmpty() )
                    name = getContactNameFromPhone(phone);
            } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                name = c.getString(fi.mEmailColFromAddress);
            }
            if (V) Log.v(TAG, "setSenderName: " + name);
            if(name == null)
                name = "";
            e.setSenderName(name);
        }
    }

    private void setDateTime(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_DATETIME) != 0) {
            long date = 0;
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
                date = c.getLong(fi.mSmsColDate);
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                /* Use Mms.DATE for all messages. Although contract class states */
                /* Mms.DATE_SENT are for outgoing messages. But that is not working. */
                date = c.getLong(fi.mMmsColDate) * 1000L;

                /* int msgBox = c.getInt(c.getColumnIndex(Mms.MESSAGE_BOX)); */
                /* if (msgBox == Mms.MESSAGE_BOX_INBOX) { */
                /*     date = c.getLong(c.getColumnIndex(Mms.DATE)) * 1000L; */
                /* } else { */
                /*     date = c.getLong(c.getColumnIndex(Mms.DATE_SENT)) * 1000L; */
                /* } */
            } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                date = c.getLong(fi.mEmailColDate);
            }
            e.setDateTime(date);
        }
    }

    private String getTextPartsMms(long id) {
        String text = "";
        String selection = new String("mid=" + id);
        String uriStr = new String(Mms.CONTENT_URI + "/" + id + "/part");
        Uri uriAddress = Uri.parse(uriStr);
        // TODO: maybe use a projection with only "ct" and "text"

        Cursor c = mResolver.query(uriAddress, null, selection, null, null);
        try {
            while(c != null && c.moveToNext()) {
                String ct = c.getString(c.getColumnIndex("ct"));
                if (ct.equals("text/plain")) {
                    String part = c.getString(c.getColumnIndex("text"));
                    if(part != null) {
                        text += part;
                    }
                }
            }
        } finally {
            close(c);
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
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
                subject = c.getString(fi.mSmsColSubject);
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                subject = c.getString(fi.mMmsColSubject);
                if (subject == null || subject.length() == 0) {
                    /* Get subject from mms text body parts - if any exists */
                    long id = c.getLong(fi.mMmsColId);
                    subject = getTextPartsMms(id);
                }
            } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                subject = c.getString(fi.mEmailColSubject);
            }
            if (subject != null && subject.length() > subLength) {
                subject = subject.substring(0, subLength);
            }
            if (V) Log.d(TAG, "setSubject: " + subject);
            e.setSubject(subject);
        }
    }

    private void setHandle(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        long handle = -1;
        if (fi.mMsgType == FilterInfo.TYPE_SMS) {
            handle = c.getLong(fi.mSmsColId);
        } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
            handle = c.getLong(fi.mMmsColId);
        } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
            handle = c.getLong(fi.mEmailColId);
        }
        if (V) Log.d(TAG, "setHandle: " + handle );
        e.setHandle(handle);
    }

    private BluetoothMapMessageListingElement element(Cursor c, FilterInfo fi,
            BluetoothMapAppParams ap) {
        BluetoothMapMessageListingElement e = new BluetoothMapMessageListingElement();
        setHandle(e, c, fi, ap);
        setDateTime(e, c, fi, ap);
        setType(e, c, fi, ap);
        setRead(e, c, fi, ap);
        // we set number and name for sender/recipient later
        // they require lookup on contacts so no need to
        // do it for all elements unless they are to be used.
        e.setCursorIndex(c.getPosition());
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
        try {
            if (c != null && c.moveToFirst()) {
                name = c.getString(c.getColumnIndex(Contacts.DISPLAY_NAME));
            };
        } finally {
            close(c);
        }
        return name;
    }

    static public String getAddressMms(ContentResolver r, long id, int type) {
        String selection = new String("msg_id=" + id + " AND type=" + type);
        String uriStr = new String(Mms.CONTENT_URI + "/" + id + "/addr");
        Uri uriAddress = Uri.parse(uriStr);
        String addr = null;

        Cursor c = r.query(uriAddress, null, selection, null, null);
        try {
            if (c != null && c.moveToFirst()) {
                addr = c.getString(c.getColumnIndex(Mms.Addr.ADDRESS));
                if (addr.equals(INSERT_ADDRES_TOKEN)) addr  = "";
            }
        } finally {
            close(c);
        }

        return addr;
    }

    /**
     * Matching functions for originator and recipient for MMS
     * @return true if found a match
     */
    private boolean matchRecipientMms(Cursor c, FilterInfo fi, String recip) {
        boolean res;
        long id = c.getLong(c.getColumnIndex(BaseColumns._ID));
        String phone = getAddressMms(mResolver, id, MMS_TO);
        if (phone != null && phone.length() > 0) {
            if (phone.matches(recip)) {
                if (V) Log.v(TAG, "matchRecipientMms: match recipient phone = " + phone);
                res = true;
            } else {
                String name = getContactNameFromPhone(phone);
                if (name != null && name.length() > 0 && name.matches(recip)) {
                    if (V) Log.v(TAG, "matchRecipientMms: match recipient name = " + name);
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
            String phone = fi.mPhoneNum;
            String name = fi.mPhoneAlphaTag;
            if (phone != null && phone.length() > 0 && phone.matches(recip)) {
                if (V) Log.v(TAG, "matchRecipientSms: match recipient phone = " + phone);
                res = true;
            } else if (name != null && name.length() > 0 && name.matches(recip)) {
                if (V) Log.v(TAG, "matchRecipientSms: match recipient name = " + name);
                res = true;
            } else {
                res = false;
            }
        } else {
            String phone = c.getString(c.getColumnIndex(Sms.ADDRESS));
            if (phone != null && phone.length() > 0) {
                if (phone.matches(recip)) {
                    if (V) Log.v(TAG, "matchRecipientSms: match recipient phone = " + phone);
                    res = true;
                } else {
                    String name = getContactNameFromPhone(phone);
                    if (name != null && name.length() > 0 && name.matches(recip)) {
                        if (V) Log.v(TAG, "matchRecipientSms: match recipient name = " + name);
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
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
                res = matchRecipientSms(c, fi, recip);
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                res = matchRecipientMms(c, fi, recip);
            } else {
                if (D) Log.d(TAG, "matchRecipient: Unknown msg type: " + fi.mMsgType);
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
                if (V) Log.v(TAG, "matchOriginatorMms: match originator phone = " + phone);
                res = true;
            } else {
                String name = getContactNameFromPhone(phone);
                if (name != null && name.length() > 0 && name.matches(orig)) {
                    if (V) Log.v(TAG, "matchOriginatorMms: match originator name = " + name);
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
                    if (V) Log.v(TAG, "matchOriginatorSms: match originator phone = " + phone);
                    res = true;
                } else {
                    String name = getContactNameFromPhone(phone);
                    if (name != null && name.length() > 0 && name.matches(orig)) {
                        if (V) Log.v(TAG, "matchOriginatorSms: match originator name = " + name);
                        res = true;
                    } else {
                        res = false;
                    }
                }
            } else {
                res = false;
            }
        } else {
            String phone = fi.mPhoneNum;
            String name = fi.mPhoneAlphaTag;
            if (phone != null && phone.length() > 0 && phone.matches(orig)) {
                if (V) Log.v(TAG, "matchOriginatorSms: match originator phone = " + phone);
                res = true;
            } else if (name != null && name.length() > 0 && name.matches(orig)) {
                if (V) Log.v(TAG, "matchOriginatorSms: match originator name = " + name);
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
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
                res = matchOriginatorSms(c, fi, orig);
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                res = matchOriginatorMms(c, fi, orig);
            } else {
                if(D) Log.d(TAG, "matchOriginator: Unknown msg type: " + fi.mMsgType);
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

    /*
     * Where filter functions
     * */
    private String setWhereFilterFolderTypeSms(String folder) {
        String where = "";
        if (BluetoothMapContract.FOLDER_NAME_INBOX.equalsIgnoreCase(folder)) {
            where = Sms.TYPE + " = 1 AND " + Sms.THREAD_ID + " <> -1";
        } else if (BluetoothMapContract.FOLDER_NAME_OUTBOX.equalsIgnoreCase(folder)) {
            where = "(" + Sms.TYPE + " = 4 OR " + Sms.TYPE + " = 5 OR "
                    + Sms.TYPE + " = 6) AND " + Sms.THREAD_ID + " <> -1";
        } else if (BluetoothMapContract.FOLDER_NAME_SENT.equalsIgnoreCase(folder)) {
            where = Sms.TYPE + " = 2 AND " + Sms.THREAD_ID + " <> -1";
        } else if (BluetoothMapContract.FOLDER_NAME_DRAFT.equalsIgnoreCase(folder)) {
            where = Sms.TYPE + " = 3 AND " + Sms.THREAD_ID + " <> -1";
        } else if (BluetoothMapContract.FOLDER_NAME_DELETED.equalsIgnoreCase(folder)) {
            where = Sms.THREAD_ID + " = -1";
        }

        return where;
    }

    private String setWhereFilterFolderTypeMms(String folder) {
        String where = "";
        if (BluetoothMapContract.FOLDER_NAME_INBOX.equalsIgnoreCase(folder)) {
            where = Mms.MESSAGE_BOX + " = 1 AND " + Mms.THREAD_ID + " <> -1";
        } else if (BluetoothMapContract.FOLDER_NAME_OUTBOX.equalsIgnoreCase(folder)) {
            where = Mms.MESSAGE_BOX + " = 4 AND " + Mms.THREAD_ID + " <> -1";
        } else if (BluetoothMapContract.FOLDER_NAME_SENT.equalsIgnoreCase(folder)) {
            where = Mms.MESSAGE_BOX + " = 2 AND " + Mms.THREAD_ID + " <> -1";
        } else if (BluetoothMapContract.FOLDER_NAME_DRAFT.equalsIgnoreCase(folder)) {
            where = Mms.MESSAGE_BOX + " = 3 AND " + Mms.THREAD_ID + " <> -1";
        } else if (BluetoothMapContract.FOLDER_NAME_DELETED.equalsIgnoreCase(folder)) {
            where = Mms.THREAD_ID + " = -1";
        }

        return where;
    }

    private String setWhereFilterFolderTypeEmail(long folderId) {
        String where = "";
        if (folderId >= 0) {
            where = BluetoothMapContract.MessageColumns.FOLDER_ID + " = " + folderId;
        } else {
            Log.e(TAG, "setWhereFilterFolderTypeEmail: not valid!" );
            throw new IllegalArgumentException("Invalid folder ID");
        }
        return where;
    }

    private String setWhereFilterFolderType(BluetoothMapFolderElement folderElement, FilterInfo fi) {
        String where = "";
        if (fi.mMsgType == FilterInfo.TYPE_SMS) {
            where = setWhereFilterFolderTypeSms(folderElement.getName());
        } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
            where = setWhereFilterFolderTypeMms(folderElement.getName());
        } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
            where = setWhereFilterFolderTypeEmail(folderElement.getEmailFolderId());
        }
        return where;
    }

    private String setWhereFilterReadStatus(BluetoothMapAppParams ap, FilterInfo fi) {
        String where = "";
        if (ap.getFilterReadStatus() != -1) {
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
                if ((ap.getFilterReadStatus() & 0x01) != 0) {
                    where = " AND " + Sms.READ + "= 0";
                }

                if ((ap.getFilterReadStatus() & 0x02) != 0) {
                    where = " AND " + Sms.READ + "= 1";
                }
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                if ((ap.getFilterReadStatus() & 0x01) != 0) {
                    where = " AND " + Mms.READ + "= 0";
                }

                if ((ap.getFilterReadStatus() & 0x02) != 0) {
                    where = " AND " + Mms.READ + "= 1";
                }
            } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                if ((ap.getFilterReadStatus() & 0x01) != 0) {
                    where = " AND " + BluetoothMapContract.MessageColumns.FLAG_READ + "= 0";
                }

                if ((ap.getFilterReadStatus() & 0x02) != 0) {
                    where = " AND " + BluetoothMapContract.MessageColumns.FLAG_READ + "= 1";
                }
            }
        }
        return where;
    }

    private String setWhereFilterPeriod(BluetoothMapAppParams ap, FilterInfo fi) {
        String where = "";
        if ((ap.getFilterPeriodBegin() != -1)) {
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
            where = " AND " + Sms.DATE + " >= " + ap.getFilterPeriodBegin();
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                where = " AND " + Mms.DATE + " >= " + (ap.getFilterPeriodBegin() / 1000L);
            } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                where = " AND " + BluetoothMapContract.MessageColumns.DATE + " >= " + (ap.getFilterPeriodBegin());
            }
        }

        if ((ap.getFilterPeriodEnd() != -1)) {
            if (fi.mMsgType == FilterInfo.TYPE_SMS) {
            where += " AND " + Sms.DATE + " < " + ap.getFilterPeriodEnd();
            } else if (fi.mMsgType == FilterInfo.TYPE_MMS) {
                where += " AND " + Mms.DATE + " < " + (ap.getFilterPeriodEnd() / 1000L);
            } else if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                where += " AND " + BluetoothMapContract.MessageColumns.DATE + " < " + (ap.getFilterPeriodEnd());
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

        try {
            while (c != null && c.moveToNext()) {
                String contactId = c.getString(c.getColumnIndex(ContactsContract.Contacts._ID));

                Cursor p = mResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                    new String[]{contactId},
                    null);

                try {
                    while (p != null && p.moveToNext()) {
                        String number = p.getString(
                            p.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));

                        where += " address = " + "'" + number + "'";
                        if (!p.isLast()) where += " OR ";
                    }
                } finally {
                    close(p);
                }

                if (!c.isLast()) where += " OR ";
            }
        } finally {
            close(c);
        }

        if (str != null && str.length() > 0) {
            if (where.length() > 0) {
                where += " OR ";
            }
            where += " address like " + "'" + str + "'";
        }

        return where;
    }

    private String setWhereFilterOriginatorEmail(BluetoothMapAppParams ap) {
        String where = "";
        String orig = ap.getFilterOriginator();

        /* Be aware of wild cards in the beginning of string, may not be valid? */
        if (orig != null && orig.length() > 0) {
            orig = orig.replace("*", "%");
            where = " AND " + BluetoothMapContract.MessageColumns.FROM_LIST + " LIKE '%" +  orig + "%'";
        }
        return where;
    }
    private String setWhereFilterPriority(BluetoothMapAppParams ap, FilterInfo fi) {
        String where = "";
        int pri = ap.getFilterPriority();
        /*only MMS have priority info */
        if(fi.mMsgType == FilterInfo.TYPE_MMS)
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

    private String setWhereFilterRecipientEmail(BluetoothMapAppParams ap) {
        String where = "";
        String recip = ap.getFilterRecipient();

        /* Be aware of wild cards in the beginning of string, may not be valid? */
        if (recip != null && recip.length() > 0) {
            recip = recip.replace("*", "%");
            where = " AND ("
            + BluetoothMapContract.MessageColumns.TO_LIST  + " LIKE '%" + recip + "%' OR "
            + BluetoothMapContract.MessageColumns.CC_LIST  + " LIKE '%" + recip + "%' OR "
            + BluetoothMapContract.MessageColumns.BCC_LIST + " LIKE '%" + recip + "%' )";
        }
        return where;
    }

    private String setWhereFilter(BluetoothMapFolderElement folderElement,
            FilterInfo fi, BluetoothMapAppParams ap) {
        String where = "";

        where += setWhereFilterFolderType(folderElement, fi);
        if(!where.isEmpty()) {
            where += setWhereFilterReadStatus(ap, fi);
            where += setWhereFilterPeriod(ap, fi);
            where += setWhereFilterPriority(ap,fi);

            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                where += setWhereFilterOriginatorEmail(ap);
                where += setWhereFilterRecipientEmail(ap);
            }
        }


        return where;
    }

    /**
     * Determine from application parameter if sms should be included.
     * The filter mask is set for message types not selected
     * @param fi
     * @param ap
     * @return boolean true if sms is selected, false if not
     */
    private boolean smsSelected(FilterInfo fi, BluetoothMapAppParams ap) {
        int msgType = ap.getFilterMessageType();
        int phoneType = fi.mPhoneType;

        if (D) Log.d(TAG, "smsSelected msgType: " + msgType);

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

    /**
     * Determine from application parameter if mms should be included.
     * The filter mask is set for message types not selected
     * @param fi
     * @param ap
     * @return boolean true if sms is selected, false if not
     */
    private boolean mmsSelected(FilterInfo fi, BluetoothMapAppParams ap) {
        int msgType = ap.getFilterMessageType();

        if (D) Log.d(TAG, "mmsSelected msgType: " + msgType);

        if (msgType == -1)
            return true;

        if ((msgType & 0x08) == 0)
            return true;

        return false;
    }

    /**
     * Determine from application parameter if email should be included.
     * The filter mask is set for message types not selected
     * @param fi
     * @param ap
     * @return boolean true if sms is selected, false if not
     */
    private boolean emailSelected(FilterInfo fi, BluetoothMapAppParams ap) {
        int msgType = ap.getFilterMessageType();

        if (D) Log.d(TAG, "emailSelected msgType: " + msgType);

        if (msgType == -1)
            return true;

        if ((msgType & 0x04) == 0)
            return true;

        return false;
    }

    private void setFilterInfo(FilterInfo fi) {
        TelephonyManager tm = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            fi.mPhoneType = tm.getPhoneType();
            fi.mPhoneNum = tm.getLine1Number();
            fi.mPhoneAlphaTag = tm.getLine1AlphaTag();
            if (D) Log.d(TAG, "phone type = " + fi.mPhoneType +
                " phone num = " + fi.mPhoneNum +
                " phone alpha tag = " + fi.mPhoneAlphaTag);
        }
    }

    /**
     * Get a listing of message in folder after applying filter.
     * @param folder Must contain a valid folder string != null
     * @param ap Parameters specifying message content and filters
     * @return Listing object containing requested messages
     */
    public BluetoothMapMessageListing msgListing(BluetoothMapFolderElement folderElement,
            BluetoothMapAppParams ap) {
        if (D) Log.d(TAG, "msgListing: folderName = " + folderElement.getName()
                + " folderId = " + folderElement.getEmailFolderId()
                + " messageType = " + ap.getFilterMessageType() );
        BluetoothMapMessageListing bmList = new BluetoothMapMessageListing();


        /* We overwrite the parameter mask here if it is 0 or not present, as this
         * should cause all parameters to be included in the message list. */
        if(ap.getParameterMask() == BluetoothMapAppParams.INVALID_VALUE_PARAMETER ||
                ap.getParameterMask() == 0) {
            ap.setParameterMask(BluetoothMapAppParams.PARAMETER_MASK_ALL_ENABLED);
            if (V) Log.v(TAG, "msgListing(): appParameterMask is zero or not present, " +
                    "changing to: " + ap.getParameterMask());
        }

        /* Cache some info used throughout filtering */
        FilterInfo fi = new FilterInfo();
        setFilterInfo(fi);
        Cursor smsCursor = null;
        Cursor mmsCursor = null;
        Cursor emailCursor = null;

        try {
            String limit = "";
            int countNum = ap.getMaxListCount();
            int offsetNum = ap.getStartOffset();
            if(ap.getMaxListCount()>0){
                limit=" LIMIT "+ (ap.getMaxListCount()+ap.getStartOffset());
            }

            if (smsSelected(fi, ap) && folderElement.hasSmsMmsContent()) {
                if(ap.getFilterMessageType() == (BluetoothMapAppParams.FILTER_NO_EMAIL|
                                                 BluetoothMapAppParams.FILTER_NO_MMS|
                                                 BluetoothMapAppParams.FILTER_NO_SMS_GSM)||
                   ap.getFilterMessageType() == (BluetoothMapAppParams.FILTER_NO_EMAIL|
                                                 BluetoothMapAppParams.FILTER_NO_MMS|
                                                 BluetoothMapAppParams.FILTER_NO_SMS_CDMA)){
                    //set real limit and offset if only this type is used (only if offset/limit is used
                    limit = " LIMIT " + ap.getMaxListCount()+" OFFSET "+ ap.getStartOffset();
                    if(D) Log.d(TAG, "SMS Limit => "+limit);
                    offsetNum = 0;
                }
                fi.mMsgType = FilterInfo.TYPE_SMS;
                if(ap.getFilterPriority() != 1){ /*SMS cannot have high priority*/
                    String where = setWhereFilter(folderElement, fi, ap);
                    if(!where.isEmpty()) {
                        if (D) Log.d(TAG, "msgType: " + fi.mMsgType);
                        smsCursor = mResolver.query(Sms.CONTENT_URI,
                                SMS_PROJECTION, where, null, Sms.DATE + " DESC" + limit);
                        if (smsCursor != null) {
                            BluetoothMapMessageListingElement e = null;
                            // store column index so we dont have to look them up anymore (optimization)
                            if(D) Log.d(TAG, "Found " + smsCursor.getCount() + " sms messages.");
                            fi.setSmsColumns(smsCursor);
                            while (smsCursor.moveToNext()) {
                                if (matchAddresses(smsCursor, fi, ap)) {
                                    e = element(smsCursor, fi, ap);
                                    bmList.add(e);
                                }
                            }
                        }
                    }
                }
            }

            if (mmsSelected(fi, ap) && folderElement.hasSmsMmsContent()) {
                if(ap.getFilterMessageType() == (BluetoothMapAppParams.FILTER_NO_EMAIL|
                                                 BluetoothMapAppParams.FILTER_NO_SMS_CDMA|
                                                 BluetoothMapAppParams.FILTER_NO_SMS_GSM)){
                    //set real limit and offset if only this type is used (only if offset/limit is used
                    limit = " LIMIT " + ap.getMaxListCount()+" OFFSET "+ ap.getStartOffset();
                    if(D) Log.d(TAG, "MMS Limit => "+limit);
                    offsetNum = 0;
                }
                fi.mMsgType = FilterInfo.TYPE_MMS;
                String where = setWhereFilter(folderElement, fi, ap);
                if(!where.isEmpty()) {
                    if (D) Log.d(TAG, "msgType: " + fi.mMsgType);
                    mmsCursor = mResolver.query(Mms.CONTENT_URI,
                            MMS_PROJECTION, where, null, Mms.DATE + " DESC" + limit);
                    if (mmsCursor != null) {
                        BluetoothMapMessageListingElement e = null;
                        // store column index so we dont have to look them up anymore (optimization)
                        fi.setMmsColumns(mmsCursor);
                        int cnt = 0;
                        if(D) Log.d(TAG, "Found " + mmsCursor.getCount() + " mms messages.");
                        while (mmsCursor.moveToNext()) {
                            if (matchAddresses(mmsCursor, fi, ap)) {
                                e = element(mmsCursor, fi, ap);
                                bmList.add(e);
                            }
                        }
                    }
                }
            }

            if (emailSelected(fi, ap) && folderElement.getEmailFolderId() != -1) {
                if(ap.getFilterMessageType() == (BluetoothMapAppParams.FILTER_NO_MMS|
                                                 BluetoothMapAppParams.FILTER_NO_SMS_CDMA|
                                                 BluetoothMapAppParams.FILTER_NO_SMS_GSM)){
                    //set real limit and offset if only this type is used (only if offset/limit is used
                    limit = " LIMIT " + ap.getMaxListCount()+" OFFSET "+ ap.getStartOffset();
                    if(D) Log.d(TAG, "Email Limit => "+limit);
                    offsetNum = 0;
                }
                fi.mMsgType = FilterInfo.TYPE_EMAIL;
                String where = setWhereFilter(folderElement, fi, ap);

                if(!where.isEmpty()) {
                    if (D) Log.d(TAG, "msgType: " + fi.mMsgType);
                    Uri contentUri = Uri.parse(mBaseEmailUri + BluetoothMapContract.TABLE_MESSAGE);
                    emailCursor = mResolver.query(contentUri, BluetoothMapContract.BT_MESSAGE_PROJECTION,
                            where, null, BluetoothMapContract.MessageColumns.DATE + " DESC" + limit);
                    if (emailCursor != null) {
                        BluetoothMapMessageListingElement e = null;
                        // store column index so we dont have to look them up anymore (optimization)
                        fi.setEmailColumns(emailCursor);
                        int cnt = 0;
                        while (emailCursor.moveToNext()) {
                            if(D) Log.d(TAG, "Found " + emailCursor.getCount() + " email messages.");
                            e = element(emailCursor, fi, ap);
                            bmList.add(e);
                        }
                    //   emailCursor.close();
                    }
                }
            }

            /* Enable this if post sorting and segmenting needed */
            bmList.sort();
            bmList.segment(ap.getMaxListCount(), offsetNum);
            List<BluetoothMapMessageListingElement> list = bmList.getList();
            int listSize = list.size();
            Cursor tmpCursor = null;
            for (int x=0; x<listSize; x++){
                BluetoothMapMessageListingElement ele = list.get(x);
                if ((ele.getType().equals(TYPE.SMS_GSM)||ele.getType().equals(TYPE.SMS_CDMA)) && smsCursor != null){
                    tmpCursor = smsCursor;
                    fi.mMsgType = FilterInfo.TYPE_SMS;
                } else if (ele.getType().equals(TYPE.MMS) && mmsCursor != null){
                    tmpCursor = mmsCursor;
                    fi.mMsgType = FilterInfo.TYPE_MMS;
                } else if (ele.getType().equals(TYPE.EMAIL) && emailCursor != null){
                    tmpCursor = emailCursor;
                    fi.mMsgType = FilterInfo.TYPE_EMAIL;
                }

                if (tmpCursor != null && tmpCursor.moveToPosition(ele.getCursorIndex())) {
                    setSenderAddressing(ele, tmpCursor, fi, ap);
                    setSenderName(ele, tmpCursor, fi, ap);
                    setRecipientAddressing(ele, tmpCursor, fi, ap);
                    setRecipientName(ele, tmpCursor, fi, ap);
                    setSubject(ele, tmpCursor, fi, ap);
                    setSize(ele, tmpCursor, fi, ap);
                    setReceptionStatus(ele, tmpCursor, fi, ap);
                    setText(ele, tmpCursor, fi, ap);
                    setAttachmentSize(ele, tmpCursor, fi, ap);
                    setPriority(ele, tmpCursor, fi, ap);
                    setSent(ele, tmpCursor, fi, ap);
                    setProtected(ele, tmpCursor, fi, ap);
                    setThreadId(ele, tmpCursor, fi, ap);
                }
            }
        } finally {
            close(emailCursor);
            close(smsCursor);
            close(mmsCursor);
        }

        if (D) Log.d(TAG, "messagelisting end");
        return bmList;
    }

    /**
     * Get the size of the message listing
     * @param folder Must contain a valid folder string != null
     * @param ap Parameters specifying message content and filters
     * @return Integer equal to message listing size
     */
    public int msgListingSize(BluetoothMapFolderElement folderElement,
            BluetoothMapAppParams ap) {
        if (D) Log.d(TAG, "msgListingSize: folder = " + folderElement.getName());
        int cnt = 0;

        /* Cache some info used throughout filtering */
        FilterInfo fi = new FilterInfo();
        setFilterInfo(fi);

        if (smsSelected(fi, ap) && folderElement.hasSmsMmsContent()) {
            fi.mMsgType = FilterInfo.TYPE_SMS;
            String where = setWhereFilter(folderElement, fi, ap);
            Cursor c = mResolver.query(Sms.CONTENT_URI,
                    SMS_PROJECTION, where, null, Sms.DATE + " DESC");

            if (c != null) cnt = c.getCount();
            close(c);
        }

        if (mmsSelected(fi, ap) && folderElement.hasSmsMmsContent()) {
            fi.mMsgType = FilterInfo.TYPE_MMS;
            String where = setWhereFilter(folderElement, fi, ap);
            Cursor c = mResolver.query(Mms.CONTENT_URI,
                    MMS_PROJECTION, where, null, Mms.DATE + " DESC");
            if (c != null) cnt += c.getCount();
            close(c);
        }

        if (emailSelected(fi, ap) && folderElement.getEmailFolderId() != -1) {
            fi.mMsgType = FilterInfo.TYPE_EMAIL;
            String where = setWhereFilter(folderElement, fi, ap);
            if (!where.isEmpty()) {
                Uri contentUri = Uri.parse(mBaseEmailUri + BluetoothMapContract.TABLE_MESSAGE);
                Cursor c = mResolver.query(contentUri, BluetoothMapContract.BT_MESSAGE_PROJECTION,
                        where, null, BluetoothMapContract.MessageColumns.DATE + " DESC");
                if (c != null) cnt += c.getCount();
                close(c);
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
    public boolean msgListingHasUnread(BluetoothMapFolderElement folderElement,
            BluetoothMapAppParams ap) {
        if (D) Log.d(TAG, "msgListingHasUnread: folder = " + folderElement.getName());
        int cnt = 0;

        /* Cache some info used throughout filtering */
        FilterInfo fi = new FilterInfo();
        setFilterInfo(fi);

       if (smsSelected(fi, ap)  && folderElement.hasSmsMmsContent()) {
            fi.mMsgType = FilterInfo.TYPE_SMS;
            String where = setWhereFilterFolderType(folderElement, fi);
            where += " AND " + Sms.READ + "=0 ";
            where += setWhereFilterPeriod(ap, fi);
            Cursor c = mResolver.query(Sms.CONTENT_URI,
                SMS_PROJECTION, where, null, Sms.DATE + " DESC");

            if (c != null) cnt += c.getCount();
            close(c);
        }

        if (mmsSelected(fi, ap)  && folderElement.hasSmsMmsContent()) {
            fi.mMsgType = FilterInfo.TYPE_MMS;
            String where = setWhereFilterFolderType(folderElement, fi);
            where += " AND " + Mms.READ + "=0 ";
            where += setWhereFilterPeriod(ap, fi);
            Cursor c = mResolver.query(Mms.CONTENT_URI,
                MMS_PROJECTION, where, null, Sms.DATE + " DESC");

            if (c != null) cnt += c.getCount();
            close(c);
        }


        if (emailSelected(fi, ap) && folderElement.getEmailFolderId() != -1) {
            fi.mMsgType = FilterInfo.TYPE_EMAIL;
            String where = setWhereFilterFolderType(folderElement, fi);
            if(!where.isEmpty()) {
                where += " AND " + BluetoothMapContract.MessageColumns.FLAG_READ + "=0 ";
                where += setWhereFilterPeriod(ap, fi);
                Uri contentUri = Uri.parse(mBaseEmailUri + BluetoothMapContract.TABLE_MESSAGE);
                Cursor c = mResolver.query(contentUri, BluetoothMapContract.BT_MESSAGE_PROJECTION,
                        where, null, BluetoothMapContract.MessageColumns.DATE + " DESC");
                if (c != null) cnt += c.getCount();
                close(c);
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
            return BluetoothMapContract.FOLDER_NAME_DELETED;

        switch(type) {
        case 1:
            return BluetoothMapContract.FOLDER_NAME_INBOX;
        case 2:
            return BluetoothMapContract.FOLDER_NAME_SENT;
        case 3:
            return BluetoothMapContract.FOLDER_NAME_DRAFT;
        case 4: // Just name outbox, failed and queued "outbox"
        case 5:
        case 6:
            return BluetoothMapContract.FOLDER_NAME_OUTBOX;
        }
        return "";
    }

    public byte[] getMessage(String handle, BluetoothMapAppParams appParams,
            BluetoothMapFolderElement folderElement) throws UnsupportedEncodingException{
        TYPE type = BluetoothMapUtils.getMsgTypeFromHandle(handle);
        long id = BluetoothMapUtils.getCpHandle(handle);
        if(appParams.getFractionRequest() == BluetoothMapAppParams.FRACTION_REQUEST_NEXT) {
            throw new IllegalArgumentException("FRACTION_REQUEST_NEXT does not make sence as" +
                                               " we always return the full message.");
        }
        switch(type) {
        case SMS_GSM:
        case SMS_CDMA:
            return getSmsMessage(id, appParams.getCharset());
        case MMS:
            return getMmsMessage(id, appParams);
        case EMAIL:
            return getEmailMessage(id, appParams, folderElement);
        }
        throw new IllegalArgumentException("Invalid message handle.");
    }

    private String setVCardFromPhoneNumber(BluetoothMapbMessage message, String phone, boolean incoming) {
        String contactId = null, contactName = null;
        String[] phoneNumbers = null;
        String[] emailAddresses = null;

        Uri uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phone));

        String[] projection = {Contacts._ID, Contacts.DISPLAY_NAME};
        String selection = Contacts.IN_VISIBLE_GROUP + "=1";
        String orderBy = Contacts._ID + " ASC";

        // Get the contact _ID and name
        Cursor p = mResolver.query(uri, projection, selection, null, orderBy);

        try {
            if (p != null && p.moveToFirst()) {
                contactId = p.getString(p.getColumnIndex(Contacts._ID));
                contactName = p.getString(p.getColumnIndex(Contacts.DISPLAY_NAME));
            }

            // Bail out if we are unable to find a contact, based on the phone number
            if(contactId == null) {
                phoneNumbers = new String[1];
                phoneNumbers[0] = phone;
            } else {
                // use only actual phone number
                phoneNumbers = new String[1];
                phoneNumbers[0] = phone;

                // Fetch contact e-mail addresses
                close (p);
                p = mResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        new String[]{contactId},
                        null);
                if (p != null) {
                    int i = 0;
                    emailAddresses = new String[p.getCount()];
                    while (p != null && p.moveToNext()) {
                        String emailAddress = p.getString(
                            p.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS));
                        emailAddresses[i++] = emailAddress;
                    }
                }
            }
        } finally {
            close(p);
        }

        if (incoming == true) {
            if(V) Log.d(TAG, "Adding originator for phone:" + phone);
            message.addOriginator(contactName, contactName, phoneNumbers, emailAddresses); // Use version 3.0 as we only have a formatted name
        } else {
            if(V) Log.d(TAG, "Adding recipient for phone:" + phone);
            message.addRecipient(contactName, contactName, phoneNumbers, emailAddresses); // Use version 3.0 as we only have a formatted name
        }
        return contactName;
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
        if (c == null || !c.moveToFirst()) {
            throw new IllegalArgumentException("SMS handle not found");
        }

        try {
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
        } finally {
            close(c);
        }

        return message.encode();
    }

    private void extractMmsAddresses(long id, BluetoothMapbMessageMms message) {
        final String[] projection = null;
        String selection = new String(Mms.Addr.MSG_ID + "=" + id);
        String uriStr = new String(Mms.CONTENT_URI + "/" + id + "/addr");
        Uri uriAddress = Uri.parse(uriStr);
        String contactName = null;

        Cursor c = mResolver.query( uriAddress, projection, selection, null, null);
        try {
            while (c != null && c.moveToNext()) {
                String address = c.getString(c.getColumnIndex(Mms.Addr.ADDRESS));
                if(address.equals(INSERT_ADDRES_TOKEN))
                    continue;
                Integer type = c.getInt(c.getColumnIndex(Mms.Addr.TYPE));
                switch(type) {
                case MMS_FROM:
                    contactName = setVCardFromPhoneNumber(message, address, true);
                    message.addFrom(contactName, address);
                    break;
                case MMS_TO:
                    contactName = setVCardFromPhoneNumber(message, address, false);
                    message.addTo(contactName, address);
                    break;
                case MMS_CC:
                    contactName = setVCardFromPhoneNumber(message, address, false);
                    message.addCc(contactName, address);
                    break;
                case MMS_BCC:
                    contactName = setVCardFromPhoneNumber(message, address, false);
                    message.addBcc(contactName, address);
                    break;
                default:
                    break;
                }
            }
        } finally {
            close (c);
        }
    }

    /**
     * Read out a mms data part and return the data in a byte array.
     * @param partid the content provider id of the mms.
     * @return
     */
    private byte[] readMmsDataPart(long partid) {
        String uriStr = new String(Mms.CONTENT_URI + "/part/" + partid);
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
            close(os);
            close(is);
        }
        return retVal;
    }

    /**
     * Read out the mms parts and update the bMessage object provided i {@linkplain message}
     * @param id the content provider ID of the message
     * @param message the bMessage object to add the information to
     */
    private void extractMmsParts(long id, BluetoothMapbMessageMms message)
    {
        /* Handling of filtering out non-text parts for exclude
         * attachments is handled within the bMessage object. */
        final String[] projection = null;
        String selection = new String(Mms.Part.MSG_ID + "=" + id);
        String uriStr = new String(Mms.CONTENT_URI + "/"+ id + "/part");
        Uri uriAddress = Uri.parse(uriStr);
        BluetoothMapbMessageMms.MimePart part;
        Cursor c = mResolver.query(uriAddress, projection, selection, null, null);

        try {
            while(c != null && c.moveToNext()) {
                Long partId = c.getLong(c.getColumnIndex(BaseColumns._ID));
                String contentType = c.getString(c.getColumnIndex(Mms.Part.CONTENT_TYPE));
                String name = c.getString(c.getColumnIndex(Mms.Part.NAME));
                String charset = c.getString(c.getColumnIndex(Mms.Part.CHARSET));
                String filename = c.getString(c.getColumnIndex(Mms.Part.FILENAME));
                String text = c.getString(c.getColumnIndex(Mms.Part.TEXT));
                Integer fd = c.getInt(c.getColumnIndex(Mms.Part._DATA));
                String cid = c.getString(c.getColumnIndex(Mms.Part.CONTENT_ID));
                String cl = c.getString(c.getColumnIndex(Mms.Part.CONTENT_LOCATION));
                String cdisp = c.getString(c.getColumnIndex(Mms.Part.CONTENT_DISPOSITION));

                if(V) Log.d(TAG, "     _id : " + partId +
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
                part.mContentType = contentType;
                part.mPartName = name;
                part.mContentId = cid;
                part.mContentLocation = cl;
                part.mContentDisposition = cdisp;

                try {
                    if(text != null) {
                        part.mData = text.getBytes("UTF-8");
                        part.mCharsetName = "utf-8";
                    } else {
                        part.mData = readMmsDataPart(partId);
                        if(charset != null)
                            part.mCharsetName = CharacterSets.getMimeName(Integer.parseInt(charset));
                    }
                } catch (NumberFormatException e) {
                    Log.d(TAG,"extractMmsParts",e);
                    part.mData = null;
                    part.mCharsetName = null;
                } catch (UnsupportedEncodingException e) {
                    Log.d(TAG,"extractMmsParts",e);
                    part.mData = null;
                    part.mCharsetName = null;
                }
                part.mFileName = filename;
            }
        } finally {
            close(c);
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
        if (appParams.getCharset() == MAP_MESSAGE_CHARSET_NATIVE)
            throw new IllegalArgumentException("MMS charset native not allowed for MMS - must be utf-8");

        BluetoothMapbMessageMms message = new BluetoothMapbMessageMms();
        Cursor c = mResolver.query(Mms.CONTENT_URI, MMS_PROJECTION, "_ID = " + id, null, null);
        if (c == null || !c.moveToFirst()) {
            throw new IllegalArgumentException("MMS handle not found");
        }

        try {
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
            message.setTextOnly(c.getInt(c.getColumnIndex(Mms.TEXT_ONLY)) == 0 ? false : true);
            message.setIncludeAttachments(appParams.getAttachment() == 0 ? false : true);

            extractMmsParts(id, message);
            extractMmsAddresses(id, message);
        } finally {
            close(c);
        }

        return message.encode();
    }

    /**
    *
    * @param id the content provider id for the message to fetch.
    * @param appParams The application parameter object received from the client.
    * @return a byte[] containing the utf-8 encoded bMessage to send to the client.
    * @throws UnsupportedEncodingException if UTF-8 is not supported,
    * which is guaranteed to be supported on an android device
    */
   public byte[] getEmailMessage(long id, BluetoothMapAppParams appParams,
           BluetoothMapFolderElement currentFolder) throws UnsupportedEncodingException {
       // Log print out of application parameters set
       if(D && appParams != null) {
           Log.d(TAG,"TYPE_MESSAGE (GET): Attachment = " + appParams.getAttachment() +
                   ", Charset = " + appParams.getCharset() +
                   ", FractionRequest = " + appParams.getFractionRequest());
       }

       // Throw exception if requester NATIVE charset for Email
       // Exception is caught by MapObexServer sendGetMessageResp
       if (appParams.getCharset() == MAP_MESSAGE_CHARSET_NATIVE)
           throw new IllegalArgumentException("EMAIL charset not UTF-8");

       BluetoothMapbMessageEmail message = new BluetoothMapbMessageEmail();
       Uri contentUri = Uri.parse(mBaseEmailUri + BluetoothMapContract.TABLE_MESSAGE);
       Cursor c = mResolver.query(contentUri, BluetoothMapContract.BT_MESSAGE_PROJECTION, "_ID = " + id, null, null);
       if (c != null && c.moveToFirst()) {
           throw new IllegalArgumentException("EMAIL handle not found");
       }

       try {
           BluetoothMapFolderElement folderElement;

           // Handle fraction requests
           int fractionRequest = appParams.getFractionRequest();
           if (fractionRequest != BluetoothMapAppParams.INVALID_VALUE_PARAMETER) {
               // Fraction requested
               if(V) {
                   String fractionStr = (fractionRequest == 0) ? "FIRST" : "NEXT";
                   Log.v(TAG, "getEmailMessage - FractionRequest " + fractionStr
                           +  " - send compete message" );
               }
               // Check if message is complete and if not - request message from server
               if (c.getString(c.getColumnIndex(
                       BluetoothMapContract.MessageColumns.RECEPTION_STATE)).equalsIgnoreCase(
                               BluetoothMapContract.RECEPTION_STATE_COMPLETE) == false)  {
                   // TODO: request message from server
                   Log.w(TAG, "getEmailMessage - receptionState not COMPLETE -  Not Implemented!" );
               }
           }
           // Set read status:
           String read = c.getString(c.getColumnIndex(BluetoothMapContract.MessageColumns.FLAG_READ));
           if (read != null && read.equalsIgnoreCase("1"))
               message.setStatus(true);
           else
               message.setStatus(false);

           // Set message type:
           message.setType(TYPE.EMAIL);

           // Set folder:
           long folderId = c.getLong(c.getColumnIndex(BluetoothMapContract.MessageColumns.FOLDER_ID));
           folderElement = currentFolder.getEmailFolderById(folderId);
           message.setCompleteFolder(folderElement.getFullPath());

           // Set recipient:
           String nameEmail = c.getString(c.getColumnIndex(BluetoothMapContract.MessageColumns.TO_LIST));
           Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(nameEmail);
           if (tokens.length != 0) {
               if(D) Log.d(TAG, "Recipient count= " + tokens.length);
               int i = 0;
               while (i < tokens.length) {
                   if(V) Log.d(TAG, "Recipient = " + tokens[i].toString());
                   String[] emails = new String[1];
                   emails[0] = tokens[i].getAddress();
                   String name = tokens[i].getName();
                   message.addRecipient(name, name, null, emails);
                   i++;
               }
           }

           // Set originator:
           nameEmail = c.getString(c.getColumnIndex(BluetoothMapContract.MessageColumns.FROM_LIST));
           tokens = Rfc822Tokenizer.tokenize(nameEmail);
           if (tokens.length != 0) {
               if(D) Log.d(TAG, "Originator count= " + tokens.length);
               int i = 0;
               while (i < tokens.length) {
                   if(V) Log.d(TAG, "Originator = " + tokens[i].toString());
                   String[] emails = new String[1];
                   emails[0] = tokens[i].getAddress();
                   String name = tokens[i].getName();
                   message.addOriginator(name, name, null, emails);
                   i++;
               }
           }

           // Find out if we get attachments
           String attStr = (appParams.getAttachment() == 0) ?  "/" +  BluetoothMapContract.FILE_MSG_NO_ATTACHMENTS : "";
           Uri uri = Uri.parse(contentUri + "/" + id + attStr);

           // Get email message body content
           int count = 0;
           FileInputStream is = null;
           ParcelFileDescriptor fd = null;

           try {
               fd = mResolver.openFileDescriptor(uri, "r");
               is = new FileInputStream(fd.getFileDescriptor());
               StringBuilder email = new StringBuilder("");
               byte[] buffer = new byte[1024];
               while((count = is.read(buffer)) != -1) {
                   // TODO: Handle breaks within a UTF8 character
                   email.append(new String(buffer,0,count));
                   if(V) Log.d(TAG, "Email part = " + new String(buffer,0,count) + " count=" + count);
               }
               // Set email message body:
               message.setEmailBody(email.toString());
           } catch (FileNotFoundException e) {
               Log.w(TAG, e);
           } catch (NullPointerException e) {
               Log.w(TAG, e);
           } catch (IOException e) {
               Log.w(TAG, e);
           } finally {
               close(is);
               close(fd);
           }
       } finally {
           close(c);
       }

       return message.encode();
   }
}
