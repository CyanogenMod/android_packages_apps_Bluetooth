/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

/**
 * @hide
 */

package com.android.bluetooth.btservice;

import android.app.Application;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.QBluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLEServiceUuid;
import android.bluetooth.IQBluetoothAdapterCallback;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetooth;
import android.bluetooth.IQBluetooth;
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
import android.os.Process;
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
import com.android.bluetooth.LeScanRequestArbitrator;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.Map;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.List;
import android.content.pm.PackageManager;
import android.os.ServiceManager;
import android.os.PowerManager;
import android.content.Context;

public class QAdapterService extends Service {
    private static final String TAG = "QBluetoothAdapterService";
    private static final boolean DBG = false;
    private static final boolean TRACE_REF = true;
    /*only support at most one le extended scan request */
    private static final int LE_EXTENDED_SCAN_TOKEN = 1;

    //For Debugging only
    private static int sRefCount=0;

    static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    static {
        classInitNative();
    }

    private static AdapterService mAdapterService;
    private static QAdapterService mQAdapterService;
    private LeExtendedScanSession mLeExtendedScanSession = null;

    private final Map<String, IQBluetoothAdapterCallback> mLeLppMonitorClients = new HashMap<String, IQBluetoothAdapterCallback>();

    public static synchronized QAdapterService getAdapterService(){
        if (mQAdapterService != null && !mQAdapterService.mCleaningUp) {
            return mQAdapterService;
        }
        if (DBG)  {
            if (mQAdapterService == null) {
                Log.e(TAG, "getAdapterService(): service not available");
            } else if (mQAdapterService.mCleaningUp) {
                Log.i(TAG,"getAdapterService(): service is cleaning up");
            }
        }
        return null;
    }

    private static synchronized void setAdapterService(QAdapterService instance) {
        if (instance != null && !instance.mCleaningUp) {
            if (DBG) Log.d(TAG, "setAdapterService(): set to: " + mQAdapterService);
            mQAdapterService = instance;
        } else {
            if (DBG)  {
                if (mQAdapterService == null) {
                    Log.e(TAG, "setAdapterService(): service not available");
                } else if (mQAdapterService.mCleaningUp) {
                    Log.i(TAG,"setAdapterService(): service is cleaning up");
                }
            }
        }
    }

    private static synchronized void clearAdapterService() {
        mQAdapterService = null;
    }

    private QAdapterProperties mAdapterProperties;
    private QJniCallbacks mJniCallbacks;

    private boolean mNativeAvailable;
    private boolean mCleaningUp;
    private Object mScanLock = new Object();
    public QAdapterService() {
        super();
        if (TRACE_REF) {
            synchronized (QAdapterService.class) {
                sRefCount++;
                Log.i(TAG, "REFCOUNT: CREATED. INSTANCE_COUNT" + sRefCount);
            }
        }
    }

     @Override
    public void onCreate() {
        super.onCreate();
        mQBinder = new QAdapterServiceBinder(this);
        mAdapterProperties=new QAdapterProperties(this);
        mJniCallbacks=new QJniCallbacks(mAdapterProperties, this);

        initNative();
        mNativeAvailable=true;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mQBinder;
    }
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    public void onDestroy() {
    }

    public void onLeExtendedScanResult(String address, int rssi, byte[] advData) {
        IQBluetoothAdapterCallback client = null;
        synchronized(mScanLock) {
            if(mLeExtendedScanSession != null) {
                client = mLeExtendedScanSession.mClient;
            }
        }
        if (client != null) {
            try {
                client.onScanResult(address, rssi, advData);
            } catch (RemoteException e) {
                 stopLeExtendedScan(mLeExtendedScanSession.mScanToken);
                 Log.w(TAG, "", e);
            }
        }
    }

   void onLeLppWriteRssiThreshold(String address, int status) {
        IQBluetoothAdapterCallback client = null;
        synchronized(mLeLppMonitorClients) {
            client = mLeLppMonitorClients.get(address);
        }

        if (client != null) {
            try {
                client.onWriteRssiThreshold(address, status);
            } catch (RemoteException e) {
                Log.w(TAG, "", e);
                synchronized(mLeLppMonitorClients) {
                    mLeLppMonitorClients.remove(address);
                }
            }
        }
    }

