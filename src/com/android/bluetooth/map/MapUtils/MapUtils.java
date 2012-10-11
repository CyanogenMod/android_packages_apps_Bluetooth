/*
 * Copyright (c) 2010-2011, Code Aurora Forum. All rights reserved.
 * Copyright (c) 2011-2012, Code Aurora Forum. All rights reserved.
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
import com.android.vcard.VCardProperty;
import com.android.vcard.VCardInterpreter;
import com.android.vcard.VCardParser;
import com.android.vcard.VCardParser_V21;
import com.android.vcard.VCardParser_V30;
import com.android.vcard.exception.VCardException;
import com.android.vcard.exception.VCardVersionException;
import android.util.Log;
import android.util.Xml;
import java.io.UnsupportedEncodingException;

import com.android.bluetooth.map.BluetoothMasService;

import org.xmlpull.v1.XmlSerializer;
import com.android.internal.util.FastXmlSerializer;

import java.io.IOException;
import java.io.StringBufferInputStream;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;

import static com.android.vcard.VCardConstants.PROPERTY_EMAIL;
import static com.android.vcard.VCardConstants.PROPERTY_FN;
import static com.android.vcard.VCardConstants.PROPERTY_N;
import static com.android.vcard.VCardConstants.PROPERTY_TEL;
import static com.android.vcard.VCardConstants.VERSION_V21;
import static com.android.vcard.VCardConstants.VERSION_V30;

/**
 * MapUtils is a class of utility methods that provide routines for converting
 * data to either XML or bMessage formats. The class shall also support parsing
 * XML and bMessage formatted data.
 * <p>
 * The following methods are currently supported:
 * <p>
 * folderListingXML()
 *
 * @version 0.1
 *
 */
public class MapUtils {
    public static final String TAG = "MapUtils";
    public static final boolean V = BluetoothMasService.VERBOSE;
    private static final String CRLF = "\r\n";

    /**
     * folderListingXML
     *
     * This method takes a list of folder names and returns a String with the
     * XML version of the List
     *
     * @param list
     *            An array of strings where each element represents a folder
     *            name
     * @return This method returns either null or a String
     */
    public static String folderListingXML(List<String> list) {
        String str = "<?xml version=\"1.0\"?><!DOCTYPE folder-listing SYSTEM \"obex-folder-listing.dtd\"><folder-listing version=\"1.0\">";

        for (String s : list) {
            str += "<folder name=\"";
            str += s;
            str += "\"/>";
        }

        str += "</folder-listing>";

        return str;
    }

