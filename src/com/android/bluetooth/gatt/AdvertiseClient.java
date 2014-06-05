/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.bluetooth.gatt;

import android.annotation.Nullable;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisementData;

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
