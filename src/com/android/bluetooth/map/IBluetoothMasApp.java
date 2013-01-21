/*
 * Copyright (c) 2011, Code Aurora Forum. All rights reserved.
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

import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMasMessageListingRsp;
import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMasMessageRsp;
import com.android.bluetooth.map.MapUtils.CommonUtils.BluetoothMasPushMsgRsp;
import com.android.bluetooth.map.MapUtils.MapUtils.BadRequestException;

import java.io.File;

public interface IBluetoothMasApp {
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

    /**
     * Message Access Profile SPEC V10
     * 3.1.1 Handle
     * The handle shall be a 64 bit unsigned integer whose value is defined by the MSE.
     * 7.1 SDP Interoperability Requirements
     * Up to 12 MAS Instances may be supported by a MSE device.
     * The value range of the MASInstanceID. shall be 0..255.
     * Although id range is 0..255, 0~11 are selected for conventional indexing
     */
    public static final long HANDLE_OFFSET[] = {
        0,          // MAS id 0
        1 << 59,    // MAS id 1
        2 << 59,    // MAS id 2
        3 << 59,    // MAS id 3
        4 << 59,    // MAS id 4
        5 << 59,    // MAS id 5
        6 << 59,    // MAS id 6
        7 << 59,    // MAS id 7
        8 << 59,    // MAS id 8
        9 << 59,    // MAS id 9
        10 << 59,   // MAS id 10
        11 << 59,   // MAS id 11
        Long.MAX_VALUE
    };

    public static final int EMAIL_MAX_PUSHMSG_SIZE = 409600;

    public static final int DELETED_THREAD_ID = -1;

    public static final String TELECOM = "telecom";
    public static final String MSG = "msg";

    public static final int MESSAGE_TYPE_EMAIL = 1 << 0;
    public static final int MESSAGE_TYPE_SMS_GSM = 1 << 1;
    public static final int MESSAGE_TYPE_SMS_CDMA = 1 << 2;
    public static final int MESSAGE_TYPE_MMS = 1 << 3;
    public static final int MESSAGE_TYPE_SMS = MESSAGE_TYPE_SMS_GSM | MESSAGE_TYPE_SMS_CDMA;
    public static final int MESSAGE_TYPE_SMS_MMS = MESSAGE_TYPE_SMS | MESSAGE_TYPE_MMS;

    public boolean setPath(boolean up, String name);
    public boolean checkPath(boolean up, String name, boolean setPathFlag);
    public int folderListingSize();
    public String folderListing(BluetoothMasAppParams appParam);
    //private String getFullPath(String child);
    public BluetoothMasMessageListingRsp msgListing(String name,
        BluetoothMasAppParams appParams);
    public BluetoothMasMessageRsp msg(String msgHandle,
        BluetoothMasAppParams bluetoothMasAppParams);
    public BluetoothMasPushMsgRsp pushMsg(String name, File file,
        BluetoothMasAppParams bluetoothMasAppParams) throws BadRequestException;
    public int msgStatus(String msgHandle, BluetoothMasAppParams bluetoothMasAppParams);
    public int msgUpdate();
    public void onConnect();
    public void onDisconnect();
    public int notification(BluetoothDevice remoteDevice,
        BluetoothMasAppParams bluetoothMasAppParams);
    public void startMnsSession(BluetoothDevice remoteDevice);
    public void stopMnsSession(BluetoothDevice remoteDevice);
    public int getMasId();
    public boolean checkPrecondition();

    public interface MessageNotificationListener {
        public static final String NEW_MESSAGE = "NewMessage";
        public static final String DELIVERY_SUCCESS = "DeliverySuccess";
        public static final String SENDING_SUCCESS = "SendingSuccess";
        public static final String DELIVERY_FAILURE = "DeliveryFailure";
        public static final String SENDING_FAILURE = "SendingFailure";
        public static final String MESSAGE_DELETED = "MessageDeleted";
        public static final String MESSAGE_SHIFT = "MessageShift";
        public void onNewMessage(int masId, String handle, String folder, String msgType);
        public void onDeliverySuccess(int masId, String handle, String folder, String msgType);
        public void onSendingSuccess(int masId, String handle, String folder, String msgType);
        public void onDeliveryFailure(int masId, String handle, String folder, String msgType);
        public void onSendingFailure(int masId, String handle, String folder, String msgType);
        public void onMessageDeleted(int masId, String handle, String folder, String msgType);
        public void onMessageShift(int masId, String handle, String toFolder, String fromFolder,
                String msgType);
    }

    public interface MnsRegister {
        public void register(MessageNotificationListener listener);
        public boolean isRegistered();
    }
}
