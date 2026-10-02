package com.nesstation.app.core.jni

/**
 * Citra MMJ（3DS）核心库加载器 —— NesStation 集成。
 *
 * libmain.so（= 上游 Citra_MMJ_20250220.apk 的核心库，提取改名
 * libcitra_mmj.so 放 `app/src/main/jniLibs/arm64-v8a/`）的 JNI 符号按
 * Java_org_citra_emu_NativeLibrary_<method> 绑定到 vendored 契约类
 * `org.citra.emu.NativeLibrary`。本对象只负责 loadLibrary + 可用性探测，
 * 全部原生方法经 [lib] 访问。
 *
 * 仅提供 arm64-v8a（上游 MMJ 即 64 位 only）；32 位 / x86 进程加载
 * 失败时 [loaded] 为 false，UI 报告不可用而非崩溃（同 Azahar/DraStic）。
 */
object CitraMmjNative {

    @Volatile
    var loaded: Boolean = false
        private set

    @Volatile
    private var loadAttempted = false

    /** 契约类（首次引用即触发其类初始化）。 */
    val lib: org.citra.emu.NativeLibrary
        get() = org.citra.emu.NativeLibrary

    /**
     * 尝试加载 libcitra_mmj.so。重复调用安全（幂等）。
     */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            if (loadAttempted) return false
            loadAttempted = true
            loaded = try {
                // 触发契约类 <clinit> 前先显式 loadLibrary，失败语义清晰
                System.loadLibrary("citra_mmj")
                true
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("CitraMmjNative", "Failed to load libcitra_mmj.so", e)
                false
            } catch (e: SecurityException) {
                false
            }
            return loaded
        }
    }
}
