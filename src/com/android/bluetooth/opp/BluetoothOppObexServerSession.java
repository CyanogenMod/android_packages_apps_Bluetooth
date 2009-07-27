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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.webkit.MimeTypeMap;

import javax.obex.HeaderSet;
import javax.obex.ObexTransport;
import javax.obex.Operation;
import javax.obex.ResponseCodes;
import javax.obex.ServerRequestHandler;
import javax.obex.ServerSession;

/**
 * This class runs as an OBEX server
 */
public class BluetoothOppObexServerSession extends ServerRequestHandler implements
        BluetoothOppObexSession {

    private static final String TAG = "BtOpp Server";

    private ObexTransport mTransport;

    private Context mContext;

    private Handler mCallback = null;

    /* status when server is blocking for user/auto confirmation */
    private boolean mServerBlocking = true;

    /* the current transfer info */
    private BluetoothOppShareInfo mInfo;

    /* info id when we insert the record */
    private int mLocalShareInfoId;

    private int mAccepted = BluetoothShare.USER_CONFIRMATION_PENDING;

    private boolean mInterrupted = false;

    private ServerSession mSession;

    private long mTimestamp;

    private BluetoothOppReceiveFileInfo mFileInfo;

    public BluetoothOppObexServerSession(Context context, ObexTransport transport) {
        mContext = context;
        mTransport = transport;
    }

    public void unblock() {
        mServerBlocking = false;
    }

    /**
     * Called when connection is accepted from remote, to retrieve the first
     * Header then wait for user confirmation
     */
    public void preStart() {
        try {
            if (Constants.LOGV) {
                Log.v(TAG, "Create ServerSession with transport " + mTransport.toString());
            }
            mSession = new ServerSession(mTransport, this, null);
        } catch (IOException e) {
            Log.e(TAG, "Create server session error" + e);
        }
    }

    /**
     * Called from BluetoothOppTransfer to start the "Transfer"
     */
    public void start(Handler handler) {
        if (Constants.LOGV) {
            Log.v(TAG, "Start!");
        }
        mCallback = handler;

    }

    /**
     * Called from BluetoothOppTransfer to cancel the "Transfer" Otherwise,
     * server should end by itself.
     */
    public void stop() {
        /*
         * TODO now we implement in a tough way, just close the socket.
         * maybe need nice way
         */
        if (Constants.LOGV) {
            Log.v(TAG, "Stop!");
        }
        mInterrupted = true;
        if (mSession != null) {
            try {
                mSession.close();
                mTransport.close();
            } catch (IOException e) {
                Log.e(TAG, "close mTransport error" + e);
            }
        }
    }

    public void addShare(BluetoothOppShareInfo info) {
        if (Constants.LOGV) {
            Log.v(TAG, "addShare for id " + info.mId);
        }
        mInfo = info;
        mFileInfo = processShareInfo();
    }

    @Override
    public int onPut(Operation op) {
        if (Constants.LOGV) {
            Log.v(TAG, "onPut " + op.toString());
        }
        HeaderSet request;
        String name, mimeType;
        Long length;

        int obexResponse = ResponseCodes.OBEX_HTTP_OK;

        /**
         * For multiple objects, reject further objects after user deny the
         * first one
         */
        if (mAccepted == BluetoothShare.USER_CONFIRMATION_DENIED) {
            return ResponseCodes.OBEX_HTTP_FORBIDDEN;
        }

        try {
            boolean pre_reject = false;
            request = op.getReceivedHeader();
            if (Constants.LOGVV) {
                logHeader(request);
            }
            name = (String)request.getHeader(HeaderSet.NAME);
            length = (Long)request.getHeader(HeaderSet.LENGTH);
            mimeType = (String)request.getHeader(HeaderSet.TYPE);

            if (length == 0) {
                if (Constants.LOGV) {
                    Log.w(TAG, "length is 0, reject the transfer");
                }
                pre_reject = true;
                obexResponse = ResponseCodes.OBEX_HTTP_LENGTH_REQUIRED;
            }

            if (name == null || name.equals("")) {
                if (Constants.LOGV) {
                    Log.w(TAG, "name is null or empty, reject the transfer");
                }
                pre_reject = true;
                obexResponse = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }

            if (!pre_reject) {
                /* first we look for Mimetype in Android map */
                String extension, type;
                int dotIndex = name.indexOf('.');
                if (dotIndex < 0) {
                    if (Constants.LOGV) {
                        Log.w(TAG, "There is no file extension, reject the transfer");
                    }
                    pre_reject = true;
                    obexResponse = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
                } else {
                    extension = name.substring(dotIndex + 1);
                    MimeTypeMap map = MimeTypeMap.getSingleton();
                    type = map.getMimeTypeFromExtension(extension);
                    if (Constants.LOGVV) {
                        Log.v(TAG, "Mimetype guessed from extension " + extension + " is "
                                + type);
                    }
                    if (type != null) {
                        mimeType = type;

                    } else {
                        if (mimeType == null) {
                            if (Constants.LOGV) {
                                Log.w(TAG, "Can't get mimetype, reject the transfer");
                            }
                            pre_reject = true;
                            obexResponse = ResponseCodes.OBEX_HTTP_UNSUPPORTED_TYPE;
                        }
                    }
                    if (mimeType != null) {
                        mimeType = mimeType.toLowerCase();
                    }
                }
            }

            if (!pre_reject
                    && (mimeType == null || Constants.mimeTypeMatches(mimeType,
                            Constants.UNACCEPTABLE_SHARE_INBOUND_TYPES))) {
                if (Constants.LOGV) {
                    Log.w(TAG, "mimeType is null or in unacceptable list, reject the transfer");
                }
                pre_reject = true;
                obexResponse = ResponseCodes.OBEX_HTTP_UNSUPPORTED_TYPE;
            }

            if (pre_reject && obexResponse != ResponseCodes.OBEX_HTTP_OK) {
                return obexResponse;
            }

        } catch (IOException e) {
            Log.e(TAG, "get getReceivedHeaders error " + e);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        ContentValues values = new ContentValues();

        values.put(BluetoothShare.FILENAME_HINT, name);
        values.put(BluetoothShare.TOTAL_BYTES, length.intValue());
        values.put(BluetoothShare.MIMETYPE, mimeType);

        if (mTransport instanceof BluetoothOppRfcommTransport) {
            String a = ((BluetoothOppRfcommTransport)mTransport).getRemoteAddress();
            values.put(BluetoothShare.DESTINATION, a);
        } else {
            values.put(BluetoothShare.DESTINATION, "FF:FF:FF:00:00:00");
        }

        values.put(BluetoothShare.DIRECTION, BluetoothShare.DIRECTION_INBOUND);
        values.put(BluetoothShare.TIMESTAMP, mTimestamp);

        boolean needConfirm = true;
        /** It's not first put if !serverBlocking, so we auto accept it */
        if (!mServerBlocking) {
            values.put(BluetoothShare.USER_CONFIRMATION,
                    BluetoothShare.USER_CONFIRMATION_AUTO_CONFIRMED);
            needConfirm = false;
        }

        Uri contentUri = mContext.getContentResolver().insert(BluetoothShare.CONTENT_URI, values);
        mLocalShareInfoId = Integer.parseInt(contentUri.getPathSegments().get(1));

        if (needConfirm) {
            Intent in = new Intent(BluetoothShare.INCOMING_FILE_CONFIRMATION_REQUEST_ACTION);
            in.setClassName(Constants.THIS_PACKAGE_NAME, BluetoothOppReceiver.class.getName());
            mContext.sendBroadcast(in);
        }

        if (Constants.LOGVV) {
            Log.v(TAG, "insert contentUri: " + contentUri);
            Log.v(TAG, "mLocalShareInfoId = " + mLocalShareInfoId);
        }

        // TODO add server wait timeout
        mServerBlocking = true;
        boolean msgSent = false;
        synchronized (this) {
            try {

                while (mServerBlocking) {
                    wait(1000);
                    if (mCallback != null && !msgSent) {
                        mCallback.sendMessageDelayed(mCallback
                                .obtainMessage(BluetoothOppObexSession.MSG_CONNECT_TIMEOUT),
                                BluetoothOppObexSession.SESSION_TIMEOUT);
                        msgSent = true;
                        if (Constants.LOGVV) {
                            Log.v(TAG, "MSG_CONNECT_TIMEOUT sent");
                        }
                    }
                }
            } catch (InterruptedException e) {
                if (Constants.LOGVV) {
                    Log.v(TAG, "Interrupted in onPut blocking");
                }
            }
        }
        if (Constants.LOGV) {
            Log.v(TAG, "Server unblocked ");
        }
        if (mCallback != null && msgSent) {
            mCallback.removeMessages(BluetoothOppObexSession.MSG_CONNECT_TIMEOUT);
        }

        /* we should have mInfo now */

        /*
         * TODO check if this mInfo match the one that we insert before server
         * blocking? just to make sure no error happens
         */
        if (mInfo.mId != mLocalShareInfoId) {
            Log.e(TAG, "Unexpected error!");
        }
        mAccepted = mInfo.mConfirm;

        if (Constants.LOGVV) {
            Log.v(TAG, "after confirm: userAccepted=" + mAccepted);
        }
        int status = BluetoothShare.STATUS_SUCCESS;

        if (mAccepted == BluetoothShare.USER_CONFIRMATION_CONFIRMED
                || mAccepted == BluetoothShare.USER_CONFIRMATION_AUTO_CONFIRMED) {
            /* Confirm or auto-confirm */

            if (mFileInfo.mFileName == null) {
                status = mFileInfo.mStatus;
                /* TODO need to check if this line is correct */
                mInfo.mStatus = mFileInfo.mStatus;
                Constants.updateShareStatus(mContext, mInfo.mId, status);
                obexResponse = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;

            }

            if (mFileInfo.mFileName != null) {

                ContentValues updateValues = new ContentValues();
                contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + mInfo.mId);
                updateValues.put(BluetoothShare._DATA, mFileInfo.mFileName);
                updateValues.put(BluetoothShare.STATUS, BluetoothShare.STATUS_RUNNING);
                mContext.getContentResolver().update(contentUri, updateValues, null, null);

                status = receiveFile(mFileInfo, op);
                /*
                 * TODO map status to obex response code
                 */
                if (status != BluetoothShare.STATUS_SUCCESS) {
                    obexResponse = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                }
                Constants.updateShareStatus(mContext, mInfo.mId, status);
            }

            if (status == BluetoothShare.STATUS_SUCCESS) {
                Message msg = Message.obtain(mCallback, BluetoothOppObexSession.MSG_SHARE_COMPLETE);
                msg.obj = mInfo;
                msg.sendToTarget();
            } else {
                Message msg = Message.obtain(mCallback, BluetoothOppObexSession.MSG_SESSION_ERROR);
                msg.obj = mInfo;
                msg.sendToTarget();
            }
        } else if (mAccepted == BluetoothShare.USER_CONFIRMATION_DENIED
                || mAccepted == BluetoothShare.USER_CONFIRMATION_TIMEOUT) {
            /* user actively deny the inbound transfer */
            /*
             * Note There is a question: what's next if user deny the first obj?
             * Option 1 :continue prompt for next objects
             * Option 2 :reject next objects and finish the session
             * Now we take option 2:
             */
            if (Constants.LOGVV) {
                Log.v(TAG, "request forbidden, indicate interrupted");
            }
            status = BluetoothShare.STATUS_FORBIDDEN;
            Constants.updateShareStatus(mContext, mInfo.mId, status);
            obexResponse = ResponseCodes.OBEX_HTTP_FORBIDDEN;
            Message msg = Message.obtain(mCallback);
            /* TODO check which message should be sent */
            msg.what = BluetoothOppObexSession.MSG_SHARE_INTERRUPTED;
            msg.obj = mInfo;
            msg.sendToTarget();
        }
        return obexResponse;
    }

    private int receiveFile(BluetoothOppReceiveFileInfo fileInfo, Operation op) {
        /*
         * implement receive file
         */
        int status = -1;
        BufferedOutputStream bos = null;

        InputStream is = null;
        boolean error = false;
        try {
            is = op.openInputStream();
        } catch (IOException e1) {
            Log.e(TAG, "Error when openInputStream");
            status = BluetoothShare.STATUS_OBEX_DATA_ERROR;
            error = true;
        }

        Uri contentUri = Uri.parse(BluetoothShare.CONTENT_URI + "/" + mInfo.mId);

        if (!error) {
            ContentValues updateValues = new ContentValues();
            updateValues.put(BluetoothShare._DATA, fileInfo.mFileName);
            mContext.getContentResolver().update(contentUri, updateValues, null, null);
        }

        int position = 0;
        if (!error) {
            File f = new File(fileInfo.mFileName);
            try {
                bos = new BufferedOutputStream(new FileOutputStream(f), 0x10000);
            } catch (FileNotFoundException e1) {
                Log.e(TAG, "Error when open file " + f.toString());
                status = BluetoothShare.STATUS_FILE_ERROR;
                error = true;
            }
        }

        if (!error) {
            int outputBufferSize = op.getMaxPacketSize();
            byte[] b = new byte[outputBufferSize];
            int readLength = 0;
            long timestamp;
            try {
                while ((!mInterrupted) && (position != fileInfo.mLength)) {

                    if (Constants.LOGVV) {
                        timestamp = System.currentTimeMillis();
                    }

                    readLength = is.read(b);

                    if (readLength == -1) {
                        if (Constants.LOGV) {
                            Log.v(TAG, "Receive file reached stream end at position" + position);
                        }
                        break;
                    }

                    bos.write(b, 0, readLength);
                    position += readLength;

                    if (Constants.USE_EMULATOR_DEBUG) {
                        synchronized (this) {
                            try {
                                wait(300);
                            } catch (InterruptedException e) {
                                status = BluetoothShare.STATUS_CANCELED;
                                mInterrupted = true;
                                if (Constants.LOGVV) {
                                    Log.v(TAG, "ReceiveFile interrupted when receive file "
                                            + fileInfo.mFileName + " at " + position + " of "
                                            + position);
                                }
                                Constants.updateShareStatus(mContext, mInfo.mId, status);
                            }
                        }
                    }

                    if (Constants.LOGVV) {
                        Log.v(TAG, "Receive file position = " + position + " readLength "
                                + readLength + " bytes took "
                                + (System.currentTimeMillis() - timestamp) + " ms");
                    }

                    ContentValues updateValues = new ContentValues();
                    updateValues.put(BluetoothShare.CURRENT_BYTES, position);
                    mContext.getContentResolver().update(contentUri, updateValues, null, null);
                }
            } catch (IOException e1) {
                Log.e(TAG, "Error when receiving file");
                status = BluetoothShare.STATUS_OBEX_DATA_ERROR;
                error = true;
            }
        }

        if (mInterrupted) {
            if (Constants.LOGV) {
                Log.v(TAG, "receiving file interrupted by user.");
            }
            status = BluetoothShare.STATUS_CANCELED;
        } else {
            if (position == fileInfo.mLength) {
                if (Constants.LOGV) {
                    Log.v(TAG, "Receiving file completed for " + fileInfo.mFileName);
                }
                status = BluetoothShare.STATUS_SUCCESS;
            } else {
                if (Constants.LOGV) {
                    Log.v(TAG, "Reading file failed at " + position + " of " + fileInfo.mLength);
                }
                if (status == -1) {
                    status = BluetoothShare.STATUS_UNKNOWN_ERROR;
                }
            }
        }

        Constants.updateShareStatus(mContext, mInfo.mId, status);
        if (bos != null) {
            try {
                bos.close();
            } catch (IOException e) {
                Log.e(TAG, "Error when closing stream after send");
            }
        }
        return status;
    }

    private BluetoothOppReceiveFileInfo processShareInfo() {
        if (Constants.LOGV) {
            Log.v(TAG, "processShareInfo() " + mInfo.mId);
        }
        BluetoothOppReceiveFileInfo fileInfo = BluetoothOppReceiveFileInfo.generateFileInfo(
                mContext, mInfo.mId);
        if (Constants.LOGVV) {
            Log.v(TAG, "Generate BluetoothOppReceiveFileInfo:");
            Log.v(TAG, "filename  :" + fileInfo.mFileName);
            Log.v(TAG, "length    :" + fileInfo.mLength);
            Log.v(TAG, "status    :" + fileInfo.mStatus);
        }
        return fileInfo;
    }

    @Override
    public int onConnect(HeaderSet request, HeaderSet reply) {

        if (Constants.LOGV) {
            Log.v(TAG, "onConnect");
        }
        if (Constants.LOGVV) {
            logHeader(request);
        }

        mTimestamp = System.currentTimeMillis();
        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public void onDisconnect(HeaderSet req, HeaderSet resp) {

        if (Constants.LOGV) {
            Log.v(TAG, "onDisconnect");
        }
        resp.responseCode = ResponseCodes.OBEX_HTTP_OK;
        /* onDisconnect could happen even before start() where mCallback is set */
        if (mCallback != null) {
            Message msg = Message.obtain(mCallback);
            msg.what = BluetoothOppObexSession.MSG_SESSION_COMPLETE;
            msg.obj = mInfo;
            msg.sendToTarget();
        }
    }

    @Override
    public void onClose() {
    }

    private void logHeader(HeaderSet hs) {
        Log.v(TAG, "Dumping HeaderSet " + hs.toString());
        try {

            Log.v(TAG, "COUNT : " + hs.getHeader(HeaderSet.COUNT));
            Log.v(TAG, "NAME : " + hs.getHeader(HeaderSet.NAME));
            Log.v(TAG, "TYPE : " + hs.getHeader(HeaderSet.TYPE));
            Log.v(TAG, "LENGTH : " + hs.getHeader(HeaderSet.LENGTH));
            Log.v(TAG, "TIME_ISO_8601 : " + hs.getHeader(HeaderSet.TIME_ISO_8601));
            Log.v(TAG, "TIME_4_BYTE : " + hs.getHeader(HeaderSet.TIME_4_BYTE));
            Log.v(TAG, "DESCRIPTION : " + hs.getHeader(HeaderSet.DESCRIPTION));
            Log.v(TAG, "TARGET : " + hs.getHeader(HeaderSet.TARGET));
            Log.v(TAG, "HTTP : " + hs.getHeader(HeaderSet.HTTP));
            Log.v(TAG, "WHO : " + hs.getHeader(HeaderSet.WHO));
            Log.v(TAG, "OBJECT_CLASS : " + hs.getHeader(HeaderSet.OBJECT_CLASS));
            Log.v(TAG, "APPLICATION_PARAMETER : " + hs.getHeader(HeaderSet.APPLICATION_PARAMETER));
        } catch (IOException e) {
            Log.e(TAG, "dump HeaderSet error " + e);
        }

    }
}
