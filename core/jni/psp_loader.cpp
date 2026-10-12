// SPDX-License-Identifier: MIT
// PPSSPP (Sony PSP) host driver — NesStation in-process integration.
// See psp_loader.h for the design. This file dlopen()s the prebuilt
// official libppsspp_jni.so (PPSSPP v1.20.4) and drives it through the
// exact JNI contract vendored in app/src/main/java/org/ppsspp/ppsspp/.
//
// Symbol ground truth: libppsspp_jni.so dynamic symbol table
// (nm -D --defined-only) + official v1.20.4 sources
// (android/jni/app-android.cpp, Core/SaveState.h).

#include "psp_loader.h"

#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <functional>
#include <future>
#include <mutex>
#include <chrono>

#define TAG "pspcore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace pspload {

// ---------------------------------------------------------------------------
// JNI function pointer types — signatures mirror the vendored Java layer
// (org.ppsspp.ppsspp.NativeApp / PpssppActivity, official v1.20.4).
// All are static members of NativeApp except the three PpssppActivity
// instance methods (registerCallbacks / runVulkanRenderLoop /
// requestExitVulkanRenderLoop), which take a jobject `this` — the native
// side only uses it for registerCallbacks (GetObjectClass) and ignores it
// for the render-loop pair (verified against v1.20.4 sources).
// ---------------------------------------------------------------------------

using InitFn        = void (*)(JNIEnv*, jclass, jstring, jint, jstring, jstring,
                                jstring, jstring, jstring, jstring, jstring, jstring,
                                jstring, jstring, jint, jstring);
using AudioInitFn   = void (*)(JNIEnv*, jclass);
using AudioShutFn   = void (*)(JNIEnv*, jclass);
using AudioCfgFn    = void (*)(JNIEnv*, jclass, jint, jint);
using VoidFn        = void (*)(JNIEnv*, jclass);
using ComputeBBFn   = void (*)(JNIEnv*, jclass);
using GetBBSizeFn   = jint (*)(JNIEnv*, jclass);
using SetDispFn     = void (*)(JNIEnv*, jclass, jint, jint, jint, jfloat);
using BBResizeFn    = void (*)(JNIEnv*, jclass, jint, jint, jint);
using KeyFn         = jboolean (*)(JNIEnv*, jclass, jint, jint, jboolean);
using KeyUpFn       = jboolean (*)(JNIEnv*, jclass, jint, jint);
using AxisFn        = void (*)(JNIEnv*, jclass, jint, jintArray, jfloatArray, jint);
using TouchFn       = void (*)(JNIEnv*, jclass, jfloat, jfloat, jint, jint);
using QueryCfgFn    = jstring (*)(JNIEnv*, jclass, jstring);
using SendMsgFn     = void (*)(JNIEnv*, jclass, jstring, jstring);

using RegCbFn       = void (*)(JNIEnv*, jobject);
using RunVKLoopFn   = jboolean (*)(JNIEnv*, jobject, jobject);
using ExitVKLoopFn  = void (*)(JNIEnv*, jobject);

// C++ symbols exported by libppsspp_jni.so (arm64 dynamic symbol table):
//   _ZN9SaveState13GetGamePrefixERK12ParamSFOData
//       std::string SaveState::GetGamePrefix(const ParamSFOData&)
//   _ZN9SaveState8SaveSlotENSt6__ndk117basic_string_viewIcNS0_11char_traitsIcEEEEiNS0_8functionIFvNS_6StatusES4_EEE
//       void SaveState::SaveSlot(std::string_view, int,
//                                std::function<void(Status, std::string)>)
//   _ZN9SaveState8LoadSlotE... (same shape)
//   B g_paramSFO  (Core/ParamSFO.cpp global — filled once a game boots)
using GetPrefixFn   = std::string (*)(const void* /*ParamSFOData*/);
using SaveSlotFn    = void (*)(std::string_view /*gamePrefix*/, int /*slot*/,
                                std::function<void(int /*Status*/, std::string)> /*cb*/);

