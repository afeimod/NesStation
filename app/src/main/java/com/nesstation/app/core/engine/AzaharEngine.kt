package com.nesstation.app.core.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Surface
import com.nesstation.app.core.jni.AzaharNative
import org.citra.citra_emu.NativeLibrary
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Azahar（3DS 模拟器核心）引擎 —— NesStation 集成。
 *
 * 集成方式参照 DraStic（激烈）核心：上游 Java 宿主层以**原包名 JNI 契约**形式
 * vendored（`org.citra.citra_emu.NativeLibrary`，见同目录契约文件），预编译核心库
 * `libazahar.so`（= 上游 libcitra-android.so，来自 AzaharPlus_2125.x APK）放
 * `app/src/main/jniLibs/arm64-v8a/`，经 scripts/fetch_azahar_ishiruka_libs.sh 提取。
 *
 * 架构（推模型，与上游 EmulationFragment 一致 —— 引擎不拥有模拟循环）：
 *  1. [loadRom] 仅记录 ROM 路径并写 config.ini（用户目录 `<filesDir>/azahar`）；
 *  2. Surface 有效后（[setSurface]）启动专用线程调用 `NativeLibrary.run(path)`
 *     （阻塞直至退出），并由 Choreographer 在 UI 线程驱动 `NativeLibrary.doFrame()`
 *     呈现帧 —— 上游 Choreographer.FrameCallback 同款；
 *  3. 暂停/恢复走 `pauseEmulation()/unPauseEmulation()`，快进走
 *     `setTemporaryFrameLimit()`，停止走 `stopEmulation()`；
 *  4. 音频由核心内部播放（与 ARMSX2 / 推模型核心一致，引擎无 AudioTrack）。
 *
 * 设置：`setCoreOption("Renderer/resolution_factor", "2")` —— "段/键" 复合键直写
 * 用户目录 config.ini 后 `reloadSettings()` 热生效（Azahar 原生侧即按此流程读取，
 * 与上游 Java 端 SettingsRepository → config.ini 完全等价）。
 *
 * ROM：.3ds/.cci/.cxi/.cia/.app（Encrypted 卡带需 aes_keys.txt 放用户目录）。
 * 存档：核心 saveState/loadState 使用自身槽位文件（`<userDir>/states/`），
 * dst 参数仅做 UI 兼容标记（同 PS2 引擎做法）。
 */
class AzaharEngine private constructor() : EmulatorEngine, AzaharCoreEngine {

    /**
     * 3DS 无帧缓冲回读（核心直绘 Surface）—— 保留 API 兼容，[captureFrame] 返回 null。
     */
    override val frameBuffer = IntArray(1)

    private val running = AtomicBoolean(false)
    private var heartbeatThread: Thread? = null

    /** 上游同款：UI 线程 Choreographer → NativeLibrary.doFrame() */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameCallback: Choreographer.FrameCallback? = null
    private val presentationActive = AtomicBoolean(false)
    /** 布局保障线程去重标志（每次启动补一次 updateFramebuffer）。 */
    private val layoutEnsurePending = AtomicBoolean(false)

    @Volatile private var surface: Surface? = null
    @Volatile private var romPath: String? = null
    @Volatile private var systemDir: String? = null
    @Volatile private var saveDir: String? = null
    private var emuThread: Thread? = null

    @Volatile override var isLoaded = false
        private set

    @Volatile private var _paused = false
    @Volatile private var _ffSpeed = 0
    @Volatile private var lastErrorText = ""

    /** 复合键（"Section/key"）→ 配置值 的热更新集合。 */
    private val coreOptions = LinkedHashMap<String, String>()

    // === Netplay（推模型核心不支持锁步 —— 接受但无效，与 PS2 引擎一致） ===
    @Volatile private var _frameHook: NetplayHook? = null
    @Volatile private var _netFrame = 0L
    override var frameHook: NetplayHook?
        get() = _frameHook
        set(value) {
            _frameHook = value
            _netFrame = 0L
        }

    private val lifecycleLock = Any()

    /** App context — set by NesApp.onCreate so the engine can locate user dirs. */
    @Volatile var appContext: Context? = null

    /**
     * ★ 3DS 黑屏修复（错误可见化）：非用户请求的模拟退出回调。
     *
     * Azahar 是推模型核心：loadRom 返回 true 后若核心因 ROM 加载失败
     * （加密卡带缺 aes_keys.txt / 文件损坏 / 系统文件缺失）自行退出，
     * 旧实现只把错误写进 lastErrorText 而无人消费 —— UI 永远停在
     * loaded=true 的黑屏上，用户完全不知道原因。现在 UI（EmulatorScreen）
     * 在启动后注册本回调，核心异常退出时立刻把 lastError 呈现成错误
     * 对话框而不是永久黑屏。
     *
     * 线程：回调在原生线程 / 模拟线程触发，宿主必须自行 post 到主线程。
     * 用户主动退出（unload/reset/shutdown）不触发。
     */
    @Volatile var onPrematureExit: ((String) -> Unit)? = null

    /** 用户主动停止标记（true 时 run() 返回不触发 [onPrematureExit]）。 */
    @Volatile private var userRequestedStop = false

    // ------------------------------------------------------------------
    // 可用性
    // ------------------------------------------------------------------

    data class Availability(val available: Boolean, val reason: String?)

    @Volatile private var probedAvailability: Availability? = null

    fun probeAvailability(): Availability {
        probedAvailability?.let { return it }
        val result = try {
            AzaharNative.ensureLoaded()
            if (AzaharNative.loaded) {
                Availability(true, null)
            } else {
                Availability(
                    false,
                    "libazahar 加载失败（当前进程非 ARM64，或库缺失）\n" +
                        "Azahar 核心仅提供 arm64-v8a；请运行 " +
                        "scripts/fetch_azahar_ishiruka_libs.sh 从 AzaharPlus APK 提取核心库。"
                )
            }
        } catch (e: Throwable) {
            Availability(false, "Azahar 核心加载异常: ${e.message}")
        }
        probedAvailability = result
        return result
    }

    override fun isCoreAvailable(): Boolean = probeAvailability().available

    override fun ensureLoaded(): Boolean = probeAvailability().available

    // ------------------------------------------------------------------
    // 目录 / 配置
    // ------------------------------------------------------------------

    private fun userDir(): String {
        val ctx = appContext
            ?: throw IllegalStateException("AzaharEngine: app context not initialised")
        return File(ctx.filesDir, "azahar").apply { mkdirs() }.absolutePath
    }

    // ------------------------------------------------------------------
    // ★ 3DS 报错根治（密钥/引导文件自动归位）：
    //   核心 KeyManager::LoadKeys 从 **<userDir>/sysdata/** 读 aes_keys.txt
    //   （libazahar.so 字符串 "sysdata"+"aes_keys.txt" 实测；boot9.bin /
    //   seeddb.bin 同目录）。旧诊断让用户把文件放 azahar 根目录 —— 位置错了，
    //   核心永远读不到 → 加密 ROM 必然黑屏/报错。
    //   本方法在每次 loadRom 前把用户可能放置位置（azahar 根目录 / filesDir 根 /
    //   应用外置 files 目录 / 外置 azahar 目录）的密钥文件**自动拷贝归位**到
    //   sysdata/，已存在同名校验大小防止反复覆盖。
    // ------------------------------------------------------------------

