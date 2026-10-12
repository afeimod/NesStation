package com.nesstation.app.ui.emulator

import android.content.Context
import java.io.File

/**
 * ★★ PSP（libretro PPSSPP 核心）运行环境准备 —— NesStation 集成 ★★
 *
 * v3（libretro 化重写）：旧版在此准备 memstick 目录树 + memstick_dir.txt +
 * ppsspp.ini（供 dlopen 官方 libppsspp_jni.so 的 JNI 契约路径使用）——
 * 那套集成已整体废弃。新版核心是 libretro 架构（与 flycast/pcsx-rearmed
 * 同构），它自己管理目录（标准 libretro 机制，见 core/jni/psp_loader.cpp）：
 *
 *   · 资产根 = <系统目录>/PPSSPP/（核心在 retro_load_game 里以
 *     GET_SYSTEM_DIRECTORY 结果追加 "PPSSPP/" —— VFS 根，内含
 *     ppge_atlas.zim 游戏内对话框图集、compat.ini 每游戏兼容性标志、
 *     flash0/font 目录的 .pgf 字体 PSP 系统字体、knownfuncs.ini / langregion.ini）；
 *   · memstick 根 = <保存目录>（核心自建 PSP/{SAVEDATA,SYSTEM,GAME,...}
 *     子树 —— 游戏存档落在 <保存目录>/PSP/SAVEDATA）。
 *
 * 本文件职责：
 *  1. [ensurePpssppEnvironment]（幂等，loadRom 前调用）：把随包资产
 *     app/src/main/assets/ppsspp/ 播种到 <系统目录>/PPSSPP/（版本标记
 *     变化时重播种）。
 *  2. [pspSettingsIniFile]：设置面板（PspSettingsStore）的持久化文件
 *     —— 旧版直写核心 ppsspp.ini；libretro 核心不读 ini，本文件只是
 *     NesStation 自己的设置存储（PspEngine.loadRom 时映射为 ppsspp_*
 *     核心选项）。旧 memstick/ppsspp.ini 的历史设置自动迁移。
 */

/** 资产播种版本 —— 随包资产内容变化时递增，触发重新播种。 */
private const val PPSSPP_ASSETS_VERSION = "1"

/**
 * PPSSPP 首次运行环境准备（幂等）：
 *  - 把 assets "ppsspp/" 递归播种到 <systemDir>/PPSSPP/（核心的资产 VFS 根
 *    + flash0 字体目录）；版本标记不一致时整体重播种；
 *  - 返回 [systemDir]（PspEngine 原样传给 PspNative.setPaths）。
 *
 * @param context   App context（读 assets）
 * @param systemDir 系统目录（EmulatorScreen.loadRom 传入的 core system dir）
 * @return 系统目录绝对路径
 */
fun ensurePpssppEnvironment(context: Context, systemDir: String): String {
    val base = File(systemDir, "PPSSPP")
    try {
        base.mkdirs()
        val marker = File(base, ".nesstation_seeded")
        val current = if (marker.isFile) marker.readText().trim() else ""

        if (current != PPSSPP_ASSETS_VERSION || !File(base, "ppge_atlas.zim").isFile) {
            // 递归解包 assets/ppsspp/ → <systemDir>/PPSSPP/
            copyAssetTree(context, "ppsspp", base)
            try {
                marker.parentFile?.mkdirs()
                marker.writeText(PPSSPP_ASSETS_VERSION)
            } catch (_: Throwable) {}
            android.util.Log.i("PspSupport", "PPSSPP assets seeded to ${base.absolutePath}")
        }

        // 旧版设置迁移：memstick/PSP/SYSTEM/ppsspp.ini → pspSettingsIniFile
        migrateLegacySettings(context)
    } catch (t: Throwable) {
        android.util.Log.w("PspSupport", "ensurePpssppEnvironment failed", t)
    }
    return systemDir
}

/** 递归复制 assets 子树到目标目录（目录结构原样保留）。 */
private fun copyAssetTree(context: Context, assetPath: String, targetDir: File) {
    val entries = try {
        context.assets.list(assetPath)?.toList() ?: emptyList()
    } catch (_: Throwable) {
        emptyList<String>()
    }
    if (entries.isEmpty()) {
        // 叶子文件
        try {
            targetDir.parentFile?.mkdirs()
            targetDir.outputStream().use { out ->
                context.assets.open(assetPath).use { it.copyTo(out) }
            }
        } catch (t: Throwable) {
            android.util.Log.w("PspSupport", "copyAssetTree($assetPath) failed", t)
        }
        return
    }
    targetDir.mkdirs()
    for (entry in entries) {
        copyAssetTree(context, "$assetPath/$entry", File(targetDir, entry))
    }
}

/**
 * 设置面板持久化文件（PspSettingsStore 读写）。
 * 位置：<filesDir>/PSP/nesstation_psp.ini —— 与核心运行时目录解耦
 *（libretro 核心的 memstick 由核心自建，不再作为设置存储）。
 */
fun pspSettingsIniFile(context: Context): File {
    val dir = File(context.filesDir, "PSP").apply { mkdirs() }
    return File(dir, "nesstation_psp.ini")
}

/** 旧版 memstick/PSP/SYSTEM/ppsspp.ini 的历史设置 → 新设置文件（一次性）。 */
private fun migrateLegacySettings(context: Context) {
    try {
        val dst = pspSettingsIniFile(context)
        if (dst.isFile) return
        val extFiles = context.getExternalFilesDir(null)
            ?: File(context.filesDir, "ext")
        val legacy = File(extFiles, "PSP/PSP/SYSTEM/ppsspp.ini")
        if (legacy.isFile) {
            dst.parentFile?.mkdirs()
            legacy.copyTo(dst, overwrite = true)
        }
    } catch (_: Throwable) {}
}
