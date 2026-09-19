// SPDX-License-Identifier: MIT
// JNI bridge for melonDS (Nintendo DS) core.
//
// Thin wrapper around the libretro frontend in nds_loader.cpp. Kotlin
// owns the emulation thread and pulls frames / audio on demand — same
// pull-model as the other engines.

#include "nds_bridge.h"
#include "nds_loader.h"
#include "shared/core_shared.h"

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstdint>
#include <cstring>

#define TAG "ndscore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace ndscore {

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
    LOGI("NDS ROM loaded OK: %s (region=%d, rate=%d, %dx%d)",
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

void Engine::setTouchInput(int x, int y, bool pressed) {
    rom::setTouchInput(x, y, pressed);
}
void Engine::setTouchInputDirect(int x, int y, bool pressed) {
    rom::setTouchInputDirect(x, y, pressed);
}
void Engine::setRegion(int region)    { rom::applyRegion(region); }
void Engine::setSampleRate(int hz)    { rom::applySampleRate(hz); }
void Engine::setFastForward(int speed)  { rom::applySpeed(speed > 0 ? (float)speed : 1.0f); }

bool Engine::saveState(int slot, const std::string& path) {
    return rom::saveStateToPath(slot, path);
}

bool Engine::loadState(int slot, const std::string& path) {
    return rom::loadStateFromPath(slot, path);
}

bool Engine::getFrameBuffer(uint32_t* out, int w, int h) {
    return rom::copyFramebufferARGB(out, w, h);
}

bool Engine::getFilteredFrameBuffer(uint32_t* out, int w, int h) {
    return rom::copyFilteredFramebufferARGB(out, w, h);
}

int Engine::filteredWidth()  { return rom::filteredWidth(); }
int Engine::filteredHeight() { return rom::filteredHeight(); }
uint64_t Engine::frameStamp() { return rom::frameStamp(); }

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

} // namespace ndscore

// ---------------------------------------------------------------------------
// JNI surface — mirrors NdsNative.kt exactly
// ---------------------------------------------------------------------------

static JavaVM* s_jvm = nullptr;

