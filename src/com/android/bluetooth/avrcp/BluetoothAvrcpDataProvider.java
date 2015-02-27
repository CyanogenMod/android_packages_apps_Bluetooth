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

package com.android.bluetooth.avrcp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.SQLException;
import android.content.UriMatcher;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.util.Log;
import android.bluetooth.BluetoothAvrcpController;
import android.bluetooth.BluetoothAvrcpInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


/**
 * This content-provider implements AVRCP-MetaData database.
 */

public final class BluetoothAvrcpDataProvider extends ContentProvider {

    private static final String TAG = "BluetoothAvrcpDataProvider";
    private static final String DB_NAME = "btavrcp_ct.db";
    private static final int DB_VERSION = 1;
    private static final int DB_VERSION_NOP_UPGRADE_FROM = 0;
    private static final int DB_VERSION_NOP_UPGRADE_TO = 1;
    private static final String DB_TABLE = "btavrcp_ct";
    private static final String TRACK_LIST_TYPE = "vnd.android.cursor.dir/vnd.android.btavrcp_ct";
    private static final String TRACK_TYPE = "vnd.android.cursor.item/vnd.android.btavrcp_ct";
    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    /** For all TrackList */
    private static final int TRACKS = 1;
    /** For a single Track ID */
    private static final int TRACK_ID = 2;

    static {
        sURIMatcher.addURI("com.android.bluetooth.avrcp", "btavrcp_ct", TRACKS);
        sURIMatcher.addURI("com.android.bluetooth.avrcp", "btavrcp_ct/#", TRACK_ID);
    }
    private SQLiteOpenHelper mOpenHelper = null;

    private final class DbHelper extends SQLiteOpenHelper {

        public DbHelper(final Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(final SQLiteDatabase db) {
             Log.v(TAG, "populating new database");
            createTable(db);
        }

        @Override
        public void onUpgrade(final SQLiteDatabase db, int oldV, final int newV) {
            if (oldV == DB_VERSION_NOP_UPGRADE_FROM) {
                if (newV == DB_VERSION_NOP_UPGRADE_TO) { // that's a no-op
                    return;
                }
                oldV = DB_VERSION_NOP_UPGRADE_TO;
            }
            Log.i(TAG, "Upgrading downloads database from version " + oldV + " to "
                    + newV + ", which will destroy all old data");
            dropTable(db);
            createTable(db);
        }
    }

    private void createTable(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE " + DB_TABLE + "("
                    + BluetoothAvrcpInfo._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + BluetoothAvrcpInfo.TRACK_NUM + " INTEGER, "
                    + BluetoothAvrcpInfo.TITLE + " TEXT, "
                    + BluetoothAvrcpInfo.ARTIST_NAME + " TEXT, "
                    + BluetoothAvrcpInfo.ALBUM_NAME + " TEXT, "
                    + BluetoothAvrcpInfo.TOTAL_TRACKS + " INTEGER, "
                    + BluetoothAvrcpInfo.GENRE + " TEXT, "
                    + BluetoothAvrcpInfo.PLAYING_TIME + " INTEGER, "
                    + BluetoothAvrcpInfo.TOTAL_TRACK_TIME + " INTEGER, "
                    + BluetoothAvrcpInfo.PLAY_STATUS + " TEXT, "
                    + BluetoothAvrcpInfo.REPEAT_STATUS + " TEXT, "
                    + BluetoothAvrcpInfo.SHUFFLE_STATUS + " TEXT, "
                    + BluetoothAvrcpInfo.SCAN_STATUS + " TEXT, "
                    + BluetoothAvrcpInfo.EQUALIZER_STATUS + " TEXT); ");
        } catch (SQLException ex) {
            Log.e(TAG, "couldn't create table in downloads database");
            throw ex;
        }
    }

    private void dropTable(SQLiteDatabase db) {
        try {
            db.execSQL("DROP TABLE IF EXISTS " + DB_TABLE);
        } catch (SQLException ex) {
            Log.e(TAG, "Couldn't Drop table");
            throw ex;
        }
    }

    @Override
    public String getType(Uri uri) {
        int match = sURIMatcher.match(uri);
        switch (match) {
            case TRACKS: {
                return TRACK_LIST_TYPE;
            }
            case TRACK_ID: {
                return TRACK_TYPE;
            }
            default: {
                Log.d(TAG, "getType called on unknown URI: " + uri);
                throw new IllegalArgumentException("Unknown URI: " + uri);
            }
        }
    }

    private static final void copyString(String key, ContentValues from, ContentValues to) {
        String s = from.getAsString(key);
        if (s != null) {
            to.put(key, s);
        }
    }

    private static final void copyInteger(String key, ContentValues from, ContentValues to) {
        Integer i = from.getAsInteger(key);
        if (i != null) {
            to.put(key, i);
        }
    }

