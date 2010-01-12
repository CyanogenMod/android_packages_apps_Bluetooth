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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;

/**
 * This class provides a simplified interface on top of other Bluetooth service
 * layer components; Also it handles some Opp application level variables. It's
 * a singleton got from BluetoothOppManager.getInstance(context);
 */
public class BluetoothOppManager {
    private static final String TAG = "BluetoothOppManager";
    private static final boolean D = Constants.DEBUG;
    private static final boolean V = Constants.VERBOSE;

    private static BluetoothOppManager INSTANCE;

    /** Used when obtaining a reference to the singleton instance. */
    private static Object INSTANCE_LOCK = new Object();

    private boolean mInitialized;

    private Context mContext;

    private BluetoothAdapter mAdapter;

    private String mMimeTypeOfSendigFile;

    private String mUriOfSendingFile;

    private String mMimeTypeOfSendigFiles;

    private ArrayList<Uri> mUrisOfSendingFiles;

    private boolean mCanStartTransfer = false;

    private static final String OPP_PREFERENCE_FILE = "OPPMGR";

    private static final String SENDING_FLAG = "SENDINGFLAG";

    private static final String MIME_TYPE = "MIMETYPE";

    private static final String FILE_URI = "FILE_URI";

    private static final String MIME_TYPE_MULTIPLE = "MIMETYPE_MULTIPLE";

    private static final String FILE_URIS = "FILE_URIS";

    private static final String MULTIPLE_FLAG = "MULTIPLE_FLAG";

    private static final String ARRAYLIST_ITEM_SEPERATOR = "!";

    // used to judge if need continue sending process after received a
    // ENABLED_ACTION
    public boolean mSendingFlag;

    public boolean mMultipleFlag;

    public int mfileNumInBatch;

    /**
     * Get singleton instance.
     */
    public static BluetoothOppManager getInstance(Context context) {
        synchronized (INSTANCE_LOCK) {
            if (INSTANCE == null) {
                INSTANCE = new BluetoothOppManager();
            }
            INSTANCE.init(context);

            return INSTANCE;
        }
    }

    /**
     * init
     */
    private boolean init(Context context) {
        if (mInitialized)
            return true;
        mInitialized = true;

        // This will be around as long as this process is
        mContext = context.getApplicationContext();

        mAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mAdapter == null) {
            if (V) Log.v(TAG, "BLUETOOTH_SERVICE is not started! ");
        }

        // Restore data from preference
        restoreApplicationData();

