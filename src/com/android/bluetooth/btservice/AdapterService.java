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
import android.bluetooth.IBluetoothCallback;
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
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.hid.HidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.hdp.HealthService;
import com.android.bluetooth.pan.PanService;
import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
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
    private static final boolean TRACE_REF = true;
    //For Debugging only
    private static int sRefCount=0;

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
    public static synchronized AdapterService getAdapterService(){
        if (sAdapterService != null && !sAdapterService.mCleaningUp) {
            if (DBG) Log.d(TAG, "getAdapterService(): returning " + sAdapterService);
            return sAdapterService;
        }
        if (DBG)  {
            if (sAdapterService == null) {
                Log.d(TAG, "getAdapterService(): service not available");
            } else if (sAdapterService.mCleaningUp) {
                Log.d(TAG,"getAdapterService(): service is cleaning up");
            }
        }
        return null;
    }

    private static synchronized void setAdapterService(AdapterService instance) {
        if (instance != null && !instance.mCleaningUp) {
            if (DBG) Log.d(TAG, "setAdapterService(): set to: " + sAdapterService);
            sAdapterService = instance;
        } else {
            if (DBG)  {
                if (sAdapterService == null) {
                    Log.d(TAG, "setAdapterService(): service not available");
                } else if (sAdapterService.mCleaningUp) {
                    Log.d(TAG,"setAdapterService(): service is cleaning up");
                }
            }
        }
    }

    private static synchronized void clearAdapterService() {
        sAdapterService = null;
    }

    private AdapterProperties mAdapterProperties;
    private AdapterState mAdapterStateMachine;
    private BondStateMachine mBondStateMachine;
    private JniCallbacks mJniCallbacks;
    private RemoteDevices mRemoteDevices;
    private boolean mProfilesStarted;
    private boolean mNativeAvailable;
    private boolean mCleaningUp;
    private HashMap<String,Integer> mProfileServicesState = new HashMap<String,Integer>();
    private RemoteCallbackList<IBluetoothCallback> mCallbacks;//Only BluetoothManagerService should be registered
    private int mCurrentRequestId;

    public AdapterService() {
        super();
        if (TRACE_REF) {
            synchronized (AdapterService.class) {
                sRefCount++;
                Log.d(TAG, "REFCOUNT: CREATED. INSTANCE_COUNT" + sRefCount);
            }
        }
    }

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
        IBluetooth.Stub binder = mBinder;
        if (binder != null) {
            try {
                binder.sendConnectionStateChange(device, profileId, newState,prevState);
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
            //Send message to state machine
            mProfilesStarted=false;
            mAdapterStateMachine.sendMessage(mAdapterStateMachine.obtainMessage(AdapterState.STOPPED));
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
            mProfilesStarted=true;
            //Send message to state machine
            mAdapterStateMachine.sendMessage(mAdapterStateMachine.obtainMessage(AdapterState.STARTED));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (DBG) debugLog("onCreate");
        mBinder = new AdapterServiceBinder(this);
        mAdapterProperties = new AdapterProperties(this);
        mAdapterStateMachine =  new AdapterState(this, mAdapterProperties);
        mJniCallbacks =  new JniCallbacks(mAdapterStateMachine, mAdapterProperties);
        initNative();
        mNativeAvailable=true;
        mAdapterStateMachine.start();
        mCallbacks = new RemoteCallbackList<IBluetoothCallback>();
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
        if (mCleaningUp) {
            Log.e(TAG,"*************Received new request while service is cleaning up****************************");
        }

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
        Class[] SUPPORTED_PROFILE_SERVICES = Config.getSupportedProfiles();
        //Initialize data objects
        for (int i=0; i < SUPPORTED_PROFILE_SERVICES.length;i++) {
            mProfileServicesState.put(SUPPORTED_PROFILE_SERVICES[i].getName(),BluetoothAdapter.STATE_OFF);
        }
        mRemoteDevices = new RemoteDevices(this);
        mBondStateMachine = new BondStateMachine(this, mAdapterProperties, mRemoteDevices);
        mAdapterProperties.init(mRemoteDevices);
        mJniCallbacks.init(mBondStateMachine,mRemoteDevices);

        //Start Bond State Machine
        if (DBG) {debugLog("processStart(): Starting Bond State Machine");}
        mBondStateMachine.start();

        //FIXME: Set static instance here???
        setAdapterService(this);

        //Start profile services
        if (!mProfilesStarted && SUPPORTED_PROFILE_SERVICES.length >0) {
            //Startup all profile services
            setProfileServiceState(SUPPORTED_PROFILE_SERVICES,BluetoothAdapter.STATE_ON);
        }else {
            if (DBG) {debugLog("processStart(): Profile Services alreay started");}
            mAdapterStateMachine.sendMessage(mAdapterStateMachine.obtainMessage(AdapterState.STARTED));
        }
    }

    void startBluetoothDisable() {
        mAdapterStateMachine.sendMessage(mAdapterStateMachine.obtainMessage(AdapterState.BEGIN_DISABLE));
    }

    boolean stopProfileServices() {
        Class[] SUPPORTED_PROFILE_SERVICES = Config.getSupportedProfiles();
        if (mProfilesStarted && SUPPORTED_PROFILE_SERVICES.length>0) {
            setProfileServiceState(SUPPORTED_PROFILE_SERVICES,BluetoothAdapter.STATE_OFF);
            return true;
        } else {
            if (DBG) {debugLog("stopProfileServices(): No profiles services to stop or already stopped.");}
            return false;
        }
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

     void updateAdapterState(int prevState, int newState){
        if (mCallbacks !=null) {
            int n=mCallbacks.beginBroadcast();
            Log.d(TAG,"Broadcasting updateAdapterState() to " + n + " receivers.");
            for (int i=0; i <n;i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onBluetoothStateChange(prevState,newState);
                }  catch (RemoteException e) {
                    Log.e(TAG, "Unable to call onBluetoothStateChange() on callback #" + i, e);
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    void cleanup () {
        if (DBG)debugLog("cleanup()");
        if (mCleaningUp) {
            Log.w(TAG,"*************service already starting to cleanup... Ignoring cleanup request.........");
            return;
        }

        mCleaningUp = true;

        if (mAdapterStateMachine != null) {
            mAdapterStateMachine.quit();
            mAdapterStateMachine.cleanup();
            mAdapterStateMachine = null;
        }

        if (mBondStateMachine != null) {
            mBondStateMachine.quit();
            mBondStateMachine.cleanup();
            mBondStateMachine = null;
        }

        if (mRemoteDevices != null) {
            mRemoteDevices.cleanup();
            mRemoteDevices = null;
        }

        if (mNativeAvailable) {
            Log.d(TAG, "Cleaning up adapter native....");
            cleanupNative();
            Log.d(TAG, "Done cleaning up adapter native....");
            mNativeAvailable=false;
        }

        if (mAdapterProperties != null) {
            mAdapterProperties.cleanup();
            mAdapterProperties = null;
        }

        if (mJniCallbacks != null) {
            mJniCallbacks.cleanup();
            mJniCallbacks = null;
        }

        if (mProfileServicesState != null) {
            mProfileServicesState.clear();
            mProfileServicesState= null;
        }

        clearAdapterService();

        if (mBinder != null) {
            mBinder.cleanup();
            mBinder = null;
        }

        if (mCallbacks !=null) {
            mCallbacks.kill();
        }

        if (DBG)debugLog("cleanup() done");
    }

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

    private boolean isAvailable() {
        return !mCleaningUp;
    }

    /**
     * Handlers for incoming service calls
     */
    private AdapterServiceBinder mBinder;

    /**
     * The Binder implementation must be declared to be a static class, with
     * the AdapterService instance passed in the constructor. Furthermore,
     * when the AdapterService shuts down, the reference to the AdapterService 
     * must be explicitly removed.
     *
     * Otherwise, a memory leak can occur from repeated starting/stopping the
     * service...Please refer to android.os.Binder for further details on
     * why an inner instance class should be avoided.
     *
     */
    private static class AdapterServiceBinder extends IBluetooth.Stub {
        private AdapterService mService;

        public AdapterServiceBinder(AdapterService svc) {
            mService = svc;
        }
        public boolean cleanup() {
            mService = null;
            return true;
        }

        public AdapterService getService() {
            if (mService  != null && mService.isAvailable()) {
                return mService;
            }
            return null;
        }
        public boolean isEnabled() {
            AdapterService service = getService();
            if (service == null) return false;
            return service.isEnabled();
        }

        public int getState() {
            AdapterService service = getService();
            if (service == null) return  BluetoothAdapter.STATE_OFF;
            return service.getState();
        }

        public boolean enable() {
            AdapterService service = getService();
            if (service == null) return false;
            return service.enable();
        }

        public boolean enableNoAutoConnect() {
            // TODO(BT)
            return false;
        }

        public boolean disable(boolean persist) {
            AdapterService service = getService();
            if (service == null) return false;
            return service.disable(persist);
        }

        public String getAddress() {
            AdapterService service = getService();
            if (service == null) return null;
            return service.getAddress();
        }

        public ParcelUuid[] getUuids() {
            AdapterService service = getService();
            if (service == null) return new ParcelUuid[0];
            return service.getUuids();
        }

        public String getName() {
            AdapterService service = getService();
            if (service == null) return null;
            return service.getName();
        }

        public boolean setName(String name) {
            AdapterService service = getService();
            if (service == null) return false;
            return service.setName(name);
        }

        public int getScanMode() {
            AdapterService service = getService();
            if (service == null) return BluetoothAdapter.SCAN_MODE_NONE;
            return service.getScanMode();
        }

        public boolean setScanMode(int mode, int duration) {
            AdapterService service = getService();
            if (service == null) return false;
            return service.setScanMode(mode,duration);
        }

        public int getDiscoverableTimeout() {
            AdapterService service = getService();
            if (service == null) return 0;
            return service.getDiscoverableTimeout();
        }

        public boolean setDiscoverableTimeout(int timeout) {
            AdapterService service = getService();
            if (service == null) return false;
            return service.setDiscoverableTimeout(timeout);
        }

        public boolean startDiscovery() {
            AdapterService service = getService();
            if (service == null) return false;
            return service.startDiscovery();
        }

        public boolean cancelDiscovery() {
            AdapterService service = getService();
            if (service == null) return false;
            return service.cancelDiscovery();
        }
        public boolean isDiscovering() {
            AdapterService service = getService();
            if (service == null) return false;
            return service.isDiscovering();
        }

        public BluetoothDevice[] getBondedDevices() {
            AdapterService service = getService();
            if (service == null) return new BluetoothDevice[0];
            return service.getBondedDevices();
        }

        public int getAdapterConnectionState() {
            AdapterService service = getService();
            if (service == null) return BluetoothAdapter.STATE_DISCONNECTED;
            return service.getAdapterConnectionState();
        }

        public int getProfileConnectionState(int profile) {
            AdapterService service = getService();
            if (service == null) return BluetoothProfile.STATE_DISCONNECTED;
            return service.getProfileConnectionState(profile);
        }

        public boolean createBond(BluetoothDevice device) {
            AdapterService service = getService();
            if (service == null) return false;
            return service.createBond(device);
        }

        public boolean cancelBondProcess(BluetoothDevice device) {
            AdapterService service = getService();
            if (service == null) return false;
            return service.cancelBondProcess(device);
        }

        public boolean removeBond(BluetoothDevice device) {
            AdapterService service = getService();
            if (service == null) return false;
            return service.removeBond(device);
        }

        public int getBondState(BluetoothDevice device) {
            AdapterService service = getService();
            if (service == null) return BluetoothDevice.BOND_NONE;
            return service.getBondState(device);
        }

        public String getRemoteName(BluetoothDevice device) {
            AdapterService service = getService();
            if (service == null) return null;
            return service.getRemoteName(device);
        }

        public String getRemoteAlias(BluetoothDevice device) {
            AdapterService service = getService();
            if (service == null) return null;
            return service.getRemoteAlias(device);
        }

        public boolean setRemoteAlias(BluetoothDevice device, String name) {
            AdapterService service = getService();
            if (service == null) return false;
            return service.setRemoteAlias(device, name);
        }

        public int getRemoteClass(BluetoothDevice device) {
            AdapterService service = getService();
            if (service == null) return 0;
            return service.getRemoteClass(device);
        }

        public ParcelUuid[] getRemoteUuids(BluetoothDevice device) {
            AdapterService service = getService();
            if (service == null) return null;
            return service.getRemoteUuids(device);
        }

        public boolean fetchRemoteUuids(BluetoothDevice device) {
            AdapterService service = getService();
            if (service == null) return false;
            return service.fetchRemoteUuids(device);
        }

        public boolean setPin(BluetoothDevice device, boolean accept, int len, byte[] pinCode) {
            AdapterService service = getService();
            if (service == null) return false;
            return service.setPin(device, accept, len, pinCode);
        }

        public boolean setPasskey(BluetoothDevice device, boolean accept, int len, byte[] passkey) {
            AdapterService service = getService();
            if (service == null) return false;
            return service.setPasskey(device, accept, len, passkey);
        }

        public boolean setPairingConfirmation(BluetoothDevice device, boolean accept) {
            AdapterService service = getService();
            if (service == null) return false;
            return service.setPairingConfirmation(device, accept);
        }

        public void sendConnectionStateChange(BluetoothDevice
                device, int profile, int state, int prevState) {
            AdapterService service = getService();
            if (service == null) return;
            service.sendConnectionStateChange(device, profile, state, prevState);
        }

        public ParcelFileDescriptor connectSocket(BluetoothDevice device, int type,
                                                  ParcelUuid uuid, int port, int flag) {
            AdapterService service = getService();
            if (service == null) return null;
            return service.connectSocket(device, type, uuid, port, flag);
        }

        public ParcelFileDescriptor createSocketChannel(int type, String serviceName,
                                                        ParcelUuid uuid, int port, int flag) {
            AdapterService service = getService();
            if (service == null) return null;
            return service.createSocketChannel(type, serviceName, uuid, port, flag);
        }

        public void registerCallback(IBluetoothCallback cb) {
            AdapterService service = getService();
            if (service == null) return ;
            service.registerCallback(cb);
         }

         public void unregisterCallback(IBluetoothCallback cb) {
             AdapterService service = getService();
             if (service == null) return ;
             service.unregisterCallback(cb);
         }
    };


    //----API Methods--------
     boolean isEnabled() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mAdapterProperties.getState() == BluetoothAdapter.STATE_ON;
    }

     int getState() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        debugLog("getState(): mAdapterProperties: " + mAdapterProperties);
        return mAdapterProperties.getState();
    }

     boolean enable() {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH ADMIN permission");
        if (DBG) debugLog("enable() called...");
        Message m =
                mAdapterStateMachine.obtainMessage(AdapterState.USER_TURN_ON);
        m.arg1 = 1; //persist state
        mAdapterStateMachine.sendMessage(m);
        return true;
    }

     boolean disable(boolean persist) {
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

     String getAddress() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        String addrString = null;
        byte[] address = mAdapterProperties.getAddress();
        return Utils.getAddressStringFromByte(address);
    }

     ParcelUuid[] getUuids() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mAdapterProperties.getUuids();
    }

     String getName() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                "Need BLUETOOTH permission");
        try {
            return mAdapterProperties.getName();
        } catch (Throwable t) {
            Log.d(TAG, "Unexpected exception while calling getName()",t);
        }
        return null;
    }

     boolean setName(String name) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH ADMIN permission");
        return mAdapterProperties.setName(name);
    }

     int getScanMode() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mAdapterProperties.getScanMode();
    }

     boolean setScanMode(int mode, int duration) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        setDiscoverableTimeout(duration);

        int newMode = convertScanModeToHal(mode);
        return mAdapterProperties.setScanMode(newMode);
    }

     int getDiscoverableTimeout() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mAdapterProperties.getDiscoverableTimeout();
    }

     boolean setDiscoverableTimeout(int timeout) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mAdapterProperties.setDiscoverableTimeout(timeout);
    }

     boolean startDiscovery() {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH ADMIN permission");
        return startDiscoveryNative();
    }

     boolean cancelDiscovery() {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH ADMIN permission");
        return cancelDiscoveryNative();
    }

     boolean isDiscovering() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mAdapterProperties.isDiscovering();
    }

     BluetoothDevice[] getBondedDevices() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        debugLog("Get Bonded Devices being called");
        return mAdapterProperties.getBondedDevices();
    }

     int getAdapterConnectionState() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mAdapterProperties.getConnectionState();
    }

     int getProfileConnectionState(int profile) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mAdapterProperties.getProfileConnectionState(profile);
    }

     boolean createBond(BluetoothDevice device) {
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

     boolean cancelBondProcess(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
        byte[] addr = Utils.getBytesFromAddress(device.getAddress());
        return cancelBondNative(addr);
    }

     boolean removeBond(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null || deviceProp.getBondState() != BluetoothDevice.BOND_BONDED) {
            return false;
        }
        Message msg = mBondStateMachine.obtainMessage(BondStateMachine.REMOVE_BOND);
        msg.obj = device;
        mBondStateMachine.sendMessage(msg);
        return true;
    }

     int getBondState(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) {
            return BluetoothDevice.BOND_NONE;
        }
        return deviceProp.getBondState();
    }

     String getRemoteName(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) return null;
        return deviceProp.getName();
    }

     String getRemoteAlias(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) return null;
        return deviceProp.getAlias();
    }

     boolean setRemoteAlias(BluetoothDevice device, String name) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) return false;
        deviceProp.setAlias(name);
        return true;
    }

     int getRemoteClass(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) return 0;

        return deviceProp.getBluetoothClass();
    }

     ParcelUuid[] getRemoteUuids(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) return null;
        return deviceProp.getUuids();
    }

     boolean fetchRemoteUuids(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        mRemoteDevices.fetchUuids(device);
        return true;
    }

     boolean setPin(BluetoothDevice device, boolean accept, int len, byte[] pinCode) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null || deviceProp.getBondState() != BluetoothDevice.BOND_BONDING) {
            return false;
        }

        byte[] addr = Utils.getBytesFromAddress(device.getAddress());
        return pinReplyNative(addr, accept, len, pinCode);
    }

     boolean setPasskey(BluetoothDevice device, boolean accept, int len, byte[] passkey) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null || deviceProp.getBondState() != BluetoothDevice.BOND_BONDING) {
            return false;
        }

        byte[] addr = Utils.getBytesFromAddress(device.getAddress());
        return sspReplyNative(addr, AbstractionLayer.BT_SSP_VARIANT_PASSKEY_ENTRY, accept,
                Utils.byteArrayToInt(passkey));
    }

     boolean setPairingConfirmation(BluetoothDevice device, boolean accept) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null || deviceProp.getBondState() != BluetoothDevice.BOND_BONDING) {
            return false;
        }

        byte[] addr = Utils.getBytesFromAddress(device.getAddress());
        return sspReplyNative(addr, AbstractionLayer.BT_SSP_VARIANT_PASSKEY_CONFIRMATION,
                accept, 0);
    }

     void sendConnectionStateChange(BluetoothDevice
            device, int profile, int state, int prevState) {
        // TODO(BT) permission check?
        // Since this is a binder call check if Bluetooth is on still
        if (getState() == BluetoothAdapter.STATE_OFF) return;

        mAdapterProperties.sendConnectionStateChange(device, profile, state, prevState);

    }

     ParcelFileDescriptor connectSocket(BluetoothDevice device, int type,
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

     ParcelFileDescriptor createSocketChannel(int type, String serviceName,
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

     void registerCallback(IBluetoothCallback cb) {
         mCallbacks.register(cb);
      }

      void unregisterCallback(IBluetoothCallback cb) {
         mCallbacks.unregister(cb);
      }

    private static int convertScanModeToHal(int mode) {
        switch (mode) {
            case BluetoothAdapter.SCAN_MODE_NONE:
                return AbstractionLayer.BT_SCAN_MODE_NONE;
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
                return AbstractionLayer.BT_SCAN_MODE_CONNECTABLE;
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                return AbstractionLayer.BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE;
        }
       // errorLog("Incorrect scan mode in convertScanModeToHal");
        return -1;
    }

    static int convertScanModeFromHal(int mode) {
        switch (mode) {
            case AbstractionLayer.BT_SCAN_MODE_NONE:
                return BluetoothAdapter.SCAN_MODE_NONE;
            case AbstractionLayer.BT_SCAN_MODE_CONNECTABLE:
                return BluetoothAdapter.SCAN_MODE_CONNECTABLE;
            case AbstractionLayer.BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                return BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
        }
        //errorLog("Incorrect scan mode in convertScanModeFromHal");
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

    protected void finalize() {
        cleanup();
        if (TRACE_REF) {
            synchronized (AdapterService.class) {
                sRefCount--;
                Log.d(TAG, "REFCOUNT: FINALIZED. INSTANCE_COUNT= " + sRefCount);
            }
        }
    }
}
