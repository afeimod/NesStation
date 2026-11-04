// SPDX-License-Identifier: MIT
// libretro frontend surface for the FBNeo arcade core.
//
// fbneo_loader wraps the standard libretro API (retro_init /
// retro_load_game / retro_run ...) behind a small, stable C++ interface
// that the JNI bridge in fbneo_bridge.cpp calls. The prebuilt
// libfbneo_libretro_android.so is dlopen()'d at runtime and the retro_*
// symbols resolved via dlsym() — same pattern as dos_loader.cpp.
//
// Features:
//   * Runtime loading of prebuilt libfbneo_libretro_android.so
//   * 6-button arcade gamepad input (RETRO_DEVICE_JOYPAD) on port 0
//     with the standard FBNeo layout: A/B/X/Y/L/R + D-pad + Start/Select/Coin
//   * Dynamic ARGB frame buffer (arcade resolutions vary: 224x256, 256x224,
//     320x240, 384x224, 512x448, etc.)
//   * Stereo audio ring buffer + resampler (shared implementation)
//   * Core options variable system (fbneo_*)
//   * Pixel format conversion (XRGB8888 / RGB565 / 0RGB1555 -> ARGB)
//   * Hardware-accelerated rendering via ANativeWindow (SurfaceView)
//   * BIOS file lookup in the system directory (neogeo.zip, pgm.zip,
//     cvs2.zip, etc.) — FBNeo looks for these by filename next to the
//     ROM zip or in the system dir.
//
// All retro_* calls happen on a single emulation thread (see FbNeoEngine
// in Kotlin), so no extra internal locking is needed around the core
// itself. The frame and audio buffers are mutex-guarded because the UI /
// AudioTrack threads read them concurrently.

#pragma once
#include <string>
#include <cstdint>

namespace fbneocore::rom {

// Load an arcade ROM (path to .zip or .7z).
// Returns an empty string on success, an error message otherwise.
// `regionOut` receives 0 = NTSC, 1 = PAL (auto-detected from the core).
std::string loadFromFile(const std::string& path, int& regionOut);

void unload();
void resetEmulation(bool hard);
void stepFrame();

bool copyFramebufferARGB(uint32_t* out, int w, int h);
int  readAudio(int16_t* out, int maxFrames);

int audioSampleRate();
int audioTargetSampleRate();

// Push controller state. `bits` layout (12 buttons, same as SNES):
//   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down,
//   bit6=Left, bit7=Right, bit8=X, bit9=Y, bit10=L, bit11=R
// FBNeo's libretro frontend maps these to the standard arcade layout:
//   A = Low kick / Button 1, B = Medium kick / Button 2,
//   X = High punch / Button 3, Y = High kick / Button 4,
//   L = Button 5, R = Button 6, Select = Coin, Start = Start.
// Supports dual controllers: port 0 (pad 1) and port 1 (pad 2).
void setControllerInput(int port, uint16_t bits);

// Set system (BIOS ROMs) and save (high-score NVRAM) directories.
void setPaths(const std::string& systemDir, const std::string& saveDir);

// Set an explicit .srm basename for the next ROM load.
void setSaveName(const std::string& name);

// Set the absolute path to libfbneo_libretro_android.so for dlopen.
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

// --- v71 核心选项定义查询（金手指 / PGM2 插卡） ---
// 返回捕获的 fbneo-cheat-* / fbneo-pgm2-ic-* 选项定义 JSON 数组：
//   [{"key":...,"desc":...,"default":...,"values":[{"v":...,"l":...},...]},...]
// 游戏未加载 / 核心未注册选项时返回空串。
std::string getCheatOptionsJson();
void clearCapturedOptions();

// --- Video geometry ---
int  videoWidth();
int  videoHeight();
void videoAspectRatio(int& num, int& den);

// --- Video filter (frontend post-processing) ---
void setVideoFilter(int filter);
void setHighQualityScaling(bool enabled);

// Check whether libfbneo_libretro_android.so was successfully dlopen()'d.
bool isCoreLoaded();

} // namespace fbneocore::rom
