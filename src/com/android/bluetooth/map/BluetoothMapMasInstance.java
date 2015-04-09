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

import com.android.bluetooth.BluetoothObexTransport;
import com.android.bluetooth.IObexConnectionHandler;
import com.android.bluetooth.ObexServerSockets;
import com.android.bluetooth.sdp.SdpManager;

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

public class BluetoothMapMasInstance implements IObexConnectionHandler {
    private static final String TAG = "BluetoothMapMasInstance";

    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = BluetoothMapService.VERBOSE;

    private static final int SDP_MAP_MSG_TYPE_EMAIL    = 0x01;
    private static final int SDP_MAP_MSG_TYPE_SMS_GSM  = 0x02;
    private static final int SDP_MAP_MSG_TYPE_SMS_CDMA = 0x04;
    private static final int SDP_MAP_MSG_TYPE_MMS      = 0x08;

    private static final int SDP_MAP_MAS_VERSION       = 0x0102;

    /* TODO: Should these be adaptive for each MAS? - e.g. read from app? */
    private static final int SDP_MAP_MAS_FEATURES      = 0x0000007F;

    private ServerSession mServerSession = null;

    // The handle to the socket registration with SDP
    private ObexServerSockets mServerSockets = null;
    private int mSdpHandle = -1;

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

    private int mRemoteFeatureMask = BluetoothMapUtils.MAP_FEATURE_DEFAULT_BITMASK;

    public static final String TYPE_SMS_MMS_STR = "SMS/MMS";
    public static final String TYPE_EMAIL_STR = "EMAIL";
    public static final String TYPE_IM_STR = "IM";

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

    public void startRfcommSocketListener() {
        if (D) Log.d(TAG, "Map Service startRfcommSocketListener");

        if (mServerSession != null) {
            if (D) Log.d(TAG, "mServerSession exists - shutting it down...");
            mServerSession.close();
            mServerSession = null;
        }
        if (mObserver != null) {
            if (D) Log.d(TAG, "mObserver exists - shutting it down...");
            mObserver.deinit();
            mObserver = null;
        }

        closeConnectionSocket();

        if(mServerSockets != null) {
            mServerSockets.prepareForNewConnect();
        } else {

            mServerSockets = ObexServerSockets.create(this);

            if(mServerSockets == null) {
                // TODO: Handle - was not handled before
                Log.e(TAG, "Failed to start the listeners");
                return;
            }
            if(mSdpHandle > 0) {
                SdpManager.getDefaultManager().removeSdpRecord(mSdpHandle);
            }
            mSdpHandle = createMasSdpRecord(mServerSockets.getRfcommChannel(),
                    mServerSockets.getL2capPsm());
        }
    }

    /**
     * Create the MAS SDP record with the information stored in the instance.
     * @param rfcommChannel the rfcomm channel ID
     * @param l2capPsm the l2capPsm - set to -1 to exclude
     */
    private int createMasSdpRecord(int rfcommChannel, int l2capPsm) {
        String masName = "";
        int messageTypeFlags = 0;
        if(mEnableSmsMms) {
            masName = TYPE_SMS_MMS_STR;
            messageTypeFlags |= SDP_MAP_MSG_TYPE_SMS_GSM |
//                           SDP_MAP_MSG_TYPE_SMS_CDMA|
                           SDP_MAP_MSG_TYPE_MMS;
        }

        if(mBaseEmailUri != null) {
            if(mEnableSmsMms) {
                masName += "/" + TYPE_EMAIL_STR;
            } else {
                masName = mAccount.getName();
            }
            messageTypeFlags |= SDP_MAP_MSG_TYPE_EMAIL;
        }

        return SdpManager.getDefaultManager().createMapMasRecord(masName,
                mMasInstanceId,
                rfcommChannel,
                l2capPsm,
                SDP_MAP_MAS_VERSION,
                messageTypeFlags,
                SDP_MAP_MAS_FEATURES);
    }

    /* Called for all MAS instances for each instance when auth. is completed, hence
     * must check if it has a valid connection before creating a session.
     * Returns true at success. */
    public boolean startObexServerSession(BluetoothMnsObexClient mnsClient)
            throws IOException, RemoteException {
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

            // setup transport
            BluetoothObexTransport transport = new BluetoothObexTransport(mConnSocket);
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

        closeConnectionSocket();

        closeServerSockets(true);
    }

    /**
     * Signal to the ServerSockets handler that a new connection may be accepted.
     */
    public void restartObexServerSession() {
        if (D) Log.d(TAG, "MAP Service restartObexServerSession()");
        startRfcommSocketListener();
    }


    private final synchronized void closeServerSockets(boolean block) {
        // exit SocketAcceptThread early
        ObexServerSockets sockets = mServerSockets;
        if (sockets != null) {
            sockets.shutdown(block);
            mServerSockets = null;
        }
    }

    private final synchronized void closeConnectionSocket() {
        if (mConnSocket != null) {
            try {
                mConnSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Close Connection Socket error: ", e);
            } finally {
                mConnSocket = null;
            }
        }
    }

    public void setRemoteFeatureMask(int supported_features) {
        mRemoteFeatureMask  = supported_features;
    }

    public int getRemoteFeatureMask(){
        return this.mRemoteFeatureMask;
    }

    @Override
    public synchronized boolean onConnect(BluetoothDevice device, BluetoothSocket socket) {
        /* Signal to the service that we have received an incoming connection.
         */
        boolean isValid = mMapService.onConnect(device, BluetoothMapMasInstance.this);

        if(isValid == true) {
            mRemoteDevice = device;
            mConnSocket = socket;
        }

        return isValid;
    }

    /**
     * Called when an unrecoverable error occurred in an accept thread.
     * Close down the server socket, and restart.
     * TODO: Change to message, to call start in correct context.
     */
    @Override
    public synchronized void onAcceptFailed() {
        mServerSockets = null; // Will cause a new to be created when calling start.
        Log.e(TAG,"Failed to accept incomming connection - restarting");
        startRfcommSocketListener();

    }

}
