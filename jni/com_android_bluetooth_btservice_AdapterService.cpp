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

#define LOG_TAG "BluetoothServiceJni"
#include "com_android_bluetooth.h"
#include "android_hardware_wipower.h"
#include "hardware/bt_sock.h"
#include "hardware/bt_mce.h"
#include "utils/Log.h"
#include "utils/misc.h"
#include "cutils/properties.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"

#include <string.h>
#include <pthread.h>

#include <sys/stat.h>
#include <fcntl.h>

namespace android {

#define ADDITIONAL_NREFS 50
static jmethodID method_stateChangeCallback;
static jmethodID method_adapterPropertyChangedCallback;
static jmethodID method_devicePropertyChangedCallback;
static jmethodID method_deviceFoundCallback;
static jmethodID method_pinRequestCallback;
static jmethodID method_sspRequestCallback;
static jmethodID method_bondStateChangeCallback;
static jmethodID method_aclStateChangeCallback;
static jmethodID method_discoveryStateChangeCallback;
static jmethodID method_deviceMasInstancesFoundCallback;
static jmethodID method_wakeStateChangeCallback;

static const bt_interface_t *sBluetoothInterface = NULL;
static const btsock_interface_t *sBluetoothSocketInterface = NULL;
static const btmce_interface_t *sBluetoothMceInterface = NULL;
static JNIEnv *callbackEnv = NULL;

static jobject sJniCallbacksObj = NULL;
static jfieldID sJniCallbacksField;


const bt_interface_t* getBluetoothInterface() {
    return sBluetoothInterface;
}

JNIEnv* getCallbackEnv() {
    return callbackEnv;
}

void checkAndClearExceptionFromCallback(JNIEnv* env,
                                               const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

static bool checkCallbackThread() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (callbackEnv != env || callbackEnv == NULL) {
        ALOGE("Callback env check fail: env: %p, callback: %p", env, callbackEnv);
        return false;
    }
    return true;
}

static void adapter_state_change_callback(bt_state_t status) {
    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }
    ALOGV("%s: Status is: %d", __FUNCTION__, status);
    if(sJniCallbacksObj) {
       callbackEnv->CallVoidMethod(sJniCallbacksObj, method_stateChangeCallback, (jint)status);
    } else {
       ALOGE("JNI ERROR : JNI reference already cleaned : adapter_state_change_callback", __FUNCTION__);
    }
    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);
}

static int get_properties(int num_properties, bt_property_t *properties, jintArray *types,
                        jobjectArray *props) {
    jbyteArray propVal;
    for (int i = 0; i < num_properties; i++) {
        propVal = callbackEnv->NewByteArray(properties[i].len);
        if (propVal == NULL) goto Fail;

        callbackEnv->SetByteArrayRegion(propVal, 0, properties[i].len,
                                             (jbyte*)properties[i].val);
        callbackEnv->SetObjectArrayElement(*props, i, propVal);
        // Delete reference to propVal
        callbackEnv->DeleteLocalRef(propVal);
        callbackEnv->SetIntArrayRegion(*types, i, 1, (jint *)&properties[i].type);
    }
    return 0;
Fail:
    if (propVal) callbackEnv->DeleteLocalRef(propVal);
    ALOGE("Error while allocation of array in %s", __FUNCTION__);
    return -1;
}

static void adapter_properties_callback(bt_status_t status, int num_properties,
                                        bt_property_t *properties) {
    jobjectArray props;
    jintArray types;
    jbyteArray val;
    jclass mclass;

    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }

    ALOGV("%s: Status is: %d, Properties: %d", __FUNCTION__, status, num_properties);

    if (status != BT_STATUS_SUCCESS) {
        ALOGE("%s: Status %d is incorrect", __FUNCTION__, status);
        return;
    }

    val = (jbyteArray) callbackEnv->NewByteArray(num_properties);
    if (val == NULL) {
        ALOGE("%s: Error allocating byteArray", __FUNCTION__);
        return;
    }

    mclass = callbackEnv->GetObjectClass(val);

    /* (BT) Initialize the jobjectArray and jintArray here itself and send the
     initialized array pointers alone to get_properties */

    props = callbackEnv->NewObjectArray(num_properties, mclass,
                                             NULL);
    if (props == NULL) {
        ALOGE("%s: Error allocating object Array for properties", __FUNCTION__);
        return;
    }

    types = (jintArray)callbackEnv->NewIntArray(num_properties);

    if (types == NULL) {
        ALOGE("%s: Error allocating int Array for values", __FUNCTION__);
        return;
    }
    // Delete the reference to val and mclass
    callbackEnv->DeleteLocalRef(mclass);
    callbackEnv->DeleteLocalRef(val);

    if (get_properties(num_properties, properties, &types, &props) < 0) {
        if (props) callbackEnv->DeleteLocalRef(props);
        if (types) callbackEnv->DeleteLocalRef(types);
        return;
    }

    callbackEnv->CallVoidMethod(sJniCallbacksObj, method_adapterPropertyChangedCallback, types,
                                props);
    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);
    callbackEnv->DeleteLocalRef(props);
    callbackEnv->DeleteLocalRef(types);
    return;

}

