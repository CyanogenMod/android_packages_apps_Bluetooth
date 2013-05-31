/*
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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothUuid;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.text.format.Time;
import android.util.Log;
import android.util.Pair;

import com.android.bluetooth.map.IBluetoothMasApp.MessageNotificationListener;
import com.android.bluetooth.map.IBluetoothMasApp.MnsRegister;
import com.android.bluetooth.map.MapUtils.MapUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.obex.ObexTransport;

import static com.android.bluetooth.map.BluetoothMasService.MAS_INS_INFO;
import static com.android.bluetooth.map.BluetoothMasService.MAX_INSTANCES;
import static com.android.bluetooth.map.IBluetoothMasApp.HANDLE_OFFSET;
import static com.android.bluetooth.map.IBluetoothMasApp.MSG;
import static com.android.bluetooth.map.IBluetoothMasApp.TELECOM;

/**
 * This class run an MNS session.
 */
public class BluetoothMns implements MessageNotificationListener {
    private static final String TAG = "BtMns";

    private static final boolean V = BluetoothMasService.VERBOSE;

    public static final int RFCOMM_ERROR = 10;

    public static final int RFCOMM_CONNECTED = 11;

    public static final int MNS_CONNECT = 13;

    public static final int MNS_DISCONNECT = 14;

    public static final int MNS_SEND_EVENT = 15;

    public static final int MNS_SEND_EVENT_DONE = 16;

    public static final int MNS_SEND_TIMEOUT = 17;

    public static final int MNS_BLUETOOTH_OFF = 18;

    public static final int MNS_SEND_TIMEOUT_DURATION = 30000; // 30 secs

    private static final short MNS_UUID16 = 0x1133;

    public static final String NEW_MESSAGE = "NewMessage";

    public static final String DELIVERY_SUCCESS = "DeliverySuccess";

    public static final String SENDING_SUCCESS = "SendingSuccess";

    public static final String DELIVERY_FAILURE = "DeliveryFailure";

    public static final String SENDING_FAILURE = "SendingFailure";

    public static final String MEMORY_FULL = "MemoryFull";

    public static final String MEMORY_AVAILABLE = "MemoryAvailable";

    public static final String MESSAGE_DELETED = "MessageDeleted";

    public static final String MESSAGE_SHIFT = "MessageShift";

    private Context mContext;

    private BluetoothAdapter mAdapter;

    private BluetoothMnsObexSession mSession;

    private EventHandler mSessionHandler;

    private List<MnsClient> mMnsClients = new ArrayList<MnsClient>();

    private HashSet<Integer> mWaitingMasId = new HashSet<Integer>();
    private final Queue<Pair<Integer, String>> mEventQueue = new ConcurrentLinkedQueue<Pair<Integer, String>>();
    private boolean mSendingEvent = false;

    public BluetoothMns(Context context) {
        /* check Bluetooth enable status */
        /*
         * normally it's impossible to reach here if BT is disabled. Just check
         * for safety
         */

        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mContext = context;

        for (int i = 0; i < MAX_INSTANCES; i ++) {
            try {
                // TODO: must be updated when Class<? extends MnsClient>'s constructor is changed
                Constructor<? extends MnsClient> constructor;
                constructor = MAS_INS_INFO[i].mMnsClientClass.getConstructor(Context.class,
                        Integer.class);
                mMnsClients.add(constructor.newInstance(mContext, i));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "The " + MAS_INS_INFO[i].mMnsClientClass.getName()
                        + "'s constructor arguments mismatch", e);
            } catch (InstantiationException e) {
                Log.e(TAG, "The " + MAS_INS_INFO[i].mMnsClientClass.getName()
                        + " cannot be instantiated", e);
            } catch (IllegalAccessException e) {
                Log.e(TAG, "The " + MAS_INS_INFO[i].mMnsClientClass.getName()
                        + " cannot be instantiated", e);
            } catch (InvocationTargetException e) {
                Log.e(TAG, "Exception during " + MAS_INS_INFO[i].mMnsClientClass.getName()
                        + "'s constructor invocation", e);
            } catch (SecurityException e) {
                Log.e(TAG, MAS_INS_INFO[i].mMnsClientClass.getName()
                        + "'s constructor is not accessible", e);
            } catch (NoSuchMethodException e) {
                Log.e(TAG, MAS_INS_INFO[i].mMnsClientClass.getName()
                        + " has no matched constructor", e);
            }
        }

