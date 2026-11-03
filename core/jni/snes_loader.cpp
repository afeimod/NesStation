// SPDX-License-Identifier: MIT
// libretro frontend that drives the SNES9x core.
//
// Features:
//   * Hardware-accelerated rendering via ANativeWindow (SurfaceView)
//   * Core options variable system (aspect, overclock, layers, audio, etc.)
//   * Dynamically-sized ARGB frame buffer (SNES resolution varies: 256x224
//     standard, up to 512x478 in high-resolution/interlaced modes)
//   * Stereo audio ring buffer (shared implementation from core_shared.h)
//   * Dual controller support (12-button SNES pad: A B X Y L R Select Start
//     Up Down Left Right)
//   * Save-state serialization
//
// All retro_* calls happen on a single emulation thread (see SnesEngine in
// Kotlin), so no extra internal locking is needed around the core itself.
// The frame and audio buffers are mutex-guarded because the UI / AudioTrack
// threads read them concurrently.
//
// Shared blit / XBR / HQX / audio ring buffer / surface management code is
// provided by core_shared.h — this file does NOT reimplement those utilities.

#include "snes_loader.h"
#include "shared/core_shared.h"

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

#define TAG "snescore-rom"
// core_shared.h already defines LOGI/LOGW/LOGE with tag "core-shared".
// Redefine them with our specific tag for better log filtering.
#undef LOGI
#undef LOGW
#undef LOGE
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace snescore::rom {

// SNES internal resolution.
//   Standard:  256x224 (NTSC) / 256x239 (PAL)
//   High-res:  512x224, 512x448 (interlaced)
//   Max:       512x478 (highest reported by SNES9x libretro core)
static constexpr int kSnesW    = 256;   // standard width
static constexpr int kSnesH    = 224;   // standard height
static constexpr int kSnesMaxW = 512;   // max width  (high-res mode)
static constexpr int kSnesMaxH = 478;   // max height (interlaced high-res)

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
static bool s_loaded = false;
static int  s_sampleRate = 0;
static int  s_region = 0;
static std::string s_systemDir;
static std::string s_saveDir;
static std::string s_coreMessage;  // last message from the core
static std::string s_lastRomPath;  // last successfully loaded ROM path (for SRAM save)
static std::string s_saveName;     // explicit .srm basename (set by frontend for content:// URI games)

// Pixel format requested by the core via SET_PIXEL_FORMAT.
// SNES9x typically requests XRGB8888, but we handle all formats for safety.
static unsigned s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;

// Frame buffer (ARGB, 0xAARRGGBB). Written by video_cb, read by
// copyFramebufferARGB. Also used as the source for ANativeWindow blitting.
// SNES resolution is variable, so we use a dynamically-sized vector rather
// than a fixed array. s_frameW / s_frameH track the current dimensions.
static std::mutex s_frameMtx;
static std::vector<uint32_t> s_frame;
static int s_frameW = 0;
static int s_frameH = 0;
static std::atomic<bool> s_newFrame{false};

// Current video dimensions from the core (may change with high-res modes).
static unsigned s_videoW = kSnesW;
static unsigned s_videoH = kSnesH;

// Controller bits (12 buttons per pad — see setControllerInput layout).
// SNES supports dual controllers: port 0 (pad 1) and port 1 (pad 2).
static std::atomic<uint16_t> s_pad1{0};
static std::atomic<uint16_t> s_pad2{0};

// Video filter type:
//   0=none, 1=scanline, 2=crt, 3=dot, 4=xbr, 5=hq2x, 6=hq4x, 7=xbr+dot,
//   8=4xbr, 9=4xbr+dot, 10=hq4x+dot
static std::atomic<int> s_videoFilter{0};

// When true, blitToSurface uses display-resolution buffer (sharp, heavy CPU).
// When false, uses source-resolution buffer (fast, GPU upscales).
static std::atomic<bool> s_highQualityScaling{false};

// 2x upscale buffer for XBR/HQ2X (max 512x478 -> 1024x956)
static uint32_t s_xbrBuffer2x[kSnesMaxW * kSnesMaxH * 2 * 2];

// 4x upscale buffer for HQ4X/4xBR (max 512x478 -> 2048x1912)
static uint32_t s_xbrBuffer4x[kSnesMaxW * kSnesMaxH * 4 * 4];

// Intermediate buffer for 4xBR cascade pass 1 (max 512x478 -> 1024x956)
static uint32_t s_xbrMidBuffer[kSnesMaxW * kSnesMaxH * 2 * 2];

// Audio ring buffer: interleaved stereo int16 samples (shared implementation).
static coreshared::AudioRingBuffer s_audio;

// Streaming audio resampler: converts from the SNES native sample rate
// (~32040 Hz for SNES9x) to Android's 48000 Hz native rate.
//
// Without this resampler, AudioTrack is created at ~32040 Hz and Android's
// AudioFlinger performs low-quality resampling to 48000 Hz internally.
// On phones the resampling artifact is mild (the phone's audio HAL may
// even accept 32040 Hz natively), but on TV boxes with HDMI output the
// native rate is always 48000 Hz, so AudioFlinger's 32040→48000
// resampling produces audible buzzing, crackling, and muffled audio.
//
// Doing the resampling in our own native code with a linear interpolator
// eliminates these artifacts. This matches the GB/GBC/GBA core (mGBA),
// which already resamples to 48000 Hz and works correctly on TV.
static coreshared::AudioResampler s_resampler;

// ---------------------------------------------------------------------------
// ANativeWindow — hardware-accelerated direct surface rendering
// ---------------------------------------------------------------------------
static ANativeWindow* s_window = nullptr;
static std::mutex s_windowMtx;

// Fast-forward: when true, skip most surface blits to prevent
// ANativeWindow_lock from blocking the emulation thread.
static std::atomic<bool> s_fastForward{false};
static std::atomic<int>  s_ffFrameSkip{0};
static std::atomic<int>  s_ffMaxSkip{6};

// ---------------------------------------------------------------------------
// Core options — key/value map served to the core via GET_VARIABLE
// ---------------------------------------------------------------------------
static std::mutex s_optMtx;
static std::map<std::string, std::string> s_options;
static std::atomic<bool> s_optionsChanged{false};

// Initialize SNES9x core options with sensible defaults.
// Keys MUST match SNES9x's libretro_core_options.h exactly.
// Wrong keys cause the core to ignore settings and use its own defaults,
// which can break text rendering in RPGs like Fire Emblem (字体花屏).
static void initDefaultOptions() {
    // --- Video / Aspect ---
    s_options["snes9x_aspect"]                = "4:3";
    s_options["snes9x_overscan"]              = "enabled";

    // --- Overclock / Hacks ---
    s_options["snes9x_overclock"]             = "100%";
    s_options["snes9x_overclock_cycles"]      = "disabled";
    s_options["snes9x_reduce_sprite_flicker"] = "disabled";
    s_options["snes9x_randomize_memory"]      = "disabled";

    // --- Video Processing ---
    s_options["snes9x_hires_blend"]           = "disabled";
    s_options["snes9x_blargg"]                = "disabled";

    // --- Audio ---
    s_options["snes9x_audio_interpolation"]   = "gaussian";
    s_options["snes9x_sndchan_1"]             = "enabled";
    s_options["snes9x_sndchan_2"]             = "enabled";
    s_options["snes9x_sndchan_3"]             = "enabled";
    s_options["snes9x_sndchan_4"]             = "enabled";
    s_options["snes9x_sndchan_5"]             = "enabled";
    s_options["snes9x_sndchan_6"]             = "enabled";
    s_options["snes9x_sndchan_7"]             = "enabled";
    s_options["snes9x_sndchan_8"]             = "enabled";

    // --- Input ---
    s_options["snes9x_up_down_allowed"]       = "disabled";

    // --- Layers (all enabled by default) ---
    s_options["snes9x_layer_1"]               = "enabled";
    s_options["snes9x_layer_2"]               = "enabled";
    s_options["snes9x_layer_3"]               = "enabled";
    s_options["snes9x_layer_4"]               = "enabled";
    s_options["snes9x_layer_5"]               = "enabled";

    // --- Graphics (CRITICAL for text/font rendering) ---
    // snes9x_gfx_clip: Enable graphic clip windows. WITHOUT this, RPGs like
    //   Fire Emblem that use window clipping for text boxes/dialog will have
    //   garbled or invisible text (字体花屏).
    // snes9x_gfx_transp: Enable transparency effects. Text boxes use
    //   transparency; disabling causes rendering artifacts.
    // snes9x_gfx_hires: Enable hires mode. Some games use hi-res for text.
    s_options["snes9x_gfx_clip"]              = "enabled";
    s_options["snes9x_gfx_transp"]            = "enabled";
    s_options["snes9x_gfx_hires"]             = "enabled";

    // --- VRAM / Compatibility ---
    // Allow invalid VRAM access by default (block_invalid_vram_access = "disabled").
    // Some games (e.g. Fire Emblem) write to VRAM in non-standard ways that
    // SNES9x considers "invalid". Blocking these writes prevents the game from
    // writing text/font tiles correctly, causing garbled text (字体花屏).
    // Allowing invalid access lets these games render text properly.
    s_options["snes9x_block_invalid_vram_access"] = "disabled";
    s_options["snes9x_echo_buffer_hack"]      = "disabled";

    // --- Advanced AV Settings ---
    // Must be "enabled" so the core processes gfx_clip, gfx_transp, layers, etc.
    // Without this, the core may skip reading these options in some code paths.
    s_options["snes9x_show_advanced_av_settings"] = "enabled";

    // --- Region ---
    s_options["snes9x_region"]                = "auto";

    // --- Lightgun crosshairs (defaults) ---
    s_options["snes9x_superscope_crosshair"]  = "2";
    s_options["snes9x_justifier1_crosshair"]  = "4";
    s_options["snes9x_justifier2_crosshair"]  = "4";
    s_options["snes9x_rifle_crosshair"]       = "2";
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
    __android_log_vprint(prio, "snes9x", fmt, ap);
    va_end(ap);
}

static bool cb_environment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            if (data) *static_cast<bool*>(data) = true;
            return true;

        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            // Accept the core's pixel format request and store it for
            // conversion in cb_video. SNES9x typically requests RGB565.
            // We always convert to ARGB in cb_video and blit via RGBA_8888
            // surface — never change the surface format here, as the
            // SurfaceView was created with RGBX_8888 and changing it
            // mid-stream causes garbled output.
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

        // The core registers these; we accept.
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
#ifdef RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE
        case RETRO_ENVIRONMENT_SET_CONTENT_INFO_OVERRIDE:
#endif
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            return true;

        // --- Core Options API (v1) ---
        // SNES9x uses the modern SET_CORE_OPTIONS API. We must handle
        // GET_CORE_OPTIONS_VERSION to tell the core we support v1, otherwise
        // it falls back to the legacy SET_VARIABLES path which may not
        // properly register all options. Returning version 1 ensures the
        // core uses SET_CORE_OPTIONS / SET_CORE_OPTIONS_INTL to register
        // its options, and then queries GET_VARIABLE for current values.
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            if (data) *static_cast<unsigned*>(data) = 1;
            return true;

        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
            // Accept the core's option definitions. We don't need to parse
            // them since we serve values via GET_VARIABLE from s_options.
            return true;

        // SNES9x sends SET_GEOMETRY when the video aspect ratio or base
        // dimensions change (e.g., switching between standard and hi-res).
        // Accept it so the core knows we handle geometry changes.
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            return true;

        // The core may send SET_SYSTEM_AV_INFO to change timing/dimensions.
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
            return true;

        // Tell the core we want both audio (bit 1) and video (bit 0) enabled.
        // Without this, the core may default to disabling rendering in some
        // code paths, causing blank/garbled output.
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            if (data) *static_cast<int*>(data) = 3; // audio + video
            return true;

        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            // Capture core messages for error reporting
            if (data) {
                auto* msg = static_cast<const retro_message*>(data);
                if (msg && msg->msg) {
                    s_coreMessage = msg->msg;
                    LOGE("Core message: %s", msg->msg);
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

        // SNES9x uses filestream_open() which falls back to the built-in VFS
        // implementation when no VFS interface is provided. This fallback
        // works correctly on Android for standard filesystem paths.
        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
            return false;

        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return false; // we answer per-button input_state queries

        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
            return false;

        // SNES9x sends SET_CORE_OPTIONS_DISPLAY to show/hide options in the UI.
        // We don't have a UI options browser, so just accept and ignore.
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
            return true;

        default:
            return false;
    }
}

static void cb_video(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return; // duplicate frame / no data this frame

    s_videoW = width;
    s_videoH = height;

    // Convert source to ARGB and store in s_frame (for fallback rendering,
    // screenshots, and filter upscaling). Also used for direct surface blit
    // when the pixel format is not RGB565.
    {
        std::lock_guard<std::mutex> lk(s_frameMtx);

        const size_t need = (size_t)width * height;
        if ((int)width != s_frameW || (int)height != s_frameH || s_frame.size() < need) {
            s_frameW = (int)width;
            s_frameH = (int)height;
            s_frame.resize(need);
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
            // RGB565: rrrrrggggggbbbbb -> ARGB 0xFFRRGGBB
            // Use proper bit expansion: (v << 3) | (v >> 2) for 5-bit,
            // (v << 2) | (v >> 4) for 6-bit. This maps 0-31 to 0-255 and
            // 0-63 to 0-255 correctly (vs simple << 3 which only reaches 248).
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
            // 0RGB1555: 0rrrrrgggggbbbbb -> ARGB 0xFFRRGGBB
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

    // During fast-forward, skip most blits to prevent ANativeWindow_lock
    // from blocking the emulation thread. Only blit every 6th frame so the
    // user can still see the game. ARGB conversion above still runs every
    // frame (for screenshots).
    if (s_fastForward.load(std::memory_order_relaxed)) {
        int skip = s_ffMaxSkip.load(std::memory_order_relaxed);
        if (skip > 0 && s_ffFrameSkip.fetch_add(1, std::memory_order_relaxed) % skip != 0)
            return;
    } else {
        s_ffFrameSkip.store(0, std::memory_order_relaxed);
    }

    const int filter = s_videoFilter.load(std::memory_order_relaxed);

    // Always use the ARGB blit path. The cb_video function above already
    // converted the core's native pixel format (RGB565/XRGB8888/0RGB1555)
    // to ARGB 0xFFRRGGBB in s_frame. The surface is always RGBA_8888, so
    // the ARGB blit path handles all formats correctly without any surface
    // format mismatch issues.
    coreshared::applyFilterAndBlit(
        s_window, s_windowMtx,
        s_frame.data(), width, height, width,
        filter,
        s_xbrBuffer2x, s_xbrBuffer4x, s_xbrMidBuffer,
        (unsigned)kSnesMaxW, (unsigned)kSnesMaxH,
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
    // SNES supports dual controllers: port 0 (pad 1) and port 1 (pad 2).
    const uint16_t bits = (port == 0) ? s_pad1.load(std::memory_order_relaxed)
                                      : (port == 1) ? s_pad2.load(std::memory_order_relaxed)
                                                    : 0;
    // Controller bit layout (12 buttons):
    //   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down,
    //   bit6=Left, bit7=Right, bit8=X, bit9=Y, bit10=L, bit11=R
    switch (id) {
        case RETRO_DEVICE_ID_JOYPAD_A:      return (bits >> 0)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_B:      return (bits >> 1)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_SELECT: return (bits >> 2)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_START:  return (bits >> 3)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_UP:     return (bits >> 4)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_DOWN:   return (bits >> 5)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_LEFT:   return (bits >> 6)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_RIGHT:  return (bits >> 7)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_X:      return (bits >> 8)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_Y:      return (bits >> 9)  & 1;
        case RETRO_DEVICE_ID_JOYPAD_L:      return (bits >> 10) & 1;
        case RETRO_DEVICE_ID_JOYPAD_R:      return (bits >> 11) & 1;
        default: return 0;
    }
}

// ---------------------------------------------------------------------------
// Interface implementation
// ---------------------------------------------------------------------------
std::string loadFromFile(const std::string& path, int& regionOut) {
    if (s_loaded) unload();

    // Initialize core options before the core starts.
    // Always call initDefaultOptions() to ensure ALL keys exist, but don't
    // overwrite values already set by the user via setCoreOption().
    {
        std::lock_guard<std::mutex> lk(s_optMtx);
        // Save current user-set values
        auto saved = s_options;
        initDefaultOptions();
        // Restore user-set values on top of defaults
        for (auto& [k, v] : saved) {
            s_options[k] = v;
        }
    }
    s_optionsChanged.store(true, std::memory_order_release);

    // Reset pixel format to libretro default before init.
    s_pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;

    // CRITICAL: retro_set_environment MUST be called before retro_init().
    // SNES9x's retro_init() calls the environment callback (e.g. to get the
    // log interface). If environ_cb is still NULL, it dereferences pc 0x0.
    retro_set_environment(cb_environment);
    retro_init();
    retro_set_video_refresh(cb_video);
    retro_set_audio_sample(cb_audio_sample);
    retro_set_audio_sample_batch(cb_audio_batch);
    retro_set_input_poll(cb_input_poll);
    retro_set_input_state(cb_input_state);

    // Provide BOTH path and data so the core can use whichever mode it prefers.
    struct retro_game_info game;
    std::memset(&game, 0, sizeof(game));
    std::vector<uint8_t> romData;

    {
        FILE* f = std::fopen(path.c_str(), "rb");
        if (!f) { retro_deinit(); return "Cannot open ROM file: " + path; }
        std::fseek(f, 0, SEEK_END);
        long sz = std::ftell(f);
        std::fseek(f, 0, SEEK_SET);
        if (sz <= 0) { std::fclose(f); retro_deinit(); return "Empty ROM file"; }
        romData.resize((size_t)sz);
        size_t rd = std::fread(romData.data(), 1, (size_t)sz, f);
        std::fclose(f);
        if (rd != (size_t)sz) { retro_deinit(); return "Cannot read ROM file"; }
        game.path = path.c_str();
        game.data = romData.data();
        game.size = romData.size();
    }

    LOGI("About to call retro_load_game for: %s (systemDir=%s)",
         path.c_str(), s_systemDir.c_str());

    if (!retro_load_game(&game)) {
        retro_unload_game();
        retro_deinit();
        LOGE("retro_load_game FAILED for: %s", path.c_str());
        LOGE("Core message: %s", s_coreMessage.c_str());
        // Return the core's own error message if available
        if (!s_coreMessage.empty()) {
            std::string err = s_coreMessage;
            s_coreMessage.clear();
            return err;
        }
        return "SNES9x rejected the ROM (unsupported or corrupt file)";
    }
    s_loaded = true;
    LOGI("retro_load_game SUCCEEDED for: %s", path.c_str());

    // Load battery-backed cartridge SRAM from disk into the core's SAVE_RAM
    // region. The libretro API requires the frontend to do this — the SNES9x
    // core's retro_unload_game() is empty (it does NOT auto-save SRAM).
    // For SNES, RETRO_MEMORY_SAVE_RAM points to Memory.SRAM which holds the
    // cartridge's battery-backed RAM (used for in-game saves).
    s_lastRomPath = path;
    {
        void* sram = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
        size_t sramSize = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
        coreshared::loadSramFromDisk(sram, sramSize, s_saveDir, path, s_saveName);
    }

    // Set up dual controller ports
    retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    retro_set_controller_port_device(1, RETRO_DEVICE_JOYPAD);

    struct retro_system_av_info av;
    retro_get_system_av_info(&av);
    s_sampleRate = (int)av.timing.sample_rate;
    s_region = (av.timing.fps < 55.0) ? 1 : 0;
    regionOut = s_region;
    s_videoW = av.geometry.base_width;
    s_videoH = av.geometry.base_height;

    // Initialize frame buffer to the core's base geometry
    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        s_frameW = (int)av.geometry.base_width;
        s_frameH = (int)av.geometry.base_height;
        if (s_frameW <= 0) s_frameW = kSnesW;
        if (s_frameH <= 0) s_frameH = kSnesH;
        s_frame.assign((size_t)s_frameW * s_frameH, 0xFF000000u);
    }

    s_audio.reset();
    s_newFrame.store(false);

    // Default audio output — NO TV-mode special handling. Pure passthrough
    // (src == dst): the core's native mixer rate feeds the AudioTrack
    // directly; AudioFlinger handles any device-rate conversion itself.
    s_resampler.init(s_sampleRate, s_sampleRate);
    LOGI("Audio passthrough: %d Hz (ratio=%.6f, active=%d)",
         s_sampleRate, s_resampler.ratio, s_resampler.active ? 1 : 0);

    LOGI("ROM loaded: %s  rate=%d  fps=%.2f  region=%d  geom=%ux%u  max=%ux%u",
         path.c_str(), s_sampleRate, av.timing.fps, s_region,
         av.geometry.base_width, av.geometry.base_height,
         av.geometry.max_width, av.geometry.max_height);
    return "";
}


void cheatRamRegions(void** sysRam, size_t* sysSize,
                     void** saveRam, size_t* saveSize) {
    if (sysRam) *sysRam = nullptr;
    if (sysSize) *sysSize = 0;
    if (saveRam) *saveRam = nullptr;
    if (saveSize) *saveSize = 0;
    if (!s_loaded) return;
    if (sysRam) *sysRam = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
    if (sysSize) *sysSize = retro_get_memory_size(RETRO_MEMORY_SYSTEM_RAM);
    if (saveRam) *saveRam = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (saveSize) *saveSize = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
}

void unload() {
    if (s_loaded) {
        // Persist battery-backed cartridge SRAM to disk BEFORE unloading the
        // core — SNES9x's retro_unload_game() is empty, so without this call
        // the SRAM would be lost when the game is closed.
        if (!s_lastRomPath.empty()) {
            void* sram = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
            size_t sramSize = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
            coreshared::saveSramToDisk(sram, sramSize, s_saveDir, s_lastRomPath, s_saveName);
        }
        retro_unload_game();
        retro_deinit();
        s_loaded = false;
    }
    s_sampleRate = 0;
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
    s_videoW = kSnesW;
    s_videoH = kSnesH;
    s_lastRomPath.clear();
    s_saveName.clear();
}

void resetEmulation(bool /*hard*/) {
    if (!s_loaded) return;
    retro_reset();
}

void stepFrame() {
    if (!s_loaded) return;
    retro_run();
}

bool copyFramebufferARGB(uint32_t* out, int w, int h) {
    if (!out) return false;
    if (!s_loaded || s_frame.empty()) {
        std::memset(out, 0, (size_t)w * h * sizeof(uint32_t));
        return false;
    }
    std::lock_guard<std::mutex> lk(s_frameMtx);
    // w/h are the destination dimensions. Copy the intersection of (w,h)
    // and the actual frame size (s_frameW x s_frameH) from s_frame.
    const int cw = (w < s_frameW) ? w : s_frameW;
    const int ch = (h < s_frameH) ? h : s_frameH;
    for (int y = 0; y < ch; ++y) {
        std::memcpy(out + (size_t)y * w,
                    s_frame.data() + (size_t)y * s_frameW,
                    (size_t)cw * sizeof(uint32_t));
    }
    return s_newFrame.exchange(false, std::memory_order_acq_rel);
}

int readAudio(int16_t* out, int maxFrames) {
    // The resampler pulls source frames from s_audio and writes resampled
    // output at 48000 Hz. When srcRate == 48000 (active=false), the
    // resampler passes through directly to s_audio.read().
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

void applyRegion(int /*region*/) { /* region is auto-detected at load */ }
void applySampleRate(int /*hz*/) { /* fixed by the core */ }
void applySpeed(float multiplier) {
    s_fastForward.store(multiplier > 1.0f, std::memory_order_relaxed);
    // Frame skip: higher speed = skip more frames between renders.
    // For 2x: skip 1, render 1 (every 2nd frame)
    // For 4x: skip 3, render 1 (every 4th frame)
    // For 6x: skip 5, render 1 (every 6th frame)
    // For 8x: skip 7, render 1 (every 8th frame)
    s_ffMaxSkip.store((int)multiplier, std::memory_order_relaxed);
    s_ffFrameSkip.store(0, std::memory_order_relaxed);
}

void saveStateToPath(int /*slot*/, const std::string& path) {
    if (!s_loaded) return;
    size_t sz = retro_serialize_size();
    if (sz == 0) return;
    std::vector<uint8_t> buf(sz);
    if (!retro_serialize(buf.data(), sz)) { LOGE("retro_serialize failed"); return; }
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) { LOGE("Cannot open save state for write: %s", path.c_str()); return; }
    std::fwrite(buf.data(), 1, sz, f);
    std::fclose(f);
}

bool loadStateFromPath(int /*slot*/, const std::string& path) {
    if (!s_loaded) return false;
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
    if (!retro_unserialize(buf.data(), sz)) { LOGE("retro_unserialize failed"); return false; }
    return true;
}

// --- Hardware-accelerated rendering ---------------------------------------

void setSurface(void* nativeWindow) {
    // Always use RGBA_8888 surface format. The cb_video function converts
    // all pixel formats (RGB565/XRGB8888/0RGB1555) to ARGB 0xFFRRGGBB,
    // and blitToSurface handles RGBA_8888/RGBX_8888 surfaces correctly.
    // Never change the surface format to RGB_565 — the SurfaceView was
    // created with RGBX_8888 and changing it causes garbled output.
    coreshared::setSurface(s_window, s_windowMtx, nativeWindow);
    if (nativeWindow) {
        // Apply high-quality scaling flag: when false, blitToSurface will
        // set the buffer geometry to the source resolution per-frame.
        // When true, leave it at 0x0 (display resolution) for sharp C++ scaling.
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
            // blitToSurface will set source-res geometry per-frame
            ANativeWindow_setBuffersGeometry(s_window, s_videoW, s_videoH, WINDOW_FORMAT_RGBA_8888);
        }
    }
    LOGI("High-quality scaling: %s", enabled ? "ON" : "OFF");
}

// --- Core options ----------------------------------------------------------

void setCoreOption(const std::string& key, const std::string& value) {
    {
        std::lock_guard<std::mutex> lk(s_optMtx);
        s_options[key] = value;
    }
    s_optionsChanged.store(true, std::memory_order_release);
    LOGI("Core option set: %s = %s", key.c_str(), value.c_str());
}

// --- Video geometry --------------------------------------------------------

int videoWidth()  { return (int)s_videoW; }
int videoHeight() { return (int)s_videoH; }

void videoAspectRatio(int& num, int& den) {
    // SNES9x default aspect is 4:3 (configured via "snes9x_aspect" core option).
    // The Kotlin layer uses this to size the SurfaceView.
    num = 4;
    den = 3;
}

// --- Video filter ----------------------------------------------------------

void setVideoFilter(int filter) {
    s_videoFilter.store(filter, std::memory_order_relaxed);
    LOGI("Video filter set: %d (0=none, 1=scanline, 2=crt, 3=dot, 4=xbr, 5=hq2x, 6=hq4x, 7=xbr+dot)", filter);
}

} // namespace snescore::rom
