/*
 * Copyright (C) 2012 Google Inc.
 */

package com.android.bluetooth.pan;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothTetheringDataTracker;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothPan;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources.NotFoundException;
import android.net.ConnectivityManager;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.NetworkUtils;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;

/**
 * Provides Bluetooth Pan Device profile, as a service in
 * the Bluetooth application.
 * @hide
 */
public class PanService extends Service {
    private static final String TAG = "BluetoothPanService";
    private static final boolean DBG = true;

    static final String BLUETOOTH_ADMIN_PERM =
        android.Manifest.permission.BLUETOOTH_ADMIN;
    static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final String BLUETOOTH_IFACE_ADDR_START= "192.168.44.1";
    private static final int BLUETOOTH_MAX_PAN_CONNECTIONS = 5;
    private static final int BLUETOOTH_PREFIX_LENGTH        = 24;

    private BluetoothAdapter mAdapter;
    private HashMap<BluetoothDevice, BluetoothPanDevice> mPanDevices;
    private ArrayList<String> mBluetoothIfaceAddresses;
    private int mMaxPanDevices;
    private String mPanIfName;

    private static final int MESSAGE_CONNECT = 1;
    private static final int MESSAGE_DISCONNECT = 2;
    private static final int MESSAGE_SET_TETHERING = 3;
    private static final int MESSAGE_CONNECT_STATE_CHANGED = 11;


    static {
        classInitNative();
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");
        mAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @Override
    public void onStart(Intent intent, int startId) {
        log("onStart");
        if (mAdapter == null) {
            Log.w(TAG, "Stopping Bluetooth Pan Service: device does not have BT");
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
    public IBinder onBind(Intent intent) {
        log("onBind");
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DBG) log("Stopping Bluetooth PanService");
    }

    private void start() {
        if (DBG) log("start");
        mPanDevices = new HashMap<BluetoothDevice, BluetoothPanDevice>();
        mBluetoothIfaceAddresses = new ArrayList<String>();
        try {
            mMaxPanDevices = getResources().getInteger(
                                 com.android.internal.R.integer.config_max_pan_devices);
        } catch (NotFoundException e) {
            mMaxPanDevices = BLUETOOTH_MAX_PAN_CONNECTIONS;
        }

        initializeNative();

        //Notify adapter service
        AdapterService sAdapter = AdapterService.getAdapterService();
        if (sAdapter!= null) {
            sAdapter.onProfileServiceStateChanged(getClass().getName(), BluetoothAdapter.STATE_ON);
        }

    }

    private void stop() {
        if (DBG) log("stop");

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

    private final Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_CONNECT:
                {
                    BluetoothDevice device = (BluetoothDevice) msg.obj;
                    if (!connectPanNative(getByteAddress(device), BluetoothPan.LOCAL_PANU_ROLE, BluetoothPan.REMOTE_NAP_ROLE)) {
                        handlePanDeviceStateChange(device, null, BluetoothProfile.STATE_CONNECTING,
                                                   BluetoothPan.LOCAL_PANU_ROLE, BluetoothPan.REMOTE_NAP_ROLE);
                        handlePanDeviceStateChange(device, null, BluetoothProfile.STATE_DISCONNECTED,
                                                   BluetoothPan.LOCAL_PANU_ROLE, BluetoothPan.REMOTE_NAP_ROLE);
                        break;
                    }
                }
                    break;
                case MESSAGE_DISCONNECT:
                {
                    BluetoothDevice device = (BluetoothDevice) msg.obj;
                    if (!disconnectPanNative(getByteAddress(device)) ) {
                        handlePanDeviceStateChange(device, mPanIfName, BluetoothProfile.STATE_DISCONNECTING,
                                                   BluetoothPan.LOCAL_PANU_ROLE, BluetoothPan.REMOTE_NAP_ROLE);
                        handlePanDeviceStateChange(device, mPanIfName, BluetoothProfile.STATE_DISCONNECTED,
                                                   BluetoothPan.LOCAL_PANU_ROLE, BluetoothPan.REMOTE_NAP_ROLE);
                        break;
                    }
                }
                    break;
                case MESSAGE_SET_TETHERING:
                    boolean tetherOn = (Boolean) msg.obj;
                    if(tetherOn)
                        enablePanNative(BluetoothPan.LOCAL_NAP_ROLE | BluetoothPan.LOCAL_PANU_ROLE);
                    else enablePanNative(BluetoothPan.LOCAL_PANU_ROLE);
                    break;
                case MESSAGE_CONNECT_STATE_CHANGED:
                {
                    ConnectState cs = (ConnectState)msg.obj;
                    BluetoothDevice device = getDevice(cs.addr);
                    // TBD get iface from the msg
                    if (DBG) log("MESSAGE_CONNECT_STATE_CHANGED: " + device + " state: " + cs.state);
                    handlePanDeviceStateChange(device, mPanIfName /* iface */, convertHalState(cs.state),
                                               cs.local_role,  cs.remote_role);
                }
                break;
            }
        }
    };

