/*
 * Copyright (C) 2016 The Android Open Source Project
 *
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
package com.android.bluetooth.pbapclient;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.util.Log;
import android.util.Pair;

import com.android.vcard.VCardEntry;
import com.android.bluetooth.pbapclient.BluetoothPbapClient;
import com.android.bluetooth.R;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import java.lang.Thread;
/**
 * These are the possible paths that can be pulled:
 *       BluetoothPbapClient.PB_PATH;
 *       BluetoothPbapClient.SIM_PB_PATH;
 *       BluetoothPbapClient.ICH_PATH;
 *       BluetoothPbapClient.SIM_ICH_PATH;
 *       BluetoothPbapClient.OCH_PATH;
 *       BluetoothPbapClient.SIM_OCH_PATH;
 *       BluetoothPbapClient.MCH_PATH;
 *       BluetoothPbapClient.SIM_MCH_PATH;
 */
public class PbapPCEClient  implements PbapHandler.PbapListener {
    private static final String TAG = "PbapPCEClient";
    private static final boolean DBG = false;
    private final Queue<PullRequest> mPendingRequests = new ArrayDeque<PullRequest>();
    private final AtomicBoolean mPendingPull = new AtomicBoolean(false);
    private BluetoothDevice mDevice;
    private BluetoothPbapClient mClient;
    private boolean mClientConnected = false;
    private PbapHandler mHandler;
    private Handler mSelfHandler;
    private PullRequest mLastPull;
    private Account mAccount = null;
    private Context mContext = null;
    private AccountManager mAccountManager;
    private DeleteCallLogTask mDeleteCallLogTask;

    PbapPCEClient(Context context) {
        mContext = context;
        mSelfHandler = new Handler(mContext.getMainLooper());
        mHandler = new PbapHandler(this);
        mAccountManager = AccountManager.get(mContext);

    }

    public int getConnectionState() {
        if (mDevice == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        BluetoothPbapClient.ConnectionState currentState = mClient.getState();
        int bluetoothConnectionState;
        switch(currentState) {
          case DISCONNECTED:
              bluetoothConnectionState = BluetoothProfile.STATE_DISCONNECTED;
              break;
          case CONNECTING:
              bluetoothConnectionState = BluetoothProfile.STATE_CONNECTING;
              break;
          case CONNECTED:
              bluetoothConnectionState = BluetoothProfile.STATE_CONNECTED;
              break;
          case DISCONNECTING:
              bluetoothConnectionState = BluetoothProfile.STATE_DISCONNECTING;
              break;
          default:
              bluetoothConnectionState = BluetoothProfile.STATE_DISCONNECTED;
        }
        return bluetoothConnectionState;
    }

    public BluetoothDevice getDevice() {
        return mDevice;
    }

    private synchronized boolean maybePull() {
        if (DBG) {
            Log.d(TAG,"Attempting to Pull");
        }
        if (!mClientConnected) {
            Log.w(TAG, "Client not connected yet -- will execute on next cycle.");
            return false;
        }
        return maybePullLocked();
    }

    private boolean maybePullLocked() {
        if (DBG) {
            Log.d(TAG,"maybePullLocked()");
        }
        if (mPendingPull.compareAndSet(false, true)) {
            if (mPendingRequests.isEmpty()) {
                mPendingPull.set(false);
                return false;
            }
            if (mClient != null) {
                mLastPull = mPendingRequests.remove();
                if (DBG) {
                    Log.d(TAG, "Pulling phone book from: " + mLastPull.path);
                }
                return mClient.pullPhoneBook(mLastPull.path);
            }
        }
        return false;
    }

    private void pullComplete() {
        mPendingPull.set(false);
        maybePull();
    }

    @Override
    public void onPhoneBookPullDone(List<VCardEntry> entries) {
        mLastPull.onPullComplete(true, entries);
        pullComplete();
    }

    @Override
    public void onPhoneBookError() {
        if (DBG) {
            Log.d(TAG, "Error, mLastPull = "  + mLastPull);
        }
        mLastPull.onPullComplete(false, null);
        pullComplete();
    }

    @Override
    public synchronized void onPbapClientConnected(boolean status) {
        mClientConnected = status;
        if (mClientConnected == false) {
            // If we are disconnected then whatever the current device is we should simply clean up.
            handleDisconnect(null);
        }
        if (mClientConnected == true) maybePullLocked();
    }

    public void handleConnect(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalStateException(TAG + ":Connect with null device!");
        } else if (mDevice != null && !mDevice.equals(device)) {
            // Check that we are not already connected to an existing different device.
            // Since the device can be connected to multiple external devices -- we use the honor
            // protocol and only accept the first connecting device.
            Log.e(TAG, ":Got a connected event when connected to a different device. " +
                  "existing = " + mDevice + " new = " + device);
            return;
        } else if (device.equals(mDevice)) {
            Log.w(TAG, "Got a connected event for the same device. Ignoring!");
            return;
        }
        resetState();
        // Cancel any pending delete tasks that might race.
        if (mDeleteCallLogTask != null) {
            mDeleteCallLogTask.cancel(true);
        }
        mDeleteCallLogTask = new DeleteCallLogTask();

        // Cleanup any existing accounts if we get a connected event but previous account state was
        // left hanging (such as unclean shutdown).
        removeUncleanAccounts();

        // Update the device.
        mDevice = device;
        mClient = new BluetoothPbapClient(mDevice, mAccount, mHandler);
        mClient.connect();

        // Add the account. This should give us a place to stash the data.
        addAccount(device.getAddress());
        downloadPhoneBook();
        downloadCallLogs();
    }

