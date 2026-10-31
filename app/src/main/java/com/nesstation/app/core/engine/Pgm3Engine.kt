package com.nesstation.app.core.engine

import android.content.Context
import android.os.Build
import android.view.Surface
import com.codegg.afbn.pgm3.Pgm3Activity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ★★ v52 PGM3 引擎（三国战记3 HD / IGS M027 基板专用核心）。
 *
 * 背景（用户需求："兼容三国战记3hd版"）：KOV3-HD 运行在 IGS M027（PGM2 世
 * 代）基板上 —— 与 PGM1（FBNeo 的 d_pgm.cpp）完全不同的硬件：ARM926 主控
 * + 定制 ASIC + 数学协处理器固件。FBNeo 拉模型核心跑不了它；本项目随
 * v52 引入参考实现（afbn）同源的预编译专用核心 libpgm3.so：
 *
 *   - 自包含（仅依赖系统 libandroid/libEGL/libGLESv2/libOpenSLES/
 *     libmediandk，零 SDL），38MB，仅 arm64-v8a（32 位设备在
 *     ensureLoaded 优雅报告"核心不可用"，不崩溃）；
 *   - 推模型核心：nativeRun 启动后模拟循环/渲染（GL 直绘 Surface）/
 *     音频（OpenSLES）全部在核心内部线程 —— 与 ARMSX2/PSP 推模型同
 *     架构，EmulatorScreen 的拉模型路径（frameBuffer/onFrame/FPS 计数）
 *     对本引擎自然退化为无操作；
 *   - 运行时数学协处理器固件（startup/division/matrix/float/mcu-startup
 *     五个 oracle bin）随 assets/pgm3_runtime 分发，首次启动解包到
 *     <filesDir>/PGM3/runtime/（对齐参考实现 Pgm3Support.prepareRuntime：
 *     .pending 原子写 + 重命名，防半截文件）；
 *   - ROM 布局（对齐参考实现 Pgm3Support）：kov3hd.zip（romset）+
 *     同目录 kov3hd/kov3hd_m105.chd（CHD 光盘镜像，必填）—— 用户把
 *     kov3hd.zip 与 kov3hd 文件夹放同一目录即可；直接选 kov3hd_m105.chd
 *     启动也支持（反向找同目录 kov3hd.zip）。
 *
 * 输入映射：本项目街机虚拟手柄走 arcadeToLibretroLayout 后到达本引擎
 * （标准 libretro JOYPAD 位），此处转 PGM3 MCU 位（参考实现 toMcu 同一
 * 语义）：Start→0x1 Up→0x2 Down→0x4 Left→0x8 Right→0x10，
 * A/B/X/Y→攻击1/2/3/4（0x20/0x40/0x80/0x100）；投币 = Select 按下沿
 * → nativeCoin（PGM3 投币走专用通道，不在 pad 位里）。
 *
 * 联机对战：本核心为推模型（模拟循环在原生线程内），无法接入 frameHook
 * 锁步 —— kov3hd 暂不支持联机（对战平台 PGM1 街机不受影响）。即时存档
 * 同理不可用（PGM3 进度 = 记忆卡语义，走核心内部卡槽）。
 */
class Pgm3Engine private constructor() : EmulatorEngine {

    /** 推模型核心不产生前端帧缓冲（GL 直绘）—— 占位满足接口。 */
    override val frameBuffer: IntArray = IntArray(1)

    @Volatile override var isLoaded: Boolean = false
        private set

    /** 核心是否已启动（nativeRun 成功）。 */
    @Volatile private var started = false

    private val loaded = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null
    @Volatile private var lastErr: String = ""
    @Volatile private var surfaceReady = false
    @Volatile private var pausedExt = false

    /** ★★ v58 启动进度（等待首帧期间由 watchdog 更新，前端轮询显示；null = 无启动进行中）。 */
    @Volatile var bootProgress: String? = null
        private set

    /** 上一次各玩家 pad（Select 投币按下沿检测）。 */
    private val lastPad = IntArray(2)

    /** libretro JOYPAD Select 位（投币）。 */
    private val LIBRETRO_SELECT = 0x04

    /** 联机锁步钩子 —— 推模型核心不支持，恒 null（设置被忽略）。 */
    override var frameHook: NetplayHook?
        get() = null
        set(_) { /* 推模型核心：模拟循环在原生线程内，无法挂钩 */ }

    private val lifecycleLock = Any()

