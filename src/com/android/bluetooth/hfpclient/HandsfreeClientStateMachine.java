/*
 * Copyright (C) 2013 The Linux Foundation. All rights reserved
 * Not a Contribution.
 * Copyright (C) 2012 The Android Open Source Project
 *
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

/**
 * Bluetooth Handsfree Client StateMachine
 *                      (Disconnected)
 *                           | ^  ^
 *                   CONNECT | |  | DISCONNECTED
 *                           V |  |
 *                   (Connecting) |
 *                           |    |
 *                 CONNECTED |    | DISCONNECT
 *                           V    |
 *                        (Connected)
 *                           |    ^
 *             CONNECT_AUDIO |    | DISCONNECT_AUDIO
 *                           V    |
 *                         (AudioOn)
 */

package com.android.bluetooth.hfpclient;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHandsfreeClient;
import android.bluetooth.BluetoothHandsfreeClientCall;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.os.Bundle;
import android.os.Message;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.Pair;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;

import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

final class HandsfreeClientStateMachine extends StateMachine {
    private static final String TAG = "HandsfreeClientStateMachine";
    private static final boolean DBG = false;

    static final int NO_ACTION = 0;

    // external actions
    static final int CONNECT = 1;
    static final int DISCONNECT = 2;
    static final int CONNECT_AUDIO = 3;
    static final int DISCONNECT_AUDIO = 4;
    static final int VOICE_RECOGNITION_START = 5;
    static final int VOICE_RECOGNITION_STOP = 6;
    static final int SET_MIC_VOLUME = 7;
    static final int SET_SPEAKER_VOLUME = 8;
    static final int REDIAL = 9;
    static final int DIAL_NUMBER = 10;
    static final int DIAL_MEMORY = 11;
    static final int ACCEPT_CALL = 12;
    static final int REJECT_CALL = 13;
    static final int HOLD_CALL = 14;
    static final int TERMINATE_CALL = 15;
    static final int ENTER_PRIVATE_MODE = 16;
    static final int SEND_DTMF = 17;
    static final int EXPLICIT_CALL_TRANSFER = 18;
    static final int LAST_VTAG_NUMBER = 19;

    // internal actions
    static final int QUERY_CURRENT_CALLS = 50;
    static final int QUERY_OPERATOR_NAME = 51;
    static final int SUBSCRIBER_INFO = 52;
    // special action to handle terminating specific call from multiparty call
    static final int TERMINATE_SPECIFIC_CALL = 53;

    private static final int STACK_EVENT = 100;

    private final Disconnected mDisconnected;
    private final Connecting mConnecting;
    private final Connected mConnected;
    private final AudioOn mAudioOn;

    private final HandsfreeClientService mService;

    private Hashtable<Integer, BluetoothHandsfreeClientCall> mCalls;
    private Hashtable<Integer, BluetoothHandsfreeClientCall> mCallsUpdate;
    private boolean mQueryCallsSupported;

    private int mIndicatorNetworkState;
    private int mIndicatorNetworkType;
    private int mIndicatorNetworkSignal;
    private int mIndicatorBatteryLevel;

    private int mIndicatorCall;
    private int mIndicatorCallSetup;
    private int mIndicatorCallHeld;
    private boolean mVgsFromStack = false;
    private boolean mVgmFromStack = false;
    private Uri alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
    private Ringtone mRingtone = null;

    private String mOperatorName;
    private String mSubscriberInfo;

    private int mVoiceRecognitionActive;
    private int mInBandRingtone;

    // queue of send actions (pair action, action_data)
    private Queue<Pair<Integer, Object>> mQueuedActions;

    // last executed command, before action is complete e.g. waiting for some
    // indicator
    private Pair<Integer, Object> mPendingAction;

    private final AudioManager mAudioManager;
    private int mAudioState;
    private boolean mAudioWbs;
    private final BluetoothAdapter mAdapter;
    private boolean mNativeAvailable;

    // currently connected device
    private BluetoothDevice mCurrentDevice = null;

    // general peer features and call handling features
    private int mPeerFeatures;
    private int mChldFeatures;

    static {
        classInitNative();
    }

    private void clearPendingAction() {
        mPendingAction = new Pair<Integer, Object>(NO_ACTION, 0);
    }

    private void addQueuedAction(int action) {
        addQueuedAction(action, 0);
    }

    private void addQueuedAction(int action, Object data) {
        mQueuedActions.add(new Pair<Integer, Object>(action, data));
    }

    private void addQueuedAction(int action, int data) {
        mQueuedActions.add(new Pair<Integer, Object>(action, data));
    }

    private void addCall(int state, String number) {
        Log.d(TAG, "addToCalls state:" + state + " number:" + number);

        boolean outgoing = state == BluetoothHandsfreeClientCall.CALL_STATE_DIALING ||
               state == BluetoothHandsfreeClientCall.CALL_STATE_ALERTING;

        // new call always takes lowest possible id, starting with 1
        Integer id = 1;
        while (mCalls.containsKey(id)) {
            id++;
        }

        BluetoothHandsfreeClientCall c = new BluetoothHandsfreeClientCall(id, state, number, false,
                outgoing);
        mCalls.put(id, c);

        sendCallChangedIntent(c);
    }

    private void removeCalls(int... states) {
        Log.d(TAG, "removeFromCalls states:" + Arrays.toString(states));

        Iterator<Hashtable.Entry<Integer, BluetoothHandsfreeClientCall>> it;

        it = mCalls.entrySet().iterator();
        while (it.hasNext()) {
            BluetoothHandsfreeClientCall c = it.next().getValue();

            for (int s : states) {
                if (c.getState() == s) {
                    it.remove();
                    setCallState(c, BluetoothHandsfreeClientCall.CALL_STATE_TERMINATED);
                    break;
                }
            }
        }
    }

    private void changeCallsState(int old_state, int new_state) {
        Log.d(TAG, "changeStateFromCalls old:" + old_state + " new: " + new_state);

        for (BluetoothHandsfreeClientCall c : mCalls.values()) {
            if (c.getState() == old_state) {
                setCallState(c, new_state);
            }
        }
    }

    private BluetoothHandsfreeClientCall getCall(int... states) {
        Log.d(TAG, "getFromCallsWithStates states:" + Arrays.toString(states));
        for (BluetoothHandsfreeClientCall c : mCalls.values()) {
            for (int s : states) {
                if (c.getState() == s) {
                    return c;
                }
            }
        }

        return null;
    }

    private int callsInState(int state) {
        int i = 0;
        for (BluetoothHandsfreeClientCall c : mCalls.values()) {
            if (c.getState() == state) {
                i++;
            }
        }

        return i;
    }

    private void updateCallsMultiParty() {
        boolean multi = callsInState(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE) > 1;

        for (BluetoothHandsfreeClientCall c : mCalls.values()) {
            if (c.getState() == BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE) {
                if (c.isMultiParty() == multi) {
                    continue;
                }

                c.setMultiParty(multi);
                sendCallChangedIntent(c);
            } else {
                if (c.isMultiParty()) {
                    c.setMultiParty(false);
                    sendCallChangedIntent(c);
                }
            }
        }
    }

    private void setCallState(BluetoothHandsfreeClientCall c, int state) {
        if (state == c.getState()) {
            return;
        }
        //abandon focus here
        if (state == BluetoothHandsfreeClientCall.CALL_STATE_TERMINATED) {
            if (mAudioManager.getMode() != AudioManager.MODE_NORMAL) {
                mAudioManager.setMode(AudioManager.MODE_NORMAL);
                Log.d(TAG, "abandonAudioFocus ");
                // abandon audio focus after the mode has been set back to normal
                mAudioManager.abandonAudioFocusForCall();
            }
        }
        c.setState(state);
        sendCallChangedIntent(c);
    }

