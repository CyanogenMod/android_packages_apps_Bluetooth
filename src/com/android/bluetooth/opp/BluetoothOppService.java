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

import com.google.android.collect.Lists;
import javax.obex.ObexTransport;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothError;
import android.bluetooth.BluetoothIntent;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.CharArrayBuffer;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;
import android.os.Process;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Performs the background Bluetooth OPP transfer. It also starts thread to
 * accept incoming OPP connection.
 */

public class BluetoothOppService extends Service {

    private boolean userAccepted = false;

    private class BluetoothShareContentObserver extends ContentObserver {

        public BluetoothShareContentObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange) {
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "Service ContentObserver received notification");
            }
            updateFromProvider();
        }
    }

    private static final String TAG = "BtOpp Service";

    /** Observer to get notified when the content observer's data changes */
    private BluetoothShareContentObserver mObserver;

    /** Class to handle Notification Manager updates */
    private BluetoothOppNotification mNotifier;

    private boolean mPendingUpdate;

    private UpdateThread mUpdateThread;

    private ArrayList<BluetoothOppShareInfo> mShares;

    private ArrayList<BluetoothOppBatch> mBatchs;

    private BluetoothOppTransfer mTransfer;

    private BluetoothOppTransfer mServerTransfer;

    private int mBatchId;

    /**
     * Array used when extracting strings from content provider
     */
    private CharArrayBuffer mOldChars;

    /**
     * Array used when extracting strings from content provider
     */
    private CharArrayBuffer mNewChars;

    private BluetoothDevice mBluetooth;

    private PowerManager mPowerManager;

    private BluetoothOppRfcommListener mSocketListener;

    private boolean mListenStarted = false;

    /*
     * TODO No support for queue incoming from multiple devices.
     * Make an array list of server session to support receiving queue from
     * multiple devices
     */
    private BluetoothOppObexServerSession mServerSession;

    @Override
    public IBinder onBind(Intent arg0) {
        throw new UnsupportedOperationException("Cannot bind to Bluetooth OPP Service");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Constants.LOGVV) {
            Log.v(TAG, "Service onCreate");
        }
        mBluetooth = (BluetoothDevice)getSystemService(Context.BLUETOOTH_SERVICE);
        mPowerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
        mSocketListener = new BluetoothOppRfcommListener();
        mShares = Lists.newArrayList();
        mBatchs = Lists.newArrayList();
        mObserver = new BluetoothShareContentObserver();
        getContentResolver().registerContentObserver(BluetoothShare.CONTENT_URI, true, mObserver);
        mBatchId = 1;
        mNotifier = new BluetoothOppNotification(this);
        mNotifier.mNotificationMgr.cancelAll();
        mNotifier.updateNotification();

        trimDatabase();

        IntentFilter filter = new IntentFilter(BluetoothIntent.REMOTE_DEVICE_DISCONNECTED_ACTION);
        filter.addAction(BluetoothIntent.BLUETOOTH_STATE_CHANGED_ACTION);
        registerReceiver(mBluetoothIntentReceiver, filter);

        synchronized (BluetoothOppService.this) {
            if (mBluetooth == null) {
                Log.w(TAG, "Local BT device is not enabled");
            } else {
                startListenerDelayed();
            }
        }
        if (Constants.LOGVV) {
            BluetoothOppPreference.getInstance(this).dump();
        }
        updateFromProvider();
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        if (Constants.LOGVV) {
            Log.v(TAG, "Service onStart");
        }

        if (mBluetooth == null) {
            Log.w(TAG, "Local BT device is not enabled");
        } else {
            startListenerDelayed();
        }
        updateFromProvider();

    }

    private void startListenerDelayed() {
        if (!mListenStarted) {
            if (mBluetooth.isEnabled()) {
                if (Constants.LOGVV) {
                    Log.v(TAG, "Starting RfcommListener in 9 seconds");
                }
                mHandler.sendMessageDelayed(mHandler.obtainMessage(START_LISTENER), 9000);
                mListenStarted = true;
            }
        }
    }

    private static final int START_LISTENER = 1;

    private static final int MEDIA_SCANNED = 2;

    private static final int MEDIA_SCANNED_FAILED = 3;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case START_LISTENER:
                    if (mBluetooth.isEnabled()) {
                        startSocketListener();
                    }
                    break;
                case MEDIA_SCANNED:
                    if (Constants.LOGVV) {
                        Log.v(TAG, "Update mInfo.id " + msg.arg1 + " for data uri= "
                                + msg.obj.toString());
                    }
                    ContentValues updateValues = new ContentValues();
                    Uri contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + msg.arg1);
                    updateValues.put(Constants.MEDIA_SCANNED, Constants.MEDIA_SCANNED_SCANNED_OK);
                    updateValues.put(BluetoothShare.URI, msg.obj.toString()); // update
                    updateValues.put(BluetoothShare.MIMETYPE, getContentResolver().getType(
                            Uri.parse(msg.obj.toString())));
                    getContentResolver().update(contentUri, updateValues, null, null);

                    break;
                case MEDIA_SCANNED_FAILED:
                    Log.v(TAG, "Update mInfo.id " + msg.arg1 + " for MEDIA_SCANNED_FAILED");
                    ContentValues updateValues1 = new ContentValues();
                    Uri contentUri1 = Uri.parse(BluetoothShare.CONTENT_URI + "/" + msg.arg1);
                    updateValues1.put(Constants.MEDIA_SCANNED,
                            Constants.MEDIA_SCANNED_SCANNED_FAILED);
                    getContentResolver().update(contentUri1, updateValues1, null, null);
            }
        }

    };

    private void startSocketListener() {

        if (Constants.LOGVV) {
            Log.v(TAG, "start RfcommListener");
        }
        mSocketListener.start(mIncomingConnectionHandler);
        if (Constants.LOGVV) {
            Log.v(TAG, "RfcommListener started");
        }
    }

    @Override
    public void onDestroy() {
        if (Constants.LOGVV) {
            Log.v(TAG, "Service onDestroy");
        }
        super.onDestroy();
        getContentResolver().unregisterContentObserver(mObserver);
        unregisterReceiver(mBluetoothIntentReceiver);
        mSocketListener.stop();
    }

    private final Handler mIncomingConnectionHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (Constants.LOGV) {
                Log.v(TAG, "Get incoming connection");
            }
            ObexTransport transport = (ObexTransport)msg.obj;
            /*
             * TODO need to identify in which case we can create a
             * serverSession, and when we will reject connection
             */
            createServerSession(transport);
        }
    };

    /* suppose we auto accept an incoming OPUSH connection */
    private void createServerSession(ObexTransport transport) {
        mServerSession = new BluetoothOppObexServerSession(this, transport);
        mServerSession.preStart();
        if (Constants.LOGV) {
            Log.v(TAG, "Get ServerSession " + mServerSession.toString()
                    + " for incoming connection" + transport.toString());
        }
    }

    private void handleRemoteDisconnected(String address) {
        if (Constants.LOGVV) {
            Log.v(TAG, "Handle remote device disconnected " + address);
        }
        int batchId = -1;
        int i;
        if (mTransfer != null) {
            batchId = mTransfer.getBatchId();
            i = findBatchWithId(batchId);
            if (i != -1 && mBatchs.get(i).mStatus == Constants.BATCH_STATUS_RUNNING
                    && mBatchs.get(i).mDestination.equals(address)) {
                if (Constants.LOGVV) {
                    Log.v(TAG, "Find mTransfer is running for remote device " + address);
                }
                mTransfer.stop();

            }
        } else if (mServerTransfer != null) {
            batchId = mServerTransfer.getBatchId();
            i = findBatchWithId(batchId);
            if (i != -1 && mBatchs.get(i).mStatus == Constants.BATCH_STATUS_RUNNING
                    && mBatchs.get(i).mDestination.equals(address)) {
                if (Constants.LOGVV) {
                    Log.v(TAG, "Find mServerTransfer is running for remote device " + address);
                }
            }
        }
    }

    private final BroadcastReceiver mBluetoothIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String address = intent.getStringExtra(BluetoothIntent.ADDRESS);

            if (action.equals(BluetoothIntent.REMOTE_DEVICE_DISCONNECTED_ACTION)) {
                if (Constants.LOGVV) {
                    Log.v(TAG, "Receiver REMOTE_DEVICE_DISCONNECTED_ACTION from " + address);
                }
                handleRemoteDisconnected(address);

            } else if (action.equals(BluetoothIntent.BLUETOOTH_STATE_CHANGED_ACTION)) {

                switch (intent.getIntExtra(BluetoothIntent.BLUETOOTH_STATE, BluetoothError.ERROR)) {
                    case BluetoothDevice.BLUETOOTH_STATE_ON:
                        if (Constants.LOGVV) {
                            Log.v(TAG,
                                    "Receiver BLUETOOTH_STATE_CHANGED_ACTION, BLUETOOTH_STATE_ON");
                        }
                        startSocketListener();
                        break;
                    case BluetoothDevice.BLUETOOTH_STATE_TURNING_OFF:
                        if (Constants.LOGVV) {
                            Log.v(TAG, "Receiver DISABLED_ACTION ");
                        }
                        mSocketListener.stop();
                        mListenStarted = false;
                        synchronized (BluetoothOppService.this) {
                            if (mUpdateThread == null) {
                                stopSelf();
                            }
                        }
                        break;
                }
            }
        }
    };

    private void updateFromProvider() {
        synchronized (BluetoothOppService.this) {
            mPendingUpdate = true;
            if (mUpdateThread == null) {
                mUpdateThread = new UpdateThread();
                mUpdateThread.start();
            }
        }
    }

    private class UpdateThread extends Thread {
        public UpdateThread() {
            super("Bluetooth Share Service");
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            boolean keepUpdateThread = false;
            for (;;) {
                synchronized (BluetoothOppService.this) {
                    if (mUpdateThread != this) {
                        throw new IllegalStateException(
                                "multiple UpdateThreads in BluetoothOppService");
                    }
                    if (Constants.LOGVV) {
                        Log.v(TAG, "pendingUpdate is " + mPendingUpdate + " keepUpdateThread is "
                                + keepUpdateThread + " sListenStarted is " + mListenStarted);
                    }
                    if (!mPendingUpdate) {
                        mUpdateThread = null;
                        if (!keepUpdateThread && !mListenStarted) {
                            stopSelf();
                            break;
                        }
                        return;
                    }
                    mPendingUpdate = false;
                }
                Cursor cursor = getContentResolver().query(BluetoothShare.CONTENT_URI, null, null,
                        null, BluetoothShare._ID);

                if (cursor == null) {
                    return;
                }

                cursor.moveToFirst();

                int arrayPos = 0;

                keepUpdateThread = false;
                boolean isAfterLast = cursor.isAfterLast();

                int idColumn = cursor.getColumnIndexOrThrow(BluetoothShare._ID);
                /*
                 * Walk the cursor and the local array to keep them in sync. The
                 * key to the algorithm is that the ids are unique and sorted
                 * both in the cursor and in the array, so that they can be
                 * processed in order in both sources at the same time: at each
                 * step, both sources point to the lowest id that hasn't been
                 * processed from that source, and the algorithm processes the
                 * lowest id from those two possibilities. At each step: -If the
                 * array contains an entry that's not in the cursor, remove the
                 * entry, move to next entry in the array. -If the array
                 * contains an entry that's in the cursor, nothing to do, move
                 * to next cursor row and next array entry. -If the cursor
                 * contains an entry that's not in the array, insert a new entry
                 * in the array, move to next cursor row and next array entry.
                 */
                while (!isAfterLast || arrayPos < mShares.size()) {
                    if (isAfterLast) {
                        // We're beyond the end of the cursor but there's still
                        // some
                        // stuff in the local array, which can only be junk
                        if (Constants.LOGVV) {
                            int arrayId = mShares.get(arrayPos).mId;
                            Log.v(TAG, "Array update: trimming " + arrayId + " @ " + arrayPos);
                        }

                        if (shouldScanFile(arrayPos)) {
                            scanFile(null, arrayPos);
                        }
                        deleteShare(arrayPos); // this advances in the array
                    } else {
                        int id = cursor.getInt(idColumn);

                        if (arrayPos == mShares.size()) {
                            insertShare(cursor, arrayPos);
                            if (Constants.LOGVV) {
                                Log.v(TAG, "Array update: inserting " + id + " @ " + arrayPos);
                            }
                            if (shouldScanFile(arrayPos) && (!scanFile(cursor, arrayPos))) {
                                keepUpdateThread = true;
                            }
                            if (visibleNotification(arrayPos)) {
                                keepUpdateThread = true;
                            }
                            if (needAction(arrayPos)) {
                                keepUpdateThread = true;
                            }

                            ++arrayPos;
                            cursor.moveToNext();
                            isAfterLast = cursor.isAfterLast();
                        } else {
                            int arrayId = mShares.get(arrayPos).mId;

                            if (arrayId < id) {
                                if (Constants.LOGVV) {
                                    Log.v(TAG, "Array update: removing " + arrayId + " @ "
                                            + arrayPos);
                                }
                                if (shouldScanFile(arrayPos)) {
                                    scanFile(null, arrayPos);
                                }
                                deleteShare(arrayPos);
                            } else if (arrayId == id) {
                                // This cursor row already exists in the stored
                                // array
                                updateShare(cursor, arrayPos, userAccepted);
                                if (shouldScanFile(arrayPos) && (!scanFile(cursor, arrayPos))) {
                                    keepUpdateThread = true;
                                }
                                if (visibleNotification(arrayPos)) {
                                    keepUpdateThread = true;
                                }
                                if (needAction(arrayPos)) {
                                    keepUpdateThread = true;
                                }

                                ++arrayPos;
                                cursor.moveToNext();
                                isAfterLast = cursor.isAfterLast();
                            } else {
                                // This cursor entry didn't exist in the stored
                                // array
                                if (Constants.LOGVV) {
                                    Log.v(TAG, "Array update: appending " + id + " @ " + arrayPos);
                                }
                                insertShare(cursor, arrayPos);

                                if (shouldScanFile(arrayPos) && (!scanFile(cursor, arrayPos))) {
                                    keepUpdateThread = true;
                                }
                                if (visibleNotification(arrayPos)) {
                                    keepUpdateThread = true;
                                }
                                if (needAction(arrayPos)) {
                                    keepUpdateThread = true;
                                }
                                ++arrayPos;
                                cursor.moveToNext();
                                isAfterLast = cursor.isAfterLast();
                            }
                        }
                    }
                }

                mNotifier.updateNotification();

                cursor.close();
            }
        }

    }

    private void insertShare(Cursor cursor, int arrayPos) {
        BluetoothOppShareInfo info = new BluetoothOppShareInfo(
                cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare._ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(BluetoothShare.URI)),
                cursor.getString(cursor.getColumnIndexOrThrow(BluetoothShare.FILENAME_HINT)),
                cursor.getString(cursor.getColumnIndexOrThrow(BluetoothShare._DATA)),
                cursor.getString(cursor.getColumnIndexOrThrow(BluetoothShare.MIMETYPE)),
                cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.DIRECTION)),
                cursor.getString(cursor.getColumnIndexOrThrow(BluetoothShare.DESTINATION)),
                cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.VISIBILITY)),
                cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.USER_CONFIRMATION)),
                cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.STATUS)),
                cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.TOTAL_BYTES)),
                cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.CURRENT_BYTES)),
                cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.TIMESTAMP)),
                cursor.getInt(cursor.getColumnIndexOrThrow(Constants.MEDIA_SCANNED)) != Constants.MEDIA_SCANNED_NOT_SCANNED);

        if (Constants.LOGVV) {
            Log.v(TAG, "Service adding new entry");
            Log.v(TAG, "ID      : " + info.mId);
            // Log.v(TAG, "URI     : " + ((info.mUri != null) ? "yes" : "no"));
            Log.v(TAG, "URI     : " + info.mUri);
            Log.v(TAG, "HINT    : " + info.mHint);
            Log.v(TAG, "FILENAME: " + info.mFilename);
            Log.v(TAG, "MIMETYPE: " + info.mMimetype);
            Log.v(TAG, "DIRECTION: " + info.mDirection);
            Log.v(TAG, "DESTINAT: " + info.mDestination);
            Log.v(TAG, "VISIBILI: " + info.mVisibility);
            Log.v(TAG, "CONFIRM : " + info.mConfirm);
            Log.v(TAG, "STATUS  : " + info.mStatus);
            Log.v(TAG, "TOTAL   : " + info.mTotalBytes);
            Log.v(TAG, "CURRENT : " + info.mCurrentBytes);
            Log.v(TAG, "TIMESTAMP : " + info.mTimestamp);
            Log.v(TAG, "SCANNED : " + info.mMediaScanned);
        }

        mShares.add(arrayPos, info);

        /* Mark the info as failed if it's in invalid status */
        if (info.isObsolete()) {
            Constants.updateShareStatus(this, info.mId, BluetoothShare.STATUS_UNKNOWN_ERROR);
        }
        /*
         * Add info into a batch. The logic is
         * 1) Only add valid and readyToStart info
         * 2) If there is no batch, create a batch and insert this transfer into batch,
         * then run the batch
         * 3) If there is existing batch and timestamp match, insert transfer into batch
         * 4) If there is existing batch and timestamp does not match, create a new batch and
         * put in queue
         */

        if (info.isReadyToStart()) {
            if (info.mDirection == BluetoothShare.DIRECTION_OUTBOUND) {
                /* check if the file exists */
                try {
                    InputStream i = getContentResolver().openInputStream(Uri.parse(info.mUri));
                    i.close();
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "Can't open file for OUTBOUND info " + info.mId);
                    Constants.updateShareStatus(this, info.mId, BluetoothShare.STATUS_BAD_REQUEST);
                    return;
                } catch (IOException ex) {
                    Log.e(TAG, "IO error when close file for OUTBOUND info " + info.mId);
                    return;
                }
            }
            if (mBatchs.size() == 0) {
                BluetoothOppBatch newBatch = new BluetoothOppBatch(this, info);
                newBatch.mId = mBatchId;
                mBatchId++;
                mBatchs.add(newBatch);
                if (info.mDirection == BluetoothShare.DIRECTION_OUTBOUND) {
                    if (Constants.LOGVV) {
                        Log.v(TAG, "Service create new Batch " + newBatch.mId
                                + " for OUTBOUND info " + info.mId);
                    }
                    mTransfer = new BluetoothOppTransfer(this, mPowerManager, newBatch);
                } else if (info.mDirection == BluetoothShare.DIRECTION_INBOUND) {
                    if (Constants.LOGVV) {
                        Log.v(TAG, "Service create new Batch " + newBatch.mId
                                + " for INBOUND info " + info.mId);
                    }
                    mServerTransfer = new BluetoothOppTransfer(this, mPowerManager, newBatch,
                            mServerSession);
                }

                if (info.mDirection == BluetoothShare.DIRECTION_OUTBOUND && mTransfer != null) {
                    if (Constants.LOGVV) {
                        Log.v(TAG, "Service start transfer new Batch " + newBatch.mId
                                + " for info " + info.mId);
                    }
                    mTransfer.start();
                } else if (info.mDirection == BluetoothShare.DIRECTION_INBOUND
                        && mServerTransfer != null) {
                    /*
                     * TODO investigate here later?
                     */
                    if (Constants.LOGVV) {
                        Log.v(TAG, "Service start server transfer new Batch " + newBatch.mId
                                + " for info " + info.mId);
                    }
                    mServerTransfer.start();
                }

            } else {
                int i = findBatchWithTimeStamp(info.mTimestamp);
                if (i != -1) {
                    if (Constants.LOGVV) {
                        Log.v(TAG, "Service add info " + info.mId + " to existing batch "
                                + mBatchs.get(i).mId);
                    }
                    mBatchs.get(i).addShare(info);
                } else {
                    BluetoothOppBatch newBatch = new BluetoothOppBatch(this, info);
                    newBatch.mId = mBatchId;
                    mBatchId++;
                    mBatchs.add(newBatch);
                    if (Constants.LOGVV) {
                        Log.v(TAG, "Service add new Batch " + newBatch.mId + " for info "
                                + info.mId);
                    }
                }
            }
        }
    }

    private void updateShare(Cursor cursor, int arrayPos, boolean userAccepted) {
        BluetoothOppShareInfo info = mShares.get(arrayPos);
        int statusColumn = cursor.getColumnIndexOrThrow(BluetoothShare.STATUS);

        info.mId = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare._ID));
        info.mUri = stringFromCursor(info.mUri, cursor, BluetoothShare.URI);
        info.mHint = stringFromCursor(info.mHint, cursor, BluetoothShare.FILENAME_HINT);
        info.mFilename = stringFromCursor(info.mFilename, cursor, BluetoothShare._DATA);
        info.mMimetype = stringFromCursor(info.mMimetype, cursor, BluetoothShare.MIMETYPE);
        info.mDirection = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.DIRECTION));
        info.mDestination = stringFromCursor(info.mDestination, cursor, BluetoothShare.DESTINATION);
        int newVisibility = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.VISIBILITY));

        boolean confirmed = false;
        int newConfirm = cursor.getInt(cursor
                .getColumnIndexOrThrow(BluetoothShare.USER_CONFIRMATION));

        if (info.mVisibility == BluetoothShare.VISIBILITY_VISIBLE
                && newVisibility != BluetoothShare.VISIBILITY_VISIBLE
                && (BluetoothShare.isStatusCompleted(info.mStatus) || newConfirm == BluetoothShare.USER_CONFIRMATION_PENDING)) {
            mNotifier.mNotificationMgr.cancel(info.mId);
        }

        info.mVisibility = newVisibility;

        if (info.mConfirm == BluetoothShare.USER_CONFIRMATION_PENDING
                && newConfirm != BluetoothShare.USER_CONFIRMATION_PENDING) {
            confirmed = true;
        }
        info.mConfirm = cursor.getInt(cursor
                .getColumnIndexOrThrow(BluetoothShare.USER_CONFIRMATION));
        int newStatus = cursor.getInt(statusColumn);

        if (!BluetoothShare.isStatusCompleted(info.mStatus)
                && BluetoothShare.isStatusCompleted(newStatus)) {
            mNotifier.mNotificationMgr.cancel(info.mId);
        }

        info.mStatus = newStatus;
        info.mTotalBytes = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.TOTAL_BYTES));
        info.mCurrentBytes = cursor.getInt(cursor
                .getColumnIndexOrThrow(BluetoothShare.CURRENT_BYTES));
        info.mTimestamp = cursor.getInt(cursor.getColumnIndexOrThrow(BluetoothShare.TIMESTAMP));
        info.mMediaScanned = (cursor.getInt(cursor.getColumnIndexOrThrow(Constants.MEDIA_SCANNED)) != Constants.MEDIA_SCANNED_NOT_SCANNED);

        if (confirmed) {
            if (Constants.LOGVV) {
                Log.v(TAG, "Service handle info " + info.mId + " confirmed");
            }
            /* Inbounds transfer get user confirmation, so we start it */
            int i = findBatchWithTimeStamp(info.mTimestamp);
            BluetoothOppBatch batch = mBatchs.get(i);
            if (batch.mId == mServerTransfer.getBatchId()) {
                mServerTransfer.setConfirmed();
            } //TODO need to think about else
        }
        int i = findBatchWithTimeStamp(info.mTimestamp);
        if (i != -1) {
            BluetoothOppBatch batch = mBatchs.get(i);
            if (batch.mStatus == Constants.BATCH_STATUS_FINISHED
                    || batch.mStatus == Constants.BATCH_STATUS_FAILED) {
                if (Constants.LOGVV) {
                    Log.v(TAG, "Batch " + batch.mId + " is finished");
                }
                if (batch.mDirection == BluetoothShare.DIRECTION_OUTBOUND) {
                    if (mTransfer == null) {
                        Log.e(TAG, "Unexpected error! mTransfer is null");
                    } else if (batch.mId == mTransfer.getBatchId()) {
                        mTransfer.stop();
                    } else {
                        Log.e(TAG, "Unexpected error! batch id " + batch.mId
                                + " doesn't match mTransfer id " + mTransfer.getBatchId());
                    }
                } else {
                    if (mServerTransfer == null) {
                        Log.e(TAG, "Unexpected error! mServerTransfer is null");
                    } else if (batch.mId == mServerTransfer.getBatchId()) {
                        mServerTransfer.stop();
                    } else {
                        Log.e(TAG, "Unexpected error! batch id " + batch.mId
                                + " doesn't match mServerTransfer id "
                                + mServerTransfer.getBatchId());
                    }
                }
                removeBatch(batch);
            }
        }
    }

    /**
     * Removes the local copy of the info about a share.
     */
    private void deleteShare(int arrayPos) {
        BluetoothOppShareInfo info = mShares.get(arrayPos);

        /*
         * Delete arrayPos from a batch. The logic is
         * 1) Search existing batch for the info
         * 2) cancel the batch
         * 3) If the batch become empty delete the batch
         */
        int i = findBatchWithTimeStamp(info.mTimestamp);
        if (i != -1) {
            BluetoothOppBatch batch = mBatchs.get(i);
            if (batch.hasShare(info)) {
                if (Constants.LOGVV) {
                    Log.v(TAG, "Service cancel batch for share " + info.mId);
                }
                batch.cancelBatch();
            }
            if (batch.isEmpty()) {
                if (Constants.LOGVV) {
                    Log.v(TAG, "Service remove batch  " + batch.mId);
                }
                removeBatch(batch);
            }
        }
        mShares.remove(arrayPos);
    }

    private String stringFromCursor(String old, Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        if (old == null) {
            return cursor.getString(index);
        }
        if (mNewChars == null) {
            mNewChars = new CharArrayBuffer(128);
        }
        cursor.copyStringToBuffer(index, mNewChars);
        int length = mNewChars.sizeCopied;
        if (length != old.length()) {
            return cursor.getString(index);
        }
        if (mOldChars == null || mOldChars.sizeCopied < length) {
            mOldChars = new CharArrayBuffer(length);
        }
        char[] oldArray = mOldChars.data;
        char[] newArray = mNewChars.data;
        old.getChars(0, length, oldArray, 0);
        for (int i = length - 1; i >= 0; --i) {
            if (oldArray[i] != newArray[i]) {
                return new String(newArray, 0, length);
            }
        }
        return old;
    }

    private int findBatchWithTimeStamp(long timestamp) {
        for (int i = mBatchs.size() - 1; i >= 0; i--) {
            if (mBatchs.get(i).mTimestamp == timestamp) {
                return i;
            }
        }
        return -1;
    }

    private int findBatchWithId(int id) {
        if (Constants.LOGVV) {
            Log.v(TAG, "Service search batch for id " + id + " from " + mBatchs.size());
        }
        for (int i = mBatchs.size() - 1; i >= 0; i--) {
            if (mBatchs.get(i).mId == id) {
                return i;
            }
        }
        return -1;
    }

    private void removeBatch(BluetoothOppBatch batch) {
        if (Constants.LOGVV) {
            Log.v(TAG, "Remove batch " + batch.mId);
        }
        mBatchs.remove(batch);
        if (mBatchs.size() > 0) {
            for (int i = 0; i < mBatchs.size(); i++) {
                // we have a running batch
                if (mBatchs.get(i).mStatus == Constants.BATCH_STATUS_RUNNING) {
                    return;
                } else {
                    /*
                     * TODO Pending batch for inbound transfer is not considered
                     * here
                     */
                    // we have a pending batch
                    if (batch.mDirection == mBatchs.get(i).mDirection) {
                        if (Constants.LOGVV) {
                            Log.v(TAG, "Start pending batch " + mBatchs.get(i).mId);
                        }
                        mTransfer = new BluetoothOppTransfer(this, mPowerManager, mBatchs.get(i));
                        mTransfer.start();
                        return;
                    }
                }
            }
        }
    }

    private boolean needAction(int arrayPos) {
        BluetoothOppShareInfo info = mShares.get(arrayPos);
        if (BluetoothShare.isStatusCompleted(info.mStatus)) {
            return false;
        }
        return true;
    }

    private boolean visibleNotification(int arrayPos) {
        BluetoothOppShareInfo info = mShares.get(arrayPos);
        return info.hasCompletionNotification();
    }

    private boolean scanFile(Cursor cursor, int arrayPos) {
        BluetoothOppShareInfo info = mShares.get(arrayPos);
        synchronized (BluetoothOppService.this) {
            if (Constants.LOGV) {
                Log.v(TAG, "Scanning file " + info.mFilename);
            }
            new MediaScannerNotifier(this, info, mHandler);
            return true;
        }
    }

    private boolean shouldScanFile(int arrayPos) {
        BluetoothOppShareInfo info = mShares.get(arrayPos);
        return !info.mMediaScanned && info.mDirection == BluetoothShare.DIRECTION_INBOUND
                && BluetoothShare.isStatusSuccess(info.mStatus);

    }

    private void trimDatabase() {
        Cursor cursor = getContentResolver().query(BluetoothShare.CONTENT_URI, new String[] {
            BluetoothShare._ID
        }, BluetoothShare.STATUS + " >= '200'", null, BluetoothShare._ID);
        if (cursor == null) {
            // This isn't good - if we can't do basic queries in our database,
            // nothing's gonna work
            Log.e(TAG, "null cursor in trimDatabase");
            return;
        }
        if (cursor.moveToFirst()) {
            int numDelete = cursor.getCount() - Constants.MAX_RECORDS_IN_DATABASE;
            int columnId = cursor.getColumnIndexOrThrow(BluetoothShare._ID);
            while (numDelete > 0) {
                getContentResolver().delete(
                        ContentUris.withAppendedId(BluetoothShare.CONTENT_URI, cursor
                                .getLong(columnId)), null, null);
                if (!cursor.moveToNext()) {
                    break;
                }
                numDelete--;
            }
        }
        cursor.close();
    }

    private static class MediaScannerNotifier implements MediaScannerConnectionClient {

        private MediaScannerConnection mConnection;

        private BluetoothOppShareInfo mInfo;

        private Context mContext;

        private Handler mCallback;

        public MediaScannerNotifier(Context context, BluetoothOppShareInfo info, Handler handler) {
            mContext = context;
            mInfo = info;
            mCallback = handler;
            mConnection = new MediaScannerConnection(mContext, this);
            if (Constants.LOGVV) {
                Log.v(TAG, "Connecting to MediaScannerConnection ");
            }
            mConnection.connect();
        }

        public void onMediaScannerConnected() {
            if (Constants.LOGVV) {
                Log.v(TAG, "MediaScannerConnection onMediaScannerConnected");
            }
            mConnection.scanFile(mInfo.mFilename, mInfo.mMimetype);
        }

        public void onScanCompleted(String path, Uri uri) {
            try {
                if (Constants.LOGVV) {
                    Log.v(TAG, "MediaScannerConnection onScanCompleted");
                    Log.v(TAG, "MediaScannerConnection path is " + path);
                    Log.v(TAG, "MediaScannerConnection Uri is " + uri);
                }
                if (uri != null) {
                    Message msg = Message.obtain();
                    msg.setTarget(mCallback);
                    msg.what = MEDIA_SCANNED;
                    msg.arg1 = mInfo.mId;
                    msg.obj = uri;
                    msg.sendToTarget();
                } else {
                    Message msg = Message.obtain();
                    msg.setTarget(mCallback);
                    msg.what = MEDIA_SCANNED_FAILED;
                    msg.arg1 = mInfo.mId;
                    msg.sendToTarget();
                }
            } catch (Exception ex) {
                Log.v(TAG, "!!!MediaScannerConnection exception: " + ex);
            } finally {
                if (Constants.LOGVV) {
                    Log.v(TAG, "MediaScannerConnection disconnect");
                }
                mConnection.disconnect();
            }
        }
    }
}
