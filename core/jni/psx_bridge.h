// SPDX-License-Identifier: MIT
// JNI bridge for PCSX-ReARMed (PlayStation 1) core.
//
// Thin wrapper around the libretro frontend in psx_loader.cpp. Kotlin
// owns the emulation thread and pulls frames / audio on demand — same
// pull-model as the NES / SNES / GBA / FBNeo bridges.
//
// PlayStation uses the standard 12-button libretro gamepad layout (same
// bit layout as SNES). The bridge therefore only needs setPad1/2/3/4(int)
// for input — no keyboard / mouse injection (touchscreen / analog stick
// support can be added later via RETRO_DEVICE_ANALOG, but the standard
// digital pad is sufficient for most PS1 games).
//
// Video resolution is dynamic (PS1 standard is 320x240 NTSC / 320x256 PAL,
// but several games use 256x240, 384x240, 512x240, or interlaced 640x480
// hi-res modes). The frame buffer uses a std::vector that resizes as
// needed up to a hard cap of 640x480.

#pragma once

#include <jni.h>
#include <stdint.h>
#include <string>

namespace psxcore {

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
    // PS1's libretro port maps these to the standard DualShock layout:
    //   A=Circle (Japanese confirm) / Cross (Western confirm),
    //   B=Cross  (Japanese cancel)   / Circle (Western cancel),
    //   X=Triangle, Y=Square, L=L1, R=R1, Select=Select, Start=Start.
    // L2/R2/L3/R3 are not exposed via this 12-bit interface — they require
    // RETRO_DEVICE_ANALOG / a wider bitfield. Most PS1 games are fully
    // playable with just the 12-button layout.
    void setPad1(int bits);
    // Second controller (port 1) — used for local 2-player. Same bit layout.
    void setPad2(int bits);
    // Third controller (port 2) — used for 3+ player games via Multitap.
    void setPad3(int bits);
    // Fourth controller (port 3) — used for 4+ player games via Multitap.
    // PS1 supports up to 8 players with dual Multitaps; only pads 1-4 are
    // exposed here (covers the vast majority of multiplayer titles).
    void setPad4(int bits);

    // ★★ v60 DualShock analog stick axes for player 1 (int16 per axis).
    //   Feeds cb_input_state's RETRO_DEVICE_ANALOG queries — the missing
    //   piece that made PS1 analog sticks never move. Pair with
    //   setPortDevice(0, RETRO_DEVICE_ANALOG) ("analog" input device setting).
    void setAnalog1(int lx, int ly, int rx, int ry);

    void setRegion(int region);
    void setSampleRate(int hz);
    void setFastForward(int speed);

    // Switch a controller port between RETRO_DEVICE_JOYPAD (standard digital)
    // and RETRO_DEVICE_ANALOG (DualShock). Queued; applied on the emu thread.
    void setPortDevice(int port, int device);
    // Core-reported refresh rate (59.82614 NTSC / 50.0 PAL / ...).
    double videoRefreshRate();
    // Read & clear the count of frames actually presented by the core since
    // the last poll (FPS HUD — real game output rate, not the paced loop rate).
    int pollPresentedFrames();

    void saveState(int slot, const std::string& dstPath);
    bool loadState(int slot, const std::string& srcPath);

    // Pull the latest frame into `out` (w*h uint32, 0xAARRGGBB).
    bool getFrameBuffer(uint32_t* out, int w, int h);

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

    // ★ 分辨率倍数（前端整数放大，1..4）—— 运行中热切换，下一帧生效。
    void setResolutionScale(int scale);

    std::string lastError() const { return lastError_; }

private:
    Engine() = default;
    std::string lastError_;
};

} // namespace psxcore
