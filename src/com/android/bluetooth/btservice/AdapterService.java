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
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.hid.HidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.hdp.HealthService;
import com.android.bluetooth.pan.PanService;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.HashMap;
import java.util.Set;
import java.util.Map;
import java.util.Iterator;
import java.util.Map.Entry;
import android.content.pm.PackageManager;
import android.os.ServiceManager;

public class AdapterService extends Service {
    private static final String TAG = "BluetoothAdapterService";
    private static final boolean DBG = true;

    /**
     * List of profile services to support.Comment out to disable a profile
     * Profiles started in order of appearance
     */
    @SuppressWarnings("rawtypes")
    private static final Class[] SUPPORTED_PROFILE_SERVICES = {
        HeadsetService.class,
        A2dpService.class,
        HidService.class,
        HealthService.class,
        PanService.class
    };

    public static final String ACTION_LOAD_ADAPTER_PROPERTIES="com.android.bluetooth.btservice.action.LOAD_ADAPTER_PROPERTIES";
    public static final String ACTION_SERVICE_STATE_CHANGED="com.android.bluetooth.btservice.action.STATE_CHANGED";
    public static final String EXTRA_ACTION="action";

    static final String BLUETOOTH_ADMIN_PERM =
        android.Manifest.permission.BLUETOOTH_ADMIN;
    static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final int ADAPTER_SERVICE_TYPE=Service.START_STICKY;

    static {
        classInitNative();
    }

    private static AdapterService sAdapterService;
    public static AdapterService getAdapterService(){
        return sAdapterService;
    }

    private IBluetoothManager mBluetoothManager;
    private IBluetooth mBluetoothService;
    private AdapterProperties mAdapterProperties;
    private int mAdapterState;
    private Context mContext;

    private AdapterState mAdapterStateMachine;
    private BondStateMachine mBondStateMachine;
    private JniCallbacks mJniCallbacks;
    private RemoteDevices mRemoteDevices;
    private boolean mProfilesStarted;
    private boolean mNativeAvailable;
    private HashMap<String,Integer> mProfileServicesState = new HashMap<String,Integer>();
    private int mCurrentRequestId;

    public void onProfileConnectionStateChanged(BluetoothDevice device, int profileId, int newState, int prevState) {
        Message m = mHandler.obtainMessage(MESSAGE_PROFILE_CONNECTION_STATE_CHANGED);
        m.obj = device;
        m.arg1 = profileId;
        m.arg2 = newState;
        Bundle b = new Bundle(1);
        b.putInt("prevState", prevState);
        m.setData(b);
        mHandler.sendMessage(m);
    }

    private void processProfileStateChanged(BluetoothDevice device, int profileId, int newState, int prevState) {
        if (mBluetoothService != null) {
            try {
                mBluetoothService.sendConnectionStateChange(device, profileId, newState,
                prevState);
            } catch (RemoteException re) {
                Log.e(TAG, "",re);
            }
        }
    }

