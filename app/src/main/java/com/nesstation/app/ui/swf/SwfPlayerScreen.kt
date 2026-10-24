package com.nesstation.app.ui.swf

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// 注:不同 Compose 版本下 Float.toDp() 可能在不同包。这里用 density.density 手动换算 px → dp,
// 避免 unresolved reference。
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.window.Dialog
import com.nesstation.app.flash.data.PrefsManager
import com.nesstation.app.flash.input.ActionButtonView
import com.nesstation.app.flash.input.DPadView
import com.nesstation.app.flash.input.KeyMapper
import com.nesstation.app.flash.input.MouseControlView
import com.nesstation.app.flash.download.SwfDownloadItem
import com.nesstation.app.flash.download.SwfDownloadManager
import com.nesstation.app.flash.download.DownloadStatus
import com.nesstation.app.flash.webview.GameWebChromeClient
import com.nesstation.app.flash.webview.GameWebView
import com.nesstation.app.flash.webview.GameWebViewClient
import com.nesstation.app.flash.webview.NavHelper
import com.nesstation.app.flash.webview.WebAppInterface
import com.nesstation.app.flash.widget.FloatingMenuView

/**
 * SWF 播放器 Compose 入口。
 * 1:1 移植自 3.3-fix2 GameActivity（仅 SWF 模式）。所有 webview/input/widget 都用
 * com.nesstation.app.flash 包下的 3.3 类。
 *
 * @param swfPath  本地 SWF 文件 URI（content:// / file://）
 * @param onExit   退出回调
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SwfPlayerScreen(
    swfPath: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    remember { PrefsManager.init(context) }
    val activity = context as? Activity

    var gamepadVisible by remember { mutableStateOf(PrefsManager.isGamepadEnabled) }
    var isMouseEnabled by remember { mutableStateOf(PrefsManager.isMouseEnabled) }
    var isFullscreen by remember { mutableStateOf(false) }
    var isLandscape by remember { mutableStateOf(true) }
    val localSwfUri = remember { mutableStateOf(swfPath) }
    val showFlashDialog = remember { mutableStateOf(false) }
    val showKeyDialog = remember { mutableStateOf(false) }

    // WebView 自定义全屏视图（Ruffle 等元素 requestFullscreen 触发）：
    // 真实把 view 加到 Activity DecorView 上,避免 WebView 内部状态不一致导致卡死。
    val customViewRef = remember { mutableStateOf<View?>(null) }
    val customViewCallbackRef = remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val showZoomDialog = remember { mutableStateOf(false) }
    val showUaDialog = remember { mutableStateOf(false) }
    val showAspectRatioDialog = remember { mutableStateOf(false) }

    // 下载进度 state:监听 SwfDownloadManager 实时刷新
    val downloadProgress = remember { mutableStateOf<List<SwfDownloadItem>>(emptyList()) }
    val isDownloadingActive = remember { mutableStateOf(false) }
    val swfExtractDialog = remember { mutableStateOf<String?>(null) }

    // ScreenPositionEditor(自由布局编辑器):一个原生 View,挂在 FrameLayoutSwfContainer 里
    // 4 角 + body 5 个 hot zone,自己处理 onDraw + onTouchEvent。
    // 不用 Compose overlay 因为 Compose pointerInput 在 AndroidView 之上拿不到触摸。
    val screenPositionEditorRef = remember { mutableStateOf<ScreenPositionEditor?>(null) }
    // 镜像到顶级可变状态,让顶级 enter/exit 函数也能读到(Composable 内的 remember 在 lambda 外访问不到)
    LaunchedEffect(screenPositionEditorRef.value) { _screenPositionEditor.value = screenPositionEditorRef.value }
    // 本地 SWF 打开时先弹出引擎选择（仅弹一次）
    val enginePickerShown = remember { mutableStateOf(false) }
    // 位置编辑模式（拖动调整方向键/动作键/鼠标按钮位置）
    var isPositionEditMode by remember { mutableStateOf(false) }

    val webViewRef = remember { mutableStateOf<GameWebView?>(null) }
    LaunchedEffect(webViewRef.value) { _webView.value = webViewRef.value }
    val dpadRef = remember { mutableStateOf<DPadView?>(null) }
    val actionRef = remember { mutableStateOf<ActionButtonView?>(null) }
    val mouseRef = remember { mutableStateOf<MouseControlView?>(null) }
    val floatingMenuRef = remember { mutableStateOf<FloatingMenuView?>(null) }
    val webAppInterfaceRef = remember { mutableStateOf<WebAppInterface?>(null) }
    val mainBoxRef = remember { mutableStateOf<ViewGroup?>(null) }
    LaunchedEffect(mainBoxRef.value) { _mainContainer.value = mainBoxRef.value as? android.widget.FrameLayout }

    // 虚拟手柄重建触发器：添加/删除/修改按键后递增，触发 LaunchedEffect 重建手柄
    val gamepadRebuildTrigger = remember { mutableStateOf(0) }

    fun rebuildGamepad() {
        gamepadRebuildTrigger.value = gamepadRebuildTrigger.value + 1
    }

    BackHandler {
        if (webViewRef.value?.canGoBack() == true) webViewRef.value?.goBack() else onExit()
    }

    fun applyOrientation(landscape: Boolean) {
        activity?.requestedOrientation = if (landscape)
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        activity?.window?.let { w ->
            if (isFullscreen) {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).hide(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                )
            } else {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, true)
                androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).show(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                )
            }
        }
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

    fun toggleMouse() {
        isMouseEnabled = !isMouseEnabled
        PrefsManager.sp.edit()
            .putBoolean("mouse_enabled", isMouseEnabled)
            .putBoolean("mouse_buttons_visible", isMouseEnabled)
            .apply()
        val wv = webViewRef.value
        if (wv != null) {
            if (isMouseEnabled) {
                wv.evaluateJavascript(GameWebViewClient.MOUSE_CURSOR_SCRIPT, null)
                Toast.makeText(context, "鼠标光标已开启", Toast.LENGTH_SHORT).show()
            } else {
                wv.evaluateJavascript(
                    "(function(){var c=document.getElementById('__mouseCursor');if(c)c.remove();window.__mouseEnabled=false;})();", null
                )
                Toast.makeText(context, "鼠标光标已关闭", Toast.LENGTH_SHORT).show()
            }
        }
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

    /**
     * 应用自定义四角布局:把 WebView 父容器按 left/top/right/bottom 归一化值定位。
     * 0/0/1/1 = 全屏(默认值),用户拖动可缩小到任意矩形。
     */
    fun applyCustomLayout(enabled: Boolean, left: Float, top: Float, right: Float, bottom: Float) {
        PrefsManager.setCustomLayout(enabled, left, top, right, bottom)
        val container = mainBoxRef.value as? android.widget.FrameLayout ?: return
        val wv = webViewRef.value ?: return
        val parentW = container.width
        val parentH = container.height
        if (parentW <= 0 || parentH <= 0) return
        val l = (left.coerceIn(0f, 1f) * parentW).toInt()
        val t = (top.coerceIn(0f, 1f) * parentH).toInt()
        val r = (right.coerceIn(0f, 1f) * parentW).toInt()
        val b = (bottom.coerceIn(0f, 1f) * parentH).toInt()
        val lp = android.widget.FrameLayout.LayoutParams(
            (r - l).coerceAtLeast(1),
            (b - t).coerceAtLeast(1)
        )
        lp.leftMargin = l
        lp.topMargin = t
        wv.layoutParams = lp
        wv.requestLayout()
    }

    fun reload() {
        val wv = webViewRef.value ?: return
        // ★★ v24: 保留当前播放页的 swf/base 参数重建 URL（与 WebGameScreen
        //   applyAspectRatio 的重载同款）—— 旧版固定 base=null，改比例/引擎
        //   触发 reload 后多文件 SWF 的 base 丢失，只能靠资源拦截兑底，
        //   失败即黑屏；现在在播放页上原样带回。
        val curUrl = wv.url ?: ""
        var swfParam: String? = null
        var baseUrl: String? = null
        if (curUrl.startsWith("https://flash.local/")) {
            try {
                val parsed = android.net.Uri.parse(curUrl)
                swfParam = parsed.getQueryParameter("swf")
                baseUrl = parsed.getQueryParameter("base")
            } catch (_: Throwable) {}
        }
        val swfProxy = swfParam ?: "https://flash.local/local.swf?t=${System.currentTimeMillis()}"
        val playerUrl = NavHelper.playerUrl(swfProxy, base = baseUrl, title = null)
        wv.releaseAllKeys()
        wv.loadUrl(playerUrl)
    }

    fun applyAspectRatio(ratio: String) {
        // ★★ 修复「看图 SWF 比例定死」：Ruffle 的 scale/letterbox/forceScale 是
        //   【加载期】配置（player.html 读 URL 参数注入 window.RufflePlayer.config），
        //   运行中只改外框 CSS 无法改变引擎内部缩放 —— 自管 Stage.scaleMode 的
        //   SWF（看图/视觉小说类）对外框变化完全无反应，黑边纹丝不动。
        //   现在与 WebGameScreen.applyAspectRatio 同步写入引擎缩放偏好，并
        //   【自动重载播放页】让新参数立即生效（进度会重置，属预期代价）：
        //     · auto   = showAll + letterbox on（保持原比例不变形）
        //     · stretch = exactFit + letterbox off（无黑边拉伸铺满全屏）
        //     · 4:3 等 = exactFit + letterbox off（拉伸填满指定比例外框；
        //       ★ v24 修正：旧注释写 on —— Ruffle letterbox 优先于 scale，
        //       框内又按原生比例加黑边 = 双重黑边，实际写的是 off 才对，
        //       NavHelper 侧消费链同步已修）
        val spEdit = PrefsManager.sp.edit().putString("game_aspect_ratio", ratio)
        when (ratio) {
            "auto" -> spEdit.putString("flash_scale", "showAll").putString("flash_letterbox", "on")
            "stretch" -> spEdit.putString("flash_scale", "exactFit").putString("flash_letterbox", "off")
            else -> spEdit.putString("flash_scale", "exactFit").putString("flash_letterbox", "off")
        }
        spEdit.apply()

        val wv = webViewRef.value
        if (wv != null) {
            // 按当前引擎选择正确的 letterbox 脚本。
            // - Ruffle: 用 buildRuffleAspectRatioScript（让 ruffle-player 内部 letterbox 生效，
            //           只控制 #stage 外框大小+居中,不破坏引擎内部 stage 尺寸）。
            // - WAFlash: 用 buildAspectRatioScript（WAFlash 引擎自身不做 letterbox,必须外层 CSS 强制）。
            val script = if (PrefsManager.flashEngine == "waflash") {
                GameWebViewClient.buildAspectRatioScript(ratio)
            } else {
                GameWebViewClient.buildRuffleAspectRatioScript(ratio)
            }
            wv.evaluateJavascript(script, null)
            // ★ 自动重载：scale 级变更必须重载播放页才完全生效（config 是
            //   页面加载时注入的）。正在播放 flash.local 播放页时立即重载 ——
            //   用户改比例立刻看到效果，不再是"老样子"。
            val curUrl = wv.url ?: ""
            if (curUrl.startsWith("https://flash.local/")) {
                wv.postDelayed({
                    try { reload() } catch (_: Throwable) {}
                }, 150)
            }
        }
        val modeText = when (ratio) {
            "auto" -> "全屏自适应（保持原比例）"
            "stretch" -> "全屏拉伸（无黑边铺满，已重载生效）"
            else -> "按 $ratio 拉伸显示（已重载生效）"
        }
        Toast.makeText(context, "画面比例：$modeText", Toast.LENGTH_SHORT).show()
    }

    fun applyZoom(mode: String, manual: Int) {
        PrefsManager.sp.edit()
            .putString("page_zoom_mode", mode)
            .putInt("page_zoom_manual", manual)
            .apply()
        val wv = webViewRef.value
        if (wv != null) {
            // 直接调用 GameWebView.applyPageZoom:用 WebSettings.textZoom + setInitialScale
            // (与 viewport meta 无关,对已加载页面也生效,reload 后应用)。
            wv.applyPageZoom(if (mode == "manual") manual else 100)
            wv.reload()
        }
    }

    fun applyUa(mode: String) {
        PrefsManager.sp.edit().putString("ua_mode", mode).apply()
        val wv = webViewRef.value
        if (wv != null) {
            if (mode == "desktop") {
                wv.useDesktopMode(true)
            } else {
                wv.useUaMode(mode)
            }
        }
        webViewRef.value?.reload()
    }

    fun applyEngineAndReload(engine: String) {
        PrefsManager.sp.edit().putString("flash_engine", engine).putBoolean("flash_enabled", true).apply()
        reload()
    }

    fun extractSwfFromPage() {
        Toast.makeText(context, "正在扫描页面中的 SWF...", Toast.LENGTH_SHORT).show()
        val iface = webAppInterfaceRef.value
        if (iface != null) {
            iface.swfExtractCallback = { json ->
                if (json.isNullOrEmpty()) {
                    Toast.makeText(context, "未发现 SWF", Toast.LENGTH_SHORT).show()
                } else {
                    // 弹出 SwfExtractDialog(支持多选+下载+全选,同 WebGameScreen)
                    swfExtractDialog.value = json
                }
            }
        }
        webViewRef.value?.evaluateJavascript(SWF_SNIFFER_SCRIPT, null)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // FrameLayout 作为游戏画面容器,ScreenPositionEditor 覆盖在最上层(GONE)
                // 整个 View tree 用标准 FrameLayout,不依赖 onInterceptTouchEvent
                val container = android.widget.FrameLayout(ctx)
                mainBoxRef.value = container
                // 创建并添加 ScreenPositionEditor 覆盖层(初始 GONE)
                val editor = ScreenPositionEditor(ctx).apply {
                    visibility = View.GONE
                    // 监听器:实时改 WebView layoutParams
                    listener = object : ScreenPositionEditor.Listener {
                        override fun onRectChanged(x1: Float, y1: Float, x2: Float, y2: Float, confirm: Boolean) {
                            val wv = webViewRef.value ?: return
                            val pw = container.width
                            val ph = container.height
                            if (pw <= 0 || ph <= 0) return
                            // 实时改 WebView 在 container 里的位置和大小
                            val lp = FrameLayout.LayoutParams(
                                ((x2 - x1) * pw).toInt().coerceAtLeast(1),
                                ((y2 - y1) * ph).toInt().coerceAtLeast(1)
                            )
                            lp.leftMargin = (x1 * pw).toInt()
                            lp.topMargin = (y1 * ph).toInt()
                            wv.layoutParams = lp
                            wv.requestLayout()
                            if (confirm) {
                                // 触摸结束:持久化到 PrefsManager
                                PrefsManager.setCustomLayout(true, x1, y1, x2, y2)
                            }
                        }
                    }
                }
                container.addView(editor, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                screenPositionEditorRef.value = editor
                // 读 PrefsManager 中的已有自定义布局,恢复
                if (PrefsManager.isCustomLayoutEnabled) {
                    editor.setRect(
                        PrefsManager.customLayoutLeft,
                        PrefsManager.customLayoutTop,
                        PrefsManager.customLayoutRight,
                        PrefsManager.customLayoutBottom
                    )
                    // 立即应用 WebView layoutParams
                    container.post {
                        applyCustomLayoutToWebView()
                    }
                }
                container
            }
        )

        // ========== 下载进度 Overlay(顶部) ==========
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
                val currentPercent = items.filter { it.status == DownloadStatus.DOWNLOADING }
                    .maxByOrNull { it.progress }?.progress ?: 0
                Column {
                    Text(
                        "下载进度: $completed/$total" + if (currentPercent > 0) " (当前: $currentPercent%)" else "",
                        color = Color.White, fontSize = 12.sp
                    )
                    LinearProgressIndicator(
                        progress = { if (total > 0) completed.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = Color(0xFFFFC107)
                    )
                    items.filter { it.status != DownloadStatus.COMPLETED }.forEach { item ->
                        Text(
                            "${item.title}: ${when (item.status) {
                                DownloadStatus.DOWNLOADING -> "下载中 ${item.progress}%"
                                DownloadStatus.FAILED -> "失败(重试${item.retryCount})"
                                DownloadStatus.PENDING -> "等待中"
                                DownloadStatus.CANCELLED -> "已取消"
                                else -> ""
                            }}",
                            color = Color.White, fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // ========== SWF 提取对话框(多选+下载) ==========
        swfExtractDialog.value?.let { json ->
            SwfExtractDialog(
                json = json,
                onDismiss = { swfExtractDialog.value = null },
                onPlay = { /* SwfPlayerScreen 已在播放,不用 onPlay */ swfExtractDialog.value = null },
                onDownload = { items ->
                    if (!isDownloadingActive.value) {
                        val manager = SwfDownloadManager(context)
                        manager.setProgressListener { list -> downloadProgress.value = list }
                        manager.setCompleteListener { list ->
                            isDownloadingActive.value = false
                            val success = list.count { it.status == DownloadStatus.COMPLETED }
                            val failed = list.count { it.status == DownloadStatus.FAILED }
                            android.widget.Toast.makeText(
                                context, "下载完成: $success 个成功" +
                                        if (failed > 0) ", $failed 个失败" else "",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        isDownloadingActive.value = true
                        val pageUrl = webViewRef.value?.url ?: ""
                        val swfItems = items.filter { it.type == "swf" }
                        val resItems = items.filter { it.type == "resource" }
                        val gameFolder = if (swfItems.isNotEmpty()) {
                            swfItems.first().title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                .take(50).ifBlank { "GameBox" }
                        } else if (resItems.isNotEmpty()) {
                            "resources"
                        } else {
                            ""
                        }
                        val swfUrls = swfItems.map { it.url to it.title }
                        val resUrls = resItems.map { it.url to it.subDir }
                        manager.startDownload(swfUrls, pageUrl, gameFolder, resUrls)
                        // 关键:dismiss dialog,用户回到游戏画面看到顶部 progress overlay
                        swfExtractDialog.value = null
                    } else {
                        android.widget.Toast.makeText(context, "正在下载中，请等待",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    LaunchedEffect(mainBoxRef.value) {
        val container = mainBoxRef.value ?: return@LaunchedEffect
        if (webViewRef.value == null) {
            val wv = GameWebView(container.context)
            // WebView 创建时应用用户保存的缩放(避免首次加载显示 100%,reload 后才生效的延迟)
            if (PrefsManager.pageZoomMode == "manual") {
                wv.applyPageZoom(PrefsManager.pageZoomManual)
            }
            val webAppInterface = WebAppInterface(container.context)
            webAppInterfaceRef.value = webAppInterface

            webAppInterface.openSwfCallback = { swfUrl, _ ->
                val wv2 = webViewRef.value
                if (wv2 != null) {
                    val playerUrl = NavHelper.playerUrl(swfUrl, base = wv2.url, title = null)
                    wv2.loadUrl(playerUrl)
                }
            }
            webAppInterface.fullscreenCallback = { toggleFullscreen() }

            val chromeCallback = object : GameWebChromeClient.Callback {
                override fun onProgress(progress: Int) {}
                override fun onTitle(title: String?) {}
                override fun onConsole(level: String, msg: String, sourceId: String?, line: Int) {}

                // 实现 WebView 自定义全屏协议（按 Android 标准）：
                // 把 WebView 内部生成的全屏 view 加到 Activity 的 DecorView 上,
                // 实现 WebView 自定义全屏协议(Android 标准):
                // 把 WebView 内部生成的全屏 view 加到 DecorView 上层,让 WebView 内部
                // 状态正常推进,避免 Ruffle 等元素 requestFullscreen 触发 onShowCustomView 后
                // WebView 内部状态不一致而卡死。
                //
                // 关键:不要在这里 hide/show Android systemBars(状态栏/虚拟按键)。
                // 那些是 Activity 级,跟 WebView 全屏是独立的两套——
                //   - WebView 全屏:view 加到 DecorView,占满 viewport
                //   - 手机全屏(浮动菜单的全屏按钮):用 WindowInsetsControllerCompat
                // 之前实现错把 WebView 全屏跟手机全屏耦合,导致:
                //   1. WebView 全屏时 systemBars 被 hide,虚拟按键被覆盖
                //   2. 退出 WebView 全屏时调 show(systemBars),等于退出手机全屏
                override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
                    customViewRef.value = view
                    customViewCallbackRef.value = callback
                    // 把 customView 加到 mainBoxRef 覆盖在 WebView 上层。
                    // 之前加到 DecorView 会盖住 floatingMenu + 虚拟按键,
                    // 加到 mainBoxRef 只覆盖 WebView 区域,虚拟按键和悬浮按钮仍可见可按。
                    val parent = mainBoxRef.value as? ViewGroup
                    if (parent != null) {
                        parent.addView(view, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        ))
                        // 关键:customView MATCH_PARENT 会盖在 dpad/menu/mouse 上面。
                        // 强制把它们 bringToFront 拉到最上层,确保全屏期间仍可操作。
                        listOfNotNull(
                            dpadRef.value, actionRef.value, mouseRef.value, floatingMenuRef.value
                        ).forEach { v -> v.bringToFront() }
                    }
                    isFullscreen = true
                    // 关键:不调 floatingMenuRef.value?.isFullscreen = true。
                    // 之前的设计是"全屏时隐藏 triggerBtn",但用户的实际期望是"全屏时悬浮按钮仍可用"
                    // (按 triggerBtn 弹出菜单,菜单项里可以退出全屏)。
                    // FloatingMenuView 的 triggerBtn 默认在 mainBoxRef 顶部右侧,
                    // customView MATCH_PARENT 会盖住它,但 bringToFront 后 triggerBtn 仍可见可点。
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
                    isFullscreen = false
                    // 同样不调 isFullscreen = false,保持菜单一致状态
                }

                override fun onFileChooser(callback: ValueCallback<Array<android.net.Uri>>, accept: String?): Boolean { return true }
            }

            val viewClientCallback = object : GameWebViewClient.Callback {
                override fun onPageStarted(url: String?) { webViewRef.value?.releaseAllKeys() }
                override fun onPageFinished(url: String?) {}
                override fun onProgress(progress: Int) {}
                override fun onError(url: String?, errorCode: Int, description: String?) {}
                override fun onSwfIntercepted(swfUrl: String, pageUrl: String) {
                    val wv2 = webViewRef.value
                    if (wv2 != null) {
                        val playerUrl = NavHelper.playerUrl(swfUrl, base = pageUrl, title = null)
                        wv2.loadUrl(playerUrl)
                    }
                }
                override fun shouldInjectRuffle(url: String?): Boolean = false
                override fun getCachedSwfPath(): String? = null
                override fun getLocalSwfUri(): String? = localSwfUri.value
                override fun getLocalSwfDir(): String? {
                    val uri = localSwfUri.value
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
                        android.util.Log.w("SwfPlayerScreen", "获取SWF目录失败: ${e.message}")
                        null
                    }
                }
            }

            wv.apply {
                addJavascriptInterface(webAppInterface, "Android")
                webChromeClient = object : GameWebChromeClient(chromeCallback) {}
                webViewClient = GameWebViewClient(viewClientCallback)
                useDesktopMode(true)
                cameraRotationEnabled = PrefsManager.isCameraRotationEnabled
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.action == KeyEvent.ACTION_UP) onExit()
                        true
                    } else if (keyCode in GameWebView.GAME_KEYS) {
                        dispatchKeyEvent(event)
                        true
                    } else false
                }
            }
            wv.injectDocumentStartScripts()

            container.addView(wv, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            webViewRef.value = wv
            // 关键:WebView addView 之后会盖在 ScreenPositionEditor 上面,
            // 必须把 editor 重新 bringToFront 才能让 4 角手柄接收触摸
            val editor = screenPositionEditorRef.value
            if (editor != null) {
                editor.bringToFront()
                // 如果 editor 当前是 VISIBLE(GONE 时也 bringToFront 无害),恢复 rect
                if (editor.visibility == View.VISIBLE && PrefsManager.isCustomLayoutEnabled) {
                    editor.setRect(
                        PrefsManager.customLayoutLeft,
                        PrefsManager.customLayoutTop,
                        PrefsManager.customLayoutRight,
                        PrefsManager.customLayoutBottom
                    )
                }
            }
            // 如果有保存的自定义布局,立即应用到 WebView(避免闪烁成全屏再缩)
            if (PrefsManager.isCustomLayoutEnabled) {
                container.post {
                    applyCustomLayoutToWebView()
                }
            }
        }
    }

    LaunchedEffect(mainBoxRef.value, gamepadVisible, isMouseEnabled, gamepadRebuildTrigger.value) {
        val container = mainBoxRef.value ?: return@LaunchedEffect
        val density = container.resources.displayMetrics.density

        listOfNotNull(
            dpadRef.value, actionRef.value, mouseRef.value, floatingMenuRef.value
        ).forEach { container.removeView(it) }

        val dpad = DPadView(container.context).apply {
            targetWebView = webViewRef.value
            overlayAlpha = PrefsManager.gamepadAlpha
        }
        dpadRef.value = dpad
        val dpadSize = (140 * density).toInt()
        val dpadLp = android.widget.FrameLayout.LayoutParams(dpadSize, dpadSize).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            bottomMargin = (24 * density).toInt()
            marginStart = (16 * density).toInt()
        }
        if (gamepadVisible) container.addView(dpad, dpadLp)

        val action = ActionButtonView(container.context).apply {
            targetWebView = webViewRef.value
            overlayAlpha = PrefsManager.gamepadAlpha
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

        val mouse = MouseControlView(container.context).apply {
            targetWebView = webViewRef.value
            overlayAlpha = PrefsManager.gamepadAlpha
        }
        mouseRef.value = mouse
        val mouseSize = (220 * density).toInt()
        val mouseLp = android.widget.FrameLayout.LayoutParams(mouseSize, (80 * density).toInt()).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = (200 * density).toInt()
        }
        if (isMouseEnabled) container.addView(mouse, mouseLp)

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
                if (webViewRef.value?.canGoBack() == true) webViewRef.value?.goBack() else onExit()
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

    LaunchedEffect(gamepadVisible) {
        if (!gamepadVisible) webViewRef.value?.releaseAllKeys()
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        }
    }

    // ========== 本地 SWF 引擎选择弹窗（仅弹一次） ==========
    LaunchedEffect(Unit) {
        if (!enginePickerShown.value) {
            enginePickerShown.value = true
            android.app.AlertDialog.Builder(context)
                .setTitle("选择 Flash 引擎")
                .setMessage("本地 SWF 文件需要选择播放引擎")
                .setPositiveButton("Ruffle (推荐)") { dlg, _ ->
                    applyEngineAndReload("ruffle")
                    dlg.dismiss()
                }
                .setNegativeButton("WAFlash") { dlg, _ ->
                    applyEngineAndReload("waflash")
                    dlg.dismiss()
                }
                .setOnCancelListener { onExit() }
                .show()
        }
    }

    // ========== Flash 引擎切换弹窗 ==========
    if (showFlashDialog.value) {
        // ★ Flash 引擎 + 悬浮菜单排列（用户反馈「菜单不能一直横着放」）：
        //   引擎选择保持原单选行为；新增菜单排列选择（跟随屏幕/竖排/横排），
        //   写入 floating_menu_layout 偏好，FloatingMenuView.showMenu 读取。
        val engines = arrayOf("Ruffle", "WAFlash", "关闭 Flash", "悬浮菜单排列…")
        val values = arrayOf("ruffle", "waflash", "off", "__menu_layout__")
        val current = if (PrefsManager.isFlashEnabled) PrefsManager.flashEngine else "off"
        val checked = values.indexOf(current).coerceAtLeast(0)
        android.app.AlertDialog.Builder(context)
            .setTitle("Flash 引擎")
            .setSingleChoiceItems(engines, checked) { dlg, which ->
                if (values[which] == "__menu_layout__") {
                    // 悬浮菜单排列子选择（不关闭引擎对话框选择态）
                    val layouts = arrayOf("跟随屏幕 (默认)", "竖排 (横屏看图推荐)", "横排 (2×2 并排)")
                    val layoutValues = arrayOf("auto", "portrait", "landscape")
                    android.app.AlertDialog.Builder(context)
                        .setTitle("悬浮菜单排列")
                        .setSingleChoiceItems(layouts,
                            layoutValues.indexOf(PrefsManager.floatingMenuLayout).coerceAtLeast(0)
                        ) { dlg2, w2 ->
                            PrefsManager.sp.edit().putString("floating_menu_layout", layoutValues[w2]).apply()
                            Toast.makeText(context,
                                "菜单排列：${layouts[w2]}（下次打开菜单生效）",
                                Toast.LENGTH_SHORT).show()
                            dlg2.dismiss()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else if (values[which] == "off") {
                    PrefsManager.sp.edit().putBoolean("flash_enabled", false).apply()
                } else {
                    applyEngineAndReload(values[which])
                }
                dlg.dismiss()
                showFlashDialog.value = false
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { showFlashDialog.value = false }
            .show()
    }

    // ========== 按键映射弹窗（增强版） ==========
    if (showKeyDialog.value) {
        val allKeys = arrayOf(
            "J", "K", "L", "U", "I", "O",
            "A", "B", "C", "D", "E", "F", "G", "H", "M", "N",
            "P", "Q", "R", "S", "T", "V", "W", "X", "Y", "Z",
            "SPACE", "ENTER", "TAB", "ESC",
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"
        )
        val items = arrayOf(
            "按键映射 (选择按键修改)",
            "添加按键",
            "删除按键",
            "Start/Select 映射",
            "方向键模式 (DPAD/WASD/摇杆)",
            "方向键大小",
            "动作按键大小",
            "显示/隐藏按键",
            "添加/隐藏鼠标按钮",
            "位置编辑模式 (拖动调整)",
            "视角旋转 (3D游戏)",
            "恢复默认"
        )
        android.app.AlertDialog.Builder(context)
            .setTitle("按键映射 (共 ${PrefsManager.gamepadKeyCount} 个)")
            .setItems(items) { _, which ->
                when (which) {
                    // 0: 按键映射 - 选择某个按键修改其映射
                    0 -> {
                        val keys = PrefsManager.gamepadKeys
                        val labels = keys.mapIndexed { i, k -> "按键 ${i + 1} ($k)" }.toTypedArray()
                        android.app.AlertDialog.Builder(context)
                            .setTitle("选择要修改的按键")
                            .setItems(labels) { _, idx ->
                                val cur = PrefsManager.gamepadKeys.getOrElse(idx) { "J" }
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("按键 ${idx + 1} 映射")
                                    .setSingleChoiceItems(allKeys, allKeys.indexOf(cur).coerceAtLeast(0)) { dlg, w ->
                                        PrefsManager.sp.edit().putString("gamepad_key_${idx + 1}", allKeys[w]).apply()
                                        dlg.dismiss()
                                        rebuildGamepad()
                                        Toast.makeText(context, "按键 ${idx + 1} 已设为 ${allKeys[w]}", Toast.LENGTH_SHORT).show()
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    // 1: 添加按键（最多 18 个）
                    1 -> {
                        val c = PrefsManager.gamepadKeyCount
                        if (c < 18) {
                            PrefsManager.sp.edit().putInt("gamepad_key_count", c + 1).apply()
                            rebuildGamepad()
                            Toast.makeText(context, "已添加按键，当前 ${c + 1} 个", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "已达最大按键数 18", Toast.LENGTH_SHORT).show()
                        }
                    }
                    // 2: 删除按键（最少 2 个）
                    2 -> {
                        val c = PrefsManager.gamepadKeyCount
                        if (c > 2) {
                            PrefsManager.sp.edit().putInt("gamepad_key_count", c - 1).apply()
                            rebuildGamepad()
                            Toast.makeText(context, "已删除按键，当前 ${c - 1} 个", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "已达最小按键数 2", Toast.LENGTH_SHORT).show()
                        }
                    }
                    // 3: Start/Select 映射
                    3 -> {
                        val sysItems = arrayOf(
                            "Start 键 (${PrefsManager.startKey})",
                            "Select 键 (${PrefsManager.selectKey})"
                        )
                        android.app.AlertDialog.Builder(context)
                            .setTitle("Start/Select 映射")
                            .setItems(sysItems) { _, idx ->
                                val title = if (idx == 0) "Start 键映射" else "Select 键映射"
                                val current = if (idx == 0) PrefsManager.startKey else PrefsManager.selectKey
                                android.app.AlertDialog.Builder(context)
                                    .setTitle(title)
                                    .setSingleChoiceItems(allKeys, allKeys.indexOf(current).coerceAtLeast(0)) { dlg, w ->
                                        val prefKey = if (idx == 0) "start_key" else "select_key"
                                        PrefsManager.sp.edit().putString(prefKey, allKeys[w]).apply()
                                        dlg.dismiss()
                                        rebuildGamepad()
                                        Toast.makeText(context, "$title 已设为 ${allKeys[w]}", Toast.LENGTH_SHORT).show()
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    // 4: 方向键模式（摇杆/十字键/WASD）
                    4 -> {
                        val modes = arrayOf("摇杆 (joystick)", "十字键 (dpad)", "WASD (wasd)")
                        val values = arrayOf("joystick", "dpad", "wasd")
                        val checked = values.indexOf(PrefsManager.dpadMode).coerceAtLeast(0)
                        android.app.AlertDialog.Builder(context)
                            .setTitle("方向键模式")
                            .setSingleChoiceItems(modes, checked) { dlg, w ->
                                PrefsManager.sp.edit().putString("dpad_mode", values[w]).apply()
                                dlg.dismiss()
                                rebuildGamepad()
                                Toast.makeText(context, "方向键模式已设为 ${modes[w]}", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    // 5: 方向键大小
                    5 -> {
                        val sizes = arrayOf("小 (80%)", "中 (100%)", "大 (120%)", "超大 (150%)")
                        val values = intArrayOf(80, 100, 120, 150)
                        val current = PrefsManager.sp.getInt("dpad_scale", 100)
                        val checked = values.indexOf(current).coerceAtLeast(0)
                        android.app.AlertDialog.Builder(context)
                            .setTitle("方向键大小")
                            .setSingleChoiceItems(sizes, checked) { dlg, w ->
                                PrefsManager.sp.edit().putInt("dpad_scale", values[w]).apply()
                                dlg.dismiss()
                                rebuildGamepad()
                                Toast.makeText(context, "方向键大小已设为 ${sizes[w]}", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    // 6: 动作按键大小
                    6 -> {
                        val sizes = arrayOf("小 (80%)", "中 (100%)", "大 (120%)", "超大 (150%)")
                        val values = intArrayOf(80, 100, 120, 150)
                        val current = PrefsManager.sp.getInt("gamepad_scale", 100)
                        val checked = values.indexOf(current).coerceAtLeast(0)
                        android.app.AlertDialog.Builder(context)
                            .setTitle("动作按键大小")
                            .setSingleChoiceItems(sizes, checked) { dlg, w ->
                                PrefsManager.sp.edit().putInt("gamepad_scale", values[w]).apply()
                                dlg.dismiss()
                                rebuildGamepad()
                                Toast.makeText(context, "动作按键大小已设为 ${sizes[w]}", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    // 7: 显示/隐藏按键（多选）
                    7 -> {
                        val keys = PrefsManager.gamepadKeys
                        val keyVisible = PrefsManager.gamepadKeyVisible
                        val labels = ArrayList<String>()
                        val prefKeys = ArrayList<String>()
                        val checkedArr = ArrayList<Boolean>()
                        keys.forEachIndexed { i, k ->
                            labels.add("按键 ${i + 1} ($k)")
                            prefKeys.add("gamepad_key_${i + 1}_visible")
                            checkedArr.add(keyVisible.getOrElse(i) { true })
                        }
                        labels.add("方向键")
                        prefKeys.add("dpad_visible")
                        checkedArr.add(PrefsManager.isDpadVisible)
                        labels.add("Start/Select")
                        prefKeys.add("system_buttons_visible")
                        checkedArr.add(PrefsManager.isSystemButtonsVisible)
                        val checkedBooleans = checkedArr.toBooleanArray()
                        android.app.AlertDialog.Builder(context)
                            .setTitle("显示/隐藏按键")
                            .setMultiChoiceItems(labels.toTypedArray(), checkedBooleans) { _, whichItem, isChecked ->
                                checkedBooleans[whichItem] = isChecked
                            }
                            .setPositiveButton("确定") { dlg, _ ->
                                val editor = PrefsManager.sp.edit()
                                prefKeys.forEachIndexed { i, pk -> editor.putBoolean(pk, checkedBooleans[i]) }
                                editor.apply()
                                dlg.dismiss()
                                rebuildGamepad()
                                Toast.makeText(context, "按键显示设置已保存", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    // 8: 添加/隐藏鼠标按钮
                    8 -> {
                        val visible = !PrefsManager.isMouseButtonsVisible
                        PrefsManager.sp.edit()
                            .putBoolean("mouse_buttons_visible", visible)
                            .putBoolean("mouse_enabled", visible)
                            .apply()
                        isMouseEnabled = visible
                        val wv = webViewRef.value
                        if (wv != null) {
                            if (visible) {
                                wv.evaluateJavascript(GameWebViewClient.MOUSE_CURSOR_SCRIPT, null)
                            } else {
                                wv.evaluateJavascript(
                                    "(function(){var c=document.getElementById('__mouseCursor');if(c)c.remove();window.__mouseEnabled=false;})();",
                                    null
                                )
                            }
                        }
                        Toast.makeText(context, if (visible) "鼠标按钮已显示" else "鼠标按钮已隐藏", Toast.LENGTH_SHORT).show()
                    }
                    // 9: 位置编辑模式（拖动调整）
                    9 -> {
                        isPositionEditMode = !isPositionEditMode
                        dpadRef.value?.isDragMode = isPositionEditMode
                        actionRef.value?.isDragMode = isPositionEditMode
                        mouseRef.value?.isDragMode = isPositionEditMode
                        Toast.makeText(
                            context,
                            if (isPositionEditMode) "位置编辑模式已开启" else "位置编辑模式已关闭",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    // 10: 视角旋转 (3D游戏)
                    10 -> {
                        toggleCameraRotation()
                    }
                    // 11: 恢复默认
                    11 -> {
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
                            .putFloat("mouse_pos_x", -1f)
                            .putFloat("mouse_pos_y", -1f)
                            .putFloat("system_pos_x", -1f)
                            .putFloat("system_pos_y", -1f)
                            .apply()
                        rebuildGamepad()
                        Toast.makeText(context, "已恢复默认按键设置", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .setOnDismissListener { showKeyDialog.value = false }
            .show()
    }

    // ========== 页面缩放弹窗 ==========
    if (showZoomDialog.value) {
        val mode = PrefsManager.pageZoomMode
        val manual = PrefsManager.pageZoomManual

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
            max = 175
            progress = (manual - 25).coerceIn(0, 175)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    currentText.text = "缩放：${p + 25}%"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
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

        android.app.AlertDialog.Builder(context)
            .setTitle("页面缩放")
            .setView(container)
            .setPositiveButton("应用") { _, _ -> applyZoom("manual", seekBar.progress + 25); showZoomDialog.value = false }
            .setNegativeButton("自动") { _, _ -> applyZoom("auto", manual); showZoomDialog.value = false }
            .setNeutralButton("取消") { _, _ -> showZoomDialog.value = false }
            .setOnDismissListener { showZoomDialog.value = false }
            .show()
    }

    // ========== 兼容模式（UA）弹窗 ==========
    if (showUaDialog.value) {
        val modes = arrayOf("桌面模式 (Chrome)", "兼容模式 (IE11)", "移动模式")
        val values = arrayOf("desktop", "ie_compat", "mobile")
        val current = PrefsManager.uaMode
        val checked = values.indexOf(current).coerceAtLeast(0)
        android.app.AlertDialog.Builder(context)
            .setTitle("浏览器兼容模式")
            .setSingleChoiceItems(modes, checked) { dlg, which ->
                applyUa(values[which])
                dlg.dismiss()
                showUaDialog.value = false
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { showUaDialog.value = false }
            .show()
    }

    // ========== 画面比例弹窗(包含自定义布局入口) ==========
    if (showAspectRatioDialog.value) {
        // ★ 新增「全屏拉伸 (stretch)」：exactFit 无黑边铺满 —— 看图类 SWF
        //   （自管 Stage.scaleMode、原生比例定死）此前任何设置都不变形的
        //   唯一出路。选择后自动重载播放页立即生效（见 applyAspectRatio）。
        val ratios = arrayOf("全屏拉伸 (无黑边)", "全屏自适应 (auto)", "4:3", "16:9", "16:10", "5:4", "自定义(拖动四角)")
        val values = arrayOf("stretch", "auto", "4:3", "16:9", "16:10", "5:4", "__custom__")
        val current = PrefsManager.gameAspectRatio
        // 如果当前是自定义布局,把"自定义"标为选中（★ 索引随 stretch 项加入后移到 6）
        val checked = if (PrefsManager.isCustomLayoutEnabled) 6
                       else values.indexOf(current).coerceAtLeast(0)
        android.app.AlertDialog.Builder(context)
            .setTitle("画面比例")
            .setSingleChoiceItems(ratios, checked) { dlg, which ->
                if (values[which] == "__custom__") {
                    // 进入自定义布局编辑模式:显示 ScreenPositionEditor overlay(参考 eden-an)
                    enterCustomLayoutEditMode()
                } else {
                    // 标准比例:关掉自定义,WebView 恢复 MATCH_PARENT
                    exitCustomLayoutEditMode(resetRect = true)
                    applyAspectRatio(values[which])
                }
                dlg.dismiss()
                showAspectRatioDialog.value = false
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { showAspectRatioDialog.value = false }
            .show()
    }

    // ========== 自定义四角布局 Overlay ==========
    // 直接由 ScreenPositionEditor(原生 View)负责 4 角 + body 拖动。
    // 这里只画顶部状态条 + 完成/重置按钮(Compose UI,不动触摸分发)。
    if (customLayoutEditState.value) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 顶部提示条
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(8.dp)
            ) {
                Text(
                    "自由布局:拖动 4 角或矩形内部,边拖边看效果",
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
            // 右下角完成按钮
            androidx.compose.material3.Button(
                onClick = { exitCustomLayoutEditMode(resetRect = false) },
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Text("完成")
            }
            // 左下角重置按钮
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    val editor = screenPositionEditorRef.value ?: return@OutlinedButton
                    editor.setRect(0f, 0f, 1f, 1f)
                    PrefsManager.setCustomLayout(false, 0f, 0f, 1f, 1f)
                },
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Text("重置")
            }
        }
    }  // if (customLayoutEditState.value) 关闭
}

/**
 * 自定义四角布局 Overlay:
 * - 直接在游戏画面上显示 4 个角的拖动手柄(28dp 圆形,绿色)
 * - 矩形 4 边用 2dp 绿线标记选中区域
 * - 拖动手柄实时改 WebView LayoutParams,边拖边看到游戏画面变化
 * - 右上角关闭按钮 + 重置全屏按钮
 *
 * 关键:Handle 用 pointerInput + detectDragGestures(只处理拖动手柄本身,不影响 WebView 触摸)
 * BoxWithConstraints 拿到实际像素宽高,转成归一化 0..1 值
 *
 * 完整实现已迁移到 ScreenPositionEditor.kt(原生 View,参考 eden-an 实现):
 * - 5 个 hot zone:4 角 + body 整体拖动
 * - View 自身 onDraw + onTouchEvent,Compose 端只画顶部状态条 + 完成/重置按钮
 */

/**
 * 自定义布局编辑模式状态(顶级可变状态,顶级 enter/exit 函数也能改)。
 * 实际是 Composable 内 MutableState 的副本引用。
 */
private val customLayoutEditState = androidx.compose.runtime.mutableStateOf(false)

/**
 * 顶级引用:ScreenPositionEditor 视图(由 Composable 内的 AndroidView factory 创建,顶级函数也能改)。
 * 跟原来的 screenPositionEditorRef (Composable 局部 remember) 一样,只是搬到顶级让顶级函数能改。
 */
private val _screenPositionEditor = androidx.compose.runtime.mutableStateOf<ScreenPositionEditor?>(null)
private val _mainContainer = androidx.compose.runtime.mutableStateOf<android.widget.FrameLayout?>(null)
private val _webView = androidx.compose.runtime.mutableStateOf<View?>(null)
/**
 * 进入自定义布局编辑模式(画面比例弹窗里选"自定义"):
 * - 取消 WebView 触摸焦点
 * - 显示 ScreenPositionEditor(显示 4 角手柄 + 半透明矩形,接管触摸)
 * - 已有保存的布局:直接 setRect
 * - 没有:默认全屏(0,0,1,1)
 * - 标记 PrefsManager.isCustomLayoutEnabled = true(用户意图)
 */
private fun enterCustomLayoutEditMode() {
    val editor = _screenPositionEditor.value ?: return
    val container = _mainContainer.value ?: return

    // 取消 WebView 焦点,避免 WebView 抢触摸
    val wv = _webView.value
    if (wv != null) {
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
    }

    if (PrefsManager.isCustomLayoutEnabled) {
        editor.setRect(
            PrefsManager.customLayoutLeft,
            PrefsManager.customLayoutTop,
            PrefsManager.customLayoutRight,
            PrefsManager.customLayoutBottom
        )
    } else {
        // 第一次进入,默认全屏
        editor.setRect(0f, 0f, 1f, 1f)
    }
    // 标记用户意图启用(进入编辑模式即算启用)
    PrefsManager.setCustomLayout(true,
        editor.getRect()[0], editor.getRect()[1],
        editor.getRect()[2], editor.getRect()[3])
    editor.bringToFront()
    editor.visibility = View.VISIBLE
    editor.invalidate()
    container.requestLayout()
    customLayoutEditState.value = true
}

/**
 * 退出自定义布局编辑模式。
 * @param resetRect true 表示重置回全屏(MATCH_PARENT),false 表示保留当前 rect
 */
private fun exitCustomLayoutEditMode(resetRect: Boolean = false) {
    val editor = _screenPositionEditor.value
    if (editor != null) {
        editor.visibility = View.GONE
    }
    val wv = _webView.value
    val container = _mainContainer.value
    if (resetRect && wv != null && container != null) {
        PrefsManager.setCustomLayout(false, 0f, 0f, 1f, 1f)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.layoutParams = lp
        wv.requestLayout()
    }
    // 恢复 WebView 焦点(用户回到正常模式)
    if (wv != null) {
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
    }
    customLayoutEditState.value = false
}

/**
 * 把当前 ScreenPositionEditor 的 rect 应用到 WebView layoutParams。
 * 用于首次创建/旋转屏幕后恢复自定义布局。
 */
private fun applyCustomLayoutToWebView() {
    if (!PrefsManager.isCustomLayoutEnabled) return
    val wv = _webView.value ?: return
    val container = _mainContainer.value ?: return
    val pw = container.width
    val ph = container.height
    if (pw <= 0 || ph <= 0) return
    val x1 = PrefsManager.customLayoutLeft
    val y1 = PrefsManager.customLayoutTop
    val x2 = PrefsManager.customLayoutRight
    val y2 = PrefsManager.customLayoutBottom
    val lp = FrameLayout.LayoutParams(
        ((x2 - x1) * pw).toInt().coerceAtLeast(1),
        ((y2 - y1) * ph).toInt().coerceAtLeast(1)
    )
    lp.leftMargin = (x1 * pw).toInt()
    lp.topMargin = (y1 * ph).toInt()
    wv.layoutParams = lp
    wv.requestLayout()
}

private fun showExtractResultDialog(context: Context, json: String) {
    try {
        val arr = org.json.JSONArray(json)
        val lines = mutableListOf<String>()
        var swfCount = 0
        var resCount = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("url", "")
            val t = o.optString("title", u.substringAfterLast('/'))
            val type = o.optString("type", "swf")
            if (type == "resource") {
                resCount++
                lines.add("[资源] $t\n  $u")
            } else {
                swfCount++
                lines.add("[SWF] $t\n  $u")
            }
        }
        val msg = buildString {
            append("发现 $swfCount 个 SWF" + if (resCount > 0) " + $resCount 个资源" else "")
            if (lines.isNotEmpty()) {
                append("\n\n")
                append(lines.joinToString("\n\n").take(1500))
                if (lines.size > 8) append("\n\n... (共 ${lines.size} 条)")
            }
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("提取 SWF")
            .setMessage(msg)
            .setPositiveButton("好") { d, _ -> d.dismiss() }
            .show()
    } catch (e: Exception) {
        Toast.makeText(context, "解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// ============== SWF 嗅探器脚本（与 3.3 一致） ==============
private const val SWF_SNIFFER_SCRIPT = """
(function(){
  var found = {};
  var resources = {};
  // 资源文件扩展名(Flash 游戏常见外部资源)
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
    if(!/^https?:/i.test(url)) return;
    if(/\.swf([?#]|$)/i.test(url)) return;
    if(!resExts.test(url)) return;
    if(resources[url]) return;
    var subDir = '';
    try {
      var p = new URL(url).pathname;
      var dir = p.substring(0, p.lastIndexOf('/') + 1);
      if(dir.startsWith('/')) dir = dir.substring(1);
      subDir = dir;
    } catch(e) {}
    var name = url.split('/').pop().split('?')[0] || 'resource';
    resources[url] = {url:url, title:name, size:'', type:'resource', subDir:subDir};
  }
  function report(){
    var arr = [];
    for(var u in found) arr.push(found[u]);
    for(var u in resources) arr.push(resources[u]);
    if(arr.length > 0 && window.Android && window.Android.onSwfFound){
      window.Android.onSwfFound(JSON.stringify(arr));
    } else if (window.Android && window.Android.toast) {
      window.Android.toast('未发现 SWF 或资源');
    }
  }
  function scanDOM(){
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
    var all = document.querySelectorAll('[data*=".swf"], [src*=".swf"], [href*=".swf"]');
    all.forEach(function(el){
      ['data','src','href'].forEach(function(attr){
        var v = el.getAttribute(attr);
        if(v && /\.swf/i.test(v)) addSwf(v, el.getAttribute('title') || '');
      });
    });
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
        if(/\.swf([?#]|$)/i.test(e.name)) addSwf(e.name, '');
        else if(resExts.test(e.name)) addResource(e.name);
      });
    } catch(e) {}
  }
  // 关键:hook XHR/fetch,捕获 mhhf 等动态加载的 SWF/资源
  if(!window.__swfSniffHooked){
    window.__swfSniffHooked = true;
    try {
      var origOpen = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function(method, url){
        if(url && /\.swf([?#]|$)/i.test(url)) addSwf(url, '');
        else if(url && resExts.test(url)) addResource(url);
        return origOpen.apply(this, arguments);
      };
    } catch(e) {}
    try {
      var origFetch = window.fetch;
      if(origFetch){
        window.fetch = function(input){
          var u = typeof input === 'string' ? input : (input && input.url ? input.url : '');
          if(u && /\.swf([?#]|$)/i.test(u)) addSwf(u, '');
          else if(u && resExts.test(u)) addResource(u);
          return origFetch.apply(this, arguments);
        };
      }
    } catch(e) {}
  }
  scanDOM();
  scanPerformance();
  report();
  // 多次扫描捕获延迟加载(mhhf 等 SPA 路由后加载)
  setTimeout(function(){ scanDOM(); scanPerformance(); report(); }, 1000);
  setTimeout(function(){ scanDOM(); scanPerformance(); report(); }, 3000);
  setTimeout(function(){ scanDOM(); scanPerformance(); report(); }, 6000);
  // MutationObserver:监听 DOM 变化(mhhf 切页/弹窗)
  if(window.MutationObserver){
    var mo = new MutationObserver(function(){
      scanDOM();
    });
    try {
      mo.observe(document.documentElement || document.body || document, {childList:true, subtree:true});
      setTimeout(function(){ mo.disconnect(); }, 8000);
    } catch(e) {}
  }
})();
"""
