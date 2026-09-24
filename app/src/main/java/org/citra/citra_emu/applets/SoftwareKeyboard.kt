package org.citra.citra_emu.applets

import android.util.Log
import androidx.annotation.Keep

/**
 * Azahar 软键盘系统小程序 —— JNI 契约桩。
 *
 * 原生侧（jni/applets/swkbd.cpp）在 JNI_OnLoad（IDCache 初始化）时缓存：
 *  - `Execute(Lorg/citra/citra_emu/applets/SoftwareKeyboard$KeyboardConfig;)Lorg/citra/citra_emu/applets/SoftwareKeyboard$KeyboardData;`
 *  - `ShowError(Ljava/lang/String;)V`
 * —— 均为 **PascalCase**（AzaharPlus fork 实际契约，经 libazahar.so 反汇编验证；
 * 上游官方 azahar 源码同名），并按字段名反射读写 KeyboardConfig（buttonConfig:I /
 * maxTextLength:I / multilineMode:Z / hintText:Ljava/lang/String; /
 * buttonText:[Ljava/lang/String;）与 KeyboardData（text:Ljava/lang/String; / button:I）。
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

    /**
     * 方法名必须为 `Execute`（PascalCase）—— JNI_OnLoad 按 GetStaticMethodID
     * 字符串名查找，名字不符会抛 NoSuchMethodError → pending exception →
     * SIGABRT 启动闪退。
     */
    @JvmStatic
    fun Execute(config: KeyboardConfig?): KeyboardData {
        // 最小可用实现：回车确认 + 空文本（等待宿主 UI 增强）
        return KeyboardData().apply {
            text = ""
            button = config?.buttonConfig ?: 0
        }
    }

    /** 同上，必须为 `ShowError`（PascalCase）。 */
    @JvmStatic
    fun ShowError(error: String) {
        Log.w("AzaharNative", "SoftwareKeyboard error: $error")
    }
}
