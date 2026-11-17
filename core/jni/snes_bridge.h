// SPDX-License-Identifier: MIT
// JNI bridge for SNES9x core — simplified pull-model interface.
//
// Kotlin owns the emulation loop: it calls runFrame() to step the core,
// getFrameBuffer() to pull the latest ARGB frame (fallback), and readAudio()
// to pull stereo PCM for AudioTrack. For hardware-accelerated rendering,
// Kotlin calls setSurface() to attach an ANativeWindow; the core then blits
// frames directly to the surface, bypassing the JNI frame buffer copy.
#pragma once

#include <jni.h>
#include <stdint.h>
#include <string>

namespace snescore {

class Engine {
public:
    static Engine& instance();

    bool loadRom(const std::string& path);
    void unload();
    void reset(bool hard);
    void runFrame();
    void shutdown();

    void setPad1(int bits);          // bit layout: A B SEL STA U D L R X Y L R
    void setPad2(int bits);          // second controller (port 1), same bit layout
    void setRegion(int region);      // 0=NTSC 1=PAL
    void setSampleRate(int hz);
    void setFastForward(int speed);

    void saveState(int slot, const std::string& dstPath);
    bool loadState(int slot, const std::string& srcPath);

    // Pull the latest frame into `out` (w*h uint32, 0xAARRGGBB).
    // Returns true if a new frame was produced since the last call.
    bool getFrameBuffer(uint32_t* out, int w, int h);

    // Pull up to maxFrames stereo frames (2 int16 each) into `out`.
    // Returns the number of real frames written (zero-filled on underrun).
    int  readAudio(int16_t* out, int maxFrames);

    // Core-reported sample rate (0 before load).
    int  audioSampleRate();

    // Target output sample rate for Android AudioTrack (48000 Hz).
    // Audio is resampled from the core's native rate to this rate.
    int  audioTargetSampleRate();

    // Set system and save directories.
    void setPaths(const std::string& systemDir, const std::string& saveDir);

    // Set an explicit .srm basename (for content:// URI games that share a temp ROM file).
    void setSaveName(const std::string& name);

    // --- Hardware-accelerated rendering ---
    // Attach/detach an ANativeWindow for direct surface blitting.
    void setSurface(jobject surface);

    // --- Core options ---
    // Set a core option by key/value (e.g. "snes9x_aspect" -> "4:3").
    void setCoreOption(const std::string& key, const std::string& value);

    // --- Video geometry ---
    int  videoWidth();
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

} // namespace snescore
