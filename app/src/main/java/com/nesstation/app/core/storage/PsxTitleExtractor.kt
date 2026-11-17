package com.nesstation.app.core.storage

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Extracts real PSX game titles from ISO/CUE/CHD file headers.
 *
 * PSX ISO images store the game title in the ISO 9660 Primary Volume
 * Descriptor. The title is a 32-character ASCII string at a known offset.
 * This utility reads that title so the game library shows real names
 * (e.g. "FINAL FANTASY VII") instead of filenames (e.g. "FF7.iso").
 *
 * Supported formats:
 *   .iso — reads ISO 9660 volume descriptor
 *   .cue — parses CUE sheet for FILE/INDEX entries, then reads the
 *          referenced .bin/.iso for the title
 *   .chd — reads CHD header (limited support)
 *
 * For SAF (content://) URIs, falls back to filename since binary reading
 * from SAF during scanning is unreliable.
 */
object PsxTitleExtractor {

    /**
     * Extract the real game title from a PSX ROM file.
     * Returns null if extraction fails (caller should fall back to filename).
     */
    fun extractTitle(context: Context, romPath: String): String? {
        val lower = romPath.lowercase()
        return when {
            lower.endsWith(".iso") -> extractFromIso(romPath)
            lower.endsWith(".cue") -> extractFromCue(romPath)
            lower.endsWith(".chd") -> extractFromChd(romPath)
            else -> null
        }
    }

    /**
     * Extract title from a local .iso file by reading the ISO 9660
     * Primary Volume Descriptor.
     */
    private fun extractFromIso(path: String): String? {
        val file = File(path)
        if (!file.exists() || file.length() < 0x8840L) return null

        try {
            // Try multiple known offsets for PSX game titles:
            // 0x8800 — System CN area (most common for PSX)
            // 0x8040 — ISO 9660 Primary Volume Descriptor, Volume Identifier
            val offsets = listOf(0x8800, 0x8040)

            for (offset in offsets) {
                val title = readAsciiAt(file, offset, 32)
                if (title.isNotBlank()) {
                    return title.trim()
                }
            }
        } catch (_: Exception) { }
        return null
    }

    /**
     * Extract title from a .cue file by:
     * 1. Parsing the CUE sheet to find the referenced .bin/.iso file
     * 2. Reading the title from that file's ISO header
     */
    private fun extractFromCue(cuePath: String): String? {
        val cueFile = File(cuePath)
        if (!cueFile.exists()) return null

        try {
            val cueContent = cueFile.readText(charset("UTF-8"))
            val lines = cueContent.split("\n", "\r\n", "\r")

            // Look for FILE "xxx.bin" BINARY or FILE "xxx.iso" BINARY
            val filePattern = Regex("""FILE\s+["']?([^"'\s]+)["']?\s+(?:BINARY|BINARY|MODE1|MODE2)""", RegexOption.IGNORE_CASE)
            for (line in lines) {
                val match = filePattern.find(line)
                if (match != null) {
                    val refName = match.groupValues[1]
                    val refPath = File(cueFile.parent ?: "", refName).absolutePath
                    if (refPath.lowercase().endsWith(".iso") || refPath.lowercase().endsWith(".bin")) {
                        val title = extractFromIso(refPath)
                        if (title != null) return title
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    /**
     * Extract title from a .chd file. CHD headers store the source file
     * name which sometimes contains the game title.
     */
    private fun extractFromChd(path: String): String? {
        val file = File(path)
        if (!file.exists() || file.length() < 128L) return null

        try {
            // CHD header: magic "CHD\0" at offset 0, then various fields.
            // The source filename is at offset 0x40 (64 bytes).
            val title = readAsciiAt(file, 0x40, 64)
            if (title.isNotBlank()) {
                // CHD source name might be like "game.bin" — extract base name
                val baseName = title.substringBeforeLast('.')
                if (baseName.length > 2) return baseName
            }
        } catch (_: Exception) { }
        return null
    }

    /**
     * Read up to `maxLen` ASCII characters from a file at the given offset.
     * Stops at first null byte or non-printable character.
     */
    private fun readAsciiAt(file: File, offset: Int, maxLen: Int): String {
        file.inputStream().use { is_ ->
            is_.skip(offset.toLong())
            val buf = ByteArray(maxLen)
            val read = is_.read(buf, 0, maxLen)
            if (read <= 0) return ""

            val sb = StringBuilder()
            for (i in 0 until read) {
                val ch = buf[i].toInt() and 0xFF
                if (ch == 0 || ch < 0x20 || ch > 0x7E) break
                sb.append(ch.toChar())
            }
            return sb.toString()
        }
    }
}
