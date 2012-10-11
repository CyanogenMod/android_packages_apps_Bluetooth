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
import android.util.Log;

import com.android.bluetooth.map.BluetoothMns.MnsClient;
import com.android.bluetooth.map.MapUtils.EmailUtils;

import java.util.Collection;
import java.util.HashMap;

import static com.android.bluetooth.map.MapUtils.EmailUtils.EMAIL_BOX_COLUMN_ACCOUNT_KEY;
import static com.android.bluetooth.map.MapUtils.EmailUtils.EMAIL_BOX_COLUMN_DISPLAY_NAME;
import static com.android.bluetooth.map.MapUtils.EmailUtils.EMAIL_BOX_COLUMN_RECORD_ID;
import static com.android.bluetooth.map.MapUtils.EmailUtils.EMAIL_BOX_COLUMN_TYPE;
import static com.android.bluetooth.map.MapUtils.EmailUtils.EMAIL_BOX_PROJECTION;
import static com.android.bluetooth.map.MapUtils.EmailUtils.EMAIL_BOX_URI;
import static com.android.bluetooth.map.MapUtils.EmailUtils.EMAIL_MESSAGE_PROJECTION;
import static com.android.bluetooth.map.MapUtils.EmailUtils.EMAIL_MESSAGE_URI;
import static com.android.bluetooth.map.MapUtils.EmailUtils.EMAIL_URI;
import static com.android.bluetooth.map.MapUtils.EmailUtils.MSG_COL_ACCOUNT_KEY;
import static com.android.bluetooth.map.MapUtils.EmailUtils.MSG_COL_MAILBOX_KEY;
import static com.android.bluetooth.map.MapUtils.EmailUtils.MSG_COL_RECORD_ID;
import static com.android.bluetooth.map.MapUtils.EmailUtils.TYPE_DELETED;
import static com.android.bluetooth.map.MapUtils.EmailUtils.TYPE_DRAFT;
import static com.android.bluetooth.map.MapUtils.EmailUtils.TYPE_INBOX;
import static com.android.bluetooth.map.MapUtils.EmailUtils.TYPE_OUTBOX;
import static com.android.bluetooth.map.MapUtils.EmailUtils.TYPE_SENT;

/**
 * This class run an MNS session.
 */
public class BluetoothMnsEmail extends MnsClient {
    private static final String TAG = "BluetoothMnsEmail";
    private static final boolean V = BluetoothMasService.VERBOSE;
    private static final String EMAIL_TO_MAP[] = {
        "inbox",    // TYPE_INBOX = 0;
        "",         // TYPE_MAIL = 1;
        "",         // TYPE_PARENT = 2;
        "draft",    // TYPE_DRAFTS = 3;
        "outbox",   // TYPE_OUTBOX = 4;
        "sent",     // TYPE_SENT = 5;
        "deleted",  // TYPE_TRASH = 6;
        ""          // TYPE_JUNK = 7;
    };
    private static final String EMAIL = "EMAIL";
    private EmailContentObserver mEmailObserver = new EmailContentObserver();
    private long mAccountKey;

    public BluetoothMnsEmail(Context context, Integer masId) {
        super(context, masId);
    }

    @Override
    protected void registerContentObserver() {
        if (V) Log.v(TAG, "REGISTERING EMAIL MNS");
        mAccountKey = EmailUtils.getAccountId(mMasId);
        mEmailObserver.updateEmailBox();
        mEmailObserver.update(true);
        mContext.getContentResolver().registerContentObserver(EMAIL_URI, true, mEmailObserver);
        if (V) Log.v(TAG, "REGISTERING EMAIL MNS DONE");
    }

    @Override
    protected void unregisterContentObserver() {
        if (V) Log.v(TAG, "UNREGISTERING MNS EMAIL");
        mContext.getContentResolver().unregisterContentObserver(mEmailObserver);
        if (V) Log.v(TAG, "UNREGISTERED MNS EMAIL");
    }

