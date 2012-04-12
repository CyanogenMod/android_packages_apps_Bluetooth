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

import com.android.bluetooth.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.app.Activity;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Log;
import android.util.TimeFormatException;

import com.android.bluetooth.map.MapUtils.BmessageConsts;
import com.android.bluetooth.map.MapUtils.MapUtils;
import com.android.bluetooth.map.MapUtils.MsgListingConsts;
import com.android.bluetooth.map.MapUtils.SmsMmsUtils;
import com.android.bluetooth.map.MapUtils.SortMsgListByDate;
import com.android.bluetooth.map.MapUtils.EmailUtils;
import com.android.bluetooth.map.MapUtils.CommonUtils;


import javax.obex.*;

/**
 * This class provides the application interface for MAS Server It interacts
 * with the SMS repository using Sms Content Provider to service the MAS
 * requests. It also initializes BluetoothMns thread which is used for MNS
 * connection.
 */

public class BluetoothMasAppIf {

    public Context context;
    public String mode;
    public final String TAG = "BluetoothMasAppIf";

    public static final int BIT_SUBJECT = 0x1;
    public static final int BIT_DATETIME = 0x2;
    public static final int BIT_SENDER_NAME = 0x4;
    public static final int BIT_SENDER_ADDRESSING = 0x8;

    public static final int BIT_RECIPIENT_NAME = 0x10;
    public static final int BIT_RECIPIENT_ADDRESSING = 0x20;
    public static final int BIT_TYPE = 0x40;
    public static final int BIT_SIZE = 0x80;

    public static final int BIT_RECEPTION_STATUS = 0x100;
    public static final int BIT_TEXT = 0x200;
    public static final int BIT_ATTACHMENT_SIZE = 0x400;
    public static final int BIT_PRIORITY = 0x800;

    public static final int BIT_READ = 0x1000;
    public static final int BIT_SENT = 0x2000;
    public static final int BIT_PROTECTED = 0x4000;
    public static final int BIT_REPLYTO_ADDRESSING = 0x8000;

    public static final int MMS_HDLR_CONSTANT = 100000;
    public static final int EMAIL_HDLR_CONSTANT = 200000;

    private final String RootPath = "root";

    private String CurrentPath = null;

    private final String Telecom = "telecom";
    private final String Msg = "msg";

    private final String Inbox = "inbox";
    private final String Outbox = "outbox";
    private final String Sent = "sent";
    private final String Deleted = "deleted";
    private final String Draft = "draft";
    private final String Drafts = "drafts";
    private final String Undelivered = "undelivered";
    private final String Failed = "failed";
    private final String Queued = "queued";

    private final int DELETED_THREAD_ID = -1;

    private final boolean mnsServiceEnabled = false;


    // root -> telecom -> msg -> (FolderList) includes inbox, outbox, sent, etc
    // For SMS/MMS FolderList[] = { Inbox, Outbox, Sent, Deleted, Draft };

    //added email
    List<String> folderList = new ArrayList<String>();
    //end email

    private final MapUtils mu;

    private final BluetoothMns mnsClient;

    public BluetoothMasAppIf(Context context, String mode) {
        this.context = context;
        this.mode = mode;
        mu = new MapUtils();
        mnsClient = new BluetoothMns(context);

        // Clear out deleted items from database
        clearDeletedItems();

        SmsMmsUtils smu = new SmsMmsUtils();
        folderList = smu.folderListSmsMms(folderList);

        Log.d(TAG, "Constructor called");
    }

    /**
     * Check the path to a given folder. If setPathFlag is set,
     * set the path to the new value. Else, just check if the path
     * exists and don't change the current path.
     *
     * @return true if the path exists, and could be accessed.
     */
    public boolean checkPath(boolean up, String name, boolean setPathFlag) {
        Log.d(TAG, "setPath called");
        /*
         * /* Up and empty string  cd .. Up and name - cd ../name Down and name
         * - cd name Down and empty string  cd to root
         */

        List<String> completeFolderList = new ArrayList<String>();
        EmailUtils eu = new EmailUtils();

        if(mode !=null && (mode.equalsIgnoreCase("SMS")
                || mode.equalsIgnoreCase("SMS_MMS")
                || mode.equalsIgnoreCase("SMS_MMS_EMAIL"))){
            completeFolderList = folderList;
        }
        if(mode !=null && (mode.equalsIgnoreCase("SMS_MMS_EMAIL")
                || mode.equalsIgnoreCase("EMAIL"))){
            completeFolderList = eu.folderListEmail(folderList, context);
        }

        Log.d(TAG, "CurrentPath::"+CurrentPath);
        Log.d(TAG, "name::"+name);
        if ((up == false)) {
            if(name == null) {
                CurrentPath = (setPathFlag) ? null : CurrentPath;
                return true;
            } else if(name.equals("")){
                CurrentPath = (setPathFlag) ? null : CurrentPath;
                return true;
            }
        }

        if (up == true) {
            if (CurrentPath == null) {
                // Can't go above root
                return false;
            } else {
                int LastIndex = CurrentPath.lastIndexOf('/');
                if (LastIndex < 0) {
                    // Reaches root
                    CurrentPath = null;
                } else {
                    CurrentPath = CurrentPath.substring(0, LastIndex);
                }
            }
            if (name == null) {
                // Only going up by one
                return true;
            }
        }

        if (CurrentPath == null) {
            if (name.equals(Telecom)) {
                CurrentPath = (setPathFlag) ? Telecom : CurrentPath;
                return true;
            } else {
                return false;
            }
        }

        String splitStrings[] = CurrentPath.split("/");

        boolean Result = false;
        switch (splitStrings.length) {
        case 1:
            if (name.equals(Msg)) {
                CurrentPath += (setPathFlag) ? ("/" + name) : "";
                Result = true;
            }
            break;
        case 2:
            for (String FolderName : completeFolderList) {
                if(FolderName.equalsIgnoreCase(name)
                        || FolderName.toUpperCase().contains(name.toUpperCase())){
                    //added second condition for gmail sent folder
                    CurrentPath += (setPathFlag) ? ("/" + name) : "";
                    Result = true;
                    break;
                }
            }
            break;
            // TODO SUBFOLDERS: Add check for sub-folders (add more cases)

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
        Log.d(TAG, "folderListingSize called, current path " + CurrentPath);

        List<String> completeFolderList = new ArrayList<String>();
        EmailUtils eu = new EmailUtils();

        if(mode !=null && (mode.equalsIgnoreCase("SMS")
                || mode.equalsIgnoreCase("SMS_MMS")
                || mode.equalsIgnoreCase("SMS_MMS_EMAIL"))){
            completeFolderList = folderList;
        }
        if(mode !=null && (mode.equalsIgnoreCase("SMS_MMS_EMAIL")
                || mode.equalsIgnoreCase("EMAIL"))){
            completeFolderList = eu.folderListEmail(folderList, context);
        }

        if (CurrentPath == null) {
            // at root, only telecom folder should be present
            return 1;
        }

        if (CurrentPath.equals(Telecom)) {
            // at root -> telecom, only msg folder should be present
            return 1;
        }

        if (CurrentPath.equals(Telecom + "/" + Msg)) {
            // at root -> telecom -> msg, FolderList should be present
            return completeFolderList.size();
        }
        // TODO SUBFOLDERS: Add check for sub-folders

        return 0;
    }

    /**
     * Get the XML listing of the folders at CurrenthPath
     *
     * @return XML listing of the folders
     */
    public String folderListing(BluetoothMasAppParams appParam) {
        Log.d(TAG, "folderListing called, current path " + CurrentPath);

        List<String> list = new ArrayList<String>();

        List<String> completeFolderList = new ArrayList<String>();
        EmailUtils eu = new EmailUtils();
        if(mode !=null && (mode.equalsIgnoreCase("SMS")
                || mode.equalsIgnoreCase("SMS_MMS")
                || mode.equalsIgnoreCase("SMS_MMS_EMAIL"))){
            completeFolderList = folderList;
        }
        if(mode !=null && (mode.equalsIgnoreCase("SMS_MMS_EMAIL")
                || mode.equalsIgnoreCase("EMAIL"))){
            completeFolderList = eu.folderListEmail(folderList, context);
        }

        if (CurrentPath == null) {
            // at root, only telecom folder should be present
            if (appParam.ListStartOffset == 0) {
                list.add(Telecom);
            }
            return mu.folderListingXML(list);
        }

        if (CurrentPath.equals(Telecom)) {
            // at root -> telecom, only msg folder should be present
            if (appParam.ListStartOffset == 0) {
                list.add(Msg);
            }
            return mu.folderListingXML(list);
        }

        if (CurrentPath.equals(Telecom + "/" + Msg)) {
            int offset = 0;
            int added = 0;
            // at root -> telecom -> msg, FolderList should be present
            for (String Folder : completeFolderList) {
                offset++;
                if ((offset > appParam.ListStartOffset)
                        && (added < appParam.MaxListCount)) {
                    list.add(Folder);
                    added++;
                }
            }
            return mu.folderListingXML(list);
        }

        if (CurrentPath.equals(Telecom + "/" + Msg + "/" + Inbox) ||
                CurrentPath.equals(Telecom + "/" + Msg + "/" + Outbox) ||
                CurrentPath.equals(Telecom + "/" + Msg + "/" + Draft) ||
                CurrentPath.equals(Telecom + "/" + Msg + "/" + Deleted) ||
                CurrentPath.equals(Telecom + "/" + Msg + "/" + Sent)
        ) {
            return mu.folderListingXML(list);
        }

        return null;
    }

    /**
     * Append child folder to the CurrentPath
     */
    private String getFullPath(String child) {

        String tempPath = null;
        List<String> completeFolderList = new ArrayList<String>();
        EmailUtils eu = new EmailUtils();
        completeFolderList = eu.folderListEmail(folderList, context);

        if (child != null) {
            if (CurrentPath == null) {
                if (child.equals("telecom")) {
                    // Telecom is fine
                    tempPath = new String("telecom");
                }
            } else if (CurrentPath.equals("telecom")) {
                if (child.equals("msg")) {
                    tempPath = CurrentPath + "/" + child;
                }
            } else if (CurrentPath.equals("telecom/msg")) {
                for (String Folder : completeFolderList) { //TODO NEED TO LOOK INTO THIS
                    if (child.equalsIgnoreCase(Folder)) {
                        tempPath = CurrentPath + "/" + Folder;
                    }
                }
            }
        }
        return tempPath;
    }

    public class BluetoothMasMessageListingRsp {
        public File file = null;
        public int msgListingSize = 0;
        public byte newMessage = 0;
        public int rsp = ResponseCodes.OBEX_HTTP_OK;
    }

    private class VcardContent {
        public String name = "";
        public String tel = "";
        public String email = "";
    }

    static final int PHONELOOKUP_ID_COLUMN_INDEX = 0;
    static final int PHONELOOKUP_LOOKUP_KEY_COLUMN_INDEX = 1;
    static final int PHONELOOKUP_DISPLAY_NAME_COLUMN_INDEX = 2;

    static final int EMAIL_DATA_COLUMN_INDEX = 0;

    private List<VcardContent> list;

    private VcardContent getVcardContent(String phoneAddress) {

        VcardContent vCard = new VcardContent();
        vCard.tel = phoneAddress;

        Uri uriContacts = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneAddress));
        Cursor cursorContacts = context.getContentResolver().query(
                uriContacts,
                new String[] { PhoneLookup._ID, PhoneLookup.LOOKUP_KEY,
                        PhoneLookup.DISPLAY_NAME }, null, null, null);

