package com.nesstation.app.core.engine

import android.content.Context
import android.os.Build
import android.view.Surface
import com.nesstation.app.core.jni.PspNative
import com.nesstation.app.ui.emulator.BTN_A
import com.nesstation.app.ui.emulator.BTN_B
import com.nesstation.app.ui.emulator.BTN_L2
import com.nesstation.app.ui.emulator.BTN_L_SNES
import com.nesstation.app.ui.emulator.BTN_R2
import com.nesstation.app.ui.emulator.BTN_R_SNES
import com.nesstation.app.ui.emulator.BTN_SELECT
import com.nesstation.app.ui.emulator.BTN_START
import com.nesstation.app.ui.emulator.BTN_X
import com.nesstation.app.ui.emulator.BTN_Y
import com.nesstation.app.ui.emulator.BTN_DOWN
import com.nesstation.app.ui.emulator.BTN_LEFT
import com.nesstation.app.ui.emulator.BTN_RIGHT
import com.nesstation.app.ui.emulator.BTN_UP
import com.nesstation.app.ui.emulator.PspSettingsStore
import com.nesstation.app.ui.emulator.ensurePpssppEnvironment
import org.ppsspp.ppsspp.PspHostStub
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * ★★ PSP（PPSSPP 核心）进程内引擎 —— NesStation 统一集成 ★★
 *
 * 与 DC（libdccore → dlopen libflycast_libretro_android.so）、PSX
 * （libpsxcore → dlopen libpcsx_rearmed_libretro_android.so）完全同构的
 * dlopen 模式：libpspcore.so 运行时加载预编译官方 libppsspp_jni.so
 * （PPSSPP v1.20.4，三 ABI），并驱动它导出的 JNI 契约 —— 与官方
 * PpssppActivity 使用的入口逐字节一致：
 *
 *   NativeApp.init(...)                       boot（boot_filename = ROM 路径）
 *   NativeApp.audioConfig/audioInit           OpenSL 音频（核心内自含线程）
 *   NativeApp.setDisplayParameters            init 前的显示参数（默认倍率决策）
 *   PpssppActivity.registerCallbacks          帧命令宿主（PspHostStub 桩）
 *   PpssppActivity.runVulkanRenderLoop        在 NesStation 的 Surface 上跑
 *   PpssppActivity.requestExitVulkanRenderLoop 渲染循环退出（join EmuThread）
 *   NativeApp.keyDown/keyUp/joystickAxis/touch 输入注入
 *   SaveState.GetGamePrefix/SaveSlot/LoadSlot  即时存档（与 PPSSPP 自家
 *                                              暂停菜单槽位完全互通）
 *
 * 架构（推模型 —— 核心拥有渲染/模拟循环，前端只喂 Surface / 输入 / 生命周期）：
 *  1. [loadRom] 仅准备 memstick 目录树 + 强制 Vulkan 后端 + boot（阻塞、
 *     内部完成 dlopen → registerCallbacks → setDisplayParameters → init）；
 *  2. Surface 有效后（[setSurface]/[onSurfaceChanged]）启动 Vulkan 渲染
 *     循环 —— 核心在内部 spawn 自己的 EmuThread，帧呈现/限速全部内建；
 *  3. 音频由核心内部 OpenSL 直出（与 Azahar/PS2 等推模型核心一致，
 *     引擎无 AudioTrack）；
 *  4. 输入：NesStation 位掩码 → Android 标准键码注入（PPSSPP 默认
 *     Android 手柄映射表 defaultPadMapAndroid：A→⨯ / B→○ / X→□ / Y→△ /
 *     L1→L / R1→R / L2→PPSSPP 菜单 / R2→快进 / DPAD→十字键），虚拟摇杆
 *     → AXIS_X/Y；触摸 → NativeApp.touch（PPSSPP 自带虚拟按键覆盖层）。
 *
 * 设置：[setCoreOption]("Section/key", value) 直写 ppsspp.ini（经
 * PspSettingsStore），与主设置页"核心设置 → PSP"分区共用同一文件；
 * 部分键需要重启会话生效（PPSSPP 启动时读 ini）。
 *
 * 即时存档：核心自己的 SaveState 槽位（memstick/PSP/PPSSPP_STATE/
 * <DiscID>_<slot>.ppst），[saveState] 的 dst 参数仅做 UI 兼容标记
 * （同 Azahar/PS2 引擎做法）—— 槽位真实文件与 PPSSPP 原生菜单完全互通。
 */
