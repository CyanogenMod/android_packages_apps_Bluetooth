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

import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.text.format.Time;
import android.util.Log;

import com.android.bluetooth.map.MapUtils.BmessageConsts;
import com.android.bluetooth.map.MapUtils.CommonUtils;
import com.android.bluetooth.map.MapUtils.EmailUtils;
import com.android.bluetooth.map.MapUtils.MapUtils;
import com.android.bluetooth.map.MapUtils.MsgListingConsts;
import com.android.bluetooth.map.MapUtils.SqlHelper;
import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMasMessageListingRsp;
import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMasMessageRsp;
import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMasPushMsgRsp;
import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMsgListRsp;
import com.android.bluetooth.map.MapUtils.MapUtils.BadRequestException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.obex.ResponseCodes;

import static com.android.bluetooth.map.BluetoothMasService.MSG_SERVERSESSION_CLOSE;
import static com.android.bluetooth.map.MapUtils.EmailUtils.TYPE_DELETED;
import static com.android.bluetooth.map.MapUtils.EmailUtils.TYPE_DRAFT;
import static com.android.bluetooth.map.MapUtils.EmailUtils.TYPE_INBOX;
import static com.android.bluetooth.map.MapUtils.EmailUtils.TYPE_OUTBOX;
import static com.android.bluetooth.map.MapUtils.EmailUtils.TYPE_SENT;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.DELETED;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.DRAFT;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.DRAFTS;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.INBOX;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.OUTBOX;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.SENT;

public class BluetoothMasAppEmail extends BluetoothMasAppIf {
    public final String TAG = "BluetoothMasAppEmail";
    public final boolean V = BluetoothMasService.VERBOSE;;

    private ContentObserver mObserver;
    private static final int[] SPECIAL_MAILBOX_TYPES
            = {TYPE_DELETED, TYPE_DRAFT, TYPE_INBOX, TYPE_OUTBOX, TYPE_SENT};
    private static final String[] SPECIAL_MAILBOX_MAP_NAME
            = {DELETED, DRAFT, INBOX, OUTBOX, SENT};
    private HashMap<Integer, String> mSpecialMailboxName = new HashMap<Integer, String>();

