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

    // ==================================================================
    // ★★ v23→v24: HD 纹理包路径实证与工具 —— 结合 PPSSPP 上游源码 ★★
    //
    // 路径链（逐层实证，v22 及更早的引导目录全部是错的）：
    //   1. 上游 libretro.cpp  g_Config.memStickDirectory = retro_save_dir
    //      —— memstick 根 = libretro save 目录；
    //   2. psp_loader.cpp GET_SAVE_DIRECTORY 返回 PspNative.setPaths(systemDir,
    //      saveDir) 传入的 saveDir = EmulatorScreen 的 savesDirPath
    //      （v23 及更早 = <filesDir>/saves 内部不可见；★ v24 起 PSP 专传
    //      [memstickRoot] = <外置files>/PSP，见下节）；
    //   3. 上游 TextureReplacer.cpp NotifyConfigChanged：
    //      basePath_ = GetSysDirectory(DIRECTORY_TEXTURES) / gameID_
    //      = <memstick>/PSP/TEXTURES/<游戏ID>/（TEXTURES 大写！Android 内部
    //      存储大小写敏感，小写 textures 核心永远读不到）；
    //   4. gameID_ = g_paramSFO.GetDiscID() = PARAM.SFO DISC_ID 原样值 ——
    //      大写、无连字符（如 NPJH50505 / ULUS10566，官方文档同口径）；
    //   5. 同函数 replaceEnabled_ && File::IsDirectory(basePath_) ——
    //      目录不存在则高清纹理替换【静默自禁用】（"开了没反应"的机制根源）。
    //
    // v23 教训：路径算对了但指向 <filesDir>/saves（/data/data 内部，文件
    // 管理器不可见）+ 目录从不预建（libretro 版不调 CreateSysDirectories）
    // ＝ 用户视角 "psp文件夹什么都没有生成、贴图依旧无效"。
    // ==================================================================

    // ==================================================================
    // ★★ v24: memstick 迁至用户可见目录 + 预建目录树 ★★
    //
    // 上游实证（hrydgard/ppsspp master，逐文件核对）：
    //   1. libretro/libretro.cpp:1265  g_Config.memStickDirectory = retro_save_dir
    //      —— memstick 根 = libretro save 目录（本应用 = EmulatorScreen 传给
    //      loadRom 的 saveDir，v24 起 PSP 平台专传本函数返回的目录）；
    //   2. Core/Util/PathUtil.cpp:95-102  memstick 末级目录名若为 "PSP" 则自动
    //      去重 —— memstick=<外置files>/PSP 时 TEXTURES=<外置files>/PSP/TEXTURES
    //      （与 PPSSPP 安卓独立版 /sdcard/PSP 布局完全一致）；
    //   3. Core/Util/PathUtil.cpp:158 CreateSysDirectories() 会预建
    //      PSP/{SAVEDATA,PPSSPP_STATE,GAME,SYSTEM,TEXTURES,CHEATS,PLUGINS,
    //      SYSTEM/CACHE}（Android 加 .nomedia）—— 但【libretro 移植从不调用它】
    //      （libretro.cpp 无任何调用点）＝ 用户 "psp文件夹什么都没生成" 的
    //      根因：核心永远不会自建目录树，必须前端预建。
    //
    // v24 方案：memstick = <getExternalFilesDir>/PSP（文件管理器/电脑可见，
    // /storage/emulated/0/Android/data/<pkg>/files/PSP/），首启预建全套目录
    // 树 + 一次性从旧内部 memstick（<filesDir>/saves）递归拷贝迁移。
    // 外置目录不可用（罕见）时回退旧路径（行为 = v23，不会更差）。
    // ==================================================================

    /**
     * memstick 根目录：优先 <getExternalFilesDir>/PSP（用户可见），
     * 不可用时回退 <filesDir>/saves（内部，v23 行为）。
     */
    fun memstickRoot(context: Context): java.io.File {
        val ext = context.getExternalFilesDir(null)
        return if (ext != null) java.io.File(ext, "PSP")
               else java.io.File(context.filesDir, "saves")
    }

    /** 一次性迁移标记（存在 = 旧内部 memstick 已搬运，后续启动直接跳过）。 */
    private val memstickMigrateMarker = ".ns_memstick_migrated"

    /**
     * 确保 memstick 目录树存在并完成一次性迁移，返回 memstick 根。
     * 上游 CreateSysDirectories 同款目录集（含 .nomedia），由前端预建 ——
     * libretro 版 PPSSPP 永远不会自建这些目录。
     * 一次性迁移：旧内部 memstick（&lt;filesDir&gt;/saves 下的 PSP 子树）递归
     * 拷贝到新根（跳过已存在，绝不覆盖；源保留作备份）。
     * 幂等 —— 标记文件存在即跳过。
     *
     * ⚠ 注释书写注意：Kotlin 块注释支持嵌套，正文中禁止出现字面 "PSP/"
     * 紧跟星号的写法（会被词法器当作嵌套注释开括号，导致本文件 317 行起
     * 全部代码被吞进注释、编译报 Missing '}' / Unclosed comment）。
     */
    fun ensureMemstick(context: Context): java.io.File {
        val root = memstickRoot(context)
        try {
            // 1) 预建目录树（上游 PathUtil.cpp CreateSysDirectories 同款）
            val dirs = listOf(
                "SAVEDATA", "PPSSPP_STATE", "GAME", "SYSTEM",
                "TEXTURES", "CHEATS", "PLUGINS", "SYSTEM/CACHE")
            for (d in dirs) {
                val f = java.io.File(root, d)
                f.mkdirs()
                // 上游 Android 行为：各子目录放 .nomedia 防媒体库扫描
                try { java.io.File(f, ".nomedia").createNewFile() } catch (_: Throwable) {}
            }
            // 2) 一次性迁移旧内部 memstick（PSP/ 整树 = SAVEDATA/存档/纹理包…）
            val marker = java.io.File(root, memstickMigrateMarker)
            val oldPsp = java.io.File(context.filesDir, "saves/PSP")
            if (!marker.isFile && oldPsp.isDirectory) {
                copyTreeRecursive(oldPsp, root)
                try { marker.createNewFile() } catch (_: Throwable) {}
            } else if (!marker.isFile) {
                // 无旧数据也写标记，避免每启动都扫 saves 目录
                try { marker.createNewFile() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        return root
    }

    /** 递归拷贝（跳过已存在；返回拷贝的文件数）。 */
    private fun copyTreeRecursive(src: java.io.File, dst: java.io.File): Int {
        var n = 0
        try {
            val files = src.listFiles() ?: return 0
            for (f in files) {
                val t = java.io.File(dst, f.name)
                if (f.isDirectory) {
                    t.mkdirs()
                    n += copyTreeRecursive(f, t)
                } else if (!t.isFile) {
                    try {
                        f.inputStream().use { i -> t.outputStream().use { o -> i.copyTo(o) } }
                        n++
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        return n
    }

    /** 核心真正读取的纹理包目录（正确路径，勿改大小写）。 */
    fun texturePackDir(context: Context): java.io.File =
        java.io.File(memstickRoot(context), "TEXTURES")

    /** v22 及更早引导文案造成的错误放置位置（仅用于检测/迁移）。 */
    private fun legacyTextureRoots(context: Context): List<java.io.File> {
        val roots = mutableListOf(
            // ★ v24: 旧正确目录（v23 口径，内部不可见路径）也要迁到新 memstick
            java.io.File(context.filesDir, "saves/PSP/TEXTURES"),
            java.io.File(context.filesDir, "saves/PSP/textures"),   // 根对了但小写
            java.io.File(context.filesDir, "PSP/textures"),          // 根错+小写
            java.io.File(context.filesDir, "psp/PSP/textures"),
            java.io.File(context.filesDir, "psp/textures"),
            java.io.File(context.filesDir, "PPSSPP/PSP/textures"))
        val memstick = memstickRoot(context)
        // 注意：新 memstick 自己的下级 textures（小写误建）也算旧位置
        roots.add(java.io.File(memstick, "textures"))
        context.getExternalFilesDir(null)?.let { ext ->
            roots.add(java.io.File(ext, "PSP/textures"))            // v22 引导的外部目录
            roots.add(java.io.File(ext, "psp/PSP/textures"))
            roots.add(java.io.File(ext, "psp/textures"))
            roots.add(java.io.File(ext, "PPSSPP/PSP/textures"))
        }
        return roots.distinctBy { it.absolutePath }
    }

    /**
     * 扫描纹理包：返回 (正确目录包数, 正确目录绝对路径, 旧错误位置包数)。
     * 旧位置包数 > 0 时应引导用户迁移（见 [migrateLegacyTexturePacks]）。
     */
    fun scanTexturePacks(context: Context): Triple<Int, String, Int> {
        val canonical = texturePackDir(context)
        val count = canonical.listFiles { f -> f.isDirectory }?.size ?: 0
        var legacyCount = 0
        for (r in legacyTextureRoots(context)) {
            legacyCount += r.listFiles { f -> f.isDirectory }?.size ?: 0
        }
        return Triple(count, canonical.absolutePath, legacyCount)
    }

    /**
     * 把旧错误位置的纹理包目录迁移到核心真实目录（目标已存在则跳过该包，
     * 绝不覆盖）。★ v24: 旧位置可能在另一存储卷（内部→外置），renameTo
     * 跨卷必败 —— 改为递归拷贝 + 成功后删源；同卷 renameTo 快路径优先。
     * 返回迁移成功的包数。
     */
    fun migrateLegacyTexturePacks(context: Context): Int {
        val canonical = texturePackDir(context)
        try { canonical.mkdirs() } catch (_: Throwable) {}
        var moved = 0
        for (r in legacyTextureRoots(context)) {
            val packs = r.listFiles { f -> f.isDirectory } ?: continue
            for (p in packs) {
                val target = java.io.File(canonical, p.name)
                if (target.exists()) continue
                try {
                    if (p.renameTo(target)) { moved++; continue }
                    // 跨卷 renameTo 失败 → 递归拷贝 + 删源
                    if (copyTreeRecursive(p, target) > 0) {
                        p.deleteRecursively()
                        moved++
                    }
                } catch (_: Throwable) {}
            }
        }
        return moved
    }

    /** 纹理包导入结果。 */
    data class TexturePackImportResult(
        val ok: Boolean,
        val message: String,
        val folders: List<String> = emptyList()
    )

    /**
     * 从 SAF Uri 导入纹理包 zip，解压到核心真实读取的
     * <外置files>/PSP/TEXTURES/<游戏ID>/（memstickRoot —— 与上游 PPSSPP
     * "打开 zip 自动安装" 同等效果）。识别三种包结构（按优先级）：
     *   1) zip 根层有以游戏ID命名的文件夹（NPJH50505/xxx.png）—— 全部导入；
     *   2) 唯一顶层包装文件夹且其名是游戏ID —— 以该文件夹为 ID 导入；
     *   3) 以上都没有 —— 要求 zip 文件名即游戏ID（官方发行包的普遍形态）。
     * 拒绝无 ID 可得的导入（给出改名指引），杜绝解压成核心读不到的孤儿目录。
     */
    fun importTexturePackZip(context: Context, uri: android.net.Uri): TexturePackImportResult {
        val tmp = java.io.File(context.cacheDir,
            "psp_tex_import_${System.currentTimeMillis()}.zip")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return TexturePackImportResult(false, "无法读取所选文件")
            java.util.zip.ZipFile(tmp).use { zip ->
                val idPattern = Regex("^[A-Za-z]{4}[0-9]{5}$")
                fun junk(name: String): Boolean =
                    name.isBlank() || name.contains("__MACOSX") || name.contains("..") ||
                    name.split('/').any { it.startsWith(".") }
                // java.util.Enumeration 手动遍历（不依赖任何序列转换扩展的可用性）
                val entries = mutableListOf<java.util.zip.ZipEntry>()
                val en = zip.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    if (!e.isDirectory && !junk(e.name) &&
                        !e.name.substringAfterLast('/').startsWith("._")) {
                        entries.add(e)
                    }
                }
                if (entries.isEmpty())
                    return TexturePackImportResult(false, "zip 内没有可用的纹理文件")
                // zip 文件名（SAF 显示名去扩展名）—— 可能本身即游戏ID
                var displayName: String? = null
                try {
                    context.contentResolver.query(uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (i >= 0) displayName = c.getString(i)
                        }
                    }
                } catch (_: Throwable) {}
                val stemId = (displayName ?: "").substringBeforeLast('.').trim().uppercase()

                // (游戏ID, 解压时需剥掉的 zip 内前缀) 目标列表
                val targets: List<Pair<String, String>> = run {
                    // ① 根层以游戏ID命名的文件夹（NPJH50505/xxx.png）—— 全部导入
                    val idDirs = entries.mapNotNull { e ->
                        val top = e.name.substringBefore('/')
                        if (e.name.contains('/') && idPattern.matches(top)) top.uppercase() else null
                    }.toSortedSet()
                    if (idDirs.isNotEmpty()) return@run idDirs.map { it to "$it/" }
                    // ② 唯一顶层包装文件夹：优先用文件夹名，其次 zip 文件名作 ID
                    val tops = entries.map { it.name.substringBefore('/') }.toSet()
                    if (tops.size == 1 && entries.all { it.name.contains('/') }) {
                        val wrapper = tops.first()
                        if (idPattern.matches(wrapper)) return@run listOf(wrapper.uppercase() to "$wrapper/")
                        if (idPattern.matches(stemId)) return@run listOf(stemId to "$wrapper/")
                        return@run emptyList<Pair<String, String>>()
                    }
                    // ③ 根层散文件 —— zip 文件名即游戏ID（官方发行包普遍形态）
                    if (idPattern.matches(stemId)) return@run listOf(stemId to "")
                    emptyList<Pair<String, String>>()
                }
                if (targets.isEmpty())
                    return TexturePackImportResult(false,
                        "未识别游戏ID：请把 zip 重命名为 <游戏ID>.zip（如 NPJH50505.zip），" +
                        "或把 zip 内纹理放进以游戏ID命名的文件夹后重新导入")
                val canonical = texturePackDir(context)
                try { canonical.mkdirs() } catch (_: Throwable) {}
                val done = mutableListOf<String>()
                for ((id, prefix) in targets) {
                    val dir = java.io.File(canonical, id)
                    try { dir.mkdirs() } catch (_: Throwable) {}
                    var n = 0
                    for (e in entries) {
                        if (!e.name.startsWith(prefix)) continue
                        val rel = e.name.removePrefix(prefix)
                        if (rel.isBlank()) continue
                        val out = java.io.File(dir, rel)
                        // zip-slip 防护：落盘路径必须仍在目标目录内
                        if (!out.canonicalPath.startsWith(dir.canonicalPath + java.io.File.separator)) continue
                        try {
                            out.parentFile?.mkdirs()
                            zip.getInputStream(e).use { ins ->
                                out.outputStream().use { o -> ins.copyTo(o) }
                            }
                            n++
                        } catch (_: Throwable) {}
                    }
                    if (n > 0) done.add(id)
                }
                return if (done.isEmpty())
                    TexturePackImportResult(false, "导入失败：未能解压出任何纹理文件")
                else
                    TexturePackImportResult(true,
                        "已导入 ${done.size} 个纹理包（${done.joinToString("、")}）", done)
            }
        } catch (t: Throwable) {
            android.util.Log.w("PspSettingsStore", "importTexturePackZip failed", t)
            return TexturePackImportResult(false,
                "导入失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            try { tmp.delete() } catch (_: Throwable) {}
        }
    }
}
