// SPDX-License-Identifier: MIT
// JNI bridge for Genesis-Plus-GX core (SEGA MD / SMS / GG / SG / Mega-CD).
//
// Thin wrapper around the libretro frontend in genesis_loader.cpp. Kotlin
// owns the emulation thread and pulls frames / audio on demand — same
// pull-model as the other engines.

#include "genesis_bridge.h"
#include "genesis_loader.h"
#include "shared/cheat_engine.h"  // v69 全核心金手指

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>

#define TAG "genesicore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace genesicore {

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
    LOGI("SEGA ROM loaded OK: %s (region=%d, rate=%d, %dx%d)",
         path.c_str(), region, rom::audioSampleRate(),
         rom::videoWidth(), rom::videoHeight());
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

int Engine::audioSampleRate()       { return rom::audioSampleRate(); }
int Engine::audioTargetSampleRate() { return rom::audioTargetSampleRate(); }

void Engine::setPaths(const std::string& systemDir, const std::string& saveDir) {
    rom::setPaths(systemDir, saveDir);
}

void Engine::setSaveName(const std::string& name) {
    rom::setSaveName(name);
}

void Engine::setSurface(jobject /*surface*/) {
    // Stub — JNI wrapper below performs ANativeWindow extraction.
}

void Engine::setCoreOption(const std::string& key, const std::string& value) {
    rom::setCoreOption(key, value);
}

int Engine::videoWidth()  { return rom::videoWidth(); }
int Engine::videoHeight() { return rom::videoHeight(); }

void Engine::setVideoFilter(int filter) { rom::setVideoFilter(filter); }
void Engine::setHighQualityScaling(bool enabled) { rom::setHighQualityScaling(enabled); }

} // namespace genesicore

// ---------------------------------------------------------------------------
// JNI surface — mirrors GenesisNative.kt exactly
// ---------------------------------------------------------------------------

static JavaVM* s_jvm = nullptr;

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    s_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_loadRom(JNIEnv* env, jclass, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = genesicore::Engine::instance().loadRom(cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_unload(JNIEnv*, jclass) {
    genesicore::Engine::instance().unload();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_reset(JNIEnv*, jclass, jboolean hard) {
    genesicore::Engine::instance().reset(hard == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_runFrame(JNIEnv*, jclass) {
    // ★★ v69 全核心金手指：帧前把补丁打到核心 RAM（列表为空时零开销直通）。
    if (coreshared::cheatActive()) {
        void* sysRam = nullptr; size_t sysSize = 0;
        void* saveRam = nullptr; size_t saveSize = 0;
        genesicore::rom::cheatRamRegions(&sysRam, &sysSize, &saveRam, &saveSize);
        coreshared::cheatApplyRegions(sysRam, sysSize, saveRam, saveSize);
    }
    genesicore::Engine::instance().runFrame();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_cheatSetList(JNIEnv* env, jclass, jintArray list) {
    coreshared::cheatSetList(env, list);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_setPad1(JNIEnv*, jclass, jint bits) {
    genesicore::Engine::instance().setPad1(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_setPad2(JNIEnv*, jclass, jint bits) {
    genesicore::Engine::instance().setPad2(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_setRegion(JNIEnv*, jclass, jint region) {
    genesicore::Engine::instance().setRegion(region);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_setSampleRate(JNIEnv*, jclass, jint rate) {
    genesicore::Engine::instance().setSampleRate(rate);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_setFastForward(JNIEnv*, jclass, jint speed) {
    genesicore::Engine::instance().setFastForward(speed);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_saveState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    genesicore::Engine::instance().saveState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_loadState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = genesicore::Engine::instance().loadState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_getFrameBuffer(JNIEnv* env, jclass, jintArray out) {
    if (!out) return JNI_FALSE;
    int vw = genesicore::Engine::instance().videoWidth();
    int vh = genesicore::Engine::instance().videoHeight();
    if (vw <= 0 || vh <= 0) { vw = 320; vh = 224; }
    jsize len = env->GetArrayLength(out);
    if (len < vw * vh) return JNI_FALSE;
    jint* px = env->GetIntArrayElements(out, nullptr);
    if (!px) return JNI_FALSE;
    bool fresh = genesicore::Engine::instance().getFrameBuffer(
        reinterpret_cast<uint32_t*>(px), vw, vh);
    env->ReleaseIntArrayElements(out, px, 0);
    return fresh ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_readAudio(JNIEnv* env, jclass, jshortArray out) {
    if (!out) return 0;
    jsize len = env->GetArrayLength(out);
    if (len < 2) return 0;
    int maxFrames = len / 2;
    jshort* buf = env->GetShortArrayElements(out, nullptr);
    if (!buf) return 0;
    int n = genesicore::Engine::instance().readAudio(
        reinterpret_cast<int16_t*>(buf), maxFrames);
    env->ReleaseShortArrayElements(out, buf, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_audioSampleRate(JNIEnv*, jclass) {
    return genesicore::Engine::instance().audioSampleRate();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_audioTargetSampleRate(JNIEnv*, jclass) {
    return genesicore::Engine::instance().audioTargetSampleRate();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_setPaths(JNIEnv* env, jclass, jstring systemDir, jstring saveDir) {
    const char* sys = systemDir ? env->GetStringUTFChars(systemDir, nullptr) : nullptr;
    const char* sav = saveDir   ? env->GetStringUTFChars(saveDir,   nullptr) : nullptr;
    genesicore::Engine::instance().setPaths(sys ? sys : "", sav ? sav : "");
    if (sys) env->ReleaseStringUTFChars(systemDir, sys);
    if (sav) env->ReleaseStringUTFChars(saveDir, sav);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_setSaveName(JNIEnv* env, jclass, jstring name) {
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    genesicore::Engine::instance().setSaveName(n ? n : "");
    if (n) env->ReleaseStringUTFChars(name, n);
}

JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(genesicore::Engine::instance().lastError().c_str());
}

// --- Hardware-accelerated rendering ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_setSurface(JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* win = nullptr;
    if (surface) {
        win = ANativeWindow_fromSurface(env, surface);
    }
    genesicore::rom::setSurface(win);
    if (win) ANativeWindow_release(win);
}

// --- Core options ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_setCoreOption(JNIEnv* env, jclass, jstring key, jstring value) {
    const char* ckey = key ? env->GetStringUTFChars(key, nullptr) : nullptr;
    const char* cval = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
    genesicore::Engine::instance().setCoreOption(ckey ? ckey : "", cval ? cval : "");
    if (ckey) env->ReleaseStringUTFChars(key, ckey);
    if (cval) env->ReleaseStringUTFChars(value, cval);
}

// --- Video geometry ---

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_videoWidth(JNIEnv*, jclass) {
    return genesicore::Engine::instance().videoWidth();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_videoHeight(JNIEnv*, jclass) {
    return genesicore::Engine::instance().videoHeight();
}

// --- Video filter ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_setVideoFilter(JNIEnv*, jclass, jint filter) {
    genesicore::Engine::instance().setVideoFilter(filter);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_setHighQualityScaling(JNIEnv*, jclass, jboolean enabled) {
    genesicore::Engine::instance().setHighQualityScaling(enabled);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_isCoreLibLoaded(JNIEnv*, jclass) {
    return genesicore::rom::isCoreLoaded() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_GenesisNative_setCoreLibPath(JNIEnv* env, jclass, jstring path) {
    const char* cpath = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    genesicore::rom::setCoreLibPath(cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
}

} // extern "C"
