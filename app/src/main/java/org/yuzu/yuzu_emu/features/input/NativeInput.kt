// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// ★ NesStation 集成版契约 —— org.yuzu.yuzu_emu.features.input.NativeInput
//   与上游 Java_org_yuzu_yuzu_1emu_features_input_NativeInput_* 对应。
//
// EdenEngine 的输入桥接策略（NesStation 统一位布局 → Switch 输入）：
//   · 屏幕虚拟按键 / 物理手柄 → 【VirtualGamepad】
//     （input_common 的 VirtualGamepad 注释：always assigned to the game
//      input —— 不依赖 docked/handheld 形态，最稳的前端喂入路径）
//     onOverlayButtonEventImpl(port, buttonId(0..19), PRESSED/RELEASED)
//     onOverlayJoystickEventImpl(port, stickId(0=左/1=右), x(-1..1), y(-1..1))
//   · 游戏区触摸 → 【窗口触摸】onTouchPressed/Moved/Released
//     （handheld 形态下即 Switch 触摸屏；docked 形态核心忽略，与上游一致）
//   · 陀螺仪 → onDeviceMotionEvent（WiiMotionSensors 同源数据可复用）

package org.yuzu.yuzu_emu.features.input

object NativeInput {
    object ButtonState {
        const val RELEASED = 0
        const val PRESSED = 1
    }

    external fun isHandheldOnly(): Boolean

    external fun onGamePadButtonEvent(
        guid: String,
        port: Int,
        buttonId: Int,
        action: Int
    )

    external fun onGamePadAxisEvent(guid: String, port: Int, axis: Int, value: Float)

    external fun onGamePadMotionEvent(
        guid: String,
        port: Int,
        deltaTimestamp: Long,
        xGyro: Float,
        yGyro: Float,
        zGyro: Float,
        xAccel: Float,
        yAccel: Float,
        zAccel: Float
    )

    external fun onReadNfcTag(data: ByteArray?)
    external fun onRemoveNfcTag()

    external fun onTouchPressed(fingerId: Int, xAxis: Float, yAxis: Float)
    external fun onTouchMoved(fingerId: Int, xAxis: Float, yAxis: Float)
    external fun onTouchReleased(fingerId: Int)

    // ---- VirtualGamepad（前端喂入主路径；上游为 private，此处公开 ——
    //      JNI 解析只看名字，访问级别不影响绑定）----
    external fun onOverlayButtonEventImpl(port: Int, buttonId: Int, action: Int)

    external fun onOverlayJoystickEventImpl(port: Int, stickId: Int, xAxis: Float, yAxis: Float)

    external fun onDeviceMotionEvent(
        port: Int,
        deltaTimestamp: Long,
        xGyro: Float,
        yGyro: Float,
        zGyro: Float,
        xAccel: Float,
        yAccel: Float,
        zAccel: Float
    )
}
