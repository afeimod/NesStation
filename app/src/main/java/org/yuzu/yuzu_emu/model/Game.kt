// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// ★ NesStation 集成版契约 —— id_cache.cpp（native.cpp GameHelper 路径）
//   要求：FindClass("org/yuzu/yuzu_emu/model/Game") +
//   <init>(String,String,String,String,String,Z)V（title, path, programId,
//   developer, version, isHomebrew 顺序）+ 同名公共字段（GetFieldID）。
//   @JvmField 直接暴露后备字段，保证 GetFieldID/GetIntField 稳定命中。

package org.yuzu.yuzu_emu.model

import androidx.annotation.Keep

@Keep
class Game(
    @JvmField val title: String = "",
    @JvmField val path: String = "",
    @JvmField val programId: String = "",
    @JvmField val developer: String = "",
    @JvmField var version: String = "",
    @JvmField val isHomebrew: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Game
        if (title != other.title) return false
        if (path != other.path) return false
        if (programId != other.programId) return false
        if (developer != other.developer) return false
        if (version != other.version) return false
        if (isHomebrew != other.isHomebrew) return false
        return true
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + programId.hashCode()
        result = 31 * result + developer.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + isHomebrew.hashCode()
        return result
    }

    companion object {
        // 上游 Game.extensions —— Switch ROM 扫描格式（NesStation 侧
        // GameEntry.NSWITCH 的 fromExtension 与此对齐）
        val extensions: Set<String> = hashSetOf("xci", "nsp", "nca", "nro")
    }
}
