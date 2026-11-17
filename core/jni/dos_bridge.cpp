// SPDX-License-Identifier: MIT
// JNI bridge for DOSBox-Pure core (DOS/PC emulator).
//
// Thin wrapper around the libretro frontend in dos_loader.cpp. Kotlin owns
// the emulation thread and pulls frames / audio on demand — same pull-model
// as the NES / SNES / GBA bridges.
//
// Key difference: DOSBox requires full keyboard + mouse input. The bridge
// therefore exposes both setPad1(int) for the standard gamepad and dedicated
// injectKeyDown/Up / injectMouseMove / injectMouseButton functions for
// keyboard and mouse events.

#include "dos_bridge.h"
#include "dos_loader.h"

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>

#define TAG "doscore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace doscore {

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
    LOGI("DOS game loaded OK: %s (rate=%d, %dx%d)",
         path.c_str(), rom::audioSampleRate(),
         rom::videoWidth(), rom::videoHeight());
    return true;
}

void Engine::unload()       { rom::unload(); }
void Engine::reset(bool h)  { rom::resetEmulation(h); }
void Engine::runFrame()     { rom::stepFrame(); }
void Engine::shutdown()     { rom::unload(); }

void Engine::setPad1(int bits) {
    rom::setControllerInput(0, (uint16_t)bits);
}

void Engine::injectKeyDown(int keyCode, int modifiers) {
    rom::keyboardDown(keyCode, modifiers);
}

void Engine::injectKeyUp(int keyCode, int modifiers) {
    rom::keyboardUp(keyCode, modifiers);
}

void Engine::injectMouseMove(int dx, int dy) {
    rom::mouseMove(dx, dy);
}

void Engine::injectMouseButton(int button, bool pressed) {
    rom::mouseButton(button, pressed);
}

void Engine::setInputDeviceMode(int mode) {
    rom::setInputDeviceMode(mode);
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

} // namespace doscore

// ---------------------------------------------------------------------------
// JNI surface — mirrors DosNative.kt exactly
// ---------------------------------------------------------------------------

