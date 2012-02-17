/*
 * Copyright (C) 2012 Google Inc.
 */

package com.android.bluetooth.hfp;

/*
 * @hide
 */

final public class HeadsetHalConstants {
    // Do not modify without upating the HAL bt_hf.h files.

    // match up with bthf_connection_state_t enum of bt_hf.h
    final static int CONNECTION_STATE_DISCONNECTED = 0;
    final static int CONNECTION_STATE_CONNECTING = 1;
    final static int CONNECTION_STATE_CONNECTED = 2;
    final static int CONNECTION_STATE_DISCONNECTING = 3;

    // match up with bthf_audio_state_t enum of bt_hf.h
    final static int AUDIO_STATE_DISCONNECTED = 0;
    final static int AUDIO_STATE_CONNECTING = 1;
    final static int AUDIO_STATE_CONNECTED = 2;
    final static int AUDIO_STATE_DISCONNECTING = 3;

    // match up with bthf_vr_state_t enum of bt_hf.h
    final static int VR_STATE_STOPPED = 0;
    final static int VR_STATE_STARTED = 1;

    // match up with bthf_volume_type_t enum of bt_hf.h
    final static int VOLUME_TYPE_SPK = 0;
    final static int VOLUME_TYPE_MIC = 1;

    // match up with bthf_network_state_t enum of bt_hf.h
    final static int NETWORK_STATE_AVAILABLE = 0;
    final static int NETWORK_STATE_NOT_AVAILABLE = 1;

    // match up with bthf_service_type_t enum of bt_hf.h
    final static int SERVICE_TYPE_HOME = 0;
    final static int SERVICE_TYPE_ROAMING = 1;

    // match up with bthf_at_response_t of bt_hf.h
    final static int AT_RESPONSE_ERROR = 0;
    final static int AT_RESPONSE_OK = 1;

    // match up with bthf_call_state_t of bt_hf.h
    final static int CALL_STATE_IDLE = 0;
    final static int CALL_STATE_INCOMING = 1;
    final static int CALL_STATE_DIALING = 2;
    final static int CALL_STATE_ALERTING = 3;
    final static int CALL_STATE_WAITING = 4;
    final static int CALL_STATE_ACTIVE = 5;
    final static int CALL_STATE_ONHOLD = 6;
}