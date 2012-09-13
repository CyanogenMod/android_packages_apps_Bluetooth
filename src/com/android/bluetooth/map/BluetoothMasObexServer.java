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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.text.format.Time;
import android.util.Log;

import javax.obex.*;


import com.android.bluetooth.map.BluetoothMasAppIf.BluetoothMasMessageListingRsp;
import com.android.bluetooth.map.BluetoothMasAppIf.BluetoothMasMessageRsp;
import com.android.bluetooth.map.BluetoothMasAppIf.BluetoothMasPushMsgRsp;


public class BluetoothMasObexServer extends ServerRequestHandler {

    private static final String TAG = "BluetoothMasObexServer";

    private static final boolean D = BluetoothMasService.DEBUG;

    private static final boolean V = BluetoothMasService.VERBOSE;

    private static final int UUID_LENGTH = 16;

    // type for list folder contents
    private static final String TYPE_LISTING = "x-obex/folder-listing";
    private static final String TYPE_MESSAGE_LISTING = "x-bt/MAP-msg-listing";
    private static final String TYPE_MESSAGE = "x-bt/message";
    private static final String TYPE_MESSAGE_STATUS = "x-bt/messageStatus";
    private static final String TYPE_MESSAGE_UPDATE = "x-bt/MAP-messageUpdate";
    private static final String TYPE_MESSAGE_NOTIFICATION = "x-bt/MAP-NotificationRegistration";

    public long mConnectionId;

    private Handler mCallback = null;

    public Context mContext;

    public static boolean sIsAborted = false;

    public enum MasState {
        MAS_SERVER_CONNECTING,
        MAS_SERVER_DISCONNECTING,
        MAS_SERVER_CONNECTED,
        MAS_SERVER_DISCONNECTED,
        MAS_SERVER_SET_FOLDER,
        MAS_SERVER_GET_FILE_PENDING,
        MAS_SERVER_BROWSE_FOLDER_PENDING,
        MAS_SERVER_BROWSE_FOLDER,
        MAS_SERVER_GET_MSG_LIST_PENDING,
        MAS_SERVER_GET_MSG_LIST,
        MAS_SERVER_GET_MSG_PENDING,
        MAS_SERVER_GET_MSG,
        MAS_SERVER_SET_MSG_STATUS,
        MAS_SERVER_SET_NOTIFICATION_REG,
        MAS_SERVER_UPDATE_INBOX,
        MAS_SERVER_PUSH_MESSAGE
    };
    private static  MasState state = MasState.MAS_SERVER_DISCONNECTED;

     // 128 bit UUID for MAS
    private static final byte[] MAS_TARGET = new byte[] {
        (byte)0xbb, (byte)0x58, (byte)0x2b, (byte)0x40, (byte)0x42, (byte)0x0c, (byte)0x11, (byte)0xdb,
        (byte)0xb0, (byte)0xde, (byte)0x08, (byte)0x00, (byte)0x20, (byte)0x0c, (byte)0x9a, (byte)0x66
    };

    private BluetoothMasAppIf appIf;

    private BluetoothDevice mRemoteDevice;

    private class MasAppParamsStore {

        private BluetoothMasAppParams appParams = null;

        public final void clear() {
            if (D) Log.d(TAG, "Clear AppParams : Enter");
            appParams.MaxListCount = BluetoothMasSpecParams.MAS_DEFAULT_MAX_LIST_COUNT;
            appParams.ListStartOffset = 0;
            appParams.SubjectLength = BluetoothMasSpecParams.MAS_DEFAULT_SUBJECT_LENGTH;
            appParams.ParameterMask = BluetoothMasSpecParams.MAS_DEFAULT_PARAMETER_MASK;
            appParams.FilterMessageType = 0;
            appParams.FilterReadStatus = 0;
            appParams.FilterPriority = 0;
            appParams.FilterPeriodBegin = null;
            appParams.FilterPeriodEnd = null;
            appParams.FilterRecipient = null;
            appParams.FilterOriginator = null;
            appParams.Retry = 1;
            appParams.Transparent = 0;
            appParams.FractionRequest = BluetoothMasSpecParams.MAS_FRACTION_REQUEST_NOT_SET;
            appParams.Charset = 0x01;
        }

        public MasAppParamsStore() {
            super();
            appParams = new BluetoothMasAppParams();
            clear();
        }

