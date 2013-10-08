/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#define LOG_TAG "QBluetoothAdapterServiceJni"

#define BD_ADDR_LEN 6

#include "com_android_bluetooth.h"
#include "utils/Log.h"
#include "utils/misc.h"
#include "cutils/properties.h"
#include "android_runtime/AndroidRuntime.h"

#include <string.h>
#include <pthread.h>

#include <sys/stat.h>
#include <fcntl.h>


namespace android {
static jmethodID method_onLeExtendedScanResultCallback;
static jmethodID method_onLeLppWriteRssiThreshold;
static jmethodID method_onLeLppReadRssiThreshold;
static jmethodID method_onLeLppEnableRssiMonitor;
static jmethodID method_onLeLppRssiThresholdEvent;

static const bt_interface_t *qcBluetoothInterface = NULL;
static JNIEnv *qccallbackEnv = NULL;
static jobject qcJniCallbacksObj=NULL;
static jfieldID qcJniCallbacksField;

static void set_uuid(uint8_t* uuid, jlong uuid_msb, jlong uuid_lsb)
{
    for (int i = 0; i != 8; ++i)
    {
        uuid[i]     = (uuid_lsb >> (8 * i)) & 0xFF;
        uuid[i + 8] = (uuid_msb >> (8 * i)) & 0xFF;
    }
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

static bool checkCallbackThread() {
    qccallbackEnv = getCallbackEnv();

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if(env==NULL)
        ALOGE("checkCallbackThread env is NULL");
    if(qccallbackEnv==NULL)
        ALOGE("checkCallbackThread qccallbackEnv is NULL");

    if(env!=qccallbackEnv)
        ALOGE("env and qccallbackEnv dont match");
    if (qccallbackEnv != env || qccallbackEnv == NULL) return false;
    return true;
}

static void le_extended_scan_result_callbacks(bt_bdaddr_t* bda, int rssi, uint8_t* adv_data)
{
    char c_address[32];
    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
    }

    snprintf(c_address, sizeof(c_address),"%02X:%02X:%02X:%02X:%02X:%02X",
        bda->address[0], bda->address[1], bda->address[2],
        bda->address[3], bda->address[4], bda->address[5]);

    jstring address = qccallbackEnv->NewStringUTF(c_address);
    jbyteArray jb = qccallbackEnv->NewByteArray(62);
    qccallbackEnv->SetByteArrayRegion(jb, 0, 62, (jbyte *) adv_data);

    qccallbackEnv->CallVoidMethod(qcJniCallbacksObj, method_onLeExtendedScanResultCallback,
                                  address, rssi, jb);

    qccallbackEnv->DeleteLocalRef(address);
    qccallbackEnv->DeleteLocalRef(jb);

    checkAndClearExceptionFromCallback(qccallbackEnv, __FUNCTION__);
}

void le_lpp_write_rssi_thresh_callbacks(bt_bdaddr_t *bda, int status)
{
    char c_address[32] = {0};
    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }

    snprintf(c_address, sizeof(c_address),"%02X:%02X:%02X:%02X:%02X:%02X",
        bda->address[0], bda->address[1], bda->address[2],
        bda->address[3], bda->address[4], bda->address[5]);
    jstring address = qccallbackEnv->NewStringUTF(c_address);
    qccallbackEnv->CallVoidMethod(qcJniCallbacksObj, method_onLeLppWriteRssiThreshold,
                                  address, status);
    qccallbackEnv->DeleteLocalRef(address);
    checkAndClearExceptionFromCallback(qccallbackEnv, __FUNCTION__);
}

void le_lpp_read_rssi_thresh_callbacks(bt_bdaddr_t *bda, int low, int upper,
                                int alert, int status)
{
    char c_address[32] = {0};
    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }
    snprintf(c_address, sizeof(c_address),"%02X:%02X:%02X:%02X:%02X:%02X",
        bda->address[0], bda->address[1], bda->address[2],
        bda->address[3], bda->address[4], bda->address[5]);
    jstring address = qccallbackEnv->NewStringUTF(c_address);
    qccallbackEnv->CallVoidMethod(qcJniCallbacksObj, method_onLeLppReadRssiThreshold,
                                  address, low, upper, alert, status);
    qccallbackEnv->DeleteLocalRef(address);
    checkAndClearExceptionFromCallback(qccallbackEnv, __FUNCTION__);
}

