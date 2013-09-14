package com.android.bluetooth.tests;

import android.test.AndroidTestCase;
import android.util.Log;
import android.database.Cursor;
import android.content.Context;
import android.content.ContentResolver;

import java.text.SimpleDateFormat;
import java.util.Date;

import com.android.bluetooth.map.BluetoothMapContent;
import com.android.bluetooth.map.BluetoothMapContentObserver;

import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;

public class BluetoothMapContentTest extends AndroidTestCase {
    private static final String TAG = "BluetoothMapContentTest";

    private static final boolean D = true;
    private static final boolean V = true;

    private Context mContext;
    private ContentResolver mResolver;

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

    private String getDateTimeString(long timestamp) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss");
        Date date = new Date(timestamp);
        return format.format(date); // Format to YYYYMMDDTHHMMSS local time
    }

    private void printEmail(Cursor c) {
        if (D) Log.d(TAG, "printEmail " +
            c.getLong(c.getColumnIndex(EmailContent.RECORD_ID)) +
            "\n   " + MessageColumns.DISPLAY_NAME + " : " + c.getString(c.getColumnIndex(MessageColumns.DISPLAY_NAME)) +
            "\n   " + MessageColumns.TIMESTAMP + " : " + getDateTimeString(c.getLong(c.getColumnIndex(MessageColumns.TIMESTAMP))) +
            "\n   " + MessageColumns.SUBJECT + " : " + c.getString(c.getColumnIndex(MessageColumns.SUBJECT)) +
            "\n   " + MessageColumns.FLAG_READ + " : " + c.getString(c.getColumnIndex(MessageColumns.FLAG_READ)) +
            "\n   " + MessageColumns.FLAG_ATTACHMENT + " : " + c.getInt(c.getColumnIndex(MessageColumns.FLAG_ATTACHMENT)) +
            "\n   " + MessageColumns.FLAGS + " : " + c.getInt(c.getColumnIndex(MessageColumns.FLAGS)) +
            "\n   " + SyncColumns.SERVER_ID + " : " + c.getInt(c.getColumnIndex(SyncColumns.SERVER_ID)) +
            "\n   " + MessageColumns.DRAFT_INFO + " : " + c.getInt(c.getColumnIndex(MessageColumns.DRAFT_INFO)) +
            "\n   " + MessageColumns.MESSAGE_ID + " : " + c.getInt(c.getColumnIndex(MessageColumns.MESSAGE_ID)) +
            "\n   " + MessageColumns.MAILBOX_KEY + " : " + c.getInt(c.getColumnIndex(MessageColumns.MAILBOX_KEY)) +
            "\n   " + MessageColumns.ACCOUNT_KEY + " : " + c.getInt(c.getColumnIndex(MessageColumns.ACCOUNT_KEY)) +
            "\n   " + MessageColumns.FROM_LIST + " : " + c.getString(c.getColumnIndex(MessageColumns.FROM_LIST)) +
            "\n   " + MessageColumns.TO_LIST + " : " + c.getString(c.getColumnIndex(MessageColumns.TO_LIST)) +
            "\n   " + MessageColumns.CC_LIST + " : " + c.getString(c.getColumnIndex(MessageColumns.CC_LIST)) +
            "\n   " + MessageColumns.BCC_LIST + " : " + c.getString(c.getColumnIndex(MessageColumns.BCC_LIST)) +
            "\n   " + MessageColumns.REPLY_TO_LIST + " : " + c.getString(c.getColumnIndex(MessageColumns.REPLY_TO_LIST)) +
            "\n   " + SyncColumns.SERVER_TIMESTAMP + " : " + getDateTimeString(c.getLong(c.getColumnIndex(SyncColumns.SERVER_TIMESTAMP))) +
            "\n   " + MessageColumns.MEETING_INFO + " : " + c.getString(c.getColumnIndex(MessageColumns.MEETING_INFO)) +
            "\n   " + MessageColumns.SNIPPET + " : " + c.getString(c.getColumnIndex(MessageColumns.SNIPPET)) +
            "\n   " + MessageColumns.PROTOCOL_SEARCH_INFO + " : " + c.getString(c.getColumnIndex(MessageColumns.PROTOCOL_SEARCH_INFO)) +
            "\n   " + MessageColumns.THREAD_TOPIC + " : " + c.getString(c.getColumnIndex(MessageColumns.THREAD_TOPIC)));
    }

    public void dumpEmailMessageTable() {
        Log.d(TAG, "**** Dump of email message table ****");

        Cursor c = mResolver.query(Message.CONTENT_URI,
            EMAIL_PROJECTION, null, null, "_id DESC");
        if (c != null) {
            Log.d(TAG, "c.getCount() = " + c.getCount());
            c.moveToPosition(-1);
            while (c.moveToNext()) {
                printEmail(c);
            }
        } else {
            Log.d(TAG, "query failed");
            c.close();
        }
    }

    public BluetoothMapContentTest() {
        super();
    }

    public void testDumpMessages() {
        mContext = this.getContext();
        mResolver = mContext.getContentResolver();
        dumpEmailMessageTable();
    }
}
