/*
 * Copyright (C) 2013 The Linux Foundation. All rights reserved
 * Not a Contribution.
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2013-14 The Linux Foundation. All rights reserved
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

/**
 * @hide
 */

package com.android.bluetooth.btservice;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothRemoteDiRecord;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.a2dp.A2dpSinkService;
import com.android.bluetooth.hid.HidService;
import com.android.bluetooth.hid.HidDevService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.hdp.HealthService;
import com.android.bluetooth.hfpclient.HeadsetClientService;
import com.android.bluetooth.pan.PanService;
import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.List;
import android.content.pm.PackageManager;
import android.os.ServiceManager;

public class AdapterService extends Service {
    private static final String TAG = "BluetoothAdapterService";
    private static final boolean DBG = false;
    private static final boolean TRACE_REF = true;
    private static final int MIN_ADVT_INSTANCES_FOR_MA = 5;
    private static final int MIN_OFFLOADED_FILTERS = 10;
    private static final int MIN_OFFLOADED_SCAN_STORAGE_BYTES = 2048;
    private static final String delayConnectTimeoutDevice[] = {"00:23:3D"}; // volkswagen carkit
    //For Debugging only
    private static int sRefCount=0;

    private int mStackReportedState;
    private int mTxTimeTotalMs;
    private int mRxTimeTotalMs;
    private int mIdleTimeTotalMs;
    private int mEnergyUsedTotalVoltAmpSecMicro;

    public static final String ACTION_LOAD_ADAPTER_PROPERTIES =
        "com.android.bluetooth.btservice.action.LOAD_ADAPTER_PROPERTIES";
    public static final String ACTION_SERVICE_STATE_CHANGED =
        "com.android.bluetooth.btservice.action.STATE_CHANGED";
    public static final String EXTRA_ACTION="action";
    public static final int PROFILE_CONN_CONNECTED  = 1;
    public static final int PROFILE_CONN_REJECTED  = 2;

    private static final String ACTION_ALARM_WAKEUP =
        "com.android.bluetooth.btservice.action.ALARM_WAKEUP";

    static final ParcelUuid[] A2DP_SOURCE_SINK_UUIDS = {
        BluetoothUuid.AudioSource,
        BluetoothUuid.AudioSink
    };
    static final String BLUETOOTH_ADMIN_PERM =
        android.Manifest.permission.BLUETOOTH_ADMIN;
    public static final String BLUETOOTH_PRIVILEGED =
                android.Manifest.permission.BLUETOOTH_PRIVILEGED;
    static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;
    static final String RECEIVE_MAP_PERM = android.Manifest.permission.RECEIVE_BLUETOOTH_MAP;

    private static final String PHONEBOOK_ACCESS_PERMISSION_PREFERENCE_FILE =
            "phonebook_access_permission";
    private static final String MESSAGE_ACCESS_PERMISSION_PREFERENCE_FILE =
            "message_access_permission";

    private static final int ADAPTER_SERVICE_TYPE=Service.START_STICKY;

    static {
        System.load("/system/lib/libbluetooth_jni.so");
        classInitNative();
    }
    private static AdapterService sAdapterService;
    public static synchronized AdapterService getAdapterService(){
        if (sAdapterService != null && !sAdapterService.mCleaningUp) {
            Log.d(TAG, "getAdapterService() - returning " + sAdapterService);
            return sAdapterService;
        }
        if (DBG)  {
            if (sAdapterService == null) {
                Log.d(TAG, "getAdapterService() - Service not available");
            } else if (sAdapterService.mCleaningUp) {
                Log.d(TAG,"getAdapterService() - Service is cleaning up");
            }
        }
        return null;
    }

