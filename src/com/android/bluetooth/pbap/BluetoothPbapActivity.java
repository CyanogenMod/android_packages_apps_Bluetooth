
package com.android.bluetooth.pbap;

import com.android.bluetooth.R;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
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
 * PbapActivity shows two dialogues: One for accepting incoming pbap request and
 * the other prompts the user to enter a session key for authentication with a
 * remote Bluetooth device.
 */
public class BluetoothPbapActivity extends AlertActivity implements
        DialogInterface.OnClickListener, Preference.OnPreferenceChangeListener, TextWatcher {
    private static final String TAG = "BluetoothPbapActivity";

    private static final int BLUETOOTH_OBEX_AUTHKEY_MAX_LENGTH = 16;

    private static final int DIALOG_YES_NO_CONNECT = 1;

    private static final int DIALOG_YES_NO_AUTH = 2;

    private View mView;

    private EditText mKeyView;

    private TextView messageView;

    private String mSessionKey = "";

    private int mCurrentDialog = 0;

    private Button mOkButton;

    private CheckBox mAlwaysAllowed;

    private static final String USER_TIMEOUT = "user_timeout";

    private boolean mUserConfirmTimeout = false;

    private boolean mAlwaysAllowedValue = false;

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothPbapService.USER_CONFIRM_TIMEOUT.equals(intent.getAction())) {
                return;
            }
            onUserConfirmTimeout();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = getIntent();
        String action = i.getAction();
        if (action.equals(BluetoothPbapService.ACCESS_REQUEST)) {
            showPbapDialog(DIALOG_YES_NO_CONNECT);
            mCurrentDialog = DIALOG_YES_NO_CONNECT;
        } else if (action.equals(BluetoothPbapService.AUTH_CHALL)) {
            showPbapDialog(DIALOG_YES_NO_AUTH);
            mCurrentDialog = DIALOG_YES_NO_AUTH;
        } else {
            Log.e(TAG, "Error: this activity may be started only with intent "
                    + "PBAP_ACCESS_REQUEST or PBAP_AUTH_CHALL ");
            finish();
        }
        registerReceiver(mReceiver, new IntentFilter(BluetoothPbapService.USER_CONFIRM_TIMEOUT));
    }

    private void showPbapDialog(int id) {
        final AlertController.AlertParams p = mAlertParams;
        switch (id) {
            case DIALOG_YES_NO_CONNECT:
                p.mIconId = android.R.drawable.ic_dialog_info;
                p.mTitle = getString(R.string.pbap_acceptance_dialog_header);
                p.mView = createView(DIALOG_YES_NO_CONNECT);
                p.mPositiveButtonText = getString(android.R.string.yes);
                p.mPositiveButtonListener = this;
                p.mNegativeButtonText = getString(android.R.string.no);
                p.mNegativeButtonListener = this;
                mOkButton = mAlert.getButton(DialogInterface.BUTTON_POSITIVE);
                setupAlert();
                break;
            case DIALOG_YES_NO_AUTH:
                p.mIconId = android.R.drawable.ic_dialog_info;
                p.mTitle = getString(R.string.pbap_session_key_dialog_header);
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

    private String createDisplayText(final int id) {
        String mRemoteName = BluetoothPbapService.getRemoteDeviceName();
        switch (id) {
            case DIALOG_YES_NO_CONNECT:
                String mMessage1 = getString(R.string.pbap_acceptance_dialog_title, mRemoteName,
                        mRemoteName);
                return mMessage1;
            case DIALOG_YES_NO_AUTH:
                String mMessage2 = getString(R.string.pbap_session_key_dialog_title, mRemoteName);
                return mMessage2;
            default:
                return null;
        }
    }

    private View createView(final int id) {
        switch (id) {
            case DIALOG_YES_NO_CONNECT:
                mView = getLayoutInflater().inflate(R.layout.access, null);
                messageView = (TextView)mView.findViewById(R.id.message);
                messageView.setText(createDisplayText(id));
                mAlwaysAllowed = (CheckBox)mView.findViewById(R.id.alwaysallowed);
                mAlwaysAllowed.setOnCheckedChangeListener(new OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        if (isChecked) {
                            mAlwaysAllowedValue = true;
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
                return null;
        }
    }

    private void onPositive() {
        if (!mUserConfirmTimeout) {
            if (mCurrentDialog == DIALOG_YES_NO_CONNECT) {
                sendIntentToReceiver(BluetoothPbapService.ACCESS_ALLOWED,
                        BluetoothPbapService.ALWAYS_ALLOWED, mAlwaysAllowedValue);
            } else if (mCurrentDialog == DIALOG_YES_NO_AUTH) {
                sendIntentToReceiver(BluetoothPbapService.AUTH_RESPONSE,
                        BluetoothPbapService.SESSION_KEY, mSessionKey);
                mKeyView.removeTextChangedListener(this);
            }
        }
        mUserConfirmTimeout = false;
        finish();
    }

    private void onNegative() {
        if (mCurrentDialog == DIALOG_YES_NO_CONNECT) {
            sendIntentToReceiver(BluetoothPbapService.ACCESS_DISALLOWED, null, null);
        } else if (mCurrentDialog == DIALOG_YES_NO_AUTH) {
            sendIntentToReceiver(BluetoothPbapService.AUTH_CANCELLED, null, null);
            mKeyView.removeTextChangedListener(this);
        }
        finish();
    }

    private void sendIntentToReceiver(final String intentName, final String extraName,
            final String extraValue) {
        Intent intent = new Intent(intentName);
        intent.setClassName(BluetoothPbapService.THIS_PACKAGE_NAME, BluetoothPbapReceiver.class
                .getName());
        if (extraName != null) {
            intent.putExtra(extraName, extraValue);
        }
        sendBroadcast(intent);
    }

    private void sendIntentToReceiver(final String intentName, final String extraName,
            final boolean extraValue) {
        Intent intent = new Intent(intentName);
        intent.setClassName(BluetoothPbapService.THIS_PACKAGE_NAME, BluetoothPbapReceiver.class
                .getName());
        if (extraName != null) {
            intent.putExtra(extraName, extraValue);
        }
        sendBroadcast(intent);
    }

    public void onClick(DialogInterface dialog, int which) {
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
        }
    }

    private void onUserConfirmTimeout() {
        mUserConfirmTimeout = true;
        if (mCurrentDialog == DIALOG_YES_NO_CONNECT) {
            messageView.setText(getString(R.string.pbap_acceptance_timeout_message,
                    BluetoothPbapService.getRemoteDeviceName()));
            mAlert.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.GONE);
            mAlwaysAllowed.setVisibility(View.GONE);
            mAlwaysAllowed.clearFocus();
        } else if (mCurrentDialog == DIALOG_YES_NO_AUTH) {
            messageView.setText(getString(R.string.pbap_authentication_timeout_message,
                    BluetoothPbapService.getRemoteDeviceName()));
            mKeyView.setVisibility(View.GONE);
            mKeyView.clearFocus();
            mKeyView.removeTextChangedListener(this);
            mOkButton.setEnabled(true);
            mAlert.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(View.GONE);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mUserConfirmTimeout = savedInstanceState.getBoolean(USER_TIMEOUT);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(USER_TIMEOUT, mUserConfirmTimeout);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);
    }

    public boolean onPreferenceChange(Preference preference, Object newValue) {
        return true;
    }

    // Not used
    public void beforeTextChanged(CharSequence s, int start, int before, int after) {
    }

    // Not used
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    public void afterTextChanged(android.text.Editable s) {
        if (s.length() > 0) {
            mOkButton.setEnabled(true);
        }
    }
}
