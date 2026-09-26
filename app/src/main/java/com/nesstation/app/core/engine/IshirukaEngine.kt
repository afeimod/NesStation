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
        val gcUpdates = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val wiiUpdates = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val gc = "GCPadNew.ini"
        val gcSection = "GCPad1"
        fun gcSet(key: String, value: String) {
            try { NativeLibrary.SetConfig(gc, gcSection, key, value) } catch (_: Throwable) {}
            gcUpdates.getOrPut(gcSection) { LinkedHashMap() }[key] = value
        }
        gcSet("Device", "Android/0/Touchscreen")
        gcSet("Buttons/A", "Button 0")
        gcSet("Buttons/B", "Button 1")
        gcSet("Buttons/X", "Button 3")
        gcSet("Buttons/Y", "Button 4")
        gcSet("Buttons/Z", "Button 5")
        gcSet("Buttons/Start", "Button 2")
        gcSet("D-Pad/Up", "Button 6")
        gcSet("D-Pad/Down", "Button 7")
        gcSet("D-Pad/Left", "Button 8")
        gcSet("D-Pad/Right", "Button 9")
        gcSet("Main Stick/Up", "Axis 11-")
        gcSet("Main Stick/Down", "Axis 12+")
        gcSet("Main Stick/Left", "Axis 13-")
        gcSet("Main Stick/Right", "Axis 14+")
        gcSet("C Stick/Up", "Axis 16-")
        gcSet("C Stick/Down", "Axis 17+")
        gcSet("C Stick/Left", "Axis 18-")
        gcSet("C Stick/Right", "Axis 19+")
        gcSet("Triggers/L", "Axis 20+")
        gcSet("Triggers/R", "Axis 21+")

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
            wiiSet(sec, "Device", "Android/0/Touchscreen")
            wiiSet(sec, "Buttons/A", "Button 100")
            wiiSet(sec, "Buttons/B", "Button 101")
            wiiSet(sec, "Buttons/1", "Button 105")
            wiiSet(sec, "Buttons/2", "Button 106")
            wiiSet(sec, "Buttons/Minus", "Button 102")
            wiiSet(sec, "Buttons/Plus", "Button 103")
            wiiSet(sec, "Buttons/Home", "Button 104")
            wiiSet(sec, "D-Pad/Up", "Button 107")
            wiiSet(sec, "D-Pad/Down", "Button 108")
            wiiSet(sec, "D-Pad/Left", "Button 109")
            wiiSet(sec, "D-Pad/Right", "Button 110")
            wiiSet(sec, "IR/Up", "Axis 112-")
            wiiSet(sec, "IR/Down", "Axis 113+")
            wiiSet(sec, "IR/Left", "Axis 114-")
            wiiSet(sec, "IR/Right", "Axis 115+")
            wiiSet(sec, "IR/Forward", "Axis 116-")
            wiiSet(sec, "IR/Backward", "Axis 117+")
            wiiSet(sec, "IR/Hide", "Button 118")
            wiiSet(sec, "IR/Recenter", "")
            wiiSet(sec, "Shake/X", "Button 132")
            wiiSet(sec, "Shake/Y", "Button 133")
            wiiSet(sec, "Shake/Z", "Button 134")
            // 扩展手柄由控制模式决定（P1）
            if (slot == 1) {
                wiiSet(sec, "Extension", when (effectiveWiiExtension()) {
                    "classic" -> "Classic"
                    "none" -> "None"
                    else -> "Nunchuk"
                })
                wiiSet("Nunchuk", "Buttons/C", "Button 200")
                wiiSet("Nunchuk", "Buttons/Z", "Button 201")
                wiiSet("Nunchuk", "Stick/Up", "Axis 203-")
                wiiSet("Nunchuk", "Stick/Down", "Axis 204+")
                wiiSet("Nunchuk", "Stick/Left", "Axis 205-")
                wiiSet("Nunchuk", "Stick/Right", "Axis 206+")
                wiiSet("Nunchuk", "Shake/X", "Button 220")
                wiiSet("Nunchuk", "Shake/Y", "Button 221")
                wiiSet("Nunchuk", "Shake/Z", "Button 222")
                wiiSet("Classic", "Buttons/A", "Button 300")
                wiiSet("Classic", "Buttons/B", "Button 301")
                wiiSet("Classic", "Buttons/X", "Button 302")
                wiiSet("Classic", "Buttons/Y", "Button 303")
                wiiSet("Classic", "Buttons/ZL", "Axis 323+")
                wiiSet("Classic", "Buttons/ZR", "Axis 324+")
                wiiSet("Classic", "Buttons/Minus", "Button 304")
                wiiSet("Classic", "Buttons/Plus", "Button 305")
                wiiSet("Classic", "Buttons/Home", "Button 306")
                wiiSet("Classic", "D-Pad/Up", "Button 309")
                wiiSet("Classic", "D-Pad/Down", "Button 310")
                wiiSet("Classic", "D-Pad/Left", "Button 311")
                wiiSet("Classic", "D-Pad/Right", "Button 312")
                wiiSet("Classic", "Left Stick/Up", "Axis 314-")
                wiiSet("Classic", "Left Stick/Down", "Axis 315+")
                wiiSet("Classic", "Left Stick/Left", "Axis 316-")
                wiiSet("Classic", "Left Stick/Right", "Axis 317+")
                wiiSet("Classic", "Right Stick/Up", "Axis 319-")
                wiiSet("Classic", "Right Stick/Down", "Axis 320+")
                wiiSet("Classic", "Right Stick/Left", "Axis 321-")
                wiiSet("Classic", "Right Stick/Right", "Axis 322+")
                wiiSet("Classic", "Triggers/L", "Axis 323+")
                wiiSet("Classic", "Triggers/R", "Axis 324+")
            }
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
    }

    override fun setCoreOption(key: String, value: String) {
        // key 形如 "Dolphin.ini/Core/CPUCore"；"IshirukaEngine/*" 前缀是
        // 引擎内部门面键（控制模式 / 扩展手柄），不落 INI 而是改运行时属性。
        // ★ 修复：旧实现把这两个键存进 coreOptions 后在 writeCoreIni 里因
        //   parts.size != 3 被静默丢弃，controlMode/wiiExtension 永远停在
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
        }
        coreOptions[key] = value
        val parts = key.split('/', limit = 3)
        if (parts.size == 3 && isRunning2()) {
            try { NativeLibrary.SetConfig(parts[0], parts[1], parts[2], value) } catch (_: Throwable) {}
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

        try {
            NativeLibrary.NesStationHost.register(object : NativeLibrary.Host {
                override fun onPanicAlert(caption: String, text: String, yesNo: Boolean) {
                    android.util.Log.e("IshirukaEngine", "PanicAlert: $caption: $text")
                }
            })
            // 1) 用户目录 + Sys 目录 + 标准目录结构
            NativeLibrary.SetUserDirectory(userDir())
            DirectoryInitializationService.initialize(
                File(userDir()), File(filesRoot(), "ishiiruka")
            )
            // 2) 核心设置 / 手柄绑定
            writeCoreIni()
            writeControllerInis()
        } catch (t: Throwable) {
            android.util.Log.w("IshirukaEngine", "dir / config init failed", t)
        }

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
                try { NativeLibrary.SurfaceDestroyed() } catch (_: Throwable) {}
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
                NativeLibrary.ButtonType.WIIMOTE_IR_HIDE to BIT_Z,
                NativeLibrary.ButtonType.WIIMOTE_IR_FORWARD to BIT_IR_FAR,
                NativeLibrary.ButtonType.WIIMOTE_IR_BACKWARD to BIT_IR_NEAR
            )
            for ((button, bit) in pairs) {
                NativeLibrary.onGamePadEvent(dev, button, state(bit))
            }
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
        // up/down/left/right 值 = max(0, ±axis)
        val lv = floatArrayOf(
            maxOf(0f, -ly), maxOf(0f, ly), maxOf(0f, -lx), maxOf(0f, lx)
        )
        for (i in 0 until 4) {
            if (lv[i] != stickLast[i]) {
                stickLast[i] = lv[i]
                try { NativeLibrary.onGamePadMoveEvent(dev, ids[i], lv[i]) } catch (_: Throwable) {}
            }
        }
        if (rids != null) {
            val rv = floatArrayOf(
                maxOf(0f, -ry), maxOf(0f, ry), maxOf(0f, -rx), maxOf(0f, rx)
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
     */
    override fun setPointer(nx: Float, ny: Float, pressed: Boolean) {
        if (!isLoaded || effectiveMode() == "ngc") return
        val dev = NativeLibrary.TouchScreenDevice
        val x = nx.coerceIn(0f, 1f)
        val y = ny.coerceIn(0f, 1f)
        val values = if (!pressed) floatArrayOf(0f, 0f, 0f, 0f) else floatArrayOf(
            maxOf(0f, (0.5f - x) * 2f),   // IR_UP
            maxOf(0f, (x - 0.5f) * 2f),   // IR_DOWN
            maxOf(0f, (0.5f - y) * 2f),   // IR_LEFT
            maxOf(0f, (y - 0.5f) * 2f)    // IR_RIGHT
        )
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

    /** IR 进深轴（IR+ / IR− 按钮）。 */
    fun setPointerDepth(forward: Boolean, pressed: Boolean) {
        if (!isLoaded || effectiveMode() == "ngc") return
        val dev = NativeLibrary.TouchScreenDevice
        val id = if (forward) NativeLibrary.ButtonType.WIIMOTE_IR_FORWARD
        else NativeLibrary.ButtonType.WIIMOTE_IR_BACKWARD
        val value = if (pressed) 1f else 0f
        val idx = if (forward) 4 else 5
        if (irLast[idx] != value) {
            irLast[idx] = value
            try { NativeLibrary.onGamePadMoveEvent(dev, id, value) } catch (_: Throwable) {}
        }
    }

    // ------------------------------------------------------------------
    // 控制模式
    // ------------------------------------------------------------------

    /** 返回当前生效的 Wii 扩展手柄类型：classic / none / nunchuk。 */
    private fun effectiveWiiExtension(): String = wiiExtension

    override fun isGameCubeGame(): Boolean {
        val path = romPath ?: return false
        // ★ 精确判定（首选）：走 so 导出的 GameFileCache/GameFile JNI ——
        //   Dolphin 卷头解析，覆盖 .gcm/.iso/.rvz/.gcz/.wbfs/.ciso/.nkit/.wad
        //   全部容器格式（GetPlatform: 0 = GameCube, 1 = Wii 光盘, 2 = WiiWare）。
        //   ★ 修复：旧实现调用的 GetPlatform 是 Java 兑底恒返 0，导致
        //   auto 模式把所有游戏（包括 Wii）都判成 NGC —— 控制模式判定、
        //   IR 触摸、Wii 按键路由全部失效，是 "Wii 虚拟按键不对/闪退" 的根因之一。
        try {
            val gf = org.dolphinemu.dolphinemu.model.GameFileCache.addOrGet(path)
            if (gf != null) return gf.platform == 0
        } catch (_: Throwable) {}
        // 兑底：无法经核心判定时按扩展名 + GC 卷头魔数猜。
        return fallbackIsGameCube(path)
    }

    /** 兑底平台判定：扩展名白名单 + .gcm/.iso 的 0x18 偏移卷头魔数。 */
    private fun fallbackIsGameCube(path: String): Boolean {
        val lower = path.lowercase()
        val ext = lower.substringAfterLast('.', "")
        // Wii 专属容器：直接判 Wii
        if (ext in setOf("rvz", "wbfs", "gcz", "ciso", "nkit", "wad", "dol", "elf")) return false
        // .gcm/.iso：GC 卷头魔数 0xC2339F3D（偏移 0x18）判 GC，否则 Wii
        if (ext in setOf("gcm", "iso")) {
            return try {
                java.io.RandomAccessFile(path, "r").use { raf ->
                    if (raf.length() < 0x20) return false
                    raf.seek(0x18)
                    val b = ByteArray(4)
                    raf.readFully(b)
                    (b[0].toInt() and 0xFF) == 0xC2 &&
                        (b[1].toInt() and 0xFF) == 0x33 &&
                        (b[2].toInt() and 0xFF) == 0x9F &&
                        (b[3].toInt() and 0xFF) == 0x3D
                }
            } catch (_: Throwable) { false }
        }
        // 未知扩展名：默认按 Wii 处理（wii 布局兼容 GC 游戏的 SI 手柄，
        // 反之 NGC 布局对 Wii 游戏完全无输入）
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