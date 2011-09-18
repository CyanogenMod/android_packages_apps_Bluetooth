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

package com.android.bluetooth.pbap;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.PhoneLookup;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import com.android.bluetooth.R;
import com.android.vcard.VCardComposer;
import com.android.vcard.VCardConfig;
import com.android.internal.telephony.CallerInfo;
import com.android.vcard.VCardPhoneNumberTranslationCallback;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;

import javax.obex.ServerOperation;
import javax.obex.Operation;
import javax.obex.ResponseCodes;

public class BluetoothPbapVcardManager {
    private static final String TAG = "BluetoothPbapVcardManager";

    private static final boolean V = BluetoothPbapService.VERBOSE;

    private ContentResolver mResolver;

    private Context mContext;

    static final String[] PHONES_PROJECTION = new String[] {
            Data._ID, // 0
            CommonDataKinds.Phone.TYPE, // 1
            CommonDataKinds.Phone.LABEL, // 2
            CommonDataKinds.Phone.NUMBER, // 3
            Contacts.DISPLAY_NAME, // 4
    };

    private static final int PHONE_NUMBER_COLUMN_INDEX = 3;

    static final String SORT_ORDER_PHONE_NUMBER = CommonDataKinds.Phone.NUMBER + " ASC";

    static final String[] CONTACTS_PROJECTION = new String[] {
            Contacts._ID, // 0
            Contacts.DISPLAY_NAME, // 1
    };

    static final int CONTACTS_ID_COLUMN_INDEX = 0;

    static final int CONTACTS_NAME_COLUMN_INDEX = 1;

    // call histories use dynamic handles, and handles should order by date; the
    // most recently one should be the first handle. In table "calls", _id and
    // date are consistent in ordering, to implement simply, we sort by _id
    // here.
    static final String CALLLOG_SORT_ORDER = Calls._ID + " DESC";

    private static final String CLAUSE_ONLY_VISIBLE = Contacts.IN_VISIBLE_GROUP + "=1";

    public BluetoothPbapVcardManager(final Context context) {
        mContext = context;
        mResolver = mContext.getContentResolver();
    }

    public final String getOwnerPhoneNumberVcard(final boolean vcardType21) {
        BluetoothPbapCallLogComposer composer = new BluetoothPbapCallLogComposer(mContext);
        String name = BluetoothPbapService.getLocalPhoneName();
        String number = BluetoothPbapService.getLocalPhoneNum();
        String vcard = composer.composeVCardForPhoneOwnNumber(Phone.TYPE_MOBILE, name, number,
                vcardType21);
        return vcard;
    }

    public final int getPhonebookSize(final int type) {
        int size;
        switch (type) {
            case BluetoothPbapObexServer.ContentType.PHONEBOOK:
                size = getContactsSize();
                break;
            default:
                size = getCallHistorySize(type);
                break;
        }
        if (V) Log.v(TAG, "getPhonebookSzie size = " + size + " type = " + type);
        return size;
    }

    public final int getContactsSize() {
        final Uri myUri = Contacts.CONTENT_URI;
        int size = 0;
        Cursor contactCursor = null;
        try {
            contactCursor = mResolver.query(myUri, null, CLAUSE_ONLY_VISIBLE, null, null);
            if (contactCursor != null) {
                size = contactCursor.getCount() + 1; // always has the 0.vcf
            }
        } finally {
            if (contactCursor != null) {
                contactCursor.close();
            }
        }
        return size;
    }

    public final int getCallHistorySize(final int type) {
        final Uri myUri = CallLog.Calls.CONTENT_URI;
        String selection = BluetoothPbapObexServer.createSelectionPara(type);
        int size = 0;
        Cursor callCursor = null;
        try {
            callCursor = mResolver.query(myUri, null, selection, null,
                    CallLog.Calls.DEFAULT_SORT_ORDER);
            if (callCursor != null) {
                size = callCursor.getCount();
            }
        } finally {
            if (callCursor != null) {
                callCursor.close();
            }
        }
        return size;
    }

