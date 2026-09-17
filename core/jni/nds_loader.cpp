// SPDX-License-Identifier: MIT
// libretro frontend that drives the melonDS (Nintendo DS) core.
//
// This loader is compiled directly against the melonDS libretro wrapper
// source (core/melonds/libretro/libretro.cpp) — no dlopen/dlsym needed.
// The retro_* functions are regular C-linkage functions defined in the
// melonDS libretro wrapper and linked statically via CMake.
//
// The libretro API surface we use:
//   retro_init, retro_deinit, retro_load_game, retro_unload_game, retro_run,
//   retro_reset, retro_get_system_info, retro_get_system_av_info,
//   retro_set_environment, retro_set_video_refresh, retro_set_audio_sample,
//   retro_set_audio_sample_batch, retro_set_input_poll, retro_set_input_state,
//   retro_set_controller_port_device, retro_serialize_size, retro_serialize,
//   retro_unserialize, retro_get_memory_size, retro_get_memory_data
//
// Video resolution is fixed at 256x192 per screen. melonDS 0.9.3' libretro
// port composites the two screens into a single framebuffer based on the
// melonds_screen_layout core option (values use mixed case + '/'):
//   Top/Bottom (default): 256x384 (top screen above bottom screen)
//   Bottom/Top:           256x384 (bottom above top)
//   Left/Right:           512x192 (top left, bottom right)
//   Right/Left:           512x192 (bottom left, top right)
//   Top Only / Bottom Only: 256x192 (single screen)
// The frame buffer uses a std::vector that resizes to the largest seen
// resolution. Filter buffers are sized to 256x384 max — this covers every
// melonDS screen layout.
//
// Audio: DS outputs at 32768 Hz (16-bit stereo); the resampler converts
// to Android's 48000 Hz.
//
// Input: 12-button DS gamepad (RETRO_DEVICE_JOYPAD) on ports 0-3.
// melonDS maps the standard JOYPAD buttons 1:1 to DS hardware buttons:
//   A=A, B=B, X=X, Y=Y, L=L, R=R, Select=Select, Start=Start.
// Touchscreen / microphone / lid-close are NOT exposed via this 12-bit
// field (would require RETRO_DEVICE_POINTER + RETRO_DEVICE_MIC).
//
// BIOS files: melonDS 0.9.3 looks for the following files in <systemDir>/:
//   NDS mode:  bios7.bin (16 KB ARM7 BIOS), bios9.bin (4 KB ARM9 BIOS),
//             firmware.bin (256 KB NDS firmware — provides settings
//             like username / language / boot slot)
//   DSi mode:  dsi_arm7.bin, dsi_bios7.bin, dsi_bios9.bin, dsi_firmware.bin,
//             dsi_nand.bin (DSi NAND image with launcher app)
// If any are missing the core silently falls back to the built-in FreeBIOS
// (see "Bios ARM7 not found. Proceeding with FreeBIOS." in the core logs),
// so the game still boots without external BIOS files.
//
// All retro_* calls happen on a single emulation thread (see NdsEngine in
// Kotlin), so no extra internal locking is needed around the core itself.
// The frame and audio buffers are mutex-guarded because the UI / AudioTrack
// threads read them concurrently.

#include "nds_loader.h"
#include "shared/core_shared.h"
#include "input.h"   // touch_direct_* state (direct-pixel touchscreen path)

#include <libretro.h>
#include <android/log.h>
#include <android/native_window.h>

#include <atomic>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>

// EGL + OpenGL ES headers for hardware-accelerated 3D rendering.
// melonDS's libretro core requires an OpenGL ES context for its 3D
// renderer (GPU 3D). Without this, the 3D layer renders as grey.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

// ---------------------------------------------------------------------------
// glad: OpenGL function pointer loader used by the melonDS core.
// glad.h is included by the melonDS core source files (via PlatformOGL.h) and
// defines macros like glFoo → glad_glFoo. The function pointer variables
// (glad_glFoo) are declared in glad.h and defined in glad.c (compiled as part
// of the melonds-core static library). They must be initialized with the
// actual GL function addresses before any melonDS core code that uses the
// GL renderer is called.
//
// We forward-declare gladLoadGLLoader here (without including glad.h, which
// would conflict with our GLES2/gl2.h includes) and call it right after the
// EGL context is created, using eglGetProcAddress as the loader callback.
// ---------------------------------------------------------------------------
typedef void* (*GLADloadproc)(const char* name);
extern "C" int gladLoadGLLoader(GLADloadproc);

// Wrapper that adapts EGL's eglGetProcAddress to glad's loader signature.
static void* glad_egl_loader(const char* name) {
    return (void*)eglGetProcAddress(name);
}

#define TAG "ndscore-rom"
#undef LOGI
#undef LOGW
#undef LOGE
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Forward-declare the override hooks defined in the melonDS libretro
// wrapper (libretro.cpp). These let us replace the .sav filename that
// melonDS derives from info->path (which would be "temp_rom.sav" for
// cached ROMs) with our own stable game.id-based name, so saves don't
// clobber across games.
extern "C" {
void melonds_set_save_basename_override(const char* name);
void melonds_set_gba_save_basename_override(const char* name);
}

