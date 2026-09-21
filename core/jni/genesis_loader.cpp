// SPDX-License-Identifier: MIT
// libretro frontend that drives the prebuilt Genesis-Plus-GX core.
//
// Same dlopen() pattern as fbneo_loader.cpp / dos_loader.cpp: we resolve
// retro_* symbols at runtime from libgenesis_plus_gx_libretro_android.so,
// which ships in app/src/main/jniLibs/<abi>/.
//
// Genesis-Plus-GX supports SEGA:
//   * Mega Drive / Genesis     (.md .bin .smd .gen .68k)
//   * Master System            (.sms)
//   * Game Gear                (.gg)
//   * SG-1000                  (.sg)
//   * Mega-CD / SEGA-CD        (.cue .chd .iso — requires BIOS in system dir)
//
// Video resolution varies by system:
//   MD:     320x224 (NTSC) / 320x240 (PAL) — H40 mode
//           256x224 (NTSC) / 256x240 (PAL) — H32 mode
//           320x448 / 256x448 (interlaced)
//   SMS:    256x192 / 256x224 (PAL)
//   GG:     160x144 (native) / 256x144 (cropped)
//   SG-1k:  256x192
// Filter buffers sized to 512x512 max.
//
// Audio: Genesis-Plus-GX outputs at 44100 Hz (NTSC) or 53267 Hz (PAL SMS)
// or 48000 Hz (some configurations). The resampler converts to 48000 Hz.
//
// Input: 3-button and 6-button SEGA controllers (RETRO_DEVICE_JOYPAD) on
// port 0, with the SNES-style bit layout remapped to SEGA's button order
// by the libretro frontend:
//   SNES A -> SEGA A,  SNES B -> SEGA B,  SNES X -> SEGA C,
//   SNES Y -> SEGA X (6-btn only),
//   SNES L -> SEGA Y (6-btn only),
//   SNES R -> SEGA Z (6-btn only),
//   SNES Select -> Mode,  SNES Start -> Start.

#include "genesis_loader.h"
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