    static class EmailBox {
        long mId;
        String mDisplayName;
        long mAccountKey;
        int mType;

        public EmailBox(long id, String displayName, long accountKey, int type) {
            mId = id;
            mDisplayName = displayName;
            mAccountKey = accountKey;
            mType = type;
        }

        @Override
        public String toString() {
            return "[id:" + mId + ", display name:" + mDisplayName + ", account key:" +
                    mAccountKey + ", type:" + mType + "]";
        }
    }

    static class EmailMessage {
        long mId;
        long mAccountKey;
        String mFolderName;
        int mType;

        public EmailMessage(long id, long accountKey, String folderName, int type) {
            mId = id;
            mAccountKey = accountKey;
            mFolderName = folderName;
            mType = type;
        }

        @Override
        public String toString() {
            return "[id:" + mId + ", folder name:" + mFolderName + ", account key:" + mAccountKey +
                    ", type:" + mType + "]";
        }
    }

    private class EmailContentObserver extends ContentObserver {
        private static final String TAG = "EmailContentObserver";
        private HashMap<Long, EmailBox> mEmailBoxList = new HashMap<Long, EmailBox>();
        private HashMap<Long, EmailMessage> mEmailList = new HashMap<Long, EmailMessage>();
        /** List of deleted message, do not notify */
        private HashMap<Long, EmailMessage> mDeletedList = new HashMap<Long, EmailMessage>();
        private HashMap<Long, EmailMessage> mEmailAddedList = new HashMap<Long, EmailMessage>();
        /** List of newly deleted message, notify */
        private HashMap<Long, EmailMessage> mEmailDeletedList = new HashMap<Long, EmailMessage>();

        private static final int UPDATE = 0;
        private static final int THRESHOLD = 3000;  // 3 sec

        public EmailContentObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            if (V) Log.v(TAG, "onChange(" + selfChange + ")");
            if (mHandler.hasMessages(UPDATE)) {
                mHandler.removeMessages(UPDATE);
            }
            mHandler.sendEmptyMessageDelayed(UPDATE, THRESHOLD);
        }

        private Handler mHandler = new Handler() {
            private static final String TAG = "EmailContentObserver.Hanlder";
            @Override
            public void handleMessage(android.os.Message msg) {
                if (V) Log.v(TAG, "handleMessage(" + msg.what + ") mas Id: " + mMasId);
                switch (msg.what) {
                    case UPDATE:
                        new Thread(new Runnable() {
                            public void run() {
                                updateEmailBox();
                                update(false);
                                sendEvents();
                            }
                        }, "Email Content Observer Thread").start();
                        break;
                }
            }
        };

        void updateEmailBox() {
            mEmailBoxList.clear();
            final ContentResolver resolver = mContext.getContentResolver();
            Cursor crBox = resolver.query(EMAIL_BOX_URI, EMAIL_BOX_PROJECTION, null, null, null);
            if (crBox != null) {
                if (crBox.moveToFirst()) {
                    do {
                        final long id = crBox.getLong(EMAIL_BOX_COLUMN_RECORD_ID);
                        final String displayName = crBox.getString(EMAIL_BOX_COLUMN_DISPLAY_NAME);
                        final long accountKey = crBox.getLong(EMAIL_BOX_COLUMN_ACCOUNT_KEY);
                        final int type = crBox.getInt(EMAIL_BOX_COLUMN_TYPE);
                        final EmailBox box = new EmailBox(id, displayName, accountKey, type);
                        mEmailBoxList.put(id, box);
                        if (V) Log.v(TAG, box.toString());
                    } while (crBox.moveToNext());
                }
                crBox.close();
            }
        }

