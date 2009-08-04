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

import javax.obex.ClientOperation;
import javax.obex.ClientSession;
import javax.obex.HeaderSet;
import javax.obex.ObexTransport;
import javax.obex.ResponseCodes;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Process;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.Thread;

/**
 * This class runs as an OBEX client
 */
public class BluetoothOppObexClientSession implements BluetoothOppObexSession {

    private static final String TAG = "BtOpp Client";

    private ClientThread mThread;

    private ObexTransport mTransport;

    private Context mContext;

    private volatile boolean mInterrupted;

    private volatile boolean mWaitingForRemote;

    private Handler mCallback;

    public BluetoothOppObexClientSession(Context context, ObexTransport transport) {
        if (transport == null) {
            throw new NullPointerException("transport is null");
        }
        mContext = context;
        mTransport = transport;
    }

    public void start(Handler handler) {
        if (Constants.LOGV) {
            Log.v(TAG, "Start!");
        }
        mCallback = handler;
        mThread = new ClientThread(mContext, mTransport);
        mThread.start();
    }

    public void stop() {
        if (Constants.LOGV) {
            Log.v(TAG, "Stop!");
        }
        if (mThread != null) {
            mInterrupted = true;
            try {
                mThread.interrupt();
                if (Constants.LOGVV) {
                    Log.v(TAG, "waiting for thread to terminate");
                }
                mThread.join();
                mThread = null;
            } catch (InterruptedException e) {
                if (Constants.LOGVV) {
                    Log.v(TAG, "Interrupted waiting for thread to join");
                }
            }
        }
        mCallback = null;
    }

    public void addShare(BluetoothOppShareInfo share) {
        mThread.addShare(share);
    }

    private class ClientThread extends Thread {
        public ClientThread(Context context, ObexTransport transport) {
            super("BtOpp ClientThread");
            mContext1 = context;
            mTransport1 = transport;
            waitingForShare = true;
            mWaitingForRemote = false;

            PowerManager pm = (PowerManager)mContext1.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        }

        private Context mContext1;

        private BluetoothOppShareInfo mInfo;

        private volatile boolean waitingForShare;

        private int mTimeoutRemainingMs = 500;

        private ObexTransport mTransport1;

        private ClientSession mCs;

        private WakeLock wakeLock;

        private BluetoothOppSendFileInfo mFileInfo = null;

        public void addShare(BluetoothOppShareInfo info) {
            mInfo = info;
            mFileInfo = processShareInfo();
            waitingForShare = false;
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            if (Constants.LOGVV) {
                Log.v(TAG, "acquire partial WakeLock");
            }
            wakeLock.acquire();

            try {
                Thread.sleep(100);
            } catch (InterruptedException e1) {
                if (Constants.LOGVV) {
                    Log.v(TAG, "Client thread was interrupted (1), exiting");
                }
                mInterrupted = true;
            }
            if (!mInterrupted) {
                connect();
            }
            while (!mInterrupted) {
                if (!waitingForShare) {
                    doSend();
                } else {
                    try {
                        if (Constants.LOGV) {
                            Log.v(TAG, "Client thread waiting for next share, sleep for "
                                    + mTimeoutRemainingMs);
                        }
                        Thread.sleep(mTimeoutRemainingMs);
                    } catch (InterruptedException e) {

                    }
                }
            }
            disconnect();

            if (Constants.LOGVV) {
                Log.v(TAG, "release partial WakeLock");
            }
            wakeLock.release();

            Message msg = Message.obtain(mCallback);
            msg.what = BluetoothOppObexSession.MSG_SESSION_COMPLETE;
            msg.obj = mInfo;
            msg.sendToTarget();
        }