// SaveState::Status (official v1.20.4 Core/SaveState.h):
//   FAILURE = 0, WARNING = 1, SUCCESS = 2
static constexpr int kStatusSuccess = 2;

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

namespace {

std::mutex g_lock;
void*      g_so = nullptr;              // dlopen handle of libppsspp_jni.so
std::string g_soPath;
std::string g_loadError;                 // dlopen/dlsym failure detail
std::string g_lastError;                 // last runtime error (boot/save/…)

// Cached JNI entities (global refs, created in boot()).
jclass g_clsNativeApp = nullptr;         // org.ppsspp.ppsspp.NativeApp
jclass g_clsPpssppActivity = nullptr;    // org.ppsspp.ppsspp.PpssppActivity
jobject g_hostStub = nullptr;            // Java host stub (postCommand sink)

// Resolved entry points.
InitFn      g_init = nullptr;
AudioInitFn g_audioInit = nullptr;
AudioShutFn g_audioShutdown = nullptr;
AudioCfgFn  g_audioConfig = nullptr;
ComputeBBFn g_computeBB = nullptr;
GetBBSizeFn g_getBBW = nullptr;
GetBBSizeFn g_getBBH = nullptr;
SetDispFn   g_setDisplay = nullptr;
BBResizeFn  g_bbResize = nullptr;
KeyFn       g_keyDown = nullptr;
KeyUpFn     g_keyUp = nullptr;
AxisFn      g_axis = nullptr;
TouchFn     g_touch = nullptr;
QueryCfgFn  g_queryConfig = nullptr;
SendMsgFn   g_sendMessage = nullptr;
VoidFn      g_pause = nullptr;
VoidFn      g_resume = nullptr;
VoidFn      g_shutdown = nullptr;

RegCbFn     g_registerCallbacks = nullptr;
RegCbFn     g_unregisterCallbacks = nullptr;
RunVKLoopFn g_runVkLoop = nullptr;
ExitVKLoopFn g_exitVkLoop = nullptr;

GetPrefixFn g_getPrefix = nullptr;
SaveSlotFn  g_saveSlot = nullptr;
SaveSlotFn  g_loadSlot = nullptr;
void*       g_paramSFO = nullptr;

bool g_booted = false;
bool g_loopActive = false;               // Vulkan render loop thread running

std::string ToStr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(s, p);
    env->DeleteLocalRef(s);
    return out;
}

std::string QueryConfigStr(JNIEnv* env, const char* name) {
    if (!g_queryConfig || !g_clsNativeApp) return {};
    jstring jname = env->NewStringUTF(name);
    jstring jres = g_queryConfig(env, g_clsNativeApp, jname);
    env->DeleteLocalRef(jname);
    return ToStr(env, jres);
}

void SetError(const std::string& e) {
    std::lock_guard<std::mutex> lk(g_lock);
    g_lastError = e;
    LOGE("%s", e.c_str());
}

template <typename T>
T Sym(const char* name, bool required) {
    void* p = dlsym(g_so, name);
    if (!p && required) {
        g_loadError = std::string("dlsym missing: ") + name;
        LOGE("%s", g_loadError.c_str());
    }
    return reinterpret_cast<T>(p);
}

