/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.opp;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * This class listens on OPUSH channel for incoming connection
 */
public class TestTcpListener {

    private static final String TAG = "BtOppRfcommListener";
    private static final boolean D = Constants.DEBUG;
    private static final boolean V = Constants.VERBOSE;

    private volatile boolean mInterrupted;

    private Thread mSocketAcceptThread;

    private Handler mCallback;

    private static final int ACCEPT_WAIT_TIMEOUT = 5000;

    public static final int DEFAULT_OPP_CHANNEL = 12;

    public static final int MSG_INCOMING_BTOPP_CONNECTION = 100;

    private int mBtOppRfcommChannel = -1;

    public TestTcpListener() {
        this(DEFAULT_OPP_CHANNEL);
    }

    public TestTcpListener(int channel) {
        mBtOppRfcommChannel = channel;
    }

    public synchronized boolean start(Handler callback) {
        if (mSocketAcceptThread == null) {
            mCallback = callback;
            mSocketAcceptThread = new Thread(TAG) {
                ServerSocket mServerSocket;

                public void run() {
                    if (D) Log.d(TAG, "RfcommSocket listen thread starting");
                    try {
                        if (V) Log.v(TAG, "Create server RfcommSocket on channel"
                                    + mBtOppRfcommChannel);
                        mServerSocket = new ServerSocket(6500, 1);
                    } catch (IOException e) {
                        Log.e(TAG, "Error listing on channel" + mBtOppRfcommChannel);
                        mInterrupted = true;
                    }
                    while (!mInterrupted) {
                        try {
                            mServerSocket.setSoTimeout(ACCEPT_WAIT_TIMEOUT);
                            Socket clientSocket = mServerSocket.accept();
                            if (clientSocket == null) {
                                if (V) Log.v(TAG, "incomming connection time out");
                            } else {
                                if (D) Log.d(TAG, "RfcommSocket connected!");
                                Log.d(TAG, "remote addr is "
                                        + clientSocket.getRemoteSocketAddress());
                                TestTcpTransport transport = new TestTcpTransport(clientSocket);
                                Message msg = Message.obtain();
                                msg.setTarget(mCallback);
                                msg.what = MSG_INCOMING_BTOPP_CONNECTION;
                                msg.obj = transport;
                                msg.sendToTarget();
                            }
                        } catch (SocketException e) {
                            Log.e(TAG, "Error accept connection " + e);
                        } catch (IOException e) {
                            Log.e(TAG, "Error accept connection " + e);
                        }

                        if (mInterrupted) {
                            Log.e(TAG, "socketAcceptThread thread was interrupted (2), exiting");
                        }
                    }
                    if (D) Log.d(TAG, "RfcommSocket listen thread finished");
                    }
            };
            mInterrupted = false;
            mSocketAcceptThread.start();

        }
        return true;

    }

    public synchronized void stop() {
        if (mSocketAcceptThread != null) {
            if (D) Log.d(TAG, "stopping Connect Thread");
            mInterrupted = true;
            try {
                mSocketAcceptThread.interrupt();
                if (V) Log.v(TAG, "waiting for thread to terminate");
                mSocketAcceptThread.join();
                mSocketAcceptThread = null;
                mCallback = null;
            } catch (InterruptedException e) {
                if (V) Log.v(TAG, "Interrupted waiting for Accept Thread to join");
            }
        }
    }

}
