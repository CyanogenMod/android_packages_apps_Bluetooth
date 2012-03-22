/*
 * Copyright (C) 2012 Google Inc.
 */

#define LOG_TAG "BluetoothHidServiceJni"

#define LOG_NDEBUG 0

#define CHECK_CALLBACK_ENV                                                      \
   if (!checkCallbackThread()) {                                                \
       LOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);\
       return;                                                                  \
   }

#include "com_android_bluetooth.h"
#include "hardware/bt_hh.h"
#include "utils/Log.h"
#include "android_runtime/AndroidRuntime.h"

#include <string.h>

namespace android {

static jmethodID method_onConnectStateChanged;

static const bthh_interface_t *sBluetoothHidInterface = NULL;
static jobject mCallbacksObj = NULL;
static JNIEnv *sCallbackEnv = NULL;

static bool checkCallbackThread() {
    if (sCallbackEnv == NULL) {
        sCallbackEnv = getCallbackEnv();
    }

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (sCallbackEnv != env || sCallbackEnv == NULL) return false;
    return true;
}

static void connection_state_callback(bt_bdaddr_t *bd_addr, bthh_connection_state_t state) {
    jbyteArray addr;

    CHECK_CALLBACK_ENV
    addr = sCallbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if (!addr) {
        LOGE("Fail to new jbyteArray bd addr for HID channel state");
        checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
        return;
    }
    sCallbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte *) bd_addr);

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onConnectStateChanged, addr, (jint) state);
    checkAndClearExceptionFromCallback(sCallbackEnv, __FUNCTION__);
    sCallbackEnv->DeleteLocalRef(addr);
}

static bthh_callbacks_t sBluetoothHidCallbacks = {
    sizeof(sBluetoothHidCallbacks),
    connection_state_callback,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL
};

// Define native functions

static void classInitNative(JNIEnv* env, jclass clazz) {
    int err;
    const bt_interface_t* btInf;
    bt_status_t status;

    method_onConnectStateChanged = env->GetMethodID(clazz, "onConnectStateChanged",
                                                    "([BI)V");

    if ( (btInf = getBluetoothInterface()) == NULL) {
        LOGE("Bluetooth module is not loaded");
        return;
    }

    if ( (sBluetoothHidInterface = (bthh_interface_t *)
          btInf->get_profile_interface(BT_PROFILE_HIDHOST_ID)) == NULL) {
        LOGE("Failed to get Bluetooth Handsfree Interface");
        return;
    }

    // TODO(BT) do this only once or
    //          Do we need to do this every time the BT reenables?
    if ( (status = sBluetoothHidInterface->init(&sBluetoothHidCallbacks)) != BT_STATUS_SUCCESS) {
        LOGE("Failed to initialize Bluetooth HID, status: %d", status);
        sBluetoothHidInterface = NULL;
        return;
    }

    LOGI("%s: succeeds", __FUNCTION__);
}

static void initializeNativeDataNative(JNIEnv *env, jobject object) {
    // TODO(BT) clean it up when hid service is stopped
    mCallbacksObj = env->NewGlobalRef(object);
}

static jboolean connectHidNative(JNIEnv *env, jobject object, jbyteArray address) {
    bt_status_t status;
    jbyte *addr;
    jboolean ret = JNI_TRUE;
    if (!sBluetoothHidInterface) return JNI_FALSE;

    addr = env->GetByteArrayElements(address, NULL);
    if (!addr) {
        LOGE("Bluetooth device address null");
        return JNI_FALSE;
    }

    if ((status = sBluetoothHidInterface->connect((bt_bdaddr_t *) addr)) !=
         BT_STATUS_SUCCESS) {
        LOGE("Failed HID channel connection, status: %d", status);
        ret = JNI_FALSE;
    }
    env->ReleaseByteArrayElements(address, addr, 0);

    return ret;
}

static jboolean disconnectHidNative(JNIEnv *env, jobject object, jbyteArray address) {
    bt_status_t status;
    jbyte *addr;
    jboolean ret = JNI_TRUE;
    if (!sBluetoothHidInterface) return JNI_FALSE;

    addr = env->GetByteArrayElements(address, NULL);
    if (!addr) {
        LOGE("Bluetooth device address null");
        return JNI_FALSE;
    }

    if ( (status = sBluetoothHidInterface->disconnect((bt_bdaddr_t *) addr)) !=
         BT_STATUS_SUCCESS) {
        LOGE("Failed disconnect hid channel, status: %d", status);
        ret = JNI_FALSE;
    }
    env->ReleaseByteArrayElements(address, addr, 0);

    return ret;
}

static JNINativeMethod sMethods[] = {
    {"classInitNative", "()V", (void *) classInitNative},
    {"initializeNativeDataNative", "()V", (void *) initializeNativeDataNative},
    {"connectHidNative", "([B)Z", (void *) connectHidNative},
    {"disconnectHidNative", "([B)Z", (void *) disconnectHidNative},
    // TBD
};

int register_com_android_bluetooth_hid(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/bluetooth/hid/HidService",
                                    sMethods, NELEM(sMethods));
}

}