static void remote_device_properties_callback(bt_status_t status, bt_bdaddr_t *bd_addr,
                                              int num_properties, bt_property_t *properties) {
    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }

    ALOGV("%s: Status is: %d, Properties: %d", __FUNCTION__, status, num_properties);

    if (status != BT_STATUS_SUCCESS) {
        ALOGE("%s: Status %d is incorrect", __FUNCTION__, status);
        return;
    }

    callbackEnv->PushLocalFrame(ADDITIONAL_NREFS);

    jobjectArray props;
    jbyteArray addr;
    jintArray types;
    jbyteArray val;
    jclass mclass;

    val = (jbyteArray) callbackEnv->NewByteArray(num_properties);
    if (val == NULL) {
        ALOGE("%s: Error allocating byteArray", __FUNCTION__);
        return;
    }

    mclass = callbackEnv->GetObjectClass(val);

    /* Initialize the jobjectArray and jintArray here itself and send the
     initialized array pointers alone to get_properties */

    props = callbackEnv->NewObjectArray(num_properties, mclass,
                                             NULL);
    if (props == NULL) {
        ALOGE("%s: Error allocating object Array for properties", __FUNCTION__);
        return;
    }

    types = (jintArray)callbackEnv->NewIntArray(num_properties);

    if (types == NULL) {
        ALOGE("%s: Error allocating int Array for values", __FUNCTION__);
        return;
    }
    // Delete the reference to val and mclass
    callbackEnv->DeleteLocalRef(mclass);
    callbackEnv->DeleteLocalRef(val);

    addr = callbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if (addr == NULL) goto Fail;
    if (addr) callbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*)bd_addr);

    if (get_properties(num_properties, properties, &types, &props) < 0) {
        if (props) callbackEnv->DeleteLocalRef(props);
        if (types) callbackEnv->DeleteLocalRef(types);
        callbackEnv->PopLocalFrame(NULL);
        return;
    }

    callbackEnv->CallVoidMethod(sJniCallbacksObj, method_devicePropertyChangedCallback, addr,
                                types, props);
    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);
    callbackEnv->DeleteLocalRef(props);
    callbackEnv->DeleteLocalRef(types);
    callbackEnv->DeleteLocalRef(addr);
    callbackEnv->PopLocalFrame(NULL);
    return;

Fail:
    ALOGE("Error while allocation byte array in %s", __FUNCTION__);
}


static void device_found_callback(int num_properties, bt_property_t *properties) {
    jbyteArray addr = NULL;
    int addr_index;

    for (int i = 0; i < num_properties; i++) {
        if (properties[i].type == BT_PROPERTY_BDADDR) {
            addr = callbackEnv->NewByteArray(properties[i].len);
            if (addr) {
                callbackEnv->SetByteArrayRegion(addr, 0, properties[i].len,
                                                (jbyte*)properties[i].val);
                addr_index = i;
            } else {
                ALOGE("Address is NULL (unable to allocate) in %s", __FUNCTION__);
                return;
            }
        }
    }
    if (addr == NULL) {
        ALOGE("Address is NULL in %s", __FUNCTION__);
        return;
    }

    ALOGV("%s: Properties: %d, Address: %s", __FUNCTION__, num_properties,
        (const char *)properties[addr_index].val);

    remote_device_properties_callback(BT_STATUS_SUCCESS, (bt_bdaddr_t *)properties[addr_index].val,
                                      num_properties, properties);

    callbackEnv->CallVoidMethod(sJniCallbacksObj, method_deviceFoundCallback, addr);
    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);
    callbackEnv->DeleteLocalRef(addr);
}

static void bond_state_changed_callback(bt_status_t status, bt_bdaddr_t *bd_addr,
                                        bt_bond_state_t state) {
    jbyteArray addr;
    int i;
    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }
    if (!bd_addr) {
        ALOGE("Address is null in %s", __FUNCTION__);
        return;
    }
    addr = callbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if (addr == NULL) {
       ALOGE("Address allocation failed in %s", __FUNCTION__);
       return;
    }
    callbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte *)bd_addr);

    callbackEnv->CallVoidMethod(sJniCallbacksObj, method_bondStateChangeCallback, (jint) status,
                                addr, (jint)state);
    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);
    callbackEnv->DeleteLocalRef(addr);
}

static void acl_state_changed_callback(bt_status_t status, bt_bdaddr_t *bd_addr,
                                       bt_acl_state_t state)
{
    jbyteArray addr;
    int i;
    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }
    if (!bd_addr) {
        ALOGE("Address is null in %s", __FUNCTION__);
        return;
    }
    addr = callbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if (addr == NULL) {
       ALOGE("Address allocation failed in %s", __FUNCTION__);
       return;
    }
    callbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte *)bd_addr);

    callbackEnv->CallVoidMethod(sJniCallbacksObj, method_aclStateChangeCallback, (jint) status,
                                addr, (jint)state);
    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);
    callbackEnv->DeleteLocalRef(addr);
}

