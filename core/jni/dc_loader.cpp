// SPDX-License-Identifier: MIT
// libretro frontend that drives the prebuilt SEGA Dreamcast / NAOMI /
// AtomisWave core (libretro Flycast).
//
// This loader follows the same dlopen() pattern as psx_loader.cpp:
// instead of statically linking the flycast source tree (which is huge and
// requires its own GL/Vulkan renderer build), we dlopen() the prebuilt
// libflycast_libretro_android.so at runtime and resolve the retro_* symbols
// via dlsym(). The .so ships in app/src/main/jniLibs/<abi>/ (official
// libretro buildbot build — same source as the RetroArch core, so DC behaves
// exactly like the other dlopen'd cores in this app).
//
// ═══════════════════════════════════════════════════════════════════════
// HARDWARE RENDERING — the one big difference to the software cores:
//
// Flycast is a GPU-rendering core (OpenGL ES 3). It renders the final frame
// into the framebuffer returned by our get_current_framebuffer() callback
// and signals completion via video_cb(RETRO_HW_FRAME_BUFFER_VALID, ...).
// The frontend must therefore:
//   1. Accept RETRO_ENVIRONMENT_SET_HW_RENDER and create an EGL context
//      matching the core's request (GLES 3).
//   2. Provide an FBO (color texture + depth24/stencil8 — the PVR renderer
//      needs depth & stencil) for the core to render into.
//   3. After each retro_run(): blit the FBO to the ANativeWindow backbuffer
//      (scaled to the window) and call eglSwapBuffers().
//
// We steer the core away from its Vulkan backend via
// RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER → RETRO_HW_CONTEXT_OPENGLES3,
// so only the GL path is exercised (identical to RetroArch running the GL
// video driver).
//
// BIOS / system files follow the RetroArch convention for this core:
//   <systemDir>/dc/dc_boot.bin + dc_flash.bin  (Dreamcast)
//   <systemDir>/dc/naomi.zip / awbios.zip ...  (NAOMI / AtomisWave sets)
// i.e. with NesStation passing <filesDir> as the system directory the files
// live in <filesDir>/dc/ — the platform folder, seeded from assets/dc/ on
// startup (see NesApp.ensureDcBios). VMU saves (vmu_save_*.bin) live in the
// save directory like every other core's save data.
// ═══════════════════════════════════════════════════════════════════════

#include "dc_loader.h"
#include <cstdarg>
#include "shared/core_shared.h"

#include <libretro.h>
#include <android/log.h>
#include <android/native_window.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <dlfcn.h>
#include <unistd.h>
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

