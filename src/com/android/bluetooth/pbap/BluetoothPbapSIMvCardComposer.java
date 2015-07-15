/* Copyright (c) 2010-2015, The Linux Foundation. All rights reserved.
 *
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
   the License.
 */
package com.android.bluetooth.pbap;

import com.android.bluetooth.R;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import com.android.vcard.VCardBuilder;
import com.android.vcard.VCardConfig;
import com.android.vcard.VCardConstants;
import com.android.vcard.VCardUtils;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Log;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;

/**
 * VCard composer especially for Call Log used in Bluetooth.
 */
public class BluetoothPbapSIMvCardComposer {
    private static final String TAG = "SIMvCardComposer";

    private static final String FAILURE_REASON_FAILED_TO_GET_DATABASE_INFO =
        "Failed to get database information";

    private static final String FAILURE_REASON_NO_ENTRY =
        "There's no exportable in the database";

    private static final String FAILURE_REASON_NOT_INITIALIZED =
        "The vCard composer object is not correctly initialized";

    /** Should be visible only from developers... (no need to translate, hopefully) */
    private static final String FAILURE_REASON_UNSUPPORTED_URI =
        "The Uri vCard composer received is not supported by the composer.";

    private static final String NO_ERROR = "No error";

    private final String SIM_URI = "content://icc/adn";

    private static final String[] SIM_PROJECTION = new String[] {
                Contacts.DISPLAY_NAME,
                CommonDataKinds.Phone.NUMBER,
                CommonDataKinds.Phone.TYPE,
                CommonDataKinds.Phone.LABEL};

    private static final int NAME_COLUMN_INDEX = 0;
    private static final int NUMBER_COLUMN_INDEX = 1;
    private static final int NUMBERTYPE_COLUMN_INDEX = 2;
    private static final int NUMBERLABEL_COLUMN_INDEX = 3;


    private final Context mContext;
    private ContentResolver mContentResolver;
    private Cursor mCursor;
    private boolean mTerminateIsCalled;
    private String mErrorReason = NO_ERROR;
    public BluetoothPbapSIMvCardComposer(final Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();
    }

    public boolean init(final Uri contentUri, final String selection,
            final String[] selectionArgs, final String sortOrder) {
            final Uri myUri = Uri.parse(SIM_URI);
        if (!myUri.equals(contentUri)) {

            mErrorReason = FAILURE_REASON_UNSUPPORTED_URI;
            return false;
        }

        mCursor = mContentResolver.query(
                contentUri, SIM_PROJECTION, null, null,sortOrder); //checkpoint Figure out if we can apply selection, projection and sort order.

        if (mCursor == null) {
            mErrorReason = FAILURE_REASON_FAILED_TO_GET_DATABASE_INFO;
            return false;
        }
        if (mCursor.getCount() == 0 || !mCursor.moveToFirst()) {
            try {
                mCursor.close();
            } catch (SQLiteException e) {
                Log.e(TAG, "SQLiteException on Cursor#close(): " + e.getMessage());
            } finally {
                mErrorReason = FAILURE_REASON_NO_ENTRY;
                mCursor = null;
            }
            return false;
        }

        return true;
    }


    public String createOneEntry(boolean vcardVer21) {
        if (mCursor == null || mCursor.isAfterLast()) {
            mErrorReason = FAILURE_REASON_NOT_INITIALIZED;
            return null;
        }
        try {
            return createOnevCardEntryInternal(vcardVer21);
        } finally {
            mCursor.moveToNext();
        }
    }

    private String createOnevCardEntryInternal(boolean vcardVer21) {
        final int vcardType = (vcardVer21 ? VCardConfig.VCARD_TYPE_V21_GENERIC :
                VCardConfig.VCARD_TYPE_V30_GENERIC) |
                VCardConfig.FLAG_REFRAIN_PHONE_NUMBER_FORMATTING;
        final VCardBuilder builder = new VCardBuilder(vcardType);
        String name = mCursor.getString(NAME_COLUMN_INDEX);
        if (TextUtils.isEmpty(name)) {
            name = mCursor.getString(NUMBER_COLUMN_INDEX);
        }
        final boolean needCharset = !(VCardUtils.containsOnlyPrintableAscii(name));
        builder.appendLine(VCardConstants.PROPERTY_FN, name, needCharset, false);
        builder.appendLine(VCardConstants.PROPERTY_N, name, needCharset, false);

        String number = mCursor.getString(NUMBER_COLUMN_INDEX);
        if (number.equals("-1")) {
            number = mContext.getString(R.string.unknownNumber);
        }

        // checkpoint Figure out what are the type and label
        final int type = mCursor.getInt(NUMBERTYPE_COLUMN_INDEX);
        String label = mCursor.getString(NUMBERLABEL_COLUMN_INDEX);
        if (TextUtils.isEmpty(label)) {
            label = Integer.toString(type);
        }
        builder.appendTelLine(type, label, number, false);
        return builder.toString();
    }

    public void terminate() {
        if (mCursor != null) {
            try {
                mCursor.close();
            } catch (SQLiteException e) {
                Log.e(TAG, "SQLiteException on Cursor#close(): " + e.getMessage());
            }
            mCursor = null;
        }

        mTerminateIsCalled = true;
    }

    @Override
    public void finalize() {
        if (!mTerminateIsCalled) {
            terminate();
        }
    }

    public int getCount() {
        if (mCursor == null) {
            return 0;
        }
        return mCursor.getCount();
    }

    public boolean isAfterLast() {
        if (mCursor == null) {
            return false;
        }
        return mCursor.isAfterLast();
    }

    public void moveToPosition(final int position, boolean sortalpha){
        if(mCursor == null) {
            return;
        }
        if(sortalpha) {
            setpositionbyalpha(position);
            return;
        }
        mCursor.moveToPosition(position);
    }

    public String getErrorReason() {
        return mErrorReason;
    }

    public void setpositionbyalpha(int position){
        if(mCursor == null) {
            return;
        }
        ArrayList<String> nameList = new ArrayList<String>();
        for (mCursor.moveToFirst(); !mCursor.isAfterLast(); mCursor
                        .moveToNext()) {
            String name = mCursor.getString(NAME_COLUMN_INDEX);
            if (TextUtils.isEmpty(name)) {
                name = mContext.getString(android.R.string.unknownName);
            }
            nameList.add(name);
        }

        Collections.sort(nameList, new Comparator <String> ()
                                 {@Override
                                  public int compare(String str1, String str2){
                                      return str1.compareToIgnoreCase(str2);
                                 }
        });

        for (mCursor.moveToFirst(); !mCursor.isAfterLast(); mCursor
                        .moveToNext()) {
            if(mCursor.getString(NAME_COLUMN_INDEX).equals(nameList.get(position))) {
              break;
            }

        }
    }

}


