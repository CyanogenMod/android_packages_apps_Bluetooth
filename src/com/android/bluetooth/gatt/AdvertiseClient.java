
package com.android.bluetooth.gatt;

import android.bluetooth.BluetoothLeAdvertiseScanData.AdvertisementData;
import android.bluetooth.BluetoothLeAdvertiser.Settings;

/**
 * @hide
 */
class AdvertiseClient {
    int clientIf;
    Settings settings;
    AdvertisementData data;

    AdvertiseClient(int clientIf, Settings settings, AdvertisementData data) {
        this.clientIf = clientIf;
        this.settings = settings;
        this.data = data;
    }
}
