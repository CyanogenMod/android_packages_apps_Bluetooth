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

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;
import android.util.Base64;
import android.util.Log;
import java.util.Random;
import android.util.Log;
import java.io.IOException;

public class BluetoothMapbMessageMmsEmail extends BluetoothMapbMessage {
    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = Log.isLoggable(BluetoothMapService.LOG_TAG, Log.VERBOSE) ? true : false;
    protected static String TAG = "BluetoothMapbMessageEmail";
    private static final String CRLF = "\r\n";

    public static class MimePart {
        public long _id = INVALID_VALUE;   /* The _id from the content provider, can be used to sort the parts if needed */
        public String contentType = null;  /* The mime type, e.g. text/plain */
        public String contentId = null;
        public String contentLocation = null;
        public String contentDisposition = null;
        public String partName = null;     /* e.g. text_1.txt*/
        public String charsetName = null;  /* This seems to be a number e.g. 106 for UTF-8 CharacterSets
                                                holds a method for the mapping. */
        public String fileName = null;     /* Do not seem to be used */
        public byte[] data = null;        /* The raw un-encoded data e.g. the raw jpeg data or the text.getBytes("utf-8") */



        public void encode(StringBuilder sb, String boundaryTag, boolean last) throws UnsupportedEncodingException {
            sb.append("--").append(boundaryTag).append("\r\n");
            if(contentType != null)
                sb.append("Content-Type: ").append(contentType);
            if(charsetName != null)
                sb.append("; ").append("charset=\"").append(charsetName).append("\"");
            sb.append("\r\n");
            if(contentLocation != null)
                sb.append("Content-Location: ").append(contentLocation).append("\r\n");
            if(contentId != null)
                sb.append("Content-ID: ").append(contentId).append("\r\n");
            if(contentDisposition != null)
                sb.append("Content-Disposition: ").append(contentDisposition).append("\r\n");
            if(data != null) {
                /* TODO: If errata 4176 is adopted in the current form (it is not in either 1.1 or 1.2),
                the below is not allowed, Base64 should be used for text. */

                if(contentType != null &&
                        (contentType.toUpperCase().contains("TEXT") ||
                         contentType.toUpperCase().contains("SMIL") )) {
                    sb.append("Content-Transfer-Encoding: 8BIT\r\n\r\n"); // Add the header split empty line
                    sb.append(new String(data,"UTF-8")).append("\r\n");
                }
                else {
                    sb.append("Content-Transfer-Encoding: Base64\r\n\r\n"); // Add the header split empty line
                    sb.append(Base64.encodeToString(data, Base64.DEFAULT)).append("\r\n");
                }
            }
            if(last) {
                sb.append("--").append(boundaryTag).append("--").append("\r\n");
            }
        }

        public void encodePlainText(StringBuilder sb) throws UnsupportedEncodingException {
            if(contentType != null && contentType.toUpperCase().contains("TEXT")) {
                if(data != null) {
                   sb.append(contentType).append("\r\n");
                   sb.append("Content-Transfer-Encoding: 8bit").append("\r\n");
                   sb.append("Content-Disposition:inline").append("\r\n")
                           .append("\r\n");
                sb.append(new String(data,"UTF-8")).append("\r\n");
                }
            } else if(contentType != null && contentType.toUpperCase().contains("/SMIL")) {
                /* Skip the smil.xml, as no-one knows what it is. */
            } else {
                /* Not a text part, just print the filename or part name if they exist. */
                if(partName != null)
                    sb.append("<").append(partName).append(">\r\n");
                else
                    sb.append("<").append("attachment").append(">\r\n");
            }
        }
    }

    private long date = INVALID_VALUE;
    private String subject = null;
    private String emailBody = null;
    private ArrayList<Rfc822Token> from = null;   // Shall not be empty
    private ArrayList<Rfc822Token> sender = null;   // Shall not be empty
    private ArrayList<Rfc822Token> to = null;     // Shall not be empty
    private ArrayList<Rfc822Token> cc = null;     // Can be empty
    private ArrayList<Rfc822Token> bcc = null;    // Can be empty
    private ArrayList<Rfc822Token> replyTo = null;// Can be empty
    private String messageId = null;
    private ArrayList<MimePart> parts = null;
    private String contentType = null;
    private String boundary = null;
    private boolean textOnly = false;
    private boolean includeAttachments;
    private boolean hasHeaders = false;
    private String encoding = null;

