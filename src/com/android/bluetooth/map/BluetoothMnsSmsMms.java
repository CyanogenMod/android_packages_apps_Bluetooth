/*
 * Copyright (c) 2011, Code Aurora Forum. All rights reserved.
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

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.provider.Telephony.Mms;
import android.provider.Telephony.MmsSms;
import android.provider.Telephony.Sms;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.bluetooth.map.BluetoothMns.MnsClient;
import com.google.android.mms.pdu.PduHeaders;

import java.util.Collection;
import java.util.HashMap;

import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.DELETED;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.OUTBOX;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.SENT;

/**
 * This class run an MNS session.
 */
public class BluetoothMnsSmsMms extends MnsClient {
    private static final String TAG = "BluetoothMnsSmsMms";
    private static final boolean V = BluetoothMasService.VERBOSE;
    private static final String MSG_TO_MAP[] = {
        "",         // ALL
        "inbox",    // INBOX
        "sent",     // SENT
        "draft",    // DRAFT
        "outbox",   // OUTBOX
        "outbox",   // FAILED
        "outbox",   // QUEUED
        "inbox",    // INBOX_SUB1
        "inbox",    // INBOX_SUB2
    };
    private static final String SMS_GSM = "SMS_GSM";
    private static final String SMS_CDMA = "SMS_CDMA";
    private static final String MMS = "MMS";
    private final long SMS_OFFSET_START;
    private final long MMS_OFFSET_START;

    private MmsSmsContentObserver mMmsSmsObserver = new MmsSmsContentObserver();

    public BluetoothMnsSmsMms(Context context, Integer masId) {
        super(context, masId);
        SMS_OFFSET_START = OFFSET_START;
        MMS_OFFSET_START = OFFSET_START + ((OFFSET_END - OFFSET_START) / 2);
    }

    @Override
    protected void registerContentObserver() {
        if (V) Log.v(TAG, "REGISTERING SMS/MMS MNS");
        mMmsSmsObserver.update(true);
        mContext.getContentResolver().registerContentObserver(MmsSms.CONTENT_URI, true,
                mMmsSmsObserver);
        if (V) Log.v(TAG, "REGISTERING SMS/MMS MNS DONE");
    }

    @Override
    protected void unregisterContentObserver() {
        if (V) Log.v(TAG, "UNREGISTERING MNS SMS/MMS");
        mContext.getContentResolver().unregisterContentObserver(mMmsSmsObserver);
        if (V) Log.v(TAG, "UNREGISTERING MNS SMS/MMS DONE");
    }

    private static final String[] MMS_PROJECTION = new String[] {Mms._ID, Mms.MESSAGE_BOX,
        Mms.THREAD_ID, Mms.MESSAGE_TYPE, Mms.DATE};
    private static final int MMS_ID_COL = 0;
    private static final int MMS_BOX_TYPE_COL = 1;
    private static final int MMS_THREAD_ID_COL = 2;
    private static final int MMS_MSG_TYPE_COL = 3;
    private static final int MMS_DATE_COL = 4;

    private static final String[] SMS_PROJECTION = new String[] {Sms._ID, Sms.TYPE, Sms.THREAD_ID,
        Sms.DATE};
    private static final int SMS_ID_COL = 0;
    private static final int SMS_TYPE_COL = 1;
    private static final int SMS_THREAD_ID_COL = 2;
    private static final int SMS_DATE_COL = 3;

    static class Message {
        long mId;
        String mFolderName;
        int mType;
        long mThreadId;
        long mDate;

        public Message(long id, String folderName, int type, long threadId, long date) {
            mId = id;
            mFolderName = folderName;
            mType = type;
            mThreadId = threadId;
            mDate = date;
        }
    }

    private class MmsSmsContentObserver extends ContentObserver {
        private static final String TAG = "MmsSmsContentObserver";
        private HashMap<Long, Message> mSmsList = new HashMap<Long, Message>();
        private HashMap<Long, Message> mSmsAddedList = new HashMap<Long, Message>();
        private HashMap<Long, Message> mSmsDeletedList = new HashMap<Long, Message>();

        private HashMap<Long, Message> mMmsList = new HashMap<Long, Message>();
        private HashMap<Long, Message> mMmsAddedList = new HashMap<Long, Message>();
        private HashMap<Long, Message> mMmsDeletedList = new HashMap<Long, Message>();

        private static final int UPDATE = 0;
        private static final int THRESHOLD = 1500;  // 1.5 sec

