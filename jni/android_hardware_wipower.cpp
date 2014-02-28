/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *            notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *            notice, this list of conditions and the following disclaimer in the
 *            documentation and/or other materials provided with the distribution.
 *        * Neither the name of The Linux Foundation nor
 *            the names of its contributors may be used to endorse or promote
 *            products derived from this software without specific prior written
 *            permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.    IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#define LOG_TAG "wipower"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "utils/Log.h"
#include "utils/misc.h"
#include <cutils/properties.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include "android_hardware_wipower.h"
#include "com_android_bluetooth.h"

#define CHECK_CALLBACK_ENV                                                      \
   if (!checkCallbackThread()) {                                                \
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);\
       return;                                                                  \
   }

#define ALOGV ALOGE

namespace android {

static jmethodID method_wipowerstateChangeCallback;
static jmethodID method_wipowerAlertNotify;
static jmethodID method_wipowerDataNotify;
static jmethodID method_wipowerPowerNotify;

static const wipower_interface_t *sWipowerInterface = NULL;
static jobject sCallbacksObj;
static JNIEnv *sCallbackEnv = NULL;

static bool checkCallbackThread() {
    sCallbackEnv = getCallbackEnv();

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (sCallbackEnv != env || sCallbackEnv == NULL) {
        ALOGE("Callback env check fail: env: %p, callback: %p", env, sCallbackEnv);
        return false;
    }
    return true;
}


static void wipower_state_changed_cb(wipower_state_t state) {
    ALOGV("%s: State is: %d", __FUNCTION__, state);
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(sCallbacksObj, method_wipowerstateChangeCallback, (jint) state);
}

static void wipower_alerts_cb(unsigned char alert_data) {
    ALOGV("%s: alert_data is: %d", __FUNCTION__, alert_data);
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(sCallbacksObj, method_wipowerAlertNotify, (jint) alert_data);
}

static void wipower_data_cb(wipower_dyn_data_t *alert_data) {
    ALOGV("%s: wp data is: %x", __FUNCTION__, (unsigned int)alert_data);
    jbyteArray wp_data = NULL;

    CHECK_CALLBACK_ENV
    wp_data =  sCallbackEnv->NewByteArray(sizeof(wipower_dyn_data_t));
    if (wp_data == NULL) {
        ALOGV("%s: alloc failure", __FUNCTION__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(wp_data, 0, sizeof(wipower_dyn_data_t),
                                                (jbyte*)alert_data);


    ALOGV("set value to byte array");

    sCallbackEnv->CallVoidMethod(sCallbacksObj, method_wipowerDataNotify, wp_data);

    sCallbackEnv->DeleteLocalRef(wp_data);
}

static void wipower_power_cb(unsigned char alert_data) {
    ALOGV("%s: alert_data is: %d", __FUNCTION__, alert_data);
    CHECK_CALLBACK_ENV
    sCallbackEnv->CallVoidMethod(sCallbacksObj, method_wipowerPowerNotify, (jint) alert_data);
}

wipower_callbacks_t sWipowerCallbacks = {
    sizeof(sWipowerCallbacks),
    wipower_state_changed_cb,
    wipower_alerts_cb,
    wipower_data_cb,
    wipower_power_cb
};


static void android_wipower_wipowerJNI_classInitNative(JNIEnv* env, jclass clazz) {

    ALOGV("%s:",__FUNCTION__);
    method_wipowerstateChangeCallback = env->GetMethodID(clazz, "stateChangeCallback", "(I)V");

    method_wipowerAlertNotify = env->GetMethodID(clazz, "wipowerAlertNotify",   "(I)V");

    method_wipowerDataNotify = env->GetMethodID(clazz, "wipowerDataNotify",
                                                             "([B)V");

    method_wipowerPowerNotify = env->GetMethodID(clazz, "wipowerPowerNotify",
                                                             "(B)V");
}

static void android_wipower_wipowerJNI_initNative (JNIEnv* env, jobject obj) {
    ALOGE("%s:",__FUNCTION__);

    const bt_interface_t* btInf;

    if ( (btInf = getBluetoothInterface()) == NULL) {
        ALOGE("Bluetooth module is not loaded");
        return;
    }

    //Get WiPower Interface
    sWipowerInterface = (const wipower_interface_t*)btInf->get_profile_interface(WIPOWER_PROFILE_ID);

    ALOGE("%s: Get wipower interface: %x",__FUNCTION__, (unsigned int)sWipowerInterface);
    //Initialize wipower interface
    int ret = sWipowerInterface->init(&sWipowerCallbacks);

    if (ret != 0)
        ALOGE("wipower init failed");

    sCallbacksObj = env->NewGlobalRef(obj);
}

/* native interface */
static jint android_wipower_wipowerJNI_enableNative
        (JNIEnv* env, jobject thiz, jboolean enable)
{

    ALOGD("%s->", __func__);

    if (sWipowerInterface == NULL) {
        ALOGE("No Interface initialized");
        return JNI_FALSE;
    }

    int ret = sWipowerInterface->enable(enable);

    if (ret != 0) {
        ALOGE("wipower enable failed");
        return JNI_FALSE;
    } else {
        ALOGE("wipower enabled successfully");
    }
    return 0;
}

/* native interface */
static jint android_wipower_wipowerJNI_setCurrentLimitNative
        (JNIEnv* env, jobject thiz, jbyte value)
{
    ALOGD("%s->", __func__);

    if (sWipowerInterface == NULL) {
        ALOGE("No Interface initialized");
        return JNI_FALSE;
    }

    int ret = sWipowerInterface->set_current_limit(value);

    if (ret != 0) {
        ALOGE("wipower set current limit failed");
        return JNI_FALSE;
    } else {
        ALOGD("%s:success", __func__);
    }

    return 0;
}

/* native interface */
static jbyte android_wipower_wipowerJNI_getCurrentLimitNative
        (JNIEnv* env, jobject thiz)
{
    ALOGD("%s->", __func__);

    if (sWipowerInterface == NULL) {
        ALOGE("No Interface initialized");
        return JNI_FALSE;
    }

    unsigned char val = sWipowerInterface->get_current_limit();

    ALOGE("%s: %d", __func__, val);

    return val;
}

/* native interface */
static jint android_wipower_wipowerJNI_getStateNative
        (JNIEnv* env, jobject thiz)
{
    ALOGD("%s->", __func__);

    if (sWipowerInterface == NULL) {
        ALOGE("No Interface initialized");
        return JNI_FALSE;
    }

    int val = sWipowerInterface->get_state();

    ALOGE("%s: %d", __func__, val);

    return val;
}

/* native interface */
static jint android_wipower_wipowerJNI_enableAlertNative
        (JNIEnv* env, jobject thiz, jboolean enable)
{
    ALOGD("%s->", __func__);

    if (sWipowerInterface == NULL) {
        ALOGE("No Interface initialized");
        return JNI_FALSE;
    }

    int ret = sWipowerInterface->enable_alerts(enable);

    if (ret != 0) {
        ALOGE("%s: Failure", __func__);
        return JNI_FALSE;
    } else {
        ALOGE("%s: Success", __func__);
    }
    return 0;
}

/* native interface */
static jint android_wipower_wipowerJNI_enableDataNative
        (JNIEnv* env, jobject thiz, jboolean enable)
{

    ALOGD("%s->", __func__);

    if (sWipowerInterface == NULL) {
        ALOGE("No Interface initialized");
        return JNI_FALSE;
    }

    int ret = sWipowerInterface->enable_data_notify(enable);

    if (ret != 0) {
        ALOGE("%s: Failure", __func__);
        return JNI_FALSE;
    } else {
        ALOGE("%s: Success", __func__);
    }

    return 0;
}

/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        { "classInitNative", "()V",
            (void*)android_wipower_wipowerJNI_classInitNative},

        { "initNative", "()V",
            (void*)android_wipower_wipowerJNI_initNative},

        { "enableNative", "(Z)I",
            (void*)android_wipower_wipowerJNI_enableNative},

        { "setCurrentLimitNative", "(B)I",
            (void*)android_wipower_wipowerJNI_setCurrentLimitNative},

        { "getCurrentLimitNative", "()B",
            (void*)android_wipower_wipowerJNI_getCurrentLimitNative},

        { "getStateNative", "()I",
            (void*)android_wipower_wipowerJNI_getStateNative},

        { "enableAlertNative", "(Z)I",
            (void*)android_wipower_wipowerJNI_enableAlertNative},

        { "enableDataNative", "(Z)I",
            (void*)android_wipower_wipowerJNI_enableDataNative},

};
int register_android_hardware_wipower(JNIEnv* env)
{

    ALOGE("%s: >\n", __func__);
    return jniRegisterNativeMethods(env, "com/android/bluetooth/wipower/WipowerService", gMethods, NELEM(gMethods));
}
}
