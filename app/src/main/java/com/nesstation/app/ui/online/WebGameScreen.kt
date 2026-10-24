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
// ★ v12: 标签栏所需
import androidx.compose.foundation.lazy.LazyRow
// ★ v17: 主页桌面网格横向滚动 + 紧凑地址栏光标所需
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
// ★ v21: 背景设置对话框横屏可滚动（内容超出小高度不再被裁）
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
// ★ 主页优化版：横屏/平板下搜索框、引擎行、收藏网格限宽居中所需
import androidx.compose.foundation.layout.widthIn
// ★ v24: 收藏网格限高（heightIn）—— 网格不再吃满剩余高度
import androidx.compose.foundation.layout.heightIn
// ★ v11 浏览器化：工具栏/收藏夹主页所需
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
// ★ v11.1 热修：v11 新增工具栏/收藏夹 UI 用了 Modifier.size()（21 处）但漏了
//   本导入 —— CI "Unresolved reference: size" ×21（其余 7 个 v11 文件无此问题）。
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import com.nesstation.app.flash.webview.WebPinchLayout
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.flash.download.DownloadStatus
import com.nesstation.app.flash.download.SwfDownloadItem
import com.nesstation.app.flash.download.SwfDownloadManager
import com.nesstation.app.ui.swf.SwfExtractDialog
import com.nesstation.app.ui.swf.SwfItem
import kotlinx.coroutines.flow.MutableStateFlow
// ★ v24: 主页双指缩放（手势采集）
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.clipToBounds

// ★ Neon 主题适配：播放器底色随主题切换（Neon=深空黑蓝）
private val PrimaryBackground
    get() = if (com.nesstation.app.ui.neon.NeonUi.isNeon) com.nesstation.app.ui.neon.Neon.Bg
        else Color(0xFF0F1115)

/**
 * ★ v11: 浏览器主页伪协议 —— 在线游戏入口直通真实浏览器，
 * 主页 = 收藏夹网格（默认种子：原内置 12 站点 + 用户旧自添加站点）。
 * 主页的 WebView 载体是 about:blank，由 BrowserHomeOverlay 全覆盖。
 */
const val BROWSER_HOME_URL = "nesstation://home"

/** 主页（about:blank）判定 */
private const val BLANK_URL = "about:blank"

/**
 * ★ v11: 地址栏输入规整（参照 AnWind normalizeUrl）：
 * - 带协议前缀 → 原样；
 * - 无空格且含点（域名启发）→ 补 https://；
 * - 其余 → 百度搜索（关键词用户主要呈中文，站点搜索覆盖面最广）。
 */
private fun normalizeUrl(input: String): String {
    val t = input.trim()
    if (t.isEmpty()) return ""
    val lower = t.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://") ||
        lower.startsWith("file://") || lower.startsWith("content://") ||
        lower.startsWith("about:")) return t
    val isDomain = !t.contains(' ') && (t.contains('.') || lower.startsWith("localhost"))
    return if (isDomain) "https://$t"
    else "https://www.baidu.com/s?wd=" + java.net.URLEncoder.encode(t, "UTF-8")
}

