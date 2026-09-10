// SPDX-License-Identifier: MIT
// libretro frontend surface for the Flycast (Sega Dreamcast / Naomi /
// Atomiswave) core.
//
// flycast_loader wraps the standard libretro API (retro_init /
// retro_load_game / retro_run ...) behind a small, stable C++ interface
// that the JNI bridge in flycast_bridge.cpp calls. The prebuilt
// libflycast_libretro_android.so is dlopen()'d at runtime and the retro_*
// symbols resolved via dlsym() — same pattern as psx_loader.cpp.
//
// *** Hardware rendering ***
//
// Unlike the 2D cores (software frames via cb_video → ANativeWindow blit),
// Flycast is a GPU core: it renders the Dreamcast's PowerVR2 output through
// OpenGL ES 3 (via libretro's glsm state machine) and only signals "frame is
// in the FBO" through video_cb(RETRO_HW_FRAME_BUFFER_VALID, w, h, 0).
// This loader therefore implements the full frontend side of the libretro
// hardware-rendering protocol:
//
//   1. cb_environment handles RETRO_ENVIRONMENT_SET_HW_RENDER and accepts
//      the GLES context request (context_type OPENGLES2/OPENGLES3 — glsm
//      hardcodes OPENGLES2 for HAVE_OPENGLES builds but resolves every GL
//      symbol through our get_proc_address, so we create a real GLES 3.0
//      context; flycast's GL renderer requires ES3 on Android).
//      GET_PREFERRED_HW_RENDER returns OPENGLES3 so the core skips its
//      Vulkan/DX11 probes; Vulkan is explicitly rejected.
//   2. On the emulation thread (runFrame) we create an EGLContext (GLES 3,
//      depth 24 + stencil 8 — the core requests both) bound either to the
//      ANativeWindow from setSurface() or, when no Surface is available
//      yet, to a small pbuffer so captureFrame / fallback blits still work.
//   3. We provide hw_render.get_current_framebuffer (our own RGBA8 FBO with
//      depth24/stencil8, grown on demand to the size the core reports) and
//      hw_render.get_proc_address (eglGetProcAddress), then invoke the
//      core's context_reset().
//   4. After retro_run() returns we composite the core's FBO texture into
//      the window with a tiny GLES shader and eglSwapBuffers.
//
// Video resolution is dynamic (640x480 native up to the configured
// reicast_internal_resolution); the FBO and fallback frame buffer resize
// automatically. bottom_left_origin=true is honoured by the compositor's
// UV mapping.
//
// Audio: AICA outputs 44100 Hz stereo via audio_batch — same ring buffer +
// AudioTrack pull-model as the other cores.
//
// Input: 4 maple ports. Standard Dreamcast pad (RETRO_DEVICE_JOYPAD) plus
// RETRO_DEVICE_ANALOG queries for the thumb stick — flycast reads ANALOG
// LEFT X/Y regardless of the port device type, so the on-screen stick feeds
// setAnalogAxis() while the D-pad/face buttons go through the 16-bit mask.
// DC trigger buttons are JOYPAD_L2/R2 (bits 12/13); flycast converts those
// to full-pull analog triggers (or reads ANALOG_INDEX_BUTTON when the
// frontend provides it — we also report lt/rt through JOYPAD bits only,
// which the core handles with its digital-trigger fallback).
//
// BIOS / system files (looked up by the core inside <systemDir>/dc/):
//   dc_boot.bin   — Dreamcast boot ROM (required)
//   dc_flash.bin  — Dreamcast flash ROM (required)
//   naomi.zip     — Naomi BIOS set (Naomi games; MAME romset)
//   atomiswave.zip— Atomiswave BIOS set (MAME romset)
// The core also creates <systemDir>/dc/data/ for VMU / flash / eeprom
// writes, and stores per-game saves under <saveDir>/reicast/.
//
// All retro_* calls happen on a single emulation thread (see FlycastEngine
// in Kotlin); EGL objects are likewise created and used on that thread only.

#pragma once
#include <string>
#include <cstdint>