#define TAG "dccore-rom"
#undef LOGI
#undef LOGW
#undef LOGE
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace dccore::rom {

// ---------------------------------------------------------------------------
// Software-fallback frame budget. In HW mode the FBO is sized dynamically
// from the core's reported geometry; these limits only guard the CPU-side
// filter buffers used if the core ever emits software pixels (it does not
// in practice — flycast always HW-renders).
// DC internal resolutions: 320x240 .. 3840x2880 (6x). The filter budget
// covers the common 1x/2x range; upscale filters are skipped above it
// (same behavior as the NDS GL path in nds_loader.cpp).
// ---------------------------------------------------------------------------
static constexpr int kMaxW = 1280;
static constexpr int kMaxH = 1024;

// ---------------------------------------------------------------------------
// libretro function pointer types
// ---------------------------------------------------------------------------
typedef void   (*retro_init_t)(void);
typedef void   (*retro_deinit_t)(void);
typedef unsigned (*retro_api_version_t)(void);
typedef void   (*retro_get_system_info_t)(struct retro_system_info* info);
typedef void   (*retro_get_system_av_info_t)(struct retro_system_av_info* info);
typedef void   (*retro_set_controller_port_device_t)(unsigned port, unsigned device);
typedef void   (*retro_reset_t)(void);
typedef void   (*retro_run_t)(void);
typedef size_t (*retro_serialize_size_t)(void);
typedef bool   (*retro_serialize_t)(void* data, size_t size);
typedef bool   (*retro_unserialize_t)(const void* data, size_t size);
typedef void*  (*retro_get_memory_data_t)(unsigned id);
typedef size_t (*retro_get_memory_size_t)(unsigned id);
typedef bool   (*retro_load_game_t)(const struct retro_game_info* game);
typedef void   (*retro_unload_game_t)(void);
typedef void   (*retro_set_environment_t)(retro_environment_t);
typedef void   (*retro_set_video_refresh_t)(retro_video_refresh_t);
typedef void   (*retro_set_audio_sample_t)(retro_audio_sample_t);
typedef void   (*retro_set_audio_sample_batch_t)(retro_audio_sample_batch_t);
typedef void   (*retro_set_input_poll_t)(retro_input_poll_t);
typedef void   (*retro_set_input_state_t)(retro_input_state_t);

// ---------------------------------------------------------------------------
// State — dlopen handle and resolved symbols
// ---------------------------------------------------------------------------
static void* s_coreLib = nullptr;

static retro_init_t                      s_retro_init = nullptr;
static retro_deinit_t                    s_retro_deinit = nullptr;
static retro_api_version_t               s_retro_api_version = nullptr;
static retro_get_system_info_t           s_retro_get_system_info = nullptr;
static retro_get_system_av_info_t        s_retro_get_system_av_info = nullptr;
static retro_set_controller_port_device_t s_retro_set_controller_port_device = nullptr;
static retro_reset_t                     s_retro_reset = nullptr;
static retro_run_t                       s_retro_run = nullptr;
static retro_serialize_size_t            s_retro_serialize_size = nullptr;
static retro_serialize_t                 s_retro_serialize = nullptr;
static retro_unserialize_t               s_retro_unserialize = nullptr;
static retro_get_memory_data_t           s_retro_get_memory_data = nullptr;
static retro_get_memory_size_t           s_retro_get_memory_size = nullptr;
static retro_load_game_t                 s_retro_load_game = nullptr;
static retro_unload_game_t               s_retro_unload_game = nullptr;
static retro_set_environment_t           s_retro_set_environment = nullptr;
static retro_set_video_refresh_t         s_retro_set_video_refresh = nullptr;
static retro_set_audio_sample_t          s_retro_set_audio_sample = nullptr;
static retro_set_audio_sample_batch_t    s_retro_set_audio_sample_batch = nullptr;
static retro_set_input_poll_t            s_retro_set_input_poll = nullptr;
static retro_set_input_state_t           s_retro_set_input_state = nullptr;

static bool s_loaded = false;
static bool s_gameLoaded = false;
static int  s_sampleRate = 0;
static double s_refreshRate = 60.0;
static int  s_region = 0;
static std::string s_systemDir;
static std::string s_saveDir;
static std::string s_saveName;
static std::string s_lastRomPath;
static std::string s_coreMessage;
static std::string s_coreError;
static std::string s_coreLibPath;

// Persistent copy of the currently-loaded ROM path. Flycast reads
// retro_game_info.path for content identification (game-id for per-content
// VMUs / cheats / texture packs) — the pointer must outlive the JNI
// GetStringUTFChars / ReleaseStringUTFChars cycle.
static std::string s_romPath;

// Dynamic frame buffer (ARGB, 0xAARRGGBB) — used for the software-video
// fallback path and for UI screenshots (captured from the FBO on demand).
static std::mutex s_frameMtx;
static std::vector<uint32_t> s_frame;
static unsigned s_frameW = 0;
static unsigned s_frameH = 0;
static std::atomic<bool> s_newFrame{false};

static unsigned s_videoW = 640;
static unsigned s_videoH = 480;
static unsigned s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;

// FPS HUD：核心真实提交帧计数（cb_video 非空 data 时 +1，UI 每秒读走清零）。
static std::atomic<int> s_presentedFrames{0};

// Gamepad bits (port 0..3, RETRO_DEVICE_JOYPAD, libretro standard ids).
static std::atomic<uint16_t> s_pad1{0};
static std::atomic<uint16_t> s_pad2{0};
static std::atomic<uint16_t> s_pad3{0};
static std::atomic<uint16_t> s_pad4{0};

static std::atomic<int>  s_videoFilter{0};
static std::atomic<bool> s_highQualityScaling{false};
static std::atomic<bool> s_fastForward{false};
static std::atomic<int>  s_ffFrameSkip{0};
static std::atomic<int>  s_ffMaxSkip{6};

// 2x / 4x upscale buffers for XBR / HQ2X / HQ4X filters (software fallback
// only; unused in HW mode).
static uint32_t s_xbrBuffer2x[kMaxW * kMaxH * 2 * 2];
static uint32_t s_xbrBuffer4x[kMaxW * kMaxH * 4 * 4];
static uint32_t s_xbrMidBuffer[kMaxW * kMaxH * 2 * 2];

static coreshared::AudioRingBuffer s_audio;
static coreshared::AudioResampler s_resampler;

// Core options (reicast_* — keys/values MUST match the core's
// libretro_core_options.h exactly; wrong keys are silently ignored).
static std::mutex s_optMtx;
static std::map<std::string, std::string> s_options;
static std::atomic<bool> s_optionsChanged{false};

// Controller-port device switching requested from the UI thread — applied on
// the emulation thread before the next frame (same pattern as psx_loader).
static std::atomic<uint32_t> s_pendingPortDevices{0xFFu << 24};

// ---------------------------------------------------------------------------
// EGL / OpenGL ES context — required by flycast (hardware renderer).
//
// Unlike the melonDS EGL path (nds_loader.cpp — offscreen Pbuffer, core
// reads back its own FBOs), flycast's final output lives in the framebuffer
// WE provide, and presentation happens via eglSwapBuffers on a WINDOW
// surface. Layout:
//   * EGLDisplay / EGLConfig / EGLContext — created once, shared lifetime.
//   * EGLSurface — a window surface bound to the current ANativeWindow when
//     one is attached, otherwise a 1x1 Pbuffer fallback (the core still
//     renders into our FBO; only the final blit/swap is skipped).
// The context is used on the emulation thread (stepFrame) and briefly on
// the loading thread (retro_load_game → context_reset) — bound/unbound
// exactly like the melonDS flow (unbind after load, re-bind per frame).
// ---------------------------------------------------------------------------
static EGLDisplay s_eglDisplay = EGL_NO_DISPLAY;
static EGLContext s_eglContext = EGL_NO_CONTEXT;
static EGLConfig  s_eglConfig = nullptr;
static EGLSurface s_eglSurface = EGL_NO_SURFACE;      // window or pbuffer
static EGLSurface s_eglWindowSurface = EGL_NO_SURFACE;
static EGLSurface s_eglPbufferSurface = EGL_NO_SURFACE;
static bool s_eglInitialized = false;
static std::mutex s_eglMtx;                            // guards surface (re)creation

// libretro HW render callbacks — provided by the core via SET_HW_RENDER.
static retro_hw_context_reset_t s_hwContextReset = nullptr;
static retro_hw_context_reset_t s_hwContextDestroy = nullptr;
static std::atomic<bool> s_hwRenderActive{false};

// Our FBO the core renders into (color texture + depth/stencil RB).
static GLuint s_fbo = 0;
static GLuint s_fboTex = 0;
static GLuint s_fboDepthStencil = 0;
static unsigned s_fboW = 0;
static unsigned s_fboH = 0;
static std::atomic<bool> s_fboNeedsResize{false};

// Window size cache for the blit (queried on setSurface).
static int s_winW = 0;
static int s_winH = 0;

// Screenshot request — performed inside stepFrame right after retro_run()
// while the GL context and FBO are current (UI thread cannot touch GL).
static std::atomic<bool> s_captureRequested{false};
static std::atomic<bool> s_captureDone{false};

// ANativeWindow handle for the defensive software-video fallback path
// (applyFilterAndBlit). Ref-counted via coreshared::setSurface. The HW
// presentation path uses the EGL window surface instead.
static ANativeWindow* s_softwareWindow = nullptr;
static std::mutex s_softwareWindowMtx;

// ---------------------------------------------------------------------------
// Initialize flycast core options with sensible defaults.
// Keys and VALUES verified against the shipped libflycast_libretro_android.so
// (string-scanned the binary: reicast_* keys + exact value strings such as
// "640x480 (Native)" / "per-triangle (normal)").
// Wrong keys/values cause the core to ignore the option and use its own
// default — no crash, just no effect.
// ---------------------------------------------------------------------------
static void initDefaultOptions() {
    // Only fill defaults for keys that have not already been set by the UI
    // (applyCoreOptions -> setCoreOption runs BEFORE loadRom on first load).
    auto setIfMissing = [](const std::string& key, const std::string& val) {
        if (s_options.find(key) == s_options.end()) {
            s_options[key] = val;
        }
    };

    // --- Video / GPU ---
    setIfMissing("reicast_internal_resolution", "640x480 (Native)"); // 1x — fastest; UI offers up to x6
    setIfMissing("reicast_alpha_sorting",       "per-triangle (normal)"); // per-strip(fastest)/per-triangle/per-pixel(accurate)
    setIfMissing("reicast_threaded_rendering",  "enabled");       // multi-core rendering thread (perf critical)
    setIfMissing("reicast_delay_frame_swapping","enabled");       // Android default: less tearing
    setIfMissing("reicast_frame_skipping",      "disabled");
    setIfMissing("reicast_widescreen_hack",     "disabled");      // 16:9 stretch of the 3D frustum
    setIfMissing("reicast_widescreen_cheats",   "disabled");      // per-game 16:9 cheats
    setIfMissing("reicast_gdrom_fast_loading",  "enabled");       // shorter load screens

    // --- System / BIOS ---
    setIfMissing("reicast_hle_bios",            "disabled");      // use the bundled real BIOS
    setIfMissing("reicast_dc_32mb_mod",         "disabled");      // 16MB stock RAM
    setIfMissing("reicast_force_wince",         "disabled");      // WinCE games need a forced toggle

    // --- Audio ---
    setIfMissing("reicast_enable_dsp",          "disabled");      // AICA DSP (a few games want it)
    setIfMissing("reicast_volume_modifier_enable", "enabled");    // DS emulation path some games need
}

// ---------------------------------------------------------------------------
// dlopen the core .so and resolve all retro_* symbols.
// ---------------------------------------------------------------------------
static bool loadCoreLib() {
    if (s_coreLib) return true;

    std::vector<std::string> candidates;
    if (!s_coreLibPath.empty()) candidates.push_back(s_coreLibPath);
    candidates.push_back("libflycast_libretro_android.so");

    const char* lastDlError = nullptr;
    for (const auto& name : candidates) {
        s_coreLib = dlopen(name.c_str(), RTLD_NOW);
        if (s_coreLib) {
            LOGI("dlopen(%s) OK", name.c_str());
            break;
        } else {
            lastDlError = dlerror();
            LOGW("dlopen(%s) failed: %s", name.c_str(),
                 lastDlError ? lastDlError : "(unknown)");
        }
    }

    if (!s_coreLib) {
        s_coreError = "dlopen(libflycast_libretro_android.so) failed: ";
        s_coreError += (lastDlError ? lastDlError : "(unknown)");
        LOGE("%s", s_coreError.c_str());
        return false;
    }

    #define RESOLVE(name) \
        s_##name = reinterpret_cast<name##_t>(dlsym(s_coreLib, #name)); \
        if (!s_##name) { \
            const char* _dlerr = dlerror(); \
            s_coreError = "dlsym(" #name ") failed: "; \
            s_coreError += (_dlerr ? _dlerr : "(unknown)"); \
            LOGE("%s", s_coreError.c_str()); \
            dlclose(s_coreLib); s_coreLib = nullptr; \
            return false; \
        }

    RESOLVE(retro_init);
    RESOLVE(retro_deinit);
    RESOLVE(retro_api_version);
    RESOLVE(retro_get_system_info);
    RESOLVE(retro_get_system_av_info);
    RESOLVE(retro_set_controller_port_device);
    RESOLVE(retro_reset);
    RESOLVE(retro_run);
    RESOLVE(retro_serialize_size);
    RESOLVE(retro_serialize);
    RESOLVE(retro_unserialize);
    RESOLVE(retro_load_game);
    RESOLVE(retro_unload_game);
    RESOLVE(retro_set_environment);
    RESOLVE(retro_set_video_refresh);
    RESOLVE(retro_set_audio_sample);
    RESOLVE(retro_set_audio_sample_batch);
    RESOLVE(retro_set_input_poll);
    RESOLVE(retro_set_input_state);

    // Optional — flycast exposes RETRO_MEMORY_SAVE_RAM for VMU persistence.
    s_retro_get_memory_data = reinterpret_cast<retro_get_memory_data_t>(
        dlsym(s_coreLib, "retro_get_memory_data"));
    s_retro_get_memory_size = reinterpret_cast<retro_get_memory_size_t>(
        dlsym(s_coreLib, "retro_get_memory_size"));

    #undef RESOLVE

    LOGI("All retro_* symbols resolved");
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
    __android_log_vprint(prio, "flycast", fmt, ap);
    va_end(ap);
}

// ---------------------------------------------------------------------------
// EGL context (created once) — ES 3.x with depth+stencil (flycast requires
// both for the PVR renderer's tile-based effects).
// ---------------------------------------------------------------------------
static bool ensureEglDisplayAndContext() {
    if (s_eglInitialized) return true;

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

    // RGBA8888 + depth24 + stencil8 — flycast's HW render request sets
    // depth=true, stencil=true (PVR tile accelerator emulation).
    const EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RED_SIZE,        8,
        EGL_GREEN_SIZE,      8,
        EGL_BLUE_SIZE,       8,
        EGL_ALPHA_SIZE,      8,
        EGL_DEPTH_SIZE,      24,
        EGL_STENCIL_SIZE,    8,
        EGL_NONE
    };
    EGLint numConfigs = 0;
    if (!eglChooseConfig(s_eglDisplay, attribs, &s_eglConfig, 1, &numConfigs) ||
        numConfigs == 0) {
        LOGE("eglChooseConfig failed: 0x%x", eglGetError());
        eglTerminate(s_eglDisplay);
        s_eglDisplay = EGL_NO_DISPLAY;
        return false;
    }

    // Create an ES 3 context (flycast needs GLES3; try 3.2 → 3.1 → 3.0).
    static const int tryCtx[][2] = { {3,2}, {3,1}, {3,0} };
    for (const auto& vc : tryCtx) {
        const EGLint ctxAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, vc[0],
            EGL_CONTEXT_MINOR_VERSION,  vc[1],
            EGL_NONE
        };
        s_eglContext = eglCreateContext(s_eglDisplay, s_eglConfig,
                                        EGL_NO_CONTEXT, ctxAttribs);
        if (s_eglContext != EGL_NO_CONTEXT) {
            LOGI("EGL context created: ES %d.%d", vc[0], vc[1]);
            break;
        }
        LOGW("eglCreateContext ES %d.%d failed (0x%x), trying next",
             vc[0], vc[1], eglGetError());
    }
    if (s_eglContext == EGL_NO_CONTEXT) {
        // Last resort: plain ES 3.0 (no minor version attribute).
        const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
        s_eglContext = eglCreateContext(s_eglDisplay, s_eglConfig,
                                        EGL_NO_CONTEXT, ctxAttribs);
        if (s_eglContext == EGL_NO_CONTEXT) {
            LOGE("eglCreateContext failed for all ES versions");
            eglTerminate(s_eglDisplay);
            s_eglDisplay = EGL_NO_DISPLAY;
            return false;
        }
        LOGI("EGL context created: ES 3.0");
    }

    // Pbuffer fallback surface (1x1) — used when no window surface exists
    // so the context can still be made current for core init / rendering.
    const EGLint pbAttribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    s_eglPbufferSurface = eglCreatePbufferSurface(s_eglDisplay, s_eglConfig, pbAttribs);
    if (s_eglPbufferSurface == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface failed: 0x%x", eglGetError());
        eglDestroyContext(s_eglDisplay, s_eglContext);
        s_eglContext = EGL_NO_CONTEXT;
        eglTerminate(s_eglDisplay);
        s_eglDisplay = EGL_NO_DISPLAY;
        return false;
    }
    s_eglSurface = s_eglPbufferSurface;

    s_eglInitialized = true;
    return true;
}

