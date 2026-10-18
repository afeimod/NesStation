// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// ★ NesStation 集成版契约 —— id_cache.cpp 要求：
//   FindClass("org/yuzu/yuzu_emu/model/Patch") +
//   <init>(Z,String,String,I,String,String,J,I)V
//   （enabled, name, version, type, programId, titleId, numericVersion, source）
//   + 公共字段 enabled/name/version/type/programId/titleId（GetFieldID）。

package org.yuzu.yuzu_emu.model

import androidx.annotation.Keep

@Keep
data class Patch(
    @JvmField var enabled: Boolean,
    @JvmField val name: String,
    @JvmField val version: String,
    @JvmField val type: Int,
    @JvmField val programId: String,
    @JvmField val titleId: String,
    @JvmField val numericVersion: Long = 0,
    @JvmField val source: Int = 0
) {
    companion object {
        const val SOURCE_UNKNOWN = 0
        const val SOURCE_NAND = 1
        const val SOURCE_SDMC = 2
        const val SOURCE_EXTERNAL = 3
        const val SOURCE_PACKED = 4
    }

    val isRemovable: Boolean
        get() = source != SOURCE_EXTERNAL && source != SOURCE_PACKED
}