        public final BluetoothMasAppParams get() {
            if (D) Log.d(TAG, "Create MasAppParams struct for service : Enter");
            BluetoothMasAppParams tmp = new BluetoothMasAppParams();

            tmp.MaxListCount = appParams.MaxListCount;
            tmp.ListStartOffset = appParams.ListStartOffset;
            tmp.SubjectLength = appParams.SubjectLength;
            tmp.ParameterMask = appParams.ParameterMask;

            tmp.Attachment = appParams.Attachment;
            tmp.Charset = appParams.Charset;

            tmp.StatusIndicator = appParams.StatusIndicator;
            tmp.StatusValue = appParams.StatusValue;
            tmp.Retry = appParams.Retry;

            tmp.FilterMessageType = appParams.FilterMessageType;
            tmp.FilterReadStatus = appParams.FilterReadStatus;
            tmp.FilterPriority = appParams.FilterPriority;

            tmp.FilterPeriodBegin = (appParams.FilterPeriodBegin == null) ? null : new String(appParams.FilterPeriodBegin);
            tmp.FilterPeriodEnd = (appParams.FilterPeriodEnd == null) ? null : new String(appParams.FilterPeriodEnd);
            tmp.FilterRecipient = (appParams.FilterRecipient == null) ? null : new String(appParams.FilterRecipient);
            tmp.FilterOriginator = (appParams.FilterOriginator == null) ? null : new String(appParams.FilterOriginator);
            tmp.Retry = appParams.Retry;
            tmp.Transparent = appParams.Transparent;
            tmp.FractionRequest = appParams.FractionRequest;
            tmp.Notification = appParams.Notification;

            return tmp;
        }

        public final boolean isMaxListCountZero() {

            return (appParams.MaxListCount == 0) ? true : false;

        }

        private final int getUint16BigEndian(byte b1, byte b2) {
            int retVal;
            retVal = (int) ((0x0000FF00 & (int) (b1 << 0x8)) | (0x000000FF & (int) b2));
            return retVal;
        }

        private final long getUint32BigEndian(byte b1, byte b2, byte b3, byte b4) {
            long retVal;
            retVal = (long) ((0xFF000000 & (long) (b1 << 0x24))
                    | (0x00FF0000 & (long) (b2 << 0x16))
                    | (0x0000FF00 & (long) (b3 << 0x8)) | (0x000000FF & (long) b4));
            return retVal;
        }

        private final boolean validateTag(long tagVal, long tagLen, long tagMinVal, long tagMaxVal, long tagActualLen) {

            if (tagLen != tagActualLen) {
                return false;
            }

            if ( tagVal < tagMinVal || tagVal > tagMaxVal){
                return false;
            }
            return true;
        }

