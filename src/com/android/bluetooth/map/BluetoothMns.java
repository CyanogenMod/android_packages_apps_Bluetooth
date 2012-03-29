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



import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.CursorJoiner;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.os.Process;
import android.text.format.Time;
import android.util.Log;

import com.android.bluetooth.map.MapUtils.MapUtils;
import com.android.bluetooth.map.MapUtils.EmailUtils;

import javax.obex.*;

/**
 * This class run an MNS session.
 */
public class BluetoothMns {
    private static final String TAG = "BtMns";

    private static final boolean D = BluetoothMasService.DEBUG;

    private static final boolean V = BluetoothMasService.VERBOSE;

    public static final int RFCOMM_ERROR = 10;

    public static final int RFCOMM_CONNECTED = 11;

    public static final int SDP_RESULT = 12;

    public static final int MNS_CONNECT = 13;

    public static final int MNS_DISCONNECT = 14;

    public static final int MNS_SEND_EVENT = 15;

    private static final int CONNECT_WAIT_TIMEOUT = 45000;

    private static final int CONNECT_RETRY_TIME = 100;

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

    public static final int MMS_HDLR_CONSTANT = 100000;

    public static final int EMAIL_HDLR_CONSTANT = 200000;

    private static final int MSG_CP_INBOX_TYPE = 1;

    private static final int MSG_CP_SENT_TYPE = 2;

    private static final int MSG_CP_DRAFT_TYPE = 3;

    private static final int MSG_CP_OUTBOX_TYPE = 4;

    private static final int MSG_CP_FAILED_TYPE = 5;

    private static final int MSG_CP_QUEUED_TYPE = 6;

    private Context mContext;

    private BluetoothAdapter mAdapter;

    private BluetoothMnsObexSession mSession;

    private int mStartId = -1;

    private ObexTransport mTransport;

    private HandlerThread mHandlerThread;

    private EventHandler mSessionHandler;

    private BluetoothDevice mDestination;

    private MapUtils mu = null;

    public static final ParcelUuid BluetoothUuid_ObexMns = ParcelUuid
            .fromString("00001133-0000-1000-8000-00805F9B34FB");

    private long mTimestamp;

    public String deletedFolderName = null;

    public BluetoothMns(Context context) {
        /* check Bluetooth enable status */
        /*
         * normally it's impossible to reach here if BT is disabled. Just check
         * for safety
         */

        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mContext = context;

        mDestination = BluetoothMasService.mRemoteDevice;

        mu = new MapUtils();

        if (!mAdapter.isEnabled()) {
            Log.e(TAG, "Can't send event when Bluetooth is disabled ");
            return;
        }

        if (mHandlerThread == null) {
            if (V) Log.v(TAG, "Create handler thread for batch ");
            mHandlerThread = new HandlerThread("Bt MNS Transfer Handler",
                    Process.THREAD_PRIORITY_BACKGROUND);
            mHandlerThread.start();
            mSessionHandler = new EventHandler(mHandlerThread.getLooper());
        }
    }

    public Handler getHandler() {
        return mSessionHandler;
    }

