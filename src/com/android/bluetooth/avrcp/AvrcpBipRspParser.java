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

import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import android.provider.MediaStore;
import android.net.Uri;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.UnsupportedEncodingException;
import android.content.ContentUris;
import android.content.ContentResolver;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Random;
import java.util.HashMap;
import android.content.Context;
import java.io.IOException;
import java.io.OutputStream;
import org.xmlpull.v1.XmlSerializer;
import java.io.StringWriter;
import com.android.internal.util.FastXmlSerializer;
import java.io.ByteArrayInputStream;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.InputStream;
import android.graphics.YuvImage;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import android.os.Environment;
import android.media.ExifInterface;
import java.util.Objects;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import java.lang.NumberFormatException;

public class AvrcpBipRspParser {
    private final String TAG = "AvrcpBipRspParser";

    private static final boolean D = true;
    private static final boolean V = AvrcpBipRsp.V;
    private Context mContext;
    private static HashMap<String, String> mArtHandleMap = new HashMap<String, String>();
    private static HashMap<String, AvrcpBipRspCoverArtAttributes> mCoverArtAttributesMap =
        new HashMap<String, AvrcpBipRspCoverArtAttributes>();
    private static long mAlbumId = -1;
    private static final String mAlbumUri = "content://media/external/audio/albumart";
    private static String mArtPath;
    private static int BIP_THUMB_WIDTH = 200;
    private static int BIP_THUMB_HEIGHT = 200;
    private static int MIN_SUPPORTED_WIDTH = 100;
    private static int MIN_SUPPORTED_HEIGHT = 100;
    private static int MAX_SUPPORTED_WIDTH = 1280;
    private static int MAX_SUPPORTED_HEIGHT = 1080;
    private static int MAX_IMG_HANDLE = 10000000;
    private static int COMPRESSION_QUALITY_HIGH = 75;
    /* Constants used for converting RGB -> YUV */
    private static final int COEFF1 = 19595;
    private static final int COEFF2 = 38470;
    private static final int COEFF3 = 7471;
    private static final int COEFF4 = -11059;
    private static final int COEFF5 = -21709;
    private static final int COEFF6 = 32768;
    private static final int COEFF7 = 32768;
    private static final int COEFF8 = -27439;
    private static final int COEFF9 = -5329;
    /* Tmp path for storing file before compressing and sending to obex.
     * This file is deleted after the operation */
    private String mTmpFilePath = Environment.getExternalStorageDirectory() +
                "/tmpBtBip.jpg";

    public AvrcpBipRspParser(Context context) {
        mContext = context;
        mArtHandleMap.clear();
        mCoverArtAttributesMap.clear();
         /* Delete the tmp file if exist */
        File f = new File(mTmpFilePath);
        if (f != null && f.isFile() && f.exists()) {
            // delete the file now, may be due to battery removal or some exception
            f.delete();
        }
    }

    private class AvrcpBipRspCoverArtAttributes {
        private String mVersion = "1.0";
        private String mNativeEncoding = "";
        private String mNativePixel = "";
        private String mNativeSize = "";
        private long mAlbumId = 0;
        private String mArtPath = null;
        private int mWidth = 0;
        private int mHeight = 0;
        public void setAlbumId(long albumId) {
            mAlbumId = albumId;
        }
        public void setWidth(int width) {
            mWidth = width;
        }
        public void setHeight(int height) {
            mHeight = height;
        }
        public void setArtPath(String artPath) {
            mArtPath = artPath;
        }
        public void setNativeEncoding(String nativeEncoding) {
            mNativeEncoding = nativeEncoding;
        }
        public void setNativePixel(String nativePixel) {
            mNativePixel = nativePixel;
        }
        public void setNativeSize(String nativeSize) {
            mNativeSize = nativeSize;
        }
        public long getAlbumId() {
            return mAlbumId;
        }
        public int getWidth() {
            return mWidth;
        }
        public int getHeigth() {
            return mHeight;
        }
        public String getArtPath() {
            return mArtPath;
        }
        public String getVersion() {
            return mVersion;
        }
        public String getNativeEncoding() {
            return mNativeEncoding;
        }
        public String getNativePixel() {
            return mNativePixel;
        }
        public String getNativeSize() {
            return mNativeSize;
        }
    };

    private void rgb2yuv(int rgb, byte[] convArray) {
        int a = Color.red(rgb);
        int b = Color.green(rgb);
        int c = Color.blue(rgb);
        convArray[0] = (byte) ((COEFF1 * a + COEFF2 * b + COEFF3 * c) >> 16);
        convArray[1] = (byte) (((COEFF4 * a + COEFF5 * b + COEFF6 * c) >> 16) + 128);
        convArray[2] = (byte) (((COEFF7 * a + COEFF8 * b + COEFF9 * c) >> 16) + 128);
    }

