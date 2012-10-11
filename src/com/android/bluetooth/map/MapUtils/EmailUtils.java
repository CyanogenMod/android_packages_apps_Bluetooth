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

package com.android.bluetooth.map.MapUtils;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.Html;
import android.text.format.Time;
import android.util.Log;
import android.util.TimeFormatException;

import com.android.bluetooth.map.BluetoothMasAppParams;
import com.android.bluetooth.map.BluetoothMasService;
import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMasMessageRsp;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

public class EmailUtils {
    public static final String TAG = "EmailUtils";
    public static final boolean V = BluetoothMasService.VERBOSE;

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

    public static final String BMW = "BMW";

    public static List<String> folderListEmail(List<String> folderList, Context context) {
        String[] projection = new String[] {"displayName"};
        Uri uri = Uri.parse("content://com.android.email.provider/mailbox");
        Cursor cr = context.getContentResolver().query(uri, projection, null, null, null);

        if (cr != null && cr.moveToFirst()) {
            do {
                if (V){
                    Log.v(TAG, " Column Name: "+ cr.getColumnName(0) + " Value: " + cr.getString(0));
                }
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
        if (V){
            Log.v(TAG, " Folder Listing of SMS,MMS and EMAIL: "+folderList);
        }
        if (cr != null) {
            cr.close();
        }
        return folderList;
    }

    public static String getWhereIsQueryForTypeEmail(String folder, Context context, int masId) {
        String query = "mailboxKey = -1";
        String folderId;
        Uri uri = Uri.parse("content://com.android.email.provider/mailbox");
        if (folder == null) {
            return query;
        }
        if (folder.contains("'")){
            folder = folder.replace("'", "''");
        }
        Cursor cr = context.getContentResolver().query(
                uri, null, "(" + ACCOUNT_KEY + "=" + getAccountId(masId) +
                ") AND (UPPER(displayName) = '"+ folder.toUpperCase()+"')" , null, null);
        if (cr != null) {
            if ( cr.moveToFirst()) {
                do {
                    folderId = cr.getString(cr.getColumnIndex("_id"));
                    query = "mailboxKey = "+ folderId;
                    break;
                } while ( cr.moveToNext());
            }
            cr.close();
        }
        return query;
    }

    public static int getMessageSizeEmail(long messageId, Context context) {
        if (V){
            Log.v(TAG, ":: Message Id in getMessageSizeEmail ::"+ messageId);
        }
        int msgSize = 0;
        String textContent, htmlContent;
        Uri uri = Uri.parse("content://com.android.email.provider/body");

        Cursor cr = context.getContentResolver().query(
                uri, null, "messageKey = "+ messageId , null, null);

        if (cr != null && cr.moveToFirst()) {
            do {
                textContent = cr.getString(cr.getColumnIndex("textContent"));
                htmlContent = cr.getString(cr.getColumnIndex("htmlContent"));
                if(textContent != null && textContent.length() != 0){
                    msgSize = textContent.length();
                }
                else if(textContent == null){
                    if(htmlContent != null && htmlContent.length() != 0){
                        msgSize = htmlContent.length();
                    }
                }
                break;
            } while (cr.moveToNext());
        }
        if (cr != null) {
            cr.close();
        }
        return msgSize;
    }

    public static String getFolderName(String[] splitStringsEmail) {
        String folderName;
        if (V){
            Log.v(TAG, ":: Split Strings Array in getFolderName ::"+ splitStringsEmail);
        }
        if(splitStringsEmail[2].trim().equalsIgnoreCase("[Gmail]") || splitStringsEmail[2].trim().contains("Gmail")){
            folderName = splitStringsEmail[2]+"/"+splitStringsEmail[3];
        }
        else{
            folderName = splitStringsEmail[2];
        }
        if (V){
            Log.v(TAG, "folderName :: " + folderName);
        }
        return folderName;
    }

    public static String getConditionString(String folderName, Context context,
            BluetoothMasAppParams appParams, int masId) {
        String whereClauseEmail = getWhereIsQueryForTypeEmail(folderName, context, masId);

        /* Filter readstatus: 0 no filtering, 0x01 get unread, 0x10 get read */
        if (appParams.FilterReadStatus != 0) {
            if ((appParams.FilterReadStatus & 0x1) != 0) {
                if (whereClauseEmail.length() != 0) {
                    whereClauseEmail += " AND ";
                }
                whereClauseEmail += " flagRead = 0 ";
            }
            if (V){
                Log.v(TAG, "Filter Read Status Value:"+appParams.FilterReadStatus);
                Log.v(TAG, "Filter Read Status Condition Value:"+(appParams.FilterReadStatus & 0x02));
            }
            if ((appParams.FilterReadStatus & 0x02) != 0) {
                if (whereClauseEmail.length() != 0) {
                    whereClauseEmail += " AND ";
                }
                whereClauseEmail += " flagRead = 1 ";
            }
        }
        if (V){
            Log.v(TAG, "Filter Recipient Value:"+appParams.FilterRecipient);
            Log.v(TAG, "Filter Recipient Condition 1 :"+(appParams.FilterRecipient != null));
            if (appParams.FilterRecipient != null) {
                Log.v(TAG, "Filter Recipient Condition 2 :"+(appParams.FilterRecipient.length() != 0));
            }
        }
        //Filter Recipient
        if ((appParams.FilterRecipient != null) && (appParams.FilterRecipient.length() > 0)
                && !(appParams.FilterRecipient.equalsIgnoreCase("*"))) {
                if(appParams.FilterRecipient.contains("*")){
                    appParams.FilterRecipient = appParams.FilterRecipient.replace('*', '%');
                }
                if (whereClauseEmail.length() != 0) {
                    whereClauseEmail += " AND ";
                }
            whereClauseEmail += " toList LIKE '%"+appParams.FilterRecipient.trim()+"%'";
        }

        // TODO Filter Originator

        if ((appParams.FilterOriginator != null)
                && (appParams.FilterOriginator.length() > 0)
                && !(appParams.FilterOriginator.equalsIgnoreCase("*"))) {
                if(appParams.FilterOriginator.contains("*")){
                    appParams.FilterOriginator = appParams.FilterOriginator.replace('*', '%');
                }
            // For incoming message
            if (whereClauseEmail.length() != 0) {
                whereClauseEmail += " AND ";
            }
            String str1 = appParams.FilterOriginator;
            whereClauseEmail += "fromList LIKE '%"+appParams.FilterOriginator.trim()+"%'";
        }
        if (V){
            Log.v(TAG, "whereClauseEmail :" + whereClauseEmail);
        }// TODO Filter priority?

        /* Filter Period Begin */
        if ((appParams.FilterPeriodBegin != null)
                && (appParams.FilterPeriodBegin.length() != 0)) {
            Time time = new Time();

            try {
                time.parse(appParams.FilterPeriodBegin.trim());
                if (whereClauseEmail.length() != 0) {
                    whereClauseEmail += " AND ";
                }
                whereClauseEmail += "timeStamp >= " + time.toMillis(false);

            } catch (TimeFormatException e) {
                Log.d(TAG, "Bad formatted FilterPeriodBegin, Ignore"
                        + appParams.FilterPeriodBegin);
            }
        }

        /* Filter Period End */
        if ((appParams.FilterPeriodEnd != null)
                && (appParams.FilterPeriodEnd.length() != 0)) {
            Time time = new Time();
            try {
                time.parse(appParams.FilterPeriodEnd.trim());
                if (whereClauseEmail.length() != 0) {
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

    public static MsgListingConsts bldEmailMsgLstItem(Context context, String folderName,
                BluetoothMasAppParams appParams, String subject, String timestamp,
                String senderName, String senderAddressing, String recipientName,
                String recipientAddressing, String msgId, String readStatus, String replyToStr,
                long offset) {

        MsgListingConsts emailMsg = new MsgListingConsts();
        emailMsg.setMsg_handle(Long.valueOf(msgId)+ offset);

        Time time = new Time();
        time.set(Long.valueOf(timestamp));

        String datetimeStr = time.toString().substring(0, 15);

        emailMsg.msgInfo.setDateTime(datetimeStr);

        if (V){
            Log.v(TAG, "bldEmailMsgLstItem");
            Log.v(TAG, "Subject: " + subject);
            Log.v(TAG, "timestamp: " + timestamp);
            Log.v(TAG, "senderName: " + senderName);
            Log.v(TAG, "senderAddressing: " + senderAddressing);
            Log.v(TAG, "recipientName: " + recipientName);
            Log.v(TAG, "recipientAddressing: " + recipientAddressing);
            Log.v(TAG, "msgId: " + msgId);
            Log.v(TAG, "readStatus: " + readStatus);
            Log.v(TAG, "replyToStr: " + replyToStr);
            Log.v(TAG, "offset: " + offset);
        }

        if ((appParams.ParameterMask & BIT_SUBJECT) != 0) {

            if (V){
                Log.v(TAG, "bldEmailMsgLstItem :: Subject " + subject);
            }
            if ((subject != null && appParams.SubjectLength > 0)
                    && (subject.length() > appParams.SubjectLength)) {
                subject = subject.substring(0,
                        appParams.SubjectLength);
            }
            else if(subject != null){
                emailMsg.setSubject(subject.trim());
            }
            emailMsg.sendSubject = true;
       }

        if ((appParams.ParameterMask & BIT_DATETIME) != 0) {
            /*emailMsg.setDatetime(time.toString().substring(0, 15)
                    + "-0700");*/
            emailMsg.setDatetime(datetimeStr);
        }

        if ((appParams.ParameterMask & BIT_SENDER_NAME) != 0) {
            if(senderName != null) {
                if(senderName.contains("")){
                    String[] senderStr = senderName.split("");
                    if(senderStr !=null && senderStr.length > 0){
                        if (V){
                            Log.v(TAG, " ::Sender name split String 0:: " + senderStr[0]
                                    + "::Sender name split String 1:: " + senderStr[1]);
                        }
                        emailMsg.setSender_name(senderStr[1].trim());
                    }
                }
                else{
                    emailMsg.setSender_name(senderName.trim());
                }
            }
       }

        if ((appParams.ParameterMask & BIT_SENDER_ADDRESSING) != 0) {
            if(senderAddressing != null) {
                if(senderAddressing.contains("")){
                    String[] senderAddrStr = senderAddressing.split("");
                    if(senderAddrStr !=null && senderAddrStr.length > 0){
                        if (V){
                            Log.v(TAG, " ::Sender Addressing split String 0:: " + senderAddrStr[0]
                                    + "::Sender Addressing split String 1:: " + senderAddrStr[1]);
                        }
                        emailMsg.setSender_addressing(senderAddrStr[0].trim());
                    }
                }
                else{
                    emailMsg.setSender_addressing(senderAddressing.trim());
                }
            }
        }

        if ((appParams.ParameterMask & BIT_RECIPIENT_NAME) != 0) {
            String multiRecepients = "";
            if(recipientName != null){
                if(recipientName.contains("")){
                    List<String> recipientNameArr = new ArrayList<String>();
                    List<String> recipientEmailArr = new ArrayList<String>();
                    String[] multiRecipientStr = recipientName.split("");
                    for(int i=0; i < multiRecipientStr.length ; i++){
                        if(multiRecipientStr[i].contains("")){
                            String[] recepientStr = multiRecipientStr[i].split("");
                            recipientNameArr.add(recepientStr[1]);
                            recipientEmailArr.add(recepientStr[0]);
                        }
                    }
                    if(recipientNameArr != null && recipientNameArr.size() > 0){
                        for(int i=0; i < recipientNameArr.size() ; i++){
                            if(i < (recipientNameArr.size()-1)){
                                multiRecepients += recipientNameArr.get(i)+";";
                            }
                            else{
                                multiRecepients += recipientNameArr.get(i);
                            }
                        }
                    }
                    emailMsg.setRecepient_name(multiRecepients.trim());
                }
                else if(recipientName.contains("")){
                    String[] recepientStr = recipientName.split("");
                    if(recepientStr !=null && recepientStr.length > 0){
                        if (V){
                            Log.v(TAG, " ::Recepient name split String 0:: " + recepientStr[0]
                                    + "::Recepient name split String 1:: " + recepientStr[1]);
                        }
                        emailMsg.setRecepient_name(recepientStr[1].trim());
                    }
                }
                else{
                    emailMsg.setRecepient_name(recipientName.trim());
                }
            }
        }

        if ((appParams.ParameterMask & BIT_RECIPIENT_ADDRESSING) != 0) {
            String multiRecepientAddrs = "";

            if (recipientAddressing != null) {
                if (recipientAddressing.contains("")) {
                    List<String> recipientNameArr = new ArrayList<String>();
                    List<String> recipientEmailArr = new ArrayList<String>();
                    if  (recipientName != null) {
                        String[] multiRecipientStr = recipientName.split("");
                        for (int i=0; i < multiRecipientStr.length ; i++) {
                            if (multiRecipientStr[i].contains("")) {
                                String[] recepientStr = multiRecipientStr[i].split("");
                                recipientNameArr.add(recepientStr[1]);
                                recipientEmailArr.add(recepientStr[0]);
                            }
                        }
                    }
                    final int recipientEmailArrSize = recipientEmailArr.size();
                    if (recipientEmailArrSize > 0) {
                        for (int i=0; i < recipientEmailArrSize ; i++) {
                            if (i < (recipientEmailArrSize-1)) {
                                multiRecepientAddrs += recipientEmailArr.get(i)+";";
                            } else {
                                multiRecepientAddrs += recipientEmailArr.get(i);
                            }
                        }
                    }
                    emailMsg.setRecepient_addressing(multiRecepientAddrs.trim());
                    emailMsg.setSendRecipient_addressing(true);
                } else if (recipientAddressing.contains("")) {
                    String[] recepientAddrStr = recipientAddressing.split("");
                    if (recepientAddrStr !=null && recepientAddrStr.length > 0) {
                        if (V){
                            Log.v(TAG, " ::Recepient addressing split String 0:: " + recepientAddrStr[0]
                                    + "::Recepient addressing split String 1:: " + recepientAddrStr[1]);
                        }
                        emailMsg.setRecepient_addressing(recepientAddrStr[0].trim());
                        emailMsg.setSendRecipient_addressing(true);
                    }
                } else {
                    emailMsg.setRecepient_addressing(recipientAddressing.trim());
                    emailMsg.setSendRecipient_addressing(true);
                }
            }
        }

        if ((appParams.ParameterMask & BIT_TYPE) != 0) {
            emailMsg.setType("EMAIL");
        }

        if ((appParams.ParameterMask & BIT_SIZE) != 0) {
            int  msgSize = 0;
            msgSize = getMessageSizeEmail(Long.valueOf(msgId), context);
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
            if (V){
                Log.v(TAG, " ::Read Status:: " + readStatus);
            }
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
            if (V){
                Log.v(TAG, " ::Reply To addressing:: " + replyToStr);
            }
            if (replyToStr !=null && replyToStr.length() != 0){
                if (replyToStr.contains("")){
                    String replyToStrArr[] = replyToStr.split("");
                    replyToStr = replyToStrArr[0];
                }
                emailMsg.setReplyTo_addressing(replyToStr);
            } else{
                emailMsg.setReplyTo_addressing(emailMsg.getSender_addressing());
            }
        }
        return emailMsg;
    }

    public static String bldEmailBmsg(long msgHandle, BluetoothMasMessageRsp rsp, Context context,
            String remoteDeviceName) {
        String str = null;
        //Query the message table for obtaining the message related details
        Cursor cr1 = null;
        int folderId;
        String timeStamp = null;
        String subjectText = null;
        Uri uri1 = Uri.parse("content://com.android.email.provider/message");
        String whereClause = " _id = " + msgHandle;

        // Create a bMessage
        BmessageConsts bmsg = new BmessageConsts();

        cr1 = context.getContentResolver().query(uri1, null, whereClause, null,
                null);
        if (cr1 != null && cr1.getCount() > 0) {
            cr1.moveToFirst();
            folderId = cr1.getInt(cr1.getColumnIndex("mailboxKey"));
            String containingFolder = getContainingFolderEmail(folderId, context);
            timeStamp = cr1.getString(cr1.getColumnIndex("timeStamp"));
            subjectText = cr1.getString(cr1.getColumnIndex("subject"));

            // TODO Get Current type
            bmsg.setType("EMAIL");

            bmsg.setBmsg_version("1.0");
            if (cr1.getString(cr1.getColumnIndex("flagRead")).equalsIgnoreCase("1")) {
                bmsg.setStatus("READ");
            } else {
                bmsg.setStatus("UNREAD");
            }

            bmsg.setFolder(MapUtilsConsts.Telecom + "/" + MapUtilsConsts.Msg +
                        "/" + containingFolder);

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
                if (V){
                    Log.v(TAG, " ::Recepient name :: " + multiRecepients);
                }
                bmsg.setRecipientVcard_name(multiRecepients.trim());
                bmsg.setRecipientVcard_email(multiRecepients.trim());
            }
            else{
                bmsg.setRecipientVcard_name(recipientName.trim());
                bmsg.setRecipientVcard_email(recipientName.trim());
            }
        }
        if (cr1 != null) {
            cr1.close();
        }

        //Query the body table for obtaining the message body
        Cursor cr2 = null;
        Uri uri2 = Uri.parse("content://com.android.email.provider/body");
        String whereStr = " messageKey = " + msgHandle;
        cr2 = context.getContentResolver().query(uri2, null, whereStr, null,
                null);
        if (cr2 != null) {
            StringBuilder sb = new StringBuilder();
            String emailBody = null;

            if (cr2.getCount() > 0) {
                cr2.moveToFirst();
                emailBody = cr2.getString(cr2.getColumnIndex("textContent"));
                if (emailBody == null || emailBody.length() == 0){
                    String msgBody = cr2.getString(cr2.getColumnIndex("htmlContent"));
                    if (msgBody != null){
                        CharSequence msgText = Html.fromHtml(msgBody);
                        emailBody = msgText.toString();
                    }
                }
            }

            Date date = new Date(Long.parseLong(timeStamp));
            sb.append("From:").append(bmsg.getOriginatorVcard_email()).append("\r\n");
            sb.append("To:").append(bmsg.getRecipientVcard_email()).append("\r\n");
            if (remoteDeviceName != null && remoteDeviceName.startsWith(BMW)) {
                sb.append("Mime-Version: 1.0").append("\r\n");
                sb.append("Content-Type: text/plain; charset=\"UTF-8\"").append("\r\n");
                sb.append("Content-Transfer-Encoding: 8bit").append("\r\n");
                // BMW 14692 carkit accepts Date format in "EEE, dd MMM yyyy HH:mm:ss Z"
                sb.append("Date:");
                sb.append(new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z").format(date));
                sb.append("\r\n");
                sb.append("Subject:").append(subjectText).append("\r\n").append("\r\n");
                sb.append(emailBody).append("\r\n");
            } else {
                Random randomGenerator = new Random();
                int randomInt = randomGenerator.nextInt(1000);
                String boundary = "MessageBoundary."+randomInt;
                if (emailBody != null){
                    while (emailBody.contains(boundary)){
                        randomInt = randomGenerator.nextInt(1000);
                        boundary = "MessageBoundary."+randomInt;
                    }
                }
                sb.append("Date: ").append(date.toString()).append("\r\n");
                sb.append("Subject:").append(subjectText).append("\r\n");
                sb.append("Mime-Version: 1.0").append("\r\n");
                sb.append(
                        "Content-Type: multipart/mixed; boundary=\""+boundary+"\"")
                        .append("\r\n");
                sb.append("Content-Transfer-Encoding: 8bit").append("\r\n")
                        .append("\r\n");
                sb.append("MIME Message").append("\r\n");
                sb.append("--"+boundary).append("\r\n");
                sb.append("Content-Type: text/plain; charset=\"UTF-8\"").append("\r\n");
                sb.append("Content-Transfer-Encoding: 8bit").append("\r\n");
                sb.append("Content-Disposition:inline").append("\r\n")
                        .append("\r\n");
                sb.append(emailBody).append("\r\n");
                sb.append("--"+boundary+"--").append("\r\n");
            }

            bmsg.setBody_msg(sb.toString());
            bmsg.setBody_length(sb.length() + 22);
            // Send a bMessage
            if (V){
                Log.v(TAG, "bMessageEmail test\n");
                Log.v(TAG, "=======================\n\n");
            }
            str = MapUtils.toBmessageEmail(bmsg);
            if (V){
                Log.v(TAG, str);
                Log.v(TAG, "\n\n");
            }
            cr2.close();
        }
        return str;
    }

    /**
     * Get the folder name (MAP representation) for Email based on the
     * mailboxKey value in message table
     */
    public static String getContainingFolderEmail(int folderId, Context context) {
        Cursor cr;
        String folderName = null;
        String whereClause = "_id = " + folderId;
        cr = context.getContentResolver().query(
                Uri.parse("content://com.android.email.provider/mailbox"),
                null, whereClause, null, null);
        if (cr != null) {
            if (cr.getCount() > 0) {
                cr.moveToFirst();
                folderName = cr.getString(cr.getColumnIndex("displayName"));
            }
            cr.close();
        }
        return folderName;
    }

    public static final String AUTHORITY = "com.android.email.provider";
    public static final Uri EMAIL_URI = Uri.parse("content://" + AUTHORITY);
    public static final Uri EMAIL_ACCOUNT_URI = Uri.withAppendedPath(EMAIL_URI, "account");
    public static final Uri EMAIL_BOX_URI = Uri.withAppendedPath(EMAIL_URI, "mailbox");
    public static final Uri EMAIL_MESSAGE_URI = Uri.withAppendedPath(EMAIL_URI, "message");
    public static final String RECORD_ID = "_id";
    public static final String DISPLAY_NAME = "displayName";
    public static final String ACCOUNT_KEY = "accountKey";
    public static final String MAILBOX_KEY = "mailboxKey";
    public static final String EMAIL_ADDRESS = "emailAddress";
    public static final String IS_DEFAULT = "isDefault";
    public static final String TYPE = "type";
    public static final String[] EMAIL_BOX_PROJECTION = new String[] {
        RECORD_ID, DISPLAY_NAME, ACCOUNT_KEY, TYPE
    };
    public static final int EMAIL_BOX_COLUMN_RECORD_ID = 0;
    public static final int EMAIL_BOX_COLUMN_DISPLAY_NAME = 1;
    public static final int EMAIL_BOX_COLUMN_ACCOUNT_KEY = 2;
    public static final int EMAIL_BOX_COLUMN_TYPE = 3;
    public static final String[] EMAIL_MESSAGE_PROJECTION = new String[] {
        RECORD_ID, MAILBOX_KEY, ACCOUNT_KEY
    };
    public static final int MSG_COL_RECORD_ID = 0;
    public static final int MSG_COL_MAILBOX_KEY = 1;
    public static final int MSG_COL_ACCOUNT_KEY = 2;
    private static final String[] ACCOUNT_ID_PROJECTION = new String[] {
        RECORD_ID, EMAIL_ADDRESS, IS_DEFAULT
    };
    private static final String[] ACCOUNT_ID_NAME_PROJECTION = new String[] {
       RECORD_ID, EMAIL_ADDRESS, DISPLAY_NAME
    };
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

    public static HashMap<Long, Integer> sAccToMas = new HashMap<Long, Integer>();
    public static HashMap<Integer, Long> sMasToAcc = new HashMap<Integer, Long>();

    public static void clearMapTable() {
        sAccToMas.clear();
        sMasToAcc.clear();
    }

    public static void updateMapTable(long accountId, int masId) {
        if (sAccToMas.containsKey(accountId)) {
            sAccToMas.remove(accountId);
        }
        if (sMasToAcc.containsKey(masId)) {
            sMasToAcc.remove(masId);
        }
        sAccToMas.put(accountId, masId);
        sMasToAcc.put(masId, accountId);
    }

    public static long getAccountId(int masId) {
        Long accountId = sMasToAcc.get(masId);
        return (accountId != null) ? accountId : -1;
    }

    public static int getMasId(long accountId) {
        Integer masId = sAccToMas.get(accountId);
        return (masId != null) ? masId : -1;
    }

    public static void removeMasIdIfNotPresent(List<Long> accountIdList) {
        Collection<Long> oldList = sMasToAcc.values();
        ArrayList<Long> toRemove = new ArrayList<Long>();
        for (long oldId : oldList) {
            if (!accountIdList.contains(oldId)) {
                // remove it
                toRemove.add(oldId);
            }
        }
        for (long accountId : toRemove) {
            Integer masId = sAccToMas.remove(accountId);
            if (masId != null) {
                sMasToAcc.remove(masId);
            }
        }
    }

    public static int countEmailAccount(Context context) {
        return SqlHelper.count(context, EMAIL_ACCOUNT_URI, null, null);
    }

    /**
     * Returns whether Email account exists
     * @param context the calling Context
     * @return true if any Email account exists; false otherwise
     */
    public static boolean hasEmailAccount(Context context) {
        int numAccounts = SqlHelper.count(context, EMAIL_ACCOUNT_URI, null, null);
        if (numAccounts > 0) {
            return true;
        } else {
            return false;
        }
    }

    public static boolean hasEmailAccount(Context context, long accountId) {
        String where = RECORD_ID + "=" + accountId;
        int numAccounts = SqlHelper.count(context, EMAIL_ACCOUNT_URI, where, null);
        if (numAccounts > 0) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Returns the first Email account id that satisfies where condition
     * @param context the calling Context
     * @param where the condition respect to {@link #RECORD_ID}, {@link #EMAIL_ADDRESS}, {@link #IS_DEFAULT}
     * @return Email account id
     */
    public static long getEmailAccountId(Context context, String where) {
        if (V) Log.v(TAG, "getEmailAccountId(" + where + ")");
        long id = -1;
        Cursor cursor = context.getContentResolver().query(EMAIL_ACCOUNT_URI,
                ACCOUNT_ID_PROJECTION, where, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                id = cursor.getLong(0);
            }
            cursor.close();
        }
        if (V) Log.v(TAG, "id = " + id);
        return id;
    }

    /**
     * Returns the first Email account id name that satisfies where condition
     * @param context the calling Context
     * @param where the condition respect to {@link #RECORD_ID}, {@link #EMAIL_ADDRESS}, {@link #IS_DEFAULT}
     * @return Email account id Email
     */
    public static String getEmailAccountIdEmail(Context context, String where) {
        if (V) Log.v(TAG, "getEmailAccountIdName(" + where + ")");
        String idEmail = null;
        Cursor cursor = context.getContentResolver().query(EMAIL_ACCOUNT_URI,
                ACCOUNT_ID_NAME_PROJECTION, where, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                idEmail = cursor.getString(1);
            }
            cursor.close();
        }
        if (V) Log.v(TAG, "idEmail = " + idEmail);
        return idEmail;
    }

    /**
     * Returns the first Email account id name that satisfies where condition
     * @param context the calling Context
     * @param where the condition respect to {@link #RECORD_ID}, {@link #EMAIL_ADDRESS}, {@link #IS_DEFAULT}
     * @return Email account id Display Name
     */
    public static String getEmailAccountDisplayName(Context context, String where) {
        if (V) Log.v(TAG, "getEmailAccountIdName(" + where + ")");
        String displayName = null;
        Cursor cursor = context.getContentResolver().query(EMAIL_ACCOUNT_URI,
                ACCOUNT_ID_NAME_PROJECTION, where, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                displayName = cursor.getString(2);
            }
            cursor.close();
        }
        if (V) Log.v(TAG, "displayName = " + displayName);
        return displayName;
    }


    /**
     * Returns the default Email account id; the first account id if no default account
     * @param context the calling Context
     * @return the default Email account id
     */
    public static long getDefaultEmailAccountId(Context context) {
        if (V) Log.v(TAG, "getDefaultEmailAccountId()");
        long id = getEmailAccountId(context, IS_DEFAULT + "=1");
        if (id == -1) {
            id = getEmailAccountId(context, null);
        }
        if (V) Log.v(TAG, "id = " + id);
        return id;
    }

    public static List<Long> getEmailAccountIdList(Context context) {
        if (V) Log.v(TAG, "getEmailAccountIdList()");
        long id = -1;
        ArrayList<Long> list = new ArrayList<Long>();
        Cursor cursor = context.getContentResolver().query(EMAIL_ACCOUNT_URI,
                ACCOUNT_ID_PROJECTION, null, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    id = cursor.getLong(0);
                    list.add(id);
                    if (V) Log.v(TAG, "id = " + id);
                } while (cursor.moveToNext());
            }
            cursor.close();
        }
        return list;
    }

    /**
     * Return Email folder list for the id
     * @param context the calling Context
     * @param id the email account id
     * @return the list of Email folder
     */
    public static List<String> getEmailFolderList(Context context, long id) {
        if (V) Log.v(TAG, "getEmailFolderList: id = " + id);
        StringBuilder sb = new StringBuilder();
        if (id > 0) {
            sb.append(ACCOUNT_KEY);
            sb.append("=");
            sb.append(id);
        }
        return SqlHelper.getListForColumn(context, EMAIL_BOX_URI, DISPLAY_NAME, sb.toString(), null);
    }

    /**
     * Return folder name for the type of mailbox
     * @param context the calling Context
     * @param id the email account id
     * @param type
     * @return
     */
    public static String getFolderForType(Context context, long id, int type) {
        if (V) Log.v(TAG, "getFolderForType: id = " + id + ", type = " + type);
        StringBuilder sb = new StringBuilder();
        if (id > 0) {
            sb.append(ACCOUNT_KEY);
            sb.append("=");
            sb.append(id);
            sb.append(" AND ");
        }
        sb.append(TYPE);
        sb.append("=");
        sb.append(type);
        return SqlHelper.getFirstValueForColumn(context, EMAIL_BOX_URI, DISPLAY_NAME, sb.toString(), null);
    }

    /**
     * Return list of folder names for the type of mailbox
     * @param context the calling Context
     * @param id the email account id
     * @param type
     * @return
     */
    public static List<String> getFoldersForType(Context context, long id, int type) {
        if (V) Log.v(TAG, "getFolderForType: id = " + id + ", type = " + type);
        StringBuilder sb = new StringBuilder();
        if (id > 0) {
            sb.append(ACCOUNT_KEY);
            sb.append("=");
            sb.append(id);
            sb.append(" AND ");
        }
        sb.append(TYPE);
        sb.append("=");
        sb.append(type);
        return SqlHelper.getListForColumn(context, EMAIL_BOX_URI, DISPLAY_NAME, sb.toString(), null);
    }

    public static int getTypeForFolder(Context context, long id, String folderName) {
        if (V) Log.v(TAG, "getTypeForFolder: id = " + id + ", folderName = " + folderName);
        StringBuilder sb = new StringBuilder();
        if (id > 0) {
            sb.append(ACCOUNT_KEY);
            sb.append("=");
            sb.append(id);
            sb.append(" AND ");
        }
        sb.append(DISPLAY_NAME);
        sb.append("=");
        sb.append("'"+folderName+"'");
        return SqlHelper.getFirstIntForColumn(context, EMAIL_BOX_URI, TYPE, sb.toString(), null);
    }
}