    /** 3DS 密钥/引导文件名 → sysdata/ 自动归位。 */
    private fun ensureSysDataFiles() {
        val ctx = appContext ?: return
        val sysdata = File(userDir(), "sysdata").apply { mkdirs() }
        val sourcesRoots = buildList {
            add(File(userDir()))                       // 历史诊断提示的 azahar 根目录
            add(ctx.filesDir)                          // filesDir 根目录
            try {
                ctx.getExternalFilesDir(null)?.let {
                    add(it)                            // /sdcard/Android/data/<pkg>/files/
                    add(File(it, "azahar"))
                    add(File(it, "sysdata"))
                }
            } catch (_: Throwable) {}
        }
        val names = listOf("aes_keys.txt", "boot9.bin", "seeddb.bin")
        for (name in names) {
            val dest = File(sysdata, name)
            if (dest.exists() && dest.length() > 0) continue
            val src = sourcesRoots.asSequence()
                .map { File(it, name) }
                .firstOrNull { it.isFile && it.length() > 0 } ?: continue
            try {
                src.copyTo(dest, overwrite = true)
                android.util.Log.i("AzaharEngine", "sysdata auto-import: $name <- ${src.absolutePath}")
            } catch (t: Throwable) {
                android.util.Log.w("AzaharEngine", "sysdata import failed: $name (${t.message})")
            }
        }
    }

