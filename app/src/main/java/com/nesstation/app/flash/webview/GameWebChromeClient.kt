package com.nesstation.app.flash.webview

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * 游戏 WebView 的 Chrome 客户端：
 * 进度、JS 弹窗、控制台日志、文件选择回调、全屏。
 */
open class GameWebChromeClient(
    private val callback: Callback
) : WebChromeClient() {

    interface Callback {
        fun onProgress(progress: Int)
        fun onTitle(title: String?)
        fun onConsole(level: String, msg: String, sourceId: String?, line: Int)
        /** 全屏（Flash 全屏播放） */
        fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
        fun onHideFullscreen()
        /** 文件上传选择回调 */
        fun onFileChooser(callback: ValueCallback<Array<Uri>>, accept: String?): Boolean
        /** ★ v10: 多窗口 —— window.open / target=_blank 由宿主创建弹窗 WebView 并经
         *   WebViewTransport 交接（宿主必须在 resultMsg 上 sendToTarget）；
         *   返回 false = 拒绝弹窗。默认拒绝（向后兼容）。 */
        fun handleCreateWindow(opener: WebView?, isDialog: Boolean, resultMsg: android.os.Message): Boolean = false
        /** ★ v10: window.close() / 页面自行关闭弹窗通知（宿主销毁对应弹窗） */
        fun handleCloseWindow(window: WebView?) {}
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        callback.onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        callback.onTitle(title)
    }

    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        result?.confirm()
        return true
    }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        result?.confirm()
        return true
    }

    override fun onJsPrompt(
        view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?
    ): Boolean {
        result?.confirm()
        return true
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        consoleMessage?.let {
            val level = when (it.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                ConsoleMessage.MessageLevel.WARNING -> "WARN"
                ConsoleMessage.MessageLevel.DEBUG -> "DEBUG"
                else -> "LOG"
            }
            callback.onConsole(level, it.message(), it.sourceId(), it.lineNumber())
            Log.d("WebConsole", "$level ${it.message()} @ ${it.sourceId()}:${it.lineNumber()}")
        }
        return true
    }

    // ---------- 全屏 ----------
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        if (view != null) this.callback.onShowFullscreen(view, callback ?: return)
    }

    override fun onHideCustomView() {
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        callback.onHideFullscreen()
    }

    // ---------- 文件选择（4399 上传头像等） ----------
    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        val accept = fileChooserParams?.acceptTypes?.firstOrNull() ?: "*/*"
        return callback.onFileChooser(filePathCallback ?: return false, accept)
    }

    // ---------- 权限 ----------
    override fun onGeolocationPermissionsShowPrompt(
        origin: String?, callback: GeolocationPermissions.Callback?
    ) {
        callback?.invoke(origin, true, false)
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.grant(request.resources)
    }

    // ---------- 多窗口（window.open / target=_blank → 真实弹窗 WebView） ----------
    override fun onCreateWindow(
        view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
    ): Boolean {
        if (resultMsg == null) return false
        return callback.handleCreateWindow(view, isDialog, resultMsg)
    }

    override fun onCloseWindow(window: WebView?) {
        callback.handleCloseWindow(window)
    }
}