static void discovery_state_changed_callback(bt_discovery_state_t state) {
    jbyteArray addr;
    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }

    ALOGV("%s: DiscoveryState:%d ", __FUNCTION__, state);

    callbackEnv->CallVoidMethod(sJniCallbacksObj, method_discoveryStateChangeCallback,
                                (jint)state);

    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);
}
static void wake_state_changed_callback(bt_state_t state) {

    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }

    ALOGV("%s: WakeState:%d ", __FUNCTION__, state);

    callbackEnv->CallVoidMethod(sJniCallbacksObj, method_wakeStateChangeCallback,
                                (jint)state);

    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);
}
static void pin_request_callback(bt_bdaddr_t *bd_addr, bt_bdname_t *bdname, uint32_t cod, uint8_t secure) {
    jbyteArray addr, devname;
    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }
    if (!bd_addr) {
        ALOGE("Address is null in %s", __FUNCTION__);
        return;
    }

    addr = callbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if (addr == NULL) goto Fail;
    callbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*)bd_addr);

    devname = callbackEnv->NewByteArray(sizeof(bt_bdname_t));
    if (devname == NULL) goto Fail;

    callbackEnv->SetByteArrayRegion(devname, 0, sizeof(bt_bdname_t), (jbyte*)bdname);

    callbackEnv->CallVoidMethod(sJniCallbacksObj, method_pinRequestCallback, addr, devname, cod, secure);

    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);
    callbackEnv->DeleteLocalRef(addr);
    callbackEnv->DeleteLocalRef(devname);
    return;

Fail:
    if (addr) callbackEnv->DeleteLocalRef(addr);
    if (devname) callbackEnv->DeleteLocalRef(devname);
    ALOGE("Error while allocating in: %s", __FUNCTION__);
}

static void ssp_request_callback(bt_bdaddr_t *bd_addr, bt_bdname_t *bdname, uint32_t cod,
                                 bt_ssp_variant_t pairing_variant, uint32_t pass_key) {
    jbyteArray addr, devname;
    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }
    if (!bd_addr) {
        ALOGE("Address is null in %s", __FUNCTION__);
        return;
    }

    addr = callbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if (addr == NULL) goto Fail;
    callbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte *)bd_addr);

    devname = callbackEnv->NewByteArray(sizeof(bt_bdname_t));
    if (devname == NULL) goto Fail;
    callbackEnv->SetByteArrayRegion(devname, 0, sizeof(bt_bdname_t), (jbyte*)bdname);

    callbackEnv->CallVoidMethod(sJniCallbacksObj, method_sspRequestCallback, addr, devname, cod,
                                (jint) pairing_variant, pass_key);

    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);
    callbackEnv->DeleteLocalRef(addr);
    callbackEnv->DeleteLocalRef(devname);
    return;

Fail:
    if (addr) callbackEnv->DeleteLocalRef(addr);
    if (devname) callbackEnv->DeleteLocalRef(devname);

    ALOGE("Error while allocating in: %s", __FUNCTION__);
}

static void callback_thread_event(bt_cb_thread_evt event) {
    JavaVM* vm = AndroidRuntime::getJavaVM();
    if (event  == ASSOCIATE_JVM) {
        JavaVMAttachArgs args;
        char name[] = "BT Service Callback Thread";
        args.version = JNI_VERSION_1_6;
        args.name = name;
        args.group = NULL;
        vm->AttachCurrentThread(&callbackEnv, &args);
        ALOGV("Callback thread attached: %p", callbackEnv);
    } else if (event == DISASSOCIATE_JVM) {
        if (!checkCallbackThread()) {
            ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
            return;
        }
        vm->DetachCurrentThread();
    }
}

static void dut_mode_recv_callback (uint16_t opcode, uint8_t *buf, uint8_t len) {

}
static void le_test_mode_recv_callback (bt_status_t status, uint16_t packet_count) {

    ALOGV("%s: status:%d packet_count:%d ", __FUNCTION__, status, packet_count);
}
bt_callbacks_t sBluetoothCallbacks = {
    sizeof(sBluetoothCallbacks),
    adapter_state_change_callback,
    adapter_properties_callback,
    remote_device_properties_callback,
    device_found_callback,
    discovery_state_changed_callback,
    wake_state_changed_callback,
    pin_request_callback,
    ssp_request_callback,
    bond_state_changed_callback,
    acl_state_changed_callback,
    callback_thread_event,
    dut_mode_recv_callback,
    NULL,
    le_test_mode_recv_callback,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL
};