        public final boolean parse(byte[] params) {
            int i = 0;


            if (D) Log.d(TAG, "Parse App. Params: Enter");

            if (params == null){
                if (D) Log.d(TAG, "No App. Params to parse: Exit");
                return true;
            }

            while (i < params.length) {
                switch (params[i]) {
                case BluetoothMasSpecParams.MAS_TAG_MAX_LIST_COUNT:
                    i += 2;
                    appParams.MaxListCount = getUint16BigEndian(params[i], params[i + 1]);
                    Log.d(TAG, " params i " + params[i] + " params i+1"
                            + params[i + 1] + " maxlistcount "
                            + appParams.MaxListCount);
                    if(validateTag((long)appParams.MaxListCount, (long) params[i-1],
                                   (long) BluetoothMasSpecParams.MAS_TAG_MAX_LIST_COUNT_MIN_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_MAX_LIST_COUNT_MAX_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_MAX_LIST_COUNT_LEN ) == false){
                        return false;
                    }
                    i += BluetoothMasSpecParams.MAS_TAG_MAX_LIST_COUNT_LEN;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_LIST_START_OFFSET:
                    i += 2;
                    appParams.ListStartOffset = getUint16BigEndian(params[i],
                            params[i + 1]);
                    Log.d(TAG, " params i " + params[i] + " params i+1"
                            + params[i + 1] + " maxlistcount "
                            + appParams.ListStartOffset);
                    if(validateTag((long)appParams.ListStartOffset, (long) params[i-1],
                                   (long) BluetoothMasSpecParams.MAS_TAG_LIST_START_OFFSET_MIN_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_LIST_START_OFFSET_MAX_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_LIST_START_OFFSET_LEN ) == false){
                        return false;
                    }
                    i += BluetoothMasSpecParams.MAS_TAG_LIST_START_OFFSET_LEN;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_FILTER_PERIOD_BEGIN:
                    i += 1;
                    appParams.FilterPeriodBegin = new String("");
                    for (int j = 1; j < params[i]; j++) {
                        appParams.FilterPeriodBegin += (char) params[i + j];
                    }
                    Log.d(TAG, "FilterPeriodBegin "
                            + appParams.FilterPeriodBegin);
                    i += params[i];
                    i += 1;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_FILTER_PERIOD_END:
                    i += 1;
                    appParams.FilterPeriodEnd = new String("");
                    for (int j = 1; j < params[i]; j++) {
                        appParams.FilterPeriodEnd += (char) params[i + j];
                    }
                    Log.d(TAG, "FilterPeriodEnd " + appParams.FilterPeriodEnd);
                    i += params[i];
                    i += 1;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_FILTER_RECIPIENT:
                    i += 1;
                    appParams.FilterRecipient = new String("");
                    for (int j = 1; j < params[i]; j++) {
                        appParams.FilterRecipient += (char)params[i + j];

                    }
                    Log.d(TAG, "FilterPeriodRecipient "
                            + appParams.FilterRecipient);
                    i += params[i];
                    i += 1;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_FILTER_ORIGINATOR:
                    i += 1;
                    appParams.FilterOriginator = new String("");
                    for (int j = 1; j < params[i]; j++) {
                        appParams.FilterOriginator += (char) params[i+ j];
                    }
                    Log.d(TAG, "FilterPeriodOriginator "
                            + appParams.FilterOriginator);
                    i += params[i];
                    i += 1;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_FILTER_MESSAGE_TYPE:
                    i += 2;
                    appParams.FilterMessageType = params[i];
                    Log.d(TAG, " params i " + params[i] + " FilterMessageType "
                            + appParams.FilterMessageType);
                    if(validateTag((long)appParams.FilterMessageType, (long) params[i-1],
                                   (long) BluetoothMasSpecParams.MAS_TAG_FILTER_MESSAGE_TYPE_MIN_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_FILTER_MESSAGE_TYPE_MAX_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_FILTER_MESSAGE_TYPE_LEN ) == false){
                        return false;
                    }
                    i += BluetoothMasSpecParams.MAS_TAG_FILTER_MESSAGE_TYPE_LEN;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_FILTER_READ_STATUS:
                    i += 2;
                    appParams.FilterReadStatus = params[i];
                    Log.d(TAG, " params i " + params[i] + " FilterReadStatus "
                            + appParams.FilterReadStatus);
                    if(validateTag((long)appParams.FilterReadStatus, (long) params[i-1],
                                   (long) BluetoothMasSpecParams.MAS_TAG_FILTER_READ_STATUS_MIN_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_FILTER_READ_STATUS_MAX_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_FILTER_READ_STATUS_LEN ) == false){
                        return false;
                    }
                    i += BluetoothMasSpecParams.MAS_TAG_FILTER_READ_STATUS_LEN;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_FILTER_PRIORITY:
                    i += 2;
                    appParams.FilterPriority = params[i];
                    Log.d(TAG, " params i " + params[i] + " FilterPriority "
                            + appParams.FilterPriority);
                    if(validateTag((long)appParams.FilterPriority, (long) params[i-1],
                                   (long) BluetoothMasSpecParams.MAS_TAG_FILTER_PRIORITY_MIN_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_FILTER_PRIORITY_MAX_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_FILTER_PRIORITY_LEN ) == false){
                        return false;
                    }
                    i += BluetoothMasSpecParams.MAS_TAG_FILTER_PRIORITY_LEN;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_STATUS_INDICATOR:
                    i += 2;
                    appParams.StatusIndicator = params[i];
                    Log.d(TAG, " params i " + params[i] + " StatusIndicator "
                            + appParams.StatusIndicator);
                    if(validateTag((long)appParams.StatusIndicator, (long) params[i-1],
                                   (long) BluetoothMasSpecParams.MAS_TAG_STATUS_INDICATOR_MIN_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_STATUS_INDICATOR_MAX_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_STATUS_INDICATOR_LEN ) == false){
                        return false;
                    }
                    i += BluetoothMasSpecParams.MAS_TAG_STATUS_INDICATOR_LEN;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_STATUS_VALUE:
                    i += 2;
                    appParams.StatusValue = params[i];
                    Log.d(TAG, " params i " + params[i] + " StatusValue "
                            + appParams.StatusValue);
                    if(validateTag((long)appParams.StatusValue, (long) params[i-1],
                                   (long) BluetoothMasSpecParams.MAS_TAG_STATUS_VALUE_MIN_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_STATUS_VALUE_MAX_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_STATUS_VALUE_LEN ) == false){
                        return false;
                    }
                    i += BluetoothMasSpecParams.MAS_TAG_STATUS_VALUE_LEN;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_SUBJECT_LENGTH:
                    i += 2;
                    appParams.SubjectLength = (short)(params[i] & 0x00FF);
                    Log.d(TAG, " params i " + params[i] + " SubjectLen "
                                + appParams.SubjectLength);
                    if(validateTag((long)appParams.SubjectLength, (long) params[i-1],
                                         (long) BluetoothMasSpecParams.MAS_TAG_SUBJECT_LENGTH_MIN_VAL,
                                         (long) BluetoothMasSpecParams.MAS_TAG_SUBJECT_LENGTH_MAX_VAL,
                                         (long) BluetoothMasSpecParams.MAS_TAG_SUBJECT_LENGTH_LEN ) == false){
                        return false;
                    }
                    i += BluetoothMasSpecParams.MAS_TAG_SUBJECT_LENGTH_LEN;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_PARAMETER_MASK:
                    i += 2;
                    appParams.ParameterMask = getUint32BigEndian(params[i],
                            params[i + 1], params[i + 2], params[i + 3]);
                    if ( appParams.ParameterMask == 0 ){
                        // If it is 0, send all parameters
                        appParams.ParameterMask = BluetoothMasSpecParams.MAS_DEFAULT_PARAMETER_MASK;
                    }
                    Log.d(TAG, " params i " + params[i] + " params i+1"
                            + params[i + 1] + "params[i+2]" + params[i + 2]
                            + "params[i+3" + params[i + 3] + " ParameterMask "
                            + appParams.ParameterMask);
                    if(validateTag((long)appParams.ParameterMask, (long) params[i-1],
                                   (long) BluetoothMasSpecParams.MAS_TAG_PARAMETER_MASK_MIN_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_PARAMETER_MASK_MAX_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_PARAMETER_MASK_LEN ) == false){
                        return false;
                    }
                    i += BluetoothMasSpecParams.MAS_TAG_PARAMETER_MASK_LEN;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_CHARSET:
                    i += 2;
                    appParams.Charset = params[i];
                    Log.d(TAG, " params i " + params[i] + " Charset "
                    + appParams.Charset);
                    if(validateTag((long)appParams.Charset, (long) params[i-1],
                                          (long) BluetoothMasSpecParams.MAS_TAG_CHARSET_MIN_VAL,
                                          (long) BluetoothMasSpecParams.MAS_TAG_CHARSET_MAX_VAL,
                                          (long) BluetoothMasSpecParams.MAS_TAG_CHARSET_LEN ) == false){
                        return false;
                    }
                    i += BluetoothMasSpecParams.MAS_TAG_CHARSET_LEN;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_TRANSPARENT:
                    i += 2;
                    appParams.Transparent = params[i];
                    Log.d(TAG, " params i " + params[i] + " Transparent "
                            + appParams.Transparent);
                    if(validateTag((long)appParams.Transparent, (long) params[i-1],
                                   (long) BluetoothMasSpecParams.MAS_TAG_TRANSPARENT_MIN_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_TRANSPARENT_MAX_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_TRANSPARENT_LEN ) == false){
                        return false;
                    }
                    i += BluetoothMasSpecParams.MAS_TAG_TRANSPARENT_LEN;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_RETRY:
                    i += 2;
                    appParams.Retry = params[i];
                    Log.d(TAG, " params i " + params[i] + " Retry "
                            + appParams.Retry);
                    if(validateTag((long)appParams.Retry, (long) params[i-1],
                                   (long) BluetoothMasSpecParams.MAS_TAG_RETRY_MIN_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_RETRY_MAX_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_RETRY_LEN ) == false){
                        return false;
                    }
                    i += BluetoothMasSpecParams.MAS_TAG_RETRY_LEN;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_ATTACHMENT:
                    i += 2;
                    appParams.Attachment = params[i];
                    Log.d(TAG, " params i " + params[i] + " Attachment "
                            + appParams.Attachment);
                    if(validateTag((long)appParams.Attachment, (long) params[i-1],
                                   (long) BluetoothMasSpecParams.MAS_TAG_ATTACHMENT_MIN_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_ATTACHMENT_MAX_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_ATTACHMENT_LEN ) == false){
                        return false;
                    }
                    i += BluetoothMasSpecParams.MAS_TAG_ATTACHMENT_LEN;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_FRACTION_REQUEST:
                    i += 2;
                    appParams.FractionRequest = params[i];
                    Log.d(TAG, " params i " + params[i] + " Fraction Request "
                            + appParams.FractionRequest);
                    if(validateTag((long)appParams.FractionRequest, (long) params[i-1],
                                   (long) BluetoothMasSpecParams.MAS_TAG_FRACTION_REQUEST_MIN_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_FRACTION_REQUEST_MAX_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_FRACTION_REQUEST_LEN ) == false){
                        return false;
                    }
                    i += BluetoothMasSpecParams.MAS_TAG_FRACTION_REQUEST_LEN;
                    break;

                case BluetoothMasSpecParams.MAS_TAG_NOTIFICATION_STATUS:
                    i += 2;
                    appParams.Notification = params[i];
                    if(validateTag((long)appParams.MaxListCount, (long) params[i-1],
                                   (long) BluetoothMasSpecParams.MAS_TAG_NOTIFICATION_STATUS_MIN_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_NOTIFICATION_STATUS_MAX_VAL,
                                   (long) BluetoothMasSpecParams.MAS_TAG_NOTIFICATION_STATUS_LEN ) == false){
                        return false;

                    }
                    i += BluetoothMasSpecParams.MAS_TAG_NOTIFICATION_STATUS_LEN;
                    break;
                default:
                    break;

                }
            }
            return true;
        }
    }

