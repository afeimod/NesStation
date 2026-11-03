package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.NesNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [NesNative]. Owns the emulation thread, the
 * AudioTrack for sound, and optional hardware-accelerated surface rendering.
 *
 * Architecture:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from the native ring buffer (core-rate
 *     passthrough — no resampling), writes to AudioTrack with BLOCKING mode (prevents sample
 *     drops / crackling)
 *
 * Audio pipeline (matches the GB/GBC/GBA core):
 *   FCEUmm core (44100 Hz) → libretro callback → AudioRingBuffer
 *     → readAudio() JNI → AudioTrack (core's own sample rate)
 *
 * Default audio output: AudioTrack opens at the core's own sample rate —
 * no TV-mode special handling anywhere. The native resampler runs in
 * passthrough mode (rom_loader.cpp), so the core's mixer output reaches
 * the speaker untouched; AudioFlinger performs standard device-rate
 * conversion only when the hardware requires it.
 *
 * Lifecycle:
 *  - [ensureLoaded] loads the native library (call once at app startup).
 *  - [loadRom] boots a native session and starts the emulation thread.
 *  - [setSurface] attaches a Surface for direct ANativeWindow blitting.
 *  - [unload] / [shutdown] stop the thread and release audio.
 *  - [setPad1] pushes controller state to the core for the next frame.
 */
class NesEngine private constructor() : EmulatorEngine, RamCheatEngine {

    // ★★ v69 全核心金手指：补丁列表直通核心 JNI（见 RamCheatEngine 注释）。
    override fun pushCheatPatchList(list: IntArray) {
        try { NesNative.cheatSetList(list) } catch (_: Throwable) {}
    }

    override val frameBuffer = IntArray(256 * 240)

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
    // When non-null, the emulation loop calls beforeFrame() / afterFrame()
    // around each runFrame() so a NetplayController can sample local input,
    // send it over the network, and synchronously wait for the remote input.
    @Volatile private var _frameHook: NetplayHook? = null
    @Volatile private var _netFrame: Long = 0L
    override var frameHook: NetplayHook?
        get() = _frameHook
        set(value) {
            _frameHook = value
            _netFrame = 0L  // reset frame counter whenever hook changes
        }

    /** Lock for all lifecycle methods to prevent restart conflicts. */
    private val lifecycleLock = Any()

    override fun ensureLoaded(): Boolean = NesNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) return@synchronized false

        // Full cleanup of any previous session before loading new ROM.
        // This is critical for the "exit game → launch another game" flow:
        // without it, stale emulation thread / audio thread / native state
        // from the previous game can collide with the new load and crash.
        cleanup()

        NesNative.setPaths(systemDir, saveDir)

        if (!NesNative.loadRom(rom.absolutePath)) {
            return@synchronized false
        }
        isLoaded = true

        NesNative.setFastForward(_ffSpeed)

        // Default audio — open the AudioTrack at the core's own sample rate
        // (no TV-mode 48kHz special handling; AudioFlinger handles any
        // device-rate conversion with its standard high-quality path).
        val rate = NesNative.audioSampleRate().takeIf { it > 0 } ?: 48000
        startAudio(rate)

        running.set(true)
        thread = thread(name = "nescore-loop", isDaemon = true) {
            try {
                // Boost the emulation thread to a high priority so it doesn't
                // get starved by the UI thread or background GC on low-power
                // TV boxes. THREAD_PRIORITY_URGENT_DISPLAY (-8) is the same
                // priority used by SurfaceFlinger's render thread.
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
                    // If a frame hook is attached, ask it for (pad1, pad2)
                    // for THIS frame. The hook samples local input, sends it
                    // to the network, and synchronously waits for the remote
                    // player's input for the same frame. The returned pad
                    // bits override whatever was last set via setPad1/setPad2
                    // — this is what guarantees both players execute the
                    // same input sequence on the same frame.
                    val npHook = _frameHook
                    if (npHook != null) {
                        val pads = npHook.beforeFrame(_netFrame)
                        if (pads != null) {
                            NesNative.setPad1(pads.first)
                            NesNative.setPad2(pads.second)
                        }
                    }

                    NesNative.runFrame()

                    if (npHook != null) {
                        npHook.afterFrame(_netFrame)
                        _netFrame++
                    }

                    // Re-check running right after the native call — if
                    // unload() ran during runFrame(), isLoaded is now false
                    // and we must NOT touch the core any further.
                    if (!running.get()) break

                    if (!hasSurface) {
                        NesNative.getFrameBuffer(frameBuffer)
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
                // Swallow native crashes on the emulation thread so the UI
                // thread doesn't get killed. The user will see a frozen
                // frame instead of a full app crash.
                android.util.Log.e("NesEngine", "Emulation thread crashed", t)
            }
        }
        true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        NesNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        NesNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        NesNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) NesNative.videoWidth() else 256
    override fun videoHeight(): Int = if (isLoaded) NesNative.videoHeight() else 240

    override fun setVideoFilter(filter: Int) = NesNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = NesNative.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) NesNative.setFastForward(speed)
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
        // The audio thread reads from the native ring buffer (non-blocking,
        // core-rate passthrough — no resampling) and
        // writes to AudioTrack (blocking). AudioTrack plays at the hardware
        // sample rate, so the blocking write naturally paces the loop — when
        // AudioTrack's buffer is full, write() blocks until room is available,
        // preventing audio acceleration.
        audioRunning.set(true)
        audioThread = thread(name = "nes-audio-loop", isDaemon = true) {
            // Boost audio thread priority so it's not starved by the
            // emulation thread on low-power TV devices.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = NesNative.readAudio(buf)
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
                android.util.Log.e("NesEngine", "Audio thread crashed", t)
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

    override fun reset(hard: Boolean) = NesNative.reset(hard)

    override fun unload() = synchronized(lifecycleLock) {
        cleanup()
    }

    override fun shutdown() = synchronized(lifecycleLock) {
        cleanup()
    }

    /**
     * Complete, idempotent resource cleanup. Mirrors [FbNeoEngine.cleanup].
     * Safe to call multiple times — every step is guarded by try/catch and
     * null/flag checks. This is what makes the "exit game → launch another
     * game" flow safe: the previous session's threads are fully joined and
     * native state fully released before [loadRom] starts the new session.
     */
    private fun cleanup() {
        // === 卸载顺序很重要，避免闪退 ===
        // 1. 先 setSurface(null) —— 通知 native 不再 blit 到 surface，
        //    避免 emulation thread 在 surfaceDestroyed 之后还调用
        //    ANativeWindow_lock 操作已释放的窗口（这是最常见的闪退源）。
        // 2. stop() —— 停 emulation thread，等线程退出 retro_run()。
        // 3. stopAudio() —— 停 audio thread，释放 AudioTrack。
        // 4. NesNative.unload() —— 卸载核心（retro_unload_game + retro_deinit）。
        try { setSurface(null) } catch (_: Throwable) {}
        try { stop() } catch (_: Throwable) {}
        try { stopAudio() } catch (_: Throwable) {}
        if (isLoaded) {
            try { NesNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }
        _paused = false
        _ffSpeed = 0
        hasSurface = false
    }

    override fun setPad1(bits: Int) = NesNative.setPad1(bits)
    override fun setPad2(bits: Int) = NesNative.setPad2(bits)
    override fun setRegion(region: Int) = NesNative.setRegion(region)
    override fun setSampleRate(rate: Int) = NesNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File): Boolean = NesNative.saveState(slot, dst.absolutePath)
    override fun loadState(slot: Int, src: File): Boolean = NesNative.loadState(slot, src.absolutePath)

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
        NesNative.getFrameBuffer(buf)
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = NesNative.lastError()

    private fun stop() {
        running.set(false)
        thread?.let { t ->
            t.interrupt()
            // Join with retries — the emulation thread may be inside a
            // long retro_run() call (16-33ms). If it doesn't stop within
            // 500ms, try again up to 6 times (3s total) before giving up.
            // 给足时间让 retro_run() 返回后再释放核心 —— 否则 native
            // 端 retro_unload_game() 会释放 emulation thread 正在访问的
            // 内存，导致偶发 SIGSEGV（用户描述的"偶尔退出游戏闪退"）。
            // 之前只重试 3 次（1.5s），对某些慢速设备 / 长帧渲染不够。
            for (attempt in 0 until 6) {
                try { t.join(500) } catch (_: InterruptedException) { break }
                if (!t.isAlive) break
            }
        }
        thread = null
    }

    companion object {
        @Volatile private var instance: NesEngine? = null
        fun get(): NesEngine = instance ?: synchronized(this) {
            instance ?: NesEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
