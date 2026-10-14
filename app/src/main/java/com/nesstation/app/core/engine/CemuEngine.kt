package com.nesstation.app.core.engine

import android.content.Context
import android.view.Surface
import com.nesstation.app.core.jni.CemuNative
import info.cemu.cemu.nativeinterface.NativeActiveSettings
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeGraphicPacks
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeQuickSave
import info.cemu.cemu.nativeinterface.NativeSettings
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Cemu（Wii U 模拟器核心）引擎 —— NesStation 集成。
 *
 * 上游：afeimod/Cemu-AZ（SSimco Android 移植 + afeimod 修改版）。源码树移植在
 * core/cemu/，经 :cemu-core Gradle 模块（vcpkg + CMake）从源码编译产出
 * libCemuAndroid.so —— 集成方式与 DraStic/Azahar/Ishiiruka 的"独立模拟器
 * 形态"一致，但核心库为源码构建（非预编译 so）。
 *
 * 架构（推模型 —— 引擎不拥有模拟循环，与上游 EmulationViewModel 同构）：
 *  1. [loadRom] 初始化 ActiveSettings 路径（用户目录 <filesDir>/cemu）+
 *     [NativeEmulation.initializeEmulation]（配置加载/目录创建/网络初始化），
 *     记录 ROM 路径；Surface 有效后（[setSurface]）由专用线程执行上游同款
 *     启动序列：prepareTitle → initializeSystems → initializeRenderer →
 *     setSurface(main) → initializeSurface(main) → launchTitle（阻塞至退出）；
 *  2. 暂停/恢复走 [NativeEmulation.pauseTitle]/[resumeTitle]，快进走
 *     [NativeSettings.setGameSpeed]（0.5x/1x/2x/4x/8x 档位）；
 *  3. 音频由核心内部播放（cubeb/AAX，推模型核心约定，引擎无 AudioTrack）；
 *  4. 渲染为 Vulkan 直绘主画布（TV 画面），GamePad 画面可经
 *     [toggleTvPadView] 切到主画布显示。
 *
 * 输入：
 *  - [setPad1] 的 NesStation 位布局 → VPAD 按钮（onOverlayButton(0, VPADButton.*)）
 *  - [setAnalogAxes] → 左/右摇杆四向轴（onOverlayAxis STICKL/R_UP/DOWN/LEFT/RIGHT）
 *  - [setTouchInput]/[setTouchMoved] → GamePad 触摸屏（onTouchDown/Move/Up，
 *    视图坐标换算到 DRC 854x480）
 *
 * 设置：`setCoreOption("CemuEngine/<name>", value)` 门面键直调 NativeSettings
 * （gameSpeed/vsyncMode/asyncShaderCompile/upscaleFilter/…，见 applyCoreOption）。
 *
 * ROM：.wud/.wux/.wua/.wuhb/.iso/.rpx/.elf（.wad 归 NGCWII 平台沿用）。
 * 存档：核心 NativeQuickSave 原生槽位（1..6，带缩略图），dst 参数仅作 UI
 * 标记（与 Azahar/PS2 引擎同做法）。
 */
class CemuEngine private constructor() : EmulatorEngine, CemuCoreEngine {

    // ------------------------------------------------------------------
    // EmulatorEngine 基础
    // ------------------------------------------------------------------

    /** 直绘核心无帧缓冲回读 —— 保留 API 兼容，[captureFrame] 返回 null。 */
    override val frameBuffer = IntArray(1)

    private val running = AtomicBoolean(false)

    @Volatile override var isLoaded = false
        private set

    @Volatile private var _paused = false
    @Volatile private var _ffSpeed = 0
    @Volatile private var lastErrorText = ""

    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    @Volatile private var romPath: String? = null
    @Volatile private var pendingBoot = false
    @Volatile private var bootStarted = false
    private var emuThread: Thread? = null
    private var heartbeatThread: Thread? = null