    /**
     * 读取原生日志尾部（azahar_log.txt）—— 错误弹窗附带真实失败原因，
     * 用户截图即可定位（不用再猜"黑屏"是密钥/系统档案/驱动哪一环）。
     *
     * ★★ '!'-userDir 修复后：核心 LogDir = "!<userDir>/log/"，每次
     *   IOFile::Open 经 TranslateFilePath 剥 '!' → 日志**真实落盘**在
     *   <userDir>/log/azahar_log.txt（与 NesStation 读取位置一致，有内容）。
     *   旧版本（裸 setUserDirectory）日志被写到双重前缀的错乱嵌套路径，
     *   这里的"折叠镜像"候选用于兼容诊断那些历史残留文件。
     */
    fun nativeLogTail(maxLines: Int = 24): String? {
        val userDir = try { userDir() } catch (_: Throwable) { return null }
        // 主位置（'!'-userDir 模式真实落盘处）优先；折叠镜像路径仅兜底
        val mangled = File(userDir, userDir.removePrefix("/").removePrefix("/"))
        val candidates = listOf(
            File(File(userDir, "log"), "azahar_log.txt"),
            File(userDir, "azahar_log.txt"),
            File(mangled, "log/azahar_log.txt")
        )
        for (f in candidates) {
            if (!f.isFile || f.length() == 0L) continue
            try {
                val lines = f.readLines().let { if (it.size > maxLines) it.takeLast(maxLines) else it }
                val text = lines.joinToString("\n")
                if (text.isNotBlank()) return text
            } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * 诊断：config.ini 里 lle_applets 的实际生效值（核心日志弹窗展示）。
     * 核心默认 true（LLE 系统小程序）：无完整 NAND 时 applet 加载失败 →
     * 建档命名类操作卡死。此处读合并写入后的最终值，一眼确认 HLE 生效。
     */
    fun lleAppletsEffective(): String {
        val userDir = try { userDir() } catch (_: Throwable) { return "userDir 不可用" }
        return try {
            val ini = configFile(userDir)
            if (!ini.isFile) {
                "config.ini 不存在 → lle_applets 走核心默认 true（LLE，需完整 NAND）"
            } else {
                val m = Regex("(?im)^\\s*lle_applets\\s*=\\s*(\\S+)").find(ini.readText())
                val v = m?.groupValues?.get(1)
                when {
                    v == null -> "lle_applets 未写入 → 核心默认 true（LLE，需完整 NAND）"
                    v.equals("false", true) || v == "0" ->
                        "lle_applets = $v（HLE —— 建档命名走应用内键盘对话框）"
                    else -> "lle_applets = $v（LLE —— 无完整 NAND 时建档类操作会卡死）"
                }
            }
        } catch (t: Throwable) { "config.ini 读取失败: ${t.message}" }
    }

    /** 3DS 环境自检摘要（密钥/引导文件/系统数据种子状态，用于错误诊断文本）。 */
    private fun sysDataDiag(): String {
        val userDir = try { userDir() } catch (_: Throwable) { "<unknown>" }
        val sysdata = File(userDir, "sysdata")
        fun st(name: String): String {
            val f = File(sysdata, name)
            return if (f.isFile && f.length() > 0) "已存在(${f.length()}B)" else "缺失"
        }
        // ★ 文案纠偏（用户实测 + 参考 APK 行为）：
        //   参考 APK 内部不带 boot9.bin，也能正常运行绝大多数游戏
        //   （Azahar 对未加密 ROM 是 HLE 引导，不需要引导 ROM）；boot9/seeddb
        //   仅少数场景（LLE 引导/系统应用/区域种子）才用。未加密的
        //   .app/.3ds/.cci 依赖的共享字体/系统 title 已由内置参考包自动解压。
        return buildString {
            append("未加密 ROM（.app/.3ds/.cci 大多数）：无需任何密钥/引导文件")
            append("\naes_keys.txt: ").append(st("aes_keys.txt"))
            append("（仅**已加密** ROM 需要 → 放入 ")
            append(sysdata.absolutePath)
            append("/，或放 azahar 目录由应用自动归位）")
            append("\nboot9.bin: ").append(st("boot9.bin"))
            append("（**通常不需要**，参考 APK 也不内置，仅 LLE/系统应用场景用）")
            append("\nseeddb.bin: ").append(st("seeddb.bin")).append("（可选，区域种子）")
            try {
                val ctx = appContext
                if (ctx != null) {
                    append("\n\n—— 参考环境系统数据 ——\n")
                    append(com.nesstation.app.core.storage.AzaharSystemData.diag(ctx, File(userDir)))
                }
            } catch (_: Throwable) {}
        }
    }

    private fun configFile(userDirPath: String): File =
        File(File(userDirPath, "config").apply { mkdirs() }, "config.ini")

    /**
     * 把 coreOptions 复合键写入 config.ini。
     *
     * ★ 3DS 黑屏修复：旧实现直接用 UI 提供的键**整体覆盖** config.ini，
     * 把 createConfigFile() 刚生成的完整默认配置（[Controls]/[Data Storage]/
     * [Core] 等全部段）毁掉。本实现改为**合并写入**（与 Ishiruka 的
     * writeIniMerged 同模式）：读出已有内容，仅更新 UI 涉及的段/键，
     * 其余保持不变，核心的 INIReader 依然按完整配置读取。
     */
    private fun flushConfig() {
        val dir = systemDir ?: return
        try {
            val cfg = configFile(dir)
            val grouped = LinkedHashMap<String, LinkedHashMap<String, String>>()
            // 读入已有配置（createConfigFile 生成的默认完整配置或上次合并结果）
            if (cfg.exists()) {
                var current: String? = null
                try {
                    cfg.forEachLine { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEachLine
                        if (line.startsWith("[") && line.endsWith("]")) {
                            current = line.substring(1, line.length - 1).trim()
                            grouped.getOrPut(current!!) { LinkedHashMap() }
                        } else if (current != null) {
                            val eq = line.indexOf('=')
                            if (eq > 0) {
                                grouped.getOrPut(current!!) { LinkedHashMap() }[line.substring(0, eq).trim()] =
                                    line.substring(eq + 1).trim()
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
            // 叠加 UI 提供的键（"段/键" 复合键）
            coreOptions.forEach { (composite, value) ->
                val idx = composite.indexOf('/')
                if (idx <= 0 || idx == composite.length - 1) return@forEach
                val section = composite.substring(0, idx)
                val key = composite.substring(idx + 1)
                grouped.getOrPut(section) { LinkedHashMap() }[key] = value
            }
            val sb = StringBuilder()
            grouped.forEach { (section, kv) ->
                sb.append('[').append(section).append("]\n")
                kv.forEach { (k, v) -> sb.append(k).append(" = ").append(v).append('\n') }
                sb.append('\n')
            }
            cfg.writeText(sb.toString())
        } catch (t: Throwable) {
            android.util.Log.w("AzaharEngine", "flushConfig failed", t)
        }
    }

    /**
     * 当前 App 方向是否竖屏（与原生 isPortraitMode() 回调同语义）。
     * 用于 updateFramebuffer —— 上游 SettingsActivityPresenter /
     * ScreenAdjustmentUtil 在每次设置重载后都会调用它刷新帧缓冲布局。
     */
    private fun isPortraitNow(): Boolean = try {
        appContext?.resources?.configuration?.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
    } catch (_: Throwable) { false }

    /**
     * 布局刷新（上游 reloadSettings + updateFramebuffer 等价对）：
     * ★ 3DS 黑屏修复的关键一环 —— 屏幕布局/间距/立体 3D 等设置变更后
     * 原生侧必须收到 updateFramebuffer 才会用新布局重算两个屏幕在
     * Surface 内的矩形；旧实现从不调用，改动后画面可能保持旧布局
     * （或布局失效后不重绘 → 黑屏）。
     * system.IsPoweredOn() 前调用是无害 no-op（native 侧自身判断）。
     */
    private fun refreshFramebufferLayout() {
        try { AzaharNative.lib.updateFramebuffer(isPortraitNow()) } catch (_: Throwable) {}
    }

    override fun setCoreOption(key: String, value: String) {
        val prev = coreOptions.put(key, value)
        // ★ 批量事务中只缓存 —— 由 endCoreOptionsBatch 统一提交一次
        //（旧行为：每个键全量 flush+reload+updateFramebuffer，50 键连发 = 卡顿根源）
        if (optionsBatching) {
            // ★ 图形后端变更标记（批末统一处理，见 endCoreOptionsBatch）
            if (key == "Renderer/graphics_api" && prev != null && prev != value) {
                pendingRendererRestart = true
            }
            return
        }
        if (isRunning2()) {
            flushConfig()
            try { AzaharNative.lib.reloadSettings() } catch (_: Throwable) {}
            // 布局/呈现相关设置变更后刷新帧缓冲布局（上游同款对）
            if (key.startsWith("Layout/") || key.startsWith("Renderer/")) {
                refreshFramebufferLayout()
            }
            // ★★★ 图形后端（VK/GL）切换生效修复（"3ds的vk渲染失效，没有
            //   起作用"根因）★★★：渲染器只在 run() 启动时构造一次
            //   （反汇编实测：run() 读 graphics_api 分派 EmuWindow_Vulkan/
            //   OpenGL，reloadSettings 只更新 Settings 值不重建窗口）。
            //   游戏中切换图形后端后必须重启模拟线程才会用新后端 ——
            //   在后台线程执行 reset()（内部 join 最多 8s，不能阻塞主线程）。
            if (key == "Renderer/graphics_api" && prev != null && prev != value) {
                scheduleRendererRestart()
            }
        }
    }

    /** ★ 批量设置事务开启（见 EmulatorEngine 注释）。 */
    override fun beginCoreOptionsBatch() {
        optionsBatching = true
    }

    /**
     * ★ 批量设置事务提交：一次 flush + 一次 reload + 一次 framebuffer 刷新
     *（等价旧行为的“最后一次”效果，但只做一次）。
     */
    override fun endCoreOptionsBatch() {
        if (!optionsBatching) return
        optionsBatching = false
        if (isRunning2()) {
            flushConfig()
            try { AzaharNative.lib.reloadSettings() } catch (_: Throwable) {}
            refreshFramebufferLayout()
            if (pendingRendererRestart) {
                pendingRendererRestart = false
                scheduleRendererRestart()
            }
        } else {
            pendingRendererRestart = false
        }
    }

    /** 批内标记的图形后端变更（见 setCoreOption 注释）。 */
    @Volatile private var pendingRendererRestart = false

    /** 渲染器重启单线程执行器（reset 会 join 模拟线程，绝不在主线程执行）。 */
    private val rendererRestartExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "azahar-renderer-restart").apply { isDaemon = true }
    }

    /**
     * 后台线程重启模拟线程：stopEmulation → 重新 run() → 新渲染器
     *（VK/GL 热切换生效）。同一时刻只允许一个重启任务（重复触发直接忽略）
     */
    private fun scheduleRendererRestart() {
        try {
            rendererRestartExecutor.execute {
                try {
                    android.util.Log.i("AzaharEngine", "graphics_api changed, restarting renderer")
                    reset()
                } catch (t: Throwable) {
                    android.util.Log.w("AzaharEngine", "renderer restart failed", t)
                }
            }
        } catch (_: Throwable) {}
    }

    @Volatile private var optionsBatching = false

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) {
            lastErrorText = probeAvailability().reason ?: "Azahar 核心不可用"
            return false
        }
        cleanupLocked()
        // 主动停止标记：本局由用户发起的重载，退出回调不算异常
        userRequestedStop = false

        // 引擎传来的 systemDir 即 <filesDir>/azahar（EmulatorScreen 平台分支）——
        // 即 Azahar 用户目录（config/sdmc/nand/states 均在其中）。
        this.systemDir = userDir()
        this.saveDir = saveDir
        this.romPath = rom.absolutePath

        // ★★ 参考环境系统数据种子（必须在核心读配置前完成）★★
        //   参考 APK 首次初始化即解压 Mii.zip + Font_ACG.zip（共享字体 /
        //   seeddb / 系统 title）。旧集成从不种这些 → 与参考环境不一致。
        //   已就绪时幂等无开销。
        try {
            val seedCtx = appContext
            if (seedCtx != null) {
                com.nesstation.app.core.storage.AzaharSystemData.ensureSeeded(
                    seedCtx, File(userDir())
                )
            }
        } catch (t: Throwable) {
            android.util.Log.w("AzaharEngine", "sysdata seed failed", t)
        }

        // ★ 密钥/引导文件自动归位（必须在核心读配置前完成）
        ensureSysDataFiles()

        val lib = AzaharNative.lib
        try {
            NativeLibrary.NesStationHost.register(object : NativeLibrary.Host {
                override fun appContext(): Context? = this@AzaharEngine.appContext
                override fun azaharUserDirectory(): String = userDir()
                override fun onEmulationExited(result: Int) {
                    // ★ 黑屏修复：非用户请求的退出 → 上报给 UI（见 onPrematureExit）。
                    if (!userRequestedStop && isLoaded) {
                        val msg = "Azahar 核心提前退出（status=$result）" +
                            (lastErrorText.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "") +
                            "\n常见原因：未加密 ROM 无需任何密钥/引导文件；仅**已加密**卡带需 " +
                            "aes_keys.txt（放入 azahar/sysdata/ 目录，或放 azahar 目录由应用" +
                            "自动归位）；镜像损坏或不完整；存储空间不足。" +
                            (sysDataDiag().let { "\n\n$it" }) +
                            (nativeLogTail()?.let { "\n\n—— 核心日志尾部 ——\n$it" } ?: "")
                        onPrematureExit?.invoke(msg)
                    } else {
                        lastErrorText = "Azahar 退出（status=$result）"
                    }
                }

                // ★ 3DS 黑屏修复配套：核心致命错误上报（onCoreError → 此处）。
                // 旧链路：onCoreError 永远返回 true → 核心继续空转 → 永久黑屏。
                // 新链路：onCoreError 上报错误并返回 false 终止模拟 → 本回调把
                // 原因写入 lastErrorText 并立即通过 onPrematureExit 弹窗展示，
                // 与参考 APK 的 CoreErrorDialogFragment 行为对齐。
                override fun onCoreErrorReport(errorName: String, details: String) {
                    lastErrorText = "核心错误 $errorName${
                        details.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
                    }"
                    try { onPrematureExit?.invoke(lastErrorText) } catch (_: Throwable) {}
                }
            })
            // ⚠ 上游硬性契约（DirectoryInitialization.start() 同序）：必须先
            // createLogFile()（= Common::Log::Initialize/Start）再 createConfigFile()/
            // reloadSettings() —— 否则 Config::ReadValues() 内
            // Common::Log::SetGlobalFilter 抛 "Using Logging instance before its
            // initialization"，C++ 异常跨 JNI 边界 → std::terminate → SIGABRT 闪退
            //（Java try/catch 拦不住 native abort，顺序即修复）。详见 AzaharNative。
            AzaharNative.initConfigPipeline(userDir())
            flushConfig()
            // ★ 3DS 黑屏修复：GPU 驱动参数初始化对齐参考 APK。
            //   参考 AzaharPlus 的 DirectoryInitialization.start() →
            //   GpuDriverHelper.initializeDriverParameters()（反编译实测）传入：
            //     hookLibPath          = <nativeLibraryDir>/
            //     driverInstallationPath = <filesDir>/gpu_driver/
            //     customDriverLibraryName = 未安装自定义驱动时空串
            //     fileRedirectionPath  = <userDir>/gpu/vk_file_redirect/
            //   旧实现传四个 null —— 部分构建在渲染后端初始化阶段读到未初始化
            //   路径而黑屏；按参考语义传入真实路径（无自定义驱动时 hook 逻辑
            //   自动跳过，与参考 APK 无自定义驱动时行为一致）。
            try {
                val ctx = appContext
                val hookLibPath = ctx?.applicationInfo?.nativeLibraryDir?.let { "$it/" } ?: ""
                val driverInstallPath = ctx?.filesDir?.let { File(it, "gpu_driver") }?.apply { mkdirs() }?.absolutePath ?: ""
                // ★ 对齐参考 APK GpuDriverHelper.initializeDriverParameters()：
                //   fileRedirectionPath = **filesDir 规范路径** + "/gpu/vk_file_redirect/"
                //   （参考用的是 getInternalUserPath()，不是 azahar 用户目录子目录）
                val fileRedirectPath = ctx?.filesDir?.let {
                    File(File(it, "gpu"), "vk_file_redirect").apply { mkdirs() }.absolutePath
                } ?: ""
                // ★★ GPU 驱动安装与选择（本轮新增，对齐上游 Azahar）：第 3 参
                //   customDriverLibraryName 接入用户在驱动管理器中选中的驱动
                //   （空 = 系统驱动）。驱动 zip 经 GpuDriverHelper 安装到
                //   <filesDir>/gpu_driver/<id>/，核心据此通过 adrenotools
                //   加载自定义 Turnip/Adreno 驱动（VK 后端）。
                val customDriverLibrary = try {
                    org.citra.citra_emu.utils.GpuDriverHelper.selectedLibraryName(ctx)
                } catch (_: Throwable) { "" }
                lib.initializeGpuDriver(hookLibPath, driverInstallPath, customDriverLibrary, fileRedirectPath)
            } catch (_: Throwable) {}
            try { lib.reloadSettings() } catch (_: Throwable) {}
        } catch (t: Throwable) {
            android.util.Log.w("AzaharEngine", "user dir / config init failed", t)
        }

        isLoaded = true

        // HUD 心跳（FPS 计数 / netplay 兼容节拍）
        running.set(true)
        heartbeatThread = thread(name = "azahar-hud-heartbeat", isDaemon = true) {
            try {
                while (running.get()) {
                    _frameHook?.beforeFrame(_netFrame)?.let { (p1, p2) ->
                        setPad1(p1); setPad2(p2)
                    }
                    onFrame()
                    _netFrame++
                    try { Thread.sleep(16) } catch (_: InterruptedException) { break }
                }
            } catch (t: Throwable) {
                android.util.Log.e("AzaharEngine", "heartbeat crashed", t)
            }
        }

        // Surface 可能已就绪（EmulatorScreen 先挂 Surface 再 loadRom）——直接开跑
        surface?.let { startEmulationLocked() }
        return true
    }

    private fun startEmulationLocked() {
        val lib = AzaharNative.lib
        val path = romPath ?: return
        if (emuThread?.isAlive == true) return
        // ★★★ 启动前文件预检（native 视角）：Java File.exists() 为 true 不代表
        //   native fopen 一定成功（FUSE/权限层差异）。核心侧打开失败只会写
        //   "Failed to load ROM" 日志然后静默退出（黑屏/秒退无提示）。
        //   这里用核心自己的 nativeFileExists 探测，失败立即上报明确错误。
        //
        //   ★★★★ 3DS 打不开根治修复（'!' 原生路径前缀）★★★★
        //   Azahar 原生侧的 FileUtil::Exists / IOFile::Open 在 Android 上统一
        //   经 AndroidUtils::TranslateFilePath 翻译路径（android_utils.cpp 实测）：
        //     - 以 '!' 开头 → 剥掉 '!' 按原生绝对路径直读；
        //     - 其它（含 "/storage/..." 绝对路径）→ 被拼成 <userDir>/<原路径> ——
        //       文件必然“不存在”！
        //   参考 AzaharPlus APK 的 GameHelper.getGame 把游戏路径存成
        //   "!" + getNativePath(uri)，EmulationFragment 直接 run(该路径) ——
        //   NesStation 旧实现传裸绝对路径 → nativeFileExists=false /
        //   run() 加载失败 / CIA 安装 ErrorFileNotFound，全部同根因。
        //   现在与参考 APK 一致：传给核心的路径一律加 "!" 前缀。
        try {
            val nativeOk = lib.nativeFileExists("!$path")
            if (!nativeOk) {
                val msg = "无法从核心侧读取游戏文件（nativeFileExists=false）：\n$path" +
                    "\n\n请检查：1) 文件是否已被移动/删除；" +
                    "2) 系统设置 → 应用 → NesStation →『所有文件访问』权限是否开启；" +
                    "3) 若文件在外置存储/网盘，请先拷入本机存储。"
                lastErrorText = msg
                if (!userRequestedStop && isLoaded) {
                    try { onPrematureExit?.invoke("Azahar 模拟已结束\n\n$msg") } catch (_: Throwable) {}
                }
                return
            }
        } catch (_: Throwable) {
            // nativeFileExists 不可用（老库）→ 交给核心自身判断
        }
        // ★★★ 3DS 黑屏根治修复（surface 竞态终极加固）：在 lifecycleLock
        //   同步块内捕获 surface 到局部变量 bootSurface，整个启动流程
        //   都使用这个局部副本 —— 即便 setSurface(null) 在 startEmulationLocked
        //   返回后立刻把 this.surface 改成 null，新启动的 emuThread 仍然
        //   用 bootSurface 调 surfaceChanged/run，native EmuWindow 永远
        //   拿到非 null window。这就是用户日志 exitEmulationActivity(result=2)
        //   立即触发的根本原因（surface 在 thread 启动前已被 setSurface(null)
        //   置空，native EmuWindow_Android 构造读到 null → abort）。
        val bootSurface = surface ?: return
        if (!bootSurface.isValid) return
        try { lib.surfaceChanged(bootSurface) } catch (t: Throwable) {
            android.util.Log.w("AzaharEngine", "surfaceChanged failed", t)
        }
        if (_ffSpeed > 0) {
            try { lib.setTemporaryFrameLimit(100.0 * _ffSpeed) } catch (_: Throwable) {}
        }
        emuThread = thread(name = "AzaharNative") {
            // ★ 用启动时捕获的 bootSurface，不再读 volatile this.surface ——
            //   彻底消除"thread 启动到 surfaceChanged 之间 setSurface(null)
            //   把字段置空"的竞态窗口。
            //   若 Compose 重组销毁了原 SurfaceView，bootSurface 仍可能
            //   isValid=false —— 这种情况下 run() 会进入"surface is nullptr"
            //   abort；为兜底这种情况，循环重试最多 5 次拉取当前 surface，
            //   命中即推给 native 并 break，避免永久黑屏。
            var attemptedSurface = bootSurface
            if (!attemptedSurface.isValid) {
                // bootSurface 已失效 —— 等待新 surface 到达并重试（Compose 重建
                // SurfaceView 可能需要几百毫秒，旧版 5×50ms=250ms 太短）。
                var ready = false
                for (attempt in 1..30) {
                    Thread.sleep(100)
                    if (!running.get()) return@thread
                    val cur = surface
                    if (cur != null && cur.isValid) {
                        attemptedSurface = cur
                        ready = true
                        break
                    }
                }
                if (!ready) {
                    // ★★★ Vulkan 闪退根治：绝不能带着无效 surface 进入 run()！
                    //   原生 RunCitra 用 s_surface 构造 EmuWindow_Android_Vulkan，
                    //   null → “surface is nullptr” LOG_CRITICAL → 后续
                    //   vkCreateAndroidSurfaceKHR CHECK 失败 → SIGABRT（GL 路径
                    //   只是黑屏，VK 路径直接闪退 —— “vk 用不了会闪退”根因）。
                    //   这里放弃本次启动并上报；SurfaceView 重建后 setSurface()
                    //   会重新拉起 startEmulationLocked，不会卡死。
                    lastErrorText = "启动时 Surface 尚未就绪（视图重建中），已放弃本次启动；\n画面恢复后将自动重启。"
                    try {
                        onPrematureExit?.invoke(
                            "Azahar 启动等待 Surface 超时\n\n$lastErrorText\n" +
                                "\n常见原因：横竖屏切换/布局切换与游戏启动同时发生。\n" +
                                "返回后重进游戏即可。")
                    } catch (_: Throwable) {}
                    return@thread
                }
                try { lib.surfaceChanged(attemptedSurface) } catch (_: Throwable) {}
            } else {
                try { lib.surfaceChanged(attemptedSurface) } catch (_: Throwable) {}
            }
            try {
                // ★ '!' 前缀 = 原生绝对路径标记（见 startEmulationLocked 注释）。
                //   裸绝对路径会被 TranslateFilePath 拼到用户目录下变成不存在
                //   的路径 → 核心 "Failed to load ROM" → 黑屏/秒退。
                lib.run("!$path")
            } catch (t: Throwable) {
                android.util.Log.e("AzaharEngine", "run() crashed", t)
                lastErrorText = t.message ?: "run() crashed"
            }
            // run() 自然返回（未被用户停止）且核心从未报过错误 → 同样属于
            // 提前退出，上报（覆盖核心不调 exitEmulationActivity 的路径）。
            if (!userRequestedStop && isLoaded && emuThread === Thread.currentThread()) {
                // ★ 增强错误诊断：检查启动时 surface 状态、密钥文件存在性
                val diag = buildString {
                    append("Azahar 模拟已结束")
                    lastErrorText.takeIf { it.isNotBlank() }?.let { append("\n错误: ").append(it) }
                    val surf = surface
                    append("\n启动时 surface 状态: ")
                    if (surf == null) append("null（已被 setSurface(null) 销毁）")
                    else if (!surf.isValid) append("invalid（已释放）")
                    else append("valid (${surf})")
                    val attempted = attemptedSurface
                    append("\nemuThread 内 attempted surface: ")
                    if (attempted == null) append("null")
                    else if (!attempted.isValid) append("invalid")
                    else append("valid")
                    // ★ 3DS 必需文件（正确位置 <userDir>/sysdata/，与核心
                    //   KeyManager::LoadKeys 的读取路径一致）
                    append("\n\n")
                    append(sysDataDiag())
                    append("\n\n若非预期退出，常见原因：")
                    append("\n1) 加密卡带需 aes_keys.txt + seeddb.bin（放入 azahar/sysdata/，")
                    append("或放 azahar 目录 / 应用外置 files 目录由应用自动归位）")
                    append("\n2) ROM 文件损坏或不完整")
                    append("\n3) Surface 在核心启动期间被销毁（已被本修复加固）")
                    // ★★★ 核心真实日志尾部：run() 失败时 native 会在
                    //   azahar_log.txt 留下 "Failed to load ROM (Error N)!" 等
                    //   真实原因 —— 没有它一切诊断都是猜测。
                    nativeLogTail()?.let {
                        append("\n\n—— 核心日志尾部（azahar_log.txt）——\n")
                        append(it)
                    }
                }
                try { onPrematureExit?.invoke(diag) } catch (_: Throwable) {}
            }
        }
        startPresentation()

        // ★ 3DS 黑屏修复：核心完成 System::Init 后刷新一次帧缓冲布局。
        // 原生 updateFramebuffer 在 system.IsPoweredOn() 前是 no-op，而
        // OnSurfaceChanged 计算布局发生在 System::Init 之前 —— 启动窗口
        // 内若方向/尺寸判定与最终不一致，或寄存布局被后续流程重置，
        // 画面会停留在未初始化布局上（黑屏/单屏错位）。等核心真正跑起
        // 后再补一次 updateFramebuffer（与上游 runWithValidSurface 后的
        // 布局保障等价），最多等 15s，命中即刷并退出。
        if (layoutEnsurePending.compareAndSet(false, true)) {
            thread(name = "azahar-layout-ensure", isDaemon = true) {
                try {
                    var waited = 0
                    while (waited < 15_000) {
                        if (!running.get()) return@thread
                        val nativeRunning = try {
                            AzaharNative.lib.isRunning()
                        } catch (_: Throwable) { false }
                        if (isRunning2() && nativeRunning) {
                            // ★ surface 竞态终极保险：核心刚跑起来（EmuWindow 已
                            //   构造、原生 running 标志已置位）时，把**当前**
                            //   surface 再推给核心一次 —— 原生 surfaceChanged
                            //   会把新窗口转发给已构造的 EmuWindow（见
                            //   EmuWindow_Android::OnSurfaceChanged）。无论启动
                            //   过程中发生过多少次 SurfaceView 重建抖动，这一步
                            //   都把呈现窗口收敛到用户眼前这块 Surface。
                            val surf = surface
                            if (surf != null && surf.isValid) {
                                try { AzaharNative.lib.surfaceChanged(surf) } catch (_: Throwable) {}
                            }
                            refreshFramebufferLayout()
                            // ★ 补做启动期被推迟的 surface 尺寸重置（见
                            //   onSurfaceChanged 的 Vulkan 竞态加固注释）：
                            //   核心此刻已就绪，destroy/recreate 对安全。
                            if (pendingSurfaceResize.compareAndSet(true, false)) {
                                val rs = surface
                                if (rs != null && rs.isValid) {
                                    try {
                                        AzaharNative.lib.surfaceDestroyed()
                                        AzaharNative.lib.surfaceChanged(rs)
                                    } catch (_: Throwable) {}
                                    refreshFramebufferLayout()
                                }
                            }
                            return@thread
                        }
                        Thread.sleep(100)
                        waited += 100
                    }
                } catch (_: InterruptedException) {
                } finally {
                    layoutEnsurePending.set(false)
                }
            }
        }
    }

    /** UI 线程 Choreographer 每帧 → doFrame()（上游 EmulationFragment 同款）。 */
    private fun startPresentation() {
        if (!presentationActive.compareAndSet(false, true)) return
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!presentationActive.get()) return
                try { AzaharNative.lib.doFrame() } catch (_: Throwable) {}
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = cb
        mainHandler.post { Choreographer.getInstance().postFrameCallback(cb) }
    }

    private fun stopPresentation() {
        frameCallback?.let { cb ->
            mainHandler.post { Choreographer.getInstance().removeFrameCallback(cb) }
        }
        frameCallback = null
        presentationActive.set(false)
    }

    override fun setSurface(surface: Surface?) {
        synchronized(lifecycleLock) {
            this.surface = surface
            if (!isLoaded) return
            val lib = AzaharNative.lib
            if (surface != null) {
                // 与上游 EmulationState.newSurface 相同：先挂 Surface，若线程未启动则启动
                try { lib.surfaceChanged(surface) } catch (_: Throwable) {}
                if (emuThread?.isAlive != true) startEmulationLocked()
            } else {
                // ★★★ 3DS 黑屏修复关键路径：surface=null 在 boot 窗口内时，
                //   1) 不调 surfaceDestroyed（避免 native EmuWindow 构造读到 null）
                //   2) **不**让正启动中的 emuThread 读到 null surface ——
                //      startEmulationLocked 已用 bootSurface 局部变量绕开此问题。
                //   但若 boot 窗口已结束（nativeRunning=true），可以安全通知销毁。
                val nativeRunning = try { lib.isRunning() } catch (_: Throwable) { false }
                val booting = emuThread?.isAlive == true
                if (!booting || nativeRunning) {
                    try { lib.surfaceDestroyed() } catch (_: Throwable) {}
                }
            }
        }
    }

    override fun onSurfaceChanged(surface: Surface?, width: Int, height: Int) {
        if (surface == null) {
            onSurfaceDestroyed()
            return
        }
        if (surface != this.surface) {
            setSurface(surface)
            lastSurfaceW = width
            lastSurfaceH = height
            return
        }
        // ★★★ 3DS 双屏跟随全局「画面缩放」修复（本补丁核心）★★★
        //
        // 病灶：SurfaceView 尺寸变化（用户改 videoScale：4:3/16:9/custom…，
        // 或旋转屏幕）时，Android 复用**同一个 Surface 实例**仅改尺寸 ——
        // 原生 EmuWindow_Android::OnSurfaceChanged 对相同 ANativeWindow
        // 直接 return false（emu_window.cpp：`if (render_window == surface)
        // return false;`），window_width/height 永不重读 → 核心继续按**旧尺寸**
        // 布局双屏，SurfaceFlinger 把旧画面拉伸到新视图 → 用户看到的
        // “3DS 没有根据全局屏幕缩放”的直接根因。
        //
        // 修复：同实例但尺寸变化时，先 surfaceDestroyed（原生侧把
        // render_window 置 null）再 surfaceChanged（重新拿到同一个
        // ANativeWindow —— 此时 render_window != surface，强制重读
        // ANativeWindow_getWidth/Height → OnFramebufferSizeChanged →
        // UpdateCurrentFramebufferLayout 以新尺寸重算双屏布局）。
        // 与上游真实 destroy/create 周期同路径，邮箱（mailbox）机制自带
        // 处理，日志中的 “Failed to recreate present FBO!” 为无害自愈信息。
        if (isLoaded && width > 0 && height > 0 &&
            (width != lastSurfaceW || height != lastSurfaceH)) {
            lastSurfaceW = width
            lastSurfaceH = height
            val lib = AzaharNative.lib
            // ★★★ Vulkan 启动竞态加固：核心启动中（emuThread 存活且原生
            //   尚未进入主循环 —— isRunning()=false，即 System::Init/渲染器
            //   构造窗口期）绝不做 surfaceDestroyed+surfaceChanged 对。
            //   旧实现在这里无保护地打这对组合拳：destroy 后原生
            //   render_window/s_surface 短暂为 null，恰逢 RunCitra 构造
            //   EmuWindow_Android_Vulkan(s_surface=null) → “surface is
            //   nullptr” → vkCreateAndroidSurfaceKHR CHECK → SIGABRT
            //   （GL 路径仅黑屏自愈，VK 路径必闪退）。
            //   启动期改记 pendingSurfaceResize，由 azahar-layout-ensure
            //   线程在核心就绪后补做（见 startEmulationLocked）。
            val nativeRunning = try { lib.isRunning() } catch (_: Throwable) { false }
            val booting = emuThread?.isAlive == true && !nativeRunning
            if (booting) {
                pendingSurfaceResize.set(true)
            } else {
                try {
                    lib.surfaceDestroyed()
                    lib.surfaceChanged(surface)
                } catch (_: Throwable) {}
                refreshFramebufferLayout()
            }
        }
    }

    /** 启动期被推迟的 surface 尺寸重置（layout-ensure 线程补做）。 */
    private val pendingSurfaceResize = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 上次上报给引擎的 Surface 尺寸（尺寸变化检测用）。 */
    @Volatile private var lastSurfaceW = 0
    @Volatile private var lastSurfaceH = 0

    override fun onSurfaceDestroyed() {
        setSurface(null)
    }

    override fun setSaveName(name: String) {
        // Azahar 存档按 TitleId 组织（states/<titleId>.state.x），无需前端命名
    }

    override fun setPaused(paused: Boolean) {
        if (_paused == paused) return
        _paused = paused
        if (!isLoaded) return
        val lib = AzaharNative.lib
        try {
            if (paused) lib.pauseEmulation() else lib.unPauseEmulation()
        } catch (t: Throwable) {
            android.util.Log.w("AzaharEngine", "pause/resume", t)
        }
    }

    override fun reset(hard: Boolean) {
        synchronized(lifecycleLock) {
            if (!isLoaded || romPath == null) return@synchronized
            val lib = AzaharNative.lib
            userRequestedStop = true   // 重置属于用户请求，退出不上报
            try {
                lib.stopEmulation()
                emuThread?.join(8000)
            } catch (_: Throwable) {}
            emuThread = null
            userRequestedStop = false
            val surf = surface ?: return@synchronized
            try { lib.surfaceChanged(surf) } catch (_: Throwable) {}
            startEmulationLocked()
        }
    }

    override fun unload() = synchronized(lifecycleLock) { cleanupLocked() }

    override fun shutdown() = synchronized(lifecycleLock) { cleanupLocked() }

    private fun cleanupLocked() {
        userRequestedStop = true   // 主动卸载，run() 返回不上报
        running.set(false)
        heartbeatThread?.let { t ->
            t.interrupt()
            try { t.join(500) } catch (_: InterruptedException) {}
        }
        heartbeatThread = null

        if (emuThread?.isAlive == true) {
            try {
                AzaharNative.lib.stopEmulation()
                emuThread?.join(8000)
            } catch (_: Throwable) {}
        }
        emuThread = null

        stopPresentation()
        try { AzaharNative.lib.surfaceDestroyed() } catch (_: Throwable) {}
        surface = null
        lastSurfaceW = 0
        lastSurfaceH = 0
        isLoaded = false
        _paused = false
        _ffSpeed = 0
        _netFrame = 0
        lastErrorText = ""
    }

    // ------------------------------------------------------------------
    // 视频 / 快进 / 性能
    // ------------------------------------------------------------------

    override fun videoWidth(): Int {
        // ★ 取值标度修复后的 resolution_factor：0=自动（适配窗口），1=1x 原生，
        //   n=n 倍。旧实现 (f+1) 按旧错误标度计算，修正为直接乘倍数。
        val f = coreOptions["Renderer/resolution_factor"]?.toIntOrNull() ?: 1
        return 400 * (if (f <= 0) 1 else f.coerceIn(1, 5))
    }

    override fun videoHeight(): Int {
        val f = coreOptions["Renderer/resolution_factor"]?.toIntOrNull() ?: 1
        return 240 * (if (f <= 0) 1 else f.coerceIn(1, 5))
    }

    /**
     * 核心真实实时帧率：getPerfStats()[1] = game_fps（getPerfStats 为推模型
     * 核心 —— 心跳计数无意义，与 PS2 引擎同理）。
     */
    override fun realtimeFps(): Double {
        if (!isLoaded) return 0.0
        return try {
            val stats = AzaharNative.lib.getPerfStats()
            if (stats != null && stats.size > 1) stats[1] else 0.0
        } catch (_: Throwable) {
            0.0
        }
    }

    /**
     * ★ 3DS 黑屏修复（启动看门狗配套）：把原始 PerfStats 暴露给 UI 层。
     * getPerfStats() 返回 [system_fps, game_fps, emulation_speed, ...]；
     * 看门狗用 system_fps（下标 0）判断核心是否真的在跑帧。
     */
    fun getPerfStats(): DoubleArray? = try {
        AzaharNative.lib.getPerfStats()
    } catch (_: Throwable) {
        null
    }

    /** 看门狗用：当前 surface 是否有效（诊断启动时序问题）。 */
    fun isSurfaceValid(): Boolean = surface?.isValid == true

    /** 看门狗用：Azahar 用户目录绝对路径（aes_keys.txt / boot9.bin 所在）。 */
    fun userDirectoryPath(): String = try { userDir() } catch (_: Throwable) { "" }

    override fun setVideoFilter(filter: Int) {
        // 前端滤镜不适用于直绘核心（核心自身有 texture_filter）——忽略
    }

    override fun setHighQualityScaling(enabled: Boolean) {
        // 直绘核心无 CPU 缩放路径 —— 忽略
    }

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (!isLoaded) return
        try {
            val lib = AzaharNative.lib
            if (speed > 0) lib.setTemporaryFrameLimit(100.0 * speed)
            else lib.disableTemporaryFrameLimit()
        } catch (t: Throwable) {
            android.util.Log.w("AzaharEngine", "fast-forward", t)
        }
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /**
     * libretro 布局位（UI projectToLibretroLayout 输出）+ ZL(bit16)/ZR(bit17)
     * → Azahar ButtonType 按键事件（原生 PressKey/ReleaseKey 状态机）。
     */
    override fun setPad1(bits: Int) {
        if (!isLoaded) return
        pushButtons(bits)
    }

    override fun setPad2(bits: Int) {
        // Touchscreen 设备为 P1 专属（原生单通道）——P2 不支持
    }

    private fun pushButtons(bits: Int) {
        val lib = AzaharNative.lib
        val dev = NativeLibrary.TOUCHSCREEN_DEVICE
        val pairs = listOf(
            NativeLibrary.ButtonType.BUTTON_A to BIT_A,
            NativeLibrary.ButtonType.BUTTON_B to BIT_B,
            NativeLibrary.ButtonType.BUTTON_X to BIT_X,
            NativeLibrary.ButtonType.BUTTON_Y to BIT_Y,
            NativeLibrary.ButtonType.BUTTON_SELECT to BIT_SELECT,
            NativeLibrary.ButtonType.BUTTON_START to BIT_START,
            NativeLibrary.ButtonType.DPAD_UP to BIT_UP,
            NativeLibrary.ButtonType.DPAD_DOWN to BIT_DOWN,
            NativeLibrary.ButtonType.DPAD_LEFT to BIT_LEFT,
            NativeLibrary.ButtonType.DPAD_RIGHT to BIT_RIGHT,
            NativeLibrary.ButtonType.TRIGGER_L to BIT_L,
            NativeLibrary.ButtonType.TRIGGER_R to BIT_R,
            NativeLibrary.ButtonType.BUTTON_ZL to BIT_ZL,
            NativeLibrary.ButtonType.BUTTON_ZR to BIT_ZR
        )
        for ((button, bit) in pairs) {
            val pressed = (bits and bit) != 0
            if (pressed) {
                lib.onGamePadEvent(dev, button, NativeLibrary.ButtonState.PRESSED)
            } else {
                lib.onGamePadEvent(dev, button, NativeLibrary.ButtonState.RELEASED)
            }
        }
    }

    override fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float) {
        if (!isLoaded) return
        val lib = AzaharNative.lib
        val dev = NativeLibrary.TOUCHSCREEN_DEVICE
        try {
            // CirclePad（STICK_LEFT=713）与 C-Stick（STICK_C=718）。
            // 原生侧翻转 y（Citra 内部 y 向上为正），这里保持屏幕坐标约定
            // （向上为负）直传 —— 与上游 InputOverlayDrawableJoystick 一致。
            lib.onGamePadMoveEvent(dev, NativeLibrary.ButtonType.STICK_LEFT, lx, ly)
            lib.onGamePadMoveEvent(dev, NativeLibrary.ButtonType.STICK_C, rx, ry)
        } catch (_: Throwable) {}
    }

