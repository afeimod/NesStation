package com.nesstation.app.core.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.view.Surface
import com.nesstation.app.core.jni.IshirukaNative
import org.dolphinemu.ishiiruka.NativeLibrary
import org.dolphinemu.ishiiruka.services.DirectoryInitializationService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Ishiiruka（NGC/Wii 模拟器核心，Dolphin 优化分支）引擎 —— NesStation 集成。
 *
 * 集成方式参照 DraStic（激烈）核心：上游 Java 宿主层以**原包名 JNI 契约**形式
 * vendored（`org.dolphinemu.ishiiruka.NativeLibrary` +
 * `services.DirectoryInitializationService`，见契约文件），预编译核心库
 * `libishiiruka.so`（= 上游 libmain.so，来自 Ishiiruka APK）放
 * `app/src/main/jniLibs/arm64-v8a/`，经 scripts/fetch_azahar_ishiruka_libs.sh 提取。
 *
 * 架构（推模型，Dolphin 5.0 系 —— 引擎不拥有模拟循环）：
 *  1. [loadRom] 初始化目录（`<filesDir>/ishiiruka/user` + `sys`）、编程式写入
 *     GCPadNew.ini / WiimoteNew.ini（把 Touchscreen 设备的全部虚拟按键绑定进核心
 *     输入系统）与 Dolphin.ini / GFX.ini 设置，随后在专用线程调用
 *     `NativeLibrary.Run(path)`（阻塞直至退出）；
 *  2. Surface 生命周期经 `NativeLibrary.SurfaceChanged/SurfaceDestroyed`；
 *  3. 暂停/恢复走 `PauseEmulation/UnPauseEmulation`，停止走 `StopEmulation`；
 *  4. 音频由核心内部播放（推模型，引擎无 AudioTrack）。
 *
 * 输入（经原生 ButtonManager "Touchscreen" 设备，Button N / Axis N±）：
 *  - 按键：[setPad1] 的 libretro 位布局 → GC 或 Wii 按键事件（按 [controlMode]）；
 *  - 摇杆：[setAnalogAxes] → 方向轴事件（STICK_MAIN / STICK_C / NUNCHUK_STICK）；
 *  - IR 指针：[setPointer]（视图归一化坐标）→ WIIMOTE_IR 六轴绝对输入 ——
 *    无需上游未有的"触摸 IR"补丁，纯标准绑定即可实现绝对指向。
 *
 * 存档：核心 SaveState/LoadState 使用自身槽位（User/StateSaves），dst 参数仅做
 * UI 兼容标记（同 PS2 引擎做法）。
 */
class IshirukaEngine private constructor() : EmulatorEngine, NgcWiiCoreEngine {

    override val frameBuffer = IntArray(1)

    private val running = AtomicBoolean(false)
    private var heartbeatThread: Thread? = null

    // ★★ 本轮新增：心跳线程实测节拍滑动窗口（Wii FPS HUD 显示根因修复）★★
    //   背景：libishiiruka.so 未导出任何 FPS/PerformanceMetrics JNI（nm -D 实测
    //   只有 IsRunning() 布尔），无法像 PS2 那样直接读核心内部 getFPS ——
    //   EmulatorScreen 的 fpsDisplay 旧实现 fallback 到心跳计数：心跳线程
    //   Thread.sleep(16) 固定节拍，counted 永远 ~60，掉帧/快进都看不出来。
    //   修复：在心跳线程里同步打点 nanoTime()，保留最近 60 个样本，按窗口
    //   平均间隔反推"实测节拍"。系统空闲时 ~60；CPU 抢占/掉帧时节拍漂移
    //   下行，HUD 数值能跟随下降 —— 至少给用户一个掉帧信号（远比永远 60 准确）。
    //   注：这是 CPU 节拍代理，不是原生渲染 FPS —— 真·核心 FPS 仍需开
    //   GFX.ini/Settings/ShowFPS（核心内置 HUD 会在画面上叠加
    //   "FPS: %.0f - VPS:%.0f - %.0f%%"）。
    private val heartbeatTimes = java.util.ArrayDeque<Long>()
    private val heartbeatLock = Any()
    @Volatile private var measuredFps: Double = 0.0

    @Volatile private var surface: Surface? = null
    @Volatile private var romPath: String? = null
    @Volatile private var userDir: String? = null
    @Volatile private var saveDir: String? = null
    private var emuThread: Thread? = null

    @Volatile override var isLoaded = false
        private set

    @Volatile private var _paused = false
    @Volatile private var _ffSpeed = 0
    @Volatile private var lastErrorText = ""

    /** 控制模式："ngc" / "wii" / "auto"（auto 按游戏平台判定）。 */
    @Volatile override var controlMode: String = "auto"

    /** Wii 扩展手柄（wii 模式）："nunchuk" / "classic" / "none"。 */
    @Volatile override var wiiExtension: String = "nunchuk"

    /**
     * Wii Remote 握持方向（仅影响前端虚拟按键布局）："vertical" / "horizontal"。
     * 由 UI 经门面键 "IshirukaEngine/wiiOrientation" 下发。
     */
    @Volatile var wiiOrientation: String = "vertical"

    /** 复合键（"文件.ini/Section/key"）→ 配置值 的热更新集合。 */
    private val coreOptions = LinkedHashMap<String, String>()

    /** IR 指针当前轴值（增量推送前缓存，避免重复事件）。 */
    private var irLast = FloatArray(6)
    /** 摇杆当前轴值缓存（方向轴仅在变化时推送）。 */
    private var stickLast = FloatArray(8)

    /**
     * ★ NGC/WII 闪退修复：本局游戏的平台判定缓存（null = 未判定）。
     *
     * 旧实现 [isGameCubeGame] 在**每次调用**时都走 JNI
     * GameFileCache.addOrGet —— 而它被 overlay 渲染（每帧/每次重组的
     * effectiveMode 查询）、每个输入事件（setPad1/setAnalogAxes/触摸）
     * 高频调用，且原生侧 AddOrGet 需要解析光盘卷头（重量级 I/O）。
     * 更致命的是：从未调用 GameFileCache.init() 时原生单例为 null →
     * addOrGet 直接 SIGSEGV（见契约文件注释）。现在：
     *   1. loadRom（IO 线程）先 init() 创建单例，再一次性判定并缓存；
     *   2. isGameCubeGame 只读缓存，渲染/输入线程零 JNI、零 I/O；
     *   3. 缓存缺失时（理论上仅 loadRom 前的短暂窗口）兑底用纯 Kotlin
     *      魔数探测（32 字节读取）并就地缓存。
     */
    @Volatile private var cachedIsGameCube: Boolean? = null

    /** GameFileCache.init() 是否已成功（决定 JNI 判定通道可用性）。 */
    @Volatile private var gameFileCacheReady = false

    // === Netplay（推模型核心不支持锁步 —— 接受但无效） ===
    @Volatile private var _frameHook: NetplayHook? = null
    @Volatile private var _netFrame = 0L
    override var frameHook: NetplayHook?
        get() = _frameHook
        set(value) {
            _frameHook = value
            _netFrame = 0L
        }

    private val lifecycleLock = Any()

    /** App context — set by NesApp.onCreate. */
    @Volatile var appContext: Context? = null

    // ------------------------------------------------------------------
    // 可用性
    // ------------------------------------------------------------------

    data class Availability(val available: Boolean, val reason: String?)

    @Volatile private var probedAvailability: Availability? = null

    fun probeAvailability(): Availability {
        probedAvailability?.let { return it }
        val result = try {
            if (IshirukaNative.ensureLoaded()) {
                Availability(true, null)
            } else {
                Availability(
                    false,
                    "libishiiruka 加载失败（当前进程非 ARM64，或库缺失）\n" +
                        "Ishiiruka 核心仅提供 arm64-v8a；请运行 " +
                        "scripts/fetch_azahar_ishiruka_libs.sh 从 Ishiiruka APK 提取核心库。"
                )
            }
        } catch (e: Throwable) {
            Availability(false, "Ishiiruka 核心加载异常: ${e.message}")
        }
        probedAvailability = result
        return result
    }

    override fun isCoreAvailable(): Boolean = probeAvailability().available

    override fun ensureLoaded(): Boolean = probeAvailability().available

    // ------------------------------------------------------------------
    // 目录 / 配置
    // ------------------------------------------------------------------

    private fun userDir(): String {
        val ctx = appContext
            ?: throw IllegalStateException("IshirukaEngine: app context not initialised")
        return File(ctx.filesDir, "ishiiruka/user").apply { mkdirs() }.absolutePath
    }

    /**
     * ★ 设置不生效修复 —— 直接写 INI 文件到 `<userDir>/Config/`。
     *
     * 旧实现全部经 NativeLibrary.SetConfig(file, section, key, value) 下发，但
     * 该 JNI 的写盘路径由 so 内部 GetUserPath(ConfigDir) 决定 —— 在本预编译
     * libishiiruka.so 里与核心读取的 `<userDir>/Config/` 不一致（SetConfig 的
     * 异常又被调用侧 catch 吞掉），导致"分辨率倍数等所有设置全都不生效"。
     *
     * Dolphin 核心（4.x/5.x 全系）启动时固定从 `<SetUserDirectory>/Config/`
     * 读 Dolphin.ini / GFX.ini，从 `<userDir>/Config/` 读 GCPadNew.ini /
     * WiimoteNew.ini —— 该位置只由 SetUserDirectory 决定，与 SetConfig 无关，
     * 因此直接以普通文件写入 100% 覆盖核心的读取路径，不存在静默失败。
     *
     * [updates] 结构：section → (key → value)。与已有文件内容**合并**（保留
     * 未涉及的段/键，如 GameSettings、Display 等），不会整文件覆盖。
     */
    private fun writeIniMerged(file: File, updates: Map<String, Map<String, String>>) {
        if (updates.isEmpty()) return
        val sections = LinkedHashMap<String, LinkedHashMap<String, String>>()
        if (file.exists()) {
            var current: String? = null
            try {
                file.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEachLine
                    if (line.startsWith("[") && line.endsWith("]")) {
                        current = line.substring(1, line.length - 1).trim()
                        sections.getOrPut(current!!) { LinkedHashMap() }
                    } else if (current != null) {
                        val eq = line.indexOf('=')
                        if (eq > 0) {
                            // current 是在闭包中被修改的局部 var，Kotlin 智能转换
                            // 无法覆盖，故此处需显式 !!。
                            val sec = sections.getOrPut(current!!) { LinkedHashMap() }
                            sec[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
                        }
                    }
                }
            } catch (_: Throwable) { }
        }
        updates.forEach { (sec, kv) ->
            val target = sections.getOrPut(sec) { LinkedHashMap() }
            kv.forEach { (k, v) -> target[k] = v }
        }
        val sb = StringBuilder()
        sections.forEach { (sec, kv) ->
            sb.append('[').append(sec).append("]\n")
            kv.forEach { (k, v) -> sb.append(k).append(" = ").append(v).append('\n') }
            sb.append('\n')
        }
        try {
            file.parentFile?.mkdirs()
            file.writeText(sb.toString())
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaEngine", "writeIniMerged failed: ${file.name}", t)
        }
    }

    /** `<userDir>/Config/` 目录（Dolphin 全部运行时 INI 的规范位置）。 */
    private fun configDir(): File = File(userDir(), "Config").apply { mkdirs() }

    /**
     * ★ Wii 主机语言 SYSCONF 补丁（IPL.LNG）。
     *
     * Dolphin 5.0 系的 Wii 系统语言存在 Wii 自己的 SYSCONF 文件里，不在
     * 任何 INI。核心读写的规范位置 = <userDir>/Wii/shared2/SYSCONF
     * （首次启动 Wii 内容时由核心生成默认值）；Sys 目录下的副本仅作
     * 静态兜底。SYSCONF 条目布局：[type:1][nameLen:2 BE][name][data]，
     *   IPL.LNG 是 BIGBYTE 型（type 0x01）—— 检索字节序列
     *   01 00 07 'I''P''L''.''L''N''G' 后紧跟的 1 字节即语言值。
     *
     * 取值（Wii IPL/LNG）：0=日语 1=英语 2=德语 3=法语 4=西班牙语 5=意大利语 6=荷兰语。
     * 找不到文件/条目时静默跳过（首次 Wii 启动前 SYSCONF 尚未生成，
     * [pendingWiiLanguage] 会在下次 loadRom 前重放补丁）。
     */
    private fun patchSysconfLanguage(language: Int) {
        val ctx = appContext
        val userSysconf = File(File(File(userDir(), "Wii"), "shared2"), "SYSCONF")
        val sysRoot = ctx?.let { File(File(File(it.filesDir, "ishiiruka"), "sys"), "Wii") }
        val sysSysconf = sysRoot?.let { File(File(it, "shared2"), "SYSCONF") }
        var patched = false
        if (userSysconf.isFile) {
            patchSysconfFile(userSysconf, language)
            patched = true
        }
        if (sysSysconf != null && sysSysconf.isFile) {
            patchSysconfFile(sysSysconf, language)
            patched = true
        }
        if (!patched) {
            android.util.Log.w("IshirukaEngine", "SYSCONF not found for language patch (will retry on next loadRom)")
        }
    }

    /** 用户请求的 Wii 主机语言（-1 = 未设置；loadRom 前重放补丁）。 */
    @Volatile private var pendingWiiLanguage: Int = -1

    /** ★ 通用 SYSCONF BIGBYTE 条目补丁（IPL.LNG / IPL.AR 等）。
     *   复用 [patchSysconfLanguage] 的字节布局：
     *   [type=0x01][nameLen:2 BE][name][data:1]。找不到条目时静默跳过。 */
    private fun patchSysconfBigByte(sysconf: File, name: String, value: Int) {
        val data = sysconf.readBytes()
        if (data.size < 0x40 || data[0].toInt() != 'S'.code || data[1].toInt() != 'C'.code) return
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val prefix = ByteArray(3 + nameBytes.size)
        prefix[0] = 0x01
        prefix[1] = ((nameBytes.size shr 8) and 0xFF).toByte()
        prefix[2] = (nameBytes.size and 0xFF).toByte()
        nameBytes.copyInto(prefix, 3)
        outer@ for (i in 0..data.size - prefix.size - 1) {
            for (j in prefix.indices) {
                if (data[i + j] != prefix[j]) continue@outer
            }
            val valueOffset = i + prefix.size
            if (data[valueOffset].toInt() == value) return
            data[valueOffset] = value.toByte()
            val tmp = File(sysconf.parentFile, "SYSCONF.tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(sysconf)) {
                tmp.copyTo(sysconf, overwrite = true)
                tmp.delete()
            }
            return
        }
        android.util.Log.w("IshirukaEngine", "SYSCONF entry not found: $name")
    }

