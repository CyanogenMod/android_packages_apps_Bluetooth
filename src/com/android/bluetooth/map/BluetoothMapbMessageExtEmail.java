/*
* Copyright (c) 2015, The Linux Foundation. All rights reserved.
* Not a Contribution.
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
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

import com.android.bluetooth.map.BluetoothMapSmsPdu.SmsPdu;

import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;
import android.util.Base64;
import java.util.Random;
import android.util.Log;


public class BluetoothMapbMessageExtEmail extends BluetoothMapbMessageMime {

    protected static String TAG = "BluetoothMapbMessageExtEmail";
    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = Log.isLoggable(BluetoothMapService.LOG_TAG, Log.VERBOSE);
    private String mEmailBody = null;
    private static final String CRLF = "\r\n";

    public void setEmailBody(String emailBody) {
        this.mEmailBody = emailBody;
        this.mCharset = "UTF-8";
        this.mEncoding = "8bit";
    }

    public String getEmailBody() {
        return mEmailBody;
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

    private static String parseSubjectEmail(String body) {
    if(D) Log.d(TAG,"parseSubjectEmail");
       int pos = body.indexOf("Subject:");
       if (pos > 0) {
         int beginVersionPos = pos + (("Subject:").length());
         int endVersionPos = body.indexOf("\n", beginVersionPos);
         return body.substring(beginVersionPos, endVersionPos);
       } else {
         return "";
       }
   }

   public void parseBodyEmail(String body) throws IllegalArgumentException {
       if(D) Log.d(TAG,"parseBodyEmail");
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
       if (boundary != null && !boundary.equalsIgnoreCase("")) {
           pos1 = body.indexOf("--"+boundary);
           mimeFlag = 1;
       } else {
           pos1 = body.indexOf("Date:");
           mimeFlag = 0;
       }
       int contentIndex = body.indexOf("Content-Type",pos1);
       if (contentIndex > 0) {
           contentType = parseContentTypeEmail(body, boundary);
           if (contentType != null && contentType.trim().equalsIgnoreCase("message/rfc822")) {
               rfc822Flag = 1;
           }
       }
       int pos = body.indexOf(CRLF, pos1) + CRLF.length();
       while (pos > 0) {
           if (body.startsWith(CRLF, pos)) {
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
                   //Remove a '/' in all occurrences of <CRLF>'/END:MSG' as per New Spec.
                   body = body.replaceAll("\r\n([/]*)/END\\:MSG", "\r\n$1END:MSG");
                  //Last occurence of END:MSG
                   int endMsg = body.lastIndexOf("END:MSG");
                   if (endMsg == -1) {
                       throw new IllegalArgumentException("Ill-formatted bMessage, no END:MSG");
                   }
                   setEmailBody(body.substring(beginMsg + "BEGIN:MSG".length(),
                           endMsg - CRLF.length()));
                   break;
               } else {
                   pos = next + CRLF.length();
               }
           }
       }
       if (beginVersionPos > 0) {
           int endVersionPos;
           if (rfc822Flag == 0) {
               if (mimeFlag == 0) {
                   //Last occurence of END:MSG
                   endVersionPos = body.lastIndexOf("END:MSG") ;
                   if (endVersionPos != -1) {
                       setEmailBody(body.substring(beginVersionPos, (endVersionPos - CRLF.length())));
                   } else {
                       setEmailBody(body.substring(beginVersionPos));
                   }
               } else {
                   endVersionPos = (body.indexOf("--"+boundary+"--", beginVersionPos)
                                       - CRLF.length());
                   try {
                       setEmailBody(body.substring(beginVersionPos, endVersionPos));
                   } catch (IndexOutOfBoundsException e) {
                       throw new
                           IllegalArgumentException("Ill-formatted bMessage, no end boundary");
                   }
               }
           } else if (rfc822Flag == 1) {
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
                          throw new
                              IllegalArgumentException("Ill-formatted bMessage, no empty line");
                      } else {
                          pos2 = next + CRLF.length();
                      }
                   }
               }
               if (beginVersionPos1 > 0) {
                   setEmailBody(body.substring(beginVersionPos1));
               }
           }
       }
       Log.v(TAG, "fetch body Email NULL:");
   }

   public void encodeEmailHeaders(StringBuilder sb) throws UnsupportedEncodingException {
       /* TODO: From RFC-4356 - about the RFC-(2)822 headers:
        *    "Current Internet Message format requires that only 7-bit US-ASCII
        *     characters be present in headers.  Non-7-bit characters in an address
        *     domain must be encoded with [IDN].  If there are any non-7-bit
        *     characters in the local part of an address, the message MUST be
        *     rejected.  Non-7-bit characters elsewhere in a header MUST be encoded
        *     according to [Hdr-Enc]."
        *    We need to add the address encoding in encodeHeaderAddresses, but it is not
        *    straight forward, as it is unclear how to do this.  */
       if (getDate() != INVALID_VALUE)
           sb.append("Date: ").append(getDateString()).append("\r\n");
       /* According to RFC-2822 headers must use US-ASCII, where the MAP specification states
        * UTF-8 should be used for the entire <bmessage-body-content>. We let the MAP specification
        * take precedence above the RFC-2822.
        */
       if (getSubject() != null)
           sb.append("Subject: ").append(getSubject()).append("\r\n");
       if (getFrom() == null)
           sb.append("From: \r\n");
       if (getFrom() != null)
           encodeHeaderAddresses(sb, "From: ", getFrom()); // This includes folding if needed.
       if (getSender() != null)
           encodeHeaderAddresses(sb, "Sender: ", getSender()); // This includes folding if needed.
       /* For MMS one recipient(to, cc or bcc) must exists, if none: 'To:  undisclosed-
        * recipients:;' could be used.
        */
       if (getTo() == null && getCc() == null && getBcc() == null)
           sb.append("To:  undisclosed-recipients:;\r\n");
       if (getTo() != null)
           encodeHeaderAddresses(sb, "To: ", getTo()); // This includes folding if needed.
       if (getCc() != null)
           encodeHeaderAddresses(sb, "Cc: ", getCc()); // This includes folding if needed.
       if (getBcc() != null)
           encodeHeaderAddresses(sb, "Bcc: ", getBcc()); // This includes folding if needed.
       if (getReplyTo() != null) // This includes folding if needed.
           encodeHeaderAddresses(sb, "Reply-To: ", getReplyTo());
       if (getIncludeAttachments() == true) {
           if (getMessageId() != null)
               sb.append("Message-Id: ").append(getMessageId()).append("\r\n");
           if (getContentType() != null)
               sb.append("Content-Type: ").append(getContentType())
                   .append("; boundary=").append(getBoundary()).append("\r\n");
        } else {
            // Attachment fetaure not supported . Append below MIME Headers.
            sb.append("Mime-Version: 1.0").append("\r\n");
            sb.append(
                  "Content-Type: multipart/mixed; boundary=\""+ getBoundary() + "\"")
                   .append("\r\n");
            sb.append("Content-Transfer-Encoding: 8bit").append("\r\n");
        }
        // If no headers exists, we still need two CRLF, hence keep it out of the if above.
        sb.append("\r\n");
    }

   public byte[] encodeEmail() throws UnsupportedEncodingException
   {
       if (V) Log.v(TAG, "Inside encodeEmail ");
       ArrayList<byte[]> bodyFragments = new ArrayList<byte[]>();
       StringBuilder sb = new StringBuilder ();
       int count = 0;
       String emailBody;
       encodeEmailHeaders(sb);
       sb.append("MIME Message").append("\r\n");
       sb.append("--" + getBoundary()).append("\r\n");
       Log.v(TAG, "after encode header sb is "+ sb.toString());
       if (parts != null) {
           if (getIncludeAttachments() == false) {
              for (MimePart part : parts) {
                  /* We call encode on all parts, to include a tag,
                   * where an attachment is missing. */
                  part.encodePlainText(sb);
                  sb.append("--" + getBoundary() + "--").append("\r\n");
              }
          } else {
              for (MimePart part : parts) {
                  count++;
                  part.encode(sb, getBoundary(), (count == parts.size()));
              }
          }
       } else {
              Log.e(TAG, " parts is null.");
       }

       emailBody = sb.toString();
       if (V) Log.v(TAG, "emailBody is "+emailBody);

       if (emailBody != null) {
           // Replace any occurrences of END:MSG with \END:MSG
           String tmpBody = emailBody.replaceAll("END:MSG", "/END\\:MSG");
           bodyFragments.add(tmpBody.getBytes("UTF-8"));
       } else {
           Log.e(TAG, "Email has no body - this should not be possible");
           bodyFragments.add(new byte[0]); // An empty message - this should not be possible
       }
       return encodeGeneric(bodyFragments);
   }

}