bool ResolveSymbols() {
    if (!g_so) return false;
    g_loadError.clear();

    g_init             = Sym<InitFn>("Java_org_ppsspp_ppsspp_NativeApp_init", true);
    g_audioInit        = Sym<AudioInitFn>("Java_org_ppsspp_ppsspp_NativeApp_audioInit", true);
    g_audioShutdown    = Sym<AudioShutFn>("Java_org_ppsspp_ppsspp_NativeApp_audioShutdown", true);
    g_audioConfig      = Sym<AudioCfgFn>("Java_org_ppsspp_ppsspp_NativeApp_audioConfig", true);
    g_computeBB        = Sym<ComputeBBFn>("Java_org_ppsspp_ppsspp_NativeApp_computeDesiredBackbufferDimensions", true);
    g_getBBW           = Sym<GetBBSizeFn>("Java_org_ppsspp_ppsspp_NativeApp_getDesiredBackbufferWidth", true);
    g_getBBH           = Sym<GetBBSizeFn>("Java_org_ppsspp_ppsspp_NativeApp_getDesiredBackbufferHeight", true);
    g_setDisplay       = Sym<SetDispFn>("Java_org_ppsspp_ppsspp_NativeApp_setDisplayParameters", true);
    g_bbResize         = Sym<BBResizeFn>("Java_org_ppsspp_ppsspp_NativeApp_backbufferResize", true);
    g_keyDown          = Sym<KeyFn>("Java_org_ppsspp_ppsspp_NativeApp_keyDown", true);
    g_keyUp            = Sym<KeyUpFn>("Java_org_ppsspp_ppsspp_NativeApp_keyUp", true);
    g_axis             = Sym<AxisFn>("Java_org_ppsspp_ppsspp_NativeApp_joystickAxis", true);
    g_touch            = Sym<TouchFn>("Java_org_ppsspp_ppsspp_NativeApp_touch", true);
    g_queryConfig      = Sym<QueryCfgFn>("Java_org_ppsspp_ppsspp_NativeApp_queryConfig", true);
    g_sendMessage      = Sym<SendMsgFn>("Java_org_ppsspp_ppsspp_NativeApp_sendMessageFromJava", false);
    g_pause            = Sym<VoidFn>("Java_org_ppsspp_ppsspp_NativeApp_pause", true);
    g_resume           = Sym<VoidFn>("Java_org_ppsspp_ppsspp_NativeApp_resume", true);
    g_shutdown         = Sym<VoidFn>("Java_org_ppsspp_ppsspp_NativeApp_shutdown", true);

    g_registerCallbacks   = Sym<RegCbFn>("Java_org_ppsspp_ppsspp_PpssppActivity_registerCallbacks", true);
    g_unregisterCallbacks = Sym<RegCbFn>("Java_org_ppsspp_ppsspp_PpssppActivity_unregisterCallbacks", false);
    g_runVkLoop           = Sym<RunVKLoopFn>("Java_org_ppsspp_ppsspp_PpssppActivity_runVulkanRenderLoop", true);
    g_exitVkLoop          = Sym<ExitVKLoopFn>("Java_org_ppsspp_ppsspp_PpssppActivity_requestExitVulkanRenderLoop", true);

    // C++ symbols for instant save states (all exported by the official so).
    g_getPrefix = Sym<GetPrefixFn>("_ZN9SaveState13GetGamePrefixERK12ParamSFOData", false);
    g_saveSlot  = Sym<SaveSlotFn>(
        "_ZN9SaveState8SaveSlotENSt6__ndk117basic_string_viewIcNS0_11char_traitsIcEEEEiNS0_8functionIFvNS_6StatusES4_EEE",
        false);
    g_loadSlot  = Sym<SaveSlotFn>(
        "_ZN9SaveState8LoadSlotENSt6__ndk117basic_string_viewIcNS0_11char_traitsIcEEEEiNS0_8functionIFvNS_6StatusES4_EEE",
        false);
    g_paramSFO  = dlsym(g_so, "g_paramSFO");

    if (g_loadError.empty()) return true;
    return false;
}

// PPSSPP touch "code" bit for finger tool type (NativeApp.processTouchEvent
// packs toolType<<10; InputDevice.TOOL_TYPE_FINGER == 1).
constexpr jint kFingerToolBits = 1 << 10;

} // namespace

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

void setCoreLibPath(const std::string& path) {
    std::lock_guard<std::mutex> lk(g_lock);
    g_soPath = path;
}

bool isCoreLoaded() {
    std::lock_guard<std::mutex> lk(g_lock);
    return g_so != nullptr;
}

std::string coreLoadError() {
    std::lock_guard<std::mutex> lk(g_lock);
    return g_loadError;
}

