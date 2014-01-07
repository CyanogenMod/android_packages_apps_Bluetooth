/*
* Copyright (C) 2013 Samsung System LSI
* Copyright (C) 2013, The Linux Foundation. All rights reserved.
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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Calendar;
import android.os.PowerManager;

import javax.obex.HeaderSet;
import javax.obex.Operation;
import javax.obex.ResponseCodes;
import javax.obex.ServerRequestHandler;

import com.android.bluetooth.map.BluetoothMapUtils;
import com.android.bluetooth.map.BluetoothMapUtils.TYPE;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import java.util.List;
import java.util.ArrayList;


public class BluetoothMapObexServer extends ServerRequestHandler {

    private static final String TAG = "BluetoothMapObexServer";

    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = BluetoothMapService.VERBOSE;

    private static final int UUID_LENGTH = 16;

    // 128 bit UUID for MAP
    private static final byte[] MAP_TARGET = new byte[] {
             (byte)0xBB, (byte)0x58, (byte)0x2B, (byte)0x40,
             (byte)0x42, (byte)0x0C, (byte)0x11, (byte)0xDB,
             (byte)0xB0, (byte)0xDE, (byte)0x08, (byte)0x00,
             (byte)0x20, (byte)0x0C, (byte)0x9A, (byte)0x66
             };

    /* Message types */
    private static final String TYPE_GET_FOLDER_LISTING  = "x-obex/folder-listing";
    private static final String TYPE_GET_MESSAGE_LISTING = "x-bt/MAP-msg-listing";
    private static final String TYPE_MESSAGE             = "x-bt/message";
    private static final String TYPE_SET_MESSAGE_STATUS  = "x-bt/messageStatus";
    private static final String TYPE_SET_NOTIFICATION_REGISTRATION = "x-bt/MAP-NotificationRegistration";
    private static final String TYPE_MESSAGE_UPDATE      = "x-bt/MAP-messageUpdate";

    private BluetoothMapFolderElement mCurrentFolder;

    private BluetoothMnsObexClient mMnsClient;

    private Handler mCallback = null;

    private Context mContext;

    private PowerManager.WakeLock mWakeLock = null;

    private int mMasId;
    // Types of mailboxes. From EmailContent.java
    // inbox
    public static final int TYPE_INBOX = 0;
    // draft
    public static final int TYPE_DRAFT = 3;
    // outbox
    public static final int TYPE_OUTBOX = 4;
    // sent
    public static final int TYPE_SENT = 5;
    // deleted
    public static final int TYPE_DELETED = 6;

    public static final String INBOX = "inbox";
    public static final String OUTBOX = "outbox";
    public static final String SENT = "sent";
    public static final String DELETED = "deleted";
    public static final String DRAFT = "draft";
    public static final String DRAFTS = "drafts";
    public static final String UNDELIVERED = "undelivered";
    public static final String FAILED = "failed";
    public static final String QUEUED = "queued";

    private static final int[] SPECIAL_MAILBOX_TYPES
            = {TYPE_INBOX, TYPE_DRAFT, TYPE_OUTBOX, TYPE_SENT, TYPE_DELETED};
    private static final String[] SPECIAL_MAILBOX_MAP_NAME
            = {INBOX, DRAFT, OUTBOX, SENT, DELETED};

    public static boolean sIsAborted = false;

    BluetoothMapContent mOutContent;

    public BluetoothMapObexServer(Handler callback, Context context,
                                  BluetoothMnsObexClient mns, int masId) {
        super();
        mCallback = callback;
        mContext = context;
        mMasId = masId;
        mMnsClient = mns;
        mOutContent = new BluetoothMapContent(mContext);
        if(mMasId == 0) {
           buildFolderStructure();
        } else {
       buildFolderStructureEmail(); /* Build the default folder structure, and set
                                   mCurrentFolder to root folder */
        }
    }


    /**
     * Build the default minimal folder structure, as defined in the MAP specification.
     */
    private void buildFolderStructure(){
        mCurrentFolder = new BluetoothMapFolderElement("root", null); // This will be the root element
        BluetoothMapFolderElement tmpFolder;
        tmpFolder = mCurrentFolder.addFolder("telecom"); // root/telecom
        tmpFolder = tmpFolder.addFolder("msg");          // root/telecom/msg
        tmpFolder.addFolder("inbox");                    // root/telecom/msg/inbox
        tmpFolder.addFolder("outbox");
        tmpFolder.addFolder("sent");
        tmpFolder.addFolder("deleted");
        tmpFolder.addFolder("draft");
    }


    /**
     * Build the default minimal folder structure, as defined in the MAP specification.
     */
    private void buildFolderStructureEmail(){
        mCurrentFolder = new BluetoothMapFolderElement("root", null); // This will be the root element
        BluetoothMapFolderElement tmpFolder;
        tmpFolder = mCurrentFolder.addFolder("telecom"); // root/telecom
        tmpFolder = tmpFolder.addFolder("msg");          // root/telecom/msg
    }

    @Override
    public int onConnect(final HeaderSet request, HeaderSet reply) {
        if (D) Log.d(TAG, "onConnect():");
        acquireMapLock();
        int retVal = onConnectInternal(request, reply);
        if (V) Log.v(TAG, "BluetoothMapObexServer: exiting from onConnect");
        releaseMapLock();
        return retVal;
    }

    private int onConnectInternal(final HeaderSet request, HeaderSet reply) {
        if (D) Log.d(TAG, "onConnectInternal():");
        if (V) logHeader(request);
        try {
            byte[] uuid = (byte[])request.getHeader(HeaderSet.TARGET);
            if (uuid == null) {
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
            if (D) Log.d(TAG, "onConnect(): uuid=" + Arrays.toString(uuid));

            if (uuid.length != UUID_LENGTH) {
                Log.w(TAG, "Wrong UUID length");
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
            for (int i = 0; i < UUID_LENGTH; i++) {
                if (uuid[i] != MAP_TARGET[i]) {
                    Log.w(TAG, "Wrong UUID");
                    return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
                }
            }
            if (mMasId == 1) {
                if (BluetoothMapUtils.getEmailAccountId(mContext) == -1)
                    return ResponseCodes.OBEX_HTTP_UNAVAILABLE;
            }
            reply.setHeader(HeaderSet.WHO, uuid);
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        try {
            byte[] remote = (byte[])request.getHeader(HeaderSet.WHO);
            if (remote != null) {
                if (D) Log.d(TAG, "onConnect(): remote=" + Arrays.toString(remote));
                reply.setHeader(HeaderSet.TARGET, remote);
            }
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        if (V) Log.v(TAG, "onConnect(): uuid is ok, will send out " +
                "MSG_SESSION_ESTABLISHED msg.");


        Message msg = Message.obtain(mCallback);
        msg.what = BluetoothMapService.MSG_SESSION_ESTABLISHED;
        msg.sendToTarget();

        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public void onDisconnect(final HeaderSet req, final HeaderSet resp) {
        if (D) Log.d(TAG, "onDisconnect(): enter");
        acquireMapLock();
        onDisconnectInternal(req, resp);
        if (V) Log.v(TAG, "BluetoothMapObexServer: exiting from onDisconnect");
        releaseMapLock();
    }

    private void onDisconnectInternal(final HeaderSet req, final HeaderSet resp) {
        if (D) Log.d(TAG, "onDisconnectInternal(): enter");
        if (V) logHeader(req);

        resp.responseCode = ResponseCodes.OBEX_HTTP_OK;
        if (mCallback != null) {
            Message msg = Message.obtain(mCallback);
            msg.what = BluetoothMapService.MSG_SESSION_DISCONNECTED;
            msg.sendToTarget();
            if (V) Log.v(TAG, "onDisconnect(): msg MSG_SESSION_DISCONNECTED sent out.");
        }
    }

    @Override
    public int onAbort(HeaderSet request, HeaderSet reply) {
        if (D) Log.d(TAG, "onAbort(): enter.");
        sIsAborted = true;
        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public int onPut(final Operation op) {
    if (D) Log.d(TAG, "onPut(): enter");
        acquireMapLock();
        int retVal = onPutInternal(op);
        if (V) Log.v(TAG, "BluetoothMapObexServer: exiting from onPut");
        releaseMapLock();
        return retVal;
    }
    public int onPutInternal(final Operation op) {
        if (D) Log.d(TAG, "onPutInternal(): enter");
        HeaderSet request = null;
        String type, name;
        byte[] appParamRaw;
        BluetoothMapAppParams appParams = null;

        try {
            request = op.getReceivedHeader();
            type = (String)request.getHeader(HeaderSet.TYPE);
            name = (String)request.getHeader(HeaderSet.NAME);
            appParamRaw = (byte[])request.getHeader(HeaderSet.APPLICATION_PARAMETER);
            if(appParamRaw != null)
                appParams = new BluetoothMapAppParams(appParamRaw);
        } catch (Exception e) {
            Log.e(TAG, "request headers error");
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        if(D) Log.d(TAG,"type = " + type + ", name = " + name);
        if (type.equals(TYPE_MESSAGE_UPDATE)) {
            if(V) {
                Log.d(TAG,"TYPE_MESSAGE_UPDATE:");
            }
            if(mMasId == 1) {
               mOutContent.msgUpdate();
               return ResponseCodes.OBEX_HTTP_OK;
            } else {
            return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
            }
        }else if(type.equals(TYPE_SET_NOTIFICATION_REGISTRATION)) {
            if(V) {
                Log.d(TAG,"TYPE_SET_NOTIFICATION_REGISTRATION: NotificationStatus: " + appParams.getNotificationStatus());
            }
            return setNotificationRegistration(appParams);
        }else if(type.equals(TYPE_SET_MESSAGE_STATUS)) {
            if(V) {
                Log.d(TAG,"TYPE_SET_MESSAGE_STATUS: StatusIndicator: " + appParams.getStatusIndicator() + ", StatusValue: " + appParams.getStatusValue());
            }
            return setMessageStatus(name, appParams);
        } else if (type.equals(TYPE_MESSAGE)) {
            if(V) {
                Log.d(TAG,"TYPE_MESSAGE: Transparet: " + appParams.getTransparent() +  ", Retry: " + appParams.getRetry());
                Log.d(TAG,"              charset: " + appParams.getCharset());
            }
            return pushMessage(op, name, appParams);

        }

        return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
    }

    private int setNotificationRegistration(BluetoothMapAppParams appParams) {
        // Forward the request to the MNS thread as a message - including the MAS instance ID.
        Handler mns = mMnsClient.getMessageHandler();
        if(mns != null) {
            Message msg = Message.obtain(mns);
            msg.what = BluetoothMnsObexClient.MSG_MNS_NOTIFICATION_REGISTRATION;
            msg.arg1 = mMasId;
            msg.arg2 = appParams.getNotificationStatus();
            msg.sendToTarget();
            if(D) Log.d(TAG,"MSG_MNS_NOTIFICATION_REGISTRATION");
            return ResponseCodes.OBEX_HTTP_OK;
        } else {
            return ResponseCodes.OBEX_HTTP_UNAVAILABLE; // This should not happen.
        }
    }

    private int pushMessage(final Operation op, String folderName, BluetoothMapAppParams appParams) {
        if(appParams.getCharset() == BluetoothMapAppParams.INVALID_VALUE_PARAMETER) {
            if(D) Log.d(TAG, "Missing charset - unable to decode message content. appParams.getCharset() = " + appParams.getCharset());
            return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
        }
        try {
            if(folderName == null || folderName.equals("")) {
                folderName = mCurrentFolder.getName();
            }
            if(!folderName.equalsIgnoreCase("outbox") && !folderName.equalsIgnoreCase("draft") &&
                !folderName.equalsIgnoreCase("drafts")) {
                if(D) Log.d(TAG, "Push message only allowed to outbox and draft. folderName: " + folderName);
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
            /*  - Read out the message
             *  - Decode into a bMessage
             *  - send it.
             */
            InputStream bMsgStream;
            BluetoothMapbMessage message;
            bMsgStream = op.openInputStream();
            message = BluetoothMapbMessage.parse(bMsgStream, appParams.getCharset()); // Decode the messageBody
            // Send message
            BluetoothMapContentObserver observer = mMnsClient.getContentObserver(mMasId);
            if (observer == null) {
                return ResponseCodes.OBEX_HTTP_UNAVAILABLE; // Should not happen.
            }

            long handle = observer.pushMessage(message, folderName, appParams);
            if (D) Log.d(TAG, "pushMessage handle: " + handle);
            if (handle < 0) {
                return ResponseCodes.OBEX_HTTP_UNAVAILABLE; // Should not happen.
            }
            HeaderSet replyHeaders = new HeaderSet();
            String handleStr = BluetoothMapUtils.getMapHandle(handle, message.getType());
            if (D) Log.d(TAG, "handleStr: " + handleStr + " message.getType(): " + message.getType());
            replyHeaders.setHeader(HeaderSet.NAME, handleStr);
            op.sendHeaders(replyHeaders);

            bMsgStream.close();
        } catch (IllegalArgumentException e) {
            if(D) Log.w(TAG, "Wrongly formatted bMessage received", e);
            return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
        } catch (Exception e) {
            // TODO: Change to IOException after debug
            Log.e(TAG, "Exception occured: ", e);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }
        return ResponseCodes.OBEX_HTTP_OK;
    }

    private int setMessageStatus(String msgHandle, BluetoothMapAppParams appParams) {
        int indicator = appParams.getStatusIndicator();
        int value = appParams.getStatusValue();
        long handle;
        BluetoothMapUtils.TYPE msgType;

        if(indicator == BluetoothMapAppParams.INVALID_VALUE_PARAMETER ||
           value == BluetoothMapAppParams.INVALID_VALUE_PARAMETER ||
           msgHandle == null) {
            return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
        }
        BluetoothMapContentObserver observer = mMnsClient.getContentObserver(mMasId);
        if (observer == null) {
            return ResponseCodes.OBEX_HTTP_UNAVAILABLE; // Should not happen.
        }

        try {
            handle = BluetoothMapUtils.getCpHandle(msgHandle);
            msgType = BluetoothMapUtils.getMsgTypeFromHandle(msgHandle);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Wrongly formatted message handle: " + msgHandle);
            return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
        }

        if( indicator == BluetoothMapAppParams.STATUS_INDICATOR_DELETED) {
            if (!observer.setMessageStatusDeleted(handle, msgType, value)) {
                return ResponseCodes.OBEX_HTTP_UNAVAILABLE;
            }
        } else /* BluetoothMapAppParams.STATUS_INDICATOR_READE */ {
            if (!observer.setMessageStatusRead(handle, msgType, value)) {
                return ResponseCodes.OBEX_HTTP_UNAVAILABLE;
            }
        }
        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public int onSetPath(final HeaderSet request, final HeaderSet reply, final boolean backup,
            final boolean create) {
        if (D) Log.d(TAG, "onSetPath():");
        acquireMapLock();
        int retVal = onSetPathInternal(request, reply, backup, create);
        if (V) Log.v(TAG, "BluetoothMapObexServer: exiting from onSetPath");
        releaseMapLock();
        return retVal;
    }

    private int onSetPathInternal(final HeaderSet request, final HeaderSet reply, final boolean backup,
            final boolean create) {
        String folderName;
        BluetoothMapFolderElement folder;
        try {
            folderName = (String)request.getHeader(HeaderSet.NAME);
        } catch (Exception e) {
            Log.e(TAG, "request headers error");
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        if (V) logHeader(request);
        if (D) Log.d(TAG, "onSetPath name is " + folderName + " backup: " + backup
                     + "create: " + create);

        if(backup == true){
            if(mCurrentFolder.getParent() != null)
                mCurrentFolder = mCurrentFolder.getParent();
            else
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        if (folderName == null || folderName == "") {
            if(backup == false)
                mCurrentFolder = mCurrentFolder.getRoot();
        }
        else {
            folder = mCurrentFolder.getSubFolder(folderName);
            if(folder != null)
                mCurrentFolder = folder;
            else {
                Log.e(TAG, " folder name  " +folderName +"not found");
                Log.e(TAG, " Change folder failed");
                Log.e(TAG, " Do message listing before changing the folder");
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }
        }
        if (V) Log.d(TAG, "Current Folder: " + mCurrentFolder.getName());
        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public void onClose() {
        if (V) Log.v(TAG, "BluetoothMapObexServer: onClose");
        acquireMapLock();
        if (mCallback != null) {
            Message msg = Message.obtain(mCallback);
            msg.what = BluetoothMapService.MSG_SERVERSESSION_CLOSE;
            msg.arg1 = mMasId;
            msg.sendToTarget();
            if (D) Log.d(TAG, "onClose(): msg MSG_SERVERSESSION_CLOSE sent out.");
        }
        releaseMapLock();
    }

    @Override
    public int onGet(Operation op) {
    if (V) Log.v(TAG, "BluetoothMapObexServer: onGet");
        acquireMapLock();
        int retVal = onGetInternal(op);
        if (V) Log.v(TAG, "BluetoothMapObexServer: exiting from onGet");
        releaseMapLock();
        return retVal;
    }
    private int onGetInternal(Operation op) {
        sIsAborted = false;
        HeaderSet request;
        String type;
        String name;
        byte[] appParamRaw = null;
        BluetoothMapAppParams appParams = null;
        try {
            request = op.getReceivedHeader();
            type = (String)request.getHeader(HeaderSet.TYPE);
            name = (String)request.getHeader(HeaderSet.NAME);
            appParamRaw = (byte[])request.getHeader(HeaderSet.APPLICATION_PARAMETER);
            if(appParamRaw != null)
                appParams = new BluetoothMapAppParams(appParamRaw);

            if (V) logHeader(request);
            if (D) Log.d(TAG, "OnGet type is " + type + " name is " + name);

            if (type == null) {
                if (V) Log.d(TAG, "type is null?" + type);
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }

            if (type.equals(TYPE_GET_FOLDER_LISTING)) {
                if (V && appParams != null) {
                    Log.d(TAG,"TYPE_GET_FOLDER_LISTING: MaxListCount = " + appParams.getMaxListCount() +
                              ", ListStartOffset = " + appParams.getStartOffset());
                }
                return sendFolderListingRsp(op, appParams); // Block until all packets have been send.
            }
            else if (type.equals(TYPE_GET_MESSAGE_LISTING)){
                if (V && appParams != null) {
                    Log.d(TAG,"TYPE_GET_MESSAGE_LISTING: MaxListCount = " + appParams.getMaxListCount() +
                              ", ListStartOffset = " + appParams.getStartOffset());
                    Log.d(TAG,"SubjectLength = " + appParams.getSubjectLength() + ", ParameterMask = " +
                              appParams.getParameterMask());
                    Log.d(TAG,"FilterMessageType = " + appParams.getFilterMessageType() +
                              ", FilterPeriodBegin = " + appParams.getFilterPeriodBegin());
                    Log.d(TAG,"FilterPeriodEnd = " + appParams.getFilterPeriodBegin() +
                              ", FilterReadStatus = " + appParams.getFilterReadStatus());
                    Log.d(TAG,"FilterRecipient = " + appParams.getFilterRecipient() +
                              ", FilterOriginator = " + appParams.getFilterOriginator());
                    Log.d(TAG,"FilterPriority = " + appParams.getFilterPriority());
                }
                return sendMessageListingRsp(op, appParams, name); // Block until all packets have been send.
            }
            else if (type.equals(TYPE_MESSAGE)){
                if(V && appParams != null) {
                    Log.d(TAG,"TYPE_MESSAGE (GET): Attachment = " + appParams.getAttachment() + ", Charset = " + appParams.getCharset() +
                        ", FractionRequest = " + appParams.getFractionRequest());
                }
                return sendGetMessageRsp(op, name, appParams); // Block until all packets have been send.
            }
            else {
                Log.w(TAG, "unknown type request: " + type);
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
        } catch (Exception e) {
            // TODO: Move to the part that actually throws exceptions, and change to the correat exception type
            Log.e(TAG, "request headers error, Exception:", e);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }
    }

    /**
     * Generate and send the message listing response based on an application
     * parameter header. This function call will block until complete or aborted
     * by the peer. Fragmentation of packets larger than the obex packet size
     * will be handled by this function.
     *
     * @param op
     *            The OBEX operation.
     * @param appParams
     *            The application parameter header
     * @return {@link ResponseCodes.OBEX_HTTP_OK} on success or
     *         {@link ResponseCodes.OBEX_HTTP_BAD_REQUEST} on error.
     */
    private int sendMessageListingRsp(Operation op, BluetoothMapAppParams appParams, String folderName){
        OutputStream outStream = null;
        byte[] outBytes = null;
        int maxChunkSize, bytesToWrite, bytesWritten = 0, listSize;
        boolean hasUnread = false;
        HeaderSet replyHeaders = new HeaderSet();
        BluetoothMapAppParams outAppParams = new BluetoothMapAppParams();
        BluetoothMapMessageListing outList;
        if(folderName == null) {
            folderName = mCurrentFolder.getName();
        } else if(folderName.equalsIgnoreCase("draft") && mMasId ==1) {
            folderName="Drafts";
        }
        if(D) Log.d(TAG, "folderName is "+folderName);
        if(D) Log.d(TAG, "mCurrentFolder  is "+mCurrentFolder.getName());
        if(mCurrentFolder.getSubFolder(folderName) == null &&
            !(mCurrentFolder.getName().equalsIgnoreCase(folderName))) {
           Log.d(TAG, "Path not set. returning from here");
           return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }
        if(appParams == null){
            appParams = new BluetoothMapAppParams();
            appParams.setMaxListCount(1024);
            appParams.setStartOffset(0);
        }

        // Check to see if we only need to send the size - hence no need to encode.
        try {
            // Open the OBEX body stream
            outStream = op.openOutputStream();

            if(appParams.getMaxListCount() == BluetoothMapAppParams.INVALID_VALUE_PARAMETER)
                appParams.setMaxListCount(1024);

            if(appParams.getStartOffset() == BluetoothMapAppParams.INVALID_VALUE_PARAMETER)
                appParams.setStartOffset(0);

            if(appParams.getMaxListCount() != 0) {
               if(mMasId == 0)
                  outList = mOutContent.msgListing(folderName, appParams);
               else
                  outList = mOutContent.msgListingEmail(folderName, appParams);
                  // Generate the byte stream
                  outAppParams.setMessageListingSize(outList.getCount());
                  outBytes = outList.encode();
                  hasUnread = outList.hasUnread();
            }
            else {
               if(mMasId == 0){
                  listSize = mOutContent.msgListingSize(folderName, appParams);
                  hasUnread = mOutContent.msgListingHasUnread(folderName, appParams);
               } else {
                  listSize = mOutContent.msgListingSizeEmail(folderName, appParams);
                  hasUnread = mOutContent.msgListingHasUnreadEmail(folderName, appParams);
                }
                outAppParams.setMessageListingSize(listSize);
                Log.d(TAG, "not setting body and end of body header");
                op.noBodyHeader();
                op.noEndofBody();
            }

            // Build the application parameter header

            // let the peer know if there are unread messages in the list
            if(hasUnread)
            {
                outAppParams.setNewMessage(1);
            }else{
                outAppParams.setNewMessage(0);
            }

            outAppParams.setMseTime(Calendar.getInstance().getTime().getTime());
            replyHeaders.setHeader(HeaderSet.APPLICATION_PARAMETER, outAppParams.EncodeParams());
            op.sendHeaders(replyHeaders);

        } catch (IOException e) {
            Log.w(TAG,"sendMessageListingRsp: IOException - sending OBEX_HTTP_BAD_REQUEST", e);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        } catch (IllegalArgumentException e) {
            Log.w(TAG,"sendMessageListingRsp: IllegalArgumentException - sending OBEX_HTTP_BAD_REQUEST", e);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        maxChunkSize = op.getMaxPacketSize(); // This must be called after setting the headers.
        if(outBytes != null) {
            try {
                while (bytesWritten < outBytes.length && sIsAborted == false) {
                    bytesToWrite = Math.min(maxChunkSize, outBytes.length - bytesWritten);
                    outStream.write(outBytes, bytesWritten, bytesToWrite);
                    bytesWritten += bytesToWrite;
                }
            } catch (IOException e) {
                if(V) Log.w(TAG,e);
                // We were probably aborted or disconnected
            } finally {
                if(outStream != null) {
                    try {
                        outStream.close();
                    } catch (IOException e) {
                        // If an error occurs during close, there is no more cleanup to do
                    }
                }
            }
            if(bytesWritten != outBytes.length)
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        } else {
            try {
                outStream.close();
            } catch (IOException e) {
                // If an error occurs during close, there is no more cleanup to do
            }
        }
        return ResponseCodes.OBEX_HTTP_OK;
    }

    /**
     * Generate and send the Folder listing response based on an application
     * parameter header. This function call will block until complete or aborted
     * by the peer. Fragmentation of packets larger than the obex packet size
     * will be handled by this function.
     *
     * @param op
     *            The OBEX operation.
     * @param appParams
     *            The application parameter header
     * @return {@link ResponseCodes.OBEX_HTTP_OK} on success or
     *         {@link ResponseCodes.OBEX_HTTP_BAD_REQUEST} on error.
     */
    private int sendFolderListingRsp(Operation op, BluetoothMapAppParams appParams){
        OutputStream outStream = null;
        byte[] outBytes = null;
        List<String> list;
        ArrayList<String> finalList = new ArrayList<String>();
        int type;
        BluetoothMapAppParams outAppParams = new BluetoothMapAppParams();
        int maxChunkSize, bytesWritten = 0;
        HeaderSet replyHeaders = new HeaderSet();
        int curType;
        int bytesToWrite, maxListCount, listStartOffset;
        if(appParams == null){
            appParams = new BluetoothMapAppParams();
            appParams.setMaxListCount(1024);
        }

        if(V)
            Log.v(TAG,"sendFolderList for " + mCurrentFolder.getName());

        try {
            maxListCount = appParams.getMaxListCount();
            listStartOffset = appParams.getStartOffset();

            if(listStartOffset == BluetoothMapAppParams.INVALID_VALUE_PARAMETER)
                listStartOffset = 0;

            if(maxListCount == BluetoothMapAppParams.INVALID_VALUE_PARAMETER)
                maxListCount = 1024;
                Log.v(TAG,"mMasId is " + mMasId);
                if(mMasId == 1) {
                   long id = BluetoothMapUtils.getEmailAccountId(mContext);
                   list = mOutContent.getEmailFolderListAtPath(mContext,id,mCurrentFolder.getName());
                   if(mCurrentFolder.getName().equals("telecom") || mCurrentFolder.getName().equals("msg")) {
                      if(V) Log.v(TAG, "Doing no processing");
                      for (String str : list) {
                        finalList.add(str);
                      }
                   } else {
                      if (V) Log.v(TAG, "processing for special folders");
                      for (String str : list) {
                           String folderStr = str.substring(mCurrentFolder.getName().length()+ 1);
                           if(V) Log.v(TAG, "folderStr is " +folderStr);
                           String folder[] = folderStr.split("/");
                           if(folder.length == 1){
                              if (V) Log.v(TAG, " Add Folder:" + folder[0]);
                              finalList.add(folder[0]);
                           }
                      }
                   }
                    for (int i = 0; i < mCurrentFolder.subFolders.size(); i ++) {
                         if(finalList.contains(mCurrentFolder.subFolders.get(i).getName())) {
                            if (V) Log.v(TAG, " listing already contains, hence removing folder : "
                                                            + mCurrentFolder.subFolders.get(i).getName());
                            finalList.remove(mCurrentFolder.subFolders.get(i).getName());
                         }
                    }
                    if (V) Log.v(TAG, "final list");
                    for (String str : finalList) {
                         if (V) Log.v(TAG, "" + str);
                         mCurrentFolder.addFolder(str);
                    }
                }

            if(maxListCount != 0) {
                outBytes = mCurrentFolder.encode(listStartOffset, maxListCount);
                outStream = op.openOutputStream();
            } else {
                op.noBodyHeader();
                op.noEndofBody();
            }

            // Build and set the application parameter header
            outAppParams.setFolderListingSize(mCurrentFolder.getSubFolderCount());
            replyHeaders.setHeader(HeaderSet.APPLICATION_PARAMETER, outAppParams.EncodeParams());
            op.sendHeaders(replyHeaders);

        } catch (IOException e1) {
            Log.w(TAG,"sendFolderListingRsp: IOException - sending OBEX_HTTP_BAD_REQUEST Exception:", e1);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        } catch (IllegalArgumentException e1) {
            Log.w(TAG,"sendFolderListingRsp: IllegalArgumentException - sending OBEX_HTTP_BAD_REQUEST Exception:", e1);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        maxChunkSize = op.getMaxPacketSize(); // This must be called after setting the headers.

        if(outBytes != null) {
            try {
                while (bytesWritten < outBytes.length && sIsAborted == false) {
                    bytesToWrite = Math.min(maxChunkSize, outBytes.length - bytesWritten);
                    outStream.write(outBytes, bytesWritten, bytesToWrite);
                    bytesWritten += bytesToWrite;
                }
            } catch (IOException e) {
                // We were probably aborted or disconnected
            } finally {
                if(outStream != null) {
                    try {
                        outStream.close();
                    } catch (IOException e) {
                        // If an error occurs during close, there is no more cleanup to do
                    }
                }
            }
            if(V)
                Log.v(TAG,"sendFolderList sent " + bytesWritten + " bytes out of "+ outBytes.length);
            if(bytesWritten == outBytes.length)
                return ResponseCodes.OBEX_HTTP_OK;
            else
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        return ResponseCodes.OBEX_HTTP_OK;
    }

    /**
     * Generate and send the get message response based on an application
     * parameter header and a handle.
     *
     * @param op
     *            The OBEX operation.
     * @param appParams
     *            The application parameter header
     * @param handle
     *            The handle of the requested message
     * @return {@link ResponseCodes.OBEX_HTTP_OK} on success or
     *         {@link ResponseCodes.OBEX_HTTP_BAD_REQUEST} on error.
     */
    private int sendGetMessageRsp(Operation op, String handle, BluetoothMapAppParams appParams){
        OutputStream outStream ;
        byte[] outBytes;
        int maxChunkSize, bytesToWrite, bytesWritten = 0;
        long msgHandle;
        if (V) Log.v(TAG, "sendGetMessageRsp for handle " + handle);

        try {
            outBytes = mOutContent.getMessage(handle, appParams);
            outStream = op.openOutputStream();

        } catch (IOException e) {
            Log.w(TAG,"sendGetMessageRsp: IOException - sending OBEX_HTTP_BAD_REQUEST", e);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        } catch (IllegalArgumentException e) {
            Log.w(TAG,"sendGetMessageRsp: IllegalArgumentException (e.g. invalid handle) - sending OBEX_HTTP_BAD_REQUEST", e);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        maxChunkSize = op.getMaxPacketSize(); // This must be called after setting the headers.

        if(outBytes != null) {
            try {
                while (bytesWritten < outBytes.length && sIsAborted == false) {
                    bytesToWrite = Math.min(maxChunkSize, outBytes.length - bytesWritten);
                    outStream.write(outBytes, bytesWritten, bytesToWrite);
                    bytesWritten += bytesToWrite;
                }
            } catch (IOException e) {
                // We were probably aborted or disconnected
            } finally {
                if(outStream != null) {
                    try {
                        outStream.close();
                    } catch (IOException e) {
                        // If an error occurs during close, there is no more cleanup to do
                    }
                }
            }
            if(bytesWritten == outBytes.length)
                return ResponseCodes.OBEX_HTTP_OK;
            else
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        return ResponseCodes.OBEX_HTTP_OK;
    }


    private static final void logHeader(HeaderSet hs) {
        Log.v(TAG, "Dumping HeaderSet " + hs.toString());
        try {
            Log.v(TAG, "CONNECTION_ID : " + hs.getHeader(HeaderSet.CONNECTION_ID));
            Log.v(TAG, "NAME : " + hs.getHeader(HeaderSet.NAME));
            Log.v(TAG, "TYPE : " + hs.getHeader(HeaderSet.TYPE));
            Log.v(TAG, "TARGET : " + hs.getHeader(HeaderSet.TARGET));
            Log.v(TAG, "WHO : " + hs.getHeader(HeaderSet.WHO));
            Log.v(TAG, "APPLICATION_PARAMETER : " + hs.getHeader(HeaderSet.APPLICATION_PARAMETER));
        } catch (IOException e) {
            Log.e(TAG, "dump HeaderSet error " + e);
        }
        Log.v(TAG, "NEW!!! Dumping HeaderSet END");
    }

    private void acquireMapLock() {
        if (V) Log.v(TAG, "About to acquire Map:mWakeLock");
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MapPartialWakeLock");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
            if (V) Log.v(TAG, "Map:mWakeLock acquired");
        }
        else
        {
            Log.e(TAG, "Map:mWakeLock already acquired");
        }
    }

    private void releaseMapLock() {
        if (V) Log.v(TAG, "About to release Map:mWakeLock");
        if (mWakeLock != null) {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
                if (V) Log.v(TAG, "Map:mWakeLock released");
            } else {
                if (V) Log.v(TAG, "Map:mWakeLock already released");
            }
            mWakeLock = null;
        }
    }
}