namespace ndscore::rom {

// ---------------------------------------------------------------------------
// Maximum video resolution that the CPU upscale filters (XBR / HQX) accept.
//
// DS screens are each 256x192. The software renderer's default "Top/Bottom"
// layout produces a 256x384 composite; the GL compositor (opengl.cpp) emits
// 256x386 — 384 + a fixed 2-row black gap between the screens.
//
// 修复（"开启硬件加速后全局放大滤镜失效"）：OpenGL 渲染器的内部分辨率
// 倍数高于 1x 时，合成帧等比变大（2x → 512x772，4x → 1024x1544）。
// 旧上限 256x386 会让 canUpscale 在 2x+ 分辨率下永远为 false —— 用户
// 开了硬件加速 + 2x 分辨率后，XBR/HQ2X/HQ4X 全部被静默跳过，看起来
// 就是"全局滤镜不起作用"。现在把上限放宽到 2x GL 帧（512x772）：
//   - 2x 放大滤镜（XBR/HQ2X/XBR+dot）：输出 1024x1544 ≈ 6.3MB ✔
//   - 4x 放大滤镜（HQ4X/4XBR/…+dot）：输出 2048x3088 ≈ 25MB（BSS 按需
//     落地，仅在用户选择 4x 类滤镜时触碰）
// 4x GL 帧（1024x1544）做 4x CPU 放大单帧代价过高（>100MB 输出 +
// 数十毫秒/帧），仍然跳过并打一条一次性日志 —— GL 本身已按倍数放大，
// CPU 滤镜在 4x 源上是收益极低的重复劳动。
// ---------------------------------------------------------------------------
static constexpr int kMaxW = 512;
static constexpr int kMaxH = 772;

// 一次性提示：帧大于滤镜可承受上限时只打一次日志，避免刷屏。
static bool s_filterSkipLogged = false;

static constexpr int TARGET_SAMPLE_RATE = coreshared::TARGET_SAMPLE_RATE;

// ---------------------------------------------------------------------------
// State — directly linked to melonDS libretro wrapper
// ---------------------------------------------------------------------------
static bool s_loaded = false;
static bool s_gameLoaded = false;
static int  s_sampleRate = 0;
static int  s_region = 0;
static std::string s_systemDir;
static std::string s_saveDir;
static std::string s_saveName;
static std::string s_lastRomPath;
static std::string s_coreMessage;
static std::string s_coreError;

// ---------------------------------------------------------------------------
// Core-wide mutex — serializes every retro_* call that touches the melonDS
// NDS instance.
//
// The Kotlin side owns the emulation thread (ndscore-loop → stepFrame →
// retro_run), but saveState / loadState / reset are invoked from the UI
// thread (Compose dialogs). Without this mutex, retro_serialize /
// retro_unserialize (NDS::DoSavestate walks the ENTIRE emulator state) can
// run CONCURRENTLY with retro_run (NDS::RunFrame mutating the exact same
// structures) — a data race that corrupts savestates and can crash the
// process. This replaces the old "all retro_* calls happen on a single
// thread" assumption, which was never true for the save/load/reset path.
//
// recursive: harmless safety against accidental nested locking (e.g. if a
// future code path calls resetEmulation from inside stepFrame).
//
// Frame/audio buffers keep their own dedicated mutexes (s_frameMtx /
// audio ring) and are NOT covered by this lock — those are designed for
// cross-thread concurrent access.
// ---------------------------------------------------------------------------
static std::recursive_mutex s_coreMtx;

// Persistent copy of the currently-loaded ROM path.
// melonDS may read retro_game_info.path asynchronously (e.g. for save-state
// naming), so the pointer must outlive the JNI GetStringUTFChars /
// ReleaseStringUTFChars cycle. We store the path here and pass
// s_romPath.c_str() as gameInfo.path instead of the transient cpath pointer.
static std::string s_romPath;

// Dynamic frame buffer (ARGB, 0xAARRGGBB).
static std::mutex s_frameMtx;
static std::vector<uint32_t> s_frame;
static unsigned s_frameW = 0;
static unsigned s_frameH = 0;
static std::atomic<bool> s_newFrame{false};

// Monotonic frame counter — incremented on every frame pushed through
// cb_video(). The Kotlin NdsDualScreenView polls it from its Choreographer
// callback and only calls invalidate() when a NEW frame is actually
// available, eliminating redundant redraws on 90/120 Hz displays.
static std::atomic<uint64_t> s_frameStamp{0};

// Filtered (upscaled) frame for the custom dual-screen layout mode.
// When no ANativeWindow is attached (videoScale == "custom"), the CPU
// upscale filters (HQ2X / HQ4X / XBR) cannot go through the usual
// applyFilterAndBlit() surface path — so cb_video() runs the SAME filter
// pipeline into this buffer instead, and the Kotlin side pulls it via
// getFilteredFrameBuffer(). When no upscale filter is active these stay 0
// and callers fall back to the raw frame.
static std::vector<uint32_t> s_filteredFrame;
static unsigned s_filteredW = 0;
static unsigned s_filteredH = 0;

static unsigned s_videoW = 256;
static unsigned s_videoH = 384;
static unsigned s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;

// Gamepad bits (port 0..3, RETRO_DEVICE_JOYPAD).
static std::atomic<uint16_t> s_pad1{0};
static std::atomic<uint16_t> s_pad2{0};
static std::atomic<uint16_t> s_pad3{0};
static std::atomic<uint16_t> s_pad4{0};

// Touchscreen state (RETRO_DEVICE_POINTER).
static std::atomic<int16_t> s_touchX{0};
static std::atomic<int16_t> s_touchY{0};
static std::atomic<bool>    s_touchPressed{false};

static std::atomic<int>  s_videoFilter{0};
static std::atomic<bool> s_highQualityScaling{false};
static std::atomic<bool> s_fastForward{false};
static std::atomic<int>  s_ffFrameSkip{0};
static std::atomic<int>  s_ffMaxSkip{6};

// 2x / 4x upscale buffers for XBR / HQ2X / HQ4X filters.
static uint32_t s_xbrBuffer2x[kMaxW * kMaxH * 2 * 2];
static uint32_t s_xbrBuffer4x[kMaxW * kMaxH * 4 * 4];
static uint32_t s_xbrMidBuffer[kMaxW * kMaxH * 2 * 2];

static coreshared::AudioRingBuffer s_audio;
static coreshared::AudioResampler s_resampler;

static ANativeWindow* s_window = nullptr;
static std::mutex s_windowMtx;

static std::mutex s_optMtx;
static std::map<std::string, std::string> s_options;
static std::atomic<bool> s_optionsChanged{false};

// ---------------------------------------------------------------------------
// EGL / OpenGL ES context — required by melonDS libretro core for the
// hardware-accelerated 3D renderer (GPU 3D). Without this, the core's
// 3D layer renders as grey ("灰屏").
//
// We create an offscreen Pbuffer context that persists for the lifetime
// of the emulation session. The melonDS core renders 3D content to its
// own FBOs internally, then reads back the pixels and submits them
// through the cb_video() callback — so the ANativeWindow blitting path
// is unaffected and the EGL context is only used for the core's internal
// 3D rendering.
// ---------------------------------------------------------------------------
static EGLDisplay s_eglDisplay = EGL_NO_DISPLAY;
static EGLContext s_eglContext = EGL_NO_CONTEXT;
static EGLSurface s_eglSurface = EGL_NO_SURFACE;
static bool s_eglInitialized = false;

// libretro HW render callbacks — set by the core via
// RETRO_ENVIRONMENT_SET_HW_RENDER.
static retro_hw_context_reset_t s_hwContextReset = nullptr;
static retro_hw_context_reset_t s_hwContextDestroy = nullptr;

// ---------------------------------------------------------------------------
// Create an offscreen EGL context for OpenGL ES rendering.
// Supports both ES 2.0 and ES 3.0 based on the core's request.
// Uses a small Pbuffer surface (1x1) — the melonDS core renders to its
// own framebuffer objects internally.
// ---------------------------------------------------------------------------
// Forward declaration — defined below; used by createEglContext's
// "already initialized" path to re-bind the context to the calling thread.
static bool ensureEglContextCurrent();

static bool createEglContext(int contextType = RETRO_HW_CONTEXT_OPENGLES2,
                              int versionMajor = 2, int versionMinor = 0) {
    if (s_eglInitialized) {
        // 修复（"硬件加速丢失"）：上下文已存在（上一次会话创建）时不能
        // 直接 return true —— EGL context 是绑定在"当前线程"上的。旧会话
        // 的模拟线程退出后上下文虽然被释放，但当前（加载）线程上并没有
        // current context；核心紧接着在本线程调用 GLRenderer::New() 编译
        // 着色器时，所有 glCreateShader / glShaderSource 调用都会静默失败
        // （无 current context 时 GL 指令是 no-op），返回 null → 包装层
        // 打出 "GLRenderer::New() failed … falling back to the CPU
        // renderer" 并把 enable_opengl 永久置 false —— 第二次进 NDS 游戏
        // 必现"硬件加速丢失"。先把已存在的上下文绑到当前线程，再让核心
        // 继续初始化。
        if (!ensureEglContextCurrent()) {
            LOGE("createEglContext: existing EGL context could not be "
                 "re-bound to current thread — falling back to software");
            return false;
        }
        LOGI("createEglContext: re-bound existing EGL context to current "
             "thread");
        return true;
    }

    s_eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (s_eglDisplay == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed: 0x%x", eglGetError());
        return false;
    }

    EGLint major, minor;
    if (!eglInitialize(s_eglDisplay, &major, &minor)) {
        LOGE("eglInitialize failed: 0x%x", eglGetError());
        s_eglDisplay = EGL_NO_DISPLAY;
        return false;
    }
    LOGI("EGL initialized: %d.%d", major, minor);

    // Determine the EGL renderable type and client version based on the
    // core's HW render request. If the core requests ES 3.0 (either via
    // RETRO_HW_CONTEXT_OPENGLES3 or RETRO_HW_CONTEXT_OPENGLES_VERSION
    // with version >= 3), we try ES 3.0 first, falling back to ES 2.0.
    EGLint renderableType = EGL_OPENGL_ES2_BIT;
    EGLint clientVersion = 2;
    bool wantES3 = (contextType == RETRO_HW_CONTEXT_OPENGLES3) ||
                   (contextType == RETRO_HW_CONTEXT_OPENGLES_VERSION && versionMajor >= 3);
    if (wantES3) {
        renderableType = EGL_OPENGL_ES3_BIT;
        clientVersion = 3;
    }

    // Choose config with RGBA 8888, depth 24, stencil 8.
    // melonDS needs depth for 3D rendering and stencil for some effects.
    EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, renderableType,
        EGL_SURFACE_TYPE,    EGL_PBUFFER_BIT,
        EGL_RED_SIZE,        8,
        EGL_GREEN_SIZE,      8,
        EGL_BLUE_SIZE,       8,
        EGL_ALPHA_SIZE,      8,
        EGL_DEPTH_SIZE,      24,
        EGL_STENCIL_SIZE,    8,
        EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(s_eglDisplay, attribs, &config, 1, &numConfigs) || numConfigs == 0) {
        if (wantES3) {
            // ES 3.0 config not available — fall back to ES 2.0
            LOGW("ES 3.0 config not available, falling back to ES 2.0");
            renderableType = EGL_OPENGL_ES2_BIT;
            clientVersion = 2;
            attribs[1] = renderableType;
            if (!eglChooseConfig(s_eglDisplay, attribs, &config, 1, &numConfigs) || numConfigs == 0) {
                LOGE("eglChooseConfig failed (ES 2.0 fallback): 0x%x", eglGetError());
                eglTerminate(s_eglDisplay);
                s_eglDisplay = EGL_NO_DISPLAY;
                return false;
            }
        } else {
            LOGE("eglChooseConfig failed: 0x%x", eglGetError());
            eglTerminate(s_eglDisplay);
            s_eglDisplay = EGL_NO_DISPLAY;
            return false;
        }
    }