/**
 * 在线网页游戏/Flash 游戏的 Compose 入口。
 *
 * 1:1 移植自 3.3-fix2 GameActivity：把 3.3 的 setupWebView / setupGamepad / setupFloatingMenu
 * 全部用 AndroidView 嵌入到 Compose 里。
 *
 * ★ v11 起本屏同时是「真实浏览器」：
 * - 顶部常驻浏览器工具栏（后退/前进/刷新停止/主页/地址栏/收藏星标）；
 * - 入口传 [BROWSER_HOME_URL] 时落在浏览器主页 = 收藏夹网格
 *   （默认种子：原内置 12 站点 + 用户旧自添加站点，支持增/删/改名/图标）；
 * - WebView 由 WebPinchLayout 仲裁容器承载：双指缩放强制可用且连续
 *   （页面 meta/preventDefault 禁缩放时容器接管）；
 * - 返回键优先级：网页全屏 → 弹窗 → 退出工具栏全屏 → 网页后退 → 回主页 → 退出。
 *
 * @param url   入口 URL（页面 URL / .swf 直链 / [BROWSER_HOME_URL]）
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

    // ★ v17: 在线游戏手柄默认不显示（用户反馈）—— 不再读 PrefsManager.isGamepadEnabled
    //   （全局开关默认开），改读本屏专属偏好 web_gamepad_visible（默认 false）。
    //   toggleGamepad() 同步持久化 → 用户打开过就一直记住，首次进入不再默认铺手柄。
    var gamepadVisible by remember { mutableStateOf(PrefsManager.sp.getBoolean("web_gamepad_visible", false)) }
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
    // ★ v17: 模拟摇杆（方向键模式 = stick_wasd 时取代十字键）
    val stickRef = remember { mutableStateOf<WebStickView?>(null) }
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

    // ★ v10: 加载进度 / 渲染进程自愈（renderEpoch）/ 回调共享引用
    val loadProgress = remember { mutableStateOf(100) }
    val renderEpoch = remember { mutableStateOf(0) }
    // ★ v12: 多标签引擎（参照 AnWind TabManager —— 每标签独占常驻 WebView +
    //   缩放容器，切换只 attach/detach 不销毁重建；window.open 弹窗 = 新标签，
    //   不再永久叠层 —— 根除 v11"开新标签后回主页，点任何收藏都还是旧弹窗页面"）
    val browserTabs = remember { mutableStateListOf<BrowserTabInfo>() }
    var activeTabId by remember { mutableStateOf(0) }
    val tabTitleMap = remember { mutableStateMapOf<Int, String>() }
    var nextTabId by remember { mutableStateOf(1) }
    val wvClientRef = remember { mutableStateOf<GameWebViewClient?>(null) }
    val chromeCallbackRef = remember { mutableStateOf<GameWebChromeClient.Callback?>(null) }

    // 引擎选择对话框 / 提取 SWF 对话框
    var pendingSwfAction by remember { mutableStateOf<String?>(null) }

    // ★ v11 浏览器化：主页/地址栏/页面标题/收藏夹/工具栏状态
    var homeVisible by remember { mutableStateOf(url == BROWSER_HOME_URL) }
    var addressText by remember { mutableStateOf("") }
    var addressBarFocused by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf(WebFavoritesStore.loadAll(context)) }
    var showAddFavoriteDialog by remember { mutableStateOf(false) }
    var menuFavGame by remember { mutableStateOf<WebGameEntry?>(null) }
    var pendingFavIcon by remember { mutableStateOf<WebGameEntry?>(null) }
    var pendingFavRename by remember { mutableStateOf<WebGameEntry?>(null) }
    // 当前缩放值（活动标签的容器回调同步）
    val pinchZoom = remember { mutableStateOf(1f) }

    // ---------- File chooser ----------
    val filePathCallback = remember { mutableStateOf<ValueCallback<Array<android.net.Uri>>?>(null) }
    val activityResultLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val arr = uris.takeIf { it.isNotEmpty() }?.toTypedArray()
        filePathCallback.value?.onReceiveValue(arr)
        filePathCallback.value = null
    }

    // ★ v11: 收藏夹自定义图标选择器（主页长按收藏 → 自定义图标）
    val favIconPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val fav = pendingFavIcon
        pendingFavIcon = null
        val uri = uris.firstOrNull()
        if (uri == null || fav == null) return@rememberLauncherForActivityResult
        try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            val iconsDir = java.io.File(context.filesDir, "icons").apply { mkdirs() }
            val dest = java.io.File(iconsDir,
                "fav_${System.currentTimeMillis()}_${uri.lastPathSegment?.substringAfterLast('/') ?: "icon"}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取所选图片")
            WebFavoritesStore.setCustomIcon(context, fav.url, dest.absolutePath)
            favorites = WebFavoritesStore.loadAll(context)
            Toast.makeText(context, "已设置收藏图标：${fav.customTitle ?: fav.title}", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "图标设置失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Activity (for fullscreen / orientation) ----------
    val activity = context as? Activity

    // ---------- 屏幕方向 / 全屏（★ v11 上移：keyEventHandler/BackHandler 引用须先声明） ----------
    fun applyOrientation(landscape: Boolean) {
        activity?.requestedOrientation = if (landscape)
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    // 切换全屏（★ v12：浏览器会话进入即沉浸 + 占用刘海（见 DisposableEffect），
    //   系统栏全程隐藏，此处只切换工具栏/标签栏显隐 —— 若再动系统栏，一退出
    //   全屏就重新露出黑边、刘海又被让出去。）
    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
    }

    fun toggleOrientation() {
        isLandscape = !isLandscape
        applyOrientation(isLandscape)
        floatingMenuRef.value?.isLandscape = isLandscape
    }

    // ================ ★ v12: 多标签引擎（参照 AnWind TabManager） ================

    /** view → 所在标签 */
    fun findTabOf(wv: WebView?): BrowserTabInfo? =
        browserTabs.firstOrNull { it.webView === wv }

    /** 当前活动标签 */
    fun activeTab(): BrowserTabInfo? =
        browserTabs.firstOrNull { it.id == activeTabId }

    /** JS 静音（销毁前执行，防"关了标签声音还在"） */
    fun muteWebView(wv: WebView) {
        try {
            wv.evaluateJavascript(
                "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){" +
                    "try{m.pause();}catch(e){}});}catch(e){}})();", null
            )
        } catch (_: Exception) {}
    }

    /** 标签附着内容容器（独占全屏；控件组拉回最上层） */
    fun attachTab(tab: BrowserTabInfo) {
        val container = mainBoxRef.value ?: return
        if (tab.pinch.parent !== container) {
            (tab.pinch.parent as? ViewGroup)?.removeView(tab.pinch)
            container.addView(tab.pinch, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        listOfNotNull(
            dpadRef.value, actionRef.value, mouseRef.value,
            systemButtonsRef.value, floatingMenuRef.value
        ).forEach { it.bringToFront() }
    }

    /** 切换活动标签 —— 活动标签独占显示（v11"弹窗永久叠层盖住主窗"根除点） */
    fun switchToTab(id: Int) {
        val tab = browserTabs.firstOrNull { it.id == id } ?: return
        if (id == activeTabId) return
        val container = mainBoxRef.value
        // 其余标签全部脱离视图树（常驻内存，切回即恢复，不重载）
        browserTabs.forEach { t ->
            if (t.id != id && t.pinch.parent === container) container?.removeView(t.pinch)
        }
        activeTabId = id
        webViewRef.value = tab.webView
        attachTab(tab)
        dpadRef.value?.targetWebView = tab.webView
        actionRef.value?.targetWebView = tab.webView
        stickRef.value?.targetWebView = tab.webView
        mouseRef.value?.targetWebView = tab.webView
        // UI 状态随标签同步（缩放/地址栏/主页）
        pinchZoom.value = tab.pinch.currentZoom()
        val u = tab.webView.url
        val isHome = u == null || u == BLANK_URL
        homeVisible = isHome
        if (!addressBarFocused) addressText = if (isHome) "" else (u ?: "")
    }

    /** 关闭标签（window.close / 标签栏 X / 后退过栈底 / 渲染进程死亡） */
    fun closeTab(id: Int) {
        val tab = browserTabs.firstOrNull { it.id == id } ?: return
        if (browserTabs.size <= 1) {
            Toast.makeText(context, "最后一个标签无法关闭", Toast.LENGTH_SHORT).show()
            return
        }
        val wasActive = tab.id == activeTabId
        browserTabs.remove(tab)
        muteWebView(tab.webView)
        (tab.pinch.parent as? ViewGroup)?.removeView(tab.pinch)
        tab.pinch.detachWebView()
        try { tab.webView.destroy() } catch (_: Exception) {}
        tabTitleMap.remove(tab.id)
        if (wasActive) {
            browserTabs.lastOrNull()?.let { switchToTab(it.id) }
        }
    }

    // ================ ★ v11: 浏览器导航助手（须先于 keyEventHandler/BackHandler 声明） ================

    /** 回浏览器主页（收藏夹网格）。WebView 载体是 about:blank（在后台栈里，
     *  返回时自然回到它），主页 UI 由 BrowserHomeOverlay 全覆盖。 */
    fun goHome() {
        val wv = webViewRef.value ?: return
        homeVisible = true
        if (!addressBarFocused) addressText = ""
        wv.loadUrl(BLANK_URL)
    }

    /**
     * 浏览器「后退」语义链：
     * 1. WebView 有历史 → goBack（返回上一页，用户核心诉求）；
     * 2. 无历史且当前不在主页 → 回主页（不再“直接退回模拟器主页”）；
     * 返回 false = 已在主页且无历史（交由调用方决定退出）。
     */
    fun browserBack(): Boolean {
        val tab = activeTab() ?: return false
        val wv = tab.webView
        if (wv.canGoBack()) {
            wv.goBack()
            return true
        }
        // ★ v12: 弹窗标签退过栈底 = 关闭标签回 opener（Chrome 语义；
        //   v11 弹窗永久叠层、主页/收藏按钮对它完全无效，只能靠返回键逐层销毁）。
        if (tab.isPopup && browserTabs.size > 1) {
            closeTab(tab.id)
            return true
        }
        if (!homeVisible) {
            goHome()
            return true
        }
        return false
    }

    /** 打开网址（收藏/地址栏/搜索共用）：同步 UA 模式后加载。 */
    fun openInBrowser(target: String, ua: String) {
        val wv = webViewRef.value ?: return
        if (target.isBlank()) return
        // ★ v14: 全局 UA 模式优先 —— 收藏/入口各自存的 uaMode 不再凌驾于用户
        //   当前选择（“切了模式点收藏还是老样子”根治）；仅显式 ie_compat 的
        //   收藏（老 Flash 页兼容需要）保留专属模式。桌面/兼容模式的新页视口
        //   拉宽与客户端提示伪装由 UA_DESKTOP_SPOOF_SCRIPT 按 UA 自适应生效。
        val effectiveUa = if (ua == "ie_compat") "ie_compat" else PrefsManager.uaMode
        PrefsManager.sp.edit().putString("ua_mode", effectiveUa).apply()
        when (effectiveUa) {
            "ie_compat" -> wv.useUaMode("ie_compat")
            "mobile" -> wv.useUaMode("mobile")
            else -> wv.useUaMode("desktop")
        }
        homeVisible = false
        wv.loadUrl(target)
    }

    /** 地址栏 Go：输入规整（域名补协议/关键词搜索）后加载。 */
    fun navigateAddress(input: String) {
        val target = normalizeUrl(input)
        if (target.isNotEmpty()) openInBrowser(target, PrefsManager.uaMode)
    }

    /** 工具栏收藏星标：当前页加入/移出收藏夹（主页无网页可收藏）。 */
    fun toggleFavorite() {
        val wv = webViewRef.value ?: return
        val current = wv.url ?: return
        if (current.isBlank() || current == BLANK_URL) {
            Toast.makeText(context, "主页无网页可收藏", Toast.LENGTH_SHORT).show()
            return
        }
        if (WebFavoritesStore.isFavorite(context, current)) {
            WebFavoritesStore.remove(context, current)
            favorites = WebFavoritesStore.loadAll(context)
            Toast.makeText(context, "已从收藏夹移除", Toast.LENGTH_SHORT).show()
        } else {
            val title = pageTitle.ifBlank { wv.title ?: current }
            WebFavoritesStore.add(context, title, current, PrefsManager.uaMode)
            favorites = WebFavoritesStore.loadAll(context)
            Toast.makeText(context, "已加入收藏夹（主页长按可管理）", Toast.LENGTH_SHORT).show()
        }
    }

    /** 销毁一个被 WebPinchLayout 承载的 WebView（含容器）：主窗/弹窗/渲染自愈共用。 */
    fun destroyWebViewHolder(wv: android.webkit.WebView) {
        val holder = wv.parent as? ViewGroup
        holder?.removeView(wv)
        (holder?.parent as? ViewGroup)?.removeView(holder)
        try { wv.destroy() } catch (_: Exception) {}
    }

    // ---------- 物理键盘（实体键盘）透传 ----------
    val keyEventHandler: (KeyEvent) -> Boolean = { event ->
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // 系统 BACK 键：★ v11 浏览器语义 —— 工具栏全屏先退全屏；
            // 网页能后退就后退；无历史回主页；已在主页才退出。
            if (event.action == KeyEvent.ACTION_UP) {
                if (isFullscreen) {
                    toggleFullscreen()
                } else if (!browserBack()) {
                    onExit()
                }
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
        // 系统返回键：★ v11 与物理键同链（全屏 → 网页后退 → 回主页 → 退出）。
        // 网页全屏视频由后注册的 BackHandler#2 优先处理；★ v12 弹窗已升级为
        // 标签（后退过栈底由 browserBack 的标签链关闭回 opener）。
        if (isFullscreen) {
            toggleFullscreen()
        } else if (!browserBack()) {
            onExit()
        }
    })

    // ================ ★ v12: 标签工厂 / 新建标签 / window.open→标签 ================

    /** 标签页用 GameWebView 工厂（新建标签 / window.open 弹窗共用配置：
     *  复用主窗 client 与 Chrome 回调、JS 桥、UA、缩放基线、按键透传）。 */
    fun newTabWebView(): GameWebView {
        val container = mainBoxRef.value!!
        return GameWebView(container.context).apply {
            webChromeClient = object : GameWebChromeClient(chromeCallbackRef.value!!) {}
            // ★ v12.1: 非空断言修复 CI 496（GameWebViewClient? 不能赋给
            //   WebViewClient 槽位）。主窗 client 在重建 effect 里与 chromeCallback
            //   同源先行赋值（wvClientRef.value = wvClient），能走到新建标签必非空。
            webViewClient = wvClientRef.value!!
            webAppInterfaceRef.value?.let { addJavascriptInterface(it, "Android") }
            when (PrefsManager.uaMode) {
                "ie_compat" -> useUaMode("ie_compat")
                "mobile" -> useUaMode("mobile")
                else -> useDesktopMode(true)
            }
            if (PrefsManager.pageZoomMode == "manual") applyPageZoom(PrefsManager.pageZoomManual)
            setOnKeyListener { _, _, event -> keyEventHandler(event) }
            injectDocumentStartScripts()
        }
    }

    /** 新建空白标签（标签栏"+"；落在主页网格，点收藏/输地址即导航） */
    fun openNewTab() {
        if (browserTabs.size >= 8) {
            Toast.makeText(context, "标签太多啦（最多 8 个）", Toast.LENGTH_SHORT).show()
            return
        }
        val tabId = nextTabId++
        val wv = newTabWebView()
        val pinch = WebPinchLayout(wv.context).apply {
            onZoomChanged = { z -> if (activeTabId == tabId) pinchZoom.value = z }
        }
        pinch.setWebView(wv)
        browserTabs.add(BrowserTabInfo(tabId, wv, pinch, isPopup = false))
        tabTitleMap[tabId] = "新标签"
        switchToTab(tabId)
        homeVisible = true
        if (!addressBarFocused) addressText = ""
        wv.loadUrl(BLANK_URL)
    }

    /** ★ v12: window.close / 页面自行关闭 → 关闭对应标签（原 closePopupWindow） */
    fun closeTabOf(window: WebView?) {
        val wv = window ?: return
        val tab = findTabOf(wv) ?: return
        closeTab(tab.id)
    }

    /**
     * ★ v12: window.open / target=_blank → 新标签（原 createPopupWindow 重构）：
     * 弹窗 WebView 登记为标签并立即激活（Chromium 直接在其中导航，POST 表单 /
     * JS 重定向全兼容），不再永久叠层 —— 点链接开新标签、回主页、点收藏互不遮挡。
     */
    fun createTabFromPopup(opener: WebView?, resultMsg: android.os.Message): Boolean {
        if (mainBoxRef.value == null || wvClientRef.value == null || chromeCallbackRef.value == null) return false
        if (browserTabs.size >= 8) {
            Toast.makeText(context, "标签太多啦（最多 8 个）", Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val tabId = nextTabId++
            val popup = newTabWebView()
            val pinch = WebPinchLayout(popup.context).apply {
                onZoomChanged = { z -> if (activeTabId == tabId) pinchZoom.value = z }
            }
            pinch.setWebView(popup)
            browserTabs.add(BrowserTabInfo(tabId, popup, pinch, isPopup = true))
            tabTitleMap[tabId] = "新标签"
            switchToTab(tabId)
            val transport = resultMsg.obj as? WebView.WebViewTransport
            transport?.webView = popup
            resultMsg.sendToTarget()
            true
        } catch (e: Exception) {
            android.util.Log.w("WebGameScreen", "createTabFromPopup failed", e)
            false
        }
    }

    // ---------- 屏幕方向 / 全屏：★ v11 已整体上移（见 activity 声明后） ----------

    fun toggleGamepad() {
        gamepadVisible = !gamepadVisible
        // ★ v17: 持久化到本屏专属键（与上方初始读取同源），切换跨会话保留
        PrefsManager.sp.edit().putBoolean("web_gamepad_visible", gamepadVisible).apply()
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
        // ★ v11: 统一缩放通道 —— WebPinchLayout 动态 viewport 注入，
        //   即时生效、无需 reload，与双指捏合同一坐标系自然衔接。
        //   （v10 的 setInitialScale+reload 与页面 meta/PINCH_UNLOCK 打架，
        //    用户实测"百分比设置完全没作用"，已整体废弃。）
        webViewRef.value?.applyPageZoom(manual)   // 仅清理通道（textZoom/body.zoom 残留）
        val z = if (mode == "manual") manual.coerceIn(25, 300) / 100f else 1f
        // ★ v12: 缩放走活动标签自己的仲裁容器（每标签各自保留缩放状态）
        activeTab()?.pinch?.setZoomImmediate(z)
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
        // ★ v14: 立即可见反馈（"切了没任何变化"感知问题）—— Toast + 重载；
        //   桌面/兼容模式下新页视口自动拉宽 + 客户端提示伪装
        //   （UA_DESKTOP_SPOOF_SCRIPT 按 UA 自适应，无需额外处理）。
        val uaLabel = when (mode) {
            "ie_compat" -> "兼容模式（IE11）"
            "mobile" -> "移动模式"
            else -> "桌面模式（Chrome）"
        }
        Toast.makeText(context, "已切换：$uaLabel（正在刷新）", Toast.LENGTH_SHORT).show()
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
        // ★ v21: 联动 Flash 引擎缩放 —— 隐藏的 RuffleInjector 与可见的
        //   buildFlashInjectScript 都读 flash_scale/flash_letterbox（此前无任何
        //   UI 写入这两个键 = 引擎永远 showAll 保持原比例不拉伸，即用户反馈
        //   "一些游戏没有被拉伸"的根因）：
        //   · 选具体比例（4:3/16:9/…）= 引擎 exactFit 拉伸填满外层比例容器；
        //   · stretch（全屏拉伸）= exactFit + letterbox off，无黑边铺满全屏
        //     （看图类自管 Stage.scaleMode 的 SWF 唯一有效的拉伸方式）；
        //   · auto = 引擎默认 showAll 不变形。
        //   注意：scale 级变更对"已在游戏中"的实例要重新进入才完全生效
        //   （config 是页面加载时注入的），外层 letterbox 容器即时生效 ——
        //   ★ 当前正在播放 flash.local 播放页时自动重载，立即完全生效。
        val spEdit = PrefsManager.sp.edit().putString("game_aspect_ratio", ratio)
        if (ratio == "auto") {
            spEdit.putString("flash_scale", "showAll").putString("flash_letterbox", "on")
        } else {
            // stretch 与 4:3/16:9 等：exactFit（区别在 letterbox 与外框 CSS）
            spEdit.putString("flash_scale", "exactFit").putString("flash_letterbox", "off")
        }
        spEdit.apply()
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
            // ★ 当前标签正在播放内置播放页（本地 SWF / 提取的 SWF）→ 以当前
            //   页面的 swf/base 参数重建播放页 URL（NavHelper 按最新偏好拼
            //   scale/letterbox/aspect），重载后 exactFit/off 立即完全生效；
            //   普通网页不动（比例设置对之后打开的 SWF 生效）。
            val curUrl = wv.url ?: ""
            if (curUrl.startsWith("https://flash.local/player") ||
                curUrl.startsWith("https://flash.local/waflash")) {
                wv.postDelayed({
                    try {
                        val parsed = android.net.Uri.parse(curUrl)
                        val swfParam = parsed.getQueryParameter("swf")
                        if (!swfParam.isNullOrEmpty()) {
                            val baseParam = parsed.getQueryParameter("base")
                            val playerUrl = NavHelper.playerUrl(swfParam, base = baseParam, title = null)
                            wv.releaseAllKeys()
                            wv.loadUrl(playerUrl)
                        } else {
                            wv.reload()
                        }
                    } catch (_: Throwable) {
                        try { wv.reload() } catch (_: Throwable) {}
                    }
                }, 150)
            }
        }
        val modeText = if (ratio == "auto") "全屏自适应（保持原比例不拉伸）"
            else if (ratio == "stretch") "全屏拉伸（无黑边铺满）"
            else "按 $ratio 拉伸显示"
        Toast.makeText(context, "画面比例：$modeText", Toast.LENGTH_SHORT).show()
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

    // ================ ★ v10/v12: 网页全屏退出（标签管理已上移至多标签引擎） ================

    /** 网页全屏退出（返回键与 ChromeClient.onHideFullscreen 共用同一逻辑） */
    fun exitWebFullscreen() {
        val view = customViewRef.value
        if (view != null) (view.parent as? ViewGroup)?.removeView(view)
        customViewCallbackRef.value?.onCustomViewHidden()
        customViewRef.value = null
        customViewCallbackRef.value = null
    }

    // ★ v12: 视频全屏优先于标签/网页后退（后注册的 BackHandler 优先生效）——
    //   window.open 已升级为标签（后退过栈底由 browserBack 关闭标签回 opener）。
    BackHandler(enabled = customViewRef.value != null) {
        if (customViewRef.value != null) {
            exitWebFullscreen()
        }
    }

    // Compose 主容器（★ v11: Column = 浏览器工具栏 + 内容区；全屏时工具栏隐藏）
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ===== ★ v12: 标签栏（≥2 个标签时显示；AnWind TabBar 手机化） =====
            if (!isFullscreen && browserTabs.size > 1) {
                BrowserTabStrip(
                    tabs = browserTabs,
                    activeTabId = activeTabId,
                    titles = tabTitleMap,
                    onSelect = { switchToTab(it) },
                    onClose = { closeTab(it) },
                    onNew = { openNewTab() }
                )
            }

            // ===== 浏览器工具栏（后退/前进/刷新停止/主页/地址栏/收藏星标） =====
            if (!isFullscreen) {
                val curUrl = webViewRef.value?.url
                val favActive = !homeVisible && curUrl != null && curUrl != BLANK_URL &&
                    favorites.any { it.url == curUrl }
                BrowserToolbar(
                    address = addressText,
                    onAddressChange = { addressText = it },
                    onAddressFocusChange = { addressBarFocused = it },
                    onGo = { navigateAddress(addressText) },
                    onBack = { val wv = webViewRef.value; if (wv?.canGoBack() == true) wv.goBack() },
                    onForward = { val wv = webViewRef.value; if (wv?.canGoForward() == true) wv.goForward() },
                    onReload = { reload() },
                    onStop = { webViewRef.value?.stopLoading() },
                    onHome = { goHome() },
                    onFavorite = { toggleFavorite() },
                    onMenu = { floatingMenuRef.value?.openMenu() },
                    isFavorite = favActive,
                    loading = loadProgress.value in 1 until 100
                )
            }

            // ===== 内容区 =====
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        FrameLayoutGameContainer(ctx).apply {
                            mainBoxRef.value = this
                        }
                    },
                    update = { _ ->
                        // ★ v10 修复页面随机重载：AndroidView.update 在每次重组都会执行 ——
                        //   原实现每次执行都 loadUrl(url)，导致开关手柄/鼠标、下载浮层出现、
                        //   进度条更新等任何状态变化都把网页整个重载（游戏中误触开关 = 刷页）。
                        //   初始加载由 LaunchedEffect 完成、刷新由 reloadTrigger effect 走
                        //   WebView.reload()，此处必须保持空操作。
                    }
                )

                // ★ v11: 浏览器主页 = 收藏夹网格（覆盖内容区，about:blank 载体被完全遮住）
                if (!isFullscreen && homeVisible) {
                    BrowserHomeOverlay(
                        favorites = favorites,
                        onNavigate = { entry -> openInBrowser(entry.url, entry.uaMode) },
                        onOpenMenu = { entry -> menuFavGame = entry },
                        onAdd = { showAddFavoriteDialog = true },
                        // ★ v19: 主页搜索（引擎 URL + 关键词）走当前标签导航
                        onSearch = { url -> openInBrowser(url, PrefsManager.uaMode) }
                    )
                }

                // ★ v10: 页面加载进度条（顶部，加载中显示）—— 原实现 onProgress 回调为空，
                //   白屏等待时用户毫无反馈（v11 移入内容区、主页不显示）
                if (!homeVisible && loadProgress.value in 1 until 100) {
                    LinearProgressIndicator(
                        progress = { loadProgress.value / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        color = Color(0xFF8A7BFF),
                        trackColor = Color(0x26FFFFFF)
                    )
                }

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
        }
    }

    // 在容器创建后用 LaunchedEffect 把各 view 加进去
    // ★ v10: renderEpoch 为键 —— 渲染进程死亡后销毁旧 WebView 并自动重建重载
    LaunchedEffect(mainBoxRef.value, renderEpoch.value) {
        val container = mainBoxRef.value ?: return@LaunchedEffect
        // ★ v12: 主标签以 browserTabs 登记（id=0）为准 —— 渲染自愈后旧引用
        //   已被清除，若仍以 webViewRef 判重，活动标签为弹窗时主标签永不重建。
        if (browserTabs.none { it.id == 0 }) {
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

            // 客户端与回调（★ v10: onProgress 接顶部进度条 / 弹窗窗口管理 / 全屏退出统一）
            val chromeCallback = object : GameWebChromeClient.Callback {
                // ★ v12: 进度条只跟随活动标签（后台标签加载不闪进度条）
                override fun onProgress(view: WebView?, progress: Int) {
                    if (view === webViewRef.value) loadProgress.value = progress
                }
                override fun onTitle(view: WebView?, title: String?) {
                    // ★ v12: 标签栏标题按 view 归属各自标签；
                    //   页面标题 → 收藏星标默认名（只认活动标签）
                    findTabOf(view)?.let { tabTitleMap[it.id] = (title ?: "").ifBlank { "网页" } }
                    if (view === webViewRef.value) pageTitle = title ?: ""
                }
                override fun onConsole(level: String, msg: String, sourceId: String?, line: Int) {}
                // WebView 全屏：网页调用 requestFullscreen() 时触发。
                // 把 WebView 内部生成的全屏 view 加到 mainBoxRef 覆盖在 WebView 上层，
                // 让 WebView 内部状态正常推进，避免卡死。
                override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
                    customViewRef.value = view
                    customViewCallbackRef.value = callback
                    // ★ v11: 视频全屏才藏悬浮菜单触发器（customView 会盖住它）。
                    //   工具栏全屏（toggleFullscreen）不再藏 —— v10 全屏后无路可退。菜单隐/显由本回调统一驱动：触发器隐藏（customView 盖住），退出还原
                    floatingMenuRef.value?.isFullscreen = true
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
                    // ★ v11: 视频全屏退出 → 还原悬浮菜单触发器
                    floatingMenuRef.value?.isFullscreen = false
                    exitWebFullscreen()
                }
                override fun onFileChooser(callback: ValueCallback<Array<android.net.Uri>>, accept: String?): Boolean {
                    filePathCallback.value?.onReceiveValue(null)
                    filePathCallback.value = callback
                    val mimes = accept?.split(",")?.toTypedArray() ?: arrayOf("*/*")
                    try { activityResultLauncher.launch(mimes) } catch (e: Exception) { callback.onReceiveValue(null); filePathCallback.value = null }
                    return true
                }
                // ★ v12: window.open / target=_blank → 新标签（原 createPopupWindow）
                override fun handleCreateWindow(opener: WebView?, isDialog: Boolean, resultMsg: android.os.Message): Boolean {
                    return createTabFromPopup(opener, resultMsg)
                }
                // ★ v12: window.close() / 页面自行关闭 → 关闭对应标签
                override fun handleCloseWindow(window: WebView?) {
                    closeTabOf(window)
                }
            }
            // ★ v10: 暴露给 createTabFromPopup（标签复用同一套 Chrome 行为）
            chromeCallbackRef.value = chromeCallback

            val viewClientCallback = object : GameWebViewClient.Callback {
                override fun onPageStarted(view: WebView?, url: String?) {
                    (view as? GameWebView)?.releaseAllKeys()
                    // ★ v12: 缩放域导航重置按各自标签的容器（后台标签互不干扰）
                    findTabOf(view)?.pinch?.resetForNavigation()
                    // ★ v12: 地址栏/主页状态只跟随活动标签（后台标签导航不串台）
                    if (view !== webViewRef.value) return
                    if (url == BLANK_URL) {
                        homeVisible = true
                        if (!addressBarFocused) addressText = ""
                    } else if (!url.isNullOrBlank()) {
                        homeVisible = false
                        if (!addressBarFocused) addressText = url
                    }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    // ★ v11/v12: 持久化手动缩放基线重放 —— 每标签各自容器，
                    //   后台标签载完也保持基线（切回不漂移）
                    if (PrefsManager.pageZoomMode == "manual") {
                        findTabOf(view)?.pinch?.setZoomImmediate(
                            PrefsManager.pageZoomManual.coerceIn(25, 300) / 100f
                        )
                    }
                }
                override fun onProgress(progress: Int) {}
                override fun onError(url: String?, errorCode: Int, description: String?) {
                    if (errorCode == -1) return
                    errorMessage.value = "加载失败: $description"
                }
                override fun onSwfIntercepted(view: WebView?, swfUrl: String, pageUrl: String) {
                    // ★ v12: SWF 在拦截它的那个标签内播放（不串台到活动标签）
                    val target = view ?: webViewRef.value
                    target?.loadUrl(NavHelper.playerUrl(swfUrl, base = pageUrl, title = null))
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
                // ★ v10/v12: 渲染进程死亡自愈（参照 AnWind renderEpoch）—— 不接管则
                //   Chromium 杀整个应用进程。主标签 = renderEpoch 重建重载；
                //   弹窗标签 = 直接关闭（AnWind 同款）。
                override fun onRendererGone(deadView: WebView?) {
                    val tab = findTabOf(deadView)
                    if (tab == null) return
                    if (tab.id == 0) {
                        if (deadView != null) destroyWebViewHolder(deadView)
                        browserTabs.removeAll { it.id == 0 }
                        if (tab.id == activeTabId) webViewRef.value = null
                        renderEpoch.value = renderEpoch.value + 1
                        Toast.makeText(context, "页面渲染进程异常，已自动恢复", Toast.LENGTH_SHORT).show()
                    } else {
                        closeTab(tab.id)
                        Toast.makeText(context, "标签页渲染异常，已自动关闭", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val wvClient = GameWebViewClient(viewClientCallback)
            // ★ v10: 暴露给弹窗创建（弹窗复用同一套 client 行为）
            wvClientRef.value = wvClient

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

            // ★ v11/v12: 双指缩放仲裁容器（WebPinchLayout）承载 WebView ——
            //   页面禁缩放（meta/preventDefault）时容器接管手势，动态改写
            //   viewport meta 强制连续缩放（AnWind 已验证方案）。
            val pinch = WebPinchLayout(container.context).apply {
                onZoomChanged = { z -> if (activeTabId == 0) pinchZoom.value = z }
            }
            pinch.setWebView(wv)
            // ★ v12: 主标签登记 + 活动标签独占附着不变式
            val freshMain = BrowserTabInfo(0, wv, pinch)
            val idx0 = browserTabs.indexOfFirst { it.id == 0 }
            if (idx0 >= 0) browserTabs[idx0] = freshMain else browserTabs.add(0, freshMain)
            if (!tabTitleMap.containsKey(0)) tabTitleMap[0] = if (url == BROWSER_HOME_URL) "主页" else "标签 1"
            if (browserTabs.none { it.id == activeTabId }) activeTabId = 0
            browserTabs.forEach { t ->
                if (t.id == activeTabId) attachTab(t)
                else if (t.pinch.parent === container) container.removeView(t.pinch)
            }
            webViewRef.value = activeTab()?.webView

            // 初始加载（★ v11: 浏览器主页入口 = about:blank + 主页收藏夹网格）
            val isHome = url == BROWSER_HOME_URL
            val isSwf = NavHelper.isSwf(url) || NavHelper.isLocalFile(url)
            when {
                isHome -> {
                    homeVisible = true
                    wv.loadUrl(BLANK_URL)
                }
                isSwf -> {
                    val playerUrl = NavHelper.playerUrl(url, base = null, title = null)
                    wv.loadUrl(playerUrl)
                }
                url.contains("4399.com") -> wv.loadUrl(url, mapOf("Referer" to "https://www.4399.com/"))
                else -> wv.loadUrl(url)
            }
        }
    }

    // 创建手柄与菜单
    LaunchedEffect(mainBoxRef.value, gamepadVisible, isMouseEnabled, gamepadRebuildTrigger.value) {
        val container = mainBoxRef.value ?: return@LaunchedEffect
        val density = container.resources.displayMetrics.density

        // 清掉旧手柄控件
        listOfNotNull(
            dpadRef.value, stickRef.value, actionRef.value, mouseRef.value,
            systemButtonsRef.value, floatingMenuRef.value
        ).forEach { container.removeView(it) }

        // ★ v17: 重建时清一次页面按键状态 —— 控件被移除瞬间若仍有按住的方向/
        //   动作键，页面端会残留 keydown（角色持续移动）。releaseAllKeys 幂等，
        //   与 WebStickView.onDetachedFromWindow 兜底构成双保险。
        try { webViewRef.value?.releaseAllKeys() } catch (_: Exception) { }

        // ★ v17: 方向键模式 = stick_wasd（摇杆+WASD）时，十字键不再上屏，
        //   由 WebStickView 摇杆取代（底部同一位置）。DPadView 仍构造（引用
        //   链/切换标签同步保持原状），只是不 addView。
        val useStickMode = PrefsManager.dpadMode == "stick_wasd"

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
        if (gamepadVisible && !useStickMode) container.addView(dpad, dpadLp)

        // ★ v17: 创建模拟摇杆（WebStickView，仿原生摇杆手感：斜向 = 双键并按，
        //   摇杆外环四向标注当前映射键名，方向键大小设置同 dpad_scale 生效）
        val stick = WebStickView(container.context).apply {
            targetWebView = webViewRef.value
            // ★ v17.1: gamepadAlpha（Int，隐藏 PrefsManager）取值语义无法从可见代码确认
            //   （0-255 Paint alpha 或 0-100 百分比），按数值自适应换算，两种语义下均
            //   不失真：>100 必为 0-255 语义 → /255f；≤100 按 /100f（0-100 语义全档
            //   精确；0-255 语义低透明档下摇杆偏亮但永不消失，优于固定单边猜测）。
            overlayAlpha = if (PrefsManager.gamepadAlpha > 100) {
                PrefsManager.gamepadAlpha / 255f
            } else {
                PrefsManager.gamepadAlpha / 100f
            }
            isDragMode = isPositionEditMode
        }
        stickRef.value = stick
        val stickSize = (140 * density * PrefsManager.dpadScale).toInt()
        val stickLp = android.widget.FrameLayout.LayoutParams(stickSize, stickSize).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            bottomMargin = (24 * density).toInt()
            marginStart = (16 * density).toInt()
        }
        if (gamepadVisible && useStickMode) container.addView(stick, stickLp)

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
                // 悬浮菜单"返回"：★ v11 与系统返回键同链（网页后退 → 回主页 →
                //   已在主页提示）。不直接退出 —— 退出用"关闭"按钮，防误触。
                if (!browserBack()) {
                    Toast.makeText(context, "已在主页，点\"关闭\"退出浏览器", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onClose() = onExit()
            override fun onExtractSwf() = extractSwfFromPage()
            override fun onToggleCameraRotation() = toggleCameraRotation()
            override fun onOpenAspectRatio() { showAspectRatioDialog.value = true }
            // ★ 菜单优化版：悬浮球菜单「主页」—— 直达浏览器主页（收藏夹网格）。
            //   与工具栏主页按钮同一 goHome() 通道；已在主页时轻提示不重复跳转。
            override fun onGoHome() {
                if (homeVisible) {
                    Toast.makeText(context, "已在主页", Toast.LENGTH_SHORT).show()
                } else {
                    goHome()
                }
            }
        })
        // ★ v11: 菜单触发器不再随工具栏全屏隐藏（v10 全屏后无路可退）；
        //   仅视频全屏（customView 盖住触发器）时由 onShow/onHideFullscreen 驱动隐藏。
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

    // 释放资源 / 屏幕常亮 / ★ v12 刘海沉浸会话
    DisposableEffect(Unit) {
        // ★ v10: 游戏期间屏幕常亮
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // ★ v12: 浏览器会话 = 占用刘海屏 + 沉浸式（参照 AnWind SHORT_EDGES）。
        //   v11 未设 cutout mode 且跟随系统 decorFits —— 刘海/挖孔区域整条
        //   黑边（Redmi K60 Pro 横屏挖孔在侧边尤明显）。进入浏览器后：
        //   ① layoutInDisplayCutoutMode = SHORT_EDGES（内容延伸绘制进挖孔区）；
        //   ② decorFits=false + 系统栏隐藏（WebView 真正边到边）。
        //   退出时逐项还原（进入时系统栏本来就可见才还原显示，防把别的
        //   界面原本的沉浸状态改掉）。
        val w = activity?.window
        var oldCutout = -1
        var barsWereVisible = true
        if (w != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                oldCutout = w.attributes.layoutInDisplayCutoutMode
                w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            // ★ v12.1: 修复 CI 1249 —— WindowCompat 并没有 getRootWindowInsets
            //   （v12 误用导致 Unresolved reference），正确 API 是
            //   androidx.core.view.ViewCompat.getRootWindowInsets(View): WindowInsetsCompat?
            barsWereVisible = androidx.core.view.ViewCompat.getRootWindowInsets(w.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars()) ?: true
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).hide(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
            )
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // 视频全屏兜底退出（customView 挂在容器上，销毁前先收）
            if (customViewRef.value != null) exitWebFullscreen()
            // 还原窗口：cutout mode / 系统栏 / decorFits
            if (w != null) {
                if (oldCutout >= 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = oldCutout }
                }
                if (barsWereVisible) {
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, true)
                    androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).show(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    )
                }
            }
            // ★ v12: 全部标签销毁链（先 JS 静音再 destroy，参照 AnWind 防"关了还在响"；
            //   各标签缩放容器连同剥离）
            browserTabs.forEach { tab ->
                muteWebView(tab.webView)
                (tab.pinch.parent as? ViewGroup)?.removeView(tab.pinch)
                tab.pinch.detachWebView()
                try { tab.webView.destroy() } catch (_: Exception) {}
            }
            browserTabs.clear()
            tabTitleMap.clear()
            webViewRef.value = null
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
        // ★ 新增「全屏拉伸 (stretch)」：exactFit + letterbox off 无黑边铺满 ——
        //   看图类 SWF（自管 Stage.scaleMode、比例定死）唯一有效的拉伸方式。
        //   正在播放内置播放页时选择即自动重载生效（见 applyAspectRatio）。
        val ratios = arrayOf("全屏拉伸 (无黑边)", "全屏自适应 (auto)", "4:3", "16:9", "16:10", "5:4")
        val values = arrayOf("stretch", "auto", "4:3", "16:9", "16:10", "5:4")
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

    // ========== ★ v11: 收藏夹管理（主页长按 / 添加 / 重命名 / 图标） ==========
    menuFavGame?.let { fav ->
        val favName = fav.customTitle?.takeIf { it.isNotBlank() } ?: fav.title
        android.app.AlertDialog.Builder(context)
            .setTitle(favName)
            .setItems(arrayOf("打开", "重命名", "自定义图标", "删除收藏")) { dlg, which ->
                when (which) {
                    0 -> openInBrowser(fav.url, fav.uaMode)
                    1 -> pendingFavRename = fav
                    2 -> {
                        // 选图 → favIconPicker 回调拷贝进 filesDir/icons 并写库
                        pendingFavIcon = fav
                        try {
                            favIconPicker.launch(arrayOf("image/*"))
                        } catch (e: Exception) {
                            pendingFavIcon = null
                            Toast.makeText(context, "无法打开图片选择器", Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> {
                        WebFavoritesStore.remove(context, fav.url)
                        favorites = WebFavoritesStore.loadAll(context)
                        Toast.makeText(context, "已删除收藏：$favName", Toast.LENGTH_SHORT).show()
                    }
                }
                dlg.dismiss()
            }
            .setOnDismissListener { menuFavGame = null }
            .show()
    }

    if (showAddFavoriteDialog) {
        AddFavoriteDialog(
            onConfirm = { t, u, ua ->
                if (WebFavoritesStore.add(context, t, u, ua)) {
                    favorites = WebFavoritesStore.loadAll(context)
                    Toast.makeText(context, "已添加收藏", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "网址不能为空", Toast.LENGTH_SHORT).show()
                }
                showAddFavoriteDialog = false
            },
            onDismiss = { showAddFavoriteDialog = false }
        )
    }

    pendingFavRename?.let { fav ->
        RenameFavoriteDialog(
            initialTitle = fav.customTitle?.takeIf { it.isNotBlank() } ?: fav.title,
            onConfirm = { newTitle ->
                WebFavoritesStore.setCustomTitle(context, fav.url, newTitle)
                favorites = WebFavoritesStore.loadAll(context)
                pendingFavRename = null
            },
            onDismiss = { pendingFavRename = null }
        )
    }
}

/** FrameLayout 子类，作为手柄 + WebView + 菜单的承载容器 */
@SuppressLint("ViewConstructor")
private class FrameLayoutGameContainer(context: Context) : android.widget.FrameLayout(context)

/**
 * ★ v12: 浏览器标签 —— 每标签独占常驻 WebView + 缩放仲裁容器（AnWind BrowserTab 同构：
 * 切换只 attach/detach 不销毁重建，切回即恢复；isPopup 标记 window.open 出身的标签，
 * 后退过栈底时按 Chrome 语义关闭回 opener）。
 */
private class BrowserTabInfo(
    val id: Int,
    val webView: GameWebView,
    val pinch: WebPinchLayout,
    val isPopup: Boolean = false
)

@Composable
private fun FlashEngineDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // ★ 引擎选择 + 悬浮菜单排列（用户反馈「菜单不能一直横着放」）：
    //   排列偏好写入 floating_menu_layout，FloatingMenuView.showMenu 读取。
    val engines = arrayOf("Ruffle (推荐)", "WAFlash", "关闭 Flash", "悬浮菜单排列…")
    val values = arrayOf("ruffle", "waflash", "off", "__menu_layout__")
    val current = if (PrefsManager.isFlashEnabled) PrefsManager.flashEngine else "off"
    val checked = values.indexOf(current).coerceAtLeast(0)
    android.app.AlertDialog.Builder(context)
        .setTitle("Flash 引擎")
        .setSingleChoiceItems(engines, checked) { dlg, which ->
            if (values[which] == "__menu_layout__") {
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

    // ---------- ★ v19: 分区设置面板（v17 的 0-12 平铺列表重排，用户反馈"非常乱"） ----------
    // 13 项操作全部保留，按【方向控制 / 动作按键 / 功能键 / 布局与其他】四区归组，
    // 行尾显示当前值（黄色），恢复默认移到底部按钮。

    fun showKeyRemap() {
        // 按键映射（选择按键修改）
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

    fun showStartSelect() {
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

    fun showDpadMode() {
        // ★ v19: 摇杆 WASD 提到第一位并加说明（此前藏在第 4 项不显眼）；
        //   4 种模式语义全部保留（stick_wasd/dpad/wasd/joystick）。
        val modes = arrayOf(
            "摇杆 WASD（推荐 · 可拖动 · 斜向双键 · 四向可改键）",
            "十字键（方向箭头）",
            "十字键 WASD（十字键发出 W/A/S/D）",
            "原生摇杆（旧版摇杆布局）"
        )
        val values = arrayOf("stick_wasd", "dpad", "wasd", "joystick")
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

    fun showStickMap() {
        // 摇杆 WASD 映射（上/下/左/右四向键位，默认 W/S/A/D）
        val dirNames = arrayOf("上", "下", "左", "右")
        val dirPrefKeys = arrayOf("web_stick_key_up", "web_stick_key_down", "web_stick_key_left", "web_stick_key_right")
        val dirDefaults = arrayOf("W", "S", "A", "D")
        val dirKeys = dirPrefKeys.mapIndexed { i, k -> PrefsManager.sp.getString(k, dirDefaults[i]) ?: dirDefaults[i] }
        android.app.AlertDialog.Builder(context)
            .setTitle("摇杆 WASD 映射")
            // 注意：模板里变量名后紧跟 CJK 字符必须用显式花括号（v18 编译事故教训）
            .setItems(dirNames.mapIndexed { i, d -> "${d}方向 (当前: ${dirKeys[i]})" }.toTypedArray()) { _, idx ->
                android.app.AlertDialog.Builder(context)
                    .setTitle("摇杆「${dirNames[idx]}」方向映射")
                    .setSingleChoiceItems(keys, keys.indexOf(dirKeys[idx]).coerceAtLeast(0)) { d2, w2 ->
                        PrefsManager.sp.edit().putString(dirPrefKeys[idx], keys[w2]).apply()
                        d2.dismiss()
                        onReload()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showDpadSize() {
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

    fun showActionSize() {
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

    fun showKeyVisibility() {
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

    fun restoreDefaults() {
        // 恢复默认（★ v17 增摇杆四向键位与位置重置，v19 迁移为底部按钮）
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
            .putString("web_stick_key_up", "W")
            .putString("web_stick_key_down", "S")
            .putString("web_stick_key_left", "A")
            .putString("web_stick_key_right", "D")
            .putFloat("web_stick_pos_x", -1f)
            .putFloat("web_stick_pos_y", -1f)
            .apply()
        onReload()
    }

    fun dpadModeLabel(mode: String): String = when (mode) {
        "dpad" -> "十字键"
        "wasd" -> "十字键 WASD"
        "stick_wasd" -> "摇杆 WASD"
        else -> "原生摇杆"
    }

    // ---------- 自绘分区面板（android.widget，深色底 + 紫罗兰分区头 + 黄色当前值） ----------
    val density = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    val rippleId = android.util.TypedValue().let { tv ->
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        tv.resourceId
    }
    val list = android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setBackgroundColor(android.graphics.Color.parseColor("#10121C"))
    }
    val scroll = android.widget.ScrollView(context).apply { addView(list) }

    fun header(title: String) {
        list.addView(android.widget.TextView(context).apply {
            // 注意：参数不可叫 text —— apply 内 $text 会解析到 TextView 的同名属性
            text = "  $title"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#8A7BFF"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(12), dp(4), dp(2))
        }, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    fun row(title: String, value: String, onClick: () -> Unit) {
        val rowLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(rippleId)
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        val titleView = android.widget.TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
        }
        val valueView = android.widget.TextView(context).apply {
            text = value
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#FDD835"))
        }
        rowLayout.addView(titleView, android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        rowLayout.addView(valueView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        rowLayout.setOnClickListener { onClick() }
        list.addView(rowLayout, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    header("方向控制")
    row("方向键模式", dpadModeLabel(PrefsManager.dpadMode)) { showDpadMode() }
    row("摇杆按键映射", "四向可改 (W/S/A/D)") { showStickMap() }
    row("方向键大小", "${(PrefsManager.dpadScale * 100).toInt()}%") { showDpadSize() }

    header("动作按键（共 $keyCount 个）")
    row("按键映射", "选择按键修改") { showKeyRemap() }
    row("添加按键", "最多 18 个") {
        val c = PrefsManager.gamepadKeyCount
        if (c < 18) PrefsManager.sp.edit().putInt("gamepad_key_count", c + 1).apply()
        onReload()
    }
    row("删除按键", "至少保留 2 个") {
        val c = PrefsManager.gamepadKeyCount
        if (c > 2) PrefsManager.sp.edit().putInt("gamepad_key_count", c - 1).apply()
        onReload()
    }
    row("动作按键大小", "${(PrefsManager.gamepadScale * 100).toInt()}%") { showActionSize() }

    header("功能键")
    row("Start / Select", "当前 ${PrefsManager.startKey} / ${PrefsManager.selectKey}") { showStartSelect() }

    header("布局与其他")
    row("显示/隐藏按键", "勾选要显示的按键") { showKeyVisibility() }
    row("鼠标按钮", if (PrefsManager.sp.getBoolean("mouse_buttons_visible", false)) "已显示" else "已隐藏") { onToggleMouseButtons() }
    row("位置编辑模式", if (isPositionEditMode) "已开启 · 拖动调整" else "已关闭") { onTogglePositionEdit() }
    row("视角旋转", "3D 游戏") { onToggleCameraRotation() }

    android.app.AlertDialog.Builder(context)
        .setTitle("按键设置")
        .setView(scroll)
        .setPositiveButton("恢复默认") { _, _ -> restoreDefaults() }
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

// ======================================================================
// ★ v11: 浏览器工具栏 / 收藏夹主页 / 收藏管理对话框
// ======================================================================

/** 收藏卡片循环取用的强调色（旧在线游戏列表同款色板） */
private val FavAccentPalette = listOf(
    Color(0xFF8A7BFF), Color(0xFFE74C3C), Color(0xFF27AE60), Color(0xFF3498DB),
    Color(0xFFE67E22), Color(0xFF1ABC9C), Color(0xFF9B59B6), Color(0xFFF1C40F),
    Color(0xFFE84393), Color(0xFF00CEC9), Color(0xFF6C5CE7), Color(0xFFFDCB6E)
)

/**
 * 浏览器工具栏：后退 / 前进 / 刷新停止 / 主页 | 地址栏（回车前往）| 收藏星标 | ☰菜单。
 * 全屏（isFullscreen）时不组合 —— 纯净游戏视图；菜单按钮是主页等覆盖层场景
 * 下唯一能打开悬浮菜单的入口（触发器会被覆盖层盖住）。
 */
@Composable
private fun BrowserToolbar(
    address: String,
    onAddressChange: (String) -> Unit,
    onAddressFocusChange: (Boolean) -> Unit,
    onGo: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onHome: () -> Unit,
    onFavorite: () -> Unit,
    onMenu: () -> Unit,
    isFavorite: Boolean,
    loading: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xF0161822))
            .padding(horizontal = 1.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Rounded.ArrowBack, "后退", tint = Color.White, modifier = Modifier.size(14.dp))
        }
        IconButton(onClick = onForward, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Rounded.ArrowForward, "前进", tint = Color.White, modifier = Modifier.size(14.dp))
        }
        if (loading) {
            IconButton(onClick = onStop, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Rounded.Close, "停止", tint = Color(0xFFFF8A80), modifier = Modifier.size(14.dp))
            }
        } else {
            IconButton(onClick = onReload, modifier = Modifier.size(26.dp)) {
                Icon(Icons.Rounded.Refresh, "刷新", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        IconButton(onClick = onHome, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Rounded.Home, "主页", tint = Color.White, modifier = Modifier.size(14.dp))
        }
        // ★ v17 修复：网址文本不显示 —— v14 用 M3 OutlinedTextField 强制定高 32dp，
        //   其内部装饰布局按 56dp 最小高度设计（label/内边距占位后文本被垂直裁出可视区），
        //   网址怎么都看不见。改用 BasicTextField 自绘紧凑容器：文本在 32dp 内垂直居中，
        //   尺寸观感与 v14 一致，文本保证可见。
        var urlFieldFocused by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(Color(0x14FFFFFF))
                .border(
                    width = 1.dp,
                    color = if (urlFieldFocused) Color(0xFF8A7BFF) else Color(0x33FFFFFF),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 8.dp)
        ) {
            Icon(Icons.Rounded.Search, null, tint = Color(0xFF8899AA), modifier = Modifier.size(12.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // 占位提示（仅空值时可见）
                if (address.isEmpty()) {
                    Text(
                        "搜索或输入网址",
                        color = Color(0xFF8899AA),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = address,
                    onValueChange = onAddressChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged {
                            urlFieldFocused = it.isFocused
                            onAddressFocusChange(it.isFocused)
                        },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 11.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF8A7BFF)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onGo() })
                )
            }
        }
        IconButton(onClick = onFavorite, modifier = Modifier.size(26.dp)) {
            Icon(
                if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                "收藏",
                tint = if (isFavorite) Color(0xFFFFD54F) else Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
        // ☰ 菜单：打开悬浮菜单（手柄/Flash/缩放/兼容模式等全部设置入口）
        IconButton(onClick = onMenu, modifier = Modifier.size(26.dp)) {
            Icon(Icons.Rounded.MoreVert, "菜单", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

// ======================================================================
// ★ v12: 标签栏（AnWind TabBar 手机化 —— 横滑 chip 列表 + 新建按钮；
// ≥2 个标签时显示，单标签省一排空间不打扰游戏）
// ======================================================================

@Composable
private fun BrowserTabStrip(
    tabs: List<BrowserTabInfo>,
    activeTabId: Int,
    titles: Map<Int, String>,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onNew: () -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xF0101218))
            .padding(horizontal = 1.dp, vertical = 1.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
    ) {
        items(tabs, key = { it.id }) { tab ->
            val title = titles[tab.id].orEmpty().ifBlank { "标签" }
            val isActive = tab.id == activeTabId
            Row(
                modifier = Modifier
                    .height(22.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(if (isActive) Color(0xFF232736) else Color(0x1AFFFFFF))
                    .clickable { onSelect(tab.id) }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title.take(12),
                    color = if (isActive) Color.White else Color(0xFF8899AA),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.width(60.dp)
                )
                Icon(
                    Icons.Rounded.Close, "关闭标签",
                    tint = Color(0xFF8899AA),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(11.dp)
                        .clickable { onClose(tab.id) }
                )
            }
        }
        item {
            IconButton(onClick = onNew, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Rounded.Add, "新建标签", tint = Color(0xFF8A7BFF), modifier = Modifier.size(12.dp))
            }
        }
    }
}

// ---------- ★ v13: 主页背景预设（自定义背景 = 相册图片 / 内置渐变 / 默认深色） ----------
private val BgPresetNames = listOf("星夜紫", "深海蓝", "熔金落日", "森林绿", "石墨灰")

private val BgPresetGradients = listOf(
    listOf(Color(0xFF1A1B3A), Color(0xFF4F46E5).copy(alpha = 0.38f), Color(0xFF0F1015)),
    listOf(Color(0xFF06283D), Color(0xFF1363DF).copy(alpha = 0.38f), Color(0xFF021019)),
    listOf(Color(0xFF3B1F2B), Color(0xFFE56718).copy(alpha = 0.30f), Color(0xFF14090D)),
    listOf(Color(0xFF0E2A1F), Color(0xFF1B7A4B).copy(alpha = 0.32f), Color(0xFF06130C)),
    listOf(Color(0xFF17181C), Color(0xFF3A3F4B).copy(alpha = 0.42f), Color(0xFF0B0C0F))
)

/** ★ v19: 主页搜索引擎条目（id 持久化键 / 显示名 / 徽标字符 / 搜索 URL 前缀） */
private data class SearchEngine(val id: String, val label: String, val glyph: String, val urlPrefix: String)

/**
 * ★ v13: 浏览器主页现代化 —— 主页背景可自定义（相册图片 / 内置渐变预设 /
 *   默认深色，顶栏齿轮入口）。
 *   ★ v14: 卡片紧凑化（64x78 小卡，单行 LazyRow 轮播）。
 *   ★ v17: 排列仿 AnWind 桌面网格（图标在上、标签在下，纵向填列）。
 *   ★ v19: 按用户截图（jianfast 式搜索主页）重排 —— 中央白底搜索框 +
 *   引擎徽标（百度/Google/必应/搜狗/360，点击切换，选择持久化）+ 引擎
 *   快捷切换行 + 4 列快捷方式网格（图标上/标签下，竖向滚动）；顶栏"背景"
 *   文字胶囊换设置齿轮图标。语义不变：点击收藏打开站点；长按弹管理菜单；
 *   尾部"+"图标添加收藏；搜索 = 当前引擎 + 关键词在当前标签导航。
 *   覆盖整个内容区 —— 底层 about:blank 载体完全不可见。
 */
@Composable
private fun BrowserHomeOverlay(
    favorites: List<WebGameEntry>,
    onNavigate: (WebGameEntry) -> Unit,
    onOpenMenu: (WebGameEntry) -> Unit,
    onAdd: () -> Unit,
    onSearch: (String) -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 背景偏好（SharedPreferences 持久化；对话框操作后本地 state 即时刷新）
    var bgMode by remember { mutableStateOf(WebFavoritesStore.getBackground(ctx).first) }
    var bgPreset by remember { mutableStateOf(WebFavoritesStore.getBackground(ctx).second) }
    var bgImagePath by remember { mutableStateOf(WebFavoritesStore.getBackgroundImagePath(ctx)) }
    var showBgDialog by remember { mutableStateOf(false) }

    // SAF 选图 → 降采样落盘 <filesDir>/web_bg/custom_bg.jpg（应用私有、稳定可读）
    val bgPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val saved = WebFavoritesStore.applyBackgroundImage(ctx, uri)
            if (saved != null) {
                bgMode = "image"
                bgImagePath = saved
            } else {
                Toast.makeText(ctx, "导入背景图片失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 背景位图（图片模式）—— remember 缓存，随路径变化重建
    val bgBitmap = remember(bgImagePath) {
        if (bgImagePath.isNullOrBlank()) null
        else try {
            if (java.io.File(bgImagePath).exists()) android.graphics.BitmapFactory.decodeFile(bgImagePath) else null
        } catch (_: Exception) { null }
    }

    // 展示层去重（网格 key 需 url 唯一，防异常种子数据导致重复 key）
    val shownFavorites = remember(favorites) { favorites.distinctBy { it.url } }

    // ---- ★ v24: 主页双指缩放（用户反馈"第一个页面无法双指缩放"）----
    //   主页是 Compose 覆盖层（nesstation://home，非网页），WebView 的缩放
    //   管不到这里 —— 在覆盖层自实现捏合缩放：
    //   - 手势采集在 Initial 指针阶段且仅在 ≥2 指时消费 —— 单指点击/滚动
    //     不受影响，双指捏合时子元素（网格滚动/点击）不会误触发；
    //   - 缩放钳制 0.6x..2.4x，中心锚点；持久化 web_home_zoom；
    //   - 非 1x 时右下角出现百分比胶囊，点击复位。
    var homeZoom by remember {
        mutableStateOf(
            (PrefsManager.sp.getFloat("web_home_zoom", 1f)).coerceIn(0.6f, 2.4f))
    }

    // ---- ★ v19: 搜索引擎（仿图中主页：中央搜索框 + 引擎切换） ----
    val searchEngines = remember {
        listOf(
            SearchEngine("baidu", "百度", "百", "https://www.baidu.com/s?wd="),
            SearchEngine("google", "Google", "G", "https://www.google.com/search?q="),
            SearchEngine("bing", "必应", "必", "https://www.bing.com/search?q="),
            SearchEngine("sogou", "搜狗", "搜", "https://www.sogou.com/web?query="),
            SearchEngine("so360", "360", "3", "https://www.so.com/s?q=")
        )
    }
    var engineId by remember { mutableStateOf(PrefsManager.sp.getString("web_search_engine", "baidu") ?: "baidu") }
    val engineGlyph = searchEngines.firstOrNull { it.id == engineId }?.glyph ?: "百"
    var query by remember { mutableStateOf("") }
    var showEngineDialog by remember { mutableStateOf(false) }
    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        val prefix = searchEngines.firstOrNull { it.id == engineId }?.urlPrefix
            ?: "https://www.baidu.com/s?wd="
        onSearch(prefix + java.net.URLEncoder.encode(q, "UTF-8"))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBackground)
            .clipToBounds()
            // ★ v24: 双指捏合缩放 —— Initial 阶段仅拦 ≥2 指；单指手势
            //   原样放行给子元素（网格滚动/点击/输入完全不受影响）。
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var prevSpan = -1f
                    var pinched = false
                    while (true) {
                        val event =
                            awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            pinched = true
                            val span = (pressed[0].position - pressed[1].position)
                                .getDistance()
                            if (prevSpan > 0f && span > 1f) {
                                val factor = span / prevSpan
                                if (factor != 1f) {
                                    homeZoom = (homeZoom * factor)
                                        .coerceIn(0.6f, 2.4f)
                                }
                            }
                            prevSpan = span
                            // 捏合期间消费掉，防止网格滚动/误点击
                            event.changes.forEach { it.consume() }
                        } else {
                            prevSpan = -1f
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                    if (pinched) {
                        PrefsManager.sp.edit()
                            .putFloat("web_home_zoom", homeZoom).apply()
                    }
                }
            }
    ) {
        // ---- 背景层：自定义图片 / 预设渐变（默认 = PrimaryBackground 深色底） ----
        when (bgMode) {
            "image" -> if (bgBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bgBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
            "preset" -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            BgPresetGradients[bgPreset.coerceIn(0, BgPresetGradients.lastIndex)]
                        )
                    )
            )
        }
        // ---- 压暗纱：任何背景下都保证前景文字/卡片可读 ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0x2E000000), Color(0x66000000))
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                // ★ v24: 缩放作用于内容层（背景层保持全幅不缩放）
                .graphicsLayer {
                    scaleX = homeZoom
                    scaleY = homeZoom
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
        ) {
            // ★ v22: 仿图二「简洁主页」紧凑化 —— 顶栏/搜索框/引擎行/图标全元素缩小
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
            // ---- 顶栏：标题区 + ★ v19 设置入口（原"背景"文字胶囊 → 齿轮图标） ----
            // ★ 主页优化版：限宽 680dp 居中 —— 横屏/平板上顶栏不再拉满整屏宽度，
            //   与下方限宽的搜索区/收藏网格对齐成统一内容列，视觉更聚焦。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "在线游戏",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.size(2.dp))
                    Text(
                        "共 ${shownFavorites.size} 个收藏 · 点击打开 · 长按管理 · 工具栏★收藏当前页",
                        color = Color(0xFFAAB6C6),
                        fontSize = 8.sp
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                // ★ v19: 齿轮设置图标（同一点击行为 = 打开背景/主页设置）
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(Color(0x2EFFFFFF))
                        .clickable { showBgDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Settings, null, tint = Color(0xE6FFFFFF), modifier = Modifier.size(16.dp))
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            // ---- ★ v19: 中央搜索框（仿图中主页：白底圆角长条 + 引擎徽标 + 搜索按钮） ----
            //   ★ v22: 46dp → 38dp 紧凑瘦身（用户反馈对比图二"差的太多，都缩小点"）
            //   ★ 主页优化版：限宽 520dp 居中。
            //   ★★ v24（用户反馈"搜索框太长"）：旧 fillMaxWidth()+widthIn(520dp) 在
            //      竖屏（内容宽 ~360-412dp）限宽完全不生效 → 搜索条几乎占满全屏宽。
            //      改为 fillMaxWidth(0.82f) + 上限 380dp：竖屏 ~295-338dp 明显收短，
            //      横屏/平板最多 380dp，与引擎行/收藏网格同轴对齐。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .widthIn(max = 380.dp)
                    .align(Alignment.CenterHorizontally)
                    .height(38.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(Color(0xF2FFFFFF))
                    .padding(horizontal = 4.dp)
            ) {
                // 引擎徽标（点击弹出引擎选择列表）
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(Color(0xFF8A7BFF), Color(0xFF6C5CE7))
                            )
                        )
                        .clickable { showEngineDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        engineGlyph,
                        color = Color.White,
                        fontSize = if (engineGlyph.length > 1) 9.sp else 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                // 关键词输入（BasicTextField 紧凑容器，与地址栏同款手法）
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color(0xFF20232F),
                            fontSize = 13.sp
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF8A7BFF)),
                        keyboardOptions = KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (query.isEmpty()) {
                        Text(
                            "输入关键词，搜索整个网络",
                            color = Color(0xFF98A0B3),
                            fontSize = 12.sp
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                // 搜索按钮
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(Color(0xFF8A7BFF))
                        .clickable { doSearch() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
            // ---- 引擎快捷切换行（仿图中引擎列表：当前引擎高亮，其余半透明白） ----
            //   ★ 主页优化版：与搜索框同宽限宽居中，两者对齐成一体。
            //   ★★ v24：同步搜索框新口径（0.82f + 380dp 上限），保持同轴对齐。
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .widthIn(max = 380.dp)
                    .align(Alignment.CenterHorizontally)
            ) {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                searchEngines.forEach { eng ->
                    Box(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                            .background(if (eng.id == engineId) Color(0xFF8A7BFF) else Color(0x3DFFFFFF))
                            .clickable {
                                engineId = eng.id
                                PrefsManager.sp.edit().putString("web_search_engine", eng.id).apply()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            eng.label,
                            color = Color.White,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            // ---- ★ 主页优化版：「收藏站点」分区标题（小标签 + 发丝分隔线 + 计数） ----
            //   原顶栏一行小字塞了全部提示信息，结构不清晰；拆出分区标题后
            //   主页层级 = 标题栏 → 搜索区 → 收藏网格，排列一目了然。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    "收藏站点",
                    color = Color(0xFFDDE4F0),
                    fontSize = 9.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(Color(0x26FFFFFF))
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                Text(
                    "${shownFavorites.size} 个",
                    color = Color(0xFF8FA2B8),
                    fontSize = 8.sp
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
            // ---- ★ v19: 快捷方式网格（仿图中主页：图标上/标签下，4 列横排） ----
            //   复用 v17 DesktopShortcut 单元（白字描边阴影，任意壁纸可读），
            //   LazyVerticalGrid 行优先流动 + 竖向滚动，取代 v17 纵向填列桌面排布
            //   （用户反馈"太难看"，按图中搜索主页样式重排）。
            //   ★ v21: Fixed(4) → Adaptive(80dp) —— 横屏下 4 列均分宽屏会把内容
            //   摊成稀疏大方格（用户反馈"横屏时间隔非常大"），改自适应列数后
            //   竖屏 4 列 / 横屏自动 7-9 列紧凑排布，同图中导航站样式。
            //   ★ v22: Adaptive(80→60dp) + 瓦片 44→40dp + 标签 9→8sp —— 仿图二
            //   「简洁主页」小图标多列紧凑观感（竖屏 5-6 列，间距均匀不空旷）。
            //   ★ 主页优化版：网格限宽 640dp 居中 —— 横屏/平板上收藏图标不再
            //      摊满整屏宽度，与搜索区同轴对齐；单元密度维持 v22 紧凑口径。
            //   ★★ v24（用户反馈"收藏夹的排列也太长"）：旧 weight(1f) 让网格吃掉
            //      全部剩余高度（竖屏 12 个种子收藏铺满下半屏）；改为
            //      heightIn(max=184dp)（约 3 行）+ 列更密（Adaptive 60→56dp）+
            //      行距 8→6dp —— 网格自身不再掉长屏，超出部分在小区内滚动；
            //      底部提示行由 Spacer(weight(1f)) 压到屏幕底部，中部留白显壁纸。
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 56.dp),
                modifier = Modifier
                    .heightIn(max = 184.dp)
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
            ) {
                // ★ v20: gridItems 是 LazyGridScope 的 List 扩展（同 2333 行标签栏 items 用法），
                //   原误传数量 Int 导致 CI 六连错（重载不匹配→lambda 类型不可推断→Composable 上下文丢失）。
                gridItems(shownFavorites, key = { it.url }) { fav ->
                    DesktopShortcut(
                        fav = fav,
                        onClick = { onNavigate(fav) },
                        onLongClick = { onOpenMenu(fav) }
                    )
                }
                item(key = "add_shortcut") {
                    DesktopAddShortcut(onClick = onAdd)
                }
            }
            // ★ v24: 网格限高后中段留白显壁纸，底部提示行压到屏幕底部
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Text(
                "双指缩放 · 点击打开 · 长按管理 · 齿轮设置背景",
                color = Color(0xFF8FA2B8),
                fontSize = 8.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 6.dp)
            )
        }

        // ★ v24: 非 1x 时右下角缩放状态胶囊（固定尺寸，不随内容缩放）
        if (homeZoom != 1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(Color(0xCC1E2A3A))
                    .clickable {
                        homeZoom = 1f
                        PrefsManager.sp.edit().putFloat("web_home_zoom", 1f).apply()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "缩放 ${kotlin.math.round(homeZoom * 100).toInt()}% · 点此复位",
                    color = Color(0xFFFDD835), fontSize = 11.sp
                )
            }
        }
    }

    // ---- ★ v19: 搜索引擎选择对话框（仿图中引擎下拉列表，m3 同款安全弹窗） ----
    if (showEngineDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showEngineDialog = false },
            title = { Text("选择搜索引擎") },
            text = {
                Column {
                    searchEngines.forEach { eng ->
                        androidx.compose.material3.TextButton(
                            onClick = {
                                engineId = eng.id
                                PrefsManager.sp.edit().putString("web_search_engine", eng.id).apply()
                                showEngineDialog = false
                            }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                                        .background(
                                            androidx.compose.ui.graphics.Brush.linearGradient(
                                                listOf(Color(0xFF8A7BFF), Color(0xFF6C5CE7))
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        eng.glyph,
                                        color = Color.White,
                                        fontSize = if (eng.glyph.length > 1) 10.sp else 12.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                }
                                androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                                Text(
                                    eng.label,
                                    color = if (eng.id == engineId) Color(0xFF8A7BFF) else Color(0xFFC7D0DE),
                                    fontSize = 14.sp
                                )
                                if (eng.id == engineId) {
                                    androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                                    Text("✓", color = Color(0xFF8A7BFF), fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showEngineDialog = false }) { Text("取消") }
            },
            dismissButton = {}
        )
    }


    // ---- 背景选择对话框 ----
    if (showBgDialog) {
        BackgroundChooserDialog(
            currentMode = bgMode,
            currentPreset = bgPreset,
            hasImage = bgImagePath != null,
            onUseDefault = {
                WebFavoritesStore.setBackground(ctx, "none", 0)
                bgMode = "none"
            },
            onUsePreset = { idx ->
                WebFavoritesStore.setBackground(ctx, "preset", idx)
                bgMode = "preset"
                bgPreset = idx
            },
            onPickImage = { bgPicker.launch(arrayOf("image/*")) },
            onClearImage = {
                WebFavoritesStore.clearBackgroundImage(ctx)
                bgMode = "none"
                bgImagePath = null
            },
            onDismiss = { showBgDialog = false }
        )
    }
}

/** ★ v17: AnWind 式桌面快捷方式单元（仿 Windows 桌面图标）—— 图标在上、
 *  标签在下（带描边阴影，任何壁纸/背景上可读）。点击打开、长按管理。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopShortcut(
    fav: WebGameEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val displayTitle = fav.customTitle?.takeIf { it.isNotBlank() } ?: fav.title
    val icon = remember(fav.iconPath) {
        val path = fav.iconPath
        if (path.isNullOrBlank()) null
        else try {
            if (java.io.File(path).exists()) android.graphics.BitmapFactory.decodeFile(path) else null
        } catch (_: Exception) { null }
    }
    val accent = FavAccentPalette[displayTitle.hashCode().mod(FavAccentPalette.size)]
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            // ★ v21: width(64.dp) → fillMaxWidth() —— 网格槽位变宽（Adaptive 列）
            //   后内容在槽内居中，不再贴左留大片空白。
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        // ---- 图标瓦片（40dp 圆形，站点 favicon 或首字母；★ v21 方块改圆去“方格”感，
        //      ★ v22 44→40dp 随网格槽位缩小） ----
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.92f), Color(0xFF1C2032))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = displayTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Text(
                    displayTitle.take(1).ifBlank { "?" },
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(2.dp))
        // ---- 桌面标签（Windows 桌面同款：白字 + 深色阴影，壁纸上保证可读） ----
        Text(
            displayTitle,
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = androidx.compose.ui.text.TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color(0xCC000000), blurRadius = 8f
                )
            )
        )
    }
}

/** ★ v17: 桌面"新建快捷方式"图标（尾部"+"，与桌面图标同规格：图标上标签下） */
@Composable
private fun DesktopAddShortcut(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            // ★ v21: width(64.dp) → fillMaxWidth()（与 DesktopShortcut 同款槽内居中）
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(Color(0x1FFFFFFF))
                .border(
                    width = 1.dp,
                    color = Color(0x40FFFFFF),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Add, null, tint = Color(0xFF8A7BFF), modifier = Modifier.size(16.dp))
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(2.dp))
        Text(
            "添加收藏",
            color = Color(0xFFDDE4F0),
            fontSize = 8.sp,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = androidx.compose.ui.text.TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color(0xCC000000), blurRadius = 8f
                )
            )
        )
    }
}

/** ★ v13: 背景选择对话框（默认深色 / 5 个内置渐变 / 相册图片 / 清除图片） */
@Composable
private fun BackgroundChooserDialog(
    currentMode: String,
    currentPreset: Int,
    hasImage: Boolean,
    onUseDefault: () -> Unit,
    onUsePreset: (Int) -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        // ★ v22: 标题显式 15sp 加粗 —— 默认 headlineSmall(24sp) 占高过大
        title = { Text("自定义主页背景", fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        text = {
            // ★ v21: verticalScroll —— 横屏可用高度小，7-8 行选项超出即被裁
            //   （用户反馈“横屏时设置背景菜单页显示不全”）。
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                BgOptionRow("默认深色", currentMode == "none", null) {
                    onUseDefault(); onDismiss()
                }
                BgPresetNames.forEachIndexed { i, name ->
                    BgOptionRow(name, currentMode == "preset" && currentPreset == i, BgPresetGradients[i]) {
                        onUsePreset(i); onDismiss()
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                // ★ v22: 相册/清除按钮 TextButton → 紧凑自绘行 —— TextButton
                //   最小交互尺寸强制 ≥48dp，是对话框超高、"从相册里选择按钮
                //   都显示不出来"的根因之一。34dp 行 + 13sp，横屏也完整可见。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clickable { onPickImage(); onDismiss() }
                        .padding(horizontal = 8.dp)
                ) {
                    Text("从相册选择图片…", color = Color(0xFF8A7BFF), fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
                if (hasImage) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .clickable { onClearImage(); onDismiss() }
                            .padding(horizontal = 8.dp)
                    ) {
                        Text("清除自定义图片", color = Color(0xFFE2707C), fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {}
    )
}

/** 背景选项行：渐变色卡（可选）+ 名称 + 选中态 */
@Composable
private fun BgOptionRow(
    label: String,
    selected: Boolean,
    swatch: List<Color>?,
    onClick: () -> Unit
) {
    // ★ v22: TextButton → 紧凑自绘行（34dp + 13sp + 色卡 28×16）—— TextButton
    //   的最小交互尺寸把每行顶到 ≥48dp，6 行选项 + 标题 + 按钮轻松超过横屏
    //   可用高度（用户反馈"这也太大了吧，从相册里选择按钮都显示不出来"）。
    //   v21 的选中紫色/onSurface/加粗语义全部保留，verticalScroll 保留兜底。
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
    ) {
        if (swatch != null) {
            Box(
                modifier = Modifier
                    .size(28.dp, 16.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(swatch))
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        }
        Text(
            label,
            color = if (selected) Color(0xFF8A7BFF)
                    else androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else null
        )
        if (selected) {
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
            Text("✓", color = Color(0xFF8A7BFF), fontSize = 13.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
    }
}

/** 添加收藏对话框：名称 + 网址 + UA 模式切换 */
@Composable
private fun AddFavoriteDialog(
    onConfirm: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var uaMode by remember { mutableStateOf("desktop") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加收藏") },
        text = {
            Column {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    label = { Text("名称") }
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, singleLine = true,
                    label = { Text("网址") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                androidx.compose.material3.TextButton(onClick = {
                    uaMode = if (uaMode == "desktop") "mobile" else "desktop"
                }) {
                    Text(
                        "UA 模式：" + if (uaMode == "desktop") "桌面版（点击切换）" else "手机版（点击切换）",
                        color = Color(0xFF8A7BFF), fontSize = 13.sp
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(title.trim(), url.trim(), uaMode) }) {
                Text("添加")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 重命名收藏对话框 */
@Composable
private fun RenameFavoriteDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialTitle) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名收藏") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("名称") }
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(name.trim()) }) {
                Text("保存")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ===========================================================================
// ★★ v17: 在线游戏模拟摇杆（WebStickView）★★
//   用户反馈"按键映射处缺少摇杆的 wsad"—— 原 DPadView 三模式（joystick/
//   dpad/wasd）不可改键、且摇杆模式不含 WASD 组合。本控件补齐：
//   - 仿游戏手柄左摇杆：拖动拇指块滑向任意方向，含斜向（双键并按，如 W+D）；
//   - 四向键位可映射（默认 W/S/A/D，按键映射面板"摇杆 WASD 映射"可改）；
//   - 外环四向标注当前映射键名，所见即所得；
//   - 方向键模式 = stick_wasd 时取代十字键上屏（同位置同 dpad_scale 大小）；
//   - 位置编辑模式：拖动整块调整位置（web_stick_pos_x/y 比例持久化），
//     语义与 DPadView 的拖动调整一致。
//   按键注入走 GameWebView.injectKeyDown/Up（JS 按键状态管理器分发，
//   Ruffle/Flash 网游可靠收键；漏 up 由页面级 releaseAllKeys 兜底）。
// ===========================================================================
private class WebStickView(context: android.content.Context) : android.view.View(context) {

    /** 目标 WebView（切换标签时宿主同步）。 */
    var targetWebView: GameWebView? = null

    /** 与其他手柄控件一致的透明度（gamepadAlpha）。 */
    var overlayAlpha: Float = 1f

    /** 位置编辑模式：触摸 = 拖动整块（不发键），同 DPadView 语义。 */
    var isDragMode: Boolean = false

    // ---- 四向键位映射（web_stick_key_*，默认 WASD；按键映射面板可改） ----
    private fun keyFor(dir: Int): String = when (dir) {
        0 -> PrefsManager.sp.getString("web_stick_key_up", "W") ?: "W"
        1 -> PrefsManager.sp.getString("web_stick_key_down", "S") ?: "S"
        2 -> PrefsManager.sp.getString("web_stick_key_left", "A") ?: "A"
        else -> PrefsManager.sp.getString("web_stick_key_right", "D") ?: "D"
    }

    /** 四方向激活状态（0=上 1=下 2=左 3=右；斜向 = 双激活）。 */
    private val activeDirs = BooleanArray(4)

    /** 摇杆头偏移（相对中心，px；松手归零）。 */
    private var knobX = 0f
    private var knobY = 0f

    /** 拖动是否进行中（摇杆模式）。 */
    private var stickHeld = false

    // ---- 位置编辑（拖动整块 + web_stick_pos_x/y 比例持久化） ----
    private var dragAnchorX = 0f
    private var dragAnchorY = 0f

    private val ringPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = android.graphics.Color.WHITE
    }
    private val knobPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = 0xFF8A7BFF.toInt()   // 与工具栏/悬浮球同款紫罗兰（注意 Long 字面量 toInt）
    }
    private val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = android.graphics.Paint.Align.CENTER
    }

    init {
        labelPaint.textSize = 11f * resources.displayMetrics.scaledDensity
    }

    /** 摇杆外环半径（占短边一半的 86%，留出键名标注空间）。 */
    private fun ringRadius(): Float = minOf(width, height) / 2f * 0.86f

    /** 摇杆头最大行程半径。 */
    private fun travelRadius(): Float = ringRadius() * 0.62f

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = ringRadius()
        val density = resources.displayMetrics.density
        val a = overlayAlpha.coerceIn(0f, 1f)
        // ---- 外环 ----
        ringPaint.strokeWidth = 3f * density
        ringPaint.alpha = (72f * a).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r, ringPaint)
        // ---- 内十字参考线（极浅） ----
        ringPaint.strokeWidth = 1.5f * density
        ringPaint.alpha = (30f * a).toInt().coerceIn(0, 255)
        canvas.drawLine(cx - r * 0.55f, cy, cx + r * 0.55f, cy, ringPaint)
        canvas.drawLine(cx, cy - r * 0.55f, cx, cy + r * 0.55f, ringPaint)
        // ---- 摇杆头（拖动到哪里画到哪里，松手回中心） ----
        knobPaint.alpha = (235f * a).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx + knobX, cy + knobY, r * 0.34f, knobPaint)
        // ---- 四向键名标注（当前映射，所见即所得） ----
        labelPaint.alpha = (160f * a).toInt().coerceIn(0, 255)
        val tx = labelPaint.textSize * 0.35f   // drawText y 为基线的竖直居中修正
        canvas.drawText(keyFor(0), cx, cy - r * 0.72f + tx, labelPaint)
        canvas.drawText(keyFor(1), cx, cy + r * 0.72f + tx, labelPaint)
        canvas.drawText(keyFor(2), cx - r * 0.72f, cy + tx, labelPaint)
        canvas.drawText(keyFor(3), cx + r * 0.72f, cy + tx, labelPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (isDragMode) {
                    dragAnchorX = event.rawX
                    dragAnchorY = event.rawY
                    return true
                }
                stickHeld = true
                updateKnob(event.x, event.y)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (isDragMode) {
                    val dx = event.rawX - dragAnchorX
                    val dy = event.rawY - dragAnchorY
                    dragAnchorX = event.rawX
                    dragAnchorY = event.rawY
                    moveSelfBy(dx, dy)
                    return true
                }
                if (stickHeld) updateKnob(event.x, event.y)
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (isDragMode) {
                    saveSelfPosition()
                    return true
                }
                stickHeld = false
                knobX = 0f
                knobY = 0f
                for (d in 0..3) setDir(d, false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 归一化方向判定（阈值 0.34；斜向区间自然产生双激活）。 */
    private fun updateKnob(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val max = travelRadius()
        if (max <= 0f) return
        var dx = x - cx
        var dy = y - cy
        val dist = kotlin.math.hypot(dx, dy)
        if (dist > max) { dx = dx / dist * max; dy = dy / dist * max }
        knobX = dx
        knobY = dy
        val nx = dx / max
        val ny = dy / max
        val th = 0.34f
        setDir(2, nx < -th)   // 左
        setDir(3, nx > th)    // 右
        setDir(0, ny < -th)   // 上（屏幕 y 向下）
        setDir(1, ny > th)    // 下
        invalidate()
    }

    /** 方向边沿 → 按键注入（仅状态变化时发，长按不重发）。 */
    private fun setDir(dir: Int, on: Boolean) {
        if (activeDirs[dir] == on) return
        activeDirs[dir] = on
        val wv = targetWebView ?: return
        val keyCode = try { KeyMapper.toKeyCode(keyFor(dir)) } catch (_: Throwable) { return }
        try {
            if (on) wv.injectKeyDown(keyCode) else wv.injectKeyUp(keyCode)
        } catch (_: Exception) { }
    }

    /** 位置编辑：按拖动量平移自身（marginStart/bottomMargin，屏幕 y 向下取反）。 */
    private fun moveSelfBy(dx: Float, dy: Float) {
        val lp = layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        lp.marginStart = (lp.marginStart + dx.toInt()).coerceAtLeast(0)
        lp.bottomMargin = (lp.bottomMargin - dy.toInt()).coerceAtLeast(0)
        requestLayout()
    }

    /** 位置编辑松手：以"父容器内可用空间比例"持久化（与设备分辨率无关）。 */
    private fun saveSelfPosition() {
        val lp = layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        val parent = parent as? android.view.ViewGroup ?: return
        val freeW = (parent.width - width).coerceAtLeast(1)
        val freeH = (parent.height - height).coerceAtLeast(1)
        val fx = lp.marginStart.toFloat() / freeW
        val fy = lp.bottomMargin.toFloat() / freeH
        PrefsManager.sp.edit()
            .putFloat("web_stick_pos_x", fx.coerceIn(0f, 1f))
            .putFloat("web_stick_pos_y", fy.coerceIn(0f, 1f))
            .apply()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 恢复上次编辑的位置（-1/未写 = 默认左下角）
        val px = PrefsManager.sp.getFloat("web_stick_pos_x", -1f)
        val py = PrefsManager.sp.getFloat("web_stick_pos_y", -1f)
        if (px in 0f..1f && py in 0f..1f) {
            val parent = parent as? android.view.ViewGroup ?: return
            val lp = layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
            val freeW = (parent.width - width).coerceAtLeast(1)
            val freeH = (parent.height - height).coerceAtLeast(1)
            lp.marginStart = (px * freeW).toInt()
            lp.bottomMargin = (py * freeH).toInt()
            requestLayout()
        }
    }

    override fun onDetachedFromWindow() {
        // 兜底：控件被移除（隐藏手柄/改模式/重建）时若仍有方向按住，立即补发 keyup，
        // 防止摇杆半路消失导致游戏角色持续移动（页面级 releaseAllKeys 的双保险）。
        for (d in 0..3) if (activeDirs[d]) setDir(d, false)
        super.onDetachedFromWindow()
    }
}