class PspEngine private constructor() : EmulatorEngine, PspCoreEngine {

    override val frameBuffer = IntArray(1)

    private val running = AtomicBoolean(false)
    private var heartbeatThread: Thread? = null

    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0
    @Volatile private var loopStarted = false

    @Volatile override var isLoaded = false
        private set

    @Volatile private var _paused = false
    @Volatile private var lastErrorText = ""

    @Volatile private var lastRomPath: String? = null
    @Volatile private var lastSaveName: String? = null

    /** 复合键（"Section/key"）→ 配置值 的 ini 热更新集合（批量事务用）。 */
    private val pendingOptions = LinkedHashMap<String, String>()
    private val optionsLock = Any()

    // === Netplay（推模型核心不支持锁步 —— 接受但无效，与 Azahar/PS2 一致） ===
    @Volatile private var _frameHook: NetplayHook? = null
    override var frameHook: NetplayHook?
        get() = _frameHook
        set(value) { _frameHook = value }

    private val lifecycleLock = Any()

    /** App context — set by NesApp.onCreate so the engine can locate dirs. */
    @Volatile var appContext: Context? = null

    /**
     * PPSSPP 帧命令宿主桩（Toast 转发等）。
     * ★ 惰性创建：PspHostStub 是 ContextWrapper，需要宿主 Context 包装 ——
     *   boot 时（appContext 已就绪）首次构建。旧版“构造即建”会在 appContext
     *   赋值前执行，拿不到 Context（PSP 闪退修复 #2 的配套）。
     */
    @Volatile private var hostStub: PspHostStub? = null

