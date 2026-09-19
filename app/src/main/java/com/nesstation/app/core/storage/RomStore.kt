package com.nesstation.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent ROM library store using SharedPreferences.
 * Stores imported ROM paths so they survive activity recreation and app restarts.
 * Supports multiple platforms (NES, JAVA) and custom icons.
 */
object RomStore {
    private const val PREFS_NAME = "rom_library"
    private const val KEY_COUNT = "rom_count"
    private const val KEY_PREFIX_ID = "rom_id_"
    private const val KEY_PREFIX_TITLE = "rom_title_"
    private const val KEY_PREFIX_PATH = "rom_path_"
    private const val KEY_PREFIX_ACCENT = "rom_accent_"
    private const val KEY_PREFIX_PLATFORM = "rom_platform_"
    private const val KEY_PREFIX_ICON = "rom_icon_"
    private const val KEY_PREFIX_LASTPLAYED = "rom_lastplayed_"
    private const val KEY_PREFIX_FAVORITE = "rom_favorite_"
    private const val KEY_PREFIX_CUSTOM_TITLE = "rom_customtitle_"

    private val ACCENT_COLORS = listOf(
        0xFFE74C3C.toInt(), 0xFF27AE60.toInt(), 0xFF3498DB.toInt(),
        0xFF8E44AD.toInt(), 0xFFE67E22.toInt(), 0xFF1ABC9C.toInt(),
        0xFF2ECC71.toInt(), 0xFF9B59B6.toInt(), 0xFFF1C40F.toInt(),
        0xFF1E2A3A.toInt()
    )

    /** SharedPreferences keys for the last-imported SAF folder URI (for refresh). */
    private const val KEY_LAST_IMPORT_FOLDER_URI = "last_import_folder_uri"
    private const val KEY_LAST_IMPORT_PLATFORM = "last_import_platform"

    /** SharedPreferences key for ALL folders the user has imported games from. */
    private const val KEY_IMPORTED_FOLDERS = "imported_folders"

    /** Persist the SAF folder URI the user just imported from, so the Refresh
     *  button can re-scan the same folder without asking again. */
    fun setLastImportFolder(ctx: Context, folderUri: String?, platform: GamePlatform?) {
        val p = prefs(ctx).edit()
        if (folderUri == null) {
            p.remove(KEY_LAST_IMPORT_FOLDER_URI).remove(KEY_LAST_IMPORT_PLATFORM)
        } else {
            p.putString(KEY_LAST_IMPORT_FOLDER_URI, folderUri)
            p.putString(KEY_LAST_IMPORT_PLATFORM, platform?.name ?: GamePlatform.NES.name)
            // Also remember this folder in the persistent multi-folder list so
            // the Refresh button re-scans EVERY imported folder (not just the
            // most recent one). Without this, ROMs added/removed in folders
            // imported earlier were never picked up by the refresh button.
            val folders = getImportedFolders(ctx).toMutableList()
            if (folders.none { it.first == folderUri }) {
                folders.add(folderUri to (platform ?: GamePlatform.NES))
                p.putString(KEY_IMPORTED_FOLDERS, encodeImportedFolders(folders))
            }
        }
        p.apply()
    }

    /** Returns (folderUri, platform) pair, or null if no folder was ever imported. */
    fun getLastImportFolder(ctx: Context): Pair<String, GamePlatform>? {
        val p = prefs(ctx)
        val uri = p.getString(KEY_LAST_IMPORT_FOLDER_URI, null) ?: return null
        val platName = p.getString(KEY_LAST_IMPORT_PLATFORM, GamePlatform.NES.name)
        return uri to GamePlatform.fromString(platName)
    }

