// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// ★★★ NesStation 集成版 —— org.yuzu.yuzu_emu.applets.keyboard.SoftwareKeyboard
//
// 与上游 common/android/applets/software_keyboard.cpp 的 InitJNI 契约一致：
//   · FindClass org/yuzu/yuzu_emu/applets/keyboard/SoftwareKeyboard
//     + $KeyboardConfig + $KeyboardData
//   · 静态 executeNormal(KeyboardConfig)KeyboardData（阻塞，C++ 等待）
//   · 静态 executeInline(KeyboardConfig)V
// 与上游差异：不再依赖 EmulationActivity/FragmentManager/IME 强拉起 ——
// 改为发布 StateFlow 请求，由 NesStation EmulatorScreen 渲染对话框
// （与 Wii U CemuTextInputDialog 同款交互），完成时调
// [complete] 送回结果。Android IME（含中文输入法）由 Compose TextField
// 原生处理 —— Switch 游戏内文本输入天然支持中文。

package org.yuzu.yuzu_emu.applets.keyboard

import androidx.annotation.Keep
import java.io.Serializable

@Keep
object SoftwareKeyboard {

    /** 当前请求（null = 无键盘请求）。EmulatorScreen 观察它渲染输入对话框。 */
    @JvmStatic
    val pending = kotlinx.coroutines.flow.MutableStateFlow<KeyboardConfig?>(null)

    /** inline 模式标记（决定完成时的提交路径）。 */
    @JvmStatic
    private var inlineMode = false

    /** 完成回调数据锁（executeNormal 阻塞等待用，上游同协议）。 */
    val dataLock = Object()

    @JvmStatic
    var data: KeyboardData = KeyboardData(SwkbdResult.Cancel.ordinal, "")
        private set

    /**
     * EmulatorScreen 调用：用户点击「完成」。
     * Normal 模式 → 填 data + 唤醒 executeNormal；Inline 模式 → 提交
     * 文本 + 回车键码（SubmitInlineKeyboardText/Input）。
     */
    @JvmStatic
    fun complete(result: SwkbdResult, text: String) {
        val cfg = pending.value ?: return
        pending.value = null
        if (inlineMode) {
            try {
                org.yuzu.yuzu_emu.NativeLibrary.submitInlineKeyboardText(text)
                org.yuzu.yuzu_emu.NativeLibrary.submitInlineKeyboardInput(
                    android.view.KeyEvent.KEYCODE_ENTER
                )
            } catch (_: Throwable) {
            }
            return
        }
        synchronized(dataLock) {
            data = KeyboardData(result.ordinal, text)
            dataLock.notifyAll()
        }
    }

    @JvmStatic
    fun executeNormal(config: KeyboardConfig): KeyboardData {
        inlineMode = false
        data = KeyboardData(SwkbdResult.Cancel.ordinal, "")
        pending.value = config
        synchronized(dataLock) {
            dataLock.wait()
        }
        return data
    }

    @JvmStatic
    fun executeInline(config: KeyboardConfig) {
        inlineMode = true
        pending.value = config
    }

    // Corresponds to Service::AM::Applets::SwkbdType
    enum class SwkbdType {
        Normal, NumberPad, Qwerty, Unknown3, Latin,
        SimplifiedChinese, TraditionalChinese, Korean
    }

    // Corresponds to Service::AM::Applets::SwkbdPasswordMode
    enum class SwkbdPasswordMode {
        Disabled, Enabled
    }

    // Corresponds to Service::AM::Applets::SwkbdResult
    enum class SwkbdResult {
        Ok, Cancel
    }

    @Keep
    data class KeyboardConfig(
        var ok_text: String? = null,
        var header_text: String? = null,
        var sub_text: String? = null,
        var guide_text: String? = null,
        var initial_text: String? = null,
        var left_optional_symbol_key: Short = 0,
        var right_optional_symbol_key: Short = 0,
        var max_text_length: Int = 0,
        var min_text_length: Int = 0,
        var initial_cursor_position: Int = 0,
        var type: Int = 0,
        var password_mode: Int = 0,
        var text_draw_type: Int = 0,
        var key_disable_flags: Int = 0,
        var use_blur_background: Boolean = false,
        var enable_backspace_button: Boolean = false,
        var enable_return_button: Boolean = false,
        var disable_cancel_button: Boolean = false
    ) : Serializable

    // Corresponds to Frontend::KeyboardData
    @Keep
    data class KeyboardData(var result: Int, var text: String)
}