// Make the shared context current on the calling thread.
static bool ensureEglContextCurrent() {
    if (!s_eglInitialized) return false;
    if (eglGetCurrentContext() == s_eglContext &&
        eglGetCurrentSurface(EGL_DRAW) == s_eglSurface) return true;
    if (!eglMakeCurrent(s_eglDisplay, s_eglSurface, s_eglSurface, s_eglContext)) {
        LOGE("eglMakeCurrent (bind) failed: 0x%x", eglGetError());
        return false;
    }
    return true;
}

static void destroyEglContext() {
    if (!s_eglInitialized) return;
    eglMakeCurrent(s_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (s_fbo) {
        glDeleteFramebuffers(1, &s_fbo); s_fbo = 0;
        glDeleteTextures(1, &s_fboTex); s_fboTex = 0;
        glDeleteRenderbuffers(1, &s_fboDepthStencil); s_fboDepthStencil = 0;
        s_fboW = s_fboH = 0;
    }
    if (s_eglWindowSurface != EGL_NO_SURFACE) {
        eglDestroySurface(s_eglDisplay, s_eglWindowSurface);
        s_eglWindowSurface = EGL_NO_SURFACE;
    }
    if (s_eglPbufferSurface != EGL_NO_SURFACE) {
        eglDestroySurface(s_eglDisplay, s_eglPbufferSurface);
        s_eglPbufferSurface = EGL_NO_SURFACE;
    }
    s_eglSurface = EGL_NO_SURFACE;
    if (s_eglContext != EGL_NO_CONTEXT) {
        eglDestroyContext(s_eglDisplay, s_eglContext);
        s_eglContext = EGL_NO_CONTEXT;
    }
    eglTerminate(s_eglDisplay);
    s_eglDisplay = EGL_NO_DISPLAY;
    s_eglInitialized = false;
    LOGI("EGL context destroyed");
}

// ---------------------------------------------------------------------------
// FBO the flycast core renders into. Size = the core's reported geometry max
// (internal resolution). Depth24+Stencil8 — PVR needs both.
// ---------------------------------------------------------------------------
static void destroyFbo() {
    if (s_fbo)        { glDeleteFramebuffers(1, &s_fbo); s_fbo = 0; }
    if (s_fboTex)     { glDeleteTextures(1, &s_fboTex); s_fboTex = 0; }
    if (s_fboDepthStencil) { glDeleteRenderbuffers(1, &s_fboDepthStencil); s_fboDepthStencil = 0; }
    s_fboW = s_fboH = 0;
}

static void ensureFbo(unsigned w, unsigned h) {
    if (w == 0 || h == 0) return;
    if (s_fbo && s_fboW == w && s_fboH == h) return;

    destroyFbo();

    glGenTextures(1, &s_fboTex);
    glBindTexture(GL_TEXTURE_2D, s_fboTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenRenderbuffers(1, &s_fboDepthStencil);
    glBindRenderbuffer(GL_RENDERBUFFER, s_fboDepthStencil);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h);

    glGenFramebuffers(1, &s_fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, s_fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s_fboTex, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT,
                              GL_RENDERBUFFER, s_fboDepthStencil);
    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("FBO incomplete: 0x%x (%ux%u)", status, w, h);
        destroyFbo();
    } else {
        s_fboW = w;
        s_fboH = h;
        LOGI("FBO ready: %ux%u (depth24+stencil8)", w, h);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

// libretro HW render callbacks ------------------------------------------------
static uintptr_t hw_get_current_framebuffer(void) {
    // The core renders here. If the FBO is not ready yet (should not happen:
    // context_reset precedes the first frame), return 0 so at least the
    // default framebuffer keeps the core alive.
    return s_fbo ? (uintptr_t)s_fbo : 0;
}

static retro_proc_address_t hw_get_proc_address(const char* sym) {
    return (retro_proc_address_t)eglGetProcAddress(sym);
}

// ---------------------------------------------------------------------------
// Blit the core's FBO to the window backbuffer and present it.
// Called on the emulation thread right after retro_run() in HW mode.
// ---------------------------------------------------------------------------
static void blitAndSwap() {
    if (s_eglWindowSurface == EGL_NO_SURFACE || !s_fbo) return;

    EGLint drawW = 0, drawH = 0;
    eglQuerySurface(s_eglDisplay, s_eglWindowSurface, EGL_WIDTH, &drawW);
    eglQuerySurface(s_eglDisplay, s_eglWindowSurface, EGL_HEIGHT, &drawH);
    if (drawW <= 0 || drawH <= 0) return;

    glBindFramebuffer(GL_READ_FRAMEBUFFER, s_fbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_SCISSOR_TEST);
    // 1:1 GL→GL blit preserves orientation (both are bottom-left GL spaces).
    glBlitFramebuffer(0, 0, s_fboW, s_fboH,
                      0, 0, drawW, drawH,
                      GL_COLOR_BUFFER_BIT, GL_LINEAR);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    eglSwapBuffers(s_eglDisplay, s_eglWindowSurface);
}

// ---------------------------------------------------------------------------
// Screenshot: glReadPixels from the core's FBO (bottom-up) → ARGB buffer.
// Runs on the emulation thread while the context/FBO are current.
// ---------------------------------------------------------------------------
static void captureFrameFromFbo() {
    if (!s_fbo || s_fboW == 0 || s_fboH == 0) return;
    std::vector<uint8_t> rgba((size_t)s_fboW * s_fboH * 4);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, s_fbo);
    glReadPixels(0, 0, s_fboW, s_fboH, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);

    std::lock_guard<std::mutex> lk(s_frameMtx);
    s_frame.resize((size_t)s_fboW * s_fboH);
    s_frameW = s_fboW;
    s_frameH = s_fboH;
    // glReadPixels rows are bottom-up → flip vertically; RGBA bytes → 0xAARRGGBB.
    for (unsigned y = 0; y < s_fboH; ++y) {
        const uint8_t* src = rgba.data() + (size_t)(s_fboH - 1 - y) * s_fboW * 4;
        uint32_t* dst = s_frame.data() + (size_t)y * s_fboW;
        for (unsigned x = 0; x < s_fboW; ++x) {
            dst[x] = 0xFF000000u |
                     ((uint32_t)src[x * 4 + 0] << 16) |   // R
                     ((uint32_t)src[x * 4 + 1] << 8)  |   // G
                     ((uint32_t)src[x * 4 + 2]);          // B
        }
    }
    s_newFrame.store(true, std::memory_order_release);
}

static bool cb_environment(unsigned cmd, void* data) {
    // NOTE: no per-call logging — GET_VARIABLE_UPDATE fires every frame.
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            if (data) *static_cast<bool*>(data) = true;
            return true;

        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            // Only used in the software-video fallback path.
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
            // Root of the core's content tree — flycast derives
            // <system>/dc/ from this (BIOS: <filesDir>/dc/dc_boot.bin ...).
            if (data) *static_cast<const char**>(data) = s_systemDir.c_str();
            return !s_systemDir.empty();

        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            // VMU saves (vmu_save_*.bin) / per-game nvmem / states live here.
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
            // Internal resolution changed (e.g. core-driven resize).
            s_fboNeedsResize.store(true, std::memory_order_release);
            return true;

        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
            if (data) {
                auto* av2 = static_cast<retro_system_av_info*>(data);
                if (av2->timing.sample_rate > 8000) {
                    s_sampleRate = (int)av2->timing.sample_rate;
                    s_resampler.init(s_sampleRate, s_sampleRate);
                }
                if (av2->timing.fps > 10.0) {
                    s_refreshRate = av2->timing.fps;
                }
                s_fboNeedsResize.store(true, std::memory_order_release);
                LOGI("SET_SYSTEM_AV_INFO: %d Hz, %.4f fps", s_sampleRate, s_refreshRate);
            }
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
            // Copy into a stable buffer while holding the lock — returning
            // it->second.c_str() directly races with setCoreOption() on the
            // UI thread (see nds_loader.cpp for the full rationale).
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

        // ── Hardware rendering ────────────────────────────────────────────
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
            // Steer flycast to its OpenGL ES 3 backend (not Vulkan): we
            // implement the GL frontend path (EGL + FBO blit). Returning
            // OPENGLES3 here makes the core request exactly that in
            // SET_HW_RENDER below.
            if (data) *static_cast<unsigned*>(data) = RETRO_HW_CONTEXT_OPENGLES3;
            return true;

        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            if (!data) return false;
            auto* hw = static_cast<retro_hw_render_callback*>(data);

            // Only the GL paths are supported by this frontend. If the core
            // insists on Vulkan we decline — with GET_PREFERRED_HW_RENDER
            // steering it should never do that.
            switch (hw->context_type) {
                case RETRO_HW_CONTEXT_OPENGLES3:
                case RETRO_HW_CONTEXT_OPENGLES2:
                case RETRO_HW_CONTEXT_OPENGLES_VERSION:
                case RETRO_HW_CONTEXT_OPENGL:
                    break;
                default:
                    LOGW("Declining HW render type %d (GL-only frontend)",
                         hw->context_type);
                    return false;
            }

            s_hwContextReset   = hw->context_reset;
            s_hwContextDestroy = hw->context_destroy;

            // Provide the frontend's get_current_framebuffer / get_proc_address.
            hw->get_current_framebuffer = hw_get_current_framebuffer;
            hw->get_proc_address = hw_get_proc_address;

            LOGI("HW render requested: type=%d, depth=%d, stencil=%d, "
                 "version=%d.%d, bottom_left=%d",
                 hw->context_type, hw->depth, hw->stencil,
                 hw->version_major, hw->version_minor,
                 hw->bottom_left_origin);

            if (!ensureEglDisplayAndContext()) {
                LOGE("Failed to create EGL context for HW render");
                return false;
            }
            // Bind the context here so the core's context_reset (shader /
            // resource creation) runs with a current GL context.
            if (!ensureEglContextCurrent()) {
                LOGE("eglMakeCurrent failed during SET_HW_RENDER");
                return false;
            }

            if (s_hwContextReset) {
                LOGI("Calling HW context_reset");
                s_hwContextReset();
            }
            s_hwRenderActive.store(true, std::memory_order_release);
            LOGI("HW render setup complete");
            return true;
        }

        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE:
            // Vulkan interface — not implemented (GL-only frontend).
            return false;

        case RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER:
            // We let the core render into its FBO; no software fallback buffer.
            return false;

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

        default:
            return false;
    }
}