    private static final void copyLong(String key, ContentValues from, ContentValues to) {
        Long i = from.getAsLong(key);
        if (i != null) {
            to.put(key, i);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        if (sURIMatcher.match(uri) != TRACKS) {
            Log.d(TAG, "calling insert on an unknown/invalid URI: " + uri);
            throw new IllegalArgumentException("Unknown/Invalid URI " + uri);
        }

        ContentValues filteredValues = new ContentValues();

        copyLong(BluetoothAvrcpInfo.TRACK_NUM, values, filteredValues);
        copyString(BluetoothAvrcpInfo.TITLE, values, filteredValues);
        copyString(BluetoothAvrcpInfo.ARTIST_NAME, values, filteredValues);
        copyString(BluetoothAvrcpInfo.ALBUM_NAME, values, filteredValues);
        copyLong(BluetoothAvrcpInfo.TOTAL_TRACKS, values, filteredValues);
        copyString(BluetoothAvrcpInfo.GENRE, values, filteredValues);
        copyLong(BluetoothAvrcpInfo.PLAYING_TIME, values, filteredValues);
        copyLong(BluetoothAvrcpInfo.TOTAL_TRACK_TIME, values, filteredValues);
        copyString(BluetoothAvrcpInfo.PLAY_STATUS, values, filteredValues);
        copyString(BluetoothAvrcpInfo.REPEAT_STATUS, values, filteredValues);
        copyString(BluetoothAvrcpInfo.SHUFFLE_STATUS, values, filteredValues);
        copyString(BluetoothAvrcpInfo.SCAN_STATUS, values, filteredValues);
        copyString(BluetoothAvrcpInfo.EQUALIZER_STATUS, values, filteredValues);

        long rowID = db.insert(DB_TABLE, null, filteredValues);

        Uri ret = null;
        Context context = getContext();

        if (rowID != -1) {
            ret = Uri.parse(BluetoothAvrcpInfo.CONTENT_URI + "/" + rowID);
            context.getContentResolver().notifyChange(uri, null);
        } else {
            Log.d(TAG, "couldn't insert into database");
            }

        return ret;
    }

    @Override
    public boolean onCreate() {
        mOpenHelper = new DbHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();

        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        int match = sURIMatcher.match(uri);
        switch (match) {
            case TRACKS: {
                qb.setTables(DB_TABLE);
                break;
            }
            case TRACK_ID: {
                qb.setTables(DB_TABLE);
                qb.appendWhere(BluetoothAvrcpInfo._ID + "=");
                qb.appendWhere(uri.getPathSegments().get(1));
                break;
            }
            default: {
                Log.d(TAG, "querying unknown URI: " + uri);
                throw new IllegalArgumentException("Unknown URI: " + uri);
            }
        }

        {
            java.lang.StringBuilder sb = new java.lang.StringBuilder();
            sb.append("starting query, database is ");
            if (db != null) {
                sb.append("not ");
            }
            sb.append("null; ");
            if (projection == null) {
                sb.append("projection is null; ");
            } else if (projection.length == 0) {
                sb.append("projection is empty; ");
            } else {
                for (int i = 0; i < projection.length; ++i) {
                    sb.append("projection[");
                    sb.append(i);
                    sb.append("] is ");
                    sb.append(projection[i]);
                    sb.append("; ");
                }
            }
            sb.append("selection is ");
            sb.append(selection);
            sb.append("; ");
            if (selectionArgs == null) {
                sb.append("selectionArgs is null; ");
            } else if (selectionArgs.length == 0) {
                sb.append("selectionArgs is empty; ");
            } else {
                for (int i = 0; i < selectionArgs.length; ++i) {
                    sb.append("selectionArgs[");
                    sb.append(i);
                    sb.append("] is ");
                    sb.append(selectionArgs[i]);
                    sb.append("; ");
                }
            }
            sb.append("sort is ");
            sb.append(sortOrder);
            sb.append(".");
            Log.v(TAG, sb.toString());
        }

        Cursor ret = qb.query(db, projection, selection, selectionArgs, null, null, sortOrder);
        Log.v(TAG," Query Done ");
        if (ret != null) {
            ret.setNotificationUri(getContext().getContentResolver(), uri);
             Log.v(TAG, "created cursor " + ret + " on behalf of ");
        } else {
             Log.d(TAG, "query failed in downloads database");
            }

        return ret;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        int count;
        long rowId = 0;

        int match = sURIMatcher.match(uri);
        switch (match) {
            case TRACKS:
            case TRACK_ID: {
                String myWhere;
                if (selection != null) {
                    if (match == TRACKS) {
                        myWhere = "( " + selection + " )";
                    } else {
                        myWhere = "( " + selection + " ) AND ";
                    }
                } else {
                    myWhere = "";
                }
                if (match == TRACK_ID) {
                    String segment = uri.getPathSegments().get(1);
                    rowId = Long.parseLong(segment);
                    myWhere += " ( " + BluetoothAvrcpInfo._ID + " = " + rowId + " ) ";
                }

                if (values.size() > 0) {
                    count = db.update(DB_TABLE, values, myWhere, selectionArgs);
                } else {
                    count = 0;
                }
                break;
            }
            default: {
                Log.d(TAG, "updating unknown/invalid URI: " + uri);
                throw new UnsupportedOperationException("Cannot update URI: " + uri);
            }
        }
        getContext().getContentResolver().notifyChange(uri, null);

        return count;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count;
        int match = sURIMatcher.match(uri);
        switch (match) {
            case TRACKS:
            case TRACK_ID: {
                String myWhere;
                if (selection != null) {
                    if (match == TRACKS) {
                        myWhere = "( " + selection + " )";
                    } else {
                        myWhere = "( " + selection + " ) AND ";
                    }
                } else {
                    myWhere = "";
                }
                if (match == TRACK_ID) {
                    String segment = uri.getPathSegments().get(1);
                    long rowId = Long.parseLong(segment);
                    myWhere += " ( " + BluetoothAvrcpInfo._ID + " = " + rowId + " ) ";
                }

                count = db.delete(DB_TABLE, myWhere, selectionArgs);
                break;
            }
            default: {
                Log.d(TAG, "deleting unknown/invalid URI: " + uri);
                throw new UnsupportedOperationException("Cannot delete URI: " + uri);
            }
        }
        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }
}