    override fun setTouchInput(x: Float, y: Float, pressed: Boolean) {
        if (!isLoaded) return
        try {
            AzaharNative.lib.onTouchEvent(x, y, pressed)
        } catch (_: Throwable) {}
    }

    override fun setTouchMoved(x: Float, y: Float) {
        if (!isLoaded) return
        try {
            AzaharNative.lib.onTouchMoved(x, y)
        } catch (_: Throwable) {}
    }

    override fun swapScreens() {
        if (!isLoaded) return
        try {
            AzaharNative.lib.swapScreens(!screensSwapped, 0)
            screensSwapped = !screensSwapped
        } catch (_: Throwable) {}
    }

    @Volatile private var screensSwapped = false

    // ------------------------------------------------------------------
    // 存档 / 截图 / 其它
    // ------------------------------------------------------------------

    /**
     * 核心 states 目录下指定槽位的真实 .cst 文件（<userDir>/states/
     * <titleId16hex>_version_2005.<slot02>.cst）。找不到返回 null。
     */
    private fun realStateFile(slot: Int): File? {
        val statesDir = File(userDir(), "states")
        val files = statesDir.listFiles { f -> f.isFile && f.name.endsWith(".cst") }
            ?: return null
        val suffix = "_%02d.cst".format(slot)
        // 文件名形如 0004000000055F00_version_2005.01.cst —— 按后缀匹配槽位。
        return files.firstOrNull { it.name.endsWith(suffix) }
            ?: files.firstOrNull {
                // 宽容：无 _version_ 段的非常规命名（未来版本格式变化）
                it.name.endsWith(".state.${slot}.cst") || it.name.endsWith("_${slot}.cst")
            }
    }