void le_lpp_enable_rssi_monitor_callbacks(bt_bdaddr_t *bda,
                                    int enable, int status)
{
    char c_address[32] = {0};
    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }
    snprintf(c_address, sizeof(c_address),"%02X:%02X:%02X:%02X:%02X:%02X",
        bda->address[0], bda->address[1], bda->address[2],
        bda->address[3], bda->address[4], bda->address[5]);
    jstring address = qccallbackEnv->NewStringUTF(c_address);
    qccallbackEnv->CallVoidMethod(qcJniCallbacksObj, method_onLeLppEnableRssiMonitor,
                                  address, enable, status);
    qccallbackEnv->DeleteLocalRef(address);
    checkAndClearExceptionFromCallback(qccallbackEnv, __FUNCTION__);
}

void le_lpp_rssi_threshold_evt_callbacks(bt_bdaddr_t *bda,
                                  int evt_type, int rssi)
{
    char c_address[32] = {0};
    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }
    snprintf(c_address, sizeof(c_address),"%02X:%02X:%02X:%02X:%02X:%02X",
        bda->address[0], bda->address[1], bda->address[2],
        bda->address[3], bda->address[4], bda->address[5]);
    jstring address = qccallbackEnv->NewStringUTF(c_address);
    qccallbackEnv->CallVoidMethod(qcJniCallbacksObj, method_onLeLppRssiThresholdEvent,
                                  address, evt_type, rssi);
    qccallbackEnv->DeleteLocalRef(address);
    checkAndClearExceptionFromCallback(qccallbackEnv, __FUNCTION__);
}

bt_callbacks_t sQBluetoothCallbacks = {
    sizeof(sQBluetoothCallbacks),
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    le_extended_scan_result_callbacks,
    le_lpp_write_rssi_thresh_callbacks,
    le_lpp_read_rssi_thresh_callbacks,
    le_lpp_enable_rssi_monitor_callbacks,
    le_lpp_rssi_threshold_evt_callbacks,
};
static void classInitNative(JNIEnv* env, jclass clazz) {
    int err;
    hw_module_t* module;

    ALOGV("%s:",__FUNCTION__);
    if((qcBluetoothInterface=getBluetoothInterface())==NULL){
        ALOGE("Bluetooth module is not loaded");
        return;
    }

    jclass jniCallbackClass =
        env->FindClass("com/android/bluetooth/btservice/QJniCallbacks");
    qcJniCallbacksField = env->GetFieldID(clazz, "mJniCallbacks",
        "Lcom/android/bluetooth/btservice/QJniCallbacks;");

    method_onLeExtendedScanResultCallback = env->GetMethodID(jniCallbackClass,
                                                  "onLeExtendedScanResult", "(Ljava/lang/String;I[B)V");
    method_onLeLppWriteRssiThreshold = env->GetMethodID(jniCallbackClass, "onLeLppWriteRssiThreshold", "(Ljava/lang/String;I)V");
    method_onLeLppReadRssiThreshold = env->GetMethodID(jniCallbackClass, "onLeLppReadRssiThreshold", "(Ljava/lang/String;IIII)V");
    method_onLeLppEnableRssiMonitor = env->GetMethodID(jniCallbackClass, "onLeLppEnableRssiMonitor", "(Ljava/lang/String;II)V");
    method_onLeLppRssiThresholdEvent = env->GetMethodID(jniCallbackClass, "onLeLppRssiThresholdEvent", "(Ljava/lang/String;II)V");

}

