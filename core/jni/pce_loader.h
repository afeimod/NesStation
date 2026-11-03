// SPDX-License-Identifier: MIT
// libretro frontend surface for the Geargrafx core
// (PC-Engine / TurboGrafx-16 / SuperGrafx / PCE-CD).
//
// pce_loader wraps the standard libretro API behind a small, stable
// C++ interface that the JNI bridge in pce_bridge.cpp calls. The
// prebuilt libgeargrafx_libretro_android.so is dlopen()'d at
// runtime — same pattern as genesis_loader.cpp / fbneo_loader.cpp.
//
// Features:
//   * Runtime loading of prebuilt libgeargrafx_libretro_android.so
//   * Standard PCE 2-button controller (RETRO_DEVICE_JOYPAD) on port 0
//   * Dynamic ARGB frame buffer (PCE: 256x224/256x242, SGX: 256x224/256x239)
//   * Stereo audio ring buffer + resampler
//   * Core options variable system (geargrafx_*)
//   * PCE-CD BIOS lookup in the system directory (syscard1.pce, syscard2.pce,
//     syscard3.pce, gexpress.pce)
//
// All retro_* calls happen on a single emulation thread (see PceEngine
// in Kotlin), so no extra internal locking is needed around the core
// itself.

#pragma once
#include <string>
#include <cstdint>

namespace pcecore::rom {

// Load a PCE ROM (path to .pce / .sgx / .hes / .cue / .chd).
// Returns an empty string on success, an error message otherwise.
// `regionOut` receives 0 = NTSC, 1 = PAL (auto-detected from the core).
std::string loadFromFile(const std::string& path, int& regionOut);

void unload();

// v69 全核心金手指：取核心当前暴露的 SYSTEM_RAM / SAVE_RAM 区域指针。
// 指针归核心所有，仅在 emulation 线程的帧回调内使用；未加载游戏时两组均
// 返回 NULL/0（调用方按越界跳过处理）。见 shared/cheat_engine.h。
void cheatRamRegions(void** sysRam, size_t* sysSize,
                     void** saveRam, size_t* saveSize);

void resetEmulation(bool hard);
void stepFrame();

bool copyFramebufferARGB(uint32_t* out, int w, int h);
int  readAudio(int16_t* out, int maxFrames);

int audioSampleRate();
int audioTargetSampleRate();

// Push controller state. `bits` layout (8 buttons used by PCE):
//   bit0=A(PCE II), bit1=B(PCE I), bit2=Select, bit3=Start(Run),
//   bit4=Up, bit5=Down, bit6=Left, bit7=Right
// (bits 8-11 are unused — PCE only has 2 face buttons)
// Supports dual controllers: port 0 (pad 1) and port 1 (pad 2).
void setControllerInput(int port, uint16_t bits);

void setPaths(const std::string& systemDir, const std::string& saveDir);
void setSaveName(const std::string& name);

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

bool isCoreLoaded();

} // namespace pcecore::rom
