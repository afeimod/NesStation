package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.SnesNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [SnesNative] (snes9x core).
 *
 * Architecture:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from the native ring buffer (core-rate
 *     passthrough — no resampling), writes to AudioTrack with BLOCKING mode (prevents sample
 *     drops / crackling)
 *
 * Audio pipeline (matches the GB/GBC/GBA core):
 *   SNES9x core (~32040 Hz) → libretro callback → AudioRingBuffer
 *     → readAudio() JNI → AudioTrack (core's own sample rate)
 *
 * The resampler runs in the native layer (snes_loader.cpp). AudioTrack is
 * always created at 48000 Hz, which is Android's native audio sample rate.
 * This eliminates the buzzing/crackling/muffled audio that occurred on TV
 * boxes (HDMI output always runs at 48000 Hz) when AudioTrack was created
 * at the SNES native rate (~32040 Hz) and AudioFlinger was forced to
 * resample. Phones often accept 32040 Hz natively or have a higher-quality
 * resampler, so the bug was invisible there — which is why the issue only
 * appeared in TV mode.
 *
 * SNES button bit layout (12 buttons):
 *   bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Up, bit5=Down, bit6=Left, bit7=Right
 *   bit8=X, bit9=Y, bit10=L, bit11=R
 */
class SnesEngine private constructor() : EmulatorEngine, RamCheatEngine {

    // ★★ v69 全核心金手指：补丁列表直通核心 JNI（见 RamCheatEngine 注释）。
    override fun pushCheatPatchList(list: IntArray) {
        try { SnesNative.cheatSetList(list) } catch (_: Throwable) {}
    }

    override val frameBuffer = IntArray(512 * 478) // max SNES resolution

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var audioTrack: AudioTrack? = null

    // Audio thread state
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

    override fun ensureLoaded(): Boolean = SnesNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) return@synchronized false

        // Full cleanup of any previous session before loading new ROM.
        // Critical for "exit game → launch another game" — without it the
        // previous session's threads/state can collide with the new load.
        cleanup()

        SnesNative.setPaths(systemDir, saveDir)

        if (!SnesNative.loadRom(rom.absolutePath)) {
            return@synchronized false
        }
        isLoaded = true

        SnesNative.setFastForward(_ffSpeed)

        // Default audio — open the AudioTrack at the core's own sample rate
        // (no TV-mode 48kHz special handling; AudioFlinger handles any
        // device-rate conversion with its standard high-quality path).
        val rate = SnesNative.audioSampleRate().takeIf { it > 0 } ?: 48000
        startAudio(rate)

        running.set(true)
        thread = thread(name = "snescore-loop", isDaemon = true) {
            try {
                // Boost emulation thread priority for smooth 60fps on TV.
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
                )
                while (running.get()) {
                    if (_paused) {
                        try { Thread.sleep(16) } catch (_: InterruptedException) { break }
                        continue
                    }

                    val t0 = System.nanoTime()

                    // Re-check running right before the native call — unload()
                    // may have set it to false while we were sleeping.
                    if (!running.get()) break

                    // === Netplay lockstep ===
                    val npHook = _frameHook
                    if (npHook != null) {
                        val pads = npHook.beforeFrame(_netFrame)
                        if (pads != null) {
                            SnesNative.setPad1(pads.first)
                            SnesNative.setPad2(pads.second)
                        }
                    }

                    SnesNative.runFrame()

                    if (npHook != null) {
                        npHook.afterFrame(_netFrame)
                        _netFrame++
                    }

                    // Re-check running right after the native call — if
                    // unload() ran during runFrame(), the core is now freed
                    // and we must NOT touch it any further.
                    if (!running.get()) break

                    if (!hasSurface) {
                        SnesNative.getFrameBuffer(frameBuffer)
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
                android.util.Log.e("SnesEngine", "Emulation thread crashed", t)
            }
        }
        true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        SnesNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        SnesNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        SnesNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) SnesNative.videoWidth() else 256
    override fun videoHeight(): Int = if (isLoaded) SnesNative.videoHeight() else 224

    override fun setVideoFilter(filter: Int) = SnesNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = SnesNative.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) SnesNative.setFastForward(speed)
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
            // Use a larger buffer for 48000 Hz output to prevent underruns on
            // weak TV boxes. At 48kHz stereo 16-bit, 1 frame = 4 bytes.
            // minBuf is typically ~4800 bytes (~1200 frames) on most devices.
            // A multiplier of 4 gives ~4800 frames, enough for ~100ms of audio.
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

        // Dedicated audio thread with BLOCKING writes — no crackling.
        // The native readAudio() returns samples at the core's own rate
        // by the AudioResampler in snes_loader.cpp. Blocking write paces the
        // loop at the hardware sample rate.
        audioRunning.set(true)
        audioThread = thread(name = "snes-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = SnesNative.readAudio(buf)
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
                android.util.Log.e("SnesEngine", "Audio thread crashed", t)
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

    override fun reset(hard: Boolean) = SnesNative.reset(hard)

    override fun unload() = synchronized(lifecycleLock) {
        cleanup()
    }

    override fun shutdown() = synchronized(lifecycleLock) {
        cleanup()
    }

    /**
     * Complete, idempotent resource cleanup. Mirrors [FbNeoEngine.cleanup].
     * Safe to call multiple times — ensures "exit → relaunch" flow is crash-free.
     */
    private fun cleanup() {
        // === 卸载顺序很重要，避免闪退（同 NesEngine）===
        // 先 setSurface(null) 让 native blit 提前退出，再停线程，再卸载核心。
        try { setSurface(null) } catch (_: Throwable) {}
        try { stop() } catch (_: Throwable) {}
        try { stopAudio() } catch (_: Throwable) {}
        if (isLoaded) {
            try { SnesNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }
        _paused = false
        _ffSpeed = 0
        hasSurface = false
    }

    override fun setPad1(bits: Int) = SnesNative.setPad1(bits)
    override fun setPad2(bits: Int) = SnesNative.setPad2(bits)
    override fun setRegion(region: Int) = SnesNative.setRegion(region)
    override fun setSampleRate(rate: Int) = SnesNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File): Boolean = SnesNative.saveState(slot, dst.absolutePath)
    override fun loadState(slot: Int, src: File): Boolean = SnesNative.loadState(slot, src.absolutePath)

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
        SnesNative.getFrameBuffer(buf)
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = SnesNative.lastError()

    private fun stop() {
        running.set(false)
        thread?.let { t ->
            t.interrupt()
            // Join with retries — the emulation thread may be inside a
            // long retro_run() call. Without this, unload() could call
            // SnesNative.unload() while the thread is still inside
            // retro_run(), causing a crash.
            // 6 次 × 500ms = 3s（之前 1.5s 对慢速设备不够，偶发闪退）
            for (attempt in 0 until 6) {
                try { t.join(500) } catch (_: InterruptedException) { break }
                if (!t.isAlive) break
            }
        }
        thread = null
    }

    companion object {
        @Volatile private var instance: SnesEngine? = null
        fun get(): SnesEngine = instance ?: synchronized(this) {
            instance ?: SnesEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
