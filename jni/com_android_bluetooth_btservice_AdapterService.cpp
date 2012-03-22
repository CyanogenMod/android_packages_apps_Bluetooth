/*
 * Copyright (C) 2012 Google Inc.
 */

#define LOG_TAG "BluetoothServiceJni"

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

#define ADDITIONAL_NREFS 50

static jmethodID method_stateChangeCallback;
static jmethodID method_adapterPropertyChangedCallback;
static jmethodID method_devicePropertyChangedCallback;
static jmethodID method_deviceFoundCallback;
static jmethodID method_pinRequestCallback;
static jmethodID method_sspRequestCallback;
static jmethodID method_bondStateChangeCallback;
static jmethodID method_discoveryStateChangeCallback;

static const bt_interface_t *sBluetoothInterface = NULL;
static JNIEnv *callbackEnv = NULL;

static jobject sJniCallbacksObj;
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
        LOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

static bool checkCallbackThread() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (callbackEnv != env || callbackEnv == NULL) return false;
    return true;
}

static void adapter_state_change_callback(bt_state_t status) {
    if (!checkCallbackThread()) {
       LOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }
    LOGV("%s: Status is: %d", __FUNCTION__, status);

    callbackEnv->CallVoidMethod(sJniCallbacksObj, method_stateChangeCallback, (jint)status);

    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);
}

static int get_properties(int num_properties, bt_property_t *properties, jintArray *types,
                        jobjectArray *props) {
    jbyteArray propVal, val;
    val = (jbyteArray) callbackEnv->NewByteArray(num_properties);
    if (val == NULL) goto Fail;

    //TODO(BT) Is this the best way to do it ?
    *props = callbackEnv->NewObjectArray(num_properties, callbackEnv->GetObjectClass(val),
                                             NULL);
    if (*props == NULL) goto Fail;

    *types = callbackEnv->NewIntArray(num_properties);
    if (*types == NULL) goto Fail;

    for (int i = 0; i < num_properties; i++) {
       propVal = callbackEnv->NewByteArray(properties[i].len);
       if (propVal == NULL) goto Fail;

       callbackEnv->SetByteArrayRegion(propVal, 0, properties[i].len,
                                            (jbyte*)properties[i].val);
       callbackEnv->SetObjectArrayElement(*props, i, propVal);

       callbackEnv->SetIntArrayRegion(*types, i, 1, (jint *)&properties[i].type);
    }
    return 0;
Fail:
    if (val) callbackEnv->DeleteLocalRef(val);
    if (propVal) callbackEnv->DeleteLocalRef(propVal);
    LOGE("Error while allocation of array in %s", __FUNCTION__);
    return -1;
}

static void adapter_properties_callback(bt_status_t status, int num_properties,
                                        bt_property_t *properties) {
    jobjectArray props;
    jintArray types;
    if (!checkCallbackThread()) {
       LOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }

    LOGV("%s: Status is: %d, Properties: %d", __FUNCTION__, status, num_properties);

    if (status != BT_STATUS_SUCCESS) {
        LOGE("%s: Status %d is incorrect", __FUNCTION__, status);
        return;
    }

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
       LOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }

    LOGV("%s: Status is: %d, Properties: %d", __FUNCTION__, status, num_properties);

    if (status != BT_STATUS_SUCCESS) {
        LOGE("%s: Status %d is incorrect", __FUNCTION__, status);
        return;
    }

    callbackEnv->PushLocalFrame(ADDITIONAL_NREFS);

    jobjectArray props;
    jbyteArray addr;
    jintArray types;

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
    callbackEnv->PopLocalFrame(NULL);
    return;

