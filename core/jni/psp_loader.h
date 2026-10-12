// SPDX-License-Identifier: MIT
// libretro frontend surface for the Sony PSP core (libretro PPSSPP port).
//
// psp_loader wraps the standard libretro API (retro_init / retro_load_game /
// retro_run ...) behind a small, stable C++ interface that the JNI bridge in
// psp_bridge.cpp calls — the SAME integration pattern as dc_loader.cpp /
// psx_loader.cpp (dlopen the prebuilt core .so at runtime, resolve the
// retro_* symbols via dlsym).
//
// ★★ 集成架构说明（v3 — libretro 化重写）★★
//
// 旧版集成 dlopen 官方 libppsspp_jni.so 并驱动其 JNI 契约（模拟
// PpssppActivity）—— 与 DC/PSX 等核心的 libretro 模式完全异构，导致：
//   · PPSSPP 自带虚拟按键覆盖层（宿主触摸直通）与 NesStation 统一手柄冲突；
//   · 画面方向/呈现由 PPSSPP 内部 Vulkan 循环决定，宿主无法管理；
//   · 设置走 ppsspp.ini 直写，与主设置面板割裂；
//   · JNI 契约脆弱（JNI_OnLoad / registerCallbacks / ContentUri …连环雷）。
// 本版彻底重写为 libretro 前端 —— 与 flycast / pcsx-rearmed 完全同构：
//   · 输入走 retro_set_input_state（标准 libretro JOYPAD 布局，宿主统一
//     虚拟按键/摇杆直供，无 PPSSPP 覆盖层）；
//   · 画面走 EGL/GLES3 HW render（FBO + blit，宿主全权管理 Surface）；
//   · 设置走 retro 变量（ppsspp_* 核心选项，与主设置面板打通）；
//   · 无任何 Java 契约依赖（org.ppsspp.ppsspp 包与 libppsspp_jni.so 已移除）。
//
// The core .so is the official libretro buildbot build
// (libppsspp_libretro_android.so, shipped in app/src/main/jniLibs/<abi>/;
// content extensions: elf|iso|cso|prx|pbp|chd).
//
// PPSSPP is a HARDWARE-RENDERING core (OpenGL ES via LibretroGLContext): it
// renders the final frame into the framebuffer provided by
// get_current_framebuffer() and signals completion via
// video_cb(RETRO_HW_FRAME_BUFFER_VALID, ...). The frontend therefore must:
//   * accept RETRO_ENVIRONMENT_SET_HW_RENDER (the core requests
//     RETRO_HW_CONTEXT_OPENGL; on Android we satisfy it with an ES3 context
//     — identical to RetroArch's GL video driver on Android),
//   * create an EGL context + window surface bound to the ANativeWindow,
//   * provide an FBO for the core to render into,
//   * after each retro_run() blit the FBO to the window backbuffer (scaled)
//     and call eglSwapBuffers().
//
// System / save directories (standard libretro mechanism):
//   * GET_SYSTEM_DIRECTORY → <systemDir>: the core derives its asset base as
//     <systemDir>/PPSSPP/ (VFS root: ppge_atlas.zim / lang/ / compat.ini …)
//     and flash0 as <systemDir>/PPSSPP/flash0/ (in-game PSP fonts *.pgf).
//     NesStation seeds both from app assets "ppsspp/" on first run
//     (PspSupport.ensurePpssppEnvironment).
//   * GET_SAVE_DIRECTORY → <saveDir>: used as the memstick root — the core
//     creates memstick/PSP/{SAVEDATA,SYSTEM,GAME,…} inside it (standard
//     PPSSPP tree), i.e. saves land in <saveDir>/PSP/SAVEDATA.
//
// No BIOS is required for PSP content.

#pragma once
#include <string>
#include <cstdint>

namespace pspcore::rom {

// Load a PSP game (.iso / .cso / .pbp / .elf / .chd / .prx). The game is
// passed BY PATH (the core opens it itself — PBP/CSO are streamed).
// Returns an empty string on success, an error message otherwise.
// `regionOut` receives 0 = NTSC, 1 = PAL (from the core's reported timing).
std::string loadFromFile(const std::string& path, int& regionOut);

void unload();
void resetEmulation(bool hard);
void stepFrame();

// 读取并清零自上次轮询以来核心真实提交（cb_video 非空）的帧数。
// FPS HUD 每秒轮询一次，得到的是游戏真实输出帧率。
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
// for the result to land in the shared frame buffer.
void requestFrameCapture();
bool waitForCapture(int timeoutMs);

int readAudio(int16_t* out, int maxFrames);

int audioSampleRate();
int audioTargetSampleRate();

// Push controller state. `bits` layout (standard libretro JOYPAD ids after
// the Kotlin-side ps2ToLibretroLayout() conversion — PSP face buttons share
// the PlayStation label semantics with PS2):
//   bit0=✕(Cross, libretro B), bit1=□(Square, Y), bit2=Select, bit3=Start,
//   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
//   bit8=○(Circle, A), bit9=△(Triangle, X), bit10=L, bit11=R
// Single-player handheld — ports 1..3 accepted but unused by games.
void setControllerInput(int port, uint16_t bits);

// Push analog stick state for one controller port (libretro int16 axes,
// −32768..32767, 0 = center). Slot order: [LX, LY, RX, RY]. PSP only has a
// left stick; right-stick values are accepted and polled but unbound.
void setControllerAnalog(int port, int16_t lx, int16_t ly, int16_t rx, int16_t ry);

// Set system (PPSSPP asset base: <systemDir>/PPSSPP/) and save (memstick
// root — the core creates PSP/SAVEDATA etc. inside) directories.
void setPaths(const std::string& systemDir, const std::string& saveDir);

// Set an explicit save basename for the next ROM load (UI hint only).
void setSaveName(const std::string& name);

// Set the absolute path to libppsspp_libretro_android.so for dlopen.
// Pass an empty string to revert to bare-name dlopen.
void setCoreLibPath(const std::string& path);

void applyRegion(int region);
void applySampleRate(int hz);
void applySpeed(float multiplier);

void saveStateToPath(int slot, const std::string& path);
bool loadStateFromPath(int slot, const std::string& path);

// --- Hardware-accelerated rendering (EGL / GLES3) ---
void setSurface(void* nativeWindow);

// --- Core options (ppsspp_* — keys MUST match the core's option ids
//     exactly; wrong keys are silently ignored by the core) ---
void setCoreOption(const std::string& key, const std::string& value);

// --- Video geometry ---
int  videoWidth();
int  videoHeight();
void videoAspectRatio(int& num, int& den);

// --- Video filter (frontend post-processing; only used when the core runs
//     in software-video fallback — in HW mode the GL blit handles scaling) ---
void setVideoFilter(int filter);
void setHighQualityScaling(bool enabled);

// Check whether libppsspp_libretro_android.so was successfully dlopen()'d.
bool isCoreLoaded();

} // namespace pspcore::rom
