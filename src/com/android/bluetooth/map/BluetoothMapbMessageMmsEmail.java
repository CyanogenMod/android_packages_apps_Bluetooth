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

public class BluetoothMapbMessageMmsEmail extends BluetoothMapbMessage {

    private String mmsBody;
    /**
     * TODO: Determine the best way to store the MMS message content.
     * @param mmsBody
     */

    public static class MimePart {
        public long _id = INVALID_VALUE;   /* The _id from the content provider, can be used to sort the parts if needed */
        public String contentType = null;  /* The mime type, e.g. text/plain */
        public String contentId = null;
        public String partName = null;     /* e.g. text_1.txt*/
        public String charsetName = null;  /* This seems to be a number e.g. 106 for UTF-8 CharacterSets
                                                holds a method for the mapping. */
        public String fileName = null;     /* Do not seem to be used */
        public byte[] data = null;        /* The raw un-encoded data e.g. the raw jpeg data or the text.getBytes("utf-8") */

        public void encode(StringBuilder sb, String boundaryTag, boolean last) throws UnsupportedEncodingException {
            sb.append("--").append(boundaryTag);
            if(last)
                sb.append("--");
            sb.append("\r\n");
            if(contentType != null)
                sb.append("Content-Type: ").append(contentType);
            if(charsetName != null)
                sb.append("; ").append("charset=\"").append(charsetName).append("\"");
            sb.append("\r\n");
            if(partName != null)
                sb.append("Content-Location: ").append(partName).append("\r\n");
            if(data != null) {
                // If errata 4176 is adopted in the current form, the below is not allowed, Base64 should be used for text
                if(contentType.toUpperCase().contains("TEXT")) {
                    sb.append("Content-Transfer-Encoding: 8BIT\r\n\r\n"); // Add the header split empty line
                    sb.append(new String(data,"UTF-8")).append("\r\n");
                }
                else {
                    sb.append("Content-Transfer-Encoding: Base64\r\n\r\n"); // Add the header split empty line
                    sb.append(Base64.encodeToString(data, Base64.DEFAULT)).append("\r\n");
                }
            }
        }
    }
    private long date = INVALID_VALUE;
    private String subject = null;
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

