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

import java.util.List;
import java.util.Set;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.text.format.Time;
import android.util.Log;
import android.util.TimeFormatException;

import com.android.bluetooth.map.BluetoothMasAppParams;



public class SmsMmsUtils {

        public final String TAG = "SmsMmsUtils";
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

        private final String Inbox = "inbox";
    private final String Outbox = "outbox";
    private final String Sent = "sent";
    private final String Deleted = "deleted";
    private final String Draft = "draft";
    private final String Drafts = "drafts";
    private final String Undelivered = "undelivered";
    private final String Failed = "failed";
    private final String Queued = "queued";

    private final int DELETED_THREAD_ID = -1;

    static final int PHONELOOKUP_ID_COLUMN_INDEX = 0;
    static final int PHONELOOKUP_LOOKUP_KEY_COLUMN_INDEX = 1;
    static final int PHONELOOKUP_DISPLAY_NAME_COLUMN_INDEX = 2;

    static final int EMAIL_DATA_COLUMN_INDEX = 0;

    private List<VcardContent> list;

    private class VcardContent {
        public String name = "";
        public String tel = "";
        public String email = "";
    }

    public List<String> folderListSmsMms(List<String> folderList) {
        folderList.add(Inbox);
        folderList.add(Outbox);
        folderList.add(Sent);
        folderList.add(Deleted);
        folderList.add(Draft);

        return folderList;

    }
    public String getWhereIsQueryForType(String folder) {

        String query = null;

        if (folder.equalsIgnoreCase(Inbox)) {
            query = "type = 1 AND thread_id <> " + DELETED_THREAD_ID;
        }
        else if (folder.equalsIgnoreCase(Outbox)) {
            query = "(type = 4 OR type = 5 OR type = 6) AND thread_id <> " + DELETED_THREAD_ID;
        }
        else if (folder.equalsIgnoreCase(Sent)) {
            query = "type = 2 AND thread_id <> " + DELETED_THREAD_ID;
        }
        else if (folder.equalsIgnoreCase(Draft)) {
            query = "type = 3 AND thread_id <> " + DELETED_THREAD_ID;
        }
        else if (folder.equalsIgnoreCase(Deleted)) {
            query = "thread_id = " + DELETED_THREAD_ID;
        }
        else{
                query = "type = -1";
        }
        return query;

    }
    public String getConditionStringSms(String folderName, BluetoothMasAppParams appParams) {
         String whereClause = getWhereIsQueryForType(folderName);

         /* Filter readstatus: 0 no filtering, 0x01 get unread, 0x10 get read */
         if (appParams.FilterReadStatus != 0) {
             if ((appParams.FilterReadStatus & 0x1) != 0) {
                 if (whereClause != "") {
                     whereClause += " AND ";
                 }
                 whereClause += " read=0 ";
             }
             if ((appParams.FilterReadStatus & 0x02) != 0) {
                 if (whereClause != "") {
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
                 time.parse(appParams.FilterPeriodBegin);
                 if (whereClause != "") {
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
                 time.parse(appParams.FilterPeriodEnd);
                 if (whereClause != "") {
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

}
