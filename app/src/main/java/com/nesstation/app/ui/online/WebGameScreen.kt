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

    // ★ v10: 加载进度 / 渲染进程自愈（renderEpoch）/ window.open 弹窗栈 / 回调共享引用
    val loadProgress = remember { mutableStateOf(100) }
    val renderEpoch = remember { mutableStateOf(0) }
    val popupViews = remember { java.util.ArrayList<GameWebView>() }
    val popupCount = remember { mutableStateOf(0) }
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
    // 缩放仲裁容器（主窗）与当前缩放值
    val pinchLayoutRef = remember { mutableStateOf<WebPinchLayout?>(null) }
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

    // 切换全屏（★ v11：工具栏随全屏隐藏；悬浮菜单触发器保持可见 ——
    // v10 会把菜单触发器一并藏起来，全屏后无路可退。
    // 视频全屏时触发器隐藏改由 onShowFullscreen 单独驱动。）
    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        applyFullscreen(isFullscreen)
    }

    fun toggleOrientation() {
        isLandscape = !isLandscape
        applyOrientation(isLandscape)
        floatingMenuRef.value?.isLandscape = isLandscape
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
        val wv = webViewRef.value ?: return false
        if (wv.canGoBack()) {
            wv.goBack()
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
        PrefsManager.sp.edit().putString("ua_mode", ua).apply()
        when (ua) {
            "ie_compat" -> wv.useUaMode("ie_compat")
            "mobile" -> wv.useUaMode("mobile")
            else -> wv.useDesktopMode(true)
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
        // 网页全屏视频/window.open 弹窗由后注册的 BackHandler#2 优先处理。
        if (isFullscreen) {
            toggleFullscreen()
        } else if (!browserBack()) {
            onExit()
        }
    })

    // ---------- 屏幕方向 / 全屏：★ v11 已整体上移（见 activity 声明后） ----------

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
        // ★ v11: 统一缩放通道 —— WebPinchLayout 动态 viewport 注入，
        //   即时生效、无需 reload，与双指捏合同一坐标系自然衔接。
        //   （v10 的 setInitialScale+reload 与页面 meta/PINCH_UNLOCK 打架，
        //    用户实测"百分比设置完全没作用"，已整体废弃。）
        webViewRef.value?.applyPageZoom(manual)   // 仅清理通道（textZoom/body.zoom 残留）
        val z = if (mode == "manual") manual.coerceIn(25, 300) / 100f else 1f
        pinchLayoutRef.value?.setZoomImmediate(z)
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

    // ================ ★ v10: 网页全屏退出 / window.open 弹窗管理 ================

    /** 网页全屏退出（返回键与 ChromeClient.onHideFullscreen 共用同一逻辑） */
    fun exitWebFullscreen() {
        val view = customViewRef.value
        if (view != null) (view.parent as? ViewGroup)?.removeView(view)
        customViewCallbackRef.value?.onCustomViewHidden()
        customViewRef.value = null
        customViewCallbackRef.value = null
    }

    /** 关闭并销毁一个弹窗 WebView（window.close / 返回键 / 渲染进程死亡时调用）。
     *   ★ v11: 弹窗由 WebPinchLayout 承载 → 连容器一起销毁；
     *   地址栏同步回主窗口当前页（弹窗导航曾把地址栏带去弹窗 URL）。 */
    fun closePopupWindow(window: WebView?) {
        val wv = window ?: return
        if (popupViews.remove(wv)) {
            destroyWebViewHolder(wv)
            popupCount.value = popupViews.size
            val mainUrl = webViewRef.value?.url
            if (!addressBarFocused) {
                addressText = when {
                    mainUrl == null || mainUrl == BLANK_URL -> ""
                    else -> mainUrl
                }
            }
        }
    }

    /**
     * ★ v10: window.open / target=_blank → 真实弹窗 WebView（参照 AnWind onCreateWindow）：
     * 弹窗复用主窗口的 View/Chrome 客户端与 JS 桥，全屏铺在主画面之上，
     * 手柄/悬浮菜单置顶仍可操作；返回键逐层关闭（见 BackHandler 优先级）。
     */
    fun createPopupWindow(opener: WebView?, resultMsg: android.os.Message): Boolean {
        val container = mainBoxRef.value ?: return false
        val client = wvClientRef.value ?: return false
        val chromeCb = chromeCallbackRef.value ?: return false
        try {
            val popup = GameWebView(container.context).apply {
                webChromeClient = object : GameWebChromeClient(chromeCb) {}
                webViewClient = client
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
            // ★ v11: 弹窗同样由缩放仲裁容器承载（弹窗内双指缩放与主窗同能力）
            val popupPinch = WebPinchLayout(container.context)
            popupPinch.setWebView(popup)
            container.addView(popupPinch, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            // 手柄/悬浮菜单保持在弹窗之上可操作
            listOfNotNull(
                dpadRef.value, actionRef.value, mouseRef.value,
                systemButtonsRef.value, floatingMenuRef.value
            ).forEach { it.bringToFront() }
            popupViews.add(popup)
            popupCount.value = popupViews.size
            val transport = resultMsg.obj as? WebView.WebViewTransport
            transport?.webView = popup
            resultMsg.sendToTarget()
            return true
        } catch (e: Exception) {
            android.util.Log.w("WebGameScreen", "createPopupWindow failed", e)
            return false
        }
    }

    // ★ v10: 返回键优先级 —— 网页全屏(HTML5 requestFullscreen)先退全屏；
    //   window.open 弹窗先逐层关闭；两者都不是时交给上面的常规返回逻辑。
    //   （后注册的 BackHandler 优先生效；引用的局部函数均已在上方声明）
    BackHandler(enabled = customViewRef.value != null || popupCount.value > 0) {
        if (customViewRef.value != null) {
            exitWebFullscreen()
        } else if (popupViews.isNotEmpty()) {
            // ★ v11: 弹窗由 WebPinchLayout 承载 → 连容器一起销毁
            destroyWebViewHolder(popupViews.removeAt(popupViews.size - 1))
            popupCount.value = popupViews.size
        }
    }

    // Compose 主容器（★ v11: Column = 浏览器工具栏 + 内容区；全屏时工具栏隐藏）
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

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
                        onAdd = { showAddFavoriteDialog = true }
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

            // 客户端与回调（★ v10: onProgress 接顶部进度条 / 弹窗窗口管理 / 全屏退出统一）
            val chromeCallback = object : GameWebChromeClient.Callback {
                override fun onProgress(progress: Int) { loadProgress.value = progress }
                override fun onTitle(title: String?) {
                    // ★ v11: 页面标题 → 收藏星标默认名
                    pageTitle = title ?: ""
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
                // ★ v10: window.open / target=_blank → 真实弹窗 WebView（返回键逐层关闭）
                override fun handleCreateWindow(opener: WebView?, isDialog: Boolean, resultMsg: android.os.Message): Boolean {
                    return createPopupWindow(opener, resultMsg)
                }
                // ★ v10: window.close() / 页面自行关闭 → 销毁对应弹窗
                override fun handleCloseWindow(window: WebView?) {
                    closePopupWindow(window)
                }
            }
            // ★ v10: 暴露给 createPopupWindow（弹窗复用同一套 Chrome 行为）
            chromeCallbackRef.value = chromeCallback

            val viewClientCallback = object : GameWebViewClient.Callback {
                override fun onPageStarted(url: String?) {
                    webViewRef.value?.releaseAllKeys()
                    // ★ v11: 地址栏同步 + 主页检测 + 缩放域导航重置
                    //   （焦点内输入时不同步 —— 正在打字不覆盖）
                    if (url == BLANK_URL) {
                        homeVisible = true
                        if (!addressBarFocused) addressText = ""
                    } else if (!url.isNullOrBlank()) {
                        homeVisible = false
                        if (!addressBarFocused) addressText = url
                    }
                    pinchLayoutRef.value?.resetForNavigation()
                }
                override fun onPageFinished(url: String?) {
                    // ★ v11: 持久化手动缩放基线重放 —— 每页加载完成回到用户
                    //   设定的百分比（auto 已在 onPageStarted 重置回 100%）
                    if (PrefsManager.pageZoomMode == "manual") {
                        pinchLayoutRef.value?.setZoomImmediate(
                            PrefsManager.pageZoomManual.coerceIn(25, 300) / 100f
                        )
                    }
                }
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
                // ★ v10: 渲染进程死亡自愈（参照 AnWind renderEpoch）—— 不接管则
                //   Chromium 杀整个应用进程。主窗口销毁重建自动重载；弹窗直接关闭。
                // ★ v11: 主窗销毁连带其缩放容器（WebPinchLayout）一起拆除。
                override fun onRendererGone(deadView: WebView?) {
                    val main = webViewRef.value
                    if (deadView != null && deadView !== main) {
                        closePopupWindow(deadView)
                        return
                    }
                    val dead = main
                    webViewRef.value = null
                    if (dead != null) {
                        destroyWebViewHolder(dead)
                    }
                    pinchLayoutRef.value = null
                    renderEpoch.value = renderEpoch.value + 1
                    Toast.makeText(context, "页面渲染进程异常，已自动恢复", Toast.LENGTH_SHORT).show()
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

            // ★ v11: 双指缩放仲裁容器（WebPinchLayout）承载 WebView ——
            //   页面禁缩放（meta/preventDefault）时容器接管手势，动态改写
            //   viewport meta 强制连续缩放（AnWind 已验证方案）。
            val oldPinch = pinchLayoutRef.value
            if (oldPinch != null && oldPinch.parent == container) container.removeView(oldPinch)
            val pinch = WebPinchLayout(container.context).apply {
                onZoomChanged = { z -> pinchZoom.value = z }
            }
            pinchLayoutRef.value = pinch
            pinch.setWebView(wv)
            container.addView(pinch, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            // ★ v11: 渲染自愈重建时 pinch 追加在控件之后 —— 把手柄/菜单拉回最上层
            listOfNotNull(
                dpadRef.value, actionRef.value, mouseRef.value,
                systemButtonsRef.value, floatingMenuRef.value
            ).forEach { it.bringToFront() }
            webViewRef.value = wv

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

    // 释放资源 / 屏幕常亮
    DisposableEffect(Unit) {
        // ★ v10: 游戏期间屏幕常亮
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // ★ v10: 弹窗销毁链 —— 先 JS 静音再 destroy（参照 AnWind，防“关了还在响”）
            // ★ v11: 弹窗/主窗均由 WebPinchLayout 承载 → destroyWebViewHolder 连容器一起拆
            popupViews.forEach { pv ->
                try {
                    pv.evaluateJavascript(
                        "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){" +
                        "try{m.pause();}catch(e){}});}catch(e){}})();", null)
                } catch (_: Exception) {}
                destroyWebViewHolder(pv)
            }
            popupViews.clear()
            popupCount.value = 0
            // 主窗口同样先静音再 destroy
            webViewRef.value?.let { wv ->
                try {
                    wv.evaluateJavascript(
                        "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){" +
                        "try{m.pause();}catch(e){}});}catch(e){}})();", null)
                } catch (_: Exception) {}
                destroyWebViewHolder(wv)
            }
            pinchLayoutRef.value?.detachWebView()
            pinchLayoutRef.value = null
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
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Rounded.ArrowBack, "后退", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onForward, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Rounded.ArrowForward, "前进", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        if (loading) {
            IconButton(onClick = onStop, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.Close, "停止", tint = Color(0xFFFF8A80), modifier = Modifier.size(20.dp))
            }
        } else {
            IconButton(onClick = onReload, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.Refresh, "刷新", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        IconButton(onClick = onHome, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Rounded.Home, "主页", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onAddressFocusChange(it.isFocused) },
            singleLine = true,
            placeholder = { Text("搜索或输入网址", color = Color(0xFF8899AA), fontSize = 13.sp) },
            leadingIcon = {
                Icon(Icons.Rounded.Search, null, tint = Color(0xFF8899AA), modifier = Modifier.size(16.dp))
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF8A7BFF),
                unfocusedBorderColor = Color(0x33FFFFFF),
                focusedContainerColor = Color(0x14FFFFFF),
                unfocusedContainerColor = Color(0x14FFFFFF),
                cursorColor = Color(0xFF8A7BFF)
            )
        )
        IconButton(onClick = onFavorite, modifier = Modifier.size(38.dp)) {
            Icon(
                if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                "收藏",
                tint = if (isFavorite) Color(0xFFFFD54F) else Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        // ☰ 菜单：打开悬浮菜单（手柄/Flash/缩放/兼容模式等全部设置入口）
        IconButton(onClick = onMenu, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Rounded.MoreVert, "菜单", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * 浏览器主页（收藏夹网格 + 提示条）。点击收藏打开站点；
 * 长按弹管理菜单（打开/重命名/自定义图标/删除）；尾部"+"卡片添加收藏。
 * 覆盖整个内容区 —— 底层 about:blank 载体完全不可见。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserHomeOverlay(
    favorites: List<WebGameEntry>,
    onNavigate: (WebGameEntry) -> Unit,
    onOpenMenu: (WebGameEntry) -> Unit,
    onAdd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBackground)
    ) {
        androidx.compose.foundation.layout.Spacer(Modifier.size(18.dp))
        Text(
            "浏览器 · 在线游戏",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
        Text(
            "收藏夹 · ${favorites.size} 个站点（点击打开 · 长按管理 · 工具栏★收藏当前页）",
            color = Color(0xFF8899AA),
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            gridItems(favorites) { fav ->
                FavoriteCard(
                    fav = fav,
                    onClick = { onNavigate(fav) },
                    onLongClick = { onOpenMenu(fav) }
                )
            }
            item {
                AddFavoriteCard(onClick = onAdd)
            }
        }
    }
}

/** 单个收藏卡片：自定义图标优先，无图标用首字母色卡；底部标题条。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteCard(
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
    Box(
        modifier = Modifier
            .aspectRatio(0.9f)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.32f), Color(0x141A1A2E))
                )
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 34.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        // 底部标题条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC10131A))
                .align(Alignment.BottomCenter)
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text(
                displayTitle,
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

/** 尾部"+"添加卡片 */
@Composable
private fun AddFavoriteCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(0.9f)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(Color(0x1FFFFFFF))
            .border(
                width = 1.dp,
                color = Color(0x40FFFFFF),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Icon(Icons.Rounded.Add, null, tint = Color(0xFF8A7BFF), modifier = Modifier.size(28.dp))
            Text("添加", color = Color(0xFF8899AA), fontSize = 11.sp)
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
