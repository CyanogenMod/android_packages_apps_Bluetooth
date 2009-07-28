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

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Contacts;
import android.provider.CallLog.Calls;
import android.provider.Contacts.Organizations;
import android.provider.CallLog;
import android.provider.Contacts.People;
import android.provider.Contacts.Phones;
import android.syncml.pim.vcard.ContactStruct;
import android.syncml.pim.vcard.VCardComposer;
import android.syncml.pim.vcard.VCardException;
import android.syncml.pim.vcard.VCardParser;
import android.util.Log;

import java.util.ArrayList;

public class BluetoothPbapVcardManager {
    static private final String TAG = "BluetoothPbapVcardManager";

    private ContentResolver mResolver;

    private Context mContext;

    private String mDefaultName = null;

    private String mDefaultNumber = null;

    /** The projection to use when querying the call log table */
    public static final String[] CALL_LOG_PROJECTION = new String[] {
            Calls._ID, Calls.NUMBER, Calls.DATE, Calls.DURATION, Calls.TYPE, Calls.CACHED_NAME,
            Calls.CACHED_NUMBER_TYPE, Calls.CACHED_NUMBER_LABEL
    };

    public static final int ID_COLUMN_INDEX = 0;

    public static final int NUMBER_COLUMN_INDEX = 1;

    public static final int DATE_COLUMN_INDEX = 2;

    public static final int DURATION_COLUMN_INDEX = 3;

    public static final int CALL_TYPE_COLUMN_INDEX = 4;

    public static final int CALLER_NAME_COLUMN_INDEX = 5;

    public static final int CALLER_NUMBERTYPE_COLUMN_INDEX = 6;

    public static final int CALLER_NUMBERLABEL_COLUMN_INDEX = 7;

    /** The projection to use when querying the phone book table */
    public static final String[] CONTACT_PROJECTION = new String[] {
            People._ID, // 0
            People.NAME, // 1
            People.NOTES, // 2
            People.PRIMARY_PHONE_ID, // 3
            People.PRESENCE_STATUS, // 4
            People.STARRED, // 5
            People.CUSTOM_RINGTONE, // 6
            People.SEND_TO_VOICEMAIL, // 7
            People.PHONETIC_NAME, // 8
    };

    public static final int CONTACT_ID_COLUMN = 0;

    public static final int CONTACT_NAME_COLUMN = 1;

    public static final int CONTACT_NOTES_COLUMN = 2;

    public static final int CONTACT_PREFERRED_PHONE_COLUMN = 3;

    public static final int CONTACT_SERVER_STATUS_COLUMN = 4;

    public static final int CONTACT_STARRED_COLUMN = 5;

    public static final int CONTACT_CUSTOM_RINGTONE_COLUMN = 6;

    public static final int CONTACT_SEND_TO_VOICEMAIL_COLUMN = 7;

    public static final int CONTACT_PHONETIC_NAME_COLUMN = 8;

    public static final String[] PHONES_PROJECTION = new String[] {
            People.Phones._ID, // 0
            People.Phones.NUMBER, // 1
            People.Phones.TYPE, // 2
            People.Phones.LABEL, // 3
            People.Phones.ISPRIMARY, // 4
    };

    public static final int PHONES_ID_COLUMN = 0;

    public static final int PHONES_NUMBER_COLUMN = 1;

    public static final int PHONES_TYPE_COLUMN = 2;

    public static final int PHONES_LABEL_COLUMN = 3;

    public static final int PHONES_ISPRIMARY_COLUMN = 4;

    public static final String[] METHODS_PROJECTION = new String[] {
            People.ContactMethods._ID, // 0
            People.ContactMethods.KIND, // 1
            People.ContactMethods.DATA, // 2
            People.ContactMethods.TYPE, // 3
            People.ContactMethods.LABEL, // 4
            People.ContactMethods.ISPRIMARY, // 5
            People.ContactMethods.AUX_DATA, // 6
    };