    public final ArrayList<String> loadCallHistoryList(final int type) {
        final Uri myUri = CallLog.Calls.CONTENT_URI;
        String selection = BluetoothPbapObexServer.createSelectionPara(type);
        String[] projection = new String[] {
                Calls.NUMBER, Calls.CACHED_NAME
        };
        final int CALLS_NUMBER_COLUMN_INDEX = 0;
        final int CALLS_NAME_COLUMN_INDEX = 1;

        Cursor callCursor = null;
        ArrayList<String> list = new ArrayList<String>();
        try {
            callCursor = mResolver.query(myUri, projection, selection, null,
                    CALLLOG_SORT_ORDER);
            if (callCursor != null) {
                for (callCursor.moveToFirst(); !callCursor.isAfterLast();
                        callCursor.moveToNext()) {
                    String name = callCursor.getString(CALLS_NAME_COLUMN_INDEX);
                    if (TextUtils.isEmpty(name)) {
                        // name not found, use number instead
                        name = callCursor.getString(CALLS_NUMBER_COLUMN_INDEX);
                        if (CallerInfo.UNKNOWN_NUMBER.equals(name) ||
                                CallerInfo.PRIVATE_NUMBER.equals(name) ||
                                CallerInfo.PAYPHONE_NUMBER.equals(name)) {
                            name = mContext.getString(R.string.unknownNumber);
                        }
                    }
                    list.add(name);
                }
            }
        } finally {
            if (callCursor != null) {
                callCursor.close();
            }
        }
        return list;
    }

    public final ArrayList<String> getPhonebookNameList(final int orderByWhat) {
        ArrayList<String> nameList = new ArrayList<String>();
        nameList.add(BluetoothPbapService.getLocalPhoneName());

        final Uri myUri = Contacts.CONTENT_URI;
        Cursor contactCursor = null;
        try {
            if (orderByWhat == BluetoothPbapObexServer.ORDER_BY_INDEXED) {
                if (V) Log.v(TAG, "getPhonebookNameList, order by index");
                contactCursor = mResolver.query(myUri, CONTACTS_PROJECTION, CLAUSE_ONLY_VISIBLE,
                        null, Contacts._ID);
            } else if (orderByWhat == BluetoothPbapObexServer.ORDER_BY_ALPHABETICAL) {
                if (V) Log.v(TAG, "getPhonebookNameList, order by alpha");
                contactCursor = mResolver.query(myUri, CONTACTS_PROJECTION, CLAUSE_ONLY_VISIBLE,
                        null, Contacts.DISPLAY_NAME);
            }
            if (contactCursor != null) {
                for (contactCursor.moveToFirst(); !contactCursor.isAfterLast(); contactCursor
                        .moveToNext()) {
                    String name = contactCursor.getString(CONTACTS_NAME_COLUMN_INDEX);
                    if (TextUtils.isEmpty(name)) {
                        name = mContext.getString(android.R.string.unknownName);
                    }
                    nameList.add(name);
                }
            }
        } finally {
            if (contactCursor != null) {
                contactCursor.close();
            }
        }
        return nameList;
    }

