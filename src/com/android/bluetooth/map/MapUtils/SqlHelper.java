/*
 * Copyright (c) 2011, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *          notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *          notice, this list of conditions and the following disclaimer in the
 *          documentation and/or other materials provided with the distribution.
 *        * Neither the name of Code Aurora nor
 *          the names of its contributors may be used to endorse or promote
 *          products derived from this software without specific prior written
 *          permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.    IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.map.MapUtils;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.android.bluetooth.map.BluetoothMasService;

import java.util.ArrayList;
import java.util.List;

public class SqlHelper {
    public static final String TAG = "SqlHelper";
    public static final boolean V = BluetoothMasService.VERBOSE;
    private static final String[] COUNT_COLUMNS = new String[]{"count(*)"};

    /**
     * Generic count method that can be used for any ContentProvider
     * @param resolver the calling Context
     * @param uri the Uri for the provider query
     * @param selection as with a query call
     * @param selectionArgs as with a query call
     * @return the number of items matching the query (or zero)
     */
    public static int count(Context context, Uri uri, String selection, String[] selectionArgs) {
        if (V) Log.v(TAG, "count(" + uri + ", " + selection + ", " + selectionArgs + ")");
        int cnt = 0;
        Cursor cursor = context.getContentResolver().query(uri,
                COUNT_COLUMNS, selection, selectionArgs, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                cnt = cursor.getInt(0);
                if (V) Log.v(TAG, "count = " + cnt);
            }
            cursor.close();
        }
        return cnt;
    }

    /**
     * Generic method to retrieve the first value for the column
     * @param resolver the calling Context
     * @param uri the Uri for the provider query
     * @param columnName the column name to be retrieved
     * @param selection as with a query call
     * @param selectionArgs as with a query call
     * @return the value first of that column
     */
    public static String getFirstValueForColumn(Context context, Uri uri,
            String columnName, String selection, String[] selectionArgs) {
        if (V) Log.v(TAG, "getFirstValueForColumn(" + uri + ", " + columnName +
                ", " + selection + ", " + selectionArgs + ")");
        String value = "";
        Cursor cr = context.getContentResolver().query(uri, null, selection, selectionArgs, null);
        if (cr != null) {
            if (cr.moveToFirst()) {
                value = cr.getString(cr.getColumnIndex(columnName));
                if (V) Log.v(TAG, "value = " + value);
            }
            cr.close();
        }
        return value;
    }

    /**
     * Generic method to retrieve the first value for the column
     * @param resolver the calling Context
     * @param uri the Uri for the provider query
     * @param columnName the column name to be retrieved
     * @param selection as with a query call
     * @param selectionArgs as with a query call
     * @return the value first of that column
     */
    public static int getFirstIntForColumn(Context context, Uri uri,
            String columnName, String selection, String[] selectionArgs) {
        if (V) Log.v(TAG, "getFirstIntForColumn(" + uri + ", " + columnName +
                ", " + selection + ", " + selectionArgs + ")");
        int value = -1;
        Cursor cr = context.getContentResolver().query(uri, null, selection, selectionArgs, null);
        if (cr != null) {
            if (cr.moveToFirst()) {
                value = cr.getInt(cr.getColumnIndex(columnName));
                if (V) Log.v(TAG, "value = " + value);
            }
            cr.close();
        }
        return value;
    }

    /**
     * Generic method to retrieve the list for the column
     * @param resolver the calling Context's ContentResolver
     * @param uri the Uri for the provider query
     * @param columnName the column name to be retrieved
     * @param selection as with a query call
     * @param selectionArgs as with a query call
     * @return the value first of that column
     */
    public static List<String> getListForColumn(Context context, Uri uri,
            String columnName, String selection, String[] selectionArgs) {
        ArrayList<String> list = new ArrayList<String>();
        if (V) Log.v(TAG, "getListForColumn(" + uri + ", " + columnName + ", " +
                selection + ", " + selectionArgs + ")");
        Cursor cr = context.getContentResolver().query(uri, null, selection, selectionArgs, null);
        if (cr != null) {
            if (cr.moveToFirst()) {
                final int columnIndex = cr.getColumnIndex(columnName);
                do {
                    final String value = cr.getString(columnIndex);
                    list.add(value);
                    if (V) Log.v(TAG, "adding: " + value);
                } while (cr.moveToNext());
            }
            cr.close();
        }
        return list;
    }
}
