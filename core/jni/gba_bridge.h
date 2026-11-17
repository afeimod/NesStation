// SPDX-License-Identifier: MIT
// JNI bridge for mGBA core (GB/GBC/GBA) — simplified pull-model interface.
//
// Kotlin owns the emulation loop: it calls runFrame() to step the core,
// getFrameBuffer() to pull the latest ARGB frame (fallback), and readAudio()
// to pull stereo PCM for AudioTrack. For hardware-accelerated rendering,
// Kotlin calls setSurface() to attach an ANativeWindow; the core then blits
// frames directly to the surface, bypassing the JNI frame buffer copy.
//
// Video resolution is dynamic:
//   GB/GBC: 160x144
//   GBA:    240x160
// Use videoWidth() / videoHeight() to query the current geometry before
// allocating the frame buffer array on the Kotlin side.
#pragma once

#include <jni.h>
#include <stdint.h>
#include <string>

namespace gbacore {

class Engine {
public:
    static Engine& instance();

    bool loadRom(const std::string& path);
    void unload();
    // Flush current SAVE_RAM to disk now (protects in-game saves against
    // process kill/crash/backgrounding). No-op when no ROM is loaded.
    void flushSave();
    void reset(bool hard);
    void runFrame();
    void shutdown();

    // bit layout: A B SEL STA U D L R L R(gba)
    //   bit0=A, bit1=B, bit2=Select, bit3=Start,
    //   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
    //   bit8=L(GBA), bit9=R(GBA)
    void setPad1(int bits);
    void setPad2(int bits);          // second controller (port 1), same bit layout
    void setRegion(int region);      // 0=NTSC 1=PAL (best-effort)
    void setSampleRate(int hz);
    void setFastForward(int speed);

    void saveState(int slot, const std::string& dstPath);
    bool loadState(int slot, const std::string& srcPath);

    // Pull the latest frame into `out` (w*h uint32, 0xAARRGGBB).
    // Returns true if a new frame was produced since the last call.
    // w/h should match the current videoWidth()/videoHeight().
    bool getFrameBuffer(uint32_t* out, int w, int h);

    // Pull up to maxFrames stereo frames (2 int16 each) into `out`.
    // Returns the number of real frames written (zero-filled on underrun).
    int  readAudio(int16_t* out, int maxFrames);

    // Core-reported sample rate (0 before load).
    int  audioSampleRate();

    // Target output sample rate for Android AudioTrack (48000 Hz).
    // Audio is resampled from the core's native rate to this rate.
    int  audioTargetSampleRate();

    // Set system (BIOS) and save (SRAM) directories.
    void setPaths(const std::string& systemDir, const std::string& saveDir);

    // Set an explicit .srm basename (for content:// URI games that share a temp ROM file).
    void setSaveName(const std::string& name);

    // --- Hardware-accelerated rendering ---
    // Attach/detach an ANativeWindow for direct surface blitting.
    void setSurface(jobject surface);

    // --- Core options ---
    // Set a core option by key/value (e.g. "mgba_gb_model" -> "Autodetect").
    void setCoreOption(const std::string& key, const std::string& value);

    // --- Video geometry ---
    // Returns the current video width (160 for GB/GBC, 240 for GBA).
    int  videoWidth();
    // Returns the current video height (144 for GB/GBC, 160 for GBA).
    int  videoHeight();

    // --- Video filter ---
    // Set frontend post-processing filter: 0=none 1=scanline 2=crt 3=dot 4=xbr 5=hq2x 6=hq4x 7=xbr+dot
    void setVideoFilter(int filter);

    // --- High-quality scaling ---
    void setHighQualityScaling(bool enabled);

    std::string lastError() const { return lastError_; }

private:
    Engine() = default;
    std::string lastError_;
};

} // namespace gbacore
