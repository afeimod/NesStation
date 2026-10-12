package com.nesstation.app.ui.emulator

import android.content.Context
import java.io.File

/**
 * ★★ PSP（PPSSPP 核心）运行环境准备 —— NesStation 集成 ★★
 *
 * 历史注：本文件曾是"独立 PpssppActivity 启动桥"（Compose 启动器 +
 * Lifecycle 回退检测）。PSP 已改为与 DC/PSX 完全同构的进程内引擎集成
 * （PspEngine → libpspcore.so → dlopen 官方 libppsspp_jni.so，统一走
 * EmulatorScreen 全链路），启动器组件已删除，本文件只保留两个全局工具：
 *
 *  1. [ensurePpssppEnvironment]（幂等，每次 loadRom 前调用）：
 *     - memstick 目录 = <externalFilesDir>/PSP（应用私有外部目录，无需
 *       存储权限，卸载随应用清除）；
 *     - 写 <filesDir>/memstick_dir.txt —— PPSSPP scoped storage 模式下以
 *       该文件定位 memstick（NativeInit 逻辑），缺失时 PPSSPP 首启会弹出
 *       "选择存储位置"浏览器，破坏体验；
 *     - 预建 PSP/SYSTEM、PSP/SAVEDATA、PSP/PPSSPP_STATE、PSP/GAME、
 *       PSP/CHEATS、PSP/TEXTURES、PSP/PLUGINS、PSP/CACHE（与上游
 *       Core/Util/PathUtil.cpp CreateSysDirectories 一致；即使缺目录，
 *       PPSSPP 启动时也会自行补建）；
 *  2. [ppssppIniFile] —— 全局配置文件绝对路径（PspSettingsStore 读写 +
 *       PspEngine 强制 Vulkan 后端用）。
 */

/**
 * PPSSPP 首次运行环境准备（幂等）：
 *  - memstick = <externalFilesDir>/PSP；
 *  - <filesDir>/memstick_dir.txt 写入 memstick 绝对路径（PPSSPP scoped
 *    storage 引导逻辑；PPSSPP 要求该目录已存在才采纳）；
 *  - 预建 PSP 子目录树（含 .nomedia，与上游 CreateSysDirectories 一致）。
 *
 * @return memstick 根目录绝对路径（<externalFilesDir>/PSP）
 */
fun ensurePpssppEnvironment(context: android.content.Context): String {
    val extFiles = context.getExternalFilesDir(null)
        ?: File(context.filesDir, "ext").apply { mkdirs() }
    val memstick = File(extFiles, "PSP").apply { mkdirs() }

    // PSP 子目录树（与上游 CreateSysDirectories 的 sysDirs 一致）
    for (sub in listOf(
        "SYSTEM", "SAVEDATA", "PPSSPP_STATE", "GAME",
        "CHEATS", "TEXTURES", "PLUGINS", "CACHE"
    )) {
        File(memstick, sub).apply { mkdirs() }
        // .nomedia：与上游一致，避免相册扫描存档/缓存图片
        val nomedia = File(memstick, "$sub/.nomedia")
        if (!nomedia.exists()) {
            try { nomedia.createNewFile() } catch (_: Throwable) {}
        }
    }

    // memstick_dir.txt：PPSSPP NativeInit 读取它确定 memstick 目录。
    // 仅在内容缺失/变化时重写（externalFilesDir 的挂载路径可能随用户切换
    // 多用户/迁移存储变化，PPSSPP 侧用 File::Exists 校验，路径失效会回退
    // 到询问用户 —— 所以这里每次启动都校正为当前有效路径）。
    val marker = File(context.filesDir, "memstick_dir.txt")
    val desired = memstick.absolutePath
    val current = try {
        if (marker.exists()) marker.readText().trim() else ""
    } catch (_: Throwable) { "" }
    if (current != desired) {
        try {
            marker.parentFile?.mkdirs()
            marker.writeText(desired)
        } catch (_: Throwable) {}
    }
    return desired
}

/** PPSSPP 全局配置文件（ppsspp.ini）绝对路径；尚未生成时返回其将来的路径。 */
fun ppssppIniFile(context: android.content.Context): File {
    val memstick = ensurePpssppEnvironment(context)
    return File(memstick, "PSP/SYSTEM/ppsspp.ini")
}
