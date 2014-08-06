/*
* Copyright (C) 2014 Samsung System LSI
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


import android.util.Log;
import org.xmlpull.v1.XmlSerializer;

import com.android.internal.util.FastXmlSerializer;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map.Entry;


/**
 * Class to contain a single folder element representation.
 *
 */
public class BluetoothMapFolderElement {
    private String mName;
    private BluetoothMapFolderElement mParent = null;
    private boolean mHasSmsMmsContent = false;
    private long mEmailFolderId = -1;
    private HashMap<String, BluetoothMapFolderElement> mSubFolders;

    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = BluetoothMapService.VERBOSE;

    private final static String TAG = "BluetoothMapFolderElement";

    public BluetoothMapFolderElement( String name, BluetoothMapFolderElement parrent){
        this.mName = name;
        this.mParent = parrent;
        mSubFolders = new HashMap<String, BluetoothMapFolderElement>();
    }

    public String getName() {
        return mName;
    }

    public boolean hasSmsMmsContent(){
        return mHasSmsMmsContent;
    }

    public long getEmailFolderId(){
        return mEmailFolderId;
    }

    public void setEmailFolderId(long emailFolderId) {
        this.mEmailFolderId = emailFolderId;
    }

    public void setHasSmsMmsContent(boolean hasSmsMmsContent) {
        this.mHasSmsMmsContent = hasSmsMmsContent;
    }

    /**
     * Fetch the parent folder.
     * @return the parent folder or null if we are at the root folder.
     */
    public BluetoothMapFolderElement getParent() {
        return mParent;
    }

    /**
     * Build the full path to this folder
     * @return a string representing the full path.
     */
    public String getFullPath() {
        StringBuilder sb = new StringBuilder(mName);
        BluetoothMapFolderElement current = mParent;
        while(current != null) {
            if(current.getParent() != null) {
                sb.insert(0, current.mName + "/");
            }
            current = current.getParent();
        }
        //sb.insert(0, "/"); Should this be included? The MAP spec. do not include it in examples.
        return sb.toString();
    }


    public BluetoothMapFolderElement getEmailFolderByName(String name) {
        BluetoothMapFolderElement folderElement = this.getRoot();
        folderElement = folderElement.getSubFolder("telecom");
        folderElement = folderElement.getSubFolder("msg");
        folderElement = folderElement.getSubFolder(name);
        if (folderElement != null && folderElement.getEmailFolderId() == -1 )
            folderElement = null;
        return folderElement;
    }

    public BluetoothMapFolderElement getEmailFolderById(long id) {
        return getEmailFolderById(id, this);
    }

    public static BluetoothMapFolderElement getEmailFolderById(long id,
            BluetoothMapFolderElement folderStructure) {
        if(folderStructure == null) {
            return null;
        }
        return findEmailFolderById(id, folderStructure.getRoot());
    }

    private static BluetoothMapFolderElement findEmailFolderById(long id,
            BluetoothMapFolderElement folder) {
        if(folder.getEmailFolderId() == id) {
            return folder;
        }
        /* Else */
        for(BluetoothMapFolderElement subFolder : folder.mSubFolders.values().toArray(
                new BluetoothMapFolderElement[folder.mSubFolders.size()]))
        {
            BluetoothMapFolderElement ret = findEmailFolderById(id, subFolder);
            if(ret != null) {
                return ret;
            }
        }
        return null;
    }


    /**
     * Fetch the root folder.
     * @return the parent folder or null if we are at the root folder.
     */
    public BluetoothMapFolderElement getRoot() {
        BluetoothMapFolderElement rootFolder = this;
        while(rootFolder.getParent() != null)
            rootFolder = rootFolder.getParent();
        return rootFolder;
    }

