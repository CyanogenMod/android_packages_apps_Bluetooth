/*
 * Copyright (c) 2010-2011, Code Aurora Forum. All rights reserved.
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

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import android.util.Log;

import org.xmlpull.v1.XmlSerializer;

import android.util.Xml;

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

    private final String CRLF = "\r\n";

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

    public String folderListingXML(List<String> list) {

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

    public String messageListingXML(List<MsgListingConsts> list) {
        XmlSerializer serializer = Xml.newSerializer();
        StringWriter writer = new StringWriter();
        try {
            String str1;
            String str2 = "<?xml version=\"1.0\"?>";
            serializer.setOutput(writer);
            serializer.startDocument(null, null);
            serializer.text("\n");
            serializer.startTag("", "MAP-msg-listing");
            serializer.attribute("", "version", "1.0");
            for (MsgListingConsts msg : list) {
                serializer.startTag("", "");

                serializer.attribute("", "msg handle", ("" + msg.msg_handle));
                if (msg.sendSubject == true) {
                    if (msg.subject == null){
                        serializer.attribute("", "subject", "");
                    } else {
                        serializer.attribute("", "subject", msg.subject);
                    }

                }
                if (msg.datetime != null) {
                    serializer.attribute("", "datetime", msg.datetime);
                }
                if (msg.sender_name != null) {
                    serializer.attribute("", "sender_name", msg.sender_name);
                }

                if (msg.sender_addressing != null) {
                    serializer.attribute("", "sender_addressing",
                            msg.sender_addressing);
                }

                if (msg.replyto_addressing != null) {
                    serializer.attribute("", "replyto_addressing",
                            msg.replyto_addressing);
                }

                if (msg.recepient_name != null) {
                    serializer.attribute("", "recipient_name",
                            msg.recepient_name);
                }
                if (msg.sendRecipient_addressing == true) {
                    if (msg.recepient_addressing != null) {
                        serializer.attribute("", "recipient_addressing",
                                msg.recepient_addressing);
                    } else {
                        serializer.attribute("", "recipient_addressing", "");
                    }
                }
                if (msg.type != null) {
                    serializer.attribute("", "type", msg.type);
                }
                if (msg.size != 0) {
                    serializer.attribute("", "size", ("" + msg.size));
                }

                if (msg.contains_text != null) {
                    serializer.attribute("", "text", msg.contains_text);
                }

                if (msg.reception_status != null) {
                    serializer.attribute("", "reception_status",
                            msg.reception_status);
                }

                if (msg.attachment_size != -1) {
                    serializer.attribute("", "attachment_size",
                            ("" + Integer.toString(msg.attachment_size)));
                }

                if (msg.priority != null) {
                    serializer.attribute("", "priority", msg.priority);
                }

                if (msg.read != null) {
                    serializer.attribute("", "read", msg.read);
                }

                if (msg.sent != null) {
                    serializer.attribute("", "sent", msg.sent);
                }

                if (msg.msg_protected != null) {
                    serializer.attribute("", "protected", msg.msg_protected);
                }

                serializer.endTag("", "");

            }
            serializer.endTag("", "MAP-msg-listing");
            serializer.endDocument();
            str1 = writer.toString();

            str1 = removeMsgHdrSpace(str1);

            int line1 = 0;
            line1 = str1.indexOf("\n");
            str2 += str1.substring(line1 + 1);
            if (list.size() > 0) {
                int indxHandle = str2.indexOf("msg handle");
                String str3 = "<" + str2.substring(indxHandle);
                str2 = str2.substring(0, (indxHandle - 1)) + str3;
            }
            return str2;

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

    public String msgListingGetHdrXML() {
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

    public String msgListingGetFooterXML() {
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

    public String msgListingGetMsgsXML(List<MsgListingConsts> list) {
        XmlSerializer serializer = Xml.newSerializer();
        StringWriter writer = new StringWriter();
        try {
            String str1;
            serializer.setOutput(writer);
            serializer.startDocument("", false);
            serializer.text("\n");
            for (MsgListingConsts msg : list) {
                serializer.startTag("", "");
                serializer.attribute("", "msg handle", ("" + msg.msg_handle));
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

                serializer.endTag("", "");

            }
            serializer.endDocument();
            str1 = writer.toString();

            str1 = removeMsgHdrSpace(str1);
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

    public String mapEventReportXML(String type, String handle, String folder,
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

            serializer.startTag("", "");
            if (type != null) {
                serializer.attribute("", "event type", ("" + type));
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
            serializer.endTag("", "");
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

    public String toBmessageSMS(BmessageConsts bmsg) {

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

    public String toBmessageMMS(BmessageConsts bmsg) {

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

    public String toBmessageEmail(BmessageConsts bmsg) {

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

            sb.append("LANGUAGE:English").append("\r\n");
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
     */

    public BmessageConsts fromBmessageSMS(String bmsg) {

        BmessageConsts bMsgObj = new BmessageConsts();

        // Extract Telephone number of sender
        String phoneNumber = null;
        String vCard = null;
        vCard = fetchRecipientVcard(bmsg);
        phoneNumber = fetchVcardTel(vCard);
        bMsgObj.setRecipientVcard_phone_number(phoneNumber);

        // Extract vCard Version
        bMsgObj.setVcard_version(fetchVcardVersion(vCard));

        // Extract vCard Name
        bMsgObj.setVcard_version(fetchVcardVersion(vCard));

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
     */

    public BmessageConsts fromBmessageMMS(String bmsg) {

        BmessageConsts bMsgObj = new BmessageConsts();

        // Extract Telephone number of sender
        String phoneNumber = null;
        String vCard = null;
        vCard = fetchRecipientVcard(bmsg);

        if (vCard.indexOf("EMAIL:") > 0) {
            phoneNumber = fetchVcardEmailforMms(vCard);
        } else {
            phoneNumber = fetchVcardTel(vCard);
        }

        bMsgObj.setRecipientVcard_phone_number(phoneNumber);

        // Extract vCard Version
        bMsgObj.setVcard_version(fetchVcardVersion(vCard));

        // Extract vCard Name
        bMsgObj.setVcard_version(fetchVcardVersion(vCard));

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

    public BmessageConsts fromBmessageEmail(String bmsg) {

        BmessageConsts bMsgObj = new BmessageConsts();
        Log.d("MapUtils", "Inside fromBmessageEmail method::");
        // Extract Telephone number of sender
        String email = null;
        String vCard = null;
        vCard = fetchRecepientVcardEmail(bmsg);
        Log.d("MapUtils", "vCard Info:: "+vCard);
        email = fetchVcardEmail(vCard);

        Log.d("MapUtils", "email Info:: "+email);
        bMsgObj.setRecipientVcard_email(email);

        String vcardOrig = fetchOriginatorVcardEmail(bmsg);
        String emailOrig = fetchVcardEmail(vcardOrig);
        Log.d("MapUtils", "Vcard Originator Email:: "+emailOrig);
        bMsgObj.setOriginatorVcard_email(emailOrig);

        Log.d("MapUtils", "Vcard Originatore Name:: "+fetchVcardName(vcardOrig));
        String nameOrig = fetchVcardName(vcardOrig);
        bMsgObj.setOriginatorVcard_name(nameOrig);

        Log.d("MapUtils", "Vcard version:: "+fetchVcardVersion(vCard));
        // Extract vCard Version
        bMsgObj.setVcard_version(fetchVcardVersion(vCard));

        // Extract vCard Name

        Log.d("MapUtils", "Bmsg version:: "+fetchVersion(bmsg));
        // Extract bMessage Version
        bMsgObj.setBmsg_version(fetchVersion(bmsg));

        Log.d("MapUtils", "Read status:: "+fetchReadStatus(bmsg));
        // Extract Message Status
        bMsgObj.setStatus(fetchReadStatus(bmsg));

        Log.d("MapUtils", "Message Type:: "+fetchType(bmsg));
        // Extract Message Type
        bMsgObj.setType(fetchType(bmsg));

        Log.d("MapUtils", "Folder:: "+fetchFolder(bmsg));
        // Extract Message Folder
        bMsgObj.setFolder(fetchFolder(bmsg));

        Log.d("MapUtils", "body length:: "+fetchBodyLength(bmsg));
        // Fetch Message Length
        bMsgObj.setBody_length(fetchBodyLength(bmsg));

        Log.d("MapUtils", "Message body:: "+fetchBodyMsgEmail(bmsg));
        // Extract Message
        bMsgObj.setBody_msg(fetchBodyMsgEmail(bmsg));

        Log.d("MapUtils", "Message encoding:: "+fetchBodyEncoding(bmsg));
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

    private String fetchVcardEmail(String vCard) {

        int pos = vCard.indexOf(("EMAIL:"));
        int beginVersionPos = pos + (("EMAIL:").length());
        Log.d("Map Utils","Begin Version Position Email:: "+beginVersionPos);
        int endVersionPos = vCard.indexOf("\n", beginVersionPos);
        Log.d("Map Utils","End version Pos Email:: "+endVersionPos);
        return vCard.substring(beginVersionPos, endVersionPos);
    }
    private String fetchRecepientVcardEmail(String bmsg) {

        // Find the position of the first vCard in the string
        int pos = bmsg.indexOf("BEGIN:BENV");
        Log.d("fetchOriginatorVcard", "vCard start position:: "+pos);
        if (pos > 0) {
            Log.d("fetchOriginatorVcard", "vCard start position greater than 0::");
            int beginVcardPos = pos + ("\r\n".length());
            int endVcardPos = bmsg.indexOf("END:BENV");

            return bmsg.substring(beginVcardPos, endVcardPos);

        } else {

            return null;

        }
    }

    private String fetchOriginatorVcardEmail(String bmsg) {

        // Find the position of the first vCard in the string
        int pos = bmsg.indexOf("BEGIN:VCARD");
        Log.d("fetchOriginatorVcard", "vCard start position:: "+pos);
        if (pos > 0) {
            Log.d("fetchOriginatorVcard", "vCard start position greater than 0::");
            int beginVcardPos = pos + ("\r\n".length());
            int endVcardPos = bmsg.indexOf("END:VCARD");

            return bmsg.substring(beginVcardPos, endVcardPos);

        } else {

            return null;

        }
    }
    private String fetchSubjectEmail(String body) {

        int pos = body.indexOf("Subject:");

        if (pos > 0) {
            int beginVersionPos = pos + (("Subject:").length());
            int endVersionPos = body.indexOf("\n", beginVersionPos);
            return body.substring(beginVersionPos, endVersionPos);

        } else {

            return null;
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

    private String fetchVersion(String bmsg) {
        int pos = bmsg.indexOf("VERSION:");
        if (pos > 0) {

            int beginVersionPos = pos + (("VERSION:").length());
            int endVersionPos = bmsg.indexOf(CRLF, beginVersionPos);
            return bmsg.substring(beginVersionPos, endVersionPos);

        } else {

            return null;

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

    private String fetchOriginatorVcard(String bmsg) {

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

    @SuppressWarnings("unused")
    private String fetchRecipientVcard(String bmsg) {

        // Locate BENV
        int locBENV = 0;
        int pos = 0;
        locBENV = bmsg.indexOf(CRLF + "BEGIN:BENV");
        pos = bmsg.indexOf(CRLF + "BEGIN:VCARD", locBENV);
        if (locBENV < pos) {
            pos = bmsg.indexOf(CRLF + "BEGIN:VCARD", locBENV);
        } else {
            pos = bmsg.indexOf(CRLF + "BEGIN:VCARD");
        }
        if (pos > 0) {
            int beginVcardPos = pos;
            int endVcardPos = bmsg.indexOf("END:VCARD", pos);
            return bmsg.substring(beginVcardPos, endVcardPos);

        } else {
            return null;
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

    private String fetchReadStatus(String bmsg) {
        int pos = bmsg.indexOf("STATUS:");
        if (pos > 0) {

            int beginStatusPos = pos + (("STATUS:").length());
            int endStatusPos = bmsg.indexOf(CRLF, beginStatusPos);
            return bmsg.substring(beginStatusPos, endStatusPos);

        } else {

            return null;

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

    public String fetchType(String bmsg) {
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

    private String fetchFolder(String bmsg) {
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
    private String fetchBody(String bmsg) {
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
    private String fetchBodyPartID(String body) {
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
    private String fetchCharset(String body) {

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

    private String fetchBodyEncoding(String body) {
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
    private String fetchBodyLanguage(String body) {
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

    private Integer fetchBodyLength(String body) {

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

    private String fetchBodyMsg(String body) {
        int pos = body.indexOf("BEGIN:MSG");
        if (pos > 0) {
            int beginVersionPos = pos
                    + (("BEGIN:MSG").length() + CRLF.length());
            int endVersionPos = (body.indexOf("END:MSG", beginVersionPos) - CRLF
                    .length());

            return body.substring(beginVersionPos, endVersionPos);

        } else {

            return null;
        }
    }

    private String fetchBodyMsgEmail(String body) {
        Log.d("MapUtils", "bMessageEmail inside fetch body ::"+body);
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
    private Integer fetchNumEnv(String bmsg) {
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

    private String fetchVcardVersion(String vCard) {

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
    private String fetchVcardName(String vCard) {

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

    private String fetchVcardTel(String vCard) {

        int pos = vCard.indexOf((CRLF + "TEL:"));
        int beginVersionPos = pos + (("TEL:").length() + CRLF.length());
        int endVersionPos = vCard.indexOf(CRLF, beginVersionPos);
        return vCard.substring(beginVersionPos, endVersionPos);
    }

    /**
     * removeMsgHdrSpace
     *
     * This method takes as input a String that contains message listings and
     * removes a space between the < and the msg handle value. This space is
     * inserted by the serializer causing an error in processing the message
     * correctly. This private method parses the message and removes the spaces.
     *
     * @param String
     *            Message to be parsed
     * @return String This method returns the message String
     */

    private String removeMsgHdrSpace(String message) {
        String str1 = null;
        String str2 = null;

        Integer index = 0;
        Integer endSubstring = 0;

        index = message.indexOf("< msg handle");
        if (index < 0) {
            str2 = message;

        } else {
            str2 = message.substring(0, index);
            str2 = str2 + "\n";
            index = 0;
            while ((index = message.indexOf("msg handle", index)) > 0) {
                endSubstring = message.indexOf("/>", index) + "/>".length();
                str1 = "<" + message.substring(index, endSubstring);
                index = index + 1;
                str2 = str2 + str1;

            }
        }

        return str2 + "</MAP-msg-listing>";

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

    private String fetchVcardEmailforMms(String vCard) {

        int pos = vCard.indexOf((CRLF + "EMAIL:"));
        int beginVersionPos = pos + (("EMAIL:").length() + CRLF.length());
        int endVersionPos = vCard.indexOf(CRLF, beginVersionPos);
        return vCard.substring(beginVersionPos, endVersionPos);
    }

}
