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
import android.util.Log;
import javax.obex.*;

public class TestTcpSessionNotifier {
    /* implements SessionNotifier */

    ServerSocket server = null;

    Socket conn = null;

    private static final String TAG = "TestTcpSessionNotifier";

    public TestTcpSessionNotifier(int port) throws IOException {
        server = new ServerSocket(port);
    }

    public ServerSession acceptAndOpen(ServerRequestHandler handler, Authenticator auth)
            throws IOException {
        try {
            conn = server.accept();

        } catch (Exception ex) {
            Log.v(TAG, "ex");
        }

        TestTcpTransport tt = new TestTcpTransport(conn);

        return new ServerSession(tt, handler, auth);

    }

    public ServerSession acceptAndOpen(ServerRequestHandler handler) throws IOException {

        return acceptAndOpen(handler, null);

    }

}
