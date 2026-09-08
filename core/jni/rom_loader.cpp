// SPDX-License-Identifier: MIT
// NES/FC core engine driver — NostalgiaLite fceux engine.
//
// This file replaces the previous libretro-fceumm frontend. The engine
// underneath is now the fceux core shipped inside NostalgiaLite-master
// (core/fceux), the exact engine that plays the previously gray-screening
// games (e.g. 天使之翼2中文版) correctly. It is driven DIRECTLY through its
// native driver API (FCEUI_Initialize / FCEUI_LoadGame / FCEUI_Emulate ...),
// no libretro layer in between — one process-wide fceux instance, exactly
// like the reference app does it.
//
// The public surface (namespace nescore::rom, see rom_loader.h) is unchanged,
// so bridge.cpp, NesNative.kt and the whole Kotlin layer keep working without
// any modification:
//   * Hardware-accelerated rendering via ANativeWindow (SurfaceView)
//     with the shared 2xBR/4xBR/HQ filters from core_shared.h
//   * 256x240 ARGB frame buffer for fallback Bitmap rendering
//   * Stereo int16 audio ring buffer + optional resampler
//   * Controller state, save states, SRAM battery saves, FDS BIOS handling
//
// ROM loading is 1:1 with the reference engine: the file is handed to
// FCEUI_LoadGame() as-is. NO iNES header patching of any kind is performed.
//
// All FCEUI_* calls happen on the single emulation thread owned by Kotlin
// (NesEngine), same threading model as before.

#include "rom_loader.h"
#include "shared/core_shared.h"
#include "hqx/hqx.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <mutex>
#include <string>
#include <vector>

#include "fceux/driver.h"
#include "fceux/fceu.h"
#include "fceux/file.h"
#include "fceux/state.h"
#include "fceux/emufile.h"

#define TAG "nescore-rom"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace nescore::rom {

// NES internal resolution — fceux XBuf layout.
static constexpr int kNesW = 256;
static constexpr int kNesH = 240;

// Audio sample rate configured into the engine (FCEUI_Sound).
// 48000 Hz matches Android AudioTrack's native rate on TV boxes, so no
// resampling is needed anywhere in the chain. fceux has dedicated FIR
// tables for 48000 (c48000ntsc.h / c48000pal.h).
static constexpr int kSampleRate = 48000;

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
static bool s_loaded = false;
static bool s_engineInited = false;
static FCEUGI* s_game = nullptr;

static int  s_sampleRate = 0;
static int  s_region = 0;          // 0 = NTSC (regionOut to Kotlin)
static int  s_vidSystem = 0;       // fceux: 0 = NTSC, 1 = PAL (user setting)
static std::string s_lastError;    // engine error captured for load reporting

static std::string s_systemDir;
static std::string s_saveDir;
static std::string s_lastRomPath;
static std::string s_saveName;

// Controller state packed into ONE 32-bit word, exactly like the reference
// engine: fceux UpdateGP reads the gamepad int32 as four packed pads —
// bits 0-7 = port 0 (1P), bits 8-15 = port 1 (2P). Bit layout per pad is the
// standard NES order: A, B, Select, Start, Up, Down, Left, Right — identical
// to the setControllerInput() contract. fceux dereferences this pointer every
// frame on the emulation thread, so it must be atomic.
static std::atomic<uint32_t> s_pads{0};

// Palette table written by FCEUD_SetPalette (called by the engine every
// frame). Index = XBuf palette byte (8-bit, includes deemph variants),
// value = 0xAARRGGBB.
static std::mutex s_paletteMtx;
static uint32_t s_paletteTable[256];

// Frame buffer (ARGB 0xAARRGGBB, 256x240) + fresh flag. Written by
// stepFrame, read by copyFramebufferARGB (Kotlin Bitmap fallback path) and
// used as the source for ANativeWindow blitting and all post filters.
static std::mutex s_frameMtx;
static uint32_t s_frame[kNesW * kNesH];
static std::atomic<bool> s_newFrame{false};

// FCEUI_Emulate outputs (owned by the engine; valid until the next call).
static uint8_t* s_xbuf = nullptr;
static int32_t* s_sndBuf = nullptr;