    private MasAppParamsStore masAppParams = new MasAppParamsStore();

    public BluetoothMasObexServer(Handler callback,
            BluetoothDevice remoteDevice, Context context) {
        super();
        appIf = new BluetoothMasAppIf(context, "SMS_MMS_EMAIL");
        mConnectionId = -1;
        mCallback = callback;
        mContext = context;
        mRemoteDevice = remoteDevice;

        Log.d(TAG, "BlueoothMasObexServer const called");
        // set initial value when ObexServer created
        if (D) Log.d(TAG, "Initialize MasObexServer");
    }

    @Override
    public int onConnect(final HeaderSet request, HeaderSet reply) {
        if (D) Log.d(TAG, "onConnect()");
        try {
            byte[] uuid = (byte[]) request.getHeader(HeaderSet.TARGET);
            if (uuid == null) {
                Log.w(TAG, "Null UUID ");
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
            if (D)
                Log.d(TAG, "onConnect(): uuid=" + Arrays.toString(uuid));

            if (uuid.length != UUID_LENGTH) {
                Log.w(TAG, "Wrong UUID length");
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
            for (int i = 0; i < UUID_LENGTH; i++) {
                if (uuid[i] != MAS_TARGET[i]) {
                    Log.w(TAG, "Wrong UUID");
                    return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
                }
            }
            reply.setHeader(HeaderSet.WHO, uuid);
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        try {
            byte[] remote = (byte[]) request.getHeader(HeaderSet.WHO);
            if (remote != null) {
                if (D) Log.d(TAG, "onConnect(): remote=" + Arrays.toString(remote));
                reply.setHeader(HeaderSet.TARGET, remote);
            }
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        if (V) Log.v(TAG, "onConnect(): uuid is ok, will send out "
            + "MSG_SESSION_ESTABLISHED msg.");

        Message msg = Message.obtain(mCallback);
        msg.what = BluetoothMasService.MSG_SESSION_ESTABLISHED;
        msg.sendToTarget();

        state = MasState.MAS_SERVER_CONNECTED;
        if (D) Log.d(TAG, "Connect(): Success");
        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public void onDisconnect(final HeaderSet req, final HeaderSet resp) {
        if (D) Log.d(TAG, "onDisconnect(): enter");
        appIf.disconnect();

        resp.responseCode = ResponseCodes.OBEX_HTTP_OK;
        if (mCallback != null) {
            Message msg = Message.obtain(mCallback);
            msg.what = BluetoothMasService.MSG_SESSION_DISCONNECTED;
            msg.sendToTarget();
            if (V) Log.v(TAG,"onDisconnect(): msg MSG_SESSION_DISCONNECTED sent out.");
        }

        // MNS Service
        appIf.stopMnsSession(mRemoteDevice);

        state = MasState.MAS_SERVER_DISCONNECTED;
    }

    @Override
    public int onAbort(HeaderSet request, HeaderSet reply) {
        if (D) Log.e(TAG, "onAbort(): enter.");
        sIsAborted = true;
        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public int onSetPath(final HeaderSet request, final HeaderSet reply,
            final boolean backup, final boolean create) {

        if (D) Log.e(TAG, "onSetPath(): supports SetPath request.");

        String tmpPath = null;
        boolean retVal;
        boolean tmpBackup = backup;

        if (tmpBackup && create) {
            tmpBackup = true;
        } else {
            tmpBackup = false;
        }
        if (state != MasState.MAS_SERVER_CONNECTED) {
            if (D)
                Log.e(TAG, "onSetPath() Failed: Mas Server not connected");
            return ResponseCodes.OBEX_HTTP_UNAVAILABLE;
        }
        state = MasState.MAS_SERVER_SET_FOLDER;

        try {
            tmpPath = (String) request.getHeader(HeaderSet.NAME);
        } catch (IOException e) {
            Log.e(TAG, "Get name header fail");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        if (D)
            Log.e(TAG, "backup=" + backup + " create=" + create + " name="
                    + tmpPath);

        retVal = appIf.setPath(backup, tmpPath);
        state = MasState.MAS_SERVER_CONNECTED;
        if (retVal == true) {
            if (V)
                Log.e(TAG, "SetPath to" + tmpPath + "SUCCESS");
            return ResponseCodes.OBEX_HTTP_OK;
        } else {
            Log.e(TAG, "Path not found");
            return ResponseCodes.OBEX_HTTP_NOT_FOUND;
        }
    }

    @Override
    public void onClose() {

        if (mCallback != null) {
            Message msg = Message.obtain(mCallback);
            msg.what = BluetoothMasService.MSG_SERVERSESSION_CLOSE;
            msg.sendToTarget();
            if (D) Log.e(TAG, "onClose(): msg MSG_SERVERSESSION_CLOSE sent out.");
        }
    }

    @Override
    public int onGet(Operation op) {

        byte[] appParams = null;
        boolean retVal = true;

        if (D) Log.e(TAG, "onGet(): support GET request.");

        sIsAborted = false;
        HeaderSet request = null;
        String type = "";
        String name = "";

        // TBD - IncompleteGet handling
        try {
            request = op.getReceivedHeader();
            type = (String) request.getHeader(HeaderSet.TYPE);
            name = (String) request.getHeader(HeaderSet.NAME);
            appParams = (byte[])request.getHeader(HeaderSet.APPLICATION_PARAMETER);
        } catch (IOException e) {
            Log.e(TAG, "request headers error");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        masAppParams.clear();
        retVal = masAppParams.parse(appParams);

        if (type == null || (retVal == false) ) {
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        Log.e(TAG, "type = " + type);

        if (type.equals(TYPE_LISTING)) {
            return sendFolderListing(op);
        }
        if (type.equals(TYPE_MESSAGE_LISTING)) {
            return sendMsgListing(op, name);
        }
        if (type.equals(TYPE_MESSAGE)) {
            return sendMsg(op, name);
        }

        Log.e(TAG, "get returns HTTP_BAD_REQUEST");
        return ResponseCodes.OBEX_HTTP_BAD_REQUEST;

    }

    private final int pushMsg(Operation op, String name) {
        // TBD - Need to do this on a per masinstance basis
        String DEFAULT_FILE = "PushMsg.txt";
        int outputBufferSize = op.getMaxPacketSize();
        int readLength = 0;
        long timestamp = 0;
        int position = 0;
        byte[] b = new byte[outputBufferSize];
        BufferedOutputStream bos = null;
        InputStream is = null;
        boolean error = false;
        File file = null;
        BluetoothMasPushMsgRsp pMsg;

        file = new File(mContext.getFilesDir() + "/" + DEFAULT_FILE);

        try {
            is = op.openInputStream();
        } catch (IOException e1) {
            Log.e(TAG, "Error while opening InputStream");
            error = true;
        }

        if (error != true) {
            try {

                FileOutputStream fos = mContext.openFileOutput(DEFAULT_FILE,
                        Context.MODE_PRIVATE);

                bos = new BufferedOutputStream(fos);
            } catch (FileNotFoundException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        if (error != true) {
            try {
                while (true) {
                    if (V) {
                        timestamp = System.currentTimeMillis();
                    }
                    readLength = is.read(b);
                    if (readLength == -1) {
                        if (D) {
                            Log.d(TAG, "Receive file reached stream end at position" + position);
                        }
                        break;
                    }
                    bos.write(b, 0, readLength);
                    position += readLength;
                    if (V) {
                        Log.v(TAG, "Receive file position = " + position
                                + " readLength " + readLength + " bytes took "
                                + (System.currentTimeMillis() - timestamp)
                                + " ms");
                    }
                }
            } catch (IOException e1) {
                Log.e(TAG, "Error when receiving file");
                error = true;
            }
        }

        if (bos != null) {
            try {
                bos.close();
            } catch (IOException e) {
                Log.e(TAG, "Error when closing stream after send");
                error = true;
            }
        }

        if (error != true) {
            pMsg = appIf.pushMsg(name, file, masAppParams.get());

            if ((pMsg.msgHandle != null)
                    && (pMsg.response == ResponseCodes.OBEX_HTTP_OK)) {
                HeaderSet reply;
                reply = new HeaderSet();
                reply.setHeader(HeaderSet.NAME, pMsg.msgHandle);
                return pushHeader(op, reply);

            } else {
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }
        } else {
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }
    }

    private final int msgStatus(Operation op, String name) {
        if (D) Log.d(TAG, "msgStatus: Enter");
        return appIf.msgStatus(name, masAppParams.get());
    }

    private final int msgUpdate(Operation op, String name) {
        if (D) Log.d(TAG, "msgUpdate: Enter");
        return appIf.msgUpdate(name, masAppParams.get());
    }

    private final int notification(Operation op) {
        return appIf.notification(mRemoteDevice, masAppParams.get());
    }

    @Override
    public int onPut(Operation op) {

        byte[] appParams = null;
        boolean retVal = true;
        BluetoothMasAppParams tmp;

        if (D) Log.d(TAG, "onPut(): support PUT request.");

        sIsAborted = false;
        HeaderSet request = null;
        String type = "";
        String name = "";

        // TBD - IncompleteGet handling
        try {
            request = op.getReceivedHeader();
            type = (String) request.getHeader(HeaderSet.TYPE);
            name = (String) request.getHeader(HeaderSet.NAME);
            appParams = (byte[])request.getHeader(HeaderSet.APPLICATION_PARAMETER);
        } catch (IOException e) {
            Log.e(TAG, "request headers error");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        masAppParams.clear();
        if ( appParams != null ){
            masAppParams.parse(appParams);
        }
        if(type == null || retVal == false) {
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        tmp = masAppParams.get();

        if (tmp.Charset == 0x00) {
            return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
        }
        Log.e(TAG, "type = " + type);

        if (type.equals(TYPE_MESSAGE)) {
            return pushMsg(op, name);
        }
        if (type.equals(TYPE_MESSAGE_STATUS)) {
            return msgStatus(op, name);
        }
        if (type.equals(TYPE_MESSAGE_UPDATE)) {
            return msgUpdate(op, name);
        }
        if (type.equals(TYPE_MESSAGE_NOTIFICATION)) {
            return notification(op);
        }
        Log.e(TAG, "put returns HTTP_BAD_REQUEST");
        return ResponseCodes.OBEX_HTTP_BAD_REQUEST;

    }

    /**
    */
    private final int pushHeader(final Operation op, final HeaderSet reply) {

        if (D) Log.d(TAG, "Push Header");
        if (D) Log.d(TAG, reply.toString());

        int pushResult = ResponseCodes.OBEX_HTTP_OK;
        try {
            op.sendHeaders(reply);
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            pushResult = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        if (D) Log.d(TAG, "Push Header: Exit : RetVal " + pushResult);
        return pushResult;
    }

    /** Function to send folder data to client */
    private final int sendFolderListingBody(Operation op,
            final String folderlistString) {

        if (folderlistString == null) {
            Log.e(TAG, "folderlistString is null!");
            return ResponseCodes.OBEX_HTTP_OK;
        }

        int folderlistStringLen = folderlistString.length();
        if (D) Log.d(TAG, "Send Folder Listing Body: len=" + folderlistStringLen);

        OutputStream outputStream = null;
        int pushResult = ResponseCodes.OBEX_HTTP_OK;
        try {
            outputStream = op.openOutputStream();
        } catch (IOException e) {
            Log.e(TAG, "open outputstrem failed" + e.toString());
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        int position = 0;
        long timestamp = 0;
        int outputBufferSize = op.getMaxPacketSize();
        if (V) Log.d(TAG, "outputBufferSize = " + outputBufferSize);
        while (position != folderlistStringLen) {
            if (sIsAborted) {
                ((ServerOperation) op).isAborted = true;
                sIsAborted = false;
                break;
            }
            if (V) timestamp = System.currentTimeMillis();
            int readLength = outputBufferSize;
            if (folderlistStringLen - position < outputBufferSize) {
                readLength = folderlistStringLen - position;
            }
            String subStr = folderlistString.substring(position, position + readLength);
            try {
                outputStream.write(subStr.getBytes(), 0, readLength);
            } catch (IOException e) {
                Log.e(TAG, "write outputstream failed" + e.toString());
                pushResult = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                break;
            }
            if (V) {
                Log.d(TAG, "Sending folderlist String position = " + position
                        + " readLength " + readLength + " bytes took "
                        + (System.currentTimeMillis() - timestamp) + " ms");
            }
            position += readLength;
        }

        if (V) Log.e(TAG, "Send Data complete!");

        if (!closeStream(outputStream, op)) {
            Log.e(TAG,"Send Folder Listing Body - Close output stream error! ");
            pushResult = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        if (V) Log.e(TAG, "Send Folder Listing Body complete! result = " + pushResult);
        return pushResult;
    }

    private final int sendBody(Operation op, File fileinfo) {

        Log.e(TAG, "sendFile = " + fileinfo.getName());
        int position = 0;
        int readLength = 0;
        int outputBufferSize = op.getMaxPacketSize();
        long timestamp = 0;
        FileInputStream fileInputStream;
        OutputStream outputStream;
        BufferedInputStream bis;

        if (D) Log.d(TAG, "Send Body: Enter");
        try {
            byte[] buffer = new byte[outputBufferSize];
            fileInputStream = new FileInputStream(fileinfo);
            outputStream = op.openOutputStream();
            bis = new BufferedInputStream(fileInputStream, 0x4000);
            while ((position != fileinfo.length())) {
                timestamp = System.currentTimeMillis();
                if (position != fileinfo.length()) {
                    readLength = bis.read(buffer, 0, outputBufferSize);
                }
                outputStream.write(buffer, 0, readLength);
                position += readLength;
                if (V) {
                    Log.e(TAG, "Sending file position = " + position
                            + " readLength " + readLength + " bytes took "
                            + (System.currentTimeMillis() - timestamp) + " ms");
                }
            }
        } catch (IOException e) {
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }
        if (position == fileinfo.length()) {
            if (D) Log.d(TAG, "SendBody : Exit: OK");
            return ResponseCodes.OBEX_HTTP_OK;
        }
        else {
            if (D) Log.d(TAG, "SendBody : Exit: CONTINUE");
            return ResponseCodes.OBEX_HTTP_CONTINUE;
        }
    }

    /** Send a bMessage to client */
    private final int sendMsg(Operation op, String name) {

        BluetoothMasMessageRsp msg;
        byte[] val = new byte[1];

        if (D) Log.d(TAG, "SendMsg : Enter");
        msg = appIf.msg(name, masAppParams.get());
        if(msg == null || msg.rsp != ResponseCodes.OBEX_HTTP_OK) {
            return msg.rsp;
        }

        if(masAppParams.get().FractionRequest == 1){
            HeaderSet reply;
            val[0] = msg.fractionDeliver;
            ApplicationParameter ap = new ApplicationParameter();
            ap.addAPPHeader(
                (byte) BluetoothMasSpecParams.MAS_TAG_FRACTION_DELIVER,
                (byte) BluetoothMasSpecParams.MAS_TAG_FRACTION_DELIVER_LEN,
                 val);

            reply = new HeaderSet();
            reply.setHeader(HeaderSet.APPLICATION_PARAMETER, ap.getAPPparam());

            int retVal;
            retVal = pushHeader(op, reply);
            if (retVal != ResponseCodes.OBEX_HTTP_OK) {
                if (D) Log.d(TAG, "SendMsg : FAILED: RetVal " + retVal);
                 return retVal;
            }
        }
        if (D) Log.d(TAG, "SendMsg : SUCCESS");
        return sendBody(op, msg.file);
    }

    /** Send an XML format String to client for Folder listing */
    private final int sendFolderListing(Operation op) {
        int folderListSize;

        if (D) Log.d(TAG, "SendFolderListing : Enter");
        folderListSize = appIf.folderListingSize();
        byte[] size = new byte[2];
        size[0] = (byte) ((folderListSize / 0x100) & 0xff);
        size[1] = (byte) ((folderListSize % 0x100) & 0xff);

        HeaderSet reply;
        ApplicationParameter ap = new ApplicationParameter();
        ap.addAPPHeader(
            (byte) BluetoothMasSpecParams.MAS_TAG_FOLDER_LISTING_SIZE,
            (byte) BluetoothMasSpecParams.MAS_TAG_FOLDER_LISTING_SIZE_LEN,
            size);
        reply = new HeaderSet();
        reply.setHeader(HeaderSet.APPLICATION_PARAMETER, ap.getAPPparam());

        if (!masAppParams.isMaxListCountZero()) {
            int retVal;
            retVal = pushHeader(op, reply);
            if (retVal != ResponseCodes.OBEX_HTTP_OK) {
                if (D) Log.d(TAG, "SendFolderListing : FAILED : RetVal" + retVal);
                return retVal;
            }
            return sendFolderListingBody(op, appIf.folderListing(masAppParams.get()));
        } else {
            op.noEndofBody();
            return pushHeader(op, reply);
        }
    }

    /** Send an XML format String to client for Message listing */
    private final int sendMsgListing(Operation op, String name) {

        byte[] val = new byte[2];

        BluetoothMasMessageListingRsp appIfMsgListRsp = appIf.msgListing(name,
                masAppParams.get());

        if(appIfMsgListRsp == null || appIfMsgListRsp.rsp != ResponseCodes.OBEX_HTTP_OK) {
            return appIfMsgListRsp.rsp;
        }

        if (D) Log.d(TAG, "SendMsgListing : Enter");

        Time time = new Time();
        time.setToNow();

        String time3339 = time.format3339(false);
        int timeStrLength = time3339.length();

        String datetimeStr = time.toString().substring(0, 15) +
                time3339.substring(timeStrLength - 6, timeStrLength - 3) +
                time3339.substring(timeStrLength - 2, timeStrLength);

        byte[] MSETime = datetimeStr.getBytes();

        HeaderSet reply;
        ApplicationParameter ap = new ApplicationParameter();
        ap.addAPPHeader((byte) BluetoothMasSpecParams.MAS_TAG_MSE_TIME,
                (byte) BluetoothMasSpecParams.MAS_TAG_MSE_TIME_LEN, MSETime);
        val[0] = appIfMsgListRsp.newMessage;
        ap.addAPPHeader((byte) BluetoothMasSpecParams.MAS_TAG_NEW_MESSAGE,
                (byte) BluetoothMasSpecParams.MAS_TAG_NEW_MESSAGE_LEN, val);

        val[0] = (byte) ((appIfMsgListRsp.msgListingSize / 0x100) & 0xff);
        val[1] = (byte) ((appIfMsgListRsp.msgListingSize % 0x100) & 0xff);

        ap.addAPPHeader(
                (byte) BluetoothMasSpecParams.MAS_TAG_MESSAGE_LISTING_SIZE,
                (byte) BluetoothMasSpecParams.MAS_TAG_MESSAGE_LISTING_SIZE_LEN,
                val);

        reply = new HeaderSet();
        reply.setHeader(HeaderSet.APPLICATION_PARAMETER, ap.getAPPparam());

        if (!masAppParams.isMaxListCountZero()) {
            int retVal;
            retVal = pushHeader(op, reply);
            if (retVal != ResponseCodes.OBEX_HTTP_OK) {
                if (D) Log.d(TAG, "SendMsgListing : Failed : RetVal " + retVal);
                return retVal;
            }
            return sendBody(op, appIfMsgListRsp.file);
        } else {
            return pushHeader(op, reply);
        }
    }

    public static boolean closeStream(final OutputStream out, final Operation op) {
        boolean returnvalue = true;
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "outputStream close failed" + e.toString());
            returnvalue = false;
        }
        try {
            if (op != null) {
                op.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "operation close failed" + e.toString());
            returnvalue = false;
        }
        return returnvalue;
    }
};

