// SPDX-License-Identifier: MIT
// JNI bridge for the PPSSPP (PSP) core — NesStation in-process integration.
//
// Mirrors PsxNative.kt / psx_bridge.cpp structure: every Kotlin call lands
// on a Java_com_nesstation_app_core_jni_PspNative_* export which forwards
// into psp_loader.cpp (the dlopen'd libppsspp_jni.so host driver).
// The stub jobject is created and owned by Kotlin (PspHostStub) — the core's
// frame commands (toast/keyboard/…) arrive there via registerCallbacks.

#include "psp_bridge.h"
#include "psp_loader.h"

#include <jni.h>
#include <android/log.h>
#include <string>

#define TAG "pspcore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

namespace {

std::string JStr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(s, p);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    (void)vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setCoreLibPath(JNIEnv* env, jclass, jstring path) {
    pspload::setCoreLibPath(JStr(env, path));
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PspNative_boot(
    JNIEnv* env, jclass, jobject stub,
    jstring model, jint deviceType, jstring langRegion, jstring apkPath,
    jstring dataDir, jstring extStorageDir, jstring extFilesDir,
    jstring nativeLibDir, jstring addStorageDirs, jstring cacheDir,
    jstring shortcutParam, jstring installerName, jint androidVersion,
    jstring board, jint displayXres, jint displayYres, jint displayDpi,
    jfloat displayRefresh, jint optimalFpb, jint optimalSr) {

    pspload::setHostStub(env, stub);

    pspload::HostParams p;
    p.model                 = JStr(env, model);
    p.deviceType            = deviceType;
    p.langRegion            = JStr(env, langRegion);
    p.apkPath               = JStr(env, apkPath);
    p.dataDir               = JStr(env, dataDir);
    p.externalStorageDir    = JStr(env, extStorageDir);
    p.externalFilesDir      = JStr(env, extFilesDir);
    p.nativeLibDir          = JStr(env, nativeLibDir);
    p.additionalStorageDirs = JStr(env, addStorageDirs);
    p.cacheDir              = JStr(env, cacheDir);
    p.shortcutParam         = JStr(env, shortcutParam);
    p.installerName         = JStr(env, installerName);
    p.androidVersion        = androidVersion;
    p.board                 = JStr(env, board);
    p.displayXres           = displayXres;
    p.displayYres           = displayYres;
    p.displayDpi            = displayDpi;
    p.displayRefresh        = displayRefresh;
    p.optimalFramesPerBuffer = optimalFpb;
    p.optimalSampleRate     = optimalSr;

    const bool ok = pspload::boot(env, p);
    if (ok) LOGI("boot OK");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PspNative_vulkanActive(JNIEnv*, jclass) {
    return pspload::vulkanActive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setSurface(JNIEnv* env, jclass,
                                                      jobject surface,
                                                      jint width, jint height) {
    pspload::setSurface(env, surface, width, height);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_pause(JNIEnv* env, jclass) {
    pspload::pause(env);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_resume(JNIEnv* env, jclass) {
    pspload::resume(env);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PspNative_keyDown(JNIEnv* env, jclass, jint keycode) {
    return pspload::keyDown(env, keycode) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PspNative_keyUp(JNIEnv* env, jclass, jint keycode) {
    return pspload::keyUp(env, keycode) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setAnalog(JNIEnv* env, jclass,
                                                     jfloat lx, jfloat ly) {
    pspload::joystickAxis(env, lx, ly);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_onTouch(JNIEnv* env, jclass,
                                                   jfloat x, jfloat y,
                                                   jint code, jint pointerId) {
    pspload::touch(env, x, y, code, pointerId);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PspNative_saveState(JNIEnv* env, jclass, jint slot) {
    return pspload::saveState(env, slot) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PspNative_loadState(JNIEnv* env, jclass, jint slot) {
    return pspload::loadState(env, slot) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setFastForward(JNIEnv*, jclass, jboolean on) {
    pspload::setFastForward(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_unload(JNIEnv* env, jclass) {
    pspload::unload(env);
}

JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_PspNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(pspload::lastError().c_str());
}

} // extern "C"
