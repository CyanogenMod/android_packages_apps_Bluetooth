/*
 * Copyright (c) 2011-2012, Code Aurora Forum. All rights reserved.
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

import android.app.ActivityManager;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.text.format.Time;
import android.util.Log;
import android.util.TimeFormatException;

import com.android.bluetooth.map.MapUtils.BmessageConsts;
import com.android.bluetooth.map.MapUtils.CommonUtils;
import com.android.bluetooth.map.MapUtils.MapUtils;
import com.android.bluetooth.map.MapUtils.MsgListingConsts;
import com.android.bluetooth.map.MapUtils.SmsMmsUtils;
import com.android.bluetooth.map.MapUtils.SortMsgListByDate;
import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMasMessageListingRsp;
import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMasMessageRsp;
import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMasPushMsgRsp;
import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMsgListRsp;
import com.android.bluetooth.map.MapUtils.MapUtils.BadRequestException;
import com.android.bluetooth.map.MapUtils.SmsMmsUtils.VcardContent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;

import javax.obex.ResponseCodes;

import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.DELETED;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.DRAFT;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.DRAFTS;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.INBOX;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.OUTBOX;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.QUEUED;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.SENT;

public class BluetoothMasAppSmsMms extends BluetoothMasAppIf {
    public final String TAG = "BluetoothMasAppSmsMms";

    private final long SMS_OFFSET_START;
    private final long MMS_OFFSET_START;

    private static final String SMS_GSM = "SMS_GSM";
    private static final String SMS_CDMA = "SMS_CDMA";
    private static final String MMS = "MMS";
    // OMA-TS-MMS-ENC defined many types in X-Mms-Message-Type.
    // Only m-send-req (128) m-retrieve-conf (132), m-notification-ind (130)
    // are interested by user
    private static final String INTERESTED_MESSAGE_TYPE_CLAUSE =
                "(m_type = 128 OR m_type = 132 OR m_type = 130)";

    public BluetoothMasAppSmsMms(Context context, Handler handler, BluetoothMns mnsClient,
            int masId, String remoteDeviceName) {
        super(context, handler, MESSAGE_TYPE_SMS_MMS, mnsClient, masId, remoteDeviceName);
        SMS_OFFSET_START = OFFSET_START;
        MMS_OFFSET_START = OFFSET_START + ((OFFSET_END - OFFSET_START) / 2);

        // Clear out deleted items from database
        cleanUp();

        if (V) Log.v(TAG, "BluetoothMasAppSmsMms Constructor called");
    }

    /**
     * Start an MNS obex client session and push notification whenever available
     */
    public void startMnsSession(BluetoothDevice remoteDevice) {
        if (V) Log.v(TAG, "Start MNS Client");
        mMnsClient.getHandler()
                .obtainMessage(BluetoothMns.MNS_CONNECT, 0, -1, remoteDevice)
                .sendToTarget();
    }

    /**
     * Stop pushing notifications and disconnect MNS obex session
     */
    public void stopMnsSession(BluetoothDevice remoteDevice) {
        if (V) Log.v(TAG, "Stop MNS Client");
        mMnsClient.getHandler()
                .obtainMessage(BluetoothMns.MNS_DISCONNECT, 0, -1,
                remoteDevice).sendToTarget();
    }

    @Override
    protected List<String> getCompleteFolderList() {
        return SmsMmsUtils.FORLDER_LIST_SMS_MMS;
    }

    private void cleanUp() {
        // Remove the deleted item entries
        mContext.getContentResolver().delete(Uri.parse("content://sms/"),
                "thread_id = " + DELETED_THREAD_ID, null);
        mContext.getContentResolver().delete(Uri.parse("content://mms/"),
                "thread_id = " + DELETED_THREAD_ID, null);
    }

    public boolean checkPrecondition() {
        // TODO: Add any precondition check routine for this MAS instance
        return true;
    }

    public void onConnect() {
        // TODO: Add any routine to be run when OBEX connection established
    }

    public void onDisconnect() {
        cleanUp();
    }

    @Override
    protected BluetoothMsgListRsp msgListingSpecific(List<MsgListingConsts> msgList, String name,
            BluetoothMasMessageListingRsp rsp, BluetoothMasAppParams appParams) {
        BluetoothMsgListRsp bmlr = new BluetoothMsgListRsp();
        boolean validFilter = false;
        String fullPath = (name == null || name.length() == 0) ? mCurrentPath :
                CommonUtils.getFullPath(name, mContext, getCompleteFolderList(), mCurrentPath);
        if (fullPath == null) {
            // Child folder not present
            rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            bmlr.rsp = rsp;
            return bmlr;
        }

        if (V) {
            Log.v(TAG, "appParams.FilterMessageType ::"+ appParams.FilterMessageType);
            Log.v(TAG, "Condition result::"+ (appParams.FilterMessageType & 0x0B));
        }
        String splitStrings[] = fullPath.split("/");
        if (splitStrings.length == 3) {
            String folderName = splitStrings[2];
            if (V) Log.v(TAG, "folderName: " + folderName);
            if (CommonUtils.validateFilterPeriods(appParams) == 0) {
                rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
                bmlr.rsp = rsp;
                return bmlr;
            }
            if (appParams.FilterReadStatus > 0x02) {
                rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
                bmlr.rsp = rsp;
                return bmlr;
            }

            // TODO: Filter priority?
            /*
             * There is no support for FilterPriority in SMS/MMS. So, we will
             * assume Filter Priority is always non-high which makes sense for
             * SMS/MMS.We check the content provider only if the Filter Priority
             * is "unfiltered" or "non-high". Else, simply return an empty
             * string. If the Filter priority is greater than 2, return a bad
             * request.
             */

            if (appParams.FilterPriority == 0 || appParams.FilterPriority == 0x02) {
                final int phoneType = TelephonyManager.getDefault().getPhoneType();
                if ((appParams.FilterMessageType & 0x03) == 0 ||
                        ((appParams.FilterMessageType & 0x01) == 0 &&
                                phoneType == TelephonyManager.PHONE_TYPE_GSM) ||
                        ((appParams.FilterMessageType & 0x02) == 0 &&
                                phoneType == TelephonyManager.PHONE_TYPE_CDMA)) {
                    validFilter = true;
                    BluetoothMsgListRsp bmlrSms = msgListSms(msgList, folderName,
                            rsp, appParams);
                    bmlr.msgList = bmlrSms.msgList;
                    bmlr.rsp = bmlrSms.rsp;
                }
                // Now that all of the SMS messages have been listed. Look for
                // any
                // MMS messages and provide them
                if((appParams.FilterMessageType & 0x08) == 0) {
                    Log.v(TAG, "About to retrieve msgListMms ");
                    // MMS draft folder is called //mms/drafts not //mms/draft like
                    // SMS
                    validFilter = true;
                    if (DRAFT.equalsIgnoreCase(folderName)) {
                        folderName = DRAFTS;
                    }
                    BluetoothMsgListRsp bmlrMms = msgListMms(msgList, folderName, rsp, appParams);
                    bmlr.msgList = bmlrMms.msgList;
                    bmlr.rsp = bmlrMms.rsp;
                }
                if (validFilter != true) {
                    if (V) Log.v(TAG, "Invalid message filter, returning empty-list");
                    rsp.rsp = ResponseCodes.OBEX_HTTP_OK;
                    bmlr.rsp = rsp;
                    return bmlr;
                }
            } else {
                if (appParams.FilterPriority > 0x02) {
                    rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
                    bmlr.rsp = rsp;
                    return bmlr;
                }
            }
        }

        // Now that the message list exists, we can sort the list by date
        Collections.sort(bmlr.msgList, new SortMsgListByDate());
        rsp.rsp = ResponseCodes.OBEX_HTTP_OK;
        bmlr.rsp = rsp;
        return bmlr;
    }

    @Override
    protected BluetoothMasMessageRsp getMessageSpecific(long msgHandle, BluetoothMasMessageRsp rsp,
            BluetoothMasAppParams bluetoothMasAppParams) {
        final long handle = Long.valueOf(msgHandle);

        if (handle >= MMS_OFFSET_START) { // MMS
            /*
             * Spec 5.6.4 says MSE shall reject request with value native
             * for MMS and Email
             */
            if ((int)bluetoothMasAppParams.Charset == 0) {
                rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
                return rsp;
            }
            return getMessageMms(msgHandle, rsp);
        } else { // SMS
            return getMessageSms(msgHandle, mContext, rsp, bluetoothMasAppParams);
        }
    }

    /**
     * Push a outgoing message from MAS Client to the network
     *
     * @return Response to push command
     */
    public BluetoothMasPushMsgRsp pushMsg(String name, File file,
            BluetoothMasAppParams bluetoothMasAppParams) throws BadRequestException {
        BluetoothMasPushMsgRsp rsp = new BluetoothMasPushMsgRsp();
        rsp.response = ResponseCodes.OBEX_HTTP_UNAVAILABLE;
        rsp.msgHandle = null;

        if(!checkPath(false, name, false) ||
                mCurrentPath == null ||
                mCurrentPath.equals("telecom") ||
                (mCurrentPath.equals("telecom/msg") && (name == null || name.length() == 0))) {
            rsp.response = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            return rsp;
        }

        ActivityManager am = (ActivityManager)mContext.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo outInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(outInfo);
        final long allowedMem = outInfo.availMem - outInfo.threshold;

        byte[] readBytes = null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            if(file.length() > allowedMem){
                rsp.response = ResponseCodes.OBEX_HTTP_ENTITY_TOO_LARGE;
                rsp.msgHandle = null;
                Log.d(TAG,"Message body is larger than the max length allowed");
                return rsp;
            } else {
                readBytes = new byte[(int) file.length()];
                fis.read(readBytes);
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, e.getMessage());
            return rsp;
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
            return rsp;
        } catch (SecurityException e) {
            Log.e(TAG, e.getMessage());
            return rsp;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error while closing stream"+ e.toString());
                }
            }
        }

        String readStr = "";
        String type = "";
        try {
            readStr = new String(readBytes);
            type = MapUtils.fetchType(readStr);
        } catch (Exception e) {
            throw new BadRequestException(e.getMessage());
        }
        if (type == null) {
            rsp.response = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            return rsp;
        }
        if (SMS_GSM.equalsIgnoreCase(type) || SMS_CDMA.equalsIgnoreCase(type)) {
            return pushMessageSms(rsp, readStr, name, bluetoothMasAppParams);
        } else if (MMS.equals(type)) {
            // If the message to be pushed is an MMS message, extract any text,
            // discard
            // any attachments and convert the message to an SMS
            if (type.equalsIgnoreCase("MMS")) {
                /*
                 * The pair of calls below is used to send the MMS message out to
                 * the network.You need to first move the message to the drafts
                 * folder and then move the message from drafts to the outbox
                 * folder. This action causes the message to also be added to the
                 * pending_msgs table in the database. The transaction service will
                 * then send the message out to the network the next time it is
                 * scheduled to run
                 */
                rsp = pushMessageMms(rsp, readStr, name);
                return rsp;
            }
        }
        rsp.response = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        return rsp;
    }

    /**
     * Sets the message status (read/unread, delete)
     *
     * @return Obex response code
     */
    public int msgStatus(String msgHandle, BluetoothMasAppParams bluetoothMasAppParams) {
        if ((bluetoothMasAppParams.StatusIndicator != 0)
                && (bluetoothMasAppParams.StatusIndicator != 1)) {
            return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
        }
        if ((bluetoothMasAppParams.StatusValue != 0)
                && (bluetoothMasAppParams.StatusValue != 1)) {
            return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
        }
        final long handle = Long.valueOf(msgHandle);
        if (handle < OFFSET_START && handle > OFFSET_END) {
            return ResponseCodes.OBEX_HTTP_NOT_FOUND;
        }
        if (handle >= MMS_OFFSET_START) { // MMS
            return setMsgStatusMms(handle, bluetoothMasAppParams);
        } else { // SMS
            return setMsgStatusSms(handle, bluetoothMasAppParams);
        }
    }

    /**
     * Sets the message update
     *
     * @return Obex response code
     */
    public int msgUpdate() {
        if (V) Log.v(TAG, "Message Update");
        // UpdateInbox for MMS/SMS is not supported
        return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
    }

    private boolean isOutgoingSMSMessage(int type) {
        if (type == 1) {
            return false;
        }
        return true;
    }

    /**
     * Get the folder name (MAP representation) based on the folder type value
     * in SMS database
     */
    private String getMAPFolder(String type, String threadId) {
        String folder = null;
        if (type == null || threadId == null){
            if (V) Log.v(TAG, "getMapFolder cannot parse folder type");
            return null;
        }

        if (Integer.valueOf(threadId) == DELETED_THREAD_ID) {
            folder = DELETED;
        } else {
            switch (Integer.valueOf(type)) {
            case 1:
                folder = INBOX;
                break;
            case 2:
                folder = SENT;
                break;
            case 3:
                folder = DRAFT;
                break;
            case 4:
            case 5:
            case 6:
                folder = OUTBOX;
                break;
            default:
                break;
            }
        }
        return folder;
    }

    /**
     * Get the folder name (MAP representation) based on the message Handle
     */
    private String getContainingFolder(long msgHandle) {
        String folder = null;
        Cursor cr = mContext.getContentResolver().query(
                Uri.parse("content://sms/" + msgHandle),
                new String[] { "_id", "type", "thread_id"}, null, null, null);
        if (cr != null) {
            if (cr.getCount() > 0) {
                cr.moveToFirst();
                folder = getMAPFolder(cr.getString(cr.getColumnIndex("type")),
                        cr.getString(cr.getColumnIndex("thread_id")));
            }
            cr.close();
        }
        return folder;
    }

    /**
     * Get the SMS Deliver PDU for the given SMS
     */
    private String getSMSDeliverPdu(String smsBody, String dateTime, String address) {
        Time time = new Time();
        time.set(Long.valueOf(dateTime));

        String timeStr = time.format3339(false);

        // Extract the YY, MM, DD, HH, MM, SS from time
        String tempTimeStr = timeStr.substring(2,4) + timeStr.substring(5, 7)
                + timeStr.substring(8, 10) + timeStr.substring(11, 13) +
                timeStr.substring(14, 16) + timeStr.substring(17, 19);

        /* Calculate the time zone offset
         * An offset of 1 indicates 15 min difference between local
         * time and GMT. MSB of 1 in offset indicates it is negative
         */
        String tZoneStr = timeStr.substring(timeStr.length()- 6);
        int tempInt = Integer.valueOf(tZoneStr.substring(tZoneStr.length()-2));
        int tZone15offset = tempInt / 15;

        tZone15offset += (Integer.valueOf(tZoneStr.substring(tZoneStr.length()-5,
                tZoneStr.length()-3)) * 4);
        if (timeStr.charAt(timeStr.length()-6) == '-'){
            tZone15offset = tZone15offset | 0x80;
        }

        String tZone15OffsetHexStr = "";

        // Add 0 as prefix for single digit offset
        if(((int) tZone15offset & 0xff) < 0x10){
            tZone15OffsetHexStr += "0";
        }
        tZone15OffsetHexStr += Integer.toHexString(tZone15offset);

        tempTimeStr += tZone15OffsetHexStr;

        // Swap the nibble
        String encodedTimeStr = "";
        for (int i=0; i<tempTimeStr.length(); i=i+2){
            encodedTimeStr += tempTimeStr.substring(i+1, i+2);
            encodedTimeStr += tempTimeStr.substring(i, i+1);
        }

        byte[] byteAddress = address.getBytes();

        // Let the service center number be 0000000000
        String smsPdu = "0681000000000004";

        // Extract only digits out of the phone address
        StringBuffer strbufAddress = new StringBuffer(address.length() + 1);
        for (int i=0; i<address.length(); i++){
            if (V){
                Log.v(TAG, " VAL " + address.substring(i, i+1));
            }
            if (byteAddress[i] >= 48 && byteAddress[i] <= 57){
                strbufAddress.append(Integer.parseInt(address.substring(i, i+1)));
            }
        }

        int addressLength = strbufAddress.length();

        String addressLengthStr = "";

        if(((int) addressLength & 0xff) < 0x10)
            addressLengthStr += "0";
        addressLengthStr += Integer.toHexString(addressLength);

        smsPdu = smsPdu + addressLengthStr;
        smsPdu = smsPdu + "81";

        String strAddress = new String(strbufAddress);

        // Use getSubmitPdu only to obtain the encoded msg and encoded address
        byte[] msg = SmsMessage.getSubmitPdu(null, strAddress, smsBody, false).encodedMessage;

        int addLength = Integer.valueOf(msg[2]);
        if (addLength %2 != 0){
            addLength++;
        }
        addLength = addLength / 2;

        // Extract the message from the SubmitPdu
        int msgOffset = 7 + addLength;
        int msgLength = msg.length - msgOffset;

        StringBuffer strbufMessage = new StringBuffer(msgLength * 2);

        // Convert from byte to Hex String
        for (int i=msgOffset; i<msgLength + msgOffset; i++) {
            if (((int) msg[i] & 0xff) < 0x10) {
                strbufMessage.append("0");
            }
            strbufMessage.append((Long.toString((int) msg[i] & 0xff, 16)));
        }

        int encodedAddressLength = strAddress.length() / 2;
        if (strAddress.length() % 2 != 0) {
            encodedAddressLength++;
        }

        StringBuffer strbufAddress1 = new StringBuffer(msgLength * 2);

        // Convert from byte to Hex String
        for(int i=4; i<encodedAddressLength + 4; i++)
        {
            if(((int) msg[i] & 0xff) < 0x10)
                strbufAddress1.append("0");
            strbufAddress1.append((Long.toString((int) msg[i] & 0xff, 16)));
        }

        smsPdu += strbufAddress1;
        smsPdu += "0000";
        smsPdu += encodedTimeStr;

        int smsBodyLength = smsBody.length();
        String smsMessageTextLengthStr = "";

        if(((int) smsBodyLength & 0xff) < 0x10){
            smsMessageTextLengthStr += "0";
        }
        smsMessageTextLengthStr += Integer.toHexString(smsBodyLength);

        smsPdu += smsMessageTextLengthStr;
        smsPdu += strbufMessage;
        smsPdu = smsPdu.toUpperCase();
        return smsPdu;
    }

    /**
     * Adds a SMS to the Sms ContentProvider
     */
    private String addToSmsFolder(String folder, String address, String text) {
        ContentValues values = new ContentValues();
        // thread_id is handled by SmsProvider
        values.put("body", text);
        values.put("address", address);
        values.put("read", 0);
        values.put("seen", 0);
        /*
         * status none -1 complete 0 pending 64 failed 128
         */
        values.put("status", -1);
        /*
         * outbox 4 queued 6
         */
        values.put("locked", 0);
        values.put("error_code", 0);
        Uri uri = mContext.getContentResolver().insert(
                Uri.parse("content://sms/" + folder), values);
        if (V){
            Log.v(TAG, " NEW URI " + ((uri == null) ? "null" : uri.toString()));
        }

        if (uri == null) {
            return INTERNAL_ERROR;
        }
        String str = uri.toString();
        String[] splitStr = str.split("/");
        if (splitStr.length < 4) {
            return INTERNAL_ERROR;
        }
        if (V){
            Log.v(TAG, " NEW HANDLE " + splitStr[3]);
        }
        return splitStr[3];
    }

    private void updateMMSThreadId(long handle, int threadId) {
        ContentValues values = new ContentValues();
        values.put("thread_id", threadId);
        mContext.getContentResolver().update(Uri.parse("content://mms/" + handle),
                values, null, null);
    }

    private void deleteMMS(long handle) {
        Cursor cr = mContext.getContentResolver().query(Uri.parse("content://mms/" + handle),
                null, null, null, null);
        if (cr != null && cr.moveToFirst()){
            int threadId = cr.getInt(cr.getColumnIndex(("thread_id")));
            if (threadId != DELETED_THREAD_ID){
                // Move to deleted folder
                updateMMSThreadId(handle, Integer.valueOf(DELETED_THREAD_ID));
            } else {
                // Delete the message permanently
                long msgId = handle + MMS_OFFSET_START;
                mMnsClient.addMceInitiatedOperation(Long.toString(msgId));
                mContext.getContentResolver().delete(Uri.parse("content://mms/" + handle),
                        null, null);
            }
        }
        if (cr != null) {
            cr.close();
        }
    }

    private void unDeleteMMS(long msgHandle) {
        Cursor cr = mContext.getContentResolver().query(Uri.parse("content://mms/" + msgHandle),
                null, null, null, null );
        if (cr == null) {
            if (V){
                Log.v(TAG, "unable to query content://mms/" + msgHandle);
            }
            return;
        }
        if (cr.moveToFirst()){
            // Make sure that the message is in delete folder
            String currentThreadId = cr.getString(cr.getColumnIndex("thread_id"));
            if (currentThreadId != null && Integer.valueOf(currentThreadId) != -1){
                if (V){
                    Log.v(TAG, " Not in delete folder");
                }
                return;
            }

            // Fetch the address of the deleted message
            String address = getMmsMsgAddress(msgHandle);

            // Search the database for the given message ID
            Cursor crThreadId = mContext.getContentResolver().query(Uri.parse("content://mms/"),
                    null,"_id = " + msgHandle + " AND thread_id != -1", null, null);
            if (crThreadId != null && crThreadId.moveToFirst()) {
                // A thread for the given message ID exists in the database
                String threadIdStr = crThreadId.getString(crThreadId.getColumnIndex("thread_id"));
                if (V){
                    Log.v(TAG, " THREAD ID " + threadIdStr);
                }
                updateMMSThreadId(msgHandle, Integer.valueOf(threadIdStr));
            } else {
                /* No thread for the given address
                 * Create a fake message to obtain the thread, use that thread_id
                 * and then delete the fake message
                 */
                ContentValues tempValue = new ContentValues();
                tempValue.put("address", address);
                tempValue.put("type", "20");
                Uri tempUri = mContext.getContentResolver().insert( Uri.parse("content://sms/"),
                        tempValue);

                if (tempUri != null) {
                    Cursor tempCr = mContext.getContentResolver().query(tempUri, null, null, null,
                            null);
                    if (tempCr != null) {
                        tempCr.moveToFirst();
                        String newThreadIdStr = tempCr.getString(
                                tempCr.getColumnIndex("thread_id"));
                        tempCr.close();
                        updateMMSThreadId(msgHandle, Integer.valueOf(newThreadIdStr));
                    }
                    mContext.getContentResolver().delete(tempUri, null, null);
                } else {
                    Log.e(TAG, "Error in undelete");
                }
            }
            if (crThreadId != null) {
                crThreadId.close();
            }
        } else {
            if (V) {
                Log.v(TAG, "msgHandle not found");
            }
        }
        cr.close();
    }

    private void updateSMSThreadId(long msgHandle, int threadId) {
        ContentValues values = new ContentValues();
        values.put("thread_id", threadId);
        mContext.getContentResolver().update(Uri.parse("content://sms/" + msgHandle),
                values, null, null);
    }

    private void deleteSMS(long handle) {
        Cursor cr = mContext.getContentResolver().query(Uri.parse("content://sms/" + handle),
                null, null, null, null);
        if (cr != null && cr.moveToFirst()){
            int threadId = cr.getInt(cr.getColumnIndex(("thread_id")));
            if (threadId != DELETED_THREAD_ID){
                // Move to deleted folder
                updateSMSThreadId(handle, Integer.valueOf(DELETED_THREAD_ID));
            } else {
                // Delete the message permanently
                long msgHandle = handle + SMS_OFFSET_START;
                mMnsClient.addMceInitiatedOperation(Long.toString(msgHandle));
                mContext.getContentResolver().delete(Uri.parse("content://sms/" + handle),
                        null, null);
            }
        }
        if (cr != null) {
            cr.close();
        }
    }

    private void unDeleteSMS(long msgHandle){
        Cursor cr = mContext.getContentResolver().query(Uri.parse("content://sms/" + msgHandle),
                null, null, null, null );
        if (cr == null) {
            return;
        }

        if (cr.moveToFirst()){
            // Make sure that the message is in delete folder
            String currentThreadId = cr.getString(cr.getColumnIndex("thread_id"));
            if (currentThreadId != null && Integer.valueOf(currentThreadId) != -1){
                if (V){
                    Log.v(TAG, " Not in delete folder");
                }
                return;
            }

            // Fetch the address of the deleted message
            String address = cr.getString(cr.getColumnIndex("address"));

            // Search the database for the given address
            Cursor crThreadId = mContext.getContentResolver().query(Uri.parse("content://sms/"),
                    null, "address = " + address + " AND thread_id != -1", null, null);
            if (crThreadId != null && crThreadId.moveToFirst()) {
                // A thread for the given address exists in the database
                String threadIdStr = crThreadId.getString(crThreadId.getColumnIndex("thread_id"));
                if (V){
                    Log.v(TAG, " THREAD ID " + threadIdStr);
                }
                updateSMSThreadId(msgHandle, Integer.valueOf(threadIdStr));
            } else {
                /* No thread for the given address
                 * Create a fake message to obtain the thread, use that thread_id
                 * and then delete the fake message
                 */
                ContentValues tempValue = new ContentValues();
                tempValue.put("address", address);
                tempValue.put("type", "20");
                Uri tempUri = mContext.getContentResolver().insert( Uri.parse("content://sms/"),
                        tempValue);

                if (tempUri != null) {
                    Cursor tempCr = mContext.getContentResolver().query(tempUri, null, null, null,
                            null);
                    if (tempCr != null) {
                        tempCr.moveToFirst();
                        String newThreadIdStr = tempCr.getString(
                                tempCr.getColumnIndex("thread_id"));
                        tempCr.close();
                        updateSMSThreadId(msgHandle, Integer.valueOf(newThreadIdStr));
                    }

                    mContext.getContentResolver().delete(tempUri, null, null);
                }
            }
            if (crThreadId != null) {
                crThreadId.close();
            }
        } else {
            if (V) {
                Log.v(TAG, "msgHandle not found");
            }
        }
        cr.close();
    }

    /**
     * Obtain the number of MMS messages
     */
    private int getNumMmsMsgs(String name) {
        int msgCount = 0;

        if ( name.equalsIgnoreCase(DELETED)){
            Uri uri = Uri.parse("content://mms/");
            ContentResolver cr = mContext.getContentResolver();
            Cursor cursor = cr.query(uri, null, "thread_id = " + DELETED_THREAD_ID
                    + " AND " + INTERESTED_MESSAGE_TYPE_CLAUSE, null, null);
            if(cursor != null){
                msgCount = cursor.getCount();
                cursor.close();
            }
        } else {
            Uri uri = Uri.parse("content://mms/" + name);
            ContentResolver cr = mContext.getContentResolver();
            Cursor cursor = cr.query(uri, null, "thread_id <> " + DELETED_THREAD_ID
                    + " AND " + INTERESTED_MESSAGE_TYPE_CLAUSE, null, null);
            if(cursor != null){
                msgCount = cursor.getCount();
                cursor.close();
            }
        }
        return msgCount;
    }

    /**
     * Obtain the MMS message ID from Handle
     */
    private Long getMmsMsgHndToID(long msgHandle) {
        long msgID = -1;
        String whereClause = " mid= " + (msgHandle - MMS_OFFSET_START);
        Uri uri = Uri.parse("content://mms/part");
        ContentResolver cr = mContext.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                int handleInd = cursor.getColumnIndex("mid");
                msgID = cursor.getLong(handleInd);
            }
            cursor.close();
        }
        return msgID;
    }

    /**
     * Obtain the MMS message MID list
     */
    private List<Integer> getMmsMsgMIDs(String whereClause) {
        List<Integer> idList = new ArrayList<Integer>();
        Uri uri = Uri.parse("content://mms");
        ContentResolver cr = mContext.getContentResolver();
        Cursor crID = cr.query(uri, null, whereClause, null, null);
        if (crID != null) {
            int idInd = crID.getColumnIndex("_id");
            if (crID.getCount() != 0) {
                crID.moveToFirst();
                do {
                    idList.add(Integer.valueOf(crID.getInt(idInd)));
                } while (crID.moveToNext());
            }
            crID.close();
        }
        return idList;
    }

    /**
     * Build a whereclause for MMS filtering
     */
    private String bldMmsWhereClause(BluetoothMasAppParams appParams, int foldertype) {
        String whereClause = "";
        if ( foldertype != -1) {
            // Inbox, Outbox, Sent, Draft folders
            whereClause = "msg_box=" + foldertype + " AND thread_id <> " + DELETED_THREAD_ID
                    + " AND " + INTERESTED_MESSAGE_TYPE_CLAUSE;
        } else {
            // Deleted folder
            whereClause =  "thread_id = " + DELETED_THREAD_ID + " AND "
                    + INTERESTED_MESSAGE_TYPE_CLAUSE;
        }

        /* Filter readstatus: 0 no filtering, 0x01 get unread, 0x10 get read */
        if (appParams.FilterReadStatus != 0) {
            if ((appParams.FilterReadStatus & 0x1) != 0) {
                if (whereClause.length() != 0) {
                    whereClause += " AND ";
                }
                whereClause += " read=0 ";
            }
            if ((appParams.FilterReadStatus & 0x02) != 0) {
                if (whereClause.length() != 0) {
                    whereClause += " AND ";
                }
                whereClause += " read=1 ";
            }
        }

        /* Filter Period Begin */
        if ((appParams.FilterPeriodBegin != null)
                && (appParams.FilterPeriodBegin.length() > 0)) {
            Time time = new Time();
            try {
                time.parse(appParams.FilterPeriodBegin.trim());
                if (whereClause.length() != 0) {
                    whereClause += " AND ";
                }
                whereClause += "date >= " + (time.toMillis(false))/1000;
            } catch (TimeFormatException e) {
                Log.d(TAG, "Bad formatted FilterPeriodBegin "
                        + appParams.FilterPeriodBegin);
            }
        }

        /* Filter Period End */
        if ((appParams.FilterPeriodEnd != null)
                && (appParams.FilterPeriodEnd.length() > 0)) {
            Time time = new Time();
            try {
                time.parse(appParams.FilterPeriodEnd.trim());
                if (whereClause.length() != 0) {
                    whereClause += " AND ";
                }
                whereClause += "date < " + (time.toMillis(false))/1000;
            } catch (TimeFormatException e) {
                Log.d(TAG, "Bad formatted FilterPeriodEnd "
                        + appParams.FilterPeriodEnd);
            }
        }
        //Delivery report check
        if (whereClause.length() != 0) {
            whereClause += " AND ";
        }
        whereClause += "d_rpt > 0";

        return whereClause;
    }

    /**
     * Obtain the MMS msg_box id
     */
    private int getMmsMsgBox(long msgID) {
        int val = -1;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = mContext.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                int msgBoxInd = cursor.getColumnIndex("msg_box");
                val = cursor.getInt(msgBoxInd);
            }
            cursor.close();
        }
        return val;
    }

    /**
     * Obtain MMS message text
     */
    private String getMmsMsgTxt(long msgID) {
        String text = null;
        String whereClause = " mid= " + msgID + " AND ct=\"text/plain\"";
        Uri uri = Uri.parse("content://mms/part");
        ContentResolver cr = mContext.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                int textInd = cursor.getColumnIndex("text");
                text = cursor.getString(textInd);
            }
            cursor.close();
        }
        return text;
    }

    /**
     * Obtain the MMS message Subject
     */
    private String getMmsMsgSubject(long msgID) {
        String text = null;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = mContext.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                int subjectInd = cursor.getColumnIndex("sub");
                text = cursor.getString(subjectInd);
            }
            cursor.close();
        }
        return text;
    }

    /**
     * Obtain the MMS message Date
     */
    private String getMmsMsgDate(long msgID) {
        String text = "0";
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = mContext.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                int dateInd = cursor.getColumnIndex("date");
                text = cursor.getString(dateInd);
            }
            cursor.close();
        }
        return text;

    }

    /**
     * Obtain the MMS attachment size
     */
    private int getMmsMsgAttachSize(long msgID) {
        int attachSize = 0;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = mContext.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                int sizeInd = cursor.getColumnIndex("m_size");
                attachSize = cursor.getInt(sizeInd);
            }
            cursor.close();
        }
        return attachSize;

    }

    /**
     * Obtain the MMS message read status
     */
    private String getMmsMsgReadStatus(long msgID) {
        String text = null;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = mContext.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                int readInd = cursor.getColumnIndex("read");
                if (cursor.getInt(readInd) == 0) {
                    text = "no";
                } else {
                    text = "yes";
                }
            }
            cursor.close();
        }
        return text;
    }

    /**
     * Obtain the MMS message read sent
     */
    private String getMmsMsgReadSent(long msgID) {
        String text = null;
        if ( getMmsMsgBox(msgID) == 2 ) {
            // Sent folder
            text = "yes";
        } else {
            text = "no";
        }
        return text;
    }

    /**
     * Obtain the MMS message priority
     */
    private String getMmsMsgPriority(long msgID) {
        final int PRIORITY_LOW = 0X80;
        final int PRIORITY_NORMAL = 0X81;
        final int PRIORITY_HIGH = 0X82;

        String text = null;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = mContext.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor != null && cursor.getCount() > 0) {
            cursor.moveToFirst();
            int priInd = cursor.getColumnIndex("pri");

            switch (cursor.getInt(priInd)) {
            case PRIORITY_LOW:
                text = "no";
                break;
            case PRIORITY_NORMAL:
                text = "no";
                break;
            case PRIORITY_HIGH:
                text = "yes";
                break;

            default:
                text = "no";
                break;
            }
        }
        if (cursor != null) {
            cursor.close();
        }
        return text;
    }

    /**
     * Obtain the MMS message read protected
     */
    private String getMmsMsgProtected(long msgID) {
        String text = null;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = mContext.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                int readInd = cursor.getColumnIndex("locked");
                if (cursor.getInt(readInd) == 0) {
                    text = "no";
                } else {
                    text = "yes";
                }
            }
            cursor.close();
        }
        return text;

    }

    /**
     * Obtain MMS message address
     */
    private String getMmsMsgAddress(long msgID) {
        String text = null;
        String whereClause = " address != \"insert-address-token\"";
        Uri uri = Uri.parse("content://mms/" + msgID + "/addr");
        ContentResolver cr = mContext.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                int addressInd = cursor.getColumnIndex("address");
                text = cursor.getString(addressInd);
            }
            cursor.close();
        }
        return text;
    }

    /**
     * Get the folder name (MAP representation) based on the message Handle
     */
    private int getMmsContainingFolder(long msgID) {
        int folderNum = 0;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = mContext.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor != null) {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                int msgboxInd = cursor.getColumnIndex("msg_box");
                String thread_id = cursor.getString(cursor.getColumnIndex("thread_id"));
                if ( Integer.valueOf(thread_id) == DELETED_THREAD_ID) {
                    // Deleted folder
                    folderNum = 0;
                } else {
                    folderNum = cursor.getInt(msgboxInd);
                }
            }
            cursor.close();
        }
        return folderNum;
    }

    /**
     * Get MMS folder name based on value Inbox = 1 Sent = 2 Drafts = 3 Outbox =
     * 4 Queued = 6
     *
     */
    private String getMmsMapVirtualFolderName(int type) {
        String folderName = null;

        switch (type) {
        case 0:
            folderName = DELETED;
            break;
        case 1:
            folderName = INBOX;
            break;
        case 2:
            folderName = SENT;
            break;
        case 3:
            folderName = DRAFT;
            break;
        case 4: // outbox
        case 5: // failed
        case 6: // queued
            folderName = OUTBOX;
            break;

        default:
            break;
        }
        return folderName;
    }

    /**
     * Build an MMS bMessage when given a message handle
     */
    private BluetoothMasMessageRsp bldMmsBmsg(long msgID, BluetoothMasMessageRsp rsp) {
        Cursor cr = null;
        Uri uri = Uri.parse("content://mms/");
        String whereClause = " _id = " + msgID;
        cr = mContext.getContentResolver().query(uri, null, whereClause, null,
                null);
        if (cr == null) {
            rsp.rsp = ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            return rsp;
        }
        if (cr.getCount() > 0) {
            cr.moveToFirst();
            String containingFolder = getMmsMapVirtualFolderName((getMmsContainingFolder(msgID)));
            BmessageConsts bmsg = new BmessageConsts();

            // Create a bMessage
            bmsg.setType("MMS");

            bmsg.setBmsg_version("1.0");
            if (cr.getString(cr.getColumnIndex("read")).equalsIgnoreCase("1")) {
                bmsg.setStatus("READ");
            } else {
                bmsg.setStatus("UNREAD");
            }

            bmsg.setFolder(TELECOM + "/" + MSG + "/" + containingFolder);

            bmsg.setVcard_version("2.1");
            VcardContent vcard = getVcardContent(getMmsMsgAddress(msgID));
            String type = cr.getString(cr.getColumnIndex("msg_box"));
            // Inbox is type 1.
            if (type.equalsIgnoreCase("1")) {
                bmsg.setOriginatorVcard_name(vcard.name);
                bmsg.setOriginatorVcard_phone_number(vcard.tel);
                bmsg.setRecipientVcard_name(getOwnerName());
                bmsg.setRecipientVcard_phone_number(getOwnerNumber());
            } else {
                bmsg.setRecipientVcard_name(vcard.name);
                bmsg.setRecipientVcard_phone_number(vcard.tel);
                bmsg.setOriginatorVcard_name(getOwnerName());
                bmsg.setOriginatorVcard_phone_number(getOwnerNumber());

            }

            StringBuilder sb = new StringBuilder();
            Date date = new Date(Integer.valueOf(getMmsMsgDate(msgID)));
            sb.append("Date: ").append(date.toString()).append("\r\n");

            boolean MIME = true;
            boolean msgFormat = MIME;
            sb.append(bldMMSBody(bmsg, msgFormat, msgID));
            bmsg.setBody_msg(sb.toString());
            bmsg.setBody_length(sb.length() + 22);
            bmsg.setBody_encoding("8BIT");
            // Send a bMessage
            String str = MapUtils.toBmessageMMS(bmsg);
            if (V) Log.v(TAG, str);
            if (str != null && (str.length() > 0)) {
                final String FILENAME = "message" + getMasId();
                FileOutputStream bos = null;
                File file = new File(mContext.getFilesDir() + "/" + FILENAME);
                file.delete();

                try {
                    bos = mContext.openFileOutput(FILENAME, Context.MODE_PRIVATE);
                    bos.write(str.getBytes());
                    bos.flush();
                    bos.close();
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "Unable to write " + FILENAME, e);
                } catch (IOException e) {
                    Log.e(TAG, "Unable to write " + FILENAME, e);
                }

                File fileR = new File(mContext.getFilesDir() + "/" + FILENAME);
                if (fileR.exists() == true) {
                    rsp.file = fileR;
                    rsp.fractionDeliver = 1;
                }
            }
        }
        cr.close();
        return rsp;
    }

    private boolean isOutgoingMMSMessage(long mmsMsgID) {
        if (getMmsMsgBox(mmsMsgID) == 1) {
            return false;
        }
        return true;
    }

    /**
     * This method constructs an MMS message that is added to the message list
     * which is used to construct a message listing
     */
    private MsgListingConsts bldMmsMsgLstItem(long mmsMsgID, BluetoothMasAppParams appParams,
            String folderName, String datetimeStr) {

        MsgListingConsts ml = new MsgListingConsts();

        // Set the message handle
        ml.setMsg_handle(mmsMsgID + MMS_OFFSET_START);

        // Set the message subject
        if ((appParams.ParameterMask & BIT_SUBJECT) != 0) {
            ml.setSubject(getMmsMsgSubject(mmsMsgID));
            ml.sendSubject = true;
        }

        // Construct datetime value
        if ((appParams.ParameterMask & BIT_DATETIME) != 0) {
            ml.setDatetime(datetimeStr);
        }

        // Construct msg body
        if ((appParams.ParameterMask & BIT_TEXT) != 0) {
            if ((getMmsMsgTxt(mmsMsgID) != null)) {
                ml.setContains_text("yes");
            } else {
                ml.setContains_text("no");
            }

        }

        // Set text size
        if ((appParams.ParameterMask & BIT_SIZE) != 0) {
            final String mmsMsgTxt = getMmsMsgTxt(mmsMsgID);
            ml.setSize(mmsMsgTxt == null ? 0 : mmsMsgTxt.length());
        }

        // Set message type
        if ((appParams.ParameterMask & BIT_TYPE) != 0) {
            ml.setType("MMS");
        }

        if ((appParams.ParameterMask & BIT_RECIPIENT_NAME) != 0) {
            // TODO: "recipient_name" is the name of the
            // recipient of the message, when it is known
            // by the MSE device.
            String recipientName = null;
            if (isOutgoingMMSMessage(mmsMsgID) == false) {
                recipientName = getOwnerName();
            } else {
                recipientName = getContactName(getMmsMsgAddress(mmsMsgID));
            }
            ml.setRecepient_name(recipientName);
        }

        if ((appParams.ParameterMask & BIT_RECIPIENT_ADDRESSING) != 0) {
            // TODO: In case of a SMS this is the recipient's phone number
            // in canonical form (chapter 2.4.1 of [5])
            String recipientAddressing = null;
            if (isOutgoingMMSMessage(mmsMsgID) == false) {
                recipientAddressing = getOwnerNumber();
            } else {
                recipientAddressing = getMmsMsgAddress(mmsMsgID);
            }
            ml.setRecepient_addressing(recipientAddressing);
            ml.setSendRecipient_addressing(true);
        }

        if ((appParams.ParameterMask & BIT_SENDER_NAME) != 0) {
            String senderName = null;
            if (isOutgoingMMSMessage(mmsMsgID) == true) {
                senderName = getOwnerName();
            } else {
                senderName = getContactName(getMmsMsgAddress(mmsMsgID));
            }
            ml.setSender_name(senderName);
        }

        if ((appParams.ParameterMask & BIT_SENDER_ADDRESSING) != 0) {
            String senderAddressing = null;
            if (isOutgoingMMSMessage(mmsMsgID) == true) {
                senderAddressing = getOwnerNumber();
            } else {
                senderAddressing = getMmsMsgAddress(mmsMsgID);
            }
            ml.setSender_addressing(senderAddressing);
        }

        // Set read status
        if ((appParams.ParameterMask & BIT_READ) != 0) {
            final String mmsMsgStatus = getMmsMsgReadStatus(mmsMsgID);
            if (mmsMsgStatus != null && mmsMsgStatus.equalsIgnoreCase("yes")) {
                ml.setRead("yes");
            } else {
                ml.setRead("no");
            }
        }

        // Set priority
        if ((appParams.ParameterMask & BIT_PRIORITY) != 0) {
            ml.setPriority(getMmsMsgPriority(mmsMsgID));
        }

        // Set Protected
        if ((appParams.ParameterMask & BIT_PROTECTED) != 0) {
            ml.setMsg_protected(getMmsMsgProtected(mmsMsgID));
        }

        // Set sent
        if ((appParams.ParameterMask & BIT_SENT) != 0) {
            ml.setSent(getMmsMsgReadSent(mmsMsgID));
        }

        // Set reception status
        if ((appParams.ParameterMask & BIT_RECEPTION_STATUS) != 0) {
            ml.setReception_status("complete");
        }

        // Set attachment size
        if ((appParams.ParameterMask & BIT_ATTACHMENT_SIZE) != 0) {
            ml.setAttachment_size(getMmsMsgAttachSize(mmsMsgID));
        }

        return ml;
    }

    /**
     * This method is used to take an MMS in the drafts folder and move it to
     * the outbox This action is required to add the MMS to the pending_msgs
     * table which is used to send the MMS out to the network
     */
    private void moveMMSfromDraftstoOutbox() {

        String handle = null;

        // scan drafts folder for an MMS to send
        // fetch the message handle
        Uri uri = Uri.parse("content://mms/drafts");
        ContentResolver cr = mContext.getContentResolver();
        Cursor crID = cr.query(uri, null, null, null, null);
        if (crID != null) {
            if (crID.getCount() > 0) {
                crID.moveToFirst();
                int msgIDInd = crID.getColumnIndex("_id");
                handle = crID.getString(msgIDInd);
            }
            crID.close();
        }

        if (handle != null) {
            String whereClause = " _id= " + handle;
            uri = Uri.parse("content://mms");
            crID = cr.query(uri, null, whereClause, null, null);
            if (crID != null) {
                if (crID.getCount() > 0) {
                    crID.moveToFirst();
                    ContentValues values = new ContentValues();
                    values.put("msg_box", 4);
                    cr.update(uri, values, whereClause, null);
                }
                crID.close();
            }
        }
    }

    /**
     * This method is used to take a Bmessage that was pushed and move it to the
     * folder
     */
    private String addToMmsFolder(String folderName, String mmsMsg) throws BadRequestException {
        if (folderName == null) {
            return null;
        }
        if (folderName.equalsIgnoreCase(DRAFT)) {
            folderName = DRAFTS;
        }
        int folderType = SmsMmsUtils.getFolderTypeMms(folderName);
        BmessageConsts bMsg = MapUtils.fromBmessageMMS(mmsMsg);
        String address = bMsg.getRecipientVcard_phone_number();
        String mmsText = bMsg.getBody_msg();

        /**
         * The PTS tester does not contain the same message format as CE4A This
         * code /* looks at the pushed message and checks for the message boundary. If
         * it does not /* find it then it then it assumes PTS tester format
         */
        mmsText = MapUtils.fetchBodyEmail(mmsText);
        ContentValues values = new ContentValues();
        values.put("msg_box", folderType);

        if (folderName.equalsIgnoreCase(DELETED)) {
            values.put("thread_id", -1);
        } else {
            values.put("thread_id", createMMSThread(address));
        }

        // function that creates a thread ID
        values.put("read", 0);
        values.put("seen", 0);
        values.put("sub_cs", 106);
        values.put("ct_t", "application/vnd.wap.multipart.related");
        values.put("exp", 604800);
        values.put("m_cls", "personal");
        values.put("m_type", 128);
        values.put("v", 18);
        values.put("pri", 129);
        values.put("rr", 129);
        values.put("tr_id", "T12dc2e87182");
        values.put("d_rpt", 129);
        values.put("locked", 0);

        Uri uri;
        if (folderName.equalsIgnoreCase(DELETED)) {
            uri = Uri.parse("content://mms/inbox");
        } else {
            uri = Uri.parse("content://mms/" + folderName);
        }
        ContentResolver cr = mContext.getContentResolver();
        uri = cr.insert(uri, values);

        if (uri == null) {
            // unable to insert MMS
            Log.e(TAG, "Unabled to insert MMS " + values);
            return INTERNAL_ERROR;
        }
        String msgNum = uri.getLastPathSegment();
        long msgID = Long.parseLong(msgNum);
        if (V){
            Log.v(TAG, " NEW URI " + uri.toString());
        }
        long virtualMsgId = (msgID + MMS_OFFSET_START);

        // Build the \mms\part portion
        values.clear();

        values.put("seq", -1);
        values.put("ct", "application/smil");
        values.put("cid", "<smil>");
        values.put("cl", "smil.xml");
        values.put("text", "<smil><head><layout><root-layout width=\"320px\" height=\"480px\"/>" +
                "<region id=\"Text\" left=\"0\" top=\"320\" width=\"320px\" height=\"160px\"" +
                " fit=\"meet\"/></layout></head><body><par dur=\"5000ms\">" +
                "<text src=\"text_0.txt\" region=\"Text\"/></par></body></smil>");

        uri = Uri.parse("content://mms/" + msgID + "/part");
        uri = cr.insert(uri, values);
        if (uri != null && V){
            Log.v(TAG, " NEW URI " + uri.toString());
        }

        values.clear();
        values.put("seq", 0);
        values.put("ct", "text/plain");
        values.put("name", "null");
        values.put("chset", 106);
        values.put("cd", "null");
        values.put("fn", "null");
        values.put("cid", "<smil>");
        values.put("cl", "text_0.txt");
        values.put("ctt_s", "null");
        values.put("ctt_t", "null");
        values.put("_data", "null");
        values.put("text", mmsText);

        uri = Uri.parse("content://mms/" + msgID + "/part");
        uri = cr.insert(uri, values);
        if (uri != null && V){
            Log.v(TAG, " NEW URI " + uri.toString());
        }

        values.clear();
        values.put("contact_id", "null");
        values.put("address", "insert-address-token");
        values.put("type", 137);
        values.put("charset", 106);

        uri = Uri.parse("content://mms/" + msgID + "/addr");
        uri = cr.insert(uri, values);
        if (uri != null && V){
            Log.v(TAG, " NEW URI " + uri.toString());
        }

        values.clear();
        values.put("contact_id", "null");
        values.put("address", address);
        values.put("type", 151);
        values.put("charset", 106);

        uri = Uri.parse("content://mms/" + msgID + "/addr");
        uri = cr.insert(uri, values);
        if (uri != null && V){
            Log.v(TAG, " NEW URI " + uri.toString());
        }

        String virtualMsgIdStr = String.valueOf(virtualMsgId);
        String whereClause = "address LIKE '" + address + "' AND type = 125";
        if (!folderName.equalsIgnoreCase("deleted")) {
            mContext.getContentResolver().delete(Uri.parse("content://sms/"), whereClause, null);
        }
        return virtualMsgIdStr;
    }

    /**
     * Method to construct body of bmessage using either MIME or no MIME
     *
     */
    private String bldMMSBody(BmessageConsts bMsg, boolean msgType, long msgID) {
        boolean MIME = true;
        StringBuilder sb = new StringBuilder();

        if (msgType == MIME) {
            Random randomGenerator = new Random();
            int randomInt = randomGenerator.nextInt(1000);
            String boundary = "MessageBoundary."+randomInt;
            final String mmsMsgTxt = getMmsMsgTxt(msgID);
            if(mmsMsgTxt != null){
                while(mmsMsgTxt.contains(boundary)){
                    randomInt = randomGenerator.nextInt(1000);
                    boundary = "MessageBoundary."+randomInt;
                }
            }
            sb.append("To:").append(bMsg.recipient_vcard_phone_number)
                    .append("\r\n");
            sb.append("Mime-Version: 1.0").append("\r\n");
            sb.append(
                    "Content-Type: multipart/mixed; boundary=\""+boundary+"\"")
                    .append("\r\n");
            sb.append("Content-Transfer-Encoding: 7bit").append("\r\n")
                    .append("\r\n");
            sb.append("MIME Message").append("\r\n");
            sb.append("--"+boundary).append("\r\n");
            sb.append("Content-Type: text/plain").append("\r\n");
            sb.append("Content-Transfer-Encoding: 8bit").append("\r\n");
            sb.append("Content-Disposition:inline").append("\r\n")
                    .append("\r\n");
            sb.append(getMmsMsgTxt(msgID)).append("\r\n");
            sb.append("--"+boundary+"--").append("\r\n")
                    .append("\r\n");
        } else {
            sb.append("Subject:").append("Not Implemented").append("\r\n");
            sb.append("From:").append(bMsg.originator_vcard_phone_number)
                    .append("\r\n");
            sb.append(getMmsMsgTxt(msgID)).append("\r\n").append("\r\n");
        }
        return sb.toString();
    }

    /**
     * Method to create a thread for a pushed MMS message
     *
     */
    private int createMMSThread(String address) {
        int returnValue = 0;
        if (address != null) {
            if (V){
                Log.v(TAG, "Inside adress not null");
            }
            ContentValues tempValue = new ContentValues();
            tempValue.put("address", address);
            tempValue.put("type", 125);
            Uri tempUri = mContext.getContentResolver().insert(
                    Uri.parse("content://sms/"), tempValue);

            if (tempUri != null) {
                Cursor tempCr = mContext.getContentResolver().query(tempUri, null,
                        null, null, null);
                if (tempCr != null) {
                    tempCr.moveToFirst();
                    String newThreadIdStr = tempCr.getString(tempCr
                            .getColumnIndex("thread_id"));
                    tempCr.close();
                    returnValue = Integer.valueOf(newThreadIdStr);
                }
                if (V){
                    Log.v(TAG, "Thread ID::"+returnValue);
                }
            }
        }

        return returnValue;
    }

    private String bldSmsBmsg(long msgHandle, Context context, Cursor cr,
                BluetoothMasAppParams bluetoothMasAppParams) {
        String str = null;
        if (cr.getCount() > 0) {
            cr.moveToFirst();
            String containingFolder = getContainingFolder(msgHandle);
            BmessageConsts bmsg = new BmessageConsts();

            // Create a bMessage

            if (TelephonyManager.getDefault().getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
                bmsg.setType("SMS_CDMA");
            } else {
                bmsg.setType("SMS_GSM");
            }

            bmsg.setBmsg_version("1.0");
            if (cr.getString(cr.getColumnIndex("read")).equalsIgnoreCase("1")) {
                bmsg.setStatus("READ");
            } else {
                bmsg.setStatus("UNREAD");
            }

            bmsg.setFolder(TELECOM + "/" + MSG + "/" + containingFolder);

            bmsg.setVcard_version("2.1");
            VcardContent vcard = getVcardContent(cr.getString(cr
                    .getColumnIndex("address")));

            String type = cr.getString(cr.getColumnIndex("type"));
            if (type.equalsIgnoreCase("1")) {
                // The address in database is of originator
                bmsg.setOriginatorVcard_name(vcard.name);
                bmsg.setOriginatorVcard_phone_number(vcard.tel);
                bmsg.setRecipientVcard_name(getOwnerName());
                bmsg.setRecipientVcard_phone_number(getOwnerNumber());
            } else {
                bmsg.setRecipientVcard_name(vcard.name);
                bmsg.setRecipientVcard_phone_number(vcard.tel);
                bmsg.setOriginatorVcard_name(getOwnerName());
                bmsg.setOriginatorVcard_phone_number(getOwnerNumber());
            }

            String smsBody = " ";

            if ( (int)bluetoothMasAppParams.Charset == 1){
                bmsg.setBody_charset("UTF-8");
                smsBody = cr.getString(cr.getColumnIndex("body"));
            }

            if ( (int)bluetoothMasAppParams.Charset == 0){
                bmsg.setBody_encoding("G-7BIT");
                String smsBodyUnicode = cr.getString(cr.getColumnIndex("body"));
                smsBody = getSMSDeliverPdu(smsBodyUnicode, cr.getString(cr.getColumnIndex("date")),
                        vcard.tel);
            }

            bmsg.setBody_length(22 + smsBody.length());

            bmsg.setBody_msg(smsBody);
            cr.close();

            // Send a bMessage
            if (V){
                Log.v(TAG, "bMessageSMS test\n");
                Log.v(TAG, "=======================\n\n");
            }
            str = MapUtils.toBmessageSMS(bmsg);
        }
        return str;
    }

    private MsgListingConsts bldSmsMsgLstItem(BluetoothMasAppParams appParams,
                String subject, String timestamp, String address, String msgId,
                String readStatus, int msgType) {
        MsgListingConsts ml = new MsgListingConsts();
        ml.setMsg_handle(Integer.valueOf(msgId));

        Time time = new Time();
        time.set(Long.valueOf(timestamp));

        String datetimeStr = time.toString().substring(0, 15);

        ml.msgInfo.setDateTime(datetimeStr);

        if ((appParams.ParameterMask & BIT_SUBJECT) != 0) {
            /* SMS doesn't have subject. Append Body
             * so that remote client doesn't have to do
             * GetMessage
             */
            ml.setSendSubject(true);
            if (subject == null) {
                subject = "";
            } else if (subject != null && subject.length() > appParams.SubjectLength ) {
                subject = subject.substring(0,
                        appParams.SubjectLength);
            }
            ml.setSubject(subject);
        }

        if ((appParams.ParameterMask & BIT_DATETIME) != 0) {
            ml.setDatetime(datetimeStr);
        }

        if ((appParams.ParameterMask & BIT_SENDER_NAME) != 0) {
            // TODO: Query the Contacts database
            String senderName = null;
            if (isOutgoingSMSMessage(msgType) == true) {
                senderName = getOwnerName();
            } else {
                senderName = getContactName(address);
            }
            ml.setSender_name(senderName);
        }

        if ((appParams.ParameterMask & BIT_SENDER_ADDRESSING) != 0) {
            // TODO: In case of a SMS this is the sender's phone number in canonical form
            // (chapter 2.4.1 of [5]).
            String senderAddressing = null;
            if (isOutgoingSMSMessage(msgType) == true) {
                senderAddressing = getOwnerNumber();
            } else {
                senderAddressing = address;
            }
            ml.setSender_addressing(senderAddressing);
        }

        if ((appParams.ParameterMask & BIT_RECIPIENT_NAME) != 0) {
            // TODO: "recipient_name" is the name of the recipient of
            // the message, when it is known by the MSE device.
            String recipientName = null;
            if (isOutgoingSMSMessage(msgType) == false) {
                recipientName = getOwnerName();
            } else {
                recipientName = getContactName(address);
            }
            ml.setRecepient_name(recipientName);
        }

        if ((appParams.ParameterMask & BIT_RECIPIENT_ADDRESSING) != 0) {
            // TODO: In case of a SMS this is the recipient's phone
            // number in canonical form (chapter 2.4.1 of [5])
            String recipientAddressing = null;
            if (isOutgoingSMSMessage(msgType) == false) {
                recipientAddressing = getOwnerNumber();
            } else {
                recipientAddressing = address;
            }
            ml.setRecepient_addressing(recipientAddressing);
            ml.setSendRecipient_addressing(true);
        }

        if ((appParams.ParameterMask & BIT_TYPE) != 0) {
            final int phoneType = TelephonyManager.getDefault().getPhoneType();
            if (phoneType == TelephonyManager.PHONE_TYPE_CDMA) {
                ml.setType("SMS_CDMA");
            } else {
                ml.setType("SMS_GSM");
            }
        }

        if ((appParams.ParameterMask & BIT_SIZE) != 0) {
            ml.setSize(subject.length());
        }

        if ((appParams.ParameterMask & BIT_RECEPTION_STATUS) != 0) {
            ml.setReception_status("complete");
        }

        if ((appParams.ParameterMask & BIT_TEXT) != 0) {
            // TODO: Set text to "yes"
            ml.setContains_text("yes");
        }

        if ((appParams.ParameterMask & BIT_ATTACHMENT_SIZE) != 0) {
            ml.setAttachment_size(0);
        }

        if ((appParams.ParameterMask & BIT_PRIORITY) != 0) {
            // TODO: Get correct priority
            ml.setPriority("no");
        }

        if ((appParams.ParameterMask & BIT_READ) != 0) {
            if (readStatus.equalsIgnoreCase("1")) {
                ml.setRead("yes");
            } else {
                ml.setRead("no");
            }
        }

        if ((appParams.ParameterMask & BIT_SENT) != 0) {
            // TODO: Get sent status?
            if (msgType == 2) {
                ml.setSent("yes");
            } else {
                ml.setSent("no");
            }
        }

        if ((appParams.ParameterMask & BIT_PROTECTED) != 0) {
            ml.setMsg_protected("no");
        }

        return ml;
    }
    private BluetoothMsgListRsp msgListSms(List<MsgListingConsts> msgList, String folder,
            BluetoothMasMessageListingRsp rsp, BluetoothMasAppParams appParams) {
        BluetoothMsgListRsp bmlr = new BluetoothMsgListRsp();
        String url = "content://sms/";
        Uri uri = Uri.parse(url);
        ContentResolver cr = mContext.getContentResolver();
        String whereClause  = SmsMmsUtils.getConditionStringSms(folder, appParams);

        Cursor cursor = cr.query(uri, null, whereClause, null,
                "date desc");

        if (cursor != null && V) {
                Log.v(TAG, "move to First" + cursor.moveToFirst());
        }
        if (cursor != null && V) {
                Log.v(TAG, "move to Liststartoffset"
                    + cursor.moveToPosition(appParams.ListStartOffset));
        }
        if (cursor != null && cursor.moveToFirst()) {
            int idInd = cursor.getColumnIndex("_id");
            int addressInd = cursor.getColumnIndex("address");
            int personInd = cursor.getColumnIndex("person");
            int dateInd = cursor.getColumnIndex("date");
            int readInd = cursor.getColumnIndex("read");
            int statusInd = cursor.getColumnIndex("status");
            int subjectInd = cursor.getColumnIndex("subject");
            int typeInd = cursor.getColumnIndex("type");
            int bodyInd = cursor.getColumnIndex("body");

            do {
                /*
                 * Apply remaining filters
                 */

                /*
                 * For incoming message, originator is the remote
                 * contact For outgoing message, originator is the
                 * owner.
                 */
                String filterString = null;
                String oname = getOwnerName();
                if (oname == null) {
                    oname = "";
                }
                String onumber = getOwnerNumber();
                if (onumber == null) {
                    onumber = "";
                }

                int msgType = cursor.getInt(typeInd);
                String regExpOrig = null;
                String regExpRecipient = null;
                if (appParams.FilterOriginator != null) {
                    regExpOrig = appParams.FilterOriginator.replace("*", ".*[0-9A-Za-z].*");
                }
                if (appParams.FilterRecipient != null) {
                    regExpRecipient = appParams.FilterRecipient.replace("*", ".*[0-9A-Za-z].*");
                }
                if (isOutgoingSMSMessage(msgType) == true) {
                    if ((appParams.FilterOriginator != null)
                            && (appParams.FilterOriginator.length() != 0)
                            && !(oname.matches(".*"+regExpOrig+".*"))
                            && !(onumber.matches(".*"+regExpOrig+".*"))) {
                        continue;
                    }
                    if ((appParams.FilterRecipient != null)
                            && (appParams.FilterRecipient.length() != 0)) {
                        filterString = appParams.FilterRecipient.trim();
                        if (V){
                                Log.v(TAG, "appParams.FilterRecipient"
                                    + appParams.FilterRecipient);
                        }
                    }
                }
                if (isOutgoingSMSMessage(msgType) == false) {
                    if ((appParams.FilterRecipient != null)
                            && (appParams.FilterRecipient.length() != 0)
                            && !(oname.matches(".*"+regExpRecipient+".*"))
                            && !(onumber.matches(".*"+regExpRecipient+".*"))) {
                        continue;
                    }
                    if ((appParams.FilterOriginator != null)
                            && (appParams.FilterOriginator.length() != 0)) {
                        filterString = appParams.FilterOriginator.trim();
                        if (V){
                                Log.v(TAG, "appParams.FilterOriginator"
                                    + appParams.FilterOriginator);
                        }
                    }
                }

                if (filterString != null) {
                    if (V){
                        Log.v(TAG, "filterString = " + filterString);
                    }
                    if (allowEntry(cursor.getString(addressInd),
                            filterString) == true) {
                        if (V){
                                Log.v(TAG,
                                    " ALLOWED : "
                                    + cursor.getString(addressInd)
                                    + " - " + cursor.getPosition());
                        }
                    } else {
                        if (V){
                                Log.v(TAG,
                                    " DENIED : "
                                    + cursor.getString(addressInd)
                                    + " - " + cursor.getPosition());
                        }
                        continue;
                    }
                }
                if (V) Log.v(TAG, " msgListSize " + rsp.msgListingSize);
                rsp.msgListingSize++;

                /*
                 * Don't want the listing; just send the listing size
                 * after applying all the filters.
                 */
                if (appParams.MaxListCount == 0) {
                    continue;
                }
                String msgIdSms = cursor.getString(idInd);
                String subjectSms = cursor.getString(bodyInd);
                String timestampSms = cursor.getString(dateInd);
                String addressSms = cursor.getString(addressInd);
                String readStatusSms = cursor.getString(readInd);

                MsgListingConsts ml = bldSmsMsgLstItem(appParams, subjectSms,
                                timestampSms, addressSms, msgIdSms,
                                readStatusSms, msgType);

                // New Message?
                if ((rsp.newMessage == 0)
                        && (cursor.getInt(readInd) == 0)) {
                    rsp.newMessage = 1;
                }

                msgList.add(ml);
            } while (cursor.moveToNext());
        }
        if (cursor != null) {
            cursor.close();
        }
        rsp.rsp = ResponseCodes.OBEX_HTTP_OK;
        bmlr.messageListingSize = rsp.msgListingSize;
        bmlr.rsp = rsp;
        bmlr.msgList = msgList;
        return bmlr;
    }

    private BluetoothMsgListRsp msgListMms(List<MsgListingConsts> msgList, String name,
            BluetoothMasMessageListingRsp rsp, BluetoothMasAppParams appParams) {
        BluetoothMsgListRsp bmlr = new BluetoothMsgListRsp();
        String filterString = null;

        String oname = getOwnerName();
        if (oname == null) {
            oname = "";
        }

        String onumber = getOwnerNumber();
        if (onumber == null) {
            onumber = "";
        }

        Log.v(TAG, "oname = " + oname + "onumber = " + onumber);

        String regExpOrig = null;
        String regExpRecipient = null;

        if (appParams.FilterOriginator != null) {
            regExpOrig = appParams.FilterOriginator.replace("*", ".*[0-9A-Za-z].*");
        }

        if (appParams.FilterRecipient != null) {
            regExpRecipient = appParams.FilterRecipient.replace("*", ".*[0-9A-Za-z].*");
        }

        Log.v(TAG, " regExpOrig = " + regExpOrig + " regExpRecipient = " + regExpRecipient);

        if (getNumMmsMsgs(name) != 0) {
            List<Integer> list = getMmsMsgMIDs(bldMmsWhereClause(
                    appParams, SmsMmsUtils.getFolderTypeMms(name)));
            for (int msgId : list) {
                if (V){
                        Log.v(TAG, "\n MMS Text message ==> "
                            + getMmsMsgTxt(msgId));
                }
                if (V){
                        Log.v(TAG, "\n MMS message subject ==> "
                            + getMmsMsgSubject(msgId));
                }
                if (isOutgoingMMSMessage(msgId) == false) {
                    if ((appParams.FilterRecipient != null)
                        && (appParams.FilterRecipient.length() != 0)
                        && !(oname.matches(".*"+regExpRecipient+".*"))
                        && !(onumber.matches(".*"+regExpRecipient+".*"))) {
                            continue;
                        }
                    if ((appParams.FilterOriginator != null)
                        && (appParams.FilterOriginator.length() != 0)) {
                        filterString = appParams.FilterOriginator.trim();
                        if (V){
                            Log.v(TAG, " appParams.FilterOriginator"
                                + appParams.FilterOriginator);
                        }
                    }
                }

                if (isOutgoingMMSMessage(msgId) == true) {
                    if ((appParams.FilterOriginator != null)
                        && (appParams.FilterOriginator.length() != 0)
                        && !(oname.matches(".*"+regExpOrig+".*"))
                        && !(onumber.matches(".*"+regExpOrig+".*"))) {
                        continue;
                    }

                    if ((appParams.FilterRecipient != null)
                        && (appParams.FilterRecipient.length() != 0)) {
                        filterString = appParams.FilterRecipient.trim();
                        if (V){
                            Log.v(TAG, " appParams.FilterRecipient"
                                + appParams.FilterRecipient);
                        }
                    }
                }

                if (filterString != null) {
                    if (V){
                        Log.v(TAG, " filterString = " + filterString);
                    }
                    String ContactName = null;
                    String ContactNum = null;

                    ContactName = getContactName(getMmsMsgAddress(msgId));
                    ContactNum = getMmsMsgAddress(msgId);

                    if (ContactName.matches(filterString) || ContactNum.matches(filterString)) {
                        if (V){
                            Log.v(TAG, " ALLOWED : "
                                + ContactName + " - " + ContactNum );
                        }
                    } else {
                        if (V){
                            Log.v(TAG, " DENIED : "
                                + ContactName + " - " + ContactNum );
                        }
                        continue;
                    }
                }

                String datetime = getMmsMsgDate(msgId);
                Time time = new Time();
                Date dt = new Date(Long.valueOf(datetime));
                time.set((dt.getTime() * 1000));

                String datetimeStr = time.toString().substring(0, 15);

                MsgListingConsts mmsl = bldMmsMsgLstItem(msgId, appParams, name, datetimeStr);
                mmsl.msgInfo.setDateTime(datetimeStr);

                if ((rsp.newMessage == 0)
                        && "no".equalsIgnoreCase(getMmsMsgReadStatus(msgId))) {
                    rsp.newMessage = 1;
                }

                msgList.add(mmsl);
                rsp.msgListingSize++;
            }
        }
        rsp.rsp = ResponseCodes.OBEX_HTTP_OK;
        bmlr.messageListingSize = rsp.msgListingSize;
        bmlr.rsp = rsp;
        bmlr.msgList = msgList;
        return bmlr;
    }

    private BluetoothMasMessageRsp getMessageSms(long msgHandle, Context context,
            BluetoothMasMessageRsp rsp, BluetoothMasAppParams bluetoothMasAppParams) {
        long smsHandle = msgHandle - SMS_OFFSET_START;
        Cursor cr = null;
        Uri uri = Uri.parse("content://sms/");
        String whereClause = " _id = " + smsHandle;
        try {
            cr = context.getContentResolver().query(uri, null, whereClause, null,
                    null);
        } catch (Exception e){
            rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            return rsp;
        }
        if (cr == null) {
            rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            return rsp;
        }
        String strSms = bldSmsBmsg(smsHandle, context, cr, bluetoothMasAppParams);
        cr.close();
        if (V) Log.v(TAG, strSms);

        if (strSms != null && (strSms.length() > 0)) {
            final String FILENAME = "message" + getMasId();
            FileOutputStream bos = null;
            File file = new File(context.getFilesDir() + "/" + FILENAME);
            file.delete();

            try {
                bos = context.openFileOutput(FILENAME, Context.MODE_PRIVATE);
                bos.write(strSms.getBytes());
                bos.flush();
                bos.close();
            } catch (FileNotFoundException e) {
                Log.e(TAG, "Unable to write " + FILENAME, e);
            } catch (IOException e) {
                Log.e(TAG, "Unable to write " + FILENAME, e);
            }

            File fileR = new File(context.getFilesDir() + "/" + FILENAME);
            if (fileR.exists() == true) {
                rsp.file = fileR;
                rsp.fractionDeliver = 1;
            }
        }
        return rsp;
    }

    private BluetoothMasMessageRsp getMessageMms(long msgHandle, BluetoothMasMessageRsp rsp) {
        long mmsMsgID = 0;
        try {
            mmsMsgID = getMmsMsgHndToID(msgHandle);
        } catch (Exception e) {
            rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            return rsp;
        }
        if (mmsMsgID > 0) {
            rsp = bldMmsBmsg(mmsMsgID, rsp);
        }
        return rsp;
    }

    private BluetoothMasPushMsgRsp pushMessageMms(BluetoothMasPushMsgRsp rsp,
            String readStr, String name) throws BadRequestException {
        String fullPath = (name == null || name.length() == 0) ? mCurrentPath : mCurrentPath + "/" + name;
        if (fullPath.equalsIgnoreCase("telecom/msg/outbox")) {
            String handle = addToMmsFolder(DRAFTS, readStr);
            if (INTERNAL_ERROR == handle) {  // == comparison valid here
                rsp.response = ResponseCodes.OBEX_HTTP_NOT_FOUND;
                return rsp;
            }
            moveMMSfromDraftstoOutbox();
            if (V) Log.v(TAG, "\nBroadcasting Intent to MmsSystemEventReceiver\n ");
            Intent sendIntent = new Intent("android.intent.action.MMS_PUSH");
            mContext.sendBroadcast(sendIntent);
            rsp.msgHandle = handle;
            rsp.response = ResponseCodes.OBEX_HTTP_OK;
            return rsp;
        } else {
            String splitStrings[] = mCurrentPath.split("/");
            mMnsClient.addMceInitiatedOperation("+");
            int tmp = splitStrings.length;
            String folderName;
            if (name != null) {
                if (name.length() == 0) {
                    folderName = splitStrings[tmp - 1];
                } else {
                    folderName = name;
                }
            } else {
                folderName = splitStrings[tmp - 1];
            }
            if (folderName != null && folderName.equalsIgnoreCase(DRAFT)){
                String handle = addToMmsFolder(folderName, readStr);
                if (INTERNAL_ERROR == handle) {  // == comparison valid here
                    rsp.msgHandle = null;
                    rsp.response = ResponseCodes.OBEX_HTTP_NOT_FOUND;
                    return rsp;
                }
                rsp.msgHandle = handle;
                rsp.response = ResponseCodes.OBEX_HTTP_OK;
                return rsp;
            } else {
                rsp.msgHandle = null;
                rsp.response = ResponseCodes.OBEX_HTTP_FORBIDDEN;
                return rsp;
            }
        }
    }

    private BluetoothMasPushMsgRsp pushMessageSms(BluetoothMasPushMsgRsp rsp, String readStr,
            String name, BluetoothMasAppParams bluetoothMasAppParams) throws BadRequestException {
        BmessageConsts bMsg = MapUtils.fromBmessageSMS(readStr);
        String address = bMsg.getRecipientVcard_phone_number();
        String smsText = bMsg.getBody_msg();
        String fullPath = (name == null || name.length() == 0) ? mCurrentPath : mCurrentPath + "/" + name;
        if(!"telecom/msg/outbox".equalsIgnoreCase(fullPath)) {
            String splitStrings[] = mCurrentPath.split("/");
            mMnsClient.addMceInitiatedOperation("+");
            int tmp = splitStrings.length;
            String folderName;
            if (name != null) {
                if (name.length() == 0){
                    folderName = splitStrings[tmp - 1];
                } else {
                    folderName = name;
                }
            } else {
                folderName = splitStrings[tmp - 1];
            }
            if (folderName != null && folderName.equalsIgnoreCase(DRAFT)) {
                String handle = addToSmsFolder(folderName, address, smsText);
                if (INTERNAL_ERROR == handle) {  // == comparison valid here
                    rsp.msgHandle = null;
                    rsp.response = ResponseCodes.OBEX_HTTP_NOT_FOUND;
                    return rsp;
                }
                rsp.msgHandle = handle;
                rsp.response = ResponseCodes.OBEX_HTTP_OK;
                return rsp;
            } else {
                rsp.msgHandle = null;
                rsp.response = ResponseCodes.OBEX_HTTP_FORBIDDEN;
                return rsp;
            }
        }
        rsp.msgHandle = "";
        if (bluetoothMasAppParams.Transparent == 0) {
            mMnsClient.addMceInitiatedOperation("+");
            String handle = addToSmsFolder(QUEUED, address, smsText);
            if (INTERNAL_ERROR == handle) {  // == comparison valid here
                rsp.msgHandle = null;
                rsp.response = ResponseCodes.OBEX_HTTP_NOT_FOUND;
                return rsp;
            }
            rsp.msgHandle = handle;
            rsp.response = ResponseCodes.OBEX_HTTP_OK;
        } else if (bluetoothMasAppParams.Transparent == 1) {
            ArrayList<String> parts = new ArrayList<String>();
            SmsManager sms = SmsManager.getDefault();
            parts = sms.divideMessage(smsText);

            mMnsClient.addMceInitiatedOperation("+");
            sms.sendMultipartTextMessage(address, null, parts, null, null);
            rsp.msgHandle = "-1";
            rsp.response = ResponseCodes.OBEX_HTTP_OK;
            return rsp;
        }

        if (V) {
            Log.v(TAG, " Trying to send SMS ");
            Log.v(TAG, " Text " + smsText + " address " + address);
        }

        try {
            Intent sendIntentSms = new Intent("com.android.mms.transaction.SEND_MESSAGE");
            sendIntentSms.putExtra(android.content.Intent.EXTRA_PHONE_NUMBER, address);
            sendIntentSms.putExtra(android.content.Intent.EXTRA_TEXT, smsText);
            mContext.sendBroadcast(sendIntentSms);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return rsp;
    }

    private int setMsgStatusSms(long msgHandle, BluetoothMasAppParams bluetoothMasAppParams){
        long handle = msgHandle - SMS_OFFSET_START;
        Uri uri = Uri.parse("content://sms/" + handle);
        Cursor cr = mContext.getContentResolver().query(uri, null, null, null, null);
        if (cr != null && cr.moveToFirst()) {
            if (bluetoothMasAppParams.StatusIndicator == 0) {
                /* Read Status */
                ContentValues values = new ContentValues();
                values.put("read", bluetoothMasAppParams.StatusValue);
                mContext.getContentResolver().update(uri, values, null, null);
            } else {
                if (bluetoothMasAppParams.StatusValue == 1) {
                    deleteSMS(handle);
                } else if (bluetoothMasAppParams.StatusValue == 0) {
                    unDeleteSMS(handle);
                }
            }
        }
        if (cr != null) {
            cr.close();
        }
        // Do we need to return ResponseCodes.OBEX_HTTP_BAD_REQUEST when cr == null?
        return ResponseCodes.OBEX_HTTP_OK;
    }

    private int setMsgStatusMms(long msgHandle, BluetoothMasAppParams bluetoothMasAppParams){
        long handle = getMmsMsgHndToID(msgHandle);
        String whereClause = " _id= " + handle;
        Uri uri = Uri.parse("content://mms/");
        if(handle > 0){
            Cursor cr = mContext.getContentResolver().query(uri, null, null, null, null);
            if (cr != null) {
                if (cr.getCount() > 0) {
                    cr.moveToFirst();
                    if (bluetoothMasAppParams.StatusIndicator == 0) {
                        /* Read Status */
                        ContentValues values = new ContentValues();
                        values.put("read", bluetoothMasAppParams.StatusValue);
                        int rowUpdate = mContext.getContentResolver().update(uri,
                                values, whereClause, null);
                        if (V){
                                Log.v(TAG, "\nRows updated => " + Integer.toString(rowUpdate));
                        }
                        return ResponseCodes.OBEX_HTTP_OK;
                    } else {
                        if (bluetoothMasAppParams.StatusValue == 1) {
                            deleteMMS(handle);
                        } else if (bluetoothMasAppParams.StatusValue == 0) {
                            unDeleteMMS(handle);
                        }
                        return ResponseCodes.OBEX_HTTP_OK;
                    }
                }
                cr.close();
            }
        }
        return ResponseCodes.OBEX_HTTP_OK;
    }
}
