package com.nesstation.app.flash.webview

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Collections
import kotlin.math.abs

/**
 * 游戏专用 WebView：
 * 1. 预置适配 Flash/H5 游戏的 WebSettings（DOM 存储、自动播放、跨域、硬件加速）
 * 2. 触屏手势：长按 → 屏蔽系统选择菜单；滑动 → 方向键
 * 3. 物理键盘：dispatchKeyEvent 透传方向键 / WASD / 空格 / 回车给网页
 * 4. 原生 IME 接口（NativeIme）供 WAFlash 等网页唤起 Android 软键盘
 *    并实现自定义 InputConnection 把输入法提交的文本回灌到 JS 键盘事件
 *
 * 注：当前 WebView 默认仅消费 BACK 键，其余按键必须重写 dispatchKeyEvent
 *     才能让网页 keydown/keyup 监听器收到。这是触屏 + 键盘双控的关键。
 *
 * 1:1 移植自 3.3-fix2 GameWebView，包名/imports 改为 com.nesstation.app.flash。
 */
open class GameWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    /** 是否拦截并屏蔽长按系统菜单（选中文字/复制） */
    var blockLongPressMenu: Boolean = true

    /**
     * 3D 视角旋转模式：开启后页面会注入触摸→鼠标事件脚本，此时
     * 滑动手势不再注入方向键，避免拖动旋转视角时误触移动。
     */
    var cameraRotationEnabled: Boolean = false

    /** 保存原始移动版 UA，供切换时恢复 */
    private var mobileUa: String = ""

    // ---- 3D 视角旋转触摸追踪 ----
    // 旧实现：每个 ACTION_MOVE 都立即 evaluateJavascript 一次，每秒 60+ 次 JNI
    // 往返 + JS 解析 + 事件分发 —— 触摸移动太快时 evaluateJavascript 队列堆积，
    // 前几次触摸事件几乎完全没反应，直到队列消化一部分才"有点顺畅"
    // （用户描述的"长按屏幕以后才有点顺畅"）。
    //
    // 新实现：把 ACTION_MOVE 的 dx/dy 累积到 pendingCameraDx/Dy，
    // 用 post() + 帧节流（16ms 一次）合并成一次 evaluateJavascript 调用。
    // 这样无论触摸事件多快，每帧只产生一次 JS 调用，旋转视角立即响应、流畅。
    /** 上一次触摸 X 坐标（用于计算移动增量） */
    private var cameraLastX = 0f
    /** 上一次触摸 Y 坐标（用于计算移动增量） */
    private var cameraLastY = 0f
    /** 是否正在拖动旋转视角 */
    private var cameraDragging = false
    /** 本帧累积的 X 方向移动量（待 flush 到 JS） */
    private var pendingCameraDx = 0f
    /** 本帧累积的 Y 方向移动量（待 flush 到 JS） */
    private var pendingCameraDy = 0f
    /** 是否已经 schedule 了帧 flush（防止重复 post） */
    private val cameraFlushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * IME 是否被请求（仅 WAFlash 等需要文本输入的场景开启）。
     * 开启时 onCheckIsTextEditor() 返回 true，系统认定本 View 为文本编辑器并弹软键盘。
     */
    private var imeRequested = false

    /**
     * 当前通过虚拟手柄按下的按键集合（Android KeyCode）。
     * 用于在页面导航、Activity 暂停、手柄隐藏时统一释放，
     * 防止 Ruffle/Flash 引擎因漏收 keyup 导致角色持续移动。
     */
    private val pressedKeys = Collections.synchronizedSet(HashSet<Int>())

    private val gestureDetector = GestureDetector(context, GestureListener())

    // ★ v10 双指缩放体验：本手势流是否出现过多指 —— 双指捏合过程中 GestureDetector
    //   可能把两指间位移误判为 fling 而注入方向键（缩放时游戏角色乱动）。
    //   多指后本手势流内不再注入方向键（DOWN 时复位）。
    private var multiTouchSeen = false

    init {
        configureSettings()
        // 横竖滚动条隐藏，让游戏画面铺满
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        // 确保 WebView 可获取焦点（网页 input 点击时触发软键盘必需）
        isFocusable = true
        isFocusableInTouchMode = true
        // 屏蔽长按系统菜单
        setOnLongClickListener { blockLongPressMenu }
        // ★ v10: 通用文件下载（apk/zip/图片等 <a download> / content-disposition 附件）——
        //   SWF 直链不会到这里（shouldOverrideUrlLoading 先行拦截进播放器）。
        //   走系统 DownloadManager（参照 AnWind）：落公共 Downloads/、系统通知进度、
        //   文件名解析含 RFC 5987 filename*=UTF-8'' 形式。
        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleExternalDownload(url, userAgent, contentDisposition, mimetype)
        }
        // ★ v10: 第三方 Cookie 放行（登录态/弹窗会话共享；部分游戏站点登录必需）
        try {
            android.webkit.CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(this@GameWebView, true)
            }
        } catch (_: Exception) {
        }
        // 添加原生 IME 接口，供 WAFlash 等网页调用唤起输入法（双通道：无 NativeIme 时回落到隐藏 input）
        addJavascriptInterface(object {
            @JavascriptInterface
            fun showIme() {
                imeRequested = true
                post {
                    requestFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(this@GameWebView, 0)
                }
            }
            @JavascriptInterface
            fun hideIme() {
                imeRequested = false
                post {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(this@GameWebView.windowToken, 0)
                }
            }
        }, "NativeIme")
        // ★ v19: 下载桥接接口 —— blob:/data: 链接经页面 JS 取回内容后回传落盘
        //   （与 NativeIme 同款双通道模式；JS 侧即 window.NativeDownload.*）。
        //   根因：DownloadManager 只认 http/https，弹窗下载页最常见的 blob:
        //   （JS 生成文件再 window.open/location 跳转）与 data:（base64 内联）
        //   喂给 DownloadManager 必抛"Unsupported scheme" → 此前"跳出弹窗
        //   下载的都无法下载"。桥接 = 页面内 fetch(blob) → FileReader base64
        //   → 本接口落盘，DownloadManager 无法企及的场景全部兜住。
        addJavascriptInterface(object {
            @JavascriptInterface
            fun saveBase64(name: String, mime: String, base64: String) {
                Thread {
                    try {
                        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                        val saved = saveBytesToDownloads(name, mime, bytes)
                        post { android.widget.Toast.makeText(context, "已保存：$saved", android.widget.Toast.LENGTH_LONG).show() }
                    } catch (e: Exception) {
                        post { android.widget.Toast.makeText(context, "下载失败：${e.message ?: "保存文件失败"}", android.widget.Toast.LENGTH_SHORT).show() }
                    }
                }.start()
            }
            @JavascriptInterface
            fun fail(reason: String) {
                post { android.widget.Toast.makeText(context, "下载失败：$reason", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }, "NativeDownload")
    }

    @Suppress("DEPRECATION")
    private fun configureSettings() = settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true                       // H5 游戏依赖 localStorage
        databaseEnabled = true
        allowFileAccess = true
        allowContentAccess = true
        allowFileAccessFromFileURLs = true             // 本地 SWF 播放需要
        allowUniversalAccessFromFileURLs = true        // 自托管 wasm 跨域
        mediaPlaybackRequiresUserGesture = false       // Flash 游戏 BGM 自动播放
        javaScriptCanOpenWindowsAutomatically = true
        loadWithOverviewMode = true
        useWideViewPort = true
        cacheMode = WebSettings.LOAD_DEFAULT
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // 双指缩放：PC 网页内容通常较宽，需要缩放查看
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false                  // 不显示+/-按钮，仅手势缩放
        // PC 网页缩放：强制启用 viewport 缩放，让宽页面适配屏幕
        // ★ v10: 多窗口支持 —— window.open / target=_blank 经 GameWebChromeClient
        //   .onCreateWindow → 真实弹窗 WebView（此前 false = 弹窗链接点击无反应）
        setSupportMultipleWindows(true)
        // 保存默认移动版 UA，追加客户端标识
        mobileUa = userAgentString
        userAgentString = "$mobileUa 4399App/1.0 (Android)"
        // ★ v10: 默认编码/地理能力补齐（AnWind 同款基线；地理授权由 ChromeClient 授予）
        defaultTextEncodingName = "UTF-8"
        setGeolocationEnabled(true)
        // ★ v10: 禁用算法暗色（参照 AnWind）—— 防 MIUI/系统深色模式把网页渲染
        //   搞成“灰屏/反色”；窗口级硬件加速已在 Manifest 开启。
        //   同时移除旧版 setLayerType(LAYER_TYPE_HARDWARE)：官方文档不支持对 WebView
        //   强制硬件层，部分设备（MIUI）会导致“加载完成但永不绘制”（AnWind 实证）。
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, false)
            }
        } catch (_: Exception) {
        }
        // 启用 Safe Browsing（AndroidX WebKit，minSdk 23+）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(this, true)
        }
    }

    /**
     * ★ v10: 通用下载走系统 DownloadManager（参照 AnWind）：文件名解析
     * contentDisposition（含 RFC 5987 filename*=UTF-8'' 形式）→ 落公共
     * Downloads/，系统通知进度；APK 完成后允许媒体扫描。SWF 直链不会到这里
     * （shouldOverrideUrlLoading 先行拦截进 Ruffle/WAFlash 播放器）。
     *
     * ★ v19: 修复"下载文件报错、弹窗下载全失败"——
     *   1) http/https：补 Cookie/Referer 转发（登录态/防盗链下载此前 403 报错）；
     *      公共 Downloads 落盘失败（个别设备 SecurityException）自动回退应用
     *      外部目录；DownloadManager 全链路失败的最后一搏 = ACTION_VIEW 转系统；
     *   2) data:（base64 内联文件）：DownloadManager 不认 → 直接解码落盘；
     *   3) blob:（JS 生成文件对象、弹窗下载页最常见形态）：DownloadManager
     *      完全不支持 → 在创建该 blob 的本 WebView 页面上下文注入 fetch 脚本，
     *      FileReader 转 base64 经 NativeDownload 桥接落盘；
     *   4) 文件名清洗（剥路径分隔符/控制字符），防非法名导致 enqueue 失败。
     */
    private fun handleExternalDownload(url: String, userAgent: String, contentDisposition: String?, mimetype: String?) {
        val fileName = sanitizeDownloadFileName(parseDownloadFileName(contentDisposition, url))
        val scheme = try { android.net.Uri.parse(url).scheme?.lowercase() } catch (_: Exception) { null }
        when (scheme) {
            "http", "https" -> {
                try {
                    val request = android.app.DownloadManager.Request(android.net.Uri.parse(url)).apply {
                        try { setMimeType(mimetype) } catch (_: Exception) {}
                        addRequestHeader("User-Agent", userAgent)
                        // ★ v19: 会话 Cookie 转发 —— 登录后才能下载的文件此前 403
                        try {
                            android.webkit.CookieManager.getInstance().getCookie(url)?.let {
                                addRequestHeader("Cookie", it)
                            }
                        } catch (_: Exception) {}
                        // ★ v19: Referer 转发 —— 防盗链站点此前直接拒绝
                        try {
                            this@GameWebView.url?.takeIf { it.isNotBlank() }?.let {
                                addRequestHeader("Referer", it)
                            }
                        } catch (_: Exception) {}
                        setTitle(fileName)
                        setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        try { allowScanningByMediaScanner() } catch (_: Exception) {}
                        // 公共 Downloads 优先；个别设备/旧系统抛异常 → 回退应用外部目录（免权限）
                        try {
                            setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                        } catch (se: Exception) {
                            setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                        }
                    }
                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                    dm.enqueue(request)
                    post { android.widget.Toast.makeText(context, "开始下载：$fileName", android.widget.Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    // ★ v19: DownloadManager 建任务失败的最后一搏 —— 转交系统应用处理
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        post { android.widget.Toast.makeText(context, "已转交系统下载：$fileName", android.widget.Toast.LENGTH_SHORT).show() }
                    } catch (e2: Exception) {
                        post { android.widget.Toast.makeText(context, "下载失败：${e2.message ?: e.message ?: "无法创建下载"}", android.widget.Toast.LENGTH_SHORT).show() }
                    }
                }
            }
            "data" -> {
                // ★ v19: data: URI（base64/URL 编码内联文件）—— 直接解码落盘
                try {
                    val comma = url.indexOf(',')
                    if (comma > 4) {
                        val header = url.substring(5, comma)
                        val payload = url.substring(comma + 1)
                        val isBase64 = header.contains("base64", ignoreCase = true)
                        val bytes = if (isBase64) android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                                    else java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
                        val mime = header.substringBefore(';').ifBlank { mimetype ?: "application/octet-stream" }
                        val saved = saveBytesToDownloads(fileName, mime, bytes)
                        post { android.widget.Toast.makeText(context, "已保存：$saved", android.widget.Toast.LENGTH_LONG).show() }
                    } else {
                        post { android.widget.Toast.makeText(context, "下载失败：data 链接格式无效", android.widget.Toast.LENGTH_SHORT).show() }
                    }
                } catch (e: Exception) {
                    post { android.widget.Toast.makeText(context, "下载失败：${e.message ?: "data 解码失败"}", android.widget.Toast.LENGTH_SHORT).show() }
                }
            }
            "blob" -> {
                // ★ v19: blob: URL —— 弹窗下载页最常见的形态（JS 生成的文件对象）。
                //   必须在创建该 blob 的同一个 WebView 页面里 fetch（本实例），
                //   FileReader 转 base64 → NativeDownload 桥接落盘。
                post { android.widget.Toast.makeText(context, "正在获取下载内容…", android.widget.Toast.LENGTH_SHORT).show() }
                val urlJson = org.json.JSONObject(url).toString()
                val nameJson = org.json.JSONObject(fileName).toString()
                val script = """
                    (function(){
                      if (window.__nsDlBusy) return; window.__nsDlBusy = 1;
                      try {
                        fetch($urlJson).then(function(r){ return r.blob(); }).then(function(b){
                          var fr = new FileReader();
                          fr.onload = function(){
                            try {
                              var d = String(fr.result);
                              var i = d.indexOf(',');
                              var head = d.slice(5, i);
                              var mime = head.split(';')[0] || 'application/octet-stream';
                              window.NativeDownload.saveBase64($nameJson, mime, d.slice(i + 1));
                            } catch (e) { window.NativeDownload.fail(String(e)); }
                            window.__nsDlBusy = 0;
                          };
                          fr.onerror = function(){ window.NativeDownload.fail('read blob failed'); window.__nsDlBusy = 0; };
                          fr.readAsDataURL(b);
                        }).catch(function(e){ window.NativeDownload.fail(String(e)); window.__nsDlBusy = 0; });
                      } catch (e) { window.NativeDownload.fail(String(e)); window.__nsDlBusy = 0; }
                    })();
                """.trimIndent()
                evaluateJavascript(script, null)
            }
            else -> {
                post { android.widget.Toast.makeText(context, "暂不支持该类型下载链接（$scheme）", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /** ★ v19: 下载文件名清洗 —— 剥离路径分隔符与控制/非法字符，空名兑底时间戳名 */
    private fun sanitizeDownloadFileName(raw: String): String {
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1F\"<>|:?*]"), "").trim()
        return cleaned.ifBlank { "download_${System.currentTimeMillis()}.bin" }
    }

    /** ★ v19: 字节流落 Downloads —— API 29+ 走 MediaStore（系统下载目录、
     *  文件应用可见），以下走应用外部私有目录（免权限）；均失败再兑底应用
     *  私有 files 目录（100% 可写），保证下载结果永不为空。 */
    private fun saveBytesToDownloads(fileName: String, mimeType: String, data: ByteArray): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.IOException("MediaStore 写入失败")
                context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                    ?: throw java.io.IOException("打开输出流失败")
                "Download/$fileName"
            } else {
                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val file = java.io.File(dir, fileName)
                java.io.FileOutputStream(file).use { it.write(data) }
                file.absolutePath
            }
        } catch (e: Exception) {
            val file = java.io.File(context.filesDir, fileName)
            java.io.FileOutputStream(file).use { it.write(data) }
            file.absolutePath
        }
    }

    /** 下载文件名解析：RFC 5987 filename*=UTF-8''… → filename="…" → URL 兑底 */
    private fun parseDownloadFileName(cd: String?, url: String): String {
        cd?.let { raw ->
            val utf8 = Regex("""filename\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
            if (utf8 != null) {
                return try { java.net.URLDecoder.decode(utf8, "UTF-8") } catch (_: Exception) { utf8 }
                    .replace("\r", "").replace("\n", "")
            }
            val plain = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
            if (plain != null) {
                return try { java.net.URLDecoder.decode(plain, "UTF-8") } catch (_: Exception) { plain }
                    .replace("\r", "").replace("\n", "")
            }
        }
        val name = android.net.Uri.parse(url).lastPathSegment?.substringAfterLast('/') ?: "download"
        return try { java.net.URLDecoder.decode(name, "UTF-8") } catch (_: Exception) { name }
    }

    /**
     * 注入 Document Start 脚本：在页面任何 JS 之前执行（AndroidX WebKit 1.6.0+）。
     * 必须在 WebView 创建后、首次加载前调用。
     * 解决 evaluateJavascript(onPageStarted) 时序问题——异步注入可能晚于页面自身脚本。
     * ★ v10: 追加 PINCH_UNLOCK_SCRIPT（双指缩放解锁 —— 必须在页面 meta 解析前
     *   挂上 MutationObserver，才能覆盖“页面脚本后插/改写 viewport meta”的场景）。
     * ★ v14: 追加 UA_DESKTOP_SPOOF_SCRIPT（桌面/兼容模式的视口拉宽 + 客户端
     *   提示伪装 —— 只改 UA 请求头时响应式站点照样手机布局，详见脚本注释）。
     */
    fun injectDocumentStartScripts() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(this, DOCUMENT_START_SCRIPT, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(this, PINCH_UNLOCK_SCRIPT, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(this, UA_DESKTOP_SPOOF_SCRIPT, setOf("*"))
        }
    }

    /**
     * 切换桌面/移动 UA。
     * - 桌面模式：使用 Windows Chrome UA，不含 "Mobile"/"Android"，4399 服务器据此返回 PC 版页面
     * - 移动模式：恢复默认移动版 UA + 客户端标识
     */
    fun useDesktopMode(enabled: Boolean) {
        settings.userAgentString = if (enabled) DESKTOP_UA else "$mobileUa 4399App/1.0 (Android)"
    }

    /**
     * 设置 UA 模式：
     * - "desktop": Windows Chrome UA
     * - "ie_compat": IE11 兼容模式 UA（Trident/7.0）
     * - 其他: 默认移动版 UA
     */
    fun useUaMode(mode: String) {
        settings.userAgentString = when (mode) {
            "desktop" -> DESKTOP_UA
            "ie_compat" -> IE_COMPAT_UA
            else -> "$mobileUa 4399App/1.0 (Android)"
        }
    }

    /**
     * 应用页面缩放 —— ★ v11 起本方法仅作「清理通道」：真实缩放统一走
     * WebPinchLayout（宿主持有，见 WebGameScreen.applyZoom → setZoomImmediate），
     * 与原生双指捏合同一坐标系（viewport meta），无需 reload 即时生效。
     *
     * v10 的 setInitialScale+reload 方案已被用户实测否决：在带 viewport meta 的
     * 页面上与 PINCH_UNLOCK/页面自身 meta 互相覆盖打架，表现为“百分比设置
     * 完全没作用”；setInitialScale 还要求整页重载、体验割裂。
     *
     * 本方法保留（历史调用方兼容）：重置 textZoom 残留 + 清理旧版 body.zoom。
     */
    fun applyPageZoom(percent: Int) {
        val p = percent.coerceIn(25, 300)
        settings.textZoom = 100  // 重置 textZoom,避免之前的设置残留影响字体
        // 清理旧版本遗留的 body.zoom（一次性兼容清理）
        post { evaluateJavascript("try{document.body.style.zoom='';}catch(e){}", null) }
    }

    // ---------------- 触屏 ----------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // ★ v10 双指缩放体验：记录本手势流是否出现过多指 —— 双指捏合过程中
        //   GestureDetector 可能把两指间的位移误判为 fling 而注入方向键，
        //   导致缩放时游戏“乱动”；多指后本手势流内不再注入方向键。
        if (event.actionMasked == MotionEvent.ACTION_DOWN) multiTouchSeen = false
        else if (event.pointerCount > 1) multiTouchSeen = true
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_DOWN) performClick()

        // 3D 视角旋转模式：追踪拖动并分发 mousemove 事件
        // 必须在 super.onTouchEvent 之前处理，否则某些网页的默认 touch handler
        // 可能消费事件，导致 ACTION_MOVE 不再上报到本 View。
        if (cameraRotationEnabled) {
            handleCameraRotationTouch(event)
        }

        return super.onTouchEvent(event)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    /**
     * 3D 视角旋转触摸处理：
     * 追踪拖动距离，通过 JS 分发带 movementX/movementY 的 mousemove 事件。
     * 不拦截事件（返回 void），让网页仍能接收 tap/click。
     *
     * 性能优化：ACTION_MOVE 不再每个事件都立即 evaluateJavascript（每秒 60+ 次
     * JNI 调用会堆积队列、造成前几帧无响应、需要"长按一会才顺畅"的现象）。
     * 改为累积 dx/dy 到 pendingCameraDx/Dy，每帧（约 16ms）通过 post() 合并
     * 成一次 evaluateJavascript 调用。
     */
    private fun handleCameraRotationTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cameraLastX = event.x
                cameraLastY = event.y
                cameraDragging = false
                // 手指按下时立即 flush 上一帧残留的累积量，避免拖动开始时跳跃
                flushPendingCameraMotion()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - cameraLastX
                val dy = event.y - cameraLastY
                // 移动超过 1px 就累积（阈值从 2 降到 1，让微小拖动也能响应；
                // 之前 2px 阈值 + 每事件 evaluateJavascript 太迟钝）
                if (abs(dx) > 1f || abs(dy) > 1f) {
                    cameraDragging = true
                    // 累积到本帧待 flush 的总量
                    pendingCameraDx += dx
                    pendingCameraDy += dy
                    cameraLastX = event.x
                    cameraLastY = event.y
                    // schedule 帧 flush（如果还没 schedule）
                    scheduleCameraFlush()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 手指抬起时立即 flush 最后一段累积量，避免最后一帧丢失
                flushPendingCameraMotion()
                cameraDragging = false
            }
        }
    }

    /**
     * Schedule 一次帧 flush：在下一个 UI 帧（约 16ms 后）把累积的 dx/dy 一次性
     * 发送给 JS。同一帧内多个 ACTION_MOVE 只会 schedule 一次，确保每帧最多
     * 一次 evaluateJavascript 调用。
     */
    private fun scheduleCameraFlush() {
        if (cameraFlushScheduled.compareAndSet(false, true)) {
            post {
                cameraFlushScheduled.set(false)
                flushPendingCameraMotion()
            }
        }
    }

    /**
     * 把累积的 pendingCameraDx/Dy 通过一次 evaluateJavascript 调用发送给 JS。
     * 用 float 累积、int 派发：JavaScript MouseEvent.movementX/Y 是整数，
     * 累积小数部分会在下一帧保留，避免慢速拖动时 movementX 始终为 0。
     */
    private fun flushPendingCameraMotion() {
        val dx = pendingCameraDx
        val dy = pendingCameraDy
        if (dx == 0f && dy == 0f) return
        pendingCameraDx = 0f
        pendingCameraDy = 0f
        // 把累积量四舍五入到整数；小数部分留在 pendingXxx 累加器里下次再算
        val intDx = dx.toInt()
        val intDy = dy.toInt()
        if (intDx == 0 && intDy == 0) {
            // 整数部分为 0 —— 把小数部分保留，等下一次累积到 ≥1 再派发
            pendingCameraDx = dx
            pendingCameraDy = dy
            return
        }
        // 派发整数部分，余数留到下一帧
        pendingCameraDx = dx - intDx
        pendingCameraDy = dy - intDy
        evaluateJavascript(
            "if(window.__cameraRotate){window.__cameraRotate($intDx, $intDy);}", null
        )
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        // 双击放大已移除，只保留双指缩放
        override fun onLongPress(e: MotionEvent) { /* 屏蔽系统长按菜单 */ }
        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
        ): Boolean {
            // 视角旋转模式下不注入方向键，避免拖动旋转时误触滚屏/移动
            if (cameraRotationEnabled) return false
            // ★ v10: 双指手势流（缩放进行中）不注入方向键，防“缩放时游戏乱动”
            if (multiTouchSeen) return false
            // 滑动手势映射为方向键（部分页游用方向键滚屏）
            if (abs(vx) > abs(vy)) {
                injectKey(if (vx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            }
            return true
        }
    }

    // ---------------- IME 输入法支持 ----------------
    /**
     * 当网页调用 NativeIme.showIme() 时，imeRequested 设为 true，
     * 系统据此认定本 View 为文本编辑器，从而弹出软键盘。
     * 正常游戏时 imeRequested=false，不影响 WebView 默认行为。
     */
    override fun onCheckIsTextEditor(): Boolean {
        if (imeRequested) return true
        return super.onCheckIsTextEditor()
    }

    /**
     * 自定义 InputConnection：捕获输入法提交的文本，
     * 通过 JavaScript KeyboardEvent 转发给网页（供 WAFlash 接收）。
     * 仅在 imeRequested=true 时启用。
     *
     * 注意：WebView 的 onCreateInputConnection() 不能返回 null（系统会 NPE 崩溃），
     * 因此 imeRequested=false 时必须委托给 super，不能 new BaseInputConnection(this, false) 顶替。
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        if (!imeRequested) {
            return base
        }
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                val textStr = text.toString()
                if (textStr.isNotEmpty()) {
                    // 转义特殊字符后通过 JS 注入到网页
                    val escaped = textStr
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "")
                    evaluateJavascript("""
                        (function(){
                          var text = '$escaped';
                          for (var i = 0; i < text.length; i++) {
                            var ch = text.charCodeAt(i);
                            var target = document.activeElement || document;
                            target.dispatchEvent(new KeyboardEvent('keydown', {key: text[i], keyCode: ch, which: ch, bubbles: true}));
                            target.dispatchEvent(new KeyboardEvent('keypress', {key: text[i], keyCode: ch, which: ch, charCode: ch, bubbles: true}));
                            target.dispatchEvent(new KeyboardEvent('keyup', {key: text[i], keyCode: ch, which: ch, bubbles: true}));
                          }
                        })();
                    """.trimIndent(), null)
                }
                return super.commitText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // 删除键：注入 Backspace 键事件
                evaluateJavascript("""
                    (function(){
                      var target = document.activeElement || document;
                      target.dispatchEvent(new KeyboardEvent('keydown', {key: 'Backspace', keyCode: 8, which: 8, bubbles: true}));
                      target.dispatchEvent(new KeyboardEvent('keyup', {key: 'Backspace', keyCode: 8, which: 8, bubbles: true}));
                    })();
                """.trimIndent(), null)
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }

    // ---------------- 键盘 ----------------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // BACK：交给 Activity 的 OnBackPressedDispatcher 处理（这里不消费）
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }
        // 游戏常用按键白名单透传给网页（其余交给系统）
        if (event.keyCode in GAME_KEYS) {
            return super.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 注入一次按键 down+up（供虚拟手柄调用）。
     * 使用 JavaScript KeyboardEvent 直接分发，比 Android dispatchKeyEvent 更可靠，
     * 避免 WebView 失焦时 keyup 丢失导致 Ruffle 角色持续移动。
     */
    fun injectKey(keyCode: Int) {
        injectKeyDown(keyCode)
        injectKeyUp(keyCode)
    }

    /**
     * 按住状态：只发 keydown（不自动 keyup）。
     * 通过 JS 按键状态管理器在 window/document/activeElement 三个目标上分发，
     * 确保 Ruffle（监听在 window 上）必定收到事件。
     */
    fun injectKeyDown(keyCode: Int) {
        pressedKeys.add(keyCode)
        val info = androidKeyToJsInfo(keyCode)
        val js = """
            $KEY_MANAGER_INIT
            window.__gameKeys.down(${info.keyCode},"${info.key}","${info.code}");
        """.trimIndent()
        evaluateJavascript(js, null)
    }

    /**
     * 松开：发 keyup。
     * 通过 JS 按键状态管理器分发，并在 50ms/150ms 后冗余重发 keyup，
     * 确保即使第一次分发丢失，Ruffle 也能收到释放事件。
     */
    fun injectKeyUp(keyCode: Int) {
        pressedKeys.remove(keyCode)
        val info = androidKeyToJsInfo(keyCode)
        val js = """
            $KEY_MANAGER_INIT
            window.__gameKeys.up(${info.keyCode},"${info.key}","${info.code}");
        """.trimIndent()
        evaluateJavascript(js, null)
    }

    /**
     * 释放所有当前按下的按键。
     * 在页面导航、Activity 暂停、手柄隐藏时调用，
     * 防止 Ruffle/Flash 引擎因漏收 keyup 导致角色持续移动。
     */
    fun releaseAllKeys() {
        val keys = synchronized(pressedKeys) {
            val copy = pressedKeys.toSet()
            pressedKeys.clear()
            copy
        }
        Log.d("GameWebView", "releaseAllKeys: ${keys.size} keys")
        // 调用 JS 管理器释放所有按键（即使 keys 为空也要调用，清理 JS 端残留状态）
        val js = """
            $KEY_MANAGER_INIT
            window.__gameKeys.releaseAll();
        """.trimIndent()
        evaluateJavascript(js, null)
    }

    // ---------------- 按键状态心跳同步 ----------------
    /** 心跳 Handler：定期同步 Android 与 JS 的按键状态，释放"卡住"的按键 */
    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            syncPressedKeys()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL)
        }
    }

    /**
     * 同步 Android 按下的按键状态到 JS。
     * JS 端会释放所有"Android 认为未按下但 JS 认为已按下"的按键，
     * 并对最近释放的按键冗余重发 keyup，彻底防止方向键卡住。
     *
     * 注意：必须把 Android KeyCode 转成 JS keyCode 再传，
     * 因为 JS 端 pressed 字典的 key 是 JS keyCode（如 39），
     * 而 pressedKeys 存的是 Android KeyCode（如 22），两者不匹配会导致
     * 心跳同步误判为"按键已松开"从而错误释放正在长按的按键。
     */
    private fun syncPressedKeys() {
        val jsKeyCodes = synchronized(pressedKeys) {
            pressedKeys.map { androidKeyToJsInfo(it).keyCode }.toIntArray()
        }
        val keysStr = jsKeyCodes.joinToString(",")
        evaluateJavascript("""
            $KEY_MANAGER_INIT
            window.__gameKeys.sync([$keysStr]);
        """.trimIndent(), null)
    }

    override fun onResume() {
        super.onResume()
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL)
    }

    override fun onPause() {
        super.onPause()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    // ---- Android KeyCode → JavaScript KeyboardEvent 映射 ----

    private data class JsKeyInfo(val key: String, val code: String, val keyCode: Int)

    /** 将 Android KeyEvent.KEYCODE_* 映射为 JavaScript KeyboardEvent 所需的 key/code/keyCode */
    private fun androidKeyToJsInfo(keyCode: Int): JsKeyInfo = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP    -> JsKeyInfo("ArrowUp",    "ArrowUp",    38)
        KeyEvent.KEYCODE_DPAD_DOWN  -> JsKeyInfo("ArrowDown",  "ArrowDown",  40)
        KeyEvent.KEYCODE_DPAD_LEFT  -> JsKeyInfo("ArrowLeft",  "ArrowLeft",  37)
        KeyEvent.KEYCODE_DPAD_RIGHT -> JsKeyInfo("ArrowRight", "ArrowRight", 39)
        KeyEvent.KEYCODE_DPAD_CENTER-> JsKeyInfo("Enter",      "Enter",      13)
        KeyEvent.KEYCODE_SPACE      -> JsKeyInfo(" ",          "Space",      32)
        KeyEvent.KEYCODE_ENTER      -> JsKeyInfo("Enter",      "Enter",      13)
        KeyEvent.KEYCODE_TAB        -> JsKeyInfo("Tab",        "Tab",         9)
        KeyEvent.KEYCODE_ESCAPE     -> JsKeyInfo("Escape",     "Escape",     27)
        KeyEvent.KEYCODE_CTRL_LEFT  -> JsKeyInfo("Control",    "ControlLeft",17)
        KeyEvent.KEYCODE_CTRL_RIGHT -> JsKeyInfo("Control",    "ControlRight",17)
        KeyEvent.KEYCODE_SHIFT_LEFT -> JsKeyInfo("Shift",      "ShiftLeft",  16)
        KeyEvent.KEYCODE_SHIFT_RIGHT-> JsKeyInfo("Shift",      "ShiftRight", 16)
        KeyEvent.KEYCODE_ALT_LEFT   -> JsKeyInfo("Alt",        "AltLeft",    18)
        KeyEvent.KEYCODE_ALT_RIGHT  -> JsKeyInfo("Alt",        "AltRight",   18)
        // WASD + 功能字母
        KeyEvent.KEYCODE_A -> JsKeyInfo("a","KeyA",65)
        KeyEvent.KEYCODE_B -> JsKeyInfo("b","KeyB",66)
        KeyEvent.KEYCODE_C -> JsKeyInfo("c","KeyC",67)
        KeyEvent.KEYCODE_D -> JsKeyInfo("d","KeyD",68)
        KeyEvent.KEYCODE_E -> JsKeyInfo("e","KeyE",69)
        KeyEvent.KEYCODE_F -> JsKeyInfo("f","KeyF",70)
        KeyEvent.KEYCODE_G -> JsKeyInfo("g","KeyG",71)
        KeyEvent.KEYCODE_H -> JsKeyInfo("h","KeyH",72)
        KeyEvent.KEYCODE_I -> JsKeyInfo("i","KeyI",73)
        KeyEvent.KEYCODE_J -> JsKeyInfo("j","KeyJ",74)
        KeyEvent.KEYCODE_K -> JsKeyInfo("k","KeyK",75)
        KeyEvent.KEYCODE_L -> JsKeyInfo("l","KeyL",76)
        KeyEvent.KEYCODE_M -> JsKeyInfo("m","KeyM",77)
        KeyEvent.KEYCODE_N -> JsKeyInfo("n","KeyN",78)
        KeyEvent.KEYCODE_O -> JsKeyInfo("o","KeyO",79)
        KeyEvent.KEYCODE_P -> JsKeyInfo("p","KeyP",80)
        KeyEvent.KEYCODE_Q -> JsKeyInfo("q","KeyQ",81)
        KeyEvent.KEYCODE_R -> JsKeyInfo("r","KeyR",82)
        KeyEvent.KEYCODE_S -> JsKeyInfo("s","KeyS",83)
        KeyEvent.KEYCODE_T -> JsKeyInfo("t","KeyT",84)
        KeyEvent.KEYCODE_U -> JsKeyInfo("u","KeyU",85)
        KeyEvent.KEYCODE_V -> JsKeyInfo("v","KeyV",86)
        KeyEvent.KEYCODE_W -> JsKeyInfo("w","KeyW",87)
        KeyEvent.KEYCODE_X -> JsKeyInfo("x","KeyX",88)
        KeyEvent.KEYCODE_Y -> JsKeyInfo("y","KeyY",89)
        KeyEvent.KEYCODE_Z -> JsKeyInfo("z","KeyZ",90)
        // 数字
        KeyEvent.KEYCODE_0 -> JsKeyInfo("0","Digit0",48)
        KeyEvent.KEYCODE_1 -> JsKeyInfo("1","Digit1",49)
        KeyEvent.KEYCODE_2 -> JsKeyInfo("2","Digit2",50)
        KeyEvent.KEYCODE_3 -> JsKeyInfo("3","Digit3",51)
        KeyEvent.KEYCODE_4 -> JsKeyInfo("4","Digit4",52)
        KeyEvent.KEYCODE_5 -> JsKeyInfo("5","Digit5",53)
        KeyEvent.KEYCODE_6 -> JsKeyInfo("6","Digit6",54)
        KeyEvent.KEYCODE_7 -> JsKeyInfo("7","Digit7",55)
        KeyEvent.KEYCODE_8 -> JsKeyInfo("8","Digit8",56)
        KeyEvent.KEYCODE_9 -> JsKeyInfo("9","Digit9",57)
        else -> JsKeyInfo("", "Unidentified", keyCode)
    }

    // ---------------- 鼠标事件注入 ----------------

    /** 在 WebView 中心坐标注入鼠标移动事件（用于旋转视角） */
    fun injectMouseMove(dx: Float, dy: Float) {
        // 通过 JS 注入 mousemove 事件，模拟鼠标在页面中心移动
        val cx = width / 2f
        val cy = height / 2f
        val newX = cx + dx
        val newY = cy + dy
        evaluateJavascript(
            """
            (function(){
              var el = document.elementFromPoint($cx, $cy);
              if (!el) return;
              var evt = new MouseEvent('mousemove', {
                bubbles: true, cancelable: true, view: window,
                clientX: $newX, clientY: $newY,
                movementX: $dx, movementY: $dy
              });
              el.dispatchEvent(evt);
            })();
            """.trimIndent(), null
        )
    }

    /** 注入鼠标左键点击（mousedown + mouseup + click） */
    fun injectMouseLeftClick(x: Float = width / 2f, y: Float = height / 2f) {
        evaluateJavascript(
            """
            (function(){
              var el = document.elementFromPoint($x, $y);
              if (!el) return;
              var opt = {bubbles: true, cancelable: true, view: window, clientX: $x, clientY: $y, button: 0};
              el.dispatchEvent(new MouseEvent('mousedown', opt));
              el.dispatchEvent(new MouseEvent('mouseup', opt));
              el.dispatchEvent(new MouseEvent('click', opt));
            })();
            """.trimIndent(), null
        )
    }

    /** 注入鼠标右键点击（contextmenu） */
    fun injectMouseRightClick(x: Float = width / 2f, y: Float = height / 2f) {
        evaluateJavascript(
            """
            (function(){
              var el = document.elementFromPoint($x, $y);
              if (!el) return;
              var opt = {bubbles: true, cancelable: true, view: window, clientX: $x, clientY: $y, button: 2};
              el.dispatchEvent(new MouseEvent('mousedown', opt));
              el.dispatchEvent(new MouseEvent('mouseup', opt));
              el.dispatchEvent(new MouseEvent('contextmenu', opt));
            })();
            """.trimIndent(), null
        )
    }

    /** 注入鼠标左键按下（持续按住状态） */
    fun injectMouseLeftDown(x: Float = width / 2f, y: Float = height / 2f) {
        evaluateJavascript(
            """
            (function(){
              var el = document.elementFromPoint($x, $y);
              if (!el) return;
              el.dispatchEvent(new MouseEvent('mousedown', {
                bubbles: true, cancelable: true, view: window, clientX: $x, clientY: $y, button: 0
              }));
            })();
            """.trimIndent(), null
        )
    }

    /** 注入鼠标左键松开 */
    fun injectMouseLeftUp(x: Float = width / 2f, y: Float = height / 2f) {
        evaluateJavascript(
            """
            (function(){
              var el = document.elementFromPoint($x, $y);
              if (!el) return;
              el.dispatchEvent(new MouseEvent('mouseup', {
                bubbles: true, cancelable: true, view: window, clientX: $x, clientY: $y, button: 0
              }));
            })();
            """.trimIndent(), null
        )
    }

    companion object {
        /** 心跳同步间隔（ms）：定期检查并释放"卡住"的按键 */
        private const val HEARTBEAT_INTERVAL = 300L

        /**
         * Document Start 脚本：通过 addDocumentStartJavaScript 在页面任何 JS 之前执行。
         *
         * 核心功能：
         * 1. View Transitions API polyfill — 替换 document.startViewTransition，
         *    确保 SPA 导航回调始终执行，避免 "Transition was skipped" 导致页面无法跳转。
         *    必须在页面 JS 之前注入，否则网站框架可能已捕获原始引用。
         * 2. navigator.plugins 兜底 — 确保任何页面（含 iframe）的 navigator.plugins
         *    都有 namedItem/item 方法，避免 Ruffle 引擎崩溃。
         */
        private const val DOCUMENT_START_SCRIPT = """
            (function(){
              // === 1. View Transitions API polyfill ===
              // 问题：Android WebView 的 startViewTransition 可能直接跳过过渡
              // （Transition was skipped），导致回调不执行、页面无法跳转。
              // 方案：彻底替换 startViewTransition，确保回调始终同步执行。
              // 关键：必须覆盖 Document.prototype（而非仅 document 实例），
              //       因为 WebView 可能通过原型 getter 返回原生实现，覆盖实例无效。
              if (!window.__vtPatched) {
                window.__vtPatched = true;
                var vtPolyfill = function(callback) {
                  var result;
                  try {
                    result = callback ? callback() : undefined;
                  } catch(e) {
                    result = Promise.reject(e);
                  }
                  var p = (result && typeof result.then === 'function') ? result : Promise.resolve();
                  // 强制触发重绘：原生 startViewTransition 会触发渲染帧，
                  // polyfill 仅同步执行回调，WebView 不知道 DOM 已更新需要重绘。
                  // 不触发重绘 → 页面"卡住"，切后台再回来 onResume 强制重绘才恢复。
                  requestAnimationFrame(function() {
                    try {
                      void document.body && document.body.offsetHeight;
                    } catch(e) {}
                  });
                  var finished = p.then(undefined, function(err) {
                    if (err && err.name === 'AbortError') return;
                    throw err;
                  });
                  return {
                    finished: finished,
                    ready: Promise.resolve(),
                    updateCallbackDone: p,
                    skipTransition: function() {},
                    types: []
                  };
                };
                // 覆盖 Document.prototype（最优先：阻止原生 getter 返回原始实现）
                try {
                  Object.defineProperty(Document.prototype, 'startViewTransition', {
                    value: vtPolyfill,
                    writable: true,
                    configurable: true
                  });
                } catch(e) {}
                // 同时覆盖 document 实例（双重保险）
                try { document.startViewTransition = vtPolyfill; } catch(e) {}
              }
              // === 2. 捕获 unhandledrejection：抑制 View Transitions 的 AbortError ===
              if (!window.__vtRejectionHook) {
                window.__vtRejectionHook = true;
                window.addEventListener('unhandledrejection', function(event) {
                  if (event.reason && event.reason.name === 'AbortError') {
                    var msg = event.reason.message || String(event.reason);
                    if (msg.indexOf('Transition') >= 0 || msg.indexOf('skipped') >= 0 || msg === 'AbortError') {
                      event.preventDefault();
                    }
                  }
                });
              }
              // === 3. navigator.plugins 兜底：确保 namedItem/item 方法存在 ===
              try {
                var np = navigator.plugins;
                if (np && typeof np.namedItem !== 'function') {
                  np.namedItem = function(name) {
                    return (name === 'Shockwave Flash') ? { name: name, length: 1, 0: { type: 'application/x-shockwave-flash' } } : null;
                  };
                }
                if (np && typeof np.item !== 'function') {
                  np.item = function(i) { return i === 0 ? { name: 'Shockwave Flash' } : null; };
                }
              } catch(e) {}
            })();
        """

        /**
         * JavaScript 按键状态管理器初始化脚本（自初始化：仅在 window.__gameKeys 不存在时创建）。
         *
         * 核心机制：
         * 1. 在 window / document / activeElement 三个目标上分发 KeyboardEvent，
         *    确保 Ruffle（监听在 window 上）必定收到 keydown/keyup
         * 2. 维护 pressed 字典（keyCode → {key, code}），跟踪已按下按键
         * 3. up() 时在 0ms / 50ms / 150ms 冗余重发 keyup，防止首次分发丢失
         * 4. sync(androidKeys) 与 Android 端对账：释放 Android 认为未按下但 JS 认为已按下的按键，
         *    并对最近 500ms 内释放的按键冗余重发 keyup
         * 5. 页面 blur / visibilitychange 时自动释放所有按键
         */
        private val KEY_MANAGER_INIT = """
            if(!window.__gameKeys){
              window.__gameKeys=(function(){
                var pressed={};
                var recentlyReleased={};
                function mkEv(type,kc,kv,cv){
                  var e=new KeyboardEvent(type,{key:kv,code:cv,bubbles:true,cancelable:true,composed:true});
                  Object.defineProperty(e,'keyCode',{get:function(){return kc;}});
                  Object.defineProperty(e,'which',{get:function(){return kc;}});
                  Object.defineProperty(e,'charCode',{get:function(){return 0;}});
                  return e;
                }
                function dispatch(type,kc,kv,cv){
                  var ae=document.activeElement||document.body||document.documentElement;
                  try{ae.dispatchEvent(mkEv(type,kc,kv,cv));}catch(e){}
                  try{if(ae!==document)document.dispatchEvent(mkEv(type,kc,kv,cv));}catch(e){}
                  try{window.dispatchEvent(mkEv(type,kc,kv,cv));}catch(e){}
                }
                function down(kc,kv,cv){
                  pressed[kc]={key:kv,code:cv};
                  delete recentlyReleased[kc];
                  dispatch('keydown',kc,kv,cv);
                }
                function up(kc,kv,cv){
                  var info=pressed[kc];
                  var akv=kv||(info?info.key:'');
                  var acv=cv||(info?info.code:'Unidentified');
                  delete pressed[kc];
                  recentlyReleased[kc]={key:akv,code:acv,time:Date.now()};
                  dispatch('keyup',kc,akv,acv);
                  setTimeout(function(){if(!pressed[kc])dispatch('keyup',kc,akv,acv);},50);
                  setTimeout(function(){if(!pressed[kc])dispatch('keyup',kc,akv,acv);},150);
                }
                function releaseAll(){
                  for(var kc in pressed){
                    var info=pressed[kc];
                    dispatch('keyup',parseInt(kc),info.key,info.code);
                    recentlyReleased[kc]={key:info.key,code:info.code,time:Date.now()};
                  }
                  pressed={};
                }
                function sync(androidKeys){
                  var aSet={};
                  if(androidKeys){for(var i=0;i<androidKeys.length;i++)aSet[androidKeys[i]]=true;}
                  var now=Date.now();
                  for(var kc in pressed){
                    if(!aSet[kc]){
                      var info=pressed[kc];
                      dispatch('keyup',parseInt(kc),info.key,info.code);
                      recentlyReleased[kc]={key:info.key,code:info.code,time:now};
                      delete pressed[kc];
                    }
                  }
                  for(var kc in recentlyReleased){
                    if(!aSet[kc]&&!pressed[kc]){
                      var info=recentlyReleased[kc];
                      if(now-info.time<500){dispatch('keyup',parseInt(kc),info.key,info.code);}
                      else{delete recentlyReleased[kc];}
                    }else{delete recentlyReleased[kc];}
                  }
                }
                window.addEventListener('blur',function(){releaseAll();});
                document.addEventListener('visibilitychange',function(){if(document.hidden)releaseAll();});
                return{down:down,up:up,releaseAll:releaseAll,sync:sync};
              })();
            }
        """.trimIndent()

        /** 桌面版 Chrome UA（Windows），不含 Mobile/Android，4399 据此返回 PC 版页面 */
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * ★ v10: 双指缩放解锁脚本 —— 剥离 viewport 的 user-scalable=no / 放开
         * maximum-scale 钳制。大量游戏门户（4399 等）在 meta 里写死 no-zoom，
         * 导致 WebView 原生捏合完全失效（这是“双指缩放不生效”的主根因）。
         * 幂等（__pinchUnlock 守卫）+ MutationObserver 持续监听（页面脚本可在
         * 加载后插入/改写 meta），扫描现存 meta 并改写其 content。
         * 注入点：Document Start（页面 meta 解析前挂观察器）+ onPageStarted 兜底。
         */
        const val PINCH_UNLOCK_SCRIPT = """
            (function(){
              if (window.__pinchUnlock) return; window.__pinchUnlock = true;
              function unlockMeta(m){
                // ★ v11: WebPinchLayout 缩放接管期间（window.__az 活跃）不得碰 meta ——
                //   接管域的 minimum-scale=maximum-scale 是缩放系统的唯一事实源，
                //   此处若把 maximum-scale "解锁"成 10 会让两套系统互踩（缩放跳变）。
                if (window.__az) return;
                try{
                  var c = m.getAttribute('content') || '';
                  if(!c) return;
                  var orig = c;
                  c = c.replace(/user-scalable\s*=\s*no/gi, 'user-scalable=yes');
                  c = c.replace(/maximum-scale\s*=\s*([0-9.]+)\s*(,|$)/gi, function(mm, v, tail){
                    return (parseFloat(v) < 10) ? ('maximum-scale=10.0' + tail) : mm;
                  });
                  if (c !== orig) m.setAttribute('content', c);
                }catch(e){}
              }
              function scanAll(){
                if (window.__az) return;
                try{
                  var metas = document.querySelectorAll('meta[name="viewport"]');
                  for(var i=0;i<metas.length;i++) unlockMeta(metas[i]);
                }catch(e){}
              }
              scanAll();
              try{
                if (window.MutationObserver){
                  var mo = new MutationObserver(function(muts){
                    for (var i=0;i<muts.length;i++){
                      var m = muts[i];
                      if (m.type === 'attributes'){
                        var t = m.target;
                        if (t && t.tagName === 'META' && (t.getAttribute('name')||'').toLowerCase() === 'viewport') unlockMeta(t);
                      } else if (m.addedNodes && m.addedNodes.length){
                        for (var j=0;j<m.addedNodes.length;j++){
                          var n = m.addedNodes[j];
                          if (n && n.tagName === 'META' && (n.getAttribute('name')||'').toLowerCase() === 'viewport') unlockMeta(n);
                        }
                      }
                    }
                  });
                  mo.observe(document.documentElement || document, {childList:true, subtree:true, attributes:true, attributeFilter:['content']});
                }
              }catch(e){}
            })();
        """

        /** IE11 兼容模式 UA（Trident/7.0），4399 部分页面需要此 UA 才能正常加载 */
        const val IE_COMPAT_UA = "Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko"

        /**
         * ★ v14: 桌面/兼容模式视口拉宽 + 客户端提示伪装 —— useUaMode/useDesktopMode
         * 的配套 Document Start 脚本。只改 UA 请求头对两类站点无效（用户实测
         * “电脑模式还是手机版网页”）：
         *   ① 响应式站点：CSS 断点看视口宽度，width=device-width 的 meta 把布局
         *      锁死在手机宽度 → 桌面 UA 也渲染手机版；
         *   ② 客户端探测：WebView 的 Sec-CH-UA-Mobile 客户端提示不受
         *      setUserAgentString 覆盖影响（仍上报 ?1/Android 平台），现代站点
         *      navigator.userAgentData.mobile === true 照样切移动版。
         * 本脚本在页面任何 JS 之前执行，读当前 navigator.userAgent（Kotlin 侧已
         * 覆盖）自适应判定：桌面/IE UA → ① viewport 的 device-width 改写为 1280
         * 并去掉 initial-scale（布局视口拉宽 + loadWithOverviewMode 自动缩小 =
         * 真·桌面版式）；② navigator.userAgentData 伪装为 Windows/非移动、
         * navigator.platform=Win32。移动 UA → 什么都不做（缩放解锁仍归
         * PINCH_UNLOCK_SCRIPT）。与 WebPinchLayout 缩放仲裁共存：window.__az
         * 活跃期间不碰 meta。幂等（__uaDesk 守卫）+ MutationObserver 监听
         * 页面后插/改写 meta。服务端 Accept-CH 探测无法在 JS 层拦截（公版
         * WebView 无该 API），该场景由 UA 请求头本身覆盖。
         */
        const val UA_DESKTOP_SPOOF_SCRIPT = """
            (function(){
              if (window.__uaDesk) return; window.__uaDesk = true;
              var ua = '';
              try { ua = navigator.userAgent || ''; } catch(e) {}
              var isDesk = /Windows NT/.test(ua) || /Trident\/7/.test(ua) ||
                           /Macintosh/.test(ua) || /X11/.test(ua);
              if (!isDesk) return;
              // --- 1. navigator.userAgentData / platform 客户端提示伪装 ---
              try {
                var deskUAD = {
                  brands: [
                    { brand: 'Not_A Brand', version: '8' },
                    { brand: 'Chromium', version: '120' },
                    { brand: 'Google Chrome', version: '120' }
                  ],
                  mobile: false,
                  platform: 'Windows',
                  getHighEntropyValues: function(hints) {
                    return Promise.resolve({
                      architecture: 'x86', bitness: '64', mobile: false, model: '',
                      platform: 'Windows', platformVersion: '15.0.0',
                      uaFullVersion: '120.0.0.0',
                      fullVersionList: [
                        { brand: 'Chromium', version: '120.0.0.0' },
                        { brand: 'Google Chrome', version: '120.0.0.0' }
                      ]
                    });
                  }
                };
                try {
                  Object.defineProperty(Navigator.prototype, 'userAgentData', {
                    get: function() { return deskUAD; },
                    configurable: true
                  });
                } catch(e) {
                  try {
                    Object.defineProperty(navigator, 'userAgentData', {
                      get: function() { return deskUAD; },
                      configurable: true
                    });
                  } catch(e2) {}
                }
                try {
                  Object.defineProperty(navigator, 'platform', {
                    value: 'Win32', configurable: true
                  });
                } catch(e) {}
              } catch(e) {}
              // --- 2. viewport 桌面化：device-width → 1280，去 initial-scale ---
              function deskView(m){
                if (window.__az) return;
                try {
                  var c = m.getAttribute('content') || '';
                  if (!c) return;
                  var orig = c;
                  c = c.replace(/width\s*=\s*["']?device-width["']?/gi, 'width=1280');
                  c = c.replace(/,?\s*initial-scale\s*=\s*[0-9.]+/gi, '');
                  if (c !== orig) m.setAttribute('content', c);
                } catch(e) {}
              }
              function scanAll(){
                try {
                  var metas = document.querySelectorAll('meta[name="viewport"]');
                  for (var i = 0; i < metas.length; i++) deskView(metas[i]);
                } catch(e) {}
              }
              scanAll();
              try {
                if (window.MutationObserver) {
                  var mo = new MutationObserver(function(muts){
                    for (var i = 0; i < muts.length; i++) {
                      var m = muts[i];
                      if (m.type === 'attributes') {
                        var t = m.target;
                        if (t && t.tagName === 'META' &&
                        (t.getAttribute('name') || '').toLowerCase() === 'viewport') deskView(t);
                      } else if (m.addedNodes && m.addedNodes.length) {
                        for (var j = 0; j < m.addedNodes.length; j++) {
                          var n = m.addedNodes[j];
                          if (n && n.tagName === 'META' &&
                          (n.getAttribute('name') || '').toLowerCase() === 'viewport') deskView(n);
                        }
                      }
                    }
                  });
                  mo.observe(document.documentElement || document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
                }
              } catch(e) {}
            })();
        """

        /** 游戏常用按键白名单 */
        val GAME_KEYS = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            // WASD
            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D,
            // 功能字母
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V,
            // 动作按键常用字母（J/K/L/U/I/O/H/G/B/N/P/M/T/Y）
            KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L,
            KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O,
            KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_B,
            KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_M,
            KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y,
            // 数字
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9
        )
    }
}
