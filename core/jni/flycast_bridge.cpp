// SPDX-License-Identifier: MIT
// JNI bridge for the Flycast (Dreamcast / Naomi / Atomiswave) core —
// mirrors FlycastNative.kt exactly. See flycast_loader.h / flycast_bridge.h.

#include "flycast_bridge.h"
#include "flycast_loader.h"

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <algorithm>
#include <cmath>
#include <cstring>

#define TAG "flycastcore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace flycastcore {

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
void Engine::reset(bool h)  { rom::resetEmulation(h); }
void Engine::runFrame()     { rom::stepFrame(); }
void Engine::shutdown()     { rom::unload(); }

void Engine::setPad1(int bits)        { rom::setControllerInput(0, (uint16_t)bits); }
void Engine::setPad2(int bits)        { rom::setControllerInput(1, (uint16_t)bits); }
void Engine::setPad3(int bits)        { rom::setControllerInput(2, (uint16_t)bits); }
void Engine::setPad4(int bits)        { rom::setControllerInput(3, (uint16_t)bits); }

void Engine::setAnalogAxis(int port, int axis, int16_t value) {
    rom::setAnalogAxis(port, axis, value);
}

void Engine::setRegion(int region)   { rom::applyRegion(region); }
void Engine::setSampleRate(int hz)   { rom::applySampleRate(hz); }
void Engine::setFastForward(int speed) { rom::applySpeed(speed > 0 ? (float)speed : 1.0f); }

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

void Engine::requestFrameReadback() { rom::requestFrameReadback(); }

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
    // Stub — the JNI wrapper below performs ANativeWindow extraction.
}

void Engine::setCoreOption(const std::string& key, const std::string& value) {
    rom::setCoreOption(key, value);
}

int Engine::videoWidth()  { return rom::videoWidth(); }
int Engine::videoHeight() { return rom::videoHeight(); }

void Engine::setVideoFilter(int filter) { rom::setVideoFilter(filter); }
void Engine::setHighQualityScaling(bool enabled) { rom::setHighQualityScaling(enabled); }

} // namespace flycastcore

// ---------------------------------------------------------------------------
// JNI surface — mirrors FlycastNative.kt exactly
// ---------------------------------------------------------------------------

