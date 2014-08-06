/*
* Copyright (C) 2014 Samsung System LSI
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

import javax.obex.ServerSession;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

public class BluetoothMapMasInstance {
    private static final String TAG = "BluetoothMapMasInstance";

    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = BluetoothMapService.VERBOSE;

    private static final int SDP_MAP_MSG_TYPE_EMAIL    = 0x01;
    private static final int SDP_MAP_MSG_TYPE_SMS_GSM  = 0x02;
    private static final int SDP_MAP_MSG_TYPE_SMS_CDMA = 0x04;
    private static final int SDP_MAP_MSG_TYPE_MMS      = 0x08;

    private SocketAcceptThread mAcceptThread = null;

    private ServerSession mServerSession = null;

    // The handle to the socket registration with SDP
    private BluetoothServerSocket mServerSocket = null;

    // The actual incoming connection handle
    private BluetoothSocket mConnSocket = null;

    private BluetoothDevice mRemoteDevice = null; // The remote connected device

    private BluetoothAdapter mAdapter;

    private volatile boolean mInterrupted;              // Used to interrupt socket accept thread

    private Handler mServiceHandler = null;             // MAP service message handler
    private BluetoothMapService mMapService = null;     // Handle to the outer MAP service
    private Context mContext = null;                    // MAP service context
    private BluetoothMnsObexClient mMnsClient = null;   // Shared MAP MNS client
    private BluetoothMapEmailSettingsItem mAccount = null; //
    private String mBaseEmailUri = null;                // Email client base URI for this instance
    private int mMasInstanceId = -1;
    private boolean mEnableSmsMms = false;
    BluetoothMapContentObserver mObserver;

    /**
     * Create a e-mail MAS instance
     * @param callback
     * @param context
     * @param mns
     * @param emailBaseUri - use null to create a SMS/MMS MAS instance
     */
    public BluetoothMapMasInstance (BluetoothMapService mapService,
            Context context,
            BluetoothMapEmailSettingsItem account,
            int masId,
            boolean enableSmsMms) {
        mMapService = mapService;
        mServiceHandler = mapService.getHandler();
        mContext = context;
        mAccount = account;
        if(account != null) {
            mBaseEmailUri = account.mBase_uri;
        }
        mMasInstanceId = masId;
        mEnableSmsMms = enableSmsMms;
        init();
    }

    @Override
    public String toString() {
        return "MasId: " + mMasInstanceId + " Uri:" + mBaseEmailUri + " SMS/MMS:" + mEnableSmsMms;
    }

    private void init() {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public int getMasId() {
        return mMasInstanceId;
    }

    /**
     * A thread that runs in the background waiting for remote rfcomm
     * connect. Once a remote socket connected, this thread shall be
     * shutdown. When the remote disconnect, this thread shall run again
     * waiting for next request.
     */
    private class SocketAcceptThread extends Thread {

        private boolean stopped = false;

        @Override
        public void run() {
            BluetoothServerSocket serverSocket;
            if (mServerSocket == null) {
                if (!initSocket()) {
                    return;
                }
            }

            while (!stopped) {
                try {
                    if (D) Log.d(TAG, "Accepting socket connection...");
                    serverSocket = mServerSocket;
                    if(serverSocket == null) {
                        Log.w(TAG, "mServerSocket is null");
                        break;
                    }
                    mConnSocket = serverSocket.accept();
                    if (D) Log.d(TAG, "Accepted socket connection...");

                    synchronized (BluetoothMapMasInstance.this) {
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

                    /* Signal to the service that we have received an incoming connection.
                     */
                    boolean isValid = mMapService.onConnect(mRemoteDevice, BluetoothMapMasInstance.this);

                    if(isValid == false) {
                        // Close connection if we already have a connection with another device
                        Log.i(TAG, "RemoteDevice is invalid - closing.");
                        mConnSocket.close();
                        mConnSocket = null;
                        // now wait for a new connect
                    } else {
                        stopped = true; // job done ,close this thread;
                    }
                } catch (IOException ex) {
                    stopped=true;
                    if (D) Log.v(TAG, "Accept exception: (expected at shutdown)", ex);
                }
            }
        }

        void shutdown() {
            stopped = true;
            if(mServerSocket != null) {
                try {
                    mServerSocket.close();
                } catch (IOException e) {
                    if(D) Log.d(TAG, "Exception while thread shurdown:", e);
                } finally {
                    mServerSocket = null;
                }
            }
            interrupt();
        }
    }

    public void startRfcommSocketListener() {
        if (D) Log.d(TAG, "Map Service startRfcommSocketListener");
        mInterrupted = false; /* For this to work all calls to this function
                                 and shutdown() must be from same thread. */
        if (mAcceptThread == null) {
            mAcceptThread = new SocketAcceptThread();
            mAcceptThread.setName("BluetoothMapAcceptThread masId=" + mMasInstanceId);
            mAcceptThread.start();
        }
    }

    private final boolean initSocket() {
        if (D) Log.d(TAG, "MAS initSocket()");

        boolean initSocketOK = false;
        final int CREATE_RETRY_TIME = 10;

        // It's possible that create will fail in some cases. retry for 10 times
        for (int i = 0; (i < CREATE_RETRY_TIME) && !mInterrupted; i++) {
            initSocketOK = true;
            try {
                // It is mandatory for MSE to support initiation of bonding and
                // encryption.
                String masId = String.format("%02x", mMasInstanceId & 0xff);
                String masName = "";
                int messageTypeFlags = 0;
                if(mEnableSmsMms) {
                    masName = "SMS/MMS";
                    messageTypeFlags |= SDP_MAP_MSG_TYPE_SMS_GSM |
                                   SDP_MAP_MSG_TYPE_SMS_CDMA|
                                   SDP_MAP_MSG_TYPE_MMS;
                }
                if(mBaseEmailUri != null) {
                    if(mEnableSmsMms) {
                        masName += "/EMAIL";
                    } else {
                        masName = mAccount.getName();
                    }
                    messageTypeFlags |= SDP_MAP_MSG_TYPE_EMAIL;
                }
                String msgTypes = String.format("%02x", messageTypeFlags & 0xff);
                String sdpString = masId + msgTypes + masName;
                if(V) Log.d(TAG, "  masId = " + masId +
                                 "\n  msgTypes = " + msgTypes +
                                 "\n  masName = " + masName +
                                 "\n  SDP string = " + sdpString);
                mServerSocket = mAdapter.listenUsingRfcommWithServiceRecord
                    (sdpString, BluetoothUuid.MAS.getUuid());

            } catch (IOException e) {
                Log.e(TAG, "Error create RfcommServerSocket " + e.toString());
                initSocketOK = false;
            }
            if (!initSocketOK) {
                // Need to break out of this loop if BT is being turned off.
                if (mAdapter == null) break;
                int state = mAdapter.getState();
                if ((state != BluetoothAdapter.STATE_TURNING_ON) &&
                    (state != BluetoothAdapter.STATE_ON)) {
                    Log.w(TAG, "initServerSocket failed as BT is (being) turned off");
                    break;
                }
                try {
                    if (V) Log.v(TAG, "waiting 300 ms...");
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Log.e(TAG, "socketAcceptThread thread was interrupted (3)");
                }
            } else {
                break;
            }
        }
        if (mInterrupted) {
            initSocketOK = false;
            // close server socket to avoid resource leakage
            closeServerSocket();
        }

        if (initSocketOK) {
            if (V) Log.v(TAG, "Succeed to create listening socket ");

        } else {
            Log.e(TAG, "Error to create listening socket after " + CREATE_RETRY_TIME + " try");
        }
        return initSocketOK;
    }

    /* Called for all MAS instances for each instance when auth. is completed, hence
     * must check if it has a valid connection before creating a session.
     * Returns true at success. */
    public boolean startObexServerSession(BluetoothMnsObexClient mnsClient) throws IOException, RemoteException {
        if (D) Log.d(TAG, "Map Service startObexServerSession masid = " + mMasInstanceId);

        if (mConnSocket != null) {
            if(mServerSession != null) {
                // Already connected, just return true
                return true;
            }
            mMnsClient = mnsClient;
            BluetoothMapObexServer mapServer;
            mObserver = new  BluetoothMapContentObserver(mContext,
                                                         mMnsClient,
                                                         this,
                                                         mAccount,
                                                         mEnableSmsMms);
            mObserver.init();
            mapServer = new BluetoothMapObexServer(mServiceHandler,
                                                    mContext,
                                                    mObserver,
                                                    mMasInstanceId,
                                                    mAccount,
                                                    mEnableSmsMms);
            // setup RFCOMM transport
            BluetoothMapRfcommTransport transport = new BluetoothMapRfcommTransport(mConnSocket);
            mServerSession = new ServerSession(transport, mapServer, null);
            if (D) Log.d(TAG, "    ServerSession started.");

            return true;
        }
        if (D) Log.d(TAG, "    No connection for this instance");
        return false;
    }

    public boolean handleSmsSendIntent(Context context, Intent intent){
        if(mObserver != null) {
            return mObserver.handleSmsSendIntent(context, intent);
        }
        return false;
    }

    /**
     * Check if this instance is started.
     * @return true if started
     */
    public boolean isStarted() {
        return (mConnSocket != null);
    }

    public void shutdown() {
        if (D) Log.d(TAG, "MAP Service shutdown");

        if (mServerSession != null) {
            mServerSession.close();
            mServerSession = null;
        }
        if (mObserver != null) {
            mObserver.deinit();
            mObserver = null;
        }
        mInterrupted = true;
        if(mAcceptThread != null) {
            mAcceptThread.shutdown();
            try {
                mAcceptThread.join();
            } catch (InterruptedException e) {/* Not much we can do about this*/}
            mAcceptThread = null;
        }

        closeConnectionSocket();
    }

    /**
     * Stop a running server session or cleanup, and start a new
     * RFComm socket listener thread.
     */
    public void restartObexServerSession() {
        if (D) Log.d(TAG, "MAP Service stopObexServerSession");

        shutdown();

        // Last obex transaction is finished, we start to listen for incoming
        // connection again -
        startRfcommSocketListener();
    }


    private final synchronized void closeServerSocket() {
        // exit SocketAcceptThread early
        if (mServerSocket != null) {
            try {
                // this will cause mServerSocket.accept() return early with IOException
                mServerSocket.close();
            } catch (IOException ex) {
                Log.e(TAG, "Close Server Socket error: " + ex);
            } finally {
                mServerSocket = null;
            }
        }
    }

    private final synchronized void closeConnectionSocket() {
        if (mConnSocket != null) {
            try {
                mConnSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Close Connection Socket error: " + e.toString());
            } finally {
                mConnSocket = null;
            }
        }
    }

}
