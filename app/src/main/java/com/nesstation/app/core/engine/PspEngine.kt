package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.PspNative
import com.nesstation.app.ui.emulator.PspSettingsStore
import com.nesstation.app.ui.emulator.ensurePpssppEnvironment
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * ★★ PSP（libretro PPSSPP 核心）进程内引擎 —— NesStation 统一集成 ★★
 *
 * 与 DC（libdccore → dlopen libflycast_libretro_android.so）、PSX
 * （libpsxcore → dlopen libpcsx_rearmed_libretro_android.so）完全同构的
 * libretro 模式：libpspcore.so 运行时加载预编译 libppsspp_libretro_android.so
 * （官方 libretro buildbot 构建）并驱动标准 retro_* API。
 *
 * ★★ 集成架构说明（v3 — libretro 化重写）★★
 *
 * 旧版集成 dlopen 官方 libppsspp_jni.so 驱动其 JNI 契约（模拟
 * PpssppActivity + PspHostStub 桩），与 DC/PSX 等核心完全异构，产生：
 *   · PPSSPP 自带虚拟按键覆盖层（宿主触摸直通喂给 NativeApp.touch），
 *     与 NesStation 统一手柄并存 —— "虚拟按键还是它原版的"；
 *   · 画面方向/呈现由 PPSSPP 内部 Vulkan 循环决定 —— "画面方向乱七八糟"；
 *   · 设置走 ppsspp.ini 直写，与主设置面板割裂 —— "设置乱七八糟"；
 *   · JNI 契约脆弱（JNI_OnLoad / registerCallbacks / ContentUri 连环雷，
 *     两轮闪退修复的根源）。
 * 本版彻底重写为 libretro 前端（core/jni/psp_loader.cpp，与 dc_loader.cpp
 * 同构）：输入走标准 retro JOYPAD（NesStation 统一虚拟按键/摇杆直供，
 * 无任何 PPSSPP 覆盖层）；画面走 EGL/GLES3 FBO + blit（宿主全权管理
 * Surface，无方向问题）；设置走 ppsspp_* 核心选项（与主设置面板打通）；
 * org.ppsspp.ppsspp Java 包与 libppsspp_jni.so 已整体移除。
 *
 * 架构（拉模型 —— 与 DcEngine 一致）：
 *  1. [loadRom] 播种 PPSSPP 资产 + 应用核心选项 + retro_load_game（阻塞）；
 *  2. 模拟线程（本引擎拥有）按核心刷新率跑 [PspNative.runFrame]，原生侧
 *     每帧把 FBO blit 到 Surface 并 swap（EGL HW render，见 psp_loader.cpp）；
 *  3. 音频线程从原生环形缓冲拉 PCM 写 AudioTrack（核心 44.1k 直通）；
 *  4. 输入：NesStation 位掩码经 EmulatorScreen.routePadBits 的
 *     ps2ToLibretroLayout() 转换（PSP 面键与 PS2 同为 ×○□△ 语义）后直接
 *     推 libretro JOYPAD 位；虚拟摇杆 → libretro int16 轴。
 *
 * 即时存档：核心的 retro_serialize/retro_unserialize（完整 PSP RAM 状态，
 * 与 RetroArch 的 PPSSPP 槽位兼容）。
 */
class PspEngine private constructor() : EmulatorEngine, PspCoreEngine {

    /**
     * PSP frame buffer. 内部分辨率 480x272 (1x)..1920x1088 (4x)；
     * 常规 2x = 960x544。截图走原生按需 FBO 回读（动态尺寸）。
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

    /** Core-reported refresh rate; used for pacing (PSP: 59.94). */
    @Volatile private var _targetHz: Int = 60

    /** 前端帧数限制（Hz）。0 = 跟随核心刷新率不限速（语义同 DcEngine）。 */
    @Volatile private var _frameLimitHz: Int = 0

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

    @Volatile private var lastErrorText = ""

    override fun ensureLoaded(): Boolean = PspNative.ensureLoaded()

    // ------------------------------------------------------------------
    // 可用性
    // ------------------------------------------------------------------

