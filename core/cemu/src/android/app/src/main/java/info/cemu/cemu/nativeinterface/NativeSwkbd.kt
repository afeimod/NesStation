package info.cemu.cemu.nativeinterface

import androidx.annotation.Keep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object NativeSwkbd {

    object SwkbdState {
        data class State(
            val text: String,
            val maxLength: Int,
        )

        private val _data = MutableStateFlow<State?>(null)
        val data = _data.asStateFlow()

        // ★★★ NesStation R15: 中文输入修复 —— 上游 BlacklistedCharactersRegex
        //   [^\da-zA-Z -/;:',.?!#...]=="只允许 ASCII 字母数字与少量符号"，
        //   任何中文/日文/韩文字符（以及 Emoji）都会在 updateText 里被整字符
        //   删掉：拼音输入法的组合文本（如 "zhong"选字前的候选串本身合法，
        //   但上屏的“中”字）一提交就被清空 —— 表现为“输入法不支持中文”。
        //   实际上游 C++ 侧 formStringBuffer 为 wchar/UTF-16，GetInputFormString
        //   按 UTF-16BE 回给游戏，本身完全放得下 CJK；被卡住的只有这一层
        //   Java 黑名单。改为只过滤【控制字符】（C0 + DEL），保留全部可见
        //   Unicode（中日韩、全角符号、Emoji 等）；游戏侧能显示多少取决于
        //   游戏自带字体（与 Wii U 真机一致），但文本本身可以完整送达游戏。
        //   超长截断仍按 UTF-16 单元计数（与上游 maxLength 语义一致），
        //   中文一字 = 1 单元，与 C++ formStringLength 同口径。
        private val ControlCharsRegex =
            Regex("[\\u0000-\\u001F\\u007F]")

        fun updateText(text: String) {
            val currentState = _data.value ?: return

            val numChars = if (currentState.maxLength > 0) currentState.maxLength else text.length
            val newText = ControlCharsRegex.replace(text, "").take(numChars)
            _data.value = currentState.copy(
                text = newText
            )

            onTextChanged(newText)
        }

        @Keep
        @JvmStatic
        @Suppress("unused")
        private fun makeVisible(initialText: String?, maxLength: Int) {
            _data.value = State(initialText ?: "", maxLength)
        }

        @Keep
        @JvmStatic
        @Suppress("unused")
        private fun hide() {
            _data.value = null
        }
    }

    @JvmStatic
    external fun initializeSwkbd()

    @JvmStatic
    private external fun onTextChanged(text: String?)

    @JvmStatic
    external fun onFinishedInputEdit()
}
