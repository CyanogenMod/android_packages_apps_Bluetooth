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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.android.bluetooth.map.BluetoothMapEmailSettingsItem;



import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import com.android.bluetooth.mapapi.BluetoothMapContract;
import android.text.format.DateUtils;
import android.util.Log;


public class BluetoothMapEmailSettingsLoader {
    private static final String TAG = "BluetoothMapEmailSettingsLoader";
    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = BluetoothMapService.VERBOSE;
    private Context mContext = null;
    private PackageManager mPackageManager = null;
    private ContentResolver mResolver;
    private int mAccountsEnabledCount = 0;
    private ContentProviderClient mProviderClient = null;
    private static final long PROVIDER_ANR_TIMEOUT = 20 * DateUtils.SECOND_IN_MILLIS;

    public BluetoothMapEmailSettingsLoader(Context ctx)
    {
        mContext = ctx;
    }

    /**
     * Method to look through all installed packages system-wide and find those that contain the
     * BTMAP meta-tag in their manifest file. For each app the list of accounts are fetched using
     * the method parseAccounts().
     * @return LinkedHashMap with the packages as keys(BluetoothMapEmailSettingsItem) and
     *          values as ArrayLists of BluetoothMapEmailSettingsItems.
     */
    public LinkedHashMap<BluetoothMapEmailSettingsItem,
                         ArrayList<BluetoothMapEmailSettingsItem>> parsePackages(boolean includeIcon) {

        LinkedHashMap<BluetoothMapEmailSettingsItem, ArrayList<BluetoothMapEmailSettingsItem>> groups =
                new LinkedHashMap<BluetoothMapEmailSettingsItem,ArrayList<BluetoothMapEmailSettingsItem>>();
        Intent searchIntent = new Intent(BluetoothMapContract.PROVIDER_INTERFACE);
        // reset the counter every time this method is called.
        mAccountsEnabledCount=0;
        // find all installed packages and filter out those that do not support Map Email.
        // this is done by looking for a apps with content providers containing the intent-filter for
        // android.content.action.BTMAP_SHARE in the manifest file.
        mPackageManager = mContext.getPackageManager();
        List<ResolveInfo> resInfos = mPackageManager
                .queryIntentContentProviders(searchIntent, 0);

        if (resInfos != null) {
            if(D) Log.d(TAG,"Found " + resInfos.size() + " applications");
            for (ResolveInfo rInfo : resInfos) {
                // We cannot rely on apps that have been force-stopped in the application settings menu.
                if ((rInfo.providerInfo.applicationInfo.flags & ApplicationInfo.FLAG_STOPPED) == 0) {
                    BluetoothMapEmailSettingsItem app = createAppItem(rInfo, includeIcon);
                    if (app != null){
                        ArrayList<BluetoothMapEmailSettingsItem> accounts = parseAccounts(app);
                        // we do not want to list apps without accounts
                        if(accounts.size() >0)
                        {
                            // we need to make sure that the "select all" checkbox is checked if all accounts in the list are checked
                            app.mIsChecked = true;
                            for (BluetoothMapEmailSettingsItem acc: accounts)
                            {
                                if(!acc.mIsChecked)
                                {
                                    app.mIsChecked = false;
                                    break;
                                }
                            }
                            groups.put(app, accounts);
                        }
                    }
                } else {
                    if(D)Log.d(TAG,"Ignoring force-stopped authority "+ rInfo.providerInfo.authority +"\n");
                }
            }
        }
        else {
            if(D) Log.d(TAG,"Found no applications");
        }
        return groups;
    }

    public BluetoothMapEmailSettingsItem createAppItem(ResolveInfo rInfo,
            boolean includeIcon) {
        String provider = rInfo.providerInfo.authority;
        if(provider != null) {
            String name = rInfo.loadLabel(mPackageManager).toString();
            if(D)Log.d(TAG,rInfo.providerInfo.packageName + " - " + name + " - meta-data(provider = " + provider+")\n");
            BluetoothMapEmailSettingsItem app = new BluetoothMapEmailSettingsItem(
                    "0",
                    name,
                    rInfo.providerInfo.packageName,
                    provider,
                    (includeIcon == false)? null : rInfo.loadIcon(mPackageManager));
            return app;
        }

        return null;
    }


    /**
     * Method for getting the accounts under a given contentprovider from a package.
     * This
     * @param app The parent app object
     * @return An ArrayList of BluetoothMapEmailSettingsItems containing all the accounts from the app
     */
    public ArrayList<BluetoothMapEmailSettingsItem> parseAccounts(BluetoothMapEmailSettingsItem app)  {
        Cursor c = null;
        if(D) Log.d(TAG,"Adding app "+app.getPackageName());
        ArrayList<BluetoothMapEmailSettingsItem> children = new ArrayList<BluetoothMapEmailSettingsItem>();
        // Get the list of accounts from the email apps content resolver (if possible
        mResolver = mContext.getContentResolver();
        try{
            mProviderClient = mResolver.acquireUnstableContentProviderClient(Uri.parse(app.mBase_uri_no_account));
             if (mProviderClient == null) {
                 throw new RemoteException("Failed to acquire provider for " + app.getPackageName());
             }
             mProviderClient.setDetectNotResponding(PROVIDER_ANR_TIMEOUT);

            Uri uri = Uri.parse(app.mBase_uri_no_account + "/" + BluetoothMapContract.TABLE_ACCOUNT);
            c = mProviderClient.query(uri, BluetoothMapContract.BT_ACCOUNT_PROJECTION, null, null,
                    BluetoothMapContract.AccountColumns._ID+" DESC");
            while (c != null && c.moveToNext()) {
                BluetoothMapEmailSettingsItem child = new BluetoothMapEmailSettingsItem(
                    String.valueOf((c.getInt(c.getColumnIndex(BluetoothMapContract.AccountColumns._ID)))),
                    c.getString(c.getColumnIndex(BluetoothMapContract.AccountColumns.ACCOUNT_DISPLAY_NAME)) ,
                    app.getPackageName(),
                    app.getProviderAuthority(),
                    null);

                child.mIsChecked = (c.getInt(c.getColumnIndex(BluetoothMapContract.AccountColumns.FLAG_EXPOSE))!=0);
                /*update the account counter so we can make sure that not to many accounts are checked. */
                if(child.mIsChecked)
                {
                    mAccountsEnabledCount++;
                }
                children.add(child);
            }
        } catch (RemoteException e){
            if(D)Log.d(TAG,"Could not establish ContentProviderClient for "+app.getPackageName()+
                    " - returning empty account list" );
        } finally {
            if (c != null) c.close();
        }
        return children;
    }
    /**
     * Gets the number of enabled accounts in total across all supported apps.
     * NOTE that this method should not be called before the parsePackages method
     * has been successfully called.
     * @return number of enabled accounts
     */
    public int getAccountsEnabledCount() {
        if(D)Log.d(TAG,"Enabled Accounts count:"+ mAccountsEnabledCount);
        return mAccountsEnabledCount;
    }
}