    /** App context — 由 NesApp.onCreate 注入（定位用户目录/资源）。 */
    @Volatile var appContext: Context? = null

    /**
     * 非用户请求的模拟退出回调（原生线程触发，宿主自行 post 主线程）。
     * prepareTitle 失败 / launchTitle 异常返回时触发 —— UI 呈现错误对话框
     * 而非永久黑屏（与 AzaharEngine.onPrematureExit 同语义）。
     */
    @Volatile var onPrematureExit: ((String) -> Unit)? = null

    /** 用户主动停止标记（true 时 launchTitle 返回不触发 [onPrematureExit]）。 */
    @Volatile private var userRequestedStop = false

    /** 主画布当前是否显示 GamePad 画面（TV/Pad 视图切换状态）。 */
    @Volatile private var padViewActive = false

    /** GamePad（DRC）逻辑分辨率 —— 上游 EmulationScreen 同款常量。 */
    private val drcWidth = 854
    private val drcHeight = 480

    // === Netplay（推模型核心不支持锁步 —— 接受但无效，与 PS2/3DS 引擎一致） ===
    @Volatile private var _frameHook: NetplayHook? = null
    @Volatile private var _netFrame = 0L
    override var frameHook: NetplayHook?
        get() = _frameHook
        set(value) {
            _frameHook = value
            _netFrame = 0L
        }

    private val lifecycleLock = Any()

    // ------------------------------------------------------------------
    // 可用性
    // ------------------------------------------------------------------

    data class Availability(val available: Boolean, val reason: String?)

    @Volatile private var probedAvailability: Availability? = null

    fun probeAvailability(): Availability {
        probedAvailability?.let { return it }
        val result = try {
            CemuNative.ensureLoaded()
            if (CemuNative.loaded) {
                Availability(true, null)
            } else {
                Availability(
                    false,
                    "libCemuAndroid 加载失败（当前进程非 arm64-v8a、API 低于 30，或模块未构建）\n" +
                        "Cemu 核心由 :cemu-core 模块从 core/cemu/ 源码构建，仅支持 arm64-v8a + " +
                        "Android 11(API 30)+。请先运行 scripts/fetch_cemu_submodules.sh 并执行 " +
                        "./gradlew assembleDebug -PabiFilter=arm64-v8a 完成构建。"
                )
            }
        } catch (e: Throwable) {
            Availability(false, "Cemu 核心加载异常: ${e.message}")
        }
        probedAvailability = result
        return result
    }

    override fun isCoreAvailable(): Boolean = probeAvailability().available

    override fun ensureLoaded(): Boolean = probeAvailability().available

    // ------------------------------------------------------------------
    // 目录 / 路径（<filesDir>/cemu 用户目录，data/ 持资源，graphicPacks/ 持图形包）
    // ------------------------------------------------------------------

    private fun userDir(): File {
        val ctx = appContext
            ?: throw IllegalStateException("CemuEngine: app context not initialised")
        return File(ctx.filesDir, "cemu").apply { mkdirs() }
    }

    private fun dataDir(): File = File(userDir(), "data").apply { mkdirs() }

