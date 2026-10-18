package com.nesstation.app.core.engine

import android.content.Context
import android.net.Uri
import android.view.Surface
import com.nesstation.app.core.jni.EdenNative
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.disk_shader_cache.DiskShaderCacheProgress
import org.yuzu.yuzu_emu.features.input.NativeInput
import org.yuzu.yuzu_emu.utils.GpuDriverHelper
import org.yuzu.yuzu_emu.utils.NativeConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Eden（Nintendo Switch 模拟器核心）引擎 —— NesStation 集成。
 *
 * 上游：eden-emulator/eden（yuzu 系社区延续分支）。完整源码树移植在
 * core/eden/，经 :eden-core Gradle 模块（CPM + CMake）从源码编译产出
 * libyuzu-android.so —— 集成方式与 Wii U（Cemu-AZ）的 :cemu-core 完全
 * 一致；JNI 契约 vendored 于 org.yuzu.yuzu_emu.*（原包名，
 * Java_org_yuzu_yuzu_1emu_* 符号一一对应）。
 *
 * 架构（推模型 —— 引擎不拥有模拟循环，与 CemuEngine/AzaharEngine 同构）：
 *  1. [loadRom] 完成核心一次性初始化（initJvm → GPU 驱动参数 →
 *     setAppDirectory → NativeConfig → keys 自动安装 → initializeSystem），
 *     记录 ROM 路径；Surface 有效后（[setSurface]）由专用线程执行：
 *     applySettings → surfaceChanged(surf) → run(path, 0, true)（阻塞
 *     至模拟结束；错误经 NativeLibrary.host 回调上抛）；
 *  2. 暂停/恢复走 pauseEmulation/unpauseEmulation；快进/游戏速度走
 *     setTurboSpeedLimit / setSlowSpeedLimit / setStandardSpeedLimit；
 *  3. 音频由核心内部输出（oboe/cubeb，推模型核心约定）；
 *  4. 渲染为 Vulkan 直绘主画布（1280x720 内部分辨率）。
 *
 * 输入（[EdenCoreEngine]，NesStation 统一位布局 → Switch）：
 *  - [setPad1] 位布局 → VirtualGamepad 按键事件（onOverlayButtonEventImpl，
 *    0..19 索引 = A/B/X/Y/摇杆按下/L/R/ZL/ZR/+/−/十字/SL/SR/HOME/截屏）；
 *  - [setAnalogAxes] → onOverlayJoystickEventImpl（0=左摇杆 1=右摇杆）；
 *  - [setTouchInput]/[setTouchMoved] → 窗口触摸（handheld 形态即 Switch
 *    触摸屏；docked 形态核心按需忽略，与上游行为一致）。
 *
 * 设置：`setCoreOption("EdenEngine/<name>", value)` 门面键直调 NativeConfig
 * （键名对照上游 settings.h 序列化键），运行中热生效（RuntimeList 类
 * 通过 applySettings 应用）。
 *
 * ROM：.xci/.nsp/.nca/.nro（GameEntry.NSWITCH；.xci/.nsp 为主体格式）。
 * 存档：走游戏内存档（NAND user save）；核心无即时存档（saveState 返回
 * false 并说明）。
 *
 * Keys / 固件 / 驱动（设置页完整入口）：
 *  - [installKeysFromAssets] 构建者把 prod.keys/title.keys 放进
 *    assets/eden/keys/ 即自动识别安装（无需玩家操作）；
 *  - [installKeysFromUri] 玩家手动选择（设置页按钮，SAF 选 .keys 文件）；
 *  - [installFirmwareFromUri] 固件 zip 安装（官方 InstallableActions 同
 *    流程：解包 → 校验全 .nca → 落 <filesDir>/eden/nand/system/Contents/
 *    registered/ → initializeSystem(true)）；
 *  - [uninstallFirmware] 固件卸载；
 *  - GPU 驱动管理由 GpuDriverHelper（<filesDir>/eden/gpu_driver*）承担，
 *    设置页提供导入/切换/删除按钮。
 */
