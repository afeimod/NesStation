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

    private fun configFile(userDirPath: String): File =
        File(File(userDirPath, "config").apply { mkdirs() }, "config.ini")

    /**
     * 把 coreOptions 复合键写入 config.ini（简单 INI 分组写法 —— 与原生 INIReader
     * 兼容；未涉及的段/键保持核心默认值）。
     */
    private fun flushConfig() {
        val dir = systemDir ?: return
        try {
            val cfg = configFile(dir)
            val grouped = LinkedHashMap<String, LinkedHashMap<String, String>>()
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

    override fun setCoreOption(key: String, value: String) {
        coreOptions[key] = value
        if (isRunning2()) {
            flushConfig()
            try { AzaharNative.lib.reloadSettings() } catch (_: Throwable) {}
        }
    }

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

        // 引擎传来的 systemDir 即 <filesDir>/azahar（EmulatorScreen 平台分支）——
        // 即 Azahar 用户目录（config/sdmc/nand/states 均在其中）。
        this.systemDir = userDir()
        this.saveDir = saveDir
        this.romPath = rom.absolutePath

        val lib = AzaharNative.lib
        try {
            NativeLibrary.NesStationHost.register(object : NativeLibrary.Host {
                override fun appContext(): Context? = this@AzaharEngine.appContext
                override fun azaharUserDirectory(): String = userDir()
                override fun onEmulationExited(result: Int) {
                    lastErrorText = "Azahar 退出（status=$result）"
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
        try { lib.surfaceChanged(surface!!) } catch (t: Throwable) {
            android.util.Log.w("AzaharEngine", "surfaceChanged failed", t)
        }
        if (_ffSpeed > 0) {
            try { lib.setTemporaryFrameLimit(100.0 * _ffSpeed) } catch (_: Throwable) {}
        }
        emuThread = thread(name = "AzaharNative") {
            try {
                lib.run(path)
            } catch (t: Throwable) {
                android.util.Log.e("AzaharEngine", "run() crashed", t)
                lastErrorText = t.message ?: "run() crashed"
            }
        }
        startPresentation()
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
                try { lib.surfaceDestroyed() } catch (_: Throwable) {}
            }
        }
    }

    override fun onSurfaceChanged(surface: Surface?, width: Int, height: Int) {
        // Azahar 原生侧自行从 ANativeWindow 读取尺寸；仅在 Surface 实例变化时处理
        if (surface != null && surface != this.surface) setSurface(surface)
    }

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
            try {
                lib.stopEmulation()
                emuThread?.join(8000)
            } catch (_: Throwable) {}
            emuThread = null
            val surf = surface ?: return@synchronized
            try { lib.surfaceChanged(surf) } catch (_: Throwable) {}
            startEmulationLocked()
        }
    }

    override fun unload() = synchronized(lifecycleLock) { cleanupLocked() }

    override fun shutdown() = synchronized(lifecycleLock) { cleanupLocked() }

    private fun cleanupLocked() {
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
        val f = coreOptions["Renderer/resolution_factor"]?.toIntOrNull() ?: 1
        return 400 * (f + 1).coerceIn(1, 19)
    }

    override fun videoHeight(): Int {
        val f = coreOptions["Renderer/resolution_factor"]?.toIntOrNull() ?: 1
        return 240 * (f + 1).coerceIn(1, 19)
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

    override fun saveState(slot: Int, dst: File): Boolean {
        if (!isLoaded) return false
        return try {
            AzaharNative.lib.saveState(slot)
            // UI 依赖 stateFile.exists() 判断槽位可读 —— 触一个标记文件
            dst.parentFile?.mkdirs()
            if (!dst.exists()) dst.createNewFile()
            true
        } catch (t: Throwable) {
            android.util.Log.w("AzaharEngine", "saveState($slot)", t)
            false
        }
    }

    override fun loadState(slot: Int, src: File): Boolean {
        if (!isLoaded) return false
        return try {
            AzaharNative.lib.loadState(slot)
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
