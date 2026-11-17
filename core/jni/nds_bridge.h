// SPDX-License-Identifier: MIT
// JNI bridge for melonDS (Nintendo DS) core.
//
// Thin wrapper around the libretro frontend in nds_loader.cpp. Kotlin
// owns the emulation thread and pulls frames / audio on demand — same
// pull-model as the NES / SNES / GBA / FBNeo / PSX bridges.
//
// DS uses the standard 12-button libretro gamepad layout (same bit layout
// as SNES). The bridge therefore only needs setPad1/2/3/4(int) for input
// — no keyboard / mouse injection. DS touchscreen input is exposed via
// setTouchInput(x, y, pressed) where x/y are 16-bit signed coordinates
// in [-0x8000, 0x7FFF] mapping to the full composited frame buffer
// (libretro RETRO_DEVICE_POINTER convention). The melonDS core's Touch
// mode handles the per-screen touch region check internally.
// setTouchInputDirect(x, y, pressed) instead takes DIRECT bottom-screen
// pixel coordinates (0..255 / 0..191) — the official melonDS Android
// frontend architecture — and is preferred for all non-hybrid layouts.
//
// Video resolution is fixed at 256x192 per screen. The default layout
// stacks the two screens vertically (top+bottom = 256x384), but melonDS
// also supports horizontal layouts (left/right = 512x192) and single-screen
// modes via the melonds_screen_layout core option.

#pragma once

#include <jni.h>
#include <stdint.h>
#include <string>

namespace ndscore {

class Engine {
public:
    static Engine& instance();

    bool loadRom(const std::string& path);
    void unload();
    void reset(bool hard);
    void runFrame();
    void shutdown();

    // Standard libretro gamepad (port 0, RETRO_DEVICE_JOYPAD).
    //   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down,
    //   bit6=Left, bit7=Right, bit8=X, bit9=Y, bit10=L, bit11=R
    // DS's libretro port maps these to the standard DS button layout:
    //   A=A, B=B, X=X, Y=Y, L=L, R=R, Select=Select, Start=Start.
    // (DS has the same 12-button layout as SNES — A/B/X/Y + D-pad + L/R +
    // Start/Select.) Touchscreen input (X/Y position + pressure) is NOT
    // exposed via this interface; it requires RETRO_DEVICE_POINTER, which
    // we'll add in a future revision.
    void setPad1(int bits);
    // Second controller (port 1) — used for local 2-player DS games
    // (e.g. Mario Kart DS download play). Same bit layout.
    void setPad2(int bits);
    // Third controller (port 2) — DS supports up to 16 players via
    // Download Play; only pads 1-4 are exposed here.
    void setPad3(int bits);
    // Fourth controller (port 3).
    void setPad4(int bits);

    // Touchscreen input via RETRO_DEVICE_POINTER.
    // x, y: 0..0xFFFF (normalized — the core maps to DS screen coords).
    // pressed: true = touching, false = released.
    void setTouchInput(int x, int y, bool pressed);

    // Touchscreen input with direct bottom-screen pixel coordinates
    // (x: 0..255, y: 0..191). See nds_loader.h for details.
    void setTouchInputDirect(int x, int y, bool pressed);

    void setRegion(int region);
    void setSampleRate(int hz);
    void setFastForward(int speed);

    bool saveState(int slot, const std::string& dstPath);
    bool loadState(int slot, const std::string& srcPath);

    // Pull the latest frame into `out` (w*h uint32, 0xAARRGGBB).
    bool getFrameBuffer(uint32_t* out, int w, int h);

    // Pull the frame the custom dual-screen view should present: the
    // upscaled (HQ2X/HQ4X/XBR) frame when the filter is active and no
    // surface is attached, the raw frame otherwise.
    bool getFilteredFrameBuffer(uint32_t* out, int w, int h);

    // Dimensions matching getFilteredFrameBuffer's output.
    int  filteredWidth();
    int  filteredHeight();

    // Monotonic frame counter (for draw throttling on high-refresh screens).
    uint64_t frameStamp();

    // Pull up to maxFrames stereo frames (2 int16 each) into `out`.
    int  readAudio(int16_t* out, int maxFrames);

    int  audioSampleRate();
    int  audioTargetSampleRate();

    void setPaths(const std::string& systemDir, const std::string& saveDir);
    void setSaveName(const std::string& name);

    // --- Hardware-accelerated rendering ---
    void setSurface(jobject surface);

    // --- Core options ---
    void setCoreOption(const std::string& key, const std::string& value);

    // --- Video geometry ---
    int  videoWidth();
    int  videoHeight();

    // --- Video filter (frontend post-processing) ---
    void setVideoFilter(int filter);
    void setHighQualityScaling(bool enabled);

    std::string lastError() const { return lastError_; }

private:
    Engine() = default;
    std::string lastError_;
};

} // namespace ndscore