    private byte[] convertToYuv(int[] rgb, int w, int h) {
        byte[] convArray = new byte[w * h * ImageFormat.getBitsPerPixel(ImageFormat.YUY2)];
        byte[] col0 = new byte[3];
        byte[] co11 = new byte[3];
        for (int i = 0; i < h; ++i) {
            for (int j = 0; j < w; j += 2) {
                int id = i * w + j;
                rgb2yuv(rgb[id], col0);
                rgb2yuv(rgb[id + 1], co11);
                int index = id / 2 * 4;
                convArray[index] = col0[0];
                convArray[index + 1] = col0[1];
                convArray[index + 2] = co11[0];
                convArray[index + 3] = col0[2];
           }
        }

        return convArray;
    }

    private Bitmap getScaledBitmap(long album_id, int w, int h) {
        ContentResolver res = mContext.getContentResolver();

        if (res == null)
            return null;

        Log.d(TAG, "Enter getScaledBitmap");
        Uri uri = ContentUris.withAppendedId(Uri.parse(mAlbumUri), album_id);
        if (uri != null) {
            ParcelFileDescriptor fd = null;
            try {
                fd = res.openFileDescriptor(uri, "r");
                int sampleSize = 1;
                BitmapFactory.Options opt = new BitmapFactory.Options();

                opt.inJustDecodeBounds = true;
                opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
                opt.inDither = false;
                BitmapFactory.decodeFileDescriptor(
                fd.getFileDescriptor(), null, opt);
                int nextWidth = opt.outWidth >> 1;
                int nextHeight = opt.outHeight >> 1;
                while (nextWidth>w && nextHeight>h) {
                    sampleSize <<= 1;
                    nextWidth >>= 1;
                    nextHeight >>= 1;
                }

                opt.inSampleSize = sampleSize;
                opt.inJustDecodeBounds = false;
                Bitmap b = BitmapFactory.decodeFileDescriptor(
                fd.getFileDescriptor(), null, opt);

                if (b != null) {
                    // rescale to exactly the size we need
                    if (opt.outWidth != w || opt.outHeight != h) {
                        Bitmap tmp = Bitmap.createScaledBitmap(b, w, h, true);
                        if (tmp != b) b.recycle();
                        b = tmp;
                    }
                }
                return b;
            } catch (FileNotFoundException e) {
                Log.e(TAG, "getScaledBitmap: File not found");
            } finally {
                try {
                    if (fd != null)
                        fd.close();
                } catch (IOException e) {
                    Log.e(TAG, "getScaledBitmap: exception in close file");
                }
            }
        }
        Log.d(TAG, "Exit getScaledBitmap");
        return null;
    }