// Video filter id (frontend post-processing): 0=none,1=scanline,2=crt,
// 3=dot,4=xbr,5=hq2x,6=hq4x,7=xbr+dot,8=4xbr,9=4xbr+dot,10=hq4x+dot
static std::atomic<int> s_videoFilter{0};

// High-quality (display-resolution) surface blitting flag.
static std::atomic<bool> s_highQualityScaling{false};

// Upscaler work buffers for the shared filter pipeline (2x/4x).
static uint32_t s_xbrBuffer[kNesW * 2 * kNesH * 2];
static uint32_t s_xbrMidBuffer[kNesW * 2 * kNesH * 2];
static uint32_t s_hq4xBuffer[kNesW * 4 * kNesH * 4];

// Audio ring buffer (interleaved stereo int16) + resampler, shared
// implementation from core_shared.h (same as SNES/GB/GBC/GBA cores).
static coreshared::AudioRingBuffer s_audio;
static coreshared::AudioResampler s_resampler;

// ANativeWindow for hardware-accelerated rendering.
static ANativeWindow* s_window = nullptr;
static std::mutex s_windowMtx;

// Fast-forward: skip most surface blits so ANativeWindow_lock never paces
// the emulation thread back to real time.
static std::atomic<bool> s_fastForward{false};
static std::atomic<int>  s_ffFrameSkip{0};
static std::atomic<int>  s_ffMaxSkip{6};

// ---------------------------------------------------------------------------
// Engine callbacks — palette / logging are implemented at GLOBAL scope below
// (after the namespace), because fceux declares them as plain global C++
// functions and the linker needs the exact C++-mangled symbols.
// ---------------------------------------------------------------------------

// Last engine error (FCEUD_PrintError) — surfaced as load failure message.
static std::mutex s_errMtx;
static std::string s_engineError;

static void recordEngineError(const char* s) {
    std::lock_guard<std::mutex> lk(s_errMtx);
    s_engineError = s ? s : "";
}

// ---------------------------------------------------------------------------
// Engine lifecycle helpers
// ---------------------------------------------------------------------------
static bool ensureEngine() {
    if (s_engineInited) return true;

    if (!FCEUI_Initialize()) {
        LOGE("FCEUI_Initialize failed");
        return false;
    }

    // Reference-engine settings (NostalgiaLite Nes.cpp start()):
    // volume 100, low-pass on, sound quality "low" (engine's simple path),
    // 48000 Hz output.
    FCEUI_SetSoundVolume(100);
    FCEUI_SetLowPass(1);
    FCEUI_SetSoundQuality(0);
    FCEUI_Sound(kSampleRate);

    s_engineInited = true;
    LOGI("fceux engine initialized (rate=%d)", kSampleRate);
    return true;
}

// SRAM strategy: fceux manages battery saves itself — CartInfo::SaveGame
// buffers are flushed to <BatterySaveDir>/<FileBase>.sav at FCEUI_CloseGame
// and re-read at FCEUI_LoadGame (cart.cpp FCEU_SaveGameSave/FCEU_LoadGameSave).
// We point BatterySaveDir at the app save dir, so saves land as
// <saveDir>/<rom-basename>.sav.
//
// Compatibility with saves written by the previous fceumm-based core
// (<saveDir>/<name>.srm): one-shot migration — if a .sav does not exist yet
// but the legacy .srm does, copy it over before load so the engine populates
// battery RAM from it. Never overwrites an existing (newer) .sav.
static bool copyFileIfMissing(const std::string& from, const std::string& to) {
    if (from.empty() || to.empty()) return false;
    FILE* dst = std::fopen(to.c_str(), "rb");
    if (dst) { std::fclose(dst); return false; }   // .sav already exists
    FILE* src = std::fopen(from.c_str(), "rb");
    if (!src) return false;                        // no legacy file
    std::fseek(src, 0, SEEK_END);
    long sz = std::ftell(src);
    std::fseek(src, 0, SEEK_SET);
    if (sz <= 0) { std::fclose(src); return false; }
    std::vector<uint8_t> buf((size_t)sz);
    size_t rd = std::fread(buf.data(), 1, (size_t)sz, src);
    std::fclose(src);
    if (rd != (size_t)sz) return false;
    dst = std::fopen(to.c_str(), "wb");
    if (!dst) return false;
    std::fwrite(buf.data(), 1, rd, dst);
    std::fclose(dst);
    LOGI("SRAM migration: %s -> %s (%zu bytes)", from.c_str(), to.c_str(), rd);
    return true;
}

