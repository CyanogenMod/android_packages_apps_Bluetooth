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

import android.content.Context;
import android.preference.Preference;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;

/**
 * BluetoothDevicePickerDevicePreference is the preference type used to display
 * each remote Bluetooth device in the Device Picker screen.
 */
public class BluetoothDevicePickerDevicePreference extends Preference implements
        BluetoothDevicePickerDevice.Callback {
    // public class BluetoothDevicePickerDevicePreference extends Preference{
    private static final String TAG = "BluetoothDevicePickerDevicePreference";

    private static int sDimAlpha = Integer.MIN_VALUE;

    private BluetoothDevicePickerDevice mLocalDevice;

    private boolean mIsBusy;

    public BluetoothDevicePickerDevicePreference(Context context,
            BluetoothDevicePickerDevice localDevice) {
        super(context);

        if (sDimAlpha == Integer.MIN_VALUE) {
            TypedValue outValue = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.disabledAlpha, outValue, true);
            sDimAlpha = (int)(outValue.getFloat() * 255);
        }

        mLocalDevice = localDevice;

        setLayoutResource(R.layout.preference_bluetooth);

        localDevice.registerCallback(this);

        onDeviceAttributesChanged(localDevice);
    }

    public BluetoothDevicePickerDevice getDevice() {
        return mLocalDevice;
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        mLocalDevice.unregisterCallback(this);
    }

    public void onDeviceAttributesChanged(BluetoothDevicePickerDevice device) {
        setTitle(mLocalDevice.getName());
        setSummary(mLocalDevice.getSummary());
        mIsBusy = false;
        notifyChanged();
        notifyHierarchyChanged();
    }

    @Override
    public boolean isEnabled() {
        return super.isEnabled() && !mIsBusy;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);

        ImageView btClass = (ImageView)view.findViewById(R.id.btClass);
        btClass.setImageResource(mLocalDevice.getBtClassDrawable());
        btClass.setAlpha(isEnabled() ? 255 : sDimAlpha);
    }

    @Override
    public int compareTo(Preference another) {
        if (!(another instanceof BluetoothDevicePickerDevicePreference)) {
            return 1;
        }

        return mLocalDevice
                .compareTo(((BluetoothDevicePickerDevicePreference)another).mLocalDevice);
    }

}
