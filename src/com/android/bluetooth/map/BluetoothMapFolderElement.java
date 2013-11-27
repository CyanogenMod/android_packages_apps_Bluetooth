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

import java.io.IOException;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

import org.xmlpull.v1.XmlSerializer;

import android.util.Xml;

/**
 * @author cbonde
 *
 */
public class BluetoothMapFolderElement {
    private String name;
    private BluetoothMapFolderElement parent = null;
    private ArrayList<BluetoothMapFolderElement> subFolders;

    public BluetoothMapFolderElement( String name, BluetoothMapFolderElement parrent ){
        this.name = name;
        this.parent = parrent;
        subFolders = new ArrayList<BluetoothMapFolderElement>();
    }

    public String getName() {
        return name;
    }

    /**
     * Fetch the parent folder.
     * @return the parent folder or null if we are at the root folder.
     */
    public BluetoothMapFolderElement getParent() {
        return parent;
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
     * Add a folder.
     * @param name the name of the folder to add.
     * @return the added folder element.
     */
    public BluetoothMapFolderElement addFolder(String name){
        BluetoothMapFolderElement newFolder = new BluetoothMapFolderElement(name, this);
        subFolders.add(newFolder);
        return newFolder;
    }

    /**
     * Fetch the number of sub folders.
     * @return returns the number of sub folders.
     */
    public int getSubFolderCount(){
        return subFolders.size();
    }

    /**
     * Returns the subFolder element matching the supplied folder name.
     * @param folderName the name of the subFolder to find.
     * @return the subFolder element if found {@code null} otherwise.
     */
    public BluetoothMapFolderElement getSubFolder(String folderName){
        for(BluetoothMapFolderElement subFolder : subFolders){
            if(subFolder.getName().equalsIgnoreCase(folderName))
                return subFolder;
        }
        return null;
    }

    public byte[] encode(int offset, int count) throws UnsupportedEncodingException {
        StringWriter sw = new StringWriter();
        XmlSerializer xmlMsgElement = Xml.newSerializer();
        int i, stopIndex;
        if(offset > subFolders.size())
            throw new IllegalArgumentException("FolderListingEncode: offset > subFolders.size()");

        stopIndex = offset + count;
        if(stopIndex > subFolders.size())
            stopIndex = subFolders.size();

        try {
            xmlMsgElement.setOutput(sw);
            xmlMsgElement.startDocument(null, null);
            xmlMsgElement.text("\n");
            xmlMsgElement.startTag("", "folder-listing");
            xmlMsgElement.attribute("", "version", "1.0");
            for(i = offset; i<stopIndex; i++)
            {
                xmlMsgElement.startTag("", "folder");
                xmlMsgElement.attribute("", "name", subFolders.get(i).getName());
                xmlMsgElement.endTag("", "folder");
            }
            xmlMsgElement.endTag("", "folder-listing");
            xmlMsgElement.endDocument();
        } catch (IllegalArgumentException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IllegalStateException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return sw.toString().getBytes("UTF-8");
    }
}
