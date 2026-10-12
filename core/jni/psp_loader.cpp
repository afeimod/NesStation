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

// JNI_VERSION_1_6 (0x00010006) — JNI_OnLoad's expected return value.
static constexpr jint kJniVersion16 = JNI_VERSION_1_6;

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
bool g_jniOnLoadDone = false;            // JNI_OnLoad manually invoked (dlopen doesn't run it)

std::string ToStr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(s, p);
    env->DeleteLocalRef(s);
    return out;
}

// ★ NesStation 集成补丁（PSP 闪退根治 #5 —— JNI 契约防御）★
//
//   背景（2026-10-12 tombstone）：PPSSPP v1.20.4 的 registerCallbacks
//   对宿主桩对象连续做三个 GetMethodID（postCommand / getDebugString /
//   getNativeCrashHistory）。官方源注释称 "It's OK if getNativeCrashHistory
//   is missing"（app-android.cpp:608），但缺失时 GetMethodID 抛出的
//   NoSuchMethodError 并不会被清除 —— 紧接着 Android_RegisterStorageCallbacks
//   的 FindClass("org/ppsspp/ppsspp/ContentUri") 在 pending exception
//   状态下执行，ART 直接 AssertNoPendingException → SIGABRT。
//
//   本工具在每个"调用核心 so 的 JNI 入口"之后检查并清理 pending
//   exception，把未来任何 so 版本升级带来的契约错配从"进程 abort"
//   降级为 logcat 错误（配合 PspHostStub 补齐全部契约方法双保险）。
void ClearPendingException(JNIEnv* env, const char* where) {
    if (!env->ExceptionCheck()) return;
    jthrowable e = env->ExceptionOccurred();
    env->ExceptionClear();
    if (e) {
        std::string what;
        jclass cls = env->GetObjectClass(e);
        if (cls) {
            jmethodID ts = env->GetMethodID(cls, "toString", "()Ljava/lang/String;");
            if (ts) {
                jstring js = (jstring)env->CallObjectMethod(e, ts);
                if (js) {
                    const char* p = env->GetStringUTFChars(js, nullptr);
                    if (p) { what = p; env->ReleaseStringUTFChars(js, p); }
                    env->DeleteLocalRef(js);
                }
            }
            env->DeleteLocalRef(cls);
        }
        env->DeleteLocalRef(e);
        LOGE("pending JNI exception cleared @%s: %s", where, what.c_str());
    } else {
        LOGW("pending JNI exception cleared @%s (no detail)", where);
    }
}

std::string QueryConfigStr(JNIEnv* env, const char* name) {
    if (!g_queryConfig || !g_clsNativeApp) return {};
    jstring jname = env->NewStringUTF(name);
    jstring jres = g_queryConfig(env, g_clsNativeApp, jname);
    env->DeleteLocalRef(jname);
    ClearPendingException(env, "queryConfig");
    return ToStr(env, jres);
}

