package com.nesstation.app.flash.data

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * 偏好封装（1:1 移植自 3.3-fix2 PrefsManager）。
 *
 * 集中管理设置项的读写，避免散落的 getString/getBoolean。
 * 命名空间改为 com.nesstation.app.flash，逻辑完全一致。
 */
object PrefsManager {

    lateinit var sp: SharedPreferences
        private set

    fun init(context: Context) {
        sp = PreferenceManager.getDefaultSharedPreferences(context)
    }

    // ---- 通用 ----
    val orientation: String get() = sp.getString("orientation", "landscape") ?: "landscape"
    val isBlockAds: Boolean get() = sp.getBoolean("block_ads", true)
    val isMouseEnabled: Boolean get() = sp.getBoolean("mouse_enabled", false)
    val isLandscapeGame: Boolean get() = sp.getBoolean("landscape_game", true)

    // ---- Flash ----
    val isFlashEnabled: Boolean get() = sp.getBoolean("flash_enabled", true)
    val isFlashAutoplay: Boolean get() = sp.getBoolean("flash_autoplay", true)
    val flashCdn: String get() = sp.getString("flash_cdn", "local") ?: "local"
    val flashQuality: String get() = sp.getString("flash_quality", "high") ?: "high"

    // ---- 手柄 ----
    val isGamepadEnabled: Boolean get() = sp.getBoolean("gamepad_enabled", true)
    val pageZoom: Int get() = sp.getInt("page_zoom", 0)
    val gamepadAlpha: Int
        get() = ((sp.getInt("gamepad_opacity", 60) / 100f) * 255).toInt().coerceIn(40, 255)
    val gamepadScale: Float
        get() = (sp.getInt("gamepad_scale", 100) / 100f).coerceIn(0.5f, 2.0f)
    val dpadScale: Float
        get() = (sp.getInt("dpad_scale", 100) / 100f).coerceIn(0.5f, 2.0f)
    val dpadMode: String get() = sp.getString("dpad_mode", "joystick") ?: "joystick"
    val dpadOffsetX: Int get() = sp.getInt("dpad_offset_x", 0)
    val dpadOffsetY: Int get() = sp.getInt("dpad_offset_y", 0)
    val dpadPosX: Float get() = sp.getFloat("dpad_pos_x", -1f)
    val dpadPosY: Float get() = sp.getFloat("dpad_pos_y", -1f)

    val pageZoomMode: String get() = sp.getString("page_zoom_mode", "auto") ?: "auto"
    // 默认 100%：40% 会让画面过小，违反用户直觉；范围 25-200 仍按 Ruffle 官方约定保留。
    val pageZoomManual: Int get() = sp.getInt("page_zoom_manual", 100).coerceIn(25, 200)
    val actionOffsetX: Int get() = sp.getInt("action_offset_x", 0)
    val actionOffsetY: Int get() = sp.getInt("action_offset_y", 0)
    val actionPosX: Float get() = sp.getFloat("action_pos_x", -1f)
    val actionPosY: Float get() = sp.getFloat("action_pos_y", -1f)
    val gamepadKeyCount: Int
        get() = sp.getInt("gamepad_key_count", 6).coerceIn(2, 18)

    private val gamepadKeyDefaults = arrayOf(
        "J", "K", "L", "U", "I", "O",
        "1", "2", "3", "4", "5", "6",
        "7", "8", "9", "Q", "E", "R"
    )