bool boot(JNIEnv* env, const HostParams& p) {
    std::lock_guard<std::mutex> lk(g_lock);

    if (g_booted) return true;

    if (g_so == nullptr) {
        if (g_soPath.empty()) {
            g_loadError = "core lib path not set";
            return false;
        }
        LOGI("dlopen %s", g_soPath.c_str());
        g_so = dlopen(g_soPath.c_str(), RTLD_NOW);
        if (!g_so) {
            const char* e = dlerror();
            g_loadError = std::string("dlopen(libppsspp_jni.so) failed: ") + (e ? e : "?");
            LOGE("%s", g_loadError.c_str());
            return false;
        }
        if (!ResolveSymbols()) return false;
    }

    // The Java host stub must be provided by the bridge (PspNative.boot).
    if (g_hostStub == nullptr) {
        g_loadError = "host stub not registered";
        return false;
    }

    if (!g_clsNativeApp) {
        jclass local = env->FindClass("org/ppsspp/ppsspp/NativeApp");
        if (!local) { g_loadError = "FindClass(NativeApp) failed"; return false; }
        g_clsNativeApp = (jclass)env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
    }
    if (!g_clsPpssppActivity) {
        jclass local = env->FindClass("org/ppsspp/ppsspp/PpssppActivity");
        if (!local) { g_loadError = "FindClass(PpssppActivity) failed"; return false; }
        g_clsPpssppActivity = (jclass)env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
    }

    // 1) Register native→Java callbacks BEFORE init: ProcessFrameCommands
    //    (toast/keyboard/…) fires during init and calls postCommand
    //    unconditionally (v1.20.4 app-android.cpp — no null guard).
    if (g_registerCallbacks) {
        g_registerCallbacks(env, g_hostStub);
    }

    // 2) Display parameters must precede init (config defaults / 1x-2x pick).
    if (g_setDisplay) {
        g_setDisplay(env, g_clsNativeApp,
                     p.displayXres > 0 ? p.displayXres : 1280,
                     p.displayYres > 0 ? p.displayYres : 720,
                     p.displayDpi, p.displayRefresh);
    }

    // 3) Audio tuning (0 → native defaults 44100 Hz / 512 frames).
    if (g_audioConfig) g_audioConfig(env, g_clsNativeApp,
                                     p.optimalFramesPerBuffer, p.optimalSampleRate);

    // 4) init — shortcutParam becomes boot_filename (parse_args → NativeInit),
    //    graphicsContext (AndroidVulkanContext) is created inside.
    jstring jmodel  = env->NewStringUTF(p.model.c_str());
    jstring jlang   = env->NewStringUTF(p.langRegion.c_str());
    jstring japk    = env->NewStringUTF(p.apkPath.c_str());
    jstring jdata   = env->NewStringUTF(p.dataDir.c_str());
    jstring jextSt  = env->NewStringUTF(p.externalStorageDir.c_str());
    jstring jextFi  = env->NewStringUTF(p.externalFilesDir.c_str());
    jstring jlibDir = env->NewStringUTF(p.nativeLibDir.c_str());
    jstring jaddSt  = env->NewStringUTF(p.additionalStorageDirs.c_str());
    jstring jcache  = env->NewStringUTF(p.cacheDir.c_str());
    jstring jshort  = env->NewStringUTF(p.shortcutParam.c_str());
    jstring jinst   = env->NewStringUTF(p.installerName.c_str());
    jstring jboard  = env->NewStringUTF(p.board.c_str());
    g_init(env, g_clsNativeApp,
           jmodel, (jint)p.deviceType, jlang, japk, jdata, jextSt, jextFi,
           jlibDir, jaddSt, jcache, jshort, jinst, (jint)p.androidVersion, jboard);
    env->DeleteLocalRef(jmodel);  env->DeleteLocalRef(jlang);
    env->DeleteLocalRef(japk);    env->DeleteLocalRef(jdata);
    env->DeleteLocalRef(jextSt);  env->DeleteLocalRef(jextFi);
    env->DeleteLocalRef(jlibDir); env->DeleteLocalRef(jaddSt);
    env->DeleteLocalRef(jcache);  env->DeleteLocalRef(jshort);
    env->DeleteLocalRef(jinst);   env->DeleteLocalRef(jboard);

    // 5) Backend sanity: our host only drives the Vulkan render loop. If the
    //    ini asked for OpenGL (or Vulkan was unavailable and release builds
    //    force-revert to GL), refuse with a readable error instead of a
    //    black screen.
    const std::string javaGL = QueryConfigStr(env, "androidJavaGL");
    if (javaGL == "true") {
        g_loadError = "PPSSPP OpenGL (JavaGL) 后端不受进程内宿主支持："
                      "需要在 ppsspp.ini 设 Graphics/GraphicsBackend=3 (Vulkan)，"
                      "或设备不支持 Vulkan";
        LOGE("%s", g_loadError.c_str());
        return false;
    }

    // 6) Desired backbuffer + OpenSL audio (self-contained native thread).
    if (g_computeBB) g_computeBB(env, g_clsNativeApp);
    if (g_audioInit) g_audioInit(env, g_clsNativeApp);

    g_booted = true;
    g_lastError.clear();
    LOGI("PPSSPP booted: %dx%d @%.1fHz dpi=%d rom=%s",
         p.displayXres, p.displayYres, p.displayRefresh, p.displayDpi,
         p.shortcutParam.c_str());
    return true;
}