static JavaVM* s_jvm = nullptr;

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    s_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_DosNative_loadRom(JNIEnv* env, jclass, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = doscore::Engine::instance().loadRom(cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_unload(JNIEnv*, jclass) {
    doscore::Engine::instance().unload();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_reset(JNIEnv*, jclass, jboolean hard) {
    doscore::Engine::instance().reset(hard == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_runFrame(JNIEnv*, jclass) {
    doscore::Engine::instance().runFrame();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_setPad1(JNIEnv*, jclass, jint bits) {
    doscore::Engine::instance().setPad1(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_setPad2(JNIEnv*, jclass, jint bits) {
    doscore::Engine::instance().setPad2(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_injectKeyDown(JNIEnv*, jclass, jint keyCode, jint modifiers) {
    doscore::Engine::instance().injectKeyDown(keyCode, modifiers);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_injectKeyUp(JNIEnv*, jclass, jint keyCode, jint modifiers) {
    doscore::Engine::instance().injectKeyUp(keyCode, modifiers);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_injectMouseMove(JNIEnv*, jclass, jint dx, jint dy) {
    doscore::Engine::instance().injectMouseMove(dx, dy);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_injectMouseButton(JNIEnv*, jclass, jint button, jboolean pressed) {
    doscore::Engine::instance().injectMouseButton(button, pressed == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_setInputDeviceMode(JNIEnv*, jclass, jint mode) {
    doscore::Engine::instance().setInputDeviceMode(mode);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_setRegion(JNIEnv*, jclass, jint region) {
    doscore::Engine::instance().setRegion(region);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_setSampleRate(JNIEnv*, jclass, jint rate) {
    doscore::Engine::instance().setSampleRate(rate);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_setFastForward(JNIEnv*, jclass, jint speed) {
    doscore::Engine::instance().setFastForward(speed);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_DosNative_saveState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    doscore::Engine::instance().saveState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_DosNative_loadState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = doscore::Engine::instance().loadState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_DosNative_getFrameBuffer(JNIEnv* env, jclass, jintArray out) {
    if (!out) return JNI_FALSE;
    int w = doscore::Engine::instance().videoWidth();
    int h = doscore::Engine::instance().videoHeight();
    if (w <= 0 || h <= 0) return JNI_FALSE;
    jsize len = env->GetArrayLength(out);
    if (len < w * h) return JNI_FALSE;
    jint* px = env->GetIntArrayElements(out, nullptr);
    if (!px) return JNI_FALSE;
    bool fresh = doscore::Engine::instance().getFrameBuffer(
        reinterpret_cast<uint32_t*>(px), w, h);
    env->ReleaseIntArrayElements(out, px, 0);
    return fresh ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_DosNative_readAudio(JNIEnv* env, jclass, jshortArray out) {
    if (!out) return 0;
    jsize len = env->GetArrayLength(out);
    if (len < 2) return 0;
    int maxFrames = len / 2;
    jshort* buf = env->GetShortArrayElements(out, nullptr);
    if (!buf) return 0;
    int n = doscore::Engine::instance().readAudio(
        reinterpret_cast<int16_t*>(buf), maxFrames);
    env->ReleaseShortArrayElements(out, buf, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_DosNative_audioSampleRate(JNIEnv*, jclass) {
    return doscore::Engine::instance().audioSampleRate();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_DosNative_audioTargetSampleRate(JNIEnv*, jclass) {
    return doscore::Engine::instance().audioTargetSampleRate();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_setPaths(JNIEnv* env, jclass, jstring systemDir, jstring saveDir) {
    const char* sys = systemDir ? env->GetStringUTFChars(systemDir, nullptr) : nullptr;
    const char* sav = saveDir   ? env->GetStringUTFChars(saveDir,   nullptr) : nullptr;
    doscore::Engine::instance().setPaths(sys ? sys : "", sav ? sav : "");
    if (sys) env->ReleaseStringUTFChars(systemDir, sys);
    if (sav) env->ReleaseStringUTFChars(saveDir, sav);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_setSaveName(JNIEnv* env, jclass, jstring name) {
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    doscore::Engine::instance().setSaveName(n ? n : "");
    if (n) env->ReleaseStringUTFChars(name, n);
}

JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_DosNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(doscore::Engine::instance().lastError().c_str());
}

// --- Hardware-accelerated rendering ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_setSurface(JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* win = nullptr;
    if (surface) {
        win = ANativeWindow_fromSurface(env, surface);
    }
    doscore::rom::setSurface(win);
    if (win) ANativeWindow_release(win);
}

// --- Core options ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_setCoreOption(JNIEnv* env, jclass, jstring key, jstring value) {
    const char* ckey = key ? env->GetStringUTFChars(key, nullptr) : nullptr;
    const char* cval = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
    doscore::Engine::instance().setCoreOption(ckey ? ckey : "", cval ? cval : "");
    if (ckey) env->ReleaseStringUTFChars(key, ckey);
    if (cval) env->ReleaseStringUTFChars(value, cval);
}

// --- Video geometry ---

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_DosNative_videoWidth(JNIEnv*, jclass) {
    return doscore::Engine::instance().videoWidth();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_DosNative_videoHeight(JNIEnv*, jclass) {
    return doscore::Engine::instance().videoHeight();
}

// --- Video filter ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_setVideoFilter(JNIEnv*, jclass, jint filter) {
    doscore::Engine::instance().setVideoFilter(filter);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_setHighQualityScaling(JNIEnv*, jclass, jboolean enabled) {
    doscore::Engine::instance().setHighQualityScaling(enabled);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_DosNative_isCoreLibLoaded(JNIEnv*, jclass) {
    return doscore::rom::isCoreLoaded() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DosNative_setCoreLibPath(JNIEnv* env, jclass, jstring path) {
    const char* cpath = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    doscore::rom::setCoreLibPath(cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
}

} // extern "C"
