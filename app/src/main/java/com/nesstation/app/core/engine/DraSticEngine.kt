package com.nesstation.app.core.engine

import android.content.Context
import android.os.Build
import com.dsemu.drastic.DraSticJNI
import com.dsemu.drastic.filesystem.DraSticPathCache
import com.nesstation.app.core.jni.NdsNative
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "DraSticEngine"

/**
 * DraStic（激烈）NDS 核心引擎 —— 与 [NdsEngine]（melonDS）并列的第二个
 * NDS 核心，启动时由玩家在核心选择对话框里二选一。
 *
 * ## 架构（反汇编 libdrastic_arm64.so 逐条验证）
 *  - **startGame() 本身就是模拟主循环**，跑在调用者线程上：游戏运行期间
 *    【永不返回】，quitSystem() 置退出标志后它才返回。原版 DraStic 在专门
 *    的后台 GameThread 里调它；原版拆机顺序 pauseSystem → quitSystem →
 *    等待 startGame 返回 → releaseSystem 即为此设计。因此 [loadRom] 把
 *    startGame 放到专用守护线程 "drastic-game"（该线程 = 模拟线程），
 *    自己只等"就绪信号"：渲染消费线程拿到首帧（frameCount > 0）即置
 *    isLoaded 并返回 —— 旧实现同步等待 startGame 返回 → 永不返回 →
 *    loaded 恒 false → UI 永远停在"正在加载"（此时模拟已在跑、OpenSL
 *    音频正常出声 —— 正是"有声音卡加载页"的根因）。
 *  - OpenSL ES 音频输出由核心内部线程驱动，无需 Kotlin 音频线程。
 *  - 渲染消费线程做"帧搬运"：`waitScreen()`（原生实现 = mutex lock +
 *    cond_wait + unlock，无超时无空指针守卫；mutex/cond 位于零初始化
 *    BSS，核心建立前调用也安全）→ `getScreenBuffers(top, bottom)`
 *    （ARGB_8888 256×192 ×2；未就绪时空指针守卫直接返回，反汇编确认）
 *    → 合成 [frameBuffer]（上屏在前 256×384）→ 帧号 +1；
 *  - [NdsDualScreenView] 按 Choreographer 节流消费 [frameBuffer]，因此
 *    DraStic 分支无论什么画面缩放模式都走画布渲染路径（GameSurfaceView
 *    已为此做了分支处理）。
 *
 * ## 输入位布局换算
 * NesStation 前端经 routePadBits/projectToLibretroLayout 统一下发【标准
 * libretro】位布局（bit0=B bit1=Y bit8=A bit9=X）；
 * DraStic 原生使用 bit0-3=方向 / bit4-11=A,B,X,Y,L,R,Start,Select +
 * bit31=触摸标志（[DraSticJNI.updateInput] + KEYINPUT 组装反汇编验证：
 * KEYINPUT bit0(A)=掩码 bit4、bit1(B)=掩码 bit5、KEYXY bit0(X)=掩码 bit6、
 * bit1(Y)=掩码 bit7）。[setPad1] 负责 libretro→DraStic 的位重排，
 * [pushInput] 保证按键与触摸状态合并成一个完整的 updateInput 调用
 * （两状态都缓存在引擎里）。
 *
 * ## ABI 支持（双 ABI）
 * libdrastic*.so 同时提供 armeabi-v7a（32 位：drastic / drastic_compat）
 * 与 arm64-v8a（64 位：drastic_arm64）两套原生库，两套主库导出完全相同的
 * 72 个 JNI 符号，引擎层代码无需感知 ABI 差异。
 * 默认多 ABI 构建下：64 位设备以 arm64 进程运行并加载 drastic_arm64，
 * 32 位设备（或 -PabiFilter=armeabi-v7a 构建）加载 drastic/compat；
 * 仅 x86/x86_64 进程无库可用（[probeAvailability] 报告不可用，UI 禁用
 * DraStic 选项，不影响选 melonDS 游玩）。
 *
 * ## 存档
 * 电池存档固定为裸 .sav（bit50 恒置位），格式与位置完全跟随 NesStation
 * 全局存档方式（设置 → 存储 → 存档方式，见 [DraSticEngine] 存档互通注释）——
 * 激烈核心自身不再提供独立的存档格式/位置选项。原生库始终把备份写到
 * `<filesDir>/drastic/user/backup/`（预编译 .so 的路径写死），会话边界与
 * 全局存档位置双向同步，对用户呈现为同一份存档。即时存档（.dss 槽 0-8，
 * 9 为快速槽）由原生经 [DraSticPathCache] 写入；与 NesStation 槽位 UI 的
 * 互通见 [saveState]（写路径观察器精确定位 .dss，复制一份到 NesStation
 * 的 .state 路径供槽位存在性检查 / 备份迁移）。
 */
class DraSticEngine private constructor() : EmulatorEngine, NdsCoreEngine {

