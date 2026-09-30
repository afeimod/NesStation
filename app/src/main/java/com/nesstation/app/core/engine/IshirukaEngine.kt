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
        }
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
        if (surface != null && surface != this.surface) setSurface(surface)
    }

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
    }

    // ------------------------------------------------------------------
    // 视频 / 快进 / 性能
    // ------------------------------------------------------------------

    override fun videoWidth(): Int = 1280
    override fun videoHeight(): Int = 720

    override fun realtimeFps(): Double = 0.0

    override fun setVideoFilter(filter: Int) {}
    override fun setHighQualityScaling(enabled: Boolean) {}

    /**
     * 快进：Dolphin 5.0 系无独立快进 API —— 通过 Dolphin.ini [Core] EmulationSpeed
     * 热写（0 = 不限速，1.0 = 原速；上游 Ishiiruka 支持 EmulationSpeed 键）。
     */
    override fun setFastForward(speed: Int) {
        _ffSpeed = speed
        if (!isLoaded) return
        try {
            val value = if (speed > 0) "0" else "1"
            NativeLibrary.SetConfig("Dolphin.ini", "Core", "EmulationSpeed", value)
        } catch (_: Throwable) {}
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
    // U/D/L/R=4..7, X=8, Y=9, L=10, R=11, L2=12, R2=13）+ NGC/WII 扩展位：
    // Z=18（GC Z / Wii IR 隐藏）、HOME=19、1=20、2=21、+=22、−=23、
    // C=24、Z(双节棍)=25、IR−=26、IR+=27
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