        private void disconnect() {
            try {
                if (mCs != null) {
                    mCs.disconnect(null);
                }
                mCs = null;
                if (Constants.LOGV) {
                    Log.v(TAG, "OBEX session disconnected");
                }
            } catch (IOException e) {
                Log.e(TAG, "OBEX session disconnect error" + e);
            }
            try {
                if (mCs != null) {
                    if (Constants.LOGV) {
                        Log.v(TAG, "OBEX session close mCs");
                    }
                    mCs.close();
                    if (Constants.LOGV) {
                        Log.v(TAG, "OBEX session closed");
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "OBEX session close error" + e);
            }
            if (mTransport1 != null) {
                try {
                    mTransport1.close();
                } catch (IOException e) {
                    Log.e(TAG, "mTransport.close error");
                }

            }

        }

        private void connect() {
            if (Constants.LOGV) {
                Log.v(TAG, "Create ClientSession with transport " + mTransport1.toString());
            }
            try {
                mCs = new ClientSession(mTransport1);
            } catch (IOException e1) {
                Log.e(TAG, "OBEX session create error");
            }
            HeaderSet hs = new HeaderSet();
            synchronized (this) {
                mWaitingForRemote = true;
            }
            try {
                mCs.connect(hs);
                if (Constants.LOGV) {
                    Log.v(TAG, "OBEX session created");
                }
            } catch (IOException e) {
                Log.e(TAG, "OBEX session connect error");
            }

            synchronized (this) {
                mWaitingForRemote = false;
            }
        }

        private void doSend() {

            int status = BluetoothShare.STATUS_SUCCESS;

            /* connection is established too fast to get first mInfo */
            while (mFileInfo == null) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    status = BluetoothShare.STATUS_CANCELED;
                }
            }
            if (status != BluetoothShare.STATUS_CANCELED) {
                /* do real send */
                if (mFileInfo.mFileName != null) {
                    status = sendFile(mFileInfo);
                } else {
                    /* this is invalid request */
                    status = mFileInfo.mStatus;
                }
            }
            waitingForShare = true;

            if (status == BluetoothShare.STATUS_SUCCESS) {
                Message msg = Message.obtain(mCallback);
                msg.what = BluetoothOppObexSession.MSG_SHARE_COMPLETE;
                msg.obj = mInfo;
                msg.sendToTarget();
            } else {
                Message msg = Message.obtain(mCallback);
                msg.what = BluetoothOppObexSession.MSG_SESSION_ERROR;
                msg.obj = mInfo;
                msg.sendToTarget();
            }
        }

