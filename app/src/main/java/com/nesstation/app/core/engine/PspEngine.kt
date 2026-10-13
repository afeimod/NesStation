package com.nesstation.app.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.PspNative
import com.nesstation.app.ui.emulator.PspCoreOptions
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
     * 把设置面板持久化的 libretro 核心选项（PspCoreOptions 目录 +
     * psp_core_options.ini 中用户改动/手工直通键）在 retro_load_game 之前
     * 整表灌入核心（核心在加载游戏与每帧 GET_VARIABLE_UPDATE 时读取）。
     *
     * 键/值与 PPSSPP 官方 libretro.cpp check_variables() 逐字节对齐
     * （大小写敏感，见 PspCoreOptions 头注释的历史教训）。
     */
    private fun applyStoredCoreOptions() {
        val ctx = appContext ?: PspNative.appContext ?: return
        try {
            // v9 一次性迁移：旧默认分辨率存储值 480x272 → 回落新默认 960x544
            PspSettingsStore.migrateInternalResolutionDefault(ctx)
            val stored = PspSettingsStore.readCoreOptions(ctx)
            // 1) 目录全量（存储值优先，缺省用目录默认；空值不下发）
            for ((key, value) in PspCoreOptions.effectiveValues(stored)) {
                PspNative.setCoreOption(key, value)
            }
            // 2) 目录之外的手工直通键（高级用户直接写进
            //    psp_core_options.ini 的 ppsspp_* 键）也一并下发
            val inCatalog = PspCoreOptions.CATALOG.map { it.key }.toHashSet()
            for ((key, value) in stored) {
                if (key.startsWith("ppsspp_") && key !in inCatalog && value.isNotEmpty()) {
                    PspNative.setCoreOption(key, value)
                }
            }
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
            //    v9 起同时把字体播种到 <saveDir>/PSP/flash0/font/（ms0 经典
            //    覆盖，优先级高于 NAND 固件字体与 VFS —— sceFont.cpp
            //    __LoadInternalFonts 查找链），保证汉化游戏 GBK 查字命中。
            if (ctx != null) {
                ensurePpssppEnvironment(ctx, systemDir, saveDir)
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
                            // ★ 存/读档兑底取件：暂停时不跑帧，但模拟线程仍在
                            // 循环 —— 每次把挂起的即时存档/读档操作取件执行
                            // （retro_serialize 必须在模拟线程上、retro_run 之外，
                            // 否则与核心内部模拟线程握手死锁。运行中时在
                            // stepFrame() 开头取件，暂停时在这里。）
                            try { PspNative.drainStateOp() } catch (_: Throwable) {}
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
        // libretro 核心选项（ppsspp_*）：持久化到 psp_core_options.ini +
        // PspNative.setCoreOption 直推（会话中改即时热更新 —— 核心经
        // GET_VARIABLE_UPDATE 每帧感知；下次启动由 applyStoredCoreOptions
        // 整表重放）。
        if (key.startsWith("ppsspp_")) {
            val ctx = appContext ?: PspNative.appContext
            if (ctx != null) {
                try {
                    PspSettingsStore.writeCoreOption(ctx, key, value)
                } catch (t: Throwable) {
                    android.util.Log.w("PspEngine", "persistCoreOption($key) failed", t)
                }
            }
            PspNative.setCoreOption(key, value)
            return
        }
        // 旧 ini 风格键（"Section/key"）—— 兼容保留：写入 ppsspp.ini。
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

    /**
     * 即时存档（v5 线程修复后）：请求投递到模拟线程、在两帧之间执行
     * retro_serialize。模拟线程已死（崩溃/未启动）时直接失败 —— 此时无
     * 人能在合法线程上完成 serialize，等待只会白屏 8s。
     */
    override fun saveState(slot: Int, dst: File): Boolean {
        if (!isLoaded) return false
        val t = thread
        if (t == null || !t.isAlive) {
            android.util.Log.w("PspEngine", "saveState: emu thread not alive")
            return false
        }
        return PspNative.saveState(slot, dst.absolutePath)
    }

    /** 即时读档：同 [saveState] 的模拟线程投递模型。 */
    override fun loadState(slot: Int, src: File): Boolean {
        if (!isLoaded) return false
        val t = thread
        if (t == null || !t.isAlive) {
            android.util.Log.w("PspEngine", "loadState: emu thread not alive")
            return false
        }
        return PspNative.loadState(slot, src.absolutePath)
    }

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