    public static final int METHODS_ID_COLUMN = 0;

    public static final int METHODS_KIND_COLUMN = 1;

    public static final int METHODS_DATA_COLUMN = 2;

    public static final int METHODS_TYPE_COLUMN = 3;

    public static final int METHODS_LABEL_COLUMN = 4;

    public static final int METHODS_ISPRIMARY_COLUMN = 5;

    public static final int METHODS_AUX_DATA_COLUMN = 6;

    public static final int METHODS_STATUS_COLUMN = 7;

    public static final String[] ORGANIZATIONS_PROJECTION = new String[] {
            Organizations._ID, // 0
            Organizations.TYPE, // 1
            Organizations.LABEL, // 2
            Organizations.COMPANY, // 3
            Organizations.TITLE, // 4
            Organizations.ISPRIMARY, // 5
    };

    public static final int ORGANIZATIONS_ID_COLUMN = 0;

    public static final int ORGANIZATIONS_TYPE_COLUMN = 1;

    public static final int ORGANIZATIONS_LABEL_COLUMN = 2;

    public static final int ORGANIZATIONS_COMPANY_COLUMN = 3;

    public static final int ORGANIZATIONS_TITLE_COLUMN = 4;

    public static final int ORGANIZATIONS_ISPRIMARY_COLUMN = 5;

    public BluetoothPbapVcardManager(final Context context) {
        mContext = context;
        mResolver = mContext.getContentResolver();
        mDefaultName = context.getString(android.R.string.unknownName);
        mDefaultNumber = context.getString(R.string.defaultnumber);
    }

    private String getThisPhoneName() {
        String name;
        name = BluetoothPbapService.getLocalPhoneName();
        if (name == null || name.trim().length() == 0) {
            name = mDefaultName;
        }
        return name;
    }

    private String getThisPhoneNumber() {
        String number;
        number = BluetoothPbapService.getLocalPhoneNum();
        if (number == null || number.trim().length() == 0) {
            number = mDefaultNumber;
        }
        return number;
    }

    public final int getPhonebookSize() {
        Uri myUri = Contacts.People.CONTENT_URI;
        Cursor contactC = mResolver.query(myUri, null, null, null, null);

        int mPhonebookSize = 0;
        if (contactC != null) {
            mPhonebookSize = contactC.getCount() + 1; // always has the 0.vcf
        }
        return mPhonebookSize;
    }

    public final String getPhonebook(final int pos, final boolean vCard21) {
        try {
            if (pos >= 0 && pos < getPhonebookSize()) {
                long id = 0;
                Uri myUri = Contacts.People.CONTENT_URI;
                // Individual Contact may be deleted, which lead to incontinuous
                // ID for Uri; So we need to calculate the actual Uri.
                if (pos > 0) {
                    Cursor personCursor = mResolver.query(myUri, CONTACT_PROJECTION, null, null,
                            null);
                    if (personCursor != null) {
                        personCursor.moveToPosition(pos - 1);
                        id = personCursor.getLong(CONTACT_ID_COLUMN);
                        personCursor.close();
                    }
                    String sPos = String.valueOf(id);
                    myUri = Uri.withAppendedPath(myUri, sPos);
                }
                return loadPhonebook(myUri, pos, vCard21);
            } else {
                Log.w(TAG, "pos invalid");
            }
        } catch (Exception e) {
            Log.e(TAG, "catch exception" + e.toString());
        }
        return null;
    }

    public final int getCallHistorySize(final int type) {
        int size = 0;
        Uri myUri = CallLog.Calls.CONTENT_URI;
        String selection = null;
        switch (type) {
            case BluetoothPbapObexServer.ContentType.INCOMING_CALL_HISTORY:
                selection = Calls.TYPE + "=" + CallLog.Calls.INCOMING_TYPE;
                break;
            case BluetoothPbapObexServer.ContentType.OUTGOING_CALL_HISTORY:
                selection = Calls.TYPE + "=" + CallLog.Calls.OUTGOING_TYPE;
                break;
            case BluetoothPbapObexServer.ContentType.MISSED_CALL_HISTORY:
                selection = Calls.TYPE + "=" + CallLog.Calls.MISSED_TYPE;
                break;
            default:
                break;
        }
        Cursor callCursor = mResolver.query(myUri, null, selection, null,
                CallLog.Calls.DEFAULT_SORT_ORDER);
        if (callCursor != null) {
            size = callCursor.getCount();
            callCursor.close();
        }
        return size;
    }

