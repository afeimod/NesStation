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

/** 资产播种版本 —— 随包资产内容变化时递增，触发重新播种。
 *  v2：新增 lang/（<systemDir>/PPSSPP/lang 目录下的多语言 ini —— 核心选项
 *  ppsspp_language 与游戏内系统对话框文字的资源根），必须重播种。
 *  v3：flash0/font 字体（jpn0.pgf 等）是后补进随包资产的；早先已播种过
 *  v1/v2 的设备因版本标记一致而永远不会补上 —— 造成 PSP 游戏内文字缺失
 *  （sceFont 走 VFS 兜底路径 flash0/font/，见官方 sceFont.cpp）。
 *  v4：中文大字库 jpn0.pgf + zh_gb.pgf（GBK 码位全覆盖）+ ppge_atlas.meta。
 *  v5（v9 交付）★ 字体加载优先级修复：
 *   官方 sceFont.cpp __LoadInternalFonts() 的字体查找链是：
 *     游戏盘 disc0 → ms0:/PSP/flash0/font/（经典覆盖）→
 *     NAND flash0:/font/（固件）→ VFS（<systemDir>/PPSSPP/，优先级最低）。
 *   v4 的中文字体只播种在 VFS（优先级最低）——而核心的固件自动安装
 *   （System.cpp:565 AutoInstallFirmwareFromDisc，无条件）会把真机字体
 *   （JIS 索引，无 GBK 码位）装进 NAND，直接遮蔽 VFS 字体 → 汉化游戏
 *   用 GBK 码位查字再次全部落空（altCharCode=0x5F）→ 满屏下划线。
 *   v5 起把整套字体同时播种到 ms0 经典覆盖路径（<saveDir>/PSP/flash0/
 *   font/）——它在官方查找链里优先于 NAND 与 VFS，固件装不装都命中。
 *   首次生效时 logcat 会出现 "Classic font overrides active, ignoring
 *   NAND"（sceFont.cpp WARN，预期行为，勿当故障）。
 *  递增到 v5 强制全量重播种（含 ms0 字体覆盖层）。
 *  注意：KDoc 注释内不可出现 "斜杠-星号" 字符序列 —— Kotlin 块注释支持
 *  嵌套，序列会被当作开启新的嵌套注释，导致 "Unclosed comment" 编译错误。 */
private const val PPSSPP_ASSETS_VERSION = "5"

/** ms0 字体覆盖层自己的播种标记（与 VFS 资产标记分离，各自独立幂等）。 */
private const val FONT_OVERRIDE_MARKER = ".nesstation_font_seed"

/**
 * PPSSPP 首次运行环境准备（幂等）：
 *  - 把 assets "ppsspp/" 递归播种到 <systemDir>/PPSSPP/（核心的资产 VFS 根
 *    + flash0 字体目录）；版本标记不一致时整体重播种；
 *  - 把 flash0/font 全套 .pgf 额外播种到 <saveDir>/PSP/flash0/font/
 *    （ms0 经典字体覆盖，官方查找链优先级高于 NAND/VFS —— 见版本注释 v5）；
 *  - 返回 [systemDir]（PspEngine 原样传给 PspNative.setPaths）。
 *
 * @param context   App context（读 assets）
 * @param systemDir 系统目录（EmulatorScreen.loadRom 传入的 core system dir）
 * @param saveDir   存档目录（与 PspNative.setPaths 的第二参一致 = 核心
 *                  memstick 根；ms0: 即此目录）
 * @return 系统目录绝对路径
 */
fun ensurePpssppEnvironment(context: Context, systemDir: String, saveDir: String): String {
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

        // ms0 经典字体覆盖（优先级高于 NAND 固件字体与 VFS）。
        // 独立标记：目录级幂等，版本变化或目录首次使用时播种一次。
        try {
            seedClassicFontOverrides(context, saveDir)
        } catch (t: Throwable) {
            android.util.Log.w("PspSupport", "seedClassicFontOverrides failed", t)
        }

        // 旧版设置迁移：memstick/PSP/SYSTEM/ppsspp.ini → pspSettingsIniFile
        migrateLegacySettings(context)
    } catch (t: Throwable) {
        android.util.Log.w("PspSupport", "ensurePpssppEnvironment failed", t)
    }
    return systemDir
}

/**
 * ms0 经典字体覆盖层播种：assets ppsspp/flash0/font 全套 .pgf →
 * <saveDir>/PSP/flash0/font/。
 *
 * 官方 sceFont.cpp __LoadInternalFonts()（每局游戏启动时执行）按
 * 游戏盘 → ms0 → NAND → VFS 的顺序为 fontRegistry 的每个注册项找字体。
 * 只要把字体放进 ms0:/PSP/flash0/font/，就能压过 NAND 里固件自动安装
 * 的真机字体（JIS 索引、无 GBK 码位）——汉化游戏按 GBK 码位查字即可
 * 命中随包中文字库。标记文件保证同一目录只播种一次。
 */
private fun seedClassicFontOverrides(context: Context, saveDir: String) {
    val fontDir = File(File(File(saveDir), "PSP"), "flash0").resolve("font")
    val marker = File(fontDir, FONT_OVERRIDE_MARKER)
    val current = if (marker.isFile) marker.readText().trim() else ""
    if (current == PPSSPP_ASSETS_VERSION &&
        File(fontDir, "jpn0.pgf").isFile && File(fontDir, "zh_gb.pgf").isFile) {
        return
    }
    copyAssetTree(context, "ppsspp/flash0/font", fontDir)
    try {
        marker.parentFile?.mkdirs()
        marker.writeText(PPSSPP_ASSETS_VERSION)
    } catch (_: Throwable) {}
    android.util.Log.i("PspSupport", "ms0 font overrides seeded to ${fontDir.absolutePath}")
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
