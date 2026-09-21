// SPDX-License-Identifier: MIT
// libretro frontend that drives the prebuilt PCSX-ReARMed (PlayStation 1) core.
//
// This loader follows the same dlopen() pattern as fbneo_loader.cpp:
// instead of statically linking the PCSX-ReARMed source tree (which is huge
// and has platform-specific dynarecs), we dlopen() the prebuilt
// libpcsx_rearmed_libretro_android.so at runtime and resolve the retro_*
// symbols via dlsym(). The .so file ships in app/src/main/jniLibs/<abi>/.
//
// The libretro API surface we resolve is identical to fbneo_loader.cpp:
//   retro_init, retro_deinit, retro_load_game, retro_unload_game, retro_run,
//   retro_reset, retro_get_system_info, retro_get_system_av_info,
//   retro_set_environment, retro_set_video_refresh, retro_set_audio_sample,
//   retro_set_audio_sample_batch, retro_set_input_poll, retro_set_input_state,
//   retro_set_controller_port_device, retro_serialize_size, retro_serialize,
//   retro_unserialize, retro_get_memory_size, retro_get_memory_data
//
// Video resolution is dynamic (PS1 uses 256x240, 320x240, 368x240, 512x240,
// 640x480 interlaced hi-res). The frame buffer uses a std::vector that
// resizes to the largest seen resolution. Filter buffers are sized to
// 640x480 max — this covers every PS1 video mode including 480i hi-res.
//
// Audio: PS1 SPU outputs at 44100 Hz (NTSC) or 44100 Hz (PAL, same SPU
// clock); the resampler converts to Android's 48000 Hz.
//
// Input: 12-button PS1 gamepad (RETRO_DEVICE_JOYPAD) on ports 0-3.
// PCSX-ReARMed maps the standard JOYPAD buttons to DualShock labels:
//   A=Cross, B=Circle, X=Triangle, Y=Square, L=L1, R=R1,
//   Select=Select, Start=Start. L2/R2/L3/R3 require RETRO_DEVICE_ANALOG
//   (not exposed via this 12-bit interface).
//
// All retro_* calls happen on a single emulation thread (see PsxEngine in
// Kotlin), so no extra internal locking is needed around the core itself.
// The frame and audio buffers are mutex-guarded because the UI / AudioTrack
// threads read them concurrently.

#include "psx_loader.h"
#include "shared/core_shared.h"

#include <libretro.h>
#include <android/log.h>
#include <android/native_window.h>

#include <dlfcn.h>
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

