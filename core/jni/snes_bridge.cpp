// SPDX-License-Identifier: MIT
// JNI bridge for SNES9x core
//
// Thin wrapper around the libretro frontend in snes_loader.cpp. Kotlin owns
// the emulation thread and pulls frames / audio on demand — no native-side
// threads or callbacks, which keeps the lifecycle simple and crash-free.
//
// For hardware-accelerated rendering, Kotlin passes a Surface object via
// setSurface(); we extract the ANativeWindow and hand it to snes_loader, which
// blits frames directly to the surface buffer in the video callback.

#include "snes_bridge.h"
#include "snes_loader.h"
#include "shared/cheat_engine.h"  // v69 全核心金手指

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>

#define TAG "snescore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace snescore {

Engine& Engine::instance() {
    static Engine e;
    return e;
}

bool Engine::loadRom(const std::string& path) {
    int region = 0;
    auto err = rom::loadFromFile(path, region);
    if (!err.empty()) {
        lastError_ = err;
        LOGE("loadRom failed: %s", err.c_str());
        return false;
    }
    lastError_.clear();
    LOGI("ROM loaded OK: %s (region=%d, rate=%d)",
         path.c_str(), region, rom::audioSampleRate());
    return true;
}

void Engine::unload()       { rom::unload(); }
void Engine::reset(bool h)  { rom::resetEmulation(h); }
void Engine::runFrame()     { rom::stepFrame(); }
void Engine::shutdown()     { rom::unload(); }

void Engine::setPad1(int bits)        { rom::setControllerInput(0, (uint16_t)bits); }
void Engine::setPad2(int bits)        { rom::setControllerInput(1, (uint16_t)bits); }
void Engine::setRegion(int region)    { rom::applyRegion(region); }
void Engine::setSampleRate(int hz)    { rom::applySampleRate(hz); }
void Engine::setFastForward(int speed)  { rom::applySpeed(speed > 0 ? (float)speed : 1.0f); }

void Engine::saveState(int slot, const std::string& path) {
    rom::saveStateToPath(slot, path);
}

bool Engine::loadState(int slot, const std::string& path) {
    return rom::loadStateFromPath(slot, path);
}

bool Engine::getFrameBuffer(uint32_t* out, int w, int h) {
    return rom::copyFramebufferARGB(out, w, h);
}

int Engine::readAudio(int16_t* out, int maxFrames) {
    return rom::readAudio(out, maxFrames);
}

int Engine::audioSampleRate() {
    return rom::audioSampleRate();
}

int Engine::audioTargetSampleRate() {
    return rom::audioTargetSampleRate();
}

void Engine::setPaths(const std::string& systemDir, const std::string& saveDir) {
    rom::setPaths(systemDir, saveDir);
}

void Engine::setSaveName(const std::string& name) {
    rom::setSaveName(name);
}

void Engine::setSurface(jobject surface) {
    if (!surface) {
        rom::setSurface(nullptr);
        return;
    }
    // The actual ANativeWindow extraction happens in the JNI wrapper below,
    // which calls ANativeWindow_fromSurface(env, surface) and passes the
    // result to rom::setSurface().
}

void Engine::setCoreOption(const std::string& key, const std::string& value) {
    rom::setCoreOption(key, value);
}

int Engine::videoWidth()  { return rom::videoWidth(); }
int Engine::videoHeight() { return rom::videoHeight(); }

void Engine::setVideoFilter(int filter) { rom::setVideoFilter(filter); }
void Engine::setHighQualityScaling(bool enabled) { rom::setHighQualityScaling(enabled); }

} // namespace snescore

// ---------------------------------------------------------------------------
// JNI surface — mirrors SnesNative.kt exactly
// ---------------------------------------------------------------------------