    private void sendCallChangedIntent(BluetoothHandsfreeClientCall c) {
        Intent intent = new Intent(BluetoothHandsfreeClient.ACTION_CALL_CHANGED);
        intent.putExtra(BluetoothHandsfreeClient.EXTRA_CALL, c);
        mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private boolean waitForIndicators(int call, int callsetup, int callheld) {
        // all indicators initial values received
        if (mIndicatorCall != -1 && mIndicatorCallSetup != -1 &&
                mIndicatorCallHeld != -1) {
            return false;
        }

        if (call != -1) {
            mIndicatorCall = call;
        } else if (callsetup != -1) {
            mIndicatorCallSetup = callsetup;
        } else if (callheld != -1) {
            mIndicatorCallHeld = callheld;
        }

        // still waiting for some indicators
        if (mIndicatorCall == -1 || mIndicatorCallSetup == -1 ||
                mIndicatorCallHeld == -1) {
            return true;
        }

        // for start always query calls to define if it is supported
        mQueryCallsSupported = queryCallsStart();

        if (mQueryCallsSupported) {
            return true;
        }

        // no support for querying calls

        switch (mIndicatorCallSetup) {
            case HandsfreeClientHalConstants.CALLSETUP_INCOMING:
                addCall(BluetoothHandsfreeClientCall.CALL_STATE_INCOMING, "");
                break;
            case HandsfreeClientHalConstants.CALLSETUP_OUTGOING:
                addCall(BluetoothHandsfreeClientCall.CALL_STATE_DIALING, "");
                break;
            case HandsfreeClientHalConstants.CALLSETUP_ALERTING:
                addCall(BluetoothHandsfreeClientCall.CALL_STATE_ALERTING, "");
                break;
            case HandsfreeClientHalConstants.CALLSETUP_NONE:
            default:
                break;
        }

        switch (mIndicatorCall) {
            case HandsfreeClientHalConstants.CALL_CALLS_IN_PROGRESS:
                addCall(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE, "");
                break;
            case HandsfreeClientHalConstants.CALL_NO_CALLS_IN_PROGRESS:
            default:
                break;
        }

        switch (mIndicatorCallHeld) {
            case HandsfreeClientHalConstants.CALLHELD_HOLD_AND_ACTIVE:
            case HandsfreeClientHalConstants.CALLHELD_HOLD:
                addCall(BluetoothHandsfreeClientCall.CALL_STATE_HELD, "");
                break;
            case HandsfreeClientHalConstants.CALLHELD_NONE:
            default:
                break;
        }

        return true;
    }

    private void updateCallIndicator(int call) {
        Log.d(TAG, "updateCallIndicator " + call);

        if (waitForIndicators(call, -1, -1)) {
            return;
        }

        if (mQueryCallsSupported) {
            sendMessage(QUERY_CURRENT_CALLS);
            return;
        }

        BluetoothHandsfreeClientCall c = null;

        switch (call) {
            case HandsfreeClientHalConstants.CALL_NO_CALLS_IN_PROGRESS:
                removeCalls(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE,
                        BluetoothHandsfreeClientCall.CALL_STATE_HELD,
                        BluetoothHandsfreeClientCall.CALL_STATE_HELD_BY_RESPONSE_AND_HOLD);

                break;
            case HandsfreeClientHalConstants.CALL_CALLS_IN_PROGRESS:
                if (mIndicatorCall == HandsfreeClientHalConstants.CALL_CALLS_IN_PROGRESS) {
                    // WP7.8 is sending call=1 before setup=0 when rejecting
                    // waiting call
                    if (mIndicatorCallSetup != HandsfreeClientHalConstants.CALLSETUP_NONE) {
                        c = getCall(BluetoothHandsfreeClientCall.CALL_STATE_WAITING);
                        if (c != null) {
                            setCallState(c, BluetoothHandsfreeClientCall.CALL_STATE_TERMINATED);
                            mCalls.remove(c.getId());
                        }
                    }

                    break;
                }

                // if there is only waiting call it is changed to incoming so
                // don't
                // handle it here
                if (mIndicatorCallSetup != HandsfreeClientHalConstants.CALLSETUP_NONE) {
                    c = getCall(BluetoothHandsfreeClientCall.CALL_STATE_DIALING,
                            BluetoothHandsfreeClientCall.CALL_STATE_ALERTING,
                            BluetoothHandsfreeClientCall.CALL_STATE_INCOMING);
                    if (c != null) {
                        setCallState(c, BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                    }
                }

                updateCallsMultiParty();
                break;
            default:
                break;
        }

        mIndicatorCall = call;
    }

    private void updateCallSetupIndicator(int callsetup) {
        Log.d(TAG, "updateCallSetupIndicator " + callsetup + " " + mPendingAction.first);

        if (mRingtone != null && mRingtone.isPlaying()) {
            Log.d(TAG,"stopping ring after no response");
            mRingtone.stop();
        }

        if (waitForIndicators(-1, callsetup, -1)) {
            return;
        }

        if (mQueryCallsSupported) {
            sendMessage(QUERY_CURRENT_CALLS);
            return;
        }

        switch (callsetup) {
            case HandsfreeClientHalConstants.CALLSETUP_NONE:
                switch (mPendingAction.first) {
                    case ACCEPT_CALL:
                        switch ((Integer) mPendingAction.second) {
                            case HandsfreeClientHalConstants.CALL_ACTION_ATA:
                                removeCalls(BluetoothHandsfreeClientCall.CALL_STATE_DIALING,
                                        BluetoothHandsfreeClientCall.CALL_STATE_ALERTING);
                                clearPendingAction();
                                break;
                            case HandsfreeClientHalConstants.CALL_ACTION_CHLD_1:
                                removeCalls(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                                changeCallsState(BluetoothHandsfreeClientCall.CALL_STATE_WAITING,
                                        BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                                clearPendingAction();
                                break;
                            case HandsfreeClientHalConstants.CALL_ACTION_CHLD_2:
                                // no specific order for callsetup=0 and
                                // callheld=1
                                if (mIndicatorCallHeld ==
                                        HandsfreeClientHalConstants.CALLHELD_HOLD_AND_ACTIVE) {
                                    clearPendingAction();
                                }
                                break;
                            case HandsfreeClientHalConstants.CALL_ACTION_CHLD_3:
                                if (mIndicatorCallHeld ==
                                        HandsfreeClientHalConstants.CALLHELD_NONE) {
                                    clearPendingAction();
                                }
                                break;
                            default:
                                Log.e(TAG, "Unexpected callsetup=0 while in action ACCEPT_CALL");
                                break;
                        }
                        break;
                    case REJECT_CALL:
                        switch ((Integer) mPendingAction.second) {
                            case HandsfreeClientHalConstants.CALL_ACTION_CHUP:
                                removeCalls(BluetoothHandsfreeClientCall.CALL_STATE_INCOMING);
                                clearPendingAction();
                                break;
                            case HandsfreeClientHalConstants.CALL_ACTION_CHLD_0:
                                removeCalls(BluetoothHandsfreeClientCall.CALL_STATE_WAITING);
                                clearPendingAction();
                                break;
                            default:
                                Log.e(TAG, "Unexpected callsetup=0 while in action REJECT_CALL");
                                break;
                        }
                        break;
                    case DIAL_NUMBER:
                    case DIAL_MEMORY:
                    case REDIAL:
                    case NO_ACTION:
                    case TERMINATE_CALL:
                        removeCalls(BluetoothHandsfreeClientCall.CALL_STATE_INCOMING,
                                BluetoothHandsfreeClientCall.CALL_STATE_DIALING,
                                BluetoothHandsfreeClientCall.CALL_STATE_WAITING,
                                BluetoothHandsfreeClientCall.CALL_STATE_ALERTING);
                        clearPendingAction();
                        break;
                    default:
                        Log.e(TAG, "Unexpected callsetup=0 while in action " +
                                mPendingAction.first);
                        break;
                }
                break;
            case HandsfreeClientHalConstants.CALLSETUP_ALERTING:
                BluetoothHandsfreeClientCall c =
                        getCall(BluetoothHandsfreeClientCall.CALL_STATE_DIALING);
                if (c == null) {
                    if (mPendingAction.first == DIAL_NUMBER) {
                        addCall(BluetoothHandsfreeClientCall.CALL_STATE_ALERTING,
                                (String) mPendingAction.second);
                    } else {
                        addCall(BluetoothHandsfreeClientCall.CALL_STATE_ALERTING, "");
                    }
                } else {
                    setCallState(c, BluetoothHandsfreeClientCall.CALL_STATE_ALERTING);
                }

                switch (mPendingAction.first) {
                    case DIAL_NUMBER:
                    case DIAL_MEMORY:
                    case REDIAL:
                        clearPendingAction();
                        break;
                    default:
                        break;
                }
                break;
            case HandsfreeClientHalConstants.CALLSETUP_OUTGOING:
                if (mPendingAction.first == DIAL_NUMBER) {
                    addCall(BluetoothHandsfreeClientCall.CALL_STATE_DIALING,
                            (String) mPendingAction.second);
                } else {
                    addCall(BluetoothHandsfreeClientCall.CALL_STATE_DIALING, "");
                }
                break;
            case HandsfreeClientHalConstants.CALLSETUP_INCOMING:
                if (getCall(BluetoothHandsfreeClientCall.CALL_STATE_WAITING) == null)
                {
                    // will get number in clip if known
                    addCall(BluetoothHandsfreeClientCall.CALL_STATE_INCOMING, "");
                }
                break;
            default:
                break;
        }

        updateCallsMultiParty();

        mIndicatorCallSetup = callsetup;
    }

    private void updateCallHeldIndicator(int callheld) {
        Log.d(TAG, "updateCallHeld " + callheld);

        if (waitForIndicators(-1, -1, callheld)) {
            return;
        }

        if (mQueryCallsSupported) {
            sendMessage(QUERY_CURRENT_CALLS);
            return;
        }

        switch (callheld) {
            case HandsfreeClientHalConstants.CALLHELD_NONE:
                switch (mPendingAction.first) {
                    case REJECT_CALL:
                        removeCalls(BluetoothHandsfreeClientCall.CALL_STATE_HELD);
                        clearPendingAction();
                        break;
                    case ACCEPT_CALL:
                        switch ((Integer) mPendingAction.second) {
                            case HandsfreeClientHalConstants.CALL_ACTION_CHLD_1:
                                removeCalls(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                                changeCallsState(BluetoothHandsfreeClientCall.CALL_STATE_HELD,
                                        BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                                clearPendingAction();
                                break;
                            case HandsfreeClientHalConstants.CALL_ACTION_CHLD_3:
                                changeCallsState(BluetoothHandsfreeClientCall.CALL_STATE_HELD,
                                        BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                                clearPendingAction();
                                break;
                            default:
                                break;
                        }
                        break;
                    case NO_ACTION:
                        if (mIndicatorCall == HandsfreeClientHalConstants.CALL_CALLS_IN_PROGRESS &&
                                mIndicatorCallHeld == HandsfreeClientHalConstants.CALLHELD_HOLD) {
                            changeCallsState(BluetoothHandsfreeClientCall.CALL_STATE_HELD,
                                    BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                            break;
                        }

                        removeCalls(BluetoothHandsfreeClientCall.CALL_STATE_HELD);
                        break;
                    default:
                        Log.e(TAG, "Unexpected callheld=0 while in action " + mPendingAction.first);
                        break;
                }
                break;
            case HandsfreeClientHalConstants.CALLHELD_HOLD_AND_ACTIVE:
                switch (mPendingAction.first) {
                    case ACCEPT_CALL:
                        if ((Integer) mPendingAction.second ==
                                HandsfreeClientHalConstants.CALL_ACTION_CHLD_2) {
                            BluetoothHandsfreeClientCall c =
                                    getCall(BluetoothHandsfreeClientCall.CALL_STATE_WAITING);
                            if (c != null) { // accept
                                changeCallsState(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE,
                                        BluetoothHandsfreeClientCall.CALL_STATE_HELD);
                                setCallState(c, BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                            } else { // swap
                                for (BluetoothHandsfreeClientCall cc : mCalls.values()) {
                                    if (cc.getState() ==
                                            BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE) {
                                        setCallState(cc,
                                                BluetoothHandsfreeClientCall.CALL_STATE_HELD);
                                    } else if (cc.getState() ==
                                            BluetoothHandsfreeClientCall.CALL_STATE_HELD) {
                                        setCallState(cc,
                                                BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                                    }
                                }
                            }
                            clearPendingAction();
                        }
                        break;
                    case NO_ACTION:
                        BluetoothHandsfreeClientCall c =
                                getCall(BluetoothHandsfreeClientCall.CALL_STATE_WAITING);
                        if (c != null) { // accept
                            changeCallsState(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE,
                                    BluetoothHandsfreeClientCall.CALL_STATE_HELD);
                            setCallState(c, BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                            break;
                        }

                        // swap
                        for (BluetoothHandsfreeClientCall cc : mCalls.values()) {
                            if (cc.getState() == BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE) {
                                setCallState(cc, BluetoothHandsfreeClientCall.CALL_STATE_HELD);
                            } else if (cc.getState() == BluetoothHandsfreeClientCall.CALL_STATE_HELD) {
                                setCallState(cc, BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                            }
                        }
                        break;
                    case ENTER_PRIVATE_MODE:
                        for (BluetoothHandsfreeClientCall cc : mCalls.values()) {
                            if (cc != (BluetoothHandsfreeClientCall) mPendingAction.second) {
                                setCallState(cc, BluetoothHandsfreeClientCall.CALL_STATE_HELD);
                            }
                        }
                        clearPendingAction();
                        break;
                    default:
                        Log.e(TAG, "Unexpected callheld=0 while in action " + mPendingAction.first);
                        break;
                }
                break;
            case HandsfreeClientHalConstants.CALLHELD_HOLD:
                switch (mPendingAction.first) {
                    case DIAL_NUMBER:
                    case DIAL_MEMORY:
                    case REDIAL:
                        changeCallsState(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE,
                                BluetoothHandsfreeClientCall.CALL_STATE_HELD);
                        break;
                    case REJECT_CALL:
                        switch ((Integer) mPendingAction.second) {
                            case HandsfreeClientHalConstants.CALL_ACTION_CHLD_1:
                                removeCalls(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                                changeCallsState(BluetoothHandsfreeClientCall.CALL_STATE_HELD,
                                        BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                                clearPendingAction();
                                break;
                            case HandsfreeClientHalConstants.CALL_ACTION_CHLD_3:
                                changeCallsState(BluetoothHandsfreeClientCall.CALL_STATE_HELD,
                                        BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                                clearPendingAction();
                                break;
                            default:
                                break;
                        }
                        break;
                    case TERMINATE_CALL:
                    case NO_ACTION:
                        removeCalls(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                        break;
                    default:
                        Log.e(TAG, "Unexpected callheld=0 while in action " + mPendingAction.first);
                        break;
                }
                break;
            default:
                break;
        }

        updateCallsMultiParty();

        mIndicatorCallHeld = callheld;
    }

    private void updateRespAndHold(int resp_and_hold) {
        Log.d(TAG, "updatRespAndHold " + resp_and_hold);

        if (mQueryCallsSupported) {
            sendMessage(QUERY_CURRENT_CALLS);
        }

        BluetoothHandsfreeClientCall c = null;

        switch (resp_and_hold) {
            case HandsfreeClientHalConstants.RESP_AND_HOLD_HELD:
                // might be active if it was resp-and-hold before SLC created
                c = getCall(BluetoothHandsfreeClientCall.CALL_STATE_INCOMING,
                        BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                if (c != null) {
                    setCallState(c,
                            BluetoothHandsfreeClientCall.CALL_STATE_HELD_BY_RESPONSE_AND_HOLD);
                } else {
                    addCall(BluetoothHandsfreeClientCall.CALL_STATE_HELD_BY_RESPONSE_AND_HOLD, "");
                }
                break;
            case HandsfreeClientHalConstants.RESP_AND_HOLD_ACCEPT:
                c = getCall(BluetoothHandsfreeClientCall.CALL_STATE_HELD_BY_RESPONSE_AND_HOLD);
                if (c != null) {
                    setCallState(c, BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
                }
                if (mPendingAction.first == ACCEPT_CALL &&
                        (Integer) mPendingAction.second ==
                        HandsfreeClientHalConstants.CALL_ACTION_BTRH_1) {
                    clearPendingAction();
                }
                break;
            case HandsfreeClientHalConstants.RESP_AND_HOLD_REJECT:
                removeCalls(BluetoothHandsfreeClientCall.CALL_STATE_HELD_BY_RESPONSE_AND_HOLD);
                break;
            default:
                break;
        }
    }

    private void updateClip(String number) {
        Log.d(TAG, "updateClip number: " + number);

        BluetoothHandsfreeClientCall c = getCall(BluetoothHandsfreeClientCall.CALL_STATE_INCOMING);

        if (c == null) {
            // MeeGo sends CLCC indicating waiting call followed by CLIP when call state changes
            // from waiting to incoming in 3WC scenarios. Handle this call state transfer here.
            BluetoothHandsfreeClientCall cw = getCall(BluetoothHandsfreeClientCall.CALL_STATE_WAITING);
            if(cw != null) {
                setCallState(cw, BluetoothHandsfreeClientCall.CALL_STATE_INCOMING);
            }
            else {
                addCall(BluetoothHandsfreeClientCall.CALL_STATE_INCOMING, number);
            }
        } else {
            c.setNumber(number);
            sendCallChangedIntent(c);
        }
    }

    private void addCallWaiting(String number) {
        Log.d(TAG, "addCallWaiting number: " + number);

        if (getCall(BluetoothHandsfreeClientCall.CALL_STATE_WAITING) == null) {
            addCall(BluetoothHandsfreeClientCall.CALL_STATE_WAITING, number);
        }
    }

    // use ECS
    private boolean queryCallsStart() {
        Log.d(TAG, "queryCallsStart");

        // not supported
        if (mQueryCallsSupported == false) {
            return false;
        }

        clearPendingAction();

        // already started
        if (mCallsUpdate != null) {
            return true;
        }

        if (queryCurrentCallsNative()) {
            mCallsUpdate = new Hashtable<Integer, BluetoothHandsfreeClientCall>();
            addQueuedAction(QUERY_CURRENT_CALLS, 0);
            return true;
        }

        Log.i(TAG, "updateCallsStart queryCurrentCallsNative failed");
        mQueryCallsSupported = false;
        mCallsUpdate = null;
        return false;
    }

    private void queryCallsDone() {
        Log.d(TAG, "queryCallsDone");
        Iterator<Hashtable.Entry<Integer, BluetoothHandsfreeClientCall>> it;

        // check if any call was removed
        it = mCalls.entrySet().iterator();
        while (it.hasNext()) {
            Hashtable.Entry<Integer, BluetoothHandsfreeClientCall> entry = it.next();

            if (mCallsUpdate.containsKey(entry.getKey())) {
                continue;
            }

            Log.d(TAG, "updateCallsDone call removed id:" + entry.getValue().getId());
            BluetoothHandsfreeClientCall c = entry.getValue();

            setCallState(c, BluetoothHandsfreeClientCall.CALL_STATE_TERMINATED);
        }

        /* check if any calls changed or new call is present */
        it = mCallsUpdate.entrySet().iterator();
        while (it.hasNext()) {
            Hashtable.Entry<Integer, BluetoothHandsfreeClientCall> entry = it.next();

            if (mCalls.containsKey(entry.getKey())) {
                // avoid losing number if was not present in clcc
                if (entry.getValue().getNumber().equals("")) {
                    entry.getValue().setNumber(mCalls.get(entry.getKey()).getNumber());
                }

                if (mCalls.get(entry.getKey()).equals(entry.getValue())) {
                    continue;
                }

                Log.d(TAG, "updateCallsDone call changed id:" + entry.getValue().getId());
                sendCallChangedIntent(entry.getValue());
            } else {
                Log.d(TAG, "updateCallsDone new call id:" + entry.getValue().getId());
                sendCallChangedIntent(entry.getValue());
            }
        }

        mCalls = mCallsUpdate;
        mCallsUpdate = null;

        if (loopQueryCalls()) {
            Log.d(TAG, "queryCallsDone ambigious calls, starting call query loop");
            sendMessageDelayed(QUERY_CURRENT_CALLS, 1523);
        }
    }

    private void queryCallsUpdate(int id, int state, String number, boolean multiParty,
            boolean outgoing) {
        Log.d(TAG, "queryCallsUpdate: " + id);

        // should not happen
        if (mCallsUpdate == null) {
            return;
        }

        mCallsUpdate.put(id, new BluetoothHandsfreeClientCall(id, state, number, multiParty,
                outgoing));
    }

    // helper function for determining if query calls should be looped
    private boolean loopQueryCalls() {
        if (callsInState(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE) > 1) {
            return true;
        }

        // Workaround for Windows Phone 7.8 not sending callsetup=0 after
        // rejecting incoming call in 3WC use case (when no active calls present).
        // Fixes both, AG and HF rejecting the call.
        BluetoothHandsfreeClientCall c = getCall(BluetoothHandsfreeClientCall.CALL_STATE_INCOMING);
        if (c != null && mIndicatorCallSetup == HandsfreeClientHalConstants.CALLSETUP_NONE)
            return true;

        return false;
    }

    private void acceptCall(int flag, boolean retry) {
        int action;

        Log.d(TAG, "acceptCall: (" + flag + ")");

        BluetoothHandsfreeClientCall c = getCall(BluetoothHandsfreeClientCall.CALL_STATE_INCOMING,
                BluetoothHandsfreeClientCall.CALL_STATE_WAITING);
        if (c == null) {
            c = getCall(BluetoothHandsfreeClientCall.CALL_STATE_HELD_BY_RESPONSE_AND_HOLD,
                    BluetoothHandsfreeClientCall.CALL_STATE_HELD);

            if (c == null) {
                return;
            }
        }

        switch (c.getState()) {
            case BluetoothHandsfreeClientCall.CALL_STATE_INCOMING:
                if (flag != BluetoothHandsfreeClient.CALL_ACCEPT_NONE) {
                    return;
                }

                // Some NOKIA phones with Windows Phone 7.8 and MeeGo requires CHLD=1
                // for accepting incoming call if it is the only call present after
                // second active remote has disconnected (3WC scenario - call state
                // changes from waiting to incoming). On the other hand some Android
                // phones and iPhone requires ATA. Try to handle those gently by
                // first issuing ATA. Failing means that AG is probably one of those
                // phones that requires CHLD=1. Handle this case when we are retrying.
                // Accepting incoming calls when there is held one and
                // no active should also be handled by ATA.
                action = HandsfreeClientHalConstants.CALL_ACTION_ATA;

                if (mCalls.size() == 1 && retry) {
                    action = HandsfreeClientHalConstants.CALL_ACTION_CHLD_1;
                }
                break;
            case BluetoothHandsfreeClientCall.CALL_STATE_WAITING:
                if (callsInState(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE) == 0) {
                    // if no active calls present only plain accept is allowed
                    if (flag != BluetoothHandsfreeClient.CALL_ACCEPT_NONE) {
                        return;
                    }

                    // Some phones (WP7) require ATA instead of CHLD=2
                    // to accept waiting call if no active calls are present.
                    if (retry) {
                        action = HandsfreeClientHalConstants.CALL_ACTION_ATA;
                    } else {
                        action = HandsfreeClientHalConstants.CALL_ACTION_CHLD_2;
                    }
                    break;
                }

                // if active calls are present action must be selected
                if (flag == BluetoothHandsfreeClient.CALL_ACCEPT_HOLD) {
                    action = HandsfreeClientHalConstants.CALL_ACTION_CHLD_2;
                } else if (flag == BluetoothHandsfreeClient.CALL_ACCEPT_TERMINATE) {
                    action = HandsfreeClientHalConstants.CALL_ACTION_CHLD_1;
                } else {
                    return;
                }
                break;
            case BluetoothHandsfreeClientCall.CALL_STATE_HELD:
                if (flag == BluetoothHandsfreeClient.CALL_ACCEPT_HOLD) {
                    action = HandsfreeClientHalConstants.CALL_ACTION_CHLD_2;
                } else if (flag == BluetoothHandsfreeClient.CALL_ACCEPT_TERMINATE) {
                    action = HandsfreeClientHalConstants.CALL_ACTION_CHLD_1;
                } else if (getCall(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE) != null) {
                    action = HandsfreeClientHalConstants.CALL_ACTION_CHLD_3;
                } else {
                    action = HandsfreeClientHalConstants.CALL_ACTION_CHLD_2;
                }
                break;
            case BluetoothHandsfreeClientCall.CALL_STATE_HELD_BY_RESPONSE_AND_HOLD:
                action = HandsfreeClientHalConstants.CALL_ACTION_BTRH_1;
                break;
            case BluetoothHandsfreeClientCall.CALL_STATE_ALERTING:
            case BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE:
            case BluetoothHandsfreeClientCall.CALL_STATE_DIALING:
            default:
                return;
        }

        if (handleCallActionNative(action, 0)) {
            addQueuedAction(ACCEPT_CALL, action);
        } else {
            Log.e(TAG, "ERROR: Couldn't accept a call, action:" + action);
        }
    }

    private void rejectCall() {
        int action;

        Log.d(TAG, "rejectCall");
        if ( mRingtone != null && mRingtone.isPlaying()) {
            Log.d(TAG,"stopping ring after call reject");
            mRingtone.stop();
        }

        BluetoothHandsfreeClientCall c =
                getCall(BluetoothHandsfreeClientCall.CALL_STATE_INCOMING,
                BluetoothHandsfreeClientCall.CALL_STATE_WAITING,
                BluetoothHandsfreeClientCall.CALL_STATE_HELD_BY_RESPONSE_AND_HOLD,
                BluetoothHandsfreeClientCall.CALL_STATE_HELD);
        if (c == null) {
            return;
        }

        switch (c.getState()) {
            case BluetoothHandsfreeClientCall.CALL_STATE_INCOMING:
                action = HandsfreeClientHalConstants.CALL_ACTION_CHUP;
                break;
            case BluetoothHandsfreeClientCall.CALL_STATE_WAITING:
            case BluetoothHandsfreeClientCall.CALL_STATE_HELD:
                action = HandsfreeClientHalConstants.CALL_ACTION_CHLD_0;
                break;
            case BluetoothHandsfreeClientCall.CALL_STATE_HELD_BY_RESPONSE_AND_HOLD:
                action = HandsfreeClientHalConstants.CALL_ACTION_BTRH_2;
                break;
            case BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE:
            case BluetoothHandsfreeClientCall.CALL_STATE_DIALING:
            case BluetoothHandsfreeClientCall.CALL_STATE_ALERTING:
            default:
                return;
        }

        if (handleCallActionNative(action, 0)) {
            addQueuedAction(REJECT_CALL, action);
        } else {
            Log.e(TAG, "ERROR: Couldn't reject a call, action:" + action);
        }
    }

    private void holdCall() {
        int action;

        Log.d(TAG, "holdCall");

        BluetoothHandsfreeClientCall c = getCall(BluetoothHandsfreeClientCall.CALL_STATE_INCOMING);
        if (c != null) {
            action = HandsfreeClientHalConstants.CALL_ACTION_BTRH_0;
        } else {
            c = getCall(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE);
            if (c == null) {
                return;
            }

            action = HandsfreeClientHalConstants.CALL_ACTION_CHLD_2;
        }

        if (handleCallActionNative(action, 0)) {
            addQueuedAction(HOLD_CALL, action);
        } else {
            Log.e(TAG, "ERROR: Couldn't hold a call, action:" + action);
        }
    }

    private void terminateCall(int idx) {
        Log.d(TAG, "terminateCall: " + idx);

        if (idx == 0) {
            int action = HandsfreeClientHalConstants.CALL_ACTION_CHUP;

            BluetoothHandsfreeClientCall c = getCall(
                    BluetoothHandsfreeClientCall.CALL_STATE_DIALING,
                    BluetoothHandsfreeClientCall.CALL_STATE_ALERTING);
            if (c != null) {
                if (handleCallActionNative(action, 0)) {
                    addQueuedAction(TERMINATE_CALL, action);
                } else {
                    Log.e(TAG, "ERROR: Couldn't terminate outgoing call");
                }
            }

            if (callsInState(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE) > 0) {
                if (handleCallActionNative(action, 0)) {
                    addQueuedAction(TERMINATE_CALL, action);
                } else {
                    Log.e(TAG, "ERROR: Couldn't terminate active calls");
                }
            }
        } else {
            int action;
            BluetoothHandsfreeClientCall c = mCalls.get(idx);

            if (c == null) {
                return;
            }

            switch (c.getState()) {
                case BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE:
                    action = HandsfreeClientHalConstants.CALL_ACTION_CHLD_1x;
                    break;
                case BluetoothHandsfreeClientCall.CALL_STATE_DIALING:
                case BluetoothHandsfreeClientCall.CALL_STATE_ALERTING:
                    action = HandsfreeClientHalConstants.CALL_ACTION_CHUP;
                    break;
                default:
                    return;
            }

            if (handleCallActionNative(action, idx)) {
                if (action == HandsfreeClientHalConstants.CALL_ACTION_CHLD_1x) {
                    addQueuedAction(TERMINATE_SPECIFIC_CALL, c);
                } else {
                    addQueuedAction(TERMINATE_CALL, action);
                }
            } else {
                Log.e(TAG, "ERROR: Couldn't terminate a call, action:" + action + " id:" + idx);
            }
        }
    }

    private void enterPrivateMode(int idx) {
        Log.d(TAG, "enterPrivateMode: " + idx);

        BluetoothHandsfreeClientCall c = mCalls.get(idx);

        if (c == null) {
            return;
        }

        if (c.getState() != BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE) {
            return;
        }

        if (!c.isMultiParty()) {
            return;
        }

        if (handleCallActionNative(HandsfreeClientHalConstants.CALL_ACTION_CHLD_2x, idx)) {
            addQueuedAction(ENTER_PRIVATE_MODE, c);
        } else {
            Log.e(TAG, "ERROR: Couldn't enter private " + " id:" + idx);
        }
    }

    private void explicitCallTransfer() {
        Log.d(TAG, "explicitCallTransfer");

        // can't transfer call if there is not enough call parties
        if (mCalls.size() < 2) {
            return;
        }

        if (handleCallActionNative(HandsfreeClientHalConstants.CALL_ACTION_CHLD_4, -1)) {
            addQueuedAction(EXPLICIT_CALL_TRANSFER);
        } else {
            Log.e(TAG, "ERROR: Couldn't transfer call");
        }
    }

    public Bundle getCurrentAgFeatures()
    {
        Bundle b = new Bundle();
        if ((mPeerFeatures & HandsfreeClientHalConstants.PEER_FEAT_3WAY) ==
                HandsfreeClientHalConstants.PEER_FEAT_3WAY) {
            b.putBoolean(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_3WAY_CALLING, true);
        }
        if ((mPeerFeatures & HandsfreeClientHalConstants.PEER_FEAT_VREC) ==
                HandsfreeClientHalConstants.PEER_FEAT_VREC) {
            b.putBoolean(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_VOICE_RECOGNITION, true);
        }
        if ((mPeerFeatures & HandsfreeClientHalConstants.PEER_FEAT_VTAG) ==
                HandsfreeClientHalConstants.PEER_FEAT_VTAG) {
            b.putBoolean(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_ATTACH_NUMBER_TO_VT, true);
        }
        if ((mPeerFeatures & HandsfreeClientHalConstants.PEER_FEAT_REJECT) ==
                HandsfreeClientHalConstants.PEER_FEAT_REJECT) {
            b.putBoolean(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_REJECT_CALL, true);
        }
        if ((mPeerFeatures & HandsfreeClientHalConstants.PEER_FEAT_ECC) ==
                HandsfreeClientHalConstants.PEER_FEAT_ECC) {
            b.putBoolean(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_ECC, true);
        }

        // add individual CHLD support extras
        if ((mChldFeatures & HandsfreeClientHalConstants.CHLD_FEAT_HOLD_ACC) ==
                HandsfreeClientHalConstants.CHLD_FEAT_HOLD_ACC) {
            b.putBoolean(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_ACCEPT_HELD_OR_WAITING_CALL, true);
        }
        if ((mChldFeatures & HandsfreeClientHalConstants.CHLD_FEAT_REL) ==
                HandsfreeClientHalConstants.CHLD_FEAT_REL) {
            b.putBoolean(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_RELEASE_HELD_OR_WAITING_CALL, true);
        }
        if ((mChldFeatures & HandsfreeClientHalConstants.CHLD_FEAT_REL_ACC) ==
                HandsfreeClientHalConstants.CHLD_FEAT_REL_ACC) {
            b.putBoolean(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_RELEASE_AND_ACCEPT, true);
        }
        if ((mChldFeatures & HandsfreeClientHalConstants.CHLD_FEAT_MERGE) ==
                HandsfreeClientHalConstants.CHLD_FEAT_MERGE) {
            b.putBoolean(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_MERGE, true);
        }
        if ((mChldFeatures & HandsfreeClientHalConstants.CHLD_FEAT_MERGE_DETACH) ==
                HandsfreeClientHalConstants.CHLD_FEAT_MERGE_DETACH) {
            b.putBoolean(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_MERGE_AND_DETACH, true);
        }

        return b;
    }

    private HandsfreeClientStateMachine(HandsfreeClientService context) {
        super(TAG);
        mService = context;

        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mAudioState = BluetoothHandsfreeClient.STATE_AUDIO_DISCONNECTED;
        mAudioWbs = false;

        if(alert == null) {
            // alert is null, using backup
            alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if(alert == null) {
                // alert backup is null, using 2nd backup
                 alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
        }
        if (alert != null) {
            mRingtone = RingtoneManager.getRingtone(mService, alert);
        } else {
            Log.e(TAG,"alert is NULL no ringtone");
        }
        mIndicatorNetworkState = HandsfreeClientHalConstants.NETWORK_STATE_NOT_AVAILABLE;
        mIndicatorNetworkType = HandsfreeClientHalConstants.SERVICE_TYPE_HOME;
        mIndicatorNetworkSignal = 0;
        mIndicatorBatteryLevel = 0;

        // all will be set on connected
        mIndicatorCall = -1;
        mIndicatorCallSetup = -1;
        mIndicatorCallHeld = -1;

        mOperatorName = null;
        mSubscriberInfo = null;

        mVoiceRecognitionActive = HandsfreeClientHalConstants.VR_STATE_STOPPED;
        mInBandRingtone = HandsfreeClientHalConstants.IN_BAND_RING_NOT_PROVIDED;

        mQueuedActions = new LinkedList<Pair<Integer, Object>>();
        clearPendingAction();

        mCalls = new Hashtable<Integer, BluetoothHandsfreeClientCall>();
        mCallsUpdate = null;
        mQueryCallsSupported = true;

        initializeNative();
        mNativeAvailable = true;

        mDisconnected = new Disconnected();
        mConnecting = new Connecting();
        mConnected = new Connected();
        mAudioOn = new AudioOn();

        addState(mDisconnected);
        addState(mConnecting);
        addState(mConnected);
        addState(mAudioOn, mConnected);

        setInitialState(mDisconnected);
    }

    static HandsfreeClientStateMachine make(HandsfreeClientService context) {
        Log.d(TAG, "make");
        HandsfreeClientStateMachine hfcsm = new HandsfreeClientStateMachine(context);
        hfcsm.start();
        return hfcsm;
    }

    public void doQuit() {
        quitNow();
    }

    public void cleanup() {
        if (mNativeAvailable) {
            cleanupNative();
            mNativeAvailable = false;
        }
    }

    private class Disconnected extends State {
        @Override
        public void enter() {
            Log.d(TAG, "Enter Disconnected: " + getCurrentMessage().what);

            // cleanup
            mIndicatorNetworkState = HandsfreeClientHalConstants.NETWORK_STATE_NOT_AVAILABLE;
            mIndicatorNetworkType = HandsfreeClientHalConstants.SERVICE_TYPE_HOME;
            mIndicatorNetworkSignal = 0;
            mIndicatorBatteryLevel = 0;

            mAudioWbs = false;

            // will be set on connect
            mIndicatorCall = -1;
            mIndicatorCallSetup = -1;
            mIndicatorCallHeld = -1;

            mOperatorName = null;
            mSubscriberInfo = null;

            mQueuedActions = new LinkedList<Pair<Integer, Object>>();
            clearPendingAction();

            mVoiceRecognitionActive = HandsfreeClientHalConstants.VR_STATE_STOPPED;
            mInBandRingtone = HandsfreeClientHalConstants.IN_BAND_RING_NOT_PROVIDED;

            mCalls = new Hashtable<Integer, BluetoothHandsfreeClientCall>();
            mCallsUpdate = null;
            mQueryCallsSupported = true;

            mPeerFeatures = 0;
            mChldFeatures = 0;

            removeMessages(QUERY_CURRENT_CALLS);
        }

        @Override
        public synchronized boolean processMessage(Message message) {
            Log.d(TAG, "Disconnected process message: " + message.what);

            if (mCurrentDevice != null) {
                Log.e(TAG, "ERROR: current device not null in Disconnected");
                return NOT_HANDLED;
            }

            switch (message.what) {
                case CONNECT:
                    BluetoothDevice device = (BluetoothDevice) message.obj;

                    broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                            BluetoothProfile.STATE_DISCONNECTED);

                    if (!connectNative(getByteAddress(device))) {
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_CONNECTING);
                        break;
                    }

                    mCurrentDevice = device;
                    transitionTo(mConnecting);
                    break;
                case DISCONNECT:
                    // ignore
                    break;
                case STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    if (DBG) {
                        Log.d(TAG, "Stack event type: " + event.type);
                    }
                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            Log.d(TAG, "Disconnected: Connection " + event.device
                                    + " state changed:" + event.valueInt);
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        default:
                            Log.e(TAG, "Disconnected: Unexpected stack event: " + event.type);
                            break;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        // in Disconnected state
        private void processConnectionEvent(int state, BluetoothDevice device)
        {
            switch (state) {
                case HandsfreeClientHalConstants.CONNECTION_STATE_CONNECTED:
                    Log.w(TAG, "HFPClient Connecting from Disconnected state");
                    if (okToConnect(device)) {
                        Log.i(TAG, "Incoming AG accepted");
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                BluetoothProfile.STATE_DISCONNECTED);
                        mCurrentDevice = device;
                        transitionTo(mConnecting);
                    } else {
                        Log.i(TAG, "Incoming AG rejected. priority=" + mService.getPriority(device)
                                +
                                " bondState=" + device.getBondState());
                        // reject the connection and stay in Disconnected state
                        // itself
                        disconnectNative(getByteAddress(device));
                        // the other profile connection should be initiated
                        AdapterService adapterService = AdapterService.getAdapterService();
                        if (adapterService != null) {
                            adapterService.connectOtherProfile(device,
                                    AdapterService.PROFILE_CONN_REJECTED);
                        }
                    }
                    break;
                case HandsfreeClientHalConstants.CONNECTION_STATE_CONNECTING:
                case HandsfreeClientHalConstants.CONNECTION_STATE_DISCONNECTED:
                case HandsfreeClientHalConstants.CONNECTION_STATE_DISCONNECTING:
                default:
                    Log.i(TAG, "ignoring state: " + state);
                    break;
            }
        }

        @Override
        public void exit() {
            Log.d(TAG, "Exit Disconnected: " + getCurrentMessage().what);
        }
    }

    private class Connecting extends State {
        @Override
        public void enter() {
            Log.d(TAG, "Enter Connecting: " + getCurrentMessage().what);
        }

        @Override
        public synchronized boolean processMessage(Message message) {
            Log.d(TAG, "Connecting process message: " + message.what);

            boolean retValue = HANDLED;
            switch (message.what) {
                case CONNECT:
                case CONNECT_AUDIO:
                case DISCONNECT:
                    deferMessage(message);
                    break;
                case STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    if (DBG) {
                        Log.d(TAG, "Connecting: event type: " + event.type);
                    }
                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            Log.d(TAG, "Connecting: Connection " + event.device + " state changed:"
                                    + event.valueInt);
                            processConnectionEvent(event.valueInt, event.valueInt2,
                                    event.valueInt3, event.device);
                            break;
                        case EVENT_TYPE_AUDIO_STATE_CHANGED:
                        case EVENT_TYPE_VR_STATE_CHANGED:
                        case EVENT_TYPE_NETWORK_STATE:
                        case EVENT_TYPE_ROAMING_STATE:
                        case EVENT_TYPE_NETWORK_SIGNAL:
                        case EVENT_TYPE_BATTERY_LEVEL:
                        case EVENT_TYPE_CALL:
                        case EVENT_TYPE_CALLSETUP:
                        case EVENT_TYPE_CALLHELD:
                        case EVENT_TYPE_RESP_AND_HOLD:
                        case EVENT_TYPE_CLIP:
                        case EVENT_TYPE_CALL_WAITING:
                        case EVENT_TYPE_VOLUME_CHANGED:
                        case EVENT_TYPE_IN_BAND_RING:
                            deferMessage(message);
                            break;
                        case EVENT_TYPE_CMD_RESULT:
                        case EVENT_TYPE_SUBSCRIBER_INFO:
                        case EVENT_TYPE_CURRENT_CALLS:
                        case EVENT_TYPE_OPERATOR_NAME:
                        default:
                            Log.e(TAG, "Connecting: ignoring stack event: " + event.type);
                            break;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return retValue;
        }

        // in Connecting state
        private void processConnectionEvent(int state, int peer_feat, int chld_feat, BluetoothDevice device) {
            switch (state) {
                case HandsfreeClientHalConstants.CONNECTION_STATE_DISCONNECTED:
                    broadcastConnectionState(mCurrentDevice, BluetoothProfile.STATE_DISCONNECTED,
                            BluetoothProfile.STATE_CONNECTING);
                    mCurrentDevice = null;
                    transitionTo(mDisconnected);
                    break;
                case HandsfreeClientHalConstants.CONNECTION_STATE_SLC_CONNECTED:
                    Log.w(TAG, "HFPClient Connected from Connecting state");

                    mPeerFeatures = peer_feat;
                    mChldFeatures = chld_feat;

                    broadcastConnectionState(mCurrentDevice, BluetoothProfile.STATE_CONNECTED,
                            BluetoothProfile.STATE_CONNECTING);
                    transitionTo(mConnected);

                    // TODO get max stream volume and scale 0-15
                    sendMessage(obtainMessage(HandsfreeClientStateMachine.SET_SPEAKER_VOLUME,
                            mAudioManager.getStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO), 0));
                    sendMessage(obtainMessage(HandsfreeClientStateMachine.SET_MIC_VOLUME,
                            mAudioManager.isMicrophoneMute() ? 0 : 15, 0));

                    // query subscriber info
                    sendMessage(HandsfreeClientStateMachine.SUBSCRIBER_INFO);
                    break;
                case HandsfreeClientHalConstants.CONNECTION_STATE_CONNECTED:
                    if (!mCurrentDevice.equals(device)) {
                        Log.w(TAG, "incoming connection event, device: " + device);

                        broadcastConnectionState(mCurrentDevice,
                                BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_CONNECTING);
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                BluetoothProfile.STATE_DISCONNECTED);

                        mCurrentDevice = device;
                    }
                    break;
                case HandsfreeClientHalConstants.CONNECTION_STATE_CONNECTING:
                    /* outgoing connecting started */
                    Log.d(TAG, "outgoing connection started, ignore");
                    break;
                case HandsfreeClientHalConstants.CONNECTION_STATE_DISCONNECTING:
                default:
                    Log.e(TAG, "Incorrect state: " + state);
                    break;
            }
        }

        @Override
        public void exit() {
            Log.d(TAG, "Exit Connecting: " + getCurrentMessage().what);
        }
    }

    private class Connected extends State {
        @Override
        public void enter() {
            Log.d(TAG, "Enter Connected: " + getCurrentMessage().what);

            mAudioWbs = false;
        }

        @Override
        public synchronized boolean processMessage(Message message) {
            Log.d(TAG, "Connected process message: " + message.what);
            if (DBG) {
                if (mCurrentDevice == null) {
                    Log.d(TAG, "ERROR: mCurrentDevice is null in Connected");
                    return NOT_HANDLED;
                }
            }

            switch (message.what) {
                case CONNECT:
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (mCurrentDevice.equals(device)) {
                        // already connected to this device, do nothing
                        break;
                    }

                    if (!disconnectNative(getByteAddress(mCurrentDevice))) {
                        // if succeed this will be handled from disconnected
                        // state
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTING,
                                BluetoothProfile.STATE_DISCONNECTED);
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_CONNECTING);
                        break;
                    }

                    // will be handled when entered disconnected
                    deferMessage(message);
                    break;
                case DISCONNECT:
                    BluetoothDevice dev = (BluetoothDevice) message.obj;
                    if (!mCurrentDevice.equals(dev)) {
                        break;
                    }
                    broadcastConnectionState(dev, BluetoothProfile.STATE_DISCONNECTING,
                            BluetoothProfile.STATE_CONNECTED);
                    if (!disconnectNative(getByteAddress(dev))) {
                        // disconnecting failed
                        broadcastConnectionState(dev, BluetoothProfile.STATE_CONNECTED,
                                BluetoothProfile.STATE_DISCONNECTED);
                        break;
                    }
                    break;
                case CONNECT_AUDIO:
                    // TODO: handle audio connection failure
                    if (!connectAudioNative(getByteAddress(mCurrentDevice))) {
                        Log.e(TAG, "ERROR: Couldn't connect Audio.");
                    }
                    break;
                case DISCONNECT_AUDIO:
                    // TODO: handle audio disconnection failure
                    if (!disconnectAudioNative(getByteAddress(mCurrentDevice))) {
                        Log.e(TAG, "ERROR: Couldn't connect Audio.");
                    }
                    break;
                case VOICE_RECOGNITION_START:
                    if (mVoiceRecognitionActive == HandsfreeClientHalConstants.VR_STATE_STOPPED) {
                        if (startVoiceRecognitionNative()) {
                            addQueuedAction(VOICE_RECOGNITION_START);
                        } else {
                            Log.e(TAG, "ERROR: Couldn't start voice recognition");
                        }
                    }
                    break;
                case VOICE_RECOGNITION_STOP:
                    if (mVoiceRecognitionActive == HandsfreeClientHalConstants.VR_STATE_STARTED) {
                        if (stopVoiceRecognitionNative()) {
                            addQueuedAction(VOICE_RECOGNITION_STOP);
                        } else {
                            Log.e(TAG, "ERROR: Couldn't stop voice recognition");
                        }
                    }
                    break;
                case SET_MIC_VOLUME:
                    if (mVgmFromStack) {
                        mVgmFromStack = false;
                        break;
                    }
                    if (setVolumeNative(HandsfreeClientHalConstants.VOLUME_TYPE_MIC, message.arg1)) {
                        addQueuedAction(SET_MIC_VOLUME);
                    }
                    break;
                case SET_SPEAKER_VOLUME:
                    Log.d(TAG,"Volume is set to " + message.arg1);
                    mAudioManager.setParameters("hfp_volume=" + message.arg1);
                    if (mVgsFromStack) {
                        mVgsFromStack = false;
                        break;
                    }
                    if (setVolumeNative(HandsfreeClientHalConstants.VOLUME_TYPE_SPK, message.arg1)) {
                        addQueuedAction(SET_SPEAKER_VOLUME);
                    }
                    break;
                case REDIAL:
                    if (dialNative(null)) {
                        addQueuedAction(REDIAL);
                    } else {
                        Log.e(TAG, "ERROR: Cannot redial");
                    }
                    break;
                case DIAL_NUMBER:
                    if (dialNative((String) message.obj)) {
                        addQueuedAction(DIAL_NUMBER, message.obj);
                    } else {
                        Log.e(TAG, "ERROR: Cannot dial with a given number:" + (String) message.obj);
                    }
                    break;
                case DIAL_MEMORY:
                    if (dialMemoryNative(message.arg1)) {
                        addQueuedAction(DIAL_MEMORY);
                    } else {
                        Log.e(TAG, "ERROR: Cannot dial with a given location:" + message.arg1);
                    }
                    break;
                case ACCEPT_CALL:
                    acceptCall(message.arg1, false);
                    break;
                case REJECT_CALL:
                    rejectCall();
                    break;
                case HOLD_CALL:
                    holdCall();
                    break;
                case TERMINATE_CALL:
                    terminateCall(message.arg1);
                    break;
                case ENTER_PRIVATE_MODE:
                    enterPrivateMode(message.arg1);
                    break;
                case EXPLICIT_CALL_TRANSFER:
                    explicitCallTransfer();
                    break;
                case SEND_DTMF:
                    if (sendDtmfNative((byte) message.arg1)) {
                        addQueuedAction(SEND_DTMF);
                    } else {
                        Log.e(TAG, "ERROR: Couldn't send DTMF");
                    }
                    break;
                case SUBSCRIBER_INFO:
                    if (retrieveSubscriberInfoNative()) {
                        addQueuedAction(SUBSCRIBER_INFO);
                    } else {
                        Log.e(TAG, "ERROR: Couldn't retrieve subscriber info");
                    }
                    break;
                case LAST_VTAG_NUMBER:
                    if (requestLastVoiceTagNumberNative()) {
                        addQueuedAction(LAST_VTAG_NUMBER);
                    } else {
                        Log.e(TAG, "ERROR: Couldn't get last VTAG number");
                    }
                    break;
                case QUERY_CURRENT_CALLS:
                    queryCallsStart();
                    break;
                case STACK_EVENT:
                    Intent intent = null;
                    StackEvent event = (StackEvent) message.obj;
                    if (DBG) {
                        Log.d(TAG, "Connected: event type: " + event.type);
                    }

                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            Log.d(TAG, "Connected: Connection state changed: " + event.device
                                    + ": " + event.valueInt);
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        case EVENT_TYPE_AUDIO_STATE_CHANGED:
                            Log.d(TAG, "Connected: Audio state changed: " + event.device + ": "
                                    + event.valueInt);
                            processAudioEvent(event.valueInt, event.device);
                            break;
                        case EVENT_TYPE_NETWORK_STATE:
                            Log.d(TAG, "Connected: Network state: " + event.valueInt);

                            mIndicatorNetworkState = event.valueInt;

                            intent = new Intent(BluetoothHandsfreeClient.ACTION_AG_EVENT);
                            intent.putExtra(BluetoothHandsfreeClient.EXTRA_NETWORK_STATUS,
                                    event.valueInt);

                            if (mIndicatorNetworkState ==
                                    HandsfreeClientHalConstants.NETWORK_STATE_NOT_AVAILABLE) {
                                mOperatorName = null;
                                intent.putExtra(BluetoothHandsfreeClient.EXTRA_OPERATOR_NAME,
                                        mOperatorName);
                            }

                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                            mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);

                            if (mIndicatorNetworkState ==
                                    HandsfreeClientHalConstants.NETWORK_STATE_AVAILABLE) {
                                if (queryCurrentOperatorNameNative()) {
                                    addQueuedAction(QUERY_OPERATOR_NAME);
                                } else {
                                    Log.e(TAG, "ERROR: Couldn't querry operator name");
                                }
                            }
                            break;
                        case EVENT_TYPE_ROAMING_STATE:
                            Log.d(TAG, "Connected: Roaming state: " + event.valueInt);

                            mIndicatorNetworkType = event.valueInt;

                            intent = new Intent(BluetoothHandsfreeClient.ACTION_AG_EVENT);
                            intent.putExtra(BluetoothHandsfreeClient.EXTRA_NETWORK_ROAMING,
                                    event.valueInt);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                            mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
                            break;
                        case EVENT_TYPE_NETWORK_SIGNAL:
                            Log.d(TAG, "Connected: Signal level: " + event.valueInt);

                            mIndicatorNetworkSignal = event.valueInt;

                            intent = new Intent(BluetoothHandsfreeClient.ACTION_AG_EVENT);
                            intent.putExtra(BluetoothHandsfreeClient.EXTRA_NETWORK_SIGNAL_STRENGTH,
                                    event.valueInt);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                            mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
                            break;
                        case EVENT_TYPE_BATTERY_LEVEL:
                            Log.d(TAG, "Connected: Battery level: " + event.valueInt);

                            mIndicatorBatteryLevel = event.valueInt;

                            intent = new Intent(BluetoothHandsfreeClient.ACTION_AG_EVENT);
                            intent.putExtra(BluetoothHandsfreeClient.EXTRA_BATTERY_LEVEL,
                                    event.valueInt);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                            mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
                            break;
                        case EVENT_TYPE_OPERATOR_NAME:
                            Log.d(TAG, "Connected: Operator name: " + event.valueString);

                            mOperatorName = event.valueString;

                            intent = new Intent(BluetoothHandsfreeClient.ACTION_AG_EVENT);
                            intent.putExtra(BluetoothHandsfreeClient.EXTRA_OPERATOR_NAME,
                                    event.valueString);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                            mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
                            break;
                        case EVENT_TYPE_VR_STATE_CHANGED:
                            Log.d(TAG, "Connected: Voice recognition state: " + event.valueInt);

                            if (mVoiceRecognitionActive != event.valueInt) {
                                mVoiceRecognitionActive = event.valueInt;

                                intent = new Intent(BluetoothHandsfreeClient.ACTION_AG_EVENT);
                                intent.putExtra(BluetoothHandsfreeClient.EXTRA_VOICE_RECOGNITION,
                                        mVoiceRecognitionActive);
                                intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                                mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
                            }
                            break;
                        case EVENT_TYPE_CALL:
                            updateCallIndicator(event.valueInt);
                            break;
                        case EVENT_TYPE_CALLSETUP:
                            updateCallSetupIndicator(event.valueInt);
                            break;
                        case EVENT_TYPE_CALLHELD:
                            updateCallHeldIndicator(event.valueInt);
                            break;
                        case EVENT_TYPE_RESP_AND_HOLD:
                            updateRespAndHold(event.valueInt);
                            break;
                        case EVENT_TYPE_CLIP:
                            updateClip(event.valueString);
                            break;
                        case EVENT_TYPE_CALL_WAITING:
                            addCallWaiting(event.valueString);
                            break;
                        case EVENT_TYPE_IN_BAND_RING:
                            if (mInBandRingtone != event.valueInt) {
                                mInBandRingtone = event.valueInt;
                                intent = new Intent(BluetoothHandsfreeClient.ACTION_AG_EVENT);
                                intent.putExtra(BluetoothHandsfreeClient.EXTRA_IN_BAND_RING,
                                        mInBandRingtone);
                                intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                                mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
                            }
                            break;
                        case EVENT_TYPE_CURRENT_CALLS:
                            queryCallsUpdate(
                                    event.valueInt,
                                    event.valueInt3,
                                    event.valueString,
                                    event.valueInt4 ==
                                            HandsfreeClientHalConstants.CALL_MPTY_TYPE_MULTI,
                                    event.valueInt2 ==
                                            HandsfreeClientHalConstants.CALL_DIRECTION_OUTGOING);
                            break;
                        case EVENT_TYPE_VOLUME_CHANGED:
                            if (event.valueInt == HandsfreeClientHalConstants.VOLUME_TYPE_SPK) {
                                mAudioManager.setStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO,
                                        event.valueInt2, AudioManager.FLAG_SHOW_UI);
                                mVgsFromStack = true;
                            } else if (event.valueInt == HandsfreeClientHalConstants.VOLUME_TYPE_MIC) {
                                mAudioManager.setMicrophoneMute(event.valueInt2 == 0);
                                mVgmFromStack = true;
                            }
                            break;
                        case EVENT_TYPE_CMD_RESULT:
                            Pair<Integer, Object> queuedAction = mQueuedActions.poll();

                            // should not happen but...
                            if (queuedAction == null || queuedAction.first == NO_ACTION) {
                                clearPendingAction();
                                break;
                            }

                            Log.d(TAG, "Connected: command result: " + event.valueInt
                                    + " queuedAction: " + queuedAction.first);

                            switch (queuedAction.first) {
                                case VOICE_RECOGNITION_STOP:
                                case VOICE_RECOGNITION_START:
                                    if (event.valueInt == HandsfreeClientHalConstants.CMD_COMPLETE_OK) {
                                        if (queuedAction.first == VOICE_RECOGNITION_STOP) {
                                            mVoiceRecognitionActive =
                                                    HandsfreeClientHalConstants.VR_STATE_STOPPED;
                                        } else {
                                            mVoiceRecognitionActive =
                                                    HandsfreeClientHalConstants.VR_STATE_STARTED;
                                        }
                                    }
                                    intent = new Intent(BluetoothHandsfreeClient.ACTION_AG_EVENT);
                                    intent.putExtra(
                                            BluetoothHandsfreeClient.EXTRA_VOICE_RECOGNITION,
                                            mVoiceRecognitionActive);
                                    intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                                    mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
                                    break;
                                case QUERY_CURRENT_CALLS:
                                    queryCallsDone();
                                    break;
                                case ACCEPT_CALL:
                                    if (event.valueInt == BluetoothHandsfreeClient.ACTION_RESULT_OK) {
                                        mPendingAction = queuedAction;
                                    } else {
                                        if (callsInState(BluetoothHandsfreeClientCall.CALL_STATE_ACTIVE) == 0) {
                                            if(getCall(BluetoothHandsfreeClientCall.CALL_STATE_INCOMING) != null &&
                                                (Integer) mPendingAction.second == HandsfreeClientHalConstants.CALL_ACTION_ATA) {
                                                acceptCall(BluetoothHandsfreeClient.CALL_ACCEPT_NONE, true);
                                                break;
                                            } else if(getCall(BluetoothHandsfreeClientCall.CALL_STATE_WAITING) != null &&
                                                     (Integer) mPendingAction.second == HandsfreeClientHalConstants.CALL_ACTION_CHLD_2) {
                                                acceptCall(BluetoothHandsfreeClient.CALL_ACCEPT_NONE, true);
                                                break;
                                            }
                                        }
                                        sendActionResultIntent(event);
                                    }
                                    break;
                                case REJECT_CALL:
                                case HOLD_CALL:
                                case TERMINATE_CALL:
                                case ENTER_PRIVATE_MODE:
                                case DIAL_NUMBER:
                                case DIAL_MEMORY:
                                case REDIAL:
                                    if (event.valueInt == BluetoothHandsfreeClient.ACTION_RESULT_OK) {
                                        mPendingAction = queuedAction;
                                    } else {
                                        sendActionResultIntent(event);
                                    }
                                    break;
                                case TERMINATE_SPECIFIC_CALL:
                                    // if terminating specific succeed no other
                                    // event is send
                                    if (event.valueInt == BluetoothHandsfreeClient.ACTION_RESULT_OK) {
                                        BluetoothHandsfreeClientCall c =
                                                (BluetoothHandsfreeClientCall) queuedAction.second;
                                        setCallState(c,
                                                BluetoothHandsfreeClientCall.CALL_STATE_TERMINATED);
                                        mCalls.remove(c.getId());
                                    } else {
                                        sendActionResultIntent(event);
                                    }
                                    break;
                                case LAST_VTAG_NUMBER:
                                    if (event.valueInt != BluetoothHandsfreeClient.ACTION_RESULT_OK) {
                                        sendActionResultIntent(event);
                                    }
                                    break;
                                case SET_MIC_VOLUME:
                                case SET_SPEAKER_VOLUME:
                                case SUBSCRIBER_INFO:
                                case QUERY_OPERATOR_NAME:
                                    break;
                                default:
                                    sendActionResultIntent(event);
                                    break;
                            }

                            break;
                        case EVENT_TYPE_SUBSCRIBER_INFO:
                            /* TODO should we handle type as well? */
                            mSubscriberInfo = event.valueString;
                            intent = new Intent(BluetoothHandsfreeClient.ACTION_AG_EVENT);
                            intent.putExtra(BluetoothHandsfreeClient.EXTRA_SUBSCRIBER_INFO,
                                    mSubscriberInfo);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                            mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
                            break;
                        case EVENT_TYPE_LAST_VOICE_TAG_NUMBER:
                            intent = new Intent(BluetoothHandsfreeClient.ACTION_LAST_VTAG);
                            intent.putExtra(BluetoothHandsfreeClient.EXTRA_NUMBER,
                                    event.valueString);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
                            mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
                            break;
                        case EVENT_TYPE_RING_INDICATION:
                            Log.e(TAG, "start ringing");
                            if (mRingtone != null && mRingtone.isPlaying()) {
                                Log.d(TAG,"ring already playing");
                                break;
                            }
                            int newAudioMode = AudioManager.MODE_RINGTONE;
                            int currMode = mAudioManager.getMode();
                            if (currMode != newAudioMode) {
                                 // request audio focus before setting the new mode
                                 mAudioManager.requestAudioFocusForCall(AudioManager.MODE_RINGTONE,
                                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                                 Log.d(TAG, "setAudioMode Setting audio mode from "
                                    + currMode + " to " + newAudioMode);
                                 mAudioManager.setMode(newAudioMode);
                            }
                            if (mRingtone != null) {
                                mRingtone.play();
                            }
                            break;
                        default:
                            Log.e(TAG, "Unknown stack event: " + event.type);
                            break;
                    }

                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private void sendActionResultIntent(StackEvent event) {
            Intent intent = new Intent(BluetoothHandsfreeClient.ACTION_RESULT);
            intent.putExtra(BluetoothHandsfreeClient.EXTRA_RESULT_CODE, event.valueInt);
            if (event.valueInt == BluetoothHandsfreeClient.ACTION_RESULT_ERROR_CME) {
                intent.putExtra(BluetoothHandsfreeClient.EXTRA_CME_CODE, event.valueInt2);
            }
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, event.device);
            mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
        }

        // in Connected state
        private void processConnectionEvent(int state, BluetoothDevice device) {
            switch (state) {
                case HandsfreeClientHalConstants.CONNECTION_STATE_DISCONNECTED:
                    Log.d(TAG, "Connected disconnects.");
                    // AG disconnects
                    if (mCurrentDevice.equals(device)) {
                        broadcastConnectionState(mCurrentDevice,
                                BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_CONNECTED);
                        mCurrentDevice = null;
                        transitionTo(mDisconnected);
                    } else {
                        Log.e(TAG, "Disconnected from unknown device: " + device);
                    }
                    break;
                default:
                    Log.e(TAG, "Connection State Device: " + device + " bad state: " + state);
                    break;
            }
        }

        // in Connected state
        private void processAudioEvent(int state, BluetoothDevice device) {
            // message from old device
            if (!mCurrentDevice.equals(device)) {
                Log.e(TAG, "Audio changed on disconnected device: " + device);
                return;
            }

            switch (state) {
                case HandsfreeClientHalConstants.AUDIO_STATE_CONNECTED_MSBC:
                    mAudioWbs = true;
                    // fall through
                case HandsfreeClientHalConstants.AUDIO_STATE_CONNECTED:
                    mAudioState = BluetoothHandsfreeClient.STATE_AUDIO_CONNECTED;
                    // request audio focus for call
                    if (mRingtone != null && mRingtone.isPlaying()) {
                        Log.d(TAG,"stopping ring and request focus for call");
                        mRingtone.stop();
                    }
                    int newAudioMode = AudioManager.MODE_IN_CALL;
                    int currMode = mAudioManager.getMode();
                    if (currMode != newAudioMode) {
                         // request audio focus before setting the new mode
                         mAudioManager.requestAudioFocusForCall(AudioManager.STREAM_VOICE_CALL,
                                 AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                         Log.d(TAG, "setAudioMode Setting audio mode from "
                            + currMode + " to " + newAudioMode);
                         mAudioManager.setMode(newAudioMode);
                    }
                    Log.d(TAG,"hfp_enable=true");
                    Log.d(TAG,"mAudioWbs is " + mAudioWbs);
                    if (mAudioWbs) {
                        Log.d(TAG,"Setting sampling rate as 16000");
                        mAudioManager.setParameters("hfp_set_sampling_rate=16000");
                    }
                    else {
                        Log.d(TAG,"Setting sampling rate as 8000");
                        mAudioManager.setParameters("hfp_set_sampling_rate=8000");
                    }
                    mAudioManager.setParameters("hfp_enable=true");
                    broadcastAudioState(device, BluetoothHandsfreeClient.STATE_AUDIO_CONNECTED,
                            BluetoothHandsfreeClient.STATE_AUDIO_CONNECTING);
                    transitionTo(mAudioOn);
                    break;
                case HandsfreeClientHalConstants.AUDIO_STATE_CONNECTING:
                    mAudioState = BluetoothHandsfreeClient.STATE_AUDIO_CONNECTING;
                    broadcastAudioState(device, BluetoothHandsfreeClient.STATE_AUDIO_CONNECTING,
                            BluetoothHandsfreeClient.STATE_AUDIO_DISCONNECTED);
                    break;
                case HandsfreeClientHalConstants.AUDIO_STATE_DISCONNECTED:
                    if (mAudioState == BluetoothHandsfreeClient.STATE_AUDIO_CONNECTING) {
                        mAudioState = BluetoothHandsfreeClient.STATE_AUDIO_DISCONNECTED;
                        broadcastAudioState(device,
                                BluetoothHandsfreeClient.STATE_AUDIO_DISCONNECTED,
                                BluetoothHandsfreeClient.STATE_AUDIO_CONNECTING);
                    }
                    break;
                default:
                    Log.e(TAG, "Audio State Device: " + device + " bad state: " + state);
                    break;
            }
        }

        @Override
        public void exit() {
            Log.d(TAG, "Exit Connected: " + getCurrentMessage().what);
        }
    }

    private class AudioOn extends State {
        @Override
        public void enter() {
            Log.d(TAG, "Enter AudioOn: " + getCurrentMessage().what);

            mAudioManager.setStreamSolo(AudioManager.STREAM_BLUETOOTH_SCO, true);
        }

        @Override
        public synchronized boolean processMessage(Message message) {
            Log.d(TAG, "AudioOn process message: " + message.what);
            if (DBG) {
                if (mCurrentDevice == null) {
                    Log.d(TAG, "ERROR: mCurrentDevice is null in Connected");
                    return NOT_HANDLED;
                }
            }

            switch (message.what) {
                case DISCONNECT:
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mCurrentDevice.equals(device)) {
                        break;
                    }
                    deferMessage(message);
                    /*
                     * fall through - disconnect audio first then expect
                     * deferred DISCONNECT message in Connected state
                     */
                case DISCONNECT_AUDIO:
                    /*
                     * just disconnect audio and wait for
                     * EVENT_TYPE_AUDIO_STATE_CHANGED, that triggers State
                     * Machines state changing
                     */
                    if (disconnectAudioNative(getByteAddress(mCurrentDevice))) {
                        mAudioState = BluetoothHandsfreeClient.STATE_AUDIO_DISCONNECTED;
                        //abandon audio focus
                        if (mAudioManager.getMode() != AudioManager.MODE_NORMAL) {
                                mAudioManager.setMode(AudioManager.MODE_NORMAL);
                                Log.d(TAG, "abandonAudioFocus");
                                // abandon audio focus after the mode has been set back to normal
                                mAudioManager.abandonAudioFocusForCall();
                        }
                        Log.d(TAG,"hfp_enable=false");
                        mAudioManager.setParameters("hfp_enable=false");
                        broadcastAudioState(mCurrentDevice,
                                BluetoothHandsfreeClient.STATE_AUDIO_DISCONNECTED,
                                BluetoothHandsfreeClient.STATE_AUDIO_CONNECTED);
                    }
                    break;
                case STACK_EVENT:
                    StackEvent event = (StackEvent) message.obj;
                    if (DBG) {
                        Log.d(TAG, "AudioOn: event type: " + event.type);
                    }
                    switch (event.type) {
                        case EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            Log.d(TAG, "AudioOn connection state changed" + event.device + ": "
                                    + event.valueInt);
                            processConnectionEvent(event.valueInt, event.device);
                            break;
                        case EVENT_TYPE_AUDIO_STATE_CHANGED:
                            Log.d(TAG, "AudioOn audio state changed" + event.device + ": "
                                    + event.valueInt);
                            processAudioEvent(event.valueInt, event.device);
                            break;
                        default:
                            return NOT_HANDLED;
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        // in AudioOn state. Can AG disconnect RFCOMM prior to SCO? Handle this
        private void processConnectionEvent(int state, BluetoothDevice device) {
            switch (state) {
                case HandsfreeClientHalConstants.CONNECTION_STATE_DISCONNECTED:
                    if (mCurrentDevice.equals(device)) {
                        processAudioEvent(HandsfreeClientHalConstants.AUDIO_STATE_DISCONNECTED,
                                device);
                        broadcastConnectionState(mCurrentDevice,
                                BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_CONNECTED);
                        mCurrentDevice = null;
                        transitionTo(mDisconnected);
                    } else {
                        Log.e(TAG, "Disconnected from unknown device: " + device);
                    }
                    break;
                default:
                    Log.e(TAG, "Connection State Device: " + device + " bad state: " + state);
                    break;
            }
        }

        // in AudioOn state
        private void processAudioEvent(int state, BluetoothDevice device) {
            if (!mCurrentDevice.equals(device)) {
                Log.e(TAG, "Audio changed on disconnected device: " + device);
                return;
            }

            switch (state) {
                case HandsfreeClientHalConstants.AUDIO_STATE_DISCONNECTED:
                    if (mAudioState != BluetoothHandsfreeClient.STATE_AUDIO_DISCONNECTED) {
                        mAudioState = BluetoothHandsfreeClient.STATE_AUDIO_DISCONNECTED;
                        //abandon audio focus for call
                        if (mAudioManager.getMode() != AudioManager.MODE_NORMAL) {
                              mAudioManager.setMode(AudioManager.MODE_NORMAL);
                              Log.d(TAG, "abandonAudioFocus");
                                // abandon audio focus after the mode has been set back to normal
                                mAudioManager.abandonAudioFocusForCall();
                        }
                        Log.d(TAG,"hfp_enable=false");
                        mAudioManager.setParameters("hfp_enable=false");
                        broadcastAudioState(device,
                                BluetoothHandsfreeClient.STATE_AUDIO_DISCONNECTED,
                                BluetoothHandsfreeClient.STATE_AUDIO_CONNECTED);
                    }

                    transitionTo(mConnected);
                    break;
                default:
                    Log.e(TAG, "Audio State Device: " + device + " bad state: " + state);
                    break;
            }
        }

        @Override
        public void exit() {
            Log.d(TAG, "Exit AudioOn: " + getCurrentMessage().what);

            mAudioManager.setStreamSolo(AudioManager.STREAM_BLUETOOTH_SCO, false);
        }
    }

    /**
     * @hide
     */
    public synchronized int getConnectionState(BluetoothDevice device) {
        if (mCurrentDevice == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }

        if (!mCurrentDevice.equals(device)) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }

        IState currentState = getCurrentState();
        if (currentState == mConnecting) {
            return BluetoothProfile.STATE_CONNECTING;
        }

        if (currentState == mConnected || currentState == mAudioOn) {
            return BluetoothProfile.STATE_CONNECTED;
        }

        Log.e(TAG, "Bad currentState: " + currentState);
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    private void broadcastAudioState(BluetoothDevice device, int newState, int prevState) {
        Intent intent = new Intent(BluetoothHandsfreeClient.ACTION_AUDIO_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);

        if (newState == BluetoothHandsfreeClient.STATE_AUDIO_CONNECTED) {
            intent.putExtra(BluetoothHandsfreeClient.EXTRA_AUDIO_WBS, mAudioWbs);
        }

        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
        Log.d(TAG, "Audio state " + device + ": " + prevState + "->" + newState);
    }

    // This method does not check for error condition (newState == prevState)
    private void broadcastConnectionState(BluetoothDevice device, int newState, int prevState) {
        Log.d(TAG, "Connection state " + device + ": " + prevState + "->" + newState);
        /*
         * Notifying the connection state change of the profile before sending
         * the intent for connection state change, as it was causing a race
         * condition, with the UI not being updated with the correct connection
         * state.
         */
        mService.notifyProfileConnectionStateChanged(device, BluetoothProfile.HANDSFREE_CLIENT,
                newState, prevState);
        Intent intent = new Intent(BluetoothHandsfreeClient.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);

        // add feature extras when connected
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if ((mPeerFeatures & HandsfreeClientHalConstants.PEER_FEAT_3WAY) ==
                    HandsfreeClientHalConstants.PEER_FEAT_3WAY) {
                intent.putExtra(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_3WAY_CALLING, true);
            }
            if ((mPeerFeatures & HandsfreeClientHalConstants.PEER_FEAT_VREC) ==
                    HandsfreeClientHalConstants.PEER_FEAT_VREC) {
                intent.putExtra(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_VOICE_RECOGNITION, true);
            }
            if ((mPeerFeatures & HandsfreeClientHalConstants.PEER_FEAT_VTAG) ==
                    HandsfreeClientHalConstants.PEER_FEAT_VTAG) {
                intent.putExtra(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_ATTACH_NUMBER_TO_VT, true);
            }
            if ((mPeerFeatures & HandsfreeClientHalConstants.PEER_FEAT_REJECT) ==
                    HandsfreeClientHalConstants.PEER_FEAT_REJECT) {
                intent.putExtra(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_REJECT_CALL, true);
            }
            if ((mPeerFeatures & HandsfreeClientHalConstants.PEER_FEAT_ECC) ==
                    HandsfreeClientHalConstants.PEER_FEAT_ECC) {
                intent.putExtra(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_ECC, true);
            }

            // add individual CHLD support extras
            if ((mChldFeatures & HandsfreeClientHalConstants.CHLD_FEAT_HOLD_ACC) ==
                    HandsfreeClientHalConstants.CHLD_FEAT_HOLD_ACC) {
                intent.putExtra(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_ACCEPT_HELD_OR_WAITING_CALL, true);
            }
            if ((mChldFeatures & HandsfreeClientHalConstants.CHLD_FEAT_REL) ==
                    HandsfreeClientHalConstants.CHLD_FEAT_REL) {
                intent.putExtra(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_RELEASE_HELD_OR_WAITING_CALL, true);
            }
            if ((mChldFeatures & HandsfreeClientHalConstants.CHLD_FEAT_REL_ACC) ==
                    HandsfreeClientHalConstants.CHLD_FEAT_REL_ACC) {
                intent.putExtra(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_RELEASE_AND_ACCEPT, true);
            }
            if ((mChldFeatures & HandsfreeClientHalConstants.CHLD_FEAT_MERGE) ==
                    HandsfreeClientHalConstants.CHLD_FEAT_MERGE) {
                intent.putExtra(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_MERGE, true);
            }
            if ((mChldFeatures & HandsfreeClientHalConstants.CHLD_FEAT_MERGE_DETACH) ==
                    HandsfreeClientHalConstants.CHLD_FEAT_MERGE_DETACH) {
                intent.putExtra(BluetoothHandsfreeClient.EXTRA_AG_FEATURE_MERGE_AND_DETACH, true);
            }
        }

        mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    boolean isConnected() {
        IState currentState = getCurrentState();
        return (currentState == mConnected || currentState == mAudioOn);
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>();
        Set<BluetoothDevice> bondedDevices = mAdapter.getBondedDevices();
        int connectionState;
        synchronized (this) {
            for (BluetoothDevice device : bondedDevices) {
                ParcelUuid[] featureUuids = device.getUuids();
                if (!BluetoothUuid.isUuidPresent(featureUuids, BluetoothUuid.Handsfree_AG)) {
                    continue;
                }
                connectionState = getConnectionState(device);
                for (int state : states) {
                    if (connectionState == state) {
                        deviceList.add(device);
                    }
                }
            }
        }
        return deviceList;
    }

    boolean okToConnect(BluetoothDevice device) {
        int priority = mService.getPriority(device);
        boolean ret = false;
        // check priority and accept or reject the connection. if priority is
        // undefined
        // it is likely that our SDP has not completed and peer is initiating
        // the
        // connection. Allow this connection, provided the device is bonded
        if ((BluetoothProfile.PRIORITY_OFF < priority) ||
                ((BluetoothProfile.PRIORITY_UNDEFINED == priority) &&
                (device.getBondState() != BluetoothDevice.BOND_NONE))) {
            ret = true;
        }
        return ret;
    }

    boolean isAudioOn() {
        return (getCurrentState() == mAudioOn);
    }

    synchronized int getAudioState(BluetoothDevice device) {
        if (mCurrentDevice == null || !mCurrentDevice.equals(device)) {
            return BluetoothHandsfreeClient.STATE_AUDIO_DISCONNECTED;
        }
        return mAudioState;
    }

    /**
     * @hide
     */
    List<BluetoothDevice> getConnectedDevices() {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        synchronized (this) {
            if (isConnected()) {
                devices.add(mCurrentDevice);
            }
        }
        return devices;
    }

    private BluetoothDevice getDevice(byte[] address) {
        return mAdapter.getRemoteDevice(Utils.getAddressStringFromByte(address));
    }

    private void onConnectionStateChanged(int state, int peer_feat, int chld_feat, byte[] address) {
        StackEvent event = new StackEvent(EVENT_TYPE_CONNECTION_STATE_CHANGED);
        event.valueInt = state;
        event.valueInt2 = peer_feat;
        event.valueInt3 = chld_feat;
        event.device = getDevice(address);
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onAudioStateChanged(int state, byte[] address) {
        StackEvent event = new StackEvent(EVENT_TYPE_AUDIO_STATE_CHANGED);
        event.valueInt = state;
        event.device = getDevice(address);
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onVrStateChanged(int state) {
        StackEvent event = new StackEvent(EVENT_TYPE_VR_STATE_CHANGED);
        event.valueInt = state;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onNetworkState(int state) {
        StackEvent event = new StackEvent(EVENT_TYPE_NETWORK_STATE);
        event.valueInt = state;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onNetworkRoaming(int state) {
        StackEvent event = new StackEvent(EVENT_TYPE_ROAMING_STATE);
        event.valueInt = state;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onNetworkSignal(int signal) {
        StackEvent event = new StackEvent(EVENT_TYPE_NETWORK_SIGNAL);
        event.valueInt = signal;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onBatteryLevel(int level) {
        StackEvent event = new StackEvent(EVENT_TYPE_BATTERY_LEVEL);
        event.valueInt = level;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onCurrentOperator(String name) {
        StackEvent event = new StackEvent(EVENT_TYPE_OPERATOR_NAME);
        event.valueString = name;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onCall(int call) {
        StackEvent event = new StackEvent(EVENT_TYPE_CALL);
        event.valueInt = call;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onCallSetup(int callsetup) {
        StackEvent event = new StackEvent(EVENT_TYPE_CALLSETUP);
        event.valueInt = callsetup;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onCallHeld(int callheld) {
        StackEvent event = new StackEvent(EVENT_TYPE_CALLHELD);
        event.valueInt = callheld;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onRespAndHold(int resp_and_hold) {
        StackEvent event = new StackEvent(EVENT_TYPE_RESP_AND_HOLD);
        event.valueInt = resp_and_hold;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onClip(String number) {
        StackEvent event = new StackEvent(EVENT_TYPE_CLIP);
        event.valueString = number;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onCallWaiting(String number) {
        StackEvent event = new StackEvent(EVENT_TYPE_CALL_WAITING);
        event.valueString = number;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onCurrentCalls(int index, int dir, int state, int mparty, String number) {
        StackEvent event = new StackEvent(EVENT_TYPE_CURRENT_CALLS);
        event.valueInt = index;
        event.valueInt2 = dir;
        event.valueInt3 = state;
        event.valueInt4 = mparty;
        event.valueString = number;
        Log.d(TAG, "incoming " + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onVolumeChange(int type, int volume) {
        StackEvent event = new StackEvent(EVENT_TYPE_VOLUME_CHANGED);
        event.valueInt = type;
        event.valueInt2 = volume;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onCmdResult(int type, int cme) {
        StackEvent event = new StackEvent(EVENT_TYPE_CMD_RESULT);
        event.valueInt = type;
        event.valueInt2 = cme;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onSubscriberInfo(String number, int type) {
        StackEvent event = new StackEvent(EVENT_TYPE_SUBSCRIBER_INFO);
        event.valueInt = type;
        event.valueString = number;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onInBandRing(int in_band) {
        StackEvent event = new StackEvent(EVENT_TYPE_IN_BAND_RING);
        event.valueInt = in_band;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private void onLastVoiceTagNumber(String number) {
        StackEvent event = new StackEvent(EVENT_TYPE_LAST_VOICE_TAG_NUMBER);
        event.valueString = number;
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }
    private void onRingIndication() {
        StackEvent event = new StackEvent(EVENT_TYPE_RING_INDICATION);
        Log.d(TAG, "incoming" + event);
        sendMessage(STACK_EVENT, event);
    }

    private String getCurrentDeviceName() {
        String defaultName = "<unknown>";
        if (mCurrentDevice == null) {
            return defaultName;
        }
        String deviceName = mCurrentDevice.getName();
        if (deviceName == null) {
            return defaultName;
        }
        return deviceName;
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
    }

    // Event types for STACK_EVENT message
    final private static int EVENT_TYPE_NONE = 0;
    final private static int EVENT_TYPE_CONNECTION_STATE_CHANGED = 1;
    final private static int EVENT_TYPE_AUDIO_STATE_CHANGED = 2;
    final private static int EVENT_TYPE_VR_STATE_CHANGED = 3;
    final private static int EVENT_TYPE_NETWORK_STATE = 4;
    final private static int EVENT_TYPE_ROAMING_STATE = 5;
    final private static int EVENT_TYPE_NETWORK_SIGNAL = 6;
    final private static int EVENT_TYPE_BATTERY_LEVEL = 7;
    final private static int EVENT_TYPE_OPERATOR_NAME = 8;
    final private static int EVENT_TYPE_CALL = 9;
    final private static int EVENT_TYPE_CALLSETUP = 10;
    final private static int EVENT_TYPE_CALLHELD = 11;
    final private static int EVENT_TYPE_CLIP = 12;
    final private static int EVENT_TYPE_CALL_WAITING = 13;
    final private static int EVENT_TYPE_CURRENT_CALLS = 14;
    final private static int EVENT_TYPE_VOLUME_CHANGED = 15;
    final private static int EVENT_TYPE_CMD_RESULT = 16;
    final private static int EVENT_TYPE_SUBSCRIBER_INFO = 17;
    final private static int EVENT_TYPE_RESP_AND_HOLD = 18;
    final private static int EVENT_TYPE_IN_BAND_RING = 19;
    final private static int EVENT_TYPE_LAST_VOICE_TAG_NUMBER = 20;
    final private static int EVENT_TYPE_RING_INDICATION= 21;

    // for debugging only
    private final String EVENT_TYPE_NAMES[] =
    {
            "EVENT_TYPE_NONE",
            "EVENT_TYPE_CONNECTION_STATE_CHANGED",
            "EVENT_TYPE_AUDIO_STATE_CHANGED",
            "EVENT_TYPE_VR_STATE_CHANGED",
            "EVENT_TYPE_NETWORK_STATE",
            "EVENT_TYPE_ROAMING_STATE",
            "EVENT_TYPE_NETWORK_SIGNAL",
            "EVENT_TYPE_BATTERY_LEVEL",
            "EVENT_TYPE_OPERATOR_NAME",
            "EVENT_TYPE_CALL",
            "EVENT_TYPE_CALLSETUP",
            "EVENT_TYPE_CALLHELD",
            "EVENT_TYPE_CLIP",
            "EVENT_TYPE_CALL_WAITING",
            "EVENT_TYPE_CURRENT_CALLS",
            "EVENT_TYPE_VOLUME_CHANGED",
            "EVENT_TYPE_CMD_RESULT",
            "EVENT_TYPE_SUBSCRIBER_INFO",
            "EVENT_TYPE_RESP_AND_HOLD",
            "EVENT_TYPE_IN_BAND_RING",
            "EVENT_TYPE_LAST_VOICE_TAG_NUMBER",
            "EVENT_TYPE_RING_INDICATION",
    };

    private class StackEvent {
        int type = EVENT_TYPE_NONE;
        int valueInt = 0;
        int valueInt2 = 0;
        int valueInt3 = 0;
        int valueInt4 = 0;
        String valueString = null;
        BluetoothDevice device = null;

        private StackEvent(int type) {
            this.type = type;
        }

        @Override
        public String toString() {
            // event dump
            StringBuilder result = new StringBuilder();
            result.append("StackEvent {type:" + EVENT_TYPE_NAMES[type]);
            result.append(", value1:" + valueInt);
            result.append(", value2:" + valueInt2);
            result.append(", value3:" + valueInt3);
            result.append(", value4:" + valueInt4);
            result.append(", string: \"" + valueString + "\"");
            result.append(", device:" + device + "}");
            return result.toString();
        }
    }

    private native static void classInitNative();

    private native void initializeNative();

    private native void cleanupNative();

    private native boolean connectNative(byte[] address);

    private native boolean disconnectNative(byte[] address);

    private native boolean connectAudioNative(byte[] address);

    private native boolean disconnectAudioNative(byte[] address);

    private native boolean startVoiceRecognitionNative();

    private native boolean stopVoiceRecognitionNative();

    private native boolean setVolumeNative(int volumeType, int volume);

    private native boolean dialNative(String number);

    private native boolean dialMemoryNative(int location);

    private native boolean handleCallActionNative(int action, int index);

    private native boolean queryCurrentCallsNative();

    private native boolean queryCurrentOperatorNameNative();

    private native boolean retrieveSubscriberInfoNative();

    private native boolean sendDtmfNative(byte code);

    private native boolean requestLastVoiceTagNumberNative();

    public List<BluetoothHandsfreeClientCall> getCurrentCalls() {
        return new ArrayList<BluetoothHandsfreeClientCall>(mCalls.values());
    }

    public Bundle getCurrentAgEvents() {
        Bundle b = new Bundle();
        b.putInt(BluetoothHandsfreeClient.EXTRA_NETWORK_STATUS, mIndicatorNetworkState);
        b.putInt(BluetoothHandsfreeClient.EXTRA_NETWORK_SIGNAL_STRENGTH, mIndicatorNetworkSignal);
        b.putInt(BluetoothHandsfreeClient.EXTRA_NETWORK_ROAMING, mIndicatorNetworkType);
        b.putInt(BluetoothHandsfreeClient.EXTRA_BATTERY_LEVEL, mIndicatorBatteryLevel);
        b.putString(BluetoothHandsfreeClient.EXTRA_OPERATOR_NAME, mOperatorName);
        b.putInt(BluetoothHandsfreeClient.EXTRA_VOICE_RECOGNITION, mVoiceRecognitionActive);
        b.putInt(BluetoothHandsfreeClient.EXTRA_IN_BAND_RING, mInBandRingtone);
        b.putString(BluetoothHandsfreeClient.EXTRA_SUBSCRIBER_INFO, mSubscriberInfo);
        return b;
    }
}