// ★ NesStation 集成补丁：boot 前预检宿主桩的 JNI 契约方法集。
//
//   与 v1.20.4 app-android.cpp:602-604 一一对应 —— 宿主对象上三个实例
//   方法一个都不能少，否则 registerCallbacks 内部会留下 pending
//   exception（见 ClearPendingException 注释）或后续调用点拿到空
//   methodID。逐个探测、逐个清理异常，把缺失项以可读形式打进 logcat。
//   另外核心还会 FindClass("org/ppsspp/ppsspp/ContentUri")（
//   AndroidStorage.cpp:37）与 FindClass("org/ppsspp/ppsspp/PpssppActivity")
//   （JNI_OnLoad, app-android.cpp:252）—— 这两个类已由 app 内 vendor 的
//   官方 Java 源码提供（app/src/main/java/org/ppsspp/ppsspp/）。
void ValidateHostStubContract(JNIEnv* env) {
    if (!g_hostStub) return;
    jclass cls = env->GetObjectClass(g_hostStub);
    if (!cls) { env->ExceptionClear(); return; }
    static const struct { const char* name; const char* sig; } kContract[] = {
        { "postCommand",           "(Ljava/lang/String;Ljava/lang/String;)V" },
        { "getDebugString",        "(Ljava/lang/String;)Ljava/lang/String;" },
        { "getNativeCrashHistory", "(I)Ljava/util/ArrayList;" },
    };
    for (const auto& m : kContract) {
        jmethodID mid = env->GetMethodID(cls, m.name, m.sig);
        if (mid) {
            LOGI("host stub contract ok: %s", m.name);
        } else {
            LOGE("host stub contract MISSING: %s%s "
                 "(PspHostStub 需补齐，否则 registerCallbacks 后 ART abort)",
                 m.name, m.sig);
        }
        env->ExceptionClear();  // GetMethodID 失败抛 NoSuchMethodError —— 必须清
    }
    env->DeleteLocalRef(cls);
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

// ★ NesStation 集成补丁：会话卸载的公共实现（无锁版本）。
//   顺序与官方 PpssppActivity.onDestroy 一致：
//     1. 退出 Vulkan 渲染循环（requestExitVulkanRenderLoop —— join emu 线程）
//     2. audioShutdown（关 OpenSL）
//     3. NativeApp.shutdown（NativeShutdown —— 反初始化核心子系统）
//     4. unregisterCallbacks（释放核心侧持有的宿主引用链）
//   boot()（持有 g_lock）在"上局会话仍活着"时复用；unload()（同样持有
//   g_lock）直接走本函数 —— 两处都不能再对 g_lock 加锁。
void TeardownNoLock(JNIEnv* env) {
    if (!g_booted) return;
    if (g_loopActive && g_exitVkLoop) {
        LOGI("requestExitVulkanRenderLoop (teardown)");
        g_exitVkLoop(env, g_hostStub);
        ClearPendingException(env, "exitVkLoop(teardown)");
        g_loopActive = false;
    }
    if (g_audioShutdown) {
        g_audioShutdown(env, g_clsNativeApp);
        ClearPendingException(env, "audioShutdown");
    }
    if (g_shutdown) {
        g_shutdown(env, g_clsNativeApp);
        ClearPendingException(env, "NativeApp.shutdown");
    }
    if (g_unregisterCallbacks) {
        g_unregisterCallbacks(env, g_hostStub);
        ClearPendingException(env, "unregisterCallbacks");
    }
    g_booted = false;
    LOGI("PPSSPP session torn down (so kept resident for next launch)");
}

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

    // ★ NesStation 集成补丁（PSP 闪退根治）★
    // 上一局会话仍处于 booted 状态时（极快的"退出→再进"竞态窗口，或上层
    // 任何遗漏的 unload 路径），boot() 的旧实现直接 return true —— 新 ROM
    // 根本不会被引导（PPSSPP 内部仍挂着上一局的 CoreParameter/EMU 线程），
    // 表现为"换游戏后黑屏/闪退/还是上一局"。这里对齐官方 PpssppActivity
    // 的生命周期：先按 onDestroy 顺序完整卸载旧会话，再继续本次引导。
    if (g_booted) {
        LOGI("boot(): previous session still active — tearing down first");
        TeardownNoLock(env);
    }

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

        // ★★★ NesStation 集成补丁（PSP 闪退根治 #1 —— 手动 JNI_OnLoad）★★★
        //
        // 官方 PpssppActivity 经 System.loadLibrary("ppsspp_jni") 加载核心，
        // ART 会在加载后自动回调 JNI_OnLoad（app-android.cpp:247）：
        //   gJvm = pjvm;                 // 缓存 JavaVM —— getEnv() 的生命线
        //   gClassLoader = ...;          // 缓存 app ClassLoader（native 线程 FindClass 用）
        //   RegisterAttachDetach(...);   // PPSSPP 线程管理器的 attach/detach 钩子
        //   TimeInit();                  // 时间基准
        //
        // 本宿主走 dlopen() 路径 —— ART 不知道这次加载，JNI_OnLoad 不会被
        // 调用 → gJvm 恒为 nullptr。后果（tombstone 实证信号）：
        //   runVulkanRenderLoop → VulkanEmuThread → AndroidJNIThreadContext
        //   → getEnv() → gJvm->GetEnv(...) → SIGSEGV（空指针解引用）
        // 游戏一进渲染必崩 —— "PSP 游戏直接闪退"的根因。
        //
        // 修复：dlopen 成功后立即 dlsym("JNI_OnLoad") 并手动调用。JavaVM
        // 指针从当前调用线程（Java 线程，PspNative.boot 的调用栈）取；
        // FindClass 也在该线程上执行 —— 栈顶是 Java 帧，能找到 app 类。
        // 幂等：g_jniOnLoadDone 防止重复 boot 时二次执行（重复执行虽无害
        // 但会泄漏一个 GlobalRef）。
        if (!g_jniOnLoadDone) {
            JavaVM* jvm = nullptr;
            if (env->GetJavaVM(&jvm) != JNI_OK || jvm == nullptr) {
                g_loadError = "GetJavaVM failed — cannot initialize PPSSPP JNI runtime";
                LOGE("%s", g_loadError.c_str());
                return false;
            }
            using JniOnLoadFn = jint (*)(JavaVM*, void*);
            JniOnLoadFn jniOnLoad = Sym<JniOnLoadFn>("JNI_OnLoad", false);
            if (jniOnLoad) {
                LOGI("invoking JNI_OnLoad manually (dlopen path)");
                const jint ver = jniOnLoad(jvm, nullptr);
                if (ver < 0) {
                    g_loadError = "PPSSPP JNI_OnLoad returned error";
                    LOGE("%s", g_loadError.c_str());
                    return false;
                }
                LOGI("JNI_OnLoad done, JNI version 0x%x", (unsigned)ver);
            } else {
                // 官方 so 一定导出 JNI_OnLoad；缺失说明 so 版本异常，继续
                // 也会在 getEnv() 处崩 —— 提前给出可读错误。
                g_loadError = "JNI_OnLoad not exported by libppsspp_jni.so (unexpected build)";
                LOGE("%s", g_loadError.c_str());
                return false;
            }
            g_jniOnLoadDone = true;
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
    //
    //    ★ NesStation 集成补丁（PSP 闪退根治 #4）★：预检契约 + 清理
    //    pending exception —— v1.20.4 registerCallbacks 对宿主对象做
    //    三个 GetMethodID，缺失时不清异常，下一个 FindClass(ContentUri)
    //    在 pending exception 下执行 → ART AssertNoPendingException →
    //    SIGABRT（tombstone：NoSuchMethodError getNativeCrashHistory）。
    ValidateHostStubContract(env);
    if (g_registerCallbacks) {
        g_registerCallbacks(env, g_hostStub);
        ClearPendingException(env, "registerCallbacks");
    }

    // 2) Display parameters must precede init (config defaults / 1x-2x pick).
    if (g_setDisplay) {
        g_setDisplay(env, g_clsNativeApp,
                     p.displayXres > 0 ? p.displayXres : 1280,
                     p.displayYres > 0 ? p.displayYres : 720,
                     p.displayDpi, p.displayRefresh);
        ClearPendingException(env, "setDisplayParameters");
    }

    // 3) Audio tuning (0 → native defaults 44100 Hz / 512 frames).
    if (g_audioConfig) {
        g_audioConfig(env, g_clsNativeApp,
                      p.optimalFramesPerBuffer, p.optimalSampleRate);
        ClearPendingException(env, "audioConfig");
    }

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
    ClearPendingException(env, "NativeApp.init");
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
    if (g_computeBB) {
        g_computeBB(env, g_clsNativeApp);
        ClearPendingException(env, "computeDesiredBackbufferDimensions");
    }
    if (g_audioInit) {
        g_audioInit(env, g_clsNativeApp);
        ClearPendingException(env, "audioInit");
    }

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
            ClearPendingException(env, "exitVkLoop(surface gone)");
            g_loopActive = false;
        }
        return;
    }

    // Restart policy mirrors PpssppActivity.notifySurface: exit any running
    // loop, then start a fresh one on the new surface.
    if (g_loopActive && g_exitVkLoop) {
        g_exitVkLoop(env, g_hostStub);
        ClearPendingException(env, "exitVkLoop(surface swap)");
        g_loopActive = false;
    }

    if (width > 0 && height > 0 && g_bbResize) {
        // PixelFormat.RGBA_8888 == 1 (what the official SizeManager forwards).
        g_bbResize(env, g_clsNativeApp, width, height, 1);
        ClearPendingException(env, "backbufferResize");
    }
    if (g_computeBB) {
        g_computeBB(env, g_clsNativeApp);
        ClearPendingException(env, "computeDesiredBackbufferDimensions");
    }

    if (g_runVkLoop) {
        jboolean ok = g_runVkLoop(env, g_hostStub, surface);
        ClearPendingException(env, "runVulkanRenderLoop");
        g_loopActive = (ok == JNI_TRUE);
        if (g_loopActive) {
            LOGI("Vulkan render loop started (%dx%d)", width, height);
        } else {
            SetError("runVulkanRenderLoop 启动失败（graphicsContext 未就绪或循环已在跑）");
        }
    }
}