    public void handleDisconnect(BluetoothDevice device) {
        Log.w(TAG, "pbap disconnecting from = " + device);

        if (device == null) {
            // If we have a null device then disconnect the current device.
            device = mDevice;
        } else if (mDevice == null) {
            Log.w(TAG, "No existing device connected to service - ignoring device = " + device);
            return;
        } else if (!mDevice.equals(device)) {
            Log.w(TAG, "Existing device different from disconnected device. existing = " + mDevice +
                       " disconnecting device = " + device);
            return;
        }

        if (device != null) {
            removeAccount(mAccount);
            mAccount = null;
        }
        resetState();
    }

    public void start() {
        if (mDevice != null) {
            // We are already connected -Ignore.
            Log.w(TAG, "Already started, ignoring request to start again.");
            return;
        }
        // Device is NULL, we go on remove any unclean shutdown accounts.
        removeUncleanAccounts();
        resetState();
    }

    private void resetState() {
        Log.d(TAG,"resetState()");
        if (mClient != null) {
            // This should abort any inflight messages.
            mClient.disconnect();
        }
        mClient = null;
        mClientConnected = false;
        if (mDeleteCallLogTask != null &&
            mDeleteCallLogTask.getStatus() == AsyncTask.Status.PENDING) {
            mDeleteCallLogTask.execute();
        }
        mDevice = null;
        mAccount = null;
        Log.d(TAG,"resetState Complete");

    }

    private void removeUncleanAccounts() {
        // Find all accounts that match the type "pbap" and delete them. This section is
        // executed only if the device was shut down in an unclean state and contacts persisted.
        Account[] accounts =
            mAccountManager.getAccountsByType(mContext.getString(R.string.pbap_account_type));
        Log.w(TAG, "Found " + accounts.length + " unclean accounts");
        for (Account acc : accounts) {
            Log.w(TAG, "Deleting " + acc);
            // The device ID is the name of the account.
            removeAccount(acc);
        }
    }

    private void downloadCallLogs() {
        // Download Incoming Call Logs.
        CallLogPullRequest ichCallLog = new CallLogPullRequest(mContext, BluetoothPbapClient.ICH_PATH);
        addPullRequest(ichCallLog);

        // Downoad Outgoing Call Logs.
        CallLogPullRequest ochCallLog = new CallLogPullRequest(mContext, BluetoothPbapClient.OCH_PATH);
        addPullRequest(ochCallLog);

        // Downoad Missed Call Logs.
        CallLogPullRequest mchCallLog = new CallLogPullRequest(mContext, BluetoothPbapClient.MCH_PATH);
        addPullRequest(mchCallLog);
    }

    private void downloadPhoneBook() {
        // Download the phone book.
        PhonebookPullRequest pb = new PhonebookPullRequest(mContext, mAccount);
        addPullRequest(pb);
    }

    private class DeleteCallLogTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... unused) {
            if (isCancelled()) {
                return null;
            }

            mContext.getContentResolver().delete(CallLog.Calls.CONTENT_URI, null, null);
            if (DBG) {
                Log.d(TAG, "Call logs deleted.");
            }
            return null;
        }
    }

    private boolean addAccount(String id) {
        mAccount = new Account(id, mContext.getString(R.string.pbap_account_type));
        if (mAccountManager.addAccountExplicitly(mAccount, null, null)) {
            if (DBG) {
                Log.d(TAG, "Added account " + mAccount);
            }
            return true;
        }
        throw new IllegalStateException(TAG + ":Failed to add account!");
    }

    private boolean removeAccount(Account acc) {
        if (mAccountManager.removeAccountExplicitly(acc)) {
            if (DBG) {
                Log.d(TAG, "Removed account " + acc);
            }
            return true;
        }
        Log.e(TAG, "Failed to remove account " + mAccount);
        return false;
    }

    public void addPullRequest(PullRequest r) {
        if (DBG) {
            Log.d(TAG, "pull request mClient=" + mClient + " connected= " +
                    mClientConnected + " mDevice=" + mDevice + " path= " + r.path);
        }
        if (mClient == null || mDevice == null) {
            // It seems we want to pull but the bt connection isn't up, fail it
            // immediately.
            Log.w(TAG, "aborting pull request.");
            r.onPullComplete(false, null);
            return;
        }
        mPendingRequests.add(r);
        maybePull();
    }
}