static void remote_mas_instances_callback(bt_status_t status, bt_bdaddr_t *bd_addr,
                                          int num_instances, btmce_mas_instance_t *instances)
{
    if (!checkCallbackThread()) {
       ALOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }

    ALOGV("%s: Status is: %d, Instances: %d", __FUNCTION__, status, num_instances);

    if (status != BT_STATUS_SUCCESS) {
        ALOGE("%s: Status %d is incorrect", __FUNCTION__, status);
        return;
    }

    callbackEnv->PushLocalFrame(ADDITIONAL_NREFS);

    jbyteArray addr = NULL;
    jobjectArray a_name = NULL;
    jintArray a_scn = NULL;
    jintArray a_masid = NULL;
    jintArray a_msgtype = NULL;
    jclass mclass;

    mclass = callbackEnv->FindClass("java/lang/String");

    addr = callbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if (addr == NULL) goto clean;

    callbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*)bd_addr);

    a_name = callbackEnv->NewObjectArray(num_instances, mclass, NULL);
    if (a_name == NULL) goto clean;

    a_scn = callbackEnv->NewIntArray(num_instances);
    if (a_scn == NULL) goto clean;

    a_masid = callbackEnv->NewIntArray(num_instances);
    if (a_masid == NULL) goto clean;

    a_msgtype = callbackEnv->NewIntArray(num_instances);
    if (a_msgtype == NULL) goto clean;

    for (int i = 0; i < num_instances; i++) {
        jstring name = callbackEnv->NewStringUTF(instances[i].p_name);

        callbackEnv->SetObjectArrayElement(a_name, i, name);
        callbackEnv->SetIntArrayRegion(a_scn, i, 1, &instances[i].scn);
        callbackEnv->SetIntArrayRegion(a_masid, i, 1, &instances[i].id);
        callbackEnv->SetIntArrayRegion(a_msgtype, i, 1, &instances[i].msg_types);

        callbackEnv->DeleteLocalRef(name);
    }

    callbackEnv->CallVoidMethod(sJniCallbacksObj, method_deviceMasInstancesFoundCallback,
            (jint) status, addr, a_name, a_scn, a_masid, a_msgtype);
    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);

clean:
    if (addr != NULL) callbackEnv->DeleteLocalRef(addr);
    if (a_name != NULL) callbackEnv->DeleteLocalRef(a_name);
    if (a_scn != NULL) callbackEnv->DeleteLocalRef(a_scn);
    if (a_masid != NULL) callbackEnv->DeleteLocalRef(a_masid);
    if (a_msgtype != NULL) callbackEnv->DeleteLocalRef(a_msgtype);
    callbackEnv->PopLocalFrame(NULL);
}

static btmce_callbacks_t sBluetoothMceCallbacks = {
    sizeof(sBluetoothMceCallbacks),
    remote_mas_instances_callback,
};

static void classInitNative(JNIEnv* env, jclass clazz) {
    int err;
    hw_module_t* module;

    jclass jniCallbackClass =
        env->FindClass("com/android/bluetooth/btservice/JniCallbacks");
    sJniCallbacksField = env->GetFieldID(clazz, "mJniCallbacks",
        "Lcom/android/bluetooth/btservice/JniCallbacks;");

    method_stateChangeCallback = env->GetMethodID(jniCallbackClass, "stateChangeCallback", "(I)V");

    method_adapterPropertyChangedCallback = env->GetMethodID(jniCallbackClass,
                                                             "adapterPropertyChangedCallback",
                                                             "([I[[B)V");
    method_discoveryStateChangeCallback = env->GetMethodID(jniCallbackClass,
                                                           "discoveryStateChangeCallback", "(I)V");
    method_wakeStateChangeCallback = env->GetMethodID(jniCallbackClass,
                                                           "wakeStateChangeCallback", "(I)V");
    method_devicePropertyChangedCallback = env->GetMethodID(jniCallbackClass,
                                                            "devicePropertyChangedCallback",
                                                            "([B[I[[B)V");
    method_deviceFoundCallback = env->GetMethodID(jniCallbackClass, "deviceFoundCallback", "([B)V");
    method_pinRequestCallback = env->GetMethodID(jniCallbackClass, "pinRequestCallback",
                                                 "([B[BIZ)V");
    method_sspRequestCallback = env->GetMethodID(jniCallbackClass, "sspRequestCallback",
                                                 "([B[BIII)V");

    method_bondStateChangeCallback = env->GetMethodID(jniCallbackClass,
                                                     "bondStateChangeCallback", "(I[BI)V");

    method_aclStateChangeCallback = env->GetMethodID(jniCallbackClass,
                                                    "aclStateChangeCallback", "(I[BI)V");

    method_deviceMasInstancesFoundCallback = env->GetMethodID(jniCallbackClass,
                                                    "deviceMasInstancesFoundCallback",
                                                    "(I[B[Ljava/lang/String;[I[I[I)V");

    char value[PROPERTY_VALUE_MAX];
    property_get("bluetooth.mock_stack", value, "");

    const char *id = (strcmp(value, "1")? BT_STACK_MODULE_ID : BT_STACK_TEST_MODULE_ID);

    err = hw_get_module(id, (hw_module_t const**)&module);

    if (err == 0) {
        hw_device_t* abstraction;
        err = module->methods->open(module, id, &abstraction);
        if (err == 0) {
            bluetooth_module_t* btStack = (bluetooth_module_t *)abstraction;
            sBluetoothInterface = btStack->get_bluetooth_interface();
        } else {
           ALOGE("Error while opening Bluetooth library");
        }
    } else {
        ALOGE("No Bluetooth Library found");
    }
    ALOGI("%s: succeeds", __FUNCTION__);
}

