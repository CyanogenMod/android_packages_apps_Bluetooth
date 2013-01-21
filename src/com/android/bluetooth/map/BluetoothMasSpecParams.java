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

package com.android.bluetooth.map;

public final class BluetoothMasSpecParams {

    public static final int MAS_TAG_MAX_LIST_COUNT            = 0x01;
    public static final int MAS_TAG_LIST_START_OFFSET         = 0x02;
    public static final int MAS_TAG_FILTER_MESSAGE_TYPE       = 0x03;
    public static final int MAS_TAG_FILTER_PERIOD_BEGIN       = 0x04;
    public static final int MAS_TAG_FILTER_PERIOD_END         = 0x05;
    public static final int MAS_TAG_FILTER_READ_STATUS        = 0x06;
    public static final int MAS_TAG_FILTER_RECIPIENT          = 0x07;
    public static final int MAS_TAG_FILTER_ORIGINATOR         = 0x08;
    public static final int MAS_TAG_FILTER_PRIORITY           = 0x09;
    public static final int MAS_TAG_ATTACHMENT                = 0x0A;
    public static final int MAS_TAG_TRANSPARENT               = 0x0B;
    public static final int MAS_TAG_RETRY                     = 0x0C;
    public static final int MAS_TAG_NEW_MESSAGE               = 0x0D;
    public static final int MAS_TAG_NOTIFICATION_STATUS       = 0x0E;
    public static final int MAS_TAG_MAS_INSTANCE_ID           = 0x0F;
    public static final int MAS_TAG_PARAMETER_MASK            = 0x10;
    public static final int MAS_TAG_FOLDER_LISTING_SIZE       = 0x11;
    public static final int MAS_TAG_MESSAGE_LISTING_SIZE     = 0x12;
    public static final int MAS_TAG_SUBJECT_LENGTH            = 0x13;
    public static final int MAS_TAG_CHARSET                   = 0x14;
    public static final int MAS_TAG_FRACTION_REQUEST          = 0x15;
    public static final int MAS_TAG_FRACTION_DELIVER          = 0x16;
    public static final int MAS_TAG_STATUS_INDICATOR          = 0x17;
    public static final int MAS_TAG_STATUS_VALUE              = 0x18;
    public static final int MAS_TAG_MSE_TIME                  = 0x19;

    public static final int MAS_TAG_MAX_LIST_COUNT_LEN            = 0x02;
    public static final int MAS_TAG_LIST_START_OFFSET_LEN         = 0x02;
    public static final int MAS_TAG_SUBJECT_LENGTH_LEN            = 0x01;
    public static final int MAS_TAG_FILTER_MESSAGE_TYPE_LEN       = 0x01;
    public static final int MAS_TAG_FILTER_READ_STATUS_LEN        = 0x01;
    public static final int MAS_TAG_FILTER_PRIORITY_LEN           = 0x01;
    public static final int MAS_TAG_PARAMETER_MASK_LEN            = 0x04;
    public static final int MAS_TAG_ATTACHMENT_LEN                = 0x01;
    public static final int MAS_TAG_TRANSPARENT_LEN               = 0x01;
    public static final int MAS_TAG_RETRY_LEN                     = 0x01;
    public static final int MAS_TAG_NEW_MESSAGE_LEN               = 0x01;
    public static final int MAS_TAG_NOTIFICATION_STATUS_LEN       = 0x01;
    public static final int MAS_TAG_MAS_INSTANCE_ID_LEN           = 0x01;
    public static final int MAS_TAG_FOLDER_LISTING_SIZE_LEN       = 0x02;
    public static final int MAS_TAG_MESSAGE_LISTING_SIZE_LEN     = 0x02;
    public static final int MAS_TAG_CHARSET_LEN                   = 0x01;
    public static final int MAS_TAG_FRACTION_REQUEST_LEN         = 0x01;
    public static final int MAS_TAG_FRACTION_DELIVER_LEN          = 0x01;
    public static final int MAS_TAG_STATUS_INDICATOR_LEN          = 0x01;
    public static final int MAS_TAG_STATUS_VALUE_LEN              = 0x01;
    public static final int MAS_TAG_MSE_TIME_LEN                  = 0x14;