Fail:
    LOGE("Error while allocation byte array in %s", __FUNCTION__);
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
                LOGE("Address is NULL (unable to allocate) in %s", __FUNCTION__);
                return;
            }
        }
    }
    if (addr == NULL) {
        LOGE("Address is NULL in %s", __FUNCTION__);
        return;
    }

    LOGV("%s: Properties: %d, Address: %s", __FUNCTION__, num_properties,
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
       LOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }
    if (!bd_addr) {
        LOGE("Address is null in %s", __FUNCTION__);
        return;
    }
    addr = callbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if (addr == NULL) {
       LOGE("Address allocation failed in %s", __FUNCTION__);
       return;
    }
    callbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte *)bd_addr);

    callbackEnv->CallVoidMethod(sJniCallbacksObj, method_bondStateChangeCallback, (jint) status,
                                addr, (jint)state);
    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);
    callbackEnv->DeleteLocalRef(addr);
}

static void discovery_state_changed_callback(bt_discovery_state_t state) {
    jbyteArray addr;
    if (!checkCallbackThread()) {
       LOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }

    LOGV("%s: DiscoveryState:%d ", __FUNCTION__, state);

    callbackEnv->CallVoidMethod(sJniCallbacksObj, method_discoveryStateChangeCallback,
                                (jint)state);

    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);
}

static void pin_request_callback(bt_bdaddr_t *bd_addr, bt_bdname_t *bdname, uint32_t cod) {
    jbyteArray addr, devname;
    if (!checkCallbackThread()) {
       LOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }
    if (!bd_addr) {
        LOGE("Address is null in %s", __FUNCTION__);
        return;
    }

    addr = callbackEnv->NewByteArray(sizeof(bt_bdaddr_t));
    if (addr == NULL) goto Fail;
    callbackEnv->SetByteArrayRegion(addr, 0, sizeof(bt_bdaddr_t), (jbyte*)bd_addr);

    devname = callbackEnv->NewByteArray(sizeof(bt_bdname_t));
    if (devname == NULL) goto Fail;

    callbackEnv->SetByteArrayRegion(devname, 0, sizeof(bt_bdname_t), (jbyte*)bdname);

    callbackEnv->CallVoidMethod(sJniCallbacksObj, method_pinRequestCallback, addr, devname, cod);

    checkAndClearExceptionFromCallback(callbackEnv, __FUNCTION__);
    callbackEnv->DeleteLocalRef(addr);
    callbackEnv->DeleteLocalRef(devname);
    return;

Fail:
    if (addr) callbackEnv->DeleteLocalRef(addr);
    if (devname) callbackEnv->DeleteLocalRef(devname);
    LOGE("Error while allocating in: %s", __FUNCTION__);
}

static void ssp_request_callback(bt_bdaddr_t *bd_addr, bt_bdname_t *bdname, uint32_t cod,
                                 bt_ssp_variant_t pairing_variant, uint32_t pass_key) {
    jbyteArray addr, devname;
    if (!checkCallbackThread()) {
       LOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
       return;
    }
    if (!bd_addr) {
        LOGE("Address is null in %s", __FUNCTION__);
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

    LOGE("Error while allocating in: %s", __FUNCTION__);
}

static void callback_thread_event(bt_cb_thread_evt event) {
    JavaVM* vm = AndroidRuntime::getJavaVM();
    if (event  == ASSOCIATE_JVM) {
        JavaVMAttachArgs args;
        char name[] = "BT Service Callback Thread";
        //TODO(BT)
        //args.version = nat->envVer;
        args.name = name;
        args.group = NULL;
        vm->AttachCurrentThread(&callbackEnv, &args);
        LOGV("Callback thread attached: %p", callbackEnv);
    } else if (event == DISASSOCIATE_JVM) {
        if (!checkCallbackThread()) {
            LOGE("Callback: '%s' is not called on the correct thread", __FUNCTION__);
            return;
        }
        vm->DetachCurrentThread();
    }
}

bt_callbacks_t sBluetoothCallbacks = {
    sizeof(sBluetoothCallbacks),
    adapter_state_change_callback,
    adapter_properties_callback,
    remote_device_properties_callback,
    device_found_callback,
    discovery_state_changed_callback,
    pin_request_callback,
    ssp_request_callback,
    bond_state_changed_callback,
    callback_thread_event,
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

    method_devicePropertyChangedCallback = env->GetMethodID(jniCallbackClass,
                                                            "devicePropertyChangedCallback",
                                                            "([B[I[[B)V");
    method_deviceFoundCallback = env->GetMethodID(jniCallbackClass, "deviceFoundCallback", "([B)V");
    method_pinRequestCallback = env->GetMethodID(jniCallbackClass, "pinRequestCallback",
                                                 "([B[BI)V");
    method_sspRequestCallback = env->GetMethodID(jniCallbackClass, "sspRequestCallback",
                                                 "([B[BIII)V");

    method_bondStateChangeCallback = env->GetMethodID(jniCallbackClass,
                                                     "bondStateChangeCallback", "(I[BI)V");

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
           LOGE("Error while opening Bluetooth library");
        }
    } else {
        LOGE("No Bluetooth Library found");
    }
}

