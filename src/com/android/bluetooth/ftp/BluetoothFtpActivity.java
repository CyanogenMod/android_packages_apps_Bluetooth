 /*
 * Copyright (c) 2010, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *            notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *            notice, this list of conditions and the following disclaimer in the
 *            documentation and/or other materials provided with the distribution.
 *        * Neither the name of Code Aurora nor
 *            the names of its contributors may be used to endorse or promote
 *            products derived from this software without specific prior written
 *            permission.
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

package com.android.bluetooth.ftp;

import com.android.bluetooth.R;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.Preference;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Button;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.InputFilter.LengthFilter;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;

/**
 * FtpActivity shows two dialogues: One for accepting incoming ftp request and
 * the other prompts the user to enter a session key for authentication with a
 * remote Bluetooth device.
 */
public class BluetoothFtpActivity extends AlertActivity implements
        DialogInterface.OnClickListener, Preference.OnPreferenceChangeListener, TextWatcher {
    private static final String TAG = "BluetoothFtpActivity";

    private static final boolean V = BluetoothFtpService.VERBOSE;

    private static final int BLUETOOTH_OBEX_AUTHKEY_MAX_LENGTH = 16;

    private static final int DIALOG_YES_NO_CONNECT = 1;

    private static final int DIALOG_YES_NO_AUTH = 2;

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
            if (!BluetoothFtpService.USER_CONFIRM_TIMEOUT_ACTION.equals(intent.getAction())) {
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
        if(V) Log.v(TAG,"onCreate action = "+ action);
        if (action.equals(BluetoothFtpService.ACCESS_REQUEST_ACTION)) {
            showFtpDialog(DIALOG_YES_NO_CONNECT);
            mCurrentDialog = DIALOG_YES_NO_CONNECT;
        } else if (action.equals(BluetoothFtpService.AUTH_CHALL_ACTION)) {
            showFtpDialog(DIALOG_YES_NO_AUTH);
            mCurrentDialog = DIALOG_YES_NO_AUTH;
        } else {
            Log.e(TAG, "Error: this activity may be started only with intent "
                    + "FTP_ACCESS_REQUEST or FTP_AUTH_CHALL ");
            finish();
        }
        Log.i(TAG,"onCreate");
        registerReceiver(mReceiver, new IntentFilter(
                BluetoothFtpService.USER_CONFIRM_TIMEOUT_ACTION));
    }
    /*
    * Creates a Button with Yes/No dialog
    */
    private void showFtpDialog(int id) {
        final AlertController.AlertParams p = mAlertParams;
        switch (id) {
            case DIALOG_YES_NO_CONNECT:
                if(V) Log.v(TAG,"showFtpDialog DIALOG_YES_NO_CONNECT");
                p.mIconId = android.R.drawable.ic_dialog_info;
                p.mTitle = getString(R.string.ftp_acceptance_dialog_header);
                p.mView = createView(DIALOG_YES_NO_CONNECT);
                p.mPositiveButtonText = getString(android.R.string.yes);
                p.mPositiveButtonListener = this;
                p.mNegativeButtonText = getString(android.R.string.no);
                p.mNegativeButtonListener = this;
                mOkButton = mAlert.getButton(DialogInterface.BUTTON_POSITIVE);
                setupAlert();
                break;
            case DIALOG_YES_NO_AUTH:
                if(V) Log.v(TAG,"showFtpDialog DIALOG_YES_NO_AUTH");
                p.mIconId = android.R.drawable.ic_dialog_info;
                p.mTitle = getString(R.string.ftp_session_key_dialog_header);
                p.mView = createView(DIALOG_YES_NO_AUTH);
                p.mPositiveButtonText = getString(android.R.string.ok);
                p.mPositiveButtonListener = this;
                p.mNegativeButtonText = getString(android.R.string.cancel);
                p.mNegativeButtonListener = this;
                setupAlert();
                mOkButton = mAlert.getButton(DialogInterface.BUTTON_POSITIVE);
                mOkButton.setEnabled(false);
                break;
            default:
                break;
        }
    }
    /*
    * Creates a Text window for the FTP acceptance or session key dialog
    */
    private String createDisplayText(final int id) {
        String mRemoteName = BluetoothFtpService.getRemoteDeviceName();
        if(V) Log.v(TAG,"createDisplayText" + id);
        switch (id) {
            case DIALOG_YES_NO_CONNECT:
                String mMessage1 = getString(R.string.ftp_acceptance_dialog_title, mRemoteName,
                        mRemoteName);
                return mMessage1;
            case DIALOG_YES_NO_AUTH:
                String mMessage2 = getString(R.string.ftp_session_key_dialog_title, mRemoteName);
                return mMessage2;
            default:
                Log.e(TAG,"Display Text id ("+ id + ")not part of FTP resource");
                return null;
        }
    }
    /*
    * Creates a view for the dialog and text to get the user inputs
    */
    private View createView(final int id) {
        if(V) Log.v(TAG,"createView" + id);
        switch (id) {
            case DIALOG_YES_NO_CONNECT:
                mView = getLayoutInflater().inflate(R.layout.access, null);
                messageView = (TextView)mView.findViewById(R.id.message);
                messageView.setText(createDisplayText(id));
                mAlwaysAllowed = (CheckBox)mView.findViewById(R.id.alwaysallowed);
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
            case DIALOG_YES_NO_AUTH:
                mView = getLayoutInflater().inflate(R.layout.auth, null);
                messageView = (TextView)mView.findViewById(R.id.message);
                messageView.setText(createDisplayText(id));
                mKeyView = (EditText)mView.findViewById(R.id.text);
                mKeyView.addTextChangedListener(this);
                mKeyView.setFilters(new InputFilter[] {
                    new LengthFilter(BLUETOOTH_OBEX_AUTHKEY_MAX_LENGTH)
                });
                return mView;
            default:
                Log.e(TAG,"Create view id ("+ id + ")not part of FTP resource");
                return null;
        }
    }

    private void onPositive() {
        if(V) Log.v(TAG,"onPositive mtimeout = " + mTimeout + "mCurrentDialog = " + mCurrentDialog);
        if (!mTimeout) {
            if (mCurrentDialog == DIALOG_YES_NO_CONNECT) {
                sendIntentToReceiver(BluetoothFtpService.ACCESS_ALLOWED_ACTION,
                        BluetoothFtpService.EXTRA_ALWAYS_ALLOWED, mAlwaysAllowedValue);
            } else if (mCurrentDialog == DIALOG_YES_NO_AUTH) {
                sendIntentToReceiver(BluetoothFtpService.AUTH_RESPONSE_ACTION,
                        BluetoothFtpService.EXTRA_SESSION_KEY, mSessionKey);
                mKeyView.removeTextChangedListener(this);
            }
        }
        mTimeout = false;
        finish();
    }

    private void onNegative() {
        if(V) Log.v(TAG,"onNegative mtimeout = " + mTimeout + "mCurrentDialog = " + mCurrentDialog);
        if (mCurrentDialog == DIALOG_YES_NO_CONNECT) {
            sendIntentToReceiver(BluetoothFtpService.ACCESS_DISALLOWED_ACTION, null, null);
        } else if (mCurrentDialog == DIALOG_YES_NO_AUTH) {
            sendIntentToReceiver(BluetoothFtpService.AUTH_CANCELLED_ACTION, null, null);
            mKeyView.removeTextChangedListener(this);
        }
        finish();
    }
    /*
    * Sends an intent to the BluetoothFtpService class with a String parameter
    * @param intentName the name of the intent to be broadcasted
    * @param extraName the name of the extra parameter broadcasted
    * @param extraValue the extra name parameter broadcasted
    */
    private void sendIntentToReceiver(final String intentName, final String extraName,
            final String extraValue) {
        Intent intent = new Intent(intentName);
        intent.setClassName(BluetoothFtpService.THIS_PACKAGE_NAME, BluetoothFtpReceiver.class
                .getName());
        if (extraName != null) {
            intent.putExtra(extraName, extraValue);
        }
        sendBroadcast(intent);
    }
    /*
    * Sends an intent to the BluetoothFtpService class with a integer parameter
    * @param intentName the name of the intent to be broadcasted
    * @param extraName the name of the extra parameter broadcasted
    * @param extraValue the extra name parameter broadcasted

    */
    private void sendIntentToReceiver(final String intentName, final String extraName,
            final boolean extraValue) {
        Intent intent = new Intent(intentName);
        intent.setClassName(BluetoothFtpService.THIS_PACKAGE_NAME, BluetoothFtpReceiver.class
                .getName());
        if (extraName != null) {
            intent.putExtra(extraName, extraValue);
        }
        sendBroadcast(intent);
    }

    public void onClick(DialogInterface dialog, int which) {
        if(V) Log.v(TAG,"onClick which = " + which);
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                if (mCurrentDialog == DIALOG_YES_NO_AUTH) {
                    mSessionKey = mKeyView.getText().toString();
                }
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
        if(V) Log.v(TAG,"onTimeout mCurrentDialog = " + mCurrentDialog);
        if (mCurrentDialog == DIALOG_YES_NO_CONNECT) {
            /*Proceed to clear the view only if one is created*/
            if(mView != null) {
                messageView.setText(getString(R.string.ftp_acceptance_timeout_message,
                        BluetoothFtpService.getRemoteDeviceName()));
                mAlert.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.GONE);
                mAlwaysAllowed.setVisibility(View.GONE);
                mAlwaysAllowed.clearFocus();
            }
        } else if (mCurrentDialog == DIALOG_YES_NO_AUTH) {
            /* Proceed to clear the view only if one created */
            if(mView != null) {
                messageView.setText(getString(R.string.ftp_authentication_timeout_message,
                        BluetoothFtpService.getRemoteDeviceName()));
                mKeyView.setVisibility(View.GONE);
                mKeyView.clearFocus();
                mKeyView.removeTextChangedListener(this);
                mOkButton.setEnabled(true);
                mAlert.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.GONE);
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
