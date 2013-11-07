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

import android.content.Context;
import android.os.Message;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.provider.CallLog.Calls;
import android.provider.CallLog;

import java.io.IOException;
import java.io.OutputStream;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.Arrays;

import javax.obex.ServerRequestHandler;
import javax.obex.ResponseCodes;
import javax.obex.ApplicationParameter;
import javax.obex.ServerOperation;
import javax.obex.Operation;
import javax.obex.HeaderSet;

public class BluetoothPbapObexServer extends ServerRequestHandler {

    private static final String TAG = "BluetoothPbapObexServer";

    private static final boolean D = BluetoothPbapService.DEBUG;

    private static final boolean V = BluetoothPbapService.VERBOSE;

    private static final int UUID_LENGTH = 16;

    // The length of suffix of vcard name - ".vcf" is 5
    private static final int VCARD_NAME_SUFFIX_LENGTH = 5;

    // 128 bit UUID for PBAP
    private static final byte[] PBAP_TARGET = new byte[] {
            0x79, 0x61, 0x35, (byte)0xf0, (byte)0xf0, (byte)0xc5, 0x11, (byte)0xd8, 0x09, 0x66,
            0x08, 0x00, 0x20, 0x0c, (byte)0x9a, 0x66
    };

    // Currently not support SIM card
    private static final String[] LEGAL_PATH = {
            "/telecom", "/telecom/pb", "/telecom/ich", "/telecom/och", "/telecom/mch",
            "/telecom/cch"
    };

    @SuppressWarnings("unused")
    private static final String[] LEGAL_PATH_WITH_SIM = {
            "/telecom", "/telecom/pb", "/telecom/ich", "/telecom/och", "/telecom/mch",
            "/telecom/cch", "/SIM1", "/SIM1/telecom", "/SIM1/telecom/ich", "/SIM1/telecom/och",
            "/SIM1/telecom/mch", "/SIM1/telecom/cch", "/SIM1/telecom/pb"

    };

    // SIM card
    private static final String SIM1 = "SIM1";

    // missed call history
    private static final String MCH = "mch";

    // incoming call history
    private static final String ICH = "ich";

    // outgoing call history
    private static final String OCH = "och";

    // combined call history
    private static final String CCH = "cch";

    // phone book
    private static final String PB = "pb";

    private static final String ICH_PATH = "/telecom/ich";

    private static final String OCH_PATH = "/telecom/och";

    private static final String MCH_PATH = "/telecom/mch";

    private static final String CCH_PATH = "/telecom/cch";

    private static final String PB_PATH = "/telecom/pb";

    // type for list vcard objects
    private static final String TYPE_LISTING = "x-bt/vcard-listing";

    // type for get single vcard object
    private static final String TYPE_VCARD = "x-bt/vcard";

    // to indicate if need send body besides headers
    private static final int NEED_SEND_BODY = -1;

    // type for download all vcard objects
    private static final String TYPE_PB = "x-bt/phonebook";

    // The number of indexes in the phone book.
    private boolean mNeedPhonebookSize = false;

    // The number of missed calls that have not been checked on the PSE at the
    // point of the request. Only apply to "mch" case.
    private boolean mNeedNewMissedCallsNum = false;

    private int mMissedCallSize = 0;

    // record current path the client are browsing
    private String mCurrentPath = "";

    private Handler mCallback = null;

    private Context mContext;

    private BluetoothPbapVcardManager mVcardManager;

    private int mOrderBy  = ORDER_BY_INDEXED;

    private static int CALLLOG_NUM_LIMIT = 50;

    public static int ORDER_BY_INDEXED = 0;

    public static int ORDER_BY_ALPHABETICAL = 1;

    public static boolean sIsAborted = false;

    public static class ContentType {
        public static final int PHONEBOOK = 1;

        public static final int INCOMING_CALL_HISTORY = 2;

        public static final int OUTGOING_CALL_HISTORY = 3;

        public static final int MISSED_CALL_HISTORY = 4;

        public static final int COMBINED_CALL_HISTORY = 5;
    }

    public BluetoothPbapObexServer(Handler callback, Context context) {
        super();
        mCallback = callback;
        mContext = context;
        mVcardManager = new BluetoothPbapVcardManager(mContext);

        // set initial value when ObexServer created
        mMissedCallSize = mVcardManager.getPhonebookSize(ContentType.MISSED_CALL_HISTORY);
        if (D) Log.d(TAG, "Initialize mMissedCallSize=" + mMissedCallSize);
    }