static void cb_video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;  // dupe frame

    // FPS HUD：真实提交帧计数（仅核心渲染出新帧时才计）
    s_presentedFrames.fetch_add(1, std::memory_order_relaxed);
    s_videoW = width;
    s_videoH = height;

    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        // HW path: the frame lives in our FBO; presentation happens in
        // blitAndSwap() right after retro_run(). Copy-on-demand screenshots
        // go through captureFrameFromFbo().
        s_newFrame.store(true, std::memory_order_release);
        return;
    }

    // ── Software fallback (defensive; flycast always HW-renders) ─────────
    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        const size_t need = (size_t)width * height;
        if (s_frameW != width || s_frameH != height || s_frame.size() < need) {
            s_frame.resize(need);
            s_frameW = width;
            s_frameH = height;
        }

        if (s_pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
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
    }

    // Fast-forward frame skip + CPU blit (software path only).
    if (s_fastForward.load(std::memory_order_relaxed)) {
        int skip = s_ffMaxSkip.load(std::memory_order_relaxed);
        if (skip > 0 && s_ffFrameSkip.fetch_add(1, std::memory_order_relaxed) % skip != 0)
            return;
    } else {
        s_ffFrameSkip.store(0, std::memory_order_relaxed);
    }

    const int filter = s_videoFilter.load(std::memory_order_relaxed);
    coreshared::applyFilterAndBlit(
        s_softwareWindow, s_softwareWindowMtx,
        s_frame.data(), width, height, width,
        filter,
        s_xbrBuffer2x, s_xbrBuffer4x, s_xbrMidBuffer,
        (unsigned)kMaxW, (unsigned)kMaxH,
        s_highQualityScaling.load(std::memory_order_relaxed));
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
                              unsigned index, unsigned id) {
    // Analog axes: the on-screen UI feeds digital bits only; sticks rest at
    // center (0). Analog-only games still see a present-but-neutral stick.
    if (device == RETRO_DEVICE_ANALOG) {
        if (index == 0 && id <= 3) return 0;  // LX / LY / RX / RY neutral
        device = RETRO_DEVICE_JOYPAD;         // fall through for pad ids
    }
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    // DC supports up to 4 controllers (ports 0-3).
    const uint16_t bits = (port == 0) ? s_pad1.load(std::memory_order_relaxed)
                         : (port == 1) ? s_pad2.load(std::memory_order_relaxed)
                         : (port == 2) ? s_pad3.load(std::memory_order_relaxed)
                         : (port == 3) ? s_pad4.load(std::memory_order_relaxed)
                                       : 0;
    if (id >= 16) return 0;
    return (bits >> id) & 1;
}

