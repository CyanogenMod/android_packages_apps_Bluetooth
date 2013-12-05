/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.btservice;

import com.android.bluetooth.btservice.QAdapterService;

final class QJniCallbacks {

    private QAdapterProperties mQAdapterProperties;
    private QAdapterService    mQAdapterSvc = null;

    QJniCallbacks(QAdapterProperties adapterProperties, QAdapterService service) {
        mQAdapterProperties = adapterProperties;
        mQAdapterSvc = service;
    }

    void cleanup() {
        mQAdapterProperties = null;
        mQAdapterSvc = null;
    }

    public Object Clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    void onLeExtendedScanResult(String address, int rssi, byte[] adv_data) {
        if(mQAdapterSvc != null) {
            mQAdapterSvc.onLeExtendedScanResult(address, rssi, adv_data);
        }
    }

    void onLeLppWriteRssiThreshold(String address, int status){
        if (mQAdapterSvc != null) {
            mQAdapterSvc.onLeLppWriteRssiThreshold(address, status);
        }
    }

    void onLeLppReadRssiThreshold(String address, int low, int upper,
                                  int alert, int status) {
        if (mQAdapterSvc != null) {
            mQAdapterSvc.onLeLppReadRssiThreshold(address, low, upper, alert, status);
        }
    }

    void onLeLppEnableRssiMonitor(String address, int enable, int status) {
        if (mQAdapterSvc != null) {
            mQAdapterSvc.onLeLppEnableRssiMonitor(address, enable, status);
        }
    }

    void onLeLppRssiThresholdEvent(String address, int evtType, int rssi) {
        if (mQAdapterSvc != null) {
            mQAdapterSvc.onLeLppRssiThresholdEvent(address, evtType, rssi);
        }
    }

}
