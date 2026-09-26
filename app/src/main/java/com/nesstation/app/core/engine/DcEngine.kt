package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.DcNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [DcNative] (libretro Flycast — Dreamcast /
 * NAOMI / AtomisWave core).
 *
 * Architecture mirrors [PsxEngine] / [FbNeoEngine]:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from the native ring buffer (core-rate
 *     passthrough — no resampling), writes to AudioTrack with BLOCKING mode
 *
 * Video is hardware-rendered inside the native layer (EGL/GLES3 FBO →
 * window blit + swap — see dc_loader.cpp); the Kotlin side only owns the
 * emulation pacing and audio. [frameBuffer] serves UI screenshots via
 * [captureFrame]'s on-demand FBO readback.
 *
 * DC BIOS files (dc_boot.bin / dc_flash.bin / naomi.zip / awbios.zip ...)
 * are looked up by the core in <systemDir>/dc/ (set via [setPaths] with
 * systemDir = <filesDir>; seeded from assets/dc/ on startup).
 *
 * Game images (.gdi/.cdi/.cue/.chd/.iso/.lst and NAOMI/AtomisWave .zip)
 * are passed by path to the core — the core opens them itself (multi-file
 * disc images reference sibling track files; archive naming drives game
 * detection for NAOMI/AtomisWave sets).
 *
 * Button bit layout (12 buttons, standard libretro ids after the
 * dcToLibretroLayout() conversion — same as SNES):
 *   bit0=A, bit1=X, bit2=Select, bit3=Start,
 *   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
 *   bit8=B, bit9=Y, bit10=L, bit11=R
 */
class DcEngine private constructor() : EmulatorEngine, DcCoreEngine {

    /**
     * DC frame buffer. Standard VGA is 640x480; internal resolution up to
     * 1280x960 (2x) is covered for the screenshot path (larger sizes fall
     * back to a dynamic-size capture buffer).
     */
    override val frameBuffer = IntArray(1280 * 1024)

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var audioTrack: AudioTrack? = null

    private val audioRunning = AtomicBoolean(false)
    private var audioThread: Thread? = null

    @Volatile override var isLoaded = false
        private set

    @Volatile private var _ffSpeed = 0
    @Volatile private var hasSurface = false
    @Volatile private var _paused = false

    /** Core-reported refresh rate; used for pacing (NTSC 59.94 / PAL 50). */
    @Volatile private var _targetHz: Int = 60

    /**
     * 前端帧数限制（Hz）。0 = 跟随核心刷新率不限速；>0 时把步进频率硬限制到
     * min(核心刷新率, 限制值)（快进时豁免 —— 用户显式要求超速）。修复
     * "默认 60 帧下部分游戏运行过快"：游戏逻辑超速时降到 30/50 帧即可恢复。
     */
    @Volatile private var _frameLimitHz: Int = 0

    /** FPS HUD：上次轮询提交帧数的时间戳（ns），用于按真实时间间隔换算帧率。 */
    @Volatile private var _fpsPollNs: Long = 0L

    // === Netplay (lockstep hook) ===
    @Volatile private var _frameHook: NetplayHook? = null
    @Volatile private var _netFrame: Long = 0L
    override var frameHook: NetplayHook?
        get() = _frameHook
        set(value) {
            _frameHook = value
            _netFrame = 0L
        }

    /** Lock for all lifecycle methods to prevent restart conflicts. */
    private val lifecycleLock = Any()

