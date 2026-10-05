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
 * ★★ Citra MMJ（3DS）核心引擎 —— NesStation 集成 ★★
 *
 * 需求原话："源码里我上传了一个citra的apk.zip，将它集成在3ds核心里用于
 * 选择核心，就像nds的激烈核心一样，全部设置加入进去"。
 *
 * 集成模式 = DraStic / Azahar 同款：vendored .so（libcitra_mmj.so =
 * Citra_MMJ_20250220.apk 的 libmain.so）+ 原包名 JNI 契约
 * （org.citra.emu.NativeLibrary）+ 引擎类驱动生命周期。
 *
 * ★★★ 架构（本轮重构，逐项对齐原版 APK 反编译实证）★★★
 *
 * 启动序列（= 原版 MainActivity.d1.a.R → EmulationActivity.C0）：
 *  1. [loadRom]：建用户目录树（<filesDir>/azahar/，与 Azahar 共用）+
 *     种子系统文件（assets/mmj：nand/sdmc/sysdata/config/shaders）→
 *     用户设置合并写入 config/config-mmj.ini → SetUserPath(裸路径) →
 *     loadConfig → SurfaceChanged → 专用线程 Run(裸路径)（阻塞直至退出）；
 *  2. Choreographer 帧回调驱动 doFrame（呈现）；
 *  3. 暂停/恢复 PauseEmulation/ResumeEmulation；停止 StopEmulation。
 *
 * 配置（★★闪退根治 —— 见 MMJ_INI_SECTION 注释的根因定论★★）：
 *  - setConfig 系/getConfig 系 JNI 各有硬编码分发器，合计只接受 8 个键
 *    （combo_key_0/1/2、input_overlay_alpha/scale、input_overlay_feedback/
 *    hide、input_joystick_relative），其它键 brk #1 → SIGTRAP；
 *  - 因此全部用户设置走 ini 文件（原版设置 UI 同款机制）：合并写入
 *    <userDir>/config/config-mmj.ini，loadConfig() 读取；
 *  - 唯一的运行期 JNI 配置调用：setConfigBoolean("input_overlay_hide",
 *    true)（合法键，强制关闭 MMJ 自带 overlay —— NesStation 用自己的
 *    Compose 遮罩）。
 *
 * 输入（原版 InputOverlay 同款，零配置免闪退）：
 *  - 数字键全部经 InputEvent(固定索引, 1f/0f)：A=0 B=1 X=2 Y=3 十字键=4..7
 *    L=8 R=9 start=10 select=11 home=12 zl=14 zr=15；
 *  - 摇杆经 InputEvent(索引, 模拟值)：圆盘 X=21 Y=22，C 摇杆 X=23 Y=24
 *    （Y 轴 +1=向上 —— 原版 overlay k() 实证，下发前对屏幕坐标取反）；
 *  - 底屏触摸经 TouchEvent（action 位掩码：1=按下 / 2=抬起 / 4=移动）；
 *  - 物理手柄由 NesStation 统一映射进 setPad1/setAnalogAxes（同通道）。
 *
 * 即时存档：MMJ so 未导出 SaveState/LoadState JNI（nm 实测），saveState
 * 诚实返回 false 并给出说明（UI 提示改用 Azahar 核心）。
 */
class CitraMmjEngine private constructor() : EmulatorEngine, AzaharCoreEngine {

    companion object {
        // ------------------------------------------------------------------
        // ★★ InputEvent 固定索引通道（本轮根治重构）★★
        //
        // 输入一律走 InputEvent(index, value) —— 与原版 Citra_MMJ_20250220.apk
        // 的 InputOverlay（org.citra.emu.overlay.InputOverlay / a.java / b.java）
        // 完全同款：overlay 构造表 new a("button_a", 0) / new a("dpad", 4) /
        // new a("joystick", 21) / new a("c_stick", 23) 反编译实证——
        //   - 单键类（overlay/a.java h()/e()）：InputEvent(ownIndex, 1.0f/0.0f)
        //   - 四键类（overlay/b.java l()）：InputEvent(base+0..3, 1.0f/0.0f) 数字化
        //   - 摇杆类（overlay/b.java k()）：InputEvent(base+0, x) / (base+1, y) 模拟量
        //
        // 旧的 KeyEvent(自选id)/MoveEvent(自选轴码) 双通道已整体移除：那套设计
        // 需要先用 setConfigInteger 把 button_*/circle_pad_* 写成自选 id —— 而
        // MMJ 的 setConfigInteger 分发器只接受 input_overlay_alpha/scale 两个键，
        // 其它键一律 brk #1（SIGTRAP 闪退，详见下方 ini 架构注释）。
        // ------------------------------------------------------------------
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
        private const val IDX_HOME = 12      // 原版 overlay：new a("button_home", 12)
        private const val IDX_ZL = 14        // 原版 overlay：new a("button_zl", 14)
        private const val IDX_ZR = 15        // 原版 overlay：new a("button_zr", 15)
        // 摇杆（joystick=21 / c_stick=23；k() 模拟量通道，X=base+0，Y=base+1）
        // ★ Y 约定与 Android 屏幕坐标相反：原版 k() 触摸上 1/3 → fArr[1]=+1 →
        //   InputEvent(22, +1) = 圆盘向上。NesStation 的 setAnalogAxes 是屏幕
        //   坐标（上=负），下发前取反。
        private const val IDX_CPAD_X = 21
        private const val IDX_CPAD_Y = 22
        private const val IDX_CSTICK_X = 23
        private const val IDX_CSTICK_Y = 24

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
        // ★★ 本轮根治：与 Azahar 共用 <filesDir>/azahar 目录 —— nand/sdmc/sysdata
        //   3DS 必需系统文件、游戏存档、SD 卡内容双核心共享，用户在一个核心
        //   导入的存档/系统文件另一个核心立即可用。两核心的【配置】分开：
        //     - Azahar 主配置：<userDir>/config.ini
        //     - MMJ per-game 配置：<userDir>/config/config-games.ini
        //     - MMJ 输入布局：<userDir>/config/input-layout.ini
        //   两者使用不同文件路径，互不覆盖。
        return File(ctx.filesDir, "azahar").apply { mkdirs() }.absolutePath
    }

