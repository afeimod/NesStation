// SPDX-License-Identifier: MIT
// JNI bridge for the SEGA Dreamcast / NAOMI / AtomisWave core (libretro
// Flycast).
//
// Thin wrapper around the libretro frontend in dc_loader.cpp. Kotlin
// owns the emulation thread and pulls frames / audio on demand — same
// pull-model as the other engines. JNI method names follow the
// Java_com_nesstation_app_core_jni_DcNative_* convention (the Kotlin
// side loads libdccore.so via DcNative.ensureLoaded()).

#include "dc_bridge.h"
#include "dc_loader.h"

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>

#define TAG "dccore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace dccore {

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
    LOGI("DC ROM loaded OK: %s (region=%d, rate=%d, %dx%d)",
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
    // the JNI layer (DcNative_setSurface below) because ANativeWindow needs
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

} // namespace dccore

// ---------------------------------------------------------------------------
// JNI surface — mirrors PsxNative.kt (see psx_bridge.cpp for the reference).
// ---------------------------------------------------------------------------

static JavaVM* s_jvm = nullptr;

jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    s_jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_DcNative_loadRom(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return JNI_FALSE;
    bool ok = dccore::Engine::instance().loadRom(p);
    env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_unload(JNIEnv*, jobject) {
    dccore::Engine::instance().unload();
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_reset(JNIEnv*, jobject, jboolean hard) {
    dccore::Engine::instance().reset(hard);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_runFrame(JNIEnv*, jobject) {
    dccore::Engine::instance().runFrame();
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setPad1(JNIEnv*, jobject, jint bits) {
    dccore::Engine::instance().setPad1(bits);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setPad2(JNIEnv*, jobject, jint bits) {
    dccore::Engine::instance().setPad2(bits);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setPad3(JNIEnv*, jobject, jint bits) {
    dccore::Engine::instance().setPad3(bits);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setPad4(JNIEnv*, jobject, jint bits) {
    dccore::Engine::instance().setPad4(bits);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setRegion(JNIEnv*, jobject, jint region) {
    dccore::Engine::instance().setRegion(region);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setSampleRate(JNIEnv*, jobject, jint rate) {
    dccore::Engine::instance().setSampleRate(rate);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setFastForward(JNIEnv*, jobject, jint speed) {
    dccore::Engine::instance().setFastForward(speed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setControllerDevice(JNIEnv*, jobject, jint port, jint device) {
    dccore::Engine::instance().setPortDevice(port, device);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setAnalogAxes(JNIEnv*, jobject,
                                                       jint port, jint lx, jint ly, jint rx, jint ry) {
    dccore::Engine::instance().setAnalogAxes(port, lx, ly, rx, ry);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_nesstation_app_core_jni_DcNative_videoFps(JNIEnv*, jobject) {
    return dccore::Engine::instance().videoRefreshRate();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_DcNative_pollPresentedFrames(JNIEnv*, jobject) {
    return dccore::Engine::instance().pollPresentedFrames();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_DcNative_saveState(JNIEnv* env, jobject, jint slot, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return JNI_FALSE;
    dccore::Engine::instance().saveState(slot, p);
    env->ReleaseStringUTFChars(path, p);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_DcNative_loadState(JNIEnv* env, jobject, jint slot, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return JNI_FALSE;
    bool ok = dccore::Engine::instance().loadState(slot, p);
    env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_DcNative_getFrameBuffer(JNIEnv* env, jobject, jintArray out) {
    if (out == nullptr) return JNI_FALSE;
    jsize len = env->GetArrayLength(out);
    jint* buf = env->GetIntArrayElements(out, nullptr);
    if (!buf) return JNI_FALSE;
    // Frame geometry is passed implicitly: square-ish handling mirrors the
    // other bridges — the Kotlin side always allocates w*h and videoWidth/
    // videoHeight are queried separately. We support only 1D arrays, so
    // derive (w,h) from the array length the same way psx does not: we take
    // the full array as w*h with w from the engine's videoWidth().
    const int total = len;
    int w = dccore::Engine::instance().videoWidth();
    int h = dccore::Engine::instance().videoHeight();
    if (w <= 0 || h <= 0 || w * h > total) {
        w = 640; h = 480;
        if (w * h > total) { w = total; h = 1; }
    }
    bool ok = dccore::Engine::instance().getFrameBuffer(
        reinterpret_cast<uint32_t*>(buf), w, h);
    env->ReleaseIntArrayElements(out, buf, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_DcNative_captureFrame(JNIEnv* env, jobject, jintArray out) {
    if (out == nullptr) return JNI_FALSE;
    jsize len = env->GetArrayLength(out);
    jint* buf = env->GetIntArrayElements(out, nullptr);
    if (!buf) return JNI_FALSE;
    int w = dccore::Engine::instance().videoWidth();
    int h = dccore::Engine::instance().videoHeight();
    if (w <= 0 || h <= 0 || w * h > len) {
        w = 640; h = 480;
        if (w * h > len) { w = len; h = 1; }
    }
    bool ok = dccore::Engine::instance().captureFrame(
        reinterpret_cast<uint32_t*>(buf), w, h);
    env->ReleaseIntArrayElements(out, buf, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_DcNative_readAudio(JNIEnv* env, jobject, jshortArray out) {
    if (out == nullptr) return 0;
    jsize len = env->GetArrayLength(out);
    jshort* buf = env->GetShortArrayElements(out, nullptr);
    if (!buf) return 0;
    int frames = dccore::Engine::instance().readAudio(
        reinterpret_cast<int16_t*>(buf), len / 2);
    env->ReleaseShortArrayElements(out, buf, 0);
    return frames;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_DcNative_audioSampleRate(JNIEnv*, jobject) {
    return dccore::Engine::instance().audioSampleRate();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_DcNative_audioTargetSampleRate(JNIEnv*, jobject) {
    return dccore::Engine::instance().audioTargetSampleRate();
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setPaths(JNIEnv* env, jobject, jstring systemDir, jstring saveDir) {
    const char* sys = env->GetStringUTFChars(systemDir, nullptr);
    const char* sav = env->GetStringUTFChars(saveDir, nullptr);
    if (sys && sav) dccore::Engine::instance().setPaths(sys, sav);
    if (sys) env->ReleaseStringUTFChars(systemDir, sys);
    if (sav) env->ReleaseStringUTFChars(saveDir, sav);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setSaveName(JNIEnv* env, jobject, jstring name) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    if (!n) return;
    dccore::Engine::instance().setSaveName(n);
    env->ReleaseStringUTFChars(name, n);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setCoreLibPath(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return;
    dccore::rom::setCoreLibPath(p);
    env->ReleaseStringUTFChars(path, p);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setCoreOption(JNIEnv* env, jobject, jstring key, jstring value) {
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    if (k && v) dccore::Engine::instance().setCoreOption(k, v);
    if (k) env->ReleaseStringUTFChars(key, k);
    if (v) env->ReleaseStringUTFChars(value, v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setSurface(JNIEnv* env, jobject, jobject surface) {
    dccore::Engine_setSurfaceFromJni(env, surface);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setVideoFilter(JNIEnv*, jobject, jint filter) {
    dccore::Engine::instance().setVideoFilter(filter);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_DcNative_setHighQualityScaling(JNIEnv*, jobject, jboolean enabled) {
    dccore::Engine::instance().setHighQualityScaling(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_DcNative_videoWidth(JNIEnv*, jobject) {
    return dccore::Engine::instance().videoWidth();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_DcNative_videoHeight(JNIEnv*, jobject) {
    return dccore::Engine::instance().videoHeight();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_DcNative_lastError(JNIEnv* env, jobject) {
    std::string err = dccore::Engine::instance().lastError();
    return env->NewStringUTF(err.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_DcNative_isCoreLibLoaded(JNIEnv*, jobject) {
    return dccore::rom::isCoreLoaded() ? JNI_TRUE : JNI_FALSE;
}
