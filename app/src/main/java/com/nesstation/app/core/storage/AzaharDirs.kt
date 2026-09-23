package com.nesstation.app.core.storage

import android.content.Context
import java.io.File

/**
 * Azahar（3DS）用户目录与 config.ini 管理。
 *
 * 目录布局（<files>/azahar-user）：
 *   config/config.ini          主配置（Qt 风格 INI）
 *   keys/aes_keys.txt          3DS 解密密钥（Azahar 新位置）
 *   sysdata/aes_keys.txt       兼容旧 Citra 位置（两处都写）
 *   sdmc/                      SD 卡内容
 *   nand/                      系统存储（SystemSaveData / 已安装 CIA）
 *   states/                    即时存档
 *   cheats/                    金手指
 *
 * config.ini 分组/键与 Azahar Settings::values 对应
 * （键名由 org.citra.citra_emu.features.settings.SettingKeys 提供）。
 * 写入后调 AzaharNative.reloadSettings() 热生效。
 */
object AzaharDirs {

    @Volatile private var cachedUserDir: String? = null

    fun userDir(context: Context): String {
        cachedUserDir?.let { return it }
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "azahar-user")
        dir.mkdirs()
        val s = dir.absolutePath
        cachedUserDir = s
        return s
    }

    /** 引擎启动前调用：建目录树 + 播种默认 config.ini。 */
    fun ensureUserDir(context: Context): String {
        val root = File(userDir(context))
        root.mkdirs()
        listOf("config", "keys", "sysdata", "sdmc", "nand", "states", "cheats",
            "sdmc/Nintendo 3DS", "shaders").forEach {
            File(root, it).mkdirs()
        }
        val config = File(root, "config/config.ini")
        if (!config.exists() || config.length() == 0L) {
            config.parentFile?.mkdirs()
            defaultConfig().let { content ->
                config.writeText(content)
            }
        }
        return root.absolutePath
    }

    fun configFile(context: Context): File = File(userDir(context), "config/config.ini")

    /** aes_keys.txt 目标路径（两个兼容位置）。 */
    fun keyFiles(context: Context): List<File> = listOf(
        File(userDir(context), "keys/aes_keys.txt"),
        File(userDir(context), "sysdata/aes_keys.txt")
    )

    /** 导入 aes_keys.txt（内容原样落盘到两处）。返回 true=至少一处成功。 */
    fun importKeys(context: Context, content: String): Boolean {
        var ok = false
        keyFiles(context).forEach { f ->
            try {
                f.parentFile?.mkdirs()
                f.writeText(content)
                ok = true
            } catch (t: Throwable) {
                android.util.Log.w("AzaharDirs", "importKeys ${f.path}", t)
            }
        }
        return ok
    }

    /** 读取密钥文本（任一存在即返回）。 */
    fun readKeys(context: Context): String? {
        keyFiles(context).forEach { f ->
            if (f.exists() && f.length() > 0) {
                return try { f.readText() } catch (t: Throwable) { null }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // config.ini 读写（Qt 风格：[Section] + key=value，支持 \\ 转义）
    // ------------------------------------------------------------------

    /** 读整个 config 为 section -> (key -> value)。 */
    fun readConfig(context: Context): MutableMap<String, MutableMap<String, String>> {
        val result = linkedMapOf<String, MutableMap<String, String>>()
        val f = configFile(context)
        if (!f.exists()) return result
        var section = ""
        f.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim()
                result.getOrPut(section) { linkedMapOf() }
            } else {
                val idx = line.indexOf('=')
                if (idx > 0 && section.isNotEmpty()) {
                    result.getOrPut(section) { linkedMapOf() }[line.substring(0, idx).trim()] =
                        line.substring(idx + 1).trim()
                }
            }
        }
        return result
    }

    /** 写 config（全量重写；保留未知键）。 */
    fun writeConfig(context: Context, sections: Map<String, Map<String, String>>) {
        val sb = StringBuilder()
        // 固定顺序写已知分组，剩余按原顺序追加
        val order = listOf("Core", "System", "Renderer", "Layout", "Utility",
            "Audio", "Input", "Camera", "DataStorage", "Debugging", "WebService",
            "UI", "Shortcuts", "Multiplayer")
        val written = mutableSetOf<String>()
        order.forEach { name ->
            val kv = sections[name]
            if (!kv.isNullOrEmpty()) {
                sb.append('[').append(name).append("]\n")
                kv.forEach { (k, v) -> sb.append(k).append('=').append(v).append('\n') }
                sb.append('\n')
                written.add(name)
            }
        }
        sections.forEach { (name, kv) ->
            if (name !in written && kv.isNotEmpty()) {
                sb.append('[').append(name).append("]\n")
                kv.forEach { (k, v) -> sb.append(k).append('=').append(v).append('\n') }
                sb.append('\n')
            }
        }
        configFile(context).let {
            it.parentFile?.mkdirs()
            it.writeText(sb.toString())
        }
    }

    /** 单键更新（读改写）。 */
    fun setConfigValue(context: Context, section: String, key: String, value: String) {
        val all = readConfig(context)
        all.getOrPut(section) { linkedMapOf() }[key] = value
        writeConfig(context, all)
    }

    fun getConfigValue(context: Context, section: String, key: String, default: String): String {
        return readConfig(context)[section]?.get(key) ?: default
    }

    /**
     * 默认 config.ini —— 与 Azahar Android 默认一致，外加 NesStation 选择：
     *  - motion_device = android（★ 体感：核心经 ASensorManager 直读
     *    陀螺仪/加速度计，无需前端喂事件）
     *  - input_device = touchscreen（虚拟按键 id=ButtonType 映射）
     *  - use_vsync/use_hw_shader/use_shader_jit 等与上游默认一致
     */
    fun defaultConfig(): String = """
        [Core]
        use_cpu_jit = 1
        cpu_clock_percentage = 100
        use_frame_limit = 1
        frame_limit = 100
        use_virtual_sd = 1

        [System]
        is_new_3ds = 0
        region_value = -1
        init_clock = 0
        init_time = 946681279
        init_ticks_type = 0
        steps_per_hour = 204
        lle_applets = 0
        apply_region_free_patch = 0

        [Renderer]
        use_hw_shader = 1
        use_shader_jit = 1
        shaders_accurate_mul = 1
        use_disk_shader_cache = 1
        use_vsync = 1
        resolution_factor = 1
        graphics_api = 1
        spirv_shader_gen = 1
        async_shader_compilation = 0
        async_presentation = 1
        async_custom_loading = 1
        deterministic_async_operations = 1
        use_gles = 1
        render_3d = 0
        factor_3d = 0
        pp_shader_name = none (dumb)
        anaglyph_shader_name = none (dumb)
        filter_mode = 1
        texture_filter = 0
        texture_sampling = 0
        custom_textures = 0
        preload_textures = 0
        dump_textures = 0
        bg_red = 0
        bg_green = 0
        bg_blue = 0

        [Layout]
        layout_option = 2
        portrait_layout_option = 2
        swap_screen = 0
        upright_screen = 0
        large_screen_proportion = 4
        small_screen_position = 0
        screen_gap = 0
        screen_orientation = 0
        expand_to_cutout_area = 0

        [Utility]
        custom_textures = 0
        preload_textures = 0
        dump_textures = 0

        [Audio]
        output_type = 0
        output_device = auto
        input_type = 0
        input_device = auto
        audio_emulation = 1
        enable_audio_stretching = 1
        enable_realtime_audio = 0
        volume = 100

        [DataStorage]
        use_virtual_sd = 1

        [Debugging]
        use_gdbstub = 0
        gdbstub_port = 24689
        record_frame_times = 0
        log_filter = *:Info
    """.trimIndent()
}
