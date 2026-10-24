package com.nesstation.app.flash.webview

import android.net.Uri
import com.nesstation.app.flash.data.GameType
import com.nesstation.app.flash.data.PrefsManager

// 同包的 RuffleInjector（无 import 必要）

/**
 * 导航与 URL 工具：构造 4399 各类页面地址、判断 SWF、生成内置播放器地址。
 */
object NavHelper {

    /** 4399 移动版主页 */
    const val URL_4399_MOBILE = "https://h.4399.com/"
    /** 4399 PC 版（含 Flash 游戏） */
    const val URL_4399_PC = "https://www.4399.com/"
    /** 全部分类 */
    const val URL_4399_CATEGORY = "https://h.4399.com/wap/allcategory.htm"
    /** 热门小游戏榜 */
    const val URL_4399_HOT = "https://h.4399.com/wap/xyxRank.htm"

    /** 根据 ID 与类型构造 4399 游戏页 */
    fun gameUrl(id: String, type: GameType): String = when (type) {
        GameType.FLASH -> "https://www.4399.com/flash/$id.htm"
        GameType.H5    -> "https://h.4399.com/play/$id.htm"
        GameType.URL   -> id
        GameType.LOCAL_SWF -> id
    }

    /** 判断是否为 SWF 资源 */
    fun isSwf(url: String): Boolean =
        url.endsWith(".swf", ignoreCase = true) || url.contains(".swf?", ignoreCase = true)

    /** 判断是否为本地文件 URI（content:// 或 file://） */
    fun isLocalFile(url: String): Boolean =
        url.startsWith("content://") || url.startsWith("file://")

    /** 构造内置 Flash 播放器地址（根据引擎选择不同播放器页面） */
    fun playerUrl(swfUrl: String, base: String? = null, title: String? = null): String {
        // 从 PrefsManager.sp 直接读取（不依赖 FlashPrefs），
        // 因为 PrefsManager.sp 才是真正的数据源。
        val scale = PrefsManager.sp.getString("flash_scale", null) ?: "showAll"
        val letterbox = when (PrefsManager.gameAspectRatio) {
            // 4:3 / 16:9 / 16:10 / 5:4 等指定比例时，把 Ruffle letterbox 强制 on，
            // 让 Ruffle 自身按 SWF 原生 stageSize letterbox，外层 buildRuffleAspectRatioScript
            // 只控制外框大小 + 居中，**不会**改 ruffle-player 内部 stage 尺寸。
            // 这样 Ruffle 内核能稳定执行引擎内部 letterbox 逻辑，画面比例完全可控。
            "4:3", "16:9", "16:10", "5:4" -> "on"
            // ★ stretch（全屏拉伸）：letterbox 关 + scale=exactFit（applyAspectRatio
            //   已写入 flash_scale）—— SWF 无黑边拉伸铺满全屏；forceScale 由
            //   player.html 按 aspect != auto 自动启用，压制 SWF 内部
            //   Stage.scaleMode 自管（看图类 SWF"比例定死"的根因）。
            "stretch" -> "off"
            else -> "fullscreen"
        }
        val quality = PrefsManager.flashQuality
        // WAFlash 引擎使用独立的 waflash.html 页面（canvas 渲染 + ES module）
        // 从 flash.local 虚拟域名加载，确保 Emscripten 的 fetch/XHR 能正常工作
        if (PrefsManager.flashEngine == "waflash") {
            val u = Uri.parse("https://flash.local/waflash.html")
                .buildUpon()
                .appendQueryParameter("swf", swfUrl)
                .appendQueryParameter("aspect", PrefsManager.gameAspectRatio)
            base?.let { u.appendQueryParameter("base", it) }
            title?.let { u.appendQueryParameter("title", it) }
            return u.build().toString()
        }
        // Ruffle / swf2js 使用 player.html（从 flash.local 加载，确保 XHR/fetch 能被拦截）
        val u = Uri.parse("https://flash.local/player.html")
            .buildUpon()
            .appendQueryParameter("swf", swfUrl)
            .appendQueryParameter("engine", PrefsManager.flashEngine)
            .appendQueryParameter("autoplay", if (PrefsManager.isFlashAutoplay) "on" else "off")
            .appendQueryParameter("scale", scale)
            .appendQueryParameter("letterbox", letterbox)
            .appendQueryParameter("quality", quality)
            .appendQueryParameter("aspect", PrefsManager.gameAspectRatio)
            .apply {
                // 自定义四角布局:enabled=true 时传 box="l,t,r,b"
                if (PrefsManager.isCustomLayoutEnabled) {
                    appendQueryParameter(
                        "box",
                        "${PrefsManager.customLayoutLeft},${PrefsManager.customLayoutTop}," +
                        "${PrefsManager.customLayoutRight},${PrefsManager.customLayoutBottom}"
                    )
                }
            }
        // Ruffle 模式传递 CDN/本地路径
        // WAFlash 使用独立播放器页面，不需要传递 cdn/path
        if (PrefsManager.flashEngine != "waflash" && PrefsManager.flashEngine != "swf2js") {
            u.appendQueryParameter("cdn", RuffleInjector.scriptUrl())
            u.appendQueryParameter("path", RuffleInjector.publicPath())
        }
        base?.let { u.appendQueryParameter("base", it) }
        title?.let { u.appendQueryParameter("title", it) }
        return u.build().toString()
    }

    /** 规范化用户输入：自动补 https://、识别纯数字为 4399 游戏 ID */
    fun normalizeInput(input: String): Pair<String, GameType> {
        val trimmed = input.trim()
        // 纯数字 → 4399 游戏 ID（默认 Flash）
        if (trimmed.matches(Regex("^\\d+$"))) {
            return gameUrl(trimmed, GameType.FLASH) to GameType.FLASH
        }
        // 已带协议
        var url = trimmed
        if (!url.startsWith("http://") && !url.startsWith("https://") &&
            !url.startsWith("file://")) {
            url = "https://$url"
        }
        return url to GameType.URL
    }
}
