package com.nesstation.app.core.engine

import android.content.Context
import android.os.Build
import com.dsemu.drastic.DraSticJNI
import com.dsemu.drastic.filesystem.DraSticPathCache
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * DraStic（激烈）NDS 核心引擎 —— 与 [NdsEngine]（melonDS）并列的第二个
 * NDS 核心，启动时由玩家在核心选择对话框里二选一。
 *
 * ## 架构（与 melonDS 拉模型完全不同的推模型核心）
 *  - [loadRom] 调 `DraSticJNI.startGame` 后，**原生线程**接管模拟循环与
 *    OpenSL ES 音频输出（无需 Kotlin 音频线程 / AudioTrack）；
 *  - 本引擎的渲染线程做"帧搬运"：`waitScreen()`（阻塞到新帧，原生每帧
 *    与每 50ms 都会唤醒）→ `getScreenBuffers(top, bottom)`（ARGB_8888
 *    256×192 ×2）→ 合成为 [frameBuffer]（上屏在前 256×384）→ 帧号 +1；
 *  - [NdsDualScreenView] 按 Choreographer 节流消费 [frameBuffer]，因此
 *    DraStic 分支无论什么画面缩放模式都走画布渲染路径（GameSurfaceView
 *    已为此做了分支处理）。
 *
 * ## 输入位布局换算
 * NesStation 统一使用 libretro 12 键位（bit0=A … bit11=R）；
 * DraStic 使用 bit0-3=方向 / bit4-11=A,B,X,Y,L,R,Start,Select +
 * bit31=触摸标志（[DraSticJNI.updateInput] 反汇编验证）。
 * [setPad1] 负责 libretro→DraStic 的位重排，[pushInput] 保证按键与触摸
 * 状态合并成一个完整的 updateInput 调用（两状态都缓存在引擎里）。
 *
 * ## ABI 前提
 * libdrastic*.so 仅有 armeabi-v7a（32 位）版本。默认多 ABI 构建在 64 位
 * 设备上以 arm64 进程运行 → 无法加载 → [probeAvailability] 报告不可用，
 * UI 禁用 DraStic 选项。构建 32 位专用包：`./gradlew assembleRelease
 * -PabiFilter=armeabi-v7a`。
 *
 * ## 存档
 * 电池存档（.dsv）与即时存档（.dss 槽 0-8，9 为快速槽）全部由原生经
 * [DraSticPathCache] 写入 `<filesDir>/drastic/user/`。即时存档与
 * NesStation 槽位 UI 的互通见 [saveState]（写路径观察器精确定位 .dss，
 * 复制一份到 NesStation 的 .state 路径供槽位存在性检查 / 备份迁移）。
 */
class DraSticEngine private constructor() : EmulatorEngine, NdsCoreEngine {