namespace flycastcore::rom {

// Load a game (path to .chd/.cdi/.gdi/.cue/.iso/.m3u CD/GD-ROM image, or a
// .zip/.7z/.lst/.bin/.dat Naomi/Atomiswave romset). The path is passed to
// the core by reference (retro_game_info.path) — the core opens the disc /
// archive itself, exactly like PCSX-ReARMed's CD handling.
// Returns an empty string on success, an error message otherwise.
// `regionOut` receives 0 = NTSC, 1 = PAL (auto-detected from the core).
std::string loadFromFile(const std::string& path, int& regionOut);

void unload();
void resetEmulation(bool hard);
void stepFrame();

// 读取并清零自上次轮询以来核心真实提交（cb_video 收到 RETRO_HW_FRAME_BUFFER_VALID）
// 的帧数。FPS HUD 每秒轮询一次，得到游戏真实输出帧率。
int pollPresentedFrames();

// Queue a libretro controller-port device switch. Flycast supports
// RETRO_DEVICE_JOYPAD (standard pad), RETRO_DEVICE_KEYBOARD, lightgun and
// twinstick device types. Applied on the emulation thread before the next
// stepFrame().
void setPortDevice(int port, int device);
double videoRefreshRate();

bool copyFramebufferARGB(uint32_t* out, int w, int h);
int  readAudio(int16_t* out, int maxFrames);

int audioSampleRate();
int audioTargetSampleRate();

// Push the digital button mask for one of the 4 maple ports.
// `bits` is the project UI layout (identical to PSX/PS2):
//   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down,
//   bit6=Left, bit7=Right, bit8=X, bit9=Y, bit10=L1, bit11=R1,
//   bit12=L2, bit13=R2, bit14=L3, bit15=R3
// The JNI bridge converts it to the libretro JOYPAD layout flycast expects:
//   screen A -> JOYPAD_B(bit0)  = DC A
//   screen B -> JOYPAD_A(bit8)  = DC B
//   screen X -> JOYPAD_Y(bit1)  = DC X
//   screen Y -> JOYPAD_X(bit9)  = DC Y
//   screen L -> JOYPAD_L2(bit12) = left analog trigger
//   screen R -> JOYPAD_R2(bit13) = right analog trigger
void setControllerInput(int port, uint16_t bits);

// Push an analog axis value (int16, -32768..32767) for one of the 4 ports.
// axis: 0 = LX, 1 = LY, 2 = RX, 3 = RY (libretro convention: right positive,
// down positive — same as the on-screen UI, no flip needed).
void setAnalogAxis(int port, int axis, int16_t value);

// Set system (dc_boot.bin / dc_flash.bin root) and save (per-game VMU /
// naomi eeprom) directories.
void setPaths(const std::string& systemDir, const std::string& saveDir);

// Set an explicit save basename for the next ROM load.
void setSaveName(const std::string& name);

// Set the absolute path to libflycast_libretro_android.so for dlopen.
// Pass an empty string to revert to bare-name dlopen.
void setCoreLibPath(const std::string& path);

void applyRegion(int region);
void applySampleRate(int hz);
void applySpeed(float multiplier);

void saveStateToPath(int slot, const std::string& path);
bool loadStateFromPath(int slot, const std::string& path);

// --- Hardware-accelerated rendering (EGL surface management) ---
// nativeWindow is an ANativeWindow* (extracted in the JNI bridge).
// Pass null to detach: the GL context is torn down (with the core's
// context_destroy callback) and later frames fall back to pbuffer +
// CPU readback so captureFrame keeps working.
void setSurface(void* nativeWindow);

// --- Core options (reicast_* — keys MUST match flycast's
//     libretro_core_options.h; prefix "reicast" per CORE_OPTION_NAME) ---
void setCoreOption(const std::string& key, const std::string& value);

// --- Video geometry ---
int  videoWidth();
int  videoHeight();
void videoAspectRatio(int& num, int& den);

// --- Video filter (frontend post-processing; no-op for the GPU path but
//     kept for interface parity with the other engines) ---
void setVideoFilter(int filter);
void setHighQualityScaling(bool enabled);

// Check whether libflycast_libretro_android.so was successfully dlopen()'d.
bool isCoreLoaded();

// True once the core accepted a hardware render context (SET_HW_RENDER OK).
bool isHwRenderAvailable();

// Ask the emulation thread to glReadPixels() the core FBO into the CPU
// frame buffer on the next stepFrame() — used by captureFrame (screenshots)
// when a window surface is attached (normally the GPU path never touches
// the CPU buffer). Call shortly before copyFramebufferARGB().
void requestFrameReadback();

} // namespace flycastcore::rom
