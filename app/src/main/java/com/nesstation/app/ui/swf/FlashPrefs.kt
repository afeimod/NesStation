package com.nesstation.app.ui.swf

import com.nesstation.app.flash.data.PrefsManager

/**
 * Flash 引擎偏好设置。
 *
 * 管理 SWF 播放器的引擎选择、画质、自动播放、缩放模式等设置。
 * 为了和老 PrefsManager 共享数据源（避免双份存储漂移），这里直接走
 * [PrefsManager.sp] 的同 key：flash_engine / flash_quality / flash_autoplay / flash_scale。
 *
 * scale 合法值（参考 Ruffle 官方 StageScaleMode）：
 *   - "showAll"  默认：等比缩放，舞台完整可见
 *   - "noBorder" 等比缩放，舞台铺满视口（可能被裁切）
 *   - "exactFit" 非等比缩放，舞台铺满视口
 *   - "noScale"  不缩放，舞台使用 SWF 原生像素大小
 */
object FlashPrefs {

    private const val KEY_ENGINE = "flash_engine"       // "ruffle" | "waflash"
    private const val KEY_QUALITY = "flash_quality"     // "low" | "medium" | "high" | "best"
    private const val KEY_AUTOPLAY = "flash_autoplay"   // true | false
    private const val KEY_SCALE = "flash_scale"         // "showAll" | "noBorder" | "exactFit" | "noScale"

    /** 引擎类型 */
    enum class Engine(val value: String, val displayName: String) {
        RUFFLE("ruffle", "Ruffle (AS1/2/3, 内置中文字体)"),
        WAFLASH("waflash", "WAFlash (AS2/AS3, Canvas渲染)");

        companion object {
            fun fromValue(v: String?): Engine = values().firstOrNull { it.value == v } ?: RUFFLE
        }
    }

    fun getEngine(): Engine = Engine.fromValue(PrefsManager.sp.getString(KEY_ENGINE, null))

    fun setEngine(engine: Engine) {
        PrefsManager.sp.edit().putString(KEY_ENGINE, engine.value).apply()
    }

    fun getQuality(): String = PrefsManager.sp.getString(KEY_QUALITY, "high") ?: "high"

    fun setQuality(quality: String) {
        PrefsManager.sp.edit().putString(KEY_QUALITY, quality).apply()
    }

    fun isAutoplay(): Boolean = PrefsManager.sp.getBoolean(KEY_AUTOPLAY, true)

    fun setAutoplay(autoplay: Boolean) {
        PrefsManager.sp.edit().putBoolean(KEY_AUTOPLAY, autoplay).apply()
    }

    /** 取得当前 scale，无效值或缺失值都回退到 Ruffle 官方默认 "showAll"。 */
    fun getScale(): String {
        val v = PrefsManager.sp.getString(KEY_SCALE, "showAll") ?: "showAll"
        return when (v) {
            "showAll", "noBorder", "exactFit", "noScale" -> v
            else -> "showAll"
        }
    }

    fun setScale(scale: String) {
        val normalized = when (scale) {
            "showAll", "noBorder", "exactFit", "noScale" -> scale
            else -> "showAll"
        }
        PrefsManager.sp.edit().putString(KEY_SCALE, normalized).apply()
    }
}
