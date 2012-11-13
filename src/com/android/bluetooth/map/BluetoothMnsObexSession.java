/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 * Copyright (c) 2010-2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *          notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *          notice, this list of conditions and the following disclaimer in the
 *          documentation and/or other materials provided with the distribution.
 *        * Neither the name of Code Aurora nor
 *          the names of its contributors may be used to endorse or promote
 *          products derived from this software without specific prior written
 *          permission.
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

package com.android.bluetooth.map;

import android.content.Context;
import android.os.Handler;
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
 * This class runs as an OBEX client
 */

// TBD - Do applications need to do anything for power management?

public class BluetoothMnsObexSession {

    private static final String TAG = "BtMns ObexClient";
    private static final boolean D = BluetoothMasService.DEBUG;
    private static final boolean V = BluetoothMasService.VERBOSE;

    public final static int MSG_SESSION_ERROR = 1;
    public final static int MSG_CONNECT_TIMEOUT = 2;

    private ObexTransport mTransport;

    private Context mContext;

    private volatile boolean mWaitingForRemote;

    private static final String TYPE_EVENT = "x-bt/MAP-event-report";

    public BluetoothMnsObexSession(Context context, ObexTransport transport) {
        if (transport == null) {
            throw new NullPointerException("transport is null");
        }
        mContext = context;
        mTransport = transport;
    }

    private ClientSession mCs;

    private boolean mConnected = false;

    public boolean isConnected() {
        return mConnected;
    }

    public void disconnect() {
        try {
            if (mCs != null) {
                mCs.disconnect(null);
            }
            mCs = null;
            if (D) Log.d(TAG, "OBEX session disconnected");
        } catch (IOException e) {
            Log.w(TAG, "OBEX session disconnect error " + e.getMessage());
        }
        try {
            if (mCs != null) {
                if (D) Log.d(TAG, "OBEX session close mCs");
                mCs.close();
                if (D) Log.d(TAG, "OBEX session closed");
            }
        } catch (IOException e) {
            Log.w(TAG, "OBEX session close error" + e.getMessage());
        }
        try {
            if (mTransport != null) {
                mTransport.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "mTransport.close error " + e.getMessage());
        }
    }

    private HeaderSet hsConnect = null;

    public void connect() {
        Log.d(TAG, "Create ClientSession with transport " + mTransport.toString());
        try {
            mCs = new ClientSession(mTransport);
            mConnected = true;
        } catch (IOException e1) {
            Log.e(TAG, "OBEX session create error " + e1.getMessage());
        }
        if (mConnected && mCs != null) {
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
                hsConnect = mCs.connect(hs);
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

    public int sendEvent(File file, byte masInstanceId) {

        boolean error = false;
        int responseCode = -1;
        HeaderSet request;
        byte[] val = new byte[1];
        val[0] =  masInstanceId;
        request = new HeaderSet();
        ApplicationParameter ap = new ApplicationParameter();
        ap.addAPPHeader((byte)BluetoothMasSpecParams.MAS_TAG_MAS_INSTANCE_ID,
                        (byte)BluetoothMasSpecParams.MAS_TAG_MAS_INSTANCE_ID_LEN,
                         val);
        request.setHeader(HeaderSet.TYPE, TYPE_EVENT);
        request.setHeader(HeaderSet.APPLICATION_PARAMETER, ap.getAPPparam());

        request.mConnectionID = new byte[4];
        System.arraycopy(hsConnect.mConnectionID, 0, request.mConnectionID, 0, 4);

        ClientOperation putOperation = null;
        OutputStream outputStream = null;
        FileInputStream fileInputStream = null;
        try {
            synchronized (this) {
                mWaitingForRemote = true;
            }
            // Send the header first and then the body
            try {
                if (V) Log.v(TAG, "Send headerset Event ");
                putOperation = (ClientOperation)mCs.put(request);
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
                int position = 0;
                int readLength = 0;
                long timestamp = 0;
                int outputBufferSize = putOperation.getMaxPacketSize();
                byte[] buffer = new byte[outputBufferSize];

                fileInputStream = new FileInputStream(file);
                BufferedInputStream a = new BufferedInputStream(fileInputStream, 0x4000);

                while ((position != file.length())) {
                    if (V) timestamp = System.currentTimeMillis();

                    readLength = a.read(buffer, 0, outputBufferSize);
                    outputStream.write(buffer, 0, readLength);

                    position += readLength;
                    if (V) {
                        Log.v(TAG, "Sending file position = " + position
                                + " readLength " + readLength + " bytes took "
                                + (System.currentTimeMillis() - timestamp) + " ms");
                    }
                }
                if (position == file.length()) {
                    Log.i(TAG, "SendFile finished send out file " + file.length()
                            + " length " + file.length());
                    outputStream.close();
                } else {
                    error = true;
                    // TBD - Is Output stream close needed here
                    putOperation.abort();
                    Log.i(TAG, "SendFile interrupted when send out file "
                            + " at " + position + " of " + file.length());
                }
            }
        } catch (IOException e) {
            handleSendException(e.toString());
            error = true;
        } catch (IndexOutOfBoundsException e) {
            handleSendException(e.toString());
            error = true;
        } finally {
            if (fileInputStream != null) {
                try {
                    fileInputStream.close();
                } catch (IOException ei) {
                    Log.e(TAG, "Error while closing stream"+ ei.toString());
                }
            }
            try {
                if (!error) {
                    responseCode = putOperation.getResponseCode();
                    if (responseCode != -1) {
                        if (V) Log.v(TAG, "Get response code " + responseCode);
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
}