void pause(JNIEnv* env) {
    if (g_booted && g_pause) {
        g_pause(env, g_clsNativeApp);
        ClearPendingException(env, "NativeApp.pause");
    }
}

void resume(JNIEnv* env) {
    if (g_booted && g_resume) {
        g_resume(env, g_clsNativeApp);
        ClearPendingException(env, "NativeApp.resume");
    }
}

bool keyDown(JNIEnv* env, int keycode) {
    if (!g_booted || !g_keyDown) return false;
    // DEVICE_ID_PAD_0 = 10 (Common/Input/InputState.h) — PPSSPP's default
    // Android gamepad table (defaultPadMapAndroid) is registered for it.
    jboolean r = g_keyDown(env, g_clsNativeApp, 10, keycode, JNI_FALSE);
    ClearPendingException(env, "keyDown");
    return r == JNI_TRUE;
}

bool keyUp(JNIEnv* env, int keycode) {
    if (!g_booted || !g_keyUp) return false;
    jboolean r = g_keyUp(env, g_clsNativeApp, 10, keycode);
    ClearPendingException(env, "keyUp");
    return r == JNI_TRUE;
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
    ClearPendingException(env, "joystickAxis");
}

void touch(JNIEnv* env, float x, float y, int code, int pointerId) {
    if (!g_booted || !g_touch || code == 0) return;
    g_touch(env, g_clsNativeApp, x, y, (jint)(code | kFingerToolBits),
            (jint)pointerId);
    ClearPendingException(env, "touch");
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
    // ★ NesStation 集成补丁：逻辑抽入 TeardownNoLock（boot 的重引导路径
    //   复用同一份卸载实现，两处顺序保证一致），此处仅加锁转发。
    TeardownNoLock(env);
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
