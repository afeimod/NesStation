package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.FlycastNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [FlycastNative] (Flycast — Dreamcast / Naomi /
 * Atomiswave core).
 *
 * Architecture mirrors [PsxEngine] (pull-model) with one structural
 * difference: rendering is hardware-based end to end.
 *   - Emulation thread: runs game frames through the core, which renders
 *     into a frontend-provided GLES3 FBO; the native loader composites the
 *     FBO into the attached Surface and swaps (libretro HW render protocol).
 *     When no Surface is attached the EGL context falls back to a pbuffer
 *     and frames are glReadPixels'd into [frameBuffer] so the fallback
 *     Bitmap path keeps working.
 *   - Audio thread: reads from the native ring buffer (44100 Hz core-rate
 *     passthrough — no resampling), writes to AudioTrack with BLOCKING mode.
 *
 * BIOS files (dc_boot.bin + dc_flash.bin, naomi.zip / atomiswave.zip) are
 * looked up by the core in <systemDir>/dc/ (set via [setPaths] +
 * FlycastGameHelper.ensureBiosLayout in NesApp).
 *
 * Button bit layout (16-bit project layout, screen labels):
 *   bit0=A(✕圈叉见类注), bit1=B, bit2=Select, bit3=Start,
 *   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
 *   bit8=X, bit9=Y, bit10=L1, bit11=R1, bit12=L2, bit13=R2
 * The native loader converts this to flycast's libretro mapping:
 *   screen A→DC A, B→DC B, X→DC X, Y→DC Y, L2/R2→analog triggers.
 * The analog stick is pushed separately via [setAnalogAxes] (DC games read
 * the thumb stick through RETRO_DEVICE_ANALOG — required for most titles).
 */
class FlycastEngine private constructor() : EmulatorEngine {

    /**
     * Fallback frame buffer (no-Surface preview). Dreamcast renders at
     * 640x480 natively; the preview caps at 1280x960 (2x internal res).
     */
    override val frameBuffer = IntArray(1280 * 960)

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

    /** Core-reported refresh rate; used for pacing (NTSC 59.826 / PAL 50). */
    @Volatile private var _targetHz: Int = 60

    /** FPS HUD：上次轮询提交帧数的时间戳（ns）。 */
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

