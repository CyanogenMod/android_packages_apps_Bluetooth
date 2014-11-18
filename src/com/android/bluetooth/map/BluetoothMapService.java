/*
* Copyright (C) 2013 Samsung System LSI
* Copyright (C) 2013, The Linux Foundation. All rights reserved.
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

package com.android.bluetooth.map;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashMap;

import android.app.AlarmManager;
import javax.btobex.ServerSession;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.IBluetoothMap;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.BluetoothMap;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.ParcelUuid;
import android.text.TextUtils;
import android.util.Log;
import android.provider.Settings;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.net.Uri;
import android.content.ContentResolver;
import android.database.ContentObserver;
import com.android.bluetooth.map.BluetoothMapUtils.*;
import com.android.bluetooth.map.BluetoothMapUtils.TYPE;
import android.database.sqlite.SQLiteException;


import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;


public class BluetoothMapService extends ProfileService {
    private static final String TAG = "BluetoothMapService";
    public static final String LOG_TAG = "BluetoothMap";

    /**
     * To enable MAP DEBUG/VERBOSE logging - run below cmd in adb shell, and
     * restart com.android.bluetooth process. only enable DEBUG log:
     * "setprop log.tag.BluetoothMapService DEBUG"; enable both VERBOSE and
     * DEBUG log: "setprop log.tag.BluetoothMapService VERBOSE"
     */

    public static final boolean DEBUG = true;

    public static boolean VERBOSE;

    /**
     * Intent indicating incoming obex authentication request which is from
     * PCE(Carkit)
     */
    public static final String AUTH_CHALL_ACTION = "com.android.bluetooth.map.authchall";

    /**
     * Intent indicating timeout for user confirmation, which is sent to
     * BluetoothMapActivity
     */
    public static final String USER_CONFIRM_TIMEOUT_ACTION =
            "com.android.bluetooth.map.userconfirmtimeout";
    private static final int USER_CONFIRM_TIMEOUT_VALUE = 30000;

    /**
     * Intent Extra name indicating session key which is sent from
     * BluetoothMapActivity
     */
    public static final String EXTRA_SESSION_KEY = "com.android.bluetooth.map.sessionkey";

    public static final String THIS_PACKAGE_NAME = "com.android.bluetooth";

    public static final int MSG_SERVERSESSION_CLOSE = 5000;

    public static final int MSG_SESSION_ESTABLISHED = 5001;

    public static final int MSG_SESSION_DISCONNECTED = 5002;

    public static final int MSG_OBEX_AUTH_CHALL = 5003;

    public static final int MSG_ACQUIRE_WAKE_LOCK = 5004;

    public static final int MSG_RELEASE_WAKE_LOCK = 5005;

    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;

    private static final int START_LISTENER = 1;

    private static final int USER_TIMEOUT = 2;

    private static final int DISCONNECT_MAP = 3;

    private static final int MSG_INTERNAL_REGISTER_EMAIL = 4;

    private static final int RELEASE_WAKE_LOCK_DELAY = 10000;

    private PowerManager.WakeLock mWakeLock = null;

    private BluetoothAdapter mAdapter;

    private BluetoothMapAuthenticator mAuth = null;

    private ServerSession mServerSession = null;

    private BluetoothMnsObexClient mBluetoothMnsObexClient = null;

    private BluetoothSocket mConnSocket = null;

    private static BluetoothDevice mRemoteDevice = null;

    private static String sRemoteDeviceName = null;

    private volatile boolean mInterrupted;

    private boolean mIsWaitingAuthorization = false;
    private boolean mRemoveTimeoutMsg = false;
    private int mPermission = BluetoothDevice.ACCESS_UNKNOWN;
    private boolean mAccountChanged = false;
    private int mState;

    private boolean isWaitingAuthorization = false;
    private boolean removeTimeoutMsg = false;

    // package and class name to which we send intent to check message access access permission
    private static final String ACCESS_AUTHORITY_PACKAGE = "com.android.settings";
    private static final String ACCESS_AUTHORITY_CLASS =
        "com.android.settings.bluetooth.BluetoothPermissionRequest";

    private static final ParcelUuid[] MAP_UUIDS = {
        BluetoothUuid.MAP,
        BluetoothUuid.MNS,
    };

    public static final int MESSAGE_TYPE_EMAIL = 1 << 0;
    public static final int MESSAGE_TYPE_SMS_GSM = 1 << 1;
    public static final int MESSAGE_TYPE_SMS_CDMA = 1 << 2;
    public static final int MESSAGE_TYPE_MMS = 1 << 3;
    public static final int MESSAGE_TYPE_SMS = MESSAGE_TYPE_SMS_GSM | MESSAGE_TYPE_SMS_CDMA;
    public static final int MESSAGE_TYPE_SMS_MMS = MESSAGE_TYPE_SMS | MESSAGE_TYPE_MMS;

    private boolean mIsEmailEnabled = true;
    public static final int MAX_INSTANCES = 2;
    BluetoothMapObexConnectionManager mConnectionManager = null;
    public static final int MAS_INS_INFO[] = {MESSAGE_TYPE_SMS_MMS, MESSAGE_TYPE_EMAIL};
    private ContentObserver mEmailAccountObserver;
    public static final String AUTHORITY = "com.android.email.provider";
    public static final Uri EMAIL_URI = Uri.parse("content://" + AUTHORITY);
    public static final Uri EMAIL_ACCOUNT_URI = Uri.withAppendedPath(EMAIL_URI, "account");
    public static boolean isMapEmailRequestON = false;

    public BluetoothMapService() {
        mState = BluetoothMap.STATE_DISCONNECTED;
        mConnectionManager = new BluetoothMapObexConnectionManager();
        if (VERBOSE)
           Log.v(TAG, "BluetoothMapService: mIsEmailEnabled: " + mIsEmailEnabled);
        mEmailAccountObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                long isEmailConfigured = -1;
                Context context = mConnectionManager.getEmailContext();
                //Handle only email configuration changes
                if (context != null )
                    isEmailConfigured = BluetoothMapUtils.getEmailAccountId(context) ;
                boolean isMapEmailStarted = mConnectionManager.isMapEmailON();

                if (VERBOSE) {
                     Log.v(TAG,"onChange mEmailAccountObserver Configured Account: "+ isEmailConfigured
                     + "IsMapEmailRequstInprogress " + isMapEmailRequestON + "isMAP AlreadyStarted "+ isMapEmailStarted);
                }
                if( mSessionStatusHandler.hasMessages(MSG_INTERNAL_REGISTER_EMAIL)) {
                        // Remove any pending requests in queue
                        mSessionStatusHandler.removeMessages(MSG_INTERNAL_REGISTER_EMAIL);
                }
                // Donot send request if Inprogress or already ON
                if( isEmailConfigured != -1 && !isMapEmailRequestON && !isMapEmailStarted) {
                    if ( mAdapter != null) {
                        int state = mAdapter.getState();
                        if (state == BluetoothAdapter.STATE_ON ) {
                            mSessionStatusHandler.sendEmptyMessage(MSG_INTERNAL_REGISTER_EMAIL);
                        } else if (VERBOSE) {
                                Log.v(TAG, "BT is not ON, no start");
                        }
                    }
                } else if(isEmailConfigured == -1) {
                    mConnectionManager.closeMapEmail();
                }
            }
        };
    }
    private final void closeService() {
       if (VERBOSE) Log.v(TAG, "closeService");
       mConnectionManager.closeAll();
    }

    private final Handler mSessionStatusHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (DEBUG) Log.v(TAG, "Handler(): got msg=" + msg.what);

            switch (msg.what) {
                case START_LISTENER:
                    if (mAdapter.isEnabled()) {
                        mConnectionManager.startAll();
                    }
                    break;

                case MSG_INTERNAL_REGISTER_EMAIL:
                    Log.d(TAG,"received MSG_INTERNAL_REGISTER_EMAIL");
                    if ((mAdapter != null) && mAdapter.isEnabled()) {
                        mConnectionManager.startMapEmail();
                    }
                    break;

                case USER_TIMEOUT:
                    Intent intent = new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_CANCEL);
                    intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
                    intent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                                    BluetoothDevice.REQUEST_TYPE_MESSAGE_ACCESS);
                    sendBroadcast(intent, BLUETOOTH_PERM);
                    isWaitingAuthorization = false;
                    removeTimeoutMsg = false;
                    mConnectionManager.stopObexServerSessionWaiting();
                    break;
                case MSG_SERVERSESSION_CLOSE:
                    final int masId = msg.arg1;
                    mConnectionManager.stopObexServerSession(masId);
                    break;
                case MSG_SESSION_ESTABLISHED:
                    break;
                case MSG_SESSION_DISCONNECTED:
                    // handled elsewhere
                    break;
                case DISCONNECT_MAP:
                    disconnectMap((BluetoothDevice)msg.obj);
                    break;
                case MSG_ACQUIRE_WAKE_LOCK:
                    if (mWakeLock == null) {
                        PowerManager pm = (PowerManager)getSystemService(
                                          Context.POWER_SERVICE);
                        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                    "StartingObexMapTransaction");
                        mWakeLock.setReferenceCounted(false);
                        mWakeLock.acquire();
                        Log.w(TAG, "Acquire Wake Lock");
                    }
                    mSessionStatusHandler.removeMessages(MSG_RELEASE_WAKE_LOCK);
                    mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
                      .obtainMessage(MSG_RELEASE_WAKE_LOCK), RELEASE_WAKE_LOCK_DELAY);
                    break;
                case MSG_RELEASE_WAKE_LOCK:
                    if (mWakeLock != null) {
                        mWakeLock.release();
                        mWakeLock = null;
                        Log.w(TAG, "Release Wake Lock");
                    }
                    break;
                default:
                    break;
            }
        }
    };


   public int getState() {
        return mState;
    }

    public static BluetoothDevice getRemoteDevice() {
        return mRemoteDevice;
    }
    private void setState(int state) {
        setState(state, BluetoothMap.RESULT_SUCCESS);
    }

    private synchronized void setState(int state, int result) {
        if (state != mState) {
            if (DEBUG) Log.d(TAG, "Map state " + mState + " -> " + state + ", result = "
                    + result);
            int prevState = mState;
            mState = state;
            Intent intent = new Intent(BluetoothMap.ACTION_CONNECTION_STATE_CHANGED);
            intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
            intent.putExtra(BluetoothProfile.EXTRA_STATE, mState);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
            sendBroadcast(intent, BLUETOOTH_PERM);
            AdapterService s = AdapterService.getAdapterService();
            if (s != null) {
                s.onProfileConnectionStateChanged(mRemoteDevice, BluetoothProfile.MAP,
                        mState, prevState);
            }
        }
    }

    public static String getRemoteDeviceName() {
        return sRemoteDeviceName;
    }

    public boolean disconnect(BluetoothDevice device) {
        mSessionStatusHandler.sendMessage(mSessionStatusHandler.obtainMessage(DISCONNECT_MAP, 0, 0, device));
        return true;
    }

    public boolean disconnectMap(BluetoothDevice device) {
        boolean result = false;
        if (DEBUG) Log.d(TAG, "disconnectMap");
        if (getRemoteDevice().equals(device)) {
            switch (mState) {
                case BluetoothMap.STATE_CONNECTED:
                    //do no call close service else map service will close
                    //closeService();
                    mConnectionManager.stopObexServerSessionAll();
                    setState(BluetoothMap.STATE_DISCONNECTED, BluetoothMap.RESULT_CANCELED);
                    result = true;
                    break;
                default:
                    break;
                }
        }
        return result;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        synchronized(this) {
            if (mState == BluetoothMap.STATE_CONNECTED && mRemoteDevice != null) {
                devices.add(mRemoteDevice);
            }
        }
        return devices;
    }

    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>();
        Set<BluetoothDevice> bondedDevices = mAdapter.getBondedDevices();
        int connectionState;
        synchronized (this) {
            if (bondedDevices != null) {
            for (BluetoothDevice device : bondedDevices) {
                ParcelUuid[] featureUuids = device.getUuids();
                if (!BluetoothUuid.containsAnyUuid(featureUuids, MAP_UUIDS)) {
                    continue;
                }
                connectionState = getConnectionState(device);
                for(int i = 0; i < states.length; i++) {
                    if (connectionState == states[i]) {
                        deviceList.add(device);
                    }
                }
            }
        }
        }
        return deviceList;
    }

    public int getConnectionState(BluetoothDevice device) {
        synchronized(this) {
            if (getState() == BluetoothMap.STATE_CONNECTED && getRemoteDevice().equals(device)) {
                return BluetoothProfile.STATE_CONNECTED;
            } else {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
        }
    }

    public boolean setPriority(BluetoothDevice device, int priority) {
        Settings.Global.putInt(getContentResolver(),
            Settings.Global.getBluetoothMapPriorityKey(device.getAddress()),
            priority);
        if (DEBUG) Log.d(TAG, "Saved priority " + device + " = " + priority);
        return true;
    }

    public int getPriority(BluetoothDevice device) {
        int priority = Settings.Global.getInt(getContentResolver(),
            Settings.Global.getBluetoothMapPriorityKey(device.getAddress()),
            BluetoothProfile.PRIORITY_UNDEFINED);
        return priority;
    }

    @Override
    protected IProfileServiceBinder initBinder() {
    Log.d(TAG, "Inside initBinder");
        return new BluetoothMapBinder(this);
    }

    @Override
    protected boolean start() {
        if (DEBUG) Log.d(TAG, "start()");
        VERBOSE = Log.isLoggable(LOG_TAG, Log.VERBOSE) ? true : false;
        if (VERBOSE) Log.v(TAG, "verbose logging is enabled");
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            registerReceiver(mMapReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG,"Unable to register map receiver",e);
        }
        mConnectionManager.init();
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        // start RFCOMM listener
        if(mAdapter ==null) {
          Log.w(TAG,"Local BT device is not enabled");
        } else {
        mSessionStatusHandler.sendMessage(mSessionStatusHandler
                .obtainMessage(START_LISTENER));
        }
        // Register EmailAccountObserver for dynamic SDP update
        try {
            if (DEBUG) Log.d(TAG,"Registering observer");
            getContentResolver().registerContentObserver(
               EMAIL_ACCOUNT_URI, false, mEmailAccountObserver);
        } catch (SQLiteException e) {
            Log.e(TAG, "SQLite exception: " + e);
        }
        return true;
    }

    @Override
    protected boolean stop() {
        if (DEBUG) Log.d(TAG, "stop()");
        try {
            unregisterReceiver(mMapReceiver);
        } catch (Exception e) {
            Log.w(TAG,"Unable to unregister map receiver",e);
        }

        setState(BluetoothMap.STATE_DISCONNECTED, BluetoothMap.RESULT_CANCELED);
        closeService();
        try {
            if (DEBUG) Log.d(TAG,"Unregistering Email account observer");
            getContentResolver().unregisterContentObserver(mEmailAccountObserver);
        } catch (SQLiteException e) {
            Log.e(TAG, "SQLite exception: " + e);
        }
        return true;
    }

    public boolean cleanup()  {
        if (DEBUG) Log.d(TAG, "cleanup()");
        setState(BluetoothMap.STATE_DISCONNECTED, BluetoothMap.RESULT_CANCELED);
        closeService();
        return true;
    }

    class BluetoothMapObexConnectionManager {
        private ArrayList<BluetoothMapObexConnection> mConnections =
                new ArrayList<BluetoothMapObexConnection>();
                private HashMap<Integer, String> MapClientList = new HashMap<Integer, String>();

        public BluetoothMapObexConnectionManager() {
            int numberOfSupportedInstances = MAX_INSTANCES;
            if(VERBOSE)
                Log.v(TAG, "BluetoothMapObexConnectionManager: mIsEmailEnabled: " + mIsEmailEnabled);
            if(!mIsEmailEnabled) {
                numberOfSupportedInstances = 1; /*Email instance not supported*/
            }
            for (int i = 0; i < numberOfSupportedInstances; i ++) {
                mConnections.add(new BluetoothMapObexConnection(
                        MAS_INS_INFO[i], i ));
            MapClientList.put(i, null);
            }
        }

        public void initiateObexServerSession(BluetoothDevice device) {
            try {Log.d(TAG, "inside initiateObexServerSession");
                for (BluetoothMapObexConnection connection : mConnections) {
                    if (connection.mConnSocket != null && connection.mWaitingForConfirmation) {
                        connection.mWaitingForConfirmation = false;
                        Log.d(TAG, "calling startobexServerSession for masid  "+connection.mMasId);
                        connection.startObexServerSession(device);
                    }
                }
            } catch (IOException ex) {
                Log.e(TAG, "Caught the error: " + ex.toString());
            }
        }

        public void setWaitingForConfirmation(int masId) {
            Log.d(TAG, "Inside setWaitingForConfirmation");
            if (masId < mConnections.size()) {
                final BluetoothMapObexConnection connect = mConnections.get(masId);
                connect.mWaitingForConfirmation = true;
            } else {
                Log.e(TAG, "Attempt to set waiting for user confirmation for MAS id: " + masId);
                Log.e(TAG, "out of index");
            }
        }

        public void stopObexServerSession(int masId) {
            int serverSessionConnected = 0;
            for (BluetoothMapObexConnection connection : mConnections) {
                if (connection.mConnSocket != null) {
                    serverSessionConnected++;
                }
            }
            // stop mnsclient session if only one session was connected.
            if(serverSessionConnected == 1) {
               if (mBluetoothMnsObexClient != null) {
                   mBluetoothMnsObexClient.shutdown();
                   mBluetoothMnsObexClient = null;
               }
            }

         if (masId < mConnections.size()) {
                final BluetoothMapObexConnection connect = mConnections.get(masId);
                if (connect.mConnSocket != null) {
                    connect.stopObexServerSession();
                } else {
                    Log.w(TAG, "Attempt to stop OBEX Server session for MAS id: " + masId);
                    Log.w(TAG, "when there is no connected socket");
                }
            } else {
                Log.e(TAG, "Attempt to stop OBEX Server session for MAS id: " + masId);
                Log.e(TAG, "out of index");
            }

        }

        public void stopObexServerSessionWaiting() {

            int serverSessionConnected = 0;
            for (BluetoothMapObexConnection connection : mConnections) {
                 if (connection.mConnSocket != null) {
                     serverSessionConnected++;
                 }
            }
            // stop mnsclient session if only one session was connected.
            if(serverSessionConnected == 1) {
               if (mBluetoothMnsObexClient != null) {
                    mBluetoothMnsObexClient.shutdown();
                    mBluetoothMnsObexClient = null;
               }
            }

        for (BluetoothMapObexConnection connection : mConnections) {
               if (connection.mConnSocket != null && connection.mWaitingForConfirmation) {
                  connection.mWaitingForConfirmation = false;
                  connection.stopObexServerSession();
                }
            }
       }

       public void stopObexServerSessionAll() {
           if (mBluetoothMnsObexClient != null) {
                   mBluetoothMnsObexClient.shutdown();
                   mBluetoothMnsObexClient = null;
           }
           for (BluetoothMapObexConnection connection : mConnections) {
               if (connection.mConnSocket != null) {
                   connection.stopObexServerSession();
               }
           }
        }

        public void closeAll() {
           if (mBluetoothMnsObexClient != null) {
               mBluetoothMnsObexClient.shutdown();
               mBluetoothMnsObexClient = null;
           }

           for (BluetoothMapObexConnection connection : mConnections) {
                connection.mInterrupted = true;
                connection.closeConnection();
           }
        }

        public void startAll() {
            for (BluetoothMapObexConnection connection : mConnections) {
                connection.startRfcommSocketListener();
            }
        }
        public boolean isMapEmailON () {
                final BluetoothMapObexConnection connect = mConnections.get(1);
                if(connect != null && connect.mMasId == 1 && connect.mServerSocket != null
                    && connect.mAcceptThread != null )
                    return true;
                return false;
        }
        public Context getEmailContext () {
            final BluetoothMapObexConnection connect = mConnections.get(1);
            return connect.context;
        }
        public void startMapEmail() {
            final BluetoothMapObexConnection connect = mConnections.get(1);
            if (connect != null) {
                // SET Email Inprogress ON
                isMapEmailRequestON = true;
                // Start Listener and add email support in SDP
                connect.startRfcommSocketListener();
            }
            // SET Email Inprogress OFF
            isMapEmailRequestON = false;
        }

        public void closeMapEmail() {
            final BluetoothMapObexConnection connect = mConnections.get(1);
            if (connect != null) {
                // Handle common flag for MAP instances
                boolean isWaitingAuth = isWaitingAuthorization;
                // Stop Listener and remove email support in SDP
                connect.closeConnection();
                isWaitingAuthorization = isWaitingAuth;
            }
        }

        public void init() {
            for (BluetoothMapObexConnection connection: mConnections) {
                connection.mInterrupted = false;
            }
        }

        public void addToMapClientList(String remoteAddr, int masId) {
                        Log.d(TAG,"Adding to mapClient List masid "+masId+" bdaddr "+remoteAddr);
                        MapClientList.put(masId, remoteAddr);
        }

        public void removeFromMapClientList(int masId) {
                        Log.d(TAG,"Removing from the list, masid "+masId);
                        MapClientList.put(masId, null);
        }

        public boolean isAllowedConnection(BluetoothDevice remoteDevice, int masId) {
            String remoteAddress = remoteDevice.getAddress();
            if (remoteAddress == null) {
                if (VERBOSE) Log.v(TAG, "Connection request from unknown device");
                return false;
            }
            if(MapClientList.get(masId)==null) {
               if(MapClientList.get((masId^1)) == null) {
                  if (VERBOSE) Log.v(TAG, "Allow Connection request from " +remoteAddress
                                     + "when no other device is connected");
                   return true;
               } else {
                   if(MapClientList.get((masId^1)).equalsIgnoreCase(remoteAddress)) {
                         Log.d(TAG, "Allow Connection request from " +remoteAddress);
                         Log.d(TAG, "when mas" +(masId^1) +"is connected to " +MapClientList.get((masId^1)));
                                return true;
                   } else {
                         Log.d(TAG, "Dont Allow Connection request from " +remoteAddress
                               + "when mas" +(masId^1) +"is connected to" +MapClientList.get((masId^1)));
                         return false;
                   }
               }
            }
            Log.d(TAG,"connection not allowed from " + remoteAddress);
            return false;
        }
    }

    private class BluetoothMapObexConnection {
        private volatile boolean mInterrupted;
        private BluetoothMapObexServer mMapServer = null;
        private BluetoothServerSocket mServerSocket = null;
        private SocketAcceptThread mAcceptThread = null;
        private BluetoothSocket mConnSocket = null;
        private ServerSession mServerSession = null;
        private int mSupportedMessageTypes;
        private int mMasId;
        private Context context;
        boolean mWaitingForConfirmation = false;

        public BluetoothMapObexConnection(int supportedMessageTypes, int masId) {
            Log.d(TAG, "inside BluetoothMapObexConnection");
            Log.d(TAG, "supportedMessageTypes "+supportedMessageTypes);
            Log.d(TAG, "masId "+masId);
            mSupportedMessageTypes = supportedMessageTypes;
            mMasId = masId;
        }

        private void startRfcommSocketListener() {
            if (VERBOSE){
                Log.v(TAG, "Map Service startRfcommSocketListener");
                Log.v(TAG, "mMasId is "+mMasId);
            }

            context = getApplicationContext();
            // Promote Email Instance only if primary email account configured
            if(mMasId == 1) {
               if (BluetoothMapUtils.getEmailAccountId(context) == -1) {
                   if (VERBOSE) Log.v(TAG, "account is not configured");
                   return;
               }
            }

            if (mServerSocket == null) {
                if (!initSocket()) {
                    closeConnection();
                    return;
                }
            }
            if (mAcceptThread == null) {
                mAcceptThread = new SocketAcceptThread(mMasId);
                mAcceptThread.setName("BluetoothMapAcceptThread " + mMasId);
                mAcceptThread.start();
            }
        }

        private final boolean initSocket() {
            if (VERBOSE) {
                Log.v(TAG, "Map Service initSocket");
                Log.v(TAG, "mMasId is "+mMasId);
            }

            boolean initSocketOK = false;
            final int CREATE_RETRY_TIME = 10;
            mInterrupted = false;

            // It's possible that create will fail in some cases. retry for 10 times
            for (int i = 0; i < CREATE_RETRY_TIME && !mInterrupted; i++) {
                try {
                    if(mSupportedMessageTypes == MESSAGE_TYPE_EMAIL)
                       mServerSocket  = mAdapter.listenUsingRfcommWithServiceRecord("Email Message Access",BluetoothUuid.MAS.getUuid());
                    else
                       mServerSocket  = mAdapter.listenUsingRfcommWithServiceRecord("SMS/MMS Message Access", BluetoothUuid.MAS.getUuid());
                    initSocketOK = true;
                } catch (IOException e) {
                    Log.e(TAG, "Error create RfcommServerSocket " + e.toString());
                    initSocketOK = false;
                }

                if (!initSocketOK) {
                    // Need to break out of this loop if BT is being turned off.
                    if (mAdapter == null) {
                        break;
                    }
                    int state = mAdapter.getState();
                    if ((state != BluetoothAdapter.STATE_TURNING_ON) && (state != BluetoothAdapter.STATE_ON)) {
                         Log.w(TAG, "initRfcommSocket failed as BT is (being) turned off");
                         break;
                     }

                    synchronized (this) {
                        try {
                            if (VERBOSE) Log.v(TAG, "wait 3 seconds");
                            Thread.sleep(300);
                        } catch (InterruptedException e) {
                            Log.e(TAG, "socketAcceptThread thread was interrupted (3)");
                            mInterrupted = true;
                        }
                    }
                } else {
                    break;
                }
            }

            if (initSocketOK) {
                if (VERBOSE)
                    Log.v(TAG, "Succeed to create listening socket for mMasId "
                            + mMasId);
            } else {
                Log.e(TAG, "Error to create listening socket after "
                        + CREATE_RETRY_TIME + " try");
            }
            return initSocketOK;
        }

        private final synchronized void closeServerSocket() {
            // exit SocketAcceptThread early
            if (VERBOSE) {
                Log.v(TAG, "Close Server Socket : " );
            }
            if (mServerSocket != null) {
                try {
                   // this will cause mServerSocket.accept() return early with IOException
                   mServerSocket.close();
                   mServerSocket = null;
                } catch (IOException ex) {
                   Log.e(TAG, "Close Server Socket error: " + ex);
                }
            }
        }
        private final synchronized void closeConnectionSocket() {
            if (mConnSocket != null) {
                try {
                    mConnSocket.close();
                    mConnSocket = null;
                } catch (IOException e) {
                    Log.e(TAG, "Close Connection Socket error: " + e.toString());
                }
            }
        }

        private final void closeConnection() {
            if (DEBUG) Log.d(TAG, "MAP Service closeService in");
            // exit initSocket early
            mInterrupted = true;
            closeServerSocket();
            if (mAcceptThread != null) {
                try {
                    mAcceptThread.shutdown();
                    mAcceptThread.join();
                    mAcceptThread = null;
                } catch (InterruptedException ex) {
                     Log.w(TAG, "mAcceptThread close error" + ex);
                }
            }
            if (mServerSession != null) {
                    mServerSession.close();
                    mServerSession = null;
            }
           closeConnectionSocket();
            if (mSessionStatusHandler != null) {
                mSessionStatusHandler.removeCallbacksAndMessages(null);
            }
            isWaitingAuthorization = false;
            if (VERBOSE) Log.v(TAG, "MAP Service closeService out");
        }

        private final void startObexServerSession(BluetoothDevice device) throws IOException {
            if (DEBUG) {
                  Log.d(TAG, "Map Service startObexServerSession");
                  Log.d(TAG, "mMasId is "+mMasId);
            }
            if(VERBOSE) Log.d(TAG, "after getting application context");
            if(mBluetoothMnsObexClient == null)
                mBluetoothMnsObexClient = new BluetoothMnsObexClient(context, mRemoteDevice);
            mBluetoothMnsObexClient.initObserver(mSessionStatusHandler, mMasId);
            mMapServer = new BluetoothMapObexServer(mSessionStatusHandler, context,
            mBluetoothMnsObexClient, mMasId);
            synchronized (this) {
               // We need to get authentication now that obex server is up
               mAuth = new BluetoothMapAuthenticator(mSessionStatusHandler);
               mAuth.setChallenged(false);
               mAuth.setCancelled(false);
            }
            // setup RFCOMM transport
            BluetoothMapRfcommTransport transport = new BluetoothMapRfcommTransport(mConnSocket);
            mServerSession = new ServerSession(transport, mMapServer, mAuth);
            setState(BluetoothMap.STATE_CONNECTED);
            if (DEBUG) {
                Log.d(TAG, "startObexServerSession() success!");
                Log.d(TAG, "mMasId is "+mMasId);
            }
        }

        private void stopObexServerSession() {
            if (DEBUG) {
                Log.d(TAG, "Map Service stopObexServerSession ");
                Log.d(TAG, "mMasId is "+mMasId);
            }

            if (mAcceptThread != null) {
                try {
                    mAcceptThread.shutdown();
                    mAcceptThread.join();
                } catch (InterruptedException ex) {
                    Log.w(TAG, "mAcceptThread  close error" + ex);
                } finally {
                    mAcceptThread = null;
                }
            }

            if (mServerSession != null) {
                mServerSession.close();
                mServerSession = null;
            }

            if(mBluetoothMnsObexClient != null)
               mBluetoothMnsObexClient.deinitObserver(mMasId);
            mConnectionManager.removeFromMapClientList(mMasId);
            closeConnectionSocket();

            // Last obex transaction is finished, we start to listen for incoming
            // connection again
            if (mAdapter.isEnabled()) {
                startRfcommSocketListener();
            }
            setState(BluetoothMap.STATE_DISCONNECTED);
        }

        /**
         * A thread that runs in the background waiting for remote rfcomm
         * connect.Once a remote socket connected, this thread shall be
         * shutdown.When the remote disconnect,this thread shall run again waiting
         * for next request.
         */
        private class SocketAcceptThread extends Thread {
            private boolean stopped = false;
            private int mMasId;

            public SocketAcceptThread(int masId) {
             Log.d(TAG, "inside SocketAcceptThread");
                mMasId = masId;
            }
           @Override
           public void run() {
               BluetoothServerSocket serverSocket;
               if (mServerSocket == null) {
                  if (!initSocket()) {
                    return;
                  }
               }

               mConnectionManager.removeFromMapClientList(mMasId);
               while (!stopped) {
                   try {
                       if (DEBUG) Log.d(TAG, "Accepting socket connection...");
                       serverSocket = mServerSocket;
                        if(serverSocket == null) {
                           Log.w(TAG, "mServerSocket is null");
                           break;
                       }
                       mConnSocket = serverSocket.accept();
                       if (DEBUG) Log.d(TAG, "Accepted socket connection...");
                       synchronized (BluetoothMapService.this) {
                           if (mConnSocket == null) {
                               Log.w(TAG, "mConnSocket is null");
                               break;
                           }
                           mRemoteDevice = mConnSocket.getRemoteDevice();
                       }
                       if (mRemoteDevice == null) {
                          Log.i(TAG, "getRemoteDevice() = null");
                          break;
                       }

                       sRemoteDeviceName = mRemoteDevice.getName();
                      // In case getRemoteName failed and return null
                      if (TextUtils.isEmpty(sRemoteDeviceName)) {
                          sRemoteDeviceName = getString(R.string.defaultname);
                      }
                      if (!mConnectionManager.isAllowedConnection(mRemoteDevice,mMasId)) {
                          mConnSocket.close();
                          mConnSocket = null;
                          continue;
                      }

                      mConnectionManager.addToMapClientList(mRemoteDevice.getAddress(), mMasId);

                      mConnectionManager.setWaitingForConfirmation(mMasId);
                      isWaitingAuthorization = true;
                      Intent intent = new
                          Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_REQUEST);
                      intent.setClassName(ACCESS_AUTHORITY_PACKAGE, ACCESS_AUTHORITY_CLASS);
                      intent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                                      BluetoothDevice.REQUEST_TYPE_MESSAGE_ACCESS);
                      intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
                      sendBroadcast(intent, BLUETOOTH_ADMIN_PERM);

                      if (DEBUG) Log.d(TAG, "waiting for authorization for connection from: "
                              + sRemoteDeviceName);

                      //Queue USER_TIMEOUT to disconnect MAP OBEX session. If user doesn't
                      //accept or reject authorization request.
                      removeTimeoutMsg = true;
                      mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
                          .obtainMessage(USER_TIMEOUT), USER_CONFIRM_TIMEOUT_VALUE);
                      stopped = true; // job done ,close this thread;
                    } catch (IOException ex) {
                       stopped=true;
                       if (DEBUG) Log.v(TAG, "Accept exception: " + ex.toString());
                   }
               }
            }

            void shutdown() {
               stopped = true;
               interrupt();
            }
        }
    };

    private MapBroadcastReceiver mMapReceiver = new MapBroadcastReceiver();

    private class MapBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.d(TAG, "onReceive");
            String action = intent.getAction();
            if (DEBUG) Log.d(TAG, "onReceive, action "+action);
            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                                               BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_TURNING_OFF) {
                    if (DEBUG) Log.d(TAG, "STATE_TURNING_OFF removeTimeoutMsg:" + removeTimeoutMsg);
                    // Send any pending timeout now, as this service will be destroyed.
                    if (removeTimeoutMsg) {
                        mSessionStatusHandler.removeMessages(USER_TIMEOUT);

                        Intent timeoutIntent =
                                new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_CANCEL);
                        timeoutIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
                        timeoutIntent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                                               BluetoothDevice.REQUEST_TYPE_MESSAGE_ACCESS);
                        sendBroadcast(timeoutIntent, BLUETOOTH_PERM);
                        isWaitingAuthorization = false;
                        removeTimeoutMsg = false;
                    }

                    // Release all resources
                    closeService();
                } else if (state == BluetoothAdapter.STATE_ON) {
                    if (DEBUG) Log.d(TAG, "STATE_ON");
                    // start RFCOMM listener
                    mSessionStatusHandler.sendMessage(mSessionStatusHandler
                                  .obtainMessage(START_LISTENER));
                }
            } else if (action.equals(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY)) {
                int requestType = intent.getIntExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                                               BluetoothDevice.REQUEST_TYPE_MESSAGE_ACCESS);
                if (DEBUG) Log.d(TAG, "Received ACTION_CONNECTION_ACCESS_REPLY:" +
                           requestType + "isWaitingAuthorization:" + isWaitingAuthorization);
                if ((!isWaitingAuthorization) ||
                    (requestType != BluetoothDevice.REQUEST_TYPE_MESSAGE_ACCESS)) {
                    // this reply is not for us
                    return;
                }

                isWaitingAuthorization = false;
                if (removeTimeoutMsg) {
                    mSessionStatusHandler.removeMessages(USER_TIMEOUT);
                    removeTimeoutMsg = false;
                }

                if (intent.getIntExtra(BluetoothDevice.EXTRA_CONNECTION_ACCESS_RESULT,
                                       BluetoothDevice.CONNECTION_ACCESS_NO) ==
                    BluetoothDevice.CONNECTION_ACCESS_YES) {
                    //bluetooth connection accepted by user
                    if (intent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)) {
                        boolean result = mRemoteDevice.setMessageAccessPermission(
                                BluetoothDevice.ACCESS_ALLOWED);
                        if( DEBUG) Log.d(TAG, "setMessageAccessPermission(ACCESS_ALLOWED) result="
                           + result);
                    }

                    if(mIsEmailEnabled) {
                      //  todo updateEmailAccount();
                    }
                    if (DEBUG) Log.d(TAG, "calling initiateObexServerSession");
                    mConnectionManager.initiateObexServerSession(mRemoteDevice);

                } else {
                    if (intent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)) {
                        boolean result = mRemoteDevice.setMessageAccessPermission(
                               BluetoothDevice.ACCESS_REJECTED);
                        if(DEBUG) Log.d(TAG, "setMessageAccessPermission(ACCESS_REJECTED) result="
                            +result);
                    }
                   Log.d(TAG, "calling stopObexServerSessionWaiting");
                   mConnectionManager.stopObexServerSessionWaiting();
                }
            } else if (action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED) &&
                    isWaitingAuthorization) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                if (mRemoteDevice == null || device == null) {
                    Log.e(TAG, "Unexpected error!");
                    return;
                }

                if (DEBUG) Log.d(TAG,"ACL disconnected for "+ device);

                if (mRemoteDevice.equals(device) && removeTimeoutMsg) {
                    // Send any pending timeout now, as ACL got disconnected.
                    mSessionStatusHandler.removeMessages(USER_TIMEOUT);

                    Intent timeoutIntent =
                            new Intent(BluetoothDevice.ACTION_CONNECTION_ACCESS_CANCEL);
                    timeoutIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
                    timeoutIntent.putExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                                           BluetoothDevice.REQUEST_TYPE_MESSAGE_ACCESS);
                    sendBroadcast(timeoutIntent, BLUETOOTH_PERM);
                    isWaitingAuthorization = false;
                    removeTimeoutMsg = false;
                    mConnectionManager.stopObexServerSessionWaiting();
                }
            }
        }
    };

    //Binder object: Must be static class or memory leak may occur
    /**
     * This class implements the IBluetoothMap interface - or actually it validates the
     * preconditions for calling the actual functionality in the MapService, and calls it.
     */
    private static class BluetoothMapBinder extends IBluetoothMap.Stub
        implements IProfileServiceBinder {
        private BluetoothMapService mService;

        private BluetoothMapService getService() {
            if (!Utils.checkCaller()) {
                Log.w(TAG,"MAP call not allowed for non-active user");
                return null;
            }

            if (mService != null && mService.isAvailable()) {
                mService.enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
                return mService;
            }
            return null;
        }

        BluetoothMapBinder(BluetoothMapService service) {
            if (VERBOSE) Log.v(TAG, "BluetoothMapBinder()");
            mService = service;
        }

        public boolean cleanup()  {
            mService = null;
            return true;
        }

        public int getState() {
            if (VERBOSE) Log.v(TAG, "getState()");
            BluetoothMapService service = getService();
            if (service == null) return BluetoothMap.STATE_DISCONNECTED;
            return service.getState();
        }

        public BluetoothDevice getClient() {
            if (VERBOSE) Log.v(TAG, "getClient()");
            BluetoothMapService service = getService();
            if (service == null) return null;
            Log.v(TAG, "getClient() - returning " + service.getRemoteDevice());
            return service.getRemoteDevice();
        }

        public boolean isConnected(BluetoothDevice device) {
            if (VERBOSE) Log.v(TAG, "isConnected()");
            BluetoothMapService service = getService();
            if (service == null) return false;
            return service.getState() == BluetoothMap.STATE_CONNECTED && service.getRemoteDevice().equals(device);
        }

        public boolean connect(BluetoothDevice device) {
            if (VERBOSE) Log.v(TAG, "connect()");
            BluetoothMapService service = getService();
            if (service == null) return false;
            return false;
        }

        public boolean disconnect(BluetoothDevice device) {
            if (VERBOSE) Log.v(TAG, "disconnect()");
            BluetoothMapService service = getService();
            if (service == null) return false;
            return service.disconnect(device);
        }

        public List<BluetoothDevice> getConnectedDevices() {
            if (VERBOSE) Log.v(TAG, "getConnectedDevices()");
            BluetoothMapService service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>(0);
            return service.getConnectedDevices();
        }

        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            if (VERBOSE) Log.v(TAG, "getDevicesMatchingConnectionStates()");
            BluetoothMapService service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>(0);
            return service.getDevicesMatchingConnectionStates(states);
        }

        public int getConnectionState(BluetoothDevice device) {
            if (VERBOSE) Log.v(TAG, "getConnectionState()");
            BluetoothMapService service = getService();
            if (service == null) return BluetoothProfile.STATE_DISCONNECTED;
            return service.getConnectionState(device);
        }

        public boolean setPriority(BluetoothDevice device, int priority) {
            BluetoothMapService service = getService();
            if (service == null) return false;
            return service.setPriority(device, priority);
        }

        public int getPriority(BluetoothDevice device) {
            BluetoothMapService service = getService();
            if (service == null) return BluetoothProfile.PRIORITY_UNDEFINED;
            return service.getPriority(device);
        }
    };
}
