// SPDX-License-Identifier: MIT
// libretro frontend surface for the SNES9x core.
//
// snes_loader wraps the standard libretro API (retro_init / retro_load_game /
// retro_run ...) behind a small, stable C++ interface that the JNI bridge in
// snes_bridge.cpp calls. Video frames can be delivered in two ways:
//   1. Hardware-accelerated: directly to an ANativeWindow (SurfaceView).
//      The core's video callback blits the framebuffer into the surface buffer,
//      eliminating the JNI copy + Compose Canvas redraw overhead.
//   2. Fallback: copied into a dynamically-sized ARGB int array for Bitmap
//      rendering. SNES resolution varies (256x224 standard, up to 512x478 in
//      high-resolution/interlaced modes), so the frame buffer uses a vector
//      that is resized on demand.
//
// Core options (aspect ratio, overclock, layers, audio, etc.) are stored
// in a key-value map and served to the core via RETRO_ENVIRONMENT_GET_VARIABLE.
//
// Shared blit / XBR / audio ring buffer code is provided by core_shared.h,
// so this file does NOT reimplement those utilities.
#pragma once
#include <string>
#include <cstdint>

namespace snescore::rom {

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
// The SNES frame buffer has variable dimensions (s_frameW x s_frameH); this
// function copies the intersection of (w,h) and the actual frame size, leaving
// any remaining area in `out` untouched (callers should pre-clear if needed).
// Returns true if a fresh frame was produced since the previous call.
bool copyFramebufferARGB(uint32_t* out, int w, int h);

// Pull up to `maxFrames` stereo frames (2 int16 each) into `out`, zero-filling
// on underrun. Returns the number of *real* stereo frames written.
int readAudio(int16_t* out, int maxFrames);

// Sample rate reported by the core after load (e.g. 32040). 0 before load.
int audioSampleRate();

// Target output sample rate for Android AudioTrack (48000 Hz).
// Audio is resampled from the SNES native rate (~32040 Hz) to this rate in
// readAudio(), matching the GB/GBC/GBA core. Using the SNES native rate
// directly with AudioTrack causes poor-quality resampling in AudioFlinger
// on TV boxes (HDMI output always runs at 48000 Hz), producing
// buzzing/crackling/muffled audio.
int audioTargetSampleRate();

// Push controller state. `bits` layout (12 buttons):
//   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down,
//   bit6=Left, bit7=Right, bit8=X, bit9=Y, bit10=L, bit11=R
// Supports dual controllers: port 0 (pad 1) and port 1 (pad 2).
void setControllerInput(int port, uint16_t bits);

// Filesystem directories the core uses for BIOS/cheats (system) and SRAM (save).
void setPaths(const std::string& systemDir, const std::string& saveDir);

// Set an explicit .srm basename for the next ROM load.
// Pass a stable game identifier (e.g. "ff6", or the game's DB id) when the
// ROM is loaded from a content:// URI and copied to a shared temp file —
// this prevents different games from sharing the same temp_rom.srm.
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

// Set a core option value by key (e.g. "snes9x_aspect" -> "4:3").
// Triggers RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE so the core picks up changes.
void setCoreOption(const std::string& key, const std::string& value);

// --- Video geometry --------------------------------------------------------

// Returns the current video width reported by the core (e.g. 256 or 512).
int videoWidth();

// Returns the current video height reported by the core (e.g. 224 or 478).
int videoHeight();

// Returns the aspect ratio numerator/denominator (default 4:3 for SNES).
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

} // namespace snescore::rom
