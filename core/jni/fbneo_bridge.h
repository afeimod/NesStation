// SPDX-License-Identifier: MIT
// JNI bridge for FBNeo arcade core.
//
// Thin wrapper around the libretro frontend in fbneo_loader.cpp. Kotlin
// owns the emulation thread and pulls frames / audio on demand — same
// pull-model as the NES / SNES / GBA / DOS bridges.
//
// FBNeo uses the standard 12-button libretro gamepad layout (same bit
// layout as SNES). The bridge therefore only needs setPad1(int) for
// input — no keyboard / mouse injection (unlike DOSBox).
//
// Video resolution is dynamic (arcade boards use various resolutions:
// 224x256 vertical, 256x224 horizontal, 320x240, 384x224, 512x448
// for hi-res CPS2 / NeoGeo). The frame buffer uses a std::vector that
// resizes as needed.

#pragma once

#include <jni.h>
#include <stdint.h>
#include <string>

namespace fbneocore {

class Engine {
public:
    static Engine& instance();

    bool loadRom(const std::string& path);
    void unload();
    void reset(bool hard);
    void runFrame();
    void shutdown();

    // Standard libretro gamepad (port 0, RETRO_DEVICE_JOYPAD).
    //   bit0=A, bit1=B, bit2=Select(Coin), bit3=Start, bit4=Up, bit5=Down,
    //   bit6=Left, bit7=Right, bit8=X, bit9=Y, bit10=L, bit11=R
    // FBNeo's libretro port maps these to arcade button labels:
    //   A=Button1, B=Button2, X=Button3, Y=Button4, L=Button5, R=Button6,
    //   Select=Coin, Start=Start.
    void setPad1(int bits);
    // Second controller (port 1, RETRO_DEVICE_JOYPAD) — used for local
    // 2-player or netplay (remote opponent input). Same bit layout as
    // setPad1. FBNeo maps port 1 to player 2.
    void setPad2(int bits);
    // Third controller (port 2) — used for local 3-player arcade games.
    void setPad3(int bits);
    // Fourth controller (port 3) — used for local 4-player arcade games.
    void setPad4(int bits);

    void setRegion(int region);
    void setSampleRate(int hz);
    void setFastForward(int speed);

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

    std::string lastError() const { return lastError_; }

private:
    Engine() = default;
    std::string lastError_;
};

} // namespace fbneocore
