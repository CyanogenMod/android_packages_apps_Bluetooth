 /*
 * Copyright (c) 2008-2009, Motorola, Inc.
 * Copyright (c) 2010-2011, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *          notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *          notice, this list of conditions and the following disclaimer in the
 *          documentation and/or other materials provided with the distribution.
 *        * Neither the name of Code Aurora nor
 *          the names of its contributors may be used to endorse or promote
 *          products derived from this software without specific prior written
 *          permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.    IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.map;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.Preference;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.CompoundButton.OnCheckedChangeListener;

import com.android.bluetooth.R;
import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;

import static com.android.bluetooth.map.BluetoothMasService.EXTRA_BLUETOOTH_DEVICE;

/**
 * MapActivity shows two dialogues: One for accepting incoming ftp request and
 * the other prompts the user to enter a session key for authentication with a
 * remote Bluetooth device.
 */
public class BluetoothMasActivity extends AlertActivity implements
        DialogInterface.OnClickListener, Preference.OnPreferenceChangeListener, TextWatcher {
    private static final String TAG = "BluetoothMasActivity";

    private static final boolean V = BluetoothMasService.VERBOSE;

    private static final int BLUETOOTH_OBEX_AUTHKEY_MAX_LENGTH = 16;

    private static final int DIALOG_YES_NO_CONNECT = 1;

    private static final String KEY_USER_TIMEOUT = "user_timeout";

    private View mView;

    private EditText mKeyView;

    private TextView messageView;

    private String mSessionKey = "";

    private int mCurrentDialog;

    private Button mOkButton;

    private CheckBox mAlwaysAllowed;

    private boolean mTimeout = false;

    private boolean mAlwaysAllowedValue = true;

    private static final int DISMISS_TIMEOUT_DIALOG = 0;

    private static final int DISMISS_TIMEOUT_DIALOG_VALUE = 2000;

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothMasService.USER_CONFIRM_TIMEOUT_ACTION.equals(intent.getAction())) {
                return;
            }
            onTimeout();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = getIntent();
        String action = i.getAction();
        if (BluetoothMasService.ACCESS_REQUEST_ACTION.equals(action)) {
            showMapDialog(DIALOG_YES_NO_CONNECT);
            mCurrentDialog = DIALOG_YES_NO_CONNECT;
        }
        else {
            Log.e(TAG, "Error: this activity may be started only with intent "
                    + "MAP_ACCESS_REQUEST");
            finish();
        }
        registerReceiver(mReceiver, new IntentFilter(
                BluetoothMasService.USER_CONFIRM_TIMEOUT_ACTION));
    }

    private void showMapDialog(int id) {
        final AlertController.AlertParams p = mAlertParams;
        switch (id) {
            case DIALOG_YES_NO_CONNECT:
                p.mIconId = android.R.drawable.ic_dialog_info;
                p.mTitle = getString(R.string.map_acceptance_dialog_header);
                p.mView = createView(DIALOG_YES_NO_CONNECT);
                p.mPositiveButtonText = getString(android.R.string.yes);
                p.mPositiveButtonListener = this;
                p.mNegativeButtonText = getString(android.R.string.no);
                p.mNegativeButtonListener = this;
                mOkButton = mAlert.getButton(DialogInterface.BUTTON_POSITIVE);
                setupAlert();
                break;
            default:
                break;
        }
    }

    private String getRemoteDeviceName() {
        String remoteDeviceName = null;
        Intent intent = getIntent();
        if (intent.hasExtra(EXTRA_BLUETOOTH_DEVICE)) {
            BluetoothDevice device = intent.getParcelableExtra(EXTRA_BLUETOOTH_DEVICE);
            if (device != null) {
                remoteDeviceName = device.getName();
            }
        }

        return (remoteDeviceName != null) ? remoteDeviceName : getString(R.string.defaultname);
    }

    private String createDisplayText(final int id) {
        String mRemoteName = getRemoteDeviceName();
        switch (id) {
        case DIALOG_YES_NO_CONNECT:
            String mMessage1 = getString(R.string.map_acceptance_dialog_title, mRemoteName,
                    mRemoteName);
            return mMessage1;
        default:
            return null;
        }
    }

    private View createView(final int id) {
        switch (id) {
            case DIALOG_YES_NO_CONNECT:
                //mView = getLayoutInflater().inflate(R.layout.access, null);
                messageView = (TextView)mView.findViewById(R.id.message);
                messageView.setText(createDisplayText(id));
                //mAlwaysAllowed = (CheckBox)mView.findViewById(R.id.alwaysallowed);
                mAlwaysAllowed.setChecked(true);
                mAlwaysAllowed.setOnCheckedChangeListener(new OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        if (isChecked) {
                            mAlwaysAllowedValue = true;
                        } else {
                            mAlwaysAllowedValue = false;
                        }
                    }
                });
                return mView;
            default:
                return null;
        }
    }

    private void onPositive() {
        if (!mTimeout) {
            if (mCurrentDialog == DIALOG_YES_NO_CONNECT) {
                sendIntentToReceiver(BluetoothMasService.ACCESS_ALLOWED_ACTION,
                        BluetoothMasService.EXTRA_ALWAYS_ALLOWED, mAlwaysAllowedValue);
            }
        }
        mTimeout = false;
        finish();
    }

    private void onNegative() {
        if (mCurrentDialog == DIALOG_YES_NO_CONNECT) {
            sendIntentToReceiver(BluetoothMasService.ACCESS_DISALLOWED_ACTION, null, null);
        }
        finish();
    }

    private void sendIntentToReceiver(final String intentName, final String extraName,
            final String extraValue) {
        Intent intent = new Intent(intentName);
        intent.setClassName(BluetoothMasService.THIS_PACKAGE_NAME, BluetoothMasReceiver.class
                .getName());
        if (extraName != null) {
            intent.putExtra(extraName, extraValue);
        }
        sendBroadcast(intent);
    }

    private void sendIntentToReceiver(final String intentName, final String extraName,
            final boolean extraValue) {
        Intent intent = new Intent(intentName);
        intent.setClassName(BluetoothMasService.THIS_PACKAGE_NAME, BluetoothMasReceiver.class
                .getName());
        if (extraName != null) {
            intent.putExtra(extraName, extraValue);
        }
        Intent i = getIntent();
        if (i.hasExtra(EXTRA_BLUETOOTH_DEVICE)) {
            intent.putExtra(EXTRA_BLUETOOTH_DEVICE, i.getParcelableExtra(EXTRA_BLUETOOTH_DEVICE));
        }
        sendBroadcast(intent);
    }

    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                onPositive();
                break;

            case DialogInterface.BUTTON_NEGATIVE:
                onNegative();
                break;
            default:
                break;
        }
    }

    private void onTimeout() {
        mTimeout = true;
        if (mCurrentDialog == DIALOG_YES_NO_CONNECT) {
            if(mView != null) {
                messageView.setText(getString(R.string.map_acceptance_timeout_message,
                        getRemoteDeviceName()));
                mAlert.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.GONE);
                mAlwaysAllowed.setVisibility(View.GONE);
                mAlwaysAllowed.clearFocus();
            }
        }

        mTimeoutHandler.sendMessageDelayed(mTimeoutHandler.obtainMessage(DISMISS_TIMEOUT_DIALOG),
                DISMISS_TIMEOUT_DIALOG_VALUE);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mTimeout = savedInstanceState.getBoolean(KEY_USER_TIMEOUT);
        if (V) Log.v(TAG, "onRestoreInstanceState() mTimeout: " + mTimeout);

        if (mTimeout) {
            onTimeout();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_USER_TIMEOUT, mTimeout);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);
    }

    public boolean onPreferenceChange(Preference preference, Object newValue) {
        return true;
    }

    public void beforeTextChanged(CharSequence s, int start, int before, int after) {
    }

    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    public void afterTextChanged(android.text.Editable s) {
        if (s.length() > 0) {
            mOkButton.setEnabled(true);
        }
    }
    private final Handler mTimeoutHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case DISMISS_TIMEOUT_DIALOG:
                    if (V) Log.v(TAG, "Received DISMISS_TIMEOUT_DIALOG msg.");
                    finish();
                    break;
                default:
                    break;
            }
        }
    };
}

