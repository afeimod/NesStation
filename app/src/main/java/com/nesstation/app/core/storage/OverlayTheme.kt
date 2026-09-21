package com.nesstation.app.core.storage

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nesstation.app.core.model.GamePlatform

/**
 * 单个按键的主题（颜色或自定义图片，二者可并存）。
 *
 * 所有颜色用 Long?（ARGB，如 0xFFE74C3C），null 表示「使用该核心的默认色」，
 * 未配置时整个 ButtonTheme 可以为 null。存储层用 Gson 序列化进
 * [PadLayout.overlayThemeJson] 的 JSON blob（每核心独立，互不影响）。
 *
 * @param color           常规状态下按键颜色；null = 默认色
 * @param pressedColor    按压状态下按键颜色；null = 使用默认按压效果（提亮 + 高光）
 * @param imageUri        常规状态按键图片（png/jpg，SAF 持久 URI）；null = 不用图片
 * @param pressedImageUri 按压状态按键图片；null = 复用 [imageUri]（若设置了）或默认按压效果
 */
data class ButtonTheme(
    val color: Long? = null,
    val pressedColor: Long? = null,
    val imageUri: String? = null,
    val pressedImageUri: String? = null
)

/**
 * 画面遮罩 / 按键主题配置（每个模拟核心独立一份）。
 *
 * 背景遮罩（游戏画面周边）：
 *  - [bgColor]     ：游戏画面周边的背景颜色（letterbox / 空白区域）；
 *                    null = 默认纯黑
 *  - [bgImageUri]  ：背景图片（png/jpg，SAF 持久 URI）；设置后优先于 [bgColor]
 *  - [maskEnabled] ：是否在游戏画面上叠加一层半透明遮罩
 *  - [maskColor]   ：遮罩颜色（ARGB）；null = 不叠加颜色层（✕ 可取消选择，
 *                    修复"选了遮罩图片/不想用颜色时颜色还是黑色"）。
 *                    常与 [maskAlpha] 配合
 *  - [maskAlpha]   ：遮罩强度 0–255（叠加到 maskColor 的 alpha 上）
 *  - [maskImageUri]：遮罩图片（png/jpg，SAF 持久 URI）；设置后优先于 [maskColor]
 *
 * 按键主题：
 *  - [buttons]：按键 id → [ButtonTheme]，id 与
 *    [PadLayoutStore.getAvailableButtons] 的 id 完全一致
 *    （"dpad" / "a" / "b" / "x" / "y" / "start" / "select" / "l" / "r" /
 *    "l2" / "r2" / "ta" / "tb" / "l3" / "r3" …）。
 */
data class OverlayTheme(
    val bgColor: Long? = null,
    val bgImageUri: String? = null,
    val maskEnabled: Boolean = false,
    val maskColor: Long? = null,
    val maskAlpha: Int = 80,
    val maskImageUri: String? = null,
    val buttons: MutableMap<String, ButtonTheme> = mutableMapOf()
) {
    /** 是否已经对任意按键做过自定义（用于 UI 显示“恢复默认”入口）。 */
    val hasButtonCustomizations: Boolean get() = buttons.isNotEmpty()

    /**
     * 是否完全未自定义（与默认实例等价）。
     *
     * 用于运行时的 GBA→GB 主题回退：设置页里 mGBA 核心只有「GB / GBA」一个
     * 入口（写 "GB" 键），没有单独的 GBA 入口；当 "GBA" 键从未被配置过时，
     * 运行时回退读 "GB" 键，避免 GBA 游戏永远显示默认黑色背景/默认按键。
     */
    val isDefault: Boolean
        get() = bgColor == null && bgImageUri == null && !maskEnabled &&
                maskImageUri == null && buttons.isEmpty()

    fun button(id: String): ButtonTheme? = buttons[id]

    /**
     * 设置某按键的主题。颜色与图片字段都为空时移除该按键配置
     *（还原为核心默认色）。
     */
    fun setButton(id: String, theme: ButtonTheme): OverlayTheme {
        if (theme.color == null && theme.pressedColor == null &&
            theme.imageUri == null && theme.pressedImageUri == null) {
            buttons.remove(id)
        } else {
            buttons[id] = theme
        }
        return this
    }

    /** 完全清除所有按键主题。 */
    fun clearButtons(): OverlayTheme {
        buttons.clear()
        return this
    }
}

