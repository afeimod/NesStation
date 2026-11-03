// SPDX-License-Identifier: MIT
// libretro frontend surface for the Genesis-Plus-GX core
// (SEGA Mega Drive / Genesis / Master System / Game Gear / SG-1000 / Mega-CD).
//
// genesis_loader wraps the standard libretro API behind a small, stable
// C++ interface that the JNI bridge in genesis_bridge.cpp calls. The
// prebuilt libgenesis_plus_gx_libretro_android.so is dlopen()'d at
// runtime — same pattern as fbneo_loader.cpp / dos_loader.cpp.
//
// NOTE: Genesis-Plus-GX does NOT support the SEGA Saturn (SS). Saturn
// requires a separate core (Yabause / Mednafen). The MD platform in
// GameBox covers everything Genesis-Plus-GX supports: MD, SMS, GG, SG, CD.
//
// Features:
//   * Runtime loading of prebuilt libgenesis_plus_gx_libretro_android.so
//   * 3-button and 6-button SEGA controllers (RETRO_DEVICE_JOYPAD) on port 0
//   * Dynamic ARGB frame buffer (MD: 320x224/320x240, SMS: 256x192/256x224,
//     GG: 160x144)
//   * Stereo audio ring buffer + resampler
//   * Core options variable system (genesis_plus_gx_*)
//   * Mega-CD BIOS lookup in the system directory (bios_CD_E.zip,
//     bios_CD_J.zip, bios_CD_U.zip)
//
// All retro_* calls happen on a single emulation thread (see GenesisEngine
// in Kotlin), so no extra internal locking is needed around the core
// itself.

#pragma once
#include <string>
#include <cstdint>

namespace genesicore::rom {

// Load a SEGA ROM (path to .md / .bin / .sms / .gg / .sg / .cue / .chd).
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

// Push controller state. `bits` layout (12 buttons, same as SNES):
//   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down,
//   bit6=Left, bit7=Right, bit8=X(Mode), bit9=Y, bit10=L, bit11=R
// For MD the meaningful buttons are:
//   A=Button A, B=Button B, C=Button C (mapped to X bit8 in our layout
//   since SNES has no C), Start=Start, X=Mode (6-button only),
//   Y/Z unused. Genesis-Plus-GX's libretro frontend maps the standard
//   JOYPAD buttons to the SEGA 3/6-button layout:
//     SNES A -> SEGA A, SNES B -> SEGA B, SNES X -> SEGA C,
//     SNES Y -> SEGA X (6-btn), SNES L -> SEGA Y (6-btn),
//     SNES R -> SEGA Z (6-btn), SNES Select -> Mode, SNES Start -> Start.
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

} // namespace genesicore::rom
