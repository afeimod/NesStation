package com.nesstation.app.core.jni

/**
 * Cemu（Wii U）核心库加载器 —— NesStation 集成。
 *
 * libCemuAndroid.so 由 :cemu-core Gradle 模块从 afeimod/Cemu-AZ 源码树
 * （core/cemu/，vcpkg + CMake 源码编译，非预编译 so）构建产出，其全部
 * JNI 符号按 Java_info_cemu_cemu_nativeinterface_* 绑定到 vendored 契约包
 * `info.cemu.cemu.nativeinterface`（见 app/src/main/java/info/cemu/cemu/）。
 *
 * 本对象只做幂等加载 + 可用性探测（与 IshirukaNative 同模式）：
 *  · [ensureLoaded] 触发契约对象类初始化（external 方法解析不触发加载，
 *    由首个 external 调用触发；这里主动 System.loadLibrary 并做一次
 *    无害调用验证链接成功）；
 *  · 仅 arm64-v8a ABI 产出（上游默认）；其他 ABI / API<30（Cemu 依赖
 *    Vulkan 1.1 与 API 30+ 的图形栈）→ [loaded] 为 false，UI 报告不可用
 *    而非崩溃。
 */
object CemuNative {

    private const val TAG = "CemuNative"

    @Volatile
    var loaded: Boolean = false
        private set

    @Volatile
    private var loadAttempted = false

    /**
     * 尝试加载 libCemuAndroid.so。重复调用安全（幂等）。
     */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            if (loadAttempted) return false
            loadAttempted = true
            loaded = try {
                // ★ Cemu 运行时门槛（上游 Cemu-AZ minSdk=30 + Vulkan 1.1 特性声明）：
                //   低于 Android 11 的设备直接报告不可用，避免 loadLibrary 后
                //   在图形栈初始化路径上崩溃。
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                    android.util.Log.w(TAG, "Cemu 需要 Android 11 (API 30)+，当前 API ${android.os.Build.VERSION.SDK_INT}")
                    false
                } else {
                    System.loadLibrary("CemuAndroid")
                    // 链接验证：读一个无副作用的查询（mlc 路径需先初始化才可用，
                    // 这里改用纯查询型 hasRequiredOnlineFiles 的异常行为做链接
                    // 探测并不安全 —— 直接以 loadLibrary 成功为准，符号错误会在
                    // 首个 external 调用抛 UnsatisfiedLinkError，由引擎层兜底）。
                    true
                }
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e(TAG, "Failed to load libCemuAndroid.so", e)
                false
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "Cemu init failed", e)
                false
            }
            return loaded
        }
    }
}
