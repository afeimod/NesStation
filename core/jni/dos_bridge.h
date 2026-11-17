// SPDX-License-Identifier: MIT
// JNI bridge for DOSBox-Pure core (DOS/PC emulator).
//
// DOSBox-Pure ships as a prebuilt libretro .so (libdosbox_pure_libretro_android.so)
// that we dlopen() at runtime from libdoscore.so. This avoids having to compile
// the complex dosbox-pure source tree and lets us reuse the prebuilt binaries
// from the libretro buildbot.
//
// Unlike the NES/SNES/GBA cores which only need a small gamepad bitfield, DOSBox
// requires a full keyboard + mouse. The bridge therefore exposes:
//   - setPad1(bits) for the standard libretro gamepad (D-pad + A/B/X/Y + L/R)
//   - injectKeyDown/up(keyCode, modifiers) for full keyboard input via RETRO_DEVICE_KEYBOARD
//   - injectMouseMove(dx, dy) for mouse via RETRO_DEVICE_MOUSE (REL_X/REL_Y)
//   - injectMouseButton(button, pressed) for mouse buttons (LEFT/RIGHT/MIDDLE)
//
// Video resolution is dynamic (typically 320x200, 640x480, or 1024x768), so
// the frame buffer uses a std::vector that resizes as needed.
#pragma once

#include <jni.h>
#include <stdint.h>
#include <string>

namespace doscore {

class Engine {
public:
    static Engine& instance();

    bool loadRom(const std::string& path);
    void unload();
    void reset(bool hard);
    void runFrame();
    void shutdown();

    // Standard libretro gamepad (port 0, RETRO_DEVICE_JOYPAD).
    //   bit0=A(Enter), bit1=B(Esc), bit2=Select, bit3=Start,
    //   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
    //   bit8=L(mouse left), bit9=R(mouse right),
    //   bit10=X(Space), bit11=Y(Tab)
    // The actual mapping to DOS keys is handled by dosbox_pure's auto-mapping.
    void setPad1(int bits);

    // DOS is single-player only — setPad2 is a no-op.
    void setPad2(int bits) {}

    // Full keyboard input (port 0, RETRO_DEVICE_KEYBOARD).
    // `keyCode` is a libretro RETROK_* value (see libretro.h).
    // `modifiers` is a bitmask of RETROKMOD_* (SHIFT/CTRL/ALT/META/NUMLOCK/CAPSLOCK/SCROLLOCK).
    void injectKeyDown(int keyCode, int modifiers);
    void injectKeyUp(int keyCode, int modifiers);

    // Mouse input (port 0, RETRO_DEVICE_MOUSE).
    // `dx`, `dy` are relative deltas (REL_X / REL_Y).
    void injectMouseMove(int dx, int dy);

    // Mouse buttons: 0=LEFT, 1=RIGHT, 2=MIDDLE, 3=WHEEL_UP, 4=WHEEL_DOWN,
    // 5=HORIZ_WHEEL_UP, 6=HORIZ_WHEEL_DOWN, 7=BUTTON_4, 8=BUTTON_5
    void injectMouseButton(int button, bool pressed);

    // Set which libretro device type to use on port 0.
    //   0 = JOYPAD (default — auto-mapped gamepad)
    //   1 = KEYBOARD-only (full keyboard, no gamepad)
    //   2 = MOUSE-only (full mouse, no gamepad)
    //   3 = JOYPAD + KEYBOARD + MOUSE (combined)
    void setInputDeviceMode(int mode);

    void setRegion(int region);      // unused (DOS has no region concept)
    void setSampleRate(int hz);
    void setFastForward(int speed);

    // DOSBox-Pure does not support save states by default.
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

} // namespace doscore
