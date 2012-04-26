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
    static final int STARTED=2;
    static final int ENABLED_READY = 3;
   // static final int POST_ENABLE =4;

    static final int USER_TURN_OFF = 20;
    static final int BEGIN_DISABLE = 21;
    static final int ALL_DEVICES_DISCONNECTED = 22;
   // static final int DISABLE=23;
    static final int DISABLED = 24;
    static final int STOPPED=25;

    static final int START_TIMEOUT = 100;
    static final int ENABLE_TIMEOUT = 101;
    static final int DISABLE_TIMEOUT = 103;
    static final int STOP_TIMEOUT = 104;

    static final int USER_TURN_OFF_DELAY_MS=500;

    //TODO: tune me
    private static final int ENABLE_TIMEOUT_DELAY = 8000;
    private static final int DISABLE_TIMEOUT_DELAY = 8000;
    private static final int START_TIMEOUT_DELAY = 5000;
    private static final int STOP_TIMEOUT_DELAY = 5000;
    private static final int PROPERTY_OP_DELAY =2000;
    private AdapterService mAdapterService;
    private Context mContext;
    private AdapterProperties mAdapterProperties;
    private PendingCommandState mPendingCommandState = new PendingCommandState();
    private OnState mOnState = new OnState();
    private OffState mOffState = new OffState();

    public boolean isTurningOn() {
        boolean isTurningOn=  mPendingCommandState.isTurningOn();
        Log.d(TAG,"isTurningOn()=" + isTurningOn);
        return isTurningOn;
    }

    public boolean isTurningOff() {
        boolean isTurningOff= mPendingCommandState.isTurningOff();
        Log.d(TAG,"isTurningOff()=" + isTurningOff);
        return isTurningOff;
    }

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
        if(mAdapterProperties != null)
            mAdapterProperties = null;
        if(mAdapterService != null)
            mAdapterService = null;
        if(mContext != null)
            mContext = null;
    }

    private class OffState extends State {
        @Override
        public void enter() {
            infoLog("Entering OffState");
        }

        @Override
        public boolean processMessage(Message msg) {
            if (msg.what == SM_QUIT_CMD) {
                Log.d(TAG, "Received quit request...");
                return false;
            }
            int requestId = msg.arg1;

            switch(msg.what) {
               case USER_TURN_ON:
                   if (DBG) Log.d(TAG,"CURRENT_STATE=OFF, MESSAGE = USER_TURN_ON, requestId= " + msg.arg1);
                   sendIntent(BluetoothAdapter.STATE_TURNING_ON);
                   mPendingCommandState.setTurningOn(true);
                   transitionTo(mPendingCommandState);
                   sendMessageDelayed(START_TIMEOUT, START_TIMEOUT_DELAY);
                   mAdapterService.processStart();
                   break;
               case USER_TURN_OFF:
                   if (DBG) Log.d(TAG,"CURRENT_STATE=OFF, MESSAGE = USER_TURN_OFF, requestId= " + msg.arg1);
                   //Handle case of service started and stopped without enable
                   mAdapterService.startShutdown(requestId);
                   break;
               default:
                   if (DBG) Log.d(TAG,"ERROR: UNEXPECTED MESSAGE: CURRENT_STATE=OFF, MESSAGE = " + msg.what );
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
                   if (DBG) Log.d(TAG,"CURRENT_STATE=ON, MESSAGE = USER_TURN_OFF, requestId= " + msg.arg1);
                   sendIntent(BluetoothAdapter.STATE_TURNING_OFF);
                   mPendingCommandState.setTurningOff(true);
                   mPendingCommandState.setOffRequestId(msg.arg1);
                   transitionTo(mPendingCommandState);

                   // Invoke onBluetoothDisable which shall trigger a
                   // setScanMode to SCAN_MODE_NONE
                   Message m = obtainMessage(BEGIN_DISABLE);
                   m.arg1 = msg.arg1;
                   sendMessageDelayed(m, PROPERTY_OP_DELAY);
                   mAdapterProperties.onBluetoothDisable();
                   break;

               case USER_TURN_ON:
                   if (DBG) Log.d(TAG,"CURRENT_STATE=ON, MESSAGE = USER_TURN_ON, requestId= " + msg.arg1);
                   Log.i(TAG,"Bluetooth already ON, ignoring USER_TURN_ON");
                   break;
               default:
                   if (DBG) Log.d(TAG,"ERROR: UNEXPECTED MESSAGE: CURRENT_STATE=ON, MESSAGE = " + msg.what );
                   return false;
            }
            return true;
        }
    }

    private class PendingCommandState extends State {
        private boolean mIsTurningOn;
        private boolean mIsTurningOff;

        private int mRequestId;

        public void enter() {
            infoLog("Entering PendingCommandState State: isTurningOn()=" + isTurningOn() + ", isTurningOff()=" + isTurningOff());
        }

        public void setTurningOn(boolean isTurningOn) {
            mIsTurningOn = isTurningOn;
        }

        public boolean isTurningOn() {
            return mIsTurningOn;
        }

        public void setTurningOff(boolean isTurningOff) {
            mIsTurningOff = isTurningOff;
        }

        public boolean isTurningOff() {
            return mIsTurningOff;
        }

        public void setOffRequestId(int requestId) {
            mRequestId = requestId;
        }

        public int getOffRequestId() {
            return mRequestId;
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean isTurningOn= isTurningOn();
            boolean isTurningOff = isTurningOff();
            switch (msg.what) {
                case USER_TURN_ON:
                    if (DBG) Log.d(TAG,"CURRENT_STATE=PENDING, MESSAGE = USER_TURN_ON, requestId= " + msg.arg1
                            + ", isTurningOn=" + isTurningOn + ", isTurningOff=" + isTurningOff);
                    if (isTurningOn) {
                        Log.i(TAG,"CURRENT_STATE=PENDING: Alreadying turning on bluetooth... Ignoring USER_TURN_ON...");
                    } else {
                        Log.i(TAG,"CURRENT_STATE=PENDING: Deferring request USER_TURN_ON");
                        deferMessage(msg);
                    }
                    break;
                case USER_TURN_OFF:
                    if (DBG) Log.d(TAG,"CURRENT_STATE=PENDING, MESSAGE = USER_TURN_ON, requestId= " + msg.arg1
                            + ", isTurningOn=" + isTurningOn + ", isTurningOff=" + isTurningOff);
                    if (isTurningOff) {
                        Log.i(TAG,"CURRENT_STATE=PENDING: Alreadying turning off bluetooth... Ignoring USER_TURN_OFF...");
                    } else {
                        Log.i(TAG,"CURRENT_STATE=PENDING: Deferring request USER_TURN_OFF");
                        deferMessage(msg);
                    }
                    break;
                case STARTED:   {
                    if (DBG) Log.d(TAG,"CURRENT_STATE=PENDING, MESSAGE = STARTED, isTurningOn=" + isTurningOn + ", isTurningOff=" + isTurningOff);
                    //Remove start timeout
                    removeMessages(START_TIMEOUT);

                    //Enable
                    boolean ret = mAdapterService.enableNative();
                    if (!ret) {
                        Log.e(TAG, "Error while turning Bluetooth On");
                        sendIntent(BluetoothAdapter.STATE_OFF);
                        transitionTo(mOffState);
                    } else {
                        sendMessageDelayed(ENABLE_TIMEOUT, ENABLE_TIMEOUT_DELAY);
                    }
                }
                    break;

                case ENABLED_READY:
                    if (DBG) Log.d(TAG,"CURRENT_STATE=PENDING, MESSAGE = ENABLE_READY, isTurningOn=" + isTurningOn + ", isTurningOff=" + isTurningOff);
                    removeMessages(ENABLE_TIMEOUT);
                    mAdapterProperties.onBluetoothReady();
                    mPendingCommandState.setTurningOn(false);
                    transitionTo(mOnState);
                    sendIntent(BluetoothAdapter.STATE_ON);
                    break;

                case BEGIN_DISABLE: {
                    if (DBG) Log.d(TAG,"CURRENT_STATE=PENDING, MESSAGE = BEGIN_DISABLE" + isTurningOn + ", isTurningOff=" + isTurningOff);
                    removeMessages(BEGIN_DISABLE); //Remove extra message we setup in USER_TURN_OFF
                    //Log.d(TAG,"CURRENT_STATE=ON, MESSAGE = BEGIN_DISABLE_ON, requestId= " + msg.arg1);
                    sendMessageDelayed(DISABLE_TIMEOUT, DISABLE_TIMEOUT_DELAY);
                    boolean ret = mAdapterService.disableNative();
                    if (!ret) {
                        removeMessages(DISABLE_TIMEOUT);
                        Log.e(TAG, "Error while turning Bluetooth Off");
                        //FIXME: what about post enable services
                        mPendingCommandState.setTurningOff(false);
                        mPendingCommandState.setOffRequestId(-1);
                        sendIntent(BluetoothAdapter.STATE_ON);
                    }
                }
                    break;
                case DISABLED:
                    if (DBG) Log.d(TAG,"CURRENT_STATE=PENDING, MESSAGE = DISABLED, isTurningOn=" + isTurningOn + ", isTurningOff=" + isTurningOff);
                    removeMessages(DISABLE_TIMEOUT);
                    sendMessageDelayed(STOP_TIMEOUT, STOP_TIMEOUT_DELAY);
                    if (mAdapterService.stopProfileServices()) {
                        Log.d(TAG,"Stopping profile services that were post enabled");
                        break;
                    }
                    //Fall through if no post-enabled services or services already stopped
                case STOPPED:
                    if (DBG) Log.d(TAG,"CURRENT_STATE=PENDING, MESSAGE = STOPPED, isTurningOn=" + isTurningOn + ", isTurningOff=" + isTurningOff);
                    removeMessages(STOP_TIMEOUT);
                    setTurningOff(false);
                    int requestId= getOffRequestId();
                    setOffRequestId(-1);
                    transitionTo(mOffState);
                    sendIntent(BluetoothAdapter.STATE_OFF);
                    mAdapterService.processStopped();
                    mAdapterService.startShutdown(requestId);
                    break;
                case START_TIMEOUT:
                    if (DBG) Log.d(TAG,"CURRENT_STATE=PENDING, MESSAGE = START_TIMEOUT, isTurningOn=" + isTurningOn + ", isTurningOff=" + isTurningOff);
                    errorLog("Error enabling Bluetooth");
                    mPendingCommandState.setTurningOn(false);
                    transitionTo(mOffState);
                    sendIntent(BluetoothAdapter.STATE_OFF);
                    break;
                case ENABLE_TIMEOUT:
                    if (DBG) Log.d(TAG,"CURRENT_STATE=PENDING, MESSAGE = ENABLE_TIMEOUT, isTurningOn=" + isTurningOn + ", isTurningOff=" + isTurningOff);
                    errorLog("Error enabling Bluetooth");
                    mPendingCommandState.setTurningOn(false);
                    transitionTo(mOffState);
                    sendIntent(BluetoothAdapter.STATE_OFF);
                    break;
                case STOP_TIMEOUT:
                    if (DBG) Log.d(TAG,"CURRENT_STATE=PENDING, MESSAGE = STOP_TIMEOUT, isTurningOn=" + isTurningOn + ", isTurningOff=" + isTurningOff);
                    errorLog("Error stopping Bluetooth profiles");
                    mPendingCommandState.setTurningOff(false);
                    transitionTo(mOffState);
                    break;
                case DISABLE_TIMEOUT:
                    if (DBG) Log.d(TAG,"CURRENT_STATE=PENDING, MESSAGE = DISABLE_TIMEOUT, isTurningOn=" + isTurningOn + ", isTurningOff=" + isTurningOff);
                    errorLog("Error disabling Bluetooth");
                    mPendingCommandState.setTurningOff(false);
                    transitionTo(mOnState);
                    break;
                default:
                    if (DBG) Log.d(TAG,"ERROR: UNEXPECTED MESSAGE: CURRENT_STATE=PENDING, MESSAGE = " + msg.what );
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
