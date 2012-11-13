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

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.bluetooth.map.MapUtils.MapUtils;
import com.android.bluetooth.map.MapUtils.MsgListingConsts;
import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMasMessageListingRsp;
import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMasMessageRsp;
import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMsgListRsp;
import com.android.bluetooth.map.MapUtils.SmsMmsUtils.VcardContent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.obex.ResponseCodes;

import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.DELETED;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.DRAFT;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.INBOX;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.OUTBOX;
import static com.android.bluetooth.map.MapUtils.SmsMmsUtils.SENT;

/**
 * This class provides the application interface for MAS Server It interacts
 * with the SMS repository using Sms Content Provider to service the MAS
 * requests. It also initializes BluetoothMns thread which is used for MNS
 * connection.
 */

public abstract class BluetoothMasAppIf implements IBluetoothMasApp {
    public static final String TAG = "BluetoothMasAppIf";
    public static final boolean D = BluetoothMasService.DEBUG;
    public static final boolean V = BluetoothMasService.VERBOSE;

    protected static final String INTERNAL_ERROR = "ERROR";

    // IOP work around for BMW carkit
    // The connection is dropped by the carkit When GetMessagesListing results empty list
    // So, we ignore improper filtering request by messageType.
    protected static final String BMW = "BMW";

    protected Context mContext;
    protected int mSupportedMessageTypes;
    protected int mMasId;
    protected String mRemoteDeviceName;

    protected String mCurrentPath = null;
    protected BluetoothMns mMnsClient;
    protected Handler mHandler = null;

    protected final long OFFSET_START;
    protected final long OFFSET_END;

    public BluetoothMasAppIf(Context context, Handler handler, int supportedMessageTypes,
            BluetoothMns mnsClient, int masId, String remoteDeviceName) {
        mContext = context;
        mSupportedMessageTypes = supportedMessageTypes;
        mMasId = masId;
        mHandler = handler;
        mMnsClient = mnsClient;
        mRemoteDeviceName = remoteDeviceName;

        OFFSET_START = HANDLE_OFFSET[masId];
        OFFSET_END = HANDLE_OFFSET[masId + 1] - 1;

        if (V) Log.v(TAG, "Constructor called");
    }

    /**
     * This must be overridden
     * @return folder list for corresponding MAS
     */
    protected abstract List<String> getCompleteFolderList();

    /**
     * Check the path to a given folder. If setPathFlag is set,
     * set the path to the new value. Else, just check if the path
     * exists and don't change the current path.
     *
     * @return true if the path exists, and could be accessed.
     */
    public boolean checkPath(boolean up, String name, boolean setPathFlag) {
        if (V) Log.v(TAG, "setPath called");
        if (V) {
            Log.v(TAG, "mCurrentPath::"+mCurrentPath);
            Log.v(TAG, "name::"+name);
        }
        if (up == false) {
            if (name == null || name.length() == 0) {
                mCurrentPath = (setPathFlag) ? null : mCurrentPath;
                return true;
            }
        } else {
            if (mCurrentPath == null) {
                // Can't go above root
                return false;
            } else {
                int LastIndex;
                if (mCurrentPath.toUpperCase().contains("GMAIL")) {
                    LastIndex = mCurrentPath.lastIndexOf('/');
                    mCurrentPath = mCurrentPath.substring(0, LastIndex);
                    LastIndex = mCurrentPath.lastIndexOf('/');
                } else {
                    LastIndex = mCurrentPath.lastIndexOf('/');
                }
                if (LastIndex < 0) {
                    // Reaches root
                    mCurrentPath = null;
                } else {
                    mCurrentPath = mCurrentPath.substring(0, LastIndex);
                }
            }
            if (name == null || name.length() == 0) {
                // Only going up by one
                return true;
            }
        }

        if (mCurrentPath == null) {
            if (TELECOM.equals(name)) {
                mCurrentPath = (setPathFlag) ? TELECOM : mCurrentPath;
                return true;
            } else {
                return false;
            }
        }

        String splitStrings[] = mCurrentPath.split("/");

        boolean Result = false;
        switch (splitStrings.length) {
        case 1:
            if (name.equals(MSG)) {
                mCurrentPath += (setPathFlag) ? ("/" + name) : "";
                Result = true;
            }
            break;
        case 2:
            List<String> completeFolderList = getCompleteFolderList();
            for (String FolderName : completeFolderList) {
                //added second condition for gmail sent folder
                if (FolderName.equalsIgnoreCase(name)) {
                    mCurrentPath += (setPathFlag) ? ("/" + name) : "";
                    Result = true;
                    break;
                }
            }
            break;
            // TODO: SUBFOLDERS: Add check for sub-folders (add more cases)

        default:
            Result = false;
            break;
        }
        return Result;
    }

