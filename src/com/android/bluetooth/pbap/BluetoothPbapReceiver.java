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

package com.android.bluetooth.pbap;

import com.android.bluetooth.R;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothError;
import android.bluetooth.BluetoothIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.util.Log;

public class BluetoothPbapReceiver extends BroadcastReceiver {

    private static final String TAG = "BluetoothPbapReceiver";

    public static final int NOTIFICATION_ID_ACCESS = 1;

    public static final int NOTIFICATION_ID_AUTH = 2;

    static final Object mStartingServiceSync = new Object();

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent i = new Intent();
        i.putExtras(intent);
        i.setClass(context, BluetoothPbapService.class);
        String action = intent.getAction();
        i.putExtra("action", action);
        if (action.equals(BluetoothIntent.BLUETOOTH_STATE_CHANGED_ACTION)
                || action.equals(BluetoothIntent.BLUETOOTH_STATE_CHANGED_ACTION)) {
            int state = intent.getIntExtra(BluetoothIntent.BLUETOOTH_STATE, BluetoothError.ERROR);
            i.putExtra(BluetoothIntent.BLUETOOTH_STATE, state);
            if ((state != BluetoothDevice.BLUETOOTH_STATE_TURNING_ON)
                    && (state != BluetoothDevice.BLUETOOTH_STATE_TURNING_OFF)) {
                beginStartingService(context, i);
            }
        } else {
            beginStartingService(context, i);
        }
    }

    public static void makeNewPbapNotification(Context mContext, String action) {

        NotificationManager nm = (NotificationManager)mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notification = null;
        Resources res = mContext.getResources();
        String name = BluetoothPbapService.getRemoteDeviceName();
        // Create an intent triggered by clicking on the status icon.
        Intent clickIntent = new Intent();
        clickIntent.setClass(mContext, BluetoothPbapActivity.class);
        clickIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        clickIntent.setAction(action);

        // Create an intent triggered by clicking on the
        // "Clear All Notifications" button
        Intent deleteIntent = new Intent();
        deleteIntent.setClass(mContext, BluetoothPbapReceiver.class);

        if (action.equals(BluetoothPbapService.ACCESS_REQUEST)) {
            deleteIntent.setAction(BluetoothPbapService.ACCESS_DISALLOWED);
            notification = new Notification(android.R.drawable.stat_sys_data_bluetooth, res
                    .getString(R.string.pbap_notif_ticker), System.currentTimeMillis());
            notification.setLatestEventInfo(mContext, res.getString(R.string.pbap_notif_title), res
                    .getString(R.string.pbap_notif_message, name), PendingIntent
                    .getActivity(mContext, 0, clickIntent, 0));

            notification.flags |= Notification.FLAG_AUTO_CANCEL;
            notification.deleteIntent = PendingIntent.getBroadcast(mContext, 0, deleteIntent, 0);
            nm.notify(NOTIFICATION_ID_ACCESS, notification);
        } else if (action.equals(BluetoothPbapService.AUTH_CHALL)) {
            deleteIntent.setAction(BluetoothPbapService.AUTH_CANCELLED);
            notification = new Notification(android.R.drawable.stat_sys_data_bluetooth, res
                    .getString(R.string.auth_notif_ticker), System.currentTimeMillis());
            notification.setLatestEventInfo(mContext, res.getString(R.string.auth_notif_title), res
                    .getString(R.string.auth_notif_message, name), PendingIntent
                    .getActivity(mContext, 0, clickIntent, 0));

            notification.flags |= Notification.FLAG_AUTO_CANCEL;
            notification.deleteIntent = PendingIntent.getBroadcast(mContext, 0, deleteIntent, 0);
            nm.notify(NOTIFICATION_ID_AUTH, notification);
        }
    }

    public static void removePbapNotification(Context mContext, int id) {
        NotificationManager nm = (NotificationManager)mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(id);
    }

    private static void beginStartingService(Context context, Intent intent) {
        synchronized (mStartingServiceSync) {
            context.startService(intent);
        }
    }

    public static void finishStartingService(Service service, int startId) {
        synchronized (mStartingServiceSync) {
            if (service.stopSelfResult(startId)) {
                Log.i(TAG, "successfully stopped pbap service");
            }
        }
    }
}