    /**
     * Add a virtual folder.
     * @param name the name of the folder to add.
     * @return the added folder element.
     */
    public BluetoothMapFolderElement addFolder(String name){
        name = name.toLowerCase(Locale.US);
        BluetoothMapFolderElement newFolder = mSubFolders.get(name);
        if(D) Log.i(TAG,"addFolder():" + name);
        if(newFolder == null) {
            newFolder = new BluetoothMapFolderElement(name, this);
            mSubFolders.put(name, newFolder);
        }
        return newFolder;
    }

    /**
     * Add a sms/mms folder.
     * @param name the name of the folder to add.
     * @return the added folder element.
     */
    public BluetoothMapFolderElement addSmsMmsFolder(String name){
        name = name.toLowerCase(Locale.US);
        BluetoothMapFolderElement newFolder = mSubFolders.get(name);
        if(D) Log.i(TAG,"addSmsMmsFolder():" + name);
        if(newFolder == null) {
            newFolder = new BluetoothMapFolderElement(name, this);
            mSubFolders.put(name, newFolder);
        }
        newFolder.setHasSmsMmsContent(true);
        return newFolder;
    }

    /**
     * Add an Email folder.
     * @param name the name of the folder to add.
     * @return the added folder element.
     */
    public BluetoothMapFolderElement addEmailFolder(String name, long emailFolderId){
        name = name.toLowerCase();
        BluetoothMapFolderElement newFolder = mSubFolders.get(name);
        if(V) Log.v(TAG,"addEmailFolder(): name = " + name
                 + "id = " + emailFolderId);
        if(newFolder == null) {
            newFolder = new BluetoothMapFolderElement(name, this);
            mSubFolders.put(name, newFolder);
        }
        newFolder.setEmailFolderId(emailFolderId);
        return newFolder;
    }

    /**
     * Fetch the number of sub folders.
     * @return returns the number of sub folders.
     */
    public int getSubFolderCount(){
        return mSubFolders.size();
    }

    /**
     * Returns the subFolder element matching the supplied folder name.
     * @param folderName the name of the subFolder to find.
     * @return the subFolder element if found {@code null} otherwise.
     */
    public BluetoothMapFolderElement getSubFolder(String folderName){
        return mSubFolders.get(folderName.toLowerCase());
    }

    public byte[] encode(int offset, int count) throws UnsupportedEncodingException {
        StringWriter sw = new StringWriter();
        XmlSerializer xmlMsgElement = new FastXmlSerializer();
        int i, stopIndex;
        // We need index based access to the subFolders
        BluetoothMapFolderElement[] folders = mSubFolders.values().toArray(new BluetoothMapFolderElement[mSubFolders.size()]);

        if(offset > mSubFolders.size())
            throw new IllegalArgumentException("FolderListingEncode: offset > subFolders.size()");

        stopIndex = offset + count;
        if(stopIndex > mSubFolders.size())
            stopIndex = mSubFolders.size();

        try {
            xmlMsgElement.setOutput(sw);
            xmlMsgElement.startDocument("UTF-8", true);
            xmlMsgElement.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            xmlMsgElement.startTag(null, "folder-listing");
            xmlMsgElement.attribute(null, "version", "1.0");
            for(i = offset; i<stopIndex; i++)
            {
                xmlMsgElement.startTag(null, "folder");
                xmlMsgElement.attribute(null, "name", folders[i].getName());
                xmlMsgElement.endTag(null, "folder");
            }
            xmlMsgElement.endTag(null, "folder-listing");
            xmlMsgElement.endDocument();
        } catch (IllegalArgumentException e) {
            if(D) Log.w(TAG,e);
            throw new IllegalArgumentException("error encoding folderElement");
        } catch (IllegalStateException e) {
            if(D) Log.w(TAG,e);
            throw new IllegalArgumentException("error encoding folderElement");
        } catch (IOException e) {
            if(D) Log.w(TAG,e);
            throw new IllegalArgumentException("error encoding folderElement");
        }
        return sw.toString().getBytes("UTF-8");
    }
}