bool vulkanActive() {
    return g_booted && g_runVkLoop != nullptr;
}

void setSurface(JNIEnv* env, jobject surface, int width, int height) {
    if (!g_booted) return;

    if (surface == nullptr) {
        if (g_loopActive && g_exitVkLoop) {
            LOGI("requestExitVulkanRenderLoop (surface gone)");
            g_exitVkLoop(env, g_hostStub);
            g_loopActive = false;
        }
        return;
    }

    // Restart policy mirrors PpssppActivity.notifySurface: exit any running
    // loop, then start a fresh one on the new surface.
    if (g_loopActive && g_exitVkLoop) {
        g_exitVkLoop(env, g_hostStub);
        g_loopActive = false;
    }

    if (width > 0 && height > 0 && g_bbResize) {
        // PixelFormat.RGBA_8888 == 1 (what the official SizeManager forwards).
        g_bbResize(env, g_clsNativeApp, width, height, 1);
    }
    if (g_computeBB) g_computeBB(env, g_clsNativeApp);

    if (g_runVkLoop) {
        jboolean ok = g_runVkLoop(env, g_hostStub, surface);
        g_loopActive = (ok == JNI_TRUE);
        if (g_loopActive) {
            LOGI("Vulkan render loop started (%dx%d)", width, height);
        } else {
            SetError("runVulkanRenderLoop 启动失败（graphicsContext 未就绪或循环已在跑）");
        }
    }
}

void pause(JNIEnv* env) {
    if (g_booted && g_pause) g_pause(env, g_clsNativeApp);
}

void resume(JNIEnv* env) {
    if (g_booted && g_resume) g_resume(env, g_clsNativeApp);
}

bool keyDown(JNIEnv* env, int keycode) {
    if (!g_booted || !g_keyDown) return false;
    // DEVICE_ID_PAD_0 = 10 (Common/Input/InputState.h) — PPSSPP's default
    // Android gamepad table (defaultPadMapAndroid) is registered for it.
    return g_keyDown(env, g_clsNativeApp, 10, keycode, JNI_FALSE) == JNI_TRUE;
}

bool keyUp(JNIEnv* env, int keycode) {
    if (!g_booted || !g_keyUp) return false;
    return g_keyUp(env, g_clsNativeApp, 10, keycode) == JNI_TRUE;
}