    data class Availability(val available: Boolean, val reason: String?)

    @Volatile private var probedAvailability: Availability? = null

    fun probeAvailability(): Availability {
        probedAvailability?.let { return it }
        val result = try {
            if (PspNative.appContext == null) {
                appContext?.let { PspNative.appContext = it }
            }
            if (PspNative.ensureLoaded()) {
                Availability(true, null)
            } else {
                Availability(
                    false,
                    "libpspcore 加载失败（当前进程 ABI 无对应 libppsspp_libretro_android.so，或库缺失）"
                )
            }
        } catch (e: Throwable) {
            Availability(false, "PPSSPP 核心加载异常: ${e.message}")
        }
        probedAvailability = result
        return result
    }

    override fun isCoreAvailable(): Boolean = probeAvailability().available

    // ------------------------------------------------------------------
    // 核心选项：PspSettingsStore（设置面板持久化）→ ppsspp_* retro 变量
    // ------------------------------------------------------------------

    /**
     * 把设置面板的 PspSettingsStore 键（ppsspp.ini 风格 "Section/key"）
     * 映射为 libretro 核心的 ppsspp_* 选项值，在 retro_load_game 之前应用
     *（核心在加载游戏与每帧 GET_VARIABLE_UPDATE 时读取）。
     *
     * 无对应 libretro 选项的 ini 键（PostShader/Volume/ShowTouchControls 等
     * —— libretro 模式下由宿主渲染/宿主手柄接管）直接跳过，键值仍保留在
     * 设置存储里，UI 不变。
     */
    private fun applyStoredCoreOptions() {
        val ctx = appContext ?: PspNative.appContext ?: return
        try {
            // Graphics/InternalResolution：ini 0=auto（映射 2x 档），1..8 → 档位字符串
            val res = PspSettingsStore.readInt(ctx, PspSettingsStore.INTERNAL_RES, 0)
            val resValue = if (res <= 0) "960x544"
                           else "${480 * res}x${272 * res}"
            PspNative.setCoreOption("ppsspp_internal_resolution", resValue)

            // Graphics/FrameSkip：0..5 十进制字符串（libretro 直接喂 g_Config）
            PspNative.setCoreOption(
                "ppsspp_frameskip",
                PspSettingsStore.readInt(ctx, PspSettingsStore.FRAME_SKIP, 0).coerceIn(0, 10).toString()
            )
            // Graphics/AutoFrameSkip
            PspNative.setCoreOption(
                "ppsspp_auto_frameskip",
                if (PspSettingsStore.readBool(ctx, PspSettingsStore.AUTO_FRAME_SKIP, false)) "enabled" else "disabled"
            )
            // Graphics/TexScalingLevel：ini 0=auto,1=off,2..5 → libretro 同标度直写
            PspNative.setCoreOption(
                "ppsspp_texture_scaling_level",
                PspSettingsStore.readInt(ctx, PspSettingsStore.TEX_SCALE_LEVEL, 1).coerceIn(0, 5).toString()
            )
            // Graphics/TexScalingType：0=xBRZ,1=Hybrid,2=Bicubic,3=Hybrid+Bicubic
            PspNative.setCoreOption(
                "ppsspp_texture_scaling_type",
                when (PspSettingsStore.readInt(ctx, PspSettingsStore.TEX_SCALE_TYPE, 0)) {
                    1 -> "hybrid"; 2 -> "bicubic"; 3 -> "hybrid_bicubic"; else -> "xbrz"
                }
            )
            // Graphics/HardwareTransform
            PspNative.setCoreOption(
                "ppsspp_gpu_hardware_transform",
                if (PspSettingsStore.readBool(ctx, PspSettingsStore.HW_TRANSFORM, true)) "enabled" else "disabled"
            )
            // System/CPUCore：0=JIT,1=IR-JIT,2=解释器
            PspNative.setCoreOption(
                "ppsspp_cpu_core",
                when (PspSettingsStore.readInt(ctx, PspSettingsStore.CPU_CORE, 0)) {
                    1 -> "IR jit"; 2 -> "interpreter"; else -> "jit"
                }
            )
            // System/CPUSpeed：0=auto,222/266/333
            val cpuSpeed = PspSettingsStore.readInt(ctx, PspSettingsStore.CPU_SPEED, 0)
            PspNative.setCoreOption(
                "ppsspp_locked_cpu_speed",
                when (cpuSpeed) { 222 -> "222MHz"; 266 -> "266MHz"; 333 -> "333MHz"; else -> "off" }
            )
            // System/FastMemoryAccess
            PspNative.setCoreOption(
                "ppsspp_fast_memory",
                if (PspSettingsStore.readBool(ctx, PspSettingsStore.FAST_MEMORY, true)) "enabled" else "disabled"
            )
            // System/EnableCheats
            PspNative.setCoreOption(
                "ppsspp_cheats",
                if (PspSettingsStore.readBool(ctx, PspSettingsStore.ENABLE_CHEATS, false)) "enabled" else "disabled"
            )
            // IO/音频（无面板键 —— 用稳妥默认，键仍可经 setCoreOption 覆盖）
            PspNative.setCoreOption("ppsspp_io_timing_method", "Fast")
            PspNative.setCoreOption("ppsspp_audio_latency", "low")
            PspNative.setCoreOption("ppsspp_rendering_mode", "buffered")
        } catch (t: Throwable) {
            android.util.Log.w("PspEngine", "applyStoredCoreOptions failed", t)
        }
    }