    companion object {
        @Volatile
        private var instance: DraSticEngine? = null

        /** 单例（与 NdsEngine/FbNeoEngine 等保持一致的 get() 模式）。 */
        @JvmStatic
        fun get(): DraSticEngine {
            return instance ?: synchronized(this) {
                instance ?: DraSticEngine().also { instance = it }
            }
        }

        // ---- libretro → DraStic 按键位换算表 ----
        // ★ 位序已修正：前端 routePadBits 对 NDS 下发的是【标准 libretro
        // 位布局】（melonDS 核心同款），旧实现误按项目 SNES 布局解释 ——
        // 导致 ABXY 输出错位（实测 A→X、B→A、X→Y、Y→B，用户报
        // “a 输出 y 了、b 变成 a 了”）。
        //
        // 标准 libretro JOYPAD 位序（RETRO_DEVICE_ID_JOYPAD_*）：
        //           bit0=B bit1=Y bit2=Select bit3=Start bit4=Up bit5=Down
        //           bit6=Left bit7=Right bit8=A bit9=X bit10=L bit11=R
        // DraStic 位序（KEYINPUT 组装 0x80cbc-0x80e74 反汇编实锤，原生
        // 掩码 active-high、KEYINPUT/KEYXY active-low）：
        //           bit0=Up bit1=Down bit2=Left bit3=Right bit4=A bit5=B
        //           bit6=X bit7=Y bit8=L bit9=R bit10=Start bit11=Select
        private const val LR_A = 0x100   // 标准 libretro：A = bit8
        private const val LR_B = 0x001   // 标准 libretro：B = bit0
        private const val LR_SELECT = 0x004
        private const val LR_START = 0x008
        private const val LR_UP = 0x010
        private const val LR_DOWN = 0x020
        private const val LR_LEFT = 0x040
        private const val LR_RIGHT = 0x080
        private const val LR_X = 0x200   // 标准 libretro：X = bit9
        private const val LR_Y = 0x002   // 标准 libretro：Y = bit1
        private const val LR_L = 0x400
        private const val LR_R = 0x800

        private const val DS_UP = 0x001
        private const val DS_DOWN = 0x002
        private const val DS_LEFT = 0x004
        private const val DS_RIGHT = 0x008
        private const val DS_A = 0x010
        private const val DS_B = 0x020
        private const val DS_X = 0x040
        private const val DS_Y = 0x080
        private const val DS_L = 0x100
        private const val DS_R = 0x200
        private const val DS_START = 0x400
        private const val DS_SELECT = 0x800

        /** DraStic 触摸激活标志（updateInput arg1 的 bit31）。 */
        private const val DS_TOUCH_FLAG = 0x80000000.toInt()

        /** 原版 onInit 传的 versionCode —— jadx 反编译确认原版传自身 PackageInfo
         *  .versionCode（r2.6.0.4a arm64 APK = 109），与其保持一致可让
         *  “全新安装”的数据迁移路径与原版完全同构。 */
        private const val DRASTIC_VERSION_CODE = 109

        /** 原版首次运行在系统目录创建的子目录（DraSticActivity.e0() + f0()）。 */
        private val SYSTEM_SUBDIRS = arrayOf(
            "backup", "savestates", "config", "unzip_cache", "system",
            "input_record", "cheats", "slot2", "microphone", "scripts", "users"
        )

        /** 原版用户目录子目录（AddUser.f()）。 */
        private val USER_SUBDIRS = arrayOf("savestates", "config", "backup", "cheats")

        /** 捆绑系统资产版本：更新 APK 内 game_database/usrcheat 时递增以触发重装。 */
        private const val DRASTIC_ASSET_VERSION = 1

        /** 原版默认音量（_Volume 0..10 → setAudioVolume ×10）。 */
        private const val DEFAULT_VOLUME = 100

        /**
         * 启动就绪超时：drastic-game 线程调 startGame 后，渲染消费线程必须在
         * 该时限内拿到首帧（frameCount > 0），否则判定启动失败并干净收场。
         * 注意：startGame 在游戏运行期间不返回（它是模拟主循环本身，反汇编
         * 验证），不能拿"startGame 是否返回"当超时/成败依据。
         */
        private const val BOOT_TIMEOUT_MS = 20_000L

        /**
         * 打包 DraStic 配置位域 —— 与原版 App `f0.h.n()`（jadx 反编译
         * DraStic r2.6.0.4a classes.dex）逐位一致，并经 libdrastic_arm64.so
         * 反汇编验证消费链路（applyConfig 0x1a4a0 → 解包器 0x17c58）：
         *
         * 【连续字段】
         *  bits 0-3  = 跳帧值（原版 _FrameskipValue，默认 4；解包器以
         *              FrameskipValue^1 写入全局跳帧计数 0x3c9b048）
         *  bits 5-7  = 跳帧类型（_FrameskipType：0=关闭 1=手动 2=自动）
         *  bits 8-9  = 音频延迟（_AudioLatency：0=低 1=中 2=高 3=极高，默认 3）
         *  bits 12-15= 快进速率（_FfwdSpeed：0=50% 1=150% 2=200%(默认)
         *              3=300% 4=400% 5=无限制；仅 bit29 激活时查表
         *              0x1070c0=[100000,33333,25000,16666,12500,5000]µs）
         *  bits 16-19= 模拟线程数（原版按 CPU 核数自动：≥4核=3，≥2核=2，
         *              否则 1；可由 config/threads.cfg 强制 1-8。
         *              ★ 3D 渲染按 16 行/段分段光栅化，线程数决定并行段数：
         *              传 0 = 单线程模式，3D 大游戏每帧光栅化赶不上帧节奏，
         *              显示端只能捕到顶部 1 段（画面顶部一条、其余全黑的
         *              "3D 游戏显示不完整" bug 根因）。原版绝不会传 0！）
         *  bits 32-34= 连发速度（_AutoFireSpeed 0-4，默认 2）
         *  bits 37-38= 麦克风等级（_MicLevel 0-3，默认 1；表 0x10a080=[2,4,8,16]
         *              → float 麦克风增益 —— 注意不是快进倍率！）
         *  bits 43-46= Slot2 卡带类型（_Slot2Type 0-5，默认 1=GBA 卡）
         *
         * 【开关位】（默认值 = 原版 SharedPreferences 默认）
         *  bit23 = 16 位渲染（_GlUse16Bit，默认关）
         *  bit24 = 忽略卡带容量（_IgnoreGamecardLimit，默认关）
         *  bit25 = 即时存档内保存游戏存档（_BackupInSavestates，默认开）
         *  bit26 = 麦克风启用（_MicEnabled，默认开）
         *  bit27 = 金手指启用（_CheatsEnabled，默认开）
         *  bit28 = 多线程 3D 渲染（_Threaded3D，默认关；解包器 → cfg+0x468，
         *          帧冲刷函数 0x59bb4 按它选择"异步分段+上一帧补拷贝"路径）
         *  bit29 = 快进激活（运行时状态，非用户设置）
         *  bit30 = 显示 FPS（_ShowFPS，默认关）
         *  bit31 = 声音启用（_SoundEnabled，默认开）
         *  bit35 = 主屏固定在上屏（_FixMainEngineScreen，默认关；
         *          解包器 SIMD → cfg+0x498，帧冲刷按它交换双屏脏标记顺序）
         *  bit36 = ROM 自动裁边（_AutoTrim，默认关）
         *  bit39 = RTC 使用系统时间（_RtcSystemTime，默认关）
         *  bit40 = 禁用边缘标记（_DisableEdgeMarking，默认关；cfg+0x49c）
         *  bit41 = 高清渲染（_Hires3D，默认关；SIMD → cfg+0x4a0 →
         *          startGame 路径 0x3ca08 调 setResolution(0x1cde4) 写两屏
         *          分辨率档 → 帧池 512×384×4/屏）
         *  bit42 = Lua 启用（_LuaEnabled，默认开）
         *  bit47 = 安全跳帧（_FrameskipSafe，默认关）
         *  bit48 = 预解压 ROM 到内存（_PreloadRoms，默认关）
         *  bit49 = 混合渲染（_Blend，默认关）
         *  bit50 = 存档格式（_RawSavFormat：0=.dsv 带头格式（原生默认）
         *          1=裸 .sav。★ 反汇编实锤（0x75740：csel x3, .dsv, .sav, eq）
         *          —— 字段==0 → ".dsv" 备份名，非 0 → ".sav"；本工程默认置 1
         *          （裸 .sav，与 melonDS 互通））
         *  bit 4 / 10-11 / 20-22 / 33-34 高位 / 51-63 = 恒 0（原版同）
         */
        private fun packConfig(
            frameskipType: Int = 0,
            frameskipValue: Int = 4,
            audioLatency: Int = 3,
            ffwdRate: Int = 2,
            emuThreads: Int = 3,
            autoFireSpeed: Int = 2,
            micLevel: Int = 1,
            slot2Type: Int = 1,
            glUse16Bit: Boolean = false,
            ignoreGamecardLimit: Boolean = false,
            backupInSavestates: Boolean = true,
            micEnabled: Boolean = true,
            cheatsEnabled: Boolean = true,
            threaded3D: Boolean = false,
            fastForward: Boolean = false,
            showFps: Boolean = false,
            sound: Boolean = true,
            fixMainEngineScreen: Boolean = false,
            autoTrim: Boolean = false,
            rtcSystemTime: Boolean = false,
            disableEdgeMarking: Boolean = false,
            hdRender: Boolean = false,
            luaEnabled: Boolean = true,
            frameskipSafe: Boolean = false,
            preloadRoms: Boolean = false,
            blend: Boolean = false,
            saveRawSav: Boolean = true
        ): Long {
            var cfg = (frameskipValue.coerceIn(0, 15).toLong()) or          // bits 0-3
                ((frameskipType.coerceIn(0, 7).toLong()) shl 5) or          // bits 5-7
                ((audioLatency.coerceIn(0, 3).toLong()) shl 8) or           // bits 8-9
                ((ffwdRate.coerceIn(0, 15).toLong()) shl 12) or             // bits 12-15
                ((emuThreads.coerceIn(1, 8).toLong()) shl 16) or            // bits 16-19 ★ 绝不为 0
                ((autoFireSpeed.coerceIn(0, 7).toLong()) shl 32) or         // bits 32-34
                ((micLevel.coerceIn(0, 3).toLong()) shl 37) or              // bits 37-38
                ((slot2Type.coerceIn(0, 15).toLong()) shl 43)               // bits 43-46
            if (glUse16Bit) cfg = cfg or (1L shl 23)
            if (ignoreGamecardLimit) cfg = cfg or (1L shl 24)
            if (backupInSavestates) cfg = cfg or (1L shl 25)
            if (micEnabled) cfg = cfg or (1L shl 26)
            if (cheatsEnabled) cfg = cfg or (1L shl 27)
            if (threaded3D) cfg = cfg or (1L shl 28)
            if (fastForward) cfg = cfg or (1L shl 29)
            if (showFps) cfg = cfg or (1L shl 30)
            if (sound) cfg = cfg or (1L shl 31)
            if (fixMainEngineScreen) cfg = cfg or (1L shl 35)
            if (autoTrim) cfg = cfg or (1L shl 36)
            if (rtcSystemTime) cfg = cfg or (1L shl 39)
            if (disableEdgeMarking) cfg = cfg or (1L shl 40)
            if (hdRender) cfg = cfg or (1L shl 41)
            if (luaEnabled) cfg = cfg or (1L shl 42)
            if (frameskipSafe) cfg = cfg or (1L shl 47)
            if (preloadRoms) cfg = cfg or (1L shl 48)
            if (blend) cfg = cfg or (1L shl 49)
            // bit50 = _RawSavFormat：置位 = 裸 .sav（与 melonDS 互通）；
            // 清零 = .dsv（DeSmuME 带头格式，原生默认）。反汇编 0x75768
            // (csel x3, x12=.dsv, x11=.sav, eq) 实锤：0 → .dsv，非 0 → .sav。
            // ★ 旧实现把 "dsv" 映射到 bit50=1 —— 与原生语义恰好相反，
            // 导致存档格式设置永远与用户选择相反（“总变回默认”根因）。
            if (saveRawSav) cfg = cfg or (1L shl 50)
            return cfg
        }

        /**
         * 模拟线程数自动探测 —— 与原版 f0.h 完全同构：
         * ≥4 核 → 3，≥2 核 → 2，否则 1；用户强制值（1-8）优先。
         * 3D 游戏的 16 行/段分段光栅化依赖该线程数提供并行度 ——
         * 必须与原版一致（绝不传 0），否则 3D 大游戏画面只剩顶部一条。
         */
        fun effectiveEmuThreads(forced: Int): Int {
            if (forced in 1..8) return forced
            val cores = Runtime.getRuntime().availableProcessors()
            return when {
                cores >= 4 -> 3
                cores >= 2 -> 2
                else -> 1
            }
        }

        // ---- 可用性探测（核心选择对话框用） ----

        /** 探测结果：库是否可用 + 不可用原因（可用时为 null）。 */
        data class Availability(val available: Boolean, val reason: String?)
    }

    /** 进程内缓存探测结果（库加载结果在进程生命周期内不会变化）。 */
    @Volatile
    private var probedAvailability: Availability? = null

    /** 触发 DraSticJNI 类初始化（加载 drastic_cpu + 主库）并返回可用性。 */
    fun probeAvailability(): Availability {
        probedAvailability?.let { return it }
        val result = try {
            Class.forName("com.dsemu.drastic.DraSticJNI")
            if (DraSticJNI.JniStartupError) {
                Availability(
                    false,
                    "libdrastic 加载失败（当前进程非 ARM，或库缺失）\n" +
                        "DraStic 仅提供 ARM 库（32 位 + 64 位）；" +
                        "x86 / x86_64 设备不可用。"
                )
            } else if (DraSticJNI.JniCpuType != DraSticJNI.CPU_TYPE_ARMv7a_NEON &&
                DraSticJNI.JniCpuType != DraSticJNI.CPU_TYPE_ARMv7a_TEGRA2 &&
                DraSticJNI.JniCpuType != DraSticJNI.CPU_TYPE_ARMv8a
            ) {
                Availability(false, "设备 CPU 不受支持 (cpuType=${DraSticJNI.JniCpuType})")
            } else {
                Availability(true, null)
            }
        } catch (e: Throwable) {
            Availability(false, "DraStic 核心加载异常: ${e.message}")
        }
        probedAvailability = result
        return result
    }

    // ---- 引擎状态 ----

    /**
     * 合成帧：上屏 256×192 在前（行 0..191）、下屏在后（行 192..383），
     * 共 256×384。NdsDualScreenView.computeSrcRects 的 Top/Bottom 切片
     * 推导（screenH=192、gap=0）对它天然成立。
     */
    @Volatile
    override var frameBuffer: IntArray = IntArray(256 * 384)

    private val topBuf = IntArray(256 * 192)
    private val bottomBuf = IntArray(256 * 192)

    /** 帧快照一致性校验用的影子缓冲（多线程 3D 下双拷贝比对，见渲染线程）。 */
    private val topVerify = IntArray(256 * 192)
    private val bottomVerify = IntArray(256 * 192)

    // === 高清（bit41 / _Hires3D）真实帧读取缓冲（修复"高清2x渲染无效果"）===
    // 高清会话下原生 getScreenBuffers 走 0x19398 降采样分支，CPU 路径只能
    // 拿到 2:1 抽取后的 256×192/屏。渲染线程优先走 NdsNative.drasticGetCompletedFrames
    // （libndscore 内 drastic_frames.cpp shim）直接读帧池，拿到真实 512×384/屏
    // 帧；shim 校验失败（16位渲染/库未加载/档位异常）再回落 getScreenBuffers。
    private val hdTopBuf = IntArray(512 * 384)
    private val hdBottomBuf = IntArray(512 * 384)
    private val hdDims = IntArray(4)   // {topW, topH, botW, botH}

