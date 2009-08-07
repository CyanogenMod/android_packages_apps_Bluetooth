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

import java.util.List;
import java.util.WeakHashMap;

import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.util.Log;
import android.util.Config;
import android.content.Intent;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

public class BluetoothDevicePickerActivity extends PreferenceActivity implements
        BluetoothDevicePickerManager.Callback {
    // public class BluetoothDevicePickerManager extends Activity {
    static final int BASIC_PANEL = 0;

    static final int ADVANCED_PANEL = 1;

    private static final String LOG_TAG = "-------------BluetoothDevicePickerActivity";

    private static final boolean DEBUG = true;

    private static final boolean LOG_ENABLED = DEBUG ? Config.LOGD : Config.LOGV;

    /* for device picker */
    private static final String KEY_BT_DEVICE_LIST = "bt_device_list";

    private static final String KEY_BT_SCAN = "bt_scan";

    private ProgressCategory mDeviceList;

    private WeakHashMap<BluetoothDevicePickerDevice, BluetoothDevicePickerDevicePreference> mDevicePreferenceMap = new WeakHashMap<BluetoothDevicePickerDevice, BluetoothDevicePickerDevicePreference>();

    private BluetoothDevicePickerManager mLocalManager;

    @Override
    public void onCreate(Bundle icicle) {
        log("onCreate");
        super.onCreate(icicle);

        addPreferencesFromResource(R.layout.device_picker);
        mLocalManager = BluetoothDevicePickerManager.getInstance(this);
        if (mLocalManager == null) {
            log("mLocalManager is null");
            finish();
        }

        mDeviceList = (ProgressCategory)findPreference(KEY_BT_DEVICE_LIST);
    }

    @Override
    protected void onResume() {
        super.onResume();
        log("onResume");
        mDevicePreferenceMap.clear();

        mDeviceList.removeAll();
        if (mLocalManager != null) {
            mLocalManager.startBluetoothEventListener();
            addDevices();
            mLocalManager.registerCallback(this);
            mLocalManager.setForegroundActivity(this);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mLocalManager.unregisterCallback(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mLocalManager != null) {
            mLocalManager.stopBluetoothEventListener();
        }
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen,
            Preference preference) {
        log("onPreferenceTreeClick");

        if (KEY_BT_SCAN.equals(preference.getKey())) {
            log("begin to scan");
            mLocalManager.startScanning(true);
            return super.onPreferenceTreeClick(preferenceScreen, preference);
        } else if (preference instanceof BluetoothDevicePickerDevicePreference) {
            log("user choosed a device");
            BluetoothDevicePickerDevicePreference btPreference =
                    (BluetoothDevicePickerDevicePreference) preference;
            BluetoothDevicePickerDevice device = btPreference.getDevice();
            // device.onClicked();

            if (device.getPairingStatus() == BluetoothDevicePickerBtStatus.PAIRING_STATUS_PAIRED) {
                log("the device chosed is paired, send intent to" +
                        " OPP with the BT device and finish this activity.");
                BluetoothDevice remoteDevice = btPreference.getDevice().getRemoteDevice();
                Intent intent = new Intent(BluetoothShare.BLUETOOTH_DEVICE_SELECTED_ACTION);
                intent.setClassName(Constants.THIS_PACKAGE_NAME, BluetoothOppReceiver.class
                        .getName());
                intent.putExtra("BT_DEVICE", remoteDevice);
                this.sendBroadcast(intent);
                super.onPreferenceTreeClick(preferenceScreen, preference);
                finish();
            } else {
                log("the device chosed is NOT paired, try to pair" +
                        " with it and wait for bond request from bluez");
                device.pair();
            }

            return super.onPreferenceTreeClick(preferenceScreen, preference);
        }

        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    public void onScanningStateChanged(boolean started) {
        log("onScanningStateChanged");
        mDeviceList.setProgress(started);
    }

    public void onDeviceAdded(BluetoothDevicePickerDevice device) {
        log("onDeviceAdded");

        if (mDevicePreferenceMap.get(device) != null) {
            throw new IllegalStateException("Got onDeviceAdded, but device already exists");
        }

        createDevicePreference(device);
    }

    public void onDeviceDeleted(BluetoothDevicePickerDevice device) {
        BluetoothDevicePickerDevicePreference preference = mDevicePreferenceMap.remove(device);
        if (preference != null) {
            mDeviceList.removePreference(preference);
        }
    }

    public void onBondingStateChanged(BluetoothDevice remoteDevice, boolean created) {
        log("onBondingStateChanged");
        if (created == true) {
            Intent intent = new Intent(BluetoothShare.BLUETOOTH_DEVICE_SELECTED_ACTION);
            intent.setClassName(Constants.THIS_PACKAGE_NAME, BluetoothOppReceiver.class.getName());
            intent.putExtra("BT_DEVICE", remoteDevice);
            this.sendBroadcast(intent);
            log("the device bond succeeded, send intent to OPP");
        }

        finish();
    }

    private void addDevices() {
        List<BluetoothDevicePickerDevice> devices = mLocalManager.getLocalDeviceManager()
                .getDevicesCopy();
        for (BluetoothDevicePickerDevice device : devices) {
            onDeviceAdded(device);
        }
    }

    private void createDevicePreference(BluetoothDevicePickerDevice device) {
        log("createDevicePreference");
        BluetoothDevicePickerDevicePreference preference = new BluetoothDevicePickerDevicePreference(
                this, device);
        mDeviceList.addPreference(preference);
        mDevicePreferenceMap.put(device, preference);
    }

    static void log(String message) {
        if (LOG_ENABLED) {
            Log.v(LOG_TAG, message);
        }
    }
}
