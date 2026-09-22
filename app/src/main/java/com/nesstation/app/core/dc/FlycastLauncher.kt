package com.nesstation.app.core.dc

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.util.Log
import com.flycast.emulator.BaseGLActivity
import com.flycast.emulator.NativeGLActivity
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.storage.RomStore
import java.io.File

/**
 * Flycast (Dreamcast/NAOMI/AtomisWave) 游戏启动器。
 *
 * 与其它平台「进程内核心 + 统一 EmulatorScreen」不同，Flycast 是一个自带
 * 完整运行循环 / 原生 ImGui 菜单 / 原生虚拟手柄的独立模拟器（standalone），
 * 渲染、音频、输入、联机、RetroAchievements 全部由 libflycast.so + 其 Java
 * 宿主层（com.flycast.emulator.*，随本源码一并 vendor 进工程）自管理。
 * 因此 Dreamcast 平台的启动方式是：带游戏 URI 启动
 * [com.flycast.emulator.NativeGLActivity]，由它接管整个游戏会话；
 * 用户退出该 Activity 后返回 NesStation 启动页。
 *
 * 传参走 [BaseGLActivity.EXTRA_GAME_URI]（纯字符串 extra，避免经
 * Intent.setData(file://) 触发 StrictMode FileUriExposedException）。
 * native 侧 setGameUri 接受：
 *   · 纯绝对路径（POSIX 直读，多文件游戏 .cue+.bin / .gdi 轨道可解析）；
 *   · content:// URI（SAF 桥 AndroidStorage，见 flycast android_storage.h，
 *     isKnownPath 仅放行 content://，其余一律走 POSIX）。
 * 注意不要传 "file://" 前缀 —— native 不剥离该前缀会导致 stat 失败。
 */
object FlycastLauncher {

    private const val TAG = "FlycastLauncher"

    /** libflycast.so 仅打包了 arm64-v8a（与上游 APK 一致） */
    fun isAbiSupported(): Boolean =
        Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }

    /** DC 游戏（.gdi/.cue 多轨道）需要真实文件系统路径；单文件镜像可走 SAF */
    private fun resolveGameUri(game: GameEntry): String? {
        val romPath = game.romPath ?: return null
        if (romPath.isEmpty()) return null
        if (!romPath.startsWith("content://")) {
            // 本地路径直接使用（native POSIX 直读，文件存在性由调用方校验）
            return romPath
        }
        // content:// → 尝试解析外部存储真实路径（NesStation 已有成熟逻辑，
        // 复制自 EmulatorScreen.resolveContentUriToRealFile 的同等实现），
        // 多轨道游戏（cue/gdi）必须这样才能找到同目录轨道文件。
        val real = resolveContentUriToRealFile(romPath)
        return real?.absolutePath ?: romPath
    }

    /** 与 EmulatorScreen.resolveContentUriToRealFile 一致的 SAF→真实路径解析 */
    private fun resolveContentUriToRealFile(uriStr: String): File? {
        return try {
            val uri = android.net.Uri.parse(uriStr)
            val lastSeg = android.net.Uri.decode(uri.lastPathSegment ?: return null)
            // "primary:ROMs/dc/game.gdi" 或 "17FB-1E12:ROMS/game.chd"
            if (!lastSeg.contains(':')) return null
            val volume = lastSeg.substringBefore(':', "").trim()
            val pathPart = lastSeg.substringAfter(':', "").trimStart('/')
            if (pathPart.isBlank()) return null
            val storageRoot = when {
                volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
                volume.contains('-') -> "/storage/$volume"
                else -> return null
            }
            val file = File(storageRoot, pathPart)
            file.takeIf { it.isFile && it.canRead() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 启动游戏。
     * @return null = 已启动；非 null = 中文错误信息（调用方展示）
     */
    fun launch(activity: Activity, game: GameEntry): String? {
        if (!isAbiSupported()) {
            return "Flycast 核心仅提供 64 位 (arm64-v8a) 库，当前设备为 32 位进程，" +
                   "无法运行 Dreamcast 游戏。"
        }
        val gameUri = resolveGameUri(game)
        if (gameUri.isNullOrEmpty()) {
            return "该游戏未关联 ROM 文件"
        }
        val rom = if (gameUri.startsWith("content://")) null else File(gameUri)
        if (rom != null && !rom.isFile) {
            return "ROM 文件不存在：\n$gameUri\n\n可能已被移动或删除，请重新导入。"
        }

        // 1. home 目录 + 偏好写入（必须在核心初始化前生效）
        FlycastPaths.ensureHomePref(activity)
        FlycastPaths.ensureBiosFromAssets(activity)

        // 2. DC 光盘游戏的 BIOS 预检（Naomi/AtomisWave zip 自带 BIOS，不预检；
        //    卡带 .gdi/.cdi/.cue/.chd/.iso 均为 DC 商业游戏，需要 BIOS 才能启动）
        val isConsoleImage = listOf("gdi", "cdi", "cue", "chd", "iso", "lst")
            .any { gameUri.endsWith(".$it", ignoreCase = true) }
        if (isConsoleImage && !FlycastPaths.hasConsoleBios(activity)) {
            val home = FlycastPaths.homeDir(activity)
            return "Dreamcast 游戏需要 BIOS 文件才能运行（当前未检测到）。\n\n" +
                   "请先到 设置 → DC/Dreamcast → BIOS 管理，导入：\n" +
                   "  · dc_boot.bin（BIOS ROM）\n" +
                   "  · dc_flash.bin（闪存）\n\n" +
                   "文件将存放在：${File(home, "data")}\n\n" +
                   " Naomi / AtomisWave 街机游戏（.zip）无需此 BIOS。"
        }

        // 3. 启动 Flycast 的原生 GL Activity（extra 传 URI 字符串）
        try {
            val intent = Intent(activity, NativeGLActivity::class.java)
                .putExtra(BaseGLActivity.EXTRA_GAME_URI, gameUri)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            activity.startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start NativeGLActivity", t)
            return "启动 Flycast 失败：${t.message}"
        }

        // 4. 更新最近游玩时间（与游戏库「最近游玩」排序联动）
        try {
            RomStore.updateLastPlayed(activity, game.id)
        } catch (_: Throwable) {
        }
        return null
    }
}