static void migrateLegacySrm(const std::string& path) {
    // Base name of the ROM (no directory, no extension) — matches fceux's
    // FileBase used for the .sav name.
    std::string base = path;
    size_t slash = base.find_last_of('/');
    if (slash != std::string::npos) base = base.substr(slash + 1);
    size_t dot = base.find_last_of('.');
    if (dot != std::string::npos) base = base.substr(0, dot);
    if (base.empty()) return;

    std::string dir = s_saveDir;
    if (!dir.empty() && dir.back() == '/') dir.pop_back();
    if (dir.empty()) return;

    copyFileIfMissing(dir + "/" + base + ".srm", dir + "/" + base + ".sav");

    // Netplay passes an explicit stable save name (setSaveName); migrate
    // that file too so network-session saves survive the core swap.
    if (!s_saveName.empty()) {
        std::string name = s_saveName;
        dot = name.find_last_of('.');
        if (dot != std::string::npos) {
            std::string tail = name.substr(dot);
            for (auto& c : tail) c = (char)std::tolower((unsigned char)c);
            if (tail == ".srm" || tail == ".sav") name = name.substr(0, dot);
        }
        if (!name.empty() && name != base) {
            copyFileIfMissing(dir + "/" + name + ".srm", dir + "/" + name + ".sav");
        }
    }
}

// fceux stores raw char* pointers for dir overrides (FCEUI_SetDirOverride
// keeps the pointer, it does not copy). Back them with stable buffers that
// live for the process lifetime and refresh on every load.
static char s_systemDirBuf[1024];
static char s_saveDirBuf[1024];

static void applyEngineDirs() {
    std::snprintf(s_systemDirBuf, sizeof(s_systemDirBuf), "%s",
                  s_systemDir.c_str());
    std::snprintf(s_saveDirBuf, sizeof(s_saveDirBuf), "%s",
                  s_saveDir.c_str());
    if (!s_systemDir.empty()) FCEUI_SetBaseDirectory(s_systemDir);
    if (!s_saveDir.empty())   NOSTALGIA_SetBatterySaveDir(s_saveDir);
    // FDS BIOS (disksys.rom) lookup: engine default is <BaseDirectory>/
    // disksys.rom — the override makes it explicit and independent of the
    // base dir.
    FCEUI_SetDirOverride(FCEUIOD_FDSROM, s_systemDirBuf);
    // FDS side-disk save data (<FileBase>.fds): put next to the .sav files.
    FCEUI_SetDirOverride(FCEUIOD_NV, s_saveDirBuf);
}

// ---------------------------------------------------------------------------
// ROM loading
// ---------------------------------------------------------------------------
std::string loadFromFile(const std::string& path, int& regionOut) {
    if (s_loaded) unload();

    if (!ensureEngine()) {
        return "FC 引擎初始化失败 (fceux engine init failed)";
    }

    applyEngineDirs();

    // Existence check for a clean error message (fceux's own error is
    // captured via FCEUD_PrintError below anyway).
    {
        FILE* f = std::fopen(path.c_str(), "rb");
        if (!f) return "Cannot open ROM file: " + path;
        std::fseek(f, 0, SEEK_END);
        long sz = std::ftell(f);
        std::fclose(f);
        if (sz <= 0) return "Empty ROM file";
        LOGI("FC core engine = NostalgiaLite fceux (%s %s): %s (%ld bytes)",
             __DATE__, __TIME__, path.c_str(), sz);
    }

    // Legacy .srm -> .sav migration BEFORE load, so FCEU_LoadGameSave picks
    // the migrated data up.
    migrateLegacySrm(path);

    s_lastError.clear();
    {
        std::lock_guard<std::mutex> lk(s_errMtx);
        s_engineError.clear();
    }

    // Hand the file to the engine exactly as the reference does
    // (FCEUI_LoadGame(path, 0)): the engine parses the iNES/UNIF/FDS header
    // itself. NO header patching of any kind.
    s_game = FCEUI_LoadGame(path.c_str(), 0);
    if (!s_game) {
        std::lock_guard<std::mutex> lk(s_errMtx);
        LOGE("FCEUI_LoadGame FAILED for: %s (engine: %s)",
             path.c_str(), s_engineError.c_str());
        s_game = nullptr;
        if (!s_engineError.empty()) return s_engineError;
        return "FC 引擎拒绝加载该 ROM (unsupported mapper or corrupt file)";
    }

    // Reference behavior: force NTSC video system after load
    // (FCEUI_SetVidSystem(isPal ? 1 : 0) with isPal always false).
    // The user's region setting (SettingsScreen -> setCoreOption) can still
    // override it afterwards.
    FCEUI_SetVidSystem(s_vidSystem);

    // Input: one shared 32-bit word, ports 0/1 (reference layout:
    // bits 0-7 = 1P, bits 8-15 = 2P). Fourscore attached like the reference.
    FCEUI_SetInputFourscore(true);
    FCEUI_SetInput(0, SI_GAMEPAD, (void*)&s_pads, 0);
    FCEUI_SetInput(1, SI_GAMEPAD, (void*)&s_pads, 0);

    s_lastRomPath = path;
    s_loaded = true;
    s_sampleRate = kSampleRate;
    s_region = 0;                 // NTSC — same as the reference engine
    regionOut = s_region;
    s_newFrame.store(false);

    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        std::memset(s_frame, 0, sizeof(s_frame));
    }
    s_audio.reset();
    // Passthrough resampler (src == dst): pure bypass, see core_shared.h.
    s_resampler.init(kSampleRate, kSampleRate);

    LOGI("fceux ROM loaded: %s  rate=%d  vidSystem=%d  region=%d",
         path.c_str(), s_sampleRate, s_vidSystem, s_region);
    return "";
}

