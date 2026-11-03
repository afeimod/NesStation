// SPDX-License-Identifier: MIT
// JNI bridge for mGBA core (GB/GBC/GBA)
//
// Thin wrapper around the libretro frontend in gba_loader.cpp. Kotlin owns
// the emulation thread and pulls frames / audio on demand — no native-side
// threads or callbacks, which keeps the lifecycle simple and crash-free.
//
// For hardware-accelerated rendering, Kotlin passes a Surface object via
// setSurface(); we extract the ANativeWindow and hand it to gba_loader, which
// blits frames directly to the surface buffer in the video callback.
//
// Video resolution is dynamic (GB/GBC = 160x144, GBA = 240x160), so
// getFrameBuffer() queries videoWidth()/videoHeight() at call time rather
// than using a hardcoded size.
//
// Controller input supports 10 buttons (A, B, Select, Start, D-pad, L, R).
// setPad1(int) accepts the full bitfield; rom::setControllerInput takes
// uint16_t to preserve the GBA L/R bits (bit8/bit9).

#include "gba_bridge.h"
#include "gba_loader.h"
#include "shared/cheat_engine.h"  // v69 全核心金手指

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>

#define TAG "gbacore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace gbacore {

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
    LOGI("ROM loaded OK: %s (region=%d, rate=%d, %dx%d)",
         path.c_str(), region, rom::audioSampleRate(),
         rom::videoWidth(), rom::videoHeight());
    return true;
}

void Engine::unload()       { rom::unload(); }
void Engine::flushSave()    { rom::flushSave(); }
void Engine::reset(bool h)  { rom::resetEmulation(h); }
void Engine::runFrame()     { rom::stepFrame(); }
void Engine::shutdown()     { rom::unload(); }

void Engine::setPad1(int bits) {
    // GBA uses up to 10 buttons (bit8=L, bit9=R). uint16_t preserves all bits.
    rom::setControllerInput(0, (uint16_t)bits);
}

void Engine::setPad2(int bits) {
    rom::setControllerInput(1, (uint16_t)bits);
}

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
    // The actual ANativeWindow_fromSurface call requires a JNIEnv, which is
    // only available in the JNI function context. This method is a stub;
    // the JNI wrapper below performs the extraction and calls rom::setSurface.
}

void Engine::setCoreOption(const std::string& key, const std::string& value) {
    rom::setCoreOption(key, value);
}

int Engine::videoWidth()  { return rom::videoWidth(); }
int Engine::videoHeight() { return rom::videoHeight(); }

void Engine::setVideoFilter(int filter) { rom::setVideoFilter(filter); }
void Engine::setHighQualityScaling(bool enabled) { rom::setHighQualityScaling(enabled); }

} // namespace gbacore

// ---------------------------------------------------------------------------
// JNI surface — mirrors GbaNative.kt exactly
// ---------------------------------------------------------------------------

