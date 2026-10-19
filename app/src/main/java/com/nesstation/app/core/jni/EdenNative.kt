package com.nesstation.app.core.jni

/**
 * Eden（Switch）核心库加载器 —— NesStation 集成。
 *
 * libyuzu-android.so 由 :eden-core Gradle 模块从 eden-emulator/eden 源码树
 * （core/eden/，CPM + CMake 源码编译，非预编译 so）构建产出，其全部 JNI
 * 符号按 Java_org_yuzu_yuzu_1emu_* 绑定到 vendored 契约包
 * `org.yuzu.yuzu_emu`（见 app/src/main/java/org/yuzu/yuzu_emu/）。
 *
 * 本对象只做幂等加载 + 可用性探测（与 CemuNative 同模式）：
 *  · [ensureLoaded] 触发 System.loadLibrary("yuzu-android")；
 *  · 仅 arm64-v8a ABI 产出（上游默认）；其他 ABI / API<28（NCE/
 *    adrenotools 与动态链接在 <28 上问题频发，取 yuzu 系长期支持
 *    下限 28）→ [loaded] 为 false，UI 报告不可用而非崩溃。
 *  · initJvm（id_cache 的 Java 类/方法缓存）必须在首个 JNI 调用前、
 *    且从带 App ClassLoader 的 Java 线程执行 —— 由 [EdenEngine] 在
 *    loadRom 时序里调用 [ensureInitialized]，见引擎注释。
 */
object EdenNative {

    private const val TAG = "EdenNative"

    @Volatile
    var loaded: Boolean = false
        private set

    @Volatile
    private var loadAttempted = false

    /** initJvm 是否已执行（进程生命周期内一次）。 */
    @Volatile
    private var jvmInitialized = false

    /**
     * 尝试加载 libyuzu-android.so。重复调用安全（幂等）。
     */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            if (loadAttempted) return false
            loadAttempted = true
            loaded = try {
                // ★ Eden 运行时门槛：上游 main flavor minSdk=24，但 NCE/
                //   adrenotools/动态符号在 <28 上问题频发，取 28 为下限。
                //   更低版本设备报告"核心不可用"，应用不崩溃。
                if (android.os.Build.VERSION.SDK_INT < 28) {
                    android.util.Log.w(
                        TAG,
                        "Eden 需要 Android 9 (API 28)+，当前 API ${android.os.Build.VERSION.SDK_INT}"
                    )
                    false
                } else {
                    System.loadLibrary("yuzu-android")
                    // ★★ v5（Switch 无声根因修复）★★
                    // SDL3 以静态库链入 libyuzu-android.so：其 JNI_OnLoad 只完成
                    // JavaVM 捕获与 org.libsdl.app.* 回调表注册；而
                    // SDL_SetMainReady() —— SDL_InitSubSystem(SDL_INIT_AUDIO) 的
                    // 前置门槛 —— 只能经 Java 侧 SDL.setupJNI()（SDLActivity /
                    // SDLAudioManager / SDLControllerManager 三类 nativeSetupJNI →
                    // checkJNIReady 三者齐备）触发。上游 NativeLibrary.init 顺序
                    // 即 loadLibrary → SDL.setupJNI() → initJvm，此处对齐。
                    // 缺失时 SDL_InitSubSystem 直接返回 "Application didn't
                    // initialize properly" → SDLSinkStream::SDL_OpenAudioDeviceStream
                    // 失败仅 LOG_CRITICAL 后静默返回 → 游戏正常运行但全程无声。
                    //
                    // org.libsdl.app.* 12 个桥接类【随 v5 vendored 进
                    // app/src/main/java/org/libsdl/app/】（取自上游 Eden 构建实际
                    // 链接的 SDL3 预编译包 crueter-ci/SDL3 3.4.14-…-147a8ee32d 的
                    // java/ 目录 —— core/eden 的 CMake 在 YUZU_USE_BUNDLED_SDL3
                    // 分支本会把它们拷到 externals/generated/sdl/java 供上游 app
                    // sourceSets 编译；NesStation 集成裁掉了 app 侧 gradle 引用，
                    // 故在此直接编入，版本与 libyuzu-android.so 内静态链接的 SDL3
                    // 严格一致）。旧版 3.7 APK 中的同类来自已废弃的 ARMSX2 java
                    // sourceSet，当前仓库已无任何模块编译它们，无重复类风险。
                    try {
                        org.libsdl.app.SDL.setupJNI()
                    } catch (t: Throwable) {
                        // 桥接异常不阻断核心加载（SDL 音频/热插拔降级，游戏可玩）
                        android.util.Log.w(
                            TAG, "SDL.setupJNI failed — Switch audio/SDL features degraded", t
                        )
                    }
                    true
                }
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e(TAG, "Failed to load libyuzu-android.so", e)
                false
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "Eden init failed", e)
                false
            }
            return loaded
        }
    }

    /**
     * 加载 + initJvm（id_cache：缓存 NativeLibrary/DiskShaderCacheProgress/
     * GameDir/Game/Patch/OverlayControlData/PlayerInput/YuzuInputDevice 等
     * Java 类与回调 MethodID）。必须在任何触发 C++→Java 回调的调用之前
     * 从【Java 线程】执行一次（JNI FindClass 用调用线程的 ClassLoader）。
     */
    fun ensureInitialized(): Boolean {
        if (!ensureLoaded()) return false
        synchronized(this) {
            if (!jvmInitialized) {
                try {
                    org.yuzu.yuzu_emu.NativeLibrary.initJvm()
                } catch (t: Throwable) {
                    // ★ 兜底加固：id_cache.cpp 的 FindClassOrThrow /
                    //   GetStaticMethodIDOrThrow 在契约缺失时会抛出带
                    //   缺失类/方法名的可捕获 RuntimeException。此处兜住
                    //   并转成「核心不可用」（调用方 coreInitOnce 会设置
                    //   lastErrorText 并在 UI 呈现），而不是让异常向
                    //   协程层传播导致 App 崩溃。
                    android.util.Log.e(TAG, "initJvm (Common::Android::Initialize) failed", t)
                    return false
                }
                jvmInitialized = true
            }
        }
        return true
    }
}
