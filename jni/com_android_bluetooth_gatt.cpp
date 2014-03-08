/*
 * Copyright (C) 2013 The Android Open Source Project
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


#define LOG_TAG "BtGatt.JNI"

#define LOG_NDEBUG 0

#define CHECK_CALLBACK_ENV                                                      \
   if (!checkCallbackThread()) {                                                \
       error("Callback: '%s' is not called on the correct thread", __FUNCTION__);\
       return;                                                                  \
   }

#include "com_android_bluetooth.h"
#include "hardware/bt_gatt.h"
#include "utils/Log.h"
#include "android_runtime/AndroidRuntime.h"

#include <string.h>

#include <cutils/log.h>
#define info(fmt, ...)  ALOGI ("%s(L%d): " fmt,__FUNCTION__, __LINE__,  ## __VA_ARGS__)
#define debug(fmt, ...) ALOGD ("%s(L%d): " fmt,__FUNCTION__, __LINE__,  ## __VA_ARGS__)
#define warn(fmt, ...) ALOGW ("WARNING: %s(L%d): " fmt "##",__FUNCTION__, __LINE__, ## __VA_ARGS__)
#define error(fmt, ...) ALOGE ("ERROR: %s(L%d): " fmt "##",__FUNCTION__, __LINE__, ## __VA_ARGS__)
#define asrt(s) if(!(s)) ALOGE ("%s(L%d): ASSERT %s failed! ##",__FUNCTION__, __LINE__, #s)

#define BD_ADDR_LEN 6

#define UUID_PARAMS(uuid_ptr) \
    uuid_lsb(uuid_ptr),  uuid_msb(uuid_ptr)

#define GATT_ID_PARAMS(attr_ptr) \
    attr_ptr->inst_id, \
    UUID_PARAMS((&attr_ptr->uuid))

#define SRVC_ID_PARAMS(srvc_ptr) \
    (srvc_ptr->is_primary ? \
    BTGATT_SERVICE_TYPE_PRIMARY : BTGATT_SERVICE_TYPE_SECONDARY), \
    GATT_ID_PARAMS((&srvc_ptr->id))


static void set_uuid(uint8_t* uuid, jlong uuid_msb, jlong uuid_lsb)
{
    for (int i = 0; i != 8; ++i)
    {
        uuid[i]     = (uuid_lsb >> (8 * i)) & 0xFF;
        uuid[i + 8] = (uuid_msb >> (8 * i)) & 0xFF;
    }
}

static uint64_t uuid_lsb(bt_uuid_t* uuid)
{
    uint64_t  lsb = 0;
    int i;

    for (i = 7; i >= 0; i--)
    {
        lsb <<= 8;
        lsb |= uuid->uu[i];
    }

    return lsb;
}

static uint64_t uuid_msb(bt_uuid_t* uuid)
{
    uint64_t msb = 0;
    int i;

    for (i = 15; i >= 8; i--)
    {
        msb <<= 8;
        msb |= uuid->uu[i];
    }

    return msb;
}

static void bd_addr_str_to_addr(const char* str, uint8_t *bd_addr)
{
    int    i;
    char   c;

    c = *str++;
    for (i = 0; i < BD_ADDR_LEN; i++)
    {
        if (c >= '0' && c <= '9')
            bd_addr[i] = c - '0';
        else if (c >= 'a' && c <= 'z')
            bd_addr[i] = c - 'a' + 10;
        else   // (c >= 'A' && c <= 'Z')
            bd_addr[i] = c - 'A' + 10;

        c = *str++;
        if (c != ':')
        {
            bd_addr[i] <<= 4;
            if (c >= '0' && c <= '9')
                bd_addr[i] |= c - '0';
            else if (c >= 'a' && c <= 'z')
                bd_addr[i] |= c - 'a' + 10;
            else   // (c >= 'A' && c <= 'Z')
                bd_addr[i] |= c - 'A' + 10;

            c = *str++;
        }

        c = *str++;
    }
}

static void jstr2bdaddr(JNIEnv* env, bt_bdaddr_t *bda, jstring address)
{
    const char* c_bda = env->GetStringUTFChars(address, NULL);
    if (c_bda != NULL && bda != NULL && strlen(c_bda) == 17)
    {
        bd_addr_str_to_addr(c_bda, bda->address);
        env->ReleaseStringUTFChars(address, c_bda);
    }
}

namespace android {

/**
 * Client callback methods
 */

static jmethodID method_onClientRegistered;
static jmethodID method_onScanResult;
static jmethodID method_onConnected;
static jmethodID method_onDisconnected;
static jmethodID method_onReadCharacteristic;
static jmethodID method_onWriteCharacteristic;
static jmethodID method_onExecuteCompleted;
static jmethodID method_onSearchCompleted;
static jmethodID method_onSearchResult;
static jmethodID method_onReadDescrExtProp;
static jmethodID method_onReadDescriptor;
static jmethodID method_onWriteDescriptor;
static jmethodID method_onNotify;
static jmethodID method_onGetCharacteristic;
static jmethodID method_onGetDescriptor;
static jmethodID method_onGetIncludedService;
static jmethodID method_onRegisterForNotifications;
static jmethodID method_onReadRemoteRssi;

/**
 * Server callback methods
 */
static jmethodID method_onServerRegistered;
static jmethodID method_onClientConnected;
static jmethodID method_onServiceAdded;
static jmethodID method_onIncludedServiceAdded;
static jmethodID method_onCharacteristicAdded;
static jmethodID method_onDescriptorAdded;
static jmethodID method_onServiceStarted;
static jmethodID method_onServiceStopped;
static jmethodID method_onServiceDeleted;
static jmethodID method_onResponseSendCompleted;
static jmethodID method_onAttributeRead;
static jmethodID method_onAttributeWrite;
static jmethodID method_onExecuteWrite;

/**
 * Static variables
 */

static const btgatt_interface_t *sGattIf = NULL;
static jobject mCallbacksObj = NULL;
static JNIEnv *sCallbackEnv = NULL;

static bool checkCallbackThread() {
    sCallbackEnv = getCallbackEnv();

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (sCallbackEnv != env || sCallbackEnv == NULL) return false;
    return true;
}

/**
 * BTA client callbacks
 */