static bool initNative(JNIEnv* env, jobject obj) {
    ALOGV("%s:",__FUNCTION__);

    qcJniCallbacksObj = env->NewGlobalRef(env->GetObjectField(obj, qcJniCallbacksField));

    if (qcBluetoothInterface) {
        int ret = qcBluetoothInterface->initq(&sQBluetoothCallbacks);
        if (ret != BT_STATUS_SUCCESS) {
            ALOGE("Error while setting the callbacks \n");
            qcBluetoothInterface = NULL;
            return JNI_FALSE;
        }
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static bool cleanupNative(JNIEnv *env, jobject obj) {
    ALOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!qcBluetoothInterface) return result;

    if(qcJniCallbacksObj!=NULL){
        env->DeleteGlobalRef(qcJniCallbacksObj);
        qcJniCallbacksObj=NULL;
    }
    return JNI_TRUE;
}

static void btLeExtendedScanNative(JNIEnv *env, jobject object,
                                   jobjectArray uuids, jboolean start)
{
    int i = 0, entries = 0;
    uint8_t scan_policy = 0x80; /* 0x80 represent only AD whitelist applied*/
    bt_le_service_t *service_list = 0;
    entries = env->GetArrayLength(uuids);
    if(qcBluetoothInterface && (service_list = (bt_le_service_t*)malloc(entries * sizeof(bt_le_service_t))))
    {
        jclass clazzuuid;
        jobject uuid;
        jmethodID getlsb, getmsb, gettype;
        jlong lsbbits, msbbits;
        if ((clazzuuid = env->FindClass("android/bluetooth/BluetoothLEServiceUuid")) != NULL &&
            (getlsb = env->GetMethodID(clazzuuid, "getLeastSignificantBits", "()J")) != NULL &&
            (getmsb = env->GetMethodID(clazzuuid, "getMostSignificantBits", "()J")) != NULL &&
            (gettype = env->GetMethodID(clazzuuid, "getType", "()B")) != NULL)
        {
            for(i = 0; i < entries; i++)
            {
                uuid = env->GetObjectArrayElement(uuids, i);
                service_list[i].uuidtype = env->CallByteMethod(uuid, gettype);
                lsbbits = env->CallLongMethod(uuid, getlsb);
                msbbits = env->CallLongMethod(uuid, getmsb);
                set_uuid(&service_list[i].uuidval.uu[0], msbbits, lsbbits);
            }
            qcBluetoothInterface->le_extended_scan(service_list, entries, scan_policy, start?1:0);
        }
    }
    free(service_list);
}

static void btLeLppWriteRssiThresholdNative(JNIEnv *env, jobject object,
                                            jstring address, jbyte min, jbyte max)
{
    bt_bdaddr_t bda;
    if (!qcBluetoothInterface) return;
    jstr2bdaddr(env, &bda, address);
    qcBluetoothInterface->le_lpp_write_rssi_threshold(&bda, min, max);
}

static void btLeLppEnableRssiMonitorNative(JNIEnv* env, jobject object,
                                           jstring address, jboolean enable)
{
    bt_bdaddr_t bda;
    if (!qcBluetoothInterface) return;
    jstr2bdaddr(env, &bda, address);
    qcBluetoothInterface->le_lpp_enable_rssi_monitor(&bda, enable);
}

static void btLeLppReadRssiThresholdNative(JNIEnv* env, jobject object,
                                              jstring address)
{
    bt_bdaddr_t bda;
    if (!qcBluetoothInterface) return;
    jstr2bdaddr(env, &bda, address);
    qcBluetoothInterface->le_lpp_read_rssi_threshold(&bda);
}

static JNINativeMethod qMethods[] = {
    /* name, signature, funcPtr */
    {"classInitNative", "()V", (void *) classInitNative},
    {"initNative", "()Z", (void *) initNative},
    {"cleanupNative", "()V", (void*) cleanupNative},
    {"btLeExtendedScanNative", "([Landroid/bluetooth/BluetoothLEServiceUuid;Z)V", (void*) btLeExtendedScanNative},
    {"btLeLppWriteRssiThresholdNative", "(Ljava/lang/String;BB)V", (void*)btLeLppWriteRssiThresholdNative},
    {"btLeLppEnableRssiMonitorNative", "(Ljava/lang/String;Z)V", (void*)btLeLppEnableRssiMonitorNative},
    {"btLeLppReadRssiThresholdNative", "(Ljava/lang/String;)V", (void*)btLeLppReadRssiThresholdNative},

};

int register_com_android_bluetooth_btservice_QAdapterService(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/bluetooth/btservice/QAdapterService",
                                    qMethods, NELEM(qMethods));
}

}
