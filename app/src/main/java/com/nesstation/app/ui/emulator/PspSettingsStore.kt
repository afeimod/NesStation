package com.nesstation.app.ui.emulator

import android.content.Context
import java.io.File

/**
 * ★★ PPSSPP（PSP）全局配置读写 —— NesStation 集成 ★★
 *
 * 直接读写 PPSSPP 的 ppsspp.ini（<externalFilesDir>/PSP/SYSTEM/ppsspp.ini，
 * 经 [ensurePpssppEnvironment] 预置 memstick）。键名/节名与 PPSSPP v1.20.4
 * 源码 Core/Config.cpp 的 ConfigSetting 逐一核对：
 *
 *   [Graphics] GraphicsBackend / InternalResolution / FrameSkip / AutoFrameSkip /
 *              VerticalSync / TexScalingLevel / TexScalingType / HardwareTransform /
 *              HardwareTessellation / SplineBezierQuality
 *   [PostShaderList] PostShader1（后处理着色器链第一槽）
 *   [System] CPUCore / CPUSpeed / FastMemoryAccess / IgnoreBadMemAccess / EnableCheats
 *   [Audio]  GameVolume
 *   [Controls] ShowTouchControls / TouchButtonOpacity
 *   [Network] EnableWlan
 *
 * 修改即时落盘；PPSSPP 在自身进程启动时读取 ini，故"下次启动 PSP 游戏生效"
 * （PPSSPP 游戏内设置菜单的改动也在退出时回写同一文件，双端互通）。
 */
object PspSettingsStore {

    /** ini 键定义：节 + 键。 */
    data class Key(val section: String, val name: String)

    // ---- 常用键（v1.20.4 Config.cpp 实证）----
    val GRAPHICS_BACKEND   = Key("Graphics", "GraphicsBackend")     // 0=OpenGL, 3=Vulkan
    val INTERNAL_RES       = Key("Graphics", "InternalResolution")  // 0=auto, 1..8
    val FRAME_SKIP         = Key("Graphics", "FrameSkip")           // 0..5
    val AUTO_FRAME_SKIP    = Key("Graphics", "AutoFrameSkip")       // bool
    val VSYNC              = Key("Graphics", "VerticalSync")        // bool
    val TEX_SCALE_LEVEL    = Key("Graphics", "TexScalingLevel")     // 1=off, 2..5
    val TEX_SCALE_TYPE     = Key("Graphics", "TexScalingType")      // 0=xBRZ, 1=Hybrid
    val HW_TRANSFORM       = Key("Graphics", "HardwareTransform")   // bool
    val HW_TESSELLATION    = Key("Graphics", "HardwareTessellation")// bool
    val SPLINE_QUALITY     = Key("Graphics", "SplineBezierQuality") // 0/1/2
    val POST_SHADER_1      = Key("PostShaderList", "PostShader1")   // "Off" / shader 名
    val CPU_CORE           = Key("System", "CPUCore")               // 0=JIT,1=IR-JIT,2=解释
    val CPU_SPEED          = Key("System", "CPUSpeed")              // 0=auto, 19..333
    val FAST_MEMORY        = Key("System", "FastMemoryAccess")      // bool
    val IGNORE_BAD_MEM     = Key("System", "IgnoreBadMemAccess")    // bool
    val ENABLE_CHEATS      = Key("System", "EnableCheats")          // bool
    val GAME_VOLUME        = Key("Audio", "GameVolume")             // 0..100
    val SHOW_TOUCH         = Key("Controls", "ShowTouchControls")   // bool
    val TOUCH_OPACITY      = Key("Controls", "TouchButtonOpacity")  // 0..100
    val ENABLE_WLAN        = Key("Network", "EnableWlan")           // bool

    // ------------------------------------------------------------------
    // ini 解析 / 写入（保序、保注释能力有限 —— PPSSPP 的 ini 简单扁平，
    // 逐行处理节与键即可；写回时只改目标键所在行，其余原样保留）
    // ------------------------------------------------------------------