    // ------------------------------------------------------------------
    // ROM 加载（推模型：记录路径 + 初始化核心全局，真正启动在 Surface 就绪后）
    // ------------------------------------------------------------------

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean {
        synchronized(lifecycleLock) {
            if (!ensureLoaded()) {
                lastErrorText = probeAvailability().reason ?: "Cemu 核心不可用"
                return false
            }
            // 上一局仍在跑 → 先卸载（EmulatorScreen 正常路径 unload 已先行；
            // 这里兜底防止重入）。
            if (running.get()) {
                unloadLocked()
            }
            val ctx = appContext
            if (ctx == null) {
                lastErrorText = "CemuEngine 未初始化（appContext 为空）"
                return false
            }
            try {
                val user = userDir().absolutePath
                // ★ 上游 CemuApplication.initializeCemu() 同款序列：
                //   路径 → nativeLibDir/internalDir → initializeEmulation → 刷新图形包
                NativeActiveSettings.initializeActiveSettings(
                    userDataPath = user,
                    configPath = user,
                    dataPath = dataDir().absolutePath,
                    cachePath = user,
                )
                NativeActiveSettings.setNativeLibDir(ctx.applicationInfo.nativeLibraryDir)
                NativeActiveSettings.setInternalDir(ctx.dataDir.absolutePath)
                NativeEmulation.setDPI(ctx.resources.displayMetrics.density)
                NativeEmulation.initializeEmulation()
                try {
                    NativeGraphicPacks.refreshGraphicPacks()
                } catch (t: Throwable) {
                    // 图形包刷新失败不阻塞启动（用户可后补图形包）
                    android.util.Log.w("CemuEngine", "refreshGraphicPacks failed", t)
                }
            } catch (t: Throwable) {
                lastErrorText = "Cemu 初始化失败: ${t.message ?: t.javaClass.simpleName}"
                android.util.Log.e("CemuEngine", "initializeEmulation failed", t)
                return false
            }

            romPath = rom.absolutePath
            isLoaded = true
            _paused = false
            _ffSpeed = 0
            userRequestedStop = false
            padViewActive = false
            pendingBoot = true
            bootStarted = false
            running.set(true)

            // HUD 心跳（推模型核心前端无模拟循环；16ms 节拍驱动 onFrame 计数 +
            // NetplayHook（若有）—— 与 AzaharEngine 同模式）
            heartbeatThread = thread(name = "cemu-hud-heartbeat", isDaemon = true) {
                try {
                    while (running.get()) {
                        _frameHook?.beforeFrame(_netFrame)?.let { (p1, p2) ->
                            setPad1(p1); setPad2(p2)
                        }
                        onFrame()
                        _netFrame++
                        try { Thread.sleep(16) } catch (_: InterruptedException) { break }
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("CemuEngine", "heartbeat crashed", t)
                }
            }

            // Surface 可能已就绪（EmulatorScreen 先挂 Surface 再 loadRom）——直接开跑
            surface?.let { startBootLocked() }
            return true
        }
    }

    /**
     * 上游 EmulationViewModel.initializeEmulation() 同款启动序列
     * （prepareTitle → initializeSystems → initializeRenderer →
     *  setSurface+initializeSurface(main) → launchTitle），在专用线程执行。
     * launchTitle 阻塞直至核心退出。
     */
    private fun startBootLocked() {
        val path = romPath ?: return
        if (bootStarted) return
        bootStarted = true
        pendingBoot = false
        emuThread = thread(name = "cemu-emu", isDaemon = true) {
            try {
                // --- prepareTitle（错误码语义与上游 PrepareTitleResult 一致）---
                val prepareResult = try {
                    NativeEmulation.prepareTitle(path)
                } catch (t: Throwable) {
                    android.util.Log.e("CemuEngine", "prepareTitle threw", t)
                    -1
                }
                when (prepareResult) {
                    NativeEmulation.PrepareTitleResult.SUCCESSFUL -> { /* 继续 */ }
                    NativeEmulation.PrepareTitleResult.ERROR_GAME_BASE_FILES_NOT_FOUND -> {
                        failBoot("游戏基础文件缺失：${path}\n（WUD/WUX 镜像不完整，或 RPX/ELF 无法独立启动）")
                        return@thread
                    }
                    NativeEmulation.PrepareTitleResult.ERROR_NO_DISC_KEY -> {
                        failBoot("缺少光盘密钥（game disc key）。\n请把 online files（otp.bin 等）放入 ${userDir().absolutePath}")
                        return@thread
                    }
                    NativeEmulation.PrepareTitleResult.ERROR_NO_TITLE_TIK -> {
                        failBoot("缺少 title.tik 票据。\n请把 online files（otp.bin 等）放入 ${userDir().absolutePath}")
                        return@thread
                    }
                    else -> {
                        failBoot("游戏准备失败（未知错误）：${path}")
                        return@thread
                    }
                }

                // --- initializeSystems（默认手柄 + 音频设备）---
                try {
                    NativeEmulation.initializeSystems()
                } catch (t: Throwable) {
                    android.util.Log.w("CemuEngine", "initializeSystems failed", t)
                }

                // --- initializeRenderer（Vulkan 全局 + VulkanRenderer）---
                NativeEmulation.initializeRenderer()

                // --- 主画布 surface（TV 画面）---
                val s = surface
                if (s != null && s.isValid) {
                    val w = if (surfaceWidth > 0) surfaceWidth else 1280
                    val h = if (surfaceHeight > 0) surfaceHeight else 720
                    NativeEmulation.setSurface(s, true)
                    NativeEmulation.setSurfaceSize(w, h, true)
                    NativeEmulation.initializeSurface(true)
                } else {
                    // 无 Surface 启动（TV 后台等极端场景）：核心仍可跑，
                    // Surface 到达后由 setSurface 重挂。
                    android.util.Log.w("CemuEngine", "boot without surface — will attach on setSurface")
                }

                // --- 启动（阻塞直至核心退出/用户停止）---
                try {
                    NativeEmulation.launchTitle()
                } catch (t: Throwable) {
                    android.util.Log.e("CemuEngine", "launchTitle crashed", t)
                    failBoot("模拟器运行异常退出：${t.message ?: t.javaClass.simpleName}")
                    return@thread
                }

                // 正常返回：用户请求停止 or 核心自行退出
                if (!userRequestedStop) {
                    val msg = lastErrorText.ifBlank { "核心已退出（未指定原因）" }
                    android.util.Log.w("CemuEngine", "core exited unexpectedly")
                    try { onPrematureExit?.invoke(msg) } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                android.util.Log.e("CemuEngine", "boot sequence failed", t)
                failBoot("启动失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /** 启动失败统一出口：记录错误 + 通知 UI（post 回调由宿主负责）。 */
    private fun failBoot(msg: String) {
        lastErrorText = msg
        running.set(false)
        try { onPrematureExit?.invoke(msg) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------
    // Surface 生命周期
    // ------------------------------------------------------------------

    override fun setSurface(surface: Surface?) {
        synchronized(lifecycleLock) {
            this.surface = surface
            if (surface == null) return
            // 已在运行 → 重挂 swapchain（Surface 重建场景，如切后台回来）
            if (bootStarted && !pendingBoot) {
                try {
                    NativeEmulation.setSurface(surface, true)
                    if (surfaceWidth > 0 && surfaceHeight > 0) {
                        NativeEmulation.setSurfaceSize(surfaceWidth, surfaceHeight, true)
                    }
                    NativeEmulation.initializeSurface(true)
                    if (_paused && !userRequestedStop) {
                        try { NativeEmulation.resumeTitle() } catch (_: Throwable) {}
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("CemuEngine", "re-attach surface failed", t)
                }
            } else if (pendingBoot) {
                // loadRom 已就绪、Surface 首次到达 → 开跑
                startBootLocked()
            }
        }
    }

    override fun onSurfaceChanged(surface: Surface?, width: Int, height: Int) {
        synchronized(lifecycleLock) {
            surfaceWidth = width
            surfaceHeight = height
            if (surface != null) this.surface = surface
            if (width <= 0 || height <= 0) return
            try {
                NativeEmulation.setSurfaceSize(width, height, true)
            } catch (_: Throwable) {
                // 核心未启动时 setSurfaceSize 无害失败（WindowSystem 尚未初始化）
            }
            // GamePad（DRC）逻辑尺寸固定 854x480 —— 触摸坐标换算基准
            try { NativeEmulation.setSurfaceSize(drcWidth, drcHeight, false) } catch (_: Throwable) {}
        }
    }

    override fun onSurfaceDestroyed() {
        synchronized(lifecycleLock) {
            // 上游 surfaceDestroyed 同款：主画布销毁 → 暂停（surface 重建后恢复）
            if (bootStarted && !pendingBoot && running.get()) {
                try { NativeEmulation.pauseTitle() } catch (_: Throwable) {}
                _paused = true
            }
        }
    }

    // ------------------------------------------------------------------
    // 存档名（UI 统一存档路径；Cemu 用原生槽位，见 saveState）
    // ------------------------------------------------------------------

    override fun setSaveName(name: String) {
        // Cemu 存档为原生槽位（QuickSave），无 .srm 概念 —— 保留 API 兼容
    }

    // ------------------------------------------------------------------
    // 设置（"CemuEngine/<name>" 门面键 → NativeSettings）
    // ------------------------------------------------------------------

    override fun setCoreOption(key: String, value: String) {
        try {
            val v = key.removePrefix("CemuEngine/")
            when (v) {
                "gameSpeed" -> NativeSettings.setGameSpeed(value.toIntOrNull() ?: 1)
                "vsyncMode" -> NativeSettings.setVsyncMode(value.toIntOrNull() ?: 0)
                "asyncShaderCompile" -> NativeSettings.setAsyncShaderCompile(value == "true" || value == "enabled")
                "accurateBarriers" -> NativeSettings.setAccurateBarriers(value == "true" || value == "enabled")
                "upscaleFilter" -> NativeSettings.setUpscalingFilter(value.toIntOrNull() ?: 0)
                "downscaleFilter" -> NativeSettings.setDownscalingFilter(value.toIntOrNull() ?: 0)
                "fullscreenScaling" -> NativeSettings.setFullscreenScaling(value.toIntOrNull() ?: 0)
                "tvVolume" -> NativeSettings.setAudioDeviceVolume((value.toIntOrNull() ?: 100).coerceIn(0, 100), true)
                "padVolume" -> NativeSettings.setAudioDeviceVolume((value.toIntOrNull() ?: 100).coerceIn(0, 100), false)
                "audioLatency" -> NativeSettings.setAudioLatency(value.toIntOrNull() ?: 60)
                "consoleLanguage" -> NativeSettings.setConsoleLanguage(value.toIntOrNull() ?: 1)
                "overlayFPS" -> NativeSettings.setOverlayFPSEnabled(value == "true" || value == "enabled")
                "padViewSwap" -> {
                    val want = value == "true" || value == "enabled"
                    if (want != padViewActive) toggleTvPadView()
                }
                else -> android.util.Log.w("CemuEngine", "unknown core option: $key")
            }
        } catch (t: Throwable) {
            android.util.Log.w("CemuEngine", "setCoreOption($key) failed", t)
        }
    }

    // ------------------------------------------------------------------
    // 视频（直绘核心：尺寸/滤镜由核心内部管理）
    // ------------------------------------------------------------------

    override fun videoWidth(): Int = 0

    override fun videoHeight(): Int = 0

    override fun setVideoFilter(filter: Int) {
        // 前端软件滤镜不适用（Vulkan 直绘）—— 忽略；上游等效能力为
        // upscaleFilter（缩放着色器）设置项。
    }

    override fun setHighQualityScaling(enabled: Boolean) {
        // Vulkan swapchain 直绘 —— 无前端逐像素缩放路径
    }

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        try {
            // 上游档位：0=0.5x 1=1x 2=2x 3=4x 4=8x
            val mode = when {
                speed <= 0 -> 1   // 关闭 → 常速
                speed >= 8 -> 4
                speed >= 4 -> 3
                else -> 2
            }
            NativeSettings.setGameSpeed(mode)
        } catch (t: Throwable) {
            android.util.Log.w("CemuEngine", "setFastForward failed", t)
        }
    }

    // ------------------------------------------------------------------
    // 暂停 / 恢复 / 重置 / 卸载
    // ------------------------------------------------------------------

    override fun setPaused(paused: Boolean) {
        _paused = paused
        try {
            if (paused) NativeEmulation.pauseTitle() else NativeEmulation.resumeTitle()
        } catch (t: Throwable) {
            android.util.Log.w("CemuEngine", "pause/resume failed", t)
        }
    }

    override fun reset(hard: Boolean) {
        // Cemu 无热重置 API —— 通过"停旧 → 起新"实现（EmulatorScreen 的
        // reset() 会跟随 loadRom 重入，此处只做停止标记）
        synchronized(lifecycleLock) {
            userRequestedStop = true
            try { NativeEmulation.shutdownTitle() } catch (_: Throwable) {}
        }
    }

    override fun unload() {
        synchronized(lifecycleLock) {
            unloadLocked()
        }
    }

    private fun unloadLocked() {
        userRequestedStop = true
        running.set(false)
        try { NativeEmulation.shutdownTitle() } catch (t: Throwable) {
            android.util.Log.w("CemuEngine", "shutdownTitle failed", t)
        }
        // join 模拟线程（上限 3s —— shutdownTitle 后 launchTitle 会返回）
        emuThread?.let { t ->
            try { t.join(3000) } catch (_: InterruptedException) {}
        }
        emuThread = null
        heartbeatThread?.let { t ->
            try { t.interrupt() } catch (_: Throwable) {} 
            try { t.join(500) } catch (_: InterruptedException) {}
        }
        heartbeatThread = null
        isLoaded = false
        pendingBoot = false
        bootStarted = false
        _paused = false
        romPath = null
    }

    override fun shutdown() {
        unload()
    }

    // ------------------------------------------------------------------
    // 输入：NesStation 位布局 → VPAD（GamePad，控制器槽位 0）
    // ------------------------------------------------------------------

    /** 上次推送的位状态（边沿检测：只发变化的按钮事件）。 */
    @Volatile private var lastPadBits = 0

    override fun setPad1(bits: Int) {
        val old = lastPadBits
        if (old == bits) return
        lastPadBits = bits
        // NesStation 位 → VPAD 按钮映射（位定义见 EmulatorScreen BTN_* 常量）
        // 每个位：变化时发送 onOverlayButton(0, VPADButton.X, state)
        fun edge(mask: Int, vpadId: Int) {
            val was = old and mask
            val now = bits and mask
            if (was == now) return
            try { NativeInput.onOverlayButton(0, vpadId, now != 0) } catch (_: Throwable) {}
        }
        edge(0x01, NativeInput.VPADButton.A)        // BTN_A
        edge(0x02, NativeInput.VPADButton.B)        // BTN_B
        edge(0x100, NativeInput.VPADButton.X)       // BTN_X
        edge(0x200, NativeInput.VPADButton.Y)       // BTN_Y
        edge(0x10, NativeInput.VPADButton.UP)       // BTN_UP
        edge(0x20, NativeInput.VPADButton.DOWN)     // BTN_DOWN
        edge(0x40, NativeInput.VPADButton.LEFT)     // BTN_LEFT
        edge(0x80, NativeInput.VPADButton.RIGHT)    // BTN_RIGHT
        edge(0x400, NativeInput.VPADButton.L)       // BTN_L_SNES(bit10)
        edge(0x800, NativeInput.VPADButton.R)       // BTN_R_SNES(bit11)
        edge(0x10000, NativeInput.VPADButton.ZL)    // BTN_ZL(bit16)
        edge(0x20000, NativeInput.VPADButton.ZR)    // BTN_ZR(bit17)
        edge(0x08, NativeInput.VPADButton.PLUS)     // BTN_START(bit8)
        edge(0x04, NativeInput.VPADButton.MINUS)    // BTN_SELECT(bit4)
        edge(0x80000, NativeInput.VPADButton.HOME)  // BTN_HOME(bit19)
        edge(0x4000, NativeInput.VPADButton.STICKL) // BTN_L3(bit14) 左摇杆按下
        edge(0x8000, NativeInput.VPADButton.STICKR) // BTN_R3(bit15) 右摇杆按下
        edge(BTN_WIIU_MIC, NativeInput.VPADButton.MIC) // bit30 GamePad 麦克风
        // TV/Pad 视图切换（BTN_WIIU_TV bit29）：上升沿切换一次
        if (bits and BTN_WIIU_TV != 0 && old and BTN_WIIU_TV == 0) {
            toggleTvPadView()
        }
    }

    override fun setPad2(bits: Int) {
        // 单 GamePad（VPAD0）输入 —— 忽略 2P（上游多 VPAD 需控制器配置 UI）
    }

    /** 双摇杆：屏幕坐标(-1..1, 上负) → VPAD 四向模拟轴。 */
    override fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float) {
        fun axis(id: Int, v: Float) {
            try { NativeInput.onOverlayAxis(0, id, v.coerceIn(0f, 1f)) } catch (_: Throwable) {}
        }
        axis(NativeInput.VPADButton.STICKL_UP, max(0f, -ly))
        axis(NativeInput.VPADButton.STICKL_DOWN, max(0f, ly))
        axis(NativeInput.VPADButton.STICKL_LEFT, max(0f, -lx))
        axis(NativeInput.VPADButton.STICKL_RIGHT, max(0f, lx))
        axis(NativeInput.VPADButton.STICKR_UP, max(0f, -ry))
        axis(NativeInput.VPADButton.STICKR_DOWN, max(0f, ry))
        axis(NativeInput.VPADButton.STICKR_LEFT, max(0f, -rx))
        axis(NativeInput.VPADButton.STICKR_RIGHT, max(0f, rx))
    }

    /**
     * GamePad 触摸（Wii U 唯一可触摸屏）。游戏视图像素坐标 → DRC 854x480：
     * 以当前视图尺寸（surfaceWidth/Height，TV 与 Pad 视图同为 16:9）等比缩放，
     * 两种视图下均可全屏操作 GamePad 触摸。
     */
    override fun setTouchInput(x: Float, y: Float, pressed: Boolean) {
        try {
            if (pressed) {
                val (dx, dy) = viewToDrc(x, y)
                NativeInput.onTouchDown(dx, dy, false)
            } else {
                NativeInput.onTouchUp(0, 0, false)
            }
        } catch (_: Throwable) {}
    }

    override fun setTouchMoved(x: Float, y: Float) {
        try {
            val (dx, dy) = viewToDrc(x, y)
            NativeInput.onTouchMove(dx, dy, false)
        } catch (_: Throwable) {}
    }

    private fun viewToDrc(x: Float, y: Float): Pair<Int, Int> {
        val vw = surfaceWidth.toFloat()
        val vh = surfaceHeight.toFloat()
        if (vw <= 0f || vh <= 0f) return x.toInt() to y.toInt()
        val dx = (x / vw * drcWidth).toInt().coerceIn(0, drcWidth - 1)
        val dy = (y / vh * drcHeight).toInt().coerceIn(0, drcHeight - 1)
        return dx to dy
    }

    /** 主画布 TV/GamePad 画面切换（上游 TAB 键语义）。 */
    override fun toggleTvPadView(): Boolean {
        padViewActive = !padViewActive
        try {
            NativeEmulation.setReplaceTVWithPadView(padViewActive)
        } catch (t: Throwable) {
            android.util.Log.w("CemuEngine", "setReplaceTVWithPadView failed", t)
        }
        return padViewActive
    }

    // ------------------------------------------------------------------
    // 区域 / 采样率（由核心配置管理 —— 忽略）
    // ------------------------------------------------------------------

    override fun setRegion(region: Int) {}

    override fun setSampleRate(rate: Int) {}

    // ------------------------------------------------------------------
    // 即时存档（NativeQuickSave 原生槽位）
    // ------------------------------------------------------------------

    override fun saveState(slot: Int, dst: File): Boolean {
        if (!isLoaded) return false
        return try {
            val r = NativeQuickSave.quickSaveSlot(slot)
            when (r) {
                NativeQuickSave.RESULT_OK, NativeQuickSave.RESULT_OK_RESTORED_IN_PLACE -> {
                    // dst 标记文件（UI 槽位列表/时间戳展示用；核心槽位在
                    // <userDir>/states，进程重启后仍可用）
                    try {
                        dst.parentFile?.mkdirs()
                        dst.writeText("cemu-quicksave-slot-$slot\n")
                    } catch (_: Throwable) {}
                    true
                }
                NativeQuickSave.RESULT_ERROR_NOT_RUNNING -> {
                    lastErrorText = "存档失败：核心未在运行"
                    false
                }
                NativeQuickSave.RESULT_ERROR_NO_SAVE_DATA -> {
                    lastErrorText = "存档失败：无存档数据（游戏尚未产生可快照状态）"
                    false
                }
                else -> {
                    lastErrorText = "存档失败（核心返回码 $r）"
                    false
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("CemuEngine", "saveState($slot)", t)
            false
        }
    }

    override fun loadState(slot: Int, src: File): Boolean {
        if (!isLoaded) return false
        return try {
            // 优先原生槽位；若 UI 标记文件存在但原生槽位为空（跨安装拷贝等），
            // 回落到 RESULT_ERROR_SLOT_EMPTY 报错提示
            val r = NativeQuickSave.quickRestoreSlot(slot, NativeQuickSave.RESTORE_FLAG_ALLOW_LEGACY)
            when (r) {
                NativeQuickSave.RESULT_OK, NativeQuickSave.RESULT_OK_RESTORED_IN_PLACE -> true
                NativeQuickSave.RESULT_ERROR_SLOT_EMPTY -> {
                    lastErrorText = "读档失败：槽位 $slot 为空"
                    false
                }
                NativeQuickSave.RESULT_ERROR_STATE_RESTORE_FAILED -> {
                    lastErrorText = "读档失败：状态恢复失败（版本不匹配或数据损坏）"
                    false
                }
                else -> {
                    lastErrorText = "读档失败（核心返回码 $r）"
                    false
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("CemuEngine", "loadState($slot)", t)
            false
        }
    }

    /** 槽位信息（带缩略图元数据）—— UI 快速存档面板可选消费。 */
    fun quickSaveInfo(): info.cemu.cemu.nativeinterface.NativeQuickSave.QuickSaveInfo? {
        if (!isLoaded) return null
        return try {
            NativeQuickSave.parseQuickSaveInfo(NativeQuickSave.getQuickSaveInfo())
        } catch (_: Throwable) { null }
    }

    // ------------------------------------------------------------------
    // 帧捕获 / FPS / 错误
    // ------------------------------------------------------------------

    override fun captureFrame(): FrameCapture? = null

    override fun realtimeFps(): Double {
        // 推模型核心：HUD 由心跳计数（onFrame 每 16ms 一次 ≈ 恒 60，不反映
        // 真实性能）—— 返回 0 让 UI 回退心跳计数，与 Azahar 引擎同策略。
        return 0.0
    }

    override fun lastError(): String = lastErrorText

    // ------------------------------------------------------------------
    // 单例
    // ------------------------------------------------------------------

    companion object {
        @Volatile private var instance: CemuEngine? = null

        fun get(): CemuEngine =
            instance ?: CemuEngine().also { instance = it }
    }
}

/** Wii U 专属位（EmulatorScreen 的 BTN_* 位布局扩展；见 setPad1 映射）。 */
internal const val BTN_WIIU_TV = 0x20000000     // bit29 — TV/GamePad 视图切换
internal const val BTN_WIIU_MIC = 0x40000000    // bit30 — GamePad 麦克风