    private String getBoundary() {
        if(boundary == null)
            boundary = "----" + UUID.randomUUID();
        return boundary;
    }

    /**
     * @return the parts
     */
    public ArrayList<MimePart> getMimeParts() {
        return parts;
    }

    public MimePart addMimePart() {
        if(parts == null)
            parts = new ArrayList<BluetoothMapbMessageMmsEmail.MimePart>();
        MimePart newPart = new MimePart();
        parts.add(newPart);
        return newPart;
    }
    public String getDateString() {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        Date dateObj = new Date(date);
        return format.format(dateObj); // Format according to RFC 2822 page 14
    }
    public long getDate() {
        return date;
    }
    public void setDate(long date) {
        this.date = date;
    }
    public String getSubject() {
        return subject;
    }
    public void setSubject(String subject) {
       if(D) Log.d(TAG,"setting Subject to" +subject);
        this.subject = subject;
    }

    public void setEmailBody(String emailBody) {
        if(D) Log.d(TAG,"setting setEmailBody to" +emailBody);
        this.emailBody= emailBody;
    }

    public ArrayList<Rfc822Token> getFrom() {
        return from;
    }
    public void setFrom(ArrayList<Rfc822Token> from) {
        this.from = from;
    }
    public void addFrom(String name, String address) {
        if(this.from == null)
            this.from = new ArrayList<Rfc822Token>(1);
        this.from.add(new Rfc822Token(name, address, null));
    }
    public ArrayList<Rfc822Token> getSender() {
        return sender;
    }
    public void setSender(ArrayList<Rfc822Token> sender) {
        this.sender = sender;
    }
    public void addSender(String name, String address) {
        if(this.sender == null)
            this.sender = new ArrayList<Rfc822Token>(1);
        this.sender.add(new Rfc822Token(name,address,null));
    }
    public ArrayList<Rfc822Token> getTo() {
        return to;
    }
    public void setTo(ArrayList<Rfc822Token> to) {
        this.to = to;
    }
    public void addTo(String name, String address) {
        if(this.to == null)
            this.to = new ArrayList<Rfc822Token>(1);
        this.to.add(new Rfc822Token(name, address, null));
    }
    public ArrayList<Rfc822Token> getCc() {
        return cc;
    }
    public void setCc(ArrayList<Rfc822Token> cc) {
        this.cc = cc;
    }
    public void addCc(String name, String address) {
        if(this.cc == null)
            this.cc = new ArrayList<Rfc822Token>(1);
        this.cc.add(new Rfc822Token(name, address, null));
    }
    public ArrayList<Rfc822Token> getBcc() {
        return bcc;
    }
    public void setBcc(ArrayList<Rfc822Token> bcc) {
        this.bcc = bcc;
    }
    public void addBcc(String name, String address) {
        if(this.bcc == null)
            this.bcc = new ArrayList<Rfc822Token>(1);
        this.bcc.add(new Rfc822Token(name, address, null));
    }
    public ArrayList<Rfc822Token> getReplyTo() {
        return replyTo;
    }
    public void setReplyTo(ArrayList<Rfc822Token> replyTo) {
        this.replyTo = replyTo;
    }
    public void addReplyTo(String name, String address) {
        if(this.replyTo == null)
            this.replyTo = new ArrayList<Rfc822Token>(1);
        this.replyTo.add(new Rfc822Token(name, address, null));
    }
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
    public String getMessageId() {
        return messageId;
    }
    public String getEmailBody() {
        return emailBody;
    }
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
    public String getContentType() {
        return contentType;
    }
    public void setTextOnly(boolean textOnly) {
        this.textOnly = textOnly;
    }
    public boolean getTextOnly() {
        return textOnly;
    }
    public void setIncludeAttachments(boolean includeAttachments) {
        this.includeAttachments = includeAttachments;
    }
    public boolean getIncludeAttachments() {
        return includeAttachments;
    }
    public void updateCharset() {
        if(D) Log.d(TAG, " Inside updateCharset ");
        charset = null;
        if (parts == null) {
            Log.e(TAG, " parts is null. returning ");
            return;
        }
        for(MimePart part : parts) {
            if(part.contentType != null &&
               part.contentType.toUpperCase().contains("TEXT")) {
                charset = "UTF-8";
                break;
            }
        }
    }
    public int getSize() {
        int message_size = 0;
        if (parts == null) {
            Log.e(TAG, " parts is null. returning ");
            return message_size;
        }
        for(MimePart part : parts) {
            message_size += part.data.length;
        }
        return message_size;
    }