class EdenEngine private constructor() : EmulatorEngine, EdenCoreEngine,
    NativeLibrary.Host {

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
    /** 基准游戏速度档（设置页"游戏速度"0..4，快进关闭时恢复到该档）。 */
    @Volatile private var _baseGameSpeed = 1
    @Volatile private var lastErrorText = ""

    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    @Volatile private var romPath: String? = null
    @Volatile private var pendingBoot = false
    @Volatile private var bootStarted = false
    private var emuThread: Thread? = null
    private var heartbeatThread: Thread? = null

    /** App context —— 由 NesApp.onCreate 注入（定位用户目录/资源）。 */
    @Volatile var appContext: Context? = null

    /** 非用户请求的模拟退出回调（核心线程触发，宿主自行 post 主线程）。 */
    @Volatile var onPrematureExit: ((String) -> Unit)? = null

    /** 用户主动停止标记（true 时 run 返回不触发 [onPrematureExit]）。 */
    @Volatile private var userRequestedStop = false

    /** 核心一次性初始化完成标记（进程生命周期内一次）。 */
    @Volatile private var coreInitialized = false

    /** keys 状态流（设置页显示）：null = 探测中/未加载。 */
    val keysState = MutableStateFlow<KeysState?>(null).asStateFlow()

    /** 固件状态流（设置页显示）。 */
    val firmwareState = MutableStateFlow<FirmwareState?>(null).asStateFlow()

    data class KeysState(val present: Boolean, val source: String)
    data class FirmwareState(val available: Boolean, val version: String)

    // === Netplay（推模型核心不支持锁步 —— 接受但无效，与 Cemu/PS2 引擎一致） ===
    @Volatile private var _frameHook: NetplayHook? = null
    @Volatile private var _netFrame = 0L
    override var frameHook: NetplayHook?
        get() = _frameHook
        set(value) {
            _frameHook = value
            _netFrame = 0L
        }

    private val lifecycleLock = Any()

    // 输入状态（边沿检测用）
    @Volatile private var lastPadBits = 0

    // ------------------------------------------------------------------
    // 可用性
    // ------------------------------------------------------------------

    data class Availability(val available: Boolean, val reason: String?)

    @Volatile private var probedAvailability: Availability? = null

    fun probeAvailability(): Availability {
        probedAvailability?.let { return it }
        val result = try {
            EdenNative.ensureLoaded()
            if (EdenNative.loaded) {
                Availability(true, null)
            } else {
                Availability(
                    false,
                    "libyuzu-android 加载失败（当前进程非 arm64-v8a、API 低于 28，或模块未构建）\n" +
                        "Eden 核心由 :eden-core 模块从 core/eden/ 源码构建，仅支持 arm64-v8a + " +
                        "Android 9(API 28)+。请执行 ./gradlew assembleDebug 完成构建" +
                        "（首次需联网编译 FFmpeg/OpenSSL 等依赖，之后有缓存）。"
                )
            }
        } catch (e: Throwable) {
            Availability(false, "Eden 核心加载异常: ${e.message}")
        }
        probedAvailability = result
        return result
    }

    override fun isCoreAvailable(): Boolean = probeAvailability().available

    override fun ensureLoaded(): Boolean = probeAvailability().available

    // ------------------------------------------------------------------
    // 目录 / 路径（<filesDir>/eden 用户目录）
    // ------------------------------------------------------------------

    private fun userDir(): File {
        val ctx = appContext
            ?: throw IllegalStateException("EdenEngine: app context not initialised")
        return File(ctx.filesDir, "eden").apply { mkdirs() }
    }

    private fun keysDir(): File = File(userDir(), "keys").apply { mkdirs() }
    private fun nandDir(): File = File(userDir(), "nand")
    private fun sdmcDir(): File = File(userDir(), "sdmc")

    /** 固件落盘目录（官方 InstallableActions 同路径）。 */
    fun firmwareRegisteredDir(): File =
        File(nandDir(), "system/Contents/registered")

    // ------------------------------------------------------------------
    // 核心一次性初始化（loadRom / 设置页操作前调用）
    // ------------------------------------------------------------------

    /**
     * 核心一次性初始化（幂等）：目录/驱动参数/全局配置/系统初始化。
     * loadRom 与设置面板（CoreSettingsPanel 的密钥/固件/驱动分区）共用。
     */
    fun coreInitOnce(): Boolean {
        if (coreInitialized) return true
        val ctx = appContext ?: run {
            lastErrorText = "EdenEngine 未初始化（appContext 为空）"
            return false
        }
        if (!EdenNative.ensureInitialized()) {
            lastErrorText = probeAvailability().reason ?: "Eden 核心不可用"
            return false
        }
        try {
            // 1. C++→Java 回调桥 + SAF resolver
            NativeLibrary.host = this
            GpuDriverHelper.ContextHolder.resolver = ctx.contentResolver

            // 2. 目录
            userDir(); keysDir(); nandDir().mkdirs(); sdmcDir().mkdirs()

            // 3. GPU 驱动参数（上游 YuzuApplication.onCreate 同款时序：
            //    initializeDriverParameters 内部完成 adrenotools 钩子/自定义
            //    驱动/文件重定向初始化 + freedreno 配置）
            GpuDriverHelper.initializeDriverParameters(
                ctx, ctx.applicationInfo.nativeLibraryDir
            )

            // 4. 应用目录 + 全局配置（上游 DirectoryInitialization.start 同款）
            NativeLibrary.setAppDirectory(userDir().absolutePath)
            NativeConfig.initializeGlobalConfig()
            NativeConfig.setNandDir(nandDir().absolutePath)
            NativeConfig.setSdmcDir(sdmcDir().absolutePath)

            // 5. keys 自动识别（assets → filesDir/eden/keys/）
            installKeysFromAssets(ctx)
            refreshKeysState("assets/手动")

            // 6. 系统初始化（System().Initialize + EmulationSession）
            NativeLibrary.initializeSystem(false)
            try { NativeLibrary.logDeviceInfo() } catch (_: Throwable) {}

            // 7. 应用当前持久化的设置（PadLayoutStore ed* 门面值）
            applyPersistedSettings()

            coreInitialized = true
            refreshFirmwareState()
            return true
        } catch (t: Throwable) {
            lastErrorText = "Eden 核心初始化失败: ${t.message ?: t.javaClass.simpleName}"
            android.util.Log.e("EdenEngine", "coreInitOnce failed", t)
            return false
        }
    }

    /** 探测后更新 keys/固件状态流（设置页与启动前校验消费）。 */
    fun refreshKeysState(sourceHint: String) {
        if (!EdenNative.loaded) return
        try {
            val present = NativeLibrary.areKeysPresent()
            keysState.value = KeysState(present, if (present) sourceHint else "缺失")
        } catch (_: Throwable) {
        }
    }

    fun refreshFirmwareState() {
        if (!EdenNative.loaded) return
        try {
            firmwareState.value = FirmwareState(
                NativeLibrary.isFirmwareAvailable(),
                NativeLibrary.firmwareVersion() ?: ""
            )
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    // Keys：assets 自动识别 / 玩家手动加载
    // ------------------------------------------------------------------

    /**
     * assets 自动识别（构建者零操作集成）：把 APK assets/eden/keys/ 下的
     * prod.keys / title.keys 复制到 <filesDir>/eden/keys/（已存在则跳过，
     * 不覆盖玩家手动导入的版本）。
     */
    fun installKeysFromAssets(ctx: Context) {
        listOf("prod.keys", "title.keys").forEach { name ->
            val dst = File(keysDir(), name)
            if (dst.exists() && dst.length() > 0) return@forEach
            try {
                ctx.assets.open("eden/keys/$name").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                android.util.Log.i("EdenEngine", "keys 从 assets 自动安装: $name")
            } catch (e: Exception) {
                // assets 无该文件 —— 正常场景（玩家手动导入）
            }
        }
    }

    /**
     * 玩家手动加载 keys（设置页"选择 keys 文件"按钮）。
     * SAF 选中 .keys 文件（prod.keys 或 title.keys）→ 落盘临时真实路径 →
     * NativeLibrary.installKeys(path, "keys")（上游 FirmwareManager 语义：
     * 校验扩展名 + 复制进 keys 目录）→ reloadKeys。
     * @return null = 成功；否则为用户可读的错误信息。
     */
    fun installKeysFromUri(ctx: Context, uri: Uri): String? {
        if (!coreInitOnce()) return lastErrorText
        return try {
            val name = queryDisplayName(ctx, uri)
                ?: uri.lastPathSegment?.substringAfterLast('/') ?: "prod.keys"
            if (!name.lowercase().endsWith(".keys")) {
                return "所选文件不是 .keys 密钥文件（选中：$name）"
            }
            val tmp = File(ctx.cacheDir, "eden_key_pick_$name")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return "无法读取所选文件"
            val code = NativeLibrary.installKeys(tmp.absolutePath, "keys")
            tmp.delete()
            if (code == 0) {
                NativeLibrary.reloadKeys()
                refreshKeysState("手动导入")
                null
            } else {
                "密钥安装失败（错误码 $code；请确认选择的是 prod.keys / title.keys）"
            }
        } catch (t: Throwable) {
            "密钥安装失败: ${t.message}"
        }
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------
    // 固件：安装 / 卸载（官方 InstallableActions.processFirmware 同流程）
    // ------------------------------------------------------------------

    /**
     * 玩家手动安装固件（设置页"安装固件"按钮，SAF 选固件 zip）。
     * 官方语义：解包 → 校验全部为 .nca → 清空并落
     * <filesDir>/eden/nand/system/Contents/registered/ → initializeSystem(true)。
     * @param onProgress (0..100)，取消返回 true
     * @return null = 成功；否则为错误信息。
     */
    fun installFirmwareFromUri(
        ctx: Context,
        uri: Uri,
        onProgress: (Int) -> Boolean
    ): String? {
        if (!coreInitOnce()) return lastErrorText
        val cacheDir = File(ctx.cacheDir, "eden_firmware_registered")
        return try {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            // 解包（带进度）
            val total = ctx.contentResolver.openInputStream(uri)?.use { it.available() } ?: 0
            var written = 0L
            java.util.zip.ZipInputStream(ctx.contentResolver.openInputStream(uri)).use { zis ->
                var entry = zis.nextEntry
                val buf = ByteArray(64 * 1024)
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // 固件 zip 内应为直接铺开的 .nca（可能带目录层级）
                        val rel = entry.name.substringAfterLast('/')
                        val out = File(cacheDir, rel)
                        java.io.FileOutputStream(out).use { fos ->
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                                written += n
                                if (total > 0 && onProgress(((written * 100) / total).toInt())) {
                                    throw java.io.IOException("cancelled")
                                }
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            // 校验：全部 .nca 且非空
            val files = cacheDir.listFiles() ?: emptyArray()
            if (files.isEmpty() || files.any { !it.name.lowercase().endsWith(".nca") }) {
                cacheDir.deleteRecursively()
                return "固件包校验失败：请选择官方固件 zip（内含 .nca 文件）"
            }
            // 落盘 registered/
            firmwareRegisteredDir().deleteRecursively()
            firmwareRegisteredDir().mkdirs()
            files.forEach { it.copyTo(File(firmwareRegisteredDir(), it.name), overwrite = true) }
            cacheDir.deleteRecursively()
            NativeLibrary.initializeSystem(true)
            refreshFirmwareState()
            null
        } catch (t: Throwable) {
            cacheDir.deleteRecursively()
            "固件安装失败: ${t.message}"
        }
    }

    /** 卸载固件（设置页按钮）。 */
    fun uninstallFirmware(): String? {
        if (!coreInitOnce()) return lastErrorText
        return try {
            firmwareRegisteredDir().deleteRecursively()
            NativeLibrary.initializeSystem(true)
            refreshFirmwareState()
            null
        } catch (t: Throwable) {
            "固件卸载失败: ${t.message}"
        }
    }

    // ------------------------------------------------------------------
    // ROM 加载（推模型：记录路径 + 初始化，真正启动在 Surface 就绪后）
    // ------------------------------------------------------------------

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean {
        synchronized(lifecycleLock) {
            if (!coreInitOnce()) return false
            if (running.get()) {
                unloadLocked()
            }
            // keys / 固件启动前校验（给出可操作的提示而不是黑屏）
            if (!NativeLibrary.areKeysPresent()) {
                lastErrorText = "缺少 Switch 密钥（prod.keys）。\n" +
                    "请在 设置 → Switch·Eden → 密钥与固件 里选择密钥文件，" +
                    "或由构建者放入 assets/eden/keys/ 自动识别。"
                return false
            }
            romPath = rom.absolutePath
            isLoaded = true
            _paused = false
            _ffSpeed = 0
            userRequestedStop = false
            pendingBoot = true
            bootStarted = false
            running.set(true)

            // HUD 心跳（推模型核心前端无模拟循环；16ms 节拍驱动 onFrame 计数）
            heartbeatThread = thread(name = "eden-hud-heartbeat", isDaemon = true) {
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
                    android.util.Log.e("EdenEngine", "heartbeat crashed", t)
                }
            }

            // Surface 可能已就绪（EmulatorScreen 先挂 Surface 再 loadRom）——直接开跑
            surface?.let { startBootLocked() }
            return true
        }
    }

    /**
     * 启动序列（上游 EmulationFragment.run 同款）：
     * applySettings → surfaceChanged(surf) → run(path, 0, true)。
     * run 阻塞至模拟结束；非 Success 返回经 NativeLibrary.exitEmulationActivity
     * 回调（Host 桥）上抛。
     */
    private fun startBootLocked() {
        val path = romPath ?: return
        if (bootStarted) return
        bootStarted = true
        pendingBoot = false
        emuThread = thread(name = "eden-emu", isDaemon = true) {
            try {
                // 运行时可调项热应用
                try { NativeLibrary.applySettings() } catch (_: Throwable) {}

                // 主画布 surface（上游 surfaceChanged 在 run 之前）
                val s = surface
                if (s != null && s.isValid) {
                    NativeLibrary.surfaceChanged(s)
                } else {
                    android.util.Log.w("EdenEngine", "boot without surface — will attach on setSurface")
                }

                // 启动（阻塞直至停止）
                NativeLibrary.run(path, 0, true)
                // 正常返回（Success）= 用户停止或程序内退出 —— 不上抛
            } catch (t: Throwable) {
                android.util.Log.e("EdenEngine", "run crashed", t)
                failBoot("模拟器运行异常退出：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun failBoot(msg: String) {
        lastErrorText = msg
        running.set(false)
        try { onPrematureExit?.invoke(msg) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------
    // NativeLibrary.Host（C++→Java 回调）
    // ------------------------------------------------------------------

    override fun exitEmulationActivity(result: Int) {
        // run() 返回非 Success —— Core::SystemResultStatus 枚举值
        val reason = when (result) {
            1 -> "找不到可执行文件（ROM 损坏或格式不受支持）"
            2 -> "缺少密钥（prod.keys）——加密内容无法解密"
            3 -> "缺少系统文件/固件（部分游戏需要固件，请在设置里安装）"
            4 -> "共享字体缺失（需安装固件）"
            else -> "核心错误（代码 $result）"
        }
        if (!userRequestedStop) {
            failBoot("启动失败：$reason")
        }
    }

    override fun onEmulationStarted() {
        android.util.Log.i("EdenEngine", "emulation started")
    }

    override fun onEmulationStopped(result: Int) {
        android.util.Log.i("EdenEngine", "emulation stopped ($result)")
    }

    override fun onProgramChanged(programIndex: Int) {
        android.util.Log.i("EdenEngine", "program changed -> $programIndex")
    }

    // ------------------------------------------------------------------
    // Surface 生命周期
    // ------------------------------------------------------------------

    override fun setSurface(surface: Surface?) {
        synchronized(lifecycleLock) {
            this.surface = surface
            if (surface == null) return
            if (bootStarted && !pendingBoot) {
                // 已在运行 → 重挂（Surface 重建场景，如切后台回来）
                try { NativeLibrary.surfaceChanged(surface) } catch (_: Throwable) {}
            } else {
                startBootLocked()
            }
        }
    }

    override fun onSurfaceChanged(surface: Surface?, width: Int, height: Int) {
        synchronized(lifecycleLock) {
            this.surface = surface
            surfaceWidth = width
            surfaceHeight = height
            if (surface != null && bootStarted && !pendingBoot) {
                try { NativeLibrary.surfaceChanged(surface) } catch (_: Throwable) {}
            }
        }
    }

    override fun onSurfaceDestroyed() {
        synchronized(lifecycleLock) {
            if (EdenNative.loaded && running.get()) {
                try { NativeLibrary.surfaceDestroyed() } catch (_: Throwable) {}
            }
        }
    }

    // ------------------------------------------------------------------
    // 输入（EdenCoreEngine）—— VirtualGamepad 桥接
    // ------------------------------------------------------------------

    override fun setPad1(bits: Int) {
        setPlayerPad(0, bits)
    }

    override fun setPad2(bits: Int) {
        setPlayerPad(1, bits)
    }

    private fun setPlayerPad(port: Int, bits: Int) {
        if (!EdenNative.loaded || !running.get()) return
        if (port != 0) return // 单 GamePad（与 Wii U 同策略：P1 专用）
        val old = lastPadBits
        // 位布局 → VirtualGamepad 索引（input_common VirtualButton）：
        //  0=A 1=B 2=X 3=Y 4=StickL 5=StickR 6=L 7=R 8=ZL 9=ZR 10=+ 11=-
        //  12=Left 13=Up 14=Right 15=Down 16=SL 17=SR 18=Home 19=Capture
        fun edge(mask: Int, idx: Int) {
            val now = bits and mask != 0
            val was = old and mask != 0
            if (now != was) {
                NativeInput.onOverlayButtonEventImpl(
                    port, idx, if (now) NativeInput.ButtonState.PRESSED else NativeInput.ButtonState.RELEASED
                )
            }
        }
        edge(BTN_A, 0); edge(BTN_B, 1); edge(BTN_X, 2); edge(BTN_Y, 3)
        edge(BTN_L3, 4); edge(BTN_R3, 5)             // 摇杆按下
        edge(BTN_L_SNES, 6); edge(BTN_R_SNES, 7)
        edge(BTN_ZL, 8); edge(BTN_ZR, 9)
        edge(BTN_START, 10); edge(BTN_SELECT, 11)     // + / −
        edge(BTN_LEFT, 12); edge(BTN_UP, 13); edge(BTN_RIGHT, 14); edge(BTN_DOWN, 15)
        edge(BTN_HOME, 18)
        edge(BTN_IR_NEAR, 19)                        // bit26 复用：Switch 语境=截屏键
        lastPadBits = bits
    }

    override fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float) {
        if (!EdenNative.loaded || !running.get()) return
        // 屏幕坐标约定（上=负）→ 上游同口径（y 上=负），直接透传
        NativeInput.onOverlayJoystickEventImpl(0, 0, lx, ly)   // 左摇杆
        NativeInput.onOverlayJoystickEventImpl(0, 1, rx, ry)   // 右摇杆
    }

    override fun setTouchInput(x: Float, y: Float, pressed: Boolean) {
        if (!EdenNative.loaded || !running.get()) return
        if (pressed) {
            NativeInput.onTouchPressed(0, normalizeX(x), normalizeY(y))
        } else {
            NativeInput.onTouchReleased(0)
        }
    }

    override fun setTouchMoved(x: Float, y: Float) {
        if (!EdenNative.loaded || !running.get()) return
        NativeInput.onTouchMoved(0, normalizeX(x), normalizeY(y))
    }

    /** 游戏视图像素坐标 → [0,1] 归一化（EmuWindow 窗口触摸口径）。 */
    private fun normalizeX(x: Float): Float {
        val w = if (surfaceWidth > 0) surfaceWidth else 1280
        return (x / w).coerceIn(0f, 1f)
    }

    private fun normalizeY(y: Float): Float {
        val h = if (surfaceHeight > 0) surfaceHeight else 720
        return (y / h).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------
    // 控制（暂停/快进/重置/卸载）
    // ------------------------------------------------------------------

    override fun setPaused(paused: Boolean) {
        if (!EdenNative.loaded || !running.get()) return
        if (paused == _paused) return
        _paused = paused
        try {
            if (paused) NativeLibrary.pauseEmulation() else NativeLibrary.unpauseEmulation()
        } catch (t: Throwable) {
            android.util.Log.w("EdenEngine", "pause/unpause failed", t)
        }
    }

    override fun setFastForward(speed: Int) {
        if (!EdenNative.loaded || !running.get()) return
        val on = speed > 0
        if (on == (_ffSpeed > 0)) return
        _ffSpeed = if (on) speed else 0
        try {
            if (on) NativeLibrary.setTurboSpeedLimit(true)
            else applyBaseGameSpeed()
        } catch (t: Throwable) {
            android.util.Log.w("EdenEngine", "fast-forward failed", t)
        }
    }

    /** 应用基准游戏速度档（0=0.5x 1=1x 2=2x 3=4x 4=8x）。 */
    private fun applyBaseGameSpeed() {
        try {
            when (_baseGameSpeed) {
                0 -> { NativeConfig.setInt("slow_speed_limit", 50); NativeLibrary.setSlowSpeedLimit(true) }
                1 -> NativeLibrary.setStandardSpeedLimit(true)
                2 -> { NativeConfig.setInt("turbo_speed_limit", 200); NativeLibrary.setTurboSpeedLimit(true) }
                3 -> { NativeConfig.setInt("turbo_speed_limit", 400); NativeLibrary.setTurboSpeedLimit(true) }
                else -> { NativeConfig.setInt("turbo_speed_limit", 800); NativeLibrary.setTurboSpeedLimit(true) }
            }
        } catch (t: Throwable) {
            android.util.Log.w("EdenEngine", "applyBaseGameSpeed failed", t)
        }
    }

    override fun reset(hard: Boolean) {
        // Switch 核心无热重置（游戏内重开由玩家操作）；保留 API 兼容。
    }

    private fun unloadLocked() {
        userRequestedStop = true
        try { if (EdenNative.loaded) NativeLibrary.stopEmulation() } catch (_: Throwable) {}
        // 等待模拟线程退出（有界）
        emuThread?.let { t -> try { t.join(1500) } catch (_: InterruptedException) {} }
        emuThread = null
        heartbeatThread?.let { t -> try { t.interrupt() } catch (_: Throwable) {} }
        heartbeatThread = null
        running.set(false)
        isLoaded = false
        romPath = null
        pendingBoot = false
        bootStarted = false
        _paused = false
        _ffSpeed = 0
        lastPadBits = 0
        userRequestedStop = false
    }

    override fun unload() {
        synchronized(lifecycleLock) {
            if (running.get() || isLoaded) unloadLocked()
        }
    }

    override fun shutdown() {
        unload()
    }

    // ------------------------------------------------------------------
    // EmulatorEngine 其余（视频/存档/截图/FPS）
    // ------------------------------------------------------------------

    override fun videoWidth(): Int = 1280
    override fun videoHeight(): Int = 720

    override fun setVideoFilter(filter: Int) { /* 前端滤镜归 EmulatorScreen 合成器 */ }
    override fun setHighQualityScaling(enabled: Boolean) { /* 直绘核心无 CPU 缩放路径 */ }
    override fun setRegion(region: Int) { /* Switch 无区域强制（游戏卡带自带） */ }
    override fun setSampleRate(rate: Int) { /* 音频由核心内部输出 */ }

    override fun setSaveName(name: String) { /* 存档在 NAND 内，按游戏区分；API 兼容 */ }

    override fun saveState(slot: Int, dst: File): Boolean {
        lastErrorText = "Switch 核心暂不支持即时存档（请使用游戏内存档）"
        return false
    }

    override fun loadState(slot: Int, src: File): Boolean {
        lastErrorText = "Switch 核心暂不支持即时读档（请使用游戏内存档）"
        return false
    }

    override fun captureFrame(): FrameCapture? = null

    override fun realtimeFps(): Double {
        if (!EdenNative.loaded || !running.get()) return 0.0
        return try {
            val stats = NativeLibrary.getPerfStats()
            // [0]=system_fps [1]=average_game_fps [2]=frametime [3]=emulation_speed
            stats.getOrNull(1) ?: 0.0
        } catch (_: Throwable) {
            0.0
        }
    }

    override fun lastError(): String = lastErrorText

    // ------------------------------------------------------------------
    // 设置门面（EdenEngine/<name>，见 CoreSettingsPanel / EmulatorScreen）
    // ------------------------------------------------------------------

    override fun setCoreOption(key: String, value: String) {
        if (!coreInitialized && !EdenNative.loaded) return
        try {
            applyEdenOption(key.removePrefix("EdenEngine/"), value)
        } catch (t: Throwable) {
            android.util.Log.w("EdenEngine", "setCoreOption($key) failed", t)
        }
    }

    /** 启动时把 PadLayoutStore 持久化的 ed* 值全部应用一遍。 */
    private fun applyPersistedSettings() {
        // 由 EmulatorScreen.applyCoreOptions 在游戏启动前统一推送
        // （与 CemuEngine cw* 同策略），此处仅应用全局基线速度档兜底。
    }

    private fun applyEdenOption(name: String, value: String) {
        val v = value.trim()
        when (name) {
            // ---- 画面/性能（RuntimeList 类经 applySettings 热生效；
            //      默认值均对齐上游 settings.h 在 __ANDROID__ 下的默认） ----
            "resolution" -> NativeConfig.setInt("resolution_setup", v.toIntOrNull() ?: 3)
            "gpuAccuracy" -> NativeConfig.setInt("gpu_accuracy", v.toIntOrNull() ?: 0)
            "cpuAccuracy" -> NativeConfig.setInt("cpu_accuracy", v.toIntOrNull() ?: 0)
            "cpuBackend" -> NativeConfig.setInt("cpu_backend", v.toIntOrNull() ?: 1)
            "anisotropy" -> NativeConfig.setInt("max_anisotropy", v.toIntOrNull() ?: 1)
            "scalingFilter" -> NativeConfig.setInt("scaling_filter", v.toIntOrNull() ?: 0)
            "antiAliasing" -> NativeConfig.setInt("anti_aliasing", v.toIntOrNull() ?: 0)
            "aspectRatio" -> NativeConfig.setInt("aspect_ratio", v.toIntOrNull() ?: 0)
            "framePacing" -> NativeConfig.setInt("frame_pacing_mode", v.toIntOrNull() ?: 0)
            "gpuFence" -> NativeConfig.setInt("gpu_fence_behavior", v.toIntOrNull() ?: 0)
            "nvdec" -> NativeConfig.setInt("nvdec_emulation", v.toIntOrNull() ?: 1)
            "astc" -> NativeConfig.setInt("accelerate_astc", v.toIntOrNull() ?: 1)
            "vramUsage" -> NativeConfig.setInt("vram_usage_mode", v.toIntOrNull() ?: 0)
            "vsync" -> NativeConfig.setInt("use_vsync", v.toIntOrNull() ?: 2)
            "asyncShaders" -> NativeConfig.setBoolean("use_asynchronous_shaders", v.toBoolean())
            "diskShaderCache" -> NativeConfig.setBoolean("use_disk_shader_cache", v.toBoolean())
            "asyncGpu" -> NativeConfig.setBoolean("use_asynchronous_gpu_emulation", v.toBoolean())
            "reactiveFlush" -> NativeConfig.setBoolean("use_reactive_flushing", v.toBoolean())
            "forceMaxClock" -> NativeConfig.setBoolean("force_max_clock", v.toBoolean())
            "dmaAccuracy" -> NativeConfig.setInt("dma_accuracy", v.toIntOrNull() ?: 0)
            "memoryLayout" -> NativeConfig.setInt("memory_layout_mode", v.toIntOrNull() ?: 0)

            // ---- 系统 ----
            "docked" -> NativeConfig.setBoolean("use_docked_mode", v.toBoolean())
            "language" -> NativeConfig.setInt("language_index", v.toIntOrNull() ?: 1)
            "region" -> NativeConfig.setInt("region_index", v.toIntOrNull() ?: 1)
            "gameSpeed" -> {
                _baseGameSpeed = (v.toIntOrNull() ?: 1).coerceIn(0, 4)
                if (_ffSpeed == 0) applyBaseGameSpeed()
            }

            // ---- 音频 ----
            "audioVolume" -> NativeConfig.setByte("volume", (v.toFloatOrNull() ?: 100f).coerceIn(0f, 100f).toInt().toByte())
            "audioMuted" -> NativeConfig.setBoolean("audio_muted", v.toBoolean())

            // ---- 网络/其他（记录型，暂无对应单一键） ----
            else -> android.util.Log.w("EdenEngine", "unknown eden option: $name=$value")
        }
        // 热应用运行时项（分辨率/精度/滤镜等）+ 落盘
        try {
            NativeLibrary.applySettings()
            NativeConfig.saveGlobalConfig()
        } catch (_: Throwable) {
        }
    }

    /** 当前着色器磁盘缓存编译进度（0..100；-1 无进行中编译）。HUD 提示用。 */
    fun shaderBuildProgress(): Int =
        try { DiskShaderCacheProgress.buildProgressPercent } catch (_: Throwable) { -1 }

    /** 当前生效 GPU 驱动名（设置页显示）。 */
    fun currentGpuDriver(): String =
        try { NativeLibrary.getGpuDriver() } catch (_: Throwable) { "未知" }

    /** Vulkan 信息（设置页显示）。 */
    fun vulkanInfo(): String =
        try {
            "Vulkan ${NativeLibrary.getVulkanApiVersion()} · ${NativeLibrary.getGpuModel()}"
        } catch (_: Throwable) { "未知" }

    /** CPU 后端（设置页显示：NCE = ARM 原生执行）。 */
    fun cpuBackend(): String =
        try { NativeLibrary.getCpuBackend() } catch (_: Throwable) { "未知" }

    // ------------------------------------------------------------------
    // 单例
    // ------------------------------------------------------------------

    companion object {
        @Volatile private var instance: EdenEngine? = null

        fun get(): EdenEngine =
            instance ?: EdenEngine().also { instance = it }
    }
}

/**
 * Eden（Switch）核心引擎契约 —— 在 [EmulatorEngine] 之上补充
 * Switch 手柄模拟摇杆与触摸屏能力（[EdenEngine] 实现）。
 */
interface EdenCoreEngine : EmulatorEngine {

    /**
     * 推送双摇杆：左摇杆 = (lx, ly)，右摇杆 = (rx, ry)，
     * 取值 -1..1（屏幕坐标约定，向上为负）。
     */
    fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float)

    /**
     * 游戏区触摸按下/抬起（游戏视图像素坐标；pressed=false 时坐标被忽略）。
     * 引擎内部归一化到 [0,1] 后喂给核心窗口（handheld 形态 = Switch 触摸屏）。
     */
    fun setTouchInput(x: Float, y: Float, pressed: Boolean)

    /** 游戏区触摸移动（游戏视图像素坐标）。 */
    fun setTouchMoved(x: Float, y: Float)

    /**
     * 核心是否已加载完成（.so 存在且 System.loadLibrary 成功、API>=28）。
     */
    fun isCoreAvailable(): Boolean
}

/** Switch 专属位（EmulatorScreen 位布局在 NSWITCH 语境下的复用说明）：
 *  bit26（BTN_IR_NEAR）在 Switch 语境 = 截屏（CAPTURE）键。 */
internal const val BTN_EDEN_CAPTURE = BTN_IR_NEAR
