// SPDX-License-Identifier: MIT
// JNI bridge for the SEGA Dreamcast / NAOMI / AtomisWave core (libretro
// Flycast).
//
// Thin wrapper around the libretro frontend in dc_loader.cpp. Kotlin
// owns the emulation thread and pulls frames / audio on demand — same
// pull-model as the NES / SNES / GBA / FBNeo / PSX bridges.
//
// The Dreamcast pad uses the standard 12-button libretro gamepad layout
// (same bit layout as SNES after the Kotlin-side dcToLibretroLayout()
// conversion — flycast maps JOYPAD_B→DC A, JOYPAD_A→DC B, JOYPAD_Y→DC X,
// JOYPAD_X→DC Y). The bridge therefore only needs setPad1/2/3/4(int) for
// input. The analog sticks report neutral axes via RETRO_DEVICE_ANALOG so
// analog-aware games still see a present stick.
//
// Video is hardware-rendered (GLES3 FBO → ANativeWindow blit + swap) —
// see dc_loader.cpp for the full frontend contract.

#pragma once

#include <jni.h>
#include <stdint.h>
#include <string>

namespace dccore {

class Engine {
public:
    static Engine& instance();

    bool loadRom(const std::string& path);
    void unload();
    void reset(bool hard);
    void runFrame();
    void shutdown();

    // Standard libretro gamepad (port 0, RETRO_DEVICE_JOYPAD).
    //   bit0=A, bit1=X, bit2=Select, bit3=Start, bit4=Up, bit5=Down,
    //   bit6=Left, bit7=Right, bit8=B, bit9=Y, bit10=L, bit11=R
    // (Kotlin dcToLibretroLayout maps the on-screen A/B/X/Y labels onto
    //  these ids — screen "A" presses DC A, screen "B" presses DC B, etc.)
    void setPad1(int bits);
    // Second controller (port 1) — used for local 2-player. Same bit layout.
    void setPad2(int bits);
    // Third controller (port 2). Same bit layout.
    void setPad3(int bits);
    // Fourth controller (port 3). Same bit layout.
    void setPad4(int bits);

    void setRegion(int region);
    void setSampleRate(int hz);
    void setFastForward(int speed);

    // Switch a controller port device (RETRO_DEVICE_JOYPAD = 1 / ANALOG = 5).
    // Queued; applied on the emu thread.
    void setPortDevice(int port, int device);
    // Core-reported refresh rate (59.94 NTSC / 50.0 PAL).
    double videoRefreshRate();
    // Read & clear the count of frames actually presented by the core since
    // the last poll (FPS HUD — real game output rate, not the paced loop rate).
    int pollPresentedFrames();

    void saveState(int slot, const std::string& dstPath);
    bool loadState(int slot, const std::string& srcPath);

    // Pull the latest frame into `out` (w*h uint32, 0xAARRGGBB). In HW mode
    // this serves the last glReadPixels capture (see dc_loader.cpp).
    bool getFrameBuffer(uint32_t* out, int w, int h);

    // Schedule a fresh capture from the core's FBO and wait briefly for it.
    bool captureFrame(uint32_t* out, int w, int h);

    // Pull up to maxFrames stereo frames (2 int16 each) into `out`.
    int  readAudio(int16_t* out, int maxFrames);

    int  audioSampleRate();
    int  audioTargetSampleRate();

    void setPaths(const std::string& systemDir, const std::string& saveDir);
    void setSaveName(const std::string& name);

    // --- Hardware-accelerated rendering (EGL / GLES3) ---
    void setSurface(jobject surface);

    // --- Core options (reicast_*) ---
    void setCoreOption(const std::string& key, const std::string& value);

    // --- Video geometry ---
    int  videoWidth();
    int  videoHeight();

    // --- Video filter (software fallback only) ---
    void setVideoFilter(int filter);
    void setHighQualityScaling(bool enabled);

    std::string lastError() const { return lastError_; }

private:
    Engine() = default;
    std::string lastError_;
};

} // namespace dccore