    /**
     * ★★ 3DS 即时存档无效修复（本轮）★★
     * 旧实现三重问题：
     *   1. saveState 是**发后不管**的异步信号（RunLoop 下一帧才消费），
     *      旧代码却立即返回 true + 无条件创建假标记文件 → UI 弹"已存档"
     *      而核心可能静默失败（暂停中信号永不消费 / LLE 开启 / app loader
     *      不支持）——“存档无作用"的直接根因；
     *   2. 暂停态（菜单打开时 engine.setPaused(true)）下模拟线程停在
     *      条件变量上，RunLoop 不再执行 → 存/读档信号永远不被消费；
     *   3. 读档前置校验只看 NesStation 自己的假标记文件而非核心真实
     *      .cst —— 假标记存在 → 发 Load 信号 → 核心抛异常 → 旧
     *      onCoreError 返回 false 终止模拟 → "读取直接卡死"。
     * 修复：
     *   - 暂停中先临时解除（unPauseEmulation），让 RunLoop 消费信号；
     *   - 存档后轮询真实 .cst 落盘（最多 5s），确认后才写标记并复制
     *     真实字节到 dst（UI 的 exists/大小/时间全部真实）；超时返回 false；
     *   - 读档前先确认真实 .cst 存在，不存在直接返回 false（不发信号，
     *     不触发核心错误路径）；读档成功后恢复原暂停态。
     */
    override fun saveState(slot: Int, dst: File): Boolean {
        if (!isLoaded) return false
        return try {
            val wasPaused = _paused
            if (wasPaused) {
                try { AzaharNative.lib.unPauseEmulation() } catch (_: Throwable) {}
            }
            val before = realStateFile(slot)
            val beforeTime = before?.lastModified() ?: 0L
            AzaharNative.lib.saveState(slot)
            // 轮询核心真实槽位文件落盘/更新（RunLoop 异步消费，同步执行
            // ZSTD 压缩 + 落盘，大游戏可能秒级）。
            val deadline = System.currentTimeMillis() + 5000
            var real: File? = null
            while (System.currentTimeMillis() < deadline) {
                real = realStateFile(slot)
                if (real != null && real.exists() && real.length() > 0 &&
                    real.lastModified() > beforeTime) break
                Thread.sleep(100)
            }
            if (wasPaused) {
                try { AzaharNative.lib.pauseEmulation() } catch (_: Throwable) {}
            }
            if (real == null || !real.exists() || real.length() == 0L) {
                android.util.Log.w("AzaharEngine", "saveState($slot): 核心未落盘真实 .cst（可能暂停中或 LLE 开启）")
                lastErrorText = "存档失败：核心未写入状态文件"
                return false
            }
            // 标记文件 = 真实 .cst 的完整字节拷贝（UI 的槽位列表/大小/时间
            // 从此真实，读档前置检查也对得上）。
            try {
                dst.parentFile?.mkdirs()
                real.copyTo(dst, overwrite = true)
            } catch (t: Throwable) {
                android.util.Log.w("AzaharEngine", "saveState($slot): 标记拷贝失败", t)
                if (!dst.exists()) dst.createNewFile()
            }
            true
        } catch (t: Throwable) {
            android.util.Log.w("AzaharEngine", "saveState($slot)", t)
            false
        }
    }