    // Create a small Pbuffer surface — just needs to be valid for the
    // context to be current. The melonDS core renders to its own FBOs.
    const EGLint pbAttribs[] = {
        EGL_WIDTH,  1,
        EGL_HEIGHT, 1,
        EGL_NONE
    };
    s_eglSurface = eglCreatePbufferSurface(s_eglDisplay, config, pbAttribs);
    if (s_eglSurface == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface failed: 0x%x", eglGetError());
        eglTerminate(s_eglDisplay);
        s_eglDisplay = EGL_NO_DISPLAY;
        return false;
    }

    // Create OpenGL ES context with the appropriate client version.
    // Try ES 3.2 (EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB); fall back to
    // ES 3.0, then ES 2.0. Some drivers accept CLIENT_VERSION=3 but
    // default to 3.0 even when 3.2 is available; we explicitly request 3.2.
    //
    // EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB (EGL 1.4 KHR_create_context)
    // EGL_CONTEXT_MINOR_VERSION     = 0x30FB (EGL 1.5)
    #ifndef EGL_CONTEXT_MINOR_VERSION
    #ifdef EGL_CONTEXT_MINOR_VERSION_KHR
    #define EGL_CONTEXT_MINOR_VERSION EGL_CONTEXT_MINOR_VERSION_KHR
    #else
    #define EGL_CONTEXT_MINOR_VERSION 0x30FB
    #endif
    #endif
    {
        int tryCtx[][3] = {
            {3, 2},  // ES 3.2 (required for #version 320 es shaders)
            {3, 0},  // ES 3.0 fallback
            {2, 0},  // ES 2.0 fallback
        };
        int numTries = sizeof(tryCtx) / sizeof(tryCtx[0]);
        int startTry = 0;
        // If the core explicitly requested a version, start from that.
        if (clientVersion == 2) startTry = 2; // ES 2.0 only
        else if (versionMajor == 3 && versionMinor == 0) startTry = 1; // ES 3.0
        // else start at 0 (try 3.2 first)

        for (int i = startTry; i < numTries; i++) {
            int maj = tryCtx[i][0];
            int min = tryCtx[i][1];
            EGLint ctxAttribs[6];
            int idx = 0;
            ctxAttribs[idx++] = EGL_CONTEXT_CLIENT_VERSION;
            ctxAttribs[idx++] = maj;
            if (min > 0) {
                ctxAttribs[idx++] = EGL_CONTEXT_MINOR_VERSION;
                ctxAttribs[idx++] = min;
            }
            ctxAttribs[idx++] = EGL_NONE;

            s_eglContext = eglCreateContext(s_eglDisplay, config, EGL_NO_CONTEXT, ctxAttribs);
            if (s_eglContext != EGL_NO_CONTEXT) {
                LOGI("EGL context created: ES %d.%d, Pbuffer 1x1, depth=24, stencil=8", maj, min);
                break;
            }
            LOGW("eglCreateContext ES %d.%d failed (0x%x), trying next", maj, min, eglGetError());
        }
    }
    if (s_eglContext == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed for all ES versions");
        eglDestroySurface(s_eglDisplay, s_eglSurface);
        s_eglSurface = EGL_NO_SURFACE;
        eglTerminate(s_eglDisplay);
        s_eglDisplay = EGL_NO_DISPLAY;
        return false;
    }

    // Make the context current on this thread.
    if (!eglMakeCurrent(s_eglDisplay, s_eglSurface, s_eglSurface, s_eglContext)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        eglDestroyContext(s_eglDisplay, s_eglContext);
        s_eglContext = EGL_NO_CONTEXT;
        eglDestroySurface(s_eglDisplay, s_eglSurface);
        s_eglSurface = EGL_NO_SURFACE;
        eglTerminate(s_eglDisplay);
        s_eglDisplay = EGL_NO_DISPLAY;
        return false;
    }

    s_eglInitialized = true;

    // Initialize glad OpenGL function pointers using eglGetProcAddress.
    // This must happen after the EGL context is made current, because
    // eglGetProcAddress returns valid function pointers only when a
    // current EGL context exists.
    if (!gladLoadGLLoader(&glad_egl_loader)) {
        LOGE("gladLoadGLLoader failed — OpenGL function pointers not initialized");
        // This is non-fatal: the core will fall back to the software
        // renderer if GLRenderer::New() fails later.
    } else {
        LOGI("glad initialized successfully");
    }
    return true;
}

static void destroyEglContext() {
    if (!s_eglInitialized) return;

    eglMakeCurrent(s_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    if (s_eglContext != EGL_NO_CONTEXT) {
        eglDestroyContext(s_eglDisplay, s_eglContext);
        s_eglContext = EGL_NO_CONTEXT;
    }
    if (s_eglSurface != EGL_NO_SURFACE) {
        eglDestroySurface(s_eglDisplay, s_eglSurface);
        s_eglSurface = EGL_NO_SURFACE;
    }
    if (s_eglDisplay != EGL_NO_DISPLAY) {
        eglTerminate(s_eglDisplay);
        s_eglDisplay = EGL_NO_DISPLAY;
    }
    s_eglInitialized = false;
    LOGI("EGL context destroyed");
}

// Ensure the EGL context is current on the calling thread.
// Called before each retro_run() to handle cases where the context
// might have been lost (e.g., thread migration).
static bool ensureEglContextCurrent() {
    if (!s_eglInitialized) return false;
    if (eglGetCurrentContext() != s_eglContext) {
        if (!eglMakeCurrent(s_eglDisplay, s_eglSurface, s_eglSurface, s_eglContext)) {
            LOGE("eglMakeCurrent (re-bind) failed: 0x%x", eglGetError());
            return false;
        }
    }
    return true;
}

// ---------------------------------------------------------------------------
// libretro HW render callbacks — getters for the retro_hw_render_callback
// struct. The core provides its own context_reset/context_destroy callbacks
// through the HW_RENDER environment call; we store those and invoke them
// at the appropriate time.
// ---------------------------------------------------------------------------

// Get the current framebuffer object for rendering.
// melonDS renders to its own FBOs, so we return 0 (default framebuffer).
static uintptr_t hw_get_current_framebuffer(void) {
    return 0;
}

// Get a GL function pointer by name.
static retro_proc_address_t hw_get_proc_address(const char* sym) {
    return (retro_proc_address_t)eglGetProcAddress(sym);
}

// ---------------------------------------------------------------------------
// Initialize melonDS core options with sensible defaults.
// Keys MUST match melonDS's libretro_core_options.h exactly.
//
// NOTE: The melonDS libretro source tree is NOT included in this project
// snapshot — the .so is prebuilt. The keys below are the standard melonDS
// libretro option keys as documented in the upstream
// melonDS-libretro/libretro_core_options.h. They have been verified
// against the public melonDS libretro source on GitHub (libretro/melonds
// repository). If any key is wrong, the melonDS core silently ignores it
// and uses its built-in default — no crash, just the option has no effect.
//
// The bundled core is a prebuilt melonDS 0.9.3 libretro build (verified by
// string-scanning libmelonds_libretro_android.so). BIOS/system path is
// provided to the core via RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY, not an
// option key (melonds_sysfile_directory does NOT exist in 0.9.3).
// ---------------------------------------------------------------------------
static void initDefaultOptions() {
    // Only set defaults for options that haven't been set yet.
    // applyCoreOptions() from Kotlin may have already set these options
    // BEFORE loadRom() is called. Overwriting them here would cause:
    //   - Layout / mode / touch choices being reset to the defaults below
    auto setIfMissing = [](const std::string& key, const std::string& val) {
        if (s_options.find(key) == s_options.end()) {
            s_options[key] = val;
        }
    };

    // --- Boot ---
    // CRITICAL: Without "melonds_boot_directly"="enabled" the core leaves
    // Config::DirectBoot = 0 and boots into the (grey) DS firmware menu
    // instead of launching the cart directly — this is the classic "NDS
    // loads but the screen stays grey" symptom.
    // FreeBIOS is built into the 0.9.3 core and used automatically when no
    // external bios7.bin/bios9.bin/firmware.bin are present.
    setIfMissing("melonds_boot_directly",      "enabled");
    setIfMissing("melonds_use_fw_settings",    "disabled"); // don't need a firmware to supply username/lang

    // --- Display / Layout ---
    // 0.9.3 option values use mixed case with '/': "Top/Bottom",
    // "Bottom/Top", "Left/Right", "Right/Left", "Top Only",
    // "Bottom Only", "Hybrid Top", "Hybrid Bottom".
    setIfMissing("melonds_screen_layout",      "Top/Bottom");

    // --- System / Console ---
    // console_mode value is "DSi" for DSi mode, anything else = DS.
    setIfMissing("melonds_console_mode",       "DS");
    setIfMissing("melonds_dsi_sdcard",         "disabled");

    // --- JIT / Performance ---
    setIfMissing("melonds_jit_enable",         "enabled");  // JIT compiler (enabled = much faster, disabled = interpreter)

    // --- Audio ---
    // Valid values: None | Linear | Cosine | Cubic
    setIfMissing("melonds_audio_interpolation", "Cosine");

    // --- Renderer / Input / Touch ---
    // OpenGL renderer. ENABLED by default: this build has a full EGL/GLES3.2
    // path (createEglContext + RETRO_ENVIRONMENT_SET_HW_RENDER handler call
    // context_reset and wire up glad), so hardware 3D rendering is available.
    // If EGL/GLES init fails on a device, initialize_opengl() safely falls
    // back to the software 3D renderer (GPU3D_Soft) — this is non-fatal.
    setIfMissing("melonds_opengl_renderer",        "enabled");
    // OpenGL 3D rendering resolution (1x = native 256x192, etc.).
    // The value format MUST match libretro_core_options.h exactly:
    // "1x native (256x192)" .. "8x native (2048x1536)".
    setIfMissing("melonds_opengl_resolution",      "1x native (256x192)");
    // OpenGL improved polygon splitting — reduces graphical artifacts.
    setIfMissing("melonds_opengl_better_polygons", "disabled");
    // OpenGL texture filtering: nearest (sharp) | linear (smooth).
    setIfMissing("melonds_opengl_filtering",       "nearest");
    // Threaded software renderer — fallback when OpenGL is disabled.
    setIfMissing("melonds_threaded_renderer",      "enabled");
    setIfMissing("melonds_touch_mode",             "Touch");
}

// ---------------------------------------------------------------------------
// Core library check — always succeeds because the melonDS libretro
// wrapper is compiled directly into the binary (no dlopen).
// ---------------------------------------------------------------------------
static bool loadCoreLib() {
    LOGI("melonDS core is statically linked (no dlopen needed)");
    return true;
}

// ---------------------------------------------------------------------------
// libretro callbacks
// ---------------------------------------------------------------------------
static void libretroLog(retro_log_level level, const char* fmt, ...) {
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case RETRO_LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
        case RETRO_LOG_WARN:  prio = ANDROID_LOG_WARN;  break;
        case RETRO_LOG_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default: break;
    }
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(prio, "melonds", fmt, ap);
    va_end(ap);
}