void unload() {
    if (s_game) {
        // FCEUI_CloseGame flushes battery SRAM to <saveDir>/<FileBase>.sav
        // (cart.cpp FCEU_SaveGameSave) and releases the cartridge.
        FCEUI_CloseGame();
        s_game = nullptr;
    }
    s_loaded = false;
    s_sampleRate = 0;
    s_audio.reset();
    s_resampler.reset();
    s_newFrame.store(false);
    s_lastRomPath.clear();
    s_saveName.clear();
    LOGI("fceux game unloaded");
}

void resetEmulation(bool hard) {
    if (!s_loaded || !s_game) return;
    if (hard) {
        FCEUI_PowerNES();     // power cycle (battery RAM kept in memory)
    } else {
        FCEUI_ResetNES();     // soft reset ($FC write)
    }
    s_audio.reset();
    s_resampler.reset();
}

// ---------------------------------------------------------------------------
// Frame stepping
// ---------------------------------------------------------------------------
void stepFrame() {
    if (!s_loaded || !s_game) return;

    // 1) Emulate one frame. skip=0 → render video + audio.
    int32_t ssize = 0;
    FCEUI_Emulate((uint8**)&s_xbuf, &s_sndBuf, &ssize, 0);

    // 2) Convert palette-index XBuf (256x240) into the ARGB frame buffer.
    //    The palette table was refreshed by FCEUD_SetPalette callbacks
    //    during FCEUI_Emulate (WritePalette / SetNESDeemph).
    {
        std::lock_guard<std::mutex> lk(s_frameMtx);
        if (s_xbuf) {
            const uint8_t* src = s_xbuf;
            uint32_t* dst = s_frame;
            const uint32_t* pal = s_paletteTable;
            for (int y = 0; y < kNesH; ++y) {
                const uint8_t* srow = src + (size_t)y * kNesW;
                uint32_t* drow = dst + (size_t)y * kNesW;
                for (int x = 0; x < kNesW; ++x) drow[x] = pal[srow[x]];
            }
        } else {
            std::memset(s_frame, 0, sizeof(s_frame));
        }
        s_newFrame.store(true, std::memory_order_release);
    }

    // 3) Audio: fceux outputs MONO int32 samples (WaveFinal; low 16 bits are
    //    the sample, exactly what the reference engine consumes). Duplicate
    //    to both channels into the shared interleaved stereo ring buffer.
    if (ssize > 0 && s_sndBuf) {
        int16_t tmp[1024];
        int32_t remaining = ssize;
        const int32_t* srcp = s_sndBuf;
        while (remaining > 0) {
            int n = (remaining < 512) ? remaining : 512;
            int16_t* o = tmp;
            for (int i = 0; i < n; ++i) {
                int16_t s = (int16_t)(srcp[i] & 0xFFFF);
                *o++ = s;   // L
                *o++ = s;   // R
            }
            s_audio.push(tmp, (size_t)n * 2);
            srcp += n;
            remaining -= n;
        }
    }

    // 4) Blit to the attached surface (with fast-forward frame skipping so
    //    ANativeWindow_lock cannot pace the emulation thread).
    if (s_fastForward.load(std::memory_order_relaxed)) {
        int skip = s_ffMaxSkip.load(std::memory_order_relaxed);
        if (skip > 0 && s_ffFrameSkip.fetch_add(1, std::memory_order_relaxed) % skip != 0)
            return;
    } else {
        s_ffFrameSkip.store(0, std::memory_order_relaxed);
    }

    coreshared::applyFilterAndBlit(
        s_window, s_windowMtx,
        s_frame, kNesW, kNesH, kNesW,
        s_videoFilter.load(std::memory_order_relaxed),
        s_xbrBuffer, s_hq4xBuffer, s_xbrMidBuffer,
        kNesW, kNesH,
        s_highQualityScaling.load(std::memory_order_relaxed));
}