void btgattc_register_app_cb(int status, int clientIf, bt_uuid_t *app_uuid)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onClientRegistered, status,
        clientIf, UUID_PARAMS(app_uuid));
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_scan_result_cb(bt_bdaddr_t* bda, int rssi, uint8_t* adv_data)
{
    CHECK_CALLBACK_ENV

    char c_address[32];
    snprintf(c_address, sizeof(c_address),"%02X:%02X:%02X:%02X:%02X:%02X",
        bda->address[0], bda->address[1], bda->address[2],
        bda->address[3], bda->address[4], bda->address[5]);

    jstring address = sCallbackEnv->NewStringUTF(c_address);
    jbyteArray jb = sCallbackEnv->NewByteArray(62);
    sCallbackEnv->SetByteArrayRegion(jb, 0, 62, (jbyte *) adv_data);

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onScanResult
        , address, rssi, jb);

    sCallbackEnv->DeleteLocalRef(address);
    sCallbackEnv->DeleteLocalRef(jb);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_open_cb(int conn_id, int status, int clientIf, bt_bdaddr_t* bda)
{
    CHECK_CALLBACK_ENV

    char c_address[32];
    snprintf(c_address, sizeof(c_address),"%02X:%02X:%02X:%02X:%02X:%02X",
        bda->address[0], bda->address[1], bda->address[2],
        bda->address[3], bda->address[4], bda->address[5]);

    jstring address = sCallbackEnv->NewStringUTF(c_address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConnected,
        clientIf, conn_id, status, address);
    sCallbackEnv->DeleteLocalRef(address);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_close_cb(int conn_id, int status, int clientIf, bt_bdaddr_t* bda)
{
    CHECK_CALLBACK_ENV
    char c_address[32];
    snprintf(c_address, sizeof(c_address),"%02X:%02X:%02X:%02X:%02X:%02X",
        bda->address[0], bda->address[1], bda->address[2],
        bda->address[3], bda->address[4], bda->address[5]);

    jstring address = sCallbackEnv->NewStringUTF(c_address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onDisconnected,
        clientIf, conn_id, status, address);
    sCallbackEnv->DeleteLocalRef(address);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_search_complete_cb(int conn_id, int status)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onSearchCompleted,
                                 conn_id, status);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_search_result_cb(int conn_id, btgatt_srvc_id_t *srvc_id)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onSearchResult, conn_id,
        SRVC_ID_PARAMS(srvc_id));
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_get_characteristic_cb(int conn_id, int status,
                btgatt_srvc_id_t *srvc_id, btgatt_gatt_id_t *char_id,
                int char_prop)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onGetCharacteristic
        , conn_id, status, SRVC_ID_PARAMS(srvc_id), GATT_ID_PARAMS(char_id)
        , char_prop);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_get_descriptor_cb(int conn_id, int status,
                btgatt_srvc_id_t *srvc_id, btgatt_gatt_id_t *char_id,
                btgatt_gatt_id_t *descr_id)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onGetDescriptor
        , conn_id, status, SRVC_ID_PARAMS(srvc_id), GATT_ID_PARAMS(char_id)
        , GATT_ID_PARAMS(descr_id));
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_get_included_service_cb(int conn_id, int status,
                btgatt_srvc_id_t *srvc_id, btgatt_srvc_id_t *incl_srvc_id)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onGetIncludedService
        , conn_id, status, SRVC_ID_PARAMS(srvc_id), SRVC_ID_PARAMS(incl_srvc_id));
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_register_for_notification_cb(int conn_id, int registered, int status,
                                          btgatt_srvc_id_t *srvc_id, btgatt_gatt_id_t *char_id)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onRegisterForNotifications
        , conn_id, status, registered, SRVC_ID_PARAMS(srvc_id), GATT_ID_PARAMS(char_id));
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_notify_cb(int conn_id, btgatt_notify_params_t *p_data)
{
    CHECK_CALLBACK_ENV

    char c_address[32];
    snprintf(c_address, sizeof(c_address), "%02X:%02X:%02X:%02X:%02X:%02X",
        p_data->bda.address[0], p_data->bda.address[1], p_data->bda.address[2],
        p_data->bda.address[3], p_data->bda.address[4], p_data->bda.address[5]);

    jstring address = sCallbackEnv->NewStringUTF(c_address);
    jbyteArray jb = sCallbackEnv->NewByteArray(p_data->len);
    sCallbackEnv->SetByteArrayRegion(jb, 0, p_data->len, (jbyte *) p_data->value);

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onNotify
        , conn_id, address, SRVC_ID_PARAMS((&p_data->srvc_id))
        , GATT_ID_PARAMS((&p_data->char_id)), p_data->is_notify, jb);

    sCallbackEnv->DeleteLocalRef(address);
    sCallbackEnv->DeleteLocalRef(jb);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_read_characteristic_cb(int conn_id, int status, btgatt_read_params_t *p_data)
{
    CHECK_CALLBACK_ENV

    jbyteArray jb;
    if ( status == 0 )      //successful
    {
        jb = sCallbackEnv->NewByteArray(p_data->value.len);
        sCallbackEnv->SetByteArrayRegion(jb, 0, p_data->value.len,
            (jbyte *) p_data->value.value);
    } else {
        uint8_t value = 0;
        jb = sCallbackEnv->NewByteArray(1);
        sCallbackEnv->SetByteArrayRegion(jb, 0, 1, (jbyte *) &value);
    }

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onReadCharacteristic
        , conn_id, status, SRVC_ID_PARAMS((&p_data->srvc_id))
        , GATT_ID_PARAMS((&p_data->char_id)), p_data->value_type, jb);
    sCallbackEnv->DeleteLocalRef(jb);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_write_characteristic_cb(int conn_id, int status, btgatt_write_params_t *p_data)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onWriteCharacteristic
        , conn_id, status, SRVC_ID_PARAMS((&p_data->srvc_id))
        , GATT_ID_PARAMS((&p_data->char_id)));
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_execute_write_cb(int conn_id, int status)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExecuteCompleted
        , conn_id, status);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_read_descriptor_cb(int conn_id, int status, btgatt_read_params_t *p_data)
{
    CHECK_CALLBACK_ENV

    jbyteArray jb;
    if ( p_data->value.len != 0 )
    {
        jb = sCallbackEnv->NewByteArray(p_data->value.len);
        sCallbackEnv->SetByteArrayRegion(jb, 0, p_data->value.len,
                                (jbyte *) p_data->value.value);
    } else {
        jb = sCallbackEnv->NewByteArray(1);
    }

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onReadDescriptor
        , conn_id, status, SRVC_ID_PARAMS((&p_data->srvc_id))
        , GATT_ID_PARAMS((&p_data->char_id)), GATT_ID_PARAMS((&p_data->descr_id))
        , p_data->value_type, jb);

    sCallbackEnv->DeleteLocalRef(jb);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_write_descriptor_cb(int conn_id, int status, btgatt_write_params_t *p_data)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onWriteDescriptor
        , conn_id, status, SRVC_ID_PARAMS((&p_data->srvc_id))
        , GATT_ID_PARAMS((&p_data->char_id))
        , GATT_ID_PARAMS((&p_data->descr_id)));
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgattc_remote_rssi_cb(int client_if,bt_bdaddr_t* bda, int rssi, int status)
{
    CHECK_CALLBACK_ENV

    char c_address[32];
    snprintf(c_address, sizeof(c_address),"%02X:%02X:%02X:%02X:%02X:%02X",
        bda->address[0], bda->address[1], bda->address[2],
        bda->address[3], bda->address[4], bda->address[5]);
    jstring address = sCallbackEnv->NewStringUTF(c_address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onReadRemoteRssi,
       client_if, address, rssi, status);
    sCallbackEnv->DeleteLocalRef(address);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

static const btgatt_client_callbacks_t sGattClientCallbacks = {
    btgattc_register_app_cb,
    btgattc_scan_result_cb,
    btgattc_open_cb,
    btgattc_close_cb,
    btgattc_search_complete_cb,
    btgattc_search_result_cb,
    btgattc_get_characteristic_cb,
    btgattc_get_descriptor_cb,
    btgattc_get_included_service_cb,
    btgattc_register_for_notification_cb,
    btgattc_notify_cb,
    btgattc_read_characteristic_cb,
    btgattc_write_characteristic_cb,
    btgattc_read_descriptor_cb,
    btgattc_write_descriptor_cb,
    btgattc_execute_write_cb,
    btgattc_remote_rssi_cb
};


/**
 * BTA server callbacks
 */

void btgatts_register_app_cb(int status, int server_if, bt_uuid_t *uuid)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServerRegistered
        , status, server_if, UUID_PARAMS(uuid));
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgatts_connection_cb(int conn_id, int server_if, int connected, bt_bdaddr_t *bda)
{
    CHECK_CALLBACK_ENV

    char c_address[32];
    sprintf(c_address, "%02X:%02X:%02X:%02X:%02X:%02X",
            bda->address[0], bda->address[1], bda->address[2],
            bda->address[3], bda->address[4], bda->address[5]);

    jstring address = sCallbackEnv->NewStringUTF(c_address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onClientConnected,
                                 address, connected, conn_id, server_if);
    sCallbackEnv->DeleteLocalRef(address);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgatts_service_added_cb(int status, int server_if,
                              btgatt_srvc_id_t *srvc_id, int srvc_handle)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServiceAdded, status,
                                 server_if, SRVC_ID_PARAMS(srvc_id),
                                 srvc_handle);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgatts_included_service_added_cb(int status, int server_if,
                                   int srvc_handle,
                                   int incl_srvc_handle)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onIncludedServiceAdded,
                                 status, server_if, srvc_handle, incl_srvc_handle);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgatts_characteristic_added_cb(int status, int server_if, bt_uuid_t *char_id,
                                     int srvc_handle, int char_handle)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onCharacteristicAdded,
                                 status, server_if, UUID_PARAMS(char_id),
                                 srvc_handle, char_handle);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgatts_descriptor_added_cb(int status, int server_if,
                                 bt_uuid_t *descr_id, int srvc_handle,
                                 int descr_handle)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onDescriptorAdded,
                                 status, server_if, UUID_PARAMS(descr_id),
                                 srvc_handle, descr_handle);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgatts_service_started_cb(int status, int server_if, int srvc_handle)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServiceStarted, status,
                                 server_if, srvc_handle);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgatts_service_stopped_cb(int status, int server_if, int srvc_handle)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServiceStopped, status,
                                 server_if, srvc_handle);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgatts_service_deleted_cb(int status, int server_if, int srvc_handle)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onServiceDeleted, status,
                                 server_if, srvc_handle);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgatts_request_read_cb(int conn_id, int trans_id, bt_bdaddr_t *bda,
                             int attr_handle, int offset, bool is_long)
{
    CHECK_CALLBACK_ENV

    char c_address[32];
    sprintf(c_address, "%02X:%02X:%02X:%02X:%02X:%02X",
            bda->address[0], bda->address[1], bda->address[2],
            bda->address[3], bda->address[4], bda->address[5]);

    jstring address = sCallbackEnv->NewStringUTF(c_address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onAttributeRead,
                                 address, conn_id, trans_id, attr_handle,
                                 offset, is_long);
    sCallbackEnv->DeleteLocalRef(address);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgatts_request_write_cb(int conn_id, int trans_id,
                              bt_bdaddr_t *bda, int attr_handle,
                              int offset, int length,
                              bool need_rsp, bool is_prep, uint8_t* value)
{
    CHECK_CALLBACK_ENV

    char c_address[32];
    sprintf(c_address, "%02X:%02X:%02X:%02X:%02X:%02X",
            bda->address[0], bda->address[1], bda->address[2],
            bda->address[3], bda->address[4], bda->address[5]);

    jstring address = sCallbackEnv->NewStringUTF(c_address);

    jbyteArray val = sCallbackEnv->NewByteArray(length);
    if (val) sCallbackEnv->SetByteArrayRegion(val, 0, length, (jbyte*)value);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onAttributeWrite,
                                 address, conn_id, trans_id, attr_handle,
                                 offset, length, need_rsp, is_prep, val);
    sCallbackEnv->DeleteLocalRef(address);
    sCallbackEnv->DeleteLocalRef(val);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgatts_request_exec_write_cb(int conn_id, int trans_id,
                                   bt_bdaddr_t *bda, int exec_write)
{
    CHECK_CALLBACK_ENV

    char c_address[32];
    sprintf(c_address, "%02X:%02X:%02X:%02X:%02X:%02X",
            bda->address[0], bda->address[1], bda->address[2],
            bda->address[3], bda->address[4], bda->address[5]);

    jstring address = sCallbackEnv->NewStringUTF(c_address);
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onExecuteWrite,
                                 address, conn_id, trans_id, exec_write);
    sCallbackEnv->DeleteLocalRef(address);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

void btgatts_response_confirmation_cb(int status, int handle)
{
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onResponseSendCompleted,
                                 status, handle);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
}