static bool cb_environment(unsigned cmd, void* data) {
    // NOTE: no per-call logging here. cb_environment is invoked for
    // GET_VARIABLE (dozens of times per option re-read) and — critically —
    // for GET_VARIABLE_UPDATE on EVERY retro_run() (60+ times per second).
    // The old unconditional LOGI flooded logcat and cost measurable time
    // (each __android_log_print performs a socket write to logd).
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            if (data) *static_cast<bool*>(data) = true;
            return true;

        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            if (data) {
                s_pixelFormat = *static_cast<const unsigned*>(data);
                LOGI("Pixel format set: %u (0=0RGB1555, 1=XRGB8888, 2=RGB565)",
                     s_pixelFormat);
            }
            return true;

        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            if (data) {
                auto* log = static_cast<retro_log_callback*>(data);
                log->log = libretroLog;
            }
            return true;

        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            if (data) *static_cast<const char**>(data) = s_systemDir.c_str();
            LOGI("GET_SYSTEM_DIRECTORY -> %s", s_systemDir.c_str());
            return !s_systemDir.empty();

        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            if (data) *static_cast<const char**>(data) = s_saveDir.c_str();
            return !s_saveDir.empty();

        case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY:
            if (data) *static_cast<const char**>(data) = s_systemDir.c_str();
            return !s_systemDir.empty();

        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
#ifdef RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE
        case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
#endif
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            return true;

        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            if (data) *static_cast<unsigned*>(data) = 1;
            return true;

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
            return true;

        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            return true;

        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
            return true;

        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            if (data) *static_cast<int*>(data) = 3;
            return true;

        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            if (data) {
                auto* msg = static_cast<const retro_message*>(data);
                if (msg && msg->msg) {
                    s_coreMessage = msg->msg;
                    LOGI("Core message: %s", msg->msg);
                }
            }
            return true;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            if (!data) return false;
            auto* var = static_cast<retro_variable*>(data);
            if (!var->key) return false;
            // Copy the value into a stable buffer while holding the lock.
            // Returning it->second.c_str() directly was a dangling-pointer
            // race: setCoreOption() on the UI thread can reallocate the
            // std::string between this return and the core's strcmp().
            // s_optValueBuf is only ever written under s_optMtx, and the
            // core consumes var->value synchronously before its next
            // GET_VARIABLE call, so a single shared buffer is safe.
            std::lock_guard<std::mutex> lk(s_optMtx);
            auto it = s_options.find(var->key);
            if (it != s_options.end()) {
                static thread_local std::string s_optValueBuf;
                s_optValueBuf = it->second;
                var->value = s_optValueBuf.c_str();
                return true;
            }
            return false;
        }

        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            if (data) {
                *static_cast<bool*>(data) = s_optionsChanged.exchange(false,
                    std::memory_order_acq_rel);
            }
            return true;

        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            return false;

        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return false;

        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
            return false;

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
            return true;

        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            if (data) *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
            return true;

        // Handle both old (13) and new (14) values of SET_HW_RENDER.
        // Some prebuilt melonDS cores were compiled with an older libretro.h
        // where the value was 13, while our libretro.h uses 14.
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            // melonDS libretro core requests an OpenGL ES context for
            // hardware-accelerated 3D rendering. We must accept this
            // request, otherwise the 3D renderer fails and produces a
            // grey screen.
            if (!data) return false;
            auto* hw = static_cast<retro_hw_render_callback*>(data);

            // Store the core's reset/destroy callbacks so we can call
            // them at the appropriate time.
            s_hwContextReset = hw->context_reset;
            s_hwContextDestroy = hw->context_destroy;

            // Provide the frontend's get_current_framebuffer and
            // get_proc_address implementations.
            hw->get_current_framebuffer = hw_get_current_framebuffer;
            hw->get_proc_address = hw_get_proc_address;

            LOGI("HW render requested: type=%d, depth=%d, stencil=%d, "
                 "version=%d.%d, bottom_left=%d",
                 hw->context_type, hw->depth, hw->stencil,
                 hw->version_major, hw->version_minor,
                 hw->bottom_left_origin);

            // Create the EGL context matching the core's requested
            // context type and version. This must be done before
            // the core's context_reset is called, because the core
            // needs a valid GL context to initialize its resources.
            if (!createEglContext(hw->context_type,
                                  hw->version_major,
                                  hw->version_minor)) {
                LOGE("Failed to create EGL context for HW render");
                // Fall back: return false so the core uses software
                // rendering. If the core was compiled without software
                // renderer, it will fail gracefully.
                return false;
            }

            // Notify the core that the GL context is ready.
            // The core initializes its FBOs, shaders, textures, etc.
            // here. If context_reset is null, the core handles its
            // own initialization differently.
            if (s_hwContextReset) {
                LOGI("Calling HW context_reset");
                s_hwContextReset();
            }

            LOGI("HW render setup complete");
            return true;
        }

        default:
            return false;
    }
}