    public final ArrayList<String> getContactNamesByNumber(final String phoneNumber) {
        ArrayList<String> nameList = new ArrayList<String>();

        Cursor contactCursor = null;
        Uri uri = null;

        if (phoneNumber != null && phoneNumber.length() == 0) {
            uri = Contacts.CONTENT_URI;
        } else {
            uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber));
        }

        try {
            contactCursor = mResolver.query(uri, CONTACTS_PROJECTION, CLAUSE_ONLY_VISIBLE,
                        null, Contacts._ID);

            if (contactCursor != null) {
                for (contactCursor.moveToFirst(); !contactCursor.isAfterLast(); contactCursor
                        .moveToNext()) {
                    String name = contactCursor.getString(CONTACTS_NAME_COLUMN_INDEX);
                    long id = contactCursor.getLong(CONTACTS_ID_COLUMN_INDEX);
                    if (TextUtils.isEmpty(name)) {
                        name = mContext.getString(android.R.string.unknownName);
                    }
                    if (V) Log.v(TAG, "got name " + name + " by number " + phoneNumber + " @" + id);
                    nameList.add(name);
                }
            }
        } finally {
            if (contactCursor != null) {
                contactCursor.close();
            }
        }
        return nameList;
    }

    public final int composeAndSendCallLogVcards(final int type, Operation op,
            final int startPoint, final int endPoint, final boolean vcardType21) {
        if (startPoint < 1 || startPoint > endPoint) {
            Log.e(TAG, "internal error: startPoint or endPoint is not correct.");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        String typeSelection = BluetoothPbapObexServer.createSelectionPara(type);

        final Uri myUri = CallLog.Calls.CONTENT_URI;
        final String[] CALLLOG_PROJECTION = new String[] {
            CallLog.Calls._ID, // 0
        };
        final int ID_COLUMN_INDEX = 0;

        Cursor callsCursor = null;
        long startPointId = 0;
        long endPointId = 0;
        try {
            // Need test to see if order by _ID is ok here, or by date?
            callsCursor = mResolver.query(myUri, CALLLOG_PROJECTION, typeSelection, null,
                    CALLLOG_SORT_ORDER);
            if (callsCursor != null) {
                callsCursor.moveToPosition(startPoint - 1);
                startPointId = callsCursor.getLong(ID_COLUMN_INDEX);
                if (V) Log.v(TAG, "Call Log query startPointId = " + startPointId);
                if (startPoint == endPoint) {
                    endPointId = startPointId;
                } else {
                    callsCursor.moveToPosition(endPoint - 1);
                    endPointId = callsCursor.getLong(ID_COLUMN_INDEX);
                }
                if (V) Log.v(TAG, "Call log query endPointId = " + endPointId);
            }
        } finally {
            if (callsCursor != null) {
                callsCursor.close();
            }
        }

        String recordSelection;
        if (startPoint == endPoint) {
            recordSelection = Calls._ID + "=" + startPointId;
        } else {
            // The query to call table is by "_id DESC" order, so change
            // correspondingly.
            recordSelection = Calls._ID + ">=" + endPointId + " AND " + Calls._ID + "<="
                    + startPointId;
        }

        String selection;
        if (typeSelection == null) {
            selection = recordSelection;
        } else {
            selection = "(" + typeSelection + ") AND (" + recordSelection + ")";
        }

        if (V) Log.v(TAG, "Call log query selection is: " + selection);

        return composeAndSendVCards(op, selection, vcardType21, null, false);
    }

    public final int composeAndSendPhonebookVcards(Operation op, final int startPoint,
            final int endPoint, final boolean vcardType21, String ownerVCard) {
        if (startPoint < 1 || startPoint > endPoint) {
            Log.e(TAG, "internal error: startPoint or endPoint is not correct.");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        final Uri myUri = Contacts.CONTENT_URI;

        Cursor contactCursor = null;
        long startPointId = 0;
        long endPointId = 0;
        try {
            contactCursor = mResolver.query(myUri, CONTACTS_PROJECTION, CLAUSE_ONLY_VISIBLE, null,
                    Contacts._ID);
            if (contactCursor != null) {
                contactCursor.moveToPosition(startPoint - 1);
                startPointId = contactCursor.getLong(CONTACTS_ID_COLUMN_INDEX);
                if (V) Log.v(TAG, "Query startPointId = " + startPointId);
                if (startPoint == endPoint) {
                    endPointId = startPointId;
                } else {
                    contactCursor.moveToPosition(endPoint - 1);
                    endPointId = contactCursor.getLong(CONTACTS_ID_COLUMN_INDEX);
                }
                if (V) Log.v(TAG, "Query endPointId = " + endPointId);
            }
        } finally {
            if (contactCursor != null) {
                contactCursor.close();
            }
        }

        final String selection;
        if (startPoint == endPoint) {
            selection = Contacts._ID + "=" + startPointId + " AND " + CLAUSE_ONLY_VISIBLE;
        } else {
            selection = Contacts._ID + ">=" + startPointId + " AND " + Contacts._ID + "<="
                    + endPointId + " AND " + CLAUSE_ONLY_VISIBLE;
        }

        if (V) Log.v(TAG, "Query selection is: " + selection);

        return composeAndSendVCards(op, selection, vcardType21, ownerVCard, true);
    }

    public final int composeAndSendPhonebookOneVcard(Operation op, final int offset,
            final boolean vcardType21, String ownerVCard, int orderByWhat) {
        if (offset < 1) {
            Log.e(TAG, "Internal error: offset is not correct.");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        final Uri myUri = Contacts.CONTENT_URI;
        Cursor contactCursor = null;
        String selection = null;
        long contactId = 0;
        if (orderByWhat == BluetoothPbapObexServer.ORDER_BY_INDEXED) {
            try {
                contactCursor = mResolver.query(myUri, CONTACTS_PROJECTION, CLAUSE_ONLY_VISIBLE,
                        null, Contacts._ID);
                if (contactCursor != null) {
                    contactCursor.moveToPosition(offset - 1);
                    contactId = contactCursor.getLong(CONTACTS_ID_COLUMN_INDEX);
                    if (V) Log.v(TAG, "Query startPointId = " + contactId);
                }
            } finally {
                if (contactCursor != null) {
                    contactCursor.close();
                }
            }
        } else if (orderByWhat == BluetoothPbapObexServer.ORDER_BY_ALPHABETICAL) {
            try {
                contactCursor = mResolver.query(myUri, CONTACTS_PROJECTION, CLAUSE_ONLY_VISIBLE,
                        null, Contacts.DISPLAY_NAME);
                if (contactCursor != null) {
                    contactCursor.moveToPosition(offset - 1);
                    contactId = contactCursor.getLong(CONTACTS_ID_COLUMN_INDEX);
                    if (V) Log.v(TAG, "Query startPointId = " + contactId);
                }
            } finally {
                if (contactCursor != null) {
                    contactCursor.close();
                }
            }
        } else {
            Log.e(TAG, "Parameter orderByWhat is not supported!");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        selection = Contacts._ID + "=" + contactId;

        if (V) Log.v(TAG, "Query selection is: " + selection);

        return composeAndSendVCards(op, selection, vcardType21, ownerVCard, true);
    }

    public final int composeAndSendVCards(Operation op, final String selection,
            final boolean vcardType21, String ownerVCard, boolean isContacts) {
        long timestamp = 0;
        if (V) timestamp = System.currentTimeMillis();

        if (isContacts) {
            VCardComposer composer = null;
            HandlerForStringBuffer buffer = null;
            try {
                // Currently only support Generic Vcard 2.1 and 3.0
                int vcardType;
                if (vcardType21) {
                    vcardType = VCardConfig.VCARD_TYPE_V21_GENERIC;
                } else {
                    vcardType = VCardConfig.VCARD_TYPE_V30_GENERIC;
                }
                vcardType |= VCardConfig.FLAG_REFRAIN_IMAGE_EXPORT;

                composer = new VCardComposer(mContext, vcardType, true);
                // BT does want PAUSE/WAIT conversion while it doesn't want the other formatting
                // done by vCard library by default.
                composer.setPhoneNumberTranslationCallback(
                        new VCardPhoneNumberTranslationCallback() {
                            public String onValueReceived(
                                    String rawValue, int type, String label, boolean isPrimary) {
                                // 'p' and 'w' are the standard characters for pause and wait
                                // (see RFC 3601)
                                // so use those when exporting phone numbers via vCard.
                                String numberWithControlSequence = rawValue
                                        .replace(PhoneNumberUtils.PAUSE, 'p')
                                        .replace(PhoneNumberUtils.WAIT, 'w');
                                return numberWithControlSequence;
                            }
                        });
                buffer = new HandlerForStringBuffer(op, ownerVCard);
                if (!composer.init(Contacts.CONTENT_URI, selection, null, Contacts._ID) ||
                        !buffer.onInit(mContext)) {
                    return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                }

                while (!composer.isAfterLast()) {
                    if (BluetoothPbapObexServer.sIsAborted) {
                        ((ServerOperation)op).isAborted = true;
                        BluetoothPbapObexServer.sIsAborted = false;
                        break;
                    }
                    String vcard = composer.createOneEntry();
                    if (vcard == null) {
                        Log.e(TAG, "Failed to read a contact. Error reason: "
                                + composer.getErrorReason());
                        return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                    }
                    if (!buffer.onEntryCreated(vcard)) {
                        // onEntryCreate() already emits error.
                        return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                    }
                }
            } finally {
                if (composer != null) {
                    composer.terminate();
                }
                if (buffer != null) {
                    buffer.onTerminate();
                }
            }
        } else { // CallLog
            BluetoothPbapCallLogComposer composer = null;
            HandlerForStringBuffer buffer = null;
            try {

                composer = new BluetoothPbapCallLogComposer(mContext);
                buffer = new HandlerForStringBuffer(op, ownerVCard);
                if (!composer.init(CallLog.Calls.CONTENT_URI, selection, null,
                                   CALLLOG_SORT_ORDER) ||
                                   !buffer.onInit(mContext)) {
                    return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                }

                while (!composer.isAfterLast()) {
                    if (BluetoothPbapObexServer.sIsAborted) {
                        ((ServerOperation)op).isAborted = true;
                        BluetoothPbapObexServer.sIsAborted = false;
                        break;
                    }
                    String vcard = composer.createOneEntry(vcardType21);
                    if (vcard == null) {
                        Log.e(TAG, "Failed to read a contact. Error reason: "
                                + composer.getErrorReason());
                        return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                    }
                    buffer.onEntryCreated(vcard);
                }
            } finally {
                if (composer != null) {
                    composer.terminate();
                }
                if (buffer != null) {
                    buffer.onTerminate();
                }
            }
        }

        if (V) Log.v(TAG, "Total vcard composing and sending out takes "
                    + (System.currentTimeMillis() - timestamp) + " ms");

        return ResponseCodes.OBEX_HTTP_OK;
    }

    /**
     * Handler to emit vCards to PCE.
     */
    public class HandlerForStringBuffer {
        private Operation operation;

        private OutputStream outputStream;

        private String phoneOwnVCard = null;

        public HandlerForStringBuffer(Operation op, String ownerVCard) {
            operation = op;
            if (ownerVCard != null) {
                phoneOwnVCard = ownerVCard;
                if (V) Log.v(TAG, "phone own number vcard:");
                if (V) Log.v(TAG, phoneOwnVCard);
            }
        }

        private boolean write(String vCard) {
            try {
                if (vCard != null) {
                    outputStream.write(vCard.getBytes());
                    return true;
                }
            } catch (IOException e) {
                Log.e(TAG, "write outputstrem failed" + e.toString());
            }
            return false;
        }

        public boolean onInit(Context context) {
            try {
                outputStream = operation.openOutputStream();
                if (phoneOwnVCard != null) {
                    return write(phoneOwnVCard);
                }
                return true;
            } catch (IOException e) {
                Log.e(TAG, "open outputstrem failed" + e.toString());
            }
            return false;
        }

        public boolean onEntryCreated(String vcard) {
            return write(vcard);
        }

        public void onTerminate() {
            if (!BluetoothPbapObexServer.closeStream(outputStream, operation)) {
                if (V) Log.v(TAG, "CloseStream failed!");
            } else {
                if (V) Log.v(TAG, "CloseStream ok!");
            }
        }
    }
}