    public void onProfileServiceStateChanged(String serviceName, int state) {
        Message m = mHandler.obtainMessage(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        m.obj=serviceName;
        m.arg1 = state;
        mHandler.sendMessage(m);
    }

    private void processProfileServiceStateChanged(String serviceName, int state) {
        boolean doUpdate=false;
        boolean isTurningOn;
        boolean isTurningOff;

        synchronized (mProfileServicesState) {
            Integer prevState = mProfileServicesState.get(serviceName);
            if (prevState != null && prevState != state) {
                mProfileServicesState.put(serviceName,state);
                doUpdate=true;
            }
        }
        if (DBG) Log.d(TAG,"onProfileServiceStateChange: serviceName=" + serviceName + ", state = " + state +", doUpdate = " + doUpdate);

        if (!doUpdate) {
            return;
        }

        synchronized (mAdapterStateMachine) {
            isTurningOff = mAdapterStateMachine.isTurningOff();
            isTurningOn = mAdapterStateMachine.isTurningOn();
        }

        if (isTurningOff) {
            //Process stop or disable pending
            //Check if all services are stopped if so, do cleanup
            //if (DBG) Log.d(TAG,"Checking if all profiles are stopped...");
            synchronized (mProfileServicesState) {
                Iterator<Map.Entry<String,Integer>> i = mProfileServicesState.entrySet().iterator();
                while (i.hasNext()) {
                    Map.Entry<String,Integer> entry = i.next();
                    if (BluetoothAdapter.STATE_OFF != entry.getValue()) {
                        Log.d(TAG, "Profile still running: " + entry.getKey());
                        return;
                    }
                }
            }
            if (DBG) Log.d(TAG, "All profile services stopped...");
            onProfilesStopped();
        } else if (isTurningOn) {
            //Process start pending
            //Check if all services are started if so, update state
            //if (DBG) Log.d(TAG,"Checking if all profiles are running...");
            synchronized (mProfileServicesState) {
                Iterator<Map.Entry<String,Integer>> i = mProfileServicesState.entrySet().iterator();
                while (i.hasNext()) {
                    Map.Entry<String,Integer> entry = i.next();
                    if (BluetoothAdapter.STATE_ON != entry.getValue()) {
                        Log.d(TAG, "Profile still not running:" + entry.getKey());
                        return;
                    }
                }
            }
            if (DBG) Log.d(TAG, "All profile services started.");
            onProfilesStarted();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (DBG) debugLog("onCreate");
        mContext = this;
        sAdapterService = this;
        mAdapterProperties = new AdapterProperties(this, mContext);
        mAdapterStateMachine =  new AdapterState(this, mContext, mAdapterProperties);
        mJniCallbacks = JniCallbacks.getInstance(null, mAdapterProperties,mAdapterStateMachine,null);
        initNative();
        mNativeAvailable=true;
        mAdapterStateMachine.start();

        //Load the name and address
        getAdapterPropertyNative(AbstractionLayer.BT_PROPERTY_BDADDR);
        getAdapterPropertyNative(AbstractionLayer.BT_PROPERTY_BDNAME);
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (DBG) debugLog("onBind");
        return mBinder;
    }
    public boolean onUnbind(Intent intent) {
        if (DBG) debugLog("onUnbind");
        return super.onUnbind(intent);
    }

    public void onDestroy() {
        debugLog("****onDestroy()********");
        mHandler.removeMessages(MESSAGE_SHUTDOWN);
        cleanup();
    }

    public int onStartCommand(Intent intent ,int flags, int startId) {

        mCurrentRequestId = startId;
        if (DBG) debugLog("onStartCommand: flags = " + flags + ", startId = " + startId);
        if (checkCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM)!=PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission denied!");
            return ADAPTER_SERVICE_TYPE;
        }

        //Check if we are restarting
        if (intent == null) {
            debugLog("Restarting AdapterService");
            return ADAPTER_SERVICE_TYPE;
        }

        //Get action and check if valid. If invalid, ignore and return
        String action  = intent.getStringExtra(EXTRA_ACTION);
        debugLog("onStartCommand(): action = " + action);
        if (!ACTION_SERVICE_STATE_CHANGED.equals(action)) {
            Log.w(TAG,"Unknown action: " + action);
            return ADAPTER_SERVICE_TYPE;
        }

        //Check state of request. If invalid, ignore and return
        int state= intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.ERROR);
        debugLog("onStartCommand(): state = " + Utils.debugGetAdapterStateString(state));

        //Cancel any pending shutdown requests
        synchronized (mHandler) {
            mHandler.removeMessages(MESSAGE_SHUTDOWN);
        }

        if (state == BluetoothAdapter.STATE_OFF) {
            Message m = mAdapterStateMachine.obtainMessage(AdapterState.USER_TURN_OFF);
            m.arg1= startId;
            mAdapterStateMachine.sendMessage(m);
        } else if (state == BluetoothAdapter.STATE_ON) {
            Message m = mAdapterStateMachine.obtainMessage(AdapterState.USER_TURN_ON);
            m.arg1= startId;
            mAdapterStateMachine.sendMessage(m);
        } else {
            Log.w(TAG,"Invalid state: " + action);
            return ADAPTER_SERVICE_TYPE;
        }
        return ADAPTER_SERVICE_TYPE;
    }

