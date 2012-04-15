/*
 * Copyright (C) 2012 Google Inc.
 */

package com.android.bluetooth.hdp;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHealth;
import android.bluetooth.BluetoothHealthAppConfiguration;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothHealth;
import android.bluetooth.IBluetoothHealthCallback;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import com.android.bluetooth.Utils;
import android.content.pm.PackageManager;
import com.android.bluetooth.btservice.AdapterService;

/**
 * Provides Bluetooth Health Device profile, as a service in
 * the Bluetooth application.
 * @hide
 */
public class HealthService extends Service {
    private static final String TAG = "BluetoothHealthService";
    private static final boolean DBG = true;

    static final String BLUETOOTH_ADMIN_PERM =
        android.Manifest.permission.BLUETOOTH_ADMIN;
    static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private BluetoothAdapter mAdapter;
    private List<HealthChannel> mHealthChannels;
    private Map <BluetoothHealthAppConfiguration, AppInfo> mApps;
    private Map <BluetoothDevice, Integer> mHealthDevices;
    private HealthServiceMessageHandler mHandler;
    private static final int MESSAGE_REGISTER_APPLICATION = 1;
    private static final int MESSAGE_UNREGISTER_APPLICATION = 2;
    private static final int MESSAGE_CONNECT_CHANNEL = 3;
    private static final int MESSAGE_DISCONNECT_CHANNEL = 4;
    private static final int MESSAGE_APP_REGISTRATION_CALLBACK = 11;
    private static final int MESSAGE_CHANNEL_STATE_CALLBACK = 12;

    static {
        classInitNative();
    }

