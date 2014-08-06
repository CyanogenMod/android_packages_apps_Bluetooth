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

import android.graphics.drawable.Drawable;
import android.util.Log;

/**
 * Class to contain all the info about the items of the Map Email Settings Menu.
 * It can be used for both Email Apps (group Parent item) and Accounts (Group child Item).
 *
 */
public class BluetoothMapEmailSettingsItem implements Comparable<BluetoothMapEmailSettingsItem>{
    private static final String TAG = "BluetoothMapEmailSettingsItem";

    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = BluetoothMapService.VERBOSE;

    protected boolean mIsChecked;
    private String mName;
    private String mPackageName;
    private String mId;
    private String mProviderAuthority;
    private Drawable mIcon;
    public String mBase_uri;
    public String mBase_uri_no_account;
    public BluetoothMapEmailSettingsItem(String id, String name, String packageName, String authority, Drawable icon) {
        this.mName = name;
        this.mIcon = icon;
        this.mPackageName = packageName;
        this.mId = id;
        this.mProviderAuthority = authority;
        this.mBase_uri_no_account = "content://" + authority;
        this.mBase_uri = mBase_uri_no_account + "/"+id;
    }

    public long getAccountId() {
        if(mId != null) {
            return Long.parseLong(mId);
        }
        return -1;
    }

    @Override
    public int compareTo(BluetoothMapEmailSettingsItem other) {

        if(!other.mId.equals(this.mId)){
            if(V) Log.d(TAG, "Wrong id : " + this.mId + " vs " + other.mId);
            return -1;
        }
        if(!other.mName.equals(this.mName)){
            if(V) Log.d(TAG, "Wrong name : " + this.mName + " vs " + other.mName);
            return -1;
        }
        if(!other.mPackageName.equals(this.mPackageName)){
            if(V) Log.d(TAG, "Wrong packageName : " + this.mPackageName + " vs " + other.mPackageName);
             return -1;
        }
        if(!other.mProviderAuthority.equals(this.mProviderAuthority)){
            if(V) Log.d(TAG, "Wrong providerName : " + this.mProviderAuthority + " vs " + other.mProviderAuthority);
            return -1;
        }
        if(other.mIsChecked != this.mIsChecked){
            if(V) Log.d(TAG, "Wrong isChecked : " + this.mIsChecked + " vs " + other.mIsChecked);
            return -1;
        }
        return 0;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mId == null) ? 0 : mId.hashCode());
        result = prime * result + ((mName == null) ? 0 : mName.hashCode());
        result = prime * result
                + ((mPackageName == null) ? 0 : mPackageName.hashCode());
        result = prime * result
                + ((mProviderAuthority == null) ? 0 : mProviderAuthority.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        BluetoothMapEmailSettingsItem other = (BluetoothMapEmailSettingsItem) obj;
        if (mId == null) {
            if (other.mId != null)
                return false;
        } else if (!mId.equals(other.mId))
            return false;
        if (mName == null) {
            if (other.mName != null)
                return false;
        } else if (!mName.equals(other.mName))
            return false;
        if (mPackageName == null) {
            if (other.mPackageName != null)
                return false;
        } else if (!mPackageName.equals(other.mPackageName))
            return false;
        if (mProviderAuthority == null) {
            if (other.mProviderAuthority != null)
                return false;
        } else if (!mProviderAuthority.equals(other.mProviderAuthority))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return mName + " (" + mBase_uri + ")";
    }

    public Drawable getIcon() {
        return mIcon;
    }

    public void setIcon(Drawable icon) {
        this.mIcon = icon;
    }

    public String getName() {
        return mName;
    }

    public void setName(String name) {
        this.mName = name;
    }

    public String getId() {
        return mId;
    }

    public void setId(String id) {
        this.mId = id;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public void setPackageName(String packageName) {
        this.mPackageName = packageName;
    }

    public String getProviderAuthority() {
        return mProviderAuthority;
    }

    public void setProviderAuthority(String providerAuthority) {
        this.mProviderAuthority = providerAuthority;
    }
}