    public BluetoothMasAppEmail(Context context, Handler handler, BluetoothMns mnsClient,
            int masId, String remoteDeviceName) {
        super(context, handler, MESSAGE_TYPE_EMAIL, mnsClient, masId, remoteDeviceName);

        mObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                long id = EmailUtils.getAccountId(mMasId);
                if (!EmailUtils.hasEmailAccount(mContext, id)) {
                    // Email account removed, disconnect
                    // TODO: inform the user
                    disconnect();
                }
                super.onChange(selfChange);
            }
        };

        loadSpecialMailboxName();
        if (V) Log.v(TAG, "BluetoothMasAppEmail Constructor called");
    }

    private void loadSpecialMailboxName() {
        mSpecialMailboxName.clear();
        long id = EmailUtils.getAccountId(mMasId);
        final String where = EmailUtils.ACCOUNT_KEY + "=" + id + " AND " + EmailUtils.TYPE + "=";
        String name;
        for (int i = 0; i < SPECIAL_MAILBOX_TYPES.length; i ++) {
            name = SqlHelper.getFirstValueForColumn(mContext, EmailUtils.EMAIL_BOX_URI,
                    EmailUtils.DISPLAY_NAME, where + SPECIAL_MAILBOX_TYPES[i], null);
            if (name.length() > 0) {
                mSpecialMailboxName.put(i, name);
            }
        }
    }

    /**
     * Start an MNS obex client session and push notification whenever available
     */
    public void startMnsSession(BluetoothDevice remoteDevice) {
        if (V) Log.v(TAG, "Start MNS Client");
        mMnsClient.getHandler().obtainMessage(BluetoothMns.MNS_CONNECT, mMasId,
                -1, remoteDevice).sendToTarget();
    }

    /**
     * Stop pushing notifications and disconnect MNS obex session
     */
    public void stopMnsSession(BluetoothDevice remoteDevice) {
        if (V) Log.v(TAG, "Stop MNS Client");
        mMnsClient.getHandler().obtainMessage(BluetoothMns.MNS_DISCONNECT, mMasId,
                -1, remoteDevice).sendToTarget();
    }

    @Override
    protected List<String> getCompleteFolderList() {
        if (V) Log.v(TAG, "getCompleteFolderList");
        long id = EmailUtils.getAccountId(mMasId);
        List<String> list = EmailUtils.getEmailFolderList(mContext, id);
        ArrayList<String> finalList = new ArrayList<String>();
        String name;
        int type;
        int curType;
        for (int i = 0; i < SPECIAL_MAILBOX_TYPES.length; i ++) {
            curType = SPECIAL_MAILBOX_TYPES[i];
            if (V) Log.v(TAG, " getCompleteFolderList: Current Type: " + curType);
            for (String str : list) {
                type = EmailUtils.getTypeForFolder(mContext, id, str);
                if (V) Log.v(TAG, " getCompleteFolderList: type: " + type);
                if (type == curType) {
                    if (V) Log.v(TAG, " getCompleteFolderList: removing folder : " + str);
                    list.remove(str);
                    break;
                }
            }
            if (!list.contains(SPECIAL_MAILBOX_MAP_NAME[i])) {
                if (V) Log.v(TAG, " getCompleteFolderList: adding default folder : "
                    + SPECIAL_MAILBOX_MAP_NAME[i]);
                list.add(SPECIAL_MAILBOX_MAP_NAME[i]);
            }
        }
        for (String str : list) {
            type = EmailUtils.getTypeForFolder(mContext, id, str);
            if (V) Log.v(TAG, " getCompleteFolderList: Processing type: " + type
                + " for Folder : " + str);
            if (type <= ((EmailUtils.TYPE_DELETED) + 1)) {
                if (V) Log.v(TAG, " getCompleteFolderList: Adding a valid folder:" + str);
                finalList.add(str);
            }
        }
        if (V) Log.v(TAG, "Returning from CompleteFolderList");
        return finalList;
    }

    public boolean checkPrecondition() {
        long id = EmailUtils.getAccountId(mMasId);
        if (id == -1) {
            return false;
        }
        return true;
    }

    public void onConnect() {
        if (V) Log.v(TAG, "onConnect() registering email account content observer");
        mContext.getContentResolver().registerContentObserver(
                EmailUtils.EMAIL_ACCOUNT_URI, true, mObserver);
    }

    public void onDisconnect() {
        if (V) Log.v(TAG, "onDisconnect() unregistering email account content observer");
        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    private void disconnect() {
        if (V) Log.v(TAG, "disconnect() sending serversession close.");
        mHandler.obtainMessage(MSG_SERVERSESSION_CLOSE, mMasId, -1).sendToTarget();
    }

    /*
     * Email specific methods
     */
    @Override
    protected BluetoothMsgListRsp msgListingSpecific(List<MsgListingConsts> msgList, String name,
            BluetoothMasMessageListingRsp rsp, BluetoothMasAppParams appParams) {
        BluetoothMsgListRsp bmlr = new BluetoothMsgListRsp();
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
            Log.v(TAG, "Condition result:"+ (appParams.FilterMessageType & 0x04));
        }
        // TODO: Take care of subfolders
        String splitStrings[] = fullPath.split("/");
        if ((splitStrings.length == 3 || splitStrings.length == 4)) {
            // TODO: Take care of subfolders
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

            if (appParams.FilterPriority == 0 || appParams.FilterPriority == 0x02) {
                if((appParams.FilterMessageType & 0x04) == 0) {
                    String folderName;
                    if (splitStrings.length < 3) {
                        Log.e(TAG, "The folder path is invalid.");
                        rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;;
                        bmlr.rsp = rsp;
                        return bmlr;
                    }
                    if (V) Log.v(TAG, "splitStrings[2] = " + splitStrings[2]);
                    // TODO: Take care of subfolders

                    folderName = EmailUtils.getFolderName(splitStrings);
                    int index = 0;
                    long accountId = EmailUtils.getAccountId(mMasId);
                    for (; index < SPECIAL_MAILBOX_MAP_NAME.length; index ++) {
                        if (SPECIAL_MAILBOX_MAP_NAME[index].equalsIgnoreCase(folderName)) {
                            List<String> folders = EmailUtils.getFoldersForType(mContext,
                                    accountId, SPECIAL_MAILBOX_TYPES[index]);
                            List<MsgListingConsts> list = null;
                            for (String folder : folders) {
                                list = getListEmailFromFolder(folder, rsp, appParams);
                                if (list.size() > 0) {
                                    msgList.addAll(list);
                                }
                            }
                            break;
                        }
                    }
                    if (index == SPECIAL_MAILBOX_MAP_NAME.length) {
                        msgList = getListEmailFromFolder(folderName, rsp, appParams);
                    }

                    rsp.rsp = ResponseCodes.OBEX_HTTP_OK;
                    bmlr.messageListingSize = rsp.msgListingSize;
                    bmlr.rsp = rsp;
                    bmlr.msgList = msgList;
                    return bmlr;
                }
                else {
                    if (V) Log.v(TAG, "Invalid Message Filter, returning empty list");
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
        rsp.rsp = ResponseCodes.OBEX_HTTP_OK;
        bmlr.rsp = rsp;
        return bmlr;
    }

    @Override
    protected BluetoothMasMessageRsp getMessageSpecific(long msgHandle,
            BluetoothMasMessageRsp rsp, BluetoothMasAppParams bluetoothMasAppParams) {
        /*
         * Spec 5.6.4 says MSE shall reject request with value native
         * for MMS and Email
         */
        if ((int)bluetoothMasAppParams.Charset == 0) {
            rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            return rsp;
        }

        long emailMsgID = msgHandle - OFFSET_START;
        String str = EmailUtils.bldEmailBmsg(emailMsgID, rsp, mContext, mRemoteDeviceName);
        if (V) Log.v(TAG, "\n" + str + "\n");
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
            } else {
                rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }
        }
        return rsp;
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
        byte[] readBytes = null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            if(file.length() > EMAIL_MAX_PUSHMSG_SIZE){
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
                } catch (IOException ei) {
                    Log.e(TAG, "Error while closing stream"+ ei.toString());
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
        if (type != null && type.equalsIgnoreCase("EMAIL")) {
            rsp = pushMessageEmail(rsp, readStr, name);
            return rsp;
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
        return setMsgStatusEmail(handle, bluetoothMasAppParams);
    }

    /**
     * Sets the message update
     *
     * @return Obex response code
     */
    public int msgUpdate() {
        if (V) Log.v(TAG, "Message Update");
        long accountId = EmailUtils.getAccountId(mMasId);
        if (V) Log.v(TAG, " Account id for Inbox Update: " +accountId);

        Intent emailIn = new Intent();

        emailIn.setAction("com.android.email.intent.action.MAIL_SERVICE_WAKEUP");
        emailIn.putExtra("com.android.email.intent.extra.ACCOUNT", accountId);
        mContext.startService(emailIn);

        return ResponseCodes.OBEX_HTTP_OK;
    }

    /**
     * Adds an Email to the Email ContentProvider
     */
    private String addToEmailFolder(String folder, String address, String text, String subject,
            String OrigEmail, String OrigName) {
        if (V) {
            Log.v(TAG, "-------------");
            Log.v(TAG, "address " + address);
            Log.v(TAG, "TEXT " + text);
        }
        // TODO: need to insert a row in the body table and update the mailbox table
        // with the no of messages unread
        Cursor cr;
        int folderId = -1;
        long accountId = -1;
        Time timeObj = new Time();
        timeObj.setToNow();

        Cursor cr1;
        String whereClause1 = "UPPER(emailAddress) LIKE  '"+OrigEmail.toUpperCase().trim()+"'";
        cr1 = mContext.getContentResolver().query(
                Uri.parse("content://com.android.email.provider/account"),
                null, whereClause1, null, null);
        if (cr1 != null) {
            if (cr1.getCount() > 0) {
                cr1.moveToFirst();
                accountId = cr1.getInt(cr1.getColumnIndex("_id"));
            }
            cr1.close();
        }
        if (accountId == -1) {
            accountId = EmailUtils.getAccountId(mMasId);
        }
        if (DRAFT.equalsIgnoreCase(folder)) {
            List<String> folders = EmailUtils.getFoldersForType(mContext, accountId,
                    EmailUtils.TYPE_DRAFT);
            if (V) Log.v(TAG, "DRAFT folders: " + folders.toString());
            if (folders.size() == 0) {
                // no draft folder
                return INTERNAL_ERROR;
            }
            if (folders.contains(DRAFTS)) {
                folder = DRAFTS;
            } else {
                folder = folders.get(0);
            }
        }

        String whereClause = "UPPER(displayName) = '"+folder.toUpperCase().trim()+"'";
        cr = mContext.getContentResolver().query(
                Uri.parse("content://com.android.email.provider/mailbox"),
                null, whereClause, null, null);
        if (cr != null) {
            if (cr.getCount() > 0) {
                cr.moveToFirst();
                folderId = cr.getInt(cr.getColumnIndex("_id"));
            }
            cr.close();
        }
        if (folderId == -1) {
            return INTERNAL_ERROR;
        }

        if (V){
            Log.v(TAG, "-------------");
            Log.v(TAG, "To address " + address);
            Log.v(TAG, "Text " + text);
            Log.v(TAG, "Originator email address:: " + OrigEmail);
            Log.v(TAG, "Originator email name:: " + OrigName);
            Log.v(TAG, "Time Stamp:: " + timeObj.toMillis(false));
            Log.v(TAG, "Account Key:: " + accountId);
            Log.v(TAG, "Folder Id:: " + folderId);
            Log.v(TAG, "Folder Name:: " + folder);
            Log.v(TAG, "Subject" + subject);
        }
        ContentValues values = new ContentValues();
        values.put("syncServerTimeStamp", 0);
        values.put("syncServerId", "5:65");
        values.put("displayName", OrigName.trim());
        values.put("timeStamp", timeObj.toMillis(false));
        values.put("subject", subject.trim());
        values.put("flagLoaded", "1");
        values.put("flagFavorite", "0");
        values.put("flagAttachment", "0");
        values.put("flags", "0");

        values.put("accountKey", accountId);
        values.put("fromList", OrigEmail.trim());

        values.put("mailboxKey", folderId);
        values.put("toList", address.trim());
        values.put("flagRead", 0);

        Uri uri = mContext.getContentResolver().insert(
                Uri.parse("content://com.android.email.provider/message"), values);
        if (V){
            Log.v(TAG, " NEW URI " + (uri == null ? "null" : uri.toString()));
        }

        if (uri == null) {
            return INTERNAL_ERROR;
        }
        String str = uri.toString();
        String[] splitStr = str.split("/");
        if (splitStr.length < 5) {
            return INTERNAL_ERROR;
        }
        if (V){
            Log.v(TAG, " NEW HANDLE " + splitStr[4]);
        }

        // TODO: need to insert into the body table
        // --seems like body table gets updated automatically
        ContentValues valuesBody = new ContentValues();
        valuesBody.put("messageKey", splitStr[4]);
        valuesBody.put("textContent", text);

        mContext.getContentResolver().insert(
                Uri.parse("content://com.android.email.provider/body"), valuesBody);
        long virtualMsgId;
        virtualMsgId = Long.valueOf(splitStr[4]) + OFFSET_START;
        return Long.toString(virtualMsgId);
    }

    private List<MsgListingConsts> getListEmailFromFolder(String folderName,
            BluetoothMasMessageListingRsp rsp, BluetoothMasAppParams appParams) {
        List<MsgListingConsts> msgList = new ArrayList<MsgListingConsts>();
        String urlEmail = "content://com.android.email.provider/message";
        Uri uriEmail = Uri.parse(urlEmail);
        ContentResolver crEmail = mContext.getContentResolver();

        String whereClauseEmail  = EmailUtils.getConditionString(folderName, mContext, appParams,
                mMasId);

        if (V){
                Log.v(TAG, "## whereClauseEmail ##:" + whereClauseEmail);
        }
        Cursor cursor = crEmail.query(uriEmail, null, whereClauseEmail, null, "timeStamp desc");

        if (cursor != null && V){
                Log.v(TAG, "move to First" + cursor.moveToFirst());
        }
        if (cursor != null && V){
                Log.v(TAG, "move to Liststartoffset"
                    + cursor.moveToPosition(appParams.ListStartOffset));
        }
        if (cursor != null && cursor.moveToFirst()) {
            int idInd = cursor.getColumnIndex("_id");
            int fromIndex = cursor.getColumnIndex("fromList");
            int toIndex = cursor.getColumnIndex("toList");
            int dateInd = cursor.getColumnIndex("timeStamp");
            int readInd = cursor.getColumnIndex("flagRead");
            int subjectInd = cursor.getColumnIndex("subject");
            int replyToInd = cursor.getColumnIndex("replyToList");

            do {
                /*
                 * Apply remaining filters
                 */

                if (V) Log.v(TAG, " msgListSize " + rsp.msgListingSize);
                rsp.msgListingSize++;

                String subject = cursor.getString(subjectInd);
                String timestamp = cursor.getString(dateInd);
                String senderName = cursor.getString(fromIndex);
                String senderAddressing = cursor.getString(fromIndex);
                String recipientName = cursor.getString(toIndex);
                String recipientAddressing = cursor.getString(toIndex);
                String msgId = cursor.getString(idInd);
                String readStatus = cursor.getString(readInd);
                String replyToStr = cursor.getString(replyToInd);

                /*
                 * Don't want the listing; just send the listing size after
                 * applying all the filters.
                 */

                /*
                 * TODO: Skip the first ListStartOffset record(s). Don't write
                 * more than MaxListCount record(s).
                 */

                MsgListingConsts emailMsg = new MsgListingConsts();
                emailMsg = EmailUtils.bldEmailMsgLstItem(mContext, folderName, appParams,
                        subject, timestamp, senderName, senderAddressing,
                        recipientName, recipientAddressing,
                        msgId, readStatus, replyToStr, OFFSET_START);

                // New Message?
                if ((rsp.newMessage == 0) && (cursor.getInt(readInd) == 0)) {
                    rsp.newMessage = 1;
                }
                msgList.add(emailMsg);
            } while (cursor.moveToNext());
        }
        if (cursor != null) {
            cursor.close();
        }
        return msgList;
    }

    private boolean isAllowedEmailFolderForPush(String folderName) {
        if (DRAFT.equalsIgnoreCase(folderName) || OUTBOX.equalsIgnoreCase(folderName)) {
            return true;
        }
        long id = EmailUtils.getAccountId(mMasId);
        int type = EmailUtils.getTypeForFolder(mContext, id, folderName);
        if (type == EmailUtils.TYPE_DRAFT || type == EmailUtils.TYPE_OUTBOX) {
            return true;
        } else {
            return false;
        }
    }

    private BluetoothMasPushMsgRsp pushMessageEmail(BluetoothMasPushMsgRsp rsp,
                String readStr, String name) throws BadRequestException {
        if (V) Log.v(TAG, " Before fromBmessageemail method:: "+readStr);

        BmessageConsts bMsg = MapUtils.fromBmessageEmail(mContext,readStr,mMasId);
        String address = bMsg.getRecipientVcard_email();
        String text = bMsg.getBody_msg();
        String subject = bMsg.getSubject();
        String originator = bMsg.getOriginatorVcard_email();
        String origName = bMsg.getOriginatorVcard_name();

        String fullPath = (name == null || name.length() == 0)
                ? mCurrentPath : mCurrentPath + "/" + name;
        String splitStrings[] = fullPath.split("/");
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
        if (!isAllowedEmailFolderForPush(folderName)) {
            rsp.msgHandle = null;
            rsp.response = ResponseCodes.OBEX_HTTP_FORBIDDEN;
            return rsp;
        }
        String handle = addToEmailFolder(folderName, address, text, subject, originator, origName);
        if (INTERNAL_ERROR == handle) { // == comparison valid here
            rsp.msgHandle = null;
            rsp.response = ResponseCodes.OBEX_HTTP_NOT_FOUND;
            return rsp;
        }
        rsp.msgHandle = handle;
        rsp.response = ResponseCodes.OBEX_HTTP_OK;

        long accountId = EmailUtils.getAccountId(mMasId);
        if (V) Log.v(TAG, " Account id before Mail service:: " + accountId);

        Intent emailIn = new Intent();
        emailIn.setAction("com.android.email.intent.action.MAIL_SERVICE_WAKEUP");
        emailIn.putExtra("com.android.email.intent.extra.ACCOUNT", accountId);
        mContext.startService(emailIn);
        return rsp;
    }

    private int setMsgStatusEmail(long handle,
            BluetoothMasAppParams bluetoothMasAppParams){
        //Query the mailbox table to get the id values for Inbox and Trash folder
        Uri uri1 = Uri.parse("content://com.android.email.provider/mailbox");
        Cursor cr1 = mContext.getContentResolver().query(uri1, null,
                "(UPPER(displayName) = 'INBOX' OR UPPER(displayName) LIKE '%TRASH%')", null, null);
        int inboxFolderId = 0;
        int deletedFolderId = 0;
        int msgFolderId = 0;
        String folderName;
        if (cr1 != null && cr1.moveToFirst()) {
            do {
                folderName = cr1.getString(cr1.getColumnIndex("displayName"));
                if(folderName.equalsIgnoreCase("INBOX")){
                    inboxFolderId = cr1.getInt(cr1.getColumnIndex("_id"));
                } else {
                    deletedFolderId = cr1.getInt(cr1.getColumnIndex("_id"));
                }
            } while (cr1.moveToNext());
        }
        if (cr1 != null) {
            cr1.close();
        }

        //Query the message table for the given message id
        long emailMsgId = handle - 0;
        emailMsgId = handle - OFFSET_START;
        Uri uri2 = Uri.parse("content://com.android.email.provider/message/"+emailMsgId);
        Cursor crEmail = mContext.getContentResolver().query(uri2, null, null, null, null);
        if (crEmail != null && crEmail.moveToFirst()) {
            if (bluetoothMasAppParams.StatusIndicator == 0) {
                /* Read Status */
                ContentValues values = new ContentValues();
                values.put("flagRead", bluetoothMasAppParams.StatusValue);
                mContext.getContentResolver().update(uri2, values, null, null);
            } else {
                if (bluetoothMasAppParams.StatusValue == 1) { //if the email is deleted
                    msgFolderId = crEmail.getInt(crEmail.getColumnIndex("mailboxKey"));
                    if(msgFolderId == deletedFolderId){
                        // TODO: need to add notification for deleted email here
                        mMnsClient.addMceInitiatedOperation(Long.toString(handle));
                        mContext.getContentResolver().delete(
                                Uri.parse("content://com.android.email.provider/message/"
                                + emailMsgId), null, null);
                    } else {
                        ContentValues values = new ContentValues();
                        values.put("mailboxKey", deletedFolderId);
                        mContext.getContentResolver().update(uri2, values, null, null);
                    }
                } else { // if the email is undeleted
                    // TODO: restore it to original folder
                    ContentValues values = new ContentValues();
                    values.put("mailboxKey", inboxFolderId);
                    mContext.getContentResolver().update(uri2, values, null, null);
                }
            }
        }
        if (crEmail != null) {
            crEmail.close();
        }
        return ResponseCodes.OBEX_HTTP_OK;
    }
}
