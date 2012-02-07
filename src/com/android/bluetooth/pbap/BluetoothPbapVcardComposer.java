/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.content.ContentValues;
import android.content.Context;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

import com.android.vcard.VCardBuilder;
import com.android.vcard.VCardComposer;
import com.android.vcard.VCardConfig;
import com.android.vcard.VCardPhoneNumberTranslationCallback;

import java.util.List;
import java.util.Map;

public class BluetoothPbapVcardComposer extends VCardComposer
{
    private static final String LOG_TAG = "BluetoothPbapVcardComposer";

    public static final long FILTER_VERSION = (1L << 0); // vCard Version
    public static final long FILTER_FN = (1L << 1); // Formatted Name
    public static final long FILTER_N = (1L << 2); // Structured Presentation of Name
    public static final long FILTER_PHOTO = (1L << 3); // Associated Image or Photo
    public static final long FILTER_BDAY = (1L << 4); // Birthday
    public static final long FILTER_ADR = (1L << 5); // Delivery Address
    public static final long FILTER_LABEL = (1L << 6); // Delivery
    public static final long FILTER_TEL = (1L << 7); // Telephone Number
    public static final long FILTER_EMAIL = (1L << 8); // Electronic Mail Address
    public static final long FILTER_MAILER = (1L << 9); // Electronic Mail
    public static final long FILTER_TZ = (1L << 10); // Time Zone
    public static final long FILTER_GEO = (1L << 11); // Geographic Position
    public static final long FILTER_TITLE = (1L << 12); // Job
    public static final long FILTER_ROLE = (1L << 13); // Role within the Organization
    public static final long FILTER_LOGO = (1L << 14); // Organization Logo
    public static final long FILTER_AGENT = (1L << 15); // vCard of Person Representing
    public static final long FILTER_ORG = (1L << 16); // Name of Organization
    public static final long FILTER_NOTE = (1L << 17); // Comments
    public static final long FILTER_REV = (1L << 18); // Revision
    public static final long FILTER_SOUND = (1L << 19); // Pronunciation of Name
    public static final long FILTER_URL = (1L << 20); // Uniform Resource Locator
    public static final long FILTER_UID = (1L << 21); // Unique ID
    public static final long FILTER_KEY = (1L << 22); // Public Encryption Key
    public static final long FILTER_NICKNAME = (1L << 23); // Nickname
    public static final long FILTER_CATEGORIES = (1L << 24); // Categories
    public static final long FILTER_PROID = (1L << 25); // Product ID
    public static final long FILTER_CLASS = (1L << 26); // Class information
    public static final long FILTER_SORT_STRING = (1L << 27); // String used for sorting operations
    public static final long FILTER_X_IRMC_CALL_DATETIME = (1L << 28); // Time stamp

    private final int mVCardType;
    private final String mCharset;
    private final long mFilter;

    // BT does want PAUSE/WAIT conversion while it doesn't want the other formatting
    // done by vCard library by default.
    private static final VCardPhoneNumberTranslationCallback TRANSLATION_CALLBACK =
            new VCardPhoneNumberTranslationCallback() {
        @Override
        public String onValueReceived(String rawValue, int type, String label, boolean isPrimary) {
            // 'p' and 'w' are the standard characters for pause and wait
            // (see RFC 3601)
            // so use those when exporting phone numbers via vCard.
            String numberWithControlSequence = rawValue
                    .replace(PhoneNumberUtils.PAUSE, 'p')
                    .replace(PhoneNumberUtils.WAIT, 'w');
            return numberWithControlSequence;
        }
    };

    public BluetoothPbapVcardComposer(final Context context, final int vcardType,
            long filter, final boolean careHandlerErrors) {
        super(context, vcardType, null, careHandlerErrors);
        mVCardType = vcardType;
        mCharset = null;
        mFilter = filter;
        setPhoneNumberTranslationCallback(TRANSLATION_CALLBACK);
    }

    public String buildVCard(final Map<String, List<ContentValues>> contentValuesListMap) {
        if (contentValuesListMap == null) {
            Log.e(LOG_TAG, "The given map is null. Ignore and return empty String");
            return "";
        } else {
            if (Log.isLoggable(BluetoothPbapService.LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "buildVCard filter = " + mFilter);
            }
            final VCardBuilder builder = new VCardBuilder(mVCardType, mCharset);
            // TODO: not perfect here - perhaps subclass VCardBuilder to separate N and FN
            if (((mFilter & FILTER_N) != 0) || ((mFilter & FILTER_FN) != 0)) {
                builder.appendNameProperties(contentValuesListMap
                        .get(StructuredName.CONTENT_ITEM_TYPE));
            }
            if ((mFilter & FILTER_NICKNAME) != 0) {
                builder.appendNickNames(contentValuesListMap.get(Nickname.CONTENT_ITEM_TYPE));
            }
            if ((mFilter & FILTER_TEL) != 0) {
                builder.appendPhones(contentValuesListMap.get(Phone.CONTENT_ITEM_TYPE),
                        TRANSLATION_CALLBACK);
            }
            if ((mFilter & FILTER_EMAIL) != 0) {
                builder.appendEmails(contentValuesListMap.get(Email.CONTENT_ITEM_TYPE));
            }
            if ((mFilter & FILTER_ADR) != 0) {
                builder.appendPostals(contentValuesListMap.get(StructuredPostal.CONTENT_ITEM_TYPE));
            }
            if ((mFilter & FILTER_ORG) != 0) {
                builder.appendOrganizations(contentValuesListMap
                        .get(Organization.CONTENT_ITEM_TYPE));
            }
            if ((mFilter & FILTER_URL) != 0) {
                builder.appendWebsites(contentValuesListMap.get(Website.CONTENT_ITEM_TYPE));
            }
            if ((mFilter & FILTER_PHOTO) != 0) {
                builder.appendPhotos(contentValuesListMap.get(Photo.CONTENT_ITEM_TYPE));
            }
            if ((mFilter & FILTER_NOTE) != 0) {
                builder.appendNotes(contentValuesListMap.get(Note.CONTENT_ITEM_TYPE));
            }
            if ((mFilter & FILTER_BDAY) != 0) {
                builder.appendEvents(contentValuesListMap.get(Event.CONTENT_ITEM_TYPE));
            }
            return builder.toString();
        }
    }
}
