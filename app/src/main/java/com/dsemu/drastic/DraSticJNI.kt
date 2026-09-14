@file:Suppress("unused", "ClassName", "FunctionName")

package com.dsemu.drastic

import androidx.annotation.Keep

/**
 * DraStic（激烈）NDS 模拟器核心的 JNI 契约类。
 *
 * 包名 / 类名 / 方法签名与原版 APK 内的 com.dsemu.drastic.DraSticJNI **严格一致** ——
 * libdrastic*.so 的原生符号按 Java_com_dsemu_drastic_DraSticJNI_<method> 命名解析，
 * 任何改名都会导致 UnsatisfiedLinkError。原版通过 androidx.annotation.Keep +
 * proguard 规则防混淆，本项目在 proguard-rules.pro 中同样 keep 整个包。
 *
 * 库加载顺序（与原版一致，不能颠倒）：
 *  1. System.loadLibrary("drastic_cpu")   —— ~10KB 的 CPU 探测库
 *  2. getCpuType()：0 = ARMv7a+NEON  → loadLibrary("drastic")
 *                   1 = Tegra2 兼容   → loadLibrary("drastic_compat")
 *                   3 = ARMv8a (64位) → loadLibrary("drastic_arm64")
 *                   其余 = 不支持（x86 / 不识别的 CPU）
 *
 * 这些 .so 同时提供 armeabi-v7a（32 位：drastic / drastic_compat）与
 * arm64-v8a（64 位：drastic_arm64，无 Tegra2 兼容版 —— Tegra2 仅 32 位）
 * 两套版本。32/64 位进程各自加载对应 ABI 目录下的 drastic_cpu 完成探测：
 *  - 32 位进程：drastic_cpu 返回 0/1 → 加载 drastic / drastic_compat；
 *  - 64 位进程：drastic_cpu 返回 3（内部探测命中 arm64 特征）→ 加载
 *    drastic_arm64。
 * 两套主库导出**完全相同的 72 个 JNI 符号**（已符号级比对验证），
 * JNI 契约、位布局、调用时序完全一致，上层引擎代码无需区分 ABI。
 * 仅 x86 / x86_64 进程因库缺失在类初始化时失败（DraStic 无 x86 版）。
 *
 * NesStation 默认构建含 arm64-v8a + armeabi-v7a，64 位设备上 DraStic
 * 以原生 64 位运行（DraSticEngine.probeAvailability 报告可用）；若某
 * 设备 CPU 不受支持则 UI 禁用该选项并显示原因。
 *
 * 语义注释中标 [反汇编] 的条目来自对 libdrastic.so 的 capstone 反汇编验证，
 * 标 [反编译] 的来自 jadx 对原版 classes.dex 的反编译 —— 两者交叉确认。
 */
@Keep
object DraSticJNI {

    // ---- CPU 类型常量（原版 getCpuType 返回值） ----
    const val CPU_TYPE_ARMv7a_NEON: Int = 0
    const val CPU_TYPE_ARMv7a_TEGRA2: Int = 1
    const val CPU_TYPE_X86: Int = 2
    const val CPU_TYPE_ARMv8a: Int = 3
    const val CPU_TYPE_X86_64: Int = 4
    const val CPU_TYPE_UNSUPPORTED: Int = -1

    @JvmField
    @Volatile
    var JniCpuType: Int = -1

    @JvmField
    @Volatile
    var JniStartupError: Boolean = false

    init {
        try {
            System.loadLibrary("drastic_cpu")
            val cpuType = getCpuType()
            JniCpuType = cpuType
            val lib = when (cpuType) {
                CPU_TYPE_ARMv7a_NEON -> "drastic"
                CPU_TYPE_ARMv7a_TEGRA2 -> "drastic_compat"
                CPU_TYPE_ARMv8a -> "drastic_arm64"
                else -> null
            }
            if (lib != null) {
                System.loadLibrary(lib)
            }
        } catch (e: UnsatisfiedLinkError) {
            JniStartupError = true
        } catch (e: SecurityException) {
            JniStartupError = true
        }
    }