    override fun loadState(slot: Int, src: File): Boolean {
        if (!isLoaded) return false
        return try {
            // ★ 前置校验：核心 states 目录必须存在真实 .cst（不看 NesStation
            //   的标记文件）—— 不存在时直接失败，不发 Load 信号（避免触发
            //   核心异常路径）。标记文件存在但核心 .cst 缺失（旧版本假标记
            //   残留）时自动导入：把标记字节回写为核心槽位路径。
            var real = realStateFile(slot)
            if (real == null || !real.exists() || real.length() == 0L) {
                if (src.exists() && src.length() > 0) {
                    val statesDir = File(userDir(), "states").apply { mkdirs() }
                    real = File(statesDir, "nesstation_slot%02d.cst".format(slot))
                    try {
                        src.copyTo(real, overwrite = true)
                        android.util.Log.i("AzaharEngine", "loadState($slot): 从标记文件回填核心槽位 $real")
                    } catch (t: Throwable) {
                        android.util.Log.w("AzaharEngine", "loadState($slot): 回填失败", t)
                    }
                }
                if (real == null || !real.exists() || real.length() == 0L) {
                    lastErrorText = "读档失败：槽位 $slot 无核心状态文件"
                    android.util.Log.w("AzaharEngine", "loadState($slot): 无真实 .cst")
                    return false
                }
            }
            val wasPaused = _paused
            if (wasPaused) {
                try { AzaharNative.lib.unPauseEmulation() } catch (_: Throwable) {}
            }
            // 发 Load 信号（RunLoop 在模拟线程内同步反序列化，期间画面
            // 冻结数秒是正常现象）。失败走 onCoreError(ErrorSavestate)
            // → 已改为返回 true 继续运行，不再终止模拟。
            AzaharNative.lib.loadState(slot)
            // 给 RunLoop 一点时间消费信号（读档在下一帧触发），随后恢复
            // 原暂停态（菜单打开时用户期望仍处于暂停）。
            try { Thread.sleep(600) } catch (_: InterruptedException) {}
            if (wasPaused) {
                try { AzaharNative.lib.pauseEmulation() } catch (_: Throwable) {}
            }
            true
        } catch (t: Throwable) {
            android.util.Log.w("AzaharEngine", "loadState($slot)", t)
            false
        }
    }