#define TAG "genesicore-rom"
#undef LOGI
#undef LOGW
#undef LOGE
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace genesicore::rom {

// ---------------------------------------------------------------------------
// Maximum video resolution. MD interlaced hi-res goes up to 320x448.
// 512x512 covers every supported SEGA system with margin.
// ---------------------------------------------------------------------------
static constexpr int kMaxW = 512;
static constexpr int kMaxH = 512;

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

// === Extended game info for RETRO_ENVIRONMENT_GET_GAME_INFO_EXT ===
// Genesis-Plus-GX's retro_load_game() queries GET_GAME_INFO_EXT. If we
// implement it and return the in-memory ROM buffer, GPGX uses the buffer
// directly (g_rom_data/g_rom_size) and skips filestream_open(). This
// bypasses any file-path encoding issues (Chinese characters, spaces,
// brackets in paths like "/storage/emulated/0/游戏/MD/Double Dragon (UE) [!].sms")
// and lets the core read the ROM from memory.
//
// Without this, GPGX relies on info->path and filestream_open() — which
// can silently fail on certain Android FUSE paths, producing a black screen
// with no error logged (the user's reported SMS bug).
static std::vector<uint8_t> s_extRomData;
static std::string s_extRomDir;
static std::string s_extRomName;
static std::string s_extRomExt;
static struct retro_game_info_ext s_extGameInfo;
static bool s_extGameInfoValid = false;

// Dynamic frame buffer (ARGB, 0xAARRGGBB).
static std::mutex s_frameMtx;
static std::vector<uint32_t> s_frame;
static unsigned s_frameW = 0;
static unsigned s_frameH = 0;
static std::atomic<bool> s_newFrame{false};

static unsigned s_videoW = 320;
static unsigned s_videoH = 224;
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
// Initialize Genesis-Plus-GX core options with sensible defaults.
// Keys MUST match libretro_core_options.h exactly.
// ---------------------------------------------------------------------------
static void initDefaultOptions() {
    // --- System / Region ---
    s_options["genesis_plus_gx_region"]              = "auto";  // auto | ntsc-u | pal | ntsc-j
    s_options["genesis_plus_gx_system"]              = "auto";  // auto | md | sms | gg | sg
    s_options["genesis_plus_gx_bios"]                = "disabled";
    s_options["genesis_plus_gx_force_dtack"]         = "enabled";
    s_options["genesis_plus_gx_addr_error"]          = "enabled";
    s_options["genesis_plus_gx_lock_on"]             = "disabled";  // lock-on: game-genie | super
    s_options["genesis_plus_gx_cartridge_fuson"]     = "disabled";

    // --- Video ---
    s_options["genesis_plus_gx_left_border"]         = "disabled";
    s_options["genesis_plus_gx_right_border"]        = "disabled";
    s_options["genesis_plus_gx_top_border"]          = "disabled";
    s_options["genesis_plus_gx_bottom_border"]       = "disabled";
    s_options["genesis_plus_gx_aspect_ratio"]        = "auto";  // auto | 4:3 | 16:9 | stretch
    s_options["genesis_plus_gx_render"]              = "normal";  // normal | double | interlaced
    s_options["genesis_plus_gx_filter"]              = "disabled";  // disabled | composite | svideo | rgb
    s_options["genesis_plus_gx_blargg_ntsc_filter"]  = "disabled";
    s_options["genesis_plus_gx_lcd_filter"]          = "disabled";
    s_options["genesis_plus_gx_overscan"]            = "disabled";
    s_options["genesis_plus_gx_gg_extra"]            = "disabled";  // GG extended screen
    s_options["genesis_plus_gx_aspect_ratio_pal"]    = "auto";

    // --- Audio ---
    s_options["genesis_plus_gx_audio_filter"]        = "disabled";
    s_options["genesis_plus_gx_audio_filter_range"]  = "60";
    s_options["genesis_plus_gx_lowpass_range"]       = "60";
    s_options["genesis_plus_gx_psg_preamp"]          = "150";
    s_options["genesis_plus_gx_fm_preamp"]           = "100";
    s_options["genesis_plus_gx_cdda_volume"]         = "100";
    s_options["genesis_plus_gx_pcm_volume"]          = "100";
    s_options["genesis_plus_gx_audio_eq_low"]        = "100";
    s_options["genesis_plus_gx_audio_eq_mid"]        = "100";
    s_options["genesis_plus_gx_audio_eq_high"]       = "100";

    // --- Input ---
    // 6-button SEGA controller is the default — Genesis-Plus-GX auto-detects
    // when a game uses the Mode button or 6-button layout.
    s_options["genesis_plus_gx_input"]               = "6 button";  // 3 button | 6 button
    s_options["genesis_plus_gx_mouse"]               = "disabled";  // enabled | disabled
    s_options["genesis_plus_gx_menacer"]             = "disabled";
    s_options["genesis_plus_gx_justifier"]           = "disabled";
    s_options["genesis_plus_gx_multitap"]            = "disabled";
    s_options["genesis_plus_gx_allow_up_down_allowed"] = "disabled";

    // --- Mega-CD ---
    // Bios files required in <systemDir>:
    //   bios_CD_E.zip — European Mega-CD BIOS
    //   bios_CD_J.zip — Japanese Mega-CD BIOS
    //   bios_CD_U.zip — US SEGA-CD BIOS
    // Each zip contains a single .bin file (e.g. bios_CD_E.bin).
    s_options["genesis_plus_gx_cd_bios"]             = "auto";
    s_options["genesis_plus_gx_cd_perfect_sync"]     = "disabled";
    s_options["genesis_plus_gx_cd_fastboot"]         = "enabled";

    // --- Performance ---
    s_options["genesis_plus_gx_overclock"]           = "100%";  // 100% | 125% | 150% | 200%
    s_options["genesis_plus_gx_frameskip"]           = "0";     // 0..5

    // --- SMS/GG-specific ---
    s_options["genesis_plus_gx_sms_fm"]              = "auto";  // auto | on | off
    s_options["genesis_plus_gx_sms_fmchannel"]       = "YM2413";
    s_options["genesis_plus_gx_sms_cart"]            = "disabled";
    s_options["genesis_plus_gx_gg_stretch"]          = "disabled";
}

// ---------------------------------------------------------------------------
// dlopen the core .so and resolve all retro_* symbols.
// ---------------------------------------------------------------------------
static bool loadCoreLib() {
    if (s_coreLib) return true;

    std::vector<std::string> candidates;
    if (!s_coreLibPath.empty()) candidates.push_back(s_coreLibPath);
    candidates.push_back("libgenesis_plus_gx_libretro_android.so");

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
        s_coreError = "dlopen(libgenesis_plus_gx_libretro_android.so) failed: ";
        s_coreError += (lastDlError ? lastDlError : "(unknown)");
        LOGE("%s", s_coreError.c_str());
        return false;
    }

    LOGI("dlopen(libgenesis_plus_gx_libretro_android.so) OK");

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
    __android_log_vprint(prio, "genplus", fmt, ap);
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

        // === Provide extended game info so GPGX uses our in-memory ROM buffer ===
        // Genesis-Plus-GX's retro_load_game() queries GET_GAME_INFO_EXT. If we
        // return our pre-loaded ROM buffer here, GPGX copies it directly into
        // cart.rom via load_archive()'s g_rom_data fast-path — bypassing
        // filestream_open() on the (possibly Chinese-character-laden) path.
        // Without this, GPGX falls back to info->path and may fail silently.
        case RETRO_ENVIRONMENT_GET_GAME_INFO_EXT: {
            if (!s_extGameInfoValid) {
                LOGW("GET_GAME_INFO_EXT: no extended info available "
                     "(s_extGameInfoValid=false) — GPGX will read from path");
                return false;
            }
            if (data) {
                *static_cast<struct retro_game_info_ext**>(data) = &s_extGameInfo;
                LOGI("GET_GAME_INFO_EXT: returning in-memory buffer "
                     "(data=%p, size=%zu, full_path=%s)",
                     s_extGameInfo.data, s_extGameInfo.size,
                     s_extGameInfo.full_path ? s_extGameInfo.full_path : "(null)");
            }
            return true;
        }

        default:
            return false;
    }
}

