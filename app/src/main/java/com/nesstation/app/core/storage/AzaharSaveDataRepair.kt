package com.nesstation.app.core.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * ★★ 3DS 存档数据体检/修复（"部分游戏建档卡死"前端侧根治）★★
 *
 * 用户实测（时之笛 3D，Title 00040000/000afd00，核心日志）：
 *
 *   [7.089804] Service.FS <Error> savedata_archive.cpp:OpenFile:76:
 *     Non-existing file /sdmc/Nintendo 3DS/.../title/00040000/000afd00/
 *     data/00000001/sys can't be open without mode create.
 *   ... slot_0 ... slot_1 同错
 *   —— 之后核心日志再无输出（模拟线程卡死）
 *
 * 机理：Azahar（libazahar.so 预编译，不可改）的存档格式化流程是
 * "先建目录、再写文件"两步。上一局在建档中途被退出/杀死（或写文件
 * 阶段失败）时，会留下**只有目录、没有文件**的半格式化存档：
 *
 *   <userDir>/sdmc/Nintendo 3DS/<id0>/<id1>/title/<高8位>/<低8位>/
 *     data/00000001/            ← 目录存在（GetFormat 判定"已格式化"）
 *       （sys / slot_0 / slot_1 等文件缺失）
 *
 * 下次进游戏：游戏问 FS "存档存在吗？" → 目录存在 → 判定已格式化 →
 * 跳过 FormatSaveData 直接 OpenFile 读 sys/slot_* → 文件不存在 →
 * 返回错误 → 部分游戏（时之笛 3D 等）在此后死等/死循环 → "建档卡死"。
 *
 * 前端侧修复（核心不可改，只能在 Kotlin 层把坏状态清掉）：
 *
 *   1. [repairIncompleteSaveData] —— 每次启动 3DS 游戏前（loadRom 之前）
 *      扫描全部 sdmc title 存档目录：data/<id> 目录存在但**内部递归
 *      无任何文件**（真实存档必有文件）→ 判定半格式化 → 删除该目录，
 *      让核心/游戏重新走完整格式化。只删"零文件"目录，绝不动任何
 *      含有真实存档文件的目录（删除无内容的空目录无数据损失）。
 *
 *   2. [clearGameSaveData] + [parseTitleId] —— 长按游戏菜单「清除 3DS
 *      存档」的兜底入口：从 ROM（NCCH 头）或已安装标题路径解析
 *      Title ID，按 id 删除该游戏的 sdmc title 数据目录（不动 NAND
 *      里的游戏本体）。用于顽固卡死/想重新建档的场景。
 */
object AzaharSaveDataRepair {

    private const val TAG = "Az3dsSaveRepair"

    /** Azahar 用户目录（= <filesDir>/azahar，与 AzaharEngine.userDir 一致）。 */
    fun userDir(context: Context): File = File(context.filesDir, "azahar")

    // ------------------------------------------------------------------
    // 1. 启动前自动修复：清理半格式化存档目录
    // ------------------------------------------------------------------

    /**
     * 扫描 sdmc 下全部 title 存档目录，删除"目录存在但无任何文件"的
     * 半格式化 data 目录。
     *
     * 目录结构（Citra/Azahar sdmc 布局）：
     *   sdmc/Nintendo 3DS/<id0>/<id1>/title/<TID高8>/<TID低8>/data/<存档id>/
     *     └─ 真实存档 = 内部有文件（sys / slot_0 / 00000001 …）
     *
     * @return 修复（删除）的目录数；0 = 无需修复。同时写 Logcat 便于回溯。
     */
    fun repairIncompleteSaveData(context: Context): Int {
        return try {
            repairIncompleteSaveData(userDir(context))
        } catch (t: Throwable) {
            Log.w(TAG, "repair scan failed: ${t.javaClass.simpleName}: ${t.message}")
            0
        }
    }