static bool initNative(JNIEnv* env, jobject obj) {
    ALOGV("%s:",__FUNCTION__);

    sJniCallbacksObj = env->NewGlobalRef(env->GetObjectField(obj, sJniCallbacksField));

    if (sBluetoothInterface) {
        int ret = sBluetoothInterface->init(&sBluetoothCallbacks);
        if (ret != BT_STATUS_SUCCESS) {
            ALOGE("Error while setting the callbacks \n");
            sBluetoothInterface = NULL;
            return JNI_FALSE;
        }
        if ( (sBluetoothSocketInterface = (btsock_interface_t *)
                  sBluetoothInterface->get_profile_interface(BT_PROFILE_SOCKETS_ID)) == NULL) {
                ALOGE("Error getting socket interface");
        }

        if ( (sBluetoothMceInterface = (btmce_interface_t *)
                  sBluetoothInterface->get_profile_interface(BT_PROFILE_MAP_CLIENT_ID)) == NULL) {
                ALOGE("Error getting mapclient interface");
        } else {
            if ( (sBluetoothMceInterface->init(&sBluetoothMceCallbacks)) != BT_STATUS_SUCCESS) {
                ALOGE("Failed to initialize Bluetooth MCE");
                sBluetoothMceInterface = NULL;
            }
        }

        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static bool cleanupNative(JNIEnv *env, jobject obj) {
    ALOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    sBluetoothInterface->cleanup();
    ALOGI("%s: return from cleanup",__FUNCTION__);

    env->DeleteGlobalRef(sJniCallbacksObj);
    sJniCallbacksObj = NULL;
    return JNI_TRUE;
}

static jboolean enableNative(JNIEnv* env, jobject obj) {
    ALOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    int ret = sBluetoothInterface->enable();
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
    return result;
}

static jboolean disableNative(JNIEnv* env, jobject obj) {
    ALOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    int ret = sBluetoothInterface->disable();
    /* Retrun JNI_FALSE only when BTIF explicitly reports
       BT_STATUS_FAIL. It is fine for the BT_STATUS_NOT_READY
       case which indicates that stack had not been enabled.
    */
    result = (ret == BT_STATUS_FAIL) ? JNI_FALSE : JNI_TRUE;
    return result;
}

static jboolean startDiscoveryNative(JNIEnv* env, jobject obj) {
    ALOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    int ret = sBluetoothInterface->start_discovery();
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
    return result;
}

static jboolean cancelDiscoveryNative(JNIEnv* env, jobject obj) {
    ALOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    int ret = sBluetoothInterface->cancel_discovery();
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
    return result;
}

static jboolean createBondNative(JNIEnv* env, jobject obj, jbyteArray address) {
    ALOGV("%s:",__FUNCTION__);

    jbyte *addr;
    jboolean result = JNI_FALSE;

    if (!sBluetoothInterface) return result;

    addr = env->GetByteArrayElements(address, NULL);
    if (addr == NULL) {
        jniThrowIOException(env, EINVAL);
        return result;
    }

    int ret = sBluetoothInterface->create_bond((bt_bdaddr_t *)addr);
    env->ReleaseByteArrayElements(address, addr, 0);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean removeBondNative(JNIEnv* env, jobject obj, jbyteArray address) {
    ALOGV("%s:",__FUNCTION__);

    jbyte *addr;
    jboolean result;
    if (!sBluetoothInterface) return JNI_FALSE;

    addr = env->GetByteArrayElements(address, NULL);
    if (addr == NULL) {
        jniThrowIOException(env, EINVAL);
        return JNI_FALSE;
    }

    int ret = sBluetoothInterface->remove_bond((bt_bdaddr_t *)addr);
    env->ReleaseByteArrayElements(address, addr, 0);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean cancelBondNative(JNIEnv* env, jobject obj, jbyteArray address) {
    ALOGV("%s:",__FUNCTION__);

    jbyte *addr;
    jboolean result;
    if (!sBluetoothInterface) return JNI_FALSE;

    addr = env->GetByteArrayElements(address, NULL);
    if (addr == NULL) {
        jniThrowIOException(env, EINVAL);
        return JNI_FALSE;
    }

    int ret = sBluetoothInterface->cancel_bond((bt_bdaddr_t *)addr);
    env->ReleaseByteArrayElements(address, addr, 0);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean pinReplyNative(JNIEnv *env, jobject obj, jbyteArray address, jboolean accept,
                               jint len, jbyteArray pinArray) {
    ALOGV("%s:",__FUNCTION__);

    jbyte *addr, *pinPtr = NULL;
    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    addr = env->GetByteArrayElements(address, NULL);
    if (addr == NULL) {
        jniThrowIOException(env, EINVAL);
        return result;
    }

    if (accept) {
        pinPtr = env->GetByteArrayElements(pinArray, NULL);
        if (pinPtr == NULL) {
           jniThrowIOException(env, EINVAL);
           env->ReleaseByteArrayElements(address, addr, 0);
           return result;
        }
    }

    int ret = sBluetoothInterface->pin_reply((bt_bdaddr_t*)addr, accept, len,
                                              (bt_pin_code_t *) pinPtr);
    env->ReleaseByteArrayElements(address, addr, 0);
    env->ReleaseByteArrayElements(pinArray, pinPtr, 0);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean sspReplyNative(JNIEnv *env, jobject obj, jbyteArray address,
                               jint type, jboolean accept, jint passkey) {
    ALOGV("%s:",__FUNCTION__);

    jbyte *addr;
    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    addr = env->GetByteArrayElements(address, NULL);
    if (addr == NULL) {
        jniThrowIOException(env, EINVAL);
        return result;
    }

    int ret = sBluetoothInterface->ssp_reply((bt_bdaddr_t *)addr,
         (bt_ssp_variant_t) type, accept, passkey);
    env->ReleaseByteArrayElements(address, addr, 0);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean setAdapterPropertyNative(JNIEnv *env, jobject obj, jint type, jbyteArray value) {
    ALOGV("%s:",__FUNCTION__);

    jbyte *val;
    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    val = env->GetByteArrayElements(value, NULL);
    bt_property_t prop;
    prop.type = (bt_property_type_t) type;
    prop.len = env->GetArrayLength(value);
    prop.val = val;

    int ret = sBluetoothInterface->set_adapter_property(&prop);
    env->ReleaseByteArrayElements(value, val, 0);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean getAdapterPropertiesNative(JNIEnv *env, jobject obj) {
    ALOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    int ret = sBluetoothInterface->get_adapter_properties();
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean getAdapterPropertyNative(JNIEnv *env, jobject obj, jint type) {
    ALOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    int ret = sBluetoothInterface->get_adapter_property((bt_property_type_t) type);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean getDevicePropertyNative(JNIEnv *env, jobject obj, jbyteArray address, jint type) {
    ALOGV("%s:",__FUNCTION__);

    jbyte *addr = NULL;
    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    addr = env->GetByteArrayElements(address, NULL);
    if (addr == NULL) {
        jniThrowIOException(env, EINVAL);
        return result;
    }

    int ret = sBluetoothInterface->get_remote_device_property((bt_bdaddr_t *)addr,
                                                              (bt_property_type_t) type);
    env->ReleaseByteArrayElements(address, addr, 0);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean setDevicePropertyNative(JNIEnv *env, jobject obj, jbyteArray address,
                                        jint type, jbyteArray value) {
    ALOGV("%s:",__FUNCTION__);

    jbyte *val, *addr;
    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    val = env->GetByteArrayElements(value, NULL);
    if (val == NULL) {
        jniThrowIOException(env, EINVAL);
        return result;
    }

    addr = env->GetByteArrayElements(address, NULL);
    if (addr == NULL) {
        env->ReleaseByteArrayElements(value, val, 0);
        jniThrowIOException(env, EINVAL);
        return result;
    }


    bt_property_t prop;
    prop.type = (bt_property_type_t) type;
    prop.len = env->GetArrayLength(value);
    prop.val = val;

    int ret = sBluetoothInterface->set_remote_device_property((bt_bdaddr_t *)addr, &prop);
    env->ReleaseByteArrayElements(value, val, 0);
    env->ReleaseByteArrayElements(address, addr, 0);

    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static int getSocketOptNative(JNIEnv *env, jobject obj, jint type, jint channel, jint optionName,
                                        jbyteArray optionVal) {
    ALOGV("%s:",__FUNCTION__);

    jbyte *option_val = NULL;
    int option_len;
    bt_status_t status;

    if (!sBluetoothSocketInterface) return -1;

    option_val = env->GetByteArrayElements(optionVal, NULL);
    if (option_val == NULL) {
        ALOGE("getSocketOptNative :jniThrowIOException ");
        jniThrowIOException(env, EINVAL);
        return -1;
    }

    if ( (status = sBluetoothSocketInterface->get_sock_opt((btsock_type_t)type, channel,
         (btsock_option_type_t) optionName, (void *) option_val, &option_len)) !=
                                                           BT_STATUS_SUCCESS) {
        ALOGE("get_sock_opt failed: %d", status);
        goto Fail;
    }
    env->ReleaseByteArrayElements(optionVal, option_val, 0);

    return option_len;
Fail:
    env->ReleaseByteArrayElements(optionVal, option_val, 0);
    return -1;
}

static int setSocketOptNative(JNIEnv *env, jobject obj, jint type, jint channel, jint optionName,
                                        jbyteArray optionVal, jint optionLen) {
    ALOGV("%s:",__FUNCTION__);

    jbyte *option_val = NULL;
    bt_status_t status;

    if (!sBluetoothSocketInterface) return -1;

    option_val = env->GetByteArrayElements(optionVal, NULL);
    if (option_val == NULL) {
        ALOGE("setSocketOptNative:jniThrowIOException ");
        jniThrowIOException(env, EINVAL);
        return -1;
    }

    if ( (status = sBluetoothSocketInterface->set_sock_opt((btsock_type_t)type, channel,
         (btsock_option_type_t) optionName, (void *) option_val, optionLen)) !=
                                                         BT_STATUS_SUCCESS) {
        ALOGE("set_sock_opt failed: %d", status);
        goto Fail;
    }
    env->ReleaseByteArrayElements(optionVal, option_val, 0);

    return 0;
Fail:
    env->ReleaseByteArrayElements(optionVal, option_val, 0);
    return -1;
}

static jboolean getRemoteServicesNative(JNIEnv *env, jobject obj, jbyteArray address) {
    ALOGV("%s:",__FUNCTION__);

    jbyte *addr = NULL;
    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    addr = env->GetByteArrayElements(address, NULL);
    if (addr == NULL) {
        jniThrowIOException(env, EINVAL);
        return result;
    }

    int ret = sBluetoothInterface->get_remote_services((bt_bdaddr_t *)addr);
    env->ReleaseByteArrayElements(address, addr, 0);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
    return result;
}

static jboolean getRemoteMasInstancesNative(JNIEnv *env, jobject obj, jbyteArray address) {
    ALOGV("%s:",__FUNCTION__);

    jbyte *addr = NULL;
    jboolean result = JNI_FALSE;
    if (!sBluetoothMceInterface) return result;

    addr = env->GetByteArrayElements(address, NULL);
    if (addr == NULL) {
        jniThrowIOException(env, EINVAL);
        return result;
    }

    int ret = sBluetoothMceInterface->get_remote_mas_instances((bt_bdaddr_t *)addr);
    env->ReleaseByteArrayElements(address, addr, NULL);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
    return result;
}

static int connectSocketNative(JNIEnv *env, jobject object, jbyteArray address, jint type,
                                   jbyteArray uuidObj, jint channel, jint flag) {
    jbyte *addr = NULL, *uuid = NULL;
    int socket_fd;
    bt_status_t status;

    if (!sBluetoothSocketInterface) return -1;

    addr = env->GetByteArrayElements(address, NULL);
    if (!addr) {
        ALOGE("failed to get Bluetooth device address");
        goto Fail;
    }

    uuid = env->GetByteArrayElements(uuidObj, NULL);
    if (!uuid) {
        ALOGE("failed to get uuid");
        goto Fail;
    }

    if ( (status = sBluetoothSocketInterface->connect((bt_bdaddr_t *) addr, (btsock_type_t) type,
                       (const uint8_t*) uuid, channel, &socket_fd, flag)) != BT_STATUS_SUCCESS) {
        ALOGE("Socket connection failed: %d", status);
        goto Fail;
    }


    if (socket_fd < 0) {
        ALOGE("Fail to creat file descriptor on socket fd");
        goto Fail;
    }
    env->ReleaseByteArrayElements(address, addr, 0);
    env->ReleaseByteArrayElements(uuidObj, uuid, 0);
    return socket_fd;

Fail:
    if (addr) env->ReleaseByteArrayElements(address, addr, 0);
    if (uuid) env->ReleaseByteArrayElements(uuidObj, uuid, 0);

    return -1;
}

static int createSocketChannelNative(JNIEnv *env, jobject object, jint type,
                                     jstring name_str, jbyteArray uuidObj, jint channel, jint flag) {
    const char *service_name;
    jbyte *uuid = NULL;
    int socket_fd;
    bt_status_t status;

    if (!sBluetoothSocketInterface) return -1;

    service_name = env->GetStringUTFChars(name_str, NULL);

    uuid = env->GetByteArrayElements(uuidObj, NULL);
    if (!uuid) {
        ALOGE("failed to get uuid");
        goto Fail;
    }
    ALOGE("SOCK FLAG = %x ***********************",flag);
    if ( (status = sBluetoothSocketInterface->listen((btsock_type_t) type, service_name,
                       (const uint8_t*) uuid, channel, &socket_fd, flag)) != BT_STATUS_SUCCESS) {
        ALOGE("Socket listen failed: %d", status);
        goto Fail;
    }

    if (socket_fd < 0) {
        ALOGE("Fail to creat file descriptor on socket fd");
        goto Fail;
    }
    if (service_name) env->ReleaseStringUTFChars(name_str, service_name);
    if (uuid) env->ReleaseByteArrayElements(uuidObj, uuid, 0);
    return socket_fd;

Fail:
    if (service_name) env->ReleaseStringUTFChars(name_str, service_name);
    if (uuid) env->ReleaseByteArrayElements(uuidObj, uuid, 0);

    return -1;
}

static jboolean configHciSnoopLogNative(JNIEnv* env, jobject obj, jboolean enable) {
    ALOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;

    if (!sBluetoothInterface) return result;

    int ret = sBluetoothInterface->config_hci_snoop_log(enable);

    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static JNINativeMethod sMethods[] = {
    /* name, signature, funcPtr */
    {"classInitNative", "()V", (void *) classInitNative},
    {"initNative", "()Z", (void *) initNative},
    {"cleanupNative", "()V", (void*) cleanupNative},
    {"enableNative", "()Z",  (void*) enableNative},
    {"disableNative", "()Z",  (void*) disableNative},
    {"setAdapterPropertyNative", "(I[B)Z", (void*) setAdapterPropertyNative},
    {"getAdapterPropertiesNative", "()Z", (void*) getAdapterPropertiesNative},
    {"getAdapterPropertyNative", "(I)Z", (void*) getAdapterPropertyNative},
    {"getDevicePropertyNative", "([BI)Z", (void*) getDevicePropertyNative},
    {"setDevicePropertyNative", "([BI[B)Z", (void*) setDevicePropertyNative},
    {"startDiscoveryNative", "()Z", (void*) startDiscoveryNative},
    {"cancelDiscoveryNative", "()Z", (void*) cancelDiscoveryNative},
    {"createBondNative", "([B)Z", (void*) createBondNative},
    {"removeBondNative", "([B)Z", (void*) removeBondNative},
    {"cancelBondNative", "([B)Z", (void*) cancelBondNative},
    {"pinReplyNative", "([BZI[B)Z", (void*) pinReplyNative},
    {"sspReplyNative", "([BIZI)Z", (void*) sspReplyNative},
    {"getRemoteServicesNative", "([B)Z", (void*) getRemoteServicesNative},
    {"getRemoteMasInstancesNative", "([B)Z", (void*) getRemoteMasInstancesNative},
    {"connectSocketNative", "([BI[BII)I", (void*) connectSocketNative},
    {"createSocketChannelNative", "(ILjava/lang/String;[BII)I",
     (void*) createSocketChannelNative},
    {"configHciSnoopLogNative", "(Z)Z", (void*) configHciSnoopLogNative},
    {"getSocketOptNative", "(III[B)I", (void*) getSocketOptNative},
    {"setSocketOptNative", "(III[BI)I", (void*) setSocketOptNative}
};

int register_com_android_bluetooth_btservice_AdapterService(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/bluetooth/btservice/AdapterService",
                                    sMethods, NELEM(sMethods));
}

} /* namespace android */


/*
 * JNI Initialization
 */
jint JNI_OnLoad(JavaVM *jvm, void *reserved)
{
    JNIEnv *e;
    int status;

    ALOGV("Bluetooth Adapter Service : loading JNI\n");

    // Check JNI version
    if (jvm->GetEnv((void **)&e, JNI_VERSION_1_6)) {
        ALOGE("JNI version mismatch error");
        return JNI_ERR;
    }

    if ((status = android::register_com_android_bluetooth_btservice_AdapterService(e)) < 0) {
        ALOGE("jni adapter service registration failure, status: %d", status);
        return JNI_ERR;
    }

    if ((status = android::register_com_android_bluetooth_hfp(e)) < 0) {
        ALOGE("jni hfp registration failure, status: %d", status);
        return JNI_ERR;
    }

    if ((status = android::register_com_android_bluetooth_hfpclient(e)) < 0) {
        ALOGE("jni hfp client registration failure, status: %d", status);
        return JNI_ERR;
    }

    if ((status = android::register_com_android_bluetooth_a2dp(e)) < 0) {
        ALOGE("jni a2dp registration failure: %d", status);
        return JNI_ERR;
    }

    if ((status = android::register_com_android_bluetooth_avrcp(e)) < 0) {
        ALOGE("jni avrcp registration failure: %d", status);
        return JNI_ERR;
    }

    if ((status = android::register_com_android_bluetooth_hid(e)) < 0) {
        ALOGE("jni hid registration failure: %d", status);
        return JNI_ERR;
    }

    if ((status = android::register_com_android_bluetooth_hidd(e)) < 0) {
        ALOGE("jni hidd registration failure: %d", status);
        return JNI_ERR;
    }

    if ((status = android::register_com_android_bluetooth_hdp(e)) < 0) {
        ALOGE("jni hdp registration failure: %d", status);
        return JNI_ERR;
    }

    if ((status = android::register_com_android_bluetooth_pan(e)) < 0) {
        ALOGE("jni pan registration failure: %d", status);
        return JNI_ERR;
    }

    if ((status = android::register_com_android_bluetooth_gatt(e)) < 0) {
        ALOGE("jni gatt registration failure: %d", status);
        return JNI_ERR;
    }

    if ((status = android::register_com_android_bluetooth_btservice_QAdapterService(e)) < 0) {
        ALOGE("jni Q adapter service failure: %d", status);
        return JNI_ERR;
    }

    if ((status = android::register_android_hardware_wipower(e)) < 0) {
        ALOGE("jni wipower service registration failure, status: %d", status);
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
