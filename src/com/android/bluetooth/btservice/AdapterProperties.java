/*
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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelUuid;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;

import java.util.HashMap;
import java.util.ArrayList;

class AdapterProperties {
    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    private static final String TAG = "BluetoothAdapterProperties";

    private static final int BD_ADDR_LEN = 6; // 6 bytes
    private String mName;
    private byte[] mAddress;
    private int mBluetoothClass;
    private int mScanMode;
    private int mDiscoverableTimeout;
    private ParcelUuid[] mUuids;
    private ArrayList<BluetoothDevice> mBondedDevices = new ArrayList<BluetoothDevice>();

    private int mProfilesConnecting, mProfilesConnected, mProfilesDisconnecting;
    private HashMap<Integer, Pair<Integer, Integer>> mProfileConnectionState;


    private int mConnectionState = BluetoothAdapter.STATE_DISCONNECTED;
    private int mState = BluetoothAdapter.STATE_OFF;

    private AdapterService mService;
    private boolean mDiscovering;
    private RemoteDevices mRemoteDevices;
    private BluetoothAdapter mAdapter;

    // Lock for all getters and setters.
    // If finer grained locking is needer, more locks
    // can be added here.
    private Object mObject = new Object();

    public AdapterProperties(AdapterService service) {
        mService = service;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
    }
    public void init(RemoteDevices remoteDevices) {
        if (mProfileConnectionState ==null) {
            mProfileConnectionState = new HashMap<Integer, Pair<Integer, Integer>>();
        } else {
            mProfileConnectionState.clear();
        }
        mRemoteDevices = remoteDevices;
    }

    public void cleanup() {
        mRemoteDevices = null;
        if (mProfileConnectionState != null) {
            mProfileConnectionState.clear();
            mProfileConnectionState = null;
        }
        mService = null;
        if (!mBondedDevices.isEmpty())
            mBondedDevices.clear();
    }

    public Object Clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    /**
     * @return the mName
     */
    String getName() {
        synchronized (mObject) {
            return mName;
        }
    }

    /**
     * Set the local adapter property - name
     * @param name the name to set
     */
    boolean setName(String name) {
        synchronized (mObject) {
            return mService.setAdapterPropertyNative(
                    AbstractionLayer.BT_PROPERTY_BDNAME, name.getBytes());
        }
    }

    /**
     * @return the mClass
     */
    int getBluetoothClass() {
        synchronized (mObject) {
            return mBluetoothClass;
        }
    }

    /**
     * @return the mScanMode
     */
    int getScanMode() {
        synchronized (mObject) {
            return mScanMode;
        }
    }

    /**
     * Set the local adapter property - scanMode
     *
     * @param scanMode the ScanMode to set
     */
    boolean setScanMode(int scanMode) {
        synchronized (mObject) {
            return mService.setAdapterPropertyNative(
                    AbstractionLayer.BT_PROPERTY_ADAPTER_SCAN_MODE, Utils.intToByteArray(scanMode));
        }
    }

    /**
     * @return the mUuids
     */
    ParcelUuid[] getUuids() {
        synchronized (mObject) {
            return mUuids;
        }
    }

    /**
     * Set local adapter UUIDs.
     *
     * @param uuids the uuids to be set.
     */
    boolean setUuids(ParcelUuid[] uuids) {
        synchronized (mObject) {
            return mService.setAdapterPropertyNative(
                    AbstractionLayer.BT_PROPERTY_UUIDS, Utils.uuidsToByteArray(uuids));
        }
    }

    /**
     * @return the mAddress
     */
    byte[] getAddress() {
        synchronized (mObject) {
            return mAddress;
        }
    }

    /**
     * @param mConnectionState the mConnectionState to set
     */
    void setConnectionState(int mConnectionState) {
        synchronized (mObject) {
            this.mConnectionState = mConnectionState;
        }
    }

    /**
     * @return the mConnectionState
     */
    int getConnectionState() {
        synchronized (mObject) {
            return mConnectionState;
        }
    }

    /**
     * @param mState the mState to set
     */
    void setState(int mState) {
        synchronized (mObject) {
            debugLog("Setting state to " + mState);
            this.mState = mState;
        }
    }

    /**
     * @return the mState
     */
    int getState() {
        /* remove the lock to work around a platform deadlock problem */
        /* and also for read access, it is safe to remove the lock to save CPU power */
        return mState;
    }

    /**
     * @return the mBondedDevices
     */
    BluetoothDevice[] getBondedDevices() {
        BluetoothDevice[] bondedDeviceList = new BluetoothDevice[0];
        synchronized (mObject) {
            if(mBondedDevices.isEmpty())
                return (new BluetoothDevice[0]);

            try {
                bondedDeviceList = mBondedDevices.toArray(bondedDeviceList);
                debugLog("getBondedDevices: length="+bondedDeviceList.length);
                return bondedDeviceList;
            } catch(ArrayStoreException ee) {
                errorLog("Error retrieving bonded device array");
                return (new BluetoothDevice[0]);
            }
        }
    }
    // This function shall be invoked from BondStateMachine whenever the bond
    // state changes.
    void onBondStateChanged(BluetoothDevice device, int state)
    {
        if(device == null)
            return;
        try {
            byte[] addrByte = Utils.getByteAddress(device);
            DeviceProperties prop = mRemoteDevices.getDeviceProperties(device);
            if (prop == null)
                prop = mRemoteDevices.addDeviceProperties(addrByte);
            prop.setBondState(state);

            if (state == BluetoothDevice.BOND_BONDED) {
                // add if not already in list
                if(!mBondedDevices.contains(device)) {
                    debugLog("Adding bonded device:" +  device);
                    mBondedDevices.add(device);
                }
            } else if (state == BluetoothDevice.BOND_NONE) {
                // Setting remote trust to false
                boolean result = mService.setRemoteTrust(device, false);
                debugLog("setRemoteTrust() result=" + result);

                // remove device from list
                if (mBondedDevices.remove(device))
                    debugLog("Removing bonded device:" +  device);
                else
                    debugLog("Failed to remove device: " + device);
            } else if (state == BluetoothDevice.BOND_BONDING) {
                // Setting remote trust to false on BONDING state if it's already in BONDED list
                if (mBondedDevices.contains(device)) {
                    boolean result = mService.setRemoteTrust(device, false);
                    debugLog("onBondStateChanged result=" + result);
                }
            }
        }
        catch(Exception ee) {
            Log.e(TAG, "Exception in onBondStateChanged : ", ee);
        }
    }

    int getDiscoverableTimeout() {
        synchronized (mObject) {
            return mDiscoverableTimeout;
        }
    }

    boolean setDiscoverableTimeout(int timeout) {
        synchronized (mObject) {
            return mService.setAdapterPropertyNative(
                    AbstractionLayer.BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT,
                    Utils.intToByteArray(timeout));
        }
    }

    int getProfileConnectionState(int profile) {
        synchronized (mObject) {
            Pair<Integer, Integer> p = mProfileConnectionState.get(profile);
            if (p != null) return p.first;
            return BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    boolean isDiscovering() {
        synchronized (mObject) {
            return mDiscovering;
        }
    }

    void sendConnectionStateChange(BluetoothDevice device, int profile, int state, int prevState) {
        if (!validateProfileConnectionState(state) ||
                !validateProfileConnectionState(prevState)) {
            // Previously, an invalid state was broadcast anyway,
            // with the invalid state converted to -1 in the intent.
            // Better to log an error and not send an intent with
            // invalid contents or set mAdapterConnectionState to -1.
            errorLog("Error in sendConnectionStateChange: "
                    + "prevState " + prevState + " state " + state);
            return;
        }

        synchronized (mObject) {
            updateProfileConnectionState(profile, state, prevState);

            if (updateCountersAndCheckForConnectionStateChange(state, prevState)) {
                setConnectionState(state);

                Intent intent = new Intent(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
                intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
                intent.putExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        convertToAdapterState(state));
                intent.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_CONNECTION_STATE,
                        convertToAdapterState(prevState));
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                mService.sendBroadcastAsUser(intent, UserHandle.ALL,
                        mService.BLUETOOTH_PERM);
                Log.d(TAG, "CONNECTION_STATE_CHANGE: " + device + ": "
                        + prevState + " -> " + state);
            }
        }
    }

    private boolean validateProfileConnectionState(int state) {
        return (state == BluetoothProfile.STATE_DISCONNECTED ||
                state == BluetoothProfile.STATE_CONNECTING ||
                state == BluetoothProfile.STATE_CONNECTED ||
                state == BluetoothProfile.STATE_DISCONNECTING);
    }


    private int convertToAdapterState(int state) {
        switch (state) {
            case BluetoothProfile.STATE_DISCONNECTED:
                return BluetoothAdapter.STATE_DISCONNECTED;
            case BluetoothProfile.STATE_DISCONNECTING:
                return BluetoothAdapter.STATE_DISCONNECTING;
            case BluetoothProfile.STATE_CONNECTED:
                return BluetoothAdapter.STATE_CONNECTED;
            case BluetoothProfile.STATE_CONNECTING:
                return BluetoothAdapter.STATE_CONNECTING;
        }
        Log.e(TAG, "Error in convertToAdapterState");
        return -1;
    }

    private boolean updateCountersAndCheckForConnectionStateChange(int state, int prevState) {
        switch (prevState) {
            case BluetoothProfile.STATE_CONNECTING:
                if (mProfilesConnecting > 0)
                    mProfilesConnecting--;
                else
                    Log.e(TAG, "mProfilesConnecting " + mProfilesConnecting);
                break;

            case BluetoothProfile.STATE_CONNECTED:
                if (mProfilesConnected > 0)
                    mProfilesConnected--;
                else
                    Log.e(TAG, "mProfilesConnected " + mProfilesConnected);
                break;

            case BluetoothProfile.STATE_DISCONNECTING:
                if (mProfilesDisconnecting > 0)
                    mProfilesDisconnecting--;
                else
                    Log.e(TAG, "mProfilesDisconnecting " + mProfilesDisconnecting);
                break;
        }

        switch (state) {
            case BluetoothProfile.STATE_CONNECTING:
                mProfilesConnecting++;
                return (mProfilesConnected == 0 && mProfilesConnecting == 1);

            case BluetoothProfile.STATE_CONNECTED:
                mProfilesConnected++;
                return (mProfilesConnected == 1);

            case BluetoothProfile.STATE_DISCONNECTING:
                mProfilesDisconnecting++;
                return (mProfilesConnected == 0 && mProfilesDisconnecting == 1);

            case BluetoothProfile.STATE_DISCONNECTED:
                return (mProfilesConnected == 0 && mProfilesConnecting == 0);

            default:
                return true;
        }
    }

    private void updateProfileConnectionState(int profile, int newState, int oldState) {
        // mProfileConnectionState is a hashmap -
        // <Integer, Pair<Integer, Integer>>
        // The key is the profile, the value is a pair. first element
        // is the state and the second element is the number of devices
        // in that state.
        int numDev = 1;
        int newHashState = newState;
        boolean update = true;

        // The following conditions are considered in this function:
        // 1. If there is no record of profile and state - update
        // 2. If a new device's state is current hash state - increment
        //    number of devices in the state.
        // 3. If a state change has happened to Connected or Connecting
        //    (if current state is not connected), update.
        // 4. If numDevices is 1 and that device state is being updated, update
        // 5. If numDevices is > 1 and one of the devices is changing state,
        //    decrement numDevices but maintain oldState if it is Connected or
        //    Connecting
        Pair<Integer, Integer> stateNumDev = mProfileConnectionState.get(profile);
        if (stateNumDev != null) {
            int currHashState = stateNumDev.first;
            numDev = stateNumDev.second;

            if (newState == currHashState) {
                numDev ++;
            } else if (newState == BluetoothProfile.STATE_CONNECTED ||
                   (newState == BluetoothProfile.STATE_CONNECTING &&
                    currHashState != BluetoothProfile.STATE_CONNECTED)) {
                 numDev = 1;
            } else if (numDev == 1 && oldState == currHashState) {
                 update = true;
            } else if (numDev > 1 && oldState == currHashState) {
                 numDev --;

                 if (currHashState == BluetoothProfile.STATE_CONNECTED ||
                     currHashState == BluetoothProfile.STATE_CONNECTING) {
                    newHashState = currHashState;
                 }
            } else {
                 update = false;
            }
        }

        if (update) {
            mProfileConnectionState.put(profile, new Pair<Integer, Integer>(newHashState,
                    numDev));
        }
    }

    void adapterPropertyChangedCallback(int[] types, byte[][] values) {
        Intent intent;
        int type;
        byte[] val;
        for (int i = 0; i < types.length; i++) {
            val = values[i];
            type = types[i];
            infoLog("adapterPropertyChangedCallback with type:" + type + " len:" + val.length);
            synchronized (mObject) {
                switch (type) {
                    case AbstractionLayer.BT_PROPERTY_BDNAME:
                        mName = new String(val);
                        intent = new Intent(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED);
                        intent.putExtra(BluetoothAdapter.EXTRA_LOCAL_NAME, mName);
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                        mService.sendBroadcastAsUser(intent, UserHandle.ALL,
                                 mService.BLUETOOTH_PERM);
                        debugLog("Name is: " + mName);
                        break;
                    case AbstractionLayer.BT_PROPERTY_BDADDR:
                        mAddress = val;
                        debugLog("Address is:" + Utils.getAddressStringFromByte(mAddress));
                        break;
                    case AbstractionLayer.BT_PROPERTY_CLASS_OF_DEVICE:
                        mBluetoothClass = Utils.byteArrayToInt(val, 0);
                        debugLog("BT Class:" + mBluetoothClass);
                        break;
                    case AbstractionLayer.BT_PROPERTY_ADAPTER_SCAN_MODE:
                        int mode = Utils.byteArrayToInt(val, 0);
                        mScanMode = mService.convertScanModeFromHal(mode);
                        intent = new Intent(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
                        intent.putExtra(BluetoothAdapter.EXTRA_SCAN_MODE, mScanMode);
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                        mService.sendBroadcast(intent, mService.BLUETOOTH_PERM);
                        debugLog("Scan Mode:" + mScanMode);
                        if (mBluetoothDisabling) {
                            mBluetoothDisabling=false;
                            mService.startBluetoothDisable();
                        }
                        break;
                    case AbstractionLayer.BT_PROPERTY_UUIDS:
                        mUuids = Utils.byteArrayToUuid(val);
                        break;
                    case AbstractionLayer.BT_PROPERTY_ADAPTER_BONDED_DEVICES:
                        int number = val.length/BD_ADDR_LEN;
                        byte[] addrByte = new byte[BD_ADDR_LEN];
                        for (int j = 0; j < number; j++) {
                            System.arraycopy(val, j * BD_ADDR_LEN, addrByte, 0, BD_ADDR_LEN);
                            onBondStateChanged(mAdapter.getRemoteDevice(
                                               Utils.getAddressStringFromByte(addrByte)),
                                               BluetoothDevice.BOND_BONDED);
                        }
                        break;
                    case AbstractionLayer.BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT:
                        mDiscoverableTimeout = Utils.byteArrayToInt(val, 0);
                        debugLog("Discoverable Timeout:" + mDiscoverableTimeout);
                        break;
                    default:
                        errorLog("Property change not handled in Java land:" + type);
                }
            }
        }
    }

    void onBluetoothReady() {
        Log.d(TAG, "ScanMode =  " + mScanMode );
        Log.d(TAG, "State =  " + getState() );

        // When BT is being turned on, all adapter properties will be sent in 1
        // callback. At this stage, set the scan mode.
        synchronized (mObject) {
            if (getState() == BluetoothAdapter.STATE_TURNING_ON) {
                    /* mDiscoverableTimeout is part of the
                       adapterPropertyChangedCallback received before
                       onBluetoothReady */
                    switch (mScanMode) {
                        case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                            if (mDiscoverableTimeout != 0)
                                setScanMode(AbstractionLayer.BT_SCAN_MODE_CONNECTABLE);
                            else
                                setScanMode(AbstractionLayer.BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE);
                            break;
                        case BluetoothAdapter.SCAN_MODE_NONE:
                        case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
                        default:
                            setScanMode(AbstractionLayer.BT_SCAN_MODE_CONNECTABLE);
                    }
                    /* though not always required, this keeps NV up-to date on first-boot after flash */
                    setDiscoverableTimeout(mDiscoverableTimeout);
            }
        }
    }

    private boolean mBluetoothDisabling=false;

    void onBluetoothDisable() {
        // When BT disable is invoked, set the scan_mode to NONE
        // so no incoming connections are possible

        //Set flag to indicate we are disabling. When property change of scan mode done
        //continue with disable sequence
        debugLog("onBluetoothDisable()");
        mBluetoothDisabling = true;

        if (getState() == BluetoothAdapter.STATE_TURNING_OFF) {
           switch (mScanMode) {
               case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                   if (mDiscoverableTimeout != 0)
                       setScanMode(AbstractionLayer.BT_SCAN_MODE_CONNECTABLE);
                   else
                       setScanMode(AbstractionLayer.BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE);
                   break;
               case BluetoothAdapter.SCAN_MODE_NONE:
               case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
               default:
                   setScanMode(AbstractionLayer.BT_SCAN_MODE_CONNECTABLE);
           }
        }
    }
    void discoveryStateChangeCallback(int state) {
        infoLog("Callback:discoveryStateChangeCallback with state:" + state + " disc: " + mDiscovering);
        synchronized (mObject) {
            Intent intent;
            if (state == AbstractionLayer.BT_DISCOVERY_STOPPED) {
                mDiscovering = false;
                intent = new Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                mService.sendBroadcast(intent, mService.BLUETOOTH_PERM);
            } else if ((state == AbstractionLayer.BT_DISCOVERY_STARTED) && !mDiscovering) {
                mDiscovering = true;
                intent = new Intent(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
                mService.sendBroadcast(intent, mService.BLUETOOTH_PERM);
            }
        }
    }

    private void infoLog(String msg) {
        if (DBG) Log.i(TAG, msg);
    }

    private void debugLog(String msg) {
        if (DBG) Log.d(TAG, msg);
    }

    private void errorLog(String msg) {
        Log.e(TAG, msg);
    }
}