    void onLeLppReadRssiThreshold(String address, int low, int upper,
                                  int alert, int status) {
        IQBluetoothAdapterCallback client = null;
        synchronized(mLeLppMonitorClients) {
            client = mLeLppMonitorClients.get(address);
        }

        if (client != null) {
            try {
                client.onReadRssiThreshold(address, low, upper, alert, status);
            } catch (RemoteException e) {
                Log.w(TAG, "", e);
                synchronized(mLeLppMonitorClients) {
                    mLeLppMonitorClients.remove(address);
                }
            }
        }
    }

    void onLeLppEnableRssiMonitor(String address, int enable, int status) {
        IQBluetoothAdapterCallback client = null;
        synchronized(mLeLppMonitorClients) {
            client = mLeLppMonitorClients.get(address);
        }

        if (client != null) {
            try {
                client.onEnableRssiMonitor(address, enable, status);
            } catch (RemoteException e) {
                Log.w(TAG, "", e);
                synchronized(mLeLppMonitorClients) {
                    mLeLppMonitorClients.remove(address);
                }
            }
        }
    }

    void onLeLppRssiThresholdEvent(String address, int evtType, int rssi) {
        IQBluetoothAdapterCallback client = null;
        synchronized(mLeLppMonitorClients) {
            client = mLeLppMonitorClients.get(address);
        }

        if (client != null) {
            try {
                client.onRssiThresholdEvent(address, evtType, rssi);
            } catch (RemoteException e) {
                Log.w(TAG, "", e);
                synchronized(mLeLppMonitorClients) {
                    mLeLppMonitorClients.remove(address);
                }
            }
        }
    }

    void cleanup () {
        if (mCleaningUp) {
            Log.w(TAG,"*************service already starting to cleanup... Ignoring cleanup request.........");
            return;
        }

        mCleaningUp = true;

        if (mNativeAvailable) {
            mNativeAvailable=false;
        }

        if (mAdapterProperties != null) {
            mAdapterProperties.cleanup();
        }

        if (mJniCallbacks != null) {
            mJniCallbacks.cleanup();
        }

        clearAdapterService();

        if (mQBinder != null) {
            mQBinder.cleanup();
            mQBinder = null;  //Do not remove. Otherwise Binder leak!
        }

    }

    private boolean isAvailable() {
        return !mCleaningUp;
    }

    private QAdapterServiceBinder mQBinder;

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
    private static class QAdapterServiceBinder extends IQBluetooth.Stub {
         private QAdapterService mQService;

         public QAdapterServiceBinder(QAdapterService svc) {
             mQService = svc;
         }
         public boolean cleanup() {
             mQService = null;
             return true;
         }

         public QAdapterService getService() {
             if (mQService  != null && mQService.isAvailable()) {
                 return mQService;
             }
             return null;
         }

          public int startLeScanEx(BluetoothLEServiceUuid[] services, IQBluetoothAdapterCallback callback) {
              QAdapterService service = getService();
              if (service == null) return -1;
              return service.startLeExtendedScan(services, callback);
          }

          public void stopLeScanEx(int token) {
              QAdapterService service = getService();
              if (service == null) return;
              service.stopLeExtendedScan(token);
          }

          public boolean registerLeLppRssiMonitorClient(String address, IQBluetoothAdapterCallback client, boolean add) {
              QAdapterService service = getService();
              if (service == null) return false;
              return service.registerRssiMonitorClient(address, client, add);
          }

          public void writeLeLppRssiThreshold(String address, byte min, byte max) {
              QAdapterService service = getService();
              if (service == null) return;
              service.writeRssiThreshold(address, min, max);
          }

          public void readLeLppRssiThreshold(String address) {
              QAdapterService service = getService();
              if (service == null) return;
              service.readRssiThreshold(address);
          }

          public void enableLeLppRssiMonitor(String address, boolean enable) {
              QAdapterService service = getService();
              if (service == null) return;
              service.enableRssiMonitor(address, enable);
          }
    }


    //----API Methods--------