    void processStart() {
        if (DBG) debugLog("processStart()");

        //Initialize data objects
        for (int i=0; i < SUPPORTED_PROFILE_SERVICES.length;i++) {
            mProfileServicesState.put(SUPPORTED_PROFILE_SERVICES[i].getName(),BluetoothAdapter.STATE_OFF);
        }

        mRemoteDevices = new RemoteDevices(this, mContext);
        mRemoteDevices.init();
        mAdapterProperties.init(mRemoteDevices);
        mBondStateMachine = new BondStateMachine(this, mContext, mAdapterProperties, mRemoteDevices);
        mJniCallbacks.init(mRemoteDevices, mAdapterProperties,mAdapterStateMachine,mBondStateMachine);

        //Init BluetoothManager
        if (DBG) {debugLog("processStart(): Initializing Bluetooth Manager");}
        IBinder b = ServiceManager.getService(BluetoothAdapter.BLUETOOTH_MANAGER_SERVICE);
        if (b != null) {
            mBluetoothManager = IBluetoothManager.Stub.asInterface(b);
            if (mBluetoothManager != null) {
                try {
                    Log.d(TAG, "FRED: Registering manager callback " + mManagerCallback);
                    mBluetoothService = mBluetoothManager.registerAdapter(mManagerCallback);
                } catch (RemoteException re) {
                    Log.e(TAG, "",re);
                }
            }
        }

        //Start Bond State Machine
        if (DBG) {debugLog("processStart(): Starting Bond State Machine");}
        mBondStateMachine.start();

        //Start profile services
        if (SUPPORTED_PROFILE_SERVICES.length==0 || mProfilesStarted) {
            //Skip starting profiles and go to next step
            if (DBG) {debugLog("processStart(): Profile Services started");}
            Message m = mAdapterStateMachine.obtainMessage(AdapterState.STARTED);
            mAdapterStateMachine.sendMessage(m);
        } else {
            //Startup all profile services
            setProfileServiceState(SUPPORTED_PROFILE_SERVICES,BluetoothAdapter.STATE_ON);
        }
    }

    void startBluetoothDisable() {
        Log.d(TAG,"startBluetoothDisable()");
        Message m = mAdapterStateMachine.obtainMessage(AdapterState.BEGIN_DISABLE);
        mAdapterStateMachine.sendMessage(m);
    }

    void onProfilesStarted(){
        Log.d(TAG,"onProfilesStarted()");
        mProfilesStarted=true;
        Message m = mAdapterStateMachine.obtainMessage(AdapterState.STARTED);
        mAdapterStateMachine.sendMessage(m);
    }

    void onProfilesStopped() {
        Log.d(TAG,"onProfilesStopped()");
        mProfilesStarted=false;
        //Message m = mAdapterStateMachine.obtainMessage(AdapterState.DISABLE);
        Message m = mAdapterStateMachine.obtainMessage(AdapterState.STOPPED);
        mAdapterStateMachine.sendMessage(m);
    }


    boolean stopProfileServices() {
        if (SUPPORTED_PROFILE_SERVICES.length==0 || !mProfilesStarted) {
            if (DBG) {debugLog("processDisable(): No profiles services to stop or already stopped.");}
            return false;
        }
        setProfileServiceState(SUPPORTED_PROFILE_SERVICES,BluetoothAdapter.STATE_OFF);
        return true;
    }

