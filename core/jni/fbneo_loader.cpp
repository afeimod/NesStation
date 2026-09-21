// SPDX-License-Identifier: MIT
// libretro frontend that drives the prebuilt FBNeo arcade core.
//
// This loader follows the same dlopen() pattern as dos_loader.cpp:
// instead of statically linking the FBNeo source tree (which is huge and
// has no libretro port in the upstream snapshot we ship), we dlopen()
// the prebuilt libfbneo_libretro_android.so at runtime and resolve the
// retro_* symbols via dlsym(). The .so file ships in
// app/src/main/jniLibs/<abi>/.
//
// The libretro API surface we resolve is identical to dos_loader.cpp:
//   retro_init, retro_deinit, retro_load_game, retro_unload_game, retro_run,
//   retro_reset, retro_get_system_info, retro_get_system_av_info,
//   retro_set_environment, retro_set_video_refresh, retro_set_audio_sample,
//   retro_set_audio_sample_batch, retro_set_input_poll, retro_set_input_state,
//   retro_set_controller_port_device, retro_serialize_size, retro_serialize,
//   retro_unserialize, retro_get_memory_size, retro_get_memory_data
//
// Video resolution is dynamic (arcade boards use various resolutions:
// 224x256 vertical, 256x224 horizontal, 320x240, 384x224, 512x448 for
// hi-res CPS2 / NeoGeo). The frame buffer uses a std::vector that resizes
// to the largest seen resolution. Filter buffers are sized to 512x512 max.
//
// Audio: FBNeo typically outputs at 44100 Hz or 48000 Hz; the resampler
// converts to Android's 48000 Hz.
//
// Input: 6-button arcade gamepad (RETRO_DEVICE_JOYPAD) on port 0, plus
// dual controller support on port 1. FBNeo's libretro port maps the
// standard JOYPAD buttons to arcade button labels:
//   A=Button1, B=Button2, X=Button3, Y=Button4, L=Button5, R=Button6,
//   Select=Coin, Start=Start.
//
// All retro_* calls happen on a single emulation thread (see FbNeoEngine
// in Kotlin), so no extra internal locking is needed around the core
// itself. The frame and audio buffers are mutex-guarded because the UI /
// AudioTrack threads read them concurrently.

#include "fbneo_loader.h"
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

