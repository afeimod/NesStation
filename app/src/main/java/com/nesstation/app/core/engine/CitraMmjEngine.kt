package com.nesstation.app.core.engine

import android.content.Context
import android.view.Choreographer
import android.view.Surface
import com.nesstation.app.core.jni.CitraMmjNative
import org.citra.emu.NativeLibrary
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * ★★ Citra MMJ（3DS）核心引擎 —— NesStation 集成（本轮新增）★★
 *
 * 需求原话："源码里我上传了一个citra的apk.zip，将它集成在3ds核心里用于
 * 选择核心，就像nds的激烈核心一样，全部设置加入进去"。
 *
 * 集成模式 = DraStic / Azahar 同款：vendored .so（libcitra_mmj.so =
 * Citra_MMJ_20250220.apk 的 libmain.so）+ 原包名 JNI 契约
 * （org.citra.emu.NativeLibrary）+ 引擎类驱动生命周期。
 *
 * 架构（推模型，与 AzaharEngine 同构）：
 *  1. [loadRom]：建用户目录树（<filesDir>/citra_mmj/）→ SetUserPath →
 *     loadConfig → 应用 MMJ 设置（coreOptions）+ 输入映射配置 →
 *     SurfaceChanged → 专用线程 Run(path)（阻塞直至退出）；
 *  2. Choreographer 帧回调驱动 doFrame（呈现）；
 *  3. 暂停/恢复 PauseEmulation/ResumeEmulation；停止 StopEmulation。
 *
 * 输入（so 反汇编实证的输入管理器模型）：
 *  - 输入管理器把 KeyEvent/MoveEvent/InputEvent 的 key 与**配置键的值**
 *    匹配后分发（配置键：button_a/b/x/y/l/r/start/select/zl/zr/home/
 *    up/down/left/right、circle_pad_*、c_stick_*）；
 *  - 本引擎在启动前把这些配置键写入自选 id（MMJ_ID_*），按键统一经
 *    KeyEvent(id, 1/0) 下发；
 *  - 摇杆经 MoveEvent(轴码, 带符号值)（负值 → 核心按 key+0x1000 匹配
 *    反向槽 —— circle_pad_up = 轴码+0x1000，circle_pad_down = 轴码）；
 *  - 兜底：A/B/X/Y/L/R/十字键同时经 InputEvent(小索引, value) 下发
 *    （覆盖表索引通道，双通道确保至少一条命中）；
 *  - 底屏触摸经 TouchEvent（action 位掩码：1=按下 / 2=抬起 / 4=移动）。
 *
 * 即时存档：MMJ so 未导出 SaveState/LoadState JNI（nm 实测），saveState
 * 诚实返回 false 并给出说明（UI 提示改用 Azahar 核心）。
 */
class CitraMmjEngine private constructor() : EmulatorEngine, AzaharCoreEngine {

    companion object {
        // ---- 自选输入 id（写入 MMJ 配置键；启动前经 setConfigInteger 固化） ----
        // 按键（KeyEvent 通道）
        private const val MMJ_ID_A = 1001
        private const val MMJ_ID_B = 1002
        private const val MMJ_ID_X = 1003
        private const val MMJ_ID_Y = 1004
        private const val MMJ_ID_L = 1005
        private const val MMJ_ID_R = 1006
        private const val MMJ_ID_START = 1007
        private const val MMJ_ID_SELECT = 1008
        private const val MMJ_ID_ZL = 1009
        private const val MMJ_ID_ZR = 1010
        private const val MMJ_ID_HOME = 1011
        private const val MMJ_ID_UP = 1012
        private const val MMJ_ID_DOWN = 1013
        private const val MMJ_ID_LEFT = 1014
        private const val MMJ_ID_RIGHT = 1015
        // 摇杆轴（MoveEvent 通道；负向槽 = 轴码 + 0x1000）
        private const val MMJ_AXIS_CPAD_Y = 3000   // +下 / -上（circle_pad_down=3000, up=7096）
        private const val MMJ_AXIS_CPAD_X = 3002   // +右 / -左
        private const val MMJ_AXIS_CSTICK_Y = 3004
        private const val MMJ_AXIS_CSTICK_X = 3006
        private const val MMJ_NEG = 0x1000

        // InputEvent 兜底通道的小索引（citra Android overlay 约定）
        private const val IDX_A = 0
        private const val IDX_B = 1
        private const val IDX_X = 2
        private const val IDX_Y = 3
        private const val IDX_UP = 4
        private const val IDX_DOWN = 5
        private const val IDX_LEFT = 6
        private const val IDX_RIGHT = 7
        private const val IDX_L = 8
        private const val IDX_R = 9
        private const val IDX_START = 10
        private const val IDX_SELECT = 11

        // 项目位布局（EmulatorScreen 直传：A=bit0, B=bit1, Select=2, Start=3,
        // U/D/L/R=4..7, X=8, Y=9, L=10, R=11；扩展位 ZL=16, ZR=17, HOME=19）
        private const val BIT_A = 0x01
        private const val BIT_B = 0x02
        private const val BIT_SELECT = 0x04
        private const val BIT_START = 0x08
        private const val BIT_UP = 0x10
        private const val BIT_DOWN = 0x20
        private const val BIT_LEFT = 0x40
        private const val BIT_RIGHT = 0x80
        private const val BIT_X = 0x100
        private const val BIT_Y = 0x200
        private const val BIT_L = 0x400
        private const val BIT_R = 0x800
        private const val BIT_ZL = 0x10000
        private const val BIT_ZR = 0x20000
        private const val BIT_HOME = 0x80000

        @Volatile private var instance: CitraMmjEngine? = null
        fun get(): CitraMmjEngine = instance ?: synchronized(this) {
            instance ?: CitraMmjEngine().also { instance = it }
        }
    }