// Store the JavaVM for ANativeWindow_fromSurface calls
static JavaVM* s_jvm = nullptr;

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    s_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_GbaNative_loadRom(JNIEnv* env, jclass, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = gbacore::Engine::instance().loadRom(cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_unload(JNIEnv*, jclass) {
    gbacore::Engine::instance().unload();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_flushSave(JNIEnv*, jclass) {
    gbacore::Engine::instance().flushSave();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_reset(JNIEnv*, jclass, jboolean hard) {
    gbacore::Engine::instance().reset(hard == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_runFrame(JNIEnv*, jclass) {
    // ★★ v69 全核心金手指：帧前把补丁打到核心 RAM（列表为空时零开销直通）。
    if (coreshared::cheatActive()) {
        void* sysRam = nullptr; size_t sysSize = 0;
        void* saveRam = nullptr; size_t saveSize = 0;
        gbacore::rom::cheatRamRegions(&sysRam, &sysSize, &saveRam, &saveSize);
        coreshared::cheatApplyRegions(sysRam, sysSize, saveRam, saveSize);
    }
    gbacore::Engine::instance().runFrame();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_cheatSetList(JNIEnv* env, jclass, jintArray list) {
    coreshared::cheatSetList(env, list);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_setPad1(JNIEnv*, jclass, jint bits) {
    gbacore::Engine::instance().setPad1(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_setPad2(JNIEnv*, jclass, jint bits) {
    gbacore::Engine::instance().setPad2(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_setRegion(JNIEnv*, jclass, jint region) {
    gbacore::Engine::instance().setRegion(region);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_setSampleRate(JNIEnv*, jclass, jint rate) {
    gbacore::Engine::instance().setSampleRate(rate);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_setFastForward(JNIEnv*, jclass, jint speed) {
    gbacore::Engine::instance().setFastForward(speed);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_GbaNative_saveState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    gbacore::Engine::instance().saveState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_GbaNative_loadState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = gbacore::Engine::instance().loadState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_GbaNative_getFrameBuffer(JNIEnv* env, jclass, jintArray out) {
    if (!out) return JNI_FALSE;
    // GBA uses dynamic resolution (GB/GBC=160x144, GBA=240x160).
    // Query the current geometry from the core.
    int w = gbacore::Engine::instance().videoWidth();
    int h = gbacore::Engine::instance().videoHeight();
    if (w <= 0 || h <= 0) return JNI_FALSE;
    jsize len = env->GetArrayLength(out);
    if (len < w * h) return JNI_FALSE;
    jint* px = env->GetIntArrayElements(out, nullptr);
    if (!px) return JNI_FALSE;
    bool fresh = gbacore::Engine::instance().getFrameBuffer(
        reinterpret_cast<uint32_t*>(px), w, h);
    env->ReleaseIntArrayElements(out, px, 0); // commit back
    return fresh ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_GbaNative_readAudio(JNIEnv* env, jclass, jshortArray out) {
    if (!out) return 0;
    jsize len = env->GetArrayLength(out);
    if (len < 2) return 0;
    int maxFrames = len / 2;
    jshort* buf = env->GetShortArrayElements(out, nullptr);
    if (!buf) return 0;
    int n = gbacore::Engine::instance().readAudio(
        reinterpret_cast<int16_t*>(buf), maxFrames);
    env->ReleaseShortArrayElements(out, buf, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_GbaNative_audioSampleRate(JNIEnv*, jclass) {
    return gbacore::Engine::instance().audioSampleRate();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_GbaNative_audioTargetSampleRate(JNIEnv*, jclass) {
    return gbacore::Engine::instance().audioTargetSampleRate();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_setPaths(JNIEnv* env, jclass, jstring systemDir, jstring saveDir) {
    const char* sys = systemDir ? env->GetStringUTFChars(systemDir, nullptr) : nullptr;
    const char* sav = saveDir   ? env->GetStringUTFChars(saveDir,   nullptr) : nullptr;
    gbacore::Engine::instance().setPaths(sys ? sys : "", sav ? sav : "");
    if (sys) env->ReleaseStringUTFChars(systemDir, sys);
    if (sav) env->ReleaseStringUTFChars(saveDir, sav);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_setSaveName(JNIEnv* env, jclass, jstring name) {
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    gbacore::Engine::instance().setSaveName(n ? n : "");
    if (n) env->ReleaseStringUTFChars(name, n);
}

JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_GbaNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(gbacore::Engine::instance().lastError().c_str());
}

// --- Hardware-accelerated rendering ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_setSurface(JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* win = nullptr;
    if (surface) {
        win = ANativeWindow_fromSurface(env, surface);
    }
    gbacore::rom::setSurface(win);
    // ANativeWindow_fromSurface returns a new reference; setSurface acquires it.
    // Release our local reference so we don't leak.
    if (win) ANativeWindow_release(win);
}

// --- Core options ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_setCoreOption(JNIEnv* env, jclass, jstring key, jstring value) {
    const char* ckey = key ? env->GetStringUTFChars(key, nullptr) : nullptr;
    const char* cval = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
    gbacore::Engine::instance().setCoreOption(ckey ? ckey : "", cval ? cval : "");
    if (ckey) env->ReleaseStringUTFChars(key, ckey);
    if (cval) env->ReleaseStringUTFChars(value, cval);
}

// --- Video geometry ---

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_GbaNative_videoWidth(JNIEnv*, jclass) {
    return gbacore::Engine::instance().videoWidth();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_GbaNative_videoHeight(JNIEnv*, jclass) {
    return gbacore::Engine::instance().videoHeight();
}

// --- Video filter ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_setVideoFilter(JNIEnv*, jclass, jint filter) {
    gbacore::Engine::instance().setVideoFilter(filter);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GbaNative_setHighQualityScaling(JNIEnv*, jclass, jboolean enabled) {
    gbacore::Engine::instance().setHighQualityScaling(enabled);
}

} // extern "C"
