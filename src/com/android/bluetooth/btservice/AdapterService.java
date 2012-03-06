/*
 * Copyright (C) 2012 Google Inc.
 */

/**
 * @hide
 */

package com.android.bluetooth.btservice;

import android.app.Application;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetooth;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;

import java.util.HashMap;
import java.util.Set;

public class AdapterService extends Application {
    private static final String TAG = "BluetoothAdapterService";
    private static final boolean DBG = true;

    static final String BLUETOOTH_ADMIN_PERM =
        android.Manifest.permission.BLUETOOTH_ADMIN;
    static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private AdapterProperties mAdapterProperties;
    private int mAdapterState;
    private Context mContext;
    private boolean mIsAirplaneSensitive;
    private boolean mIsAirplaneToggleable;
    private static AdapterService sAdapterService;

    private BluetoothAdapter mAdapter;
    private AdapterState mAdapterStateMachine;
    private BondStateMachine mBondStateMachine;
    private JniCallbacks mJniCallbacks;


    private RemoteDevices mRemoteDevices;
    static {
        System.loadLibrary("bluetooth_jni");
        classInitNative();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ServiceManager.addService(Context.BLUETOOTH_SERVICE, mBinder);

        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mContext = this;
        sAdapterService = this;

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        registerForAirplaneMode(filter);
        registerReceiver(mReceiver, filter);

        mRemoteDevices = RemoteDevices.getInstance(this, mContext);
        mAdapterProperties = AdapterProperties.getInstance(this, mContext);
        mAdapterStateMachine = new AdapterState(this, mContext, mAdapterProperties);
        mBondStateMachine = new BondStateMachine(this, mContext, mAdapterProperties);
        mJniCallbacks = JniCallbacks.getInstance(mRemoteDevices, mAdapterProperties,
                                                 mAdapterStateMachine, mBondStateMachine);


        initNative();
        mAdapterStateMachine.start();
        mBondStateMachine.start();
        // TODO(BT): Start other profile services.
        // startService();
    }

