package com.nesstation.app.core.jni

/**
 * Ishiiruka（NGC/Wii）核心库加载器 —— NesStation 集成。
 *
 * libishiiruka.so（= 上游 libmain.so，由 scripts/fetch_azahar_ishiruka_libs.sh
 * 从 Ishiruka APK 提取改名）的 JNI_OnLoad 内 FindClass("org/dolphinemu/dolphinemu/
 * NativeLibrary")，全部导出符号按 Java_org_dolphinemu_dolphinemu_* 绑定到
 * 新契约类 `org.dolphinemu.dolphinemu.NativeLibrary`（其 static 块加载 so）；
 * org.dolphinemu.ishiiruka.NativeLibrary 保留为兼容门面（静态常量 + 委托方法）。
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

    /** 契约类引用（首次访问触发 static 块 → loadLibrary + 缓存 JNI 方法）。
     *  NativeLibrary 是 Java final class（静态成员形态），因此返回其 Class 而非实例。 */
    val lib: Class<org.dolphinemu.ishiiruka.NativeLibrary>
        get() = org.dolphinemu.ishiiruka.NativeLibrary::class.java

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
                // 闪退修复：触发 dolphinemu 包契约类 static 块（loadLibrary("ishiiruka")）。
                // libishiiruka.so 的 JNI_OnLoad FindClass("org/dolphinemu/dolphinemu/NativeLibrary")，
                // 符号按 Java_org_dolphinemu_dolphinemu_* 绑定 —— 加载必须发生在新契约类上。
                Class.forName("org.dolphinemu.dolphinemu.NativeLibrary")
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
