package com.nesstation.app.core.engine

import android.view.Surface
import com.nesstation.app.core.jni.AzaharNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 3DS（Azahar/AzaharPlus 2125 核心）进程内引擎。
 *
 * 与 [Psx2Engine]（ARMSX2/PCSX2）同一推模型架构：
 *  - 核心自带模拟/渲染/音频循环（run(path) 阻塞在模拟线程），
 *    Kotlin 侧不拉帧、不建 AudioTrack、不节拍；
 *  - 视频直接渲染到 Surface（核心自管 3DS 双屏布局：上下屏/左右屏/
 *    单屏/大屏小屏等 layout_option 全部由核心完成）；
 *  - 体感：核心经 NDK SensorManager 直读陀螺仪/加速度计（默认
 *    motion_device 已指向传感器后端），无需前端喂数据；
 *  - 触摸：NesStation 把游戏视图上的触摸以视图像素坐标喂给
 *    onTouchEvent —— 核心按当前布局自动映射到 3DS 下屏。
 *
 * 输入约定见 [AzaharNative.buttonEvent]/[AzaharNative.stickMove]
 * （ButtonType id：A=700..ZR=708，十字键 709-712，摇杆 713/718）。
 *
 * 即时存档用核心槽位（saveState(slot)/loadState(slot)），0 为快速存档槽。
 */
class AzaharEngine private constructor() : EmulatorEngine {

    /** 兼容占位（核心直渲 Surface，无 CPU 帧缓冲可读）。 */
    override val frameBuffer = IntArray(400 * 480)

    private val running = AtomicBoolean(false)
    private var emuThread: Thread? = null
    private var heartbeatThread: Thread? = null

    @Volatile override var isLoaded = false
        private set

    @Volatile private var _paused = false
    @Volatile private var currentRom: String? = null
    @Volatile private var onFrameCb: (() -> Unit)? = null

    private val lifecycleLock = Any()

    override fun ensureLoaded(): Boolean = AzaharNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!AzaharNative.isAbiSupported()) {
            android.util.Log.e("AzaharEngine", "3DS 核心需要 arm64 设备")
            return false
        }
        if (!ensureLoaded()) return false
        cleanup()

        AzaharNative.ensureUserDirectory()
        onFrameCb = onFrame
        currentRom = rom.absolutePath

        running.set(true)
        isLoaded = true
        emuThread = thread(name = "azahar-emu", isDaemon = true) {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
            )
            try {
                // 阻塞运行直至 stopEmulation —— 核心内部完成初始化/加载/循环
                AzaharNative.run(rom.absolutePath)
            } catch (t: Throwable) {
                android.util.Log.e("AzaharEngine", "emulation crashed", t)
            }
        }
        heartbeatThread = thread(name = "azahar-hud", isDaemon = true) {
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
                android.util.Log.e("AzaharEngine", "heartbeat crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        AzaharNative.surfaceChanged(surface)
    }

    override fun setSaveName(name: String) {
        // 核心槽位存档按游戏自动命名 —— 无需额外基名
    }

    override fun setCoreOption(key: String, value: String) {
        // 设置写 config.ini（AzaharSettings），再 reloadSettings 热生效
        val ctx = com.nesstation.app.core.jni.AzaharNative.appContext ?: return
        // key 形如 "Renderer.resolution_factor" —— 由设置面板拆分
        val idx = key.indexOf('.')
        if (idx > 0) {
            val section = key.substring(0, idx)
            val k = key.substring(idx + 1)
            com.nesstation.app.core.storage.AzaharDirs.setConfigValue(ctx, section, k, value)
        }
    }

    override fun videoWidth(): Int = 400
    override fun videoHeight(): Int = 480

    override fun setVideoFilter(filter: Int) {}
    override fun setHighQualityScaling(enabled: Boolean) {}

    override fun setFastForward(speed: Int) {
        // 核心无独立快进 API；通过 setTemporaryFrameLimit 近似（4x）
        try {
            if (!isLoaded) return
            if (speed > 0) {
                AzaharNative.setTemporaryFrameLimit(4.0 * speed)
            } else {
                AzaharNative.disableTemporaryFrameLimit()
            }
        } catch (t: Throwable) {
            android.util.Log.w("AzaharEngine", "setFastForward", t)
        }
    }

    override fun setPaused(paused: Boolean) {
        if (_paused == paused) return
        _paused = paused
        if (isLoaded) {
            if (paused) AzaharNative.pause() else AzaharNative.unpause()
        }
    }

    override fun reset(hard: Boolean) = synchronized(lifecycleLock) {
        val rom = currentRom ?: return
        // 核心无 reset API —— stop + 新线程重新 run（run 阻塞，禁止在
        // 调用线程直接跑，否则菜单里点"重置"会把 UI 线程挂住 → ANR）
        AzaharNative.reset()
        val romPath = rom
        thread(name = "azahar-reset", isDaemon = true) {
            try {
                emuThread?.join(3000)
            } catch (_: InterruptedException) {}
            try {
                AzaharNative.run(romPath)
            } catch (t: Throwable) {
                android.util.Log.e("AzaharEngine", "reset run", t)
            }
        }
        // synchronized 块以最后表达式为返回值；thread(...) 返回 Thread，
        // 而接口 reset():Unit —— 显式丢弃返回值以匹配接口签名
        Unit
    }

    override fun unload() = synchronized(lifecycleLock) { cleanup() }
    override fun shutdown() = synchronized(lifecycleLock) { cleanup() }

    private fun cleanup() {
        try {
            if (isLoaded && AzaharNative.isRunning()) {
                AzaharNative.stop()
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
        currentRom = null
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
        val ok = AzaharNative.saveState(slot)
        if (ok) {
            try {
                dst.parentFile?.mkdirs()
                dst.createNewFile()
            } catch (_: Throwable) {}
        }
        return ok
    }

    override fun loadState(slot: Int, src: File): Boolean = AzaharNative.loadState(slot)

    override fun captureFrame(): FrameCapture? = null

    /** 核心真实帧率（perf stats[0]）。 */
    override fun realtimeFps(): Double =
        if (isLoaded) AzaharNative.perfFps() else 0.0

    override fun lastError(): String = ""

    // === 3DS 专属输入（EmulatorScreen 调用） ===

    /** 按键事件（ButtonType id，true=按下）。 */
    fun buttonEvent(id: Int, pressed: Boolean) = AzaharNative.buttonEvent(id, pressed)

    /** 左摇杆向量（x 右+，y 上+，范围 -1..1）。 */
    fun circlePad(x: Float, y: Float) = AzaharNative.stickMove(713, x, y)

    /** C 摇杆向量。 */
    fun cStick(x: Float, y: Float) = AzaharNative.stickMove(718, x, y)

    /** 下屏触摸（游戏视图像素坐标）。 */
    fun touch(x: Float, y: Float, pressed: Boolean) = AzaharNative.touch(x, y, pressed)

    fun touchMoved(x: Float, y: Float) = AzaharNative.touchMoved(x, y)

    /** 交换双屏（游戏内菜单/虚拟键）。 */
    fun swapScreens(swap: Boolean, portrait: Boolean) =
        AzaharNative.swapScreens(swap, portrait)

    companion object {
        @Volatile private var instance: AzaharEngine? = null
        fun get(): AzaharEngine = instance ?: synchronized(this) {
            instance ?: AzaharEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
