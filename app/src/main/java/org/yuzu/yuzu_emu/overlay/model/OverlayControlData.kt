// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// ★ NesStation 集成版契约 —— id_cache.cpp 要求：
//   FindClass("org/yuzu/yuzu_emu/overlay/model/OverlayControlData") +
//   <init>(String;Z;Lkotlin/Pair;Lkotlin/Pair;Lkotlin/Pair;F)V +
//   公共字段 id/enabled/landscapePosition/portraitPosition/
//   foldablePosition/individualScale（GetFieldID）。
//   前端不用 eden 的覆盖层布局体系（虚拟按键布局归 NesStation
//   触控系统管），本类只为满足 native_config.cpp 的
//   get/setOverlayControlData 序列化路径而存在。

package org.yuzu.yuzu_emu.overlay.model

import androidx.annotation.Keep

@Keep
data class OverlayControlData(
    @JvmField val id: String,
    @JvmField var enabled: Boolean,
    @JvmField var landscapePosition: Pair<Double, Double>,
    @JvmField var portraitPosition: Pair<Double, Double>,
    @JvmField var foldablePosition: Pair<Double, Double>,
    @JvmField var individualScale: Float
)