    /**
     * Encode an address header, and perform folding if needed.
     * @param sb The stringBuilder to write to
     * @param headerName The RFC 2822 header name
     * @param addresses the reformatted address substrings to encode.
     */
    public void encodeHeaderAddresses(StringBuilder sb, String headerName,
            ArrayList<Rfc822Token> addresses) throws UnsupportedEncodingException {
        /* TODO: Do we need to encode the addresses if they contain illegal characters?
         * This depends of the outcome of errata 4176. The current spec. states to use UTF-8
         * where possible, but the RFCs states to use US-ASCII for the headers - hence encoding
         * would be needed to support non US-ASCII characters. But the MAP spec states not to
         * use any encoding... */
        int partLength, lineLength = 0;
        lineLength += headerName.getBytes().length;
        sb.append(headerName);
        for(Rfc822Token address : addresses) {
            partLength = address.toString().getBytes().length+1;
            // Add folding if needed
            if(lineLength + partLength >= 998) // max line length in RFC2822
            {
                sb.append("\r\n "); // Append a FWS (folding whitespace)
                lineLength = 0;
            }
            sb.append(new String(address.toString().getBytes("UTF-8"),"UTF-8")).append(";");
            lineLength += partLength;
        }
        sb.append("\r\n");
    }

    public void encodeHeaders(StringBuilder sb) throws UnsupportedEncodingException
    {
        /* TODO: From RFC-4356 - about the RFC-(2)822 headers:
         *    "Current Internet Message format requires that only 7-bit US-ASCII
         *     characters be present in headers.  Non-7-bit characters in an address
         *     domain must be encoded with [IDN].  If there are any non-7-bit
         *     characters in the local part of an address, the message MUST be
         *     rejected.  Non-7-bit characters elsewhere in a header MUST be encoded
         *     according to [Hdr-Enc]."
         *    We need to add the address encoding in encodeHeaderAddresses, but it is not
         *    straight forward, as it is unclear how to do this.  */
        if (date != INVALID_VALUE)
            sb.append("Date: ").append(getDateString()).append("\r\n");
        /* According to RFC-2822 headers must use US-ASCII, where the MAP specification states
         * UTF-8 should be used for the entire <bmessage-body-content>. We let the MAP specification
         * take precedence above the RFC-2822. The code to
         */
        /* If we are to use US-ASCII anyway, here are the code for it.
          if (subject != null){
            // Use base64 encoding for the subject, as it may contain non US-ASCII characters or other
            // illegal (RFC822 header), and android do not seem to have encoders/decoders for quoted-printables
            sb.append("Subject:").append("=?utf-8?B?");
            sb.append(Base64.encodeToString(subject.getBytes("utf-8"), Base64.DEFAULT));
            sb.append("?=\r\n");
        }*/
        if (subject != null)
            sb.append("Subject: ").append(new String(subject.getBytes("UTF-8"),"UTF-8")).append("\r\n");
        if(from != null)
            encodeHeaderAddresses(sb, "From: ", from); // This includes folding if needed.
        if(sender != null)
            encodeHeaderAddresses(sb, "Sender: ", sender); // This includes folding if needed.
        /* For MMS one recipient(to, cc or bcc) must exists, if none: 'To:  undisclosed-
         * recipients:;' could be used.
         * TODO: Is this a valid solution for E-Mail?
         */
        if(to == null && cc == null && bcc == null)
            sb.append("To:  undisclosed-recipients:;\r\n");
        if(to != null)
            encodeHeaderAddresses(sb, "To: ", to); // This includes folding if needed.
        if(cc != null)
            encodeHeaderAddresses(sb, "Cc: ", cc); // This includes folding if needed.
        if(bcc != null)
            encodeHeaderAddresses(sb, "Bcc: ", bcc); // This includes folding if needed.
        if(replyTo != null)
            encodeHeaderAddresses(sb, "Reply-To: ", replyTo); // This includes folding if needed.
        if(includeAttachments == true)
        {
            if(messageId != null)
                sb.append("Message-Id: ").append(messageId).append("\r\n");
            if(contentType != null)
                sb.append("Content-Type: ").append(contentType).append("; boundary=").append(getBoundary()).append("\r\n");
        }
    }

