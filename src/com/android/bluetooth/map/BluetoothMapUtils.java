/*
* Copyright (C) 2013 Samsung System LSI
* Copyright (C) 2013, The Linux Foundation. All rights reserved.
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
import android.util.Log;
import android.net.Uri;
import java.util.List;
import java.util.ArrayList;
import java.util.*;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.sqlite.SQLiteException;
import com.android.emailcommon.provider.Mailbox;

/**
 * Various utility methods and generic defines that can be used throughout MAPS
 */
public class BluetoothMapUtils {

    private static final String TAG = "MapUtils";
    private static final boolean V = BluetoothMapService.VERBOSE;
    /* We use the upper 5 bits for the type mask - avoid using the top bit, since it
     * indicates a negative value, hence corrupting the formatter when converting to
     * type String. (I really miss the unsigned type in Java:))
     */
    private static final long HANDLE_TYPE_MASK            = 0xf<<59;
    private static final long HANDLE_TYPE_MMS_MASK        = 0x1<<59;
    private static final long HANDLE_TYPE_EMAIL_MASK      = 0x2<<59;
    private static final long HANDLE_TYPE_SMS_GSM_MASK    = 0x4<<59;
    private static final long HANDLE_TYPE_SMS_CDMA_MASK   = 0x8<<59;
    public static final String AUTHORITY = "com.android.email.provider";
    public static final Uri EMAIL_URI = Uri.parse("content://" + AUTHORITY);
    public static final Uri EMAIL_ACCOUNT_URI = Uri.withAppendedPath(EMAIL_URI, "account");
    public static final String RECORD_ID = "_id";
    public static final String DISPLAY_NAME = "displayName";
    public static final String EMAIL_ADDRESS = "emailAddress";
    public static final String ACCOUNT_KEY = "accountKey";
    public static final String IS_DEFAULT = "isDefault";
    public static final String EMAIL_TYPE = "type";
    public static final String[] EMAIL_BOX_PROJECTION = new String[] {
        RECORD_ID, DISPLAY_NAME, ACCOUNT_KEY, EMAIL_TYPE };
    private static Context mContext;
    private static ContentResolver mResolver;
    private static final String[] ACCOUNT_ID_PROJECTION = new String[] {
                         RECORD_ID, EMAIL_ADDRESS, IS_DEFAULT
    };

    /**
     * This enum is used to convert from the bMessage type property to a type safe
     * type. Hence do not change the names of the enum values.
     */
    public enum TYPE{
        EMAIL,
        SMS_GSM,
        SMS_CDMA,
        MMS
    }

    /**
     * Convert a Content Provider handle and a Messagetype into a unique handle
     * @param cpHandle content provider handle
     * @param messageType message type (TYPE_MMS/TYPE_SMS_GSM/TYPE_SMS_CDMA/TYPE_EMAIL)
     * @return String Formatted Map Handle
     */
    static public String getMapHandle(long cpHandle, TYPE messageType){
        String mapHandle = "-1";
        switch(messageType)
        {
            case MMS:
                mapHandle = String.format("%016X",(cpHandle | HANDLE_TYPE_MMS_MASK));
                break;
            case SMS_GSM:
                mapHandle = String.format("%016X",cpHandle | HANDLE_TYPE_SMS_GSM_MASK);
                break;
            case SMS_CDMA:
                mapHandle = String.format("%016X",cpHandle | HANDLE_TYPE_SMS_CDMA_MASK);
                break;
            case EMAIL:
                mapHandle = String.format("%016X",(cpHandle | HANDLE_TYPE_EMAIL_MASK)); //TODO correct when email support is implemented
                break;
                default:
                    throw new IllegalArgumentException("Message type not supported");
        }
        return mapHandle;

    }
    public static int getSystemMailboxGuessType(String folderName) {

        if(folderName.equalsIgnoreCase("outbox")){
           return Mailbox.TYPE_OUTBOX;
        } else if(folderName.equalsIgnoreCase("inbox")){
           return Mailbox.TYPE_INBOX;
        } else if(folderName.equalsIgnoreCase("drafts")){
           return Mailbox.TYPE_DRAFTS;
        } else if(folderName.equalsIgnoreCase("Trash")){
           return Mailbox.TYPE_TRASH;
        } else if(folderName.equalsIgnoreCase("Sent")){
           return Mailbox.TYPE_SENT;
        } else if(folderName.equalsIgnoreCase("Junk")){
           return Mailbox.TYPE_JUNK;
        } else if(folderName.equalsIgnoreCase("Sent")){
           return Mailbox.TYPE_STARRED;
        } else if(folderName.equalsIgnoreCase("Unread")){
           return Mailbox.TYPE_UNREAD;
        }
        //UNKNOWN
        return -1;
      }
    /**
     * Get Account id for Default Email app
     * @return the Account id value
     */
    static public long getEmailAccountId(Context context) {
        if (V) Log.v(TAG, "getEmailAccountIdList()");
        long id = -1;
        ArrayList<Long> list = new ArrayList<Long>();
        Context mContext = context;
        mResolver = mContext.getContentResolver();
        try {
            Cursor cursor = mResolver.query(EMAIL_ACCOUNT_URI,
                   ACCOUNT_ID_PROJECTION, null, null, null);
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    id = cursor.getLong(0);
                    if (V) Log.v(TAG, "id = " + id);
                }
                cursor.close();
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "SQLite exception: " + e);
        }
        return id;
    }

    /**
     * Convert a handle string the the raw long representation, including the type bit.
     * @param mapHandle the handle string
     * @return the handle value
     */
    static public long getMsgHandleAsLong(String mapHandle){
        return Long.parseLong(mapHandle, 16);
    }
    /**
     * Convert a Map Handle into a content provider Handle
     * @param mapHandle handle to convert from
     * @return content provider handle without message type mask
     */
    static public long getCpHandle(String mapHandle)
    {
        long cpHandle = getMsgHandleAsLong(mapHandle);
        /* remove masks as the call should already know what type of message this handle is for */
        cpHandle &= ~HANDLE_TYPE_MASK;
        return cpHandle;
    }

    /**
     * Extract the message type from the handle.
     * @param mapHandle
     * @return
     */
    static public TYPE getMsgTypeFromHandle(String mapHandle) {
        long cpHandle = getMsgHandleAsLong(mapHandle);

        if((cpHandle & HANDLE_TYPE_MMS_MASK) != 0)
            return TYPE.MMS;
        if((cpHandle & HANDLE_TYPE_EMAIL_MASK) != 0)
            return TYPE.EMAIL;
        if((cpHandle & HANDLE_TYPE_SMS_GSM_MASK) != 0)
            return TYPE.SMS_GSM;
        if((cpHandle & HANDLE_TYPE_SMS_CDMA_MASK) != 0)
            return TYPE.SMS_CDMA;

        throw new IllegalArgumentException("Message type not found in handle string.");
    }
}