        return true;
    }

    /**
     * Restore data from preference
     */
    private void restoreApplicationData() {
        SharedPreferences settings = mContext.getSharedPreferences(OPP_PREFERENCE_FILE, 0);

        mSendingFlag = settings.getBoolean(SENDING_FLAG, false);
        mMimeTypeOfSendigFile = settings.getString(MIME_TYPE, null);
        mUriOfSendingFile = settings.getString(FILE_URI, null);
        mMimeTypeOfSendigFiles = settings.getString(MIME_TYPE_MULTIPLE, null);
        mMultipleFlag = settings.getBoolean(MULTIPLE_FLAG, false);

        if (V) Log.v(TAG, "restoreApplicationData! " + mSendingFlag + mMultipleFlag
                    + mMimeTypeOfSendigFile + mUriOfSendingFile);

        String strUris = settings.getString(FILE_URIS, null);
        // TODO(Moto): restore mUrisOfSendingFiles from strUris.
    }

    /**
     * Save application data to preference, need restore these data later
     */
    private void onDestroy() {
        SharedPreferences.Editor editor = mContext.getSharedPreferences(OPP_PREFERENCE_FILE, 0)
                .edit();
        editor.putBoolean(SENDING_FLAG, mSendingFlag).commit();
        editor.putString(MIME_TYPE, mMimeTypeOfSendigFile).commit();
        editor.putString(FILE_URI, mUriOfSendingFile).commit();
        editor.putString(MIME_TYPE_MULTIPLE, mMimeTypeOfSendigFiles).commit();
        editor.putBoolean(MULTIPLE_FLAG, mMultipleFlag).commit();
        String strUris;
        StringBuilder sb = new StringBuilder();
        for (int i = 0, count = mUrisOfSendingFiles.size(); i < count; i++) {
            Uri uriContent = mUrisOfSendingFiles.get(i);
            sb.append(uriContent);
            sb.append(ARRAYLIST_ITEM_SEPERATOR);
        }
        strUris = sb.toString();
        editor.putString(FILE_URIS, strUris).commit();
        if (V) Log.v(TAG, "finalize is called and application data saved by SharedPreference! ");
    }

    /**
     * Save data to preference when this class is destroyed by system due to
     * memory lack
     */
    protected void finalize() throws Throwable {
        try {
            onDestroy();
        } finally {
            super.finalize();
        }
    }

    public void saveSendingFileInfo(String mimeType, String uri) {
        mMultipleFlag = false;
        mMimeTypeOfSendigFile = mimeType;
        mUriOfSendingFile = uri;
        mCanStartTransfer = true;
    }

    public void saveSendingFileInfo(String mimeType, ArrayList<Uri> uris) {
        mMultipleFlag = true;
        mMimeTypeOfSendigFiles = mimeType;
        mUrisOfSendingFiles = uris;
        mCanStartTransfer = true;
    }
    /**
     * Get the current status of Bluetooth hardware.
     * @return true if Bluetooth enabled, false otherwise.
     */
    public boolean isEnabled() {
        if (mAdapter != null) {
            return mAdapter.isEnabled();
        } else {
            if (V) Log.v(TAG, "BLUETOOTH_SERVICE is not available! ");
            return false;
        }
    }

    /**
     * Enable Bluetooth hardware.
     */
    public void enableBluetooth() {
        if (mAdapter != null) {
            mAdapter.enable();
        }
    }

    /**
     * Disable Bluetooth hardware.
     */
    public void disableBluetooth() {
        if (mAdapter != null) {
            mAdapter.disable();
        }
    }

    /**
     * Get device name per bluetooth address.
     */
    public String getDeviceName(BluetoothDevice device) {
        String deviceName;

        deviceName = BluetoothOppPreference.getInstance(mContext).getName(device);

        if (deviceName == null && mAdapter != null) {
            deviceName = device.getName();
        }

        if (deviceName == null) {
            deviceName = mContext.getString(R.string.unknown_device);
        }

        return deviceName;
    }

    /**
     * insert sending sessions to db, only used by Opp application.
     */
    public void startTransfer(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "Target bt device is null!");
            return;
        }

        if (!mCanStartTransfer) {
            if (V) Log.v(TAG, "No transfer info restored: fileType&fileName");
            return;
        }

        if (mMultipleFlag == true) {
            int count = mUrisOfSendingFiles.size();
            mfileNumInBatch = count;

            Long ts = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                Uri fileUri = mUrisOfSendingFiles.get(i);
                ContentResolver contentResolver = mContext.getContentResolver();
                String contentType = contentResolver.getType(fileUri);
                if (V) Log.v(TAG, "Got mimetype: " + contentType + "  Got uri: " + fileUri);

                ContentValues values = new ContentValues();
                values.put(BluetoothShare.URI, fileUri.toString());
                values.put(BluetoothShare.MIMETYPE, contentType);
                values.put(BluetoothShare.DESTINATION, device.getAddress());
                values.put(BluetoothShare.TIMESTAMP, ts);

                final Uri contentUri = mContext.getContentResolver().insert(
                        BluetoothShare.CONTENT_URI, values);
                if (V) Log.v(TAG, "Insert contentUri: " + contentUri + "  to device: "
                            + getDeviceName(device));
            }
        } else {
            ContentValues values = new ContentValues();
            values.put(BluetoothShare.URI, mUriOfSendingFile);
            values.put(BluetoothShare.MIMETYPE, mMimeTypeOfSendigFile);
            values.put(BluetoothShare.DESTINATION, device.getAddress());

            final Uri contentUri = mContext.getContentResolver().insert(BluetoothShare.CONTENT_URI,
                    values);
            if (V) Log.v(TAG, "Insert contentUri: " + contentUri + "  to device: "
                        + getDeviceName(device));
        }

        // reset vars
        mMimeTypeOfSendigFile = null;
        mUriOfSendingFile = null;
        mUrisOfSendingFiles = null;
        mMultipleFlag = false;
        mCanStartTransfer = false;
    }
}
