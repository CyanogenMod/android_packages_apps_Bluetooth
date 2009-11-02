/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.opp;

import com.android.bluetooth.R;

import android.content.Context;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.widget.RemoteViews;
import android.os.Process;
import java.util.HashMap;

/**
 * This class handles the updating of the Notification Manager for the cases
 * where there is an ongoing transfer, incoming transfer need confirm and
 * complete (successful or failed) transfer.
 */
class BluetoothOppNotification {
    private static final String TAG = "BluetoothOppNotification";
    private static final boolean D = Constants.DEBUG;
    private static final boolean V = Constants.VERBOSE;

    static final String status = "(" + BluetoothShare.STATUS + " == '192'" + ")";

    static final String visible = "(" + BluetoothShare.VISIBILITY + " IS NULL OR "
            + BluetoothShare.VISIBILITY + " == '" + BluetoothShare.VISIBILITY_VISIBLE + "'" + ")";

    static final String confirm = "(" + BluetoothShare.USER_CONFIRMATION + " == '"
            + BluetoothShare.USER_CONFIRMATION_CONFIRMED + "' OR "
            + BluetoothShare.USER_CONFIRMATION + " == '"
            + BluetoothShare.USER_CONFIRMATION_AUTO_CONFIRMED + "'" + ")";

    static final String WHERE_RUNNING = status + " AND " + visible + " AND " + confirm;

    static final String WHERE_COMPLETED = BluetoothShare.STATUS + " >= '200' AND " + visible;

    static final String WHERE_CONFIRM_PENDING = BluetoothShare.USER_CONFIRMATION + " == '"
            + BluetoothShare.USER_CONFIRMATION_PENDING + "'" + " AND " + visible;

    public NotificationManager mNotificationMgr;

    private Context mContext;

    private HashMap<String, NotificationItem> mNotifications;

    private NotificationUpdateThread mUpdateNotificationThread;

    private boolean mPendingUpdate = false;

    private boolean mFinised = false;

    /**
     * This inner class is used to describe some properties for one transfer.
     */
    static class NotificationItem {
        int id; // This first field _id in db;

        int direction; // to indicate sending or receiving

        int totalCurrent = 0; // current transfer bytes

        int totalTotal = 0; // total bytes for current transfer

        String description; // the text above progress bar
    }

    /**
     * Constructor
     *
     * @param ctx The context to use to obtain access to the Notification
     *            Service
     */
    BluetoothOppNotification(Context ctx) {
        mContext = ctx;
        mNotificationMgr = (NotificationManager)mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        mNotifications = new HashMap<String, NotificationItem>();
    }

    public void finishNotification() {
        synchronized (this) {
            mFinised = true;
        }
    }

    /**
     * Update the notification ui.
     */
    public void updateNotification() {
        synchronized (this) {
            mPendingUpdate = true;
            if (mUpdateNotificationThread == null) {
                mUpdateNotificationThread = new NotificationUpdateThread();
                mUpdateNotificationThread.start();
                mFinised = false;
            }
        }
    }

    private class NotificationUpdateThread extends Thread {

        public NotificationUpdateThread() {
            super("Notification Update Thread");
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            for (;;) {
                synchronized (this) {
                    if (mUpdateNotificationThread != this) {
                        throw new IllegalStateException(
                                "multiple UpdateThreads in BluetoothOppNotification");
                    }
                    if (!mPendingUpdate && mFinised) {
                        mUpdateNotificationThread = null;
                        return;
                    }
                    mPendingUpdate = false;
                }
                updateActiveNotification();
                updateCompletedNotification();
                updateIncomingFileConfirmNotification();
            }
        }
    }

