/*
* Copyright (C) 2013 Samsung System LSI
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
package com.android.bluetooth.map;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

import android.os.Environment;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

import com.android.bluetooth.map.BluetoothMapUtils.TYPE;

public abstract class BluetoothMapbMessage {

    protected static String TAG = "BluetoothMapbMessage";
    protected static final boolean D = BluetoothMapService.DEBUG;
    protected static final boolean V = Log.isLoggable(BluetoothMapService.LOG_TAG, Log.VERBOSE) ? true : false;
    private static final String VERSION = "VERSION:1.0";

    public static int INVALID_VALUE = -1;

    protected int appParamCharset = BluetoothMapAppParams.INVALID_VALUE_PARAMETER;

    // TODO: Reevaluate if strings are the best types for the members.

    /* BMSG attributes */
    private String status = null; // READ/UNREAD
    protected TYPE type = null;   // SMS/MMS/EMAIL

    private String folder = null;

    /* BBODY attributes */
    private long partId = INVALID_VALUE;
    protected String encoding = null;
    protected String charset = null;
    private String language = null;

    private int bMsgLength = INVALID_VALUE;

    private ArrayList<vCard> originator = null;
    private ArrayList<vCard> recipient = null;


    public static class vCard {
        /* VCARD attributes */
        private String version;
        private String name = null;
        private String formattedName = null;
        private String[] phoneNumbers = {};
        private String[] emailAddresses = {};
        private int envLevel = 0;

        /**
         * Construct a version 3.0 vCard
         * @param name Structured
         * @param formattedName Formatted name
         * @param phoneNumbers a String[] of phone numbers
         * @param emailAddresses a String[] of email addresses
         * @param the bmessage envelope level (0 is the top/most outer level)
         */
        public vCard(String name, String formattedName, String[] phoneNumbers,
                String[] emailAddresses, int envLevel) {
            this.envLevel = envLevel;
            this.version = "3.0";
            this.name = name != null ? name : "";
            this.formattedName = formattedName != null ? formattedName : "";
            setPhoneNumbers(phoneNumbers);
            if (emailAddresses != null)
                this.emailAddresses = emailAddresses;
        }

        /**
         * Construct a version 2.1 vCard
         * @param name Structured name
         * @param phoneNumbers a String[] of phone numbers
         * @param emailAddresses a String[] of email addresses
         * @param the bmessage envelope level (0 is the top/most outer level)
         */
        public vCard(String name, String[] phoneNumbers,
                String[] emailAddresses, int envLevel) {
            this.envLevel = envLevel;
            this.version = "2.1";
            this.name = name != null ? name : "";
            setPhoneNumbers(phoneNumbers);
            if (emailAddresses != null)
                this.emailAddresses = emailAddresses;
        }

        /**
         * Construct a version 3.0 vCard
         * @param name Structured name
         * @param formattedName Formatted name
         * @param phoneNumbers a String[] of phone numbers
         * @param emailAddresses a String[] of email addresses
         */
        public vCard(String name, String formattedName, String[] phoneNumbers, String[] emailAddresses) {
            this.version = "3.0";
            this.name = name != null ? name : "";
            this.formattedName = formattedName != null ? formattedName : "";
            setPhoneNumbers(phoneNumbers);
            if (emailAddresses != null)
                this.emailAddresses = emailAddresses;
        }

        /**
         * Construct a version 2.1 vCard
         * @param name Structured Name
         * @param phoneNumbers a String[] of phone numbers
         * @param emailAddresses a String[] of email addresses
         */
        public vCard(String name, String[] phoneNumbers, String[] emailAddresses) {
            this.version = "2.1";
            this.name = name != null ? name : "";
            setPhoneNumbers(phoneNumbers);
            if (emailAddresses != null)
                this.emailAddresses = emailAddresses;
        }

        private void setPhoneNumbers(String[] numbers) {
            if(numbers != null && numbers.length > 0)
            {
                phoneNumbers = new String[numbers.length];
                for(int i = 0, n = numbers.length; i < n; i++){
                    phoneNumbers[i] = PhoneNumberUtils.extractNetworkPortion(numbers[i]);
                }
            }
        }

        public String getFirstPhoneNumber() {
            if(phoneNumbers.length > 0) {
                return phoneNumbers[0];
            } else
                throw new IllegalArgumentException("No Phone number");
        }
        public String[] getEmailAddresses() {
            if(emailAddresses.length > 0) {
                return emailAddresses;
            } else
                throw new IllegalArgumentException("No Recipient Email Address");
        }

        public int getEnvLevel() {
            return envLevel;
        }

        public void encode(StringBuilder sb) throws UnsupportedEncodingException
        {
            sb.append("BEGIN:VCARD").append("\r\n");
            sb.append("VERSION:").append(version).append("\r\n");
            if(version.equals("3.0") && formattedName != null)
            {
                sb.append("FN:").append(new String(formattedName.getBytes("UTF-8"),"UTF-8")).append("\r\n");
            }
            if (name != null)
                sb.append("N:").append(new String(name.getBytes("UTF-8"),"UTF-8")).append("\r\n");
            for(String phoneNumber : phoneNumbers)
            {
                sb.append("TEL:").append(phoneNumber).append("\r\n");
            }
            for(String emailAddress : emailAddresses)
            {
                sb.append("EMAIL:").append(new String(emailAddress.getBytes("UTF-8"),"UTF-8")).append("\r\n");
            }
            sb.append("END:VCARD").append("\r\n");
        }

        /**
         * Parse a vCard from a BMgsReader, where a line containing "BEGIN:VCARD" have just been read.
         * @param reader
         * @param originator
         * @return
         */
        public static vCard parseVcard(BMsgReader reader, int envLevel) {
            String formattedName = null;
            String name = null;
            ArrayList<String> phoneNumbers = null;
            ArrayList<String> emailAddresses = null;
            String[] parts;
            String line = reader.getLineEnforce();

            while(!line.contains("END:VCARD")) {
                line = line.trim();
                if(line.startsWith("N:")){
                    parts = line.split("[^\\\\]:"); // Split on "un-escaped" ':'
                    if(parts.length == 2) {
                        name = parts[1];
                    } else
                        name = "";
                }
                else if(line.startsWith("FN:")){
                    parts = line.split("[^\\\\]:"); // Split on "un-escaped" ':'
                    if(parts.length == 2) {
                        formattedName = parts[1];
                    } else
                        formattedName = "";
                }
                else if(line.startsWith("TEL:")){
                    parts = line.split("[^\\\\]:"); // Split on "un-escaped" ':'
                    if(parts.length == 2) {
                        String[] subParts = parts[1].split("[^\\\\];");
                        if(phoneNumbers == null)
                            phoneNumbers = new ArrayList<String>(1);
                        phoneNumbers.add(subParts[subParts.length-1]); // only keep actual phone number
                    } else {}
                        // Empty phone number - ignore
                }
                else if(line.startsWith("EMAIL:")){
                    line = line.replace("&lt;", "<").replace("&gt;", ">");
                    parts = line.split("[^\\\\]:"); // Split on "un-escaped" :
                    if(parts.length == 2) {
                        String[] subParts = parts[1].split("[^\\\\];");
                        if(emailAddresses == null)
                            emailAddresses = new ArrayList<String>(1);
                        emailAddresses.add(subParts[subParts.length-1]); // only keep actual email address
                    } else {}
                        // Empty email address entry - ignore
                }
                line = reader.getLineEnforce();
            }
            return new vCard(name, formattedName,
                    phoneNumbers == null? null : phoneNumbers.toArray(new String[phoneNumbers.size()]),
                    emailAddresses == null ? null : emailAddresses.toArray(new String[emailAddresses.size()]),
                    envLevel);
        }
    };

    private static class BMsgReader {
        InputStream mInStream;
        public BMsgReader(InputStream is)
        {
            this.mInStream = is;
        }

        private byte[] getLineTerminatorAsBytes() {
            int readByte;

            /* Donot skip Empty Line.
             */

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                while ((readByte = mInStream.read()) != -1) {
                    if (readByte == '\r') {
                        if ((readByte = mInStream.read()) != -1 && readByte == '\n') {
                            if(output.size() == 0){
                               Log.v(TAG,"outputsize 0");
                               output.write('\r');
                               output.write('\n');
                            }
                            break;
                        } else {
                            output.write('\r');
                        }
                    }
                    output.write(readByte);
                }
            } catch (IOException e) {
                Log.w(TAG, e);
                return null;
            }
            return output.toByteArray();
        }

        private byte[] getLineAsBytes() {
            int readByte;

            /* TODO: Actually the vCard spec. allows to break lines by using a newLine
             * followed by a white space character(space or tab). Not sure this is a good idea to implement
             * as the Bluetooth MAP spec. illustrates vCards using tab alignment, hence actually
             * showing an invalid vCard format...
             * If we read such a folded line, the folded part will be skipped in the parser
             */

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                while ((readByte = mInStream.read()) != -1) {
                    if (readByte == '\r') {
                        if ((readByte = mInStream.read()) != -1 && readByte == '\n') {
                            if(output.size() == 0)
                                continue; /* Skip empty lines */
                            else
                                break;
                        } else {
                            output.write('\r');
                        }
                    } else if (readByte == '\n' && output.size() == 0) {
                        /* Empty line - skip */
                        continue;
                    }

                    output.write(readByte);
                }
            } catch (IOException e) {
                Log.w(TAG, e);
                return null;
            }
            return output.toByteArray();
        }

        /**
         * Read a line of text from the BMessage including empty lines.
         * @return the next line of text, or null at end of file, or if UTF-8 is not supported.
         */
        public String getLineTerminator() {
            try {
                byte[] line = getLineTerminatorAsBytes();
                if (line == null) {
                    return null;
                } else if (line.length == 0){
                    return null;
                } else {
                    return new String(line, "UTF-8");
                }
            } catch (UnsupportedEncodingException e) {
                Log.w(TAG, e);
                return null;
            }
        }
        /**
         * Read a line of text from the BMessage.
         * @return the next line of text, or null at end of file, or if UTF-8 is not supported.
         */
        public String getLine() {
            try {
                byte[] line = getLineAsBytes();
                if (line == null) {
                   return null;
                } else if (line.length == 0) {
                   return null;
                } else {
                   return new String(line, "UTF-8");
                }
            } catch (UnsupportedEncodingException e) {
                Log.w(TAG, e);
                return null;
            }
        }

        /**
         * same as getLine(), but throws an exception, if we run out of lines.
         * Use this function when ever more lines are needed for the bMessage to be complete.
         * @return the next line
         */
        public String getLineEnforce() {
        String line = getLine();
        if (line == null)
            throw new IllegalArgumentException("Bmessage too short");

        return line;
        }


        /**
         * Reads a line from the InputStream, and examines if the subString
         * matches the line read.
         * @param subString
         * The string to match against the line.
         * @throws IllegalArgumentException
         * If the expected substring is not found.
         *
         */
        public void expect(String subString) throws IllegalArgumentException{
            String line = getLine();
            if(line == null || subString == null){
                throw new IllegalArgumentException("Line or substring is null");
            }else if(!line.toUpperCase().contains(subString.toUpperCase()))
                throw new IllegalArgumentException("Expected \"" + subString + "\" in: \"" + line + "\"");
        }

        /**
         * Same as expect(String), but with two strings.
         * @param subString
         * @param subString2
         * @throws IllegalArgumentException
         * If one or all of the strings are not found.
         */
        public void expect(String subString, String subString2) throws IllegalArgumentException{
            String line = getLine();
            if(line == null || subString == null) {
                throw new IllegalArgumentException("Line or substring is null");
            } else if(!line.toUpperCase().contains(subString.toUpperCase()))
                throw new IllegalArgumentException("Expected \"" + subString + "\" in: \"" + line + "\"");
            if(line == null || subString2 == null) {
                throw new IllegalArgumentException("Line or substring is null");
            } else if(!line.toUpperCase().contains(subString2.toUpperCase()))
                throw new IllegalArgumentException("Expected \"" + subString + "\" in: \"" + line + "\"");
        }

        /**
         * Read a part of the bMessage as raw data.
         * @param length the number of bytes to read
         * @return the byte[] containing the number of bytes or null if an error occurs or EOF is reached
         * before length bytes have been read.
         */
        public byte[] getDataBytes(int length) {
            byte[] data = new byte[length];
            try {
                int bytesRead;
                int offset=0;
                while ((bytesRead = mInStream.read(data, offset, length-offset)) != (length - offset)) {
                    if(bytesRead == -1)
                        return null;
                    offset += bytesRead;
                }
            } catch (IOException e) {
                Log.w(TAG, e);
                return null;
            }
            return data;
        }

        public String getStringTerminator(String terminator) {
            StringBuilder dataStr= new StringBuilder();
            String lineCur = getLineTerminator();
           while( lineCur != null && (!lineCur.equals(terminator))) {
                dataStr.append(lineCur);
                if(! lineCur.equals("\r\n")) {
                   dataStr.append("\r\n");
                }
                lineCur = getLineTerminator();
           }
           return dataStr.toString();
        }
    };

    public BluetoothMapbMessage(){

    }

    public static BluetoothMapbMessage parse(InputStream bMsgStream, int appParamCharset) throws IllegalArgumentException{
        BMsgReader reader;
        String line = "";
        BluetoothMapbMessage newBMsg = null;
        boolean status = false;
        boolean statusFound = false;
        TYPE type = null;
        String folder = null;

        /* This section is used for debug. It will write the incoming message to a file on the SD-card,
         * hence should only be used for test/debug.
         * If an error occurs, it will result in a OBEX_HTTP_PRECON_FAILED to be send to the client,
         * even though the message might be formatted correctly, hence only enable this code for test. */
        if(V) {
            /* Read the entire stream into a file on the SD card*/
            File sdCard = Environment.getExternalStorageDirectory();
            File dir = new File (sdCard.getAbsolutePath() + "/bluetooth/log/");
            dir.mkdirs();
            File file = new File(dir, "receivedBMessage.txt");
            FileOutputStream outStream = null;
            boolean failed = false;
            int writtenLen = 0;

            try {
                outStream = new FileOutputStream(file, false); /* overwrite if it does already exist */

                byte[] buffer = new byte[4*1024];
                int len = 0;
                while ((len = bMsgStream.read(buffer)) > 0) {
                    outStream.write(buffer, 0, len);
                    writtenLen += len;
                }
            } catch (FileNotFoundException e) {
                Log.e(TAG,"Unable to create output stream",e);
            } catch (IOException e) {
                Log.e(TAG,"Failed to copy the received message",e);
                if(writtenLen != 0)
                    failed = true; /* We failed to write the complete file, hence the received message is lost... */
            } finally {
                if(outStream != null)
                    try {
                        outStream.close();
                    } catch (IOException e) {
                    }
            }

            /* Return if we corrupted the incoming bMessage. */
            if(failed) {
                throw new IllegalArgumentException(); /* terminate this function with an error. */
            }

            if (outStream == null) {
                /* We failed to create the the log-file, just continue using the original bMsgStream. */
            } else {
                /* overwrite the bMsgStream using the file written to the SD-Card */
                try {
                    bMsgStream.close();
                } catch (IOException e) {
                    /* Ignore if we cannot close the stream. */
                }
                /* Open the file and overwrite bMsgStream to read from the file */
                try {
                    bMsgStream = new FileInputStream(file);
                } catch (FileNotFoundException e) {
                    Log.e(TAG,"Failed to open the bMessage file", e);
                    throw new IllegalArgumentException(); /* terminate this function with an error. */
                }
            }
            Log.i(TAG, "The incoming bMessage have been dumped to " + file.getAbsolutePath());
        } /* End of if(V) log-section */

        reader = new BMsgReader(bMsgStream);
        reader.expect("BEGIN:BMSG");
        reader.expect("VERSION","1.0");

        line = reader.getLineEnforce();
        // Parse the properties - which end with either a VCARD or a BENV
        while(!line.contains("BEGIN:VCARD") && !line.contains("BEGIN:BENV")) {
            if(line.contains("STATUS")){
                String arg[] = line.split(":");
                if (arg != null && arg.length == 2) {
                    if (arg[1].trim().equals("READ")) {
                        status = true;
                    } else if (arg[1].trim().equals("UNREAD")) {
                        status =false;
                    } else {
                        throw new IllegalArgumentException("Wrong value in 'STATUS': " + arg[1]);
                    }
                } else {
                    throw new IllegalArgumentException("Missing value for 'STATUS': " + line);
                }
            }
            if(line.contains("TYPE")) {
                String arg[] = line.split(":");
                if (arg != null && arg.length == 2) {
                    String value = arg[1].trim();
                    type = TYPE.valueOf(value); // Will throw IllegalArgumentException if value is wrong
                    if(appParamCharset == BluetoothMapAppParams.CHARSET_NATIVE
                            && type != TYPE.SMS_CDMA && type != TYPE.SMS_GSM) {
                        throw new IllegalArgumentException("Native appParamsCharset only supported for SMS");
                    }
                    switch(type) {
                    case SMS_CDMA:
                    case SMS_GSM:
                        newBMsg = new BluetoothMapbMessageSms();
                        break;
                    case MMS:
                    case EMAIL:
                        newBMsg = new BluetoothMapbMessageMmsEmail();
                        break;
                    default:
                        break;
                    }
                } else {
                    throw new IllegalArgumentException("Missing value for 'TYPE':" + line);
                }
            }
            if(line.contains("FOLDER")) {
                String[] arg = line.split(":");
                if (arg != null && arg.length == 2) {
                    folder = arg[1].trim();
                }
                // This can be empty for push message - hence ignore if there is no value
            }
            line = reader.getLineEnforce();
        }
        if(newBMsg == null)
            throw new IllegalArgumentException("Missing bMessage TYPE: - unable to parse body-content");
        newBMsg.setType(type);
        newBMsg.appParamCharset = appParamCharset;
        if(folder != null)
            newBMsg.setCompleteFolder(folder);
        if(statusFound)
            newBMsg.setStatus(status);

        // Now check for originator VCARDs
        while(line.contains("BEGIN:VCARD")){
            if(D) Log.d(TAG,"Decoding vCard");
            newBMsg.addOriginator(vCard.parseVcard(reader,0));
            line = reader.getLineEnforce();
        }
        if(line.contains("BEGIN:BENV")) {
            newBMsg.parseEnvelope(reader, 0);
        } else
            throw new IllegalArgumentException("Bmessage has no BEGIN:BENV - line:" + line);

        /* TODO: Do we need to validate the END:* tags? They are only needed if someone puts additional info
         *        below the END:MSG - in which case we don't handle it.
         */

        try {
            bMsgStream.close();
        } catch (IOException e) {
            /* Ignore if we cannot close the stream. */
        }

        return newBMsg;
    }

    private void parseEnvelope(BMsgReader reader, int level) {
        String line;
        line = reader.getLineEnforce();
        if(D) Log.d(TAG,"Decoding envelope level " + level);

       while(line.contains("BEGIN:VCARD")){
           if(D) Log.d(TAG,"Decoding recipient vCard level " + level);
            if(recipient == null)
                recipient = new ArrayList<vCard>(1);
            recipient.add(vCard.parseVcard(reader, level));
            line = reader.getLineEnforce();
        }
        if(line.contains("BEGIN:BENV")) {
            if(D) Log.d(TAG,"Decoding nested envelope");
            parseEnvelope(reader, ++level); // Nested BENV
        }
        if(line.contains("BEGIN:BBODY")){
            if(D) Log.d(TAG,"Decoding bbody");

            if(type == TYPE.EMAIL){
               //TODO: Support Attachments also.
               parseBodyEmail(reader.getStringTerminator("END:BBODY"));
            } else
            parseBody(reader);
        }
    }

    private void parseBody(BMsgReader reader) {
        String line;
        line = reader.getLineEnforce();
        while(!line.contains("END:")) {
            if(line.contains("PARTID:")) {
                String arg[] = line.split(":");
                if (arg != null && arg.length == 2) {
                    try {
                    partId = Long.parseLong(arg[1].trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Wrong value in 'PARTID': " + arg[1]);
                    }
                } else {
                    throw new IllegalArgumentException("Missing value for 'PARTID': " + line);
                }
            }
            else if(line.contains("ENCODING:")) {
                String arg[] = line.split(":");
                if (arg != null && arg.length == 2) {
                    encoding = arg[1].trim(); // TODO: Validate ?
                } else {
                    throw new IllegalArgumentException("Missing value for 'ENCODING': " + line);
                }
            }
            else if(line.contains("CHARSET:")) {
                String arg[] = line.split(":");
                if (arg != null && arg.length == 2) {
                    charset = arg[1].trim(); // TODO: Validate ?
                } else {
                    throw new IllegalArgumentException("Missing value for 'CHARSET': " + line);
                }
            }
            else if(line.contains("LANGUAGE:")) {
                String arg[] = line.split(":");
                if (arg != null && arg.length == 2) {
                    language = arg[1].trim(); // TODO: Validate ?
                } else {
                    throw new IllegalArgumentException("Missing value for 'LANGUAGE': " + line);
                }
            }
            else if(line.contains("LENGTH:")) {
                String arg[] = line.split(":");
                if (arg != null && arg.length == 2) {
                    try {
                        bMsgLength = Integer.parseInt(arg[1].trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Wrong value in 'LENGTH': " + arg[1]);
                    }
                } else {
                    throw new IllegalArgumentException("Missing value for 'LENGTH': " + line);
                }
            }
            else if(line.contains("BEGIN:MSG")) {
                if(bMsgLength == INVALID_VALUE)
                    throw new IllegalArgumentException("Missing value for 'LENGTH'. Unable to read remaining part of the message");
                // For SMS: Encoding of MSG is always UTF-8 compliant, regardless of any properties, since PDUs are encodes as hex-strings
                /* PTS has a bug regarding the message length, and sets it 2 bytes too short, hence
                 * using the length field to determine the amount of data to read, might not be the
                 * best solution.
                 * Since errata ???(bluetooth.org is down at the moment) introduced escaping of END:MSG
                 * in the actual message content, it is now safe to use the END:MSG tag as terminator,
                 * and simply ignore the length field.*/
                //byte[] rawData = reader.getDataBytes(bMsgLength - (line.getBytes().length + 2)); // 2 added to compensate for the removed \r\n
                String data = reader.getStringTerminator("END:MSG");
                    if(V) {
                        Log.v(TAG,"MsgLength: " + bMsgLength);
                    Log.v(TAG,"data.getBytes().length: " + data.getBytes().length);
                        String debug = line.replaceAll("\\n", "<LF>\n");
                        debug = debug.replaceAll("\\r", "<CR>");
                        Log.v(TAG,"The line: \"" + debug + "\"");
                        debug = data.replaceAll("\\n", "<LF>\n");
                        debug = debug.replaceAll("\\r", "<CR>");
                    Log.v(TAG,"The msgString: \"" + data + "\"");
                }
                /* Decoding of MSG:
                 * 1) split on "\r\nEND:MSG\r\n"
                 * 2) delete "BEGIN:MSG\r\n" for each msg
                 * 3) replace any occurrence of "\END:MSG" with "END:MSG"
                 * 4) based on charset from application properties either store as String[] or decode to raw PDUs
                 * */
                String messages[] = data.split("\r\nEND:MSG\r\n");
                parseMsgInit();
                for(int i = 0; i < messages.length; i++) {
                    messages[i] = messages[i].replaceFirst("^BEGIN:MSG\r\n", "");
                    messages[i] = messages[i].replaceAll("\r\n([/]*)/END\\:MSG", "\r\n$1END:MSG");
                    messages[i] = messages[i].trim();
                    parseMsgPart(messages[i]);
                }
            }
            line = reader.getLineEnforce();
        }
    }

    /**
     * Parse the 'message' part of <bmessage-body-content>"
     * @param msgPart
     */
    public abstract void parseMsgPart(String msgPart);
    /**
     * Set initial values before parsing - will be called is a message body is found
     * during parsing.
     */
    public abstract void parseMsgInit();

    public void parseBodyEmail (String msg){
    }
    public abstract byte[] encode() throws UnsupportedEncodingException;

    public void setStatus(boolean read) {
        if(read)
            this.status = "READ";
        else
            this.status = "UNREAD";
    }

    public void setType(TYPE type) {
        this.type = type;
    }

    /**
     * @return the type
     */
    public TYPE getType() {
        return type;
    }

    public void setCompleteFolder(String folder) {
        this.folder = folder;
    }

    public void setFolder(String folder) {
        this.folder = "telecom/msg/" + folder;
    }

    public String getFolder() {
        return folder;
    }


    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public ArrayList<vCard> getOriginators() {
        return originator;
    }

    public void addOriginator(vCard originator) {
        if(this.originator == null)
            this.originator = new ArrayList<vCard>();
        this.originator.add(originator);
    }

    /**
     * Add a version 3.0 vCard with a formatted name
     * @param name e.g. Bonde;Casper
     * @param formattedName e.g. "Casper Bonde"
     * @param phoneNumbers
     * @param emailAddresses
     */
    public void addOriginator(String name, String formattedName, String[] phoneNumbers, String[] emailAddresses) {
        if(originator == null)
            originator = new ArrayList<vCard>();
        originator.add(new vCard(name, formattedName, phoneNumbers, emailAddresses));
    }

    /** Add a version 2.1 vCard with only a name.
     *
     * @param name e.g. Bonde;Casper
     * @param phoneNumbers
     * @param emailAddresses
     */
    public void addOriginator(String name, String[] phoneNumbers, String[] emailAddresses) {
        if(originator == null)
            originator = new ArrayList<vCard>();
        originator.add(new vCard(name, phoneNumbers, emailAddresses));
    }

    public ArrayList<vCard> getRecipients() {
        return recipient;
    }

    public void setRecipient(vCard recipient) {
        if(this.recipient == null)
            this.recipient = new ArrayList<vCard>();
        this.recipient.add(recipient);
    }

    public void addRecipient(String name, String formattedName, String[] phoneNumbers, String[] emailAddresses) {
        if(recipient == null)
            recipient = new ArrayList<vCard>();
        recipient.add(new vCard(name, formattedName, phoneNumbers, emailAddresses));
    }

    public void addRecipient(String name, String[] phoneNumbers, String[] emailAddresses) {
        if(recipient == null)
            recipient = new ArrayList<vCard>();
        recipient.add(new vCard(name, phoneNumbers, emailAddresses));
    }

    /**
     * Convert a byte[] of data to a hex string representation, converting each nibble to the corresponding
     * hex char.
     * NOTE: There is not need to escape instances of "\r\nEND:MSG" in the binary data represented as a string
     *       as only the characters [0-9] and [a-f] is used.
     * @param pduData the byte-array of data.
     * @param scAddressData the byte-array of the encoded sc-Address.
     * @return the resulting string.
     */
    protected String encodeBinary(byte[] pduData, byte[] scAddressData) {
        StringBuilder out = new StringBuilder((pduData.length + scAddressData.length)*2);
        for(int i = 0; i < scAddressData.length; i++) {
            out.append(Integer.toString((scAddressData[i] >> 4) & 0x0f,16)); // MS-nibble first
            out.append(Integer.toString( scAddressData[i]       & 0x0f,16));
        }
        for(int i = 0; i < pduData.length; i++) {
            out.append(Integer.toString((pduData[i] >> 4) & 0x0f,16)); // MS-nibble first
            out.append(Integer.toString( pduData[i]       & 0x0f,16));
            /*out.append(Integer.toHexString(data[i]));*/ /* This is the same as above, but does not include the needed 0's
                                                           e.g. it converts the value 3 to "3" and not "03" */
        }
        return out.toString();
    }

    /**
     * Decodes a binary hex-string encoded UTF-8 string to the represented binary data set.
     * @param data The string representation of the data - must have an even number of characters.
     * @return the byte[] represented in the data.
     */
    protected byte[] decodeBinary(String data) {
        byte[] out = new byte[data.length()/2];
        String value;
        if(D) Log.d(TAG,"Decoding binary data: START:" + data + ":END");
        for(int i = 0, j = 0, n = out.length; i < n; i++)
        {
            value = data.substring(j++, ++j); // same as data.substring(2*i, 2*i+1+1) - substring() uses end-1 for last index
            out[i] = (byte)(Integer.valueOf(value, 16) & 0xff);
        }
        if(D) {
            StringBuilder sb = new StringBuilder(out.length);
            for(int i = 0, n = out.length; i < n; i++)
            {
                sb.append(String.format("%02X",out[i] & 0xff));
            }
            Log.d(TAG,"Decoded binary data: START:" + sb.toString() + ":END");
        }
        return out;
    }

    public byte[] encodeGeneric(ArrayList<byte[]> bodyFragments) throws UnsupportedEncodingException
    {
        StringBuilder sb = new StringBuilder(256);
        byte[] msgStart, msgEnd;
        sb.append("BEGIN:BMSG").append("\r\n");
        sb.append(VERSION).append("\r\n");
        sb.append("STATUS:").append(status).append("\r\n");
        sb.append("TYPE:").append(type.name()).append("\r\n");
        if(folder.length() > 512)
            sb.append("FOLDER:").append(folder.substring(folder.length()-512, folder.length())).append("\r\n");
        else
            sb.append("FOLDER:").append(folder).append("\r\n");
        if(originator != null){
            for(vCard element : originator)
                element.encode(sb);
        }
        /* TODO: Do we need the three levels of env? - e.g. for e-mail. - we do have a level in the
         *  vCards that could be used to determine the the levels of the envelope.
         */

        sb.append("BEGIN:BENV").append("\r\n");
        if(recipient != null){
            for(vCard element : recipient)
                element.encode(sb);
        }
        sb.append("BEGIN:BBODY").append("\r\n");
        if(encoding != null && encoding != "")
            sb.append("ENCODING:").append(encoding).append("\r\n");
        if(charset != null && charset != "")
            sb.append("CHARSET:").append(charset).append("\r\n");


        int length = 0;
        /* 22 is the length of the 'BEGIN:MSG' and 'END:MSG' + 3*CRLF */
        for (byte[] fragment : bodyFragments) {
            length += fragment.length + 22;
        }
        sb.append("LENGTH:").append(length).append("\r\n");

        // Extract the initial part of the bMessage string
        msgStart = sb.toString().getBytes("UTF-8");

        sb = new StringBuilder(31);
        sb.append("END:BBODY").append("\r\n");
        sb.append("END:BENV").append("\r\n");
        sb.append("END:BMSG").append("\r\n");

        msgEnd = sb.toString().getBytes("UTF-8");

        try {

            ByteArrayOutputStream stream = new ByteArrayOutputStream(msgStart.length + msgEnd.length + length);
            stream.write(msgStart);

            for (byte[] fragment : bodyFragments) {
                stream.write("BEGIN:MSG\r\n".getBytes("UTF-8"));
                stream.write(fragment);
                stream.write("\r\nEND:MSG\r\n".getBytes("UTF-8"));
            }
            stream.write(msgEnd);

            if(V) Log.v(TAG,stream.toString("UTF-8"));
            return stream.toByteArray();
        } catch (IOException e) {
            Log.w(TAG,e);
            return null;
        }
    }
}