    val gamepadKeys: List<String>
        get() {
            val count = gamepadKeyCount
            return (0 until count).map { i ->
                sp.getString("gamepad_key_${i + 1}", gamepadKeyDefaults.getOrElse(i) { "J" }) ?: "J"
            }
        }
    val gamepadKeyVisible: List<Boolean>
        get() {
            val count = gamepadKeyCount
            return (0 until count).map { i -> sp.getBoolean("gamepad_key_${i + 1}_visible", true) }
        }
    val selectKey: String get() = sp.getString("select_key", "TAB") ?: "TAB"
    val startKey: String get() = sp.getString("start_key", "ENTER") ?: "ENTER"
    val isSystemButtonsVisible: Boolean get() = sp.getBoolean("system_buttons_visible", false)
    val isDpadVisible: Boolean get() = sp.getBoolean("dpad_visible", true)
    val isMouseModeEnabled: Boolean get() = sp.getBoolean("mouse_mode_enabled", false)
    val isMouseButtonsVisible: Boolean get() = sp.getBoolean("mouse_buttons_visible", false)
    val mouseOffsetX: Int get() = sp.getInt("mouse_offset_x", 0)
    val mouseOffsetY: Int get() = sp.getInt("mouse_offset_y", 0)
    val mousePosX: Float get() = sp.getFloat("mouse_pos_x", -1f)
    val mousePosY: Float get() = sp.getFloat("mouse_pos_y", -1f)
    val systemPosX: Float get() = sp.getFloat("system_pos_x", -1f)
    val systemPosY: Float get() = sp.getFloat("system_pos_y", -1f)

    // ---- Flash 引擎 ----
    val flashEngine: String get() {
        val v = sp.getString("flash_engine", "ruffle") ?: "ruffle"
        return if (v == "ruffle_mhhf") "ruffle" else v
    }
    val uaMode: String get() = sp.getString("ua_mode", "desktop") ?: "desktop"
    val gamepadAKey: String get() = sp.getString("gamepad_a_key", "SPACE") ?: "SPACE"
    val gamepadBKey: String get() = sp.getString("gamepad_b_key", "ENTER") ?: "ENTER"

    // ---- 3D 视角旋转 ----
    val isCameraRotationEnabled: Boolean get() = sp.getBoolean("camera_rotation_enabled", false)

    // ---- 画面比例（游戏画面 letterbox）----
    // auto(全屏自适应) / stretch(全屏拉伸) / 4:3 / 16:9 / 16:10 / 5:4
    val gameAspectRatio: String get() = sp.getString("game_aspect_ratio", "auto") ?: "auto"

    // ---- 悬浮球菜单排列（用户反馈「菜单不能一直横着放」）----
    // auto = 跟随屏幕方向（横屏 2×2 分区并排 / 竖屏四节竖排，默认）；
    // portrait = 强制竖排（四节自上而下堆叠，窄而高 —— 横屏看图等
    //            竖向内容场景不挡画面）；
    // landscape = 强制横排（2×2 分区并排，矮而宽）。
    val floatingMenuLayout: String get() = sp.getString("floating_menu_layout", "auto") ?: "auto"

    // ---- 自定义四角布局（按 parent 0..1 归一化坐标）----
    // 启用时游戏画面按 left/top/right/bottom 的归一化值放入矩形。
    // 默认 0/0/1/1 即全屏。
    val isCustomLayoutEnabled: Boolean get() = sp.getBoolean("custom_layout_enabled", false)
    val customLayoutLeft: Float get() = sp.getFloat("custom_layout_left", 0f).coerceIn(0f, 1f)
    val customLayoutTop: Float get() = sp.getFloat("custom_layout_top", 0f).coerceIn(0f, 1f)
    val customLayoutRight: Float get() = sp.getFloat("custom_layout_right", 1f).coerceIn(0f, 1f)
    val customLayoutBottom: Float get() = sp.getFloat("custom_layout_bottom", 1f).coerceIn(0f, 1f)

    fun setCustomLayout(enabled: Boolean, left: Float, top: Float, right: Float, bottom: Float) {
        sp.edit()
            .putBoolean("custom_layout_enabled", enabled)
            .putFloat("custom_layout_left", left.coerceIn(0f, 1f))
            .putFloat("custom_layout_top", top.coerceIn(0f, 1f))
            .putFloat("custom_layout_right", right.coerceIn(0f, 1f))
            .putFloat("custom_layout_bottom", bottom.coerceIn(0f, 1f))
            .apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(listener)
}
