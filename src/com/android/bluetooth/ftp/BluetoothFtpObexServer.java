/*
 * Copyright (c) 2010, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *            notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *            notice, this list of conditions and the following disclaimer in the
 *            documentation and/or other materials provided with the distribution.
 *        * Neither the name of Code Aurora nor
 *            the names of its contributors may be used to endorse or promote
 *            products derived from this software without specific prior written
 *            permission.
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

package com.android.bluetooth.ftp;

import android.content.Context;
import android.os.Message;
import android.os.Handler;
import android.os.StatFs;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.util.Date;
import java.lang.StringBuffer;

import javax.obex.ServerRequestHandler;
import javax.obex.ResponseCodes;
import javax.obex.ApplicationParameter;
import javax.obex.ServerOperation;
import javax.obex.Operation;
import javax.obex.HeaderSet;

public class BluetoothFtpObexServer extends ServerRequestHandler {

    private static final String TAG = "BluetoothFtpObexServer";

    private static final boolean D = BluetoothFtpService.DEBUG;

    private static final boolean V = BluetoothFtpService.VERBOSE;

    private static final int UUID_LENGTH = 16;

    // type for list folder contents
    private static final String TYPE_LISTING = "x-obex/folder-listing";

    private static final String ROOT_FOLDER_PATH = "/sdcard";

   // record current path the client are browsing
    private String mCurrentPath = "";

    private long mConnectionId;

    private Handler mCallback = null;

    private Context mContext;

    public static boolean sIsAborted = false;

    // 128 bit UUID for FTP
    private static final byte[] FTP_TARGET = new byte[] {
            (byte)0xF9, (byte)0xEC, (byte)0x7B, (byte)0xC4, (byte)0x95,
            (byte)0x3c, (byte)0x11, (byte)0xD2, (byte)0x98, (byte)0x4E,
            (byte)0x52, (byte)0x54, (byte)0x00, (byte)0xDc, (byte)0x9E,
            (byte)0x09
    };

    private static final String[] LEGAL_PATH = {"/sdcard"};

    public BluetoothFtpObexServer(Handler callback, Context context) {
        super();
        mConnectionId = -1;
        mCallback = callback;
        mContext = context;
        // set initial value when ObexServer created
        if (D) Log.d(TAG, "Initialize FtpObexServer");
    }
    /**
    * onConnect
    *
    * Called when a CONNECT request is received.
    *
    * @param request contains the headers sent by the client;
    *        request will never be null
    * @param reply the headers that should be sent in the reply;
    *        reply will never be null
    * @return a response code defined in ResponseCodes that will
    *         be returned to the client; if an invalid response code is
    *         provided, the OBEX_HTTP_INTERNAL_ERROR response code
    *         will be used
    */
    @Override
    public int onConnect(final HeaderSet request, HeaderSet reply) {
        if (D) Log.d(TAG, "onConnect()+");
        /* Extract the Target header */
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
            /* Compare the Uuid from target with FTP service uuid */
            for (int i = 0; i < UUID_LENGTH; i++) {
                if (uuid[i] != FTP_TARGET[i]) {
                    Log.w(TAG, "Wrong UUID");
                    return ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE;
                }
            }
            /* Add the uuid into the WHO header part of connect reply */
            reply.setHeader(HeaderSet.WHO, uuid);
        } catch (IOException e) {
            Log.e(TAG,"onConnect "+ e.toString());
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        /* Extract the remote WHO header and fill it in the Target header*/
        try {
            byte[] remote = (byte[])request.getHeader(HeaderSet.WHO);
            if (remote != null) {
                if (D) Log.d(TAG, "onConnect(): remote=" +
                                                 Arrays.toString(remote));
                reply.setHeader(HeaderSet.TARGET, remote);
            }
        } catch (IOException e) {
            Log.e(TAG,"onConnect "+ e.toString());
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        if (V) Log.v(TAG, "onConnect(): uuid is ok, will send out " +
                "MSG_SESSION_ESTABLISHED msg.");
        /* Notify the FTP service about the session establishment */
        Message msg = Message.obtain(mCallback);
        msg.what = BluetoothFtpService.MSG_SESSION_ESTABLISHED;
        msg.sendToTarget();
        /* Initialise the mCurrentPath to ROOT path = /sdcard */
        mCurrentPath = ROOT_FOLDER_PATH;
        if (D) Log.d(TAG, "onConnect() -");
        return ResponseCodes.OBEX_HTTP_OK;
    }
    /**
    * onDisconnect
    *
    * Called when a DISCONNECT request is received.
    *
    * @param request contains the headers sent by the client;
    *        request will never be null
    * @param reply the headers that should be sent in the reply;
    *        reply will never be null
    */
    @Override
    public void onDisconnect(final HeaderSet req, final HeaderSet resp) {
        if (D) Log.d(TAG, "onDisconnect() +");

        resp.responseCode = ResponseCodes.OBEX_HTTP_OK;
        /* Send a message to the FTP service to close the Server session */
        if (mCallback != null) {
            Message msg = Message.obtain(mCallback);
            msg.what = BluetoothFtpService.MSG_SESSION_DISCONNECTED;
            msg.sendToTarget();
            if (V) Log.v(TAG,
                 "onDisconnect(): msg MSG_SESSION_DISCONNECTED sent out.");
        }
        if (D) Log.d(TAG, "onDisconnect() -");
    }
    /**
    * Called when a ABORT request is received.
    */
    @Override
    public int onAbort(HeaderSet request, HeaderSet reply) {
        if (D) Log.d(TAG, "onAbort() +");
        sIsAborted = true;
        if (D) Log.d(TAG, "onAbort() -");
        return ResponseCodes.OBEX_HTTP_OK;
    }
    /**
    * onDelete
    *
    * Called when a DELETE request is received.
    *
    * @param request contains the headers sent by the client;
    *        request will never be null
    * @param reply the headers that should be sent in the reply;
    *        reply will never be null
    * @return a response code defined in ResponseCodes that will
    *         be returned to the client; if an invalid response code is
    *         provided, the OBEX_HTTP_INTERNAL_ERROR response code
    *         will be used
    */
    @Override
    public int onDelete(HeaderSet request, HeaderSet reply) {
        if (D) Log.d(TAG, "onDelete() +");
        String name = "";
        /* Check if Card is mounted */
        if(checkMountedState() == false) {
           Log.e(TAG,"SD card not Mounted");
           return ResponseCodes.OBEX_HTTP_NO_CONTENT;
        }
        /* 1. Extract the name header
         * 2. Check if the file exists by appending the name to current path
         * 3. Check if its read only
         * 4. Check if the directory is read only
         * 5. If 2 satisfies and 3 ,4 are not true proceed to delete the file
         */
        try{
           name = (String)request.getHeader(HeaderSet.NAME);
           if (D) Log.d(TAG,"OnDelete File = " + name +
                                          "mCurrentPath = " + mCurrentPath );
           File deleteFile = new File(mCurrentPath + "/" + name);
           if(deleteFile.exists() == true){
               if (D) Log.d(TAG, "onDelete(): Found File" + name + "in folder "
                                                         + mCurrentPath);
               if(!deleteFile.canWrite()) {
                   return ResponseCodes.OBEX_HTTP_UNAUTHORIZED;
               }

               if(deleteFile.isDirectory()) {
                   if(!deleteDirectory(deleteFile)) {
                       if (D) Log.d(TAG,"Directory  delete unsuccessful");
                       return ResponseCodes.OBEX_HTTP_UNAUTHORIZED;
                   }
               } else {
                   if(!deleteFile.delete()){
                       if (D) Log.d(TAG,"File delete unsuccessful");
                       return ResponseCodes.OBEX_HTTP_UNAUTHORIZED;
                   }
               }
           }
           else{
               if (D) Log.d(TAG,"File doesnot exist");
               return ResponseCodes.OBEX_HTTP_NOT_FOUND;
           }
        }catch (IOException e) {
            Log.e(TAG,"onDelete "+ e.toString());
            if (D) Log.d(TAG, "Delete operation failed");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        if (D) Log.d(TAG, "onDelete() -");
        return ResponseCodes.OBEX_HTTP_OK;
    }
    /**
    * onPut
    *
    * Called when a PUT request is received.
    *
    * If an ABORT request is received during the processing of a PUT request,
    * op will be closed by the implementation.
    * @param operation contains the headers sent by the client and allows new
    *        headers to be sent in the reply; op will never be
    *        null
    * @return a response code defined in ResponseCodes that will
    *         be returned to the client; if an invalid response code is
    *         provided, the OBEX_HTTP_INTERNAL_ERROR response code
    *         will be used
    */
    @Override
    public int onPut(final Operation op) {
        if (D) Log.d(TAG, "onPut() +");
        HeaderSet request = null;
        long length;
        String name = "";
        String filetype = "";
        int obexResponse = ResponseCodes.OBEX_HTTP_OK;

        if(checkMountedState() == false) {
            Log.e(TAG,"SD card not Mounted");
            return ResponseCodes.OBEX_HTTP_NO_CONTENT;
        }
        /* 1. Extract the name,length and type header from operation object
         *  2. check if name is null or empty
         *  3. Check if disk has available space for the length of file
         *  4. Open an input stream for the Bluetooth Socket and a file handle
         *     to the folder
         *  5. Check if the file exists and can be overwritten
         *  6. If 2,5 is false and 3 is satisfied proceed to read from the input
         *     stream and write to the new file
         */
        try {
            request = op.getReceivedHeader();
            length = (Long)request.getHeader(HeaderSet.LENGTH);
            name = (String)request.getHeader(HeaderSet.NAME);
            filetype = (String)request.getHeader(HeaderSet.TYPE);
            if (D) Log.d(TAG,"type = " + filetype + " name = " + name
                    + " Current Path = " + mCurrentPath + "length = " + length);

            if (length == 0) {
                if (D) Log.d(TAG, "length is 0,proceeding with the transfer");
            }
            if (name == null || name.equals("")) {
                if (D) Log.d(TAG, "name is null or empty, reject the transfer");
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }
            if(checkAvailableSpace(length) == false) {
                if (D) Log.d(TAG,"No Space Available");
                return ResponseCodes.OBEX_HTTP_ENTITY_TOO_LARGE;
            }
            BufferedOutputStream buff_op_stream = null;
            InputStream in_stream = null;

            try {
                in_stream = op.openInputStream();
            } catch (IOException e1) {
                Log.e(TAG,"onPut open input stream "+ e1.toString());
                if (D) Log.d(TAG, "Error while openInputStream");
                return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
            }

            int positioninfile = 0;
            File fileinfo = new File(mCurrentPath+ "/" + name);
            if(fileinfo.getParentFile().canWrite() == false) {
                if (D) Log.d(TAG,"Dir "+ fileinfo.getParent() +"is read-only");
                return ResponseCodes.OBEX_DATABASE_LOCKED;
            }
            if(fileinfo.exists() == true) {
                if(fileinfo.canWrite() == false) {
                    if (D) Log.d(TAG,"File "+ fileinfo.getName()
                                             +" is readonly can't replace it");
                    return ResponseCodes.OBEX_DATABASE_LOCKED;
                }
            }

            FileOutputStream fileOutputStream = new FileOutputStream(fileinfo);
            buff_op_stream = new BufferedOutputStream(fileOutputStream, 0x4000);
            int outputBufferSize = op.getMaxPacketSize();
            byte[] buff = new byte[outputBufferSize];
            int readLength = 0;
            long timestamp = 0;
            long starttimestamp = System.currentTimeMillis();
            try {
                while ((positioninfile != length)) {
                    if (sIsAborted) {
                        ((ServerOperation)op).isAborted = true;
                        sIsAborted = false;
                        break;
                    }
                    timestamp = System.currentTimeMillis();
                    if (V) Log.v(TAG,"Read Socket >");
                    readLength = in_stream.read(buff);
                    if (V) Log.v(TAG,"Read Socket <");

                    if (readLength == -1) {
                        if (D) Log.d(TAG,"File reached end at position"
                                                             + positioninfile);
                        break;
                    }

                    buff_op_stream.write(buff, 0, readLength);
                    positioninfile += readLength;

                    if (V) {
                        Log.v(TAG, "Receive file position = " + positioninfile
                                  + " readLength "+ readLength + " bytes took "
                                  + (System.currentTimeMillis() - timestamp) +
                                  " ms");
                    }
                }
            }catch (IOException e1) {
                Log.e(TAG, "onPut File receive"+ e1.toString());
                if (D) Log.d(TAG, "Error when receiving file");
                ((ServerOperation)op).isAborted = true;
                return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
            }

            long finishtimestamp = System.currentTimeMillis();
            Log.i(TAG,"Put Request TP analysis : Received  "+ positioninfile +
                      " bytes in " + (finishtimestamp - starttimestamp)+"ms");
            if (buff_op_stream != null) {
                try {
                    buff_op_stream.close();
                 } catch (IOException e) {
                     Log.e(TAG,"onPut close stream "+ e.toString());
                     if (D) Log.d(TAG, "Error when closing stream after send");
                     return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                }
            }
            if(D) Log.d(TAG,"close Stream >");
            if (!closeStream(in_stream, op)) {
                if (D) Log.d(TAG,"Failed to close Input stream");
                return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
            }
            if (D) Log.d(TAG,"close Stream <");

        }catch (IOException e) {
            Log.e(TAG, "onPut headers error "+ e.toString());
            if (D) Log.d(TAG, "request headers error");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        if (D) Log.d(TAG, "onPut() -");
        return ResponseCodes.OBEX_HTTP_OK;
    }
    /**
    * Called when a SETPATH request is received.
    *
    * @param request contains the headers sent by the client;
    *        request will never be null
    * @param reply the headers that should be sent in the reply;
    *        reply will never be null
    * @param backup true if the client requests that the server
    *        back up one directory before changing to the path described by
    *        name; false to apply the request to the
    *        present path
    * @param create true if the path should be created if it does
    *        not already exist; false if the path should not be
    *        created if it does not exist and an error code should be returned
    * @return a response code defined in ResponseCodes that will
    *         be returned to the client; if an invalid response code is
    *         provided, the OBEX_HTTP_INTERNAL_ERROR response code
    *         will be used
    */
    @Override
    public int onSetPath(final HeaderSet request, final HeaderSet reply, final boolean backup,
            final boolean create) {

        if (D) Log.d(TAG, "onSetPath() +");

        String current_path_tmp = mCurrentPath;
        String tmp_path = null;
        /* Check if Card is mounted */
        if(checkMountedState() == false) {
           Log.e(TAG,"SD card not Mounted");
           return ResponseCodes.OBEX_HTTP_NO_CONTENT;
        }
        /* Extract the name header */
        try {
            tmp_path = (String)request.getHeader(HeaderSet.NAME);
        } catch (IOException e) {
            Log.e(TAG,"onSetPath  get header"+ e.toString());
            if (D) Log.d(TAG, "Get name header fail");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        if (D) Log.d(TAG, "backup=" + backup + " create=" + create +
                   " name=" + tmp_path +"mCurrentPath = " + mCurrentPath);

        /* If backup flag is set then if the current path is not null then
         * remove the substring till '/' in the current path For ex. if current
         * path is "/sdcard/bluetooth" we will return a string "/sdcard" into
         * current_path_tmp
         *
         * else we append the name to the current path if not null or else
         * set the current path to ROOT Folder path
         */
        if (backup) {
            if (current_path_tmp.length() != 0) {
                current_path_tmp = current_path_tmp.substring(0,
                        current_path_tmp.lastIndexOf("/"));
            }
        } else {
            if (tmp_path == null) {
                current_path_tmp = ROOT_FOLDER_PATH;
            } else {
                current_path_tmp = current_path_tmp + "/" + tmp_path;
            }
        }

        /* If the Path passed in the name doesnot exist and if the create flag
         * is set we proceed towards creating the folder in the current folder
         *
         * else if the path doesnot exist and the create flag is not set we
         * return ResponseCodes.OBEX_HTTP_NOT_FOUND
         */
        if ((current_path_tmp.length() != 0) &&
                                  (!doesPathExist(current_path_tmp))) {
            if (D) Log.d(TAG, "Current path has valid length ");
            if (create) {
                if (D) Log.d(TAG, "path create is not forbidden!");
                File filecreate = new File(current_path_tmp);
                filecreate.mkdir();
                mCurrentPath = current_path_tmp;
                return ResponseCodes.OBEX_HTTP_OK;
            } else {
                if (D) Log.d(TAG, "path not found error");
                return ResponseCodes.OBEX_HTTP_NOT_FOUND;
            }
        }
        /* We have already reached the root folder but user tries to press the
         * back button
         */
        if(current_path_tmp.length() == 0){
              current_path_tmp = ROOT_FOLDER_PATH;
        }

        mCurrentPath = current_path_tmp;
        if (V) Log.v(TAG, "after setPath, mCurrentPath ==  " + mCurrentPath);

        if (D) Log.d(TAG, "onSetPath() -");

        return ResponseCodes.OBEX_HTTP_OK;
    }
    /**
    * Called when session is closed.
    */
    @Override
    public void onClose() {
        if (D) Log.d(TAG, "onClose() +");
        /* Send a message to the FTP service to close the Server session */
        if (mCallback != null) {
            Message msg = Message.obtain(mCallback);
            msg.what = BluetoothFtpService.MSG_SERVERSESSION_CLOSE;
            msg.sendToTarget();
            if (D) Log.d(TAG,
                       "onClose(): msg MSG_SERVERSESSION_CLOSE sent out.");
        }
        if (D) Log.d(TAG, "onClose() -");
    }

    @Override
    public int onGet(Operation op) {
        if (D) Log.d(TAG, "onGet() +");

        sIsAborted = false;
        HeaderSet request = null;
        String type = "";
        String name = "";
        /* Check if Card is mounted */
        if(checkMountedState() == false) {
           Log.e(TAG,"SD card not Mounted");
           return ResponseCodes.OBEX_HTTP_NO_CONTENT;
        }
        /*Extract the name and type header from operation object */
        try {
            request = op.getReceivedHeader();
            type = (String)request.getHeader(HeaderSet.TYPE);
            name = (String)request.getHeader(HeaderSet.NAME);
        } catch (IOException e) {
            Log.e(TAG,"onGet request headers "+ e.toString());
            if (D) Log.d(TAG, "request headers error");
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        if (D) Log.d(TAG,"type = " + type + " name = " + name +
                                       " Current Path = " + mCurrentPath);

        boolean validName = true;

        if (TextUtils.isEmpty(name)) {
            validName = false;
        }
        if (D) Log.d(TAG,"validName = " + validName);

        if(type != null) {
            /* If type is folder listing then invoke the routine to package
             * the folder listing contents in xml format
             *
             * Else call the routine to send the requested file names contents
             */
             if(type.equals(TYPE_LISTING)){
                if(!validName){
                    if (D) Log.d(TAG,"Not having a name");
                    File rootfolder = new File(mCurrentPath);
                    File [] files = rootfolder.listFiles();
                    for(int i = 0; i < files.length; i++)
                        if (D) Log.d(TAG,"Folder listing =" + files[i] );
                    return sendFolderListingXml(0,op,files);
                } else {
                    if (D) Log.d(TAG,"Non Root Folder");
                    if(type.equals(TYPE_LISTING)){
                        File currentfolder = new File(mCurrentPath);
                        if (D) Log.d(TAG,"Current folder name = " +
                                                currentfolder.getName() +
                                          "Requested subFolder =" + name);
                        if(currentfolder.getName().compareTo(name) != 0) {
                            if (D) {
                                Log.d(TAG,"Not currently in this folder");
                            }
                            File subFolder = new File(mCurrentPath +"/"+ name);
                            if(subFolder.exists()) {
                                File [] files = subFolder.listFiles();
                                return sendFolderListingXml(0,op,files);
                            } else {
                                Log.e(TAG,
                                    "ResponseCodes.OBEX_HTTP_NO_CONTENT");
                                return ResponseCodes.OBEX_HTTP_NO_CONTENT;
                            }
                        }

                        File [] files = currentfolder.listFiles();
                        for(int i = 0; i < files.length; i++)
                           if (D) Log.d(TAG,"Non Root Folder listing =" + files[i] );
                        return sendFolderListingXml(0,op,files);
                    }
                }
            }
        } else {
            if (D) Log.d(TAG,"File get request");
            File fileinfo = new File (mCurrentPath + "/" + name);
            return sendFileContents(op,fileinfo);
        }
        if (D) Log.d(TAG, "onGet() -");
        return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
    }
    /**
    * deleteDirectory
    *
    * Called when a PUT request is received to delete a non empty folder
    *
    * @param dir provides the handle to the directory to be deleted
    * @return a TRUE if operation was succesful or false otherwise
    */
    private final boolean deleteDirectory(File dir) {
        if (D) Log.d(TAG, "deleteDirectory() +");
        if(dir.exists()) {
            File [] files = dir.listFiles();
            for(int i = 0; i < files.length;i++) {
                if(files[i].isDirectory()) {
                    deleteDirectory(files[i]);
                    if (D) Log.d(TAG,"Dir Delete =" + files[i].getName());
                } else {
                    if (D) Log.d(TAG,"File Delete =" + files[i].getName());
                    files[i].delete();
                }
            }
        }

        if (D) Log.d(TAG, "deleteDirectory() -");
        return( dir.delete() );
    }
    /**
    * sendFileContents
    *
    * Called when a GET request to get the contents of a File is received
    *
    * @param op provides the handle to the current server operation
    * @param fileinfo provides the handle to the file to be sent
    * @return  a response code defined in ResponseCodes that will
    *         be returned to the client; if an invalid response code is
    *         provided, the OBEX_HTTP_INTERNAL_ERROR response code
    *         will be used
    */
    private final int sendFileContents(Operation op,File fileinfo){

        if (D) Log.d(TAG,"sendFile + = " + fileinfo.getName() );
        int position = 0;
        int readLength = 0;
        boolean isitokToProceed = false;
        int outputBufferSize = op.getMaxPacketSize();
        long timestamp = 0;
        int responseCode = -1;
        FileInputStream fileInputStream;
        OutputStream outputStream;
        BufferedInputStream bis;
        long finishtimestamp;
        long starttimestamp;

        byte[] buffer = new byte[outputBufferSize];
        try {
            fileInputStream = new FileInputStream(fileinfo);
            outputStream = op.openOutputStream();
        } catch(IOException e) {
            Log.e(TAG,"SendFilecontents open stream "+ e.toString());
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        bis = new BufferedInputStream(fileInputStream, 0x4000);
        starttimestamp = System.currentTimeMillis();
        try {
            while ((position != fileinfo.length())) {
                if (sIsAborted) {
                    ((ServerOperation)op).isAborted = true;
                    sIsAborted = false;
                    break;
                }
                timestamp = System.currentTimeMillis();
                if(position != fileinfo.length()){
                    readLength = bis.read(buffer, 0, outputBufferSize);
                }
                if (D) Log.d(TAG,"Read File");
                outputStream.write(buffer, 0, readLength);
                position += readLength;
                if (V) {
                    Log.v(TAG, "Sending file position = " + position
                       + " readLength " + readLength + " bytes took "
                       + (System.currentTimeMillis() - timestamp) + " ms");
                }
            }
        } catch (IOException e) {
            Log.e(TAG,"Write aborted " + e.toString());
            if (D) Log.d(TAG,"Write Abort Received");
            ((ServerOperation)op).isAborted = true;
            return ResponseCodes.OBEX_HTTP_BAD_REQUEST;
        }
        finishtimestamp = System.currentTimeMillis();
        if (bis != null) {
            try {
                bis.close();
            } catch (IOException e) {
                Log.e(TAG,"input stream close" + e.toString());
                if (D) Log.d(TAG, "Error when closing stream after send");
                return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
            }
        }

        if (!closeStream(outputStream, op)) {
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        if (D) Log.d(TAG,"sendFile - position = " + position );
        if(position == fileinfo.length()) {
            Log.i(TAG,"Get Request TP analysis : Transmitted "+ position +
                  " bytes in" + (finishtimestamp - starttimestamp)  + "ms");
            return ResponseCodes.OBEX_HTTP_OK;
        }else {
            return ResponseCodes.OBEX_HTTP_CONTINUE;
        }
    }

    /** check whether path is legal */
    private final boolean doesPathExist(final String str) {
        if (D) Log.d(TAG,"doesPathExist + = " + str );
        File searchfolder = new File(str);
        if(searchfolder.exists())
            return true;
        return false;
    }

    /** Check the Mounted State of External Storage */
    private final boolean checkMountedState() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        } else {
            if (D) Log.d(TAG,"SD card Media not mounted");
            return false;
        }
    }

    /** Check the Available Space on External Storage */
    private final boolean checkAvailableSpace(long filelength) {
        StatFs stat = new StatFs(ROOT_FOLDER_PATH);
        if (D) Log.d(TAG,"stat.getAvailableBlocks() "+ stat.getAvailableBlocks());
        if (D) Log.d(TAG,"stat.getBlockSize() ="+ stat.getBlockSize());
        long availabledisksize = stat.getBlockSize() * ((long)stat.getAvailableBlocks() - 4);
        if (D) Log.d(TAG,"Disk size = " + availabledisksize + "File length = " + filelength);
        if (stat.getBlockSize() * ((long)stat.getAvailableBlocks() - 4) <  filelength) {
            if (D) Log.d(TAG,"Not Enough Space hence can't receive the file");
            return false;
        } else {
            return true;
        }
    }

    /** Function to send folder listing data to client */
    private final int pushBytes(Operation op, final String folderlistString) {
        if (D) Log.d(TAG,"pushBytes +");
        if (folderlistString == null) {
            if (D) Log.d(TAG, "folderlistString is null!");
            return ResponseCodes.OBEX_HTTP_OK;
        }

        int folderlistStringLen = folderlistString.length();
        if (D) Log.d(TAG, "Send Data: len=" + folderlistStringLen);

        OutputStream outputStream = null;
        int pushResult = ResponseCodes.OBEX_HTTP_OK;
        try {
            outputStream = op.openOutputStream();
        } catch (IOException e) {
            Log.e(TAG, "open outputstrem failed" + e.toString());
            return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }

        int position = 0;
        long timestamp = 0;
        int outputBufferSize = op.getMaxPacketSize();
        if (V) Log.v(TAG, "outputBufferSize = " + outputBufferSize);
        while (position != folderlistStringLen) {
            if (sIsAborted) {
                ((ServerOperation)op).isAborted = true;
                sIsAborted = false;
                break;
            }
            if (V) timestamp = System.currentTimeMillis();
            int readLength = outputBufferSize;
            if (folderlistStringLen - position < outputBufferSize) {
                readLength = folderlistStringLen - position;
            }
            String subStr = folderlistString.substring(position, position + readLength);
            try {
                outputStream.write(subStr.getBytes(), 0, readLength);
            } catch (IOException e) {
                Log.e(TAG, "write outputstrem failed" + e.toString());
                pushResult = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                break;
            }
            if (V) {
                if (D) Log.d(TAG, "Sending folderlist String position = " + position + " readLength "
                        + readLength + " bytes took " + (System.currentTimeMillis() - timestamp)
                        + " ms");
            }
            position += readLength;
        }

        if (V) Log.v(TAG, "Send Data complete!");

        if (!closeStream(outputStream, op)) {
            pushResult = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
        if (V) Log.v(TAG, "pushBytes - result = " + pushResult);
        return pushResult;
    }
    /* Convert Month string to number strings for months */
    private final String convertMonthtoDigit(String Month) {
        if(Month.compareTo("Jan")== 0) {
            return "01";
        } else if (Month.compareTo("Feb")== 0){
            return "02";
        } else if (Month.compareTo("Mar")== 0){
            return "03";
        } else if (Month.compareTo("Apr")== 0){
            return "04";
        } else if (Month.compareTo("May")== 0){
            return "05";
        } else if (Month.compareTo("Jun")== 0){
            return "06";
        } else if (Month.compareTo("Jul")== 0){
            return "07";
        } else if (Month.compareTo("Aug")== 0){
            return "08";
        } else if (Month.compareTo("Sep")== 0){
            return "09";
        } else if (Month.compareTo("Oct")== 0){
            return "10";
        } else if (Month.compareTo("Nov")== 0){
            return "11";
        } else if (Month.compareTo("Dec")== 0){
            return "12";
        } else {
            return "00";
        }
    }

    /** Form and Send an XML format String to client for Folder listing */
    private final int sendFolderListingXml(final int type,Operation op,final File[] files) {
        if (V) Log.v(TAG, "sendFolderListingXml =" + files.length);

        StringBuilder result = new StringBuilder();
        result.append("<?xml version=\"1.0\"?>");
        result.append('\r');
        result.append('\n');
        result.append("<!DOCTYPE folder-listing SYSTEM \"obex-folder-listing.dtd\">");
        result.append('\r');
        result.append('\n');
        result.append("<folder-listing version=\"1.0\">");
        result.append('\r');
        result.append('\n');

        for(int i =0; i < files.length; i++){
            if(files[i].isDirectory()) {
                String dirperm = "";
                if(files[i].canRead() && files[i].canWrite()) {
                    dirperm = "RW";
                } else if(files[i].canRead()) {
                    dirperm = "R";
                } else if(files[i].canWrite()) {
                    dirperm = "W";
                }
                Date date = new Date(files[i].lastModified());

                StringBuffer xmldateformat = new StringBuffer(date.toString().substring(30,34));
                xmldateformat.append(convertMonthtoDigit(date.toString().substring(4,7)));
                xmldateformat.append(date.toString().substring(8,10));
                xmldateformat.append("T");
                xmldateformat.append(date.toString().substring(11,13));
                xmldateformat.append(date.toString().substring(14,16));
                xmldateformat.append("00Z");

                if (D) Log.d(TAG,"<folder name = " + files[i].getName()+ " size = "
                                + files[i].length() + "modified = " + date.toString()
                                + "xmldateformat.toString() = " + xmldateformat.toString());
                result.append("<folder name=\"" + files[i].getName()+ "\"" + " size=\"" +
                    files[i].length() + "\"" + " user-perm=\"" + dirperm + "\"" +
                    " modified=\"" + xmldateformat.toString()  + "\"" + "/>");
                result.append('\r');
                result.append('\n');
            }
            else {
                String userperm = "";
                if(files[i].canRead() && files[i].canWrite()) {
                    userperm = "RW";
                } else if(files[i].canRead()) {
                    userperm = "R";
                } else if(files[i].canWrite()) {
                    userperm = "W";
                }

                Date date = new Date(files[i].lastModified());
                /*First put in the Year into String buffer */
                StringBuffer xmldateformat = new StringBuffer(date.toString().substring(30,34));

                xmldateformat.append(convertMonthtoDigit(date.toString().substring(4,7)));
                xmldateformat.append(date.toString().substring(8,10));
                xmldateformat.append("T");
                xmldateformat.append(date.toString().substring(11,13));
                xmldateformat.append(date.toString().substring(14,16));
                xmldateformat.append("00Z");

                if (D) Log.d(TAG,"<file name = " + files[i].getName() + "size = " +
                        files[i].length() + "Append user-perm = "
                        + userperm + "Date in string format = " + date.toString()
                        + "files[i].modifieddate = " + xmldateformat.toString() );
                result.append("<file name=\"" + files[i].getName()+ "\"" + " size=\"" +
                    files[i].length() + "\"" + " user-perm=\"" + userperm + "\"" + " modified=\""
                    + xmldateformat.toString()  + "\"" + "/>");
                result.append('\r');
                result.append('\n');
            }
        }
        result.append("</folder-listing>");
        result.append('\r');
        result.append('\n');
        if (D) Log.d(TAG, "sendFolderListingXml -");
        return pushBytes(op, result.toString());
    }
    /* Close the output stream */
    public static boolean closeStream(final OutputStream out, final Operation op) {
        boolean returnvalue = true;
        if (D) Log.d(TAG, "closeoutStream +");
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

        if (D) Log.d(TAG, "closeoutStream -");
        return returnvalue;
    }
    /* Close the input stream */
    public static boolean closeStream(final InputStream in, final Operation op) {
        boolean returnvalue = true;
        if (D) Log.d(TAG, "closeinStream +");

        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "inputStream close failed" + e.toString());
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

        if (D) Log.d(TAG, "closeinStream -");

        return returnvalue;
    }
};
