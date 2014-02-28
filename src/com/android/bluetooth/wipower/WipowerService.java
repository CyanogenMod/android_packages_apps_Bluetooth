/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *            notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *            notice, this list of conditions and the following disclaimer in the
 *            documentation and/or other materials provided with the distribution.
 *        * Neither the name of The Linux Foundation nor
 *            the names of its contributors may be used to endorse or promote
 *            products derived from this software without specific prior written
 *            permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.    IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.wipower;

import java.util.UUID;

import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.app.Service;
import android.net.Credentials;
import java.io.OutputStream;
import android.util.Log;
import android.os.IBinder;
import android.content.Intent;
import android.os.Process;
import java.nio.ByteBuffer;
import android.wipower.IWipower;
import android.wipower.IWipowerManagerCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

/**
 * Class which executes A4WP service
 */
public class WipowerService extends Service
{
    private static final String LOGTAG = "WipowerService";
    private static OutputStream mOutputStream = null;
    private BluetoothAdapter mBluetoothAdapter = null;
    private BluetoothGattServer mBluetoothGattServer = null;
    private BluetoothDevice mDevice = null;

    private static final Object mLock = new Object();
    private int mState = BluetoothProfile.STATE_DISCONNECTED;


    static {
        Log.e(LOGTAG, "call ClassInitNative()");
        classInitNative();
    }

    public boolean startCharging() {
        int ret = enableNative(true);
        return (ret==0) ? true : false;
    }

    public boolean stopCharging() {
        int ret = enableNative(false);
        return (ret==0) ? true : false;
    }

    public int getState() {
        int ret = getStateNative();
        return ret;
    }

    public boolean setCurrentLimit(byte value) {
        int ret = setCurrentLimitNative(value);
        return (ret==0) ? true : false;
    }

    public byte getCurrentLimit() {
        byte ret = getCurrentLimitNative();
        return ret;
    }

    public boolean enableAlert(boolean enable) {
        int ret = enableAlertNative(enable);
        return (ret==0) ? true : false;
    }

    public boolean enableData(boolean enable) {
        int ret = enableDataNative(enable);
        return (ret==0) ? true : false;
    }

    public void registerCallback(IWipowerManagerCallback callback) {
        mCallbacks.register(callback);
    }

    public void unregisterCallback(IWipowerManagerCallback callback) {
        mCallbacks.unregister(callback);
    }

    private static class WipowerBinder extends IWipower.Stub {
        private WipowerService mService;

         public WipowerBinder(WipowerService svc) {
             Log.e(LOGTAG, ">In Constructor");
             mService = svc;
         }

         public WipowerService getService() {
            if (mService  != null && mService.isAvailable()) {
                return mService;
            }
            return null;
        }

        public boolean startCharging() {
            boolean ret = false;
            if (mService == null) {
                Log.e(LOGTAG, "startCharging:Service not found");
            } else {
                ret = mService.startCharging();
            }
            return ret;
        }

        public boolean stopCharging() {
            boolean ret = false;
            if (mService == null) {
                Log.e(LOGTAG, "stopCharging:Service not found");
            } else {
                ret = mService.stopCharging();
            }
            return ret;
        }

        public int getState() {
            int ret = -1;
            if (mService == null) {
                Log.e(LOGTAG, "getState:Service not found");
            } else {
                ret = mService.getState();
            }

            return ret;
        }

        public boolean setCurrentLimit(byte value) {
            boolean ret = false;
            if (mService == null) {
                Log.e(LOGTAG, "setCurrentLimit:Service not found");
            } else {
                ret = mService.setCurrentLimit(value);
            }

            return ret;
        }

        public byte getCurrentLimit() {
            byte value = (byte)0xff;
            if (mService == null) {
                Log.e(LOGTAG, "getCurrentLimit:Service not found");
            } else {
                value = mService.getCurrentLimit();
            }
            return value;
        }

        public boolean enableAlert(boolean enable) {
            boolean ret = false;
            if (mService == null) {
                Log.e(LOGTAG, "enableAlert:Service not found");
            } else {
                 ret = mService.enableAlert(enable);
            }
            return ret;
        }

        public boolean enableData(boolean enable) {
            boolean ret = false;
            if (mService == null) {
                Log.e(LOGTAG, "enableData:Service not found");
            } else {
                ret = mService.enableData(enable);
            }
            return ret;
        }

