/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 * Copyright (c) 2008-2009, Motorola, Inc.
 * Copyright (C) 2009-2012, Broadcom Corporation
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
import java.util.Collections;
import java.util.Comparator;
import com.android.bluetooth.R;
import com.android.vcard.VCardComposer;
import com.android.vcard.VCardConfig;
import com.android.vcard.VCardPhoneNumberTranslationCallback;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;

import javax.obex.ServerOperation;
import javax.obex.Operation;
import javax.obex.ResponseCodes;

import com.android.bluetooth.Utils;

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

    private final String SIM_URI = "content://icc/adn";

    static final String[] SIM_PROJECTION = new String[] {
            Contacts.DISPLAY_NAME,
            CommonDataKinds.Phone.NUMBER,
    };
    private static final int PHONE_NUMBER_COLUMN_INDEX = 3;

    private static final int SIM_NAME_COLUMN_INDEX = 0;
    private static final int SIM_NUMBER_COLUMN_INDEX = 1;
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

    /**
     * Create an owner vcard from the configured profile
     * @param vcardType21
     * @return
     */
    private final String getOwnerPhoneNumberVcardFromProfile(final boolean vcardType21, final byte[] filter) {
        // Currently only support Generic Vcard 2.1 and 3.0
        int vcardType;
        if (vcardType21) {
            vcardType = VCardConfig.VCARD_TYPE_V21_GENERIC;
        } else {
            vcardType = VCardConfig.VCARD_TYPE_V30_GENERIC;
        }

        if (!BluetoothPbapConfig.includePhotosInVcard()) {
            vcardType |= VCardConfig.FLAG_REFRAIN_IMAGE_EXPORT;
        }

        return BluetoothPbapUtils.createProfileVCard(mContext, vcardType,filter);
    }

    public final String getOwnerPhoneNumberVcard(final boolean vcardType21, final byte[] filter) {
        //Owner vCard enhancement: Use "ME" profile if configured
        if (BluetoothPbapConfig.useProfileForOwnerVcard()) {
            String vcard = getOwnerPhoneNumberVcardFromProfile(vcardType21, filter);
            if (vcard != null && vcard.length() != 0) {
                return vcard;
            }
        }
        //End enhancement

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
            case BluetoothPbapObexServer.ContentType.SIM_PHONEBOOK:
                size = getSIMContactsSize();
                break;
            default:
                size = getCallHistorySize(type);
                break;
        }
        if (V) Log.v(TAG, "getPhonebookSize size = " + size + " type = " + type);
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

    public final int getSIMContactsSize() {
        final Uri myUri = Uri.parse(SIM_URI);
        int size = 0;
        Cursor contactCursor = null;
        try {
            contactCursor = mResolver.query(myUri, SIM_PROJECTION, null,null, null);
            if (contactCursor != null) {
                size = contactCursor.getCount() +1;  //always has the 0.vcf
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
                Calls.NUMBER, Calls.CACHED_NAME, Calls.NUMBER_PRESENTATION
        };
        final int CALLS_NUMBER_COLUMN_INDEX = 0;
        final int CALLS_NAME_COLUMN_INDEX = 1;
        final int CALLS_NUMBER_PRESENTATION_COLUMN_INDEX = 2;

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
                        final int numberPresentation = callCursor.getInt(
                                CALLS_NUMBER_PRESENTATION_COLUMN_INDEX);
                        if (numberPresentation != Calls.PRESENTATION_ALLOWED) {
                            name = mContext.getString(R.string.unknownNumber);
                        } else {
                            name = callCursor.getString(CALLS_NUMBER_COLUMN_INDEX);
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
    public final ArrayList<String> getSIMPhonebookNameList(final int orderByWhat) {
        ArrayList<String> nameList = new ArrayList<String>();
        nameList.add(BluetoothPbapService.getLocalPhoneName());
        //Since owner card should always be 0.vcf, maintaing a separate list to avoid sorting
        ArrayList<String> allnames = new ArrayList<String>();
        final Uri myUri = Uri.parse(SIM_URI);
        Cursor contactCursor = null;
        try {
            contactCursor = mResolver.query(myUri, SIM_PROJECTION, null,null,null);
            if (contactCursor != null) {
                for (contactCursor.moveToFirst(); !contactCursor.isAfterLast(); contactCursor
                        .moveToNext()) {
                    String name = contactCursor.getString(SIM_NAME_COLUMN_INDEX);
                    if (TextUtils.isEmpty(name)) {
                        name = mContext.getString(android.R.string.unknownName);
                    }
                    allnames.add(name);
                }
            }
        } finally {
            if (contactCursor != null) {
                contactCursor.close();
            }
        }
        if (orderByWhat == BluetoothPbapObexServer.ORDER_BY_INDEXED) {
                if (V) Log.v(TAG, "getPhonebookNameList, order by index");
        } else if (orderByWhat == BluetoothPbapObexServer.ORDER_BY_ALPHABETICAL) {
                if (V) Log.v(TAG, "getPhonebookNameList, order by alpha");
                Collections.sort(allnames, new Comparator <String> ()
                                 {@Override
                                  public int compare(String str1, String str2){
                                      return str1.compareToIgnoreCase(str2);
                                  }
                 });
        }

        nameList.addAll(allnames);
        return nameList;

    }


    public final ArrayList<String> getPhonebookNameList(final int orderByWhat) {
        ArrayList<String> nameList = new ArrayList<String>();
        //Owner vCard enhancement. Use "ME" profile if configured
        String ownerName = null;
        if (BluetoothPbapConfig.useProfileForOwnerVcard()) {
            ownerName = BluetoothPbapUtils.getProfileName(mContext);
        }
        if (ownerName == null || ownerName.length()==0) {
            ownerName = BluetoothPbapService.getLocalPhoneName();
        }
        nameList.add(ownerName);
        //End enhancement

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

    public final ArrayList<String> getSIMContactNamesByNumber(final String phoneNumber) {
        ArrayList<String> nameList = new ArrayList<String>();
        ArrayList<String> startNameList = new ArrayList<String>();
        StringBuilder onlyphoneNumber = new StringBuilder();
        for (int j=0; j<phoneNumber.length(); j++) {
            char c = phoneNumber.charAt(j);
                if (c >= '0' && c <= '9') {
                    onlyphoneNumber = onlyphoneNumber.append(c);
                }
        }
        String SearchOnlyNumber = onlyphoneNumber.toString();

        Cursor contactCursor = null;
        final Uri uri = Uri.parse(SIM_URI);

        try {
            contactCursor = mResolver.query(uri, SIM_PROJECTION, null, null, null);

            if (contactCursor != null) {
                for (contactCursor.moveToFirst(); !contactCursor.isAfterLast(); contactCursor
                        .moveToNext()) {
                    String number = contactCursor.getString(SIM_NUMBER_COLUMN_INDEX);
                    if (number == null) {
                        if (V) Log.v(TAG, "number is null");
                        continue;
                    }
                    StringBuilder onlyNumber = new StringBuilder();
                    for (int j=0; j<number.length(); j++) {
                        char c = number.charAt(j);
                        if (c >= '0' && c <= '9') {
                            onlyNumber = onlyNumber.append(c);
                        }
                    }
                    String tmpNumber = onlyNumber.toString();
                    if (V) Log.v(TAG, "number: "+number+" onlyNumber:"+onlyNumber+" tmpNumber:"+tmpNumber);
                    if (tmpNumber.endsWith(SearchOnlyNumber)) {
                        String name = contactCursor.getString(SIM_NAME_COLUMN_INDEX);
                        if (TextUtils.isEmpty(name)) {
                            name = mContext.getString(android.R.string.unknownName);
                        }
                        if (V) Log.v(TAG, "got name " + name + " by number " + phoneNumber);
                        if (V) Log.v(TAG, "Adding to end name list");
                        nameList.add(name);
                    }
                    if (tmpNumber.startsWith(SearchOnlyNumber)) {
                        String name = contactCursor.getString(SIM_NAME_COLUMN_INDEX);
                        if (TextUtils.isEmpty(name)) {
                            name = mContext.getString(android.R.string.unknownName);
                        }
                        if (V) Log.v(TAG, "got name " + name + " by number " + phoneNumber);
                        if (V) Log.v(TAG, "Adding to start name list");
                        startNameList.add(name);
                    }
                }
            }
        } finally {
            if (contactCursor != null) {
                contactCursor.close();
            }
        }
        int startListSize = startNameList.size();
        for (int index = 0; index < startListSize; index++) {
            String object = startNameList.get(index);
            if (!nameList.contains(object))
                nameList.add(object);
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
            final int startPoint, final int endPoint, final boolean vcardType21, boolean ignorefilter, byte[] filter) {
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

        return composeAndSendVCards(op, selection, vcardType21, null, false, ignorefilter, filter);
    }

    public final int composeAndSendPhonebookVcards(Operation op, final int startPoint,
            final int endPoint, final boolean vcardType21, String ownerVCard, boolean ignorefilter, byte[] filter) {
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

        return composeAndSendVCards(op, selection, vcardType21, ownerVCard, true, ignorefilter, filter);
    }
    public final int composeAndSendSIMPhonebookVcards(Operation op, final int startPoint,
            final int endPoint, final boolean vcardType21, String ownerVCard) {
        if (startPoint < 1 || startPoint > endPoint) {
            Log.e(TAG, "internal error: startPoint or endPoint is not correct.");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        final Uri myUri = Uri.parse(SIM_URI);
        BluetoothPbapSIMvCardComposer composer = null;
        HandlerForStringBuffer buffer = null;
            try {
                composer = new BluetoothPbapSIMvCardComposer(mContext);
                buffer = new HandlerForStringBuffer(op, ownerVCard);

                if (!composer.init(myUri, null, null, null)||
                                   !buffer.onInit(mContext)) {
                    return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                }
               composer.moveToPosition(startPoint -1, false);
               for (int count =startPoint -1; count < endPoint; count++) {
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

        return ResponseCodes.OBEX_HTTP_OK;
    }

    public final int composeAndSendPhonebookOneVcard(Operation op, final int offset,
            final boolean vcardType21, String ownerVCard, int orderByWhat, boolean ignorefilter, byte[] filter) {
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

        return composeAndSendVCards(op, selection, vcardType21, ownerVCard, true, ignorefilter, filter);
    }

    public final int composeAndSendSIMPhonebookOneVcard(Operation op, final int offset,
        final boolean vcardType21, String ownerVCard, int orderByWhat) {
        if (offset < 1) {
            Log.e(TAG, "Internal error: offset is not correct.");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        final Uri myUri = Uri.parse(SIM_URI);

        BluetoothPbapSIMvCardComposer composer = null;
        HandlerForStringBuffer buffer = null;
            try {
                composer = new BluetoothPbapSIMvCardComposer(mContext);
                buffer = new HandlerForStringBuffer(op, ownerVCard);
                if (!composer.init(myUri, null, null,null)||
                                   !buffer.onInit(mContext)) {
                    return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                }
                if (orderByWhat == BluetoothPbapObexServer.ORDER_BY_INDEXED) {
                    if (V) Log.v(TAG, "getPhonebookNameList, order by index");
                    composer.moveToPosition(offset -1, false);
                } else if (orderByWhat == BluetoothPbapObexServer.ORDER_BY_ALPHABETICAL) {
                    if (V) Log.v(TAG, "getPhonebookNameList, order by alpha");
                    composer.moveToPosition(offset -1, true);
                }
                if (BluetoothPbapObexServer.sIsAborted) {
                    ((ServerOperation)op).isAborted = true;
                     BluetoothPbapObexServer.sIsAborted = false;
                }
                String vcard = composer.createOneEntry(vcardType21);
                if (vcard == null) {
                    Log.e(TAG, "Failed to read a contact. Error reason: "
                                + composer.getErrorReason());
                    return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                }
                buffer.onEntryCreated(vcard);
            } finally {
                if (composer != null) {
                    composer.terminate();
                }
                if (buffer != null) {
                    buffer.onTerminate();
                }
            }

        return ResponseCodes.OBEX_HTTP_OK;
    }

    public final int composeAndSendVCards(Operation op, final String selection,
            final boolean vcardType21, String ownerVCard, boolean isContacts, boolean ignorefilter, byte[] filter) {
        long timestamp = 0;
        if (V) timestamp = System.currentTimeMillis();

        if (isContacts) {
            VCardComposer composer = null;
            FilterVcard vcardfilter= new FilterVcard();
            if (!ignorefilter) {
                vcardfilter.setFilter(filter);
            }
            HandlerForStringBuffer buffer = null;
            try {
                // Currently only support Generic Vcard 2.1 and 3.0
                int vcardType;
                if (vcardType21) {
                    vcardType = VCardConfig.VCARD_TYPE_V21_GENERIC;
                } else {
                    vcardType = VCardConfig.VCARD_TYPE_V30_GENERIC;
                }
                if (!vcardfilter.isPhotoEnabled()) {
                    vcardType |= VCardConfig.FLAG_REFRAIN_IMAGE_EXPORT;
                }

                //Enhancement: customize Vcard based on preferences/settings and input from caller
                composer = BluetoothPbapUtils.createFilteredVCardComposer(mContext, vcardType,null);
                //End enhancement

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
                    Log.v (TAG , "vCard from composer: " + vcard);
                    if (!ignorefilter) {
                        vcard = vcardfilter.applyFilter(vcard, vcardType21);
                        Log.v (TAG , "vCard on applying filter: " + vcard);
                    }
                    if (vcard == null) {
                        Log.e(TAG, "Failed to read a contact. Error reason: "
                                + composer.getErrorReason());
                        return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                    }
                    if (V) {
                        Log.v(TAG, "Vcard Entry:");
                        Log.v(TAG,vcard);
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
                    if (V) {
                        Log.v(TAG, "Vcard Entry:");
                        Log.v(TAG,vcard);
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

    public class FilterVcard{

        public FilterVcard(){
        };

        private final int FN_BIT = 1;

        private boolean fn = true;

        private final int PHOTO_BIT = 3;

        private boolean photo = true;

        //BDAY falls under events
        private final int BDAY_BIT = 4;

        private boolean bday = true;

        private final int ADR_BIT = 5;

        private boolean adr = true;

        private final int EMAIL_BIT = 8;

        private boolean email = true;

        private final int TITLE_BIT = 12;

        private boolean title = true;

        private final int ORG_BIT = 16;

        private boolean org = true;

        private final int NOTES_BIT = 17;

        private boolean notes = true;

        private final int URL_BIT = 20;

        private boolean url = true;

        private final int NICKNAME_BIT = 23;

        private boolean nickname = true;

        public void setFilter(byte[] filter){

           fn = checkbit(FN_BIT, filter);
           photo = checkbit(PHOTO_BIT, filter);
           bday = checkbit(BDAY_BIT, filter);
           adr = checkbit(ADR_BIT, filter);
           email = checkbit(EMAIL_BIT, filter);
           title = checkbit(TITLE_BIT, filter);
           org = checkbit(ORG_BIT, filter);
           notes = checkbit(NOTES_BIT, filter);
           url = checkbit(URL_BIT, filter);
           nickname = checkbit(NICKNAME_BIT, filter);
        }

        private boolean checkbit (int attr_bit, byte[] filter){
            int filterlen = filter.length;
            if( ((filter[filterlen -1 -((int)attr_bit/8)] >> (attr_bit%8)) & 0x01) == 0) {
                return false;
            }
            return true;
        }

        public boolean isPhotoEnabled(){
            return photo;
        }

        private boolean checkValidFilter (String attr) {
            if((attr.startsWith("N:")) || (attr.startsWith("TEL"))
                || (attr.startsWith("VERSION")) || (attr.startsWith("URL"))
                || (attr.startsWith("FN")) || (attr.startsWith("BDAY"))
                || (attr.startsWith("ADR")) || (attr.startsWith("EMAIL"))
                || (attr.startsWith("TITLE")) || (attr.startsWith("ORG"))
                || (attr.startsWith("NOTE")) || (attr.startsWith("NICKNAME"))) {
                return true;
            }
            return false;
        }

        public String applyFilter ( String vCard, boolean vCardType21){
            String attr [] = vCard.split(System.getProperty("line.separator"));
            String filteredVcard = "";

            //FN is not the mandatory field in 2.1 vCard
            if(((!fn) && (vCardType21)) && (vCard.contains("FN"))) {
                for (int i=0; i < attr.length; i++) {
                    if(attr[i].startsWith("FN")){
                        attr[i] = "";
                        /** Remove multiline Content, if any */
                        /** End traversal before END:VCARD */
                        for (int j = i+1; j < attr.length - 1; j++) {
                            if (checkValidFilter(attr[j])) {
                                break;
                            } else {
                                /** Continuation of above attribute, remove */
                                attr[j] = "";
                            }
                        }
                    }
                }
            }

          //NOTE: No need to check photo, we already refrained it if it is not set in the filter
            if((!bday) && (vCard.contains("BDAY"))) {
                for (int i=0; i < attr.length; i++) {
                    if(attr[i].startsWith("BDAY")){
                        attr[i] = "";
                        /** Remove multiline Content, if any */
                        /** End traversal before END:VCARD */
                        for (int j = i+1; j < attr.length - 1; j++) {
                            if (checkValidFilter(attr[j])) {
                                break;
                            } else {
                                /** Continuation of above attribute, remove */
                                attr[j] = "";
                            }
                        }
                    }
                }
            }

            if((!adr) && (vCard.contains("ADR"))) {
                for (int i=0; i < attr.length; i++) {
                    if(attr[i].startsWith("ADR")){
                        attr[i] = "";
                        /** Remove multiline Content, if any */
                        /** End traversal before END:VCARD */
                        for (int j = i+1; j < attr.length - 1; j++) {
                            if (checkValidFilter(attr[j])) {
                                break;
                            } else {
                                /** Continuation of above attribute, remove */
                                attr[j] = "";
                            }
                        }
                    }
                }
            }

            if((!email) && (vCard.contains("EMAIL"))) {
                for (int i=0; i < attr.length; i++) {
                    if(attr[i].startsWith("EMAIL")){
                        attr[i] = "";
                        /** Remove multiline Content, if any */
                        /** End traversal before END:VCARD */
                        for (int j = i+1; j < attr.length - 1; j++) {
                            if (checkValidFilter(attr[j])) {
                                break;
                            } else {
                                /** Continuation of above attribute, remove */
                                attr[j] = "";
                            }
                        }
                    }
                }
            }

            if((!title) && (vCard.contains("TITLE"))) {
                for (int i=0; i < attr.length; i++) {
                    if(attr[i].startsWith("TITLE")){
                        attr[i] = "";
                        /** Remove multiline Content, if any */
                        /** End traversal before END:VCARD */
                        for (int j = i+1; j < attr.length - 1; j++) {
                            if (checkValidFilter(attr[j])) {
                                break;
                            } else {
                                /** Continuation of above attribute, remove */
                                attr[j] = "";
                            }
                        }
                    }
                }
            }

            if((!org) && (vCard.contains("ORG"))) {
                for (int i=0; i < attr.length; i++) {
                    if(attr[i].startsWith("ORG")){
                        attr[i] = "";
                        /** Remove multiline Content, if any */
                        /** End traversal before END:VCARD */
                        for (int j = i+1; j < attr.length - 1; j++) {
                            if (checkValidFilter(attr[j])) {
                                break;
                            } else {
                                /** Continuation of above attribute, remove */
                                attr[j] = "";
                            }
                        }
                    }
                }
            }

            if((!notes) && (vCard.contains("NOTE"))) {
                for (int i=0; i < attr.length; i++) {
                    if(attr[i].startsWith("NOTE")){
                        attr[i] = "";
                        /** Remove multiline Content, if any */
                        /** End traversal before END:VCARD */
                        for (int j = i+1; j < attr.length - 1; j++) {
                            if (checkValidFilter(attr[j])) {
                                break;
                            } else {
                                /** Continuation of above attribute, remove */
                                attr[j] = "";
                            }
                        }
                    }
                }
            }
            /*Nickname is not supported in 2.1 version.
             *Android still ads it for 2.1 with nickname mentioned in lower case, and therefore
             *we need to check for both cases.
             */
            if(((!nickname) || (vCardType21)) && (vCard.contains("NICKNAME"))) {
                for (int i=0; i < attr.length; i++) {
                    if(attr[i].startsWith("NICKNAME")){
                        attr[i] = "";
                        /** Remove multiline Content, if any */
                        /** End traversal before END:VCARD */
                        for (int j = i+1; j < attr.length - 1; j++) {
                            if (checkValidFilter(attr[j])) {
                                break;
                            } else {
                                /** Continuation of above attribute, remove */
                                attr[j] = "";
                            }
                        }
                    }
                }
            }

            if((!url) && (vCard.contains("URL"))) {
                for (int i=0; i < attr.length; i++) {
                    if(attr[i].startsWith("URL")){
                        attr[i] = "";
                        /** Remove multiline Content, if any */
                        /** End traversal before END:VCARD */
                        for (int j = i+1; j < attr.length - 1; j++) {
                            if (checkValidFilter(attr[j])) {
                                break;
                            } else {
                                /** Continuation of above attribute, remove */
                                attr[j] = "";
                            }
                        }
                    }
                }
            }
            /*Since PBAP does not have filter bit for IM and SIP,
             *removing them by default.
            */
            if(vCard.toUpperCase().contains("IM")) {
                for (int i=0; i < attr.length; i++) {
                    if(attr[i].toUpperCase().contains("IM")){
                        vCard = vCard.replace(attr[i] + "\n", "");
                    }
                }
            }

            if(vCard.toUpperCase().contains("SIP")) {
                for (int i=0; i < attr.length; i++) {
                    if(attr[i].toUpperCase().contains("SIP")){
                        vCard = vCard.replace(attr[i] + "\n", "");
                    }
                }
            }

            Log.v(TAG, "Tokens after applying filter: ");

            for (int i=0; i < attr.length; i++) {
                if(!attr[i].equals("")){
                    filteredVcard = filteredVcard.concat(attr[i] + "\n");
                }
            }

            return filteredVcard;
        }
    }
}
