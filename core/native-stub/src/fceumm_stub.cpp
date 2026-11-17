// SPDX-License-Identifier: MIT
// Fallback placeholder core — used when the FCEUmm submodule has not
// been pulled yet. Exposes the same JNI surface as the real one, so the
// app can build and the developer can iterate on UI.
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>

#define TAG "nescore-stub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static bool s_loaded = false;
static int  s_frame  = 0;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_loadRom(JNIEnv*, jclass, jstring) {
    LOGI("loadRom (stub) — real FCEUmm not linked");
    s_loaded = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_unload(JNIEnv*, jclass) { s_loaded = false; }

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_reset(JNIEnv*, jclass, jboolean) {}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_runFrame(JNIEnv*, jclass) {
    s_frame++;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setPad1(JNIEnv*, jclass, jint) {}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setRegion(JNIEnv*, jclass, jint) {}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setSampleRate(JNIEnv*, jclass, jint) {}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setFastForward(JNIEnv*, jclass, jboolean) {}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_saveState(JNIEnv*, jclass, jint, jstring) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_loadState(JNIEnv*, jclass, jint, jstring) {
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_getFrameBuffer(JNIEnv* env, jclass, jintArray out) {
    if (!out) return JNI_FALSE;
    jsize len = env->GetArrayLength(out);
    if (len < 256 * 240) return JNI_FALSE;
    jint* px = env->GetIntArrayElements(out, nullptr);
    if (!px) return JNI_FALSE;
    // Draw a moving gradient so the stub is visually distinguishable
    int phase = (s_frame * 4) & 0xFF;
    for (int y = 0; y < 240; y++) {
        for (int x = 0; x < 256; x++) {
            int r = (40 + (x * 200 / 256) + phase / 4) & 0xFF;
            int g = (60 + (y * 100 / 240)) & 0xFF;
            int b = (200 - phase) & 0xFF;
            px[y * 256 + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }
    env->ReleaseIntArrayElements(out, px, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NesNative_readAudio(JNIEnv* env, jclass, jshortArray out) {
    if (!out) return 0;
    jsize len = env->GetArrayLength(out);
    if (len < 2) return 0;
    jshort* buf = env->GetShortArrayElements(out, nullptr);
    if (!buf) return 0;
    // Silence
    for (jsize i = 0; i < len; i++) buf[i] = 0;
    env->ReleaseShortArrayElements(out, buf, 0);
    return len / 2;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NesNative_audioSampleRate(JNIEnv*, jclass) {
    return 44100;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setPaths(JNIEnv*, jclass, jstring, jstring) {}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_NesNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF("");
}