    public final String getCallHistory(final int pos, final int type, final boolean vCard21) {
        int size = 0;
        String selection = null;
        switch (type) {
            case BluetoothPbapObexServer.ContentType.INCOMING_CALL_HISTORY:
                selection = Calls.TYPE + "=" + CallLog.Calls.INCOMING_TYPE;
                break;
            case BluetoothPbapObexServer.ContentType.OUTGOING_CALL_HISTORY:
                selection = Calls.TYPE + "=" + CallLog.Calls.OUTGOING_TYPE;
                break;
            case BluetoothPbapObexServer.ContentType.MISSED_CALL_HISTORY:
                selection = Calls.TYPE + "=" + CallLog.Calls.MISSED_TYPE;
                break;
            default:
                break;
        }
        size = getCallHistorySize(type);
        try {
            if (pos >= 0 && pos < size) {
                Uri myUri = CallLog.Calls.CONTENT_URI;
                return loadCallHistory(myUri, pos, selection, vCard21);
            } else {
                Log.w(TAG, "pos invalid");
            }
        } catch (Exception e) {
            Log.e(TAG, "catch exception e" + e.toString());
        }
        return null;
    }

    public final ArrayList<String> loadNameList() {
        ArrayList<String> nameList = new ArrayList<String>();
        int size = getPhonebookSize();
        Uri myUri = Contacts.People.CONTENT_URI;
        Cursor contactC = mResolver.query(myUri, null, null, null, null);
        for (int pos = 0; pos < size; pos++) {
            if (pos == 0) {
                nameList.add(getThisPhoneName());
            } else {
                if (contactC != null) {
                    contactC.moveToPosition(pos - 1);
                    String name = contactC.getString(contactC
                            .getColumnIndexOrThrow(Contacts.People.NAME));
                    if (name == null || name.trim().length() == 0) {
                        name = mDefaultName;
                    }
                    nameList.add(name);
                }
            }
        }
        if (contactC != null) {
            contactC.close();
        }
        return nameList;
    }

    public final ArrayList<String> loadNumberList() {
        ArrayList<String> numberList = new ArrayList<String>();
        if (numberList.size() > 0) {
            numberList.clear();
        }
        int size = getPhonebookSize();
        Uri myUri = Contacts.People.CONTENT_URI;
        Cursor contactC = mResolver.query(myUri, null, null, null, null);
        for (int pos = 0; pos < size; pos++) {
            if (pos == 0) {
                numberList.add(getThisPhoneNumber());
            } else {
                contactC.moveToPosition(pos - 1);
                String number = contactC.getString(contactC
                        .getColumnIndexOrThrow(Contacts.People.PRIMARY_PHONE_ID));
                if (number == null || number.trim().length() == 0) {
                    number = mDefaultNumber;
                }
                numberList.add(number);
            }
        }
        return numberList;
    }

