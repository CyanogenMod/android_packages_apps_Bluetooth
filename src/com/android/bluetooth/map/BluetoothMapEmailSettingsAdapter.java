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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.android.bluetooth.R;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import com.android.bluetooth.mapapi.BluetoothMapContract;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ExpandableListView;
import android.widget.ExpandableListView.OnGroupExpandListener;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CompoundButton;
import com.android.bluetooth.map.BluetoothMapEmailSettingsItem;
import com.android.bluetooth.map.BluetoothMapEmailSettingsLoader;
public class BluetoothMapEmailSettingsAdapter extends BaseExpandableListAdapter {
    private static final boolean D = BluetoothMapService.DEBUG;
    private static final boolean V = BluetoothMapService.VERBOSE;
    private static final String TAG = "BluetoothMapEmailSettingsAdapter";
    private boolean mCheckAll = true;
    public LayoutInflater mInflater;
    public Activity mActivity;
    /*needed to prevent random checkbox toggles due to item reuse */
    ArrayList<Boolean> mPositionArray;
    private LinkedHashMap<BluetoothMapEmailSettingsItem,
                            ArrayList<BluetoothMapEmailSettingsItem>> mProupList;
    private ArrayList<BluetoothMapEmailSettingsItem> mMainGroup;
    private int[] mGroupStatus;
    /* number of accounts possible to share */
    private int mSlotsLeft = 10;


    public BluetoothMapEmailSettingsAdapter(Activity act,
                                            ExpandableListView listView,
                                            LinkedHashMap<BluetoothMapEmailSettingsItem,
                                            ArrayList<BluetoothMapEmailSettingsItem>> groupsList,
                                            int enabledAccountsCounts) {
        mActivity = act;
        this.mProupList = groupsList;
        mInflater = act.getLayoutInflater();
        mGroupStatus = new int[groupsList.size()];
        mSlotsLeft = mSlotsLeft-enabledAccountsCounts;

        listView.setOnGroupExpandListener(new OnGroupExpandListener() {

            public void onGroupExpand(int groupPosition) {
                BluetoothMapEmailSettingsItem group = mMainGroup.get(groupPosition);
                if (mProupList.get(group).size() > 0)
                    mGroupStatus[groupPosition] = 1;

            }
        });
        mMainGroup = new ArrayList<BluetoothMapEmailSettingsItem>();
        for (Map.Entry<BluetoothMapEmailSettingsItem, ArrayList<BluetoothMapEmailSettingsItem>> mapEntry : mProupList.entrySet()) {
            mMainGroup.add(mapEntry.getKey());
        }
    }

    @Override
    public BluetoothMapEmailSettingsItem getChild(int groupPosition, int childPosition) {
        BluetoothMapEmailSettingsItem item = mMainGroup.get(groupPosition);
        return mProupList.get(item).get(childPosition);
    }
    private ArrayList<BluetoothMapEmailSettingsItem> getChild(BluetoothMapEmailSettingsItem group) {
        return mProupList.get(group);
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
        return 0;
    }

    @Override
    public View getChildView(final int groupPosition, final int childPosition,
            boolean isLastChild, View convertView, ViewGroup parent) {


        final ChildHolder holder;
        if (convertView == null) {
            convertView = mInflater.inflate(R.layout.bluetooth_map_email_settings_account_item, null);
            holder = new ChildHolder();
            holder.cb = (CheckBox) convertView.findViewById(R.id.bluetooth_map_email_settings_item_check);
            holder.title = (TextView) convertView.findViewById(R.id.bluetooth_map_email_settings_item_text_view);
            convertView.setTag(holder);
        } else {
            holder = (ChildHolder) convertView.getTag();
        }
            final BluetoothMapEmailSettingsItem child =  getChild(groupPosition, childPosition);
            holder.cb.setOnCheckedChangeListener(new OnCheckedChangeListener() {

                public void onCheckedChanged(CompoundButton buttonView,
                        boolean isChecked) {
                    BluetoothMapEmailSettingsItem parentGroup = (BluetoothMapEmailSettingsItem)getGroup(groupPosition);
                    boolean oldIsChecked = child.mIsChecked; // needed to prevent updates on UI redraw
                    child.mIsChecked = isChecked;
                    if (isChecked) {
                        ArrayList<BluetoothMapEmailSettingsItem> childList = getChild(parentGroup);
                        int childIndex = childList.indexOf(child);
                        boolean isAllChildClicked = true;
                        if(mSlotsLeft-childList.size() >=0){

                            for (int i = 0; i < childList.size(); i++) {
                                if (i != childIndex) {
                                    BluetoothMapEmailSettingsItem siblings = childList.get(i);
                                    if (!siblings.mIsChecked) {
                                        isAllChildClicked = false;
                                            BluetoothMapEmailSettingsDataHolder.mCheckedChilds.put(child.getName(),
                                                    parentGroup.getName());
                                        break;

                                    }
                                }
                            }
                        }else {
                            showWarning(mActivity.getString(R.string.bluetooth_map_email_settings_no_account_slots_left));
                            isAllChildClicked = false;
                            child.mIsChecked = false;
                        }
                        if (isAllChildClicked) {
                            parentGroup.mIsChecked = true;
                            if(!(BluetoothMapEmailSettingsDataHolder.mCheckedChilds.containsKey(child.getName())==true)){
                                BluetoothMapEmailSettingsDataHolder.mCheckedChilds.put(child.getName(),
                                        parentGroup.getName());
                            }
                            mCheckAll = false;
                        }


                    } else {
                        if (parentGroup.mIsChecked) {
                            parentGroup.mIsChecked = false;
                            mCheckAll = false;
                            BluetoothMapEmailSettingsDataHolder.mCheckedChilds.remove(child.getName());
                        } else {
                            mCheckAll = true;
                            BluetoothMapEmailSettingsDataHolder.mCheckedChilds.remove(child.getName());
                        }
                        // child.isChecked =false;
                    }
                    notifyDataSetChanged();
                    if(child.mIsChecked != oldIsChecked){
                        updateAccount(child);
                    }

                }

            });

            holder.cb.setChecked(child.mIsChecked);
            holder.title.setText(child.getName());
            if(D)Log.i("childs are", BluetoothMapEmailSettingsDataHolder.mCheckedChilds.toString());
            return convertView;

    }



