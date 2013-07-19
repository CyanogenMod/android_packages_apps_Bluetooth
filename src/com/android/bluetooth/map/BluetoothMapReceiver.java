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

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BluetoothMapReceiver extends BroadcastReceiver {

    private static final String TAG = "BluetoothMapReceiver";

    private static final boolean V = BluetoothMapService.VERBOSE;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (V) Log.v(TAG, "MapReceiver onReceive ");

        Intent in = new Intent();
        in.putExtras(intent);
        in.setClass(context, BluetoothMapService.class);
        String action = intent.getAction();
        in.putExtra("action", action);
        if (V) Log.v(TAG,"***********action = " + action);

        boolean startService = true;
        if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            in.putExtra(BluetoothAdapter.EXTRA_STATE, state);
            if (V) Log.v(TAG,"***********state = " + state);
            if ((state == BluetoothAdapter.STATE_TURNING_ON)
                    || (state == BluetoothAdapter.STATE_OFF)) {
                //FIX: We turn on MAP after BluetoothAdapter.STATE_ON,
                //but we turn off MAP right after BluetoothAdapter.STATE_TURNING_OFF
                startService = false;
            }
        } else {
            // Don't forward intent unless device has bluetooth and bluetooth is enabled.
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                startService = false;
            }
        }
        if (startService) {
            if (V) Log.v(TAG,"***********Calling start service!!!! with action = " + in.getAction());
            context.startService(in);
        }
    }
}