    /**
     * ★★ Wii 宽屏信号（SYSCONF IPL.AR = 1/0）★★ —— “宽屏修正抽搐”根治。
     *
     * 抽搐根因：旧实现只开 GFX 的 wideScreenHack（投影矩阵 patch），它
     * 按帧拦截游戏矩阵调用 —— 游戏切换场景/内置 16:9 模式时 patch 命中
     * 与否来回翻转 → 画面一会儿 16:9 修正一会儿 4:3。
     *
     * 修复：Wii 游戏改走【主机级 16:9 信号】（SYSCONF IPL.AR=1），带
     * 原生 16:9 的游戏（绝大多数 Wii 游戏）读到后自行切到宽屏渲染，
     * 稳定无抽搐；GC 游戏没有 SYSCONF，才回退到 wideScreenHack。
     * 两路互斥，杜绝双重修正。
     */
    private fun patchSysconfWidescreen(widescreen: Boolean) {
        val value = if (widescreen) 1 else 0
        val userSysconf = File(File(File(userDir(), "Wii"), "shared2"), "SYSCONF")
        val sysRoot = appContext?.let { File(File(File(it.filesDir, "ishiiruka"), "sys"), "Wii") }
        val sysSysconf = sysRoot?.let { File(File(it, "shared2"), "SYSCONF") }
        var patched = false
        if (userSysconf.isFile) {
            try { patchSysconfBigByte(userSysconf, "IPL.AR", value); patched = true } catch (t: Throwable) {
                android.util.Log.w("IshirukaEngine", "SYSCONF widescreen patch (user) failed", t)
            }
        }
        if (sysSysconf != null && sysSysconf.isFile) {
            try { patchSysconfBigByte(sysSysconf, "IPL.AR", value); patched = true } catch (t: Throwable) {
                android.util.Log.w("IshirukaEngine", "SYSCONF widescreen patch (sys) failed", t)
            }
        }
        // ★★ 同步原生 JNI 通道（getSysconfSettings/setSysconfSettings
        //   布局：[0]屏保 [1]语言 [2]宽屏 [3]逐行 [4]PAL60 [5]感应条位置
        //   [6]感应条灵敏度 [7]扬声器音量 [8]马达）—— 双保险。★★
        //
        //   ★★ 本轮根治：getSysconfSettings 在 SConfig 单例为 null 时解引用
        //   null+0x18 → SIGSEGV（fault addr 0x18）。SConfig 仅在核心 Run 起来
        //   后才初始化。但本方法被 setCoreOption("GFX.ini/Settings/wideScreenHack")
        //   调用 —— 而 setCoreOption 又被 EmulatorScreen 的 LaunchedEffect
        //   在 padLayout 变化时（含游戏加载阶段、用户改设置面板）触发，
        //   这些时机核心尚未启动 → SIGSEGV 闪退（崩点
        //   Java_org_dolphinemu_dolphinemu_NativeLibrary_getSysconfSettings，
        //   fault addr 0x18，主线程协程）。
        //   修复：仅当核心真实运行（isLoaded && emuThread 真在跑）时才走 JNI
        //   推送；否则只走文件补丁（patchSysconfBigByte，纯 I/O 无 JNI），
        //   下次启动游戏时核心会读到被改过的 SYSCONF 文件 —— 与"下次启动
        //   生效"的语义一致，永不闪退。try/catch 对原生 SIGSEGV 无效，不能
        //   依赖异常兜底。
        if (isLoaded && emuThread?.isAlive == true) {
            try {
                val cur = org.dolphinemu.dolphinemu.NativeLibrary.getSysconfSettings()
                if (cur != null && cur.size >= 9) {
                    cur[2] = if (widescreen) 1 else 0
                    org.dolphinemu.dolphinemu.NativeLibrary.setSysconfSettings(cur)
                }
            } catch (_: Throwable) {}
        }
        if (!patched) {
            android.util.Log.w("IshirukaEngine", "SYSCONF not found for widescreen patch (will apply on next boot)")
        }
    }

