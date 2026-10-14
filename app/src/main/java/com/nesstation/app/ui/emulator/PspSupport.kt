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
 *  3. [clearPspNand]（v10 新增、v11 扩展）：每次 loadRom 前清空
 *     <saveDir>/PSP/NAND/ —— 官方核心会把光盘固件更新器里的字体/固件
 *     （或游戏自己写入的文件）持久化到该目录，跨游戏污染后引发字体
 *     缺陷与部分游戏绿屏；启动前按官方 EraseInstalledFirmware 的整体
 *     擦除语义全量清空，官方链路会按需重装。
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
 *  - （v10/v11）启动前清空 <saveDir>/PSP/NAND/（官方固件/字体自动
 *    安装与游戏写入的持久化目录，跨游戏污染源 —— 见 clearPspNand）；
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
        // v11：启动清理 —— 官方固件/字体自动安装链（System.cpp:565
        // AutoInstallFirmwareFromDisc / sceFont.cpp:951
        // InstallFontsFromDiscUpdater）与部分游戏自身都会往
        // <saveDir>/PSP/NAND 写文件并跨游戏持久化；残缺或被替换后
        // 引发字体缺陷与部分游戏绿屏（用户实测根因）。官方对陈旧
        // NAND 的解法就是整体擦除重装（PSARUnpack.cpp:1169
        // EraseInstalledFirmware / :1395 SwapStagingIntoPlace），这里
        // 每次进游戏前照此整目录清空，交还官方链路按需重建
        // （见 clearPspNand 的 KDoc）。
        clearPspNand(saveDir)

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
 * v10 新增、v11 扩展 —— 模拟 NAND 启动清理（用户实测根因修复）。
 *
 * 为什么不能只删 font：NAND 里除 flash0/font 外还有 kd/（内核模块）、
 * vsh/、flash1/、ipl/ 等，固件安装中断或被游戏写入后同样残留损坏
 * 文件，部分游戏因此异常（绿屏）。官方对 NAND 陈旧内容的解法就是
 * 整体擦除后重装（见下），从不按子目录挑选。
 *
 * 官方做法对照（hrydgard/ppsspp 当前 master，Core/Util/PSARUnpack.cpp）：
 *  - EraseInstalledFirmware()（PSARUnpack.cpp:1169）递归删除 NAND 下
 *    的 flash0、flash1、ipl 三个目录 —— 官方唯一的 NAND 擦除函数；
 *  - 每次固件安装 SwapStagingIntoPlace()（PSARUnpack.cpp:1395）都先
 *    调它把旧 NAND 全部擦掉，再从 install-staging 换入新固件 ——
 *    官方的安装语义就是全量擦除加重写（System.cpp:562-564 注释原文
 *    亦如此）；
 *  - InstallFirmware()（PSARUnpack.cpp:1437）每次还先清掉上一次安装
 *    中断遗留的 install-staging 目录。
 *
 * 位置推导：PathUtil.cpp:144 DIRECTORY_NAND = <memstick>/PSP/NAND，
 * System.cpp:345 把 flash0:/flash1: 官方挂载在 NAND/flash0、NAND/flash1
 * （可写目录文件系统，等同真机 NAND）；NesStation 的 memstick 根即
 * saveDir（PspEngine.loadRom 传给核心的 GET_SAVE_DIRECTORY）。
 *
 * 本函数每次 loadRom 前把 <saveDir>/PSP/NAND 整目录删除并重建空目录：
 *  - 等价于官方 EraseInstalledFirmware 的全量擦除，并顺带清掉
 *    install-staging 残留与 .nomedia（官方会在安装时重建）；
 *  - 擦除后官方链路按需重装：带更新器的游戏由 AutoInstallFirmwareFromDisc
 *    （System.cpp:565）/ InstallFontsFromDiscUpdater（sceFont.cpp:951）
 *    重新写入，属官方既定行为；
 *  - 游戏存档（PSP/SAVEDATA）、即时存档（PSP/PPSSPP_STATE）、游戏本体
 *    均在 NAND 之外，不受影响。
 * （v10 仅删 font 子目录，实测字体可修复但 kd/ 等残留仍致绿屏，
 *   v11 扩为全量清理。）
 */
private fun clearPspNand(saveDir: String) {
    if (saveDir.isBlank()) return
    try {
        val nand = File(File(File(saveDir), "PSP"), "NAND")
        if (nand.exists()) {
            val ok = nand.deleteRecursively()
            nand.mkdirs()
            val status = if (ok) "done" else "FAILED"
            android.util.Log.i("PspSupport", "PSP/NAND cleanup $status: ${nand.absolutePath}")
        }
    } catch (t: Throwable) {
        android.util.Log.w("PspSupport", "clearPspNand failed", t)
    }
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
