package com.nesstation.app.core.storage

import android.content.Context
import android.util.Log
import java.io.File

/**
 * ★★ NGC/WII（Ishiiruka）Sys 系统数据种子（对齐参考 APK Ishiruka_01）★★
 *
 * 参考 APK 的 assets/Sys 目录由上游 DirectoryInitialization 在首次运行时
 * 整体解压进 Sys 目录，包含：
 *
 *   Sys/GC/font_western.bin + font_japanese.bin —— GC 系统字库
 *     （Wii/GC 游戏的文本绘制依赖；缺失 → 文本刷新慢 / 显示不全 / 缺字，
 *      用户实测"很多文本刷新特别慢甚至缺失"的直接根因）
 *   Sys/GC/dsp_rom.bin + dsp_coef.bin —— DSP LLE ROM（部分游戏需要）
 *   Sys/GameSettings（.ini ×1328）—— 逐游戏兼容性修正
 *     （官方对每个游戏的图形/性能 quirk 补丁；缺失 → 游戏渲染异常/性能差）
 *   Sys/Wii/shared2/... —— Wii 系统共享数据（WC24 等）
 *   Sys/totaldb.dsy / wiitdb-*.txt —— 数据库
 *   Sys/codehandler.bin —— 金手指代码处理器
 *   Sys/Shaders（.glsl）—— 后处理着色器
 *
 * NesStation 旧集成把 Sys 目录设成**空目录**且从不种子 → 与参考环境不一致。
 * 本对象在 loadRom 前把 assets/ishiiruka/Sys 拷进 <filesDir>/ishiiruka/sys
 * （已存在的非空文件一律不覆盖，幂等且保护用户数据）。
 */
object IshirukaSystemData {

    private const val TAG = "IshirukaSystemData"

    /** 一次性标记：种子完成后写在 sys 根，避免每次启动全量扫描。 */
    private const val FLAG = ".sys_seeded"

    /**
     * 确保 Sys 资产已就位。
     * @param sysDir 目标 Sys 目录（= <filesDir>/ishiiruka/sys）
     */
    fun ensureSeeded(context: Context, sysDir: File): Boolean {
        if (File(sysDir, FLAG).isFile) return true
        var ok = true
        try {
            // 与参考 APK DirectoryInitialization 语义一致：整体递归拷贝，
            // 目标已存在且非空时跳过（绝不覆盖用户数据/核心生成物）。
            ok = copyAssetFolder(context, "ishiiruka/Sys", sysDir)
        } catch (t: Throwable) {
            Log.w(TAG, "seed Sys failed: ${t.message}")
            ok = false
        }
        if (ok) {
            try {
                File(sysDir, FLAG).writeText("seeded ${System.currentTimeMillis()}")
            } catch (_: Throwable) {}
        }
        return ok
    }

    private fun copyAssetFolder(context: Context, assetDir: String, destDir: File): Boolean {
        var allOk = true
        val children = try {
            context.assets.list(assetDir)
        } catch (_: Throwable) {
            null
        } ?: return false
        if (children.isEmpty()) {
            // 叶子文件
            if (destDir.isFile && destDir.length() > 0) return true
            return try {
                destDir.parentFile?.mkdirs()
                context.assets.open(assetDir).use { input ->
                    destDir.outputStream().use { output -> input.copyTo(output) }
                }
                true
            } catch (t: Throwable) {
                Log.w(TAG, "seed file $assetDir failed: ${t.message}")
                false
            }
        }
        destDir.mkdirs()
        for (child in children) {
            val ok = copyAssetFolder(context, "$assetDir/$child", File(destDir, child))
            if (!ok) allOk = false
        }
        return allOk
    }
}
