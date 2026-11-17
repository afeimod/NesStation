package com.nesstation.app.battle

import android.content.Context
import java.io.File

/**
 * 对战平台 ROM 本地存储管理。
 *
 * 下载目录：<filesDir>/battle_roms/（与游戏库隔离，统一由服务器分发）
 * BIOS 目录：<filesDir>/fbneo/（与本地街机共用，由 NesApp 启动时从 assets 解压）
 */
object BattleRomStore {

    fun romDir(ctx: Context): File =
        File(ctx.filesDir, "battle_roms").apply { mkdirs() }

    /** 游戏 ROM 文件路径（如 kof97.zip） */
    fun romFile(ctx: Context, fileName: String): File =
        File(romDir(ctx), fileName)

    fun hasRom(ctx: Context, fileName: String): Boolean {
        val f = romFile(ctx, fileName)
        return f.exists() && f.length() > 0 && !f.name.endsWith(".part")
    }

    /** 街机 BIOS 目录（neogeo.zip 等由 NesApp 启动时从 assets 解压到这里） */
    fun biosDir(ctx: Context): File =
        File(ctx.filesDir, "fbneo")

    fun hasBios(ctx: Context, biosName: String): Boolean =
        File(biosDir(ctx), biosName).exists()
}
