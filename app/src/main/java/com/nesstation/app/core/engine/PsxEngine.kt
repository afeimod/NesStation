package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.PsxNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * High-level façade around [PsxNative] (PCSX-ReARMed PlayStation 1 core).
 *
 * Architecture mirrors [FbNeoEngine] / [NesEngine]:
 *   - Emulation thread: runs game frames, renders to surface, paces to 60fps
 *   - Audio thread: reads from the native ring buffer (core-rate
 *     passthrough — no resampling), writes to AudioTrack with BLOCKING mode
 *
 * PSX BIOS files (scph1000/1/2.bin, scph5500/1/2.bin, psxonpsp660.bin) are
 * looked up by the core in the system directory (set via [setPaths]).
 * Without a BIOS the core falls back to HLE BIOS (less compatible).
 *
 * CD images (.cue/.bin/.chd/.pbp/.m3u/.ecm) are passed by path to the core —
 * the core opens the CD image itself to parse the TOC and locate tracks.
 *
 * Button bit layout (12 buttons, same as SNES):
 *   bit0=□(Square), bit1=✕(Cross), bit2=Select, bit3=Start,
 *   bit4=Up, bit5=Down, bit6=Left, bit7=Right,
 *   bit8=△(Triangle), bit9=○(Circle), bit10=L1, bit11=R1
 */
class PsxEngine private constructor() : EmulatorEngine {

    /**
     * PSX frame buffer. PSX resolutions vary: 320x240 (NTSC), 320x256 (PAL),
     * 640x480 (hi-res)。
     * ★ v24: NEON 2x 增强分辨率（pcsx_rearmed_neon_enhancement_enable）开启后
     *   核心按 2x 输出（上游 frontend/libretro.c:205："PSX max resolution is
     *   640x512, but with enhancement it's 1024x512"）。旧固定 640x480 缓冲会
     *   拒收/裁切增强帧（PAL 320x256 → 640x512、512x240 → 1024x480），
     *   扩到 1024x512 覆盖全部增强档位（约 2MB 常驻，可接受）。
     */
    override val frameBuffer = IntArray(1024 * 512)

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

    /** Core-reported refresh rate; used for pacing (NTSC 59.82614 / PAL 50). */
    @Volatile private var _targetHz: Int = 60

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

