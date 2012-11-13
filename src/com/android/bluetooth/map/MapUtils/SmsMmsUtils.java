/*
 * Copyright (c) 2010-2011, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *          notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *          notice, this list of conditions and the following disclaimer in the
 *          documentation and/or other materials provided with the distribution.
 *        * Neither the name of Code Aurora nor
 *          the names of its contributors may be used to endorse or promote
 *          products derived from this software without specific prior written
 *          permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.    IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.map.MapUtils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.format.Time;
import android.util.Log;
import android.util.TimeFormatException;

import com.android.bluetooth.map.BluetoothMasAppParams;

import java.util.ArrayList;

public class SmsMmsUtils {
    public static final String TAG = "SmsMmsUtils";
    public static final int BIT_SUBJECT = 0x1;
    public static final int BIT_DATETIME = 0x2;
    public static final int BIT_SENDER_NAME = 0x4;
    public static final int BIT_SENDER_ADDRESSING = 0x8;

    public static final int BIT_RECIPIENT_NAME = 0x10;
    public static final int BIT_RECIPIENT_ADDRESSING = 0x20;
    public static final int BIT_TYPE = 0x40;
    public static final int BIT_SIZE = 0x80;

    public static final int BIT_RECEPTION_STATUS = 0x100;
    public static final int BIT_TEXT = 0x200;
    public static final int BIT_ATTACHMENT_SIZE = 0x400;
    public static final int BIT_PRIORITY = 0x800;

    public static final int BIT_READ = 0x1000;
    public static final int BIT_SENT = 0x2000;
    public static final int BIT_PROTECTED = 0x4000;
    public static final int BIT_REPLYTO_ADDRESSING = 0x8000;

    public static final String INBOX = "inbox";
    public static final String OUTBOX = "outbox";
    public static final String SENT = "sent";
    public static final String DELETED = "deleted";
    public static final String DRAFT = "draft";
    public static final String DRAFTS = "drafts";
    public static final String UNDELIVERED = "undelivered";
    public static final String FAILED = "failed";
    public static final String QUEUED = "queued";

    public static final int DELETED_THREAD_ID = -1;

    static final int PHONELOOKUP_ID_COLUMN_INDEX = 0;
    static final int PHONELOOKUP_LOOKUP_KEY_COLUMN_INDEX = 1;
    static final int PHONELOOKUP_DISPLAY_NAME_COLUMN_INDEX = 2;

    static final int EMAIL_DATA_COLUMN_INDEX = 0;

    public static class VcardContent {
        public String name = "";
        public String tel = "";
        public String email = "";
    }

    public static final ArrayList<String> FORLDER_LIST_SMS_MMS;
    public static final ArrayList<String> FORLDER_LIST_SMS_MMS_MNS;

    static {
        FORLDER_LIST_SMS_MMS = new ArrayList<String>();
        FORLDER_LIST_SMS_MMS.add(INBOX);
        FORLDER_LIST_SMS_MMS.add(OUTBOX);
        FORLDER_LIST_SMS_MMS.add(SENT);
        FORLDER_LIST_SMS_MMS.add(DELETED);
        FORLDER_LIST_SMS_MMS.add(DRAFT);

        FORLDER_LIST_SMS_MMS_MNS = new ArrayList<String>();
        FORLDER_LIST_SMS_MMS_MNS.add(INBOX);
        FORLDER_LIST_SMS_MMS_MNS.add(OUTBOX);
        FORLDER_LIST_SMS_MMS_MNS.add(SENT);
        FORLDER_LIST_SMS_MMS_MNS.add(DRAFT);
        FORLDER_LIST_SMS_MMS_MNS.add(FAILED);
        FORLDER_LIST_SMS_MMS_MNS.add(QUEUED);
    }

    public static int getFolderTypeMms(String folder) {
        int folderType = -5 ;

        if (INBOX.equalsIgnoreCase(folder)) {
            folderType = 1;
        }
        else if (OUTBOX.equalsIgnoreCase(folder)) {
            folderType = 4;
        }
        else if (SENT.equalsIgnoreCase(folder)) {
            folderType = 2;
        }
        else if (DRAFT.equalsIgnoreCase(folder) || DRAFTS.equalsIgnoreCase(folder)) {
            folderType = 3;
        }
        else if (DELETED.equalsIgnoreCase(folder)) {
            folderType = -1;
        }
        return folderType;
    }

    public static String getWhereIsQueryForType(String folder) {
        String query = null;

        if (INBOX.equalsIgnoreCase(folder)) {
            query = "type = 1 AND thread_id <> " + DELETED_THREAD_ID;
        }
        else if (OUTBOX.equalsIgnoreCase(folder)) {
            query = "(type = 4 OR type = 5 OR type = 6) AND thread_id <> " + DELETED_THREAD_ID;
        }
        else if (SENT.equalsIgnoreCase(folder)) {
            query = "type = 2 AND thread_id <> " + DELETED_THREAD_ID;
        }
        else if (DRAFT.equalsIgnoreCase(folder)) {
            query = "type = 3 AND thread_id <> " + DELETED_THREAD_ID;
        }
        else if (DELETED.equalsIgnoreCase(folder)) {
            query = "thread_id = " + DELETED_THREAD_ID;
        }
        else{
            query = "type = -1";
        }
        return query;
    }

    public static String getConditionStringSms(String folderName, BluetoothMasAppParams appParams) {
        String whereClause = getWhereIsQueryForType(folderName);

         /* Filter readstatus: 0 no filtering, 0x01 get unread, 0x10 get read */
        if (appParams.FilterReadStatus != 0) {
             if ((appParams.FilterReadStatus & 0x1) != 0) {
                 if (whereClause.length() != 0) {
                     whereClause += " AND ";
                 }
                 whereClause += " read=0 ";
             }
             if ((appParams.FilterReadStatus & 0x02) != 0) {
                 if (whereClause.length() != 0) {
                     whereClause += " AND ";
                 }
                 whereClause += " read=1 ";
             }
         }
         // TODO Filter priority?

         /* Filter Period Begin */
         if ((appParams.FilterPeriodBegin != null)
                 && (appParams.FilterPeriodBegin.length() > 0)) {
             Time time = new Time();
             try {
                 time.parse(appParams.FilterPeriodBegin.trim());
                 if (whereClause.length() != 0) {
                     whereClause += " AND ";
                 }
                 whereClause += "date >= " + time.toMillis(false);
             } catch (TimeFormatException e) {
                 Log.d(TAG, "Bad formatted FilterPeriodBegin "
                         + appParams.FilterPeriodBegin);
             }
         }

         /* Filter Period End */
         if ((appParams.FilterPeriodEnd != null)
                 && (appParams.FilterPeriodEnd.length() > 0 )) {
             Time time = new Time();
             try {
                 time.parse(appParams.FilterPeriodEnd.trim());
                 if (whereClause.length() != 0) {
                     whereClause += " AND ";
                 }
                 whereClause += "date < " + time.toMillis(false);
             } catch (TimeFormatException e) {
                 Log.d(TAG, "Bad formatted FilterPeriodEnd "
                         + appParams.FilterPeriodEnd);
             }
         }
         return whereClause;
    }

    public static final Uri SMS_URI = Uri.parse("content://sms");
    public static final Uri MMS_URI = Uri.parse("content://mms");
    public static final String[] THREAD_ID_COLUMN = new String[]{"thread_id"};

    /**
     * Obtain the number of MMS messages
     */
    public static int getNumMmsMsgs(Context context, String name) {
        int msgCount = 0;

        if (DELETED.equalsIgnoreCase(name)) {
            Uri uri = Uri.parse("content://mms/");
            ContentResolver cr = context.getContentResolver();
            Cursor cursor = cr.query(uri, null, "thread_id = " + DELETED_THREAD_ID, null, null);
            if(cursor != null){
                msgCount = cursor.getCount();
                cursor.close();
            }
        } else {
            Uri uri = Uri.parse("content://mms/" + name);
            ContentResolver cr = context.getContentResolver();
            Cursor cursor = cr.query(uri, null, "thread_id <> " + DELETED_THREAD_ID, null, null);
            if(cursor != null){
                msgCount = cursor.getCount();
                cursor.close();
            }
        }
        return msgCount;
    }
}
