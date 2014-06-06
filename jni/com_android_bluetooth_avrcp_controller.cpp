/*
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


static btrc_ctrl_callbacks_t sBluetoothAvrcpCallbacks = {
    sizeof(sBluetoothAvrcpCallbacks),
    btavrcp_passthrough_response_callback,
    btavrcp_connection_state_callback
};

static void classInitNative(JNIEnv* env, jclass clazz) {
    method_handlePassthroughRsp =
        env->GetMethodID(clazz, "handlePassthroughRsp", "(II)V");

    method_onConnectionStateChanged =
        env->GetMethodID(clazz, "onConnectionStateChanged", "(Z[B)V");

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

static JNINativeMethod sMethods[] = {
    {"classInitNative", "()V", (void *) classInitNative},
    {"initNative", "()V", (void *) initNative},
    {"cleanupNative", "()V", (void *) cleanupNative},
    {"sendPassThroughCommandNative", "([BII)Z",
     (void *) sendPassThroughCommandNative},
};

int register_com_android_bluetooth_avrcp_controller(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/bluetooth/avrcp/AvrcpControllerService",
                                    sMethods, NELEM(sMethods));
}

}
