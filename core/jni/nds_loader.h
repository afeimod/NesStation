// SPDX-License-Identifier: MIT
// libretro frontend surface for the melonDS (Nintendo DS) core.
//
// nds_loader wraps the standard libretro API (retro_init /
// retro_load_game / retro_run ...) behind a small, stable C++ interface
// that the JNI bridge in nds_bridge.cpp calls. The melonDS libretro
// wrapper (core/melonds/libretro/libretro.cpp) is compiled directly into
// the binary via CMake — no dlopen/dlsym needed.
//
// Features:
//   * Directly linked to melonDS libretro wrapper (no dlopen)
//   * 12-button DS gamepad (RETRO_DEVICE_JOYPAD) on ports 0-3 with the
//     standard DS layout: D-pad + A/B/X/Y + L/R + Start/Select. DS has
//     the same 12-button layout as SNES.
//   * DS touchscreen input via RETRO_DEVICE_POINTER (setTouchInput).
//     Coordinates are 16-bit signed in [-0x8000, 0x7FFF] mapping to the
//     composited frame buffer, matching the melonDS core Touch mode.
//   * DS touchscreen input via direct bottom-screen pixel coordinates
//     (setTouchInputDirect, 0..255 / 0..191) — the same architecture as the
//     official melonDS Android frontend. Preferred for every layout except
//     Hybrid, whose on-screen geometry is only known to the core.
//   * Filtered (upscaled) frame output for the custom dual-screen layout:
//     when no ANativeWindow is attached, cb_video runs the CPU upscale
//     filters (HQ2X / HQ4X / XBR) into an internal buffer that Kotlin pulls
//     via copyFilteredFramebufferARGB() / filteredWidth() / filteredHeight().
//   * Monotonic frame stamp (frameStamp) so the Kotlin view only redraws
//     when a new frame actually arrived.
//   * 3D rendering resolution scaling via melonds_resolution_scale
//     (1x = native 256x192, up to 8x). Only affects OpenGL/Compute
//     renderers.
//   * Fixed 256x192 per-screen frame buffer; default layout stacks both
//     screens vertically (256x384). melonDS's libretro port renders the
//     two-screen layout into a single 256x384 (or 512x192 horizontal)
//     framebuffer, so the frontend sees one combined buffer.
//   * Stereo audio ring buffer + resampler (shared implementation)
//   * Core options variable system (melonds_* — keys MUST match melonDS's
//     libretro_core_options.h exactly).
//   * Pixel format conversion (XRGB8888 / RGB565 / 0RGB1555 -> ARGB)
//   * Hardware-accelerated rendering via ANativeWindow (SurfaceView)
//   * BIOS file lookup in the system directory — melonDS requires
//     bios7.bin / bios9.bin / firmware.bin for NDS mode, plus additional
//     dsi_* files for DSi mode. The core reports BIOS-load errors itself;
//     the frontend does NOT pre-check for these files.
//   * Save RAM persistence via RETRO_MEMORY_SAVE_RAM (SRAM .srm alongside
//     the save state directory).
//
// All retro_* calls happen on a single emulation thread (see NdsEngine
// in Kotlin), so no extra internal locking is needed around the core
// itself. The frame and audio buffers are mutex-guarded because the UI /
// AudioTrack threads read them concurrently.

#pragma once
#include <string>
#include <cstdint>

namespace ndscore::rom {

// Load a DS game (path to .nds / .ids / .app / .srl / .dsi ROM file).
// DS ROMs are small (max 512 MB for the largest commercial carts), so they
// are pre-loaded into memory and passed to retro_load_game via data + size
// (standard libretro behavior for non-CD content).
// Returns an empty string on success, an error message otherwise.
// `regionOut` receives 0 = NTSC, 1 = PAL (DS is region-free; this is
// always 0 — DS has no region concept — but the field is kept for API
// symmetry with the other cores).
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

// --- Filtered frame for the custom dual-screen layout (videoScale ==
//     "custom", no ANativeWindow). Returns the upscaled frame when an
//     upscale filter (HQ2X / HQ4X / XBR) is active, the raw frame
//     otherwise. filteredWidth/Height report the matching dimensions.
bool copyFilteredFramebufferARGB(uint32_t* out, int w, int h);
int  filteredWidth();
int  filteredHeight();

// Monotonic counter of frames delivered through cb_video. Kotlin polls
// this to skip redundant redraws on high-refresh displays.
uint64_t frameStamp();

int audioSampleRate();
int audioTargetSampleRate();

// Push controller state. `bits` layout (12 buttons, same as SNES):
//   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down,
//   bit6=Left, bit7=Right, bit8=X, bit9=Y, bit10=L, bit11=R
// melonDS's libretro port maps these 1:1 to the DS hardware buttons:
//   A=A, B=B, X=X, Y=Y, L=L, R=R, Select=Select, Start=Start.
// Touchscreen / microphone / lid-close are NOT exposed via this 12-bit
// field (would require RETRO_DEVICE_POINTER + RETRO_DEVICE_MIC).
// Supports up to 4 controllers (DS Download Play can support up to 16,
// but only pads 1-4 are exposed here).
void setControllerInput(int port, uint16_t bits);

// Touchscreen input via RETRO_DEVICE_POINTER.
// x, y: 0..0xFFFF (normalized to DS screen coordinates by the core).
// pressed: 0 = released, non-zero = touched.
void setTouchInput(int x, int y, bool pressed);

// Touchscreen input with DIRECT bottom-screen pixel coordinates
// (x: 0..255, y: 0..191) — the official melonDS Android architecture.
// The core applies these verbatim (clamped), bypassing the composite-frame
// coordinate round-trip entirely. Robust against layout / screen-gap /
// GL-gap / custom-layout geometry mismatches.
void setTouchInputDirect(int x, int y, bool pressed);

// Set system (BIOS files) and save (SRAM .srm) directories.
void setPaths(const std::string& systemDir, const std::string& saveDir);

// Set an explicit .srm basename for the next ROM load.
void setSaveName(const std::string& name);

void applyRegion(int region);
void applySampleRate(int hz);
void applySpeed(float multiplier);

// 返回 false 表示存档失败（serialize 出错/无法写文件）——前端据此提示用户，
// 避免“显示保存成功但文件是假档”的体验。
bool saveStateToPath(int slot, const std::string& path);
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

// Check whether the melonDS libretro wrapper is available.
// Always returns true because the core is statically linked.
bool isCoreLoaded();

} // namespace ndscore::rom
