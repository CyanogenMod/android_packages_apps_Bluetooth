/*
* Copyright (C) 2013 Samsung System LSI
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
package com.android.bluetooth.map;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import javax.obex.Authenticator;
import javax.obex.PasswordAuthentication;

/**
 * BluetoothMapAuthenticator is a used by BluetoothObexServer for obex
 * authentication procedure.
 */
public class BluetoothMapAuthenticator implements Authenticator {
    private static final String TAG = "BluetoothMapAuthenticator";

    private boolean mChallenged;

    private boolean mAuthCancelled;

    private String mSessionKey;

    private Handler mCallback;

    public BluetoothMapAuthenticator(final Handler callback) {
        mCallback = callback;
        mChallenged = false;
        mAuthCancelled = false;
        mSessionKey = null;
    }

    public final synchronized void setChallenged(final boolean bool) {
        mChallenged = bool;
    }

    public final synchronized void setCancelled(final boolean bool) {
        mAuthCancelled = bool;
    }

    public final synchronized void setSessionKey(final String string) {
        mSessionKey = string;
    }

    private void waitUserConfirmation() {
        Message msg = Message.obtain(mCallback);
        msg.what = BluetoothMapService.MSG_OBEX_AUTH_CHALL;
        msg.sendToTarget();
        synchronized (this) {
            while (!mChallenged && !mAuthCancelled) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting on isChallenged");
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

    // TODO: Reserved for future use only, in case MSE challenge MCE
    public byte[] onAuthenticationResponse(final byte[] userName) {
        byte[] b = null;
        return b;
    }
}
