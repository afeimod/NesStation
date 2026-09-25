package com.nesstation.app.core.jni

/**
 * Azahar（3DS）核心库加载器 —— NesStation 集成。
 *
 * 与 DraStic 的 [DraSticJNI] 同思路：libazahar.so（= 上游 libcitra-android.so，
 * 由 scripts/fetch_azahar_ishiruka_libs.sh 从 AzaharPlus APK 提取改名）的 JNI 符号
 * 按 Java_org_citra_citra_emu_NativeLibrary_<method> 绑定到 vendored 契约类
 * `org.citra.citra_emu.NativeLibrary`。本对象只负责 loadLibrary + 可用性探测，
 * 全部原生方法经 [lib] 访问。
 *
 * 仅提供 arm64-v8a（上游 Android 移植即 64 位 only）；32 位 / x86 进程加载失败时
 * [loaded] 为 false，UI 报告不可用而非崩溃。
 */
object AzaharNative {

    @Volatile
    var loaded: Boolean = false
        private set

    @Volatile
    private var loadAttempted = false

    /** 契约类（首次引用即触发其类初始化 —— 本对象不额外持库）。 */
    val lib: org.citra.citra_emu.NativeLibrary
        get() = org.citra.citra_emu.NativeLibrary

    /**
     * 初始化原生配置管线 —— 上游 DirectoryInitialization.start() 等价序列
     * （setUserDirectory → createLogFile → logUserDirectory → createConfigFile）。
     *
     * ⚠ 顺序是硬性契约：必须先 [org.citra.citra_emu.NativeLibrary.createLogFile]
     * （native 侧 = `Common::Log::Initialize()` + `Common::Log::Start()`），之后才
     * 允许 createConfigFile()/reloadSettings()。上游 Config::ReadValues() 末尾直接
     * 调用 `Common::Log::SetGlobalFilter()/SetRegexFilter()`（config.cpp 注释原话：
     * "as the logger has already been initialized"），日志后端未初始化时
     * `Impl::Instance()` 抛 std::runtime_error("Using Logging instance before its
     * initialization")。该 C++ 异常跨 JNI 边界不会被转换成 Java 异常，直接
     * std::terminate → abort → SIGABRT —— Java 层 try/catch 拦不住 native abort，
     * 正确的调用顺序本身就是修复。（实测 libazahar.so BuildId 0758b08c…：
     * createLogFile 符号存在，且 so 内含 "Using Logging instance before its
     * initialization" 与 "Logging backend initialised" 字符串，机制吻合。）
     */
    fun initConfigPipeline(userDir: String) {
        lib.setUserDirectory(userDir)
        // 关键一步：先初始化日志后端，再触碰任何 Config 读取
        lib.createLogFile()
        try { lib.logUserDirectory(userDir) } catch (_: Throwable) {}
        // 生成默认配置树（已有则核心按存在文件处理）
        lib.createConfigFile()
    }

    /**
     * 尝试加载 libazahar.so。重复调用安全（幂等）。
     */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            if (loadAttempted) return false
            loadAttempted = true
            loaded = try {
                // 触发契约类 <clinit> 前先显式 loadLibrary，失败语义清晰
                System.loadLibrary("azahar")
                true
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("AzaharNative", "Failed to load libazahar.so", e)
                false
            } catch (e: SecurityException) {
                false
            }
            return loaded
        }
    }
}
