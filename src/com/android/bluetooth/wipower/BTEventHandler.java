/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *            notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *            notice, this list of conditions and the following disclaimer in the
 *            documentation and/or other materials provided with the distribution.
 *        * Neither the name of The Linux Foundation nor
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

package com.android.bluetooth.wipower;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;
import android.util.Log;

import java.lang.Object;

public class BTEventHandler extends BroadcastReceiver {
    private static final String TAG = "BTEventHandler";
    private static final boolean V = false/*Constants.VERBOSE*/;
    private int state;

    @Override
    public void onReceive(Context context, Intent intent) {
        if(SystemProperties.getBoolean("ro.bluetooth.wipower", false) == false) {
            Log.e(TAG, "A4WP is not supported");
            return;
        }

        String action = intent.getAction();

        if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
            state = intent.getIntExtra
                           (BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (BluetoothAdapter.STATE_ON == state) {
                if (V) Log.e(TAG, "Received BLUETOOTH_STATE_ON");

                ComponentName service = context.startService
                                      (new Intent(context, WipowerService.class));
                if (service != null) {
                    Log.e(TAG, "Wipower service started successfully");
                } else {
                    Log.e(TAG, "Could Not Start Wipower Service ");
                    return;
                }


            } else if (BluetoothAdapter.STATE_OFF == state) {
                if (V) Log.v(TAG, "Received BLUETOOTH_STATE_OFF");
                context.stopService(new Intent(context, WipowerService.class));
           }
        }
    }
}
