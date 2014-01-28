/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.btservice;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.QBluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelUuid;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;

import java.util.HashMap;
import java.util.ArrayList;

class QAdapterProperties {
    private static final boolean DBG = false;
    private static final boolean VDBG = false;
    private static final String TAG = "QBluetoothAdapterProperties";

    private int mAdvMode;
    private QAdapterService mQService;
    private BluetoothAdapter mAdapter;

    private Object mObject = new Object();

    public QAdapterProperties(QAdapterService service) {
        mQService = service;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        //set the initial value of adv mode here
        mAdvMode=QBluetoothAdapter.ADV_MODE_NONE;
    }
    public void cleanup() {
        mQService = null;
    }
    /**
     * @return the mAdvMode
     */
    int getLEAdvMode() {
        synchronized (mObject) {
            return mAdvMode;
        }
    }

   /** Set the local adapter property - adv LE Mode
     *
     * @param advMode the advMode to set
     */
    boolean setLEAdvMode(int advMode) {
        synchronized (mObject) {
            if(mQService==null)
            {
                debugLog("setLEAdvMode: Handle to the Qadapterservice found null. returning false");
                return false;
            }
            return mQService.setLEAdvModeNative(
                    AbstractionLayer.BT_PROPERTY_ADAPTER_BLE_ADV_MODE, Utils.intToByteArray(advMode));
        }
    }

    void adapterPropertyChangedCallback(int[] types, byte[][] values) {
        Intent intent;
        int type;
        byte[] val;
        for (int i = 0; i < types.length; i++) {
            val = values[i];
            type = types[i];
            infoLog("adapterPropertyChangedCallback with type:" + type + " len:" + val.length);
            synchronized (mObject) {
                switch (type) {
                    case AbstractionLayer.BT_PROPERTY_ADAPTER_BLE_ADV_MODE:
                        int advMode=Utils.byteArrayToInt(val,0);
                        mAdvMode=mQService.convertAdvModeFromHal(advMode);
                        //create intent if required
                        infoLog("For property 13 LE_ADV_SET, Adv mode set to:"+ mAdvMode);
                        break;
                    default:
                        errorLog("Property change not handled in Java land:" + type);
                }
            }
        }
    }
    void advEnableCallback(int advEnable, int advType){
        debugLog("advEnableCallback");
        infoLog("advEnableCallback called with advEnable: "+ advEnable + " advType: " + advType);
        Intent intent;
        mAdvMode=mQService.convertAdvModeFromHal(advType);
        infoLog("Adv Mode changed to:" + mAdvMode);

        intent = new Intent(QBluetoothAdapter.ACTION_ADV_ENABLE_CHANGED);
        intent.putExtra(QBluetoothAdapter.EXTRA_ADV_ENABLE, mAdvMode);
        mQService.sendBroadcast(intent,mQService.BLUETOOTH_PERM);
        infoLog("advEnableCallback Intent Sent");
    }
    private void infoLog(String msg) {
        if (DBG) Log.i(TAG, msg);
    }

    private void debugLog(String msg) {
        if (DBG) Log.d(TAG, msg);
    }

    private void errorLog(String msg) {
        Log.e(TAG, msg);
    }
}
