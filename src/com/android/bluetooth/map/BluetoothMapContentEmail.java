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
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.net.Uri.Builder;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.provider.Telephony.MmsSms;
import android.provider.Telephony.CanonicalAddressesColumns;
import android.provider.Telephony.Threads;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.os.RemoteException;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentFilter.MalformedMimeTypeException;
import android.text.format.Time;
import android.util.TimeFormatException;


import com.android.bluetooth.SignedLongLong;
import com.android.bluetooth.map.BluetoothMapContentObserver.Msg;
import com.android.bluetooth.map.BluetoothMapUtils.TYPE;
import com.android.bluetooth.map.BluetoothMapbMessageMime.MimePart;
import com.android.bluetooth.mapapi.BluetoothMapEmailContract;
import com.android.bluetooth.mapapi.BluetoothMapContract;
import com.android.bluetooth.mapapi.BluetoothMapContract.ConversationColumns;
import com.google.android.mms.pdu.CharacterSets;
import com.google.android.mms.pdu.PduHeaders;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.StringTokenizer;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import android.text.Html;
import java.util.concurrent.atomic.AtomicLong;
import com.android.emailcommon.provider.EmailContent.Message;

@TargetApi(19)
public class BluetoothMapContentEmail extends BluetoothMapContent {

    private static final String TAG = "BluetoothMapContentEmail";

    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = Log.isLoggable(BluetoothMapService.LOG_TAG, Log.VERBOSE);

    // Parameter Mask for selection of parameters to return in listings
    private static final int MASK_SUBJECT               = 0x00000001;
    private static final int MASK_DATETIME              = 0x00000002;
    private static final int MASK_SENDER_NAME           = 0x00000004;
    private static final int MASK_SENDER_ADDRESSING     = 0x00000008;
    private static final int MASK_RECIPIENT_NAME        = 0x00000010;
    private static final int MASK_RECIPIENT_ADDRESSING  = 0x00000020;
    private static final int MASK_TYPE                  = 0x00000040;
    private static final int MASK_SIZE                  = 0x00000080;
    private static final int MASK_RECEPTION_STATUS      = 0x00000100;
    private static final int MASK_TEXT                  = 0x00000200;
    private static final int MASK_ATTACHMENT_SIZE       = 0x00000400;
    private static final int MASK_PRIORITY              = 0x00000800;
    private static final int MASK_READ                  = 0x00001000;
    private static final int MASK_SENT                  = 0x00002000;
    private static final int MASK_PROTECTED             = 0x00004000;
    private static final int MASK_REPLYTO_ADDRESSING    = 0x00008000;
    // TODO: Duplicate in proposed spec
    // private static final int MASK_RECEPTION_STATE       = 0x00010000;
    private static final int MASK_DELIVERY_STATUS       = 0x00020000;
    private static final int MASK_CONVERSATION_ID       = 0x00040000;
    private static final int MASK_CONVERSATION_NAME     = 0x00080000;
    private static final int MASK_FOLDER_TYPE           = 0x00100000;


    private static final int FILTER_READ_STATUS_UNREAD_ONLY = 0x01;
    private static final int FILTER_READ_STATUS_READ_ONLY   = 0x02;
    private static final int FILTER_READ_STATUS_ALL         = 0x00;

    public static final String INSERT_ADDRES_TOKEN = "insert-address-token";

    private final Context mContext;
    private final ContentResolver mResolver;
    private final String mBaseUri;
    private final BluetoothMapAccountItem mAccount;
    /* The MasInstance reference is used to update persistent (over a connection) version counters*/
    private final BluetoothMapMasInstance mMasInstance;
    private String mMessageVersion = BluetoothMapUtils.MAP_V10_STR;
    private final boolean EMAIL_ATTACHMENT_IMPLEMENTED = false;

    private int mRemoteFeatureMask = BluetoothMapUtils.MAP_FEATURE_DEFAULT_BITMASK;
    private int mMsgListingVersion = BluetoothMapUtils.MAP_MESSAGE_LISTING_FORMAT_V10;

    private class FilterInfo {
            public static final int TYPE_SMS    = 0;
            public static final int TYPE_MMS    = 1;
            public static final int TYPE_EMAIL  = 2;
            public static final int TYPE_IM     = 3;

            // TODO: Change to ENUM, to ensure correct usage
            int mMsgType = TYPE_EMAIL;
            /*column indices used to optimize queries */
            public int mMessageColId                = -1;
            public int mMessageColDate              = -1;
            public int mMessageColBody              = -1;
            public int mMessageColSubject           = -1;
            public int mMessageColFolder            = -1;
            public int mMessageColRead              = -1;
            public int mMessageColSize              = -1;
            public int mMessageColFromAddress       = -1;
            public int mMessageColToAddress         = -1;
            public int mMessageColCcAddress         = -1;
            public int mMessageColBccAddress        = -1;
            public int mMessageColReplyTo           = -1;
            public int mMessageColAccountId         = -1;
            public int mMessageColAttachment        = -1;
            public int mMessageColAttachmentSize    = -1;
            public int mMessageColAttachmentMime    = -1;
            public int mMessageColPriority          = -1;
            public int mMessageColProtected         = -1;

            public void setEmailAttachmentColumns(Cursor c) {
                mMessageColAttachmentSize   = c.getColumnIndex(
                    BluetoothMapEmailContract.ExtEmailMessageColumns.EMAIL_ATTACHMENT_SIZE);
            }

            public void setEmailMessageColumns(Cursor c) {
                mMessageColId               = c.getColumnIndex(
                    BluetoothMapEmailContract.ExtEmailMessageColumns.RECORD_ID);
                mMessageColDate             = c.getColumnIndex(
                    BluetoothMapEmailContract.ExtEmailMessageColumns.TIMESTAMP);
                mMessageColSubject          = c.getColumnIndex(
                    BluetoothMapContract.MessageColumns.SUBJECT);
                mMessageColFolder           = c.getColumnIndex(
                    BluetoothMapEmailContract.ExtEmailMessageColumns.MAILBOX_KEY);
                mMessageColRead             = c.getColumnIndex(
                    BluetoothMapEmailContract.ExtEmailMessageColumns.EMAIL_FLAG_READ);
                //TODO: EMAIL SIZE - No DB value available
                mMessageColSize             = 0;
                mMessageColFromAddress      = c.getColumnIndex(
                    BluetoothMapEmailContract.ExtEmailMessageColumns.EMAIL_FROM_LIST);
                mMessageColToAddress        = c.getColumnIndex(
                    BluetoothMapEmailContract.ExtEmailMessageColumns.EMAIL_TO_LIST);
                mMessageColAttachment       = c.getColumnIndex(
                    BluetoothMapEmailContract.ExtEmailMessageColumns.EMAIL_FLAG_ATTACHMENT);
                mMessageColCcAddress        = c.getColumnIndex(
                    BluetoothMapEmailContract.ExtEmailMessageColumns.EMAIL_CC_LIST);
                mMessageColBccAddress       = c.getColumnIndex(
                    BluetoothMapEmailContract.ExtEmailMessageColumns.EMAIL_BCC_LIST);
                mMessageColReplyTo          = c.getColumnIndex(
                    BluetoothMapEmailContract.ExtEmailMessageColumns.EMAIL_REPLY_TO_LIST);
            }

        }

    public BluetoothMapContentEmail(final Context context, BluetoothMapAccountItem account,
            BluetoothMapMasInstance mas) {
        super(context, null, mas);
        mContext = context;
        mResolver = mContext.getContentResolver();
        mMasInstance = mas;
        if (mResolver == null) {
            if (D) Log.d(TAG, "getContentResolver failed");
        }

        if(account != null){
            mBaseUri = account.mBase_uri + "/";
            mAccount = account;
        } else {
            mBaseUri = null;
            mAccount = null;
        }
    }
    private static void close(Closeable c) {
        try {
          if (c != null) c.close();
        } catch (IOException e) {
        }
    }