static const btgatt_server_callbacks_t sGattServerCallbacks = {
    btgatts_register_app_cb,
    btgatts_connection_cb,
    btgatts_service_added_cb,
    btgatts_included_service_added_cb,
    btgatts_characteristic_added_cb,
    btgatts_descriptor_added_cb,
    btgatts_service_started_cb,
    btgatts_service_stopped_cb,
    btgatts_service_deleted_cb,
    btgatts_request_read_cb,
    btgatts_request_write_cb,
    btgatts_request_exec_write_cb,
    btgatts_response_confirmation_cb
};

/**
 * GATT callbacks
 */

static const btgatt_callbacks_t sGattCallbacks = {
    sizeof(btgatt_callbacks_t),
    &sGattClientCallbacks,
    &sGattServerCallbacks
};

/**
 * Native function definitions
 */

static void classInitNative(JNIEnv* env, jclass clazz) {

    // Client callbacks

    method_onClientRegistered = env->GetMethodID(clazz, "onClientRegistered", "(IIJJ)V");
    method_onScanResult = env->GetMethodID(clazz, "onScanResult", "(Ljava/lang/String;I[B)V");
    method_onConnected   = env->GetMethodID(clazz, "onConnected", "(IIILjava/lang/String;)V");
    method_onDisconnected = env->GetMethodID(clazz, "onDisconnected", "(IIILjava/lang/String;)V");
    method_onReadCharacteristic = env->GetMethodID(clazz, "onReadCharacteristic", "(IIIIJJIJJI[B)V");
    method_onWriteCharacteristic = env->GetMethodID(clazz, "onWriteCharacteristic", "(IIIIJJIJJ)V");
    method_onExecuteCompleted = env->GetMethodID(clazz, "onExecuteCompleted",  "(II)V");
    method_onSearchCompleted = env->GetMethodID(clazz, "onSearchCompleted",  "(II)V");
    method_onSearchResult = env->GetMethodID(clazz, "onSearchResult", "(IIIJJ)V");
    method_onReadDescriptor = env->GetMethodID(clazz, "onReadDescriptor", "(IIIIJJIJJIJJI[B)V");
    method_onWriteDescriptor = env->GetMethodID(clazz, "onWriteDescriptor", "(IIIIJJIJJIJJ)V");
    method_onNotify = env->GetMethodID(clazz, "onNotify", "(ILjava/lang/String;IIJJIJJZ[B)V");
    method_onGetCharacteristic = env->GetMethodID(clazz, "onGetCharacteristic", "(IIIIJJIJJI)V");
    method_onGetDescriptor = env->GetMethodID(clazz, "onGetDescriptor", "(IIIIJJIJJIJJ)V");
    method_onGetIncludedService = env->GetMethodID(clazz, "onGetIncludedService", "(IIIIJJIIJJ)V");
    method_onRegisterForNotifications = env->GetMethodID(clazz, "onRegisterForNotifications", "(IIIIIJJIJJ)V");
    method_onReadRemoteRssi = env->GetMethodID(clazz, "onReadRemoteRssi", "(ILjava/lang/String;II)V");

     // Server callbacks

    method_onServerRegistered = env->GetMethodID(clazz, "onServerRegistered", "(IIJJ)V");
    method_onClientConnected = env->GetMethodID(clazz, "onClientConnected", "(Ljava/lang/String;ZII)V");
    method_onServiceAdded = env->GetMethodID(clazz, "onServiceAdded", "(IIIIJJI)V");
    method_onIncludedServiceAdded = env->GetMethodID(clazz, "onIncludedServiceAdded", "(IIII)V");
    method_onCharacteristicAdded  = env->GetMethodID(clazz, "onCharacteristicAdded", "(IIJJII)V");
    method_onDescriptorAdded = env->GetMethodID(clazz, "onDescriptorAdded", "(IIJJII)V");
    method_onServiceStarted = env->GetMethodID(clazz, "onServiceStarted", "(III)V");
    method_onServiceStopped = env->GetMethodID(clazz, "onServiceStopped", "(III)V");
    method_onServiceDeleted = env->GetMethodID(clazz, "onServiceDeleted", "(III)V");
    method_onResponseSendCompleted = env->GetMethodID(clazz, "onResponseSendCompleted", "(II)V");
    method_onAttributeRead= env->GetMethodID(clazz, "onAttributeRead", "(Ljava/lang/String;IIIIZ)V");
    method_onAttributeWrite= env->GetMethodID(clazz, "onAttributeWrite", "(Ljava/lang/String;IIIIIZZ[B)V");
    method_onExecuteWrite= env->GetMethodID(clazz, "onExecuteWrite", "(Ljava/lang/String;III)V");

    info("classInitNative: Success!");
}