    // ---- 生命周期 ----

    /**
     * 全局初始化。原版传 (activity, versionCode, Build.VERSION.SDK_INT)。
     * [反汇编] 原生侧只消费 versionCode（存 +0x498）与 sdkInt（存 +0x4c8），
     * 第一个 Object 参数未被 onInit 本体使用（可能仅在尾部初始化里作
     * JNI 上下文），传 Application 即可。
     */
    @JvmStatic external fun onInit(app: Any?, versionCode: Int, sdkInt: Int)

    /**
     * 启动游戏。原生线程接管模拟与 OpenSL 音频。
     * @param path ROM 路径（本移植直接传真实绝对路径，DraSticPathCache 负责解析）
     * @param slot 启动时自动读取的存档槽（-1 = 不读档正常启动）
     * @param config 打包配置位域（见 DraSticEngine.packConfig）
     * @param arg3 原版恒传 0
     * @param quickLoad 原版两条分支传 0 / 1（快速载入模式），这里恒 false
     * @param clockRate 自定义时钟（-1 = 禁用；原版 _CustomClock，仅启用时生效）
     */
    @JvmStatic external fun startGame(
        path: String,
        slot: Int,
        config: Long,
        arg3: Int,
        quickLoad: Boolean,
        clockRate: Long
    ): Boolean

    /** 暂停(1)/恢复(0)模拟。 */
    @JvmStatic external fun pauseSystem(paused: Int)

    /** 通知原生模拟线程退出（不阻塞；随后 releaseSystem 释放状态）。 */
    @JvmStatic external fun quitSystem()

    /** 释放核心状态。必须在 quitSystem 之后调用。 */
    @JvmStatic external fun releaseSystem()

    /** 软复位（相当于按 RESET）。 */
    @JvmStatic external fun resetDS()

    // ---- 输入 ----

    /**
     * 推送输入状态（按键 + 触摸）。
     *
     * [反汇编] 语义（Java_com_dsemu_drastic_DraSticJNI_updateInput）：
     *  - arg1: bit31 = 触摸激活标志；bit0-30 = 按键掩码
     *  - arg2: (touchX shl 16) or touchY —— x: 0..255, y: 0..191
     *  - arg3: 第二按键掩码，原生将其与 arg1 掩码 OR 合并后写入 NDS 输入
     *    （并非 P2 手柄，两掩码同值安全）
     *
     * 按键位布局（反编译 + 反汇编交叉验证）：
     *  bit0=Up  bit1=Down  bit2=Left  bit3=Right
     *  bit4=A   bit5=B     bit6=X     bit7=Y
     *  bit8=L   bit9=R     bit10=Start bit11=Select
     *  bit12=触摸指示位（由原生在触摸激活时自动置位，Java 侧不使用）
     */
    @JvmStatic external fun updateInput(buttonsOrTouchFlag: Int, packedXY: Int, buttons2: Int)

    /**
     * updateInput 的超集：推送输入并返回打包状态。
     * 返回值 [反汇编]：bit31=状态有效位，bit30=事件挂起标志（读后清零），
     * bit16-23=百分比（0..100），bit0-15=内部计数。本移植统一走 updateInput，
     * 此方法仅供诊断。
     */
    @JvmStatic external fun updateFrame(buttonsOrTouchFlag: Int, packedXY: Int, buttons2: Int): Int

    /** 麦克风噪声开关（配合 config 的 MicEnabled 位使用）。 */
    @JvmStatic external fun setWhitenoiseFeed(enabled: Boolean)

    @JvmStatic external fun updateAccelerometer(x: Float, y: Float, z: Float)

    @JvmStatic external fun updateGyroscope(value: Float)

