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

import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.text.format.Formatter;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;

/**
 * This class is designed to ask user to confirm if accept incoming file;
 */
public class BluetoothOppIncomingFileConfirmActivity extends AlertActivity implements
        DialogInterface.OnClickListener {
    private static final String TAG = "BluetoothIncomingFileConfirmActivity";

    private BluetoothOppTransferInfo mTransInfo;

    private Uri mUri;

    private ContentValues mUpdateValues;

    private boolean mOkOrCancelClicked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        mUri = intent.getData();
        mTransInfo = new BluetoothOppTransferInfo();
        mTransInfo = BluetoothOppUtility.queryRecord(this, mUri);
        if (mTransInfo == null) {
            if (Constants.LOGVV) {
                Log.e(TAG, "Error: Can not get data from db");
            }
            finish();
            return;
        }

        // Set up the "dialog"
        final AlertController.AlertParams p = mAlertParams;
        p.mIconId = android.R.drawable.ic_dialog_info;
        p.mTitle = getString(R.string.incoming_file_confirm_title);
        p.mView = createView();
        p.mPositiveButtonText = getString(R.string.incoming_file_confirm_ok);
        p.mPositiveButtonListener = this;
        p.mNegativeButtonText = getString(R.string.incoming_file_confirm_cancel);
        p.mNegativeButtonListener = this;
        setupAlert();

        if (Constants.LOGVV) {
            Log.v(TAG, "BluetoothIncomingFileConfirmActivity: Got uri:" + mUri);
        }
    }

    private View createView() {
        View view = getLayoutInflater().inflate(R.layout.confirm_dialog, null);

        TextView contentView = (TextView)view.findViewById(R.id.content);

        String text = getString(R.string.incoming_file_confirm_content, mTransInfo.mDeviceName,
                mTransInfo.mFileName, Formatter.formatFileSize(this, mTransInfo.mTotalBytes));

        contentView.setText(text);

        return view;
    }

    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                // Update database
                mUpdateValues = new ContentValues();
                mUpdateValues.put(BluetoothShare.USER_CONFIRMATION,
                        BluetoothShare.USER_CONFIRMATION_CONFIRMED);
                this.getContentResolver().update(mUri, mUpdateValues, null, null);

                Toast.makeText(this, getString(R.string.bt_toast_1), Toast.LENGTH_SHORT).show();
                break;

            case DialogInterface.BUTTON_NEGATIVE:
                // Update database
                mUpdateValues = new ContentValues();
                mUpdateValues.put(BluetoothShare.USER_CONFIRMATION,
                        BluetoothShare.USER_CONFIRMATION_DENIED);
                this.getContentResolver().update(mUri, mUpdateValues, null, null);

                Toast.makeText(this, getString(R.string.bt_toast_2), Toast.LENGTH_SHORT).show();
                break;
        }
        mOkOrCancelClicked = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mOkOrCancelClicked) {
            return;
        }
        // for the "back" key press case
        mUpdateValues = new ContentValues();
        mUpdateValues.put(BluetoothShare.VISIBILITY, BluetoothShare.VISIBILITY_HIDDEN);
        this.getContentResolver().update(mUri, mUpdateValues, null, null);
        if (Constants.LOGVV) {
            Log.v(TAG, "db updated: change to VISIBILITY_HIDDEN");
        }
        Toast.makeText(this, getString(R.string.bt_toast_2), Toast.LENGTH_SHORT).show();
    }
}
