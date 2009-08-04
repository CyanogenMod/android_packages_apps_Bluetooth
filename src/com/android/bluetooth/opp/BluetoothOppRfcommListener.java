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

import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * This class listens on OPUSH channel for incoming connection
 */
public class BluetoothOppRfcommListener {
    private static final String TAG = "BtOpp RfcommListener";

    private volatile boolean mInterrupted;

    private Thread mSocketAcceptThread;

    private Handler mCallback;

    private static final int ACCEPT_WAIT_TIMEOUT = 5000;

    private static final int CREATE_RETRY_TIME = 10;

    public static final int DEFAULT_OPP_CHANNEL = 12;

    public static final int MSG_INCOMING_BTOPP_CONNECTION = 100;

    private int mBtOppRfcommChannel = -1;

    public BluetoothOppRfcommListener() {
        this(DEFAULT_OPP_CHANNEL);
    }

    public BluetoothOppRfcommListener(int channel) {
        mBtOppRfcommChannel = channel;
    }

    public synchronized boolean start(Handler callback) {
        if (mSocketAcceptThread == null) {
            mCallback = callback;
            if (Constants.LOGV) {
                Log.v(TAG, "create mSocketAcceptThread");
            }
            mSocketAcceptThread = new Thread(TAG) {

                public void run() {
                    if (Constants.LOGV) {
                        Log.v(TAG, "BluetoothOppRfcommListener thread starting");
                    }
                    if (Constants.USE_TCP_DEBUG) {
                        ServerSocket mServerSocket = null;
                        try {
                            if (Constants.LOGVV) {
                                Log.v(TAG, "Create ServerSocket on port "
                                        + Constants.TCP_DEBUG_PORT);
                            }

                            mServerSocket = new ServerSocket(Constants.TCP_DEBUG_PORT, 1);

                        } catch (IOException e) {
                            Log.e(TAG, "Error listing on port" + Constants.TCP_DEBUG_PORT);
                            mInterrupted = true;
                        }
                        while (!mInterrupted) {
                            try {
                                mServerSocket.setSoTimeout(ACCEPT_WAIT_TIMEOUT);
                                Socket clientSocket = mServerSocket.accept();

                                if (clientSocket == null) {
                                    if (Constants.LOGVV) {
                                        Log.v(TAG, "incomming connection time out");
                                    }
                                } else {
                                    if (Constants.LOGV) {
                                        Log.v(TAG, "TCP Socket connected!");
                                    }
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
                                if (Constants.LOGVV) {
                                    Log.v(TAG, "Error accept connection " + e);
                                }
                            } catch (IOException e) {
                                if (Constants.LOGVV) {
                                    Log.v(TAG, "Error accept connection " + e);
                                }
                            }
                        }
                        if (Constants.LOGV) {
                            Log.v(TAG, "TCP listen thread finished");
                        }
                        try {
                            mServerSocket.close();
                        } catch (IOException e) {
                            Log.e(TAG, "Error close mServerSocker " + e);
                        }
                    } else {
                        BluetoothServerSocket mServerSocket = null;
                        boolean serverOK = true;

                        /*
                         * it's possible that create will fail in some cases.
                         * retry for 10 times
                         */
                        if (Constants.LOGVV) {
                            Log.v(TAG, "Create BluetoothServerSocket on channel "
                                    + mBtOppRfcommChannel);
                        }
                        for (int i = 0; i < CREATE_RETRY_TIME && !mInterrupted; i++) {
                            try {
                                mServerSocket = BluetoothServerSocket
                                        .listenUsingInsecureRfcommOn(mBtOppRfcommChannel);
                            } catch (IOException e1) {
                                Log.d(TAG, "Error create RfcommServerSocket " + e1);
                                serverOK = false;
                            }
                            if (!serverOK) {
                                synchronized (this) {
                                    try {
                                        if (Constants.LOGVV) {
                                            Log.v(TAG, "wait 3 seconds");
                                        }
                                        Thread.sleep(3000);
                                    } catch (InterruptedException e) {
                                        Log.e(TAG, "socketAcceptThread thread was interrupted (3)");
                                        mInterrupted = true;
                                    }
                                }
                            } else {
                                break;
                            }
                        }
                        if (!serverOK) {
                            Log.e(TAG, "Error start listening after " + CREATE_RETRY_TIME + " try");
                            mInterrupted = true;
                        }

                        BluetoothSocket clientSocket;
                        while (!mInterrupted) {
                            try {
                                clientSocket = mServerSocket.accept(ACCEPT_WAIT_TIMEOUT);
                                if (Constants.LOGVV) {
                                    Log.v(TAG, "BluetoothSocket connected!");
                                    Log.v(TAG, "remote addr is " + clientSocket.getAddress());
                                }
                                BluetoothOppRfcommTransport transport = new BluetoothOppRfcommTransport(
                                        clientSocket);
                                Message msg = Message.obtain();
                                msg.setTarget(mCallback);
                                msg.what = MSG_INCOMING_BTOPP_CONNECTION;
                                msg.obj = transport;
                                msg.sendToTarget();
                            } catch (IOException e) {
                                //TODO later accept should not throw exception
                                if (Constants.LOGVV) {
                                    //Log.v(TAG, "Error accept connection " + e);
                                }
                            }
                        }
                        try {
                            if (mServerSocket != null) {
                                if (Constants.LOGVV) {
                                    Log.v(TAG, "close mServerSocket");
                                }
                                mServerSocket.close();
                                //TODO
                                //mServerSocket.destroy();
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Errro close mServerSocket " + e);
                        }
                        if (Constants.LOGV) {
                            Log.v(TAG, "BluetoothSocket listen thread finished");
                        }
                    }
                }
            };
            mInterrupted = false;
            mSocketAcceptThread.start();
        }
        return true;
    }

    public synchronized void stop() {
        if (mSocketAcceptThread != null) {
            if (Constants.LOGV) {
                Log.v(TAG, "stopping Connect Thread");
            }
            mInterrupted = true;
            try {
                mSocketAcceptThread.interrupt();
                if (Constants.LOGVV) {
                    Log.v(TAG, "waiting for thread to terminate");
                }
                mSocketAcceptThread.join();
                mSocketAcceptThread = null;
                mCallback = null;
            } catch (InterruptedException e) {
                if (Constants.LOGVV) {
                    Log.v(TAG, "Interrupted waiting for Accept Thread to join");
                }
            }
        }
    }
}
