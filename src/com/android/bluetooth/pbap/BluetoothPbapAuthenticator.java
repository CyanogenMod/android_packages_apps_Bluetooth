
package com.android.bluetooth.pbap;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import javax.obex.Authenticator;
import javax.obex.PasswordAuthentication;

/**
 * BluetoothPbapAuthenticator is a used by BluetoothObexServer for obex
 * authentication procedure.
 */
public class BluetoothPbapAuthenticator implements Authenticator {
    private static final String TAG = "BluetoothPbapAuthenticator";

    private boolean mChallenged;

    private boolean mAuthCancelled;

    private String mSessionKey;

    private Handler mCallback;

    public BluetoothPbapAuthenticator(final Handler callback) {
        mCallback = callback;
        mChallenged = false;
        mAuthCancelled = false;
        mSessionKey = null;
    }

    public final void setChallenged(final boolean bool) {
        mChallenged = bool;
    }

    public final synchronized void setCancelled(final boolean bool) {
        mAuthCancelled = bool;
    }

    public final void setSessionKey(final String string) {
        mSessionKey = string;
    }

    private void waitUserConfirmation() {
        Message msg = Message.obtain(mCallback);
        msg.what = BluetoothPbapService.MSG_OBEX_AUTH_CHALL;
        msg.sendToTarget();
        synchronized (this) {
            while (!mChallenged && !mAuthCancelled) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting on isChalled");
                }
            }
        }
    }

    public PasswordAuthentication onAuthenticationChallenge(final String description,
            final boolean isUserIdRequired, final boolean isFullAccess) {
        waitUserConfirmation();
        if (mSessionKey.trim().length() != 0) {
            PasswordAuthentication pa = new PasswordAuthentication(null, mSessionKey.getBytes());
            return pa;
        }
        return null;
    }

    // TODO reserved for future use only.In case PSE challenge PCE
    public byte[] onAuthenticationResponse(final byte[] userName) {
        byte[] b = null;
        return b;
    }
}
