package com.nesstation.app.core.engine

import android.util.Log
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * ★★ FBNeo 改版 ROM .dif 差分兼容层（修复 kof97t / 拳皇97三问 等「缺 .dif 文件」）
 *
 * 背景（上游 libretro/FBNeo 行为，已对照 libretro.cpp open_archive() 审计逻辑核实）：
 * 上游为一批"优化版/改版"街机 romset（kof97t 三问、kof96ae/kof98ae 暴力版、
 * kof98mix、kof99eur、sengk3eb、ffightdw、dinofw、oldsplusnr ... 共 15 个 set）
 * 定义了 Nebula 风格的 *.dif 差分条目：差分文件内容与父 ROM 逐字节 XOR 得到
 * 改版 ROM（d_neogeo.cpp RomDiffPatch()：`if (pTemp[i] != 0) pSrc[i] ^= pTemp[i]`）。
 * 这些条目带着 BRF_ESS 标志参与 ROM 审计 —— **即使 romset zip 里已带全套完整
 * 改版 ROM（如 kof97t.zip 的 232-p1t.p1 / 232-c1t.c1 ...），审计仍会因 zip 里
 * 没有 232-p1t.dif 等 7 个差分文件而整体拒绝启动**，报：
 *   "REQUIRED FILES ARE MISSING ... 232-p1t.dif and CRC 0xb162219a is missing ..."
 * 这正是用户遇到的「fbneo 还是不支持部分改版（kof97t.zip）」的直接根因。
 *
 * 修复原理（无需重编核心）：
 * 1. 上游 locate_archive() 在 bPatchedRomsetsEnabled（fbneo-allow-patched-romsets，
 *    fbneo_loader.cpp 已默认开启）时，会**优先**搜索 <systemDir>/fbneo/patched/
 *    下的同名 zip，且该目录内的条目【忽略 CRC、按文件名匹配、仅校验长度】；
 * 2. 全零差分对 XOR 是无操作（0 与任何值异或不变），RomDiffPatch 打了等于没打；
 * 3. 因此在进入核心前，检查用户 zip 里缺哪些 .dif 条目，向 patched/<set>.zip
 *    写入**按驱动期望长度填零**的同名占位文件即可通过审计；游戏继续用 zip 里
 *    的完整改版 ROM 运行（默认版本不受影响）。
 *
 * 防护策略：
 * - 用户 zip 里已有（按文件名或 CRC 命中）的 .dif 不生成占位，继续用真文件；
 * - 用户之后把真 .dif 放进自己的 zip → 下次启动检测到全部满足，自动删除我们
 *   生成的占位 zip（避免 patched/ 优先搜索遮蔽真文件）；patched 目录是玩家
 *   手动放改版 ROM 的官方通道，凡不含本模块标记条目的 zip 一律不碰。
 */
internal object FbNeoDifCompat {

    private const val TAG = "FbNeoDifCompat"

    /** patched/ 占位 zip 内的标记条目名（0 字节）。用于识别"这是本模块生成的" */
    private const val MARKER_ENTRY = "__nesstation_dif_staged__"

    /** 单次写入缓冲（1 MiB 全零块复用） */
    private val ZERO_BLOCK = ByteArray(1 shl 20)