bool copyFramebufferARGB(uint32_t* out, int w, int h) {
    if (!out) return false;
    if (!s_loaded) {
        std::memset(out, 0, (size_t)w * h * sizeof(uint32_t));
        return false;
    }
    std::lock_guard<std::mutex> lk(s_frameMtx);
    const int cw = (w < kNesW) ? w : kNesW;
    const int ch = (h < kNesH) ? h : kNesH;
    for (int y = 0; y < ch; ++y) {
        std::memcpy(out + (size_t)y * w, s_frame + (size_t)y * kNesW,
                    (size_t)cw * sizeof(uint32_t));
    }
    return s_newFrame.exchange(false, std::memory_order_acq_rel);
}

int readAudio(int16_t* out, int maxFrames) {
    // Resampler initialized with src == dst → pure passthrough into the
    // ring buffer, exactly like the previous core's default path.
    return s_resampler.readResampled(s_audio, out, maxFrames);
}

int audioSampleRate() { return s_sampleRate; }

int audioTargetSampleRate() { return s_sampleRate; }  // == core rate (passthrough)

void setControllerInput(int port, uint8_t bits) {
    // Shared 32-bit word: bits 0-7 = 1P, bits 8-15 = 2P (reference layout).
    uint32_t cur = s_pads.load(std::memory_order_relaxed);
    if (port == 0)      cur = (cur & 0xFFFFFF00u) | (uint32_t)(bits & 0xFF);
    else if (port == 1) cur = (cur & 0xFFFF00FFu) | ((uint32_t)(bits & 0xFF) << 8);
    else return;
    s_pads.store(cur, std::memory_order_relaxed);
}

void setPaths(const std::string& systemDir, const std::string& saveDir) {
    s_systemDir = systemDir;
    s_saveDir = saveDir;
    if (s_engineInited) applyEngineDirs();
}

void setSaveName(const std::string& name) {
    s_saveName = name;
    LOGI("SRAM save name set: '%s'", name.c_str());
}

void applyRegion(int region) {
    // 0 = NTSC, 1 = PAL. Dendy/auto are handled by the caller as NTSC.
    s_vidSystem = (region == 1) ? 1 : 0;
    if (s_loaded) FCEUI_SetVidSystem(s_vidSystem);
}

void applySampleRate(int /*hz*/) { /* fixed at 48000 by the engine */ }

void applySpeed(float multiplier) {
    s_fastForward.store(multiplier > 1.0f, std::memory_order_relaxed);
    // Frame skip: 2x → render every 2nd frame, 4x → every 4th, etc.
    s_ffMaxSkip.store((int)multiplier, std::memory_order_relaxed);
    s_ffFrameSkip.store(0, std::memory_order_relaxed);
}

// ---------------------------------------------------------------------------
// Save states (.fcs format written by the fceux engine itself)
// ---------------------------------------------------------------------------
void saveStateToPath(int /*slot*/, const std::string& path) {
    if (!s_loaded || !s_game) return;
    FCEUI_SaveState(path.c_str(), false);
    s_audio.reset();
}

