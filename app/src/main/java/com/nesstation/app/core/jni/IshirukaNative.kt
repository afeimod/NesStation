package com.nesstation.app.core.jni

/**
 * Ishiiruka（NGC/Wii）核心库加载器 —— NesStation 集成。
 *
 * libishiiruka.so（= 上游 libmain.so，由 scripts/fetch_azahar_ishiruka_libs.sh
 * 从 Ishiruka APK 提取改名）的 JNI 符号按 Java_org_dolphinemu_ishiiruka_* 绑定到
 * vendored 契约类 `org.dolphinemu.ishiiruka.NativeLibrary`（其 <clinit> 内
 * System.loadLibrary("ishiiruka") + CacheClassesAndMethods，与上游一致）。
 * 本对象只做幂等触发 + 可用性探测，全部原生方法经 [lib] 访问。
 *
 * 仅提供 arm64-v8a；加载失败时 [loaded] 为 false，UI 报告不可用而非崩溃。
 */
object IshirukaNative {

    @Volatile
    var loaded: Boolean = false
        private set

    @Volatile
    private var loadAttempted = false

    /** 契约类（首次引用触发 static 块 → loadLibrary + 缓存 JNI 方法）。 */
    val lib: org.dolphinemu.ishiiruka.NativeLibrary
        get() = org.dolphinemu.ishiiruka.NativeLibrary

    /**
     * 尝试加载 libishiiruka.so。重复调用安全（幂等）。
     */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            if (loadAttempted) return false
            loadAttempted = true
            loaded = try {
                // 触发契约类 static 块（loadLibrary("ishiiruka") + CacheClassesAndMethods）
                Class.forName("org.dolphinemu.ishiiruka.NativeLibrary")
                // 再读一个静态常量确认类初始化成功
                org.dolphinemu.ishiiruka.NativeLibrary.TouchScreenDevice.isNotEmpty()
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("IshirukaNative", "Failed to load libishiiruka.so", e)
                false
            } catch (e: Throwable) {
                android.util.Log.e("IshirukaNative", "Ishiiruka init failed", e)
                false
            }
            return loaded
        }
    }
}
