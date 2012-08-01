/*
 * Copyright (C) 2012 Google Inc.
 */

package com.android.bluetooth.btservice;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Message;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.util.ArrayList;

/**
 * This state machine handles Bluetooth Adapter State.
 * States:
 *      {@link StableState} :  No device is in bonding / unbonding state.
 *      {@link PendingCommandState} : Some device is in bonding / unbonding state.
 * TODO(BT) This class can be removed and this logic moved to the stack.
 */

final class BondStateMachine extends StateMachine {
    private static final boolean DBG = true;
    private static final String TAG = "BluetoothBondStateMachine";

    static final int CREATE_BOND = 1;
    static final int CANCEL_BOND = 2;
    static final int REMOVE_BOND = 3;
    static final int BONDING_STATE_CHANGE = 4;

    static final int BOND_STATE_NONE = 0;
    static final int BOND_STATE_BONDING = 1;
    static final int BOND_STATE_BONDED = 2;

    private AdapterService mAdapterService;
    private AdapterProperties mAdapterProperties;
    private RemoteDevices mRemoteDevices;
    private BluetoothAdapter mAdapter;

    private PendingCommandState mPendingCommandState = new PendingCommandState();
    private StableState mStableState = new StableState();

    public BondStateMachine(AdapterService service,
            AdapterProperties prop, RemoteDevices remoteDevices) {
        super("BondStateMachine:");
        addState(mStableState);
        addState(mPendingCommandState);
        mRemoteDevices = remoteDevices;
        mAdapterService = service;
        mAdapterProperties = prop;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        setInitialState(mStableState);
    }

    public void doQuit() {
        quitNow();
    }

    public void cleanup() {
        mAdapterService = null;
        mRemoteDevices = null;
        mAdapterProperties = null;
    }

    private class StableState extends State {
        @Override
        public void enter() {
            infoLog("StableState(): Entering Off State");
        }

        @Override
        public boolean processMessage(Message msg) {

            BluetoothDevice dev = (BluetoothDevice)msg.obj;

            switch(msg.what) {

              case CREATE_BOND:
                  createBond(dev, true);
                  break;
              case REMOVE_BOND:
                  removeBond(dev, true);
                  break;
              case BONDING_STATE_CHANGE:
                int newState = msg.arg1;
                /* if incoming pairing, transition to pending state */
                if (newState == BluetoothDevice.BOND_BONDING)
                {
                    sendIntent(dev, newState);
                    transitionTo(mPendingCommandState);
                }
                else
                {
                    Log.e(TAG, "In stable state, received invalid newState: " + newState);
                }
                break;

              case CANCEL_BOND:
              default:
                   Log.e(TAG, "Received unhandled state: " + msg.what);
                   return false;
            }
            return true;
        }
    }


    private class PendingCommandState extends State {
        private final ArrayList<BluetoothDevice> mDevices =
            new ArrayList<BluetoothDevice>();

        @Override
        public void enter() {
            infoLog("Entering PendingCommandState State");
            BluetoothDevice dev = (BluetoothDevice)getCurrentMessage().obj;
        }

        @Override
        public boolean processMessage(Message msg) {

            BluetoothDevice dev = (BluetoothDevice)msg.obj;
            boolean result = false;
            if (mDevices.contains(dev) &&
                    msg.what != CANCEL_BOND && msg.what != BONDING_STATE_CHANGE) {
                deferMessage(msg);
                return true;
            }

            switch (msg.what) {
                case CREATE_BOND:
                    result = createBond(dev, false);
                    break;
                case REMOVE_BOND:
                    result = removeBond(dev, false);
                    break;
                case CANCEL_BOND:
                    result = cancelBond(dev);
                    break;
                case BONDING_STATE_CHANGE:
                    int newState = msg.arg1;
                    sendIntent(dev, newState);
                    if(newState != BluetoothDevice.BOND_BONDING )
                    {
                        /* this is either none/bonded, remove and transition */
                        result = !mDevices.remove(dev);
                        if (mDevices.isEmpty()) {
                            // Whenever mDevices is empty, then we need to
                            // set result=false. Else, we will end up adding
                            // the device to the list again. This prevents us
                            // from pairing with a device that we just unpaired
                            result = false;
                            transitionTo(mStableState);
                        }
                    }
                    else if(!mDevices.contains(dev))
                        result=true;
                    break;
                default:
                    Log.e(TAG, "Received unhandled event:" + msg.what);
                    return false;
            }
            if (result) mDevices.add(dev);

            return true;
        }
    }

    private boolean cancelBond(BluetoothDevice dev) {
        if (dev.getBondState() == BluetoothDevice.BOND_BONDING) {
            byte[] addr = Utils.getBytesFromAddress(dev.getAddress());
            if (!mAdapterService.cancelBondNative(addr)) {
               Log.e(TAG, "Unexpected error while cancelling bond:");
            } else {
                return true;
            }
        }
        return false;
    }

    private boolean removeBond(BluetoothDevice dev, boolean transition) {
        if (dev.getBondState() == BluetoothDevice.BOND_BONDED) {
            byte[] addr = Utils.getBytesFromAddress(dev.getAddress());
            if (!mAdapterService.removeBondNative(addr)) {
               Log.e(TAG, "Unexpected error while removing bond:");
            } else {
                if (transition) transitionTo(mPendingCommandState);
                return true;
            }

        }
        return false;
    }

    private boolean createBond(BluetoothDevice dev, boolean transition) {
        if (dev.getBondState() == BluetoothDevice.BOND_NONE) {
            infoLog("Bond address is:" + dev);
            byte[] addr = Utils.getBytesFromAddress(dev.getAddress());
            if (!mAdapterService.createBondNative(addr)) {
                sendIntent(dev, BluetoothDevice.BOND_NONE);
                return false;
            } else if (transition) {
                transitionTo(mPendingCommandState);
            }
            return true;
        }
        return false;
    }

    private void sendIntent(BluetoothDevice device, int newState) {
        DeviceProperties devProp = mRemoteDevices.getDeviceProperties(device);
        int oldState = BluetoothDevice.BOND_NONE;
        if (devProp != null) {
            oldState = devProp.getBondState();
        }
        if (oldState == newState) return;
        mAdapterProperties.onBondStateChanged(device, newState);

        Intent intent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, oldState);
        mAdapterService.sendBroadcast(intent, AdapterService.BLUETOOTH_PERM);
        infoLog("Bond State Change Intent:" + device + " OldState: " + oldState
                + " NewState: " + newState);
    }

    void bondStateChangeCallback(int status, byte[] address, int newState) {
        BluetoothDevice device = mRemoteDevices.getDevice(address);

        if (device == null) {
            infoLog("No record of the device:" + device);
            // This device will be added as part of the BONDING_STATE_CHANGE intent processing
            // in sendIntent above
            device = mAdapter.getRemoteDevice(Utils.getAddressStringFromByte(address));
        }

        infoLog("bondStateChangeCallback: Status: " + status + " Address: " + device
                + " newState: " + newState);

        Message msg = obtainMessage(BONDING_STATE_CHANGE);
        msg.obj = device;

        if (newState == BOND_STATE_BONDED)
            msg.arg1 = BluetoothDevice.BOND_BONDED;
        else if (newState == BOND_STATE_BONDING)
            msg.arg1 = BluetoothDevice.BOND_BONDING;
        else
            msg.arg1 = BluetoothDevice.BOND_NONE;

        sendMessage(msg);
    }

    private void infoLog(String msg) {
        Log.i(TAG, msg);
    }

    private void errorLog(String msg) {
        Log.e(TAG, msg);
    }

}