        public MmsSmsContentObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            if (V) Log.v(TAG, "onChange(" + selfChange + ") SMS mas Id: " + mMasId);
            if (mHandler.hasMessages(UPDATE)) {
                mHandler.removeMessages(UPDATE);
            }
            mHandler.sendEmptyMessageDelayed(UPDATE, THRESHOLD);
        }

        private Handler mHandler = new Handler() {
            private static final String TAG = "MmsSmsContentObserver.Hanlder";
            @Override
            public void handleMessage(android.os.Message msg) {
                if (V) Log.v(TAG, "handleMessage(" + msg.what + ") mas Id: " + mMasId);
                switch (msg.what) {
                    case UPDATE:
                        new Thread(new Runnable() {
                            public void run() {
                                update(false);
                                sendEvents();
                            }
                        }, "MmsSms Content Observer Thread").start();
                        break;
                }
            }
        };

        void update(boolean init) {
            updateSms(init);
            updateMms(init);
        }

        void updateSms(boolean init) {
            if (init) {
                clearSms();
            }
            final ContentResolver resolver = mContext.getContentResolver();
            Cursor crSms = resolver.query(Sms.CONTENT_URI, SMS_PROJECTION, null, null, null);
            if (crSms != null) {
                if (crSms.moveToFirst()) {
                    HashMap<Long, Message> oldSmsList = mSmsList;
                    HashMap<Long, Message> newSmsList = new HashMap<Long, Message>();
                    do {
                        final long id = crSms.getLong(SMS_ID_COL);
                        final int type = crSms.getInt(SMS_TYPE_COL);
                        final long threadId = crSms.getLong(SMS_THREAD_ID_COL);
                        final long date = crSms.getLong(SMS_DATE_COL);
                        if (type > 0 && type < MSG_TO_MAP.length) {
                            final Message msg = new Message(id, MSG_TO_MAP[type], type, threadId,
                                    date);
                            newSmsList.put(id, msg);
                            if (oldSmsList.containsKey(id)) {
                                Message old_msg = oldSmsList.remove(id);
                                if (msg.mType != old_msg.mType) {
                                    if(V) Log.v(TAG, "MNS_SMS: Add to mSmsAddedList");
                                    mSmsAddedList.put(id, msg);
                                }
                            }
                            else {
                                final Message oldMsg = oldSmsList.remove(id);
                                if (!init && (oldMsg == null || oldMsg.mDate != date)) {
                                    if(V) Log.v(TAG, "MNS_SMS: Add to mSmsAddedList");
                                    mSmsAddedList.put(id, msg);
                                }
                            }
                        }
                    } while (crSms.moveToNext());
                    mSmsList = newSmsList;
                    mSmsDeletedList = oldSmsList;
                }
                else
                {
                    // Last sms to be deleted
                    if(mSmsList.size() > 0)
                    {
                        if(V) Log.v(TAG, "MNS_SMS: mSmsList Length: " + mSmsList.size());
                        mSmsDeletedList = mSmsList;
                    }
                }
                crSms.close();
            }
        }

        void updateMms(boolean init) {
            if (init) {
                clearMms();
            }
            final ContentResolver resolver = mContext.getContentResolver();
            Cursor crMms = resolver.query(Mms.CONTENT_URI, MMS_PROJECTION, null, null, null);
            if (crMms != null) {
                if (crMms.moveToFirst()) {
                    HashMap<Long, Message> oldMmsList = mMmsList;
                    HashMap<Long, Message> newMmsList = new HashMap<Long, Message>();
                    do {
                        final long id = crMms.getInt(MMS_ID_COL);
                        final int boxType = crMms.getInt(MMS_BOX_TYPE_COL);
                        final long threadId = crMms.getLong(MMS_THREAD_ID_COL);
                        final int msgType = crMms.getInt(MMS_MSG_TYPE_COL);
                        final long date = crMms.getLong(MMS_DATE_COL);
                        // TODO need to filter out Pdu by message type?
                        if (msgType != PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND &&
                                msgType != PduHeaders.MESSAGE_TYPE_DELIVERY_IND) {
                            final Message msg = new Message(id, MSG_TO_MAP[boxType],
                                    boxType, threadId, date);
                            newMmsList.put(id, msg);
                            final Message oldMsg = oldMmsList.remove(id);
                            if (!init && (oldMsg == null || oldMsg.mDate != date)) {
                                mMmsAddedList.put(id, msg);
                            }
                        }
                    } while (crMms.moveToNext());
                    mMmsList = newMmsList;
                    mMmsDeletedList = oldMmsList;
                }
                crMms.close();
            }
        }

