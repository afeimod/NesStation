package com.nesstation.app.ui.online

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.nesstation.app.flash.data.GameType
import com.nesstation.app.flash.data.PrefsManager
import com.nesstation.app.flash.input.ActionButtonView
import com.nesstation.app.flash.input.DPadView
import com.nesstation.app.flash.input.KeyMapper
import com.nesstation.app.flash.input.MouseControlView
import com.nesstation.app.flash.webview.GameWebChromeClient
import com.nesstation.app.flash.webview.GameWebView
import com.nesstation.app.flash.webview.GameWebViewClient
import com.nesstation.app.flash.webview.NavHelper
import com.nesstation.app.flash.webview.WebAppInterface
import com.nesstation.app.flash.widget.FloatingMenuView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.flash.download.DownloadStatus
import com.nesstation.app.flash.download.SwfDownloadItem
import com.nesstation.app.flash.download.SwfDownloadManager
import com.nesstation.app.ui.swf.SwfExtractDialog
import com.nesstation.app.ui.swf.SwfItem
import kotlinx.coroutines.flow.MutableStateFlow

// ★ Neon 主题适配：播放器底色随主题切换（Neon=深空黑蓝）
private val PrimaryBackground
    get() = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.Bg
        else Color(0xFF0F1115)

/**
 * 在线网页游戏/Flash 游戏的 Compose 入口。
 *
 * 1:1 移植自 3.3-fix2 GameActivity：把 3.3 的 setupWebView / setupGamepad / setupFloatingMenu
 * 全部用 AndroidView 嵌入到 Compose 里。
 *
 * @param url   入口 URL（页面 URL 或 .swf 直链）
 * @param uaMode  入口 UA 模式：desktop / mobile / ie_compat
 * @param onExit  退出回调
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebGameScreen(
    url: String,
    uaMode: String = "desktop",
    onExit: () -> Unit
) {
    val context = LocalContext.current

    // 确保 PrefsManager 已初始化（应用启动时也应该调用一次）
    remember { PrefsManager.init(context) }

    var gamepadVisible by remember { mutableStateOf(PrefsManager.isGamepadEnabled) }
    var isMouseEnabled by remember { mutableStateOf(PrefsManager.isMouseEnabled) }
    var isFullscreen by remember { mutableStateOf(false) }
    var isLandscape by remember { mutableStateOf(true) }
    var isPositionEditMode by remember { mutableStateOf(false) }
    val errorMessage = remember { mutableStateOf<String?>(null) }
    val reloadTrigger = remember { mutableStateOf(0) }
    val gamepadRebuildTrigger = remember { mutableStateOf(0) }
    val swfExtractJson = remember { mutableStateOf<String?>(null) }
    val downloadProgress = remember { mutableStateOf<List<SwfDownloadItem>>(emptyList()) }
    val isDownloadingActive = remember { mutableStateOf(false) }
    val showKeyDialog = remember { mutableStateOf(false) }
    val showFlashDialog = remember { mutableStateOf(false) }
    val showZoomDialog = remember { mutableStateOf(false) }
    val showUaDialog = remember { mutableStateOf(false) }
    val showAspectRatioDialog = remember { mutableStateOf(false) }

    // 持有各 view 的引用
    val webViewRef = remember { mutableStateOf<GameWebView?>(null) }
    val dpadRef = remember { mutableStateOf<DPadView?>(null) }
    val actionRef = remember { mutableStateOf<ActionButtonView?>(null) }
    val mouseRef = remember { mutableStateOf<MouseControlView?>(null) }
    val floatingMenuRef = remember { mutableStateOf<FloatingMenuView?>(null) }
    val webAppInterfaceRef = remember { mutableStateOf<WebAppInterface?>(null) }
    val systemButtonsRef = remember { mutableStateOf<View?>(null) }
    val startBtnRef = remember { mutableStateOf<View?>(null) }
    val selectBtnRef = remember { mutableStateOf<View?>(null) }
    val localSwfUri = remember { mutableStateOf<String?>(null) }
    val mainBoxRef = remember { mutableStateOf<ViewGroup?>(null) }
    // WebView 自定义全屏视图（网页 requestFullscreen 触发）
    val customViewRef = remember { mutableStateOf<View?>(null) }
    val customViewCallbackRef = remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    // 引擎选择对话框 / 提取 SWF 对话框
    var pendingSwfAction by remember { mutableStateOf<String?>(null) }

    // ---------- File chooser ----------
    val filePathCallback = remember { mutableStateOf<ValueCallback<Array<android.net.Uri>>?>(null) }
    val activityResultLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val arr = uris.takeIf { it.isNotEmpty() }?.toTypedArray()
        filePathCallback.value?.onReceiveValue(arr)
        filePathCallback.value = null
    }

    // ---------- Activity (for fullscreen / orientation) ----------
    val activity = context as? Activity

    // ---------- 物理键盘（实体键盘）透传 ----------
    val keyEventHandler: (KeyEvent) -> Boolean = { event ->
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // 系统 BACK 键：网页能后退就后退，不能后退才退出。
            // 之前是无条件 onExit() —— 用户按返回键直接退出游戏，体验差。
            if (event.action == KeyEvent.ACTION_UP) {
                val wv = webViewRef.value
                if (wv != null && wv.canGoBack()) wv.goBack() else onExit()
            }
            true
        } else if (event.keyCode in GameWebView.GAME_KEYS) {
            val wv = webViewRef.value
            if (wv != null) {
                wv.dispatchKeyEvent(event)
                true
            } else false
        } else false
    }
    BackHandler(enabled = true, onBack = {
        // 系统返回键 / 悬浮菜单"返回"共用同一逻辑：
        // 优先网页后退，无可后退历史时退出。
        val wv = webViewRef.value
        if (wv != null && wv.canGoBack()) wv.goBack() else onExit()
    })

    fun applyOrientation(landscape: Boolean) {
        activity?.requestedOrientation = if (landscape)
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    fun applyFullscreen(full: Boolean) {
        if (full) {
            // 简单实现：隐藏系统栏
            activity?.window?.let { w ->
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).hide(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                )
            }
        } else {
            activity?.window?.let { w ->
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, true)
                androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).show(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                )
            }
        }
    }

    // 切换全屏
    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        applyFullscreen(isFullscreen)
        floatingMenuRef.value?.isFullscreen = isFullscreen
    }

    fun toggleOrientation() {
        isLandscape = !isLandscape
        applyOrientation(isLandscape)
        floatingMenuRef.value?.isLandscape = isLandscape
    }

    fun toggleGamepad() {
        gamepadVisible = !gamepadVisible
    }

    fun rebuildGamepad() {
        gamepadRebuildTrigger.value = gamepadRebuildTrigger.value + 1
    }

    fun toggleMouse() {
        isMouseEnabled = !isMouseEnabled
        PrefsManager.sp.edit().putBoolean("mouse_enabled", isMouseEnabled).apply()
        PrefsManager.sp.edit().putBoolean("mouse_buttons_visible", isMouseEnabled).apply()
        val wv = webViewRef.value
        if (wv != null) {
            if (isMouseEnabled) {
                wv.evaluateJavascript(MOUSE_CURSOR_SCRIPT, null)
            } else {
                wv.evaluateJavascript(
                    "(function(){var c=document.getElementById('__mouseCursor');if(c)c.remove();window.__mouseEnabled=false;})();", null
                )
            }
        }
    }

    fun reload() {
        reloadTrigger.value = reloadTrigger.value + 1
    }

    fun applyEngineAndReload(engine: String) {
        PrefsManager.sp.edit().putString("flash_engine", engine).putBoolean("flash_enabled", true).apply()
        reload()
    }

    fun applyFlashEnabled(enabled: Boolean) {
        PrefsManager.sp.edit().putBoolean("flash_enabled", enabled).apply()
        reload()
    }

    fun applyZoom(mode: String, manual: Int) {
        PrefsManager.sp.edit()
            .putString("page_zoom_mode", mode)
            .putInt("page_zoom_manual", manual)
            .apply()
        // 直接用 WebSettings.textZoom + setInitialScale(与 viewport meta 无关)
        webViewRef.value?.applyPageZoom(if (mode == "manual") manual else 100)
        reload()
    }

    fun applyUa(mode: String) {
        // 三个兼容模式（desktop / ie_compat / mobile）都必须立即切换 UA + 重载页面，
        // 否则用户改完设置完全看不到效果，要退出重进才生效。
        // 之前的实现：desktop 模式不调用 useUaMode()，依赖 GameWebViewClient 在重载时
        // "智能判断" —— 但 GameWebViewClient 里根本没有读取 ua_mode=desktop 的代码，
        // 导致 desktop 模式永远不生效。
        PrefsManager.sp.edit().putString("ua_mode", mode).apply()
        val wv = webViewRef.value
        if (wv != null) {
            // 三个模式都通过 useUaMode() 切换 userAgentString。
            // useUaMode 内部：
            //   desktop  -> DESKTOP_UA (Windows Chrome)
            //   ie_compat-> IE_COMPAT_UA (Trident/7.0)
            //   其他      -> 默认移动版 UA + 4399App 标识
            wv.useUaMode(mode)
        }
        reload()
    }

    fun toggleCameraRotation() {
        val enabled = !PrefsManager.isCameraRotationEnabled
        PrefsManager.sp.edit().putBoolean("camera_rotation_enabled", enabled).apply()
        val wv = webViewRef.value
        if (wv != null) {
            wv.cameraRotationEnabled = enabled
            if (enabled) {
                wv.evaluateJavascript(GameWebViewClient.CAMERA_ROTATION_SCRIPT, null)
                Toast.makeText(context, "视角旋转已开启，拖动屏幕旋转视角", Toast.LENGTH_LONG).show()
            } else {
                wv.evaluateJavascript(
                    "(function(){window.__cameraRotation=false;var s=document.getElementById('__cameraRotateStyle');if(s)s.remove();})();", null
                )
                Toast.makeText(context, "视角旋转已关闭", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun applyAspectRatio(ratio: String) {
        PrefsManager.sp.edit().putString("game_aspect_ratio", ratio).apply()
        val wv = webViewRef.value
        if (wv != null) {
            // 按当前引擎选择正确的 letterbox 脚本。
            // - Ruffle: buildRuffleAspectRatioScript (让 ruffle-player 内部 letterbox 生效)
            // - WAFlash: buildAspectRatioScript (WAFlash 自身不做 letterbox, 必须外层 CSS 强制)
            val script = if (PrefsManager.flashEngine == "waflash") {
                GameWebViewClient.buildAspectRatioScript(ratio)
            } else {
                GameWebViewClient.buildRuffleAspectRatioScript(ratio)
            }
            wv.evaluateJavascript(script, null)
        }
    }

    fun showSwfActions(swfUrl: String) {
        pendingSwfAction = swfUrl
    }

    fun playSwfWithEngine(swfUrl: String) {
        val wv = webViewRef.value
        if (wv != null) {
            val playerUrl = NavHelper.playerUrl(swfUrl, base = wv.url, title = null)
            wv.loadUrl(playerUrl)
        }
    }

    // ---- SWF 嗅探器 (与 3.3 SWF_SNIFFER_SCRIPT 一致) ----
    fun extractSwfFromPage() {
        Toast.makeText(context, "正在扫描页面中的 SWF...", Toast.LENGTH_SHORT).show()
        val iface = webAppInterfaceRef.value
        if (iface != null) {
            iface.swfExtractCallback = { json ->
                swfExtractJson.value = json
            }
        }
        // 关键:先清空之前的结果,避免上一次"提取 SWF"后没关弹框,本次扫描时
        // 弹框还显示着旧的 SWF/资源列表(就是用户说的"提取后面任何的游戏都是相同的资源"现象)。
        swfExtractJson.value = null
        // 用 URL 作为嗅探上下文的一部分注入,让 JS 端可以用 pageUrl 过滤掉来自其他页面的资源
        // (4399 嵌套 iframe + SPA 切页时常驻的 iframe 会让嗅探器扫到旧页面的资源)
        val wv = webViewRef.value
        val pageUrl = wv?.url ?: ""
        wv?.evaluateJavascript(
            """
            (function(){
              try { window.__swfExtractPageUrl = ${if (pageUrl.isEmpty()) "location.href" else "\"" + pageUrl.replace("\"", "\\\"") + "\""}; } catch(e) {}
            })();
            """.trimIndent(),
            null
        )
        webViewRef.value?.evaluateJavascript(SWF_SNIFFER_SCRIPT, null)
    }

    // ---- 释放按键 ----
    fun releaseAllKeys() {
        webViewRef.value?.releaseAllKeys()
    }

    // Compose 主容器
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBackground)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FrameLayoutGameContainer(ctx).apply {
                    mainBoxRef.value = this
                }
            },
            update = { _ ->
                // 每次 reload 触发重新加载 URL
                val wv = webViewRef.value
                if (wv != null) {
                    val currentUrl = wv.url ?: url
                    val isSwf = NavHelper.isSwf(currentUrl) || NavHelper.isSwf(url) || NavHelper.isLocalFile(url)
                    if (isSwf) {
                        val playerUrl = NavHelper.playerUrl(url, base = null, title = null)
                        wv.loadUrl(playerUrl)
                    } else if (url.contains("4399.com")) {
                        wv.loadUrl(url, mapOf("Referer" to "https://www.4399.com/"))
                    } else {
                        wv.loadUrl(url)
                    }
                }
            }
        )

        // Download progress overlay - fixed at top
        if (isDownloadingActive.value) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xE61A1A2E))
                    .padding(8.dp)
                    .align(Alignment.TopCenter)
            ) {
                val items = downloadProgress.value
                val completed = items.count { it.status == DownloadStatus.COMPLETED }
                val total = items.size
                val currentPercent = items.filter { it.status == DownloadStatus.DOWNLOADING }.maxByOrNull { it.progress }?.progress ?: 0

                Column {
                    Text("下载进度: $completed/$total" + if (currentPercent > 0) " (当前: $currentPercent%)" else "", color = Color.White, fontSize = 12.sp)
                    LinearProgressIndicator(
                        progress = { if (total > 0) completed.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = Color(0xFFFFC107)
                    )
                    // Show individual file status
                    items.filter { it.status != DownloadStatus.COMPLETED }.forEach { item ->
                        Text("${item.title}: ${when (item.status) { DownloadStatus.DOWNLOADING -> "下载中 ${item.progress}%"; DownloadStatus.FAILED -> "失败(重试${item.retryCount})"; DownloadStatus.PENDING -> "等待中"; DownloadStatus.CANCELLED -> "已取消"; else -> "" }}",
                            color = Color.White, fontSize = 10.sp)
                    }
                }
            }
        }
    }

    // 在容器创建后用 LaunchedEffect 把各 view 加进去
    LaunchedEffect(mainBoxRef.value) {
        val container = mainBoxRef.value ?: return@LaunchedEffect
        if (webViewRef.value == null) {
            // 创建 WebView
            val wv = GameWebView(container.context)
            // WebView 创建时应用用户保存的缩放
            if (PrefsManager.pageZoomMode == "manual") {
                wv.applyPageZoom(PrefsManager.pageZoomManual)
            }
            val webAppInterface = WebAppInterface(container.context)
            webAppInterfaceRef.value = webAppInterface

            // 注入 SWF 打开回调
            webAppInterface.openSwfCallback = { swfUrl, _ ->
                val wv2 = webViewRef.value
                if (wv2 != null) {
                    val playerUrl = NavHelper.playerUrl(swfUrl, base = wv2.url, title = null)
                    wv2.loadUrl(playerUrl)
                }
            }
            // 注入 SWF 提取回调
            webAppInterface.swfExtractCallback = { json ->
                swfExtractJson.value = json
            }
            // 注入全屏切换回调（player.html CSS 全屏调用）
            webAppInterface.fullscreenCallback = { toggleFullscreen() }

            // 客户端与回调
            val chromeCallback = object : GameWebChromeClient.Callback {
                override fun onProgress(progress: Int) {}
                override fun onTitle(title: String?) {}
                override fun onConsole(level: String, msg: String, sourceId: String?, line: Int) {}
                // WebView 全屏：网页调用 requestFullscreen() 时触发。
                // 把 WebView 内部生成的全屏 view 加到 mainBoxRef 覆盖在 WebView 上层，
                // 让 WebView 内部状态正常推进，避免卡死。
                override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
                    customViewRef.value = view
                    customViewCallbackRef.value = callback
                    val parent = mainBoxRef.value
                    if (parent != null) {
                        parent.addView(view, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        ))
                        // 把悬浮菜单拉到最上层，确保全屏期间仍可操作
                        floatingMenuRef.value?.bringToFront()
                    }
                }
                override fun onHideFullscreen() {
                    val view = customViewRef.value
                    val cb = customViewCallbackRef.value
                    if (view != null) {
                        val parent = view.parent as? ViewGroup
                        parent?.removeView(view)
                    }
                    cb?.onCustomViewHidden()
                    customViewRef.value = null
                    customViewCallbackRef.value = null
                }
                override fun onFileChooser(callback: ValueCallback<Array<android.net.Uri>>, accept: String?): Boolean {
                    filePathCallback.value?.onReceiveValue(null)
                    filePathCallback.value = callback
                    val mimes = accept?.split(",")?.toTypedArray() ?: arrayOf("*/*")
                    try { activityResultLauncher.launch(mimes) } catch (e: Exception) { callback.onReceiveValue(null); filePathCallback.value = null }
                    return true
                }
            }

            val viewClientCallback = object : GameWebViewClient.Callback {
                override fun onPageStarted(url: String?) {
                    webViewRef.value?.releaseAllKeys()
                }
                override fun onPageFinished(url: String?) {}
                override fun onProgress(progress: Int) {}
                override fun onError(url: String?, errorCode: Int, description: String?) {
                    if (errorCode == -1) return
                    errorMessage.value = "加载失败: $description"
                }
                override fun onSwfIntercepted(swfUrl: String, pageUrl: String) {
                    val wv2 = webViewRef.value
                    if (wv2 != null) {
                        val playerUrl = NavHelper.playerUrl(swfUrl, base = pageUrl, title = null)
                        wv2.loadUrl(playerUrl)
                    }
                }
                override fun shouldInjectRuffle(url: String?): Boolean {
                    if (url == null) return false
                    if (url.startsWith("file:///android_asset/")) return false
                    if (url.startsWith("https://flash.local/")) return false
                    val lower = url.lowercase()
                    if (lower.contains("/login") || lower.contains("/signin") ||
                        lower.contains("/register") || lower.contains("/api/") ||
                        lower.contains("/ajax/") || lower.contains("/account") ||
                        lower.contains("/user/") || lower.contains("/passport") ||
                        lower.contains("/auth") || lower.contains("/logout")) return false
                    return PrefsManager.isFlashEnabled
                }
                override fun getCachedSwfPath(): String? = null
                override fun getLocalSwfUri(): String? = localSwfUri.value
                override fun getLocalSwfDir(): String? {
                    val uri = localSwfUri.value ?: return null
                    return try {
                        when {
                            uri.startsWith("content://") -> {
                                // For content:// URIs, return the parent document URI
                                val parsed = android.net.Uri.parse(uri)
                                // Try to get parent by removing the last path segment
                                val parentUri = parsed.toString().substringBeforeLast("%2F")
                                    .substringBeforeLast("/")
                                if (parentUri != uri) parentUri else null
                            }
                            uri.startsWith("file://") -> {
                                val path = android.net.Uri.parse(uri).path ?: return null
                                val file = java.io.File(path)
                                file.parentFile?.absolutePath
                            }
                            else -> {
                                val file = java.io.File(uri)
                                if (file.parentFile != null) file.parentFile?.absolutePath else null
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("WebGameScreen", "获取SWF目录失败: ${e.message}")
                        null
                    }
                }
            }

            val wvClient = GameWebViewClient(viewClientCallback)

            wv.apply {
                addJavascriptInterface(webAppInterface, "Android")
                webChromeClient = object : GameWebChromeClient(chromeCallback) {}
                webViewClient = wvClient
                // UA 模式
                if (uaMode == "ie_compat") {
                    useUaMode("ie_compat")
                } else if (uaMode == "mobile") {
                    useUaMode("mobile")
                } else {
                    useDesktopMode(true)
                }
                cameraRotationEnabled = PrefsManager.isCameraRotationEnabled
                setOnKeyListener { _, _, event ->
                    keyEventHandler(event)
                }
            }
            wv.injectDocumentStartScripts()

            container.addView(wv, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            webViewRef.value = wv

            // 初始加载
            val isSwf = NavHelper.isSwf(url) || NavHelper.isLocalFile(url)
            if (isSwf) {
                val playerUrl = NavHelper.playerUrl(url, base = null, title = null)
                wv.loadUrl(playerUrl)
            } else if (url.contains("4399.com")) {
                wv.loadUrl(url, mapOf("Referer" to "https://www.4399.com/"))
            } else {
                wv.loadUrl(url)
            }
        }
    }

    // 创建手柄与菜单
    LaunchedEffect(mainBoxRef.value, gamepadVisible, isMouseEnabled, gamepadRebuildTrigger.value) {
        val container = mainBoxRef.value ?: return@LaunchedEffect
        val density = container.resources.displayMetrics.density

        // 清掉旧手柄控件
        listOfNotNull(
            dpadRef.value, actionRef.value, mouseRef.value,
            systemButtonsRef.value, floatingMenuRef.value
        ).forEach { container.removeView(it) }

        // 创建 DPad
        val dpad = DPadView(container.context).apply {
            targetWebView = webViewRef.value
            overlayAlpha = PrefsManager.gamepadAlpha
            isDragMode = isPositionEditMode
        }
        dpadRef.value = dpad
        val dpadSize = (140 * density).toInt()
        val dpadLp = android.widget.FrameLayout.LayoutParams(dpadSize, dpadSize).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            bottomMargin = (24 * density).toInt()
            marginStart = (16 * density).toInt()
        }
        if (gamepadVisible) container.addView(dpad, dpadLp)

        // 创建 Action Buttons
        val action = ActionButtonView(container.context).apply {
            targetWebView = webViewRef.value
            overlayAlpha = PrefsManager.gamepadAlpha
            isDragMode = isPositionEditMode
        }
        actionRef.value = action
        val baseSize = (160 * density).toInt()
        val count = PrefsManager.gamepadKeyCount
        val sizeMult = if (count > 6) 1f + (count - 6) * 0.12f else 1f
        val actionSize = (baseSize * PrefsManager.gamepadScale * sizeMult).toInt()
        val actionLp = android.widget.FrameLayout.LayoutParams(actionSize, actionSize).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            bottomMargin = (24 * density).toInt()
            marginEnd = (16 * density).toInt()
        }
        if (gamepadVisible) container.addView(action, actionLp)

        // 创建 System Buttons (Start/Select)
        val sysContainer = android.widget.LinearLayout(container.context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        val selectBtn = android.widget.Button(container.context).apply {
            text = "Select"
            setOnClickListener { webViewRef.value?.injectKey(KeyMapper.toKeyCode(PrefsManager.selectKey)) }
        }
        val startBtn = android.widget.Button(container.context).apply {
            text = "Start"
            setOnClickListener { webViewRef.value?.injectKey(KeyMapper.toKeyCode(PrefsManager.startKey)) }
        }
        sysContainer.addView(selectBtn)
        sysContainer.addView(startBtn)
        systemButtonsRef.value = sysContainer
        startBtnRef.value = startBtn
        selectBtnRef.value = selectBtn
        val sysLp = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = (80 * density).toInt()
        }
        if (gamepadVisible && PrefsManager.isSystemButtonsVisible) container.addView(sysContainer, sysLp)

        // 创建 MouseControl
        val mouse = MouseControlView(container.context).apply {
            targetWebView = webViewRef.value
            overlayAlpha = PrefsManager.gamepadAlpha
            isDragMode = isPositionEditMode
        }
        mouseRef.value = mouse
        val mouseSize = (220 * density).toInt()
        val mouseLp = android.widget.FrameLayout.LayoutParams(mouseSize, (80 * density).toInt()).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = (200 * density).toInt()
        }
        if (isMouseEnabled) container.addView(mouse, mouseLp)

        // 创建悬浮菜单
        val menu = FloatingMenuView(container.context)
        menu.setCallbacks(object : FloatingMenuView.Callbacks {
            override fun onToggleFullscreen() = toggleFullscreen()
            override fun onToggleOrientation() = toggleOrientation()
            override fun onToggleGamepad() = toggleGamepad()
            override fun onToggleMouse() = toggleMouse()
            override fun onOpenKeyMapping() { showKeyDialog.value = true }
            override fun onOpenFlashSettings() { showFlashDialog.value = true }
            override fun onOpenPageZoom() { showZoomDialog.value = true }
            override fun onOpenUaMode() { showUaDialog.value = true }
            override fun onRefresh() = reload()
            override fun onBack() {
                // 悬浮菜单"返回"按钮：
                // 优先网页后退；无可后退历史时不直接退出（用户可以用"关闭"按钮退出），
                // 而是提示"已是第一页"——避免误触退出游戏。
                val wv = webViewRef.value
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    Toast.makeText(context, "已是第一页，点\"关闭\"退出", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onClose() = onExit()
            override fun onExtractSwf() = extractSwfFromPage()
            override fun onToggleCameraRotation() = toggleCameraRotation()
            override fun onOpenAspectRatio() { showAspectRatioDialog.value = true }
        })
        menu.isFullscreen = isFullscreen
        menu.isLandscape = isLandscape
        floatingMenuRef.value = menu
        menu.attachTo(container)
    }

    // 切换手柄可见性时释放按键
    LaunchedEffect(gamepadVisible) {
        if (!gamepadVisible) webViewRef.value?.releaseAllKeys()
    }

    // ========== 真正的网页刷新 ==========
    // reload() 只是把 reloadTrigger +1，这里监听变化并实际调用 WebView.reload()。
    // 之前的实现：reload() 只更新 trigger，没有任何 LaunchedEffect 监听它，
    // 导致悬浮菜单的"刷新"按钮、UA 切换、缩放设置等所有调 reload() 的地方都
    // 不刷新网页 —— 用户感觉"刷新按钮没作用"、"兼容模式改完不生效"。
    LaunchedEffect(reloadTrigger.value) {
        if (reloadTrigger.value > 0) {
            webViewRef.value?.reload()
        }
    }

    // 释放资源
    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        }
    }

    // 错误提示
    errorMessage.value?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage.value = null },
            title = { Text("错误") },
            text = { Text(msg) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { errorMessage.value = null }) {
                    Text("确定")
                }
            }
        )
    }

    // SWF 提取结果对话框
    swfExtractJson.value?.let { json ->
        SwfExtractDialog(
            json = json,
            onDismiss = { swfExtractJson.value = null },
            onPlay = { url ->
                playSwfWithEngine(url)
                swfExtractJson.value = null
            },
            onDownload = { items ->
                if (!isDownloadingActive.value) {
                    val manager = SwfDownloadManager(context)
                    manager.setProgressListener { list -> downloadProgress.value = list }
                    manager.setCompleteListener { list ->
                        isDownloadingActive.value = false
                        val success = list.count { it.status == DownloadStatus.COMPLETED }
                        val failed = list.count { it.status == DownloadStatus.FAILED }
                        Toast.makeText(context, "下载完成: $success 个成功" + if (failed > 0) ", $failed 个失败" else "", Toast.LENGTH_LONG).show()
                    }
                    isDownloadingActive.value = true
                    val pageUrl = webViewRef.value?.url ?: ""
                    // 分离 SWF 和资源文件
                    val swfItems = items.filter { it.type == "swf" }
                    val resItems = items.filter { it.type == "resource" }
                    // 生成游戏文件夹名（取第一个 SWF 的标题，清理非法字符）
                    val gameFolder = if (swfItems.isNotEmpty()) {
                        swfItems.first().title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(50).ifBlank { "GameBox" }
                    } else if (resItems.isNotEmpty()) {
                        "resources"
                    } else {
                        ""
                    }
                    val swfUrls = swfItems.map { it.url to it.title }
                    val resUrls = resItems.map { it.url to it.subDir }
                    manager.startDownload(swfUrls, pageUrl, gameFolder, resUrls)
                    // 关键:立即 dismiss dialog,让用户回到游戏画面看到顶部 progress overlay。
                    // 之前 dialog 一直显示,挡住 progress overlay,用户看不到下载进度变化。
                    swfExtractJson.value = null
                } else {
                    Toast.makeText(context, "正在下载中，请等待", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // 引擎选择对话框
    if (showFlashDialog.value) {
        FlashEngineDialog(
            onPick = { engine ->
                applyEngineAndReload(engine)
                showFlashDialog.value = false
            },
            onDismiss = { showFlashDialog.value = false }
        )
    }

    // 页面缩放对话框
    if (showZoomDialog.value) {
        PageZoomDialog(
            onApply = { mode, manual ->
                applyZoom(mode, manual)
                showZoomDialog.value = false
            },
            onDismiss = { showZoomDialog.value = false }
        )
    }

    // UA 模式对话框
    if (showUaDialog.value) {
        UaModeDialog(
            onPick = { mode ->
                applyUa(mode)
                showUaDialog.value = false
            },
            onDismiss = { showUaDialog.value = false }
        )
    }

    // 按键设置对话框
    if (showKeyDialog.value) {
        KeyMappingDialog(
            onReload = { rebuildGamepad() },
            onToggleMouseButtons = { toggleMouse() },
            isPositionEditMode = isPositionEditMode,
            onTogglePositionEdit = {
                isPositionEditMode = !isPositionEditMode
                dpadRef.value?.isDragMode = isPositionEditMode
                actionRef.value?.isDragMode = isPositionEditMode
                mouseRef.value?.isDragMode = isPositionEditMode
            },
            onToggleCameraRotation = { toggleCameraRotation() },
            onDismiss = { showKeyDialog.value = false }
        )
    }

    // ========== 画面比例弹窗 ==========
    if (showAspectRatioDialog.value) {
        val ratios = arrayOf("全屏自适应 (auto)", "4:3", "16:9", "16:10", "5:4")
        val values = arrayOf("auto", "4:3", "16:9", "16:10", "5:4")
        val current = PrefsManager.gameAspectRatio
        val checked = values.indexOf(current).coerceAtLeast(0)
        android.app.AlertDialog.Builder(context)
            .setTitle("画面比例")
            .setSingleChoiceItems(ratios, checked) { dlg, which ->
                applyAspectRatio(values[which])
                dlg.dismiss()
                showAspectRatioDialog.value = false
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { showAspectRatioDialog.value = false }
            .show()
    }
}

/** FrameLayout 子类，作为手柄 + WebView + 菜单的承载容器 */
@SuppressLint("ViewConstructor")
private class FrameLayoutGameContainer(context: Context) : android.widget.FrameLayout(context)

@Composable
private fun FlashEngineDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val engines = arrayOf("Ruffle (推荐)", "WAFlash", "关闭 Flash")
    val values = arrayOf("ruffle", "waflash", "off")
    val current = if (PrefsManager.isFlashEnabled) PrefsManager.flashEngine else "off"
    val checked = values.indexOf(current).coerceAtLeast(0)
    android.app.AlertDialog.Builder(context)
        .setTitle("Flash 引擎")
        .setSingleChoiceItems(engines, checked) { dlg, which ->
            if (values[which] == "off") {
                PrefsManager.sp.edit().putBoolean("flash_enabled", false).apply()
                onPick("off")
            } else {
                PrefsManager.sp.edit().putString("flash_engine", values[which]).putBoolean("flash_enabled", true).apply()
                onPick(values[which])
            }
            dlg.dismiss()
        }
        .setNegativeButton("取消", null)
        .setOnDismissListener { onDismiss() }
        .show()
}

@Composable
private fun PageZoomDialog(
    onApply: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val mode = PrefsManager.pageZoomMode
    val manual = PrefsManager.pageZoomManual

    // 用 LinearLayout 放 SeekBar + 文字提示
    val container = android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(48, 32, 48, 16)
    }

    val currentText = android.widget.TextView(context).apply {
        text = if (mode == "auto") "模式：自动" else "缩放：$manual%"
        textSize = 16f
        gravity = android.view.Gravity.CENTER
    }
    container.addView(currentText)

    val seekBar = android.widget.SeekBar(context).apply {
        max = 175          // 25% ~ 200% → 0 ~ 175
        progress = (manual - 25).coerceIn(0, 175)
        // 拖动时实时更新文字
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                val pct = p + 25
                currentText.text = "缩放：$pct%"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
    }
    // 给 SeekBar 设置 layoutParams，确保宽度填满
    seekBar.layoutParams = android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
    )
    container.addView(seekBar)

    val hint = android.widget.TextView(context).apply {
        text = "范围：25% — 200%"
        textSize = 12f
        gravity = android.view.Gravity.CENTER
        setTextColor(android.graphics.Color.GRAY)
    }
    container.addView(hint)

    val dialog = android.app.AlertDialog.Builder(context)
        .setTitle("页面缩放")
        .setView(container)
        .setPositiveButton("应用") { _, _ ->
            onApply("manual", seekBar.progress + 25)
        }
        .setNegativeButton("自动") { _, _ ->
            onApply("auto", manual)
        }
        .setNeutralButton("取消", null)
        .setOnDismissListener { onDismiss() }
        .create()

    dialog.show()
}

@Composable
private fun UaModeDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val modes = arrayOf("desktop" to "桌面模式 (Chrome)", "ie_compat" to "兼容模式 (IE11)", "mobile" to "移动模式")
    val current = PrefsManager.uaMode
    val checked = modes.indexOfFirst { it.first == current }.coerceAtLeast(0)
    android.app.AlertDialog.Builder(context)
        .setTitle("浏览器兼容模式")
        .setSingleChoiceItems(modes.map { it.second }.toTypedArray(), checked) { dlg, which ->
            onPick(modes[which].first)
            dlg.dismiss()
        }
        .setNegativeButton("取消", null)
        .setOnDismissListener { onDismiss() }
        .show()
}

@Composable
private fun KeyMappingDialog(
    onReload: () -> Unit,
    onToggleMouseButtons: () -> Unit,
    isPositionEditMode: Boolean,
    onTogglePositionEdit: () -> Unit,
    onToggleCameraRotation: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val keyCount = PrefsManager.gamepadKeyCount
    val keys = arrayOf("J","K","L","U","I","O","A","B","C","D","E","F","G","H","M","N","P","Q","R","S","T","W","X","Y","Z","SPACE","ENTER","TAB","ESC","0","1","2","3","4","5","6","7","8","9")

    val menuItems = arrayOf(
        "按键映射 (选择按键修改)",
        "添加按键 (当前: $keyCount)",
        "删除按键 (当前: $keyCount)",
        "Start/Select 映射",
        "方向键模式 (DPAD/WASD/摇杆)",
        "方向键大小",
        "动作按键大小",
        "显示/隐藏按键",
        "添加/隐藏鼠标按钮",
        "位置编辑模式 (拖动调整)" + if (isPositionEditMode) " [已开启]" else "",
        "视角旋转 (3D游戏)",
        "恢复默认"
    )

    android.app.AlertDialog.Builder(context)
        .setTitle("按键设置（共 $keyCount 个）")
        .setItems(menuItems) { _, which ->
            when (which) {
                0 -> {
                    // 按键映射 (选择按键修改)
                    val gameKeys = PrefsManager.gamepadKeys
                    val labels = gameKeys.mapIndexed { i, k -> "按键 ${i + 1} ($k)" }.toTypedArray()
                    android.app.AlertDialog.Builder(context)
                        .setTitle("选择要修改的按键")
                        .setItems(labels) { _, idx ->
                            val current = gameKeys.getOrElse(idx) { "J" }
                            android.app.AlertDialog.Builder(context)
                                .setTitle("按键 ${idx + 1} 映射")
                                .setSingleChoiceItems(keys, keys.indexOf(current).coerceAtLeast(0)) { d2, w2 ->
                                    PrefsManager.sp.edit().putString("gamepad_key_${idx + 1}", keys[w2]).apply()
                                    d2.dismiss()
                                    onReload()
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                1 -> {
                    // 添加按键
                    val c = PrefsManager.gamepadKeyCount
                    if (c < 18) PrefsManager.sp.edit().putInt("gamepad_key_count", c + 1).apply()
                    onReload()
                }
                2 -> {
                    // 删除按键
                    val c = PrefsManager.gamepadKeyCount
                    if (c > 2) PrefsManager.sp.edit().putInt("gamepad_key_count", c - 1).apply()
                    onReload()
                }
                3 -> {
                    // Start/Select 映射
                    val startKey = PrefsManager.startKey
                    val selectKey = PrefsManager.selectKey
                    android.app.AlertDialog.Builder(context)
                        .setTitle("Start/Select 映射")
                        .setItems(arrayOf("Start 键 (当前: $startKey)", "Select 键 (当前: $selectKey)")) { _, idx ->
                            val current = if (idx == 0) startKey else selectKey
                            android.app.AlertDialog.Builder(context)
                                .setTitle(if (idx == 0) "Start 键映射" else "Select 键映射")
                                .setSingleChoiceItems(keys, keys.indexOf(current).coerceAtLeast(0)) { d2, w2 ->
                                    if (idx == 0) {
                                        PrefsManager.sp.edit().putString("start_key", keys[w2]).apply()
                                    } else {
                                        PrefsManager.sp.edit().putString("select_key", keys[w2]).apply()
                                    }
                                    d2.dismiss()
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                4 -> {
                    // 方向键模式 (DPAD/WASD/摇杆)
                    val modes = arrayOf("摇杆 (joystick)", "十字键 (dpad)", "WASD (wasd)")
                    val values = arrayOf("joystick", "dpad", "wasd")
                    val current = PrefsManager.dpadMode
                    android.app.AlertDialog.Builder(context)
                        .setTitle("方向键模式")
                        .setSingleChoiceItems(modes, values.indexOf(current).coerceAtLeast(0)) { d2, w2 ->
                            PrefsManager.sp.edit().putString("dpad_mode", values[w2]).apply()
                            d2.dismiss()
                            onReload()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                5 -> {
                    // 方向键大小
                    val sizes = arrayOf("小 (80)", "中 (100)", "大 (120)", "超大 (150)")
                    val values = intArrayOf(80, 100, 120, 150)
                    val current = (PrefsManager.dpadScale * 100).toInt()
                    android.app.AlertDialog.Builder(context)
                        .setTitle("方向键大小")
                        .setSingleChoiceItems(sizes, values.indexOf(current).coerceAtLeast(0)) { d2, w2 ->
                            PrefsManager.sp.edit().putInt("dpad_scale", values[w2]).apply()
                            d2.dismiss()
                            onReload()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                6 -> {
                    // 动作按键大小
                    val sizes = arrayOf("小 (80)", "中 (100)", "大 (120)", "超大 (150)")
                    val values = intArrayOf(80, 100, 120, 150)
                    val current = (PrefsManager.gamepadScale * 100).toInt()
                    android.app.AlertDialog.Builder(context)
                        .setTitle("动作按键大小")
                        .setSingleChoiceItems(sizes, values.indexOf(current).coerceAtLeast(0)) { d2, w2 ->
                            PrefsManager.sp.edit().putInt("gamepad_scale", values[w2]).apply()
                            d2.dismiss()
                            onReload()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                7 -> {
                    // 显示/隐藏按键
                    val gameKeys = PrefsManager.gamepadKeys
                    val visible = PrefsManager.gamepadKeyVisible
                    val checked = visible.toBooleanArray()
                    val labels = gameKeys.mapIndexed { i, k -> "按键 ${i + 1} ($k)" }.toTypedArray()
                    android.app.AlertDialog.Builder(context)
                        .setTitle("显示/隐藏按键")
                        .setMultiChoiceItems(labels, checked) { _, which2, isChecked ->
                            checked[which2] = isChecked
                        }
                        .setPositiveButton("确定") { _, _ ->
                            val editor = PrefsManager.sp.edit()
                            checked.forEachIndexed { i, c -> editor.putBoolean("gamepad_key_${i + 1}_visible", c) }
                            editor.apply()
                            onReload()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                8 -> {
                    // 添加/隐藏鼠标按钮
                    onToggleMouseButtons()
                }
                9 -> {
                    // 位置编辑模式 (拖动调整)
                    onTogglePositionEdit()
                }
                10 -> {
                    // 视角旋转 (3D游戏)
                    onToggleCameraRotation()
                }
                11 -> {
                    // 恢复默认
                    PrefsManager.sp.edit()
                        .putInt("gamepad_key_count", 6)
                        .putString("gamepad_key_1", "J")
                        .putString("gamepad_key_2", "K")
                        .putString("gamepad_key_3", "L")
                        .putString("gamepad_key_4", "U")
                        .putString("gamepad_key_5", "I")
                        .putString("gamepad_key_6", "O")
                        .putString("select_key", "TAB")
                        .putString("start_key", "ENTER")
                        .putString("dpad_mode", "joystick")
                        .putInt("dpad_scale", 100)
                        .putInt("gamepad_scale", 100)
                        .putFloat("dpad_pos_x", -1f)
                        .putFloat("dpad_pos_y", -1f)
                        .putFloat("action_pos_x", -1f)
                        .putFloat("action_pos_y", -1f)
                        .putFloat("system_pos_x", -1f)
                        .putFloat("system_pos_y", -1f)
                        .apply()
                    onReload()
                }
            }
        }
        .setNegativeButton("关闭", null)
        .setOnDismissListener { onDismiss() }
        .show()
}

// ============== 内部脚本（SWF + 资源文件嗅探器） ==============

private const val SWF_SNIFFER_SCRIPT = """
(function(){
  var found = {};
  var resources = {};
  // 资源文件扩展名（Flash 游戏常见外部资源）
  var resExts = /\.(jpe?g|png|gif|bmp|svg|mp3|wav|ogg|flv|mp4|f4v|xml|json|txt|csv|css|js|php|asp)([?#]|$)/i;

  function addSwf(url, title){
    if(!url) return;
    try { url = new URL(url, location.href).href; } catch(e) { return; }
    if(!/\.swf([?#]|$)/i.test(url) && !/application\/x-shockwave-flash/i.test(url)){
      if(!/^data:application\/x-shockwave-flash/i.test(url)) return;
    }
    if(found[url]) return;
    var t = title || '';
    if(!t){
      try { t = decodeURIComponent(url.split('/').pop().split('?')[0].replace(/\.swf$/i,'')); } catch(e){}
    }
    found[url] = {url:url, title:t, size:'', type:'swf'};
  }

  function addResource(url){
    if(!url) return;
    try { url = new URL(url, location.href).href; } catch(e) { return; }
    // 跳过 data:、blob:、javascript: 等非 HTTP 资源
    if(!/^https?:/i.test(url)) return;
    // 跳过已收集的 SWF
    if(/\.swf([?#]|$)/i.test(url)) return;
    // 只收集已知资源扩展名
    if(!resExts.test(url)) return;
    if(resources[url]) return;
    // 提取子目录路径（相对于 origin 的目录结构）
    var subDir = '';
    try {
      var p = new URL(url).pathname;
      var dir = p.substring(0, p.lastIndexOf('/') + 1);
      // 去掉开头的 /
      if(dir.startsWith('/')) dir = dir.substring(1);
      subDir = dir;
    } catch(e) {}
    var name = url.split('/').pop().split('?')[0] || 'resource';
    resources[url] = {url:url, title:name, size:'', type:'resource', subDir:subDir};
  }

  function scanDOM(){
    // SWF 扫描
    var objs = document.querySelectorAll('object[data], embed[src]');
    objs.forEach(function(el){
      var u = el.getAttribute('data') || el.getAttribute('src') || '';
      var t = el.getAttribute('title') || el.getAttribute('name') || '';
      if(u) addSwf(u, t);
    });
    var params = document.querySelectorAll('param[name="movie"], param[name="src"]');
    params.forEach(function(p){
      var v = p.getAttribute('value') || '';
      if(v) addSwf(v, '');
    });
    var iframes = document.querySelectorAll('iframe[src]');
    iframes.forEach(function(f){
      var s = f.getAttribute('src') || '';
      if(/\.swf/i.test(s)) addSwf(s, '');
    });
    var all = document.querySelectorAll('[data*=".swf"], [src*=".swf"], [href*=".swf"]');
    all.forEach(function(el){
      ['data','src','href'].forEach(function(attr){
        var v = el.getAttribute(attr);
        if(v && /\.swf/i.test(v)) addSwf(v, el.getAttribute('title') || '');
      });
    });

    // 资源文件扫描
    var imgs = document.querySelectorAll('img[src]');
    imgs.forEach(function(el){
      var s = el.getAttribute('src') || '';
      if(s) addResource(s);
    });
    var sources = document.querySelectorAll('source[src], video[src], audio[src]');
    sources.forEach(function(el){
      var s = el.getAttribute('src') || '';
      if(s) addResource(s);
    });
    var links = document.querySelectorAll('link[href]');
    links.forEach(function(el){
      var s = el.getAttribute('href') || '';
      if(s && resExts.test(s)) addResource(s);
    });
  }

  function scanPerformance(){
    try {
      var entries = performance.getEntriesByType('resource');
      entries.forEach(function(e){
        if(/\.swf([?#]|$)/i.test(e.name)) {
          addSwf(e.name, '');
        } else if(resExts.test(e.name)) {
          addResource(e.name);
        }
      });
    } catch(e) {}
  }

  function scanScripts(){
    var scripts = document.querySelectorAll('script:not([src])');
    var swfRe = /(?:https?:)?[^\s'"<>]+\.swf[^\s'"<>]*/gi;
    var resRe = /(?:https?:)?[^\s'"<>]+\.(?:jpe?g|png|gif|bmp|svg|mp3|wav|ogg|flv|mp4|f4v|xml|json|txt|csv|php)[^\s'"<>]*/gi;
    scripts.forEach(function(s){
      var text = s.textContent || '';
      var m;
      while((m = swfRe.exec(text)) !== null){
        addSwf(m[0], '');
      }
      while((m = resRe.exec(text)) !== null){
        addResource(m[0]);
      }
    });
    var extScripts = document.querySelectorAll('script[src]');
    extScripts.forEach(function(s){
      var src = s.getAttribute('src') || '';
      if(/\.swf/i.test(src)) addSwf(src, '');
      else if(resExts.test(src)) addResource(src);
    });
  }

  if(!window.__swfSniffHooked){
    window.__swfSniffHooked = true;
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url){
      if(url && /\.swf([?#]|$)/i.test(url)) addSwf(url, '');
      else if(url && resExts.test(url)) addResource(url);
      return origOpen.apply(this, arguments);
    };
    var origFetch = window.fetch;
    if(origFetch){
      window.fetch = function(input){
        var u = typeof input === 'string' ? input : (input && input.url ? input.url : '');
        if(u && /\.swf([?#]|$)/i.test(u)) addSwf(u, '');
        else if(u && resExts.test(u)) addResource(u);
        return origFetch.apply(this, arguments);
      };
    }
  }
  scanDOM();
  scanPerformance();
  scanScripts();
  if(window.MutationObserver){
    var mo = new MutationObserver(function(muts){
      muts.forEach(function(m){
        m.addedNodes.forEach(function(n){
          if(n.nodeType === 1){
            var u = n.getAttribute && (n.getAttribute('data') || n.getAttribute('src') || n.getAttribute('href') || '');
            if(u && /\.swf/i.test(u)) addSwf(u, n.getAttribute('title') || '');
            else if(u && resExts.test(u)) addResource(u);
            if(n.querySelectorAll){
              var inner = n.querySelectorAll('[data*=".swf"], [src*=".swf"], [href*=".swf"], param[name="movie"], img[src], source[src], link[href]');
              inner.forEach(function(el){
                var v = el.getAttribute('data') || el.getAttribute('src') || el.getAttribute('href') || el.getAttribute('value') || '';
                if(v && /\.swf/i.test(v)) addSwf(v, '');
                else if(v && resExts.test(v)) addResource(v);
              });
            }
          }
        });
      });
    });
    mo.observe(document.documentElement || document.body || document, {childList:true, subtree:true});
    setTimeout(function(){ mo.disconnect(); }, 5000);
  }
  setTimeout(function(){
    scanDOM();
    scanPerformance();
    scanScripts();
    var arr = [];
    for(var u in found) arr.push(found[u]);
    for(var u in resources) arr.push(resources[u]);
    if(window.Android && window.Android.onSwfFound){
      window.Android.onSwfFound(JSON.stringify(arr));
    }
  }, 1500);
  setTimeout(function(){
    var arr = [];
    for(var u in found) arr.push(found[u]);
    for(var u in resources) arr.push(resources[u]);
    if(arr.length > 0 && window.Android && window.Android.onSwfFound){
      window.Android.onSwfFound(JSON.stringify(arr));
    }
  }, 500);
})();
"""

private const val MOUSE_CURSOR_SCRIPT = """
(function(){
  if (window.__mouseEnabled) return; window.__mouseEnabled = true;
  var cursor = document.createElement('div');
  cursor.id = '__mouseCursor';
  cursor.style.cssText = 'position:fixed;width:20px;height:20px;pointer-events:none;z-index:999999;left:0;top:0;transform:translate(-4px,-4px);';
  cursor.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M5.5,3.5L18,12L11.5,12.5L15,19L12.5,20L9,13.5L5.5,17L5.5,3.5Z" fill="white" stroke="black" stroke-width="1.5"/></svg>';
  document.body.appendChild(cursor);
  var lastX = 0, lastY = 0;
  document.addEventListener('touchstart', function(e){
    var t = e.touches[0];
    lastX = t.clientX; lastY = t.clientY;
    cursor.style.left = lastX + 'px';
    cursor.style.top = lastY + 'px';
  }, {passive: true});
  document.addEventListener('touchmove', function(e){
    var t = e.touches[0];
    lastX = t.clientX; lastY = t.clientY;
    cursor.style.left = lastX + 'px';
    cursor.style.top = lastY + 'px';
    var el = document.elementFromPoint(lastX, lastY);
    if (el) {
      var evt = new MouseEvent('mousemove', {bubbles:true, clientX:lastX, clientY:lastY});
      el.dispatchEvent(evt);
    }
  }, {passive: true});
})();
"""