static void cb_video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) {
        LOGW("cb_video: data is NULL (dupe frame)");
        return;
    }

    // Auto-detect pixel format from pitch if the core didn't set it.
    // XRGB8888: pitch = width * 4, RGB565/0RGB1555: pitch = width * 2.
    if (s_pixelFormat == RETRO_PIXEL_FORMAT_0RGB1555 && pitch >= width * 4) {
        s_pixelFormat = RETRO_PIXEL_FORMAT_XRGB8888;
        LOGI("cb_video: auto-detected XRGB8888 from pitch=%zu (w=%u)", pitch, width);
    } else if (s_pixelFormat == RETRO_PIXEL_FORMAT_0RGB1555 && pitch == width * 2) {
        s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
    }

    s_videoW = width;
    s_videoH = height;

    {
        std::lock_guard<std::mutex> lk(s_frameMtx);

        const size_t need = (size_t)width * height;
        if (s_frameW != width || s_frameH != height || s_frame.size() < need) {
            LOGI("cb_video: %ux%u pitch=%zu fmt=%u (buffer resized)", width, height, pitch, s_pixelFormat);
            s_frame.resize(need);
            s_frameW = width;
            s_frameH = height;
        }

        if (s_pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
            // NEON-accelerated bulk conversion (16 px/iter). With the GL
            // hardware 3D renderer at scale >1 the frame is large (e.g.
            // 512x772 at 2x, 1024x1544 at 4x) — the old scalar per-pixel
            // loop cost multiple milliseconds per frame on the emulation
            // thread and was a major NDS performance bottleneck.
            coreshared::convertXrgbRowsToArgb(
                s_frame.data(),
                static_cast<const uint32_t*>(data),
                pitch / sizeof(uint32_t), width,
                width, height);
        } else if (s_pixelFormat == RETRO_PIXEL_FORMAT_RGB565) {
            const uint16_t* src = static_cast<const uint16_t*>(data);
            const size_t stride = pitch / sizeof(uint16_t);
            for (unsigned y = 0; y < height; ++y) {
                const uint16_t* srow = src + y * stride;
                uint32_t* drow = s_frame.data() + (size_t)y * width;
                for (unsigned x = 0; x < width; ++x) {
                    uint16_t px = srow[x];
                    uint32_t r5 = (px >> 11) & 0x1F;
                    uint32_t g6 = (px >> 5)  & 0x3F;
                    uint32_t b5 = px & 0x1F;
                    uint32_t r = (r5 << 3) | (r5 >> 2);
                    uint32_t g = (g6 << 2) | (g6 >> 4);
                    uint32_t b = (b5 << 3) | (b5 >> 2);
                    drow[x] = 0xFF000000u | (r << 16) | (g << 8) | b;
                }
            }
        } else {
            const uint16_t* src = static_cast<const uint16_t*>(data);
            const size_t stride = pitch / sizeof(uint16_t);
            for (unsigned y = 0; y < height; ++y) {
                const uint16_t* srow = src + y * stride;
                uint32_t* drow = s_frame.data() + (size_t)y * width;
                for (unsigned x = 0; x < width; ++x) {
                    uint16_t px = srow[x];
                    uint32_t r5 = (px >> 10) & 0x1F;
                    uint32_t g5 = (px >> 5)  & 0x1F;
                    uint32_t b5 = px & 0x1F;
                    uint32_t r = (r5 << 3) | (r5 >> 2);
                    uint32_t g = (g5 << 3) | (g5 >> 2);
                    uint32_t b = (b5 << 3) | (b5 >> 2);
                    drow[x] = 0xFF000000u | (r << 16) | (g << 8) | b;
                }
            }
        }
        s_newFrame.store(true, std::memory_order_release);
        // Monotonic frame stamp for the Kotlin-side draw throttling
        // (NdsDualScreenView only invalidates when this changes).
        s_frameStamp.fetch_add(1, std::memory_order_relaxed);
    }

    // Fast-forward frame skip — same pattern as fbneo_loader.
    if (s_fastForward.load(std::memory_order_relaxed)) {
        int skip = s_ffMaxSkip.load(std::memory_order_relaxed);
        if (skip > 0 && s_ffFrameSkip.fetch_add(1, std::memory_order_relaxed) % skip != 0)
            return;
    } else {
        s_ffFrameSkip.store(0, std::memory_order_relaxed);
    }

    const int filter = s_videoFilter.load(std::memory_order_relaxed);
    // 滤镜被跳过时给出一次性可见日志（此前是完全静默的 —— "滤镜失效"
    // 的根因之一就是无从得知被跳过）。GL 4x 帧做 CPU 4x 放大代价过高，
    // 属预期行为；其余情况出现即说明缓冲/上限需要跟进。
    if (filter >= 4 && filter <= 10) {
        const bool canUpscale = (width <= (unsigned)kMaxW && height <= (unsigned)kMaxH);
        if (!canUpscale && !s_filterSkipLogged) {
            s_filterSkipLogged = true;
            LOGW("Video filter %d skipped: frame %ux%u exceeds filter budget "
                 "%dx%d (GL resolution too high — the GL renderer already "
                 "upscales internally)", filter, width, height, kMaxW, kMaxH);
        }
    }
    coreshared::applyFilterAndBlit(
        s_window, s_windowMtx,
        s_frame.data(), width, height, width,
        filter,
        s_xbrBuffer2x, s_xbrBuffer4x, s_xbrMidBuffer,
        (unsigned)kMaxW, (unsigned)kMaxH,
        s_highQualityScaling.load(std::memory_order_relaxed));

    // ------------------------------------------------------------------
    // Custom dual-screen layout mode (no ANativeWindow attached): the
    // applyFilterAndBlit() call above is a no-op, so the upscale filters
    // (HQ2X / HQ4X / XBR) would be silently lost — the "自由布局下滤镜失效"
    // bug. Run the SAME filter pipeline into s_filteredFrame here on the
    // emulation thread (identical cost/threading to the surface path) and
    // let the Kotlin NdsDualScreenView pull it via getFilteredFrameBuffer().
    // Overlay filters (scanline/crt/dot, filter 1/2/3) are drawn by the
    // view itself and need no CPU work here.
    // ------------------------------------------------------------------
    {
        std::lock_guard<std::mutex> lk(s_windowMtx);
        const bool hasWindow = (s_window != nullptr);
        if (!hasWindow) {
            // 自定义布局路径的滤镜预算保持旧的 256x386（原始 1x 帧）：
            // 这条路径在每帧做一次整帧 assign 拷贝（surface 路径则是把
            // 滤镜输出直接 blit 到 ANativeWindow，无额外复制），放宽预算
            // 会引入每帧 6~25MB 的额外复制。GL 2x+ 分辨率下放大滤镜在此
            // 跳过 —— 帧本身已经比 1x 清晰，视图缩放由 Compose 层做。
            const bool canUpscale = (width <= 256u && height <= 386u);
            if ((filter == 4 || filter == 5 || filter == 7) && canUpscale) {
                // 2x filters: XBR(4) / HQ2X(5) / XBR+dot(7)
                s_filteredFrame.assign(s_xbrBuffer2x, s_xbrBuffer2x + (size_t)width * 2 * height * 2);
                s_filteredW = width * 2;
                s_filteredH = height * 2;
            } else if ((filter == 6 || filter == 8 || filter == 9 || filter == 10) && canUpscale) {
                // 4x filters: HQ4X(6) / 4XBR(8) / 4XBR+dot(9) / HQ4X+dot(10)
                s_filteredFrame.assign(s_xbrBuffer4x, s_xbrBuffer4x + (size_t)width * 4 * height * 4);
                s_filteredW = width * 4;
                s_filteredH = height * 4;
            } else {
                s_filteredFrame.clear();
                s_filteredW = 0;
                s_filteredH = 0;
            }
        }
        // Note: when a window IS attached, the surface path already applied
        // the filter and presented it — s_filteredFrame is not needed (the
        // Kotlin side never reads it in surface mode). We don't clear it
        // eagerly to avoid churn; filteredWidth()/Height() return the raw
        // dims whenever a window is attached.
    }
}

// Frame stamp — monotonic counter of frames pushed through cb_video().
// The Kotlin NdsDualScreenView polls this from its Choreographer callback
// so it only invalidates when a new frame actually arrived (no redundant
// redraws on 90/120 Hz screens). Defined here but incremented inside the
// frame-copy lock above via s_frameStamp.
uint64_t frameStamp() {
    return s_frameStamp.load(std::memory_order_relaxed);
}

static void cb_audio_sample(int16_t left, int16_t right) {
    int16_t pair[2] = {left, right};
    s_audio.push(pair, 2);
}

