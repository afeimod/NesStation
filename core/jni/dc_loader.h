// SPDX-License-Identifier: MIT
// libretro frontend surface for the SEGA Dreamcast / NAOMI / AtomisWave core
// (libretro Flycast).
//
// dc_loader wraps the standard libretro API (retro_init / retro_load_game /
// retro_run ...) behind a small, stable C++ interface that the JNI bridge in
// dc_bridge.cpp calls — the SAME integration pattern as psx_loader.cpp /
// fbneo_loader.cpp (dlopen the prebuilt core .so at runtime, resolve the
// retro_* symbols via dlsym).
//
// The core .so is the official libretro buildbot build
// (libflycast_libretro_android.so, shipped in app/src/main/jniLibs/<abi>/).
//
// Flycast is a HARDWARE-RENDERING core (OpenGL ES 3): it renders the final
// frame into the framebuffer provided by get_current_framebuffer() and
// signals completion via video_cb(RETRO_HW_FRAME_BUFFER_VALID, ...). The
// frontend therefore must:
//   * accept RETRO_ENVIRONMENT_SET_HW_RENDER (GLES 3 context request),
//   * create an EGL context + window surface bound to the ANativeWindow,
//   * provide an FBO for the core to render into,
//   * after each retro_run() blit the FBO to the window backbuffer (scaled)
//     and call eglSwapBuffers().
// We steer the core away from its Vulkan backend via
// RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER → RETRO_HW_CONTEXT_OPENGLES3,
// so only the GL path is exercised (same choice as RetroArch with a GL video
// driver).
//
// BIOS / system files (dc_boot.bin, dc_flash.bin, naomi.zip, awbios.zip ...)
// are looked up by the core in the system directory set via setPaths() —
// the standard libretro mechanism, same as PCSX-ReARMed / Genesis-Plus-GX.

#pragma once
#include <string>
#include <cstdint>

namespace dccore::rom {

// Load a Dreamcast / NAOMI / AtomisWave game.
//   Dreamcast disc images: .gdi / .cdi / .cue / .chd / .iso / .lst (multi-file
//   images MUST stay on a real filesystem — passed by path, the core opens
//   them itself to parse the TOC / track layout).
//   NAOMI / AtomisWave / system SP arcade sets: .zip (passed by path).
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

// Queue a libretro controller-port device switch. Applied on the emulation
// thread at the next stepFrame() — never directly from the caller thread.
void setPortDevice(int port, int device);
double videoRefreshRate();

// Pull the latest frame into `out` (w*h uint32, 0xAARRGGBB).
// In HW-render mode this returns the last captured (glReadPixels) frame;
// capture is scheduled inside stepFrame() right after retro_run().
bool copyFramebufferARGB(uint32_t* out, int w, int h);

// Screenshot request — schedules a glReadPixels from the core's FBO on the
// emulation thread (requestFrameCapture), then waitForCapture blocks briefly
// for the result to land in the shared frame buffer. copyFramebufferARGB
// then returns the captured frame.
void requestFrameCapture();
bool waitForCapture(int timeoutMs);

int  readAudio(int16_t* out, int maxFrames);

int audioSampleRate();
int audioTargetSampleRate();

// Push controller state. `bits` layout (12 buttons, standard libretro
// JOYPAD ids after the Kotlin-side dcToLibretroLayout() conversion):
//   bit0=A, bit1=X, bit2=Select, bit3=Start, bit4=Up, bit5=Down,
//   bit6=Left, bit7=Right, bit8=B, bit9=Y, bit10=L, bit11=R
// (flycast maps: JOYPAD_B→DC A, JOYPAD_A→DC B, JOYPAD_Y→DC X,
//  JOYPAD_X→DC Y, L/R→DC analog triggers, Select→"D", Start→Start.)
// Supports up to 4 controllers via the standard 4 ports.
void setControllerInput(int port, uint16_t bits);

// Set system (BIOS files: dc_boot.bin / dc_flash.bin / naomi.zip / awbios.zip)
// and save (VMU vmu_save_*.bin / nvmem / .state) directories.
void setPaths(const std::string& systemDir, const std::string& saveDir);

// Set an explicit VMU / nvmem basename for the next ROM load.
void setSaveName(const std::string& name);

// Set the absolute path to libflycast_libretro_android.so for dlopen.
// Pass an empty string to revert to bare-name dlopen.
void setCoreLibPath(const std::string& path);

void applyRegion(int region);
void applySampleRate(int hz);
void applySpeed(float multiplier);

void saveStateToPath(int slot, const std::string& path);
bool loadStateFromPath(int slot, const std::string& path);

// --- Hardware-accelerated rendering (EGL / GLES3) ---
void setSurface(void* nativeWindow);

// --- Core options (reicast_* — keys MUST match the core's
//     libretro_core_options.h exactly) ---
void setCoreOption(const std::string& key, const std::string& value);

// --- Video geometry ---
int  videoWidth();
int  videoHeight();
void videoAspectRatio(int& num, int& den);

// --- Video filter (frontend post-processing; only used when the core runs
//     in software-video fallback — in HW mode the GL blit handles scaling) ---
void setVideoFilter(int filter);
void setHighQualityScaling(bool enabled);

// Check whether libflycast_libretro_android.so was successfully dlopen()'d.
bool isCoreLoaded();

} // namespace dccore::rom
