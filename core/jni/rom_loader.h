// SPDX-License-Identifier: MIT
// libretro frontend surface for the FCEUmm core.
//
// rom_loader wraps the standard libretro API (retro_init / retro_load_game /
// retro_run ...) behind a small, stable C++ interface that the JNI bridge in
// bridge.cpp calls. Video frames can be delivered in two ways:
//   1. Hardware-accelerated: directly to an ANativeWindow (SurfaceView).
//      The core's video callback blits the framebuffer into the surface buffer,
//      eliminating the JNI copy + Compose Canvas redraw overhead.
//   2. Fallback: copied into a 256x240 ARGB int array for Bitmap rendering.
//
// Core options (NTSC filter, aspect ratio, palette, region, etc.) are stored
// in a key-value map and served to the core via RETRO_ENVIRONMENT_GET_VARIABLE.
#pragma once
#include <string>
#include <cstdint>

namespace nescore::rom {

// Load a ROM. Returns an empty string on success, an error message otherwise.
// `regionOut` receives 0 = NTSC, 1 = PAL (auto-detected from the core).
std::string loadFromFile(const std::string& path, int& regionOut);

// Tear down the current game + core.
void unload();

// v69 全核心金手指（NES）：把 coreshared::g_cheatList 里的补丁逐字节打到
// fceux 的 CPU 总线上（FCEU_CheatGetByte/SetByte —— fceux 自家金手指引擎
// 用的同一对入口，ARead/BWrite 表自动处理所有 mapper 的地址译码）。
// 在每次 stepFrame 之前由 bridge 调用；列表为空时立刻返回（零开销）。
void applyCheatFrame();

// Soft/hard reset (libretro only exposes retro_reset()).
void resetEmulation(bool hard);

// Emulate exactly one frame. Fills the internal ARGB frame buffer and pushes
// audio into the ring buffer. Must be called on a single dedicated thread.
// If a Surface is set, the frame is also blitted directly to the ANativeWindow.
void stepFrame();

// Copy the latest produced frame into `out` (w*h uint32 entries, 0xAARRGGBB).
// Returns true if a fresh frame was produced since the previous call.
bool copyFramebufferARGB(uint32_t* out, int w, int h);

// Pull up to `maxFrames` stereo frames (2 int16 each) into `out`, zero-filling
// on underrun. Returns the number of *real* stereo frames written.
int readAudio(int16_t* out, int maxFrames);

// Sample rate reported by the core after load (e.g. 44100). 0 before load.
int audioSampleRate();

// Target output sample rate for Android AudioTrack (48000 Hz).
// Audio is resampled from the core's native rate (typically 44100 Hz for
// FCEUmm) to this rate in readAudio(), matching the GB/GBC/GBA core.
// Using the core's native rate directly with AudioTrack causes poor-quality
// resampling in AudioFlinger on TV boxes (HDMI output always runs at
// 48000 Hz), producing buzzing/crackling/muffled audio.
int audioTargetSampleRate();

// Push controller state. `bits` layout:
//   bit0 A, bit1 B, bit2 Select, bit3 Start, bit4 Up, bit5 Down, bit6 Left, bit7 Right
void setControllerInput(int port, uint8_t bits);

// Filesystem directories the core uses for FDS BIOS (system) and SRAM (save).
void setPaths(const std::string& systemDir, const std::string& saveDir);

// Set an explicit .srm basename for the next ROM load.
// Pass a stable game identifier (e.g. "pokemon_emerald", or the game's DB id)
// when the ROM is loaded from a content:// URI and copied to a shared temp
// file — this prevents different games from sharing the same temp_rom.srm.
// Pass an empty string to revert to deriving the .srm name from the ROM path.
void setSaveName(const std::string& name);

// Region / sample-rate / speed hints. Region is auto-detected at load; the
// core fixes the audio sample rate, so these are best-effort and mostly no-ops
// kept for ABI compatibility with the bridge.
void applyRegion(int region);
void applySampleRate(int hz);
void applySpeed(float multiplier);

// Save / load a state to an absolute filesystem path.
void saveStateToPath(int slot, const std::string& path);
bool loadStateFromPath(int slot, const std::string& path);

// --- Hardware-accelerated rendering ---------------------------------------

// Set the ANativeWindow for direct framebuffer blitting. Pass nullptr to
// detach (e.g. when the SurfaceView is destroyed).
// The window is acquired (ANativeWindow_acquire) internally; the caller does
// NOT need to hold a reference after calling this.
void setSurface(void* nativeWindow);

// --- Core options ----------------------------------------------------------

// Set a core option value by key (e.g. "fceumm_ntsc_filter" -> "composite").
// Triggers RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE so the core picks up changes.
void setCoreOption(const std::string& key, const std::string& value);

// --- Video geometry --------------------------------------------------------

// Returns the current video width reported by the core (e.g. 256 or 302 for NTSC).
int videoWidth();

// Returns the current video height reported by the core (e.g. 240).
int videoHeight();

// Returns the aspect ratio numerator/denominator from core geometry.
// e.g. for 4:3, num=4, den=3.
void videoAspectRatio(int& num, int& den);

// --- Video filter (frontend post-processing) -------------------------------

// Set the video filter type applied during surface blitting.
//   0 = none (nearest-neighbor)
//   1 = scanline
//   2 = CRT (scanline + vignette)
//   3 = dot (LCD dot grid)
//   4 = XBR (edge-preserving smooth scaling)
void setVideoFilter(int filter);

// Control the native surface buffer geometry for performance vs quality.
// - false (default): buffer = source resolution (256x240), fast 1:1 blit + GPU upscale
// - true: buffer = display resolution, sharp C++ per-pixel scale (heavier CPU)
void setHighQualityScaling(bool enabled);

} // namespace nescore::rom
