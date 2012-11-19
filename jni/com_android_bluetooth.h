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

int register_com_android_bluetooth_hdp(JNIEnv* env);

int register_com_android_bluetooth_pan(JNIEnv* env);

}

#endif /* COM_ANDROID_BLUETOOTH_H */
