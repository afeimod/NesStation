// SPDX-License-Identifier: MIT
// JNI bridge for the Flycast (Sega Dreamcast / Naomi / Atomiswave) core.
//
// Thin wrapper around the libretro frontend in flycast_loader.cpp. Kotlin
// owns the emulation thread and pulls frames / audio on demand — same
// pull-model as the other engines. The difference versus the 2D cores is
// rendering: flycast draws through the frontend-provided GLES3 FBO (see
// flycast_loader.h), so setSurface() forwards an ANativeWindow and the
// emulation thread owns the EGL context.

#pragma once

#include <jni.h>
#include <stdint.h>
#include <string>

namespace flycastcore {

class Engine {
public:
    static Engine& instance();

    bool loadRom(const std::string& path);
    void unload();
    void reset(bool hard);
    void runFrame();
    void shutdown();

    // Digital pad state for ports 0..3 (project UI bit layout, converted to
    // libretro inside the loader — see flycast_loader.h for the mapping).
    void setPad1(int bits);
    void setPad2(int bits);
    void setPad3(int bits);
    void setPad4(int bits);

    // Analog thumb stick (libretro convention, int16 range):
    // axis 0 = LX, 1 = LY, 2 = RX, 3 = RY.
    void setAnalogAxis(int port, int axis, int16_t value);

    void setRegion(int region);
    void setSampleRate(int hz);
    void setFastForward(int speed);

    // Queue a maple-port device switch (RETRO_DEVICE_JOYPAD / KEYBOARD / ...).
    void setPortDevice(int port, int device);
    double videoRefreshRate();
    int pollPresentedFrames();

    void saveState(int slot, const std::string& dstPath);
    bool loadState(int slot, const std::string& srcPath);

    // Pull the latest CPU-side frame (filled by glReadPixels) into `out`.
    bool getFrameBuffer(uint32_t* out, int w, int h);
    // Schedule a fresh glReadPixels on the emulation thread (screenshots).
    void requestFrameReadback();

    int  readAudio(int16_t* out, int maxFrames);

    int  audioSampleRate();
    int  audioTargetSampleRate();

    void setPaths(const std::string& systemDir, const std::string& saveDir);
    void setSaveName(const std::string& name);

    // Hardware-accelerated rendering surface (ANativeWindow extracted here).
    void setSurface(jobject surface);

    void setCoreOption(const std::string& key, const std::string& value);

    int  videoWidth();
    int  videoHeight();

    void setVideoFilter(int filter);
    void setHighQualityScaling(bool enabled);

    std::string lastError() const { return lastError_; }

private:
    Engine() = default;
    std::string lastError_;
};

} // namespace flycastcore
