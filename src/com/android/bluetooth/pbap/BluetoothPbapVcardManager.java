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

import com.android.bluetooth.R;

import android.net.Uri;
import android.os.Handler;
import android.syncml.pim.vcard.ContactStruct;
import android.syncml.pim.vcard.VCardException;
import android.text.TextUtils;
import android.util.Log;
import android.database.Cursor;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.PhoneLookup;
import android.pim.vcard.VCardComposer;
import android.pim.vcard.VCardConfig;
import android.pim.vcard.VCardComposer.OneEntryHandler;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.CommonDataKinds.Phone;

import javax.obex.ResponseCodes;
import javax.obex.Operation;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.ArrayList;

public class BluetoothPbapVcardManager {
    private static final String TAG = "BluetoothPbapVcardManager";

    private static final boolean V = BluetoothPbapService.VERBOSE;

    private ContentResolver mResolver;

    private Context mContext;

    private StringBuilder mVcardResults = null;

    static final String[] PHONES_PROJECTION = new String[] {
            Data._ID, // 0
            CommonDataKinds.Phone.TYPE, // 1
            CommonDataKinds.Phone.LABEL, // 2
            CommonDataKinds.Phone.NUMBER, // 3
            Contacts.DISPLAY_NAME, // 4
    };

    private static final int ID_COLUMN_INDEX = 0;

    private static final int PHONE_TYPE_COLUMN_INDEX = 1;

    private static final int PHONE_LABEL_COLUMN_INDEX = 2;

    private static final int PHOEN_NUMBER_COLUMN_INDEX = 3;

    private static final int CONTACTS_DISPLAY_NAME_COLUMN_INDEX = 4;

    static final String SORT_ORDER_NAME = Contacts.DISPLAY_NAME + " ASC";

    static final String SORT_ORDER_PHONE_NUMBER = CommonDataKinds.Phone.NUMBER + " ASC";

    public BluetoothPbapVcardManager(final Context context) {
        mContext = context;
        mResolver = mContext.getContentResolver();
    }

