/*
 * Copyright (c) 2015, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.mapapi;

import android.content.ContentResolver;
import android.net.Uri;
import com.android.bluetooth.mapapi.BluetoothMapContract.MessageColumns;
import com.android.bluetooth.mapapi.BluetoothMapContract.EmailMessageColumns;
import com.android.bluetooth.mapapi.BluetoothMapContract.FolderColumns;


/**
 * This class defines the minimum sets of data needed for a client to
 * implement to claim support for the Bluetooth Message Access Profile.
 * Access to three data sets are needed:
 * <ul>
 *   <li>Message data set containing lists of messages.</li>
 *   <li>Account data set containing info on the existing accounts, and whether to expose
 *     these accounts. The content of the account data set is often sensitive information,
 *     hence care must be taken, not to reveal any personal information nor passwords.
 *     The accounts in this data base will be exposed in the settings menu, where the user
 *     is able to enable and disable the EXPOSE_FLAG, and thereby provide access to an
 *     account from another device, without any password protection the e-mail client
 *     might provide.</li>
 *   <li>Folder data set with the folder structure for the messages. Each message is linked to an
 *     entry in this data set.</li>
 *   <li>Conversation data set with the thread structure of the messages. Each message is linked
 *     to an entry in this data set.</li>
 * </ul>
 *
 * To enable that the Bluetooth Message Access Server can detect the content provider implementing
 * this interface, the {@code provider} tag for the Bluetooth related content provider must
 * have an intent-filter like the following in the manifest:
 * <pre class="prettyprint">&lt;provider  android:authorities="[PROVIDER AUTHORITY]"
              android:exported="true"
              android:enabled="true"
              android:permission="android.permission.BLUETOOTH_MAP"&gt;
 *   ...
 *      &lt;intent-filter&gt;
           &lt;action android:name="android.content.action.BLEUETOOT_MAP_PROVIDER" /&gt;
        &lt;/intent-filter&gt;
 *   ...
 *   &lt;/provider&gt;
 * [PROVIDER AUTHORITY] shall be the providers authority value which implements this
 * contract. Only a single authority shall be used. The android.permission.BLUETOOTH_MAP
 * permission is needed for the provider.
 */
public final class BluetoothMapEmailContract {
    /**
     * Constructor - should not be used
     */
    private BluetoothMapEmailContract(){
      /* class should not be instantiated */
    }

    public static final String EMAIL_AUTHORITY = "com.android.email.provider";
    public static final String ACTION_CHECK_MAIL =
            "org.codeaurora.email.intent.action.MAIL_SERVICE_WAKEUP";
    public static final String EXTRA_ACCOUNT = "org.codeaurora.email.intent.extra.ACCOUNT";
    public static final String ACTION_DELETE_MESSAGE =
            "org.codeaurora.email.intent.action.MAIL_SERVICE_DELETE_MESSAGE";
    public static final String ACTION_MOVE_MESSAGE =
            "org.codeaurora.email.intent.action.MAIL_SERVICE_MOVE_MESSAGE";
    public static final String ACTION_MESSAGE_READ =
            "org.codeaurora.email.intent.action.MAIL_SERVICE_MESSAGE_READ";
    public static final String ACTION_SEND_PENDING_MAIL =
            "org.codeaurora.email.intent.action.MAIL_SERVICE_SEND_PENDING";
    public static final String EXTRA_MESSAGE_ID = "org.codeaurora.email.intent.extra.MESSAGE_ID";
    public static final String EXTRA_MESSAGE_INFO =
            "org.codeaurora.email.intent.extra.MESSAGE_INFO";

