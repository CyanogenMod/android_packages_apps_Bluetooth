/*
 * Copyright (c) 2015, The Linux Foundation. All rights reserved.
 * Not a Contribution
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

#define LOG_TAG "BluetoothAvrcpControllerJni"

#define LOG_NDEBUG 0

#include "com_android_bluetooth.h"
#include "hardware/bt_rc.h"
#include "utils/Log.h"
#include "android_runtime/AndroidRuntime.h"

#include <string.h>

namespace android {
static jmethodID method_handlePassthroughRsp;
static jmethodID method_onConnectionStateChanged;
static jmethodID method_getRcFeatures;
static jmethodID method_handleGetCapabilitiesResponse;
static jmethodID method_handleListPlayerApplicationSettingsAttrib;
static jmethodID method_handleListPlayerApplicationSettingValue;
static jmethodID method_handleCurrentPlayerApplicationSettingsResponse;
static jmethodID method_handleNotificationRsp;
static jmethodID method_handleGetElementAttributes;
static jmethodID method_handleGetPlayStatus;
static jmethodID method_handleSetAbsVolume;
static jmethodID method_handleRegisterNotificationAbsVol;
static jmethodID method_handleSetPlayerApplicationResponse;


static const btrc_ctrl_interface_t *sBluetoothAvrcpInterface = NULL;
static jobject mCallbacksObj = NULL;
static JNIEnv *sCallbackEnv = NULL;

static bool checkCallbackThread() {
    // Always fetch the latest callbackEnv from AdapterService.
    // Caching this could cause this sCallbackEnv to go out-of-sync
    // with the AdapterService's ENV if an ASSOCIATE/DISASSOCIATE event
    // is received
    sCallbackEnv = getCallbackEnv();

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (sCallbackEnv != env || sCallbackEnv == NULL) return false;
    return true;
}

static void btavrcp_passthrough_response_callback(int id, int pressed) {
    ALOGI("%s", __FUNCTION__);
    ALOGI("id: %d, pressed: %d", id, pressed);

    if (!checkCallbackThread()) {
        ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
        return;
    }

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_handlePassthroughRsp, (jint)id,
                                                                             (jint)pressed);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

static void btavrcp_connection_state_callback(bool state, bt_bdaddr_t* bd_addr) {
    jbyteArray addr;

    ALOGI("%s", __FUNCTION__);
    ALOGI("conn state: %d", state);

    if (!checkCallbackThread()) {                                       \
        ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__); \
        return;                                                         \
    }

    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if (!addr) {
        ALOGE("Fail to new jbyteArray bd addr for connection state");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*) bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConnectionStateChanged, (jboolean) state,
                                 addr);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
}

static void btavrcp_get_rcfeatures_callback(bt_bdaddr_t *bd_addr, int features) {
    jbyteArray addr;

    ALOGI("%s", __FUNCTION__);

    if (!checkCallbackThread()) {                                       \
        ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__); \
        return;                                                         \
    }

    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if (!addr) {
        ALOGE("Fail to new jbyteArray bd addr ");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*) bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_getRcFeatures, addr, (jint)features);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
}

static void btavrcp_getcap_rsp_callback(bt_bdaddr_t *bd_addr, int cap_id,
        uint32_t* supported_values, int num_supported, uint8_t rsp_type) {
    jbyteArray addr;
    jintArray supported_val = NULL;

    ALOGI("%s", __FUNCTION__);

    if (!checkCallbackThread()) {                                       \
        ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__); \
        return;                                                         \
    }

    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    supported_val = sCallbackEnv->NewIntArray(num_supported);
    if ((!addr)||(!supported_val)) {
        ALOGE("Fail to get new array ");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*) bd_addr);
    sCallbackEnv->SetIntArrayRegion(supported_val, 0, (num_supported), (jint*)(supported_values));
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_handleGetCapabilitiesResponse, addr,
           (jint)cap_id,supported_val, (jint)num_supported, (jbyte)rsp_type);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
    sCallbackEnv->DeleteLocalRef(supported_val);
}

static void btavrcp_list_player_app_setting_attrib_rsp_callback(bt_bdaddr_t *bd_addr,
             uint8_t* supported_attribs, int num_attrib, uint8_t rsp_type) {
    jbyteArray addr;
    jbyteArray supported_val;

    ALOGI("%s", __FUNCTION__);

    if (!checkCallbackThread()) {                                       \
        ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__); \
        return;                                                         \
    }

    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    supported_val = sCallbackEnv->NewByteArray(num_attrib);
    if ((!addr)||(!supported_val)) {
        ALOGE("Fail to get new array ");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*) bd_addr);
    sCallbackEnv->SetByteArrayRegion(supported_val, 0, (num_attrib), (jbyte*)(supported_attribs));
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_handleListPlayerApplicationSettingsAttrib,
               addr, supported_val,(jint)num_attrib, (jbyte)rsp_type);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
    sCallbackEnv->DeleteLocalRef(supported_val);
}

static void btavrcp_list_player_app_setting_value_rsp_callback(bt_bdaddr_t *bd_addr,
                        uint8_t* supported_values, uint8_t num_supported, uint8_t rsp_type) {
    jbyteArray addr;
    jbyteArray supported_val;

    ALOGI("%s", __FUNCTION__);

    if (!checkCallbackThread()) {                                       \
        ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__); \
        return;                                                         \
    }

    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    supported_val = sCallbackEnv->NewByteArray(num_supported);
    if ((!addr)||(!supported_val)) {
        ALOGE("Fail to get new array ");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*) bd_addr);
    sCallbackEnv->SetByteArrayRegion(supported_val, 0,(num_supported), (jbyte*)(supported_values));
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_handleListPlayerApplicationSettingValue,
                                        addr, supported_val,(jbyte)num_supported, (jbyte)rsp_type);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
    sCallbackEnv->DeleteLocalRef(supported_val);
}

static void btavrcp_current_player_app_setting_rsp_callback(bt_bdaddr_t *bd_addr,
      uint8_t* supported_ids,uint8_t* supported_values, uint8_t num_attrib, uint8_t rsp_type) {
    jbyteArray addr;
    jbyteArray supported_attrib_ids;
    jbyteArray supported_val;

    ALOGI("%s", __FUNCTION__);

    if (!checkCallbackThread()) {                                       \
        ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__); \
        return;                                                         \
    }

    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    supported_val = sCallbackEnv->NewByteArray(num_attrib);
    supported_attrib_ids = sCallbackEnv->NewByteArray(num_attrib);
    if ((!addr)||(!supported_val)) {
        ALOGE("Fail to get new array ");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*) bd_addr);
    sCallbackEnv->SetByteArrayRegion(supported_val, 0, (num_attrib), (jbyte*)(supported_values));
    sCallbackEnv->SetByteArrayRegion(supported_attrib_ids, 0, (num_attrib),
                                                       (jbyte*)(supported_ids));
    sCallbackEnv->CallVoidMethod(mCallbacksObj,
           method_handleCurrentPlayerApplicationSettingsResponse,addr,supported_attrib_ids,
                                               supported_val, (jbyte)num_attrib, (jbyte)rsp_type);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
    sCallbackEnv->DeleteLocalRef(supported_val);
    sCallbackEnv->DeleteLocalRef(supported_attrib_ids);
}

static void btavrcp_set_player_app_setting_rsp_callback(bt_bdaddr_t *bd_addr,uint8_t rsp_type) {
    jbyteArray addr;

    ALOGI("%s", __FUNCTION__);

    if (!checkCallbackThread()) {                                       \
        ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__); \
        return;                                                         \
    }

    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if ((!addr)) {
        ALOGE("Fail to get new array ");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*) bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_handleSetPlayerApplicationResponse,
                          addr,(jbyte)rsp_type);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
}

static void btavrcp_notification_rsp_callback(bt_bdaddr_t *bd_addr, uint8_t rsp_type,
        int rsp_len, uint8_t* notification_rsp) {
    jbyteArray addr;
    jbyteArray supported_val;

    ALOGI("%s", __FUNCTION__);

    if (!checkCallbackThread()) {                                       \
        ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__); \
        return;                                                         \
    }

    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    supported_val = sCallbackEnv->NewByteArray(rsp_len);
    if ((!addr)||(!supported_val)) {
        ALOGE("Fail to get new array ");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*) bd_addr);
    sCallbackEnv->SetByteArrayRegion(supported_val, 0, (rsp_len), (jbyte*)(notification_rsp));
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_handleNotificationRsp,
                                        addr, (jbyte)rsp_type, (jint)rsp_len, supported_val);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
    sCallbackEnv->DeleteLocalRef(supported_val);
}

static void btavrcp_get_element_attrib_rsp_callback(bt_bdaddr_t *bd_addr, uint8_t num_attributes,
                                           int rsp_len, uint8_t* attrib_rsp,uint8_t rsp_type) {
    jbyteArray addr;
    jbyteArray supported_val;

    ALOGI("%s", __FUNCTION__);

    if (!checkCallbackThread()) {                                       \
        ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__); \
        return;                                                         \
    }

    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    supported_val = sCallbackEnv->NewByteArray(rsp_len);
    if ((!addr)||(!supported_val)) {
        ALOGE("Fail to get new array ");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*) bd_addr);
    sCallbackEnv->SetByteArrayRegion(supported_val, 0, (rsp_len), (jbyte*)(attrib_rsp));
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_handleGetElementAttributes,
                  addr, (jbyte)num_attributes, (jint)rsp_len,supported_val, (jbyte)rsp_type);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
    sCallbackEnv->DeleteLocalRef(supported_val);
}

static void btavrcp_get_playstatus_rsp_callback(bt_bdaddr_t *bd_addr, int param_len,
                                                 uint8_t* play_status_rsp,uint8_t rsp_type) {
    jbyteArray addr;
    jbyteArray supported_val;

    ALOGI("%s", __FUNCTION__);

    if (!checkCallbackThread()) {                                       \
        ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__); \
        return;                                                         \
    }

    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    supported_val = sCallbackEnv->NewByteArray(param_len);
    if ((!addr)||(!supported_val)) {
        ALOGE("Fail to get new array ");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*) bd_addr);
    sCallbackEnv->SetByteArrayRegion(supported_val, 0, (param_len), (jbyte*)(play_status_rsp));
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_handleGetPlayStatus,
                                        addr, (jint)param_len, supported_val, (jbyte)rsp_type);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
    sCallbackEnv->DeleteLocalRef(supported_val);
}

static void btavrcp_set_abs_vol_cmd_callback(bt_bdaddr_t *bd_addr, uint8_t abs_vol) {
    jbyteArray addr;

    ALOGI("%s", __FUNCTION__);

    if (!checkCallbackThread()) {                                       \
        ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__); \
        return;                                                         \
    }

    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if ((!addr)) {
        ALOGE("Fail to get new array ");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*)bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_handleSetAbsVolume, addr, (jbyte)abs_vol);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
}

static void btavrcp_register_notification_absvol_callback(bt_bdaddr_t *bd_addr) {
    jbyteArray addr;

    ALOGI("%s", __FUNCTION__);

    if (!checkCallbackThread()) {                                       \
        ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__); \
        return;                                                         \
    }

    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if ((!addr)) {
        ALOGE("Fail to get new array ");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*)bd_addr);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_handleRegisterNotificationAbsVol, addr);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
}

static btrc_ctrl_callbacks_t sBluetoothAvrcpCallbacks = {
    sizeof(sBluetoothAvrcpCallbacks),
    btavrcp_passthrough_response_callback,
    btavrcp_connection_state_callback,
    btavrcp_get_rcfeatures_callback,
    btavrcp_getcap_rsp_callback,
    btavrcp_list_player_app_setting_attrib_rsp_callback,
    btavrcp_list_player_app_setting_value_rsp_callback,
    btavrcp_current_player_app_setting_rsp_callback,
    btavrcp_set_player_app_setting_rsp_callback,
    btavrcp_notification_rsp_callback,
    btavrcp_get_element_attrib_rsp_callback,
    btavrcp_get_playstatus_rsp_callback,
    btavrcp_set_abs_vol_cmd_callback,
    btavrcp_register_notification_absvol_callback
};

static void classInitNative(JNIEnv* env, jclass clazz) {
    method_handlePassthroughRsp =
        env->GetMethodID(clazz, "handlePassthroughRsp", "(II)V");

    method_onConnectionStateChanged =
        env->GetMethodID(clazz, "onConnectionStateChanged", "(Z[B)V");

    method_getRcFeatures =
        env->GetMethodID(clazz, "getRcFeatures", "([BI)V");

    method_handleGetCapabilitiesResponse =
        env->GetMethodID(clazz, "handleGetCapabilitiesResponse", "([BI[IIB)V");

    method_handleListPlayerApplicationSettingsAttrib =
        env->GetMethodID(clazz, "handleListPlayerApplicationSettingsAttrib", "([B[BIB)V");

    method_handleListPlayerApplicationSettingValue =
        env->GetMethodID(clazz, "handleListPlayerApplicationSettingValue", "([B[BBB)V");

    method_handleCurrentPlayerApplicationSettingsResponse =
        env->GetMethodID(clazz, "handleCurrentPlayerApplicationSettingsResponse", "([B[B[BBB)V");

    method_handleNotificationRsp =
        env->GetMethodID(clazz, "handleNotificationRsp", "([BBI[B)V");

    method_handleGetElementAttributes =
        env->GetMethodID(clazz, "handleGetElementAttributes", "([BBI[BB)V");

    method_handleGetPlayStatus =
        env->GetMethodID(clazz, "handleGetPlayStatus", "([BI[BB)V");

    method_handleSetAbsVolume =
        env->GetMethodID(clazz, "handleSetAbsVolume", "([BB)V");

    method_handleRegisterNotificationAbsVol =
        env->GetMethodID(clazz, "handleRegisterNotificationAbsVol", "([B)V");

    method_handleSetPlayerApplicationResponse =
        env->GetMethodID(clazz, "handleSetPlayerApplicationResponse", "([BB)V");

    ALOGI("%s: succeeds", __FUNCTION__);
}

static void initNative(JNIEnv *env, jobject object) {
    const bt_interface_t* btInf;
    bt_status_t status;

    if ( (btInf = getBluetoothInterface()) == NULL) {
        ALOGE("Bluetooth module is not loaded");
        return;
    }

    if (sBluetoothAvrcpInterface !=NULL) {
         ALOGW("Cleaning up Avrcp Interface before initializing...");
         sBluetoothAvrcpInterface->cleanup();
         sBluetoothAvrcpInterface = NULL;
    }

    if (mCallbacksObj != NULL) {
         ALOGW("Cleaning up Avrcp callback object");
         env->DeleteGlobalRef(mCallbacksObj);
         mCallbacksObj = NULL;
    }

    if ( (sBluetoothAvrcpInterface = (btrc_ctrl_interface_t *)
          btInf->get_profile_interface(BT_PROFILE_AV_RC_CTRL_ID)) == NULL) {
        ALOGE("Failed to get Bluetooth Avrcp Controller Interface");
        return;
    }

    if ( (status = sBluetoothAvrcpInterface->init(&sBluetoothAvrcpCallbacks)) !=
         BT_STATUS_SUCCESS) {
        ALOGE("Failed to initialize Bluetooth Avrcp Controller, status: %d", status);
        sBluetoothAvrcpInterface = NULL;
        return;
    }

    mCallbacksObj = env->NewGlobalRef(object);
}

static void cleanupNative(JNIEnv *env, jobject object) {
    const bt_interface_t* btInf;

    if ( (btInf = getBluetoothInterface()) == NULL) {
        ALOGE("Bluetooth module is not loaded");
        return;
    }

    if (sBluetoothAvrcpInterface !=NULL) {
        sBluetoothAvrcpInterface->cleanup();
        sBluetoothAvrcpInterface = NULL;
    }

    if (mCallbacksObj != NULL) {
        env->DeleteGlobalRef(mCallbacksObj);
        mCallbacksObj = NULL;
    }
}

static jboolean sendPassThroughCommandNative(JNIEnv *env, jobject object, jbyteArray address,
                                                    jint key_code, jint key_state) {
    jbyte *addr;
    bt_status_t status;

    if (!sBluetoothAvrcpInterface) return JNI_FALSE;

    ALOGI("%s: sBluetoothAvrcpInterface: %p", __FUNCTION__, sBluetoothAvrcpInterface);

    ALOGI("key_code: %d, key_state: %d", key_code, key_state);

    addr = env->GetByteArrayElements(address, NULL);
    if (!addr) {
        jniThrowIOException(env, EINVAL);
        return JNI_FALSE;
    }

    if ((status = sBluetoothAvrcpInterface->send_pass_through_cmd((bt_bdaddr_t *)addr,
            (uint8_t)key_code, (uint8_t)key_state))!= BT_STATUS_SUCCESS) {
        ALOGE("Failed sending passthru command, status: %d", status);
    }
    env->ReleaseByteArrayElements(address, addr, 0);

    return (status == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static void getCapabilitiesNative(JNIEnv *env, jobject object,jint capability_id) {
    bt_status_t status;

    if (!sBluetoothAvrcpInterface) return;

    ALOGI("%s: sBluetoothAvrcpInterface: %p", __FUNCTION__, sBluetoothAvrcpInterface);
    if ((status = sBluetoothAvrcpInterface->getcapabilities_cmd((uint8_t)capability_id))
                                                                 != BT_STATUS_SUCCESS) {
        ALOGE("Failed sending getCapabilitiesNative command, status: %d", status);
    }
}

static void listPlayerApplicationSettingAttributeNative(JNIEnv *env, jobject object) {
    bt_status_t status;

    if (!sBluetoothAvrcpInterface) return;

    ALOGI("%s: sBluetoothAvrcpInterface: %p", __FUNCTION__, sBluetoothAvrcpInterface);
    if ((status = sBluetoothAvrcpInterface->list_player_app_setting_attrib_cmd())
                                                           != BT_STATUS_SUCCESS) {
        ALOGE("Failed sending listPlAppSettAttribNative command,status: %d", status);
    }
}

static void listPlayerApplicationSettingValueNative(JNIEnv *env, jobject object, jbyte attrib_id) {
    bt_status_t status;

    if (!sBluetoothAvrcpInterface) return;

    ALOGI("%s: sBluetoothAvrcpInterface: %p", __FUNCTION__, sBluetoothAvrcpInterface);
    if ((status = sBluetoothAvrcpInterface->list_player_app_setting_value_cmd((uint8_t)attrib_id))
                                                                             != BT_STATUS_SUCCESS) {
        ALOGE("Failed sending listPlAppSettValNative command, status: %d", status);
    }
}

static void getPlayerApplicationSettingValuesNative(JNIEnv *env, jobject object, jbyte num_attrib, jbyteArray attrib_ids) {
    bt_status_t status;
    uint8_t *pAttrs = NULL;
    int i;
    jbyte *attr;

    if (!sBluetoothAvrcpInterface) return;

    pAttrs = new uint8_t[num_attrib];
    if (!pAttrs) {
        ALOGE("getPlayerApplicationSettingValuesNative: not have enough memeory");
        return;
    }
    attr = env->GetByteArrayElements(attrib_ids, NULL);
    if (!attr) {
        delete[] pAttrs;
        jniThrowIOException(env, EINVAL);
        return;
    }
    for (i = 0; i < num_attrib; ++i) {
        pAttrs[i] = (uint8_t)attr[i];
    }
    if (i < num_attrib) {
        delete[] pAttrs;
        env->ReleaseByteArrayElements(attrib_ids, attr, 0);
        return;
    }

    ALOGI("%s: sBluetoothAvrcpInterface: %p", __FUNCTION__, sBluetoothAvrcpInterface);
    if ((status = sBluetoothAvrcpInterface->get_player_app_setting_cmd((uint8_t)num_attrib, pAttrs))
                                                                               != BT_STATUS_SUCCESS) {
        ALOGE("Failed sending getPlAppSettValNative command, status: %d", status);
    }
    delete[] pAttrs;
    env->ReleaseByteArrayElements(attrib_ids, attr, 0);
}

static void setPlayerApplicationSettingValuesNative(JNIEnv *env, jobject object, jbyte num_attrib, jbyteArray attrib_ids,
                                                                                                  jbyteArray attrib_val) {
    bt_status_t status;
    uint8_t *pAttrs = NULL;
    uint8_t *pAttrsVal = NULL;
    int i;
    jbyte *attr;
    jbyte *attr_val;

    if (!sBluetoothAvrcpInterface) return;

    pAttrs = new uint8_t[num_attrib];
    pAttrsVal = new uint8_t[num_attrib];
    if ((!pAttrs) ||(!pAttrsVal)) {
        ALOGE("setPlayerApplicationSettingValuesNative: not have enough memeory");
        return;
    }
    attr = env->GetByteArrayElements(attrib_ids, NULL);
    attr_val = env->GetByteArrayElements(attrib_val, NULL);
    if ((!attr)||(!attr_val)) {
        delete[] pAttrs;
        delete[] pAttrsVal;
        jniThrowIOException(env, EINVAL);
        return;
    }
    for (i = 0; i < num_attrib; ++i) {
        pAttrs[i] = (uint8_t)attr[i];
        pAttrsVal[i] = (uint8_t)attr_val[i];
    }
    if (i < num_attrib) {
        delete[] pAttrs;
        delete[] pAttrsVal;
        env->ReleaseByteArrayElements(attrib_ids, attr, 0);
        env->ReleaseByteArrayElements(attrib_val, attr_val, 0);
        return;
    }

    ALOGI("%s: sBluetoothAvrcpInterface: %p", __FUNCTION__, sBluetoothAvrcpInterface);
    if ((status = sBluetoothAvrcpInterface->set_player_app_setting_cmd((uint8_t)num_attrib, pAttrs, pAttrsVal))
                                                                                        != BT_STATUS_SUCCESS) {
        ALOGE("Failed sending setPlAppSettValNative command, status: %d", status);
    }
    delete[] pAttrs;
    delete[] pAttrsVal;
    env->ReleaseByteArrayElements(attrib_ids, attr, 0);
    env->ReleaseByteArrayElements(attrib_val, attr_val, 0);
}

static void registerNotificationNative(JNIEnv *env, jobject object, jbyte event_id, jint value ) {
    bt_status_t status;

    if (!sBluetoothAvrcpInterface) return;

    ALOGI("%s: sBluetoothAvrcpInterface: %p", __FUNCTION__, sBluetoothAvrcpInterface);
    if ((status = sBluetoothAvrcpInterface->register_notification_cmd((uint8_t)event_id,(uint32_t)value))
                                                                                    != BT_STATUS_SUCCESS) {
        ALOGE("Failed sending registerNotificationNative command, status: %d", status);
    }
}

static void getElementAttributeNative(JNIEnv *env, jobject object, jbyte num_attrib, jint attrib_id) {
    bt_status_t status;

    if (!sBluetoothAvrcpInterface) return;

    ALOGI("%s: sBluetoothAvrcpInterface: %p", __FUNCTION__, sBluetoothAvrcpInterface);
    if ((status = sBluetoothAvrcpInterface->get_element_attribute_cmd((uint8_t)num_attrib,
                                                    (uint32_t)attrib_id))!= BT_STATUS_SUCCESS) {
        ALOGE("Failed sending getElementAttributeNative command, status: %d", status);
    }
}

static void getPlayStatusNative(JNIEnv *env, jobject object) {
    bt_status_t status;

    if (!sBluetoothAvrcpInterface) return;

    ALOGI("%s: sBluetoothAvrcpInterface: %p", __FUNCTION__, sBluetoothAvrcpInterface);
    if ((status = sBluetoothAvrcpInterface->get_play_status_cmd())!= BT_STATUS_SUCCESS) {
        ALOGE("Failed sending getPlayStatusNative command, status: %d", status);
    }
}

static void sendAbsVolRspNative(JNIEnv *env, jobject object, jint abs_vol) {
    bt_status_t status;

    if (!sBluetoothAvrcpInterface) return;

    ALOGI("%s: sBluetoothAvrcpInterface: %p", __FUNCTION__, sBluetoothAvrcpInterface);
    if ((status = sBluetoothAvrcpInterface->send_abs_vol_rsp((uint8_t)abs_vol))!= BT_STATUS_SUCCESS) {
        ALOGE("Failed sending sendAbsVolRspNative command, status: %d", status);
    }
}

static void sendRegisterAbsVolRspNative(JNIEnv *env, jobject object, jbyte rsp_type, jint abs_vol) {
    bt_status_t status;

    if (!sBluetoothAvrcpInterface) return;

    ALOGI("%s: sBluetoothAvrcpInterface: %p", __FUNCTION__, sBluetoothAvrcpInterface);
    if ((status = sBluetoothAvrcpInterface->send_register_abs_vol_rsp((uint8_t)rsp_type,
                                                     (uint8_t)abs_vol))!= BT_STATUS_SUCCESS) {
        ALOGE("Failed sending sendRegisterAbsVolRspNative command, status: %d", status);
    }
}

static JNINativeMethod sMethods[] = {
    {"classInitNative", "()V", (void *) classInitNative},
    {"initNative", "()V", (void *) initNative},
    {"cleanupNative", "()V", (void *) cleanupNative},
    {"sendPassThroughCommandNative", "([BII)Z",(void *) sendPassThroughCommandNative},
    {"getCapabilitiesNative", "(I)V",(void *) getCapabilitiesNative},
    {"listPlayerApplicationSettingAttributeNative", "()V",
                               (void *) listPlayerApplicationSettingAttributeNative},
    {"listPlayerApplicationSettingValueNative", "(B)V",
                               (void *) listPlayerApplicationSettingValueNative},
    {"getPlayerApplicationSettingValuesNative", "(B[B)V",
                               (void *) getPlayerApplicationSettingValuesNative},
    {"setPlayerApplicationSettingValuesNative", "(B[B[B)V",
                               (void *) setPlayerApplicationSettingValuesNative},
    {"registerNotificationNative", "(BI)V",
                               (void *) registerNotificationNative},
    {"getElementAttributeNative", "(BI)V",(void *) getElementAttributeNative},
    {"getPlayStatusNative", "()V",(void *) getPlayStatusNative},
    {"sendAbsVolRspNative", "(I)V",(void *) sendAbsVolRspNative},
    {"sendRegisterAbsVolRspNative", "(BI)V",(void *) sendRegisterAbsVolRspNative},
};

int register_com_android_bluetooth_avrcp_controller(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/bluetooth/avrcp/AvrcpControllerService",
                                    sMethods, NELEM(sMethods));
}

}
