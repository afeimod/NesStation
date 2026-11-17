package com.nesstation.app.ui.settings

import android.content.Context
import android.view.KeyEvent

/**
 * Persistent store for custom key mappings.
 *
 * Keys are action IDs (e.g. "nes_a", "snes_l") and values are Android
 * [KeyEvent.keyCode] integers. The mappings are stored in a dedicated
 * SharedPreferences file (`keymap_v1`) so they survive app restarts.
 *
 * The [EmulatorScreen] reads these mappings to route physical gamepad /
 * D-pad key events to the correct controller bits for each platform.
 */
object KeyMapStore {
    private const val PREFS_NAME = "keymap_v1"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Get the custom keyCode for [actionId], or null if none is set. */
    fun get(ctx: Context, actionId: String): Int? {
        val v = prefs(ctx).getInt(actionId, -1)
        return if (v < 0) null else v
    }

    /** Set the custom [keyCode] for [actionId]. */
    fun put(ctx: Context, actionId: String, keyCode: Int) {
        prefs(ctx).edit().putInt(actionId, keyCode).apply()
    }

    /** Remove the custom mapping for [actionId], reverting to the default. */
    fun remove(ctx: Context, actionId: String) {
        prefs(ctx).edit().remove(actionId).apply()
    }

    /** Human-readable label for an Android keyCode. */
    fun keyCodeToLabel(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP       -> "方向上"
            KeyEvent.KEYCODE_DPAD_DOWN     -> "方向下"
            KeyEvent.KEYCODE_DPAD_LEFT     -> "方向左"
            KeyEvent.KEYCODE_DPAD_RIGHT    -> "方向右"
            KeyEvent.KEYCODE_DPAD_CENTER   -> "确认"
            KeyEvent.KEYCODE_BUTTON_A      -> "手柄 A"
            KeyEvent.KEYCODE_BUTTON_B      -> "手柄 B"
            KeyEvent.KEYCODE_BUTTON_X      -> "手柄 X"
            KeyEvent.KEYCODE_BUTTON_Y      -> "手柄 Y"
            KeyEvent.KEYCODE_BUTTON_L1     -> "L1"
            KeyEvent.KEYCODE_BUTTON_R1     -> "R1"
            KeyEvent.KEYCODE_BUTTON_L2     -> "L2"
            KeyEvent.KEYCODE_BUTTON_R2     -> "R2"
            KeyEvent.KEYCODE_BUTTON_START  -> "Start"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
            KeyEvent.KEYCODE_BUTTON_MODE   -> "Mode"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "左摇杆"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "右摇杆"
            KeyEvent.KEYCODE_ENTER         -> "Enter"
            KeyEvent.KEYCODE_SPACE         -> "Space"
            KeyEvent.KEYCODE_SHIFT_LEFT    -> "左 Shift"
            KeyEvent.KEYCODE_SHIFT_RIGHT   -> "右 Shift"
            KeyEvent.KEYCODE_CTRL_LEFT     -> "左 Ctrl"
            KeyEvent.KEYCODE_CTRL_RIGHT    -> "右 Ctrl"
            KeyEvent.KEYCODE_ALT_LEFT      -> "左 Alt"
            KeyEvent.KEYCODE_ALT_RIGHT     -> "右 Alt"
            KeyEvent.KEYCODE_TAB           -> "Tab"
            KeyEvent.KEYCODE_ESCAPE        -> "Esc"
            KeyEvent.KEYCODE_BACK         -> "返回"
            KeyEvent.KEYCODE_MENU         -> "菜单"
            KeyEvent.KEYCODE_SEARCH       -> "搜索"
            else -> {
                // Fallback: use Android's symbolic name or "Key N"
                val name = KeyEvent.keyCodeToString(keyCode)
                if (name.startsWith("KEYCODE_")) name.removePrefix("KEYCODE_") else "Key $keyCode"
            }
        }
    }
}