    public final ArrayList<String> loadCallHistoryList(final int type) {
        int size = 0;
        String selection = null;
        Uri myUri = CallLog.Calls.CONTENT_URI;
        ArrayList<String> list = new ArrayList<String>();
        switch (type) {
            case BluetoothPbapObexServer.ContentType.INCOMING_CALL_HISTORY:
                selection = Calls.TYPE + "=" + CallLog.Calls.INCOMING_TYPE;
                break;
            case BluetoothPbapObexServer.ContentType.OUTGOING_CALL_HISTORY:
                selection = Calls.TYPE + "=" + CallLog.Calls.OUTGOING_TYPE;
                break;
            case BluetoothPbapObexServer.ContentType.MISSED_CALL_HISTORY:
                selection = Calls.TYPE + "=" + CallLog.Calls.MISSED_TYPE;
                break;
            default:
                break;
        }
        size = getCallHistorySize(type);
        Cursor callCursor = mResolver.query(myUri, CALL_LOG_PROJECTION, selection, null,
                CallLog.Calls.DEFAULT_SORT_ORDER);
        if (callCursor != null) {
            for (int pos = 0; pos < size; pos++) {
                callCursor.moveToPosition(pos);
                String name = callCursor.getString(CALLER_NAME_COLUMN_INDEX);
                if (name == null || name.trim().length() == 0) {
                    // name not found,use number instead
                    name = callCursor.getString(NUMBER_COLUMN_INDEX);
                }
                list.add(name);
            }
            callCursor.close();
        }
        return list;
    }

    private final String loadCallHistory(final Uri mUri, final int pos, final String selection,
            final boolean vCard21) {
        ContactStruct contactStruct = new ContactStruct();
        Cursor callCursor = mResolver.query(mUri, CALL_LOG_PROJECTION, selection, null,
                CallLog.Calls.DEFAULT_SORT_ORDER);
        if (callCursor != null) {
            if (callCursor.moveToPosition(pos)) {
                contactStruct.name = callCursor.getString(CALLER_NAME_COLUMN_INDEX);
                if (contactStruct.name == null || contactStruct.name.trim().length() == 0) {
                    contactStruct.name = callCursor.getString(NUMBER_COLUMN_INDEX);
                }
                String number = callCursor.getString(NUMBER_COLUMN_INDEX);
                int type = callCursor.getInt(CALLER_NUMBERTYPE_COLUMN_INDEX);
                String label = callCursor.getString(CALLER_NUMBERLABEL_COLUMN_INDEX);
                if (label == null || label.trim().length() == 0) {
                    label = Integer.toString(type);
                }
                contactStruct.addPhone(type, number, label, true);
            }
            callCursor.close();
        }
        try {
            VCardComposer composer = new VCardComposer();
            if (vCard21) {
                return composer.createVCard(contactStruct, VCardParser.VERSION_VCARD21_INT);
            } else {
                return composer.createVCard(contactStruct, VCardParser.VERSION_VCARD30_INT);
            }
        } catch (VCardException e) {
            Log.e(TAG, "catch exception" + e.toString());
            return null;
        }
    }

