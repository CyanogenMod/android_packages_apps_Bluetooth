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

final class JniCallbacks {

    private RemoteDevices mRemoteDevices;
    private AdapterProperties mAdapterProperties;
    private AdapterState mAdapterStateMachine;
    private BondStateMachine mBondStateMachine;

    JniCallbacks(AdapterState adapterStateMachine,AdapterProperties adapterProperties) {
        mAdapterStateMachine = adapterStateMachine;
        mAdapterProperties = adapterProperties;
    }

    void init(BondStateMachine bondStateMachine, RemoteDevices remoteDevices) {
        mRemoteDevices = remoteDevices;
        mBondStateMachine = bondStateMachine;
    }

    void cleanup() {
        mRemoteDevices = null;
        mAdapterProperties = null;
        mAdapterStateMachine = null;
        mBondStateMachine = null;
    }

    public Object Clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    void sspRequestCallback(byte[] address, byte[] name, int cod, int pairingVariant,
            int passkey) {
        mRemoteDevices.sspRequestCallback(address, name, cod, pairingVariant,
            passkey);
    }
    void devicePropertyChangedCallback(byte[] address, int[] types, byte[][] val) {
        mRemoteDevices.devicePropertyChangedCallback(address, types, val);
    }

    void deviceFoundCallback(byte[] address) {
        mRemoteDevices.deviceFoundCallback(address);
    }

    void pinRequestCallback(byte[] address, byte[] name, int cod, boolean secure) {
        mRemoteDevices.pinRequestCallback(address, name, cod, secure);
    }

    void bondStateChangeCallback(int status, byte[] address, int newState) {
        mBondStateMachine.bondStateChangeCallback(status, address, newState);
    }

    void aclStateChangeCallback(int status, byte[] address, int newState) {
        mRemoteDevices.aclStateChangeCallback(status, address, newState);
    }
    void wakeStateChangeCallback(int state) {
        mRemoteDevices.wakeStateChangeCallback(state);
    }
    void stateChangeCallback(int status) {
        mAdapterStateMachine.stateChangeCallback(status);
    }

    void discoveryStateChangeCallback(int state) {
        mAdapterProperties.discoveryStateChangeCallback(state);
    }

    void adapterPropertyChangedCallback(int[] types, byte[][] val) {
        mAdapterProperties.adapterPropertyChangedCallback(types, val);
    }

    void deviceMasInstancesFoundCallback(int status, byte[] address, String[] name, int[] scn,
            int[] id, int[] msgtype) {
        mRemoteDevices.deviceMasInstancesFoundCallback(status, address, name, scn, id, msgtype);
    }
}
