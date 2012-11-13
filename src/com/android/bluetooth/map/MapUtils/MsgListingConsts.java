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

public class MsgListingConsts {

    public class MsgInfo {
        public String dateTime = null;

        public String getDateTime() {
            return dateTime;
        }

        public void setDateTime(String dateTime) {
            this.dateTime = dateTime;
        }

    }
    public MsgInfo msgInfo = new MsgInfo();

    public long msg_handle = 0;
    public String subject = null;
    public boolean  sendSubject = false;
    public String datetime = null;
    public String sender_name = null;
    public String sender_addressing = null;
    public String recepient_name = null;
    public boolean sendRecipient_addressing = false;
    public String recepient_addressing = null;
    public String type = null;
    public int size = 0;
    public String reception_status = null;
    public int attachment_size = -1;
    public String contains_text = null;
    public String priority = null;
    public String read = null;
    public String sent = null;
    public String msg_protected = null;
    public String replyto_addressing = null;

    public long getMsg_handle() {
        return msg_handle;
    }

    public void setMsg_handle(long msg_handle) {
        this.msg_handle = msg_handle;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public void setSendSubject(boolean flag) {
        this.sendSubject = flag;
    }

    public String getDatetime() {
        return datetime;
    }

    public void setDatetime(String datetime) {
        this.datetime = datetime;
    }

    public String getSender_name() {
        return sender_name;
    }

    public void setSender_name(String sender_name) {
        this.sender_name = sender_name;
    }

    public String getSender_addressing() {
        return sender_addressing;
    }

    public void setSender_addressing(String sender_addressing) {
        this.sender_addressing = sender_addressing;
    }

    public String getRecepient_name() {
        return recepient_name;
    }

    public void setRecepient_name(String recepient_name) {
        this.recepient_name = recepient_name;
    }

    public String getRecepient_addressing() {
        return recepient_addressing;
    }

    public void setSendRecipient_addressing(boolean flag) {
        this.sendRecipient_addressing = flag;
    }


    public void setRecepient_addressing(String recepient_addressing) {
        this.recepient_addressing = recepient_addressing;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public String getReception_status() {
        return reception_status;
    }

    public void setReception_status(String reception_status) {
        this.reception_status = reception_status;
    }

    public int getAttachment_size() {
        return attachment_size;
    }

    public void setAttachment_size(int attachment_size) {
        this.attachment_size = attachment_size;
    }

    public String getContains_text() {
        return contains_text;
    }

    public void setContains_text(String contains_text) {
        this.contains_text = contains_text;
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

    public void setRead(String read) {
        this.read = read;
    }

    public void setSent(String sent) {
        this.sent = sent;
    }

    public String getSent() {
        return sent;
    }

    public String getMsg_protected() {
        return msg_protected;
    }

    public void setMsg_protected(String msg_protected) {
        this.msg_protected = msg_protected;
    }

    public String getReplyTo_addressing() {
        return sender_addressing;
    }

    public void setReplyTo_addressing(String replyto_addressing) {
        this.replyto_addressing = replyto_addressing;
    }

}