    private fun ensureUserDirTree() {
        val root = File(userDir())
        for (rel in listOf(
            "nand", "nand/title", "nand/data", "sdmc", "sdmc/1ds", "sysdata",
            "config", "log", "states", "cheats", "shaders", "dump", "sdmc/Nintendo 3DS"
        )) {
            try { File(root, rel).mkdirs() } catch (_: Throwable) {}
        }
        // ★★ v1.3：系统文件种子（"启动游戏闪退"根治）—— 见 seedMmjSystemAssets。
        seedMmjSystemAssets()
        // ★★ 本轮：与 Azahar 共用目录后，sysdata 拷贝逻辑已无意义（同一目录）
        //   —— AzaharEngine.ensureSysDataFiles() 已把 aes_keys.txt/boot9.bin/seeddb.bin
        //   归位到 <userDir>/sysdata/，MMJ 直接读同一路径即可，无需再拷贝。
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
     * <filesDir>/azahar/ 对应位置（与 Azahar 共用目录）。已存在且非空的文件跳过（对齐原版
     * overwrite=FALSE 语义，二次启动零拷贝、纯 list 遍历）。
     */
    private fun seedMmjSystemAssets() {
        val ctx = appContext ?: return
        try {
            val am = ctx.assets
            // ★★ 本轮：与 userDir() 对齐 —— 之前硬编码 "citra_mmj" 会与
            //   userDir() 改为 "azahar" 后不一致，种子文件写到旧目录、核心
            //   却在新目录读不到，启动崩。改用 userDir() 同步路径。
            val root = File(userDir())
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
     * ★★★ 配置架构（本轮根治重构，对齐原版 APK 实证行为）★★★
     *
     * 【闪退根因 —— so 反汇编最终定论】
     * MMJ 的 setConfig 系/getConfig 系 JNI 不是通用键值存储！每个类型各有一个
     * 硬编码分发器（libcitra_mmj.so 反汇编实证）：
     *   - setConfigString   → sub_281d10：仅接受 combo_key_0/1/2
     *   - setConfigInteger  → sub_28208c：仅接受 input_overlay_alpha/scale
     *   - setConfigBoolean  → sub_282238：仅接受 input_overlay_feedback/
     *                          input_overlay_hide/input_joystick_relative
     * 分发器把传入键名与 BSS 运行时初始化的 std::string 表逐个比对，全部
     * 不匹配 → UNREACHABLE → brk #1 → SIGTRAP（pc 0x282234，与用户崩溃栈
     * 完全一致）。旧实现经 setConfigInteger 写 button_start/circle_pad_*、
     * 经 setConfigBoolean 写 resolution_factor/is_new_3ds 等 40+ 键 ——
     * 第一键即触发 brk，无法被 Java try/catch 捕获，进程直接闪退。
     * （旧白名单按 so 字面量池编制 —— 那些键名确实存在于设置表，但只用于
     *  ini 文件读写，与 JNI 分发器的接受集是两回事。）
     *
     * 【原版 APK 的真实配置流（反编译实证）】
     * 原版的设置 UI（Y0/i.java）用 Java ini 库（Z0 包）【直接读写】
     * <userDir>/config/config-mmj.ini（so 字符串实证 "config/config-mmj.ini"），
     * 核心经 loadConfig() 读该文件。sections（原版设置编辑器逐一实证）：
     *   [Renderer]  resolution_factor/use_hw_shader/accurate_mul_type/
     *               pp_shader_name/frame_limit/custom_textures/factor_3d/
     *               use_fence_sync/layout_option/show_fps/use_frame_limit
     *   [Debug]     shader_type/use_present_thread/cpu_usage_limit + 性能 hack 族
     *               (hw_gs_mode、use_fmv_hack、skip_ 三兄弟、
     *                force_texture_filter、async_shader_compile、
     *                use_compatible_mode、shadow_rendering)
     *   [Core]      is_new_3ds/use_cpu_jit/region_value/language/
     *               shared_font_type/use_game_config/use_virtual_sd
     *   [Audio]     audio_output_type/audio_input_type/enable_audio_stretching/
     *               audio_volume/mic_volume/enable_dsp_lle/dsp_lle_multithread
     *   [Camera]    camera_type
     *   [Controls]  overlay/布局族（input_overlay_*、input_joystick_*、
     *               landscape/portrait_swap_screen、landscape_custom_layout、
     *               landscape_layout_option 等，设置表结构体段名实证）
     * NesStation 沿用同一机制：把用户设置合并写入 ini → loadConfig() 读取。
     * 键名不在下表 → 只记日志跳过（ini 未知键本来就会被核心忽略，双保险）。
     */
    private val MMJ_INI_SECTION: Map<String, String> = mapOf(
        // ★★★ 本轮段名全面修正（根治"布局不生效/画面不跟随全局缩放"）★★★
        //
        // 依据（双源实证）：
        //   1. weihuoya/citra 上游 src/android/jni/config/main_settings.cpp 的
        //      ConfigInfo 定义（authoritative 段名表）；
        //   2. 参考 APK Java 设置编辑器 Y0/i.java k2()（段名一致）。
        //
        // 旧表把布局族全塞进 [Controls]，且 layout_option 重复定义两次
        // （mapOf 保留后者 → Controls）—— 核心实际从 [Renderer]/[Layout] 读，
        // 写错段 = 核心永远读不到 → "自定义布局不行 / 全局缩放不跟随"的
        // 直接根因。本轮逐一按上游修正。
        //
        // [Renderer] —— 上游 main_settings.cpp + k2() 实证
        "layout_option" to "Renderer", "landscape_layout_option" to "Renderer",
        "screen_presentation_mode" to "Renderer", "show_fps" to "Renderer",
        "resolution_factor" to "Renderer", "use_hw_shader" to "Renderer",
        "accurate_mul_type" to "Renderer", "pp_shader_name" to "Renderer",
        "frame_limit" to "Renderer", "custom_textures" to "Renderer",
        "factor_3d" to "Renderer", "use_fence_sync" to "Renderer",
        "use_frame_limit" to "Renderer",
        "mag_filter" to "Renderer", "min_filter" to "Renderer",
        // [Debug] —— k2() 实证 + 设置表相邻条目（性能 hack 族）
        "shader_type" to "Debug", "use_present_thread" to "Debug",
        "cpu_usage_limit" to "Debug", "hw_gs_mode" to "Debug",
        "use_fmv_hack" to "Debug", "skip_slow_draw" to "Debug",
        "skip_cpu_write" to "Debug", "skip_texture_copy" to "Debug",
        "force_texture_filter" to "Debug", "async_shader_compile" to "Debug",
        "use_compatible_mode" to "Debug", "shadow_rendering" to "Debug",
        // [Core] —— k2() 实证
        "use_game_config" to "Core", "is_new_3ds" to "Core",
        "use_cpu_jit" to "Core", "region_value" to "Core",
        "language" to "Core", "shared_font_type" to "Core",
        "use_virtual_sd" to "Core", "use_shader_jit" to "Core",
        // [Audio] —— k2() 实证
        "audio_output_type" to "Audio", "enable_audio_stretching" to "Audio",
        "audio_input_type" to "Audio", "audio_volume" to "Audio",
        "mic_volume" to "Audio", "enable_dsp_lle" to "Audio",
        "dsp_lle_multithread" to "Audio",
        // [Camera] —— k2() 实证
        "camera_type" to "Camera",
        // [Controls] —— 上游实证：仅 overlay/输入族（布局族已迁出！）
        "input_overlay_scale" to "Controls", "input_overlay_alpha" to "Controls",
        "input_overlay_hide" to "Controls", "input_overlay_feedback" to "Controls",
        "input_joystick_relative" to "Controls", "input_joystick_range" to "Controls",
        "input_joystick_deadzone" to "Controls",
        // ★★★ [Layout] —— 上游 main_settings.cpp 实证（旧表错放 [Controls]，
        //   是"自定义布局/交换屏幕/全局缩放跟随"全部失效的段名根因）：
        //   PORTRAIT/LANDSCAPE_CUSTOM_LAYOUT / SWAP_SCREEN / 16 个矩形键。
        "landscape_swap_screen" to "Layout", "portrait_swap_screen" to "Layout",
        "landscape_custom_layout" to "Layout", "portrait_custom_layout" to "Layout",
        // ★★★ 双屏自定义布局矩形（so .rodata 键名表实证 0x12dbf8-0x12e010：
        //   landscape_top_left/top/top/right/bottom + landscape_bottom_* +
        //   portrait_top_* + portrait_bottom_*，共 16 键，像素坐标相对全屏
        //   窗口 —— setCustomLayout JNI 同源字段（config+0x27e..0x28c u16））。
        //   旧集成只发了 landscape_custom_layout 开关、从未发矩形 ——
        //   "自定义布局更不行"的直接原因。
        "landscape_top_left" to "Layout", "landscape_top_top" to "Layout",
        "landscape_top_right" to "Layout", "landscape_top_bottom" to "Layout",
        "landscape_bottom_left" to "Layout", "landscape_bottom_top" to "Layout",
        "landscape_bottom_right" to "Layout", "landscape_bottom_bottom" to "Layout",
        "portrait_top_left" to "Layout", "portrait_top_top" to "Layout",
        "portrait_top_right" to "Layout", "portrait_top_bottom" to "Layout",
        "portrait_bottom_left" to "Layout", "portrait_bottom_top" to "Layout",
        "portrait_bottom_right" to "Layout", "portrait_bottom_bottom" to "Layout"
    )

    /**
     * ★★★ 布局族热生效键（本轮新增）★★★
     * 这些键改动时核心无需重启：写 ini → loadConfig() 重读 →
     * WindowChanged() 触发 EmuWindow 重算双屏布局（so 0x264dc0 实证：
     * 对窗口全局对象调虚方法重算布局，窗口为空时内部有空检查直接返回，
     * 运行中调用安全）。自定义布局编辑器拖动即所见即所得。
     */
    private val MMJ_LAYOUT_HOT_KEYS = setOf(
        "layout_option", "landscape_layout_option",
        "landscape_swap_screen", "portrait_swap_screen",
        "landscape_custom_layout", "portrait_custom_layout",
        "landscape_top_left", "landscape_top_top",
        "landscape_top_right", "landscape_top_bottom",
        "landscape_bottom_left", "landscape_bottom_top",
        "landscape_bottom_right", "landscape_bottom_bottom",
        "portrait_top_left", "portrait_top_top",
        "portrait_top_right", "portrait_top_bottom",
        "portrait_bottom_left", "portrait_bottom_top",
        "portrait_bottom_right", "portrait_bottom_bottom"
    )

    /**
     * ★★★ 值域归一化（本轮根治"滋滋滋"音频 + "很多独立设置无效"）★★★
     *
     * 依据：Citra_MMJ_20250220.apk 反编译（settings 编辑器 Y0/i.java k2() +
     * res/values/arrays.xml 逐一实证）—— MMJ 核心只接受以下枚举值，
     * 越界值行为未定义（audio_output_type=0/3 → sink 选择走野指针分支 →
     * 输出白噪声"滋滋滋"，即用户反馈的音频问题根因）：
     *   - audio_output_type： 1=关闭 / 2=Cubeb（原版默认 2）
     *   - audio_input_type：  1=关闭 / 2=静态噪声 / 3=真实设备（原版默认 1）
     *   - camera_type：       "blank" / "image" / "camera"（字符串！arrays.xml
     *                         cameraValues 实证 —— 上一轮误写 still_image/
     *                         from_device，已修正）
     *   - resolution_factor： 1..4（无 5x 档）
     *   - shader_type：       0=标准 / 1=标准+缓存（默认）/ 2=分离
     *   - accurate_mul_type： 0=关闭（默认）/ 1=快速 / 2=精确
     *   - factor_3d：         0..10（不是 0..100）
     *   - audio_volume/mic_volume： float 0..1（上游 ConfigInfo<float> 默认
     *                         1.0/1.5；UI 存 0..100 百分比 → /100 归一）
     *   - shared_font_type：  -1=自动（默认）/ 0=shared_font.bin / 1=日 /
     *                         2=简中 / 3=韩 / 4=繁
     *   - region_value：      -1=自动 / 0=日 / 1=美 / 2=欧 / 3=澳 / 4=中 /
     *                         5=韩 / 6=台
     *   - layout_option：     0..3（默认/单屏/大屏/并排；自定义布局走
     *                         landscape/portrait_custom_layout 开关 + 16 矩形键）
     * 旧版 UI 下发过的非法值在此统一归一到合法集，双保险（UI 层同步修正）。
     */
    private fun normalizeMmjValue(key: String, value: String): String {
        fun clampInt(v: String, lo: Int, hi: Int, dflt: Int): String {
            val n = v.toIntOrNull() ?: return dflt.toString()
            return n.coerceIn(lo, hi).toString()
        }
        // float 百分比归一：上游 AUDIO_VOLUME/MIC_VOLUME 是 float 0..1
        //（上游 dsp_interface.cpp clamp(0,1)），UI 下发 0..100 → /100。
        fun percentToFloat(v: String, dflt: Float): String {
            val f = v.toFloatOrNull() ?: return dflt.toString()
            val norm = if (f > 1.0f) f / 100f else f
            return norm.coerceIn(0f, 1f).let {
                if (it == it.toInt().toFloat()) it.toInt().toString() else it.toString()
            }
        }
        return when (key) {
            "audio_output_type" -> if (value == "1" || value == "2") value else "2"
            "audio_input_type" -> if (value == "1" || value == "2" || value == "3") value else "1"
            "camera_type" -> if (value == "blank" || value == "image" ||
                value == "camera") value else "blank"
            "resolution_factor" -> clampInt(value, 1, 4, 1)
            "shader_type" -> if (value == "0" || value == "1" || value == "2") value else "1"
            "accurate_mul_type" -> if (value == "0" || value == "1" || value == "2") value else "0"
            "factor_3d" -> clampInt(value, 0, 10, 0)
            "audio_volume" -> percentToFloat(value, 1.0f)
            "mic_volume" -> percentToFloat(value, 1.5f).let {
                // mic 上游默认 1.5（>1 有增益），本 UI 百分比语义下 100%→1.0，
                // 保持 1.0 即可（真麦克风输入由 audio_input_type=3 开启）。
                val f = it.toFloatOrNull() ?: 1.0f
                f.coerceIn(0f, 1.5f).let { v2 ->
                    if (v2 == v2.toInt().toFloat()) v2.toInt().toString() else v2.toString()
                }
            }
            "shared_font_type" -> if (value in listOf("-1", "0", "1", "2", "3", "4")) value else "-1"
            "region_value" -> clampInt(value, -1, 6, -1)
            "layout_option", "landscape_layout_option" ->
                if (value == "0" || value == "1" || value == "2" || value == "3") value else "0"
            else -> value
        }
    }

    /**
     * 把 coreOptions 合并写入 <userDir>/config/config-mmj.ini。
     *
     * 合并语义（对齐原版 Java ini 编辑器）：保留文件中已有的一切条目（包括
     * 原生 saveConfig 写回的键、per-game 兼容键），只覆盖本引擎下发的键；
     * 文件不存在则新建。input_overlay_hide 永远强制 true（NesStation 自带
     * Compose 遮罩，MMJ 自带 overlay 无资源且会闪退，必须关闭）。
     *
     * @param extra 额外覆盖项（如快进的 use_frame_limit/frame_limit）
     */
    private fun writeMmjIniLocked(extra: Map<String, String> = emptyMap()) {
        val root = userDir()
        val iniFile = File(root, "config/config-mmj.ini")
        try {
            iniFile.parentFile?.mkdirs()
            // 1) 解析现有文件（保持段序/键序/未知条目）
            val sections = LinkedHashMap<String, LinkedHashMap<String, String>>()
            if (iniFile.isFile) {
                try {
                    java.io.BufferedReader(java.io.FileReader(iniFile)).use { br ->
                        var cur = "\u0000root"   // 段前的裸键挂到虚拟根（正常不会出现）
                        sections[cur] = LinkedHashMap()
                        br.forEachLine { raw ->
                            val line = raw.trim()
                            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                                // 注释/空行不保留（重写后由内容自描述）
                                return@forEachLine
                            }
                            if (line.startsWith("[") && line.endsWith("]")) {
                                cur = line.substring(1, line.length - 1).trim()
                                sections.getOrPut(cur) { LinkedHashMap() }
                            } else {
                                val eq = line.indexOf('=')
                                if (eq > 0) {
                                    val k = line.substring(0, eq).trim()
                                    val v = line.substring(eq + 1).trim()
                                    sections.getOrPut(cur) { LinkedHashMap() }[k] = v
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("CitraMmjEngine", "parse config-mmj.ini failed, rewrite", t)
                }
            }
            // 2) 应用覆盖项（input_overlay_hide 硬编码 true）
            val overrides = LinkedHashMap<String, String>()
            for ((k, v) in coreOptions) overrides[k] = v
            overrides.putAll(extra)
            overrides["input_overlay_hide"] = "true"
            // ★★★ 闪退根治（本轮，崩溃栈逐帧反汇编定位）★★★
            //
            // 用户 tombstone（Redmi socrates / Android 15）：
            //   fault 0x5414f8 ldr x8,[x20] ← F0(0x541484 VFS 打开+读文件)
            //   ← F1(0x4254c4 后处理着色器加载) ← F2(渲染器初始化虚方法)
            //   ← F3(0x3d4f54) ← F4(0x288d54) ← F5(0x2654d8 boot 主体)
            //   ← Run+192。F1 读 config 的 pp_shader_name（非空时）拼
            //   <前缀><name>.glsl 以文本模式("r")经 VFS 打开 —— VFS 走
            //   Java RemoteFileOpen，文件不存在返回 null → F0 对返回的
            //   shared_ptr 裸指针【无空检查】直接 ldr → SIGSEGV。
            //   （so 内同族函数 0x541368 有 cbz 空检查，唯独 F0 没有。）
            //
            //   触发值：UI 默认 "(off)" 哨兵与下拉里的 "Anime4K"（无此文件）
            //   都会写进 ini → 核心启动必炸；"部分游戏有概率能启动"是设置
            //   下发与 loadRom 的竞态 + saveConfig 回写振荡的结果。
            //
            //   修复：任何值先校验 <userDir>/shaders/<name>.glsl 真实存在，
            //   不存在一律归一为空串 —— 空串时 F1 在 0x4255ec cbz 直接
            //   跳过文件加载（内置直通渲染），与原版"无后处理"语义一致。
            val shaderName = overrides.remove("pp_shader_name")
            if (shaderName != null) {
                val exists = shaderName.isNotEmpty() &&
                    File(userDir(), "shaders/$shaderName.glsl").isFile
                overrides["pp_shader_name"] = if (exists) shaderName else ""
                if (!exists && shaderName.isNotEmpty()) {
                    android.util.Log.w("CitraMmjEngine",
                        "pp_shader_name '$shaderName' has no .glsl in shaders/, reset to none (crash guard)")
                }
            }
            // ★★★ 帧率限制保险（"画面像快进"根治）★★★
            // MMJ 的 use_frame_limit=false = 不限速（快进）。旧流程退出快进
            // 时机不对时会在 ini 残留 false，下次启动 loadConfig 读到 →
            // 游戏全速狂奔（用户反馈"画面有时候像是快进一样"）。
            // 本次覆盖集没有该键时强制写 true + 用户值/100。
            if (!overrides.containsKey("use_frame_limit")) {
                overrides["use_frame_limit"] = "true"
                if (!overrides.containsKey("frame_limit")) {
                    overrides["frame_limit"] = coreOptions["frame_limit"] ?: "100"
                }
            }
            var applied = 0
            for ((key, value) in overrides) {
                val section = MMJ_INI_SECTION[key]
                if (section == null) {
                    android.util.Log.w("CitraMmjEngine", "skip unknown MMJ ini key: $key")
                    continue
                }
                // ★ 值域归一化：非法枚举值（旧 UI 下发/手改 ini）在此修正，
                //   保证核心永远读到合法值（详见 normalizeMmjValue 注释）。
                sections.getOrPut(section) { LinkedHashMap() }[key] =
                    normalizeMmjValue(key, value)
                applied++
            }
            // ★ 旧版 [Controls] 布局族残留清理（见 cleanupLegacyControlsLayoutKeys）
            cleanupLegacyControlsLayoutKeys(sections)
            // 3) 写回
            val sb = StringBuilder()
            for ((section, kv) in sections) {
                if (kv.isEmpty()) continue
                if (section != "\u0000root") sb.append('[').append(section).append("]\n")
                for ((k, v) in kv) sb.append(k).append('=').append(v).append('\n')
                sb.append('\n')
            }
            java.io.FileWriter(iniFile).use { it.write(sb.toString()) }
            android.util.Log.i("CitraMmjEngine",
                "config-mmj.ini written ($applied overrides, ${sections.size} sections)")
        } catch (t: Throwable) {
            android.util.Log.w("CitraMmjEngine", "writeMmjIni failed", t)
        }
    }

    /**
     * ★★ 旧版残留清理（本轮段名修正的配套）：
     * 旧映射表把布局族键写进了 [Controls]（核心从 [Layout]/[Renderer] 读）。
     * 修正后新值写对段，但用户设备上的 ini 还残留 [Controls] 下的旧键。
     * 虽核心按段读会忽略它们，仍主动清除防止任何歧义（如核心未来版本
     * 改为全局键匹配时新旧值打架）。在每次写 ini 前调用。
     */
    private fun cleanupLegacyControlsLayoutKeys(sections: LinkedHashMap<String, LinkedHashMap<String, String>>) {
        val controls = sections["Controls"] ?: return
        val legacyKeys = setOf(
            "layout_option", "landscape_layout_option", "screen_presentation_mode",
            "landscape_swap_screen", "portrait_swap_screen",
            "landscape_custom_layout", "portrait_custom_layout",
            "landscape_top_left", "landscape_top_top",
            "landscape_top_right", "landscape_top_bottom",
            "landscape_bottom_left", "landscape_bottom_top",
            "landscape_bottom_right", "landscape_bottom_bottom",
            "portrait_top_left", "portrait_top_top",
            "portrait_top_right", "portrait_top_bottom",
            "portrait_bottom_left", "portrait_bottom_top",
            "portrait_bottom_right", "portrait_bottom_bottom"
        )
        controls.keys.removeAll(legacyKeys)
        if (controls.isEmpty()) sections.remove("Controls")
    }

    /** 读 ini 里某键当前值（swapScreens 等需要读-改-写的场景）。 */
    private fun readMmjIniValue(key: String): String? {
        val iniFile = File(userDir(), "config/config-mmj.ini")
        val section = MMJ_INI_SECTION[key] ?: return null
        if (!iniFile.isFile) return null
        return try {
            var result: String? = null
            java.io.BufferedReader(java.io.FileReader(iniFile)).use { br ->
                var inSection = false
                while (true) {
                    val raw = br.readLine() ?: break
                    val line = raw.trim()
                    if (line.startsWith("[") && line.endsWith("]")) {
                        inSection = line.substring(1, line.length - 1).trim() == section
                    } else if (inSection && line.startsWith("$key=")) {
                        result = line.substring(key.length + 1).trim()
                        break
                    }
                }
            }
            result
        } catch (_: Throwable) { null }
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
            // ★★★ 初始化管线（本轮重构，逐步对齐原版 APK 的启动序列）★★★
            //
            // 原版 Citra_MMJ_20250220.apk（反编译实证）：
            //   MainActivity → d1.a.R(ctx)：
            //     ① userDir = getExternalFilesDir/citra-emu（SAF 模式）
            //     ② NativeLibrary.SetUserPath(【裸路径，无任何前缀】)
            //     ③ 后台拷贝 assets（nand/sdmc/sysdata/config/shaders）
            //   EmulationActivity → C0()：
            //     ④ NativeLibrary.SurfaceChanged(surface)
            //     ⑤ 新线程 NativeLibrary.Run(【裸路径，无任何前缀】)
            //
            // 旧实现的两大路径错误（本轮修复）：
            //   A. '!' 前缀 —— 那是 Azahar（org.citra.citra_emu）的
            //      TranslateFilePath 约定。MMJ（org.citra.emu，weihuoya 分支）
            //      的 SetUserPath/Run 反汇编实证【直接消费字符串，无前缀处理】：
            //      SetUserPath("!dir") 会让用户目录字面量变成 "!..." →
            //      nand/sdmc/sysdata/config 全部读不到；Run("!path") →
            //      ROM 找不到 → "Invalid ROM Format" 提前返回（黑屏/秒退）。
            //   B. InitGpuDriver —— 原版 Java 侧【从不调用】（仅声明）。
            //      该调用只是把 4 个字符串存进全局变量（反汇编实证），原版
            //      这些全局保持空串 = 不安装 GPU 钩子 = 系统驱动直跑。
            //      我们的 APK 没有 libhook_impl.so 等 5 个配套库，主动设置
            //      hookLibDir 反而有让渲染后端尝试加载缺失钩子库的风险 ——
            //      删除调用，与原版完全一致。
            //
            // 顺序：
            //   1. ensureUserDirTree：建目录树 + 种子系统文件（assets/mmj）
            //   2. writeMmjIniLocked：用户设置合并写入 config/config-mmj.ini
            //   3. SetUserPath(userDir)：裸路径（原生侧自动补尾部 '/'）
            //   4. loadConfig：从 ini 读全部设置（含 input_overlay_hide=true）
            //   5. setConfigBoolean("input_overlay_hide", true)：分发器接受集
            //      内的合法键（belt & braces，防止旧 ini 残留 false 值竞态）
            ensureUserDirTree()
            writeMmjIniLocked()
            try { lib.SetUserPath(userDir()) } catch (_: Throwable) {}
            try { lib.loadConfig() } catch (_: Throwable) {}
            try { lib.setConfigBoolean("input_overlay_hide", true) } catch (_: Throwable) {}
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
            // ★★ 本轮根治（pc 0x282234 在 libcitra_mmj.so 的第 2 个触发点）★★
            //   开发者既有注释（下行 503-509）已自指认：MMJ 原生 SurfaceChanged
            //   内部调 vkCreateAndroidSurfaceKHR，传入**对象存在但底层已失效**
            //   的 Surface（视图重建 / SurfaceHolder 销毁但 Java 侧仍持有引用）
            //   会触发 VK 后端 CHECK → SIGTRAP（brk #1）at pc 0x282234，正是
            //   用户反馈"MMJ 启动游戏闪退"的栈点（DefaultDispatch 线程，
            //   BuildId 14a21e39...）。
            //   emu 线程侧已有 surface.isValid 守卫（下方 line 510-533），
            //   但 IO 协程侧这里的 SurfaceChanged 没有同款守卫 —— 上轮
            //   移除 SetDisplayInfo 后用户仍崩，因为崩点是这里。
            //   修复：与 emu 线程同款 isValid 守卫。无效 surface 直接跳过
            //   （不调 SurfaceChanged，surfaceAttached=false），让 emu 线程
            //   的等待循环（line 510-528）拉到有效 surface 后再推给 native。
            surface?.let { s ->
                if (s.isValid) {
                    lib.SurfaceChanged(s)
                    surfaceAttached = true
                } else {
                    android.util.Log.w("CitraMmjEngine",
                        "SurfaceChanged skipped: surface valid=false (will retry on emu thread)")
                }
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
                if (_ffSpeed > 0) {
                    // ★ 快进状态在 Run 前落盘并重读 ini —— 本次会话即不限速
                    //   （loadRom 的 loadConfig 早于此，需重读才能带上快进键）。
                    applyFastForwardConfig()
                    try { lib.loadConfig() } catch (_: Throwable) {}
                }
                // ★ 裸路径直传（原版实证：Run(intent 的 GamePath 原样字符串）。
                //   '!' 前缀是 Azahar 的约定，MMJ 原生无此前缀处理 —— 加了
                //   反而让核心找不到 ROM → "Invalid ROM Format" 提前退出。
                lib.Run(path)
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
        // ★ 运行时设置推送（通道 2）：核心进入模拟主循环后直写帧率上限/
        //   布局等（原版游戏内设置面板同款通道），不依赖 ini 解析链路。
        startRuntimeSettingsPusher()
    }

    /** Choreographer 驱动 doFrame（呈现节拍，与 Azahar 同构）。 */
    private fun startPresentation() {
        stopPresentation()
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            try {
                // ★★ 本轮加固：Choreographer 实例捕获为局部 val —— 旧实现回调
                //   里读 choreographer 字段，与 stopPresentation()（可从引擎
                //   线程调用）置 null 存在竞态：一旦撞上，postFrameCallback
                //   静默失败 → 呈现节拍永久死亡 → 核心呈现线程失去 vsync
                //   驱动（画面停滞/节奏异常）。局部实例彻底消除该竞态。
                val ch = Choreographer.getInstance()
                val cb = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (!running.get()) return
                        try { CitraMmjNative.lib.doFrame(frameTimeNanos) } catch (_: Throwable) {}
                        try { ch.postFrameCallback(this) } catch (_: Throwable) {}
                    }
                }
                frameCallback = cb
                choreographer = ch
                ch.postFrameCallback(cb)
            } catch (_: Throwable) {}
        }
    }

    private fun stopPresentation() {
        try { frameCallback?.let { choreographer?.removeFrameCallback(it) } } catch (_: Throwable) {}
        choreographer = null
        frameCallback = null
    }

    /**
     * ★★★ 运行时设置推送（本轮新增：根治"全都 60 帧 / 速度加快 / 滋滋声"）★★★
     *
     * 原版 APK 有两条设置通道（c1/Q.java 反编译实证）：
     *   1. ini 文件（启动时 Run() 内部 Config::Load 读取）；
     *   2. setRunningSettings(int[20])（游戏运行中实时生效 —— 直接写核心
     *      Settings 结构体 + Config::Set 注册表，原版游戏内设置面板用的
     *      就是这条）。
     * 旧集成只用了通道 1。若 ini → 运行时结构体的传递在任何环节断链
     * （段名/时序/核心内部状态），帧率限制值到不了核心 → 模拟不限速
     * 狂奔（30 帧游戏跑 60 帧、速度 2x）→ 音频样本产出速度远超播放
     * 速度 → 缓冲反复覆盖 = 恒定"滋滋滋"白噪声（速度与音频是同一根因）。
     *
     * 本方法在核心真正进入模拟主循环后，经【通道 2】把用户的帧率上限/
     * 布局/性能 hack 直写核心运行时结构体 —— 与原版"打开游戏内设置并
     * 确认"完全同款效果，不依赖 ini 解析链路。
     *
     * 数组布局（Q.java K() + so 0x269520 setRunningSettings 反汇编实证）：
     *   [0]震动 [1]摇杆相对 [2]隐藏overlay [3]手柄缩放 [4]手柄透明度
     *   （前 5 项 Java 侧 overlay 用 —— overlay 已隐藏，值仅占位）
     *   [5]FMV hack [6]skip_slow_draw [7]skip_cpu_write [8]skip_texture_copy
     *   [9]force_texture_filter [10]hw_gs_mode [11]shadow_rendering
     *   [12]async_shader_compile [13]use_compatible_mode
     *   [14]分辨率(0基, 原版运行时面板同款) [15]layout_option
     *   [16]accurate_mul_type [17]custom_layout
     *   [18]frame_limit(%, 核心内 max(v,1)) [19]投屏
     */
    private fun buildRunningSettingsArray(): IntArray {
        fun b(key: String, dflt: Boolean) =
            (coreOptions[key]?.let { it == "true" || it == "enabled" } ?: dflt)
        fun i(key: String, dflt: Int, lo: Int = Int.MIN_VALUE, hi: Int = Int.MAX_VALUE) =
            (coreOptions[key]?.toIntOrNull() ?: dflt).coerceIn(lo, hi)
        // 分辨率：UI 存 1..4，运行时面板 0 基（0=1x）
        val res0based = (i("resolution_factor", 1, 1, 4) - 1).coerceIn(0, 3)
        val customLayout = coreOptions["landscape_custom_layout"] == "true" ||
            coreOptions["portrait_custom_layout"] == "true"
        return intArrayOf(
            0,                                  // [0] 震动（Java 侧，占位 0）
            1,                                  // [1] 摇杆相对中心（占位默认）
            1,                                  // [2] 隐藏 overlay（我们本来就强制隐藏）
            40,                                 // [3] 手柄缩放（占位默认）
            100,                                // [4] 手柄透明度（占位默认）
            if (b("use_fmv_hack", false)) 1 else 0,      // [5] FMV hack
            0,                                  // [6] skip_slow_draw（默认关）
            0,                                  // [7] skip_cpu_write（默认关）
            0,                                  // [8] skip_texture_copy（默认关）
            i("force_texture_filter", 0, 0, 2), // [9] 强制纹理过滤
            i("hw_gs_mode", 0, 0, 2),           // [10] 几何着色器
            if (b("shadow_rendering", false)) 1 else 0,  // [11] 阴影渲染
            if (b("async_shader_compile", true)) 1 else 0, // [12] 异步着色器编译
            if (b("use_compatible_mode", false)) 1 else 0, // [13] 兼容模式
            res0based,                          // [14] 分辨率（0 基）
            i("layout_option", 0, 0, 3),        // [15] 屏幕布局
            i("accurate_mul_type", 0, 0, 2),    // [16] 精确乘法
            if (customLayout) 1 else 0,         // [17] 自定义布局开关
            i("frame_limit", 100, 1, 200),      // [18] 帧率上限 %（核心内 max(v,1)）
            0                                   // [19] 投屏（关）
        )
    }

    /** 推送运行时设置到核心（通道 2）。快进中不推（避免给快进限速）。 */
    private fun pushRunningSettingsNow() {
        try {
            val arr = buildRunningSettingsArray()
            CitraMmjNative.lib.setRunningSettings(arr)
            android.util.Log.i("CitraMmjEngine",
                "setRunningSettings pushed: frame_limit=${arr[18]}% layout=${arr[15]} " +
                "custom=${arr[17]} res=${arr[14] + 1}x")
        } catch (t: Throwable) {
            android.util.Log.w("CitraMmjEngine", "setRunningSettings failed", t)
        }
    }

    /**
     * 运行时设置推送看护线程：轮询 IsRunning（最多 ~20s），核心进入模拟
     * 主循环后再等 600ms（越过引导期），然后推送一次运行时设置。
     */
    private fun startRuntimeSettingsPusher() {
        val pusher = Thread({
            var waited = 0
            while (waited < 20000 && !Thread.currentThread().isInterrupted) {
                try { Thread.sleep(400) } catch (_: InterruptedException) { return@Thread }
                waited += 400
                if (!running.get() || _loaded.not()) return@Thread
                val isRunning = try { CitraMmjNative.lib.IsRunning() } catch (_: Throwable) { false }
                if (isRunning) {
                    try { Thread.sleep(600) } catch (_: InterruptedException) { return@Thread }
                    // 快进中不推（frame_limit 会给快进限速）；退出快进时
                    // applyFastForwardConfig 会重新推送。
                    if (running.get() && _ffSpeed <= 0) pushRunningSettingsNow()
                    return@Thread
                }
            }
        }, "mmj-runtime-settings-pusher")
        pusher.isDaemon = true
        pusher.start()
    }

    override fun setSurface(surface: Surface?) {
        synchronized(lifecycleLock) {
            this.surface = surface
            if (!_loaded) return
            val lib = CitraMmjNative.lib
            if (surface != null) {
                // ★★ 同 startEmulationLocked 守卫：无效 surface 进 SurfaceChanged
                //   会触发 VK 后端 vkCreateAndroidSurfaceKHR CHECK → SIGTRAP at
                //   pc 0x282234（详见 startEmulationLocked 注释）。
                if (surface.isValid) {
                    try { lib.SurfaceChanged(surface); surfaceAttached = true } catch (_: Throwable) {}
                    // ★ 全局缩放跟随（同 onSurfaceChanged）：SurfaceView 改变
                    //   宽高比时 Android 会重建 Surface —— 换新 surface 挂到
                    //   运行中的核心后同样要 WindowChanged() 让核心按新窗口
                    //   几何重算双屏布局。
                    val running = try { lib.IsRunning() } catch (_: Throwable) { false }
                    if (running) {
                        try { lib.WindowChanged() } catch (_: Throwable) {}
                    }
                } else {
                    android.util.Log.w("CitraMmjEngine",
                        "setSurface: surface valid=false, defer SurfaceChanged to emu thread")
                }
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
        // ★★ 本轮根治：SetDisplayInfo 只在核心真正进入模拟主循环后下发。★★
        //   旧守卫是 emuThread?.isAlive —— 但 boot 线程在 3 秒等 surface 窗口期
        //   和 Run() 引导早期（EmuWindow 尚未构造完成）同样是 alive 状态！
        //   引导窗口内调 SetDisplayInfo → 原生 EmuWindow 状态机 UNREACHABLE
        //   → brk #1 → SIGTRAP（pc 0x282234）→ "MMJ 运行游戏闪退"。
        //   IsRunning() 返回核心模拟主循环的真实运行标志（引导期 false），
        //   是唯一可靠的"EmuWindow 已就绪"指示。
        if (_loaded && width > 0 && height > 0 && emuThread?.isAlive == true) {
            val coreRunning = try { CitraMmjNative.lib.IsRunning() } catch (_: Throwable) { false }
            if (coreRunning) {
                // ★★★ 本轮根治：传【真实】旋转角/挖孔边距/密度 ★★★
                //   （原版 EmulationActivity.N0() 反编译实证语义：
                //    SetDisplayInfo(safeInsetLeft, safeInsetTop, safeInsetRight,
                //                    safeInsetBottom, rotation, scaledDensity)）
                //   旧实现永远传 rotation=0 —— 横屏游玩时核心按竖屏几何
                //   计算双屏布局 → 全局缩放/布局怎么调都不对（"画面不
                //   跟随全局缩放"的另一半根因）。现在从 appContext 读取
                //   真实 display rotation；挖孔边距经 Display.getCutout()
                //   反射获取（失败传 0 —— 无挖孔设备等价原版）；密度取
                //   resources 显示 scaledDensity（overlay 已隐藏，仅对
                //   overlay 缩放有意义）。
                try {
                    val ctx = appContext
                    var rotation = 0
                    var insets = intArrayOf(0, 0, 0, 0)
                    var density = 1f
                    if (ctx != null) {
                        @Suppress("DEPRECATION")
                        val disp = (ctx.getSystemService(android.content.Context.WINDOW_SERVICE)
                            as? android.view.WindowManager)?.defaultDisplay
                        rotation = disp?.rotation ?: 0
                        // 挖孔屏安全边距：Display.getCutout()（API 28+，
                        // @UnsupportedAppUsage 但各厂商 ROM 普遍可反射调用；
                        // 失败 = 无挖孔 = 全 0，与原版无挖孔分支一致）
                        if (android.os.Build.VERSION.SDK_INT >= 28) {
                            try {
                                val m = android.view.Display::class.java
                                    .getDeclaredMethod("getCutout")
                                m.isAccessible = true
                                val cutout = m.invoke(disp) as? android.view.DisplayCutout
                                if (cutout != null) {
                                    insets = intArrayOf(
                                        cutout.safeInsetLeft, cutout.safeInsetTop,
                                        cutout.safeInsetRight, cutout.safeInsetBottom)
                                }
                            } catch (_: Throwable) { /* 无挖孔/权限限制 */ }
                        }
                        density = try {
                            ctx.resources.displayMetrics.scaledDensity
                        } catch (_: Throwable) { 1f }
                    }
                    CitraMmjNative.lib.SetDisplayInfo(
                        insets[0], insets[1], insets[2], insets[3], rotation, density)
                } catch (_: Throwable) {
                    // 兜底：任何一步失败都不阻塞窗口更新
                    try { CitraMmjNative.lib.SetDisplayInfo(0, 0, 0, 0, 0, 1f) } catch (_: Throwable) {}
                }
                // ★★★ 全局缩放跟随（本轮根治）★★★
                // SurfaceView 随「画面缩放」(videoScale) 改变宽高比时，
                // surfaceChanged 携带新几何回调到这里。MMJ 核心的双屏布局
                // 不是每帧重算的 —— 必须显式 WindowChanged()（so 0x264dc0：
                // 对 EmuWindow 全局对象调虚方法重算布局）才会按新窗口
                // 几何重新排版上下屏。旧实现从不调用 → 改全局缩放后核心
                // 仍按旧布局渲染 → "游戏画面没有根据全局缩放来"。
                try { CitraMmjNative.lib.WindowChanged() } catch (_: Throwable) {}
            }
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
     * 快进：写 frame_limit / use_frame_limit 到 ini（MMJ 无运行时变速 JNI；
     * use_frame_limit=false = 不限速）。核心启动时读 ini 生效 —— 快进
     * 效果同"下次启动生效"（与设置面板提示一致）。
     *
     * ★ 旧实现经 setConfigBoolean("use_frame_limit")/setConfigInteger(
     *   "frame_limit") 下发 —— 两键都不在 JNI 分发器接受集内 → brk #1
     *   SIGTRAP 闪退。改为 ini 文件写入（原版设置编辑器同款机制）。
     */
    private fun applyFastForwardConfig() {
        synchronized(lifecycleLock) {
            if (!_loaded) return
            writeMmjIniLocked(
                if (_ffSpeed > 0) mapOf("use_frame_limit" to "false")
                else mapOf(
                    // ★ 退出快进恢复【用户设置值】（旧实现硬编码 100，会覆盖
                    //   用户在设置里选的帧率上限百分比）。
                    "use_frame_limit" to (coreOptions["use_frame_limit"] ?: "true"),
                    "frame_limit" to (coreOptions["frame_limit"] ?: "100")
                )
            )
            // ★ 快进开关即时生效：重读 ini + 通知核心（MMJ 帧率限制由核心
            //   每帧消费 config，loadConfig 后立即生效，无需重启）。
            try { CitraMmjNative.lib.loadConfig() } catch (_: Throwable) {}
            // ★ 本轮新增：退出快进时经通道 2 重推运行时 frame_limit（与
            //   启动推送同款），双保险恢复限速；进入快进不推（运行时
            //   frame_limit 会给快进封顶）。
            if (_ffSpeed <= 0) {
                val isRunning = try { CitraMmjNative.lib.IsRunning() } catch (_: Throwable) { false }
                if (isRunning) pushRunningSettingsNow()
            }
        }
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    /**
     * MMJ 配置键直通（key = MMJ 配置键名，如 "resolution_factor" /
     * "use_hw_shader"）。设置持久化到 config/config-mmj.ini，下次启动
     * loadConfig 读取生效；运行中变更立即写盘（多数键核心下次启动生效）。
     *
     * ★ 绝不经 setConfig* JNI 下发（分发器仅接受 8 键，其余 brk #1 闪退，
     *   详见 MMJ_INI_SECTION 注释）。
     */
    /**
     * ★★★ 设置批量事务 + 布局热生效（本轮新增）★★★
     * EmulatorScreen.applyCoreOptions 把整批设置包在 begin/end 之间 ——
     * 一次面板变更/编辑器拖动连发几十个 setCoreOption。旧实现每个键都
     * 全量重写 ini（文件 IO × 50），拖动自定义布局时更会每键都触发核心
     * 重载。现在 begin/end 之间只缓存，end 时统一：写一次 ini +
     * （若含布局族键）loadConfig() 重读 + WindowChanged() 热重算 ——
     * 自定义布局拖动即时生效（所见即所得），其余键下次启动生效（与
     * 原版行为一致）。
     */
    private var optionsBatching = false
    private var optionsBatchDirty = false
    private var optionsBatchLayoutDirty = false

    override fun setCoreOption(key: String, value: String) {
        coreOptions[key] = value
        if (!MMJ_INI_SECTION.containsKey(key)) return
        synchronized(lifecycleLock) {
            if (optionsBatching) {
                optionsBatchDirty = true
                if (key in MMJ_LAYOUT_HOT_KEYS) optionsBatchLayoutDirty = true
                return
            }
            if (!_loaded) return
            writeMmjIniLocked()
            applyLayoutHotReloadIfNeeded(key)
        }
    }

    /** 单键（非批量）路径的布局热生效。 */
    private fun applyLayoutHotReloadIfNeeded(vararg keys: String) {
        if (keys.none { it in MMJ_LAYOUT_HOT_KEYS }) return
        try { CitraMmjNative.lib.loadConfig() } catch (_: Throwable) {}
        val running = try { CitraMmjNative.lib.IsRunning() } catch (_: Throwable) { false }
        if (running) {
            try { CitraMmjNative.lib.WindowChanged() } catch (_: Throwable) {}
        }
    }

    override fun beginCoreOptionsBatch() {
        synchronized(lifecycleLock) {
            optionsBatching = true
            optionsBatchDirty = false
            optionsBatchLayoutDirty = false
        }
    }

    override fun endCoreOptionsBatch() {
        synchronized(lifecycleLock) {
            optionsBatching = false
            val dirty = optionsBatchDirty
            val layoutDirty = optionsBatchLayoutDirty
            optionsBatchDirty = false
            optionsBatchLayoutDirty = false
            if (!dirty || !_loaded) return
            writeMmjIniLocked()
            if (layoutDirty) applyLayoutHotReloadIfNeeded("layout_option")
        }
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /**
     * 按键（数字通道）—— 只走 InputEvent 固定索引（原版 overlay 同款）。
     *
     * 索引表（原版 InputOverlay 构造表反编译实证）：
     *   A=0 B=1 X=2 Y=3 | 十字键 up=4 down=5 left=6 right=7 | L=8 R=9 |
     *   start=10 select=11 home=12 | zl=14 zr=15
     *
     * ★ 旧的 KeyEvent(自选id) 通道已删除：那套需要先用 setConfigInteger 把
     *   button_* 键写成自选 id —— setConfigInteger 分发器只接受
     *   input_overlay_alpha/scale 两键，button_* 一律 brk #1 → SIGTRAP
     *   （"MMJ 启动游戏闪退"的根因之一）。InputEvent 通道零配置、与原版
     *   虚拟按键完全等价，且覆盖 home/zl/zr 全键位。
     */
    override fun setPad1(bits: Int) {
        if (!_loaded) return
        val lib = CitraMmjNative.lib
        fun pressed(bit: Int) = bits and bit != 0
        val idxEvents = intArrayOf(
            IDX_A, IDX_B, IDX_X, IDX_Y,
            IDX_L, IDX_R, IDX_START, IDX_SELECT,
            IDX_UP, IDX_DOWN, IDX_LEFT, IDX_RIGHT,
            IDX_HOME, IDX_ZL, IDX_ZR
        )
        val states = booleanArrayOf(
            pressed(BIT_A), pressed(BIT_B), pressed(BIT_X), pressed(BIT_Y),
            pressed(BIT_L), pressed(BIT_R), pressed(BIT_START), pressed(BIT_SELECT),
            pressed(BIT_UP), pressed(BIT_DOWN), pressed(BIT_LEFT), pressed(BIT_RIGHT),
            pressed(BIT_HOME), pressed(BIT_ZL), pressed(BIT_ZR)
        )
        for (i in idxEvents.indices) {
            try { lib.InputEvent(idxEvents[i], if (states[i]) 1f else 0f) } catch (_: Throwable) {}
        }
    }

    override fun setPad2(bits: Int) {
        // MMJ 触屏设备为 P1 专属 —— P2 不支持
    }

    /**
     * 双摇杆：CirclePad (lx, ly) / C-Stick (rx, ry) —— InputEvent 模拟量
     * 通道（原版 overlay/b.java k() 同款）：
     *   圆盘 = InputEvent(21, x) / InputEvent(22, y)，C 摇杆 = 23/24。
     *
     * ★ Y 轴符号：原版 k() 实证【+1 = 向上】（触摸上 1/3 → fArr[1]=+1）；
     *   NesStation 的 setAnalogAxes 是屏幕坐标（上=负，AzaharEngine 同款
     *   注释实证）—— 下发前对 Y 取反。X 轴两侧一致（左=-1 右=+1）直传。
     * ★ 旧的 MoveEvent(自选轴码) 通道已删除（同 setPad1 注释的根因）。
     */
    override fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float) {
        if (!_loaded) return
        val lib = CitraMmjNative.lib
        fun send(idx: Int, v: Float, lastIdx: Int) {
            val clamped = v.coerceIn(-1f, 1f)
            if (clamped != stickLast[lastIdx]) {
                stickLast[lastIdx] = clamped
                try { lib.InputEvent(idx, clamped) } catch (_: Throwable) {}
            }
        }
        send(IDX_CPAD_X, lx, 0)
        send(IDX_CPAD_Y, -ly, 1)      // Y 取反：屏幕坐标（上=负）→ MMJ（上=+1）
        send(IDX_CSTICK_X, rx, 2)
        send(IDX_CSTICK_Y, -ry, 3)
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
        // ★ ini 读-改-写（原版设置编辑器同款机制）。旧实现的
        //   getConfigBoolean/setConfigBoolean("landscape_swap_screen") 两键
        //   都不在 JNI 分发器接受集内 → brk #1 SIGTRAP 闪退（已反汇编实证）。
        //   写入 ini 后 loadConfig() 重读 + WindowChanged() 【即时生效】
        //   （swap_screen 属布局族热键，无需重启游戏）。
        synchronized(lifecycleLock) {
            val current = readMmjIniValue("landscape_swap_screen")
                ?.equals("true", true) ?: false
            writeMmjIniLocked(
                mapOf(
                    "landscape_swap_screen" to (!current).toString(),
                    "portrait_swap_screen" to (!current).toString()
                )
            )
            applyLayoutHotReloadIfNeeded("landscape_swap_screen")
        }
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
