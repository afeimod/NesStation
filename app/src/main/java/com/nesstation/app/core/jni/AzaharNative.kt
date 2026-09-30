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
    /**
     * 补建 Azahar 用户目录的基础目录树 —— 上游 DirectoryInitialization 的
     * nand/sdmc/sysdata 等目录由 Java 层显式创建（FileUtil::CreateFullPath 只
     * 在写文件时按需建父目录，但原生 Service::AM::InstallCIA / 系统 Title
     * 枚举 / 共享字体加载都要先探测这些目录存在与否）。不先建树时：
     *  - installCIA 写 NAND 前 getFilesName/createDir 探测失败 → 安装返回 Error*；
     *  - 启动时 ArchiveFactory_NAND/SDMC 枚举不到既有目录 → 黑屏/系统 Title 缺失。
     * 与上游 citra-android 的 DirectoryInitialization.start() 行为一致
     * （CreateDirectory("sysdata"/"nand"/"sdmc"/... )）。
     */
    private fun ensureUserDirTree(userDir: String) {
        val subDirs = listOf(
            "nand", "nand/title", "sdmc", "sysdata",
            "config", "log", "states", "shaderCache", "load", "dump"
        )
        val root = java.io.File(userDir)
        try { root.mkdirs() } catch (_: Throwable) {}
        for (rel in subDirs) {
            try {
                // 与原生 createDir 回调同语义：仅当不存在才创建，幂等
                java.io.File(root, rel).mkdirs()
            } catch (_: Throwable) {}
        }
    }

    fun initConfigPipeline(userDir: String) {
        // 目录树先行：原生 FileUtil::CreateFullPath 按需建父目录，但 C++ 侧
        // 对"目录是否存在"的探测（GetFilesName/Exists）发生在安装/启动早期，
        // 显式建树保证 NAND/SDMC/共享字体槽位必然存在。
        ensureUserDirTree(userDir)
        // ★★★★ 3DS 全链路根治（本轮定位的真根因）★★★★
        //
        // 绝不能传裸绝对路径！原生 FileUtil::SetUserPath(path) 开头是
        // CreateFullPath(path) → Exists(path) → stat(TranslateFilePath(path))，
        // 而 TranslateFilePath 对**非 '!' 开头**的路径一律拼上 Java 回调
        // getUserDirectory() 返回的 userDir（android_utils.cpp 反汇编实测）——
        // stat("<userDir>/<userDir>") 必失败 → CreateDir 循环逐级
        // mkdir("<userDir>/data/user/0/com.nesstation.app/files/azahar")
        // 反而全部成功（错乱目录树被造出来）→ CreateFullPath 返回 true →
        // user_path 以**绝对路径**写入 g_paths。
        // 后果：核心之后所有文件操作（config.ini / nand / sdmc / sysdata /
        // log / states）再过一次 TranslateFilePath 时又被拼一遍 userDir →
        // 全部落到 <userDir>/<userDir>/config/... 双重前缀的错乱路径：
        //   - 核心**永远读不到** Java 层写入的 <userDir>/config/config.ini
        //     （全部 3DS 设置无效的直接根因）；
        //   - azahar_log.txt 写进错乱嵌套路径（用户看到的位置文件为空）；
        //   - CIA 装进错乱 NAND、系统字体/密钥错位。
        //
        // 正确姿势 = "!" + userDir：
        //   Exists("!"+userDir) → stat(userDir) 直读成功（目录上面已建好）→
        //   user_path = "!"+userDir，g_paths 全部 '!'-前缀 →
        //   每个 IOFile::Open 经 TranslateFilePath 剥 '!' 后落在**真实路径**，
        //   与 NesStation Java 层写入的 config.ini / sysdata / NAND 完全一致。
        // （参考 AzaharPlus APK 从不调 setUserDirectory —— 其 g_paths 走
        //  Android 分支 user_path="/"，路径由 Java 回调拼接；"!" 前缀方案
        //  是同结果的本集成可控实现，且对 Java 侧返回的路径是绝对路径，零歧义。）
        lib.setUserDirectory("!$userDir")
        // 关键一步：先初始化日志后端，再触碰任何 Config 读取
        // （LogDir 此刻 = "!"+userDir+"/log/"，日志真实落盘 <userDir>/log/azahar_log.txt）
        lib.createLogFile()
        try { lib.logUserDirectory(userDir) } catch (_: Throwable) {}
        // 生成默认配置树（已有则核心按存在文件处理；
        // ConfigDir = "!"+userDir+"/config/" → 核心直读 Java 写入的 config.ini）
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