    private void updateActiveNotification() {
        // Active transfers
        Cursor cursor = mContext.getContentResolver().query(BluetoothShare.CONTENT_URI, null,
                WHERE_RUNNING, null, BluetoothShare._ID);
        if (cursor == null) {
            return;
        }

        // Collate the notifications
        mNotifications.clear();
        for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
            int timeStamp = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.TIMESTAMP));
            int dir = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.DIRECTION));
            int id = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare._ID));
            int total = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.TOTAL_BYTES));
            int current = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.CURRENT_BYTES));

            String fileName = cursor.getString(cursor.getColumnIndexOrThrow(BluetoothShare._DATA));
            if (fileName == null) {
                fileName = cursor.getString(cursor
                        .getColumnIndexOrThrow(BluetoothShare.FILENAME_HINT));
            }
            if (fileName == null) {
                fileName = mContext.getString(R.string.unknown_file);
            }

            String batchID = Long.toString(timeStamp);

            // sending objects in one batch has same timeStamp
            if (mNotifications.containsKey(batchID)) {
                // NOTE: currently no such case
                // Batch sending case
            } else {
                NotificationItem item = new NotificationItem();
                item.id = id;
                item.direction = dir;
                if (item.direction == BluetoothShare.DIRECTION_OUTBOUND) {
                    item.description = mContext.getString(R.string.notification_sending, fileName);
                } else if (item.direction == BluetoothShare.DIRECTION_INBOUND) {
                    item.description = mContext
                            .getString(R.string.notification_receiving, fileName);
                } else {
                    if (V) Log.v(TAG, "mDirection ERROR!");
                }
                item.totalCurrent = current;
                item.totalTotal = total;

                mNotifications.put(batchID, item);

                if (V) Log.v(TAG, "ID=" + item.id + "; batchID=" + batchID + "; totoalCurrent"
                            + item.totalCurrent + "; totalTotal=" + item.totalTotal);
            }
        }
        cursor.close();

        // Add the notifications
        for (NotificationItem item : mNotifications.values()) {
            // Build the RemoteView object
            RemoteViews expandedView = new RemoteViews(Constants.THIS_PACKAGE_NAME,
                    R.layout.status_bar_ongoing_event_progress_bar);

            expandedView.setTextViewText(R.id.description, item.description);

            expandedView.setProgressBar(R.id.progress_bar, item.totalTotal, item.totalCurrent,
                    item.totalTotal == -1);

            expandedView.setTextViewText(R.id.progress_text, BluetoothOppUtility
                    .formatProgressText(item.totalTotal, item.totalCurrent));

            // Build the notification object
            Notification n = new Notification();
            if (item.direction == BluetoothShare.DIRECTION_OUTBOUND) {
                n.icon = android.R.drawable.stat_sys_upload;
                expandedView.setImageViewResource(R.id.appIcon, android.R.drawable.stat_sys_upload);
            } else if (item.direction == BluetoothShare.DIRECTION_INBOUND) {
                n.icon = android.R.drawable.stat_sys_download;
                expandedView.setImageViewResource(R.id.appIcon,
                        android.R.drawable.stat_sys_download);
            } else {
                if (V) Log.v(TAG, "mDirection ERROR!");
            }

            n.flags |= Notification.FLAG_ONGOING_EVENT;
            n.contentView = expandedView;

            Intent intent = new Intent(Constants.ACTION_LIST);
            intent.setClassName(Constants.THIS_PACKAGE_NAME, BluetoothOppReceiver.class.getName());
            intent.setData(Uri.parse(BluetoothShare.CONTENT_URI + "/" + item.id));

            n.contentIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
            mNotificationMgr.notify(item.id, n);
        }
    }

    private void updateCompletedNotification() {
        Cursor cursor = mContext.getContentResolver().query(BluetoothShare.CONTENT_URI, null,
                WHERE_COMPLETED, null, BluetoothShare._ID);
        if (cursor == null) {
            return;
        }

        for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
            // Add the notifications
            long timeStamp = cursor.getLong(cursor.getColumnIndexOrThrow(BluetoothShare.TIMESTAMP));
            int dir = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.DIRECTION));
            int id = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare._ID));
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.STATUS));

            String fileName = cursor.getString(cursor
                    .getColumnIndexOrThrow(BluetoothShare.FILENAME_HINT));
            if (fileName == null) {
                fileName = mContext.getString(R.string.unknown_file);
            }

            String title;
            String caption;
            Uri contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + id);

            Notification n = new Notification();
            if (BluetoothShare.isStatusError(status)) {
                if (dir == BluetoothShare.DIRECTION_OUTBOUND) {
                    title = mContext.getString(R.string.notification_sent_fail, fileName);
                } else {
                    title = mContext.getString(R.string.notification_received_fail, fileName);
                }
                caption = mContext.getString(R.string.download_fail_line3, BluetoothOppUtility
                        .getStatusDescription(mContext, status));
                n.icon = android.R.drawable.stat_notify_error;
            } else {
                if (dir == BluetoothShare.DIRECTION_OUTBOUND) {
                    title = mContext.getString(R.string.notification_sent, fileName);
                    n.icon = android.R.drawable.stat_sys_upload_done;
                } else {
                    title = mContext.getString(R.string.notification_received, fileName);
                    n.icon = android.R.drawable.stat_sys_download_done;
                }
                caption = mContext.getString(R.string.notification_sent_complete);
            }
            Intent intent = new Intent(Constants.ACTION_OPEN);
            intent.setClassName(Constants.THIS_PACKAGE_NAME, BluetoothOppReceiver.class.getName());
            intent.setData(contentUri);

            n.when = timeStamp;
            n.setLatestEventInfo(mContext, title, caption, PendingIntent.getBroadcast(mContext, 0,
                    intent, 0));

            intent = new Intent(Constants.ACTION_HIDE);
            intent.setClassName(Constants.THIS_PACKAGE_NAME, BluetoothOppReceiver.class.getName());
            intent.setData(contentUri);
            n.deleteIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);

            mNotificationMgr.notify(id, n);
        }
        cursor.close();
    }

    private void updateIncomingFileConfirmNotification() {
        Cursor cursor = mContext.getContentResolver().query(BluetoothShare.CONTENT_URI, null,
                WHERE_CONFIRM_PENDING, null, BluetoothShare._ID);

        if (cursor == null) {
            return;
        }

        for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
            String title = mContext.getString(R.string.incoming_file_confirm_Notification_title);
            String caption = mContext
                    .getString(R.string.incoming_file_confirm_Notification_caption);
            int id = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare._ID));
            long timeStamp = cursor.getLong(cursor.getColumnIndexOrThrow(BluetoothShare.TIMESTAMP));
            Uri contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + id);

            Notification n = new Notification();
            n.icon = R.drawable.bt_incomming_file_notification;
            n.flags |= Notification.FLAG_ONLY_ALERT_ONCE;
            n.defaults = Notification.DEFAULT_SOUND;
            n.tickerText = title;
            Intent intent = new Intent(Constants.ACTION_INCOMING_FILE_CONFIRM);
            intent.setClassName(Constants.THIS_PACKAGE_NAME, BluetoothOppReceiver.class.getName());
            intent.setData(contentUri);

            n.when = timeStamp;
            n.setLatestEventInfo(mContext, title, caption, PendingIntent.getBroadcast(mContext, 0,
                    intent, 0));

            intent = new Intent(Constants.ACTION_HIDE);
            intent.setClassName(Constants.THIS_PACKAGE_NAME, BluetoothOppReceiver.class.getName());
            intent.setData(contentUri);
            n.deleteIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);

            mNotificationMgr.notify(id, n);
        }
        cursor.close();
    }
}