    companion object {
        private const val TAG = "Pgm3Engine"

        /** PGM3 romset 名（对齐参考实现 Pgm3Support 的路由判定）。 */
        private const val PGM3_ROMSET = "kov3hd"

        /** CHD 镜像名（kov3hd 必需的伴生光盘）。 */
        private const val PGM3_CHD_NAME = "kov3hd_m105.chd"

        /** 运行时固件（assets/pgm3_runtime → <root>/runtime）。 */
        private val RUNTIME_ASSETS = listOf(
            "startup-oracle.bin", "division-oracle.bin", "mcu-startup-oracle.bin",
            "float-oracle-dcmplt.bin", "matrix-oracle.bin"
        )

        // ★★ v59 启动监护超时矩阵（毫秒）—— ★ v60 再放宽：
        //    用户实测大 CHD「读取/解压需要 1~2 分钟」，期间核心可能完全静默
        //    （不写日志、状态不变、无帧 —— LZMA 解压是纯 CPU 工作，无任何
        //    外部可见信号）。旧值静默 30s / 首帧 90s 会把正在解压的核心
        //    误判为死亡强制失败。新矩阵：静默 150s（≥ 解压时长上限）、
        //    首帧 300s、总限 480s —— 宁可多等，绝不误杀加载中的核心。
        /** 首帧等待上限：完全无活动信号时最多等这么久。 */
        internal const val FIRST_FRAME_TIMEOUT_MS = 300_000L
        /** 总上限：只要核心有活动（状态/日志在变）最多等这么久。 */
        internal const val TOTAL_TIMEOUT_MS = 480_000L
        /** 静默判死：自最近一次活动起三信号全无变化这么久 → 核心已死。
         *  ★ v60：30s → 150s —— 大 CHD 解压期间核心静默 1~2 分钟是正常现象。 */
        internal const val SILENCE_TIMEOUT_MS = 150_000L

        /** 启动期明确失败语义（nativeStatus 文本 / interactive.log 内容命中即报错；
         *  全部来自 libpgm3 二进制里实际存在的失败字符串，避免误伤正常加载日志）。
         *  ★★ v59 补 CHD 打开失败的二进制真实串（"Cannot open original m105 CHD: "
         *  / "Cannot open original kov3hd.zip" / "CHD hunk decompression failed"
         *  等 —— 大写开头，旧表里的 "cannot open" 小写匹配不上）。 */
        internal val STARTUP_ERROR_KEYWORDS = listOf(
            "Unsupported CHD", "unsupported chd",
            "Cannot open original m105 CHD", "Cannot open original kov3hd.zip",
            "CHD hunk decompression failed", "CHD read requires a regular file",
            "CHD mapped read out of range", "Invalid CHD path",
            "EGL window mode target is missing", "GPU presenter surface size mismatch",
            "无法打开", "无法创建", "无法读取", "不存在", "不完整", "校验失败",
            "读取失败", "写入失败", "加载失败", "初始化失败",
            "游戏会话已结束", "cannot open", "No such file or directory",
            "FAIL FRAM", "FAIL:"
        )

        @Volatile private var instance: Pgm3Engine? = null
        fun get(): Pgm3Engine = instance ?: synchronized(this) {
            instance ?: Pgm3Engine().also { instance = it }
        }

        /** 路由判定：该 ROM 是否为 PGM3 专用核心游戏（kov3hd，含选 CHD 启动）。 */
        fun isPgm3Rom(romPath: String?): Boolean {
            if (romPath.isNullOrBlank()) return false
            val name = File(romPath).nameWithoutExtension.lowercase()
            return name == PGM3_ROMSET || name == PGM3_CHD_NAME.removeSuffix(".chd")
        }
    }

    // === 生命周期 ===

