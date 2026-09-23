package com.nesstation.app.core.engine

import android.view.Surface
import com.nesstation.app.core.jni.IshirukaNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * NGC/WII（Ishiruka — Dolphin fork 5.0-15560）进程内引擎。
 *
 * 推模型（同 [Psx2Engine]/[AzaharEngine]）：核心自持模拟/渲染/音频循环，
 * Kotlin 侧只做 Surface 生命周期、输入注入、INI 设置。
 *
 * 输入（与核心自带 GCPadNew.ini/WiimoteNew.ini 的 Touchscreen id 一致）：
 *  - 摇杆为 ±配对轴：y 同时写 11/12（Up/Down），x 同时写 13/14（Left/Right）
 *    （与官方 overlay 实现逐字节一致，方向语义由轴配对自洽）
 *  - IR 指针：绝对模式，值 = 触点相对游戏视图中心的归一化 / 显示缩放
 *    （复刻官方 IR overlay 公式；支持 IR/Hide=118 与 IR/Recenter=139）
 *  - 体感：设备方向传感器（TYPE_GAME_ROTATION_VECTOR）→ 倾斜轴 127-130
 *    （复刻官方 Tilt overlay 公式），摇晃=按钮 132-134 / 220-222
 *  - 控制器切换：扩展 None/Nunchuk/Classic 经 WiimoteNew.ini +
 *    ReloadWiimoteConfig 热切换
 */
class IshirukaEngine private constructor() : EmulatorEngine {

    override val frameBuffer = IntArray(1280 * 1024)

    private val running = AtomicBoolean(false)
    private var emuThread: Thread? = null
    private var heartbeatThread: Thread? = null

    @Volatile override var isLoaded = false
        private set

    @Volatile private var _paused = false
    @Volatile private var currentPaths: Array<String>? = null
    @Volatile private var onFrameCb: (() -> Unit)? = null

    /** 当前游戏是否 Wii（false = GameCube）——由 GameFileCache 平台号判定。 */
    @Volatile var isWiiGame: Boolean = true
        private set

    private val lifecycleLock = Any()

    // === 输入 id 常量（与核心 INI 一致） ===
    object GcId {
        const val A = 0
        const val B = 1
        const val START = 2
        const val X = 3
        const val Y = 4
        const val Z = 5
        const val UP = 6
        const val DOWN = 7
        const val LEFT = 8
        const val RIGHT = 9
        const val STICK_Y_UP = 11
        const val STICK_Y_DOWN = 12
        const val STICK_X_LEFT = 13
        const val STICK_X_RIGHT = 14
        const val C_Y_UP = 16
        const val C_Y_DOWN = 17
        const val C_X_LEFT = 18
        const val C_X_RIGHT = 19
        const val TRIGGER_L = 20
        const val TRIGGER_R = 21
    }

    object WiiId {
        const val A = 100
        const val B = 101
        const val MINUS = 102
        const val PLUS = 103
        const val HOME = 104
        const val ONE = 105
        const val TWO = 106
        const val UP = 107
        const val DOWN = 108
        const val LEFT = 109
        const val RIGHT = 110
        const val IR_Y_UP = 112
        const val IR_Y_DOWN = 113
        const val IR_X_LEFT = 114
        const val IR_X_RIGHT = 115
        const val IR_HIDE = 118
        const val SWING_UP = 120
        const val SWING_DOWN = 121
        const val SWING_LEFT = 122
        const val SWING_RIGHT = 123
        const val SWING_FWD = 124
        const val SWING_BACK = 125
        const val TILT_FWD = 127
        const val TILT_BACK = 128
        const val TILT_LEFT = 129
        const val TILT_RIGHT = 130
        const val SHAKE_X = 132
        const val SHAKE_Y = 133
        const val SHAKE_Z = 134
        const val SIDEWAYS_TOGGLE = 135
        const val UPRIGHT_TOGGLE = 136
        const val SIDEWAYS_HOLD = 137
        const val UPRIGHT_HOLD = 138
        const val IR_RECENTER = 139
        const val NUNCHUK_C = 200
        const val NUNCHUK_Z = 201
        const val NUN_STICK_Y_UP = 203
        const val NUN_STICK_Y_DOWN = 204
        const val NUN_STICK_X_LEFT = 205
        const val NUN_STICK_X_RIGHT = 206
        const val NUN_SHAKE_X = 220
        const val NUN_SHAKE_Y = 221
        const val NUN_SHAKE_Z = 222
        const val CLASSIC_A = 300
        const val CLASSIC_B = 301
        const val CLASSIC_X = 302
        const val CLASSIC_Y = 303
        const val CLASSIC_MINUS = 304
        const val CLASSIC_PLUS = 305
        const val CLASSIC_HOME = 306
        const val CLASSIC_ZL = 307
        const val CLASSIC_ZR = 308
        const val CLASSIC_UP = 309
        const val CLASSIC_DOWN = 310
        const val CLASSIC_LEFT = 311
        const val CLASSIC_RIGHT = 312
        const val CLASSIC_L_Y_UP = 314
        const val CLASSIC_L_Y_DOWN = 315
        const val CLASSIC_L_X_LEFT = 316
        const val CLASSIC_L_X_RIGHT = 317
        const val CLASSIC_R_Y_UP = 319
        const val CLASSIC_R_Y_DOWN = 320
        const val CLASSIC_R_X_LEFT = 321
        const val CLASSIC_R_X_RIGHT = 322
        const val CLASSIC_L = 323
        const val CLASSIC_R = 324
    }