    /*
     * Receives events from mConnectThread & mSession back in the main thread.
     */
    private class EventHandler extends Handler {
        public EventHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, " Handle Message " + msg.what);
            switch (msg.what) {
            case MNS_CONNECT:
                if (mSession != null) {
                    Log.d(TAG, "Disconnect previous obex connection");
                    mSession.disconnect();
                    mSession = null;
                }
                start((BluetoothDevice) msg.obj);
                break;
            case MNS_DISCONNECT:
                deregisterUpdates();
                stop();
                break;
            case SDP_RESULT:
                if (V) Log.v(TAG, "SDP request returned " + msg.arg1
                    + " (" + (System.currentTimeMillis() - mTimestamp + " ms)"));
                if (!((BluetoothDevice) msg.obj).equals(mDestination)) {
                    return;
                }
                try {
                    mContext.unregisterReceiver(mReceiver);
                } catch (IllegalArgumentException e) {
                    // ignore
                }
                if (msg.arg1 > 0) {
                    mConnectThread = new SocketConnectThread(mDestination,
                        msg.arg1);
                    mConnectThread.start();
                } else {
                    /* SDP query fail case */
                    Log.e(TAG, "SDP query failed!");
                }

                break;

            /*
             * RFCOMM connect fail is for outbound share only! Mark batch
             * failed, and all shares in batch failed
             */
            case RFCOMM_ERROR:
                if (V) Log.v(TAG, "receive RFCOMM_ERROR msg");
                mConnectThread = null;

                break;
            /*
             * RFCOMM connected. Do an OBEX connect by starting the session
             */
            case RFCOMM_CONNECTED:
                if (V) Log.v(TAG, "Transfer receive RFCOMM_CONNECTED msg");
                mConnectThread = null;
                mTransport = (ObexTransport) msg.obj;
                startObexSession();
                registerUpdates();

                break;

            /* Handle the error state of an Obex session */
            case BluetoothMnsObexSession.MSG_SESSION_ERROR:
                if (V) Log.v(TAG, "receive MSG_SESSION_ERROR");
                deregisterUpdates();
                mSession.disconnect();
                mSession = null;
                break;

            case BluetoothMnsObexSession.MSG_CONNECT_TIMEOUT:
                if (V) Log.v(TAG, "receive MSG_CONNECT_TIMEOUT");
                /*
                 * for outbound transfer, the block point is
                 * BluetoothSocket.write() The only way to unblock is to tear
                 * down lower transport
                 */
                try {
                    if (mTransport == null) {
                        Log.v(TAG,"receive MSG_SHARE_INTERRUPTED but " +
                                "mTransport = null");
                    } else {
                        mTransport.close();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "failed to close mTransport");
                }
                if (V) Log.v(TAG, "mTransport closed ");

                break;

            case MNS_SEND_EVENT:
                sendEvent((String) msg.obj);
                break;
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

        for ( BluetoothMnsMsgHndlMceInitOp op: opList) {
            // Remove stale entries
            if ( currentTime.toMillis(false) - op.time.toMillis(false) > 10000 ) {
                opList.remove(op);
            }
        }

        for ( BluetoothMnsMsgHndlMceInitOp op: opList) {
            if ( op.msgHandle.equalsIgnoreCase(msgHandle)){
                location = opList.indexOf(op);
                break;
            }
        }

        if (location == -1) {
            for ( BluetoothMnsMsgHndlMceInitOp op: opList) {
                if ( op.msgHandle.equalsIgnoreCase("+")) {
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
    public void sendMnsEvent(String msg, String handle, String folder,
            String old_folder, String msgType) {
        int location = -1;

        /* Send the notification, only if it was not initiated
         * by MCE. MEMORY_FULL and MEMORY_AVAILABLE cannot be
         * MCE initiated
         */
        if ( msg.equals(MEMORY_AVAILABLE) || msg.equals(MEMORY_FULL)) {
            location = -1;
        } else {
            location = findLocationMceInitiatedOperation(handle);
        }

        if (location == -1) {
            String str = mu.mapEventReportXML(msg, handle, folder, old_folder,
                    msgType);
            mSessionHandler.obtainMessage(MNS_SEND_EVENT, -1, -1, str)
            .sendToTarget();
        } else {
            removeMceInitiatedOperation(location);
        }
    }

    /**
     * Push the message over Obex client session
     */
    private void sendEvent(String str) {
        if (str != null && (str.length() > 0)) {

            Log.d(TAG, "--------------");
            Log.d(TAG, " CONTENT OF EVENT REPORT FILE: " + str);

            final String FILENAME = "EventReport";
            FileOutputStream fos = null;
            File file = new File(mContext.getFilesDir() + "/" + FILENAME);
            file.delete();
            try {
                fos = mContext.openFileOutput(FILENAME, Context.MODE_PRIVATE);
                fos.write(str.getBytes());
                fos.flush();
                fos.close();
            } catch (FileNotFoundException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            File fileR = new File(mContext.getFilesDir() + "/" + FILENAME);
            if (fileR.exists() == true) {
                Log.d(TAG, " Sending event report file ");
                if (mSession != null) {
                    mSession.sendEvent(fileR, (byte) 0);
                } else {
                    Log.d(TAG, " Unable to send report file: mSession == null");
                }
            } else {
                Log.d(TAG, " ERROR IN CREATING SEND EVENT OBJ FILE");
            }
        }
    }

    private boolean updatesRegistered = false;

    /**
     * Register with content provider to receive updates
     * of change on cursor.
     */
    private void registerUpdates() {

        Log.d(TAG, "REGISTER MNS UPDATES");

        Uri smsUri = Uri.parse("content://sms/");
        crSmsA = mContext.getContentResolver().query(smsUri,
                new String[] { "_id", "body", "type" }, null, null, "_id asc");
        crSmsB = mContext.getContentResolver().query(smsUri,
                new String[] { "_id", "body", "type" }, null, null, "_id asc");

        Uri smsInboxUri = Uri.parse("content://sms/inbox/");
        crSmsInboxA = mContext.getContentResolver().query(smsInboxUri,
                new String[] { "_id", "body", "type" }, null, null, "_id asc");
        crSmsInboxB = mContext.getContentResolver().query(smsInboxUri,
                new String[] { "_id", "body", "type" }, null, null, "_id asc");

        Uri smsSentUri = Uri.parse("content://sms/sent/");
        crSmsSentA = mContext.getContentResolver().query(smsSentUri,
                new String[] { "_id", "body", "type" }, null, null, "_id asc");
        crSmsSentB = mContext.getContentResolver().query(smsSentUri,
                new String[] { "_id", "body", "type" }, null, null, "_id asc");

        Uri smsDraftUri = Uri.parse("content://sms/draft/");
        crSmsDraftA = mContext.getContentResolver().query(smsDraftUri,
                new String[] { "_id", "body", "type" }, null, null, "_id asc");
        crSmsDraftB = mContext.getContentResolver().query(smsDraftUri,
                new String[] { "_id", "body", "type" }, null, null, "_id asc");

        Uri smsOutboxUri = Uri.parse("content://sms/outbox/");
        crSmsOutboxA = mContext.getContentResolver().query(smsOutboxUri,
                new String[] { "_id", "body", "type" }, null, null, "_id asc");
        crSmsOutboxB = mContext.getContentResolver().query(smsOutboxUri,
                new String[] { "_id", "body", "type" }, null, null, "_id asc");

        Uri smsFailedUri = Uri.parse("content://sms/failed/");
        crSmsFailedA = mContext.getContentResolver().query(smsFailedUri,
                new String[] { "_id", "body", "type" }, null, null, "_id asc");
        crSmsFailedB = mContext.getContentResolver().query(smsFailedUri,
                new String[] { "_id", "body", "type" }, null, null, "_id asc");

        Uri smsQueuedUri = Uri.parse("content://sms/queued/");
        crSmsQueuedA = mContext.getContentResolver().query(smsQueuedUri,
                new String[] { "_id", "body", "type" }, null, null, "_id asc");
        crSmsQueuedB = mContext.getContentResolver().query(smsQueuedUri,
                new String[] { "_id", "body", "type" }, null, null, "_id asc");

        Uri smsObserverUri = Uri.parse("content://mms-sms/");
        mContext.getContentResolver().registerContentObserver(smsObserverUri,
                true, smsContentObserver);

        Uri mmsUri = Uri.parse("content://mms/");
        crMmsA = mContext.getContentResolver()
                .query(mmsUri, new String[] { "_id", "read", "m_type" }, null,
                        null, "_id asc");
        crMmsB = mContext.getContentResolver()
                .query(mmsUri, new String[] { "_id", "read", "m_type" }, null,
                        null, "_id asc");

        Uri mmsOutboxUri = Uri.parse("content://mms/outbox/");
        crMmsOutboxA = mContext.getContentResolver()
                .query(mmsOutboxUri, new String[] { "_id", "read", "m_type" },
                        null, null, "_id asc");
        crMmsOutboxB = mContext.getContentResolver()
                .query(mmsOutboxUri, new String[] { "_id", "read", "m_type" },
                        null, null, "_id asc");
        Uri mmsDraftUri = Uri.parse("content://mms/drafts/");
        crMmsDraftA = mContext.getContentResolver()
                .query(mmsDraftUri, new String[] { "_id", "read", "m_type" },
                        null, null, "_id asc");
        crMmsDraftB = mContext.getContentResolver()
                .query(mmsDraftUri, new String[] { "_id", "read", "m_type" },
                        null, null, "_id asc");
        Uri mmsInboxUri = Uri.parse("content://mms/inbox/");
        crMmsInboxA = mContext.getContentResolver()
                .query(mmsInboxUri, new String[] { "_id", "read", "m_type" },
                        null, null, "_id asc");
        crMmsInboxB = mContext.getContentResolver()
                .query(mmsInboxUri, new String[] { "_id", "read", "m_type" },
                        null, null, "_id asc");

        Uri mmsSentUri = Uri.parse("content://mms/sent/");
        crMmsSentA = mContext.getContentResolver()
                .query(mmsSentUri, new String[] { "_id", "read", "m_type" },
                        null, null, "_id asc");
        crMmsSentB = mContext.getContentResolver()
                .query(mmsSentUri, new String[] { "_id", "read", "m_type" },
                        null, null, "_id asc");

        //email start
        Uri emailUri = Uri.parse("content://com.android.email.provider/message");
        crEmailA = mContext.getContentResolver().query(emailUri,
                new String[] { "_id", "mailboxkey" }, null, null, "_id asc");
        crEmailB = mContext.getContentResolver().query(emailUri,
                new String[] { "_id", "mailboxkey" }, null, null, "_id asc");

        EmailUtils eu = new EmailUtils();
        String emailInboxCondition = eu.getWhereIsQueryForTypeEmail("inbox", mContext);
        crEmailInboxA = mContext.getContentResolver().query(emailUri,
                new String[] {  "_id", "mailboxkey"  }, emailInboxCondition, null, "_id asc");
        crEmailInboxB = mContext.getContentResolver().query(emailUri,
                new String[] {  "_id", "mailboxkey" }, emailInboxCondition, null, "_id asc");

        String emailSentCondition = eu.getWhereIsQueryForTypeEmail("sent", mContext);
        crEmailSentA = mContext.getContentResolver().query(emailUri,
                new String[] {"_id", "mailboxkey" }, emailSentCondition, null, "_id asc");
        crEmailSentB = mContext.getContentResolver().query(emailUri,
                new String[] {"_id", "mailboxkey" }, emailSentCondition, null, "_id asc");

        String emailDraftCondition = eu.getWhereIsQueryForTypeEmail("drafts", mContext);
        crEmailDraftA = mContext.getContentResolver().query(emailUri,
                new String[] {"_id", "mailboxkey"}, emailDraftCondition, null, "_id asc");
        crEmailDraftB = mContext.getContentResolver().query(emailUri,
                new String[] {"_id", "mailboxkey" }, emailDraftCondition, null, "_id asc");

        String emailOutboxCondition = eu.getWhereIsQueryForTypeEmail("outbox", mContext);
        crEmailOutboxA = mContext.getContentResolver().query(emailUri,
                new String[] {"_id", "mailboxkey"}, emailOutboxCondition, null, "_id asc");
        crEmailOutboxB = mContext.getContentResolver().query(emailUri,
                new String[] { "_id", "mailboxkey"}, emailOutboxCondition, null, "_id asc");

        Uri emailObserverUri = Uri.parse("content://com.android.email.provider/message");
        mContext.getContentResolver().registerContentObserver(emailObserverUri,
                true, emailContentObserver);

        Uri emailInboxObserverUri = Uri.parse("content://com.android.email.provider/message");
        mContext.getContentResolver().registerContentObserver(
                        emailInboxObserverUri, true, emailInboxContentObserver);

        Uri emailSentObserverUri = Uri.parse("content://com.android.email.provider/message");
        mContext.getContentResolver().registerContentObserver(
                emailSentObserverUri, true, emailSentContentObserver);

        Uri emailDraftObserverUri = Uri.parse("content://com.android.email.provider/message");
        mContext.getContentResolver().registerContentObserver(
                        emailDraftObserverUri, true, emailDraftContentObserver);

        Uri emailOutboxObserverUri = Uri.parse("content://com.android.email.provider/message");
        mContext.getContentResolver().registerContentObserver(
                        emailOutboxObserverUri, true, emailOutboxContentObserver);

        //email end


        Uri smsInboxObserverUri = Uri.parse("content://mms-sms/inbox");
        mContext.getContentResolver().registerContentObserver(
                smsInboxObserverUri, true, inboxContentObserver);

        Uri smsSentObserverUri = Uri.parse("content://mms-sms/sent");
        mContext.getContentResolver().registerContentObserver(
                smsSentObserverUri, true, sentContentObserver);

        Uri smsDraftObserverUri = Uri.parse("content://mms-sms/draft");
        mContext.getContentResolver().registerContentObserver(
                smsDraftObserverUri, true, draftContentObserver);

        Uri smsOutboxObserverUri = Uri.parse("content://mms-sms/outbox");
        mContext.getContentResolver().registerContentObserver(
                smsOutboxObserverUri, true, outboxContentObserver);

        Uri smsFailedObserverUri = Uri.parse("content://mms-sms/failed");
        mContext.getContentResolver().registerContentObserver(
                smsFailedObserverUri, true, failedContentObserver);

        Uri smsQueuedObserverUri = Uri.parse("content://mms-sms/queued");
        mContext.getContentResolver().registerContentObserver(
                smsQueuedObserverUri, true, queuedContentObserver);


        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW);
        filter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
        mContext.registerReceiver(mStorageStatusReceiver, filter);

        updatesRegistered = true;
        Log.d(TAG, " ---------------- ");
        Log.d(TAG, " REGISTERED MNS UPDATES ");
        Log.d(TAG, " ---------------- ");
    }

    /**
     * Stop listening to changes in cursor
     */
    private void deregisterUpdates() {

        if ( updatesRegistered == true ){
                updatesRegistered = false;
            Log.d(TAG, "DEREGISTER MNS SMS UPDATES");
            mContext.getContentResolver().unregisterContentObserver(
                    smsContentObserver);
            mContext.getContentResolver().unregisterContentObserver(
                    inboxContentObserver);
            mContext.getContentResolver().unregisterContentObserver(
                    sentContentObserver);
            mContext.getContentResolver().unregisterContentObserver(
                    draftContentObserver);
            mContext.getContentResolver().unregisterContentObserver(
                    outboxContentObserver);
            mContext.getContentResolver().unregisterContentObserver(
                    failedContentObserver);
            mContext.getContentResolver().unregisterContentObserver(
                    queuedContentObserver);

            //email start
            mContext.getContentResolver().unregisterContentObserver(
                    emailContentObserver);
            //email end

            mContext.unregisterReceiver(mStorageStatusReceiver);

            crSmsA.close();
            crSmsB.close();
            currentCRSms = CR_SMS_A;
            crSmsInboxA.close();
            crSmsInboxB.close();
            currentCRSmsInbox = CR_SMS_INBOX_A;
            crSmsSentA.close();
            crSmsSentB.close();
            currentCRSmsSent = CR_SMS_SENT_A;
            crSmsDraftA.close();
            crSmsDraftB.close();
            currentCRSmsDraft = CR_SMS_DRAFT_A;
            crSmsOutboxA.close();
            crSmsOutboxB.close();
            currentCRSmsOutbox = CR_SMS_OUTBOX_A;
            crSmsFailedA.close();
            crSmsFailedB.close();
            currentCRSmsFailed = CR_SMS_FAILED_A;
            crSmsQueuedA.close();
            crSmsQueuedB.close();
            currentCRSmsQueued = CR_SMS_QUEUED_A;

            crMmsA.close();
            crMmsB.close();
            currentCRMms = CR_MMS_A;
            crMmsOutboxA.close();
            crMmsOutboxB.close();
            currentCRMmsOutbox = CR_MMS_OUTBOX_A;
            crMmsDraftA.close();
            crMmsDraftB.close();
            currentCRMmsDraft = CR_MMS_DRAFT_A;
            crMmsInboxA.close();
            crMmsInboxB.close();
            currentCRMmsInbox = CR_MMS_INBOX_A;
            crMmsSentA.close();
            crMmsSentB.close();
            currentCRMmsSent = CR_MMS_SENT_A;

            //email start
            crEmailA.close();
            crEmailB.close();
            currentCREmail = CR_EMAIL_A;
            crEmailOutboxA.close();
            crEmailOutboxB.close();
            currentCREmailOutbox = CR_EMAIL_OUTBOX_A;
            crEmailDraftA.close();
            crEmailDraftB.close();
            currentCREmailDraft = CR_EMAIL_DRAFT_A;
            crEmailInboxA.close();
            crEmailInboxB.close();
            currentCREmailInbox = CR_EMAIL_INBOX_A;
            crEmailSentA.close();
            crEmailSentB.close();
            currentCREmailSent = CR_EMAIL_SENT_A;
            //email end

        }

    }

    private SmsContentObserverClass smsContentObserver = new SmsContentObserverClass();
    private InboxContentObserverClass inboxContentObserver = new InboxContentObserverClass();
    private SentContentObserverClass sentContentObserver = new SentContentObserverClass();
    private DraftContentObserverClass draftContentObserver = new DraftContentObserverClass();
    private OutboxContentObserverClass outboxContentObserver = new OutboxContentObserverClass();
    private FailedContentObserverClass failedContentObserver = new FailedContentObserverClass();
    private QueuedContentObserverClass queuedContentObserver = new QueuedContentObserverClass();

    private EmailContentObserverClass emailContentObserver = new EmailContentObserverClass();
    private EmailInboxContentObserverClass emailInboxContentObserver = new EmailInboxContentObserverClass();
    private EmailSentContentObserverClass emailSentContentObserver = new EmailSentContentObserverClass();
    private EmailDraftContentObserverClass emailDraftContentObserver = new EmailDraftContentObserverClass();
    private EmailOutboxContentObserverClass emailOutboxContentObserver = new EmailOutboxContentObserverClass();

    private Cursor crSmsA = null;
    private Cursor crSmsB = null;
    private Cursor crSmsInboxA = null;
    private Cursor crSmsInboxB = null;
    private Cursor crSmsSentA = null;
    private Cursor crSmsSentB = null;
    private Cursor crSmsDraftA = null;
    private Cursor crSmsDraftB = null;
    private Cursor crSmsOutboxA = null;
    private Cursor crSmsOutboxB = null;
    private Cursor crSmsFailedA = null;
    private Cursor crSmsFailedB = null;
    private Cursor crSmsQueuedA = null;
    private Cursor crSmsQueuedB = null;

    private Cursor crMmsA = null;
    private Cursor crMmsB = null;
    private Cursor crMmsOutboxA = null;
    private Cursor crMmsOutboxB = null;
    private Cursor crMmsDraftA = null;
    private Cursor crMmsDraftB = null;
    private Cursor crMmsInboxA = null;
    private Cursor crMmsInboxB = null;
    private Cursor crMmsSentA = null;
    private Cursor crMmsSentB = null;


    private Cursor crEmailA = null;
    private Cursor crEmailB = null;
    private Cursor crEmailOutboxA = null;
    private Cursor crEmailOutboxB = null;
    private Cursor crEmailDraftA = null;
    private Cursor crEmailDraftB = null;
    private Cursor crEmailInboxA = null;
    private Cursor crEmailInboxB = null;
    private Cursor crEmailSentA = null;
    private Cursor crEmailSentB = null;

    private final int CR_SMS_A = 1;
    private final int CR_SMS_B = 2;
    private int currentCRSms = CR_SMS_A;
    private final int CR_SMS_INBOX_A = 1;
    private final int CR_SMS_INBOX_B = 2;
    private int currentCRSmsInbox = CR_SMS_INBOX_A;
    private final int CR_SMS_SENT_A = 1;
    private final int CR_SMS_SENT_B = 2;
    private int currentCRSmsSent = CR_SMS_SENT_A;
    private final int CR_SMS_DRAFT_A = 1;
    private final int CR_SMS_DRAFT_B = 2;
    private int currentCRSmsDraft = CR_SMS_DRAFT_A;
    private final int CR_SMS_OUTBOX_A = 1;
    private final int CR_SMS_OUTBOX_B = 2;
    private int currentCRSmsOutbox = CR_SMS_OUTBOX_A;
    private final int CR_SMS_FAILED_A = 1;
    private final int CR_SMS_FAILED_B = 2;
    private int currentCRSmsFailed = CR_SMS_FAILED_A;
    private final int CR_SMS_QUEUED_A = 1;
    private final int CR_SMS_QUEUED_B = 2;
    private int currentCRSmsQueued = CR_SMS_QUEUED_A;

    private final int CR_MMS_A = 1;
    private final int CR_MMS_B = 2;
    private int currentCRMms = CR_MMS_A;
    private final int CR_MMS_OUTBOX_A = 1;
    private final int CR_MMS_OUTBOX_B = 2;
    private int currentCRMmsOutbox = CR_MMS_OUTBOX_A;
    private final int CR_MMS_DRAFT_A = 1;
    private final int CR_MMS_DRAFT_B = 2;
    private int currentCRMmsDraft = CR_MMS_DRAFT_A;
    private final int CR_MMS_INBOX_A = 1;
    private final int CR_MMS_INBOX_B = 2;
    private int currentCRMmsInbox = CR_MMS_INBOX_A;
    private final int CR_MMS_SENT_A = 1;
    private final int CR_MMS_SENT_B = 2;
    private int currentCRMmsSent = CR_MMS_SENT_A;

    private final int CR_EMAIL_A = 1;
    private final int CR_EMAIL_B = 2;
    private int currentCREmail = CR_EMAIL_A;
    private final int CR_EMAIL_OUTBOX_A = 1;
    private final int CR_EMAIL_OUTBOX_B = 2;
    private int currentCREmailOutbox = CR_EMAIL_OUTBOX_A;
    private final int CR_EMAIL_DRAFT_A = 1;
    private final int CR_EMAIL_DRAFT_B = 2;
    private int currentCREmailDraft = CR_EMAIL_DRAFT_A;
    private final int CR_EMAIL_INBOX_A = 1;
    private final int CR_EMAIL_INBOX_B = 2;
    private int currentCREmailInbox = CR_EMAIL_INBOX_A;
    private final int CR_EMAIL_SENT_A = 1;
    private final int CR_EMAIL_SENT_B = 2;
    private int currentCREmailSent = CR_EMAIL_SENT_A;


    /**
     * Get the folder name (MAP representation) based on the
     * folder type value in SMS database
     */
    private String getMAPFolder(int type) {
        String folder = null;
        switch (type) {
        case 1:
            folder = "inbox";
            break;
        case 2:
            folder = "sent";
            break;
        case 3:
            folder = "draft";
            break;
        case 4:
        case 5:
        case 6:
            folder = "outbox";
            break;
        default:
            break;
        }
        return folder;
    }

    /**
     * Get the folder name based on the type in SMS ContentProvider
     */
    private String getFolder(int type) {
        String folder = null;
        switch (type) {
        case 1:
            folder = "inbox";
            break;
        case 2:
            folder = "sent";
            break;
        case 3:
            folder = "draft";
            break;
        case 4:
            folder = "outbox";
            break;
        case 5:
            folder = "failed";
            break;
        case 6:
            folder = "queued";
            break;
        default:
            break;
        }
        return folder;
    }

    /**
     * Gets the table type (as in Sms Content Provider) for the
     * given id
     */
    private int getMessageType(String id) {
        Cursor cr = mContext.getContentResolver().query(
                Uri.parse("content://sms/" + id),
                new String[] { "_id", "type" }, null, null, null);
        if (cr.moveToFirst()) {
            return cr.getInt(cr.getColumnIndex("type"));
        }
        return -1;
    }
    /**
     * Gets the table type (as in Email Content Provider) for the
     * given id
     */
    private int getDeletedFlagEmail(String id) {
        int deletedFlag =0;
        Cursor cr = mContext.getContentResolver().query(
                Uri.parse("content://com.android.email.provider/message/" + id),
                new String[] { "_id", "mailboxKey" }, null, null, null);
        int folderId = -1;
        if (cr.moveToFirst()) {
                folderId = cr.getInt(cr.getColumnIndex("mailboxKey"));
        }

        Cursor cr1 = mContext.getContentResolver().query(
                Uri.parse("content://com.android.email.provider/mailbox"),
                new String[] { "_id", "displayName" }, "_id ="+ folderId, null, null);
        String folderName = null;
        if (cr1.moveToFirst()) {
                folderName = cr1.getString(cr1.getColumnIndex("displayName"));
        }
        if(folderName !=null && (folderName.equalsIgnoreCase("Trash") ||
                        folderName.toUpperCase().contains("TRASH"))){
                deletedFlag = 1;
        }
        return deletedFlag;
    }

    /**
     * Get the folder name (table name of Sms Content Provider)
     */
    private String getContainingFolder(String oldFolder, String id,
            String dateTime) {
        String newFolder = null;
        Cursor cr = mContext.getContentResolver().query(
                Uri.parse("content://sms/"),
                new String[] { "_id", "date", "type" }, " _id = " + id, null,
                null);
        if (cr.moveToFirst()) {
            return getFolder(cr.getInt(cr.getColumnIndex("type")));
        }
        return newFolder;
    }


    private BroadcastReceiver mStorageStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_DEVICE_STORAGE_LOW)) {
                Log.d(TAG, " Memory Full ");
                sendMnsEvent(MEMORY_FULL, null, null, null, null);
            } else if (intent.getAction().equals(Intent.ACTION_DEVICE_STORAGE_OK)) {
                Log.d(TAG, " Memory Available ");
                sendMnsEvent(MEMORY_AVAILABLE, null, null, null, null);
            }
        }
    };