#define TAG "fbneocore-rom"
#undef LOGI
#undef LOGW
#undef LOGE
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace fbneocore::rom {

// ---------------------------------------------------------------------------
// Maximum video resolution supported by FBNeo.
// Arcade boards vary widely — common cases:
//   224x256 (vertical shooters), 256x224 (most horizontal boards),
//   320x240 (mid-90s boards), 384x224 (CPS2 wider mode), 496x384 (Puzzloop),
//   512x448 (CPS2 interlaced hi-res).
// 512x512 covers every known FBNeo-supported board with margin to spare.
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

// Dynamic frame buffer (ARGB, 0xAARRGGBB).
static std::mutex s_frameMtx;
static std::vector<uint32_t> s_frame;
static unsigned s_frameW = 0;
static unsigned s_frameH = 0;
static std::atomic<bool> s_newFrame{false};

static unsigned s_videoW = 320;
static unsigned s_videoH = 240;
static unsigned s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;

// Gamepad bits (port 0 / port 1, RETRO_DEVICE_JOYPAD).
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

// ---------------------------------------------------------------------------
// Initialize FBNeo core options with sensible defaults.
// Keys MUST match FBNeo's libretro_core_options.h exactly.
// Wrong keys cause the core to ignore settings and use its own defaults.
// ---------------------------------------------------------------------------
static void initDefaultOptions() {
    // --- Video / Aspect ---
    s_options["fbneo-vertical-mode"]               = "disabled";
    s_options["fbneo-rotate-mode"]                 = "norotate";
    s_options["fbneo-aspect"]                      = "auto";  // auto | 4:3 | 3:4 | 16:9 | 16:15
    s_options["fbneo-crop-overscan"]               = "enabled";

    // --- Performance ---
    s_options["fbneo-cpu-speed"]                   = "100";   // 100 | 75 | 50 | 150 | 200 | 250
    s_options["fbneo-cpu-frameskip"]               = "0";     // 0..10
    s_options["fbneo-force-60hz"]                  = "disabled";

    // --- Audio ---
    s_options["fbneo-samplerate"]                  = "48000";
    s_options["fbneo-audio-quality"]               = "2";     // 1=low, 2=high
    s_options["fbneo-audio-interpolation"]         = "2";     // 0=off,1=nearest,2=linear,3=cubic
    s_options["fbneo-lowpass"]                     = "disabled";
    s_options["fbneo-lowpass-range"]               = "60";    // 0..100

    // --- Input ---
    // 6-button arcade layout: A/B/X/Y/L/R + Select(Coin) + Start.
    s_options["fbneo-dipswitch-A"]                 = "";
    s_options["fbneo-dipswitch-B"]                 = "";
    s_options["fbneo-neogeo-mode"]                 = "MVS";   // MVS | AES
    s_options["fbneo-memcard-mode"]                = "enabled";

    // --- Diagnostics ---
    s_options["fbneo-debug-text"]                  = "disabled";
    s_options["fbneo-show-debug-info"]             = "disabled";

    // --- NeoGeo / PGM BIOS selection (default: unibios for NeoGeo if available) ---
    // FBNeo auto-detects BIOS by filename in the system directory.
    // Common BIOS files FBNeo looks for (place in <systemDir>/):
    //   neogeo.zip       — NeoGeo BIOS (required for all NeoGeo games)
    //   pgm.zip          — PolyGame Master BIOS (required for all PGM games:
    //                      Knights of Valour, Demon Front, Espgaluda, etc.)
    //   cvs2.zip         — Capcom VS SNK 2 decryption key
    //   neogeo_sp1.bin   — NeoGeo CD BIOS (rare, used by some homebrew)
    //   asia-aes.bin     — NeoGeo AES BIOS
    //   mvs-ax7.bin      — NeoGeo MVS BIOS
    // These are auto-discovered by filename — no core option needed.
}

// ---------------------------------------------------------------------------
// dlopen the core .so and resolve all retro_* symbols.
// ---------------------------------------------------------------------------
static bool loadCoreLib() {
    if (s_coreLib) return true;

    std::vector<std::string> candidates;
    if (!s_coreLibPath.empty()) candidates.push_back(s_coreLibPath);
    candidates.push_back("libfbneo_libretro_android.so");

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
        s_coreError = "dlopen(libfbneo_libretro_android.so) failed: ";
        s_coreError += (lastDlError ? lastDlError : "(unknown)");
        LOGE("%s", s_coreError.c_str());
        return false;
    }

    LOGI("dlopen(libfbneo_libretro_android.so) OK");

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

    // Optional — FBNeo exposes these for NVRAM (high-score) persistence.
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
    __android_log_vprint(prio, "fbneo", fmt, ap);
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

    // Fast-forward frame skip — same pattern as snes_loader.
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
    // FBNeo supports up to 4 players: port 0-3 (pad 1-4).
    const uint16_t bits = (port == 0) ? s_pad1.load(std::memory_order_relaxed)
                         : (port == 1) ? s_pad2.load(std::memory_order_relaxed)
                         : (port == 2) ? s_pad3.load(std::memory_order_relaxed)
                         : (port == 3) ? s_pad4.load(std::memory_order_relaxed)
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
        return s_coreError.empty() ? "Failed to load libfbneo_libretro_android.so" : s_coreError;
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
        LOGI("FBNeo core initialized (API version %u)", s_retro_api_version());
    }

    if (s_gameLoaded) {
        // Persist NVRAM (high-score) BEFORE unloading the previous game.
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

    // FBNeo loads ROMs by path (it has its own zip/7z VFS). The path passed
    // is the .zip / .7z archive containing the ROM set.
    retro_game_info gameInfo{};
    gameInfo.path = path.c_str();
    gameInfo.data = nullptr;
    gameInfo.size = 0;
    gameInfo.meta = nullptr;

    bool ok = s_retro_load_game(&gameInfo);
    if (!ok) {
        // Fallback: read the file into memory and pass data + size.
        // FBNeo normally prefers path-based loading (so it can find BIOS
        // zips in the same directory), but for content:// URIs that have
        // been copied to a temp file, the path is already a real filesystem
        // path — this fallback is rarely hit but kept for safety.
        FILE* fp = fopen(path.c_str(), "rb");
        if (fp) {
            fseek(fp, 0, SEEK_END);
            long sz = ftell(fp);
            fseek(fp, 0, SEEK_SET);
            if (sz > 0 && sz < 256 * 1024 * 1024) {
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
        // Provide a detailed Chinese explanation of common FBNeo load failures.
        // FBNeo rejects ROMs for several reasons:
        //   1. Missing BIOS (neogeo.zip / pgm.zip) — most common for NeoGeo/PGM
        //   2. Missing parent ROM (clone/test versions need parent)
        //   3. CRC mismatch (modified or corrupted ROM)
        //   4. Unknown romset (ROM from incompatible FBNeo/MAME version)
        s_coreError += "\n\n常见原因:\n";
        s_coreError += "  1. BIOS 缺失: NeoGeo游戏需要 neogeo.zip, PGM游戏需要 pgm.zip "
                        "(Settings → 街机 → BIOS 管理).\n";
        s_coreError += "  2. 父 ROM 缺失: 克隆版/测试版/改版需要父 ROM 同时存在 "
                        "(如 kof97t.zip 需要 kof97.zip).\n";
        s_coreError += "  3. CRC 校验失败: ROM 被修改或损坏, 请重新下载完整 ROM 集.\n";
        s_coreError += "  4. ROM 版本不匹配: ROM 集版本与本 FBNeo 核心不兼容.\n";
        s_coreError += "\n详细帮助请查看: Settings → 街机 → ROM 兼容性帮助";
        LOGE("%s", s_coreError.c_str());
        return s_coreError;
    }

    s_gameLoaded = true;
    s_lastRomPath = path;

    // Load NVRAM (high-score) from disk into the core's SAVE_RAM region.
    if (s_retro_get_memory_data && s_retro_get_memory_size) {
        void* nvram = s_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
        size_t nvramSize = s_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
        coreshared::loadSramFromDisk(nvram, nvramSize, s_saveDir, path, s_saveName);
    }

    // Set up dual controller ports
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

    LOGI("Arcade ROM loaded: %s  rate=%d  fps=%.2f  region=%d  geom=%ux%u  max=%ux%u",
         path.c_str(), s_sampleRate, av.timing.fps, s_region,
         av.geometry.base_width, av.geometry.base_height,
         av.geometry.max_width, av.geometry.max_height);
    return "";
}

void unload() {
    if (s_loaded) {
        if (s_gameLoaded) {
            // Persist NVRAM (high-score) BEFORE unloading.
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
    s_videoH = 240;
    s_lastRomPath.clear();
    s_saveName.clear();
    s_pad1.store(0, std::memory_order_relaxed);
    s_pad2.store(0, std::memory_order_relaxed);
    s_pad3.store(0, std::memory_order_relaxed);
    s_pad4.store(0, std::memory_order_relaxed);

    // ★ 街机关闭后再打开闪退修复：dlclose 核心库。
    // FBNeo 核心内部有大量静态状态（驱动表、ROM 缓存指针、内存池、
    // 一次性的全局初始化标志）。此前 unload() 只调 retro_deinit 不
    // dlclose —— 进程内第二次 retro_init + retro_load_game 复用旧的
    // 静态状态时核心会崩溃（"关闭街机游戏后再打开街机游戏必闪退"，
    // 且换别的街机 ROM 也一样崩）。与 RetroArch 关闭内容时的生命周期
    // 对齐：retro_deinit 之后立即 dlclose，下次 loadFromFile 里的
    // loadCoreLib() 会重新 dlopen —— 核心所有静态回到首次启动的干净
    // 状态。dlclose 前模拟/音频线程已被 Kotlin 侧 cleanup() join 完毕，
    // 不存在核心代码仍在执行的窗口。
    if (s_coreLib) {
        LOGI("dlclose(libfbneo_libretro_android.so) — full core state reset");
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
    else if (port == 2) s_pad3.store(bits, std::memory_order_relaxed);
    else if (port == 3) s_pad4.store(bits, std::memory_order_relaxed);
}

void setPaths(const std::string& systemDir, const std::string& saveDir) {
    s_systemDir = systemDir;
    s_saveDir = saveDir;
}

void setSaveName(const std::string& name) {
    s_saveName = name;
    LOGI("NVRAM save name set: '%s'", name.c_str());
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
    // FBNeo default aspect is auto (matches arcade cabinet orientation).
    // Vertical games (e.g. DonPachi) are 3:4, horizontal are 4:3.
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

} // namespace fbneocore::rom