static const bt_interface_t* btIf;

static void initializeNative(JNIEnv *env, jobject object) {
    if(btIf)
        return;

    if ( (btIf = getBluetoothInterface()) == NULL) {
        error("Bluetooth module is not loaded");
        return;
    }

    if (sGattIf != NULL) {
         ALOGW("Cleaning up Bluetooth GATT Interface before initializing...");
         sGattIf->cleanup();
         sGattIf = NULL;
    }

    if (mCallbacksObj != NULL) {
         ALOGW("Cleaning up Bluetooth GATT callback object");
         env->DeleteGlobalRef(mCallbacksObj);
         mCallbacksObj = NULL;
    }

    if ( (sGattIf = (btgatt_interface_t *)
          btIf->get_profile_interface(BT_PROFILE_GATT_ID)) == NULL) {
        error("Failed to get Bluetooth GATT Interface");
        return;
    }

    bt_status_t status;
    if ( (status = sGattIf->init(&sGattCallbacks)) != BT_STATUS_SUCCESS) {
        error("Failed to initialize Bluetooth GATT, status: %d", status);
        sGattIf = NULL;
        return;
    }

    mCallbacksObj = env->NewGlobalRef(object);
}

static void cleanupNative(JNIEnv *env, jobject object) {
    bt_status_t status;
    if (!btIf) return;

    if (sGattIf != NULL) {
        sGattIf->cleanup();
        sGattIf = NULL;
    }

    if (mCallbacksObj != NULL) {
        env->DeleteGlobalRef(mCallbacksObj);
        mCallbacksObj = NULL;
    }
    btIf = NULL;
}

/**
 * Native Client functions
 */

static int gattClientGetDeviceTypeNative(JNIEnv* env, jobject object, jstring address)
{
    if (!sGattIf) return 0;
    bt_bdaddr_t bda;
    jstr2bdaddr(env, &bda, address);
    return sGattIf->client->get_device_type(&bda);
}

static void gattClientRegisterAppNative(JNIEnv* env, jobject object,
                                        jlong app_uuid_lsb, jlong app_uuid_msb )
{
    bt_uuid_t uuid;

    if (!sGattIf) return;
    set_uuid(uuid.uu, app_uuid_msb, app_uuid_lsb);
    sGattIf->client->register_client(&uuid);
}

static void gattClientUnregisterAppNative(JNIEnv* env, jobject object, jint clientIf)
{
    if (!sGattIf) return;
    sGattIf->client->unregister_client(clientIf);
}

