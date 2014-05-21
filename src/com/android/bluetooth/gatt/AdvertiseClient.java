
package com.android.bluetooth.gatt;

import android.annotation.Nullable;
import android.bluetooth.BluetoothLeAdvertiseScanData.AdvertisementData;
import android.bluetooth.BluetoothLeAdvertiser.Settings;

/**
 * @hide
 */
class AdvertiseClient {
    int clientIf;
    Settings settings;
    AdvertisementData advertiseData;
    @Nullable
    AdvertisementData scanResponse;

    AdvertiseClient(int clientIf, Settings settings, AdvertisementData data,
            AdvertisementData scanResponse) {
        this.clientIf = clientIf;
        this.settings = settings;
        this.advertiseData = data;
        this.scanResponse = scanResponse;
    }
}
