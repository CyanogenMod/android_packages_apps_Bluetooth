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

    //Profile services will not be automatically restarted.
    //They must be explicitly restarted by AdapterService
    private static final int PROFILE_SERVICE_MODE=Service.START_NOT_STICKY;

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
        mAdapter = BluetoothAdapter.getDefaultAdapter();

        log("onCreate");
        super.onCreate();
    }

    private void doStart(Intent intent) {

        //Start service
        if (mAdapter == null) {
            Log.e(mName, "Error starting profile. BluetoothAdapter is null");
        } else {
            mStartError = !start();
            if (!mStartError) {
                notifyProfileServiceStateChange(BluetoothAdapter.STATE_ON);
            } else {
                Log.e(mName, "Error starting profile. BluetoothAdapter is null");
            }
        }
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        log("onStartCommand()");
        if (mStartError || mAdapter == null) {
            Log.w(mName, "Stopping profile service: device does not have BT");
            doStop(intent);
            return PROFILE_SERVICE_MODE;
        }

        if (checkCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM)!=PackageManager.PERMISSION_GRANTED) {
            Log.e(mName, "Permission denied!");
            return PROFILE_SERVICE_MODE;
        }

        if (intent == null) {
            Log.d(mName, "Restarting profile service...");
            return PROFILE_SERVICE_MODE;
        } else {
            String action = intent.getStringExtra(AdapterService.EXTRA_ACTION);
            if (AdapterService.ACTION_SERVICE_STATE_CHANGED.equals(action)) {
                int state= intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if(state==BluetoothAdapter.STATE_OFF) {
                    Log.d(mName, "Received stop request...Stopping profile...");
                    doStop(intent);
                } else if (state == BluetoothAdapter.STATE_ON) {
                    Log.d(mName, "Received start request. Starting profile...");
                    doStart(intent);
                }
            }
        }
        return PROFILE_SERVICE_MODE;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        log("Destroying service.");
    }

    private void doStop(Intent intent) {
        if (stop()) {
            notifyProfileServiceStateChange(BluetoothAdapter.STATE_OFF);
            stopSelf();
        } else {
            Log.e(mName, "Unable to stop profile");
        }
    }

    protected void notifyProfileServiceStateChange(int state) {
        //Notify adapter service
        AdapterService sAdapter = AdapterService.getAdapterService();
        if (sAdapter!= null) {
            sAdapter.onProfileServiceStateChanged(getClass().getName(), state);
        }
    }


    protected void log(String msg) {
        Log.d(mName, msg);
    }
}
