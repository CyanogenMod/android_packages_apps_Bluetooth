
package com.android.bluetooth.gatt;

import android.annotation.Nullable;
import android.bluetooth.le.AdvertisementData;
import android.bluetooth.le.BluetoothLeAdvertiseSettings;

/**
 * @hide
 */
class AdvertiseClient {
    int clientIf;
    BluetoothLeAdvertiseSettings settings;
    AdvertisementData advertiseData;
    @Nullable
    AdvertisementData scanResponse;

    AdvertiseClient(int clientIf, BluetoothLeAdvertiseSettings settings, AdvertisementData data,
            AdvertisementData scanResponse) {
        this.clientIf = clientIf;
        this.settings = settings;
        this.advertiseData = data;
        this.scanResponse = scanResponse;
    }
}
