/*
* Copyright (c) 2015, The Linux Foundation. All rights reserved.
* Not a Contribution.
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

import com.android.bluetooth.map.BluetoothMapAccountItem;
import com.android.bluetooth.map.BluetoothMapUtils.*;
import com.android.bluetooth.map.BluetoothMapUtils.TYPE;



import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import com.android.bluetooth.mapapi.BluetoothMapContract;
import com.android.bluetooth.mapapi.BluetoothMapEmailContract;
import com.android.bluetooth.map.BluetoothMapAccountLoader;
import android.text.format.DateUtils;
import android.util.Log;


public class BluetoothMapAccountEmailLoader extends BluetoothMapAccountLoader {
    private static final String TAG = "BluetoothMapAccountEmailLoader";
    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = Log.isLoggable(BluetoothMapService.LOG_TAG, Log.VERBOSE);
    private Context mEmailContext = null;
    private PackageManager mPackageManager = null;
    private ContentResolver mResolver;
    private int mAccountsEnabledCount = 0;
    private ContentProviderClient mProviderClient = null;
    private static final long PROVIDER_ANR_TIMEOUT = 20 * DateUtils.SECOND_IN_MILLIS;
    public BluetoothMapAccountEmailLoader(Context ctx) {
        super(ctx);
        mEmailContext = ctx;
        if(mEmailContext != null){
            Log.d(TAG,"mEmailContext Assigned ");
        }
    }

    /**
     * Method to look through all installed packages system-wide and find those that contain one of
     * the BT-MAP intents in their manifest file. For each app the list of accounts are fetched
     * using the method parseAccounts().
     * @return LinkedHashMap with the packages as keys(BluetoothMapAccountItem) and
     *          values as ArrayLists of BluetoothMapAccountItems.
     */
    @Override
    public LinkedHashMap<BluetoothMapAccountItem,
                         ArrayList<BluetoothMapAccountItem>> parsePackages(boolean includeIcon) {

        LinkedHashMap<BluetoothMapAccountItem, ArrayList<BluetoothMapAccountItem>> groups =
                new LinkedHashMap<BluetoothMapAccountItem,
                                  ArrayList<BluetoothMapAccountItem>>();
                // reset the counter every time this method is called.
        mAccountsEnabledCount=0;
        groups = super.parsePackages(includeIcon);
        Log.d(TAG, "Groups SIZE: "+groups.size());
        if(groups.size() == 0) {
            /* Support ONLY account(s) configured from default or primary Email App,
            * accisible with "com.android.email.permission.ACCESS_PROVIDER",
            * similar to MAP email instance as on QCOM KK and L releas(es).*/
            Log.d(TAG,"Found 0 applications - Support legacy default or primary email Account");
            if( mEmailContext != null ) {
                BluetoothMapAccountItem app = BluetoothMapAccountItem.create(
                    "0",
                    null,
                    "com.android.email",
                    BluetoothMapEmailContract.EMAIL_AUTHORITY,
                    null ,
                    BluetoothMapUtils.TYPE.EMAIL);
                    if (app != null){
                        ArrayList<BluetoothMapAccountItem> accounts = parseEmailAccounts(app);
                        Log.d(TAG,"parseAccnts: " + accounts.size());
                        // we do not want to list apps without accounts
                        if(accounts.size() > 0)
                        {   // we need to make sure that the "select all" checkbox
                            // is checked if all accounts in the list are checked
                            app.mIsChecked = true;
                            for (BluetoothMapAccountItem acc: accounts)
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
                    Log.d(TAG,"mEmailContext NOT Assigned - NULL");
               }
        }
        return groups;
    }

    public BluetoothMapAccountItem createAppItem(ResolveInfo rInfo, boolean includeIcon,
            BluetoothMapUtils.TYPE type) {
        String provider = rInfo.providerInfo.authority;
        if(provider != null) {
            String name = rInfo.loadLabel(mPackageManager).toString();
            if(D)Log.d(TAG,rInfo.providerInfo.packageName + " - " + name +
                            " - meta-data(provider = " + provider+")\n");
            BluetoothMapAccountItem app = BluetoothMapAccountItem.create(
                    "0",
                    name,
                    rInfo.providerInfo.packageName,
                    provider,
                    (includeIcon == false)? null : rInfo.loadIcon(mPackageManager),
                    type);
            return app;
        }

        return null;
    }

    /**
     * Method for getting the accounts under a given contentprovider from a package.
     * @param app The parent app object
     * @return An ArrayList of BluetoothMapAccountItems containing all the accounts from the app
     */
    public ArrayList<BluetoothMapAccountItem> parseEmailAccounts(BluetoothMapAccountItem app)  {
        Cursor c = null;
        if(D) Log.d(TAG,"Finding accounts for app "+app.getPackageName());
        ArrayList<BluetoothMapAccountItem> children = new ArrayList<BluetoothMapAccountItem>();
        // Get the list of accounts from the email apps content resolver (if possible)
        mResolver = mEmailContext.getContentResolver();
        try{
            mProviderClient = mResolver.acquireUnstableContentProviderClient(
                    Uri.parse(app.mBase_uri_no_account));
            if (mProviderClient == null) {
                throw new RemoteException("Failed to acquire provider for " + app.getPackageName());
            }
            mProviderClient.setDetectNotResponding(PROVIDER_ANR_TIMEOUT);
            Uri uri = Uri.parse(app.mBase_uri_no_account + "/"
                                + BluetoothMapEmailContract.EMAIL_TABLE_ACCOUNT);
            c = mProviderClient.query(uri, BluetoothMapEmailContract
                    .BT_EMAIL_ACCOUNT_ID_PROJECTION, null, null, null);
        } catch (RemoteException e){
            if(D)Log.d(TAG,"Could not establish ContentProviderClient for "+app.getPackageName()+
                    " - returning empty account list" );
            return children;
        } finally {
            if (mProviderClient != null) {
                mProviderClient.release();
            }
        }

        if (c != null) {
            c.moveToPosition(-1);
            int dispNameIndex = c.getColumnIndex(BluetoothMapEmailContract
                                        .ExtEmailMessageColumns.DISPLAY_NAME);
            int idIndex = c.getColumnIndex(BluetoothMapEmailContract.ExtEmailMessageColumns
                                  .RECORD_ID);
            int exposeIndex = c.getColumnIndex(BluetoothMapEmailContract.ExtEmailMessageColumns
                                  .IS_DEFAULT);
            while (c.moveToNext()) {
                if(D)Log.d(TAG,"Adding account " + c.getString(dispNameIndex) +
                        " with ID " + String.valueOf(c.getInt(idIndex)));
                String uci = null;
                String uciPrefix = null;
                if(app.getType() == TYPE.IM){
                    int uciIndex =
                            c.getColumnIndex(BluetoothMapContract.AccountColumns.ACCOUNT_UCI);
                    int uciPreIndex = c.getColumnIndex(
                    BluetoothMapContract.AccountColumns.ACCOUNT_UCI_PREFIX);
                    uci = c.getString(uciIndex);
                    uciPrefix = c.getString(uciPreIndex);
                    if(D)Log.d(TAG,"   Account UCI " + uci);
                }
                BluetoothMapAccountItem child = BluetoothMapAccountItem.create(
                        String.valueOf((c.getInt(idIndex))),
                        BluetoothMapEmailContract.ExtEmailMessageColumns.EMAIL_SERVICE_NAME,
                        app.getPackageName(),
                        app.getProviderAuthority(),
                        null,
                        app.getType(),
                        uci,
                        uciPrefix);

                child.mIsChecked = (c.getInt(exposeIndex) != 0);
                child.mIsChecked = true; // TODO: Revert when this works
                /* update the account counter
                 * so we can make sure that not to many accounts are checked. */
                if(child.mIsChecked)
                {
                    mAccountsEnabledCount++;
                }
                children.add(child);
            }
            c.close();
        } else {
            if(D)Log.d(TAG, "query failed");
        }
        return children;
    }

    @Override
    /**
     * Method for getting the accounts under a given contentprovider from a package.
     * @param app The parent app object
     * @return An ArrayList of BluetoothMapAccountItems containing all the accounts from the app
     */
    public ArrayList<BluetoothMapAccountItem> parseAccounts(BluetoothMapAccountItem app)  {
        return parseEmailAccounts(app);
    }

    /**
     * Gets the number of enabled accounts in total across all supported apps.
     * NOTE that this method should not be called before the parsePackages method
     * has been successfully called.
     * @return number of enabled accounts
     */
    @Override
    public int getAccountsEnabledCount() {
        if(D)Log.d(TAG,"Enabled Accounts count:"+ mAccountsEnabledCount);
        return super.getAccountsEnabledCount() + mAccountsEnabledCount;
    }
}
