/*
 * Copyright (C) 2012 Google Inc.
 */

package com.android.bluetooth.btservice;

final class JniCallbacks {

    private static JniCallbacks sInstance;
    private RemoteDevices mRemoteDevices;
    private AdapterProperties mAdapterProperties;
    private AdapterState mAdapterStateMachine;
    private BondStateMachine mBondStateMachine;

    private JniCallbacks(RemoteDevices remoteDevices, AdapterProperties adapterProperties,
            AdapterState adapterStateMachine, BondStateMachine bondStateMachine) {
        mRemoteDevices = remoteDevices;
        mAdapterProperties = adapterProperties;
        mAdapterStateMachine = adapterStateMachine;
        mBondStateMachine = bondStateMachine;
    }

    static synchronized JniCallbacks getInstance(RemoteDevices remoteDevices,
            AdapterProperties adapterProperties, AdapterState adapterStateMachine,
            BondStateMachine bondStateMachine) {
        if (sInstance == null)  {
            sInstance =
                new JniCallbacks(remoteDevices, adapterProperties, adapterStateMachine,
                        bondStateMachine);
        } else {
            sInstance.mRemoteDevices = remoteDevices;
            sInstance.mAdapterProperties = adapterProperties;
            sInstance.mAdapterStateMachine = adapterStateMachine;
            sInstance.mBondStateMachine = bondStateMachine;
        }
        return sInstance;
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

    void pinRequestCallback(byte[] address, byte[] name, int cod) {
        mRemoteDevices.pinRequestCallback(address, name, cod);
    }

    void bondStateChangeCallback(int status, byte[] address, int newState) {
        mBondStateMachine.bondStateChangeCallback(status, address, newState);
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

}
