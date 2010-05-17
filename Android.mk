LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := Bluetooth

LOCAL_JAVA_LIBRARIES := javax.obex
LOCAL_STATIC_JAVA_LIBRARIES := com.android.vcard

LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
