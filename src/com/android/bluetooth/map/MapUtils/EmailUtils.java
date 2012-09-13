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

package com.android.bluetooth.map.MapUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Set;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.format.Time;
import android.util.Log;
import android.util.TimeFormatException;

import com.android.bluetooth.map.BluetoothMasAppIf.BluetoothMasMessageRsp;
import com.android.bluetooth.map.BluetoothMasAppParams;


public class EmailUtils {

        public final String TAG = "EmailUtils";
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

    public static final int EMAIL_HDLR_CONSTANT = 200000;

    // TODO: Is this needed? public static int deletedEmailFolderId = -1;

    public List<String> folderListEmail(List<String> folderList, Context context) {
        String[] projection = new String[] {"displayName"};
        Uri uri = Uri.parse("content://com.android.email.provider/mailbox");
        Cursor cr = context.getContentResolver().query(uri, projection, null, null, null);

        if ( cr.moveToFirst()) {
                do {
                        Log.d(TAG, " Column Name: "+ cr.getColumnName(0) + " Value: " + cr.getString(0));
                        int folderFlag = 0;
                        for(int i=0; i< folderList.size(); i++){
                        if(folderList.get(i).equalsIgnoreCase(cr.getString(0))){
                            folderFlag = 1;
                            break;
                        }
                }
                        if(cr.getString(0).equalsIgnoreCase("Drafts")){ //TODO need to remove this hardcoded value
                                folderFlag = 1;
                        }
                        if(folderFlag == 0){
                                folderList.add(cr.getString(0));
                        }

            } while ( cr.moveToNext());
        }
        Log.d(TAG, " Folder Listing of SMS,MMS and EMAIL: "+folderList);

        return folderList;

    }

    public String getWhereIsQueryForTypeEmail(String folder, Context context) {

        Log.d(TAG, ":: Inside getWhereIsQueryForTypeEmail ::"+ folder);
        String query = "mailboxKey = -1";
        String folderId;
        Uri uri = Uri.parse("content://com.android.email.provider/mailbox");

        Cursor cr = context.getContentResolver().query(
                                uri, null, "(UPPER(displayName) = '"+ folder.toUpperCase()+"')" , null, null);

                if ( cr.moveToFirst()) {
                        do {
                                Log.d(TAG, ":: Inside getWhereIsQueryForTypeEmail Folder Name ::"+ cr.getString(cr.getColumnIndex("displayName")));
                        folderId = cr.getString(cr.getColumnIndex("_id"));
                        query = "mailboxKey = "+ folderId;
                        break;
                        } while ( cr.moveToNext());
                }

        return query;
    }

    public int getMessageSizeEmail(int messageId, Context context) {

        Log.d(TAG, ":: Inside getMessageSizeEmail ::"+ messageId);
        int msgSize = 0;
        String textContent;
        Uri uri = Uri.parse("content://com.android.email.provider/body");

                Cursor cr = context.getContentResolver().query(
                                uri, null, "messageKey = "+ messageId , null, null);

                if ( cr.moveToFirst()) {
                        do {
                        textContent = cr.getString(cr.getColumnIndex("textContent"));
                        msgSize = textContent.length();
                        break;
                        } while ( cr.moveToNext());
                }

        return msgSize;
    }

    public String getFolderName(String[] splitStringsEmail) {
        String folderName;
        Log.d(TAG, ":: Inside getFolderName ::"+ splitStringsEmail);

        if(splitStringsEmail[2].trim().equalsIgnoreCase("[Gmail]") || splitStringsEmail[2].trim().contains("Gmail")){
                folderName = splitStringsEmail[2]+"/"+splitStringsEmail[3];
        }
        else{
                folderName = splitStringsEmail[2];
        }

        Log.d(TAG, "folderName :: " + folderName);

        return folderName;
    }