    /** 读取指定键的原始字符串值；不存在返回 null。 */
    fun readRaw(ini: File, key: Key): String? {
        if (!ini.isFile) return null
        var inSection = false
        try {
            // 注意：不要用 forEachLine { ... return ... } —— 非 `for` 循环
            // 上下文里跨 lambda 的非局部 return 在部分 Kotlin 版本被拒编译
            //（"'return' is not allowed here"），for 循环内的 return 永远合法。
            for (line in ini.readLines()) {
                val t = line.trim()
                if (t.startsWith("[") && t.endsWith("]")) {
                    inSection = t.equals("[${key.section}]", ignoreCase = true)
                } else if (inSection && t.contains('=')) {
                    val k = t.substringBefore('=').trim()
                    if (k.equals(key.name, ignoreCase = true)) {
                        return t.substringAfter('=').trim()
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * 写入指定键（不存在则在节末尾追加；节不存在则建节）。
     * 只重写受影响的行 —— 保留 PPSSPP 自己写入的其它全部内容。
     */
    fun writeRaw(ini: File, key: Key, value: String) {
        try {
            ini.parentFile?.mkdirs()
            val lines: MutableList<String> = if (ini.isFile) {
                ini.readLines().toMutableList()
            } else {
                mutableListOf()
            }
            var sectionStart = -1
            var sectionEnd = lines.size
            var keyLine = -1
            for (i in lines.indices) {
                val t = lines[i].trim()
                if (t.startsWith("[") && t.endsWith("]")) {
                    if (t.equals("[${key.section}]", ignoreCase = true)) {
                        sectionStart = i
                    } else if (sectionStart >= 0) {
                        sectionEnd = i
                        break
                    }
                } else if (sectionStart >= 0 && t.contains('=')) {
                    val k = t.substringBefore('=').trim()
                    if (k.equals(key.name, ignoreCase = true)) {
                        keyLine = i
                        break
                    }
                }
            }
            if (keyLine >= 0) {
                lines[keyLine] = "${key.name} = $value"
            } else if (sectionStart >= 0) {
                lines.add(sectionEnd, "${key.name} = $value")
            } else {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                lines.add("[${key.section}]")
                lines.add("${key.name} = $value")
            }
            ini.writeText(lines.joinToString("\r\n") + "\r\n")
        } catch (t: Throwable) {
            android.util.Log.w("PspSettingsStore", "writeRaw(${key.section}/${key.name}) failed", t)
        }
    }

    // ---- 类型化便捷读写 ----

    fun readString(context: Context, key: Key, default: String): String {
        val raw = readRaw(pspSettingsIniFile(context), key) ?: return default
        return raw.ifBlank { default }
    }

    fun readInt(context: Context, key: Key, default: Int): Int =
        readRaw(pspSettingsIniFile(context), key)?.trim()?.toIntOrNull() ?: default

    fun readBool(context: Context, key: Key, default: Boolean): Boolean {
        val raw = readRaw(pspSettingsIniFile(context), key)?.lowercase() ?: return default
        return when (raw) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }
    }

    fun writeString(context: Context, key: Key, value: String) =
        writeRaw(pspSettingsIniFile(context), key, value)

    fun writeInt(context: Context, key: Key, value: Int) =
        writeRaw(pspSettingsIniFile(context), key, value.toString())

    /** PPSSPP 的 bool 落盘格式（大写 True/False，与其 Config 保存一致）。 */
    fun writeBool(context: Context, key: Key, value: Boolean) =
        writeRaw(pspSettingsIniFile(context), key, if (value) "True" else "False")

    /**
     * 可选后处理着色器列表："Off" + assets/shaders 下的 .fsh 基名。
     * PPSSPP 后处理着色器即 assets/shaders/<name>.fsh（游戏内"后处理着色器"
     * 菜单同样列出这些名字），动态列举保证与随包资源一致。
     */
    fun postShaderChoices(context: Context): List<Pair<String, String>> {
        val names = linkedSetOf("Off")
        try {
            context.assets.list("shaders")?.forEach { f ->
                if (f.endsWith(".fsh")) names.add(f.removeSuffix(".fsh"))
            }
        } catch (_: Throwable) {}
        return names.map { it to it }
    }

    // ==================================================================
    // ★★ libretro 核心选项（ppsspp_*）持久化 —— 现行设置通道 ★★
    //
    // 与上面 ppsspp.ini 直写（旧"独立 PPSSPP Activity"集成遗留）不同，
    // libretro 版 PPSSPP 核心【不读取 ppsspp.ini】—— 它唯一认可的设置
    // 通道是 libretro 核心选项（retro_GET_VARIABLE 的 ppsspp_* 键，与
    // RetroArch 的核心选项完全同构）。本节把核心选项持久化到独立文件
    // <filesDir>/psp_core_options.ini（每行 "ppsspp_xxx=value"），由
    // PspEngine 在 retro_load_game 之前整表灌入核心，并支持会话中热更新
    // （核心每帧经 GET_VARIABLE_UPDATE 感知变更）。
    //
    // 完整选项目录（键/合法值/中文标签/默认值）见 PspCoreOptions.kt，
    // 与 PPSSPP 官方 libretro.cpp check_variables() 逐一核对。
    // ==================================================================

    /** 核心选项存储文件（<filesDir>/psp_core_options.ini）。 */
    fun coreOptionsFile(context: Context): File =
        File(context.filesDir, "psp_core_options.ini")

    /** 读取全部已存储的核心选项（键 → 值；文件不存在返回空表）。 */
    fun readCoreOptions(context: Context): Map<String, String> {
        val out = linkedMapOf<String, String>()
        try {
            val f = coreOptionsFile(context)
            if (!f.isFile) return out
            for (line in f.readLines()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#") || !t.contains('=')) continue
                val k = t.substringBefore('=').trim()
                val v = t.substringAfter('=').trim()
                if (k.isNotEmpty()) out[k] = v
            }
        } catch (_: Throwable) {}
        return out
    }

    /** 读取单个核心选项；未存储返回 null。 */
    fun readCoreOption(context: Context, key: String): String? =
        readCoreOptions(context)[key]

    /** 写入单个核心选项（不存在则追加；原子落盘）。 */
    fun writeCoreOption(context: Context, key: String, value: String) {
        try {
            val f = coreOptionsFile(context)
            f.parentFile?.mkdirs()
            val lines = if (f.isFile) f.readLines().toMutableList() else mutableListOf()
            val newLine = "$key=$value"
            var replaced = false
            for (i in lines.indices) {
                if (lines[i].substringBefore('=').trim() == key) {
                    lines[i] = newLine
                    replaced = true
                    break
                }
            }
            if (!replaced) lines.add(newLine)
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(lines.joinToString("\n") + "\n")
            if (!tmp.renameTo(f)) {
                // rename 失败（跨设备/占用）退化为直写
                f.writeText(lines.joinToString("\n") + "\n")
                tmp.delete()
            }
        } catch (t: Throwable) {
            android.util.Log.w("PspSettingsStore", "writeCoreOption($key) failed", t)
        }
    }

    /** 删除单个核心选项（键不存在时无操作；原子落盘）。 */
    fun removeCoreOption(context: Context, key: String) {
        try {
            val f = coreOptionsFile(context)
            if (!f.isFile) return
            val lines = f.readLines().toMutableList()
            val kept = lines.filter { it.substringBefore('=').trim() != key }
            if (kept.size == lines.size) return
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(kept.joinToString("\n") + "\n")
            if (!tmp.renameTo(f)) {
                f.writeText(kept.joinToString("\n") + "\n")
                tmp.delete()
            }
        } catch (t: Throwable) {
            android.util.Log.w("PspSettingsStore", "removeCoreOption($key) failed", t)
        }
    }

    /** 一次性迁移标记键（存于核心选项文件内，值恒为 "1"）。 */
    private const val MIGRATION_MARKER_KEY = "nesstation_migration_internal_res_2x"

    /**
     * ★ v9 一次性迁移：渲染分辨率默认值 480x272(1x) → 960x544(2x)。
     *
     * v4–v8 的目录默认值是 "480x272"（1x 原生档）。若用户从没在面板里
     * 改过分辨率，其 psp_core_options.ini 中会存有旧默认值（或压根没有
     * 该键）——两种情况都应享受新默认值；只有用户【明确改过且不是
     * 480x272】的才保留。因此：存储值 == "480x272" 且迁移标记不存在时
     * 删除该存储键（回落到新默认 960x544）并写标记；标记存在则不再动。
     * 用户若在迁移后再次手动选 1x，标记已存在，选择会被尊重。
     */
    fun migrateInternalResolutionDefault(context: Context) {
        try {
            val stored = readCoreOptions(context)
            if (stored[MIGRATION_MARKER_KEY] == "1") return
            if (stored["ppsspp_internal_resolution"] == "480x272") {
                removeCoreOption(context, "ppsspp_internal_resolution")
            }
            writeCoreOption(context, MIGRATION_MARKER_KEY, "1")
        } catch (t: Throwable) {
            android.util.Log.w("PspSettingsStore", "migrateInternalResolutionDefault failed", t)
        }
    }
}