    /**
     * 加载 ROM 前调用：确保该 romset 需要的 .dif 差分在 patched/ 目录有占位。
     *
     * @param romFile   用户选择的 ROM 文件（.zip / .7z）
     * @param systemDir 传给核心的 system 目录（ARCADE = <filesDir>/fbneo；
     *                  patched 目录 = <systemDir>/fbneo/patched，与核心
     *                  locate_archive()/fbneo_loader.cpp ensurePatchedRomDirs 一致）
     */
    fun ensureDifPatches(romFile: File, systemDir: String) {
        try {
            val romset = romFile.nameWithoutExtension.lowercase()
            val entries = FbNeoDifTable.table[romset] ?: return

            val patchedDir = File(File(systemDir, "fbneo"), "patched")
            patchedDir.mkdirs()
            val patchedZip = File(patchedDir, "$romset.zip")

            // 用户自建 patched/<set>.zip（不含标记）→ 玩家手动覆盖通道，不碰
            if (patchedZip.isFile && !isOurs(patchedZip)) {
                Log.i(TAG, "patched/$romset.zip exists (user-made), leave untouched")
                return
            }

            // 检查用户 ROM zip 里已满足的 .dif。
            // ★ 满足的判定与上游审计一致：CRC 命中【且长度一致】才算找到
            //   （libretro.cpp 对 CRC 命中的文件仍做 STAT_SMALL/STAT_LARGE 判定，
            //   长度不匹配同样算缺）；仅文件名命中而 CRC 不符在用户 zip 里
            //   （非 patched 目录）不参与名字回退，审计必挂 —— 这种情况也要
            //   铺占位（patched/ 按名匹配、免 CRC，优先级最高，直接补齐）。
            val present = scanUserArchive(romFile)

            val missing = entries.filter { e ->
                present.none { it.first == e.crc && it.second == e.size }
            }
            if (missing.isEmpty()) {
                // 全部满足：清理我们旧的占位 zip，避免 patched/ 优先级遮蔽真文件
                if (patchedZip.isFile) {
                    patchedZip.delete()
                    Log.i(TAG, "all .dif present in user zip, removed staged $patchedZip")
                }
                return
            }

            writeStagedZip(patchedZip, missing)
            Log.i(TAG, "staged ${missing.size} dummy .dif entry(ies) for '$romset' " +
                    "(${missing.joinToString { it.name }}) -> $patchedZip")
        } catch (t: Throwable) {
            // 兼容层永不阻断正常加载路径
            Log.w(TAG, "ensureDifPatches failed (ignored): ${t.message}")
        }
    }

    /** 读取用户 zip 内的 (CRC, 未压缩长度) 集合 —— 审计命中即要求这两项匹配。
     *  非 zip 后缀（.7z 等）java.util.zip 无法解析时返回空集（按全部缺失处理，
     *  铺占位不会造成回归），读取失败同样返回空集。 */
    private fun scanUserArchive(romFile: File): Set<Pair<Long, Int>> {
        if (!romFile.name.endsWith(".zip", ignoreCase = true)) return emptySet()
        return try {
            ZipFile(romFile).use { zf ->
                buildSet {
                    zf.entries().asSequence().forEach { ze ->
                        add(ze.crc to ze.size.toInt())
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scan zip failed: ${t.message}")
            emptySet()
        }
    }

    /** 判断 patched zip 是否为本模块生成（含标记条目） */
    private fun isOurs(patchedZip: File): Boolean = try {
        ZipFile(patchedZip).use { zf -> zf.getEntry(MARKER_ENTRY) != null }
    } catch (_: Throwable) {
        // 损坏/非 zip —— 也视为非我们的（保守：不覆盖玩家文件）
        false
    }

    /** 生成占位 zip：标记条目 + 每个缺失 .dif 一个全零文件（精确长度，DEFLATE 压缩） */
    private fun writeStagedZip(patchedZip: File, missing: List<FbNeoDifTable.DifEntry>) {
        val tmp = File(patchedZip.parentFile, patchedZip.name + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { zos ->
            zos.setLevel(java.util.zip.Deflater.BEST_SPEED)
            // 标记条目
            zos.putNextEntry(ZipEntry(MARKER_ENTRY))
            zos.closeEntry()
            // 全零 .dif 条目（长度必须精确等于驱动期望 —— 上游审计 STAT_SMALL/STAT_LARGE 同样判缺）
            for (e in missing) {
                zos.putNextEntry(ZipEntry(e.name))
                var remain = e.size
                while (remain > 0) {
                    val n = minOf(remain, ZERO_BLOCK.size)
                    zos.write(ZERO_BLOCK, 0, n)
                    remain -= n
                }
                zos.closeEntry()
            }
        }
        if (patchedZip.isFile) patchedZip.delete()
        if (!tmp.renameTo(patchedZip)) {
            // rename 失败（极少数文件系统）→ 流拷贝兜底
            tmp.copyTo(patchedZip, overwrite = true)
            tmp.delete()
        }
    }
}
