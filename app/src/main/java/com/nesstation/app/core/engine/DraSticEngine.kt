package com.nesstation.app.core.engine

import android.content.Context
import android.os.Build
import com.dsemu.drastic.DraSticJNI
import com.dsemu.drastic.filesystem.DraSticPathCache
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
 * NesStation 统一使用 libretro 12 键位（bit0=A … bit11=R）；
 * DraStic 使用 bit0-3=方向 / bit4-11=A,B,X,Y,L,R,Start,Select +
 * bit31=触摸标志（[DraSticJNI.updateInput] 反汇编验证）。
 * [setPad1] 负责 libretro→DraStic 的位重排，[pushInput] 保证按键与触摸
 * 状态合并成一个完整的 updateInput 调用（两状态都缓存在引擎里）。
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
 * 电池存档（.dsv）与即时存档（.dss 槽 0-8，9 为快速槽）全部由原生经
 * [DraSticPathCache] 写入 `<filesDir>/drastic/user/`。即时存档与
 * NesStation 槽位 UI 的互通见 [saveState]（写路径观察器精确定位 .dss，
 * 复制一份到 NesStation 的 .state 路径供槽位存在性检查 / 备份迁移）。
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
        // libretro: bit0=A bit1=B bit2=Select bit3=Start bit4=Up bit5=Down
        //           bit6=Left bit7=Right bit8=X bit9=Y bit10=L bit11=R
        // DraStic:  bit0=Up bit1=Down bit2=Left bit3=Right bit4=A bit5=B
        //           bit6=X bit7=Y bit8=L bit9=R bit10=Start bit11=Select
        private const val LR_A = 0x001
        private const val LR_B = 0x002
        private const val LR_SELECT = 0x004
        private const val LR_START = 0x008
        private const val LR_UP = 0x010
        private const val LR_DOWN = 0x020
        private const val LR_LEFT = 0x040
        private const val LR_RIGHT = 0x080
        private const val LR_X = 0x100
        private const val LR_Y = 0x200
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
         * 打包 DraStic 配置位域（位布局经 libdrastic_arm64.so 逐指令反汇编
         * 验证 —— applyConfig(0x1a4a0) → 解包器(0x17c58) → 各消费点全链路）：
         *
         *  bit31     = 声音启用（解包器 → [cfg+0x460]；!bit31 写入音频
         *              引擎的"跳过音频"标志）
         *  bit29     = 快进激活状态（运行中随 setFastForward 翻转）
         *  bits12-15 = 快进时的显示帧间隔表索引（0x1070c0：
         *              [100000,33333,25000,16666,12500,5000]µs，仅 ≤5 有效；
         *              固定用 2 = 25ms = 40fps 显示上限）
         *  bit41     = 高清渲染（2x）：解包器 → [cfg+0x4a0] → startGame 路径
         *              读 [core+0x8aaf8] → setResolution(0x1cde4) 写两屏
         *              分辨率档 → renderFrame 上传 (scale+1)×256 × (scale+1)×192；
         *              帧池每屏 0xC0000 = 512×384×4，按 2x 容量预分配
         *  bits37-38 = 快进倍率表索引（0x1d728 读 0x10a080：[2,4,8,16] → float
         *              存 [0x143ec0]；索引钳位到 0..3）
         *  bit50     = 存档格式：解包器 → [cfg+0x4b8]，0=.sav，1=.dsv
         *  bit23     = 帧缓冲色彩格式（applyConfig 直接测试 → [core+0x3b2f931]
         *              的 16/32 bpp → sub_1cbc0 写 GL 常量 0x1908/0x1401 或
         *              0x1907/0x8363）——恒为 0（32 位）：16 位的 REV 类型
         *              非 GLES 核心，不暴露
         *  其余位保持 0 = 关闭/自动（默认即已验证可正常显示的配置字）
         */
        private fun packConfig(
            sound: Boolean,
            fastForward: Boolean,
            ffwdSpeed: Int,
            hdRender: Boolean = false,
            ffwdMultiplier: Int = 0,
            saveDsv: Boolean = false
        ): Long {
            var cfg = 0L
            if (sound) cfg = cfg or 0x80000000L
            if (fastForward) cfg = cfg or 0x20000000L
            cfg = cfg or ((ffwdSpeed.coerceIn(0, 15).toLong()) shl 12)
            if (hdRender) cfg = cfg or (1L shl 41)             // bit 41
            cfg = cfg or ((ffwdMultiplier.coerceIn(0, 3).toLong()) shl 37)  // bits 37-38
            if (saveDsv) cfg = cfg or (1L shl 50)              // bit 50
            return cfg
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
    // loadRom 之前缓存的值会在 startGame 时生效；游戏运行期间修改的
    // 会通过 applyConfig 热更新（原生解包函数 0x17c58 立即消费新位域）。

    /** 声音开关（bit31）。 */
    @Volatile
    var optSound: Boolean = true

    /** 高清渲染 2x（bit41）。 */
    @Volatile
    var optHdRender: Boolean = false

    /** 快进倍率索引 0..3 = 2x/4x/8x/16x（bits37-38）。 */
    @Volatile
    var optFfwdMultiplier: Int = 0

    /** 存档格式 .dsv（bit50）；false = 裸 .sav。 */
    @Volatile
    var optSaveDsv: Boolean = false

    // ---- 会话快照（loadRom 时从用户偏好拍下，决定原生 GPU 初始化） ----
    // bit41（高清）在 startGame 时进入原生分辨率初始化（setResolution 路径），
    // 决定帧池的上传尺寸；GL 显示视图也按此快照分配纹理。游戏运行中改设置
    // 只入 opt* 偏好，下次进游戏生效 —— 避免视图/原生两侧尺寸不一致导致
    // 上传错位。

    /** 本局游戏的高清渲染快照（loadRom 时拍下）。 */
    @Volatile
    var activeHdRender: Boolean = false
        private set

    /** GL 显示路径已接管帧消费（DraSticGlView 置位）：
     * 渲染消费线程跳过 getScreenBuffers 的 CPU 帧拷贝（约 500KB/帧），
     * 仅维持帧计数与就绪握手；截图改由 captureFrame 直接拉取。 */
    @Volatile
    var glDisplayActive: Boolean = false

    /** 用会话快照 + 快进状态打包完整 config（热更新用，保证分辨率位
     *  与当前会话的原生状态一致）。 */
    private fun currentConfig(fastForward: Boolean): Long = packConfig(
        sound = optSound,
        fastForward = fastForward,
        ffwdSpeed = 2,                       // 快进时显示帧间隔档（40fps 显示上限）
        hdRender = activeHdRender,
        ffwdMultiplier = if (fastForward) optFfwdMultiplier else 0,
        saveDsv = optSaveDsv
    )

    /** GL 显示失败回退画布时调用：立即把原生渲染分辨率降回 1x（bit41=0），
     * 并同步偏好与会话快照 —— 画布路径的 getScreenBuffers 固定按
     * 256×192 读取，高清帧池下只能取到左上 1/4。 */
    fun revertHdForCanvasFallback() {
        optHdRender = false
        activeHdRender = false
        if (isLoaded) {
            try {
                DraSticJNI.applyConfig(packConfig(
                    sound = optSound,
                    fastForward = false,
                    ffwdSpeed = 2,
                    hdRender = false,
                    ffwdMultiplier = 0,
                    saveDsv = optSaveDsv
                ))
            } catch (e: Throwable) {
                android.util.Log.w("DraSticEngine", "revert HD failed", e)
            }
        }
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
            DraSticJNI.updateInput(mask1, packedXY, mask)
        } catch (e: Throwable) {
            android.util.Log.w("DraSticEngine", "updateInput failed", e)
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

        // 预启动配置。ROM 以真实绝对路径传入（startGame 的路径最终会回到
        // DraSticPathCache.open —— 绝对路径分支直接解析为真实文件）
        // 先拍会话快照（原生分辨率初始化与 GL 视图纹理分配都以此为准）。
        activeHdRender = optHdRender
        try {
            DraSticPathCache.changeRom(effectiveRom.absolutePath, effectiveRom)

            DraSticJNI.setAudioVolume(DEFAULT_VOLUME)
            DraSticJNI.setAutosaveInterval(0)
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
                        // 画布路径：搬运帧到 frameBuffer（截图/存档缩略图用）。
                        // GL 路径：跳过约 500KB/帧的 CPU 拷贝，GL 线程直接消费帧池。
                        DraSticJNI.getScreenBuffers(topBuf, bottomBuf)
                        // 合成：上屏在前、下屏在后（256×384）
                        System.arraycopy(topBuf, 0, frameBuffer, 0, topBuf.size)
                        System.arraycopy(bottomBuf, 0, frameBuffer, topBuf.size, bottomBuf.size)
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

    /** NesStation 快进倍速 → DraStic 倍率索引（bits37-38，表 [2,4,8,16]）。
     * 表内倍速精确映射；表外倍速（3x/6x）回落到用户的激烈快进倍率设置。 */
    private fun ffwdMultiplierIndex(speed: Int): Int = when (speed) {
        2 -> 0
        4 -> 1
        8 -> 2
        16 -> 3
        else -> optFfwdMultiplier.coerceIn(0, 3)
    }

    override fun setFastForward(speed: Int) {
        if (!isLoaded) return
        val active = speed > 0
        // NesStation 倍速 → DraStic 倍率索引（bits37-38，表 [2,4,8,16]）。
        // bits12-15 的显示帧间隔档保持 2（快进时 40fps 显示上限，防止
        // 显示线程拖慢模拟）。分辨率位用会话快照，不随快进翻转。
        try {
            DraSticJNI.applyConfig(
                packConfig(
                    sound = optSound,
                    fastForward = active,
                    ffwdSpeed = 2,
                    hdRender = activeHdRender,
                    ffwdMultiplier = if (active) ffwdMultiplierIndex(speed) else optFfwdMultiplier,
                    saveDsv = optSaveDsv
                )
            )
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

    /** NesStation 传来的每游戏存档名（content:// temp_rom 缓解用）。 */
    private var saveNameOverride: String? = null

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

    override fun filteredVideoWidth(): Int = 256

    override fun filteredVideoHeight(): Int = 384

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
        // 立即生效的部分（音量）直接转发；位域类设置先入缓存 ——
        // 游戏未启动时随 startGame 生效，已启动时经 applyConfig 热更新。
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
            "drastic_ffwd_speed" -> {
                // 用户设置快进倍率：0..3 → 2x/4x/8x/16x（仅快进激活时生效）
                optFfwdMultiplier = value.toIntOrNull()?.coerceIn(0, 3) ?: 0
            }
            "drastic_save_format" -> {
                optSaveDsv = value == "dsv"
                applyOptionsHot()
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

    override fun setVideoFilter(filter: Int) {
        // 叠加型滤镜（scanline/crt/dot）由 NdsDualScreenView 视图层绘制；
        // 放大型（HQ2X 等）为 melonDS 原生特性，DraStic 不支持，忽略
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
                FrameCapture(frameBuffer.copyOf(), 256, 384)
            }
        } catch (e: Throwable) {
            null
        }
    }

    @Volatile
    private var lastErrorMsg: String = ""

    override fun lastError(): String = lastErrorMsg
}
