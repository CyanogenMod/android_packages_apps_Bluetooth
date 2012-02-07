/************************************************************************************
 *
 *  Copyright (C) 2009-2012 Broadcom Corporation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ************************************************************************************/
package com.android.bluetooth.pbap;

import android.content.Context;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.pbap.BluetoothPbapService;
import com.android.vcard.VCardComposer;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.Profile;
import android.provider.ContactsContract.RawContactsEntity;


public class BluetoothPbapUtils {

    private static final String TAG = "FilterUtils";
    private static final boolean V = Log.isLoggable(BluetoothPbapService.LOG_TAG, Log.VERBOSE) ? true : false;

    public static boolean isProfileSet(Context context) {
        Cursor c = context.getContentResolver().query(
                Profile.CONTENT_VCARD_URI, new String[] { Profile._ID }, null,
                null, null);
        boolean isSet = (c != null && c.getCount() > 0);
        if (c != null) {
            c.close();
            c = null;
        }
        return isSet;
    }

    public static String getProfileName(Context context) {
        Cursor c = context.getContentResolver().query(
                Profile.CONTENT_URI, new String[] { Profile.DISPLAY_NAME}, null,
                null, null);
        String ownerName =null;
        if (c!= null && c.moveToFirst()) {
            ownerName = c.getString(0);
        }
        if (c != null) {
            c.close();
            c = null;
        }
        return ownerName;
    }
    public static final String createProfileVCard(Context ctx, final int vcardType,
            final long filter) {
        VCardComposer composer = null;
        String vcard = null;
        try {
            composer = new BluetoothPbapVcardComposer(ctx, vcardType, filter, true);
            if (composer
                    .init(Profile.CONTENT_URI, null, null, null, null, Uri
                            .withAppendedPath(Profile.CONTENT_URI,
                                    RawContactsEntity.CONTENT_URI
                                            .getLastPathSegment()))) {
                vcard = composer.createOneEntry();
            } else {
                Log.e(TAG,
                        "Unable to create profile vcard. Error initializing composer: "
                                + composer.getErrorReason());
            }
        } catch (Throwable t) {
            Log.e(TAG, "Unable to create profile vcard.", t);
        }
        if (composer != null) {
            try {
                composer.terminate();
            } catch (Throwable t) {

            }
        }
        return vcard;
    }

    public static boolean createProfileVCardFile(File file, Context context) {
        // File defaultFile = new
        // File(OppApplicationConfig.OPP_OWNER_VCARD_PATH);
        FileInputStream is = null;
        FileOutputStream os = null;
        boolean success = true;
        try {
            AssetFileDescriptor fd = context.getContentResolver()
                    .openAssetFileDescriptor(Profile.CONTENT_VCARD_URI, "r");

            if(fd == null)
            {
                return false;
            }
            is = fd.createInputStream();
            os = new FileOutputStream(file);
            Utils.copyStream(is, os, 200);
        } catch (Throwable t) {
            Log.e(TAG, "Unable to create default contact vcard file", t);
            success = false;
        }
        Utils.safeCloseStream(is);
        Utils.safeCloseStream(os);
        return success;
    }
}