    /**
     * Handlers for incoming service calls
     */
    private final IBluetoothPan.Stub mBinder = new IBluetoothPan.Stub() {
        public boolean connect(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            if (getConnectionState(device) != BluetoothProfile.STATE_DISCONNECTED) {
                Log.e(TAG, "Pan Device not disconnected: " + device);
                return false;
            }
            Message msg = mHandler.obtainMessage(MESSAGE_CONNECT);
            msg.obj = device;
            mHandler.sendMessage(msg);
            return true;
        }

        public boolean disconnect(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            Message msg = mHandler.obtainMessage(MESSAGE_DISCONNECT);
            msg.obj = device;
            mHandler.sendMessage(msg);
            return true;
        }

        public int getConnectionState(BluetoothDevice device) {
            BluetoothPanDevice panDevice = mPanDevices.get(device);
            if (panDevice == null) {
                return BluetoothPan.STATE_DISCONNECTED;
            }
            return panDevice.mState;
        }
        private boolean isPanNapOn() {
            if(DBG) Log.d(TAG, "isTetheringOn call getPanLocalRoleNative");
            return (getPanLocalRoleNative() & BluetoothPan.LOCAL_NAP_ROLE) != 0;
        }
        private boolean isPanUOn() {
            if(DBG) Log.d(TAG, "isTetheringOn call getPanLocalRoleNative");
            return (getPanLocalRoleNative() & BluetoothPan.LOCAL_PANU_ROLE) != 0;
        }
        public boolean isTetheringOn() {
            // TODO(BT) have a variable marking the on/off state
            return isPanNapOn();
        }

        public void setBluetoothTethering(boolean value) {
            if(DBG) Log.d(TAG, "setBluetoothTethering: " + value);
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
            Message msg = mHandler.obtainMessage(MESSAGE_SET_TETHERING);
            msg.obj = new Boolean(value);
            mHandler.sendMessage(msg);
        }

        public List<BluetoothDevice> getConnectedDevices() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            List<BluetoothDevice> devices = getDevicesMatchingConnectionStates(
                    new int[] {BluetoothProfile.STATE_CONNECTED});
            return devices;
        }

        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            List<BluetoothDevice> panDevices = new ArrayList<BluetoothDevice>();