    @Override
    public int onConnect(final HeaderSet request, HeaderSet reply) {
        if (V) logHeader(request);
        try {
            byte[] uuid = (byte[])request.getHeader(HeaderSet.TARGET);
            if (uuid == null) {
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
            if (D) Log.d(TAG, "onConnect(): uuid=" + Arrays.toString(uuid));

            if (uuid.length != UUID_LENGTH) {
                Log.w(TAG, "Wrong UUID length");
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
            for (int i = 0; i < UUID_LENGTH; i++) {
                if (uuid[i] != PBAP_TARGET[i]) {
                    Log.w(TAG, "Wrong UUID");
                    return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
                }
            }
            reply.setHeader(HeaderSet.WHO, uuid);
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        try {
            byte[] remote = (byte[])request.getHeader(HeaderSet.WHO);
            if (remote != null) {
                if (D) Log.d(TAG, "onConnect(): remote=" + Arrays.toString(remote));
                reply.setHeader(HeaderSet.TARGET, remote);
            }
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        if (V) Log.v(TAG, "onConnect(): uuid is ok, will send out " +
                "MSG_SESSION_ESTABLISHED msg.");

        Message msg = Message.obtain(mCallback);
        msg.what = BluetoothPbapService.MSG_SESSION_ESTABLISHED;
        msg.sendToTarget();

        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public void onDisconnect(final HeaderSet req, final HeaderSet resp) {
        if (D) Log.d(TAG, "onDisconnect(): enter");
        if (V) logHeader(req);

        resp.responseCode = ResponseCodes.OBEX_HTTP_OK;
        if (mCallback != null) {
            Message msg = Message.obtain(mCallback);
            msg.what = BluetoothPbapService.MSG_SESSION_DISCONNECTED;
            msg.sendToTarget();
            if (V) Log.v(TAG, "onDisconnect(): msg MSG_SESSION_DISCONNECTED sent out.");
        }
    }

    @Override
    public int onAbort(HeaderSet request, HeaderSet reply) {
        if (D) Log.d(TAG, "onAbort(): enter.");
        sIsAborted = true;
        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public int onPut(final Operation op) {
        if (D) Log.d(TAG, "onPut(): not support PUT request.");
        return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
    }

    @Override
    public int onSetPath(final HeaderSet request, final HeaderSet reply, final boolean backup,
            final boolean create) {
        if (V) logHeader(request);
        if (D) Log.d(TAG, "before setPath, mCurrentPath ==  " + mCurrentPath);

        String current_path_tmp = mCurrentPath;
        String tmp_path = null;
        try {
            tmp_path = (String)request.getHeader(HeaderSet.NAME);
        } catch (IOException e) {
            Log.e(TAG, "Get name header fail");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        if (D) Log.d(TAG, "backup=" + backup + " create=" + create + " name=" + tmp_path);

        if (backup) {
            if (current_path_tmp.length() != 0) {
                current_path_tmp = current_path_tmp.substring(0,
                        current_path_tmp.lastIndexOf("/"));
            }
        } else {
            if (tmp_path == null) {
                current_path_tmp = "";
            } else {
                current_path_tmp = current_path_tmp + "/" + tmp_path;
            }
        }

        if ((current_path_tmp.length() != 0) && (!isLegalPath(current_path_tmp))) {
            if (create) {
                Log.w(TAG, "path create is forbidden!");
                return ResponseCodes.OBEX_HTTP_FORBIDDEN;
            } else {
                Log.w(TAG, "path is not legal");
                return ResponseCodes.OBEX_HTTP_NOT_FOUND;
            }
        }
        mCurrentPath = current_path_tmp;
        if (V) Log.v(TAG, "after setPath, mCurrentPath ==  " + mCurrentPath);

        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public void onClose() {
        if (mCallback != null) {
            Message msg = Message.obtain(mCallback);
            msg.what = BluetoothPbapService.MSG_SERVERSESSION_CLOSE;
            msg.sendToTarget();
            if (D) Log.d(TAG, "onClose(): msg MSG_SERVERSESSION_CLOSE sent out.");
        }
    }

    @Override
    public int onGet(Operation op) {
        sIsAborted = false;
        HeaderSet request = null;
        HeaderSet reply = new HeaderSet();
        String type = "";
        String name = "";
        byte[] appParam = null;
        AppParamValue appParamValue = new AppParamValue();
        try {
            request = op.getReceivedHeader();
            type = (String)request.getHeader(HeaderSet.TYPE);
            name = (String)request.getHeader(HeaderSet.NAME);
            appParam = (byte[])request.getHeader(HeaderSet.APPLICATION_PARAMETER);
        } catch (IOException e) {
            Log.e(TAG, "request headers error");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        if (V) logHeader(request);
        if (D) Log.d(TAG, "OnGet type is " + type + "; name is " + name);

        if (type == null) {
            return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
        }
        // Accroding to specification,the name header could be omitted such as
        // sony erriccsonHBH-DS980

        // For "x-bt/phonebook" and "x-bt/vcard-listing":
        // if name == null, guess what carkit actually want from current path
        // For "x-bt/vcard":
        // We decide which kind of content client would like per current path

        boolean validName = true;
        if (TextUtils.isEmpty(name)) {
            validName = false;
        }

        if (!validName || (validName && type.equals(TYPE_VCARD))) {
            if (D) Log.d(TAG, "Guess what carkit actually want from current path (" +
                    mCurrentPath + ")");

            if (mCurrentPath.equals(PB_PATH)) {
                appParamValue.needTag = ContentType.PHONEBOOK;
            } else if (mCurrentPath.equals(ICH_PATH)) {
                appParamValue.needTag = ContentType.INCOMING_CALL_HISTORY;
            } else if (mCurrentPath.equals(OCH_PATH)) {
                appParamValue.needTag = ContentType.OUTGOING_CALL_HISTORY;
            } else if (mCurrentPath.equals(MCH_PATH)) {
                appParamValue.needTag = ContentType.MISSED_CALL_HISTORY;
                mNeedNewMissedCallsNum = true;
            } else if (mCurrentPath.equals(CCH_PATH)) {
                appParamValue.needTag = ContentType.COMBINED_CALL_HISTORY;
            } else {
                Log.w(TAG, "mCurrentpath is not valid path!!!");
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
            if (D) Log.v(TAG, "onGet(): appParamValue.needTag=" + appParamValue.needTag);
        } else {
            // Not support SIM card currently
            if (name.contains(SIM1.subSequence(0, SIM1.length()))) {
                Log.w(TAG, "Not support access SIM card info!");
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }

            // we have weak name checking here to provide better
            // compatibility with other devices,although unique name such as
            // "pb.vcf" is required by SIG spec.
            if (name.contains(PB.subSequence(0, PB.length()))) {
                appParamValue.needTag = ContentType.PHONEBOOK;
                if (D) Log.v(TAG, "download phonebook request");
            } else if (name.contains(ICH.subSequence(0, ICH.length()))) {
                appParamValue.needTag = ContentType.INCOMING_CALL_HISTORY;
                if (D) Log.v(TAG, "download incoming calls request");
            } else if (name.contains(OCH.subSequence(0, OCH.length()))) {
                appParamValue.needTag = ContentType.OUTGOING_CALL_HISTORY;
                if (D) Log.v(TAG, "download outgoing calls request");
            } else if (name.contains(MCH.subSequence(0, MCH.length()))) {
                appParamValue.needTag = ContentType.MISSED_CALL_HISTORY;
                mNeedNewMissedCallsNum = true;
                if (D) Log.v(TAG, "download missed calls request");
            } else if (name.contains(CCH.subSequence(0, CCH.length()))) {
                appParamValue.needTag = ContentType.COMBINED_CALL_HISTORY;
                if (D) Log.v(TAG, "download combined calls request");
            } else {
                Log.w(TAG, "Input name doesn't contain valid info!!!");
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
        }

        if ((appParam != null) && !parseApplicationParameter(appParam, appParamValue)) {
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        // listing request
        if (type.equals(TYPE_LISTING)) {
            return pullVcardListing(appParam, appParamValue, reply, op);
        }
        // pull vcard entry request
        else if (type.equals(TYPE_VCARD)) {
            return pullVcardEntry(appParam, appParamValue, op, name, mCurrentPath);
        }
        // down load phone book request
        else if (type.equals(TYPE_PB)) {
            return pullPhonebook(appParam, appParamValue, reply, op, name);
        } else {
            Log.w(TAG, "unknown type request!!!");
            return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
        }
    }

    /** check whether path is legal */
    private final boolean isLegalPath(final String str) {
        if (str.length() == 0) {
            return true;
        }
        for (int i = 0; i < LEGAL_PATH.length; i++) {
            if (str.equals(LEGAL_PATH[i])) {
                return true;
            }
        }
        return false;
    }

    private class AppParamValue {
        public int maxListCount;

        public int listStartOffset;

        public String searchValue;

        // Indicate which vCard parameter the search operation shall be carried
        // out on. Can be "Name | Number | Sound", default value is "Name".
        public String searchAttr;

        // Indicate which sorting order shall be used for the
        // <x-bt/vcard-listing> listing object.
        // Can be "Alphabetical | Indexed | Phonetical", default value is
        // "Indexed".
        public String order;

        public int needTag;

        public boolean vcard21;

        public long filter;

        public AppParamValue() {
            maxListCount = 0xFFFF;
            listStartOffset = 0;
            searchValue = "";
            searchAttr = "";
            order = "";
            needTag = 0x00;
            vcard21 = true;
            filter = BluetoothPbapVcardComposer.FILTER_VERSION;
        }

        public long getActualFilter() {
            // Spec says:
            // "All attributes of the vCard shall be returned if this header is not
            //  specified or carries the value 0x00000000"
            // This causes trouble in many car kits because they receive fields they
            // cannot handle. So we send only the minimum set if there is no filter
            // specified. If the filter parameter is 0, we send all fields according
            // to spec.
            if (filter == 0L)
                return -1L;

            long mandatory = BluetoothPbapVcardComposer.FILTER_VERSION |
                             BluetoothPbapVcardComposer.FILTER_N |
                             BluetoothPbapVcardComposer.FILTER_TEL;
            if (!vcard21)
                mandatory |= BluetoothPbapVcardComposer.FILTER_FN;

            return filter | mandatory;
        }
    }

    /** To parse obex application parameter */
    private final boolean parseApplicationParameter(final byte[] appParam,
            AppParamValue appParamValue) {
        int i = 0;
        boolean parseOk = true;
        while (i < appParam.length) {
            switch (appParam[i]) {
                case ApplicationParameter.TRIPLET_TAGID.FILTER_TAGID:
                    i += 2; // length and tag field in triplet
                    appParamValue.filter = ((appParam[i+0] << 56) & 0xFF00000000000000L) |
                                           ((appParam[i+1] << 48) & 0x00FF000000000000L) |
                                           ((appParam[i+2] << 40) & 0x0000FF0000000000L) |
                                           ((appParam[i+3] << 32) & 0x000000FF00000000L) |
                                           ((appParam[i+4] << 24) & 0x00000000FF000000L) |
                                           ((appParam[i+5] << 16) & 0x0000000000FF0000L) |
                                           ((appParam[i+6] <<  8) & 0x000000000000FF00L) |
                                           ((appParam[i+7] <<  0) & 0x00000000000000FFL);
                    if (D) Log.i(TAG, "AppParam filter=" + appParamValue.filter);
                    i += ApplicationParameter.TRIPLET_LENGTH.FILTER_LENGTH;
                    break;
                case ApplicationParameter.TRIPLET_TAGID.ORDER_TAGID:
                    i += 2; // length and tag field in triplet
                    appParamValue.order = Byte.toString(appParam[i]);
                    if (D) Log.i(TAG, "AppParam order=" + appParamValue.order);
                    i += ApplicationParameter.TRIPLET_LENGTH.ORDER_LENGTH;
                    break;
                case ApplicationParameter.TRIPLET_TAGID.SEARCH_VALUE_TAGID:
                    i += 1; // length field in triplet
                    // length of search value is variable
                    int length = appParam[i];
                    if (length == 0) {
                        parseOk = false;
                        break;
                    }
                    if (appParam[i+length] == 0x0) {
                        appParamValue.searchValue = new String(appParam, i + 1, length-1);
                    } else {
                        appParamValue.searchValue = new String(appParam, i + 1, length);
                    }
                    if (D) Log.i(TAG, "AppParam searchValue=" + appParamValue.searchValue);
                    i += length;
                    i += 1;
                    break;
                case ApplicationParameter.TRIPLET_TAGID.SEARCH_ATTRIBUTE_TAGID:
                    i += 2;
                    appParamValue.searchAttr = Byte.toString(appParam[i]);
                    if (D) Log.i(TAG, "AppParam searchAttr=" + appParamValue.searchAttr);
                    i += ApplicationParameter.TRIPLET_LENGTH.SEARCH_ATTRIBUTE_LENGTH;
                    break;
                case ApplicationParameter.TRIPLET_TAGID.MAXLISTCOUNT_TAGID:
                    i += 2;
                    if (appParam[i] == 0 && appParam[i + 1] == 0) {
                        mNeedPhonebookSize = true;
                    } else {
                        int highValue = appParam[i] & 0xff;
                        int lowValue = appParam[i + 1] & 0xff;
                        appParamValue.maxListCount = highValue * 256 + lowValue;
                    }
                    if (D) Log.i(TAG, "AppParam maxListCount=" + appParamValue.maxListCount);
                    i += ApplicationParameter.TRIPLET_LENGTH.MAXLISTCOUNT_LENGTH;
                    break;
                case ApplicationParameter.TRIPLET_TAGID.LISTSTARTOFFSET_TAGID:
                    i += 2;
                    int highValue = appParam[i] & 0xff;
                    int lowValue = appParam[i + 1] & 0xff;
                    appParamValue.listStartOffset = highValue * 256 + lowValue;
                    if (D) Log.i(TAG, "AppParam listStartOffset=" + appParamValue.listStartOffset);
                    i += ApplicationParameter.TRIPLET_LENGTH.LISTSTARTOFFSET_LENGTH;
                    break;
                case ApplicationParameter.TRIPLET_TAGID.FORMAT_TAGID:
                    i += 2;// length field in triplet
                    if (appParam[i] != 0) {
                        appParamValue.vcard21 = false;
                    }
                    if (D) Log.i(TAG, "AppParam format=" + appParam[i]);
                    i += ApplicationParameter.TRIPLET_LENGTH.FORMAT_LENGTH;
                    break;
                default:
                    parseOk = false;
                    Log.e(TAG, "Parse Application Parameter error");
                    break;
            }
        }

        return parseOk;
    }

    /** Form and Send an XML format String to client for Phone book listing */
    private final int sendVcardListingXml(final int type, Operation op,
            final int maxListCount, final int listStartOffset, final String searchValue,
            String searchAttr) {
        StringBuilder result = new StringBuilder();
        int itemsFound = 0;
        result.append("<?xml version=\"1.0\"?>");
        result.append("<!DOCTYPE vcard-listing SYSTEM \"vcard-listing.dtd\">");
        result.append("<vCard-listing version=\"1.0\">");

        // Phonebook listing request
        if (type == ContentType.PHONEBOOK) {
            if (searchAttr.equals("0")) { // search by name
                itemsFound = createList(maxListCount, listStartOffset, searchValue, result,
                        "name");
            } else if (searchAttr.equals("1")) { // search by number
                itemsFound = createList(maxListCount, listStartOffset, searchValue, result,
                        "number");
            }// end of search by number
            else {
                return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
            }
        }
        // Call history listing request
        else {
            ArrayList<String> nameList = mVcardManager.loadCallHistoryList(type);
            int requestSize = nameList.size() >= maxListCount ? maxListCount : nameList.size();
            int startPoint = listStartOffset;
            int endPoint = startPoint + requestSize;
            if (endPoint > nameList.size()) {
                endPoint = nameList.size();
            }
            if (D) Log.d(TAG, "call log list, size=" + requestSize + " offset=" + listStartOffset);

            for (int j = startPoint; j < endPoint; j++) {
                writeVCardEntry(j+1, nameList.get(j),result);
            }
        }
        result.append("</vCard-listing>");

        if (V) Log.v(TAG, "itemsFound =" + itemsFound);

        return pushBytes(op, result.toString());
    }

    private int createList(final int maxListCount, final int listStartOffset,
            final String searchValue, StringBuilder result, String type) {
        int itemsFound = 0;
        ArrayList<String> nameList = mVcardManager.getPhonebookNameList(mOrderBy);
        final int requestSize = nameList.size() >= maxListCount ? maxListCount : nameList.size();
        final int listSize = nameList.size();
        String compareValue = "", currentValue;

        if (D) Log.d(TAG, "search by " + type + ", requestSize=" + requestSize + " offset="
                    + listStartOffset + " searchValue=" + searchValue);

        if (type.equals("number")) {
            // query the number, to get the names
            ArrayList<String> names = mVcardManager.getContactNamesByNumber(searchValue);
            for (int i = 0; i < names.size(); i++) {
                compareValue = names.get(i).trim();
                if (D) Log.d(TAG, "compareValue=" + compareValue);
                for (int pos = listStartOffset; pos < listSize &&
                        itemsFound < requestSize; pos++) {
                    currentValue = nameList.get(pos);
                    if (D) Log.d(TAG, "currentValue=" + currentValue);
                    if (currentValue.startsWith(compareValue)) {
                        itemsFound++;
                        writeVCardEntry(pos, currentValue,result);
                    }
                }
                if (itemsFound >= requestSize) {
                    break;
                }
            }
        } else {
            if (searchValue != null) {
                compareValue = searchValue.trim();
            }
            for (int pos = listStartOffset; pos < listSize &&
                    itemsFound < requestSize; pos++) {
                currentValue = nameList.get(pos);
                if (D) Log.d(TAG, "currentValue=" + currentValue);
                if (searchValue == null || currentValue.startsWith(compareValue)) {
                    itemsFound++;
                    writeVCardEntry(pos, currentValue,result);
                }
            }
        }
        return itemsFound;
    }

    /**
     * Function to send obex header back to client such as get phonebook size
     * request
     */
    private final int pushHeader(final Operation op, final HeaderSet reply) {
        OutputStream outputStream = null;

        if (D) Log.d(TAG, "Push Header");
        if (D) Log.d(TAG, reply.toString());

        int pushResult = ResponseCodes.OBEX_HTTP_OK;
        try {
            op.sendHeaders(reply);
            outputStream = op.openOutputStream();
            outputStream.flush();
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            pushResult = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        } finally {
            if (!closeStream(outputStream, op)) {
                pushResult = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
            }
        }
        return pushResult;
    }

    /** Function to send vcard data to client */
    private final int pushBytes(Operation op, final String vcardString) {
        if (vcardString == null) {
            Log.w(TAG, "vcardString is null!");
            return ResponseCodes.OBEX_HTTP_OK;
        }

        OutputStream outputStream = null;
        int pushResult = ResponseCodes.OBEX_HTTP_OK;
        try {
            outputStream = op.openOutputStream();
            outputStream.write(vcardString.getBytes());
            if (V) Log.v(TAG, "Send Data complete!");
        } catch (IOException e) {
            Log.e(TAG, "open/write outputstrem failed" + e.toString());
            pushResult = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        if (!closeStream(outputStream, op)) {
            pushResult = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        return pushResult;
    }

    private final int handleAppParaForResponse(AppParamValue appParamValue, int size,
            HeaderSet reply, Operation op) {
        byte[] misnum = new byte[1];
        ApplicationParameter ap = new ApplicationParameter();

        // In such case, PCE only want the number of index.
        // So response not contain any Body header.
        if (mNeedPhonebookSize) {
            if (V) Log.v(TAG, "Need Phonebook size in response header.");
            mNeedPhonebookSize = false;

            byte[] pbsize = new byte[2];

            pbsize[0] = (byte)((size / 256) & 0xff);// HIGH VALUE
            pbsize[1] = (byte)((size % 256) & 0xff);// LOW VALUE
            ap.addAPPHeader(ApplicationParameter.TRIPLET_TAGID.PHONEBOOKSIZE_TAGID,
                    ApplicationParameter.TRIPLET_LENGTH.PHONEBOOKSIZE_LENGTH, pbsize);

            if (mNeedNewMissedCallsNum) {
                mNeedNewMissedCallsNum = false;
                int nmnum = size - mMissedCallSize;
                mMissedCallSize = size;

                nmnum = nmnum > 0 ? nmnum : 0;
                misnum[0] = (byte)nmnum;
                ap.addAPPHeader(ApplicationParameter.TRIPLET_TAGID.NEWMISSEDCALLS_TAGID,
                        ApplicationParameter.TRIPLET_LENGTH.NEWMISSEDCALLS_LENGTH, misnum);
                if (D) Log.d(TAG, "handleAppParaForResponse(): mNeedNewMissedCallsNum=true,  num= "
                            + nmnum);
            }
            reply.setHeader(HeaderSet.APPLICATION_PARAMETER, ap.getAPPparam());

            if (D) Log.d(TAG, "Send back Phonebook size only, without body info! Size= " + size);

            return pushHeader(op, reply);
        }

        // Only apply to "mch" download/listing.
        // NewMissedCalls is used only in the response, together with Body
        // header.
        if (mNeedNewMissedCallsNum) {
            if (V) Log.v(TAG, "Need new missed call num in response header.");
            mNeedNewMissedCallsNum = false;

            int nmnum = size - mMissedCallSize;
            mMissedCallSize = size;

            nmnum = nmnum > 0 ? nmnum : 0;
            misnum[0] = (byte)nmnum;
            ap.addAPPHeader(ApplicationParameter.TRIPLET_TAGID.NEWMISSEDCALLS_TAGID,
                    ApplicationParameter.TRIPLET_LENGTH.NEWMISSEDCALLS_LENGTH, misnum);
            reply.setHeader(HeaderSet.APPLICATION_PARAMETER, ap.getAPPparam());
            if (D) Log.d(TAG, "handleAppParaForResponse(): mNeedNewMissedCallsNum=true,  num= "
                        + nmnum);

            // Only Specifies the headers, not write for now, will write to PCE
            // together with Body
            try {
                op.sendHeaders(reply);
            } catch (IOException e) {
                Log.e(TAG, e.toString());
                return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
            }
        }
        return NEED_SEND_BODY;
    }

    private final int pullVcardListing(byte[] appParam, AppParamValue appParamValue,
            HeaderSet reply, Operation op) {
        String searchAttr = appParamValue.searchAttr.trim();

        if (searchAttr == null || searchAttr.length() == 0) {
            // If searchAttr is not set by PCE, set default value per spec.
            appParamValue.searchAttr = "0";
            if (D) Log.d(TAG, "searchAttr is not set by PCE, assume search by name by default");
        } else if (!searchAttr.equals("0") && !searchAttr.equals("1")) {
            Log.w(TAG, "search attr not supported");
            if (searchAttr.equals("2")) {
                // search by sound is not supported currently
                Log.w(TAG, "do not support search by sound");
                return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
            }
            return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
        } else {
            Log.i(TAG, "searchAttr is valid: " + searchAttr);
        }

        int size = mVcardManager.getPhonebookSize(appParamValue.needTag);
        int needSendBody = handleAppParaForResponse(appParamValue, size, reply, op);
        if (needSendBody != NEED_SEND_BODY) {
            return needSendBody;
        }

        if (size == 0) {
            if (V) Log.v(TAG, "PhonebookSize is 0, return.");
            return ResponseCodes.OBEX_HTTP_OK;
        }

        String orderPara = appParamValue.order.trim();
        if (TextUtils.isEmpty(orderPara)) {
            // If order parameter is not set by PCE, set default value per spec.
            orderPara = "0";
            if (D) Log.d(TAG, "Order parameter is not set by PCE. " +
                       "Assume order by 'Indexed' by default");
        } else if (!orderPara.equals("0") && !orderPara.equals("1")) {
            if (V) Log.v(TAG, "Order parameter is not supported: " + appParamValue.order);
            if (orderPara.equals("2")) {
                // Order by sound is not supported currently
                Log.w(TAG, "Do not support order by sound");
                return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
            }
            return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
        } else {
            Log.i(TAG, "Order parameter is valid: " + orderPara);
        }

        if (orderPara.equals("0")) {
            mOrderBy = ORDER_BY_INDEXED;
        } else if (orderPara.equals("1")) {
            mOrderBy = ORDER_BY_ALPHABETICAL;
        }

        int sendResult = sendVcardListingXml(appParamValue.needTag, op, appParamValue.maxListCount,
                appParamValue.listStartOffset, appParamValue.searchValue,
                appParamValue.searchAttr);
        return sendResult;
    }

    private final int pullVcardEntry(byte[] appParam, AppParamValue appParamValue,
            Operation op, final String name, final String current_path) {
        if (name == null || name.length() < VCARD_NAME_SUFFIX_LENGTH) {
            if (D) Log.d(TAG, "Name is Null, or the length of name < 5 !");
            return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
        }
        String strIndex = name.substring(0, name.length() - VCARD_NAME_SUFFIX_LENGTH + 1);
        int intIndex = 0;
        if (strIndex.trim().length() != 0) {
            try {
                intIndex = Integer.parseInt(strIndex);
            } catch (NumberFormatException e) {
                Log.e(TAG, "catch number format exception " + e.toString());
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
        }

        int size = mVcardManager.getPhonebookSize(appParamValue.needTag);
        if (size == 0) {
            if (V) Log.v(TAG, "PhonebookSize is 0, return.");
            return ResponseCodes.OBEX_HTTP_NOT_FOUND;
        }

        boolean vcard21 = appParamValue.vcard21;
        if (appParamValue.needTag == 0) {
            Log.w(TAG, "wrong path!");
            return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
        } else if (appParamValue.needTag == ContentType.PHONEBOOK) {
            if (intIndex < 0 || intIndex >= size) {
                Log.w(TAG, "The requested vcard is not acceptable! name= " + name);
                return ResponseCodes.OBEX_HTTP_NOT_FOUND;
            } else if (intIndex == 0) {
                // For PB_PATH, 0.vcf is the phone number of this phone.
                String ownerVcard = mVcardManager.getOwnerPhoneNumberVcard(vcard21, appParamValue.getActualFilter());
                return pushBytes(op, ownerVcard);
            } else {
                return mVcardManager.composeAndSendPhonebookOneVcard(op, intIndex, vcard21, null,
                        mOrderBy, appParamValue.getActualFilter());
            }
        } else {
            if (intIndex <= 0 || intIndex > size) {
                Log.w(TAG, "The requested vcard is not acceptable! name= " + name);
                return ResponseCodes.OBEX_HTTP_NOT_FOUND;
            }
            // For others (ich/och/cch/mch), 0.vcf is meaningless, and must
            // begin from 1.vcf
            if (intIndex >= 1) {
                return mVcardManager.composeAndSendCallLogVcards(appParamValue.needTag, op,
                        intIndex, intIndex, vcard21, appParamValue.getActualFilter());
            }
        }
        return ResponseCodes.OBEX_HTTP_OK;
    }

    private final int pullPhonebook(byte[] appParam, AppParamValue appParamValue, HeaderSet reply,
            Operation op, final String name) {
        // code start for passing PTS3.2 TC_PSE_PBD_BI_01_C
        if (name != null) {
            int dotIndex = name.indexOf(".");
            String vcf = "vcf";
            if (dotIndex >= 0 && dotIndex <= name.length()) {
                if (name.regionMatches(dotIndex + 1, vcf, 0, vcf.length()) == false) {
                    Log.w(TAG, "name is not .vcf");
                    return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
                }
            }
        } // code end for passing PTS3.2 TC_PSE_PBD_BI_01_C

        int pbSize = mVcardManager.getPhonebookSize(appParamValue.needTag);
        int needSendBody = handleAppParaForResponse(appParamValue, pbSize, reply, op);
        if (needSendBody != NEED_SEND_BODY) {
            return needSendBody;
        }

        if (pbSize == 0) {
            if (V) Log.v(TAG, "PhonebookSize is 0, return.");
            return ResponseCodes.OBEX_HTTP_OK;
        }

        int requestSize = pbSize >= appParamValue.maxListCount ? appParamValue.maxListCount
                : pbSize;
        int startPoint = appParamValue.listStartOffset;
        if (startPoint < 0 || startPoint >= pbSize) {
            Log.w(TAG, "listStartOffset is not correct! " + startPoint);
            return ResponseCodes.OBEX_HTTP_OK;
        }

        // Limit the number of call log to CALLLOG_NUM_LIMIT
        if (appParamValue.needTag != BluetoothPbapObexServer.ContentType.PHONEBOOK) {
            if (requestSize > CALLLOG_NUM_LIMIT) {
               requestSize = CALLLOG_NUM_LIMIT;
            }
        }

        int endPoint = startPoint + requestSize - 1;
        if (endPoint > pbSize - 1) {
            endPoint = pbSize - 1;
        }
        if (D) Log.d(TAG, "pullPhonebook(): requestSize=" + requestSize + " startPoint=" +
                startPoint + " endPoint=" + endPoint);

        boolean vcard21 = appParamValue.vcard21;
        if (appParamValue.needTag == BluetoothPbapObexServer.ContentType.PHONEBOOK) {
            if (startPoint == 0) {
                String ownerVcard = mVcardManager.getOwnerPhoneNumberVcard(vcard21, appParamValue.getActualFilter());
                if (endPoint == 0) {
                    return pushBytes(op, ownerVcard);
                } else {
                    return mVcardManager.composeAndSendPhonebookVcards(op, 1, endPoint, vcard21,
                            appParamValue.getActualFilter(), ownerVcard);
                }
            } else {
                return mVcardManager.composeAndSendPhonebookVcards(op, startPoint, endPoint,
                        vcard21, appParamValue.getActualFilter(), null);
            }
        } else {
            return mVcardManager.composeAndSendCallLogVcards(appParamValue.needTag, op,
                    startPoint + 1, endPoint + 1, vcard21, appParamValue.getActualFilter());
        }
    }

    public static boolean closeStream(final OutputStream out, final Operation op) {
        boolean returnvalue = true;
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "outputStream close failed" + e.toString());
            returnvalue = false;
        }
        try {
            if (op != null) {
                op.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "operation close failed" + e.toString());
            returnvalue = false;
        }
        return returnvalue;
    }

    // Reserved for future use. In case PSE challenge PCE and PCE input wrong
    // session key.
    public final void onAuthenticationFailure(final byte[] userName) {
    }

    public static final String createSelectionPara(final int type) {
        String selection = null;
        switch (type) {
            case ContentType.INCOMING_CALL_HISTORY:
                selection = Calls.TYPE + "=" + CallLog.Calls.INCOMING_TYPE;
                break;
            case ContentType.OUTGOING_CALL_HISTORY:
                selection = Calls.TYPE + "=" + CallLog.Calls.OUTGOING_TYPE;
                break;
            case ContentType.MISSED_CALL_HISTORY:
                selection = Calls.TYPE + "=" + CallLog.Calls.MISSED_TYPE;
                break;
            default:
                break;
        }
        if (V) Log.v(TAG, "Call log selection: " + selection);
        return selection;
    }

    /**
     * XML encode special characters in the name field
     */
    private void xmlEncode(String name, StringBuilder result) {
        if (name == null) {
            return;
        }

        final StringCharacterIterator iterator = new StringCharacterIterator(name);
        char character =  iterator.current();
        while (character != CharacterIterator.DONE ){
            if (character == '<') {
                result.append("&lt;");
            }
            else if (character == '>') {
                result.append("&gt;");
            }
            else if (character == '\"') {
                result.append("&quot;");
            }
            else if (character == '\'') {
                result.append("&#039;");
            }
            else if (character == '&') {
                result.append("&amp;");
            }
            else {
                // The char is not a special one, add it to the result as is
                result.append(character);
            }
            character = iterator.next();
        }
    }

    private void writeVCardEntry(int vcfIndex, String name, StringBuilder result) {
        result.append("<card handle=\"");
        result.append(vcfIndex);
        result.append(".vcf\" name=\"");
        xmlEncode(name, result);
        result.append("\"/>");
    }

    public static final void logHeader(HeaderSet hs) {
        Log.v(TAG, "Dumping HeaderSet " + hs.toString());
        try {

            Log.v(TAG, "COUNT : " + hs.getHeader(HeaderSet.COUNT));
            Log.v(TAG, "NAME : " + hs.getHeader(HeaderSet.NAME));
            Log.v(TAG, "TYPE : " + hs.getHeader(HeaderSet.TYPE));
            Log.v(TAG, "LENGTH : " + hs.getHeader(HeaderSet.LENGTH));
            Log.v(TAG, "TIME_ISO_8601 : " + hs.getHeader(HeaderSet.TIME_ISO_8601));
            Log.v(TAG, "TIME_4_BYTE : " + hs.getHeader(HeaderSet.TIME_4_BYTE));
            Log.v(TAG, "DESCRIPTION : " + hs.getHeader(HeaderSet.DESCRIPTION));
            Log.v(TAG, "TARGET : " + hs.getHeader(HeaderSet.TARGET));
            Log.v(TAG, "HTTP : " + hs.getHeader(HeaderSet.HTTP));
            Log.v(TAG, "WHO : " + hs.getHeader(HeaderSet.WHO));
            Log.v(TAG, "OBJECT_CLASS : " + hs.getHeader(HeaderSet.OBJECT_CLASS));
            Log.v(TAG, "APPLICATION_PARAMETER : " + hs.getHeader(HeaderSet.APPLICATION_PARAMETER));
        } catch (IOException e) {
            Log.e(TAG, "dump HeaderSet error " + e);
        }
    }
}
