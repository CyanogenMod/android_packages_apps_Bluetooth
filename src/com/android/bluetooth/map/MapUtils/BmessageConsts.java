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

public class BmessageConsts {

    public String bmsg_version = null;
    public String status = null;
    public String type = null;
    public String folder = null;
    public String vcard_version = null;
    public String recipient_vcard_name = null;
    public String recipient_vcard_phone_number = null;
    public String recipient_vcard_email = null;
    public String originator_vcard_name = null;
    public String originator_vcard_phone_number = null;
    public String originator_vcard_email = null;
    public String body_encoding = null;
    public int body_length = 0;
    public String body_msg = null;
    public String body_part_ID = null;
    public String body_language = null;
    public String body_charset = null;
    public String subject = null;

    // Setters and Getters

    public String getBody_language() {
        return body_language;
    }

    public void setBody_language(String body_language) {
        this.body_language = body_language;
    }

    public String getBody_part_ID() {
        return body_part_ID;
    }

    public void setBody_part_ID(String body_part_ID) {
        this.body_part_ID = body_part_ID;
    }

    public String getBmsg_version() {
        return bmsg_version;
    }

    public void setBmsg_version(String bmsg_version) {
        this.bmsg_version = bmsg_version;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public String getVcard_version() {
        return vcard_version;
    }

    public void setVcard_version(String vcard_version) {
        this.vcard_version = vcard_version;
    }

    // Name
    public String getRecipientVcard_name() {
        return recipient_vcard_name;
    }

    public String getOriginatorVcard_name() {
        return originator_vcard_name;
    }

    // recipient_vcard_name

    public void setRecipientVcard_name(String vcard_name) {
        this.recipient_vcard_name = vcard_name;
    }

    public void setOriginatorVcard_name(String vcard_name) {
        this.originator_vcard_name = vcard_name;
    }

    // Name

    // Phone
    public String getRecipientVcard_email() {
        return recipient_vcard_email;
    }

    public String getOriginatorVcard_email() {
        return originator_vcard_email;
    }

    public void setRecipientVcard_email(String email) {
        this.recipient_vcard_email = email;
    }

    public void setOriginatorVcard_email(String email) {
        this.originator_vcard_email = email;
    }

    // Phone
    //Email
    public String getRecipientVcard_phone_number() {
        return recipient_vcard_phone_number;
    }

    public String getOriginatorVcard_phone_number() {
        return originator_vcard_phone_number;
    }

    public void setRecipientVcard_phone_number(String vcard_phone_number) {
        this.recipient_vcard_phone_number = vcard_phone_number;
    }

    public void setOriginatorVcard_phone_number(String vcard_phone_number) {
        this.originator_vcard_phone_number = vcard_phone_number;
    }




    //end Email

    public String getBody_charset() {
        return body_charset;
    }

    public void setBody_charset(String body_charset) {
        this.body_charset = body_charset;
    }

    public String getBody_encoding() {
        return body_encoding;
    }

    public void setBody_encoding(String body_encoding) {
        this.body_encoding = body_encoding;
    }

    public int getBody_length() {
        return body_length;
    }

    public void setBody_length(int body_length) {
        this.body_length = body_length;
    }

    public String getBody_msg() {
        return body_msg;
    }

    public void setBody_msg(String body_msg) {
        this.body_msg = body_msg;
    }
    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

}