    /** 翻盖状态（NDS 合盖）。 */
    @JvmStatic external fun setHingeStatus(closed: Boolean)

    // ---- 视频 ----

    /**
     * 等待下一帧就绪（阻塞；原生每帧与每 50ms 都会 signal，不会死锁）。
     * 消费者循环：waitScreen() → getScreenBuffers() → 绘制。
     */
    @JvmStatic external fun waitScreen()

    /** 手动唤醒 waitScreen 的消费者（输入变化时原版用它请求重绘）。 */
    @JvmStatic external fun signalScreen()

    /**
     * 拉取上下两屏像素（快照式）。
     * @param top    输出上屏，长度 49152 (256×192)，ARGB_8888
     * @param bottom 输出下屏，长度 49152
     */
    @JvmStatic external fun getScreenBuffers(top: IntArray, bottom: IntArray)

    /** 截图到 256×192×2 的 int 数组（首屏在前）。 */
    @JvmStatic external fun getScreenshot(out: IntArray)

    /** 清屏（w、h 语义未知，原版用于 GL 路径，软件渲染无需调用）。 */
    @JvmStatic external fun clearScreens(w: Int, h: Int)

    /** GL 纹理渲染路径（需要 EGL 上下文，本移植不使用，保留契约）。 */
    @JvmStatic external fun renderFrame(tex1: Int, tex2: Int, portrait: Boolean)

    @JvmStatic external fun renderFrameTex(tex1: Int, tex2: Int)

    @JvmStatic external fun renderFrameTexExt(tex1: Int, tex2: Int)

    @JvmStatic external fun fxSetup(w: Int, h: Int, x: Int, y: Int, sw: Int, sh: Int)

    @JvmStatic external fun fxRender(
        tex1: Int, tex2: Int, i2: Int, i3: Int, i4: Int,
        e: Int, f: Int, g: Int, h: Int, landscape: Boolean
    )

    @JvmStatic external fun fxLoad(path: String, i: Int, i2: Int): Int

    @JvmStatic external fun extfxSetup(i: Int, i2: Int, i3: Int, i4: Int, i5: Int, i6: Int)

    @JvmStatic external fun extfxRender(i: Int, i2: Int, i3: Int, i4: Int, i5: Int, i6: Int)

    @JvmStatic external fun extfxLoad(path: String, i: Int, i2: Int): Int

    // ---- 配置 ----

    /**
     * 热更新打包配置（位域布局见 DraSticEngine.packConfig）。
     * 原生立即读取并生效（声音/快进/作弊等开关）。
     */
    @JvmStatic external fun applyConfig(config: Long)

    /** 音量 0..100（原版 _Volume 0..10 × 10）。 */
    @JvmStatic external fun setAudioVolume(volume: Int)

    /** 自动存档间隔（秒；0 = 关闭，原版取值 0/300/900/1800）。 */
    @JvmStatic external fun setAutosaveInterval(intervalSec: Int)

    /** 写固件用户数据（昵称/语言/生日/颜色，语言在低 8 位）。 */
    @JvmStatic external fun setFirmwareUserdata(nickname: String, packedFields: Int)

    // ---- 存档 ----

    /**
     * 保存即时存档到指定槽（0..8 手动槽；9 为原版快速存档槽）。
     * @param backup 原版 StateMenu 传 false（是否额外做备份副本）
     */
    @JvmStatic external fun saveState(slot: Int, backup: Boolean): Boolean

    /** 读取指定槽的即时存档。 */
    @JvmStatic external fun loadState(slot: Int): Boolean

    /** 最近一次自动存档使用的槽位。 */
    @JvmStatic external fun getSavingSlot(): Int

    /** 核心是否正在写存档（写 .dss 期间为 true）。 */
    @JvmStatic external fun isSaving(): Boolean

    /** 读取 16 个存档槽缩略图（路径为 ROM 路径）。 */
    @JvmStatic external fun getSnapshots16(path: String, topOut: IntArray, bottomOut: IntArray)

