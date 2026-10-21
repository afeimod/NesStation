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
        // ---------- ★ v12.1 兼容层（v10 老签名，带默认实现）----------
        // 用户仓库里的 SWF 播放器（ui/swf/SwfPlayerScreen.kt）按 v10 老签名
        // override 这些成员（无 view 参数）。v12 换成带 view 的新签名时漏了仓库内
        // 其他实现者 → CI 报 "'onProgress'/'onTitle' overrides nothing" /
        // "does not implement abstract member onProgress"。
        // 新老并存 + 默认委托：老实现者编译恢复、行为不变；新宿主继续按标签门控。
        fun onProgress(progress: Int) {}
        fun onTitle(title: String?) {}
        /** ★ v12: 带 view —— 进度/标题按标签门控（后台标签不串台）；
         *  默认向下委托老签名（只实现老签名的宿主行为不变） */
        fun onProgress(view: WebView?, progress: Int) = onProgress(progress)
        fun onTitle(view: WebView?, title: String?) = onTitle(title)
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
        callback.onProgress(view, newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        callback.onTitle(view, title)
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
