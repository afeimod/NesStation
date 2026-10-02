/**
 * NesStation 补丁 A：NDS / N3DS ROM 内置封面（banner / SMDH 图标）提取。
 *
 * 需求来源："nds和3ds以及java游戏自己都有封面的，核心应该自动显示
 * 自带的封面才对"。NDS ROM 头 0x68 偏移处存有 banner（32×32 4bpp 图标
 * + 16 色调色板 + 多语言标题）；3DS 卡带镜像 (.3ds/.cci) 在 0x20C0 处
 * 存有 SMDH（48×48 RGB565 图标 + 多语言标题）。二者无需联网即可获得
 * 游戏自带封面。
 *
 * 输出：ARGB_8888 Bitmap（放回 GameIconExtractor 保存为 PNG 缓存）。
 */
package com.nesstation.app.core.storage

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

object RomBannerExtractor {

    /** NDS banner：icon_offset 在 ROM 头 0x68（u32 LE）。 */
    fun extractNdsIcon(rom: File): Bitmap? = try {
        RandomAccessFile(rom, "r").use { raf ->
            val header = ByteArray(0x200)
            raf.readFully(header)
            val bannerOff = readU32LE(header, 0x68).toInt()
            // 合法性：banner 位于头之后、文件内，且完整 v1 banner（0x840）可读
            if (bannerOff < 0x200 || bannerOff + 0x840 > raf.length()) return@use null
            raf.seek(bannerOff.toLong())
            val banner = ByteArray(0x260)   // 版本2 + CRC + 保留 + 图标(0x20..0x240) + 调色板(0x240..0x260)
            raf.readFully(banner)
            val version = readU16LE(banner, 0)
            if (version !in 1..3) return@use null
            decodeNdsIcon(banner)
        }
    } catch (_: Throwable) {
        null
    }

    /** 3DS SMDH：CCI (.3ds/.cci) 镜像固定偏移 0x20C0。 */
    fun extract3dsIcon(rom: File): Bitmap? = try {
        RandomAccessFile(rom, "r").use { raf ->
            if (raf.length() < 0x20C0 + 0x1208) return@use null
            raf.seek(0x20C0)
            val smdh = ByteArray(0x1208)    // 版本 + 保留 + 48×48 图标（0x08..0x1208）
            raf.readFully(smdh)
            val version = readU16LE(smdh, 0)
            if (version != 0x0400) return@use null
            decodeSmdhIcon(smdh)
        }
    } catch (_: Throwable) {
        null
    }

    /** SAF content:// 支持：流式读取（NDS 头+banner / 3DS SMDH）。 */
    fun extractNdsIcon(stream: InputStream): Bitmap? = try {
        stream.use { s ->
            val header = readExactly(s, 0x200) ?: return null
            val bannerOff = readU32LE(header, 0x68).toInt()
            if (bannerOff < 0x200) return null
            skipFully(s, (bannerOff - 0x200).toLong())
            val banner = readExactly(s, 0x260) ?: return null
            val version = readU16LE(banner, 0)
            if (version !in 1..3) return null
            decodeNdsIcon(banner)
        }
    } catch (_: Throwable) {
        null
    }

    fun extract3dsIcon(stream: InputStream): Bitmap? = try {
        stream.use { s ->
            skipFully(s, 0x20C0)
            val smdh = readExactly(s, 0x1208) ?: return null
            if (readU16LE(smdh, 0) != 0x0400) return null
            decodeSmdhIcon(smdh)
        }
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------
    // 解码
    // ------------------------------------------------------------------

    /**
     * NDS banner 图标：32×32，4×4 个 8×8 tile（行优先），4bpp 索引色，
     * 16 色 BGR555 调色板（banner+0x240）。
     */
    private fun decodeNdsIcon(banner: ByteArray): Bitmap? {
        val palette = IntArray(16)
        for (i in 0 until 16) {
            val raw = readU16LE(banner, 0x240 + i * 2)
            palette[i] = bgr555ToArgb(raw)
        }
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(32 * 32)
        // 16 个 tile：行优先 4×4
        for (tile in 0 until 16) {
            val tx = (tile % 4) * 8
            val ty = (tile / 4) * 8
            for (k in 0 until 64) {           // tile 内 8×8 行优先
                val byteIdx = 0x20 + tile * 64 + (k / 2)
                if (byteIdx >= banner.size) return null
                val b = banner[byteIdx].toInt() and 0xFF
                val idx = if (k % 2 == 0) b and 0x0F else (b shr 4) and 0x0F
                pixels[tx + k % 8 + (ty + k / 8) * 32] = palette[idx]
            }
        }
        bmp.setPixels(pixels, 0, 32, 0, 0, 32, 32)
        return bmp
    }

    /**
     * 3DS SMDH 大图标：48×48，6×6 个 8×8 tile（行优先），RGB565。
     */
    private fun decodeSmdhIcon(smdh: ByteArray): Bitmap? {
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(48 * 48)
        for (tile in 0 until 36) {
            val tx = (tile % 6) * 8
            val ty = (tile / 6) * 8
            for (k in 0 until 64) {
                val off = 0x08 + tile * 128 + k * 2
                if (off + 1 >= smdh.size) return null
                val raw = readU16LE(smdh, off)
                pixels[tx + k % 8 + (ty + k / 8) * 48] = rgb565ToArgb(raw)
            }
        }
        bmp.setPixels(pixels, 0, 48, 0, 0, 48, 48)
        return bmp
    }

    // ------------------------------------------------------------------
    // 基础工具
    // ------------------------------------------------------------------

    private fun readU16LE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 0) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(b: ByteArray, off: Int): Long =
        ((b[off].toInt() and 0xFF).toLong() shl 0) or
        ((b[off + 1].toInt() and 0xFF).toLong() shl 8) or
        ((b[off + 2].toInt() and 0xFF).toLong() shl 16) or
        ((b[off + 3].toInt() and 0xFF).toLong() shl 24)

    private fun bgr555ToArgb(v: Int): Int {
        val r = (v and 0x1F) shl 3
        val g = ((v shr 5) and 0x1F) shl 3
        val b = ((v shr 10) and 0x1F) shl 3
        return Color.argb(0xFF, r, g, b)
    }

    private fun rgb565ToArgb(v: Int): Int {
        val r = ((v shr 11) and 0x1F) shl 3
        val g = ((v shr 5) and 0x3F) shl 2
        val b = (v and 0x1F) shl 3
        return Color.argb(0xFF, r, g, b)
    }

    private fun readExactly(s: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = s.read(buf, read, n - read)
            if (r < 0) return null
            read += r
        }
        return buf
    }

    private fun skipFully(s: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = s.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (s.read() < 0) return
                remaining -= 1
            }
        }
    }
}
