// SPDX-License-Identifier: MIT
// JNI bridge for PCSX-ReARMed (PlayStation 1) core.
//
// Thin wrapper around the libretro frontend in psx_loader.cpp. Kotlin
// owns the emulation thread and pulls frames / audio on demand — same
// pull-model as the other engines.

#include "psx_bridge.h"
#include "psx_loader.h"

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>

#define TAG "psxcore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace psxcore {

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
    LOGI("PS1 ROM loaded OK: %s (region=%d, rate=%d, %dx%d)",
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
void Engine::setPad3(int bits)        { rom::setControllerInput(2, (uint16_t)bits); }
void Engine::setPad4(int bits)        { rom::setControllerInput(3, (uint16_t)bits); }
// ★★ v60 DualShock 模拟轴（port 0 / 1P）
void Engine::setAnalog1(int lx, int ly, int rx, int ry) {
    rom::setControllerAnalog(0, lx, ly, rx, ry);
}
void Engine::setRegion(int region)    { rom::applyRegion(region); }
void Engine::setSampleRate(int hz)    { rom::applySampleRate(hz); }
void Engine::setFastForward(int speed)  { rom::applySpeed(speed > 0 ? (float)speed : 1.0f); }

void Engine::setPortDevice(int port, int device) { rom::setPortDevice(port, device); }
double Engine::videoRefreshRate() { return rom::videoRefreshRate(); }
int Engine::pollPresentedFrames() { return rom::pollPresentedFrames(); }

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

// ★ 分辨率倍数（前端整数放大）—— UI 可运行中热切换，下一帧生效。
void Engine::setResolutionScale(int scale) { rom::setResolutionScale(scale); }

} // namespace psxcore

// ---------------------------------------------------------------------------
// JNI surface — mirrors PsxNative.kt exactly
// ---------------------------------------------------------------------------

