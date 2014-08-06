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
import java.util.Set;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import com.android.bluetooth.mapapi.BluetoothMapContract;
import android.util.Log;

/**
 * Class to construct content observers for for email applications on the system.
 *
 *
 */

public class BluetoothMapEmailAppObserver{

    private static final String TAG = "BluetoothMapEmailAppObserver";

    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = BluetoothMapService.VERBOSE;
    /*  */
    private LinkedHashMap<BluetoothMapEmailSettingsItem, ArrayList<BluetoothMapEmailSettingsItem>> mFullList;
    private LinkedHashMap<String,ContentObserver> mObserverMap = new LinkedHashMap<String,ContentObserver>();
    private ContentResolver mResolver;
    private Context mContext;
    private BroadcastReceiver mReceiver;
    private PackageManager mPackageManager = null;
    BluetoothMapEmailSettingsLoader mLoader;
    BluetoothMapService mMapService = null;

    public BluetoothMapEmailAppObserver(final Context context, BluetoothMapService mapService) {
        mContext  = context;
        mMapService = mapService;
        mResolver = context.getContentResolver();
        mLoader    = new BluetoothMapEmailSettingsLoader(mContext);
        mFullList = mLoader.parsePackages(false); /* Get the current list of apps */
        createReceiver();
        initObservers();
    }


    private BluetoothMapEmailSettingsItem getApp(String packageName) {
        if(V) Log.d(TAG, "getApp(): Looking for " + packageName);
        for(BluetoothMapEmailSettingsItem app:mFullList.keySet()){
            if(V) Log.d(TAG, "  Comparing: " + app.getPackageName());
            if(app.getPackageName().equals(packageName)) {
                if(V) Log.d(TAG, "  found " + app.mBase_uri_no_account);
                return app;
            }
        }
        if(V) Log.d(TAG, "  NOT FOUND!");
        return null;
    }

    private void handleAccountChanges(String packageNameWithProvider) {

        if(D)Log.d(TAG,"handleAccountChanges (packageNameWithProvider: "+packageNameWithProvider+"\n");
        String packageName = packageNameWithProvider.replaceFirst("\\.[^\\.]+$", "");
        BluetoothMapEmailSettingsItem app = getApp(packageName);
        if(app != null) {
            ArrayList<BluetoothMapEmailSettingsItem> newAccountList = mLoader.parseAccounts(app);
            ArrayList<BluetoothMapEmailSettingsItem> oldAccountList = mFullList.get(app);
            ArrayList<BluetoothMapEmailSettingsItem> addedAccountList =
                    (ArrayList<BluetoothMapEmailSettingsItem>)newAccountList.clone();
            ArrayList<BluetoothMapEmailSettingsItem> removedAccountList = mFullList.get(app); // Same as oldAccountList.clone

            mFullList.put(app, newAccountList);
            for(BluetoothMapEmailSettingsItem newAcc: newAccountList){
                for(BluetoothMapEmailSettingsItem oldAcc: oldAccountList){
                    if(newAcc.getId() == oldAcc.getId()){
                        // For each match remove from both removed and added lists
                        removedAccountList.remove(oldAcc);
                        addedAccountList.remove(newAcc);
                        if(!newAcc.getName().equals(oldAcc.getName()) && newAcc.mIsChecked){
                            // Name Changed and the acc is visible - Change Name in SDP record
                            mMapService.updateMasInstances(BluetoothMapService.UPDATE_MAS_INSTANCES_ACCOUNT_RENAMED);
                            if(V)Log.d(TAG, "    UPDATE_MAS_INSTANCES_ACCOUNT_RENAMED");
                        }
                        if(newAcc.mIsChecked != oldAcc.mIsChecked) {
                            // Visibility changed
                            if(newAcc.mIsChecked){
                                // account added - create SDP record
                                mMapService.updateMasInstances(BluetoothMapService.UPDATE_MAS_INSTANCES_ACCOUNT_ADDED);
                                if(V)Log.d(TAG, "    UPDATE_MAS_INSTANCES_ACCOUNT_ADDED isChecked changed");
                            } else {
                                // account removed - remove SDP record
                                mMapService.updateMasInstances(BluetoothMapService.UPDATE_MAS_INSTANCES_ACCOUNT_REMOVED);
                                if(V)Log.d(TAG, "    UPDATE_MAS_INSTANCES_ACCOUNT_REMOVED isChecked changed");
                            }
                        }
                        break;
                    }
                }
            }
            // Notify on any removed accounts
            for(BluetoothMapEmailSettingsItem removedAcc: removedAccountList){
                mMapService.updateMasInstances(BluetoothMapService.UPDATE_MAS_INSTANCES_ACCOUNT_REMOVED);
                if(V)Log.d(TAG, "    UPDATE_MAS_INSTANCES_ACCOUNT_REMOVED " + removedAcc);
            }
            // Notify on any new accounts
            for(BluetoothMapEmailSettingsItem addedAcc: addedAccountList){
                mMapService.updateMasInstances(BluetoothMapService.UPDATE_MAS_INSTANCES_ACCOUNT_ADDED);
                if(V)Log.d(TAG, "    UPDATE_MAS_INSTANCES_ACCOUNT_ADDED " + addedAcc);
            }

        } else {
            Log.e(TAG, "Received change notification on package not registered for notifications!");

        }
    }

