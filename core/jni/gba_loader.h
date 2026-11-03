// SPDX-License-Identifier: MIT
// libretro frontend surface for the mGBA core (GB/GBC/GBA).
//
// gba_loader wraps the standard libretro API (retro_init / retro_load_game /
// retro_run ...) behind a small, stable C++ interface that the JNI bridge in
// gba_bridge.cpp calls. Video frames can be delivered in two ways:
//   1. Hardware-accelerated: directly to an ANativeWindow (SurfaceView).
//      The core's video callback blits the framebuffer into the surface buffer,
//      eliminating the JNI copy + Compose Canvas redraw overhead.
//   2. Fallback: copied into a dynamic ARGB int array for Bitmap rendering.
//
// Video resolution is dynamic (GB/GBC = 160x144, GBA = 240x160), so the
// internal frame buffer uses a std::vector that resizes as needed.
//
// Core options (GB model, colors, frameskip, audio resampler, etc.) are
// stored in a key-value map and served to the core via GET_VARIABLE.
// Shared filter/blit/audio code comes from shared/core_shared.h.
#pragma once
#include <string>
#include <cstdint>

namespace gbacore::rom {

// Load a ROM. Returns an empty string on success, an error message otherwise.
// `regionOut` receives 0 = NTSC, 1 = PAL (auto-detected from the core).
std::string loadFromFile(const std::string& path, int& regionOut);

// Tear down the current game + core.
void unload();

// v69 全核心金手指：取核心当前暴露的 SYSTEM_RAM / SAVE_RAM 区域指针。
// 指针归核心所有，仅在 emulation 线程的帧回调内使用；未加载游戏时两组均
// 返回 NULL/0（调用方按越界跳过处理）。见 shared/cheat_engine.h。
void cheatRamRegions(void** sysRam, size_t* sysSize,
                     void** saveRam, size_t* saveSize);


// Soft/hard reset (libretro only exposes retro_reset()).
void resetEmulation(bool hard);

// Emulate exactly one frame. Fills the internal ARGB frame buffer and pushes
// audio into the ring buffer. Must be called on a single dedicated thread.
// If a Surface is set, the frame is also blitted directly to the ANativeWindow.
void stepFrame();

// Copy the latest produced frame into `out` (w*h uint32 entries, 0xAARRGGBB).
// w/h are the destination dimensions; the actual source frame may be smaller
// (e.g. 160x144 for GB). The output is zero-padded if the source is smaller.
// Returns true if a fresh frame was produced since the previous call.
bool copyFramebufferARGB(uint32_t* out, int w, int h);

// Pull up to `maxFrames` stereo frames (2 int16 each) into `out`, zero-filling
// on underrun. Returns the number of *real* stereo frames written.
int readAudio(int16_t* out, int maxFrames);

// Sample rate reported by the core after load (e.g. 32768 for GB, 65536 for GBA).
// 0 before load.
int audioSampleRate();

// Target output sample rate for Android AudioTrack (48000 Hz).
// Audio is resampled from the core's native rate to this rate in readAudio().
// This matches the mGBA Android reference project which uses 48000 Hz for Oboe.
int audioTargetSampleRate();

// Push controller state. `bits` layout:
//   bit0 A, bit1 B, bit2 Select, bit3 Start,
//   bit4 Up, bit5 Down, bit6 Left, bit7 Right,
//   bit8 L (GBA only), bit9 R (GBA only)
// GB/GBC ignores bit8/bit9; GBA uses all 10 buttons.
// Uses uint16_t (instead of NES's uint8_t) to accommodate the extra L/R bits.
void setControllerInput(int port, uint16_t bits);

// Filesystem directories the core uses for BIOS (system) and SRAM (save).
void setPaths(const std::string& systemDir, const std::string& saveDir);

// Set an explicit .srm basename for the next ROM load.
// Pass a stable game identifier (e.g. "pokemon_emerald", or the game's DB id)
// when the ROM is loaded from a content:// URI and copied to a shared temp
// file — this prevents different games from sharing the same temp_rom.srm.
// Pass an empty string to revert to deriving the .srm name from the ROM path.
void setSaveName(const std::string& name);

// Flush the current SAVE_RAM buffer to disk immediately. Safe to call while
// the core is loaded (no-op otherwise). The frontend calls this periodically
// (and when the game is paused / backgrounded) so in-game saves survive an
// app kill/crash — otherwise SRAM is only persisted in unload().
void flushSave();

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

// Set a core option value by key (e.g. "mgba_gb_model" -> "Autodetect").
// Triggers RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE so the core picks up changes.
void setCoreOption(const std::string& key, const std::string& value);

// --- Video geometry --------------------------------------------------------

// Returns the current video width (160 for GB/GBC, 240 for GBA).
int videoWidth();

// Returns the current video height (144 for GB/GBC, 160 for GBA).
int videoHeight();

// Returns the aspect ratio numerator/denominator from core geometry.
// e.g. for GB/GBC 10:9, for GBA 3:2.
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
void setHighQualityScaling(bool enabled);

} // namespace gbacore::rom
