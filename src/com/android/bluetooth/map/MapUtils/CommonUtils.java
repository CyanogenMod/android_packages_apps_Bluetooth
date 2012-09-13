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

import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

import android.content.Context;

public class CommonUtils {

        public final String TAG = "CommonUtils";

        public String getFullPath(String child, Context context, List<String> folderList, String CurrentPath) {

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
            }
            else if (CurrentPath.equals("telecom")) {
                if (child.equals("msg")) {
                    tempPath = CurrentPath + "/" + child;
                }
            }
            else if (CurrentPath.equals("telecom/msg")) {
                for (String folder : completeFolderList) { //TODO NEED TO LOOK INTO THIS
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

}