        if (!mAdapter.isEnabled()) {
            Log.e(TAG, "Can't send event when Bluetooth is disabled ");
            return;
        }

        mSessionHandler = new EventHandler();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW);
        filter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
        mContext.registerReceiver(mStorageStatusReceiver, filter);
    }

    public Handler getHandler() {
        return mSessionHandler;
    }

    /**
     * Asserting masId
     * @param masId
     * @return true if MnsClient is created for masId; otherwise false.
     */
    private boolean assertMasid(final int masId) {
        final int size = mMnsClients.size();
        if (masId < 0 || masId >= size) {
            Log.e(TAG, "MAS id: " + masId + " is out of maximum number of MAS instances: " + size);
            return false;
        }
        return true;
    }

    private boolean register(final int masId) {
        if (!assertMasid(masId)) {
            Log.e(TAG, "Attempt to register MAS id: " + masId);
            return false;
        }
        final MnsClient client = mMnsClients.get(masId);
        if (!client.isRegistered()) {
            try {
                client.register(BluetoothMns.this);
            } catch (Exception e) {
                Log.e(TAG, "Exception occured while register MNS for MAS id: " + masId, e);
                return false;
            }
        }
        return true;
    }

    private synchronized boolean canDisconnect() {
        for (MnsClient client : mMnsClients) {
            if (client.isRegistered()) {
                return false;
            }
        }
        return true;
    }

    private void deregister(final int masId) {
        if (!assertMasid(masId)) {
            Log.e(TAG, "Attempt to register MAS id: " + masId);
            return;
        }
        final MnsClient client = mMnsClients.get(masId);
        if (client.isRegistered()) {
            client.register(null);
        }
    }

    private void deregisterAll() {
        for (MnsClient client : mMnsClients) {
            if (client.isRegistered()) {
                client.register(null);
            }
        }
    }

    private void mnsCleanupInstances() {
        if (V) Log.v(TAG, "MNS_BT: entered mnsCleanupInstances");
        if(mStorageStatusReceiver != null) {
            mContext.unregisterReceiver(mStorageStatusReceiver);
            mStorageStatusReceiver = null;
        }
        for (MnsClient client : mMnsClients) {
            if (V) Log.v(TAG, "MNS_BT: mnsCleanupInstances: inside for loop");
            if (client.isRegistered()) {
                if (V) Log.v(TAG, "MNS_BT: mnsCleanupInstances: Attempt to deregister MnsClient");
                client.register(null);
                client = null;
                if (V) Log.v(TAG, "MNS_BT: mnsCleanupInstances: made client = null");
            }
        }
    }

    /*
     * Receives events from mConnectThread & mSession back in the main thread.
     */
    private class EventHandler extends Handler {
        public EventHandler() {
            super();
        }

        @Override
        public void handleMessage(Message msg) {
            if (V){
                Log.v(TAG, " Handle Message " + msg.what);
            }
            switch (msg.what) {
                case MNS_CONNECT:
                {
                    final int masId = msg.arg1;
                    final BluetoothDevice device = (BluetoothDevice)msg.obj;
                    if (mSession != null) {
                        if (V) Log.v(TAG, "is MNS session connected? " + mSession.isConnected());
                        if (mSession.isConnected()) {
                            if (!register(masId)) {
                                // failed to register, disconnect
                                obtainMessage(MNS_DISCONNECT, masId, -1).sendToTarget();
                            }
                            break;
                        }
                    }
                    if (mWaitingMasId.isEmpty()) {
                        mWaitingMasId.add(masId);
                        mConnectThread = new SocketConnectThread(device);
                        mConnectThread.start();
                    } else {
                        mWaitingMasId.add(masId);
                    }
                    break;
                }
                case MNS_DISCONNECT:
                {
                    final int masId = msg.arg1;
                    new Thread(new Runnable() {
                        public void run() {
                            deregister(masId);
                            if (canDisconnect()) {
                                stop();
                            }
                        }
                    }).start();
                    break;
                }
                case MNS_BLUETOOTH_OFF:
                    if (V) Log.v(TAG, "MNS_BT: receive MNS_BLUETOOTH_OFF msg");
                    new Thread(new Runnable() {
                        public void run() {
                            if (V) Log.v(TAG, "MNS_BT: Started Deregister Thread");
                            if (canDisconnect()) {
                                stop();
                            }
                            mnsCleanupInstances();
                        }
                    }).start();
                    break;
                /*
                 * RFCOMM connect fail is for outbound share only! Mark batch
                 * failed, and all shares in batch failed
                 */
                case RFCOMM_ERROR:
                    if (V) Log.v(TAG, "receive RFCOMM_ERROR msg");
                    deregisterAll();
                    if (canDisconnect()) {
                        stop();
                    }
                    break;
                /*
                 * RFCOMM connected. Do an OBEX connect by starting the session
                 */
                case RFCOMM_CONNECTED:
                {
                    if (V) Log.v(TAG, "Transfer receive RFCOMM_CONNECTED msg");
                    ObexTransport transport = (ObexTransport) msg.obj;
                    try {
                        startObexSession(transport);
                    } catch (NullPointerException ne) {
                        sendEmptyMessage(RFCOMM_ERROR);
                        return;
                    }
                    for (int masId : mWaitingMasId) {
                        register(masId);
                    }
                    mWaitingMasId.clear();
                    break;
                }
                /* Handle the error state of an Obex session */
                case BluetoothMnsObexSession.MSG_SESSION_ERROR:
                    if (V) Log.v(TAG, "receive MSG_SESSION_ERROR");
                    deregisterAll();
                    stop();
                    break;
                case MNS_SEND_EVENT:
                {
                    final String xml = (String)msg.obj;
                    final int masId = msg.arg1;
                    if (mSendingEvent) {
                        mEventQueue.add(new Pair<Integer, String>(masId, xml));
                    } else {
                        mSendingEvent = true;
                        new Thread(new SendEventTask(xml, masId)).start();
                    }
                    break;
                }
                case MNS_SEND_EVENT_DONE:
                    if (mEventQueue.isEmpty()) {
                        mSendingEvent = false;
                    } else {
                        final Pair<Integer, String> p = mEventQueue.remove();
                        final int masId = p.first;
                        final String xml = p.second;
                        new Thread(new SendEventTask(xml, masId)).start();
                    }
                    break;
                case MNS_SEND_TIMEOUT:
                {
                    if (V) Log.v(TAG, "MNS_SEND_TIMEOUT disconnecting.");
                    deregisterAll();
                    stop();
                    break;
                }
            }
        }

        private void setTimeout(int masId) {
            if (V) Log.v(TAG, "setTimeout MNS_SEND_TIMEOUT for instance " + masId);
            sendMessageDelayed(obtainMessage(MNS_SEND_TIMEOUT, masId, -1),
                    MNS_SEND_TIMEOUT_DURATION);
        }

        private void removeTimeout() {
            if (hasMessages(MNS_SEND_TIMEOUT)) {
                removeMessages(MNS_SEND_TIMEOUT);
                sendEventDone();
            }
        }

        private void sendEventDone() {
            if (V) Log.v(TAG, "post MNS_SEND_EVENT_DONE");
            obtainMessage(MNS_SEND_EVENT_DONE).sendToTarget();
        }

        class SendEventTask implements Runnable {
            final String mXml;
            final int mMasId;
            SendEventTask (String xml, int masId) {
                mXml = xml;
                mMasId = masId;
            }

            public void run() {
                if (V) Log.v(TAG, "MNS_SEND_EVENT started");
                setTimeout(mMasId);
                sendEvent(mXml, mMasId);
                removeTimeout();
                if (V) Log.v(TAG, "MNS_SEND_EVENT finished");
            }
        }
    }

    /*
     * Class to hold message handle for MCE Initiated operation
     */
    public class BluetoothMnsMsgHndlMceInitOp {
        public String msgHandle;
        Time time;
    }

    /*
     * Keep track of Message Handles on which the operation was
     * initiated by MCE
     */
    List<BluetoothMnsMsgHndlMceInitOp> opList = new ArrayList<BluetoothMnsMsgHndlMceInitOp>();

    /*
     * Adds the Message Handle to the list for tracking
     * MCE initiated operation
     */
    public void addMceInitiatedOperation(String msgHandle) {
        BluetoothMnsMsgHndlMceInitOp op = new BluetoothMnsMsgHndlMceInitOp();
        op.msgHandle = msgHandle;
        op.time = new Time();
        op.time.setToNow();
        opList.add(op);
    }
    /*
     * Removes the Message Handle from the list for tracking
     * MCE initiated operation
     */
    public void removeMceInitiatedOperation(int location) {
        opList.remove(location);
    }

    /*
     * Finds the location in the list of the given msgHandle, if
     * available. "+" indicates the next (any) operation
     */
    public int findLocationMceInitiatedOperation( String msgHandle) {
        int location = -1;

        Time currentTime = new Time();
        currentTime.setToNow();

        List<BluetoothMnsMsgHndlMceInitOp> staleOpList = new ArrayList<BluetoothMnsMsgHndlMceInitOp>();
        for (BluetoothMnsMsgHndlMceInitOp op: opList) {
            if (currentTime.toMillis(false) - op.time.toMillis(false) > 10000) {
                // add stale entries
                staleOpList.add(op);
            }
        }
        if (!staleOpList.isEmpty()) {
            for (BluetoothMnsMsgHndlMceInitOp op: staleOpList) {
                // Remove stale entries
                opList.remove(op);
            }
        }

        for (BluetoothMnsMsgHndlMceInitOp op: opList) {
            if (op.msgHandle.equalsIgnoreCase(msgHandle)){
                location = opList.indexOf(op);
                break;
            }
        }

        if (location == -1) {
            for (BluetoothMnsMsgHndlMceInitOp op: opList) {
                if (op.msgHandle.equalsIgnoreCase("+")) {
                    location = opList.indexOf(op);
                    break;
                }
            }
        }
        return location;
    }


    /**
     * Post a MNS Event to the MNS thread
     */
    public void sendMnsEvent(int masId, String msg, String handle, String folder,
            String old_folder, String msgType) {
        if (V) {
            Log.v(TAG, "sendMnsEvent()");
            Log.v(TAG, "msg: " + msg);
            Log.v(TAG, "handle: " + handle);
            Log.v(TAG, "folder: " + folder);
            Log.v(TAG, "old_folder: " + old_folder);
            Log.v(TAG, "msgType: " + msgType);
        }
        int location = -1;

        /* Send the notification, only if it was not initiated
         * by MCE. MEMORY_FULL and MEMORY_AVAILABLE cannot be
         * MCE initiated
         */
        if (msg.equals(MEMORY_AVAILABLE) || msg.equals(MEMORY_FULL)) {
            location = -1;
        } else {
            location = findLocationMceInitiatedOperation(handle);
        }

        if (location == -1) {
            String str = MapUtils.mapEventReportXML(msg, handle, folder, old_folder, msgType);
            if (V) Log.v(TAG, "Notification to MAS " + masId + ", msgType = " + msgType);
            mSessionHandler.obtainMessage(MNS_SEND_EVENT, masId, -1, str).sendToTarget();
        } else {
            removeMceInitiatedOperation(location);
        }
    }

    /**
     * Push the message over Obex client session
     */
    private void sendEvent(String str, int masId) {
        if (str != null && (str.length() > 0)) {
            if (V){
                Log.v(TAG, "--------------");
                Log.v(TAG, " CONTENT OF EVENT REPORT FILE: " + str);
            }

            final String FILENAME = "EventReport" + masId;
            FileOutputStream fos = null;
            File file = new File(mContext.getFilesDir() + "/" + FILENAME);
            file.delete();
            try {
                fos = mContext.openFileOutput(FILENAME, Context.MODE_PRIVATE);
                fos.write(str.getBytes());
                fos.flush();
                fos.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

            File fileR = new File(mContext.getFilesDir() + "/" + FILENAME);
            if (fileR.exists() == true) {
                if (V) {
                    Log.v(TAG, " Sending event report file for Mas " + masId);
                }
                try {
                    if (mSession != null) {
                        mSession.sendEvent(fileR, (byte) masId);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                if (V){
                    Log.v(TAG, " ERROR IN CREATING SEND EVENT OBJ FILE");
                }
            }
        } else if (V) {
            Log.v(TAG, "sendEvent(null, " + masId + ")");
        }
    }

    private BroadcastReceiver mStorageStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && mSession != null) {
                final String action = intent.getAction();
                if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action)) {
                    Log.d(TAG, " Memory Full ");
                    sendMnsEventMemory(MEMORY_FULL);
                } else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action)) {
                    Log.d(TAG, " Memory Available ");
                    sendMnsEventMemory(MEMORY_AVAILABLE);
                }
            }
        }
    };

    /**
     * Stop the transfer
     */
    public void stop() {
        if (V) Log.v(TAG, "stop");
        if (mSession != null) {
            if (V) Log.v(TAG, "Stop mSession");
            mSession.disconnect();
            mSession = null;
        }
    }

    /**
     * Connect the MNS Obex client to remote server
     */
    private void startObexSession(ObexTransport transport) throws NullPointerException {
        if (V) Log.v(TAG, "Create Client session with transport " + transport.toString());
        mSession = new BluetoothMnsObexSession(mContext, transport);
        mSession.connect();
    }

    private SocketConnectThread mConnectThread;
    /**
     * This thread is used to establish rfcomm connection to
     * remote device
     */
    private class SocketConnectThread extends Thread {
        private final BluetoothDevice device;

        private long timestamp;

        /* create a Rfcomm Socket */
        public SocketConnectThread(BluetoothDevice device) {
            super("Socket Connect Thread");
            this.device = device;
        }

        public void interrupt() {
        }

        @Override
        public void run() {
            timestamp = System.currentTimeMillis();

            BluetoothSocket btSocket = null;
            try {
                btSocket = device.createInsecureRfcommSocketToServiceRecord(
                        BluetoothUuid.MessageNotificationServer.getUuid());
                btSocket.connect();
            } catch (IOException e) {
                Log.e(TAG, "BtSocket Connect error " + e.getMessage(), e);
                markConnectionFailed(btSocket);
                return;
            }

            if (V) Log.v(TAG, "Rfcomm socket connection attempt took "
                    + (System.currentTimeMillis() - timestamp) + " ms");
            ObexTransport transport;
            transport = new BluetoothMnsRfcommTransport(btSocket);
            if (V) Log.v(TAG, "Send transport message " + transport.toString());

            mSessionHandler.obtainMessage(RFCOMM_CONNECTED, transport).sendToTarget();
        }

        /**
         * RFCOMM connection failed
         */
        private void markConnectionFailed(BluetoothSocket s) {
            try {
                if (s != null) {
                    s.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error when close socket");
            }
            mSessionHandler.obtainMessage(RFCOMM_ERROR).sendToTarget();
            return;
        }
    }

    public void sendMnsEventMemory(String msg) {
        // Sending "MemoryFull" or "MemoryAvailable" to all registered Mas Instances
        for (MnsClient client : mMnsClients) {
            if (client.isRegistered()) {
                sendMnsEvent(client.getMasId(), msg, null, null, null, null);
            }
        }
    }

    public void onDeliveryFailure(int masId, String handle, String folder, String msgType) {
        sendMnsEvent(masId, DELIVERY_FAILURE, handle, folder, null, msgType);
    }

    public void onDeliverySuccess(int masId, String handle, String folder, String msgType) {
        sendMnsEvent(masId, DELIVERY_SUCCESS, handle, folder, null, msgType);
    }

    public void onMessageShift(int masId, String handle, String toFolder,
            String fromFolder, String msgType) {
        sendMnsEvent(masId, MESSAGE_SHIFT, handle, toFolder, fromFolder, msgType);
    }

    public void onNewMessage(int masId, String handle, String folder, String msgType) {
        sendMnsEvent(masId, NEW_MESSAGE, handle, folder, null, msgType);
    }

    public void onSendingFailure(int masId, String handle, String folder, String msgType) {
        sendMnsEvent(masId, SENDING_FAILURE, handle, folder, null, msgType);
    }

    public void onSendingSuccess(int masId, String handle, String folder, String msgType) {
        sendMnsEvent(masId, SENDING_SUCCESS, handle, folder, null, msgType);
    }

    public void onMessageDeleted(int masId, String handle, String folder, String msgType) {
        sendMnsEvent(masId, MESSAGE_DELETED, handle, folder, null, msgType);
    }

    public static abstract class MnsClient implements MnsRegister {
        public static final String TAG = "MnsClient";
        public static final boolean V = BluetoothMasService.VERBOSE;
        protected static final String PRE_PATH = TELECOM + "/" + MSG + "/";

        protected Context mContext;
        protected MessageNotificationListener mListener = null;
        protected int mMasId;

        protected final long OFFSET_START;
        protected final long OFFSET_END;

        public MnsClient(Context context, int masId) {
            mContext = context;
            mMasId = masId;
            OFFSET_START = HANDLE_OFFSET[masId];
            OFFSET_END = HANDLE_OFFSET[masId + 1] - 1;
        }

        public synchronized void register(MessageNotificationListener listener) {
            if (V) Log.v(TAG, "MNS_BT: register entered");
            if (listener != null) {
                mListener = listener;
                registerContentObserver();
            } else {
                if (V) Log.v(TAG, "MNS_BT: register(null)");
                unregisterContentObserver();
                mListener = null;
            }
        }

        public boolean isRegistered() {
            return mListener != null;
        }

        public int getMasId() {
            return mMasId;
        }

        protected abstract void registerContentObserver();
        protected abstract void unregisterContentObserver();
    }
}