    override fun ensureLoaded(): Boolean = DcNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) return false

        cleanup()

        DcNative.setPaths(systemDir, saveDir)

        if (!DcNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        DcNative.setFastForward(_ffSpeed)

        // Pace to the core's own refresh rate: NTSC games run at 59.94 fps,
        // PAL games at 50.0.
        val coreFps = DcNative.videoFps()
        _targetHz = if (coreFps > 10.0 && coreFps < 500.0) Math.round(coreFps).toInt() else 60

        // Default audio — open the AudioTrack at the core's own sample rate
        // (flycast outputs 44.1 kHz; AudioFlinger handles device-rate
        // conversion with its standard high-quality path).
        val rate = DcNative.audioSampleRate().takeIf { it > 0 } ?: 44100
        startAudio(rate)

        running.set(true)
        thread = thread(name = "dccore-loop", isDaemon = true) {
            try {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
                )
                while (running.get()) {
                    if (_paused) {
                        try { Thread.sleep(16) } catch (_: InterruptedException) { break }
                        continue
                    }

                    val t0 = System.nanoTime()

                    if (!running.get()) break

                    // === Netplay lockstep ===
                    val npHook = _frameHook
                    if (npHook != null) {
                        val pads = npHook.beforeFrame(_netFrame)
                        if (pads != null) {
                            DcNative.setPad1(pads.first)
                            DcNative.setPad2(pads.second)
                        }
                    }

                    DcNative.runFrame()

                    if (npHook != null) {
                        npHook.afterFrame(_netFrame)
                        _netFrame++
                    }

                    if (!running.get()) break

                    // 无 Surface 时把核心帧拷进 CPU 侧 frameBuffer（与
                    // FbNeoEngine/PsxEngine 的 hasSurface 分支同模式）。
                    // Flycast 是硬件渲染核心，画面平时只存在于原生 FBO 里；
                    // 仿电视机（TvCurvedGameView）等无 Surface 显示路径靠
                    // 轮询 frameBuffer 拉帧 —— 缺了这一步会永远拉到黑屏。
                    // 原生侧配套修复：无窗口 Surface 时每帧做一次 FBO 回读
                    // （见 dc_loader.cpp stepFrame），否则这里拷到的仍是黑帧。
                    if (!hasSurface) {
                        DcNative.getFrameBuffer(frameBuffer)
                    }

                    onFrame()

                    // Pacing — melonDS/PSX-style fast-forward, paced to the
                    // CORE refresh rate (not a hard-coded 60), 并叠加用户
                    // 手动帧数上限（快进时豁免，见 _frameLimitHz 注释）。
                    val ff = _ffSpeed
                    var hz = if (ff > 0) _targetHz * ff else _targetHz
                    val limit = _frameLimitHz
                    if (limit > 0 && ff <= 0) hz = minOf(hz, limit)
                    val paced = FramePacer.pace(t0, hz.coerceIn(30, 600))
                    if (!paced) break
                }
            } catch (t: Throwable) {
                android.util.Log.e("DcEngine", "Emulation thread crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        DcNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        DcNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        DcNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) DcNative.videoWidth() else 640
    override fun videoHeight(): Int = if (isLoaded) DcNative.videoHeight() else 480

    /**
     * FPS HUD 的真实帧率：按固定间隔轮询核心"真实提交帧数"计数器
     * （dc_loader 的 cb_video 非空调用时 +1），并用真实流逝时间换算。
     */
    override fun realtimeFps(): Double {
        if (!isLoaded) return 0.0
        return try {
            val frames = DcNative.pollPresentedFrames()
            val now = System.nanoTime()
            val last = _fpsPollNs
            _fpsPollNs = now
            if (frames <= 0 || last <= 0L) 0.0
            else frames * 1_000_000_000.0 / (now - last).coerceAtLeast(1)
        } catch (t: Throwable) { 0.0 }
    }

    override fun setVideoFilter(filter: Int) = DcNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = DcNative.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) DcNative.setFastForward(speed)
    }

    override fun setFrameLimit(hz: Int) {
        _frameLimitHz = hz.coerceIn(0, 120)
    }

    override fun setPaused(paused: Boolean) {
        _paused = paused
    }

    private fun startAudio(sampleRate: Int) {
        stopAudio()
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = (minBuf * 4).coerceAtLeast(8192)
            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                bufSize,
                AudioTrack.MODE_STREAM,
                AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
            )
            audioTrack?.play()
        } catch (e: Exception) {
            audioTrack = null
        }

        audioRunning.set(true)
        audioThread = thread(name = "dc-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = DcNative.readAudio(buf)
                        if (n > 0) {
                            audioTrack?.write(buf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                        } else {
                            Thread.sleep(2)
                        }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("DcEngine", "Audio thread crashed", t)
            }
        }
    }

    private fun stopAudio() {
        audioRunning.set(false)
        audioThread?.let {
            it.interrupt()
            try { it.join(200) } catch (_: InterruptedException) {}
        }
        audioThread = null
        audioTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioTrack = null
    }

    override fun reset(hard: Boolean) = synchronized(lifecycleLock) {
        DcNative.reset(hard)
    }

    override fun unload() = synchronized(lifecycleLock) {
        cleanup()
    }

    override fun shutdown() = synchronized(lifecycleLock) {
        cleanup()
    }

    private fun cleanup() {
        try { setSurface(null) } catch (_: Throwable) {}
        running.set(false)
        thread?.let { t ->
            t.interrupt()
            for (attempt in 0 until 6) {
                try { t.join(500) } catch (_: InterruptedException) { break }
                if (!t.isAlive) break
            }
        }
        thread = null
        try { stopAudio() } catch (_: Throwable) {}
        if (isLoaded) {
            try { DcNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }
        _paused = false
        _ffSpeed = 0
        hasSurface = false
        _targetHz = 60
        _frameLimitHz = 0
        _fpsPollNs = 0L
    }

    override fun setPad1(bits: Int) = DcNative.setPad1(bits)
    override fun setPad2(bits: Int) = DcNative.setPad2(bits)
    fun setPad3(bits: Int) = DcNative.setPad3(bits)
    fun setPad4(bits: Int) = DcNative.setPad4(bits)

    /**
     * DC 手柄模拟摇杆（端口 0）：归一化 -1..1（屏幕坐标约定，向上为负）
     * 转 libretro int16 轴（−32768..32767，0 居中）。虚拟摇杆
     * （OnScreenController 经 onAnalogAxes）与物理手柄轴由此推送；
     * flycast 每帧以 RETRO_DEVICE_ANALOG 轮询。DC 无右摇杆，
     * (rx, ry) 预留给外设（当前透传）。
     */
    override fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float) {
        DcNative.setAnalogAxes(
            0,
            (lx * 32767f).toInt().coerceIn(-32768, 32767),
            (ly * 32767f).toInt().coerceIn(-32768, 32767),
            (rx * 32767f).toInt().coerceIn(-32768, 32767),
            (ry * 32767f).toInt().coerceIn(-32768, 32767)
        )
    }

    /**
     * Switch controller ports between the standard digital pad and an
     * analog device. Values map to libretro device ids:
     *   RETRO_DEVICE_NONE = 0, RETRO_DEVICE_JOYPAD = 1, RETRO_DEVICE_ANALOG = 5.
     * Thread-safe: queued natively and applied before the next emulated frame.
     */
    fun setControllerDevice(port: Int, device: Int) = DcNative.setControllerDevice(port, device)

    override fun setRegion(region: Int) = DcNative.setRegion(region)
    override fun setSampleRate(rate: Int) = DcNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File): Boolean = DcNative.saveState(slot, dst.absolutePath)
    override fun loadState(slot: Int, src: File): Boolean = DcNative.loadState(slot, src.absolutePath)

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        val w = videoWidth()
        val h = videoHeight()
        if (w <= 0 || h <= 0) return null
        // 截图走原生按需 FBO 回读（requestFrameCapture + 短暂等待），
        // 与软件核心的"每帧更新共享缓冲"路径不同 —— flycast 的最终画面
        // 在 GPU FBO 里，UI 线程无法直接读取，必须由模拟线程回读。
        val buf = IntArray(w * h)
        DcNative.captureFrame(buf)
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = DcNative.lastError()

    companion object {
        @Volatile private var instance: DcEngine? = null
        fun get(): DcEngine = instance ?: synchronized(this) {
            instance ?: DcEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