    /** 注入应用上下文（assets 解包用；EmulatorScreen 选择本引擎时调用）。 */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    override fun ensureLoaded(): Boolean {
        if (loaded.get()) return true
        // 仅 arm64 —— 32 位设备优雅失败（对齐 Cemu/Eden 的 probeAvailability 语义）
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            lastErr = "三国战记3HD 核心仅支持 64 位（arm64-v8a）设备"
            return false
        }
        return try {
            System.loadLibrary("pgm3")
            loaded.set(true)
            true
        } catch (t: Throwable) {
            lastErr = "加载 PGM3 核心失败：${t.message}"
            android.util.Log.e(TAG, "ensureLoaded", t)
            false
        }
    }

    override fun loadRom(rom: File, systemDir: String, saveDir: String, onFrame: () -> Unit): Boolean =
        synchronized(lifecycleLock) {
            if (!ensureLoaded()) return false
            cleanup()

            val ctx = appContext ?: run {
                lastErr = "PGM3 引擎未初始化上下文"
                return false
            }

            // 1. 运行根目录 = <filesDir>/PGM3（对齐参考实现 resourceRoot）。
            //    ARCADE 的 systemDir = <filesDir>/fbneo → filesDir = 其父目录。
            val filesDir = File(systemDir).parentFile ?: ctx.filesDir
            val root = File(filesDir, "PGM3")

            // 2. 解包数学协处理器固件（首次/升级后；.pending 原子写防半截文件）
            try {
                prepareRuntime(ctx, root)
            } catch (t: Throwable) {
                lastErr = "PGM3 运行时固件解包失败：${t.message}"
                android.util.Log.e(TAG, "prepareRuntime", t)
                return false
            }

            // 3. 解析 romset + CHD（对齐参考实现 Pgm3Support.romZip/romChd；
            //    ★★ v59 查找范围扩展：参考实现只查 zip 同目录的 kov3hd/ 子目录，
            //    用户实际文件布局五花八门（CHD 平铺、CHD 在 app 的 ROM 目录、
            //    zip 在子目录而 CHD 在上层…）—— "无法读取chd"的大多数报错都是
            //    路径没找到。现按优先级多位置查找（含 fbneo system 目录与
            //    以选中文件为根的两层子目录扫描）。
            val romZip: File
            val romChd: File
            val lower = rom.name.lowercase()
            if (lower.endsWith(".chd") ||
                rom.nameWithoutExtension.equals(PGM3_CHD_NAME.removeSuffix(".chd"), true)
            ) {
                // 直接选了 CHD 启动：CHD = 选中文件，zip 多位置查找
                romChd = rom
                romZip = resolveZipBeside(rom, systemDir)
            } else {
                romZip = rom
                romChd = resolveChdBeside(rom, systemDir)
            }
            if (!romZip.isFile) {
                lastErr = "缺少 romset：kov3hd.zip（应与 ${rom.name} 放同一目录，" +
                    "或放在 ${File(systemDir).absolutePath} 目录里）"
                return false
            }
            if (!romChd.isFile) {
                lastErr = "缺少光盘镜像：${PGM3_CHD_NAME}\n" +
                    "已查找：\n" +
                    "  1. ${File(romZip.parentFile, "kov3hd").absolutePath}\n" +
                    "  2. ${romZip.parent}\n" +
                    "  3. ${romZip.parentFile?.parentFile}\n" +
                    "  4. ${File(systemDir, "kov3hd").absolutePath} 与 ${File(systemDir).absolutePath}\n" +
                    "请把 kov3hd 文件夹（内含 ${PGM3_CHD_NAME}）放到 kov3hd.zip 同一目录，" +
                    "或把 ${PGM3_CHD_NAME} 直接平铺在同级目录。"
                return false
            }
            if (romChd.length() < 124) {
                lastErr = "CHD 文件不完整：${romChd.absolutePath}"
                return false
            }

            // 4. 启动（root 及核心要求的子目录需在 nativeRun 前存在；返回 0 = 成功）
            // ★★ v55 FRAM 存档目录修复（用户实测 "FAIL FRAM: cannot open app save
            //    directory: No such file or directory" 的根因）：libpgm3 的
            //    nativeRun 解析 FRAM 存档目录 = getenv("PGM3_FRAM_SAVE")，未设置时
            //    默认 <root>/fram-save（二进制核实：0x7fe6a0 处 root+"/fram-save"
            //    拼接 + setenv(PGM3_FRAM_SAVE)）→ FRAM 服务以 O_DIRECTORY 打开该
            //    目录（fstat 校验 S_IFDIR —— 绝不自动创建）。v52 桩只建了 root 与
            //    root/runtime，没建 fram-save → "cannot open app save directory"。
            //    同理建 memcard/kov3hd（核心虚拟记忆卡目录："新卡必须位于
            //    memcard/kov3hd 目录"）。均为空目录（核心自己写 fram.bin/
            //    fram.pending/fram.lock）。
            root.mkdirs()
            File(root, "fram-save").apply { if (!isDirectory) mkdirs() }
            File(root, "memcard/kov3hd").apply { if (!isDirectory) mkdirs() }

            // ★★ v59 记忆卡/金手指子系统配置（用户实测"无法正常启动"的根因）：
            //    反编译参考实现（afbn-release-64.apk，其 kov3hd 实测可运行）
            //    的启动序列：surfaceCreated → nativeSurface(surface) →
            //    fram-save 目录 → Pgm3CardDialog.configure（= nativeCardConfigure，
            //    失败即中止启动："记忆卡初始化失败"）→ Pgm3CheatDialog.configure
            //    （= nativeCheatConfigure，失败仅提示不中止）→ 工作线程 nativeRun。
            //    此前本项目从未调用 nativeCardConfigure —— 核心的记忆卡子系统
            //    未初始化即启动，参考实现里这是必经步骤。现对齐补齐。
            val memcardDir = File(root, "memcard/kov3hd")
            val cardOk = try {
                Pgm3Activity.nativeCardConfigure(memcardDir.absolutePath)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "nativeCardConfigure: ${t.message}")
                false
            }
            if (!cardOk) {
                lastErr = "记忆卡初始化失败：" + (try {
                    Pgm3Activity.nativeCardError()
                } catch (_: Throwable) { null }?.takeIf { it.isNotBlank() } ?: "未知错误")
                android.util.Log.e(TAG, "nativeCardConfigure failed: $lastErr")
                return false
            }
            // 金手指配置：目录 + ini 路径（文件不存在是合法状态 —— 核心按需读取）
            val cheatsDir = File(root, "cheats").apply { if (!isDirectory) mkdirs() }
            try {
                Pgm3Activity.nativeCheatConfigure(File(cheatsDir, "kov3hd.ini").absolutePath)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "nativeCheatConfigure: ${t.message}（不影响启动）")
            }

            // ★★ v57 Surface 先行等待（用户实测“黑屏 0 帧”的第一根因）：
            //    参考实现是 Activity 流程 —— 先 surfaceCreated（窗口就绪）
            //    再 nativeRun；本项目 Compose 加载 effect（IO 线程）与
            //    SurfaceView 组合并发，nativeRun 可能先于 surfaceCreated 执行，
            //    核心启动时 EGL 窗口目标缺失（libpgm3 二进制串
            //    "original EGL window mode target is missing" /
            //    "GPU presenter surface size mismatch" 即此路径）。
            //    在 nativeRun 前有界等待 Surface 就绪（loadRom 已在 IO 线程，
            //    阻塞安全；setSurface 已无锁化，不会被 lifecycleLock 阻塞）。
            //    6s 仍无 Surface：继续启动（不阻断 —— 由下方帧看门狗给出
            //    真实报错，绝不无解释黑屏）。
            val waitStart = System.currentTimeMillis()
            while (!surfaceReady && System.currentTimeMillis() - waitStart < 6_000) {
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
            if (!surfaceReady) {
                android.util.Log.w(TAG, "loadRom: Surface 6s 未就绪（继续启动，帧看门狗兜底）")
            }

            val rc = try {
                Pgm3Activity.nativeRun(root.absolutePath, romZip.absolutePath, romChd.absolutePath, "")
            } catch (t: Throwable) {
                lastErr = "PGM3 核心启动异常：${t.message}"
                android.util.Log.e(TAG, "nativeRun", t)
                return false
            }
            if (rc != 0) {
                lastErr = readFailure(root, rc)
                android.util.Log.e(TAG, "nativeRun rc=$rc: $lastErr")
                return false
            }

            // ★★ v58 智能启动监护（用户实测反馈：“三国战记3HD 要加载一定时间，
            //    12 秒就报错肯定不行，而且要保证核心真的在运行它” —— v57 的
            //    12s 硬切会误杀还在加载的核心）。libpgm3 二进制核实：
            //    kov3hd 启动有 staged startup movie（分段开机影片），且 CHD
            //    为 LZMA 压缩（CHDR_LzmaDec*），首次解压+预缓存在大镜像上
            //    就是需要时间。监护改为「多信号活动检测 + 分级超时」：
            //
            //    活动信号（任一变化 = 核心活着）：
            //      A. nativeFrameCount() > 0  —— 渲染已起跑（最终成功条件）
            //      B. nativeStatus() 文本变化 —— 核心状态机在推进（“等待启动”
            //         →“游戏尚未启动”→…→“游戏运行中”，二进制里的全局状态串）
            //      C. interactive.log 大小增长 —— 核心在写日志
            //
            //    超时矩阵：
            //      · 首帧等待上限 300s（正常加载路径，覆盖大 CHD + 慢存储）
            //      · 总上限 480s（只要有 B/C 活动就继续等，绝不提前杀）
            //      · 静默判死 150s（自最近一次活动起，三信号全无变化 ——
            //        ★ v60：大 CHD LZMA 解压期间核心可静默 1~2 分钟（纯 CPU
            //        解压无外部信号），30s 判死会误杀正在解压的核心）
            //
            //    失败详情 = 最后状态文本 + interactive.log 尾部（真死也给原因）；
            //    等待期间 bootProgress 持续更新（前端显示“核心状态 + 已等待
            //    秒数”），logcat 每 2s 一条进度 —— 用户不再面对无解释黑屏。
            watchdog(root)
            if (!started) return false
            logInteractiveLog(root)

            isLoaded = true
            lastPad[0] = 0; lastPad[1] = 0
            android.util.Log.i(TAG, "kov3hd started: zip=${romZip.name} chd=${romChd.name}")
            // Surface 若已就绪则按外部暂停状态恢复
            Pgm3Activity.nativePause(pausedExt || !surfaceReady)
            return true
        }

    /**
     * ★★ v58 启动监护主循环（loadRom 内调用，已在 IO 线程 + lifecycleLock 内）。
     * 成功置 started=true；失败置 lastErr + 停核置 started=false。
     */
    private fun watchdog(root: File) {
        bootProgress = "正在启动三国战记3HD…"
        val t0 = System.currentTimeMillis()
        var lastActivity = t0               // 最近一次 B/C 信号变化
        var lastStatus: String? = null      // nativeStatus 快照
        var lastLogSize = -1L               // interactive.log 大小快照
        var lastProgressLog = 0L            // 上次 logcat 进度输出
        var pendingError: String? = null    // 命中的错误关键词（延迟确认）
        var pendingErrorAt = 0L
        var sawAnyActivity = false

        while (true) {
            val now = System.currentTimeMillis()
            val waited = now - t0

            // A. 首帧出现 —— 渲染起跑，立即成功
            val frame = try { Pgm3Activity.nativeFrameCount() } catch (_: Throwable) { 0 }
            if (frame > 0) {
                bootProgress = null
                started = true
                android.util.Log.i(TAG, "watchdog: 首帧 #${frame}（等待 ${waited / 1000}s）")
                return
            }

            // B. 核心状态文本变化 = 状态机在推进
            val status = try { Pgm3Activity.nativeStatus() } catch (_: Throwable) { null }
            if (status != lastStatus) {
                if (lastStatus != null) {
                    sawAnyActivity = true
                    android.util.Log.i(TAG, "watchdog: 核心状态 → “${status ?: "（空）"}”（+${waited / 1000}s）")
                }
                lastStatus = status
                lastActivity = now   // 状态出现/变化都刷新活动时钟（首个状态 = 基线）
            }

            // C. interactive.log 增长 = 核心在写日志
            val logSize = try {
                File(root, "interactive.log").takeIf { it.isFile }?.length() ?: 0L
            } catch (_: Throwable) { 0L }
            if (logSize != lastLogSize) {
                if (lastLogSize >= 0) sawAnyActivity = true
                lastLogSize = logSize
                lastActivity = now
            }

            // 进度上报（前端轮询 bootProgress 显示；logcat 每 2s 一条）
            if (now - lastProgressLog >= 2_000) {
                lastProgressLog = now
                val phase = status?.takeIf { it.isNotBlank() } ?: "（无状态）"
                bootProgress = "正在启动三国战记3HD：核心状态「$phase」" +
                    "，已等待 ${waited / 1000}s${if (logSize > 0) "，日志 ${logSize / 1024}KB" else ""}" +
                    if (sawAnyActivity) "" else "（加载中，请耐心等待）"
                android.util.Log.i(TAG, "watchdog: ${bootProgress}")
            }

            // D. 明确错误信号 —— 状态文本/日志出现失败语义 → 3s 后复查仍无
            //    新活动即快速失败（不等满 90s；核心报了错不可能自己恢复）
            val errHit = findStartupError(status, root)
            if (errHit != null) {
                if (pendingError == errHit) {
                    if (now - pendingErrorAt >= 3_000 && now - lastActivity >= 3_000) {
                        failWatchdog(root, errHit)
                        return
                    }
                } else {
                    pendingError = errHit
                    pendingErrorAt = now
                }
            } else if (pendingError != null) {
                pendingError = null   // 错误消失（状态又推进了）→ 撤销快速失败
            }

            // E. 静默判死：自最近活动起 150s 三信号全无变化（大 CHD 解压期
            //    可静默 1~2 分钟 —— 30s 会误杀，见常量处注释）
            if (now - lastActivity >= SILENCE_TIMEOUT_MS) {
                failWatchdog(root, "核心连续 ${SILENCE_TIMEOUT_MS / 1000}s 无任何活动" +
                    "（状态「${status ?: "无"}」未变、日志无增长、帧号为 0）")
                return
            }

            // F. 总限：有活动最多等 180s；完全无活动最多等 90s（首帧上限）
            val limit = if (sawAnyActivity) TOTAL_TIMEOUT_MS else FIRST_FRAME_TIMEOUT_MS
            if (waited >= limit) {
                failWatchdog(root, "等待 ${limit / 1000}s 仍未出现画面" +
                    "（${if (sawAnyActivity) "核心持续有活动" else "核心始终无活动"}" +
                    "，状态「${status ?: "无"}」）")
                return
            }

            try { Thread.sleep(250) } catch (_: InterruptedException) { return }
        }
    }

    /** 启动失败收尾：停核 + 组装真实原因（最后状态 + interactive.log 尾部）。 */
    private fun failWatchdog(root: File, reason: String) {
        bootProgress = null
        val status = try { Pgm3Activity.nativeStatus() } catch (_: Throwable) { null }
        val logTail = readLogTail(root, 6)
        // 停掉已启动的核心（失败状态机没有继续运行的意义）
        try { Pgm3Activity.nativeStop() } catch (_: Throwable) {}
        try { Pgm3Activity.nativeSurface(null) } catch (_: Throwable) {}
        started = false
        lastErr = buildString {
            append("三国战记3HD 启动失败：")
            append(reason)
            status?.takeIf { it.isNotBlank() }?.let { append("；最后核心状态「").append(it).append("」") }
            if (logTail.isNotBlank()) append("\n核心日志：\n").append(logTail)
            append("\n请检查：CHD 是否为 original kov3hd_m105 版本、ROM 包是否完整、")
            append("kov3hd.zip 与 kov3hd 文件夹是否同目录。")
        }
        android.util.Log.e(TAG, "watchdog fail: $lastErr")
        logInteractiveLog(root)
    }

    /**
     * 启动期错误关键词探测（nativeStatus 文本 + interactive.log 新增内容）。
     * 仅盯二进制/日志里实际出现的明确失败语义，避免把正常加载日志误判为错。
     */
    private fun findStartupError(status: String?, root: File): String? {
        val text = buildString {
            append(status ?: "")
            val logSize = try {
                File(root, "interactive.log").takeIf { it.isFile }?.length() ?: 0L
            } catch (_: Throwable) { 0L }
            if (logSize > 0 && logSize <= 64 * 1024) {
                try { append('\n').append(File(root, "interactive.log").readText()) } catch (_: Throwable) {}
            }
        }
        if (text.isBlank()) return null
        for (kw in STARTUP_ERROR_KEYWORDS) {
            if (text.contains(kw)) return kw
        }
        return null
    }

    /** interactive.log 尾部 n 行（失败详情；无日志返回 ""）。 */
    private fun readLogTail(root: File, lines: Int): String {
        return try {
            val log = File(root, "interactive.log")
            if (!log.isFile) "" else log.readLines().takeLast(lines).joinToString("\n").trim()
        } catch (_: Throwable) { "" }
    }

    /** <romZip 同目录>/kov3hd/kov3hd_m105.chd（对齐参考实现）；
     *  ★★ v59 多位置查找（用户布局各异 —— 平铺/上溯/app ROM 目录/两层子目录扫描）。 */
    private fun resolveChdBeside(romZip: File, systemDir: String): File {
        val dir = romZip.parentFile
        // 1. 参考实现标准布局：<dir>/kov3hd/kov3hd_m105.chd
        val primary = File(File(dir, PGM3_ROMSET), PGM3_CHD_NAME)
        if (primary.isFile) return primary
        // 2. 平铺：<dir>/kov3hd_m105.chd
        val flat = File(dir, PGM3_CHD_NAME)
        if (flat.isFile) return flat
        // 3. 上溯一层（ROM 在 kov3hd/ 子目录、chd 在其旁的布局）
        val up = File(dir?.parentFile, PGM3_CHD_NAME)
        if (up.isFile) return up
        // 4. app 街机 ROM 目录（fbneo system 目录 —— 本应用街机 ROM 的家）
        val sysKov = File(File(systemDir, PGM3_ROMSET), PGM3_CHD_NAME)
        if (sysKov.isFile) return sysKov
        val sysFlat = File(systemDir, PGM3_CHD_NAME)
        if (sysFlat.isFile) return sysFlat
        // 5. 以 ROM 所在目录为根的两层子目录扫描（kov3hd.zip 在某个子目录、
        //    CHD 在另一个子目录的整理习惯）
        scanFor(dir, PGM3_CHD_NAME, 2)?.let { return it }
        return primary
    }

    /** 选 CHD 启动时反向找 romset：同目录 → 上层 → system 目录 → 两层子目录扫描。 */
    private fun resolveZipBeside(chd: File, systemDir: String): File {
        val dir = chd.parentFile
        val same = File(dir, "$PGM3_ROMSET.zip")
        if (same.isFile) return same
        // CHD 在 kov3hd/ 子目录里 → zip 在其上一级
        if (dir != null && PGM3_ROMSET.equals(dir.name, ignoreCase = true)) {
            val beside = File(dir.parentFile, "$PGM3_ROMSET.zip")
            if (beside.isFile) return beside
        }
        val up = File(dir?.parentFile, "$PGM3_ROMSET.zip")
        if (up.isFile) return up
        val sys = File(systemDir, "$PGM3_ROMSET.zip")
        if (sys.isFile) return sys
        scanFor(dir, "$PGM3_ROMSET.zip", 2)?.let { return it }
        return same
    }

    /** 在 base 下按名字找文件（深度限制 depth 层；找到即返回，找不到返回 null）。 */
    private fun scanFor(base: File?, name: String, depth: Int): File? {
        if (base == null || depth < 0) return null
        val list = try { base.listFiles() ?: return null } catch (_: Throwable) { return null }
        for (f in list) {
            if (f.isFile && f.name.equals(name, ignoreCase = true)) return f
        }
        if (depth == 0) return null
        for (f in list) {
            if (f.isDirectory) {
                scanFor(f, name, depth - 1)?.let { return it }
            }
        }
        return null
    }

    /** assets/pgm3_runtime → <root>/runtime（参考实现同一原子写法）。 */
    private fun prepareRuntime(ctx: Context, root: File) {
        val runtimeDir = File(root, "runtime")
        if (!runtimeDir.isDirectory && !runtimeDir.mkdirs()) {
            throw IllegalStateException("无法创建 ${runtimeDir.absolutePath}")
        }
        for (name in RUNTIME_ASSETS) {
            val dst = File(runtimeDir, name)
            val pending = File(runtimeDir, "$name.pending")
            if (dst.isFile && dst.length() > 0 && !pending.isFile) continue
            ctx.assets.open("pgm3_runtime/$name").use { input ->
                java.io.FileOutputStream(pending).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            if (dst.isFile) dst.delete()
            if (!pending.renameTo(dst)) {
                // 极端文件系统不支持 rename：退化为直接写
                ctx.assets.open("pgm3_runtime/$name").use { input ->
                    java.io.FileOutputStream(dst).use { out -> input.copyTo(out) }
                }
                pending.delete()
            }
        }
    }

    /** 启动失败详情：<root>/interactive.log（核心落盘的失败原因）。 */
    private fun readFailure(root: File, rc: Int): String {
        val detail = readFailureDetail(root)
        return buildString {
            append("三国战记3HD 启动失败（code $rc）")
            if (detail.isNotBlank()) append("：").append(detail.trim())
        }
    }

    /** 仅失败详情（interactive.log 首个非空行，缺则 nativeStatus；均空返回 ""）。 */
    private fun readFailureDetail(root: File): String {
        val log = File(root, "interactive.log")
        val detail = try {
            log.takeIf { it.isFile }?.readLines()?.firstOrNull { it.isNotBlank() }
        } catch (_: Throwable) { null }
        if (detail != null) return detail
        val status = try { Pgm3Activity.nativeStatus() } catch (_: Throwable) { null }
        return status?.takeIf { it.isNotBlank() } ?: ""
    }

    /** interactive.log 全文回显到 logcat（诊断 —— 线上问题只需 adb logcat）。 */
    private fun logInteractiveLog(root: File) {
        try {
            val log = File(root, "interactive.log")
            if (log.isFile) {
                val text = log.readText().trim()
                if (text.isNotEmpty()) {
                    android.util.Log.i(TAG, "interactive.log:\n$text")
                }
            }
        } catch (_: Throwable) {}
    }

    // === 渲染 ===

    override fun setSurface(surface: Surface?) {
        // ★★ v57 无锁化（黑屏 0 帧修复的必要条件）：loadRom 全程持有
        //   lifecycleLock（Surface 等待 + 帧看门狗最长 ~18s）。若本方法也
        //   抢同一把锁，SurfaceView 的 surfaceCreated 会被阻塞到 loadRom
        //   结束 —— “启动前等 Surface”永远等不到，恶性循环。
        //   nativeSurface 二进制核实为并发安全（原子窗口存储 + 版本计数器，
        //   渲染线程按版本重取）；参考实现也是 UI 线程直接调用（核心运行中
        //   随时可换窗）。nativePause 为原子标志写。started 为 @Volatile。
        try {
            Pgm3Activity.nativeSurface(surface)
        } catch (t: Throwable) {
            // 核心未启动前个别设备可能拒绑 —— 记录但不视为致命
            android.util.Log.w(TAG, "nativeSurface: ${t.message}")
        }
        surfaceReady = surface != null
        // Surface 就绪状态驱动暂停（参考实现：无 Surface = 暂停）
        if (started) {
            try { Pgm3Activity.nativePause(pausedExt || !surfaceReady) } catch (_: Throwable) {}
        }
    }

    override fun onSurfaceChanged(surface: Surface?, width: Int, height: Int) {
        // GL 表面由核心自适应 —— 只需确保绑定状态与实际一致
        if (surface != null && !surfaceReady) setSurface(surface)
    }

    override fun onSurfaceDestroyed() {
        // GL 表面失效：解绑防核心向死 BufferQueue 投帧
        setSurface(null)
    }

    override fun videoWidth(): Int = 448   // IGS M027 原生分辨率（布局用；
    override fun videoHeight(): Int = 320  // 实际缩放由核心 GL 自适应 Surface）

    override fun setVideoFilter(filter: Int) { /* PGM3 无前端滤镜（GL 直绘） */ }
    override fun setHighQualityScaling(enabled: Boolean) { /* 同上 */ }

    // === 控制 ===

    override fun setPaused(paused: Boolean) {
        pausedExt = paused
        if (started) Pgm3Activity.nativePause(paused || !surfaceReady)
    }

    override fun setFastForward(speed: Int) {
        if (!started) return
        val on = speed > 0
        if (on) {
            val ready = try { Pgm3Activity.nativeFastForwardReady() } catch (_: Throwable) { false }
            if (ready) Pgm3Activity.nativeFastForward(true)
        } else {
            try { Pgm3Activity.nativeFastForward(false) } catch (_: Throwable) {}
        }
    }

    override fun reset(hard: Boolean) {
        if (started) try { Pgm3Activity.nativeReset() } catch (t: Throwable) {
            android.util.Log.w(TAG, "nativeReset: ${t.message}")
        }
    }

    override fun unload() = synchronized(lifecycleLock) { cleanup() }
    override fun shutdown() = synchronized(lifecycleLock) { cleanup() }

    private fun cleanup() {
        started = false
        isLoaded = false
        bootProgress = null
        try { Pgm3Activity.nativeStop() } catch (_: Throwable) {}
        try { Pgm3Activity.nativeSurface(null) } catch (_: Throwable) {}
        surfaceReady = false
        pausedExt = false
        lastPad[0] = 0; lastPad[1] = 0
    }

    // === 输入（libretro JOYPAD 位 → PGM3 MCU 位；对齐参考实现 toMcu）===

    override fun setPad1(bits: Int) = pushPad(0, bits)
    override fun setPad2(bits: Int) = pushPad(1, bits)

    private fun pushPad(player: Int, libretroBits: Int) {
        if (!started || player !in 0..1) return
        val prev = lastPad[player]
        lastPad[player] = libretroBits
        try {
            Pgm3Activity.nativeInput(player, toMcuBits(libretroBits))
            // 投币 = Select(bit2) 按下沿 → 专用通道（参考实现 dispatchPadData 同一逻辑）
            if (libretroBits and LIBRETRO_SELECT != 0 && prev and LIBRETRO_SELECT == 0) {
                Pgm3Activity.nativeCoin(player)
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "nativeInput: ${t.message}")
        }
    }

    /** libretro JOYPAD 位（arcadeToLibretroLayout 输出）→ PGM3 MCU 位。 */
    private fun toMcuBits(b: Int): Int {
        var m = 0
        if (b and 0x08 != 0) m = m or 0x001   // Start
        if (b and 0x10 != 0) m = m or 0x002   // Up
        if (b and 0x20 != 0) m = m or 0x004   // Down
        if (b and 0x40 != 0) m = m or 0x008   // Left
        if (b and 0x80 != 0) m = m or 0x010   // Right
        if (b and 0x100 != 0) m = m or 0x020  // A（街机键1）
        if (b and 0x01 != 0) m = m or 0x040   // B（街机键2）
        if (b and 0x200 != 0) m = m or 0x080  // X（街机键3）
        if (b and 0x02 != 0) m = m or 0x100   // Y（街机键4）
        return m
    }

    // === 兼容性桩（推模型核心不支持的能力，明确返回失败/无操作）===

    override fun setSaveName(name: String) { /* PGM3 存档 = 记忆卡，不走 .srm */ }
    override fun setCoreOption(key: String, value: String) { /* 无核心选项面板 */ }
    override fun setRegion(region: Int) { /* M027 单一区域 */ }
    override fun setSampleRate(rate: Int) { /* 音频由核心 OpenSLES 自持 */ }

    override fun saveState(slot: Int, dst: File): Boolean {
        lastErr = "三国战记3HD 核心暂不支持即时存档（进度走核心记忆卡）"
        return false
    }

    override fun loadState(slot: Int, src: File): Boolean {
        lastErr = "三国战记3HD 核心暂不支持即时读档（进度走核心记忆卡）"
        return false
    }

    override fun captureFrame(): FrameCapture? {
        // GL 直绘表面归核心所有 —— 截图暂不可用（返回 null，UI 提示无画面数据）
        return null
    }

    override fun realtimeFps(): Double {
        // 推模型核心：onFrame 心跳无意义，FPS 由真实帧号差给出
        if (!started) return 0.0
        val now = try { Pgm3Activity.nativeFrameCount() } catch (_: Throwable) { return 0.0 }
        val t = System.nanoTime()
        val last = fpsState
        val dtSec = (t - last.first) / 1e9
        if (dtSec < 0.25) return last.third
        val fps = (now - last.second).coerceAtLeast(0) / dtSec
        fpsState = Triple(t, now, fps)
        return fps
    }
    private var fpsState: Triple<Long, Int, Double> = Triple(0L, 0, 0.0)

    override fun lastError(): String = lastErr
}