    void processStopped() {
        Log.d(TAG, "processStopped()");

        if (mBluetoothManager != null) {
            try {
                Log.d(TAG,"FRED: Unregistering manager callback " + mManagerCallback);
                mBluetoothManager.unregisterAdapter(mManagerCallback);
            } catch (RemoteException re) {
                Log.e(TAG, "",re);
            }
        }
        mBondStateMachine.quit();
        mBondStateMachine.cleanup();
        mBondStateMachine = null;
    }

    void startShutdown(int requestId) {
        debugLog("startShutdown(): requestId = " + requestId + ", currentRequestId=" + mCurrentRequestId);
        if (requestId <0) {
            Log.w(TAG, "Ignoring shutdown request. Invalid requestId");
            return;
        }

        Message m = mHandler.obtainMessage(MESSAGE_SHUTDOWN);
        synchronized(mHandler) {
            mHandler.sendMessageDelayed(m, SHUTDOWN_TIMEOUT);
        }
        stopSelfResult(requestId);
    }

    void cleanup () {
        if (DBG)debugLog("cleanup()");

        if (mNativeAvailable) {
            Log.d(TAG, "Cleaning up adapter native....");
            cleanupNative();
            Log.d(TAG, "Done cleaning up adapter native....");
            mNativeAvailable=false;
        }

        if (mAdapterStateMachine != null) {
            mAdapterStateMachine.quit();
            mAdapterStateMachine.cleanup();
            mAdapterStateMachine = null;
        }

        if (mRemoteDevices != null) {
            mRemoteDevices.cleanup();
            mRemoteDevices = null;
        }

        if (mAdapterProperties != null) {
            mAdapterProperties.cleanup();
            mAdapterProperties = null;
        }

        mProfileServicesState.clear();
        if (DBG)debugLog("cleanup() done");
    }

    final private IBluetoothManagerCallback mManagerCallback =
            new IBluetoothManagerCallback.Stub() {
                public void onBluetoothServiceUp(IBluetooth bluetoothService) {
                    if (DBG) Log.d(TAG, "onBluetoothServiceUp");
                    synchronized (mManagerCallback) {
                        mBluetoothService = bluetoothService;
                    }
                }

                public void onBluetoothServiceDown() {
                    if (DBG) Log.d(TAG, "onBluetoothServiceDown " + this);
                    synchronized (mManagerCallback) {
                        mBluetoothService = null;
                    }
                }
        };

    private static final int MESSAGE_PROFILE_SERVICE_STATE_CHANGED =1;
    private static final int MESSAGE_PROFILE_CONNECTION_STATE_CHANGED=20;
    private static final int MESSAGE_SHUTDOWN= 100;
    private static final int SHUTDOWN_TIMEOUT=2000;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (DBG) debugLog("Message: " + msg.what);