    /**
     * This class listens for changes in Email Content Provider's inbox table
     * It acts, only when a entry gets removed from the table
     */
    private class EmailInboxContentObserverClass extends ContentObserver {

        public EmailInboxContentObserverClass() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            int currentItemCount = 0;
            int newItemCount = 0;

            if (currentCREmailInbox == CR_EMAIL_INBOX_A) {
                currentItemCount = crEmailInboxA.getCount();
                crEmailInboxB.requery();
                newItemCount = crEmailInboxB.getCount();
            } else {
                currentItemCount = crEmailInboxB.getCount();
                crEmailInboxA.requery();
                newItemCount = crEmailInboxA.getCount();
            }

            Log.d(TAG, "EMAIL INBOX current " + currentItemCount + " new "
                    + newItemCount);

            if (currentItemCount > newItemCount) {
                crEmailInboxA.moveToFirst();
                crEmailInboxB.moveToFirst();

                CursorJoiner joiner = new CursorJoiner(crEmailInboxA,
                        new String[] { "_id" }, crEmailInboxB,
                        new String[] { "_id" });

                CursorJoiner.Result joinerResult;
                while (joiner.hasNext()) {
                    joinerResult = joiner.next();
                    switch (joinerResult) {
                    case LEFT:
                        // handle case where a row in cursor1 is unique
                        if (currentCREmailInbox == CR_EMAIL_INBOX_A) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " EMAIL DELETED FROM INBOX ");
                            String id = crEmailInboxA.getString(crEmailInboxA
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED EMAIL ID " + id);
                            int deletedFlag = getDeletedFlagEmail(id); //TODO
                            if(deletedFlag == 1){
                                    id = Integer.toString(Integer.valueOf(id)
                                            + EMAIL_HDLR_CONSTANT);

                                    sendMnsEvent(MESSAGE_DELETED, id,
                                                "TELECOM/MSG/INBOX", null, "EMAIL");
                            }
                            else {
                                Log.d(TAG, "Shouldn't reach here as you cannot "
                                        + "move msg from Inbox to any other folder");
                            }

                        } else {
                            // TODO - The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case RIGHT:
                        // handle case where a row in cursor2 is unique
                        if (currentCREmailInbox == CR_EMAIL_INBOX_B) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " EMAIL DELETED FROM INBOX ");
                            String id = crEmailInboxB.getString(crEmailInboxB
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED EMAIL ID " + id);
                            int deletedFlag = getDeletedFlagEmail(id); //TODO
                            if(deletedFlag == 1){
                                    id = Integer.toString(Integer.valueOf(id)
                                            + EMAIL_HDLR_CONSTANT);
                                    sendMnsEvent(MESSAGE_DELETED, id,
                                                "TELECOM/MSG/INBOX", null, "EMAIL");
                            }
                            else {
                                Log.d(TAG, "Shouldn't reach here as you cannot "
                                        + "move msg from Inbox to any other folder");
                            }
                        }
                        else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case BOTH:
                        // handle case where a row with the same key is in both
                        // cursors
                        break;
                    }
                }
            }

