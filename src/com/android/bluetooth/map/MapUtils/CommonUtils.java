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

import android.content.Context;
import android.text.format.Time;
import android.util.Log;
import android.util.TimeFormatException;

import com.android.bluetooth.map.BluetoothMasAppParams;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.obex.ResponseCodes;

public class CommonUtils {
    public static final String TAG = "CommonUtils";

    public static final ArrayList<String> FIXED_FOLDERS;

    static {
        FIXED_FOLDERS = new ArrayList<String>();
        FIXED_FOLDERS.add("inbox");
        FIXED_FOLDERS.add("sent");
        FIXED_FOLDERS.add("deleted");
        FIXED_FOLDERS.add("outbox");
        FIXED_FOLDERS.add("draft");
    }

    public static class BluetoothMasPushMsgRsp {
        public int response;
        public String msgHandle;
    }

    public static class BluetoothMasMessageListingRsp {
        public File file = null;
        public int msgListingSize = 0;
        public byte newMessage = 0;
        public int rsp = ResponseCodes.OBEX_HTTP_OK;
    }

    public static class BluetoothMasMessageRsp {
        public byte fractionDeliver = 0;
        public File file = null;
        public int rsp = ResponseCodes.OBEX_HTTP_OK;
    }

    public static class BluetoothMsgListRsp {
        public int messageListingSize = 0;
        public BluetoothMasMessageListingRsp rsp;
        public List<MsgListingConsts> msgList = new ArrayList<MsgListingConsts>();
    }

    public static String getFullPath(String child, Context context, List<String> folderList, String CurrentPath) {
        String tempPath = null;
        if (child != null) {
            if (CurrentPath == null) {
                if (child.equals("telecom")) {
                    // Telecom is fine
                    tempPath = "telecom";
                }
            }
            else if (CurrentPath.equals("telecom")) {
                if (child.equals("msg")) {
                    tempPath = CurrentPath + "/" + child;
                }
            }
            else if (CurrentPath.equals("telecom/msg")) {
                for (String folder : folderList) { //TODO NEED TO LOOK INTO THIS
                    if(child.toUpperCase().contains("GMAIL")){
                        if (folder.equalsIgnoreCase(child)
                                || folder.toUpperCase().contains(child.toUpperCase())) {
                            //added second condition above for gmail sent folder
                            tempPath = CurrentPath + "/" + folder;
                            break;
                        }
                    }
                    else{
                        if (folder.equalsIgnoreCase(child)) {
                            tempPath = CurrentPath + "/" + folder;
                            break;
                        }
                    }
                }
            }
        }
        return tempPath;
    }

    public static int validateFilterPeriods(BluetoothMasAppParams appParams) {
        int filterPeriodValid = -1;
        String periodStr = "";
        /* Filter Period Begin */
        if ((appParams.FilterPeriodBegin != null)
                && (appParams.FilterPeriodBegin.length() > 0)) {
            Time time = new Time();
            try {
                time.parse(appParams.FilterPeriodBegin.trim());
                if (periodStr.length() != 0) {
                        periodStr += " AND ";
                }
                periodStr += "date >= " + time.toMillis(false);
            } catch (TimeFormatException e) {
                Log.d(TAG, "Bad formatted FilterPeriodBegin "
                        + appParams.FilterPeriodBegin);
                filterPeriodValid = 0;
            }
        }

        /* Filter Period End */
        if ((appParams.FilterPeriodEnd != null)
                && (appParams.FilterPeriodEnd.length() > 0 )) {
            Time time = new Time();
            try {
                time.parse(appParams.FilterPeriodEnd.trim());
                if (periodStr.length() != 0) {
                        periodStr += " AND ";
                }
                periodStr += "date < " + time.toMillis(false);
            } catch (TimeFormatException e) {
                Log.d(TAG, "Bad formatted FilterPeriodEnd "
                        + appParams.FilterPeriodEnd);
                filterPeriodValid = 0;
            }
        }
        return filterPeriodValid;
    }
}