    // ------------------------------------------------------------------
    // 基础状态
    // ------------------------------------------------------------------

    override val frameBuffer: IntArray get() = IntArray(0)
    override val isLoaded: Boolean get() = _loaded
    @Volatile private var _loaded = false

    @Volatile private var appContext: Context? = null
    @Volatile private var romPath: String? = null
    @Volatile private var lastErrorText = ""
    @Volatile private var _paused = false
    @Volatile private var _ffSpeed = 0

    private val lifecycleLock = Any()
    private var emuThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0

    /**
     * ★★ Surface attach 状态门（修复 SurfaceDestroyed use-after-free 崩溃）★★
     *
     * 崩溃栈（Redmi/Socrates AQ3A.250226.002 / OS3.0.5.0.VMKCNXM）:
     * ```
     * #0 ANativeWindow_release+4 (libandroid.so)
     * #1 Java_org_citra_emu_NativeLibrary_SurfaceDestroyed+24 (libcitra_mmj.so)
     * #4 ca.h.A+130   (= CitraMmjEngine.cleanupLocked)
     * #6 ca.h.unload+6 (= CitraMmjEngine.unload)
     * ```
     *
     * 病因：MMJ 原生 SurfaceDestroyed → ANativeWindow_release **不做幂等检查**
     * （释放后置空，但若 Java 侧再次调 SurfaceDestroyed 仍会触发对野指针/
     * 已释放窗口的 release → SIGSEGV at ANativeWindow_release+4）。
     *
     * Java 侧的 setSurface(null) / onSurfaceDestroyed() / cleanupLocked() 三处
     * 都会无条件调 SurfaceDestroyed；EmulatorScreen.surfaceDestroyed 回调里
     * 又连调 onSurfaceDestroyed()+setSurface(null) 两次 + 退出 unload() 第三次
     * → 同一个 ANativeWindow 被 release 2~3 次 → 必崩。
     *
     * 修复：用本标志守门 —— 只在确实 attached 时才 SurfaceDestroyed，并立即
     * 重置；SurfaceChanged 成功后置 true。保证一个 attach 周期仅一次 release。
     */
    @Volatile private var surfaceAttached: Boolean = false

    /**
     * ★★★ 启动窗口门（本轮，与 AzaharEngine 同款根治）★★★
     *
     * SurfaceChanged → Run() 内 EmuWindow 构造完成之间，SurfaceDestroyed
     * 会把原生全局窗口置 NULL → Run() 构造窗口读到 null → 原生 abort。
     * 本标志覆盖整个启动窗口（含核心 boot 的全部时长），期间
     * setSurface(null) / cleanup 一律不得调 SurfaceDestroyed。
     * 清除时机：Run() 返回（finally）或各早退路径。
     */
    @Volatile private var nativeBooting = false

    private var choreographer: Choreographer? = null
    private var frameCallback: Choreographer.FrameCallback? = null

    /** MMJ 设置（key = 配置键名，如 "resolution_factor"）。 */
    private val coreOptions = LinkedHashMap<String, String>()

    /** 输入轴去重（避免重复发 MoveEvent）。[lx, ly, rx, ry]。 */
    private val stickLast = FloatArray(4)

    override fun ensureLoaded(): Boolean = CitraMmjNative.ensureLoaded()

    fun probeAvailability(): Pair<Boolean, String?> {
        val ok = ensureLoaded()
        return if (ok) true to null
        else false to "Citra MMJ 核心库不可用（仅 arm64；当前进程无 ARM64 支持）"
    }

    override fun isCoreAvailable(): Boolean = ensureLoaded()

    // ------------------------------------------------------------------
    // 目录 / 配置
    // ------------------------------------------------------------------

    private fun userDir(): String {
        val ctx = appContext
            ?: throw IllegalStateException("CitraMmjEngine: app context not initialised")
        return File(ctx.filesDir, "citra_mmj").apply { mkdirs() }.absolutePath
    }