static size_t cb_audio_batch(const int16_t* data, size_t frames) {
    s_audio.push(data, frames * 2);
    return frames;
}

static void cb_input_poll() { /* state is read on demand */ }

static int16_t cb_input_state(unsigned port, unsigned device,
                              unsigned /*index*/, unsigned id) {
    if (device == RETRO_DEVICE_POINTER && port == 0) {
        switch (id) {
            case RETRO_DEVICE_ID_POINTER_X:        return s_touchX.load(std::memory_order_relaxed);
            case RETRO_DEVICE_ID_POINTER_Y:        return s_touchY.load(std::memory_order_relaxed);
            case RETRO_DEVICE_ID_POINTER_PRESSED:  return s_touchPressed.load(std::memory_order_relaxed) ? 1 : 0;
            default: return 0;
        }
    }
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    // DS supports up to 16 players via Download Play, but only pads 1-4
    // are exposed via this interface (covers all common multiplayer games).
    const uint16_t bits = (port == 0) ? s_pad1.load(std::memory_order_relaxed)
                         : (port == 1) ? s_pad2.load(std::memory_order_relaxed)
                         : (port == 2) ? s_pad3.load(std::memory_order_relaxed)
                         : (port == 3) ? s_pad4.load(std::memory_order_relaxed)
                                       : 0;
    if (id >= 16) return 0;
    return (bits >> id) & 1;
}

// ---------------------------------------------------------------------------
// File-extension helper. Returns lowercased extension (without the dot)
// of `path`, or "" if none.
// ---------------------------------------------------------------------------
static std::string getExtensionLower(const std::string& path) {
    size_t dot = path.find_last_of('.');
    if (dot == std::string::npos) return "";
    std::string ext = path.substr(dot + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(),
                   [](unsigned char c){ return (char)std::tolower(c); });
    return ext;
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------
std::string loadFromFile(const std::string& path, int& regionOut) {
    regionOut = 0;

    // Serialize against the emulation thread (stepFrame / save / load
    // state). The Kotlin side joins the previous emulation thread before
    // calling this, but the lock makes the invariant explicit and safe.
    std::lock_guard<std::recursive_mutex> lk(s_coreMtx);

    if (!loadCoreLib()) {
        return s_coreError.empty()
            ? "Failed to load libmelonds_libretro_android.so"
            : s_coreError;
    }

    if (!s_loaded) {
        initDefaultOptions();

        retro_set_environment(cb_environment);
        retro_set_video_refresh(cb_video);
        retro_set_audio_sample(cb_audio_sample);
        retro_set_audio_sample_batch(cb_audio_batch);
        retro_set_input_poll(cb_input_poll);
        retro_set_input_state(cb_input_state);

        retro_init();
        s_loaded = true;
        LOGI("melonDS core initialized (API version %u)",
             retro_api_version());
    }

    if (s_gameLoaded) {
        // NOTE: melonDS persists its SRAM to the .sav file itself inside
        // retro_unload_game() via Platform::WriteNDSSave. Do NOT call
        // coreshared::saveSrmToDisk first — it would write a redundant
        // .srm that could later race-condition with melonDS's own .sav
        // write (the .srm being a stale snapshot).
        retro_unload_game();
        s_gameLoaded = false;
    }

    s_audio.reset();
    s_resampler.reset();

    // BIOS directory is handed to the core via RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY
    // (cb_environment) — melonDS 0.9.3 has no melonds_sysfile_directory option.
    // Notify the core that options changed so it re-reads melonds_boot_directly
    // (+ anything set by applyCoreOptions) before launching the game.
    s_optionsChanged.store(true, std::memory_order_release);

    // DS ROMs are small (<512 MB max for the largest commercial carts), so
    // we pre-load them into memory and pass data + size to retro_load_game.
    // This is the standard libretro behavior for non-CD content and avoids
    // any path-based VFS interface negotiation with the core.
    FILE* fp = fopen(path.c_str(), "rb");
    if (!fp) {
        s_coreError = "Cannot open NDS ROM file: " + path;
        LOGE("%s", s_coreError.c_str());
        return s_coreError;
    }
    fseek(fp, 0, SEEK_END);
    long sz = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    if (sz <= 0) {
        fclose(fp);
        s_coreError = "NDS ROM file is empty or unreadable: " + path;
        LOGE("%s", s_coreError.c_str());
        return s_coreError;
    }
    if (sz > 512L * 1024 * 1024) {
        // Sanity cap — DS commercial carts max out at 512 MB. Anything
        // larger is almost certainly a corrupt file or a non-ROM file
        // accidentally selected.
        fclose(fp);
        s_coreError = "NDS ROM file is too large (>512MB): " + path;
        LOGE("%s", s_coreError.c_str());
        return s_coreError;
    }

    std::vector<uint8_t> romBuf((size_t)sz);
    size_t rd = fread(romBuf.data(), 1, (size_t)sz, fp);
    fclose(fp);
    if (rd != (size_t)sz) {
        s_coreError = "Failed to read entire NDS ROM: " + path;
        LOGE("%s", s_coreError.c_str());
        return s_coreError;
    }

    retro_game_info gameInfo{};
    // Store path in a static variable so gameInfo.path points to stable
    // memory that outlives the JNI GetStringUTFChars / ReleaseStringUTFChars
    // cycle. melonDS may read gameInfo.path asynchronously (e.g. for save-
    // state naming), and using the transient cpath pointer caused a crash.
    s_romPath = path;
    gameInfo.path = s_romPath.c_str();
    gameInfo.data = romBuf.data();
    gameInfo.size = sz;
    gameInfo.meta = nullptr;

    bool ok = retro_load_game(&gameInfo);

    if (!ok) {
        s_coreError = "retro_load_game() failed for: " + path;
        if (!s_coreMessage.empty()) {
            s_coreError += " (";
            s_coreError += s_coreMessage;
            s_coreError += ")";
            s_coreMessage.clear();
        }
        // Provide a detailed Chinese explanation of common melonDS load
        // failures. melonDS rejects ROMs for several reasons:
        //   1. Missing BIOS files — melonDS requires bios7.bin / bios9.bin /
        //      firmware.bin in the system directory. Without these, the core
        //      cannot boot any DS ROM. (NDS mode; DSi mode needs more files.)
        //   2. Encrypted ROM — modern commercial DS ROMs are not encrypted,
        //      but some homebrew / prototype ROMs are; melonDS cannot decrypt
        //      them. Use a decrypted version instead.
        //   3. Corrupted ROM file — incomplete download or bad dump.
        //   4. Unsupported ROM type — melonDS only supports .nds / .ids /
        //      .app / .srl / .dsi. Other formats (.zip / .7z) must be
        //      extracted first.
        //   5. DSi-only ROM loaded in DS mode — switch melonds_console_mode
        //      to "dsi" (requires DSi BIOS files).
        s_coreError += "\n\n常见原因:\n";
        s_coreError += "  1. BIOS 缺失: 需要 bios7.bin / bios9.bin / firmware.bin "
                        "放在 system 目录下 (Settings → 系统 → BIOS 管理). "
                        "DSi 模式额外需要 dsi_bios7.bin / dsi_bios9.bin / "
                        "dsi_firmware.bin / dsi_nand.bin.\n";
        s_coreError += "  2. ROM 被加密: 部分自制程序/原版卡带需要解密, "
                        "请使用已解密的 .nds 文件.\n";
        s_coreError += "  3. ROM 损坏: 文件下载不完整或校验失败, 请重新下载.\n";
        s_coreError += "  4. 格式不支持: 请使用未压缩的 .nds / .ids / .app / "
                        ".srl / .dsi 文件 (不要直接加载 .zip / .7z).\n";
        s_coreError += "  5. DSi 专属游戏: 若加载 DSi 专属 ROM, 请将 "
                        "melonds_console_mode 设为 'dsi' (需 DSi BIOS).\n";
        s_coreError += "\n详细帮助请查看: Settings → NDS → ROM 兼容性帮助";
        LOGE("%s", s_coreError.c_str());
        return s_coreError;
    }

    s_gameLoaded = true;
    s_lastRomPath = path;

    // CRITICAL: Unbind the EGL context from this (loader/JNI) thread so
    // the emulation thread can re-bind it via ensureEglContextCurrent().
    // On some Android EGL implementations, eglMakeCurrent() on a different
    // thread will fail if the context is still current on this thread.
    // The context was made current on this thread in createEglContext()
    // (called from cb_environment/RETRO_ENVIRONMENT_SET_HW_RENDER during
    // retro_load_game()). After unbinding, the emulation thread's
    // stepFrame() will re-bind it before each retro_run().
    if (s_eglInitialized) {
        eglMakeCurrent(s_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        LOGI("EGL context unbound from loader thread — emulation thread will re-bind");
    }

    // NOTE: melonDS manages its own .sav file via Platform::WriteNDSSave
    // (loaded in retro_load_game above; written in retro_unload_game and
    // on periodic SRAM flushes). Do NOT call coreshared::loadSramFromDisk
    // here — it would overwrite the .sav that was just loaded into the
    // cart's SRAM buffer with a .srm file that may be stale or empty.
    // Leaving it out keeps the .sav as the single source of truth,
    // matching how the reference melonDS Android APK works.

    // NOTE: Do NOT call retro_set_controller_port_device here.
    // The melonDS libretro core manages device types internally.
    // When melonds_touch_mode = "Touch" (the default), the core sets
    // port 0 to RETRO_DEVICE_POINTER so cb_input_state can return
    // touch coordinates. Overriding to JOYPAD here would prevent the
    // core from receiving POINTER input, breaking the touch screen.

    retro_system_av_info av{};
    retro_get_system_av_info(&av);
    s_sampleRate = (int)av.timing.sample_rate;
    // DS has no region concept (region-free hardware). We always report 0.
    s_region = 0;
    regionOut = s_region;
    s_videoW = av.geometry.base_width;
    s_videoH = av.geometry.base_height;

    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        s_frameW = av.geometry.base_width;
        s_frameH = av.geometry.base_height;
        if (s_frameW == 0) s_frameW = 256;
        if (s_frameH == 0) s_frameH = 384;
        s_frame.assign((size_t)s_frameW * s_frameH, 0xFF000000u);
    }

    s_audio.reset();
    s_newFrame.store(false);

    if (s_sampleRate > 0) {
        // Default audio output — pure passthrough (src == dst), no TV-mode
        // 48kHz forced resampling. AudioTrack opens at the core's own rate.
        s_resampler.init(s_sampleRate, s_sampleRate);
        LOGI("Audio passthrough: %d Hz (ratio=%.6f, active=%d)",
             s_sampleRate, s_resampler.ratio, s_resampler.active ? 1 : 0);
    }

    LOGI("NDS ROM loaded: %s  rate=%d  fps=%.2f  region=%d  geom=%ux%u  max=%ux%u",
         path.c_str(), s_sampleRate, av.timing.fps, s_region,
         av.geometry.base_width, av.geometry.base_height,
         av.geometry.max_width, av.geometry.max_height);
    return "";
}

void unload() {
    // Hold the core mutex while the core is torn down — retro_unload_game
    // flushes the .sav and destroys the NDS instance; it must not race a
    // pending stepFrame / saveState call.
    std::lock_guard<std::recursive_mutex> lk(s_coreMtx);
    if (s_loaded) {
        if (s_gameLoaded) {
            // NOTE: melonDS persists its SRAM to .sav inside retro_unload_game()
            // via Platform::WriteNDSSave. Do NOT call coreshared::saveSrmToDisk
            // first — see loadFromFile() for the rationale.

            // Notify the core that the GL context is about to be destroyed.
            // The core cleans up its OpenGL resources (FBOs, shaders, etc.)
            // in this callback. Must be called before retro_unload_game()
            // because the core may need a valid GL context to clean up.
            if (s_eglInitialized && s_hwContextDestroy) {
                if (ensureEglContextCurrent()) {
                    LOGI("Calling HW context_destroy");
                    s_hwContextDestroy();
                } else {
                    LOGW("Cannot make EGL context current for context_destroy, skipping");
                }
            }

            retro_unload_game();
            s_gameLoaded = false;
        }

        retro_deinit();
        s_loaded = false;

        // Destroy EGL context AFTER retro_deinit() so the core can
        // clean up any remaining GL resources during deinitialization.
        destroyEglContext();
    }
    s_hwContextReset = nullptr;
    s_hwContextDestroy = nullptr;
    s_sampleRate = 0;
    s_region = 0;
    s_audio.reset();
    s_resampler.reset();
    s_newFrame.store(false);
    s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        s_frame.clear();
        s_frameW = 0;
        s_frameH = 0;
    }
    s_videoW = 256;
    s_videoH = 384;
    s_lastRomPath.clear();
    s_saveName.clear();
    s_pad1.store(0, std::memory_order_relaxed);
    s_pad2.store(0, std::memory_order_relaxed);
    s_pad3.store(0, std::memory_order_relaxed);
    s_pad4.store(0, std::memory_order_relaxed);
}

