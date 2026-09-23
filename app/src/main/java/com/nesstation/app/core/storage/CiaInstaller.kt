package com.nesstation.app.core.storage

import android.content.Context
import com.nesstation.app.core.jni.AzaharNative
import java.io.File

/**
 * 3DS CIA 安装与 NCCH 解密工具。
 *
 * == CIA 安装 ==
 * 调核心原生安装器（org.citra.citra_emu.utils.CiaInstallWorker.installCIA ——
 * libctru/am 服务全流程，自动解密已导入密钥的加密 CIA），安装结果即
 * NativeLibrary.InstallStatus。安装完成后游戏出现在
 * AzaharNative.installedGamePaths()（NAND 标题），可在游戏列表直接启动。
 *
 * == 3DS 解密 ==
 * 独立 NCCH/CIA 解密器（[NcchDecryptor]）：
 *  - 密钥来自 aes_keys.txt（Azahar 格式，slot 0x2C 主密钥 / 0x40+ 二级）
 *  - NCCH KeyY = 文件头 RSA-2048 签名前 16 字节
 *  - KeyNormal = AES-ECB 解密（KeyY 为密钥，KeyX 为数据）
 *  - CTR：partitionId(8BE) || nonce(0/1/2) || 块偏移(4BE)
 *  - ExeFS 头 0x200 用 Normal Key，其余用 ExeFS Key（7.x 加密时为二级密钥）
 *  - 固定密钥（flags[3]&4）与 seed 加密（crypto 0xA/0x11）分别处理/拒绝
 *
 * 解密产物写入 <download>/NesStation/decrypted/，可直接作为解密版游戏导入。
 */
object CiaInstaller {

    /** 安装结果（对齐 NativeLibrary.InstallStatus 名 + 本地错误）。 */
    data class InstallResult(val ok: Boolean, val message: String)

    /** CIA 安装（阻塞；调用方自备后台线程）。 */
    fun installCia(context: Context, path: String): InstallResult {
        if (!AzaharNative.ensureLoaded()) {
            return InstallResult(false, "3DS 核心加载失败（需要 arm64 设备）")
        }
        AzaharNative.ensureUserDirectory()
        val status = AzaharNative.installCia(path)
        return when (status) {
            "Success" -> InstallResult(true, "安装成功")
            "ErrorEncrypted" -> InstallResult(
                false,
                "CIA 已加密：请先在 3DS 设置页导入 aes_keys.txt（或先用「解密」工具）"
            )
            "ErrorFileNotFound" -> InstallResult(false, "文件不存在")
            "ErrorFailedToOpenFile" -> InstallResult(false, "无法打开文件")
            "ErrorAborted" -> InstallResult(false, "安装被中止")
            "ErrorInvalid" -> InstallResult(false, "不是有效的 CIA 文件")
            "Cancelled" -> InstallResult(false, "已取消")
            else -> InstallResult(false, "安装失败：$status")
        }
    }

    /** 已安装标题路径列表。 */
    fun installedTitles(): List<String> = AzaharNative.installedGamePaths()

    /** 解密单个 3DS 文件（.3ds/.cci/.cxi/.cia）。 */
    fun decrypt(context: Context, srcPath: String): DecryptResult {
        if (!AzaharNative.ensureLoaded()) {
            return DecryptResult(false, "3DS 核心加载失败（需要 arm64 设备）")
        }
        AzaharNative.ensureUserDirectory()
        val keysText = AzaharDirs.readKeys(context)
            ?: return DecryptResult(false, "尚未导入 aes_keys.txt：请先在 3DS 设置页导入解密密钥")
        val keys = NcchDecryptor.parseAesKeys(keysText)
            ?: return DecryptResult(false, "aes_keys.txt 解析失败（Azahar 格式：slot=hexkey）")
        val outDir = decryptedDir(context)
        return try {
            NcchDecryptor.decryptFile(File(srcPath), outDir, keys)
        } catch (t: Throwable) {
            DecryptResult(false, "解密失败：${t.message}")
        }
    }