    /**
     * Set the path to a given folder.
     *
     * @return true if the path exists, and could be accessed.
     */
    public boolean setPath(boolean up, String name) {
        return checkPath(up, name, true);
    }

    /**
     * Get the number of messages in the folder
     *
     * @return number of messages; -1 if error
     */
    public int folderListingSize() {
        if (V) Log.v(TAG, "folderListingSize called, current path " + mCurrentPath);

        if (mCurrentPath == null) {
            // at root, only telecom folder should be present
            return 1;
        }

        if (mCurrentPath.equals(TELECOM)) {
            // at root -> telecom, only msg folder should be present
            return 1;
        }

        if (mCurrentPath.equals(TELECOM + "/" + MSG)) {
            // at root -> telecom -> msg, FolderList should be present
            List<String> completeFolderList = getCompleteFolderList();
            return completeFolderList.size();
        }
        // TODO: SUBFOLDERS: Add check for sub-folders

        return 0;
    }

    /**
     * Get the XML listing of the folders at CurrenthPath
     *
     * @return XML listing of the folders
     */
    public String folderListing(BluetoothMasAppParams appParam) {
        if (V) Log.v(TAG, "folderListing called, current path " + mCurrentPath);

        List<String> list = new ArrayList<String>();

        if (mCurrentPath == null) {
            // at root, only telecom folder should be present
            if (appParam.ListStartOffset == 0) {
                list.add(TELECOM);
            }
            return MapUtils.folderListingXML(list);
        }

        if (mCurrentPath.equals(TELECOM)) {
            // at root -> telecom, only msg folder should be present
            if (appParam.ListStartOffset == 0) {
                list.add(MSG);
            }
            return MapUtils.folderListingXML(list);
        }

        if (mCurrentPath.equals(TELECOM + "/" + MSG)) {
            int offset = 0;
            int added = 0;
            // at root -> telecom -> msg, FolderList should be present
            List<String> completeFolderList = getCompleteFolderList();
            for (String Folder : completeFolderList) {
                offset++;
                if ((offset > appParam.ListStartOffset)
                        && (added < appParam.MaxListCount)) {
                    list.add(Folder);
                    added++;
                }
            }
            return MapUtils.folderListingXML(list);
        }

        if (mCurrentPath.equals(TELECOM + "/" + MSG + "/" + INBOX) ||
                mCurrentPath.equals(TELECOM + "/" + MSG + "/" + OUTBOX) ||
                mCurrentPath.equals(TELECOM + "/" + MSG + "/" + DRAFT) ||
                mCurrentPath.equals(TELECOM + "/" + MSG + "/" + DELETED) ||
                mCurrentPath.equals(TELECOM + "/" + MSG + "/" + SENT)
        ) {
            return MapUtils.folderListingXML(list);
        } else {
            List<String> completeFolderList = getCompleteFolderList();
            for (String Folder : completeFolderList) {
                if (mCurrentPath.equalsIgnoreCase(TELECOM + "/" + MSG + "/" + Folder)) {
                    return MapUtils.folderListingXML(list);
                }
            }
        }

        return null;
    }

    static final int PHONELOOKUP_ID_COLUMN_INDEX = 0;
    static final int PHONELOOKUP_LOOKUP_KEY_COLUMN_INDEX = 1;
    static final int PHONELOOKUP_DISPLAY_NAME_COLUMN_INDEX = 2;

    static final int EMAIL_DATA_COLUMN_INDEX = 0;

    private List<VcardContent> mVcardList = new ArrayList<VcardContent>();;

