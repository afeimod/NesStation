package com.nesstation.app.core.engine

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import com.nesstation.app.core.jni.CemuNative
import info.cemu.cemu.nativeinterface.NativeActiveSettings
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeGraphicPacks
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeQuickSave
import info.cemu.cemu.nativeinterface.NativeSettings
import info.cemu.cemu.nativeinterface.NativeSwkbd
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

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
 * 存档：核心 NativeQuickSave 原生槽位（1..6，带缩略图，语义与上游
 * QuickSaveSlotsDialog 完全一致：读档默认无 legacy 回退、安全模式重试、
 * legacy 恢复后进程内重启），dst 参数仅作 UI 标记。
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
    /** 基准游戏速度档（设置页“游戏速度”0..4，快进关闭时恢复到该档）。 */
    @Volatile private var _baseGameSpeed = 1
    /** 渲染分辨率缩放（0.25x..3x，1 = 原生）—— 经 setOutputRenderScale 下发到核心，
     *  渲染目标纹理按比例创建（真实内部渲染分辨率变化，填充率按 scale^2 变化，
     *  输出画面大小不变；GPU 线程帧边界应用并重建纹理缓存）。 */
    @Volatile private var _renderScale = 1f
    // ★ R5: GamePad 悬浮画面呈现节流间隔（ms）+ 帧率限制（见 setCoreOption）
    @Volatile private var _padPresentIntervalMs = 33
    @Volatile private var _frameLimit = 0

    /** R7 取消阴影渲染（阴影比较采样→常数受光，画面正常亮度） */
    @Volatile private var _shadowDisabled = false

    // ★ R11 CPU 核心调度：0 默认 / 1 大核优先 / 2 小核优先（省电）
    @Volatile private var _coreSchedPolicy = 0
    // ★ R11 CPU 模式：auto=不覆盖（Auto 推断·多核重编译）/ "3" 多核重编译 / "1" 单核重编译 / "0" 单核解释器
    @Volatile private var _cpuMode = "auto"
    // ★ R15 线程时间片（thread quantum）覆盖值：0 = 跟随游戏档案（上游默认 45000）
    @Volatile private var _threadQuantum = 0
    // ★ R11/R13 旧字段（R14 起映射进 _drawdoneMode）
    @Volatile private var _drawdoneFast = true
    // ★ R14 GX2DrawDone 三档模式：0 严格等待 / 1 滞后等待(one-behind·默认) / 2 纯跳过
    @Volatile private var _drawdoneMode = 1
    // ★ R14 滞后等待的滞后深度（1..4，默认 1）
    @Volatile private var _drawdoneLag = 1
    // ★ R12 ADPF 动态性能提示（默认开，API 31+ 生效；dlopen 绑定，老设备无感）
    @Volatile private var _adpfEnabled = true
    // ★ R12 帧生成（呈现侧光流插帧，实验性，默认关）
    @Volatile private var _frameGenEnabled = false
    // ★ R13 LSFG 3（Lossless.dll）配置缓存
    @Volatile private var _frameGenMultiplier = 2
    @Volatile private var _frameGenFlowScale = 0.5f
    @Volatile private var _frameGenPerf = true
    @Volatile private var _frameGenDllPath: String? = null

    /**
     * ★ R10 存/读档串行化锁 —— native 快速存档/读档会冻结全部模拟线程，
     * 并发进入（如退出时自动存档 + 手动读档、双入口同时触发）会竞态崩溃
     * （读档偶发闪退的高概率来源）。全部 native QuickSave 调用统一串行。
     */
    private val stateOpLock = Any()
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

    // === GamePad（DRC）悬浮画面 —— pad 画布 surface（上游 Cemu-AZ
    //     EmulationViewModel 同款护栏：pad swapchain 只能在 Vulkan renderer
    //     存在后创建，否则原生侧解引用空 g_renderer 直接崩溃） ===
    @Volatile private var padSurface: Surface? = null
    @Volatile private var padSurfaceWidth = 0
    @Volatile private var padSurfaceHeight = 0
    @Volatile private var rendererReady = false
    @Volatile private var padSwapchainInitialized = false

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
                // ★ SWKBD（游戏内软件键盘）回调注册 —— 上游 CemuApplication
                //   .initializeCemu() 同款序列。不调用则核心的文本输入请求
                //   （游戏内输入框弹键盘）永远无法回调到 Java 层 → 弹窗缺席。
                //   幂等（C++ 侧 static 守卫），重复 loadRom 安全。
                try {
                    NativeSwkbd.initializeSwkbd()
                } catch (t: Throwable) {
                    android.util.Log.w("CemuEngine", "initializeSwkbd failed", t)
                }
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
            // ★ R2 重开修复：复位核心侧 TAB 键态（TV/Pad 视图切换）。
            //   WindowSystem 键态是进程级全局，上一局开启过 GamePad 大画面
            //   （setReplaceTVWithPadView(true)）后关游戏不清理的话，下一局
            //   开局就会停留在 GamePad 画面（主画布显示错误内容）。
            try { NativeEmulation.setReplaceTVWithPadView(false) } catch (_: Throwable) {}
            padViewActive = false
            userRequestedStop = false
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
     * launchTitle 在核心内部分离线程上启动游戏并立即返回（上游
     * CafeSystem::LaunchForegroundTitle 语义）—— 正常返回不代表游戏结束。
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
                    // ★ 主画布恒为全尺寸 swapchain；渲染分辨率缩放经
                    //   setOutputRenderScale 下发（核心内渲染目标纹理按比例创建，
                    //   真实内部分辨率变化、输出画面大小不变），触摸坐标无需换算。
                    val w = if (surfaceWidth > 0) surfaceWidth else 1280
                    val h = if (surfaceHeight > 0) surfaceHeight else 720
                    NativeEmulation.setSurface(s, true)
                    NativeEmulation.setSurfaceSize(w, h, true)
                    try { NativeEmulation.setOutputRenderScale(_renderScale) } catch (_: Throwable) {}
                    try { NativeEmulation.setPadPresentInterval(_padPresentIntervalMs) } catch (_: Throwable) {}
                    try { NativeEmulation.setFrameLimit(_frameLimit) } catch (_: Throwable) {}
                    try { NativeEmulation.setShadowRenderingDisabled(_shadowDisabled) } catch (_: Throwable) {}
                    try { NativeEmulation.setCoreSchedulingPolicy(_coreSchedPolicy) } catch (_: Throwable) {}
                    try { NativeEmulation.setCpuModeOverride(if (_cpuMode == "auto") -1 else _cpuMode.toIntOrNull() ?: -1) } catch (_: Throwable) {}
                    // ★ R15: 启动/Surface 重挂重放线程时间片（值相同则原生侧幂等）
                    try { NativeEmulation.setThreadQuantumOverride(_threadQuantum) } catch (_: Throwable) {}
                    try { NativeEmulation.setDrawDoneMode(_drawdoneMode, _drawdoneLag) } catch (_: Throwable) {}
                    try { NativeEmulation.setAdpfEnabled(_adpfEnabled) } catch (_: Throwable) {}
                    // ★ R13: 帧生成 —— 先注入 DLL 与配置，再开开关（顺序关键）
                    val dllPath = _frameGenDllPath ?: seededFrameGenDllPath().ifEmpty { null }
                    if (dllPath != null) {
                        try { NativeEmulation.setFrameGenDll(dllPath) } catch (_: Throwable) {}
                    }
                    try { NativeEmulation.setFrameGenConfig(_frameGenMultiplier, _frameGenFlowScale, _frameGenPerf) } catch (_: Throwable) {}
                    try { NativeEmulation.setFrameGenEnabled(_frameGenEnabled) } catch (_: Throwable) {}
                    NativeEmulation.initializeSurface(true)
                } else {
                    // 无 Surface 启动（TV 后台等极端场景）：核心仍可跑，
                    // Surface 到达后由 setSurface 重挂。
                    android.util.Log.w("CemuEngine", "boot without surface — will attach on setSurface")
                }

                // --- renderer 就绪：此刻起创建 pad swapchain 安全（若悬浮
                //     GamePad 画面的 surface 已先到，在此补建；上游同款护栏）---
                synchronized(lifecycleLock) {
                    rendererReady = true
                    tryInitPadSurfaceLocked()
                }

                // --- 启动（上游 CafeSystem::LaunchForegroundTitle 在分离线程
                //     上跑游戏并立即返回 —— 参考源码 EmulationViewModel 对其
                //     正常返回不做任何错误处理，错误仅在 NativeException 抛出
                //     时呈现）---
                try {
                    NativeEmulation.launchTitle()
                } catch (t: Throwable) {
                    android.util.Log.e("CemuEngine", "launchTitle crashed", t)
                    failBoot("模拟器运行异常退出：${t.message ?: t.javaClass.simpleName}")
                    return@thread
                }
                // 正常返回 = 启动指令已受理（游戏在核心自己的分离线程上运行）。
                // ★ 不再把返回当作"核心已退出"误报 —— 修复"明明启动成功却弹
                //   核心启动失败弹窗"（上游 Cemu-AZ EmulationViewModel 同语义）。
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
                    // 重挂后确保核心侧渲染分辨率缩放仍在（待生效值不随 swapchain 重建丢失，
                    // 这里幂等重发一次，防御核心重启场景）
                    try { NativeEmulation.setOutputRenderScale(_renderScale) } catch (_: Throwable) {}
                    try { NativeEmulation.setPadPresentInterval(_padPresentIntervalMs) } catch (_: Throwable) {}
                    try { NativeEmulation.setFrameLimit(_frameLimit) } catch (_: Throwable) {}
                    try { NativeEmulation.setShadowRenderingDisabled(_shadowDisabled) } catch (_: Throwable) {}
                    try { NativeEmulation.setCoreSchedulingPolicy(_coreSchedPolicy) } catch (_: Throwable) {}
                    try { NativeEmulation.setCpuModeOverride(if (_cpuMode == "auto") -1 else _cpuMode.toIntOrNull() ?: -1) } catch (_: Throwable) {}
                    // ★ R15: 启动/Surface 重挂重放线程时间片（值相同则原生侧幂等）
                    try { NativeEmulation.setThreadQuantumOverride(_threadQuantum) } catch (_: Throwable) {}
                    try { NativeEmulation.setDrawDoneMode(_drawdoneMode, _drawdoneLag) } catch (_: Throwable) {}
                    try { NativeEmulation.setAdpfEnabled(_adpfEnabled) } catch (_: Throwable) {}
                    // ★ R13: 帧生成重挂恢复（DLL/配置/开关）
                    val dllPath2 = _frameGenDllPath ?: seededFrameGenDllPath().ifEmpty { null }
                    if (dllPath2 != null) {
                        try { NativeEmulation.setFrameGenDll(dllPath2) } catch (_: Throwable) {}
                    }
                    try { NativeEmulation.setFrameGenConfig(_frameGenMultiplier, _frameGenFlowScale, _frameGenPerf) } catch (_: Throwable) {}
                    try { NativeEmulation.setFrameGenEnabled(_frameGenEnabled) } catch (_: Throwable) {}
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
            // 尾部显式 Unit：切断 synchronized 泛型 R 对块值的推断依赖，
            // 使上方 if 链 / try 块 / 嵌套 if 均处于语句位置（无 else 的 if
            // 在语句位置完全合法，作表达式才要求双分支）。
            Unit
        }
    }

    override fun onSurfaceChanged(surface: Surface?, width: Int, height: Int) {
        synchronized(lifecycleLock) {
            surfaceWidth = width
            surfaceHeight = height
            if (surface != null) this.surface = surface
            if (width <= 0 || height <= 0) return
            try {
                // ★ 全尺寸下发（swapchain 热重建由核心自动完成）；渲染分辨率
                //   缩放在核心内部离屏目标上生效，与 surface 尺寸解耦
                NativeEmulation.setSurfaceSize(surfaceWidth, surfaceHeight, true)
            } catch (_: Throwable) {
                // 核心未启动时 setSurfaceSize 无害失败（WindowSystem 尚未初始化）
            }
            // GamePad（DRC）pad 画布尺寸：悬浮小窗存在时由 setPadSurface
            // 提供真实视图尺寸（swapchain 按实际像素建）；无悬浮小窗时
            // 保持 DRC 逻辑分辨率 854x480（触摸坐标换算基准，无害）。
            if (padSurface == null) {
                try { NativeEmulation.setSurfaceSize(drcWidth, drcHeight, false) } catch (_: Throwable) {}
            }
        }
    }

    override fun onSurfaceDestroyed() {
        synchronized(lifecycleLock) {
            // 上游 surfaceDestroyed 同款：主画布销毁 → 暂停（surface 重建后恢复）
            if (bootStarted && !pendingBoot && running.get()) {
                try { NativeEmulation.pauseTitle() } catch (_: Throwable) {}
                _paused = true
            }
            // 同 setSurface：尾部 Unit 使无 else 的 if 处于语句位置。
            Unit
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
                "gameSpeed" -> {
                    // ★ 快进联动修复：游戏内快捷菜单的快进按钮与设置页“游戏速度”
                    //   共用核心同一条 gameSpeed 通道（ActiveSettings timer shift +
                    //   LatteTiming 模拟 vsync 频率缩放）。快进激活期间这里只把值
                    //   记为“基准档”，不下发 —— 否则 applyCoreOptions 重放（改任意
                    //   其它设置/布局变更）会把快进静默踩回常速，表现为“快进按钮
                    //   按了没反应/自动失效”。快进关闭时恢复到该基准档。
                    val mode = (value.toIntOrNull() ?: 1).coerceIn(0, 4)
                    _baseGameSpeed = mode
                    if (_ffSpeed <= 0) {
                        try { NativeSettings.setGameSpeed(mode) } catch (t: Throwable) {
                            android.util.Log.w("CemuEngine", "setGameSpeed failed", t)
                        }
                    }
                }
                "vsyncMode" -> NativeSettings.setVsyncMode(value.toIntOrNull() ?: 0)
                "asyncShaderCompile" -> NativeSettings.setAsyncShaderCompile(value == "true" || value == "enabled")
                "accurateBarriers" -> NativeSettings.setAccurateBarriers(value == "true" || value == "enabled")
                "upscaleFilter" -> NativeSettings.setUpscalingFilter(value.toIntOrNull() ?: 0)
                "fullscreenScaling" -> NativeSettings.setFullscreenScaling(value.toIntOrNull() ?: 0)
                "tvVolume" -> NativeSettings.setAudioDeviceVolume((value.toIntOrNull() ?: 100).coerceIn(0, 100), true)
                "padVolume" -> NativeSettings.setAudioDeviceVolume((value.toIntOrNull() ?: 100).coerceIn(0, 100), false)
                "audioLatency" -> NativeSettings.setAudioLatency(value.toIntOrNull() ?: 60)
                "consoleLanguage" -> NativeSettings.setConsoleLanguage(value.toIntOrNull() ?: 1)
                "overlayFPS" -> NativeSettings.setOverlayFPSEnabled(value == "true" || value == "enabled")
                // ★ 下采样滤镜（上游 NativeSettings.ScalingFilter 同款取值）
                "downscaleFilter" -> NativeSettings.setDownscalingFilter(value.toIntOrNull() ?: 0)
                // ★ 渲染分辨率缩放（0.25x..3x）：R3 起为真实内部分辨率缩放 ——
                //   surface 恒为全尺寸，比例下发到核心后由渲染目标纹理按比例
                //   创建实现（等效自动 [TextureRedefine] 规则），视口/剪裁/拷贝
                //   自动联动，输出画面大小不变；GPU 线程帧边界热生效。
                "renderScale" -> {
                    val newScale = (value.toFloatOrNull() ?: 1f).coerceIn(0.25f, 3f)
                    if (newScale != _renderScale) {
                        _renderScale = newScale
                        applyRenderScaleLocked()
                    }
                }
                // ★ R5 GamePad 悬浮画面更新频率（省电）：纯呈现侧节流 ——
                //   "1" 每帧 / "2" 每2帧(约30fps·默认) / "3" 每3帧(约20fps)。
                //   不触碰游戏渲染管线；被跳过的帧由合成器保持上一帧画面。
                "padPresentRate" -> {
                    val newMs = when (value) {
                        "2" -> 33
                        "3" -> 50
                        else -> 0
                    }
                    if (newMs != _padPresentIntervalMs) {
                        _padPresentIntervalMs = newMs
                        try { NativeEmulation.setPadPresentInterval(newMs) } catch (_: Throwable) {}
                    }
                }
                // ★ R4 帧率限制（省电）：复用核心自定义 vsync 频率通道 ——
                //   翻转锁步游戏按目标帧率节流，GPU/CPU/屏幕功耗随帧率线性
                //   下降（30fps ≈ 一半占用）。与快进倍率叠加生效。
                "frameLimit" -> {
                    val newLimit = when (value) {
                        "30" -> 30
                        "45" -> 45
                        "60" -> 60
                        else -> 0
                    }
                    if (newLimit != _frameLimit) {
                        _frameLimit = newLimit
                        try { NativeEmulation.setFrameLimit(newLimit) } catch (_: Throwable) {}
                    }
                }
                // ★★★ R7 取消阴影渲染：着色器反编译层把全部阴影比较
                //   采样（SAMPLE_C 系列）替换为“完全受光”常数（1.0）
                //   —— 阴影消失、画面亮度正常（不是变黑）。非原生
                //   分辨率下阴影相关渲染异常（发黑）时的替代画面，
                //   也可用于纯性能场景。状态并入 auxHash，运行中
                //   热切换（受影响着色器自动重编）。
                "disableShadows" -> {
                    val newState = value == "true" || value == "enabled"
                    if (newState != _shadowDisabled) {
                        _shadowDisabled = newState
                        try { NativeEmulation.setShadowRenderingDisabled(newState) } catch (_: Throwable) {}
                    }
                }
                // ★★★ R11 CPU 核心调度（参考「风之杖HD 重编译版」核心调度）：
                //   3 个调度主机线程 + GPU 渲染线程按策略绑定大/小核簇，
                //   运行中热生效（JNI 立即重绑全部已注册线程）。
                "coreScheduling" -> {
                    val newPolicy = value.toIntOrNull()?.coerceIn(0, 2) ?: 0
                    if (newPolicy != _coreSchedPolicy) {
                        _coreSchedPolicy = newPolicy
                        try { NativeEmulation.setCoreSchedulingPolicy(newPolicy) } catch (_: Throwable) {}
                    }
                }
                // ★★★ R11 CPU 模式（核心调用重编译配置）：多核重编译最快，
                //   单核解释器最兼容。核心在启动期读取，切换后重启游戏完全生效。
                "cpuMode" -> {
                    val newMode = value.trim()
                    if (newMode != _cpuMode) {
                        _cpuMode = newMode
                        val nativeMode = when (newMode) {
                            "3" -> 3   // 多核重编译
                            "1" -> 1   // 单核重编译
                            "0" -> 0   // 单核解释器
                            else -> -1 // auto（不覆盖，走 Auto 推断）
                        }
                        try { NativeEmulation.setCpuModeOverride(nativeMode) } catch (_: Throwable) {}
                    }
                }
                // ★★★ R15 线程时间片（thread quantum）：上游 GameProfile::threadQuantum
                //   同款语义 —— 每个调度时间片授予的 PPC 周期数（上游默认 45000，可选
                //   20000/45000/60000/80000/100000）。值越大线程切换越少（移动端提速
                //   关键）；0 = 跟随游戏档案。调度器每个时间片读取生效值 ——
                //   运行中切换即时热生效，无需重启游戏。
                "threadQuantum" -> {
                    val q = value.toIntOrNull()?.coerceIn(0, 536870912) ?: 0
                    if (q != _threadQuantum) {
                        _threadQuantum = q
                        try { NativeEmulation.setThreadQuantumOverride(q) } catch (_: Throwable) {}
                    }
                }
                // ★★★ R11 GX2DrawDone 快速模式：只 flush 不等待时间戳退休，
                //   游戏逻辑线程不再被 DrawDone 阻塞（实验性，个别游戏可能数据不同步）。
                "drawdoneFast" -> {
                    // R14：旧布尔 key 映射到三档模式（true→1 滞后等待 / false→0 严格）
                    val newState = value == "true" || value == "enabled"
                    val newMode = if (newState) 1 else 0
                    if (newState != _drawdoneFast) {
                        _drawdoneFast = newState
                        _drawdoneMode = newMode
                        try { NativeEmulation.setDrawDoneMode(newMode, _drawdoneLag) } catch (_: Throwable) {}
                    }
                }
                // ★★★ R14 GX2DrawDone 三档模式（同参考 APK set_drawdone_mode）：
                //   "0" 严格等待（最兼容） | "1" 滞后等待 one-behind（默认·帧率稳提速）
                //   | "2" 纯跳过（最快·安全阀保底）。运行中热切换。
                "drawdoneMode" -> {
                    val m = (value.toIntOrNull() ?: 1).coerceIn(0, 2)
                    if (m != _drawdoneMode) {
                        _drawdoneMode = m
                        try { NativeEmulation.setDrawDoneMode(m, _drawdoneLag) } catch (_: Throwable) {}
                    }
                }
                // ★★★ R14 滞后等待的滞后深度（1..4）：越大 CPU/GPU 重叠越多、
                //   输入延迟越高。默认 1（= 参考项目 one-behind 档）。
                "drawdoneLag" -> {
                    val l = (value.toIntOrNull() ?: 1).coerceIn(1, 4)
                    if (l != _drawdoneLag) {
                        _drawdoneLag = l
                        try { NativeEmulation.setDrawDoneMode(_drawdoneMode, l) } catch (_: Throwable) {}
                    }
                }
                // ★★★ R12 ADPF 动态性能提示（参考「风之杖HD 重编译版」ADPF 语义）：
                //   游戏运行期间向系统调度器上报帧工作时长，抑制温控/调度降频抖动。
                //   API 31+ 生效，老设备静默无感。运行中热切换。
                "adpfEnabled" -> {
                    val newState = value == "true" || value == "enabled"
                    if (newState != _adpfEnabled) {
                        _adpfEnabled = newState
                        try { NativeEmulation.setAdpfEnabled(newState) } catch (_: Throwable) {}
                    }
                }
                // ★★★ R12 帧生成（R13 起为 LSFG 3.1 完整移植 —— Lossless.dll
                //   数据容器 + 神经网络光流插帧）：两个真实帧之间 GPU 插值
                //   合成帧，30fps 游戏在 60Hz 屏上获得逐帧连续观感。需要
                //   Lossless.dll（设置页导入或 APK assets 内置）；R14 起垂直
                //   同步=关闭时自动升级 FIFO 节拍（呈现侧）。运行中热切换。
                "frameGenEnabled" -> {
                    val newState = value == "true" || value == "enabled"
                    if (newState != _frameGenEnabled) {
                        _frameGenEnabled = newState
                        try { NativeEmulation.setFrameGenEnabled(newState) } catch (_: Throwable) {}
                    }
                }
                // ★★★ R13 帧生成倍率（2/3/4）
                "frameGenMultiplier" -> {
                    val m = (value.toIntOrNull() ?: 2).coerceIn(2, 4)
                    if (m != _frameGenMultiplier) {
                        _frameGenMultiplier = m
                        applyFrameGenConfig()
                    }
                }
                // ★★★ R13 帧生成光流估计比例（0.25/0.5/0.75/1.0）
                "frameGenFlowScale" -> {
                    val f = (value.toFloatOrNull() ?: 0.5f).coerceIn(0.25f, 1f)
                    if (f != _frameGenFlowScale) {
                        _frameGenFlowScale = f
                        applyFrameGenConfig()
                    }
                }
                // ★★★ R13 帧生成质量变体（performance/quality）
                "frameGenQuality" -> {
                    val perf = value != "quality"
                    if (perf != _frameGenPerf) {
                        _frameGenPerf = perf
                        applyFrameGenConfig()
                    }
                }
                // ★★★ R13 Lossless.dll 路径（空 = 回退 assets 播种路径）
                "frameGenDll" -> {
                    var p = value.trim()
                    if (p.isEmpty()) p = seededFrameGenDllPath()
                    if (p != _frameGenDllPath) {
                        _frameGenDllPath = p
                        try { NativeEmulation.setFrameGenDll(p) } catch (_: Throwable) {}
                    }
                }
                // ★ 音频设备开关/声道（TV / GamePad 独立）
                "tvAudioEnabled" -> NativeSettings.setAudioDeviceEnabled(value == "true" || value == "enabled", true)
                "padAudioEnabled" -> NativeSettings.setAudioDeviceEnabled(value == "true" || value == "enabled", false)
                "tvChannels" -> NativeSettings.setAudioDeviceChannels(value.toIntOrNull() ?: 1, true)
                "padChannels" -> NativeSettings.setAudioDeviceChannels(value.toIntOrNull() ?: 1, false)
                // ★ 核心内覆盖层（LatteOverlay：位置/文字缩放/绘制调用/CPU/RAM）
                "overlayPos" -> NativeSettings.setOverlayPosition(value.toIntOrNull() ?: 0)
                "overlayTextScale" -> NativeSettings.setOverlayTextScalePercentage((value.toIntOrNull() ?: 100).coerceIn(50, 300))
                "overlayDrawCalls" -> NativeSettings.setOverlayDrawCallsPerFrameEnabled(value == "true" || value == "enabled")
                "overlayCPU" -> NativeSettings.setOverlayCPUUsageEnabled(value == "true" || value == "enabled")
                "overlayRAM" -> NativeSettings.setOverlayRAMUsageEnabled(value == "true" || value == "enabled")
                // ★ 账号网络服务（离线/Nintendo/Pretendo/自定义 —— 写入当前活跃账号）
                "networkService" -> {
                    val v = value.toIntOrNull() ?: 0
                    try {
                        NativeSettings.setAccountNetworkService(NativeSettings.getAccountPersistentId(), v)
                    } catch (t: Throwable) {
                        android.util.Log.w("CemuEngine", "setAccountNetworkService failed", t)
                    }
                }
                // ★ 模拟 USB 设备（Skylanders 传送门 / Infinity Base / Dimensions Toypad）
                "skylanderPortal" -> NativeSettings.setEmulateSkylanderPortalEnabled(value == "true" || value == "enabled")
                "infinityBase" -> NativeSettings.setEmulateInfinityBaseEnabled(value == "true" || value == "enabled")
                "dimensionsToypad" -> NativeSettings.setEmulateDimensionsToypadEnabled(value == "true" || value == "enabled")
                // ★ 悬浮 GamePad 画面不透明度（核心输出着色器逐像素预乘 alpha；
                //   上游 PadOverlaySurface 同款链路）
                "padOverlayOpacity" -> NativeSettings.setPadOverlayOpacity((value.toIntOrNull() ?: 100).coerceIn(0, 100))
                // ★ 自定义 Vulkan 驱动路径（"驱动选择无法保存"根治）：
                //   主面板选驱动时核心多未初始化 —— settings.xml 文件名尚未
                //   设置（NativeEmulation.initializeEmulation 才 SetFilename），
                //   Save() 静默失败；且 Android 端从不回读 settings.xml。
                //   → PadLayout 的 cwDriverPath 是唯一可靠持久层，每次游戏
                //   启动经 applyCoreOptions 下发到核心配置 + saveSettings 落盘
                //   （此时文件名已就绪，保存成功），渲染器初始化时 adrenotools
                //   读取生效。仅在值变化时落盘，避免滑条类设置 60Hz 重入。
                "customDriverPath" -> {
                    val want = value.takeIf { it.isNotBlank() }
                    val cur = try {
                        NativeSettings.getCustomDriverPath()
                    } catch (_: Throwable) {
                        "\u0000unset" // 探测失败 → 视为不同值，走一次完整写入
                    }
                    if (cur != want) {
                        NativeSettings.setCustomDriverPath(want)
                        NativeSettings.saveSettings()
                    }
                }
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

    /**
     * ★ R13: LSFG 帧生成配置下发（倍率/光流比例/质量变体 —— 任一变化时调用）。
     */
    private fun applyFrameGenConfig() {
        try {
            NativeEmulation.setFrameGenConfig(_frameGenMultiplier, _frameGenFlowScale, _frameGenPerf)
        } catch (_: Throwable) {}
    }

    /**
     * ★ R13: assets 内置 Lossless.dll 的播种路径（NesApp 启动时把
     * assets/cemu/lsfg/Lossless.dll 复制到 <filesDir>/cemu/lsfg/）。
     * 仅当用户未在设置页导入自己的 DLL 时作为回退使用。
     */
    private fun seededFrameGenDllPath(): String {
        val ctx = appContext ?: return ""
        val f = java.io.File(ctx.filesDir, "cemu/lsfg/Lossless.dll")
        return if (f.isFile) f.absolutePath else ""
    }

    /**
     * 渲染分辨率切换后立即下发到核心（LatteTexture 待生效原子值，GPU 线程
     * 帧边界应用：生效时重建纹理缓存，下一次绘制按新比例重建渲染目标）。
     * 核心未启动时下发同样无害。
     */
    private fun applyRenderScaleLocked() {
        try {
            NativeEmulation.setOutputRenderScale(_renderScale)
        } catch (t: Throwable) {
            android.util.Log.w("CemuEngine", "applyRenderScale failed", t)
        }
    }

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
            // ★ 游戏内快捷菜单快进按钮 → 核心变速，运行中即时生效。
            //   核心档位：0=0.5x 1=1x 2=2x 3=4x 4=8x（NativeSettings.setGameSpeed 同款）。
            //   关闭 → 恢复设置页“游戏速度”基准档（不再硬编码 1x）；
            //   2x/4x/8x 直映射；6x/16x 等前端档位取不超过它的最近核心档。
            val mode = when {
                speed <= 0 -> _baseGameSpeed   // 关闭 → 基准速度
                speed >= 8 -> 4                // 8x
                speed >= 4 -> 3                // 4x
                else -> 2                      // 2x
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
        // GamePad 悬浮画面状态复位（下一局 loadRom 重新拉起）
        rendererReady = false
        padSwapchainInitialized = false
        padSurface = null
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
     * GamePad 触摸（Wii U 唯一可触摸屏）—— ★完全对齐上游 Cemu-AZ 语义★。
     *
     * 上游 CanvasOnTouchListener(isTV=true)：主画布触点【原始视图像素坐标】
     * 原样透传 NativeInput.onTouchDown(x, y, true)（写入 m_main_mouse）；
     * 坐标归一化完全由核心侧 VPADController::update_touch 负责：
     * LatteRenderTarget_getScreenImageArea 计算主窗口内【实际渲染图像矩形】
     * （fullscreenScaling=kKeepAspectRatio 时保持宽高比的信箱区域、拉伸时
     * 全窗口），(touch - imagePos) / imageSize → 0..1 → VPAD 触摸范围
     * （x*3883+92 / 4095-y*3694-254）。
     *
     * 旧实现把触点预先线性映射到 DRC 854x480 再传 isTV=false —— 坐标被
     * 核心按【悬浮 pad 窗口】的物理尺寸重新归一化，画面信箱化/拉伸/
     * 宽高比不匹配时偏差被放大（“画面拉伸或者不对的时候触摸的位置
     * 没有变，导致差距很大”）。现在按参考源码改为原始坐标 + isTV=true：
     * TV 画面、padViewActive 切换后的 GamePad 画面（主画布上）均由核心
     * 用主窗口图像矩形自动补偿，两种视图下都可全屏操作 GamePad 触摸。
     */
    override fun setTouchInput(x: Float, y: Float, pressed: Boolean) {
        try {
            if (pressed) {
                // ★ R2: 核心按全尺寸窗口图像矩形归一化（LatteRenderTarget_getScreenImageArea），
                //   surface 不再缩放 —— 原始视图像素坐标直接透传。
                NativeInput.onTouchDown(x.toInt(), y.toInt(), true)
            } else {
                NativeInput.onTouchUp(0, 0, true)
            }
        } catch (_: Throwable) {}
    }

    override fun setTouchMoved(x: Float, y: Float) {
        try {
            NativeInput.onTouchMove(x.toInt(), y.toInt(), true)
        } catch (_: Throwable) {}
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
    // GamePad（DRC）悬浮画面 —— pad 画布 surface 生命周期
    //（上游 EmulationViewModel.CanvasSurfaceHolderCallback(false) 同构）
    // ------------------------------------------------------------------

    /**
     * 挂载/更新 GamePad 悬浮画面 surface。renderer 就绪前仅记录，
     * 由启动线程 initializeRenderer 后补建（护栏与上游一致）。
     * 尺寸变化（用户调节小窗大小/圆角）热更新 —— 与上游
     * CanvasSurfaceHolderCallback.surfaceChanged 同序：先 setSurfaceSize，
     * 未初始化过才 setSurface + initializeSurface。
     */
    override fun setPadSurface(surface: Surface?, width: Int, height: Int) {
        synchronized(lifecycleLock) {
            padSurface = surface
            if (width > 0) padSurfaceWidth = width
            if (height > 0) padSurfaceHeight = height
            if (surface != null) {
                if (padSwapchainInitialized) {
                    // 已建 swapchain：仅热更新尺寸（上游 surfaceChanged 首行行为）
                    try {
                        NativeEmulation.setSurfaceSize(padSurfaceWidth, padSurfaceHeight, false)
                    } catch (_: Throwable) {}
                } else {
                    tryInitPadSurfaceLocked()
                }
            } else {
                // 上游 surfaceDestroyed → clearPadSurface
                clearPadSurfaceLocked()
            }
        }
    }

    /** 拆下 GamePad 悬浮画面 surface（StopUsingPadAndWait + pad_open=false）。 */
    override fun clearPadSurface() {
        synchronized(lifecycleLock) {
            clearPadSurfaceLocked()
        }
    }

    private fun clearPadSurfaceLocked() {
        try {
            NativeEmulation.clearPadSurface()
        } catch (t: Throwable) {
            // renderer 尚未创建时无害（上游同样容忍）
            android.util.Log.w("CemuEngine", "clearPadSurface failed", t)
        }
        padSwapchainInitialized = false
        padSurface = null
    }

    /** renderer 就绪且 pad surface 已到 → 创建 pad swapchain（幂等）。 */
    private fun tryInitPadSurfaceLocked() {
        if (!rendererReady || padSwapchainInitialized) return
        val s = padSurface ?: return
        try {
            NativeEmulation.setSurface(s, false)
            if (padSurfaceWidth > 0 && padSurfaceHeight > 0) {
                NativeEmulation.setSurfaceSize(padSurfaceWidth, padSurfaceHeight, false)
            }
            NativeEmulation.initializeSurface(false)
            padSwapchainInitialized = true
        } catch (t: Throwable) {
            android.util.Log.w("CemuEngine", "pad surface init failed", t)
        }
    }

    /**
     * GamePad 悬浮画面触摸 —— ★对齐上游 Cemu-AZ CanvasOnTouchListener(isTV=false)★：
     * 归一化触点换回悬浮窗原始视图像素（padSurfaceWidth/Height 由 surfaceChanged
     * 实时维护，与视图尺寸一致）后传 isTV=false（写入 m_pad_mouse）。核心侧用
     * 【悬浮窗】的图像矩形（GetPadWindowPhysSize + 当前输出图像宽高比）归一化 ——
     * 悬浮窗固定 16:9（854x480 比例），DRC 内容填满窗口，映射 1:1；若核心侧
     * 因 fullscreenScaling/分辨率变化产生信箱区，同样被自动补偿。
     */
    override fun setPadTouch(nx: Float, ny: Float, pressed: Boolean) {
        try {
            if (pressed) {
                val dx = (nx.coerceIn(0f, 1f) * padSurfaceWidth.coerceAtLeast(1)).toInt()
                val dy = (ny.coerceIn(0f, 1f) * padSurfaceHeight.coerceAtLeast(1)).toInt()
                NativeInput.onTouchDown(dx, dy, false)
            } else {
                NativeInput.onTouchUp(0, 0, false)
            }
        } catch (_: Throwable) {}
    }

    override fun setPadTouchMoved(nx: Float, ny: Float) {
        try {
            val dx = (nx.coerceIn(0f, 1f) * padSurfaceWidth.coerceAtLeast(1)).toInt()
            val dy = (ny.coerceIn(0f, 1f) * padSurfaceHeight.coerceAtLeast(1)).toInt()
            NativeInput.onTouchMove(dx, dy, false)
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------
    // 即时存档（NativeQuickSave 原生槽位 —— 完全对齐上游 Cemu-AZ
    // QuickSaveSlotsDialog 语义：槽位 1..6 由 getQuickSaveInfo 驱动，
    // 读档默认不启用 legacy 回退，STATE_RESTORE_FAILED 时由 UI 提供
    // “安全模式”重试；legacy 恢复（RESULT_OK）后必须重启游戏）
    // ------------------------------------------------------------------

    /** UI 槽位（0..9）→ 原生槽位（1..6）。 */
    private fun nativeSlotFor(uiSlot: Int): Int = ((uiSlot % 6) + 1).coerceIn(1, 6)

    override fun saveState(slot: Int, dst: File): Boolean {
        if (!isLoaded) return false
        return try {
            val nSlot = nativeSlotFor(slot)
            // ★ R10: 与读档/快捷存档互斥（防并发冻结竞态）
            val r = synchronized(stateOpLock) { NativeQuickSave.quickSaveSlot(nSlot) }
            when (r) {
                NativeQuickSave.RESULT_OK, NativeQuickSave.RESULT_OK_RESTORED_IN_PLACE -> {
                    // dst 标记文件（UI 槽位列表/时间戳展示用；核心槽位在
                    // <userDir>/states，进程重启后仍可用）
                    try {
                        dst.parentFile?.mkdirs()
                        dst.writeText("cemu-quicksave-slot-$nSlot\n")
                    } catch (_: Throwable) {}
                    true
                }
                NativeQuickSave.RESULT_ERROR_NOT_RUNNING -> {
                    lastErrorText = "存档失败：核心未在运行"
                    false
                }
                NativeQuickSave.RESULT_ERROR_NO_SAVE_DATA -> {
                    lastErrorText = "存档失败：无存档数据"
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
            // ★ 上游语义：默认 flags=0（不用 legacy 回退）。恢复失败如实报错，
            //   由上层提供“安全模式”重试，而不是静默关游戏换存档数据。
            val nSlot = nativeSlotFor(slot)
            // ★ R10: 与存档/其它读档互斥（防并发冻结竞态 → 偶发闪退）
            val r = synchronized(stateOpLock) { NativeQuickSave.quickRestoreSlot(nSlot, 0) }
            when (r) {
                NativeQuickSave.RESULT_OK_RESTORED_IN_PLACE -> true
                NativeQuickSave.RESULT_OK -> {
                    // legacy 路径已把游戏关闭（只恢复了存档数据）→
                    // 上游 relaunchGameInProcess：进程内重启游戏
                    relaunchTitle()
                    true
                }
                NativeQuickSave.RESULT_ERROR_SLOT_EMPTY -> {
                    lastErrorText = "读档失败：槽位 $slot 为空"
                    false
                }
                NativeQuickSave.RESULT_ERROR_STATE_RESTORE_FAILED -> {
                    lastErrorText = "读档失败：状态恢复失败（可用安全模式重试）"
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

    /**
     * 原生槽位快捷存档（上游 QuickSaveSlotsDialog.saveToSlot 同款）。
     * ⚠ 会阻塞（截图等待 + 全量状态冻结），调用方必须在 IO 线程执行。
     * @param nativeSlot 原生槽位 1..6（直接透传，不做 UI 映射）
     * @return NativeQuickSave.RESULT_* 结果码
     */
    fun quickSaveSlotRaw(nativeSlot: Int): Int = try {
        // ★ R10: 串行化（见 stateOpLock 注释）
        synchronized(stateOpLock) { NativeQuickSave.quickSaveSlot(nativeSlot) }
    } catch (t: Throwable) {
        android.util.Log.w("CemuEngine", "quickSaveSlotRaw($nativeSlot)", t)
        NativeQuickSave.RESULT_ERROR_IO
    }

    /**
     * 原生槽位读档（上游 restoreFromSlot 同款：默认 flags=0，
     * 安全模式重试时传 RESTORE_FLAG_ALLOW_LEGACY）。⚠ 阻塞，IO 线程执行。
     */
    fun quickRestoreSlotRaw(nativeSlot: Int, flags: Int): Int = try {
        // ★ R10: 串行化（见 stateOpLock 注释）
        synchronized(stateOpLock) { NativeQuickSave.quickRestoreSlot(nativeSlot, flags) }
    } catch (t: Throwable) {
        android.util.Log.w("CemuEngine", "quickRestoreSlotRaw($nativeSlot)", t)
        NativeQuickSave.RESULT_ERROR_IO
    }

    /** 槽位信息（含缩略图元数据）。⚠ 阻塞，IO 线程执行。 */
    fun quickSaveInfo(): info.cemu.cemu.nativeinterface.NativeQuickSave.QuickSaveInfo? {
        if (!isLoaded) return null
        return try {
            NativeQuickSave.parseQuickSaveInfo(NativeQuickSave.getQuickSaveInfo())
        } catch (_: Throwable) { null }
    }

    /** 槽位缩略图（无图返回 null）。⚠ 阻塞，IO 线程执行。 */
    fun quickSaveThumbnail(nativeSlot: Int): Bitmap? = try {
        NativeQuickSave.getQuickSaveThumbnail(nativeSlot)
    } catch (_: Throwable) { null }

    /**
     * 进程内重启游戏（上游 relaunchGameInProcess 同款）：
     * legacy 读档路径已 ShutdownTitle，需重新 prepareTitle →
     * initializeSystems → launchTitle（renderer/swapchain 保持不变）。
     */
    fun relaunchTitle() {
        val path = romPath ?: return
        thread(name = "cemu-relaunch", isDaemon = true) {
            try {
                val prepareResult = try {
                    NativeEmulation.prepareTitle(path)
                } catch (t: Throwable) {
                    android.util.Log.e("CemuEngine", "relaunch prepareTitle threw", t)
                    -1
                }
                if (prepareResult == NativeEmulation.PrepareTitleResult.SUCCESSFUL) {
                    NativeEmulation.initializeSystems()
                    NativeEmulation.launchTitle()
                } else {
                    val msg = "读档后重启游戏失败（准备阶段错误码 $prepareResult）\n" +
                        "存档数据已恢复，请退出后重新进入游戏"
                    lastErrorText = msg
                    try { onPrematureExit?.invoke(msg) } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                android.util.Log.e("CemuEngine", "relaunchTitle failed", t)
                lastErrorText = "读档后重启游戏异常：${t.message ?: t.javaClass.simpleName}"
                try { onPrematureExit?.invoke(lastErrorText) } catch (_: Throwable) {}
            }
        }
    }

    // ------------------------------------------------------------------
    // 帧捕获 / FPS / 错误
    // ------------------------------------------------------------------

    override fun captureFrame(): FrameCapture? = null

    override fun realtimeFps(): Double {
        // ★ 修全局帧数显示不准：直接读核心渲染线程的真实呈现帧率
        //   （LattePerformanceMonitor 每秒统计一次 —— 30 帧游戏显示 30、
        //   掉帧/快进均如实反映）。旧实现恒回 0，UI 回退心跳计数，
        //   而心跳是固定 16ms 节拍 → 显示恒 ≈60，与真实性能无关。
        //   核心未出帧/未启动时返回 0，UI 仍回退心跳计数。
        return try { NativeEmulation.getRendererFps() } catch (_: Throwable) { 0.0 }
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