    companion object {
        @Volatile
        private var instance: DraSticEngine? = null

        /** 单例（与 NdsEngine/FbNeoEngine 等保持一致的 get() 模式）。 */
        @JvmStatic
        fun get(): DraSticEngine {
            return instance ?: synchronized(this) {
                instance ?: DraSticEngine().also { instance = it }
            }
        }

        // ---- libretro → DraStic 按键位换算表 ----
        // libretro: bit0=A bit1=B bit2=Select bit3=Start bit4=Up bit5=Down
        //           bit6=Left bit7=Right bit8=X bit9=Y bit10=L bit11=R
        // DraStic:  bit0=Up bit1=Down bit2=Left bit3=Right bit4=A bit5=B
        //           bit6=X bit7=Y bit8=L bit9=R bit10=Start bit11=Select
        private const val LR_A = 0x001
        private const val LR_B = 0x002
        private const val LR_SELECT = 0x004
        private const val LR_START = 0x008
        private const val LR_UP = 0x010
        private const val LR_DOWN = 0x020
        private const val LR_LEFT = 0x040
        private const val LR_RIGHT = 0x080
        private const val LR_X = 0x100
        private const val LR_Y = 0x200
        private const val LR_L = 0x400
        private const val LR_R = 0x800

        private const val DS_UP = 0x001
        private const val DS_DOWN = 0x002
        private const val DS_LEFT = 0x004
        private const val DS_RIGHT = 0x008
        private const val DS_A = 0x010
        private const val DS_B = 0x020
        private const val DS_X = 0x040
        private const val DS_Y = 0x080
        private const val DS_L = 0x100
        private const val DS_R = 0x200
        private const val DS_START = 0x400
        private const val DS_SELECT = 0x800

        /** DraStic 触摸激活标志（updateInput arg1 的 bit31）。 */
        private const val DS_TOUCH_FLAG = 0x80000000.toInt()

        /** 原版 onInit 传的 versionCode —— 仅用于原生数据迁移判断，
         *  全新安装无历史数据，取一个不会触发任何迁移分支的高值即可。 */
        private const val DRASTIC_VERSION_CODE = 64

        /** 原版默认音量（_Volume 0..10 → setAudioVolume ×10）。 */
        private const val DEFAULT_VOLUME = 100

        /**
         * 打包 DraStic 配置位域（复刻原版 f0.h.n()，默认值取原版首次
         * 安装时的出厂设置；0 之外的位全部关闭，仅开声音）：
         *  bit31   = 声音启用（_SoundEnabled，默认开）
         *  bit29   = 快进激活状态（V，运行中随 setFastForward 翻转）
         *  bits12-15 = 快进速度（_FfwdSpeed，默认 2x）
         *  其余位（作弊/麦克风/帧跳过/Slot2/线程数等）保持 0 = 关闭/自动
         */
        private fun packConfig(sound: Boolean, fastForward: Boolean, ffwdSpeed: Int): Long {
            var cfg = 0L
            if (sound) cfg = cfg or 0x80000000L
            if (fastForward) cfg = cfg or 0x20000000L
            cfg = cfg or ((ffwdSpeed.coerceIn(0, 15).toLong()) shl 12)
            return cfg
        }

        // ---- 可用性探测（核心选择对话框用） ----

        /** 探测结果：库是否可用 + 不可用原因（可用时为 null）。 */
        data class Availability(val available: Boolean, val reason: String?)
    }

    /** 进程内缓存探测结果（库加载结果在进程生命周期内不会变化）。 */
    @Volatile
    private var probedAvailability: Availability? = null

    /** 触发 DraSticJNI 类初始化（加载 drastic_cpu + drastic/compat）并返回可用性。 */
    fun probeAvailability(): Availability {
        probedAvailability?.let { return it }
        val result = try {
            Class.forName("com.dsemu.drastic.DraSticJNI")
            if (DraSticJNI.JniStartupError) {
                Availability(
                    false,
                    "libdrastic 加载失败（当前进程非 32 位 ARM）。\n" +
                        "DraStic 核心仅含 armeabi-v7a 库，需要 32 位进程。\n" +
                        "请用 -PabiFilter=armeabi-v7a 构建 32 位 APK。"
                )
            } else if (DraSticJNI.JniCpuType != DraSticJNI.CPU_TYPE_ARMv7a_NEON &&
                DraSticJNI.JniCpuType != DraSticJNI.CPU_TYPE_ARMv7a_TEGRA2
            ) {
                Availability(false, "设备 CPU 不受支持 (cpuType=${DraSticJNI.JniCpuType})")
            } else {
                Availability(true, null)
            }
        } catch (e: Throwable) {
            Availability(false, "DraStic 核心加载异常: ${e.message}")
        }
        probedAvailability = result
        return result
    }

    // ---- 引擎状态 ----

    /**
     * 合成帧：上屏 256×192 在前（行 0..191）、下屏在后（行 192..383），
     * 共 256×384。NdsDualScreenView.computeSrcRects 的 Top/Bottom 切片
     * 推导（screenH=192、gap=0）对它天然成立。
     */
    @Volatile
    override var frameBuffer: IntArray = IntArray(256 * 384)

    private val topBuf = IntArray(256 * 192)
    private val bottomBuf = IntArray(256 * 192)

    private val running = AtomicBoolean(false)
    private var renderThread: Thread? = null

    @Volatile
    override var isLoaded: Boolean = false
        private set

    @Volatile
    private var paused = false

    @Volatile
    private var frameCount = 0L

    /** App context —— 引擎初始化（onInit 上下文 / 目录定位）用。 */
    @Volatile
    var appContext: Context? = null

    // ---- 输入缓存（按键与触摸必须合并成一次 updateInput 调用） ----

    @Volatile
    private var padBitsLibretro = 0