// ---------------------------------------------------------------------------
// Apply any controller-port device switch queued by setPortDevice() before
// running the next frame. Executed on the emulation thread only.
// ---------------------------------------------------------------------------
static void applyPendingPortDevicesLockedStep() {
    uint32_t pending = s_pendingPortDevices.load(std::memory_order_acq_rel);
    if ((pending >> 24) == 0xFF || !s_retro_set_controller_port_device) return;
    for (int port = 0; port < 4; ++port) {
        unsigned dev = (pending >> (port * 8)) & 0xFFu;
        if (dev == 0xFF) dev = RETRO_DEVICE_JOYPAD;
        s_retro_set_controller_port_device((unsigned)port, dev);
        LOGI("Controller port %d -> device %u", port, dev);
    }
    s_pendingPortDevices.store(0xFFu << 24, std::memory_order_release);
}

// ---------------------------------------------------------------------------
// File-extension helpers for loadFromFile.
// ---------------------------------------------------------------------------
static std::string getExtensionLower(const std::string& path) {
    size_t dot = path.find_last_of('.');
    if (dot == std::string::npos) return "";
    std::string ext = path.substr(dot + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(),
                   [](unsigned char c){ return (char)std::tolower(c); });
    return ext;
}

// All DC content is passed BY PATH:
//   · disc images (.gdi/.cdi/.cue/.chd/...) reference sibling track files —
//     the core opens them itself and needs the real filesystem path;
//   · NAOMI / AtomisWave .zip sets are identified by their archive name and
//     are streamed by the core (preloading into memory would also break
//     per-game naming for nvmem / cheats / texture packs).
static bool isDiscOrArchive(const std::string& ext) {
    return ext == "gdi" || ext == "cdi" || ext == "cue" || ext == "chd" ||
           ext == "iso" || ext == "lst" || ext == "bin" || ext == "zip" ||
           ext == "7z"  || ext == "m3u";
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------
std::string loadFromFile(const std::string& path, int& regionOut) {
    regionOut = 0;

    if (!loadCoreLib()) {
        return s_coreError.empty()
            ? "Failed to load libflycast_libretro_android.so"
            : s_coreError;
    }

    if (!s_loaded) {
        initDefaultOptions();

        s_retro_set_environment(cb_environment);
        s_retro_set_video_refresh(cb_video);
        s_retro_set_audio_sample(cb_audio_sample);
        s_retro_set_audio_sample_batch(cb_audio_batch);
        s_retro_set_input_poll(cb_input_poll);
        s_retro_set_input_state(cb_input_state);

        s_retro_init();
        s_loaded = true;
        LOGI("Flycast core initialized (API version %u)", s_retro_api_version());
    }

    if (s_gameLoaded) {
        s_retro_unload_game();
        s_gameLoaded = false;
    }

    s_audio.reset();
    s_resampler.reset();

    s_romPath = path;

    retro_game_info gameInfo{};
    gameInfo.path = s_romPath.c_str();
    gameInfo.data = nullptr;   // flycast streams everything from the path
    gameInfo.size = 0;
    gameInfo.meta = nullptr;

    bool ok = s_retro_load_game(&gameInfo);

    if (!ok) {
        s_coreError = "retro_load_game() failed for: " + path;
        if (!s_coreMessage.empty()) {
            s_coreError += " (";
            s_coreError += s_coreMessage;
            s_coreError += ")";
            s_coreMessage.clear();
        }
        // Detailed Chinese explanation of common DC load failures — the
        // missing-BIOS case is by far the most common one.
        s_coreError += "\n\n常见原因:\n";
        s_coreError += "  1. BIOS 缺失: Dreamcast 光盘游戏需要 dc_boot.bin + "
                        "dc_flash.bin 放在系统目录 dc/ 下 (设置 → DC → BIOS 管理).\n";
        s_coreError += "  2. NAOMI / AtomisWave 游戏 (.zip) 缺少街机 BIOS: 需要 "
                        "naomi.zip / awbios.zip 等对应 BIOS 包 (已内置则会自动加载).\n";
        s_coreError += "  3. .gdi / .cue 多文件镜像不完整: 请确保轨道文件与主文件"
                        "在同一目录且文件名一致.\n";
        s_coreError += "  4. .chd / .cdi 压缩镜像损坏或格式不支持, 请用 chdman 校验或"
                        "改用 .gdi/.cue+bin.\n";
        LOGE("%s", s_coreError.c_str());
        return s_coreError;
    }

    s_gameLoaded = true;
    s_lastRomPath = path;

    // 4 controller ports, standard JOYPAD device (Dreamcast pad).
    if (s_retro_set_controller_port_device) {
        s_retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
        s_retro_set_controller_port_device(1, RETRO_DEVICE_JOYPAD);
        s_retro_set_controller_port_device(2, RETRO_DEVICE_JOYPAD);
        s_retro_set_controller_port_device(3, RETRO_DEVICE_JOYPAD);
    }

    retro_system_av_info av{};
    s_retro_get_system_av_info(&av);
    s_sampleRate = (int)av.timing.sample_rate;
    s_refreshRate = (av.timing.fps > 10.0) ? av.timing.fps : 60.0;
    s_region = (av.timing.fps < 55.0) ? 1 : 0;
    regionOut = s_region;
    s_videoW = av.geometry.base_width ? av.geometry.base_width : 640;
    s_videoH = av.geometry.base_height ? av.geometry.base_height : 480;

    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        s_frameW = s_videoW;
        s_frameH = s_videoH;
        s_frame.assign((size_t)s_frameW * s_frameH, 0xFF000000u);
    }

    s_audio.reset();
    s_newFrame.store(false);

    if (s_sampleRate > 0) {
        // Audio passthrough — AudioTrack opens at the core's own rate (44.1k).
        s_resampler.init(s_sampleRate, s_sampleRate);
        LOGI("Audio passthrough: %d Hz", s_sampleRate);
    }

    // Size the presentation FBO from the core's max geometry (internal
    // resolution); recreated on SET_SYSTEM_AV_INFO / SET_GEOMETRY changes.
    {
        std::lock_guard<std::mutex> lk(s_eglMtx);
        if (s_hwRenderActive.load(std::memory_order_acquire) &&
            s_eglInitialized && ensureEglContextCurrent()) {
            ensureFbo(av.geometry.max_width ? av.geometry.max_width : s_videoW,
                      av.geometry.max_height ? av.geometry.max_height : s_videoH);
            // Unbind so the emulation thread can take the context over.
            eglMakeCurrent(s_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
    }

    LOGI("DC ROM loaded: %s  rate=%d  fps=%.2f  region=%d  geom=%ux%u  max=%ux%u",
         path.c_str(), s_sampleRate, av.timing.fps, s_region,
         av.geometry.base_width, av.geometry.base_height,
         av.geometry.max_width, av.geometry.max_height);
    return "";
}

void unload() {
    if (s_loaded) {
        if (s_gameLoaded) {
            s_retro_unload_game();
            s_gameLoaded = false;
        }
        // GL teardown (FBO + context destroy callbacks) — the core's GL
        // resources must be destroyed while a context is current, and the
        // emu thread must be gone (Kotlin joins it before unload).
        {
            std::lock_guard<std::mutex> lk(s_eglMtx);
            if (s_hwContextDestroy && s_eglInitialized && ensureEglContextCurrent()) {
                s_hwContextDestroy();
            }
            destroyEglContext();
        }
        s_hwRenderActive.store(false, std::memory_order_release);
        s_retro_deinit();
        s_loaded = false;
    }
    s_sampleRate = 0;
    s_refreshRate = 60.0;
    s_region = 0;
    s_audio.reset();
    s_resampler.reset();
    s_newFrame.store(false);
    s_presentedFrames.store(0, std::memory_order_relaxed);
    s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        s_frame.clear();
        s_frameW = 0;
        s_frameH = 0;
    }
    s_videoW = 640;
    s_videoH = 480;
    s_lastRomPath.clear();
    s_saveName.clear();
    s_pad1.store(0, std::memory_order_relaxed);
    s_pad2.store(0, std::memory_order_relaxed);
    s_pad3.store(0, std::memory_order_relaxed);
    s_pad4.store(0, std::memory_order_relaxed);
    s_hwContextReset = nullptr;
    s_hwContextDestroy = nullptr;


    // ★ 与 psx_loader 同款"关闭后再打开闪退"预防性修复：dlclose 核心
    // 库，下次 loadFromFile 重新 dlopen —— 核心静态状态完全归零。
    if (s_coreLib) {
        LOGI("dlclose(libflycast_libretro_android.so) — full core state reset");
        dlclose(s_coreLib);
        s_coreLib = nullptr;
    }
}

void resetEmulation(bool /*hard*/) {
    if (s_loaded && s_gameLoaded) s_retro_reset();
}

void stepFrame() {
    if (!s_loaded || !s_gameLoaded) return;
    applyPendingPortDevicesLockedStep();

    if (s_hwRenderActive.load(std::memory_order_acquire)) {
        // HW path: bind the shared context on this thread, run one frame,
        // capture on demand, then present (blit + swap).
        // s_eglMtx serializes against setSurface() (window surface
        // create/destroy on the UI thread) — hold cost is negligible
        // (surface changes are rare; the emu thread is the usual owner).
        std::lock_guard<std::mutex> lk(s_eglMtx);

        // Handle a geometry change (internal resolution switch) before the run.
        if (s_fboNeedsResize.exchange(false, std::memory_order_acq_rel) &&
            s_eglInitialized && ensureEglContextCurrent()) {
            retro_system_av_info av{};
            s_retro_get_system_av_info(&av);
            ensureFbo(av.geometry.max_width ? av.geometry.max_width : s_videoW,
                      av.geometry.max_height ? av.geometry.max_height : s_videoH);
        }

        if (!ensureEglContextCurrent()) {
            LOGW("stepFrame: EGL context unavailable — frame skipped");
            return;
        }
        s_retro_run();
        if (s_captureRequested.exchange(false, std::memory_order_acq_rel)) {
            captureFrameFromFbo();
            s_captureDone.store(true, std::memory_order_release);
        }
        blitAndSwap();
    } else {
        // Software fallback (defensive).
        s_retro_run();
    }
}

// 读取并清零自上次轮询以来核心真实提交的帧数（FPS HUD 用）。
int pollPresentedFrames() {
    return s_presentedFrames.exchange(0, std::memory_order_acq_rel);
}

void setPortDevice(int port, int device) {
    if (port < 0 || port > 3) return;
    uint32_t next = s_pendingPortDevices.load(std::memory_order_relaxed);
    next = (next & ~(0xFFu << (port * 8))) |
           ((uint32_t)(device & 0xFF) << (port * 8));
    next &= 0x00FFFFFFu;                    // clear "no pending" flag byte
    s_pendingPortDevices.store(next, std::memory_order_acq_rel);
}

double videoRefreshRate() { return s_refreshRate; }

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
    // In HW mode s_frame only refreshes on explicit captures; report
    // "has new data" from the atomic anyway — the UI uses it as a hint.
    return s_newFrame.exchange(false, std::memory_order_acq_rel);
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

void setPaths(const std::string& systemDir, const std::string& saveDir) {
    s_systemDir = systemDir;
    s_saveDir = saveDir;
}

void setSaveName(const std::string& name) {
    s_saveName = name;
    LOGI("Save basename hint: '%s'", name.c_str());
}

void setCoreLibPath(const std::string& path) {
    s_coreLibPath = path;
    LOGI("Core lib path set: %s", s_coreLibPath.c_str());
}

void applyRegion(int /*region*/) { /* region auto-detected at load */ }
void applySampleRate(int /*hz*/) { /* fixed by the core */ }
void applySpeed(float multiplier) {
    s_fastForward.store(multiplier > 1.0f, std::memory_order_relaxed);
    s_ffMaxSkip.store((int)multiplier, std::memory_order_relaxed);
    s_ffFrameSkip.store(0, std::memory_order_relaxed);
}

void saveStateToPath(int /*slot*/, const std::string& path) {
    if (!s_loaded || !s_gameLoaded || !s_retro_serialize) return;
    size_t sz = s_retro_serialize_size();
    if (sz == 0) return;
    std::vector<uint8_t> buf(sz);
    if (!s_retro_serialize(buf.data(), sz)) { LOGE("retro_serialize failed"); return; }
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) { LOGE("Cannot open save state for write: %s", path.c_str()); return; }
    std::fwrite(buf.data(), 1, sz, f);
    std::fclose(f);
}

