package com.android.bluetooth.btservice;

import java.util.ArrayList;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import com.android.bluetooth.R;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.hdp.HealthService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.hid.HidService;
import com.android.bluetooth.pan.PanService;

public class Config {
    private static final String TAG = "AdapterServiceConfig";
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
        PanService.class
    };
    /**
     * Resource flag to indicate whether profile is supported or not.
     */
    private static final int[]  PROFILE_SERVICES_FLAG = {
        R.bool.profile_supported_hs_hfp,
        R.bool.profile_supported_a2dp,
        R.bool.profile_supported_hid,
        R.bool.profile_supported_hdp,
        R.bool.profile_supported_pan
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
