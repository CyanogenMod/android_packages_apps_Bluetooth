# Copyright 2015 Google Inc. All Rights Reserved.

LOCAL_PATH := $(call my-dir)

# Build the application.
include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := A2dpMediaBrowserService
LOCAL_MODULE_TAGS := optional
LOCAL_CERTIFICATE := platform

LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_RESOURCE_DIR = $(LOCAL_PATH)/res

LOCAL_PROGUARD_ENABLED := disabled

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
