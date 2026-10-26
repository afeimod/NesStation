package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.FbNeoNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [FbNeoNative] (FBNeo arcade core).
 *
 * Architecture mirrors [SnesEngine] / [NesEngine]:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from the native ring buffer (core-rate
 *     passthrough — no resampling), writes to AudioTrack with BLOCKING mode
 *
 * Audio pipeline:
 *   FBNeo core (44100/48000 Hz) → libretro callback → AudioRingBuffer
 *     → readAudio() JNI → AudioTrack (core's own sample rate)
 *
 * Lifecycle:
 *  - [ensureLoaded] loads the native library (call once at app startup).
 *  - [loadRom] boots a native session and starts the emulation thread.
 *  - [setSurface] attaches a Surface for direct ANativeWindow blitting.
 *  - [unload] / [shutdown] stop the thread and release audio.
 *  - [setPad1] pushes controller state to the core for the next frame.
 *
 * Arcade ROM zip files are loaded by path — FBNeo's libretro frontend
 * has its own zip/7z VFS and looks for BIOS zips (neogeo.zip, pgm.zip,
 * etc.) in the system directory (set via [setPaths]).
 *
 * Button bit layout (12 buttons, same as SNES):
 *   bit0=A(Btn1), bit1=B(Btn2), bit2=Select(Coin), bit3=Start,
 *   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
 *   bit8=X(Btn3), bit9=Y(Btn4), bit10=L(Btn5), bit11=R(Btn6)
 */
class FbNeoEngine private constructor() : EmulatorEngine {

    /**
     * Arcade frame buffer. Resolutions vary by board (256x224, 320x240,
     * 384x224, 512x448, etc.); we size for the largest common case.
     * The native code zero-pads if the actual frame is smaller.
     */
    override val frameBuffer = IntArray(512 * 512)

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

    override fun ensureLoaded(): Boolean = FbNeoNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) return false

        // Full cleanup of any previous session before loading new ROM.
        cleanup()

        FbNeoNative.setPaths(systemDir, saveDir)

        // ★★ 改版 .dif 兼容（kof97t 三问等）：上游审计要求 romset 同时包含
        //   完整改版 ROM 与 .dif 差分文件，用户手里的"完整 ROM 版"zip 因缺
        //   .dif 被拒。进核心前向 <systemDir>/fbneo/patched/ 铺零填充占位
        //   （fbneo-allow-patched-romsets 已默认开启，patched 目录免 CRC、
        //   仅校验文件名+长度），全零 XOR 为无操作，游戏按原 ROM 运行。
        FbNeoDifCompat.ensureDifPatches(rom, systemDir)

        if (!FbNeoNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        FbNeoNative.setFastForward(_ffSpeed)

        // Default audio — open the AudioTrack at the core's own sample rate
        // (no TV-mode 48kHz special handling; AudioFlinger handles any
        // device-rate conversion with its standard high-quality path).
        val rate = FbNeoNative.audioSampleRate().takeIf { it > 0 } ?: 48000
        startAudio(rate)

        running.set(true)
        thread = thread(name = "fbneocore-loop", isDaemon = true) {
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
                    // ★★ v4.3 对战平台多玩家：FBNeo 支持 4 手柄 —— 走
                    //   beforeFramePads（NetplayHook 新默认方法）取
                    //   [pad1, pad2, pad3, pad4]，2P/3P/4P 各占一槽。
                    //   旧 beforeFrame(pair) 由默认方法兜底转换，单机路径不受影响。
                    val npHook = _frameHook
                    if (npHook != null) {
                        val pads4 = npHook.beforeFramePads(_netFrame)
                        if (pads4 != null) {
                            FbNeoNative.setPad1(pads4[0])
                            FbNeoNative.setPad2(pads4[1])
                            FbNeoNative.setPad3(pads4[2])
                            FbNeoNative.setPad4(pads4[3])
                        }
                    }

                    FbNeoNative.runFrame()

                    if (npHook != null) {
                        npHook.afterFrame(_netFrame)
                        _netFrame++
                    }

                    if (!running.get()) break

                    if (!hasSurface) {
                        FbNeoNative.getFrameBuffer(frameBuffer)
                    }

                    onFrame()

                    // Pacing — melonDS-style fast-forward: divide the frame
                    // budget by the multiplier so emulation runs up to N×
                    // real-time. FramePacer uses a hybrid sleep+parkNanos
                    // deadline strategy — far less jitter than raw
                    // Thread.sleep, which measurably reduced micro-stutter.
                    val ff = _ffSpeed
                    val paced = if (ff > 0) FramePacer.pace(t0, 60 * ff)
                                else FramePacer.pace(t0, 60)
                    if (!paced) break
                }
            } catch (t: Throwable) {
                android.util.Log.e("FbNeoEngine", "Emulation thread crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        FbNeoNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        FbNeoNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        FbNeoNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) FbNeoNative.videoWidth() else 320
    override fun videoHeight(): Int = if (isLoaded) FbNeoNative.videoHeight() else 240

    override fun setVideoFilter(filter: Int) = FbNeoNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = FbNeoNative.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) FbNeoNative.setFastForward(speed)
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
        audioThread = thread(name = "fbneo-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = FbNeoNative.readAudio(buf)
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
                android.util.Log.e("FbNeoEngine", "Audio thread crashed", t)
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
        FbNeoNative.reset(hard)
    }

    override fun unload() = synchronized(lifecycleLock) {
        cleanup()
    }

    override fun shutdown() = synchronized(lifecycleLock) {
        cleanup()
    }

    /**
     * Complete, idempotent resource cleanup. Mirrors [DosEngine.cleanup].
     */
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
            try { FbNeoNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }

        _paused = false
        _ffSpeed = 0
        hasSurface = false
    }

    override fun setPad1(bits: Int) = FbNeoNative.setPad1(bits)
    override fun setPad2(bits: Int) = FbNeoNative.setPad2(bits)
    fun setPad3(bits: Int) = FbNeoNative.setPad3(bits)
    fun setPad4(bits: Int) = FbNeoNative.setPad4(bits)
    override fun setRegion(region: Int) = FbNeoNative.setRegion(region)
    override fun setSampleRate(rate: Int) = FbNeoNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File): Boolean = FbNeoNative.saveState(slot, dst.absolutePath)
    override fun loadState(slot: Int, src: File): Boolean = FbNeoNative.loadState(slot, src.absolutePath)

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        val w = videoWidth()
        val h = videoHeight()
        if (w <= 0 || h <= 0) return null
        val buf = IntArray(w * h)
        // getFrameBuffer 返回值仅表示"自上次读取后是否有新帧"。渲染循环每帧都会
        // 通过 getFrameBuffer(frameBuffer) 消费该标志，UI 侧截图几乎总是拿到
        // false —— 旧代码把它当失败，导致游戏内菜单截图必报"无画面数据"。
        // native 端无论返回值如何都会把最后渲染的一帧拷入 buf（仅在核心未加载
        // 时提前返回，而上面 isLoaded 已拦截），所以这里不再以返回值判定成败。
        FbNeoNative.getFrameBuffer(buf)
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = FbNeoNative.lastError()

    companion object {
        @Volatile private var instance: FbNeoEngine? = null
        fun get(): FbNeoEngine = instance ?: synchronized(this) {
            instance ?: FbNeoEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