    /**
     * All folders the user has imported games from, as (folderUriOrPath,
     * platform) pairs. The Refresh button re-scans every one of them so newly
     * added or deleted ROMs are reflected correctly even when the user has
     * imported multiple folders.
     */
    fun getImportedFolders(ctx: Context): List<Pair<String, GamePlatform>> {
        val p = prefs(ctx)
        val raw = p.getString(KEY_IMPORTED_FOLDERS, null)
        val list = mutableListOf<Pair<String, GamePlatform>>()
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val uri = obj.optString("uri", null) ?: continue
                    val plat = GamePlatform.fromString(obj.optString("platform", GamePlatform.NES.name))
                    list.add(uri to plat)
                }
            } catch (_: Exception) {
                // Malformed data — fall through to the migration path below.
            }
        }
        // Migration: older versions only saved a single last-import folder.
        // If the multi-folder list is empty, treat that folder as the only one.
        if (list.isEmpty()) {
            getLastImportFolder(ctx)?.let { list.add(it) }
        }
        return list
    }

    private fun encodeImportedFolders(folders: List<Pair<String, GamePlatform>>): String {
        val arr = JSONArray()
        folders.forEach { (uri, platform) ->
            arr.put(JSONObject().put("uri", uri).put("platform", platform.name))
        }
        return arr.toString()
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Load all persisted ROM entries */
    fun loadAll(ctx: Context): MutableList<GameEntry> {
        val p = prefs(ctx)
        val count = p.getInt(KEY_COUNT, 0)
        val list = mutableListOf<GameEntry>()
        for (i in 0 until count) {
            val id = p.getString("${KEY_PREFIX_ID}$i", null) ?: continue
            val title = p.getString("${KEY_PREFIX_TITLE}$i", "Unknown") ?: "Unknown"
            val path = p.getString("${KEY_PREFIX_PATH}$i", null) ?: continue
            val accentInt = p.getInt("${KEY_PREFIX_ACCENT}$i", 0xFFE74C3C.toInt())
            val platform = GamePlatform.fromString(p.getString("${KEY_PREFIX_PLATFORM}$i", "NES"))
            val iconPath = p.getString("${KEY_PREFIX_ICON}$i", null)
            val lastPlayed = p.getLong("${KEY_PREFIX_LASTPLAYED}$i", 0L)
            val favorite = p.getBoolean("${KEY_PREFIX_FAVORITE}$i", false)
            val customTitle = p.getString("${KEY_PREFIX_CUSTOM_TITLE}$i", null)
            list.add(GameEntry(
                id = id, title = title, romPath = path,
                accent = Color(accentInt.toLong() and 0xFFFFFFFF),
                platform = platform,
                customIconPath = iconPath,
                lastPlayedAt = lastPlayed,
                isFavorite = favorite,
                customTitle = customTitle
            ))
        }
        return list
    }

    /** Load games filtered by platform */
    fun loadByPlatform(ctx: Context, platform: GamePlatform): List<GameEntry> {
        return loadAll(ctx).filter { it.platform == platform }
    }

    /** Save the entire list, replacing any previous data */
    fun saveAll(ctx: Context, list: List<GameEntry>) {
        val p = prefs(ctx)
        // 先备份"已导入文件夹"记忆键，否则下方 clear() 会把它们连同 ROM 数据
        // 一起清掉，导致刷新按钮丢失所有已导入文件夹、无法感知新增/删除游戏。
        val importedFolders = p.getString(KEY_IMPORTED_FOLDERS, null)
        val lastFolderUri = p.getString(KEY_LAST_IMPORT_FOLDER_URI, null)
        val lastPlatform = p.getString(KEY_LAST_IMPORT_PLATFORM, null)
        p.edit().clear().apply()
        p.edit().apply {
            putInt(KEY_COUNT, list.size)
            list.forEachIndexed { i, entry ->
                putString("${KEY_PREFIX_ID}$i", entry.id)
                putString("${KEY_PREFIX_TITLE}$i", entry.title)
                putString("${KEY_PREFIX_PATH}$i", entry.romPath)
                putInt("${KEY_PREFIX_ACCENT}$i", entry.accent.value.toInt())
                putString("${KEY_PREFIX_PLATFORM}$i", entry.platform.name)
                putString("${KEY_PREFIX_ICON}$i", entry.customIconPath)
                putLong("${KEY_PREFIX_LASTPLAYED}$i", entry.lastPlayedAt)
                putBoolean("${KEY_PREFIX_FAVORITE}$i", entry.isFavorite)
                putString("${KEY_PREFIX_CUSTOM_TITLE}$i", entry.customTitle)
            }
            // 恢复被 clear() 清掉的文件夹记忆键。
            if (importedFolders != null) putString(KEY_IMPORTED_FOLDERS, importedFolders)
            if (lastFolderUri != null) putString(KEY_LAST_IMPORT_FOLDER_URI, lastFolderUri)
            if (lastPlatform != null) putString(KEY_LAST_IMPORT_PLATFORM, lastPlatform)
        }.apply()
    }

    /** Add a single ROM entry and persist */
    fun add(ctx: Context, title: String, romPath: String, platform: GamePlatform = GamePlatform.NES): GameEntry {
        val list = loadAll(ctx)
        val existingIdx = list.indexOfFirst { it.romPath == romPath }
        if (existingIdx >= 0) {
            // Update platform and title if they differ — fixes wrong platform
            // from older imports that defaulted everything to NES.
            val existing = list[existingIdx]
            if (existing.platform != platform || existing.title != title) {
                list[existingIdx] = existing.copy(platform = platform, title = title)
                saveAll(ctx, list)
            }
            return list[existingIdx]
        }
        val accent = ACCENT_COLORS[list.size % ACCENT_COLORS.size]
        val entry = GameEntry(
            id = "rom_${System.currentTimeMillis()}_${list.size}",
            title = title,
            romPath = romPath,
            accent = Color(accent),
            platform = platform
        )
        list.add(entry)
        saveAll(ctx, list)
        return entry
    }

    /** Add multiple ROM entries and persist */
    fun addAll(ctx: Context, entries: List<Pair<String, String>>, platform: GamePlatform = GamePlatform.NES): List<GameEntry> {
        val list = loadAll(ctx)
        val existingPaths = list.map { it.romPath }.toMutableSet()
        val added = mutableListOf<GameEntry>()
        entries.forEach { (title, path) ->
            if (path !in existingPaths) {
                val accent = ACCENT_COLORS[list.size % ACCENT_COLORS.size]
                val entry = GameEntry(
                    id = "rom_${System.currentTimeMillis()}_${list.size}",
                    title = title,
                    romPath = path,
                    accent = Color(accent),
                    platform = platform
                )
                list.add(entry)
                existingPaths.add(path)
                added.add(entry)
            }
        }
        saveAll(ctx, list)
        return added
    }

    /**
     * 批量导入（每项自带平台）—— 修复"添加大目录卡死黑屏"的性能根因之一。
     *
     * 旧流程在导入循环里逐个调 [add]，而 add 每次 = loadAll（全量读）+
     * saveAll（全量清空重写）→ N 个 ROM 触发 N² 级别的 JSON/SharedPreferences
     * 读写，上千个街机 zip 时 IO 线程被拖住几十秒（主线程路径直接 ANR）。
     * 本函数整批只做一次 loadAll + 一次 saveAll。
     *
     * @param items (title, romPath, platform) 三元组列表
     * @return 实际新增的条目（路径已存在的不重复入库）
     */
    fun importGames(
        ctx: Context,
        items: List<Triple<String, String, GamePlatform>>
    ): List<GameEntry> {
        if (items.isEmpty()) return emptyList()
        val list = loadAll(ctx)
        val existingPaths = list.map { it.romPath }.toMutableSet()
        val added = mutableListOf<GameEntry>()
        for ((title, path, platform) in items) {
            if (path in existingPaths) continue
            val accent = ACCENT_COLORS[list.size % ACCENT_COLORS.size]
            val entry = GameEntry(
                id = "rom_${System.currentTimeMillis()}_${list.size}",
                title = title,
                romPath = path,
                accent = Color(accent),
                platform = platform
            )
            list.add(entry)
            existingPaths.add(path)
            added.add(entry)
        }
        if (added.isNotEmpty()) saveAll(ctx, list)
        return added
    }

    /** Remove a ROM entry by id */
    fun remove(ctx: Context, id: String) {
        val list = loadAll(ctx)
        list.removeAll { it.id == id }
        saveAll(ctx, list)
    }

    /**
     * 批量按 id 删除（一次 loadAll + 一次 saveAll）。
     * 供游戏库"刷新"流程整批移除已删除文件的游戏 —— 旧实现逐个调 [remove]，
     * 每个都是全量读写，大库里非常慢。
     * @return 实际删除的数量
     */
    fun removeIds(ctx: Context, ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val idSet = ids.toHashSet()
        val list = loadAll(ctx)
        val before = list.size
        list.removeAll { it.id in idSet }
        if (list.size != before) saveAll(ctx, list)
        return before - list.size
    }

    /** Update a game entry (e.g., custom icon, last played, favorite) */
    fun update(ctx: Context, entry: GameEntry) {
        val list = loadAll(ctx)
        val idx = list.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            list[idx] = entry
            saveAll(ctx, list)
        }
    }

    /** Set custom icon path for a game */
    fun setCustomIcon(ctx: Context, gameId: String, iconPath: String?) {
        val list = loadAll(ctx)
        val idx = list.indexOfFirst { it.id == gameId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(customIconPath = iconPath)
            saveAll(ctx, list)
        }
    }

    /**
     * Set a user-defined display name for a game. When non-null and non-blank,
     * this overrides the ROM filename-derived title for display in the app.
     * Pass null or blank to clear the custom name and revert to the original title.
     * The underlying ROM file name is NEVER changed.
     */
    fun setCustomTitle(ctx: Context, gameId: String, customTitle: String?) {
        val list = loadAll(ctx)
        val idx = list.indexOfFirst { it.id == gameId }
        if (idx >= 0) {
            val cleaned = customTitle?.trim()
            list[idx] = list[idx].copy(customTitle = if (cleaned.isNullOrBlank()) null else cleaned)
            saveAll(ctx, list)
        }
    }

    /** Update last played timestamp */
    fun updateLastPlayed(ctx: Context, gameId: String) {
        val list = loadAll(ctx)
        val idx = list.indexOfFirst { it.id == gameId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(lastPlayedAt = System.currentTimeMillis())
            saveAll(ctx, list)
        }
    }

    /** Toggle favorite status */
    fun toggleFavorite(ctx: Context, gameId: String) {
        val list = loadAll(ctx)
        val idx = list.indexOfFirst { it.id == gameId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(isFavorite = !list[idx].isFavorite)
            saveAll(ctx, list)
        }
    }

    /**
     * 街机游戏中文标题迁移。
     *
     * 对于已经是 ARCADE 平台但标题仍是英文驱动名（如 "kof98h"、"mvc"）
     * 的游戏，尝试用 ArcadeTitleMapper 查找中文名并更新。
     *
     * 已是中文标题或查找不到中文名的游戏不会被修改。
     * 返回更新了标题的游戏数量。
     */
    fun migrateArcadeTitles(ctx: Context): Int {
        val list = loadAll(ctx)
        var updatedCount = 0
        var changed = false
        for (i in list.indices) {
            val entry = list[i]
            if (entry.platform != GamePlatform.ARCADE) continue
            // 跳过已包含中文字符的标题（认为已经汉化过）
            if (entry.title.any { it.code in 0x4E00..0x9FFF }) continue
            // 尝试用文件名（带后缀）查表，覆盖度更高
            val fileName = entry.romPath?.substringAfterLast('/') ?: entry.title
            val cnName = ArcadeTitleMapper.lookupByFileName(fileName)
                ?: ArcadeTitleMapper.lookup(entry.title)
            if (cnName != null && cnName != entry.title) {
                list[i] = entry.copy(title = cnName)
                updatedCount++
                changed = true
            }
        }
        if (changed) saveAll(ctx, list)
        return updatedCount
    }
}
