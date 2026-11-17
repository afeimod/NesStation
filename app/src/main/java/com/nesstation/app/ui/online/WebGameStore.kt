package com.nesstation.app.ui.online

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WebGameEntry(
    val title: String,
    val url: String,
    val isBuiltin: Boolean = false,
    val uaMode: String = "desktop", // "desktop" or "mobile"
    val customTitle: String? = null, // 自定义显示标题（重命名）
    val iconPath: String? = null     // 自定义图标文件路径
)

/**
 * 在线网页游戏列表存储（SharedPreferences + JSON）。
 *
 * - 内置游戏（builtin）由 [getBuiltinGames] 提供，硬编码在应用内，不可删除。
 * - 用户自定义游戏（custom）通过 [save] 持久化到 SharedPreferences，可删除。
 * - [loadAll] 返回「内置 + 自定义」合并后的完整列表。
 *
 * 序列化格式（KEY_GAMES 对应的 JSON 字符串）：
 * [{"title":"..","url":"..","uaMode":"desktop"}, ...]
 * 自定义条目固定 isBuiltin=false，故无需持久化该字段。
 */
object WebGameStore {
    private const val PREFS_NAME = "web_game_store"
    private const val KEY_GAMES = "games_json"
    private const val KEY_TITLES = "custom_titles_json"
    private const val KEY_ICONS = "custom_icons_json"

    fun getBuiltinGames(): List<WebGameEntry> = listOf(
        WebGameEntry("4399电脑版", "https://www.4399.com/", true, "desktop"),
        WebGameEntry("4399手机版", "https://h.4399.com/wap/", true, "mobile"),
        WebGameEntry("灵动游戏", "https://www.mhhf.com/", true, "desktop"),
        WebGameEntry("瑾龙游戏", "https://www.jlgames.cn/", true, "desktop"),
        WebGameEntry("小霸王其乐无穷", "https://www.yikm.net/", true, "desktop"),
        WebGameEntry("7k7k游戏", "https://www.7k7k.com/", true, "desktop"),
        WebGameEntry("37游戏", "https://www.37.com/", true, "desktop"),
        WebGameEntry("Poki游戏", "https://poki.com/zh", true, "desktop"),
        WebGameEntry("233乐园", "https://www.233leyuan.com/game-detail-h5", true, "mobile"),
        WebGameEntry("y8游戏", "https://zh.y8.com/", true, "desktop"),
        WebGameEntry("FC在线", "https://www.playfc.cn/", true, "desktop"),
        WebGameEntry("Flash游戏", "http://www.flashgame.com.cn/", true, "desktop")
    )

    /** url → 自定义标题；空/缺失 = 用原始标题。 */
    private fun loadTitles(ctx: Context): Map<String, String> = try {
        val o = JSONObject(prefs(ctx).getString(KEY_TITLES, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (_: Exception) { emptyMap() }

    /** url → 自定义图标文件路径。 */
    private fun loadIcons(ctx: Context): Map<String, String> = try {
        val o = JSONObject(prefs(ctx).getString(KEY_ICONS, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (_: Exception) { emptyMap() }

    private fun writeTitles(ctx: Context, titles: Map<String, String>) {
        val o = JSONObject()
        titles.forEach { (k, v) -> if (v.isNotBlank()) o.put(k, v) }
        prefs(ctx).edit().putString(KEY_TITLES, o.toString()).apply()
    }

    private fun writeIcons(ctx: Context, icons: Map<String, String>) {
        val o = JSONObject()
        icons.forEach { (k, v) -> if (v.isNotBlank()) o.put(k, v) }
        prefs(ctx).edit().putString(KEY_ICONS, o.toString()).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读取所有自定义游戏（仅持久化部分，不含内置）。
     * 出现任何解析异常时返回空列表，保证应用不致崩溃。
     */
    private fun loadCustom(ctx: Context): List<WebGameEntry> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY_GAMES, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            WebGameEntry(
                title = o.optString("title"),
                url = o.optString("url"),
                // 持久化的全是自定义条目，固定为 false
                isBuiltin = false,
                uaMode = if (o.optString("uaMode") == "mobile") "mobile" else "desktop"
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** 将自定义游戏列表整体写回 SharedPreferences（JSON 数组）。 */
    private fun writeCustom(ctx: Context, list: List<WebGameEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("title", e.title)
                put("url", e.url)
                put("uaMode", e.uaMode)
            })
        }
        prefs(ctx).edit().putString(KEY_GAMES, arr.toString()).apply()
    }

    /**
     * 加载全部游戏：内置游戏在前，自定义游戏在后。
     * 应用自定义标题/图标覆盖（对内置游戏同样生效）。
     */
    fun loadAll(ctx: Context): List<WebGameEntry> {
        val titles = loadTitles(ctx)
        val icons = loadIcons(ctx)
        fun applyEntry(e: WebGameEntry): WebGameEntry = e.copy(
            // customTitle/iconPath 对 UI 转发显示名与图标；title 保留原始名
            customTitle = titles[e.url]?.takeIf { it.isNotBlank() },
            iconPath = icons[e.url]?.takeIf { it.isNotBlank() }
        )
        return getBuiltinGames().map(::applyEntry) + loadCustom(ctx).map(::applyEntry)
    }

    /** 重命名：设置 url 的自定义显示标题；传空/空白清除覆盖恢复原名。 */
    fun setCustomTitle(ctx: Context, url: String, title: String?) {
        val t = loadTitles(ctx).toMutableMap()
        if (title.isNullOrBlank()) t.remove(url) else t[url] = title.trim()
        writeTitles(ctx, t)
    }

    /** 设置自定义图标路径；传空/空白清除覆盖恢复默认图标。 */
    fun setCustomIcon(ctx: Context, url: String, path: String?) {
        val icons = loadIcons(ctx).toMutableMap()
        if (path.isNullOrBlank()) icons.remove(url) else icons[url] = path
        writeIcons(ctx, icons)
    }

    /**
     * 保存一个自定义游戏。
     * - 同 URL 视为重复：先移除旧条目再写入（更新），并移动到列表最前。
     * - 强制 [WebGameEntry.isBuiltin] = false，防止误把内置游戏持久化。
     */
    fun save(ctx: Context, game: WebGameEntry) {
        val custom = loadCustom(ctx).toMutableList()
        custom.removeAll { it.url == game.url }
        custom.add(0, game.copy(isBuiltin = false))
        writeCustom(ctx, custom)
    }

    /**
     * 按 URL 删除一个自定义游戏。
     * 内置游戏不受影响（内置游戏不在持久化列表中）。
     */
    fun delete(ctx: Context, url: String) {
        val custom = loadCustom(ctx).toMutableList()
        custom.removeAll { it.url == url }
        writeCustom(ctx, custom)
    }
}