        public void registerCallback(IWipowerManagerCallback callback) {
            if (mService == null) {
                Log.e(LOGTAG, "registerCallback:Service not found");
            } else {
                mService.registerCallback(callback);
            }

        }

        public void unregisterCallback(IWipowerManagerCallback callback) {
           if (mService == null) {
                Log.e(LOGTAG, "unregisterCallback:Service not found");
            } else {
                mService.unregisterCallback(callback);
            }
        }

    };

    private WipowerBinder mBinder;
    private RemoteCallbackList<IWipowerManagerCallback> mCallbacks;

    public WipowerService() {
        Log.v(LOGTAG, "WipowerService");

    }

    private boolean isAvailable() {
        return false;
    }

    static private void cleanupService() {
        Log.v(LOGTAG, "cleanupService");
    }

    @Override
    public void onCreate() {
        Log.v(LOGTAG, "onCreate");
        super.onCreate();

        Log.v(LOGTAG, "Calling InitNative");
        initNative();
        mCallbacks = new RemoteCallbackList<IWipowerManagerCallback>();
        mBinder = new WipowerBinder(this);
        Log.v(LOGTAG, "onCreate>>");

    }

    @Override
    public void onDestroy() {
        Log.v(LOGTAG, "onDestroy");
    }

    @Override
    public IBinder onBind(Intent in) {
        Log.v(LOGTAG, "onBind");
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(LOGTAG, "onStart Command called!!");
        //Make this restarable service by
        //Android app manager
        return START_STICKY;
   }


   void stateChangeCallback (int state) {
        Log.e(LOGTAG, "stateChangeCallback: " + state);
        if (mCallbacks !=null) {
           int n = mCallbacks.beginBroadcast();
           Log.v(LOGTAG,"Broadcasting updateAdapterState() to " + n + " receivers.");
           for (int i=0; i <n;i++) {
               try {
                   mCallbacks.getBroadcastItem(i).onWipowerStateChange(state);
               } catch (RemoteException e) {
                    Log.e(LOGTAG, "Unable to call onBluetoothServiceUp() on callback #" + i, e);
               }
           }
           mCallbacks.finishBroadcast();
        }
   }

   void wipowerAlertNotify (int alert) {
        Log.e(LOGTAG, "wipowerAlertNotify: " + alert);

        if (mCallbacks !=null) {
        int n=mCallbacks.beginBroadcast();
        Log.d(LOGTAG,"Broadcasting wipower alert() to " + n + " receivers.");
        for (int i=0; i <n;i++) {
               try {
                    mCallbacks.getBroadcastItem(i).onWipowerAlert((byte)alert);
               } catch (RemoteException e) {
                    Log.e(LOGTAG, "Unable to call onBluetoothServiceUp() on callback #" + i, e);
               }
           }
           mCallbacks.finishBroadcast();
        }
   }

   void wipowerPowerNotify (byte alert) {
        Log.e(LOGTAG, "wipowerPowerNotify: " + alert);

        if (mCallbacks !=null) {
        int n=mCallbacks.beginBroadcast();
        Log.d(LOGTAG,"Broadcasting wipower power alert() to " + n + " receivers.");
        for (int i=0; i <n;i++) {
               try {
                    mCallbacks.getBroadcastItem(i).onPowerApply((byte)alert);
               } catch (RemoteException e) {
                    Log.e(LOGTAG, "Unable to call onBluetoothServiceUp() on callback #" + i, e);
               }
           }
           mCallbacks.finishBroadcast();
        }
   }


   void wipowerDataNotify (byte[] data) {
        Log.e(LOGTAG, "wipowerDataNotify: " + data);

        if (mCallbacks !=null) {
        int n = mCallbacks.beginBroadcast();
        Log.d(LOGTAG,"Broadcasting wipowerdata() to " + n + " receivers.");
           for (int i=0; i <n;i++) {
               try {
                    mCallbacks.getBroadcastItem(i).onWipowerData(data);
               } catch (RemoteException e) {
                    Log.e(LOGTAG, "Unable to call onBluetoothServiceUp() on callback #" + i, e);
               }

           }
           mCallbacks.finishBroadcast();
        }
   }

   private native static void classInitNative();
   private native void initNative();
   private native int enableNative(boolean enable);
   private native int setCurrentLimitNative(byte value);
   private native byte getCurrentLimitNative();
   private native int getStateNative();
   private native int enableAlertNative(boolean enable);
   private native int enableDataNative(boolean enable);

}