    /* TODO:
     *  - create an encoder for the parts e.g. embedded in the mimePart class
     *  */
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
        this.subject = subject;
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
        //this.from.add(formatAddress(name, address));
        this.from.add(new Rfc822Token(name, address, null));
    }
    public ArrayList<Rfc822Token> getSender() {
        return from;
    }
    public void setSender(ArrayList<Rfc822Token> sender) {
        this.sender = sender;
    }
    public void addSender(String name, String address) {
        if(this.sender == null)
            this.sender = new ArrayList<Rfc822Token>(1);
        //this.sender.add(formatAddress(name, address));
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
        //this.to.add(formatAddress(name, address));
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
        //this.cc.add(formatAddress(name, address));
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
        //this.bcc.add(formatAddress(name, address));
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
        //this.replyTo.add(formatAddress(name, address));
        this.replyTo.add(new Rfc822Token(name, address, null));
    }
    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
    public String getMessageId() {
        return messageId;
    }
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
    public String getContentType() {
        return contentType;
    }
    public void updateCharset() {
        charset = null;
        for(MimePart part : parts) {
            if(part.contentType != null &&
               part.contentType.toUpperCase().contains("TEXT")) {
                charset = "UTF-8";
            }
        }
    }
    /**
     * Use this to format an address according to RFC 2822.
     * @param name
     * @param address
     * @return
     */
    public static String formatAddress(String name, String address) {
        StringBuilder sb = new StringBuilder();
        Boolean nameSet = false;
        if(name != null && !(name = name.trim()).equals("")) {
            sb.append(name.trim());
            nameSet = true;
        }
        if(address != null && !(address = address.trim()).equals(""))
        {
            if(nameSet == true)
                sb.append(":");
            sb.append("<").append(address).append(">");
        }
        // TODO: Throw exception of the string is larger than 996
        return sb.toString();
    }


    /**
     * Encode an address header, and perform folding if needed.
     * @param sb The stringBuilder to write to
     * @param headerName The RFC 2822 header name
     * @param addresses the reformatted address substrings to encode. Create
     * these using {@link formatAddress}
     */
    public void encodeHeaderAddresses(StringBuilder sb, String headerName,
            ArrayList<Rfc822Token> addresses) {
        /* TODO: Do we need to encode the addresses if they contain illegal characters */
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
            sb.append(address.toString()).append(";");
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
/*        If we are to use US-ASCII anyway, here are the code for it.
          if (subject != null){
            // Use base64 encoding for the subject, as it may contain non US-ASCII characters or other
            // illegal (RFC822 header), and android do not seem to have encoders/decoders for quoted-printables
            sb.append("Subject:").append("=?utf-8?B?");
            sb.append(Base64.encodeToString(subject.getBytes("utf-8"), Base64.DEFAULT));
            sb.append("?=\r\n");
        }*/
        if (subject != null)
            sb.append("Subject: ").append(subject).append("\r\n");
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
        if(messageId != null)
            sb.append("Message-Id: ").append(messageId).append("\r\n");
        if(contentType != null)
            sb.append("Content-Type: ").append(contentType).append("; boundary=").append(getBoundary());
        sb.append("\r\n\r\n"); // If no headers exists, we still need two CRLF, hence keep it out of the if above.
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
     * (hence neither base64 nor binary) there is not mechanism to embed the attachment in
     * the <bmessage-body-content>.
     *
     * UPDATE: Errata xxx allows the needed encoding typed inside the <bmessage-body-content>
     * including Base64 and Quoted Printables - hence it is possible to encode non-us-ascii
     * messages - e.g. pictures and utf-8 strings with non-us-ascii content.
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
        for(MimePart part : parts) {
            count++;
            part.encode(sb, getBoundary(), (count == parts.size()));
        }

        mmsBody = sb.toString();

        if(mmsBody != null) {
            String tmpBody = mmsBody.replaceAll("END:MSG", "/END\\:MSG"); // Replace any occurrences of END:MSG with \END:MSG
            bodyFragments.add(tmpBody.getBytes("UTF-8"));
        } else {
            bodyFragments.add(new byte[0]); // TODO: Is this allowed? (An empty message)
        }

        return encodeGeneric(bodyFragments);
    }

    private void parseMmsHeaders(String hdrPart) {
        String[] headers = hdrPart.split("\r\n");

        for(String header : headers) {
            if(header.trim() == "")
                continue;
            String[] headerParts = header.split(":",2);
            if(headerParts.length != 2)
                throw new IllegalArgumentException("Header not formatted correctly: " + header);
            String headerType = headerParts[0].toUpperCase();
            String headerValue = headerParts[1].trim();

            // Address headers
            // TODO: If this is empty, the MSE needs to fill it in
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
                /* XXX: Set date */
            }
            else if(headerType.contains("CONTENT-TYPE")) {
                String[] contentTypeParts = headerValue.split(";");
                contentType = contentTypeParts[0];
                // Extract the boundary if it exists
                for(int i=1, n=contentTypeParts.length; i<n; i++)
                {
                    if(contentTypeParts[i].contains("boundary")) {
                        boundary = contentTypeParts[i].split("boundary[\\s]*=", 2)[1].trim();
                    }
                }
            }
            else {
                if(D) Log.w(TAG,"Skipping unknown header: " + headerType + " (" + header + ")");
            }
        }
    }

    private void parseMmsMimePart(String partStr) {
        /**/
        String[] parts = partStr.split("\r\n\r\n", 2); // Split the header from the body
        if(parts.length != 2) {
            throw new IllegalArgumentException("Wrongly formatted email part - unable to locate header section");
        }
        String[] headers = parts[0].split("\r\n");
        MimePart newPart = addMimePart();
        String encoding = "";

        for(String header : headers) {
            if(header.length() == 0)
                continue;

            if(header.trim() == "" || header.trim().equals("--")) // Skip empty lines(the \r\n after the boundary tag) and endBoundary tags
                continue;
            String[] headerParts = header.split(":",2);
            if(headerParts.length != 2)
                throw new IllegalArgumentException("part-Header not formatted correctly: " + header);
            String headerType = headerParts[0].toUpperCase();
            String headerValue = headerParts[1].trim();
            if(headerType.contains("CONTENT-TYPE")) {
                // TODO: extract charset - as for
                newPart.contentType = headerValue;
                Log.d(TAG, "*** CONTENT-TYPE: " + newPart.contentType);
            }
            else if(headerType.contains("CONTENT-LOCATION")) {
                // This is used if the smil refers to a file name in its src=
                newPart.partName = headerValue;
            }
            else if(headerType.contains("CONTENT-TRANSFER-ENCODING")) {
                encoding = headerValue;
            }
            else if(headerType.contains("CONTENT-ID")) {
                // This is used if the smil refers to a cid:<xxx> in it's src=
                newPart.contentId = headerValue;
            }
            else {
                if(D) Log.w(TAG,"Skipping unknown part-header: " + headerType + " (" + header + ")");
            }
        }
        // Now for the body
        if(encoding.toUpperCase().contains("BASE64")) {
            newPart.data = Base64.decode(parts[1], Base64.DEFAULT);
        } else {
            // TODO: handle other encoding types? - here we simply store the string data as bytes
            try {
                newPart.data = parts[1].getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                // This will never happen, as UTF-8 is mandatory on Android platforms
            }
        }
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
        message = message.replaceAll("\\r\\n[ \\\t]+", ""); // Unfold
        messageParts = message.split("\r\n\r\n", 2); // Split the header from the body
        if(messageParts.length != 2) {
            throw new IllegalArgumentException("Wrongly formatted email message - unable to locate header section");
        }
        parseMmsHeaders(messageParts[0]);
        mimeParts = messageParts[1].split("--" + boundary);
        for(String part : mimeParts) {
            if (part != null && (part.length() > 0))
                parseMmsMimePart(part);
        }
    }

    /* Notes on SMIL decoding (from http://tools.ietf.org/html/rfc2557):
     * src="filename.jpg" refers to a part with Content-Location: filename.jpg
     * src="cid:1234@hest.net" refers to a part with Content-ID:<1234@hest.net>*/
    @Override
    public void parseMsgPart(String msgPart) {
        // TODO Auto-generated method stub
        parseMms(msgPart);

    }

    @Override
    public void parseMsgInit() {
        // TODO Auto-generated method stub

    }

    @Override
    public byte[] encode() throws UnsupportedEncodingException {
        return encodeMms();
    }

}
