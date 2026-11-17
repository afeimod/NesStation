package com.nesstation.app.ui.swf

import android.net.Uri
import com.nesstation.app.ui.swf.FlashPrefs.Engine

/**
 * Flash 导航与 URL 工具（参考 3.3-fix2 NavHelper）。
 *
 * 集中生成 flash.local 虚拟域名下的播放器 URL，按当前 Flash 引擎选择目标页面。
 * 之前 SwfPlayerScreen 和 WebGameScreen 各自拼接 URL，导致引擎切换和参数不一致。
 */
object NavHelper {

    /** 内置 Ruffle 播放器（assets/player.html） */
    private const val PLAYER_PAGE = "https://flash.local/player.html"

    /** 内置 WAFlash 播放器（assets/waflash.html） */
    private const val WAFLASH_PAGE = "https://flash.local/waflash.html"

    /**
     * 构造内置 Flash 播放器 URL（按当前引擎选择目标页面）。
     *
     * @param swfUrl SWF 文件 URL（远程 HTTP(S) / content:// / file://）
     * @param base   来源页面 URL（referer / 解相对路径用，可空）
     * @param engine 引擎：Ruffle → player.html，WAFlash → waflash.html
     * @param quality 画质：low / medium / high / best
     * @param autoplay 是否自动播放
     * @param title 标题（可选）
     */
    fun playerUrl(
        swfUrl: String,
        base: String? = null,
        engine: Engine,
        quality: String = "high",
        autoplay: Boolean = true,
        title: String? = null
    ): String {
        val baseUrl = when (engine) {
            Engine.WAFLASH -> WAFLASH_PAGE
            Engine.RUFFLE  -> PLAYER_PAGE
        }
        val u = Uri.parse(baseUrl).buildUpon()
            .appendQueryParameter("swf", swfUrl)
            .appendQueryParameter("engine", engine.value)
            .appendQueryParameter("quality", quality)
            .appendQueryParameter("autoplay", if (autoplay) "on" else "off")
        base?.let { u.appendQueryParameter("base", it) }
        title?.let { u.appendQueryParameter("title", it) }
        return u.build().toString()
    }

    /** 快速判断 URL 是否为 SWF 资源 */
    fun isSwf(url: String): Boolean =
        url.endsWith(".swf", ignoreCase = true) || url.contains(".swf?", ignoreCase = true)

    /** 判断是否为本地文件 URI（content:// 或 file://） */
    fun isLocalFile(url: String): Boolean =
        url.startsWith("content://") || url.startsWith("file://")
}