static JavaVM* s_jvm = nullptr;

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    s_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_loadRom(JNIEnv* env, jclass, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    // Copy so the path outlives ReleaseStringUTFChars — the core keeps
    // game_info.path open (disc images / romsets are opened by name).
    std::string pathStr(cpath ? cpath : "");
    env->ReleaseStringUTFChars(path, cpath);
    bool ok = flycastcore::Engine::instance().loadRom(pathStr);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_unload(JNIEnv*, jclass) {
    flycastcore::Engine::instance().unload();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_reset(JNIEnv*, jclass, jboolean hard) {
    flycastcore::Engine::instance().reset(hard == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_runFrame(JNIEnv*, jclass) {
    flycastcore::Engine::instance().runFrame();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setPad1(JNIEnv*, jclass, jint bits) {
    flycastcore::Engine::instance().setPad1(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setPad2(JNIEnv*, jclass, jint bits) {
    flycastcore::Engine::instance().setPad2(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setPad3(JNIEnv*, jclass, jint bits) {
    flycastcore::Engine::instance().setPad3(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setPad4(JNIEnv*, jclass, jint bits) {
    flycastcore::Engine::instance().setPad4(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setAnalogAxis(JNIEnv*, jclass,
                                                             jint port, jint axis, jshort value) {
    flycastcore::Engine::instance().setAnalogAxis((int)port, (int)axis, (int16_t)value);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setRegion(JNIEnv*, jclass, jint region) {
    flycastcore::Engine::instance().setRegion(region);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setSampleRate(JNIEnv*, jclass, jint rate) {
    flycastcore::Engine::instance().setSampleRate(rate);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setFastForward(JNIEnv*, jclass, jint speed) {
    flycastcore::Engine::instance().setFastForward(speed);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setControllerDevice(JNIEnv*, jclass, jint port, jint device) {
    flycastcore::Engine::instance().setPortDevice((int)port, (int)device);
}

JNIEXPORT jdouble JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_videoFps(JNIEnv*, jclass) {
    return (jdouble)flycastcore::Engine::instance().videoRefreshRate();
}

// FPS HUD：读走并清零自上次轮询以来核心真实提交的帧数（video_cb VALID）。
JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_pollPresentedFrames(JNIEnv*, jclass) {
    return (jint)flycastcore::Engine::instance().pollPresentedFrames();
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_saveState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    flycastcore::Engine::instance().saveState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_loadState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = flycastcore::Engine::instance().loadState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_getFrameBuffer(JNIEnv* env, jclass, jintArray out) {
    if (!out) return JNI_FALSE;
    int vw = flycastcore::Engine::instance().videoWidth();
    int vh = flycastcore::Engine::instance().videoHeight();
    if (vw <= 0 || vh <= 0) { vw = 640; vh = 480; }
    jsize len = env->GetArrayLength(out);
    // If the caller's buffer is smaller than the core's frame (e.g. the
    // fixed preview buffer at >2x internal resolution), clamp the copy dims
    // proportionally so we never silently fail — the readback itself is
    // capped to the same 2560x1920 bound as the FBO.
    if ((long long)vw * vh > (long long)len) {
        const double scale = std::sqrt((double)len / ((double)vw * (double)vh));
        vw = std::max(1, (int)((double)vw * scale));
        vh = std::max(1, (int)((double)vh * scale));
        if ((long long)vw * vh > (long long)len) { vw = 1; vh = len; }
    }
    jint* px = env->GetIntArrayElements(out, nullptr);
    if (!px) return JNI_FALSE;
    bool fresh = flycastcore::Engine::instance().getFrameBuffer(
        reinterpret_cast<uint32_t*>(px), vw, vh);
    env->ReleaseIntArrayElements(out, px, 0);
    return fresh ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_requestFrameReadback(JNIEnv*, jclass) {
    flycastcore::Engine::instance().requestFrameReadback();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_readAudio(JNIEnv* env, jclass, jshortArray out) {
    if (!out) return 0;
    jsize len = env->GetArrayLength(out);
    if (len < 2) return 0;
    int maxFrames = len / 2;
    jshort* buf = env->GetShortArrayElements(out, nullptr);
    if (!buf) return 0;
    int n = flycastcore::Engine::instance().readAudio(
        reinterpret_cast<int16_t*>(buf), maxFrames);
    env->ReleaseShortArrayElements(out, buf, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_audioSampleRate(JNIEnv*, jclass) {
    return flycastcore::Engine::instance().audioSampleRate();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_audioTargetSampleRate(JNIEnv*, jclass) {
    return flycastcore::Engine::instance().audioTargetSampleRate();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setPaths(JNIEnv* env, jclass, jstring systemDir, jstring saveDir) {
    const char* sys = systemDir ? env->GetStringUTFChars(systemDir, nullptr) : nullptr;
    const char* sav = saveDir   ? env->GetStringUTFChars(saveDir,   nullptr) : nullptr;
    flycastcore::Engine::instance().setPaths(sys ? sys : "", sav ? sav : "");
    if (sys) env->ReleaseStringUTFChars(systemDir, sys);
    if (sav) env->ReleaseStringUTFChars(saveDir, sav);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setSaveName(JNIEnv* env, jclass, jstring name) {
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    flycastcore::Engine::instance().setSaveName(n ? n : "");
    if (n) env->ReleaseStringUTFChars(name, n);
}

JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(flycastcore::Engine::instance().lastError().c_str());
}

// --- Hardware-accelerated rendering (EGL, emu thread) ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setSurface(JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* win = nullptr;
    if (surface) {
        win = ANativeWindow_fromSurface(env, surface);
    }
    flycastcore::rom::setSurface(win);
    if (win) ANativeWindow_release(win);
}

// --- Core options ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setCoreOption(JNIEnv* env, jclass, jstring key, jstring value) {
    const char* ckey = key ? env->GetStringUTFChars(key, nullptr) : nullptr;
    const char* cval = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
    flycastcore::Engine::instance().setCoreOption(ckey ? ckey : "", cval ? cval : "");
    if (ckey) env->ReleaseStringUTFChars(key, ckey);
    if (cval) env->ReleaseStringUTFChars(value, cval);
}

// --- Video geometry ---

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_videoWidth(JNIEnv*, jclass) {
    return flycastcore::Engine::instance().videoWidth();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_videoHeight(JNIEnv*, jclass) {
    return flycastcore::Engine::instance().videoHeight();
}

// --- Video filter / scaling (interface parity — no-ops on the GPU path) ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setVideoFilter(JNIEnv*, jclass, jint filter) {
    flycastcore::Engine::instance().setVideoFilter(filter);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setHighQualityScaling(JNIEnv*, jclass, jboolean enabled) {
    flycastcore::Engine::instance().setHighQualityScaling(enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_isCoreLibLoaded(JNIEnv*, jclass) {
    return flycastcore::rom::isCoreLoaded() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_isHwRenderAvailable(JNIEnv*, jclass) {
    return flycastcore::rom::isHwRenderAvailable() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_FlycastNative_setCoreLibPath(JNIEnv* env, jclass, jstring path) {
    const char* cpath = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    flycastcore::rom::setCoreLibPath(cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
}

} // extern "C"