static void gattClientScanNative(JNIEnv* env, jobject object, jint clientIf, jboolean start)
{
    if (!sGattIf) return;
    sGattIf->client->scan(clientIf, start);
}

static void gattClientConnectNative(JNIEnv* env, jobject object, jint clientif,
                                 jstring address, jboolean isDirect)
{
    if (!sGattIf) return;

    bt_bdaddr_t bda;
    jstr2bdaddr(env, &bda, address);
    sGattIf->client->connect(clientif, &bda, isDirect);
}

static void gattClientDisconnectNative(JNIEnv* env, jobject object, jint clientIf,
                                  jstring address, jint conn_id)
{
    if (!sGattIf) return;
    bt_bdaddr_t bda;
    jstr2bdaddr(env, &bda, address);
    sGattIf->client->disconnect(clientIf, &bda, conn_id);
}

static void gattClientRefreshNative(JNIEnv* env, jobject object, jint clientIf,
                                    jstring address)
{
    if (!sGattIf) return;

    bt_bdaddr_t bda;
    jstr2bdaddr(env, &bda, address);
    sGattIf->client->refresh(clientIf, &bda);
}

static void gattClientSearchServiceNative(JNIEnv* env, jobject object, jint conn_id,
            jboolean search_all, jlong service_uuid_lsb, jlong service_uuid_msb)
{
    if (!sGattIf) return;

    bt_uuid_t uuid;
    set_uuid(uuid.uu, service_uuid_msb, service_uuid_lsb);
    sGattIf->client->search_service(conn_id, search_all ? 0 : &uuid);
}

static void gattClientGetCharacteristicNative(JNIEnv* env, jobject object,
    jint conn_id,
    jint  service_type, jint  service_id_inst_id,
    jlong service_id_uuid_lsb, jlong service_id_uuid_msb,
    jint  char_id_inst_id,
    jlong char_id_uuid_lsb, jlong char_id_uuid_msb)
{
    if (!sGattIf) return;

    btgatt_srvc_id_t srvc_id;
    srvc_id.id.inst_id = (uint8_t) service_id_inst_id;
    srvc_id.is_primary = (service_type == BTGATT_SERVICE_TYPE_PRIMARY ? 1 : 0);

    set_uuid(srvc_id.id.uuid.uu, service_id_uuid_msb, service_id_uuid_lsb);

    btgatt_gatt_id_t char_id;
    char_id.inst_id = (uint8_t) char_id_inst_id;
    set_uuid(char_id.uuid.uu, char_id_uuid_msb, char_id_uuid_lsb);

    if (char_id_uuid_lsb == 0)
    {
        sGattIf->client->get_characteristic(conn_id, &srvc_id, 0);
    } else {
        sGattIf->client->get_characteristic(conn_id, &srvc_id, &char_id);
    }
}

static void gattClientGetDescriptorNative(JNIEnv* env, jobject object,
    jint conn_id,
    jint  service_type, jint  service_id_inst_id,
    jlong service_id_uuid_lsb, jlong service_id_uuid_msb,
    jint  char_id_inst_id,
    jlong char_id_uuid_lsb, jlong char_id_uuid_msb,
    jint descr_id_inst_id,
    jlong descr_id_uuid_lsb, jlong descr_id_uuid_msb)
{
    if (!sGattIf) return;

    btgatt_srvc_id_t srvc_id;
    srvc_id.id.inst_id = (uint8_t) service_id_inst_id;
    srvc_id.is_primary = (service_type == BTGATT_SERVICE_TYPE_PRIMARY ? 1 : 0);
    set_uuid(srvc_id.id.uuid.uu, service_id_uuid_msb, service_id_uuid_lsb);

    btgatt_gatt_id_t char_id;
    char_id.inst_id = (uint8_t) char_id_inst_id;
    set_uuid(char_id.uuid.uu, char_id_uuid_msb, char_id_uuid_lsb);

    btgatt_gatt_id_t descr_id;
    descr_id.inst_id = (uint8_t) descr_id_inst_id;
    set_uuid(descr_id.uuid.uu, descr_id_uuid_msb, descr_id_uuid_lsb);

    if (descr_id_uuid_lsb == 0)
    {
        sGattIf->client->get_descriptor(conn_id, &srvc_id, &char_id, 0);
    } else {
        sGattIf->client->get_descriptor(conn_id, &srvc_id, &char_id, &descr_id);
    }
}

static void gattClientGetIncludedServiceNative(JNIEnv* env, jobject object,
    jint conn_id, jint service_type, jint service_id_inst_id,
    jlong service_id_uuid_lsb, jlong service_id_uuid_msb,
    jint incl_service_id_inst_id, jint incl_service_type,
    jlong incl_service_id_uuid_lsb, jlong incl_service_id_uuid_msb)
{
    if (!sGattIf) return;

    btgatt_srvc_id_t srvc_id;
    srvc_id.id.inst_id = (uint8_t) service_id_inst_id;
    srvc_id.is_primary = (service_type == BTGATT_SERVICE_TYPE_PRIMARY ? 1 : 0);
    set_uuid(srvc_id.id.uuid.uu, service_id_uuid_msb, service_id_uuid_lsb);

    btgatt_srvc_id_t  incl_srvc_id;
    incl_srvc_id.id.inst_id = (uint8_t) incl_service_id_inst_id;
    incl_srvc_id.is_primary = (incl_service_type == BTGATT_SERVICE_TYPE_PRIMARY ? 1 : 0);
    set_uuid(incl_srvc_id.id.uuid.uu, incl_service_id_uuid_msb, incl_service_id_uuid_lsb);

    if (incl_service_id_uuid_lsb == 0)
    {
        sGattIf->client->get_included_service(conn_id, &srvc_id, 0);
    } else {
        sGattIf->client->get_included_service(conn_id, &srvc_id, &incl_srvc_id);
    }
}

static void gattClientReadCharacteristicNative(JNIEnv* env, jobject object,
    jint conn_id, jint  service_type, jint  service_id_inst_id,
    jlong service_id_uuid_lsb, jlong service_id_uuid_msb,
    jint  char_id_inst_id,
    jlong char_id_uuid_lsb, jlong char_id_uuid_msb,
    jint authReq)
{
    if (!sGattIf) return;

    btgatt_srvc_id_t srvc_id;
    srvc_id.id.inst_id = (uint8_t) service_id_inst_id;
    srvc_id.is_primary = (service_type == BTGATT_SERVICE_TYPE_PRIMARY ? 1 : 0);
    set_uuid(srvc_id.id.uuid.uu, service_id_uuid_msb, service_id_uuid_lsb);

    btgatt_gatt_id_t char_id;
    char_id.inst_id = (uint8_t) char_id_inst_id;
    set_uuid(char_id.uuid.uu, char_id_uuid_msb, char_id_uuid_lsb);

    sGattIf->client->read_characteristic(conn_id, &srvc_id, &char_id, authReq);
}