    private fun repairIncompleteSaveData(userDir: File): Int {
        // sdmc/Nintendo 3DS/<id0>/<id1>/title/
        val n3dsRoot = File(userDir, "sdmc/Nintendo 3DS")
        if (!n3dsRoot.isDirectory) return 0
        var fixed = 0
        val details = ArrayList<String>()
        // id0 / id1 两层（正常都是全 0，但按通配处理）
        for (id0 in n3dsRoot.listFiles { f -> f.isDirectory } ?: emptyArray()) {
            for (id1 in id0.listFiles { f -> f.isDirectory } ?: emptyArray()) {
                val titleRoot = File(id1, "title")
                if (!titleRoot.isDirectory) continue
                // title/<高8>/<低8>
                for (tidHigh in titleRoot.listFiles { f -> f.isDirectory } ?: emptyArray()) {
                    for (tidLow in tidHigh.listFiles { f -> f.isDirectory } ?: emptyArray()) {
                        val dataRoot = File(tidLow, "data")
                        if (!dataRoot.isDirectory) continue
                        for (dataId in dataRoot.listFiles { f -> f.isDirectory } ?: emptyArray()) {
                            if (dirHasNoFiles(dataId)) {
                                // 半格式化存档：目录在、文件全无 → 删除，
                                // 让游戏下次进入时重新走完整格式化。
                                if (deleteRecursively(dataId)) {
                                    fixed++
                                    if (details.size < 6) {
                                        details.add("${tidHigh.name}/${tidLow.name}/data/${dataId.name}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (fixed > 0) {
            Log.i(TAG, "removed $fixed incomplete 3DS save dir(s): ${details.joinToString()}")
        }
        return fixed
    }

    /** 目录树内不存在任何常规文件（空目录树）。 */
    private fun dirHasNoFiles(dir: File): Boolean {
        val children = dir.listFiles() ?: return true
        for (c in children) {
            if (c.isFile) return false
            if (c.isDirectory && !dirHasNoFiles(c)) return false
        }
        return true
    }

    private fun deleteRecursively(f: File): Boolean = try {
        f.deleteRecursively()
    } catch (_: Throwable) {
        false
    }

    // ------------------------------------------------------------------
    // 2. 手动清除：按 Title ID 删除该游戏 sdmc 存档
    // ------------------------------------------------------------------

    /**
     * 清除指定游戏的 3DS 存档（sdmc title 数据目录，含全部 data/<id>）。
     * 不触碰 NAND 里的游戏本体（已安装标题）与其它游戏。
     *
     * @param titleIdHex 16 位十六进制 Title ID（如 "00040000000afd00"），
     *        高 8 位目录 = 前 8 字符，低 8 位目录 = 后 8 字符。
     * @return true = 找到并删除了存档目录；false = 未找到（本来就没有存档）。
     */
    fun clearGameSaveData(context: Context, titleIdHex: String): Boolean {
        return try {
            clearGameSaveData(userDir(context), titleIdHex)
        } catch (t: Throwable) {
            Log.w(TAG, "clear save failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun clearGameSaveData(userDir: File, titleIdHexRaw: String): Boolean {
        val titleIdHex = titleIdHexRaw.trim().lowercase().removePrefix("0x")
        if (titleIdHex.length != 16 || titleIdHex.any { !it.isDigit() && it !in 'a'..'f' }) {
            Log.w(TAG, "invalid title id: $titleIdHexRaw")
            return false
        }
        val tidHigh = titleIdHex.substring(0, 8)
        val tidLow = titleIdHex.substring(8, 16)
        val n3dsRoot = File(userDir, "sdmc/Nintendo 3DS")
        if (!n3dsRoot.isDirectory) return false
        var removed = false
        for (id0 in n3dsRoot.listFiles { f -> f.isDirectory } ?: emptyArray()) {
            for (id1 in id0.listFiles { f -> f.isDirectory } ?: emptyArray()) {
                val titleDir = File(File(File(File(id1, "title"), tidHigh), tidLow), "data")
                if (titleDir.isDirectory) {
                    if (deleteRecursively(titleDir)) removed = true
                }
            }
        }
        Log.i(TAG, "clear save for $tidHigh/$tidLow: $removed")
        return removed
    }

    // ------------------------------------------------------------------
    // 3. Title ID 解析（NCCH 头 / 已安装标题路径）
    // ------------------------------------------------------------------

    /**
     * 从 ROM 解析 Title ID（支持真实路径与 content:// SAF URI）。
     *
     * - .cxi / .app / .cfa（NCCH 直接开头）：Title ID 在 NCCH 头 +0x108；
     * - .3ds / .cci（NCSD 卡带镜像）：分区表在头 +0x10（media 单位 0x200），
     *   游戏 NCCH 在分区 0 → Title ID = 分区偏移 + 0x108；
     * - 已安装标题（file://…/title/<高>/<低>/content/xxx.app）：直接从
     *   路径段取。
     *
     * @return 16 位小写十六进制 Title ID；解析失败返回 null。
     */
    fun parseTitleId(context: Context, romPath: String?): String? {
        if (romPath.isNullOrBlank()) return null
        return try {
            // 1) 路径里已有 title/<高8>/<低8>（CIA 安装产物）→ 直接取
            parseTitleIdFromPath(romPath)?.let { return it }
            // 2) NCCH / NCSD 头解析
            val head = readHeaderBytes(context, romPath, 0x4200) ?: return null
            parseTitleIdFromHeader(head)
        } catch (t: Throwable) {
            Log.w(TAG, "parse title id failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** 路径形如 …/title/00040000/000afd00/content/00000000.app → "00040000000afd00"。 */
    private fun parseTitleIdFromPath(path: String): String? {
        val marker = "title/"
        val idx = path.lastIndexOf(marker)
        if (idx < 0) return null
        val rest = path.substring(idx + marker.length)
        val segs = rest.split('/', '\\')
        if (segs.size < 3) return null
        val high = segs[0].lowercase()
        val low = segs[1].lowercase()
        val hex = (high + low)
        if (hex.length == 16 && hex.all { it.isDigit() || it in 'a'..'f' }) return hex
        return null
    }

    /** 头部字节 → Title ID（先按 CXI 直解，再按 CCI 分区偏移解）。 */
    private fun parseTitleIdFromHeader(head: ByteArray): String? {
        // NCCH 直开头（.cxi/.app）：magic "NCCH" @ 0x100，Title ID @ 0x108
        if (head.size >= 0x110 && isNcchAt(head, 0)) {
            return readTitleIdAt(head, 0x108)
        }
        // NCSD（.3ds/.cci）：分区表 @ 0x120（8 项 × u64：u32 偏移 + u32 长度，
        // media 单位 0x200）；分区 0 = 游戏本体 NCCH
        if (head.size >= 0x128) {
            val off = ((head[0x120].toInt() and 0xFF) or
                ((head[0x121].toInt() and 0xFF) shl 8) or
                ((head[0x122].toInt() and 0xFF) shl 16) or
                ((head[0x123].toInt() and 0xFF) shl 24)) * 0x200
            if (off >= 0 && off + 0x110 <= head.size && isNcchAt(head, off)) {
                return readTitleIdAt(head, off + 0x108)
            }
        }
        // 兜底：最常见的固定布局（分区 0 @ 0x4000）
        if (head.size >= 0x4110 && isNcchAt(head, 0x4000)) {
            return readTitleIdAt(head, 0x4000 + 0x108)
        }
        return null
    }

    private fun isNcchAt(head: ByteArray, off: Int): Boolean =
        off + 0x104 <= head.size &&
            head[off + 0x100] == 'N'.code.toByte() &&
            head[off + 0x101] == 'C'.code.toByte() &&
            head[off + 0x102] == 'C'.code.toByte() &&
            head[off + 0x103] == 'H'.code.toByte()

    private fun readTitleIdAt(head: ByteArray, off: Int): String? {
        if (off < 0 || off + 8 > head.size) return null
        val sb = StringBuilder(16)
        for (i in 7 downTo 0) {  // 小端 → 大端序十六进制
            val v = head[off + i].toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }

    /** 读 ROM 头部字节（真实文件或 content:// 均支持）。 */
    private fun readHeaderBytes(context: Context, romPath: String, len: Int): ByteArray? {
        return try {
            if (romPath.startsWith("content://")) {
                val ins: InputStream? = try {
                    context.contentResolver.openInputStream(Uri.parse(romPath))
                } catch (_: Throwable) {
                    null
                }
                ins?.use { s ->
                    val buf = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = s.read(buf, read, len - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read > 0x110) buf.copyOf(read) else null
                }
            } else {
                val f = File(romPath.removePrefix("file://"))
                if (!f.isFile || f.length() < 0x110) null
                else f.inputStream().use { s ->
                    val want = minOf(len.toLong(), f.length()).toInt()
                    val buf = ByteArray(want)
                    var read = 0
                    while (read < want) {
                        val n = s.read(buf, read, want - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read > 0x110) buf.copyOf(read) else null
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "read header failed: ${t.message}")
            null
        }
    }
}
