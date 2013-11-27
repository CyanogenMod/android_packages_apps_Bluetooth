/*
* Copyright (C) 2013 Samsung System LSI
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
    private static final boolean D = false;
    private static final boolean V = false;

    private ObexTransport mTransport;
    private Context mContext;
    public Handler mHandler = null;
    private volatile boolean mWaitingForRemote;
    private static final String TYPE_EVENT = "x-bt/MAP-event-report";
    private ClientSession mClientSession;
    private boolean mConnected = false;
    BluetoothDevice mRemoteDevice;
    private Handler mCallback = null;
    private BluetoothMapContentObserver mObserver;
    private boolean mObserverRegistered = false;

    // Used by the MAS to forward notification registrations
    public static final int MSG_MNS_NOTIFICATION_REGISTRATION = 1;


    public static final ParcelUuid BluetoothUuid_ObexMns =
            ParcelUuid.fromString("00001133-0000-1000-8000-00805F9B34FB");


    public BluetoothMnsObexClient(Context context, BluetoothDevice remoteDevice,
                                  Handler callback) {
        if (remoteDevice == null) {
            throw new NullPointerException("Obex transport is null");
        }
        HandlerThread thread = new HandlerThread("BluetoothMnsObexClient");
        thread.start();
        Looper looper = thread.getLooper();
        mHandler = new MnsObexClientHandler(looper);
        mContext = context;
        mRemoteDevice = remoteDevice;
        mCallback = callback;
        mObserver = new BluetoothMapContentObserver(mContext);
        mObserver.init();
    }

    public Handler getMessageHandler() {
        return mHandler;
    }

    public BluetoothMapContentObserver getContentObserver() {
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
    }

    /**
     * Shutdown the MNS.
     */
    public void shutdown() {
        /* should shutdown handler thread first to make sure
         * handleRegistration won't be called when disconnet
         */
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

        if(mObserverRegistered) {
            mObserver.unregisterObserver();
            mObserverRegistered = false;
        }
        if (mObserver != null) {
            mObserver.deinit();
            mObserver = null;
        }
    }

    private HeaderSet hsConnect = null;

    public void handleRegistration(int masId, int notificationStatus){
        Log.d(TAG, "handleRegistration( " + masId + ", " + notificationStatus + ")");

        if((isConnected() == false) &&
           (notificationStatus == BluetoothMapAppParams.NOTIFICATION_STATUS_YES)) {
            Log.d(TAG, "handleRegistration: connect");
            connect();
        }

        if(notificationStatus == BluetoothMapAppParams.NOTIFICATION_STATUS_NO) {
            // Unregister - should we disconnect, or keep the connection? - the spec. says nothing about this.
            if(mObserverRegistered == true) {
                mObserver.unregisterObserver();
                mObserverRegistered = false;
                disconnect();
            }
        } else if(notificationStatus == BluetoothMapAppParams.NOTIFICATION_STATUS_YES) {
            /* Connect if we do not have a connection, and start the content observers providing
             * this thread as Handler.
             */
            if(mObserverRegistered == false) {
                mObserver.registerObserver(this, masId);
                mObserverRegistered = true;
            }
        }
    }

    public void connect() {
        Log.d(TAG, "handleRegistration: connect 2");

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
    }

    public int sendEvent(byte[] eventBytes, int masInstanceId) {

        boolean error = false;
        int responseCode = -1;
        HeaderSet request;
        int maxChunkSize, bytesToWrite, bytesWritten = 0;
        ClientSession clientSession = mClientSession;

        if ((!mConnected) || (clientSession == null)) {
            Log.w(TAG, "sendEvent after disconnect:" + mConnected);
            return responseCode;
        }

        notifyUpdateWakeLock();

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
                putOperation = (ClientOperation)clientSession.put(request);
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
                } else {
                    error = true;
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
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error when closing stream after send " + e.getMessage());
            }
            try {
                if ((!error) && (putOperation != null)) {
                    responseCode = putOperation.getResponseCode();
                    if (responseCode != -1) {
                        if (V) Log.v(TAG, "Put response code " + responseCode);
                        if (responseCode != ResponseCodes.OBEX_HTTP_OK) {
                            Log.i(TAG, "Response error code is " + responseCode);
                        }
                    }
                }
                if (putOperation != null) {
                    putOperation.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error when closing stream after send " + e.getMessage());
            }
        }

        return responseCode;
    }

    private void handleSendException(String exception) {
        Log.e(TAG, "Error when sending event: " + exception);
    }

    private void notifyUpdateWakeLock() {
        Message msg = Message.obtain(mCallback);
        msg.what = BluetoothMapService.MSG_ACQUIRE_WAKE_LOCK;
        msg.sendToTarget();
    }
}
