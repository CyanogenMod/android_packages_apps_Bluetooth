/*
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
 * the License.
 */
package com.android.bluetooth.pbap;

import com.android.bluetooth.R;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.pim.vcard.VCardBuilder;
import android.pim.vcard.VCardConfig;
import android.pim.vcard.VCardConstants;
import android.pim.vcard.VCardUtils;
import android.pim.vcard.VCardComposer.OneEntryHandler;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * VCard composer especially for Call Log used in Bluetooth.
 */
public class BluetoothPbapCallLogComposer {
    private static final String TAG = "CallLogComposer";

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

    /** The projection to use when querying the call log table */
    private static final String[] sCallLogProjection = new String[] {
            Calls.NUMBER, Calls.DATE, Calls.TYPE, Calls.CACHED_NAME, Calls.CACHED_NUMBER_TYPE,
            Calls.CACHED_NUMBER_LABEL
    };
    private static final int NUMBER_COLUMN_INDEX = 0;
    private static final int DATE_COLUMN_INDEX = 1;
    private static final int CALL_TYPE_COLUMN_INDEX = 2;
    private static final int CALLER_NAME_COLUMN_INDEX = 3;
    private static final int CALLER_NUMBERTYPE_COLUMN_INDEX = 4;
    private static final int CALLER_NUMBERLABEL_COLUMN_INDEX = 5;

    // Property for call log entry
    private static final String VCARD_PROPERTY_X_TIMESTAMP = "X-IRMC-CALL-DATETIME";
    private static final String VCARD_PROPERTY_CALLTYPE_INCOMING = "RECEIVED";
    private static final String VCARD_PROPERTY_CALLTYPE_OUTGOING = "DIALED";
    private static final String VCARD_PROPERTY_CALLTYPE_MISSED = "MISSED";

    private final Context mContext;
    private ContentResolver mContentResolver;
    private Cursor mCursor;
    private final boolean mCareHandlerErrors;

    private boolean mTerminateIsCalled;
    private final List<OneEntryHandler> mHandlerList;

    private String mErrorReason = NO_ERROR;