    @Volatile
    private var touchX = 0

    @Volatile
    private var touchY = 0

    @Volatile
    private var touchPressed = false

    /** 触屏 / 按键任何变化后调用；每次都送完整的按键+触摸状态。 */
    private fun pushInput() {
        if (!isLoaded) return
        val mask = toDrasticMask(padBitsLibretro)
        val packedXY = ((touchX shl 16) or touchY)
        val mask1 = if (touchPressed) mask or DS_TOUCH_FLAG else mask
        try {
            DraSticJNI.updateInput(mask1, packedXY, mask)
        } catch (e: Throwable) {
            android.util.Log.w("DraSticEngine", "updateInput failed", e)
        }
    }

    private fun toDrasticMask(libretroBits: Int): Int {
        var m = 0
        if (libretroBits and LR_UP != 0) m = m or DS_UP
        if (libretroBits and LR_DOWN != 0) m = m or DS_DOWN
        if (libretroBits and LR_LEFT != 0) m = m or DS_LEFT
        if (libretroBits and LR_RIGHT != 0) m = m or DS_RIGHT
        if (libretroBits and LR_A != 0) m = m or DS_A
        if (libretroBits and LR_B != 0) m = m or DS_B
        if (libretroBits and LR_X != 0) m = m or DS_X
        if (libretroBits and LR_Y != 0) m = m or DS_Y
        if (libretroBits and LR_L != 0) m = m or DS_L
        if (libretroBits and LR_R != 0) m = m or DS_R
        if (libretroBits and LR_START != 0) m = m or DS_START
        if (libretroBits and LR_SELECT != 0) m = m or DS_SELECT
        return m
    }

    // ---- 生命周期 ----

    private val lifecycleLock = Any()

    /** onInit 是否已执行过（进程内只需一次）。 */
    private var nativeInitialized = false

    private fun ensureNativeInit(): Boolean {
        if (nativeInitialized) return true
        if (DraSticJNI.JniStartupError) return false
        try {
            val ctx = appContext
            // 反汇编确认 onInit 只消费 versionCode 与 sdkInt；对象参数传
            // Application 上下文（永不被销毁，无泄漏风险）。
            DraSticJNI.onInit(ctx, DRASTIC_VERSION_CODE, Build.VERSION.SDK_INT)
            nativeInitialized = true
            return true
        } catch (e: Throwable) {
            android.util.Log.e("DraSticEngine", "onInit failed", e)
            return false
        }
    }

    override fun ensureLoaded(): Boolean {
        return !DraSticJNI.JniStartupError
    }

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        val availability = probeAvailability()
        if (!availability.available) {
            lastErrorMsg = availability.reason ?: "DraStic 不可用"
            return false
        }
        if (!rom.isFile || rom.length() < 0x200) {
            lastErrorMsg = "ROM 文件不存在或太小: ${rom.absolutePath}"
            return false
        }
        if (!ensureNativeInit()) {
            lastErrorMsg = "DraStic 原生初始化失败"
            return false
        }

        cleanup()

        // DraStic 私有目录（与 melonDS 的 <filesDir>/nds 系统目录互不干扰）
        val ctx = appContext
        if (ctx == null) {
            lastErrorMsg = "未设置 appContext（NesApp 启动时注册）"
            return false
        }
        val drasticRoot = File(ctx.filesDir, "drastic").apply { mkdirs() }
        val sysDir = File(drasticRoot, "system").apply { mkdirs() }
        val usrDir = File(drasticRoot, "user").apply { mkdirs() }
        DraSticPathCache.setBaseDirs(sysDir, usrDir)

        // content:// 缓解：SAF 导入的 ROM 会复制成共享的 temp_rom.<ext>
        // —— 若照此启动，所有这类游戏会共享同一套 DraStic 存档基名。
        // 复制成 <filesDir>/drastic/roms/<gameId>.<ext> 保证每游戏独立。
        // （普通文件路径的 ROM 不复制，直接传原路径，存档跟随 ROM 目录）
        val effectiveRom: File = if (rom.nameWithoutExtension == "temp_rom" &&
            saveNameOverride != null) {
            try {
                val romsDir = File(drasticRoot, "roms").apply { mkdirs() }
                val ext = rom.extension.ifBlank { "nds" }
                val per = File(romsDir, "${saveNameOverride}.${ext}")
                rom.copyTo(per, overwrite = true)
                per
            } catch (_: Exception) {
                rom
            }
        } else {
            rom
        }
        romFileBase = effectiveRom.nameWithoutExtension

