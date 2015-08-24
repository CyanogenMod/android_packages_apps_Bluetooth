/*
 * Copyright (c) 2015, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *           * Redistributions of source code must retain the above copyright
 *             notice, this list of conditions and the following disclaimer.
 *           * Redistributions in binary form must reproduce the above
 *           * copyright notice, this list of conditions and the following
 *             disclaimer in the documentation and/or other materials provided
 *             with the distribution.
 *           * Neither the name of The Linux Foundation nor the names of its
 *             contributors may be used to endorse or promote products derived
 *             from this software without specific prior written permission.
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

package com.android.bluetooth.avrcp;

import javax.obex.ServerSession;

import java.io.IOException;
import android.os.Message;

import com.android.bluetooth.BluetoothObexTransport;
import com.android.bluetooth.IObexConnectionHandler;
import com.android.bluetooth.ObexServerSockets;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
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

public class AvrcpBipRsp implements IObexConnectionHandler {
    private final String TAG = "AvrcpBipRsp";

    private static final String LOG_TAG = "AvrcpBip";

    private static final boolean D = true;

    public static boolean V = Log.isLoggable(LOG_TAG, Log.VERBOSE);

    private volatile boolean mShutdown = false; // Used to interrupt socket accept thread

    private boolean mIsRegistered = false;

    // The handle to the socket registration with SDP
    private ObexServerSockets mServerSocket = null;

    private ServerSession mServerSession = null;

    // The actual incoming connection handle
    private BluetoothSocket mConnSocket = null;

    // The remote connected device
    private BluetoothDevice mRemoteDevice = null;

    private BluetoothAdapter mAdapter;

    private Context mContext;

    private static final int MSG_INTERNAL_START_LISTENER = 1;

    public static final int MSG_OBEX_CONNECTED = 2;

    public static final int MSG_OBEX_DISCONNECTED = 3;

    public static final int MSG_OBEX_SESSION_CLOSED = 4;

    private static final int AVRCP_BIP_L2CAP_PSM = 0x1021;

    private AvrcpBipRspObexServer mAvrcpBipRspServer;

    private static boolean mObexConnected;

    public AvrcpBipRsp (Context context) {
        mContext = context;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    private final BroadcastReceiver mAvrcpBipRspReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (D) Log.d(TAG, "onReceive");
            String action = intent.getAction();
            if (D) Log.d(TAG, "onReceive: " + action);

            if (action == null)
                return;
            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                                               BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_TURNING_OFF) {
                    if (D) Log.d(TAG, "STATE_TURNING_OFF");
                    mShutdown = true;
                    stop();
                } else if (state == BluetoothAdapter.STATE_ON) {
                    if (D) Log.d(TAG, "STATE_ON");
                    mShutdown = false;
                    // start ServerSocket listener threads
                    mSessionStatusHandler.sendMessage(mSessionStatusHandler
                            .obtainMessage(MSG_INTERNAL_START_LISTENER));
                }

            } else if (action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                if (device == null || mRemoteDevice == null)
                    return;

                if (V) Log.v(TAG,"ACL disconnected for " + device);
                if (V) Log.v(TAG,"mRemoteDevice = " + mRemoteDevice);
                if (device.getAddress().equals(mRemoteDevice.getAddress())) {
                   /* Let the l2cap start listener handle this case as well */
                   if (V) Log.v(TAG,"calling start l2cap listener ");
                   mSessionStatusHandler.sendMessage(mSessionStatusHandler
                           .obtainMessage(MSG_INTERNAL_START_LISTENER));
                }
            }
        }
    };

    private final Handler mSessionStatusHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Log.v(TAG, "Handler(): got msg=" + msg.what);

            switch (msg.what) {
                case MSG_INTERNAL_START_LISTENER:
                    /* fall throught */
                case MSG_OBEX_SESSION_CLOSED:
                    if (mAdapter != null && mAdapter.isEnabled()) {
                        startL2capListener();
                    } else {
                        Log.w(TAG, "Received msg = " + msg.what + " when adapter is" +
                            " disabled, ignoring..");
                    }
                    break;
                case MSG_OBEX_DISCONNECTED:
                    synchronized (this) {
                        mObexConnected = false;
                    }
                    break;
                case MSG_OBEX_CONNECTED:
                    synchronized (this) {
                        mObexConnected = true;
                    }
                    break;
            }
        }
    };

    private synchronized void startL2capListener() {
        if (D) Log.d(TAG, "startL2capListener");

        if (mServerSession != null) {
            if (D) Log.d(TAG, "mServerSession exists - shutting it down...");
            mServerSession.close();
            mServerSession = null;
        }

        closeConnectionSocket();

        if(mServerSocket != null) {
            mServerSocket.prepareForNewConnect();
        } else {
            /* AVRCP 1.6 does not support obex over rfcomm */
            mServerSocket = ObexServerSockets.createWithFixedChannels(this, -1,
                              AVRCP_BIP_L2CAP_PSM);
            if(mServerSocket == null) {
                Log.e(TAG, "Failed to start the listener");
                return;
            }
        }

    }

    private final synchronized void closeServerSocket() {
        if(V) Log.d(TAG, "closeServerSocket");
        if (mServerSocket != null) {
            mServerSocket.shutdown(false);
            mServerSocket = null;
        }
    }

    private final synchronized void closeConnectionSocket() {
        if(V) Log.d(TAG, "closeConnectionSock");
        if (mConnSocket != null) {
            try {
                mConnSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Close Connection Socket error: ", e);
            } finally {
                mConnSocket = null;
                mRemoteDevice = null;
            }
        }
    }

    private final synchronized void startObexServerSession() throws IOException {
        if (V) Log.v(TAG, "startObexServerSession");

        mAvrcpBipRspServer = new AvrcpBipRspObexServer(mContext, mSessionStatusHandler);
        if (V) Log.v(TAG, "startObexServerSession: mAvrcpBipRspServer = " + mAvrcpBipRspServer);
        BluetoothObexTransport transport = new BluetoothObexTransport(mConnSocket);
        mServerSession = new ServerSession(transport, mAvrcpBipRspServer, null);

        if (V) Log.v(TAG, "startObexServerSession() success!");
    }

    public void start() {
        IntentFilter filter = new IntentFilter();
        if(!V)
            V = Log.isLoggable(LOG_TAG, Log.VERBOSE);
        if (V) Log.v(TAG, "Verbose logging enabled");
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        if (!mIsRegistered) {
            try {
                mContext.registerReceiver(mAvrcpBipRspReceiver, filter);
                mIsRegistered = true;
            } catch (Exception e) {
                Log.w(TAG,"Unable to register avrcpbip receiver",e);
            }
        } else {
            Log.w(TAG, "receiver already registered!!");
        }
    }

    public synchronized boolean stop() {
        if (D) Log.d(TAG, "stop()");
        if (mIsRegistered) {
            try {
                mIsRegistered = false;
                mContext.unregisterReceiver(mAvrcpBipRspReceiver);
            } catch (Exception e) {
                Log.w(TAG,"Unable to unregister avrcpbip receiver", e);
            }
        } else {
            if (D) Log.d(TAG, "already stopped, returning");
            return true;
        }
        mShutdown = true;
        if (mServerSession != null) {
            if (D) Log.d(TAG, "mServerSession exists - shutting it down...");
            mServerSession.close();
            mServerSession = null;
        }
        closeConnectionSocket();
        closeServerSocket();

        if (D) Log.d(TAG, "returning from stop()");
        return true;
    }

    @Override
    public synchronized boolean onConnect(BluetoothDevice device, BluetoothSocket socket) {
        /* Signal to the service that we have received an incoming connection. */
        if (device == null) {
            Log.e(TAG, "onConnect received from null device");
            return false;
        }

        if(V) Log.d(TAG, "onConnect received from " + device.getAddress());

        if (mRemoteDevice != null) {
            Log.e(TAG, "onConnect received when already connected to "
                   + mRemoteDevice.getAddress());
            return false;
        }

        mRemoteDevice = device;
        mConnSocket = socket;

        if (mConnSocket != null) {
            try {
                startObexServerSession();
            } catch (IOException e) {
                Log.e(TAG, "onConnect: IOException" + e);
            }
        }
        return true;
    }

    /**
     * Called when an unrecoverable error occurred in an accept thread.
     * Close down the server socket, and restart.
     * TODO: Change to message, to call start in correct context.
     */
    @Override
    public synchronized void onAcceptFailed() {
        mServerSocket = null;
        if (mShutdown) {
            Log.e(TAG,"Failed to accept incoming connection - " + "shutdown");
        } else if (mAdapter != null && mAdapter.isEnabled()) {
            Log.e(TAG,"Failed to accept incoming connection - " + "restarting");
            startL2capListener();
        }
    }

    public synchronized String getImgHandle(String albumName) {

        // If obex is not connected, return null
        if (!mObexConnected)
            return null;

        if (mAvrcpBipRspServer == null)
            return null;

        return mAvrcpBipRspServer.getImgHandle(albumName);
    }
}

