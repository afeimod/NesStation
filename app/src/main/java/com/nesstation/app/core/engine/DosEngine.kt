package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.DosNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [DosNative] (DOSBox-Pure core for DOS/PC games).
 *
 * Architecture mirrors [GbaEngine]:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from the native ring buffer (core-rate
 *     passthrough — no resampling), writes to AudioTrack with BLOCKING mode
 *
 * Audio pipeline:
 *   DOSBox-Pure (44100/48000 Hz) → libretro callback → AudioRingBuffer
 *     → readAudio() JNI → AudioTrack (core's own sample rate)
 *
 * Input pipeline (unique to DOS):
 *   - Standard gamepad bits via [setPad1] (auto-mapped to DOS keys by the core)
 *   - Full keyboard via [injectKeyDown] / [injectKeyUp] (RETROK_* codes)
 *   - Mouse via [injectMouseMove] / [injectMouseButton]
 *   - Input device mode selectable via [setInputDeviceMode] (default = combined)
 *
 * Gamepad bit layout (12 buttons, see DosNative doc):
 *   bit0=A(Enter), bit1=B(Esc), bit2=Select, bit3=Start,
 *   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
 *   bit8=L(mouse left), bit9=R(mouse right),
 *   bit10=X(Space), bit11=Y(Tab)
 *
 * ## Thread safety & restart conflict prevention
 *
 * All lifecycle methods ([loadRom], [unload], [shutdown], [reset], [stop],
 * [stopAudio], [cleanup]) are synchronized on [lifecycleLock] to prevent
 * race conditions when the user switches games quickly or calls unload while
 * loadRom is still starting.
 *
 * The shutdown sequence is:
 *   1. setSurface(null) — prevent blit-after-unload SIGSEGV
 *   2. stop() — interrupt + join emulation thread (up to 3s total)
 *   3. stopAudio() — interrupt + join audio thread, release AudioTrack
 *   4. DosNative.unload() — release native core resources
 *   5. Reset frontend state (_paused, _ffSpeed, hasSurface, isLoaded)
 *
 * The [cleanup] method is idempotent — safe to call multiple times.
 */
class DosEngine private constructor() : EmulatorEngine {

    /**
     * DOSBox video frame buffer. DOS resolutions can be up to 1024x768 (SVGA),
     * so we size for the worst case. The native code zero-pads if the actual
     * frame is smaller.
     */
    override val frameBuffer = IntArray(1024 * 768)

    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    @Volatile private var audioTrack: AudioTrack? = null
    private val audioRunning = AtomicBoolean(false)
    @Volatile private var audioThread: Thread? = null

    @Volatile override var isLoaded = false
        private set

    @Volatile private var _ffSpeed = 0
    @Volatile private var hasSurface = false
    @Volatile private var _paused = false

    /**
     * 音频：统一使用核心自带的混音器采样率直接输出（默认行为）。
     * 不再提供 TV/HDMI 48kHz 兼容模式 —— 所有核心一律「默认音频，
     * 不做特殊处理」：AudioTrack 按核心报告的速率打开，本地重采样器
     * ratio=1 纯旁路，DOSBox-Pure 的混音器输出原样到达扬声器。
     */

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

    override fun ensureLoaded(): Boolean = DosNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) return false

        // Full cleanup of any previous session before loading new ROM.
        // This prevents the restart conflict where stop() hasn't finished
        // joining the old thread before a new one starts.
        cleanup()

        DosNative.setPaths(systemDir, saveDir)

        if (!DosNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        DosNative.setFastForward(_ffSpeed)

        // Default to combined input device mode — gamepad + keyboard + mouse.
        // This lets the on-screen overlay dispatch all three input types.
        DosNative.setInputDeviceMode(3)

        // === 音频：核心自带音频输出（默认，无 TV 模式特殊处理）===
        // retro_load_game 之后核心已报告它自己的混音器速率（与
        // dosbox_pure_audiorate 一致）。AudioTrack 直接按该速率打开，
        // 本地重采样器 ratio=1 纯旁路 → 无线性插值损耗、无杂音。
        val coreRate = DosNative.audioSampleRate().takeIf { it > 8000 } ?: 48000
        DosNative.setSampleRate(coreRate)
        startAudio(coreRate)

        running.set(true)
        thread = thread(name = "doscore-loop", isDaemon = true) {
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
                            DosNative.setPad1(pads.first)
                            DosNative.setPad2(pads.second)
                        }
                    }

                    DosNative.runFrame()

                    if (npHook != null) {
                        npHook.afterFrame(_netFrame)
                        _netFrame++
                    }

                    if (!running.get()) break

                    if (!hasSurface) {
                        DosNative.getFrameBuffer(frameBuffer)
                    }

                    onFrame()

                    // Pacing — melonDS-style fast-forward:
                    //  - Normal: ~60 fps. DOSBox-Pure's `force60fps` option
                    //    (default on) already normalizes output to 60 fps.
                    //  - Fast-forward (_ffSpeed > 0): divide the frame budget
                    //    by the multiplier so emulation runs up to N× speed.
                    val ff = _ffSpeed
                    val paced = if (ff > 0) FramePacer.pace(t0, 60 * ff)
                                else FramePacer.pace(t0, 60)
                    if (!paced) break
                }
            } catch (t: Throwable) {
                android.util.Log.e("DosEngine", "Emulation thread crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        DosNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        DosNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        DosNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) DosNative.videoWidth() else 320
    override fun videoHeight(): Int = if (isLoaded) DosNative.videoHeight() else 200

    override fun setVideoFilter(filter: Int) = DosNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = DosNative.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) DosNative.setFastForward(speed)
    }

    override fun setPaused(paused: Boolean) {
        _paused = paused
    }

    // --- DOSBox-specific input methods ---

    /** Inject a keyboard key-down event. [keyCode] is a DosKeys constant. */
    fun injectKeyDown(keyCode: Int, modifiers: Int = 0) =
        DosNative.injectKeyDown(keyCode, modifiers)

    /** Inject a keyboard key-up event. [keyCode] is a DosKeys constant. */
    fun injectKeyUp(keyCode: Int, modifiers: Int = 0) =
        DosNative.injectKeyUp(keyCode, modifiers)

    /** Inject a mouse move event (relative delta). */
    fun injectMouseMove(dx: Int, dy: Int) = DosNative.injectMouseMove(dx, dy)

    /** Inject a mouse button event. [button] is 0=LEFT, 1=RIGHT, 2=MIDDLE. */
    fun injectMouseButton(button: Int, pressed: Boolean) =
        DosNative.injectMouseButton(button, pressed)

    /**
     * Set input device mode on port 0.
     *   0 = JOYPAD only
     *   1 = KEYBOARD only
     *   2 = MOUSE only
     *   3 = Combined (default — all three)
     */
    fun setInputDeviceMode(mode: Int) = DosNative.setInputDeviceMode(mode)

    private fun startAudio(sampleRate: Int) {
        stopAudio()
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // 2× 最小缓冲（≈40-80ms）：足够吸收模拟线程抖动、又不会引入
            // 可感知延迟。此前 tiny buffer + WRITE_NON_BLOCKING 组合会丢弃
            // 已消费的样本 —— 这是「滋滋」爆音的根本原因之一；配合下方
            // BLOCKING 写入后线程间天然限流，环形缓冲不再溢出丢样。
            val bufSize = (minBuf * 2).coerceIn(4096, 16384)
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
        audioThread = thread(name = "dos-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            // ~21ms per chunk @48kHz stereo — smooth drain without hot spinning.
            val buf = ShortArray(2048)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = DosNative.readAudio(buf)
                        if (n > 0) {
                            // 关键修复：BLOCKING 写入。AudioTrack 缓冲满时会
                            // 挂起音频线程直到有空间，样本一个都不会丢。
                            // 旧实现用 NON_BLOCKING 且忽略返回值 + 立刻继续从
                            // 环形缓冲读出下一批样本 → 装不下的样本被永久丢弃，
                            // 表现为持续的「滋滋」电流声 / 爆音。
                            audioTrack?.write(buf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                        } else {
                            Thread.sleep(1)
                        }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("DosEngine", "Audio thread crashed", t)
            }
        }
    }

    private fun stopAudio() {
        audioRunning.set(false)
        audioThread?.let {
            it.interrupt()
            try { it.join(500) } catch (_: InterruptedException) {}
        }
        audioThread = null

        audioTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioTrack = null
    }

    override fun reset(hard: Boolean) = synchronized(lifecycleLock) {
        DosNative.reset(hard)
    }

    override fun unload() = synchronized(lifecycleLock) {
        cleanup()
    }

    override fun shutdown() = synchronized(lifecycleLock) {
        cleanup()
    }

    /**
     * Complete, idempotent resource cleanup.
     *
     * Releases ALL resources in the correct order:
     *   1. Detach surface (prevents blit-after-unload SIGSEGV)
     *   2. Stop emulation thread (interrupt + join, up to 3s)
     *   3. Stop audio thread + release AudioTrack
     *   4. Unload native core (DosNative.unload)
     *   5. Reset frontend state for a clean next start
     *
     * Safe to call multiple times — each step guards against null/no-op.
     */
    private fun cleanup() {
        // Step 1: Detach surface first to prevent native blit crashes.
        try { setSurface(null) } catch (_: Throwable) {}

        // Step 2: Stop emulation thread — interrupt and wait for it to finish.
        // This is critical for restart safety: if we don't fully join the old
        // thread before loading a new ROM, the old thread's runFrame() calls
        // can race with the new core's initialization.
        running.set(false)
        thread?.let { t ->
            t.interrupt()
            // Try joining with increasing patience. The thread should exit
            // quickly after running.set(false) + interrupt(), but native
            // calls (DosNative.runFrame) may block briefly.
            for (attempt in 0 until 6) {
                try { t.join(500) } catch (_: InterruptedException) { break }
                if (!t.isAlive) break
                android.util.Log.w("DosEngine",
                    "Emulation thread still alive after ${(attempt + 1) * 500}ms")
            }
            if (t.isAlive) {
                android.util.Log.e("DosEngine",
                    "Emulation thread did NOT exit within 3s — proceeding anyway")
            }
        }
        thread = null

        // Step 3: Stop audio thread and release AudioTrack.
        try { stopAudio() } catch (_: Throwable) {}

        // Step 4: Unload native core.
        if (isLoaded) {
            try { DosNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }

        // Step 5: Reset frontend-side state so a new game starts clean.
        // Without this, _paused=true or _ffSpeed=N from the previous session
        // would carry over and make the new game start paused or fast-forwarding.
        _paused = false
        _ffSpeed = 0
        hasSurface = false
    }

    // EmulatorEngine interface implementations — use DOS-specific methods above
    override fun setPad1(bits: Int) = DosNative.setPad1(bits)
    override fun setPad2(bits: Int) { /* DOS is single-player only */ }
    override fun setRegion(region: Int) = DosNative.setRegion(region)
    override fun setSampleRate(rate: Int) = DosNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File): Boolean = DosNative.saveState(slot, dst.absolutePath)
    override fun loadState(slot: Int, src: File): Boolean = DosNative.loadState(slot, src.absolutePath)

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
        DosNative.getFrameBuffer(buf)
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = DosNative.lastError()

    companion object {
        @Volatile private var instance: DosEngine? = null
        fun get(): DosEngine = instance ?: synchronized(this) {
            instance ?: DosEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