    private fun ensureUserDirTree() {
        val root = File(userDir())
        for (rel in listOf(
            "nand", "nand/title", "nand/data", "sdmc", "sdmc/1ds", "sysdata",
            "config", "log", "states", "cheats", "shaders", "dump", "sdmc/Nintendo 3DS"
        )) {
            try { File(root, rel).mkdirs() } catch (_: Throwable) {}
        }
        // ★★ v1.3：系统文件种子（“启动游戏闪退”根治）—— 见 seedMmjSystemAssets。
        seedMmjSystemAssets()
        // 密钥/字体种子：复用 Azahar 已放置的 sysdata（aes_keys.txt 等，兜底通道）。
        try {
            val azSysdata = File(File(appContext!!.filesDir, "azahar"), "sysdata")
            val dst = File(root, "sysdata")
            for (name in listOf("aes_keys.txt", "boot9.bin", "seeddb.bin")) {
                val src = File(azSysdata, name)
                val out = File(dst, name)
                if (src.isFile && src.length() > 0 && !out.isFile) {
                    src.copyTo(out, overwrite = true)
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * ★★ v1.3：MMJ 系统文件种子 —— “Citra MMJ 启动游戏闪退”的根治修复 ★★
     *
     * 根因（原版 Citra_MMJ_20250220.apk 反编译 + so 字符串实证）：MMJ 原生
     * 核心启动游戏时从**用户目录**读 3DS 必需系统文件 ——
     *   - sysdata/aes_keys.txt（ROM 解密密钥，加密 ROM 必需）
     *   - sysdata/cbf_*.bcfnt（系统共享字体，游戏内文字渲染必需）
     *   - nand/...（系统标题 0004009b/00010402 + config savegame
     *     00010017 —— Citra 引导期强制读取）
     *   - sdmc/3ds/dspfirm.cdc（DSP 固件，音频）
     *   - config/config-games.ini（per-game 兼容配置）
     * 原版 APK 的 d1.a.E(Context) 在启动时把这些内容从 APK assets 释放到
     * 用户目录（overwrite=FALSE 幂等）；NesStation 集成只建了空目录 ——
     * 核心启动即读不到系统文件 → 原生断言 → SIGABRT/SIGTRAP 闪退。
     *
     * 修复：把原版 assets 的必需集打进 app/src/main/assets/mmj 目录（sysdata/
     * nand/sdmc/config/shaders，25 文件 ~13MB），loadRom 时递归释放到
     * <filesDir>/citra_mmj/ 对应位置。已存在且非空的文件跳过（对齐原版
     * overwrite=FALSE 语义，二次启动零拷贝、纯 list 遍历）。
     */
    private fun seedMmjSystemAssets() {
        val ctx = appContext ?: return
        try {
            val am = ctx.assets
            val root = File(ctx.filesDir, "citra_mmj")
            var copied = 0
            fun walk(assetDir: String, outDir: File) {
                val entries = try { am.list(assetDir) } catch (_: Throwable) { null } ?: return
                if (entries.isEmpty()) return
                try { outDir.mkdirs() } catch (_: Throwable) {}
                for (name in entries) {
                    val assetPath = if (assetDir.isEmpty()) name else "$assetDir/$name"
                    val outFile = File(outDir, name)
                    val children = try { am.list(assetPath) } catch (_: Throwable) { null }
                    if (!children.isNullOrEmpty()) {
                        walk(assetPath, outFile)
                    } else {
                        // 幂等：已存在且非空跳过（原版 overwrite=FALSE 语义）
                        if (outFile.isFile && outFile.length() > 0L) continue
                        try {
                            am.open(assetPath).use { ins ->
                                java.io.FileOutputStream(outFile).use { fos ->
                                    ins.copyTo(fos)
                                }
                            }
                            copied++
                        } catch (t: Throwable) {
                            android.util.Log.w("CitraMmjEngine",
                                "seed asset failed: $assetPath", t)
                        }
                    }
                }
            }
            walk("mmj", root)
            if (copied > 0) {
                android.util.Log.i("CitraMmjEngine",
                    "seeded $copied MMJ system files (first launch)")
            }
        } catch (t: Throwable) {
            android.util.Log.w("CitraMmjEngine", "seedMmjSystemAssets failed", t)
        }
    }

    /**
     * ★★ MMJ 配置键白名单（反汇编 libcitra_mmj.so 字面量池逐一核实）★★
     *
     * MMJ 核心的 setConfigInteger/Boolean/String JNI 内部是“键→枚举”switch：
     * 传入【不在白名单里的键】会落入 UNREACHABLE() → brk #1 → SIGTRAP 崩溃
     * （即用户崩溃栈 #00 pc 0x282234，BuildId 14a21e39…完全对应）。
     * 旧实现把 button_a/b/x/y/l/r 写进 applyInputConfig —— 这 6 个键在 so
     * 里根本不存在（原 MMJ APK 的 A/B/X/Y/L/R 只能经 InputEvent 索引通道
     * 下发：A=0/B=1/X=2/Y=3/L=8/R=9，与本项目 IDX_* 常量一致），启动即闪退。
     * 此处按类型维护三张白名单，任何未知键在 Java 层拦下并打日志，
     * 保证未来 UI 新增键名错误时只丢功能不闪退。
     */
    private val MMJ_INT_KEYS = setOf(
        // 输入映射（KeyEvent/MoveEvent 通道）—— button_a/b/x/y/l/r 有意缺席！
        "button_start", "button_select", "button_zl", "button_zr", "button_home",
        "button_up", "button_down", "button_left", "button_right",
        "button_debug",
        "circle_pad_down", "circle_pad_up", "circle_pad_right", "circle_pad_left",
        "c_stick_down", "c_stick_up", "c_stick_right", "c_stick_left",
        "combo_key_0", "combo_key_1", "combo_key_2",
        // 画面/性能
        "resolution_factor", "shader_type", "hw_gs_mode", "accurate_mul_type",
        "mag_filter", "min_filter", "screen_presentation_mode", "factor_3d",
        "layout_option", "landscape_layout_option", "frame_limit", "cpu_usage_limit",
        // 系统/音频
        "region_value", "audio_output_type", "audio_input_type", "audio_volume",
        "mic_volume", "shared_font_type", "camera_type",
        // 输入法 overlay
        "input_overlay_scale", "input_overlay_alpha", "input_joystick_range",
        "input_joystick_deadzone"
    )
    private val MMJ_BOOL_KEYS = setOf(
        "is_new_3ds", "use_cpu_jit", "use_hw_shader", "use_shader_jit",
        "async_shader_compile", "shadow_rendering", "force_texture_filter",
        "custom_textures", "use_compatible_mode", "use_fmv_hack", "skip_cpu_write",
        "skip_slow_draw", "skip_texture_copy", "use_fence_sync", "use_present_thread",
        "use_frame_limit", "enable_dsp_lle", "dsp_lle_multithread",
        "enable_audio_stretching", "use_virtual_sd", "use_game_config",
        "landscape_swap_screen", "portrait_swap_screen", "landscape_custom_layout",
        "input_overlay_hide", "input_overlay_feedback", "input_joystick_relative"
    )
    private val MMJ_STRING_KEYS = setOf("pp_shader_name")

    /** 键是否属于指定类型的白名单（未知键 → false，调用方应跳过写入）。 */
    private fun isKnownMmjKey(key: String, isInt: Boolean, isBool: Boolean): Boolean = when {
        isInt -> key in MMJ_INT_KEYS
        isBool -> key in MMJ_BOOL_KEYS
        else -> key in MMJ_STRING_KEYS
    }

    /** 输入映射配置（自选 id 写入 MMJ 配置键）。 */
    private fun applyInputConfig() {
        val lib = CitraMmjNative.lib
        fun i(key: String, value: Int) { try { lib.setConfigInteger(key, value) } catch (_: Throwable) {} }
        // ★★ 闪退根治（崩溃栈 #00 pc 0x282234 = so 内 UNREACHABLE 陷阱）：★★
        //   button_a/b/x/y/l/r 六键在本核心字面量池中不存在（nm/strings 逐一
        //   核实），setConfigInteger(未知键) 直接 brk #1 → SIGTRAP。
        //   A/B/X/Y/L/R 一律经 InputEvent 索引通道下发（setPad1 的 IDX_* 路径，
        //   索引值与原 MMJ APK overlay 完全一致），配置层只写真实存在的键。
        i("button_start", MMJ_ID_START)
        i("button_select", MMJ_ID_SELECT)
        i("button_zl", MMJ_ID_ZL)
        i("button_zr", MMJ_ID_ZR)
        i("button_home", MMJ_ID_HOME)
        i("button_up", MMJ_ID_UP)
        i("button_down", MMJ_ID_DOWN)
        i("button_left", MMJ_ID_LEFT)
        i("button_right", MMJ_ID_RIGHT)
        // 摇杆轴（负向槽 = 轴码 + 0x1000 —— 输入管理器对负值的匹配规则）
        i("circle_pad_down", MMJ_AXIS_CPAD_Y)
        i("circle_pad_up", MMJ_AXIS_CPAD_Y + MMJ_NEG)
        i("circle_pad_right", MMJ_AXIS_CPAD_X)
        i("circle_pad_left", MMJ_AXIS_CPAD_X + MMJ_NEG)
        i("c_stick_down", MMJ_AXIS_CSTICK_Y)
        i("c_stick_up", MMJ_AXIS_CSTICK_Y + MMJ_NEG)
        i("c_stick_right", MMJ_AXIS_CSTICK_X)
        i("c_stick_left", MMJ_AXIS_CSTICK_X + MMJ_NEG)
    }

    /** 应用全部 coreOptions（MMJ 配置键 → setConfig*；白名单外的键跳过防闪退）。 */
    private fun applyCoreOptions() {
        val lib = CitraMmjNative.lib
        for ((key, value) in coreOptions) {
            if (!isKnownMmjKey(key, value.toIntOrNull() != null,
                    value.equals("true", true) || value.equals("false", true))) {
                android.util.Log.w("CitraMmjEngine", "skip unknown MMJ config key: $key")
                continue
            }
            try {
                when {
                    value.equals("true", true) || value.equals("false", true) ->
                        lib.setConfigBoolean(key, value.equals("true", true))
                    value.toIntOrNull() != null ->
                        lib.setConfigInteger(key, value.toInt())
                    else -> lib.setConfigString(key, value)
                }
            } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (!ensureLoaded()) {
            lastErrorText = probeAvailability().second ?: "Citra MMJ 核心不可用"
            return false
        }
        cleanupLocked()

        appContext = appContext ?: com.nesstation.app.NesApp.get()
        if (appContext == null) {
            lastErrorText = "应用上下文未初始化"
            return false
        }

        try {
            val lib = CitraMmjNative.lib
            NativeLibrary.hostContext = appContext
            NativeLibrary.host = object : NativeLibrary.MmjHost {
                override fun onGameShutdown() {
                    lastErrorText = "核心已停止"
                }
                override fun onMessage(type: Int, message: String) {
                    android.util.Log.i("CitraMmjEngine", "core message[$type]: $message")
                }
            }
            // ★★ 初始化管线（DraStic 同款严格顺序）：★★
            //   1. ensureUserDirTree：创建 nand/sdmc/config/sysdata 等基础目录
            //   2. SetUserPath("!" + userDir)：**必须加 '!' 前缀**，原因同
            //      AzaharEngine.kt：MMJ 原生 SetUserPath 开头调
            //      TranslateFilePath(path)→stat(拼接后的路径)，不加 '!' 会让
            //      路径被拼上 userDir 前缀两次→路径错乱→loadConfig 读不到
            //      config.ini→默认值→初始化断言失败→SIGTRAP at libcitra_mmj.so
            //      （崩溃栈 pc 0x282234 在 DefaultDispatch 协程线程，正是本处路径）
            //   3. loadConfig：在 userPath 生效后读 config.ini
            //   4. InitGpuDriver：对齐 Azahar 的 initializeGpuDriver 调用，
            //      避免 VK 后端初始化断言
            //   5. applyInputConfig + applyCoreOptions + saveConfig：应用用户设置
            ensureUserDirTree()
            try { lib.SetUserPath("!" + userDir()) } catch (_: Throwable) {}
            try { lib.loadConfig() } catch (_: Throwable) {}
            // ★★ 新增：GPU 驱动参数初始化（对齐 AzaharEngine 第 530–550 行）。
            //   不调时部分设备（如 Redmi Socrates）VK 后端初始化会触发原生 CHECK
            //   → SIGTRAP。传参同 Azahar：hook路径为 nativeLibraryDir/，
            //   driverInstallPath 为 filesDir/gpu_driver，fileRedirectPath
            //   为 filesDir/gpu/vk_file_redirect。
            try {
                val ctx = appContext
                val hookLibPath = ctx?.applicationInfo?.nativeLibraryDir?.let { "$it/" } ?: ""
                val driverInstallPath = ctx?.filesDir?.let {
                    java.io.File(it, "gpu_driver").apply { mkdirs() }.absolutePath
                } ?: ""
                val fileRedirectPath = ctx?.filesDir?.let {
                    java.io.File(java.io.File(it, "gpu"), "vk_file_redirect")
                        .apply { mkdirs() }.absolutePath
                } ?: ""
                lib.InitGpuDriver(hookLibPath, driverInstallPath, "", fileRedirectPath)
            } catch (_: Throwable) {}
            // 输入映射 + 用户设置
            applyInputConfig()
            applyCoreOptions()
            try { lib.saveConfig() } catch (_: Throwable) {}
        } catch (t: Throwable) {
            android.util.Log.w("CitraMmjEngine", "init failed", t)
        }

        this.romPath = rom.absolutePath
        _loaded = true

        // HUD 心跳（FPS 计数兼容）
        running.set(true)
        heartbeatThread = thread(name = "mmj-hud-heartbeat", isDaemon = true) {
            try {
                while (running.get()) {
                    onFrame()
                    try { Thread.sleep(16) } catch (_: InterruptedException) { break }
                }
            } catch (_: Throwable) {}
        }

        surface?.let { startEmulationLocked() }
        return true
    }

    private fun startEmulationLocked() {
        val path = romPath ?: return
        if (emuThread?.isAlive == true) return
        val lib = CitraMmjNative.lib
        // ★★ 本轮根治：崩溃栈 pc 0x282234 在 libcitra_mmj.so 的根因。★★
        //   旧实现在此处（Run 之前、SurfaceChanged 之前、emu 线程未启动）
        //   调用 lib.SetDisplayInfo(surfaceW, surfaceH, surfaceW, surfaceH, 0, 1f) ——
        //   MMJ 原生 EmuWindow 状态机在 Run 启动前不接 SetDisplayInfo，
        //   触发 UNREACHABLE → brk #1 → SIGTRAP（TRAP_BRKPT），
        //   Java try/catch(Throwable) 无法捕获原生陷阱，直接闪退。
        //   修复：Run 启动前一律不调 SetDisplayInfo ——
        //   · 显示几何信息由 onSurfaceChanged 在 emuThread 真正运行后下发；
        //   · 参考 Azahar 契约（org.citra.citra_emu.NativeLibrary）—— 上游同源
        //     项目根本不声明 SetDisplayInfo，证明启动期无需该调用。
        try {
            // SurfaceChanged 成功 → 同步 surfaceAttached=true（与 setSurface 路径一致）
            surface?.let {
                lib.SurfaceChanged(it)
                surfaceAttached = true
            }
        } catch (t: Throwable) {
            android.util.Log.w("CitraMmjEngine", "SurfaceChanged failed", t)
        }
        // ★★★ 启动门（本轮，与 AzaharEngine 同款根治）：SurfaceChanged 到
        //   Run() 窗口构造完成之间，SurfaceDestroyed 会把原生全局窗口置
        //   NULL → Run() 构造 EmuWindow 读到 null → 原生 abort。旧实现
        //   setSurface(null) 对此完全无守卫 —— 启动期任何视图重建（横竖
        //   屏切换/加载动画切换）都会触发闪退。nativeBooting 覆盖整个
        //   启动窗口，期间一律不调 SurfaceDestroyed。
        nativeBooting = true
        // ★★ 赋值竞态根治：先创建不启动的线程并赋值，再 start() ——
        //   消除“线程已跑、字段未赋”窗口里 setSurface(null) 读到
        //   emuThread==null 导致守卫失效的问题。
        val bootThread = thread(start = false, name = "CitraMmjNative", isDaemon = true) {
            // ★★ DraStic 同款 Surface 有效性检查：绝不能带无效 surface 进入 Run()！
            //   MMJ 原生 Run() 会调 EmuWindow_Android 构造，读到无效 surface 时
            //   VK 后端 vkCreateAndroidSurfaceKHR CHECK 失败→SIGTRAP
            //   （本次崩溃栈 pc 0x282234 在 libcitra_mmj.so 的根因之一）。
            //   等待最多 3 秒拉取有效 surface，命中则推给 native 并跑；
            //   超时则放弃本次启动并上报，避免闪退。
            var attemptedSurface: android.view.Surface? = surface
            if (attemptedSurface == null || !attemptedSurface.isValid) {
                var ready = false
                for (attempt in 1..30) {
                    try { Thread.sleep(100) } catch (_: InterruptedException) { nativeBooting = false; return@thread }
                    if (!running.get()) { nativeBooting = false; return@thread }
                    val cur = surface
                    if (cur != null && cur.isValid) {
                        attemptedSurface = cur
                        ready = true
                        break
                    }
                }
                if (!ready) {
                    nativeBooting = false
                    lastErrorText = "启动时 Surface 尚未就绪（视图重建中），已放弃本次启动；画面恢复后将自动重启。"
                    android.util.Log.w("CitraMmjEngine", lastErrorText)
                    return@thread
                }
                try {
                    lib.SurfaceChanged(attemptedSurface!!)
                    surfaceAttached = true
                } catch (_: Throwable) {}
            }
            try {
                if (_ffSpeed > 0) applyFastForwardConfig()
                // ★ '!' 前缀 = 原生绝对路径标记（同 Azahar）：裸路径会被
                //   TranslateFilePath 拼到用户目录下变成不存在的路径→黑屏/秒退
                lib.Run("!$path")
            } catch (t: Throwable) {
                android.util.Log.e("CitraMmjEngine", "Run() crashed", t)
                lastErrorText = t.message ?: "Run() crashed"
            } finally {
                // Run() 返回（含异常）即退出启动窗口
                nativeBooting = false
            }
        }
        // ★★ 先赋值再启动（赋值竞态根治）：此刻启动门已置位，即便 start()
        //   前有 setSurface(null) 也只会被门拦下。
        emuThread = bootThread
        bootThread.start()
        startPresentation()
    }

    /** Choreographer 驱动 doFrame（呈现节拍，与 Azahar 同构）。 */
    private fun startPresentation() {
        stopPresentation()
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            try {
                val cb = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (!running.get()) return
                        try { CitraMmjNative.lib.doFrame(frameTimeNanos) } catch (_: Throwable) {}
                        try { choreographer?.postFrameCallback(this) } catch (_: Throwable) {}
                    }
                }
                frameCallback = cb
                choreographer = Choreographer.getInstance()
                choreographer?.postFrameCallback(cb)
            } catch (_: Throwable) {}
        }
    }

    private fun stopPresentation() {
        try { frameCallback?.let { choreographer?.removeFrameCallback(it) } } catch (_: Throwable) {}
        choreographer = null
        frameCallback = null
    }

    override fun setSurface(surface: Surface?) {
        synchronized(lifecycleLock) {
            this.surface = surface
            if (!_loaded) return
            val lib = CitraMmjNative.lib
            if (surface != null) {
                try { lib.SurfaceChanged(surface); surfaceAttached = true } catch (_: Throwable) {}
                if (emuThread?.isAlive != true) startEmulationLocked()
            } else {
                // ★★ SurfaceDestroyed 守门：仅 attached 时调一次（详见字段注释）
                //   EmulatorScreen.surfaceDestroyed 连调 onSurfaceDestroyed+setSurface(null)
                //   + 退出 unload 三处入口都走这里 → 不守门必重复 release → 野指针崩溃
                //   ★★★ 启动门（本轮根治）：Run() boot 窗口内绝不能
                //   SurfaceDestroyed —— 原生全局窗口被置 NULL 后，Run()
                //   构造 EmuWindow 读到 null → 原生 abort。核心已跑起来
                //   （IsRunning）或无启动在途时才正常销毁。
                val nativeRunning = try { lib.IsRunning() } catch (_: Throwable) { false }
                val booting = nativeBooting || (emuThread?.isAlive == true && !nativeRunning)
                if (!booting && surfaceAttached) {
                    try { lib.SurfaceDestroyed() } catch (_: Throwable) {}
                    surfaceAttached = false
                }
            }
        }
    }

    override fun onSurfaceChanged(surface: Surface?, width: Int, height: Int) {
        if (surface == null) { setSurface(null); return }
        surfaceW = width
        surfaceH = height
        if (surface != this.surface) { setSurface(surface); return }
        // ★★ 本轮根治（同 startEmulationLocked 注释）：在 emuThread 真正运行后
        //   才调 SetDisplayInfo —— Run 之前原生 EmuWindow 状态机不接，会触发
        //   UNREACHABLE → SIGTRAP（pc 0x282234）。emuThread?.isAlive 是唯一可靠
        //   的“核心已启动”指示（running.get() 在 loadRom 中就被置 true，不能区分
        //   Run 是否已执行）。
        if (_loaded && width > 0 && height > 0 && emuThread?.isAlive == true) {
            try { CitraMmjNative.lib.SetDisplayInfo(width, height, width, height, 0, 1f) } catch (_: Throwable) {}
        }
    }

    override fun onSurfaceDestroyed() { setSurface(null) }

    override fun setSaveName(name: String) {
        // MMJ 按游戏自身组织存档（sdmc/nand），无需前端命名
    }

    override fun setPaused(paused: Boolean) {
        if (_paused == paused) return
        _paused = paused
        if (!_loaded) return
        try {
            val lib = CitraMmjNative.lib
            if (paused) lib.PauseEmulation() else lib.ResumeEmulation()
        } catch (t: Throwable) {
            android.util.Log.w("CitraMmjEngine", "pause/resume", t)
        }
    }

    override fun reset(hard: Boolean) {
        synchronized(lifecycleLock) {
            if (!_loaded || romPath == null) return@synchronized
            try {
                CitraMmjNative.lib.StopEmulation()
                emuThread?.join(8000)
            } catch (_: Throwable) {}
            emuThread = null
            // StopEmulation 后原生侧状态不可知，保守视为已 detach ——
            // 后续 SurfaceChanged 重新 attach 时会复位 surfaceAttached=true
            surfaceAttached = false
            val surf = surface ?: return@synchronized
            try {
                CitraMmjNative.lib.SurfaceChanged(surf)
                surfaceAttached = true
            } catch (_: Throwable) {}
            startEmulationLocked()
        }
    }

    override fun unload() = synchronized(lifecycleLock) { cleanupLocked() }
    override fun shutdown() = synchronized(lifecycleLock) { cleanupLocked() }

    private fun cleanupLocked() {
        running.set(false)
        stopPresentation()
        heartbeatThread?.let { t ->
            t.interrupt()
            try { t.join(500) } catch (_: InterruptedException) {}
        }
        heartbeatThread = null
        if (emuThread?.isAlive == true) {
            try {
                CitraMmjNative.lib.StopEmulation()
                emuThread?.join(8000)
            } catch (_: Throwable) {}
            // ★ 启动窗口内的 stop 可能被吞（与 Azahar 同型）：重试一次，
            //   仍存活则绝不 SurfaceDestroyed（宁可泄漏也不闪退）。
            if (emuThread?.isAlive == true) {
                try { CitraMmjNative.lib.StopEmulation() } catch (_: Throwable) {}
                emuThread?.join(4000)
            }
            if (emuThread?.isAlive == true) {
                android.util.Log.w("CitraMmjEngine",
                    "emuThread still alive after stop; skip SurfaceDestroyed to avoid null-window abort")
            }
        }
        // ★ 必须在置 null 前捕获存活状态（置 null 后 isAlive 恒 false）
        val threadStillAlive = emuThread?.isAlive == true
        emuThread = null
        nativeBooting = false
        // ★★ SurfaceDestroyed 守门：仅 attached 且模拟线程确已退出才调 ——
        //   线程仍活时（重试仍超时）宁可泄漏一次窗口也不能崩。
        if (surfaceAttached && !threadStillAlive) {
            try { CitraMmjNative.lib.SurfaceDestroyed() } catch (_: Throwable) {}
            surfaceAttached = false
        }
        surface = null
        _loaded = false
        _paused = false
        _ffSpeed = 0
        lastErrorText = ""
        stickLast.fill(0f)
    }

    // ------------------------------------------------------------------
    // 视频 / 快进
    // ------------------------------------------------------------------

    override fun videoWidth(): Int = 400
    override fun videoHeight(): Int = 480
    override fun realtimeFps(): Double = 0.0
    override fun setVideoFilter(filter: Int) {}
    override fun setHighQualityScaling(enabled: Boolean) {}

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (!_loaded) return
        applyFastForwardConfig()
    }

    /**
     * 快进：写 frame_limit / use_frame_limit 配置（MMJ 无运行时变速 JNI；
     * use_frame_limit=false = 不限速）。写配置 + saveConfig —— MMJ 的帧率
     * 限制在模拟循环内生效（若核心仅在启动读取，效果同"下次启动生效"，
     * 与设置面板提示一致）。
     */
    private fun applyFastForwardConfig() {
        try {
            val lib = CitraMmjNative.lib
            if (_ffSpeed > 0) {
                lib.setConfigBoolean("use_frame_limit", false)
            } else {
                lib.setConfigBoolean("use_frame_limit", true)
                lib.setConfigInteger("frame_limit", 100)
            }
            lib.saveConfig()
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    /**
     * MMJ 配置键直通（key = MMJ 配置键名，如 "resolution_factor" /
     * "use_hw_shader"）。运行中写入即时经 setConfig* 生效（MMJ 的
     * 配置为核心运行时读取的内存映射）；同时缓存供下次启动前重放。
     */
    override fun setCoreOption(key: String, value: String) {
        coreOptions[key] = value
        if (_loaded) {
            val isInt = value.toIntOrNull() != null
            val isBool = value.equals("true", true) || value.equals("false", true)
            // ★ 白名单拦截：未知键写入会触发核心 brk #1 崩溃（详见白名单注释），
            //   运行中下发前同样过滤。
            if (!isKnownMmjKey(key, isInt, isBool)) {
                android.util.Log.w("CitraMmjEngine", "skip unknown MMJ config key: $key")
                return
            }
            try {
                val lib = CitraMmjNative.lib
                when {
                    isBool -> lib.setConfigBoolean(key, value.equals("true", true))
                    isInt -> lib.setConfigInteger(key, value.toInt())
                    else -> lib.setConfigString(key, value)
                }
                lib.saveConfig()
            } catch (_: Throwable) {}
        }
    }

    override fun beginCoreOptionsBatch() {}
    override fun endCoreOptionsBatch() {}

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    override fun setPad1(bits: Int) {
        if (!_loaded) return
        val lib = CitraMmjNative.lib
        fun pressed(bit: Int) = bits and bit != 0
        // 双通道：KeyEvent(自选配置 id) + InputEvent(小索引兜底)
        val keyEvents = listOf(
            MMJ_ID_A to (bits and BIT_A != 0),
            MMJ_ID_B to (bits and BIT_B != 0),
            MMJ_ID_X to (bits and BIT_X != 0),
            MMJ_ID_Y to (bits and BIT_Y != 0),
            MMJ_ID_L to (bits and BIT_L != 0),
            MMJ_ID_R to (bits and BIT_R != 0),
            MMJ_ID_START to (bits and BIT_START != 0),
            MMJ_ID_SELECT to (bits and BIT_SELECT != 0),
            MMJ_ID_ZL to (bits and BIT_ZL != 0),
            MMJ_ID_ZR to (bits and BIT_ZR != 0),
            MMJ_ID_UP to (bits and BIT_UP != 0),
            MMJ_ID_DOWN to (bits and BIT_DOWN != 0),
            MMJ_ID_LEFT to (bits and BIT_LEFT != 0),
            MMJ_ID_RIGHT to (bits and BIT_RIGHT != 0)
        )
        for ((id, isPressed) in keyEvents) {
            try { lib.KeyEvent(id, if (isPressed) 1 else 0) } catch (_: Throwable) {}
        }
        val idxEvents = listOf(
            IDX_A to (bits and BIT_A != 0),
            IDX_B to (bits and BIT_B != 0),
            IDX_X to (bits and BIT_X != 0),
            IDX_Y to (bits and BIT_Y != 0),
            IDX_L to (bits and BIT_L != 0),
            IDX_R to (bits and BIT_R != 0),
            IDX_START to (bits and BIT_START != 0),
            IDX_SELECT to (bits and BIT_SELECT != 0),
            IDX_UP to (bits and BIT_UP != 0),
            IDX_DOWN to (bits and BIT_DOWN != 0),
            IDX_LEFT to (bits and BIT_LEFT != 0),
            IDX_RIGHT to (bits and BIT_RIGHT != 0)
        )
        for ((idx, isPressed) in idxEvents) {
            try { lib.InputEvent(idx, if (isPressed) 1f else 0f) } catch (_: Throwable) {}
        }
    }

    override fun setPad2(bits: Int) {
        // MMJ 触屏设备为 P1 专属 —— P2 不支持
    }

    /**
     * 双摇杆：CirclePad (lx, ly) / C-Stick (rx, ry)。
     * 屏幕坐标约定（上=负）与 MMJ 一致：MoveEvent(轴码, 带符号值)；
     * 负值由核心按 轴码+0x1000 匹配反向槽（circle_pad_up / left）。
     */
    override fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float) {
        if (!_loaded) return
        val lib = CitraMmjNative.lib
        val values = floatArrayOf(lx, ly, rx, ry)
        val axes = intArrayOf(MMJ_AXIS_CPAD_X, MMJ_AXIS_CPAD_Y, MMJ_AXIS_CSTICK_X, MMJ_AXIS_CSTICK_Y)
        for (i in axes.indices) {
            val v = values[i].coerceIn(-1f, 1f)
            if (v != stickLast[i]) {
                stickLast[i] = v
                try { lib.MoveEvent(axes[i], v) } catch (_: Throwable) {}
            }
        }
    }

    // ------------------------------------------------------------------
    // 触摸（AzaharCoreEngine 契约 —— 3DS 下屏）
    // ------------------------------------------------------------------

    override fun setTouchInput(x: Float, y: Float, pressed: Boolean) {
        if (!_loaded) return
        try {
            CitraMmjNative.lib.TouchEvent(if (pressed) 1 else 2, x.toInt(), y.toInt())
        } catch (_: Throwable) {}
    }

    override fun setTouchMoved(x: Float, y: Float) {
        if (!_loaded) return
        try {
            CitraMmjNative.lib.TouchEvent(4, x.toInt(), y.toInt())
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------
    // 双屏 / 其它
    // ------------------------------------------------------------------

    override fun swapScreens() {
        if (!_loaded) return
        try {
            // 布局交换（landscape_swap_screen 配置切换）
            val lib = CitraMmjNative.lib
            val current = lib.getConfigBoolean("landscape_swap_screen")
            lib.setConfigBoolean("landscape_swap_screen", !current)
            lib.setConfigBoolean("portrait_swap_screen", !current)
            lib.saveConfig()
        } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------
    // 存档（MMJ 无即时存档 JNI —— 诚实反馈）
    // ------------------------------------------------------------------

    override fun saveState(slot: Int, dst: File): Boolean {
        lastErrorText = "Citra MMJ 核心未提供即时存档接口（建议使用 Azahar 核心或游戏内存档）"
        return false
    }

    override fun loadState(slot: Int, src: File): Boolean {
        lastErrorText = "Citra MMJ 核心未提供即时读档接口（建议使用 Azahar 核心或游戏内存档）"
        return false
    }

    override fun captureFrame(): FrameCapture? = null
    override fun setRegion(region: Int) {}
    override fun setSampleRate(rate: Int) {}

    override fun lastError(): String = lastErrorText

    // Netplay 不支持（推模型核心）
    override var frameHook: NetplayHook?
        get() = null
        set(_) {}
}