    public final String getOwnerPhoneNumberVcard(final boolean vcardType21) {
        VCardComposer composer = new VCardComposer(mContext);
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
        Uri myUri = RawContacts.CONTENT_URI;
        int size = 0;
        Cursor contactCursor = null;
        try {
            contactCursor = mResolver.query(myUri, null, null, null, null);
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
        Uri myUri = CallLog.Calls.CONTENT_URI;
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
        Uri myUri = CallLog.Calls.CONTENT_URI;
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
                    CallLog.Calls.DEFAULT_SORT_ORDER);
            if (callCursor != null) {
                for (callCursor.moveToFirst(); !callCursor.isAfterLast();
                        callCursor.moveToNext()) {
                    String name = callCursor.getString(CALLS_NAME_COLUMN_INDEX);
                    if (TextUtils.isEmpty(name)) {
                        // name not found,use number instead
                        name = callCursor.getString(CALLS_NUMBER_COLUMN_INDEX);
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

    public final ArrayList<String> getPhonebookNameList() {
        ArrayList<String> nameList = new ArrayList<String>();
        nameList.add(BluetoothPbapService.getLocalPhoneName());

        Uri myUri = Phone.CONTENT_URI;
        Cursor phoneCursor = null;
        try {
            phoneCursor = mResolver.query(myUri, PHONES_PROJECTION, null, null, SORT_ORDER_NAME);
            if (phoneCursor != null) {
                for (phoneCursor.moveToFirst(); !phoneCursor.isAfterLast(); phoneCursor
                        .moveToNext()) {
                    String name = phoneCursor.getString(CONTACTS_DISPLAY_NAME_COLUMN_INDEX);
                    if (TextUtils.isEmpty(name)) {
                        name = mContext.getString(android.R.string.unknownName);
                    }
                    nameList.add(name);
                }
            }
        } finally {
            if (phoneCursor != null) {
                phoneCursor.close();
            }
        }
        return nameList;
    }

    public final ArrayList<String> getPhonebookNumberList() {
        ArrayList<String> numberList = new ArrayList<String>();
        numberList.add(BluetoothPbapService.getLocalPhoneNum());

        Uri myUri = Phone.CONTENT_URI;
        Cursor phoneCursor = null;
        try {
            phoneCursor = mResolver.query(myUri, PHONES_PROJECTION, null, null,
                    SORT_ORDER_PHONE_NUMBER);
            if (phoneCursor != null) {
                for (phoneCursor.moveToFirst(); !phoneCursor.isAfterLast(); phoneCursor
                        .moveToNext()) {
                    String number = phoneCursor.getString(PHOEN_NUMBER_COLUMN_INDEX);
                    if (TextUtils.isEmpty(number)) {
                        number = mContext.getString(R.string.defaultnumber);
                    }
                    numberList.add(number);
                }
            }
        } finally {
            if (phoneCursor != null) {
                phoneCursor.close();
            }
        }
        return numberList;
    }

    public final int composeAndSendCallLogVcards(final int type, final Operation op,
            final int startPoint, final int endPoint, final boolean vcardType21) {
        String typeSelection = BluetoothPbapObexServer.createSelectionPara(type);
        String recordSelection;
        if (startPoint == endPoint) {
            recordSelection = Calls._ID + "=" + startPoint;
        } else {
            recordSelection = Calls._ID + ">=" + startPoint + " AND "
                    + Calls._ID + "<=" + endPoint;
        }

        String selection;
        if (typeSelection == null) {
            selection = recordSelection;
        } else {
            selection = "(" + typeSelection + ") AND (" + recordSelection + ")";
        }

        if (V) Log.v(TAG, "Query selection is: " + selection);

        return composeAndSendVCards(op, selection, vcardType21, null, false);
    }

    public final int composeAndSendPhonebookVcards(final Operation op, final int startPoint,
            final int endPoint, final boolean vcardType21, String ownerVCard) {
        String selection;
        if (startPoint == endPoint) {
            selection = RawContacts._ID + "=" + startPoint;
        } else {
            selection = RawContacts._ID + ">=" + startPoint + " AND " + RawContacts._ID + "<="
                    + endPoint;
        }

        if (V) Log.v(TAG, "Query selection is: " + selection);

        return composeAndSendVCards(op, selection, vcardType21, ownerVCard, true);
    }

    public final int composeAndSendVCards(final Operation op, final String selection,
            final boolean vcardType21, String ownerVCard, boolean isContacts) {
        long timestamp = 0;
        if (V) timestamp = System.currentTimeMillis();

        VCardComposer composer = null;
        try {
            // Currently only support Generic Vcard 2.1 and 3.0
            int vcardType;
            if (vcardType21) {
                vcardType = VCardConfig.VCARD_TYPE_V21_GENERIC;
            } else {
                vcardType = VCardConfig.VCARD_TYPE_V30_GENERIC;
            }

            if (isContacts) {
                // create VCardComposer for contacts
                composer = new VCardComposer(mContext, vcardType, true);
            } else {
                // create VCardComposer for call logs
                composer = new VCardComposer(mContext, vcardType, true, true);
            }

            composer.addHandler(new HandlerForStringBuffer(op, ownerVCard));

            if (!composer.init(selection, null)) {
                return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
            }

            while (!composer.isAfterLast()) {
                if (!composer.createOneEntry()) {
                    Log.e(TAG, "Failed to read a contact. Error reason: "
                            + composer.getErrorReason());
                    return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                }
            }
        } finally {
            if (composer != null) {
                composer.terminate();
            }
        }

        if (V) Log.v(TAG, "Total vcard composing and sending out takes "
                    + (System.currentTimeMillis() - timestamp) + " ms");

        return ResponseCodes.OBEX_HTTP_OK;
    }

    /**
     * Handler to emit VCard String to PCE once size grow to maxPacketSize.
     */
    public class HandlerForStringBuffer implements OneEntryHandler {
        @SuppressWarnings("hiding")
        private Operation operation;

        private OutputStream outputStream;

        private int maxPacketSize;

        private String phoneOwnVCard = null;

        public HandlerForStringBuffer(Operation op, String ownerVCard) {
            operation = op;
            maxPacketSize = operation.getMaxPacketSize();
            if (V) Log.v(TAG, "getMaxPacketSize() = " + maxPacketSize);
            if (ownerVCard != null) {
                phoneOwnVCard = ownerVCard;
                if (V) Log.v(TAG, "phone own number vcard:");
                if (V) Log.v(TAG, phoneOwnVCard);
            }
        }

        public boolean onInit(Context context) {
            try {
                outputStream = operation.openOutputStream();
                mVcardResults = new StringBuilder();
                if (phoneOwnVCard != null) {
                    mVcardResults.append(phoneOwnVCard);
                }
            } catch (IOException e) {
                Log.e(TAG, "open outputstrem failed" + e.toString());
                return false;
            }
            if (V) Log.v(TAG, "openOutputStream() ok.");
            return true;
        }

        public boolean onEntryCreated(String vcard) {
            int vcardLen = vcard.length();
            if (V) Log.v(TAG, "The length of this vcard is: " + vcardLen);

            mVcardResults.append(vcard);
            int vcardStringLen = mVcardResults.toString().length();
            if (V) Log.v(TAG, "The length of this vcardResults is: " + vcardStringLen);

            if (vcardStringLen >= maxPacketSize) {
                long timestamp = 0;
                int position = 0;

                // Need while loop to handle the big vcard case
                while (position < (vcardStringLen - maxPacketSize)) {
                    if (V) timestamp = System.currentTimeMillis();

                    String subStr = mVcardResults.toString().substring(position,
                            position + maxPacketSize);
                    try {
                        outputStream.write(subStr.getBytes(), 0, maxPacketSize);
                    } catch (IOException e) {
                        Log.e(TAG, "write outputstrem failed" + e.toString());
                        return false;
                    }
                    if (V) Log.v(TAG, "Sending vcard String " + maxPacketSize + " bytes took "
                            + (System.currentTimeMillis() - timestamp) + " ms");

                    position += maxPacketSize;
                }
                mVcardResults.delete(0, position);
            }
            return true;
        }

        public void onTerminate() {
            // Send out last packet
            String lastStr = mVcardResults.toString();
            try {
                outputStream.write(lastStr.getBytes(), 0, lastStr.length());
            } catch (IOException e) {
                Log.e(TAG, "write outputstrem failed" + e.toString());
            }
            if (V) Log.v(TAG, "Last packet sent out, sending process complete!");

            if (!BluetoothPbapObexServer.closeStream(outputStream, operation)) {
                if (V) Log.v(TAG, "CloseStream failed!");
            } else {
                if (V) Log.v(TAG, "CloseStream ok!");
            }
        }
    }
}
