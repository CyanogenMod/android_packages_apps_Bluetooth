/*
 * Copyright (C) 2012 Google Inc.
 */

#ifndef COM_ANDROID_BLUETOOTH_H
#define COM_ANDROID_BLUETOOTH_H

#include "JNIHelp.h"
#include "jni.h"
#include "hardware/hardware.h"
#include "hardware/bluetooth.h"

namespace android {

void checkAndClearExceptionFromCallback(JNIEnv* env,
                                        const char* methodName);

const bt_interface_t* getBluetoothInterface();

JNIEnv* getCallbackEnv();

int register_com_android_bluetooth_hfp(JNIEnv* env);

int register_com_android_bluetooth_a2dp(JNIEnv* env);

int register_com_android_bluetooth_hid(JNIEnv* env);

}

#endif /* COM_ANDROID_BLUETOOTH_H */
