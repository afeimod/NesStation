// SPDX-License-Identifier: MIT
// JNI bridge for the Sony PSP core (libretro PPSSPP port).
//
// Thin wrapper around the libretro frontend in psp_loader.cpp. Kotlin
// owns the emulation thread and pulls frames / audio on demand — same
// pull-model as DcNative / PsxNative. JNI method names follow the
// Java_com_nesstation_app_core_jni_PspNative_* convention (the Kotlin
// side loads libpspcore.so via PspNative.ensureLoaded()).
//
// ★★ 集成架构说明（v3 — libretro 化重写）★★
// 旧版此文件驱动官方 libppsspp_jni.so 的 JNI 契约（boot(21 参) /
// registerCallbacks / runVulkanRenderLoop / keyDown / onTouch ...）——
// 现已整体废弃。新版与 dc_bridge.cpp 完全同构：libretro 核心 +
// EGL HW render + 标准 JOYPAD 输入 + retro_serialize 即时存档，
// 无任何 Java 契约类依赖（org.ppsspp.ppsspp 包已删除）。

#include "psp_bridge.h"
#include "psp_loader.h"

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>

#define TAG "pspcore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace pspcore {

// Called by the JNI layer with the caller's env — see setSurface.
void Engine_setSurfaceFromJni(JNIEnv* env, jobject surface);

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
    LOGI("PSP ROM loaded OK: %s (region=%d, rate=%d, %dx%d)",
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
void Engine::setAnalogAxes(int port, int lx, int ly, int rx, int ry) {
    rom::setControllerAnalog(port, (int16_t)lx, (int16_t)ly, (int16_t)rx, (int16_t)ry);
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

bool Engine::captureFrame(uint32_t* out, int w, int h) {
    // Schedule a fresh glReadPixels on the emulation thread, wait briefly
    // for it to land, then copy. Falls back to the last captured frame when
    // the emulation thread is not running (paused / no frames yet).
    rom::requestFrameCapture();
    rom::waitForCapture(500);
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

void Engine::setSurface(jobject surface) {
    // No-op in the Engine facade — surface binding is handled directly by
    // the JNI layer (PspNative_setSurface below) because ANativeWindow needs
    // the calling thread's JNIEnv.
}

void Engine_setSurfaceFromJni(JNIEnv* env, jobject surface) {
    ANativeWindow* win = nullptr;
    if (surface) {
        win = ANativeWindow_fromSurface(env, surface);
    }
    rom::setSurface(win);
    if (win) ANativeWindow_release(win);
}

void Engine::setCoreOption(const std::string& key, const std::string& value) {
    rom::setCoreOption(key, value);
}

int Engine::videoWidth()  { return rom::videoWidth(); }
int Engine::videoHeight() { return rom::videoHeight(); }

void Engine::setVideoFilter(int filter) { rom::setVideoFilter(filter); }
void Engine::setHighQualityScaling(bool enabled) { rom::setHighQualityScaling(enabled); }

} // namespace pspcore

// ---------------------------------------------------------------------------
// JNI surface — mirrors DcNative.kt (see dc_bridge.cpp for the reference).
// ---------------------------------------------------------------------------

static JavaVM* s_jvm = nullptr;

jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    s_jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PspNative_loadRom(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return JNI_FALSE;
    bool ok = pspcore::Engine::instance().loadRom(p);
    env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_unload(JNIEnv*, jobject) {
    pspcore::Engine::instance().unload();
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_reset(JNIEnv*, jobject, jboolean hard) {
    pspcore::Engine::instance().reset(hard);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_runFrame(JNIEnv*, jobject) {
    pspcore::Engine::instance().runFrame();
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setPad1(JNIEnv*, jobject, jint bits) {
    pspcore::Engine::instance().setPad1(bits);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setPad2(JNIEnv*, jobject, jint bits) {
    pspcore::Engine::instance().setPad2(bits);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setPad3(JNIEnv*, jobject, jint bits) {
    pspcore::Engine::instance().setPad3(bits);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setPad4(JNIEnv*, jobject, jint bits) {
    pspcore::Engine::instance().setPad4(bits);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setRegion(JNIEnv*, jobject, jint region) {
    pspcore::Engine::instance().setRegion(region);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setSampleRate(JNIEnv*, jobject, jint rate) {
    pspcore::Engine::instance().setSampleRate(rate);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setFastForward(JNIEnv*, jobject, jint speed) {
    pspcore::Engine::instance().setFastForward(speed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setControllerDevice(JNIEnv*, jobject, jint port, jint device) {
    pspcore::Engine::instance().setPortDevice(port, device);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setAnalogAxes(JNIEnv*, jobject,
                                                         jint port, jint lx, jint ly, jint rx, jint ry) {
    pspcore::Engine::instance().setAnalogAxes(port, lx, ly, rx, ry);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_nesstation_app_core_jni_PspNative_videoFps(JNIEnv*, jobject) {
    return pspcore::Engine::instance().videoRefreshRate();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_PspNative_pollPresentedFrames(JNIEnv*, jobject) {
    return pspcore::Engine::instance().pollPresentedFrames();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PspNative_saveState(JNIEnv* env, jobject, jint slot, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return JNI_FALSE;
    pspcore::Engine::instance().saveState(slot, p);
    env->ReleaseStringUTFChars(path, p);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PspNative_loadState(JNIEnv* env, jobject, jint slot, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return JNI_FALSE;
    bool ok = pspcore::Engine::instance().loadState(slot, p);
    env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PspNative_getFrameBuffer(JNIEnv* env, jobject, jintArray out) {
    if (out == nullptr) return JNI_FALSE;
    jsize len = env->GetArrayLength(out);
    jint* buf = env->GetIntArrayElements(out, nullptr);
    if (!buf) return JNI_FALSE;
    // Frame geometry is passed implicitly: the Kotlin side always allocates
    // w*h and videoWidth/videoHeight are queried separately.
    const int total = len;
    int w = pspcore::Engine::instance().videoWidth();
    int h = pspcore::Engine::instance().videoHeight();
    if (w <= 0 || h <= 0 || w * h > total) {
        w = 480; h = 272;
        if (w * h > total) { w = total; h = 1; }
    }
    bool ok = pspcore::Engine::instance().getFrameBuffer(
        reinterpret_cast<uint32_t*>(buf), w, h);
    env->ReleaseIntArrayElements(out, buf, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PspNative_captureFrame(JNIEnv* env, jobject, jintArray out) {
    if (out == nullptr) return JNI_FALSE;
    jsize len = env->GetArrayLength(out);
    jint* buf = env->GetIntArrayElements(out, nullptr);
    if (!buf) return JNI_FALSE;
    int w = pspcore::Engine::instance().videoWidth();
    int h = pspcore::Engine::instance().videoHeight();
    if (w <= 0 || h <= 0 || w * h > len) {
        w = 480; h = 272;
        if (w * h > len) { w = len; h = 1; }
    }
    bool ok = pspcore::Engine::instance().captureFrame(
        reinterpret_cast<uint32_t*>(buf), w, h);
    env->ReleaseIntArrayElements(out, buf, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_PspNative_readAudio(JNIEnv* env, jobject, jshortArray out) {
    if (out == nullptr) return 0;
    jsize len = env->GetArrayLength(out);
    jshort* buf = env->GetShortArrayElements(out, nullptr);
    if (!buf) return 0;
    int frames = pspcore::Engine::instance().readAudio(
        reinterpret_cast<int16_t*>(buf), len / 2);
    env->ReleaseShortArrayElements(out, buf, 0);
    return frames;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_PspNative_audioSampleRate(JNIEnv*, jobject) {
    return pspcore::Engine::instance().audioSampleRate();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_PspNative_audioTargetSampleRate(JNIEnv*, jobject) {
    return pspcore::Engine::instance().audioTargetSampleRate();
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setPaths(JNIEnv* env, jobject, jstring systemDir, jstring saveDir) {
    const char* sys = env->GetStringUTFChars(systemDir, nullptr);
    const char* sav = env->GetStringUTFChars(saveDir, nullptr);
    if (sys && sav) pspcore::Engine::instance().setPaths(sys, sav);
    if (sys) env->ReleaseStringUTFChars(systemDir, sys);
    if (sav) env->ReleaseStringUTFChars(saveDir, sav);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setSaveName(JNIEnv* env, jobject, jstring name) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    if (!n) return;
    pspcore::Engine::instance().setSaveName(n);
    env->ReleaseStringUTFChars(name, n);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setCoreLibPath(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return;
    pspcore::rom::setCoreLibPath(p);
    env->ReleaseStringUTFChars(path, p);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setCoreOption(JNIEnv* env, jobject, jstring key, jstring value) {
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    if (k && v) pspcore::Engine::instance().setCoreOption(k, v);
    if (k) env->ReleaseStringUTFChars(key, k);
    if (v) env->ReleaseStringUTFChars(value, v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setSurface(JNIEnv* env, jobject, jobject surface) {
    pspcore::Engine_setSurfaceFromJni(env, surface);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setVideoFilter(JNIEnv*, jobject, jint filter) {
    pspcore::Engine::instance().setVideoFilter(filter);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_PspNative_setHighQualityScaling(JNIEnv*, jobject, jboolean enabled) {
    pspcore::Engine::instance().setHighQualityScaling(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_PspNative_videoWidth(JNIEnv*, jobject) {
    return pspcore::Engine::instance().videoWidth();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_PspNative_videoHeight(JNIEnv*, jobject) {
    return pspcore::Engine::instance().videoHeight();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_PspNative_lastError(JNIEnv* env, jobject) {
    std::string err = pspcore::Engine::instance().lastError();
    return env->NewStringUTF(err.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_PspNative_isCoreLibLoaded(JNIEnv*, jobject) {
    return pspcore::rom::isCoreLoaded() ? JNI_TRUE : JNI_FALSE;
}