static void gattClientReadDescriptorNative(JNIEnv* env, jobject object,
    jint conn_id, jint  service_type, jint  service_id_inst_id,
    jlong service_id_uuid_lsb, jlong service_id_uuid_msb,
    jint  char_id_inst_id,
    jlong char_id_uuid_lsb, jlong char_id_uuid_msb,
    jint descr_id_inst_id,
    jlong descr_id_uuid_lsb, jlong descr_id_uuid_msb,
    jint authReq)
{
    if (!sGattIf) return;

    btgatt_srvc_id_t srvc_id;
    srvc_id.id.inst_id = (uint8_t) service_id_inst_id;
    srvc_id.is_primary = (service_type == BTGATT_SERVICE_TYPE_PRIMARY ? 1 : 0);
    set_uuid(srvc_id.id.uuid.uu, service_id_uuid_msb, service_id_uuid_lsb);

    btgatt_gatt_id_t char_id;
    char_id.inst_id = (uint8_t) char_id_inst_id;
    set_uuid(char_id.uuid.uu, char_id_uuid_msb, char_id_uuid_lsb);

    btgatt_gatt_id_t descr_id;
    descr_id.inst_id = (uint8_t) descr_id_inst_id;
    set_uuid(descr_id.uuid.uu, descr_id_uuid_msb, descr_id_uuid_lsb);

    sGattIf->client->read_descriptor(conn_id, &srvc_id, &char_id, &descr_id, authReq);
}

static void gattClientWriteCharacteristicNative(JNIEnv* env, jobject object,
    jint conn_id, jint  service_type, jint  service_id_inst_id,
    jlong service_id_uuid_lsb, jlong service_id_uuid_msb,
    jint  char_id_inst_id,
    jlong char_id_uuid_lsb, jlong char_id_uuid_msb,
    jint write_type, jint auth_req, jbyteArray value)
{
    if (!sGattIf) return;

    btgatt_srvc_id_t srvc_id;
    srvc_id.id.inst_id = (uint8_t) service_id_inst_id;
    srvc_id.is_primary = (service_type == BTGATT_SERVICE_TYPE_PRIMARY ? 1 : 0);
    set_uuid(srvc_id.id.uuid.uu, service_id_uuid_msb, service_id_uuid_lsb);

    btgatt_gatt_id_t char_id;
    char_id.inst_id = (uint8_t) char_id_inst_id;
    set_uuid(char_id.uuid.uu, char_id_uuid_msb, char_id_uuid_lsb);

    uint16_t len = (uint16_t) env->GetArrayLength(value);
    jbyte *p_value = env->GetByteArrayElements(value, NULL);
    if (p_value == NULL) return;

    sGattIf->client->write_characteristic(conn_id, &srvc_id, &char_id,
                                    write_type, len, auth_req, (char*)p_value);
    env->ReleaseByteArrayElements(value, p_value, 0);
}

static void gattClientExecuteWriteNative(JNIEnv* env, jobject object,
    jint conn_id, jboolean execute)
{
    if (!sGattIf) return;
    sGattIf->client->execute_write(conn_id, execute ? 1 : 0);
}

static void gattClientWriteDescriptorNative(JNIEnv* env, jobject object,
    jint conn_id, jint  service_type, jint service_id_inst_id,
    jlong service_id_uuid_lsb, jlong service_id_uuid_msb,
    jint char_id_inst_id,
    jlong char_id_uuid_lsb, jlong char_id_uuid_msb,
    jint descr_id_inst_id,
    jlong descr_id_uuid_lsb, jlong descr_id_uuid_msb,
    jint write_type, jint auth_req, jbyteArray value)
{
    if (!sGattIf) return;

    if (value == NULL) {
        warn("gattClientWriteDescriptorNative() ignoring NULL array");
        return;
    }

    btgatt_srvc_id_t srvc_id;
    srvc_id.id.inst_id = (uint8_t) service_id_inst_id;
    srvc_id.is_primary = (service_type == BTGATT_SERVICE_TYPE_PRIMARY ? 1 : 0);
    set_uuid(srvc_id.id.uuid.uu, service_id_uuid_msb, service_id_uuid_lsb);

    btgatt_gatt_id_t char_id;
    char_id.inst_id = (uint8_t) char_id_inst_id;
    set_uuid(char_id.uuid.uu, char_id_uuid_msb, char_id_uuid_lsb);

    btgatt_gatt_id_t descr_id;
    descr_id.inst_id = (uint8_t) descr_id_inst_id;
    set_uuid(descr_id.uuid.uu, descr_id_uuid_msb, descr_id_uuid_lsb);

    uint16_t len = (uint16_t) env->GetArrayLength(value);
    jbyte *p_value = env->GetByteArrayElements(value, NULL);
    if (p_value == NULL) return;

    sGattIf->client->write_descriptor(conn_id, &srvc_id, &char_id, &descr_id,
                                    write_type, len, auth_req, (char*)p_value);
    env->ReleaseByteArrayElements(value, p_value, 0);
}

static void gattClientRegisterForNotificationsNative(JNIEnv* env, jobject object,
    jint clientIf, jstring address,
    jint  service_type, jint service_id_inst_id,
    jlong service_id_uuid_lsb, jlong service_id_uuid_msb,
    jint char_id_inst_id,
    jlong char_id_uuid_lsb, jlong char_id_uuid_msb,
    jboolean enable)
{
    if (!sGattIf) return;

    btgatt_srvc_id_t srvc_id;
    srvc_id.id.inst_id = (uint8_t) service_id_inst_id;
    srvc_id.is_primary = (service_type == BTGATT_SERVICE_TYPE_PRIMARY ? 1 : 0);
    set_uuid(srvc_id.id.uuid.uu, service_id_uuid_msb, service_id_uuid_lsb);

    btgatt_gatt_id_t char_id;
    char_id.inst_id = (uint8_t) char_id_inst_id;
    set_uuid(char_id.uuid.uu, char_id_uuid_msb, char_id_uuid_lsb);

    bt_bdaddr_t bd_addr;
    const char *c_address = env->GetStringUTFChars(address, NULL);
    bd_addr_str_to_addr(c_address, bd_addr.address);

    if (enable)
        sGattIf->client->register_for_notification(clientIf, &bd_addr, &srvc_id, &char_id);
    else
        sGattIf->client->deregister_for_notification(clientIf, &bd_addr, &srvc_id, &char_id);
}

