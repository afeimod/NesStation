// SPDX-License-Identifier: MIT
// libretro frontend surface for the PCSX-ReARMed (PlayStation 1) core.
//
// psx_loader wraps the standard libretro API (retro_init /
// retro_load_game / retro_run ...) behind a small, stable C++ interface
// that the JNI bridge in psx_bridge.cpp calls. The prebuilt
// libpcsx_rearmed_libretro_android.so is dlopen()'d at runtime and the
// retro_* symbols resolved via dlsym() — same pattern as fbneo_loader.cpp.
//
// Features:
//   * Runtime loading of prebuilt libpcsx_rearmed_libretro_android.so
//   * 12-button PS1 gamepad (RETRO_DEVICE_JOYPAD) on ports 0-3 with the
//     standard DualShock layout: D-pad + Cross/Circle/Square/Triangle +
//     L1/R1 + Select/Start (L2/R2/L3/R3 require analog device — not
//     exposed via this 12-bit interface).
//   * Dynamic ARGB frame buffer (PS1 resolutions vary: 256x240, 320x240,
//     368x240, 512x240, 640x480 interlaced hi-res).
//   * Stereo audio ring buffer + resampler (shared implementation)
//   * Core options variable system (pcsx_rearmed_* — keys MUST match
//     pcsx_rearmed's libretro_core_options.h exactly).
//   * Pixel format conversion (XRGB8888 / RGB565 / 0RGB1555 -> ARGB)
//   * Hardware-accelerated rendering via ANativeWindow (SurfaceView)
//   * BIOS file lookup in the system directory (scph1001.bin / scph5500.bin
//     etc.) — PCSX-ReARMed auto-discovers these by filename in <systemDir>/.
//     With pcsx_rearmed_bios = "auto", the core falls back to HLE BIOS if
//     no real BIOS file is found.
//   * Memory card persistence via RETRO_MEMORY_SAVE_RAM (MCD data is
//     serialised as a .srm alongside the save state directory).
//
// All retro_* calls happen on a single emulation thread (see PsxEngine
// in Kotlin), so no extra internal locking is needed around the core
// itself. The frame and audio buffers are mutex-guarded because the UI /
// AudioTrack threads read them concurrently.

#pragma once
#include <string>
#include <cstdint>

namespace psxcore::rom {

// Load a PS1 game (path to .cue / .bin / .chd / .pbp / .m3u / .ecm CD image,
// or to .exe / .psf / .minipsf standalone executable).
// CD images are passed by path (the core opens them itself to parse the
// disc TOC); standalone executables are pre-loaded into memory.
// Returns an empty string on success, an error message otherwise.
// `regionOut` receives 0 = NTSC, 1 = PAL (auto-detected from the core).
std::string loadFromFile(const std::string& path, int& regionOut);

void unload();
void resetEmulation(bool hard);
void stepFrame();

// 读取并清零自上次轮询以来核心真实提交（cb_video 非空）的帧数。
// FPS HUD 每秒轮询一次，得到的是游戏真实输出帧率（区别于被
// 帧率限制器凑出来的步进频率）。
int pollPresentedFrames();

// Queue a libretro controller-port device switch (RETRO_DEVICE_JOYPAD /
// RETRO_DEVICE_ANALOG ...). Applied on the emulation thread at the next
// stepFrame() — never directly from the caller thread.
void setPortDevice(int port, int device);
double videoRefreshRate();

bool copyFramebufferARGB(uint32_t* out, int w, int h);
int  readAudio(int16_t* out, int maxFrames);

int audioSampleRate();
int audioTargetSampleRate();

// Push controller state. `bits` layout (12 buttons, same as SNES):
//   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down,
//   bit6=Left, bit7=Right, bit8=X, bit9=Y, bit10=L, bit11=R
// PCSX-ReARMed's libretro port maps these to the standard PS1 layout:
//   A = Cross  (or Circle in Japanese region — region-dependent confirm),
//   B = Circle (or Cross  in Japanese region — region-dependent cancel),
//   X = Triangle, Y = Square, L = L1, R = R1,
//   Select = Select, Start = Start.
// L2 / R2 / L3 / R3 are only available on DualShock / analog devices
// and require RETRO_DEVICE_ANALOG — not exposed via this 12-bit field.
// Supports up to 4 controllers via the standard 4 ports.
void setControllerInput(int port, uint16_t bits);

// Set system (BIOS files) and save (memory card .srm) directories.
void setPaths(const std::string& systemDir, const std::string& saveDir);

// Set an explicit .srm basename for the next ROM load.
void setSaveName(const std::string& name);

// Set the absolute path to libpcsx_rearmed_libretro_android.so for dlopen.
// Pass an empty string to revert to bare-name dlopen.
void setCoreLibPath(const std::string& path);

void applyRegion(int region);
void applySampleRate(int hz);
void applySpeed(float multiplier);

void saveStateToPath(int slot, const std::string& path);
bool loadStateFromPath(int slot, const std::string& path);

// --- Hardware-accelerated rendering ---
void setSurface(void* nativeWindow);

// --- Core options ---
void setCoreOption(const std::string& key, const std::string& value);

// --- Video geometry ---
int  videoWidth();
int  videoHeight();
void videoAspectRatio(int& num, int& den);

// --- Video filter (frontend post-processing) ---
void setVideoFilter(int filter);
void setHighQualityScaling(bool enabled);

// --- ★ 分辨率倍数（前端整数放大，参照 ARMSX upscale_multiplier 的前端实现）---
// PCSX-ReARMed 的 NEON 2x 增强仅对 ≤512x256 非 24 位色的 3D 画面生效且需
// 重进游戏；本接口在呈现层把核心输出按 1..4 整数倍最近邻放大后再上屏 ——
// 对任何画面（2D/CG/FMV/高分辨率模式）都立即生效，消除“糊成一片”的观感。
// scale: 1..4（超上限自动按帧尺寸收敛，见 cb_video）。
void setResolutionScale(int scale);

// Check whether libpcsx_rearmed_libretro_android.so was successfully dlopen()'d.
bool isCoreLoaded();

} // namespace psxcore::rom