    private String getArtHandleFromAlbum (String AlbumName) {
        String artHandle = null;

        String where = MediaStore.Audio.Media.ALBUM + "=?";

        String whereVal [] = { AlbumName };

        String orderBy = MediaStore.Audio.Albums.DEFAULT_SORT_ORDER;

        Cursor cursor = null;

        Log.d(TAG, "Enter getArtHandleFromAlbum");
        try {
            cursor = mContext.getContentResolver().query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, null,
            where, whereVal, orderBy);

            if (D) Log.v(TAG, "getArtHandleFromAlbum");
            if (cursor != null && cursor.moveToFirst()) {
                int mAlbumArtIndex;
                int mAlbumIndex;
                int mArtistIndex;
                int mAlbumIdIndex;
                int mNumOfSongsIndex;
                mAlbumArtIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM_ART);
                mArtPath = cursor.getString(mAlbumArtIndex);
                if (V) Log.v(TAG, "artPath = " + mArtPath);
                if (mArtPath != null)
                    artHandle = mArtPath.substring(mArtPath.lastIndexOf("/") + 1);
                mAlbumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM);
                String album = cursor.getString(mAlbumIndex);
                if (V) Log.v(TAG, "album = " + album);
                mArtistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ARTIST);
                String atrist = cursor.getString(mArtistIndex);
                if (V) Log.v(TAG, "artist = " + atrist);
                mAlbumId = cursor.getLong(0);
                if (V) Log.v(TAG, "albumId = " + mAlbumId);
                mNumOfSongsIndex = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Albums.NUMBER_OF_SONGS);
                String numOfSongs = cursor.getString(mNumOfSongsIndex);
                if (V) Log.v(TAG, "numOfSongs = " + numOfSongs);
            }
        } catch (IllegalArgumentException e) {
            if (V) Log.v(TAG, "getArtHandleFromAlbum: exception = " + e);
        } finally {
            if (cursor != null)
                cursor.close();
        }
        if (V) Log.v(TAG, "artHandle = " + artHandle);
        return artHandle;
    }

    private String getImgHandleFromArtHandle(String artHandle) {
        if (V) Log.d(TAG,"getImgHandleFromArtHandle: artHandle = " + artHandle);
        if (V) Log.v(TAG, "map = " + mArtHandleMap);
        if (mArtHandleMap.containsKey(artHandle) == false) {
            Random rnd = new Random();
            int val = rnd.nextInt(MAX_IMG_HANDLE);
            String imgHandle = String.format("%07d", val);
            if (V) Log.v(TAG,"imgHandle = " + imgHandle);
            AvrcpBipRspCoverArtAttributes coverArtAttributes = new AvrcpBipRspCoverArtAttributes();
            coverArtAttributes.setAlbumId(mAlbumId);
            coverArtAttributes.setArtPath(mArtPath);
            if (V) Log.v(TAG,"getImgHandleFromArtHandle: storing artPath = " +
                mArtPath + " and albumID = " + mAlbumId+ " for imgHandle = " +
                imgHandle);
            mCoverArtAttributesMap.put(imgHandle, coverArtAttributes);
            mArtHandleMap.put(artHandle, imgHandle);
            return imgHandle;
        } else {
            if (V) Log.v(TAG,"entry already present in map, imgHandle = " +
                mArtHandleMap.get(artHandle));
            return mArtHandleMap.get(artHandle);
        }
    }

    private void updateExifHeader(String imgHandle, int width, int height) {

        AvrcpBipRspCoverArtAttributes artAttributes;
        artAttributes = mCoverArtAttributesMap.get(imgHandle);

        if (artAttributes == null)
            return;

        Log.d(TAG, "Enter updateExifHeader");
        String artPath = artAttributes.getArtPath();

        if (artPath == null)
            return;

        try {
            ExifInterface oldexif = new ExifInterface(artPath);
            ExifInterface newexif = new ExifInterface(mTmpFilePath);

            if (oldexif == null || newexif == null) {
                Log.e(TAG,"updateExifHeader: oldexif = " + oldexif +
                    " newexif = " + newexif);
                return;
            }

            //Need to update it, with your new height width
            newexif.setAttribute("ImageWidth", "" + width);
            newexif.setAttribute("ImageLength", "" + height);

            // copy other fileds if present as it is
            if (oldexif.getAttribute("DateTime") != null) {
                newexif.setAttribute("DateTime",
                        oldexif.getAttribute("DateTime"));
            }
            if (oldexif.getAttribute("Flash") != null) {
                newexif.setAttribute("Flash",
                        oldexif.getAttribute("Flash"));
            }

            if (oldexif.getAttribute("Make") != null) {
                newexif.setAttribute("Make",
                        oldexif.getAttribute("Make"));
            }
            if (oldexif.getAttribute("Model") != null) {
                newexif.setAttribute("Model",
                        oldexif.getAttribute("Model"));
            }
            if (oldexif.getAttribute("Orientation") != null) {
                newexif.setAttribute("Orientation",
                        oldexif.getAttribute("Orientation"));
            }
            newexif.saveAttributes();
        } catch (IOException e) {
            Log.e(TAG,"readImgProperties: exception" + e);
        }
        Log.d(TAG, "Exit updateExifHeader");
     }

    private void readImgProperties(String imgHandle) {
        if (D) Log.d(TAG,"readImgProperties");
        AvrcpBipRspCoverArtAttributes artAttributes;
        artAttributes = mCoverArtAttributesMap.get(imgHandle);

        if (artAttributes == null)
            return;

        long albumId = artAttributes.getAlbumId();
        String artPath = artAttributes.getArtPath();
        if (V) Log.v(TAG,"artPath = " + artPath);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        //Returns null, sizes are in the options variable
        BitmapFactory.decodeFile(artPath, options);
        if (D) Log.d(TAG,"width = " + options.outWidth + " height = " + options.outHeight
            + " type = " + options.outMimeType);
        AvrcpBipRspCoverArtAttributes coverArtAttributes = new AvrcpBipRspCoverArtAttributes();
        coverArtAttributes.setAlbumId(albumId);
        coverArtAttributes.setArtPath(artPath);
        coverArtAttributes.setWidth(options.outWidth);
        coverArtAttributes.setHeight(options.outHeight);
        /*Get the cover art file size */
        File f = new File(artPath);
        if (f != null && f.isFile() && f.exists()) {
            if (D) Log.v(TAG, "readImgProperties: File Size = " + f.length());
            coverArtAttributes.setNativeSize(Objects.toString(f.length(), null));
        }
        if (options.outMimeType != null) {
            coverArtAttributes.setNativeEncoding(
            options.outMimeType.substring(options.outMimeType.lastIndexOf("/") + 1)
            .toUpperCase());
        } else {
            // DEFAULT encoding as JPEG
            coverArtAttributes.setNativeEncoding("JPEG");
        }
        //coverArtAttributes.setNativeSize(Integer.toString(size));
        coverArtAttributes.setNativePixel(options.outWidth + "*" + options.outHeight);
        if (V) Log.v(TAG,"storing artPath = " + artPath + " and albumID = " + albumId +
            " for imgHandle = " + imgHandle);
        mCoverArtAttributesMap.put(imgHandle, coverArtAttributes);
        Log.d(TAG,"Exit readImgProperties");
    }

    private class AvrcpBipRspImgDescriptor {
        public String mVersion;
        public String mEncoding;
        public String mPixel;
        public String mSize;
        public String mMaxSize;
        public String mTransformation;
        public static final String DEFAULT_VERSION = "1.0";
        private String DEFAULT_ENCODING = "JPEG";
        private String DEFAULT_PIXEL = "";

        private AvrcpBipRspImgDescriptor(HashMap<String, String> attrs) {
            mVersion = attrs.get("version");
            mEncoding = attrs.get("encoding");
            mPixel = attrs.get("pixel");
            mSize = attrs.get("size");
            mMaxSize = attrs.get("maxsize");
            mTransformation = attrs.get("transformation");
        }

        public AvrcpBipRspImgDescriptor() {
            mVersion = DEFAULT_VERSION;
            mEncoding = DEFAULT_ENCODING;
            mPixel = DEFAULT_PIXEL;
        }
    };

    private AvrcpBipRspImgDescriptor parseImgDescXml(String imgDescXml) {
        AvrcpBipRspImgDescriptor ev = null;

        if (imgDescXml == null)
            return ev;

        if (D) Log.d(TAG, "parseImgDescXml");

        try {
            InputStream is = new ByteArrayInputStream(imgDescXml.getBytes("UTF-8"));
            try {
                XmlPullParser imgDescParser = XmlPullParserFactory.newInstance().newPullParser();
                imgDescParser.setInput(is, "UTF-8");

                HashMap<String, String> attrs = new HashMap<String, String>();
                int event = imgDescParser.getEventType();
                while (event != XmlPullParser.END_DOCUMENT) {
                    switch (event) {
                        case XmlPullParser.START_TAG:
                            if (imgDescParser.getName().equals("image-descriptor")) {
                                if (V) Log.v(TAG, "image-descriptor version: " +
                                    imgDescParser.getAttributeValue(0));
                                attrs.put(imgDescParser.getAttributeName(0),
                                    imgDescParser.getAttributeValue(0));
                            }

                            if (imgDescParser.getName().equals("image")) {
                                for (int i = 0; i < imgDescParser.getAttributeCount(); i++) {
                                    attrs.put(imgDescParser.getAttributeName(i),
                                        imgDescParser.getAttributeValue(i));
                                    if (V) Log.v(TAG, "image " + imgDescParser.getAttributeName(i) +
                                        " " + imgDescParser.getAttributeValue(i));
                                }
                                ev = new AvrcpBipRspImgDescriptor(attrs);
                                // return immediately, only one event should be here
                                return ev;
                            }
                            break;
                    }

                    event = imgDescParser.next();
                }
                if (V) Log.v(TAG, "attrs " + attrs);

            } catch (XmlPullParserException e) {
                Log.e(TAG, "Error when parsing XML", e);
            } catch (IOException e) {
                Log.e(TAG, "I/O error when parsing XML", e);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid event received", e);
            }
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "UnsupportedEncodingException", e);
        }
        if (D) Log.d(TAG, "parseImgDescXml returning " + ev);
        Log.d(TAG,"Exit parseImgDescXml");
        return ev;
    }

    private AvrcpBipRspImgDescriptor validateImgDescriptor(AvrcpBipRspImgDescriptor imgDesc) {
        int width = 0, height = 0;
        int minWidth = 0, minHeight = 0;
        int maxWidth = 0, maxHeigth = 0;
        AvrcpBipRspImgDescriptor imgDes = imgDesc;

        Log.d(TAG,"Enter validateImgDescriptor");
        /* check if the pixel field is range or discrete */
        if ((imgDesc.mPixel.indexOf("-") == -1)) {
            /* Check if the pixel fields contains only 1 "*". */
            if ((imgDesc.mPixel.indexOf("*") == -1) ||
                (imgDesc.mPixel.indexOf("*") != imgDesc.mPixel.lastIndexOf("*"))) {
                Log.e(TAG, "validateImgDescriptor: pixel field is invalid: " +  imgDesc.mPixel);
                return null;
            }
            try {
                width = Integer.parseInt(imgDesc.mPixel.substring(
                        0, imgDesc.mPixel.indexOf("*")));
                height = Integer.parseInt(imgDesc.mPixel.substring(
                        imgDesc.mPixel.lastIndexOf("*") + 1));
                if ((width < MIN_SUPPORTED_WIDTH || width > MAX_SUPPORTED_WIDTH) ||
                    ((height < MIN_SUPPORTED_HEIGHT || height > MAX_SUPPORTED_HEIGHT))) {
                    Log.e(TAG, "validateImgDescriptor: pixel width or height is " +
                        "invalid: " +  imgDesc.mPixel);
                    return null;
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "exception while parsing pixel: " + imgDesc.mPixel);
                return null;
            }
        } else {
            /* check if the pixel range contains only single "-" */
            if (imgDesc.mPixel.indexOf("-") != imgDesc.mPixel.lastIndexOf("-")) {
                Log.e(TAG, "validateImgDescriptor: Invalid pixel range");
                return null;
            }
            /* Pixel is specified as range from client, parse it */
            /* Split the strings before and after "-" */
            String minRange = imgDesc.mPixel.substring(0, imgDesc.mPixel.indexOf("-"));
            String maxRange = imgDesc.mPixel.substring(imgDesc.mPixel.indexOf("-") + 1);
            Log.e(TAG, "minRange: " +  minRange + " maxRange: " + maxRange);
            try {
                minWidth = Integer.parseInt(minRange.substring(0,
                    minRange.indexOf("*")));
                minHeight = Integer.parseInt(minRange.substring(
                    minRange.lastIndexOf("*") + 1));
                maxWidth = Integer.parseInt(maxRange.substring(
                    0, maxRange.indexOf("*")));
                maxHeigth = Integer.parseInt(maxRange.substring(
                    maxRange.lastIndexOf("*") + 1));
                if ((maxWidth < minWidth || maxHeigth < minHeight)) {
                    Log.e(TAG, "validateImgDescriptor: Invalid pixel range: " + minRange + " - " +
                        maxRange);
                    return null;
                } else if ((maxWidth < MIN_SUPPORTED_WIDTH || minWidth > MAX_SUPPORTED_WIDTH) ||
                    ((maxHeigth < MIN_SUPPORTED_HEIGHT || minHeight > MAX_SUPPORTED_HEIGHT))) {
                    Log.e(TAG, "validateImgDescriptor: Invalid pixel range: " + minRange + " - " +
                        maxRange);
                    return null;
                } else if ((minWidth >= MIN_SUPPORTED_WIDTH) &&
                    (minHeight >= MIN_SUPPORTED_HEIGHT) && (maxWidth > MAX_SUPPORTED_WIDTH) &&
                    (maxHeigth > MAX_SUPPORTED_HEIGHT)) {
                    width = MAX_SUPPORTED_WIDTH;
                    height = MAX_SUPPORTED_HEIGHT;
                } else if ((minWidth < MIN_SUPPORTED_WIDTH) &&
                    (minHeight < MIN_SUPPORTED_HEIGHT) && (maxWidth >= MAX_SUPPORTED_WIDTH) &&
                    (maxHeigth >= MAX_SUPPORTED_HEIGHT)) {
                    width = MAX_SUPPORTED_WIDTH;
                    height = MAX_SUPPORTED_HEIGHT;
                } else if ((minWidth >= MIN_SUPPORTED_WIDTH) &&
                    (minHeight >= MIN_SUPPORTED_HEIGHT) && (maxWidth < MAX_SUPPORTED_WIDTH) &&
                    (maxHeigth < MAX_SUPPORTED_HEIGHT)) {
                    width = maxWidth;
                    height = maxHeigth;
                } else if ((minWidth < MIN_SUPPORTED_WIDTH) &&
                    (minHeight < MIN_SUPPORTED_HEIGHT) && (maxWidth <= MAX_SUPPORTED_WIDTH) &&
                    (maxHeigth <= MAX_SUPPORTED_HEIGHT)) {
                    width = maxWidth;
                    height = maxHeigth;
                } else {
                    Log.e(TAG, "validateImgDescriptor: Invalid pixel range: " + minRange + " - " +
                        maxRange);
                    return null;
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "exception while parsing pixel range: " + minRange + " - " +
                        maxRange);
                return null;
            }
        }
        if (imgDesc.mTransformation != null &&
            !((imgDesc.mTransformation.equals("stretch")) ||
            (imgDesc.mTransformation.equals("fill")) ||
            (imgDesc.mTransformation.equals("crop")))) {
            Log.e(TAG, "validateImgDescriptor: transformation is invalid: " +
                imgDesc.mTransformation);
            return null;
        }
        if (V) Log.v(TAG, "validateImgDescriptor: before imgDesc.mPixel = " + imgDesc.mPixel);
        /* Update the pixel value now */
        imgDes.mPixel = width + "*" + height;
        if (V) Log.v(TAG, "validateImgDescriptor: imgDesc.mPixel = " + imgDes.mPixel);
        if (V) Log.v(TAG, "validateImgDescriptor: returning true");
        Log.d(TAG,"Exit validateImgDescriptor");
        return imgDes;
    }

    public boolean getImgThumb(OutputStream out, String imgHandle) {
        OutputStream outStream = null;
        boolean retVal = false;
        FileOutputStream tmp = null;
        FileInputStream tmp1 = null;

        if (mCoverArtAttributesMap.get(imgHandle) == null) {
            Log.w(TAG, "getImgThumb: imageHandle =" +  imgHandle + " is not in hashmap");
            return false;
        }

        if (D) Log.d(TAG,"getImgThumb: imgHandle = " + imgHandle);
        long albumId = mCoverArtAttributesMap.get(imgHandle).getAlbumId();
        if (D) Log.d(TAG,"getImgThumb: getScaledBitmap +");
        Bitmap bm = getScaledBitmap(albumId, BIP_THUMB_WIDTH, BIP_THUMB_HEIGHT);
        if (D) Log.d(TAG,"getImgThumb: getScaledBitmap -");
        if (bm != null) {
            try {

                int[] pixelArray = new int[BIP_THUMB_WIDTH * BIP_THUMB_HEIGHT];
                // Copy pixel data from the Bitmap into integer pixelArray
                bm.getPixels(pixelArray, 0, BIP_THUMB_WIDTH, 0, 0, BIP_THUMB_WIDTH,
                    BIP_THUMB_HEIGHT);
                byte[] yuvArray = convertToYuv(pixelArray, BIP_THUMB_WIDTH,
                        BIP_THUMB_HEIGHT);
                /* Convert Pixel Array to YuvImage */
                YuvImage yuvImg = new YuvImage(yuvArray, ImageFormat.YUY2,
                            BIP_THUMB_WIDTH, BIP_THUMB_HEIGHT, null);
                // Use temp file as ExifInterface requires absolute path of storage
                // file to update headers
                try {
                    tmp = new FileOutputStream(mTmpFilePath);
                } catch (FileNotFoundException e) {
                    Log.w(TAG,"getImgThumb: unable to open tmp File for writing");
                    return retVal;
                }
                if (D) Log.d(TAG,"getImgThumb: compress +");
                /* Compress YuvImage in YCC422 sampling using JPEG compression */
                yuvImg.compressToJpeg(new Rect(0, 0, BIP_THUMB_WIDTH, BIP_THUMB_HEIGHT),
                    COMPRESSION_QUALITY_HIGH, tmp);
                if (D) Log.d(TAG,"getImgThumb: compress -");
                tmp.flush();
                /* replace JFIF header with EXIF header and update new pixel size */
                updateExifHeader(imgHandle, BIP_THUMB_WIDTH, BIP_THUMB_HEIGHT);
                /* Copy the new updated header file to OutputStream */
                try {
                    tmp1 = new FileInputStream(mTmpFilePath);
                } catch (FileNotFoundException e) {
                    Log.w(TAG,"getImgThumb: unable to open tmp File for reading");
                    return retVal;
                }
                byte[] buffer = new byte[4096]; // To hold file contents
                int bytes_read;
                while ((bytes_read = tmp1.read(buffer)) != -1)
                    out.write(buffer, 0, bytes_read);
                /* Flush the data to output stream */
                out.flush();
                retVal = true;
            } catch (Exception e) {
                Log.w(TAG, "Exception = " + e);
            } finally {
                try {
                    try {
                        if (tmp != null) {
                            tmp.close();
                        }
                    } catch (IOException e) {
                        Log.w(TAG,"getImgThumb: exception in closing file");
                    }
                    try {
                        if (tmp1 != null) {
                            tmp1.close();
                        }
                    } catch (IOException e) {
                        Log.w(TAG,"getImgThumb: exception in closing file");
                    }
                    if (out != null) {
                        out.close();
                    }
                    /* Delete the tmp file now */
                    File f = new File(mTmpFilePath);
                    if (f != null && f.isFile() && f.exists()) {
                        if (D) Log.d(TAG, "getImgThumb: File Size = " + f.length());
                        f.delete();
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Exception = " + e);
                }
            }
        }
        if (D) Log.d(TAG,"getImg: returning " + retVal);
        return retVal;
    }

    public boolean getImg(OutputStream out, String imgHandle,
                    String imgDescXmlString) {

        boolean retVal = false;

        if (mCoverArtAttributesMap.get(imgHandle) == null) {
            Log.w(TAG, "getImg: imageHandle =" +  imgHandle + " is not in hashmap");
            return retVal;
        }

        Log.d(TAG,"Enter getImg");
        AvrcpBipRspImgDescriptor imgDesc = new AvrcpBipRspImgDescriptor();

        /* Read the Properties of Image as per the image Handle */
        readImgProperties(imgHandle);

        if (imgDescXmlString != null) {
            /* As per SPEC, image-descriptor can be empty */
            if (!(imgDescXmlString.equals(""))) {
                imgDesc = parseImgDescXml(imgDescXmlString);
                if (imgDesc == null)
                    return retVal;

                if (!(imgDesc.mVersion.equals(AvrcpBipRspImgDescriptor.DEFAULT_VERSION) &&
                    imgDesc.mEncoding != null && imgDesc.mPixel != null))
                    return retVal;
            }
        }

        Bitmap.CompressFormat cmpFormat;
        int width;
        int height;
        FileOutputStream tmp = null;
        FileInputStream tmp1 = null;

        if (V) Log.v(TAG,"getImg: imgDesc.mPixel = " + imgDesc.mPixel);
        if (imgDesc.mPixel.equals("")) {
            // remote device is willing to accept any pixel values, send
            // the default pixels of image
            width = mCoverArtAttributesMap.get(imgHandle).getWidth();
            height = mCoverArtAttributesMap.get(imgHandle).getHeigth();
        } else {
            imgDesc = validateImgDescriptor(imgDesc);
            if (imgDesc != null) {
                try {
                    //Get Width and Height from Image Descriptor Object
                    width = Integer.parseInt(imgDesc.mPixel.substring(
                            0, imgDesc.mPixel.indexOf("*")));
                    height = Integer.parseInt(imgDesc.mPixel.substring(
                            imgDesc.mPixel.lastIndexOf("*") + 1));
                } catch (NumberFormatException e) {
                    Log.e(TAG, "exception while parsing pixel");
                    return retVal;
                }
            } else {
                /* Image descriptor cannot be parsed to valid fields */
                Log.e(TAG,"getImg: imgDesc is not valid!!");
                return retVal;
            }
        }

        if (D) Log.d(TAG,"getImg: imgHandle = " + imgHandle + " width = "
            + width + " height = " + height + " encoding = " +
                imgDesc.mEncoding);

        if (imgDesc.mEncoding.equals("")) {
            // remote device is willing to accept any encoding, send
            // the default encoding of image
            cmpFormat = Bitmap.CompressFormat.JPEG;
        } else {
            // supported encoding is only JPEG and PNG
            if (imgDesc.mEncoding.toUpperCase().equals("JPEG"))
                cmpFormat = Bitmap.CompressFormat.JPEG;
            else if (imgDesc.mEncoding.toUpperCase().equals("PNG"))
                cmpFormat = Bitmap.CompressFormat.PNG;
            else
                return retVal;
        }

        long albumId = mCoverArtAttributesMap.get(imgHandle).getAlbumId();
        if (D) Log.d(TAG,"getImg: getScaledBitmap +");
        Bitmap bm = getScaledBitmap(albumId, width, height);
        if (D) Log.d(TAG,"getImg: getScaledBitmap -");
        if (bm != null) {
            try {
                // Use temp file as ExifInterface requires absolute path of storage
                // file to update headers
                try {
                    tmp = new FileOutputStream(mTmpFilePath);
                } catch (FileNotFoundException e) {
                    Log.w(TAG,"getImg: unable to open tmp File for writing");
                    return retVal;
                }
                if (D) Log.d(TAG,"getImg: compress +");
                bm.compress(cmpFormat, COMPRESSION_QUALITY_HIGH, tmp);
                if (D) Log.d(TAG,"getImg: compress -");
                try {
                    if (tmp != null) {
                        tmp.flush();
                        tmp.close();
                    }
                } catch (IOException e) {
                     Log.e(TAG,"getImg: exception in closing file");
                     return retVal;
                }
                File f = new File(mTmpFilePath);
                if (f != null && f.isFile() && f.exists()) {
                    if (D) Log.d(TAG, "File Size = " + f.length());
                    /* check if the size of compressed file is within range of maxsize */
                    if (imgDesc.mMaxSize != null &&
                        f.length() > Long.valueOf(imgDesc.mMaxSize)) {
                        Log.w(TAG, "Image size using compression is " +
                            f.length() + " more than maxsize = " +
                            imgDesc.mMaxSize + " deleting the file");
                        f.delete();
                        return retVal;
                    }
                }
                /* Copy the new updated header file to OutputStream */
                try {
                    tmp1 = new FileInputStream(mTmpFilePath);
                } catch (FileNotFoundException e) {
                    Log.w(TAG,"getImg: unable to open tmp File for reading");
                    return retVal;
                }
                byte[] buffer = new byte[4096]; // To hold file contents
                int bytes_read;
                while ((bytes_read = tmp1.read(buffer)) != -1)
                    out.write(buffer, 0, bytes_read);
                /* Flush the data to output stream */
                out.flush();
                retVal = true;
            } catch (Exception e) {
                Log.e(TAG, "Exception = " + e);
            } finally {
                try {
                    try {
                        if (tmp != null) {
                            tmp.close();
                        }
                    } catch (IOException e) {
                         Log.e(TAG,"getImg: exception in closing file");
                    }
                    try {
                        if (tmp1 != null) {
                            tmp1.close();
                        }
                    } catch (IOException e) {
                         Log.e(TAG,"getImg: exception in closing file");
                    }
                    if (out != null) {
                        out.close();
                    }
                    /* Delete the tmp file now */
                    File f = new File(mTmpFilePath);
                    if (f != null && f.isFile() && f.exists()) {
                        f.delete();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Exception = " + e);
                }
            }
        }
        if (D) Log.d(TAG,"getImg: returning " + retVal);
        return retVal;
    }

    public boolean isImgHandleValid(String imgHandle) {
        if (D) Log.d(TAG,"isImgHandleValid: imgHandle = " + imgHandle);

        if (mArtHandleMap.containsValue(imgHandle)) {
            if (V) Log.v(TAG,"isImgHandleValid: returning true");
            return true;
        }

        if (D) Log.d(TAG,"isImgHandleValid: returning false");
        return false;
    }

    public String getImgHandle(String albumName) {
        if (D) Log.d(TAG,"getImgHandle");
        try {
            if (albumName != null) {
                String artHandle = getArtHandleFromAlbum(albumName);
                if (artHandle != null) {
                    String imgHandle = getImgHandleFromArtHandle(artHandle);
                    if (D) Log.d(TAG,"getImgHandle: imgHandle = " + imgHandle);
                    return imgHandle;
                }
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG,"getImageHandle: IllegalArgumentException = ", e);
        }

        if (D) Log.d(TAG, "getImageHandle: returning null");
        return null;
    }

    /* Encode the Image Properties into the StringBuilder reference.
     * */
    public byte[] encode(String imgHandle)
            throws IllegalArgumentException, IllegalStateException, IOException
    {
        /* Check if we have the image handle in map, if not return null */
        if (mCoverArtAttributesMap.get(imgHandle) == null) {
            Log.w(TAG, "encode: imageHandle =" +  imgHandle + " is not in hashmap");
            return null;
        }

        Log.d(TAG,"Enter encode");
        readImgProperties(imgHandle);
        StringWriter sw = new StringWriter();
        XmlSerializer xmlMsgElement = new FastXmlSerializer();
        // contruct the XML tag for a single msg in the msglisting
        try {
            xmlMsgElement.setOutput(sw);
            xmlMsgElement.startDocument("UTF-8", true);
            xmlMsgElement.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            xmlMsgElement.startTag(null, "image-properties");
            xmlMsgElement.attribute(null, "version",
                mCoverArtAttributesMap.get(imgHandle).getVersion());
            xmlMsgElement.attribute(null, "handle", imgHandle);
            xmlMsgElement.startTag(null, "native");
            xmlMsgElement.attribute(null, "encoding",
                mCoverArtAttributesMap.get(imgHandle).getNativeEncoding());
            xmlMsgElement.attribute(null, "pixel",
                mCoverArtAttributesMap.get(imgHandle).getNativePixel());
            xmlMsgElement.attribute(null, "size",
                mCoverArtAttributesMap.get(imgHandle).getNativeSize());
            xmlMsgElement.endTag(null, "native");
            xmlMsgElement.startTag(null, "variant");
            xmlMsgElement.attribute(null, "encoding", "JPEG");
            xmlMsgElement.attribute(null, "pixel", MIN_SUPPORTED_WIDTH + "*" + MIN_SUPPORTED_WIDTH +
                "-" + MAX_SUPPORTED_WIDTH + "*" + MAX_SUPPORTED_HEIGHT);
            xmlMsgElement.endTag(null, "variant");
            xmlMsgElement.startTag(null, "variant");
            xmlMsgElement.attribute(null, "encoding", "PNG");
            xmlMsgElement.attribute(null, "pixel", MIN_SUPPORTED_WIDTH + "*" + MIN_SUPPORTED_WIDTH +
                "-" + MAX_SUPPORTED_WIDTH + "*" + MAX_SUPPORTED_HEIGHT);
            xmlMsgElement.endTag(null, "variant");
            xmlMsgElement.endTag(null, "image-properties");
            xmlMsgElement.endDocument();
        } catch (IllegalArgumentException e) {
            Log.w(TAG, e);
        } catch (IllegalStateException e) {
            Log.w(TAG, e);
        } catch (IOException e) {
            Log.w(TAG, e);
        }
        if (V) Log.v(TAG, "Image properties XML = " + sw.toString());
        return sw.toString().getBytes("UTF-8");
    }
}