    /** 原始合成帧（恒 256×384，上屏在前）：截图/存档缩略图的数据源，
     * 与可能被放大滤镜替换掉的 [frameBuffer]（画布呈现帧）分离。
     * 高清会话下渲染线程把 512×384/屏 的 HD 帧 2:1 抽取写入本缓冲，
     * 保证截图/缩略图规格恒定。 */
    private val rawComposite = IntArray(256 * 384)

    private val running = AtomicBoolean(false)
    private var renderThread: Thread? = null

    /** 模拟线程：整个游戏会话期间阻塞在原生 startGame 内部（= 原版 GameThread）。 */
    private var gameThread: Thread? = null

    /** startGame 是否已返回（= 游戏会话结束）。 */
    private val gameEnded = AtomicBoolean(false)

    /** startGame 的返回值（会话结束时读取）。 */
    private val gameResult = AtomicBoolean(false)

    @Volatile
    override var isLoaded: Boolean = false
        private set

    @Volatile
    private var paused = false

    @Volatile
    private var frameCount = 0L

    /** App context —— 引擎初始化（onInit 上下文 / 目录定位）用。 */
    @Volatile
    var appContext: Context? = null

    // ---- 激烈核心用户设置缓存（setCoreOption 在 loadRom 前后都可更新） ----
    // 这些设置打包进 startGame/applyConfig 的 config 位域（见 packConfig）。
    // 默认值 = 原版 App 的 SharedPreferences 默认（jadx 确认），保证默认
    // 配置字与原版 f0.h.n() 完全一致。loadRom 之前缓存的值会在 startGame
    // 时生效；游戏运行期间修改的会通过 applyConfig 热更新。

    /** 声音开关（bit31）。 */
    @Volatile
    var optSound: Boolean = true

    /** 高清渲染 2x（bit41，_Hires3D）。 */
    @Volatile
    var optHdRender: Boolean = false

    /** 跳帧类型 0=关闭 1=手动 2=自动（bits5-7，_FrameskipType）。 */
    @Volatile
    var optFrameskipType: Int = 0

    /** 跳帧值 0-9（bits0-3，_FrameskipValue，原版默认 4）。 */
    @Volatile
    var optFrameskipValue: Int = 4

    /** 安全跳帧（bit47，_FrameskipSafe）。 */
    @Volatile
    var optFrameskipSafe: Boolean = false

    /** 多线程 3D 渲染（bit28，_Threaded3D，原版默认关）。 */
    @Volatile
    var optThreaded3D: Boolean = false

    /** 16 位渲染（bit23，_GlUse16Bit）。 */
    @Volatile
    var optGlUse16Bit: Boolean = false

    /** 禁用边缘标记（bit40，_DisableEdgeMarking）。 */
    @Volatile
    var optDisableEdgeMarking: Boolean = false

    /** 主屏固定在上屏（bit35，_FixMainEngineScreen）。 */
    @Volatile
    var optFixMainEngineScreen: Boolean = false

    /** 音频延迟 0=低 1=中 2=高 3=极高（bits8-9，_AudioLatency，原版默认 3）。 */
    @Volatile
    var optAudioLatency: Int = 3

    /** 麦克风启用（bit26，_MicEnabled，原版默认开）。 */
    @Volatile
    var optMicEnabled: Boolean = true

    /** 麦克风等级 0-3（bits37-38，_MicLevel，原版默认 1）。 */
    @Volatile
    var optMicLevel: Int = 1

    /** 连发速度 0-4（bits32-34，_AutoFireSpeed，原版默认 2）。 */
    @Volatile
    var optAutoFireSpeed: Int = 2

    /** 快进速率 0-5 = 50%/150%/200%/300%/400%/无限制（bits12-15，_FfwdSpeed，原版默认 2）。 */
    @Volatile
    var optFfwdRate: Int = 2

    /** Slot2 卡带类型 0-5（bits43-46，_Slot2Type，原版默认 1=GBA 卡）。 */
    @Volatile
    var optSlot2Type: Int = 1

    /** RTC 使用系统时间（bit39，_RtcSystemTime）。 */
    @Volatile
    var optRtcSystemTime: Boolean = false

    /** 金手指启用（bit27，_CheatsEnabled，原版默认开）。 */
    @Volatile
    var optCheatsEnabled: Boolean = true

    /** Lua 启用（bit42，_LuaEnabled，原版默认开）。 */
    @Volatile
    var optLuaEnabled: Boolean = true

    /** 即时存档内保存游戏存档（bit25，_BackupInSavestates，原版默认开）。 */
    @Volatile
    var optBackupInSavestates: Boolean = true

    /** 忽略卡带容量（bit24，_IgnoreGamecardLimit）。 */
    @Volatile
    var optIgnoreGamecardLimit: Boolean = false

    /** ROM 自动裁边（bit36，_AutoTrim）。 */
    @Volatile
    var optAutoTrim: Boolean = false

    /** 预解压 ROM 到内存（bit48，_PreloadRoms）。 */
    @Volatile
    var optPreloadRoms: Boolean = false

    /** 显示 FPS（bit30，_ShowFPS —— 原版内建 FPS 叠加）。 */
    @Volatile
    var optShowFps: Boolean = false

    /** 模拟线程数：0=自动（按核数 1/2/3），1-8=强制（原版 threads.cfg 语义）。 */
    @Volatile
    var optThreads: Int = 0

    /** 自动存档间隔秒（0=关，300/900/1800；原版 _AutosaveMode）。 */
    @Volatile
    var optAutosaveInterval: Int = 0

    /** 实际生效的线程数（自动探测结果，loadRom 时计算）。 */
    @Volatile
    var activeThreads: Int = 3
        private set

    // ---- 会话快照（loadRom 时从用户偏好拍下，决定原生 GPU 初始化） ----
    // bit41（高清）在 startGame 时进入原生分辨率初始化（setResolution 路径），
    // 决定帧池的上传尺寸；GL 显示视图也按此快照分配纹理。游戏运行中改设置
    // 只入 opt* 偏好，下次进游戏生效 —— 避免视图/原生两侧尺寸不一致导致
    // 上传错位。

    /** 本局游戏的高清渲染快照（loadRom 时拍下）。 */
    @Volatile
    var activeHdRender: Boolean = false
        private set

    /**
     * 本会话的用户提示（加载完成后由 UI 以 Toast 展示一次）。
     * 高清渲染与放大滤镜共存后不再有"会话被静默降级"的场景，
     * 当前保留该通道供未来提示使用，恒为空串。
     */
    @Volatile
    var sessionNotice: String = ""
        private set

    private fun setSessionNotice(msg: String) {
        sessionNotice = msg
        android.util.Log.i(TAG, "session notice: $msg")
    }

    /** GL 显示路径已接管帧消费（DraSticGlView 置位）：
     * 渲染消费线程跳过 getScreenBuffers 的 CPU 帧拷贝（约 500KB/帧），
     * 仅维持帧计数与就绪握手；截图改由 captureFrame 直接拉取。 */
    @Volatile
    var glDisplayActive: Boolean = false

    /** GL 放大滤镜显示路径已接管（DraSticGlView 置位，与 glDisplayActive
     * 互斥）：渲染消费线程照常搬运原始帧到 frameBuffer（GL 线程要拿去
     * 滤镜），但【不做】画布路径的滤镜/尺寸替换 —— frameBuffer 保持
     * 原始 256×384。 */
    @Volatile
    var filteredGlActive: Boolean = false

    /** 用会话快照 + 快进状态打包完整 config（热更新用，保证分辨率位
     *  与当前会话的原生状态一致）。 */
    private fun currentConfig(fastForward: Boolean): Long = packConfig(
        frameskipType = optFrameskipType,
        frameskipValue = optFrameskipValue,
        audioLatency = optAudioLatency,
        ffwdRate = optFfwdRate,
        emuThreads = activeThreads,
        autoFireSpeed = optAutoFireSpeed,
        micLevel = optMicLevel,
        slot2Type = optSlot2Type,
        glUse16Bit = optGlUse16Bit,
        ignoreGamecardLimit = optIgnoreGamecardLimit,
        backupInSavestates = optBackupInSavestates,
        micEnabled = optMicEnabled,
        cheatsEnabled = optCheatsEnabled,
        threaded3D = optThreaded3D,
        fastForward = fastForward,
        showFps = optShowFps,
        sound = optSound,
        fixMainEngineScreen = optFixMainEngineScreen,
        autoTrim = optAutoTrim,
        rtcSystemTime = optRtcSystemTime,
        disableEdgeMarking = optDisableEdgeMarking,
        hdRender = activeHdRender,
        luaEnabled = optLuaEnabled,
        frameskipSafe = optFrameskipSafe,
        preloadRoms = optPreloadRoms,
        blend = false,
        // 存档格式固定裸 .sav（bit50 恒置位）：电池存档跟随全局存档方式
        // （nesstation = saves/<gameId>.sav；core_builtin = ROM 同目录同名
        // .sav），两者都是裸 .sav —— 与 melonDS 完全同格式互通。激烈核心
        // 不再有独立的存档格式选项。
        saveRawSav = true
    )

    /** GL 显示失败回退画布时调用（保留兼容入口）：
     * 反汇编实锤（getScreenBuffers 0x190f0 → 0x19398 分支）原生在高清
     * （config bit41）下对 512×384 帧池做 even-row/even-col 2:1 抽取
     * 降采样，画布路径拿到的仍是合法 256×192/屏 —— 高清无需降档，
     * 本函数不再改任何状态。 */
    fun revertHdForCanvasFallback() {
        // no-op：画布路径在高清会话下取到的是原生降采样帧，无需强制降回 1x
    }

    // ---- 输入缓存（按键与触摸必须合并成一次 updateInput 调用） ----

    @Volatile
    private var padBitsLibretro = 0

    @Volatile
    private var touchX = 0

    @Volatile
    private var touchY = 0

    @Volatile
    private var touchPressed = false