    /**
     * messageListingXML
     *
     * This method takes a list of message objects and returns a String with the
     * XML version of the List
     *
     * @param list
     *            An array of message objects where each element represents a
     *            message
     * @return This method returns either null or a String
     */
    public static String messageListingXML(List<MsgListingConsts> list) {
        XmlSerializer serializer = new FastXmlSerializer();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        OutputStreamWriter myOutputStreamWriter = null;
        try {
            myOutputStreamWriter = new OutputStreamWriter(outputStream, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "Failed to encode: charset=" + "UTF-8");
            return null;
        }
        try {
            String str1;
            String str2 = "<?xml version=\"1.0\"?>";
            serializer.setOutput(myOutputStreamWriter);
            serializer.startDocument("UTF-8", true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.text("\n");
            serializer.startTag(null, "MAP-msg-listing");
            serializer.attribute(null, "version", "1.0");
            for (MsgListingConsts msg : list) {
                serializer.startTag(null, "msg");

                serializer.attribute(null, "handle", ("" + msg.msg_handle));
                if (msg.sendSubject == true) {
                    if (msg.subject == null){
                        serializer.attribute(null, "subject", "");
                    } else {
                        serializer.attribute(null, "subject", msg.subject);
                    }

                }
                if (msg.datetime != null) {
                    serializer.attribute(null, "datetime", msg.datetime);
                }
                if (msg.sender_name != null) {
                    serializer.attribute(null, "sender_name", msg.sender_name);
                }

                if (msg.sender_addressing != null) {
                    serializer.attribute(null, "sender_addressing",
                            msg.sender_addressing);
                }

                if (msg.replyto_addressing != null) {
                    serializer.attribute(null, "replyto_addressing",
                            msg.replyto_addressing);
                }

                if (msg.recepient_name != null) {
                    serializer.attribute(null, "recipient_name",
                            msg.recepient_name);
                }
                if (msg.sendRecipient_addressing == true) {
                    if (msg.recepient_addressing != null) {
                        serializer.attribute(null, "recipient_addressing",
                                msg.recepient_addressing);
                    } else {
                        serializer.attribute(null, "recipient_addressing", "");
                    }
                }
                if (msg.type != null) {
                    serializer.attribute(null, "type", msg.type);
                }
                if (msg.size != 0) {
                    serializer.attribute(null, "size", ("" + msg.size));
                }

                if (msg.contains_text != null) {
                    serializer.attribute(null, "text", msg.contains_text);
                }

                if (msg.reception_status != null) {
                    serializer.attribute(null, "reception_status",
                            msg.reception_status);
                }

                if (msg.attachment_size != -1) {
                    serializer.attribute(null, "attachment_size",
                            ("" + Integer.toString(msg.attachment_size)));
                }

                if (msg.priority != null) {
                    serializer.attribute(null, "priority", msg.priority);
                }

                if (msg.read != null) {
                    serializer.attribute(null, "read", msg.read);
                }

                if (msg.sent != null) {
                    serializer.attribute(null, "sent", msg.sent);
                }

                if (msg.msg_protected != null) {
                    serializer.attribute(null, "protected", msg.msg_protected);
                }

                serializer.endTag(null, "msg");

            }
            serializer.endTag(null, "MAP-msg-listing");
            serializer.endDocument();
            try {
                str1 = outputStream.toString("UTF-8");
                if (V) Log.v(TAG, "Printing XML-Converted String: " + str1);
            int line1 = 0;
            line1 = str1.indexOf("\n");
            str2 += str1.substring(line1 + 1);
            if (list.size() > 0) {
                int indxHandle = str2.indexOf("msg handle");
                String str3 = "<" + str2.substring(indxHandle);
                str2 = str2.substring(0, (indxHandle - 1)) + str3;
            }
            return str2;
            } catch (UnsupportedEncodingException e) {
                Log.e(TAG, "Failed to encode: charset=" + "UTF-8");
                return null;
            }
        } catch (IllegalArgumentException e) {

            e.printStackTrace();
        } catch (IllegalStateException e) {

            e.printStackTrace();
        } catch (IOException e) {

            e.printStackTrace();
        }
        return null;
    }

    /**
     * msgListingGetHdrXML
     *
     * This method returns a String with the XML header
     *
     * @return This method returns a String
     */
    public static String msgListingGetHdrXML() {
        String str1 = "<MAP-msg-listing version = \"1.0\">\n";
        return str1;
    }

    /**
     * msgListingGetFooterXML
     *
     * This method returns a String with the XML footer
     *
     * @return This method returns a String
     */
    public static String msgListingGetFooterXML() {
        String str1 = "</MAP-msg-listing>\n";
        return str1;
    }

    /**
     * msgListingGetMsgsXML
     *
     * This method takes a list of message objects and returns a String with the
     * XML messages
     *
     * @param list
     *            An array of message objects where each element represents a
     *            message
     * @return This method returns either null or a String
     */
    public static String msgListingGetMsgsXML(List<MsgListingConsts> list) {
        XmlSerializer serializer = Xml.newSerializer();
        StringWriter writer = new StringWriter();
        try {
            String str1;
            serializer.setOutput(writer);
            serializer.startDocument("", false);
            serializer.text("\n");
            for (MsgListingConsts msg : list) {
                serializer.startTag("", "msg");
                serializer.attribute("", "handle", ("" + msg.msg_handle));
                if (msg.subject != null) {
                    serializer.attribute("", "subject", msg.subject);
                } else {

                }
                if (msg.datetime != null) {
                    serializer.attribute("", "datetime", msg.datetime);
                } else {

                }
                if (msg.sender_name != null) {
                    serializer.attribute("", "sender_name", msg.sender_name);
                } else {

                }

                if (msg.sender_addressing != null) {
                    serializer.attribute("", "sender_addressing",
                            msg.sender_addressing);
                } else {

                }
                if (msg.recepient_name != null) {
                    serializer.attribute("", "recipient_name",
                            msg.recepient_name);
                } else {

                }
                if (msg.recepient_addressing != null) {
                    serializer.attribute("", "recipient_addressing",
                            msg.recepient_addressing);
                } else {

                }
                if (msg.type != null) {
                    serializer.attribute("", "type", msg.type);
                } else {

                }
                if (msg.size != 0) {
                    serializer.attribute("", "size", ("" + msg.size));
                } else {

                }
                if (msg.attachment_size != -1) {
                    serializer.attribute("", "attachment_size",
                            ("" + Integer.toString(msg.attachment_size)));
                } else {

                }
                if (msg.contains_text != null) {
                    serializer.attribute("", "text", msg.contains_text);
                } else {

                }
                if (msg.priority != null) {
                    serializer.attribute("", "priority", msg.priority);
                } else {

                }
                if (msg.read != null) {
                    serializer.attribute("", "read", msg.read);
                } else {

                }
                if (msg.sent != null) {
                    serializer.attribute("", "sent", msg.sent);
                } else {

                }

                if (msg.replyto_addressing != null) {
                    serializer.attribute("", "replyto_addressing",
                            msg.replyto_addressing);
                } else {

                }

                serializer.endTag("", "msg");

            }
            serializer.endDocument();
            str1 = writer.toString();

            int line1 = 0;
            line1 = str1.indexOf("\n");
            if (line1 > 0) {
                return (str1.substring((line1)));
            } else {
                return str1;
            }

        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * mapEventReportXML
     *
     * This method takes a list of map event report object and returns a String
     * with the XML message
     *
     * @param type
     *            Report type (e.g. NewMessage)
     * @param handle
     *            handle created by caller
     * @param folder
     *            Path to folder to use
     * @param Oldfolder
     *            Path to old folder to use
     * @param msgType
     *            Type of message (SMS_GSM, SMS_CDMA)
     *
     * @return This method returns either null or a String
     */
    public static String mapEventReportXML(String type, String handle, String folder,
            String oldFolder, String msgType) {
        XmlSerializer serializer = Xml.newSerializer();
        StringWriter writer = new StringWriter();

        try {
            String str1;
            serializer.setOutput(writer);
            serializer.startDocument("", false);
            serializer.text("\n");
            serializer.startTag("", "MAP-event-report");
            serializer.attribute("", "version", "1.0");
            serializer.text("\n");

            serializer.startTag("", "event");
            if (type != null) {
                serializer.attribute("", "type", ("" + type));
            } else {

            }
            if (handle != null) {
                serializer.attribute("", "handle", ("" + handle));
            } else {

            }
            if (folder != null) {
                serializer.attribute("", "folder", ("" + folder));
            } else {

            }
            if (oldFolder != null) {
                serializer.attribute("", "old_folder", ("" + oldFolder));
            } else {

            }

            if (msgType != null) {
                serializer.attribute("", "msg_type", ("" + msgType));
            } else {

            }
            serializer.endTag("", "event");
            serializer.text("\n");
            serializer.endTag("", "MAP-event-report");
            serializer.endDocument();
            str1 = writer.toString();
            int line1 = 0;
            line1 = str1.indexOf("\n");
            if (line1 > 0) {
                int index = str1.indexOf("event type");
                String str2 = "<" + str1.substring(index);
                str1 = "<MAP-event-report version=\"1.0\">" + "\n" + str2;
                return str1;
            } else {
                return str1;
            }

        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * toBmessageSMS
     *
     * This method takes as input a list of BmessageConsts objects and creates a
     * String in the bMessage format.
     *
     * @param list
     *            An array of message objects where each element represents a
     *            message
     * @return This method returns either null or a String
     */
    public static String toBmessageSMS(BmessageConsts bmsg) {
        StringBuilder sb = new StringBuilder();

        try {
            sb.append("BEGIN:BMSG");
            sb.append("\r\n");
            if (bmsg.bmsg_version != null) {
                sb.append("VERSION:").append(bmsg.bmsg_version).append("\r\n");
            } else {

            }
            if (bmsg.status != null) {
                sb.append("STATUS:").append(bmsg.status).append("\r\n");
            } else {

            }
            if (bmsg.type != null) {
                sb.append("TYPE:").append(bmsg.type).append("\r\n");
            } else {

            }
            if (bmsg.folder != null) {
                sb.append("FOLDER:").append(bmsg.folder).append("\r\n");
            } else {

            }

            // Originator
            sb.append("BEGIN:VCARD").append("\r\n");

            if (bmsg.vcard_version != null) {
                sb.append("VERSION:").append(bmsg.vcard_version).append("\r\n");
            } else {

            }
            if (bmsg.originator_vcard_name != null) {
                sb.append("N:").append(bmsg.originator_vcard_name)
                        .append("\r\n");
            } else {

            }
            if (bmsg.originator_vcard_phone_number != null) {
                sb.append("TEL:").append(bmsg.originator_vcard_phone_number)
                        .append("\r\n");
            } else {

            }

            sb.append("END:VCARD").append("\r\n");
            // End Originator

            sb.append("BEGIN:BENV").append("\r\n");

            // Recipient
            sb.append("BEGIN:VCARD").append("\r\n");

            if (bmsg.vcard_version != null) {
                sb.append("VERSION:").append(bmsg.vcard_version).append("\r\n");
            } else {

            }
            if (bmsg.recipient_vcard_name != null) {
                sb.append("N:").append(bmsg.recipient_vcard_name)
                        .append("\r\n");
            } else {

            }
            if (bmsg.recipient_vcard_phone_number != null) {
                sb.append("TEL:").append(bmsg.recipient_vcard_phone_number)
                        .append("\r\n");
            } else {

            }
            sb.append("END:VCARD").append("\r\n");
            // End Recipient

            sb.append("BEGIN:BBODY").append("\r\n");

            if (bmsg.body_charset != null) {
                sb.append("CHARSET:").append(bmsg.body_charset)
                        .append("\r\n");
            } else {

            }

            if (bmsg.body_encoding != null) {
                sb.append("ENCODING:").append(bmsg.body_encoding)
                        .append("\r\n");
            } else {

            }
            if (bmsg.body_length != 0) {
                sb.append("LENGTH:").append(bmsg.body_length).append("\r\n");
            } else {

            }
            if (bmsg.body_msg != null) {
                sb.append("BEGIN:MSG\r\n");
                sb.append(bmsg.body_msg).append("\r\n");
                sb.append("END:MSG\r\n");

            } else {

            }

            sb.append("END:BBODY").append("\r\n");
            sb.append("END:BENV").append("\r\n");

            sb.append("END:BMSG");
            sb.append("\r\n");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return sb.toString();

    }

    /**
     * toBmessageMMS
     *
     * This method takes as input a list of BmessageConsts objects and creates a
     * String in the bMessage format.
     *
     * @param list
     *            An array of message objects where each element represents a
     *            message
     * @return This method returns either null or a String
     */
    public static String toBmessageMMS(BmessageConsts bmsg) {
        StringBuilder sb = new StringBuilder();

        try {
            sb.append("BEGIN:BMSG");
            sb.append("\r\n");
            if (bmsg.bmsg_version != null) {
                sb.append("VERSION:").append(bmsg.bmsg_version).append("\r\n");
            } else {

            }
            if (bmsg.status != null) {
                sb.append("STATUS:").append(bmsg.status).append("\r\n");
            } else {

            }
            if (bmsg.type != null) {
                sb.append("TYPE:").append(bmsg.type).append("\r\n");
            } else {

            }
            if (bmsg.folder != null) {
                sb.append("FOLDER:").append(bmsg.folder).append("\r\n");
            } else {

            }

            // Originator
            sb.append("BEGIN:VCARD").append("\r\n");

            if (bmsg.vcard_version != null) {
                sb.append("VERSION:").append(bmsg.vcard_version).append("\r\n");
            } else {

            }
            if (bmsg.originator_vcard_name != null) {
                sb.append("N:").append(bmsg.originator_vcard_name)
                        .append("\r\n");
            } else {

            }
            if (bmsg.originator_vcard_phone_number != null) {
                sb.append("TEL:").append(bmsg.originator_vcard_phone_number)
                        .append("\r\n");
            } else {

            }

            sb.append("END:VCARD").append("\r\n");
            // End Originator

            sb.append("BEGIN:BENV").append("\r\n");

            // Recipient
            sb.append("BEGIN:VCARD").append("\r\n");

            if (bmsg.vcard_version != null) {
                sb.append("VERSION:").append(bmsg.vcard_version).append("\r\n");
            } else {

            }
            if (bmsg.recipient_vcard_name != null) {
                sb.append("N:").append(bmsg.recipient_vcard_name)
                        .append("\r\n");
            } else {

            }
            if (bmsg.recipient_vcard_phone_number != null) {
                sb.append("TEL:").append(bmsg.recipient_vcard_phone_number)
                        .append("\r\n");
            } else {

            }
            sb.append("END:VCARD").append("\r\n");
            // End Recipient

            sb.append("BEGIN:BBODY").append("\r\n");

            sb.append("PARTID:26988").append("\r\n");

            if (bmsg.body_encoding != null) {
                sb.append("ENCODING:").append(bmsg.body_encoding)
                        .append("\r\n");
            } else {

            }
            sb.append("CHARSET:UTF-8").append("\r\n");
            sb.append("LANGUAGE:").append("\r\n");

            if (bmsg.body_length != 0) {
                sb.append("LENGTH:").append(bmsg.body_length).append("\r\n");
            } else {

            }
            if (bmsg.body_msg != null) {
                sb.append("BEGIN:MSG\r\n");
                sb.append(bmsg.body_msg).append("\r\n");
                sb.append("END:MSG\r\n");

            } else {

            }

            sb.append("END:BBODY").append("\r\n");
            sb.append("END:BENV").append("\r\n");

            sb.append("END:BMSG");
            sb.append("\r\n");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return sb.toString();
    }

    /**
     * toBmessageEmail
     *
     * This method takes as input a list of BmessageConsts objects and creates a
     * String in the bMessage format.
     *
     * @param list
     *            An array of message objects where each element represents a
     *            message
     * @return This method returns either null or a String
     */
    public static String toBmessageEmail(BmessageConsts bmsg) {
        StringBuilder sb = new StringBuilder();

        try {
            sb.append("BEGIN:BMSG");
            sb.append("\r\n");
            if (bmsg.bmsg_version != null) {
                sb.append("VERSION:").append(bmsg.bmsg_version).append("\r\n");
            } else {

            }
            if (bmsg.status != null) {
                sb.append("STATUS:").append(bmsg.status).append("\r\n");
            } else {

            }
            if (bmsg.type != null) {
                sb.append("TYPE:").append(bmsg.type).append("\r\n");
            } else {

            }
            if (bmsg.folder != null) {
                sb.append("FOLDER:").append(bmsg.folder).append("\r\n");
            } else {

            }

            // Originator
            sb.append("BEGIN:VCARD").append("\r\n");

            if (bmsg.vcard_version != null) {
                sb.append("VERSION:").append(bmsg.vcard_version).append("\r\n");
            } else {

            }
            if (bmsg.originator_vcard_name != null) {
                sb.append("N:").append(bmsg.originator_vcard_name)
                        .append("\r\n");
            } else {

            }
            sb.append("TEL:").append("\r\n");
            if (bmsg.originator_vcard_email != null) {
                sb.append("EMAIL:").append(bmsg.originator_vcard_email)
                        .append("\r\n");
            } else {

            }

            sb.append("END:VCARD").append("\r\n");
            // End Originator

            sb.append("BEGIN:BENV").append("\r\n");

            // Recipient
            sb.append("BEGIN:VCARD").append("\r\n");

            if (bmsg.vcard_version != null) {
                sb.append("VERSION:").append(bmsg.vcard_version).append("\r\n");
            } else {

            }
            if (bmsg.recipient_vcard_name != null) {
                sb.append("N:").append(bmsg.recipient_vcard_name)
                        .append("\r\n");
            } else {

            }
            if (bmsg.recipient_vcard_name != null) {
                sb.append("FN:").append(bmsg.recipient_vcard_name)
                        .append("\r\n");
            } else {

            }
            sb.append("TEL:").append("\r\n");
            if (bmsg.recipient_vcard_email != null) {
                sb.append("EMAIL:").append(bmsg.recipient_vcard_email)
                        .append("\r\n");
            } else {

            }
            sb.append("END:VCARD").append("\r\n");
            // End Recipient

            sb.append("BEGIN:BBODY").append("\r\n");

            if (bmsg.body_encoding != null) {
                sb.append("ENCODING:").append(bmsg.body_encoding)
                        .append("\r\n");
            } else {
                sb.append("ENCODING:8BIT").append("\r\n");
            }

            sb.append("CHARSET:UTF-8").append("\r\n");

            if (bmsg.body_length != 0) {
                sb.append("LENGTH:").append(bmsg.body_length).append("\r\n");
            } else {

            }
            if (bmsg.body_msg != null) {
                sb.append("BEGIN:MSG\r\n");
                sb.append(bmsg.body_msg).append("\r\n");
                sb.append("END:MSG\r\n");

            } else {

            }

            sb.append("END:BBODY").append("\r\n");
            sb.append("END:BENV").append("\r\n");

            sb.append("END:BMSG");
            sb.append("\r\n");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return sb.toString();

    }


    /**
     * fromBmessageSMS
     *
     * This method takes as input a String in the bMessage format. It parses the
     * String and loads a BmessageConsts object that is returned
     *
     * @param String
     *            - which is a bMessage formatted SMS message
     * @return This method returns a BmessageConsts object
     * @throws BadRequestException
     */
    public static BmessageConsts fromBmessageSMS(String bmsg) throws BadRequestException {
        BmessageConsts bMsgObj = new BmessageConsts();
        String vCard = fetchRecipientVcard(bmsg);

        RecipientVCard recipient = parseVCard(vCard);
        if (recipient.mTel.length() == 0) {
            throw new BadRequestException("No TEL in vCard");
        }
        bMsgObj.setRecipientVcard_phone_number(recipient.mTel);
        if (V) Log.v(TAG, "Tel: " + recipient.mTel);

        // Extract vCard Version
        bMsgObj.setVcard_version(recipient.mVersion);

        // Extract bMessage Version
        bMsgObj.setBmsg_version(fetchVersion(bmsg));

        // Extract Message Status
        bMsgObj.setStatus(fetchReadStatus(bmsg));

        // Extract Message Type
        bMsgObj.setType(fetchType(bmsg));

        // Extract Message Folder
        bMsgObj.setFolder(fetchFolder(bmsg));

        // Fetch Message Length
        bMsgObj.setBody_length(fetchBodyLength(bmsg));

        // Extract Message
        bMsgObj.setBody_msg(fetchBodyMsg(bmsg));

        // Extract Message encoding
        bMsgObj.setBody_encoding(fetchBodyEncoding(bmsg));

        return bMsgObj;
    }

    /**
     * fromBmessageMMS
     *
     * This method takes as input a String in the bMessage format. It parses the
     * String and loads a BmessageConsts object that is returned
     *
     * @param String
     *            - which is a bMessage formatted SMS message
     * @return This method returns a BmessageConsts object
     * @throws BadRequestException
     */
    public static BmessageConsts fromBmessageMMS(String bmsg) throws BadRequestException {
        BmessageConsts bMsgObj = new BmessageConsts();

        String phoneNumber = null;
        String vCard = fetchRecipientVcard(bmsg);
        if (V) Log.v(TAG, "vCard Info: " + vCard);

        RecipientVCard recipient = parseVCard(vCard);
        if (recipient.mEmail.length() > 0) {
            phoneNumber = recipient.mEmail;
        } else if (recipient.mTel.length() > 0) {
            phoneNumber = recipient.mTel;
        } else {
            throw new BadRequestException("No Email/Tel in vCard");
        }

        if (V) Log.v(TAG, "Email: " + recipient.mEmail);
        if (V) Log.v(TAG, "Tel: " + recipient.mTel);
        if (V) Log.v(TAG, "Recipeint address: " + phoneNumber);
        bMsgObj.setRecipientVcard_phone_number(phoneNumber);

        // Extract vCard Version
        bMsgObj.setVcard_version(recipient.mVersion);

        // Extract bMessage Version
        bMsgObj.setBmsg_version(fetchVersion(bmsg));

        // Extract Message Status
        bMsgObj.setStatus(fetchReadStatus(bmsg));

        // Extract Message Type
        bMsgObj.setType(fetchType(bmsg));

        // Extract Message Folder
        bMsgObj.setFolder(fetchFolder(bmsg));

        // Fetch Message Length
        bMsgObj.setBody_length(fetchBodyLength(bmsg));

        // Extract Message
        bMsgObj.setBody_msg(fetchBodyMsg(bmsg));

        // Extract Message encoding
        bMsgObj.setBody_encoding(fetchBodyEncoding(bmsg));

        return bMsgObj;
    }

    /**
     * fromBmessageEmail
     *
     * This method takes as input a String in the bMessage format. It parses the
     * String and loads a BmessageConsts object that is returned
     *
     * @param String
     *            - which is a bMessage formatted Email message
     * @return This method returns a BmessageConsts object
     */

    public static BmessageConsts fromBmessageEmail(Context context,
                        String bmsg, int mMasId) throws BadRequestException {
        BmessageConsts bMsgObj = new BmessageConsts();
        String vCard = fetchRecipientVcard(bmsg);
        if (V) Log.v(TAG, "vCard Info: " + vCard);

        RecipientVCard recipient = parseVCard(vCard);
        if (recipient.mEmail.length() == 0) {
            throw new BadRequestException("No Email in recipient vCard");
        }
        bMsgObj.setRecipientVcard_email(recipient.mEmail);
        if (V) Log.v(TAG, "Email: " + recipient.mEmail);

        String vcardOrig = fetchOriginatorVcardEmail(bmsg);
        RecipientVCard originator = parseVCard(vcardOrig);
        if (originator.mEmail.length() == 0) {
            long accountId = -1;
            accountId = EmailUtils.getAccountId(mMasId);
            if ((accountId != -1) && (context != null)) {
                originator.mEmail = EmailUtils.getEmailAccountIdEmail
                (context,EmailUtils.RECORD_ID + "=" + accountId);
                Log.v(TAG, "Orig Email inserted by MSE as: " + originator.mEmail);
                originator.mFormattedName = EmailUtils.getEmailAccountDisplayName
                (context,EmailUtils.RECORD_ID + "=" + accountId);
                Log.v(TAG, "Orig F-Name inserted by MSE as: " + originator.mFormattedName);
            }
            if (originator.mEmail.length() == 0) {
                throw new BadRequestException("No Email in originator vCard");
            }
        }
        bMsgObj.setOriginatorVcard_email(originator.mEmail);
        if (V) Log.v(TAG, "Orig Email: " + originator.mEmail);
        if (originator.mFormattedName.length() > 0) {
            if (V) Log.v(TAG, "Orig Formatted Name: " + originator.mFormattedName);
            bMsgObj.setOriginatorVcard_name(originator.mFormattedName);
        } else {
            if (V) Log.v(TAG, "Orig Name: " + originator.mName);
            bMsgObj.setOriginatorVcard_name(originator.mName);
        }

        if (V){
            Log.v(TAG, "Bmsg version:: "+fetchVersion(bmsg));
        }
        // Extract bMessage Version
        bMsgObj.setBmsg_version(fetchVersion(bmsg));

        if (V){
            Log.v(TAG, "Read status:: "+fetchReadStatus(bmsg));
        }
        // Extract Message Status
        bMsgObj.setStatus(fetchReadStatus(bmsg));

        if (V){
            Log.v(TAG, "Message Type:: "+fetchType(bmsg));
        }
        // Extract Message Type
        bMsgObj.setType(fetchType(bmsg));

        if (V){
            Log.v(TAG, "Folder:: "+fetchFolder(bmsg));
        }
        // Extract Message Folder
        bMsgObj.setFolder(fetchFolder(bmsg));

        if (V){
            Log.v(TAG, "body length:: "+fetchBodyLength(bmsg));
        }
        // Fetch Message Length
        bMsgObj.setBody_length(fetchBodyLength(bmsg));
        // Extract Message
        bMsgObj.setBody_msg(fetchBodyEmail(bmsg));

        if (V){
            Log.v(TAG, "Message encoding:: "+fetchBodyEncoding(bmsg));
        }
        // Extract Message encoding
        bMsgObj.setBody_encoding(fetchBodyEncoding(bmsg));

        // Extract Subject of the email
        bMsgObj.setSubject(fetchSubjectEmail(bmsg));

        return bMsgObj;
    }

    /**
     * fetchVcardEmail
     *
     * This method takes as input a vCard formatted String. It parses the String
     * and returns the vCard Email as a String
     *
     * @param
     * @return String This method returns a vCard Email String
     */
    private static String fetchVcardEmail(String vCard) {
        int pos = vCard.indexOf(("EMAIL:"));
        int beginVersionPos = pos + (("EMAIL:").length());
        if (V){
            Log.v(TAG,"Begin Version Position Email:: "+beginVersionPos);
        }
        int endVersionPos = vCard.indexOf("\n", beginVersionPos);
        if (V){
            Log.v(TAG,"End version Pos Email:: "+endVersionPos);
        }
        return vCard.substring(beginVersionPos, endVersionPos);
    }

    private static String fetchOriginatorEmail(String vCard) {
        int pos = vCard.indexOf(("From:"));
        int beginVersionPos = pos + (("From:").length());
        int endVersionPos = vCard.indexOf(CRLF, beginVersionPos);
        return vCard.substring(beginVersionPos, endVersionPos);
    }

    private static String fetchRecipientEmail(String vCard) {
        int pos = vCard.indexOf(("To:"));
        int beginVersionPos = pos + (("To:").length());
        int endVersionPos = vCard.indexOf(CRLF, beginVersionPos);
        return vCard.substring(beginVersionPos, endVersionPos);
    }

    private static String fetchRecepientVcardEmail(String bmsg) {
        // Find the position of the first vCard in the string
        int pos = bmsg.indexOf("BEGIN:BENV");
        if (V){
            Log.v(TAG, "vCard start position:: "+pos);
        }
        if (pos > 0) {
            if (V){
            	Log.v(TAG, "vCard start position greater than 0::");
            }
            int beginVcardPos = pos + ("\r\n".length());
            int endVcardPos = bmsg.indexOf("END:BENV");

            return bmsg.substring(beginVcardPos, endVcardPos);

        } else {
            return "";
        }
    }

    private static String fetchOriginatorVcardEmail(String bmsg) throws BadRequestException {
        // Find the position of the first vCard in the string
        int vCardBeginPos = bmsg.indexOf("BEGIN:VCARD");
        if (vCardBeginPos == -1) {
            // no vCard bad request
            throw new BadRequestException("No Vcard");
        }
        if (V) Log.v(TAG, "vCard start position: " + vCardBeginPos);
        int bEnvPos = bmsg.indexOf("BEGIN:BENV");
        if (vCardBeginPos > bEnvPos) {
            // the first vCard is not originator
            return "";
        }
        int vCardEndPos = bmsg.indexOf("END:VCARD", vCardBeginPos);
        if (vCardEndPos == -1) {
            // no END:VCARD bad request
            throw new BadRequestException("No END:VCARD");
        }
        vCardEndPos += "END:VCARD".length();

        return bmsg.substring(vCardBeginPos, vCardEndPos);
    }

    private static String fetchSubjectEmail(String body) {
        int pos = body.indexOf("Subject:");

        if (pos > 0) {
            int beginVersionPos = pos + (("Subject:").length());
            int endVersionPos = body.indexOf("\n", beginVersionPos);
            return body.substring(beginVersionPos, endVersionPos);
        } else {
            return "";
        }
    }

    /**
     * fetchVersion
     *
     * This method takes as input a String in the bMessage format. It parses the
     * String and returns the bMessage version string
     *
     * @param
     * @return String This method returns a Version String
     */
    private static String fetchVersion(String bmsg) {
        int pos = bmsg.indexOf("VERSION:");
        if (pos > 0) {

            int beginVersionPos = pos + (("VERSION:").length());
            int endVersionPos = bmsg.indexOf(CRLF, beginVersionPos);
            return bmsg.substring(beginVersionPos, endVersionPos);

        } else {
            return "";
        }
    }

    /**
     * fetchOriginatorVcard
     *
     * This method takes as input a String in the bMessage format. It parses the
     * String and returns the orginator vCard string
     *
     * @param
     * @return String This method returns a vCard String
     */
    private static String fetchOriginatorVcard(String bmsg) {
        // Find the position of the first vCard in the string
        int pos = bmsg.indexOf("\r\nBEGIN:VCARD");
        if (pos > 0) {
            int beginVcardPos = pos + ("\r\n".length());
            int endVcardPos = bmsg.indexOf("END:VCARD");

            return bmsg.substring(beginVcardPos, endVcardPos);

        } else {

            return null;

        }
    }

    /**
     * fetchRecipientVcard
     *
     * This method takes as input a String in the bMessage format. It parses the
     * String looking for the first envelope. Once it finds the envelop, it then
     * looks for the first vCard and returns it as a String
     *
     * @param
     * @return String This method returns a Vcard String
     */
    private static String fetchRecipientVcard(String bmsg) throws BadRequestException {
        // Locate BENV
        int locBENV = 0;
        int pos = 0;
        locBENV = bmsg.indexOf(CRLF + "BEGIN:BENV");
        pos = bmsg.indexOf(CRLF + "BEGIN:VCARD", locBENV);
        if (pos < 0) {
            // no vCard in BENV
            throw new BadRequestException("No vCard in BENV");
        }
        if (pos > 0) {
            int beginVcardPos = pos + CRLF.length();
            int endVcardPos = bmsg.indexOf("END:VCARD", pos);
            if (endVcardPos < 0) {
                // no END:VCARD in BENV
                throw new BadRequestException("No END:VCARD in BENV");
            }
            endVcardPos += "END:VCARD".length();
            return bmsg.substring(beginVcardPos, endVcardPos);

        } else {
            return "";
        }
    }

    /**
     * fetchReadStatus
     *
     * This method takes as input a String in the bMessage format. It parses the
     * String and returns the bMessage read status String
     *
     * @param
     * @return String This method returns a Read Status String
     */
    private static String fetchReadStatus(String bmsg) {
        int pos = bmsg.indexOf("STATUS:");
        if (pos > 0) {

            int beginStatusPos = pos + (("STATUS:").length());
            int endStatusPos = bmsg.indexOf(CRLF, beginStatusPos);
            return bmsg.substring(beginStatusPos, endStatusPos);

        } else {
            return "";
        }
    }

    /**
     * fetchType
     *
     * This method takes as input a String in the bMessage format. It parses the
     * String and returns the bMessage type String
     *
     * @param
     * @return String This method returns a message type String
     */
    public static String fetchType(String bmsg) {
        int pos = bmsg.indexOf("TYPE:");
        if (pos > 0) {
            int beginTypePos = pos + (("TYPE:").length());
            int endTypePos = bmsg.indexOf(CRLF, beginTypePos);

            return bmsg.substring(beginTypePos, endTypePos);

        } else {
            return null;
        }
    }

    /**
     * fetchFolder
     *
     * This method takes as input a String in the bMessage format. It parses the
     * String and returns the bMessage Folder path String
     *
     * @param
     * @return String This method returns a Folder path String
     */
    private static String fetchFolder(String bmsg) {
        int pos = bmsg.indexOf("FOLDER:");
        if (pos > 0) {
            int beginVersionPos = pos + (("FOLDER:").length());
            int endVersionPos = bmsg.indexOf(CRLF, beginVersionPos);

            return bmsg.substring(beginVersionPos, endVersionPos);

        } else {
            return null;
        }
    }

    /**
     * fetchBody
     *
     * This method takes as input a String in the bMessage format. It parses the
     * String and returns the bMessage Body as a String
     *
     * @param
     * @return String This method returns a Body String
     */
    @SuppressWarnings("unused")
    private static String fetchBody(String bmsg) {
        int pos = bmsg.indexOf("BEGIN:BBODY");
        if (pos > 0) {
            int beginVersionPos = pos + (("BEGIN:BBODY").length());
            int endVersionPos = bmsg.indexOf("END:BBODY", beginVersionPos);

            return bmsg.substring(beginVersionPos, endVersionPos);

        } else {
            return null;
        }

    }

    /**
     * fetchBodyPartID
     *
     * This method takes as input a String consisting of the body portion of the
     * bMessage. It parses the String and returns the bMessage Body Part ID as a
     * String
     *
     * @param
     * @return String This method returns a Body Part ID String
     */
    @SuppressWarnings("unused")
    private static String fetchBodyPartID(String body) {
        int pos = body.indexOf("PARTID:");
        if (pos > 0) {
            int beginVersionPos = pos + (("PARTID:").length());
            int endVersionPos = body.indexOf(CRLF, beginVersionPos);
            return body.substring(beginVersionPos, endVersionPos);

        } else {
            return null;
        }
    }

    /**
     * fetchCharset
     *
     * This method takes as input a String consisting of the body portion of the
     * bMessage. It parses the String and returns the bMessage Charset as a
     * String
     *
     * @param
     * @return String This method returns a Charset String
     */
    @SuppressWarnings("unused")
    private static String fetchCharset(String body) {
        int pos = body.indexOf("CHARSET:");
        if (pos > 0) {

            int beginVersionPos = pos + (("CHARSET:").length());
            int endVersionPos = body.indexOf(CRLF, beginVersionPos);

            return body.substring(beginVersionPos, endVersionPos);

        } else {

            return null;
        }
    }

    /**
     * fetchBodyEncoding
     *
     * This method takes as input a String consisting of the body portion of the
     * bMessage. It parses the String and returns the bMessage Body Encoding as
     * a String
     *
     * @param
     * @return String This method returns a Body Encoding String
     */
    private static String fetchBodyEncoding(String body) {
        int pos = body.indexOf("ENCODING:");
        if (pos > 0) {
            int beginVersionPos = pos + (("ENCODING:").length());
            int endVersionPos = body.indexOf(CRLF, beginVersionPos);
            return body.substring(beginVersionPos, endVersionPos);

        } else {

            return null;
        }
    }

    /**
     * fetchBodyLanguage
     *
     * This method takes as input a String consisting of the body portion of the
     * bMessage. It parses the String and returns the bMessage Body Language as
     * a String
     *
     * @param
     * @return String This method returns a Body Language String
     */
    @SuppressWarnings("unused")
    private static String fetchBodyLanguage(String body) {
        int pos = body.indexOf("LANGUAGE:");
        if (pos > 0) {
            int beginVersionPos = pos + (("LANGUAGE:").length());
            int endVersionPos = body.indexOf(CRLF, beginVersionPos);
            return body.substring(beginVersionPos, endVersionPos);

        } else {

            return null;
        }
    }

    /**
     * fetchBodyLength
     *
     * This method takes as input a String consisting of the body portion of the
     * bMessage. It parses the String and returns the bMessage Body Length as an
     * Integer
     *
     * @param
     * @return String This method returns a Body Length Integer
     */
    private static Integer fetchBodyLength(String body) {
        int pos = body.indexOf("LENGTH:");
        if (pos > 0) {
            int beginVersionPos = pos + (("LENGTH:").length());
            int endVersionPos = body.indexOf(CRLF, beginVersionPos);
            String bd = body.substring(beginVersionPos,
                       endVersionPos);
            Integer bl = Integer.valueOf(bd);
            return bl;

        } else {

            return null;
        }
    }

    /**
     * fetchBodyMsg
     *
     * This method takes as input a String consisting of the body portion of the
     * bMessage. It parses the String and returns the bMessage Body Message as a
     * String
     *
     * @param
     * @return String This method returns a Body Message String
     */
    private static String fetchBodyMsg(String body) {
        int pos = body.indexOf("BEGIN:MSG");
        if (pos > 0) {
            int beginVersionPos = pos
                    + (("BEGIN:MSG").length() + CRLF.length());
            int endVersionPos = (body.indexOf("END:MSG", beginVersionPos) - CRLF
                    .length());
            return body.substring(beginVersionPos, endVersionPos);
        } else {
            return "";
        }
    }

    private static String fetchBodyMsgEmail(String body) {
        if (V){
            Log.v(TAG, "bMessageEmail inside fetch body ::"+body);
        }
        int pos = body.indexOf("Content-Disposition:inline");
        if (pos > 0) {
            int beginVersionPos = pos
                    + (("Content-Disposition:inline").length() + CRLF.length());
            int endVersionPos = (body.indexOf("--RPI-Messaging", beginVersionPos) - CRLF
                    .length());

            return body.substring(beginVersionPos, endVersionPos);

        } else {

            return null;
        }
    }

    private static String fetchBoundary(String body) {
        int pos = body.indexOf("boundary=\"");
        if (pos > 0) {
            int beginVersionPos = pos + (("boundary=\"").length());
            int endVersionPos = body.indexOf("\"", beginVersionPos);
            return body.substring(beginVersionPos, endVersionPos);

        } else {

            return null;
        }
    }

    public static String fetchBodyEmail(String body) throws BadRequestException {
        if (V) Log.v(TAG, "inside fetch body Email :"+body);

        int beginVersionPos = -1;
        int rfc822Flag = 0;
        int mimeFlag = 0;
        int beginVersionPos1 = -1;
        String contentType;
        int pos1 = 0;
        String boundary = fetchBoundary(body);
        if(boundary != null && !boundary.equalsIgnoreCase("")){
            pos1 = body.indexOf("--"+boundary);
            mimeFlag = 1;
        }
        else{
            pos1 = body.indexOf("Date:");
            mimeFlag = 0;
        }
        int contentIndex = body.indexOf("Content-Type",pos1);
        if(contentIndex > 0){
            contentType = fetchContentType(body, boundary);
            if(contentType != null && contentType.trim().equalsIgnoreCase("message/rfc822")){
                rfc822Flag = 1;
            }
        }
        int pos = body.indexOf(CRLF, pos1) + CRLF.length();
        while (pos > 0) {
            if(body.startsWith(CRLF, pos)) {
                beginVersionPos = pos + CRLF.length();
                break;
            } else {
                final int next = body.indexOf(CRLF, pos);
                if (next == -1) {
                    // throw new BadRequestException("Ill-formatted bMessage, no empty line");
                    // PTS: Instead of throwing Exception, return MSG
                    int beginMsg = body.indexOf("BEGIN:MSG");
                    if (beginMsg == -1) {
                        throw new BadRequestException("Ill-formatted bMessage, no BEGIN:MSG");
                    }
                    int endMsg = body.indexOf("END:MSG", beginMsg);
                    if (endMsg == -1) {
                        throw new BadRequestException("Ill-formatted bMessage, no END:MSG");
                    }
                    return body.substring(beginMsg + "BEGIN:MSG".length(), endMsg - CRLF.length());
                } else {
                    pos = next + CRLF.length();
                }
            }
        }
        if(beginVersionPos > 0){
            int endVersionPos;
            if(rfc822Flag == 0){
                if(mimeFlag == 0) {
                    endVersionPos = body.indexOf("END:MSG", beginVersionPos) ;
                    if (endVersionPos != -1) {
                        return body.substring(beginVersionPos, (endVersionPos - CRLF.length()));
                    }
                    else {
                        return body.substring(beginVersionPos);
                    }
                } else {
                    endVersionPos = (body.indexOf("--"+boundary+"--", beginVersionPos) - CRLF.length());
                }
                try {
                    return body.substring(beginVersionPos, endVersionPos);
                } catch (IndexOutOfBoundsException e) {
                    throw new BadRequestException("Ill-formatted bMessage, no end boundary");
                }
            }
            else if(rfc822Flag == 1){
                endVersionPos = (body.indexOf("--"+boundary+"--", beginVersionPos));
                try {
                    body = body.substring(beginVersionPos, endVersionPos);
                } catch (IndexOutOfBoundsException e) {
                    throw new BadRequestException("Ill-formatted bMessage, no end boundary");
                }
                int pos2 = body.indexOf(CRLF) + CRLF.length();
                while (pos2 > 0) {
                    if(body.startsWith(CRLF, pos2)) {
                        beginVersionPos1 = pos2 + CRLF.length();
                        break;
                    } else {
                        final int next = body.indexOf(CRLF, pos2);
                        if (next == -1) {
                            throw new BadRequestException("Ill-formatted bMessage, no empty line");
                        } else {
                            pos2 = next + CRLF.length();
                        }
                    }
                }
                if(beginVersionPos1 > 0){
                    return body.substring(beginVersionPos1);
                }
            }
        }
        return null;
    }

    private static String fetchContentType(String bmsg, String boundary) {
        int pos1 = bmsg.indexOf("--"+boundary);
        int pos = bmsg.indexOf("Content-Type:", pos1);
        if (pos > 0) {

            int beginVersionPos = pos + (("Content-Type:").length());
            int endVersionPos = bmsg.indexOf(CRLF, beginVersionPos);
            return bmsg.substring(beginVersionPos, endVersionPos);

        } else {

            return null;

        }
    }

    /**
     * fetchNumEnv
     *
     * This method takes as input a String in the bMessage format. It parses the
     * String and returns the number of envelope headers that it finds as an
     * Integer
     *
     * @param
     * @return String This method returns the number of Envelope headers as an
     *         Integer
     */
    @SuppressWarnings("unused")
    private static Integer fetchNumEnv(String bmsg) {
        int envCnt = 0;
        int pos = 0;
        int loopCnt = 0;
        pos = bmsg.indexOf((CRLF + "BEGIN:BENV"), pos);
        if (pos < 0) {
            loopCnt = 4;
        } else {
            envCnt = envCnt + 1;
        }
        while (loopCnt < 4) {
            pos = bmsg.indexOf((CRLF + "BEGIN:BENV"), pos + CRLF.length());
            if (pos < 0) {
                loopCnt = 4;
            } else {
                envCnt = envCnt + 1;
            }
        }

        return envCnt;
    }

    /**
     * fetchVcardVersion
     *
     * This method takes as input a vCard formatted String. It parses the String
     * and returns the vCard version as a String
     *
     * @param
     * @return String This method returns a vCard version String
     */
    private static String fetchVcardVersion(String vCard) {
        int pos = vCard.indexOf(CRLF + "VERSION:");
        int beginVersionPos = pos + (("VERSION:").length() + CRLF.length());
        int endVersionPos = vCard.indexOf(CRLF, beginVersionPos);

        return vCard.substring(beginVersionPos, endVersionPos);
    }

    /**
     * fetchVcardName
     *
     * This method takes as input a vCard formatted String. It parses the String
     * and returns the vCard name as a String
     *
     * @param
     * @return String This method returns a vCard name String
     */
    @SuppressWarnings("unused")
    private static String fetchVcardName(String vCard) {

        int pos = vCard.indexOf((CRLF + "N:"));
        int beginNPos = pos + "N:".length() + CRLF.length();
        int endNPos = vCard.indexOf(CRLF, beginNPos);
        return vCard.substring(beginNPos, endNPos);
    }

    /**
     * fetchVcardTel
     *
     * This method takes as input a vCard formatted String. It parses the String
     * and returns the vCard phone number as a String
     *
     * @param
     * @return String This method returns a vCard phone number String
     */
    private static String fetchVcardTel(String vCard) {

        int pos = vCard.indexOf((CRLF + "TEL:"));
        int beginVersionPos = pos + (("TEL:").length() + CRLF.length());
        int endVersionPos = vCard.indexOf(CRLF, beginVersionPos);
        return vCard.substring(beginVersionPos, endVersionPos);
    }

    /**
     * fetchVcardEmail
     *
     * This method takes as input a vCard formatted String. It parses the String
     * and returns the vCard phone number as a String
     *
     * @param
     * @return String This method returns a vCard phone number String
     */
    private static String fetchVcardEmailforMms(String vCard) {
        int pos = vCard.indexOf((CRLF + "EMAIL:"));
        int beginVersionPos = pos + (("EMAIL:").length() + CRLF.length());
        int endVersionPos = vCard.indexOf(CRLF, beginVersionPos);
        return vCard.substring(beginVersionPos, endVersionPos);
    }

    public static class BadRequestException extends Exception {
        public BadRequestException(String reason) {
            super("BadRequestException: " + reason);
        }
    }

    public static class RecipientVCard implements VCardInterpreter {
        String mName = "";
        String mFormattedName = "";
        String mTel = "";
        String mEmail = "";
        String mVersion = "";
        String mCurrentProperty = "";

        public void end() {
            if (V) Log.v(TAG, "end()");
        }

        public void endEntry() {
            if (V) Log.v(TAG, "endEntry()");
        }

        public void endProperty() {
            if (V) Log.v(TAG, "endProperty()");
            mCurrentProperty = "";
        }

        public void propertyGroup(String group) {
            if (V) Log.v(TAG, "propertyGroup(" + group + ")");
        }

        public void propertyName(String name) {
            if (V) Log.v(TAG, "propertyName(" + name + ")");
            mCurrentProperty = name;
        }

        public void propertyParamType(String type) {
            if (V) Log.v(TAG, "propertyParamType(" + type + ")");
        }

        public void onPropertyCreated(VCardProperty property) {
            List <String> values;

            if (V) Log.v(TAG, "onPropertyCreated(" + property + ")");

            values = property.getValueList();
            if (values == null){
                Log.e(TAG, "NULL Value List received");
                return;
            }

            // The first appeared property in a vCard will be used
            if (PROPERTY_N.equals(property.getName()) && mName.length() == 0) {
                StringBuilder sb = new StringBuilder();

                sb.append(values.get(0));
                final int size = values.size();
                for (int i = 0; i < size; i ++) {
                    sb.append(", ");
                    sb.append(values.get(i));
                }
                mName = sb.toString();
                if (V) Log.v(TAG, PROPERTY_N + ": " + mName);
            } else if (PROPERTY_TEL.equals(property.getName()) && mTel.length() == 0) {
                mTel = values.get(0);
                if (V) Log.v(TAG, PROPERTY_TEL + ": " + mTel);
            } else if (PROPERTY_EMAIL.equals(property.getName()) && mEmail.length() == 0) {
                mEmail = values.get(0);
                if (V) Log.v(TAG, PROPERTY_EMAIL + ": " + mEmail);
            } else if (PROPERTY_FN.equals(property.getName()) && mFormattedName.length() == 0) {
                mFormattedName = values.get(0);
                if (V) Log.v(TAG, PROPERTY_FN + ": " + mFormattedName);
            }
        }

        public void onVCardStarted() {
            if (V) Log.v(TAG, "onVCardStarted");
        }

        public void onVCardEnded() {
            if (V) Log.v(TAG, "onVCardEnded");
        }

        public void onEntryStarted() {
            if (V) Log.v(TAG, "onEntryStarted");
            mName = "";
            mFormattedName = "";
            mTel = "";
            mEmail = "";
        }

        public void onEntryEnded() {
            if (V) Log.v(TAG, "onEntryEnded");
        }


        public void propertyParamValue(String value) {
            if (V) Log.v(TAG, "propertyParamValue(" + value + ")");
        }

        public void propertyValues(List<String> values) {
            if (V) Log.v(TAG, "propertyValues(" + values.toString() + "), Property=" + mCurrentProperty);
            // The first appeared property in a vCard will be used
            if (PROPERTY_N.equals(mCurrentProperty) && mName.length() == 0) {
                StringBuilder sb = new StringBuilder();
                sb.append(values.get(0));
                final int size = values.size();
                for (int i = 0; i < size; i ++) {
                    sb.append(", ");
                    sb.append(values.get(i));
                }
                mName = sb.toString();
                if (V) Log.v(TAG, PROPERTY_N + ": " + mName);
            } else if (PROPERTY_TEL.equals(mCurrentProperty) && mTel.length() == 0) {
                mTel = values.get(0);
                if (V) Log.v(TAG, PROPERTY_TEL + ": " + mTel);
            } else if (PROPERTY_EMAIL.equals(mCurrentProperty) && mEmail.length() == 0) {
                mEmail = values.get(0);
                if (V) Log.v(TAG, PROPERTY_EMAIL + ": " + mEmail);
            } else if (PROPERTY_FN.equals(mCurrentProperty) && mFormattedName.length() == 0) {
                mFormattedName = values.get(0);
                if (V) Log.v(TAG, PROPERTY_FN + ": " + mFormattedName);
            }
        }

        public void start() {
            if (V) Log.v(TAG, "start()");
        }

        public void startEntry() {
            if (V) Log.v(TAG, "startEntry()");
            mName = "";
            mFormattedName = "";
            mTel = "";
            mEmail = "";
        }

        public void startProperty() {
            if (V) Log.v(TAG, "startProperty()");
            mCurrentProperty = "";
        }
    }

    static RecipientVCard parseVCard(String vCard) throws BadRequestException {
        if (V) Log.v(TAG, "parseVCard(" + vCard + ")");
        RecipientVCard recipient = new RecipientVCard();

        if (vCard.length() == 0) {
            return recipient;
        }

        try {
            ByteArrayInputStream is = null;
            try {
                byte vCardBytes[] = vCard.getBytes("UTF-8");
                is = new ByteArrayInputStream(vCardBytes);
            } catch (UnsupportedEncodingException ex) {
                Log.w(TAG, "Unable to parse vCard", ex);
                throw new BadRequestException("Unable to parse vCard");
            }
            VCardParser parser = new VCardParser_V21();
            try {
                if (V) Log.v(TAG, "try " + VERSION_V21);
                recipient.mVersion = VERSION_V21;
                parser.parse(is, recipient);
            } catch (VCardVersionException e) {
                is.close();
                is = new ByteArrayInputStream(vCard.getBytes("UTF-8"));
                try {
                    if (V) Log.v(TAG, "try " + VERSION_V30);
                    recipient.mVersion = VERSION_V30;
                    parser = new VCardParser_V30();
                    parser.parse(is, recipient);
                } catch (VCardVersionException e1) {
                    throw new VCardException("vCard with unsupported version.");
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Unable to parse vCard", e);
            throw new BadRequestException("Unable to parse vCard");
        } catch (VCardException e) {
            Log.w(TAG, "Unable to parse vCard", e);
            throw new BadRequestException("Unable to parse vCard");
        }

        return recipient;
    }
}
