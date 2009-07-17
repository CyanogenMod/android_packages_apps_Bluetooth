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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.method.DigitsKeyListener;
import android.util.Log;
import android.util.Config;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;

/**
 * BluetoothPinDialog asks the user to enter a PIN for pairing with a remote
 * Bluetooth device. It is an activity that appears as a dialog.
 */
public class BluetoothPinDialog extends AlertActivity implements DialogInterface.OnClickListener {
    private static final boolean DEBUG = true;

    private static final boolean LOG_ENABLED = DEBUG ? Config.LOGD : Config.LOGV;

    private static final String TAG = "-------------BluetoothPinDialog";

    static final boolean V = true;

    private BluetoothDevicePickerManager mLocalManager;

    private String mAddress;

    private EditText mPinView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (!intent.getAction().equals(BluetoothIntent.PAIRING_REQUEST_ACTION)) {
            Log.e(TAG, "Error: this activity may be started only with intent "
                    + BluetoothIntent.PAIRING_REQUEST_ACTION);
            finish();
        } else {
            log("get PAIRING_REQUEST_ACTION");
            // abortBroadcast();
        }

        mLocalManager = BluetoothDevicePickerManager.getInstance(this);
        mAddress = intent.getStringExtra(BluetoothIntent.ADDRESS);

        // Set up the "dialog"
        final AlertController.AlertParams p = mAlertParams;
        p.mIconId = android.R.drawable.ic_dialog_info;
        p.mTitle = getString(R.string.bluetooth_pin_entry);
        p.mView = createView();
        p.mPositiveButtonText = getString(android.R.string.ok);
        p.mPositiveButtonListener = this;
        p.mNegativeButtonText = getString(android.R.string.cancel);
        p.mNegativeButtonListener = this;
        setupAlert();
    }

    private View createView() {
        View view = getLayoutInflater().inflate(R.layout.bluetooth_pin_entry, null);

        String name = mLocalManager.getLocalDeviceManager().getName(mAddress);
        TextView messageView = (TextView)view.findViewById(R.id.message);
        messageView.setText(getString(R.string.bluetooth_enter_pin_msg, name));

        mPinView = (EditText)view.findViewById(R.id.text);

        return view;
    }

    private void onPair(String pin) {
        byte[] pinBytes = BluetoothDevice.convertPinToBytes(pin);

        if (pinBytes == null) {
            return;
        }

        mLocalManager.getBluetoothManager().setPin(mAddress, pinBytes);
    }

    private void onCancel() {
        mLocalManager.getBluetoothManager().cancelPairingUserInput(mAddress);
    }

    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                onPair(mPinView.getText().toString());
                break;

            case DialogInterface.BUTTON_NEGATIVE:
                onCancel();
                break;
        }
    }

    static void log(String message) {
        if (LOG_ENABLED) {
            Log.v(TAG, message);
        }
    }

}