    override fun ensureLoaded(): Boolean = PsxNative.ensureLoaded()

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) return false

        cleanup()

        PsxNative.setPaths(systemDir, saveDir)

        if (!PsxNative.loadRom(rom.absolutePath)) {
            return false
        }
        isLoaded = true

        PsxNative.setFastForward(_ffSpeed)

        // Controller-port devices (standard digital vs DualShock analog) may
        // already have been queued by applyCoreOptions — they will be applied
        // on the emu thread at the first stepFrame().

        // Pace to the core's own refresh rate: NTSC games run at 59.826 fps,
        // PAL games at 50.0. The old hard-coded 60 caused PAL titles to play
        // ~20% too fast with crackling audio and caused subtle judder on NTSC.
        val coreFps = PsxNative.videoFps()
        _targetHz = if (coreFps > 10.0 && coreFps < 500.0) Math.round(coreFps).toInt() else 60

        // Default audio — open the AudioTrack at the core's own sample rate
        // (no TV-mode 48kHz special handling; AudioFlinger handles any
        // device-rate conversion with its standard high-quality path).
        val rate = PsxNative.audioSampleRate().takeIf { it > 0 } ?: 48000
        startAudio(rate)

        running.set(true)
        thread = thread(name = "psxcore-loop", isDaemon = true) {
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
                            PsxNative.setPad1(pads.first)
                            PsxNative.setPad2(pads.second)
                        }
                    }

                    PsxNative.runFrame()

                    if (npHook != null) {
                        npHook.afterFrame(_netFrame)
                        _netFrame++
                    }

                    if (!running.get()) break

                    if (!hasSurface) {
                        PsxNative.getFrameBuffer(frameBuffer)
                    }

                    onFrame()

                    // Pacing — melonDS-style fast-forward, paced to the CORE
                    // refresh rate (not a hard-coded 60):
                    //  - Normal:   target Hz as reported by PCSX-ReARMed
                    //    (59.82614 NTSC / 50 PAL) → correct speed + audio pitch.
                    //  - Fast-forward (_ffSpeed > 0): divide the frame budget
                    //    by the multiplier so emulation runs up to N× speed.
                    //    Native applySpeed() also skips blits on non-presented
                    //    frames, keeping ANativeWindow_lock off the hot path.
                    val ff = _ffSpeed
                    val hz = if (ff > 0) _targetHz * ff else _targetHz
                    val paced = FramePacer.pace(t0, hz.coerceIn(30, 600))
                    if (!paced) break
                }
            } catch (t: Throwable) {
                android.util.Log.e("PsxEngine", "Emulation thread crashed", t)
            }
        }
        return true
    }

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        PsxNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        PsxNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        PsxNative.setCoreOption(key, value)
    }

    override fun videoWidth(): Int = if (isLoaded) PsxNative.videoWidth() else 320
    override fun videoHeight(): Int = if (isLoaded) PsxNative.videoHeight() else 240

    /**
     * FPS HUD 的真实帧率：按固定间隔轮询核心“真实提交帧数”计数器
     * （psx_loader 的 cb_video 非空调用时 +1），并用真实流逝时间换算。
     *
     * 修复点：旧实现用前端模拟循环的步进计数，它只会显示帧率限制器
     * 的目标值（NTSC 60 / PAL 50），游戏内部掉帧（很多 PS1 游戏是
     * 30/20fps）时也永远显示 60；现在读到的是核心实际输出的帧率。
     * 快进时按倍速跑，数值同样会正确上升。
     */
    override fun realtimeFps(): Double {
        if (!isLoaded) return 0.0
        return try {
            val frames = PsxNative.pollPresentedFrames()
            val now = System.nanoTime()
            val last = _fpsPollNs
            _fpsPollNs = now
            if (frames <= 0 || last <= 0L) 0.0
            else frames * 1_000_000_000.0 / (now - last).coerceAtLeast(1)
        } catch (t: Throwable) { 0.0 }
    }

    override fun setVideoFilter(filter: Int) = PsxNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = PsxNative.setHighQualityScaling(enabled)

    /**
     * ★ 分辨率倍数（前端整数放大，1..4）：呈现层把核心输出按 N 倍最近邻
     * 放大后上屏 —— 参照 ARMSX upscale_multiplier 的前端等价实现。运行中
     * 热切换（下一帧生效、无需重进游戏），对 2D/CG/FMV 全部生效 ——
     * 上游 NEON 2x 增强（仅 3D、仅 ≤512x256、需重进游戏）的通用补充。
     */
    fun setResolutionScale(scale: Int) = PsxNative.setResolutionScale(scale)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) PsxNative.setFastForward(speed)
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
        audioThread = thread(name = "psx-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = PsxNative.readAudio(buf)
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
                android.util.Log.e("PsxEngine", "Audio thread crashed", t)
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
        PsxNative.reset(hard)
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
            try { PsxNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }
        _paused = false
        _ffSpeed = 0
        hasSurface = false
        _targetHz = 60
        _fpsPollNs = 0L
    }

    override fun setPad1(bits: Int) = PsxNative.setPad1(bits)
    override fun setPad2(bits: Int) = PsxNative.setPad2(bits)
    fun setPad3(bits: Int) = PsxNative.setPad3(bits)
    fun setPad4(bits: Int) = PsxNative.setPad4(bits)

    /**
     * ★★ v60 DualShock 模拟摇杆轴（1P）：lx/ly = 左杆，rx/ry = 右杆。
     * 归一化 -1..1（屏幕坐标约定上=负，与虚拟摇杆同口径）→ int16 推给核心。
     * 与 [setPadTypes] 的 DualShock 模式（RETRO_DEVICE_ANALOG）配合生效 ——
     * 虚拟摇杆 / 实体手柄轴 / 按键映射的摇杆方向三路输入共用本入口。
     */
    fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float) {
        if (!isLoaded) return
        PsxNative.setAnalog1(
            (lx * 32767f).toInt(), (ly * 32767f).toInt(),
            (rx * 32767f).toInt(), (ry * 32767f).toInt()
        )
    }

    /**
     * Switch controller ports between the standard digital pad and the
     * DualShock (analog). Values map to libretro device ids:
     *   RETRO_DEVICE_NONE = 0, RETRO_DEVICE_JOYPAD = 1, RETRO_DEVICE_ANALOG = 5.
     * Thread-safe: queued natively and applied before the next emulated frame.
     */
    fun setControllerDevice(port: Int, device: Int) = PsxNative.setControllerDevice(port, device)

    /** Convenience: configure ports 0+1 from the settings panel pad types
     *  ("standard" -> JOYPAD, anything else -> DualShock ANALOG). */
    fun setPadTypes(type1: String, type2: String) {
        setControllerDevice(0, if (type1 == "standard") 1 else 5)
        setControllerDevice(1, if (type2 == "standard") 1 else 5)
    }

    override fun setRegion(region: Int) = PsxNative.setRegion(region)
    override fun setSampleRate(rate: Int) = PsxNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File): Boolean = PsxNative.saveState(slot, dst.absolutePath)
    override fun loadState(slot: Int, src: File): Boolean = PsxNative.loadState(slot, src.absolutePath)

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
        PsxNative.getFrameBuffer(buf)
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = PsxNative.lastError()

    companion object {
        @Volatile private var instance: PsxEngine? = null
        fun get(): PsxEngine = instance ?: synchronized(this) {
            instance ?: PsxEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}
