// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// ★ NesStation 集成版契约 —— id_cache.cpp 要求：
//   FindClass("org/yuzu/yuzu_emu/model/GameDir") + <init>(String, Boolean)V
//   （上游为 Parcelable；NesStation app 未启用 parcelize 插件，
//    JNI 仅消费构造器，降级为普通 data 类，语义不变。）

package org.yuzu.yuzu_emu.model

import androidx.annotation.Keep

@Keep
data class GameDir(
    val uriString: String,
    var deepScan: Boolean
)
