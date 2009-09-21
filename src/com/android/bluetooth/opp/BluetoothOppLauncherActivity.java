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

import java.util.ArrayList;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothDevicePicker;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.provider.Settings;

/**
 * This class is designed to act as the entry point of handling the share intent
 * via BT from other APPs. and also make "Bluetooth" available in sharing method
 * selection dialog.
 */
public class BluetoothOppLauncherActivity extends Activity {
    private static final String TAG = "BluetoothLauncherActivity";
    private static final boolean D = Constants.DEBUG;
    private static final boolean V = Constants.VERBOSE;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String action = intent.getAction();

        if (action.equals(Intent.ACTION_SEND) || action.equals(Intent.ACTION_SEND_MULTIPLE)) {
            /*
             * Other application is trying to share a file via Bluetooth,
             * probably Pictures, videos, or vCards. The Intent should contain
             * an EXTRA_STREAM with the data to attach.
             */
            if (action.equals(Intent.ACTION_SEND)) {
                // TODO(Moto): handle type == null case
                String type = intent.getType();
                Uri stream = (Uri)intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (stream != null && type != null) {
                    if (V) Log.v(TAG, "Get ACTION_SEND intent: Uri = " + stream + "; mimetype = "
                                + type);
                    // Save type/stream, will be used when adding transfer
                    // session to DB.
                    BluetoothOppManager.getInstance(this).saveSendingFileInfo(type,
                            stream.toString());
                } else {
                    Log.e(TAG, "type is null; or sending file URI is null");
                    finish();
                    return;
                }
            } else if (action.equals(Intent.ACTION_SEND_MULTIPLE)) {
                ArrayList<Uri> uris = new ArrayList<Uri>();
                String mimeType = intent.getType();
                uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (mimeType != null && uris != null) {
                    if (V) Log.v(TAG, "Get ACTION_SHARE_MULTIPLE intent: uris " + uris + "\n Type= "
                                + mimeType);
                    BluetoothOppManager.getInstance(this).saveSendingFileInfo(mimeType, uris);
                } else {
                    Log.e(TAG, "type is null; or sending files URIs are null");
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
                if (V) Log.v(TAG, "Prepare Enable BT!! ");
                Intent in = new Intent(this, BluetoothOppBtEnableActivity.class);
                in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                this.startActivity(in);
            } else {
                if (V) Log.v(TAG, "BT already enabled!! ");
                Intent in1 = new Intent(BluetoothDevicePicker.ACTION_LAUNCH);
                in1.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                in1.putExtra(BluetoothDevicePicker.EXTRA_NEED_AUTH, false);
                in1.putExtra(BluetoothDevicePicker.EXTRA_FILTER_TYPE,
                        BluetoothDevicePicker.FILTER_TYPE_TRANSFER);
                in1.putExtra(BluetoothDevicePicker.EXTRA_LAUNCH_PACKAGE,
                        Constants.THIS_PACKAGE_NAME);
                in1.putExtra(BluetoothDevicePicker.EXTRA_LAUNCH_CLASS,
                        BluetoothOppReceiver.class.getName());

                this.startActivity(in1);
            }
        } else if (action.equals(Constants.ACTION_OPEN)) {
            Uri uri = getIntent().getData();
            if (V) Log.v(TAG, "Get ACTION_OPEN intent: Uri = " + uri);

            Intent intent1 = new Intent();
            intent1.setAction(action);
            intent1.setClassName(Constants.THIS_PACKAGE_NAME, BluetoothOppReceiver.class.getName());
            intent1.setData(uri);
            this.sendBroadcast(intent1);
        }
        finish();
    }

    /* Returns true if airplane mode is currently on */
    private final boolean isAirplaneModeOn() {
        return Settings.System.getInt(this.getContentResolver(), Settings.System.AIRPLANE_MODE_ON,
                0) == 1;
    }
}
