package com.nesstation.app.core.storage

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * ★★ 3DS 系统数据种子（完全对齐参考 APK AzaharPlus_2125.1.2 实测行为）★★
 *
 * 反编译参考 APK（assets/Mii.zip + assets/Font_ACG.zip + com.aiwu.utils.f）
 * 实测：参考环境首次初始化时把两个压缩包解压进 Azahar 用户目录根：
 *
 *   Mii.zip（87 项）
 *     - nand/00000000000000000000000000000000/title/00040010/... 系统应用
 *       （Mii Maker 等系统 title 的 .app/.tmd —— Mii/部分游戏 Applet 需要）
 *     - nand/data/.../sysdata/000202xx/... 系统存档
 *     - sysdata/seeddb.bin（区域种子数据库）
 *   Font_ACG.zip（10 项）
 *     - sysdata/shared_font.bin（3DS 共享字体 —— 系统文本的核心依赖）
 *     - nand/.../0004009b/00014002/content 目录的 .app 与 .tmd（共享字体系统 title）
 *
 * NesStation 旧集成从不种这些数据 → 核心只能走"open source replacement"
 * 内存兜底字体 / 缺系统 title，与参考环境不一致（用户实测全部游戏启动失败
 * 且 CIA/系统行为异常）。本对象在 loadRom / CIA 安装前把参考包解压进用户
 * 目录（<filesDir>/azahar），条目已存在且非空时不覆盖（保护用户自放密钥）。
 */
object AzaharSystemData {

    private const val TAG = "AzaharSystemData"

    /** 解压完成标记（与参考 APK 的 MiiFlag.txt 语义一致）。 */
    private const val FLAG_FONT = "FontSeeded.flag"
    private const val FLAG_MII = "MiiSeeded.flag"

    /**
     * 确保参考系统数据已解压进 Azahar 用户目录。
     * 幂等：标记文件存在则直接返回；单包失败不影响另一包。
     *
     * @return true = 两个包都已就绪（或此前已种）
     */
    fun ensureSeeded(context: Context, userDir: File): Boolean {
        var allOk = true
        allOk = seedZip(context, "azahar/sysseed/Font_ACG.zip", userDir, FLAG_FONT) && allOk
        allOk = seedZip(context, "azahar/sysseed/Mii.zip", userDir, FLAG_MII) && allOk
        return allOk
    }

    /** 种子状态诊断（错误弹窗/日志用）。 */
    fun diag(context: Context, userDir: File): String {
        val font = File(File(userDir, "sysdata"), "shared_font.bin")
        val seed = File(File(userDir, "sysdata"), "seeddb.bin")
        return buildString {
            append("sysdata/shared_font.bin: ")
            append(if (font.isFile && font.length() > 0) "已就绪(${font.length()}B)" else "缺失")
            append("\nsysdata/seeddb.bin: ")
            append(if (seed.isFile && seed.length() > 0) "已就绪(${seed.length()}B)" else "缺失")
            append("\n（系统数据由内置参考包自动解压，无需手动放置）")
        }
    }

    private fun seedZip(context: Context, assetPath: String, userDir: File, flagName: String): Boolean {
        val flag = File(userDir, flagName)
        if (flag.isFile) return true
        try {
            userDir.mkdirs()
            ZipFile(extractAsset(context, assetPath, File(context.cacheDir, File(assetPath).name))).use { zf ->
                val entries = zf.entries()
                var applied = 0
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val name = e.name
                    if (name.endsWith("/")) {
                        // 目录条目：仅建目录
                        File(userDir, name).mkdirs()
                        continue
                    }
                    val out = File(userDir, name)
                    // 保护：目标已存在且非空 → 不覆盖（用户自放的密钥/存档优先）
                    if (out.isFile && out.length() > 0) continue
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    applied++
                }
                Log.i(TAG, "seeded $assetPath into ${userDir.absolutePath} ($applied files)")
            }
            flag.writeText("seeded ${System.currentTimeMillis()}")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "seed $assetPath failed: ${t.message}")
            return false
        } finally {
            // 临时拷贝清理（assets 不能直接当 ZipFile 打开）
            try { File(context.cacheDir, File(assetPath).name).delete() } catch (_: Throwable) {}
        }
    }

    private fun extractAsset(context: Context, assetPath: String, dest: File): File {
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest
    }
}