    override fun ensureLoaded(): Boolean = IshirukaNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!IshirukaNative.isAbiSupported()) {
            android.util.Log.e("IshirukaEngine", "NGC/WII 核心需要 arm64 设备")
            return false
        }
        val ctx = IshirukaNative.appContext ?: return false
        if (!IshirukaNative.ensureDirectories(ctx)) return false
        cleanup()

        // GC / Wii 判定（GameFileCache 平台号：0=GC，1=Wii，2=WiiWare）
        val meta = IshirukaNative.queryGameInfo(rom.absolutePath)
        isWiiGame = meta?.first?.let { it != 0 } ?: guessWii(rom)

        onFrameCb = onFrame
        currentPaths = arrayOf(rom.absolutePath)

        running.set(true)
        isLoaded = true
        emuThread = thread(name = "ishiruka-emu", isDaemon = true) {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
            )
            try {
                val revision = try {
                    org.dolphinemu.dolphinemu.NativeLibrary.GetGitRevision() ?: ""
                } catch (_: Throwable) {
                    ""
                }
                IshirukaNative.run(arrayOf(rom.absolutePath), revision)
            } catch (t: Throwable) {
                android.util.Log.e("IshirukaEngine", "emulation crashed", t)
            }
        }
        heartbeatThread = thread(name = "ishiruka-hud", isDaemon = true) {
            try {
                while (running.get()) {
                    if (!_paused) onFrameCb?.invoke()
                    try {
                        Thread.sleep(33)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("IshirukaEngine", "heartbeat crashed", t)
            }
        }
        return true
    }

    private fun guessWii(rom: File): Boolean = when (rom.extension.lowercase()) {
        "wad", "wbfs", "rvz" -> true
        else -> true
    }

    override fun setSurface(surface: Surface?) {
        IshirukaNative.surfaceChanged(surface)
    }

    override fun setSaveName(name: String) {}

    override fun setCoreOption(key: String, value: String) {
        // key 形如 "Dolphin.ini/Core/CPUCore" 或 "GFX.ini/Settings/aspect"
        val parts = key.split('/')
        if (parts.size == 3) {
            IshirukaNative.setUserSetting(parts[0], parts[1], parts[2], value)
        }
    }

    override fun videoWidth(): Int = 1280
    override fun videoHeight(): Int = 720

    override fun setVideoFilter(filter: Int) {}
    override fun setHighQualityScaling(enabled: Boolean) {}

    /** Dolphin 无统一快进 —— 通过 EmulationSpeed 实现（核内热生效）。 */
    override fun setFastForward(speed: Int) {
        if (speed > 0) {
            IshirukaNative.setUserSetting(
                "Dolphin.ini", "Core", "EmulationSpeed", (speed * 2).toString()
            )
        } else {
            IshirukaNative.setUserSetting("Dolphin.ini", "Core", "EmulationSpeed", "1.0")
        }
    }

    override fun setPaused(paused: Boolean) {
        if (_paused == paused) return
        _paused = paused
        if (isLoaded) {
            if (paused) IshirukaNative.pause() else IshirukaNative.unpause()
        }
    }

    override fun reset(hard: Boolean) {
        // Dolphin 核心无 reset API（与官方行为一致）
    }

    override fun unload() = synchronized(lifecycleLock) { cleanup() }
    override fun shutdown() = synchronized(lifecycleLock) { cleanup() }

    private fun cleanup() {
        try {
            if (isLoaded && IshirukaNative.isRunning()) {
                IshirukaNative.stop()
            }
        } catch (_: Throwable) {}
        running.set(false)
        emuThread?.let { t ->
            try { t.join(4000) } catch (_: InterruptedException) {}
        }
        emuThread = null
        heartbeatThread?.interrupt()
        heartbeatThread = null
        try { setSurface(null) } catch (_: Throwable) {}
        isLoaded = false
        _paused = false
        currentPaths = null
        onFrameCb = null
    }

    override fun setPad1(bits: Int) {}
    override fun setPad2(bits: Int) {}
    override var frameHook: NetplayHook?
        get() = null
        set(_) {}

    override fun setRegion(region: Int) {}
    override fun setSampleRate(rate: Int) {}

    override fun saveState(slot: Int, dst: File): Boolean {
        val ok = IshirukaNative.saveState(slot)
        if (ok) {
            try {
                dst.parentFile?.mkdirs()
                dst.createNewFile()
            } catch (_: Throwable) {}
        }
        return ok
    }

    override fun loadState(slot: Int, src: File): Boolean = IshirukaNative.loadState(slot)

    override fun captureFrame(): FrameCapture? = null

    override fun realtimeFps(): Double = 0.0

    override fun lastError(): String = ""

    // ====================================================================
    // === NGC/WII 专属输入 API（EmulatorScreen 调用） ===
    // ====================================================================

    /** 通用按键事件（GC/Wii/双节棍/经典 id 通用）。 */
    fun buttonEvent(id: Int, down: Boolean) = IshirukaNative.buttonEvent(id, down)

    /** GameCube 主摇杆（x 右+，y 上+，-1..1）。 */
    fun gcStick(x: Float, y: Float) {
        IshirukaNative.axisEvent(GcId.STICK_Y_UP, y)
        IshirukaNative.axisEvent(GcId.STICK_Y_DOWN, y)
        IshirukaNative.axisEvent(GcId.STICK_X_LEFT, x)
        IshirukaNative.axisEvent(GcId.STICK_X_RIGHT, x)
    }

    /** GameCube C 摇杆。 */
    fun gcCStick(x: Float, y: Float) {
        IshirukaNative.axisEvent(GcId.C_Y_UP, y)
        IshirukaNative.axisEvent(GcId.C_Y_DOWN, y)
        IshirukaNative.axisEvent(GcId.C_X_LEFT, x)
        IshirukaNative.axisEvent(GcId.C_X_RIGHT, x)
    }

    /** GC 扳机（L/R 数字按压：满行程）。 */
    fun gcTrigger(left: Boolean, pressed: Boolean) {
        IshirukaNative.axisEvent(
            if (left) GcId.TRIGGER_L else GcId.TRIGGER_R,
            if (pressed) 1f else 0f
        )
    }

    /** 双节棍摇杆。 */
    fun nunchukStick(x: Float, y: Float) {
        IshirukaNative.axisEvent(WiiId.NUN_STICK_Y_UP, y)
        IshirukaNative.axisEvent(WiiId.NUN_STICK_Y_DOWN, y)
        IshirukaNative.axisEvent(WiiId.NUN_STICK_X_LEFT, x)
        IshirukaNative.axisEvent(WiiId.NUN_STICK_X_RIGHT, x)
    }

    /** 经典手柄双摇杆。 */
    fun classicLeftStick(x: Float, y: Float) {
        IshirukaNative.axisEvent(WiiId.CLASSIC_L_Y_UP, y)
        IshirukaNative.axisEvent(WiiId.CLASSIC_L_Y_DOWN, y)
        IshirukaNative.axisEvent(WiiId.CLASSIC_L_X_LEFT, x)
        IshirukaNative.axisEvent(WiiId.CLASSIC_L_X_RIGHT, x)
    }

    fun classicRightStick(x: Float, y: Float) {
        IshirukaNative.axisEvent(WiiId.CLASSIC_R_Y_UP, y)
        IshirukaNative.axisEvent(WiiId.CLASSIC_R_Y_DOWN, y)
        IshirukaNative.axisEvent(WiiId.CLASSIC_R_X_LEFT, x)
        IshirukaNative.axisEvent(WiiId.CLASSIC_R_X_RIGHT, x)
    }

    /**
     * ★ IR 指针（绝对模式，复刻官方 IR overlay 公式）。
     * @param x, y 触点在游戏视图内的像素坐标
     * @param viewW, viewH 游戏视图尺寸（px）
     */
    fun irPointer(x: Float, y: Float, viewW: Int, viewH: Int) {
        if (viewW <= 0 || viewH <= 0) return
        val viewAspect = viewW.toFloat() / viewH.toFloat()
        val gameAspect = IshirukaNative.gameAspectRatio()
        val scale = ((IshirukaNative.gameDisplayScale() - 1f) / 2f + 1f)
        var scaleI = 1f
        var scaleJ = 1f
        var halfH: Float
        if (gameAspect > viewAspect) {
            scaleJ = gameAspect / viewAspect
            halfH = Math.round(viewW / gameAspect) / 2f
        } else {
            scaleI = gameAspect / viewAspect
            halfH = viewH / 2f
        }
        val halfW = (viewW * scaleI) / 2f
        val yVal = ((y * scaleJ) - halfH) / halfH / scale
        val xVal = ((x * scaleI) - halfW) / halfW / scale
        // 与官方 overlay 相同：y 同时写 112/113，x 同时写 114/115
        IshirukaNative.axisEvent(WiiId.IR_Y_UP, yVal)
        IshirukaNative.axisEvent(WiiId.IR_Y_DOWN, yVal)
        IshirukaNative.axisEvent(WiiId.IR_X_LEFT, xVal)
        IshirukaNative.axisEvent(WiiId.IR_X_RIGHT, xVal)
    }

    /** IR 复位（抬手归零）。 */
    fun irReset() {
        IshirukaNative.axisEvent(WiiId.IR_Y_UP, 0f)
        IshirukaNative.axisEvent(WiiId.IR_Y_DOWN, 0f)
        IshirukaNative.axisEvent(WiiId.IR_X_LEFT, 0f)
        IshirukaNative.axisEvent(WiiId.IR_X_RIGHT, 0f)
    }

    // === 体感（倾斜）——官方 Tilt overlay 公式 ===

    private var tiltCalibrated = false
    private var calibAzimuth = 0f
    private var calibRoll = 0f
    private var calibPitch = 0f

    /** 重新校准倾斜基准（当前手机姿态为水平）。 */
    fun recalibrateTilt() {
        tiltCalibrated = false
    }

    /**
     * 设备方向（getOrientation 输出 [azimuth, pitch, roll] 弧度）。
     * EmulatorScreen 的 SensorListener 转发。
     */
    fun feedOrientation(orientation: FloatArray) {
        if (orientation.size < 3) return
        if (!tiltCalibrated) {
            calibAzimuth = orientation[0]
            calibRoll = orientation[2]
            calibPitch = orientation[1]
            tiltCalibrated = true
            // 首帧归零
            IshirukaNative.axisEvent(WiiId.TILT_FWD, 0f)
            IshirukaNative.axisEvent(WiiId.TILT_BACK, 0f)
            IshirukaNative.axisEvent(WiiId.TILT_LEFT, 0f)
            IshirukaNative.axisEvent(WiiId.TILT_RIGHT, 0f)
            return
        }
        val rollDelta = calibRoll - orientation[2]
        val pitchDelta = calibPitch - orientation[1]
        // 官方公式：value * (|value| + 1) 的响应曲线
        val dY = rollDelta * (Math.abs(rollDelta) + 1f)
        val dX = pitchDelta * (Math.abs(pitchDelta) + 1f)
        IshirukaNative.axisEvent(WiiId.TILT_FWD, dY)
        IshirukaNative.axisEvent(WiiId.TILT_BACK, dY)
        IshirukaNative.axisEvent(WiiId.TILT_LEFT, dX)
        IshirukaNative.axisEvent(WiiId.TILT_RIGHT, dX)
    }

    // === 控制器切换（扩展管理） ===

    /** 扩展切换：None / Nunchuk / Classic（热生效）。 */
    fun setExtension(extension: String) {
        val ctx = IshirukaNative.appContext ?: return
        IshirukaNative.setExtension(ctx, extension)
    }

    /** GC / Wii 手柄方案（仅 UI 语义；核心按游戏类型自动选 GCPad/Wiimote）。 */
    fun setControllerModeIni(mode: String) {
        // WiimoteProfile.ini 的 profile 记录（官方 WiimoteProfile.ini 形状）
        val ctx = IshirukaNative.appContext ?: return
        try {
            val f = File(IshirukaNative.configDir(ctx), "WiimoteProfile.ini")
            f.parentFile?.mkdirs()
            if (!f.exists()) {
                ctx.assets.open("dolphin/WiimoteProfile.ini").use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaEngine", "setControllerModeIni", t)
        }
    }

    companion object {
        @Volatile private var instance: IshirukaEngine? = null
        fun get(): IshirukaEngine = instance ?: synchronized(this) {
            instance ?: IshirukaEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