            switch (msg.what) {
                case MESSAGE_SHUTDOWN: {
                    if (DBG) Log.d(TAG,"***SHUTDOWN: TIMEOUT!!! Forcing shutdown...");
                    stopSelf();
                }
                    break;
                case MESSAGE_PROFILE_SERVICE_STATE_CHANGED: {
                    if(DBG) debugLog("MESSAGE_PROFILE_SERVICE_STATE_CHANGED");
                    processProfileServiceStateChanged((String) msg.obj, msg.arg1);
                }
                    break;
                case MESSAGE_PROFILE_CONNECTION_STATE_CHANGED: {
                    if (DBG) debugLog( "MESSAGE_PROFILE_CONNECTION_STATE_CHANGED");
                    processProfileStateChanged((BluetoothDevice) msg.obj, msg.arg1,msg.arg2, msg.getData().getInt("prevState",BluetoothAdapter.ERROR));
                }
                    break;
            }
        }
    };

    @SuppressWarnings("rawtypes")
    private void setProfileServiceState(Class[] services, int state) {
        if (state != BluetoothAdapter.STATE_ON && state != BluetoothAdapter.STATE_OFF) {
            Log.w(TAG,"setProfileServiceState(): invalid state...Leaving...");
            return;
        }

        int expectedCurrentState= BluetoothAdapter.STATE_OFF;
        int pendingState = BluetoothAdapter.STATE_TURNING_ON;
        if (state == BluetoothAdapter.STATE_OFF) {
            expectedCurrentState= BluetoothAdapter.STATE_ON;
            pendingState = BluetoothAdapter.STATE_TURNING_OFF;
        }

        for (int i=0; i <services.length;i++) {
            String serviceName = services[i].getName();
            Integer serviceState = mProfileServicesState.get(serviceName);
            if(serviceState != null && serviceState != expectedCurrentState) {
                Log.w(TAG, "Unable to " + (state == BluetoothAdapter.STATE_OFF? "start" : "stop" ) +" service " +
                        serviceName+". Invalid state: " + serviceState);
                continue;
            }

            if (DBG) {
                Log.w(TAG, (state == BluetoothAdapter.STATE_OFF? "Stopping" : "Starting" ) +" service " +
                        serviceName);
            }

            mProfileServicesState.put(serviceName,pendingState);
            Intent intent = new Intent(this,services[i]);
            intent.putExtra(EXTRA_ACTION,ACTION_SERVICE_STATE_CHANGED);
            intent.putExtra(BluetoothAdapter.EXTRA_STATE,state);
            startService(intent);
        }
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
            debugLog("getState(): mAdapterProperties: " + mAdapterProperties);
            return mAdapterProperties.getState();
        }

        public boolean enable() {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                    "Need BLUETOOTH ADMIN permission");
            if (DBG) debugLog("enable() called...");
            Message m =
                    mAdapterStateMachine.obtainMessage(AdapterState.USER_TURN_ON);
            m.arg1 = 1; //persist state
            mAdapterStateMachine.sendMessage(m);
            return true;
        }

        public boolean disable(boolean persist) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                    "Need BLUETOOTH ADMIN permission");
            if (DBG) debugLog("disable() called...");
            int val = (persist ? 1 : 0);
            Message m =
                    mAdapterStateMachine.obtainMessage(AdapterState.USER_TURN_OFF);
            m.arg1 = val;
            mAdapterStateMachine.sendMessage(m);
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
            try {
                return mAdapterProperties.getName();
            } catch (Throwable t) {
                Log.d(TAG, "Unexpected exception while calling getName()",t);
            }
            return null;
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
            mRemoteDevices.fetchUuids(device);
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
            // TODO(BT) permission check?
            // Since this is a binder call check if Bluetooth is on still
            if (getState() == BluetoothAdapter.STATE_OFF) return;

            mAdapterProperties.sendConnectionStateChange(device, profile, state, prevState);

        }

        public ParcelFileDescriptor connectSocket(BluetoothDevice device, int type,
                                                  ParcelUuid uuid, int port, int flag) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            int fd = connectSocketNative(Utils.getBytesFromAddress(device.getAddress()),
                       type, Utils.uuidToByteArray(uuid), port, flag);
            if (fd < 0) {
                errorLog("Failed to connect socket");
                return null;
            }
            return ParcelFileDescriptor.adoptFd(fd);
        }

        public ParcelFileDescriptor createSocketChannel(int type, String serviceName,
                                                        ParcelUuid uuid, int port, int flag) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            int fd =  createSocketChannelNative(type, serviceName,
                                     Utils.uuidToByteArray(uuid), port, flag);
            if (fd < 0) {
                errorLog("Failed to create socket channel");
                return null;
            }
            return ParcelFileDescriptor.adoptFd(fd);
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

    private void debugLog(String msg) {
        Log.d(TAG +"(" +hashCode()+")", msg);
    }

    private void errorLog(String msg) {
        Log.e(TAG +"(" +hashCode()+")", msg);
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

    /*package*/ native boolean getRemoteServicesNative(byte[] address);

    // TODO(BT) move this to ../btsock dir
    private native int connectSocketNative(byte[] address, int type,
                                           byte[] uuid, int port, int flag);
    private native int createSocketChannelNative(int type, String serviceName,
                                                 byte[] uuid, int port, int flag);
}