            if (currentCREmailInbox == CR_EMAIL_INBOX_A) {
                currentCREmailInbox = CR_EMAIL_INBOX_B;
            } else {
                currentCREmailInbox = CR_EMAIL_INBOX_A;
            }
        }
    }
    /**
     * This class listens for changes in Email Content Provider's Sent table
     * It acts, only when a entry gets removed from the table
     */
    private class EmailSentContentObserverClass extends ContentObserver {

        public EmailSentContentObserverClass() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            int currentItemCount = 0;
            int newItemCount = 0;

            if (currentCREmailSent == CR_EMAIL_SENT_A) {
                currentItemCount = crEmailSentA.getCount();
                crEmailSentB.requery();
                newItemCount = crEmailSentB.getCount();
            } else {
                currentItemCount = crEmailSentB.getCount();
                crEmailSentA.requery();
                newItemCount = crEmailSentA.getCount();
            }

            Log.d(TAG, "EMAIL SENT current " + currentItemCount + " new "
                    + newItemCount);

            if (currentItemCount > newItemCount) {
                crEmailSentA.moveToFirst();
                crEmailSentB.moveToFirst();

                CursorJoiner joiner = new CursorJoiner(crEmailSentA,
                        new String[] { "_id" }, crEmailSentB,
                        new String[] { "_id" });

                CursorJoiner.Result joinerResult;
                while (joiner.hasNext()) {
                    joinerResult = joiner.next();
                    switch (joinerResult) {
                    case LEFT:
                        // handle case where a row in cursor1 is unique
                        if (currentCREmailSent == CR_EMAIL_SENT_A) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " EMAIL DELETED FROM SENT ");
                            String id = crEmailSentA.getString(crEmailSentA
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED EMAIL ID " + id);

                            int deletedFlag = getDeletedFlagEmail(id);
                            if(deletedFlag == 1){
                                id = Integer.toString(Integer.valueOf(id)
                                            + EMAIL_HDLR_CONSTANT);
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/SENT", null, "EMAIL");
                            } else {
                                Log.d(TAG,"Shouldn't reach here as you cannot " +
                                          "move msg from Sent to any other folder");
                            }
                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case RIGHT:
                        // handle case where a row in cursor2 is unique
                        if (currentCREmailSent == CR_EMAIL_SENT_B) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " EMAIL DELETED FROM SENT ");
                            String id = crEmailSentB.getString(crEmailSentB
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED EMAIL ID " + id);
                            int deletedFlag = getDeletedFlagEmail(id);
                            if(deletedFlag == 1){
                                id = Integer.toString(Integer.valueOf(id)
                                            + EMAIL_HDLR_CONSTANT);
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/SENT", null, "EMAIL");
                            } else {
                                Log.d(TAG, "Shouldn't reach here as " +
                                                "you cannot move msg from Sent to " +
                                                "any other folder");
                            }
                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case BOTH:
                        // handle case where a row with the same key is in both
                        // cursors
                        break;
                    }
                }
            }
            if (currentCREmailSent == CR_EMAIL_SENT_A) {
                currentCREmailSent = CR_EMAIL_SENT_B;
            } else {
                currentCREmailSent = CR_EMAIL_SENT_A;
            }
        }
    }

    /**
     * This class listens for changes in Email Content Provider's Draft table
     * It acts, only when a entry gets removed from the table
     */
    private class EmailDraftContentObserverClass extends ContentObserver {

        public EmailDraftContentObserverClass() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            int currentItemCount = 0;
            int newItemCount = 0;

            if (currentCREmailDraft == CR_EMAIL_DRAFT_A) {
                currentItemCount = crEmailDraftA.getCount();
                crEmailDraftB.requery();
                newItemCount = crEmailDraftB.getCount();
            } else {
                currentItemCount = crEmailDraftB.getCount();
                crEmailDraftA.requery();
                newItemCount = crEmailDraftA.getCount();
            }

            Log.d(TAG, "EMAIL DRAFT current " + currentItemCount + " new "
                    + newItemCount);

            if (currentItemCount > newItemCount) {
                crEmailDraftA.moveToFirst();
                crEmailDraftB.moveToFirst();

                CursorJoiner joiner = new CursorJoiner(crEmailDraftA,
                        new String[] { "_id" }, crEmailDraftB,
                        new String[] { "_id" });

                CursorJoiner.Result joinerResult;
                while (joiner.hasNext()) {
                    joinerResult = joiner.next();
                    switch (joinerResult) {
                    case LEFT:
                        // handle case where a row in cursor1 is unique
                        if (currentCREmailDraft == CR_EMAIL_DRAFT_A) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " EMAIL DELETED FROM DRAFT ");
                            String id = crEmailDraftA.getString(crEmailDraftA
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED EMAIL ID " + id);

                            int deletedFlag = getDeletedFlagEmail(id);
                            if(deletedFlag == 1){
                                id = Integer.toString(Integer.valueOf(id)
                                            + EMAIL_HDLR_CONSTANT);
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/DRAFT", null, "EMAIL");
                            } else {
                                Cursor cr1 = null;
                                int folderId;
                                String containingFolder = null;
                                EmailUtils eu = new EmailUtils();
                                Uri uri1 = Uri.parse("content://com.android.email.provider/message");
                                String whereClause = " _id = " + id;
                                cr1 = mContext.getContentResolver().query(uri1, null, whereClause, null,
                                        null);

                                if (cr1.getCount() > 0) {
                                    cr1.moveToFirst();
                                    folderId = cr1.getInt(cr1.getColumnIndex("mailboxKey"));
                                    containingFolder = eu.getContainingFolderEmail(folderId, mContext);
                                }

                                String newFolder = containingFolder;
                                id = Integer.toString(Integer.valueOf(id)
                                            + EMAIL_HDLR_CONSTANT);
                                sendMnsEvent(MESSAGE_SHIFT, id, "TELECOM/MSG/"
                                        + newFolder, "TELECOM/MSG/DRAFT",
                                        "EMAIL");
                                if ( newFolder.equalsIgnoreCase("sent")) {
                                    sendMnsEvent(SENDING_SUCCESS, id,
                                            "TELECOM/MSG/" + newFolder,
                                            null, "EMAIL");
                                }
                            }

                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case RIGHT:
                        // handle case where a row in cursor2 is unique
                        if (currentCREmailDraft == CR_EMAIL_DRAFT_B) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " EMAIL DELETED FROM DRAFT ");
                            String id = crEmailDraftB.getString(crEmailDraftB
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED EMAIL ID " + id);
                            int deletedFlag = getDeletedFlagEmail(id);
                            if(deletedFlag == 1){
                                id = Integer.toString(Integer.valueOf(id)
                                            + EMAIL_HDLR_CONSTANT);
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/DRAFT", null, "EMAIL");
                            } else {
                                Cursor cr1 = null;
                                int folderId;
                                String containingFolder = null;
                                EmailUtils eu = new EmailUtils();
                                Uri uri1 = Uri.parse("content://com.android.email.provider/message");
                                String whereClause = " _id = " + id;
                                cr1 = mContext.getContentResolver().query(uri1, null, whereClause, null,
                                        null);

                                if (cr1.getCount() > 0) {
                                    cr1.moveToFirst();
                                    folderId = cr1.getInt(cr1.getColumnIndex("mailboxKey"));
                                    containingFolder = eu.getContainingFolderEmail(folderId, mContext);
                                }

                                String newFolder = containingFolder;
                                id = Integer.toString(Integer.valueOf(id)
                                            + EMAIL_HDLR_CONSTANT);
                                sendMnsEvent(MESSAGE_SHIFT, id, "TELECOM/MSG/"
                                        + newFolder, "TELECOM/MSG/DRAFT",
                                        "EMAIL");
                                if ( newFolder.equalsIgnoreCase("sent")) {
                                    sendMnsEvent(SENDING_SUCCESS, id,
                                            "TELECOM/MSG/" + newFolder,
                                            null, "EMAIL");
                                }
                            }

                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case BOTH:
                        // handle case where a row with the same key is in both
                        // cursors
                        break;
                    }
                }
            }
            if (currentCREmailDraft == CR_EMAIL_DRAFT_A) {
                currentCREmailDraft = CR_EMAIL_DRAFT_B;
            } else {
                currentCREmailDraft = CR_EMAIL_DRAFT_A;
            }
        }
    }

    /**
     * This class listens for changes in Sms Content Provider's Outbox table
     * It acts only when a entry gets removed from the table
     */
    private class EmailOutboxContentObserverClass extends ContentObserver {

        public EmailOutboxContentObserverClass() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            int currentItemCount = 0;
            int newItemCount = 0;

            if (currentCREmailOutbox == CR_EMAIL_OUTBOX_A) {
                currentItemCount = crEmailOutboxA.getCount();
                crEmailOutboxB.requery();
                newItemCount = crEmailOutboxB.getCount();
            } else {
                currentItemCount = crEmailOutboxB.getCount();
                crEmailOutboxA.requery();
                newItemCount = crEmailOutboxA.getCount();
            }

            Log.d(TAG, "EMAIL OUTBOX current " + currentItemCount + " new "
                    + newItemCount);

            if (currentItemCount > newItemCount) {
                crEmailOutboxA.moveToFirst();
                crEmailOutboxB.moveToFirst();

                CursorJoiner joiner = new CursorJoiner(crEmailOutboxA,
                        new String[] { "_id" }, crEmailOutboxB,
                        new String[] { "_id" });

                CursorJoiner.Result joinerResult;
                while (joiner.hasNext()) {
                    joinerResult = joiner.next();
                    switch (joinerResult) {
                    case LEFT:
                        // handle case where a row in cursor1 is unique
                        if (currentCREmailOutbox == CR_EMAIL_OUTBOX_A) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " EMAIL DELETED FROM OUTBOX ");
                            String id = crEmailOutboxA.getString(crEmailOutboxA
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED EMAIL ID " + id);
                            int deletedFlag = getDeletedFlagEmail(id);
                            if(deletedFlag == 1){
                                id = Integer.toString(Integer.valueOf(id)
                                            + EMAIL_HDLR_CONSTANT);
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/OUTBOX", null, "EMAIL");
                            } else {
                                Cursor cr1 = null;
                                int folderId;
                                String containingFolder = null;
                                EmailUtils eu = new EmailUtils();
                                Uri uri1 = Uri.parse("content://com.android.email.provider/message");
                                String whereClause = " _id = " + id;
                                cr1 = mContext.getContentResolver().query(uri1, null, whereClause, null,
                                        null);

                                if (cr1.getCount() > 0) {
                                    cr1.moveToFirst();
                                    folderId = cr1.getInt(cr1.getColumnIndex("mailboxKey"));
                                    containingFolder = eu.getContainingFolderEmail(folderId, mContext);
                                }

                                String newFolder = containingFolder;
                                id = Integer.toString(Integer.valueOf(id)
                                            + EMAIL_HDLR_CONSTANT);

                                if ((newFolder != null)
                                        && (!newFolder
                                                .equalsIgnoreCase("outbox"))) {
                                    // The message has moved on MAP virtual
                                    // folder representation.
                                    sendMnsEvent(MESSAGE_SHIFT, id,
                                            "TELECOM/MSG/" + newFolder,
                                            "TELECOM/MSG/OUTBOX", "EMAIL");
                                    if ( newFolder.equalsIgnoreCase("sent")) {
                                        sendMnsEvent(SENDING_SUCCESS, id,
                                                "TELECOM/MSG/" + newFolder,
                                                null, "EMAIL");
                                    }
                                }
                            }

                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case RIGHT:
                        // handle case where a row in cursor2 is unique
                        if (currentCREmailOutbox == CR_EMAIL_OUTBOX_B) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " EMAIL DELETED FROM OUTBOX ");
                            String id = crEmailOutboxB.getString(crEmailOutboxB
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED EMAIL ID " + id);
                            int deletedFlag = getDeletedFlagEmail(id);
                            if(deletedFlag == 1){
                                id = Integer.toString(Integer.valueOf(id)
                                            + EMAIL_HDLR_CONSTANT);
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/OUTBOX", null, "EMAIL");
                            } else {
                                Cursor cr1 = null;
                                int folderId;
                                String containingFolder = null;
                                EmailUtils eu = new EmailUtils();
                                Uri uri1 = Uri.parse("content://com.android.email.provider/message");
                                String whereClause = " _id = " + id;
                                cr1 = mContext.getContentResolver().query(uri1, null, whereClause, null,
                                        null);

                                if (cr1.getCount() > 0) {
                                    cr1.moveToFirst();
                                    folderId = cr1.getInt(cr1.getColumnIndex("mailboxKey"));
                                    containingFolder = eu.getContainingFolderEmail(folderId, mContext);
                                }

                                String newFolder = containingFolder;
                                id = Integer.toString(Integer.valueOf(id)
                                            + EMAIL_HDLR_CONSTANT);

                                if ((newFolder != null)
                                        && (!newFolder
                                                .equalsIgnoreCase("outbox"))) {
                                    // The message has moved on MAP virtual
                                    // folder representation.
                                    sendMnsEvent(MESSAGE_SHIFT, id,
                                            "TELECOM/MSG/" + newFolder,
                                            "TELECOM/MSG/OUTBOX", "EMAIL");
                                }
                            }

                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case BOTH:
                        // handle case where a row with the same key is in both
                        // cursors
                        break;
                    }
                }
            }
            if (currentCREmailOutbox == CR_EMAIL_OUTBOX_A) {
                currentCREmailOutbox = CR_EMAIL_OUTBOX_B;
            } else {
                currentCREmailOutbox = CR_EMAIL_OUTBOX_A;
            }
        }
    }



    /**
     * This class listens for changes in Sms Content Provider
     * It acts, only when a new entry gets added to database
     */
    private class SmsContentObserverClass extends ContentObserver {

        public SmsContentObserverClass() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            int currentItemCount = 0;
            int newItemCount = 0;

            checkMmsAdded();

            // Synchronize this?
            if (currentCRSms == CR_SMS_A) {
                currentItemCount = crSmsA.getCount();
                crSmsB.requery();
                newItemCount = crSmsB.getCount();
            } else {
                currentItemCount = crSmsB.getCount();
                crSmsA.requery();
                newItemCount = crSmsA.getCount();
            }

            Log.d(TAG, "SMS current " + currentItemCount + " new "
                    + newItemCount);

            if (newItemCount > currentItemCount) {
                crSmsA.moveToFirst();
                crSmsB.moveToFirst();

                CursorJoiner joiner = new CursorJoiner(crSmsA,
                        new String[] { "_id" }, crSmsB, new String[] { "_id" });

                CursorJoiner.Result joinerResult;
                while (joiner.hasNext()) {
                    joinerResult = joiner.next();
                    switch (joinerResult) {
                    case LEFT:
                        // handle case where a row in cursor1 is unique
                        if (currentCRSms == CR_SMS_A) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                            Log.d(TAG, " SMS ADDED TO INBOX ");
                            String body1 = crSmsA.getString(crSmsA
                                    .getColumnIndex("body"));
                            String id1 = crSmsA.getString(crSmsA
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " ADDED SMS ID " + id1 + " BODY "
                                    + body1);
                            String folder = getMAPFolder(crSmsA.getInt(crSmsA
                                    .getColumnIndex("type")));
                            if ( folder != null ) {
                                sendMnsEvent(NEW_MESSAGE, id1, "TELECOM/MSG/"
                                        + folder, null, "SMS_GSM");
                            } else {
                                Log.d(TAG, " ADDED TO UNKNOWN FOLDER");
                            }
                        }
                        break;
                    case RIGHT:
                        // handle case where a row in cursor2 is unique
                        if (currentCRSms == CR_SMS_B) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                            Log.d(TAG, " SMS ADDED ");
                            String body1 = crSmsB.getString(crSmsB
                                    .getColumnIndex("body"));
                            String id1 = crSmsB.getString(crSmsB
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " ADDED SMS ID " + id1 + " BODY "
                                    + body1);
                            String folder = getMAPFolder(crSmsB.getInt(crSmsB
                                    .getColumnIndex("type")));
                            if ( folder != null ) {
                                sendMnsEvent(NEW_MESSAGE, id1, "TELECOM/MSG/"
                                        + folder, null, "SMS_GSM");
                            } else {
                                Log.d(TAG, " ADDED TO UNKNOWN FOLDER");
                            }
                        }
                        break;
                    case BOTH:
                        // handle case where a row with the same key is in both
                        // cursors
                        break;
                    }
                }
            }
            if (currentCRSms == CR_SMS_A) {
                currentCRSms = CR_SMS_B;
            } else {
                currentCRSms = CR_SMS_A;
            }
        }
    }

    /**
     * This class listens for changes in Email Content Provider
     * It acts, only when a new entry gets added to database
     */
    private class EmailContentObserverClass extends ContentObserver {

        public EmailContentObserverClass() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            int currentItemCount = 0;
            int newItemCount = 0;
            EmailUtils eu = new EmailUtils();
            String containingFolder = null;

            // Synchronize this?
            if (currentCREmail == CR_EMAIL_A) {
                currentItemCount = crEmailA.getCount();
                crEmailB.requery();
                newItemCount = crEmailB.getCount();
            } else {
                currentItemCount = crEmailB.getCount();
                crEmailA.requery();
                newItemCount = crEmailA.getCount();
            }

            Log.d(TAG, "Email current " + currentItemCount + " new "
                    + newItemCount);

            if (newItemCount > currentItemCount) {
                crEmailA.moveToFirst();
                crEmailB.moveToFirst();

                CursorJoiner joiner = new CursorJoiner(crEmailA,
                        new String[] { "_id" }, crEmailB, new String[] { "_id" });

                CursorJoiner.Result joinerResult;
                while (joiner.hasNext()) {
                    joinerResult = joiner.next();
                    switch (joinerResult) {
                    case LEFT:
                        // handle case where a row in cursor1 is unique
                        if (currentCREmail == CR_EMAIL_A) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                            Log.d(TAG, " EMAIL ADDED TO INBOX ");
                            String id1 = crEmailA.getString(crEmailA
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " ADDED EMAIL ID " + id1);
                            Cursor cr1 = null;
                            int folderId;
                            Uri uri1 = Uri.parse("content://com.android.email.provider/message");
                            String whereClause = " _id = " + id1;
                            cr1 = mContext.getContentResolver().query(uri1, null, whereClause, null,
                                    null);
                            if ( cr1.moveToFirst()) {
                                        do {
                                                for(int i=0;i<cr1.getColumnCount();i++){
                                                        Log.d(TAG, " Column Name: "+ cr1.getColumnName(i) + " Value: " + cr1.getString(i));
                                                }
                                    } while ( cr1.moveToNext());
                                }

                            if (cr1.getCount() > 0) {
                                cr1.moveToFirst();
                                folderId = cr1.getInt(cr1.getColumnIndex("mailboxKey"));
                                containingFolder = eu.getContainingFolderEmail(folderId, mContext);
                            }

                            if ( containingFolder != null ) {
                                Log.d(TAG, " containingFolder:: "+containingFolder);
                                id1 = Integer.toString(Integer.valueOf(id1)
                                        + EMAIL_HDLR_CONSTANT);
                                sendMnsEvent(NEW_MESSAGE, id1, "TELECOM/MSG/"
                                        + containingFolder, null, "EMAIL");
                            } else {
                                Log.d(TAG, " ADDED TO UNKNOWN FOLDER");
                            }
                        }
                        break;
                    case RIGHT:
                        // handle case where a row in cursor2 is unique
                        if (currentCREmail == CR_EMAIL_B) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                            Log.d(TAG, " EMAIL ADDED ");
                            String id1 = crEmailB.getString(crEmailB
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " ADDED EMAIL ID " + id1);
                            Cursor cr1 = null;
                            int folderId;
                            Uri uri1 = Uri.parse("content://com.android.email.provider/message");
                            String whereClause = " _id = " + id1;
                            cr1 = mContext.getContentResolver().query(uri1, null, whereClause, null,
                                    null);

                            if ( cr1.moveToFirst()) {
                                do {
                                    for(int i=0;i<cr1.getColumnCount();i++){
                                        Log.d(TAG, " Column Name: "+ cr1.getColumnName(i) +
                                                " Value: " + cr1.getString(i));
                                    }
                                } while ( cr1.moveToNext());
                            }

                            if (cr1.getCount() > 0) {
                                cr1.moveToFirst();
                                folderId = cr1.getInt(cr1.getColumnIndex("mailboxKey"));
                                containingFolder = eu.getContainingFolderEmail(folderId, mContext);
                            }
                            if ( containingFolder != null ) {
                                Log.d(TAG, " containingFolder:: "+containingFolder);
                                id1 = Integer.toString(Integer.valueOf(id1)
                                        + EMAIL_HDLR_CONSTANT);
                                sendMnsEvent(NEW_MESSAGE, id1, "TELECOM/MSG/"
                                        + containingFolder, null, "EMAIL");
                            } else {
                                Log.d(TAG, " ADDED TO UNKNOWN FOLDER");
                            }
                        }
                        break;
                    case BOTH:
                        // handle case where a row with the same key is in both
                        // cursors
                        break;
                    }
                }
            }
            if (currentCREmail == CR_EMAIL_A) {
                currentCREmail = CR_EMAIL_B;
            } else {
                currentCREmail = CR_EMAIL_A;
            }
        }
    }


    /**
     * This class listens for changes in Sms Content Provider's inbox table
     * It acts, only when a entry gets removed from the table
     */
    private class InboxContentObserverClass extends ContentObserver {

        public InboxContentObserverClass() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            int currentItemCount = 0;
            int newItemCount = 0;

            checkMmsInbox();

            if (currentCRSmsInbox == CR_SMS_INBOX_A) {
                currentItemCount = crSmsInboxA.getCount();
                crSmsInboxB.requery();
                newItemCount = crSmsInboxB.getCount();
            } else {
                currentItemCount = crSmsInboxB.getCount();
                crSmsInboxA.requery();
                newItemCount = crSmsInboxA.getCount();
            }

            Log.d(TAG, "SMS INBOX current " + currentItemCount + " new "
                    + newItemCount);

            if (currentItemCount > newItemCount) {
                crSmsInboxA.moveToFirst();
                crSmsInboxB.moveToFirst();

                CursorJoiner joiner = new CursorJoiner(crSmsInboxA,
                        new String[] { "_id" }, crSmsInboxB,
                        new String[] { "_id" });

                CursorJoiner.Result joinerResult;
                while (joiner.hasNext()) {
                    joinerResult = joiner.next();
                    switch (joinerResult) {
                    case LEFT:
                        // handle case where a row in cursor1 is unique
                        if (currentCRSmsInbox == CR_SMS_INBOX_A) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " SMS DELETED FROM INBOX ");
                            String body = crSmsInboxA.getString(crSmsInboxA
                                    .getColumnIndex("body"));
                            String id = crSmsInboxA.getString(crSmsInboxA
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED SMS ID " + id + " BODY "
                                    + body);
                            int msgType = getMessageType(id);
                            if (msgType == -1) {
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/INBOX", null, "SMS_GSM");
                            } else {
                                Log.d(TAG, "Shouldn't reach here as you cannot " +
                                                "move msg from Inbox to any other folder");
                            }
                        } else {
                            // TODO - The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case RIGHT:
                        // handle case where a row in cursor2 is unique
                        if (currentCRSmsInbox == CR_SMS_INBOX_B) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " SMS DELETED FROM INBOX ");
                            String body = crSmsInboxB.getString(crSmsInboxB
                                    .getColumnIndex("body"));
                            String id = crSmsInboxB.getString(crSmsInboxB
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED SMS ID " + id + " BODY "
                                    + body);
                            int msgType = getMessageType(id);
                            if (msgType == -1) {
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/INBOX", null, "SMS_GSM");
                            } else {
                                Log.d(TAG,"Shouldn't reach here as you cannot " +
                                                "move msg from Inbox to any other folder");
                            }
                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case BOTH:
                        // handle case where a row with the same key is in both
                        // cursors
                        break;
                    }
                }
            }
            if (currentCRSmsInbox == CR_SMS_INBOX_A) {
                currentCRSmsInbox = CR_SMS_INBOX_B;
            } else {
                currentCRSmsInbox = CR_SMS_INBOX_A;
            }
        }
    }



    /**
     * This class listens for changes in Sms Content Provider's Sent table
     * It acts, only when a entry gets removed from the table
     */
    private class SentContentObserverClass extends ContentObserver {

        public SentContentObserverClass() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            int currentItemCount = 0;
            int newItemCount = 0;

            checkMmsSent();

            if (currentCRSmsSent == CR_SMS_SENT_A) {
                currentItemCount = crSmsSentA.getCount();
                crSmsSentB.requery();
                newItemCount = crSmsSentB.getCount();
            } else {
                currentItemCount = crSmsSentB.getCount();
                crSmsSentA.requery();
                newItemCount = crSmsSentA.getCount();
            }

            Log.d(TAG, "SMS SENT current " + currentItemCount + " new "
                    + newItemCount);

            if (currentItemCount > newItemCount) {
                crSmsSentA.moveToFirst();
                crSmsSentB.moveToFirst();

                CursorJoiner joiner = new CursorJoiner(crSmsSentA,
                        new String[] { "_id" }, crSmsSentB,
                        new String[] { "_id" });

                CursorJoiner.Result joinerResult;
                // while((CursorJointer.Result joinerResult = joiner.next()) !=
                // null)
                while (joiner.hasNext()) {
                    joinerResult = joiner.next();
                    switch (joinerResult) {
                    case LEFT:
                        // handle case where a row in cursor1 is unique
                        if (currentCRSmsSent == CR_SMS_SENT_A) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " SMS DELETED FROM SENT ");
                            String body = crSmsSentA.getString(crSmsSentA
                                    .getColumnIndex("body"));
                            String id = crSmsSentA.getString(crSmsSentA
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED SMS ID " + id + " BODY "
                                    + body);

                            int msgType = getMessageType(id);
                            if (msgType == -1) {
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/SENT", null, "SMS_GSM");
                            } else {
                                Log.d(TAG,"Shouldn't reach here as you cannot " +
                                          "move msg from Sent to any other folder");
                            }
                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case RIGHT:
                        // handle case where a row in cursor2 is unique
                        if (currentCRSmsSent == CR_SMS_SENT_B) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " SMS DELETED FROM SENT ");
                            String body = crSmsSentB.getString(crSmsSentB
                                    .getColumnIndex("body"));
                            String id = crSmsSentB.getString(crSmsSentB
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED SMS ID " + id + " BODY "
                                    + body);
                            int msgType = getMessageType(id);
                            if (msgType == -1) {
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/SENT", null, "SMS_GSM");
                            } else {
                                Log.d(TAG, "Shouldn't reach here as " +
                                                "you cannot move msg from Sent to " +
                                                "any other folder");
                            }
                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case BOTH:
                        // handle case where a row with the same key is in both
                        // cursors
                        break;
                    }
                }
            }
            if (currentCRSmsSent == CR_SMS_SENT_A) {
                currentCRSmsSent = CR_SMS_SENT_B;
            } else {
                currentCRSmsSent = CR_SMS_SENT_A;
            }
        }
    }

    /**
     * This class listens for changes in Sms Content Provider's Draft table
     * It acts, only when a entry gets removed from the table
     */
    private class DraftContentObserverClass extends ContentObserver {

        public DraftContentObserverClass() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            int currentItemCount = 0;
            int newItemCount = 0;

            checkMmsDrafts();

            if (currentCRSmsDraft == CR_SMS_DRAFT_A) {
                currentItemCount = crSmsDraftA.getCount();
                crSmsDraftB.requery();
                newItemCount = crSmsDraftB.getCount();
            } else {
                currentItemCount = crSmsDraftB.getCount();
                crSmsDraftA.requery();
                newItemCount = crSmsDraftA.getCount();
            }

            Log.d(TAG, "SMS DRAFT current " + currentItemCount + " new "
                    + newItemCount);

            if (currentItemCount > newItemCount) {
                crSmsDraftA.moveToFirst();
                crSmsDraftB.moveToFirst();

                CursorJoiner joiner = new CursorJoiner(crSmsDraftA,
                        new String[] { "_id" }, crSmsDraftB,
                        new String[] { "_id" });

                CursorJoiner.Result joinerResult;
                while (joiner.hasNext()) {
                    joinerResult = joiner.next();
                    switch (joinerResult) {
                    case LEFT:
                        // handle case where a row in cursor1 is unique
                        if (currentCRSmsDraft == CR_SMS_DRAFT_A) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " SMS DELETED FROM DRAFT ");
                            String body = crSmsDraftA.getString(crSmsDraftA
                                    .getColumnIndex("body"));
                            String id = crSmsDraftA.getString(crSmsDraftA
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED SMS ID " + id + " BODY "
                                    + body);

                            int msgType = getMessageType(id);
                            if (msgType == -1) {
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/DRAFT", null, "SMS_GSM");
                            } else {
                                String newFolder = getMAPFolder(msgType);
                                sendMnsEvent(MESSAGE_SHIFT, id, "TELECOM/MSG/"
                                        + newFolder, "TELECOM/MSG/DRAFT",
                                        "SMS_GSM");
                                if ( newFolder.equalsIgnoreCase("sent")) {
                                    sendMnsEvent(SENDING_SUCCESS, id,
                                            "TELECOM/MSG/" + newFolder,
                                            null, "SMS_GSM");
                                }
                            }

                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case RIGHT:
                        // handle case where a row in cursor2 is unique
                        if (currentCRSmsDraft == CR_SMS_DRAFT_B) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " SMS DELETED FROM DRAFT ");
                            String body = crSmsDraftB.getString(crSmsDraftB
                                    .getColumnIndex("body"));
                            String id = crSmsDraftB.getString(crSmsDraftB
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED SMS ID " + id + " BODY "
                                    + body);
                            int msgType = getMessageType(id);
                            if (msgType == -1) {
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/DRAFT", null, "SMS_GSM");
                            } else {
                                String newFolder = getMAPFolder(msgType);
                                sendMnsEvent(MESSAGE_SHIFT, id, "TELECOM/MSG/"
                                        + newFolder, "TELECOM/MSG/DRAFT",
                                        "SMS_GSM");
                                if ( newFolder.equalsIgnoreCase("sent")) {
                                    sendMnsEvent(SENDING_SUCCESS, id,
                                            "TELECOM/MSG/" + newFolder,
                                            null, "SMS_GSM");
                                }
                            }

                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case BOTH:
                        // handle case where a row with the same key is in both
                        // cursors
                        break;
                    }
                }
            }
            if (currentCRSmsDraft == CR_SMS_DRAFT_A) {
                currentCRSmsDraft = CR_SMS_DRAFT_B;
            } else {
                currentCRSmsDraft = CR_SMS_DRAFT_A;
            }
        }
    }

    /**
     * This class listens for changes in Sms Content Provider's Outbox table
     * It acts only when a entry gets removed from the table
     */
    private class OutboxContentObserverClass extends ContentObserver {

        public OutboxContentObserverClass() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            int currentItemCount = 0;
            int newItemCount = 0;

            // Check MMS Outbox for changes

            checkMmsOutbox();

            // Check SMS Outbox for changes

            if (currentCRSmsOutbox == CR_SMS_OUTBOX_A) {
                currentItemCount = crSmsOutboxA.getCount();
                crSmsOutboxB.requery();
                newItemCount = crSmsOutboxB.getCount();
            } else {
                currentItemCount = crSmsOutboxB.getCount();
                crSmsOutboxA.requery();
                newItemCount = crSmsOutboxA.getCount();
            }

            Log.d(TAG, "SMS OUTBOX current " + currentItemCount + " new "
                    + newItemCount);

            if (currentItemCount > newItemCount) {
                crSmsOutboxA.moveToFirst();
                crSmsOutboxB.moveToFirst();

                CursorJoiner joiner = new CursorJoiner(crSmsOutboxA,
                        new String[] { "_id" }, crSmsOutboxB,
                        new String[] { "_id" });

                CursorJoiner.Result joinerResult;
                while (joiner.hasNext()) {
                    joinerResult = joiner.next();
                    switch (joinerResult) {
                    case LEFT:
                        // handle case where a row in cursor1 is unique
                        if (currentCRSmsOutbox == CR_SMS_OUTBOX_A) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " SMS DELETED FROM OUTBOX ");
                            String body = crSmsOutboxA.getString(crSmsOutboxA
                                    .getColumnIndex("body"));
                            String id = crSmsOutboxA.getString(crSmsOutboxA
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED SMS ID " + id + " BODY "
                                    + body);
                            int msgType = getMessageType(id);
                            if (msgType == -1) {
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/OUTBOX", null, "SMS_GSM");
                            } else {
                                String newFolder = getMAPFolder(msgType);
                                if ((newFolder != null)
                                        && (!newFolder
                                                .equalsIgnoreCase("outbox"))) {
                                    // The message has moved on MAP virtual
                                    // folder representation.
                                    sendMnsEvent(MESSAGE_SHIFT, id,
                                            "TELECOM/MSG/" + newFolder,
                                            "TELECOM/MSG/OUTBOX", "SMS_GSM");
                                    if ( newFolder.equalsIgnoreCase("sent")) {
                                        sendMnsEvent(SENDING_SUCCESS, id,
                                                "TELECOM/MSG/" + newFolder,
                                                null, "SMS_GSM");
                                    }
                                }
                                if ( (msgType == MSG_CP_QUEUED_TYPE) ||
                                        (msgType == MSG_CP_FAILED_TYPE)) {
                                    // Message moved from outbox to queue or
                                    // failed folder
                                    sendMnsEvent(SENDING_FAILURE, id,
                                            "TELECOM/MSG/OUTBOX", null, "SMS_GSM");
                                }
                            }

                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case RIGHT:
                        // handle case where a row in cursor2 is unique
                        if (currentCRSmsOutbox == CR_SMS_OUTBOX_B) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " SMS DELETED FROM OUTBOX ");
                            String body = crSmsOutboxB.getString(crSmsOutboxB
                                    .getColumnIndex("body"));
                            String id = crSmsOutboxB.getString(crSmsOutboxB
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED SMS ID " + id + " BODY "
                                    + body);
                            int msgType = getMessageType(id);
                            if (msgType == -1) {
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/OUTBOX", null, "SMS_GSM");
                            } else {
                                String newFolder = getMAPFolder(msgType);
                                if ((newFolder != null)
                                        && (!newFolder
                                                .equalsIgnoreCase("outbox"))) {
                                    // The message has moved on MAP virtual
                                    // folder representation.
                                    sendMnsEvent(MESSAGE_SHIFT, id,
                                            "TELECOM/MSG/" + newFolder,
                                            "TELECOM/MSG/OUTBOX", "SMS_GSM");
                                }
                            }

                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case BOTH:
                        // handle case where a row with the same key is in both
                        // cursors
                        break;
                    }
                }
            }
            if (currentCRSmsOutbox == CR_SMS_OUTBOX_A) {
                currentCRSmsOutbox = CR_SMS_OUTBOX_B;
            } else {
                currentCRSmsOutbox = CR_SMS_OUTBOX_A;
            }
        }
    }

    /**
     * This class listens for changes in Sms Content Provider's Failed table
     * It acts only when a entry gets removed from the table
     */
    private class FailedContentObserverClass extends ContentObserver {

        public FailedContentObserverClass() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            int currentItemCount = 0;
            int newItemCount = 0;

            // Mms doesn't have Failed type

            if (currentCRSmsFailed == CR_SMS_FAILED_A) {
                currentItemCount = crSmsFailedA.getCount();
                crSmsFailedB.requery();
                newItemCount = crSmsFailedB.getCount();
            } else {
                currentItemCount = crSmsFailedB.getCount();
                crSmsFailedA.requery();
                newItemCount = crSmsFailedA.getCount();
            }

            Log.d(TAG, "SMS FAILED current " + currentItemCount + " new "
                    + newItemCount);

            if (currentItemCount > newItemCount) {
                crSmsFailedA.moveToFirst();
                crSmsFailedB.moveToFirst();

                CursorJoiner joiner = new CursorJoiner(crSmsFailedA,
                        new String[] { "_id" }, crSmsFailedB,
                        new String[] { "_id" });

                CursorJoiner.Result joinerResult;
                while (joiner.hasNext()) {
                    joinerResult = joiner.next();
                    switch (joinerResult) {
                    case LEFT:
                        // handle case where a row in cursor1 is unique
                        if (currentCRSmsFailed == CR_SMS_FAILED_A) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " SMS DELETED FROM FAILED ");
                            String body = crSmsFailedA.getString(crSmsFailedA
                                    .getColumnIndex("body"));
                            String id = crSmsFailedA.getString(crSmsFailedA
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED SMS ID " + id + " BODY "
                                    + body);
                            int msgType = getMessageType(id);
                            if (msgType == -1) {
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/OUTBOX", null, "SMS_GSM");
                            } else {
                                String newFolder = getMAPFolder(msgType);
                                if ((newFolder != null)
                                        && (!newFolder
                                                .equalsIgnoreCase("outbox"))) {
                                    // The message has moved on MAP virtual
                                    // folder representation.
                                    sendMnsEvent(MESSAGE_SHIFT, id,
                                            "TELECOM/MSG/" + newFolder,
                                            "TELECOM/MSG/OUTBOX", "SMS_GSM");
                                    if ( newFolder.equalsIgnoreCase("sent")) {
                                        sendMnsEvent(SENDING_SUCCESS, id,
                                                "TELECOM/MSG/" + newFolder,
                                                null, "SMS_GSM");
                                    }
                                }
                            }

                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case RIGHT:
                        // handle case where a row in cursor2 is unique
                        if (currentCRSmsFailed == CR_SMS_FAILED_B) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " SMS DELETED FROM FAILED ");
                            String body = crSmsFailedB.getString(crSmsFailedB
                                    .getColumnIndex("body"));
                            String id = crSmsFailedB.getString(crSmsFailedB
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED SMS ID " + id + " BODY "
                                    + body);
                            int msgType = getMessageType(id);
                            if (msgType == -1) {
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/OUTBOX", null, "SMS_GSM");
                            } else {
                                String newFolder = getMAPFolder(msgType);
                                if ((newFolder != null)
                                        && (!newFolder
                                                .equalsIgnoreCase("outbox"))) {
                                    // The message has moved on MAP virtual
                                    // folder representation.
                                    sendMnsEvent(MESSAGE_SHIFT, id,
                                            "TELECOM/MSG/" + newFolder,
                                            "TELECOM/MSG/OUTBOX", "SMS_GSM");
                                    if ( newFolder.equalsIgnoreCase("sent")) {
                                        sendMnsEvent(SENDING_SUCCESS, id,
                                                "TELECOM/MSG/" + newFolder,
                                                null, "SMS_GSM");
                                    }
                                }
                            }
                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case BOTH:
                        // handle case where a row with the same key is in both
                        // cursors
                        break;
                    }
                }
            }
            if (currentCRSmsFailed == CR_SMS_FAILED_A) {
                currentCRSmsFailed = CR_SMS_FAILED_B;
            } else {
                currentCRSmsFailed = CR_SMS_FAILED_A;
            }
        }
    }

    /**
     * This class listens for changes in Sms Content Provider's Queued table
     * It acts only when a entry gets removed from the table
     */
    private class QueuedContentObserverClass extends ContentObserver {

        public QueuedContentObserverClass() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            int currentItemCount = 0;
            int newItemCount = 0;

            // Mms doesn't have Queued type

            if (currentCRSmsQueued == CR_SMS_QUEUED_A) {
                currentItemCount = crSmsQueuedA.getCount();
                crSmsQueuedB.requery();
                newItemCount = crSmsQueuedB.getCount();
            } else {
                currentItemCount = crSmsQueuedB.getCount();
                crSmsQueuedA.requery();
                newItemCount = crSmsQueuedA.getCount();
            }

            Log.d(TAG, "SMS QUEUED current " + currentItemCount + " new "
                    + newItemCount);

            if (currentItemCount > newItemCount) {
                crSmsQueuedA.moveToFirst();
                crSmsQueuedB.moveToFirst();

                CursorJoiner joiner = new CursorJoiner(crSmsQueuedA,
                        new String[] { "_id" }, crSmsQueuedB,
                        new String[] { "_id" });

                CursorJoiner.Result joinerResult;
                while (joiner.hasNext()) {
                    joinerResult = joiner.next();
                    switch (joinerResult) {
                    case LEFT:
                        // handle case where a row in cursor1 is unique
                        if (currentCRSmsQueued == CR_SMS_QUEUED_A) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " SMS DELETED FROM QUEUED ");
                            String body = crSmsQueuedA.getString(crSmsQueuedA
                                    .getColumnIndex("body"));
                            String id = crSmsQueuedA.getString(crSmsQueuedA
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED SMS ID " + id + " BODY "
                                    + body);
                            int msgType = getMessageType(id);
                            if (msgType == -1) {
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/OUTBOX", null, "SMS_GSM");
                            } else {
                                String newFolder = getMAPFolder(msgType);
                                if ((newFolder != null)
                                        && (!newFolder
                                                .equalsIgnoreCase("outbox"))) {
                                    // The message has moved on MAP virtual
                                    // folder representation.
                                    sendMnsEvent(MESSAGE_SHIFT, id,
                                            "TELECOM/MSG/" + newFolder,
                                            "TELECOM/MSG/OUTBOX", "SMS_GSM");
                                    if ( newFolder.equalsIgnoreCase("sent")) {
                                        sendMnsEvent(SENDING_SUCCESS, id,
                                                "TELECOM/MSG/" + newFolder,
                                                null, "SMS_GSM");
                                    }
                                }
                            }
                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case RIGHT:
                        // handle case where a row in cursor2 is unique
                        if (currentCRSmsQueued == CR_SMS_QUEUED_B) {
                            // The new query doesn't have this row; implies it
                            // was deleted
                            Log.d(TAG, " SMS DELETED FROM QUEUED ");
                            String body = crSmsQueuedB.getString(crSmsQueuedB
                                    .getColumnIndex("body"));
                            String id = crSmsQueuedB.getString(crSmsQueuedB
                                    .getColumnIndex("_id"));
                            Log.d(TAG, " DELETED SMS ID " + id + " BODY "
                                    + body);
                            int msgType = getMessageType(id);
                            if (msgType == -1) {
                                sendMnsEvent(MESSAGE_DELETED, id,
                                        "TELECOM/MSG/OUTBOX", null, "SMS_GSM");
                            } else {
                                String newFolder = getMAPFolder(msgType);
                                if ((newFolder != null)
                                        && (!newFolder
                                                .equalsIgnoreCase("outbox"))) {
                                    // The message has moved on MAP virtual
                                    // folder representation.
                                    sendMnsEvent(MESSAGE_SHIFT, id,
                                            "TELECOM/MSG/" + newFolder,
                                            "TELECOM/MSG/OUTBOX", "SMS_GSM");
                                    if ( newFolder.equalsIgnoreCase("sent")) {
                                        sendMnsEvent(SENDING_SUCCESS, id,
                                                "TELECOM/MSG/" + newFolder,
                                                null, "SMS_GSM");
                                    }
                                }
                            }

                        } else {
                            // The current(old) query doesn't have this row;
                            // implies it was added
                        }
                        break;
                    case BOTH:
                        // handle case where a row with the same key is in both
                        // cursors
                        break;
                    }
                }
            }
            if (currentCRSmsQueued == CR_SMS_QUEUED_A) {
                currentCRSmsQueued = CR_SMS_QUEUED_B;
            } else {
                currentCRSmsQueued = CR_SMS_QUEUED_A;
            }
        }
    }

    /**
     * Start MNS connection
     */
    public void start(BluetoothDevice mRemoteDevice) {
        /* check Bluetooth enable status */
        /*
         * normally it's impossible to reach here if BT is disabled. Just check
         * for safety
         */
        if (!mAdapter.isEnabled()) {
            Log.e(TAG, "Can't send event when Bluetooth is disabled ");
            return;
        }

        mDestination = mRemoteDevice;
        int channel = -1;
        // TODO Fix this below

        if (channel != -1) {
            if (D) Log.d(TAG, "Get MNS channel " + channel + " from cache for "
                    + mDestination);
            mTimestamp = System.currentTimeMillis();
            mSessionHandler.obtainMessage(SDP_RESULT, channel, -1, mDestination)
                    .sendToTarget();
        } else {
            sendMnsSdp();
        }
    }

    /**
     * Stop the transfer
     */
    public void stop() {
        if (V)
            Log.v(TAG, "stop");
        if (mConnectThread != null) {
            try {
                mConnectThread.interrupt();
                if (V) Log.v(TAG, "waiting for connect thread to terminate");
                mConnectThread.join();
            } catch (InterruptedException e) {
                if (V) Log.v(TAG,
                        "Interrupted waiting for connect thread to join");
            }
            mConnectThread = null;
        }
        if (mSession != null) {
            if (V)
                Log.v(TAG, "Stop mSession");
            mSession.disconnect();
            mSession = null;
        }
        // TODO Do this somewhere else - Should the handler thread be gracefully closed.
    }

    /**
     * Connect the MNS Obex client to remote server
     */
    private void startObexSession() {

        if (V)
            Log.v(TAG, "Create Client session with transport "
                    + mTransport.toString());
        mSession = new BluetoothMnsObexSession(mContext, mTransport);
        mSession.connect();
    }

    /**
     * Check if local database contains remote device's info
     * Else start sdp query
     */
    private void sendMnsSdp() {
        if (V)
            Log.v(TAG, "Do Opush SDP request for address " + mDestination);

        mTimestamp = System.currentTimeMillis();

        int channel = -1;

        Method m;
        try {
            m = mDestination.getClass().getMethod("getServiceChannel",
                    new Class[] { ParcelUuid.class });
            channel = (Integer) m.invoke(mDestination, BluetoothUuid_ObexMns);
        } catch (SecurityException e2) {
            // TODO Auto-generated catch block
            e2.printStackTrace();
        } catch (NoSuchMethodException e2) {
            // TODO Auto-generated catch block
            e2.printStackTrace();
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

        // TODO: channel = mDestination.getServiceChannel(BluetoothUuid_ObexMns);

        if (channel != -1) {
            if (D)
                Log.d(TAG, "Get MNS channel " + channel + " from SDP for "
                        + mDestination);

            mSessionHandler
                    .obtainMessage(SDP_RESULT, channel, -1, mDestination)
                    .sendToTarget();
            return;

        } else {

            boolean result = false;
            if (V)
                Log.v(TAG, "Remote Service channel not in cache");

            Method m2;
            try {
                m2 = mDestination.getClass().getMethod("fetchUuidsWithSdp",
                        new Class[] {});
                result = (Boolean) m2.invoke(mDestination);

            } catch (SecurityException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            } catch (NoSuchMethodException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
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

            if (result == false) {
                Log.e(TAG, "Start SDP query failed");
            } else {
                // we expect framework send us Intent ACTION_UUID. otherwise we
                // will fail
                if (V)
                    Log.v(TAG, "Start new SDP, wait for result");
                IntentFilter intentFilter = new IntentFilter(
                        "android.bleutooth.device.action.UUID");
                mContext.registerReceiver(mReceiver, intentFilter);
                return;
            }
        }
        Message msg = mSessionHandler.obtainMessage(SDP_RESULT, channel, -1,
                mDestination);
        mSessionHandler.sendMessageDelayed(msg, 2000);
    }

    /**
     * Receives the response of SDP query
     */
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            Log.d(TAG, " MNS BROADCAST RECV intent: " + intent.getAction());

            if (intent.getAction().equals(
                    "android.bleutooth.device.action.UUID")) {
                BluetoothDevice device = intent
                        .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (V)
                    Log.v(TAG, "ACTION_UUID for device " + device);
                if (device.equals(mDestination)) {
                    int channel = -1;
                    Parcelable[] uuid = intent
                            .getParcelableArrayExtra("android.bluetooth.device.extra.UUID");
                    if (uuid != null) {
                        ParcelUuid[] uuids = new ParcelUuid[uuid.length];
                        for (int i = 0; i < uuid.length; i++) {
                            uuids[i] = (ParcelUuid) uuid[i];
                        }

                        boolean result = false;

                        // TODO Fix this error
                        result = true;

                        try {
                            Class c = Class
                                    .forName("android.bluetooth.BluetoothUuid");
                            Method m = c.getMethod("isUuidPresent",
                                    new Class[] { ParcelUuid[].class,
                                            ParcelUuid.class });

                            Boolean bool = false;
                            bool = (Boolean) m.invoke(c, uuids,
                                    BluetoothUuid_ObexMns);
                            result = bool.booleanValue();

                        } catch (ClassNotFoundException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        } catch (SecurityException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        } catch (NoSuchMethodException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
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

                        if (result) {
                            // TODO: Check if UUID IS PRESENT
                            if (V)
                                Log.v(TAG, "SDP get MNS result for device "
                                        + device);

                            // TODO: Get channel from mDestination
                            // TODO: .getServiceChannel(BluetoothUuid_ObexMns);
                            Method m1;
                            try {

                                m1 = device.getClass().getMethod(
                                        "getServiceChannel",
                                        new Class[] { ParcelUuid.class });
                                Integer chan = (Integer) m1.invoke(device,
                                        BluetoothUuid_ObexMns);

                                channel = chan.intValue();
                                Log.d(TAG, " MNS SERVER Channel no " + channel);
                                if (channel == -1) {
                                    channel = 2;
                                    Log.d(TAG, " MNS SERVER USE TEMP CHANNEL "
                                            + channel);
                                }
                            } catch (SecurityException e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
                            } catch (NoSuchMethodException e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
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
                        }
                    }
                    mSessionHandler.obtainMessage(SDP_RESULT, channel, -1,
                            mDestination).sendToTarget();
                }
            }
        }
    };

    private SocketConnectThread mConnectThread;

    /**
     * This thread is used to establish rfcomm connection to
     * remote device
     */
    private class SocketConnectThread extends Thread {
        private final String host;

        private final BluetoothDevice device;

        private final int channel;

        private boolean isConnected;

        private long timestamp;

        private BluetoothSocket btSocket = null;

        /* create a Rfcomm Socket */
        public SocketConnectThread(BluetoothDevice device, int channel) {
            super("Socket Connect Thread");
            this.device = device;
            this.host = null;
            this.channel = channel;
            isConnected = false;
        }

        public void interrupt() {
        }

        @Override
        public void run() {

            timestamp = System.currentTimeMillis();

            /* Use BluetoothSocket to connect */
            try {
                try {
                    Method m = device.getClass().getMethod(
                            "createInsecureRfcommSocket",
                            new Class[] { int.class });
                    btSocket = (BluetoothSocket) m.invoke(device, channel);
                } catch (SecurityException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (NoSuchMethodException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
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
            } catch (Exception e1) {
                // TODO Auto-generated catch block
                Log.e(TAG, "Rfcomm socket create error");
                markConnectionFailed(btSocket);
                return;
            }

            try {
                btSocket.connect();
                if (V) Log.v(TAG, "Rfcomm socket connection attempt took "
                        + (System.currentTimeMillis() - timestamp) + " ms");
                BluetoothMnsRfcommTransport transport;
                transport = new BluetoothMnsRfcommTransport(btSocket);

                BluetoothMnsPreference.getInstance(mContext).setChannel(device,
                        MNS_UUID16, channel);
                BluetoothMnsPreference.getInstance(mContext).setName(device,
                        device.getName());

                if (V) Log.v(TAG, "Send transport message "
                        + transport.toString());

                mSessionHandler.obtainMessage(RFCOMM_CONNECTED, transport)
                        .sendToTarget();
            } catch (IOException e) {
                Log.e(TAG, "Rfcomm socket connect exception with error: " + e.getMessage());
                BluetoothMnsPreference.getInstance(mContext).removeChannel(
                        device, MNS_UUID16);
                markConnectionFailed(btSocket);
                return;
            }
        }

        /**
         * RFCOMM connection failed
         */
        private void markConnectionFailed(Socket s) {
            try {
                s.close();
            } catch (IOException e) {
                Log.e(TAG, "TCP socket close error");
            }
            mSessionHandler.obtainMessage(RFCOMM_ERROR).sendToTarget();
        }

        /**
         * RFCOMM connection failed
         */
        private void markConnectionFailed(BluetoothSocket s) {
            try {
                s.close();
            } catch (IOException e) {
                if (V) Log.e(TAG, "Error when close socket");
            }
            mSessionHandler.obtainMessage(RFCOMM_ERROR).sendToTarget();
            return;
        }
    }

    /**
     * Check for change in MMS outbox and send a notification if there is a
     * change
     */
    private void checkMmsOutbox() {

        int currentItemCount = 0;
        int newItemCount = 0;

        if (currentCRMmsOutbox == CR_MMS_OUTBOX_A) {
            currentItemCount = crMmsOutboxA.getCount();
            crMmsOutboxB.requery();
            newItemCount = crMmsOutboxB.getCount();
        } else {
            currentItemCount = crMmsOutboxB.getCount();
            crMmsOutboxA.requery();
            newItemCount = crMmsOutboxA.getCount();
        }

        Log.d(TAG, "MMS OUTBOX current " + currentItemCount + " new "
                + newItemCount);

        if (currentItemCount > newItemCount) {
            crMmsOutboxA.moveToFirst();
            crMmsOutboxB.moveToFirst();

            CursorJoiner joiner = new CursorJoiner(crMmsOutboxA,
                    new String[] { "_id" }, crMmsOutboxB,
                    new String[] { "_id" });

            CursorJoiner.Result joinerResult;
            while (joiner.hasNext()) {
                joinerResult = joiner.next();
                switch (joinerResult) {
                case LEFT:
                    // handle case where a row in cursor1 is unique
                    if (currentCRMmsOutbox == CR_MMS_OUTBOX_A) {
                        // The new query doesn't have this row; implies it
                        // was deleted
                        Log.d(TAG, " MMS DELETED FROM OUTBOX ");
                        String id = crMmsOutboxA.getString(crMmsOutboxA
                                .getColumnIndex("_id"));
                        int msgType = getMmsContainingFolder(Integer
                                .parseInt(id));
                        if (msgType == -1) {
                            // Convert to virtual handle for MMS
                            id = Integer.toString(Integer.valueOf(id)
                                    + MMS_HDLR_CONSTANT);

                            Log.d(TAG, " DELETED MMS ID " + id);
                            sendMnsEvent(MESSAGE_DELETED, id,
                                    "TELECOM/MSG/OUTBOX", null, "MMS");
                        } else {
                            String newFolder = getMAPFolder(msgType);
                            // Convert to virtual handle for MMS
                            id = Integer.toString(Integer.valueOf(id)
                                    + MMS_HDLR_CONSTANT);

                            Log.d(TAG, " MESSAGE_SHIFT MMS ID " + id);
                            if ((newFolder != null)
                                    && (!newFolder.equalsIgnoreCase("outbox"))) {
                                // The message has moved on MAP virtual
                                // folder representation.
                                sendMnsEvent(MESSAGE_SHIFT, id, "TELECOM/MSG/"
                                        + newFolder, "TELECOM/MSG/OUTBOX",
                                        "MMS");
                                if ( newFolder.equalsIgnoreCase("sent")) {
                                    sendMnsEvent(SENDING_SUCCESS, id,
                                            "TELECOM/MSG/" + newFolder,
                                            null, "MMS");
                                }
                            }
                            /* Mms doesn't have failed or queued type
                             * Cannot send SENDING_FAILURE as there
                             * is no indication if Sending failed
                             */
                        }

                    } else {
                        // The current(old) query doesn't have this row;
                        // implies it was added
                    }
                    break;
                case RIGHT:
                    // handle case where a row in cursor2 is unique
                    if (currentCRMmsOutbox == CR_MMS_OUTBOX_B) {
                        // The new query doesn't have this row; implies it
                        // was deleted
                        Log.d(TAG, " MMS DELETED FROM OUTBOX ");
                        String id = crMmsOutboxB.getString(crMmsOutboxB
                                .getColumnIndex("_id"));
                        int msgType = getMmsContainingFolder(Integer
                                .parseInt(id));
                        if (msgType == -1) {
                            // Convert to virtual handle for MMS
                            id = Integer.toString(Integer.valueOf(id)
                                    + MMS_HDLR_CONSTANT);
                            Log.d(TAG, " DELETED MMS ID " + id);
                            sendMnsEvent(MESSAGE_DELETED, id,
                                    "TELECOM/MSG/OUTBOX", null, "MMS");
                        } else {
                            // Convert to virtual handle for MMS
                            id = Integer.toString(Integer.valueOf(id)
                                    + MMS_HDLR_CONSTANT);
                            Log.d(TAG, " DELETED MMS ID " + id);
                            String newFolder = getMAPFolder(msgType);
                            if ((newFolder != null)
                                    && (!newFolder.equalsIgnoreCase("outbox"))) {
                                // The message has moved on MAP virtual
                                // folder representation.
                                sendMnsEvent(MESSAGE_SHIFT, id, "TELECOM/MSG/"
                                        + newFolder, "TELECOM/MSG/OUTBOX",
                                        "MMS");
                                if ( newFolder.equalsIgnoreCase("sent")) {
                                    sendMnsEvent(SENDING_SUCCESS, id,
                                            "TELECOM/MSG/" + newFolder,
                                            null, "MMS");
                                }
                            }
                            /* Mms doesn't have failed or queued type
                             * Cannot send SENDING_FAILURE as there
                             * is no indication if Sending failed
                             */
                        }

                    } else {
                        // The current(old) query doesn't have this row;
                        // implies it was added
                    }
                    break;
                case BOTH:
                    // handle case where a row with the same key is in both
                    // cursors
                    break;
                }
            }
        }
        if (currentCRMmsOutbox == CR_MMS_OUTBOX_A) {
            currentCRMmsOutbox = CR_MMS_OUTBOX_B;
        } else {
            currentCRMmsOutbox = CR_MMS_OUTBOX_A;
        }
    }

    /**
     * Check for change in MMS Drafts folder and send a notification if there is
     * a change
     */
    private void checkMmsDrafts() {

        int currentItemCount = 0;
        int newItemCount = 0;

        if (currentCRMmsDraft == CR_MMS_OUTBOX_A) {
            currentItemCount = crMmsDraftA.getCount();
            crMmsDraftA.requery();
            newItemCount = crMmsDraftB.getCount();
        } else {
            currentItemCount = crMmsDraftB.getCount();
            crMmsDraftA.requery();
            newItemCount = crMmsDraftA.getCount();
        }

        if (newItemCount != 0) {
            Log.d(TAG, "MMS DRAFT breakpoint placeholder");
        }

        Log.d(TAG, "MMS DRAFT current " + currentItemCount + " new "
                + newItemCount);

        if (currentItemCount > newItemCount) {
            crMmsDraftA.moveToFirst();
            crMmsDraftB.moveToFirst();

            CursorJoiner joiner = new CursorJoiner(crMmsDraftA,
                    new String[] { "_id" }, crMmsDraftB, new String[] { "_id" });

            CursorJoiner.Result joinerResult;
            while (joiner.hasNext()) {
                joinerResult = joiner.next();
                switch (joinerResult) {
                case LEFT:
                    // handle case where a row in cursor1 is unique
                    if (currentCRMmsDraft == CR_MMS_DRAFT_A) {
                        // The new query doesn't have this row; implies it
                        // was deleted
                        Log.d(TAG, " MMS DELETED FROM DRAFT ");
                        String id = crMmsDraftA.getString(crMmsDraftA
                                .getColumnIndex("_id"));
                        int msgType = getMmsContainingFolder(Integer
                                .parseInt(id));
                        if (msgType == -1) {
                            // Convert to virtual handle for MMS
                            id = Integer.toString(Integer.valueOf(id)
                                    + MMS_HDLR_CONSTANT);
                            Log.d(TAG, " DELETED MMS ID " + id);
                            sendMnsEvent(MESSAGE_DELETED, id,
                                    "TELECOM/MSG/DRAFT", null, "MMS");
                        } else {
                            // Convert to virtual handle for MMS
                            id = Integer.toString(Integer.valueOf(id)
                                    + MMS_HDLR_CONSTANT);
                            Log.d(TAG, " DELETED MMS ID " + id);
                            String newFolder = getMAPFolder(msgType);
                            if ((newFolder != null)
                                    && (!newFolder.equalsIgnoreCase("draft"))) {
                                // The message has moved on MAP virtual
                                // folder representation.
                                sendMnsEvent(MESSAGE_SHIFT, id, "TELECOM/MSG/"
                                        + newFolder, "TELECOM/MSG/DRAFT", "MMS");
                                if ( newFolder.equalsIgnoreCase("sent")) {
                                    sendMnsEvent(SENDING_SUCCESS, id,
                                            "TELECOM/MSG/" + newFolder,
                                            null, "MMS");
                                }
                            }
                        }

                    } else {
                        // The current(old) query doesn't have this row;
                        // implies it was added
                    }
                    break;
                case RIGHT:
                    // handle case where a row in cursor2 is unique
                    if (currentCRMmsDraft == CR_MMS_DRAFT_B) {
                        // The new query doesn't have this row; implies it
                        // was deleted
                        Log.d(TAG, " MMS DELETED FROM DRAFT ");
                        String id = crMmsDraftB.getString(crMmsDraftB
                                .getColumnIndex("_id"));
                        int msgType = getMmsContainingFolder(Integer
                                .parseInt(id));
                        if (msgType == -1) {
                            // Convert to virtual handle for MMS
                            id = Integer.toString(Integer.valueOf(id)
                                    + MMS_HDLR_CONSTANT);
                            Log.d(TAG, " DELETED MMS ID " + id);
                            sendMnsEvent(MESSAGE_DELETED, id,
                                    "TELECOM/MSG/DRAFT", null, "MMS");
                        } else {
                            // Convert to virtual handle for MMS
                            id = Integer.toString(Integer.valueOf(id)
                                    + MMS_HDLR_CONSTANT);
                            Log.d(TAG, " DELETED MMS ID " + id);
                            String newFolder = getMAPFolder(msgType);
                            if ((newFolder != null)
                                    && (!newFolder.equalsIgnoreCase("draft"))) {
                                // The message has moved on MAP virtual
                                // folder representation.
                                sendMnsEvent(MESSAGE_SHIFT, id, "TELECOM/MSG/"
                                        + newFolder, "TELECOM/MSG/DRAFT", "MMS");
                                if ( newFolder.equalsIgnoreCase("sent")) {
                                    sendMnsEvent(SENDING_SUCCESS, id,
                                            "TELECOM/MSG/" + newFolder,
                                            null, "MMS");
                                }
                            }
                        }

                    } else {
                        // The current(old) query doesn't have this row;
                        // implies it was added
                    }
                    break;
                case BOTH:
                    // handle case where a row with the same key is in both
                    // cursors
                    break;
                }
            }
        }
        if (currentCRMmsDraft == CR_MMS_DRAFT_A) {
            currentCRMmsDraft = CR_MMS_DRAFT_B;
        } else {
            currentCRMmsDraft = CR_MMS_DRAFT_A;
        }
    }

    /**
     * Check for change in MMS Inbox folder and send a notification if there is
     * a change
     */
    private void checkMmsInbox() {

        int currentItemCount = 0;
        int newItemCount = 0;

        if (currentCRMmsInbox == CR_MMS_INBOX_A) {
            currentItemCount = crMmsInboxA.getCount();
            crMmsInboxB.requery();
            newItemCount = crMmsInboxB.getCount();
        } else {
            currentItemCount = crMmsInboxB.getCount();
            crMmsInboxA.requery();
            newItemCount = crMmsInboxA.getCount();
        }

        Log.d(TAG, "MMS INBOX current " + currentItemCount + " new "
                + newItemCount);

        if (currentItemCount > newItemCount) {
            crMmsInboxA.moveToFirst();
            crMmsInboxB.moveToFirst();

            CursorJoiner joiner = new CursorJoiner(crMmsInboxA,
                    new String[] { "_id" }, crMmsInboxB, new String[] { "_id" });

            CursorJoiner.Result joinerResult;
            while (joiner.hasNext()) {
                joinerResult = joiner.next();
                switch (joinerResult) {
                case LEFT:
                    // handle case where a row in cursor1 is unique
                    if (currentCRMmsInbox == CR_MMS_INBOX_A) {
                        // The new query doesn't have this row; implies it
                        // was deleted
                        Log.d(TAG, " MMS DELETED FROM INBOX ");
                        String id = crMmsInboxA.getString(crMmsInboxA
                                .getColumnIndex("_id"));
                        int msgType = getMmsContainingFolder(Integer
                                .parseInt(id));
                        if (msgType == -1) {
                            // Convert to virtual handle for MMS
                            id = Integer.toString(Integer.valueOf(id)
                                    + MMS_HDLR_CONSTANT);
                            Log.d(TAG, " DELETED MMS ID " + id);
                            sendMnsEvent(MESSAGE_DELETED, id,
                                    "TELECOM/MSG/INBOX", null, "MMS");
                        } else {
                            Log.d(TAG, "Shouldn't reach here as you cannot "
                                    + "move msg from Inbox to any other folder");
                        }
                    } else {
                        // TODO - The current(old) query doesn't have this
                        // row;
                        // implies it was added
                    }
                    break;
                case RIGHT:
                    // handle case where a row in cursor2 is unique
                    if (currentCRMmsInbox == CR_MMS_INBOX_B) {
                        // The new query doesn't have this row; implies it
                        // was deleted
                        Log.d(TAG, " MMS DELETED FROM INBOX ");
                        String id = crMmsInboxB.getString(crMmsInboxB
                                .getColumnIndex("_id"));
                        int msgType = getMmsContainingFolder(Integer
                                .parseInt(id));
                        if (msgType == -1) {
                            // Convert to virtual handle for MMS
                            id = Integer.toString(Integer.valueOf(id)
                                    + MMS_HDLR_CONSTANT);
                            Log.d(TAG, " DELETED MMS ID " + id);
                            sendMnsEvent(MESSAGE_DELETED, id,
                                    "TELECOM/MSG/INBOX", null, "MMS");
                        } else {
                            Log.d(TAG, "Shouldn't reach here as you cannot "
                                    + "move msg from Inbox to any other folder");
                        }
                    } else {
                        // The current(old) query doesn't have this row;
                        // implies it was added
                    }
                    break;
                case BOTH:
                    // handle case where a row with the same key is in both
                    // cursors
                    break;
                }
            }
        }
        if (currentCRMmsInbox == CR_MMS_INBOX_A) {
            currentCRMmsInbox = CR_MMS_INBOX_B;
        } else {
            currentCRMmsInbox = CR_MMS_INBOX_A;
        }
    }
    /**
     * Check for change in MMS Sent folder and send a notification if there is
     * a change
     */
    private void checkMmsSent() {

        int currentItemCount = 0;
        int newItemCount = 0;

        if (currentCRMmsSent == CR_MMS_SENT_A) {
            currentItemCount = crMmsSentA.getCount();
            crMmsSentB.requery();
            newItemCount = crMmsSentB.getCount();
        } else {
            currentItemCount = crMmsSentB.getCount();
            crMmsSentA.requery();
            newItemCount = crMmsSentA.getCount();
        }

        Log.d(TAG, "MMS SENT current " + currentItemCount + " new "
                + newItemCount);

        if (currentItemCount > newItemCount) {
            crMmsSentA.moveToFirst();
            crMmsSentB.moveToFirst();

            CursorJoiner joiner = new CursorJoiner(crMmsSentA,
                    new String[] { "_id" }, crMmsSentB, new String[] { "_id" });

            CursorJoiner.Result joinerResult;
            while (joiner.hasNext()) {
                joinerResult = joiner.next();
                switch (joinerResult) {
                case LEFT:
                    // handle case where a row in cursor1 is unique
                    if (currentCRMmsSent == CR_MMS_SENT_A) {
                        // The new query doesn't have this row; implies it
                        // was deleted
                        Log.d(TAG, " MMS DELETED FROM SENT ");
                        String id = crMmsSentA.getString(crMmsSentA
                                .getColumnIndex("_id"));
                        int msgType = getMmsContainingFolder(Integer
                                .parseInt(id));
                        if (msgType == -1) {
                            // Convert to virtual handle for MMS
                            id = Integer.toString(Integer.valueOf(id)
                                    + MMS_HDLR_CONSTANT);
                            Log.d(TAG, " DELETED MMS ID " + id);
                            sendMnsEvent(MESSAGE_DELETED, id,
                                    "TELECOM/MSG/SENT", null, "MMS");
                        } else {
                            Log.d(TAG, "Shouldn't reach here as you cannot "
                                    + "move msg from Sent to any other folder");
                        }
                    } else {
                        // TODO - The current(old) query doesn't have this
                        // row;
                        // implies it was added
                    }
                    break;
                case RIGHT:
                    // handle case where a row in cursor2 is unique
                    if (currentCRMmsSent == CR_MMS_SENT_B) {
                        // The new query doesn't have this row; implies it
                        // was deleted
                        Log.d(TAG, " MMS DELETED FROM SENT ");
                        String id = crMmsSentB.getString(crMmsSentB
                                .getColumnIndex("_id"));
                        int msgType = getMmsContainingFolder(Integer
                                .parseInt(id));
                        if (msgType == -1) {
                            // Convert to virtual handle for MMS
                            id = Integer.toString(Integer.valueOf(id)
                                    + MMS_HDLR_CONSTANT);
                            Log.d(TAG, " DELETED MMS ID " + id);
                            sendMnsEvent(MESSAGE_DELETED, id,
                                    "TELECOM/MSG/SENT", null, "MMS");
                        } else {
                            Log.d(TAG, "Shouldn't reach here as you cannot "
                                    + "move msg from Sent to any other folder");
                        }
                    } else {
                        // The current(old) query doesn't have this row;
                        // implies it was added
                    }
                    break;
                case BOTH:
                    // handle case where a row with the same key is in both
                    // cursors
                    break;
                }
            }
        }
        if (currentCRMmsSent == CR_MMS_SENT_A) {
            currentCRMmsSent = CR_MMS_SENT_B;
        } else {
            currentCRMmsSent = CR_MMS_SENT_A;
        }
    }

    /**
     * Check for MMS message being added and send a notification if there is a
     * change
     */
    private void checkMmsAdded() {

        int currentItemCount = 0;
        int newItemCount = 0;

        if (currentCRMms == CR_MMS_A) {
            currentItemCount = crMmsA.getCount();
            crMmsB.requery();
            newItemCount = crMmsB.getCount();
        } else {
            currentItemCount = crMmsB.getCount();
            crMmsA.requery();
            newItemCount = crMmsA.getCount();
        }

        Log.d(TAG, "MMS current " + currentItemCount + " new " + newItemCount);

        if (newItemCount > currentItemCount) {
            crMmsA.moveToFirst();
            crMmsB.moveToFirst();

            CursorJoiner joiner = new CursorJoiner(crMmsA,
                    new String[] { "_id" }, crMmsB, new String[] { "_id" });

            CursorJoiner.Result joinerResult;
            while (joiner.hasNext()) {
                joinerResult = joiner.next();
                switch (joinerResult) {
                case LEFT:
                    // handle case where a row in cursor1 is unique
                    if (currentCRMms == CR_MMS_A) {
                        // The new query doesn't have this row; implies it
                        // was deleted
                    } else {
                        // The current(old) query doesn't have this row;
                        // implies it was added
                        Log.d(TAG, " MMS ADDED TO INBOX ");
                        String id1 = crMmsA.getString(crMmsA
                                .getColumnIndex("_id"));
                        int msgType = getMmsContainingFolder(Integer
                                .parseInt(id1));
                        String folder = getMAPFolder(msgType);
                        if (folder != null) {
                            // Convert to virtual handle for MMS
                            id1 = Integer.toString(Integer.valueOf(id1)
                                    + MMS_HDLR_CONSTANT);
                            Log.d(TAG, " ADDED MMS ID " + id1);
                            sendMnsEvent(NEW_MESSAGE, id1, "TELECOM/MSG/"
                                    + folder, null, "MMS");
                        } else {
                            Log.d(TAG, " ADDED TO UNKNOWN FOLDER");
                        }
                    }
                    break;
                case RIGHT:
                    // handle case where a row in cursor2 is unique
                    if (currentCRMms == CR_MMS_B) {
                        // The new query doesn't have this row; implies it
                        // was deleted
                    } else {
                        // The current(old) query doesn't have this row;
                        // implies it was added
                        Log.d(TAG, " MMS ADDED ");
                        String id1 = crMmsB.getString(crMmsB
                                .getColumnIndex("_id"));
                        int msgType = getMmsContainingFolder(Integer
                                .parseInt(id1));
                        String folder = getMAPFolder(msgType);
                        if (folder != null) {
                            // Convert to virtual handle for MMS
                            id1 = Integer.toString(Integer.valueOf(id1)
                                    + MMS_HDLR_CONSTANT);

                            Log.d(TAG, " ADDED MMS ID " + id1);
                            sendMnsEvent(NEW_MESSAGE, id1, "TELECOM/MSG/"
                                    + folder, null, "MMS");
                        } else {
                            Log.d(TAG, " ADDED TO UNKNOWN FOLDER");
                        }
                    }
                    break;
                case BOTH:
                    // handle case where a row with the same key is in both
                    // cursors
                    break;
                }
            }
        }
        if (currentCRMms == CR_MMS_A) {
            currentCRMms = CR_MMS_B;
        } else {
            currentCRMms = CR_MMS_A;
        }
    }
    /**
     * Get the folder name (MAP representation) based on the message Handle
     */
    private int getMmsContainingFolder(int msgID) {
        int folderNum = -1;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = mContext.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            int msgboxInd = cursor.getColumnIndex("msg_box");
            folderNum = cursor.getInt(msgboxInd);
        }
        cursor.close();
        return folderNum;
    }

}