    protected VcardContent getVcardContent(String phoneAddress) {
        VcardContent vCard = new VcardContent();
        vCard.tel = phoneAddress;

        Uri uriContacts = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneAddress));
        Cursor cursorContacts = mContext.getContentResolver().query(
                uriContacts,
                new String[] { PhoneLookup._ID, PhoneLookup.LOOKUP_KEY,
                        PhoneLookup.DISPLAY_NAME }, null, null, null);
        if (cursorContacts == null) {
            return vCard;
        }
        cursorContacts.moveToFirst();
        if (cursorContacts.getCount() > 0) {
            long contactId = cursorContacts
                    .getLong(PHONELOOKUP_ID_COLUMN_INDEX);
            String lookupKey = cursorContacts
                    .getString(PHONELOOKUP_LOOKUP_KEY_COLUMN_INDEX);
            vCard.name = cursorContacts
                    .getString(PHONELOOKUP_DISPLAY_NAME_COLUMN_INDEX);

            Uri lookUpUri = Contacts.getLookupUri(contactId, lookupKey);
            String Id = lookUpUri.getLastPathSegment();

            Cursor crEm = mContext.getContentResolver().query(Email.CONTENT_URI,
                    new String[] { Email.DATA }, Email.CONTACT_ID + "=?",
                    new String[] { Id }, null);
            if (crEm != null) {
                if (crEm.moveToFirst()) {
                    vCard.email = "";
                    if (crEm.moveToFirst()) {
                        do {
                            vCard.email += crEm.getString(EMAIL_DATA_COLUMN_INDEX) + ";";
                        } while (crEm.moveToNext());
                    }
                }
                crEm.close();
            }
        }
        cursorContacts.close();
        return vCard;
    }

    /**
     * Check if the entry is not to be filtered out (allowed)
     */
    protected boolean allowEntry(String phoneAddress, String filterString) {
        boolean found = false;
        VcardContent foundEntry = null;
        for (VcardContent elem : mVcardList) {
            if (elem.tel.contains(phoneAddress)) {
                found = true;
                foundEntry = elem;
            }
        }
        if (found == false) {
            VcardContent vCard = getVcardContent(phoneAddress);
            if (vCard != null) {
                mVcardList.add(vCard);
                found = true;
                foundEntry = vCard;
                if (V) {
                    Log.v(TAG, " NEW VCARD ADDED " + vCard.tel + vCard.name
                            + vCard.email);
                }
            } else {
                if (V) Log.v(TAG, "VCARD NOT FOUND ERROR");
            }
        }

        if (found == true) {
            String regExp = filterString.replace("*", ".*[0-9A-Za-z].*");
            if ((foundEntry.tel.matches(".*"+regExp+".*"))
                    || (foundEntry.name.matches(".*"+regExp+".*"))
                    || (foundEntry.email.matches(".*"+regExp+".*"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the contact name for the given phone number
     */
    protected String getContactName(String phoneNumber) {
        boolean found = false;
        VcardContent foundEntry = null;
        if(phoneNumber == null){
            return null;
        }
        for (VcardContent elem : mVcardList) {
            if (elem.tel == null){
                continue;
            }
            if (elem.tel.contains(phoneNumber)) {
                found = true;
                foundEntry = elem;
                break;
            }
        }
        if (found == false) {
            foundEntry = getVcardContent(phoneNumber);
            if (foundEntry != null) {
                mVcardList.add(foundEntry);
                found = true;
            }
        }
        if (found == true) {
            return foundEntry.name;
        }

        return null;
    }

    protected class OwnerInfo {
        public String Name;
        public String Number;
    }

    protected OwnerInfo getOwnerInfo() {
        OwnerInfo info = new OwnerInfo();
        TelephonyManager tm = (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            String localPhoneNum = tm.getLine1Number();
            String localPhoneName = tm.getLine1AlphaTag();

            if (TextUtils.isEmpty(localPhoneNum)) {
                localPhoneNum = "";
            }
            if (TextUtils.isEmpty(localPhoneName)) {
                localPhoneName = mContext.getString(android.R.string.unknownName);
            }
            info.Name = localPhoneName;
            info.Number = localPhoneNum;
        }
        return info;
    }

    private OwnerInfo ownerInfo = null;

    /**
     * Get the owners name
     */
    protected String getOwnerName() {
        if (ownerInfo == null) {
            ownerInfo = getOwnerInfo();
        }
        return ownerInfo.Name;
    }

    /**
     * Get the owners phone number
     */
    protected String getOwnerNumber() {
        if (ownerInfo == null) {
            ownerInfo = getOwnerInfo();
        }
        return ownerInfo.Number;
    }

    /**
     * Get the list of message in the given folder.
     * It must be implemented for MessageType specific
     * @param msgList
     * @param name
     * @param rsp
     * @param appParams
     * @return
     */
    protected abstract BluetoothMsgListRsp msgListingSpecific(List<MsgListingConsts> msgList,
            String name, BluetoothMasMessageListingRsp rsp, BluetoothMasAppParams appParams);

    /**
     * Get the list of messages in the given folder
     *
     * @return Listing of messages in MAP-msg-listing format
     */
    public BluetoothMasMessageListingRsp msgListing(String name, BluetoothMasAppParams appParams) {
        BluetoothMasMessageListingRsp rsp = new BluetoothMasMessageListingRsp();
        boolean fileGenerated = false;
        final String FILENAME = "msglist" + getMasId();

        List<MsgListingConsts> msgList = new ArrayList<MsgListingConsts>();

        if (appParams == null) {
            return null;
        }

        BluetoothMsgListRsp specificRsp = msgListingSpecific(msgList, name, rsp, appParams);
        rsp = specificRsp.rsp;

        if (rsp.rsp != ResponseCodes.OBEX_HTTP_OK) {
            return rsp;
        }
        msgList = specificRsp.msgList;
        // Process the list based on MaxListCount and list offset
        String str = null;
        int numOfItems = msgList.size();
        int msgDelta = numOfItems - appParams.ListStartOffset;
        int startIdx = appParams.ListStartOffset;
        int stopIdx = 0;
        if (msgDelta <= 0) {
            List<MsgListingConsts> msgSubList = new ArrayList<MsgListingConsts>();;
            str = MapUtils.messageListingXML(msgSubList);
        } else {
            if (msgDelta <= appParams.MaxListCount) {
                stopIdx = startIdx + msgDelta;
            } else {
                stopIdx = startIdx + appParams.MaxListCount;
            }
            List<MsgListingConsts> msgSubList = msgList.subList(startIdx,
                    stopIdx);
            str = MapUtils.messageListingXML(msgSubList);
        }
        if (str == null) {
            rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            return rsp;
        }

        // String str = "this is a test for the data file";
        try {
            FileOutputStream bos = mContext.openFileOutput(FILENAME, Context.MODE_PRIVATE);
            bos.write(str.getBytes());
            bos.flush();
            bos.close();
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        msgList.clear();

        if (V) {
            Log.v(TAG, "");
            Log.v(TAG, " MESSAGE LISTING FULL ( total length)" + str.length());
            Log.v(TAG, str);
        }

        try {
            FileInputStream fis = new FileInputStream(mContext.getFilesDir()
                    + "/" + FILENAME);
            fis.close();
            fileGenerated = true;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (fileGenerated == true) {
            File file = new File(mContext.getFilesDir() + "/" + FILENAME);
            rsp.file = file;
        }
        rsp.rsp = ResponseCodes.OBEX_HTTP_OK;
        return rsp;
    }

    protected abstract BluetoothMasMessageRsp getMessageSpecific(long msgHandle,
            BluetoothMasMessageRsp rsp, BluetoothMasAppParams bluetoothMasAppParams);

    /**
     * Get the message for the given message handle
     *
     * @return BMSG object
     */
    public BluetoothMasMessageRsp msg(String msgHandle,
            BluetoothMasAppParams bluetoothMasAppParams) {
        BluetoothMasMessageRsp rsp = new BluetoothMasMessageRsp();
        if (msgHandle == null || msgHandle.length() == 0) {
            rsp.file = null;
            rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            return rsp;
        }
        final long handle = Long.valueOf(msgHandle);
        if (handle < OFFSET_START && handle > OFFSET_END) {
            rsp.rsp = ResponseCodes.OBEX_HTTP_NOT_FOUND;
            return rsp;
        }

        return getMessageSpecific(handle, rsp, bluetoothMasAppParams);
    }

    /**
     * Enable/disable notification
     *
     * @return Obex response code
     */
    public int notification(BluetoothDevice remoteDevice,
            BluetoothMasAppParams bluetoothMasAppParams) {
        if (bluetoothMasAppParams.Notification == 1) {
            startMnsSession(remoteDevice);
            return ResponseCodes.OBEX_HTTP_OK;
        } else if (bluetoothMasAppParams.Notification == 0) {
            stopMnsSession(remoteDevice);
            return ResponseCodes.OBEX_HTTP_OK;
        }

        return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
    }

    /**
     * Start an MNS obex client session and push notification whenever available
     */
    public abstract void startMnsSession(BluetoothDevice remoteDevice);

    /**
     * Stop pushing notifications and disconnect MNS obex session
     */
    public abstract void stopMnsSession(BluetoothDevice remoteDevice);

    public int getMasId() {
        return mMasId;
    }
}
