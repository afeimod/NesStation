package com.nesstation.app.ui.online

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import android.net.Uri

/**
 * 注入到 WebView 的 JS 接口（window.Android）。
 *
 * 提供给 JS 调用的原生能力：
 * - [openSwf]      WAFlash 钩子（注入脚本调用），按当前引擎跳到 player.html / waflash.html
 * - [onSwfFound]   SWF 嗅探器回调（扫描页面中的 SWF URL 列表）
 * - [toast] / [vibrate] / [log] / [finish] / [readLocalSwf]
 *
 * 参考 3.3-fix2 WebAppInterface 设计。
 */
class WebAppInterface(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    /** SWF 打开回调（由 WebGameScreen / GameActivity 设置） */
    @Volatile
    var onSwfDetected: ((String, String) -> Unit)? = null

    /** SWF 嗅探器回调（由 WebGameScreen / GameActivity 设置，接收 JSON 数组字符串） */
    @Volatile
    var swfExtractCallback: ((String) -> Unit)? = null

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
     * WAFlash 钩子（注入脚本调用）通知原生打开 SWF。
     * 原生端按当前 FlashPrefs.engine 决定走 player.html 还是 waflash.html。
     */
    @JavascriptInterface
    fun openSwf(swfUrl: String?, pageUrl: String?) {
        if (swfUrl.isNullOrEmpty()) return
        Log.d("WebApp:Flash", "openSwf: $swfUrl (from: $pageUrl)")
        handler.post {
            onSwfDetected?.let { it(swfUrl, pageUrl ?: "") }
        }
    }

    /**
     * SWF 嗅探器回调：JS 扫描器发现 SWF 列表（JSON 数组字符串）。
     * @param json JSON 数组字符串，如 [{"url":"...","title":"...","size":"12MB"}, ...]
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
     * 读取本地 SWF 文件（content:// / file://）并以 Base64 返回。
     * 绕过 WebView 对 content:// URI 的跨域限制。
     */
    @JavascriptInterface
    fun readLocalSwf(uri: String?): String? {
        if (uri.isNullOrEmpty()) return null
        return try {
            Log.d("WebApp:LocalSwf", "Reading: $uri")
            val parsed = Uri.parse(uri)
            val data = context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                ?: throw java.io.IOException("Cannot open file stream")
            Log.d("WebApp:LocalSwf", "Read complete: ${data.size} bytes")
            Base64.encodeToString(data, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("WebApp:LocalSwf", "Read failed: ${e.message}")
            null
        }
    }

    @JavascriptInterface
    fun finish() {
        if (context is Activity) handler.post { context.finish() }
    }
}