    public String getConditionString(String folderName, Context context, BluetoothMasAppParams appParams) {
        String whereClauseEmail = getWhereIsQueryForTypeEmail(folderName, context);

        /* Filter readstatus: 0 no filtering, 0x01 get unread, 0x10 get read */
        if (appParams.FilterReadStatus != 0) {
                Log.d(TAG, "##Inside Filter Read Status.....##:");
                if ((appParams.FilterReadStatus & 0x1) != 0) {
                if (whereClauseEmail != "") {
                    whereClauseEmail += " AND ";
                }
                whereClauseEmail += " flagRead = 0 ";
            }
                Log.d(TAG, "##Filter Read Status Value##:"+appParams.FilterReadStatus);
                Log.d(TAG, "##Filter Read Status Condition Value##:"+(appParams.FilterReadStatus & 0x10));
                if ((appParams.FilterReadStatus & 0x10) != 0) {
                if (whereClauseEmail != "") {
                    whereClauseEmail += " AND ";
                }
                Log.d(TAG, "##Filter Read Status Value Appended to Query##:");
                whereClauseEmail += " flagRead = 1 ";
            }
        }

        Log.d(TAG, "##Filter Recipient Value##:"+appParams.FilterRecipient);
        Log.d(TAG, "##Filter Recipient Condition 1 ##:"+(appParams.FilterRecipient != null));
        Log.d(TAG, "##Filter Recipient Condition 2 ##:"+(appParams.FilterRecipient != ""));
        //Filter Recipient
        if ((appParams.FilterRecipient != null) && (appParams.FilterRecipient != "")  && (appParams.FilterRecipient.length() > 0)) {
                if (whereClauseEmail != "") {
                whereClauseEmail += " AND ";
            }
            whereClauseEmail += " toList LIKE '%"+appParams.FilterRecipient.trim()+"%'";
        }

        // TODO Filter Originator

        if ((appParams.FilterOriginator != null)
                && (appParams.FilterOriginator.length() != 0)) {

            // For incoming message
                if (whereClauseEmail != "") {
                whereClauseEmail += " AND ";
            }
                String str1 = appParams.FilterOriginator;
                whereClauseEmail += "fromList LIKE '%"+appParams.FilterOriginator.trim()+"%'";
        }
        Log.d(TAG, "##11 whereClauseEmail 11##:" + whereClauseEmail);
        // TODO Filter priority?

        /* Filter Period Begin */
        if ((appParams.FilterPeriodBegin != null)
                && (appParams.FilterPeriodBegin != "")) {
            Time time = new Time();

            try {
                time.parse(appParams.FilterPeriodBegin);
                if (whereClauseEmail != "") {
                    whereClauseEmail += " AND ";
                }
                whereClauseEmail += "timeStamp > " + time.toMillis(false);

            } catch (TimeFormatException e) {
                Log.d(TAG, "Bad formatted FilterPeriodBegin, Ignore"
                        + appParams.FilterPeriodBegin);
            }
        }

        /* Filter Period End */
        if ((appParams.FilterPeriodEnd != null)
                && (appParams.FilterPeriodEnd != "")) {
            Time time = new Time();
            try {
                time.parse(appParams.FilterPeriodEnd);
                if (whereClauseEmail != "") {
                    whereClauseEmail += " AND ";
                }
                whereClauseEmail += "timeStamp < " + time.toMillis(false);
            } catch (TimeFormatException e) {
                Log.d(TAG, "Bad formatted FilterPeriodEnd, Ignore"
                        + appParams.FilterPeriodEnd);
            }
        }

        return whereClauseEmail;
    }

