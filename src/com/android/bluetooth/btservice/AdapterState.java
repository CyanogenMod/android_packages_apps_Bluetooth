/*
 * Copyright (C) 2012 Google Inc.
 */

package com.android.bluetooth.btservice;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.os.Message;
import android.util.Log;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

/**
 * This state machine handles Bluetooth Adapter State.
 * States:
 *      {@link OnState} : Bluetooth is on at this state
 *      {@link OffState}: Bluetooth is off at this state. This is the initial
 *      state.
 *      {@link PendingCommandState} : An enable / disable operation is pending.
 * TODO(BT): Add per process on state.
 */

final class AdapterState extends StateMachine {
    private static final boolean DBG = true;
    private static final String TAG = "BluetoothAdapterState";

    static final int USER_TURN_ON = 1;
    static final int USER_TURN_OFF = 2;
    static final int AIRPLANE_MODE_ON = 3;
    static final int AIRPLANE_MODE_OFF = 4;
    static final int ENABLED_READY = 5;
    static final int DISABLED = 6;
    static final int ALL_DEVICES_DISCONNECTED = 7;
    static final int ENABLE_TIMEOUT = 8;

    private static final int DISCONNECT_TIMEOUT = 3000;
    private static final int ENABLE_TIMEOUT_DELAY = 6000; // 6 secs

    private AdapterService mAdapterService;
    private Context mContext;
    private AdapterProperties mAdapterProperties;
    private boolean mPendingPersistEnable;
    private PendingCommandState mPendingCommandState = new PendingCommandState();
    private OnState mOnState = new OnState();
    private OffState mOffState = new OffState();

    public AdapterState(AdapterService service, Context context,
            AdapterProperties adapterProperties) {
        super("BluetoothAdapterState:");
        addState(mOnState);
        addState(mOffState);
        addState(mPendingCommandState);
        mAdapterService = service;
        mContext = context;
        mAdapterProperties = adapterProperties;
        setInitialState(mOffState);
    }

    public void cleanup() {
    }

    private class OffState extends State {
        @Override
        public void enter() {
            infoLog("Entering Off State");
        }

        @Override
        public boolean processMessage(Message msg) {
            switch(msg.what) {
               case USER_TURN_ON:
                   int persist = msg.arg1;
                   //if (persist == 1) mAdapterService.persistBluetoothSetting(true);
                   //Persist enable state only once enable completes
                   mPendingPersistEnable = (persist ==1);
                   sendIntent(BluetoothAdapter.STATE_TURNING_ON);
                   boolean ret = mAdapterService.enableNative();
                   if (!ret) {
                       Log.e(TAG, "Error while turning Bluetooth On");
                       sendIntent(BluetoothAdapter.STATE_OFF);
                   } else {
                       sendMessageDelayed(ENABLE_TIMEOUT, ENABLE_TIMEOUT_DELAY);
                       transitionTo(mPendingCommandState);
                   }
                   break;
               case AIRPLANE_MODE_OFF:
               {
                   if(mAdapterService.getBluetoothPersistedSetting()) {
                        Log.i(TAG, "OffState : Turning BT on after Airplane"+
                            " Mode OFF state");
                        sendIntent(BluetoothAdapter.STATE_TURNING_ON);
                        ret = mAdapterService.enableNative();
                        if (!ret) {
                            Log.e(TAG, "Error while turning Bluetooth On");
                            sendIntent(BluetoothAdapter.STATE_OFF);
                        } else {
                            sendMessageDelayed(ENABLE_TIMEOUT,
                                ENABLE_TIMEOUT_DELAY);
                            transitionTo(mPendingCommandState);
                        }
                   }
                   break;
               }
               case USER_TURN_OFF:
               case AIRPLANE_MODE_ON:
                   //ignore
                   break;
               default:
                   Log.e(TAG, "Received unhandled state: " + msg.what);
                   return false;
            }
            return true;
        }
    }

    private class OnState extends State {
        @Override
        public void enter() {
            infoLog("Entering On State");
        }

        @Override
        public boolean processMessage(Message msg) {
            switch(msg.what) {
               case USER_TURN_OFF:
                   int persist = msg.arg1;
                   if (persist == 1) {
                          //Persist disable immediately even before disable completes
                       mAdapterService.persistBluetoothSetting(false);
                   }
                   //Fall Through
               case AIRPLANE_MODE_ON:
                   sendIntent(BluetoothAdapter.STATE_TURNING_OFF);
                   // Invoke onBluetoothDisable which shall trigger a
                   // setScanMode to SCAN_MODE_NONE
                   mAdapterProperties.onBluetoothDisable();
                   if (mAdapterProperties.getConnectionState() !=
                           BluetoothAdapter.STATE_DISCONNECTED) {
                       sendMessageDelayed(ALL_DEVICES_DISCONNECTED,
                                   DISCONNECT_TIMEOUT);
                       break;
                   }
                   //Fall Through
               case ALL_DEVICES_DISCONNECTED:
                   boolean ret = mAdapterService.disableNative();
                   if (!ret) {
                       Log.e(TAG, "Error while turning Bluetooth Off");
                       sendIntent(BluetoothAdapter.STATE_ON);
                   } else {
                       transitionTo(mPendingCommandState);
                   }
                   break;
               case USER_TURN_ON:
               case AIRPLANE_MODE_OFF:
                   //ignore
                   break;
               default:
                   Log.e(TAG, "Received unhandled state: " + msg.what);
                   return false;
            }
            return true;
        }
    }

    private class PendingCommandState extends State {
        @Override
        public void enter() {
            infoLog("Entering PendingCommandState State");
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case USER_TURN_ON:
                case USER_TURN_OFF:
                case AIRPLANE_MODE_ON:
                case AIRPLANE_MODE_OFF:
                    deferMessage(msg);
                    break;
                case ENABLED_READY:
                    removeMessages(ENABLE_TIMEOUT);
                    //Persist enable state only once enable completes
                    if (mPendingPersistEnable) {
                        mAdapterService.persistBluetoothSetting(true);
                        mPendingPersistEnable=false;
                    }
                    mAdapterProperties.onBluetoothReady();
                    sendIntent(BluetoothAdapter.STATE_ON);
                    transitionTo(mOnState);
                    break;
                case DISABLED:
                    sendIntent(BluetoothAdapter.STATE_OFF);
                    transitionTo(mOffState);
                    break;
                case ENABLE_TIMEOUT:
                    errorLog("Error enabling Bluetooth");
                    sendIntent(BluetoothAdapter.STATE_OFF);
                    transitionTo(mOffState);
                    break;
                default:
                    Log.e(TAG, "Received unhandled event:" + msg.what);
                    return false;
            }
            return true;
        }
    }


    private void sendIntent(int newState) {
        int oldState = mAdapterProperties.getState();
        Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, oldState);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, newState);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mAdapterProperties.setState(newState);

        mContext.sendBroadcast(intent, AdapterService.BLUETOOTH_PERM);
        infoLog("Bluetooth State Change Intent: " + oldState + " -> " + newState);
    }

    void stateChangeCallback(int status) {
        if (status == AbstractionLayer.BT_STATE_OFF) {
            sendMessage(DISABLED);
        } else if (status == AbstractionLayer.BT_STATE_ON) {
            // We should have got the property change for adapter and remote devices.
            sendMessage(ENABLED_READY);
        } else {
            errorLog("Incorrect status in stateChangeCallback");
        }
    }

    private void infoLog(String msg) {
        if (DBG) Log.i(TAG, msg);
    }

    private void errorLog(String msg) {
        Log.e(TAG, msg);
    }
}