void resetEmulation(bool /*hard*/) {
    // Hold the core mutex: resetEmulation is called from the UI thread
    // while the emulation thread may be inside retro_run.
    std::lock_guard<std::recursive_mutex> lk(s_coreMtx);
    if (s_loaded && s_gameLoaded) retro_reset();
}

void stepFrame() {
    if (!s_loaded || !s_gameLoaded) {
        LOGW("stepFrame: not loaded (s_loaded=%d s_gameLoaded=%d)", s_loaded, s_gameLoaded);
        return;
    }

    // Serialize retro_run against save/load-state and reset, which run on
    // the UI thread. Without this, DoSavestate can walk emulator state
    // while NDS::RunFrame is mutating it (corrupt savestates / crashes).
    std::lock_guard<std::recursive_mutex> lk(s_coreMtx);

    // Ensure the EGL context is current on the emulation thread
    // before calling retro_run(). The melonDS core's 3D renderer
    // needs a valid GL context to render 3D content. Without this,
    // the 3D layer renders as grey.
    //
    // NOTE: If the EGL context can't be made current, we log a
    // warning but still proceed with the frame. The core's 3D
    // renderer may produce a grey screen, but the 2D layers and
    // audio will still work. This is better than skipping the
    // frame entirely, which would freeze both screens.
    if (s_eglInitialized) {
        if (!ensureEglContextCurrent()) {
            LOGW("ensureEglContextCurrent failed, 3D may be grey but continuing");
        }
    }

    retro_run();
}

bool copyFramebufferARGB(uint32_t* out, int w, int h) {
    if (!out) return false;
    if (!s_loaded || !s_gameLoaded || s_frame.empty()) {
        std::memset(out, 0, (size_t)w * h * sizeof(uint32_t));
        return false;
    }
    std::lock_guard<std::mutex> lk(s_frameMtx);
    const int cw = (w < (int)s_frameW) ? w : (int)s_frameW;
    const int ch = (h < (int)s_frameH) ? h : (int)s_frameH;
    for (int y = 0; y < ch; ++y) {
        std::memcpy(out + (size_t)y * w,
                    s_frame.data() + (size_t)y * s_frameW,
                    (size_t)cw * sizeof(uint32_t));
    }
    return s_newFrame.exchange(false, std::memory_order_acq_rel);
}

int filteredWidth() {
    // In custom-layout mode (no window) with an active upscale filter this
    // returns the upscaled width; otherwise the raw frame width.
    {
        std::lock_guard<std::mutex> lk(s_windowMtx);
        if (s_window == nullptr && s_filteredW > 0) return (int)s_filteredW;
    }
    return (int)s_videoW;
}

int filteredHeight() {
    {
        std::lock_guard<std::mutex> lk(s_windowMtx);
        if (s_window == nullptr && s_filteredH > 0) return (int)s_filteredH;
    }
    return (int)s_videoH;
}

bool copyFilteredFramebufferARGB(uint32_t* out, int w, int h) {
    // Returns the frame the dual-screen view should draw: the upscaled
    // filtered frame when active (custom layout + HQ2X/HQ4X/XBR), the raw
    // frame otherwise. Same calling convention as copyFramebufferARGB.
    // NOTE: called from the emulation thread right after stepFrame(), the
    // same thread that runs cb_video() — no data race on s_filteredFrame.
    if (!out) return false;
    {
        std::lock_guard<std::mutex> lk(s_windowMtx);
        if (s_window == nullptr && s_filteredW > 0 && s_filteredH > 0) {
            if (w < (int)s_filteredW || h < (int)s_filteredH) return false;
            size_t n = (size_t)s_filteredW * s_filteredH;
            if (s_filteredFrame.size() < n) return false;
            std::memcpy(out, s_filteredFrame.data(), n * sizeof(uint32_t));
            return true;
        }
    }
    return copyFramebufferARGB(out, w, h);
}

