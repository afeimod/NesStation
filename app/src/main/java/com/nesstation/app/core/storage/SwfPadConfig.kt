package com.nesstation.app.core.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Data model for SWF virtual-keyboard button layout
// ---------------------------------------------------------------------------

/**
 * A single configurable virtual button for the SWF player keyboard.
 *
 * @param id   unique identifier (stable across saves)
 * @param label  text shown on the button (e.g. "A", "▲", "SPACE")
 * @param key    JavaScript key name sent to the WebView
 *               (e.g. "a", "ArrowUp", " ", "Enter")
 * @param xPct   horizontal position as percentage of screen width (0–100)
 * @param yPct   vertical position as percentage of screen height (0–100)
 * @param sizeDp button diameter / height in dp
 */
data class SwfButton(
    val id: String,
    val label: String,
    val key: String,
    val xPct: Float = 50f,
    val yPct: Float = 80f,
    val sizeDp: Float = 48f
)

/**
 * D-pad / movement control mode.
 * - DPAD: cross-shaped D-pad with arrow keys
 * - WASD: cross-shaped D-pad with WASD keys
 * - JOYSTICK: analog joystick with 8-direction WASD keys
 */
enum class DpadMode { DPAD, WASD, JOYSTICK }

/**
 * Full layout for the SWF virtual keyboard.
 *
 * @param dpadMode  D-pad/Joystick mode (DPAD / WASD / JOYSTICK)
 * @param showPad   whether the keyboard overlay is visible
 * @param buttons   ordered list of buttons (D-pad buttons are always present
 *                  and cannot be deleted — only their position/size changes)
 */
data class SwfPadConfig(
    val dpadMode: DpadMode = DpadMode.JOYSTICK,
    val showPad: Boolean = true,
    val buttons: List<SwfButton> = defaultButtons()
) {
    /** Backwards-compatible alias */
    val useWASD: Boolean get() = dpadMode == DpadMode.WASD

    companion object {
        /** IDs of buttons that cannot be deleted (D-pad + essential keys). */
        val FIXED_IDS = setOf("dpad_up", "dpad_down", "dpad_left", "dpad_right",
                               "space", "enter")

        /**
         * Default button layout — D-pad on the left, action keys on the right,
         * Space + Enter at the bottom centre.
         */
        fun defaultButtons(): List<SwfButton> = listOf(
            // D-pad (left side)
            SwfButton("dpad_up", "▲", "ArrowUp", xPct = 12f, yPct = 70f, sizeDp = 48f),
            SwfButton("dpad_down", "▼", "ArrowDown", xPct = 12f, yPct = 85f, sizeDp = 48f),
            SwfButton("dpad_left", "◀", "ArrowLeft", xPct = 5f, yPct = 77.5f, sizeDp = 48f),
            SwfButton("dpad_right", "▶", "ArrowRight", xPct = 19f, yPct = 77.5f, sizeDp = 48f),
            // Action keys (right side)
            SwfButton("u", "U", "u", xPct = 75f, yPct = 70f, sizeDp = 48f),
            SwfButton("i", "I", "i", xPct = 85f, yPct = 65f, sizeDp = 48f),
            SwfButton("o", "O", "o", xPct = 95f, yPct = 70f, sizeDp = 48f),
            SwfButton("j", "J", "j", xPct = 75f, yPct = 85f, sizeDp = 48f),
            SwfButton("k", "K", "k", xPct = 85f, yPct = 80f, sizeDp = 48f),
            SwfButton("l", "L", "l", xPct = 95f, yPct = 85f, sizeDp = 48f),
            // Centre keys
            SwfButton("space", "SPACE", " ", xPct = 40f, yPct = 90f, sizeDp = 44f),
            SwfButton("enter", "ENTER", "Enter", xPct = 60f, yPct = 90f, sizeDp = 44f)
        )

        /** Create a new custom button with a unique ID. */
        fun newButton(label: String, key: String): SwfButton {
            val id = "btn_${System.currentTimeMillis()}"
            return SwfButton(id, label, key, xPct = 50f, yPct = 50f, sizeDp = 48f)
        }
    }
}

// ---------------------------------------------------------------------------
// Persistence — saves / loads [SwfPadConfig] as JSON in SharedPreferences
// ---------------------------------------------------------------------------

private const val PREFS_NAME = "swf_pad_config"
private const val KEY_CONFIG = "config_json"

object SwfPadStore {

    fun load(context: Context): SwfPadConfig {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_CONFIG, null) ?: return SwfPadConfig()
            parseJson(json)
        } catch (_: Exception) {
            SwfPadConfig()
        }
    }

    fun save(context: Context, config: SwfPadConfig) {
        try {
            val json = toJson(config)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIG, json)
                .apply()
        } catch (_: Exception) { /* ignore */ }
    }

    fun reset(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CONFIG)
                .apply()
        } catch (_: Exception) { /* ignore */ }
    }

    // ---- JSON serialisation ------------------------------------------------

    private fun toJson(config: SwfPadConfig): String {
        val arr = JSONArray()
        config.buttons.forEach { btn ->
            arr.put(JSONObject().apply {
                put("id", btn.id)
                put("label", btn.label)
                put("key", btn.key)
                put("xPct", btn.xPct.toDouble())
                put("yPct", btn.yPct.toDouble())
                put("sizeDp", btn.sizeDp.toDouble())
            })
        }
        val obj = JSONObject().apply {
            put("dpadMode", config.dpadMode.name)
            put("useWASD", config.useWASD) // backward compat
            put("showPad", config.showPad)
            put("buttons", arr)
        }
        return obj.toString()
    }

    private fun parseJson(json: String): SwfPadConfig {
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("buttons") ?: JSONArray()
        val buttons = mutableListOf<SwfButton>()
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            buttons.add(SwfButton(
                id = b.optString("id", "btn_$i"),
                label = b.optString("label", "?"),
                key = b.optString("key", ""),
                xPct = b.optDouble("xPct", 50.0).toFloat(),
                yPct = b.optDouble("yPct", 80.0).toFloat(),
                sizeDp = b.optDouble("sizeDp", 48.0).toFloat()
            ))
        }
        return SwfPadConfig(
            dpadMode = run {
                val modeStr = obj.optString("dpadMode", null as String?)
                when {
                    !modeStr.isNullOrBlank() -> DpadMode.valueOf(modeStr)
                    obj.optBoolean("useWASD", false) -> DpadMode.WASD
                    else -> DpadMode.DPAD
                }
            },
            showPad = obj.optBoolean("showPad", true),
            buttons = if (buttons.isEmpty()) SwfPadConfig.defaultButtons() else buttons
        )
    }
}