    @Override
    public int getChildrenCount(int groupPosition) {
        BluetoothMapEmailSettingsItem item = mMainGroup.get(groupPosition);
        return mProupList.get(item).size();
    }

    @Override
    public BluetoothMapEmailSettingsItem getGroup(int groupPosition) {
        return mMainGroup.get(groupPosition);
    }

    @Override
    public int getGroupCount() {
        return mMainGroup.size();
    }

    @Override
    public void onGroupCollapsed(int groupPosition) {
        super.onGroupCollapsed(groupPosition);
    }

    @Override
    public void onGroupExpanded(int groupPosition) {
        super.onGroupExpanded(groupPosition);
    }

    @Override
    public long getGroupId(int groupPosition) {
        return 0;
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded,
            View convertView, ViewGroup parent) {

        final GroupHolder holder;

        if (convertView == null) {
            convertView = mInflater.inflate(R.layout.bluetooth_map_email_settings_account_group, null);
            holder = new GroupHolder();
            holder.cb = (CheckBox) convertView.findViewById(R.id.bluetooth_map_email_settings_group_checkbox);
            holder.imageView = (ImageView) convertView
                    .findViewById(R.id.bluetooth_map_email_settings_group_icon);
            holder.title = (TextView) convertView.findViewById(R.id.bluetooth_map_email_settings_group_text_view);
            convertView.setTag(holder);
        } else {
            holder = (GroupHolder) convertView.getTag();
        }

        final BluetoothMapEmailSettingsItem groupItem = getGroup(groupPosition);
        holder.imageView.setImageDrawable(groupItem.getIcon());


        holder.title.setText(groupItem.getName());

        holder.cb.setOnCheckedChangeListener(new OnCheckedChangeListener() {

            public void onCheckedChanged(CompoundButton buttonView,
                    boolean isChecked) {
                if (mCheckAll) {
                    ArrayList<BluetoothMapEmailSettingsItem> childItem = getChild(groupItem);
                    for (BluetoothMapEmailSettingsItem children : childItem)
                    {
                        boolean oldIsChecked = children.mIsChecked;
                        if(mSlotsLeft >0){
                            children.mIsChecked = isChecked;
                            if(oldIsChecked != children.mIsChecked){
                                updateAccount(children);
                            }
                        }else {
                            showWarning(mActivity.getString(R.string.bluetooth_map_email_settings_no_account_slots_left));
                            isChecked = false;
                        }
                    }
                }
                groupItem.mIsChecked = isChecked;
                notifyDataSetChanged();
                new Handler().postDelayed(new Runnable() {

                    public void run() {
                        if (!mCheckAll)
                            mCheckAll = true;
                    }
                }, 50);

            }

        });
        holder.cb.setChecked(groupItem.mIsChecked);
        return convertView;

    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        return true;
    }

    private class GroupHolder {
        public ImageView imageView;
        public CheckBox cb;
        public TextView title;

    }

    private class ChildHolder {
        public TextView title;
        public CheckBox cb;
    }
    public void updateAccount(BluetoothMapEmailSettingsItem account) {
        updateSlotCounter(account.mIsChecked);
        if(D)Log.d(TAG,"Updating account settings for "+account.getName() +". Value is:"+account.mIsChecked);
        ContentResolver mResolver = mActivity.getContentResolver();
        Uri uri = Uri.parse(account.mBase_uri_no_account+"/"+BluetoothMapContract.TABLE_ACCOUNT);
        ContentValues values = new ContentValues();
        values.put(BluetoothMapContract.AccountColumns.FLAG_EXPOSE, ((account.mIsChecked)?1:0)); // get title
        values.put(BluetoothMapContract.AccountColumns._ID, account.getId()); // get title
        mResolver.update(uri, values, null ,null);

    }
    private void updateSlotCounter(boolean isChecked){
        if(isChecked)
        {
            mSlotsLeft--;
        }else {
            mSlotsLeft++;
        }
        CharSequence text;

        if (mSlotsLeft <=0)
        {
            text = mActivity.getString(R.string.bluetooth_map_email_settings_no_account_slots_left);
        }else {
            text= mActivity.getString(R.string.bluetooth_map_email_settings_count) + " "+ String.valueOf(mSlotsLeft);
        }

        int duration = Toast.LENGTH_SHORT;

        Toast toast = Toast.makeText(mActivity, text, duration);
        toast.show();
    }
    private void showWarning(String text){
        int duration = Toast.LENGTH_SHORT;

        Toast toast = Toast.makeText(mActivity, text, duration);
        toast.show();

    }


}
