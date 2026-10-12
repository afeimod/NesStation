// SPDX-License-Identifier: MIT
// PPSSPP (Sony PSP) host driver — NesStation in-process integration.
//
// Drives the prebuilt official libppsspp_jni.so (PPSSPP v1.20.4, all three
// ABIs) through its exported JNI symbols — the exact same contract the
// official PpssppActivity uses (NativeApp.init / runVulkanRenderLoop /
// SaveState::*). No PPSSPP headers are needed: everything is resolved via
// dlopen()/dlsym() at runtime, so the vendored org.ppsspp.ppsspp Java layer
// stays the single source of truth for signatures.
//
// Integration model (identical to psx_loader.cpp / dc_loader.cpp):
//   PspNative.kt  →  libpspcore.so (this file + psp_bridge.cpp)
//                 →  dlopen(nativeLibraryDir/libppsspp_jni.so)
//
// ★ dlopen-vs-System.loadLibrary 差异（PSP 闪退根因，本轮修复）★
//   官方宿主经 System.loadLibrary 加载核心，ART 自动回调 JNI_OnLoad
//   （gJvm 缓存 / gClassLoader / 线程 attach 钩子 / TimeInit）。dlopen
//   路径下这些初始化不会发生，PPSSPP 内部任何 getEnv()（渲染线程、IO
//   线程池……）都会解引用空 JavaVM → SIGSEGV。因此 boot() 在 dlopen 后
//   手动 dlsym("JNI_OnLoad") 并以 GetJavaVM 的指针调用 —— 见 psp_loader.cpp。
//
// Lifecycle:
//   boot()        — dlopen, manual JNI_OnLoad, NativeApp.audioConfig + init
//                   (boot_filename = quoted ROM path), display params,
//                   registerCallbacks with the Java host stub, audioInit.
//                   Forces the Vulkan backend via ppsspp.ini before init
//                   (the OpenGL "JavaGL" host path is not driven by this
//                   loader). A still-active previous session is fully torn
//                   down first (same order as PpssppActivity.onDestroy).
//   setSurface()  — start/stop the Vulkan render loop (PPSSPP spawns its
//                   own EmuThread internally; requestExitVulkanRenderLoop
//                   joins it).
//   unload()      — exit render loop, audioShutdown, NativeApp.shutdown,
//                   unregisterCallbacks. The .so stays resident (dlclose
//                   would strand core-owned threads); the next boot()
//                   re-runs JNI_OnLoad-free re-init on the resident image.
#pragma once

#include <jni.h>
#include <string>

namespace pspload {

// Display metrics gathered on the Kotlin side before boot.
struct HostParams {
    std::string model;                 // Build.MANUFACTURER + ":" + Build.MODEL
    int deviceType = 0;                // NativeApp.DEVICE_TYPE_MOBILE
    std::string langRegion;            // Locale language_country
    std::string apkPath;               // applicationInfo.sourceDir
    std::string dataDir;               // context.getFilesDir() (PPSSPP internal dir)
    std::string externalStorageDir;    // Environment.getExternalStorageDirectory()
    std::string externalFilesDir;      // context.getExternalFilesDir(null)
    std::string nativeLibDir;          // applicationInfo.nativeLibraryDir
    std::string additionalStorageDirs; // ':'-joined SD card paths ("" ok)
    std::string cacheDir;              // context.getCacheDir()
    std::string shortcutParam;         // quoted ROM path (boot_filename)
    std::string installerName;         // package installer ("nesstation")
    int androidVersion = 0;            // Build.VERSION.SDK_INT
    std::string board;                 // Build.BOARD
    int displayXres = 0;               // display pixel width
    int displayYres = 0;               // display pixel height
    int displayDpi = 160;              // densityDpi
    float displayRefresh = 60.0f;      // refresh rate Hz
    int optimalFramesPerBuffer = 0;    // 0 → native default (44100/512)
    int optimalSampleRate = 0;
};

void setCoreLibPath(const std::string& path);
bool isCoreLoaded();
std::string coreLoadError();

// Register the Java host stub (org.ppsspp.ppsspp.PspHostStub instance) that
// receives the core's frame commands (toast / keyboard / ...). Must be called
// before boot(). Takes a global reference internally.
void setHostStub(JNIEnv* env, jobject stub);

// Boot the core. Must be called from a thread with a JNIEnv (Kotlin IO
// thread is fine). Returns false with lastError() set on failure.
bool boot(JNIEnv* env, const HostParams& params);

// queryConfig("androidJavaGL") == "false" ⇒ Vulkan render loop may run.
bool vulkanActive();

// Surface lifecycle. Call from any thread with a JNIEnv.
//   surface != null → (re)start the Vulkan render loop on the new surface.
//   surface == null → requestExitVulkanRenderLoop() + join.
// width/height: current surface size in pixels (backbufferResize); pass the
// last known values when they are unknown (0 → skip resize).
void setSurface(JNIEnv* env, jobject surface, int width, int height);

void pause(JNIEnv* env);
void resume(JNIEnv* env);

// Input. keycodes are Android KeyEvent codes (PPSSPP default Android pad
// mapping: BUTTON_A→CROSS, B→CIRCLE, X→SQUARE, Y→TRIANGLE, L1/R1→L/R,
// L2→PPSSPP pause menu, R2→fast-forward, DPAD_*→D-pad).
bool keyDown(JNIEnv* env, int keycode);
bool keyUp(JNIEnv* env, int keycode);
// Virtual left stick (Android AXIS_X=0 / AXIS_Y=1), values -1..1.
void joystickAxis(JNIEnv* env, float lx, float ly);
// Raw touch forwarding for PPSSPP's native touch controls.
// code: PPSSPP touch semantics — 1=MOVE, 2=DOWN, 4=UP (| toolType<<10).
void touch(JNIEnv* env, float x, float y, int code, int pointerId);

// Instant save states via the core's own SaveState::SaveSlot/LoadSlot
// (memstick/PSP/PPSSPP_STATE/<prefix>_<slot>.ppst — fully compatible with
// PPSSPP's own pause-menu slots).
bool saveState(JNIEnv* env, int slot);
bool loadState(JNIEnv* env, int slot);

// Fast-forward toggle. PSP_CoreParameter is not exported by the official
// so — left as a no-op; PPSSPP's own default hotkey (R2 / right trigger)
// toggles fast-forward in-game.
inline void setFastForward(bool /*on*/) {}

// Teardown. Safe to call multiple times.
void unload(JNIEnv* env);

std::string lastError();

} // namespace pspload