    /**
     * Adds a new content observer to the list of content observers.
     * The key for the observer is the uri as string
     * @param uri uri for the package that supports MAP email
     */

    public void registerObserver(BluetoothMapEmailSettingsItem app) {
        Uri uri = BluetoothMapContract.buildAccountUri(app.getProviderAuthority());
        if (V) Log.d(TAG, "registerObserver for URI "+uri.toString()+"\n");
        ContentObserver observer = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                onChange(selfChange, null);
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (V) Log.d(TAG, "onChange on thread: " + Thread.currentThread().getId()
                        + " Uri: " + uri + " selfchange: " + selfChange);
                if(uri != null) {
                    handleAccountChanges(uri.getHost());
                } else {
                    Log.e(TAG, "Unable to handle change as the URI is NULL!");
                }

            }
        };
        mObserverMap.put(uri.toString(), observer);
        mResolver.registerContentObserver(uri, true, observer);
    }

    public void unregisterObserver(BluetoothMapEmailSettingsItem app) {
        Uri uri = BluetoothMapContract.buildAccountUri(app.getProviderAuthority());
        if (V) Log.d(TAG, "unregisterObserver("+uri.toString()+")\n");
        mResolver.unregisterContentObserver(mObserverMap.get(uri.toString()));
        mObserverMap.remove(uri.toString());
    }

    private void initObservers(){
        if(D)Log.d(TAG,"initObservers()");
        for(BluetoothMapEmailSettingsItem app: mFullList.keySet()){
            registerObserver(app);
        }
    }

    private void deinitObservers(){
        if(D)Log.d(TAG,"deinitObservers()");
        for(BluetoothMapEmailSettingsItem app: mFullList.keySet()){
            unregisterObserver(app);
        }
    }

    private void createReceiver(){
        if(D)Log.d(TAG,"createReceiver()\n");
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        mReceiver= new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(D)Log.d(TAG,"onReceive\n");
                String action = intent.getAction();
                if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                    Uri data = intent.getData();
                    String packageName = data.getEncodedSchemeSpecificPart();
                    if(D)Log.d(TAG,"The installed package is: "+ packageName);
                  //  PackageInfo pInfo = getPackageInfo(packageName);
                    ResolveInfo rInfo = mPackageManager.resolveActivity(intent, 0);
                    BluetoothMapEmailSettingsItem app = mLoader.createAppItem(rInfo, false);
                    if(app != null) {
                        registerObserver(app);
                        // Add all accounts to mFullList
                        ArrayList<BluetoothMapEmailSettingsItem> newAccountList = mLoader.parseAccounts(app);
                        mFullList.put(app, newAccountList);
                    }
                }
                else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {

                    Uri data = intent.getData();
                    String packageName = data.getEncodedSchemeSpecificPart();
                    if(D)Log.d(TAG,"The removed package is: "+ packageName);
                    BluetoothMapEmailSettingsItem app = getApp(packageName);
                    /* Find the object and remove from fullList */
                    if(app != null) {
                        unregisterObserver(app);
                        mFullList.remove(app);
                    }
                }
            }
        };
        mContext.registerReceiver(mReceiver,new IntentFilter(Intent.ACTION_PACKAGE_ADDED));
    }
    private void removeReceiver(){
        if(D)Log.d(TAG,"removeReceiver()\n");
        mContext.unregisterReceiver(mReceiver);
    }
    private PackageInfo getPackageInfo(String packageName){
        mPackageManager = mContext.getPackageManager();
        try {
            return mPackageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA|PackageManager.GET_SERVICES);
        } catch (NameNotFoundException e) {
            Log.e(TAG,"Error getting package metadata", e);
        }
        return null;
    }

    /**
     * Method to get a list of the accounts (across all apps) that are set to be shared
     * through MAP.
     * @return Arraylist<BluetoothMapEmailSettingsItem> containing all enabled accounts
     */
    public ArrayList<BluetoothMapEmailSettingsItem> getEnabledAccountItems(){
        if(D)Log.d(TAG,"getEnabledAccountItems()\n");
        ArrayList<BluetoothMapEmailSettingsItem> list = new ArrayList<BluetoothMapEmailSettingsItem>();
        for(BluetoothMapEmailSettingsItem app:mFullList.keySet()){
            ArrayList<BluetoothMapEmailSettingsItem> accountList = mFullList.get(app);
            for(BluetoothMapEmailSettingsItem acc: accountList){
                if(acc.mIsChecked) {
                    list.add(acc);
                }
            }
        }
        return list;
    }

    /**
     * Method to get a list of the accounts (across all apps).
     * @return Arraylist<BluetoothMapEmailSettingsItem> containing all accounts
     */
    public ArrayList<BluetoothMapEmailSettingsItem> getAllAccountItems(){
        if(D)Log.d(TAG,"getAllAccountItems()\n");
        ArrayList<BluetoothMapEmailSettingsItem> list = new ArrayList<BluetoothMapEmailSettingsItem>();
        for(BluetoothMapEmailSettingsItem app:mFullList.keySet()){
            ArrayList<BluetoothMapEmailSettingsItem> accountList = mFullList.get(app);
            list.addAll(accountList);
        }
        return list;
    }


    /**
     * Cleanup all resources - must be called to avoid leaks.
     */
    public void shutdown() {
        deinitObservers();
        removeReceiver();
    }
}