    /**
     * Encode the bMessage as an EMAIL
     * @return
     * @throws UnsupportedEncodingException
     */
    public byte[] encodeEmail() throws UnsupportedEncodingException
    {
        if (V) Log.v(TAG, "Inside encodeEmail ");
        ArrayList<byte[]> bodyFragments = new ArrayList<byte[]>();
        StringBuilder sb = new StringBuilder ();
        int count = 0;
        String emailBody;
        Random randomGenerator = new Random();
        int randomInt = randomGenerator.nextInt(1000);
        String boundary = "MessageBoundary."+randomInt;

        encodeHeaders(sb);
        sb.append("Mime-Version: 1.0").append("\r\n");
        sb.append(
               "Content-Type: multipart/mixed; boundary=\""+boundary+"\"")
                .append("\r\n");
        sb.append("Content-Transfer-Encoding: 8bit").append("\r\n")
                .append("\r\n");
        sb.append("MIME Message").append("\r\n");
        sb.append("--"+boundary).append("\r\n");
        Log.v(TAG, "after encode header sb is "+ sb.toString());

        if (parts != null) {
            if(getIncludeAttachments() == false) {
               for(MimePart part : parts) {
                   part.encodePlainText(sb); /* We call encode on all parts, to include a tag, where an attachment is missing. */
                   sb.append("--"+boundary+"--").append("\r\n");
               }
           } else {
               for(MimePart part : parts) {
                   count++;
                   part.encode(sb, getBoundary(), (count == parts.size()));
               }
           }
        } else {
               Log.e(TAG, " parts is null.");
        }

        emailBody = sb.toString();
        if (V) Log.v(TAG, "emailBody is "+emailBody);

        if(emailBody != null) {
            String tmpBody = emailBody.replaceAll("END:MSG", "/END\\:MSG"); // Replace any occurrences of END:MSG with \END:MSG
            bodyFragments.add(tmpBody.getBytes("UTF-8"));
        } else {
            bodyFragments.add(new byte[0]);
        }

        return encodeGeneric(bodyFragments);
    }

    /* Notes on MMS
     * ------------
     * According to rfc4356 all headers of a MMS converted to an E-mail must use
     * 7-bit encoding. According the the MAP specification only 8-bit encoding is
     * allowed - hence the bMessage-body should contain no SMTP headers. (Which makes
     * sense, since the info is already present in the bMessage properties.)
     * The result is that no information from RFC4356 is needed, since it does not
     * describe any mapping between MMS content and E-mail content.
     * Suggestion:
     * Clearly state in the MAP specification that
     * only the actual message content should be included in the <bmessage-body-content>.
     * Correct the Example to not include the E-mail headers, and in stead show how to
     * include a picture or another binary attachment.
     *
     * If the headers should be included, clearly state which, as the example clearly shows
     * that some of the headers should be excluded.
     * Additionally it is not clear how to handle attachments. There is a parameter in the
     * get message to include attachments, but since only 8-bit encoding is allowed,
     * (hence neither base64 nor binary) there is no mechanism to embed the attachment in
     * the <bmessage-body-content>.
     *
     * UPDATE: Errata 4176 allows the needed encoding typed inside the <bmessage-body-content>
     * including Base64 and Quoted Printables - hence it is possible to encode non-us-ascii
     * messages - e.g. pictures and utf-8 strings with non-us-ascii content.
     * It have not yet been adopted, but since the comments clearly suggest that it is allowed
     * to use encoding schemes for non-text parts, it is still not clear what to do about non
     * US-ASCII text in the headers.
     * */

    /**
     * Encode the bMessage as a MMS
     * @return
     * @throws UnsupportedEncodingException
     */
    public byte[] encodeMms() throws UnsupportedEncodingException
    {
        ArrayList<byte[]> bodyFragments = new ArrayList<byte[]>();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        String mmsBody;

        encoding = "8BIT"; // The encoding used

        encodeHeaders(sb);

        if(parts != null) {
           if(getIncludeAttachments() == false) {
               for(MimePart part : parts) {
                   part.encodePlainText(sb); /* We call encode on all parts, to include a tag, where an attachment is missing. */
               }
           } else {
               for(MimePart part : parts) {
                   count++;
                   part.encode(sb, getBoundary(), (count == parts.size()));
               }
           }
        }
        mmsBody = sb.toString();

        if(mmsBody != null) {
            String tmpBody = mmsBody.replaceAll("END:MSG", "/END\\:MSG"); // Replace any occurrences of END:MSG with \END:MSG
            bodyFragments.add(tmpBody.getBytes("UTF-8"));
        } else {
            bodyFragments.add(new byte[0]);
        }

        return encodeGeneric(bodyFragments);
    }