    public BluetoothPbapCallLogComposer(final Context context, boolean careHandlerErrors) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mCareHandlerErrors = careHandlerErrors;
        mHandlerList = new ArrayList<OneEntryHandler>();
    }

    public boolean init(final Uri contentUri, final String selection,
            final String[] selectionArgs, final String sortOrder) {
        final String[] projection;
        if (CallLog.Calls.CONTENT_URI.equals(contentUri)) {
            projection = sCallLogProjection;
        } else {
            mErrorReason = FAILURE_REASON_UNSUPPORTED_URI;
            return false;
        }

        mCursor = mContentResolver.query(
                contentUri, projection, selection, selectionArgs, sortOrder);

        if (mCursor == null) {
            mErrorReason = FAILURE_REASON_FAILED_TO_GET_DATABASE_INFO;
            return false;
        }

        if (mCareHandlerErrors) {
            List<OneEntryHandler> finishedList = new ArrayList<OneEntryHandler>(
                    mHandlerList.size());
            for (OneEntryHandler handler : mHandlerList) {
                if (!handler.onInit(mContext)) {
                    for (OneEntryHandler finished : finishedList) {
                        finished.onTerminate();
                    }
                    return false;
                }
            }
        } else {
            // Just ignore the false returned from onInit().
            for (OneEntryHandler handler : mHandlerList) {
                handler.onInit(mContext);
            }
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

    public void addHandler(OneEntryHandler handler) {
        if (handler != null) {
            mHandlerList.add(handler);
        }
    }

    public boolean createOneEntry(boolean vcardVer21) {
        if (mCursor == null || mCursor.isAfterLast()) {
            mErrorReason = FAILURE_REASON_NOT_INITIALIZED;
            return false;
        }

        final String vcard;
        try {
            vcard = createOneCallLogEntryInternal(vcardVer21);
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "OutOfMemoryError occured. Ignore the entry");
            System.gc();
            return true;
        } finally {
            mCursor.moveToNext();
        }

        if (mCareHandlerErrors) {
            for (OneEntryHandler handler : mHandlerList) {
                if (!handler.onEntryCreated(vcard)) {
                    return false;
                }
            }
        } else {
            for (OneEntryHandler handler : mHandlerList) {
                handler.onEntryCreated(vcard);
            }
        }

        return true;
    }

    private String createOneCallLogEntryInternal(boolean vcardVer21) {
        final int vcardType = (vcardVer21 ? VCardConfig.VCARD_TYPE_V21_GENERIC :
                VCardConfig.VCARD_TYPE_V30_GENERIC) |
                VCardConfig.FLAG_REFRAIN_PHONE_NUMBER_FORMATTING;
        final VCardBuilder builder = new VCardBuilder(vcardType);
        String name = mCursor.getString(CALLER_NAME_COLUMN_INDEX);
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
        final int type = mCursor.getInt(CALLER_NUMBERTYPE_COLUMN_INDEX);
        String label = mCursor.getString(CALLER_NUMBERLABEL_COLUMN_INDEX);
        if (TextUtils.isEmpty(label)) {
            label = Integer.toString(type);
        }
        builder.appendTelLine(type, label, number, false);
        tryAppendCallHistoryTimeStampField(builder);

        return builder.toString();
    }

    /**
     * This static function is to compose vCard for phone own number
     */
    public String composeVCardForPhoneOwnNumber(int phonetype, String phoneName,
            String phoneNumber, boolean vcardVer21) {
        final int vcardType = (vcardVer21 ?
                VCardConfig.VCARD_TYPE_V21_GENERIC :
                    VCardConfig.VCARD_TYPE_V30_GENERIC) |
                VCardConfig.FLAG_REFRAIN_PHONE_NUMBER_FORMATTING;
        final VCardBuilder builder = new VCardBuilder(vcardType);
        boolean needCharset = false;
        if (!(VCardUtils.containsOnlyPrintableAscii(phoneName))) {
            needCharset = true;
        }
        builder.appendLine(VCardConstants.PROPERTY_FN, phoneName, needCharset, false);
        builder.appendLine(VCardConstants.PROPERTY_N, phoneName, needCharset, false);

        if (!TextUtils.isEmpty(phoneNumber)) {
            String label = Integer.toString(phonetype);
            builder.appendTelLine(phonetype, label, phoneNumber, false);
        }

        return builder.toString();
    }

    /**
     * Format according to RFC 2445 DATETIME type.
     * The format is: ("%Y%m%dT%H%M%S").
     */
    private final String toRfc2455Format(final long millSecs) {
        Time startDate = new Time();
        startDate.set(millSecs);
        return startDate.format2445();
    }

    /**
     * Try to append the property line for a call history time stamp field if possible.
     * Do nothing if the call log type gotton from the database is invalid.
     */
    private void tryAppendCallHistoryTimeStampField(final VCardBuilder builder) {
        // Extension for call history as defined in
        // in the Specification for Ic Mobile Communcation - ver 1.1,
        // Oct 2000. This is used to send the details of the call
        // history - missed, incoming, outgoing along with date and time
        // to the requesting device (For example, transferring phone book
        // when connected over bluetooth)
        //
        // e.g. "X-IRMC-CALL-DATETIME;MISSED:20050320T100000"
        final int callLogType = mCursor.getInt(CALL_TYPE_COLUMN_INDEX);
        final String callLogTypeStr;
        switch (callLogType) {
            case Calls.INCOMING_TYPE: {
                callLogTypeStr = VCARD_PROPERTY_CALLTYPE_INCOMING;
                break;
            }
            case Calls.OUTGOING_TYPE: {
                callLogTypeStr = VCARD_PROPERTY_CALLTYPE_OUTGOING;
                break;
            }
            case Calls.MISSED_TYPE: {
                callLogTypeStr = VCARD_PROPERTY_CALLTYPE_MISSED;
                break;
            }
            default: {
                Log.w(TAG, "Call log type not correct.");
                return;
            }
        }

        final long dateAsLong = mCursor.getLong(DATE_COLUMN_INDEX);
        builder.appendLine(VCARD_PROPERTY_X_TIMESTAMP,
                Arrays.asList(callLogTypeStr), toRfc2455Format(dateAsLong));
    }

    public void terminate() {
        for (OneEntryHandler handler : mHandlerList) {
            handler.onTerminate();
        }

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

    public String getErrorReason() {
        return mErrorReason;
    }
}
