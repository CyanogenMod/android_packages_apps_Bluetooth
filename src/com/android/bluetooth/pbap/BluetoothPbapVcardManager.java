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
import android.provider.Contacts.People.ContactMethods;
import android.syncml.pim.vcard.ContactStruct;
import android.syncml.pim.vcard.VCardComposer;
import android.syncml.pim.vcard.VCardException;
import android.syncml.pim.vcard.VCardParser;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class BluetoothPbapVcardManager {
    static private final String TAG = "BluetoothPbapVcardManager";

    private static final boolean V = BluetoothPbapService.VERBOSE;

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
            People.PRIMARY_EMAIL_ID, // 9
            People.PRIMARY_ORGANIZATION_ID, // 10
    };

    public static final int CONTACT_ID_COLUMN = 0;

    public static final int CONTACT_NAME_COLUMN = 1;

    public static final int CONTACT_NOTES_COLUMN = 2;

    public static final int CONTACT_PRIMARY_PHONE_ID_COLUMN = 3;

    public static final int CONTACT_SERVER_STATUS_COLUMN = 4;

    public static final int CONTACT_STARRED_COLUMN = 5;

    public static final int CONTACT_CUSTOM_RINGTONE_COLUMN = 6;

    public static final int CONTACT_SEND_TO_VOICEMAIL_COLUMN = 7;

    public static final int CONTACT_PHONETIC_NAME_COLUMN = 8;

    public static final int CONTACT_PRIMARY_EMAIL_ID_COLUMN = 9;

    public static final int CONTACT_PRIMARY_ORGANIZATION_ID_COLUMN = 10;

    public static final String[] PHONES_PROJECTION = new String[] {
            Phones._ID, // 0
            Phones.NUMBER, // 1
            Phones.TYPE, // 2
            Phones.LABEL, // 3
            Phones.ISPRIMARY, // 4
            Phones.PERSON_ID, // 5
    };

    public static final int PHONES_ID_COLUMN = 0;

    public static final int PHONES_NUMBER_COLUMN = 1;

    public static final int PHONES_TYPE_COLUMN = 2;

    public static final int PHONES_LABEL_COLUMN = 3;

    public static final int PHONES_ISPRIMARY_COLUMN = 4;

    public static final int PHONES_PERSON_ID_COLUMN = 5;

    public static final String[] METHODS_PROJECTION = new String[] {
            ContactMethods._ID, // 0
            ContactMethods.KIND, // 1
            ContactMethods.DATA, // 2
            ContactMethods.TYPE, // 3
            ContactMethods.LABEL, // 4
            ContactMethods.ISPRIMARY, // 5
            ContactMethods.AUX_DATA, // 6
            Phones.PERSON_ID, // 7
    };

    public static final int METHODS_ID_COLUMN = 0;

    public static final int METHODS_KIND_COLUMN = 1;

    public static final int METHODS_DATA_COLUMN = 2;

    public static final int METHODS_TYPE_COLUMN = 3;

    public static final int METHODS_LABEL_COLUMN = 4;

    public static final int METHODS_ISPRIMARY_COLUMN = 5;

    public static final int METHODS_AUX_DATA_COLUMN = 6;

    public static final int METHODS_PERSON_COLUMN = 7;

    public static final String[] ORGANIZATIONS_PROJECTION = new String[] {
            Organizations._ID, // 0
            Organizations.TYPE, // 1
            Organizations.LABEL, // 2
            Organizations.COMPANY, // 3
            Organizations.TITLE, // 4
            Organizations.ISPRIMARY, // 5
            Organizations.PERSON_ID, // 6
    };

    public static final int ORGANIZATIONS_ID_COLUMN = 0;

    public static final int ORGANIZATIONS_TYPE_COLUMN = 1;

    public static final int ORGANIZATIONS_LABEL_COLUMN = 2;

    public static final int ORGANIZATIONS_COMPANY_COLUMN = 3;

    public static final int ORGANIZATIONS_TITLE_COLUMN = 4;

    public static final int ORGANIZATIONS_ISPRIMARY_COLUMN = 5;

    public static final int ORGANIZATIONS_PERSON_ID_COLUMN = 6;

    public static final String SORT_ORDER = "person ASC";

    private int QUERY_DB_ERROR = -1;

    private int QUERY_DB_OK = 1;

    private Cursor peopleCursor, phonesCursor, contactMethodsCursor, orgCursor, callLogCursor;

    private HashMap<Integer, String> mPhones;

    private HashMap<Integer, String> mContactMethods;

    private HashMap<Integer, String> mOrganizations;

    private HashMap<Integer, HashMap<Integer, String>> tableList;

    public static final int TABLE_PHONE = 0;

    public static final int TABLE_CONTACTMETHOD = 1;

    public static final int TABLE_ORGANIZATION = 2;

    public BluetoothPbapVcardManager(final Context context) {
        mContext = context;
        mResolver = mContext.getContentResolver();
        mDefaultName = context.getString(android.R.string.unknownName);
        mDefaultNumber = context.getString(R.string.defaultnumber);

        mPhones = new HashMap<Integer, String>();
        mContactMethods = new HashMap<Integer, String>();
        mOrganizations = new HashMap<Integer, String>();

        tableList = new HashMap<Integer, HashMap<Integer, String>>();
        tableList.put(TABLE_PHONE, mPhones);
        tableList.put(TABLE_CONTACTMETHOD, mContactMethods);
        tableList.put(TABLE_ORGANIZATION, mOrganizations);
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

    /**
     * For each table - phones/contact_methods/organization:
     * Save the cursor positions for one person to hashMap.
     * @hide
     */
    private int fillDataToHashmap(int table) {
        int person_column;
        Cursor cursor;

        if (table == TABLE_PHONE) {
            cursor = phonesCursor;
            person_column = PHONES_PERSON_ID_COLUMN;

        } else if (table == TABLE_CONTACTMETHOD) {
            cursor = contactMethodsCursor;
            person_column = METHODS_PERSON_COLUMN;

        } else if (table == TABLE_ORGANIZATION) {
            cursor = orgCursor;
            person_column = ORGANIZATIONS_PERSON_ID_COLUMN;

        } else {
            Log.w(TAG, "fillDataToHashmap(): - no such table-" + table);
            return -1;
        }

        // Clear HashMap first
        tableList.get(table).clear();

        // Represent the cursor position for phone_id or contactMethod_id or
        // orgnization_id
        int x_cursor_pos = 0;
        int person_id = 0;
        int previous_person_id = 0;
        StringBuilder result = new StringBuilder();
        for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
            person_id = cursor.getInt(person_column);

            if (cursor.isFirst()) {
                previous_person_id = person_id;
            }

            if (person_id != previous_person_id) {
                tableList.get(table).put(previous_person_id, result.toString());
                result = result.delete(0, result.length());
            }
            result.append(x_cursor_pos);
            result.append(",");
            previous_person_id = person_id;
            x_cursor_pos++;

            if (cursor.isLast()) {
                tableList.get(table).put(previous_person_id, result.toString());
            }
        }
        return 1;
    }

    public final void closeContactsCursor() {
        peopleCursor.close();
        contactMethodsCursor.close();
        orgCursor.close();
    }

    public final void closeCallLogCursor() {
        callLogCursor.close();
    }

    public final int queryDataFromCallLogDB(int type) {
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
        callLogCursor = mResolver.query(myUri, CALL_LOG_PROJECTION, selection, null,
                CallLog.Calls.DEFAULT_SORT_ORDER);

        if (callLogCursor == null) {
            Log.w(TAG, "Query table - call Log failed.");
            return QUERY_DB_ERROR;
        }
        return QUERY_DB_OK;
    }

    public final int queryDataFromContactsDB() {
        Uri peopleUri = Contacts.People.CONTENT_URI;
        peopleCursor = mResolver.query(peopleUri, CONTACT_PROJECTION, null, null,
                People._ID + " ASC");
        if (peopleCursor == null) {
            Log.w(TAG, "Query table - people failed.");
            return QUERY_DB_ERROR;
        }

        Uri phonesUri = Contacts.Phones.CONTENT_URI;
        phonesCursor = mResolver.query(phonesUri, PHONES_PROJECTION, null, null, SORT_ORDER);
        if (phonesCursor == null) {
            Log.w(TAG, "Query table - phone failed.");
            return QUERY_DB_ERROR;
        }
        fillDataToHashmap(TABLE_PHONE);

        Uri contactMethodsUri = Contacts.ContactMethods.CONTENT_URI;
        contactMethodsCursor = mResolver.query(contactMethodsUri, METHODS_PROJECTION, null, null,
                SORT_ORDER);
        if (contactMethodsCursor == null) {
            Log.w(TAG, "Query table - contact_method failed.");
            return QUERY_DB_ERROR;
        }
        fillDataToHashmap(TABLE_CONTACTMETHOD);

        Uri orgUri = Contacts.Organizations.CONTENT_URI;
        orgCursor = mResolver.query(orgUri, ORGANIZATIONS_PROJECTION, null, null, SORT_ORDER);
        if (orgCursor == null) {
            Log.w(TAG, "Query table - organization failed.");
            return QUERY_DB_ERROR;
        }
        fillDataToHashmap(TABLE_ORGANIZATION);

        return QUERY_DB_OK;
    }

    private List<String> parseOutCursorPosForOnePerson(int table, int peopleId) {
        if (V) Log.v(TAG, "parseOutCursorPos(): table=" + table + "; peopleId=" + peopleId);

        String tmpStr = tableList.get(table).get(peopleId);
        if (tmpStr == null || tmpStr.trim().length() == 0) {
            Log.w(TAG, "Can not get curPosStr from HashMap.");
            return null;
        }

        String[] splitStr = tmpStr.split(",");

        List<String> list = Arrays.asList(splitStr);

        if (V) Log.v(TAG, "parseOutIds(): list=" + list.toString());

        return list;
    }

    public final String getPhonebook(final int pos, final boolean vcardType) {
        // Build up the phone entries
        ContactStruct contactStruct = new ContactStruct();

        if (pos == 0) {
            contactStruct.name = getThisPhoneName();
            contactStruct.addPhone(Contacts.PhonesColumns.TYPE_MOBILE, getThisPhoneNumber(), "",
                    true);
        } else {
            if (!peopleCursor.moveToPosition(pos - 1)) {
                Log.w(TAG, "peopleCursor can not moveToPosition: " + (pos - 1));
                return null;
            }

            int peopleId = peopleCursor.getInt(CONTACT_ID_COLUMN);
            int primary_phone = peopleCursor.getInt(CONTACT_PRIMARY_PHONE_ID_COLUMN);
            int primary_contactMethod = peopleCursor.getInt(CONTACT_PRIMARY_EMAIL_ID_COLUMN);
            int primary_org = peopleCursor.getInt(CONTACT_PRIMARY_ORGANIZATION_ID_COLUMN);

            String name = peopleCursor.getString(CONTACT_NAME_COLUMN);
            if (V) Log.v(TAG, "query data from table-people: name=" + name + "; primary_phone="
                        + primary_phone + "; primary_contactmehtod=" + primary_contactMethod
                        + "; primary_org=" + primary_org);


            // build basic info
            if (name == null || name.trim().length() == 0) {
                contactStruct.name = mDefaultName;
            } else {
                contactStruct.name = name;
            }
            contactStruct.notes.add(checkStrEnd(peopleCursor.getString(CONTACT_NOTES_COLUMN),
                    vcardType));

            String tmpStr = null;
            List<String> posList = null;
            String curPosStr = null;
            int cursorPos = 0;
            // build phone info
            if (primary_phone != 0) {
                posList = parseOutCursorPosForOnePerson(TABLE_PHONE, peopleId);
                for (int i = 0; i < posList.size(); i++) {
                    curPosStr = posList.get(i).toString();
                    cursorPos = Integer.parseInt(curPosStr);
                    if (cursorPos < 0 || cursorPos > phonesCursor.getCount()) {
                        Log.w(TAG, "Get incorrect cursor position from HashMap.");
                        continue;
                    }
                    if (phonesCursor.moveToPosition(cursorPos)) {
                        int type = phonesCursor.getInt(PHONES_TYPE_COLUMN);
                        String number = phonesCursor.getString(PHONES_NUMBER_COLUMN);
                        String label = phonesCursor.getString(PHONES_LABEL_COLUMN);
                        boolean isPrimary = phonesCursor.getInt(PHONES_ISPRIMARY_COLUMN)
                                == 1 ? true : false;
                        if (V) Log.v(TAG, "query data from table-phones: type=" + type +
                                "; number=" + number + "; label=" + label + "; isPrimary="
                                + isPrimary);

                        contactStruct.addPhone(type, number, label, isPrimary);
                    }
                }
            }

            // build contact_methods info
            if (primary_contactMethod != 0) {
                posList = parseOutCursorPosForOnePerson(TABLE_CONTACTMETHOD, peopleId);
                for (int i = 0; i < posList.size(); i++) {
                    curPosStr = posList.get(i).toString();
                    cursorPos = Integer.parseInt(curPosStr);
                    if (cursorPos < 0 || cursorPos > contactMethodsCursor.getCount()) {
                        Log.w(TAG, "Get incorrect method cursor position from HashMap.");
                        continue;
                    }
                    if (contactMethodsCursor.moveToPosition(cursorPos)) {
                        int kind = contactMethodsCursor.getInt(METHODS_KIND_COLUMN);
                        String label = contactMethodsCursor.getString(METHODS_LABEL_COLUMN);
                        String data = contactMethodsCursor.getString(METHODS_DATA_COLUMN);
                        int type = contactMethodsCursor.getInt(METHODS_TYPE_COLUMN);
                        boolean isPrimary = contactMethodsCursor.getInt(METHODS_ISPRIMARY_COLUMN)
                                        == 1 ? true : false;
                        /*
                         * TODO Below code is totally depend on the
                         * implementation of package android.syncml.pim.vcard
                         * VcardComposer shall throw null pointer exception when
                         * label is not set function appendContactMethodStr is
                         * weak and shall be improved in the future
                         */
                        if (kind == Contacts.KIND_EMAIL) {
                            if (type == Contacts.ContactMethodsColumns.TYPE_OTHER) {
                                if (label == null || label.trim().length() == 0) {
                                    label = Integer.toString(type);
                                }
                            }
                        }
                        if (kind == Contacts.KIND_POSTAL) {
                            data = checkStrEnd(data, vcardType);
                        }
                        if (V) Log.v(TAG, "query data from table-contactMethods: kind=" + kind
                                    + "; label=" + label + "; data=" + data);

                        contactStruct.addContactmethod(kind, type, data, label, isPrimary);
                    }
                }
            }

            // build organization info
            if (primary_org != 0) {
                posList = parseOutCursorPosForOnePerson(TABLE_CONTACTMETHOD, peopleId);
                for (int i = 0; i < posList.size(); i++) {
                    curPosStr = posList.get(i).toString();
                    cursorPos = Integer.parseInt(curPosStr);
                    if (cursorPos < 0 || cursorPos > orgCursor.getCount()) {
                        Log.w(TAG, "Get incorrect cursor position from HashMap.");
                        continue;
                    }
                    if (orgCursor.moveToPosition(cursorPos)) {
                        int type = orgCursor.getInt(ORGANIZATIONS_TYPE_COLUMN);
                        String company = orgCursor.getString(ORGANIZATIONS_COMPANY_COLUMN);
                        String title = checkStrEnd(orgCursor.getString(ORGANIZATIONS_TITLE_COLUMN),
                                vcardType);
                        boolean isPrimary;
                        isPrimary = orgCursor.getInt(ORGANIZATIONS_ISPRIMARY_COLUMN) == 1 ? true
                                : false;
                        if (V) Log.v(TAG, "query data from table-organization: type=" + type
                                    + "; company=" + company + "; title=" + title);

                        contactStruct.addOrganization(type, company, title, isPrimary);
                    }
                }
            }
        }

        String vcardStr;
        // Generate vCard data.
        try {
            VCardComposer composer = new VCardComposer();
            if (vcardType) {
                vcardStr = composer.createVCard(contactStruct, VCardParser.VERSION_VCARD21_INT);
            } else {
                vcardStr = composer.createVCard(contactStruct, VCardParser.VERSION_VCARD30_INT);
            }
        } catch (VCardException e) {
            Log.e(TAG, "catch exception in loadPhonebook" + e.toString());
            return null;
        }
        return vcardStr;
    }

    public final int getCallHistorySize(final int type) {
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
        int size = 0;
        if (callCursor != null) {
            size = callCursor.getCount();
            callCursor.close();
        }
        return size;
    }

    public final String getCallHistory(final int pos, final boolean vCard21) {
        try {
            int size = callLogCursor.getCount();
            if (pos >= 0 && pos < size) {
                ContactStruct contactStruct = new ContactStruct();
                if (callLogCursor.moveToPosition(pos)) {
                    contactStruct.name = callLogCursor.getString(CALLER_NAME_COLUMN_INDEX);
                    if (contactStruct.name == null || contactStruct.name.trim().length() == 0) {
                        contactStruct.name = callLogCursor.getString(NUMBER_COLUMN_INDEX);
                    }
                    String number = callLogCursor.getString(NUMBER_COLUMN_INDEX);
                    int type = callLogCursor.getInt(CALLER_NUMBERTYPE_COLUMN_INDEX);
                    String label = callLogCursor.getString(CALLER_NUMBERLABEL_COLUMN_INDEX);
                    if (label == null || label.trim().length() == 0) {
                        label = Integer.toString(type);
                    }
                    contactStruct.addPhone(type, number, label, true);
                }

                try {
                    VCardComposer composer = new VCardComposer();
                    if (vCard21) {
                        return composer.createVCard(contactStruct,
                                VCardParser.VERSION_VCARD21_INT);
                    } else {
                        return composer.createVCard(contactStruct,
                                VCardParser.VERSION_VCARD30_INT);
                    }
                } catch (VCardException e) {
                    Log.e(TAG, "catch exception" + e.toString());
                }
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

    /**
     * This function is to check the string end to avoid function
     * foldingString's returning null in package android.syncml.pim.vcard
     */
    private final String checkStrEnd(String str, final boolean vCard21) {
        if (str == null || str.trim().length() == 0) {
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
