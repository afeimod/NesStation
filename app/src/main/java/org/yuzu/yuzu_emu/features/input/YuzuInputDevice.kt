// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// ★ NesStation 集成版契约 —— id_cache.cpp 要求：
//   FindClass("org/yuzu/yuzu_emu/features/input/YuzuInputDevice") +
//   方法 getName/getGUID/getPort/getSupportsVibration/vibrate/getAxes/hasKeys。
//   前端不把 Android InputDevice 注册进 eden（虚拟按键走 VirtualGamepad、
//   物理按键经 NesStation 触控系统映射），本接口仅为满足
//   registerController 的 GetMethodID 注册路径而存在。

package org.yuzu.yuzu_emu.features.input

import androidx.annotation.Keep

@Keep
interface YuzuInputDevice {
    fun getName(): String

    fun getGUID(): String

    fun getPort(): Int

    fun getSupportsVibration(): Boolean

    fun vibrate(intensity: Float)

    fun getAxes(): Array<Int> = arrayOf()
    fun hasKeys(keys: IntArray): BooleanArray = BooleanArray(0)
}