static void gattClientReadRemoteRssiNative(JNIEnv* env, jobject object, jint clientif,
                                 jstring address)
{
    if (!sGattIf) return;

    bt_bdaddr_t bda;
    jstr2bdaddr(env, &bda, address);

    sGattIf->client->read_remote_rssi(clientif, &bda);
}

static void gattAdvertiseNative(JNIEnv *env, jobject object,
        jint client_if, jboolean start)
{
    if (!sGattIf) return;
    sGattIf->client->listen(client_if, start);
}

static void gattSetAdvDataNative(JNIEnv *env, jobject object, jint client_if, jboolean setScanRsp,
        jboolean inclName, jboolean inclTxPower, jint minInterval, jint maxInterval,
        jint appearance, jbyteArray manufacturerData, jbyteArray serviceData,
        jbyteArray serviceUuid)
{
    if (!sGattIf) return;
    jbyte* arr_data = env->GetByteArrayElements(manufacturerData, NULL);
    uint16_t arr_len = (uint16_t) env->GetArrayLength(manufacturerData);

    jbyte* service_data = env->GetByteArrayElements(serviceData, NULL);
    uint16_t service_data_len = (uint16_t) env->GetArrayLength(serviceData);

    jbyte* service_uuid = env->GetByteArrayElements(serviceUuid, NULL);
    uint16_t service_uuid_len = (uint16_t) env->GetArrayLength(serviceUuid);

    sGattIf->client->set_adv_data(client_if, setScanRsp, inclName, inclTxPower,
        minInterval, maxInterval, appearance, arr_len, (char*)arr_data,
        service_data_len, (char*)service_data, service_uuid_len,
        (char*)service_uuid);

    env->ReleaseByteArrayElements(manufacturerData, arr_data, JNI_ABORT);
    env->ReleaseByteArrayElements(serviceData, service_data, JNI_ABORT);
    env->ReleaseByteArrayElements(serviceUuid, service_uuid, JNI_ABORT);
}


/**
 * Native server functions
 */
static void gattServerRegisterAppNative(JNIEnv* env, jobject object,
                                        jlong app_uuid_lsb, jlong app_uuid_msb )
{
    bt_uuid_t uuid;
    if (!sGattIf) return;
    set_uuid(uuid.uu, app_uuid_msb, app_uuid_lsb);
    sGattIf->server->register_server(&uuid);
}

static void gattServerUnregisterAppNative(JNIEnv* env, jobject object, jint serverIf)
{
    if (!sGattIf) return;
    sGattIf->server->unregister_server(serverIf);
}

static void gattServerConnectNative(JNIEnv *env, jobject object,
                                 jint server_if, jstring address, jboolean is_direct)
{
    if (!sGattIf) return;

    bt_bdaddr_t bd_addr;
    const char *c_address = env->GetStringUTFChars(address, NULL);
    bd_addr_str_to_addr(c_address, bd_addr.address);

    sGattIf->server->connect(server_if, &bd_addr, is_direct);
}

static void gattServerDisconnectNative(JNIEnv* env, jobject object, jint serverIf,
                                  jstring address, jint conn_id)
{
    if (!sGattIf) return;
    bt_bdaddr_t bda;
    jstr2bdaddr(env, &bda, address);
    sGattIf->server->disconnect(serverIf, &bda, conn_id);
}

static void gattServerAddServiceNative (JNIEnv *env, jobject object,
        jint server_if, jint service_type, jint service_id_inst_id,
        jlong service_id_uuid_lsb, jlong service_id_uuid_msb,
        jint num_handles)
{
    if (!sGattIf) return;

    btgatt_srvc_id_t srvc_id;
    srvc_id.id.inst_id = (uint8_t) service_id_inst_id;
    srvc_id.is_primary = (service_type == BTGATT_SERVICE_TYPE_PRIMARY ? 1 : 0);
    set_uuid(srvc_id.id.uuid.uu, service_id_uuid_msb, service_id_uuid_lsb);

    sGattIf->server->add_service(server_if, &srvc_id, num_handles);
}

static void gattServerAddIncludedServiceNative (JNIEnv *env, jobject object,
        jint server_if, jint svc_handle, jint included_svc_handle)
{
    if (!sGattIf) return;
    sGattIf->server->add_included_service(server_if, svc_handle,
                                          included_svc_handle);
}

static void gattServerAddCharacteristicNative (JNIEnv *env, jobject object,
        jint server_if, jint svc_handle,
        jlong char_uuid_lsb, jlong char_uuid_msb,
        jint properties, jint permissions)
{
    if (!sGattIf) return;

    bt_uuid_t uuid;
    set_uuid(uuid.uu, char_uuid_msb, char_uuid_lsb);

    sGattIf->server->add_characteristic(server_if, svc_handle,
                                        &uuid, properties, permissions);
}

static void gattServerAddDescriptorNative (JNIEnv *env, jobject object,
        jint server_if, jint svc_handle,
        jlong desc_uuid_lsb, jlong desc_uuid_msb,
        jint permissions)
{
    if (!sGattIf) return;

    bt_uuid_t uuid;
    set_uuid(uuid.uu, desc_uuid_msb, desc_uuid_lsb);

    sGattIf->server->add_descriptor(server_if, svc_handle, &uuid, permissions);
}

static void gattServerStartServiceNative (JNIEnv *env, jobject object,
        jint server_if, jint svc_handle, jint transport )
{
    if (!sGattIf) return;
    sGattIf->server->start_service(server_if, svc_handle, transport);
}

static void gattServerStopServiceNative (JNIEnv *env, jobject object,
                                         jint server_if, jint svc_handle)
{
    if (!sGattIf) return;
    sGattIf->server->stop_service(server_if, svc_handle);
}

static void gattServerDeleteServiceNative (JNIEnv *env, jobject object,
                                           jint server_if, jint svc_handle)
{
    if (!sGattIf) return;
    sGattIf->server->delete_service(server_if, svc_handle);
}

static void gattServerSendIndicationNative (JNIEnv *env, jobject object,
        jint server_if, jint attr_handle, jint conn_id, jbyteArray val)
{
    if (!sGattIf) return;

    jbyte* array = env->GetByteArrayElements(val, 0);
    int val_len = env->GetArrayLength(val);

    sGattIf->server->send_indication(server_if, attr_handle, conn_id, val_len,
                                     /*confirm*/ 1, (char*)array);
    env->ReleaseByteArrayElements(val, array, JNI_ABORT);
}

