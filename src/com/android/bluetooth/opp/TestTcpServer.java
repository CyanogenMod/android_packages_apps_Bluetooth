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

import java.io.*;

import javax.obex.HeaderSet;
import javax.obex.Operation;
import javax.obex.ResponseCodes;
import javax.obex.ServerRequestHandler;
import javax.obex.ServerSession;

import android.util.Log;

public class TestTcpServer extends ServerRequestHandler implements Runnable {

    private long connectionID;

    static final int port = 6500;

    private static final String TAG = "ServerRequestHandler";

    public boolean a = false;

    // TextView serverStatus = null;
    public void run() {
        try {
            updateStatus("[server:] listen on port " + port);
            TestTcpSessionNotifier rsn = new TestTcpSessionNotifier(port);

            updateStatus("[server:] Now waiting for a client to connect");
            rsn.acceptAndOpen(this);
            updateStatus("[server:] A client is now connected");
        } catch (Exception ex) {
            updateStatus("[server:] Caught the error: " + ex);
        }
    }

    public void stop() {
    }

    public TestTcpServer() {
        updateStatus("enter construtor of TcpServer");
    }

    public int onConnect(HeaderSet request, HeaderSet reply) {

        updateStatus("[server:] The client has created an OBEX session");
        /* sleep for 2000 ms to wait for the batch contains all ShareInfos */
        synchronized (this) {
            try {
                while (!a) {
                    wait(500);
                }
            } catch (InterruptedException e) {
                if (Constants.LOGVV) {
                    Log.v(TAG, "Interrupted waiting for markBatchFailed");
                }
            }
        }
        updateStatus("[server:] we accpet the seesion");
        return ResponseCodes.OBEX_HTTP_OK;
    }

    public int onPut(Operation op) {
        FileOutputStream fos = null;
        try {
            java.io.InputStream is = op.openInputStream();

            updateStatus("Got data bytes " + is.available() + " name "
                    + op.getReceivedHeader().getHeader(HeaderSet.NAME) + " type " + op.getType());

            File f = new File((String)op.getReceivedHeader().getHeader(HeaderSet.NAME));
            fos = new FileOutputStream(f);
            byte b[] = new byte[1000];
            int len;

            while (is.available() > 0 && (len = is.read(b)) > 0) {
                fos.write(b, 0, len);
            }

            fos.close();
            is.close();
            updateStatus("[server:] Wrote data to " + f.getAbsolutePath());
        } catch (Exception e) {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
            e.printStackTrace();
        }
        return ResponseCodes.OBEX_HTTP_OK;
    }

    public void onDisconnect(HeaderSet req, HeaderSet resp) {

        updateStatus("[server:] The client has disconnected the OBEX session");

    }

    public void updateStatus(String message) {

        // textarea.append("\n" + message);
        // serverStatus.setText(message);
        Log.v(TAG, "\n" + message);

    }

    /*
     * public static void main(String[] args) { new TestTcpServer(); }
     */
    public void onAuthenticationFailure(byte[] userName) {
    }

    public void setConnectionID(long id) {
        if ((id < -1) || (id > 0xFFFFFFFFL)) {
            throw new IllegalArgumentException("Illegal Connection ID");
        }
        connectionID = id;
    }

    public long getConnectionID() {
        return connectionID;
    }

    public int onSetPath(HeaderSet request, HeaderSet reply, boolean backup, boolean create) {

        return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
    }

    public int onDelete(HeaderSet request, HeaderSet reply) {
        return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
    }

    public int onGet(Operation op) {
        return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
    }

}