    @Override
    protected void finalize() throws Throwable {
        mContext.unregisterReceiver(mReceiver);
        try {
            cleanupNative();
        } finally {
            super.finalize();
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            String action = intent.getAction();
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                ContentResolver resolver = context.getContentResolver();
                // Query the airplane mode from Settings.System just to make sure that
                // some random app is not sending this intent and disabling bluetooth
                if (isAirplaneModeOn()) {
                    mAdapterStateMachine.sendMessage(AdapterState.AIRPLANE_MODE_ON);
                } else {
                    mAdapterStateMachine.sendMessage(AdapterState.AIRPLANE_MODE_OFF);
                }
            }
        }
    };



    private void registerForAirplaneMode(IntentFilter filter) {
        final ContentResolver resolver = mContext.getContentResolver();
        final String airplaneModeRadios = Settings.System.getString(resolver,
                Settings.System.AIRPLANE_MODE_RADIOS);
        final String toggleableRadios = Settings.System.getString(resolver,
                Settings.System.AIRPLANE_MODE_TOGGLEABLE_RADIOS);

        mIsAirplaneSensitive = airplaneModeRadios == null ? true :
                airplaneModeRadios.contains(Settings.System.RADIO_BLUETOOTH);
        mIsAirplaneToggleable = toggleableRadios == null ? false :
                toggleableRadios.contains(Settings.System.RADIO_BLUETOOTH);

        if (mIsAirplaneSensitive) {
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        }
    }

    /* Returns true if airplane mode is currently on */
    private final boolean isAirplaneModeOn() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) == 1;
    }

    /**
     * Handlers for incoming service calls
     */
    private final IBluetooth.Stub mBinder = new IBluetooth.Stub() {
        public boolean isEnabled() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mAdapterProperties.getState() == BluetoothAdapter.STATE_ON;
        }

        public int getState() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mAdapterProperties.getState();
        }

        public boolean enable() {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                    "Need BLUETOOTH ADMIN permission");
            mAdapterStateMachine.sendMessage(AdapterState.USER_TURN_ON);
            return true;
        }

        public boolean disable() {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                    "Need BLUETOOTH ADMIN permission");
            mAdapterStateMachine.sendMessage(AdapterState.USER_TURN_OFF);
            return true;
        }

        public String getAddress() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            String addrString = null;
            byte[] address = mAdapterProperties.getAddress();
            return Utils.getAddressStringFromByte(address);
        }

        public ParcelUuid[] getUuids() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mAdapterProperties.getUuids();
        }

        public String getName() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                    "Need BLUETOOTH permission");
            return mAdapterProperties.getName();
        }

        public boolean setName(String name) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                    "Need BLUETOOTH ADMIN permission");
            return mAdapterProperties.setName(name);
        }

        public int getScanMode() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mAdapterProperties.getScanMode();
        }

        public boolean setScanMode(int mode, int duration) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            setDiscoverableTimeout(duration);

            int newMode = convertScanModeToHal(mode);
            return mAdapterProperties.setScanMode(newMode);
        }

        public int getDiscoverableTimeout() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mAdapterProperties.getDiscoverableTimeout();
        }

        public boolean setDiscoverableTimeout(int timeout) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mAdapterProperties.setDiscoverableTimeout(timeout);
        }

        public boolean startDiscovery() {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                    "Need BLUETOOTH ADMIN permission");
            return startDiscoveryNative();
        }

        public boolean cancelDiscovery() {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                    "Need BLUETOOTH ADMIN permission");
            return cancelDiscoveryNative();
        }

        public boolean isDiscovering() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mAdapterProperties.isDiscovering();
        }

        public BluetoothDevice[] getBondedDevices() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            debugLog("Get Bonded Devices being called");
            return mAdapterProperties.getBondedDevices();
        }

        public int getAdapterConnectionState() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mAdapterProperties.getConnectionState();
        }

        public int getProfileConnectionState(int profile) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mAdapterProperties.getProfileConnectionState(profile);
        }

        public boolean createBond(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH ADMIN permission");
            DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
            if (deviceProp != null && deviceProp.getBondState() != BluetoothDevice.BOND_NONE) {
                return false;
            }

            Message msg = mBondStateMachine.obtainMessage(BondStateMachine.CREATE_BOND);
            msg.obj = device;
            mBondStateMachine.sendMessage(msg);
            return true;
        }

        public boolean cancelBondProcess(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
            byte[] addr = Utils.getBytesFromAddress(device.getAddress());
            return cancelBondNative(addr);
        }

        public boolean removeBond(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH ADMIN permission");
            DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null || deviceProp.getBondState() != BluetoothDevice.BOND_BONDED) {
                return false;
            }
            Message msg = mBondStateMachine.obtainMessage(BondStateMachine.REMOVE_BOND);
            msg.obj = device;
            mBondStateMachine.sendMessage(msg);
            return true;
        }

        public int getBondState(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null) {
                return BluetoothDevice.BOND_NONE;
            }
            return deviceProp.getBondState();
        }

        public String getRemoteName(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null) return null;
            return deviceProp.getName();
        }

        public String getRemoteAlias(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null) return null;
            return deviceProp.getAlias();
        }

        public boolean setRemoteAlias(BluetoothDevice device, String name) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null) return false;
            deviceProp.setAlias(name);
            return true;
        }

        public int getRemoteClass(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null) return 0;

            return deviceProp.getBluetoothClass();
        }

        public ParcelUuid[] getRemoteUuids(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null) return null;
            return deviceProp.getUuids();
        }

        public boolean fetchRemoteUuids(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            mRemoteDevices.performSdp(device);
            return true;
        }

        public boolean setPin(BluetoothDevice device, boolean accept, int len, byte[] pinCode) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null || deviceProp.getBondState() != BluetoothDevice.BOND_BONDING) {
                return false;
            }

            byte[] addr = Utils.getBytesFromAddress(device.getAddress());
            return pinReplyNative(addr, accept, len, pinCode);
        }

        public boolean setPasskey(BluetoothDevice device, boolean accept, int len, byte[] passkey) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null || deviceProp.getBondState() != BluetoothDevice.BOND_BONDING) {
                return false;
            }

            byte[] addr = Utils.getBytesFromAddress(device.getAddress());
            return sspReplyNative(addr, AbstractionLayer.BT_SSP_VARIANT_PASSKEY_ENTRY, accept,
                    Utils.byteArrayToInt(passkey));
        }

        public boolean setPairingConfirmation(BluetoothDevice device, boolean accept) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
            if (deviceProp == null || deviceProp.getBondState() != BluetoothDevice.BOND_BONDING) {
                return false;
            }

            byte[] addr = Utils.getBytesFromAddress(device.getAddress());
            return sspReplyNative(addr, AbstractionLayer.BT_SSP_VARIANT_PASSKEY_CONFIRMATION,
                    accept, 0);
        }

        public void sendConnectionStateChange(BluetoothDevice
                device, int profile, int state, int prevState) {
            // Since this is a binder call check if Bluetooth is on still
            if (getState() == BluetoothAdapter.STATE_OFF) return;

            mAdapterProperties.sendConnectionStateChange(device, profile, state, prevState);

        }

    };

    private int convertScanModeToHal(int mode) {
        switch (mode) {
            case BluetoothAdapter.SCAN_MODE_NONE:
                return AbstractionLayer.BT_SCAN_MODE_NONE;
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
                return AbstractionLayer.BT_SCAN_MODE_CONNECTABLE;
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                return AbstractionLayer.BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE;
        }
        errorLog("Incorrect scan mode in convertScanModeToHal");
        return -1;
    }

    int convertScanModeFromHal(int mode) {
        switch (mode) {
            case AbstractionLayer.BT_SCAN_MODE_NONE:
                return BluetoothAdapter.SCAN_MODE_NONE;
            case AbstractionLayer.BT_SCAN_MODE_CONNECTABLE:
                return BluetoothAdapter.SCAN_MODE_CONNECTABLE;
            case AbstractionLayer.BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                return BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
        }
        errorLog("Incorrect scan mode in convertScanModeFromHal");
        return -1;
    }

    private static void debugLog(String msg) {
        Log.d(TAG, msg);
    }

    private static void errorLog(String msg) {
        Log.e(TAG, msg);
    }

    void persistBluetoothSetting(boolean setOn) {
        long origCallerIdentityToken = Binder.clearCallingIdentity();
        Settings.Secure.putInt(mContext.getContentResolver(),
                               Settings.Secure.BLUETOOTH_ON,
                               setOn ? 1 : 0);
        Binder.restoreCallingIdentity(origCallerIdentityToken);
    }

    private boolean getBluetoothPersistedSetting() {
        ContentResolver contentResolver = mContext.getContentResolver();
        return (Settings.Secure.getInt(contentResolver,
                                       Settings.Secure.BLUETOOTH_ON, 0) > 0);
    }

    void onBluetoothEnabled() {
        getAdapterPropertiesNative();
    }

    void onBluetoothEnabledAdapterReady() {
        mAdapterStateMachine.sendMessage(AdapterState.ENABLED_READY);
    }


    private native static void classInitNative();
    private native boolean initNative();
    private native void cleanupNative();
    /*package*/ native boolean enableNative();
    /*package*/ native boolean disableNative();
    /*package*/ native boolean setAdapterPropertyNative(int type, byte[] val);
    /*package*/ native boolean getAdapterPropertiesNative();
    /*package*/ native boolean getAdapterPropertyNative(int type);
    /*package*/ native boolean setAdapterPropertyNative(int type);
    /*package*/ native boolean
        setDevicePropertyNative(byte[] address, int type, byte[] val);
    /*package*/ native boolean getDevicePropertyNative(byte[] address, int type);

    /*package*/ native boolean createBondNative(byte[] address);
    /*package*/ native boolean removeBondNative(byte[] address);
    /*package*/ native boolean cancelBondNative(byte[] address);

    private native boolean startDiscoveryNative();
    private native boolean cancelDiscoveryNative();

    private native boolean pinReplyNative(byte[] address, boolean accept, int len, byte[] pin);
    private native boolean sspReplyNative(byte[] address, int type, boolean
            accept, int passkey);
}