static void gattServerSendNotificationNative (JNIEnv *env, jobject object,
        jint server_if, jint attr_handle, jint conn_id, jbyteArray val)
{
    if (!sGattIf) return;

    jbyte* array = env->GetByteArrayElements(val, 0);
    int val_len = env->GetArrayLength(val);

    sGattIf->server->send_indication(server_if, attr_handle, conn_id, val_len,
                                     /*confirm*/ 0, (char*)array);
    env->ReleaseByteArrayElements(val, array, JNI_ABORT);
}

static void gattServerSendResponseNative (JNIEnv *env, jobject object,
        jint server_if, jint conn_id, jint trans_id, jint status,
        jint handle, jint offset, jbyteArray val, jint auth_req)
{
    if (!sGattIf) return;

    btgatt_response_t response;

    response.attr_value.handle = handle;
    response.attr_value.auth_req = auth_req;
    response.attr_value.offset = offset;
    response.attr_value.len = 0;

    if (val != NULL)
    {
        response.attr_value.len = (uint16_t) env->GetArrayLength(val);
        jbyte* array = env->GetByteArrayElements(val, 0);

        for (int i = 0; i != response.attr_value.len; ++i)
            response.attr_value.value[i] = (uint8_t) array[i];
        env->ReleaseByteArrayElements(val, array, JNI_ABORT);
    }

    sGattIf->server->send_response(conn_id, trans_id, status, &response);
}

static void gattTestNative(JNIEnv *env, jobject object, jint command,
                           jlong uuid1_lsb, jlong uuid1_msb, jstring bda1,
                           jint p1, jint p2, jint p3, jint p4, jint p5 )
{
    if (!sGattIf) return;

    bt_bdaddr_t bt_bda1;
    jstr2bdaddr(env, &bt_bda1, bda1);

    bt_uuid_t uuid1;
    set_uuid(uuid1.uu, uuid1_msb, uuid1_lsb);

    btgatt_test_params_t params;
    params.bda1 = &bt_bda1;
    params.uuid1 = &uuid1;
    params.u1 = p1;
    params.u2 = p2;
    params.u3 = p3;
    params.u4 = p4;
    params.u5 = p5;
    sGattIf->client->test_command(command, &params);
}

/**
 * JNI function definitinos
 */

static JNINativeMethod sMethods[] = {
    {"classInitNative", "()V", (void *) classInitNative},
    {"initializeNative", "()V", (void *) initializeNative},
    {"cleanupNative", "()V", (void *) cleanupNative},

    {"gattClientGetDeviceTypeNative", "(Ljava/lang/String;)I", (void *) gattClientGetDeviceTypeNative},
    {"gattClientRegisterAppNative", "(JJ)V", (void *) gattClientRegisterAppNative},
    {"gattClientUnregisterAppNative", "(I)V", (void *) gattClientUnregisterAppNative},
    {"gattClientScanNative", "(IZ)V", (void *) gattClientScanNative},
    {"gattClientConnectNative", "(ILjava/lang/String;Z)V", (void *) gattClientConnectNative},
    {"gattClientDisconnectNative", "(ILjava/lang/String;I)V", (void *) gattClientDisconnectNative},
    {"gattClientRefreshNative", "(ILjava/lang/String;)V", (void *) gattClientRefreshNative},
    {"gattClientSearchServiceNative", "(IZJJ)V", (void *) gattClientSearchServiceNative},
    {"gattClientGetCharacteristicNative", "(IIIJJIJJ)V", (void *) gattClientGetCharacteristicNative},
    {"gattClientGetDescriptorNative", "(IIIJJIJJIJJ)V", (void *) gattClientGetDescriptorNative},
    {"gattClientGetIncludedServiceNative", "(IIIJJIIJJ)V", (void *) gattClientGetIncludedServiceNative},
    {"gattClientReadCharacteristicNative", "(IIIJJIJJI)V", (void *) gattClientReadCharacteristicNative},
    {"gattClientReadDescriptorNative", "(IIIJJIJJIJJI)V", (void *) gattClientReadDescriptorNative},
    {"gattClientWriteCharacteristicNative", "(IIIJJIJJII[B)V", (void *) gattClientWriteCharacteristicNative},
    {"gattClientWriteDescriptorNative", "(IIIJJIJJIJJII[B)V", (void *) gattClientWriteDescriptorNative},
    {"gattClientExecuteWriteNative", "(IZ)V", (void *) gattClientExecuteWriteNative},
    {"gattClientRegisterForNotificationsNative", "(ILjava/lang/String;IIJJIJJZ)V", (void *) gattClientRegisterForNotificationsNative},
    {"gattClientReadRemoteRssiNative", "(ILjava/lang/String;)V", (void *) gattClientReadRemoteRssiNative},
    {"gattAdvertiseNative", "(IZ)V", (void *) gattAdvertiseNative},

    {"gattServerRegisterAppNative", "(JJ)V", (void *) gattServerRegisterAppNative},
    {"gattServerUnregisterAppNative", "(I)V", (void *) gattServerUnregisterAppNative},
    {"gattServerConnectNative", "(ILjava/lang/String;Z)V", (void *) gattServerConnectNative},
    {"gattServerDisconnectNative", "(ILjava/lang/String;I)V", (void *) gattServerDisconnectNative},
    {"gattServerAddServiceNative", "(IIIJJI)V", (void *) gattServerAddServiceNative},
    {"gattServerAddIncludedServiceNative", "(III)V", (void *) gattServerAddIncludedServiceNative},
    {"gattServerAddCharacteristicNative", "(IIJJII)V", (void *) gattServerAddCharacteristicNative},
    {"gattServerAddDescriptorNative", "(IIJJI)V", (void *) gattServerAddDescriptorNative},
    {"gattServerStartServiceNative", "(III)V", (void *) gattServerStartServiceNative},
    {"gattServerStopServiceNative", "(II)V", (void *) gattServerStopServiceNative},
    {"gattServerDeleteServiceNative", "(II)V", (void *) gattServerDeleteServiceNative},
    {"gattServerSendIndicationNative", "(III[B)V", (void *) gattServerSendIndicationNative},
    {"gattServerSendNotificationNative", "(III[B)V", (void *) gattServerSendNotificationNative},
    {"gattServerSendResponseNative", "(IIIIII[BI)V", (void *) gattServerSendResponseNative},

    {"gattSetAdvDataNative", "(IZZZIII[B[B[B)V", (void *) gattSetAdvDataNative},
    {"gattTestNative", "(IJJLjava/lang/String;IIIII)V", (void *) gattTestNative},
};

int register_com_android_bluetooth_gatt(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/bluetooth/gatt/GattService",
                                    sMethods, NELEM(sMethods));
}

}