static JavaVM* s_jvm = nullptr;

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    s_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PsxNative_loadRom(JNIEnv* env, jclass, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    // Copy into a std::string so the data outlives ReleaseStringUTFChars.
    // psx_loader.cpp stores this in s_romPath and uses it as gameInfo.path.
    std::string pathStr(cpath ? cpath : "");
    env->ReleaseStringUTFChars(path, cpath);
    bool ok = psxcore::Engine::instance().loadRom(pathStr);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_unload(JNIEnv*, jclass) {
    psxcore::Engine::instance().unload();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_reset(JNIEnv*, jclass, jboolean hard) {
    psxcore::Engine::instance().reset(hard == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_runFrame(JNIEnv*, jclass) {
    psxcore::Engine::instance().runFrame();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setPad1(JNIEnv*, jclass, jint bits) {
    psxcore::Engine::instance().setPad1(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setPad2(JNIEnv*, jclass, jint bits) {
    psxcore::Engine::instance().setPad2(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setPad3(JNIEnv*, jclass, jint bits) {
    psxcore::Engine::instance().setPad3(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setPad4(JNIEnv*, jclass, jint bits) {
    psxcore::Engine::instance().setPad4(bits);
}

// ★★ v60 DualShock 模拟轴（1P：lx/ly = 左杆，rx/ry = 右杆，int16）
JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setAnalog1(JNIEnv*, jclass,
                                                     jint lx, jint ly,
                                                     jint rx, jint ry) {
    psxcore::Engine::instance().setAnalog1(lx, ly, rx, ry);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setRegion(JNIEnv*, jclass, jint region) {
    psxcore::Engine::instance().setRegion(region);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setSampleRate(JNIEnv*, jclass, jint rate) {
    psxcore::Engine::instance().setSampleRate(rate);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setFastForward(JNIEnv*, jclass, jint speed) {
    psxcore::Engine::instance().setFastForward(speed);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setControllerDevice(JNIEnv*, jclass, jint port, jint device) {
    psxcore::Engine::instance().setPortDevice((int)port, (int)device);
}

JNIEXPORT jdouble JNICALL
Java_com_nesstation_app_core_jni_PsxNative_videoFps(JNIEnv*, jclass) {
    return (jdouble)psxcore::Engine::instance().videoRefreshRate();
}

// FPS HUD：读走并清零自上次轮询以来核心真实提交的帧数（cb_video 非空）。
JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_PsxNative_pollPresentedFrames(JNIEnv*, jclass) {
    return (jint)psxcore::Engine::instance().pollPresentedFrames();
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PsxNative_saveState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    psxcore::Engine::instance().saveState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PsxNative_loadState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = psxcore::Engine::instance().loadState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PsxNative_getFrameBuffer(JNIEnv* env, jclass, jintArray out) {
    if (!out) return JNI_FALSE;
    int vw = psxcore::Engine::instance().videoWidth();
    int vh = psxcore::Engine::instance().videoHeight();
    if (vw <= 0 || vh <= 0) { vw = 320; vh = 240; }
    jsize len = env->GetArrayLength(out);
    if (len < vw * vh) return JNI_FALSE;
    jint* px = env->GetIntArrayElements(out, nullptr);
    if (!px) return JNI_FALSE;
    bool fresh = psxcore::Engine::instance().getFrameBuffer(
        reinterpret_cast<uint32_t*>(px), vw, vh);
    env->ReleaseIntArrayElements(out, px, 0);
    return fresh ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_PsxNative_readAudio(JNIEnv* env, jclass, jshortArray out) {
    if (!out) return 0;
    jsize len = env->GetArrayLength(out);
    if (len < 2) return 0;
    int maxFrames = len / 2;
    jshort* buf = env->GetShortArrayElements(out, nullptr);
    if (!buf) return 0;
    int n = psxcore::Engine::instance().readAudio(
        reinterpret_cast<int16_t*>(buf), maxFrames);
    env->ReleaseShortArrayElements(out, buf, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_PsxNative_audioSampleRate(JNIEnv*, jclass) {
    return psxcore::Engine::instance().audioSampleRate();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_PsxNative_audioTargetSampleRate(JNIEnv*, jclass) {
    return psxcore::Engine::instance().audioTargetSampleRate();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setPaths(JNIEnv* env, jclass, jstring systemDir, jstring saveDir) {
    const char* sys = systemDir ? env->GetStringUTFChars(systemDir, nullptr) : nullptr;
    const char* sav = saveDir   ? env->GetStringUTFChars(saveDir,   nullptr) : nullptr;
    psxcore::Engine::instance().setPaths(sys ? sys : "", sav ? sav : "");
    if (sys) env->ReleaseStringUTFChars(systemDir, sys);
    if (sav) env->ReleaseStringUTFChars(saveDir, sav);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setSaveName(JNIEnv* env, jclass, jstring name) {
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    psxcore::Engine::instance().setSaveName(n ? n : "");
    if (n) env->ReleaseStringUTFChars(name, n);
}

JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_PsxNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(psxcore::Engine::instance().lastError().c_str());
}

// --- Hardware-accelerated rendering ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setSurface(JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* win = nullptr;
    if (surface) {
        win = ANativeWindow_fromSurface(env, surface);
    }
    psxcore::rom::setSurface(win);
    if (win) ANativeWindow_release(win);
}

// --- Core options ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setCoreOption(JNIEnv* env, jclass, jstring key, jstring value) {
    const char* ckey = key ? env->GetStringUTFChars(key, nullptr) : nullptr;
    const char* cval = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
    psxcore::Engine::instance().setCoreOption(ckey ? ckey : "", cval ? cval : "");
    if (ckey) env->ReleaseStringUTFChars(key, ckey);
    if (cval) env->ReleaseStringUTFChars(value, cval);
}

// --- Video geometry ---

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_PsxNative_videoWidth(JNIEnv*, jclass) {
    return psxcore::Engine::instance().videoWidth();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_PsxNative_videoHeight(JNIEnv*, jclass) {
    return psxcore::Engine::instance().videoHeight();
}

// --- Video filter ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setVideoFilter(JNIEnv*, jclass, jint filter) {
    psxcore::Engine::instance().setVideoFilter(filter);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setHighQualityScaling(JNIEnv*, jclass, jboolean enabled) {
    psxcore::Engine::instance().setHighQualityScaling(enabled);
}

// ★ 分辨率倍数（前端整数放大，1..4）—— 运行中热切换，下一帧生效。
JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setResolutionScale(JNIEnv*, jclass, jint scale) {
    psxcore::Engine::instance().setResolutionScale(scale);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PsxNative_isCoreLibLoaded(JNIEnv*, jclass) {
    return psxcore::rom::isCoreLoaded() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PsxNative_setCoreLibPath(JNIEnv* env, jclass, jstring path) {
    const char* cpath = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    psxcore::rom::setCoreLibPath(cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
}

} // extern "C"