void joystickAxis(JNIEnv* env, float lx, float ly) {
    if (!g_booted || !g_axis) return;
    // Android MotionEvent axis ids: AXIS_X = 0, AXIS_Y = 1 — exactly what
    // defaultPadMapAndroid binds to VIRTKEY_AXIS_X/Y (PSP analog stick).
    jint ids[2] = {0, 1};
    jfloat vals[2] = {lx, ly};
    jintArray jids = env->NewIntArray(2);
    jfloatArray jvals = env->NewFloatArray(2);
    env->SetIntArrayRegion(jids, 0, 2, ids);
    env->SetFloatArrayRegion(jvals, 0, 2, vals);
    g_axis(env, g_clsNativeApp, 10, jids, jvals, 2);
    env->DeleteLocalRef(jids);
    env->DeleteLocalRef(jvals);
}

void touch(JNIEnv* env, float x, float y, int code, int pointerId) {
    if (!g_booted || !g_touch || code == 0) return;
    g_touch(env, g_clsNativeApp, x, y, (jint)(code | kFingerToolBits),
            (jint)pointerId);
}

namespace {

// Shared implementation for saveState/loadState: waits for the core's async
// completion callback (fires on the emu thread) with a generous timeout.
bool RunSlotOp(JNIEnv* env, int slot, bool save) {
    if (!g_booted) { SetError("PSP 核心未启动"); return false; }
    if (!g_getPrefix || !g_paramSFO || (!g_saveSlot && !g_loadSlot)) {
        SetError("核心缺少 SaveState 符号（so 版本不匹配）");
        return false;
    }

    std::string prefix = g_getPrefix((const void*)g_paramSFO);
    if (prefix.empty()) {
        SetError("无法确定游戏 Disc ID（游戏尚未完成引导）");
        return false;
    }

    // shared_ptr keeps the promise alive even if we time out and return —
    // the core's async completion callback may fire later on the emu thread.
    auto done = std::make_shared<std::promise<int>>();
    auto fut = done->get_future();
    std::function<void(int, std::string)> cb =
        [done](int status, std::string /*message*/) {
            try { done->set_value(status); } catch (...) {}
        };

    LOGI("%s slot %d (prefix=%s)", save ? "saveState" : "loadState", slot,
         prefix.c_str());
    if (save) g_saveSlot(prefix, slot, cb);
    else      g_loadSlot(prefix, slot, cb);

    constexpr auto kTimeout = std::chrono::seconds(15);
    if (fut.wait_for(kTimeout) != std::future_status::ready) {
        SetError(save ? "存档超时（核心未响应）" : "读档超时（核心未响应）");
        return false;
    }
    const int status = fut.get();
    if (status != kStatusSuccess) {
        SetError(std::string(save ? "存档" : "读档") + "失败：核心返回 status=" +
                 std::to_string(status));
        return false;
    }
    return true;
}

} // namespace

bool saveState(JNIEnv* env, int slot) { return RunSlotOp(env, slot, true); }
bool loadState(JNIEnv* env, int slot) { return RunSlotOp(env, slot, false); }

void unload(JNIEnv* env) {
    std::lock_guard<std::mutex> lk(g_lock);
    if (!g_booted) return;

    // Order mirrors PpssppActivity.onDestroy: exit render loop first (joins
    // the emu thread), then audioShutdown, then NativeApp.shutdown.
    if (g_loopActive && g_exitVkLoop) {
        g_exitVkLoop(env, g_hostStub);
        g_loopActive = false;
    }
    if (g_audioShutdown) g_audioShutdown(env, g_clsNativeApp);
    if (g_shutdown)      g_shutdown(env, g_clsNativeApp);
    if (g_unregisterCallbacks) g_unregisterCallbacks(env, g_hostStub);

    g_booted = false;
    LOGI("PPSSPP unloaded (so kept resident for next launch)");
}

std::string lastError() {
    std::lock_guard<std::mutex> lk(g_lock);
    return g_lastError;
}

void setHostStub(JNIEnv* env, jobject stub) {
    std::lock_guard<std::mutex> lk(g_lock);
    if (g_hostStub != nullptr) {
        env->DeleteGlobalRef(g_hostStub);
        g_hostStub = nullptr;
    }
    if (stub != nullptr) {
        g_hostStub = env->NewGlobalRef(stub);
    }
}

} // namespace pspload