// ===========================================================================
// Gson 序列化：整个 PadLayout 只存一个 overlayThemeJson 字段，
// 内部按平台名（GamePlatform.name）分键 → 每核心独立存储、互不影响。
// ===========================================================================

private val overlayGson = Gson()

/** 把 JSON blob 解析成 平台名 → 遮罩主题 映射；损坏/空串返回空表。 */
fun parseOverlayThemes(json: String): MutableMap<String, OverlayTheme> {
    if (json.isBlank()) return mutableMapOf()
    return try {
        val type = object : TypeToken<Map<String, OverlayTheme>>() {}.type
        val map: Map<String, OverlayTheme>? = overlayGson.fromJson(json, type)
        map?.mapValues { it.value }?.toMutableMap() ?: mutableMapOf()
    } catch (_: Exception) {
        mutableMapOf()
    }
}

/** 平台名 → 遮罩主题 映射序列化为 JSON blob。 */
fun formatOverlayThemes(map: Map<String, OverlayTheme>): String =
    overlayGson.toJson(map)

/** 读取某核心的遮罩主题；未配置时返回默认值（不修改原 JSON）。 */
fun overlayThemeGet(json: String, platform: GamePlatform): OverlayTheme =
    parseOverlayThemes(json)[platform.name] ?: OverlayTheme()

/** 写入某核心的遮罩主题，返回新的 overlayThemeJson 字符串。 */
fun overlayThemeSet(json: String, platform: GamePlatform, theme: OverlayTheme): String {
    val map = parseOverlayThemes(json)
    map[platform.name] = theme
    return formatOverlayThemes(map)
}

/** 清除某核心的遮罩主题（恢复默认）。 */
fun overlayThemeClear(json: String, platform: GamePlatform): String {
    val map = parseOverlayThemes(json)
    map.remove(platform.name)
    return formatOverlayThemes(map)
}

/**
 * 各核心可配置按键主题的按键列表 （id → 显示名）。
 * 与 [PadLayoutStore.getAvailableButtons] 保持一致；DOS 补充常用的
 * 方向键/AB 键，JAVA 沿用游戏手柄键位。
 */
fun themeButtonsFor(platform: GamePlatform): List<Pair<String, String>> = when (platform) {
    // GB 与 GBA 共用「GB / GBA」同一个设置入口（配置存 "GB" 键，GBA 运行时
    // 未单独配置则回退读取，见 EmulatorScreen）。GBA 比 GB 多 L/R 肩键，
    // 这里把 L/R 也列出来供自定义（GB 游戏不渲染 L/R，配置无副作用）。
    GamePlatform.GB -> listOf(
        "dpad" to "十字键", "a" to "A 键", "b" to "B 键",
        "ta" to "连射A", "tb" to "连射B",
        "l" to "L 键（仅GBA）", "r" to "R 键（仅GBA）",
        "start" to "START", "select" to "SELECT"
    )
    GamePlatform.DOS -> listOf(
        "dpad" to "十字键", "a" to "A 键", "b" to "B 键",
        "start" to "START", "select" to "SELECT"
    )
    GamePlatform.JAVA -> listOf(
        "dpad" to "十字键", "a" to "A 键", "b" to "B 键",
        "x" to "X 键", "y" to "Y 键",
        "start" to "START", "select" to "SELECT"
    )
    else -> PadLayoutStore.getAvailableButtons(platform)
        .filter { it.first != "l3" && it.first != "r3" }
}