        cursorContacts.moveToFirst();

        if (cursorContacts.getCount() > 0) {
            long contactId = cursorContacts
            .getLong(PHONELOOKUP_ID_COLUMN_INDEX);
            String lookupKey = cursorContacts
            .getString(PHONELOOKUP_LOOKUP_KEY_COLUMN_INDEX);

            Uri lookUpUri = Contacts.getLookupUri(contactId, lookupKey);
            String Id = lookUpUri.getLastPathSegment();

            Cursor crEm = context.getContentResolver().query(Email.CONTENT_URI,
                    new String[] { Email.DATA }, Email.CONTACT_ID + "=?",
                    new String[] { Id }, null);
            crEm.moveToFirst();

            vCard.name = cursorContacts
            .getString(PHONELOOKUP_DISPLAY_NAME_COLUMN_INDEX);

            vCard.email = "";
            if (crEm.moveToFirst()) {
                do {
                    vCard.email += crEm.getString(EMAIL_DATA_COLUMN_INDEX)
                    + ";";
                } while (crEm.moveToNext());
            }
        }
        return vCard;
    }

    /**
     * Check if the entry is not to be filtered out (allowed)
     */
    private boolean allowEntry(String phoneAddress, String filterString) {

        boolean found = false;
        VcardContent foundEntry = null;
        for (VcardContent elem : list) {
            if (elem.tel.contains(phoneAddress)) {
                found = true;
                foundEntry = elem;
            }
        }
        if (found == false) {
            VcardContent vCard = getVcardContent(phoneAddress);
            if (vCard != null) {
                list.add(vCard);
                found = true;
                foundEntry = vCard;
                Log.d(TAG, " NEW VCARD ADDED " + vCard.tel + vCard.name
                        + vCard.email);
            } else {
                Log.d(TAG, "VCARD NOT FOUND ERROR");
            }
        }

        if (found == true) {
            if ((foundEntry.tel.contains(filterString))
                    || (foundEntry.name.contains(filterString))
                    || (foundEntry.email.contains(filterString))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the contact name for the given phone number
     */
    private String getContactName(String phoneNumber) {
        // TODO Optimize this (get from list)

        boolean found = false;
        VcardContent foundEntry = null;
        if(phoneNumber == null){
            return null;
        }
        for (VcardContent elem : list) {
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
                list.add(foundEntry);
                found = true;
            }
        }
        if (found == true) {
            return foundEntry.name;
        }

        return null;
    }

    private class OwnerInfo {
        public String Name;
        public String Number;
    }

    private OwnerInfo getOwnerInfo() {
        OwnerInfo info = new OwnerInfo();
        TelephonyManager tm = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            String sLocalPhoneNum = tm.getLine1Number();
            String sLocalPhoneName = "";

            Method m;
            try {
                m = tm.getClass().getMethod("getLine1AlphaTag", new Class[] { });
                sLocalPhoneName = (String) m.invoke(tm);
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

            if (TextUtils.isEmpty(sLocalPhoneNum)) {
                sLocalPhoneNum = "0000000000";
            }
            if (TextUtils.isEmpty(sLocalPhoneName)) {
                sLocalPhoneName = "QCOM";
            }
            info.Name = sLocalPhoneName;
            info.Number = sLocalPhoneNum;
        }
        return info;
    }

    private OwnerInfo ownerInfo = null;



    /**
     * Get the owners name
     */
    public String getOwnerName() {
        // TODO
        if ( ownerInfo == null ) {
            ownerInfo = getOwnerInfo();
        }
        return ownerInfo.Name;
    }

    /**
     * Get the owners phone number
     */
    public String getOwnerNumber() {
        // TODO
        if ( ownerInfo == null ) {
            ownerInfo = getOwnerInfo();
        }
        return ownerInfo.Number;
    }

    private boolean isOutgoingSMSMessage(int type) {
        if (type == 1) {
            return false;
        }
        return true;
    }

    /**
     * Get the list of messages in the given folder
     *
     * @return Listing of messages in MAP-msg-listing format
     */
    public BluetoothMasMessageListingRsp msgListing(String name,
            BluetoothMasAppParams appParams) {
        // TODO Auto-generated method stub

        BluetoothMasMessageListingRsp rsp = new BluetoothMasMessageListingRsp();
        CommonUtils cu = new CommonUtils();
        boolean fileGenerated = false;

        // TODO Do this based on the MasInstance
        final String FILENAME = "msglist";

        int writeCount = 0;
        int processCount = 0;
        int messageListingSize = 0;

        List<MsgListingConsts> msgList = new ArrayList<MsgListingConsts>();

        if (appParams == null) {
            return null;
        }

        String tempPath = null;
        if (name != null) {
            tempPath = cu.getFullPath(name, context, folderList, CurrentPath);
            if (tempPath == null) {
                // Child folder not present
                rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
                return rsp;
            }
        } else {
            tempPath = CurrentPath;
        }

        FileOutputStream bos = null;

        try {
            bos = context.openFileOutput(FILENAME, Context.MODE_PRIVATE);
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        Log.d(TAG, "appParams.FilterMessageType ::"+ appParams.FilterMessageType);
        Log.d(TAG, "Condition result::"+ (appParams.FilterMessageType & 0x09));
        // TODO: Take care of subfolders
        // TODO: Check only for SMS_GSM
        // Look for messages only if both SMS/MMS are not filtered out
        if ((tempPath != null) && (tempPath.split("/").length == 3 || tempPath.contains("Gmail")
                || tempPath.split("/").length == 4) && ((appParams.FilterMessageType & 0x13) != 0x13)){

            String splitStrings[] = tempPath.split("/");

            // TODO: Take care of subfolders

            Log.d(TAG, "splitString[2] = " + splitStrings[2]);

            // TODO Assuming only for SMS.
            String url = "content://sms/";
            Uri uri = Uri.parse(url);
            ContentResolver cr = context.getContentResolver();
            SmsMmsUtils smu = new SmsMmsUtils();

            String whereClause  = smu.getConditionStringSms(splitStrings[2], appParams);

            if (appParams.FilterReadStatus > 0x02) {
                rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
                return rsp;
            }

            // TODO Filter priority?
            /*
             * There is no support for FilterPriority in SMS/MMS. So, we will
             * assume Filter Priority is always non-high which makes sense for
             * SMS/MMS.We check the content provider only if the Filter Priority
             * is "unfiltered" or "non-high". Else, simply return an empty
             * string. If the Filter priority is greater than 2, return a bad
             * request.
             */
            if (appParams.FilterPriority == 0
                    || appParams.FilterPriority == 0x02) {
                if(mode !=null && (mode.equalsIgnoreCase("SMS") || mode.equalsIgnoreCase("SMS_MMS") || mode.equalsIgnoreCase("SMS_MMS_EMAIL"))){
                    if ((appParams.FilterMessageType & 0x01) == 0) {
                        Cursor cursor = cr.query(uri, null, whereClause, null,
                        "date desc");

                        int idInd = cursor.getColumnIndex("_id");
                        int addressInd = cursor.getColumnIndex("address");
                        int personInd = cursor.getColumnIndex("person");
                        int dateInd = cursor.getColumnIndex("date");
                        int readInd = cursor.getColumnIndex("read");
                        int statusInd = cursor.getColumnIndex("status");
                        int subjectInd = cursor.getColumnIndex("subject");
                        int typeInd = cursor.getColumnIndex("type");
                        int bodyInd = cursor.getColumnIndex("body");

                        Log.d(TAG, "move to First" + cursor.moveToFirst());
                        Log.d(TAG,
                                "move to Liststartoffset"
                                + cursor.moveToPosition(appParams.ListStartOffset));

                        this.list = new ArrayList<VcardContent>();

                        if (cursor.moveToFirst()) {

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
                                String onumber = getOwnerNumber();

                                int msgType = cursor.getInt(typeInd);

                                // TODO Filter Recipient
                                if (isOutgoingSMSMessage(msgType) == true) {
                                    if ((appParams.FilterOriginator != null)
                                            && (appParams.FilterOriginator.length() != 0)
                                            && ((appParams.FilterOriginator
                                                    .compareTo(oname) != 0) && (appParams.FilterOriginator
                                                            .compareTo(onumber) != 0))) {
                                        continue;
                                    }
                                    if ((appParams.FilterRecipient != null)
                                            && (appParams.FilterRecipient.length() != 0)) {
                                        filterString = appParams.FilterRecipient;
                                        Log.d(TAG, "appParams.FilterRecipient"
                                                + appParams.FilterRecipient);
                                    }
                                }
                                if (isOutgoingSMSMessage(msgType) == false) {
                                    if ((appParams.FilterRecipient != null)
                                            && (appParams.FilterRecipient.length() != 0)
                                            && ((appParams.FilterRecipient
                                                    .compareTo(oname) != 0) && (appParams.FilterRecipient
                                                            .compareTo(onumber) != 0))) {
                                        continue;
                                    }
                                    if ((appParams.FilterOriginator != null)
                                            && (appParams.FilterOriginator.length() != 0)) {
                                        filterString = appParams.FilterOriginator;
                                        Log.d(TAG, "appParams.FilterOriginator"
                                                + appParams.FilterOriginator);
                                    }
                                }

                                if (filterString != null) {
                                    Log.d(TAG, "filterString = " + filterString);
                                    if (allowEntry(cursor.getString(addressInd),
                                            filterString) == true) {
                                        Log.d(TAG,
                                                "+++ ALLOWED +++++++++ "
                                                + cursor.getString(addressInd)
                                                + " - " + cursor.getPosition());
                                    } else {
                                        Log.d(TAG,
                                                "+++ DENIED +++++++++ "
                                                + cursor.getString(addressInd)
                                                + " - " + cursor.getPosition());
                                        continue;
                                    }
                                }
                                Log.d(TAG, " msgListSize " + messageListingSize
                                        + "write count " + writeCount);

                                messageListingSize++;

                                /*
                                 * Don't want the listing; just send the listing size
                                 * after applying all the filters.
                                 */
                                if (appParams.MaxListCount == 0) {
                                    continue;
                                }

                                processCount++;

                                MsgListingConsts ml = new MsgListingConsts();
                                ml.setMsg_handle(Integer.valueOf(cursor
                                        .getString(idInd)));

                                Time time = new Time();
                                time.set(Long.valueOf(cursor.getString(dateInd)));

                                String datetimeStr = time.toString().substring(0, 15);

                                ml.msgInfo.setDateTime(datetimeStr);

                                if ((appParams.ParameterMask & BIT_SUBJECT) != 0) {
                                    /* SMS doesn't have subject. Append Body
                                     * so that remote client doesn't have to do
                                     * GetMessage
                                     */
                                    ml.setSendSubject(true);
                                    String subject = cursor.getString(bodyInd);
                                    if (subject != null && subject.length() > appParams.SubjectLength ) {
                                        subject = subject.substring(0,
                                                appParams.SubjectLength);
                                    }
                                    ml.setSubject(subject);
                                }

                                if ((appParams.ParameterMask & BIT_DATETIME) != 0) {
                                    ml.setDatetime(datetimeStr);
                                }

                                if ((appParams.ParameterMask & BIT_SENDER_NAME) != 0) {
                                    // TODO Query the Contacts database
                                    String senderName = null;
                                    if (isOutgoingSMSMessage(msgType) == true) {
                                        senderName = getOwnerName();
                                    } else {
                                        senderName = getContactName(cursor
                                                .getString(addressInd));
                                    }
                                    ml.setSender_name(senderName);
                                }

                                if ((appParams.ParameterMask & BIT_SENDER_ADDRESSING) != 0) {
                                    // TODO In case of a SMS this is
                                    // the sender's phone number in canonical form
                                    // (chapter
                                    // 2.4.1 of [5]).
                                    String senderAddressing = null;
                                    if (isOutgoingSMSMessage(msgType) == true) {
                                        senderAddressing = getOwnerNumber();
                                    } else {
                                        senderAddressing = cursor.getString(addressInd);
                                    }
                                    ml.setSender_addressing(senderAddressing);
                                }

                                if ((appParams.ParameterMask & BIT_RECIPIENT_NAME) != 0) {
                                    // TODO "recipient_name" is the name of the
                                    // recipient of
                                    // the message, when it is known
                                    // by the MSE device.
                                    String recipientName = null;
                                    if (isOutgoingSMSMessage(msgType) == false) {
                                        recipientName = getOwnerName();
                                    } else {
                                        recipientName = getContactName(cursor
                                                .getString(addressInd));
                                    }
                                    ml.setRecepient_name(recipientName);
                                }

                                if ((appParams.ParameterMask & BIT_RECIPIENT_ADDRESSING) != 0) {
                                    // TODO In case of a SMS this is the recipient's
                                    // phone
                                    // number in canonical form (chapter 2.4.1 of [5])
                                    String recipientAddressing = null;
                                    if (isOutgoingSMSMessage(msgType) == false) {
                                        recipientAddressing = getOwnerNumber();
                                    } else {
                                        recipientAddressing = cursor
                                        .getString(addressInd);
                                    }
                                    ml.setRecepient_addressing(recipientAddressing);
                                    ml.setSendRecipient_addressing(true);
                                }

                                if ((appParams.ParameterMask & BIT_TYPE) != 0) {
                                    // TODO GSM or CDMA SMS?
                                    ml.setType("SMS_GSM");
                                }

                                if ((appParams.ParameterMask & BIT_SIZE) != 0) {
                                    ml.setSize(cursor.getString(bodyInd).length());
                                }

                                if ((appParams.ParameterMask & BIT_RECEPTION_STATUS) != 0) {
                                    ml.setReception_status("complete");
                                }

                                if ((appParams.ParameterMask & BIT_TEXT) != 0) {
                                    // TODO Set text to "yes"
                                    ml.setContains_text("yes");
                                }

                                if ((appParams.ParameterMask & BIT_ATTACHMENT_SIZE) != 0) {
                                    ml.setAttachment_size(0);
                                }

                                if ((appParams.ParameterMask & BIT_PRIORITY) != 0) {
                                    // TODO Get correct priority
                                    ml.setPriority("no");
                                }

                                if ((appParams.ParameterMask & BIT_READ) != 0) {
                                    if (cursor.getString(readInd).equalsIgnoreCase("1")) {
                                        ml.setRead("yes");
                                    } else {
                                        ml.setRead("no");
                                    }
                                }

                                if ((appParams.ParameterMask & BIT_SENT) != 0) {
                                    // TODO Get sent status?
                                    if (cursor.getInt(typeInd) == 2) {
                                        ml.setSent("yes");
                                    } else {
                                        ml.setSent("no");
                                    }
                                }

                                if ((appParams.ParameterMask & BIT_PROTECTED) != 0) {
                                    ml.setMsg_protected("no");
                                }

                                // TODO replyto_addressing is used only for email

                                // New Message?
                                if ((rsp.newMessage == 0)
                                        && (cursor.getInt(readInd) == 0)) {
                                    rsp.newMessage = 1;
                                }

                                msgList.add(ml);
                                writeCount++;
                            } while (cursor.moveToNext());

                            cursor.close();
                        }
                    }
                }
                if(mode !=null && (mode.equalsIgnoreCase("SMS_MMS") || mode.equalsIgnoreCase("SMS_MMS_EMAIL"))){
                    // Now that all of the SMS messages have been listed. Look for
                    // any
                    // MMS messages and provide them
                    if((appParams.FilterMessageType & 0x08) == 0) {

                        String splitStringsMms[] = tempPath.split("/");
                        name = splitStringsMms[2];

                        // MMS draft folder is called //mms/drafts not //mms/draft like
                        // SMS
                        if (name.equalsIgnoreCase("draft")) {
                            name = "drafts";
                        }

                        if (getNumMmsMsgs(name) != 0) {
                            MsgListingConsts mmsl = new MsgListingConsts();
                            List<Integer> list = getMmsMsgMIDs(bldMmsWhereClause(
                                    appParams, getMMSFolderType(name)));
                            for (int msgId : list) {
                                Log.d(TAG, "\n MMS Text message ==> "
                                        + getMmsMsgTxt(msgId));
                                Log.d(TAG, "\n MMS message subject ==> "
                                        + getMmsMsgSubject(msgId));

                                String datetime = getMmsMsgDate(msgId);
                                Time time = new Time();
                                Date dt = new Date(Long.valueOf(datetime));
                                time.set((dt.getTime() * 1000));

                                String datetimeStr = time.toString().substring(0, 15);

                                mmsl = bldMmsMsgLstItem(msgId, appParams, name, datetimeStr);
                                mmsl.msgInfo.setDateTime(datetimeStr);

                                if ((rsp.newMessage == 0)
                                        && (getMmsMsgReadStatus(msgId).equalsIgnoreCase("no"))) {
                                    rsp.newMessage = 1;
                                }

                                msgList.add(mmsl);
                                messageListingSize++;
                            }
                        }
                    }
                }
                if(mode !=null && (mode.equalsIgnoreCase("EMAIL")
                        || mode.equalsIgnoreCase("SMS_MMS_EMAIL"))){
                    //Email messages
                    if((appParams.FilterMessageType & 0x04) == 0){
                        EmailUtils eu = new EmailUtils();
                        String folderName;

                        String splitStringsEmail[] = tempPath.split("/");
                        Log.d(TAG, "splitStringsEmail[2] = " + splitStringsEmail[2]);
                        // TODO: Take care of subfolders

                        folderName = eu.getFolderName(splitStringsEmail);
                        if(folderName != null && folderName.equalsIgnoreCase("draft")){
                            folderName = "Drafts";
                        }

                        String urlEmail = "content://com.android.email.provider/message";
                        Uri uriEmail = Uri.parse(urlEmail);
                        ContentResolver crEmail = context.getContentResolver();

                        String whereClauseEmail  = eu.getConditionString(folderName, context, appParams);

                        Log.d(TAG, "## whereClauseEmail ##:" + whereClauseEmail);
                        Cursor cursor = crEmail.query(uriEmail, null, whereClauseEmail, null, "timeStamp desc");

                        int idInd = cursor.getColumnIndex("_id");
                        int fromIndex = cursor.getColumnIndex("fromList");
                        int toIndex = cursor.getColumnIndex("toList");
                        int dateInd = cursor.getColumnIndex("timeStamp");
                        int readInd = cursor.getColumnIndex("flagRead");
                        int subjectInd = cursor.getColumnIndex("subject");
                        int replyToInd = cursor.getColumnIndex("replyToList");

                        Log.d(TAG, "move to First" + cursor.moveToFirst());
                        Log.d(TAG, "move to Liststartoffset"
                                + cursor.moveToPosition(appParams.ListStartOffset));


                        if (cursor.moveToFirst()) {

                            do {
                                /*
                                 * Apply remaining filters
                                 */

                                Log.d(TAG, " msgListSize " + messageListingSize
                                        + "write count " + writeCount);

                                messageListingSize++;

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

                                processCount++;
                                /*
                                 * TODO Skip the first ListStartOffset record(s). Don't write
                                 * more than MaxListCount record(s).
                                 */

                                MsgListingConsts emailMsg = new MsgListingConsts();
                                emailMsg = eu.bldEmailMsgLstItem(context, folderName, appParams,
                                        subject, timestamp, senderName, senderAddressing,
                                        recipientName, recipientAddressing,
                                        msgId, readStatus, replyToStr);

                                // New Message?
                                if ((rsp.newMessage == 0) && (cursor.getInt(readInd) != 0)) {
                                    rsp.newMessage = 1;
                                }
                                msgList.add(emailMsg);
                                writeCount++;
                            } while (cursor.moveToNext());

                            cursor.close();
                        }
                    }//end email Messages if
                }
            }
            else {
                if (appParams.FilterPriority > 0x02) {
                    rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
                }
            }
        }

        // Now that the message list exists, we can sort the list by date
        Collections.sort(msgList, new SortMsgListByDate());

        // Process the list based on MaxListCount and list offset
        String str = null;
        int numOfItems = msgList.size();
        int msgDelta = numOfItems - appParams.ListStartOffset;
        int startIdx = appParams.ListStartOffset;
        int stopIdx = 0;
        if (msgDelta <= 0) {
            str = "";
        } else {

            if (msgDelta <= appParams.MaxListCount) {
                stopIdx = startIdx + msgDelta;
            } else {
                stopIdx = startIdx + appParams.MaxListCount;
            }
            List<MsgListingConsts> msgSubList = msgList.subList(startIdx,
                    stopIdx);
            str = mu.messageListingXML(msgSubList);

        }
        // TODO Undo the following check
        int pos = str.indexOf(" msg handle=\"");
        while (pos > 0) {
            Log.d(TAG, " Msg listing str modified");
            String str2 = str.substring(0, pos);
            str2 += str.substring(pos + 1);
            str = str2;
            pos = str.indexOf(" msg handle=\"");
        }

        // String str = "this is a test for the data file";
        try {
            bos.write(str.getBytes());
            bos.flush();
            bos.close();
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        msgList.clear();

        Log.d(TAG, "");

        Log.d(TAG, " MESSAGE LISTING FULL ( total length)" + str.length());
        Log.d(TAG, str);

        byte[] readBytes = new byte[10];
        try {

            FileInputStream fis = new FileInputStream(context.getFilesDir()
                    + "/" + FILENAME);
            fis.close();
            fileGenerated = true;

        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        if (fileGenerated == true) {
            File file = new File(context.getFilesDir() + "/" + FILENAME);
            rsp.file = file;
        }
        rsp.msgListingSize = messageListingSize;

        return rsp;
    }

    public class BluetoothMasMessageRsp {
        public byte fractionDeliver = 0;
        public File file = null;
        public int rsp = ResponseCodes.OBEX_HTTP_OK;
    }

    /**
     * Get the folder name (MAP representation) based on the folder type value
     * in SMS database
     */
    private String getMAPFolder(String type, String threadId) {
        String folder = null;
        if ( type == null || threadId == null){
            Log.d(TAG, "getMapFolder cannot parse folder type");
            return folder;
        }

        if ( Integer.valueOf(threadId) == DELETED_THREAD_ID){
            folder = Deleted;
        } else {
            switch (Integer.valueOf(type)) {
            case 1:
                folder = Inbox;
                break;
            case 2:
                folder = Sent;
                break;
            case 3:
                folder = Draft;
                break;
            case 4:
            case 5:
            case 6:
                folder = Outbox;
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
    private String getContainingFolder(String msgHandle) {
        Cursor cr;

        cr = context.getContentResolver().query(
                Uri.parse("content://sms/" + msgHandle),
                new String[] { "_id", "type", "thread_id" }, null, null, null);
        if (cr.getCount() > 0) {
            cr.moveToFirst();
            return getMAPFolder(cr.getString(cr.getColumnIndex("type")),
                    cr.getString(cr.getColumnIndex("thread_id")));
        }
        return null;
    }

    /**
     * Get the SMS Deliver PDU for the given SMS
     */
    private String getSMSDeliverPdu(String smsBody, String dateTime, String address){

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

        tZone15offset += (Integer.valueOf(tZoneStr.substring(tZoneStr.length()-5, tZoneStr.length()-3)) * 4);
        if ( timeStr.charAt(timeStr.length()-6) == '-'){
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
        for ( int i=0; i<tempTimeStr.length(); i=i+2){
            encodedTimeStr += tempTimeStr.substring(i+1, i+2);
            encodedTimeStr += tempTimeStr.substring(i, i+1);
        }

        byte[] byteAddress = address.getBytes();

        // Let the service center number be 0000000000
        String smsPdu = "0681000000000004";

        // Extract only digits out of the phone address
        StringBuffer strbufAddress = new StringBuffer(address.length() + 1);
        for ( int i=0; i<address.length(); i++){
            Log.d(TAG, " VAL " + address.substring(i, i+1));
            if ( byteAddress[i] >= 48 && byteAddress[i] <= 57 ){
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
        if ( addLength %2 != 0){
            addLength++;
        }
        addLength = addLength / 2;

        // Extract the message from the SubmitPdu
        int msgOffset = 7 + addLength;
        int msgLength = msg.length - msgOffset;

        StringBuffer strbufMessage = new StringBuffer(msgLength * 2);

        // Convert from byte to Hex String
        for(int i=msgOffset; i<msgLength + msgOffset; i++)
        {
            if(((int) msg[i] & 0xff) < 0x10){
                strbufMessage.append("0");
            }
            strbufMessage.append((Long.toString((int) msg[i] & 0xff, 16)));
        }

        int encodedAddressLength = strAddress.length() / 2;
        if ( strAddress.length() % 2 != 0){
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

        Cursor cr = null;
        Uri uri = Uri.parse("content://sms/");
        String whereClause = " _id = " + msgHandle;
        try {
            cr = context.getContentResolver().query(uri, null, whereClause, null,
                    null);
        } catch (Exception e){
            rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            return rsp;
        }

        if (cr.getCount() > 0) {
            cr.moveToFirst();
            String containingFolder = getContainingFolder(msgHandle);
            BmessageConsts bmsg = new BmessageConsts();

            // Create a bMessage

            // TODO Get Current type
            bmsg.setType("SMS_GSM");

            bmsg.setBmsg_version("1.0");
            if (cr.getString(cr.getColumnIndex("read")).equalsIgnoreCase("1")) {
                bmsg.setStatus("READ");
            } else {
                bmsg.setStatus("UNREAD");
            }

            bmsg.setFolder("TELECOM/MSG/" + containingFolder);

            bmsg.setVcard_version("2.1");
            VcardContent vcard = getVcardContent(cr.getString(cr
                    .getColumnIndex("address")));

            String type = cr.getString(cr.getColumnIndex("type"));
            // Inbox is type 1.
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
                smsBody = getSMSDeliverPdu(smsBodyUnicode, cr.getString(cr.getColumnIndex("date")), vcard.tel);
            }

            bmsg.setBody_length(22 + smsBody.length());

            bmsg.setBody_msg(smsBody);


            // Send a bMessage
            Log.d(TAG, "bMessageSMS test\n");
            Log.d(TAG, "=======================\n\n");
            String str = mu.toBmessageSMS(bmsg);
            Log.d(TAG, str);
            Log.d(TAG, "\n\n");

            if (str != null && (str.length() > 0)) {
                final String FILENAME = "message";
                // BufferedOutputStream bos = null;
                FileOutputStream bos = null;
                File file = new File(context.getFilesDir() + "/" + FILENAME);
                file.delete();

                try {
                    bos = context
                    .openFileOutput(FILENAME, Context.MODE_PRIVATE);
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

                File fileR = new File(context.getFilesDir() + "/" + FILENAME);
                if (fileR.exists() == true) {
                    rsp.file = fileR;
                    rsp.fractionDeliver = 1;
                }

            }

        }
        else{

            // No SMS message was discovered with that handle, now look for an
            // MMS message

            /*
             * Spec 5.6.4 says MSE shall reject request with value native
             * for MMS and Email
             */
            if ( (int)bluetoothMasAppParams.Charset == 0 ) {
                rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
                return rsp;
            }
            int mmsMsgID = 0;
            try {
                mmsMsgID = getMmsMsgHndToID(Integer.valueOf(msgHandle));
            } catch (Exception e) {
                rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
                return rsp;
            }
            if (mmsMsgID > 0) {
                bldMmsBmsg(mmsMsgID, rsp);
            }
            else{ //No SMS and MMS messages were discovered. now look for email messages
                Log.d(TAG, "Inside email :");
                // Email message
                int emailMsgID = 0;
                EmailUtils eu = new EmailUtils();

                emailMsgID = (Integer.valueOf(msgHandle) - EMAIL_HDLR_CONSTANT);
                String str = eu.bldEmailBmsg(emailMsgID, rsp, context, mu);

                Log.d(TAG, str);
                Log.d(TAG, "\n\n");

                if (str != null && (str.length() > 0)) {
                    final String FILENAME = "message";
                    FileOutputStream bos = null;
                    File file = new File(context.getFilesDir() + "/" + FILENAME);
                    file.delete();

                    try {
                        bos = context.openFileOutput(FILENAME, Context.MODE_PRIVATE);
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

                    File fileR = new File(context.getFilesDir() + "/" + FILENAME);
                    if (fileR.exists() == true) {
                        rsp.file = fileR;
                        rsp.fractionDeliver = 1;
                    }
                    else {
                        rsp.rsp = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
                    }

                }
            }
        }

        return rsp;
    }

    public class BluetoothMasPushMsgRsp {
        public int response;
        public String msgHandle;
    }

    /**
     * Retrieve the conversation thread id
     */
    private int getThreadId(String address) {

        Cursor cr = context.getContentResolver().query(
                Uri.parse("content://sms/"), null,
                "address = '" + address + "'", null, null);
        if (cr.moveToFirst()) {
            int threadId = Integer.valueOf(cr.getString(cr
                    .getColumnIndex("thread_id")));
            Log.d(TAG, " Found the entry, thread id = " + threadId);

            return (threadId);
        }
        return 0;
    }

    /**
     * Adds a SMS to the Sms ContentProvider
     */

    public String addToSmsFolder(String folder, String address, String text) {

        int threadId = getThreadId(address);
        Log.d(TAG, "-------------");
        Log.d(TAG, "address " + address + " TEXT " + text + " Thread ID "
                + threadId);

        ContentValues values = new ContentValues();
        values.put("thread_id", threadId);
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
        Uri uri = context.getContentResolver().insert(
                Uri.parse("content://sms/" + folder), values);
        Log.d(TAG, " NEW URI " + uri.toString());

        String str = uri.toString();
        String[] splitStr = str.split("/");
        Log.d(TAG, " NEW HANDLE " + splitStr[3]);
        return splitStr[3];
    }
    /**
     * Adds an Email to the Email ContentProvider
     */

    public String addToEmailFolder(String folder, String address, String text, String subject, String OrigEmail, String OrigName) {

        Log.d(TAG, "-------------");
        Log.d(TAG, "address " + address);
        Log.d(TAG, "TEXT " + text);

        //TODO need to insert a row in the body table and update the mailbox table with the no of messages unread
        Cursor cr;
        int folderId = 0;
        int accountId = 0;
        Time timeObj = new Time();
        timeObj.setToNow();

        String whereClause = "UPPER(displayName) LIKE  '%"+folder.toUpperCase().trim()+"%'";
        cr = context.getContentResolver().query(
                Uri.parse("content://com.android.email.provider/mailbox"),
                null, whereClause, null, null);
        if (cr.getCount() > 0) {
            cr.moveToFirst();
            folderId = cr.getInt(cr.getColumnIndex("_id"));
        }



        Cursor cr1;
        String whereClause1 = "UPPER(emailAddress) LIKE  '"+OrigEmail.toUpperCase().trim()+"'";
        cr1 = context.getContentResolver().query(
                Uri.parse("content://com.android.email.provider/account"),
                null, whereClause1, null, null);
        if (cr1.getCount() > 0) {
            cr1.moveToFirst();
            accountId = cr1.getInt(cr1.getColumnIndex("_id"));
        }



        Log.d(TAG, "-------------");
        Log.d(TAG, "To address " + address);
        Log.d(TAG, "Text " + text);
        Log.d(TAG, "Originator email address:: " + OrigEmail);
        Log.d(TAG, "Originator email name:: " + OrigName);
        Log.d(TAG, "Time Stamp:: " + timeObj.toMillis(false));
        Log.d(TAG, "Account Key:: " + accountId);
        Log.d(TAG, "Folder Id:: " + folderId);
        Log.d(TAG, "Folder Name:: " + folder);
        Log.d(TAG, "Subject" + subject);

        ContentValues values = new ContentValues();
        //Hardcoded values
        values.put("syncServerId", "1");
        //end Hardcoded values
        values.put("syncServerTimeStamp", timeObj.toMillis(false));
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

        Uri uri = context.getContentResolver().insert(
                Uri.parse("content://com.android.email.provider/message"), values);
        Log.d(TAG, " NEW URI " + uri.toString());

        String str = uri.toString();
        String[] splitStr = str.split("/");
        Log.d(TAG, " NEW HANDLE " + splitStr[4]);

        //TODO need to insert into the body table --seems like body table gets updated automatically
        ContentValues valuesBody = new ContentValues();
        valuesBody.put("messageKey", splitStr[4]);
        valuesBody.put("textContent", text);

        Uri uri1 = context.getContentResolver().insert(
                Uri.parse("content://com.android.email.provider/body"), valuesBody);

        return splitStr[4];

    }
    public long getEmailAccountId(String email) {
        Cursor cr1;
        long accountId = -1;
        String whereClause1 = "UPPER(emailAddress) LIKE  '"+email.toUpperCase().trim()+"'";
        cr1 = context.getContentResolver().query(
                Uri.parse("content://com.android.email.provider/account"),
                null, whereClause1, null, null);
        if (cr1.getCount() > 0) {
            cr1.moveToFirst();
            accountId = cr1.getInt(cr1.getColumnIndex("_id"));
        }
        return accountId;
    }

    /**
     * Get the type (as in Sms ContentProvider) for the given table name
     */
    private int getSMSFolderType(String folder) {
        int type = 0;
        if (folder.equalsIgnoreCase(Inbox)) {
            type = 1;
        } else if (folder.equalsIgnoreCase(Sent)) {
            type = 2;
        } else if (folder.equalsIgnoreCase(Draft)) {
            type = 3;
        } else if (folder.equalsIgnoreCase(Outbox)) {
            type = 4;
        } else if (folder.equalsIgnoreCase(Failed)) {
            type = 5;
        } else if (folder.equalsIgnoreCase(Queued)) {
            type = 6;
        }
        return type;
    }

    /**
     * Modify the type (as in Sms ContentProvider) For eg: move from outbox to
     * send type
     */
    private void moveToFolder(String handle, String folder) {
        // Make sure the handle is not null (Tranparent == 0)
        if (handle != null) {
            ContentValues values = new ContentValues();
            values.put("type", getSMSFolderType(folder));
            Uri uri = Uri.parse("content://sms/" + handle);
            context.getContentResolver().update(uri, values, null, null);
        }
    }

    private String PhoneAddress;
    private String SmsText;
    private String SmsHandle;


    private String EmailAddress;
    private String EmailText;
    private String EmailHandle;
    private String EmailSubject;
    private String EmailOriginator;
    private String EmailOrigName;

    /**
     * Push a outgoing message from MAS Client to the network
     *
     * @return Response to push command
     */
    public BluetoothMasPushMsgRsp pushMsg(String name, File file,
            BluetoothMasAppParams bluetoothMasAppParams) {
        // TODO Auto-generated method stub

        BluetoothMasPushMsgRsp rsp = new BluetoothMasPushMsgRsp();
        rsp.response = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        rsp.msgHandle = null;

        final String SENT = "Sent";
        final String DELIVERED = "Delivered";

        if(!checkPath(false, name, false) ||
                CurrentPath == null ||
                CurrentPath.equals("telecom") ||
                (CurrentPath.equals("telecom/msg") && (name == null)) ) {
            rsp.response = ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            return rsp;
        }



        byte[] readBytes = null;
        FileInputStream fis;
        try {
            fis = new FileInputStream(file);
            readBytes = new byte[(int) file.length()];
            fis.read(readBytes);

        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return rsp;
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return rsp;
        }

        String readStr = new String(readBytes);
        MapUtils mu = new MapUtils();
        String type = mu.fetchType(readStr);
        if(type!=null && (type.equalsIgnoreCase("SMS_GSM") || type.equalsIgnoreCase("MMS"))){
            BmessageConsts bMsg = mu.fromBmessageSMS(readStr);
            PhoneAddress = bMsg.getRecipientVcard_phone_number();
            SmsText = bMsg.getBody_msg();

            // If the message to be pushed is an MMS message, extract any text,
            // discard
            // any attachments and convert the message to an SMS

            if ((bMsg.getType()).equals("MMS")) {
                /*
                 * The pair of calls below is used to send the MMS message out to
                 * the network.You need to first move the message to the drafts
                 * folder and then move the message from drafts to the outbox
                 * folder. This action causes the message to also be added to the
                 * pending_msgs table in the database. The transaction service will
                 * then send the message out to the network the next time it is
                 * scheduled to run
                 */

                moveMMStoDrafts(readStr);
                moveMMSfromDraftstoOutbox();

                Log.d(TAG, "\nBroadcasting Intent to MmsSystemEventReceiver\n ");
                Intent sendIntent = new Intent("android.intent.action.MMS_PUSH");
                context.sendBroadcast(sendIntent);

                return rsp;

            }

            String tmpPath = "";
            tmpPath = (name == null) ? CurrentPath : CurrentPath + "/" + name;

            if(!tmpPath.equalsIgnoreCase("telecom/msg/outbox")) {
                String splitStrings[] = CurrentPath.split("/");
                mnsClient.addMceInitiatedOperation("+");
                int tmp = splitStrings.length;
                String folderName;
                if (name != null) {
                    if (name.equals("")){
                        folderName = splitStrings[tmp - 1];
                    } else {
                        folderName = name;
                    }
                } else {
                    folderName = splitStrings[tmp - 1];
                }
                SmsHandle = addToSmsFolder(folderName, PhoneAddress, SmsText);
                rsp.msgHandle = SmsHandle;
                rsp.response = ResponseCodes.OBEX_HTTP_OK;
                return rsp;
            }

            PendingIntent sentPI = PendingIntent.getBroadcast(this.context, 0,
                    new Intent(SENT), 0);

            PendingIntent deliveredPI = PendingIntent.getBroadcast(this.context, 0,
                    new Intent(DELIVERED), 0);

            // ---when the SMS has been sent---
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context arg0, Intent arg1) {
                    Log.d(TAG, "Sms SENT STATUS ");

                    switch (getResultCode()) {
                    case Activity.RESULT_OK:
                        Log.d(TAG, "Sms Sent");
                        moveToFolder(SmsHandle, Sent);
                        break;
                    case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                        Log.d(TAG, "Generic Failure");
                        moveToFolder(SmsHandle, Failed);
                        break;
                    case SmsManager.RESULT_ERROR_NO_SERVICE:
                        Log.d(TAG, "NO SERVICE ERROR");
                        moveToFolder(SmsHandle, Queued);
                        break;
                    case SmsManager.RESULT_ERROR_NULL_PDU:
                        Log.d(TAG, "Null PDU");
                        moveToFolder(SmsHandle, Failed);
                        break;
                    case SmsManager.RESULT_ERROR_RADIO_OFF:
                        Log.d(TAG, "RADIO OFF");
                        moveToFolder(SmsHandle, Queued);
                        break;
                    }
                    context.unregisterReceiver(this);
                }
            }, new IntentFilter(SENT));

            // ---when the SMS has been delivered---
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context arg0, Intent arg1) {
                    Log.d(TAG, "Sms SENT DELIVERED ");

                    switch (getResultCode()) {
                    case Activity.RESULT_OK:
                        Log.d(TAG, "Sms Delivered");
                        break;
                    case Activity.RESULT_CANCELED:
                        Log.d(TAG, "Sms NOT Delivered");
                        break;
                    }
                    context.unregisterReceiver(this);
                }
            }, new IntentFilter(DELIVERED));

            SmsHandle = null;
            rsp.msgHandle = "";

            if(bluetoothMasAppParams.Transparent == 0) {
                mnsClient.addMceInitiatedOperation("+");
                SmsHandle = addToSmsFolder("outbox", PhoneAddress, SmsText);
                rsp.msgHandle = SmsHandle;
            }


            SmsManager sms = SmsManager.getDefault();

            Log.d(TAG, " Trying to send SMS ");
            Log.d(TAG, " Text " + SmsText + " address " + PhoneAddress);

            try {
                sms.sendTextMessage(PhoneAddress, null, SmsText, sentPI,
                        deliveredPI);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();

            }
        }
        else if(type!=null && type.equalsIgnoreCase("EMAIL")){
            Log.d(TAG, " Before fromBmessageemail method:: "+readStr);
            BmessageConsts bMsg = mu.fromBmessageEmail(readStr);
            EmailAddress = bMsg.getRecipientVcard_email();
            EmailText = bMsg.getBody_msg();
            EmailSubject = bMsg.getSubject();
            EmailOriginator = bMsg.getOriginatorVcard_email();
            EmailOrigName = bMsg.getOriginatorVcard_name();

            String tmpPath = "";
            tmpPath = (name == null) ? CurrentPath : CurrentPath + "/" + name;

            if(!tmpPath.equalsIgnoreCase("telecom/msg/sent1")) {
                String splitStrings[] = CurrentPath.split("/");
                mnsClient.addMceInitiatedOperation("+");
                int tmp = splitStrings.length;
                String folderName;
                if (name != null) {
                    if (name.equals("")){
                        folderName = splitStrings[tmp - 1];
                    } else {
                        folderName = name;
                    }
                } else {
                    folderName = splitStrings[tmp - 1];
                }
                EmailHandle = addToEmailFolder(folderName, EmailAddress, EmailText, EmailSubject,
                        EmailOriginator, EmailOrigName);
                rsp.msgHandle = EmailHandle;
                rsp.response = ResponseCodes.OBEX_HTTP_OK;

                long accountId = getEmailAccountId(EmailOriginator);
                Log.d(TAG, " Account id before Mail service:: "+accountId);

                Intent emailIn = new Intent();

                emailIn.setAction("com.android.email.intent.action.MAIL_SERVICE_WAKEUP");
                emailIn.putExtra("com.android.email.intent.extra.ACCOUNT", accountId);
                this.context.startService(emailIn);

                return rsp;
            }
            else{
                String splitStrings[] = CurrentPath.split("/");
                mnsClient.addMceInitiatedOperation("+");
                int tmp = splitStrings.length;
                String folderName;
                if (name != null) {
                    if (name.equals("")){
                        folderName = splitStrings[tmp - 1];
                    } else {
                        folderName = name;
                    }
                }
                else {
                    folderName = splitStrings[tmp - 1];
                }

            }

        }

        rsp.response = ResponseCodes.OBEX_HTTP_OK;

        return rsp;
    }

    private void updateMMSThreadId(String msgHandle, int threadId){
        ContentValues values = new ContentValues();
        values.put("thread_id", threadId);
        context.getContentResolver().update( Uri.parse("content://mms/" + msgHandle), values, null, null);
    }

    private void deleteMMS(String msgHandle){
        Cursor cr = context.getContentResolver().query(Uri.parse("content://mms/" + msgHandle), null, null, null, null);
        if ( cr.moveToFirst()){
            int threadId = cr.getInt(cr.getColumnIndex(("thread_id")));
            if ( threadId != DELETED_THREAD_ID){
                // Move to deleted folder
                updateMMSThreadId(msgHandle, Integer.valueOf(DELETED_THREAD_ID));
            } else {
                // Delete the message permanently
                int msgId = Integer.valueOf(msgHandle) + MMS_HDLR_CONSTANT;
                mnsClient.addMceInitiatedOperation(Integer.toString(msgId));
                context.getContentResolver().delete(Uri.parse("content://mms/" + msgHandle), null, null);
            }
            cr.close();
        }
    }

    private void unDeleteMMS(String msgHandle){

        Cursor cr = context.getContentResolver().query(Uri.parse("content://mms/" + msgHandle), null, null, null, null );

        if (cr.moveToFirst()){

            // Make sure that the message is in delete folder
            String currentThreadId = cr.getString(cr.getColumnIndex("thread_id"));
            if ( currentThreadId != null && Integer.valueOf(currentThreadId) != -1){
                Log.d(TAG, " Not in delete folder");
                return;
            }

            // Fetch the address of the deleted message

            String address = getMmsMsgAddress(Integer.valueOf(msgHandle));

            // Search the database for the given message ID
            Cursor crThreadId = context.getContentResolver().query(Uri.parse("content://mms/"), null,"_id = " + msgHandle + " AND thread_id != -1", null, null);
            if (crThreadId.moveToFirst()) {
                // A thread for the given message ID exists in the database
                String threadIdStr = crThreadId.getString(crThreadId.getColumnIndex("thread_id"));
                Log.d(TAG, " THREAD ID " + threadIdStr);
                updateMMSThreadId(msgHandle, Integer.valueOf(threadIdStr));
            }
            else
            {
                /* No thread for the given address
                 * Create a fake message to obtain the thread, use that thread_id
                 * and then delete the fake message
                 */
                ContentValues tempValue = new ContentValues();
                tempValue.put("address", address);
                tempValue.put("type", "20");
                Uri tempUri = context.getContentResolver().insert( Uri.parse("content://sms/"), tempValue);

                if ( tempUri != null ) {
                    String tempMsgId = tempUri.getLastPathSegment();
                    Cursor tempCr = context.getContentResolver().query(tempUri, null, null, null, null);
                    tempCr.moveToFirst();
                    String newThreadIdStr = tempCr.getString(tempCr.getColumnIndex("thread_id"));
                    tempCr.close();

                    updateMMSThreadId(msgHandle, Integer.valueOf(newThreadIdStr));

                    context.getContentResolver().delete(tempUri, null, null);
                }
                else {
                    Log.d(TAG, "Error in undelete");
                }
            }
            crThreadId.close();
        }
        else
        {
            Log.d(TAG, "msgHandle not found");
        }
        cr.close();
    }

    private void updateSMSThreadId(String msgHandle, int threadId){
        ContentValues values = new ContentValues();
        values.put("thread_id", threadId);
        context.getContentResolver().update( Uri.parse("content://sms/" + msgHandle), values, null, null);
    }

    private void deleteSMS(String msgHandle){
        Cursor cr = context.getContentResolver().query(Uri.parse("content://sms/" + msgHandle), null, null, null, null);
        if ( cr.moveToFirst()){
            int threadId = cr.getInt(cr.getColumnIndex(("thread_id")));
            if ( threadId != DELETED_THREAD_ID){
                // Move to deleted folder
                updateSMSThreadId(msgHandle, Integer.valueOf(DELETED_THREAD_ID));
            } else {
                // Delete the message permanently
                mnsClient.addMceInitiatedOperation(msgHandle);
                context.getContentResolver().delete(Uri.parse("content://sms/" + msgHandle), null, null);
            }
            cr.close();
        }
    }

    private void unDeleteSMS(String msgHandle){

        Cursor cr = context.getContentResolver().query(Uri.parse("content://sms/" + msgHandle), null, null, null, null );

        if (cr.moveToFirst()){

            // Make sure that the message is in delete folder
            String currentThreadId = cr.getString(cr.getColumnIndex("thread_id"));
            if ( currentThreadId != null && Integer.valueOf(currentThreadId) != -1){
                Log.d(TAG, " Not in delete folder");
                return;
            }

            // Fetch the address of the deleted message
            String address = cr.getString(cr.getColumnIndex("address"));

            // Search the database for the given address
            Cursor crThreadId = context.getContentResolver().query(Uri.parse("content://sms/"),
                    null, "address = " + address + " AND thread_id != -1", null, null);
            if (crThreadId.moveToFirst()){
                // A thread for the given address exists in the database
                String threadIdStr = crThreadId.getString(crThreadId.getColumnIndex("thread_id"));
                Log.d(TAG, " THREAD ID " + threadIdStr);
                updateSMSThreadId(msgHandle, Integer.valueOf(threadIdStr));
            }
            else
            {
                /* No thread for the given address
                 * Create a fake message to obtain the thread, use that thread_id
                 * and then delete the fake message
                 */
                ContentValues tempValue = new ContentValues();
                tempValue.put("address", address);
                tempValue.put("type", "20");
                Uri tempUri = context.getContentResolver().insert( Uri.parse("content://sms/"), tempValue);

                if ( tempUri != null ) {
                    String tempMsgId = tempUri.getLastPathSegment();
                    Cursor tempCr = context.getContentResolver().query(tempUri, null, null, null, null);
                    tempCr.moveToFirst();
                    String newThreadIdStr = tempCr.getString(tempCr.getColumnIndex("thread_id"));
                    tempCr.close();

                    updateSMSThreadId(msgHandle, Integer.valueOf(newThreadIdStr));

                    context.getContentResolver().delete(tempUri, null, null);
                }
            }
            crThreadId.close();
        }
        else
        {
            Log.d(TAG, "msgHandle not found");
        }
        cr.close();
    }


    /**
     * Sets the message status (read/unread, delete)
     *
     * @return Obex response code
     */
    public int msgStatus(String name,
            BluetoothMasAppParams bluetoothMasAppParams) {

        if ((bluetoothMasAppParams.StatusIndicator != 0)
                && (bluetoothMasAppParams.StatusIndicator != 1)) {
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        if ((bluetoothMasAppParams.StatusValue != 0)
                && (bluetoothMasAppParams.StatusValue != 1)) {
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        Uri uri = Uri.parse("content://sms/" + name);

        Cursor cr = context.getContentResolver().query(uri, null, null, null,
                null);
        if (cr.moveToFirst()) {
            if (bluetoothMasAppParams.StatusIndicator == 0) {
                /* Read Status */
                ContentValues values = new ContentValues();
                values.put("read", bluetoothMasAppParams.StatusValue);
                context.getContentResolver().update(uri, values, null, null);
            } else {
                if (bluetoothMasAppParams.StatusValue == 1) {
                    deleteSMS(name);
                } else if (bluetoothMasAppParams.StatusValue == 0) {
                    unDeleteSMS(name);
                }
            }
            return ResponseCodes.OBEX_HTTP_OK;
        }
        // MMS MessageStatus processing begins here
        cr.close();
        String whereClause = " _id= " + getMmsMsgHndToID(Integer.valueOf(name));
        uri = Uri.parse("content://mms/");
        if(getMmsMsgHndToID(Integer.valueOf(name)) > 0){
            cr = context.getContentResolver().query(uri, null, null, null, null);
        }
        if (cr.getCount() > 0) {
            cr.moveToFirst();
            if (bluetoothMasAppParams.StatusIndicator == 0) {
                /* Read Status */
                ContentValues values = new ContentValues();
                values.put("read", bluetoothMasAppParams.StatusValue);
                int rowUpdate = context.getContentResolver().update(uri,
                        values, whereClause, null);
                Log.d(TAG, "\nRows updated => " + Integer.toString(rowUpdate));
                return ResponseCodes.OBEX_HTTP_OK;
            } else {
                if (bluetoothMasAppParams.StatusValue == 1) {
                    deleteMMS(Integer.toString(getMmsMsgHndToID(Integer.valueOf(name))));
                } else if (bluetoothMasAppParams.StatusValue == 0) {
                    unDeleteMMS(Integer.toString(getMmsMsgHndToID(Integer.valueOf(name))));
                }

                return ResponseCodes.OBEX_HTTP_OK;
            }
        }
        cr.close();

        //Email
        //Query the mailbox table to get the id values for Inbox and Trash folder
        Uri uri1 = Uri.parse("content://com.android.email.provider/mailbox");

        Cursor cr1 = context.getContentResolver().query(uri1, null,
                "(UPPER(displayName) = 'INBOX' OR UPPER(displayName) LIKE '%TRASH%')", null, null);
        int inboxFolderId = 0;
        int deletedFolderId = 0;
        int msgFolderId = 0;
        String folderName;
        if (cr1.getCount() > 0) {
            cr1.moveToFirst();
            do {
                folderName = cr1.getString(cr1.getColumnIndex("displayName"));
                if(folderName.equalsIgnoreCase("INBOX")){
                    inboxFolderId = cr1.getInt(cr1.getColumnIndex("_id"));
                }
                else{
                    deletedFolderId = cr1.getInt(cr1.getColumnIndex("_id"));
                }
            } while ( cr1.moveToNext());
        }


        //Query the message table for the given message id
        int emailMsgId = 0;
        Log.d(TAG, "\n name ::=> " + (Integer.valueOf(name)));
        emailMsgId = (Integer.valueOf(name) - EMAIL_HDLR_CONSTANT);

        Uri uri2 = Uri.parse("content://com.android.email.provider/message/"+emailMsgId);
        cr = context.getContentResolver().query(uri2, null, null, null,
                null);
        if (cr.moveToFirst()) {

            if (bluetoothMasAppParams.StatusIndicator == 0) {
                /* Read Status */
                ContentValues values = new ContentValues();
                values.put("flagRead", bluetoothMasAppParams.StatusValue);
                context.getContentResolver().update(uri2, values, null, null);
            }
            else {
                if (bluetoothMasAppParams.StatusValue == 1) { //if the email is deleted
                    msgFolderId = cr.getInt(cr.getColumnIndex("mailboxKey"));
                    if(msgFolderId == deletedFolderId){
                        //TODO need to add notification for deleted email here
                        mnsClient.addMceInitiatedOperation(name);
                        context.getContentResolver().delete(
                                Uri.parse("content://com.android.email.provider/message/"
                                + emailMsgId), null, null);
                    }
                    else{
                        ContentValues values = new ContentValues();
                        values.put("mailboxKey", deletedFolderId);
                        context.getContentResolver().update(uri2, values, null, null);
                    }
                }
                else{ // if the email is undeleted
                    ContentValues values = new ContentValues();
                    values.put("mailboxKey", inboxFolderId);
                    context.getContentResolver().update(uri2, values, null, null);
                }
            }
            return ResponseCodes.OBEX_HTTP_OK;
        }


        return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
    }
    /**
     * Sets the message update
     *
     * @return Obex response code
     */
    public int msgUpdate(String name,
            BluetoothMasAppParams bluetoothMasAppParams) {

        if ((bluetoothMasAppParams.StatusIndicator != 0)
                && (bluetoothMasAppParams.StatusIndicator != 1)) {
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        if ((bluetoothMasAppParams.StatusValue != 0)
                && (bluetoothMasAppParams.StatusValue != 1)) {
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        Uri uri = Uri.parse("content://sms/" + name);

        Cursor cr = context.getContentResolver().query(uri, null, null, null,
                null);
        if (cr.moveToFirst()) {
            if (bluetoothMasAppParams.StatusIndicator == 0) {
                /* Read Status */
                ContentValues values = new ContentValues();
                values.put("read", bluetoothMasAppParams.StatusValue);
                context.getContentResolver().update(uri, values, null, null);
            } else {
                if (bluetoothMasAppParams.StatusValue == 1) {
                    deleteSMS(name);
                } else if (bluetoothMasAppParams.StatusValue == 0) {
                    unDeleteSMS(name);
                }
            }
            return ResponseCodes.OBEX_HTTP_OK;
        }
        // MMS MessageStatus processing begins here
        cr.close();
        String whereClause = " _id= " + getMmsMsgHndToID(Integer.valueOf(name));
        uri = Uri.parse("content://mms/");
        if(getMmsMsgHndToID(Integer.valueOf(name)) > 0){
            cr = context.getContentResolver().query(uri, null, null, null, null);
        }
        if (cr.getCount() > 0) {
            cr.moveToFirst();
            if (bluetoothMasAppParams.StatusIndicator == 0) {
                /* Read Status */
                ContentValues values = new ContentValues();
                values.put("read", bluetoothMasAppParams.StatusValue);
                int rowUpdate = context.getContentResolver().update(uri,
                        values, whereClause, null);
                Log.d(TAG, "\nRows updated => " + Integer.toString(rowUpdate));
                return ResponseCodes.OBEX_HTTP_OK;
            } else {
                if (bluetoothMasAppParams.StatusValue == 1) {
                    deleteMMS(Integer.toString(getMmsMsgHndToID(Integer.valueOf(name))));
                } else if (bluetoothMasAppParams.StatusValue == 0) {
                    unDeleteMMS(Integer.toString(getMmsMsgHndToID(Integer.valueOf(name))));
                }

                return ResponseCodes.OBEX_HTTP_OK;
            }
        }
        cr.close();

        //Email
        //Query the mailbox table to get the id values for Inbox and Trash folder
        Uri uri1 = Uri.parse("content://com.android.email.provider/mailbox");

        Cursor cr1 = context.getContentResolver().query(
                uri1, null, "(UPPER(displayName) = 'INBOX' OR UPPER(displayName) LIKE '%TRASH%')", null, null);
        int inboxFolderId = 0;
        int deletedFolderId = 0;
        int msgFolderId = 0;
        String folderName;
        if (cr1.getCount() > 0) {
            cr1.moveToFirst();
            do {
                folderName = cr1.getString(cr1.getColumnIndex("displayName"));
                if(folderName.equalsIgnoreCase("INBOX")){
                    inboxFolderId = cr1.getInt(cr1.getColumnIndex("_id"));
                }
                else{
                    deletedFolderId = cr1.getInt(cr1.getColumnIndex("_id"));
                }
            } while ( cr1.moveToNext());
        }


        //Query the message table for the given message id
        int emailMsgId = 0;
        Log.d(TAG, "\n name ::=> " + (Integer.valueOf(name)));
        emailMsgId = (Integer.valueOf(name) - EMAIL_HDLR_CONSTANT);

        Uri uri2 = Uri.parse("content://com.android.email.provider/message/"+emailMsgId);
        cr = context.getContentResolver().query(uri2, null, null, null,
                null);
        if (cr.moveToFirst()) {

            if (bluetoothMasAppParams.StatusIndicator == 0) {
                /* Read Status */
                ContentValues values = new ContentValues();
                values.put("flagRead", bluetoothMasAppParams.StatusValue);
                context.getContentResolver().update(uri2, values, null, null);
            }
            else {
                if (bluetoothMasAppParams.StatusValue == 1) { //if the email is deleted
                    msgFolderId = cr.getInt(cr.getColumnIndex("mailboxKey"));
                    if(msgFolderId == deletedFolderId){
                        //TODO need to add notification for deleted email here
                        mnsClient.addMceInitiatedOperation(name);
                        context.getContentResolver().delete(
                                Uri.parse("content://com.android.email.provider/message/"
                                + emailMsgId), null, null);
                    }
                    else{
                        ContentValues values = new ContentValues();
                        values.put("mailboxKey", deletedFolderId);
                        context.getContentResolver().update(uri2, values, null, null);
                    }
                }
                else{ // if the email is undeleted
                    ContentValues values = new ContentValues();
                    values.put("mailboxKey", inboxFolderId);
                    context.getContentResolver().update(uri2, values, null, null);
                }
            }
            return ResponseCodes.OBEX_HTTP_OK;
        }


        return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
    }


    /**
     * Enable/disable notification
     *
     * @return Obex response code
     */
    public int notification(BluetoothDevice remoteDevice,
            BluetoothMasAppParams bluetoothMasAppParams) {
        // TODO Auto-generated method stub

        if (bluetoothMasAppParams.Notification == 1) {
            startMnsSession(remoteDevice);
            return ResponseCodes.OBEX_HTTP_OK;
        } else if (bluetoothMasAppParams.Notification == 0) {
            stopMnsSession(remoteDevice);
            return ResponseCodes.OBEX_HTTP_OK;
        }

        return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;

    }

    private void clearDeletedItems() {
        // Remove the deleted item entries
        context.getContentResolver().delete(Uri.parse("content://sms/"),
                "thread_id = " + DELETED_THREAD_ID, null);
        context.getContentResolver().delete(Uri.parse("content://mms/"),
                "thread_id = " + DELETED_THREAD_ID, null);
    }

    public void disconnect() {
        clearDeletedItems();
    }


    /**
     * Start an MNS obex client session and push notification whenever available
     */
    public void startMnsSession(BluetoothDevice remoteDevice) {
        Log.d(TAG, "Start MNS Client");
        mnsClient.getHandler()
        .obtainMessage(BluetoothMns.MNS_CONNECT, -1, -1, remoteDevice)
        .sendToTarget();
    }

    /**
     * Stop pushing notifications and disconnect MNS obex session
     */
    public void stopMnsSession(BluetoothDevice remoteDevice) {
        Log.d(TAG, "Stop MNS Client");
        mnsClient
        .getHandler()
        .obtainMessage(BluetoothMns.MNS_DISCONNECT, -1, -1,
                remoteDevice).sendToTarget();
    }

    /**
     * Push an event over MNS client to MNS server
     */
    private void sendMnsEvent(String msg, String handle, String folder,
            String old_folder, String msgType) {
        Log.d(TAG, "Send MNS Event");
        mnsClient.sendMnsEvent(msg, handle, folder, old_folder, msgType);
    }

    /**
     * Obtain the number of MMS messages
     */
    private int getNumMmsMsgs(String name) {
        int msgCount = 0;

        if ( name.equalsIgnoreCase(Deleted)){
            Uri uri = Uri.parse("content://mms/");
            ContentResolver cr = context.getContentResolver();
            Cursor cursor = cr.query(uri, null, "thread_id = " + DELETED_THREAD_ID, null, null);
            if(cursor != null){
                msgCount = cursor.getCount();
                cursor.close();
            }
        } else {
            Uri uri = Uri.parse("content://mms/" + name);
            ContentResolver cr = context.getContentResolver();
            Cursor cursor = cr.query(uri, null, "thread_id <> " + DELETED_THREAD_ID, null, null);
            if(cursor != null){
                msgCount = cursor.getCount();
                cursor.close();
            }
        }
        return msgCount;
    }

    /**
     * Obtain the MMS message Handle
     */

    private Integer getMmsMsgHnd(int msgID) {
        int handle = 0;
        String whereClause = " mid= " + msgID + " AND ct=\"text/plain\"";
        Uri uri = Uri.parse("content://mms/part");
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            int handleInd = cursor.getColumnIndex("_id");
            handle = cursor.getInt(handleInd);
        }
        cursor.close();
        return handle;
    }

    /**
     * Obtain the MMS message ID from Handle
     */

    private Integer getMmsMsgHndToID(int msgHandle) {
        int msgID = -1;
        String whereClause = " mid= " + (msgHandle - MMS_HDLR_CONSTANT);
        Uri uri = Uri.parse("content://mms/part");
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            int handleInd = cursor.getColumnIndex("mid");
            msgID = cursor.getInt(handleInd);
        }
        cursor.close();
        return msgID;
    }

    /**
     * Obtain the MMS message MID list
     */

    private List<Integer> getMmsMsgMIDs(String whereClause) {
        List<Integer> idList = new ArrayList<Integer>();
        Uri uri = Uri.parse("content://mms");
        ContentResolver cr = context.getContentResolver();
        Cursor crID = cr.query(uri, null, whereClause, null, null);
        int idInd = crID.getColumnIndex("_id");
        if (crID.getCount() != 0) {
            crID.moveToFirst();
            do {
                idList.add(Integer.valueOf(crID.getInt(idInd)));
            } while (crID.moveToNext());
        }

        crID.close();
        return idList;
    }

    /**
     * Build a whereclause for MMS filtering
     */

    private String bldMmsWhereClause(BluetoothMasAppParams appParams,
            int foldertype) {

        String whereClause = "";
        if ( foldertype != 0) {
            // Inbox, Outbox, Sent, Draft folders
            whereClause = "msg_box=" + foldertype + " AND thread_id <> " + DELETED_THREAD_ID;
        } else {
            // Deleted folder
            whereClause =  "thread_id = " + DELETED_THREAD_ID;
        }

        /* Filter readstatus: 0 no filtering, 0x01 get unread, 0x10 get read */
        if (appParams.FilterReadStatus != 0) {
            if ((appParams.FilterReadStatus & 0x1) != 0) {
                if (whereClause != "") {
                    whereClause += " AND ";
                }
                whereClause += " read=0 ";
            }
            if ((appParams.FilterReadStatus & 0x10) != 0) {
                if (whereClause != "") {
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
                time.parse(appParams.FilterPeriodBegin);
                if (whereClause != "") {
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
                time.parse(appParams.FilterPeriodEnd);
                if (whereClause != "") {
                    whereClause += " AND ";
                }
                whereClause += "date < " + (time.toMillis(false))/1000;
            } catch (TimeFormatException e) {
                Log.d(TAG, "Bad formatted FilterPeriodEnd "
                        + appParams.FilterPeriodEnd);
            }
        }

        return whereClause;
    }

    /**
     * Obtain the MMS msg_box id
     */

    private int getMmsMsgBox(int msgID) {
        int val = -1;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            int msgBoxInd = cursor.getColumnIndex("msg_box");
            val = cursor.getInt(msgBoxInd);
        }
        cursor.close();
        return val;
    }

    /**
     * Obtain MMS message text
     */

    private String getMmsMsgTxt(int msgID) {
        String text = null;
        String whereClause = " mid= " + msgID + " AND ct=\"text/plain\"";
        Uri uri = Uri.parse("content://mms/part");
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            int textInd = cursor.getColumnIndex("text");
            text = cursor.getString(textInd);
        }
        cursor.close();
        return text;
    }

    /**
     * Obtain the MMS message Subject
     */

    private String getMmsMsgSubject(int msgID) {
        String text = null;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            int subjectInd = cursor.getColumnIndex("sub");
            text = cursor.getString(subjectInd);
        }
        cursor.close();
        return text;
    }

    /**
     * Obtain the MMS message Date
     */

    private String getMmsMsgDate(int msgID) {
        String text = null;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            int dateInd = cursor.getColumnIndex("date");
            text = cursor.getString(dateInd);
        }
        cursor.close();
        return text;

    }

    /**
     * Obtain the MMS attachment size
     */

    private int getMmsMsgAttachSize(int msgID) {
        int attachSize = 0;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            int sizeInd = cursor.getColumnIndex("m_size");
            attachSize = cursor.getInt(sizeInd);
        }
        cursor.close();
        return attachSize;

    }

    /**
     * Obtain the MMS message read status
     */

    private String getMmsMsgReadStatus(int msgID) {
        String text = null;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
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
        return text;

    }

    /**
     * Obtain the MMS message read sent
     */

    private String getMmsMsgReadSent(int msgID) {
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

    private String getMmsMsgPriority(int msgID) {
        final int PRIORITY_LOW = 0X80;
        final int PRIORITY_NORMAL = 0X81;
        final int PRIORITY_HIGH = 0X82;

        String text = null;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor.getCount() > 0) {
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

        cursor.close();
        return text;

    }

    /**
     * Obtain the MMS message read protected
     */

    private String getMmsMsgProtected(int msgID) {
        String text = null;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
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
        return text;

    }

    /**
     * Obtain MMS message address
     */

    private String getMmsMsgAddress(int msgID) {
        String text = null;
        String whereClause = " address != \"insert-address-token\"";
        Uri uri = Uri.parse("content://mms/" + msgID + "/addr");
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            int addressInd = cursor.getColumnIndex("address");
            text = cursor.getString(addressInd);
        }
        cursor.close();
        return text;
    }

    /**
     * Get the folder name (MAP representation) based on the message Handle
     */
    private int getMmsContainingFolder(int msgID) {
        int folderNum = 0;
        String whereClause = " _id= " + msgID;
        Uri uri = Uri.parse("content://mms/");
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(uri, null, whereClause, null, null);
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
            folderName = Deleted;
            break;
        case 1:
            folderName = Inbox;
            break;
        case 2:
            folderName = Sent;
            break;
        case 3:
            folderName = Draft;
            break;
        case 4: // outbox
        case 5: // failed
        case 6: // queued
            folderName = Outbox;
            break;

        default:
            break;
        }
        return folderName;
    }


    /**
     * Build an MMS bMessage when given a message handle
     */
    private String bldMmsBmsg(int msgID, BluetoothMasMessageRsp rsp) {
        String bMessage = null;
        Cursor cr = null;
        Uri uri = Uri.parse("content://mms/");
        String whereClause = " _id = " + msgID;
        cr = context.getContentResolver().query(uri, null, whereClause, null,
                null);

        if (cr.getCount() > 0) {
            cr.moveToFirst();
            String containingFolder = getMmsMapVirtualFolderName((getMmsContainingFolder(msgID)));
            BmessageConsts bmsg = new BmessageConsts();

            // Create a bMessage

            // TODO Get Current type
            bmsg.setType("MMS");

            bmsg.setBmsg_version("1.0");
            if (cr.getString(cr.getColumnIndex("read")).equalsIgnoreCase("1")) {
                bmsg.setStatus("READ");
            } else {
                bmsg.setStatus("UNREAD");
            }

            bmsg.setFolder("TELECOM/MSG/" + containingFolder);

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

            boolean NO_MIME = false;
            boolean MIME = true;
            boolean msgFormat = NO_MIME;
            sb.append(bldMMSBody(bmsg, msgFormat, msgID));
            bmsg.setBody_msg(sb.toString());
            bmsg.setBody_length(sb.length() + 22);
            bmsg.setBody_encoding("8BIT");

            // Send a bMessage
            String str = mu.toBmessageMMS(bmsg);
            Log.d(TAG, str);
            Log.d(TAG, "\n\n");

            if (str != null && (str.length() > 0)) {
                final String FILENAME = "message";
                FileOutputStream bos = null;
                File file = new File(context.getFilesDir() + "/" + FILENAME);
                file.delete();

                try {
                    bos = context
                    .openFileOutput(FILENAME, Context.MODE_PRIVATE);

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

                File fileR = new File(context.getFilesDir() + "/" + FILENAME);
                if (fileR.exists() == true) {
                    rsp.file = fileR;
                    rsp.fractionDeliver = 1;
                }

            }
        }
        return bMessage;
    }

    private boolean isOutgoingMMSMessage( int mmsMsgID) {
        if ( getMmsMsgBox(mmsMsgID) == 1 ) {
            return false;
        }
        return true;
    }

    /**
     * This method constructs an MMS message that is added to the message list
     * which is used to construct a message listing
     */

    private MsgListingConsts bldMmsMsgLstItem(int mmsMsgID,
            BluetoothMasAppParams appParams, String folderName, String datetimeStr) {

        MsgListingConsts ml = new MsgListingConsts();

        // Set the message handle
        ml.setMsg_handle((mmsMsgID + MMS_HDLR_CONSTANT));

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
            if (getMmsMsgTxt(mmsMsgID) == null) {
                ml.setSize(0);
            } else {

                ml.setSize(getMmsMsgTxt(mmsMsgID).length());

            }
        }

        // Set message type
        if ((appParams.ParameterMask & BIT_TYPE) != 0) {
            ml.setType("MMS");
        }

        if ((appParams.ParameterMask & BIT_RECIPIENT_NAME) != 0) {
            // TODO "recipient_name" is the name of the
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
            // TODO In case of a SMS this is the recipient's
            // phone
            // number in canonical form (chapter 2.4.1 of [5])
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
            if (getMmsMsgReadStatus(mmsMsgID).equalsIgnoreCase("yes")) {
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
        if ((appParams.ParameterMask & BIT_SENT) != 0) {
            ml.setAttachment_size(getMmsMsgAttachSize(mmsMsgID));
        }

        return ml;
    }

    /**
     * Get the type (as in Mms ContentProvider) for the given table name
     */
    private int getMMSFolderType(String folder) {
        int type = 0;
        if (folder.equalsIgnoreCase(Inbox)) {
            type = 1;
        } else if (folder.equalsIgnoreCase(Sent)) {
            type = 2;
        } else if (folder.equalsIgnoreCase(Drafts)) {
            type = 3;
        } else if (folder.equalsIgnoreCase(Outbox)) {
            type = 4;
        } else if (folder.equalsIgnoreCase(Failed)) {
            type = 5;
        } else if (folder.equalsIgnoreCase(Queued)) {
            type = 6;
        }
        // Deleted will be folder 0
        return type;
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
        ContentResolver cr = context.getContentResolver();
        Cursor crID = cr.query(uri, null, null, null, null);
        if (crID.getCount() > 0) {
            crID.moveToFirst();
            int msgIDInd = crID.getColumnIndex("_id");
            handle = crID.getString(msgIDInd);
        }

        if (handle != null) {
            String whereClause = " _id= " + handle;
            uri = Uri.parse("content://mms");
            crID = cr.query(uri, null, whereClause, null, null);
            if (crID.getCount() > 0) {
                crID.moveToFirst();
                ContentValues values = new ContentValues();
                values.put("msg_box", 4);
                cr.update(uri, values, whereClause, null);
            }
        }
    }
    /**
     * This method is used to take a Bmessage that was pushed and move it to the
     * folder
     */
    private void moveMMStoDrafts(String mmsMsg) {

        String folder = "drafts";
        BmessageConsts bMsg = mu.fromBmessageMMS(mmsMsg);
        String Address = bMsg.getRecipientVcard_phone_number();
        String MmsText = bMsg.getBody_msg();

        /**
         * The PTS tester does not contain the same message format as CE4A This
         * code /* looks at the pushed message and checks for RPI-Messaging. If
         * it does not /* find it then it then it assumes PTS tester format
         */

        int beginPos = MmsText.indexOf("Content-Disposition:inline")
        + "Content-Disposition:inline".length();
        int endPos = MmsText.indexOf("--RPI-Messaging", beginPos);

        if (endPos < 0) {
            beginPos = 0;
            endPos = MmsText.length();
        }

        MmsText = (MmsText.substring(beginPos, endPos)).trim();

        ContentValues values = new ContentValues();
        values.put("msg_box", 3);
        values.put("thread_id", createMMSThread(Address));
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

        Uri uri = Uri.parse("content://mms/" + folder);
        ContentResolver cr = context.getContentResolver();
        uri = cr.insert(uri, values);

        String msgNum = uri.getLastPathSegment();
        int msgID = Integer.parseInt(msgNum);
        Log.d(TAG, " NEW URI " + uri.toString());

        // Build the \mms\part portion
        values.clear();

        values.put("seq", -1);
        values.put("ct", "application/smil");
        values.put("cid", "<smil>");
        values.put("cl", "smil.xml");
        values.put(
                "text",
        "<smil><head><layout><root-layout width=\"320px\" height=\"480px\"/><region id=\"Text\" left=\"0\" top=\"320\" width=\"320px\" height=\"160px\" fit=\"meet\"/></layout></head><body><par dur=\"5000ms\"><text src=\"text_0.txt\" region=\"Text\"/></par></body></smil>");

        uri = Uri.parse("content://mms/" + msgID + "/part");
        uri = cr.insert(uri, values);
        Log.d(TAG, " NEW URI " + uri.toString());

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
        values.put("text", MmsText);

        uri = Uri.parse("content://mms/" + msgID + "/part");
        uri = cr.insert(uri, values);
        Log.d(TAG, " NEW URI " + uri.toString());

        values.clear();
        values.put("contact_id", "null");
        values.put("address", "insert-address-token");
        values.put("type", 137);
        values.put("charset", 106);

        uri = Uri.parse("content://mms/" + msgID + "/addr");
        uri = cr.insert(uri, values);
        Log.d(TAG, " NEW URI " + uri.toString());

        values.clear();
        values.put("contact_id", "null");
        values.put("address", Address);
        values.put("type", 151);
        values.put("charset", 106);

        uri = Uri.parse("content://mms/" + msgID + "/addr");
        uri = cr.insert(uri, values);
        Log.d(TAG, " NEW URI " + uri.toString());

    }
    /**
     * Method to construct body of bmessage using either MIME or no MIME
     *
     */
    private String bldMMSBody(BmessageConsts bMsg, boolean msgType, int msgID) {
        boolean MIME = true;
        StringBuilder sb = new StringBuilder();
        String retString = null;
        if (msgType == MIME) {
            sb.append("To:").append(bMsg.recipient_vcard_phone_number)
            .append("\r\n");
            sb.append("Mime-Version: 1.0").append("\r\n");
            sb.append(
            "Content-Type: multipart/mixed; boundary=\"RPI-Messaging.42156.0\"")
            .append("\r\n");
            sb.append("Content-Transfer-Encoding: 7bit").append("\r\n")
            .append("\r\n");
            sb.append("MIME Message").append("\r\n");
            sb.append("--RPI-Messaging.42156.0").append("\r\n");
            sb.append("Content-Type: text/plain").append("\r\n");
            sb.append("Content-Transfer-Encoding: 8bit").append("\r\n");
            sb.append("Content-Disposition:inline").append("\r\n")
            .append("\r\n");
            sb.append(getMmsMsgTxt(msgID)).append("\r\n");
            sb.append("--RPI-Messaging.42156.0--").append("\r\n")
            .append("\r\n");
            retString = sb.toString();

        } else {
            sb.append("Subject:").append("Not Implemented").append("\r\n");
            sb.append("From:").append(bMsg.originator_vcard_phone_number)
            .append("\r\n");
            sb.append(getMmsMsgTxt(msgID)).append("\r\n").append("\r\n");
            retString = sb.toString();
        }
        return retString;
    }
    /**
     * Method to create a thread for a pushed MMS message
     *
     */

    private int createMMSThread(String address) {
        int returnValue = 1;
        ContentValues tempValue = new ContentValues();
        tempValue.put("address", address);
        tempValue.put("type", "20");
        Uri tempUri = context.getContentResolver().insert(
                Uri.parse("content://sms/"), tempValue);

        if (tempUri != null) {
            Cursor tempCr = context.getContentResolver().query(tempUri, null,
                    null, null, null);
            tempCr.moveToFirst();
            String newThreadIdStr = tempCr.getString(tempCr
                    .getColumnIndex("thread_id"));
            tempCr.close();
            returnValue = Integer.valueOf(newThreadIdStr);

            context.getContentResolver().delete(tempUri, null, null);
        }

        return returnValue;
    }

}
