package com.nesstation.app.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Extracts built-in icons from game ROM files for display in the library.
 *
 * Supported sources:
 *   - JAVA (J2ME): icon.png already extracted to the app's converted dir
 *     by JavaGameStore. This helper just resolves the path.
 *   - FBNeo (Arcade): some ROM zips include preview .png images (rare but
 *     happens for homebrew/translation packs). We check for any .png file
 *     in the zip and use the first one as the icon.
 *   - NDS: ROM 自带 banner 图标（头 0x68 偏移，32×32 4bpp）。
 *   - 3DS: 卡带镜像 (.3ds/.cci) 自带 SMDH 图标（0x20C0 偏移，48×48）。
 *     需求：“nds和3ds以及java游戏自己都有封面的，核心应该自动显示
 *     自带的封面才对”。
 *   - Other platforms (NES/SFC/GB/GBA/MD/DOS): no built-in icon in the ROM
 *     format. Returns null — the library shows a colored placeholder with
 *     the platform badge.
 *
 * All extracted icons are cached in <filesDir>/icons/<gameId>.png so we
 * only extract once per game. Subsequent calls return the cached file path.
 *
 * This implements the user's request: "考虑一下很多引擎游戏都有自带图标的，
 * 读取时可以直接显示游戏内置的图标，比如java等"
 */
object GameIconExtractor {
    private const val TAG = "GameIconExtractor"

    /**
     * Resolve the icon path for a game entry.
     *
     * Returns the absolute path to an icon file (PNG) if one is available,
     * either from the game's built-in metadata (Java MIDlet-Icon, arcade
     * preview png) or from a previously-extracted cache. Returns null when
     * no icon is available — callers should fall back to a colored placeholder.
     *
     * NOTE: For SAF-imported ROMs (content:// URIs), this does NOT do a
     * one-time extract because we'd need to copy the file first. The icon
     * is only extracted for locally-cached files (e.g. Java's converted dir,
     * arcade zip files copied to cacheDir during launch). For other games,
     * the user can manually set a custom icon via the long-press menu.
     */
    fun resolveIconPath(context: Context, game: GameEntry): String? {
        // 1. User-set custom icon takes priority
        game.customIconPath?.let { path ->
            if (File(path).exists()) return path
        }

        // 2. Per-platform extraction
        return when (game.platform) {
            GamePlatform.JAVA -> resolveJavaIcon(game)
            GamePlatform.ARCADE -> resolveArcadeIcon(context, game)
            GamePlatform.NDS -> resolveRomBannerIcon(context, game, is3ds = false)
            GamePlatform.N3DS -> resolveRomBannerIcon(context, game, is3ds = true)
            else -> game.coverPath?.takeIf { File(it).exists() }
        }
    }

    /**
     * Java icon: JavaGameStore already extracts icon.png to the converted dir.
     * Just check if it exists.
     */
    private fun resolveJavaIcon(game: GameEntry): String? {
        val path = game.romPath ?: return null
        val iconFile = File(path, "icon.png")
        return if (iconFile.exists() && iconFile.length() > 0) iconFile.absolutePath else null
    }