    private static synchronized void setAdapterService(AdapterService instance) {
        if (instance != null && !instance.mCleaningUp) {
            if (DBG) Log.d(TAG, "setAdapterService() - set to: " + sAdapterService);
            sAdapterService = instance;
        } else {
            if (DBG)  {
                if (sAdapterService == null) {
                    Log.d(TAG, "setAdapterService() - Service not available");
                } else if (sAdapterService.mCleaningUp) {
                    Log.d(TAG,"setAdapterService() - Service is cleaning up");
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
    private boolean mQuietmode = false;

    private AlarmManager mAlarmManager;
    private PendingIntent mPendingAlarm;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;
    private String mWakeLockName;
    private HashSet<String> mDisabledProfiles = new HashSet<String>();

    public AdapterService() {
        super();
        if (TRACE_REF) {
            synchronized (AdapterService.class) {
                sRefCount++;
                debugLog("AdapterService() - REFCOUNT: CREATED. INSTANCE_COUNT" + sRefCount);
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

    public void initProfilePriorities(BluetoothDevice device, ParcelUuid[] mUuids) {
        if(mUuids == null) return;
        Message m = mHandler.obtainMessage(MESSAGE_PROFILE_INIT_PRIORITIES);
        m.obj = device;
        m.arg1 = mUuids.length;
        Bundle b = new Bundle(1);
        for(int i=0; i<mUuids.length; i++) {
            b.putParcelable("uuids" + i, mUuids[i]);
        }
        m.setData(b);
        mHandler.sendMessage(m);
    }

    private void processInitProfilePriorities (BluetoothDevice device, ParcelUuid[] uuids){
        HidService hidService = HidService.getHidService();
        A2dpService a2dpService = A2dpService.getA2dpService();
        A2dpSinkService a2dpSinkService = A2dpSinkService.getA2dpSinkService();
        HeadsetService headsetService = HeadsetService.getHeadsetService();

        // Set profile priorities only for the profiles discovered on the remote device.
        // This avoids needless auto-connect attempts to profiles non-existent on the remote device
        if ((hidService != null) &&
            (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Hid) ||
             BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Hogp)) &&
            (hidService.getPriority(device) == BluetoothProfile.PRIORITY_UNDEFINED)){
            hidService.setPriority(device,BluetoothProfile.PRIORITY_ON);
        }

        // If we do not have a stored priority for A2DP then default to on.
        if ((a2dpService != null) &&
            ((BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.AudioSink) ||
            BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.AdvAudioDist)) &&
            (a2dpService.getPriority(device) == BluetoothProfile.PRIORITY_UNDEFINED))){
            a2dpService.setPriority(device,BluetoothProfile.PRIORITY_ON);
        }

        if ((a2dpSinkService != null) &&
            ((BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.AudioSource) ||
                BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.AdvAudioDist)) &&
            (a2dpSinkService.getPriority(device) == BluetoothProfile.PRIORITY_UNDEFINED))){
            a2dpSinkService.setPriority(device,BluetoothProfile.PRIORITY_ON);
        }

        if ((headsetService != null) &&
            ((BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.HSP) ||
                    BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Handsfree)) &&
            (headsetService.getPriority(device) == BluetoothProfile.PRIORITY_UNDEFINED))){
            headsetService.setPriority(device,BluetoothProfile.PRIORITY_ON);
        }
    }

    private void processProfileStateChanged(BluetoothDevice device, int profileId, int newState, int prevState) {
        if (((profileId == BluetoothProfile.A2DP) ||(profileId == BluetoothProfile.HEADSET)) &&
             (newState == BluetoothProfile.STATE_CONNECTED)){
            debugLog( "Profile connected. Schedule missing profile connection if any");
            connectOtherProfile(device, PROFILE_CONN_CONNECTED);
            setProfileAutoConnectionPriority(device, profileId);
        }
        if ((profileId == BluetoothProfile.A2DP_SINK) && (newState == BluetoothProfile.STATE_CONNECTED)) {
            setProfileAutoConnectionPriority(device, profileId);
        }
        IBluetooth.Stub binder = mBinder;
        if (binder != null) {
            try {
                binder.sendConnectionStateChange(device, profileId, newState,prevState);
            } catch (RemoteException re) {
                errorLog("" + re);
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
        debugLog("onProfileServiceStateChange() serviceName=" + serviceName
            + ", state=" + state +", doUpdate=" + doUpdate);

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
            synchronized (mProfileServicesState) {
                Iterator<Map.Entry<String,Integer>> i = mProfileServicesState.entrySet().iterator();
                while (i.hasNext()) {
                    Map.Entry<String,Integer> entry = i.next();
                    if (BluetoothAdapter.STATE_OFF != entry.getValue()) {
                        debugLog("onProfileServiceStateChange() - Profile still running: "
                            + entry.getKey());
                        return;
                    }
                }
            }
            debugLog("onProfileServiceStateChange() - All profile services stopped...");
            //Send message to state machine
            mProfilesStarted=false;
            mAdapterStateMachine.sendMessage(mAdapterStateMachine.obtainMessage(AdapterState.STOPPED));
        } else if (isTurningOn) {
            //Process start pending
            //Check if all services are started if so, update state
            synchronized (mProfileServicesState) {
                Iterator<Map.Entry<String,Integer>> i = mProfileServicesState.entrySet().iterator();
                while (i.hasNext()) {
                    Map.Entry<String,Integer> entry = i.next();
                    if (BluetoothAdapter.STATE_ON != entry.getValue()
                            && !mDisabledProfiles.contains(entry.getKey())) {
                        debugLog("onProfileServiceStateChange() - Profile still not running:"
                            + entry.getKey());
                        return;
                    }
                }
            }
            debugLog("onProfileServiceStateChange() - All profile services started.");
            mProfilesStarted=true;
            //Send message to state machine
            mAdapterStateMachine.sendMessage(mAdapterStateMachine.obtainMessage(AdapterState.STARTED));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        debugLog("onCreate()");
        mBinder = new AdapterServiceBinder(this);
        mAdapterProperties = new AdapterProperties(this);
        mAdapterStateMachine =  AdapterState.make(this, mAdapterProperties);
        mJniCallbacks =  new JniCallbacks(mAdapterStateMachine, mAdapterProperties);
        initNative();
        mNativeAvailable=true;
        mCallbacks = new RemoteCallbackList<IBluetoothCallback>();
        //Load the name and address
        getAdapterPropertyNative(AbstractionLayer.BT_PROPERTY_BDADDR);
        getAdapterPropertyNative(AbstractionLayer.BT_PROPERTY_BDNAME);
        mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        registerReceiver(mAlarmBroadcastReceiver, new IntentFilter(ACTION_ALARM_WAKEUP));
    }

    @Override
    public IBinder onBind(Intent intent) {
        debugLog("onBind()");
        return mBinder;
    }
    public boolean onUnbind(Intent intent) {
        if (getState() == BluetoothAdapter.STATE_OFF) {
            if (DBG) debugLog("onUnbind, calling cleanup");
            cleanup();
            return super.onUnbind(intent);
        }
        return false;
    }

    public void onDestroy() {
        debugLog("onDestroy()");
    }

    void processStart() {
        debugLog("processStart()");
        Class[] supportedProfileServices = Config.getSupportedProfiles();
        //Initialize data objects
        for (int i=0; i < supportedProfileServices.length;i++) {
            mProfileServicesState.put(supportedProfileServices[i].getName(),BluetoothAdapter.STATE_OFF);
        }
        mRemoteDevices = new RemoteDevices(mPowerManager, this);
        mAdapterProperties.init(mRemoteDevices);

        debugLog("processStart() - Make Bond State Machine");
        mBondStateMachine = BondStateMachine.make(this, mAdapterProperties, mRemoteDevices);

        mJniCallbacks.init(mBondStateMachine,mRemoteDevices);

        //FIXME: Set static instance here???
        setAdapterService(this);

        checkA2dpState();

        checkHidState();

        checkHfpState();

        //Start profile services
        if (!mProfilesStarted && supportedProfileServices.length >0) {
            //Startup all profile services
            setProfileServiceState(supportedProfileServices,BluetoothAdapter.STATE_ON);
        }else {
            debugLog("processStart() - Profile Services alreay started");
            mAdapterStateMachine.sendMessage(mAdapterStateMachine.obtainMessage(AdapterState.STARTED));
        }
    }

    void startBluetoothDisable() {
        mAdapterStateMachine.sendMessage(mAdapterStateMachine.obtainMessage(AdapterState.BEGIN_DISABLE));
    }

    boolean stopProfileServices() {
        Class[] supportedProfileServices = Config.getSupportedProfiles();
        Log.d(TAG,"mProfilesStarted : " + mProfilesStarted + " supportedProfileServices.length : " +
            supportedProfileServices.length);
        if (mProfilesStarted && supportedProfileServices.length>0) {
            setProfileServiceState(supportedProfileServices,BluetoothAdapter.STATE_OFF);
            return true;
        }
        debugLog("stopProfileServices() - No profiles services to stop or already stopped.");
        return false;
    }

     void updateAdapterState(int prevState, int newState){
        if (mCallbacks !=null) {
            int n=mCallbacks.beginBroadcast();
            debugLog("updateAdapterState() - Broadcasting state to " + n + " receivers.");
            for (int i=0; i <n;i++) {
                try {
                    mCallbacks.getBroadcastItem(i).onBluetoothStateChange(prevState,newState);
                }  catch (RemoteException e) {
                    debugLog("updateAdapterState() - Callback #" + i + " failed ("  + e + ")");
                }
            }
            mCallbacks.finishBroadcast();
        }
    }

    void cleanup () {
        debugLog("cleanup()");
        if (mCleaningUp) {
            errorLog("cleanup() - Service already starting to cleanup, ignoring request...");
            return;
        }

        mCleaningUp = true;

        unregisterReceiver(mAlarmBroadcastReceiver);

        if (mPendingAlarm != null) {
            mAlarmManager.cancel(mPendingAlarm);
            mPendingAlarm = null;
        }

        // This wake lock release may also be called concurrently by
        // {@link #releaseWakeLock(String lockName)}, so a synchronization is needed here.
        synchronized (this) {
            if (mWakeLock != null) {
                mWakeLock.release();
                mWakeLock = null;
            }
        }

        if (mAdapterStateMachine != null) {
            mAdapterStateMachine.doQuit();
            mAdapterStateMachine.cleanup();
        }

        if (mBondStateMachine != null) {
            mBondStateMachine.doQuit();
            mBondStateMachine.cleanup();
        }

        if (mRemoteDevices != null) {
            mRemoteDevices.cleanup();
        }

        if (mNativeAvailable) {
            debugLog("cleanup() - Cleaning up adapter native");
            cleanupNative();
            mNativeAvailable=false;
        }

        if (mAdapterProperties != null) {
            mAdapterProperties.cleanup();
        }

        if (mJniCallbacks != null) {
            mJniCallbacks.cleanup();
        }

        if (mProfileServicesState != null) {
            mProfileServicesState.clear();
        }

        if (mDisabledProfiles != null) {
            mDisabledProfiles.clear();
        }

        clearAdapterService();

        if (mBinder != null) {
            mBinder.cleanup();
            mBinder = null;  //Do not remove. Otherwise Binder leak!
        }

        if (mCallbacks !=null) {
            mCallbacks.kill();
        }

        debugLog("cleanup() - Bluetooth process exited normally.");
    }

    private static final int MESSAGE_PROFILE_SERVICE_STATE_CHANGED = 1;
    private static final int MESSAGE_PROFILE_CONNECTION_STATE_CHANGED = 20;
    private static final int MESSAGE_CONNECT_OTHER_PROFILES = 30;
    private static final int MESSAGE_PROFILE_INIT_PRIORITIES=40;
    private static final int CONNECT_OTHER_PROFILES_TIMEOUT= 6000;
    private static final int CONNECT_OTHER_PROFILES_TIMEOUT_DEYALED = 10000;
    private static final int MESSAGE_AUTO_CONNECT_PROFILES = 50;
    private static final int AUTO_CONNECT_PROFILES_TIMEOUT = 500;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            debugLog("handleMessage() - Message: " + msg.what);

            switch (msg.what) {
                case MESSAGE_PROFILE_SERVICE_STATE_CHANGED: {
                    debugLog("handleMessage() - MESSAGE_PROFILE_SERVICE_STATE_CHANGED");
                    processProfileServiceStateChanged((String) msg.obj, msg.arg1);
                }
                    break;
                case MESSAGE_PROFILE_CONNECTION_STATE_CHANGED: {
                    debugLog( "handleMessage() - MESSAGE_PROFILE_CONNECTION_STATE_CHANGED");
                    processProfileStateChanged((BluetoothDevice) msg.obj, msg.arg1,msg.arg2, msg.getData().getInt("prevState",BluetoothAdapter.ERROR));
                }
                    break;
                case MESSAGE_PROFILE_INIT_PRIORITIES: {
                    debugLog( "handleMessage() - MESSAGE_PROFILE_INIT_PRIORITIES");
                    ParcelUuid[] mUuids = new ParcelUuid[msg.arg1];
                    for(int i=0; i<mUuids.length; i++) {
                        mUuids[i] = msg.getData().getParcelable("uuids" + i);
                    }
                    processInitProfilePriorities((BluetoothDevice) msg.obj,
                            mUuids);
                }
                    break;
                case MESSAGE_CONNECT_OTHER_PROFILES: {
                    debugLog( "handleMessage() - MESSAGE_CONNECT_OTHER_PROFILES");
                    processConnectOtherProfiles((BluetoothDevice) msg.obj,msg.arg1);
                }
                    break;
                case MESSAGE_AUTO_CONNECT_PROFILES: {
                    if (DBG) debugLog( "MESSAGE_AUTO_CONNECT_PROFILES");
                    autoConnectProfilesDelayed();
                    break;
                }
            }
        }
    };

    @SuppressWarnings("rawtypes")
    private void setProfileServiceState(Class[] services, int state) {
        if (state != BluetoothAdapter.STATE_ON && state != BluetoothAdapter.STATE_OFF) {
            debugLog("setProfileServiceState() - Invalid state, leaving...");
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
                debugLog("setProfileServiceState() - Unable to " 
                    + (state == BluetoothAdapter.STATE_OFF ? "start" : "stop" )
                    + " service " + serviceName
                    + ". Invalid state: " + serviceState);
                continue;
            }

            debugLog("setProfileServiceState() - "
                + (state == BluetoothAdapter.STATE_OFF ? "Stopping" : "Starting")
                + " service " + serviceName);

            if (state == BluetoothAdapter.STATE_ON && mDisabledProfiles.contains(serviceName)) {
                Log.i(TAG, "skipping " + serviceName + " (disabled)");
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
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
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
            // don't check caller, may be called from system UI
            AdapterService service = getService();
            if (service == null) return false;
            return service.isEnabled();
        }

        public int getState() {
            // don't check caller, may be called from system UI
            AdapterService service = getService();
            if (service == null) return  BluetoothAdapter.STATE_OFF;
            return service.getState();
        }

        public boolean enable() {
            if ((Binder.getCallingUid() != Process.SYSTEM_UID) &&
                (!Utils.checkCaller())) {
                Log.w(TAG, "enable() - Not allowed for non-active user and non system user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.enable();
        }

        public boolean enableNoAutoConnect() {
            if ((Binder.getCallingUid() != Process.SYSTEM_UID) &&
                (!Utils.checkCaller())) {
                Log.w(TAG, "enableNoAuto() - Not allowed for non-active user and non system user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.enableNoAutoConnect();
        }

        public boolean disable() {
            if ((Binder.getCallingUid() != Process.SYSTEM_UID) &&
                (!Utils.checkCaller())) {
                Log.w(TAG, "disable() - Not allowed for non-active user and non system user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.disable();
        }

        public String getAddress() {
            if ((Binder.getCallingUid() != Process.SYSTEM_UID) &&
                (!Utils.checkCaller())) {
                Log.w(TAG, "getAddress() - Not allowed for non-active user and non system user");
                return null;
            }

            AdapterService service = getService();
            if (service == null) return null;
            return service.getAddress();
        }

        public ParcelUuid[] getUuids() {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "getUuids() - Not allowed for non-active user");
                return new ParcelUuid[0];
            }

            AdapterService service = getService();
            if (service == null) return new ParcelUuid[0];
            return service.getUuids();
        }

        public String getName() {
            if ((Binder.getCallingUid() != Process.SYSTEM_UID) &&
                (!Utils.checkCaller())) {
                Log.w(TAG, "getName() - Not allowed for non-active user and non system user");
                return null;
            }

            AdapterService service = getService();
            if (service == null) return null;
            return service.getName();
        }

        public boolean setName(String name) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "setName() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.setName(name);
        }

        public int getScanMode() {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "getScanMode() - Not allowed for non-active user");
                return BluetoothAdapter.SCAN_MODE_NONE;
            }

            AdapterService service = getService();
            if (service == null) return BluetoothAdapter.SCAN_MODE_NONE;
            return service.getScanMode();
        }

        public boolean setScanMode(int mode, int duration) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "setScanMode() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.setScanMode(mode,duration);
        }

        public int getDiscoverableTimeout() {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "getDiscoverableTimeout() - Not allowed for non-active user");
                return 0;
            }

            AdapterService service = getService();
            if (service == null) return 0;
            return service.getDiscoverableTimeout();
        }

        public boolean setDiscoverableTimeout(int timeout) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "setDiscoverableTimeout() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.setDiscoverableTimeout(timeout);
        }

        public boolean startDiscovery() {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "startDiscovery() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.startDiscovery();
        }

        public boolean cancelDiscovery() {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "cancelDiscovery() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.cancelDiscovery();
        }
        public boolean isDiscovering() {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "isDiscovering() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.isDiscovering();
        }

        public BluetoothDevice[] getBondedDevices() {
            // don't check caller, may be called from system UI
            AdapterService service = getService();
            if (service == null) return new BluetoothDevice[0];
            return service.getBondedDevices();
        }

        public int getAdapterConnectionState() {
            // don't check caller, may be called from system UI
            AdapterService service = getService();
            if (service == null) return BluetoothAdapter.STATE_DISCONNECTED;
            return service.getAdapterConnectionState();
        }

        public int getProfileConnectionState(int profile) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "getProfileConnectionState- Not allowed for non-active user");
                return BluetoothProfile.STATE_DISCONNECTED;
            }

            AdapterService service = getService();
            if (service == null) return BluetoothProfile.STATE_DISCONNECTED;
            return service.getProfileConnectionState(profile);
        }

        public boolean createBond(BluetoothDevice device, int transport) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "createBond() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.createBond(device, transport);
        }

        public boolean cancelBondProcess(BluetoothDevice device) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "cancelBondProcess() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.cancelBondProcess(device);
        }

        public boolean removeBond(BluetoothDevice device) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "removeBond() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.removeBond(device);
        }

        public int getBondState(BluetoothDevice device) {
            // don't check caller, may be called from system UI
            AdapterService service = getService();
            if (service == null) return BluetoothDevice.BOND_NONE;
            return service.getBondState(device);
        }

        public boolean isConnected(BluetoothDevice device) {
            AdapterService service = getService();
            if (service == null) {
                return false;
            }
            return service.isConnected(device);
        }

        public String getRemoteName(BluetoothDevice device) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "getRemoteName() - Not allowed for non-active user");
                return null;
            }

            AdapterService service = getService();
            if (service == null) return null;
            return service.getRemoteName(device);
        }

        public int getRemoteType(BluetoothDevice device) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "getRemoteType() - Not allowed for non-active user");
                return BluetoothDevice.DEVICE_TYPE_UNKNOWN;
            }

            AdapterService service = getService();
            if (service == null) return BluetoothDevice.DEVICE_TYPE_UNKNOWN;
            return service.getRemoteType(device);
        }

        public String getRemoteAlias(BluetoothDevice device) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "getRemoteAlias() - Not allowed for non-active user");
                return null;
            }

            AdapterService service = getService();
            if (service == null) return null;
            return service.getRemoteAlias(device);
        }

        public boolean setRemoteAlias(BluetoothDevice device, String name) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "setRemoteAlias() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.setRemoteAlias(device, name);
        }

        public boolean getRemoteTrust(BluetoothDevice device) {
            Log.d(TAG,"getRemoteTrust");
            if (!Utils.checkCaller()) {
                Log.w(TAG,"getRemoteTrust(): not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.getRemoteTrust(device);
        }


        public boolean setRemoteTrust(BluetoothDevice device, boolean trustValue) {
            Log.d(TAG,"setRemoteTrust to "+ trustValue);
            if (!Utils.checkCaller()) {
                Log.w(TAG,"setRemoteTrust(): not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.setRemoteTrust(device, trustValue);
        }

        public int getRemoteClass(BluetoothDevice device) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "getRemoteClass() - Not allowed for non-active user");
                return 0;
            }

            AdapterService service = getService();
            if (service == null) return 0;
            return service.getRemoteClass(device);
        }

        public ParcelUuid[] getRemoteUuids(BluetoothDevice device) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "getRemoteUuids() - Not allowed for non-active user");
                return new ParcelUuid[0];
            }

            AdapterService service = getService();
            if (service == null) return new ParcelUuid[0];
            return service.getRemoteUuids(device);
        }

        public boolean fetchRemoteUuids(BluetoothDevice device) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "fetchRemoteUuids() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.fetchRemoteUuids(device);
        }

        public boolean fetchRemoteMasInstances(BluetoothDevice device) {
            if (!Utils.checkCaller()) {
                Log.w(TAG,"fetchMasInstances(): not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.fetchRemoteMasInstances(device);
        }

        public boolean setPin(BluetoothDevice device, boolean accept, int len, byte[] pinCode) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "setPin() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.setPin(device, accept, len, pinCode);
        }

        public boolean setPasskey(BluetoothDevice device, boolean accept, int len, byte[] passkey) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "setPasskey() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.setPasskey(device, accept, len, passkey);
        }

        public boolean setPairingConfirmation(BluetoothDevice device, boolean accept) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "setPairingConfirmation() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.setPairingConfirmation(device, accept);
        }

        public int getPhonebookAccessPermission(BluetoothDevice device) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "getPhonebookAccessPermission() - Not allowed for non-active user");
                return BluetoothDevice.ACCESS_UNKNOWN;
            }

            AdapterService service = getService();
            if (service == null) return BluetoothDevice.ACCESS_UNKNOWN;
            return service.getPhonebookAccessPermission(device);
        }

        public boolean setPhonebookAccessPermission(BluetoothDevice device, int value) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "setPhonebookAccessPermission() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.setPhonebookAccessPermission(device, value);
        }

        public int getMessageAccessPermission(BluetoothDevice device) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "getMessageAccessPermission() - Not allowed for non-active user");
                return BluetoothDevice.ACCESS_UNKNOWN;
            }

            AdapterService service = getService();
            if (service == null) return BluetoothDevice.ACCESS_UNKNOWN;
            return service.getMessageAccessPermission(device);
        }

        public boolean setMessageAccessPermission(BluetoothDevice device, int value) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "setMessageAccessPermission() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.setMessageAccessPermission(device, value);
        }

        public void sendConnectionStateChange(BluetoothDevice
                device, int profile, int state, int prevState) {
            AdapterService service = getService();
            if (service == null) return;
            service.sendConnectionStateChange(device, profile, state, prevState);
        }

        public ParcelFileDescriptor connectSocket(BluetoothDevice device, int type,
                                                  ParcelUuid uuid, int port, int flag) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "connectSocket() - Not allowed for non-active user");
                return null;
            }

            AdapterService service = getService();
            if (service == null) return null;
            return service.connectSocket(device, type, uuid, port, flag);
        }

        public ParcelFileDescriptor createSocketChannel(int type, String serviceName,
                                                        ParcelUuid uuid, int port, int flag) {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "createSocketChannel() - Not allowed for non-active user");
                return null;
            }

            AdapterService service = getService();
            if (service == null) return null;
            return service.createSocketChannel(type, serviceName, uuid, port, flag);
        }

        public int setSocketOpt(int type, int channel, int optionName, byte [] optionVal,
                                                    int optionLen) {
            if (!Utils.checkCaller()) {
                Log.w(TAG,"setSocketOpt(): not allowed for non-active user");
                return -1;
            }

            AdapterService service = getService();
            if (service == null) return -1;
            return service.setSocketOpt(type, channel, optionName, optionVal, optionLen);
        }

        public int getSocketOpt(int type, int channel, int optionName, byte [] optionVal) {
            if (!Utils.checkCaller()) {
                Log.w(TAG,"getSocketOpt(): not allowed for non-active user");
                return -1;
            }

            AdapterService service = getService();
            if (service == null) return -1;
            return service.getSocketOpt(type, channel, optionName, optionVal);
        }

        public BluetoothRemoteDiRecord getRemoteDiRecord(BluetoothDevice device) {
            if (!Utils.checkCaller()) {
                Log.w(TAG,"getRemoteDiRecord: not allowed for non-active user");
                return null;
            }
            AdapterService service = getService();
            if (service == null) return null;
            return service.getRemoteDiRecord(device);
        }


        public boolean configHciSnoopLog(boolean enable) {
            if ((Binder.getCallingUid() != Process.SYSTEM_UID) &&
                (!Utils.checkCaller())) {
                Log.w(TAG, "configHciSnoopLog() - Not allowed for non-active user");
                return false;
            }

            AdapterService service = getService();
            if (service == null) return false;
            return service.configHciSnoopLog(enable);
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

         public boolean isMultiAdvertisementSupported() {
             AdapterService service = getService();
             if (service == null) return false;
             int val = service.getNumOfAdvertisementInstancesSupported();
             return (val >= MIN_ADVT_INSTANCES_FOR_MA);
         }

         public boolean isOffloadedFilteringSupported() {
             AdapterService service = getService();
             if (service == null) return false;
             int val = service.getNumOfOffloadedScanFilterSupported();
             return (val >= MIN_OFFLOADED_FILTERS);
         }

         public boolean isOffloadedScanBatchingSupported() {
             AdapterService service = getService();
             if (service == null) return false;
             int val = service.getOffloadedScanResultStorage();
             return (val >= MIN_OFFLOADED_SCAN_STORAGE_BYTES);
         }

         public boolean isActivityAndEnergyReportingSupported() {
             AdapterService service = getService();
             if (service == null) return false;
             return service.isActivityAndEnergyReportingSupported();
         }

         public void getActivityEnergyInfoFromController() {
             AdapterService service = getService();
             if (service == null) return;
             service.getActivityEnergyInfoFromController();
         }

         public BluetoothActivityEnergyInfo reportActivityInfo() {
             AdapterService service = getService();
             if (service == null) return null;
             return service.reportActivityInfo();
         }
    };


    //----API Methods--------
     boolean isEnabled() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        return mAdapterProperties.getState() == BluetoothAdapter.STATE_ON;
    }

     int getState() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        if (mAdapterProperties == null){
            return  BluetoothAdapter.STATE_OFF;
        }
        else {
            debugLog("getState() - mAdapterProperties: " + mAdapterProperties);
            return mAdapterProperties.getState();
        }
    }

     boolean enable() {
        return enable (false);
    }

      public boolean enableNoAutoConnect() {
         return enable (true);
     }

     public synchronized boolean enable(boolean quietMode) {
         enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                        "Need BLUETOOTH ADMIN permission");
         debugLog("enable() - Enable called with quiet mode status =  " + mQuietmode);
         mQuietmode  = quietMode;
         Message m =
                 mAdapterStateMachine.obtainMessage(AdapterState.USER_TURN_ON);
         mAdapterStateMachine.sendMessage(m);
         return true;
     }

     boolean disable() {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                       "Need BLUETOOTH ADMIN permission");

        debugLog("disable() called...");
        Message m =
                mAdapterStateMachine.obtainMessage(AdapterState.USER_TURN_OFF);
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
            debugLog("getName() - Unexpected exception (" + t + ")");
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

     boolean createBond(BluetoothDevice device, int transport) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
            "Need BLUETOOTH ADMIN permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp != null && deviceProp.getBondState() != BluetoothDevice.BOND_NONE) {
            return false;
        }

        // Pairing is unreliable while scanning, so cancel discovery
        // Note, remove this when native stack improves
        cancelDiscoveryNative();

        Message msg = mBondStateMachine.obtainMessage(BondStateMachine.CREATE_BOND);
        msg.obj = device;
        msg.arg1 = transport;
        mBondStateMachine.sendMessage(msg);
        return true;
    }

      public boolean isQuietModeEnabled() {
          debugLog("isQuetModeEnabled() - Enabled = " + mQuietmode);
          return mQuietmode;
     }

    // Delaying Auto Connect to make sure that all clients
    // are up and running, specially BluetoothHeadset.
    public void autoConnect() {
        if (DBG) debugLog( "delay auto connect by 500 ms");
        if ((mHandler.hasMessages(MESSAGE_AUTO_CONNECT_PROFILES) == false) &&
            (isQuietModeEnabled()== false)) {
            Message m = mHandler.obtainMessage(MESSAGE_AUTO_CONNECT_PROFILES);
            mHandler.sendMessageDelayed(m,AUTO_CONNECT_PROFILES_TIMEOUT);
        }
    }

    private void autoConnectProfilesDelayed(){
        if (getState() != BluetoothAdapter.STATE_ON){
            errorLog("BT is not ON. Exiting autoConnect");
            return;
        }
        if (isQuietModeEnabled() == false) {
            if (DBG) debugLog( "Initiate auto connection on BT on...");
            autoConnectHeadset();
            autoConnectA2dp();
            autoConnectA2dpSink();
        }
        else {
            if (DBG) debugLog( "BT is in Quiet mode. Not initiating  auto connections");
        }
    }

     private void cancelDiscoveryforautoConnect(){
        if (mAdapterProperties.isDiscovering() == true) {
            cancelDiscovery();
        }
    }

     private void autoConnectHeadset(){
        HeadsetService  hsService = HeadsetService.getHeadsetService();

        BluetoothDevice bondedDevices[] = getBondedDevices();
        if ((bondedDevices == null) ||(hsService == null)) {
            return;
        }
        for (BluetoothDevice device : bondedDevices) {
            if (hsService.getPriority(device) == BluetoothProfile.PRIORITY_AUTO_CONNECT ){
                cancelDiscoveryforautoConnect();
                debugLog("autoConnectHeadset() - Connecting HFP with " + device.toString());
                hsService.connect(device);
            }
        }
    }

     private void autoConnectA2dp(){
        A2dpService a2dpSservice = A2dpService.getA2dpService();
        BluetoothDevice bondedDevices[] = getBondedDevices();
        if ((bondedDevices == null) ||(a2dpSservice == null)) {
            return;
        }
        for (BluetoothDevice device : bondedDevices) {
            if (a2dpSservice.getPriority(device) == BluetoothProfile.PRIORITY_AUTO_CONNECT ){
                cancelDiscoveryforautoConnect();
                debugLog("autoConnectA2dp() - Connecting A2DP with " + device.toString());
                a2dpSservice.connect(device);
            }
        }
    }

     private void autoConnectA2dpSink(){
         A2dpSinkService a2dpSinkService = A2dpSinkService.getA2dpSinkService();
         BluetoothDevice bondedDevices[] = getBondedDevices();
         if ((bondedDevices == null) ||(a2dpSinkService == null)) {
             return;
         }
         for (BluetoothDevice device : bondedDevices) {
             if (a2dpSinkService.getPriority(device) == BluetoothProfile.PRIORITY_AUTO_CONNECT ){
                 cancelDiscoveryforautoConnect();
                 a2dpSinkService.connect(device);
             }
         }
     }

     public void connectOtherProfile(BluetoothDevice device, int firstProfileStatus){
        String deviceAddress = device.getAddress();
        boolean isConnectionTimeoutDelayed = false;

        for (int i = 0; i < delayConnectTimeoutDevice.length;i++) {
            if (deviceAddress.indexOf(delayConnectTimeoutDevice[i]) == 0) {
                isConnectionTimeoutDelayed = true;
            }
        }
        if (mHandler.hasMessages(MESSAGE_CONNECT_OTHER_PROFILES) == false) {
            ParcelUuid[] featureUuids = device.getUuids();
            // Some Carkits disconnect just after pairing,Initiate SDP for missing UUID's support
            if ((!(BluetoothUuid.containsAnyUuid(featureUuids, A2DP_SOURCE_SINK_UUIDS))) ||
                    (!(BluetoothUuid.isUuidPresent(featureUuids, BluetoothUuid.Handsfree)))) {
                Log.v(TAG,"Initiate SDP for Missing UUID's support in remote");
                device.fetchUuidsWithSdp();
            }
            Message m = mHandler.obtainMessage(MESSAGE_CONNECT_OTHER_PROFILES);
            m.obj = device;
            m.arg1 = (int)firstProfileStatus;
            if (isConnectionTimeoutDelayed) {
                mHandler.sendMessageDelayed(m,CONNECT_OTHER_PROFILES_TIMEOUT_DEYALED);
            }
            else {
                mHandler.sendMessageDelayed(m,CONNECT_OTHER_PROFILES_TIMEOUT);
            }
        }
    }

     private void processConnectOtherProfiles (BluetoothDevice device, int firstProfileStatus){
        if (getState()!= BluetoothAdapter.STATE_ON){
            return;
        }
        HeadsetService  hsService = HeadsetService.getHeadsetService();
        A2dpService a2dpService = A2dpService.getA2dpService();

        // if any of the profile service is  null, second profile connection not required
        if ((hsService == null) ||(a2dpService == null )){
            return;
        }
        boolean hsConnected = false;
        boolean a2dpConnected =  false;
        List<BluetoothDevice> a2dpConnDevList= a2dpService.getConnectedDevices();
        List<BluetoothDevice> hfConnDevList= hsService.getConnectedDevices();
        // Check if the device is in disconnected state and if so return
        // We ned to connect other profile only if one of the profile is still in connected state
        // This is required to avoide a race condition in which profiles would
        // automaticlly connect if the disconnection is initiated within 6 seconds of connection
        //First profile connection being rejected is an exception
        if((hfConnDevList.isEmpty() && a2dpConnDevList.isEmpty())&&
            (PROFILE_CONN_CONNECTED  == firstProfileStatus)){
            return;
        }
        if(!a2dpConnDevList.isEmpty()) {
            for (BluetoothDevice a2dpDevice : a2dpConnDevList)
            {
                if(a2dpDevice.equals(device))
                {
                    a2dpConnected = true;
                }
            }
        }
        if(!hfConnDevList.isEmpty()) {
            for (BluetoothDevice hsDevice : hfConnDevList)
            {
                if(hsDevice.equals(device))
                {
                    hsConnected = true;
                }
            }
        }
       // This change makes sure that we try to re-connect
       // the profile if its connection failed and priority
       // for desired profile is ON.

        if((hfConnDevList.isEmpty()) &&
            (hsService.getPriority(device) >= BluetoothProfile.PRIORITY_ON) &&
            (a2dpConnected || (a2dpService.getPriority(device) == BluetoothProfile.PRIORITY_OFF))) {
            hsService.connect(device);
        }
        else if((a2dpConnDevList.isEmpty()) &&
            (a2dpService.getPriority(device) >= BluetoothProfile.PRIORITY_ON) &&
            (hsConnected || (hsService.getPriority(device) == BluetoothProfile.PRIORITY_OFF))) {
            a2dpService.connect(device);
        }
    }

     private void adjustOtherHeadsetPriorities(HeadsetService  hsService,
                                                    List<BluetoothDevice> connectedDeviceList) {
        for (BluetoothDevice device : getBondedDevices()) {
           if (hsService.getPriority(device) >= BluetoothProfile.PRIORITY_AUTO_CONNECT &&
               !connectedDeviceList.contains(device)) {
               hsService.setPriority(device, BluetoothProfile.PRIORITY_ON);
           }
        }
     }

     private void adjustOtherSinkPriorities(A2dpService a2dpService,
                                                BluetoothDevice connectedDevice) {
         for (BluetoothDevice device : getBondedDevices()) {
             if (a2dpService.getPriority(device) >= BluetoothProfile.PRIORITY_AUTO_CONNECT &&
                 !device.equals(connectedDevice)) {
                 a2dpService.setPriority(device, BluetoothProfile.PRIORITY_ON);
             }
         }
     }

     private void adjustOtherSrcPriorities(A2dpSinkService a2dpSinkService,
                                               BluetoothDevice connectedDevice) {
         for (BluetoothDevice device : getBondedDevices()) {
             if (a2dpSinkService.getPriority(device) >= BluetoothProfile.PRIORITY_AUTO_CONNECT &&
                  !device.equals(connectedDevice)) {
                a2dpSinkService.setPriority(device, BluetoothProfile.PRIORITY_ON);
            }
         }
     }

     void setProfileAutoConnectionPriority (BluetoothDevice device, int profileId){
         if (profileId == BluetoothProfile.HEADSET) {
             HeadsetService  hsService = HeadsetService.getHeadsetService();
             if ((hsService != null) &&
                (BluetoothProfile.PRIORITY_AUTO_CONNECT != hsService.getPriority(device))){
                 List<BluetoothDevice> deviceList = hsService.getConnectedDevices();
                 adjustOtherHeadsetPriorities(hsService, deviceList);
                 hsService.setPriority(device,BluetoothProfile.PRIORITY_AUTO_CONNECT);
             }
         }
         else if (profileId ==  BluetoothProfile.A2DP) {
             A2dpService a2dpService = A2dpService.getA2dpService();
             if ((a2dpService != null) &&
                (BluetoothProfile.PRIORITY_AUTO_CONNECT != a2dpService.getPriority(device))){
                 adjustOtherSinkPriorities(a2dpService, device);
                 a2dpService.setPriority(device,BluetoothProfile.PRIORITY_AUTO_CONNECT);
             }
         }
         else if (profileId ==  BluetoothProfile.A2DP_SINK) {
             A2dpSinkService a2dpSinkService = A2dpSinkService.getA2dpSinkService();
             if ((a2dpSinkService != null) &&
                (BluetoothProfile.PRIORITY_AUTO_CONNECT != a2dpSinkService.getPriority(device))){
                 adjustOtherSrcPriorities(a2dpSinkService, device);
                 a2dpSinkService.setPriority(device,BluetoothProfile.PRIORITY_AUTO_CONNECT);
             }
         }
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

    boolean isConnected(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        byte[] addr = Utils.getBytesFromAddress(device.getAddress());
        return isConnectedNative(addr);
    }

     String getRemoteName(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) return null;
        return deviceProp.getName();
    }

     int getRemoteType(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) return BluetoothDevice.DEVICE_TYPE_UNKNOWN;
        return deviceProp.getDeviceType();
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

    boolean getRemoteTrust(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) return false;
        return deviceProp.getTrust();
    }

    boolean setRemoteTrust(BluetoothDevice device, boolean trustValue) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) return false;
        deviceProp.setTrust(trustValue);
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

      boolean fetchRemoteMasInstances(BluetoothDevice device) {
         enforceCallingOrSelfPermission(RECEIVE_MAP_PERM, "Need RECEIVE BLUETOOTH MAP permission");
         mRemoteDevices.fetchMasInstances(device);
         return true;
     }

     boolean setPin(BluetoothDevice device, boolean accept, int len, byte[] pinCode) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                       "Need BLUETOOTH ADMIN permission");
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
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                       "Need BLUETOOTH ADMIN permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null || deviceProp.getBondState() != BluetoothDevice.BOND_BONDING) {
            return false;
        }

        byte[] addr = Utils.getBytesFromAddress(device.getAddress());
        return sspReplyNative(addr, AbstractionLayer.BT_SSP_VARIANT_PASSKEY_CONFIRMATION,
                accept, 0);
    }

    int getPhonebookAccessPermission(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        SharedPreferences pref = getSharedPreferences(PHONEBOOK_ACCESS_PERMISSION_PREFERENCE_FILE,
                Context.MODE_PRIVATE);
        if (!pref.contains(device.getAddress())) {
            return BluetoothDevice.ACCESS_UNKNOWN;
        }
        return pref.getInt(device.getAddress(), BluetoothDevice.ACCESS_UNKNOWN);
    }

    boolean setPhonebookAccessPermission(BluetoothDevice device, int value) {
        enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED,
                                       "Need BLUETOOTH PRIVILEGED permission");
        SharedPreferences pref = getSharedPreferences(PHONEBOOK_ACCESS_PERMISSION_PREFERENCE_FILE,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        if (value == BluetoothDevice.ACCESS_UNKNOWN) {
            editor.remove(device.getAddress());
        } else {
            editor.putInt(device.getAddress(), value);
        }
        return editor.commit();
    }

    int getMessageAccessPermission(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        SharedPreferences pref = getSharedPreferences(MESSAGE_ACCESS_PERMISSION_PREFERENCE_FILE,
                Context.MODE_PRIVATE);
        if (!pref.contains(device.getAddress())) {
            return BluetoothDevice.ACCESS_UNKNOWN;
        }
        return pref.getBoolean(device.getAddress(), false)
                ? BluetoothDevice.ACCESS_ALLOWED : BluetoothDevice.ACCESS_REJECTED;
    }

    boolean setMessageAccessPermission(BluetoothDevice device, int value) {
        enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED,
                                       "Need BLUETOOTH PRIVILEGED permission");
        SharedPreferences pref = getSharedPreferences(MESSAGE_ACCESS_PERMISSION_PREFERENCE_FILE,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        if (value == BluetoothDevice.ACCESS_UNKNOWN) {
            editor.remove(device.getAddress());
        } else {
            editor.putBoolean(device.getAddress(), value == BluetoothDevice.ACCESS_ALLOWED);
        }
        return editor.commit();
    }

     void sendConnectionStateChange(BluetoothDevice
            device, int profile, int state, int prevState) {
        // TODO(BT) permission check?
        // Since this is a binder call check if Bluetooth is on still
        if (getState() == BluetoothAdapter.STATE_OFF) return;

        mAdapterProperties.sendConnectionStateChange(device, profile, state, prevState);

    }

    BluetoothRemoteDiRecord getRemoteDiRecord(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        DeviceProperties deviceProp = mRemoteDevices.getDeviceProperties(device);
        if (deviceProp == null) return null;
        return deviceProp.getRemoteDiRecord();
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

     int setSocketOpt(int type, int channel, int optionName, byte [] optionVal,
             int optionLen) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        return setSocketOptNative(type, channel, optionName, optionVal, optionLen);
     }

     int getSocketOpt(int type, int channel, int optionName, byte [] optionVal) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

        return getSocketOptNative(type, channel, optionName, optionVal);
     }

    boolean configHciSnoopLog(boolean enable) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return configHciSnoopLogNative(enable);
    }

     void registerCallback(IBluetoothCallback cb) {
         mCallbacks.register(cb);
      }

      void unregisterCallback(IBluetoothCallback cb) {
         mCallbacks.unregister(cb);
      }

    public int getNumOfAdvertisementInstancesSupported() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mAdapterProperties.getNumOfAdvertisementInstancesSupported();
    }

    public boolean isRpaOffloadSupported() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mAdapterProperties.isRpaOffloadSupported();
    }

    public int getNumOfOffloadedIrkSupported() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mAdapterProperties.getNumOfOffloadedIrkSupported();
    }

    public int getNumOfOffloadedScanFilterSupported() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mAdapterProperties.getNumOfOffloadedScanFilterSupported();
    }

    public int getOffloadedScanResultStorage() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mAdapterProperties.getOffloadedScanResultStorage();
    }

    private boolean isActivityAndEnergyReportingSupported() {
          enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, "Need BLUETOOTH permission");
          return mAdapterProperties.isActivityAndEnergyReportingSupported();
    }

    private void getActivityEnergyInfoFromController() {
        enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, "Need BLUETOOTH permission");
        if (isActivityAndEnergyReportingSupported()) {
            readEnergyInfo();
        }
    }

    private BluetoothActivityEnergyInfo reportActivityInfo() {
        enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, "Need BLUETOOTH permission");
        BluetoothActivityEnergyInfo info =
            new BluetoothActivityEnergyInfo(mStackReportedState, mTxTimeTotalMs,
                    mRxTimeTotalMs, mIdleTimeTotalMs, mEnergyUsedTotalVoltAmpSecMicro);
        // Read on clear values; a record of data is created with
        // timstamp and new samples are collected until read again
        mStackReportedState = 0;
        mTxTimeTotalMs = 0;
        mRxTimeTotalMs = 0;
        mIdleTimeTotalMs = 0;
        mEnergyUsedTotalVoltAmpSecMicro = 0;
        return info;
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

    // This function is called from JNI. It allows native code to set a single wake
    // alarm. If an alarm is already pending and a new request comes in, the alarm
    // will be rescheduled (i.e. the previously set alarm will be cancelled).
    private boolean setWakeAlarm(long delayMillis, boolean shouldWake) {
        synchronized (this) {
            if (mPendingAlarm != null) {
                mAlarmManager.cancel(mPendingAlarm);
            }

            long wakeupTime = SystemClock.elapsedRealtime() + delayMillis;
            int type = shouldWake
                ? AlarmManager.ELAPSED_REALTIME_WAKEUP
                : AlarmManager.ELAPSED_REALTIME;

            Intent intent = new Intent(ACTION_ALARM_WAKEUP);
            mPendingAlarm = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_ONE_SHOT);
            mAlarmManager.setExact(type, wakeupTime, mPendingAlarm);
            return true;
        }
    }

    // This function is called from JNI. It allows native code to acquire a single wake lock.
    // If the wake lock is already held, this function returns success. Although this function
    // only supports acquiring a single wake lock at a time right now, it will eventually be
    // extended to allow acquiring an arbitrary number of wake locks. The current interface
    // takes |lockName| as a parameter in anticipation of that implementation.
    private boolean acquireWakeLock(String lockName) {
        if (mWakeLock != null) {
            if (!lockName.equals(mWakeLockName)) {
                errorLog("Multiple wake lock acquisition attempted; aborting: " + lockName);
                return false;
            }

            // We're already holding the desired wake lock so return success.
            if (mWakeLock.isHeld()) {
                return true;
            }
        }

        mWakeLockName = lockName;
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockName);
        mWakeLock.acquire();
        return true;
    }

    // This function is called from JNI. It allows native code to release a wake lock acquired
    // by |acquireWakeLock|. If the wake lock is not held, this function returns failure.
    // Note that the release() call is also invoked by {@link #cleanup()} so a synchronization is
    // needed here. See the comment for |acquireWakeLock| for an explanation of the interface.
    private boolean releaseWakeLock(String lockName) {
        synchronized (this) {
            if (mWakeLock == null) {
                errorLog("Repeated wake lock release; aborting release: " + lockName);
                return false;
            }

            mWakeLock.release();
            mWakeLock = null;
            mWakeLockName = null;
        }
        return true;
    }

    private void energyInfoCallback (int status, int ctrl_state,
        long tx_time, long rx_time, long idle_time, long energy_used)
        throws RemoteException {
        // ToDo: Update only status is valid
        if (ctrl_state >= BluetoothActivityEnergyInfo.BT_STACK_STATE_INVALID &&
                ctrl_state <= BluetoothActivityEnergyInfo.BT_STACK_STATE_STATE_IDLE) {
            mStackReportedState = ctrl_state;
            mTxTimeTotalMs += tx_time;
            mRxTimeTotalMs += rx_time;
            mIdleTimeTotalMs += idle_time;
            // Energy is product of mA, V and ms
            mEnergyUsedTotalVoltAmpSecMicro += energy_used;
        }

        if (DBG) {
            Log.d(TAG, "energyInfoCallback  " + "status = " + status +
            "tx_time = " + tx_time + "rx_time = " + rx_time +
            "idle_time = " + idle_time + "energy_used = " + energy_used +
            "ctrl_state = " + ctrl_state);
        }
    }

    private void debugLog(String msg) {
        if (DBG) Log.d(TAG +"(" +hashCode()+")", msg);
    }

    private void errorLog(String msg) {
        Log.e(TAG +"(" +hashCode()+")", msg);
    }

    private final BroadcastReceiver mAlarmBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (AdapterService.this) {
                mPendingAlarm = null;
                alarmFiredNative();
            }
        }
    };

    private native static void classInitNative();
    private native boolean initNative();
    private native void cleanupNative();
    /*package*/ native void ssrcleanupNative(boolean cleanup);
    /*package*/ native boolean enableNative();
    /*package*/ native boolean disableNative();
    /*package*/ native boolean setAdapterPropertyNative(int type, byte[] val);
    /*package*/ native boolean getAdapterPropertiesNative();
    /*package*/ native boolean getAdapterPropertyNative(int type);
    /*package*/ native boolean setAdapterPropertyNative(int type);
    /*package*/ native boolean
        setDevicePropertyNative(byte[] address, int type, byte[] val);
    /*package*/ native boolean getDevicePropertyNative(byte[] address, int type);

    /*package*/ native boolean createBondNative(byte[] address, int transport);
    /*package*/ native boolean removeBondNative(byte[] address);
    /*package*/ native boolean cancelBondNative(byte[] address);

    /*package*/ native boolean isConnectedNative(byte[] address);

    private native boolean startDiscoveryNative();
    private native boolean cancelDiscoveryNative();

    private native boolean pinReplyNative(byte[] address, boolean accept, int len, byte[] pin);
    private native boolean sspReplyNative(byte[] address, int type, boolean
            accept, int passkey);

    /*package*/ native boolean getRemoteServicesNative(byte[] address);
    /*package*/ native boolean getRemoteMasInstancesNative(byte[] address);

    private native int readEnergyInfo();
    // TODO(BT) move this to ../btsock dir
    private native int connectSocketNative(byte[] address, int type,
                                           byte[] uuid, int port, int flag);
    private native int createSocketChannelNative(int type, String serviceName,
                                                 byte[] uuid, int port, int flag);

    private native int setSocketOptNative(int fd, int type, int optionName,
                                byte [] optionVal, int optionLen);

    private native int  getSocketOptNative(int fd, int type, int optionName,
                                byte [] optionVal);

    /*package*/ native boolean configHciSnoopLogNative(boolean enable);

    private native void alarmFiredNative();

    protected void finalize() {
        cleanup();
        if (TRACE_REF) {
            synchronized (AdapterService.class) {
                sRefCount--;
                debugLog("finalize() - REFCOUNT: FINALIZED. INSTANCE_COUNT= " + sRefCount);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private synchronized void checkA2dpState() {
        final Class a2dp_src[]  =  { A2dpService.class };
        final Class a2dp_sink[] = { A2dpSinkService.class };

        boolean isA2dpSinkEnabled = SystemProperties.getBoolean("persist.service.bt.a2dp.sink", false);
        Log.d(TAG, "checkA2dpState: isA2dpSinkEnabled = " + isA2dpSinkEnabled);

        if (isA2dpSinkEnabled) {
            mDisabledProfiles.add(A2dpService.class.getName());
            mDisabledProfiles.remove(A2dpSinkService.class.getName());
        } else {
            mDisabledProfiles.remove(A2dpService.class.getName());
            mDisabledProfiles.add(A2dpSinkService.class.getName());
        }

        if (mAdapterStateMachine.isTurningOn() || mAdapterStateMachine.isTurningOff()) {
            Log.e(TAG, "checkA2dpState: returning");
            return;
        }

        if (isA2dpSinkEnabled) {
            setProfileServiceState(a2dp_src, BluetoothAdapter.STATE_OFF);
            setProfileServiceState(a2dp_sink, BluetoothAdapter.STATE_ON);
        } else {
            setProfileServiceState(a2dp_sink, BluetoothAdapter.STATE_OFF);
            setProfileServiceState(a2dp_src, BluetoothAdapter.STATE_ON);
        }
    }

    @SuppressWarnings("rawtypes")
    private synchronized void checkHidState() {
        final Class hh[] = { HidService.class };
        final Class hd[] = { HidDevService.class };

        boolean isHidDev = SystemProperties.getBoolean("persist.service.bdroid.hid.dev", false);
        Log.d(TAG, "checkHidState: isHidDev = " + isHidDev);

        if (isHidDev) {
            mDisabledProfiles.add(HidService.class.getName());
            mDisabledProfiles.remove(HidDevService.class.getName());
        } else {
            mDisabledProfiles.remove(HidService.class.getName());
            mDisabledProfiles.add(HidDevService.class.getName());
        }

        if (mAdapterStateMachine.isTurningOn() || mAdapterStateMachine.isTurningOff()) {
            Log.e(TAG, "checkHidState: returning");
            return;
        }

        if (isHidDev) {
            setProfileServiceState(hh, BluetoothAdapter.STATE_OFF);
            setProfileServiceState(hd, BluetoothAdapter.STATE_ON);
        } else {
            setProfileServiceState(hd, BluetoothAdapter.STATE_OFF);
            setProfileServiceState(hh, BluetoothAdapter.STATE_ON);
        }
    }

    @SuppressWarnings("rawtypes")
    private synchronized void checkHfpState() {
        final Class hfp_ag[] = { HeadsetService.class };
        final Class hfp_hs[] = { HeadsetClientService.class };

        boolean isHfpClientEnabled = SystemProperties.getBoolean("persist.service.bt.hfp.client",
                false);
        Log.d(TAG, "checkHfpState: isHfpClientEnabled = " + isHfpClientEnabled);

        if (isHfpClientEnabled) {
            mDisabledProfiles.add(HeadsetService.class.getName());
            mDisabledProfiles.remove(HeadsetClientService.class.getName());
        } else {
            mDisabledProfiles.remove(HeadsetService.class.getName());
            mDisabledProfiles.add(HeadsetClientService.class.getName());
        }

        if (mAdapterStateMachine.isTurningOn() || mAdapterStateMachine.isTurningOff()) {
            Log.e(TAG, "checkHfpState: returning");
            return;
        }

        if (isHfpClientEnabled) {
            setProfileServiceState(hfp_ag, BluetoothAdapter.STATE_OFF);
            setProfileServiceState(hfp_hs, BluetoothAdapter.STATE_ON);
        } else {
            setProfileServiceState(hfp_hs, BluetoothAdapter.STATE_OFF);
            setProfileServiceState(hfp_ag, BluetoothAdapter.STATE_ON);
        }
    }
}
