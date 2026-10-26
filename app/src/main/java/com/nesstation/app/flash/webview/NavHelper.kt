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
        // ★★ v4.3 根因修复「选拉伸依旧 4:3 黑边」：
        //   旧版 scale 只读 flash_scale 偏好，而 game_aspect_ratio 与 flash_scale
        //   是两个独立键 —— 旧版本/其他路径只写 game_aspect_ratio=stretch
        //   而不写 flash_scale 时，scale 回退 showAll：
        //   showAll + forceScale + letterbox=off = 游戏内容保持原比例居中、
        //   影片背景铺满全屏 —— 正是用户反馈的“只能拉伸引擎自身画面，
        //   游戏画面不拉伸”。且 verify() 重载只会重建同一个 URL（坏偏好源头
        //   不变），重载多少次都修不好。
        //   现在画面比例模式优先：aspect != auto 时强制 exactFit；
        //   仅 auto（全屏自适应）时尊重 flash_scale 引擎缩放偏好。
        val aspect = PrefsManager.gameAspectRatio
        val scale = if (aspect != "auto") {
            "exactFit"
        } else {
            when (val prefScale = PrefsManager.sp.getString("flash_scale", null)) {
                "showAll", "noBorder", "exactFit", "noScale" -> prefScale
                else -> "showAll"
            }
        }
        // ★★ v24 修复「SWF 按比例拉伸无效、各种黑边」：
        //   旧版 letterbox 完全不读 flash_letterbox 偏好（两处播放器的
        //   applyAspectRatio 写了却没人消费 = 死键），只从 game_aspect_ratio
        //   自行推导，且对 4:3/16:9 等比例模式错传 "on" —— Ruffle 的
        //   letterbox 渲染路径优先于 scale：外层 CSS 已把 #stage 缩成指定
        //   比例框，框内引擎又按 SWF 原生 stageSize letterbox 加黑边 =
        //   双重黑边，exactFit 永远不生效（必现“拉伸无效果”）。
        //   现在改为：优先读 flash_letterbox 偏好（与 flash_scale 同源），
        //   偏好缺失时才回退到推导（回退映射同步修正：比例模式 → off）。
        //     · auto   = showAll + on/fullscreen（保持原比例，黑边属预期）
        //     · stretch = exactFit + off（无黑边铺满全屏）
        //     · 4:3 等 = exactFit + off（拉伸填满外层比例框，无内层黑边）
        val letterboxPref = PrefsManager.sp.getString("flash_letterbox", null)
        val letterbox: String =
            if (letterboxPref != null && letterboxPref in setOf("on", "off", "fullscreen")) {
                letterboxPref
            } else when (aspect) {
                "stretch" -> "off"
                // 比例模式同样 off：外层比例框 + exactFit 填满，引擎不再叠黑边
                "4:3", "16:9", "16:10", "5:4" -> "off"
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