            for (BluetoothDevice device: mPanDevices.keySet()) {
                int panDeviceState = getConnectionState(device);
                for (int state : states) {
                    if (state == panDeviceState) {
                        panDevices.add(device);
                        break;
                    }
                }
            }
            return panDevices;
        }
    };
    static protected class ConnectState {
        public ConnectState(byte[] address, int state, int error, int local_role, int remote_role) {
            this.addr = address;
            this.state = state;
            this.error = error;
            this.local_role = local_role;
            this.remote_role = remote_role;
        }
        byte[] addr;
        int state;
        int error;
        int local_role;
        int remote_role;
    };
    private void onConnectStateChanged(byte[] address, int state, int error, int local_role, int remote_role) {
        if (DBG) log("onConnectStateChanged: " + state + ", local role:" + local_role + ", remote_role: " + remote_role);
        Message msg = mHandler.obtainMessage(MESSAGE_CONNECT_STATE_CHANGED);
        msg.obj = new ConnectState(address, state, error, local_role, remote_role);
        mHandler.sendMessage(msg);
    }
    private void onControlStateChanged(int local_role, int state, int error, String ifname) {
        if (DBG)
            log("onControlStateChanged: " + state + ", error: " + error + ", ifname: " + ifname);
        if(error == 0)
            mPanIfName = ifname;
    }
    private BluetoothDevice getDevice(byte[] address) {
        return mAdapter.getRemoteDevice(Utils.getAddressStringFromByte(address));
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
    }

    private int convertHalState(int halState) {
        switch (halState) {
            case CONN_STATE_CONNECTED:
                return BluetoothProfile.STATE_CONNECTED;
            case CONN_STATE_CONNECTING:
                return BluetoothProfile.STATE_CONNECTING;
            case CONN_STATE_DISCONNECTED:
                return BluetoothProfile.STATE_DISCONNECTED;
            case CONN_STATE_DISCONNECTING:
                return BluetoothProfile.STATE_DISCONNECTING;
            default:
                Log.e(TAG, "bad pan connection state: " + halState);
                return BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    void handlePanDeviceStateChange(BluetoothDevice device,
                                    String iface, int state, int local_role, int remote_role) {
        if(DBG) Log.d(TAG, "handlePanDeviceStateChange: device: " + device + ", iface: " + iface +
                    ", state: " + state + ", local_role:" + local_role + ", remote_role:" + remote_role);
        int prevState;
        String ifaceAddr = null;
        BluetoothPanDevice panDevice = mPanDevices.get(device);
        if (panDevice == null) {
            prevState = BluetoothProfile.STATE_DISCONNECTED;
        } else {
            prevState = panDevice.mState;
            ifaceAddr = panDevice.mIfaceAddr;
        }
        Log.d(TAG, "handlePanDeviceStateChange preState: " + prevState + " state: " + state);
        if (prevState == state) return;
        if (remote_role == BluetoothPan.LOCAL_PANU_ROLE) {
            Log.d(TAG, "handlePanDeviceStateChange LOCAL_NAP_ROLE:REMOTE_PANU_ROLE");
            if (state == BluetoothProfile.STATE_CONNECTED) {
                if(DBG) Log.d(TAG, "handlePanDeviceStateChange: nap STATE_CONNECTED, enableTethering iface: " + iface);
                ifaceAddr = enableTethering(iface);
                if (ifaceAddr == null) Log.e(TAG, "Error seting up tether interface");
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (ifaceAddr != null) {
                    mBluetoothIfaceAddresses.remove(ifaceAddr);
                    ifaceAddr = null;
                }
            }
        } else {
            // PANU Role = reverse Tether
            Log.d(TAG, "handlePanDeviceStateChange LOCAL_PANU_ROLE:REMOTE_NAP_ROLE");
            if (state == BluetoothProfile.STATE_CONNECTED) {
                if(DBG) Log.d(TAG, "handlePanDeviceStateChange: panu STATE_CONNECTED, startReverseTether");
                IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);
                Log.d(TAG, "call INetworkManagementService.startReverseTethering()");
                try {
                    service.startReverseTethering(iface);
                } catch (Exception e) {
                    Log.e(TAG, "Cannot start reverse tethering: " + e);
                    return;
                }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED &&
                  (prevState == BluetoothProfile.STATE_CONNECTED ||
                  prevState == BluetoothProfile.STATE_DISCONNECTING)) {
                if(DBG) Log.d(TAG, "handlePanDeviceStateChange: stopReverseTether, panDevice.mIface: "
                                    + panDevice.mIface);
                IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);
                try {
                    service.stopReverseTethering();
                } catch(Exception e) {
                    Log.e(TAG, "Cannot stop reverse tethering: " + e);
                    return;
                }
            }
        }

        if (panDevice == null) {
            panDevice = new BluetoothPanDevice(state, ifaceAddr, iface, local_role);
            mPanDevices.put(device, panDevice);
        } else {
            panDevice.mState = state;
            panDevice.mIfaceAddr = ifaceAddr;
            panDevice.mLocalRole = local_role;
            panDevice.mIface = iface;
        }

        Intent intent = new Intent(BluetoothPan.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothPan.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothPan.EXTRA_STATE, state);
        intent.putExtra(BluetoothPan.EXTRA_LOCAL_ROLE, local_role);
        sendBroadcast(intent, BLUETOOTH_PERM);

        if (DBG) Log.d(TAG, "Pan Device state : device: " + device + " State:" +
                       prevState + "->" + state);
        AdapterService svc = AdapterService.getAdapterService();
        if (svc != null) {
            svc.onProfileConnectionStateChanged(device, BluetoothProfile.PAN, state, prevState);
        }
    }

    // configured when we start tethering
    private String enableTethering(String iface) {
        if (DBG) Log.d(TAG, "updateTetherState:" + iface);

        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        INetworkManagementService service = INetworkManagementService.Stub.asInterface(b);
        ConnectivityManager cm =
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        String[] bluetoothRegexs = cm.getTetherableBluetoothRegexs();

        // bring toggle the interfaces
        String[] currentIfaces = new String[0];
        try {
            currentIfaces = service.listInterfaces();
        } catch (Exception e) {
            Log.e(TAG, "Error listing Interfaces :" + e);
            return null;
        }

        boolean found = false;
        for (String currIface: currentIfaces) {
            if (currIface.equals(iface)) {
                found = true;
                break;
            }
        }

        if (!found) return null;

        String address = createNewTetheringAddressLocked();
        if (address == null) return null;

        InterfaceConfiguration ifcg = null;
        try {
            ifcg = service.getInterfaceConfig(iface);
            if (ifcg != null) {
                InetAddress addr = null;
                if (ifcg.addr == null || (addr = ifcg.addr.getAddress()) == null ||
                        addr.equals(NetworkUtils.numericToInetAddress("0.0.0.0")) ||
                        addr.equals(NetworkUtils.numericToInetAddress("::0"))) {
                    addr = NetworkUtils.numericToInetAddress(address);
                }
                ifcg.interfaceFlags = ifcg.interfaceFlags.replace("down", "up");
                ifcg.addr = new LinkAddress(addr, BLUETOOTH_PREFIX_LENGTH);
                ifcg.interfaceFlags = ifcg.interfaceFlags.replace("running", "");
                ifcg.interfaceFlags = ifcg.interfaceFlags.replace("  "," ");
                service.setInterfaceConfig(iface, ifcg);
                if (cm.tether(iface) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                    Log.e(TAG, "Error tethering "+iface);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error configuring interface " + iface + ", :" + e);
            return null;
        }
        return address;
    }

    private String createNewTetheringAddressLocked() {
        if (getConnectedPanDevices().size() == mMaxPanDevices) {
            if (DBG) Log.d(TAG, "Max PAN device connections reached");
            return null;
        }
        String address = BLUETOOTH_IFACE_ADDR_START;
        while (true) {
            if (mBluetoothIfaceAddresses.contains(address)) {
                String[] addr = address.split("\\.");
                Integer newIp = Integer.parseInt(addr[2]) + 1;
                address = address.replace(addr[2], newIp.toString());
            } else {
                break;
            }
        }
        mBluetoothIfaceAddresses.add(address);
        return address;
    }

    private List<BluetoothDevice> getConnectedPanDevices() {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();

        for (BluetoothDevice device: mPanDevices.keySet()) {
            if (getPanDeviceConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                devices.add(device);
            }
        }
        return devices;
    }

    private int getPanDeviceConnectionState(BluetoothDevice device) {
        BluetoothPanDevice panDevice = mPanDevices.get(device);
        if (panDevice == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return panDevice.mState;
    }

    private class BluetoothPanDevice {
        private int mState;
        private String mIfaceAddr;
        private String mIface;
        private int mLocalRole; // Which local role is this PAN device bound to

        BluetoothPanDevice(int state, String ifaceAddr, String iface, int localRole) {
            mState = state;
            mIfaceAddr = ifaceAddr;
            mIface = iface;
            mLocalRole = localRole;
        }
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }

    // Constants matching Hal header file bt_hh.h
    // bthh_connection_state_t
    private final static int CONN_STATE_CONNECTED = 0;
    private final static int CONN_STATE_CONNECTING = 1;
    private final static int CONN_STATE_DISCONNECTED = 2;
    private final static int CONN_STATE_DISCONNECTING = 3;

    private native static void classInitNative();
    private native void initializeNative();
    private native void cleanupNative();
    private native boolean connectPanNative(byte[] btAddress, int local_role, int remote_role);
    private native boolean disconnectPanNative(byte[] btAddress);
    private native boolean enablePanNative(int local_role);
    private native int getPanLocalRoleNative();
}
