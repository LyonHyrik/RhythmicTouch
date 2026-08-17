#pragma once

#include <jni.h>

namespace phira {

bool installPcmCapture(JNIEnv* env, jobject bridgeGlobalRef);
void uninstallPcmCapture();
bool isCaptureInstalled();

}  // namespace phira