/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import android.bluetooth.BluetoothSocket;
import android.util.Log;
import java.nio.ByteOrder;
import javax.btobex.ObexTransport;
import javax.btobex.ObexHelper;

public class BluetoothOppTransport implements ObexTransport {

    private static final String TAG = "BluetoothOppTransport";
    public static final int TYPE_RFCOMM = 0;
    public static final int TYPE_L2CAP = 1;

    private final BluetoothSocket mSocket;
    private final int mType;

    public BluetoothOppTransport(BluetoothSocket socket, int type) {
        super();
        this.mSocket = socket;
        this.mType = type;
    }

    public int getMaxPacketSize() {
        //return mSocket.getMtu();
        return ObexHelper.MAX_PACKET_SIZE_INT;
    }

    public void close() throws IOException {
        mSocket.close();
    }

    public DataInputStream openDataInputStream() throws IOException {
        return new DataInputStream(openInputStream());
    }

    public DataOutputStream openDataOutputStream() throws IOException {
        return new DataOutputStream(openOutputStream());
    }

    public InputStream openInputStream() throws IOException {
        return mSocket.getInputStream();
    }

    public OutputStream openOutputStream() throws IOException {
        return mSocket.getOutputStream();
    }

    public int setPutSockMTUSize(int size) throws IOException {
       ByteBuffer bb = ByteBuffer.allocate(4);
       int status;
       Log.v(TAG, "Interrupted waiting for size "+ size);
       bb.order(ByteOrder.LITTLE_ENDIAN);
       bb.putInt(0, size);
       try {
            status = mSocket.setSocketOpt(4, bb.array(), 4);
          } catch (IOException ex) {
             return -1;
          }
       return status;
    }

    /**
     * Returns the Congestion status of the Socket
     */
    public int getSockCongStatus() {
        ByteBuffer bb = ByteBuffer.allocate(4);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        int status;
        try {
            status = mSocket.getSocketOpt(5, bb.array());
        } catch (IOException ex) {
            return -1;
        }
        return bb.getInt();
    }

    public void connect() throws IOException {
    }

    public void create() throws IOException {
    }

    public void disconnect() throws IOException {
    }

    public void listen() throws IOException {
    }

    public boolean isConnected() throws IOException {
        //return mSocket.isConnected();
        // TODO: add implementation
        return true;
    }

    public String getRemoteAddress() {
        if (mSocket == null)
            return null;
        return mSocket.getRemoteDevice().getAddress();
    }

    public boolean isAmpCapable() {
        return mType == TYPE_L2CAP;
    }

    public boolean isSrmCapable() {
        return mType == TYPE_L2CAP;
    }

}
