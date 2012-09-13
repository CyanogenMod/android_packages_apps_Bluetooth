/*
 * Copyright (c) 2010-2011, Code Aurora Forum. All rights reserved.
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


import com.android.bluetooth.R;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.CursorJoiner;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import javax.obex.*;


public class BluetoothMasService extends Service {
    private static final String TAG = "BluetoothMasService";

    /**
     * To enable MAP DEBUG/VERBOSE logging - run below cmd in adb shell, and
     * restart com.android.bluetooth process. only enable DEBUG log:
     * "setprop log.tag.BluetoothMapService DEBUG"; enable both VERBOSE and
     * DEBUG log: "setprop log.tag.BluetoothMapService VERBOSE"
     */

    public static final boolean DEBUG = true;

    public static final boolean VERBOSE = true;

    private int mState;

    /**
     * Intent indicating incoming connection request which is sent to
     * BluetoothMasActivity
     */
    public static final String ACCESS_REQUEST_ACTION = "com.android.bluetooth.map.accessrequest";

    /**
     * Intent indicating incoming connection request accepted by user which is
     * sent from BluetoothMasActivity
     */
    public static final String ACCESS_ALLOWED_ACTION = "com.android.bluetooth.map.accessallowed";

    /**
     * Intent indicating incoming connection request denied by user which is
     * sent from BluetoothMasActivity
     */
    public static final String ACCESS_DISALLOWED_ACTION = "com.android.bluetooth.map.accessdisallowed";

    /**
     * Intent indicating incoming obex authentication request which is from
     * PCE(Carkit)
     */
    public static final String AUTH_CHALL_ACTION = "com.android.bluetooth.map.authchall";

    /**
     * Intent indicating obex session key input complete by user which is sent
     * from BluetoothMasActivity
     */
    public static final String AUTH_RESPONSE_ACTION = "com.android.bluetooth.map.authresponse";

    /**
     * Intent indicating user canceled obex authentication session key input
     * which is sent from BluetoothMasActivity
     */
    public static final String AUTH_CANCELLED_ACTION = "com.android.bluetooth.map.authcancelled";

    /**
     * Intent indicating timeout for user confirmation, which is sent to
     * BluetoothMasActivity
     */
    public static final String USER_CONFIRM_TIMEOUT_ACTION = "com.android.bluetooth.map.userconfirmtimeout";

    public static final String THIS_PACKAGE_NAME = "com.android.bluetooth";

    /**
     * Intent Extra name indicating always allowed which is sent from
     * BluetoothMasActivity
     */
    public static final String EXTRA_ALWAYS_ALLOWED = "com.android.bluetooth.map.alwaysallowed";

    /**
     * Intent Extra name indicating session key which is sent from
     * BluetoothMasActivity
     */
    public static final String EXTRA_SESSION_KEY = "com.android.bluetooth.map.sessionkey";

    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;

    public static final int MSG_SERVERSESSION_CLOSE = 5004;

    public static final int MSG_SESSION_ESTABLISHED = 5005;

    public static final int MSG_SESSION_DISCONNECTED = 5006;

    public static final int MSG_OBEX_AUTH_CHALL = 5007;

    private static final int START_LISTENER = 1;

    private static final int USER_TIMEOUT = 2;

    private static final int AUTH_TIMEOUT = 3;

    private static final int PORT_NUM = 16;

    private static final int USER_CONFIRM_TIMEOUT_VALUE = 30000;

    private static final int TIME_TO_WAIT_VALUE = 6000;

    // Ensure not conflict with Opp notification ID
    private static final int NOTIFICATION_ID_ACCESS = -1000005;

    private static final int NOTIFICATION_ID_AUTH = -1000006;

    private PowerManager.WakeLock mWakeLock = null;

    private BluetoothAdapter mAdapter;

    private SocketAcceptThread mAcceptThread = null;

    private BluetoothMapAuthenticator mAuth = null;

    private BluetoothServerSocket mServerSocket = null;

    private BluetoothSocket mConnSocket = null;

    public static BluetoothDevice mRemoteDevice = null;

    private static String sRemoteDeviceName = null;

    private boolean mHasStarted = false;

    private volatile boolean mInterrupted;

    private int mStartId = -1;

    private BluetoothMasObexServer mMapServer = null;

    private ServerSession mServerSession = null;

    public BluetoothMasService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (VERBOSE)
            Log.e(TAG, "Map Service onCreate");
        Log.e(TAG, "Map Service onCreate");

        mAdapter = BluetoothAdapter.getDefaultAdapter();

        if (!mHasStarted) {
            mHasStarted = true;
            if (VERBOSE)
                Log.e(TAG, "Starting MAP service");

            int state = mAdapter.getState();
            if (state == BluetoothAdapter.STATE_ON) {
                mSessionStatusHandler.sendMessageDelayed(mSessionStatusHandler
                        .obtainMessage(START_LISTENER), TIME_TO_WAIT_VALUE);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (VERBOSE)
            Log.e(TAG, "Map Service onStartCommand");
        int retCode = super.onStartCommand(intent, flags, startId);
        if (retCode == START_STICKY) {
            mStartId = startId;
            if (mAdapter == null) {
                Log.w(TAG, "Stopping BluetoothMasService: "
                        + "device does not have BT or device is not ready");
                // Release all resources
                closeService();
            } else {
                // No need to handle the null intent case, because we have
                // all restart work done in onCreate()
                if (intent != null) {
                    parseIntent(intent);
                }
            }
        }
        return retCode;
    }

    // process the intent from receiver
    private void parseIntent(final Intent intent) {
        String action = intent.getStringExtra("action");
        if (VERBOSE)
            Log.e(TAG, "action: " + action);

        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                BluetoothAdapter.ERROR);
        boolean removeTimeoutMsg = true;
        if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
            removeTimeoutMsg = false;
            if (state == BluetoothAdapter.STATE_OFF) {
                // Release all resources
                closeService();
            }
        } else if (action.equals(Intent.ACTION_MEDIA_EJECT)
                || action.equals(Intent.ACTION_MEDIA_MOUNTED)) {
            closeService();
        } else if (action.equals(ACCESS_ALLOWED_ACTION)) {
            if (intent.getBooleanExtra(EXTRA_ALWAYS_ALLOWED, false)) {
                boolean result = true;
                if (VERBOSE)
                    Log.v(TAG, "setTrust() result=" + result);
            }
            try {
                if (mConnSocket != null) {
                    startObexServerSession();
                } else {
                    stopObexServerSession();
                }
            } catch (IOException ex) {
                Log.e(TAG, "Caught the error: " + ex.toString());
            }
        } else if (action.equals(ACCESS_DISALLOWED_ACTION)) {
            stopObexServerSession();
        } else if (action.equals(AUTH_RESPONSE_ACTION)) {
            String sessionkey = intent.getStringExtra(EXTRA_SESSION_KEY);
            notifyAuthKeyInput(sessionkey);
        } else if (action.equals(AUTH_CANCELLED_ACTION)) {
            notifyAuthCancelled();
        } else {
            removeTimeoutMsg = false;
        }

        if (removeTimeoutMsg) {
            mSessionStatusHandler.removeMessages(USER_TIMEOUT);
        }
    }

    @Override
    public void onDestroy() {
        if (VERBOSE)
            Log.e(TAG, "Map Service onDestroy");

        super.onDestroy();
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }
        closeService();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (VERBOSE)
            Log.e(TAG, "Map Service onBind");
        return null;// mBind;
    }

    private void startRfcommSocketListener() {
        if (VERBOSE)
            Log.e(TAG, "Map Service startRfcommSocketListener");

        if (mServerSocket == null) {
            if (!initSocket()) {
                closeService();
                return;
            }
        }
        if (mAcceptThread == null) {
            mAcceptThread = new SocketAcceptThread();
            mAcceptThread.setName("BluetoothMapAcceptThread");
            mAcceptThread.start();
        }
    }

    private final boolean initSocket() {
        if (VERBOSE)
            Log.e(TAG, "Map Service initSocket");

        boolean initSocketOK = true;
        final int CREATE_RETRY_TIME = 10;

        // It's possible that create will fail in some cases. retry for 10 times
        for (int i = 0; i < CREATE_RETRY_TIME && !mInterrupted; i++) {
            try {
                Method m = mAdapter.getClass().getMethod("listenUsingRfcommOn",
                        new Class[] { int.class });
                try {
                    mServerSocket = (BluetoothServerSocket) m.invoke(mAdapter,
                            PORT_NUM);
                } catch (IllegalArgumentException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }

            } catch (SecurityException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (NoSuchMethodException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            if (!initSocketOK) {
                synchronized (this) {
                    try {
                        if (VERBOSE)
                            Log.e(TAG, "wait 3 seconds");
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Log
                                .e(TAG,
                                        "socketAcceptThread thread was interrupted (3)");
                        mInterrupted = true;
                    }
                }
            } else {
                break;
            }
        }

        if (initSocketOK) {
            if (VERBOSE)
                Log.e(TAG, "Succeed to create listening socket on channel "
                        + PORT_NUM);

        } else {
            Log.e(TAG, "Error to create listening socket after "
                    + CREATE_RETRY_TIME + " try");
        }
        return initSocketOK;
    }

    private final void closeSocket(boolean server, boolean accept)
            throws IOException {
        if (server == true) {
            // Stop the possible trying to init serverSocket
            mInterrupted = true;

            if (mServerSocket != null) {
                mServerSocket.close();
            }
        }

        if (accept == true) {
            if (mConnSocket != null) {
                mConnSocket.close();
            }
        }
    }

    private final void closeService() {
        if (VERBOSE)
            Log.e(TAG, "Map Service closeService");

        try {
            closeSocket(true, true);
        } catch (IOException ex) {
            Log.e(TAG, "CloseSocket error: " + ex);
        }

        if (mAcceptThread != null) {
            try {
                mAcceptThread.shutdown();
                mAcceptThread.join();
                mAcceptThread = null;
            } catch (InterruptedException ex) {
                Log.w(TAG, "mAcceptThread close error" + ex);
            }
        }
        mServerSocket = null;
        mConnSocket = null;

        if (mServerSession != null) {
            mServerSession.close();
            mServerSession = null;
        }

        mHasStarted = false;
        if (stopSelfResult(mStartId)) {
            if (VERBOSE)
                Log.e(TAG, "successfully stopped map service");
        }
    }

    private final void startObexServerSession() throws IOException {
        if (VERBOSE)
            Log.v(TAG, "Map Service startObexServerSession");

        // acquire the wakeLock before start Obex transaction thread
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK,
                    "StartingObexMapTransaction");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
        }

        mMapServer = new BluetoothMasObexServer(mSessionStatusHandler,
                mRemoteDevice, this);
        synchronized (this) {
            mAuth = new BluetoothMapAuthenticator(mSessionStatusHandler);
            mAuth.setChallenged(false);
            mAuth.setCancelled(false);
        }
        BluetoothMapRfcommTransport transport = new BluetoothMapRfcommTransport(
                mConnSocket);
        mServerSession = new ServerSession(transport, mMapServer, mAuth);
        if (VERBOSE) {
            Log.e(TAG, "startObexServerSession() success!");
        }
    }

    private void stopObexServerSession() {
        if (VERBOSE)
            Log.e(TAG, "Map Service stopObexServerSession");

        // Release the wake lock if obex transaction is over
        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        if (mServerSession != null) {
            mServerSession.close();
            mServerSession = null;
        }

        mAcceptThread = null;

        try {
            closeSocket(false, true);
            mConnSocket = null;
        } catch (IOException e) {
            Log.e(TAG, "closeSocket error: " + e.toString());
        }
        // Last obex transaction is finished, we start to listen for incoming
        // connection again
        if (mAdapter.isEnabled()) {
            startRfcommSocketListener();
        }
    }

    private void notifyAuthKeyInput(final String key) {
        synchronized (mAuth) {
            if (key != null) {
                mAuth.setSessionKey(key);
            }
            mAuth.setChallenged(true);
            mAuth.notify();
        }
    }

    private void notifyAuthCancelled() {
        synchronized (mAuth) {
            mAuth.setCancelled(true);
            mAuth.notify();
        }
    }

    /**
     * A thread that runs in the background waiting for remote rfcomm
     * connect.Once a remote socket connected, this thread shall be
     * shutdown.When the remote disconnect,this thread shall run again waiting
     * for next request.
     */
    private class SocketAcceptThread extends Thread {

        private boolean stopped = false;

        @Override
        public void run() {
            while (!stopped) {
                try {
                    mConnSocket = mServerSocket.accept();

                    mRemoteDevice = mConnSocket.getRemoteDevice();
                    if (mRemoteDevice == null) {
                        Log.i(TAG, "getRemoteDevice() = null");
                        break;
                    }
                    sRemoteDeviceName = mRemoteDevice.getName();
                    // In case getRemoteName failed and return null
                    if (TextUtils.isEmpty(sRemoteDeviceName)) {
                        sRemoteDeviceName = getString(R.string.defaultname);
                    }
                    boolean trust = true;
                    if (VERBOSE)
                        Log.e(TAG, "GetTrustState() = " + trust);

                    if (trust) {
                        try {
                            if (VERBOSE) Log.e( TAG, "incomming connection accepted from: "
                                + sRemoteDeviceName + " automatically as trusted device");
                            startObexServerSession();
                        } catch (IOException ex) {
                            Log.e(TAG, "catch exception starting obex server session"
                                + ex.toString());
                        }
                    } else {
                        createMapNotification(ACCESS_REQUEST_ACTION);
                        if (VERBOSE) Log.e(TAG, "incomming connection accepted from: "
                            + sRemoteDeviceName);
                        mSessionStatusHandler.sendMessageDelayed(
                            mSessionStatusHandler.obtainMessage(USER_TIMEOUT),
                                USER_CONFIRM_TIMEOUT_VALUE);
                    }
                    stopped = true; // job done ,close this thread;
                } catch (IOException ex) {
                    if (stopped) {
                        break;
                    }
                    if (VERBOSE)
                        Log.e(TAG, "Accept exception: " + ex.toString());
                }
            }
        }

        void shutdown() {
            stopped = true;
            interrupt();
        }
    }

    private final Handler mSessionStatusHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (VERBOSE)
                Log.e(TAG, "Handler(): got msg=" + msg.what);

            switch (msg.what) {
            case START_LISTENER:
                if (mAdapter.isEnabled()) {
                    startRfcommSocketListener();
                } else {
                    closeService();// release all resources
                }
                break;
            case USER_TIMEOUT:
                Intent intent = new Intent(USER_CONFIRM_TIMEOUT_ACTION);
                sendBroadcast(intent);
                removeMapNotification(NOTIFICATION_ID_ACCESS);
                break;
            case AUTH_TIMEOUT:
                Intent i = new Intent(USER_CONFIRM_TIMEOUT_ACTION);
                sendBroadcast(i);
                removeMapNotification(NOTIFICATION_ID_AUTH);
                notifyAuthCancelled();
                break;
            case MSG_SERVERSESSION_CLOSE:
                stopObexServerSession();
                break;
            case MSG_SESSION_ESTABLISHED:
                break;
            case MSG_SESSION_DISCONNECTED:
                // case MSG_SERVERSESSION_CLOSE will handle ,so just skip
                break;
            default:
                break;
            }
        }
    };

    private void createMapNotification(String action) {
    }

    private void removeMapNotification(int id) {
        Context context = getApplicationContext();
        NotificationManager nm = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(id);
    }

    public static String getRemoteDeviceName() {
        return sRemoteDeviceName;
    }
};
