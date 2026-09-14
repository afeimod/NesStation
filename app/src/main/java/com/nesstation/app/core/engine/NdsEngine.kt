package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.NdsNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [NdsNative] (melonDS Nintendo DS / DSi core).
 *
 * Architecture mirrors [FbNeoEngine] / [NesEngine] / [PsxEngine]:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from the native ring buffer (core-rate
 *     passthrough — no resampling), writes to AudioTrack with BLOCKING mode
 *
 * melonDS BIOS files (bios7.bin, bios9.bin, firmware.bin) are looked up by
 * the core in the system directory (set via [setPaths]). For DSi mode
 * ("melonds_console_mode" = "dsi") additional BIOS files are required
 * (dsi_arm7.bin, dsi_bios7.bin, dsi_bios9.bin, dsi_firmware.bin, dsi_nand.bin).
 *
 * DS ROMs (.nds / .app / .ids) are pre-loaded into memory by the loader
 * (max 512 MB). The core does NOT support .zip / .7z archives — extract first.
 *
 * Button bit layout (12 buttons, same as SNES):
 *   bit0=A, bit1=B, bit2=Select, bit3=Start,
 *   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
 *   bit8=X, bit9=Y, bit10=L, bit11=R
 *
 * Touchscreen input is supported via [setTouchInput] — pass signed
 * coordinates (-0x8000..0x7FFF) and a pressed flag. The core maps
 * these to the bottom screen pixel coordinates (0..255, 0..191)
 * internally. The state is stored in atomics on the native side and
 * read by the core on each frame.
 */
class NdsEngine private constructor() : EmulatorEngine, NdsCoreEngine {

    /**
     * NDS frame buffer as presented to the dual-screen custom-layout view.
     * In custom-layout mode (no surface) this holds the FILTERED composite
     * frame (HQ2X/HQ4X/XBR upscaled when such a filter is active — sized to
     * [filteredVideoWidth] x [filteredVideoHeight]); in surface mode it keeps
     * the raw frame (pulled only when no surface is attached, see the loop
     * in [loadRom]). DS screens are 256x192 each; the default top+bottom
     * stacked layout produces 256x384. With the GL (OpenGL) compositor the
     * core emits 256x386 (384 + 2-row gap between the screens), so this
     * buffer is grown on demand before each JNI frame pull.
     */
    @Volatile override var frameBuffer: IntArray = IntArray(256 * 384)

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