bool loadStateFromPath(int /*slot*/, const std::string& path) {
    if (!s_loaded || !s_gameLoaded || !s_retro_unserialize) return false;
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
    if (!s_retro_unserialize(buf.data(), sz)) { LOGE("retro_unserialize failed"); return false; }
    return true;
}

void setSurface(void* nativeWindow) {
    std::lock_guard<std::mutex> lk(s_eglMtx);

    ANativeWindow* win = static_cast<ANativeWindow*>(nativeWindow);

    // Keep a ref-counted window for the defensive software-fallback blit.
    coreshared::setSurface(s_softwareWindow, s_softwareWindowMtx, nativeWindow);

    // Tear down the old window surface.
    if (s_eglWindowSurface != EGL_NO_SURFACE) {
        if (s_eglInitialized && eglGetCurrentContext() != EGL_NO_CONTEXT) {
            eglMakeCurrent(s_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        eglDestroySurface(s_eglDisplay, s_eglWindowSurface);
        s_eglWindowSurface = EGL_NO_SURFACE;
        s_eglSurface = s_eglPbufferSurface;
        s_winW = 0;
        s_winH = 0;
    }

    if (win == nullptr) {
        LOGI("Surface detached");
        return;
    }

    if (!ensureEglDisplayAndContext()) {
        LOGE("setSurface: EGL init failed");
        return;
    }

    EGLSurface surf = eglCreateWindowSurface(s_eglDisplay, s_eglConfig,
                                             (EGLNativeWindowType)win, nullptr);
    if (surf == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        return;
    }
    s_eglWindowSurface = surf;
    s_eglSurface = surf;
    s_winW = ANativeWindow_getWidth(win);
    s_winH = ANativeWindow_getHeight(win);
    LOGI("Surface attached: %dx%d (window surface created)", s_winW, s_winH);
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
    // Dreamcast NTSC is 4:3 (VGA / composite). With the widescreen hack the
    // geometry grows, but the display surface is aspect-managed by the UI —
    // 4:3 remains the canonical presentation ratio.
    num = 4;
    den = 3;
}

void setVideoFilter(int filter) {
    s_videoFilter.store(filter, std::memory_order_relaxed);
    LOGI("Video filter set: %d", filter);
}

void setHighQualityScaling(bool enabled) {
    s_highQualityScaling.store(enabled, std::memory_order_relaxed);
    LOGI("High-quality scaling: %s", enabled ? "ON" : "OFF");
}

// Screenshot request — served inside stepFrame() from the core's FBO.
// requestFrameCapture() schedules the glReadPixels on the emulation thread;
// waitForCapture() blocks briefly for the result to land in s_frame.
void requestFrameCapture() {
    s_captureDone.store(false, std::memory_order_release);
    s_captureRequested.store(true, std::memory_order_release);
}

bool waitForCapture(int timeoutMs) {
    if (s_captureDone.exchange(false, std::memory_order_acq_rel)) return true;
    const int steps = timeoutMs / 10;
    for (int i = 0; i < steps; ++i) {
        usleep(10000);
        if (s_captureDone.exchange(false, std::memory_order_acq_rel)) return true;
    }
    return false;
}

bool isCoreLoaded() {
    return s_coreLib != nullptr;
}

} // namespace dccore::rom
