// SPDX-License-Identifier: MIT
// libretro frontend surface for the DOSBox-Pure core (DOS/PC emulator).
//
// dos_loader wraps the standard libretro API (retro_init / retro_load_game /
// retro_run ...) behind a small, stable C++ interface that the JNI bridge in
// dos_bridge.cpp calls. Unlike the NES/SNES/GBA cores which statically link
// the core source, this loader dlopen()s the prebuilt
// libdosbox_pure_libretro_android.so at runtime and resolves the retro_*
// symbols via dlsym().
//
// Features:
//   * Runtime loading of prebuilt libdosbox_pure_libretro_android.so
//   * Full keyboard + mouse input (RETRO_DEVICE_KEYBOARD + RETRO_DEVICE_MOUSE)
//   * Standard gamepad input (RETRO_DEVICE_JOYPAD, port 0)
//   * Dynamic ARGB frame buffer (DOS resolutions: 320x200 to 1024x768)
//   * Stereo audio ring buffer + resampler (shared implementation)
//   * Core options variable system (dosbox_pure_*)
//   * Pixel format conversion (XRGB8888 / RGB565 / 0RGB1555 -> ARGB)
//   * Hardware-accelerated rendering via ANativeWindow (SurfaceView)
//
// All retro_* calls happen on a single emulation thread (see DosEngine in
// Kotlin), so no extra internal locking is needed around the core itself.
// The frame and audio buffers are mutex-guarded because the UI / AudioTrack
// threads read them concurrently.

#pragma once
#include <string>
#include <cstdint>

namespace doscore::rom {

// Load a DOS game (path to .bat / .exe / .com / .dosz / .conf / folder).
// Returns an empty string on success, an error message otherwise.
std::string loadFromFile(const std::string& path, int& regionOut);

void unload();
void resetEmulation(bool hard);
void stepFrame();

bool copyFramebufferARGB(uint32_t* out, int w, int h);
int  readAudio(int16_t* out, int maxFrames);

int audioSampleRate();
int audioTargetSampleRate();

// Standard gamepad input (port 0, RETRO_DEVICE_JOYPAD).
void setControllerInput(int port, uint16_t bits);

// Full keyboard input (port 0, RETRO_DEVICE_KEYBOARD).
void keyboardDown(int keyCode, int modifiers);
void keyboardUp(int keyCode, int modifiers);

// Mouse input (port 0, RETRO_DEVICE_MOUSE).
void mouseMove(int dx, int dy);
void mouseButton(int button, bool pressed);

// Set the input device type on port 0 (see Engine::setInputDeviceMode).
void setInputDeviceMode(int mode);

void setPaths(const std::string& systemDir, const std::string& saveDir);
void setSaveName(const std::string& name);

// Set the absolute path to libdosbox_pure_libretro_android.so — used by dlopen
// at load time. Pass an empty string to revert to bare-name dlopen.
void setCoreLibPath(const std::string& path);

void applyRegion(int region);
void applySampleRate(int hz);
void applySpeed(float multiplier);

void saveStateToPath(int slot, const std::string& path);
bool loadStateFromPath(int slot, const std::string& path);

void setSurface(void* nativeWindow);

void setCoreOption(const std::string& key, const std::string& value);

int  videoWidth();
int  videoHeight();
void videoAspectRatio(int& num, int& den);

void setVideoFilter(int filter);
void setHighQualityScaling(bool enabled);

// Check whether libdosbox_pure_libretro_android.so was successfully dlopen()'d.
// Returns true if the core is loaded and ready.
bool isCoreLoaded();

} // namespace doscore::rom
