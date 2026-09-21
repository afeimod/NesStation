// SPDX-License-Identifier: MIT
// libretro frontend that drives the prebuilt Geargrafx core.
//
// Same dlopen() pattern as genesis_loader.cpp / fbneo_loader.cpp: we resolve
// retro_* symbols at runtime from libgeargrafx_libretro_android.so,
// which ships in app/src/main/jniLibs/<abi>/.
//
// Geargrafx supports:
//   * PC-Engine / TurboGrafx-16  (.pce)
//   * SuperGrafx                 (.sgx)
//   * HES (Hudson Entertainment Sound) rip (.hes)
//   * PCE-CD                     (.cue .chd — requires BIOS in system dir)
//
// Video resolution: 256x224 (typical), 256x242 (NTSC with overscan),
// 256x263 (PAL with overscan), 512x224 (rare high-res mode).
// Filter buffers sized to 512x512 max.
//
// Audio: Geargrafx outputs at 44100 Hz (NTSC) or ~44056 Hz (PAL).
// The resampler converts to 48000 Hz.
//
// Input: standard PCE 2-button controller (RETRO_DEVICE_JOYPAD) on port 0,
// with the SNES-style bit layout mapped to PCE's buttons:
//   SNES A -> PCE II (Jump / Shoot in most games)
//   SNES B -> PCE I  (Action / Run in most games)
//   SNES Select -> Select,  SNES Start -> Run (Start)
//
// PCE-CD BIOS files (looked up by filename in <systemDir>):
//   syscard1.pce — System Card 1
//   syscard2.pce — System Card 2
//   syscard3.pce — System Card 3 (Arcade Card Pro — most common, recommended)
//   gexpress.pce — Games Express BIOS (required for some adult games)
//
// NOTE: the core uses "gexpress.pce", NOT "gameexpress.pce".

