
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
