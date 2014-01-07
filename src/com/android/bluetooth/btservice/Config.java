/*
 * Copyright (C) 2013 The Linux Foundation. All rights reserved
 * Not a Contribution.
 * Copyright (C) 2012 The Android Open Source Project
 *
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

package com.android.bluetooth.btservice;

import java.util.ArrayList;

import android.content.Context;
import android.content.res.Resources;
import android.os.SystemProperties;
import android.util.Log;

import com.android.bluetooth.R;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.hdp.HealthService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.hfpclient.HandsfreeClientService;
import com.android.bluetooth.hid.HidService;
import com.android.bluetooth.hid.HidDevService;
import com.android.bluetooth.pan.PanService;
import com.android.bluetooth.gatt.GattService;
import com.android.bluetooth.map.BluetoothMapService;

public class Config {
    private static final String TAG = "AdapterServiceConfig";
    private static final int hfpClientNotSupported  = -1;
    private static final int hfpClientSupported  = 1;
    private static int enableHfpclient = 0;
    /**
     * List of profile services.
     */
    @SuppressWarnings("rawtypes")
    //Do not inclue OPP and PBAP, because their services
    //are not managed by AdapterService
    private static final Class[] PROFILE_SERVICES = {
        HeadsetService.class,
        A2dpService.class,
        HidService.class,
        HealthService.class,
        PanService.class,
        GattService.class,
        BluetoothMapService.class,
        HandsfreeClientService.class,
        HidDevService.class
    };
    /**
     * Resource flag to indicate whether profile is supported or not.
     */
    private static final int[]  PROFILE_SERVICES_FLAG = {
        R.bool.profile_supported_hs_hfp,
        R.bool.profile_supported_a2dp,
        R.bool.profile_supported_hid,
        R.bool.profile_supported_hdp,
        R.bool.profile_supported_pan,
        R.bool.profile_supported_gatt,
        R.bool.profile_supported_map,
        R.bool.profile_supported_hfpclient,
        R.bool.profile_supported_hidd
    };

    private static Class[] SUPPORTED_PROFILES = new Class[0];

    static void init(Context ctx) {
        if (ctx == null) {
            return;
        }
        Resources resources = ctx.getResources();
        if (resources == null) {
            return;
        }
        ArrayList<Class> profiles = new ArrayList<Class>(PROFILE_SERVICES.length);
        for (int i=0; i < PROFILE_SERVICES_FLAG.length; i++) {
            boolean supported = resources.getBoolean(PROFILE_SERVICES_FLAG[i]);
            if (supported) {
                Log.d(TAG, "Adding " + PROFILE_SERVICES[i].getSimpleName());
                if (PROFILE_SERVICES[i].getSimpleName().equals("HandsfreeClientService")) {
                    enableHfpclient = SystemProperties.getInt("bluetooth.hfp.client",
                            hfpClientNotSupported);
                    Log.d(TAG, "enableHfpclient " +enableHfpclient);
                    if (enableHfpclient == hfpClientSupported)
                        profiles.add(PROFILE_SERVICES[i]);
                    continue;
                }
                profiles.add(PROFILE_SERVICES[i]);
            }
        }
        int totalProfiles = profiles.size();
        SUPPORTED_PROFILES = new Class[totalProfiles];
        profiles.toArray(SUPPORTED_PROFILES);
    }

    static Class[]  getSupportedProfiles() {
        return SUPPORTED_PROFILES;
    }
}