     int startLeExtendedScan(BluetoothLEServiceUuid[] services, IQBluetoothAdapterCallback callback) {
         enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
         /* get token to start extended scan*/
         int scanToken = -1;
         synchronized(mScanLock) {
         if (mLeExtendedScanSession == null &&
                 LeScanRequestArbitrator.instance().RequestLeScan(LeScanRequestArbitrator.LE_EXTENDED_SCAN_TYPE)) {
                 mLeExtendedScanSession = new LeExtendedScanSession(LE_EXTENDED_SCAN_TOKEN, services, callback);
                 if (mLeExtendedScanSession != null) {
                     btLeExtendedScanNative(mLeExtendedScanSession.mServiceList, true);
                     scanToken = mLeExtendedScanSession.mScanToken;
                 } else {
                     Log.e(TAG, "No Resource");
                     LeScanRequestArbitrator.instance().StopLeScan(LeScanRequestArbitrator.LE_EXTENDED_SCAN_TYPE);
                 }
             }
         }
         return scanToken;
     }

     void stopLeExtendedScan(int token) {
         enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
         synchronized(mScanLock) {
            if (mLeExtendedScanSession != null &&
                mLeExtendedScanSession.mScanToken == token) {
                btLeExtendedScanNative(mLeExtendedScanSession.mServiceList, false);
                mLeExtendedScanSession = null;
                LeScanRequestArbitrator.instance().StopLeScan(LeScanRequestArbitrator.LE_EXTENDED_SCAN_TYPE);
            }
         }
     }

     boolean registerRssiMonitorClient(String address, IQBluetoothAdapterCallback client, boolean add) {
         enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
         synchronized(mLeLppMonitorClients) {
             if(add) {
                 if(mLeLppMonitorClients.containsKey(address)) {
                     IQBluetoothAdapterCallback savedClient = mLeLppMonitorClients.get(address);
                     try {
                         if (savedClient == null || !savedClient.onUpdateLease()) {
                             if (DBG) Log.d(TAG, "Cannot update the lease");
                         mLeLppMonitorClients.remove(address);
                         }
                     } catch (RemoteException e) {
                         if (DBG) Log.d(TAG, "", e);
                         mLeLppMonitorClients.remove(address);
                     }
                 }
                 if(mLeLppMonitorClients.containsKey(address) || client == null){
                     if (DBG) Log.e(TAG, "client already registered or invalid client, client=" + client);
                     return false;
                 }
                 mLeLppMonitorClients.put(address, client);
                 return true;
             } else {
                 IQBluetoothAdapterCallback savedClient = mLeLppMonitorClients.get(address);
                 /*if (savedClient == client) */{
                     mLeLppMonitorClients.remove(address);
                     return true;
                 }
             }
         }
     }

     void writeRssiThreshold(String address, byte min, byte max)
     {
         enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
         synchronized(mLeLppMonitorClients) {
             if(mLeLppMonitorClients.containsKey(address)) {
                 btLeLppWriteRssiThresholdNative(address, min, max);
             }
         }
     }

     void enableRssiMonitor(String address, boolean enable) {
         enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
         synchronized(mLeLppMonitorClients) {
             if(mLeLppMonitorClients.containsKey(address)) {
                 btLeLppEnableRssiMonitorNative(address, enable);
             }
         }
     }

     void readRssiThreshold(String address) {
         enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
         synchronized(mLeLppMonitorClients) {
             if(mLeLppMonitorClients.containsKey(address)) {
                 btLeLppReadRssiThresholdNative(address);
             }
         }
     }

     private static final class LeExtendedScanSession {
         public final int mScanToken;
         public final BluetoothLEServiceUuid[] mServiceList;
         public final IQBluetoothAdapterCallback mClient;
         public LeExtendedScanSession(int token, BluetoothLEServiceUuid[] services,
                                      IQBluetoothAdapterCallback callback) {
             this.mScanToken = token;
             this.mServiceList = services;
             this.mClient = callback;
         }
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

    private native void btLeExtendedScanNative(BluetoothLEServiceUuid[] uuids, boolean start);
    private native void btLeLppWriteRssiThresholdNative(String address, byte min, byte max);

    private native void btLeLppEnableRssiMonitorNative(String address, boolean enable);

    private native void btLeLppReadRssiThresholdNative(String address);


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

