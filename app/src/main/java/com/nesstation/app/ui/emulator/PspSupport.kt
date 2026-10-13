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
 *  v5：字体播种到 ms0 经典覆盖路径（<saveDir>/PSP/flash0/font/）。
 *  v6（本轮）★ 全面回归官方源码方案：
 *   · 随包资产全部替换为 PPSSPP 官方仓库 assets/ 原件（逐字节一致：
 *     jpn0/kr0/ltn0-15.pgf + compat.ini + knownfuncs.ini + langregion.ini
 *     + lang/），并删除自造的 zh_gb.pgf（官方任何版本均无此文件）；
 *   · 废除 v5 的 ms0 字体覆盖层。官方 sceFont.cpp __LoadInternalFonts()
 *     的查找链是 disc0 → ms0 → NAND → VFS，而 ms0 存在时核心会跳过
 *     NAND（"Classic font overrides active, ignoring NAND"）——自造字体
 *     占住 ms0 等于永久屏蔽官方字体自动安装路径
 *     （System.cpp:565 AutoInstallFirmwareFromDisc + sceFont.cpp
 *     InstallFontsFromDiscUpdater，从游戏盘自带固件升级包解包真机字体
 *     与 mpeg.prx 等模块）。废除后核心按官方行为自动安装真固件；
 *     对自带字体的汉化游戏（disc0:/PSP_GAME/USRDIR/zh_gb.pgf +
 *     oldfont.prx，sceFont.cpp:998）官方代码本就优先使用游戏盘字体。
 *   · 启动时一次性删除旧版本播种在 <saveDir>/PSP/flash0/ 的残留字体，
 *     否则旧设备上的自造字体会继续遮蔽官方链。
 *  递增到 v6 强制全量重播种。
 *  注意：KDoc 注释内不可出现 "斜杠-星号" 字符序列 —— Kotlin 块注释支持
 *  嵌套，序列会被当作开启新的嵌套注释，导致 "Unclosed comment" 编译错误。 */
private const val PPSSPP_ASSETS_VERSION = "6"

/**
 * PPSSPP 首次运行环境准备（幂等）：
 *  - 把 assets "ppsspp/" 递归播种到 <systemDir>/PPSSPP/（核心的资产 VFS 根
 *    + flash0 字体目录）；版本标记不一致时整体重播种；
 *  - 清理历史版本遗留的 ms0 字体覆盖层（v5 曾把自造字体播种到
 *    <saveDir>/PSP/flash0/font/，会遮蔽官方字体链，详见下文 v6 注释）；
 *  - 返回 [systemDir]（PspEngine 原样传给 PspNative.setPaths）。
 *
 * @param context   App context（读 assets）
 * @param systemDir 系统目录（EmulatorScreen.loadRom 传入的 core system dir）
 * @param saveDir   存档目录（与 PspNative.setPaths 的第二参一致 = 核心
 *                  memstick 根）
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

        // 清理 v5 遗留的 ms0 字体覆盖层：它以自造字体遮蔽官方字体链
        // （disc0 → ms0 → NAND → VFS 中的第二级），必须删除才能让核心按
        // 官方行为从游戏盘自动安装真固件字体/模块。幂等：目录不存在即跳过。
        try {
            removeLegacyFontOverrides(saveDir)
        } catch (t: Throwable) {
            android.util.Log.w("PspSupport", "removeLegacyFontOverrides failed", t)
        }

        // 旧版设置迁移：memstick/PSP/SYSTEM/ppsspp.ini → pspSettingsIniFile
        migrateLegacySettings(context)
    } catch (t: Throwable) {
        android.util.Log.w("PspSupport", "ensurePpssppEnvironment failed", t)
    }
    return systemDir
}

/**
 * 删除 v5 及更早版本播种的 ms0 字体覆盖层（<saveDir>/PSP/flash0/）。
 *
 * 官方 sceFont.cpp __LoadInternalFonts()：ms0:/PSP/flash0/font/ 存在时
 * （checkClassicOverrides）核心对其余查找路径全部让位（NAND 真固件字体
 * 不再参与），因此该目录一旦残留自造字体，游戏内文字就会持续异常。
 * 该路径不是核心自建的运行时目录（核心只建 PSP/{SAVEDATA,SYSTEM,GAME}），
 * 整目录删除不会影响存档/记忆棒数据。
 */
private fun removeLegacyFontOverrides(saveDir: String) {
    val legacyFlash0 = File(File(File(saveDir), "PSP"), "flash0")
    if (legacyFlash0.exists()) {
        val removed = legacyFlash0.deleteRecursively()
        android.util.Log.i("PspSupport",
            "legacy ms0 font overrides at ${legacyFlash0.absolutePath} " +
            (if (removed) "removed" else "(delete failed)"))
    }
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