    /** 触屏 / 按键任何变化后调用；每次都送完整的按键+触摸状态。 */
    private fun pushInput() {
        if (!isLoaded) return
        val mask = toDrasticMask(padBitsLibretro)
        val packedXY = ((touchX shl 16) or touchY)
        val mask1 = if (touchPressed) mask or DS_TOUCH_FLAG else mask
        try {
            // ★ arg3 必须传 0（反汇编 0x16eb8-0x16ee8 实锤）：arg3 是【连发
            // (Turbo/自动连打) 按键掩码】而非"第二手柄掩码"。模拟主循环对
            // arg3 中命中的按键按连发模式表（0x106cf8，默认 0xaaaaaaaa=50%
            // 占空比）逐帧清除（bic w9, w9, w10）。旧实现把整个按键掩码同时
            // 传给 arg1/arg3，导致【所有按住的方向键/功能键都被连发模式周期
            // 性取消】—— 表现为走路一顿一顿、移动速度只有正常的几分之一
            // （"激烈核心走路有问题"根因）。原版 App 无连发指派时 arg3 恒 0。
            DraSticJNI.updateInput(mask1, packedXY, 0)
        } catch (e: Throwable) {
            android.util.Log.w("DraSticEngine", "updateInput failed", e)
        }
    }

    /**
     * 高清帧（512×384/屏，行距 = 帧池稠密布局 512 像素）→ 1x 合成帧
     * （256×192，写入 [dst] 的 [dstOffset] 像素偏移处）的 even-row/even-col
     * 2:1 抽取 —— 与原生 getScreenBuffers 高清降采样分支（0x19398）同算法。
     * 仅用于截图 / 存档缩略图的恒定 1x 规格（rawComposite），显示路径拿的
     * 是未降采样的真实 HD 帧。
     */
    private fun downsample2x(src: IntArray, dst: IntArray, dstOffset: Int) {
        for (y in 0 until 192) {
            val srcRow = y * 2 * 512
            val dstRow = dstOffset + y * 256
            var s = srcRow
            var d = dstRow
            for (x in 0 until 256) {
                dst[d++] = src[s]
                s += 2
            }
        }
    }

    private fun toDrasticMask(libretroBits: Int): Int {
        var m = 0
        if (libretroBits and LR_UP != 0) m = m or DS_UP
        if (libretroBits and LR_DOWN != 0) m = m or DS_DOWN
        if (libretroBits and LR_LEFT != 0) m = m or DS_LEFT
        if (libretroBits and LR_RIGHT != 0) m = m or DS_RIGHT
        if (libretroBits and LR_A != 0) m = m or DS_A
        if (libretroBits and LR_B != 0) m = m or DS_B
        if (libretroBits and LR_X != 0) m = m or DS_X
        if (libretroBits and LR_Y != 0) m = m or DS_Y
        if (libretroBits and LR_L != 0) m = m or DS_L
        if (libretroBits and LR_R != 0) m = m or DS_R
        if (libretroBits and LR_START != 0) m = m or DS_START
        if (libretroBits and LR_SELECT != 0) m = m or DS_SELECT
        return m
    }

    // ---- 生命周期 ----

    private val lifecycleLock = Any()

    /** onInit 是否已执行过（进程内只需一次）。 */
    private var nativeInitialized = false

    /** 原生线程拒绝退出/未响应时置位：核心状态不可预测，本进程内禁用激烈核心。 */
    @Volatile
    private var nativePoisoned = false