    /**
     * Try to parse the hdrPart string as e-mail headers.
     * @param hdrPart The string to parse.
     * @return Null if the entire string were e-mail headers. The part of the string in which
     * no headers were found.
     */
    private String parseMmsHeaders(String hdrPart) {
        String[] headers = hdrPart.split("\r\n");
        String header;
        hasHeaders = false;

        for(int i = 0, c = headers.length; i < c; i++) {
            header = headers[i];

            /* We need to figure out if any headers are present, in cases where devices do not follow the e-mail RFCs.
             * Skip empty lines, and then parse headers until a non-header line is found, at which point we treat the
             * remaining as plain text.
             */
            if(header.trim() == "")
                continue;
            String[] headerParts = header.split(":",2);
            if(headerParts.length != 2) {
                // We treat the remaining content as plain text.
                StringBuilder remaining = new StringBuilder();
                for(; i < c; i++)
                    remaining.append(headers[i]);

                return remaining.toString();
            }

            String headerType = headerParts[0].toUpperCase();
            String headerValue = headerParts[1].trim();

            // Address headers
            /* TODO: If this is empty, the MSE needs to fill it in before sending the message.
             * This happens when sending the MMS, not sure what happens for e-mail.
             */
            if(headerType.contains("FROM")) {
                Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(headerValue);
                from = new ArrayList<Rfc822Token>(Arrays.asList(tokens));
            }
            else if(headerType.contains("TO")) {
                Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(headerValue);
                to = new ArrayList<Rfc822Token>(Arrays.asList(tokens));
            }
            else if(headerType.contains("CC")) {
                Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(headerValue);
                cc = new ArrayList<Rfc822Token>(Arrays.asList(tokens));
            }
            else if(headerType.contains("BCC")) {
                Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(headerValue);
                bcc = new ArrayList<Rfc822Token>(Arrays.asList(tokens));
            }
            else if(headerType.contains("REPLY-TO")) {
                Rfc822Token tokens[] = Rfc822Tokenizer.tokenize(headerValue);
                replyTo = new ArrayList<Rfc822Token>(Arrays.asList(tokens));
            }// Other headers
            else if(headerType.contains("SUBJECT")) {
                subject = headerValue;
            }
            else if(headerType.contains("MESSAGE-ID")) {
                messageId = headerValue;
            }
            else if(headerType.contains("DATE")) {
                /* TODO: Do we need the date? */
            }
            else if(headerType.contains("CONTENT-TYPE")) {
                String[] contentTypeParts = headerValue.split(";");
                contentType = contentTypeParts[0];
                // Extract the boundary if it exists
                for(int j=1, n=contentTypeParts.length; j<n; j++)
                {
                    if(contentTypeParts[j].contains("boundary")) {
                        boundary = contentTypeParts[j].split("boundary[\\s]*=", 2)[1].trim();
                    }
                }
            }
            else if(headerType.contains("CONTENT-TRANSFER-ENCODING")) {
                encoding = headerValue;
            }
            else {
                if(D) Log.w(TAG,"Skipping unknown header: " + headerType + " (" + header + ")");
            }
        }
        return null;
    }