bool loadStateFromPath(int /*slot*/, const std::string& path) {
    if (!s_loaded || !s_game) return false;
    // FCEUI_LoadState prints its own errors via FCEUD_PrintError; there is
    // no return value, so verify the file exists up-front.
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) { LOGE("loadState: cannot open %s", path.c_str()); return false; }
    std::fseek(f, 0, SEEK_END);
    long sz = std::ftell(f);
    std::fclose(f);
    if (sz <= 0) return false;
    FCEUI_LoadState(path.c_str(), false);
    s_audio.reset();
    return true;
}

// ---------------------------------------------------------------------------
// Hardware-accelerated rendering
// ---------------------------------------------------------------------------
void setSurface(void* nativeWindow) {
    std::lock_guard<std::mutex> lk(s_windowMtx);
    if (s_window) {
        ANativeWindow_release(s_window);
        s_window = nullptr;
    }
    if (nativeWindow) {
        s_window = static_cast<ANativeWindow*>(nativeWindow);
        ANativeWindow_acquire(s_window);
        if (s_highQualityScaling.load(std::memory_order_relaxed)) {
            ANativeWindow_setBuffersGeometry(s_window, 0, 0,
                                             WINDOW_FORMAT_RGBA_8888);
            LOGI("Surface attached (display-res buffer, high-quality scaling)");
        } else {
            ANativeWindow_setBuffersGeometry(s_window, kNesW, kNesH,
                                             WINDOW_FORMAT_RGBA_8888);
            LOGI("Surface attached (%dx%d buffer, hardware-scaled)",
                 kNesW, kNesH);
        }
    } else {
        LOGI("Surface detached");
    }
}

// ---------------------------------------------------------------------------
// Core options — only the region setting maps to the fceux engine; the rest
// (fceumm_* keys sent by the existing settings UI) are accepted and stored
// for API compatibility but have no engine effect.
// ---------------------------------------------------------------------------
void setCoreOption(const std::string& key, const std::string& value) {
    if (key == "fceumm_region") {
        if (value == "PAL") {
            applyRegion(1);
        } else {
            // "Auto" / "NTSC" / "Dendy" → NTSC (reference behavior)
            applyRegion(0);
        }
        LOGI("Core option %s = %s (vidSystem=%d)",
             key.c_str(), value.c_str(), s_vidSystem);
        return;
    }
    LOGI("Core option (no-op on fceux engine): %s = %s",
         key.c_str(), value.c_str());
}

// ---------------------------------------------------------------------------
// Video geometry
// ---------------------------------------------------------------------------
int videoWidth()  { return kNesW; }
int videoHeight() { return kNesH; }

void videoAspectRatio(int& num, int& den) {
    num = 4; den = 3;
}

// ---------------------------------------------------------------------------
// Video filter / scaling (frontend post-processing, unchanged)
// ---------------------------------------------------------------------------
void setVideoFilter(int filter) {
    s_videoFilter.store(filter, std::memory_order_relaxed);
    LOGI("Video filter set: %d", filter);
}

void setHighQualityScaling(bool enabled) {
    s_highQualityScaling.store(enabled, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lk(s_windowMtx);
    if (s_window) {
        if (enabled) {
            ANativeWindow_setBuffersGeometry(s_window, 0, 0,
                                             WINDOW_FORMAT_RGBA_8888);
        } else {
            ANativeWindow_setBuffersGeometry(s_window, kNesW, kNesH,
                                             WINDOW_FORMAT_RGBA_8888);
        }
    }
    LOGI("High-quality scaling: %s", enabled ? "ON" : "OFF");
}

} // namespace nescore::rom

// ===========================================================================
// fceux driver callbacks & stubs — global scope, plain C++ linkage (NO
// extern "C"): the engine declares these as ordinary global C++ functions
// in driver.h, so the linker needs the exact C++-mangled symbols. The set
// mirrors the reference implementation (NostalgiaLite appnes Nes.cpp) 1:1.
// ===========================================================================

// fceux calls this for every palette entry it touches (main colors at
// 128+x, deemph variants at x|0x40 / x|0xC0, unvarying 0-6, NSF/UI 7-127).
// XBuf bytes index directly into the table. NesStation's canonical frame
// format is 0xAARRGGBB (red in bits 16-23), matching core_shared.h's blit
// and Kotlin's ARGB_8888 Bitmap path.
void FCEUD_SetPalette(uint8 index, uint8 r, uint8 g, uint8 b) {
    nescore::rom::s_paletteTable[index] = 0xFF000000u |
                            ((uint32_t)r << 16) |
                            ((uint32_t)g << 8)  |
                            (uint32_t)b;
}