    // ------------------------------------------------------------------
    // loadRom
    // ------------------------------------------------------------------

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!probeAvailability().available) {
            lastErrorText = probeAvailability().reason ?: "核心不可用"
            return false
        }

        cleanup()

        try {
            val ctx = appContext ?: PspNative.appContext

            // 1) 播种 PPSSPP 资产（<systemDir>/PPSSPP/{ppge_atlas.zim,
            //    compat.ini, flash0/font/*.pgf, lang/...} —— 核心的 VFS 根；
            //    缺失时游戏能跑但游戏内对话框/系统文字会异常）。
            if (ctx != null) {
                ensurePpssppEnvironment(ctx, systemDir)
            }

            // 2) 目录：system（资产根，核心自动追加 /PPSSPP）+ save（memstick 根）。
            PspNative.setPaths(systemDir, saveDir)

            // 3) 设置面板 → ppsspp_* 核心选项（load 前应用）。
            applyStoredCoreOptions()

            // 4) retro_load_game（.iso/.cso/.pbp/.chd 按路径流式读取）。
            if (!PspNative.loadRom(rom.absolutePath)) {
                lastErrorText = PspNative.lastError().ifBlank { "PSP 镜像加载失败" }
                return false
            }
            isLoaded = true

            PspNative.setFastForward(_ffSpeed)

            // Pace to the core's own refresh rate (PSP NTSC 59.94).
            val coreFps = PspNative.videoFps()
            _targetHz = if (coreFps > 10.0 && coreFps < 500.0) Math.round(coreFps).toInt() else 60

            // Default audio — open the AudioTrack at the core's own sample
            // rate (ppsspp outputs 44.1 kHz).
            val rate = PspNative.audioSampleRate().takeIf { it > 0 } ?: 44100
            startAudio(rate)

            running.set(true)
            thread = thread(name = "pspcore-loop", isDaemon = true) {
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
                                PspNative.setPad1(pads.first)
                                PspNative.setPad2(pads.second)
                            }
                        }

                        PspNative.runFrame()

                        if (npHook != null) {
                            npHook.afterFrame(_netFrame)
                            _netFrame++
                        }

                        if (!running.get()) break

                        // 无 Surface 时把核心帧拷进 CPU 侧 frameBuffer（与
                        // DcEngine 同模式 —— 仿电视机等无 Surface 显示路径靠
                        // 轮询 frameBuffer 拉帧；原生侧配套每帧 FBO 回读）。
                        if (!hasSurface) {
                            PspNative.getFrameBuffer(frameBuffer)
                        }

                        onFrame()

                        // Pacing — fast-forward, paced to the CORE refresh
                        // rate，叠加用户手动帧数上限（快进时豁免）。
                        val ff = _ffSpeed
                        var hz = if (ff > 0) _targetHz * ff else _targetHz
                        val limit = _frameLimitHz
                        if (limit > 0 && ff <= 0) hz = minOf(hz, limit)
                        val paced = FramePacer.pace(t0, hz.coerceIn(30, 600))
                        if (!paced) break
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("PspEngine", "Emulation thread crashed", t)
                }
            }
            lastErrorText = ""
            android.util.Log.i("PspEngine", "PSP ROM loaded (libretro): ${rom.absolutePath}")
            return true
        } catch (t: Throwable) {
            android.util.Log.w("PspEngine", "loadRom failed", t)
            lastErrorText = t.message ?: t.javaClass.simpleName
            return false
        }
    }

    // ------------------------------------------------------------------
    // Surface 生命周期
    // ------------------------------------------------------------------

    override fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        PspNative.setSurface(surface)
    }

    override fun setSaveName(name: String) {
        PspNative.setSaveName(name)
    }

    override fun setCoreOption(key: String, value: String) {
        // 设置面板的键（"Section/key" ini 风格）经 PspSettingsStore 落盘；
        // libretro 选项在下次 loadRom 时由 applyStoredCoreOptions 统一映射
        // 应用（retro 变量在会话中途对多数图形键同样可热更新 —— 经
        // setCoreOption 直通核心的 ppsspp_* 键原样转发）。
        if (key.startsWith("ppsspp_")) {
            PspNative.setCoreOption(key, value)
            return
        }
        val ctx = appContext ?: PspNative.appContext ?: return
        val dot = key.indexOf('/')
        if (dot <= 0 || dot >= key.length - 1) return
        try {
            PspSettingsStore.writeRaw(
                com.nesstation.app.ui.emulator.pspSettingsIniFile(ctx),
                PspSettingsStore.Key(key.substring(0, dot), key.substring(dot + 1)),
                value
            )
        } catch (t: Throwable) {
            android.util.Log.w("PspEngine", "setCoreOption($key) failed", t)
        }
    }

    override fun beginCoreOptionsBatch() {}
    override fun endCoreOptionsBatch() {}

    override fun videoWidth(): Int = if (isLoaded) PspNative.videoWidth() else 480
    override fun videoHeight(): Int = if (isLoaded) PspNative.videoHeight() else 272

    /**
     * FPS HUD 的真实帧率：按固定间隔轮询核心"真实提交帧数"计数器
     * （psp_loader 的 cb_video 非空调用时 +1），并用真实流逝时间换算。
     */
    override fun realtimeFps(): Double {
        if (!isLoaded) return 0.0
        return try {
            val frames = PspNative.pollPresentedFrames()
            val now = System.nanoTime()
            val last = _fpsPollNs
            _fpsPollNs = now
            if (frames <= 0 || last <= 0L) 0.0
            else frames * 1_000_000_000.0 / (now - last).coerceAtLeast(1)
        } catch (t: Throwable) { 0.0 }
    }

    override fun setVideoFilter(filter: Int) = PspNative.setVideoFilter(filter)
    override fun setHighQualityScaling(enabled: Boolean) = PspNative.setHighQualityScaling(enabled)

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (isLoaded) PspNative.setFastForward(speed)
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
        audioThread = thread(name = "psp-audio-loop", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = PspNative.readAudio(buf)
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
                android.util.Log.e("PspEngine", "Audio thread crashed", t)
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
        PspNative.reset(hard)
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
            try { PspNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }
        _paused = false
        _ffSpeed = 0
        hasSurface = false
        _targetHz = 60
        _frameLimitHz = 0
        _fpsPollNs = 0L
    }

    // ------------------------------------------------------------------
    // 输入（标准 libretro 位 —— EmulatorScreen.routePadBits 已做
    // ps2ToLibretroLayout 转换；此处直通原生）
    // ------------------------------------------------------------------

    override fun setPad1(bits: Int) = PspNative.setPad1(bits)
    override fun setPad2(bits: Int) = PspNative.setPad2(bits)
    fun setPad3(bits: Int) = PspNative.setPad3(bits)
    fun setPad4(bits: Int) = PspNative.setPad4(bits)

    /**
     * PSP 手柄模拟摇杆（端口 0）：归一化 -1..1（屏幕坐标约定，向上为负）
     * 转 libretro int16 轴（−32768..32767，0 居中）。虚拟摇杆
     * （OnScreenController 经 onAnalogAxes）与物理手柄轴由此推送；
     * ppsspp 每帧以 RETRO_DEVICE_ANALOG 轮询。PSP 无右摇杆，
     * (rx, ry) 透传（核心轮询但无绑定）。
     */
    override fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float) {
        PspNative.setAnalogAxes(
            0,
            (lx * 32767f).toInt().coerceIn(-32768, 32767),
            (ly * 32767f).toInt().coerceIn(-32768, 32767),
            (rx * 32767f).toInt().coerceIn(-32768, 32767),
            (ry * 32767f).toInt().coerceIn(-32768, 32767)
        )
    }

    /**
     * ★ 历史接口保留（EmulatorScreen 触摸直通调用点仍在）：libretro 化后
     * PPSSPP 自带虚拟按键覆盖层不复存在 —— PSP 的触摸输入由 NesStation
     * 统一虚拟按键/摇杆承担（routePadBits → setPad1 / setAnalogAxes），
     * 本方法退化为空操作。
     */
    override fun onTouch(x: Float, y: Float, androidAction: Int, pointerId: Int) {
        // no-op — PPSSPP overlay is gone in the libretro integration
    }

    override fun setRegion(region: Int) = PspNative.setRegion(region)
    override fun setSampleRate(rate: Int) = PspNative.setSampleRate(rate)
    override fun saveState(slot: Int, dst: File): Boolean = PspNative.saveState(slot, dst.absolutePath)
    override fun loadState(slot: Int, src: File): Boolean = PspNative.loadState(slot, src.absolutePath)

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        val w = videoWidth()
        val h = videoHeight()
        if (w <= 0 || h <= 0) return null
        // 截图走原生按需 FBO 回读（requestFrameCapture + 短暂等待），
        // 与 DcEngine 同模式 —— 核心的最终画面在 GPU FBO 里，UI 线程
        // 无法直接读取，必须由模拟线程回读。
        val buf = IntArray(w * h)
        PspNative.captureFrame(buf)
        return FrameCapture(buf, w, h)
    }

    override fun lastError(): String = lastErrorText.ifBlank { PspNative.lastError() }

    // ------------------------------------------------------------------
    // 单例
    // ------------------------------------------------------------------

    /** App context — set by NesApp.onCreate so the engine can locate dirs. */
    @Volatile var appContext: android.content.Context? = null

    companion object {
        @Volatile private var instance: PspEngine? = null
        fun get(): PspEngine = instance ?: synchronized(this) {
            instance ?: PspEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}

/**
 * PSP（PPSSPP libretro 核心）引擎扩展契约 —— EmulatorScreen 经此接口下发
 * 摇杆与触摸（与 DcCoreEngine / AzaharCoreEngine 同款模式）。
 */
interface PspCoreEngine : EmulatorEngine {

    /** libpspcore.so / libppsspp_libretro_android.so 是否可用。 */
    fun isCoreAvailable(): Boolean

    /** 双摇杆：左摇杆 (lx, ly)，右摇杆 (rx, ry)（PSP 无右摇杆，透传）。 */
    fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float)

    /**
     * 前端帧数限制（Hz）。0 = 跟随游戏制式（NTSC 59.94）不限速；
     * >0 时把模拟线程步进频率硬限制到该值（语义同 DcCoreEngine）。
     */
    fun setFrameLimit(hz: Int)

    /**
     * 原始触摸直通（历史接口 —— libretro 化后 PPSSPP 自带覆盖层已移除，
     * 实现为空操作；保留以兼容 EmulatorScreen 现有调用点）。
     * [androidAction] = MotionEvent.actionMasked。
     */
    fun onTouch(x: Float, y: Float, androidAction: Int, pointerId: Int)
}