        void update(boolean init) {
            if (init) {
                clear();
            }
            final ContentResolver resolver = mContext.getContentResolver();
            Cursor crEmail = resolver.query(EMAIL_MESSAGE_URI, EMAIL_MESSAGE_PROJECTION,
                    null, null, null);
            if (crEmail != null) {
                if (crEmail.moveToFirst()) {
                    final HashMap<Long, EmailBox> boxList = mEmailBoxList;
                    HashMap<Long, EmailMessage> oldEmailList = mEmailList;
                    HashMap<Long, EmailMessage> emailList = new HashMap<Long, EmailMessage>();
                    do {
                        final long accountKey = crEmail.getLong(MSG_COL_ACCOUNT_KEY);
                        if (accountKey != mAccountKey) {
                            continue;
                        }
                        final long id = crEmail.getLong(MSG_COL_RECORD_ID);
                        final long mailboxKey = crEmail.getLong(MSG_COL_MAILBOX_KEY);
                        if (boxList.containsKey(mailboxKey)) {
                            final EmailBox box = boxList.get(mailboxKey);
                            if (box == null) {
                                continue;
                            }
                            final String folderName = isMapFolder(box.mType)
                                    ? EMAIL_TO_MAP[box.mType] : box.mDisplayName;
                            final EmailMessage msg = new EmailMessage(id, accountKey,
                                    folderName, box.mType);
                            if (box.mType == EmailUtils.TYPE_DELETED) {
                                if (init) {
                                    mDeletedList.put(id, msg);
                                } else if (!mDeletedList.containsKey(id) &&
                                        !mEmailDeletedList.containsKey(id)) {
                                    mEmailDeletedList.put(id, msg);
                                }
                            } else {
                                emailList.put(id, msg);
                                if (!oldEmailList.containsKey(id) && !init &&
                                        !mEmailAddedList.containsKey(id)) {
                                    mEmailAddedList.put(id, msg);
                                }
                            }
                        } else {
                            Log.e(TAG, "Mailbox is not updated");
                        }
                    } while (crEmail.moveToNext());
                    mEmailList = emailList;
                }
                crEmail.close();
            }
        }

        private void sendEvents() {
            if (mEmailAddedList.size() > 0) {
                newEmail();
                mEmailAddedList.clear();
            }
            if (mEmailDeletedList.size() > 0) {
                mDeletedList.putAll(mEmailDeletedList);
                deletedEmail();
                mEmailDeletedList.clear();
            }
        }

        private void clear() {
            mEmailList.clear();
            mDeletedList.clear();
            mEmailAddedList.clear();
            mEmailDeletedList.clear();
        }

        private boolean isMapFolder(int type) {
            if (type == TYPE_INBOX || type == TYPE_OUTBOX || type == TYPE_SENT ||
                    type == TYPE_DRAFT || type == TYPE_DELETED) {
                return true;
            }
            return false;
        }

        private void newEmail() {
            if (V) Log.v(TAG, "newEmail()");
            if (mListener != null) {
                Collection<EmailMessage> values = mEmailAddedList.values();
                for (EmailMessage email : values) {
                    if (V) Log.v(TAG, email.toString());
                    mListener.onNewMessage(mMasId, String.valueOf(email.mId + OFFSET_START),
                            PRE_PATH + email.mFolderName, EMAIL);
                    if (email.mType == TYPE_SENT) {
                        mListener.onSendingSuccess(mMasId, String.valueOf(email.mId + OFFSET_START),
                                PRE_PATH + email.mFolderName, EMAIL);
                    }
                }
            }
        }

        private void deletedEmail() {
            if (V) Log.v(TAG, "deletedEmail()");
            if (mListener != null) {
                Collection<EmailMessage> values = mEmailDeletedList.values();
                for (EmailMessage email : values) {
                    if (V) Log.v(TAG, email.toString());
                    mListener.onMessageDeleted(mMasId, String.valueOf(email.mId + OFFSET_START),
                            PRE_PATH + email.mFolderName, EMAIL);
                }
            }
        }
    };
}