    public static final int MAS_DEFAULT_MAX_LIST_COUNT = 1024;
    public static final int MAS_DEFAULT_SUBJECT_LENGTH = 255;
    public static final int MAS_DEFAULT_PARAMETER_MASK = 0xFFFF;

    public static final int MAS_FRACTION_REQUEST_NOT_SET = 0x02;

    public static final int MAS_TAG_MAX_LIST_COUNT_MIN_VAL        = 0x0;
    public static final int MAS_TAG_MAX_LIST_COUNT_MAX_VAL        = 0xFFFF;
    public static final int MAS_TAG_LIST_START_OFFSET_MIN_VAL     = 0x00;
    public static final int MAS_TAG_LIST_START_OFFSET_MAX_VAL     = 0xFFFF;
    public static final int MAS_TAG_SUBJECT_LENGTH_MIN_VAL        = 0x01;
    public static final int MAS_TAG_SUBJECT_LENGTH_MAX_VAL        = 0xFF;
    public static final int MAS_TAG_FILTER_MESSAGE_TYPE_MIN_VAL   = 0x00;
    public static final int MAS_TAG_FILTER_MESSAGE_TYPE_MAX_VAL   = 0x0F;
    public static final int MAS_TAG_FILTER_READ_STATUS_MIN_VAL    = 0x00;
    public static final int MAS_TAG_FILTER_READ_STATUS_MAX_VAL    = 0x02;
    public static final int MAS_TAG_FILTER_PRIORITY_MIN_VAL       = 0x00;
    public static final int MAS_TAG_FILTER_PRIORITY_MAX_VAL       = 0x02;
    public static final int MAS_TAG_PARAMETER_MASK_MIN_VAL        = 0x0;
    public static final int MAS_TAG_PARAMETER_MASK_MAX_VAL        = 0xFFFF;
    public static final int MAS_TAG_ATTACHMENT_MIN_VAL            = 0x00;
    public static final int MAS_TAG_ATTACHMENT_MAX_VAL            = 0x01;
    public static final int MAS_TAG_TRANSPARENT_MIN_VAL           = 0x00;
    public static final int MAS_TAG_TRANSPARENT_MAX_VAL           = 0x01;
    public static final int MAS_TAG_RETRY_MIN_VAL                 = 0x00;
    public static final int MAS_TAG_RETRY_MAX_VAL                 = 0x01;
    public static final int MAS_TAG_NEW_MESSAGE_MIN_VAL           = 0x00;
    public static final int MAS_TAG_NEW_MESSAGE_MAX_VAL           = 0x01;
    public static final int MAS_TAG_NOTIFICATION_STATUS_MIN_VAL   = 0x00;
    public static final int MAS_TAG_NOTIFICATION_STATUS_MAX_VAL   = 0x01;
    public static final int MAS_TAG_MAS_INSTANCE_ID_MIN_VAL       = 0x00;
    public static final int MAS_TAG_MAS_INSTANCE_ID_MAX_VAL       = 0xFF;
    public static final int MAS_TAG_FOLDER_LISTING_SIZE_MIN_VAL   = 0x00;
    public static final int MAS_TAG_FOLDER_LISTING_SIZE_MAX_VAL   = 0xFFFF;
    public static final int MAS_TAG_MESSAGE_LISTING_SIZE_MIN_VAL  = 0x00;
    public static final int MAS_TAG_MESSAGE_LISTING_SIZE_MAX_VAL  = 0xFFFF;
    public static final int MAS_TAG_CHARSET_MIN_VAL               = 0x00;
    public static final int MAS_TAG_CHARSET_MAX_VAL               = 0x01;
    public static final int MAS_TAG_FRACTION_REQUEST_MIN_VAL      = 0x00;
    public static final int MAS_TAG_FRACTION_REQUEST_MAX_VAL      = 0x01;
    public static final int MAS_TAG_FRACTION_DELIVER_MIN_VAL      = 0x00;
    public static final int MAS_TAG_FRACTION_DELIVER_MAX_VAL      = 0x01;
    public static final int MAS_TAG_STATUS_INDICATOR_MIN_VAL      = 0x00;
    public static final int MAS_TAG_STATUS_INDICATOR_MAX_VAL      = 0x01;
    public static final int MAS_TAG_STATUS_VALUE_MIN_VAL          = 0x00;
    public static final int MAS_TAG_STATUS_VALUE_MAX_VAL          = 0x01;
};