    override fun ensureLoaded(): Boolean = NdsNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) return false

        cleanup()

        NdsNative.setPaths(systemDir, saveDir)

        if (!NdsNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        NdsNative.setFastForward(_ffSpeed)

        // Default audio — open the AudioTrack at the core's own sample rate
        // (no TV-mode 48kHz special handling; AudioFlinger handles any
        // device-rate conversion with its standard high-quality path).
        val rate = NdsNative.audioSampleRate().takeIf { it > 0 } ?: 48000
        startAudio(rate)

        running.set(true)
        thread = thread(name = "ndscore-loop", isDaemon = true) {
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
                            NdsNative.setPad1(pads.first)
                            NdsNative.setPad2(pads.second)
                        }
                    }

                    NdsNative.runFrame()

                    if (npHook != null) {
                        npHook.afterFrame(_netFrame)
                        _netFrame++
                    }

                    if (!running.get()) break

                    // Frame pull strategy:
                    //  - Custom dual-screen mode (no surface attached): pull
                    //    the (possibly filter-upscaled) frame so
                    //    NdsDualScreenView can slice the two screens out of
                    //    it. This is the ONLY consumer of frameBuffer.
                    //  - Surface mode: the native cb_video already applied
                    //    the filter and blitted straight to the ANativeWindow
                    //    inside runFrame(). Pulling the frame again here
                    //    would copy ~400 KB through JNI per frame for nothing
                    //    (screenshots pull on demand via captureFrame()).
                    if (!hasSurface) {
                        val fw = NdsNative.filteredVideoWidth().coerceAtLeast(1)
                        val fh = NdsNative.filteredVideoHeight().coerceAtLeast(1)
                        if (fw > 0 && fh > 0 && frameBuffer.size < fw * fh) {
                            frameBuffer = IntArray(fw * fh)
                        }
                        NdsNative.getFilteredFrameBuffer(frameBuffer)
                    }

                    onFrame()

                    // Pacing — melonDS-style fast-forward:
                    //  - Normal: target ~60 fps (DS nominal 59.83, close enough).
                    //  - Fast-forward (_ffSpeed > 0): divide the frame budget
                    //    by the speed multiplier so emulation runs up to N×
                    //    real-time (or unthrottled if the device can't keep
                    //    up). Native-side applySpeed() additionally skips
                    //    filter/blit work on non-presented frames.
                    // FramePacer uses a hybrid sleep+parkNanos deadline
                    // strategy — far less jitter than raw Thread.sleep.
                    val ff = _ffSpeed
                    val paced = if (ff > 0) FramePacer.pace(t0, 60 * ff)
                                else FramePacer.pace(t0, 60)
                    if (!paced) break
                }
            } catch (t: Throwable) {
                android.util.Log.e("NdsEngine", "Emulation thread crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        NdsNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        NdsNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        NdsNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) NdsNative.videoWidth() else 256
    override fun videoHeight(): Int = if (isLoaded) NdsNative.videoHeight() else 384

    /** Width of the frame stored in [frameBuffer] (custom-layout mode: the
     *  filter-upscaled width when an upscale filter is active). */
    override fun filteredVideoWidth(): Int = if (isLoaded) NdsNative.filteredVideoWidth() else 256

    /** Height of the frame stored in [frameBuffer] (custom-layout mode: the
     *  filter-upscaled height when an upscale filter is active). */
    override fun filteredVideoHeight(): Int = if (isLoaded) NdsNative.filteredVideoHeight() else 384

    /** Monotonic core frame counter — NdsDualScreenView uses it to skip
     *  redundant redraws on high-refresh displays. */
    override fun frameStamp(): Long = if (isLoaded) NdsNative.frameStamp() else 0L

    override fun setVideoFilter(filter: Int) = NdsNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = NdsNative.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) NdsNative.setFastForward(speed)
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
        audioThread = thread(name = "nds-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = NdsNative.readAudio(buf)
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
                android.util.Log.e("NdsEngine", "Audio thread crashed", t)
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
        NdsNative.reset(hard)
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
            try { NdsNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }
        _paused = false
        _ffSpeed = 0
        hasSurface = false
    }

    override fun setPad1(bits: Int) = NdsNative.setPad1(bits)
    override fun setPad2(bits: Int) = NdsNative.setPad2(bits)
    fun setPad3(bits: Int) = NdsNative.setPad3(bits)
    fun setPad4(bits: Int) = NdsNative.setPad4(bits)
    /**
     * Set touchscreen input state (legacy composite-frame path, Hybrid
     * layouts only — see NdsNative.setTouchInput).
     * @param x Normalized X (0..0xFFFF, maps to 0..255 by the core).
     * @param y Normalized Y (0..0xFFFF, maps to 0..191 by the core).
     * @param pressed true = touching, false = released.
     */
    override fun setTouchInput(x: Int, y: Int, pressed: Boolean) = NdsNative.setTouchInput(x, y, pressed)

    /**
     * Set touchscreen input with DIRECT bottom-screen pixel coordinates —
     * the official melonDS Android frontend architecture. Preferred for
     * every non-hybrid layout including the custom free-form layout.
     * @param x Bottom-screen pixel X (0..255).
     * @param y Bottom-screen pixel Y (0..191).
     * @param pressed true = touching, false = released.
     */
    override fun setTouchInputDirect(x: Int, y: Int, pressed: Boolean) = NdsNative.setTouchInputDirect(x, y, pressed)
    override fun setRegion(region: Int) = NdsNative.setRegion(region)
    override fun setSampleRate(rate: Int) = NdsNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File): Boolean = NdsNative.saveState(slot, dst.absolutePath)
    override fun loadState(slot: Int, src: File): Boolean = NdsNative.loadState(slot, src.absolutePath)

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
        NdsNative.getFrameBuffer(buf)
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = NdsNative.lastError()

    companion object {
        @Volatile private var instance: NdsEngine? = null
        fun get(): NdsEngine = instance ?: synchronized(this) {
            instance ?: NdsEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
