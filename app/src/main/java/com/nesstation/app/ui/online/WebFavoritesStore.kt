package com.nesstation.app.ui.online

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ★ v11: 浏览器收藏夹存储（SharedPreferences + JSON）。
 *
 * 需求（用户原话）："默认的一些网页直接放进收藏夹，支持添加和删除"
 * —— 在线游戏入口改为真实浏览器（WebGameScreen 浏览器主页 = 收藏夹网格），
 *    旧的「在线游戏站点列表」（OnlineGamesScreen）退役。
 *
 * ## 首次种子迁移（不丢老数据）
 * - 首次访问时把旧 WebGameStore.loadAll 的【内置 12 站点 + 用户自添加站点】
 *   整体迁移成本库初始收藏 —— 用户在旧列表里添加/改名的站点一条不丢；
 * - 之后本库完全独立于 WebGameStore（增删改名只影响收藏夹）。
 *
 * ## 与旧库的区别
 * - 收藏【可删除】：内置条目迁移后就是普通收藏，无 isBuiltin 不可删限制；
 * - 支持 url 级改名（custom_titles_json）与自定义图标（custom_icons_json），
 *   语义与 WebGameStore 同款（浏览器主页长按卡片操作）。
 */
object WebFavoritesStore {
    private const val PREFS_NAME = "web_favorites_store"
    private const val KEY_GAMES = "favorites_json"
    private const val KEY_TITLES = "custom_titles_json"
    private const val KEY_ICONS = "custom_icons_json"
    private const val KEY_SEEDED = "seeded_from_web_game_store_v1"

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
     * 首次种子迁移：把旧「在线游戏列表」的全部条目（内置 + 用户自定义，
     * 含已设置的自定义标题/图标 —— WebGameStore.loadAll 已把覆盖值套在条目上）
     * 一次性搬进收藏夹。幂等（KEY_SEEDED 标记）。
     */
    @Synchronized
    private fun ensureSeed(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_SEEDED, false)) return
        val legacy = WebGameStore.loadAll(ctx)
        val arr = JSONArray()
        val seedTitles = JSONObject()
        val seedIcons = JSONObject()
        legacy.filter { it.url.isNotBlank() }.forEach { e ->
            arr.put(JSONObject().apply {
                // loadAll 已应用改名覆盖：直接固化生效后的名字（用户看到什么迁移什么）
                put("title", e.customTitle?.takeIf { it.isNotBlank() } ?: e.title)
                put("url", e.url)
                put("uaMode", if (e.uaMode == "mobile") "mobile" else "desktop")
            })
            e.customTitle?.takeIf { it.isNotBlank() }?.let { seedTitles.put(e.url, it) }
            e.iconPath?.takeIf { it.isNotBlank() }?.let { seedIcons.put(e.url, it) }
        }
        p.edit()
            .putString(KEY_GAMES, arr.toString())
            .putString(KEY_TITLES, seedTitles.toString())
            .putString(KEY_ICONS, seedIcons.toString())
            .putBoolean(KEY_SEEDED, true)
            .apply()
    }

    /** 读取全部收藏（含改名/图标覆盖）。 */
    fun loadAll(ctx: Context): List<WebGameEntry> {
        ensureSeed(ctx)
        val titles = loadTitles(ctx)
        val icons = loadIcons(ctx)
        fun applyEntry(e: WebGameEntry): WebGameEntry = e.copy(
            customTitle = titles[e.url]?.takeIf { it.isNotBlank() },
            iconPath = icons[e.url]?.takeIf { it.isNotBlank() }
        )
        return try {
            val arr = JSONArray(prefs(ctx).getString(KEY_GAMES, "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val url = o.optString("url")
                if (url.isBlank()) null
                else WebGameEntry(
                    title = o.optString("title").ifBlank { url },
                    url = url,
                    uaMode = if (o.optString("uaMode") == "mobile") "mobile" else "desktop"
                )
            }.map(::applyEntry)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** url 是否已在收藏夹。 */
    fun isFavorite(ctx: Context, url: String): Boolean =
        loadAll(ctx).any { it.url == url }

    /**
     * 添加一条收藏。同 URL 视为更新（先移除旧条目再写入），并置顶。
     * 返回 false = url 空白（未写入）。
     */
    fun add(ctx: Context, title: String, url: String, uaMode: String): Boolean {
        if (url.isBlank()) return false
        ensureSeed(ctx)
        val list = rawList(ctx).toMutableList()
        list.removeAll { it.url == url }
        list.add(0, WebGameEntry(
            title = title.ifBlank { url },
            url = url,
            uaMode = if (uaMode == "mobile") "mobile" else "desktop"
        ))
        writeRaw(ctx, list)
        return true
    }

    /** 按 URL 删除收藏。 */
    fun remove(ctx: Context, url: String) {
        ensureSeed(ctx)
        val list = rawList(ctx).toMutableList()
        list.removeAll { it.url == url }
        writeRaw(ctx, list)
        // 顺带清掉该 url 的改名/图标覆盖，防残留脏数据
        val titles = loadTitles(ctx).toMutableMap()
        if (titles.remove(url) != null) writeTitles(ctx, titles)
        val icons = loadIcons(ctx).toMutableMap()
        if (icons.remove(url) != null) writeIcons(ctx, icons)
    }

    /** 重命名：设置 url 的自定义显示标题；空/空白清除覆盖恢复原名。 */
    fun setCustomTitle(ctx: Context, url: String, title: String?) {
        ensureSeed(ctx)
        val t = loadTitles(ctx).toMutableMap()
        if (title.isNullOrBlank()) t.remove(url) else t[url] = title.trim()
        writeTitles(ctx, t)
    }

    /** 设置自定义图标路径；空/空白清除覆盖恢复默认图标。 */
    fun setCustomIcon(ctx: Context, url: String, path: String?) {
        ensureSeed(ctx)
        val icons = loadIcons(ctx).toMutableMap()
        if (path.isNullOrBlank()) icons.remove(url) else icons[url] = path
        writeIcons(ctx, icons)
    }

    /** 原始条目列表（不套改名/图标）。 */
    private fun rawList(ctx: Context): List<WebGameEntry> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY_GAMES, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            WebGameEntry(
                title = o.optString("title").ifBlank { o.optString("url") },
                url = o.optString("url"),
                uaMode = if (o.optString("uaMode") == "mobile") "mobile" else "desktop"
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeRaw(ctx: Context, list: List<WebGameEntry>) {
        val arr = JSONArray()
        list.filter { it.url.isNotBlank() }.forEach { e ->
            arr.put(JSONObject().apply {
                put("title", e.title)
                put("url", e.url)
                put("uaMode", e.uaMode)
            })
        }
        prefs(ctx).edit().putString(KEY_GAMES, arr.toString()).apply()
    }

    // ============================================================
    // ★ v13: 浏览器主页自定义背景（图片 / 预设渐变 / 默认深色）
    // ============================================================
    private const val KEY_BG_MODE = "bg_mode"          // "none" | "preset" | "image"
    private const val KEY_BG_PRESET = "bg_preset"      // 预设索引 0..N
    private const val KEY_BG_IMAGE = "bg_image_path"   // 落盘图片路径

    /** 读取背景偏好：mode 与预设索引。 */
    fun getBackground(ctx: Context): Pair<String, Int> =
        (prefs(ctx).getString(KEY_BG_MODE, "none") ?: "none") to prefs(ctx).getInt(KEY_BG_PRESET, 0)

    /** 背景图片路径（仅 mode == "image" 时有效）。 */
    fun getBackgroundImagePath(ctx: Context): String? =
        prefs(ctx).getString(KEY_BG_IMAGE, null)

    /** 设置背景模式："none"=默认深色；"preset"=内置渐变（preset=预设索引）。 */
    fun setBackground(ctx: Context, mode: String, preset: Int) {
        prefs(ctx).edit()
            .putString(KEY_BG_MODE, mode)
            .putInt(KEY_BG_PRESET, preset)
            .apply()
    }

    /** 清除自定义背景图（回到默认深色；图片文件一并删除）。 */
    fun clearBackgroundImage(ctx: Context) {
        val img = File(ctx.filesDir, "web_bg/custom_bg.jpg")
        try { if (img.exists()) img.delete() } catch (_: Exception) {}
        prefs(ctx).edit()
            .putString(KEY_BG_MODE, "none")
            .putString(KEY_BG_IMAGE, "")
            .apply()
    }

    /**
     * 导入自定义背景图：SAF uri → 一次性读入内存 → 按最长边降采样到 ≤2048px
     * （防超大图解码 OOM）→ JPEG(88) 落盘 <filesDir>/web_bg/custom_bg.jpg。
     * 成功返回落盘路径并直接把偏好置为 image 模式；失败返回 null。
     */
    fun applyBackgroundImage(ctx: Context, uri: android.net.Uri): String? {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            if (bytes.isEmpty() || bytes.size > 50 * 1024 * 1024) return null
            // 先探边界算采样率（inJustDecodeBounds 不分配像素内存）
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return null
            val dir = File(ctx.filesDir, "web_bg").apply { mkdirs() }
            val dst = File(dir, "custom_bg.jpg")
            dst.outputStream().use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            }
            bmp.recycle()
            prefs(ctx).edit()
                .putString(KEY_BG_MODE, "image")
                .putString(KEY_BG_IMAGE, dst.absolutePath)
                .apply()
            dst.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
