// SPDX-License-Identifier: MIT
// JNI bridge for the Sony PSP core (libretro PPSSPP port).
//
// Thin wrapper around the libretro frontend in psp_loader.cpp. Kotlin
// owns the emulation thread and pulls frames / audio on demand — same
// pull-model as the DC / PSX / FBNeo bridges (dc_bridge.cpp is the
// reference implementation of this facade).
//
// The PSP pad uses the standard libretro gamepad layout with PlayStation
// label semantics after the Kotlin-side ps2ToLibretroLayout() conversion
// (the core maps JOYPAD_B→✕ Cross, JOYPAD_A→○ Circle, JOYPAD_Y→□ Square,
// JOYPAD_X→△ Triangle). The bridge provides setPad1..4(int) for buttons
// and setAnalogAxes() for the analog stick (RETRO_DEVICE_ANALOG int16
// axes — the PSP stick is analog and most PSP games read it).
//
// Video is hardware-rendered (GLES3 FBO → ANativeWindow blit + swap) —
// see psp_loader.cpp for the full frontend contract.

#pragma once

#include <jni.h>
#include <stdint.h>
#include <string>

namespace pspcore {

class Engine {
public:
    static Engine& instance();

    bool loadRom(const std::string& path);
    void unload();
    void reset(bool hard);
    void runFrame();
    void shutdown();

    // Standard libretro gamepad (port 0, RETRO_DEVICE_JOYPAD).
    //   bit0=✕(B), bit1=□(Y), bit2=Select, bit3=Start, bit4=Up, bit5=Down,
    //   bit6=Left, bit7=Right, bit8=○(A), bit9=△(X), bit10=L, bit11=R
    // (Kotlin ps2ToLibretroLayout maps the on-screen ×○□△ labels onto
    //  these ids — screen "✕" presses Cross, screen "○" presses Circle, etc.)
    void setPad1(int bits);
    // Second controller (port 1) — unused by single-player PSP games. Same layout.
    void setPad2(int bits);
    // Third controller (port 2). Same bit layout.
    void setPad3(int bits);
    // Fourth controller (port 3). Same bit layout.
    void setPad4(int bits);

    // Analog stick for a controller port — libretro int16 axes
    // (−32768..32767, 0 = center), order LX/LY/RX/RY. Feeds the PSP
    // analog stick (the core polls RETRO_DEVICE_ANALOG LEFT X/Y).
    void setAnalogAxes(int port, int lx, int ly, int rx, int ry);

    void setRegion(int region);
    void setSampleRate(int hz);
    void setFastForward(int speed);

    // Switch a controller port device (RETRO_DEVICE_JOYPAD = 1 / ANALOG = 5).
    // Queued; applied on the emu thread.
    void setPortDevice(int port, int device);
    // Core-reported refresh rate (59.94 NTSC).
    double videoRefreshRate();
    // Read & clear the count of frames actually presented by the core since
    // the last poll (FPS HUD — real game output rate, not the paced loop rate).
    int pollPresentedFrames();

    // ★★ 即时存档（v5 线程修复）★★ 存/读档被投递到模拟线程、在两次
    // retro_run 之间执行（retro_serialize 与核心内部模拟线程握手，与
    // retro_run 并发调用会永久死锁 —— 见 psp_loader.h 的大段注释）。
    // 阻塞等待结果（典型 0.1~1s，超时 8s 返回 false）。
    bool saveState(int slot, const std::string& dstPath);
    bool loadState(int slot, const std::string& srcPath);
    // 暂停时由模拟线程 tick 调用：取出挂起的存/读档操作（不跑帧）。
    void drainStateOp();

    // Pull the latest frame into `out` (w*h uint32, 0xAARRGGBB). In HW mode
    // this serves the last glReadPixels capture (see psp_loader.cpp).
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

    // --- Core options (ppsspp_*) ---
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

} // namespace pspcore
