// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// ★ NesStation 集成版契约 —— id_cache.cpp 要求：
//   FindClass("org/yuzu/yuzu_emu/features/input/model/PlayerInput") +
//   <init>(Z;[String;[String;[String;ZIJJJJ String;Z)V +
//   公共字段 connected/buttons/analogs/motions/vibrationEnabled/
//   vibrationStrength/bodyColorLeft/... （GetFieldID，getInputSettings 用）。

package org.yuzu.yuzu_emu.features.input.model

import androidx.annotation.Keep

@Keep
data class PlayerInput(
    @JvmField var connected: Boolean,
    @JvmField var buttons: Array<String>,
    @JvmField var analogs: Array<String>,
    @JvmField var motions: Array<String>,
    @JvmField var vibrationEnabled: Boolean,
    @JvmField var vibrationStrength: Int,
    @JvmField var bodyColorLeft: Long,
    @JvmField var bodyColorRight: Long,
    @JvmField var buttonColorLeft: Long,
    @JvmField var buttonColorRight: Long,
    @JvmField var profileName: String,
    @JvmField var useSystemVibrator: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PlayerInput
        if (connected != other.connected) return false
        if (!buttons.contentEquals(other.buttons)) return false
        if (!analogs.contentEquals(other.analogs)) return false
        if (!motions.contentEquals(other.motions)) return false
        if (vibrationEnabled != other.vibrationEnabled) return false
        if (vibrationStrength != other.vibrationStrength) return false
        if (bodyColorLeft != other.bodyColorLeft) return false
        if (bodyColorRight != other.bodyColorRight) return false
        if (buttonColorLeft != other.buttonColorLeft) return false
        if (buttonColorRight != other.buttonColorRight) return false
        if (profileName != other.profileName) return false
        if (useSystemVibrator != other.useSystemVibrator) return false
        return true
    }

    override fun hashCode(): Int {
        var result = connected.hashCode()
        result = 31 * result + buttons.contentHashCode()
        result = 31 * result + analogs.contentHashCode()
        result = 31 * result + motions.contentHashCode()
        result = 31 * result + vibrationEnabled.hashCode()
        result = 31 * result + vibrationStrength
        result = 31 * result + bodyColorLeft.hashCode()
        result = 31 * result + bodyColorRight.hashCode()
        result = 31 * result + buttonColorLeft.hashCode()
        result = 31 * result + buttonColorRight.hashCode()
        result = 31 * result + profileName.hashCode()
        result = 31 * result + useSystemVibrator.hashCode()
        return result
    }
}