    /**
     * Arcade icon: check if the ROM zip contains any .png preview image.
     * Some FBNeo ROM packs (especially translations/homebrew) include a
     * preview .png. If found, extract to <filesDir>/icons/<gameId>.png.
     *
     * NOTE: This only works for locally-cached zip files. For content://
     * URIs, the user must launch the game first (which copies the zip to
     * cacheDir) before the icon can be extracted.
     */
    private fun resolveArcadeIcon(context: Context, game: GameEntry): String? {
        val path = game.romPath ?: return null
        // Only attempt extraction for local file paths (not content:// URIs)
        if (!path.startsWith("/")) return game.coverPath?.takeIf { File(it).exists() }

        val zipFile = File(path)
        if (!zipFile.exists() || !zipFile.name.endsWith(".zip", ignoreCase = true)) {
            return game.coverPath?.takeIf { File(it).exists() }
        }

        // Check cache first
        val iconsDir = File(context.filesDir, "icons").apply { mkdirs() }
        val cachedIcon = File(iconsDir, "${game.id}.png")
        if (cachedIcon.exists() && cachedIcon.length() > 0) {
            return cachedIcon.absolutePath
        }

        // Try to find a .png in the zip
        try {
            ZipFile(zipFile).use { zip ->
                val pngEntry = zip.entries().asSequence()
                    .firstOrNull { entry ->
                        !entry.isDirectory &&
                        entry.name.endsWith(".png", ignoreCase = true) &&
                        entry.size in 100..500_000  // sanity check
                    }
                if (pngEntry != null) {
                    zip.getInputStream(pngEntry).use { input ->
                        cachedIcon.outputStream().use { output -> input.copyTo(output) }
                    }
                    Log.i(TAG, "Extracted arcade icon for ${game.id} from ${zipFile.name}/${pngEntry.name}")
                    return cachedIcon.absolutePath
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract arcade icon from ${zipFile.name}: ${e.message}")
        }
        return game.coverPath?.takeIf { File(it).exists() }
    }

    /**
     * NDS / 3DS：优先用已下载封面（coverPath，可能是网络盒装图也可能是
     * 自动批持久化的内置图标）；无封面时现场从 ROM 提取 banner / SMDH
     * 图标并缓存到 <filesDir>/icons/<gameId>.png —— 满足“核心应该自动
     * 显示自带的封面”。本地路径与 SAF content:// 都支持。
     */
    private fun resolveRomBannerIcon(context: Context, game: GameEntry, is3ds: Boolean): String? {
        // 已有封面文件优先（网络盒装图/已持久化内置图）
        game.coverPath?.let { p -> if (File(p).exists() && File(p).length() > 0) return p }
        val path = game.romPath ?: return null
        val iconsDir = File(context.filesDir, "icons").apply { mkdirs() }
        val cachedIcon = File(iconsDir, "${game.id}.png")
        if (cachedIcon.exists() && cachedIcon.length() > 0) return cachedIcon.absolutePath
        try {
            val bitmap = if (path.startsWith("content://")) {
                context.contentResolver.openInputStream(android.net.Uri.parse(path))?.use { s ->
                    if (is3ds) RomBannerExtractor.extract3dsIcon(s) else RomBannerExtractor.extractNdsIcon(s)
                }
            } else {
                val f = File(path)
                if (!f.exists()) null
                else if (is3ds) RomBannerExtractor.extract3dsIcon(f) else RomBannerExtractor.extractNdsIcon(f)
            } ?: return null
            cachedIcon.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.i(TAG, "Extracted ${if (is3ds) "3DS SMDH" else "NDS banner"} icon for ${game.id}")
            return cachedIcon.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract ${if (is3ds) "3DS" else "NDS"} icon for ${game.id}: ${e.message}")
            return null
        }
    }

    /**
     * 供 CoverFetcher 自动批调用：提取 NDS/3DS 内置封面并直接落盘到
     * 指定目标文件（covers/<gameId>.png），成功返回 true。
     */
    fun extractRomBannerTo(context: Context, game: GameEntry, destFile: File): Boolean {
        val is3ds = game.platform == GamePlatform.N3DS
        val path = game.romPath ?: return false
        return try {
            val bitmap = if (path.startsWith("content://")) {
                context.contentResolver.openInputStream(android.net.Uri.parse(path))?.use { s ->
                    if (is3ds) RomBannerExtractor.extract3dsIcon(s) else RomBannerExtractor.extractNdsIcon(s)
                }
            } else {
                val f = File(path)
                if (!f.exists()) null
                else if (is3ds) RomBannerExtractor.extract3dsIcon(f) else RomBannerExtractor.extractNdsIcon(f)
            } ?: return false
            destFile.parentFile?.mkdirs()
            destFile.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "extractRomBannerTo failed for ${game.id}: ${e.message}")
            false
        }
    }

    /**
     * Generate a fallback cover bitmap for games without a built-in icon.
     * Creates a colored rectangle with the game title's first 1-2 characters
     * in white text, using the game's accent color as the background.
     *
     * This is used by GameCard when no icon is available, providing a more
     * visually appealing library than a generic gamepad icon.
     */
    fun generateFallbackCover(game: GameEntry, width: Int = 200, height: Int = 200): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background: accent color with a vertical gradient effect
        val accentColor = game.accent.toArgb()
        val darkColor = darkenColor(accentColor, 0.7f)
        val paint = Paint().apply {
            isAntiAlias = true
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                accentColor, darkColor,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Initials from title (1-2 chars)
        val title = game.title.trim()
        val initials = extractInitials(title)

        // Draw initials centered
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = width * 0.4f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initials, width / 2f, textY, textPaint)

        return bitmap
    }

    /**
     * Extract 1-2 character initials from a game title.
     * For Chinese titles, takes the first 1-2 characters.
     * For English titles, takes the first letter of the first 1-2 words.
     */
    private fun extractInitials(title: String): String {
        if (title.isBlank()) return "?"
        // For CJK titles (Chinese/Japanese/Korean), take first 2 chars
        val firstChar = title.first()
        if (firstChar.code > 0x2E80) {  // CJK Unified Ideographs start around 0x4E00
            return title.take(2)
        }
        // For Latin titles, take first letter of first 2 words
        val words = title.split(Regex("[\\s\\-_:]+"))
            .filter { it.isNotBlank() }
            .take(2)
        return if (words.isEmpty()) {
            title.take(1).uppercase()
        } else {
            words.joinToString("") { it.first().uppercaseChar().toString() }
        }
    }

    /**
     * Darken a color by the given factor (0..1).
     */
    private fun darkenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), r, g, b)
    }
}
