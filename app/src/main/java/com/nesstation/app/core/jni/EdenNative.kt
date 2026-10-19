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