int readAudio(int16_t* out, int maxFrames) {
    if (!s_loaded || !s_gameLoaded) return 0;
    return s_resampler.readResampled(s_audio, out, maxFrames);
}

int audioSampleRate() { return s_sampleRate; }
int audioTargetSampleRate() { return s_sampleRate; }  // default audio == core rate

void setControllerInput(int port, uint16_t bits) {
    if (port == 0)      s_pad1.store(bits, std::memory_order_relaxed);
    else if (port == 1) s_pad2.store(bits, std::memory_order_relaxed);
    else if (port == 2) s_pad3.store(bits, std::memory_order_relaxed);
    else if (port == 3) s_pad4.store(bits, std::memory_order_relaxed);
}

void setTouchInput(int x, int y, bool pressed) {
    s_touchX.store((int16_t)x, std::memory_order_relaxed);
    s_touchY.store((int16_t)y, std::memory_order_relaxed);
    s_touchPressed.store(pressed, std::memory_order_relaxed);
    // Legacy composite-frame path — clear the direct-pixel override so the
    // core's Touch branch reads the POINTER device state above.
    touch_direct_mode.store(false, std::memory_order_relaxed);
}

void setTouchInputDirect(int x, int y, bool pressed) {
    // Direct bottom-screen pixel coordinates (0..255 / 0..191), matching the
    // official melonDS Android frontend (onScreenTouch -> NDS::TouchScreen).
    // The core clamps and applies them verbatim — no composite-frame
    // geometry involved, so custom layouts / gaps / GL rows can't break it.
    touch_direct_x.store((int16_t)x, std::memory_order_relaxed);
    touch_direct_y.store((int16_t)y, std::memory_order_relaxed);
    touch_direct_pressed.store(pressed, std::memory_order_relaxed);
    touch_direct_mode.store(true, std::memory_order_relaxed);
}

void setPaths(const std::string& systemDir, const std::string& saveDir) {
    s_systemDir = systemDir;
    s_saveDir = saveDir;
}

void setSaveName(const std::string& name) {
    s_saveName = name;
    LOGI("SRAM save name set: '%s'", name.c_str());
    // Propagate the save-basename override to the melonDS libretro core so
    // it writes its .sav as "<saveDir>/<name>.sav" instead of deriving the
    // basename from info->path (which is "temp_rom.<ext>" for cached URIs
    // and would clobber saves across games).
    ::melonds_set_save_basename_override(name.c_str());
}

void applyRegion(int /*region*/) { /* DS is region-free — ignored */ }
void applySampleRate(int /*hz*/) { /* fixed by the core */ }
void applySpeed(float multiplier) {
    s_fastForward.store(multiplier > 1.0f, std::memory_order_relaxed);
    s_ffMaxSkip.store((int)multiplier, std::memory_order_relaxed);
    s_ffFrameSkip.store(0, std::memory_order_relaxed);
}

bool saveStateToPath(int /*slot*/, const std::string& path) {
    if (!s_loaded || !s_gameLoaded) return false;
    // Hold the core mutex across BOTH retro_serialize_size and
    // retro_serialize so the state cannot change between the two passes
    // (a size change mid-way would make the second pass overflow the
    // buffer allocated from the first pass) and so DoSavestate never runs
    // concurrently with retro_run on the emulation thread.
    std::lock_guard<std::recursive_mutex> lk(s_coreMtx);
    size_t sz = retro_serialize_size();
    if (sz == 0) {
        LOGE("retro_serialize_size returned 0 — cannot save state (DSi mode or no game)");
        return false;
    }
    std::vector<uint8_t> buf(sz);
    // Zero-initialise so a partial write (savestate->Error) doesn't leave
    // uninitialized garbage that would later be misread as a valid state.
    std::memset(buf.data(), 0, sz);
    if (!retro_serialize(buf.data(), sz)) {
        LOGE("retro_serialize failed — not writing save state file to %s", path.c_str());
        return false;
    }
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) { LOGE("Cannot open save state for write: %s", path.c_str()); return false; }
    size_t wr = std::fwrite(buf.data(), 1, sz, f);
    std::fclose(f);
    if (wr != sz) {
        LOGE("Short write on save state %s (%zu/%zu bytes)", path.c_str(), wr, sz);
        return false;
    }
    LOGI("NDS save state written: %zu bytes to %s", sz, path.c_str());
    return true;
}

bool loadStateFromPath(int /*slot*/, const std::string& path) {
    if (!s_loaded || !s_gameLoaded) return false;
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    long sz = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (sz <= 0) { std::fclose(f); return false; }
    std::vector<uint8_t> buf((size_t)sz);
    size_t rd = std::fread(buf.data(), 1, (size_t)sz, f);
    std::fclose(f);
    if (rd != (size_t)sz) return false;
    // Hold the core mutex while the state is restored into the NDS
    // instance — retro_unserialize rewrites the entire emulator state and
    // must not race retro_run on the emulation thread.
    std::lock_guard<std::recursive_mutex> lk(s_coreMtx);
    if (!retro_unserialize(buf.data(), sz)) {
        LOGE("retro_unserialize failed for %s (size=%ld) — save state may be from a different version",
             path.c_str(), sz);
        return false;
    }
    LOGI("NDS save state loaded: %ld bytes from %s", sz, path.c_str());
    return true;
}

void setSurface(void* nativeWindow) {
    coreshared::setSurface(s_window, s_windowMtx, nativeWindow);
    if (nativeWindow) {
        if (s_highQualityScaling.load(std::memory_order_relaxed)) {
            ANativeWindow_setBuffersGeometry(s_window, 0, 0, WINDOW_FORMAT_RGBA_8888);
        }
        LOGI("Surface attached (pixelFormat=%u, surface=RGBA_8888, hqScaling=%d)",
             s_pixelFormat, s_highQualityScaling.load() ? 1 : 0);
    } else {
        LOGI("Surface detached");
    }
}

void setHighQualityScaling(bool enabled) {
    s_highQualityScaling.store(enabled, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lk(s_windowMtx);
    if (s_window) {
        if (enabled) {
            ANativeWindow_setBuffersGeometry(s_window, 0, 0, WINDOW_FORMAT_RGBA_8888);
        } else {
            ANativeWindow_setBuffersGeometry(s_window, s_videoW, s_videoH, WINDOW_FORMAT_RGBA_8888);
        }
    }
    LOGI("High-quality scaling: %s", enabled ? "ON" : "OFF");
}

void setCoreOption(const std::string& key, const std::string& value) {
    {
        std::lock_guard<std::mutex> lk(s_optMtx);
        s_options[key] = value;
    }
    s_optionsChanged.store(true, std::memory_order_release);
    LOGI("Core option set: %s = %s", key.c_str(), value.c_str());
}

int videoWidth()  { return (int)s_videoW; }
int videoHeight() { return (int)s_videoH; }

void videoAspectRatio(int& num, int& den) {
    // Return aspect ratio based on the active melonds_screen_layout option.
    // melonDS composites both DS screens into a single framebuffer:
    //   "Top/Bottom"/"Bottom/Top": 256x384 -> 2:3 (two 4:3 screens stacked)
    //   "Left/Right"/"Right/Left": 512x192 -> 8:3 (two 4:3 screens side by side)
    //   "Top Only"/"Bottom Only":  256x192 -> 4:3 (single screen)
    std::string layout;
    {
        std::lock_guard<std::mutex> lk(s_optMtx);
        auto it = s_options.find("melonds_screen_layout");
        if (it != s_options.end()) layout = it->second;
    }

    if (layout == "Left/Right" || layout == "Right/Left") {
        num = 8; den = 3;
    } else if (layout == "Top Only" || layout == "Bottom Only") {
        num = 4; den = 3;
    } else {
        // Default: "Top/Bottom" (256x384) -> 2:3
        num = 2; den = 3;
    }
}

void setVideoFilter(int filter) {
    s_videoFilter.store(filter, std::memory_order_relaxed);
    LOGI("Video filter set: %d", filter);
}

bool isCoreLoaded() {
    // Always true — melonDS libretro wrapper is statically linked
    return true;
}

} // namespace ndscore::rom
