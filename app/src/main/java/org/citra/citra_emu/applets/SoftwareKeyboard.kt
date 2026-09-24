package org.citra.citra_emu.applets

import android.util.Log
import androidx.annotation.Keep

/**
 * Azahar 软键盘系统小程序 —— JNI 契约桩。
 *
 * 原生侧（jni/applets/swkbd.cpp）在 InitJNI 时缓存：
 *  - `execute(Lorg/citra/citra_emu/applets/SoftwareKeyboard$KeyboardConfig;)Lorg/citra/citra_emu/applets/SoftwareKeyboard$KeyboardData;`
 *  - `showError(Ljava/lang/String;)V`
 * 并按字段名反射读写 KeyboardConfig（buttonConfig:I / maxTextLength:I /
 * multilineMode:Z / hintText:Ljava/lang/String; / buttonText:[Ljava/lang/String;）
 * 与 KeyboardData（text:Ljava/lang/String; / button:I）。
 * 部分 3DS 游戏对话 / 命名时触发。NesStation 最小实现：返回空文本 +
 * 默认按钮，游戏内弹出系统输入框的完整体验可后续按上游 UI 补充。
 */
@Keep
object SoftwareKeyboard {

    enum class ValidationError {
        None,
        ButtonOutOfRange,
        MaxDigitsExceeded,
        AtSignNotAllowed,
        PercentNotAllowed,
        BackslashNotAllowed,
        ProfanityNotAllowed,
        CallbackFailed,
        FixedLengthRequired,
        MaxLengthExceeded,
        BlankInputNotAllowed,
        EmptyInputNotAllowed
    }

    class KeyboardConfig {
        @JvmField
        var buttonConfig: Int = 0

        @JvmField
        var maxTextLength: Int = 16

        @JvmField
        var multilineMode: Boolean = false

        @JvmField
        var hintText: String? = null

        @JvmField
        var buttonText: Array<String?> = arrayOfNulls(3)
    }

    class KeyboardData {
        @JvmField
        var text: String = ""

        @JvmField
        var button: Int = 0
    }

    @JvmStatic
    fun execute(config: KeyboardConfig?): KeyboardData {
        // 最小可用实现：回车确认 + 空文本（等待宿主 UI 增强）
        return KeyboardData().apply {
            text = ""
            button = config?.buttonConfig ?: 0
        }
    }

    @JvmStatic
    fun showError(error: String) {
        Log.w("AzaharNative", "SoftwareKeyboard error: $error")
    }
}