    private void parseMmsMimePart(String partStr) {
        String[] parts = partStr.split("\r\n\r\n", 2); // Split the header from
                                                       // the body
        String body = null;
        if (parts.length != 2) {
            // body = partStr;
            throw new IllegalArgumentException(
                    "Mime part not formatted correctly: No Header");
        } else {
            body = parts[1];
        }
        String[] headers = parts[0].split("\r\n");
        MimePart newPart = null;
        String partEncoding = encoding; /* Use the overall encoding as default */

        for (String header : headers) {
            if (header.length() == 0)
                continue;

            if (header.trim() == "" || header.trim().equals("--")) // Skip empty
                                                                   // lines(the
                                                                   // \r\n
                                                                   // after the
                                                                   // boundary
                                                                   // tag) and
                                                                   // endBoundary
                                                                   // tags
                continue;
            String[] headerParts = header.split(":", 2);
            if (headerParts.length != 2) {
                throw new IllegalArgumentException(
                        "part-Header not Formatted correctly: " + header);
            }
            if (newPart == null) {
                if (V)
                    Log.v(TAG, "Add new MimePart\n");
                newPart = addMimePart();
            }
            String headerType = headerParts[0].toUpperCase();
            String headerValue = headerParts[1].trim();
            if (headerType.contains("CONTENT-TYPE")) {
                // TODO: extract charset? Only UTF-8 is allowed for TEXT typed
                // parts
                newPart.contentType = headerValue;
                Log.d(TAG, "*** CONTENT-TYPE: " + newPart.contentType);
            } else if (headerType.contains("CONTENT-LOCATION")) {
                // This is used if the smil refers to a file name in its src=
                newPart.contentLocation = headerValue;
                newPart.partName = headerValue;
            } else if (headerType.contains("CONTENT-TRANSFER-ENCODING")) {
                partEncoding = headerValue;
            } else if (headerType.contains("CONTENT-ID")) {
                // This is used if the smil refers to a cid:<xxx> in it's src=
                newPart.contentId = headerValue;
            } else if (headerType.contains("CONTENT-DISPOSITION")) {
                // This is used if the smil refers to a cid:<xxx> in it's src=
                newPart.contentDisposition = headerValue;
            } else {
                if (D)
                    Log.w(TAG, "Skipping unknown part-header: " + headerType
                            + " (" + header + ")");
            }
        }
        // Now for the body
        if (newPart != null)
           newPart.data = decodeBody(body, partEncoding);
    }
    private static String parseSubjectEmail(String body) {
       int pos = body.indexOf("Subject:");
       if (pos > 0) {
         int beginVersionPos = pos + (("Subject:").length());
         int endVersionPos = body.indexOf("\n", beginVersionPos);
         return body.substring(beginVersionPos, endVersionPos);
       } else {
         return "";
       }
   }

    private void parseMmsMimeBody(String body) {
        MimePart newPart = addMimePart();
        if(newPart != null) {
           newPart.data = decodeBody(body, encoding);
        }
    }