    private fun patchSysconfFile(sysconf: File, language: Int) {
        val data = sysconf.readBytes()
        // SYSCONF 头验证（"SCv0"）
        if (data.size < 0x40 || data[0].toInt() != 'S'.code || data[1].toInt() != 'C'.code) return
        // IPL.LNG（BIGBYTE，nameLen=7）条目前缀：01 00 07 49 50 4C 2E 4C 4E 47
        val pattern = byteArrayOf(0x01, 0x00, 0x07, 0x49, 0x50, 0x4C, 0x2E, 0x4C, 0x4E, 0x47)
        outer@ for (i in 0..data.size - pattern.size - 1) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            val valueOffset = i + pattern.size
            if (data[valueOffset].toInt() == language) return
            data[valueOffset] = language.toByte()
            val tmp = File(sysconf.parentFile, "SYSCONF.tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(sysconf)) {
                tmp.copyTo(sysconf, overwrite = true)
                tmp.delete()
            }
            android.util.Log.i("IshirukaEngine", "SYSCONF IPL.LNG -> $language (${sysconf.absolutePath})")
            return
        }
        android.util.Log.w("IshirukaEngine", "IPL.LNG entry not found in SYSCONF")
    }

    private fun filesRoot(): String {
        val ctx = appContext
            ?: throw IllegalStateException("IshirukaEngine: app context not initialised")
        return ctx.filesDir.absolutePath
    }

    /**
     * 编程式写入 GCPadNew.ini / WiimoteNew.ini —— 与上游 assets 版本等价
     * （把 ButtonManager "Touchscreen" 设备的全部按钮 / 方向轴绑进 GC 手柄与
     * Wiimote 的标准控制组）。
     */
    private fun writeControllerInis(onlyExtension: Boolean = false) {
        // ★ 与 writeCoreIni 同款双通道：SetConfig + 直写 <userDir>/Config/。
        //   （GCPadNew.ini / WiimoteNew.ini 同样位于 Config 目录 —— 旧实现只经
        //   SetConfig 下发，写盘位置不可靠；直写保证 Touchscreen 绑定必然生效。）
        //   [onlyExtension] 运行中热切换扩展手柄时仅重写 WiimoteNew.ini。
        //
        // ★★ 虚拟按键全部失灵根治修复（绑定表达式格式对齐参考 APK）★★
        //   参考版 Ishiruka APK 的 assets/GCPadNew.ini / WiimoteNew.ini 使用：
        //     Device = Android/N/Touchscreen   （段内默认设备 = 完整限定名）
        //     Buttons/A = `Button 0`           （反引号裸表达式，作用于默认设备）
        //   旧实现写成 `Device 'Touchscreen'-Button 0`（内联设备限定符），而
        //   Dolphin 表达式解析器把 'Touchscreen' 解析为 source 组件（"Android/N/
        //   Touchscreen" 的 source 是 "Android"），永远匹配不到任何设备 →
        //   全部虚拟按键绑定永不激活（按键全死的根因，且 SetConfig 不报错）。
        //   现在逐字采用参考 APK 的绑定格式。
        val gcUpdates = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val wiiUpdates = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val gc = "GCPadNew.ini"
        val gcSection = "GCPad1"
        fun gcSet(key: String, value: String) {
            try { NativeLibrary.SetConfig(gc, gcSection, key, value) } catch (_: Throwable) {}
            gcUpdates.getOrPut(gcSection) { LinkedHashMap() }[key] = value
        }
        gcSet("Device", "Android/0/Touchscreen")
        gcSet("Buttons/A", "`Button 0`")
        gcSet("Buttons/B", "`Button 1`")
        gcSet("Buttons/Start", "`Button 2`")
        gcSet("Buttons/X", "`Button 3`")
        gcSet("Buttons/Y", "`Button 4`")
        gcSet("Buttons/Z", "`Button 5`")
        gcSet("D-Pad/Up", "`Button 6`")
        gcSet("D-Pad/Down", "`Button 7`")
        gcSet("D-Pad/Left", "`Button 8`")
        gcSet("D-Pad/Right", "`Button 9`")
        gcSet("Main Stick/Up", "`Axis 11`")
        gcSet("Main Stick/Down", "`Axis 12`")
        gcSet("Main Stick/Left", "`Axis 13`")
        gcSet("Main Stick/Right", "`Axis 14`")
        gcSet("Main Stick/Radius", "100,000000")
        gcSet("C-Stick/Up", "`Axis 16`")
        gcSet("C-Stick/Down", "`Axis 17`")
        gcSet("C-Stick/Left", "`Axis 18`")
        gcSet("C-Stick/Right", "`Axis 19`")
        gcSet("C-Stick/Radius", "100,000000")
        gcSet("Triggers/L", "`Axis 20`")
        gcSet("Triggers/R", "`Axis 21`")
        gcSet("Triggers/L-Analog", "`Axis 20` | `Axis 22`")
        gcSet("Triggers/R-Analog", "`Axis 21` | `Axis 23`")
        gcSet("Triggers/Threshold", "90,000000")
        gcSet("Rumble/Motor", "`Rumble 700`")

        val wii = "WiimoteNew.ini"
        fun wiiSet(section: String, key: String, value: String) {
            try { NativeLibrary.SetConfig(wii, section, key, value) } catch (_: Throwable) {}
            wiiUpdates.getOrPut(section) { LinkedHashMap() }[key] = value
        }

        if (onlyExtension) {
            // 热切换扩展手柄：只重写 P1 的 Extension 键（其余绑定不变），
            // 随后由调用方触发 ReloadWiimoteConfig。
            wiiSet("Wiimote1", "Extension", when (effectiveWiiExtension()) {
                "classic" -> "Classic"
                "none" -> "None"
                else -> "Nunchuk"
            })
            writeIniMerged(File(configDir(), "WiimoteNew.ini"), wiiUpdates)
            return
        }

        for (slot in 1..4) {
            val sec = "Wiimote$slot"
            wiiSet(sec, "Source", if (slot == 1) "1" else "0") // 仅 P1 启用模拟 Wiimote
            // ★ Device 通道：与参考 APK 一致 —— Wiimote1..4 = Android/4..7/Touchscreen
            //   （GC 手柄占 Android/0..3）。
            wiiSet(sec, "Device", "Android/${3 + slot}/Touchscreen")
            // ★ 绑定格式对齐参考 APK（反引号裸表达式，见 GCPad 部分注释）。
            wiiSet(sec, "Buttons/A", "`Button 100`")
            wiiSet(sec, "Buttons/B", "`Button 101`")
            wiiSet(sec, "Buttons/1", "`Button 105`")
            wiiSet(sec, "Buttons/2", "`Button 106`")
            wiiSet(sec, "Buttons/-", "`Button 102`")
            wiiSet(sec, "Buttons/+", "`Button 103`")
            wiiSet(sec, "Buttons/Home", "`Button 104`")
            wiiSet(sec, "D-Pad/Up", "`Button 107`")
            wiiSet(sec, "D-Pad/Down", "`Button 108`")
            wiiSet(sec, "D-Pad/Left", "`Button 109`")
            wiiSet(sec, "D-Pad/Right", "`Button 110`")
            wiiSet(sec, "IR/Up", "`Axis 112`")
            wiiSet(sec, "IR/Down", "`Axis 113`")
            wiiSet(sec, "IR/Left", "`Axis 114`")
            wiiSet(sec, "IR/Right", "`Axis 115`")
            wiiSet(sec, "IR/Forward", "`Axis 116`")
            wiiSet(sec, "IR/Backward", "`Axis 117`")
            wiiSet(sec, "IR/Hide", "`Button 118`")
            wiiSet(sec, "IR/Total Pitch", "15")
            wiiSet(sec, "IR/Total Yaw", "15")
            wiiSet(sec, "IR/Vertical Offset", "10")
            wiiSet(sec, "Shake/X", "`Button 132`")
            wiiSet(sec, "Shake/Y", "`Button 133`")
            wiiSet(sec, "Shake/Z", "`Button 134`")
            // ★★ Wii 倾斜/晃动绑定根治修复 ★★
            //   根因：旧实现把 Tilt 四轴写成半轴后缀表达式（"Axis 127-" 等），
            //   而本核心（Ishiiruka 5.0 系）的 WiimoteNew.ini 反引号表达式解析器
            //   只认【全轴表达式】（"Axis N"，带符号推值参与方向合成）——
            //   参考 APK（Ishiruka_01.APK 的 assets/WiimoteNew.ini，即本
            //   libishiiruka.so 的配套基准）的 40+ 个轴绑定全部无 +/- 后缀。
            //   半轴写法导致 Tilt 四绑定悬空 → 按键倾斜与手机体感
            //   （都汇入 pushWiiTilt 轴事件）全部无效，而 IR/摇杆/普通按键
            //   （全轴格式）全部正常 —— 与用户反馈完全吻合。
            //   修复：对齐参考 APK 逐字使用全轴格式，并补齐 Modifier 需以下、
            //   Swing（晃动/挥动）六轴与 Nunchuk 的 Swing/Tilt 绑定。
            //   轴号 = NativeLibrary.ButtonType：Tilt F/B/L/R=127/128/129/130，
            //   Swing F/B/L/R=124/125/122/123（Up/Down=120/121）。
            wiiSet(sec, "Tilt/Forward", "`Axis 127`")
            wiiSet(sec, "Tilt/Backward", "`Axis 128`")
            wiiSet(sec, "Tilt/Left", "`Axis 129`")
            wiiSet(sec, "Tilt/Right", "`Axis 130`")
            wiiSet(sec, "Tilt/Modifier", "`Button 131`")
            wiiSet(sec, "Tilt/Modifier/Range", "50,000000")
            // ★★ 前后晃动（Swing，用户需求原话“前后晃动(不是倾斜)”）★★：
            //   与 Tilt 独立的六轴挥动/晃动输入组，参考 APK 同款全轴绑定。
            //   L3/R3（前晃/后晃）与手机体感的前后方向会同时驱动
            //   Tilt F/B 与 Swing F/B（兼容只读 Tilt 或只读 Swing 的游戏）。
            wiiSet(sec, "Swing/Up", "`Axis 120`")
            wiiSet(sec, "Swing/Down", "`Axis 121`")
            wiiSet(sec, "Swing/Left", "`Axis 122`")
            wiiSet(sec, "Swing/Right", "`Axis 123`")
            wiiSet(sec, "Swing/Forward", "`Axis 124`")
            wiiSet(sec, "Swing/Backward", "`Axis 125`")
            // 扩展手柄由控制模式决定（P1），且 Nunchuk/Classic 的绑定键名带
            // "Nunchuk/"、"Classic/" 前缀，写在 [WiimoteN] 主段内（参考 APK 结构）。
            // ★ 旧实现写成独立的 [Nunchuk] / [Classic] 段 —— 核心在 [WiimoteN] 段
            //   找不到 Nunchuk/... 键，扩展输入全部无效。
            val ext = when {
                slot != 1 -> "None"
                effectiveWiiExtension() == "classic" -> "Classic"
                effectiveWiiExtension() == "none" -> "None"
                else -> "Nunchuk"
            }
            wiiSet(sec, "Extension", ext)
            if (slot == 1) {
                wiiSet(sec, "Nunchuk/Buttons/C", "`Button 200`")
                wiiSet(sec, "Nunchuk/Buttons/Z", "`Button 201`")
                // ★★ Nunchuk Swing/Tilt 绑定补齐（参考 APK 同款全轴格式）★★：
                //   双节棍模式下带体感的游戏读 Nunchuk 的 Swing/Tilt 组。
                wiiSet(sec, "Nunchuk/Swing/Up", "`Axis 208`")
                wiiSet(sec, "Nunchuk/Swing/Down", "`Axis 209`")
                wiiSet(sec, "Nunchuk/Swing/Left", "`Axis 210`")
                wiiSet(sec, "Nunchuk/Swing/Right", "`Axis 211`")
                wiiSet(sec, "Nunchuk/Swing/Forward", "`Axis 212`")
                wiiSet(sec, "Nunchuk/Swing/Backward", "`Axis 213`")
                wiiSet(sec, "Nunchuk/Tilt/Forward", "`Axis 215`")
                wiiSet(sec, "Nunchuk/Tilt/Backward", "`Axis 216`")
                wiiSet(sec, "Nunchuk/Tilt/Left", "`Axis 217`")
                wiiSet(sec, "Nunchuk/Tilt/Right", "`Axis 218`")
                wiiSet(sec, "Nunchuk/Tilt/Modifier", "`Button 219`")
                wiiSet(sec, "Nunchuk/Tilt/Modifier/Range", "50,000000")
                wiiSet(sec, "Nunchuk/Stick/Up", "`Axis 203`")
                wiiSet(sec, "Nunchuk/Stick/Down", "`Axis 204`")
                wiiSet(sec, "Nunchuk/Stick/Left", "`Axis 205`")
                wiiSet(sec, "Nunchuk/Stick/Right", "`Axis 206`")
                wiiSet(sec, "Nunchuk/Stick/Radius", "100,000000")
                wiiSet(sec, "Nunchuk/Shake/X", "`Button 220`")
                wiiSet(sec, "Nunchuk/Shake/Y", "`Button 221`")
                wiiSet(sec, "Nunchuk/Shake/Z", "`Button 222`")
                wiiSet(sec, "Classic/Buttons/A", "`Button 300`")
                wiiSet(sec, "Classic/Buttons/B", "`Button 301`")
                wiiSet(sec, "Classic/Buttons/X", "`Button 302`")
                wiiSet(sec, "Classic/Buttons/Y", "`Button 303`")
                // ★ Classic ZL/ZR 是按钮（Button 307/308），与参考 APK 一致。
                wiiSet(sec, "Classic/Buttons/ZL", "`Button 307`")
                wiiSet(sec, "Classic/Buttons/ZR", "`Button 308`")
                wiiSet(sec, "Classic/Buttons/-", "`Button 304`")
                wiiSet(sec, "Classic/Buttons/+", "`Button 305`")
                wiiSet(sec, "Classic/Buttons/Home", "`Button 306`")
                wiiSet(sec, "Classic/D-Pad/Up", "`Button 309`")
                wiiSet(sec, "Classic/D-Pad/Down", "`Button 310`")
                wiiSet(sec, "Classic/D-Pad/Left", "`Button 311`")
                wiiSet(sec, "Classic/D-Pad/Right", "`Button 312`")
                wiiSet(sec, "Classic/Left Stick/Up", "`Axis 314`")
                wiiSet(sec, "Classic/Left Stick/Down", "`Axis 315`")
                wiiSet(sec, "Classic/Left Stick/Left", "`Axis 316`")
                wiiSet(sec, "Classic/Left Stick/Right", "`Axis 317`")
                wiiSet(sec, "Classic/Left Stick/Radius", "100,000000")
                wiiSet(sec, "Classic/Right Stick/Up", "`Axis 319`")
                wiiSet(sec, "Classic/Right Stick/Down", "`Axis 320`")
                wiiSet(sec, "Classic/Right Stick/Left", "`Axis 321`")
                wiiSet(sec, "Classic/Right Stick/Right", "`Axis 322`")
                wiiSet(sec, "Classic/Right Stick/Radius", "100,000000")
                wiiSet(sec, "Classic/Triggers/L", "`Axis 323`")
                wiiSet(sec, "Classic/Triggers/R", "`Axis 324`")
                wiiSet(sec, "Classic/Triggers/Threshold", "90,000000")
            }
            if (slot == 1) wiiSet(sec, "Rumble/Motor", "`Rumble 700`")
        }
        // 直写规范位置（与 SetConfig 通道等价内容，保证绑定必然落盘）
        writeIniMerged(File(configDir(), "GCPadNew.ini"), gcUpdates)
        writeIniMerged(File(configDir(), "WiimoteNew.ini"), wiiUpdates)

        // ★ Wii 闪退/无输入修复：Wiimote 源（Source）存的是 Dolphin.ini 的
        //   [Wiimote1..4] 段（与 WiimoteNew.ini 是两个不同文件）。旧实现从未写
        //   [Wiimote1] Source = 1，若存档残留其它值，核心启动后 P1 Wiimote 处于
        //   禁用态，Wii 游戏读不到任何手柄输入。显式写死：P1 = 模拟 Wiimote，
        //   P2-P4 = 关闭，与 Touchscreen 单通道一致。
        val dolphinWiiUpdates = LinkedHashMap<String, LinkedHashMap<String, String>>()
        for (slot in 1..4) {
            try {
                NativeLibrary.SetConfig("Dolphin.ini", "Wiimote$slot", "Source",
                        if (slot == 1) "1" else "0")
            } catch (_: Throwable) {}
            dolphinWiiUpdates.getOrPut("Wiimote$slot") { LinkedHashMap() }["Source"] =
                    if (slot == 1) "1" else "0"
        }
        writeIniMerged(File(configDir(), "Dolphin.ini"), dolphinWiiUpdates)
    }

    /** GC 主手柄（P1）SIDevice = 标准手柄（6）。 */
    private fun writeCoreIni() {
        // ★ 双通道下发：SetConfig（若 so 内部路径正确可热生效）+ 直接写
        //   <userDir>/Config/Dolphin.ini、GFX.ini（本次修复的主通道，
        //   保证下次启动核心必然读到用户设置）。
        val dolphinUpdates = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val gfxUpdates = LinkedHashMap<String, LinkedHashMap<String, String>>()
        fun addTo(target: LinkedHashMap<String, LinkedHashMap<String, String>>,
                  section: String, key: String, value: String) {
            target.getOrPut(section) { LinkedHashMap() }[key] = value
        }
        coreOptions.forEach { (composite, value) ->
            val parts = composite.split('/', limit = 3)
            if (parts.size != 3) return@forEach
            val (file, section, key) = parts
            try { NativeLibrary.SetConfig(file, section, key, value) } catch (_: Throwable) {}
            when (file) {
                "Dolphin.ini" -> addTo(dolphinUpdates, section, key, value)
                "GFX.ini"     -> addTo(gfxUpdates, section, key, value)
            }
        }
        // 确保四个 GC 手柄位都是标准手柄（虚拟手柄 + 实体手柄共用 Touchscreen 通道 P1）
        try { NativeLibrary.SetConfig("Dolphin.ini", "Core", "SIDevice0", "6") } catch (_: Throwable) {}
        addTo(dolphinUpdates, "Core", "SIDevice0", "6")
        // 直写规范位置（核心启动唯一读取路径）
        writeIniMerged(File(configDir(), "Dolphin.ini"), dolphinUpdates)
        writeIniMerged(File(configDir(), "GFX.ini"), gfxUpdates)
        // ★ 性能诊断日志：把本次实际生效的关键性能项打出来 —— 用户报
        //   "性能有问题"时，logcat 过滤 IshirukaEngine 即可核对 JIT/后端/
        //   双核/内部分辨率是否按设置页选择生效（下轮精确对症）。
        try {
            val perf = dolphinUpdates["Core"]?.let { core ->
                listOf("CPUCore", "CPUThread", "Fastmem", "JITFollowBranch", "GFXBackend",
                       "VideoBackendIndex", "OverclockEnable", "Overclock", "DSPHLE")
                    .mapNotNull { k -> core[k]?.let { "$k=$it" } }
            } ?: emptyList()
            val gfx = gfxUpdates["Settings"]?.let { s ->
                listOf("InternalResolution", "MSAA", "WaitForShadersBeforeStarting")
                    .mapNotNull { k -> s[k]?.let { "$k=$it" } }
            } ?: emptyList()
            android.util.Log.i("IshirukaEngine", "perf config: ${perf + gfx}")
        } catch (_: Throwable) {}
    }

    override fun setCoreOption(key: String, value: String) {
        // key 形如 "Dolphin.ini/Core/CPUCore"；"IshirukaEngine/*" 前缀是
        // 引擎内部门面键（控制模式 / 扩展手柄 / Wii 主机语言），不落 INI 而是改
        // 运行时属性或直接补 SYSCONF。
        // ★ 修复：旧实现把控制模式/扩展手柄键存进 coreOptions 后在 writeCoreIni
        //   里因 parts.size != 3 被静默丢弃，controlMode/wiiExtension 永远停在
        //   默认值 —— UI 选"GameCube 手柄/Wii Remote/经典手柄"全部无效，
        //   auto 模式也只会给出错误的 effectiveMode（进而路由错误按键）。
        when (key) {
            "IshirukaEngine/controlMode" -> {
                controlMode = if (value in setOf("ngc", "wii", "auto")) value else "auto"
                return
            }
            "IshirukaEngine/wiiExtension" -> {
                val ext = if (value in setOf("nunchuk", "classic", "none")) value else "nunchuk"
                if (ext != wiiExtension) {
                    wiiExtension = ext
                    // 运行中切换扩展手柄：重写 WiimoteNew.ini 的 Extension 段并
                    // 让核心重载 Wiimote 配置（ReloadWiimoteConfig 为 so 导出符号），
                    // 无需重启游戏即可生效。
                    if (isRunning2()) {
                        try {
                            writeControllerInis(onlyExtension = true)
                            NativeLibrary.ReloadWiimoteConfig()
                        } catch (_: Throwable) {}
                    }
                }
                return
            }
            "IshirukaEngine/wiiOrientation" -> {
                // 仅前端虚拟按键布局使用；引擎存一份供 effective 布局查询。
                wiiOrientation = if (value == "horizontal") "horizontal" else "vertical"
                return
            }
            "IshirukaEngine/wiiLanguage" -> {
                // ★ Wii 主机语言：存于 Wii 的 SYSCONF（shared2/SYSCONF）而非
                //   Dolphin.ini —— Dolphin 5.0 系 Wii 语言仅从 SYSCONF 的
                //   IPL.LNG 条目读取。直接补丁 SYSCONF 二进制：
                //   条目布局 = [type=0x01(BIGBYTE)][00 07]["IPL.LNG"][值1字节]。
                //   下次启动游戏生效（与真机/Dolphin 行为一致），loadRom
                //   前会自动重放（见 [pendingWiiLanguage]）。
                val lang = value.toIntOrNull()?.coerceIn(0, 6) ?: 1
                pendingWiiLanguage = lang
                try { patchSysconfLanguage(lang) } catch (t: Throwable) {
                    android.util.Log.w("IshirukaEngine", "SYSCONF language patch failed", t)
                }
                return
            }
            // ★★ 宽屏修正重路由（"一会儿16:9修正一会儿4:3修正"抽搐根治）★★
            //   Wii 游戏：SYSCONF IPL.AR=1（主机级 16:9 信号）+ wideScreenHack
            //   显式 False —— 原生宽屏游戏（绝大多数 Wii 游戏）读到主机信号
            //   自行切 16:9 渲染，稳定无抽搐（矩阵 patch 按帧拦截游戏矩阵
            //   调用，场景切换时命中与否来回翻转 = 抽搐的直接来源）。
            //   GC 游戏：无 SYSCONF，保持矩阵 hack（唯一手段）。
            //   两路互斥，避免双重修正；关闭时两路全复位。
            "GFX.ini/Settings/wideScreenHack" -> {
                val enable = value.equals("true", true) || value.equals("1", true)
                val hackValue: String
                if (enable && !isGameCubeGame()) {
                    try { patchSysconfWidescreen(true) } catch (t: Throwable) {
                        android.util.Log.w("IshirukaEngine", "SYSCONF widescreen patch failed", t)
                    }
                    hackValue = "False"
                } else {
                    if (isLoaded && !isGameCubeGame()) {
                        try { patchSysconfWidescreen(false) } catch (_: Throwable) {}
                    }
                    hackValue = if (enable) "True" else "False"
                }
                coreOptions[key] = hackValue
                if (isRunning2()) {
                    try { NativeLibrary.SetConfig("GFX.ini", "Settings", "wideScreenHack", hackValue) } catch (_: Throwable) {}
                    try {
                        writeIniMerged(
                            java.io.File(configDir(), "GFX.ini"),
                            mapOf("Settings" to mapOf("wideScreenHack" to hackValue))
                        )
                    } catch (_: Throwable) {}
                }
                return
            }
        }
        val changed = coreOptions[key] != value
        coreOptions[key] = value
        val parts = key.split('/', limit = 3)
        if (parts.size == 3 && isRunning2()) {
            try { NativeLibrary.SetConfig(parts[0], parts[1], parts[2], value) } catch (_: Throwable) {}
            // ★ 分辨率倍数设置无效修复：旧实现运行中改设置只调
            //   NativeLibrary.SetConfig（路径被 so 内部 GetUserPath 决定，
            //   与核心读取的 <userDir>/Config/ 可能不一致，异常被 catch
            //   静默吞掉），INI 文件没更新。
            //   修复：同步直写 INI 文件，保证下次启动 / 下次 writeCoreIni
            //   时已是最新值。
            when (parts[0]) {
                "Dolphin.ini" -> {
                    try {
                        writeIniMerged(
                            java.io.File(configDir(), "Dolphin.ini"),
                            mapOf(parts[1] to mapOf(parts[2] to value))
                        )
                    } catch (_: Throwable) {}
                }
                "GFX.ini" -> {
                    try {
                        writeIniMerged(
                            java.io.File(configDir(), "GFX.ini"),
                            mapOf(parts[1] to mapOf(parts[2] to value))
                        )
                    } catch (_: Throwable) {}
                }
            }
        }
        // ★★ “设置应立即生效而不是退出后生效”（本轮根治）★★
        //   三档处理：
        //   1) 内存直写（即时）：音量（SConfig.m_Volume@+0x260，音频混音
        //      线程每周期现读，改了立刻变，与 m_EmulationSpeed 同款机制）；
        //   2) 软重启保进度（约 2-5s 黑屏）：启动期读取的设置（分辨率/
        //      MSAA/超频/CPU核心等）—— SaveState(slot8)→Stop→Run(读新
        //      INI)→LoadState，游戏进度无损；600ms 防抖合并设置面板的批量
        //      下发，且仅当值真的变化时触发（coreOptions 旧值比对）；
        //   3) 保持“重进游戏生效”：渲染后端切换（重建 EGL/Vulkan 上下文
        //      风险高，不自动重启）。
        if (isRunning2() && changed) when (key) {
            "Dolphin.ini/DSP/Volume" -> {
                val vol = value.toIntOrNull()?.coerceIn(0, 100) ?: return
                try { pokeSConfigInt(VOLUME_OFFSET, vol) } catch (_: Throwable) {}
            }
            "Dolphin.ini/Core/CPUCore", "Dolphin.ini/Core/CPUThread",
            "Dolphin.ini/Core/OverclockEnable", "Dolphin.ini/Core/Overclock",
            "Dolphin.ini/Core/Fastmem", "Dolphin.ini/Core/JITFollowBranch",
            "Dolphin.ini/Core/DSPHLE", "Dolphin.ini/Core/AudioLatency",
            "Dolphin.ini/Core/AudioStretch", "Dolphin.ini/Core/VSync",
            "Dolphin.ini/Core/SelectedLanguage", "Dolphin.ini/Core/OverrideGCLang",
            "GFX.ini/Settings/InternalResolution", "GFX.ini/Settings/MSAA",
            "GFX.ini/Settings/WaitForShadersBeforeStarting",
            "GFX.ini/Settings/ShowFPS",
            "GFX.ini/Enhancements/MaxAnisotropy",
            "GFX.ini/Enhancements/PostProcessingShader",
            "GFX.ini/Hacks/EFBToTextureEnable", "GFX.ini/Hacks/EFBScaledCopy",
            "GFX.ini/Hacks/EFBAccessEnable", "GFX.ini/Hacks/ImmediateXFBEnable",
            "GFX.ini/Hacks/XFBToTextureEnable" -> scheduleSoftRestart()
            // 渲染后端（GFXBackend/VideoBackendIndex）：不自动软重启 ——
            //   切后端会重建渲染窗口/上下文，自动化风险高，保持提示重进。
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
            lastErrorText = probeAvailability().reason ?: "Ishiiruka 核心不可用"
            return false
        }
        cleanupLocked()

        // ★ NGC/WII 闪退修复（根因处置）：先创建原生 GameFileCache 单例，
        //   再做一次性平台判定（IO 线程，安全窗口内）。旧实现从未 init()，
        //   单例槽为 null，overlay 线程的 addOrGet 直接空指针解引用闪退。
        //   ★ 本轮加固：判定在 SetUserDirectory / Sys 种子之后进行 ——
        //   GameFile 解析需读 GameSettings/字库等用户目录数据，早于目录
        //   初始化时 addOrGet 可能解析失败返回错误平台（NGC 游戏被判成
        //   Wii → 虚拟按键出现双节棍布局的直接原因）。
        try {
            org.dolphinemu.dolphinemu.model.GameFileCache.init()
            gameFileCacheReady = true
        } catch (t: Throwable) {
            gameFileCacheReady = false
            android.util.Log.w("IshirukaEngine", "GameFileCache.init failed", t)
        }

        try {
            NativeLibrary.NesStationHost.register(object : NativeLibrary.Host {
                override fun onPanicAlert(caption: String, text: String, yesNo: Boolean) {
                    android.util.Log.e("IshirukaEngine", "PanicAlert: $caption: $text")
                }
            })
            // 0) ★★ Sys 系统数据种子（对齐参考 APK Ishiruka_01）★★
            //    参考 APK 首次运行把 assets/Sys（GC 系统字库 / DSP ROM /
            //    GameSettings×1328 / Wii shared2 / totaldb / codehandler /
            //    着色器）整体解压进 Sys 目录。旧集成从不种 → 字库缺失导致
            //    "Wii 文本刷新特别慢/显示不全缺失"，逐游戏 quirk 全丢。
            //    必须在 SetSysDirectory 之前就位。
            try {
                appContext?.let { ctx ->
                    com.nesstation.app.core.storage.IshirukaSystemData.ensureSeeded(
                        ctx, File(File(filesRoot(), "ishiiruka"), "sys")
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.w("IshirukaEngine", "Sys seed failed", t)
            }
            // 1) 用户目录 + Sys 目录 + 标准目录结构
            NativeLibrary.SetUserDirectory(userDir())
            DirectoryInitializationService.initialize(
                File(userDir()), File(filesRoot(), "ishiiruka")
            )
            // 2) 核心设置 / 手柄绑定
            writeCoreIni()
            writeControllerInis()
            // 3) ★ Wii 主机语言补丁重放（首次设置时 SYSCONF 可能尚未生成，
            //    每次启动前重试，生成后即生效）
            if (pendingWiiLanguage >= 0) {
                try { patchSysconfLanguage(pendingWiiLanguage) } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaEngine", "dir / config init failed", t)
        }

        // 4) ★ 平台判定（目录/配置就绪后）—— 加固：检测失败时兑底逻辑
        //    与 JNI 结果不一致的场合优先更可靠的通道（见 detectIsGameCube）。
        cachedIsGameCube = detectIsGameCube(rom.absolutePath)

        this.romPath = rom.absolutePath
        this.saveDir = saveDir
        isLoaded = true

        // HUD 心跳
        running.set(true)
        heartbeatThread = thread(name = "ishiruka-hud-heartbeat", isDaemon = true) {
            try {
                while (running.get()) {
                    _frameHook?.beforeFrame(_netFrame)?.let { (p1, p2) ->
                        setPad1(p1); setPad2(p2)
                    }
                    onFrame()
                    recordHeartbeat()  // ★★ 本轮新增：实测节拍 → measuredFps
                    _netFrame++
                    try { Thread.sleep(16) } catch (_: InterruptedException) { break }
                }
            } catch (t: Throwable) {
                android.util.Log.e("IshirukaEngine", "heartbeat crashed", t)
            }
        }

        surface?.let { startEmulationLocked() }
        return true
    }

    private fun startEmulationLocked() {
        val path = romPath ?: return
        if (emuThread?.isAlive == true) return
        try { NativeLibrary.SurfaceChanged(surface!!) } catch (t: Throwable) {
            android.util.Log.w("IshirukaEngine", "SurfaceChanged failed", t)
        }
        emuThread = thread(name = "IshiirukaNative") {
            // ★ surface 竞态加固（与 AzaharEngine 同款）：Run() 原生侧构造
            //   EmuWindow 时读取全局窗口（由 SurfaceChanged 设置）；启动
            //   线程到此处之间的 SurfaceView 重建抖动可能把它清空 → 原生
            //   abort。进场后立刻用**当前** surface 重申再 Run。
            val surf = surface
            if (surf != null && surf.isValid) {
                try { NativeLibrary.SurfaceChanged(surf) } catch (_: Throwable) {}
            }
            // ★★ 快进重放（软重启/重进后内存速度会回读 INI 的 1.0）：Run()
            //    内部 SConfig 初始化完成后由后台任务把 _ffSpeed 重新写入
            //    m_EmulationSpeed（会话级状态跨 reset 保持）。每 100ms 重试
            //    至成功或 10s 超时（boot 慢的 Wii 游戏覆盖到）。
            //    ★ 本轮：重放成功后拉起保活线程（见 startFfwdKeeper）。
            if (_ffSpeed > 0) {
                val ffTarget = _ffSpeed.toFloat()
                thread(name = "IshiFfwdReapply", isDaemon = true) {
                    try {
                        for (i in 0..100) {
                            Thread.sleep(100)
                            if (try { NativeLibrary.IsRunning() } catch (_: Throwable) { false }) {
                                if (pokeEmulationSpeed(ffTarget)) {
                                    startFfwdKeeper()
                                    return@thread
                                }
                            }
                        }
                    } catch (_: InterruptedException) {}
                }
            }
            try {
                NativeLibrary.Run(path)
            } catch (t: Throwable) {
                android.util.Log.e("IshirukaEngine", "Run() crashed", t)
                lastErrorText = t.message ?: "Run() crashed"
            }
        }
    }

    override fun setSurface(surface: Surface?) {
        synchronized(lifecycleLock) {
            this.surface = surface
            if (!isLoaded) return
            if (surface != null) {
                try { NativeLibrary.SurfaceChanged(surface) } catch (_: Throwable) {}
                if (emuThread?.isAlive != true) startEmulationLocked()
            } else {
                // ★ surface 竞态防御（与 AzaharEngine 同款）：模拟线程还在
                //   Run() 的 boot 窗口内（EmuWindow 尚未构造完成）时不通知
                //   原生销毁 —— SurfaceDestroyed 会清空全局窗口，Run() 内
                //   构造渲染窗口时读到 null → 原生 abort（黑屏/闪退）。
                //   核心已跑起来（IsRunning）或无启动在途时才正常销毁。
                val nativeRunning = try { NativeLibrary.IsRunning() } catch (_: Throwable) { false }
                val booting = emuThread?.isAlive == true
                if (!booting || nativeRunning) {
                    try { NativeLibrary.SurfaceDestroyed() } catch (_: Throwable) {}
                }
            }
        }
    }

    override fun onSurfaceChanged(surface: Surface?, width: Int, height: Int) {
        if (surface == null) {
            setSurface(null)
            return
        }
        if (surface != this.surface) {
            setSurface(surface)
            return
        }
        // ★★★ NGC/WII 自定义布局缩放修复（"游戏画面无法自定义布局进行缩小
        //   放大，变成了剪切画面"根因）★★★
        //
        // 同一 Surface 实例只改尺寸（Compose 改 customRect → SurfaceView
        // 重排 → Android 复用 Surface 回调 surfaceChanged(w,h)）时，旧实现
        // 是完全 no-op —— 宽高被直接丢弃，核心的 EGL/viewport 保持旧
        // （大）尺寸，新 buffer 里只能看到左上角一块 → "剪切画面、不跟缩放"。
        //（并非前端有缩放百分比限制 —— 前端 surfaceModifier 的 custom
        //   分支本来就会按矩形改 SurfaceView 尺寸，丢尺寸的是这一层。）
        //
        // 修复：尺寸变化时重推一次 SurfaceChanged(surface) —— 反汇编实测
        // 该 JNI 对同一 surface 再次调用同样会置原生 "surface changed"
        // 原子标志，渲染线程据此重读 ANativeWindow_getWidth/getHeight
        // 并重建呈现 —— 画面真正跟随矩形缩小/放大。仅在核心已跑起来时
        // 重推，避开 boot 窗口期的 SurfaceChanged 竞态。
        if (isLoaded && width > 0 && height > 0 &&
            (width != lastSurfaceW || height != lastSurfaceH)) {
            lastSurfaceW = width
            lastSurfaceH = height
            val nativeRunning = try { NativeLibrary.IsRunning() } catch (_: Throwable) { false }
            if (nativeRunning) {
                try { NativeLibrary.SurfaceChanged(surface) } catch (t: Throwable) {
                    android.util.Log.w("IshirukaEngine", "resize re-push failed", t)
                }
            }
        }
    }

    /** 最近一次通知给核心的 Surface 尺寸（同实例尺寸变化检测用）。 */
    @Volatile private var lastSurfaceW = 0
    @Volatile private var lastSurfaceH = 0

    override fun onSurfaceDestroyed() {
        setSurface(null)
    }

    override fun setSaveName(name: String) {
        // Dolphin 存档按 GameID 组织，无需前端命名
    }

    override fun setPaused(paused: Boolean) {
        if (_paused == paused) return
        _paused = paused
        if (!isLoaded) return
        try {
            if (paused) NativeLibrary.PauseEmulation() else NativeLibrary.UnPauseEmulation()
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaEngine", "pause/resume", t)
        }
    }

    override fun reset(hard: Boolean) {
        synchronized(lifecycleLock) {
            if (!isLoaded || romPath == null) return@synchronized
            try {
                NativeLibrary.StopEmulation()
                emuThread?.join(8000)
            } catch (_: Throwable) {}
            emuThread = null
            val surf = surface ?: return@synchronized
            try { NativeLibrary.SurfaceChanged(surf) } catch (_: Throwable) {}
            startEmulationLocked()
        }
    }

    override fun unload() = synchronized(lifecycleLock) { cleanupLocked() }

    override fun shutdown() = synchronized(lifecycleLock) { cleanupLocked() }

    private fun cleanupLocked() {
        running.set(false)
        heartbeatThread?.let { t ->
            t.interrupt()
            try { t.join(500) } catch (_: InterruptedException) {}
        }
        heartbeatThread = null
        // ★ 本轮新增：清零 FPS 滑动窗口，避免下一游戏开局残留上一局的节拍样本
        synchronized(heartbeatLock) {
            heartbeatTimes.clear()
            measuredFps = 0.0
        }

        if (emuThread?.isAlive == true) {
            try {
                NativeLibrary.StopEmulation()
                emuThread?.join(8000)
            } catch (_: Throwable) {}
        }
        emuThread = null

        try { NativeLibrary.SurfaceDestroyed() } catch (_: Throwable) {}
        surface = null
        isLoaded = false
        _paused = false
        _ffSpeed = 0
        _netFrame = 0
        irLast = FloatArray(6)
        stickLast = FloatArray(8)
        lastErrorText = ""
        cachedIsGameCube = null
        lastSurfaceW = 0
        lastSurfaceH = 0
        synchronized(wiiTiltLock) {
            wiiTiltBtn.fill(0f)
            wiiTiltSensor.fill(0f)
            swingSensor.fill(0f)
            shakeSensor.fill(0f)
        }
        wiiTiltLast.fill(0f)
        wiiSwingLast.fill(0f)
        // ★ 去重缓存全部复位（“左右倾斜偶尔失灵”的叠加成因之一）：旧实现
        //   漏了 wiiSwingFullLast/wiiShakeLast/Nunchuk 镜像缓存 —— 换游戏后
        //   原生侧轴值已归零而引擎缓存还持有旧值，相同值推途被跳过 →
        //   首次按键无反应（“偶尔失灵”）。全部清零保证每次会话干净起步。
        wiiSwingFullLast.fill(0f)
        wiiShakeLast.fill(0f)
        wiiNunchukSwingLast.fill(0f)
        wiiNunchukTiltLast.fill(0f)
        wiiNunchukShakeLast.fill(0f)
    }

    // ------------------------------------------------------------------
    // 视频 / 快进 / 性能
    // ------------------------------------------------------------------

    override fun videoWidth(): Int = 1280
    override fun videoHeight(): Int = 720

    /**
     * 实测心跳节拍（滑动窗口）。系统空闲时 ~60，CPU 抢占/掉帧时下行漂移。
     * 与 PS2 不同（PS2 经 NativeApp.getFPS() 直读核心 PerformanceMetrics），
     * libishiiruka.so 未导出任何 FPS JNI，本值仅作 CPU 节拍代理 ——
     * 真·核心渲染 FPS 仍需开 GFX.ini/Settings/ShowFPS（核心内置 HUD）。
     */
    override fun realtimeFps(): Double = if (isLoaded) measuredFps else 0.0

    /** 心跳线程每拍调用，保留最近 60 个 nanoTime 样本，反推节拍。 */
    private fun recordHeartbeat() {
        val now = System.nanoTime()
        synchronized(heartbeatLock) {
            heartbeatTimes.addLast(now)
            while (heartbeatTimes.size > 60) heartbeatTimes.removeFirst()
            if (heartbeatTimes.size >= 2) {
                val first = heartbeatTimes.first()
                val spanNs = now - first
                val intervals = heartbeatTimes.size - 1   // N 个点 = N-1 个间隔
                if (spanNs > 0 && intervals > 0) {
                    val nsPerBeat = spanNs.toDouble() / intervals
                    measuredFps = 1_000_000_000.0 / nsPerBeat
                }
            }
        }
    }

    override fun setVideoFilter(filter: Int) {}
    override fun setHighQualityScaling(enabled: Boolean) {}

    /**
     * ★★ 快进（运行时变速）★★ —— v1.3 JNI 直写根治
     *
     * 通道优先级（三级回退，逐级降级保证任何环境不劣化）：
     *   1. ★ so 二进制补丁 JNI（主通道，v1.3）：scripts/ishi_speed_patch.py 把
     *      jniLibs 的 libishiiruka.so 死符号 SetScaledDensity(float)（连同
     *      GetGameAspectRatio/GetGameDisplayScale 头部，全工程零调用）改造为
     *      "解引用 SConfig 单例槽(.data 0x800E48→0x80B048) → str s0,[实例+0x208]"
     *      的直写器。Android 9+ SELinux 禁开 /proc/self/mem（用户实测 EACCES，
     *      mem-poke 全灭），而 JNI 在自身进程内合法写内存 —— 这是新系统上
     *      唯一可靠的运行时通道。生效延迟 ≤1 个节流周期（Throttle 现读）。
     *   2. mem-poke（旧系统兜底）：/proc/self/mem 直写（Android 8- 可用，
     *      带 BuildId 指纹 + 两级判空 + sanity + 回读校验）。
     *   3. INI 通道（保底）：写 Dolphin.ini EmulationSpeed，重启游戏生效。
     *
     * 快进是会话级状态：不持久化 INI，退出/重进自动恢复 1.0。
     */
    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        startFfwdKeeper()
        if (!isLoaded) return
        val target = if (speed > 0) speed.toFloat() else 1.0f
        // ★ 关快进时同时清节流禁用标志（若此前走的是三级机制回退，
        //   不清除会永久不限速）
        if (speed <= 0) pokeThrottleDisableFlag(false)
        // 1) so 补丁 JNI 直写（v1.3 主通道）
        if (pokeSpeedJni(target)) return
        // 2) mem-poke（旧系统兜底）
        if (pokeEmulationSpeed(target)) return
        // 3) 节流禁用标志直写（mem 可写但速度槽写失败时兼得快进效果）
        if (speed > 0 && pokeThrottleDisableFlag(true)) return
        // 4) 回退：写配置（下次启动核心读到 EmulationSpeed 生效）
        try {
            val value = if (speed > 0) "0" else "1"
            try { NativeLibrary.SetConfig("Dolphin.ini", "Core", "EmulationSpeed", value) } catch (_: Throwable) {}
            try {
                writeIniMerged(
                    java.io.File(configDir(), "Dolphin.ini"),
                    mapOf("Core" to mapOf("EmulationSpeed" to value))
                )
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
        if (speed > 0) {
            android.util.Log.w("IshirukaEngine",
                "fast-forward runtime channels unavailable (target=$target); INI fallback written")
        }
    }

    /** ★★ 快进保活线程：快进期间每 400ms 维持一次目标速度 —— 任何路径
     *   （设置重载/软重启/核心重读 INI）把它拉回 1.0 时立即重写。
     *   v1.3：JNI 通道无读回接口，改为无条件幂等重写（成本=一次 JNI 调用）；
     *   mem-poke 通道保留读回比对（漂移才重写）。 */
    @Volatile private var ffwdKeeperThread: Thread? = null

    private fun startFfwdKeeper() {
        if (_ffSpeed <= 0 || !isLoaded) { ffwdKeeperThread = null; return }
        if (ffwdKeeperThread?.isAlive == true) return
        val target = _ffSpeed.toFloat()
        val jniMode = ensureSpeedPatchVerified()
        ffwdKeeperThread = thread(name = "IshiFfwdKeeper", isDaemon = true) {
            try {
                while (_ffSpeed > 0 && isLoaded) {
                    Thread.sleep(400)
                    if (_ffSpeed <= 0 || !isLoaded) break
                    try {
                        if (jniMode) {
                            // JNI 通道：无条件幂等重写（无读接口）
                            pokeSpeedJni(target)
                        } else {
                            // mem 通道：读当前值，漂移才重写
                            val cur = readEmulationSpeed()
                            if (cur != null && kotlin.math.abs(cur - target) > 0.01f) {
                                pokeEmulationSpeed(target)
                            }
                        }
                    } catch (_: Throwable) {}
                }
            } catch (_: InterruptedException) {}
        }
    }

    /** 该 so 的构建指纹（nm/readelf 实测），内存补丁仅在此版本启用。 */
    private val ISHI_BUILD_ID = "9aa1cc68f0c7c917b27cbb643e859c2842c066c4"

    /** SConfig 实例槽位（.data，相对 so 加载基址；R_AARCH64_RELATIVE→0x80B048）。 */
    private val SCONFIG_SLOT_OFFSET = 0x800E48L

    /** m_EmulationSpeed 在 SConfig 实例内的偏移（LoadSettings@0xD6D80 与 Throttle@0x14E5A4 双向核实）。 */
    private val EMU_SPEED_OFFSET = 0x208L

    /** SConfig::m_Volume（int，0-100；LoadSettings“Volume”分支 @0xD77FC 输出地址实测，
     *  与 JNI 侧音量增减路径 0x2E3A30/0x2E3A70 的 [SConfig+0x260] 读写一致）。 */
    private val VOLUME_OFFSET = 0x260L

    /** 软重启专用存档槽位（避开用户常规 1-3 槽）。 */
    private val SOFT_RESTART_SLOT = 8

    @Volatile private var memPatchVerified = false
    @Volatile private var memPatchDisabled = false

    /**
     * 该核心是否支持运行中变速（UI 用：false 时加速按钮给出明确提示
     * “配置已写入，重新进入游戏后生效”，而不是无反馈的静默无效）。
     * v1.3：so 补丁 JNI 通道或 mem-poke 通道任一可用即 true。
     */
    fun supportsRuntimeFastForward(): Boolean =
        ensureSpeedPatchVerified() || ensureMemPatchVerified()

    // ------------------------------------------------------------------
    // ★★ v1.3：so 速度补丁 JNI 通道（主通道）★★
    // ------------------------------------------------------------------

    /** libishiiruka.so 速度补丁指纹（scripts/ishi_speed_patch.py 写入的
     *  0xD00E8 起 36 字节；小端指令序列，与 so 文件字节逐一对应）。 */
    private val SPEED_PATCH_BYTES = byteArrayOf(
        0x88.toByte(), 0x39, 0x00, 0x90.toByte(),   // adrp x8, 0x800000
        0x08, 0x25, 0x47, 0xF9.toByte(),            // ldr  x8, [x8, #0xe48]
        0x09, 0x01, 0x40, 0xF9.toByte(),            // ldr  x9, [x8]
        0x89.toByte(), 0x00, 0x00, 0xB4.toByte(),   // cbz  x9, +0x10
        0x20, 0x09, 0x02, 0xBD.toByte(),            // str  s0, [x9, #0x208]
        0x20, 0x00, 0x80.toByte(), 0x52,            // movz w0, #1
        0xC0.toByte(), 0x03, 0x5F, 0xD6.toByte(),   // ret
        0x00, 0x00, 0x80.toByte(), 0x52,            // fail: movz w0, #0
        0xC0.toByte(), 0x03, 0x5F, 0xD6.toByte()    // ret
    )

    /** .text 内 SetScaledDensity 的文件偏移（PT_LOAD 换算：该 so 此区间 VA==offset）。 */
    private val SPEED_PATCH_FILE_OFFSET = 0xD00E8L

    @Volatile private var speedPatchVerified = false
    @Volatile private var speedPatchChecked = false

    /**
     * 校验运行中的 libishiiruka.so 是否带速度补丁（读 so 文件指纹，结果缓存）。
     * 命中后 SetScaledDensity(float) JNI 即为 m_EmulationSpeed 直写器；
     * 未命中（上游更新了 so / 用户单独替换核心库）则本通道关闭，自动
     * 走 mem-poke / INI 回退 —— 与上游行为兼容，永不劣化。
     */
    private fun ensureSpeedPatchVerified(): Boolean {
        if (speedPatchChecked) return speedPatchVerified
        return synchronized(this) {
            if (speedPatchChecked) return@synchronized speedPatchVerified
            speedPatchChecked = true
            speedPatchVerified = try {
                var soPath: String? = null
                java.io.File("/proc/self/maps").forEachLine { line ->
                    if (soPath == null && line.contains("libishiiruka.so")) {
                        soPath = line.substringAfterLast(' ').trim()
                    }
                }
                val path = soPath
                if (path.isNullOrEmpty()) {
                    false
                } else {
                    java.io.RandomAccessFile(path, "r").use { raf ->
                        val buf = ByteArray(SPEED_PATCH_BYTES.size)
                        raf.seek(SPEED_PATCH_FILE_OFFSET)
                        raf.readFully(buf)
                        buf.contentEquals(SPEED_PATCH_BYTES)
                    }
                }
            } catch (_: Throwable) { false }
            if (!speedPatchVerified) {
                android.util.Log.w("IshirukaEngine",
                    "speed patch not detected in libishiiruka.so; JNI fast-forward disabled (mem/INI fallback)")
            }
            speedPatchVerified
        }
    }

    /**
     * JNI 直写 SConfig::m_EmulationSpeed（so 补丁通道）。
     * 返回 1（核心已启动且写入成功）才认为 true；0 = 核心未启动（实例空），
     * 交由调用方走后续通道。
     */
    private fun pokeSpeedJni(speed: Float): Boolean {
        if (!ensureSpeedPatchVerified()) return false
        return try {
            NativeLibrary.setEmulationSpeedPatched(speed) == 1
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaEngine", "pokeSpeedJni failed", t)
            false
        }
    }

    /** /proc/self/mem 读 long（小端）。地址非法返回 0。 */
    private fun readLongLe(mem: java.io.RandomAccessFile, addr: Long): Long {
        val b = ByteArray(8)
        mem.seek(addr)
        mem.readFully(b)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    /** /proc/self/mem 读 float（小端）。 */
    private fun readFloatLe(mem: java.io.RandomAccessFile, addr: Long): Float {
        val b = ByteArray(4)
        mem.seek(addr)
        mem.readFully(b)
        val bits = (b[3].toLong() and 0xFF).toInt().shl(24) or
                ((b[2].toLong() and 0xFF).toInt() shl 16) or
                ((b[1].toLong() and 0xFF).toInt() shl 8) or
                (b[0].toLong() and 0xFF).toInt()
        return Float.fromBits(bits)
    }

    /** /proc/self/mem 写 float（小端 4 字节）。 */
    private fun writeFloatLe(mem: java.io.RandomAccessFile, addr: Long, v: Float) {
        val bits = v.toRawBits()
        mem.seek(addr)
        mem.write(byteArrayOf(
            (bits and 0xFF).toByte(),
            (bits shr 8 and 0xFF).toByte(),
            (bits shr 16 and 0xFF).toByte(),
            (bits shr 24 and 0xFF).toByte()
        ))
    }

    /** 首次调用时校验 so 指纹（BuildId）并缓存；失败置 memPatchDisabled。 */
    private fun ensureMemPatchVerified(): Boolean {
        if (memPatchVerified) return true
        if (memPatchDisabled) return false
        return try {
            var soPath: String? = null
            var bias = -1L
            java.io.File("/proc/self/maps").forEachLine { line ->
                if (soPath == null && line.contains("libishiiruka.so")) {
                    bias = line.substringBefore('-').toLong(16)
                    soPath = line.substringAfterLast(' ').trim()
                }
            }
            val path = soPath
            if (path.isNullOrEmpty() || bias < 0) {
                memPatchDisabled = true
                return false
            }
            // .note.gnu.build-id：读 so 前 0x400 字节搜 20 字节指纹（GNU note 头 16 字节）
            val idBytes = ISHI_BUILD_ID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val head = ByteArray(0x400)
            java.io.FileInputStream(path).use { it.read(head) }
            outer@ for (i in 0..head.size - idBytes.size) {
                for (j in idBytes.indices) {
                    if (head[i + j] != idBytes[j]) continue@outer
                }
                memPatchVerified = true
                return true
            }
            memPatchDisabled = true
            android.util.Log.w("IshirukaEngine", "libishiiruka.so BuildId mismatch: mem-patch disabled")
            false
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaEngine", "verify libishiiruka.so failed", t)
            memPatchDisabled = true
            false
        }
    }

    /** 直写 SConfig::m_EmulationSpeed（小端 float）。成功 true。
     *  ★★ 本轮加固：写入后回读校验 —— /proc/self/mem 打开/寻址/写入在个别
     *   设备上可能静默失败，旧实现盲写后即返回 true，快进失效无任何线索。
     *   现在分阶段记录失败原因（打开失败/槽位空/sanity 拒绝/回读不一致）
     *   并在回读不一致时返回 false 以触发下一级回退。 */
    private fun pokeEmulationSpeed(speed: Float): Boolean {
        if (!ensureMemPatchVerified()) return false
        return try {
            java.io.RandomAccessFile("/proc/self/mem", "rw").use { mem ->
                // 重新解析 maps（每次调用时 so 基址可能不同，代价可忽略——低频操作）
                var bias = -1L
                java.io.File("/proc/self/maps").forEachLine { line ->
                    if (bias < 0 && line.contains("libishiiruka.so")) {
                        bias = line.substringBefore('-').toLong(16)
                    }
                }
                if (bias < 0) {
                    android.util.Log.w("IshirukaEngine", "poke: libishiiruka.so not in maps")
                    return false
                }
                val slot = readLongLe(mem, bias + SCONFIG_SLOT_OFFSET)
                if (slot == 0L) {
                    android.util.Log.w("IshirukaEngine", "poke: SConfig slot null (core not booted?)")
                    return false
                }
                val sconfig = readLongLe(mem, slot)
                if (sconfig == 0L) {
                    android.util.Log.w("IshirukaEngine", "poke: SConfig instance null")
                    return false
                }
                val cur = readFloatLe(mem, sconfig + EMU_SPEED_OFFSET)
                if (cur.isNaN() || cur < 0f || cur > 8f) {
                    android.util.Log.w("IshirukaEngine", "poke: sanity rejected cur=$cur")
                    return false   // sanity：非合理初值拒绝写
                }
                writeFloatLe(mem, sconfig + EMU_SPEED_OFFSET, speed)
                // ★ 回读校验：确认写逃真落盘
                val back = readFloatLe(mem, sconfig + EMU_SPEED_OFFSET)
                if (kotlin.math.abs(back - speed) > 0.001f) {
                    android.util.Log.w("IshirukaEngine", "poke: write verify failed (back=$back want=$speed)")
                    return false
                }
                true
            }
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaEngine", "pokeEmulationSpeed failed", t)
            false
        }
    }

    /** 只读当前 m_EmulationSpeed（保活线程漂移检测用；失败 null）。 */
    private fun readEmulationSpeed(): Float? {
        if (!ensureMemPatchVerified()) return null
        return try {
            java.io.RandomAccessFile("/proc/self/mem", "r").use { mem ->
                var bias = -1L
                java.io.File("/proc/self/maps").forEachLine { line ->
                    if (bias < 0 && line.contains("libishiiruka.so")) {
                        bias = line.substringBefore('-').toLong(16)
                    }
                }
                if (bias < 0) return null
                val slot = readLongLe(mem, bias + SCONFIG_SLOT_OFFSET)
                if (slot == 0L) return null
                val sconfig = readLongLe(mem, slot)
                if (sconfig == 0L) return null
                readFloatLe(mem, sconfig + EMU_SPEED_OFFSET)
            }
        } catch (_: Throwable) { null }
    }

    /** ★★ 二级机制：直写节流禁用标志（.bss 0x80B050，Throttle@0x14e5e4
     *   经 0xe3f1c 读此字节；置 1 = 节流完全旁路 = 不限速）。用于速度槽
     *   写入失败时的兼容快进。写入后同样回读校验。 */
    private val THROTTLE_DISABLE_FLAG_OFFSET = 0x80B050L

    private fun pokeThrottleDisableFlag(disable: Boolean): Boolean {
        if (!ensureMemPatchVerified()) return false
        return try {
            java.io.RandomAccessFile("/proc/self/mem", "rw").use { mem ->
                var bias = -1L
                java.io.File("/proc/self/maps").forEachLine { line ->
                    if (bias < 0 && line.contains("libishiiruka.so")) {
                        bias = line.substringBefore('-').toLong(16)
                    }
                }
                if (bias < 0) return false
                val addr = bias + THROTTLE_DISABLE_FLAG_OFFSET
                mem.seek(addr)
                mem.write(if (disable) byteArrayOf(1) else byteArrayOf(0))
                mem.seek(addr)
                val back = mem.read()
                if ((back == 1) != disable) {
                    android.util.Log.w("IshirukaEngine", "throttle-flag poke verify failed: $back")
                    return false
                }
                true
            }
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaEngine", "pokeThrottleDisableFlag failed", t)
            false
        }
    }

    /** 直写 SConfig 的 int 字段（音量等）。地址解析失败/值域异常返回 false。 */
    private fun pokeSConfigInt(fieldOffset: Long, value: Int): Boolean {
        if (!ensureMemPatchVerified()) return false
        if (value < 0 || value > 100000) return false
        return try {
            java.io.RandomAccessFile("/proc/self/mem", "rw").use { mem ->
                var bias = -1L
                java.io.File("/proc/self/maps").forEachLine { line ->
                    if (bias < 0 && line.contains("libishiiruka.so")) {
                        bias = line.substringBefore('-').toLong(16)
                    }
                }
                if (bias < 0) return false
                val slot = readLongLe(mem, bias + SCONFIG_SLOT_OFFSET)
                if (slot == 0L) return false
                val sconfig = readLongLe(mem, slot)
                if (sconfig == 0L) return false
                val addr = sconfig + fieldOffset
                // sanity：现值应为合理 int（音量 0-100；其它字段 < 10^6）
                val curBytes = ByteArray(4)
                mem.seek(addr); mem.readFully(curBytes)
                val cur = (curBytes[3].toInt() and 0xFF shl 24) or (curBytes[2].toInt() and 0xFF shl 16) or
                        (curBytes[1].toInt() and 0xFF shl 8) or (curBytes[0].toInt() and 0xFF)
                if (cur < 0 || cur > 100000) return false
                mem.seek(addr)
                mem.write(byteArrayOf(
                    (value and 0xFF).toByte(),
                    (value shr 8 and 0xFF).toByte(),
                    (value shr 16 and 0xFF).toByte(),
                    (value shr 24 and 0xFF).toByte()
                ))
                true
            }
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaEngine", "pokeSConfigInt failed", t)
            false
        }
    }

    // ------------------------------------------------------------------
    // 软重启（设置即时生效通道）
    // ------------------------------------------------------------------

    /** 软重启防抖 Handler（主线程 postDelayed）。 */
    private val softRestartHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val softRestartRunnable = Runnable { doSoftRestart() }

    /** 防抖 600ms：设置面板一次下发几十个键，只合并为一次软重启。 */
    private fun scheduleSoftRestart() {
        softRestartHandler.removeCallbacks(softRestartRunnable)
        softRestartHandler.postDelayed(softRestartRunnable, 600)
    }

    /**
     * ★★ 软重启（保进度应用启动期设置）★★
     * SaveState(slot8) → StopEmulation → Run（读新 INI）→ 等核心就绪 →
     * LoadState(slot8)。用户感知约 2-5 秒黑屏，游戏进度/状态无损。
     * 失败路径：存档/恢复异常时打日志保留——最坏情况游戏从头 boot
     * （与用户手动重启一致），不会崩溃。
     */
    private fun doSoftRestart() {
        if (!isLoaded || !isRunning2()) return
        thread(name = "IshiSoftRestart") {
            try {
                NativeLibrary.SaveState(SOFT_RESTART_SLOT, true)
            } catch (t: Throwable) {
                android.util.Log.w("IshirukaEngine", "soft-restart SaveState failed, skip restart", t)
                return@thread
            }
            // 停止
            synchronized(lifecycleLock) {
                if (!isLoaded) return@thread
                try {
                    NativeLibrary.StopEmulation()
                    emuThread?.join(8000)
                } catch (_: Throwable) {}
                emuThread = null
                val surf = surface ?: return@thread
                try { NativeLibrary.SurfaceChanged(surf) } catch (_: Throwable) {}
                startEmulationLocked()
            }
            // 等核心跑起来再恢复状态（boot 收尾 + LoadState 就绪）
            for (i in 0..60) {
                Thread.sleep(250)
                if (!isLoaded) return@thread
                val running = try { NativeLibrary.IsRunning() } catch (_: Throwable) { false }
                if (running) {
                    Thread.sleep(1500)   // boot 收尾（标题加载/着色器预热）
                    try { NativeLibrary.LoadState(SOFT_RESTART_SLOT) } catch (t: Throwable) {
                        android.util.Log.w("IshirukaEngine", "soft-restart LoadState failed", t)
                    }
                    // 用户改设置时处于暂停 → 重启后保持暂停（不意外抢跑）
                    if (_paused) {
                        try { NativeLibrary.PauseEmulation() } catch (_: Throwable) {}
                    }
                    return@thread
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    override fun setPad1(bits: Int) {
        if (!isLoaded) return
        val dev = NativeLibrary.TouchScreenDevice
        val state = { bit: Int ->
            if (bits and bit != 0) NativeLibrary.ButtonState.PRESSED
            else NativeLibrary.ButtonState.RELEASED
        }
        if (effectiveMode() == "ngc") {
            // GameCube：项目位 → ButtonManager GC 按钮（L/R 走扳机轴，PressEvent
            // 对 BIND_AXIS 绑定同样生效——按住=满行程）
            val pairs = listOf(
                NativeLibrary.ButtonType.BUTTON_A to BIT_A,
                NativeLibrary.ButtonType.BUTTON_B to BIT_B,
                NativeLibrary.ButtonType.BUTTON_X to BIT_X,
                NativeLibrary.ButtonType.BUTTON_Y to BIT_Y,
                NativeLibrary.ButtonType.BUTTON_Z to BIT_Z,
                NativeLibrary.ButtonType.BUTTON_START to BIT_START,
                NativeLibrary.ButtonType.BUTTON_UP to BIT_UP,
                NativeLibrary.ButtonType.BUTTON_DOWN to BIT_DOWN,
                NativeLibrary.ButtonType.BUTTON_LEFT to BIT_LEFT,
                NativeLibrary.ButtonType.BUTTON_RIGHT to BIT_RIGHT,
                NativeLibrary.ButtonType.TRIGGER_L to BIT_L,
                NativeLibrary.ButtonType.TRIGGER_R to BIT_R
            )
            for ((button, bit) in pairs) {
                NativeLibrary.onGamePadEvent(dev, button, state(bit))
            }
        } else {
            // Wii Remote：A/B/1/2/+/−/HOME + 十字键 + 摇晃 + IR 隐藏 + IR 进深
            val pairs = listOf(
                NativeLibrary.ButtonType.WIIMOTE_BUTTON_A to BIT_A,
                NativeLibrary.ButtonType.WIIMOTE_BUTTON_B to BIT_B,
                NativeLibrary.ButtonType.WIIMOTE_BUTTON_1 to BIT_WII_1,
                NativeLibrary.ButtonType.WIIMOTE_BUTTON_2 to BIT_WII_2,
                NativeLibrary.ButtonType.WIIMOTE_BUTTON_PLUS to BIT_WII_PLUS,
                NativeLibrary.ButtonType.WIIMOTE_BUTTON_MINUS to BIT_WII_MINUS,
                NativeLibrary.ButtonType.WIIMOTE_BUTTON_HOME to BIT_HOME,
                NativeLibrary.ButtonType.WIIMOTE_UP to BIT_UP,
                NativeLibrary.ButtonType.WIIMOTE_DOWN to BIT_DOWN,
                NativeLibrary.ButtonType.WIIMOTE_LEFT to BIT_LEFT,
                NativeLibrary.ButtonType.WIIMOTE_RIGHT to BIT_RIGHT,
                NativeLibrary.ButtonType.WIIMOTE_SHAKE_X to BIT_L,
                NativeLibrary.ButtonType.WIIMOTE_SHAKE_Z to BIT_R,
                NativeLibrary.ButtonType.WIIMOTE_IR_HIDE to BIT_Z
                // ★ IR Forward/Backward（BIT_IR_FAR/NEAR）从按键事件列表中
                //   移除 —— WiimoteNew.ini 把 IR/Forward、IR/Backward 绑定为
                //   "Axis 116-" / "Axis 117+"（轴绑定），按键事件无法激活轴绑定
                //   → IR+/IR- 永久失效。改由下方 setPointerDepth 走轴事件。
            )
            for ((button, bit) in pairs) {
                NativeLibrary.onGamePadEvent(dev, button, state(bit))
            }
            // ★ IR+/IR- 修复：改走轴事件（与 WiimoteNew.ini 的 Axis 绑定对齐）。
            //   ★★ 本轮根治：旧实现每帧调用两次 setPointerDepth（IR+/IR- 各
            //   一次），第二次调用永远把第一次的轴值归零 —— 按钮完全无效的
            //   直接根因。现改为单次调用同步两个按钮状态。
            setPointerDepth(
                farPressed = (bits and BIT_IR_FAR != 0),
                nearPressed = (bits and BIT_IR_NEAR != 0)
            )
            // ★★ Wii 倾斜体感（本轮新增，需求原话：“左右倾斜分别是L2R2，
            //   L2是向左倾斜，R2向右倾斜；前后可以用按钮L3，R3作为后期
            //   自定义”）★★：L2=左倾 / R2=右倾 / L3=前倾 / R3=后倾。
            //   Tilt 绑定是轴表达式，按键事件无法激活 → 统一经
            //   [pushWiiTilt] 走 onGamePadMoveEvent 轴事件。
            setWiiTiltFromBits(
                left = (bits and BIT_L2 != 0),
                right = (bits and BIT_R2 != 0),
                forward = (bits and BIT_L3 != 0),
                backward = (bits and BIT_R3 != 0)
            )
            when (effectiveWiiExtension()) {
                "classic" -> {
                    // 经典手柄（复用 Wii 布局位 + L/R/Z 扳机位）
                    val classic = listOf(
                        NativeLibrary.ButtonType.CLASSIC_BUTTON_A to BIT_A,
                        NativeLibrary.ButtonType.CLASSIC_BUTTON_B to BIT_B,
                        NativeLibrary.ButtonType.CLASSIC_BUTTON_X to BIT_WII_1,
                        NativeLibrary.ButtonType.CLASSIC_BUTTON_Y to BIT_WII_2,
                        NativeLibrary.ButtonType.CLASSIC_BUTTON_PLUS to BIT_WII_PLUS,
                        NativeLibrary.ButtonType.CLASSIC_BUTTON_MINUS to BIT_WII_MINUS,
                        NativeLibrary.ButtonType.CLASSIC_BUTTON_HOME to BIT_HOME,
                        NativeLibrary.ButtonType.CLASSIC_DPAD_UP to BIT_UP,
                        NativeLibrary.ButtonType.CLASSIC_DPAD_DOWN to BIT_DOWN,
                        NativeLibrary.ButtonType.CLASSIC_DPAD_LEFT to BIT_LEFT,
                        NativeLibrary.ButtonType.CLASSIC_DPAD_RIGHT to BIT_RIGHT,
                        NativeLibrary.ButtonType.CLASSIC_BUTTON_ZL to BIT_L,
                        NativeLibrary.ButtonType.CLASSIC_BUTTON_ZR to BIT_R
                    )
                    for ((button, bit) in classic) {
                        NativeLibrary.onGamePadEvent(dev, button, state(bit))
                    }
                }
                else -> {
                    // 双节棍：C / Z（专用位）
                    NativeLibrary.onGamePadEvent(dev, NativeLibrary.ButtonType.NUNCHUK_BUTTON_C, state(BIT_WII_C))
                    NativeLibrary.onGamePadEvent(dev, NativeLibrary.ButtonType.NUNCHUK_BUTTON_Z, state(BIT_WII_Z))
                }
            }
        }
    }

    override fun setPad2(bits: Int) {
        // Touchscreen 设备为 P1 专属（原生单通道）——P2 不支持
    }

    /**
     * 推送双摇杆 —— 换算为 ButtonManager 的四方向轴事件
     * （仅变化时推送；GC：主摇杆 STICK_MAIN_* + C 摇杆 STICK_C_*；
     * Wii-Nunchuk：NUNCHUK_STICK_*；Wii-Classic：CLASSIC_STICK_LEFT_*）。
     */
    override fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float) {
        if (!isLoaded) return
        val dev = NativeLibrary.TouchScreenDevice
        val ids: IntArray
        val rids: IntArray?
        if (effectiveMode() == "ngc") {
            ids = intArrayOf(
                NativeLibrary.ButtonType.STICK_MAIN_UP,
                NativeLibrary.ButtonType.STICK_MAIN_DOWN,
                NativeLibrary.ButtonType.STICK_MAIN_LEFT,
                NativeLibrary.ButtonType.STICK_MAIN_RIGHT
            )
            rids = intArrayOf(
                NativeLibrary.ButtonType.STICK_C_UP,
                NativeLibrary.ButtonType.STICK_C_DOWN,
                NativeLibrary.ButtonType.STICK_C_LEFT,
                NativeLibrary.ButtonType.STICK_C_RIGHT
            )
        } else if (effectiveWiiExtension() == "classic") {
            ids = intArrayOf(
                NativeLibrary.ButtonType.CLASSIC_STICK_LEFT_UP,
                NativeLibrary.ButtonType.CLASSIC_STICK_LEFT_DOWN,
                NativeLibrary.ButtonType.CLASSIC_STICK_LEFT_LEFT,
                NativeLibrary.ButtonType.CLASSIC_STICK_LEFT_RIGHT
            )
            rids = intArrayOf(
                NativeLibrary.ButtonType.CLASSIC_STICK_RIGHT_UP,
                NativeLibrary.ButtonType.CLASSIC_STICK_RIGHT_DOWN,
                NativeLibrary.ButtonType.CLASSIC_STICK_RIGHT_LEFT,
                NativeLibrary.ButtonType.CLASSIC_STICK_RIGHT_RIGHT
            )
        } else {
            ids = intArrayOf(
                NativeLibrary.ButtonType.NUNCHUK_STICK_UP,
                NativeLibrary.ButtonType.NUNCHUK_STICK_DOWN,
                NativeLibrary.ButtonType.NUNCHUK_STICK_LEFT,
                NativeLibrary.ButtonType.NUNCHUK_STICK_RIGHT
            )
            rids = null
        }
        // ★★ 摇杆"只有下右生效"根治修复（值语义对齐参考 APK 反编译实测）★★
        //   参考 Ishiruka APK overlay 控件（c;->a(FF) 模拟分支）对四方向轴事件
        //   的取值：UP/LEFT 两个 ID 发送**负值**（dy/dx 被 min(v,0) 截取负半轴），
        //   DOWN/RIGHT 两个 ID 发送正值（min(v,1) 截取正半轴）—— 原生 Touchscreen
        //   设备的 "Axis N" 输入是**原始带符号轴**，绑定表达式 `Axis 11` 按带符号
        //   值参与方向合成（向上 = 负值贡献）。旧实现把 UP/LEFT 发成正的幅值
        //   （max(0,-ly)），原生方向合成把同号值当成同向 → 上/左永远无效。
        //   修正后：UP = min(0, ly)，DOWN = max(0, ly)，LEFT = min(0, lx)，
        //   RIGHT = max(0, lx)（ly/lx 以屏幕坐标系为基准：上/左为负）。
        val lv = floatArrayOf(
            minOf(0f, ly), maxOf(0f, ly), minOf(0f, lx), maxOf(0f, lx)
        )
        for (i in 0 until 4) {
            if (lv[i] != stickLast[i]) {
                stickLast[i] = lv[i]
                try { NativeLibrary.onGamePadMoveEvent(dev, ids[i], lv[i]) } catch (_: Throwable) {}
            }
        }
        if (rids != null) {
            // 右摇杆（C-Stick / 经典手柄右摇杆）与主摇杆同语义：上/左负值、下/右正值
            val rv = floatArrayOf(
                minOf(0f, ry), maxOf(0f, ry), minOf(0f, rx), maxOf(0f, rx)
            )
            for (i in 0 until 4) {
                if (rv[i] != stickLast[4 + i]) {
                    stickLast[4 + i] = rv[i]
                    try { NativeLibrary.onGamePadMoveEvent(dev, rids[i], rv[i]) } catch (_: Throwable) {}
                }
            }
        }
    }

    /**
     * Wii IR 指针：把视图归一化坐标 (nx, ny ∈ [0,1]) 转换为 WIIMOTE_IR 六轴
     * 绝对输入（左/右/上/下决定位置，前/后由 IR+ 按钮提供）。
     *
     * ★ IR+/IR− 可见化配套：按住 IR+ 时指针活动范围扩大（能点到平时
     *   出屏的角落），按住 IR− 时收缩到屏幕中部 —— 对用户立即可见，
     *   同时兼容原生 z 深度（见 [setPointerDepth]）。
     */
    override fun setPointer(nx: Float, ny: Float, pressed: Boolean) {
        if (!isLoaded || effectiveMode() == "ngc") return
        val dev = NativeLibrary.TouchScreenDevice
        val x = nx.coerceIn(0f, 1f)
        val y = ny.coerceIn(0f, 1f)
        // ★★ IR"只能右半边"根治（逐行对齐参考 APK overlay d;->a(FF) 反编译实测）★★
        //   参考 APK 把【同一个带符号值】发给方向对的两根轴：
        //     Y 带符号值（上=-1 .. 下=+1）→ 同时发 Axis 112(Up) 与 Axis 113(Down)；
        //     X 带符号值（左=-1 .. 右=+1）→ 同时发 Axis 114(Left) 与 Axis 115(Right)。
        //   原生 Touchscreen 对每根方向轴按符号半波整流（负=上/左，正=下/右
        //   —— 与本引擎摇杆 Axis 11/12 的成功修复完全同约定）。
        //   旧实现只给单轴发 0..1 正向半轴值：触左半屏发到 Axis 114 的正值
        //   会被原生当作 Right 半轴 → 指针永远只能往右/下跑，且上/左半区
        //   发正值的轴被整流后归零 —— "红外无法往左边去只能右半边"的直接根因。
        val scale = irRangeScale
        var xs = (x * 2f - 1f) * scale          // 左=-1.. 右=+1（参考 APK: (x*i - g)/g）
        var ys = (y * 2f - 1f) * scale          // 上=-1.. 下=+1（参考 APK: (y*j - h)/h）
        if (xs > 1f) xs = 1f
        if (xs < -1f) xs = -1f
        if (ys > 1f) ys = 1f
        if (ys < -1f) ys = -1f
        // 记录最近触摸位置与状态（IR+/IR− 改变范围后按新比例重推）
        irLastNx = x
        irLastNy = y
        irPointerActive = pressed
        val values = if (!pressed) floatArrayOf(0f, 0f, 0f, 0f)
                     else floatArrayOf(ys, ys, xs, xs)   // 112&113=Y带符号；114&115=X带符号
        val ids = intArrayOf(
            NativeLibrary.ButtonType.WIIMOTE_IR_UP,
            NativeLibrary.ButtonType.WIIMOTE_IR_DOWN,
            NativeLibrary.ButtonType.WIIMOTE_IR_LEFT,
            NativeLibrary.ButtonType.WIIMOTE_IR_RIGHT
        )
        for (i in 0 until 4) {
            if (values[i] != irLast[i]) {
                irLast[i] = values[i]
                try { NativeLibrary.onGamePadMoveEvent(dev, ids[i], values[i]) } catch (_: Throwable) {}
            }
        }
        // !pressed 时复位
        if (!pressed) {
            for (i in 4 until 6) irLast[i] = 0f
        }
    }

    /**
     * IR 进深轴（IR+ / IR− 按钮）—— 每帧一次性同步两个按钮的状态。
     *
     * ★★ IR+ 失效根治（双重根因）：★★
     *   1. 【双调用互相覆灭（本 Bug 本轮定位）】旧实现每帧被 setPad1 调用
     *      【两次】（IR+ 一次、IR− 一次）：按住 IR+ 时第一次调用把轴值发
     *      出去，紧随的第二次（IR− 未按，pressed=false）把 z 归零再发一遍
     *      —— 每帧末尾轴值永远是 0，按钮完全无效。现在改为单次调用同步
     *      两个按钮状态，互不覆盖。
     *   2. 【同值双轴抵消】旧实现把同一个 z 同时发给 Axis 116(Forward) 与
     *      Axis 117(Backward) —— Cursor 的 zz = Forward − Backward 会互相
     *      抵消。现在只向目标半轴发值：
     *      IR+（推远/Forward）→ 仅 Axis 116 = −1.0；
     *      IR−（拉近/Backward）→ 仅 Axis 117 = +1.0。
     *
     * ★ 可见化配套：多数 Wii 游戏只读红外光点质心，z 深度本身几乎无视觉
     *   反馈。IR+/IR− 按住期间调整 [irRangeScale]（指针活动范围），用户能
     *   立刻看到指针移动范围变化（出屏角落可达/收窄）。
     */
    fun setPointerDepth(farPressed: Boolean, nearPressed: Boolean) {
        if (!isLoaded || effectiveMode() == "ngc") return
        val dev = NativeLibrary.TouchScreenDevice
        // IR+ → 只动 116；IR− → 只动 117；都松开 → 两轴全零（互不覆盖）
        val fwd = if (farPressed) -1f else 0f     // IR+（推远）→ Forward 半轴（负号，同摇杆上/左约定）
        val bwd = if (nearPressed) 1f else 0f      // IR−（拉近）→ Backward 半轴（正号）
        val ids = intArrayOf(
            NativeLibrary.ButtonType.WIIMOTE_IR_FORWARD,
            NativeLibrary.ButtonType.WIIMOTE_IR_BACKWARD
        )
        val vals = floatArrayOf(fwd, bwd)
        for (i in ids.indices) {
            val idx = 4 + i
            if (irLast[idx] != vals[i]) {
                irLast[idx] = vals[i]
                try { NativeLibrary.onGamePadMoveEvent(dev, ids[i], vals[i]) } catch (_: Throwable) {}
            }
        }
        // 可见化：按住 IR+ → 范围扩大（指针可及屏幕外角落）；按住 IR− → 收窄
        irRangeScale = when {
            farPressed  -> 1.6f
            nearPressed -> 0.55f
            else        -> 1.0f
        }
        // 范围变化后立刻按新比例重推当前位置（若指针正按着）
        if (irPointerActive) {
            setPointer(irLastNx, irLastNy, true)
        }
    }

    /** 当前 IR 指针范围缩放（IR+/IR− 按住期间变化，见 [setPointerDepth]）。 */
    @Volatile private var irRangeScale = 1.0f
    /** 最近一次 IR 触摸位置（范围变化时重推用）。 */
    @Volatile private var irLastNx = 0.5f
    @Volatile private var irLastNy = 0.5f
    @Volatile private var irPointerActive = false

    // ------------------------------------------------------------------
    // ★★ Wii 倾斜体感（Tilt 组四轴：前/后/左/右）★★
    // ----------------------------------------------------------------

    /** 按钮触发的倾斜/晃动（L2/R2/L3/R3 位 → 0/1）。索引：[左, 右, 前, 后]。 */
    private val wiiTiltBtn = FloatArray(4)
    /** 手机体感传感器的倾斜/晃动值（0..1）。索引：[左, 右, 前, 后]。 */
    @Volatile private var wiiTiltSensor = FloatArray(4)
    /** 最近一次推送的 Tilt 四轴合成值（去重）。 */
    private val wiiTiltLast = FloatArray(4)
    /** 最近一次推送的 Swing 前后轴合成值（去重）。 */
    private val wiiSwingLast = FloatArray(2)
    /**
     * ★★ V2 体感扩展：手机传感器全维度挥动/摇晃值。
     *
     * swingSensor[0..3] = swingUp/Down/Left/Right；swingSensor[4..5] = swingForward/Backward；
     * shakeSensor[0..2] = shakeX/Y/Z。
     *
     * 通过 [setWiiMotion]（MotionState sink）写入；[pushWiiTilt] 推送时同时
     * 把这些值送进对应的 SWING 系列与 SHAKE 系列轴（120-125, 132-134）。
     * 注：注释文本中不要写裸的星斜线组合，否则 KDoc 会把该序列当作
     * 注释终止符提前闭合，造成与此前本文件同款的 "Expecting member
     * declaration" 编译错误。
     */
    @Volatile private var swingSensor = FloatArray(6)
    @Volatile private var shakeSensor = FloatArray(3)
    /** 最近推送的 SWING U/D/L/R 与 SHAKE 三轴合成值（去重）。 */
    private val wiiSwingFullLast = FloatArray(4)   // U/D/L/R
    private val wiiShakeLast = FloatArray(3)
    /** 双节棍镜像轴去重缓存（见 pushWiiTilt 尾部的镜像注释）。 */
    private val wiiNunchukSwingLast = FloatArray(6)  // F/B/U/D/L/R
    private val wiiNunchukTiltLast = FloatArray(4)   // F/B/L/R
    private val wiiNunchukShakeLast = FloatArray(3)
    private val wiiTiltLock = Any()

    /** setPad1 → 按钮位驱动的倾斜（虚拟按键/实体手柄的 L2/R2/L3/R3）。 */
    private fun setWiiTiltFromBits(left: Boolean, right: Boolean, forward: Boolean, backward: Boolean) {
        if (!isLoaded) return
        synchronized(wiiTiltLock) {
            wiiTiltBtn[0] = if (left) 1f else 0f
            wiiTiltBtn[1] = if (right) 1f else 0f
            wiiTiltBtn[2] = if (forward) 1f else 0f
            wiiTiltBtn[3] = if (backward) 1f else 0f
        }
        pushWiiTilt()
    }

    /**
     * ★★ 手机体感 → Wii 倾斜模拟（加速度计，值域 0..1，各方向独立）。
     *   与按钮倾斜**叠加**（求和后截断 0..1）而非互相清零 —— 按住 L2
     *   的同时甩手机不会抵消。任一来源变化都会重推全部四轴。NGC 模式
     *   忽略（GC 手柄无体感）。
     */
    override fun setWiiMotionTilt(left: Float, right: Float, forward: Float, backward: Float) {
        if (!isLoaded || effectiveMode() == "ngc") return
        var changed = false
        synchronized(wiiTiltLock) {
            val v = floatArrayOf(
                left.coerceIn(0f, 1f), right.coerceIn(0f, 1f),
                forward.coerceIn(0f, 1f), backward.coerceIn(0f, 1f)
            )
            for (i in 0 until 4) {
                if (v[i] != wiiTiltSensor[i]) changed = true
                wiiTiltSensor[i] = v[i]
            }
        }
        if (changed) pushWiiTilt()
    }

    /**
     * ★★ V2 手机体感全维度输入（倾斜 + 挥动 + 摇晃）★★
     *
     * 与 [setWiiMotionTilt] 不同，本方法从 [WiiMotionSensors.MotionState] 拆出
     * 13 个独立轴后逐项写入传感器缓存，随后调 [pushWiiTilt] 一次性推送全部轴。
     * 解决旧版“手机体感毫无作用”根因：V1 只推 TILT_F/B/L/R + SWING_F/B 五根轴，
     * Wii Sports 挥拍类游戏读的是 SWING_U/D/L/R（120-123），上挥拍/侧挥拍
     * 全部读不到；马里奥赛车 wheelie 这类摇动游戏读 SHAKE_X/Y/Z（132-134），
     * 同样读不到。本方法补齐这两个轴组。
     */
    override fun setWiiMotion(state: WiiMotionSensors.MotionState) {
        if (!isLoaded || effectiveMode() == "ngc") return
        var changed = false
        synchronized(wiiTiltLock) {
            // Tilt 四轴：与旧 setWiiMotionTilt 同路径写入
            val tilt = floatArrayOf(
                state.tiltLeft.coerceIn(0f, 1f),
                state.tiltRight.coerceIn(0f, 1f),
                state.tiltForward.coerceIn(0f, 1f),
                state.tiltBackward.coerceIn(0f, 1f)
            )
            for (i in 0 until 4) {
                if (tilt[i] != wiiTiltSensor[i]) changed = true
                wiiTiltSensor[i] = tilt[i]
            }
            // ★ V2 新增：SWING 六轴 (Up/Down/Left/Right/Forward/Backward)
            val swing = floatArrayOf(
                state.swingUp, state.swingDown,
                state.swingLeft, state.swingRight,
                state.swingForward, state.swingBackward
            )
            for (i in 0 until 6) {
                if (swing[i] != swingSensor[i]) changed = true
                swingSensor[i] = swing[i]
            }
            // ★ V2 新增：SHAKE 三轴 (X/Y/Z)
            val shake = floatArrayOf(state.shakeX, state.shakeY, state.shakeZ)
            for (i in 0 until 3) {
                if (shake[i] != shakeSensor[i]) changed = true
                shakeSensor[i] = shake[i]
            }
        }
        if (changed) pushWiiTilt()
    }

    /**
     * 合成按钮 + 传感器输入并推送轴事件（仅变化时发送）。
     * ★★ 绑定格式修复后的推送策略（见 writeControllerInis 的根治注释）：★★
     *   Tilt/Swing/Shake 绑定为全轴表达式（`Axis N`，带符号推值），符号约定与
     *   引擎摇杆/IR 完全一致（已验证可用的同款约定）：
     *     - 左倾/前方向 → 负值；右倾/后方向 → 正值；
     *   轴号：Tilt 前/后/左/右 = 127/128/129/130，Swing 全向 = 120-125，
     *         Shake X/Y/Z = 132/133/134。
     *   前后方向（用户口中的“前后晃动”）**同时**驱动 Tilt F/B 与 Swing
     *   F/B —— 兼容只读 Tilt 的游戏（Monkey Ball 系）与只读 Swing 的
     *   游戏（Wii Sports 系挥动类）。
     *   ★★ V2 新增：同时推送 SWING_UP/DOWN/LEFT/RIGHT（120-123）与
     *      SHAKE_X/Y/Z（132-134）—— 旧版只推 SWING_F/B，是“手机体感毫无作用”
     *      的根因（如 Wii Sports 网球的上挥拍/侧挥拍读的是 120-123）。
     */
    private fun pushWiiTilt() {
        if (!isLoaded || effectiveMode() == "ngc") return
        val dev = NativeLibrary.TouchScreenDevice
        val merged = FloatArray(4)
        val swingMerged = FloatArray(6)   // U/D/L/R/F/B
        val shakeMerged = FloatArray(3)  // X/Y/Z
        synchronized(wiiTiltLock) {
            // ★★ 对向轴互斥（本轮根治："倾斜按钮偶尔失效"）：
            //   Tilt 左/右（前/后同理）是**两根独立半轴**（129/130），游戏把
            //   两侧读数做差合成方向 —— 传感器假性/持续的反向输出会把
            //   按钮的正向输出对消掉（按钮右倾 1.0 + 传感器左倾 1.0 =
            //   游戏读到的净方向为 0）。按钮是确定性输入：任一侧按钮激活
            //   时，对向的传感器分量直接屏蔽，保证按钮方向永远全额生效。
            //   无按钮时保持原叠加语义（按钮+传感器求和截断）。
            val btnL = wiiTiltBtn[0] > 0f; val btnR = wiiTiltBtn[1] > 0f
            val btnF = wiiTiltBtn[2] > 0f; val btnB = wiiTiltBtn[3] > 0f
            merged[0] = if (btnR) wiiTiltBtn[0]
                        else (wiiTiltBtn[0] + wiiTiltSensor[0]).coerceIn(0f, 1f)   // 左
            merged[1] = if (btnL) wiiTiltBtn[1]
                        else (wiiTiltBtn[1] + wiiTiltSensor[1]).coerceIn(0f, 1f)   // 右
            merged[2] = if (btnB) wiiTiltBtn[2]
                        else (wiiTiltBtn[2] + wiiTiltSensor[2]).coerceIn(0f, 1f)   // 前
            merged[3] = if (btnF) wiiTiltBtn[3]
                        else (wiiTiltBtn[3] + wiiTiltSensor[3]).coerceIn(0f, 1f)   // 后
            for (i in 0 until 6) swingMerged[i] = swingSensor[i]
            for (i in 0 until 3) shakeMerged[i] = shakeSensor[i]
        }
        // ---- Tilt 四轴 (127/128/129/130) ----
        val tiltIds = intArrayOf(
            NativeLibrary.ButtonType.WIIMOTE_TILT_FORWARD,   // 127
            NativeLibrary.ButtonType.WIIMOTE_TILT_BACKWARD,  // 128
            NativeLibrary.ButtonType.WIIMOTE_TILT_LEFT,      // 129
            NativeLibrary.ButtonType.WIIMOTE_TILT_RIGHT      // 130
        )
        val tiltSigned = floatArrayOf(
            -merged[2],   // 前倾/前晃 → 负半轴
            +merged[3],   // 后倾/后晃 → 正半轴
            -merged[0],   // 左倾 → 负半轴
            +merged[1]    // 右倾 → 正半轴
        )
        for (i in tiltIds.indices) {
            if (tiltSigned[i] != wiiTiltLast[i]) {
                wiiTiltLast[i] = tiltSigned[i]
                try { NativeLibrary.onGamePadMoveEvent(dev, tiltIds[i], tiltSigned[i]) } catch (_: Throwable) {}
            }
        }
        // ---- Swing 全六轴 (124/125/120/121/122/123) ----
        // 顺序: F/B/U/D/L/R
        val swingIds = intArrayOf(
            NativeLibrary.ButtonType.WIIMOTE_SWING_FORWARD,  // 124
            NativeLibrary.ButtonType.WIIMOTE_SWING_BACKWARD, // 125
            NativeLibrary.ButtonType.WIIMOTE_SWING_UP,      // 120
            NativeLibrary.ButtonType.WIIMOTE_SWING_DOWN,    // 121
            NativeLibrary.ButtonType.WIIMOTE_SWING_LEFT,    // 122
            NativeLibrary.ButtonType.WIIMOTE_SWING_RIGHT    // 123
        )
        // ★ Swing F/B：v1.3 修复 —— 旧实现只用 Tilt F/B（重力分量）驱动，
        //   swingSensor 的 swingForward/Backward（线性加速度 Z 轴推/拉，
        //   swingMerged[4]/[5]）被完全忽略 —— 推/拉手机这一最直接的
        //   "前后晃动"信号根本没进核心。现改为 Tilt（姿态）+ Swing（瞬时
        //   推力）合成：静态前倾走 Tilt 分量，快速推拉走 Swing 分量，
        //   二者叠加截断 —— 与"左右倾斜（静态）+ 左右挥动（动态）"
        //   的既有行为对齐。
        val swingVals = floatArrayOf(
            (-merged[2] - swingMerged[4]).coerceIn(-1f, 1f),  // F: 前倾 + 前推
            (+merged[3] + swingMerged[5]).coerceIn(-1f, 1f),  // B: 后仰 + 后拉
            -swingMerged[0],                     // U: 上挥 → 负半轴
            +swingMerged[1],                     // D: 下挥 → 正半轴
            -swingMerged[2],                     // L: 左挥 → 负半轴
            +swingMerged[3]                      // R: 右挥 → 正半轴
        )
        // F/B 走旧去重索引（0/1），U/D/L/R 走 wiiSwingFullLast（0..3）
        for (i in 0..1) {
            if (swingVals[i] != wiiSwingLast[i]) {
                wiiSwingLast[i] = swingVals[i]
                try { NativeLibrary.onGamePadMoveEvent(dev, swingIds[i], swingVals[i]) } catch (_: Throwable) {}
            }
        }
        for (i in 2..5) {
            val j = i - 2
            if (swingVals[i] != wiiSwingFullLast[j]) {
                wiiSwingFullLast[j] = swingVals[i]
                try { NativeLibrary.onGamePadMoveEvent(dev, swingIds[i], swingVals[i]) } catch (_: Throwable) {}
            }
        }
        // ---- Shake 三轴 (132/133/134) ----
        // Swing F/B 的甩手部分（前后晃动）会同时表现出 Shake Z 方向扰动 ——
        // 为了避免冗余推送，这里仅在 shakeMerged 非零时才推。
        val shakeIds = intArrayOf(
            NativeLibrary.ButtonType.WIIMOTE_SHAKE_X,  // 132
            NativeLibrary.ButtonType.WIIMOTE_SHAKE_Y,  // 133
            NativeLibrary.ButtonType.WIIMOTE_SHAKE_Z   // 134
        )
        for (i in 0..2) {
            if (shakeMerged[i] != wiiShakeLast[i]) {
                wiiShakeLast[i] = shakeMerged[i]
                try { NativeLibrary.onGamePadMoveEvent(dev, shakeIds[i], shakeMerged[i]) } catch (_: Throwable) {}
            }
        }

        // ★★ 双节棍轴镜像（“前后晃动无作用 / 手机体感毫无作用”的根治修复）★★
        //
        // 根因：默认扩展是双节棍（Nunchuk），但体感类游戏（Wii Sports 拳击 /
        // 马里奥赛车甩车头 / Zelda 挥剑等）读的是 WiimoteNew.ini 里
        // [Wiimote1] 段的 Nunchuk/Swing/* 与 Nunchuk/Tilt/* 输入组（轴
        // 208-213 / 215-218）—— 而旧实现只推 Wiimote 的 Tilt/Swing 轴
        // （120-130），Nunchuk 输入组永远是零 → 前后晃动按钮和手机体感
        // 在双节棍游戏里全部无作用。
        //
        // 修复：双节棍模式下把同一份体感镜像到 Nunchuk 的 Swing/Tilt 六轴 +
        // Shake 三键（220-222 是 Button 绑定，MoveEvent 值 >0.5 即按下）。
        // 经典手柄无体感，不镜像。
        if (effectiveWiiExtension() == "nunchuk") {
            // Nunchuk Swing 六轴：F/B/U/D/L/R = 212/213/208/209/210/211
            val nSwingIds = intArrayOf(
                NativeLibrary.ButtonType.NUNCHUK_SWING_FORWARD,  // 212
                NativeLibrary.ButtonType.NUNCHUK_SWING_BACKWARD, // 213
                NativeLibrary.ButtonType.NUNCHUK_SWING_UP,       // 208
                NativeLibrary.ButtonType.NUNCHUK_SWING_DOWN,     // 209
                NativeLibrary.ButtonType.NUNCHUK_SWING_LEFT,     // 210
                NativeLibrary.ButtonType.NUNCHUK_SWING_RIGHT     // 211
            )
            for (i in nSwingIds.indices) {
                if (swingVals[i] != wiiNunchukSwingLast[i]) {
                    wiiNunchukSwingLast[i] = swingVals[i]
                    try { NativeLibrary.onGamePadMoveEvent(dev, nSwingIds[i], swingVals[i]) } catch (_: Throwable) {}
                }
            }
            // Nunchuk Tilt 四轴：F/B/L/R = 215/216/217/218（与 Wiimote Tilt 同符号约定）
            val nTiltIds = intArrayOf(
                NativeLibrary.ButtonType.NUNCHUK_TILT_FORWARD,   // 215
                NativeLibrary.ButtonType.NUNCHUK_TILT_BACKWARD,  // 216
                NativeLibrary.ButtonType.NUNCHUK_TILT_LEFT,      // 217
                NativeLibrary.ButtonType.NUNCHUK_TILT_RIGHT      // 218
            )
            for (i in nTiltIds.indices) {
                if (tiltSigned[i] != wiiNunchukTiltLast[i]) {
                    wiiNunchukTiltLast[i] = tiltSigned[i]
                    try { NativeLibrary.onGamePadMoveEvent(dev, nTiltIds[i], tiltSigned[i]) } catch (_: Throwable) {}
                }
            }
            // Nunchuk Shake 三键（Button 绑定，>0.5 = 按下）
            val nShakeIds = intArrayOf(
                NativeLibrary.ButtonType.NUNCHUK_SHAKE_X,  // 220
                NativeLibrary.ButtonType.NUNCHUK_SHAKE_Y,  // 221
                NativeLibrary.ButtonType.NUNCHUK_SHAKE_Z   // 222
            )
            for (i in 0..2) {
                if (shakeMerged[i] != wiiNunchukShakeLast[i]) {
                    wiiNunchukShakeLast[i] = shakeMerged[i]
                    try { NativeLibrary.onGamePadMoveEvent(dev, nShakeIds[i], shakeMerged[i]) } catch (_: Throwable) {}
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 控制模式
    // ------------------------------------------------------------------

    /** 返回当前生效的 Wii 扩展手柄类型：classic / none / nunchuk。 */
    private fun effectiveWiiExtension(): String = wiiExtension

    override fun isGameCubeGame(): Boolean {
        // ★ 只读缓存 —— 渲染/输入线程零 JNI 零 I/O（见 cachedIsGameCube 注释）。
        cachedIsGameCube?.let { return it }
        val path = romPath ?: return false
        val result = detectIsGameCube(path)
        cachedIsGameCube = result
        return result
    }

    /**
     * 平台判定（一次性，IO 线程调用）：
     * 1. 首选 JNI GameFileCache.addOrGet —— Dolphin 卷头解析，覆盖
     *    .gcm/.iso/.rvz/.gcz/.wbfs/.ciso/.nkit/.wad 全部容器格式
     *    （GetPlatform: 0 = GameCube, 1 = Wii 光盘, 2 = WiiWare）。
     *    仅在 init() 成功后启用；任何异常都降级到 2。
     * 2. 兑底：扩展名白名单 + .gcm/.iso 的 0x18 偏移卷头魔数。
     *
     * ★ 编译修复：GameFile.platform 在不同契约版本中可能被声明为 Int 或 Long。
     *   直接 `== 0` / `== 0L` 均会在某一版本上失败；这里统一用 `.toLong() == 0L`
     *   归一化，无论上游声明成什么类型都能通过编译。
     */
    private fun detectIsGameCube(path: String): Boolean {
        // 主通道：JNI GameFileCache（精确解析 rvz/gcz/wbfs 等全部容器）。
        // 本轮已把判定移到 SetUserDirectory / Sys 种子之后，解析所需的
        // 用户目录数据就绪，JNI 可靠性大幅提升（旧时序下 addOrGet 解析
        // 失败 → NGC 游戏被判 Wii → 双节棍布局误现）。
        if (gameFileCacheReady) {
            try {
                val gf = org.dolphinemu.dolphinemu.model.GameFileCache.addOrGet(path)
                if (gf != null) return gf.platform.toLong() == 0L
            } catch (t: Throwable) {
                gameFileCacheReady = false
                android.util.Log.w("IshirukaEngine", "addOrGet failed, fall back to magic probe", t)
            }
        }
        return fallbackIsGameCube(path)
    }

    /**
     * 兑底平台判定：扩展名白名单 + 卷头魔数。
     *
     * ★★ GC 检测加固（本轮）：
     *   旧兑底把 rvz/gcz/ciso/nkit 一律判成 Wii —— 但这些是【通用压缩容器】
     *   （GC 游戏同样可以压缩成 rvz/gcz），导致 GC 游戏被误判 → 虚拟按键
     *   变成 Wii+双节棍布局。现改为：
     *     - wbfs/wad/dol/elf → Wii（这些确实是 Wii 专属）；
     *     - gcm/iso/ciso → 读卷头魔数（GC: 0xC2339F3D；CISO 首个数据块即
     *       光盘偏移 0，块大小头部可读，解出魔数后精准判定）；
     *     - rvz/gcz/nkit → JNI 无法使用时兑底 Wii（压缩卷头不可直读），
     *       但 JNI 通道（主通道）已能精确解析 —— 本轮已把判定移到目录
     *       初始化之后，JNI 可靠性大幅提升。
     *
     * ★ 编译修复：`magic` 由 shl/or 运算得出，为 Int；而 `0xC2339F3D` 超过
     *   Int.MAX_VALUE（2147483647），Kotlin 会把该字面量推断为 Long，导致
     *   `Int == Long` 编译失败。修复：两侧统一转 Long
     *   （`magic.toLong() == 0xC2339F3DL`）。
     */
    private fun fallbackIsGameCube(path: String): Boolean {
        val lower = path.lowercase()
        val ext = lower.substringAfterLast('.', "")
        // Wii 专属容器：直接判 Wii
        if (ext in setOf("wbfs", "wad", "dol", "elf")) return false
        // .gcm/.iso：读 0x18 偏移 4 字节魔数判平台 ——
        //   GameCube: 0xC2339F3D，Wii: 0x5D1C9EA3（读不到/未知 → 按兼容
        //   性取向兑底 Wii：wii 布局兼容 GC 的 SI 手柄，反之无输入）。
        if (ext in setOf("gcm", "iso")) {
            return try {
                java.io.RandomAccessFile(path, "r").use { raf ->
                    if (raf.length() < 0x20) return false
                    raf.seek(0x18)
                    val b = ByteArray(4)
                    raf.readFully(b)
                    val magic = ((b[0].toInt() and 0xFF) shl 24) or
                        ((b[1].toInt() and 0xFF) shl 16) or
                        ((b[2].toInt() and 0xFF) shl 8) or
                        (b[3].toInt() and 0xFF)
                    // 两侧统一 Long，彻底消除 Int/Long 推断歧义
                    magic.toLong() == 0xC2339F3DL
                }
            } catch (_: Throwable) { false }
        }
        // .ciso：通用压缩容器（GC/Wii 均可能）。CISO 头部：
        //   [0x00]"CISO" [0x04]block_size(BE u32，通常 0x8000) [0x08]block_map...
        //   第一个被映射的数据块即光盘偏移 0 → 块内 +0x18 处即卷头魔数。
        if (ext == "ciso") {
            return try {
                java.io.RandomAccessFile(path, "r").use { raf ->
                    if (raf.length() < 0x8018) return false
                    val hdr = ByteArray(8)
                    raf.readFully(hdr)
                    if (hdr[0] != 'C'.toByte() || hdr[1] != 'I'.toByte()) return false
                    val blockSize = ((hdr[4].toInt() and 0xFF) shl 24) or
                        ((hdr[5].toInt() and 0xFF) shl 16) or
                        ((hdr[6].toInt() and 0xFF) shl 8) or
                        (hdr[7].toInt() and 0xFF)
                    if (blockSize <= 0) return false
                    // 块映射表首字节非 0 → 第 0 块已映射，位于 0x8000 头部之后
                    val map = ByteArray(1)
                    raf.seek(0x8)
                    raf.readFully(map)
                    if (map[0].toInt() == 0) return false
                    raf.seek(0x8000 + 0x18)
                    val b = ByteArray(4)
                    raf.readFully(b)
                    val magic = ((b[0].toInt() and 0xFF) shl 24) or
                        ((b[1].toInt() and 0xFF) shl 16) or
                        ((b[2].toInt() and 0xFF) shl 8) or
                        (b[3].toInt() and 0xFF)
                    magic.toLong() == 0xC2339F3DL
                }
            } catch (_: Throwable) { false }
        }
        // rvz/gcz/nkit/未知扩展名：默认按 Wii 处理（wii 布局兼容 GC 游戏的
        // SI 手柄，反之 NGC 布局对 Wii 游戏完全无输入）—— 主判定通道
        // （JNI GameFileCache）已能精确解析这些容器。
        return false
    }

    override fun effectiveMode(): String = when (controlMode) {
        "ngc" -> "ngc"
        "wii" -> "wii"
        else -> if (isGameCubeGame()) "ngc" else "wii"
    }

    /**
     * ★★ Wii 横持十字键方向补偿开关（"横持 Wii 方向键还是原方向输出"修复）。
     *
     * 横持（NES 式）Wiimote 下，十字键随遥控器物理旋转 90°，横持游戏
     * （VC NES / ReBirth 系列等）按旋转后的坐标系读方向 —— 前端必须在
     * routePadBits 里把视觉方向旋转 90° 后再下发（详见该函数注释）。
     *
     * 生效条件：wii 模式 + 横持 + 非经典手柄。经典手柄是双手正常握持的
     * 传统手柄（十字键不旋转）；GC 模式无 Wiimote 十字键参与。
     * 旧实现该设置只改虚拟按键显隐（"核心按键绑定不变"），横持形同虚设。
     */
    fun needsHorizontalDpadRotation(): Boolean =
        effectiveMode() != "ngc" &&
            wiiOrientation == "horizontal" &&
            effectiveWiiExtension() != "classic"

    // ------------------------------------------------------------------
    // 存档 / 截图 / 其它
    // ------------------------------------------------------------------

    override fun saveState(slot: Int, dst: File): Boolean {
        if (!isLoaded) return false
        return try {
            NativeLibrary.SaveState(slot, true)
            dst.parentFile?.mkdirs()
            if (!dst.exists()) dst.createNewFile()
            true
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaEngine", "saveState($slot)", t)
            false
        }
    }

    override fun loadState(slot: Int, src: File): Boolean {
        if (!isLoaded) return false
        return try {
            NativeLibrary.LoadState(slot)
            true
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaEngine", "loadState($slot)", t)
            false
        }
    }

    /**
     * 截图：调用核心 SaveScreenShot 后轮询 User/ScreenShots 目录最新 PNG。
     * 超时（核心未落盘）返回 null。
     */
    override fun captureFrame(): FrameCapture? {
        if (!isLoaded) return null
        return try {
            val dir = File(userDir(), "ScreenShots")
            val before = newestPng(dir)
            NativeLibrary.SaveScreenShot()
            // 最多等 2s
            var newest = before
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline) {
                newest = newestPng(dir)
                if (newest != null && newest != before) break
                Thread.sleep(100)
            }
            val target = newest ?: return null
            val bmp = BitmapFactory.decodeFile(target.absolutePath) ?: return null
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            FrameCapture(pixels, w, h)
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaEngine", "captureFrame", t)
            null
        }
    }

    private fun newestPng(dir: File): File? =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.maxByOrNull { it.lastModified() }

    override fun setRegion(region: Int) {}
    override fun setSampleRate(rate: Int) {}

    override fun lastError(): String = lastErrorText

    private fun isRunning2(): Boolean = isLoaded && emuThread?.isAlive == true

    // 项目位布局（EmulatorScreen 直传：A=bit0, B=bit1, Select=2, Start=3,
    // U/D/L/R=4..7, X=8, Y=9, L=10, R=11, L2=12, R2=13, L3=14, R3=15）
    // + NGC/WII 扩展位：
    // Z=18（GC Z / Wii IR 隐藏）、HOME=19、1=20、2=21、+=22、−=23、
    // C=24、Z(双节棍)=25、IR−=26、IR+=27
    // ★ L2/R2/L3/R3 位（12-15）在 Wii 模式下驱动 Tilt 四轴（L2 左倾 /
    // R2 右倾 / L3 前倾 / R3 后倾，见 setPad1），NGC 模式忽略。
    companion object {
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
        private const val BIT_L2 = 0x1000
        private const val BIT_R2 = 0x2000
        private const val BIT_L3 = 0x4000
        private const val BIT_R3 = 0x8000
        const val BIT_Z = 0x40000
        const val BIT_HOME = 0x80000
        const val BIT_WII_1 = 0x100000
        const val BIT_WII_2 = 0x200000
        const val BIT_WII_PLUS = 0x400000
        const val BIT_WII_MINUS = 0x800000
        const val BIT_WII_C = 0x1000000
        const val BIT_WII_Z = 0x2000000
        const val BIT_IR_NEAR = 0x4000000
        const val BIT_IR_FAR = 0x8000000

        @Volatile private var instance: IshirukaEngine? = null
        fun get(): IshirukaEngine = instance ?: synchronized(this) {
            instance ?: IshirukaEngine().also { instance = it }
        }
        fun ensureLoaded() = get().ensureLoaded()
    }
}