    private fun ensureNativeInit(): Boolean {
        if (nativePoisoned) return false
        if (nativeInitialized) return true
        if (DraSticJNI.JniStartupError) return false
        try {
            val ctx = appContext
            // 反汇编确认 onInit 只消费 versionCode 与 sdkInt；对象参数传
            // Application 上下文（永不被销毁，无泄漏风险）。
            android.util.Log.i(TAG, "onInit(versionCode=$DRASTIC_VERSION_CODE, sdk=${Build.VERSION.SDK_INT})")
            DraSticJNI.onInit(ctx, DRASTIC_VERSION_CODE, Build.VERSION.SDK_INT)
            nativeInitialized = true
            return true
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "onInit failed", e)
            return false
        }
    }

    // ------------------------------------------------------------------
    // 系统文件安装（对应原版 DraSticActivity.c0() 首次运行安装例程）
    // ------------------------------------------------------------------

    /**
     * 安装 DraStic 运行时系统文件 —— 原版 APK 的 assets 在首次运行时
     * 装入存储根，缺一不可（jadx 反编译 DraSticActivity/i0/j0/m0/h0/e0
     * 逐项核对）：
     *
     *  - `system/drastic_bios_{arm7,arm9}.bin` —— DraStic 自带的替代
     *    BIOS（**硬性依赖**：缺失时 startGame 的原生错误分支以未初始化
     *    的 jmp_buf 调 longjmp → siglongjmp 处 SIGSEGV 直接闪退）
     *  - `game_database.xml` —— 每游戏兼容性配置数据库（startGame 时按
     *    ROM 条目查询）
     *  - `usrcheat.dat` —— 金手指数据库（系统根 + 用户目录各一份，
     *    对齐原版 AddUser.e 语义）
     *  - `config/LC_default.dat` —— 横竖屏配置默认值
     *  - `drastic_bios.zip` —— 供原版"安装 BIOS"UI 使用（保持原版布局）
     *  - 11 个系统子目录 + 4 个用户子目录（backup/savestates/config/
     *    unzip_cache/system/input_record/cheats/slot2/microphone/scripts/
     *    users）
     *
     * 幂等：已存在的文件不重拷；game_database/usrcheat 由资产版本标记
     * （sysDir/.asset_version）控制重装，更新捆绑资产时递增
     * [DRASTIC_ASSET_VERSION] 即可。
     */
    private fun ensureDrasticSystemFiles(ctx: Context, sysDir: File, usrDir: File): Boolean {
        return try {
            for (d in SYSTEM_SUBDIRS) File(sysDir, d).mkdirs()
            for (d in USER_SUBDIRS) File(usrDir, d).mkdirs()

            val versionMarker = File(sysDir, ".asset_version")
            val reinstall = !versionMarker.isFile ||
                versionMarker.readText().trim() != DRASTIC_ASSET_VERSION.toString()
            if (reinstall) {
                android.util.Log.i(TAG, "installing drastic system assets (v$DRASTIC_ASSET_VERSION)…")
                copyAsset(ctx, "game_database.xml", File(sysDir, "game_database.xml"), overwrite = true)
                copyAsset(ctx, "usrcheat.dat", File(sysDir, "usrcheat.dat"), overwrite = true)
                copyAsset(ctx, "usrcheat.dat", File(usrDir, "usrcheat.dat"), overwrite = true)
                versionMarker.writeText(DRASTIC_ASSET_VERSION.toString())
            }

            // BIOS 与 LC_default：恒为"缺失才装"（用户可能自行替换过）
            copyAsset(ctx, "drastic_bios_arm7.bin", File(sysDir, "system/drastic_bios_arm7.bin"), overwrite = false)
            copyAsset(ctx, "drastic_bios_arm9.bin", File(sysDir, "system/drastic_bios_arm9.bin"), overwrite = false)
            copyAsset(ctx, "drastic_bios.zip", File(sysDir, "drastic_bios.zip"), overwrite = false)
            copyAsset(ctx, "LC_default.dat", File(sysDir, "config/LC_default.dat"), overwrite = false)

            val ready = File(sysDir, "system/drastic_bios_arm7.bin").isFile &&
                File(sysDir, "system/drastic_bios_arm9.bin").isFile &&
                File(sysDir, "game_database.xml").isFile
            android.util.Log.i(TAG, "drastic system files ready=$ready (sysDir=${sysDir.absolutePath})")
            ready
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "install drastic system files failed", e)
            false
        }
    }

    /** 从 APK assets/drastic/ 复制单个文件（幂等，按需覆盖）。 */
    private fun copyAsset(ctx: Context, assetName: String, dst: File, overwrite: Boolean) {
        if (!overwrite && dst.isFile && dst.length() > 0) return
        dst.parentFile?.mkdirs()
        ctx.assets.open("drastic/$assetName").use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        }
    }

    override fun ensureLoaded(): Boolean {
        return !DraSticJNI.JniStartupError
    }

    override fun loadRom(
        rom: File,
        systemDir: String,
        saveDir: String,
        onFrame: () -> Unit
    ): Boolean = synchronized(lifecycleLock) {
        if (nativePoisoned) {
            lastErrorMsg = "DraStic 原生在上一次会话中未响应退出，当前进程已禁用。请重启应用后再试。"
            return false
        }
        val availability = probeAvailability()
        if (!availability.available) {
            lastErrorMsg = availability.reason ?: "DraStic 不可用"
            return false
        }
        if (!rom.isFile || rom.length() < 0x200) {
            lastErrorMsg = "ROM 文件不存在或太小: ${rom.absolutePath}"
            return false
        }
        if (!ensureNativeInit()) {
            lastErrorMsg = "DraStic 原生初始化失败"
            return false
        }

        cleanup()

        // DraStic 私有目录（与 melonDS 的 <filesDir>/nds 系统目录互不干扰）
        val ctx = appContext
        if (ctx == null) {
            lastErrorMsg = "未设置 appContext（NesApp 启动时注册）"
            return false
        }
        val drasticRoot = File(ctx.filesDir, "drastic").apply { mkdirs() }
        val sysDir = File(drasticRoot, "system").apply { mkdirs() }
        val usrDir = File(drasticRoot, "user").apply { mkdirs() }
        DraSticPathCache.setBaseDirs(sysDir, usrDir)

        // 安装原版首次运行的系统文件（BIOS / 游戏数据库 / 金手指库 / 目录结构）。
        // 缺失时 startGame 会走入原生错误分支 —— 以未初始化的 jmp_buf 调
        // longjmp → siglongjmp 处 SIGSEGV 闪退（64 位设备实测复现）。
        if (!ensureDrasticSystemFiles(ctx, sysDir, usrDir)) {
            lastErrorMsg = "DraStic 系统文件安装失败（存储空间不足或 APK 资产缺失）"
            android.util.Log.e(TAG, "ensureDrasticSystemFiles failed")
            return false
        }

        // content:// 缓解：SAF 导入的 ROM 会复制成共享的 temp_rom.<ext>
        // —— 若照此启动，所有这类游戏会共享同一套 DraStic 存档基名。
        // 复制成 <filesDir>/drastic/roms/<gameId>.<ext> 保证每游戏独立。
        // （普通文件路径的 ROM 不复制，直接传原路径，存档跟随 ROM 目录）
        val effectiveRom: File = if (rom.nameWithoutExtension == "temp_rom" &&
            saveNameOverride != null) {
            try {
                val romsDir = File(drasticRoot, "roms").apply { mkdirs() }
                val ext = rom.extension.ifBlank { "nds" }
                val per = File(romsDir, "${saveNameOverride}.${ext}")
                rom.copyTo(per, overwrite = true)
                per
            } catch (_: Exception) {
                rom
            }
        } else {
            rom
        }
        romFileBase = effectiveRom.nameWithoutExtension

        // === 存档互通（跟随全局存档方式，激烈核心无独立存档模式）===
        // 全局存档方式（设置 → 存储 → 存档方式）由 EmulatorScreen 解析后经
        // saveDir / setSaveName 传入：
        //   globalSaveMode == "nesstation" → <filesDir>/saves/<gameId>.sav
        //   globalSaveMode == "core_builtin" → ROM 同目录同名 .sav
        // 原生库始终把电池存档写到固定私有目录（预编译 .so 写死）：
        //   激烈侧  <filesDir>/drastic/user/backup/<ROM基名>.sav —— 反汇编
        //           格式串 "%s%cbackup%c%s.sav" @0x10ede4；
        // bit50 恒置位（裸 .sav，与 melonDS 同格式），共享侧即全局存档位置的
        // 同一份文件；会话边界做时间戳双向同步，旧版 .dsv（DeSmuME 带头格式）
        // 一次性迁移剥壳。
        run {
            val base = romFileBase ?: return@run
            val backupDir = File(drasticRoot, "user/backup").apply { mkdirs() }
            val drasticSav = File(backupDir, "$base.sav")
            val sharedName = saveNameOverride?.takeIf { it.isNotBlank() } ?: base
            val sharedSav = File(saveDir, "$sharedName.sav")
            drasticSavFile = drasticSav
            sharedSavFile = sharedSav
            migrateDsvIfNeeded(drasticSav)
            syncSaveOnLoad(sharedSav, drasticSav)
        }

        // 预启动配置。ROM 以真实绝对路径传入（startGame 的路径最终会回到
        // DraSticPathCache.open —— 绝对路径分支直接解析为真实文件）
        // 先拍会话快照（原生分辨率初始化与 GL 视图纹理分配都以此为准）。
        // ★ 高清 2x 与放大滤镜共存（模仿 melonDS 思路，本轮修复核心）：
        // 全局滤镜选 xBR/HQx 系时【不再强制关闭高清渲染】。旧实现认为
        // getScreenBuffers 固定拷 0x30000 字节、高清帧池下取不到完整帧
        // —— 前提错误。反汇编实锤：config bit41（_Hires3D）置位时
        // （全量 config 存于 0x14c468，byte5 = 0x14c46d 的 bit1），
        // getScreenBuffers 跳转到 0x19398 降采样分支：对 512×384 帧池做
        // even-row/even-col 2:1 抽取，输出合法 256×192/屏 —— 即高清会话
        // 下滤镜管线照常拿到 1x 帧，滤镜正常工作；同时 3D 内部仍以 2x
        // 渲染，降采样帧相当于超采样（AA 更干净），两特性同时生效。
        activeHdRender = optHdRender
        // 模拟线程数：自动探测（≥4核=3，≥2核=2，否则1）或用户强制 1-8。
        // ★ 必须 ≥1 —— 原版绝不会传 0；传 0 = 单线程 3D 光栅化，
        // 3D 大游戏每帧只能完成顶部 1 段（16 行）→ "画面显示不完整"。
        activeThreads = effectiveEmuThreads(optThreads)
        try {
            DraSticPathCache.changeRom(effectiveRom.absolutePath, effectiveRom)

            DraSticJNI.setAudioVolume(DEFAULT_VOLUME)
            DraSticJNI.setAutosaveInterval(optAutosaveInterval.coerceIn(0, 1800))
            DraSticJNI.applyConfig(currentConfig(fastForward = false))
        } catch (e: Throwable) {
            lastErrorMsg = "DraStic 启动异常: ${e.message}"
            android.util.Log.e("DraSticEngine", "pre-start config failed", e)
            return false
        }

        // ===============================================================
        // 启动架构（反汇编 libdrastic 逐条验证，与旧实现的关键差异）：
        //  · startGame() 本身就是模拟主循环，跑在调用者线程上，游戏运行
        //    期间【永不返回】（quitSystem 置退出标志后才返回）。原版 App
        //    在专门的后台 GameThread 里调它。旧实现把 startGame 当"加载
        //    函数"同步等待其返回 → loadRom 永不返回 → UI 永远停在
        //    "正在加载"（此时模拟循环已在跑、OpenSL 音频正常出声）。
        //  · 因此：startGame 放到专用守护线程 drastic-game（该线程整个
        //    会话期间 = 模拟线程），loadRom 只等"就绪信号" = 渲染消费
        //    线程拿到首帧（frameCount > 0），拿到即返回 true。
        // ===============================================================
        isLoaded = false
        paused = false
        running.set(true)
        gameEnded.set(false)
        gameResult.set(false)

        // 渲染消费线程（帧搬运）。waitScreen 为原生 condvar 等待：核心建立
        // 之前它阻塞在零初始化的 mutex/cond 上（pthread_*_INITIALIZER
        // 语义，安全）；首帧信号到达后开始搬运。getScreenBuffers 未就绪时
        // 有空指针守卫，被提前唤醒也不会读坏内存。
        renderThread = thread(name = "drastic-render", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            try {
                while (running.get()) {
                    // 阻塞到下一帧就绪（原生每帧 signal）
                    DraSticJNI.waitScreen()
                    if (!running.get()) break
                    if (paused) continue

                    if (!glDisplayActive) {
                        // 渲染线程帧搬运：
                        //  · filteredGlActive（GL 放大滤镜路径）：只搬原始合成帧，
                        //    frameBuffer 尺寸 = 帧源合成尺寸（1x 256×384 / 高清
                        //    512×768），GL 线程取去滤镜；
                        //  · 画布路径 + 放大滤镜：frameBuffer 指向放大后的合成帧，
                        //    NdsDualScreenView 按 filteredVideoWidth/Height 切片
                        //    绘制（与 melonDS 自由布局的滤镜路径同构）；先切换
                        //    frameBuffer 引用再更新尺寸，视图侧 fb.size < vw*vh
                        //    的守卫可安全吸收一帧的中间态。
                        //  · 画布路径 + 无放大滤镜：搬原始帧（截图/存档缩略图用）。
                        //
                        // ★ 高清帧读取（"高清2x渲染无效果"修复核心）：
                        //   高清会话（activeHdRender）下原生 getScreenBuffers 走
                        //   0x19398 降采样分支只返回 256×192/屏（分辨率增益全部
                        //   丢失）。因此优先调 NdsNative.drasticGetCompletedFrames
                        //   （libndscore 内 drastic_frames.cpp shim，反汇编定位
                        //   video_state，读"刚完成的帧"，与原生 renderFrame 同一
                        //   缓冲、天然无撕裂）拿真实 512×384/屏 帧；shim 校验失败
                        //   （16位渲染/档位异常/容量不足）再回落 getScreenBuffers
                        //   （行为同修复前，绝不劣化）。
                        //   拿到 HD 帧后：
                        //     · frameBuffer / displayW/H 用真实 HD 合成（512×768）
                        //       —— 画布路径与 GL 滤镜路径的源分辨率翻倍；
                        //     · rawComposite 恒 2:1 抽取为 256×384（截图/缩略图
                        //       规格不变）。
                        var frameW = 256
                        var frameH = 192
                        var srcTop: IntArray = topBuf
                        var srcBottom: IntArray = bottomBuf
                        var gotHd = false
                        if (activeHdRender) {
                            gotHd = try {
                                NdsNative.drasticGetCompletedFrames(hdTopBuf, hdBottomBuf, hdDims)
                            } catch (_: Throwable) {
                                false
                            }
                            if (gotHd && hdDims[0] == 512 && hdDims[1] == 384) {
                                frameW = 512; frameH = 384
                                srcTop = hdTopBuf; srcBottom = hdBottomBuf
                            } else {
                                gotHd = false   // 档位异常 —— 回落 1x 路径
                            }
                        }
                        if (!gotHd) {
                            // ★ 帧快照一致性校验（"多线程 3D 渲染上下屏部分贴图错乱"
                            //   根因修复）：反汇编 sub_1cd18（0x1cd18）证实
                            //   getScreenBuffers 直接读取帧池【写入槽】指针
                            //   （[video_state+0x958]，无锁），拷贝循环同样无锁。
                            //   多线程 3D（bit28）开启时，原生 3D 光栅化线程以
                            //   16 行/段异步写入同一缓冲 → 一次快照可能取到半成品
                            //   帧（上下屏水平条带错乱）。写入者在冲刷后毫秒级内
                            //   收尾，因此做"双拷贝一致性"校验：两次拷贝完全一致
                            //   才视为完整帧；不一致则让步 1ms 重试（最多 3 次，
                            //   超限用末次结果避免卡帧）。
                            var coherent = false
                            var attempt = 0
                            while (attempt < 3 && !coherent && running.get()) {
                                DraSticJNI.getScreenBuffers(topBuf, bottomBuf)
                                if (!optThreaded3D) break   // 单线程 3D：CPU 同步出帧，无需校验
                                // 让步给原生写入者收尾，再拷一次比对
                                System.arraycopy(topBuf, 0, topVerify, 0, topBuf.size)
                                System.arraycopy(bottomBuf, 0, bottomVerify, 0, bottomBuf.size)
                                try { Thread.sleep(1) } catch (_: InterruptedException) { break }
                                DraSticJNI.getScreenBuffers(topBuf, bottomBuf)
                                coherent = topBuf.contentEquals(topVerify) &&
                                    bottomBuf.contentEquals(bottomVerify)
                                attempt++
                            }
                        }
                        // 1x 原始合成帧（恒 256×384）—— 截图/存档缩略图规格不变。
                        // 1x 帧直接拷贝；HD 帧做 even-row/even-col 2:1 抽取
                        // （与原生 0x19398 降采样分支同算法）。
                        if (gotHd) {
                            downsample2x(srcTop, rawComposite, 0)
                            downsample2x(srcBottom, rawComposite, 256 * 192)
                        } else {
                            System.arraycopy(srcTop, 0, rawComposite, 0, topBuf.size)
                            System.arraycopy(srcBottom, 0, rawComposite, topBuf.size, bottomBuf.size)
                        }
                        val f = activeVideoFilter
                        val canvasFilter = !filteredGlActive && isUpscaleFilter(f)
                        if (canvasFilter) {
                            val scale = if (f == 6 || f == 8 || f == 9 || f == 10) 4 else 2
                            // 源为单屏 frameW×frameH（1x=256×192 / HD=512×384），
                            // 合成帧 = frameW*scale × frameH*2*scale。
                            val fw = frameW * scale
                            val fh = frameH * 2 * scale
                            val half = fw * (frameH * scale)
                            if (frameBuffer.size < fw * fh) frameBuffer = IntArray(fw * fh)
                            val fb = frameBuffer
                            val nTop = try {
                                NdsNative.applyUpscaleFilterArgb(f, srcTop, frameW, frameH, fb, 0)
                            } catch (e: Throwable) {
                                android.util.Log.w(TAG, "canvas filter(top) failed", e); 0
                            }
                            if (nTop > 0) {
                                try {
                                    NdsNative.applyUpscaleFilterArgb(f, srcBottom, frameW, frameH, fb, half)
                                } catch (e: Throwable) {
                                    android.util.Log.w(TAG, "canvas filter(bottom) failed", e)
                                    System.arraycopy(rawComposite, 0, fb, 0, 256 * 384)
                                    displayW = 256; displayH = 384
                                    frameCount++
                                    onFrame()
                                    continue
                                }
                                displayW = fw; displayH = fh
                            } else {
                                // 滤镜失败（不应发生）→ 回落原始帧
                                System.arraycopy(rawComposite, 0, fb, 0, 256 * 384)
                                displayW = 256; displayH = 384
                            }
                        } else {
                            // 原始合成帧（1x=256×384 / HD=512×768，上屏在前）
                            val cw = frameW
                            val ch = frameH * 2
                            if (frameBuffer.size < cw * ch) frameBuffer = IntArray(cw * ch)
                            System.arraycopy(srcTop, 0, frameBuffer, 0, cw * frameH)
                            System.arraycopy(srcBottom, 0, frameBuffer, cw * frameH, cw * frameH)
                            displayW = cw; displayH = ch
                        }
                    }
                    frameCount++
                    onFrame()
                }
            } catch (e: Throwable) {
                if (running.get()) {
                    android.util.Log.e("DraSticEngine", "render thread died", e)
                }
            }
        }

        // 模拟线程（= 原版 GameThread）：整个游戏会话期间都耗在 startGame
        // 里。音频由核心内部 OpenSL 线程输出，与该线程并行。
        gameThread = thread(name = "drastic-game", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            var ok = false
            try {
                android.util.Log.i(TAG, "startGame: rom=${effectiveRom.name} (${effectiveRom.length()}B) — 进入原生模拟主循环")
                val startT0 = System.currentTimeMillis()
                ok = DraSticJNI.startGame(
                    effectiveRom.absolutePath,
                    -1,                       // 不自动读档
                    currentConfig(fastForward = false),
                    0,
                    false,
                    -1L                       // 自定义时钟关闭
                )
                android.util.Log.i(TAG, "startGame returned=$ok after ${System.currentTimeMillis() - startT0}ms（游戏会话结束）")
            } catch (e: Throwable) {
                android.util.Log.e("DraSticEngine", "startGame failed", e)
                lastErrorMsg = "DraStic 启动异常: ${e.message}"
            }
            gameResult.set(ok)
            if (!ok && lastErrorMsg.isEmpty()) {
                lastErrorMsg = "DraStic 无法加载 ROM（文件损坏或不受支持）"
            }
            gameEnded.set(true)
            // 会话在运行中意外结束（模拟线程没了）：撤下 loaded 让视图停止
            // 绘制，等待用户退出重试。正常退出走 cleanup()（先置
            // running=false），不会命中这里。
            if (isLoaded && running.get()) {
                android.util.Log.w(TAG, "drastic session ended unexpectedly while loaded")
                isLoaded = false
            }
        }

        // 等待就绪：渲染消费线程拿到首帧 = 游戏已在模拟，立即返回 true。
        // （frameCount 由渲染线程在每次成功搬运后 +1。）
        val deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS
        while (true) {
            if (gameEnded.get()) {
                // startGame 在产出任何帧之前就返回了 —— 启动失败
                android.util.Log.e(TAG, "startGame ended before producing any frame")
                running.set(false)
                wakeAndJoinRender(500)
                gameThread = null
                lastErrorMsg = lastErrorMsg.ifBlank {
                    if (gameResult.get()) "DraStic 会话提前结束（未产出画面）"
                    else "DraStic 无法加载 ROM（文件损坏或不受支持）"
                }
                return false
            }
            if (frameCount > 0L) {
                break   // 首帧已到 —— 游戏运行中
            }
            if (System.currentTimeMillis() >= deadline) {
                android.util.Log.e(TAG, "boot TIMEOUT after ${BOOT_TIMEOUT_MS}ms — no frames produced")
                // 干净收场（对齐原版拆机顺序）：quitSystem 让 startGame 返回
                // → 等模拟线程退出 → releaseSystem。之后可以安全重试。
                try { DraSticJNI.pauseSystem(1) } catch (_: Throwable) {}
                try { DraSticJNI.quitSystem() } catch (_: Throwable) {}
                val gt = gameThread
                if (gt != null) {
                    try { gt.join(3000) } catch (_: InterruptedException) {}
                }
                val emuDied = gt?.isAlive != true
                running.set(false)
                wakeAndJoinRender(500)
                gameThread = null
                if (emuDied) {
                    try { DraSticJNI.releaseSystem() } catch (_: Throwable) {}
                    lastErrorMsg = "DraStic 启动超时（${BOOT_TIMEOUT_MS / 1000} 秒内未产生画面）。" +
                        "已自动恢复，可重试或改用 melonDS 核心。"
                } else {
                    // 原生未响应退出请求 —— 绝不能 releaseSystem（会释放仍在
                    // 使用的核心状态），本进程内禁用激烈核心防止二次踩踏。
                    nativePoisoned = true
                    lastErrorMsg = "DraStic 启动超时且原生未响应退出，请重启应用后再试。"
                }
                return false
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        isLoaded = true
        lastErrorMsg = ""
        return true
    }

    /** 唤醒可能阻塞在 waitScreen 的渲染线程并等待其退出（毫秒上限）。 */
    private fun wakeAndJoinRender(timeoutMs: Long) {
        try { DraSticJNI.signalScreen() } catch (_: Throwable) {}
        renderThread?.let { t ->
            try { t.join(timeoutMs) } catch (_: InterruptedException) {}
        }
        renderThread = null
    }

    override fun setPaused(paused: Boolean) {
        if (this.paused == paused) return
        this.paused = paused
        if (isLoaded) {
            try {
                DraSticJNI.pauseSystem(if (paused) 1 else 0)
            } catch (e: Throwable) {
                android.util.Log.w("DraSticEngine", "pauseSystem failed", e)
            }
        }
    }

    override fun setFastForward(speed: Int) {
        if (!isLoaded) return
        val active = speed > 0
        // 快进激活位 = bit29；快进速率 = bits12-15（_FfwdSpeed 表
        // [50%,150%,200%,300%,400%,无限制]，仅 bit29 激活时被原生消费）。
        // 分辨率位用会话快照，不随快进翻转。
        try {
            DraSticJNI.applyConfig(currentConfig(fastForward = active))
        } catch (e: Throwable) {
            android.util.Log.w("DraSticEngine", "applyConfig(ff) failed", e)
        }
    }

    override fun reset(hard: Boolean) {
        if (!isLoaded) return
        try {
            DraSticJNI.resetDS()
        } catch (e: Throwable) {
            android.util.Log.w("DraSticEngine", "resetDS failed", e)
        }
    }

    override fun saveState(slot: Int, dst: File): Boolean {
        if (!isLoaded) return false
        return try {
            DraSticPathCache.resetWriteLog()
            val ok = DraSticJNI.saveState(slot, false)
            if (ok) {
                // 观察器定位原生实际写的 .dss，复制一份到 NesStation 槽位
                // 路径（槽位 UI 的存在性检查 + 用户备份/迁移友好性）
                val virtual = DraSticPathCache.lastWritePath("dss")
                val real = virtual?.let { DraSticPathCache.resolveForTest(it) }
                if (real != null && real.isFile && real.length() > 0) {
                    nativeStateFiles[slot] = real
                    try {
                        real.copyTo(dst, overwrite = true)
                    } catch (_: Exception) {
                        // 复制失败不影响存档成功判定（DraStic 自己的 .dss 仍在）
                    }
                } else {
                    // 写路径观察失败（极少见）→ 写标记让槽位 UI 可见
                    try {
                        dst.writeText("drastic-state-slot:$slot")
                    } catch (_: Exception) {
                    }
                }
            }
            ok
        } catch (e: Throwable) {
            android.util.Log.w("DraSticEngine", "saveState failed", e)
            false
        }
    }

    override fun loadState(slot: Int, src: File): Boolean {
        if (!isLoaded) return false
        return try {
            // 原生槽位文件若不存在而 NesStation 侧有真实备份 → 先恢复再读档。
            // 槽位文件定位（多重候选，任一存在即直接读档）：
            //  a) saveState 时记录的写路径（写路径观察器，最可靠）
            //  b) userDir/savestates/<romBase>_<slot>.dss（规则推导）
            val nativeFile = findNativeStateFile(slot)
            if (nativeFile == null && src.isFile && isRealDssBackup(src)) {
                restoreBackupToNative(slot, src)
            }
            DraSticJNI.loadState(slot)
        } catch (e: Throwable) {
            android.util.Log.w("DraSticEngine", "loadState failed", e)
            false
        }
    }

    /** saveState 时记录的槽位 → 原生 .dss 映射（进程内）。 */
    private val nativeStateFiles = HashMap<Int, File>()

    /** 定位原生槽位文件：优先已记录路径，其次按命名规则推导。 */
    private fun findNativeStateFile(slot: Int): File? {
        nativeStateFiles[slot]?.let { if (it.isFile) return it }
        val ctx = appContext ?: return null
        val base = romFileBase ?: return null
        val candidates = listOf(
            File(File(ctx.filesDir, "drastic/user/savestates"), "${base}_$slot.dss"),
            File(File(ctx.filesDir, "drastic/system/savestates"), "${base}_$slot.dss")
        )
        return candidates.firstOrNull { it.isFile && it.length() > 0 }
    }

    /** NesStation 侧备份是否是真实 .dss（排除 saveState 写的标记文件）。 */
    private fun isRealDssBackup(src: File): Boolean {
        if (src.length() < 128) {
            return !runCatching {
                src.readText().startsWith("drastic-state-slot:")
            }.getOrDefault(true)
        }
        return true
    }

    /** 把 NesStation 备份复制回原生槽位路径（推导目标名）。 */
    private fun restoreBackupToNative(slot: Int, src: File) {
        try {
            val ctx = appContext ?: return
            val base = romFileBase ?: return
            val targetDir = File(ctx.filesDir, "drastic/user/savestates").apply { mkdirs() }
            src.copyTo(File(targetDir, "${base}_$slot.dss"), overwrite = true)
        } catch (_: Exception) {
        }
    }

    /** 当前 ROM 的去扩展名基名（存档恢复路径构造用，loadRom 时记录）。 */
    private var romFileBase: String? = null

    // ---- 存档互通（与 melonDS 共用裸 .sav）：本会话两侧的存档文件 ----
    /** melonDS 侧共享存档（<saveDir>/<gameId>.sav）。 */
    private var sharedSavFile: File? = null
    /** 激烈核心备份目录的裸 .sav（drastic/user/backup/<ROM基名>.sav）。 */
    private var drasticSavFile: File? = null

    /** NesStation 传来的每游戏存档名（content:// temp_rom 缓解用）。 */
    private var saveNameOverride: String? = null

    /** 旧版 .dsv（DeSmuME 兼容带头格式）一次性迁移为裸 .sav。
     * .dsv 布局（反汇编 DraStic 写入器 0x785f8 实锤）：
     *   [0x50 字节说明文本][版本字段][标志位] "|-DESMUME SAVE-|\\0" <原始存档>
     *   ["|<--Snip above here ... savedata footer:" 行]
     * 即原始存档紧随 15 字符标记 + 1 个 NUL 终止符（写入器按 16 字节
     * 一次性写入）；DeSmuME 写入器则在标记后直接跟换行。头部之后多出的
     * 尾部字节对读取端无害（按芯片容量取前缀），故尾部不做修剪。
     * */
    private fun migrateDsvIfNeeded(drasticSav: File) {
        if (drasticSav.isFile && drasticSav.length() > 0) return
        val dsv = File(drasticSav.parentFile, "${drasticSav.nameWithoutExtension}.dsv")
        if (!dsv.isFile || dsv.length() == 0L) return
        try {
            val data = dsv.readBytes()
            val marker = "|-DESMUME SAVE-|".toByteArray(Charsets.US_ASCII)
            val footer = ("|<--Snip above here to create a raw sav by excluding " +
                "this DeSmuME savedata footer").toByteArray(Charsets.US_ASCII)
            val s = indexOfBytes(data, marker)
            if (s < 0) return
            var start = s + marker.size
            var end = indexOfBytes(data, footer, start)
            if (end < 0) end = data.size
            // DraStic 写入器：标记后是 NUL 终止符（16 字节块写入的一部分）
            if (start < end && data[start] == 0.toByte()) {
                start++
            } else if (start < end && (data[start] == '\n'.code.toByte() ||
                    data[start] == '\r'.code.toByte())) {
                // DeSmuME 写入器：标记后是换行
                start++
                if (start < end && data[start - 1] == '\r'.code.toByte() &&
                    data[start] == '\n'.code.toByte()) start++
            }
            if (end <= start) return
            drasticSav.writeBytes(data.copyOfRange(start, end))
            // 保留原 .dsv 的修改时间：互通同步按时间戳择新，迁移件
            // 不应伪造为“最新”而压过 melonDS 侧真正的最新进度
            drasticSav.setLastModified(dsv.lastModified())
            android.util.Log.i(TAG, "save interop: migrated ${dsv.name} -> ${drasticSav.name} (${end - start}B)")
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "save interop: dsv migration failed", e)
        }
    }

    private fun indexOfBytes(data: ByteArray, pat: ByteArray, from: Int = 0): Int {
        if (pat.isEmpty() || data.size < pat.size) return -1
        outer@ for (i in from..data.size - pat.size) {
            for (j in pat.indices) {
                if (data[i + j] != pat[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** 进游戏：共享侧（melonDS）较新的裸 .sav → 拷入激烈备份目录。 */
    private fun syncSaveOnLoad(shared: File, drastic: File) {
        try {
            if (shared.isFile && shared.length() > 0) {
                if (!drastic.isFile || shared.lastModified() > drastic.lastModified()) {
                    shared.copyTo(drastic, overwrite = true)
                    android.util.Log.i(TAG, "save interop: imported ${shared.name} -> drastic backup")
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "save interop: import failed", e)
        }
    }

    /** 退游戏：激烈备份目录较新的裸 .sav → 回写共享位置（melonDS 直接可用）。
     * 在 cleanup 末尾（原生 quit 已刷盘、releaseSystem 完成后）调用。 */
    private fun syncSaveOnUnload() {
        val shared = sharedSavFile ?: return
        val drastic = drasticSavFile ?: return
        try {
            if (drastic.isFile && drastic.length() > 0) {
                if (!shared.isFile || drastic.lastModified() >= shared.lastModified()) {
                    shared.parentFile?.mkdirs()
                    drastic.copyTo(shared, overwrite = true)
                    // 对齐时间戳：下次进游戏时两侧时间相等，不再重复回导
                    shared.setLastModified(drastic.lastModified())
                    android.util.Log.i(TAG, "save interop: exported ${drastic.name} -> shared")
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "save interop: export failed", e)
        }
    }

    override fun unload() = synchronized(lifecycleLock) {
        cleanup()
    }

    override fun shutdown() = synchronized(lifecycleLock) {
        cleanup()
    }

    /**
     * 停止模拟线程 → 停止渲染线程 → 释放核心状态（顺序对齐原版拆机流程）。
     *
     * startGame 是模拟主循环本身：quitSystem 只是置退出标志，必须等它
     * 真正从 startGame 返回（gameThread 结束）之后才能 releaseSystem ——
     * 否则会释放仍在被模拟循环使用的核心状态（use-after-free）。
     */
    private fun cleanup() {
        if (!isLoaded && !running.get() && gameThread == null) return
        running.set(false)
        isLoaded = false
        paused = false

        // 释放所有按键 / 触摸，避免"粘键"带入下一局
        try {
            padBitsLibretro = 0
            touchPressed = false
            touchX = 0
            touchY = 0
            // 原生未响应退出时可能仍卡在 startGame，绝不能再并发碰它
            if (!nativePoisoned && nativeInitialized && !DraSticJNI.JniStartupError) {
                DraSticJNI.updateInput(0, 0, 0)
            }
        } catch (_: Throwable) {
        }

        // 原版顺序：pauseSystem(1) → quitSystem() → 等待 startGame 返回 → releaseSystem()
        try {
            if (!nativePoisoned && nativeInitialized && !DraSticJNI.JniStartupError) {
                DraSticJNI.pauseSystem(1)
                DraSticJNI.quitSystem()
            }
        } catch (_: Throwable) {
        }

        // 等待模拟线程从 startGame 返回（quitSystem 已请求退出；2.5 秒上限）
        val gt = gameThread
        if (gt != null) {
            try { gt.join(2500) } catch (_: InterruptedException) {}
        }
        val emuDied = gt?.isAlive != true
        gameThread = null

        // 唤醒可能阻塞在 waitScreen 的渲染线程（condvar signal）——
        // 在 releaseSystem 释放状态内存**之前**发信号是安全的；不发的话
        // 渲染线程可能永远阻塞在已释放的 condvar 上（quitSystem 后原生
        // 模拟循环随之停止，不再有每帧 signal）。
        try {
            if (!nativePoisoned && nativeInitialized && !DraSticJNI.JniStartupError) {
                DraSticJNI.signalScreen()
            }
        } catch (_: Throwable) {
        }

        // 等渲染线程退出（唤醒信号已发，正常情况立即返回；1 秒超时兜底）
        renderThread?.let { t ->
            try {
                t.join(1000)
            } catch (_: InterruptedException) {
            }
        }
        val renderDied = renderThread?.isAlive != true
        renderThread = null

        try {
            if (!nativePoisoned && nativeInitialized && !DraSticJNI.JniStartupError) {
                DraSticJNI.releaseSystem()
            }
        } catch (_: Throwable) {
        }

        // 模拟/渲染线程任一拒绝退出时，releaseSystem 已经执行会构成
        // use-after-free 风险 —— 置 poisoned 阻止本进程再次启动激烈核心。
        if (!emuDied || !renderDied) {
            nativePoisoned = true
            android.util.Log.e(TAG, "cleanup: drastic native threads refused to exit (emu=$emuDied render=$renderDied)")
        }

        // 存档互通：原生 quit 已把备份刷盘 → 较新的裸 .sav 回写共享位置
        // （melonDS 直接可用）。异常会话（poisoned）也尽力导出，避免丢档。
        syncSaveOnUnload()
    }

    // ---- 输入（EmulatorEngine / NdsCoreEngine） ----

    override fun setPad1(bits: Int) {
        padBitsLibretro = bits
        pushInput()
    }

    override fun setPad2(bits: Int) {
        // DraStic 原生 updateInput 无 P2 通道（单机 NDS），忽略
    }

    override fun setTouchInputDirect(x: Int, y: Int, pressed: Boolean) {
        touchX = x.coerceIn(0, 255)
        touchY = y.coerceIn(0, 191)
        touchPressed = pressed
        pushInput()
    }

    override fun setTouchInput(x: Int, y: Int, pressed: Boolean) {
        // 归一化坐标路径（Hybrid 布局兜底）→ 换算回下屏像素
        val px = (((x + 0x8000) * 256) ushr 16).coerceIn(0, 255)
        val py = (((y + 0x8000) * 192) ushr 16).coerceIn(0, 191)
        setTouchInputDirect(px, py, pressed)
    }

    // ---- 渲染查询（NdsCoreEngine） ----

    override fun frameStamp(): Long = frameCount

    /**
     * 画布回退路径当前呈现的合成帧尺寸：无放大滤镜时为原生 256×384
     * （高清会话经 CPU 路径拿到真实 HD 帧时为 512×768）；放大类滤镜激活时
     * 为放大后的合成帧（2x → 源宽×(源高×2)再×2 …），与 [frameBuffer]
     * 内容一致（渲染线程每帧更新，渲染消费线程之外的线程只读）。
     */
    @Volatile
    private var displayW: Int = 256
    @Volatile
    private var displayH: Int = 384

    override fun filteredVideoWidth(): Int = displayW

    override fun filteredVideoHeight(): Int = displayH

    /**
     * 恒定 1x 原始合成帧（256×384，上屏在前）的只读引用。
     * 供 DraSticGlView 在"滤镜输出超过 GL_MAX_TEXTURE_SIZE"等场景把滤镜
     * 源回退到 1x 使用（配合 [frameStamp] 做前后一致性校验，与 frameBuffer
     * 的取帧协议同构）。渲染线程恒把它维护为最新帧的 1x 版本 —— 高清会话
     * 下由 512×384/屏 的 HD 帧 2:1 抽取得到。
     */
    fun oneXComposite(): IntArray = rawComposite

    override fun videoWidth(): Int = 256

    override fun videoHeight(): Int = 384

    // ---- 其余 EmulatorEngine 成员（多为 no-op / 简化实现） ----

    override fun setSurface(surface: android.view.Surface?) {
        // DraStic 走画布渲染路径（NdsDualScreenView），不用 ANativeWindow
    }

    override fun setSaveName(name: String) {
        // NesStatio 统一存档名（仅用于 content:// 共享 temp_rom 的缓解，
        // 见 loadRom；DraStic 自身的存档命名由原生按 ROM 文件名派生）
        saveNameOverride = name.takeIf { it.isNotBlank() }
    }

    override fun setCoreOption(key: String, value: String) {
        // melonds_* 选项对 DraStic 无意义；drastic_* 专属键在此消费。
        // 音量立即转发；位域类设置先入缓存 —— 游戏未启动时随 startGame
        // 生效，已启动时经 applyConfig 热更新（原生解包函数立即消费）。
        // 键名与取值对照原版 App（jadx：f0.h 的 SharedPreferences 读写）。
        when (key) {
            "drastic_volume" -> {
                value.toIntOrNull()?.let {
                    try {
                        DraSticJNI.setAudioVolume(it.coerceIn(0, 100))
                    } catch (_: Throwable) {
                    }
                }
            }
            "drastic_sound" -> {
                optSound = value == "enabled"
                applyOptionsHot()
            }
            "drastic_hd_render" -> {
                optHdRender = value == "enabled"
                applyOptionsHot()
            }
            // ---- 已移除的独立存档模式（历史版本遗留键，忽略）----
            // "drastic_save_format"（sav/dsv）：激烈核心不再有独立存档格式
            // 设置 —— bit50 恒置位（裸 .sav），电池存档的位置与格式完全跟随
            // 全局存档方式（设置 → 存储 → 存档方式）。旧键收到后直接忽略。
            "drastic_frameskip_type" -> {
                optFrameskipType = value.toIntOrNull()?.coerceIn(0, 2) ?: 0
                applyOptionsHot()
            }
            "drastic_frameskip_value" -> {
                optFrameskipValue = value.toIntOrNull()?.coerceIn(0, 9) ?: 4
                applyOptionsHot()
            }
            "drastic_frameskip_safe" -> {
                optFrameskipSafe = value == "enabled"
                applyOptionsHot()
            }
            "drastic_threaded_3d" -> {
                optThreaded3D = value == "enabled"
                applyOptionsHot()
            }
            "drastic_16bit" -> {
                optGlUse16Bit = value == "enabled"
                applyOptionsHot()
            }
            "drastic_edge_marking" -> {
                optDisableEdgeMarking = value == "enabled"
                applyOptionsHot()
            }
            "drastic_fix_main_screen" -> {
                optFixMainEngineScreen = value == "enabled"
                applyOptionsHot()
            }
            "drastic_audio_latency" -> {
                optAudioLatency = value.toIntOrNull()?.coerceIn(0, 3) ?: 3
                applyOptionsHot()
            }
            "drastic_mic_enabled" -> {
                optMicEnabled = value == "enabled"
                applyOptionsHot()
            }
            "drastic_mic_level" -> {
                optMicLevel = value.toIntOrNull()?.coerceIn(0, 3) ?: 1
                applyOptionsHot()
            }
            "drastic_autofire_speed" -> {
                optAutoFireSpeed = value.toIntOrNull()?.coerceIn(0, 4) ?: 2
                applyOptionsHot()
            }
            "drastic_ffwd_rate" -> {
                optFfwdRate = value.toIntOrNull()?.coerceIn(0, 5) ?: 2
                applyOptionsHot()
            }
            "drastic_slot2_type" -> {
                optSlot2Type = value.toIntOrNull()?.coerceIn(0, 5) ?: 1
                applyOptionsHot()
            }
            "drastic_rtc_system_time" -> {
                optRtcSystemTime = value == "enabled"
                applyOptionsHot()
            }
            "drastic_cheats_enabled" -> {
                optCheatsEnabled = value == "enabled"
                applyOptionsHot()
            }
            "drastic_lua_enabled" -> {
                optLuaEnabled = value == "enabled"
                applyOptionsHot()
            }
            "drastic_backup_in_savestates" -> {
                optBackupInSavestates = value == "enabled"
                applyOptionsHot()
            }
            "drastic_ignore_card_limit" -> {
                optIgnoreGamecardLimit = value == "enabled"
                applyOptionsHot()
            }
            "drastic_auto_trim" -> {
                optAutoTrim = value == "enabled"
                applyOptionsHot()
            }
            "drastic_preload_roms" -> {
                optPreloadRoms = value == "enabled"
                applyOptionsHot()
            }
            "drastic_show_fps" -> {
                optShowFps = value == "enabled"
                applyOptionsHot()
            }
            "drastic_threads" -> {
                // 0=自动（按核数 1/2/3），1-8=强制；startGame 时经
                // effectiveEmuThreads 计算生效（线程组在帧冲刷中按需重建）
                optThreads = value.toIntOrNull()?.coerceIn(0, 8) ?: 0
                applyOptionsHot()
            }
            "drastic_autosave_interval" -> {
                val v = value.toIntOrNull()?.coerceIn(0, 1800) ?: 0
                optAutosaveInterval = v
                if (isLoaded) {
                    try {
                        DraSticJNI.setAutosaveInterval(v)
                    } catch (_: Throwable) {
                    }
                }
            }
            // ---- 兼容旧键（历史版本遗留，映射到新语义） ----
            "drastic_ffwd_speed" -> {
                // 旧"快进倍率"(0-3) 实际写的是麦克风等级位（bits37-38）——
                // 已纠正；旧值丢弃，避免把麦克风等级改坏。
            }
        }
    }

    /** 游戏运行中热更新位域设置（未加载时仅入缓存，startGame 时生效）。 */
    private fun applyOptionsHot() {
        if (!isLoaded) return
        try {
            DraSticJNI.applyConfig(currentConfig(fastForward = false))
        } catch (e: Throwable) {
            android.util.Log.w("DraSticEngine", "applyConfig(options) failed", e)
        }
    }

    /**
     * 当前全局滤镜编号（EmulatorScreen 经 EmulatorEngine.setVideoFilter 下发，
     * 与 melonDS 共用同一套编号）：
     *   0=none, 1=scanline, 2=crt, 3=dot, 4=xbr(2x), 5=hq2x, 6=hq4x,
     *   7=xbr+dot(2x), 8=4xbr(4x), 9=4xbr+dot(4x), 10=hq4x+dot(4x)
     *
     * - 叠加类（1/2/3）由视图层 FilterOverlay 绘制（melonDS 与 DraStic 通用）；
     * - 放大类（4..10）由 [DraSticGlView] 消费：取 frameBuffer 后经
     *   NdsNative.applyUpscaleFilter 做与 melonDS 相同的 CPU 放大，再自行
     *   上传纹理绘制（核心预编译 .so 无滤镜能力，显示层补齐）。
     * 可游戏运行中热切换，GL 视图每帧读取。
     */
    @Volatile
    var activeVideoFilter: Int = 0
        private set

    /** 放大类滤镜（2x：4/5/7；4x：6/8/9/10）。 */
    fun isUpscaleFilter(filter: Int = activeVideoFilter): Boolean =
        filter == 4 || filter == 5 || filter == 7 ||
        filter == 6 || filter == 8 || filter == 9 || filter == 10

    override fun setVideoFilter(filter: Int) {
        activeVideoFilter = filter
    }

    override fun setHighQualityScaling(enabled: Boolean) {
        // 画布路径按视图分辨率绘制，无此开关
    }

    override fun setRegion(region: Int) {
        // NDS 无区域概念
    }

    override fun setSampleRate(rate: Int) {
        // OpenSL 输出采样率由原生管理
    }

    override var frameHook: NetplayHook?
        get() = null
        set(value) {
            // 推模型核心不支持帧同步联机（UI 层已保证联机时强制 melonDS）
        }

    override fun realtimeFps(): Double = 0.0

    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        return try {
            if (glDisplayActive) {
                // GL 路径：渲染消费线程不搬运帧，截图时直接拉取当前帧
                // （getScreenBuffers 恒为 256×192×2 输出，与分辨率档无关）
                val top = IntArray(256 * 192)
                val bottom = IntArray(256 * 192)
                DraSticJNI.getScreenBuffers(top, bottom)
                val out = IntArray(256 * 384)
                System.arraycopy(top, 0, out, 0, top.size)
                System.arraycopy(bottom, 0, out, top.size, bottom.size)
                FrameCapture(out, 256, 384)
            } else {
                // 画布路径：截取原始 256×384 合成帧（rawComposite 恒为原始
                // 帧；frameBuffer 在放大滤镜激活时是放大后的呈现帧，尺寸
                // 与截图规格不符，不用它）
                FrameCapture(rawComposite.copyOf(), 256, 384)
            }
        } catch (e: Throwable) {
            null
        }
    }

    @Volatile
    private var lastErrorMsg: String = ""

    override fun lastError(): String = lastErrorMsg
}
