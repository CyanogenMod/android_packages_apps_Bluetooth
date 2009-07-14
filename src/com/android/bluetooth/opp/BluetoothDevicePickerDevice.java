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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothClass;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * BluetoothDevicePickerDevice represents a remote Bluetooth device. It contains
 * attributes of the device (such as the address, name, RSSI, etc.) and
 * functionality that can be performed on the device (pair, etc.).
 */
public class BluetoothDevicePickerDevice implements Comparable<BluetoothDevicePickerDevice> {
    private static final String TAG = "BluetoothDevicePickerDevice";

    private static final boolean V = BluetoothDevicePickerDevice.V;

    private final String mAddress;

    private String mName;

    private short mRssi;

    private int mBtClass = BluetoothClass.ERROR;

    private boolean mVisible;

    private int mPairingStatus;

    private final BluetoothDevicePickerManager mLocalManager;

    private List<Callback> mCallbacks = new ArrayList<Callback>();

    BluetoothDevicePickerDevice(Context context, String address) {
        mLocalManager = BluetoothDevicePickerManager.getInstance(context);
        if (mLocalManager == null) {
            throw new IllegalStateException(
                    "Cannot use BluetoothDevicePickerManager without Bluetooth hardware");
        }

        mAddress = address;

        fillData();
    }

    public void onClicked() {
        Log.v(TAG, "onClicked");
        int pairingStatus = getPairingStatus();

        if (pairingStatus == BluetoothDevicePickerBtStatus.PAIRING_STATUS_PAIRED) {
            // send intent to OPP
            Log.v(TAG, "onClicked: PAIRING_STATUS_PAIRED");
            sendToOpp();
        } else if (pairingStatus == BluetoothDevicePickerBtStatus.PAIRING_STATUS_UNPAIRED) {
            Log.v(TAG, "onClicked: PAIRING_STATUS_UNPAIRED");
            pair();
        }
    }

    public void sendToOpp() {
        if (!ensurePaired())
            return;
    }

    private boolean ensurePaired() {
        if (getPairingStatus() == BluetoothDevicePickerBtStatus.PAIRING_STATUS_UNPAIRED) {
            pair();
            return false;
        } else {
            return true;
        }
    }

    public void pair() {
        BluetoothDevice manager = mLocalManager.getBluetoothManager();
        Log.v(TAG, "pair enter");

        // Pairing doesn't work if scanning, so cancel
        if (manager.isDiscovering()) {
            Log.v(TAG, "is discovering");
            manager.cancelDiscovery();
        }

        if (mLocalManager.createBonding(mAddress)) {
            Log.v(TAG, "set status: PAIRING_STATUS_PAIRING");
            setPairingStatus(BluetoothDevicePickerBtStatus.PAIRING_STATUS_PAIRING);
        }
        Log.v(TAG, "pair exit");
    }

    public void unpair() {
        BluetoothDevice manager = mLocalManager.getBluetoothManager();

        switch (getPairingStatus()) {
            case BluetoothDevicePickerBtStatus.PAIRING_STATUS_PAIRED:
                manager.removeBond(mAddress);
                break;

            case BluetoothDevicePickerBtStatus.PAIRING_STATUS_PAIRING:
                manager.cancelBondProcess(mAddress);
                break;
        }
    }

    private void fillData() {
        BluetoothDevice manager = mLocalManager.getBluetoothManager();
        fetchName();
        mBtClass = manager.getRemoteClass(mAddress);
        mPairingStatus = manager.getBondState(mAddress);
        mVisible = false;
        dispatchAttributesChanged();
    }

    public String getAddress() {
        return mAddress;
    }

    public String getName() {
        return mName;
    }

    public void refreshName() {
        fetchName();
        dispatchAttributesChanged();
    }

    private void fetchName() {
        mName = mLocalManager.getBluetoothManager().getRemoteName(mAddress);

        if (TextUtils.isEmpty(mName)) {
            mName = mAddress;
        }
    }

    public void refresh() {
        dispatchAttributesChanged();
    }

    public boolean isVisible() {
        return mVisible;
    }

    void setVisible(boolean visible) {
        if (mVisible != visible) {
            mVisible = visible;
            dispatchAttributesChanged();
        }
    }

    public int getPairingStatus() {
        return mPairingStatus;
    }

    void setPairingStatus(int pairingStatus) {
        if (mPairingStatus != pairingStatus) {
            mPairingStatus = pairingStatus;
            dispatchAttributesChanged();
        }
    }

    void setRssi(short rssi) {
        if (mRssi != rssi) {
            mRssi = rssi;
            dispatchAttributesChanged();
        }
    }

    public int getBtClassDrawable() {
        switch (BluetoothClass.Device.Major.getDeviceMajor(mBtClass)) {
            case BluetoothClass.Device.Major.COMPUTER:
                return R.drawable.ic_bt_laptop;

            case BluetoothClass.Device.Major.PHONE:
                return R.drawable.ic_bt_cellphone;

            default:
                return 0;
        }
    }

    public int getSummary() {
        int pairingStatus = getPairingStatus();
        return BluetoothDevicePickerBtStatus.getPairingStatusSummary(pairingStatus);
    }

    public void registerCallback(Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.add(callback);
        }
    }

    public void unregisterCallback(Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
        }
    }

    private void dispatchAttributesChanged() {
        synchronized (mCallbacks) {
            for (Callback callback : mCallbacks) {
                callback.onDeviceAttributesChanged(this);
            }
        }
    }

    @Override
    public String toString() {
        return mAddress;
    }

    @Override
    public boolean equals(Object o) {
        if ((o == null) || !(o instanceof BluetoothDevicePickerDevice)) {
            throw new ClassCastException();
        }

        return mAddress.equals(((BluetoothDevicePickerDevice)o).mAddress);
    }

    @Override
    public int hashCode() {
        return mAddress.hashCode();
    }

    public int compareTo(BluetoothDevicePickerDevice another) {
        int comparison;

        // Paired above not paired
        comparison = (another.mPairingStatus == BluetoothDevicePickerBtStatus.PAIRING_STATUS_PAIRED ? 1
                : 0)
                - (mPairingStatus == BluetoothDevicePickerBtStatus.PAIRING_STATUS_PAIRED ? 1 : 0);
        if (comparison != 0)
            return comparison;

        // Visible above not visible
        comparison = (another.mVisible ? 1 : 0) - (mVisible ? 1 : 0);
        if (comparison != 0)
            return comparison;

        // Stronger signal above weaker signal
        comparison = another.mRssi - mRssi;
        if (comparison != 0)
            return comparison;

        // Fallback on name
        return getName().compareTo(another.getName());
    }

    public interface Callback {
        void onDeviceAttributesChanged(BluetoothDevicePickerDevice device);
    }
}