    @Override
    public void onCreate() {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @Override
    public IBinder onBind(Intent intent) {
        log("onBind");
        return mBinder;
    }

    @Override
    public void onStart(Intent intent, int startId) {
        log("onStart");
        if (mAdapter == null) {
            Log.w(TAG, "Stopping Bluetooth HealthService: device does not have BT");
            stop();
        }

        if (checkCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM)!=PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission denied!");
            return;
        }

        String action = intent.getStringExtra(AdapterService.EXTRA_ACTION);
        if (!AdapterService.ACTION_SERVICE_STATE_CHANGED.equals(action)) {
            Log.e(TAG, "Invalid action " + action);
            return;
        }

        int state= intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);        
        if(state==BluetoothAdapter.STATE_OFF) {
            stop();
        } else if (state== BluetoothAdapter.STATE_ON){
            start();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DBG) log("Destroying service.");
    }

    private void start() {
        if (DBG) log("startService");
        mHealthChannels = Collections.synchronizedList(new ArrayList<HealthChannel>());
        mApps = Collections.synchronizedMap(new HashMap<BluetoothHealthAppConfiguration,
                                            AppInfo>());
        mHealthDevices = Collections.synchronizedMap(new HashMap<BluetoothDevice, Integer>());

        HandlerThread thread = new HandlerThread("BluetoothHdpHandler");
        thread.start();
        Looper looper = thread.getLooper();
        mHandler = new HealthServiceMessageHandler(looper);
        initializeNative();

        //Notify adapter service
        AdapterService sAdapter = AdapterService.getAdapterService();
        if (sAdapter!= null) {
            sAdapter.onProfileServiceStateChanged(getClass().getName(), BluetoothAdapter.STATE_ON);
        }
    }

    private void stop() {
        if (DBG) log("stop()");

        //Cleanup looper
        Looper looper = mHandler.getLooper();
        if (looper != null) {
            looper.quit();
        }

        //Cleanup native
        cleanupNative();

        //Notify adapter service
        AdapterService sAdapter = AdapterService.getAdapterService();
        if (sAdapter!= null) {
            sAdapter.onProfileServiceStateChanged(getClass().getName(), BluetoothAdapter.STATE_OFF);
        }
        if (DBG) log("stop() done.");
        stopSelf();
    }

    private final class HealthServiceMessageHandler extends Handler {
        private HealthServiceMessageHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_REGISTER_APPLICATION:
                {
                    BluetoothHealthAppConfiguration appConfig =
                        (BluetoothHealthAppConfiguration) msg.obj;
                    int halRole = convertRoleToHal(appConfig.getRole());
                    int halChannelType = convertChannelTypeToHal(appConfig.getChannelType());
                    if (DBG) log("register datatype: " + appConfig.getDataType() + " role: " +
                                 halRole + " name: " + appConfig.getName() + " channeltype: " +
                                 halChannelType);
                    int appId = registerHealthAppNative(appConfig.getDataType(), halRole,
                                                        appConfig.getName(), halChannelType);

                    if (appId == -1) {
                        callStatusCallback(appConfig,
                                           BluetoothHealth.APP_CONFIG_REGISTRATION_FAILURE);
                        mApps.remove(appConfig);
                    } else {
                        (mApps.get(appConfig)).mAppId = appId;
                        callStatusCallback(appConfig,
                                           BluetoothHealth.APP_CONFIG_REGISTRATION_SUCCESS);
                    }
                }
                    break;
                case MESSAGE_UNREGISTER_APPLICATION:
                {
                    BluetoothHealthAppConfiguration appConfig =
                        (BluetoothHealthAppConfiguration) msg.obj;
                    int appId = (mApps.get(appConfig)).mAppId;
                    if (!unregisterHealthAppNative(appId)) {
                        Log.e(TAG, "Failed to unregister application: id: " + appId);
                        callStatusCallback(appConfig,
                                           BluetoothHealth.APP_CONFIG_UNREGISTRATION_FAILURE);
                    }
                }
                    break;
                case MESSAGE_CONNECT_CHANNEL:
                {
                    HealthChannel chan = (HealthChannel) msg.obj;
                    byte[] devAddr = getByteAddress(chan.mDevice);
                    int appId = (mApps.get(chan.mConfig)).mAppId;
                    chan.mChannelId = connectChannelNative(devAddr, appId);
                    if (chan.mChannelId == -1) {
                        callHealthChannelCallback(chan.mConfig, chan.mDevice,
                                                  BluetoothHealth.STATE_CHANNEL_DISCONNECTING,
                                                  BluetoothHealth.STATE_CHANNEL_DISCONNECTED,
                                                  chan.mChannelFd, chan.mChannelId);
                        callHealthChannelCallback(chan.mConfig, chan.mDevice,
                                                  BluetoothHealth.STATE_CHANNEL_DISCONNECTED,
                                                  BluetoothHealth.STATE_CHANNEL_DISCONNECTING,
                                                  chan.mChannelFd, chan.mChannelId);
                        mHealthChannels.remove(chan);
                    }
                }
                    break;
                case MESSAGE_DISCONNECT_CHANNEL:
                {
                    HealthChannel chan = (HealthChannel) msg.obj;
                    if (!disconnectChannelNative(chan.mChannelId)) {
                        callHealthChannelCallback(chan.mConfig, chan.mDevice,
                                                  BluetoothHealth.STATE_CHANNEL_DISCONNECTING,
                                                  BluetoothHealth.STATE_CHANNEL_CONNECTED,
                                                  chan.mChannelFd, chan.mChannelId);
                        callHealthChannelCallback(chan.mConfig, chan.mDevice,
                                                  BluetoothHealth.STATE_CHANNEL_CONNECTED,
                                                  BluetoothHealth.STATE_CHANNEL_DISCONNECTING,
                                                  chan.mChannelFd, chan.mChannelId);
                    }
                }
                    break;
                case MESSAGE_APP_REGISTRATION_CALLBACK:
                {
                    BluetoothHealthAppConfiguration appConfig = findAppConfigByAppId(msg.arg1);
                    if (appConfig == null) break;

                    int regStatus = convertHalRegStatus(msg.arg2);
                    callStatusCallback(appConfig, regStatus);
                    if (regStatus == BluetoothHealth.APP_CONFIG_REGISTRATION_FAILURE ||
                        regStatus == BluetoothHealth.APP_CONFIG_UNREGISTRATION_SUCCESS) {
                        mApps.remove(appConfig);
                    }
                }   
                    break;
                case MESSAGE_CHANNEL_STATE_CALLBACK:
                {
                    ChannelStateEvent channelStateEvent = (ChannelStateEvent) msg.obj;
                    HealthChannel chan = findChannelById(channelStateEvent.mChannelId);
                    int newState;
                    if (chan == null) {
                        // incoming connection
                        BluetoothHealthAppConfiguration appConfig =
                            findAppConfigByAppId(channelStateEvent.mAppId);
                        BluetoothDevice device = getDevice(channelStateEvent.mAddr);
                        chan = new HealthChannel(device, appConfig, appConfig.getChannelType());
                        chan.mChannelId = channelStateEvent.mChannelId;
                    }
                    newState = convertHalChannelState(channelStateEvent.mState);
                    if (newState == BluetoothHealth.STATE_CHANNEL_CONNECTED) {
                        try {
                            chan.mChannelFd = ParcelFileDescriptor.dup(channelStateEvent.mFd);
                        } catch (IOException e) {
                            Log.e(TAG, "failed to dup ParcelFileDescriptor");
                            break;
                        }
                    }
                    callHealthChannelCallback(chan.mConfig, chan.mDevice, newState,
                                              chan.mState, chan.mChannelFd, chan.mChannelId);
                    chan.mState = newState;
                    if (channelStateEvent.mState == CONN_STATE_DESTROYED) {
                        mHealthChannels.remove(chan);
                    }
                }
                    break;
            }
        }
    }

    /**
     * Handlers for incoming service calls
     */
    private final IBluetoothHealth.Stub mBinder = new IBluetoothHealth.Stub() {
        public boolean registerAppConfiguration(BluetoothHealthAppConfiguration config,
                                                IBluetoothHealthCallback callback) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM,
                                           "Need BLUETOOTH permission");
            if (mApps.get(config) != null) {
                if (DBG) log("Config has already been registered");
                return false;
            }
            mApps.put(config, new AppInfo(callback));
            Message msg = mHandler.obtainMessage(MESSAGE_REGISTER_APPLICATION);
            msg.obj = config;
            mHandler.sendMessage(msg);
            return true;
        }

        public boolean unregisterAppConfiguration(BluetoothHealthAppConfiguration config) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            if (mApps.get(config) == null) {
                if (DBG) log("unregisterAppConfiguration: no app found");
                return false;
            }

            Message msg = mHandler.obtainMessage(MESSAGE_UNREGISTER_APPLICATION);
            msg.obj = config;
            mHandler.sendMessage(msg);
            return true;
        }

        public boolean connectChannelToSource(BluetoothDevice device,
                                              BluetoothHealthAppConfiguration config) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return connectChannel(device, config, BluetoothHealth.CHANNEL_TYPE_ANY);
        }

        public boolean connectChannelToSink(BluetoothDevice device,
                           BluetoothHealthAppConfiguration config, int channelType) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return connectChannel(device, config, channelType);
        }

        public boolean disconnectChannel(BluetoothDevice device,
                                         BluetoothHealthAppConfiguration config, int channelId) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            HealthChannel chan = findChannelById(channelId);
            if (chan == null) {
                if (DBG) log("disconnectChannel: no channel found");
                return false;
            }
            Message msg = mHandler.obtainMessage(MESSAGE_DISCONNECT_CHANNEL);
            msg.obj = chan;
            mHandler.sendMessage(msg);
            return true;
        }

        public ParcelFileDescriptor getMainChannelFd(BluetoothDevice device,
                                                     BluetoothHealthAppConfiguration config) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            HealthChannel healthChan = null;
            for (HealthChannel chan: mHealthChannels) {
                if (chan.mDevice.equals(device) && chan.mConfig.equals(config)) {
                    healthChan = chan;
                }
            }
            if (healthChan == null) {
                Log.e(TAG, "No channel found for device: " + device + " config: " + config);
                return null;
            }
            return healthChan.mChannelFd;
        }

        public int getHealthDeviceConnectionState(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return getConnectionState(device);
        }

        public List<BluetoothDevice> getConnectedHealthDevices() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            List<BluetoothDevice> devices = lookupHealthDevicesMatchingStates(
                    new int[] {BluetoothHealth.STATE_CONNECTED});
            return devices;
        }

        public List<BluetoothDevice> getHealthDevicesMatchingConnectionStates(int[] states) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            List<BluetoothDevice> devices = lookupHealthDevicesMatchingStates(states);
            return devices;
        }
    };

    private void onAppRegistrationState(int appId, int state) {
        Message msg = mHandler.obtainMessage(MESSAGE_APP_REGISTRATION_CALLBACK);
        msg.arg1 = appId;
        msg.arg2 = state;
        mHandler.sendMessage(msg);
    }

    private void onChannelStateChanged(int appId, byte[] addr, int cfgIndex,
                                       int channelId, int state, FileDescriptor pfd) {
        Message msg = mHandler.obtainMessage(MESSAGE_CHANNEL_STATE_CALLBACK);
        ChannelStateEvent channelStateEvent = new ChannelStateEvent(appId, addr, cfgIndex,
                                                                    channelId, state, pfd);
        msg.obj = channelStateEvent;
        mHandler.sendMessage(msg);
    }

    private String getStringChannelType(int type) {
        if (type == BluetoothHealth.CHANNEL_TYPE_RELIABLE) {
            return "Reliable";
        } else if (type == BluetoothHealth.CHANNEL_TYPE_STREAMING) {
            return "Streaming";
        } else {
            return "Any";
        }
    }

    private void callStatusCallback(BluetoothHealthAppConfiguration config, int status) {
        if (DBG) log ("Health Device Application: " + config + " State Change: status:" + status);
        IBluetoothHealthCallback callback = (mApps.get(config)).mCallback;
        if (callback == null) {
            Log.e(TAG, "Callback object null");
        }

        try {
            callback.onHealthAppConfigurationStatusChange(config, status);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote Exception:" + e);
        }
    }

    private BluetoothHealthAppConfiguration findAppConfigByAppId(int appId) {
        BluetoothHealthAppConfiguration appConfig = null;
        for (Entry<BluetoothHealthAppConfiguration, AppInfo> e : mApps.entrySet()) {
            if (appId == (e.getValue()).mAppId) {
                appConfig = e.getKey();
                break;
            }
        }
        if (appConfig == null) {
            Log.e(TAG, "No appConfig found for " + appId);
        }
        return appConfig;
    }

    private int convertHalRegStatus(int halRegStatus) {
        switch (halRegStatus) {
            case APP_REG_STATE_REG_SUCCESS:
                return BluetoothHealth.APP_CONFIG_REGISTRATION_SUCCESS;
            case APP_REG_STATE_REG_FAILED:
                return BluetoothHealth.APP_CONFIG_REGISTRATION_FAILURE;
            case APP_REG_STATE_DEREG_SUCCESS:
                return BluetoothHealth.APP_CONFIG_UNREGISTRATION_SUCCESS;
            case APP_REG_STATE_DEREG_FAILED:
                return BluetoothHealth.APP_CONFIG_UNREGISTRATION_FAILURE;
        }
        Log.e(TAG, "Unexpected App Registration state: " + halRegStatus);
        return BluetoothHealth.APP_CONFIG_REGISTRATION_FAILURE;
    }

    private int convertHalChannelState(int halChannelState) {
        switch (halChannelState) {
            case CONN_STATE_CONNECTED:
                return BluetoothHealth.STATE_CHANNEL_CONNECTED;
            case CONN_STATE_CONNECTING:
                return BluetoothHealth.STATE_CHANNEL_CONNECTING;
            case CONN_STATE_DISCONNECTING:
                return BluetoothHealth.STATE_CHANNEL_DISCONNECTING;
            case CONN_STATE_DISCONNECTED:
                return BluetoothHealth.STATE_CHANNEL_DISCONNECTED;
            case CONN_STATE_DESTROYED:
                // TODO(BT) add BluetoothHealth.STATE_CHANNEL_DESTROYED;
                return BluetoothHealth.STATE_CHANNEL_DISCONNECTED;
            default:
                Log.e(TAG, "Unexpected channel state: " + halChannelState);
                return BluetoothHealth.STATE_CHANNEL_DISCONNECTED;
        }
    }

    private boolean connectChannel(BluetoothDevice device,
                                   BluetoothHealthAppConfiguration config, int channelType) {
        if (mApps.get(config) == null) {
            Log.e(TAG, "connectChannel fail to get a app id from config");
            return false;
        }

        HealthChannel chan = new HealthChannel(device, config, channelType);
        mHealthChannels.add(chan);

        Message msg = mHandler.obtainMessage(MESSAGE_CONNECT_CHANNEL);
        msg.obj = chan;
        mHandler.sendMessage(msg);

        return true;
    }

    private void callHealthChannelCallback(BluetoothHealthAppConfiguration config,
            BluetoothDevice device, int state, int prevState, ParcelFileDescriptor fd, int id) {
        broadcastHealthDeviceStateChange(device, state);

        if (DBG) log("Health Device Callback: " + device + " State Change: " + prevState + "->" +
                     state);

        ParcelFileDescriptor dupedFd = null;
        if (fd != null) {
            try {
                dupedFd = fd.dup();
            } catch (IOException e) {
                dupedFd = null;
                Log.e(TAG, "Exception while duping: " + e);
            }
        }

        IBluetoothHealthCallback callback = (mApps.get(config)).mCallback;
        if (callback == null) {
            Log.e(TAG, "No callback found for config: " + config);
            return;
        }

        try {
            callback.onHealthChannelStateChange(config, device, prevState, state, dupedFd, id);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote Exception:" + e);
        }
    }

    /**
     * This function sends the intent for the updates on the connection status to the remote device.
     * Note that multiple channels can be connected to the remote device by multiple applications.
     * This sends an intent for the update to the device connection status and not the channel
     * connection status. Only the following state transitions are possible:
     *
     * {@link BluetoothHealth#STATE_DISCONNECTED} to {@link BluetoothHealth#STATE_CONNECTING}
     * {@link BluetoothHealth#STATE_CONNECTING} to {@link BluetoothHealth#STATE_CONNECTED}
     * {@link BluetoothHealth#STATE_CONNECTED} to {@link BluetoothHealth#STATE_DISCONNECTING}
     * {@link BluetoothHealth#STATE_DISCONNECTING} to {@link BluetoothHealth#STATE_DISCONNECTED}
     * {@link BluetoothHealth#STATE_DISCONNECTED} to {@link BluetoothHealth#STATE_CONNECTED}
     * {@link BluetoothHealth#STATE_CONNECTED} to {@link BluetoothHealth#STATE_DISCONNECTED}
     * {@link BluetoothHealth#STATE_CONNECTING} to {{@link BluetoothHealth#STATE_DISCONNECTED}
     *
     * @param device
     * @param prevChannelState
     * @param newChannelState
     * @hide
     */
    private void broadcastHealthDeviceStateChange(BluetoothDevice device, int newChannelState) {
        if (mHealthDevices.get(device) == null) {
            mHealthDevices.put(device, BluetoothHealth.STATE_DISCONNECTED);
        }

        int currDeviceState = mHealthDevices.get(device);
        int newDeviceState = convertState(newChannelState);

        if (currDeviceState == newDeviceState) return;

        boolean sendIntent = false;
        List<HealthChannel> chan;
        switch (currDeviceState) {
            case BluetoothHealth.STATE_DISCONNECTED:
                // there was no connection or connect/disconnect attemp with the remote device
                sendIntent = true;
                break;
            case BluetoothHealth.STATE_CONNECTING:
                // there was no connection, there was a connecting attempt going on

                // Channel got connected.
                if (newDeviceState == BluetoothHealth.STATE_CONNECTED) {
                    sendIntent = true;
                } else {
                    // Channel got disconnected
                    chan = findChannelByStates(device, new int [] {
                            BluetoothHealth.STATE_CHANNEL_CONNECTING,
                            BluetoothHealth.STATE_CHANNEL_DISCONNECTING});
                    if (chan.isEmpty()) {
                        sendIntent = true;
                    }
                }
                break;
            case BluetoothHealth.STATE_CONNECTED:
                // there was at least one connection

                // Channel got disconnected or is in disconnecting state.
                chan = findChannelByStates(device, new int [] {
                        BluetoothHealth.STATE_CHANNEL_CONNECTING,
                        BluetoothHealth.STATE_CHANNEL_CONNECTED});
                if (chan.isEmpty()) {
                    sendIntent = true;
                }
                break;
            case BluetoothHealth.STATE_DISCONNECTING:
                // there was no connected channel with the remote device
                // We were disconnecting all the channels with the remote device

                // Channel got disconnected.
                chan = findChannelByStates(device, new int [] {
                        BluetoothHealth.STATE_CHANNEL_CONNECTING,
                        BluetoothHealth.STATE_CHANNEL_DISCONNECTING});
                if (chan.isEmpty()) {
                    updateAndSendIntent(device, newDeviceState, currDeviceState);
                }
                break;
        }
        if (sendIntent)
            updateAndSendIntent(device, newDeviceState, currDeviceState);
    }

    private void updateAndSendIntent(BluetoothDevice device, int newDeviceState,
            int prevDeviceState) {
        if (newDeviceState == BluetoothHealth.STATE_DISCONNECTED) {
            mHealthDevices.remove(device);
        } else {
            mHealthDevices.put(device, newDeviceState);
        }
        AdapterService svc = AdapterService.getAdapterService();
        if (svc != null) {
            svc.onProfileConnectionStateChanged(device, BluetoothProfile.HEALTH, newDeviceState, prevDeviceState);
        }
    }

    /**
     * This function converts the channel connection state to device connection state.
     *
     * @param state
     * @return
     */
    private int convertState(int state) {
        switch (state) {
            case BluetoothHealth.STATE_CHANNEL_CONNECTED:
                return BluetoothHealth.STATE_CONNECTED;
            case BluetoothHealth.STATE_CHANNEL_CONNECTING:
                return BluetoothHealth.STATE_CONNECTING;
            case BluetoothHealth.STATE_CHANNEL_DISCONNECTING:
                return BluetoothHealth.STATE_DISCONNECTING;
            case BluetoothHealth.STATE_CHANNEL_DISCONNECTED:
                return BluetoothHealth.STATE_DISCONNECTED;
        }
        Log.e(TAG, "Mismatch in Channel and Health Device State: " + state);
        return BluetoothHealth.STATE_DISCONNECTED;
    }

    private int convertRoleToHal(int role) {
        if (role == BluetoothHealth.SOURCE_ROLE) return MDEP_ROLE_SOURCE;
        if (role == BluetoothHealth.SINK_ROLE) return MDEP_ROLE_SINK;
        Log.e(TAG, "unkonw role: " + role);
        return MDEP_ROLE_SINK;
    }

    private int convertChannelTypeToHal(int channelType) {
        if (channelType == BluetoothHealth.CHANNEL_TYPE_RELIABLE) return CHANNEL_TYPE_RELIABLE;
        if (channelType == BluetoothHealth.CHANNEL_TYPE_STREAMING) return CHANNEL_TYPE_STREAMING;
        if (channelType == BluetoothHealth.CHANNEL_TYPE_ANY) return CHANNEL_TYPE_ANY;
        Log.e(TAG, "unkonw channel type: " + channelType);
        return CHANNEL_TYPE_ANY;
    }

    private HealthChannel findChannelById(int id) {
        for (HealthChannel chan : mHealthChannels) {
            if (chan.mChannelId == id) return chan;
        }
        Log.e(TAG, "No channel found by id: " + id);
        return null;
    }

    private List<HealthChannel> findChannelByStates(BluetoothDevice device, int[] states) {
        List<HealthChannel> channels = new ArrayList<HealthChannel>();
        for (HealthChannel chan: mHealthChannels) {
            if (chan.mDevice.equals(device)) {
                for (int state : states) {
                    if (chan.mState == state) {
                        channels.add(chan);
                    }
                }
            }
        }
        return channels;
    }

    private BluetoothDevice getDevice(byte[] address) {
        return mAdapter.getRemoteDevice(Utils.getAddressStringFromByte(address));
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
    }

    private int getConnectionState(BluetoothDevice device) {
        if (mHealthDevices.get(device) == null) {
            return BluetoothHealth.STATE_DISCONNECTED;
        }
        return mHealthDevices.get(device);
    }

    List<BluetoothDevice> lookupHealthDevicesMatchingStates(int[] states) {
        List<BluetoothDevice> healthDevices = new ArrayList<BluetoothDevice>();

        for (BluetoothDevice device: mHealthDevices.keySet()) {
            int healthDeviceState = getConnectionState(device);
            for (int state : states) {
                if (state == healthDeviceState) {
                    healthDevices.add(device);
                    break;
                }
            }
        }
        return healthDevices;
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }

    private class AppInfo {
        private IBluetoothHealthCallback mCallback;
        private int mAppId;

        private AppInfo(IBluetoothHealthCallback callback) {
            mCallback = callback;
            mAppId = -1;
        }
    }

    private class HealthChannel {
        private ParcelFileDescriptor mChannelFd;
        private BluetoothDevice mDevice;
        private BluetoothHealthAppConfiguration mConfig;
        // BluetoothHealth channel state
        private int mState;
        private int mChannelType;
        private int mChannelId;

        private HealthChannel(BluetoothDevice device, BluetoothHealthAppConfiguration config,
                      int channelType) {
             mChannelFd = null;
             mDevice = device;
             mConfig = config;
             mState = BluetoothHealth.STATE_CHANNEL_DISCONNECTED;
             mChannelType = channelType;
             mChannelId = -1;
        }
    }

    // Channel state event from Hal
    private class ChannelStateEvent {
        int mAppId;
        byte[] mAddr;
        int mCfgIndex;
        int mChannelId;
        int mState;
        FileDescriptor mFd;

        private ChannelStateEvent(int appId, byte[] addr, int cfgIndex,
                                  int channelId, int state, FileDescriptor fileDescriptor) {
            mAppId = appId;
            mAddr = addr;
            mCfgIndex = cfgIndex;
            mState = state;
            mChannelId = channelId;
            mFd = fileDescriptor;
        }
    }

    // Constants matching Hal header file bt_hl.h
    // bthl_app_reg_state_t
    private static final int APP_REG_STATE_REG_SUCCESS = 0;
    private static final int APP_REG_STATE_REG_FAILED = 1;
    private static final int APP_REG_STATE_DEREG_SUCCESS = 2;
    private static final int APP_REG_STATE_DEREG_FAILED = 3;

    // bthl_channel_state_t
    private static final int CONN_STATE_CONNECTING = 0;
    private static final int CONN_STATE_CONNECTED = 1;
    private static final int CONN_STATE_DISCONNECTING = 2;
    private static final int CONN_STATE_DISCONNECTED = 3;
    private static final int CONN_STATE_DESTROYED = 4;

    // bthl_mdep_role_t
    private static final int MDEP_ROLE_SOURCE = 0;
    private static final int MDEP_ROLE_SINK = 1;

    // bthl_channel_type_t
    private static final int CHANNEL_TYPE_RELIABLE = 0;
    private static final int CHANNEL_TYPE_STREAMING = 1;
    private static final int CHANNEL_TYPE_ANY =2;

    private native static void classInitNative();
    private native void initializeNative();
    private native void cleanupNative();
    private native int registerHealthAppNative(int dataType, int role, String name, int channelType);
    private native boolean unregisterHealthAppNative(int appId);
    private native int connectChannelNative(byte[] btAddress, int appId);
    private native boolean disconnectChannelNative(int channelId);
}