// Store the JavaVM for potential ANativeWindow_fromSurface calls from non-JNI
// threads. In practice, setSurface is always called from a JNI function where
// the JNIEnv is available directly.
static JavaVM* s_jvm = nullptr;

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    s_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_SnesNative_loadRom(JNIEnv* env, jclass, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = snescore::Engine::instance().loadRom(cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_unload(JNIEnv*, jclass) {
    snescore::Engine::instance().unload();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_reset(JNIEnv*, jclass, jboolean hard) {
    snescore::Engine::instance().reset(hard == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_runFrame(JNIEnv*, jclass) {
    // ★★ v69 全核心金手指：帧前把补丁打到核心 RAM（列表为空时零开销直通）。
    if (coreshared::cheatActive()) {
        void* sysRam = nullptr; size_t sysSize = 0;
        void* saveRam = nullptr; size_t saveSize = 0;
        snescore::rom::cheatRamRegions(&sysRam, &sysSize, &saveRam, &saveSize);
        coreshared::cheatApplyRegions(sysRam, sysSize, saveRam, saveSize);
    }
    snescore::Engine::instance().runFrame();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_cheatSetList(JNIEnv* env, jclass, jintArray list) {
    coreshared::cheatSetList(env, list);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_setPad1(JNIEnv*, jclass, jint bits) {
    snescore::Engine::instance().setPad1(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_setPad2(JNIEnv*, jclass, jint bits) {
    snescore::Engine::instance().setPad2(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_setRegion(JNIEnv*, jclass, jint region) {
    snescore::Engine::instance().setRegion(region);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_setSampleRate(JNIEnv*, jclass, jint rate) {
    snescore::Engine::instance().setSampleRate(rate);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_setFastForward(JNIEnv*, jclass, jint speed) {
    snescore::Engine::instance().setFastForward(speed);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_SnesNative_saveState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    snescore::Engine::instance().saveState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_SnesNative_loadState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = snescore::Engine::instance().loadState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_SnesNative_getFrameBuffer(JNIEnv* env, jclass, jintArray out) {
    if (!out) return JNI_FALSE;
    // SNES resolution is variable (256x224 standard, up to 512x478 high-res),
    // so we use the current video dimensions rather than a hardcoded size.
    int vw = snescore::Engine::instance().videoWidth();
    int vh = snescore::Engine::instance().videoHeight();
    if (vw <= 0 || vh <= 0) { vw = 256; vh = 224; } // fallback to standard SNES
    jsize len = env->GetArrayLength(out);
    if (len < vw * vh) return JNI_FALSE;
    jint* px = env->GetIntArrayElements(out, nullptr);
    if (!px) return JNI_FALSE;
    bool fresh = snescore::Engine::instance().getFrameBuffer(
        reinterpret_cast<uint32_t*>(px), vw, vh);
    env->ReleaseIntArrayElements(out, px, 0); // commit back
    return fresh ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_SnesNative_readAudio(JNIEnv* env, jclass, jshortArray out) {
    if (!out) return 0;
    jsize len = env->GetArrayLength(out);
    if (len < 2) return 0;
    int maxFrames = len / 2;
    jshort* buf = env->GetShortArrayElements(out, nullptr);
    if (!buf) return 0;
    int n = snescore::Engine::instance().readAudio(
        reinterpret_cast<int16_t*>(buf), maxFrames);
    env->ReleaseShortArrayElements(out, buf, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_SnesNative_audioSampleRate(JNIEnv*, jclass) {
    return snescore::Engine::instance().audioSampleRate();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_SnesNative_audioTargetSampleRate(JNIEnv*, jclass) {
    return snescore::Engine::instance().audioTargetSampleRate();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_setPaths(JNIEnv* env, jclass, jstring systemDir, jstring saveDir) {
    const char* sys = systemDir ? env->GetStringUTFChars(systemDir, nullptr) : nullptr;
    const char* sav = saveDir   ? env->GetStringUTFChars(saveDir,   nullptr) : nullptr;
    snescore::Engine::instance().setPaths(sys ? sys : "", sav ? sav : "");
    if (sys) env->ReleaseStringUTFChars(systemDir, sys);
    if (sav) env->ReleaseStringUTFChars(saveDir, sav);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_setSaveName(JNIEnv* env, jclass, jstring name) {
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    snescore::Engine::instance().setSaveName(n ? n : "");
    if (n) env->ReleaseStringUTFChars(name, n);
}

JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_SnesNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(snescore::Engine::instance().lastError().c_str());
}

// --- Hardware-accelerated rendering ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_setSurface(JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* win = nullptr;
    if (surface) {
        win = ANativeWindow_fromSurface(env, surface);
    }
    snescore::rom::setSurface(win);
    // ANativeWindow_fromSurface returns a new reference; setSurface acquires it.
    // Release our local reference so we don't leak.
    if (win) ANativeWindow_release(win);
}

// --- Core options ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_setCoreOption(JNIEnv* env, jclass, jstring key, jstring value) {
    const char* ckey = key ? env->GetStringUTFChars(key, nullptr) : nullptr;
    const char* cval = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
    snescore::Engine::instance().setCoreOption(ckey ? ckey : "", cval ? cval : "");
    if (ckey) env->ReleaseStringUTFChars(key, ckey);
    if (cval) env->ReleaseStringUTFChars(value, cval);
}

// --- Video geometry ---

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_SnesNative_videoWidth(JNIEnv*, jclass) {
    return snescore::Engine::instance().videoWidth();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_SnesNative_videoHeight(JNIEnv*, jclass) {
    return snescore::Engine::instance().videoHeight();
}

// --- Video filter ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_setVideoFilter(JNIEnv*, jclass, jint filter) {
    snescore::Engine::instance().setVideoFilter(filter);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_SnesNative_setHighQualityScaling(JNIEnv*, jclass, jboolean enabled) {
    snescore::Engine::instance().setHighQualityScaling(enabled);
}

} // extern "C"