    private byte[] decodeBody(String body, String encoding) {
        if(encoding != null && encoding.toUpperCase().contains("BASE64")) {
            return Base64.decode(body, Base64.DEFAULT);
        } else {
            // TODO: handle other encoding types? - here we simply store the string data as bytes
            try {
                return body.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                // This will never happen, as UTF-8 is mandatory on Android platforms
            }
        }
        return null;
    }
    private static String parseContentTypeEmail(String bmsg, String boundary) {
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

    private static String parseBoundaryEmail(String body) {
      int pos = body.indexOf("boundary=\"");
      if (pos > 0) {
          int beginVersionPos = pos + (("boundary=\"").length());
          int endVersionPos = body.indexOf("\"", beginVersionPos);
          return body.substring(beginVersionPos, endVersionPos);
      } else {
          return null;
      }
    }
    @Override
    public void parseBodyEmail(String body) throws IllegalArgumentException {
    int beginVersionPos = -1;
    int rfc822Flag = 0;
    int mimeFlag = 0;
    int beginVersionPos1 = -1;
    String contentType;
    int pos1 = 0;
    //PARSE SUBJECT
    setSubject(parseSubjectEmail(body));
    //Parse Boundary
    String boundary = parseBoundaryEmail(body);
    if(boundary != null && !boundary.equalsIgnoreCase("")) {
        pos1 = body.indexOf("--"+boundary);
        mimeFlag = 1;
    }
    else {
        pos1 = body.indexOf("Date:");
        mimeFlag = 0;
    }
    int contentIndex = body.indexOf("Content-Type",pos1);
    if(contentIndex > 0) {
       contentType = parseContentTypeEmail(body, boundary);
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
               // throw new IllegalArgumentException("Ill-formatted bMessage, no empty line");
               // PTS: Instead of throwing Exception, return MSG
               int beginMsg = body.indexOf("BEGIN:MSG");
               if (beginMsg == -1) {
                   throw new IllegalArgumentException("Ill-formatted bMessage, no BEGIN:MSG");
               }
               //Last occurence of END:MSG
               int endMsg = body.lastIndexOf("END:MSG");
               if (endMsg == -1) {
                   throw new IllegalArgumentException("Ill-formatted bMessage, no END:MSG");
               }
               setEmailBody(body.substring(beginMsg + "BEGIN:MSG".length(), endMsg - CRLF.length()));
               break;
           } else {
               pos = next + CRLF.length();
           }
        }
    }
    if(beginVersionPos > 0) {
       int endVersionPos;
       if(rfc822Flag == 0){
          if(mimeFlag == 0) {
             //Last occurence of END:MSG
             endVersionPos = body.lastIndexOf("END:MSG") ;
             if (endVersionPos != -1) {
                 setEmailBody(body.substring(beginVersionPos, (endVersionPos - CRLF.length())));
             } else {
                 setEmailBody(body.substring(beginVersionPos));
             }
          } else {
             endVersionPos = (body.indexOf("--"+boundary+"--", beginVersionPos) - CRLF.length());
             try {
                setEmailBody(body.substring(beginVersionPos, endVersionPos));
             } catch (IndexOutOfBoundsException e) {
               throw new IllegalArgumentException("Ill-formatted bMessage, no end boundary");
             }
          }
       } else if(rfc822Flag == 1) {
          endVersionPos = (body.indexOf("--"+boundary+"--", beginVersionPos));
          try {
            body = body.substring(beginVersionPos, endVersionPos);
          } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Ill-formatted bMessage, no end boundary");
          }
          int pos2 = body.indexOf(CRLF) + CRLF.length();
          while (pos2 > 0) {
             if(body.startsWith(CRLF, pos2)) {
                beginVersionPos1 = pos2 + CRLF.length();
                break;
             } else {
               final int next = body.indexOf(CRLF, pos2);
               if (next == -1) {
                   throw new IllegalArgumentException("Ill-formatted bMessage, no empty line");
               } else {
                   pos2 = next + CRLF.length();
               }
             }
          }
          if(beginVersionPos1 > 0){
             setEmailBody(body.substring(beginVersionPos1));
          }
       }
    }
    Log.v(TAG, "fetch body Email NULL:");
    }

    private void parseMms(String message) {
        /* Overall strategy for decoding:
         * 1) split on first empty line to extract the header
         * 2) unfold and parse headers
         * 3) split on boundary to split into parts (or use the remaining as a part,
         *    if part is not found)
         * 4) parse each part
         * */
        String[] messageParts;
        String[] mimeParts;
        String remaining = null;
        String messageBody = null;
        message = message.replaceAll("\\r\\n[ \\\t]+", ""); // Unfold
        messageParts = message.split("\r\n\r\n", 2); // Split the header from the body
        if(messageParts.length != 2) {
            // Handle entire message as plain text
            messageBody = message;
        }
        else
        {
            remaining = parseMmsHeaders(messageParts[0]);
            // If we have some text not being a header, add it to the message body.
            if(remaining != null) {
                messageBody = remaining + messageParts[1];
            }
            else {
                messageBody = messageParts[1];
            }
        }
        if(boundary == null)
        {
            // If the boundary is not set, handle as non-multi-part
            parseMmsMimeBody(messageBody);
            setTextOnly(true);
            if(contentType == null)
                contentType = "text/plain";
            parts.get(0).contentType = contentType;
        }
        else
        {
            mimeParts = messageBody.split("--" + boundary.replaceAll("\"",""));
            for(int i = 0; i < mimeParts.length -1; i++) {
                String part = mimeParts[i];
                if (part != null && (part.length() > 0)) {
                    try {
                        parseMmsMimePart(part);
                    } catch (IllegalArgumentException e) {
                        Log.d(TAG, " part-Header not formatted correctly: " + e);
                    }
                }
            }
        }
    }

    /* Notes on SMIL decoding (from http://tools.ietf.org/html/rfc2557):
     * src="filename.jpg" refers to a part with Content-Location: filename.jpg
     * src="cid:1234@hest.net" refers to a part with Content-ID:<1234@hest.net>*/
    @Override
    public void parseMsgPart(String msgPart) {
        parseMms(msgPart);

    }

    @Override
    public void parseMsgInit() {
        // Not used for e-mail

    }

    @Override
    public byte[] encode() throws UnsupportedEncodingException {
        return encodeMms();
    }

}