static void cb_video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;

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
        return s_coreError.empty() ? "Failed to load libgenesis_plus_gx_libretro_android.so" : s_coreError;
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
        LOGI("Genesis-Plus-GX core initialized (API version %u)", s_retro_api_version());
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

    // === Pre-load ROM into memory and fill extended game info ===
    // Genesis-Plus-GX queries GET_GAME_INFO_EXT during retro_load_game().
    // If we provide the in-memory buffer, GPGX uses it directly via
    // load_archive()'s g_rom_data fast-path — bypassing filestream_open()
    // which can fail silently on paths with Chinese characters / spaces.
    // This fixes the SMS black screen bug where the core logs BIOS paths
    // but never logs "Loading N bytes" because filestream_open returned NULL.
    //
    // === IMPORTANT: Mega-CD games (.cue/.iso/.chd) must NOT be pre-loaded ===
    // .cue is a tiny text file pointing at .bin tracks. If we pre-load the
    // .cue into romData and set gameInfo.data, the core tries to parse the
    // cue TEXT as the binary CD image → black screen + "retro_load_game
    // failed". For Mega-CD we must pass only the file path (gameInfo.path)
    // and let the core's own CD loader open the cue + bin tracks + BIOS.
    std::vector<uint8_t> romData;
    // === Parse path first — we need the extension to decide whether to
    // pre-load the file (cart) or skip pre-load (Mega-CD). ===
    size_t lastSlash = path.find_last_of('/');
    std::string fileName = (lastSlash != std::string::npos)
        ? path.substr(lastSlash + 1) : path;
    s_extRomDir = (lastSlash != std::string::npos)
        ? path.substr(0, lastSlash) : ".";
    size_t lastDot = fileName.find_last_of('.');
    if (lastDot != std::string::npos) {
        s_extRomName = fileName.substr(0, lastDot);
        s_extRomExt = fileName.substr(lastDot + 1);
        std::transform(s_extRomExt.begin(), s_extRomExt.end(),
                       s_extRomExt.begin(),
                       [](unsigned char c) { return std::tolower(c); });
    } else {
        s_extRomName = fileName;
        s_extRomExt = "md";
    }

    // === IMPORTANT: Mega-CD games (.cue/.iso/.chd) must NOT be pre-loaded ===
    // .cue is a tiny text file pointing at .bin tracks. If we pre-load the
    // .cue into romData and set gameInfo.data, the core tries to parse the
    // cue TEXT as the binary CD image → black screen + "retro_load_game
    // failed". For Mega-CD we must pass only the file path (gameInfo.path)
    // and let the core's own CD loader open the cue + bin tracks + BIOS.
    //
    // Note: "bin" is ambiguous (could be MD cart or CD track). For .bin we
    // only skip pre-load if it's accompanied by a .cue sibling — but we don't
    // have that info here. The safer bet: skip pre-load for .cue/.iso/.chd
    // only (the unambiguous CD extensions). .bin alone is treated as MD cart.
    bool isCdGame = (s_extRomExt == "cue" || s_extRomExt == "iso" ||
                     s_extRomExt == "chd");
    if (!isCdGame) {
        FILE* f = std::fopen(path.c_str(), "rb");
        if (f) {
            std::fseek(f, 0, SEEK_END);
            long sz = std::ftell(f);
            std::fseek(f, 0, SEEK_SET);
            if (sz > 0 && sz < 64 * 1024 * 1024) {
                romData.resize((size_t)sz);
                size_t rd = std::fread(romData.data(), 1, (size_t)sz, f);
                if (rd == (size_t)sz) {
                    // Move romData into the persistent s_extRomData so it
                    // survives beyond this block (GPGX queries
                    // GET_GAME_INFO_EXT later during retro_load_game()).
                    s_extRomData = std::move(romData);

                    std::memset(&s_extGameInfo, 0, sizeof(s_extGameInfo));
                    s_extGameInfo.full_path     = path.c_str();
                    s_extGameInfo.archive_path  = nullptr;
                    s_extGameInfo.archive_file  = nullptr;
                    s_extGameInfo.dir           = s_extRomDir.c_str();
                    s_extGameInfo.name          = s_extRomName.c_str();
                    s_extGameInfo.ext           = s_extRomExt.c_str();
                    s_extGameInfo.meta          = nullptr;
                    s_extGameInfo.data          = s_extRomData.data();
                    s_extGameInfo.size          = s_extRomData.size();
                    s_extGameInfo.file_in_archive = false;
                    s_extGameInfoValid = true;
                    LOGI("Filled ext game info: path=%s, dir=%s, name=%s, ext=%s, "
                         "data=%p, size=%zu",
                         path.c_str(), s_extRomDir.c_str(), s_extRomName.c_str(),
                         s_extRomExt.c_str(), s_extGameInfo.data, s_extGameInfo.size);
                } else {
                    LOGW("ROM read partial: got %zu of %ld bytes — falling back to path mode",
                         rd, sz);
                    s_extRomData.clear();
                    s_extGameInfoValid = false;
                }
            } else {
                LOGW("ROM size out of range: %ld bytes — falling back to path mode", sz);
                s_extRomData.clear();
                s_extGameInfoValid = false;
            }
            std::fclose(f);
        } else {
            LOGW("Cannot open ROM file for pre-load: %s — falling back to path mode",
                 path.c_str());
            s_extRomData.clear();
            s_extGameInfoValid = false;
        }
    } else {
        // === Mega-CD game: do NOT pre-load data, only fill ext info ===
        // For CD games (.cue/.iso/.chd), the core's own CD loader needs to open
        // the file by path (cue sheet references .bin tracks, the core opens
        // those + the BIOS files from systemDir). Pre-loading the cue text
        // into gameInfo.data would confuse the core → black screen.
        s_extRomData.clear();

        // Fill ext game info with path only (no data pointer) — the core
        // still queries GET_GAME_INFO_EXT to learn the file extension, but
        // it sees data==nullptr and uses the path to open the file itself.
        std::memset(&s_extGameInfo, 0, sizeof(s_extGameInfo));
        s_extGameInfo.full_path     = path.c_str();
        s_extGameInfo.archive_path  = nullptr;
        s_extGameInfo.archive_file  = nullptr;
        s_extGameInfo.dir           = s_extRomDir.c_str();
        s_extGameInfo.name          = s_extRomName.c_str();
        s_extGameInfo.ext           = s_extRomExt.c_str();
        s_extGameInfo.meta          = nullptr;
        s_extGameInfo.data          = nullptr;  // ← CD games: no in-memory data
        s_extGameInfo.size          = 0;
        s_extGameInfo.file_in_archive = false;
        // Mark valid=true so cb_environment's GET_GAME_INFO_EXT case returns
        // this struct (the core needs dir/name/ext to identify it as a CD game),
        // even though data is null.
        s_extGameInfoValid = true;
        LOGI("Mega-CD game detected: path=%s, dir=%s, name=%s, ext=%s — "
             "skipping pre-load (core will load cue+bin+BIOS itself)",
             path.c_str(), s_extRomDir.c_str(), s_extRomName.c_str(),
             s_extRomExt.c_str());
    }

    // Genesis-Plus-GX accepts file paths directly. For .cue / .chd / .iso
    // (Mega-CD), the path points to the cue sheet which references the bin.
    // We also provide game.data (pointing to s_extRomData) so GPGX can use
    // either path or in-memory data depending on which mode it prefers.
    //
    // === FIX: SMS/GG black-screen bug ===
    // When `genesis_plus_gx_system` is "auto" (the default), GPGX inspects the
    // file extension to pick the system type. If the temp file was renamed to
    // .md (a bug we fixed in EmulatorScreen.kt), GPGX would initialise as
    // Mega Drive, then try to fall back to SMS via header detection — leaving
    // the VDP in an inconsistent state and producing a black screen.
    //
    // As a belt-and-suspenders defence, we explicitly set the system option
    // based on the file extension we parsed above. This guarantees correct
    // system detection even if the upstream Kotlin layer ever misnames the
    // temp file again.
    if (s_extRomExt == "sms" || s_extRomExt == "sg") {
        std::lock_guard<std::mutex> lk(s_optMtx);
        s_options["genesis_plus_gx_system"] = (s_extRomExt == "sg") ? "sg" : "sms";
        s_optionsChanged.store(true, std::memory_order_release);
        LOGI("Forced genesis_plus_gx_system = %s (from ext='%s')",
             s_options["genesis_plus_gx_system"].c_str(), s_extRomExt.c_str());
    } else if (s_extRomExt == "gg") {
        std::lock_guard<std::mutex> lk(s_optMtx);
        s_options["genesis_plus_gx_system"] = "gg";
        s_optionsChanged.store(true, std::memory_order_release);
        LOGI("Forced genesis_plus_gx_system = gg (from ext='gg')");
    } else if (s_extRomExt == "md" || s_extRomExt == "bin" ||
               s_extRomExt == "smd" || s_extRomExt == "gen" ||
               s_extRomExt == "68k") {
        std::lock_guard<std::mutex> lk(s_optMtx);
        s_options["genesis_plus_gx_system"] = "md";
        s_optionsChanged.store(true, std::memory_order_release);
        LOGI("Forced genesis_plus_gx_system = md (from ext='%s')",
             s_extRomExt.c_str());
    } else {
        std::lock_guard<std::mutex> lk(s_optMtx);
        s_options["genesis_plus_gx_system"] = "auto";
        s_optionsChanged.store(true, std::memory_order_release);
    }

    retro_game_info gameInfo{};
    gameInfo.path = path.c_str();
    // For Mega-CD games, s_extGameInfoValid is true (ext info filled for
    // GET_GAME_INFO_EXT) but s_extRomData is empty (no pre-load). In that case
    // gameInfo.data MUST be nullptr so the core uses gameInfo.path instead of
    // trying to parse the (non-existent) in-memory buffer as CD image data.
    bool hasInMemoryData = s_extGameInfoValid && !s_extRomData.empty();
    gameInfo.data = hasInMemoryData ? s_extRomData.data() : nullptr;
    gameInfo.size = hasInMemoryData ? s_extRomData.size() : 0;
    gameInfo.meta = nullptr;

    LOGI("About to call retro_load_game for: %s (systemDir=%s, extInfoValid=%d, "
         "data=%p, size=%zu, hasInMemoryData=%d)",
         path.c_str(), s_systemDir.c_str(), s_extGameInfoValid ? 1 : 0,
         gameInfo.data, gameInfo.size, hasInMemoryData ? 1 : 0);

    bool ok = s_retro_load_game(&gameInfo);
    if (!ok) {
        // Fallback: read ROM into memory (used for content:// URI temp files
        // when the core needs data + size rather than just a path).
        // If we already pre-loaded above, s_extRomData is set — try with
        // a fresh gameInfo pointing to it (sometimes the first call fails
        // because GPGX's GET_GAME_INFO_EXT was queried before s_extRomData
        // was assigned, but on subsequent calls within the same load_game
        // invocation that's not possible — so this fallback is mainly for
        // the case where pre-load failed).
        if (!s_extGameInfoValid) {
            FILE* fp = fopen(path.c_str(), "rb");
            if (fp) {
                fseek(fp, 0, SEEK_END);
                long sz = ftell(fp);
                fseek(fp, 0, SEEK_SET);
                if (sz > 0 && sz < 64 * 1024 * 1024) {
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
        } else {
            LOGE("retro_load_game failed even with in-memory buffer — "
                 "GPGX rejected the ROM (unsupported mapper or corrupt file)");
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
        // For Mega-CD games, suggest checking BIOS files.
        std::string ext;
        size_t dot = path.find_last_of('.');
        if (dot != std::string::npos) ext = path.substr(dot + 1);
        std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
        if (ext == "cue" || ext == "chd" || ext == "iso") {
            s_coreError += "\nFor Mega-CD games, ensure BIOS files are "
                            "in the system directory: bios_CD_E.zip (EU), "
                            "bios_CD_J.zip (JP), bios_CD_U.zip (US).";
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

    // Set up dual controller ports (default = JOYPAD = 3-button SEGA pad;
    // user can switch to 6-button via core option "genesis_plus_gx_input").
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
        if (s_frameW == 0) s_frameW = 320;
        if (s_frameH == 0) s_frameH = 224;
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

    LOGI("SEGA ROM loaded: %s  rate=%d  fps=%.2f  region=%d  geom=%ux%u  max=%ux%u",
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
    s_videoW = 320;
    s_videoH = 224;
    s_lastRomPath.clear();
    s_saveName.clear();
    s_pad1.store(0, std::memory_order_relaxed);
    s_pad2.store(0, std::memory_order_relaxed);

    // Clear extended game info so a stale buffer is never returned to the
    // core after unload (would cause use-after-free if GPGX queries
    // GET_GAME_INFO_EXT for the next game before we fill it again).
    s_extRomData.clear();
    s_extRomData.shrink_to_fit();
    s_extRomDir.clear();
    s_extRomName.clear();
    s_extRomExt.clear();
    std::memset(&s_extGameInfo, 0, sizeof(s_extGameInfo));
    s_extGameInfoValid = false;

    // ★ 与 fbneo_loader 同款"关闭后再打开闪退"预防性修复：dlclose 核心
    // 库，下次 loadFromFile 重新 dlopen —— 核心静态状态完全归零，进程内
    // 二次 retro_init/retro_load_game 不再复用旧静态。dlclose 前模拟/
    // 音频线程已被 Kotlin 侧 join 完毕。
    if (s_coreLib) {
        LOGI("dlclose(libgenesis_plus_gx_libretro_android.so) — full core state reset");
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
    // MD/SMS: 4:3 (square pixels for 320x240, slightly stretched for 320x224).
    // GG: 4:3 (160x144 → 4:3 on cabinet), but we keep 4:3 here as default.
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

} // namespace genesicore::rom