        private void sendEvents() {
            if (mSmsAddedList.size() > 0) {
                newSms();
                mSmsAddedList.clear();
            }
            if (mSmsDeletedList.size() > 0) {
                deletedSms();
                mSmsDeletedList.clear();
            }
            if (mMmsAddedList.size() > 0) {
                newMms();
                mMmsAddedList.clear();
            }
            if (mMmsDeletedList.size() > 0) {
                deletedMms();
                mMmsDeletedList.clear();
            }
        }

        private void clearSms() {
            mSmsList.clear();
            mSmsAddedList.clear();
            mSmsDeletedList.clear();
        }

        private void clearMms() {
            mMmsList.clear();
            mMmsAddedList.clear();
            mMmsDeletedList.clear();
        }

        private void newSms() {
            if (V) Log.v(TAG, "newSms() SMS mas Id: " + mMasId);
            if (mListener != null) {
                final int phoneType = TelephonyManager.getDefault().getPhoneType();
                final String type = (phoneType == TelephonyManager.PHONE_TYPE_CDMA)
                        ? SMS_CDMA : SMS_GSM;
                Collection<Message> values = mSmsAddedList.values();
                for (Message msg : values) {
                    if (msg.mType == Sms.MESSAGE_TYPE_SENT) {
                        mListener.onSendingSuccess(mMasId, String.valueOf(SMS_OFFSET_START +
                                msg.mId), PRE_PATH + SENT, type);
                    } else if (msg.mType == Sms.MESSAGE_TYPE_FAILED) {
                        mListener.onSendingFailure(mMasId, String.valueOf(SMS_OFFSET_START +
                                msg.mId), PRE_PATH + OUTBOX, type);
                    } else if ((msg.mType == Sms.MESSAGE_TYPE_INBOX) ||
                               (msg.mType == Sms.MESSAGE_TYPE_OUTBOX)) {
                        String folderName = (msg.mThreadId == -1) ? DELETED : msg.mFolderName;
                        mListener.onNewMessage(mMasId, String.valueOf(SMS_OFFSET_START + msg.mId),
                                PRE_PATH + folderName, type);
                    }
                }
            }
        }

        private void deletedSms() {
            if (V) Log.v(TAG, "deletedSms() SMS mas Id: " + mMasId);
            if (mListener != null) {
                final int phoneType = TelephonyManager.getDefault().getPhoneType();
                final String type = (phoneType == TelephonyManager.PHONE_TYPE_CDMA)
                        ? SMS_CDMA : SMS_GSM;
                Collection<Message> values = mSmsDeletedList.values();
                for (Message msg : values) {
                    String folderName = (msg.mThreadId == -1) ? DELETED : msg.mFolderName;
                    mListener.onMessageDeleted(mMasId, String.valueOf(SMS_OFFSET_START + msg.mId),
                            PRE_PATH + folderName, type);
                }
            }
        }

        private void newMms() {
            if (V) Log.v(TAG, "newMms() MMS mas Id: " + mMasId);
            if (mListener != null) {
                Collection<Message> values = mMmsAddedList.values();
                for (Message msg : values) {
                    String folderName = (msg.mThreadId == -1) ? DELETED : msg.mFolderName;
                    mListener.onNewMessage(mMasId, String.valueOf(MMS_OFFSET_START + msg.mId),
                            PRE_PATH + folderName, MMS);
                    if (msg.mType == Mms.MESSAGE_BOX_SENT) {
                        mListener.onSendingSuccess(mMasId, String.valueOf(SMS_OFFSET_START +
                                msg.mId), PRE_PATH + SENT, MMS);
                    }
                }
            }
        }

        private void deletedMms() {
            if (V) Log.v(TAG, "deletedMms() MMS mas Id: " + mMasId);
            if (mListener != null) {
                Collection<Message> values = mMmsDeletedList.values();
                for (Message msg : values) {
                    String folderName = (msg.mThreadId == -1) ? DELETED : msg.mFolderName;
                    mListener.onMessageDeleted(mMasId, String.valueOf(MMS_OFFSET_START + msg.mId),
                            PRE_PATH + folderName, MMS);
                }
            }
        }
    };
}
