
package com.android.bluetooth.gatt;

import android.annotation.Nullable;
import android.bluetooth.le.AdvertisementData;
import android.bluetooth.le.AdvertiseSettings;

/**
 * @hide
 */
class AdvertiseClient {
    int clientIf;
    AdvertiseSettings settings;
    AdvertisementData advertiseData;
    @Nullable
    AdvertisementData scanResponse;

    AdvertiseClient(int clientIf, AdvertiseSettings settings, AdvertisementData data,
            AdvertisementData scanResponse) {
        this.clientIf = clientIf;
        this.settings = settings;
        this.advertiseData = data;
        this.scanResponse = scanResponse;
    }
}