        try {
            // ROM 以真实绝对路径传入（startGame 的路径最终会回到
            // DraSticPathCache.open —— 绝对路径分支直接解析为真实文件）
            DraSticPathCache.changeRom(effectiveRom.absolutePath, effectiveRom)

            DraSticJNI.setAudioVolume(DEFAULT_VOLUME)
            DraSticJNI.setAutosaveInterval(0)
            DraSticJNI.applyConfig(packConfig(sound = true, fastForward = false, ffwdSpeed = 2))

            val ok = DraSticJNI.startGame(
                effectiveRom.absolutePath,
                -1,                       // 不自动读档
                packConfig(sound = true, fastForward = false, ffwdSpeed = 2),
                0,
                false,
                -1L                       // 自定义时钟关闭
            )
            if (!ok) {
                lastErrorMsg = "DraStic 无法加载 ROM（文件损坏或不受支持）"
                return false
            }
        } catch (e: Throwable) {
            lastErrorMsg = "DraStic 启动异常: ${e.message}"
            android.util.Log.e("DraSticEngine", "startGame failed", e)
            return false
        }

        isLoaded = true
        lastErrorMsg = ""
        paused = false
        running.set(true)

        renderThread = thread(name = "drastic-render", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            try {
                while (running.get()) {
                    // 阻塞到下一帧就绪（原生每帧 signal；输入线程每 50ms
                    // 还有保底 signal —— 暂停/退出时不会永久卡死）
                    DraSticJNI.waitScreen()
                    if (!running.get()) break
                    if (paused) continue

                    DraSticJNI.getScreenBuffers(topBuf, bottomBuf)
                    // 合成：上屏在前、下屏在后（256×384）
                    System.arraycopy(topBuf, 0, frameBuffer, 0, topBuf.size)
                    System.arraycopy(bottomBuf, 0, frameBuffer, topBuf.size, bottomBuf.size)
                    frameCount++
                    onFrame()
                }
            } catch (e: Throwable) {
                if (running.get()) {
                    android.util.Log.e("DraSticEngine", "render thread died", e)
                }
            }
        }
        return true
    }

    override fun setPaused(paused: Boolean) {
        if (this.paused == paused) return
        this.paused = paused
        if (isLoaded) {
            try {
                DraSticJNI.pauseSystem(if (paused) 1 else 0)
            } catch (e: Throwable) {
                android.util.Log.w("DraSticEngine", "pauseSystem failed", e)
            }
        }
    }

    override fun setFastForward(speed: Int) {
        if (!isLoaded) return
        val active = speed > 0
        // 映射 NesStation 倍速（2/4/6/8/16）到 DraStic _FfwdSpeed（2..15）
        val ffwd = if (active) speed.coerceIn(2, 15) else 2
        try {
            DraSticJNI.applyConfig(packConfig(sound = true, fastForward = active, ffwdSpeed = ffwd))
        } catch (e: Throwable) {
            android.util.Log.w("DraSticEngine", "applyConfig(ff) failed", e)
        }
    }

    override fun reset(hard: Boolean) {
        if (!isLoaded) return
        try {
            DraSticJNI.resetDS()
        } catch (e: Throwable) {
            android.util.Log.w("DraSticEngine", "resetDS failed", e)
        }
    }

    override fun saveState(slot: Int, dst: File): Boolean {
        if (!isLoaded) return false
        return try {
            DraSticPathCache.resetWriteLog()
            val ok = DraSticJNI.saveState(slot, false)
            if (ok) {
                // 观察器定位原生实际写的 .dss，复制一份到 NesStation 槽位
                // 路径（槽位 UI 的存在性检查 + 用户备份/迁移友好性）
                val virtual = DraSticPathCache.lastWritePath("dss")
                val real = virtual?.let { DraSticPathCache.resolveForTest(it) }
                if (real != null && real.isFile && real.length() > 0) {
                    nativeStateFiles[slot] = real
                    try {
                        real.copyTo(dst, overwrite = true)
                    } catch (_: Exception) {
                        // 复制失败不影响存档成功判定（DraStic 自己的 .dss 仍在）
                    }
                } else {
                    // 写路径观察失败（极少见）→ 写标记让槽位 UI 可见
                    try {
                        dst.writeText("drastic-state-slot:$slot")
                    } catch (_: Exception) {
                    }
                }
            }
            ok
        } catch (e: Throwable) {
            android.util.Log.w("DraSticEngine", "saveState failed", e)
            false
        }
    }

    override fun loadState(slot: Int, src: File): Boolean {
        if (!isLoaded) return false
        return try {
            // 原生槽位文件若不存在而 NesStation 侧有真实备份 → 先恢复再读档。
            // 槽位文件定位（多重候选，任一存在即直接读档）：
            //  a) saveState 时记录的写路径（写路径观察器，最可靠）
            //  b) userDir/savestates/<romBase>_<slot>.dss（规则推导）
            val nativeFile = findNativeStateFile(slot)
            if (nativeFile == null && src.isFile && isRealDssBackup(src)) {
                restoreBackupToNative(slot, src)
            }
            DraSticJNI.loadState(slot)
        } catch (e: Throwable) {
            android.util.Log.w("DraSticEngine", "loadState failed", e)
            false
        }
    }

    /** saveState 时记录的槽位 → 原生 .dss 映射（进程内）。 */
    private val nativeStateFiles = HashMap<Int, File>()

    /** 定位原生槽位文件：优先已记录路径，其次按命名规则推导。 */
    private fun findNativeStateFile(slot: Int): File? {
        nativeStateFiles[slot]?.let { if (it.isFile) return it }
        val ctx = appContext ?: return null
        val base = romFileBase ?: return null
        val candidates = listOf(
            File(File(ctx.filesDir, "drastic/user/savestates"), "${base}_$slot.dss"),
            File(File(ctx.filesDir, "drastic/system/savestates"), "${base}_$slot.dss")
        )
        return candidates.firstOrNull { it.isFile && it.length() > 0 }
    }

    /** NesStation 侧备份是否是真实 .dss（排除 saveState 写的标记文件）。 */
    private fun isRealDssBackup(src: File): Boolean {
        if (src.length() < 128) {
            return !runCatching {
                src.readText().startsWith("drastic-state-slot:")
            }.getOrDefault(true)
        }
        return true
    }

    /** 把 NesStation 备份复制回原生槽位路径（推导目标名）。 */
    private fun restoreBackupToNative(slot: Int, src: File) {
        try {
            val ctx = appContext ?: return
            val base = romFileBase ?: return
            val targetDir = File(ctx.filesDir, "drastic/user/savestates").apply { mkdirs() }
            src.copyTo(File(targetDir, "${base}_$slot.dss"), overwrite = true)
        } catch (_: Exception) {
        }
    }

    /** 当前 ROM 的去扩展名基名（存档恢复路径构造用，loadRom 时记录）。 */
    private var romFileBase: String? = null

    /** NesStation 传来的每游戏存档名（content:// temp_rom 缓解用）。 */
    private var saveNameOverride: String? = null

    override fun unload() = synchronized(lifecycleLock) {
        cleanup()
    }

    override fun shutdown() = synchronized(lifecycleLock) {
        cleanup()
    }

    /** 停止渲染线程 → 通知原生退出 → 释放核心状态（顺序对齐原版）。 */
    private fun cleanup() {
        if (!isLoaded && !running.get()) return
        running.set(false)
        isLoaded = false
        paused = false

        // 释放所有按键 / 触摸，避免"粘键"带入下一局
        try {
            padBitsLibretro = 0
            touchPressed = false
            touchX = 0
            touchY = 0
            if (nativeInitialized && !DraSticJNI.JniStartupError) {
                DraSticJNI.updateInput(0, 0, 0)
            }
        } catch (_: Throwable) {
        }

        // 原版顺序：pauseSystem(1) → quitSystem() → (等待) → releaseSystem()
        try {
            if (nativeInitialized && !DraSticJNI.JniStartupError) {
                DraSticJNI.pauseSystem(1)
                DraSticJNI.quitSystem()
            }
        } catch (_: Throwable) {
        }

        // 主动唤醒可能阻塞在 waitScreen 的渲染线程（condvar signal）——
        // 在 releaseSystem 释放状态内存**之前**发信号是安全的；不发的话
        // 渲染线程可能永远阻塞在已释放的 condvar 上（quitSystem 后原生
        // 模拟/保底 50ms 信号循环可能随之停止）。
        try {
            if (nativeInitialized && !DraSticJNI.JniStartupError) {
                DraSticJNI.signalScreen()
            }
        } catch (_: Throwable) {
        }

        // 等渲染线程退出（唤醒信号已发，正常情况立即返回；1 秒超时兜底，
        // 超时放弃等待 —— 线程是 daemon 的，不会阻塞进程退出）
        renderThread?.let { t ->
            try {
                t.join(1000)
            } catch (_: InterruptedException) {
            }
        }
        renderThread = null

        try {
            if (nativeInitialized && !DraSticJNI.JniStartupError) {
                DraSticJNI.releaseSystem()
            }
        } catch (_: Throwable) {
        }
    }

    // ---- 输入（EmulatorEngine / NdsCoreEngine） ----

    override fun setPad1(bits: Int) {
        padBitsLibretro = bits
        pushInput()
    }

    override fun setPad2(bits: Int) {
        // DraStic 原生 updateInput 无 P2 通道（单机 NDS），忽略
    }

    override fun setTouchInputDirect(x: Int, y: Int, pressed: Boolean) {
        touchX = x.coerceIn(0, 255)
        touchY = y.coerceIn(0, 191)
        touchPressed = pressed
        pushInput()
    }

    override fun setTouchInput(x: Int, y: Int, pressed: Boolean) {
        // 归一化坐标路径（Hybrid 布局兜底）→ 换算回下屏像素
        val px = (((x + 0x8000) * 256) ushr 16).coerceIn(0, 255)
        val py = (((y + 0x8000) * 192) ushr 16).coerceIn(0, 191)
        setTouchInputDirect(px, py, pressed)
    }

    // ---- 渲染查询（NdsCoreEngine） ----

    override fun frameStamp(): Long = frameCount

    override fun filteredVideoWidth(): Int = 256

    override fun filteredVideoHeight(): Int = 384

    override fun videoWidth(): Int = 256

    override fun videoHeight(): Int = 384

    // ---- 其余 EmulatorEngine 成员（多为 no-op / 简化实现） ----

    override fun setSurface(surface: android.view.Surface?) {
        // DraStic 走画布渲染路径（NdsDualScreenView），不用 ANativeWindow
    }

    override fun setSaveName(name: String) {
        // NesStatio 统一存档名（仅用于 content:// 共享 temp_rom 的缓解，
        // 见 loadRom；DraStic 自身的存档命名由原生按 ROM 文件名派生）
        saveNameOverride = name.takeIf { it.isNotBlank() }
    }

    override fun setCoreOption(key: String, value: String) {
        // melonds_* 选项对 DraStic 无意义；预留 drastic 专属键：
        // "drastic_volume" → 0..100
        if (key == "drastic_volume") {
            value.toIntOrNull()?.let {
                try {
                    DraSticJNI.setAudioVolume(it.coerceIn(0, 100))
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun setVideoFilter(filter: Int) {
        // 叠加型滤镜（scanline/crt/dot）由 NdsDualScreenView 视图层绘制；
        // 放大型（HQ2X 等）为 melonDS 原生特性，DraStic 不支持，忽略
    }

    override fun setHighQualityScaling(enabled: Boolean) {
        // 画布路径按视图分辨率绘制，无此开关
    }

    override fun setRegion(region: Int) {
        // NDS 无区域概念
    }

    override fun setSampleRate(rate: Int) {
        // OpenSL 输出采样率由原生管理
    }

    override var frameHook: NetplayHook?
        get() = null
        set(value) {
            // 推模型核心不支持帧同步联机（UI 层已保证联机时强制 melonDS）
        }

    override fun realtimeFps(): Double = 0.0

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        return try {
            // waitScreen 短暂等待确保拿到完整帧（截图时游戏通常已暂停）
            val out = frameBuffer.copyOf()
            FrameCapture(out, 256, 384)
        } catch (e: Throwable) {
            null
        }
    }

    @Volatile
    private var lastErrorMsg: String = ""

    override fun lastError(): String = lastErrorMsg
}