    public MsgListingConsts bldEmailMsgLstItem(Context context, String folderName,
                BluetoothMasAppParams appParams, String subject, String timestamp,
                String senderName, String senderAddressing, String recipientName,
                String recipientAddressing, String msgId, String readStatus, String replyToStr) {

        MsgListingConsts emailMsg = new MsgListingConsts();
        emailMsg.setMsg_handle(Integer.valueOf(msgId)+ EMAIL_HDLR_CONSTANT);

        Time time = new Time();
        time.set(Long.valueOf(timestamp));

        String datetimeStr = time.toString().substring(0, 15);

        emailMsg.msgInfo.setDateTime(datetimeStr);

        if ((appParams.ParameterMask & BIT_SUBJECT) != 0) {

            Log.d(TAG, "Fileter Subject Length ::"+appParams.SubjectLength);
            if ((subject != null && appParams.SubjectLength > 0)
                    && (subject.length() > appParams.SubjectLength)) {
                 subject = subject.substring(0,
                        appParams.SubjectLength);
            }
            emailMsg.setSubject(subject.trim());
            emailMsg.sendSubject = true;
       }

        if ((appParams.ParameterMask & BIT_DATETIME) != 0) {
            // TODO Clarify if OFFSET is needed.
                emailMsg.setDatetime(datetimeStr);
        }

        if ((appParams.ParameterMask & BIT_SENDER_NAME) != 0) {
                if(senderName.contains("")){
                String[] senderStr = senderName.split("");
                if(senderStr !=null && senderStr.length > 0){
                    Log.d(TAG, " ::Sender name split String 0:: " + senderStr[0]
                            + "::Sender name split String 1:: " + senderStr[1]);
                    emailMsg.setSender_name(senderStr[1].trim());
                }
            }
            else{
                emailMsg.setSender_name(senderName.trim());
            }
       }

        if ((appParams.ParameterMask & BIT_SENDER_ADDRESSING) != 0) {
                if(senderAddressing.contains("")){
                String[] senderAddrStr = senderAddressing.split("");
                if(senderAddrStr !=null && senderAddrStr.length > 0){
                    Log.d(TAG, " ::Sender Addressing split String 0:: " + senderAddrStr[0]
                            + "::Sender Addressing split String 1:: " + senderAddrStr[1]);
                    emailMsg.setSender_addressing(senderAddrStr[0].trim());
                }
            }
            else{
                emailMsg.setSender_addressing(senderAddressing.trim());
            }

       }

        if ((appParams.ParameterMask & BIT_RECIPIENT_NAME) != 0) {
                String multiRecepients = null;

            if(recipientName.contains("")){
                String[] recepientStr = recipientName.split("");
                if(recepientStr !=null && recepientStr.length > 0){
                    Log.d(TAG, " ::Recepient name split String 0:: " + recepientStr[0]
                            + "::Recepient name split String 1:: " + recepientStr[1]);
                    emailMsg.setRecepient_name(recepientStr[1].trim());
                }
            }
            else if(recipientName.contains("")){
                multiRecepients = recipientName.replace('', ';');
                Log.d(TAG, " ::Recepient name :: " + multiRecepients);
                emailMsg.setRecepient_name(multiRecepients.trim());
            }
            else{
                emailMsg.setRecepient_name(recipientName.trim());
            }
        }

        if ((appParams.ParameterMask & BIT_RECIPIENT_ADDRESSING) != 0) {
                String multiRecepientAddrs = null;

            if(recipientAddressing.contains("")){
                String[] recepientAddrStr = recipientAddressing.split("");
                if(recepientAddrStr !=null && recepientAddrStr.length > 0){
                    Log.d(TAG, " ::Recepient addressing split String 0:: " + recepientAddrStr[0]
                            + "::Recepient addressing split String 1:: " + recepientAddrStr[1]);
                    emailMsg.setRecepient_addressing(recepientAddrStr[0].trim());
                }
            }
            else if(recipientAddressing.contains("")){
                multiRecepientAddrs = recipientAddressing.replace('', ';');
                Log.d(TAG, " ::Recepient Address:: " + multiRecepientAddrs);
                emailMsg.setRecepient_addressing(multiRecepientAddrs.trim());
           }
            else{
                emailMsg.setRecepient_addressing(recipientAddressing.trim());
            }
        }

        if ((appParams.ParameterMask & BIT_TYPE) != 0) {
                emailMsg.setType("EMAIL");
        }

        if ((appParams.ParameterMask & BIT_SIZE) != 0) {
                int  msgSize = 0;
                msgSize = getMessageSizeEmail(Integer.parseInt(msgId), context);
            emailMsg.setSize(msgSize);
        }

        if ((appParams.ParameterMask & BIT_RECEPTION_STATUS) != 0) {
            emailMsg.setReception_status("complete");
        }

        if ((appParams.ParameterMask & BIT_TEXT) != 0) {
            // TODO Set text to "yes"
            emailMsg.setContains_text("yes");
        }

        if ((appParams.ParameterMask & BIT_ATTACHMENT_SIZE) != 0) {
            emailMsg.setAttachment_size(0);
        }

        if ((appParams.ParameterMask & BIT_PRIORITY) != 0) {
            // TODO Get correct priority
            emailMsg.setPriority("no");
        }

        if ((appParams.ParameterMask & BIT_READ) != 0) {
                Log.d(TAG, " ::Read Status:: " + readStatus);
            if (readStatus.equalsIgnoreCase("1")) {
                emailMsg.setRead("yes");
            } else {
                emailMsg.setRead("no");
            }
        }

        if ((appParams.ParameterMask & BIT_SENT) != 0) {
                // TODO Get sent status?
            if (folderName.equalsIgnoreCase("sent") || folderName.toUpperCase().contains("SENT")) {
                emailMsg.setSent("yes");
            } else {
                emailMsg.setSent("no");
            }
        }

        if ((appParams.ParameterMask & BIT_PROTECTED) != 0) {
            emailMsg.setMsg_protected("no");
        }

        if ((appParams.ParameterMask & BIT_REPLYTO_ADDRESSING) != 0) {
                //TODO need to test
                Log.d(TAG, " ::Reply To addressing:: " + replyToStr);
            emailMsg.setReplyTo_addressing(replyToStr);
        }

        return emailMsg;
    }
    public String bldEmailBmsg(int msgHandle, BluetoothMasMessageRsp rsp, Context context, MapUtils mu) {
         Log.d(TAG, "Inside bldEmailBmsg:");
         String str = null;

        //Query the message table for obtaining the message related details
        Cursor cr1 = null;
        int folderId;
        String timeStamp = null;
        String subjectText = null;
        Uri uri1 = Uri.parse("content://com.android.email.provider/message");
        String whereClause = " _id = " + msgHandle;
        cr1 = context.getContentResolver().query(uri1, null, whereClause, null,
                null);

        if (cr1.getCount() > 0) {
            cr1.moveToFirst();
            folderId = cr1.getInt(cr1.getColumnIndex("mailboxKey"));
            String containingFolder = getContainingFolderEmail(folderId, context);
            timeStamp = cr1.getString(cr1.getColumnIndex("timeStamp"));
            subjectText = cr1.getString(cr1.getColumnIndex("subject"));
            BmessageConsts bmsg = new BmessageConsts();

            // Create a bMessage

            // TODO Get Current type
            bmsg.setType("EMAIL");

            bmsg.setBmsg_version("1.0");
            if (cr1.getString(cr1.getColumnIndex("flagRead")).equalsIgnoreCase("1")) {
                bmsg.setStatus("READ");
            } else {
                bmsg.setStatus("UNREAD");
            }

            bmsg.setFolder("TELECOM/MSG/" + containingFolder);

            bmsg.setVcard_version("2.1");

            String senderName = null;
            senderName = cr1.getString(cr1.getColumnIndex("fromList"));
            if(senderName.contains("")){
                String[] senderStr = senderName.split("");
                if(senderStr !=null && senderStr.length > 0){
                        bmsg.setOriginatorVcard_name(senderStr[1].trim());
                        bmsg.setOriginatorVcard_email(senderStr[0].trim());
                }
            }
            else{
                bmsg.setOriginatorVcard_name(senderName.trim());
                bmsg.setOriginatorVcard_email(senderName.trim());
            }

            String recipientName = null;
            String multiRecepients = null;
            recipientName = cr1.getString(cr1.getColumnIndex("toList"));
            if(recipientName.contains("")){
                String[] recepientStr = recipientName.split("");
                if(recepientStr !=null && recepientStr.length > 0){
                        bmsg.setRecipientVcard_name(recepientStr[1].trim());
                    bmsg.setRecipientVcard_email(recepientStr[0].trim());
                }
            }
            else if(recipientName.contains("")){
                multiRecepients = recipientName.replace('', ';');
                Log.d(TAG, " ::Recepient name :: " + multiRecepients);
                bmsg.setRecipientVcard_name(multiRecepients.trim());
                bmsg.setRecipientVcard_email(multiRecepients.trim());
            }
            else{
                bmsg.setRecipientVcard_name(recipientName.trim());
                bmsg.setRecipientVcard_email(recipientName.trim());
            }


            // TODO Set either Encoding or Native

            // TODO how to get body for MMS? This is for SMS only

            StringBuilder sb = new StringBuilder();
            Date date = new Date(Long.parseLong(timeStamp));
            sb.append("Date: ").append(date.toString()).append("\r\n");
            sb.append("To:").append(bmsg.getRecipientVcard_email()).append("\r\n");
            sb.append("From:").append(bmsg.getOriginatorVcard_email()).append("\r\n");
            sb.append("Subject:").append(subjectText).append("\r\n");

            sb.append("Mime-Version: 1.0").append("\r\n");
            sb.append(
                    "Content-Type: multipart/mixed; boundary=\"RPI-Messaging.123456789.0\"")
                    .append("\r\n");
            sb.append("Content-Transfer-Encoding: 7bit").append("\r\n")
                    .append("\r\n");
            sb.append("MIME Message").append("\r\n");
            sb.append("--RPI-Messaging.123456789.0").append("\r\n");
            sb.append("Content-Type: text/plain; charset=\"UTF-8\"").append("\r\n");
            sb.append("Content-Transfer-Encoding: 8bit").append("\r\n");
            sb.append("Content-Disposition:inline").append("\r\n")
                    .append("\r\n");

            //Query the body table for obtaining the message body
            Cursor cr2 = null;
            String emailBody = null;
            Uri uri2 = Uri.parse("content://com.android.email.provider/body");
            String whereStr = " messageKey = " + msgHandle;
            cr2 = context.getContentResolver().query(uri2, null, whereStr, null,
                    null);

            if (cr2.getCount() > 0) {
                cr2.moveToFirst();
                emailBody = cr2.getString(cr2.getColumnIndex("textContent"));
            }
            sb.append(emailBody).append("\r\n");

            sb.append("--RPI-Messaging.123456789.0--").append("\r\n");
            bmsg.setBody_msg(sb.toString());
            bmsg.setBody_length(sb.length() + 22);
            Log.d(TAG, "bMessageEmail test 44444444\n");
            // Send a bMessage
            Log.d(TAG, "bMessageEmail test\n");
            Log.d(TAG, "=======================\n\n");
            str = mu.toBmessageEmail(bmsg);
            Log.d(TAG, str);
            Log.d(TAG, "\n\n");
     }

        return str;
    }

    /**
     * Get the folder name (MAP representation) for Email based on the
     * mailboxKey value in message table
     */
    public String getContainingFolderEmail(int folderId, Context context) {
        Cursor cr;
        String folderName = null;
        String whereClause = "_id = " + folderId;
        cr = context.getContentResolver().query(
                Uri.parse("content://com.android.email.provider/mailbox"),
                null, whereClause, null, null);
        if (cr.getCount() > 0) {
            cr.moveToFirst();
            folderName = cr.getString(cr.getColumnIndex("displayName"));
            return folderName;
        }
        return null;
    }


}