        /*
         * Validate this ShareInfo
         */
        private BluetoothOppSendFileInfo processShareInfo() {
            if (Constants.LOGVV) {
                Log.v(TAG, "Client thread processShareInfo() " + mInfo.mId);
            }

            BluetoothOppSendFileInfo fileInfo = BluetoothOppSendFileInfo.generateFileInfo(
                    mContext1, mInfo.mUri);
            if (fileInfo.mFileName == null) {
                if (Constants.LOGVV) {
                    Log.v(TAG, "BluetoothOppSendFileInfo get null filename");
                    Constants.updateShareStatus(mContext1, mInfo.mId, fileInfo.mStatus);
                }

            } else {
                if (Constants.LOGVV) {
                    Log.v(TAG, "Generate BluetoothOppSendFileInfo:");
                    Log.v(TAG, "filename  :" + fileInfo.mFileName);
                    Log.v(TAG, "length    :" + fileInfo.mLength);
                    Log.v(TAG, "mimetype  :" + fileInfo.mMimetype);
                }

                ContentValues updateValues = new ContentValues();
                Uri contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + mInfo.mId);

                updateValues.put(BluetoothShare.FILENAME_HINT, fileInfo.mFileName);
                updateValues.put(BluetoothShare.TOTAL_BYTES, fileInfo.mLength);
                updateValues.put(BluetoothShare.MIMETYPE, fileInfo.mMimetype);

                mContext1.getContentResolver().update(contentUri, updateValues, null, null);

            }
            return fileInfo;
        }

        private void logHeader(final HeaderSet hs) {
            Log.v(TAG, "Dumping HeaderSet " + hs.toString());
            try {

                Log.v(TAG, "COUNT : " + hs.getHeader(HeaderSet.COUNT));
                Log.v(TAG, "NAME : " + hs.getHeader(HeaderSet.NAME));
                Log.v(TAG, "TYPE : " + hs.getHeader(HeaderSet.TYPE));
                Log.v(TAG, "LENGTH : " + hs.getHeader(HeaderSet.LENGTH));
                Log.v(TAG, "TIME_ISO_8601 : " + hs.getHeader(HeaderSet.TIME_ISO_8601));
                Log.v(TAG, "TIME_4_BYTE : " + hs.getHeader(HeaderSet.TIME_4_BYTE));
                Log.v(TAG, "DESCRIPTION : " + hs.getHeader(HeaderSet.DESCRIPTION));
                Log.v(TAG, "TARGET : " + hs.getHeader(HeaderSet.TARGET));
                Log.v(TAG, "HTTP : " + hs.getHeader(HeaderSet.HTTP));
                Log.v(TAG, "WHO : " + hs.getHeader(HeaderSet.WHO));
                Log.v(TAG, "OBJECT_CLASS : " + hs.getHeader(HeaderSet.OBJECT_CLASS));
                Log.v(TAG, "APPLICATION_PARAMETER : "
                        + hs.getHeader(HeaderSet.APPLICATION_PARAMETER));
            } catch (IOException e) {
                Log.e(TAG, "dump HeaderSet error " + e);
            }

        }

        private int sendFile(BluetoothOppSendFileInfo fileInfo) {
            boolean error = false;
            int responseCode = -1;
            int status = BluetoothShare.STATUS_SUCCESS;
            Uri contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + mInfo.mId);
            ContentValues updateValues;
            HeaderSet request;
            request = new HeaderSet();
            request.setHeader(HeaderSet.NAME, fileInfo.mFileName);
            request.setHeader(HeaderSet.TYPE, fileInfo.mMimetype);

            Constants.updateShareStatus(mContext1, mInfo.mId, BluetoothShare.STATUS_RUNNING);

            request.setHeader(HeaderSet.LENGTH, fileInfo.mLength);
            ClientOperation putOperation = null;
            OutputStream outputStream = null;
            InputStream inputStream = null;
            try {
                synchronized (this) {
                    mWaitingForRemote = true;
                }
                try {
                    if (Constants.LOGVV) {
                        Log.v(TAG, "put headerset for " + fileInfo.mFileName);
                    }
                    putOperation = (ClientOperation)mCs.put(request);
                } catch (IOException e) {
                    status = BluetoothShare.STATUS_OBEX_DATA_ERROR;
                    Constants.updateShareStatus(mContext1, mInfo.mId, status);

                    Log.e(TAG, "Error when put HeaderSet ");
                    error = true;
                }
                synchronized (this) {
                    mWaitingForRemote = false;
                }

                if (!error) {
                    try {
                        if (Constants.LOGVV) {
                            Log.v(TAG, "openOutputStream " + fileInfo.mFileName);
                        }
                        outputStream = putOperation.openOutputStream();
                        inputStream = putOperation.openInputStream();
                    } catch (IOException e) {
                        status = BluetoothShare.STATUS_OBEX_DATA_ERROR;
                        Constants.updateShareStatus(mContext1, mInfo.mId, status);
                        Log.e(TAG, "Error when openOutputStream");
                        error = true;
                    }
                }
                if (!error) {
                    updateValues = new ContentValues();
                    updateValues.put(BluetoothShare.CURRENT_BYTES, 0);
                    updateValues.put(BluetoothShare.STATUS, BluetoothShare.STATUS_RUNNING);
                    mContext1.getContentResolver().update(contentUri, updateValues, null, null);
                }

                if (!error) {
                    int position = 0;
                    int readLength = 0;
                    boolean okToProceed = false;
                    long timestamp = 0;
                    int outputBufferSize = putOperation.getMaxPacketSize();
                    byte[] buffer = new byte[outputBufferSize];
                    BufferedInputStream a = new BufferedInputStream(fileInfo.mInputStream, 0x4000);

                    while (!mInterrupted && (position != fileInfo.mLength)) {

                        if (Constants.LOGVV) {
                            timestamp = System.currentTimeMillis();
                        }

                        readLength = a.read(buffer, 0, outputBufferSize);

                        if (!okToProceed) {
                            mCallback.sendMessageDelayed(mCallback
                                    .obtainMessage(BluetoothOppObexSession.MSG_CONNECT_TIMEOUT),
                                    BluetoothOppObexSession.SESSION_TIMEOUT);
                        }
                        outputStream.write(buffer, 0, readLength);

                        mCallback.removeMessages(BluetoothOppObexSession.MSG_CONNECT_TIMEOUT);
                        position += readLength;

                        if (!okToProceed) {
                            /* check remote accept or reject */
                            if (responseCode == -1 && position == fileInfo.mLength) {
                                // file length is smaller than buffer size, so
                                // only one packet
                                outputStream.close();
                            }
                            responseCode = putOperation.getResponseCode();

                            if (responseCode == ResponseCodes.OBEX_HTTP_CONTINUE
                                    || responseCode == ResponseCodes.OBEX_HTTP_OK) {
                                if (Constants.LOGVV) {
                                    Log.v(TAG, "OK! Response code is " + responseCode);
                                }
                                okToProceed = true;
                            } else {
                                Log.e(TAG, "Error! Response code is " + responseCode);
                                break;
                            }
                        } else {
                            /* check remote abort */
                            responseCode = putOperation.getResponseCode();
                            if (Constants.LOGVV) {
                                Log.v(TAG, "Response code is " + responseCode);
                            }
                            if (responseCode != ResponseCodes.OBEX_HTTP_CONTINUE
                                    && responseCode != ResponseCodes.OBEX_HTTP_OK) {
                                /* abort happens */
                                break;
                            }
                        }

                        if (Constants.LOGVV) {
                            Log.v(TAG, "Sending file position = " + position + " readLength "
                                    + readLength + " bytes took "
                                    + (System.currentTimeMillis() - timestamp) + " ms");
                        }

                        if (Constants.USE_EMULATOR_DEBUG) {
                            synchronized (this) {
                                try {
                                    wait(300);
                                } catch (InterruptedException e) {
                                    error = true;
                                    status = BluetoothShare.STATUS_CANCELED;
                                    // interrupted
                                    if (Constants.LOGVV) {
                                        Log.v(TAG, "SendFile interrupted when send out file "
                                                + fileInfo.mFileName + " at " + position + " of "
                                                + position);
                                    }
                                    Constants.updateShareStatus(mContext1, mInfo.mId, status);
                                }
                            }
                        }

                        updateValues = new ContentValues();
                        updateValues.put(BluetoothShare.CURRENT_BYTES, position);
                        mContext1.getContentResolver().update(contentUri, updateValues, null, null);
                    }

                    if (responseCode == ResponseCodes.OBEX_HTTP_FORBIDDEN) {
                        Log.i(TAG, "Remote reject file " + fileInfo.mFileName + " length "
                                + fileInfo.mLength);
                        status = BluetoothShare.STATUS_FORBIDDEN;
                    } else if (responseCode == ResponseCodes.OBEX_HTTP_UNSUPPORTED_TYPE) {
                        Log.i(TAG, "Remote reject file type " + fileInfo.mMimetype);
                        status = BluetoothShare.STATUS_NOT_ACCEPTABLE;
                    } else if (!mInterrupted && position == fileInfo.mLength) {
                        Log.i(TAG, "SendFile finished send out file " + fileInfo.mFileName
                                + " length " + fileInfo.mLength);
                        outputStream.close();
                    } else {
                        error = true;
                        status = BluetoothShare.STATUS_CANCELED;
                        putOperation.abort();
                        /* interrupted */
                        Log.e(TAG, "SendFile interrupted when send out file "
                                    + fileInfo.mFileName + " at " + position + " of "
                                    + fileInfo.mLength);
                    }
                }
            } catch (IOException e) {
                status = BluetoothShare.STATUS_OBEX_DATA_ERROR;
                Log.e(TAG, "Error when sending file");
                Constants.updateShareStatus(mContext1, mInfo.mId, status);
                mCallback.removeMessages(BluetoothOppObexSession.MSG_CONNECT_TIMEOUT);
            } finally {
                try {
                    fileInfo.mInputStream.close();
                    if (!error) {
                        responseCode = putOperation.getResponseCode();
                        if (responseCode != -1) {
                            if (Constants.LOGVV) {
                                Log.v(TAG, "Get response code " + responseCode);
                            }
                            if (responseCode != ResponseCodes.OBEX_HTTP_OK) {
                                Log.i(TAG, "Response error code is "+ responseCode);
                                status = BluetoothShare.STATUS_UNHANDLED_OBEX_CODE;
                                if (responseCode == ResponseCodes.OBEX_HTTP_UNSUPPORTED_TYPE) {
                                    status = BluetoothShare.STATUS_NOT_ACCEPTABLE;
                                }
                                if (responseCode == ResponseCodes.OBEX_HTTP_FORBIDDEN) {
                                    status = BluetoothShare.STATUS_FORBIDDEN;
                                }
                            }
                        } else {
                            // responseCode is -1, which means connection error
                            status = BluetoothShare.STATUS_CONNECTION_ERROR;
                        }
                    }

                    Constants.updateShareStatus(mContext1, mInfo.mId, status);

                    if (inputStream != null) {
                        inputStream.close();
                    }
                    if (putOperation != null) {
                        putOperation.close();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error when closing stream after send");
                }
            }
            return status;
        }

        @Override
        public void interrupt() {
            super.interrupt();
            synchronized (this) {
                if (mWaitingForRemote) {
                    if (Constants.LOGVV) {
                        Log.v(TAG, "Interrupted when waitingForRemote");
                    }
                    try {
                        mTransport1.close();
                    } catch (IOException e) {
                        Log.e(TAG, "mTransport.close error");
                    }
                    Message msg = Message.obtain(mCallback);
                    msg.what = BluetoothOppObexSession.MSG_SHARE_INTERRUPTED;
                    msg.sendToTarget();
                }
            }
        }
    }

    public void unblock() {

    }

}
