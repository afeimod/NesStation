// SPDX-License-Identifier: MIT
// JNI bridge for FCEUmm core
//
// Thin wrapper around the libretro frontend in rom_loader.cpp. Kotlin owns
// the emulation thread and pulls frames / audio on demand — no native-side
// threads or callbacks, which keeps the lifecycle simple and crash-free.
//
// For hardware-accelerated rendering, Kotlin passes a Surface object via
// setSurface(); we extract the ANativeWindow and hand it to rom_loader, which
// blits frames directly to the surface buffer in the video callback.

#include "bridge.h"
#include "rom_loader.h"
#include "shared/cheat_engine.h"  // v69 全核心金手指

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>

#define TAG "nescore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace nescore {

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

void Engine::setPad1(int bits)        { rom::setControllerInput(0, (uint8_t)bits); }
void Engine::setPad2(int bits)        { rom::setControllerInput(1, (uint8_t)bits); }
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
    // Get the JNIEnv for this thread (JNI_OnLoad or a JNI call context)
    JavaVM* vm = nullptr;
    // We rely on the caller being a JNI function; the JNIEnv is available
    // via the JNI function parameters. However, setSurface might be called
    // from a non-JNI context, so we store the JavaVM globally.
    // For simplicity, we pass the jobject directly to the JNI function
    // which calls ANativeWindow_fromSurface.
    // This is handled in the JNI wrapper below.
}

void Engine::setCoreOption(const std::string& key, const std::string& value) {
    rom::setCoreOption(key, value);
}

int Engine::videoWidth()  { return rom::videoWidth(); }
int Engine::videoHeight() { return rom::videoHeight(); }

void Engine::setVideoFilter(int filter) { rom::setVideoFilter(filter); }
void Engine::setHighQualityScaling(bool enabled) { rom::setHighQualityScaling(enabled); }

} // namespace nescore

// ---------------------------------------------------------------------------
// JNI surface — mirrors NesNative.kt exactly
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
Java_com_nesstation_app_core_jni_NesNative_loadRom(JNIEnv* env, jclass, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = nescore::Engine::instance().loadRom(cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_unload(JNIEnv*, jclass) {
    nescore::Engine::instance().unload();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_reset(JNIEnv*, jclass, jboolean hard) {
    nescore::Engine::instance().reset(hard == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_runFrame(JNIEnv*, jclass) {
    // ★★ v69 全核心金手指（NES/fceux）：帧前把补丁打到 CPU 总线
    //   （FCEU_CheatGetByte/SetByte —— 列表为空时零开销直通）。
    nescore::rom::applyCheatFrame();
    nescore::Engine::instance().runFrame();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_cheatSetList(JNIEnv* env, jclass, jintArray list) {
    coreshared::cheatSetList(env, list);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setPad1(JNIEnv*, jclass, jint bits) {
    nescore::Engine::instance().setPad1(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setPad2(JNIEnv*, jclass, jint bits) {
    nescore::Engine::instance().setPad2(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setRegion(JNIEnv*, jclass, jint region) {
    nescore::Engine::instance().setRegion(region);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setSampleRate(JNIEnv*, jclass, jint rate) {
    nescore::Engine::instance().setSampleRate(rate);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setFastForward(JNIEnv*, jclass, jint speed) {
    nescore::Engine::instance().setFastForward(speed);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_saveState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    nescore::Engine::instance().saveState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_loadState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = nescore::Engine::instance().loadState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NesNative_getFrameBuffer(JNIEnv* env, jclass, jintArray out) {
    if (!out) return JNI_FALSE;
    jsize len = env->GetArrayLength(out);
    if (len < 256 * 240) return JNI_FALSE;
    jint* px = env->GetIntArrayElements(out, nullptr);
    if (!px) return JNI_FALSE;
    bool fresh = nescore::Engine::instance().getFrameBuffer(
        reinterpret_cast<uint32_t*>(px), 256, 240);
    env->ReleaseIntArrayElements(out, px, 0); // commit back
    return fresh ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NesNative_readAudio(JNIEnv* env, jclass, jshortArray out) {
    if (!out) return 0;
    jsize len = env->GetArrayLength(out);
    if (len < 2) return 0;
    int maxFrames = len / 2;
    jshort* buf = env->GetShortArrayElements(out, nullptr);
    if (!buf) return 0;
    int n = nescore::Engine::instance().readAudio(
        reinterpret_cast<int16_t*>(buf), maxFrames);
    env->ReleaseShortArrayElements(out, buf, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NesNative_audioSampleRate(JNIEnv*, jclass) {
    return nescore::Engine::instance().audioSampleRate();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NesNative_audioTargetSampleRate(JNIEnv*, jclass) {
    return nescore::Engine::instance().audioTargetSampleRate();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setPaths(JNIEnv* env, jclass, jstring systemDir, jstring saveDir) {
    const char* sys = systemDir ? env->GetStringUTFChars(systemDir, nullptr) : nullptr;
    const char* sav = saveDir   ? env->GetStringUTFChars(saveDir,   nullptr) : nullptr;
    nescore::Engine::instance().setPaths(sys ? sys : "", sav ? sav : "");
    if (sys) env->ReleaseStringUTFChars(systemDir, sys);
    if (sav) env->ReleaseStringUTFChars(saveDir, sav);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setSaveName(JNIEnv* env, jclass, jstring name) {
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    nescore::Engine::instance().setSaveName(n ? n : "");
    if (n) env->ReleaseStringUTFChars(name, n);
}

JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_NesNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(nescore::Engine::instance().lastError().c_str());
}

// --- Hardware-accelerated rendering ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setSurface(JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* win = nullptr;
    if (surface) {
        win = ANativeWindow_fromSurface(env, surface);
    }
    nescore::rom::setSurface(win);
    // ANativeWindow_fromSurface returns a new reference; setSurface acquires it.
    // Release our local reference so we don't leak.
    if (win) ANativeWindow_release(win);
}

// --- Core options ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setCoreOption(JNIEnv* env, jclass, jstring key, jstring value) {
    const char* ckey = key ? env->GetStringUTFChars(key, nullptr) : nullptr;
    const char* cval = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
    nescore::Engine::instance().setCoreOption(ckey ? ckey : "", cval ? cval : "");
    if (ckey) env->ReleaseStringUTFChars(key, ckey);
    if (cval) env->ReleaseStringUTFChars(value, cval);
}

// --- Video geometry ---

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NesNative_videoWidth(JNIEnv*, jclass) {
    return nescore::Engine::instance().videoWidth();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NesNative_videoHeight(JNIEnv*, jclass) {
    return nescore::Engine::instance().videoHeight();
}

// --- Video filter ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setVideoFilter(JNIEnv*, jclass, jint filter) {
    nescore::Engine::instance().setVideoFilter(filter);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NesNative_setHighQualityScaling(JNIEnv*, jclass, jboolean enabled) {
    nescore::Engine::instance().setHighQualityScaling(enabled);
}

} // extern "C"
