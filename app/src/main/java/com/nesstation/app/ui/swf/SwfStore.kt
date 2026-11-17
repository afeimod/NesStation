package com.nesstation.app.ui.swf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地 SWF 文件列表存储（SharedPreferences + JSON）。
 *
 * 保存用户添加的 SWF 文件路径和名称，避免每次播放都需要浏览文件系统。
 * 类似 NES ROM 库的功能——用户添加 SWF 后，下次直接从列表中选择播放。
 */
object SwfStore {

    private const val SP_NAME = "local_swf_store"
    private const val KEY_LIST = "swf_list"

    data class Entry(
        val path: String,           // 文件绝对路径
        val title: String,          // 显示名称（原始文件名）
        val size: Long,             // 文件大小（字节）
        val timestamp: Long,        // 添加时间
        val customTitle: String? = null, // 显示层自定义名称（重命名）
        val iconPath: String? = null     // 显示层自定义图标路径
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /** 获取所有保存的 SWF 文件 */
    fun list(ctx: Context): List<Entry> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY_LIST, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                path = o.optString("path"),
                title = o.optString("title"),
                size = o.optLong("size", 0),
                timestamp = o.optLong("ts"),
                customTitle = o.optString("customTitle").takeIf { it.isNotBlank() },
                iconPath = o.optString("iconPath").takeIf { it.isNotBlank() }
            )
        }
    } catch (_: Exception) { emptyList() }

    /** 添加一个 SWF 文件到列表（去重） */
    fun add(ctx: Context, path: String, title: String) {
        val list = list(ctx).toMutableList()
        list.removeAll { it.path == path }
        val size = try { File(path).length() } catch (_: Exception) { 0L }
        list.add(0, Entry(path, title, size, System.currentTimeMillis()))
        writeList(ctx, list)
    }

    /** 从列表中移除一个 SWF 文件 */
    fun remove(ctx: Context, path: String) {
        val list = list(ctx).toMutableList()
        list.removeAll { it.path == path }
        writeList(ctx, list)
    }

    /** 扫描指定文件夹中的所有 SWF 文件并添加到列表 */
    fun scanFolder(ctx: Context, folderPath: String): Int {
        val folder = File(folderPath)
        if (!folder.isDirectory) return 0
        var count = 0
        folder.listFiles()?.forEach { f ->
            if (f.isFile && f.extension.equals("swf", ignoreCase = true)) {
                add(ctx, f.absolutePath, f.nameWithoutExtension)
                count++
            }
        }
        return count
    }

    private fun writeList(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("path", e.path)
                put("title", e.title)
                put("size", e.size)
                put("ts", e.timestamp)
                e.customTitle?.takeIf { it.isNotBlank() }?.let { put("customTitle", it) }
                e.iconPath?.takeIf { it.isNotBlank() }?.let { put("iconPath", it) }
            })
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** 重命名：设置 path 的自定义显示标题；传 null/空白清除覆盖。 */
    fun setCustomTitle(ctx: Context, path: String, title: String?) {
        val list = list(ctx).toMutableList()
        val idx = list.indexOfFirst { it.path == path }
        if (idx < 0) return
        list[idx] = list[idx].copy(customTitle = title?.trim()?.takeIf { it.isNotEmpty() })
        writeList(ctx, list)
    }

    /** 设置自定义图标路径；传 null/空白清除覆盖。 */
    fun setCustomIcon(ctx: Context, path: String, iconPath: String?) {
        val list = list(ctx).toMutableList()
        val idx = list.indexOfFirst { it.path == path }
        if (idx < 0) return
        list[idx] = list[idx].copy(iconPath = iconPath?.takeIf { it.isNotBlank() })
        writeList(ctx, list)
    }

    /** 显示标题：优先自定义名称。 */
    fun displayTitle(e: Entry): String =
        e.customTitle?.takeIf { it.isNotBlank() } ?: e.title
}