static bool initNative(JNIEnv* env, jobject obj) {
    LOGV("%s:",__FUNCTION__);

    sJniCallbacksObj = env->NewGlobalRef(env->GetObjectField(obj, sJniCallbacksField));

    if (sBluetoothInterface) {
        int ret = sBluetoothInterface->init(&sBluetoothCallbacks);
        if (ret != BT_STATUS_SUCCESS) {
            LOGE("Error while setting the callbacks \n");
            sBluetoothInterface = NULL;
            return JNI_FALSE;
        }
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static bool cleanupNative(JNIEnv *env, jobject obj) {
    LOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    sBluetoothInterface->cleanup();
    env->DeleteGlobalRef(sJniCallbacksObj);
    return JNI_TRUE;
}

static jboolean enableNative(JNIEnv* env, jobject obj) {
    LOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    int ret = sBluetoothInterface->enable();
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
    return result;
}

static jboolean disableNative(JNIEnv* env, jobject obj) {
    LOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    int ret = sBluetoothInterface->disable();
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
    return result;
}

static jboolean startDiscoveryNative(JNIEnv* env, jobject obj) {
    LOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    int ret = sBluetoothInterface->start_discovery();
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
    return result;
}

static jboolean cancelDiscoveryNative(JNIEnv* env, jobject obj) {
    LOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    int ret = sBluetoothInterface->cancel_discovery();
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
    return result;
}

static jboolean createBondNative(JNIEnv* env, jobject obj, jbyteArray address) {
    LOGV("%s:",__FUNCTION__);

    jbyte *addr;
    jboolean result = JNI_FALSE;

    if (!sBluetoothInterface) return result;

    addr = env->GetByteArrayElements(address, NULL);
    if (addr == NULL) {
        jniThrowIOException(env, EINVAL);
        return result;
    }

    int ret = sBluetoothInterface->create_bond((bt_bdaddr_t *)addr);
    env->ReleaseByteArrayElements(address, addr, NULL);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean removeBondNative(JNIEnv* env, jobject obj, jbyteArray address) {
    LOGV("%s:",__FUNCTION__);

    jbyte *addr;
    jboolean result;
    if (!sBluetoothInterface) return JNI_FALSE;

    addr = env->GetByteArrayElements(address, NULL);
    if (addr == NULL) {
        jniThrowIOException(env, EINVAL);
        return JNI_FALSE;
    }

    int ret = sBluetoothInterface->remove_bond((bt_bdaddr_t *)addr);
    env->ReleaseByteArrayElements(address, addr, NULL);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean cancelBondNative(JNIEnv* env, jobject obj, jbyteArray address) {
    LOGV("%s:",__FUNCTION__);

    jbyte *addr;
    jboolean result;
    if (!sBluetoothInterface) return JNI_FALSE;

    addr = env->GetByteArrayElements(address, NULL);
    if (addr == NULL) {
        jniThrowIOException(env, EINVAL);
        return JNI_FALSE;
    }

    int ret = sBluetoothInterface->cancel_bond((bt_bdaddr_t *)addr);
    env->ReleaseByteArrayElements(address, addr, NULL);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean pinReplyNative(JNIEnv *env, jobject obj, jbyteArray address, jboolean accept,
                               jint len, jbyteArray pinArray) {
    LOGV("%s:",__FUNCTION__);

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
           env->ReleaseByteArrayElements(address, addr, NULL);
           return result;
        }
    }

    int ret = sBluetoothInterface->pin_reply((bt_bdaddr_t*)addr, accept, len,
                                              (bt_pin_code_t *) pinPtr);
    env->ReleaseByteArrayElements(address, addr, NULL);
    env->ReleaseByteArrayElements(pinArray, pinPtr, NULL);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean sspReplyNative(JNIEnv *env, jobject obj, jbyteArray address,
                               jint type, jboolean accept, jint passkey) {
    LOGV("%s:",__FUNCTION__);

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
    env->ReleaseByteArrayElements(address, addr, NULL);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean setAdapterPropertyNative(JNIEnv *env, jobject obj, jint type, jbyteArray value) {
    LOGV("%s:",__FUNCTION__);

    jbyte *val;
    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    val = env->GetByteArrayElements(value, NULL);
    bt_property_t prop;
    prop.type = (bt_property_type_t) type;
    prop.len = env->GetArrayLength(value);
    prop.val = val;

    int ret = sBluetoothInterface->set_adapter_property(&prop);
    env->ReleaseByteArrayElements(value, val, NULL);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean getAdapterPropertiesNative(JNIEnv *env, jobject obj) {
    LOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    int ret = sBluetoothInterface->get_adapter_properties();
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean getAdapterPropertyNative(JNIEnv *env, jobject obj, jint type) {
    LOGV("%s:",__FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothInterface) return result;

    int ret = sBluetoothInterface->get_adapter_property((bt_property_type_t) type);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean getDevicePropertyNative(JNIEnv *env, jobject obj, jbyteArray address, jint type) {
    LOGV("%s:",__FUNCTION__);

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
    env->ReleaseByteArrayElements(address, addr, NULL);
    result = (ret == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;

    return result;
}

static jboolean setDevicePropertyNative(JNIEnv *env, jobject obj, jbyteArray address,
                                        jint type, jbyteArray value) {
    LOGV("%s:",__FUNCTION__);

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
        env->ReleaseByteArrayElements(value, val, NULL);
        jniThrowIOException(env, EINVAL);
        return result;
    }


    bt_property_t prop;
    prop.type = (bt_property_type_t) type;
    prop.len = env->GetArrayLength(value);
    prop.val = val;

    int ret = sBluetoothInterface->set_remote_device_property((bt_bdaddr_t *)addr, &prop);
    env->ReleaseByteArrayElements(value, val, NULL);
    env->ReleaseByteArrayElements(address, addr, NULL);

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

   LOGV("Bluetooth Adapter Service : loading JNI\n");

   // Check JNI version
   if(jvm->GetEnv((void **)&e, JNI_VERSION_1_6)) {
       LOGE("JNI version mismatch error");
      return JNI_ERR;
   }

   if ((status = android::register_com_android_bluetooth_btservice_AdapterService(e)) < 0) {
       LOGE("jni adapter service registration failure, status: %d", status);
      return JNI_ERR;
   }

   if ((status = android::register_com_android_bluetooth_hfp(e)) < 0) {
       LOGE("jni hfp registration failure, status: %d", status);
      return JNI_ERR;
   }

   if ((status = android::register_com_android_bluetooth_a2dp(e)) < 0) {
       LOGE("jni a2dp registration failure: %d", status);
      return JNI_ERR;
   }

   if ((status = android::register_com_android_bluetooth_hid(e)) < 0) {
       LOGE("jni hid registration failure: %d", status);
      return JNI_ERR;
   }

   return JNI_VERSION_1_6;
}
