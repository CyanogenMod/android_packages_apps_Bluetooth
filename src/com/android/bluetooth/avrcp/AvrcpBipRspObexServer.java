/*
 * Copyright (c) 2015, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *           * Redistributions of source code must retain the above copyright
 *             notice, this list of conditions and the following disclaimer.
 *           * Redistributions in binary form must reproduce the above
 *           * copyright notice, this list of conditions and the following
 *             disclaimer in the documentation and/or other materials provided
 *             with the distribution.
 *           * Neither the name of The Linux Foundation nor the names of its
 *             contributors may be used to endorse or promote products derived
 *             from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.avrcp;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.text.format.DateUtils;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.io.OutputStream;

import javax.obex.HeaderSet;
import javax.obex.Operation;
import javax.obex.ResponseCodes;
import javax.obex.ServerRequestHandler;


public class AvrcpBipRspObexServer extends ServerRequestHandler {

    private static final String TAG = "AvrcpBipRspObexServer";

    private static final boolean D = true;
    private static final boolean V = AvrcpBipRsp.V;

    private static final int UUID_LENGTH = 16;

    private Handler mCallback = null;
    private Context mContext = null;
    private static AvrcpBipRspParser mAvrcpBipRspParser;
    private static boolean mAborted;
    private static boolean mConnected;

    // 128 bit UUID for Cover Art
    private static final byte[] BIP_RESPONDER = new byte[] {
             (byte)0x71, (byte)0x63, (byte)0xDD, (byte)0x54,
             (byte)0x4A, (byte)0x7E, (byte)0x11, (byte)0xE2,
             (byte)0xB4, (byte)0x7C, (byte)0x00, (byte)0x50,
             (byte)0xC2, (byte)0x49, (byte)0x00, (byte)0x48
             };

    /* Get Request types */
    private static final String TYPE_GET_IMAGE               = "x-bt/img-img";
    private static final String TYPE_GET_IMAGE_PROPERTIES    = "x-bt/img-properties";
    private static final String TYPE_GET_LINKED_THUMBNAIL    = "x-bt/img-thm";

    /**
     * Represents the OBEX Image handle header. This is (null terminated, UTF-16 encoded Unicode
     * text length prefixed with a two-byte unsigned integer.
     * <P>
     * The value of <code>IMG_HANDLE</code> is 0x30 (48).
     */
    public static final int IMG_HANDLE = 0x30;

    /**
     * Represents the OBEX Image Descriptor header. This is the byte sequence, length prefixed
     * with a two-byte unsigned integer.
     * <P>
     * The value of <code>IMG_DESCRIPTOR</code> is 0x71 (113).
     */
    public static final int IMG_DESCRIPTOR = 0x71;

    public AvrcpBipRspObexServer(Context context, Handler callback) {
        mContext = context;
        mCallback = callback;
        mAvrcpBipRspParser = null;
    }


    @Override
    public boolean isSrmSupported() {
        return true;
    }

    @Override
    public int onConnect(final HeaderSet request, HeaderSet reply) {
        if (D) Log.d(TAG, "onConnect()");
        if (V) logHeader(request);
        try {
            byte[] uuid = (byte[])request.getHeader(HeaderSet.TARGET);
            if (uuid == null) {
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : uuid) {
                sb.append(String.format("%02X ", b));
            }
            if (D) Log.d(TAG, "onConnect(): uuid=" + sb.toString());

            if (uuid.length != UUID_LENGTH) {
                Log.w(TAG, "Wrong UUID length");
                return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
            }
            for (int i = 0; i < UUID_LENGTH; i++) {
                if (uuid[i] != BIP_RESPONDER[i]) {
                    Log.w(TAG, "Wrong UUID");
                    return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
                }
            }
            reply.setHeader(HeaderSet.WHO, uuid);
        } catch (IOException e) {
            Log.e(TAG,"Exception during onConnect:", e);
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        try {
            byte[] remote = (byte[])request.getHeader(HeaderSet.WHO);
            if (remote != null) {
                if (V) Log.d(TAG, "onConnect(): remote=" + Arrays.toString(remote));
                reply.setHeader(HeaderSet.TARGET, remote);
            }
        } catch (IOException e) {
            Log.e(TAG,"Exception during onConnect:", e);
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        mAvrcpBipRspParser = new AvrcpBipRspParser(mContext);
        if (D) Log.d(TAG, "onConnect(): returning OBEX_HTTP_OK");
        mConnected = true;
        mAborted = false;
        if (mCallback != null)
            mCallback.sendMessage(mCallback
                    .obtainMessage(AvrcpBipRsp.MSG_OBEX_CONNECTED));
        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public void onDisconnect(final HeaderSet req, final HeaderSet resp) {
        if (D) Log.d(TAG, "onDisconnect()");
        resp.responseCode = ResponseCodes.OBEX_HTTP_OK;
        mConnected = false;
        if (V) logHeader(req);
        if (mCallback != null) {
            mCallback.sendMessage(mCallback
                    .obtainMessage(AvrcpBipRsp.MSG_OBEX_DISCONNECTED));
        }
    }

    @Override
    public int onAbort(HeaderSet request, HeaderSet reply) {
        if (D) Log.d(TAG, "onAbort()");
        mAborted = true;
        return ResponseCodes.OBEX_HTTP_OK;
    }

    @Override
    public void onClose() {
        if (D) Log.d(TAG, "onClose()");
        if (mCallback != null) {
            /* This might be caused when obex was directly disconnected without connect */
            mCallback.sendMessage(mCallback
                    .obtainMessage(AvrcpBipRsp.MSG_OBEX_SESSION_CLOSED));
            if (D) Log.d(TAG, "onClose(): msg MSG_OBEX_DISCONNECTED sent out.");
            mAvrcpBipRspParser = null;
            mCallback = null;
        }
    }

    @Override
    public int onGet(Operation op) {
        HeaderSet request;
        String type;
        String name;
        byte[] appParamRaw = null;
        String imgHandle = null;
        if (D) Log.d(TAG, "onGet()");
        try {
            request = op.getReceivedHeader();
            type = (String)request.getHeader(HeaderSet.TYPE);

            if (V) logHeader(request);
            if (D) Log.d(TAG, "OnGet type is " + type);

            if (type == null) {
                Log.w(TAG, "type is null, returning OBEX_HTTP_BAD_REQUEST");
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }
            if (request.getHeader(IMG_HANDLE) != null) {
                imgHandle = request.getHeader(IMG_HANDLE).toString();
                if (V) Log.v(TAG, "updated  imgHandle = "  + imgHandle);
                if (mAvrcpBipRspParser != null &&
                    mAvrcpBipRspParser.isImgHandleValid(imgHandle) == false) {
                    Log.w(TAG, "invalid handle = " + imgHandle);
                    return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
                }
                if (type.equals(TYPE_GET_IMAGE)) {
                    if (V) Log.v(TAG,"TYPE_GET_IMAGE");
                    String imgDescXmlString = null;
                    if (request.getHeader(IMG_DESCRIPTOR) != null) {
                        imgDescXmlString = new String((byte [])request.getHeader(IMG_DESCRIPTOR));
                        if (V) Log.v(TAG, "imgDescXmlString = "  + imgDescXmlString);
                    }
                    return getImgRsp(op, imgHandle, imgDescXmlString);
                } else if (type.equals(TYPE_GET_IMAGE_PROPERTIES)) {
                    if (V) Log.v(TAG,"TYPE_GET_IMAGE_PROPERTIES");
                    return getImgPropertiesRsp(op, imgHandle);
                } else if (type.equals(TYPE_GET_LINKED_THUMBNAIL)) {
                    if (V) Log.v(TAG,"TYPE_GET_LINKED_THUMBNAIL");
                    return getImgThumbRsp(op, imgHandle);
                } else {
                    Log.w(TAG, "unknown Get Type = " + type);
                    return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
                }
            } else {
                Log.w(TAG, "Image Handle = NULL");
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Exception:", e);
            return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
        } catch (Exception e) {
            if(D) {
                Log.e(TAG, "Exception occured while handling request", e);
            } else {
                Log.e(TAG, "Exception occured while handling request");
            }
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }
    }

    public String getImgHandle(String albumName) {

        if (D) Log.v(TAG, "getImageHandle: albumName = " + albumName);
        if (mAvrcpBipRspParser == null) {
            if (V) Log.v(TAG, "getImageHandle: mAvrcpBipRspParser = null");
            return null;
        }

        if (albumName == null) {
            if (V) Log.v(TAG, "getImageHandle: albumName = null");
            return null;
        }

        return mAvrcpBipRspParser.getImgHandle(albumName);
    }

    private static final void logHeader(HeaderSet hs) {
        Log.v(TAG, "Dumping HeaderSet " + hs.toString());
        try {
            Log.v(TAG, "CONNECTION_ID : " + hs.getHeader(HeaderSet.CONNECTION_ID));
            Log.v(TAG, "NAME : " + hs.getHeader(HeaderSet.NAME));
            Log.v(TAG, "TYPE : " + hs.getHeader(HeaderSet.TYPE));
            Log.v(TAG, "TARGET : " + hs.getHeader(HeaderSet.TARGET));
            Log.v(TAG, "WHO : " + hs.getHeader(HeaderSet.WHO));
            Log.v(TAG, "IMAGE HANDLE : " + hs.getHeader(IMG_HANDLE));
            Log.v(TAG, "IMAGE DESCRIPTOR : " + hs.getHeader(IMG_DESCRIPTOR));
        } catch (IOException e) {
            Log.e(TAG, "dump HeaderSet error " + e);
        }
        Log.v(TAG, "Dumping HeaderSet END");
    }

    private int getImgPropertiesRsp(Operation op, String imgHandle) {
        OutputStream outStream = null;
        byte[] outBytes = null;
        int maxChunkSize, bytesWritten = 0;
        int bytesToWrite;

        try {

            outBytes = mAvrcpBipRspParser.encode(imgHandle);
            outStream = op.openOutputStream();
        } catch (IOException e1) {
            Log.w(TAG,"getImgPropertiesRsp: IOException" +
                    " - sending OBEX_HTTP_BAD_REQUEST Exception:", e1);
            if(outStream != null) {
                try {
                    outStream.close();
                } catch (IOException e) {
                }
            }
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        } catch (IllegalArgumentException e1) {
            Log.w(TAG,"getImgPropertiesRsp: IllegalArgumentException" +
                    " - sending OBEX_HTTP_BAD_REQUEST Exception:", e1);
            if(outStream != null) {
                try {
                    outStream.close();
                } catch (IOException e) {
                    Log.w(TAG,"getImgPropertiesRsp: IOException:", e);
                }
            }
            return ResponseCodes.OBEX_HTTP_PRECON_FAILED;
        }

        maxChunkSize = op.getMaxPacketSize(); // This must be called after setting the headers.

        if(outBytes != null) {
            try {
                while (bytesWritten < outBytes.length) {
                    bytesToWrite = Math.min(maxChunkSize, outBytes.length - bytesWritten);
                    outStream.write(outBytes, bytesWritten, bytesToWrite);
                    bytesWritten += bytesToWrite;
                }
            } catch (IOException e) {
                // We were probably aborted or disconnected
                Log.w(TAG,"getImgPropertiesRsp: IOException:", e);
            } finally {
                if(outStream != null) { try { outStream.close(); } catch (IOException e) {} }
            }
            if(V)
                Log.v(TAG,"getImgPropertiesRsp sent " + bytesWritten +" bytes out of "
                + outBytes.length);
            if(bytesWritten == outBytes.length)
                return ResponseCodes.OBEX_HTTP_OK;
            else
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }
        return ResponseCodes.OBEX_HTTP_BAD_REQUEST;

    }

    private int getImgThumbRsp(Operation op, String imgHandle) {
        OutputStream outStream = null;
        try {
            outStream = op.openOutputStream();
        } catch (IOException e) {
            Log.w(TAG,"getImgThumbRsp: IOException" +
                    " - sending OBEX_HTTP_BAD_REQUEST Exception:", e);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        Log.v(TAG,"getImgThumbRsp: imgHandle = " + imgHandle);
        if (mAvrcpBipRspParser.getImgThumb(outStream, imgHandle)) {
            if (!mAborted && mConnected) {
                if (V) Log.d(TAG,"getImgThumbRsp: returning OBEX_HTTP_OK");
                return ResponseCodes.OBEX_HTTP_OK;
            } else {
                /* abort was issued while get was being requested. Return error now */
                mAborted = false;
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }
        }
        if (D) Log.d(TAG,"getImgThumbRsp: returning OBEX_HTTP_BAD_REQUEST");
        return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
    }

    private int getImgRsp(Operation op, String imgHandle, String imgDescXmlString) {
        OutputStream outStream = null;
        try {
            outStream = op.openOutputStream();
        } catch (IOException e) {
            Log.w(TAG,"getImgRsp: IOException" +
                    " - sending OBEX_HTTP_BAD_REQUEST Exception:", e);
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }

        Log.v(TAG,"getImgRsp: imgHandle = " + imgHandle);
        if (mAvrcpBipRspParser.getImg(outStream, imgHandle, imgDescXmlString)) {
            if (!mAborted && mConnected) {
                if (V) Log.d(TAG,"getImgRsp: returning OBEX_HTTP_OK");
                return ResponseCodes.OBEX_HTTP_OK;
            } else {
                /* abort was issued while get was being requested. Return error now */
                mAborted = false;
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }
        }
        if (D) Log.d(TAG,"getImgRsp: returning OBEX_HTTP_BAD_REQUEST");
        return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
    }
}
