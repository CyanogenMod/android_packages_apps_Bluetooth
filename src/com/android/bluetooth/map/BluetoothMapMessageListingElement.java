/*
* Copyright (C) 2013 Samsung System LSI
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
import java.io.StringWriter;

import java.text.SimpleDateFormat;
import java.util.Date;

import org.xmlpull.v1.XmlSerializer;

import android.telephony.PhoneNumberUtils;
import android.util.Log;
import android.util.Xml;

import com.android.bluetooth.map.BluetoothMapUtils.TYPE;

public class BluetoothMapMessageListingElement
    implements Comparable<BluetoothMapMessageListingElement> {

    private static final String TAG = "BluetoothMapMessageListingElement";
    private static final boolean D = true;
    private static final boolean V = true;

    private long cpHandle = 0; /* The content provider handle - without type information */
    private String mapHandle = null; /* The map hex-string handle with type information */
    private String subject = null;
    private long dateTime = 0;
    private String senderName = null;
    private String senderAddressing = null;
    private String replytoAddressing = null;
    private String recipientName = null;
    private String recipientAddressing = null;
    private TYPE type = null;
    private int size = -1;
    private String text = null;
    private String receptionStatus = null;
    private int attachmentSize = -1;
    private String priority = null;
    private String read = null;
    private String sent = null;
    private String protect = null;
    private boolean reportRead;
    public long getHandle() {
        return cpHandle;
    }

    public void setHandle(long handle, TYPE type) {
        this.cpHandle = handle;
        this.mapHandle = BluetoothMapUtils.getMapHandle(cpHandle, type);
    }

    public long getDateTime() {
        return dateTime;
    }

    public String getDateTimeString() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss");
        Date date = new Date(dateTime);
        return format.format(date); // Format to YYYYMMDDTHHMMSS local time
    }

    public void setDateTime(long dateTime) {
        this.dateTime = dateTime;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getSenderAddressing() {
        return senderAddressing;
    }

    public void setSenderAddressing(String senderAddressing) {
        /* TODO: This should depend on the type - for email, the addressing is an email address
         * Consider removing this again - to allow strings.
         */
        this.senderAddressing = PhoneNumberUtils.extractNetworkPortion(senderAddressing);
        if(this.senderAddressing == null || this.senderAddressing.length() < 2){
            this.senderAddressing = "11"; // Ensure we have at least two digits to
        }
    }

    public String getReplyToAddressing() {
        return replytoAddressing;
    }

    public void setReplytoAddressing(String replytoAddressing) {
        this.replytoAddressing = replytoAddressing;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }

    public String getRecipientAddressing() {
        return recipientAddressing;
    }

    public void setRecipientAddressing(String recipientAddressing) {
        this.recipientAddressing = recipientAddressing;
    }

    public TYPE getType() {
        return type;
    }

    public void setType(TYPE type) {
        this.type = type;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getReceptionStatus() {
        return receptionStatus;
    }

    public void setReceptionStatus(String receptionStatus) {
        this.receptionStatus = receptionStatus;
    }

    public int getAttachmentSize() {
        return attachmentSize;
    }

    public void setAttachmentSize(int attachmentSize) {
        this.attachmentSize = attachmentSize;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getRead() {
        return read;
    }

    public void setRead(String read, boolean reportRead) {
        this.read = read;
        this.reportRead = reportRead;
    }

    public String getSent() {
        return sent;
    }

    public void setSent(String sent) {
        this.sent = sent;
    }

    public String getProtect() {
        return protect;
    }

    public void setProtect(String protect) {
        this.protect = protect;
    }

    public int compareTo(BluetoothMapMessageListingElement e) {
        if (this.dateTime < e.dateTime) {
            return 1;
        } else if (this.dateTime > e.dateTime) {
            return -1;
        } else {
            return 0;
        }
    }

    /* Encode the MapMessageListingElement into the StringBuilder reference.
     * */
    public void encode(XmlSerializer xmlMsgElement) throws IllegalArgumentException, IllegalStateException, IOException
    {

            // contruct the XML tag for a single msg in the msglisting
            xmlMsgElement.startTag("", "msg");
            xmlMsgElement.attribute("", "handle", mapHandle);
            if(subject != null)
                xmlMsgElement.attribute("", "subject", subject);
            if(dateTime != 0)
                xmlMsgElement.attribute("", "datetime", this.getDateTimeString());
            if(senderName != null)
                xmlMsgElement.attribute("", "sender_name", senderName);
            if(senderAddressing != null)
                xmlMsgElement.attribute("", "sender_addressing", senderAddressing);
            if(replytoAddressing != null)
                xmlMsgElement.attribute("", "replyto_addressing",replytoAddressing);
            if(recipientName != null)
                xmlMsgElement.attribute("", "recipient_name",recipientName);
            if(recipientAddressing != null)
                xmlMsgElement.attribute("", "recipient_addressing", recipientAddressing);
            if(type != null)
                xmlMsgElement.attribute("", "type", type.name());
            if(size != -1)
                xmlMsgElement.attribute("", "size", Integer.toString(size));
            if(text != null)
                xmlMsgElement.attribute("", "text", text);
            if(receptionStatus != null)
                xmlMsgElement.attribute("", "reception_status", receptionStatus);
            if(attachmentSize != -1)
                xmlMsgElement.attribute("", "attachment_size", Integer.toString(attachmentSize));
            if(priority != null)
                xmlMsgElement.attribute("", "priority", priority);
            if(read != null && reportRead)
                xmlMsgElement.attribute("", "read", read);
            if(sent != null)
                xmlMsgElement.attribute("", "sent", sent);
            if(protect != null)
                xmlMsgElement.attribute("", "protect", protect);
            xmlMsgElement.endTag("", "msg");

    }
}