    /** boot 前获取（或首次创建）宿主桩 —— 必须持有 appContext。 */
    private fun obtainHostStub(ctx: Context): PspHostStub {
        hostStub?.let { return it }
        synchronized(this) {
            hostStub?.let { return it }
            return PspHostStub(ctx).also { hostStub = it }
        }
    }

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
                    "libpspcore 加载失败（当前进程 ABI 无对应 libppsspp_jni.so，或库缺失）"
                )
            }
        } catch (e: Throwable) {
            Availability(false, "PPSSPP 核心加载异常: ${e.message}")
        }
        probedAvailability = result
        return result
    }

    override fun isCoreAvailable(): Boolean = probeAvailability().available

    override fun ensureLoaded(): Boolean = probeAvailability().available

    // ------------------------------------------------------------------
    // 环境准备
    // ------------------------------------------------------------------

    private fun requireContext(): Context {
        val ctx = appContext
            ?: PspNative.appContext
            ?: throw IllegalStateException("PspEngine: app context not initialised")
        if (appContext == null) appContext = ctx
        return ctx
    }

    // ------------------------------------------------------------------
    // loadRom / boot
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
        try {
            val ctx = requireContext()

            // 1) memstick 目录树 + memstick_dir.txt（PPSSPP scoped storage
            //    引导逻辑 —— 缺失时核心首启会弹存储选择器）。
            ensurePpssppEnvironment(ctx)

            // 2) 强制 Vulkan 后端：本宿主只驱动 runVulkanRenderLoop（官方
            //    JavaGL/OpenGL 路径需要 GLSurfaceView 宿主，进程内引擎不适用）。
            //    PPSSPP 在 release 构建里 Vulkan 不可用时会自行回退 GL ——
            //    boot() 会经 queryConfig("androidJavaGL") 检测并给出可读错误。
            PspSettingsStore.writeInt(ctx, PspSettingsStore.GRAPHICS_BACKEND, 3)

            // 3) 组装 boot 参数（与官方 PpssppActivity.Initialize 逐项对齐）。
            val appInfo = ctx.applicationInfo
            val extStorageDir = try {
                android.os.Environment.getExternalStorageDirectory().absolutePath
            } catch (_: Throwable) { "" }
            val extFilesDir = try {
                ctx.getExternalFilesDir(null)?.absolutePath ?: ""
            } catch (_: Throwable) { "" }
            val dataDir = ctx.filesDir.absolutePath
            val cacheDir = ctx.cacheDir.absolutePath
            val model = "${Build.MANUFACTURER}:${Build.MODEL}"
            val langRegion = try {
                val loc = Locale.getDefault()
                "${loc.language}_${loc.country}"
            } catch (_: Throwable) { "en_US" }

            val dm = displayMetrics(ctx)
            // 宿主桩：ContextWrapper(appContext) —— 核心的 registerCallbacks/
            // TextRenderer.allocFont/ContentUri 等回调都拿它当 Activity 用。
            val stub = obtainHostStub(ctx)
            // boot_filename 经官方 parse_args 传递 —— 含空格路径用引号包裹
            //（parse_args 的引号/反斜杠感知切分，v1.20.4 app-android.cpp:672）。
            val romPath = rom.absolutePath
            val shortcutParam =
                if (romPath.contains(' ') || romPath.contains('\t')) "\"$romPath\""
                else romPath

            lastRomPath = romPath
            lastSaveName = null

            // 4) boot：dlopen libppsspp_jni.so → 手动 JNI_OnLoad（gJvm/
            //    gClassLoader/线程 attach 钩子 —— dlopen 不会自动触发，缺失
            //    即渲染线程 SIGSEGV 闪退）→ registerCallbacks(stub)
            //    → setDisplayParameters → audioConfig → NativeApp.init（内部
            //    创建 AndroidVulkanContext）→ audioInit（OpenSL 线程）。
            val ok = PspNative.boot(
                stub,
                model, 0 /* DEVICE_TYPE_MOBILE */, langRegion, appInfo.sourceDir ?: "",
                dataDir, extStorageDir, extFilesDir,
                appInfo.nativeLibraryDir ?: "", "", cacheDir,
                shortcutParam, "nesstation", Build.VERSION.SDK_INT, Build.BOARD ?: "",
                dm[0].toInt(), dm[1].toInt(), dm[2].toInt(), dm[3], 0, 0
            )
            if (!ok) {
                lastErrorText = PspNative.lastError().ifBlank { "PPSSPP 启动失败" }
                return false
            }
            if (!PspNative.vulkanActive()) {
                lastErrorText = PspNative.lastError().ifBlank {
                    "Vulkan 渲染路径未激活（设备不支持或后端配置错误）"
                }
                return false
            }

            lastErrorText = ""
            isLoaded = true
            _paused = false
            running.set(true)

            // 官方生命周期对齐：PpssppActivity.onStart → NativeApp.resume()
            //（首个 resume 携带 "first" 标记 —— 触发核心的自动读档检查与
            // 音频恢复，缺失会导致 auto-load savestate 行为不一致）。
            try { PspNative.resume() } catch (_: Throwable) {}

            // FPS HUD 心跳（推模型核心，onFrame 计数 = 会话存活心跳）。
            heartbeatThread?.interrupt()
            heartbeatThread = thread(name = "psp-heartbeat", isDaemon = true) {
                try {
                    while (running.get() && isLoaded) {
                        try { onFrame() } catch (_: Throwable) {}
                        Thread.sleep(16)
                    }
                } catch (_: InterruptedException) {}
            }
            android.util.Log.i("PspEngine", "PPSSPP booted: $romPath")
            return true
        } catch (t: Throwable) {
            android.util.Log.w("PspEngine", "loadRom failed", t)
            lastErrorText = t.message ?: t.javaClass.simpleName
            return false
        }
    }

    /** [displayXres, displayYres, dpi, refreshHz]（boot 参数用）。 */
    private fun displayMetrics(ctx: Context): FloatArray {
        return try {
            val dm = ctx.resources.displayMetrics
            val refresh = try {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                @Suppress("DEPRECATION")
                wm.defaultDisplay.refreshRate
            } catch (_: Throwable) { 60f }
            floatArrayOf(
                dm.widthPixels.toFloat().coerceAtLeast(1f),
                dm.heightPixels.toFloat().coerceAtLeast(1f),
                dm.densityDpi.toFloat().coerceAtLeast(1f),
                refresh
            )
        } catch (_: Throwable) {
            floatArrayOf(1280f, 720f, 320f, 60f)
        }
    }

    // ------------------------------------------------------------------
    // Surface 生命周期
    // ------------------------------------------------------------------

    override fun setSurface(surface: Surface?) {
        synchronized(lifecycleLock) {
            this.surface = surface
            if (surface == null) {
                if (loopStarted) {
                    try { PspNative.setSurface(null, 0, 0) } catch (_: Throwable) {}
                    loopStarted = false
                }
            } else if (isLoaded && !loopStarted && surfaceW > 0 && surfaceH > 0) {
                startRenderLoop()
            }
        }
    }

    override fun onSurfaceChanged(surface: Surface?, width: Int, height: Int) {
        synchronized(lifecycleLock) {
            this.surface = surface
            surfaceW = width
            surfaceH = height
            if (surface != null && isLoaded && !loopStarted &&
                width > 0 && height > 0) {
                startRenderLoop()
            }
        }
    }

    override fun onSurfaceDestroyed() {
        synchronized(lifecycleLock) {
            surface = null
            if (loopStarted) {
                try { PspNative.setSurface(null, 0, 0) } catch (_: Throwable) {}
                loopStarted = false
            }
        }
    }

    /** 必须持有 lifecycleLock 调用。 */
    private fun startRenderLoop() {
        val s = surface ?: return
        try {
            PspNative.setSurface(s, surfaceW, surfaceH)
            loopStarted = true
            android.util.Log.i("PspEngine", "Vulkan render loop started ${surfaceW}x$surfaceH")
        } catch (t: Throwable) {
            android.util.Log.w("PspEngine", "startRenderLoop failed", t)
            lastErrorText = t.message ?: "渲染循环启动失败"
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun setPaused(paused: Boolean) {
        if (_paused == paused) return
        _paused = paused
        try {
            if (paused) PspNative.pause() else PspNative.resume()
        } catch (t: Throwable) {
            android.util.Log.w("PspEngine", "setPaused($paused) failed", t)
        }
    }

    override fun reset(hard: Boolean) {
        // PPSSPP 未导出热重置入口 —— 用"卸载 + 同 ROM 重新引导"实现完整重置
        //（与用户手动退出重进等价）。持久savestate/存档目录不受影响。
        val rom = lastRomPath ?: return
        synchronized(lifecycleLock) {
            try {
                unloadLocked()
                val f = File(rom)
                if (f.exists()) {
                    // loadRom 自身会 synchronized(lifecycleLock) —— 重入安全
                    //（Kotlin synchronized 可重入）。
                    loadRom(f, "", "", {})
                }
            } catch (t: Throwable) {
                android.util.Log.w("PspEngine", "reset failed", t)
                lastErrorText = t.message ?: "重置失败"
            }
        }
    }

    override fun unload() = synchronized(lifecycleLock) { unloadLocked() }

    override fun shutdown() = synchronized(lifecycleLock) { unloadLocked() }

    /** 必须持有 lifecycleLock 调用。 */
    private fun unloadLocked() {
        running.set(false)
        heartbeatThread?.interrupt()
        heartbeatThread = null
        try {
            PspNative.setSurface(null, 0, 0)
        } catch (_: Throwable) {}
        loopStarted = false
        surface = null
        try {
            PspNative.unload()
        } catch (t: Throwable) {
            android.util.Log.w("PspEngine", "unload failed", t)
        }
        isLoaded = false
        _paused = false
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /** NesStation 位掩码 → Android 键码（PPSSPP defaultPadMapAndroid 语义）。 */
    private fun bitToKeycode(bit: Int): Int? = when (bit) {
        BTN_A            -> 96   // KEYCODE_BUTTON_A   → ⨯ CROSS
        BTN_B            -> 97   // KEYCODE_BUTTON_B   → ○ CIRCLE
        BTN_X            -> 99   // KEYCODE_BUTTON_X   → □ SQUARE
        BTN_Y            -> 100  // KEYCODE_BUTTON_Y   → △ TRIANGLE
        BTN_SELECT       -> 109  // KEYCODE_BUTTON_SELECT
        BTN_START        -> 108  // KEYCODE_BUTTON_START
        BTN_UP           -> 19   // KEYCODE_DPAD_UP
        BTN_DOWN         -> 20   // KEYCODE_DPAD_DOWN
        BTN_LEFT         -> 21   // KEYCODE_DPAD_LEFT
        BTN_RIGHT        -> 22   // KEYCODE_DPAD_RIGHT
        BTN_L_SNES       -> 102  // KEYCODE_BUTTON_L1  → L
        BTN_R_SNES       -> 103  // KEYCODE_BUTTON_R1  → R
        BTN_L2           -> 104  // KEYCODE_BUTTON_L2  → PPSSPP 暂停菜单
        BTN_R2           -> 105  // KEYCODE_BUTTON_R2  → PPSSPP 快进
        else -> null            // L3/R3 等扩展位 PSP 无对应，忽略
    }

    /** 上次推送的键码集合（边沿检测：升起按下 / 落下释放）。 */
    private val pressedKeys = HashSet<Int>()

    override fun setPad1(bits: Int) {
        if (!isLoaded) return
        synchronized(pressedKeys) {
            var mask = bits
            // 展开所有置位位
            while (mask != 0) {
                val bit = mask and -mask
                mask = mask and bit.inv()
                val kc = bitToKeycode(bit) ?: continue
                if (pressedKeys.add(kc)) {
                    try { PspNative.keyDown(kc) } catch (_: Throwable) {}
                }
            }
            // 释放已不再按下的键
            val it = pressedKeys.iterator()
            val released = ArrayList<Int>()
            while (it.hasNext()) {
                val kc = it.next()
                val bit = keycodeToBit(kc)
                if (bit == 0 || (bits and bit) == 0) {
                    released.add(kc)
                }
            }
            for (kc in released) {
                pressedKeys.remove(kc)
                try { PspNative.keyUp(kc) } catch (_: Throwable) {}
            }
        }
    }

    override fun setPad2(bits: Int) {
        // PSP 单人掌机 —— P2 无效（与 PS2 引擎一致）。
    }

    private fun keycodeToBit(kc: Int): Int = when (kc) {
        96 -> BTN_A; 97 -> BTN_B; 99 -> BTN_X; 100 -> BTN_Y
        109 -> BTN_SELECT; 108 -> BTN_START
        19 -> BTN_UP; 20 -> BTN_DOWN; 21 -> BTN_LEFT; 22 -> BTN_RIGHT
        102 -> BTN_L_SNES; 103 -> BTN_R_SNES; 104 -> BTN_L2; 105 -> BTN_R2
        else -> 0
    }

    /** 双摇杆透传：PSP 只有左摇杆（AXIS_X/Y），右摇杆忽略。 */
    override fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float) {
        if (!isLoaded) return
        try { PspNative.setAnalog(lx.coerceIn(-1f, 1f), ly.coerceIn(-1f, 1f)) } catch (_: Throwable) {}
    }

    /**
     * 触摸直通（PPSSPP 自带虚拟按键覆盖层需要原始触摸流）。
     * [androidAction] 为 MotionEvent.actionMasked。
     */
    override fun onTouch(x: Float, y: Float, androidAction: Int, pointerId: Int) {
        if (!isLoaded) return
        // NativeApp.processTouchEvent 的 code 语义：
        //   DOWN/POINTER_DOWN → 2, MOVE → 1, UP/POINTER_UP/CANCEL → 4
        val code = when (androidAction) {
            0, 5 -> 2        // ACTION_DOWN / ACTION_POINTER_DOWN
            2 -> 1           // ACTION_MOVE
            1, 6, 3 -> 4     // ACTION_UP / POINTER_UP / CANCEL
            else -> 0
        }
        if (code == 0) return
        try { PspNative.onTouch(x, y, code, pointerId) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------
    // 即时存档（核心自有 SaveState 槽位 —— 与 PPSSPP 原生菜单完全互通）
    // ------------------------------------------------------------------

    /**
     * memstick/PSP/PPSSPP_STATE 目录（核心槽位 .ppst 文件所在）。
     * ★ 层级修正：memstick = <ext>/PSP，核心状态在 memstick 的 PSP/ 子层
     *   下（PPSSPP 约定 memstick/PSP/PPSSPP_STATE）—— 旧版少了一层 PSP，
     *   永远扫不到核心真实写入的 .ppst（存档槽位大小/时间显示错误的根因）。
     */
    private fun ppssppStateDir(): File? {
        val ctx = appContext ?: PspNative.appContext ?: return null
        return try {
            val extFiles = ctx.getExternalFilesDir(null)
                ?: File(ctx.filesDir, "ext")
            File(File(File(extFiles, "PSP"), "PSP"), "PPSSPP_STATE").takeIf { it.isDirectory }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 找到 [slot] 槽位最近写入的核心状态文件（<DiscID>_<slot>.ppst，
     * DiscID 前缀由核心侧 SaveState::GetGamePrefix 决定，Kotlin 侧按
     * "_<slot>.ppst" 后缀 + 最近 mtime 匹配）。
     */
    private fun newestSlotFile(slot: Int, newerThanMs: Long = 0L): File? {
        val dir = ppssppStateDir() ?: return null
        val suffix = "_${slot}.ppst"
        return dir.listFiles { f -> f.name.endsWith(suffix) && f.length() > 0 }
            ?.filter { it.lastModified() >= newerThanMs }
            ?.maxByOrNull { it.lastModified() }
    }

    override fun saveState(slot: Int, dst: File): Boolean {
        if (!isLoaded) return false
        return try {
            val before = System.currentTimeMillis() - 2000
            val ok = PspNative.saveState(slot)
            if (!ok) {
                lastErrorText = PspNative.lastError().ifBlank { "存档失败" }
                return false
            }
            // 标记文件 = 真实 .ppst 的字节拷贝（UI 槽位列表的大小/时间从此
            // 真实，读档前置检查也对得上 —— 同 AzaharEngine 的标记策略）。
            // 大游戏压缩落盘可能秒级，轮询等待真实文件出现。
            var real: File? = null
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                real = newestSlotFile(slot, before)
                if (real != null) break
                Thread.sleep(150)
            }
            try {
                dst.parentFile?.mkdirs()
                if (real != null) {
                    real.copyTo(dst, overwrite = true)
                } else if (!dst.exists()) {
                    dst.createNewFile()
                }
            } catch (t: Throwable) {
                android.util.Log.w("PspEngine", "saveState marker copy failed", t)
                try { if (!dst.exists()) dst.createNewFile() } catch (_: Throwable) {}
            }
            lastErrorText = ""
            true
        } catch (t: Throwable) {
            android.util.Log.w("PspEngine", "saveState($slot)", t)
            lastErrorText = t.message ?: "存档异常"
            false
        }
    }

    override fun loadState(slot: Int, src: File): Boolean {
        if (!isLoaded) return false
        return try {
            // 直接按核心槽位读（DiscID 前缀核心侧已知）；src 是 NesStation
            // 的标记文件，仅用于 UI 存在性检查 —— 核心真实槽位缺失时原生
            // 调用会返回失败（含跨游戏标记误用的情况，避免错位读档）。
            val ok = PspNative.loadState(slot)
            if (!ok) {
                lastErrorText = PspNative.lastError().ifBlank { "读档失败：核心槽位缺失或版本不兼容" }
                return false
            }
            lastErrorText = ""
            true
        } catch (t: Throwable) {
            android.util.Log.w("PspEngine", "loadState($slot)", t)
            lastErrorText = t.message ?: "读档异常"
            false
        }
    }

    // ------------------------------------------------------------------
    // 设置（ppsspp.ini 直写）
    // ------------------------------------------------------------------

    override fun setCoreOption(key: String, value: String) {
        val ctx = appContext ?: PspNative.appContext ?: return
        val dot = key.indexOf('/')
        if (dot <= 0 || dot >= key.length - 1) return
        val section = key.substring(0, dot)
        val name = key.substring(dot + 1)
        try {
            PspSettingsStore.writeRaw(
                com.nesstation.app.ui.emulator.ppssppIniFile(ctx),
                PspSettingsStore.Key(section, name), value
            )
        } catch (t: Throwable) {
            android.util.Log.w("PspEngine", "setCoreOption($key) failed", t)
        }
    }

    override fun beginCoreOptionsBatch() {
        synchronized(optionsLock) { pendingOptions.clear() }
    }

    override fun endCoreOptionsBatch() {
        val batch: Map<String, String>
        synchronized(optionsLock) {
            batch = LinkedHashMap(pendingOptions)
            pendingOptions.clear()
        }
        if (batch.isEmpty()) return
        for ((k, v) in batch) setCoreOption(k, v)
    }

    override fun setFastForward(speed: Int) {
        // 官方 so 未导出 PSP_CoreParameter —— 快进由 PPSSPP 自家热键
        // （R2/右扳机）承担；此处仅记录意图。
        try { PspNative.setFastForward(speed > 0) } catch (_: Throwable) {}
    }

    override fun setVideoFilter(filter: Int) { /* PPSSPP 自带后处理链，由 ini 控制 */ }

    override fun setHighQualityScaling(enabled: Boolean) { /* 核心直绘 Surface，无前端缩放 */ }

    override fun setRegion(region: Int) { /* 由 ini/SystemParam 决定 */ }

    override fun setSampleRate(rate: Int) { /* OpenSL 音频由核心自管理 */ }

    override fun setSaveName(name: String) {
        // PPSSPP 槽位文件按 DiscID 命名，无需前端 save name —— 仅记录。
        lastSaveName = name
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    override fun videoWidth(): Int = if (surfaceW > 0) surfaceW else 480

    override fun videoHeight(): Int = if (surfaceH > 0) surfaceH else 272

    override fun captureFrame(): FrameCapture? = null // Vulkan 直绘，无帧回读

    override fun realtimeFps(): Double = 0.0 // 推模型 —— UI 回退 onFrame 计数

    override fun lastError(): String = lastErrorText

    // ------------------------------------------------------------------
    // 单例
    // ------------------------------------------------------------------

    companion object {
        @Volatile private var instance: PspEngine? = null

        fun get(): PspEngine = instance ?: synchronized(this) {
            instance ?: PspEngine().also { instance = it }
        }
    }
}

/**
 * PSP（PPSSPP）引擎扩展契约 —— EmulatorScreen 经此接口下发摇杆与触摸
 * （与 AzaharCoreEngine 同款模式）。
 */
interface PspCoreEngine : EmulatorEngine {

    /** libpspcore.so / libppsspp_jni.so 是否可用（probeAvailability 缓存）。 */
    fun isCoreAvailable(): Boolean

    /** 双摇杆：左摇杆 (lx, ly)，右摇杆 (rx, ry)（PSP 无右摇杆，忽略）。 */
    fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float)

    /** 原始触摸直通（PPSSPP 自带虚拟按键）。[androidAction] = MotionEvent.actionMasked。 */
    fun onTouch(x: Float, y: Float, androidAction: Int, pointerId: Int)
}