    override fun ensureLoaded(): Boolean = FlycastNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) return false

        cleanup()

        FlycastNative.setPaths(systemDir, saveDir)

        if (!FlycastNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        FlycastNative.setFastForward(_ffSpeed)

        // Pace to the core's own refresh rate: NTSC 59.826 fps / PAL 50.
        val coreFps = FlycastNative.videoFps()
        _targetHz = if (coreFps > 10.0 && coreFps < 500.0) Math.round(coreFps).toInt() else 60

        // AudioTrack opens at the core's own rate (44100 Hz — AICA).
        val rate = FlycastNative.audioSampleRate().takeIf { it > 0 } ?: 44100
        startAudio(rate)

        running.set(true)
        thread = thread(name = "flycast-loop", isDaemon = true) {
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
                            FlycastNative.setPad1(pads.first)
                            FlycastNative.setPad2(pads.second)
                        }
                    }

                    FlycastNative.runFrame()

                    if (npHook != null) {
                        npHook.afterFrame(_netFrame)
                        _netFrame++
                    }

                    if (!running.get()) break

                    if (!hasSurface) {
                        FlycastNative.getFrameBuffer(frameBuffer)
                    }

                    onFrame()

                    // Pacing — flycast-style fast-forward, paced to the CORE
                    // refresh rate. Fast-forward divides the frame budget by
                    // the multiplier; the GPU path has no per-frame blit to
                    // skip so unlike the 2D cores no native blit gate needed.
                    val ff = _ffSpeed
                    val hz = if (ff > 0) _targetHz * ff else _targetHz
                    val paced = FramePacer.pace(t0, hz.coerceIn(30, 600))
                    if (!paced) break
                }
            } catch (t: Throwable) {
                android.util.Log.e("FlycastEngine", "Emulation thread crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        FlycastNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        FlycastNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        FlycastNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) FlycastNative.videoWidth() else 640
    override fun videoHeight(): Int = if (isLoaded) FlycastNative.videoHeight() else 480

    /**
     * FPS HUD 的真实帧率：按固定间隔轮询核心"真实提交帧数"计数器
     * （loader 的 FBO 帧回调计数），用真实流逝时间换算。快进时同样正确上升。
     */
    override fun realtimeFps(): Double {
        if (!isLoaded) return 0.0
        return try {
            val frames = FlycastNative.pollPresentedFrames()
            val now = System.nanoTime()
            val last = _fpsPollNs
            _fpsPollNs = now
            if (frames <= 0 || last <= 0L) 0.0
            else frames * 1_000_000_000.0 / (now - last).coerceAtLeast(1)
        } catch (t: Throwable) { 0.0 }
    }

    override fun setVideoFilter(filter: Int) = FlycastNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = FlycastNative.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) FlycastNative.setFastForward(speed)
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
        audioThread = thread(name = "flycast-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = FlycastNative.readAudio(buf)
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
                android.util.Log.e("FlycastEngine", "Audio thread crashed", t)
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
        FlycastNative.reset(hard)
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
            try { FlycastNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }
        _paused = false
        _ffSpeed = 0
        hasSurface = false
        _targetHz = 60
        _fpsPollNs = 0L
    }

    override fun setPad1(bits: Int) = FlycastNative.setPad1(bits)
    override fun setPad2(bits: Int) = FlycastNative.setPad2(bits)
    fun setPad3(bits: Int) = FlycastNative.setPad3(bits)
    fun setPad4(bits: Int) = FlycastNative.setPad4(bits)

    /**
     * 推送模拟摇杆四轴（port 0）。
     * @param lx/ly/rx/ry 归一化 -1..1（libretro 约定右/下为正），内部转 int16
     */
    fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float) {
        FlycastNative.setAnalogAxis(0, 0, (lx * 32767).toInt().toShort())
        FlycastNative.setAnalogAxis(0, 1, (ly * 32767).toInt().toShort())
        FlycastNative.setAnalogAxis(0, 2, (rx * 32767).toInt().toShort())
        FlycastNative.setAnalogAxis(0, 3, (ry * 32767).toInt().toShort())
    }

    /** Switch maple port device (JOYPAD=1 / KEYBOARD=3 / LIGHTGUN=10 / ...). */
    fun setControllerDevice(port: Int, device: Int) = FlycastNative.setControllerDevice(port, device)

    override fun setRegion(region: Int) = FlycastNative.setRegion(region)
    override fun setSampleRate(rate: Int) = FlycastNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File): Boolean = FlycastNative.saveState(slot, dst.absolutePath)
    override fun loadState(slot: Int, src: File): Boolean = FlycastNative.loadState(slot, src.absolutePath)

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        val w = videoWidth().coerceAtLeast(1)
        val h = videoHeight().coerceAtLeast(1)
        if (w <= 0 || h <= 0) return null
        // GPU 路径下 CPU 帧缓冲只在显式请求时刷新：先排一次 readback，
        // 等待几帧（~60ms）让模拟线程完成 glReadPixels 再拷贝。
        // 无 Surface 预览模式时每帧都会自动 readback，等待是无害的。
        try { FlycastNative.requestFrameReadback() } catch (_: Throwable) {}
        try { Thread.sleep(60) } catch (_: InterruptedException) {}
        val buf = IntArray(w * h)
        // native 端把最后渲染的一帧拷入 buf（readback 尺寸与核心分辨率
        // 一致，上限同 FBO 的 2560x1920）；返回值只代表"是否新帧"，
        // 与 PSX 截图一样不作为成败判定。
        FlycastNative.getFrameBuffer(buf)
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = FlycastNative.lastError()

    companion object {
        @Volatile private var instance: FlycastEngine? = null
        fun get(): FlycastEngine = instance ?: synchronized(this) {
            instance ?: FlycastEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