    private void setSent(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_SENT) != 0) {
            int msgType = 0;
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL ||
                       fi.mMsgType == FilterInfo.TYPE_IM) {
                msgType = c.getInt(fi.mMessageColFolder);
            }
            String sent = null;
            if(fi.mMsgType == FilterInfo.TYPE_EMAIL &&
                msgType == BluetoothMapEmailContract.TYPE_SENT) {
              sent = "yes";
            } else {
                sent = "no";
            }
            if (V) Log.d(TAG, "setSent: " + sent);
            e.setSent(sent);
        }
    }
    private void setSent(BluetoothMapMessageListingElement e, BluetoothMapFolderElement f,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_SENT) != 0) {
            int msgFolType = -1;
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL ||
                       fi.mMsgType == FilterInfo.TYPE_IM ) {
                msgFolType = f.getFolderType();
            }
            if (V) Log.d(TAG, "setSent: msgFolType" + msgFolType);
            String sent = null;
            if(fi.mMsgType == FilterInfo.TYPE_EMAIL &&
                msgFolType == BluetoothMapEmailContract.TYPE_SENT) {
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
        if (fi.mMsgType == FilterInfo.TYPE_EMAIL ||
                   fi.mMsgType == FilterInfo.TYPE_IM) {
            read = c.getInt(fi.mMessageColRead);
        }
        String setread = null;

        if (V) Log.d(TAG, "setRead: " + setread);
        e.setRead((read==1?true:false), ((ap.getParameterMask() & MASK_READ) != 0));
    }
    /**
     * For SMS we set the attachment size to 0, as all data will be text data, hence
     * attachments for SMS is not possible.
     * For MMS all data is actually attachments, hence we do set the attachment size to
     * the total message size. To provide a more accurate attachment size, one could
     * extract the length (in bytes) of the text parts.
     */
    private void setAttachment(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_ATTACHMENT_SIZE) != 0) {
            int size = 0;
            String attachmentMimeTypes = null;
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                int attachment = c.getInt(fi.mMessageColAttachment);
                //ATTACHMENT DB
                Uri attchmntUri = BluetoothMapEmailContract
                    .buildEmailAttachmentUri(BluetoothMapEmailContract.EMAIL_AUTHORITY);
                Log.d(TAG, "attchURI: "+attchmntUri);
                String whereAttch = setWhereFilterMessagekey(
                    c.getLong(fi.mMessageColId));
                Log.d(TAG, "whereAttch: "+whereAttch+" handle: "
                    + c.getLong(fi.mMessageColId));
                Cursor emailAttachmentCursor = mResolver.query(attchmntUri,
                        BluetoothMapEmailContract.BT_EMAIL_ATTACHMENT_PROJECTION,
                                whereAttch, null, null);
                Log.d(TAG, "Found " + emailAttachmentCursor.getCount() + " size messages.");
                fi.setEmailAttachmentColumns(emailAttachmentCursor);
                if(emailAttachmentCursor != null) {
                    if( emailAttachmentCursor.moveToNext()) {
                        size = emailAttachmentCursor.getInt(fi.mMessageColAttachmentSize);
                    }
                    emailAttachmentCursor.close();
                    emailAttachmentCursor = null;
                    Log.d(TAG, "size: "+size);
                }
                if(attachment == 1 && size == 0) {
                    if (D) Log.d(TAG, "Error in message database, attachment size reported as: "
                            + size + " Changing size to 1");
                    size = 1; /* Ensure we indicate we have attachments in the size, if the
                                 message has attachments, in case the e-mail client do not
                                 report a size */
                }
            }
            if (V) Log.d(TAG, "setAttachmentSize: " + size + "\n" +
                              "setAttachmentMimeTypes: " + attachmentMimeTypes );
            e.setAttachmentSize(size);
        }
    }

    private void setText(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_TEXT) != 0) {
            String hasText = "";
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL ) {
                hasText = "yes";
            }
            if (V) Log.d(TAG, "setText: " + hasText);
            e.setText(hasText);
        }
    }

    private void setPriority(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        String priority = "no";
        if ((ap.getParameterMask() & MASK_PRIORITY) != 0) {
            if (D) Log.d(TAG, "setPriority: " + priority);
            e.setPriority(priority);
        }
    }

    private void setSize(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_SIZE) != 0) {
            int size = 0;
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                long msgId = c.getLong(fi.mMessageColId);
                String textContent, htmlContent;
                Uri uriAddress = BluetoothMapEmailContract.buildEmailMessageBodyUri(
                                     BluetoothMapEmailContract.EMAIL_AUTHORITY);
                Cursor cr = mResolver.query(uriAddress,
                    BluetoothMapEmailContract.BT_EMAIL_BODY_CONTENT_PROJECTION,
                    BluetoothMapEmailContract.EmailBodyColumns.MESSAGE_KEY + "=?",
                    new String[] {String.valueOf(msgId)}, null);
                if (cr != null && cr.moveToFirst()) {
                    ParcelFileDescriptor fd = null;
                    String textContentURI = cr.getString(cr.getColumnIndex(
                                     BluetoothMapEmailContract.EmailBodyColumns.TEXT_CONTENT_URI));
                    if (textContentURI != null ) {
                        try {
                           Log.v(TAG, " TRY EMAIL BODY textURI " + textContentURI);
                           fd = mResolver.openFileDescriptor(Uri.parse(textContentURI), "r");
                        } catch (FileNotFoundException ex) {
                           if(V) Log.w(TAG, ex);
                        }
                    }
                    if (fd == null ) {
                       String htmlContentURI = cr.getString(cr
                                                 .getColumnIndex(BluetoothMapEmailContract
                                                 .EmailBodyColumns.HTML_CONTENT_URI));
                        if (htmlContentURI != null ) {
                            try {
                                Log.v(TAG, " TRY EMAIL BODY htmlURI " + htmlContentURI);
                                fd = mResolver.openFileDescriptor(Uri.parse(htmlContentURI), "r");
                            } catch (FileNotFoundException ex) {
                                if(V) Log.w(TAG, ex);
                            }
                        }
                    }
                    if (fd != null ) {
                        //Size in bytes
                        size = (Long.valueOf(fd.getStatSize()).intValue());
                        //Add up to attachment_size
                        int attachmentSize = e.getAttachmentSize();
                        if(attachmentSize != -1) {
                            size += attachmentSize;
                        }
                        try {
                            fd.close();
                        } catch (IOException ex) {
                            if(V) Log.w(TAG, ex);
                        }
                    } else {
                        Log.e(TAG, "MessageSize Email NOT AVAILABLE");
                    }
                }
                if (cr != null )
                    cr.close();
            }
            if (size <= 0) {
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

    private TYPE getType(Cursor c, FilterInfo fi) {
        TYPE type = null;
        if (V) Log.d(TAG, "getType: for filterMsgType" + fi.mMsgType);
        if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
            type = TYPE.EMAIL;
        }
        if (V) Log.d(TAG, "getType: " + type);
        return type;
    }

    private void setFolderType(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_FOLDER_TYPE) != 0) {
            String folderType = null;
            int folderId = 0;
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                // TODO: need to find name from id and then set folder type
            }
            if (V) Log.d(TAG, "setFolderType: " + folderType);
            e.setFolderType(folderType);
        }
    }

    private String getRecipientNameEmail(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi) {

        String toAddress, ccAddress, bccAddress;
        toAddress = c.getString(fi.mMessageColToAddress);
        ccAddress = c.getString(fi.mMessageColCcAddress);
        bccAddress = c.getString(fi.mMessageColBccAddress);
        StringBuilder sb = new StringBuilder();
        if (toAddress != null) {
            Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(toAddress);
            if (tokens.length != 0) {
                if(D) Log.d(TAG, "toName count= " + tokens.length);
                int i = 0;
                boolean first = true;
                while (i < tokens.length) {
                    if(V) Log.d(TAG, "ToName = " + tokens[i].toString());
                    String name = tokens[i].getName();
                    if (!first) sb.append("; "); //Delimiter
                    sb.append(name);
                    first = false;
                    i++;
                }
            }
            if (ccAddress != null) {
                sb.append("; ");
            }
        }
        if (ccAddress != null) {
            Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(ccAddress);
            if (tokens.length != 0) {
                if(D) Log.d(TAG, "ccName count= " + tokens.length);
                int i = 0;
                boolean first = true;
                while (i < tokens.length) {
                    if (V) Log.d(TAG, "ccName = " + tokens[i].toString());
                    String name = tokens[i].getName();
                    if (!first) sb.append("; "); //Delimiter
                    sb.append(name);
                    first = false;
                    i++;
                }
            }
            if (bccAddress != null) {
                sb.append("; ");
            }
        }
        if (bccAddress != null) {
            Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(bccAddress);
            if (tokens.length != 0) {
                if(D) Log.d(TAG, "bccName count= " + tokens.length);
                int i = 0;
                boolean first = true;
                while (i < tokens.length) {
                    if(V) Log.d(TAG, "bccName = " + tokens[i].toString());
                    String name = tokens[i].getName();
                    if(!first) sb.append("; "); //Delimiter
                    sb.append(name);
                    first = false;
                    i++;
                }
            }
        }
        return sb.toString();
    }

    private String getRecipientAddressingEmail(BluetoothMapMessageListingElement e,
                                               Cursor c,
                                               FilterInfo fi) {
        String toAddress, ccAddress, bccAddress;
        toAddress = c.getString(fi.mMessageColToAddress);
        ccAddress = c.getString(fi.mMessageColCcAddress);
        bccAddress = c.getString(fi.mMessageColBccAddress);

        StringBuilder sb = new StringBuilder();
        if (toAddress != null) {
            Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(toAddress);
            if (tokens.length != 0) {
                if(D) Log.d(TAG, "toAddress count= " + tokens.length);
                int i = 0;
                boolean first = true;
                while (i < tokens.length) {
                    if(V) Log.d(TAG, "ToAddress = " + tokens[i].toString());
                    String email = tokens[i].getAddress();
                    if(!first) sb.append("; "); //Delimiter
                    sb.append(email);
                    first = false;
                    i++;
                }
            }

            if (ccAddress != null) {
                sb.append("; ");
            }
        }
        if (ccAddress != null) {
            Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(ccAddress);
            if (tokens.length != 0) {
                if(D) Log.d(TAG, "ccAddress count= " + tokens.length);
                int i = 0;
                boolean first = true;
                while (i < tokens.length) {
                    if(V) Log.d(TAG, "ccAddress = " + tokens[i].toString());
                    String email = tokens[i].getAddress();
                    if(!first) sb.append("; "); //Delimiter
                    sb.append(email);
                    first = false;
                    i++;
                }
            }
            if (bccAddress != null) {
                sb.append("; ");
            }
        }
        if (bccAddress != null) {
            Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(bccAddress);
            if (tokens.length != 0) {
                if(D) Log.d(TAG, "bccAddress count= " + tokens.length);
                int i = 0;
                boolean first = true;
                while (i < tokens.length) {
                    if(V) Log.d(TAG, "bccAddress = " + tokens[i].toString());
                    String email = tokens[i].getAddress();
                    if(!first) sb.append("; "); //Delimiter
                    sb.append(email);
                    first = false;
                    i++;
                }
            }
        }
        return sb.toString();
    }

    private void setProtected(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_PROTECTED) != 0) {
            String protect = "no";
            if (V) Log.d(TAG, "setProtected: " + protect + "\n");
            e.setProtect(protect);
        }
    }

    private void setRecipientAddressing(BluetoothMapMessageListingElement e, Cursor c,
        FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_RECIPIENT_ADDRESSING) != 0) {
            String address = null;
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                /* Might be another way to handle addresses */
                address = getRecipientAddressingEmail(e, c,fi);
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
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                /* Might be another way to handle address and names */
                name = getRecipientNameEmail(e,c,fi);
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
            String address = "";
            String tempAddress;
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                String nameEmail = c.getString(fi.mMessageColFromAddress);
                Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(nameEmail);
                if (tokens.length != 0) {
                    if(D) Log.d(TAG, "Originator count= " + tokens.length);
                    int i = 0;
                    boolean first = true;
                    while (i < tokens.length) {
                        if(V) Log.d(TAG, "SenderAddress = " + tokens[i].toString());
                        String[] emails = new String[1];
                        emails[0] = tokens[i].getAddress();
                        String name = tokens[i].getName();
                        if(!first) address += "; "; //Delimiter
                        address += emails[0];
                        first = false;
                        i++;
                    }
                }
            }
            if (V) Log.v(TAG, "setSenderAddressing: " + address);
            if(address == null)
                address = "";
            e.setSenderAddressing(address);
        }
    }

    private void setReplytoAddressing(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_REPLYTO_ADDRESSING) != 0) {
            String address = null;
                if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                    address = c.getString(fi.mMessageColReplyTo);
                    if (address == null)
                        address = "";
                }
                if (D) Log.d(TAG, "setReplytoAddressing: " + address);
                e.setReplytoAddressing(address);
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

    private void setSenderName(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        if ((ap.getParameterMask() & MASK_SENDER_NAME) != 0) {
            String name = "";
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                String nameEmail = c.getString(fi.mMessageColFromAddress);
                Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(nameEmail);
                if (tokens.length != 0) {
                    if(D) Log.d(TAG, "Originator count= " + tokens.length);
                    int i = 0;
                    boolean first = true;
                    while (i < tokens.length) {
                        if(V) Log.d(TAG, "senderName = " + tokens[i].toString());
                        String[] emails = new String[1];
                        emails[0] = tokens[i].getAddress();
                        String nameIn = tokens[i].getName();
                        if(!first) name += "; "; //Delimiter
                        name += nameIn;
                        first = false;
                        i++;
                    }
                }
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
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL ) {
                date = c.getLong(fi.mMessageColDate);
            }
            e.setDateTime(date);
        }
    }

    private void setSubject(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        String subject = "";
        int subLength = ap.getSubjectLength();
        if(subLength == BluetoothMapAppParams.INVALID_VALUE_PARAMETER)
            subLength = 256;

        if ((ap.getParameterMask() & MASK_SUBJECT) != 0) {
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                subject = c.getString(fi.mMessageColSubject);
            }
            if (subject != null && subject.length() > subLength) {
                subject = subject.substring(0, subLength);
            } else if(subject == null ) {
                subject = "";
            }
            if (V) Log.d(TAG, "setSubject: " + subject);
            e.setSubject(subject);
        }
    }

    private void setHandle(BluetoothMapMessageListingElement e, Cursor c,
            FilterInfo fi, BluetoothMapAppParams ap) {
        long handle = -1;
        if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
            handle = c.getLong(fi.mMessageColId);
        }
        if (V) Log.d(TAG, "setHandle: " + handle );
        e.setHandle(handle);
    }

    private BluetoothMapMessageListingElement element(Cursor c, FilterInfo fi,
            BluetoothMapAppParams ap) {
        BluetoothMapMessageListingElement e = new BluetoothMapMessageListingElement();
        setHandle(e, c, fi, ap);
        setSubject(e, c, fi, ap);
        setDateTime(e, c, fi, ap);
        setSenderName(e, c, fi, ap);
        setSenderAddressing(e, c, fi, ap);
        setReplytoAddressing(e, c, fi, ap);
        setRecipientName(e, c, fi, ap);
        setRecipientAddressing(e, c, fi, ap);
        e.setType(getType(c, fi), ((ap.getParameterMask() & MASK_TYPE) != 0) ? true : false);
        setReceptionStatus(e, c, fi, ap);
        setText(e, c, fi, ap);
        setAttachment(e, c, fi, ap);
        setSize(e, c, fi, ap);
        setPriority(e, c, fi, ap);
        setRead(e, c, fi, ap);
        setProtected(e, c, fi, ap);
        e.setCursorIndex(c.getPosition());
        return e;
    }

    private boolean matchRecipient(Cursor c, FilterInfo fi, BluetoothMapAppParams ap) {
        boolean res = false;
        String recip = ap.getFilterRecipient();
        if (recip != null && recip.length() > 0) {
            recip = recip.replace("*", ".*");
            recip = ".*" + recip + ".*";
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                String name = c.getString(fi.mMessageColToAddress);
                if (name != null && name.length() > 0) {
                    if (name.matches(recip)) {
                        res = true;
                    }
                }
            }
        } else {
            res = true;
        }
        return res;
    }

   private boolean matchOriginator(Cursor c, FilterInfo fi, BluetoothMapAppParams ap) {
        boolean res = false;
        String orig = ap.getFilterOriginator();
        if (orig != null && orig.length() > 0) {
            orig = orig.replace("*", ".*");
            orig = ".*" + orig + ".*";
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                String name;
                String originatorEmail;
                int displayNameIndex = c.getColumnIndex(BluetoothMapEmailContract
                        .ExtEmailMessageColumns.DISPLAY_NAME);
                name = c.getString(displayNameIndex);
                originatorEmail = c.getString(c.getColumnIndex(BluetoothMapEmailContract
                        .ExtEmailMessageColumns.EMAIL_FROM_LIST));
                if (name != null && name.length() > 0) {
                    if (name.toLowerCase().matches(orig.toLowerCase())) {
                        if (D) Log.d(TAG, "match originator name = " + name);
                        res = true;
                    }
                }
                if (originatorEmail != null && originatorEmail.length() > 0) {
                     if (originatorEmail.toLowerCase().matches(orig.toLowerCase())) {
                         if (D) Log.d(TAG, "match originator email = " + originatorEmail);
                         res = true;
                     }
                }
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
    private String setWhereFilterFolderTypeEmail(long folderId) {
        String where = "";
        if (folderId >= 0) {
            where = BluetoothMapEmailContract.ExtEmailMessageColumns.MAILBOX_KEY + " = "
                + folderId;
        } else {
            Log.e(TAG, "setWhereFilterFolderTypeEmail: not valid!" );
            throw new IllegalArgumentException("Invalid folder ID");
        }
        return where;
    }

    private String setWhereFilterAccountKey(long id) {
      String where = "";
      where = BluetoothMapEmailContract.ExtEmailMessageColumns.ACCOUNT_KEY + "=" +id+" AND ";
      return where;
    }

    private String setWhereFilterFolderType(BluetoothMapFolderElement folderElement,
                                            FilterInfo fi) {
        String where = "";
        if(folderElement.shouldIgnore()) {
            where = "1=1";
        } else {
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                if(mAccount != null)
                    where = setWhereFilterAccountKey(mAccount.getAccountId());
                where += setWhereFilterFolderTypeEmail(folderElement.getFolderId());
            }
        }
        return where;
    }

    private String setWhereFilterReadStatus(BluetoothMapAppParams ap, FilterInfo fi) {
        String where = "";
        if (ap.getFilterReadStatus() != -1) {
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL ) {
                if ((ap.getFilterReadStatus() & 0x01) != 0) {
                    where = " AND " + BluetoothMapEmailContract
                            .ExtEmailMessageColumns.EMAIL_FLAG_READ + "= 0";
                }
                if ((ap.getFilterReadStatus() & 0x02) != 0) {
                    where = " AND " + BluetoothMapEmailContract
                            .ExtEmailMessageColumns.EMAIL_FLAG_READ + "= 1";
                }
            }
        }
        return where;
    }

    private String setWhereFilterPeriod(BluetoothMapAppParams ap, FilterInfo fi) {
        String where = "";

        if ((ap.getFilterPeriodBegin() != -1)) {
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                Time time = new Time();
                SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss");
                Date date = new Date(ap.getFilterPeriodBegin());
                try {
                    time.parse((format.format(date)).trim());
                     where = " AND " + BluetoothMapEmailContract.ExtEmailMessageColumns.TIMESTAMP +
                        " >= " + time.toMillis(false);
                } catch (TimeFormatException e) {
                    Log.d(TAG, "Bad formatted FilterPeriodEnd, Ignore"
                          + ap.getFilterPeriodBegin());
                }
            }
        }
        if ((ap.getFilterPeriodEnd() != -1)) {
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL){
                Time time = new Time();
                SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss");
                Date date = new Date(ap.getFilterPeriodEnd());
                try {
                    time.parse((format.format(date)).trim());
                    where += " AND " + BluetoothMapEmailContract.ExtEmailMessageColumns.TIMESTAMP +
                        " < " + (time.toMillis(false));
                } catch (TimeFormatException e) {
                    Log.d(TAG, "Bad formatted FilterPeriodEnd, Ignore"
                          + ap.getFilterPeriodEnd());
                }
            }
        }
        return where;
    }

    private String setWhereFilterMessagekey(long id) {
        String where = "";
        where = BluetoothMapEmailContract.EmailBodyColumns.MESSAGE_KEY + "=" +id;
        return where;
    }

    private String setWhereFilterOriginatorEmail(BluetoothMapAppParams ap) {
        String where = "";
        String orig = ap.getFilterOriginator();
        /* Be aware of wild cards in the beginning of string, may not be valid? */
        if (orig != null && orig.length() > 0) {
            orig = orig.replace("*", "%");
            where = " AND " + BluetoothMapEmailContract.ExtEmailMessageColumns.EMAIL_FROM_LIST
                    + " LIKE '%" +  orig + "%'";
        }
        return where;
    }

    private String setWhereFilterPriority(BluetoothMapAppParams ap, FilterInfo fi) {
        String where = "";
        int pri = ap.getFilterPriority();
        if(fi.mMsgType == FilterInfo.TYPE_EMAIL)
        {
            /*only MMS have priority info */
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
            + BluetoothMapEmailContract.ExtEmailMessageColumns.EMAIL_TO_LIST  + " LIKE '%"
            + recip + "%' OR " + BluetoothMapEmailContract.ExtEmailMessageColumns.EMAIL_CC_LIST
            + " LIKE '%" + recip + "%' OR " + BluetoothMapEmailContract.ExtEmailMessageColumns
            .EMAIL_BCC_LIST + " LIKE '%" + recip + "%' )";
        }
        return where;
    }

    private String setWhereFilterMessageHandle(BluetoothMapAppParams ap, FilterInfo fi) {
        String where = "";
        long id = -1;
        String msgHandle = ap.getFilterMsgHandleString();
        if(msgHandle != null) {
            id = BluetoothMapUtils.getCpHandle(msgHandle);
            if(D)Log.d(TAG,"id: " + id);
        }
        if(id != -1) {
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL ||
                       fi.mMsgType == FilterInfo.TYPE_IM) {
                where = " AND " + BluetoothMapContract.MessageColumns._ID + " = " + id;
            }
        }
        return where;
    }

    private String setWhereFilter(BluetoothMapFolderElement folderElement,
            FilterInfo fi, BluetoothMapAppParams ap) {
        String where = "";
        where += setWhereFilterFolderType(folderElement, fi);

        String msgHandleWhere = setWhereFilterMessageHandle(ap, fi);
        /* if message handle filter is available, the other filters should be ignored */
        if(msgHandleWhere.isEmpty()) {
            where += setWhereFilterReadStatus(ap, fi);
            where += setWhereFilterPriority(ap,fi);
            where += setWhereFilterPeriod(ap, fi);
            if (fi.mMsgType == FilterInfo.TYPE_EMAIL) {
                where += setWhereFilterOriginatorEmail(ap);
                where += setWhereFilterRecipientEmail(ap);
            }
        } else {
            where += msgHandleWhere;
        }

        return where;
    }

    /**
     * Determine from application parameter if email should be included.
     * The filter mask is set for message types not selected
     * @param fi
     * @param ap
     * @return boolean true if email is selected, false if not
     */
    private boolean emailSelected(BluetoothMapAppParams ap) {
        int msgType = ap.getFilterMessageType();

        if (D) Log.d(TAG, "emailSelected msgType: " + msgType);

        if (msgType == BluetoothMapAppParams.INVALID_VALUE_PARAMETER)
            return true;

        if ((msgType & BluetoothMapAppParams.FILTER_NO_EMAIL) == 0)
            return true;

        return false;
    }

    /**
     * Recursively adds folders based on the folders in the email content provider.
     *       Add a content observer? - to refresh the folder list if any change occurs.
     *       Consider simply deleting the entire table, and then rebuild using
     *       buildFolderStructure()
     *       WARNING: there is no way to notify the client about these changes - hence
     *       we need to either keep the folder structure constant, disconnect or fail anything
     *       referring to currentFolder.
     *       It is unclear what to set as current folder to be able to go one level up...
     *       The best solution would be to keep the folder structure constant during a connection.
     * @param folder the parent folder to which subFolders needs to be added. The
     *        folder.getFolderId() will be used to query sub-folders.
     *        Use a parentFolder with id -1 to get all folders from root.
     */
    public void addEmailFolders(BluetoothMapFolderElement parentFolder) throws RemoteException {
        // Select all parent folders
        BluetoothMapFolderElement newFolder;
        if (mAccount == null) {
            throw new RemoteException("Failed to acquire provider for NULL account");
        }
        Uri emailFolderUri =
                BluetoothMapEmailContract.buildMailboxUri(mAccount.getProviderAuthority());
        long accountId = mAccount.getAccountId();
        String where = BluetoothMapEmailContract.MailBoxColumns.ACCOUNT_KEY + "=" + accountId;
        // Fix subFolder listing for mandatory folders added with folderId "-1".
        if (parentFolder.getName().equals("msg") && parentFolder.getFolderId() == -1) {
            //Fetch initial folders list, duplicate entries for name is already handled.
            where += " AND (" + BluetoothMapEmailContract.MailBoxColumns.PARENT_KEY +
                    " = " + parentFolder.getFolderId() + " OR "+
                    BluetoothMapEmailContract.MailBoxColumns.PARENT_SERVER_ID + " ISNULL )";
         } else if( parentFolder.getFolderId() != -1) {
           //Fetch subfolders
           where += " AND " + BluetoothMapEmailContract.MailBoxColumns.PARENT_KEY +
                    " = " + parentFolder.getFolderId();
         } else {
             if(V) Log.w(TAG,"Not a valid parentFolderId to fetch subFolders" +
                     parentFolder.getName());
             return;
         }
         if (V) Log.v(TAG, "addEmailFolders(): parentFolder: "+ parentFolder.getName() +
            "accountId: " + accountId+ " where: " + where);
        Cursor c = mResolver.query(emailFolderUri,
                BluetoothMapEmailContract.BT_EMAIL_MAILBOX_PROJECTION, where, null, null);
        try {
            if (c != null) {
                c.moveToPosition(-1);
                while (c.moveToNext()) {
                    String name = c.getString(c.getColumnIndex(
                            BluetoothMapEmailContract.MailBoxColumns.DISPLAY_NAME));
                    long id = c.getLong(c.getColumnIndex(BluetoothMapContract.FolderColumns._ID));
                    int type = c.getInt(c.getColumnIndex(BluetoothMapEmailContract.MailBoxColumns
                            .FOLDER_TYPE));
                    Log.d(TAG, "addEmailFolders(): id: "+id+ " Name: " + name + "Type: " + type);
                    newFolder = parentFolder.addEmailFolder(name, id);
                    newFolder.setFolderType(type);
                    addEmailFolders(newFolder); // Use recursion to add any sub folders
                }

            } else {
                if (D) Log.d(TAG, "addEmailFolders(): no elements found");
            }
        } finally {
            if (c != null) c.close();
        }
    }

    public void msgUpdate() {
        if (D) Log.d(TAG, "Message Update");
        long accountId = mAccount.getAccountId();
        if (V) Log.v(TAG, " Account id for Inbox Update: " +accountId);
        Intent emailIn = new Intent();
        emailIn.setAction(BluetoothMapEmailContract.ACTION_CHECK_MAIL);
        emailIn.putExtra(BluetoothMapEmailContract.EXTRA_ACCOUNT, accountId);
        mContext.sendBroadcast(emailIn);
    }

    /**
     * Get a listing of message in folder after applying filter.
     * @param folder Must contain a valid folder string != null
     * @param ap Parameters specifying message content and filters
     * @return Listing object containing requested messages
     */
    public BluetoothMapMessageListing msgListing(BluetoothMapFolderElement folderElement,
            BluetoothMapAppParams ap) {
        if (D) Log.d(TAG, "msgListing: messageType = " + ap.getFilterMessageType() );

        BluetoothMapMessageListing bmList = new BluetoothMapMessageListing();

        /* We overwrite the parameter mask here if it is 0 or not present, as this
         * should cause all parameters to be included in the message list. */
        if(ap.getParameterMask() == BluetoothMapAppParams.INVALID_VALUE_PARAMETER ||
                ap.getParameterMask() == 0) {
            ap.setParameterMask(PARAMETER_MASK_ALL_ENABLED);
            if (V) Log.v(TAG, "msgListing(): appParameterMask is zero or not present, " +
                    "changing to all enabled by default: " + ap.getParameterMask());
        }
        if (V) Log.v(TAG, "folderElement hasSmsMmsContent = " + folderElement.hasSmsMmsContent() +
                " folderElement.hasEmailContent = " + folderElement.hasEmailContent() +
                " folderElement.hasImContent = " + folderElement.hasImContent());

        /* Cache some info used throughout filtering */
        FilterInfo fi = new FilterInfo();
        Cursor emailCursor = null;
        String limit = "";
        int countNum = ap.getMaxListCount();
        int offsetNum = ap.getStartOffset();
        if(countNum < 0 || countNum > 65536){
          //Max entries when maxListCount not specified
          countNum = 1024;
        }
        if(offsetNum < 0 || offsetNum > 65536) {
            offsetNum = 0;
        }
        limit=" LIMIT "+ (countNum + offsetNum);
        try{
            if (emailSelected(ap) && folderElement.hasEmailContent()) {
                if(ap.getFilterMessageType() == (BluetoothMapAppParams.FILTER_NO_MMS|
                                                 BluetoothMapAppParams.FILTER_NO_SMS_CDMA|
                                                 BluetoothMapAppParams.FILTER_NO_SMS_GSM|
                                                 BluetoothMapAppParams.FILTER_NO_IM)){
                    //set real limit and offset if only this type is used
                    //(only if offset/limit is used)
                    limit = " LIMIT " + countNum +" OFFSET "+ offsetNum;
                    if(D) Log.d(TAG, "Email Limit => "+limit);
                    offsetNum = 0;
                }
                fi.mMsgType = FilterInfo.TYPE_EMAIL;
                String where = setWhereFilter(folderElement, fi, ap);

                if(!where.isEmpty()) {
                    where += " AND "+ Message.FLAG_LOADED_SELECTION;
                    where += " order by " +BluetoothMapEmailContract.ExtEmailMessageColumns
                        .TIMESTAMP+" desc "+ limit;
                    if (D) Log.d(TAG, "msgType: " + fi.mMsgType + " where: " + where);
                    Uri contentUri = BluetoothMapEmailContract
                            .buildEmailMessageUri(BluetoothMapEmailContract.EMAIL_AUTHORITY);
                    emailCursor =
                            mResolver.query(contentUri, BluetoothMapEmailContract
                            .BT_EMAIL_MESSAGE_PROJECTION, where, null, null);
                    Log.d(TAG, "emailUri " + contentUri.toString());
                    if (emailCursor != null) {
                        BluetoothMapMessageListingElement e = null;
                        // store column index so we dont have to look them up anymore (optimization)
                        fi.setEmailMessageColumns(emailCursor);
                        Log.d(TAG, "Found " + emailCursor.getCount() + " email messages.");
                        while (emailCursor.moveToNext()) {
                            if(V) BluetoothMapUtils.printCursor(emailCursor);
                            e = element(emailCursor, fi, ap);
                            setSent(e, folderElement, fi, ap);
                            bmList.add(e);
                        }
                    }
                }
            }

            /* Enable this if post sorting and segmenting needed */
            bmList.sort();
            bmList.segment(ap.getMaxListCount(), offsetNum);
            List<BluetoothMapMessageListingElement> list = bmList.getList();
            int listSize = list.size();
            Cursor tmpCursor = null;
            for(int x=0;x<listSize;x++){
                BluetoothMapMessageListingElement ele = list.get(x);
                /* If OBEX "GET" request header includes "ParameterMask" with 'Type' NOT set,
                 * then ele.getType() returns "null" even for a valid cursor.
                 * Avoid NullPointerException in equals() check when 'mType' value is "null" */
                TYPE tmpType = ele.getType();
                if(emailCursor != null && ((TYPE.EMAIL).equals(tmpType))) {
                    tmpCursor = emailCursor;
                    fi.mMsgType = FilterInfo.TYPE_EMAIL;
                }
                if(tmpCursor != null){
                    tmpCursor.moveToPosition(ele.getCursorIndex());
                    setSenderAddressing(ele, tmpCursor, fi, ap);
                    setSenderName(ele, tmpCursor, fi, ap);
                    setRecipientAddressing(ele, tmpCursor, fi, ap);
                    setRecipientName(ele, tmpCursor, fi, ap);
                    setSubject(ele, tmpCursor, fi, ap);
                    setSize(ele, tmpCursor, fi, ap);
                    setText(ele, tmpCursor, fi, ap);
                    setPriority(ele, tmpCursor, fi, ap);
                    setSent(ele, folderElement, fi, ap);
                    setProtected(ele, tmpCursor, fi, ap);
                    setReceptionStatus(ele, tmpCursor, fi, ap);
                    setAttachment(ele, tmpCursor, fi, ap);

                    if(mMsgListingVersion > BluetoothMapUtils.MAP_MESSAGE_LISTING_FORMAT_V10 ){
                        //TODO: Whether required for EMAIL ?
                        setFolderType(ele, tmpCursor, fi, ap);
                    }
                }
            }
        } finally {
            if(emailCursor != null)emailCursor.close();
        }

        if(D)Log.d(TAG, "messagelisting end");
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

        if (emailSelected(ap) && folderElement.hasEmailContent()) {
            fi.mMsgType = FilterInfo.TYPE_EMAIL;
            String where = setWhereFilter(folderElement, fi, ap);
            if(!where.isEmpty()) {
                Uri contentUri = BluetoothMapEmailContract
                            .buildEmailMessageUri(BluetoothMapEmailContract.EMAIL_AUTHORITY);
                Cursor c = mResolver.query(contentUri,
                               BluetoothMapEmailContract.BT_EMAIL_MESSAGE_PROJECTION, where, null,
                               BluetoothMapEmailContract.ExtEmailMessageColumns.TIMESTAMP
                               + " DESC");
                try {
                    if (c != null) {
                        cnt += c.getCount();
                    }
                } finally {
                    if (c != null) c.close();
                }
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

        if (emailSelected(ap) && folderElement.getFolderId() != -1) {
            fi.mMsgType = FilterInfo.TYPE_EMAIL;
            String where = setWhereFilterFolderType(folderElement, fi);
            if(!where.isEmpty()) {
                where += " AND " + BluetoothMapEmailContract.ExtEmailMessageColumns.EMAIL_FLAG_READ
                         + "=0 ";
                where += setWhereFilterPeriod(ap, fi);
                Uri contentUri = BluetoothMapEmailContract
                            .buildEmailMessageUri(BluetoothMapEmailContract.EMAIL_AUTHORITY);
                Cursor c = mResolver.query(contentUri,
                            BluetoothMapEmailContract.BT_EMAIL_MESSAGE_PROJECTION, where, null,
                            BluetoothMapEmailContract.ExtEmailMessageColumns.TIMESTAMP + " DESC");
                try {
                    if (c != null) {
                        cnt += c.getCount();
                    }
                } finally {
                    if (c != null) c.close();
                }
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

    /**
     * Read out a mime data part and return the data in a byte array.
     * @param contentPartUri TODO
     * @param partid the content provider id of the Mime Part.
     * @return
     */
    private byte[] readRawDataPart(Uri contentPartUri, long partid) {
        String uriStr = new String(contentPartUri+"/"+ partid);
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
    private void extractMmsParts(long id, BluetoothMapbMessageMime message)
    {
        /* Handling of filtering out non-text parts for exclude
         * attachments is handled within the bMessage object. */
        final String[] projection = null;
        String selection = new String(Mms.Part.MSG_ID + "=" + id);
        String uriStr = new String(Mms.CONTENT_URI + "/"+ id + "/part");
        Uri uriAddress = Uri.parse(uriStr);
        BluetoothMapbMessageMime.MimePart part;
        Cursor c = mResolver.query(uriAddress, projection, selection, null, null);
        try {
            if (c.moveToFirst()) {
                do {
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
                            part.mData =
                                    readRawDataPart(Uri.parse(Mms.CONTENT_URI+"/part"), partId);
                            if(charset != null) {
                                part.mCharsetName =
                                        CharacterSets.getMimeName(Integer.parseInt(charset));
                            }
                        }
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"extractMmsParts",e);
                        part.mData = null;
                        part.mCharsetName = null;
                    } catch (UnsupportedEncodingException e) {
                        Log.d(TAG,"extractMmsParts",e);
                        part.mData = null;
                        part.mCharsetName = null;
                    } finally {
                    }
                    part.mFileName = filename;
                } while(c.moveToNext());
                message.updateCharset();
            }

        } finally {
            if(c != null) c.close();
        }
    }

    private void setVCardFromEmailAddress(BluetoothMapbMessageExtEmail message, String emailAddr,
        boolean incoming) {
        if(D) Log.d(TAG, "setVCardFromEmailAddress, emailAdress is " + emailAddr);
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

    private void extractEmailAddresses(long id, BluetoothMapbMessageExtEmail message) {
        if (V) Log.v(TAG, "extractEmailAddresses with id " + id);
        StringTokenizer emailId;
        String tempEmail = null;
        Uri contentUri = BluetoothMapEmailContract
            .buildEmailMessageUri(BluetoothMapEmailContract.EMAIL_AUTHORITY);
        Cursor c = mResolver.query(contentUri, BluetoothMapEmailContract.BT_EMAIL_MESSAGE_PROJECTION,
                   "_ID = "+ id, null, null);
        if (c != null && c.moveToFirst()) {
            String senderName = null;
            if((senderName = c.getString(c.getColumnIndex(BluetoothMapEmailContract
                    .ExtEmailMessageColumns.EMAIL_FROM_LIST))) != null ) {
                if(V) Log.v(TAG, " senderName " + senderName);
                if(senderName.contains("")){
                   String[] senderStr = senderName.split("");
                   if(senderStr !=null && senderStr.length > 0){
                      if(V) Log.v(TAG, " senderStr[1] " + senderStr[1].trim());
                      if(V) Log.v(TAG, " senderStr[0] " + senderStr[0].trim());
                      setVCardFromEmailAddress(message, senderStr[1].trim(), true);
                       if(senderStr[0].indexOf('<') != -1 && senderStr[0].indexOf('>') != -1) {
                         if(V) Log.v(TAG, "from addressing is " + senderName.substring(senderStr[0]
                                   .indexOf('<')+1, senderStr[0].lastIndexOf('>')));
                         message.addFrom(null, senderStr[0].substring(senderStr[0].indexOf('<')+1,
                                 senderStr[0].lastIndexOf('>')));
                       } else {
                         message.addFrom(null, senderStr[0].trim());
                      }
                   }
                } else {
                       if(V) Log.v(TAG, " senderStr is" + senderName.trim());
                       setVCardFromEmailAddress(message, senderName.trim(), true);
                       if(senderName.indexOf('<') != -1 && senderName.indexOf('>') != -1) {
                         if(V) Log.v(TAG, "from addressing is " + senderName.substring(senderName
                                  .indexOf('<')+1, senderName.lastIndexOf('>')));
                         message.addFrom(null, senderName.substring(senderName.indexOf('<')+1,
                                 senderName.lastIndexOf('>')));
                       } else{
                         message.addFrom(null, senderName.trim());
                      }
                }
            }
            String recipientName = null;
            String multiRecepients = null;
            if((recipientName = c.getString(c.getColumnIndex(BluetoothMapEmailContract
                    .ExtEmailMessageColumns.EMAIL_TO_LIST))) != null){
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
                } else if(recipientName.contains("")) {
                      multiRecepients = recipientName.replace('', ';');
                      if(multiRecepients != null){
                         if (V){
                             Log.v(TAG, " Setting ::Recepient name :: " + multiRecepients.trim());
                         }
                         emailId = new StringTokenizer(multiRecepients.trim(),";");
                         do {
                            setVCardFromEmailAddress(message, emailId.nextElement().toString(),
                                    false);
                         } while(emailId.hasMoreElements());

                            message.addTo(multiRecepients.trim(), multiRecepients.trim());
                      }
                } else if(recipientName.contains(",")) {
                      multiRecepients = recipientName.replace(", \"", "; \"");
                      multiRecepients = recipientName.replace(", ", "; ");
                      multiRecepients = recipientName.replace(",", ";");
                      if(multiRecepients != null){
                         if (V) {
                             Log.v(TAG, "Setting ::Recepient name :: " + multiRecepients.trim());
                         }
                         emailId = new StringTokenizer(multiRecepients.trim(),";");
                         do {
                            tempEmail = emailId.nextElement().toString();
                            setVCardFromEmailAddress(message, tempEmail, false);

                            if(tempEmail.indexOf('<') != -1) {
                               if(D) Log.d(TAG, "Adding to: " +
                                       tempEmail.substring(tempEmail.indexOf('<')+1,
                                       tempEmail.indexOf('>')));
                               message.addTo(null,tempEmail.substring(tempEmail.indexOf('<')+1,
                                                            tempEmail.indexOf('>')));
                            } else {
                               message.addTo(null, tempEmail);
                            }
                         } while(emailId.hasMoreElements());
                      }
                } else {
                      Log.v(TAG, " Setting ::Recepient name :: " + recipientName.trim());
                      setVCardFromEmailAddress(message, recipientName.trim(), false);
                      if(recipientName.indexOf('<') != -1 && recipientName.indexOf('>') != -1) {
                        if(V) Log.v(TAG, "to addressing is " + recipientName.substring(
                            recipientName.indexOf('<')+1, recipientName.lastIndexOf('>')));
                        message.addTo(null, recipientName.substring(recipientName.indexOf('<')+1,
                                                           recipientName.lastIndexOf('>')));
                      } else {
                      message.addTo(null, recipientName.trim());
                      }
                }
            }
            recipientName = null;
            multiRecepients = null;
            if((recipientName = c.getString(c.getColumnIndex(BluetoothMapEmailContract
                                 .ExtEmailMessageColumns.EMAIL_CC_LIST))) != null) {
                if(V) Log.v(TAG, " recipientName " + recipientName);
                if(recipientName.contains("^B")){
                   String[] recepientStr = recipientName.split("^B");
                   if(recepientStr !=null && recepientStr.length > 0){
                      if (V){
                          Log.v(TAG, " recepientStr[1] " + recepientStr[1].trim());
                          Log.v(TAG, " recepientStr[0] " + recepientStr[0].trim());
                      }
                      setVCardFromEmailAddress(message, recepientStr[1].trim(), false);
                      message.addCc(recepientStr[1].trim(), recepientStr[0].trim());
                   }
                } else if(recipientName.contains("")) {
                      multiRecepients = recipientName.replace('', ';');
                      if(multiRecepients != null){
                         if (V){
                             Log.v(TAG, " Setting ::Recepient name :: " + multiRecepients.trim());
                         }
                         emailId = new StringTokenizer(multiRecepients.trim(),";");
                         do {
                            setVCardFromEmailAddress(message, emailId.nextElement().toString(),
                                    false);
                         } while(emailId.hasMoreElements());

                            message.addCc(multiRecepients.trim(), multiRecepients.trim());
                      }
                } else if(recipientName.contains(",")) {
                      multiRecepients = recipientName.replace(", \"", "; \"");
                      multiRecepients = recipientName.replace(", ", "; ");
                      multiRecepients = recipientName.replace(",", ";");

                      if(multiRecepients != null) {
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
                      if(V) Log.v(TAG, " Setting ::Recepient name :: " + recipientName.trim());
                      setVCardFromEmailAddress(message, recipientName.trim(), false);
                      if(recipientName.indexOf('<') != -1 && recipientName.indexOf('>') != -1) {
                         if(V) Log.v(TAG, "CC addressing is " + recipientName.substring(
                             recipientName.indexOf('<')+1, recipientName.lastIndexOf('>')));
                         message.addCc(null, recipientName.substring(recipientName.indexOf('<')+1,
                                                            recipientName.lastIndexOf('>')));
                      } else {
                         message.addCc(null, recipientName.trim());
                      }
                }
            }
        }
        if(c != null )
            c.close();
    }

    /*
     * Read  email body data under content URI.
     */
    private String readEmailBodyForMessageFd(ParcelFileDescriptor fd) {
        StringBuilder email = new StringBuilder("");
        FileInputStream is = null;
        try {
            int len=-1;
            is = new FileInputStream(fd.getFileDescriptor());
            byte[] buffer = new byte[1024];
            while((len = is.read(buffer)) != -1) {
                email.append(new String(buffer,0,len));
                if(V) Log.v(TAG, "Email part = " + new String(buffer,0,len) + " len=" + len);
            }
        } catch (NullPointerException e) {
            Log.w(TAG, e);
        } catch (IOException e) {
            Log.w(TAG, e);
        } finally {
            try {
                if(is != null)
                    is.close();
                } catch (IOException e) {}
        }
       return email.toString();
    }

    /**
     * Read out the mms parts and update the bMessage object provided i {@linkplain message}
     * @param id the content provider ID of the message
     * @param message the bMessage object to add the information to
     */
    private void extractEmailParts(long id, BluetoothMapbMessageExtEmail message)
    {
        if (V) Log.v(TAG, "extractEmailParts with id " + id);
        String emailBody = "";
        Uri uriAddress = BluetoothMapEmailContract.buildEmailMessageBodyUri(
                BluetoothMapEmailContract.EMAIL_AUTHORITY);
        BluetoothMapbMessageExtEmail.MimePart part;
        Cursor c = null;
        try {
            c = mResolver.query(uriAddress,
                    BluetoothMapEmailContract.BT_EMAIL_BODY_CONTENT_PROJECTION,
                        BluetoothMapEmailContract.EmailBodyColumns.MESSAGE_KEY + "=?",
                    new String[] {String.valueOf(id)}, null);
        } catch (Exception e){

           Log.w(TAG, " EMAIL BODY QUERY FAILDED " + e);
        }
        if(c != null) {
           if (V) Log.v(TAG, "cursor not null");
           if (c.moveToFirst()) {
                String textContentURI = c.getString(c.getColumnIndex(BluetoothMapEmailContract
                                                     .EmailBodyColumns.TEXT_CONTENT_URI));
                String htmlContentURI = c.getString(c.getColumnIndex(BluetoothMapEmailContract
                                         .EmailBodyColumns.HTML_CONTENT_URI));
                if(textContentURI != null || htmlContentURI != null ) {
                    if(V) {
                        Log.v(TAG, " EMAIL BODY textURI " + textContentURI);
                        Log.v(TAG, " EMAIL BODY htmlURI " + htmlContentURI);
                    }
                    // GET FD to parse text or HTML content
                    ParcelFileDescriptor fd = null;
                    if(textContentURI != null ) {
                        try {
                           Log.v(TAG, " TRY EMAIL BODY textURI " + textContentURI);
                           fd = mResolver.openFileDescriptor(Uri.parse(textContentURI), "r");
                        } catch (FileNotFoundException e) {
                           if(V) Log.w(TAG, e);
                        }
                    }
                    if(fd == null ) {
                        if(htmlContentURI != null ) {
                            //Try HTML content if  TEXT CONTENT NULL
                            try {
                                Log.v(TAG, " TRY EMAIL BODY htmlURI " + htmlContentURI);
                                fd = mResolver.openFileDescriptor(Uri.parse(htmlContentURI), "r");
                                } catch (FileNotFoundException e) {
                                if(V) Log.w(TAG, e);
                            } catch (NullPointerException e) {
                                if(V) Log.w(TAG, e);
                            }
                            String msgBody = null ;
                            if(fd != null ) {
                               msgBody = readEmailBodyForMessageFd(fd);
                            } else {
                               Log.w(TAG, " FETCH Email BODY File HTML URI FAILED");
                            }
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
                        } else {
                           Log.w(TAG, " FETCH Email BODY File HTML URI FAILED");
                        }
                    } else {
                        emailBody = readEmailBodyForMessageFd(fd);
                    }
                    //Set BMessage emailBody
                    message.setEmailBody(emailBody);
                    //Parts
                    Long partId = c.getLong(c.getColumnIndex(BaseColumns._ID));
                    String contentType = " text/plain; charset=\"UTF-8\"";
                    String name = null;//c.getString(c.getColumnIndex("displayName"));
                    String text = null;

                    if(D) Log.d(TAG, "     _id : " + partId +
                       "\n     ct : " + contentType +
                       "\n     partname : " + name);

                    part = message.addMimePart();
                    part.mContentType = contentType;
                    part.mPartName = name;

                    try {
                        if(emailBody != null) {
                            part.mData = emailBody.getBytes("UTF-8");
                            part.mCharsetName = "utf-8";
                        }
                    } catch (NumberFormatException e) {
                        Log.d(TAG,"extractEmailParts",e);
                        part.mData = null;
                        part.mCharsetName = null;
                    } catch (UnsupportedEncodingException e) {
                        Log.d(TAG,"extractEmailParts",e);
                        part.mData = null;
                        part.mCharsetName = null;
                    } finally {
                    }
                    try {
                       if(fd != null)
                         fd.close();
                    } catch (IOException e) {}
                } else {
                    Log.w(TAG, " FETCH Email BODY File URI FAILED");
                }
           }
           c.close();
        }
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

       BluetoothMapbMessageExtEmail message = new BluetoothMapbMessageExtEmail();
       Uri contentUri = BluetoothMapEmailContract.buildEmailMessageUri(BluetoothMapEmailContract
                                .EMAIL_AUTHORITY);
       Cursor c = mResolver.query(contentUri, BluetoothMapEmailContract.BT_EMAIL_MESSAGE_PROJECTION,
              "_ID = " + id, null, null);
       try {
           if(c != null && c.moveToFirst())
           {
               BluetoothMapFolderElement folderElement;
               FileInputStream is = null;
               ParcelFileDescriptor fd = null;
               try {
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
                       if (c.getInt(c.getColumnIndex(
                               BluetoothMapEmailContract.ExtEmailMessageColumns.FLAG_LOADED)) ==
                                   BluetoothMapEmailContract.FLAG_LOADED_COMPLETE)  {
                           // TODO: request message from server
                           Log.w(TAG, "getEmailMessage - receptionState not COMPLETE -  Not Implemented!" );
                       }
                   }
                   // Set read status:
                   int read = c.getInt(c.getColumnIndex(BluetoothMapEmailContract
                           .ExtEmailMessageColumns.EMAIL_FLAG_READ));
                   if (read == 1)
                       message.setStatus(true);
                   else
                       message.setStatus(false);

                   // Set message type:
                   message.setType(TYPE.EMAIL);
                   message.setVersionString(mMessageVersion);
                   message.setContentType(" text/plain; charset=\"UTF-8\"");
                   message.setDate(c.getLong(c.getColumnIndex(BluetoothMapEmailContract
                       .ExtEmailMessageColumns.TIMESTAMP)));
                   message.setSubject(c.getString(c.getColumnIndex(BluetoothMapContract
                       .MessageColumns.SUBJECT)));
                   // Set folder:
                   long folderId = c.getLong( c.getColumnIndex(BluetoothMapEmailContract
                           .ExtEmailMessageColumns.MAILBOX_KEY));
                   folderElement = currentFolder.getFolderById(folderId);
                   message.setCompleteFolder(folderElement.getFullPath());

                   // Set recipient:
                   String nameEmail = c.getString(c.getColumnIndex(BluetoothMapEmailContract
                           .ExtEmailMessageColumns.EMAIL_TO_LIST));
                   Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(nameEmail);
                   if (tokens.length != 0) {
                       if(D) Log.d(TAG, "Recipient count= " + tokens.length);
                       int i = 0;
                       while (i < tokens.length) {
                           if(V) Log.d(TAG, "Recipient = " + tokens[i].toString());
                           String[] emails = new String[1];
                           emails[0] = tokens[i].getAddress();
                           String name = tokens[i].getName();
                           message.addRecipient(name, name, null, emails, null, null);
                           i++;
                       }
                   }

                   // Set originator:
                   nameEmail = c.getString(c.getColumnIndex(BluetoothMapEmailContract
                          .ExtEmailMessageColumns.EMAIL_FROM_LIST));
                   tokens = Rfc822Tokenizer.tokenize(nameEmail);
                   if (tokens.length != 0) {
                       if(D) Log.d(TAG, "Originator count= " + tokens.length);
                       int i = 0;
                       while (i < tokens.length) {
                           if(V) Log.d(TAG, "Originator = " + tokens[i].toString());
                           String[] emails = new String[1];
                           emails[0] = tokens[i].getAddress();
                           String name = tokens[i].getName();
                           message.addOriginator(name, name, null, emails, null, null);
                           i++;
                       }
                   }
               } finally {
                   if(c != null) c.close();
               }
               // Find out if we get attachments
               /* TODO: Attachment yet to be supported: Needs fetch from Attachment content Uri.
                        Hence, mark attachment support false always for now.
               // String attStr = (appParams.getAttachment() == 0) ?
               //     "/" +  BluetoothMapContract.FILE_MSG_NO_ATTACHMENTS : "";
               */
               message.setIncludeAttachments(EMAIL_ATTACHMENT_IMPLEMENTED);

               // The parts
               extractEmailParts(id, message);

               // The addresses
               extractEmailAddresses(id, message);
               return message.encodeEmail();
           }
       } finally {
           if (c != null) c.close();
       }
       throw new IllegalArgumentException("EMAIL handle not found");
   }

}