#include "pce_loader.h"
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
#include <unistd.h>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#define TAG "pcecore-rom"
#undef LOGI
#undef LOGW
#undef LOGE
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace pcecore::rom {

// ---------------------------------------------------------------------------
// Maximum video resolution. PCE rare hi-res mode is 512x224.
// Maximum video resolution.
// PCE standard: 256x242 (NTSC) / 256x263 (PAL)
// PCE high-res: 512x242 (width_scale=2)
// PCE scaled: 1024x242 or 1120x242 (width_scale=3, with/without overscan)
// Geargrafx's MAX_SCREEN_WIDTH=1120, MAX_SCREEN_HEIGHT=263.
// 1120x264 covers every supported PCE system with minimal static memory.
// ---------------------------------------------------------------------------
static constexpr int kMaxW = 1120;
static constexpr int kMaxH = 264;

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
static int  s_region = 0;
static std::string s_systemDir;
static std::string s_saveDir;
static std::string s_saveName;
static std::string s_lastRomPath;
static std::string s_coreMessage;
static std::string s_coreError;
static std::string s_coreLibPath;

// === Pre-loaded ROM buffer (persists for the game's lifetime) ===
// Geargrafx's load_hucard() has two paths:
//   1. If info->data != NULL → LoadHuCardFromBuffer (copies data, works)
//   2. If info->data == NULL → uses VFS interface to open the file
// We do NOT implement VFS (RETRO_ENVIRONMENT_GET_VFS_INTERFACE returns false),
// so we MUST pre-load the ROM into memory and pass it via info->data.
// The buffer persists in s_extRomData so the pointer stays valid throughout
// the game session (Geargrafx copies it internally, but we keep it alive
// defensively in case any deferred access happens).
static std::vector<uint8_t> s_extRomData;

// Dynamic frame buffer (ARGB, 0xAARRGGBB).
static std::mutex s_frameMtx;
static std::vector<uint32_t> s_frame;
static unsigned s_frameW = 0;
static unsigned s_frameH = 0;
static std::atomic<bool> s_newFrame{false};

static unsigned s_videoW = 256;
static unsigned s_videoH = 240;
static unsigned s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;

// Gamepad bits (port 0 / port 1, RETRO_DEVICE_JOYPAD).
static std::atomic<uint16_t> s_pad1{0};
static std::atomic<uint16_t> s_pad2{0};

static std::atomic<int>  s_videoFilter{0};
static std::atomic<bool> s_highQualityScaling{false};
static std::atomic<bool> s_fastForward{false};
static std::atomic<int>  s_ffFrameSkip{0};
static std::atomic<int>  s_ffMaxSkip{6};

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
// Initialize Geargrafx core options with sensible defaults.
// Keys AND values MUST match libretro_core_options.h exactly — Geargrafx's
// check_variables() uses case-sensitive strcmp() to compare values, so
// "disabled" (lowercase) will NOT match "Disabled" and the option will be
// ignored, falling back to the core's internal default (which may not be
// what we want).
//
// The values below are copied 1:1 from the reference source's
// libretro_core_options.h "default" field for each option.
// ---------------------------------------------------------------------------
static void initDefaultOptions() {
    // --- System ---
    s_options["geargrafx_console_type"]          = "Auto";  // Auto | PC Engine (JAP) | SuperGrafx (JAP) | TurboGrafx-16 (USA)
    s_options["geargrafx_backup_ram"]            = "Enabled";
    s_options["geargrafx_deterministic_netplay"] = "Disabled";
    s_options["geargrafx_safe_vdc_defaults"]     = "Disabled";

    // --- Video ---
    // CRITICAL: scanline_count/start/end must match the reference defaults
    // exactly. The reference default for scanline_count is "224p", which
    // sets visible scanlines to [11, 234] (224 lines). If we pass "0" for
    // scanline_count, check_variables() falls through to the "Manual" else
    // branch and uses scanline_start/scanline_end. If those are also "0",
    // SetScanlineEnd(0) is called, making the visible height = 1 pixel →
    // BLACK SCREEN with audio. This was the root cause of the PCE black
    // screen bug.
    s_options["geargrafx_aspect_ratio"]          = "4:3 DAR";  // 1:1 PAR | 4:3 DAR | 6:5 DAR | 16:9 DAR | 16:10 DAR
    s_options["geargrafx_overscan"]              = "Disabled";  // Disabled | Enabled
    s_options["geargrafx_scanline_count"]        = "224p";      // 224p | 240p | Manual
    s_options["geargrafx_scanline_start"]        = "3";         // 0..30 (used only when count=Manual)
    s_options["geargrafx_scanline_end"]          = "241";       // 220..241 (used only when count=Manual)
    s_options["geargrafx_palette"]               = "Standard RGB";  // Standard RGB | Turboxray | Kitrinx
    s_options["geargrafx_no_sprite_limit"]       = "Disabled";  // Disabled | Enabled

    // --- Audio ---
    s_options["geargrafx_lowpass_filter"]        = "Disabled";  // Disabled | Enabled
    s_options["geargrafx_lowpass_intensity"]     = "100";       // 0..100
    s_options["geargrafx_lowpass_cutoff"]        = "5.0 MHz";   // 3.0..7.0 MHz
    s_options["geargrafx_lowpass_speed_536"]     = "Disabled";  // Disabled | Enabled (256px mode)
    s_options["geargrafx_lowpass_speed_716"]     = "Enabled";   // Disabled | Enabled (341px mode)
    s_options["geargrafx_lowpass_speed_108"]     = "Enabled";   // Disabled | Enabled (512px mode)
    s_options["geargrafx_psg_huc6280a"]          = "Enabled";   // Enabled | Disabled
    s_options["geargrafx_psg_volume"]            = "100";       // 0..200
    s_options["geargrafx_cdrom_volume"]          = "100";       // 0..200
    s_options["geargrafx_adpcm_volume"]          = "100";       // 0..200

    // --- CD-ROM ---
    // BIOS files required in <systemDir> for PCE-CD games:
    //   syscard1.pce, syscard2.pce, syscard3.pce, gexpress.pce
    // syscard3.pce (System Card 3 / Arcade Card Pro) is the most common
    // and is auto-selected by Geargrafx when cdrom_bios = "Auto".
    s_options["geargrafx_cdrom_type"]            = "Auto";      // Auto | Standard | Super CD-ROM | Arcade CD-ROM
    s_options["geargrafx_cdrom_bios"]            = "Auto";      // Auto | System Card 1 | System Card 2 | System Card 3 | Game Express
    s_options["geargrafx_cdrom_preload"]         = "Disabled";  // Disabled | Enabled

    // --- Input / Accessories ---
    s_options["geargrafx_up_down_allowed"]       = "Disabled";  // Disabled | Enabled
    s_options["geargrafx_soft_reset"]            = "Enabled";   // Enabled | Disabled
    s_options["geargrafx_turbotap"]              = "Disabled";  // Disabled | Enabled
    s_options["geargrafx_mb128"]                 = "Auto";      // Auto | Enabled | Disabled
    s_options["geargrafx_mouse_sensitivity"]     = "5";         // 1..15
    s_options["geargrafx_avenue_pad_3_switch"]   = "Auto";      // Auto | SELECT | RUN

    // --- Turbo / Auto-fire (off by default — user enables per-game) ---
    s_options["geargrafx_turbo_toggle_hotkey"]   = "Disabled";  // Disabled | Enabled
    s_options["geargrafx_turbo_p1_i"]            = "Disabled";  // Disabled | Enabled
    s_options["geargrafx_turbo_speed_p1_i"]      = "4";         // 1..15
    s_options["geargrafx_turbo_p1_ii"]           = "Disabled";
    s_options["geargrafx_turbo_speed_p1_ii"]     = "4";
    s_options["geargrafx_turbo_p2_i"]            = "Disabled";
    s_options["geargrafx_turbo_speed_p2_i"]      = "4";
    s_options["geargrafx_turbo_p2_ii"]           = "Disabled";
    s_options["geargrafx_turbo_speed_p2_ii"]     = "4";
    // P3-P5 turbo options (same pattern)
    s_options["geargrafx_turbo_p3_i"]            = "Disabled";
    s_options["geargrafx_turbo_speed_p3_i"]      = "4";
    s_options["geargrafx_turbo_p3_ii"]           = "Disabled";
    s_options["geargrafx_turbo_speed_p3_ii"]     = "4";
    s_options["geargrafx_turbo_p4_i"]            = "Disabled";
    s_options["geargrafx_turbo_speed_p4_i"]      = "4";
    s_options["geargrafx_turbo_p4_ii"]           = "Disabled";
    s_options["geargrafx_turbo_speed_p4_ii"]     = "4";
    s_options["geargrafx_turbo_p5_i"]            = "Disabled";
    s_options["geargrafx_turbo_speed_p5_i"]      = "4";
    s_options["geargrafx_turbo_p5_ii"]           = "Disabled";
    s_options["geargrafx_turbo_speed_p5_ii"]     = "4";
}

// ---------------------------------------------------------------------------
// dlopen the core .so and resolve all retro_* symbols.
// ---------------------------------------------------------------------------
static bool loadCoreLib() {
    if (s_coreLib) return true;

    std::vector<std::string> candidates;
    if (!s_coreLibPath.empty()) candidates.push_back(s_coreLibPath);
    candidates.push_back("libgeargrafx_libretro_android.so");

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
        s_coreError = "dlopen(libgeargrafx_libretro_android.so) failed: ";
        s_coreError += (lastDlError ? lastDlError : "(unknown)");
        LOGE("%s", s_coreError.c_str());
        return false;
    }

    LOGI("dlopen(libgeargrafx_libretro_android.so) OK");

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
    __android_log_vprint(prio, "geargrafx", fmt, ap);
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
#ifdef RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
#endif
            return true;

        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            // Geargrafx calls this when screen dimensions change mid-game.
            // We don't need to do anything — cb_video already updates
            // s_videoW/s_videoH from the width/height parameters every frame.
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
    if (!data) {
        LOGW("cb_video: data is NULL (width=%u, height=%u) — core has no frame to render", width, height);
        return;
    }

    // Log the first few frames for debugging
    static int s_frameCount = 0;
    if (s_frameCount < 3) {
        LOGI("cb_video: frame#%d  %ux%u  pitch=%zu  pixelFormat=%u  data=%p",
             s_frameCount, width, height, pitch, s_pixelFormat, data);
        s_frameCount++;
    }

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
            // Geargrafx defaults to RGB565
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
            // 0RGB1555
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

    if (s_fastForward.load(std::memory_order_relaxed)) {
        int skip = s_ffMaxSkip.load(std::memory_order_relaxed);
        if (skip > 0 && s_ffFrameSkip.fetch_add(1, std::memory_order_relaxed) % skip != 0)
            return;
    } else {
        s_ffFrameSkip.store(0, std::memory_order_relaxed);
    }

    const int filter = s_videoFilter.load(std::memory_order_relaxed);

    // Check if surface is attached before blitting
    {
        std::lock_guard<std::mutex> lk(s_windowMtx);
        if (!s_window) {
            // No surface attached yet — frame is stored in s_frame for later
            // retrieval via getFrameBuffer(). This is normal during startup.
            return;
        }
    }

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
                              unsigned /*index*/, unsigned id) {
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    const uint16_t bits = (port == 0) ? s_pad1.load(std::memory_order_relaxed)
                                      : (port == 1) ? s_pad2.load(std::memory_order_relaxed)
                                                    : 0;
    if (id >= 16) return 0;
    return (bits >> id) & 1;
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------
std::string loadFromFile(const std::string& path, int& regionOut) {
    regionOut = 0;

    if (!loadCoreLib()) {
        return s_coreError.empty() ? "Failed to load libgeargrafx_libretro_android.so" : s_coreError;
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
        LOGI("Geargrafx core initialized (API version %u)", s_retro_api_version());
    }

    if (s_gameLoaded) {
        // Persist SRAM BEFORE unloading the previous game.
        if (!s_lastRomPath.empty() && s_retro_get_memory_data && s_retro_get_memory_size) {
            void* sram = s_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
            size_t sramSize = s_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
            coreshared::saveSramToDisk(sram, sramSize, s_saveDir, s_lastRomPath, s_saveName);
        }
        s_retro_unload_game();
        s_gameLoaded = false;
    }

    s_audio.reset();
    s_resampler.reset();

    // === Pre-load ROM into memory ===
    // Geargrafx's load_hucard() requires either info->data (in-memory buffer)
    // OR a VFS interface (which we don't implement). We MUST pre-load the ROM
    // and pass it via info->data. The buffer persists in s_extRomData for the
    // game's lifetime.
    //
    // For PCE-CD (.cue/.chd), we pass only the path — Geargrafx's LoadMedia
    // uses its own CD-ROM image parsing which works with file paths directly
    // (it opens the .bin files referenced by the .cue using standard C I/O).
    s_extRomData.clear();

    // Determine if this is a CD image (cue/chd/iso) or a cartridge (pce/sgx/hes)
    std::string ext;
    size_t lastDot = path.find_last_of('.');
    if (lastDot != std::string::npos) {
        ext = path.substr(lastDot + 1);
        std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
    }
    bool isCdImage = (ext == "cue" || ext == "chd" || ext == "iso");

    retro_game_info gameInfo{};
    gameInfo.path = path.c_str();
    gameInfo.meta = nullptr;

    if (!isCdImage) {
        // Cartridge: pre-load into s_extRomData
        FILE* fp = fopen(path.c_str(), "rb");
        if (!fp) {
            s_coreError = "Cannot open ROM file: " + path;
            LOGE("%s", s_coreError.c_str());
            return s_coreError;
        }
        fseek(fp, 0, SEEK_END);
        long sz = ftell(fp);
        fseek(fp, 0, SEEK_SET);
        if (sz <= 0 || sz > 64 * 1024 * 1024) {
            fclose(fp);
            s_coreError = "ROM size out of range: " + std::to_string(sz) + " bytes";
            LOGE("%s", s_coreError.c_str());
            return s_coreError;
        }
        s_extRomData.resize((size_t)sz);
        size_t rd = fread(s_extRomData.data(), 1, (size_t)sz, fp);
        fclose(fp);
        if (rd != (size_t)sz) {
            s_coreError = "ROM read incomplete: got " + std::to_string(rd) +
                          " of " + std::to_string(sz) + " bytes";
            LOGE("%s", s_coreError.c_str());
            return s_coreError;
        }
        gameInfo.data = s_extRomData.data();
        gameInfo.size = s_extRomData.size();
        LOGI("Pre-loaded ROM: %s (%zu bytes, ext=%s)",
             path.c_str(), s_extRomData.size(), ext.c_str());
    } else {
        // CD image: pass path only, no pre-load
        gameInfo.data = nullptr;
        gameInfo.size = 0;
        LOGI("Loading CD image: %s (ext=%s)", path.c_str(), ext.c_str());
    }

    LOGI("About to call retro_load_game for: %s (systemDir=%s, data=%p, size=%zu)",
         path.c_str(), s_systemDir.c_str(), gameInfo.data, gameInfo.size);

    bool ok = s_retro_load_game(&gameInfo);

    if (!ok) {
        s_coreError = "retro_load_game() failed for: " + path;
        if (!s_coreMessage.empty()) {
            s_coreError += " (";
            s_coreError += s_coreMessage;
            s_coreError += ")";
            s_coreMessage.clear();
        }
        // For PCE-CD games, the failure may be caused by missing audio
        // tracks (.bin) referenced by the .cue, or by a missing System
        // Card BIOS. Distinguish the two so the user sees the real fix.
        std::string ext;
        size_t dot = path.find_last_of('.');
        if (dot != std::string::npos) ext = path.substr(dot + 1);
        std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
        if (ext == "cue" || ext == "chd" || ext == "iso") {
            // For .cue files, check whether referenced tracks exist on disk.
            if (ext == "cue") {
                std::string missingTracks;
                std::string dir = path.substr(0, path.find_last_of('/') + 1);
                if (dir.empty()) dir = "./";
                FILE* cueFp = fopen(path.c_str(), "rb");
                if (cueFp) {
                    char line[1024];
                    while (fgets(line, sizeof(line), cueFp)) {
                        std::string s(line);
                        std::string lc(s);
                        std::transform(lc.begin(), lc.end(), lc.begin(), ::tolower);
                        if (lc.find("file") == 0) {
                            // cue "FILE" line: FILE "track01.bin" BINARY
                            size_t q1 = s.find('"');
                            if (q1 != std::string::npos) {
                                size_t q2 = s.find('"', q1 + 1);
                                if (q2 != std::string::npos) {
                                    std::string trackName = s.substr(q1 + 1, q2 - q1 - 1);
                                    std::string trackPath = dir + trackName;
                                    if (access(trackPath.c_str(), F_OK) != 0) {
                                        if (!missingTracks.empty()) missingTracks += ", ";
                                        missingTracks += trackName;
                                    }
                                }
                            }
                        }
                    }
                    fclose(cueFp);
                }
                if (!missingTracks.empty()) {
                    s_coreError += "\nCD 音轨文件缺失（找不到 .cue 引用的音频轨）：" +
                                   missingTracks +
                                   "\n请把 .cue 和 .bin 音轨放在同一文件夹后重新导入。";
                } else {
                    s_coreError += "\nFor PCE-CD games, ensure a System Card BIOS "
                                   "is in the system directory: syscard1.pce, "
                                   "syscard2.pce, syscard3.pce (recommended), or "
                                   "gexpress.pce.";
                }
            } else {
                s_coreError += "\nFor PCE-CD games, ensure a System Card BIOS "
                               "is in the system directory: syscard1.pce, "
                               "syscard2.pce, syscard3.pce (recommended), or "
                               "gexpress.pce.";
            }
        }
        LOGE("%s", s_coreError.c_str());
        return s_coreError;
    }

    s_gameLoaded = true;
    s_lastRomPath = path;

    // Load battery-backed SRAM from disk into the core's SAVE_RAM region.
    if (s_retro_get_memory_data && s_retro_get_memory_size) {
        void* sram = s_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
        size_t sramSize = s_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
        coreshared::loadSramFromDisk(sram, sramSize, s_saveDir, path, s_saveName);
    }

    // Set up controller port (default = JOYPAD = 2-button PCE pad).
    if (s_retro_set_controller_port_device) {
        s_retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
        s_retro_set_controller_port_device(1, RETRO_DEVICE_JOYPAD);
    }

    retro_system_av_info av{};
    s_retro_get_system_av_info(&av);
    s_sampleRate = (int)av.timing.sample_rate;
    s_region = (av.timing.fps < 55.0) ? 1 : 0;
    regionOut = s_region;
    s_videoW = av.geometry.base_width;
    s_videoH = av.geometry.base_height;

    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        s_frameW = av.geometry.base_width;
        s_frameH = av.geometry.base_height;
        if (s_frameW == 0) s_frameW = 256;
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

    LOGI("PCE ROM loaded: %s  rate=%d  fps=%.2f  region=%d  geom=%ux%u  max=%ux%u",
         path.c_str(), s_sampleRate, av.timing.fps, s_region,
         av.geometry.base_width, av.geometry.base_height,
         av.geometry.max_width, av.geometry.max_height);
    return "";
}

void unload() {
    if (s_loaded) {
        if (s_gameLoaded) {
            if (!s_lastRomPath.empty() && s_retro_get_memory_data && s_retro_get_memory_size) {
                void* sram = s_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
                size_t sramSize = s_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
                coreshared::saveSramToDisk(sram, sramSize, s_saveDir, s_lastRomPath, s_saveName);
            }
            s_retro_unload_game();
            s_gameLoaded = false;
        }
        s_retro_deinit();
        s_loaded = false;
    }
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
    s_videoH = 240;
    s_lastRomPath.clear();
    s_saveName.clear();
    s_pad1.store(0, std::memory_order_relaxed);
    s_pad2.store(0, std::memory_order_relaxed);
    // Clear the pre-loaded ROM buffer so memory is freed between games.
    s_extRomData.clear();
    s_extRomData.shrink_to_fit();

    // ★ 与 fbneo_loader 同款"关闭后再打开闪退"预防性修复：dlclose 核心
    // 库，下次 loadFromFile 重新 dlopen —— 核心静态状态完全归零。
    if (s_coreLib) {
        LOGI("dlclose(libgeargrafx_libretro_android.so) — full core state reset");
        dlclose(s_coreLib);
        s_coreLib = nullptr;
    }
}

void resetEmulation(bool /*hard*/) {
    if (s_loaded && s_gameLoaded) s_retro_reset();
}

void stepFrame() {
    if (!s_loaded || !s_gameLoaded) return;
    s_retro_run();
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

int readAudio(int16_t* out, int maxFrames) {
    if (!s_loaded || !s_gameLoaded) return 0;
    return s_resampler.readResampled(s_audio, out, maxFrames);
}

int audioSampleRate() { return s_sampleRate; }
int audioTargetSampleRate() { return s_sampleRate; }  // default audio == core rate

void setControllerInput(int port, uint16_t bits) {
    if (port == 0)      s_pad1.store(bits, std::memory_order_relaxed);
    else if (port == 1) s_pad2.store(bits, std::memory_order_relaxed);
}

void setPaths(const std::string& systemDir, const std::string& saveDir) {
    s_systemDir = systemDir;
    s_saveDir = saveDir;
}

void setSaveName(const std::string& name) {
    s_saveName = name;
    LOGI("SRAM save name set: '%s'", name.c_str());
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
    // PCE: 4:3 (256×242 → 4:3 with slight stretch; matches original cabinet)
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

} // namespace pcecore::rom
