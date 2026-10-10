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
 * ★★★ 滤镜全无效根治（本轮，libmain.so 反汇编 + 原版 APK 反编译双源实证）★★★
 * 原版 boot 主体带包名指纹白名单（versionName "f805c929d" / 包名
 * org.citra.emu / 魔数 0x00d660c4f937902a / "Citra" 串，四道检查），
 * 非原版包每次启动都会命中"降级清理块"：use_gles[0x408] 等渲染标志被
 * 强制清零 + 受限位[0]=1 → 渲染器工厂（0x3d4f54）落到桌面 GL 渲染器，
 * 其后处理管线在 GLES 上下文静默不生效 → 全局 xbr/hqx 映射与自带
 * 全部 .glsl 滤镜无一可见。修复 = jniLibs 里的 libcitra_mmj.so 已打
 * 二进制补丁（四个清理块 NOP + 工厂强制走 GL 分支），详见
 * CitraMmjNative.kt 头注释；切勿用原版未补丁 libmain.so 覆盖。
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
 *    L=8 R=9 start=10 select=11 zl=14 zr=15；
 *    ★ 绝不下发 12（本核心的 enum 12 = 加速键非 HOME，见 setPad1 注释）
 *    ★ 真正的 HOME（切屏）是 enum 16，同样不下发（切屏走我们自己的按钮）
 *  - 摇杆经 InputEvent(索引, 模拟值)：圆盘 X=21 Y=22，C 摇杆 X=23 Y=24
 *    （Y 为屏幕坐标约定：+1=向下 —— overlay/c.java + 上游 SetStatus(-y)
 *    双重实证）；
 *  - 底屏触摸经 TouchEvent（action 位掩码：1=按下 / 2=移动 / 4=抬起）；
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
        // ★ 索引 12 = 本核心的【加速(Turbo)键】（so KeyEvent 反汇编实证，
        //   长按 ≥0.35s → frame_limit=200 即 2 倍速）—— 不是 HOME！
        //   绝不从 setPad1 下发（幽灵按下 = “突然 2 倍速”根因，见 setPad1）。
        //   ★★★ 本轮（快进根治）：快进按钮改经本通道驱动 —— InputEvent(12, 1f)
        //   = 按住加速键（350ms 后 2x，原版 APK 同款机制，反汇编 0x25cc8c
        //   实证：直接写 frame_limit=200 到运行时 Settings），InputEvent(12, 0f)
        //   = 松开（自动恢复原 frame_limit）。旧方案仅靠 ini frame_limit%/
        //   setRunningSettings[18] —— 该值在 renderer 初始化时被换算成
        //   skip 指标后不再重读（运行时改值无效），用户实测“无法快进”。
        private const val IDX_TURBO = 12
        private const val IDX_ZL = 14        // 原版 overlay：new a("button_zl", 14)
        private const val IDX_ZR = 15        // 原版 overlay：new a("button_zr", 15)
        // 摇杆（joystick=21 / c_stick=23；c.java k() 模拟量通道，X=base+0，Y=base+1）
        // ★ Y 约定 = 屏幕坐标（+1 = 向下）：原版 c.java k() 实证
        //   dArr[1]=(touchY-centerY)/(bottom-centerY) —— 触摸在中心下方为正；
        //   上游 Joystick::SetStatus 内部再取反（y=clamp(-y)）进 Citra 核心。
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
        // BIT_HOME(0x80000) 不再映射：本核心无安全可用的 HOME 通道
        // （enum 12 = 加速键，enum 16 = 切屏热键，都与 HOME 语义不符）。

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
                        // ★★★ v5：shaders 目录【无条件覆盖】★★★
                        //   其余目录保持幂等跳过（原版 overwrite=FALSE 语义，
                        //   用户数据/系统 title 不能被种子回滚）。着色器不同：
                        //   它们是随 APK 分发的"代码"，v5 曾修复 xBR.glsl 的
                        //   GLSL ES 3.20 常量表达式编译错误 —— 旧包种下去的
                        //   坏文件如果靠"存在即跳过"永远留在用户设备上，
                        //   原生编译失败静默回落直通 = "滤镜无效"复发。
                        //   14 个小文件（~100KB）每次启动覆盖成本可忽略。
                        val forceOverwrite = assetPath.startsWith("shaders/")
                        if (!forceOverwrite && outFile.isFile && outFile.length() > 0L) continue
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
                // v5：shaders/ 每次启动都会覆盖 → copied 几乎总是 > 0，
                //   日志不再叫 "first launch"（避免误导排查）。
                android.util.Log.i("CitraMmjEngine",
                    "seeded $copied MMJ system files (shaders force-refreshed)")
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
        "portrait_bottom_right", "portrait_bottom_bottom",
        // ★ 本轮新增：show_fps（FPS 显示）也走热生效链（写 ini → loadConfig
        //   重读 → WindowChanged 无害重算布局）—— 全局"显示帧数"开关在
        //   游戏中切换即时生效，无需重进游戏。
        "show_fps"
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

    // ------------------------------------------------------------------
    // ★★ V14 前端滤镜原生布局（MmjFilterView 原生分辨率中转的配套）★★
    // ------------------------------------------------------------------
    //
    // 【为什么需要】像素网格类后处理滤镜（xBR/4xBR/HQ2X/HQ4X/SEDI）假设
    // color_texture 是原生低分辨率 framebuffer（上屏 400×240）。前端
    // MmjFilterView 的 SurfaceTexture 中转原本按窗口尺寸渲染 —— 滤镜在
    // 已被核心双线性放大的窗口分辨率图像上完全退化（"滤镜编译成功但
    // 无效果"的根因）。
    //
    // 【机制】滤镜激活时（MmjFilterView → mmjNativeFilterLayout + setMmjNativeFilterMode(true)）：
    //   1. 把核心双屏布局强制为【已知几何的自定义布局】（portrait/landscape
    //      custom_layout=true + 16 矩形键 = 原生像素坐标），每次写 ini 都由
    //      writeMmjIniLocked 的钩子重新强制（不被 applyCoreOptions 批量
    //      重写洗掉）；
    //   2. SurfaceTexture 缓冲同步切到原生布局尺寸 → 核心在缓冲内 1:1
    //      画出原生像素网格；
    //   3. 前端滤镜着色器以原生纹理为源，按 fit 语义放大绘制 ——
    //      滤镜在真正的原生网格上工作。
    //
    // ★★ V14 根治（"滤镜生效但画面被放大"）★★
    //   V13 无视窗口宽高比，硬编码"竖屏堆叠 400×480 / 横屏并排 720×240"
    //   + letterbox 居中 —— 与核心默认布局（framebuffer_layout.cpp
    //   DefaultFrameLayout）完全不符：
    //     - 核心默认布局【与旋转家族无关】，只取决于窗口宽高比：
    //       宽窗（H/W < 1.2）= 两行网格（上屏居中于上半行、下屏居中于
    //       下半行，行高各半窗 → 内容铺满整窗）；长窗（H/W ≥ 1.2）= 同
    //       标尺上下堆叠且【顶部对齐】（内容下方留黑）；
    //       横屏手机（H/W ≈ 0.45）走"两行网格"，每屏只有半窗高 ——
    //       V13 强设并排 720×240 → 每屏放大 ~1.48×（用户截图实证）；
    //       竖屏走堆叠但 V13 letterbox 居中 ≠ 核心顶对齐（位置漂移）。
    //   - V14：按【视图真实宽高】复刻 DefaultFrameLayout / SideFrameLayout
    //     的精确几何（上屏锚定 400×240 原生像素），缓冲 = 同宽高比原生
    //     网格；前端按 fit 语义绘制：宽窗=铺满（与核心满窗绘制一致）、
    //     长窗=顶对齐、并排=居中。滤镜生效且画面几何与无滤镜时逐像素一致。
    //   - 矩形随 swap_screen（上/下屏交换）同步对调；家族布局随视图尺寸
    //     变化由视图逐帧查询刷新（存储 + ini 重写 + loadConfig 热生效）。
    //
    // 【不劫持的用户设置】前端自定义布局编辑器（portrait/landscape_custom_
    // layout=true 时）优先 —— 原生模式自动退出；单屏/大屏布局函数的专属
    // 几何不强行替代 —— 同样退出（滤镜效果退化，布局绝不破坏）。
    // 关闭滤镜时恢复启用前保存的矩形键原值 + 开关回用户值（不污染
    // 自定义布局编辑器的数据）。

    /** 原生滤镜布局强制中（true 时 writeMmjIniLocked 每次落盘重新强制）。 */
    @Volatile private var nativeFilterLayout = false

    /** 关闭后待恢复标志（下一次 writeMmjIniLocked 归还原值）。 */
    @Volatile private var restoreLayoutPending = false

    /** 启用前保存的用户布局键原值（来自 ini；关闭时恢复）。 */
    private var savedLayoutValues: Map<String, String>? = null

    /** 滤镜布局劫持涉及的全部 ini 键（16 矩形 + 2 开关）。 */
    private val MMJ_FILTER_LAYOUT_KEYS = listOf(
        "portrait_custom_layout", "landscape_custom_layout",
        "portrait_top_left", "portrait_top_top", "portrait_top_right", "portrait_top_bottom",
        "portrait_bottom_left", "portrait_bottom_top", "portrait_bottom_right", "portrait_bottom_bottom",
        "landscape_top_left", "landscape_top_top", "landscape_top_right", "landscape_top_bottom",
        "landscape_bottom_left", "landscape_bottom_top", "landscape_bottom_right", "landscape_bottom_bottom"
    )

    private val MMJ_RECT_SUFFIXES = listOf(
        "top_left", "top_top", "top_right", "top_bottom",
        "bottom_left", "bottom_top", "bottom_right", "bottom_bottom"
    )

    // ---- 前端视口拟合语义（MmjFilterView 按 fit 绘制缓冲）----
    /** letterbox 居中（并排布局 / 用户自定义布局的包围盒 —— V13 行为）。 */
    val MMJ_NATIVE_FIT_CENTERED = 0
    /** 铺满整窗（宽窗两行网格：核心本就满窗绘制，缓冲与窗口同宽高比）。 */
    val MMJ_NATIVE_FIT_STRETCH = 1
    /** 顶部对齐（长窗堆叠：核心默认布局内容顶对齐、下方留黑）。 */
    val MMJ_NATIVE_FIT_TOP_ALIGNED = 2

    /** 每个旋转家族的原生布局：矩形键值 + 缓冲尺寸 + 前端视口拟合方式。 */
    internal data class NativeFamilyLayout(
        val rects: Map<String, String>,
        val bufW: Int,
        val bufH: Int,
        val fit: Int
    )

    /** 视图查询时存储的各家族布局（writeMmjIniLocked 钩子落盘用）。 */
    @Volatile private var storedNativeLayoutPortrait: NativeFamilyLayout? = null
    @Volatile private var storedNativeLayoutLandscape: NativeFamilyLayout? = null

    /**
     * 查询前端滤镜原生缓冲布局（MmjFilterView 决定 SurfaceTexture 缓冲 +
     * 视口拟合用），并存储供 ini 钩子落盘。
     *
     * 查询到与上次不同的布局（旋转 / 窗口比例 / 交换屏幕变化）时：
     * 若原生滤镜模式已激活则立即重写 ini + loadConfig/WindowChanged 热生效
     * —— 视图随后重设缓冲尺寸并再次 onSurfaceChanged，核心按新矩形重排。
     * 查询返回 null（当前设置不支持原生中转）时清空该家族存储 → ini 钩子
     * 自愈关闭原生模式。
     *
     * @param portraitFamily true = 竖屏旋转家族（ROTATION_0/180）
     * @param viewW/viewH    视图（SurfaceView）真实像素尺寸
     * @return 原生布局；null = 当前布局设置不支持原生中转
     */
    internal fun mmjNativeFilterLayout(portraitFamily: Boolean, viewW: Int, viewH: Int): NativeFamilyLayout? =
        synchronized(lifecycleLock) {
            val layout = try {
                computeNativeFamilyLayout(portraitFamily, viewW, viewH)
            } catch (_: Throwable) { null }
            val prev = if (portraitFamily) storedNativeLayoutPortrait else storedNativeLayoutLandscape
            if (layout != prev) {
                if (portraitFamily) storedNativeLayoutPortrait = layout
                else storedNativeLayoutLandscape = layout
                // 家族几何变化 → 落盘新矩形（两家族）+ 热生效。核心下次
                // WindowChanged 即按新矩形重排（缓冲尺寸由视图同步重设）。
                if (_loaded && nativeFilterLayout) {
                    writeMmjIniLocked()
                    applyLayoutHotReloadIfNeeded(*MMJ_FILTER_LAYOUT_KEYS.toTypedArray())
                }
            }
            layout
        }

    /**
     * 计算一个旋转家族的原生布局。返回 null = 该家族当前设置不支持
     * 原生中转（调用方整体退出，不半支持）。
     *
     * 支持情形（★ V14 起几何与核心默认布局函数逐像素同构）：
     *   - 默认布局（opt 0）：宽窗 = 两行网格（缓冲同宽高比 + 铺满）；
     *     长窗 = 同标尺堆叠（400×480 + 顶对齐）；
     *   - 并排布局（opt 3）：长窗 = 堆叠变体（400×540 + 顶对齐，下屏
     *     等宽上屏 —— 核心原样怪癖）；否则 = 左右并排（720×240 + 居中）；
     *   - 用户自定义布局：矩形等比缩放到原生网格（上屏宽 400 锚定），
     *     缓冲 = 缩放后矩形包围盒（letterbox 居中 —— V13 行为保留）。
     */
    private fun computeNativeFamilyLayout(portraitFamily: Boolean, viewW: Int, viewH: Int): NativeFamilyLayout? {
        val prefix = if (portraitFamily) "portrait" else "landscape"
        if (coreOptions["${prefix}_custom_layout"] == "true") {
            // 用户自定义布局：等比缩放（防负/零宽矩形）
            val tl = coreOptions["${prefix}_top_left"]?.toIntOrNull()
            val tr = coreOptions["${prefix}_top_right"]?.toIntOrNull()
            if (tl == null || tr == null || tr <= tl) return null
            val k = 400f / (tr - tl)
            if (k <= 0f || k > 8f) return null
            val rects = LinkedHashMap<String, String>()
            var maxR = 0
            var maxB = 0
            for (suffix in MMJ_RECT_SUFFIXES) {
                val v = coreOptions["${prefix}_$suffix"]?.toIntOrNull() ?: 0
                val scaled = (v * k).toInt().coerceIn(0, 4095)
                rects["${prefix}_$suffix"] = scaled.toString()
                if ((suffix == "top_right" || suffix == "bottom_right") && scaled > maxR) maxR = scaled
                if ((suffix == "top_bottom" || suffix == "bottom_bottom") && scaled > maxB) maxB = scaled
            }
            if (maxR <= 0 || maxB <= 0) return null
            return NativeFamilyLayout(rects, maxR, maxB, MMJ_NATIVE_FIT_CENTERED)
        }
        val opt = coreOptions[if (portraitFamily) "layout_option" else "landscape_layout_option"] ?: "0"
        val swap = coreOptions[if (portraitFamily) "portrait_swap_screen" else "landscape_swap_screen"] == "true"
        return when {
            // 默认布局（opt 0，两家族同构 —— 核心几何只取决于窗口宽高比）
            opt == "0" -> defaultNativeLayout(viewW, viewH, prefix, swap)
            // 并排布局（opt 3，两家族同构）
            opt == "3" -> sideNativeLayout(viewW, viewH, prefix, swap)
            // 单屏(1)/大屏(2)：布局函数专属几何，不强行替代
            else -> null
        }
    }

    /** 写入某屏矩形键（left/top/right/bottom）。 */
    private fun putNativeRect(
        rects: LinkedHashMap<String, String>,
        prefix: String,
        screen: String,
        l: Int, t: Int, r: Int, b: Int
    ) {
        rects["${prefix}_${screen}_left"] = l.toString()
        rects["${prefix}_${screen}_top"] = t.toString()
        rects["${prefix}_${screen}_right"] = r.toString()
        rects["${prefix}_${screen}_bottom"] = b.toString()
    }

    /**
     * 默认布局（opt 0）的原生几何 —— 与核心 DefaultFrameLayout
     * （framebuffer_layout.cpp）逐像素同构，上屏锚定 400×240（1:1 网格）：
     *
     * 宽窗（H/W < 1.2）：两行网格。核心窗口内：上屏 scale=min(W, H/1.2)
     * 居中于上半行、下屏 scale'=min(W, H/1.6) 居中于下半行，行高各半窗
     * → 内容铺满整窗。原生等比：缓冲 H/W×480×480（行高各 240），上屏
     * 400×240 居中于上半行、下屏 320×240 居中于下半行，前端【铺满】。
     *
     * 长窗（H/W ≥ 1.2）：同标尺堆叠、顶部对齐。核心窗口内：上屏 W×0.6W
     * 在 y=0、下屏 0.8W×0.6W 居中于其下，内容高 1.2W 下方留黑。原生
     * 等比：缓冲 400×480，上屏 (0,0,400,240)、下屏 (40,240,360,480)，
     * 前端【顶部对齐】。
     */
    private fun defaultNativeLayout(viewW: Int, viewH: Int, prefix: String, swap: Boolean): NativeFamilyLayout {
        val rects = LinkedHashMap<String, String>()
        val wide = viewW > 0 && viewH > 0 &&
            (viewH.toFloat() / viewW.toFloat()) < 1.2f
        if (wide) {
            // 两行网格：缓冲与窗口同宽高比（行高各 240 原生像素）
            val bufW = kotlin.math.roundToInt(viewW.toDouble() / viewH.toDouble() * 480.0)
                .coerceIn(400, 4095)
            val topL = (bufW - 400) / 2          // 上屏 400×240 居中
            val botL = (bufW - 320) / 2          // 下屏 320×240 居中
            if (swap) {
                // 交换屏幕（核心 swapped 分支：上屏移到下半行、下屏留上半行）
                // —— 矩形键标识"哪块屏"：top_* = 上屏（400×240，下半行），
                //     bottom_* = 下屏（320×240，上半行）
                putNativeRect(rects, prefix, "top", topL, 240, topL + 400, 480)
                putNativeRect(rects, prefix, "bottom", botL, 0, botL + 320, 240)
            } else {
                putNativeRect(rects, prefix, "top", topL, 0, topL + 400, 240)
                putNativeRect(rects, prefix, "bottom", botL, 240, botL + 320, 480)
            }
            return NativeFamilyLayout(rects, bufW, 480, MMJ_NATIVE_FIT_STRETCH)
        }
        if (swap) {
            // 长窗堆叠 + 交换：下屏在上（320×240 居中），上屏在下
            putNativeRect(rects, prefix, "top", 0, 240, 400, 480)
            putNativeRect(rects, prefix, "bottom", 40, 0, 360, 240)
        } else {
            putNativeRect(rects, prefix, "top", 0, 0, 400, 240)
            putNativeRect(rects, prefix, "bottom", 40, 240, 360, 480)
        }
        return NativeFamilyLayout(rects, 400, 480, MMJ_NATIVE_FIT_TOP_ALIGNED)
    }

    /**
     * 并排布局（opt 3）的原生几何 —— 与核心 SideFrameLayout 同构：
     *
     * 长窗（H > 1.5W）：堆叠变体。核心窗口内：上屏 W×0.6W 顶对齐，下屏
     * 绘制矩形 = 上屏【等宽】（W×0.75W，位于其下）—— 下屏以 1.25× 上屏
     * 标尺绘制（核心原样怪癖，比例仍 4:3 不变形）。原生等比：缓冲
     * 400×540，上屏 (0,0,400,240)、下屏 (0,240,400,540)，前端【顶对齐】。
     *
     * 其余窗口：左右并排 fit-inside 居中 → 缓冲 720×240，上屏
     * (0,0,400,240)、下屏 (400,0,720,240)，前端【居中】（letterbox）。
     */
    private fun sideNativeLayout(viewW: Int, viewH: Int, prefix: String, swap: Boolean): NativeFamilyLayout {
        val rects = LinkedHashMap<String, String>()
        val stacked = viewW > 0 && viewH > 0 && viewH > viewW + viewW / 2
        if (stacked) {
            if (swap) {
                // 交换：下屏矩形在上（0,0,400,300），上屏矩形在其下
                putNativeRect(rects, prefix, "top", 0, 300, 400, 540)
                putNativeRect(rects, prefix, "bottom", 0, 0, 400, 300)
            } else {
                putNativeRect(rects, prefix, "top", 0, 0, 400, 240)
                putNativeRect(rects, prefix, "bottom", 0, 240, 400, 540)
            }
            return NativeFamilyLayout(rects, 400, 540, MMJ_NATIVE_FIT_TOP_ALIGNED)
        }
        if (swap) {
            // 交换：下屏在左，上屏在右（核心 swapped 分支同款）
            putNativeRect(rects, prefix, "top", 320, 0, 720, 240)
            putNativeRect(rects, prefix, "bottom", 0, 0, 320, 240)
        } else {
            putNativeRect(rects, prefix, "top", 0, 0, 400, 240)
            putNativeRect(rects, prefix, "bottom", 400, 0, 720, 240)
        }
        return NativeFamilyLayout(rects, 720, 240, MMJ_NATIVE_FIT_CENTERED)
    }

    /**
     * 前端滤镜原生布局开关（MmjFilterView 挂载/卸载核心 surface 时调用）。
     *
     * 启用：保存用户布局键原值（ini 现值）→ 置标志 → 立即落盘（钩子强制
     * 矩形）+ loadConfig/WindowChanged 热生效；未加载时仅记状态 ——
     * loadRom/启动线程随后的 writeMmjIniLocked 会带上钩子（boot 必然
     * 在本调用之后，因为 boot 线程等待的正是本视图交出的 surface）。
     *
     * 关闭：清标志 + 置恢复待处理 → 落盘（钩子归还原值）+ 热生效。
     */
    fun setMmjNativeFilterMode(active: Boolean) {
        synchronized(lifecycleLock) {
            if (nativeFilterLayout == active && !restoreLayoutPending) return
            nativeFilterLayout = active
            if (active) {
                // 保存 ini 里布局键的用户现值（无键 = 原本就没设）
                val saved = LinkedHashMap<String, String>()
                for (key in MMJ_FILTER_LAYOUT_KEYS) {
                    readMmjIniValue(key)?.let { saved[key] = it }
                }
                savedLayoutValues = saved
                restoreLayoutPending = false
            } else {
                restoreLayoutPending = true
            }
            if (!_loaded) return
            writeMmjIniLocked()
            applyLayoutHotReloadIfNeeded("layout_option")
            android.util.Log.i("CitraMmjEngine",
                "V13 native filter layout mode -> $active (restored=${!active})")
        }
    }

    /** 恢复用户布局键原值（custom 开关回用户值，矩形键还原/删除）。 */
    private fun restoreUserLayoutLocked(
        sections: LinkedHashMap<String, LinkedHashMap<String, String>>
    ) {
        val layout = sections.getOrPut("Layout") { LinkedHashMap() }
        layout["portrait_custom_layout"] = coreOptions["portrait_custom_layout"] ?: "false"
        layout["landscape_custom_layout"] = coreOptions["landscape_custom_layout"] ?: "false"
        val saved = savedLayoutValues
        for (key in MMJ_FILTER_LAYOUT_KEYS) {
            if (key == "portrait_custom_layout" || key == "landscape_custom_layout") continue
            val v = saved?.get(key)
            if (v != null) layout[key] = v else layout.remove(key)
        }
        savedLayoutValues = null
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
            // ★★★ 本轮：MMJ 自带帧数角标恒关（用户要求去除）★★★
            //   [Renderer] show_fps 上游默认 true（main_settings.cpp SHOW_FPS
            //   缺省值）—— OSD（on_screen_display.cpp 0x420 附近）按它绘制
            //   左上角 FPS。NesStation 无此用户设置项 → 恒写 false（与
            //   input_overlay_hide 同款硬编码覆盖语义）。键已注册于
            //   MMJ_INI_SECTION（[Renderer]）与 MMJ_LAYOUT_HOT_KEYS
            //   （游戏中写该键走 loadConfig+WindowChanged 热生效链）。
            overrides["show_fps"] = "false"
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
            // ★★★ V12 根治（"mmj 全部滤镜无效"最终修复，滤镜消费链唯一化）★★★
            //
            // 【历史】本函数曾把用户选定的滤镜名写进 ini pp_shader_name，指望
            //   核心内链（渲染器初始化 0x4254c4 读名 → VFS 打开
            //   <userDir>/shaders/<name>.glsl → DrawScreens 应用）吃掉滤镜。
            //   实测（种子链就位 + 渲染器工厂已补丁 + VFS 相对路径解析就位后）
            //   【依旧全部无效】—— 核心内链的后处理管线在本集成的 GLES 环境
            //   下静默失败（着色器编译/应用链路断在核心内部，无法从外部修复）。
            //
            // 【V11 后滤镜的真实消费链】前端 MmjFilterView（SurfaceTexture 中转
            //   + MMJ 兼容头 .glsl 滤镜，见该类头注释 V12 根因说明）—— 它直接
            //   读 <filesDir>/azahar/shaders/<name>.glsl / assets/mmj/shaders，
            //   在我们自己的 GLES 上下文里编译，不依赖核心内链，全局 xbr/hqx
            //   映射与核心专属「后处理着色器」设置都经它生效。
            //
            // 【为什么 ini 恒写空串】
            //   1. 避免双重滤镜：若内链在个别设备/未来固件上意外生效，
            //      前端叠加层 + 核心内链会对同一帧各放大一次（画质劣化）；
            //   2. 绕开核心崩溃路径：内链加载器 F0(0x541484) 对 VFS 打开
            //      失败返回的空指针【无检查】直接解引用（SIGSEGV，见下方
            //      历史注释）—— 空名时 F1 在 0x4255ec cbz 直接跳过整个加载；
            //   3. 清理存量：旧版本写进 ini 的非空 pp_shader_name（含已失效
            //      的名字）由本恒写空串统一复位，与原版"无后处理"语义一致。
            overrides["pp_shader_name"] = ""
            // ★★★ 本轮：删除「着色器激活时强制 screen_presentation_mode 0→1」★★★
            //
            // 【为什么删除（getScreenTexture JNI 0x26dae0 反汇编定论）】：
            //   原版 MMJ 的后处理消费链 = Java GL 层（c1/W 渲染器）每帧调
            //   NativeLibrary.getScreenTexture() —— 该 JNI 会按 settings 里
            //   的呈现模式字节【选择两个虚表导出实现之一】（slot 0x90/0x98）：
            //     0（兼容模式，原版默认）→ 每次调用时在【调用方 GL 线程的
            //        上下文】里把最新帧导出成纹理 —— 跨上下文安全（原版
            //        V 投屏对话框（GLSurfaceView + W 渲染器）就是走这条路，
            //        与核心自绘共存不黑屏）；
            //     1（共享上下文）→ 返回核心内部纹理 ID —— 仅在共享上下文
            //        结构下有效；NesStation 的核心自建上下文（share=NULL），
            //        前端叠加层拿到的是【无效纹理】→ 采样全黑 —— 上轮
            //        "开滤镜必黑屏"的真正根源（v2 叠加层 + 本强制同时上，
            //        黑锅背给了叠加层）。
            //   原版从不写这个键（设置 UI 无此项）→ 永远跑在默认 0 的
            //   跨上下文安全导出路径上。NesStation 现在同样【只写用户自己
            //   选择的值】（默认 0），叠加层（MmjGlView）即可安全取帧。
            // ★★★ v5：呈现模式无条件钳制为 0（对齐原版有效行为）★★★
            //
            //   原版 MMJ 从不写这个键（设置 UI 无此项）→ 永远跑默认 0（核心
            //   直绘）。NesStation 的两个 Java 呈现消费者（MmjGlView 主屏
            //   叠加层 v5 已移除 / 投屏功能不存在）都不在了 —— 写 1（共享
            //   上下文）/2（硬件缓冲）会让核心跑离屏渲染路径、等一个不存在
            //   的 Java 呈现器 → 主屏无输出 = 黑屏。无论 UI/旧 ini 存的
            //   是什么值，这里一律钳回 "0"（修复 v2 时代残留脏值的黑屏）。
            overrides["screen_presentation_mode"] = "0"
            // ★★★ 帧率限制保险（"画面像快进"根治）★★★
            //   本轮（v2）：不再写 use_frame_limit=false（运行中无通道能
            //   恢复，见 currentFrameLimitPct 注释；快进改为 frame_limit
            //   百分比实现）。这里以用户在 MMJ 设置面板的选择为准
            //   （默认 enabled），无值时兜底 true；frame_limit 取覆盖集
            //   里的值（快进路径已带倍速%），缺省用户值/100。
            overrides["use_frame_limit"] =
                coreOptions["use_frame_limit"] ?: extra["use_frame_limit"] ?: "true"
            if (!overrides.containsKey("frame_limit")) {
                overrides["frame_limit"] = coreOptions["frame_limit"] ?: "100"
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
            // ★★★ V14：前端滤镜原生布局强制/恢复（见「前端滤镜原生布局」组注释）★★★
            if (nativeFilterLayout) {
                // 优先用视图逐帧查询时存储的真实窗口几何（与前端缓冲严格
                // 一致）；尚无存储（boot 竞态防御）按家族假设几何兜底：
                // 竖屏家族 = 长窗（堆叠）；横屏家族 = 20:9 宽窗（两行网格）。
                val pLayout = storedNativeLayoutPortrait
                    ?: computeNativeFamilyLayout(true, 400, 800)
                val lLayout = storedNativeLayoutLandscape
                    ?: computeNativeFamilyLayout(false, 2400, 1080)
                if (pLayout == null || lLayout == null) {
                    // 当前设置不支持原生中转（单屏/大屏/坏矩形）→ 自愈关闭 + 还原
                    nativeFilterLayout = false
                    restoreLayoutPending = true
                    android.util.Log.i("CitraMmjEngine",
                        "V14 native filter layout unsupported by current layout opts -> self-disabled")
                } else {
                    val layoutSec = sections.getOrPut("Layout") { LinkedHashMap() }
                    layoutSec["portrait_custom_layout"] = "true"
                    layoutSec["landscape_custom_layout"] = "true"
                    for ((k, v) in pLayout.rects) layoutSec[k] = v
                    for ((k, v) in lLayout.rects) layoutSec[k] = v
                }
            }
            if (restoreLayoutPending && !nativeFilterLayout) {
                restoreLayoutPending = false
                restoreUserLayoutLocked(sections)
            }
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

    /**
     * 十字键"上"失效诊断：把 config-mmj.ini 中与 D-PAD / 圆盘绑定相关的
     * 键值行（button_* / circle_pad_* / touchscreen / 段头）打到 logcat，
     * 用于核对核心绑定数组[4] 与 8 个轴槽的运行时配置来源。
     */
    private fun dumpBindingConfig() {
        try {
            val iniFile = File(userDir(), "config/config-mmj.ini")
            if (!iniFile.isFile) return
            val sb = StringBuilder("config binding keys:")
            java.io.BufferedReader(java.io.FileReader(iniFile)).use { br ->
                br.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.startsWith("[") || line.contains("button_") ||
                        line.contains("circle_pad_") || line.contains("touchscreen") ||
                        line.contains("dpad") || line.contains("analog")
                    ) sb.append("\n  ").append(line)
                }
            }
            android.util.Log.i("CitraMmjEngine", sb.toString())
        } catch (_: Throwable) {}
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
        // ★ 十字键去重表重置：新核心实例从全零按键状态启动，pad1LastSent
        //   若残留上一局"已按下"记录，首帧 setPad1 不会重发仍按住的键 →
        //   "按住十字键进游戏后失灵"。重置后首帧按真实状态重发。
        pad1LastSent.fill(false)

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
            // ★ 十字键"上"失效排查诊断：dump config-mmj.ini 中 D-PAD/圆盘
            //   相关绑定键（button_up 等），判断数组[4]/轴槽被何值污染。
            dumpBindingConfig()
        } catch (t: Throwable) {
            android.util.Log.w("CitraMmjEngine", "init failed", t)
        }

        this.romPath = rom.absolutePath
        _loaded = true

        // HUD 心跳（FPS 计数兼容）
        // ★★ 本轮：心跳线程同时记录实测节拍（滑动窗口反推 pacing）——
        //   MMJ 核心无 FPS JNI（nm 实测），前端 HUD 只能靠心跳；旧实现
        //   显示恒 ~60（假帧率，用户"帧数显示不准"的根因）。实测节拍在
        //   CPU 抢占/掉帧时会真实下行，配合快进倍率缩放（见 realtimeFps）
        //   后 HUD 数值诚实可用。
        running.set(true)
        heartbeatThread = thread(name = "mmj-hud-heartbeat", isDaemon = true) {
            try {
                while (running.get()) {
                    onFrame()
                    recordHeartbeat()
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
                } else {
                    // ★★ 设置/滤镜落盘保险（V12 更新）★★
                    //   Run() 内部会自行 Config::Load（从磁盘 ini 读全部设置
                    //   进 Settings 结构体）。这里在进入 Run 前把 coreOptions
                    //   【最后一次】落盘 + 重读 —— 消除 loadRom 到 Run 之间
                    //   任何内部回写/竞态把值洗掉的可能（loadRom 里已写过
                    //   一次，这里是紧贴 Run 的第二道保险）。V12 起 ini 的
                    //   pp_shader_name 恒为空串（滤镜唯一消费链 = 前端
                    //   MmjFilterView，见 writeMmjIniLocked 的 V12 注释）。
                    writeMmjIniLocked()
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
            // ★ [18] 帧率上限 %：快进中 = 倍速%（见 currentFrameLimitPct
            //   注释：setRunningSettings 不写 use_frame_limit，运行时速度
            //   只能靠本槽控制 —— 快进也必须推高百分比，不能不推）。
            currentFrameLimitPct(),              // [18] 帧率上限 %（核心内 max(v,1)）
            0                                   // [19] 投屏（关）
        )
    }

    /** 推送运行时设置到核心（通道 2）。值全部来自 currentFrameLimitPct 等
     *  FF 感知计算，快进中推送也安全（[18] = 倍速%，不会给快进封顶）。
     *  ★★ 本轮：同时重发 Turbo 键状态（核心跑起来前发出的快进键事件会被
     *   原生输入管理器静默丢弃 —— 运行后补发，快进状态跨启动窗口保持）。 */
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
        // ★ Turbo 键状态重发（幂等：同状态重复发送无副作用；
        //   快进开启中 = 持续按住加速键，核心内帧限持续 2x）
        if (_ffSpeed > 0) {
            try { CitraMmjNative.lib.InputEvent(IDX_TURBO, 1f) } catch (_: Throwable) {}
        }
    }

    /**
     * 运行时设置推送看护线程（★ 本轮：30s 窗口 → 整局生命周期看护）。
     *
     * 流程：轮询 IsRunning（最多 ~20s）→ 核心进入模拟主循环后再等 600ms
     * （越过引导期）首次推送；随后前 30s 每 5s 重推一次（密集期 —— 防
     * 核心启动后期的内部 Config::Load/状态重置把推送值洗掉）；之后降频
     * 为每 30s 一次【贯穿整局】—— 用户实测"玩一会儿突然 2 倍速"表明
     * 核心内部的帧率限制状态在任意时点都可能被洗掉（内部加速热键残留/
     * 每 Config 重载），30s 窗口不够。setRunningSettings 幂等（同值重写
     * 无副作用，SettingUpdate 只置标志位下一帧 ApplySetting 消费，30s
     * 一次的实测开销可忽略）。
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
                    if (!running.get()) return@Thread
                    // ★ 快进中也照推：[18] 槽已是倍速%（currentFrameLimitPct），
                    //   推送不会给快进封顶（旧代码这里跳过是 [18] 恒写 100
                    //   时代的遗留防御，现已无必要）。
                    pushRunningSettingsNow()
                    // 密集重推（30s 窗口）
                    var rePush = 0
                    while (rePush < 6 && running.get() && !Thread.currentThread().isInterrupted) {
                        try { Thread.sleep(5000) } catch (_: InterruptedException) { return@Thread }
                        if (!running.get()) return@Thread
                        pushRunningSettingsNow()
                        rePush++
                    }
                    // ★ 低频终身看护（本轮新增）：每 30s 重推直到退出。
                    while (running.get() && !Thread.currentThread().isInterrupted) {
                        try { Thread.sleep(30000) } catch (_: InterruptedException) { return@Thread }
                        if (!running.get()) return@Thread
                        pushRunningSettingsNow()
                    }
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
        // ★★ Turbo 键释放（本轮快进修复配套）：退出/重载前松开加速键 ——
        //   输入管理器是全局单例、状态跨 Run 存活，不松开会把"按住加速"
        //   残留到下一局（开局即 2x 的幽灵状态）。
        if (surfaceAttached || emuThread?.isAlive == true) {
            try { CitraMmjNative.lib.InputEvent(IDX_TURBO, 0f) } catch (_: Throwable) {}
        }
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
        // ★★ 全局滤镜/设置加固（V12 更新：pp_shader_name 恒空串）★★：核心
        //   退出时会把【它自己的】当前配置 saveConfig 回写 config-mmj.ini ——
        //   任何核心内部的值漂移（如加速键残留的 frame_limit=200）都会就此
        //   固化成下次启动的初始值。这里在 Run 返回后用【我们下发的】
        //   coreOptions 重写一遍 ini，保证下次启动读到的是用户设置
        //   （frame_limit 等）；pp_shader_name 由 writeMmjIniLocked 恒写空串
        //   （滤镜唯一消费链 = 前端 MmjFilterView，不再依赖核心内链）。核心
        //   saveConfig 与本回写的竞争窗口内我们后写（unload 在 emuThread
        //   join 之后执行）。
        try { writeMmjIniLocked() } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------
    // 视频 / 快进
    // ------------------------------------------------------------------

    override fun videoWidth(): Int = 400
    override fun videoHeight(): Int = 480
    /**
     * ★★ 实测心跳节拍（滑动窗口）—— CPU 节拍代理：
     * 系统空闲 ≈62，CPU 抢占/掉帧时下行。MMJ 核心未导出任何 FPS JNI
     * （nm 实测全部 68 个导出），这是前端可获得的唯一实时信号。
     */
    @Volatile private var measuredFps = 0.0
    private val heartbeatTimes = ArrayDeque<Long>()
    private val heartbeatLock = Any()

    /**
     * ★ 心跳节拍的标称值估计（滚动最大值，缓慢衰减）：
     * Thread.sleep(16) 循环的实际节拍因设备而异（57~63），用它做
     * "满速判定"的基准 —— measured ≥ nominal×0.88 视为前端满速。
     */
    @Volatile private var nominalBeat = 62.5

    /** 心跳线程每拍调用，保留最近 60 个 nanoTime 样本反推节拍。 */
    private fun recordHeartbeat() {
        val now = System.nanoTime()
        synchronized(heartbeatLock) {
            heartbeatTimes.addLast(now)
            while (heartbeatTimes.size > 60) heartbeatTimes.removeFirst()
            if (heartbeatTimes.size >= 2) {
                val first = heartbeatTimes.first()
                val spanNs = now - first
                val intervals = heartbeatTimes.size - 1
                if (spanNs > 0 && intervals > 0) {
                    measuredFps = 1_000_000_000.0 / (spanNs.toDouble() / intervals)
                    // 滚动标称值：跟随设备最高节拍，缓慢衰减防毛刺固化
                    nominalBeat = maxOf(nominalBeat * 0.9995, measuredFps)
                }
            }
        }
    }

    /**
     * ★★★ 帧数显示口径修正（"帧数显示不准"根治）★★★
     *
     * 旧实现直接显示心跳实测值：Thread.sleep(16) 的节拍因设备/负载在
     * 57~63 之间抖动，满速游玩时 HUD 显示 58~64 乱跳（用户预期 60）；
     * 快进时旧代码又乘上倍率显示 124 —— 都是"不准"的直接来源。
     *
     * 新口径（与 MMJ 原版 APK 的 OSD 口径一致）：
     *   - 核心由 frame_limit% 钳速（DoFrameLimiting），满速时实际呈现率 =
     *     60 × frame_limit% —— 前端心跳只是节拍代理，它达到标称即认为
     *     【核心满速】，此时显示干净的目标值（60 / 快进 120 / 半速 30）；
     *   - 心跳显著低于标称（< 88%）= 系统严重抢占（掉帧信号），此时
     *     显示实测值 × 倍率，如实下行。
     *   —— 消除满速时的 58~64 抖动，同时保留卡顿时的真实下行。
     */
    override fun realtimeFps(): Double {
        if (!_loaded) return 0.0
        val pacing = measuredFps
        if (pacing <= 0.5) return 0.0
        // 快进/限速缩放：显示值对齐核心的变速目标（200% → ×2.0）
        val pct = try { currentFrameLimitPct() } catch (_: Throwable) { 100 }
        val scale = pct.coerceIn(1, 60000) / 100.0
        val target = 60.0 * scale          // 核心满速钳速目标（3DS vsync ≈ 60）
        val nominal = nominalBeat.coerceIn(50.0, 70.0)
        return if (pacing >= nominal * 0.88) target
        else pacing * scale
    }
    override fun setVideoFilter(filter: Int) {}
    override fun setHighQualityScaling(enabled: Boolean) {}

    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (!_loaded) return
        // ★★★ 本轮根治“mmj无法快进”★★★
        //
        // 【通道 1：Turbo 键（原版同款，反汇编实证可靠）】
        //   InputEvent(12, 1f) = 按住加速键：原生输入管理器启动 350ms 长按
        //   计时 → 到时直接把运行时 Settings.frame_limit 写为 200（2x），
        //   抬键（0f）恢复原值。这是原版 APK 唯一可靠的运行时变速通道 ——
        //   setRunningSettings[18] 写的 frame_limit 会被 renderer 初始化时
        //   一次性换算成 skip 指标（max(200-limit,2)/2，so 0x428758 实证）
        //   之后不再重读，改值无效 → 旧实现快进失败的根因。
        //   ★ 核心未跑起来时事件被原生静默丢弃（输入管理器 +0x118 字段
        //     为 null）→ 下方的运行时推送器会在 IsRunning 后重发一次。
        try {
            CitraMmjNative.lib.InputEvent(IDX_TURBO, if (speed > 0) 1f else 0f)
        } catch (_: Throwable) {}
        // 【通道 2：ini frame_limit%（旧通道保留，双保险）】
        applyFastForwardConfig()
    }

    /**
     * ★★★ 当前帧率上限百分比（本轮 v2 新增，根治"偶尔 2 倍速"）★★★
     *
     * 快进中 = 倍速 × 100（如 6x → 600%，效果 = 不限速狂奔：DoFrameLimiting
     * 的 sleep_scale=6，设备跑不到 6x 就等于不限）；非快进 = 用户的
     * frame_limit 设置（默认 100%）。
     *
     * 【为什么不再用 use_frame_limit=false 做快进】上游 main_android.cpp 实证：
     *   setRunningSettings 只写 frame_limit（Settings::values.frame_limit =
     *   max(arr[18],1)），【不写 use_frame_limit】；use_frame_limit 仅在 Run()
     *   启动时从 ini 读一次，运行中无任何 JNI 通道可改。旧方案进快进把
     *   use_frame_limit=false 写进 ini → 退出快进 ini 虽改回 true，但
     *   【运行时结构体仍是 false】且 loadConfig() 只重读注册表 →
     *   DoFrameLimiting 直接 return → 整局不限速（= 用户反馈"偶尔 2 倍速，
     *   偶尔正常"：是否复现取决于本局是否用过快进按钮，看起来像随机的）。
     * 现在 use_frame_limit 恒 true，速度完全由 frame_limit% 控制（这正是
     * 原版运行时设置面板的百分比滑杆同款机制）。
     */
    private fun currentFrameLimitPct(): Int =
        if (_ffSpeed > 0) (_ffSpeed * 100).coerceIn(100, 60000)
        else (coreOptions["frame_limit"]?.toIntOrNull() ?: 100).coerceIn(1, 60000)

    /**
     * 快进：写 frame_limit = 倍速% 到 ini（通道 1）+ setRunningSettings
     * 运行时推送（通道 2，即时生效）。
     *
     * ★★ 绝不写 use_frame_limit=false（见 currentFrameLimitPct 注释：
     *   运行中无通道能把它改回 true，退出快进后整局 2 倍速）。
     * ★ 旧实现经 setConfigBoolean("use_frame_limit")/setConfigInteger(
     *   "frame_limit") 下发 —— 两键都不在 JNI 分发器接受集内 → brk #1
     *   SIGTRAP 闪退（早已改 ini 文件写入）。
     */
    private fun applyFastForwardConfig() {
        synchronized(lifecycleLock) {
            if (!_loaded) return
            val target = currentFrameLimitPct()
            writeMmjIniLocked(mapOf(
                "use_frame_limit" to "true",
                "frame_limit" to target.toString()
            ))
            // 重读 ini（注册表/下次启动视角保持一致）+ 通道 2 即时生效：
            // setRunningSettings 直写 Settings::values.frame_limit 并触发
            // VideoCore::SettingUpdate()（下一帧 ApplySetting 消费）。
            try { CitraMmjNative.lib.loadConfig() } catch (_: Throwable) {}
            val isRunning = try { CitraMmjNative.lib.IsRunning() } catch (_: Throwable) { false }
            if (isRunning) pushRunningSettingsNow()
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
     * 索引表（原版 InputOverlay 构造表 + so KeyEvent 反汇编双向实证）：
     *   A=0 B=1 X=2 Y=3 | 十字键 up=4 down=5 left=6 right=7 | L=8 R=9 |
     *   start=10 select=11 | zl=14 zr=15
     *
     * ★★★ 本轮根治（"十字键上无效果/输出不对"）★★★
     *   原生 InputManager 里 button_up/button_down 绑定【同一个轴码 16】
     *   （默认 "code:16,dir:-" / "code:16,dir:+"），button_left/right 共用
     *   轴码 15 —— 每个码上的所有 AnalogButton 设备共享同一 axis 值，
     *   【后写覆盖先写】（ChangeButtonValue 顺序循环全部同码设备）。
     *   旧实现每次全量重发 14 键（含未按下键的 0f）：按下"上"时
     *   InputEvent(4,1f)→axis16=-1（上按下），紧随的 InputEvent(5,0f)
     *   →axis16=0 → "上"在同一毫秒内被"下"的 0f 清掉 —— 游戏每帧读到
     *   的永远是 0 → "十字键上无效果"；"左"同理被"右"的 0f 清掉。
     *   原版 InputOverlay 的四键类 b.java l() 【只发变化键值】
     *   （if (z2 != f6644a[i4]) InputEvent(...)）—— 从不重发未变化键，
     *   所以原版没有此问题。修复 = 同款只发变化（按索引去重）。
     *   （对齐 Azahar 的 PressKey/ReleaseKey 状态机语义：十字键 = 纯
     *   数字按键，绝不做圆盘合成。）
     *
     * ★ 索引 12 绝不下发（本核心的 enum 12 是【加速键】不是 HOME）。
     */
    private val pad1LastSent = BooleanArray(14)

    override fun setPad1(bits: Int) {
        if (!_loaded) return
        val lib = CitraMmjNative.lib
        fun pressed(bit: Int) = bits and bit != 0
        val idxEvents = intArrayOf(
            IDX_A, IDX_B, IDX_X, IDX_Y,
            IDX_L, IDX_R, IDX_START, IDX_SELECT,
            IDX_UP, IDX_DOWN, IDX_LEFT, IDX_RIGHT,
            IDX_ZL, IDX_ZR
        )
        val states = booleanArrayOf(
            pressed(BIT_A), pressed(BIT_B), pressed(BIT_X), pressed(BIT_Y),
            pressed(BIT_L), pressed(BIT_R), pressed(BIT_START), pressed(BIT_SELECT),
            pressed(BIT_UP), pressed(BIT_DOWN), pressed(BIT_LEFT), pressed(BIT_RIGHT),
            pressed(BIT_ZL), pressed(BIT_ZR)
        )
        // ★ 只发变化键值（原版 overlay b.java l() 同款），且【两遍发送：
        //   先全部释放、再全部按下】—— 上/下共用轴码 16、左/右共用轴码
        //   15（同码设备共享 axis 值、后写覆盖先写）。若按索引序单遍发送，
        //   拇指从"下"滑到"上"时 UP 的 1f 先发、DOWN 的 0f 后发 → 轴值被
        //   覆盖回 0（原版 overlay 同样存在此共享轴码怪癖）。先释放后按下
        //   保证最终轴值 = 仍按住的方向。
        for (i in idxEvents.indices) {
            if (states[i] || !pad1LastSent[i]) continue
            pad1LastSent[i] = false
            try { lib.InputEvent(idxEvents[i], 0f) } catch (_: Throwable) {}
        }
        for (i in idxEvents.indices) {
            if (!states[i] || pad1LastSent[i]) continue
            pad1LastSent[i] = true
            try { lib.InputEvent(idxEvents[i], 1f) } catch (_: Throwable) {}
        }
    }

    override fun setPad2(bits: Int) {
        // MMJ 触屏设备为 P1 专属 —— P2 不支持
    }

    /**
     * 双摇杆：CirclePad (lx, ly) / C-Stick (rx, ry) —— InputEvent 模拟量
     * 通道（原版 overlay/c.java k() 同款）：
     *   圆盘 = InputEvent(21, x) / InputEvent(22, y)，C 摇杆 = 23/24。
     *
     * ★★★ Y 轴符号（本轮根治"摇杆上下是反的"）★★★
     * 三方互证 MMJ 的 Y 约定 = 屏幕坐标（+1 = 向下）：
     *   1. 原版摇杆类 overlay/c.java k()（jadx）：
     *      dArr[1] = (触摸Y - 中心Y) / (bottom - 中心Y) —— 触摸在中心
     *      【下方】为正 → InputEvent(base+1, +1) = 向下！
     *      （此前错误依据的是 b.java k() 的三分区逻辑 —— 该方法实际只
     *       服务 dpad(基址4)，摇杆走的是 c.java。）
     *   2. 上游 weihuoya/citra input_manager.cpp Joystick::SetStatus：
     *      y = clamp(-y)（"Citra uses an inverted y axis sent by the
     *      frontend"）—— 前端发 +1(下)，核心存 -1 → 3DS 圆盘向下 ✓。
     *   3. AzaharEngine.setAnalogAxes 同源注释：屏幕坐标（上=负）直传。
     * 旧实现下发前取反（上=+1）→ 核心按"下"处理 → 上下颠倒。
     * 现在 X/Y 全部直传（EmulatorScreen 的 setAnalogAxes 本就是屏幕
     * 坐标：X 右正 Y 下正 —— 见 pushAnalog 注释）。
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
        send(IDX_CPAD_Y, ly, 1)       // ★ 屏幕坐标直传（+1=下），不再取反
        send(IDX_CSTICK_X, rx, 2)
        send(IDX_CSTICK_Y, ry, 3)     // ★ 同上
    }

    // ------------------------------------------------------------------
    // 触摸（AzaharCoreEngine 契约 —— 3DS 下屏）
    // ------------------------------------------------------------------

    override fun setTouchInput(x: Float, y: Float, pressed: Boolean) {
        if (!_loaded) return
        try {
            // ★★★ 本轮根治"触屏点击变成按压"：action 语义以 so 反汇编
            //   （TouchEvent@0x264ff8）+ 原版 X0/a + InputOverlay smali
            //   三方互证 —— 位掩码真实含义：
            //     bit0(1) = TouchPressed(x, y)   按下
            //     bit1(2) = TouchMoved(x, y)     移动
            //     bit2(4) = TouchReleased()      抬起（无坐标）
            //   旧注释把 2/4 标反（2=抬起 / 4=移动）→ 抬手发 2（核心当
            //   MOVE）→ 触摸永不释放 = 点击变长按；拖动发 4 → 核心当
            //   RELEASE → 拖动断触。两个方向都错了，本轮互换。
            CitraMmjNative.lib.TouchEvent(if (pressed) 1 else 4, x.toInt(), y.toInt())
        } catch (_: Throwable) {}
    }

    override fun setTouchMoved(x: Float, y: Float) {
        if (!_loaded) return
        try {
            CitraMmjNative.lib.TouchEvent(2, x.toInt(), y.toInt())
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
    // 存档（MMJ .so 二进制未导出 SaveState/LoadState JNI —— 实证 + 诚实反馈）
    // ------------------------------------------------------------------
    //
    // ★★★ V11 即时存档诊断与改进（"mmj 的即时存档没效果"根治说明）★★★
    //
    // 【实证链】nm/dynsym 全扫 libcitra_mmj.so（含 0x2f8 起 0xf228 字节的
    //   .dynsym 表，2582 个符号）—— 68 个 Java_org_citra_emu_NativeLibrary_*
    //   JNI 符号中【无 SaveState / LoadState / SaveSnapshot / 等】；
    //   .rodata（0x102840 起 0x7db3c）扫关键词 .cst / states/ / SaveState /
    //   LoadState / save_state / load_state / savestate —— 【全部 0 命中】。
    //   上游 weihuoya/citra 的 MMJ 分支在移植到 Android 时【完全移除】了
    //   上游 Citra 的 Core::System::SaveState / LoadState 实现（节省体积
    //   或规避稳定性问题），运行时不存在该函数 ——【不是接口未接线，而是
    //   功能整体缺失】。
    //
    // 【Azahar 的实现（用户参考）】Azahar 是 Citra/Lime3DS 的社区延续分支，
    //   .so 内保留了完整 SaveState/LoadState（导出为 JNI
    //   Java_org_citra_citra_emu_NativeLibrary_SaveState/LoadState），
    //   AzaharEngine 经该 JNI 触发原生 Core::System::SaveState，写
    //   <userDir>/states/<titleId>_version_2005.<slot>.cst 文件。MMJ 的
    //   分支源码本身就没有这套 —— 没有 JNI 也没有内部 C++ 函数可调，无法
    //   从外部（即便用 dlsym 反射）触发。
    //
    // 【为什么不能像 Azahar 那样修】修复需要在 .so 内【新增】一个 JNI 函数，
    //   并实现 Core::System::SaveState / LoadState 的内部状态捕获逻辑
    //   （CPU 寄存器 + GPU + RAM + NAND + SDMC + 音频 + 外设 + 调度器）——
    //   这需要从 MMJ 源码重新编译。NesStation 仓库只有二进制 .so
    //   （Citra_MMJ_20250220.apk 提取版），无源码 —— 无法重编。
    //
    // 【本引擎的诚实反馈】saveState/loadState 仍返回 false，错误消息
    //   明确告知用户三个可行方案：
    //   1. 在 3DS 系统设置切换到 Azahar 核心（联机/即时存档/驱动管理全支持）；
    //   2. 用游戏内的存档点存档（MMJ 完整支持 3DS NAND/SDMC 游戏存档，
    //      ExtSaveData/SystemSaveData 等链路在 .so 中实证存在）；
    //   3. 暂停 MMJ 模拟（不退出 App）冻结当前状态 —— 仅限本会话内有效。
    //   UI 收到 false 后会 Toast 显示 lastError()，用户立即可知。
    //
    // 【不诚实方案的拒绝】旧版有"假装存档成功"的实现（创建空 .state 文件 +
    //   返回 true）—— 读档时必然失败且误导用户。本实现拒绝这种伪成功，
    //   一律返回 false 让 UI 反馈真实情况。

    override fun saveState(slot: Int, dst: File): Boolean {
        // ★ V11：错误消息升级 —— 明确"功能缺失"而非"接口未接线"，
        //   并给出可行替代方案。
        lastErrorText = "Citra MMJ 二进制未集成即时存档功能（weihuoya 分支" +
            "编译时移除了上游 Core::System::SaveState），无法在该核心下创建" +
            "即时存档。建议：① 退出后在 3DS 系统设置切换到 Azahar 核心" +
            "（支持 10 槽即时存档）；② 或在游戏内使用存档点存档（MMJ 完整" +
            "支持 3DS NAND/SDMC 游戏存档）。"
        return false
    }

    override fun loadState(slot: Int, src: File): Boolean {
        // ★ V11：同 saveState，错误消息升级 + 替代方案。
        lastErrorText = "Citra MMJ 二进制未集成即时读档功能（同 saveState 根因：" +
            "weihuoya 分支移除了上游 SaveState/LoadState 实现）。即便 .state" +
            "文件存在（来自其它核心如 Azahar 创建）也无法被 MMJ 读取 ——" +
            "状态序列化格式由 .so 内部 C++ 类布局决定，无对应函数解析。" +
            "建议在 3DS 系统设置切换到 Azahar 核心后读档。"
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
