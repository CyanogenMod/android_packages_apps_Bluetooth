/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.opp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

/**
 * This class stores information about a single sending file It will only be
 * used for outbound share.
 */
public class BluetoothOppSendFileInfo {
    /** readable media file name */
    public final String mFileName;

    /** media file input stream */
    public final FileInputStream mInputStream;

    /** vCard string data */
    public final String mData;

    public final int mStatus;

    public final String mMimetype;

    public final long mLength;

    public final String mDestAddr;

    /** for media file */
    public BluetoothOppSendFileInfo(String fileName, String type, long length,
            FileInputStream inputStream, int status, String dest) {
        mFileName = fileName;
        mMimetype = type;
        mLength = length;
        mInputStream = inputStream;
        mStatus = status;
        mDestAddr = dest;
        mData = null;
    }

    /** for vCard, or later for vCal, vNote. Not used currently */
    public BluetoothOppSendFileInfo(String data, String type, long length, int status,
            String dest) {
        mFileName = null;
        mInputStream = null;
        mData = data;
        mMimetype = type;
        mLength = length;
        mStatus = status;
        mDestAddr = dest;
    }

    public static BluetoothOppSendFileInfo generateFileInfo(Context context, String uri,
            String type, String dest) {
        //TODO consider uri is a file:// uri
        ContentResolver contentResolver = context.getContentResolver();
        Uri u = Uri.parse(uri);
        String scheme = u.getScheme();
        String authority = u.getAuthority();
        String fileName = null;
        String contentType = null;
        long length = 0;
        if (scheme.equals("content") && authority.equals("media")) {

            contentType = contentResolver.getType(u);
            Cursor metadataCursor = contentResolver.query(u, new String[] {
                    OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
            }, null, null, null);
            if (metadataCursor != null) {
                try {
                    if (metadataCursor.moveToFirst()) {
                        fileName = metadataCursor.getString(0);
                        length = metadataCursor.getInt(1);
                    }
                } finally {
                    metadataCursor.close();
                }
            }
        } else if (scheme.equals("file")) {
            fileName = u.getLastPathSegment();
            contentType = type;
            File f = new File(u.getPath());
            length = f.length();
        } else {
            // currently don't accept other scheme
            return new BluetoothOppSendFileInfo(null, null, 0, null,
                    BluetoothShare.STATUS_FILE_ERROR, dest);
        }
        FileInputStream is;
        try {
            is = (FileInputStream)contentResolver.openInputStream(u);
        } catch (FileNotFoundException e) {
            return new BluetoothOppSendFileInfo(null, null, 0, null,
                    BluetoothShare.STATUS_FILE_ERROR, dest);
        }
        return new BluetoothOppSendFileInfo(fileName, contentType, length, is, 0, dest);
    }
}
