package com.nesstation.app.core.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.view.Surface
import com.nesstation.app.core.jni.EdenNative
import com.nesstation.app.ui.emulator.BTN_A
import com.nesstation.app.ui.emulator.BTN_B
import com.nesstation.app.ui.emulator.BTN_DOWN
import com.nesstation.app.ui.emulator.BTN_HOME
import com.nesstation.app.ui.emulator.BTN_IR_FAR
import com.nesstation.app.ui.emulator.BTN_IR_NEAR
import com.nesstation.app.ui.emulator.BTN_L3
import com.nesstation.app.ui.emulator.BTN_L_SNES
import com.nesstation.app.ui.emulator.BTN_LEFT
import com.nesstation.app.ui.emulator.BTN_R3
import com.nesstation.app.ui.emulator.BTN_RIGHT
import com.nesstation.app.ui.emulator.BTN_R_SNES
import com.nesstation.app.ui.emulator.BTN_SELECT
import com.nesstation.app.ui.emulator.BTN_START
import com.nesstation.app.ui.emulator.BTN_UP
import com.nesstation.app.ui.emulator.BTN_X
import com.nesstation.app.ui.emulator.BTN_Y
import com.nesstation.app.ui.emulator.BTN_ZL
import com.nesstation.app.ui.emulator.BTN_ZR
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 *  3. 音频由核心内部输出（SDL3 sink，Android 默认 openslES → AAudio
 *     回退；引擎音量经 NativeConfig "volume"（u8 0..200，UI 0..100%））；
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
    private val _keysState = MutableStateFlow<KeysState?>(null)
    val keysState: StateFlow<KeysState?> = _keysState.asStateFlow()

    /** 固件状态流（设置页显示）。 */
    private val _firmwareState = MutableStateFlow<FirmwareState?>(null)
    val firmwareState: StateFlow<FirmwareState?> = _firmwareState.asStateFlow()

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
     *
     * ★★ 闪退修复（“启动 Switch 游戏 100% 闪退”根因之一）：并发安全 ——
     *   设置页 EdenKeysFirmwareSection/EdenDriverSection 两个 LaunchedEffect
     *   与 EmulatorScreen 的 loadRom 协程可能同时进入本函数，旧版无锁双
     *   初始化（System/GPU 驱动参数各跑两遍，时序交叉后状态不可预测）。
     *   加 @Synchronized 串行化，后到者直接命中 coreInitialized=true 返回。
     *
     * ★★★ 闪退修复 v3（“进 Switch 核心设置 / 启动 Switch 游戏必闪退”根因）：
     *   时序对齐 eden 上游 —— areKeysPresent() 及一切 keys/固件状态探测
     *   必须排在 initializeSystem(false) 之后。旧版把 refreshKeysState 放在
     *   第 5 步（initializeSystem 之前），此时 System 未初始化、
     *   GetFilesystem() 为空 shared_ptr，native 实现无条件
     *   CreateFactories(*GetFilesystem()) → 空指针段错误
     *   （tombstone: CreateFactories+324 SIGSEGV fault addr 0x0，Kotlin
     *   catch 拦不住段错误）。上游 eden 由 DirectoryInitialization.start()
     *   在 Application 启动时先 initializeSystem(false)，UI 层永远不会
     *   早于此调用，故上游不崩 —— 本函数即 NesStation 版 DirectoryInitialization，
     *   对齐上游顺序：setAppDirectory → initializeGlobalConfig →
     *   initializeSystem(false) → 之后才允许状态探测。
     */
    @Synchronized
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

            // 5. keys 自动识别（assets → filesDir/eden/keys/）——
            //    ★ 本步只复制文件，不做状态探测（areKeysPresent 依赖 System
            //    已初始化，必须等第 6 步完成，见函数头 v3 修复注释）。
            installKeysFromAssets(ctx)

            // 6. 系统初始化（System().Initialize + EmulationSession）——
            //    上游 DirectoryInitialization.start() 同款时序：先 initializeSystem，
            //    之后 UI 才允许 areKeysPresent()/isFirmwareAvailable()。
            NativeLibrary.initializeSystem(false)
            try { NativeLibrary.logDeviceInfo() } catch (_: Throwable) {}

            // 7. keys/固件状态探测（System 文件系统已就绪 —— 安全；native 侧
            //    areKeysPresent 已同步加空指针兜底，见 native.cpp v3 修复）
            refreshKeysState("assets/手动")

            // 8. 应用当前持久化的设置（PadLayoutStore ed* 门面值）
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
            _keysState.value = KeysState(present, if (present) sourceHint else "缺失")
        } catch (_: Throwable) {
        }
    }

    fun refreshFirmwareState() {
        if (!EdenNative.loaded) return
        try {
            _firmwareState.value = FirmwareState(
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
    // 体感（陀螺仪）桥接 —— 上游 EmulationActivity.startMotionSensorListener
    // + onSensorChanged 同款算法：加速度计归一到 1G，陀螺仪 /6 校准，
    // 横屏 90°/270° 轴向互换；仅游戏运行中送数（native 侧 IsRunning 双保险）。
    // ------------------------------------------------------------------

    private var motionSensorManager: SensorManager? = null
    private var motionListener: SensorEventListener? = null
    private val motionAccel = FloatArray(3)
    private val motionGyro = FloatArray(3)
    private var motionTimestamp: Long = 0L
    @Volatile private var motionFlipOrientation = false

    /**
     * 开关体感传感器桥（设置面板“体感”开关 → setCoreOption("EdenEngine/motion")）。
     * enabled=false 时注销监听；重复调用安全（幂等）。
     */
    fun setMotionSensorEnabled(ctx: Context?, enabled: Boolean) {
        // 已注销再关 / 已注册再开 —— 直接返回，避免重复注册监听器
        if (!enabled) {
            stopMotionSensorBridge()
            return
        }
        val c = ctx ?: appContext ?: return
        if (motionListener != null) return
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val gyroSensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: run {
            android.util.Log.w("EdenEngine", "体感：设备无陀螺仪，不启用传感器桥")
            return
        }
        val accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        motionAccel.fill(0f)
        motionGyro.fill(0f)
        motionTimestamp = 0L
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                try {
                    // 与上游同口径：暂停/未运行时不送数
                    if (!running.get() || _paused) return
                    val rotation = c.getSystemService(Context.WINDOW_SERVICE)
                        ?.let { (it as? android.view.WindowManager)?.defaultDisplay?.rotation }
                        ?: android.view.Surface.ROTATION_0
                    if (rotation == android.view.Surface.ROTATION_90) motionFlipOrientation = true
                    if (rotation == android.view.Surface.ROTATION_270) motionFlipOrientation = false
                    when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> {
                            if (motionFlipOrientation) {
                                motionAccel[0] = event.values[1] / SensorManager.GRAVITY_EARTH
                                motionAccel[1] = -event.values[0] / SensorManager.GRAVITY_EARTH
                            } else {
                                motionAccel[0] = -event.values[1] / SensorManager.GRAVITY_EARTH
                                motionAccel[1] = event.values[0] / SensorManager.GRAVITY_EARTH
                            }
                            motionAccel[2] = -event.values[2] / SensorManager.GRAVITY_EARTH
                        }
                        Sensor.TYPE_GYROSCOPE -> {
                            // 上游同款：传感器值约偏 6 倍，除 6 校准
                            if (motionFlipOrientation) {
                                motionGyro[0] = -event.values[1] / 6.0f
                                motionGyro[1] = event.values[0] / 6.0f
                            } else {
                                motionGyro[0] = event.values[1] / 6.0f
                                motionGyro[1] = -event.values[0] / 6.0f
                            }
                            motionGyro[2] = event.values[2] / 6.0f
                        }
                        else -> return
                    }
                    // 上游同口径：仅在加速度计事件上送数
                    if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                    val deltaTimestamp = if (motionTimestamp == 0L) 0L
                                         else (event.timestamp - motionTimestamp) / 1000
                    motionTimestamp = event.timestamp
                    // ★★ v6 修复“体感左右几乎没作用”：上游同口径双路送数 ——
                    //   Player1Device(0) + ConsoleDevice(8)。v5 只送了玩家1一路，
                    //   读主机六轴通道的游戏（体感左/右为主机通道）拿不到数据。
                    NativeInput.onDeviceMotionEvent(
                        MOTION_PLAYER1_PORT, deltaTimestamp,
                        motionGyro[0], motionGyro[1], motionGyro[2],
                        motionAccel[0], motionAccel[1], motionAccel[2]
                    )
                    NativeInput.onDeviceMotionEvent(
                        MOTION_CONSOLE_PORT, deltaTimestamp,
                        motionGyro[0], motionGyro[1], motionGyro[2],
                        motionAccel[0], motionAccel[1], motionAccel[2]
                    )
                } catch (_: Throwable) {
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        try {
            sm.registerListener(l, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
            sm.registerListener(l, accelSensor, SensorManager.SENSOR_DELAY_GAME)
            motionSensorManager = sm
            motionListener = l
            android.util.Log.i("EdenEngine", "体感传感器桥已启用（陀螺仪+加速度计）")
        } catch (t: Throwable) {
            android.util.Log.w("EdenEngine", "体感传感器注册失败", t)
        }
    }

    private fun stopMotionSensorBridge() {
        val l = motionListener ?: return
        try { motionSensorManager?.unregisterListener(l) } catch (_: Throwable) {}
        motionListener = null
        motionSensorManager = null
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
                // ★★ v4 修复（“大多数游戏进不去 / 启动期状态损坏”根因之一）：
                //   删掉这里的 NativeLibrary.applySettings() —— 上游 Eden 的
                //   启动链【从不】在 run 前调它（上游全仓库无一处 applySettings
                //   调用）。旧代码会在 System::Load 尚未开始时先来一发
                //   HIDCore::ReloadInputDevices() + System::ApplySettings()，
                //   与紧随其后的 InitializeEmulation 内部同一批调用交叉执行，
                //   输入设备/时间服务被并发重载 —— 竞态胜负决定游戏能否进去。
                //   核心在 InitializeEmulation 内部会自己调 ApplySettings（
                //   native.cpp 已含），此处只需静默交给核心。

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
        // ★ v4 修正：对齐 Core::SystemResultStatus 真实枚举（旧映射 1/2 写反）。
        //   Success=0, 1=ErrorNotInitialized, 2=ErrorGetLoader,
        //   3=ErrorSystemFiles, 4=ErrorSharedFont, 5=ErrorVideoCore,
        //   6=ErrorUnknown，7+ = ErrorLoader 基数 + 具体 Loader 错误。
        val reason = when (result) {
            1 -> "系统未初始化（核心状态异常，请重启应用再试）"
            2 -> "无法识别该 ROM（找不到可执行文件：格式不受支持或文件损坏）"
            3 -> "缺少系统文件（部分游戏需要固件，请在 设置 → Switch·Eden 安装固件）"
            4 -> "共享字体缺失（需安装官方固件 zip）"
            5 -> "视频核心初始化失败（GPU/驱动不兼容，可尝试换驱动或改用 Dynarmic 后端）"
            6 -> "核心未知错误"
            else -> if (result >= 7) "游戏加载失败（Loader 错误 ${result - 7}：密钥不匹配或 ROM 不完整）"
                    else "核心错误（代码 $result）"
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
        // ★★ v6 修复“左摇杆上下反了”：上游 InputOverlayDrawableJoystick
        //   明确注释 "Nintendo joysticks have y axis inverted"，发送的是
        //   realYAxis = -yAxis（触摸拖上 → axisY 负 → 发送正值）。
        //   即 native 侧约定 Y 向上为正；前端拖杆是屏幕坐标（上=负）
        //   → 必须取负对齐，旧实现直接透传导致上下颠倒。
        NativeInput.onOverlayJoystickEventImpl(0, 0, lx, -ly)   // 左摇杆
        NativeInput.onOverlayJoystickEventImpl(0, 1, rx, -ry)   // 右摇杆
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
        stopMotionSensorBridge()
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
        // ★★ 闪退修复（“启动 Switch 游戏 100% 闪退”根因）★★
        // 旧守卫 `if (!coreInitialized && !EdenNative.loaded) return` 是逻辑
        // 错误：NesApp.onCreate 里 probeAvailability() 已把 EdenNative.loaded
        // 置 true（只加载 .so，未建 NativeConfig），于是 EmulatorScreen 初始
        // 组合时先于 loadRom 运行的那次 applyCoreOptions（27 个 ed* 键连发）
        // 全部越过守卫 → applyEdenOption → NativeConfig.saveGlobalConfig() →
        // native 侧 global_config->SaveAllValues()，而 global_config 还是
        // nullptr（initializeGlobalConfig 尚未执行）→ Config::SaveValues()
        // 读 this->global（空指针 + 偏移 48）→ SIGSEGV，Java try/catch 拦不住
        // native 段错误 → 进程必死（堆栈：Config::SaveValues()+32 ←
        // AndroidConfig::SaveAllValues()+16 ← setCoreOption）。
        //
        // 正确语义：未完成 coreInitOnce 就绝不碰 NativeConfig —— 本次下发
        // 直接跳过即可（EmulatorScreen 在 loadRom 成功后会重放整表设置，
        // 见 EmulatorScreen “loadRom 完成后重放”分支，不会丢配置）。
        if (!EdenNative.loaded) return
        if (!coreInitialized) {
            android.util.Log.i(
                "EdenEngine",
                "setCoreOption($key) skipped: core not initialized yet " +
                    "(will be re-applied after loadRom)"
            )
            return
        }
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
            // ★ v4 修复：edDocked 的取值是 "0"/"1"（DropdownRow 序数），而
            //   Kotlin "1".toBoolean() == false —— 旧代码“底座模式”永远开不了
            //   （用户选底座实际写入掌机）。改为显式比较。
            "docked" -> NativeConfig.setBoolean("use_docked_mode", v == "1")
            // ★ v5：回退默认 15（Language::ChineseSimplified，简体中文）
            "language" -> NativeConfig.setInt("language_index", v.toIntOrNull() ?: 15)
            "region" -> NativeConfig.setInt("region_index", v.toIntOrNull() ?: 1)
            "gameSpeed" -> {
                _baseGameSpeed = (v.toIntOrNull() ?: 1).coerceIn(0, 4)
                if (_ffSpeed == 0) applyBaseGameSpeed()
            }

            // ---- v4 补全：性能 / 音频 / 体感（上游 settings.h 已暴露项） ----
            // "fast_cpu_time" CpuClock 序数：0=Normal 1=Boost 2=Overclock
            "cpuClock" -> NativeConfig.setInt("fast_cpu_time", v.toIntOrNull() ?: 0)
            // "fast_gpu_time" GpuClock 序数：0=Normal 1=Boost 2=Overclock
            "gpuClock" -> NativeConfig.setInt("fast_gpu_time", v.toIntOrNull() ?: 1)
            "syncCoreSpeed" -> NativeConfig.setBoolean("sync_core_speed", v.toBoolean())
            "speedLimitOn" -> NativeConfig.setBoolean("use_speed_limit", v.toBoolean())
            "speedLimit" -> NativeConfig.setShort("speed_limit", (v.toIntOrNull() ?: 100).coerceIn(0, 9999).toShort())
            "fsrSharpening" -> NativeConfig.setInt("fsr_sharpening_slider", v.toIntOrNull() ?: 0)
            "astcRecompression" -> NativeConfig.setInt("astc_recompression", v.toIntOrNull() ?: 0)
            "sampleShading" -> NativeConfig.setInt("sample_shading_fraction", v.toIntOrNull() ?: 0)
            "frameGen" -> NativeConfig.setBoolean("frame_gen", v.toBoolean())
            "frameGenTarget" -> NativeConfig.setInt("frame_gen_target_rate", v.toIntOrNull() ?: 0)
            "patchOldQcom" -> NativeConfig.setBoolean("patch_old_qcom_drivers", v.toBoolean())
            // "output_engine" AudioEngine 序数：0=Auto 2=SDL3 3=Null
            //   （Android 构建未编 Cubeb，选 1 会回退 Null —— 不提供该项）
            "outputEngine" -> NativeConfig.setInt("output_engine", v.toIntOrNull() ?: 2)
            // 体感：App 侧传感器桥（不进核心配置）
            "motion" -> setMotionSensorEnabled(appContext, v.toBoolean())

            // ---- 音频 ----
            "audioVolume" -> NativeConfig.setByte("volume", (v.toFloatOrNull() ?: 100f).coerceIn(0f, 100f).toInt().toByte())
            "audioMuted" -> NativeConfig.setBoolean("audio_muted", v.toBoolean())

            // ---- 网络/其他（记录型，暂无对应单一键） ----
            else -> android.util.Log.w("EdenEngine", "unknown eden option: $name=$value")
        }
        // 热应用运行时项（分辨率/精度/滤镜等）+ 落盘
        // ★ 批量事务中不逐键落盘：一次 applyCoreOptions 会连发 27+ 个
        //   setCoreOption，旧实现每键都全量重写 config.ini（27 次文件写
        //   + 27 次 Reload 类热应用，进游戏/改设置明显卡顿）—— 批内只改
        //   内存值，endCoreOptionsBatch 统一 applySettings + 落盘一次。
        if (batchingCoreOptions) return
        try {
            // ★★ v4 修复（启动期竞态）：applySettings() = System::ApplySettings()
            //   + HIDCore::ReloadInputDevices()。上游 App 全仓库从不调它；
            //   若在 System::Load 进行中调用（loadRom 后的重放、启动期改设置），
            //   ReloadInputDevices 会与 HID 服务初始化并发 → 启动失败/闪退
            //   （“大多数游戏进不去”的时序根因之一）。仅在游戏已真正运行
            //   （m_is_running=true，即 Load 完成后）才热应用；否则配置值已
            //   写入 Settings::values，核心在本次/下次启动读取时自然生效。
            if (NativeLibrary.isRunning()) {
                NativeLibrary.applySettings()
            }
            NativeConfig.saveGlobalConfig()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------
    // 设置批处理（EmulatorEngine.beginCoreOptionsBatch/endCoreOptionsBatch
    // 契约实现：批内只改内存，结束时统一热应用 + 一次落盘）
    // ------------------------------------------------------------------

    @Volatile private var batchingCoreOptions = false

    override fun beginCoreOptionsBatch() {
        batchingCoreOptions = true
    }

    override fun endCoreOptionsBatch() {
        if (!batchingCoreOptions) return
        batchingCoreOptions = false
        if (!coreInitialized) return
        try {
            // ★★ v4：同 applyEdenOption 尾部的 isRunning 门控 —— 启动期
            //   （System::Load 进行中，IsRunning=false）绝不热应用，避免
            //   ReloadInputDevices/Renderer 刷新与核心初始化竞态。
            if (NativeLibrary.isRunning()) {
                NativeLibrary.applySettings()
            }
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

        // 上游 NativeInput.Player1Device = 0 / ConsoleDevice = 8
        // （上游 EmulationActivity.onSensorChanged 末尾双路送数：Player1 手柄
        //  六轴 + 主机六轴各一次 —— 大量 Switch 游戏读的是 ConsoleDevice 通道）
        // ★★ v6.1：常量从类体第二个 companion 合并进来 —— Kotlin 规定每类
        //   最多一个 companion object，v6 误加第二个属非法源码，kapt stub
        //   生成损坏后在 CI 报出误导性的 duplicate class 编译错误。
        private const val MOTION_PLAYER1_PORT = 0
        private const val MOTION_CONSOLE_PORT = 8

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