void FCEUD_GetPalette(uint8 i, uint8 *r, uint8 *g, uint8 *b) {
    // Only used by debug viewers; the reference driver leaves it empty too.
}

void FCEUD_PrintError(const char *s) {
    __android_log_print(ANDROID_LOG_ERROR, "nescore-rom", "[fceux] %s", s ? s : "");
    nescore::rom::recordEngineError(s);
}

void FCEUD_Message(const char *s) {
    __android_log_print(ANDROID_LOG_INFO, "nescore-rom", "[fceux] %s", s ? s : "");
}

bool turbo = 0;
int closeFinishedMovie = 0;

uint64 FCEUD_GetTime(void) { return 0; }
uint64 FCEUD_GetTimeFreq(void) { return 0; }

bool FCEUD_ShouldDrawInputAids() { return false; }

unsigned int *GetKeyboard(void) { return 0; }

FILE *FCEUD_UTF8fopen(const char *fn, const char *mode) {
    return fopen(fn, mode);
}

EMUFILE_FILE *FCEUD_UTF8_fstream(const char *n, const char *m) {
    EMUFILE_FILE *f = new EMUFILE_FILE(n, m);
    if (!f->is_open()) { delete f; return NULL; }
    return f;
}

FCEUFILE *FCEUD_OpenArchiveIndex(ArchiveScanRecord &asr, std::string &fname, int innerIndex) {
    return NULL;
}

FCEUFILE *FCEUD_OpenArchive(ArchiveScanRecord &asr, std::string &fname, std::string *innerFilename) {
    return NULL;
}

ArchiveScanRecord FCEUD_ScanArchive(std::string fname) {
    return ArchiveScanRecord();
}

const char *FCEUD_GetCompilerString() { return NULL; }

void FCEUD_SoundToggle(void) {}
void FCEUD_SoundVolumeAdjust(int) {}
void FCEUI_UseInputPreset(int preset) {}

void FCEUD_AviRecordTo(void) {}
void FCEUD_AviStop(void) {}
int FCEUI_AviBegin(const char *fname) { return 1; }
void FCEUI_AviEnd(void) {}
void FCEUI_AviVideoUpdate(const unsigned char *buffer) {}
void FCEUI_AviSoundUpdate(void *soundData, int soundLen) {}
bool FCEUI_AviIsRecording() { return false; }
bool FCEUI_AviEnableHUDrecording() { return false; }
void FCEUI_SetAviEnableHUDrecording(bool enable) {}
bool FCEUI_AviDisableMovieMessages() { return true; }
void FCEUI_SetAviDisableMovieMessages(bool disable) {}

int FCEUD_SendData(void *data, uint32 len) { return 1; }
int FCEUD_RecvData(void *data, uint32 len) { return 1; }
void FCEUD_NetplayText(uint8 *text) {}
void FCEUD_NetworkClose(void) {}

void FCEUD_SaveStateAs(void) {}
void FCEUD_LoadStateFrom(void) {}

void FCEUD_SetInput(bool fourscore, bool microphone, ESI port0, ESI port1, ESIFC fcexp) {}

void FCEUD_MovieRecordTo(void) {}
void FCEUD_MovieReplayFrom(void) {}
void FCEUD_LuaRunFrom(void) {}

void FCEUD_SetEmulationSpeed(int cmd) {}
void FCEUD_TurboOn(void) {}
void FCEUD_TurboOff(void) {}
void FCEUD_TurboToggle(void) {}

int FCEUD_ShowStatusIcon(void) { return 0; }
void FCEUD_ToggleStatusIcon(void) {}
void FCEUD_HideMenuToggle(void) {}
void FCEUD_CmdOpen(void) {}
void FCEUD_DebugBreakpoint(int bp_num) {}
void FCEUD_TraceInstruction(uint8 *opcode, int size) {}
void FCEUD_UpdateNTView(int scanline, bool drawall) {}
void FCEUD_UpdatePPUView(int scanline, int drawall) {}
bool FCEUD_PauseAfterPlayback() { return false; }
void FCEUD_VideoChanged() {}

void GetMouseData(uint32 (&md)[3]) {}
void RefreshThrottleFPS() {}