    private final String loadPhonebook(final Uri mUri, final int pos, final boolean vCard21) {
        // Build up the phone entries
        ContactStruct contactStruct = new ContactStruct();
        Cursor personCursor = mResolver.query(mUri, CONTACT_PROJECTION, null, null, null);
        if (personCursor == null) {
            return null;
        }
        if (pos == 0) {
            contactStruct.name = getThisPhoneName();
            contactStruct.addPhone(Contacts.PhonesColumns.TYPE_MOBILE, getThisPhoneNumber(), "",
                    true);
        } else if (personCursor.moveToNext()) {
            contactStruct.name = personCursor.getString(CONTACT_NAME_COLUMN);
            if (contactStruct.name == null || contactStruct.name.trim().length() == 0) {
                contactStruct.name = mDefaultName;
            }
            contactStruct.notes.add(checkStrEnd(personCursor.getString(CONTACT_NOTES_COLUMN),
                    vCard21));
            final Uri phonesUri = Uri.withAppendedPath(mUri, People.Phones.CONTENT_DIRECTORY);
            final Cursor phonesCursor = mResolver.query(phonesUri, PHONES_PROJECTION, null, null,
                    Phones.ISPRIMARY + " DESC");
            if (phonesCursor != null) {
                for (phonesCursor.moveToFirst(); !phonesCursor.isAfterLast();
                            phonesCursor.moveToNext()) {
                    int type = phonesCursor.getInt(PHONES_TYPE_COLUMN);
                    String number = phonesCursor.getString(PHONES_NUMBER_COLUMN);
                    String label = phonesCursor.getString(PHONES_LABEL_COLUMN);
                    contactStruct.addPhone(type, number, label, true);
                }
                phonesCursor.close();
            }
            // Build the contact method entries
            final Uri methodsUri = Uri.withAppendedPath(mUri,
                    People.ContactMethods.CONTENT_DIRECTORY);
            Cursor methodsCursor = mResolver
                    .query(methodsUri, METHODS_PROJECTION, null, null, null);
            if (methodsCursor != null) {
                for (methodsCursor.moveToFirst(); !methodsCursor.isAfterLast();
                           methodsCursor.moveToNext()) {
                    int kind = methodsCursor.getInt(METHODS_KIND_COLUMN);
                    String label = methodsCursor.getString(METHODS_LABEL_COLUMN);
                    String data = methodsCursor.getString(METHODS_DATA_COLUMN);
                    int type = methodsCursor.getInt(METHODS_TYPE_COLUMN);
                    boolean isPrimary = methodsCursor.getInt(METHODS_ISPRIMARY_COLUMN) == 1 ? true
                            : false;
                    // TODO
                    // Below code is totally depend on the implementation of
                    // package android.syncml.pim.vcard
                    // VcardComposer shall throw null pointer exception when
                    // label is not set
                    // function appendContactMethodStr is weak and shall be
                    // improved in the future
                    if (kind == Contacts.KIND_EMAIL) {
                        if (type == Contacts.ContactMethodsColumns.TYPE_OTHER) {
                            if (label == null || label.trim().length() == 0) {
                                label = Integer.toString(type);
                            }
                        }
                    }
                    if (kind == Contacts.KIND_POSTAL) {
                        data = checkStrEnd(data, vCard21);
                    }
                    contactStruct.addContactmethod(kind, type, data, label, isPrimary);
                }
                methodsCursor.close();
            }

            // Build the organization entries
            final Uri organizationsUri = Uri
                    .withAppendedPath(mUri, Organizations.CONTENT_DIRECTORY);
            Cursor organizationsCursor = mResolver.query(organizationsUri,
                    ORGANIZATIONS_PROJECTION, "isprimary", null, null);

            if (organizationsCursor != null) {
                for (organizationsCursor.moveToFirst(); !organizationsCursor.isAfterLast();
                           organizationsCursor.moveToNext()) {
                    int type = organizationsCursor.getInt(ORGANIZATIONS_TYPE_COLUMN);
                    String company = organizationsCursor.getString(ORGANIZATIONS_COMPANY_COLUMN);
                    String title = checkStrEnd(organizationsCursor
                            .getString(ORGANIZATIONS_TITLE_COLUMN), vCard21);
                    boolean isPrimary = organizationsCursor.
                                   getInt(ORGANIZATIONS_ISPRIMARY_COLUMN) == 1 ? true
                            : false;
                    contactStruct.addOrganization(type, company, title, isPrimary);
                }
                organizationsCursor.close();
            }
        }
        personCursor.close();
        // Generate vCard data.
        try {
            VCardComposer composer = new VCardComposer();
            if (vCard21) {
                return composer.createVCard(contactStruct, VCardParser.VERSION_VCARD21_INT);
            } else {
                return composer.createVCard(contactStruct, VCardParser.VERSION_VCARD30_INT);
            }
        } catch (VCardException e) {
            Log.e(TAG, "catch exception in loadPhonebook" + e.toString());
            return null;
        }
    }

    /**
     * This function is to check the string end to avoid function
     * foldingString's returning null in package android.syncml.pim.vcard
     */
    private final String checkStrEnd(String str, final boolean vCard21) {
        if (str == null) {
            return str;
        }
        if (str.charAt(str.length() - 1) != '\n') {
            if (vCard21) {
                str += "\r\n";
            } else {
                str += "\n";
            }
        }
        return str;
    }

}
