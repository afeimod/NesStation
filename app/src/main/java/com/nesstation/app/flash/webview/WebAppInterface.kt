package com.nesstation.app.flash.webview

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * 注入到 WebView 的 JS 接口（window.Android）。
 * 1:1 移植自 3.3-fix2 WebAppInterface，去掉 FavoriteStore 依赖 + 把 GameActivity 强转改为 callback。
 *
 * 提供给网页调用原生的能力：Toast、震动、播放 SWF、JS 嗅探器回调、读取本地 SWF。
 */
class WebAppInterface(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    /** SWF 提取结果回调（由 WebGameScreen 设置） */
    @Volatile
    var swfExtractCallback: ((String) -> Unit)? = null

    /** 打开 SWF 回调（由 WebGameScreen 设置）。参数：swfUrl, pageUrl */
    @Volatile
    var openSwfCallback: ((String, String) -> Unit)? = null

    /** 全屏切换回调（由 WebGameScreen / SwfPlayerScreen 设置） */
    @Volatile
    var fullscreenCallback: (() -> Unit)? = null

    @JavascriptInterface
    fun toast(msg: String?) {
        handler.post { Toast.makeText(context, msg ?: "", Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun vibrate(durationMs: Int) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        @Suppress("DEPRECATION")
        vibrator.vibrate(durationMs.coerceIn(1, 500).toLong())
    }

    @JavascriptInterface
    fun log(tag: String?, msg: String?) {
        Log.d("WebApp:${tag ?: "JS"}", msg ?: "")
    }

    /**
     * 打开 SWF 播放器（WAFlash 检测脚本调用）。
     * 根据当前 Flash 引擎设置跳转到对应的播放器页面。
     */
    @JavascriptInterface
    fun openSwf(swfUrl: String?, pageUrl: String?) {
        if (swfUrl.isNullOrEmpty()) return
        Log.d("WebApp:WAFlash", "openSwf: $swfUrl (from: $pageUrl)")
        val cb = openSwfCallback ?: return
        val base = pageUrl ?: ""
        handler.post { cb(swfUrl, base) }
    }

    @JavascriptInterface
    fun finish() {
        if (context is Activity) handler.post { context.finish() }
    }

    /**
     * 切换全屏状态（由 player.html 的 CSS 全屏调用）。
     * 通过 fullscreenCallback 回调到 Activity 层隐藏/恢复系统栏。
     */
    @JavascriptInterface
    fun toggleFullscreen() {
        fullscreenCallback?.let { cb -> handler.post { cb() } }
    }

    /**
     * JS 嗅探器回调：报告在页面中发现的 SWF URL 列表。
     */
    @JavascriptInterface
    fun onSwfFound(json: String?) {
        if (json.isNullOrEmpty()) return
        Log.d("WebApp:SwfExtract", "发现 SWF: $json")
        swfExtractCallback?.let { cb ->
            handler.post { cb(json) }
        }
    }

    /**
     * 读取本地 SWF 文件内容（Base64），供 JS 创建 Blob URL。
     */
    @JavascriptInterface
    fun readLocalSwf(uri: String?): String? {
        if (uri.isNullOrEmpty()) return null
        return try {
            Log.d("WebApp:LocalSwf", "读取本地文件: $uri")
            val parsed = android.net.Uri.parse(uri)
            val data = context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                ?: throw java.io.IOException("无法打开文件流")
            Log.d("WebApp:LocalSwf", "读取完成: ${data.size} bytes")
            android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("WebApp:LocalSwf", "读取失败: ${e.message}")
            null
        }
    }
}
