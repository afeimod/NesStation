package com.nesstation.app.core.storage

import java.io.File
import java.io.RandomAccessFile

/**
 * iNES header maintenance for the FC core, kept in lockstep with the
 * in-memory policy in core/jni/rom_loader.cpp.
 *
 * Two operations, both NON-DESTRUCTIVE (RandomAccessFile in-place writes —
 * the file is NEVER truncated; an earlier version of this class used
 * File.outputStream(), whose constructor TRUNCATES the file, and could
 * destroy a ROM down to its 16-byte header):
 *
 * 1) INFLATE (legacy behaviour, now whitelisted) — pirate multicart dumps
 *    (COOLBOY / MINDKIDS, mappers 268/269) whose PRG size byte lies about
 *    the real mask-ROM size. Inflating PRG to cover the whole file is ONLY
 *    allowed for those mappers; inflating ordinary games (Captain Tsubasa
 *    hacks etc. carrying trailing append data) is FORBIDDEN — folding the
 *    appendix into PRG makes emulators pad PRG to the next power of two,
 *    which moves the MMC3 fixed $C000-$FFFF window into the 0xFF filler,
 *    breaks the reset vector and produces the permanent gray screen
 *    (verified experimentally and differentially against
 *    NostalgiaLite/FCEUX).
 *
 * 2) REPAIR (new) — files whose header PRG size is NOT a power of two
 *    (e.g. 80/96 x 16KB): either born mis-sized (Chinese hack dumps like
 *    the 天使之翼2 改版 family) or corrupted by the old un-whitelisted
 *    patcher of this very class. No header-faithful engine can boot such a
 *    ROM (fixed window lands in the pow2 padding; reset vector = 0xFFFF).
 *    The MMC3/195-class boards of this family decode at most 512KB-1MB of
 *    PRG anyway, so snapping the declared PRG size down to the largest
 *    power of two that still fits the file restores a bootable layout and
 *    the ines-correct CRC routing. Runs automatically before every FC game
 *    launch, so ROMs already damaged on disk heal on the next start.
 */
object InesHeaderPatcher {

    private val INFLATE_WHITELIST = setOf(268, 269) // COOLBOY / MINDKIDS, Games Xplosion

    fun patchIfNeeded(file: File): String {
        android.util.Log.i("InesHeaderPatcher",
            "patchIfNeeded: file=${file.absolutePath}, size=${file.length()}")
        if (!file.exists()) return "file not found"
        val size = file.length()
        if (size < 16) return "file too small"

        val header = ByteArray(16)
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(header)
            }
        } catch (e: Exception) {
            return "header read error: ${e.message}"
        }

        if (header[0] != 0x4E.toByte() || header[1] != 0x45.toByte() ||
            header[2] != 0x53.toByte() || header[3] != 0x1A.toByte()) {
            return "not iNES format (no patch needed)"
        }

        val isNES2 = (header[7].toInt() and 0x0C) == 0x08
        var mapper = ((header[6].toInt() and 0xF0) shr 4) or
                     (header[7].toInt() and 0xF0)
        if (isNES2) {
            mapper = mapper or ((header[8].toInt() and 0x0F) shl 8)
        }
        val prgUnits = (header[4].toInt() and 0xFF).let { if (it == 0 && !isNES2) 256 else it }
        val chrBytes = (header[5].toInt() and 0xFF) * 8 * 1024

        // ---- 1) whitelisted multicart INFLATE (mappers 268 / 269 only) ----
        if (INFLATE_WHITELIST.contains(mapper)) {
            val hdrPrgBytes = prgUnits * 16 * 1024
            val hasTrainer = (header[6].toInt() and 0x04) != 0
            val headerClaimedSize = 16L + (if (hasTrainer) 512L else 0L) +
                                    hdrPrgBytes + chrBytes
            if (size <= headerClaimedSize + 16 * 1024) {
                return "no patch needed (size=$size, claimed=$headerClaimedSize)"
            }
            val extraBytes = size - headerClaimedSize
            val newPrgBytes = hdrPrgBytes.toLong() + extraBytes
            var prgUnitsNew = (newPrgBytes + 16L * 1024L - 1L) / (16L * 1024L)
            if (prgUnitsNew > 0xEFFL) prgUnitsNew = 0xEFFL
            val prgUnitsInt = prgUnitsNew.toInt()
            header[4] = (prgUnitsInt and 0xFF).toByte()
            val highNibble = (prgUnitsInt shr 8) and 0x0F
            if (highNibble > 0) {
                // keep the NES 2.0 marker so byte 9's low nibble is honoured
                header[7] = ((header[7].toInt() and 0xF3) or 0x08).toByte()
                header[9] = ((header[9].toInt() and 0xF0) or highNibble).toByte()
                // mapper bits 8-11 live in byte 8 under NES 2.0; the multicart
                // mappers 268/269 need them kept intact — only garbage there
                // would corrupt the ID, and 268/269 dumps carry valid values,
                // so byte 8 is left untouched here.
            }
            return try {
                writeHeaderInPlace(file, header)
                "PATCHED(268/269 inflate): file=${size}B claimed=$headerClaimedSize " +
                    "newPRG=$prgUnitsInt units"
            } catch (e: Exception) {
                "header write failed: ${e.message}"
            }
        }

        // ---- 2) non-pow2 PRG snap-down REPAIR (legacy headers, mapper < 256) ----
        if (!isNES2 && mapper < 256) {
            val isPow2 = prgUnits != 0 && (prgUnits and (prgUnits - 1)) == 0
            if (!isPow2) {
                var snapped = prgUnits
                while (snapped > 1 &&
                    (16L + chrBytes + snapped.toLong() * 16384L) > size) {
                    snapped = snapped shr 1
                }
                // largest power of two <= snapped (clear lowest set bit for
                // non-pow2 inputs, e.g. 80 -> 64, 96 -> 64, 48 -> 32)
                while (snapped > 1 && (snapped and (snapped - 1)) != 0) {
                    snapped = snapped and (snapped - 1)
                }
                if (snapped in 1 until prgUnits) {
                    header[4] = snapped.toByte()
                    return try {
                        writeHeaderInPlace(file, header)
                        "REPAIRED: PRG=$prgUnits units (non-pow2) -> $snapped units " +
                            "(file=$size, chr=${chrBytes / 1024}KB, mapper=$mapper)"
                    } catch (e: Exception) {
                        "header write failed: ${e.message}"
                    }
                }
                return "no repair possible (prg=$prgUnits, size=$size)"
            }
        }

        return "no patch needed (size=$size, mapper=$mapper, prg=$prgUnits)"
    }

    /** In-place write of the first 16 bytes. NEVER truncates the file. */
    private fun writeHeaderInPlace(file: File, header: ByteArray) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header, 0, 16)
        }
    }
}