    fun decryptedDir(context: Context): File {
        val base = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val dir = File(File(base, "NesStation"), "decrypted")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    data class DecryptResult(val ok: Boolean, val message: String)

    /** 游戏是否加密（GameInfo.isEncrypted —— 非法/缺失文件返回 false）。 */
    fun isEncrypted(path: String): Boolean = AzaharNative.isEncrypted(path)
}

/**
 * 纯 Kotlin NCCH/CIA 解密器（javax.crypto AES-CTR/ECB，无需 native）。
 */
object NcchDecryptor {

    /** aes_keys.txt 键表（slot -> key bytes）。 */
    private val keySlotRegex = Regex("^(\\w+)\\s*=\\s*([0-9a-fA-F]{32})\\s*$")

    class Keys(val map: MutableMap<Int, ByteArray> = mutableMapOf())

    fun parseAesKeys(content: String): Keys? {
        val keys = Keys()
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val m = keySlotRegex.find(line) ?: continue
            val slot = m.groupValues[1].lowercase().toIntOrNull(16) ?: continue
            keys.map[slot] = m.groupValues[2].hexToBytes()
        }
        // 至少要有主密钥 0x2C（零售）或 0x25（开发机）
        return if (keys.map.containsKey(0x2C) || keys.map.containsKey(0x25)) keys else null
    }

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }

    data class NcchInfo(
        val dataOffset: Long,      // NCCH 在文件中的起始偏移
        val dataSize: Long,        // NCCH 总长
        val partitionId: Long,     // 0x108 (8 bytes BE)
        val flags: ByteArray,      // 0x168 (8 bytes)
        val exheaderSize: Long,    // 0x160
        val normalSize: Long,      // 0x170
        val exefsOffset: Long,     // 0x1A0
        val exefsSize: Long,
        val exefsHashRegionSize: Long, // 0x198
        val romfsOffset: Long,     // 0x1C0
        val romfsSize: Long
    )

    private const val MEDIA_UNIT = 0x200L

    fun parseNcch(data: ByteArray, offset: Long, size: Long): NcchInfo? {
        if (offset + 0x200 > data.size) return null
        if (data[offset.toInt() + 0x100] != 'N'.code.toByte() ||
            data[offset.toInt() + 0x101] != 'C'.code.toByte() ||
            data[offset.toInt() + 0x102] != 'C'.code.toByte() ||
            data[offset.toInt() + 0x103] != 'H'.code.toByte()
        ) return null
        fun u32(off: Int): Long {
            val v = ((data[offset.toInt() + off].toLong() and 0xFF) shl 24) or
                ((data[offset.toInt() + off + 1].toLong() and 0xFF) shl 16) or
                ((data[offset.toInt() + off + 2].toLong() and 0xFF) shl 8) or
                (data[offset.toInt() + off + 3].toLong() and 0xFF)
            return v
        }
        var partitionId = 0L
        for (i in 0 until 8) {
            partitionId = (partitionId shl 8) or (data[offset.toInt() + 0x108 + i].toLong() and 0xFF)
        }
        val flags = ByteArray(8) { data[offset.toInt() + 0x168 + it] }
        val normalSize = u32(0x170) * MEDIA_UNIT
        val exefsSize = u32(0x194) * MEDIA_UNIT
        val exefsHashRegionSize = u32(0x198) * MEDIA_UNIT
        val romfsSize = u32(0x1C0) * MEDIA_UNIT
        val exheaderSize = u32(0x160).toLong()
        return NcchInfo(
            dataOffset = offset,
            dataSize = size,
            partitionId = partitionId,
            flags = flags,
            exheaderSize = exheaderSize,
            normalSize = normalSize,
            exefsOffset = 0x1A0L,
            exefsSize = exefsSize,
            exefsHashRegionSize = exefsHashRegionSize,
            romfsOffset = 0x1C0L,
            romfsSize = romfsSize
        )
    }

    /** 解密整个文件（.3ds 最多 8 个 NCCH 分区；.cia 逐内容解密）。 */
    fun decryptFile(src: File, outDir: File, keys: Keys): CiaInstaller.DecryptResult {
        if (!src.exists()) return CiaInstaller.DecryptResult(false, "文件不存在")
        val data = src.inputStream().use { it.readBytes() }
        val outName = src.nameWithoutExtension + "_decrypted" +
            (src.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: "")
        val dst = File(outDir, outName)
        val out = ByteArray(data.size)
        var decryptedAny = false

        if (data.size >= 0x104 && data[0x100] == 'N'.code.toByte() &&
            data[0x101] == 'C'.code.toByte() && data[0x102] == 'C'.code.toByte() && data[0x103] == 'H'.code.toByte()
        ) {
            // 单 NCCH（.3ds/.cci/.cxi）
            System.arraycopy(data, 0, out, 0, data.size)
            val info = parseNcch(data, 0, data.size.toLong())
                ?: return CiaInstaller.DecryptResult(false, "NCCH 头解析失败")
            val r = decryptNcch(data, out, info, keys)
            if (!r.ok) return r
            decryptedAny = true
        } else if (data.size >= 0x104 && data[0] == 'C'.code.toByte() &&
            data[1] == 'I'.code.toByte() && data[2] == 'A'.code.toByte() && data[3] == '\u0000'.code.toByte()
        ) {
            // CIA：解析内容段并逐个解密 NCCH
            System.arraycopy(data, 0, out, 0, data.size)
            val r = decryptCia(data, out, keys)
            if (!r.ok) return r
            decryptedAny = true
        } else {
            return CiaInstaller.DecryptResult(false, "无法识别的文件（既不是 NCCH 也不是 CIA）")
        }

        if (!decryptedAny) {
            return CiaInstaller.DecryptResult(false, "没有需要解密的分区（可能已解密）")
        }
        dst.outputStream().use { it.write(out) }
        return CiaInstaller.DecryptResult(true, "已解密 → ${dst.absolutePath}")
    }

    private fun decryptCia(data: ByteArray, out: ByteArray, keys: Keys): CiaInstaller.DecryptResult {
        fun u32(off: Int): Long = ((data[off].toLong() and 0xFF) shl 24) or
            ((data[off + 1].toLong() and 0xFF) shl 16) or
            ((data[off + 2].toLong() and 0xFF) shl 8) or
            (data[off + 3].toLong() and 0xFF)
        val headerSize = u32(0)
        val certSize = u32(0x8)
        val ticketSize = u32(0xC)
        val tmdSize = u32(0x10)
        val contentSize = u32(0x14)
        val contentBase = headerSize + certSize + ticketSize + tmdSize
        if (contentBase + contentSize > data.size) {
            return CiaInstaller.DecryptResult(false, "CIA 内容区越界")
        }
        // 逐内容扫描 NCCH 头（内容对齐 64 字节）
        var pos = contentBase
        val end = contentBase + contentSize
        while (pos + 0x200 <= end) {
            if (data[pos.toInt() + 0x100] == 'N'.code.toByte() &&
                data[pos.toInt() + 0x101] == 'C'.code.toByte() &&
                data[pos.toInt() + 0x102] == 'C'.code.toByte() &&
                data[pos.toInt() + 0x103] == 'H'.code.toByte()
            ) {
                val info = parseNcch(data, pos, end - pos) ?: break
                // NCCH 大小 = 各分区之和（取 romfs 尾部估算）
                val ncchEnd = minOf(end, pos + 0x1C0 + 0x200 + info.romfsSize)
                val r = decryptNcch(data, out, info, keys)
                if (!r.ok) return r
                pos = ncchEnd
            } else {
                pos += 0x40
            }
        }
        return CiaInstaller.DecryptResult(true, "OK")
    }

    /** 解密单个 NCCH（data → out 原位替换加密区）。 */
    private fun decryptNcch(
        data: ByteArray, out: ByteArray, info: NcchInfo, keys: Keys
    ): CiaInstaller.DecryptResult {
        val base = info.dataOffset
        val keyY = data.copyOfRange(base.toInt(), base.toInt() + 16)
        val fixedKey = (info.flags[3].toInt() and 0x04) != 0
        val method = info.flags[7].toInt() and 0xFF
        if (method >= 0x0A) {
            return CiaInstaller.DecryptResult(
                false, "不支持 seed 加密（crypto=0x${method.toString(16)}）：请用核心直接启动"
            )
        }

        // ---- KeyNormal 派生 ----
        val keyXSlot = if (keys.map.containsKey(0x2C)) 0x2C else 0x25
        val keyX = keys.map[keyXSlot]
            ?: return CiaInstaller.DecryptResult(false, "aes_keys.txt 缺少主密钥 slot 0x${keyXSlot.toString(16)}")
        val primaryKey = if (fixedKey) {
            ByteArray(16) // 固定密钥模式 KeyNormal = 0
        } else {
            Aes.decryptEcb(keyX, keyY) // KeyNormal = AES-DEC(KeyX, Key=KeyY)
        }

        // ---- 二级密钥（7.x RomFS） ----
        val secondaryKey = if (method in 1..9) {
            val slot2 = keyXSlot + method
            val kx = keys.map[slot2]
            if (kx != null && !fixedKey) Aes.decryptEcb(kx, keyY) else primaryKey
        } else primaryKey

        fun counter(nonce: Long, blockOffset: Long): ByteArray {
            val c = ByteArray(16)
            var pid = info.partitionId
            for (i in 7 downTo 0) {
                c[i] = (pid and 0xFF).toByte()
                pid = pid ushr 8
            }
            // 8..11 = nonce (BE)
            var n = nonce
            for (i in 11 downTo 8) {
                c[i] = (n and 0xFF).toByte()
                n = n ushr 8
            }
            // 12..15 = block offset (BE, unit = 0x10)
            var bo = blockOffset
            for (i in 15 downTo 12) {
                c[i] = (bo and 0xFF).toByte()
                bo = bo ushr 8
            }
            return c
        }

        fun decryptRegion(offset: Long, size: Long, nonce: Long, key: ByteArray) {
            if (size <= 0 || offset + size > data.size) return
            val blockUnits = offset / 0x10
            val ctr = counter(nonce, blockUnits)
            Aes.decryptCtr(key, ctr, data, out, offset, size)
        }

        // Normal / ExHeader：nonce = 0
        val normalOffset = 0x200L
        val normalSize = info.exheaderSize + info.normalSize
        decryptRegion(base + normalOffset, normalSize, 0, primaryKey)

        // ExeFS：nonce = 1；头 0x200 用 Normal Key，其余用 ExeFS Key
        val exefsAbs = base + info.exefsOffset
        val headerSize = minOf(info.exefsHashRegionSize, 0x200L)
        decryptRegion(exefsAbs, headerSize, 1, primaryKey)
        if (info.exefsSize > 0x200) {
            decryptRegion(exefsAbs + 0x200, info.exefsSize - 0x200, 1, secondaryKey)
        }

        // RomFS：nonce = 2（7.x 加密用二级密钥）
        val romfsKey = if (method >= 1) secondaryKey else primaryKey
        decryptRegion(base + info.romfsOffset, info.romfsSize, 2, romfsKey)

        return CiaInstaller.DecryptResult(true, "OK")
    }
}

/** AES 原语（javax.crypto / AES-CTR 手工计数器递增）。 */
private object Aes {

    fun decryptEcb(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    /** CTR 模式解密（密文即 keystream 异或结果；解密=加密）。 */
    fun decryptCtr(
        key: ByteArray, counter: ByteArray,
        src: ByteArray, dst: ByteArray, offset: Long, size: Long
    ) {
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
        val ctr = counter.copyOf()
        val stream = ByteArray(16)
        var pos = offset
        val end = offset + size
        while (pos < end) {
            cipher.doFinal(ctr, 0, 16, stream)
            val chunk = minOf(16L, end - pos).toInt()
            for (i in 0 until chunk) {
                dst[(pos + i).toInt()] =
                    (src[(pos + i).toInt()].toInt() xor stream[i].toInt()).toByte()
            }
            pos += chunk
            incrementCounter(ctr)
        }
    }

    private fun incrementCounter(ctr: ByteArray) {
        for (i in 15 downTo 12) {
            val v = (ctr[i].toLong() and 0xFF) + 1
            ctr[i] = v.toByte()
            if (v < 0x100) break
        }
    }
}