    override fun captureFrame(): FrameCapture? {
        // 核心直绘 Surface，无帧缓冲回读 —— 截图不可用（同 PS2/ARMSX2）
        return null
    }

    override fun setRegion(region: Int) {}
    override fun setSampleRate(rate: Int) {}

    override fun lastError(): String = lastErrorText

    private fun isRunning2(): Boolean = isLoaded && emuThread?.isAlive == true

    // 项目位布局（EmulatorScreen 直传：A=bit0, B=bit1, Select=2, Start=3,
    // U/D/L/R=4..7, X=8, Y=9, L=10, R=11, L2=12, R2=13；扩展位 ZL=16, ZR=17）
    companion object {
        private const val BIT_A = 0x01
        private const val BIT_B = 0x02
        private const val BIT_SELECT = 0x04
        private const val BIT_START = 0x08
        private const val BIT_UP = 0x10
        private const val BIT_DOWN = 0x20
        private const val BIT_LEFT = 0x40
        private const val BIT_RIGHT = 0x80
        private const val BIT_X = 0x100
        private const val BIT_Y = 0x200
        private const val BIT_L = 0x400
        private const val BIT_R = 0x800
        const val BIT_ZL = 0x10000
        const val BIT_ZR = 0x20000

        @Volatile private var instance: AzaharEngine? = null
        fun get(): AzaharEngine = instance ?: synchronized(this) {
            instance ?: AzaharEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