#define TAG "psxcore-rom"
#undef LOGI
#undef LOGW
#undef LOGE
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace psxcore::rom {

// ---------------------------------------------------------------------------
// Maximum video resolution supported by PCSX-ReARMed.
// PS1 video modes:
//   256x240 (low-res, many PS1 games)
//   320x240 (standard, most PS1 games)
//   368x240 (slightly wider, e.g. Chrono Cross menus)
//   512x240 (hi-res, e.g. Square RPGs in 2D mode)
//   640x480 (interlaced hi-res, e.g. Valkyrie Profile, MGS menus)
// 640x480 covers every known PS1 video mode with no margin to spare.
// ---------------------------------------------------------------------------
static constexpr int kMaxW = 640;
static constexpr int kMaxH = 480;

static constexpr int TARGET_SAMPLE_RATE = coreshared::TARGET_SAMPLE_RATE;

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

// Persistent copy of the currently-loaded ROM path.
// PCSX-ReARMed may read retro_game_info.path asynchronously (e.g. for
// save-state naming), so the pointer must outlive the JNI
// GetStringUTFChars / ReleaseStringUTFChars cycle. We store the path here
// and pass s_lastRomPath.c_str() as gameInfo.path instead of the transient
// cpath pointer from JNI.
static std::string s_romPath;

// Dynamic frame buffer (ARGB, 0xAARRGGBB).
static std::mutex s_frameMtx;
static std::vector<uint32_t> s_frame;
static unsigned s_frameW = 0;
static unsigned s_frameH = 0;
static std::atomic<bool> s_newFrame{false};

static unsigned s_videoW = 320;
static unsigned s_videoH = 240;
static unsigned s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;

// FPS HUD：核心真实提交帧计数。cb_video 收到非空 data（核心真的渲染了
// 新帧）时 +1；UI 每秒 pollPresentedFrames() 读走并清零。区别于前端
// 模拟循环的步进计数：游戏内部掉帧（如 30fps 游戏）时 PCSX-ReARMed
// 会对重复帧传 null data，这里不计入 —— 得到的是“游戏真实输出帧率”，
// 而不是被帧率限制器凑出来的 60。
static std::atomic<int> s_presentedFrames{0};

// Gamepad bits (port 0..3, RETRO_DEVICE_JOYPAD).
static std::atomic<uint16_t> s_pad1{0};
static std::atomic<uint16_t> s_pad2{0};
static std::atomic<uint16_t> s_pad3{0};
static std::atomic<uint16_t> s_pad4{0};

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

// Controller-port device switching requested from the UI thread.
// retro_set_controller_port_device() touches core internals, so instead of
// calling it directly off the emulation thread we park the request in an
// atomic and apply it at the top of the next stepFrame() — same thread
// that calls retro_run(). Layout: bits [3..0] = device id for ports 0..3,
// 0xFF = no pending request.
static std::atomic<uint32_t> s_pendingPortDevices{0xFFu << 24};

// ---------------------------------------------------------------------------
// Initialize PCSX-ReARMed core options with sensible defaults.
// Keys MUST match PCSX-ReARMed's libretro_core_options.h exactly
// (verified against pcsx_rearmed-master/frontend/libretro_core_options.h).
// Wrong keys cause the core to ignore settings and use its own defaults.
//
// NOTE: Several option keys that were suggested in the original spec do NOT
// exist in the upstream PCSX-ReARMed options header. Substitutions:
//   * "pcsx_rearmed_pad1type" / "pcsx_rearmed_pad2type" — DO NOT EXIST.
//     Controller types are set via RETRO_ENVIRONMENT_SET_CONTROLLER_PORT_DEVICE
//     (handled in loadFromFile below), not via core options.
//   * "pcsx_rearmed_gte_overclock"  — DO NOT EXIST. Replaced with
//     "pcsx_rearmed_psxclock" (PSX CPU overclock %, default "auto").
//   * "pcsx_rearmed_gpu_fast_paths" — DO NOT EXIST. Replaced with
//     "pcsx_rearmed_gpu_slow_llists" (default "disabled").
//   * "pcsx_rearmed_gpu_peops_odd_hack" — DO NOT EXIST. Replaced with the
//     actual key "pcsx_rearmed_gpu_peops_odd_even_bit" (default "disabled").
//   * "pcsx_rearmed_inuyasha_dualshock_fix" — DO NOT EXIST. PCSX-ReARMed
//     handles per-game compat via "pcsx_rearmed_icache_emulation"
//     (default "enabled") and "pcsx_rearmed_nocompathacks" (default
//     "disabled" — auto-compat-hacks ON by default).
//   * "pcsx_rearmed_frameskip" = "0" — wrong key name. The actual key is
//     "pcsx_rearmed_frameskip_type" (values: disabled | auto |
//     auto_threshold | fixed_interval; default "disabled").
//   * "pcsx_rearmed_memcard" — wrong key name. Actual keys are
//     "pcsx_rearmed_memcard1" (default "libretro") and
//     "pcsx_rearmed_memcard2" (default "shared").
//   * "pcsx_rearmed_input_sensitivity" = "100" — actual values are floats
//     0.05..2.00; default is "1.00" (the "100" was likely a percentage
//     interpretation; we use the canonical default "1.00").
// ---------------------------------------------------------------------------
static void initDefaultOptions() {
    // Only fill defaults for keys that have not already been set by the UI
    // (applyCoreOptions -> setCoreOption runs BEFORE loadRom on first load).
    // Using direct assignment here would overwrite the user's dynarec /
    // threading choices with the defaults below, making them silently ignored.
    auto setIfMissing = [](const std::string& key, const std::string& val) {
        if (s_options.find(key) == s_options.end()) {
            s_options[key] = val;
        }
    };

    // --- System / BIOS ---
    setIfMissing("pcsx_rearmed_region",             "auto");      // auto | NTSC | PAL
    setIfMissing("pcsx_rearmed_bios",                "auto");      // auto | HLE
    setIfMissing("pcsx_rearmed_show_bios_bootlogo",  "disabled");  // disabled | enabled | enabled_no_pcsx
    setIfMissing("pcsx_rearmed_memcard1",            "libretro");  // libretro | serial | shared | none
    setIfMissing("pcsx_rearmed_memcard2",            "shared");   // serial | shared | none
    setIfMissing("pcsx_rearmed_cd_readahead",        "12");       // 0..16,32,64,128,256,512,1024,333000

    // --- CPU / Dynarec ---
    setIfMissing("pcsx_rearmed_drc",                 "enabled");   // disabled | enabled (dynarec)
    setIfMissing("pcsx_rearmed_psxclock",            "auto");      // auto | 30..100 (CPU overclock %)
    setIfMissing("pcsx_rearmed_icache_emulation",     "enabled");   // required for F1 2001 / F1 Arcade / F1 99
    setIfMissing("pcsx_rearmed_exception_emulation", "disabled");  // debug-only feature
    setIfMissing("pcsx_rearmed_nocompathacks",        "disabled");  // auto per-game compat hacks ON
    setIfMissing("pcsx_rearmed_nostalls",             "disabled");  // disable CPU/GTE stalls (unsafe)
    setIfMissing("pcsx_rearmed_cd_turbo",             "disabled");  // unsafe speed hack

    // --- Video / GPU ---
    setIfMissing("pcsx_rearmed_dithering",            "enabled");   // disabled | enabled | force
    setIfMissing("pcsx_rearmed_frameskip_type",       "disabled");  // disabled|auto|auto_threshold|fixed_interval
    setIfMissing("pcsx_rearmed_frameskip_threshold",  "33");        // % (used when frameskip_type=auto_threshold)
    setIfMissing("pcsx_rearmed_frameskip_interval",   "3");         // max frames skipped (fixed_interval mode)
    setIfMissing("pcsx_rearmed_display_fps_v2",       "disabled");  // show internal FPS overlay
    setIfMissing("pcsx_rearmed_display_info",         "enabled");   // show informational OSD messages
    setIfMissing("pcsx_rearmed_fractional_framerate",  "auto");      // auto | disabled | enabled
    setIfMissing("pcsx_rearmed_alt_flip",             "auto");      // auto | early | late
    setIfMissing("pcsx_rearmed_rgb32_output",         "disabled");  // 32-bit color (higher CPU use)
    setIfMissing("pcsx_rearmed_scale_hires",          "disabled");  // downscale 480i/512i -> 320x240
    setIfMissing("pcsx_rearmed_gpu_slow_llists",     "disabled");  // slow linked-list GPU processing
    setIfMissing("pcsx_rearmed_show_overscan",        "disabled");  // show overscan area
    setIfMissing("pcsx_rearmed_neon_interlace_enable_v2", "disabled");  // neon interlace (gpu_neon)
    setIfMissing("pcsx_rearmed_neon_enhancement_enable",  "disabled");  // neon upscaling (gpu_neon)

    // --- GPU PEOPS advanced (only used if GPU plugin is gpu_peops) ---
    setIfMissing("pcsx_rearmed_gpu_peops_odd_even_bit",        "disabled");
    setIfMissing("pcsx_rearmed_gpu_peops_expand_screen_width", "disabled");
    setIfMissing("pcsx_rearmed_gpu_peops_ignore_brightness",   "disabled");
    setIfMissing("pcsx_rearmed_gpu_peops_disable_coord_check", "disabled");
    setIfMissing("pcsx_rearmed_gpu_peops_lazy_screen_update",  "disabled");
    setIfMissing("pcsx_rearmed_gpu_peops_repeated_triangles",  "disabled");
    setIfMissing("pcsx_rearmed_gpu_peops_quads_with_triangles", "disabled");
    setIfMissing("pcsx_rearmed_gpu_peops_fake_busy_state",     "disabled");

    // --- GPU UNAI advanced (only used if GPU plugin is gpu_unai) ---
    setIfMissing("pcsx_rearmed_gpu_unai_old_renderer",  "disabled");
    setIfMissing("pcsx_rearmed_gpu_unai_blending",      "enabled");
    setIfMissing("pcsx_rearmed_gpu_unai_skipline",      "disabled");
    setIfMissing("pcsx_rearmed_gpu_unai_lighting",      "enabled");
    setIfMissing("pcsx_rearmed_gpu_unai_fast_lighting", "disabled");

    // --- Audio / SPU ---
    setIfMissing("pcsx_rearmed_spu_reverb",            "enabled");   // disabled | enabled
    setIfMissing("pcsx_rearmed_spu_interpolation",     "simple");    // simple | gaussian | cubic | off
    setIfMissing("pcsx_rearmed_nocdaudio",              "enabled");   // enabled = play CD-DA (note inverted logic)
    setIfMissing("pcsx_rearmed_noxadecoding",           "enabled");   // enabled = play XA audio (inverted logic)
    setIfMissing("pcsx_rearmed_spu_thread",             "disabled");  // threaded SPU (USE_ASYNC_SPU only)

    // --- Performance / threading ---
    // pcsx_rearmed_gpu_thread_rendering: runs GPU commands on a worker thread.
    //   'auto' enables it when ≥2 CPU cores are detected — a large win on all
    //   modern Android devices. Same for drc_thread (dynarec runs in its own
    //   thread). Both default to upstream 'auto'.
    setIfMissing("pcsx_rearmed_drc_thread",              "auto");      // auto | disabled | enabled
    setIfMissing("pcsx_rearmed_gpu_thread_rendering",    "auto");      // auto | disabled | enabled

    // --- Display geometry / misc ---
    setIfMissing("pcsx_rearmed_screen_centering",        "auto");      // auto | game | borderless | manual

    // --- Input ---
    setIfMissing("pcsx_rearmed_show_input_settings",    "disabled");  // hide advanced input options
    setIfMissing("pcsx_rearmed_analog_axis_modifier",  "square");    // circle | square (analog stick bounds)
    setIfMissing("pcsx_rearmed_vibration",              "enabled");   // rumble on DualShock
    setIfMissing("pcsx_rearmed_analog_combo",           "l1+r1+select");  // DualShock mode toggle combo
    setIfMissing("pcsx_rearmed_multitap",               "disabled");  // disabled | port 1 | port 2 | ports 1 and 2
    setIfMissing("pcsx_rearmed_negcon_deadzone",        "0");         // 0% .. 30% (neGcon twist deadzone)
    setIfMissing("pcsx_rearmed_negcon_response",        "linear");    // linear | quadratic | cubic
    setIfMissing("pcsx_rearmed_input_sensitivity",      "1.00");      // mouse sensitivity (0.05 .. 2.00)

    // --- Light gun (rarely used on Android — no lightgun support in this UI) ---
    setIfMissing("pcsx_rearmed_crosshair1",            "disabled");  // disabled | blue | green | red | white
    setIfMissing("pcsx_rearmed_crosshair2",            "disabled");
}

// ---------------------------------------------------------------------------
// dlopen the core .so and resolve all retro_* symbols.
// ---------------------------------------------------------------------------
static bool loadCoreLib() {
    if (s_coreLib) return true;

    std::vector<std::string> candidates;
    if (!s_coreLibPath.empty()) candidates.push_back(s_coreLibPath);
    candidates.push_back("libpcsx_rearmed_libretro_android.so");

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
        s_coreError = "dlopen(libpcsx_rearmed_libretro_android.so) failed: ";
        s_coreError += (lastDlError ? lastDlError : "(unknown)");
        LOGE("%s", s_coreError.c_str());
        return false;
    }

    LOGI("dlopen(libpcsx_rearmed_libretro_android.so) OK");

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

    // Optional — PCSX-ReARMed exposes these for memory card (MCD)
    // persistence via RETRO_MEMORY_SAVE_RAM.
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
    __android_log_vprint(prio, "pcsx", fmt, ap);
    va_end(ap);
}