    /**
     * Build URI representing the given Accounts data-set in a
     * Bluetooth provider. When queried, the direct URI for the account
     * with the given accountID is returned.
     */
    public static Uri buildEmailAccountUri(String authority) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(EMAIL_TABLE_ACCOUNT).build();
    }

    /**
     * Build URI representing the given Accounts data-set in a
     * Bluetooth provider. When queried, the direct URI for the account
     * with the given accountID is returned.
     */
    public static Uri buildEmailAccountUriWithId(String authority, String accountId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(EMAIL_TABLE_ACCOUNT)
                .appendPath(accountId).build();
    }

    /**
     * Build URI representing the entire Message table in a
     * Bluetooth provider.
     */
    public static Uri buildEmailMessageUri(String authority) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath(EMAIL_TABLE_MESSAGE)
                .build();
    }

    /**
     * Build URI representing the entire Message table in a
     * Bluetooth provider.
     */
    public static Uri buildEmailMessageUri(String authority, String accountId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath(accountId)
                .appendPath(EMAIL_TABLE_MESSAGE)
                .build();
    }

    /**
     * Build URI representing the entire email Attachment table in a
     * Bluetooth provider.
     */
    public static Uri buildEmailAttachmentUri(String authority) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath(EMAIL_TABLE_ATTACHMENT)
                .build();
    }
    /**
     * Build URI representing the entire MessageBody table in a
     * Bluetooth provider.
     */
    public static Uri buildEmailMessageBodyUri(String authority) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath(EMAIL_TABLE_MSGBODY)
                .build();
    }
    /**
     * Build URI representing the given Message data-set in a
     * Bluetooth provider. When queried, the direct URI for the folder
     * with the given accountID is returned.
     */
    public static Uri buildMailboxUri(String authority) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath(EMAIL_TABLE_MAILBOX)
                .build();
    }

    /**
     *  @hide
     */
    public static final String EMAIL_TABLE_ACCOUNT  = "account";
    public static final String EMAIL_TABLE_MESSAGE  = "message";
    public static final String EMAIL_TABLE_ATTACHMENT = "attachment";
    public static final String EMAIL_TABLE_MSGBODY = "body";
    public static final String EMAIL_TABLE_MAILBOX  = "mailbox";

    /**
     * Mandatory folders for the Bluetooth message access profile.
     * The email client shall at least implement the following root folders.
     * E.g. as a mapping for them such that the naming will match the underlying
     * matching folder ID's.
     */
    public static final String FOLDER_NAME_INBOX   = "INBOX";
    public static final String FOLDER_NAME_SENT    = "SENT";
    public static final String FOLDER_NAME_OUTBOX  = "OUTBOX";
    public static final String FOLDER_NAME_DRAFT   = "DRAFT";
    public static final String FOLDER_NAME_DRAFTS  = "DRAFTS";
    public static final String FOLDER_NAME_DELETED = "DELETED";
    public static final String FOLDER_NAME_OTHER   = "OTHER";

    /**
     * Folder IDs to be used with Instant Messaging virtual folders
     */
    public static final long FOLDER_ID_OTHER      = 0;
    public static final long FOLDER_ID_INBOX      = 1;
    public static final long FOLDER_ID_SENT       = 2;
    public static final long FOLDER_ID_DRAFT      = 3;
    public static final long FOLDER_ID_OUTBOX     = 4;
    public static final long FOLDER_ID_DELETED    = 5;

    // Types of mailboxes. From EmailContent.java
    // inbox
    public static final int TYPE_INBOX = 0;
    // draft
    public static final int TYPE_DRAFT = 3;
    // outbox
    public static final int TYPE_OUTBOX = 4;
    // sent
    public static final int TYPE_SENT = 5;
    // deleted
    public static final int TYPE_DELETED = 6;
    // Values used in mFlagLoaded
    public static final int FLAG_LOADED_COMPLETE = 1;

    public interface EmailBodyColumns {
        // Foreign key to the message corresponding to this body
        public static final String MESSAGE_KEY = "messageKey";
        // The html content itself, not returned on query
        public static final String HTML_CONTENT = "htmlContent";
        // The html content URI, for ContentResolver#openFileDescriptor()
        public static final String HTML_CONTENT_URI = "htmlContentUri";
        // The plain text content itself, not returned on query
        public static final String TEXT_CONTENT = "textContent";
       // The text content URI, for ContentResolver#openFileDescriptor()
       public static final String TEXT_CONTENT_URI = "textContentUri";
       // Replied-to or forwarded body (in html form)
    }

    public interface ExtEmailMessageColumns extends EmailMessageColumns {
        public static final String RECORD_ID = "_id";
        public static final String DISPLAY_NAME = "displayName";
        public static final String EMAIL_ADDRESS = "emailAddress";
        public static final String ACCOUNT_KEY = "accountKey";
        public static final String IS_DEFAULT = "isDefault";
        public static final String EMAIL_TYPE = "type";
        public static final String MAILBOX_KEY = "mailboxKey";
        // The time (millis) as shown to the user in a message list [INDEX]
        public static final String TIMESTAMP = "timeStamp";
        public static final String EMAIL_SERVICE_NAME = "EMAIL Message Access";
        /**
         * Message Read flag
         * <P>Type: INTEGER (boolean) unread = 0, read = 1</P>
         *  read/write
         */
        public static final String EMAIL_FLAG_READ = "flagRead";
        /** The overall size in bytes of the message including any attachments.
         * This value is informative only and should be the size an email client
         * would display as size for the message.
         * <P>Type: INTEGER </P>
         * read-only
         */
        public static final String EMAIL_ATTACHMENT_SIZE = "size";
        public static final String FLAGS = "flags";
        public static final String FLAG_LOADED = "flagLoaded";
        // Boolean, no attachment = 0, attachment = 1
        public static final String EMAIL_FLAG_ATTACHMENT = "flagAttachment";
        // Saved draft info (reusing the never-used "clientId" column)
        public static final String DRAFT_INFO = "clientId";
       // The message-id in the message's header
       public static final String MESSAGE_ID = "messageId";
       // Address lists, packed with Address.pack()
       public static final String EMAIL_FROM_LIST = "fromList";
       public static final String EMAIL_TO_LIST = "toList";
       public static final String EMAIL_CC_LIST = "ccList";
       public static final String EMAIL_BCC_LIST = "bccList";
       public static final String EMAIL_REPLY_TO_LIST = "replyToList";
       public static final String MEETING_INFO = "meetingInfo";
       public static final String SNIPPET = "snippet";
       public static final String PROTOCOL_SEARCH_INFO = "protocolSearchInfo";
       public static final String THREAD_TOPIC = "threadTopic";
    }

    /**
     * Message folder structure
     * MAP enforces use of a folder structure with mandatory folders:
     *   - inbox, outbox, sent, deleted, draft
     * User defined folders are supported.
     * The folder table must provide filtering (use of WHERE clauses) of the following entries:
     *   - account_id (linking the folder to an e-mail account)
     *   - parent_id (linking the folders individually)
     * The folder table must have a folder name for each entry, and the mandatory folders
     * MUST exist for each account_id. The folders may be empty.
     * Use the FOLDER_NAME_xxx constants for the mandatory folders. Their names must
     * not be translated into other languages, as the folder browsing is string based, and
     * many Bluetooth Message Clients will use these strings to navigate to the folders.
     */
    public interface MailBoxColumns extends FolderColumns {
        public static final String DISPLAY_NAME = "displayName";
        public static final String SERVER_ID = "serverId";
        public static final String PARENT_SERVER_ID = "parentServerId";
        public static final String ACCOUNT_KEY = "accountKey";
    }

    public static final String[] BT_EMAIL_ATTACHMENT_PROJECTION = new String[] {
        ExtEmailMessageColumns.EMAIL_ATTACHMENT_SIZE
    };

    public static final String[] BT_EMAIL_MESSAGE_PROJECTION = new String[] {
        ExtEmailMessageColumns.RECORD_ID,
        ExtEmailMessageColumns.MAILBOX_KEY,
        ExtEmailMessageColumns.ACCOUNT_KEY,
        ExtEmailMessageColumns.DISPLAY_NAME,
        ExtEmailMessageColumns.TIMESTAMP,
        ExtEmailMessageColumns.EMAIL_FLAG_READ,
        MessageColumns.SUBJECT,
        ExtEmailMessageColumns.EMAIL_FLAG_ATTACHMENT,
        ExtEmailMessageColumns.FLAG_LOADED,
        ExtEmailMessageColumns.FLAGS,
        ExtEmailMessageColumns.DRAFT_INFO,
        ExtEmailMessageColumns.MESSAGE_ID,
        ExtEmailMessageColumns.EMAIL_FROM_LIST,
        ExtEmailMessageColumns.EMAIL_TO_LIST,
        ExtEmailMessageColumns.EMAIL_CC_LIST,
        ExtEmailMessageColumns.EMAIL_BCC_LIST,
        ExtEmailMessageColumns.EMAIL_REPLY_TO_LIST,
        ExtEmailMessageColumns.MEETING_INFO,
        ExtEmailMessageColumns.SNIPPET,
        ExtEmailMessageColumns.PROTOCOL_SEARCH_INFO,
        ExtEmailMessageColumns.THREAD_TOPIC
    };

    public static final String[] BT_EMAIL_MSG_PROJECTION_SHORT = new String[] {
        ExtEmailMessageColumns.ACCOUNT_KEY,
        ExtEmailMessageColumns.RECORD_ID,
        ExtEmailMessageColumns.MAILBOX_KEY,
        ExtEmailMessageColumns.EMAIL_FLAG_READ
    };

    public static final String[] BT_EMAIL_MSG_PROJECTION_SHORT_EXT = new String[] {
        ExtEmailMessageColumns.ACCOUNT_KEY,
        ExtEmailMessageColumns.RECORD_ID,
        ExtEmailMessageColumns.MAILBOX_KEY,
        ExtEmailMessageColumns.TIMESTAMP,
        MessageColumns.SUBJECT,
        ExtEmailMessageColumns.EMAIL_FROM_LIST,
        ExtEmailMessageColumns.EMAIL_FLAG_READ
    };

    public static final String[] BT_EMAIL_BODY_CONTENT_PROJECTION = new String[] {
        MessageColumns._ID,
        EmailBodyColumns.MESSAGE_KEY,
        EmailBodyColumns.HTML_CONTENT_URI,
        EmailBodyColumns.TEXT_CONTENT_URI
    };


    public static final String[] BT_EMAIL_ACCOUNT_ID_PROJECTION = new String[] {
        ExtEmailMessageColumns.RECORD_ID,
        ExtEmailMessageColumns.DISPLAY_NAME,
        ExtEmailMessageColumns.IS_DEFAULT
    };

    /**
     * A projection of all the columns in the Folder table
     */
    public static final String[] BT_FOLDER_PROJECTION = new String[] {
        FolderColumns._ID,
        FolderColumns.NAME,
        FolderColumns.ACCOUNT_ID,
        FolderColumns.PARENT_FOLDER_ID
    };

    /**
     * A projection of all the columns in the Folder table
     */
    public static final String[] BT_EMAIL_MAILBOX_PROJECTION = new String[] {
        FolderColumns._ID,
        MailBoxColumns.DISPLAY_NAME,
        MailBoxColumns.ACCOUNT_KEY,
        MailBoxColumns.SERVER_ID,
        MailBoxColumns.PARENT_SERVER_ID
    };

}
