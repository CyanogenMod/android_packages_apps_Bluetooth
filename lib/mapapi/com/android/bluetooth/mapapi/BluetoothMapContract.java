/*
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


/**
 * This class defines the minimum sets of data needed for an E-mail client to
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
 * </ul>
 *
 * To enable that the Bluetooth Message Access Server can detect the content provider implementing
 * this interface, the {@code provider} tag for the bluetooth related content provider must
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
public final class BluetoothMapContract {
    /**
     * Constructor - should not be used
     */
    private BluetoothMapContract(){
      /* class should not be instantiated */
    }

    /**
     * Provider interface that should be used as intent-filter action in the provider section
     * of the manifest file.
     */
    public static final String PROVIDER_INTERFACE = "android.bluetooth.action.BLUETOOTH_MAP_PROVIDER";

    /**
     * The Bluetooth Message Access profile allows a remote BT-MAP client to trigger
     * an update of a folder for a specific e-mail account, register for reception
     * of new messages from the server.
     *
     * Additionally the Bluetooth Message Access profile allows a remote BT-MAP client
     * to push a message to a folder - e.g. outbox or draft. The Bluetooth profile
     * implementation will place a new message in one of these existing folders through
     * the content provider.
     *
     * ContentProvider.call() is used for these purposes, and the METHOD_UPDATE_FOLDER
     * method name shall trigger an update of the specified folder for a specified
     * account.
     *
     * This shall be a non blocking call simply starting the update, and the update should
     * both send and receive messages, depending on what makes sense for the specified
     * folder.
     * Bundle extra parameter will carry two INTEGER (long) values:
     *   EXTRA_UPDATE_ACCOUNT_ID containing the account_id
     *   EXTRA_UPDATE_FOLDER_ID containing the folder_id of the folder to update
     *
     * The status for send complete of messages shall be reported by updating the sent-flag
     * and e.g. for outbox messages, move them to the sent folder in the message table of the
     * content provider and trigger a change notification to any attached content observer.
     */
    public static final String METHOD_UPDATE_FOLDER = "UpdateFolder";
    public static final String EXTRA_UPDATE_ACCOUNT_ID = "UpdateAccountId";
    public static final String EXTRA_UPDATE_FOLDER_ID = "UpdateFolderId";

    /**
     * These column names are used as last path segment of the URI (getLastPathSegment()).
     * Access to a specific row in the tables is done by using the where-clause, hence
     * support for .../#id if not needed for the Email clients.
     * The URI format for accessing the tables are as follows:
     *   content://ProviderAuthority/TABLE_ACCOUNT
     *   content://ProviderAuthority/account_id/TABLE_MESSAGE
     *   content://ProviderAuthority/account_id/TABLE_FOLDER
     */

    /**
     * Build URI representing the given Accounts data-set in a
     * bluetooth provider. When queried, the direct URI for the account
     * with the given accountID is returned.
     */
    public static Uri buildAccountUri(String authority) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).appendPath(TABLE_ACCOUNT).build();
    }
    /**
     * Build URI representing the given Account data-set with specific Id in a
     * Bluetooth provider. When queried, the direct URI for the account
     * with the given accountID is returned.
     */
    public static Uri buildAccountUriwithId(String authority, String accountId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath(TABLE_ACCOUNT)
                .appendPath(accountId)
                .build();
    }
    /**
     * Build URI representing the entire Message table in a
     * bluetooth provider.
     */
    public static Uri buildMessageUri(String authority) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath(TABLE_MESSAGE)
                .build();
    }
    /**
     * Build URI representing the given Message data-set in a
     * bluetooth provider. When queried, the URI for the Messages
     * with the given accountID is returned.
     */
    public static Uri buildMessageUri(String authority, String accountId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath(accountId)
                .appendPath(TABLE_MESSAGE)
                .build();
    }
    /**
     * Build URI representing the given Message data-set with specific messageId in a
     * bluetooth provider. When queried, the direct URI for the account
     * with the given accountID is returned.
     */
    public static Uri buildMessageUriWithId(String authority, String accountId,String messageId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath(accountId)
                .appendPath(TABLE_MESSAGE)
                .appendPath(messageId)
                .build();
    }
    /**
     * Build URI representing the given Message data-set in a
     * bluetooth provider. When queried, the direct URI for the account
     * with the given accountID is returned.
     */
    public static Uri buildFolderUri(String authority, String accountId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .appendPath(accountId)
                .appendPath(TABLE_FOLDER)
                .build();
    }

    /**
     *  @hide
     */
    public static final String TABLE_ACCOUNT = "Account";
    public static final String TABLE_MESSAGE = "Message";
    public static final String TABLE_FOLDER  = "Folder";

    /**
     * Mandatory folders for the Bluetooth message access profile.
     * The email client shall at least implement the following root folders.
     * E.g. as a mapping for them such that the naming will match the underlying
     * matching folder ID's.
     */
    public static final String FOLDER_NAME_INBOX   = "inbox";
    public static final String FOLDER_NAME_OUTBOX  = "outbox";
    public static final String FOLDER_NAME_SENT    = "sent";
    public static final String FOLDER_NAME_DELETED = "deleted";
    public static final String FOLDER_NAME_DRAFT   = "draft";


    /**
     * To push RFC2822 encoded messages into a folder and read RFC2822 encoded messages from
     * a folder, the openFile() interface will be used as follows:
     * Open a file descriptor to a message.
     * Two modes supported for read: With and without attachments.
     * One mode exist for write and the actual content will be with or without
     * attachments.
     *
     * mode will be "r" for read and "w" for write, never "rw".
     *
     * URI format:
     * The URI scheme is as follows.
     * For reading messages with attachments:
     *   content://ProviderAuthority/account_id/TABLE_MESSAGE/msgId
     *   Note: This shall be an offline operation, including only message parts and attachments
     *         already downloaded to the device.
     *
     * For reading messages without attachments:
     *   content://ProviderAuthority/account_id/TABLE_MESSAGE/msgId/FILE_MSG_NO_ATTACHMENTS
     *   Note: This shall be an offline operation, including only message parts already
     *         downloaded to the device.
     *
     * For downloading and reading messages with attachments:
     *   content://ProviderAuthority/account_id/TABLE_MESSAGE/msgId/FILE_MSG_DOWNLOAD
     *   Note: This shall download the message content and all attachments if possible,
     *         else throw an IOException.
     *
     * For downloading and reading messages without attachments:
     *   content://ProviderAuthority/account_id/TABLE_MESSAGE/msgId/FILE_MSG_DOWNLOAD_NO_ATTACHMENTS
     *   Note: This shall download the message content if possible, else throw an IOException.
     *
     * When reading from the file descriptor, the content provider shall return a stream
     * of bytes containing a RFC2822 encoded message, as if the message was send to an email
     * server.
     *
     * When a byte stream is written to the file descriptor, the content provider shall
     * decode the RFC2822 encoded data and insert the message into the TABLE_MESSAGE at the ID
     * supplied in URI - additionally the message content shall be stored in the underlying
     * data base structure as if the message was received from an email server. The Message ID
     * will be created using a insert on the TABLE_MESSAGE prior to calling openFile().
     * Hence the procedure for inserting a message is:
     *  - uri/msgId = insert(uri, value: folderId=xxx)
     *  - fd = openFile(uri/msgId)
     *  - fd.write (RFC2822 encoded data)
     *
     *  The Bluetooth Message Access Client might not know what to put into the From:
     *  header nor have a valid time stamp, hence the content provider shall check
     *  if the From: and Date: headers have been set by the message written, else
     *  it must fill in appropriate values.
     */
    public static final String FILE_MSG_NO_ATTACHMENTS = "NO_ATTACHMENTS";
    public static final String FILE_MSG_DOWNLOAD = "DOWNLOAD";
    public static final String FILE_MSG_DOWNLOAD_NO_ATTACHMENTS = "DOWNLOAD_NO_ATTACHMENTS";

    /**
     * Account Table
     * The columns needed to supply account information.
     * The e-mail client app may choose to expose all e-mails as being from the same account,
     * but it is not recommended, as this would be a violation of the Bluetooth specification.
     * The Bluetooth Message Access settings activity will provide the user the ability to
     * change the FLAG_EXPOSE values for each account in this table.
     * The Bluetooth Message Access service will read the values when Bluetooth is turned on,
     * and again on every notified change through the content observer interface.
     */
    public interface AccountColumns {

        /**
         * The unique ID for a row.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String _ID = "_id";

        /**
         * The account name to display to the user on the device when selecting whether
         * or not to share the account over Bluetooth.
         *
         * The account display name should not reveal any sensitive information e.g. email-
         * address, as it will be added to the Bluetooth SDP record, which can be read by
         * any Bluetooth enabled device. (Access to any account content is only provided to
         * authenticated devices). It is recommended that if the email client uses the email
         * address as account name, then the address should be obfuscated (i.e. replace "@"
         * with ".")
         * <P>Type: TEXT</P>
         * read-only
         */
        public static final String ACCOUNT_DISPLAY_NAME = "account_display_name";

        /**
         * Expose this account to other authenticated Bluetooth devices. If the expose flag
         * is set, this account will be listed as an available account to access from another
         * Bluetooth device.
         *
         * This is a read/write flag, that can be set either from within the E-mail client
         * UI or the Bluetooth settings menu.
         *
         * It is recommended to either ask the user whether to expose the account, or set this
         * to "show" as default.
         *
         * This setting shall not be used to enforce whether or not an account should be shared
         * or not if the account is bound by an administrative security policy. In this case
         * the email app should not list the account at all if it is not to be shareable over BT.
         *
         * <P>Type: INTEGER (boolean) hide = 0, show = 1</P>
         */
        public static final String FLAG_EXPOSE = "flag_expose";

    }

    /**
     * The actual message table containing all messages.
     * Content that must support filtering using WHERE clauses:
     *   - To, From, Cc, Bcc, Date, ReadFlag, PriorityFlag, folder_id, account_id
     * Additional content that must be supplied:
     *   - Subject, AttachmentFlag, LoadedState, MessageSize, AttachmentSize
     * Content that must support update:
     *   - FLAG_READ and FOLDER_ID (FOLDER_ID is used to move a message to deleted)
     * Additional insert of a new message with the following values shall be supported:
     *   - FOLDER_ID
     *
     * When doing an insert on this table, the actual content of the message (subject,
     * date etc) written through file-i/o takes precedence over the inserted values and should
     * overwrite them.
     */
    public interface MessageColumns {

        /**
         * The unique ID for a row.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String _ID = "_id";

        /**
         * The date the message was received as a unix timestamp
         * (miliseconds since 00:00:00 UTC 1/1-1970).
         *
         * <P>Type: INTEGER (long)</P>
         * read-only
         */
        public static final String DATE = "date";

        /**
         * Message subject.
         * <P>Type: TEXT</P>
         * read-only.
         */
        public static final String SUBJECT = "subject";

        /**
         * Message Read flag
         * <P>Type: INTEGER (boolean) unread = 0, read = 1</P>
         *  read/write
         */
        public static final String FLAG_READ = "flag_read";

        /**
         * Message Priority flag
         * <P>Type: INTEGER (boolean) normal priority = 0, high priority = 1</P>
         * read-only
         */
        public static final String FLAG_HIGH_PRIORITY = "high_priority";

        /**
         * Reception state - the amount of the message that have been loaded from the server.
         * <P>Type: INTEGER see RECEPTION_STATE_ constants below </P>
         * read-only
         */
        public static final String RECEPTION_STATE = "reception_state";

        /** To be able to filter messages with attachments, we need this flag.
         * <P>Type: INTEGER (boolean) no attachment = 0, attachment = 1 </P>
         * read-only
         */
        public static final String FLAG_ATTACHMENT = "flag_attachment";

        /** The overall size in bytes of the attachments of the message.
         * <P>Type: INTEGER </P>
         */
        public static final String ATTACHMENT_SIZE = "attachment_size";

        /** The overall size in bytes of the message including any attachments.
         * This value is informative only and should be the size an email client
         * would display as size for the message.
         * <P>Type: INTEGER </P>
         * read-only
         */
        public static final String MESSAGE_SIZE = "message_size";

        /** Indicates that the message or a part of it is protected by a DRM scheme.
         * <P>Type: INTEGER (boolean) no DRM = 0, DRM protected = 1 </P>
         * read-only
         */
        public static final String FLAG_PROTECTED = "flag_protected";

        /**
         * A comma-delimited list of FROM addresses in RFC2822 format.
         * The list must be compatible with Rfc822Tokenizer.tokenize();
         * <P>Type: TEXT</P>
         * read-only
         */
        public static final String FROM_LIST = "from_list";

        /**
         * A comma-delimited list of TO addresses in RFC2822 format.
         * The list must be compatible with Rfc822Tokenizer.tokenize();
         * <P>Type: TEXT</P>
         * read-only
         */
        public static final String TO_LIST = "to_list";

        /**
         * A comma-delimited list of CC addresses in RFC2822 format.
         * The list must be compatible with Rfc822Tokenizer.tokenize();
         * <P>Type: TEXT</P>
         * read-only
         */
        public static final String CC_LIST = "cc_list";

        /**
         * A comma-delimited list of BCC addresses in RFC2822 format.
         * The list must be compatible with Rfc822Tokenizer.tokenize();
         * <P>Type: TEXT</P>
         * read-only
         */
        public static final String BCC_LIST = "bcc_list";

        /**
         * A comma-delimited list of REPLY-TO addresses in RFC2822 format.
         * The list must be compatible with Rfc822Tokenizer.tokenize();
         * <P>Type: TEXT</P>
         * read-only
         */
        public static final String REPLY_TO_LIST = "reply_to_List";

        /**
         * The unique ID for a row in the folder table in which this message belongs.
         * <P>Type: INTEGER (long)</P>
         * read/write
         */
        public static final String FOLDER_ID = "folder_id";

        /**
         * The unique ID for a row in the account table which owns this message.
         * <P>Type: INTEGER (long)</P>
         * read-only
         */
        public static final String ACCOUNT_ID = "account_id";

        /**
         * The ID identify the thread a message belongs to. If no thread id is available,
         * set value to "-1"
         * <P>Type: INTEGER (long)</P>
         * read-only
         */
        public static final String THREAD_ID = "thread_id";
    }

    /**
     * Indicates that the message, including any attachments, has been received from the
     * server to the device.
     */
    public static final String RECEPTION_STATE_COMPLETE = "complete";
    /**
     * Indicates the message is partially received from the email server.
     */
    public static final String RECEPTION_STATE_FRACTIONED = "fractioned";
    /**
     * Indicates that only a notification about the message have been received.
     */
    public static final String RECEPTION_STATE_NOTIFICATION = "notification";

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
    public interface FolderColumns {

        /**
         * The unique ID for a row.
         * <P>Type: INTEGER (long)</P>
         * read-only
         */
        public static final String _ID = "_id";

        /**
         * The folder display name to present to the user.
         * <P>Type: TEXT</P>
         * read-only
         */
        public static final String NAME = "name";

        /**
         * The _id-key to the account this folder refers to.
         * <P>Type: INTEGER (long)</P>
         * read-only
         */
        public static final String ACCOUNT_ID = "account_id";

        /**
         * The _id-key to the parent folder. -1 for root folders.
         * <P>Type: INTEGER (long)</P>
         * read-only
         */
        public static final String PARENT_FOLDER_ID = "parent_id";
    }
    /**
     * A projection of all the columns in the Message table
     */
    public static final String[] BT_MESSAGE_PROJECTION = new String[] {
        MessageColumns._ID,
        MessageColumns.DATE,
        MessageColumns.SUBJECT,
        MessageColumns.FLAG_READ,
        MessageColumns.FLAG_ATTACHMENT,
        MessageColumns.FOLDER_ID,
        MessageColumns.ACCOUNT_ID,
        MessageColumns.FROM_LIST,
        MessageColumns.TO_LIST,
        MessageColumns.CC_LIST,
        MessageColumns.BCC_LIST,
        MessageColumns.REPLY_TO_LIST,
        MessageColumns.FLAG_PROTECTED,
        MessageColumns.FLAG_HIGH_PRIORITY,
        MessageColumns.MESSAGE_SIZE,
        MessageColumns.ATTACHMENT_SIZE,
        MessageColumns.RECEPTION_STATE,
        MessageColumns.THREAD_ID
    };

    /**
     * A projection of all the columns in the Account table
     */
    public static final String[] BT_ACCOUNT_PROJECTION = new String[] {
        AccountColumns._ID,
        AccountColumns.ACCOUNT_DISPLAY_NAME,
        AccountColumns.FLAG_EXPOSE,
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


}