static bool cb_environment(unsigned cmd, void* data) {
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
            if (data) {
                auto* av2 = static_cast<retro_system_av_info*>(data);
                if (av2->timing.sample_rate > 8000) {
                    s_sampleRate = (int)av2->timing.sample_rate;
                    // Default audio — passthrough, no TV-mode resampling.
                    s_resampler.init(s_sampleRate, s_sampleRate);
                }
                if (av2->timing.fps > 10.0) {
                    s_refreshRate = av2->timing.fps;
                }
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
            std::lock_guard<std::mutex> lk(s_optMtx);
            auto it = s_options.find(var->key);
            if (it != s_options.end()) {
                var->value = it->second.c_str();
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

        default:
            return false;
    }
}

static void cb_video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;

    // FPS HUD：真实提交帧计数（仅核心渲染出新帧时才计）
    s_presentedFrames.fetch_add(1, std::memory_order_relaxed);

    s_videoW = width;
    s_videoH = height;

    {
        std::lock_guard<std::mutex> lk(s_frameMtx);

        const size_t need = (size_t)width * height;
        if (s_frameW != width || s_frameH != height || s_frame.size() < need) {
            s_frame.resize(need);
            s_frameW = width;
            s_frameH = height;
        }

        if (s_pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
            // NEON bulk conversion (16 px/iter, see core_shared.h) — the old
            // scalar per-pixel loop cost several ms/frame on big frames.
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

    // Fast-forward frame skip — same pattern as fbneo_loader.
    if (s_fastForward.load(std::memory_order_relaxed)) {
        int skip = s_ffMaxSkip.load(std::memory_order_relaxed);
        if (skip > 0 && s_ffFrameSkip.fetch_add(1, std::memory_order_relaxed) % skip != 0)
            return;
    } else {
        s_ffFrameSkip.store(0, std::memory_order_relaxed);
    }

    const int filter = s_videoFilter.load(std::memory_order_relaxed);
    coreshared::applyFilterAndBlit(
        s_window, s_windowMtx,
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
    // RETRO_DEVICE_ANALOG (DualShock): report stick axes. The on-screen UI
    // feeds digital pad bits only, so both sticks rest at center (0).
    // Games probe the DualShock via this device type — reporting neutral
    // axes is enough for them to enable analog-only features.
    if (device == RETRO_DEVICE_ANALOG) {
        if (id <= 3) return 0;          // LX / LY / RX / RY neutral
        // Fall through for joypad-style ids (start/select/etc.) so Start+Select
        // analog-mode combos still work.
        device = RETRO_DEVICE_JOYPAD;
    }
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    // PS1 supports up to 4 players via standard ports (8 with dual Multitap).
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
// Returns lowercased extension (without the dot) of `path`, or "" if none.
// ---------------------------------------------------------------------------
static std::string getExtensionLower(const std::string& path) {
    size_t dot = path.find_last_of('.');
    if (dot == std::string::npos) return "";
    std::string ext = path.substr(dot + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(),
                   [](unsigned char c){ return (char)std::tolower(c); });
    return ext;
}

// Returns true if `ext` is a PS1 CD-image format that PCSX-ReARMed must open
// by path (it parses the disc TOC from the file directly). For these formats
// we DO NOT pre-load the file into memory — that would break multi-track
// .bin/.cue pairs and .chd/.pbp compressed images.
static bool isCdImageFormat(const std::string& ext) {
    return ext == "cue" || ext == "bin" || ext == "chd" ||
           ext == "pbp" || ext == "m3u" || ext == "ecm" ||
           ext == "img" || ext == "iso" || ext == "mdf" || ext == "ccd";
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------
std::string loadFromFile(const std::string& path, int& regionOut) {
    regionOut = 0;

    if (!loadCoreLib()) {
        return s_coreError.empty()
            ? "Failed to load libpcsx_rearmed_libretro_android.so"
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
        LOGI("PCSX-ReARMed core initialized (API version %u)",
             s_retro_api_version());
    }

    if (s_gameLoaded) {
        // Persist memory card (MCD) BEFORE unloading the previous game.
        if (!s_lastRomPath.empty() && s_retro_get_memory_data && s_retro_get_memory_size) {
            void* nvram = s_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
            size_t nvramSize = s_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
            coreshared::saveSramToDisk(nvram, nvramSize, s_saveDir, s_lastRomPath, s_saveName);
        }
        s_retro_unload_game();
        s_gameLoaded = false;
    }

    s_audio.reset();
    s_resampler.reset();

    const std::string ext = getExtensionLower(path);
    const bool cdImage = isCdImageFormat(ext);

    // Store path in a static variable so gameInfo.path points to stable
    // memory that outlives the JNI GetStringUTFChars / ReleaseStringUTFChars
    // cycle. PCSX-ReARMed may read gameInfo.path asynchronously (e.g. for
    // save-state naming), and using the transient cpath pointer caused a
    // strlen-on-null crash when the JNI string was released first.
    s_romPath = path;

    retro_game_info gameInfo{};
    gameInfo.path = s_romPath.c_str();
    gameInfo.data = nullptr;
    gameInfo.size = 0;
    gameInfo.meta = nullptr;

    // For CD images (.cue/.bin/.chd/.pbp/.m3u/.ecm/.img/.iso/.mdf/.ccd),
    // pass the path directly — PCSX-ReARMed opens the disc image itself so it
    // can parse the TOC, locate multi-track .bin files referenced by the .cue,
    // and decompress .chd/.pbp streams. Pre-loading a CD image into memory
    // would break this. No memory-based fallback for CD formats.
    bool ok = s_retro_load_game(&gameInfo);

    // For non-CD formats (.exe / .psf / .minipsf / .pse / etc.), fall back
    // to loading the file into memory and passing data + size to the core.
    // This is the standard libretro behavior for standalone executables.
    if (!ok && !cdImage) {
        FILE* fp = fopen(path.c_str(), "rb");
        if (fp) {
            fseek(fp, 0, SEEK_END);
            long sz = ftell(fp);
            fseek(fp, 0, SEEK_SET);
            if (sz > 0 && sz < 32 * 1024 * 1024) {  // 32 MB cap for .exe/.psf
                std::vector<uint8_t> buf(sz);
                size_t rd = fread(buf.data(), 1, sz, fp);
                fclose(fp);
                if (rd == (size_t)sz) {
                    gameInfo.data = buf.data();
                    gameInfo.size = sz;
                    ok = s_retro_load_game(&gameInfo);
                }
            } else {
                fclose(fp);
            }
        }
    }

    if (!ok) {
        s_coreError = "retro_load_game() failed for: " + path;
        if (!s_coreMessage.empty()) {
            s_coreError += " (";
            s_coreError += s_coreMessage;
            s_coreError += ")";
            s_coreMessage.clear();
        }
        // Provide a detailed Chinese explanation of common PS1 load failures.
        // PCSX-ReARMed rejects CD images for several reasons:
        //   1. Missing BIOS file (scph1001.bin / scph5500.bin) — most common
        //      when pcsx_rearmed_bios is "auto" and no real BIOS file is in
        //      <systemDir>/. The core falls back to HLE BIOS, which works for
        //      most games but breaks a few (e.g. some Megaman / Castlevania).
        //   2. Corrupted / unsupported CD image format — e.g. a .bin without
        //      its .cue file, or a .cue referencing renamed .bin tracks.
        //   3. Multi-track .cue with wrong paths — the .cue file references
        //      .bin tracks by relative path; if the .bin was renamed the core
        //      can't find them.
        //   4. Unsupported .pbp / .chd compression — older builds may not
        //      support these; use .cue/.bin or .iso instead.
        s_coreError += "\n\n常见原因:\n";
        s_coreError += "  1. CD 镜像不完整: 缺少 .cue 文件或 .cue 中引用的 .bin "
                        "轨道名不匹配 (请确保 .cue 与 .bin 在同一目录).\n";
        s_coreError += "  2. BIOS 缺失: 推荐 scph1001.bin (NTSC-U) / scph5500.bin "
                        "(NTSC-J) / scph5552.bin (PAL), 放在 system 目录下 "
                        "(Settings → 系统 → BIOS 管理). 若无 BIOS, 核心会回退到 "
                        "HLE (高模拟), 但少数游戏不兼容.\n";
        s_coreError += "  3. 镜像格式不支持: 请使用 .cue/.bin (推荐) 或 .iso, "
                        "避免使用未压缩的 .ecm (用 unecm 解压为 .bin 后再加载).\n";
        s_coreError += "  4. .pbp / .chd 需要 POPStarter / chdman 转换工具, "
                        "请确认文件完整性.\n";
        s_coreError += "\n详细帮助请查看: Settings → PS1 → ROM 兼容性帮助";
        LOGE("%s", s_coreError.c_str());
        return s_coreError;
    }

    s_gameLoaded = true;
    s_lastRomPath = path;

    // Load memory card (MCD) from disk into the core's SAVE_RAM region.
    if (s_retro_get_memory_data && s_retro_get_memory_size) {
        void* nvram = s_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
        size_t nvramSize = s_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
        coreshared::loadSramFromDisk(nvram, nvramSize, s_saveDir, path, s_saveName);
    }

    // Set up 4 controller ports with standard JOYPAD device.
    // PS1 controller types (standard / analog / DualShock / negcon / gun)
    // are normally set via pcsx_rearmed_pad1type / pad2type in RetroArch,
    // but PCSX-ReARMed's libretro port actually expects them via
    // RETRO_ENVIRONMENT_SET_CONTROLLER_PORT_DEVICE, not core options.
    // We default to RETRO_DEVICE_JOYPAD (digital pad) on all 4 ports.
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
    s_videoW = av.geometry.base_width;
    s_videoH = av.geometry.base_height;

    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        s_frameW = av.geometry.base_width;
        s_frameH = av.geometry.base_height;
        if (s_frameW == 0) s_frameW = 320;
        if (s_frameH == 0) s_frameH = 240;
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

    LOGI("PS1 ROM loaded: %s  rate=%d  fps=%.2f  region=%d  geom=%ux%u  max=%ux%u",
         path.c_str(), s_sampleRate, av.timing.fps, s_region,
         av.geometry.base_width, av.geometry.base_height,
         av.geometry.max_width, av.geometry.max_height);
    return "";
}

void unload() {
    if (s_loaded) {
        if (s_gameLoaded) {
            // Persist memory card (MCD) BEFORE unloading.
            if (!s_lastRomPath.empty() && s_retro_get_memory_data && s_retro_get_memory_size) {
                void* nvram = s_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
                size_t nvramSize = s_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
                coreshared::saveSramToDisk(nvram, nvramSize, s_saveDir, s_lastRomPath, s_saveName);
            }
            s_retro_unload_game();
            s_gameLoaded = false;
        }
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
    s_videoW = 320;
    s_videoH = 240;
    s_lastRomPath.clear();
    s_saveName.clear();
    s_pad1.store(0, std::memory_order_relaxed);
    s_pad2.store(0, std::memory_order_relaxed);
    s_pad3.store(0, std::memory_order_relaxed);
    s_pad4.store(0, std::memory_order_relaxed);

    // ★ 与 fbneo_loader 同款"关闭后再打开闪退"预防性修复：dlclose 核心
    // 库，下次 loadFromFile 重新 dlopen —— 核心静态状态完全归零。
    // PCSX-ReARMed 在 RetroArch 中同样按内容关闭 → dlclose 的生命周期
    // 运行，重新打开时 dynarec/内存池静态全部重建。
    if (s_coreLib) {
        LOGI("dlclose(libpcsx_rearmed_libretro_android.so) — full core state reset");
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
    s_retro_run();
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
    LOGI("MCD save name set: '%s'", name.c_str());
}

void setCoreLibPath(const std::string& path) {
    s_coreLibPath = path;
    LOGI("Core lib path set: %s", s_coreLibPath.c_str());
}

void applyRegion(int /*region*/) { /* region is auto-detected at load */ }
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
    // PS1 default aspect is 4:3 (NTSC) / 5:4 (PAL, slightly wider pixels).
    // We return 4:3 — most PS1 games were designed for 4:3 displays.
    // The frontend can override this if it wants PAL-correct aspect.
    num = 4;
    den = 3;
}

void setVideoFilter(int filter) {
    s_videoFilter.store(filter, std::memory_order_relaxed);
    LOGI("Video filter set: %d", filter);
}

bool isCoreLoaded() {
    return s_coreLib != nullptr;
}

} // namespace psxcore::rom
