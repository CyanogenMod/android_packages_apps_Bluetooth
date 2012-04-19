package com.android.bluetooth.btservice;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

public abstract class ProfileService extends Service {
    public static final String BLUETOOTH_ADMIN_PERM =
            android.Manifest.permission.BLUETOOTH_ADMIN;
    public static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    protected BluetoothAdapter mAdapter;
    protected String mName;

    protected String getName() {
        return getClass().getSimpleName();
    }

    public abstract IBinder onBind(Intent intent);
    protected abstract boolean start();
    protected abstract boolean stop();

    public boolean mStartError=false;
    @Override
    public void onCreate() {
        mName = getName();
        if (mName == null) {
            mName = "UnknownProfileService";
        }

        log("onCreate");
        super.onCreate();
        mAdapter = BluetoothAdapter.getDefaultAdapter();

        //Start service
        if (mAdapter == null) {
            Log.e(mName, "Error starting profile. BluetoothAdapter is null");
        } else {
            mStartError = !start();
            if (!mStartError) {
                notifyProfileOn();
            } else {
                Log.e(mName, "Error starting profile. BluetoothAdapter is null");
            }
        }
    }

    public void onStart(Intent intent, int startId) {
        log("onStart");

        if (mStartError || mAdapter == null) {
            Log.w(mName, "Stopping profile service: device does not have BT");
            doStop();
            return;
        }

        if (checkCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM)!=PackageManager.PERMISSION_GRANTED) {
            Log.e(mName, "Permission denied!");
            return;
        }

        String action = intent.getStringExtra(AdapterService.EXTRA_ACTION);
        if (AdapterService.ACTION_SERVICE_STATE_CHANGED.equals(action)) {
            int state= intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);        
            if(state==BluetoothAdapter.STATE_OFF) {
                Log.d(mName, "Received stop request...Stopping profile...");
                doStop();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mAdapter = null;
        log("Destroying service.");
    }

    private void doStop() {
        if (stop()) {
            notifyProfileOff();
            stopSelf();
        } else {
            Log.e(mName, "Unable to stop profile");
        }
    }

    protected void notifyProfileOn() {
        //Notify adapter service
        AdapterService sAdapter = AdapterService.getAdapterService();
        if (sAdapter!= null) {
            sAdapter.onProfileServiceStateChanged(getClass().getName(), BluetoothAdapter.STATE_ON);
        }
    }

    protected void notifyProfileOff() {
        //Notify adapter service
        AdapterService sAdapter = AdapterService.getAdapterService();
        if (sAdapter!= null) {
            sAdapter.onProfileServiceStateChanged(getClass().getName(), BluetoothAdapter.STATE_OFF);
        }
    }

    protected void log(String msg) {
        Log.d(mName, msg);
    }
}
