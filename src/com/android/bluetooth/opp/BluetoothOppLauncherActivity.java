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

import java.util.ArrayList;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.provider.Settings;

/**
 * This class is designed to act as the entry point of handling the share intent
 * via BT from other APPs. and also make "Bluetooth" available in sharing
 * method selection dialog.
 */
public class BluetoothOppLauncherActivity extends Activity {
    private static final String TAG = "BluetoothLauncherActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String action = intent.getAction();

        if (action.equals(Intent.ACTION_SEND)
                || action.equals("android.intent.action.ACTION_SHARE_MULTIPLE")) {
            /*
             * Other application is trying to share a file via Bluetooth,
             * probably Pictures, videos, or vCards. The Intent should contain
             * an EXTRA_STREAM with the data to attach.
             */
            if (action.equals(Intent.ACTION_SEND)) {
                // TODO(Moto): handle type == null case
                // TODO(Moto): handle extra data is filename case
                String type = intent.getType();
                Uri stream = (Uri)intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (stream != null && type != null) {
                    if (Constants.LOGVV) {
                        Log.v(TAG, "Get ACTION_SEND intent: Uri = " + stream + "; mimetype = "
                                + type);
                    }
                    // Save type/stream, will be used when adding transfer
                    // session to DB.
                    BluetoothOppManager.getInstance(this).saveSendingFileInfo(type,
                            stream.toString());
                } else {
                    Log
                            .e(TAG,
                                    "type is null; or sending file URI - getParcelableExtra(Intent.EXTRA_STREAM) == null");
                    finish();
                    return;
                }
            } else if (action.equals("android.intent.action.ACTION_SHARE_MULTIPLE")) {
                // TODO(Moto): how about send Uri list, not string list?
                // TODO(Moto): Later will use Intent.ACTION_SEND_MULTIPLE
                // Convince media team change
                ArrayList<String> uris = new ArrayList<String>();
                String mimeType = intent.getType();
                uris = intent.getStringArrayListExtra(Intent.EXTRA_STREAM);
                if (mimeType != null && uris != null) {
                    if (Constants.LOGVV) {
                        Log.v(TAG, "Get ACTION_SHARE_MULTIPLE intent: uris " + uris + "\n Type= "
                                + mimeType);
                    }
                    BluetoothOppManager.getInstance(this).saveSendingFileInfo(mimeType, uris);
                } else {
                    Log
                            .e(TAG,
                                    "type is null; or sending files URIs - getStringArrayListExtra(Intent.EXTRA_STREAM) == null");
                    finish();
                    return;
                }
            }

            if (isAirplaneModeOn()) {
                Intent in = new Intent(this, BluetoothOppBtErrorActivity.class);
                in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                in.putExtra("title", this.getString(R.string.airplane_error_title));
                in.putExtra("content", this.getString(R.string.airplane_error_msg));
                this.startActivity(in);

                finish();
                return;
            }

            // TODO(Moto): In the future, we may send intent to DevicePickerActivity
            // directly,
            // and let DevicePickerActivity to handle Bluetooth Enable.
            if (!BluetoothOppManager.getInstance(this).isEnabled()) {
                if (Constants.LOGVV) {
                    Log.v(TAG, "Prepare Enable BT!! ");
                }
                Intent in = new Intent(this, BluetoothOppBtEnableActivity.class);
                in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                this.startActivity(in);
            } else {
                if (Constants.LOGVV) {
                    Log.v(TAG, "BT already enabled!! ");
                }
                Intent in1 = new Intent(this, BluetoothDevicePickerActivity.class);
                in1.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                this.startActivity(in1);
            }
        } else if (action.equals(Constants.ACTION_OPEN)) {
            Uri uri = getIntent().getData();
            if (Constants.LOGVV) {
                Log.v(TAG, "Get ACTION_OPEN intent: Uri = " + uri);
            }

            Intent intent1 = new Intent();
            intent1.setAction(action);
            intent1.setClassName(Constants.THIS_PACKAGE_NAME, BluetoothOppReceiver.class.getName());
            intent1.setData(uri);
            this.sendBroadcast(intent1);
        }
        finish();
        // setContentView(R.layout.main);
    }

    /* Returns true if airplane mode is currently on */
    private final boolean isAirplaneModeOn() {
        return Settings.System.getInt(this.getContentResolver(), Settings.System.AIRPLANE_MODE_ON,
                0) == 1;
    }
}