extern "C" {

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    s_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_loadRom(JNIEnv* env, jclass, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    // Copy into a std::string so the data outlives ReleaseStringUTFChars.
    std::string pathStr(cpath ? cpath : "");
    env->ReleaseStringUTFChars(path, cpath);
    bool ok = ndscore::Engine::instance().loadRom(pathStr);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_unload(JNIEnv*, jclass) {
    ndscore::Engine::instance().unload();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_reset(JNIEnv*, jclass, jboolean hard) {
    ndscore::Engine::instance().reset(hard == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_runFrame(JNIEnv*, jclass) {
    ndscore::Engine::instance().runFrame();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setPad1(JNIEnv*, jclass, jint bits) {
    ndscore::Engine::instance().setPad1(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setPad2(JNIEnv*, jclass, jint bits) {
    ndscore::Engine::instance().setPad2(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setPad3(JNIEnv*, jclass, jint bits) {
    ndscore::Engine::instance().setPad3(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setPad4(JNIEnv*, jclass, jint bits) {
    ndscore::Engine::instance().setPad4(bits);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setTouchInput(JNIEnv*, jclass, jint x, jint y, jboolean pressed) {
    ndscore::Engine::instance().setTouchInput(x, y, pressed == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setTouchInputDirect(JNIEnv*, jclass, jint x, jint y, jboolean pressed) {
    ndscore::Engine::instance().setTouchInputDirect(x, y, pressed == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setRegion(JNIEnv*, jclass, jint region) {
    ndscore::Engine::instance().setRegion(region);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setSampleRate(JNIEnv*, jclass, jint rate) {
    ndscore::Engine::instance().setSampleRate(rate);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setFastForward(JNIEnv*, jclass, jint speed) {
    ndscore::Engine::instance().setFastForward(speed);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_saveState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = ndscore::Engine::instance().saveState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_loadState(JNIEnv* env, jclass, jint slot, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    bool ok = ndscore::Engine::instance().loadState(slot, cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(path, cpath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_getFrameBuffer(JNIEnv* env, jclass, jintArray out) {
    if (!out) return JNI_FALSE;
    int vw = ndscore::Engine::instance().videoWidth();
    int vh = ndscore::Engine::instance().videoHeight();
    if (vw <= 0 || vh <= 0) { vw = 256; vh = 384; }
    jsize len = env->GetArrayLength(out);
    if (len < vw * vh) return JNI_FALSE;
    jint* px = env->GetIntArrayElements(out, nullptr);
    if (!px) return JNI_FALSE;
    bool fresh = ndscore::Engine::instance().getFrameBuffer(
        reinterpret_cast<uint32_t*>(px), vw, vh);
    env->ReleaseIntArrayElements(out, px, 0);
    return fresh ? JNI_TRUE : JNI_FALSE;
}

// Filtered frame for the custom dual-screen layout: the upscaled
// (HQ2X/HQ4X/XBR) frame when active, the raw frame otherwise.
JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_getFilteredFrameBuffer(JNIEnv* env, jclass, jintArray out) {
    if (!out) return JNI_FALSE;
    int vw = ndscore::Engine::instance().filteredWidth();
    int vh = ndscore::Engine::instance().filteredHeight();
    if (vw <= 0 || vh <= 0) { vw = 256; vh = 384; }
    jsize len = env->GetArrayLength(out);
    if (len < vw * vh) return JNI_FALSE;
    jint* px = env->GetIntArrayElements(out, nullptr);
    if (!px) return JNI_FALSE;
    bool ok = ndscore::Engine::instance().getFilteredFrameBuffer(
        reinterpret_cast<uint32_t*>(px), vw, vh);
    env->ReleaseIntArrayElements(out, px, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_filteredVideoWidth(JNIEnv*, jclass) {
    return ndscore::Engine::instance().filteredWidth();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_filteredVideoHeight(JNIEnv*, jclass) {
    return ndscore::Engine::instance().filteredHeight();
}

JNIEXPORT jlong JNICALL
Java_com_nesstation_app_core_jni_NdsNative_frameStamp(JNIEnv*, jclass) {
    return (jlong)ndscore::Engine::instance().frameStamp();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_readAudio(JNIEnv* env, jclass, jshortArray out) {
    if (!out) return 0;
    jsize len = env->GetArrayLength(out);
    if (len < 2) return 0;
    int maxFrames = len / 2;
    jshort* buf = env->GetShortArrayElements(out, nullptr);
    if (!buf) return 0;
    int n = ndscore::Engine::instance().readAudio(
        reinterpret_cast<int16_t*>(buf), maxFrames);
    env->ReleaseShortArrayElements(out, buf, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_audioSampleRate(JNIEnv*, jclass) {
    return ndscore::Engine::instance().audioSampleRate();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_audioTargetSampleRate(JNIEnv*, jclass) {
    return ndscore::Engine::instance().audioTargetSampleRate();
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setPaths(JNIEnv* env, jclass, jstring systemDir, jstring saveDir) {
    const char* sys = systemDir ? env->GetStringUTFChars(systemDir, nullptr) : nullptr;
    const char* sav = saveDir   ? env->GetStringUTFChars(saveDir,   nullptr) : nullptr;
    ndscore::Engine::instance().setPaths(sys ? sys : "", sav ? sav : "");
    if (sys) env->ReleaseStringUTFChars(systemDir, sys);
    if (sav) env->ReleaseStringUTFChars(saveDir, sav);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setSaveName(JNIEnv* env, jclass, jstring name) {
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    ndscore::Engine::instance().setSaveName(n ? n : "");
    if (n) env->ReleaseStringUTFChars(name, n);
}

JNIEXPORT jstring JNICALL
Java_com_nesstation_app_core_jni_NdsNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(ndscore::Engine::instance().lastError().c_str());
}

// --- Hardware-accelerated rendering ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setSurface(JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* win = nullptr;
    if (surface) {
        win = ANativeWindow_fromSurface(env, surface);
    }
    ndscore::rom::setSurface(win);
    if (win) ANativeWindow_release(win);
}

// --- Core options ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setCoreOption(JNIEnv* env, jclass, jstring key, jstring value) {
    const char* ckey = key ? env->GetStringUTFChars(key, nullptr) : nullptr;
    const char* cval = value ? env->GetStringUTFChars(value, nullptr) : nullptr;
    ndscore::Engine::instance().setCoreOption(ckey ? ckey : "", cval ? cval : "");
    if (ckey) env->ReleaseStringUTFChars(key, ckey);
    if (cval) env->ReleaseStringUTFChars(value, cval);
}

// --- Video geometry ---

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_videoWidth(JNIEnv*, jclass) {
    return ndscore::Engine::instance().videoWidth();
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_videoHeight(JNIEnv*, jclass) {
    return ndscore::Engine::instance().videoHeight();
}

// --- Video filter ---

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setVideoFilter(JNIEnv*, jclass, jint filter) {
    ndscore::Engine::instance().setVideoFilter(filter);
}

JNIEXPORT void JNICALL
Java_com_nesstation_app_core_jni_NdsNative_setHighQualityScaling(JNIEnv*, jclass, jboolean enabled) {
    ndscore::Engine::instance().setHighQualityScaling(enabled);
}

JNIEXPORT jboolean JNICALL
Java_com_nesstation_app_core_jni_NdsNative_isCoreLibLoaded(JNIEnv*, jclass) {
    return ndscore::rom::isCoreLoaded() ? JNI_TRUE : JNI_FALSE;
}

// --- DraStic 全局放大滤镜（XBR / HQX）----------------------------------
//
// DraStic 核心是预编译 .so（libdrastic_arm64.so），其显示路径
// renderFrame(tex1, tex2) 只做"原样上传帧池 + glDrawArrays"，没有放大
// 滤镜能力。要让全局滤镜（HQ2X/HQ4X 等）在激烈核心生效，由 Kotlin 的
// DraSticGlView 自行取帧（getScreenBuffers → frameBuffer，恒为
// 256×192×2 的 0xAARRGGBB int），再调用本函数在原生侧跑与 melonDS
// surface 路径完全相同的 coreshared 滤镜实现，输出 RGBA8888 字节供
// glTexSubImage2D 直接上传。
//
// filter 取值与 coreshared::applyFilterAndBlit 一致：
//   4=xbr(2x), 5=hq2x, 6=hq4x, 7=xbr+dot(2x), 8=4xbr(4x),
//   9=4xbr+dot(4x), 10=hq4x+dot(4x)
//   （1/2/3 是叠加类，由视图层 FilterOverlay 绘制，不走这里）
//
// 返回写入 dst 的字节数；filter 不支持 / 参数非法时返回 0。
// -----------------------------------------------------------------------

// 输出上限（★ 高清 2x + 放大滤镜黑屏修复）：旧上限 256×192 只覆盖 1x 源；
// 高清（bit41/_Hires3D）会话下 DraSticGlView 拿到的单屏源是 512×384，
// 旧 guard（w>256||h>192）直接返回 0 → 滤镜永远不产出 → GL 视图每帧
// 绘制从未上传过的空纹理 → 黑屏。现放宽到 512×384（1x 与高清单屏均可）。
//   1x  源 4x → 1024×768；HD 源 2x → 1024×1536；HD 源 4x → 2048×3072。
// 缓冲为 BSS 零页（Android 惰性提交）：未用到的页不占物理内存，只有
// 实际跑到对应档位滤镜时才按页落地，无需担心常驻开销。
static constexpr int kDfMaxSrcW = 512;
static constexpr int kDfMaxSrcH = 384;

// 2x 输出缓冲（4x 级联的中间缓冲也用它）：512×2 × 384×2 = 1024×1536
static uint32_t s_dfBuf2x[kDfMaxSrcW * 2 * kDfMaxSrcH * 2];
// 4x 输出缓冲：512×4 × 384×4 = 2048×3072
static uint32_t s_dfBuf4x[kDfMaxSrcW * 4 * kDfMaxSrcH * 4];

// 0xAARRGGBB uint32（滤镜内部格式，与 Bitmap/IntArray 一致）
// → RGBA8888 字节序（GL_RGBA / GL_UNSIGNED_BYTE 上传格式）。
static inline void argbToRgbaBytes(const uint32_t* src, uint8_t* dst, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        const uint32_t px = src[i];
        dst[i * 4 + 0] = (uint8_t)((px >> 16) & 0xFF); // R
        dst[i * 4 + 1] = (uint8_t)((px >> 8)  & 0xFF); // G
        dst[i * 4 + 2] = (uint8_t)( px        & 0xFF); // B
        dst[i * 4 + 3] = 0xFF;                         // A
    }
}

JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_applyUpscaleFilter(
        JNIEnv* env, jclass, jint filter, jintArray src, jint w, jint h, jobject dst) {
    if (w <= 0 || h <= 0 || w > kDfMaxSrcW || h > kDfMaxSrcH || src == nullptr || dst == nullptr)
        return 0;

    const bool is2x = (filter == 4 || filter == 5 || filter == 7);
    const bool is4x = (filter == 6 || filter == 8 || filter == 9 || filter == 10);
    if (!is2x && !is4x) return 0;

    const jint* srcPtr = env->GetIntArrayElements(src, nullptr);
    if (srcPtr == nullptr) return 0;

    const uint32_t* srcPx = reinterpret_cast<const uint32_t*>(srcPtr);
    const size_t outW = (size_t)w * (is4x ? 4 : 2);
    const size_t outH = (size_t)h * (is4x ? 4 : 2);
    uint32_t* outBuf = is4x ? s_dfBuf4x : s_dfBuf2x;

    // 与 coreshared::applyFilterAndBlit 的 surface 路径相同的滤镜实现
    // （xbr/hq4x 均为 coreshared 命名空间内的 static inline；hq2x_32_rb /
    //  hq4x_32_rb 是 hqx/hqx.h 的全局 C 函数，hqxInit 为空操作无需调用）
    if (filter == 4 || filter == 7) {
        coreshared::xbr2xUpscale(srcPx, (unsigned)w, (unsigned)h,
                                 (size_t)w, s_dfBuf2x);
    } else if (filter == 5) {
        hq2x_32_rb(srcPx, (uint32_t)(w * sizeof(uint32_t)),
                   s_dfBuf2x, (uint32_t)(outW * sizeof(uint32_t)),
                   (int)w, (int)h);
    } else if (filter == 8 || filter == 9) {
        coreshared::xbr4xUpscale(srcPx, (unsigned)w, (unsigned)h,
                                 (size_t)w, s_dfBuf4x, s_dfBuf2x);
    } else { // 6 / 10
        hq4x_32_rb(srcPx, (uint32_t)(w * sizeof(uint32_t)),
                   s_dfBuf4x, (uint32_t)(outW * sizeof(uint32_t)),
                   (int)w, (int)h);
    }

    env->ReleaseIntArrayElements(src, const_cast<jint*>(srcPtr), JNI_ABORT);

    const size_t outPixels = outW * outH;
    const size_t outBytes = outPixels * 4;
    void* dstPtr = env->GetDirectBufferAddress(dst);
    const jlong dstCap = env->GetDirectBufferCapacity(dst);
    if (dstPtr == nullptr || dstCap < 0 || (size_t)dstCap < outBytes) return 0;
    argbToRgbaBytes(outBuf, static_cast<uint8_t*>(dstPtr), outPixels);
    return (jint)outBytes;
}

// 画布路径变体：把滤镜输出（0xAARRGGBB uint32）直接写入 jintArray 的
// [dstOffset] 处，返回写入的像素数。供 DraSticEngine 渲染线程在画布
// 回退路径（GL 初始化失败的设备）合成放大后的 frameBuffer 使用。
JNIEXPORT jint JNICALL
Java_com_nesstation_app_core_jni_NdsNative_applyUpscaleFilterArgb(
        JNIEnv* env, jclass, jint filter, jintArray src, jint w, jint h,
        jintArray dst, jint dstOffset) {
    if (w <= 0 || h <= 0 || w > kDfMaxSrcW || h > kDfMaxSrcH || src == nullptr || dst == nullptr)
        return 0;

    const bool is2x = (filter == 4 || filter == 5 || filter == 7);
    const bool is4x = (filter == 6 || filter == 8 || filter == 9 || filter == 10);
    if (!is2x && !is4x) return 0;

    const jint* srcPtr = env->GetIntArrayElements(src, nullptr);
    if (srcPtr == nullptr) return 0;

    const uint32_t* srcPx = reinterpret_cast<const uint32_t*>(srcPtr);
    const size_t outW = (size_t)w * (is4x ? 4 : 2);
    const size_t outH = (size_t)h * (is4x ? 4 : 2);
    const size_t outPixels = outW * outH;

    jint* dstPtr = env->GetIntArrayElements(dst, nullptr);
    if (dstPtr == nullptr) {
        env->ReleaseIntArrayElements(src, const_cast<jint*>(srcPtr), JNI_ABORT);
        return 0;
    }

    if (filter == 4 || filter == 7) {
        coreshared::xbr2xUpscale(srcPx, (unsigned)w, (unsigned)h, (size_t)w, s_dfBuf2x);
    } else if (filter == 5) {
        hq2x_32_rb(srcPx, (uint32_t)(w * sizeof(uint32_t)),
                   s_dfBuf2x, (uint32_t)(outW * sizeof(uint32_t)), (int)w, (int)h);
    } else if (filter == 8 || filter == 9) {
        coreshared::xbr4xUpscale(srcPx, (unsigned)w, (unsigned)h, (size_t)w, s_dfBuf4x, s_dfBuf2x);
    } else { // 6 / 10
        hq4x_32_rb(srcPx, (uint32_t)(w * sizeof(uint32_t)),
                   s_dfBuf4x, (uint32_t)(outW * sizeof(uint32_t)), (int)w, (int)h);
    }

    env->ReleaseIntArrayElements(src, const_cast<jint*>(srcPtr), JNI_ABORT);

    const uint32_t* outBuf = is4x ? s_dfBuf4x : s_dfBuf2x;
    memcpy(dstPtr + dstOffset, outBuf, outPixels * sizeof(uint32_t));
    env->ReleaseIntArrayElements(dst, dstPtr, 0);
    return (jint)outPixels;
}

} // extern "C"
