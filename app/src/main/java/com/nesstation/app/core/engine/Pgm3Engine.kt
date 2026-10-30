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

            // 3. 解析 romset + CHD（对齐参考实现 Pgm3Support.romZip/romChd）
            val romZip: File
            val romChd: File
            val lower = rom.name.lowercase()
            if (lower.endsWith(".chd") ||
                rom.nameWithoutExtension.equals(PGM3_CHD_NAME.removeSuffix(".chd"), true)
            ) {
                // 直接选了 CHD 启动：CHD = 选中文件，zip 找同目录（及上溯一层）
                romChd = rom
                romZip = resolveZipBeside(rom)
            } else {
                romZip = rom
                romChd = resolveChdBeside(rom)
            }
            if (!romZip.isFile) {
                lastErr = "缺少 romset：${romZip.name}（应与 ${rom.name} 放同一目录）"
                return false
            }
            if (!romChd.isFile) {
                lastErr = "缺少光盘镜像：kov3hd/${PGM3_CHD_NAME}（放在 ${romZip.parent} 下的 kov3hd 文件夹里）"
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

            // ★★ v57 帧看门狗（用户实测“黑屏 0 帧”的第二根因）：nativeRun
            //    返回 0 ≠ 渲染已起跑 —— 核心内部线程仍可能失败（CHD 版本校验
            //    "Unsupported CHD: original kov3hd_m105 required"、EGL 窗口缺失
            //    等），失败详情只写 <root>/interactive.log，rc 仍为 0 → 应用以为
            //    成功 → 黑屏 0 帧且无任何提示。看门狗轮询 nativeFrameCount：
            //    12s 内帧号始终为 0 → 按“启动失败”处理，读 interactive.log +
            //    nativeStatus 给出真实原因（不再黑屏哑失败）。
            val wdStart = System.currentTimeMillis()
            var framesSeen = false
            while (System.currentTimeMillis() - wdStart < 12_000) {
                val f = try { Pgm3Activity.nativeFrameCount() } catch (_: Throwable) { 0 }
                if (f > 0) { framesSeen = true; break }
                try { Thread.sleep(150) } catch (_: InterruptedException) { break }
            }
            if (!framesSeen) {
                val detail = readFailureDetail(root)   // interactive.log / nativeStatus 详情
                // 停掉已启动的核心（0 帧状态机没有继续运行的意义）
                try { Pgm3Activity.nativeStop() } catch (_: Throwable) {}
                try { Pgm3Activity.nativeSurface(null) } catch (_: Throwable) {}
                started = false
                lastErr = "三国战记3HD 核心已启动但未产生任何画面（黑屏 0 帧）" +
                    (if (detail.isNotBlank()) "：$detail" else "（interactive.log 无记录）") +
                    "\n常见原因：CHD 版本不匹配（需要 original kov3hd_m105 光盘镜像）、" +
                    "ROM 包不完整或设备 GL 不兼容。"
                android.util.Log.e(TAG, "frame watchdog: $lastErr")
                logInteractiveLog(root)
                return false
            }
            logInteractiveLog(root)

            started = true
            isLoaded = true
            lastPad[0] = 0; lastPad[1] = 0
            android.util.Log.i(TAG, "kov3hd started: zip=${romZip.name} chd=${romChd.name}")
            // Surface 若已就绪则按外部暂停状态恢复
            if (started) Pgm3Activity.nativePause(pausedExt || !surfaceReady)
            return true
        }

    /** <romZip 同目录>/kov3hd/kov3hd_m105.chd（对齐参考实现），缺则试同目录平铺。 */
    private fun resolveChdBeside(romZip: File): File {
        val dir = romZip.parentFile
        val primary = File(File(dir, PGM3_ROMSET), PGM3_CHD_NAME)
        if (primary.isFile) return primary
        val flat = File(dir, PGM3_CHD_NAME)
        if (flat.isFile) return flat
        // 上溯一层（ROM 在 kov3hd/ 子目录、chd 在其旁的布局）
        val up = File(dir?.parentFile, PGM3_CHD_NAME)
        return if (up.isFile) up else primary
    }

    /** 选 CHD 启动时反向找 romset：同目录 → 上层目录。 */
    private fun resolveZipBeside(chd: File): File {
        val dir = chd.parentFile
        val same = File(dir, "$PGM3_ROMSET.zip")
        if (same.isFile) return same
        val up = File(dir?.parentFile, "$PGM3_ROMSET.zip")
        if (up.isFile) return up
        return same
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
