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
        val raw = readRaw(ppssppIniFile(context), key) ?: return default
        return raw.ifBlank { default }
    }

    fun readInt(context: Context, key: Key, default: Int): Int =
        readRaw(ppssppIniFile(context), key)?.trim()?.toIntOrNull() ?: default

    fun readBool(context: Context, key: Key, default: Boolean): Boolean {
        val raw = readRaw(ppssppIniFile(context), key)?.lowercase() ?: return default
        return when (raw) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }
    }

    fun writeString(context: Context, key: Key, value: String) =
        writeRaw(ppssppIniFile(context), key, value)

    fun writeInt(context: Context, key: Key, value: Int) =
        writeRaw(ppssppIniFile(context), key, value.toString())

    /** PPSSPP 的 bool 落盘格式（大写 True/False，与其 Config 保存一致）。 */
    fun writeBool(context: Context, key: Key, value: Boolean) =
        writeRaw(ppssppIniFile(context), key, if (value) "True" else "False")

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
}
