/*
* Copyright (C) 2013 Samsung System LSI
* Copyright (c) 2013, The Linux Foundation. All rights reserved.
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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.util.Log;
import android.os.PowerManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

import javax.obex.ApplicationParameter;
import javax.obex.ClientOperation;
import javax.obex.ClientSession;
import javax.obex.HeaderSet;
import javax.obex.ObexTransport;
import javax.obex.ResponseCodes;

/**
 * The Message Notification Service class runs its own message handler thread,
 * to avoid executing long operations on the MAP service Thread.
 * This handler context is passed to the content observers,
 * hence all call-backs (and thereby transmission of data) is executed
 * from this thread.
 */
public class BluetoothMnsObexClient {

    private static final String TAG = "BluetoothMnsObexClient";
    private static final boolean D = true;
    private static final boolean V = true;

    private ObexTransport mTransport;
    private Context mContext;
    public Handler mHandler = null;
    private volatile boolean mWaitingForRemote;
    private static final String TYPE_EVENT = "x-bt/MAP-event-report";
    private ClientSession mClientSession;
    private boolean mConnected = false;
    BluetoothDevice mRemoteDevice;
    private BluetoothMapContentObserver mObserver;
    private BluetoothMapContentObserver mEmailObserver;
    private boolean mObserverRegistered = false;
    private boolean mEmailObserverRegistered = false;
    private PowerManager.WakeLock mWakeLock = null;

    // Used by the MAS to forward notification registrations
    public static final int MSG_MNS_NOTIFICATION_REGISTRATION = 1;


    public static final ParcelUuid BluetoothUuid_ObexMns =
            ParcelUuid.fromString("00001133-0000-1000-8000-00805F9B34FB");


    public BluetoothMnsObexClient(Context context, BluetoothDevice remoteDevice) {
        if (remoteDevice == null) {
            throw new NullPointerException("Obex transport is null");
        }
        HandlerThread thread = new HandlerThread("BluetoothMnsObexClient");
        thread.start();
        Looper looper = thread.getLooper();
        mHandler = new MnsObexClientHandler(looper);
        mContext = context;
        mRemoteDevice = remoteDevice;
    }

    public Handler getMessageHandler() {
        return mHandler;
    }

    public BluetoothMapContentObserver getContentObserver(int masInstanceId) {
        if(masInstanceId == 1)
           return mEmailObserver;

        return mObserver;
    }

