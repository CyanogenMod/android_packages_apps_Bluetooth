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

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;

/**
 * This class is designed to show BT enabling progress; It will be killed when
 * received BT_ENABLED intent.
 */
public class BluetoothOppBtEnablingActivity extends AlertActivity {
    private static final String TAG = "BluetoothOppEnablingActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set up the "dialog"
        final AlertController.AlertParams p = mAlertParams;
        p.mIconId = android.R.drawable.ic_dialog_info;
        p.mTitle = getString(R.string.enabling_progress_title);
        p.mView = createView();
        setupAlert();
    }

    private View createView() {
        View view = getLayoutInflater().inflate(R.layout.bt_enabling_progress, null);
        TextView contentView = (TextView)view.findViewById(R.id.progress_info);
        contentView.setText(getString(R.string.enabling_progress_content));

        return view;
    }

    // TODO(Moto): add timer mech to handle the case
    // "can not enable BT and can not recv the bt enabled intent".

    @Override
    protected void onPause() {
        super.onPause();

        BluetoothOppManager mOppManager = BluetoothOppManager.getInstance(this);

        // if user press "back" key during BT enabling, cancel the sending
        // operation
        if (mOppManager.mSendingFlag) {
            mOppManager.mSendingFlag = false;
            mOppManager.disableBluetooth(); // can work? May not!
            if (Constants.LOGVV) {
                Log.v(TAG, "Disabling Bluetooth:! ");
            }
        }

        // In this dialog, when press "back" key, will call
        // AlertActivity.cancel() function - finish()
        finish();
        if (Constants.LOGVV) {
            Log.v(TAG, "onPause(): finish() called");
        }
    }
}