    @JvmStatic external fun getSnapshots16Direct(path: String, topOut: IntArray, bottomOut: IntArray)

    @JvmStatic external fun getSnapshots16TopGreyscale(path: String, out: IntArray)

    // ---- ROM 信息 ----

    @JvmStatic external fun isNdsFile(path: String): Boolean

    @JvmStatic external fun getRomSize(path: String): Long

    @JvmStatic external fun getRomType(path: String): Int

    /** ROM 图标（title 数据 8×8 tile）。 */
    @JvmStatic external fun getRomIconData(path: String, paletteOut: IntArray, charOut: ByteArray, infoOut: ByteArray): Boolean

    /** 换卡带（多卡游戏）。 */
    @JvmStatic external fun insertGame(path: String, slot: Int, flag: Boolean, config: Long): Boolean

    // ---- 诊断 ----

    @JvmStatic external fun getFrameInfo(): Int

    @JvmStatic external fun getPerformanceCounters(): Int

    @JvmStatic external fun getInfoString(): String

    @JvmStatic external fun getVersionString(sub: Int): String

    @JvmStatic external fun getRumbleState(): Boolean

    // ---- 作弊 ----

    @JvmStatic external fun updateCheats(flag: Boolean)

    @JvmStatic external fun getCheatCount(): Int

    @JvmStatic external fun getCheatName(index: Int): ByteArray?

    @JvmStatic external fun getCheatNote(index: Int): ByteArray?

    @JvmStatic external fun getCheatEnabled(index: Int): Boolean

    @JvmStatic external fun setCheatEnabled(index: Int, enabled: Boolean)

    @JvmStatic external fun getCheatFolderCount(): Int

    @JvmStatic external fun getCheatFolderId(index: Int): Int

    @JvmStatic external fun getCheatFolderName(index: Int): ByteArray?

    @JvmStatic external fun getCheatFolderNote(index: Int): ByteArray?

    @JvmStatic external fun getCheatFolderExpanded(index: Int): Boolean

    @JvmStatic external fun setCheatFolderExpanded(index: Int, expanded: Boolean)

    @JvmStatic external fun getCheatFolderMultiSelect(index: Int): Boolean

    @JvmStatic external fun getCustomCheatCount(): Int

    @JvmStatic external fun getCustomCheatName(index: Int): ByteArray?

    @JvmStatic external fun getCustomCheatData(index: Int): IntArray?

    @JvmStatic external fun getCustomCheatEnabled(index: Int): Boolean

    @JvmStatic external fun setCustomCheatEnabled(index: Int, enabled: Boolean)

    @JvmStatic external fun addCustomCheat(name: String, data: IntArray, index: Int, enabled: Boolean): Int

    @JvmStatic external fun removeCustomCheat(index: Int)

    @JvmStatic external fun findCustomCheat(data: IntArray, index: Int): Int

    // ---- Lua 脚本（原版扩展功能，保留契约） ----

    @JvmStatic external fun luaIsActive(): Boolean

    @JvmStatic external fun luaGetOverrides(): Int

    @JvmStatic external fun luaUpdateAxisValues(f1: Float, f2: Float, f3: Float, f4: Float)

    @JvmStatic external fun luaUpdateRotation(rotation: Int)

    // ---- CPU 探测（libdrastic_cpu.so） ----

    /**
     * 探测当前进程 CPU 类型。
     *  - 32 位 ARM 进程返回 0/1（NEON / Tegra2）；
     *  - 64 位 ARM 进程返回 3（ARMv8a —— 反汇编验证：内部探测命中 arm64
     *    特征（返回 4）时映射为 3，否则 -1）；
     *  - x86 / x86_64 进程因 drastic_cpu 库缺失在类初始化时直接失败。
     */
    @JvmStatic external fun getCpuType(): Int
}