    private final class MnsObexClientHandler extends Handler {
        private MnsObexClientHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_MNS_NOTIFICATION_REGISTRATION:
                handleRegistration(msg.arg1 /*masId*/, msg.arg2 /*status*/);
                break;
            default:
                break;
            }
        }
    }

    public boolean isConnected() {
        return mConnected;
    }

    /**
     * Disconnect the connection to MNS server.
     * Call this when the MAS client requests a de-registration on events.
     */
    public void disconnect() {
        if(D) Log.d(TAG, "BluetoothMnsObexClient: disconnect");
        try {
            if (mClientSession != null) {
                mClientSession.disconnect(null);
                if (D) Log.d(TAG, "OBEX session disconnected");
            }
        } catch (IOException e) {
            Log.w(TAG, "OBEX session disconnect error " + e.getMessage());
        }
        try {
            if (mClientSession != null) {
                if (D) Log.d(TAG, "OBEX session close mClientSession");
                mClientSession.close();
                mClientSession = null;
                if (D) Log.d(TAG, "OBEX session closed");
            }
        } catch (IOException e) {
            Log.w(TAG, "OBEX session close error:" + e.getMessage());
        }
        if (mTransport != null) {
            try {
                if (D) Log.d(TAG, "Close Obex Transport");
                mTransport.close();
                mTransport = null;
                mConnected = false;
                if (D) Log.d(TAG, "Obex Transport Closed");
            } catch (IOException e) {
                Log.e(TAG, "mTransport.close error: " + e.getMessage());
            }
        }
        if(D) Log.d(TAG, "BluetoothMnsObexClient: exiting from disconnect");
    }

    /**
     * Shutdown the MNS.
     */
    public synchronized void shutdown() {
        /* should shutdown handler thread first to make sure
         * handleRegistration won't be called when disconnet
         */
        if(D) Log.d(TAG, "BluetoothMnsObexClient: shutdown");
        acquireMnsLock();
        if (mHandler != null) {
            // Shut down the thread
            mHandler.removeCallbacksAndMessages(null);
            Looper looper = mHandler.getLooper();
            if (looper != null) {
                looper.quit();
            }
            mHandler = null;
        }

        /* Disconnect if connected */
        disconnect();

        if(mEmailObserverRegistered) {
            mEmailObserver.unregisterObserver();
            mEmailObserverRegistered = false;
        }
        if (mEmailObserver != null) {
            mEmailObserver.deinit();
            mEmailObserver = null;
        }
        if(mObserverRegistered) {
            mObserver.unregisterObserver();
            mObserverRegistered = false;
        }
        if (mObserver != null) {
            mObserver.deinit();
            mObserver = null;
        }
        if (mHandler != null) {
            // Shut down the thread
            mHandler.removeCallbacksAndMessages(null);
            Looper looper = mHandler.getLooper();
            if (looper != null) {
                looper.quit();
            }
            mHandler = null;
        }
        if(D) Log.d(TAG, "BluetoothMnsObexClient: exiting from shutdown");
        releaseMnsLock();
    }

    private HeaderSet hsConnect = null;

    public void handleRegistration(int masId, int notificationStatus){
        Log.d(TAG, "handleRegistration( " + masId + ", " + notificationStatus + ")");

        synchronized (this) {
        if((mEmailObserverRegistered == false) && (mObserverRegistered == false) &&
           (notificationStatus == BluetoothMapAppParams.NOTIFICATION_STATUS_YES)) {
            Log.d(TAG, "handleRegistration: connect");
            connect();
        }

        if(notificationStatus == BluetoothMapAppParams.NOTIFICATION_STATUS_NO) {
              if(masId == 1 && mEmailObserverRegistered ) {
                 mEmailObserver.unregisterObserver();
                 mEmailObserverRegistered = false;
              } else if(mObserverRegistered) {
                 mObserver.unregisterObserver();
                 mObserverRegistered = false;
              }
              if((mEmailObserverRegistered ==false) && (mObserverRegistered == false)) {
                  Log.d(TAG, "handleRegistration: disconnect");
                  disconnect();
              }
        } else if(notificationStatus == BluetoothMapAppParams.NOTIFICATION_STATUS_YES) {
            /* Connect if we do not have a connection, and start the content observers providing
             * this thread as Handler.
             */
               if(masId == 1 && mEmailObserverRegistered == false && mEmailObserver != null) {
                  mEmailObserver.registerObserver(this, masId);
                  mEmailObserverRegistered = true;
                } else if(mObserverRegistered == false && mObserver != null) {
                  mObserver.registerObserver(this, masId);
                  mObserverRegistered = true;
                }
         }
        }
    }

    public void connect() {
        Log.d(TAG, "handleRegistration: connect2");
        acquireMnsLock();

        BluetoothSocket btSocket = null;
        try {
            btSocket = mRemoteDevice.createInsecureRfcommSocketToServiceRecord(
                    BluetoothUuid_ObexMns.getUuid());
            btSocket.connect();
        } catch (IOException e) {
            Log.e(TAG, "BtSocket Connect error " + e.getMessage(), e);
            // TODO: do we need to report error somewhere?
            return;
        }

        mTransport = new BluetoothMnsRfcommTransport(btSocket);

        try {
            mClientSession = new ClientSession(mTransport);
            mConnected = true;
        } catch (IOException e1) {
            Log.e(TAG, "OBEX session create error " + e1.getMessage());
        }
        if (mConnected && mClientSession != null) {
            mConnected = false;
            HeaderSet hs = new HeaderSet();
            // bb582b41-420c-11db-b0de-0800200c9a66
            byte[] mnsTarget = { (byte) 0xbb, (byte) 0x58, (byte) 0x2b, (byte) 0x41,
                                 (byte) 0x42, (byte) 0x0c, (byte) 0x11, (byte) 0xdb,
                                 (byte) 0xb0, (byte) 0xde, (byte) 0x08, (byte) 0x00,
                                 (byte) 0x20, (byte) 0x0c, (byte) 0x9a, (byte) 0x66 };
            hs.setHeader(HeaderSet.TARGET, mnsTarget);

            synchronized (this) {
                mWaitingForRemote = true;
            }
            try {
                hsConnect = mClientSession.connect(hs);
                if (D) Log.d(TAG, "OBEX session created");
                mConnected = true;
            } catch (IOException e) {
                Log.e(TAG, "OBEX session connect error " + e.getMessage());
            }
        }
            synchronized (this) {
                mWaitingForRemote = false;
        }
        Log.d(TAG, "Exiting from connect");
        releaseMnsLock();
    }

    public void  initObserver( Handler callback, int masInstanceId) {
        if(masInstanceId == 1 ){
            mEmailObserver = new BluetoothMapContentEmailObserver(mContext,callback);
            mEmailObserver.init();
        }else {
            mObserver = new BluetoothMapContentObserver(mContext);
            mObserver.init();
        }
    }
    public void  deinitObserver( int masInstanceId) {
        if(masInstanceId == 1 ){
           if(mEmailObserverRegistered) {
              mEmailObserver.unregisterObserver();
              mEmailObserverRegistered = false;
           }
           if (mEmailObserver != null) {
               mEmailObserver.deinit();
               mEmailObserver = null;
           }
        }else {
           if (mObserverRegistered) {
               mObserver.unregisterObserver();
               mObserverRegistered = false;
           }
           if (mObserver != null) {
               mObserver.deinit();
               mObserver = null;
           }
        }
    }

    public int sendEvent(byte[] eventBytes, int masInstanceId) {

        Log.d(TAG, "BluetoothMnsObexClient: sendEvent");
        acquireMnsLock();
        boolean error = false;
        int responseCode = -1;
        HeaderSet request;
        int maxChunkSize, bytesToWrite, bytesWritten = 0;
        request = new HeaderSet();
        BluetoothMapAppParams appParams = new BluetoothMapAppParams();
        appParams.setMasInstanceId(masInstanceId);

        ClientOperation putOperation = null;
        OutputStream outputStream = null;

        try {
            request.setHeader(HeaderSet.TYPE, TYPE_EVENT);
            request.setHeader(HeaderSet.APPLICATION_PARAMETER, appParams.EncodeParams());

            if (hsConnect.mConnectionID != null) {
                request.mConnectionID = new byte[4];
                System.arraycopy(hsConnect.mConnectionID, 0, request.mConnectionID, 0, 4);
            } else {
                Log.w(TAG, "sendEvent: no connection ID");
            }

            synchronized (this) {
                mWaitingForRemote = true;
            }
            // Send the header first and then the body
            try {
                if (V) Log.v(TAG, "Send headerset Event ");
                putOperation = (ClientOperation)mClientSession.put(request);
                // TODO - Should this be kept or Removed

            } catch (IOException e) {
                Log.e(TAG, "Error when put HeaderSet " + e.getMessage());
                error = true;
            }
            synchronized (this) {
                mWaitingForRemote = false;
            }
            if (!error) {
                try {
                    if (V) Log.v(TAG, "Send headerset Event ");
                    outputStream = putOperation.openOutputStream();
                } catch (IOException e) {
                    Log.e(TAG, "Error when opening OutputStream " + e.getMessage());
                    error = true;
                }
            }

            if (!error) {

                maxChunkSize = putOperation.getMaxPacketSize();

                while (bytesWritten < eventBytes.length) {
                    bytesToWrite = Math.min(maxChunkSize, eventBytes.length - bytesWritten);
                    outputStream.write(eventBytes, bytesWritten, bytesToWrite);
                    bytesWritten += bytesToWrite;
                }

                if (bytesWritten == eventBytes.length) {
                    Log.i(TAG, "SendEvent finished send length" + eventBytes.length);
                    if (outputStream != null) {
                        if (V) Log.v(TAG, "Closing outputStream");
                        outputStream.close();
                    }
                } else {
                    error = true;
                    outputStream.close();
                    putOperation.abort();
                    Log.i(TAG, "SendEvent interrupted");
                }
            }
        } catch (IOException e) {
            handleSendException(e.toString());
            error = true;
        } catch (IndexOutOfBoundsException e) {
            handleSendException(e.toString());
            error = true;
        } finally {
            Log.v(TAG, "finally");
            try {
                if (!error) {
                    if (V) Log.v(TAG, "Getting response Code");
                    responseCode = putOperation.getResponseCode();
                    if (V) Log.v(TAG, "response code is" + responseCode);
                    if (responseCode != -1) {
                        if (V) Log.v(TAG, "Put response code " + responseCode);
                        if (responseCode != ResponseCodes.OBEX_HTTP_OK) {
                            Log.i(TAG, "Response error code is " + responseCode);
                        }
                    }
                }
                if (putOperation != null) {
                    if (V) Log.v(TAG, "Closing putOperation");
                    putOperation.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error when closing stream after send " + e.getMessage());
            }
        }
        if(D) Log.d(TAG, "BluetoothMnsObexClient: Exiting sendEvent");
        releaseMnsLock();
        return responseCode;
    }

    private void handleSendException(String exception) {
        Log.e(TAG, "Error when sending event: " + exception);
    }

    private void acquireMnsLock() {
        if (V) Log.v(TAG, "About to acquire Mns:mWakeLock");
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MnsPartialWakeLock");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
            if (V) Log.v(TAG, "Mns:mWakeLock acquired");
        }
        else {
            Log.e(TAG, "Mns:mWakeLock already acquired");
        }
    }

    private void releaseMnsLock() {
        if (V) Log.v(TAG, "About to release Mns:mWakeLock");
        if (mWakeLock != null) {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
                if (V) Log.v(TAG, "Mns:mWakeLock released");
            } else {
                if (V) Log.v(TAG, "Mns:mWakeLock already released");
            }
            mWakeLock = null;
        }
    }
}
