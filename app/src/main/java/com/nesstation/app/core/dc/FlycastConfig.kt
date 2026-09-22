package com.nesstation.app.core.dc

import android.content.Context
import android.util.Log
import java.io.File

/**
 * emu.cfg 读写 —— Flycast (Dreamcast/NAOMI) 核心的全部配置载体。
 *
 * 这是核心自身的配置文件（native 侧 core/cfg/ini.cpp 解析，格式与
 * 上游逐字节兼容）：
 *
 * ```
 * [config]
 * Dreamcast.Region = 1
 * rend.Resolution = 480
 * Dynarec.Enabled = yes
 *
 * [network]
 * Enable = no
 * EmulateBBA = no
 * ```
 *
 * 序列化规则（与 flycast IniFile::save 一致）：
 *   · `[section]` 行 + `key = value` 行 + 节间空行；
 *   · 布尔写 "yes"/"no"（解析时 yes/true/on/1 均接受）；
 *   · 长键名选项（Dreamcast.* / rend.* / aica.* / Sh4Clock / Dynarec.Enabled …）
 *     全部位于 [config] 节（flycast Option 模板的默认 section）；
 *   · 短键名选项位于独立节（[network] Enable、[audio] VmuSound、
 *     [achievements] Enabled、[input] 映射 等）。
 *
 * 设计要点 —— **核心是配置的唯一权威**：
 *   · NesStation 设置页直接读写本文件（所见即核心所得）；
 *   · 用户在游戏内 Flycast 原生菜单改的设置也写回本文件，二者不互相覆盖；
 *   · 启动器不做任何设置注入，核心启动时自行加载 emu.cfg。
 */
class FlycastConfig private constructor(private val file: File) {

    /** 有序 section → (key → value)，保持文件原有顺序以便最小 diff 回写 */
    private val sections = LinkedHashMap<String, LinkedHashMap<String, String>>()
    private var dirty = false

    companion object {
        private const val TAG = "FlycastConfig"

        /** [config] 节名 —— flycast 长键名选项的默认 section */
        const val CONFIG = "config"

        /** 载入 emu.cfg；文件不存在时返回空配置（首次保存时自动创建） */
        fun load(ctx: Context): FlycastConfig {
            val cfg = FlycastConfig(FlycastPaths.configFile(ctx))
            cfg.read()
            return cfg
        }

        fun bool(v: Boolean): String = if (v) "yes" else "no"

        fun parseBool(v: String?, def: Boolean = false): Boolean {
            if (v == null) return def
            return when (v.trim().lowercase()) {
                "yes", "true", "on", "1" -> true
                "no", "false", "off", "0" -> false
                else -> def
            }
        }
    }

    private fun read() {
        sections.clear()
        if (!file.exists()) return
        try {
            var current: LinkedHashMap<String, String>? = null
            file.readLines().forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.isEmpty() || line.startsWith(";") || line.startsWith("#") -> Unit
                    line.startsWith("[") && line.endsWith("]") -> {
                        val name = line.substring(1, line.length - 1).trim()
                        current = sections.getOrPut(name) { LinkedHashMap() }
                    }
                    else -> {
                        if (current == null) current = sections.getOrPut(CONFIG) { LinkedHashMap() }
                        val idx = line.indexOf('=')
                        if (idx > 0) {
                            val key = line.substring(0, idx).trim()
                            val value = line.substring(idx + 1).trim()
                            current!![key] = value
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to parse ${file.absolutePath}", t)
        }
    }

    /** 保存（原子写：先写临时文件再改名，避免启动中核心读到半截文件） */
    @Synchronized
    fun save() {
        if (!dirty) return
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                for ((section, entries) in sections) {
                    if (entries.isEmpty()) continue
                    w.write("[$section]")
                    w.write("\n")
                    for ((k, v) in entries) {
                        w.write("$k = $v")
                        w.write("\n")
                    }
                    w.write("\n")
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    Log.w(TAG, "renameTo failed, falling back to direct write")
                    // 兜底：直接写目标文件
                    file.bufferedWriter().use { w ->
                        for ((section, entries) in sections) {
                            if (entries.isEmpty()) continue
                            w.write("[$section]\n")
                            for ((k, v) in entries) w.write("$k = $v\n")
                            w.write("\n")
                        }
                    }
                    tmp.delete()
                }
            }
            dirty = false
            Log.i(TAG, "emu.cfg saved (${sections.size} sections)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to save emu.cfg", t)
        }
    }

    // ------------------------------------------------------------------
    // 键值访问（均为 [config] 长键名或带独立节的短键名，与 flycast
    // core/cfg/option.cpp 的 Option(name, default, section) 定义一一对应）
    // ------------------------------------------------------------------
    fun get(key: String, section: String = CONFIG): String? =
        sections[section]?.get(key)

    fun getInt(key: String, def: Int, section: String = CONFIG): Int =
        get(key, section)?.trim()?.toIntOrNull() ?: def

    fun getBool(key: String, def: Boolean, section: String = CONFIG): Boolean =
        parseBool(get(key, section), def)

    fun set(key: String, value: String, section: String = CONFIG) {
        val map = sections.getOrPut(section) { LinkedHashMap() }
        if (map[key] != value) {
            map[key] = value
            dirty = true
        }
    }

    fun setInt(key: String, value: Int, section: String = CONFIG) = set(key, value.toString(), section)

    fun setBool(key: String, value: Boolean, section: String = CONFIG) = set(key, bool(value), section)

    /** 文件是否已存在（决定设置页是否显示"首次使用"提示） */
    fun existsOnDisk(): Boolean = file.exists()
}
