package com.nesstation.app.flash.input

import android.view.KeyEvent

/**
 * 按键名 → Android KeyCode 的映射工具。
 * 设置项中保存的是字符串名（如 "SPACE"），这里负责解析为 [KeyEvent.KEYCODE_*]。
 */
object KeyMapper {

    fun toKeyCode(name: String): Int = when (name) {
        "SPACE"      -> KeyEvent.KEYCODE_SPACE
        "ENTER"      -> KeyEvent.KEYCODE_ENTER
        "TAB"        -> KeyEvent.KEYCODE_TAB
        "CTRL_LEFT"  -> KeyEvent.KEYCODE_CTRL_LEFT
        "CTRL_RIGHT" -> KeyEvent.KEYCODE_CTRL_RIGHT
        "SHIFT_LEFT" -> KeyEvent.KEYCODE_SHIFT_LEFT
        "SHIFT_RIGHT"-> KeyEvent.KEYCODE_SHIFT_RIGHT
        "ALT_LEFT"   -> KeyEvent.KEYCODE_ALT_LEFT
        "ALT_RIGHT"  -> KeyEvent.KEYCODE_ALT_RIGHT
        "ESC", "ESCAPE" -> KeyEvent.KEYCODE_ESCAPE
        "BACK"       -> KeyEvent.KEYCODE_BACK
        "A"          -> KeyEvent.KEYCODE_A
        "B"          -> KeyEvent.KEYCODE_B
        "C"          -> KeyEvent.KEYCODE_C
        "D"          -> KeyEvent.KEYCODE_D
        "E"          -> KeyEvent.KEYCODE_E
        "F"          -> KeyEvent.KEYCODE_F
        "G"          -> KeyEvent.KEYCODE_G
        "H"          -> KeyEvent.KEYCODE_H
        "I"          -> KeyEvent.KEYCODE_I
        "J"          -> KeyEvent.KEYCODE_J
        "K"          -> KeyEvent.KEYCODE_K
        "L"          -> KeyEvent.KEYCODE_L
        "M"          -> KeyEvent.KEYCODE_M
        "N"          -> KeyEvent.KEYCODE_N
        "O"          -> KeyEvent.KEYCODE_O
        "P"          -> KeyEvent.KEYCODE_P
        "Q"          -> KeyEvent.KEYCODE_Q
        "R"          -> KeyEvent.KEYCODE_R
        "S"          -> KeyEvent.KEYCODE_S
        "T"          -> KeyEvent.KEYCODE_T
        "U"          -> KeyEvent.KEYCODE_U
        "V"          -> KeyEvent.KEYCODE_V
        "W"          -> KeyEvent.KEYCODE_W
        "X"          -> KeyEvent.KEYCODE_X
        "Y"          -> KeyEvent.KEYCODE_Y
        "Z"          -> KeyEvent.KEYCODE_Z
        "0"          -> KeyEvent.KEYCODE_0
        "1"          -> KeyEvent.KEYCODE_1
        "2"          -> KeyEvent.KEYCODE_2
        "3"          -> KeyEvent.KEYCODE_3
        "4"          -> KeyEvent.KEYCODE_4
        "5"          -> KeyEvent.KEYCODE_5
        "6"          -> KeyEvent.KEYCODE_6
        "7"          -> KeyEvent.KEYCODE_7
        "8"          -> KeyEvent.KEYCODE_8
        "9"          -> KeyEvent.KEYCODE_9
        "UP"         -> KeyEvent.KEYCODE_DPAD_UP
        "DOWN"       -> KeyEvent.KEYCODE_DPAD_DOWN
        "LEFT"       -> KeyEvent.KEYCODE_DPAD_LEFT
        "RIGHT"      -> KeyEvent.KEYCODE_DPAD_RIGHT
        else         -> KeyEvent.KEYCODE_SPACE
    }

    /** 字母键名 → KeyboardEvent.code (供 JS 注入) */
    fun toJsKeyCode(name: String): Int = when (name) {
        "SPACE"   -> 32
        "ENTER"   -> 13
        "TAB"     -> 9
        "ESC", "ESCAPE" -> 27
        "CTRL_LEFT", "CTRL_RIGHT" -> 17
        "SHIFT_LEFT", "SHIFT_RIGHT" -> 16
        "ALT_LEFT", "ALT_RIGHT" -> 18
        "BACK"    -> 8
        else -> name.firstOrNull()?.code ?: 32
    }
}
