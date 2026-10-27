package com.nesstation.app.flash.webview

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.documentfile.provider.DocumentFile
import com.nesstation.app.flash.data.PrefsManager
import java.io.ByteArrayInputStream

/**
 * 游戏页面 WebView 客户端：
 * 1. onPageFinished 注入 Flash 引擎（Ruffle / swf2js / WAFlash）
 * 2. 拦截 .swf 链接 → 跳转内置播放器页
 * 3. shouldInterceptRequest：拦截 flash.local 虚拟域名，从 assets 返回引擎文件
 * 4. 错误回调（仅主框架错误才显示错误页）
 */
open class GameWebViewClient(
    private val callback: Callback
) : WebViewClient() {

    interface Callback {
        // ---------- ★ v12.1 兼容层（v11 老签名，带默认实现）----------
        // 用户仓库里的 SWF 播放器（ui/swf/SwfPlayerScreen.kt）按 v11 老签名
        // override 这些成员（无 view 参数）。v12 只把签名换成带 view 的新签名、
        // 且仅核对了 WebGameScreen，漏了仓库内其他实现者 → CI 报
        // "'onPageStarted' overrides nothing" / "does not implement abstract member"。
        // v12.1 让新老签名并存、全部带默认体：老实现者编译恢复、行为与 v11 一致；
        // 新实现者（浏览器多标签宿主）继续 override 带 view 版本实现按标签门控。
        fun onPageStarted(url: String?) {}
        fun onPageFinished(url: String?) {}
        fun onSwfIntercepted(swfUrl: String, pageUrl: String) {}
        /** ★ v12: 带 view —— 多标签下后台标签导航不串台（宿主按活动标签门控）；
         *  默认向下委托老签名（只实现老签名的宿主行为不变） */
        fun onPageStarted(view: WebView?, url: String?) = onPageStarted(url)
        fun onPageFinished(view: WebView?, url: String?) = onPageFinished(url)
        fun onProgress(progress: Int)
        fun onError(url: String?, errorCode: Int, description: String?)
        /** ★ v12: 带 view —— SWF 在拦截它的那个标签内播放，不串台 */
        fun onSwfIntercepted(view: WebView?, swfUrl: String, pageUrl: String) =
            onSwfIntercepted(swfUrl, pageUrl)
        fun shouldInjectRuffle(url: String?): Boolean
        /** 获取 WAFlash 预下载的 SWF 缓存文件路径 */
        fun getCachedSwfPath(): String?
        /** 获取本地 SWF 文件的真实 URI */
        fun getLocalSwfUri(): String?
        /** 获取本地 SWF 所在的目录路径（用于多 SWF 资源加载） */
        fun getLocalSwfDir(): String?
        /** ★ v10: 渲染进程死亡（不接管则 Chromium 会杀整个应用进程）——
         *   宿主销毁死视图并重建（主窗口 / 弹窗）。默认空实现（防御性）。 */
        fun onRendererGone(deadView: WebView?) {}
    }

    /**
     * 常见广告/统计域名(参考 gamehtml-3.0,不再使用——adHosts/adSwfPaths 已废弃)
     * 保留声明供 shouldOverrideUrlLoading 等地方用。
     */
    @Suppress("unused")
    private val adHosts = emptySet<String>()

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false
        val url = uri.toString()
        if (url.endsWith(".swf", ignoreCase = true)) {
            callback.onSwfIntercepted(view, url, view.url ?: url)
            return true
        }
        // ★ v10: 非网页 scheme 分流（参照 AnWind）—— magnet/ed2k/thunder/weixin/
        //   alipays/intent:// 等第三方协议交给系统 Intent；白名单 scheme 内继续
        //   由 WebView 自行加载。此前这类链接要么无反应要么报错。
        val scheme = uri.scheme?.lowercase()
        val webSchemes = setOf("http", "https", "file", "content", "data", "about", "javascript", "blob")
        if (scheme != null && scheme !in webSchemes) {
            try {
                val intent = if (scheme == "intent")
                    android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME)
                else
                    android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                view.context.startActivity(intent)
            } catch (_: Exception) {
                // 未安装可处理应用 / 无 Activity 可唤起 → 静默忽略（页内广告/唤起链接常见）
            }
            return true
        }
        return false
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null

        // 1. 广告拦截已废弃:之前返回空 SWF 导致 WAFlash 解析空 SWF 死锁。
        //    现在让广告 SWF 走下面的 interceptSwf 路径(走 HTTPS 自己下载,失败 404 兜底),
        //    参考 gamehtml-3.0 实现。引擎收到 404+CORS 头立即报错,不无限等待。

        // 2. 拦截 flash.local 虚拟域名
        if (url.contains("flash.local")) {
            val path = url.substringAfter("flash.local/").substringBefore("?")
            // 本地 SWF 代理：flash.local/local.swf → 读取真实文件
            if (path == "local.swf") {
                return interceptLocalSwfProxy(view)
            }
            // Multi-SWF: check if this is a resource file from the local SWF's directory
            val localSwfDir = callback.getLocalSwfDir()
            if (localSwfDir != null && path != "local.swf" &&
                !path.startsWith("ruffle/") && !path.startsWith("waflash/") &&
                path != "player.html" && path != "waflash.html") {
                // Try to serve from local directory; fall through to assets if not found
                val resourceResponse = interceptLocalResource(view, localSwfDir, path)
                if (resourceResponse != null) return resourceResponse
            }
            return interceptAsset(view, path)
        }

        // 3. 拦截 file:///android_asset/waflash/ 请求
        if (url.startsWith("file:///android_asset/waflash/")) {
            val assetPath = url.removePrefix("file:///android_asset/").substringBefore("?")
            return interceptAsset(view, assetPath)
        }

        // 4. 拦截本地 SWF 文件（content:// 或 file://）
        if (url.startsWith("content://") || url.startsWith("file://")) {
            return interceptLocalFile(view, url)
        }

        // 5. 拦截远程 SWF 文件请求
        //    Ruffle/WAFlash 加载 SWF 时需要原生下载（添加 CORS 头 + Cookie 转发）
        //    只拦截真正的 SWF 资源请求，不影响普通网页浏览
        val isSwfRequest = url.endsWith(".swf", ignoreCase = true) ||
            url.contains(".swf?", ignoreCase = true) ||
            (url.contains("4399.com") && (url.contains("/dw-") || url.contains("flash_tm3") || url.contains("flash20")))
        if (isSwfRequest) {
            return interceptSwf(url, request)
        }

        // 不拦截 HTML 页面！
        // View Transitions polyfill 通过 addDocumentStartJavaScript（Document Start 注入）
        // 和 onPageStarted 兜底注入实现，无需 HTML 拦截。
        // HTML 拦截会导致：编码问题、cookie/session 丢失、缓存/重定向破坏。

        return super.shouldInterceptRequest(view, request)
    }

    /** 构建 Flash 支持伪造 + Ruffle/WAFlash 注入脚本（公开供 GameActivity 在 onPageFinished 兜底使用） */
    fun buildFlashInjectScript(pageUrl: String): String {
        val isWaflash = PrefsManager.flashEngine == "waflash"
        // 从用户设置读取 scale/letterbox，未设置时走 Ruffle 官方默认。
        // 合法值参考 ruffle core/src/config.rs:42-50 (letterbox) +
        //                 ruffle core/src/display_object/stage.rs:1020-1023 (scale)
        val rawScale = PrefsManager.sp.getString("flash_scale", null)
        val scale = when (rawScale) {
            "showAll", "noBorder", "exactFit", "noScale" -> rawScale
            else -> "showAll"
        }
        val rawLetterbox = PrefsManager.sp.getString("flash_letterbox", null)
        val letterbox = when (rawLetterbox) {
            "off", "fullscreen", "on" -> rawLetterbox
            else -> "on" // 内联注入默认 on，保证缩放语义与 buildRuffleAspectRatioScript 配合
        }
        // 防御性 fallback：scale 必须是非 null 字符串
        val safeScale = scale
        val quality = PrefsManager.flashQuality
        return """
        <script>
        (function(){
          if(window.__flashPolyfilled)return;window.__flashPolyfilled=true;
          // === 1. 伪造 Flash 插件支持（必须在页面 JS 之前） ===
          try {
            var fp = {name:'Shockwave Flash',filename:'libflashplayer.so',description:'Shockwave Flash 32.0 r0',length:1,
              0:{type:'application/x-shockwave-flash',suffixes:'swf',description:'Shockwave Flash'}};
            fp.namedItem = function(n){ return (n === 'Shockwave Flash') ? fp : null; };
            fp.item = function(i){ return i === 0 ? fp : null; };
            fp.refresh = function(){};
            var _plugins = navigator.plugins || {};
            // 保持原有方法
            if (_plugins.namedItem) { fp.namedItem = function(n){ return (n === 'Shockwave Flash') ? fp : _plugins.namedItem.call(_plugins, n); }; }
            if (_plugins.item) { fp.item = function(i){ return i === 0 ? fp : _plugins.item.call(_plugins, i); }; }
            Object.defineProperty(navigator,'plugins',{
              get:function(){
                try {
                  if (!_plugins['Shockwave Flash']) {
                    _plugins['Shockwave Flash'] = fp;
                    _plugins[0] = fp;
                  }
                  _plugins.length = Math.max(_plugins.length || 0, 1);
                } catch(e) {}
                return _plugins;
              },
              configurable: true
            });
            var fm = {type:'application/x-shockwave-flash',suffixes:'swf',description:'Shockwave Flash',enabledPlugin:fp};
            var _mimes = navigator.mimeTypes || {};
            Object.defineProperty(navigator,'mimeTypes',{
              get:function(){
                try { if (!_mimes['application/x-shockwave-flash']) _mimes['application/x-shockwave-flash'] = fm; } catch(e) {}
                return _mimes;
              },
              configurable: true
            });
            window.ActiveXObject = function(n){if(n&&/ShockwaveFlash/i.test(n))return {SetVariable:function(){},Variable:function(){return ''}};throw new Error('x');};
          } catch(e) {}

          // === 2. 伪造 document.referrer ===
          try {
            Object.defineProperty(document,'referrer',{get:function(){return '$pageUrl';},configurable:true});
          } catch(e) {}

          // === 3. Ruffle polyfill（Ruffle 模式） ===
          ${if (!isWaflash) """
          window.RufflePlayer = window.RufflePlayer || {};
          window.RufflePlayer.config = {
            autoplay: 'on',
            unmuteOverlay: 'visible',
            backgroundColor: '#000000',
            letterbox: '$letterbox',
            polyfills: true,
            maxExecutionDuration: 30,
            logLevel: 'warn',
            scale: '$safeScale',
            quality: '$quality',
            forceScale: true
          };
          var ruffleScript = document.createElement('script');
          ruffleScript.src = 'https://flash.local/ruffle/ruffle.js';
          ruffleScript.onload = function() {
            console.log('[Ruffle] 引擎加载完成');
          };
          document.head.appendChild(ruffleScript);
          """ else """
          // === WAFlash 模式：hook Flash 创建，跳转到 WAFlash 播放器 ===
          var __wafRedirected = false;
          function __wafRedirect(swfUrl, baseUrl) {
            if (__wafRedirected || !swfUrl) return;
            var isSwf = /\.swf/i.test(swfUrl) || /\/dw-\d+/i.test(swfUrl) ||
                        /flash\d*\//i.test(swfUrl) || /mm\.4399\.com/i.test(swfUrl);
            if (!isSwf) return;
            __wafRedirected = true;
            try { swfUrl = new URL(swfUrl, baseUrl || window.location.href).href; } catch(e) {}
            console.log('[WAFlash] 检测到 SWF: ' + swfUrl);
            if (window.Android && window.Android.openSwf) {
              window.Android.openSwf(swfUrl, baseUrl || window.location.href);
            } else {
              window.location.href = 'https://flash.local/waflash.html?swf=' + encodeURIComponent(swfUrl);
            }
          }
          // hook swfobject.embedSWF
          if (window.swfobject && window.swfobject.embedSWF) {
            var oe = window.swfobject.embedSWF;
            window.swfobject.embedSWF = function(){__wafRedirect(arguments[0],window.location.href);return oe.apply(this,arguments);};
          } else {
            var _swo; Object.defineProperty(window,'swfobject',{configurable:true,get:function(){return _swo;},set:function(v){_swo=v;if(v&&v.embedSWF){var o=v.embedSWF;v.embedSWF=function(){__wafRedirect(arguments[0],window.location.href);return o.apply(this,arguments);};}}});
          }
          // hook createFlash (mflash-player)
          function __hookCF(obj){if(!obj||!obj.createFlash||obj.__wafH)return;obj.__wafH=true;var o=obj.createFlash;obj.createFlash=function(){var u=null;if(typeof arguments[0]==='string')u=arguments[0];else if(arguments[0]&&typeof arguments[0]==='object')u=arguments[0].url||arguments[0].src||arguments[0].swf||arguments[0].movie;if(u)__wafRedirect(u,window.location.href);return o.apply(this,arguments);};}
          if(window['mflash-player'])__hookCF(window['mflash-player']);
          var _mp;Object.defineProperty(window,'mflash-player',{configurable:true,get:function(){return _mp;},set:function(v){_mp=v;__hookCF(v);}});
          // hook AC_FL_RunContent
          if(window.AC_FL_RunContent){var oAC=window.AC_FL_RunContent;window.AC_FL_RunContent=function(){var a=Array.prototype.slice.call(arguments);for(var i=0;i<a.length-1;i++){if((a[i]==='src'||a[i]==='movie')&&a[i+1])__wafRedirect(a[i+1],window.location.href);}return oAC.apply(this,arguments);};}
          // DOM 检测
          function __checkFlash(){
            if(__wafRedirected)return;
            var sel='object[type="application/x-shockwave-flash"],embed[type="application/x-shockwave-flash"],object[data$=".swf" i],embed[src$=".swf" i],object[classid*="D27CDB6E" i]';
            var els=document.querySelectorAll(sel);
            for(var i=0;i<els.length;i++){var s=els[i].getAttribute('data')||els[i].getAttribute('src')||'';if(!s){var ps=els[i].querySelectorAll('param[name="movie"],param[name="src"]');for(var j=0;j<ps.length;j++){var v=ps[j].getAttribute('value')||'';if(v){s=v;break;}}}if(s)__wafRedirect(s,window.location.href);}
          }
          __checkFlash();
          if(window.MutationObserver){var mo=new MutationObserver(function(){if(__wafRedirected){mo.disconnect();return;}__checkFlash();});try{mo.observe(document.documentElement||document.body||document,{childList:true,subtree:true});}catch(e){}setTimeout(function(){mo.disconnect();},15000);}
          """}
        })();
        </script>
        """.trimIndent()
    }

    /** 读取本地 SWF 文件代理（flash.local/local.swf → 真实 content:// / file:// / 路径） */
    private fun interceptLocalSwfProxy(view: WebView): WebResourceResponse? {
        val uri = callback.getLocalSwfUri()
        if (uri == null) {
            android.util.Log.w("GameWebViewClient", "local.swf: 无本地文件 URI")
            return WebResourceResponse("application/x-shockwave-flash", null, 404, "Not Found",
                mapOf("Access-Control-Allow-Origin" to "*"), java.io.ByteArrayInputStream(ByteArray(0)))
        }
        return try {
            android.util.Log.d("GameWebViewClient", "local.swf 代理: 读取 $uri")
            val data = readLocalSwfBytes(view, uri)
                ?: throw java.io.IOException("无法打开文件流")
            android.util.Log.d("GameWebViewClient", "local.swf 读取完成: ${data.size} bytes")
            WebResourceResponse(
                "application/x-shockwave-flash", null, 200, "OK",
                mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Content-Type" to "application/x-shockwave-flash",
                    "Cache-Control" to "no-cache"
                ),
                java.io.ByteArrayInputStream(data)
            )
        } catch (e: Exception) {
            android.util.Log.e("GameWebViewClient", "local.swf 读取失败: ${e.message}")
            WebResourceResponse("application/x-shockwave-flash", null, 404, "Not Found",
                mapOf("Access-Control-Allow-Origin" to "*"), java.io.ByteArrayInputStream(ByteArray(0)))
        }
    }

    /**
     * 拦截本地 SWF 资源文件请求（多 SWF 游戏）。
     * 当主 SWF 尝试加载同目录下的其他 SWF/资源文件时，从本地目录读取。
     */
    private fun interceptLocalResource(view: WebView, dirPath: String, resourcePath: String): WebResourceResponse? {
        return try {
            // Handle both file:// paths and direct paths
            val basePath = if (dirPath.startsWith("file://")) {
                android.net.Uri.parse(dirPath).path ?: dirPath.removePrefix("file://")
            } else {
                dirPath
            }

            val resourceFile = java.io.File(basePath, resourcePath)
            android.util.Log.d("GameWebViewClient", "Multi-SWF资源: 查找 $resourcePath 于 ${resourceFile.absolutePath}")

            if (resourceFile.exists() && resourceFile.canRead()) {
                val data = resourceFile.readBytes()
                android.util.Log.d("GameWebViewClient", "Multi-SWF资源: 找到 $resourcePath (${data.size} bytes)")

                // Determine MIME type based on extension
                val mimeType = when {
                    resourcePath.endsWith(".swf", ignoreCase = true) -> "application/x-shockwave-flash"
                    resourcePath.endsWith(".xml", ignoreCase = true) -> "text/xml"
                    resourcePath.endsWith(".json", ignoreCase = true) -> "application/json"
                    resourcePath.endsWith(".txt", ignoreCase = true) -> "text/plain"
                    resourcePath.endsWith(".png", ignoreCase = true) -> "image/png"
                    resourcePath.endsWith(".jpg", ignoreCase = true) || resourcePath.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                    resourcePath.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
                    resourcePath.endsWith(".wav", ignoreCase = true) -> "audio/wav"
                    else -> "application/octet-stream"
                }

                WebResourceResponse(
                    mimeType, null, 200, "OK",
                    mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Cache-Control" to "no-cache"
                    ),
                    java.io.ByteArrayInputStream(data)
                )
            } else {
                // For content:// URIs, try using DocumentFile
                tryContentUriResource(view, dirPath, resourcePath)
            }
        } catch (e: Exception) {
            android.util.Log.w("GameWebViewClient", "Multi-SWF资源: 未找到 $resourcePath - ${e.message}")
            null  // Return null to let WebView handle normally (404)
        }
    }

    /**
     * 通过 ContentResolver/DocumentFile 读取 content:// URI 同目录下的资源文件。
     */
    private fun tryContentUriResource(view: WebView, dirUri: String, resourcePath: String): WebResourceResponse? {
        return try {
            if (!dirUri.startsWith("content://")) return null

            val parsed = android.net.Uri.parse(dirUri)
            // Try to get the parent document and find the child
            val docFile = DocumentFile.fromTreeUri(view.context, parsed)
                ?: DocumentFile.fromSingleUri(view.context, parsed)
                ?: return null

            // Navigate to find the resource file
            var current: DocumentFile? = docFile
            val parts = resourcePath.split("/")
            for ((index, part) in parts.withIndex()) {
                if (current == null) break
                if (current.isDirectory) {
                    current = current.findFile(part)
                } else if (index == parts.size - 1) {
                    // Last part - check if current file matches
                    if (current.name == part) {
                        val data = view.context.contentResolver.openInputStream(current.uri)?.use { it.readBytes() }
                        if (data != null) {
                            val mimeType = if (part.endsWith(".swf", true)) "application/x-shockwave-flash" else "application/octet-stream"
                            return WebResourceResponse(mimeType, null, 200, "OK",
                                mapOf("Access-Control-Allow-Origin" to "*"),
                                java.io.ByteArrayInputStream(data))
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.w("GameWebViewClient", "ContentUri资源查找失败: ${e.message}")
            null
        }
    }

    /**
     * 统一读取本地 SWF 字节：兼容 content:// 、file:// 以及裸文件路径。
     * 先用 ContentResolver，失败则回退到 File API（适配 MANAGE_EXTERNAL_STORAGE 授权后的直读）。
     */
    private fun readLocalSwfBytes(view: WebView, uri: String): ByteArray? {
        // 1. content:// 必须走 ContentResolver
        if (uri.startsWith("content://")) {
            return try {
                view.context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
            } catch (e: Exception) {
                android.util.Log.w("GameWebViewClient", "content 读取失败，尝试 File: ${e.message}")
                null
            }
        }
        // 2. file:// 或裸路径：优先 ContentResolver，失败回退 File 直读
        val path = if (uri.startsWith("file://")) {
            android.net.Uri.parse(uri).path ?: uri.removePrefix("file://")
        } else {
            uri
        }
        if (path.isNotEmpty()) {
            try {
                val f = java.io.File(path)
                if (f.exists() && f.canRead()) return f.readBytes()
            } catch (e: Exception) {
                android.util.Log.w("GameWebViewClient", "File 直读失败: ${e.message}")
            }
        }
        return try {
            view.context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
        } catch (e: Exception) { null }
    }

    /** 读取本地 SWF 文件（content:// 或 file://），返回带 CORS 头的响应 */
    private fun interceptLocalFile(view: WebView, url: String): WebResourceResponse? {
        return try {
            android.util.Log.d("GameWebViewClient", "读取本地文件: $url")
            val data = readLocalSwfBytes(view, url)
                ?: throw java.io.IOException("无法打开文件流")
            android.util.Log.d("GameWebViewClient", "本地文件读取完成: ${data.size} bytes")
            WebResourceResponse(
                "application/x-shockwave-flash", null,
                200, "OK",
                mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Content-Type" to "application/x-shockwave-flash",
                    "Cache-Control" to "no-cache"
                ),
                java.io.ByteArrayInputStream(data)
            )
        } catch (e: Exception) {
            android.util.Log.e("GameWebViewClient", "本地文件读取失败: ${e.message}")
            WebResourceResponse(
                "application/x-shockwave-flash", null, 404, "Not Found",
                mapOf("Access-Control-Allow-Origin" to "*"),
                java.io.ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    /** SWF 下载缓存：避免同一 SWF 被多个并发请求重复下载（Ruffle 会同时发起多个请求） */
    private val swfCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** 统一 CORS 响应头：所有 SWF 拦截响应（成功/失败/预检）都带这些头 */
    private val swfCorsHeaders = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
        "Access-Control-Allow-Headers" to "*",
        "Content-Type" to "application/x-shockwave-flash",
        "Cache-Control" to "no-cache"
    )

    /** 原生下载 SWF 文件，返回带 CORS 头的响应（含缓存 + 重试 + SSL 兼容 + Cookie/请求头转发） */
    private fun interceptSwf(url: String, request: WebResourceRequest? = null): WebResourceResponse? {
        // 0. 处理 CORS 预检请求（OPTIONS）：直接返回 200 + CORS 头，不下载文件
        //    WebResourceRequest.getMethod() 需要 API 24+，低于 24 无法判断方法
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
            request?.method == "OPTIONS") {
            android.util.Log.d("GameWebViewClient", "SWF OPTIONS 预检通过: $url")
            return WebResourceResponse("text/plain", "UTF-8", 200, "OK",
                swfCorsHeaders, java.io.ByteArrayInputStream(ByteArray(0)))
        }

        // 1. 构建尝试 URL 列表
        //    不再强制 HTTP→HTTPS 升级！
        //    原因：部分 CDN（如 cdn.comment.4399pk.com）不支持 HTTPS，强制升级导致下载失败。
        //    shouldInterceptRequest 返回的响应直接给 WebView，不受 Mixed Content 限制。
        //    策略：HTTP URL 先试原始 HTTP，失败再试 HTTPS；HTTPS URL 直接用
        val tryUrls = when {
            url.startsWith("https://") -> listOf(url)
            url.startsWith("http://") -> listOf(url, "https://" + url.substring(7))
            else -> listOf(url)
        }

        // 2. 检查缓存：Ruffle/WAFlash 可能同时发起多个相同 SWF 请求，缓存避免重复下载
        for (u in tryUrls) {
            swfCache[u]?.let { cached ->
                android.util.Log.d("GameWebViewClient", "SWF 缓存命中: ${cached.size} bytes, URL=$u")
                return WebResourceResponse("application/x-shockwave-flash", null, 200, "OK",
                    swfCorsHeaders, java.io.ByteArrayInputStream(cached))
            }
        }

        // 3. 逐个 URL 尝试下载（每个 URL 最多重试 3 次）
        var lastError: Exception? = null
        for (swfUrl in tryUrls) {
            for (attempt in 1..3) {
                try {
                    android.util.Log.d("GameWebViewClient", "拦截 SWF 请求 (尝试 $attempt): $swfUrl")
                    val conn = java.net.URL(swfUrl).openConnection() as java.net.HttpURLConnection
                    // HTTPS: 信任所有证书，防止 SSL 握手失败导致 SWF 下载不了
                    if (conn is javax.net.ssl.HttpsURLConnection) {
                        conn.sslSocketFactory = trustAllSslSocketFactory()
                        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                    }
                    conn.connectTimeout = 10000
                    conn.readTimeout = 20000
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = true

                    // 转发原始请求头（User-Agent, Accept 等），模拟浏览器行为
                    // 排除需要自行设置的、条件请求的、和 CORS 相关的 header
                    request?.requestHeaders?.forEach { (key, value) ->
                        val lk = key.lowercase()
                        if (lk !in setOf(
                                "cookie", "referer", "range", "if-modified-since", "if-none-match",
                                "accept-encoding", "origin", "access-control-request-method",
                                "access-control-request-headers", "host", "content-length"
                            )) {
                            conn.setRequestProperty(key, value)
                        }
                    }
                    // 确保 User-Agent 存在（完整浏览器 UA，避免被服务器拒绝）
                    if (request?.requestHeaders?.none { it.key.equals("User-Agent", true) } != false) {
                        conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    }
                    conn.setRequestProperty("Accept", "*/*")
                    // 不请求 gzip，避免手动解压
                    conn.setRequestProperty("Accept-Encoding", "identity")

                    // 转发 Cookie（从 CookieManager 获取，防止防盗链/登录态丢失）
                    try {
                        val cookies = android.webkit.CookieManager.getInstance().getCookie(swfUrl)
                        if (cookies != null && cookies.isNotEmpty()) {
                            conn.setRequestProperty("Cookie", cookies)
                            android.util.Log.d("GameWebViewClient", "转发 Cookie: ${cookies.length} chars")
                        }
                    } catch(e: Exception) {}

                    // 添加 Referer（防盗链），从 SWF URL 推导同源 origin
                    if (swfUrl.contains("4399.com")) {
                        conn.setRequestProperty("Referer", "https://www.4399.com/")
                    } else {
                        try {
                            val uri = android.net.Uri.parse(swfUrl)
                            conn.setRequestProperty("Referer", "${uri.scheme}://${uri.host}/")
                        } catch(e: Exception) {
                            conn.setRequestProperty("Referer", swfUrl)
                        }
                    }

                    conn.connect()
                    val responseCode = conn.responseCode
                    if (responseCode in 200..299) {
                        val data = conn.inputStream.readBytes()
                        android.util.Log.d("GameWebViewClient", "SWF 下载完成: ${data.size} bytes, URL=$swfUrl")
                        // 存入缓存（限制缓存大小）
                        if (swfCache.size >= 10) swfCache.clear()
                        swfCache[swfUrl] = data
                        return WebResourceResponse(
                            "application/x-shockwave-flash", null,
                            200, "OK", swfCorsHeaders,
                            java.io.ByteArrayInputStream(data)
                        )
                    } else if (responseCode in 500..599 && attempt < 3) {
                        android.util.Log.w("GameWebViewClient", "SWF 服务器错误 $responseCode, 重试...")
                        Thread.sleep(500L * attempt)
                        continue
                    } else {
                        android.util.Log.w("GameWebViewClient", "SWF 下载失败: HTTP $responseCode, URL=$swfUrl")
                        lastError = RuntimeException("HTTP $responseCode")
                        break // 换下一个 URL 尝试
                    }
                } catch (e: Exception) {
                    lastError = e
                    android.util.Log.w("GameWebViewClient", "SWF 下载异常 (尝试 $attempt): ${e.message}")
                    if (attempt < 3) Thread.sleep(500L * attempt)
                }
            }
            // 再次检查缓存：可能在重试期间其他线程已成功下载
            swfCache[swfUrl]?.let { cached ->
                android.util.Log.d("GameWebViewClient", "SWF 重试期间缓存命中: ${cached.size} bytes")
                return WebResourceResponse("application/x-shockwave-flash", null, 200, "OK",
                    swfCorsHeaders, java.io.ByteArrayInputStream(cached))
            }
        }

        // 4. 所有 URL 都失败了
        android.util.Log.e("GameWebViewClient", "SWF 下载最终失败: ${lastError?.message}, URL=$url")
        // 返回带 CORS 头的空响应（而非 null）
        // 原因：返回 null 会让 WebView 自行请求原始 URL，
        // 但 WAFlash/Ruffle 页面运行在 https://flash.local（HTTPS），
        // 浏览器会因 Mixed Content / CORS 阻止 HTTP 资源加载，导致引擎报错。
        // 返回 502 + CORS 头让引擎知道请求失败，优雅处理（如跳过广告 SWF）。
        return WebResourceResponse("application/x-shockwave-flash", null, 502, "Bad Gateway",
            swfCorsHeaders, java.io.ByteArrayInputStream(ByteArray(0)))
    }

    /** 信任所有 SSL 证书的 SSLSocketFactory（用于 SWF 下载兼容） */
    @Volatile private var _sslFactory: javax.net.ssl.SSLSocketFactory? = null
    private fun trustAllSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        return _sslFactory ?: synchronized(this) {
            _sslFactory ?: try {
                val tm = object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
                val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf(tm), java.security.SecureRandom())
                ctx.socketFactory
            } catch (e: Exception) {
                javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory()
            }.also { _sslFactory = it }
        }
    }

    /** 从 assets 读取文件并返回带 CORS 头的 WebResourceResponse */
    private fun interceptAsset(view: WebView, assetPath: String): WebResourceResponse? {
        return try {
            val input = view.context.assets.open(assetPath)
            val (mime, charset) = when {
                assetPath.endsWith(".wasm") -> "application/wasm" to null
                assetPath.endsWith(".js") -> "application/javascript" to "UTF-8"
                assetPath.endsWith(".html") -> "text/html" to "UTF-8"
                assetPath.endsWith(".css") -> "text/css" to "UTF-8"
                assetPath.endsWith(".ttf") -> "font/ttf" to null
                assetPath.endsWith(".woff") -> "font/woff" to null
                assetPath.endsWith(".woff2") -> "font/woff2" to null
                assetPath.endsWith(".otf") -> "font/otf" to null
                assetPath.endsWith(".data") -> "application/octet-stream" to null
                else -> "application/octet-stream" to null
            }
            val headers = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-cache"
            )
            WebResourceResponse(mime, charset, 200, "OK", headers, input)
        } catch (e: Exception) {
            android.util.Log.w("GameWebViewClient", "asset not found: $assetPath", e)
            null
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        callback.onPageStarted(view, url)

        // ★ v10: 双指缩放解锁兜底（Document Start 特性不可用的旧 WebView 上）——
        //   剥离 viewport 的 user-scalable=no / 放开 maximum-scale 钳制（脚本幂等）
        view?.evaluateJavascript(GameWebView.PINCH_UNLOCK_SCRIPT, null)

        // 注入按键安全钩子：页面失焦时自动释放所有按键（所有页面通用）
        view?.evaluateJavascript(KEY_SAFETY_SCRIPT, null)

        // View Transitions API polyfill 兜底：addDocumentStartJavaScript 已在页面 JS 之前
        // 覆盖 Document.prototype.startViewTransition。此处作为旧 WebView 的兜底，
        // 同样覆盖 Document.prototype + 捕获 unhandledrejection。
        view?.evaluateJavascript(VIEW_TRANSITION_PATCH_SCRIPT, null)

        // 4399 页面：伪造 document.referrer 绕过防盗链检测 + IE 兼容模式伪造
        if (url != null && url.contains("4399.com")) {
            view?.evaluateJavascript(REFERER_SPOOF_SCRIPT, null)
            if (PrefsManager.uaMode == "ie_compat") {
                view?.evaluateJavascript(IE_COMPAT_SCRIPT, null)
            }
        }

        val isFlashPage = PrefsManager.isFlashEnabled && callback.shouldInjectRuffle(url)

        // Flash 页面：最先注入 Flash 支持伪造（在页面 JS 执行前）
        // 让 4399 检测到浏览器"有 Flash 插件"，从而创建 <object> 元素
        // 之后 Ruffle polyfill 会替换 <object> 为 Canvas 播放器
        if (isFlashPage) {
            view?.evaluateJavascript(FLASH_FAKE_SUPPORT_SCRIPT, null)
            // configScript() / loaderScript() / fullInjection() 内部会从 PrefsManager 读取
            // 当前 scale / letterbox / quality / aspect 设置并应用到 Ruffle config。
            view?.evaluateJavascript(RuffleInjector.configScript(), null)
            view?.evaluateJavascript(RuffleInjector.loaderScript(), null)
            view?.evaluateJavascript(FLASH_HIDE_SCRIPT, null)
            if (PrefsManager.flashEngine == "waflash") {
                view?.evaluateJavascript(WAFLASH_DETECT_SCRIPT, null)
            }
            // 注入 iframe 监控：游戏可能加载在 iframe 中
            view?.evaluateJavascript(IFRAME_INJECT_SCRIPT, null)
        }

        // 所有网页注入 viewport 缩放支持（不限 4399）
        if (url != null && !url.startsWith("file:///android_asset/") && !url.startsWith("https://flash.local/")) {
            view?.evaluateJavascript(buildViewportScript(), null)
        }
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        val isFlashPage = PrefsManager.isFlashEnabled && callback.shouldInjectRuffle(url)
        if (isFlashPage) {
            view?.evaluateJavascript(RuffleInjector.loaderScript(), null)
            view?.evaluateJavascript(FLASH_HIDE_SCRIPT, null)
            if (PrefsManager.flashEngine == "waflash") {
                view?.evaluateJavascript(WAFLASH_DETECT_SCRIPT, null)
            }
        }
        if (url != null && !url.startsWith("file:///android_asset/") && !url.startsWith("https://flash.local/")) {
            view?.evaluateJavascript(buildViewportScript(), null)
        }
        // 强制重绘兜底：页面首次可见时确保渲染帧已提交
        view?.invalidate()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val isFlashPage = PrefsManager.isFlashEnabled && callback.shouldInjectRuffle(url)
        if (isFlashPage) {
            view?.evaluateJavascript(RuffleInjector.fullInjection(), null)
            if (PrefsManager.flashEngine == "waflash") {
                view?.evaluateJavascript(WAFLASH_DETECT_SCRIPT, null)
            }
        }
        if (isFlashPage) {
            view?.evaluateJavascript(CSS_INJECTION, null)
        }
        if (url != null && !url.startsWith("file:///android_asset/") && !url.startsWith("https://flash.local/")) {
            view?.evaluateJavascript(buildViewportScript(), null)
        }

        // 鼠标光标模拟：导航后会丢失，需重新注入
        if (PrefsManager.isMouseEnabled) {
            view?.evaluateJavascript(MOUSE_CURSOR_SCRIPT, null)
        }
        // 3D 视角旋转：导航后会丢失，需重新注入
        if (PrefsManager.isCameraRotationEnabled) {
            view?.evaluateJavascript(CAMERA_ROTATION_SCRIPT, null)
        }
        // 画面比例 letterbox
        // - WAFlash 页面(waflash.html):WAFlash 引擎自身不做 letterbox,必须外层 CSS 强制按比例。
        // - Ruffle 页面(flash.local/player.html):引擎内部已经按 SWF 原生 stageSize 比例 letterbox +
        //   scale:showAll 不变形显示,这里只对 ruffle-player 外层做 CSS 居中 + max-width/max-height
        //   letterbox 容器,让它按用户指定的 4:3 / 16:9 / 16:10 / 5:4 居中显示,不破坏引擎内部渲染。
        // ★ stretch（全屏拉伸）也注入：脚本内部按"清理外框"处理（清掉此前
        //   比例模式留下的样式），真正的拉伸由加载参数（exactFit/off）完成。
        if (PrefsManager.gameAspectRatio != "auto" && url != null) {
            val isWaflashPage = url.startsWith("https://flash.local/waflash")
            val isRufflePage = !isWaflashPage &&
                url.startsWith("https://flash.local/player")
            when {
                isRufflePage -> view?.evaluateJavascript(
                    buildRuffleAspectRatioScript(PrefsManager.gameAspectRatio), null
                )
                isWaflashPage -> view?.evaluateJavascript(
                    buildAspectRatioScript(PrefsManager.gameAspectRatio), null
                )
            }
        }

        // 强制重绘：部分网页（如使用 View Transitions 的 SPA）加载完成后
        // 渲染管线未正确触发重绘，导致页面"卡住"（切后台再回来才显示）。
        // invalidate + requestLayout 强制 WebView 重新绘制当前帧。
        view?.invalidate()
        view?.requestLayout()
        // 延迟二次重绘：等待 evaluateJavascript 注入的脚本执行完毕后再触发一次
        view?.postDelayed({
            view?.invalidate()
            view?.evaluateJavascript(
                "try{void document.body&&document.body.offsetHeight;}catch(e){}", null
            )
        }, 300)

        callback.onPageFinished(view, url)
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        handler?.proceed()
    }

    /**
     * 新版错误回调（API 23+）：仅主框架错误才通知 callback。
     */
    override fun onReceivedError(
        view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            callback.onError(
                request.url?.toString(),
                error?.errorCode ?: -1,
                error?.description?.toString()
            )
        }
    }

    /**
     * 废弃版错误回调：不触发 callback.onError，避免子资源错误误显示错误页。
     */
    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView?, errorCode: Int, description: String?, failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
    }

    /**
     * ★ v10: 渲染进程死亡兑底（参照 AnWind）—— 不返回 true 接管时，Chromium
     * 会连带杀掉整个应用进程（重型 WASM/H5 游戏页面最常见的“整包闪退”）。
     * 宿主销毁死 WebView 并重建（renderEpoch 驱动），自动恢复浏览。
     */
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        try {
            callback.onRendererGone(view)
        } catch (_: Exception) {
        }
        return true // true = 本应用已接管，系统不再杀进程
    }

    /**
     * viewport 兜底注入脚本（onPageStarted / onPageCommitVisible / onPageFinished）：
     * ★ v10 重写：尊重页面自己的 viewport —— 不再对全部页面强制
     * width=device-width（旧实现会把 PC 版页面压成手机宽度、破坏桌面排版，
     * 也让双指缩放失去“全景基准”），只解除 no-zoom 钳制（PINCH_UNLOCK_SCRIPT）。
     * ★ v11：manual 模式的 initial-scale 强改撤销（与 WebPinchLayout 统一
     *   缩放系统互踩，见方法体注释）。
     */
    private fun buildViewportScript(): String {
        // ★ v11: 手动缩放的 meta 强改（width=device-width + initial-scale=百分比）
        //   已整体撤销 —— 该通道与 PINCH_UNLOCK/页面自身 meta/WebPinchLayout 接管域
        //   三方互相覆盖打架（用户实测"百分比设置没作用"的根因之一），且给 PC 页
        //   强塞 width=device-width 会破坏桌面排版。
        //   真实缩放唯一通道 = WebPinchLayout（viewport meta 动态注入，即时生效）；
        //   此处仅保留 PINCH_UNLOCK（原生捏合解锁，Document Start 特性不可用时的
        //   onPageStarted 兜底注入）。
        return GameWebView.PINCH_UNLOCK_SCRIPT
    }

    companion object {

        /**
         * 按键安全钩子：在页面失焦（blur/visibilitychange）时自动释放所有按键。
         * 这是 Android 端 releaseAllKeys() 的补充保险——
         * 即使 Android 侧漏调，网页侧也能自行清理，防止 Ruffle 角色持续移动。
         */
        private const val KEY_SAFETY_SCRIPT = """
            (function(){
              if (window.__keySafetyHook) return;
              window.__keySafetyHook = true;
              // 追踪所有 keydown 的 keyCode
              var pressed = {};
              window.addEventListener('keydown', function(e){
                pressed[e.keyCode] = true;
              }, true);
              window.addEventListener('keyup', function(e){
                delete pressed[e.keyCode];
              }, true);
              // 释放所有已记录的按键
              function releaseAll() {
                Object.keys(pressed).forEach(function(kc) {
                  var code = parseInt(kc);
                  var keyMap = {
                    37:'ArrowLeft',38:'ArrowUp',39:'ArrowRight',40:'ArrowDown',
                    32:' ',13:'Enter',9:'Tab',27:'Escape',17:'Control',16:'Shift',18:'Alt',
                    65:'a',66:'b',67:'c',68:'d',69:'e',70:'f',71:'g',72:'h',73:'i',74:'j',
                    75:'k',76:'l',77:'m',78:'n',79:'o',80:'p',81:'q',82:'r',83:'s',84:'t',
                    85:'u',86:'v',87:'w',88:'x',89:'y',90:'z',
                    48:'0',49:'1',50:'2',51:'3',52:'4',53:'5',54:'6',55:'7',56:'8',57:'9'
                  };
                  var keyVal = keyMap[code] || '';
                  var codeMap = {
                    37:'ArrowLeft',38:'ArrowUp',39:'ArrowRight',40:'ArrowDown',
                    32:'Space',13:'Enter',9:'Tab',27:'Escape',
                    65:'KeyA',68:'KeyD',69:'KeyE',70:'KeyF',81:'KeyQ',82:'KeyR',
                    83:'KeyS',87:'KeyW',88:'KeyX',90:'KeyZ'
                  };
                  var codeVal = codeMap[code] || '';
                  try {
                    var evt = new KeyboardEvent('keyup', {
                      key: keyVal, code: codeVal, bubbles: true, cancelable: true
                    });
                    Object.defineProperty(evt, 'keyCode', {get: function(){return code;}});
                    Object.defineProperty(evt, 'which', {get: function(){return code;}});
                    document.dispatchEvent(evt);
                  } catch(e) {}
                  delete pressed[kc];
                });
              }
              window.addEventListener('blur', releaseAll);
              document.addEventListener('visibilitychange', function() {
                if (document.hidden) releaseAll();
              });
            })();
        """

        /**
         * View Transitions API 兼容性补丁（所有页面通用）。
         *
         * 问题：部分网站（如 gamefunbar.com）使用 document.startViewTransition() 实现
         *       SPA 风格的页面跳转。Android WebView 对该 API 的支持不完善，
         *       可能直接跳过过渡（Transition was skipped），导致：
         *       1. 回调函数未执行 → 页面无法跳转（核心 Bug）
         *       2. finished Promise 抛出未捕获的 AbortError → 控制台报错
         *
         * 方案：用 polyfill 替换 document.startViewTransition，确保回调始终同步执行，
         *       返回的 Promise 始终正常 resolve（不抛 AbortError）。
         *       代价是丢失过渡动画，但不影响功能与导航。
         */
        private const val VIEW_TRANSITION_PATCH_SCRIPT = """
            (function(){
              if (window.__vtPatch) return;
              window.__vtPatch = true;
              var vtPolyfill = function(callback) {
                var result;
                try {
                  result = callback ? callback() : undefined;
                } catch(e) {
                  result = Promise.reject(e);
                }
                var p = (result && typeof result.then === 'function') ? result : Promise.resolve();
                // 强制触发重绘：解决 polyfill 执行回调后 WebView 不重绘导致页面"卡住"
                requestAnimationFrame(function() {
                  try { void document.body && document.body.offsetHeight; } catch(e) {}
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
              // 覆盖 Document.prototype（阻止原生 getter 返回原始实现）
              try {
                Object.defineProperty(Document.prototype, 'startViewTransition', {
                  value: vtPolyfill,
                  writable: true,
                  configurable: true
                });
              } catch(e) {}
              // 同时覆盖 document 实例（双重保险）
              try { document.startViewTransition = vtPolyfill; } catch(e) {}
              // 捕获 unhandledrejection：抑制 View Transitions 的 AbortError
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
            })();
        """

        /**
         * 伪造 Flash 插件支持：让 4399 等页面检测到浏览器"有 Flash 插件"。
         * 必须在页面 JS 执行前注入（onPageStarted）。
         * 伪造 navigator.plugins["Shockwave Flash"] 和 navigator.mimeTypes。
         */
        private const val FLASH_FAKE_SUPPORT_SCRIPT = """
            (function(){
              if (window.__flashFaked) return;
              window.__flashFaked = true;
              try {
                // 伪造 navigator.plugins（含 namedItem/item 方法，Ruffle 需要调用）
                var fakePlugin = {
                  name: 'Shockwave Flash',
                  filename: 'libflashplayer.so',
                  description: 'Shockwave Flash 32.0 r0',
                  length: 1,
                  0: { type: 'application/x-shockwave-flash', suffixes: 'swf', description: 'Shockwave Flash' }
                };
                fakePlugin.namedItem = function(n) { return (n === 'Shockwave Flash') ? fakePlugin : null; };
                fakePlugin.item = function(i) { return i === 0 ? fakePlugin : null; };
                fakePlugin.refresh = function() {};
                var plugins = navigator.plugins || {};
                if (plugins.namedItem) { fakePlugin.namedItem = function(n) { return (n === 'Shockwave Flash') ? fakePlugin : plugins.namedItem.call(plugins, n); }; }
                if (plugins.item) { fakePlugin.item = function(i) { return i === 0 ? fakePlugin : plugins.item.call(plugins, i); }; }
                Object.defineProperty(navigator, 'plugins', {
                  get: function() {
                    var p = plugins;
                    if (!p['Shockwave Flash']) {
                      try { p['Shockwave Flash'] = fakePlugin; p[0] = fakePlugin; } catch(e) {}
                    }
                    p.length = Math.max(p.length || 0, 1);
                    return p;
                  },
                  configurable: true
                });
                // 伪造 navigator.mimeTypes
                var fakeMime = { type: 'application/x-shockwave-flash', suffixes: 'swf', description: 'Shockwave Flash', enabledPlugin: fakePlugin };
                var mimes = navigator.mimeTypes || {};
                Object.defineProperty(navigator, 'mimeTypes', {
                  get: function() {
                    if (!mimes['application/x-shockwave-flash']) {
                      try { mimes['application/x-shockwave-flash'] = fakeMime; } catch(e) {}
                    }
                    return mimes;
                  },
                  configurable: true
                });
                // 伪造 ActiveXObject（IE 方式检测 Flash）
                window.ActiveXObject = function(name) {
                  if (name && /ShockwaveFlash/i.test(name)) return { SetVariable: function(){} };
                  throw new Error('Not supported');
                };
                console.log('[Flash] 已伪造 Flash 插件支持');
              } catch(e) { console.warn('[Flash] 伪造失败:', e); }
            })();
        """

        /**
         * iframe 注入监控：4399 游戏常加载在 iframe 中。
         * 监控 iframe 创建，将 Flash 引擎注入到 iframe 内部。
         */
        private const val IFRAME_INJECT_SCRIPT = """
            (function(){
              if (window.__iframeMonitor) return;
              window.__iframeMonitor = true;
              function injectIntoIframe(iframe) {
                try {
                  var doc = iframe.contentDocument || iframe.contentWindow.document;
                  if (!doc || doc.readyState === 'loading') {
                    setTimeout(function(){ injectIntoIframe(iframe); }, 200);
                    return;
                  }
                  // 检查是否已注入
                  if (doc.__flashFaked) return;
                  doc.__flashFaked = true;
                  // 注入 Flash 支持伪造
                  var s1 = doc.createElement('script');
                  s1.textContent = '(' + function(){
                    try {
                      var fp = {name:'Shockwave Flash',filename:'libflashplayer.so',description:'Shockwave Flash 32.0 r0',length:1,
                        0:{type:'application/x-shockwave-flash',suffixes:'swf',description:'Shockwave Flash'}};
                      fp.namedItem = function(n){ return (n === 'Shockwave Flash') ? fp : null; };
                      fp.item = function(i){ return i === 0 ? fp : null; };
                      fp.refresh = function(){};
                      Object.defineProperty(navigator,'plugins',{get:function(){return fp;},configurable:true});
                      Object.defineProperty(navigator,'mimeTypes',{get:function(){return {'application/x-shockwave-flash':{type:'application/x-shockwave-flash',suffixes:'swf',description:'Shockwave Flash',enabledPlugin:fp}};},configurable:true});
                      window.ActiveXObject = function(n){if(/ShockwaveFlash/i.test(n))return {SetVariable:function(){}};throw new Error('x');};
                    } catch(e){}
                  } + ')();';
                  doc.head.appendChild(s1);
                  // 注入 Ruffle 引擎
                  var engine = window.__ruffleLoaded ? 'ruffle' : (window.__swf2jsLoaded ? 'swf2js' : null);
                  if (engine === 'ruffle') {
                    var s2 = doc.createElement('script');
                    s2.src = 'https://flash.local/ruffle/ruffle.js';
                    s2.onload = function(){
                      try {
                        var r = doc.defaultView.RufflePlayer;
                        if (r && r.newest) { var inst = r.newest(); if (inst && inst.init) inst.init(); }
                      } catch(e){}
                    };
                    doc.head.appendChild(s2);
                  }
                  console.log('[Flash] 已注入 iframe');
                } catch(e) {
                  // 跨域 iframe 无法注入
                }
              }
              // 监控新创建的 iframe
              if (window.MutationObserver) {
                var observer = new MutationObserver(function(mutations){
                  mutations.forEach(function(m){
                    m.addedNodes.forEach(function(node){
                      if (node.tagName === 'IFRAME') injectIntoIframe(node);
                      if (node.querySelectorAll) {
                        var iframes = node.querySelectorAll('iframe');
                        for (var i = 0; i < iframes.length; i++) injectIntoIframe(iframes[i]);
                      }
                    });
                  });
                });
                observer.observe(document.documentElement, {childList: true, subtree: true});
              }
              // 检查已有的 iframe
              var existing = document.querySelectorAll('iframe');
              for (var i = 0; i < existing.length; i++) injectIntoIframe(existing[i]);
            })();
        """

        private const val REFERER_SPOOF_SCRIPT = """
            (function(){
              try {
                Object.defineProperty(document, 'referrer', {
                  get: function() { return 'https://www.4399.com/'; },
                  configurable: true
                });
              } catch(e) {}
            })();
        """

        /** IE 兼容模式伪造：4399 检测 IE 特有属性来判断兼容模式 */
        private const val IE_COMPAT_SCRIPT = """
            (function(){
              try {
                // 伪造 IE 的 documentMode（IE 独有属性）
                Object.defineProperty(document, 'documentMode', {
                  get: function() { return 11; }, configurable: true
                });
                // 伪造 IE 的 uniqueID
                Object.defineProperty(document, 'uniqueID', {
                  get: function() { return '_ie_id_'; }, configurable: true
                });
                // 伪造 IE 的 all 集合
                if (!document.all) {
                  document.all = document.getElementsByTagName('*');
                }
                // 伪装 navigator.userAgent 中含 Trident
                var origUA = navigator.userAgent;
                if (!/Trident/.test(origUA)) {
                  try {
                    Object.defineProperty(navigator, 'userAgent', {
                      get: function() {
                        return origUA + ' Trident/7.0; rv:11.0';
                      }, configurable: true
                    });
                  } catch(e) {}
                }
              } catch(e) {}
            })();
        """

        private const val CSS_INJECTION = """
            (function(){
              if (window.__cssInjected) return; window.__cssInjected = true;
              var s = document.createElement('style');
              s.textContent = [
                '.advertise,.ad_box,[id^="ad_"],[class*="advert"]{display:none!important;}',
                'object,embed{max-width:100%!important;}'
              ].join('\n');
              document.head.appendChild(s);
            })();
        """

        private const val FLASH_HIDE_SCRIPT = """
            (function(){
              if (window.__flashHideInjected) return; window.__flashHideInjected = true;
              try {
                Object.defineProperty(navigator, 'plugins', {
                  get: function() {
                    var arr = [];
                    arr.namedItem = function(name) { return name === 'Shockwave Flash' ? { name: name } : null; };
                    arr.refresh = function() {};
                    arr.item = function(i) { return arr[i] || null; };
                    return arr;
                  },
                  configurable: true
                });
              } catch(e) {}
              function hideFlashTips() {
                var selectors = [
                  '[class*="noflash"]','[id*="noflash"]',
                  '[class*="no-flash"]','[id*="no-flash"]',
                  '.flash_tip','.flash-tip',
                  '#flash_tip','#flash-tip',
                  '.prompt-flash','.prompt_flash'
                ];
                document.querySelectorAll(selectors.join(',')).forEach(function(el){
                  el.style.display = 'none';
                });
              }
              hideFlashTips();
              if (window.MutationObserver) {
                var mo = new MutationObserver(function(){ hideFlashTips(); });
                try { mo.observe(document.documentElement, {childList:true, subtree:true}); } catch(e) {}
              }
            })();
        """

        /**
         * WAFlash SWF 检测脚本：
         * 1. Hook swfobject.embedSWF() — 标准 Flash 嵌入
         * 2. Hook AC_FL_RunContent() — 老式 Flash 嵌入
         * 3. Hook createFlash() — 4399 的 mflash-player API
         * 4. Hook fetch/XMLHttpRequest — 拦截非 .swf 扩展名的 SWF 加载
         * 5. MutationObserver 检测动态创建的 Flash DOM 元素
         */
        private const val WAFLASH_DETECT_SCRIPT = """
            (function(){
              if (window.__waflashDetect) return;
              window.__waflashDetect = true;
              var redirected = false;

              function redirectToPlayer(swfUrl, baseUrl) {
                if (redirected || !swfUrl) return;
                // 检测 SWF URL：支持 .swf 扩展名、4399 的 dw-XX 格式、flash 路径
                var isSwf = /\.swf/i.test(swfUrl) ||
                            /\/dw-\d+/i.test(swfUrl) ||
                            /flash\d*\//i.test(swfUrl) ||
                            /mm\.4399\.com/i.test(swfUrl);
                if (!isSwf) return;
                redirected = true;
                try { swfUrl = new URL(swfUrl, baseUrl || window.location.href).href; } catch(e) {}
                console.log('[WAFlash] 检测到 SWF: ' + swfUrl);
                if (window.Android && window.Android.openSwf) {
                  window.Android.openSwf(swfUrl, baseUrl || window.location.href);
                } else {
                  window.location.href = 'https://flash.local/waflash.html?swf=' + encodeURIComponent(swfUrl);
                }
              }

              // 1. Hook swfobject.embedSWF
              if (window.swfobject && window.swfobject.embedSWF) {
                var origEmbed = window.swfobject.embedSWF;
                window.swfobject.embedSWF = function() {
                  redirectToPlayer(arguments[0], window.location.href);
                  return origEmbed.apply(this, arguments);
                };
              } else {
                var _swfobject;
                Object.defineProperty(window, 'swfobject', {
                  configurable: true,
                  get: function() { return _swfobject; },
                  set: function(val) {
                    _swfobject = val;
                    if (val && val.embedSWF) {
                      var orig = val.embedSWF;
                      val.embedSWF = function() {
                        redirectToPlayer(arguments[0], window.location.href);
                        return orig.apply(this, arguments);
                      };
                    }
                  }
                });
              }

              // 2. Hook AC_FL_RunContent
              if (window.AC_FL_RunContent) {
                var origAC = window.AC_FL_RunContent;
                window.AC_FL_RunContent = function() {
                  var args = Array.prototype.slice.call(arguments);
                  for (var i = 0; i < args.length - 1; i++) {
                    if ((args[i] === 'src' || args[i] === 'movie') && args[i+1]) {
                      redirectToPlayer(args[i+1], window.location.href);
                    }
                  }
                  return origAC.apply(this, arguments);
                };
              }

              // 3. Hook createFlash — 4399 的 mflash-player API
              //    createFlash(swfUrl, options) 或 createFlash({url: swfUrl, ...})
              function hookCreateFlash(obj) {
                if (!obj || !obj.createFlash || obj.__waflashHooked) return;
                obj.__waflashHooked = true;
                var orig = obj.createFlash;
                obj.createFlash = function() {
                  var swfUrl = null;
                  if (typeof arguments[0] === 'string') {
                    swfUrl = arguments[0];
                  } else if (arguments[0] && typeof arguments[0] === 'object') {
                    swfUrl = arguments[0].url || arguments[0].src || arguments[0].swf ||
                             arguments[0].movie || arguments[0].data;
                  }
                  if (swfUrl) redirectToPlayer(swfUrl, window.location.href);
                  return orig.apply(this, arguments);
                };
                console.log('[WAFlash] 已 hook createFlash');
              }

              // 检查 mflash-player 是否已存在（注意：属性名含连字符，必须用方括号）
              if (window['mflash-player']) hookCreateFlash(window['mflash-player']);
              if (window.mflashplayer) hookCreateFlash(window.mflashplayer);
              if (window.MFlash) hookCreateFlash(window.MFlash);

              // 延迟 hook：4399 可能动态加载 mflash-player
              var _mflash;
              Object.defineProperty(window, 'mflash-player', {
                configurable: true,
                get: function() { return _mflash; },
                set: function(val) { _mflash = val; hookCreateFlash(val); }
              });
              var _mflash2;
              Object.defineProperty(window, 'mflashplayer', {
                configurable: true,
                get: function() { return _mflash2; },
                set: function(val) { _mflash2 = val; hookCreateFlash(val); }
              });

              // 4. 检测页面中已有的 Flash 元素
              function extractSwfUrl(el) {
                if (!el) return null;
                var src = el.getAttribute('data') || el.getAttribute('src') ||
                          el.getAttribute('movie') || el.getAttribute('url') || '';
                if (src) return src;
                var params = el.querySelectorAll('param[name="movie"], param[name="src"]');
                for (var i = 0; i < params.length; i++) {
                  var v = params[i].getAttribute('value') || '';
                  if (v) return v;
                }
                return null;
              }

              function checkExistingFlash() {
                if (redirected) return;
                var selectors = 'object[type="application/x-shockwave-flash"], ' +
                  'embed[type="application/x-shockwave-flash"], ' +
                  'object[data$=".swf" i], embed[src$=".swf" i], ' +
                  'object[classid*="D27CDB6E" i]';
                var elements = document.querySelectorAll(selectors);
                for (var i = 0; i < elements.length; i++) {
                  var swfUrl = extractSwfUrl(elements[i]);
                  if (swfUrl) { redirectToPlayer(swfUrl, window.location.href); return; }
                }
              }

              checkExistingFlash();

              // 5. MutationObserver 持续监控
              if (window.MutationObserver) {
                var observer = new MutationObserver(function() {
                  if (redirected) { observer.disconnect(); return; }
                  checkExistingFlash();
                });
                try {
                  observer.observe(document.documentElement || document.body || document, {
                    childList: true, subtree: true
                  });
                } catch(e) {}
                setTimeout(function() { observer.disconnect(); }, 15000);
              }
            })();
        """

        /**
         * 鼠标光标模拟脚本：在 PC 网页上显示一个跟随触摸的鼠标光标，
         * 触摸 = 鼠标移动，点击 = 鼠标左键点击。
         * 用于兼容需要鼠标 hover 的 PC 网页。
         * 1:1 移植自 3.3-fix2 GameActivity.MOUSE_CURSOR_SCRIPT。
         */
        const val MOUSE_CURSOR_SCRIPT = """
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

        /**
         * 3D 视角旋转脚本：
         * 1. Hook requestPointerLock → 模拟指针锁定成功（移动端不支持原生 Pointer Lock）
         * 2. 提供 window.__cameraRotate(dx, dy) → 分发带 movementX/movementY 的 mousemove 事件
         * 3. 阻止页面滚动/选择，确保拖动只用于旋转视角
         * 1:1 移植自 3.3-fix2 GameActivity.CAMERA_ROTATION_SCRIPT。
         */
        const val CAMERA_ROTATION_SCRIPT = """
            (function(){
              if (window.__cameraRotation) return; window.__cameraRotation = true;

              // === 1. 模拟 Pointer Lock API ===
              if (!window.__pointerLockHooked) {
                window.__pointerLockHooked = true;
                var _requestPointerLock = HTMLElement.prototype.requestPointerLock;
                HTMLElement.prototype.requestPointerLock = function() {
                  window.__pointerLocked = true;
                  document.dispatchEvent(new Event('pointerlockchange'));
                  document.pointerLockElement = this;
                  return Promise.resolve();
                };
                document.exitPointerLock = function() {
                  window.__pointerLocked = false;
                  document.pointerLockElement = null;
                  document.dispatchEvent(new Event('pointerlockchange'));
                };
                try {
                  Object.defineProperty(document, 'pointerLockElement', {
                    get: function() { return window.__pointerLockElement || null; },
                    set: function(v) { window.__pointerLockElement = v; },
                    configurable: true
                  });
                } catch(e) {}
              }

              // === 2. 提供视角旋转函数 ===
              window.__cameraRotate = function(dx, dy) {
                var target = document.pointerLockElement;
                if (!target) {
                  target = document.querySelector('canvas') ||
                           document.querySelector('[id*="game"]') ||
                           document.querySelector('[id*="flash"]') ||
                           document.body;
                }
                if (!target) return;
                var evt = new MouseEvent('mousemove', {
                  bubbles: true,
                  cancelable: true,
                  view: window,
                  clientX: window.innerWidth / 2,
                  clientY: window.innerHeight / 2,
                  movementX: Math.round(dx),
                  movementY: Math.round(dy)
                });
                target.dispatchEvent(evt);
                document.dispatchEvent(evt);
              };

              // === 3. 阻止拖动时的页面滚动/选择 ===
              var style = document.createElement('style');
              style.id = '__cameraRotateStyle';
              style.textContent = 'body{-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;overflow:hidden !important;}canvas{touch-action:none !important;}';
              document.head.appendChild(style);

              console.log('[CameraRotation] 视角旋转模式已启用');
            })();
        """

        /**
         * 构建画面比例 letterbox 脚本。
         * 在内置播放器页面（https://flash.local/）上对游戏容器添加 CSS letterbox，
         * 支持 4:3 / 16:9 / 16:10 / 5:4 等比例。
         *
         * 重要:只对 WAFlash 引擎生效(Ruffle 引擎用 letterbox: "fullscreen" + scale: "showAll"
         * 自身 letterbox,外层 CSS 改 ruffle-player 宽高会破坏引擎内部布局,导致画面溢出/失真)。
         *
         * @param ratio 比例字符串："4:3" / "16:9" / "16:10" / "5:4" / "auto"
         */
        fun buildAspectRatioScript(ratio: String): String {
            if (ratio == "auto" || ratio == "stretch") {
                // 恢复默认：移除自定义样式，让引擎自身设置生效。
                // ★ stretch（全屏拉伸）＝ WAFlash 画布 CSS 本就是 100%×100%
                //   铺满 —— 与 auto 同为"清理外框"即可获得满屏拉伸效果。
                return """
                (function(){
                  // ★ v4.3：优先走 waflash.html 页面内比例系统（假视口 + 输入映射）
                  if (typeof window.__wafApplyAspect === 'function') {
                    try { console.log('[WAF] applyAspect: ' + window.__wafApplyAspect('$ratio')); } catch(e) {}
                    return;
                  }
                  var old = document.getElementById('__aspectRatioStyle');
                  if (old) old.remove();
                  // 只清理 WAFlash 相关元素,Ruffle 不动(它走自身 letterbox 引擎)
                  var waflashEl = document.getElementById('waflashContainer');
                  if (waflashEl) {
                    waflashEl.style.width = '';
                    waflashEl.style.height = '';
                  }
                  var waflashCanvas = document.querySelector('canvas.waflashCanvas') || document.querySelector('#canvas');
                  if (waflashCanvas) {
                    waflashCanvas.style.width = '';
                    waflashCanvas.style.height = '';
                    waflashCanvas.style.marginLeft = '';
                    waflashCanvas.style.marginTop = '';
                  }
                })();
                """.trimIndent()
            }
            val parts = ratio.split(":")
            if (parts.size != 2) return "(function(){})();"
            val w = parts[0].toFloatOrNull() ?: return "(function(){})();"
            val h = parts[1].toFloatOrNull() ?: return "(function(){})();"
            val targetRatio = w / h
            return """
            (function(){
              // ★ v4.3：优先走 waflash.html 页面内比例系统（假视口 + 输入映射）
              if (typeof window.__wafApplyAspect === 'function') {
                try { console.log('[WAF] applyAspect: ' + window.__wafApplyAspect('$ratio')); } catch(e) {}
                return;
              }
              // Ruffle 引擎页面:Ruffle 自身 letterbox: "fullscreen" 已处理比例,不要外层 CSS 干预
              if (document.querySelector('ruffle-player')) {
                return;
              }
              // 移除旧样式
              var old = document.getElementById('__aspectRatioStyle');
              if (old) old.remove();

              var targetRatio = $targetRatio;
              var style = document.createElement('style');
              style.id = '__aspectRatioStyle';
              document.head.appendChild(style);

              // ★ v21: canvas 三级兜底 + 全 CSS 强制 —— waflash.html 的真实 DOM 不可见,
              //   旧版只试 waflashCanvas/#canvas 两个类名,不匹配时整个脚本空转
              //   (= 用户反馈"游戏没有被拉伸,拉伸的全是文本和引擎背景");
              //   inline style 赋值也会被引擎自身 resize 重设压掉,改 !important CSS 规则。
              function applyLetterbox() {
                var sw = window.innerWidth;
                var sh = window.innerHeight;
                if (sw <= 0 || sh <= 0) return;
                var c = document.querySelector('canvas.waflashCanvas') ||
                        document.querySelector('canvas#canvas') ||
                        document.querySelector('canvas');
                var sel = 'canvas';
                if (c) {
                  if (c.id) sel = '#' + c.id;
                  else if (c.className) {
                    var cls = String(c.className).trim().split(/\s+/).join('.');
                    if (cls) sel = 'canvas.' + cls;
                  }
                }
                var w, h;
                if (sw / sh > targetRatio) {
                  h = sh; w = Math.round(sh * targetRatio);
                } else {
                  w = sw; h = Math.round(sw / targetRatio);
                }
                style.textContent = [
                  'html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;background:#000!important;overflow:hidden!important;}',
                  '#waflashContainer{display:flex!important;align-items:center!important;justify-content:center!important;width:100vw!important;height:100vh!important;}',
                  sel + '{position:fixed!important;left:50%!important;top:50%!important;transform:translate(-50%,-50%)!important;width:' + w + 'px!important;height:' + h + 'px!important;margin:0!important;}'
                ].join('\n');
              }
              applyLetterbox();
              window.addEventListener('resize', applyLetterbox);
              setTimeout(applyLetterbox, 300);
              setTimeout(applyLetterbox, 1000);
              setTimeout(applyLetterbox, 3000);
            })();
            """.trimIndent()
        }

        /**
         * Ruffle 引擎页面的画面比例脚本。
         *
         * 实现思路 — 绕开 Ruffle 0.4.1 selfhosted Shadow DOM 的限制:
         * 1. Ruffle 0.4.1 selfhosted 把 ruffle-player 用 Shadow DOM 包裹,canvas 在 shadow root 内
         * 2. 外部 CSS 改 ruffle-player 的 clientWidth/Height **不影响** shadow root 内的 canvas
         * 3. Ruffle 每帧 tick 时读 canvas 的 clientWidth/Height 算 viewport,但 canvas 永远是
         *    跟随 shadow root 的 container 大小,而 container 又是 100% 跟随 ruffle-player 父级
         * 4. 但 Ruffle 0.4.1 在 player.html 中是用 appendChild(ruffle-player) 添加到 #stage,
         *    ruffle-player 直接是 #stage 的子元素,**不在 Shadow DOM 里**(Shadow DOM 是
         *    ruffle-player 元素内部,不是 #stage 内部)
         * 5. 所以 #stage 改尺寸 → ruffle-player 改尺寸 → shadow root container 改尺寸 → canvas 改尺寸
         *    → Ruffle 下一帧 tick 读新 viewport → 重新渲染
         *
         * 那为什么之前不生效?可能是 forceScale/forceAlign 在 0.4.1 不被支持,或者 SWF 内部
         * Stage.align / Stage.windowMode 改写让 should_letterbox() 返回 false。
         *
         * 这次用**纯 CSS + JS 控制 #stage 物理尺寸** + **强制触发 Ruffle 重算 viewport**
         * (调 window.dispatchEvent('resize') 触发 ruffle-player 元素 resize 回调)。
         */
        fun buildRuffleAspectRatioScript(ratio: String): String {
            if (ratio == "auto") {
                // auto：清掉外框 letterbox 样式、#stage 回归 player.html 默认的
                //   fixed inset:0 全屏（引擎 showAll + letterbox 属预期行为）。
                return """
                (function(){
                  var old = document.getElementById('__ruffleAspectRatioStyle');
                  if (old) old.remove();
                  var stage = document.getElementById('stage');
                  if (stage && !stage.classList.contains('box-custom')) {
                    stage.style.cssText = '';
                  }
                })();
                """.trimIndent()
            }
            if (ratio == "stretch") {
                // ★★ v49 全屏拉伸修复：与比例模式（16:9 等，实测有效）走【完全
                //   相同的主动几何强制管线】—— 显式像素尺寸 + 强制 CSS
                //   （html/body 复位 + #stage fixed 居中 + ruffle-player 100%）
                //   + resize 派发。旧版只"被动清理"（清样式赌默认样式撑满 +
                //   赌引擎 exactFit），任何残留（前次比例盒脚本挂的 resize
                //   监听器会不断把比例盒样式加回来 / #stage 内联宽高残留 /
                //   ruffle-player 未被强制 100%）都会让 stretch 悄悄失效 ——
                //   正是"只有 16:9 生效、全屏拉伸没效果"的根因。
                //   stretch = 视口尺寸的"比例盒"；真正的拉伸仍由加载参数完成
                //   （scale=exactFit + letterbox=off，NavHelper/applyAspectRatio
                //   的重载链），这里只保证外框几何严格铺满视口。
                return """
                (function(){
                  var stage = document.getElementById('stage');
                  if (!stage || stage.classList.contains('box-custom')) return;
                  // 强制 CSS（与比例盒同款；重复注入无害——先清再加）
                  var old = document.getElementById('__ruffleAspectRatioStyle');
                  if (old) old.remove();
                  var st = document.createElement('style');
                  st.id = '__ruffleAspectRatioStyle';
                  st.textContent = [
                    'html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;background:#000!important;overflow:hidden!important;}',
                    '#stage{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;margin:auto!important;background:#000!important;box-sizing:border-box!important;}',
                    '#stage > ruffle-player{display:block!important;width:100%!important;height:100%!important;margin:0!important;padding:0!important;}'
                  ].join('\n');
                  document.head.appendChild(st);
                  function applyFull() {
                    var w = Math.max(1, window.innerWidth);
                    var h = Math.max(1, window.innerHeight);
                    if (Math.abs(stage.clientWidth - w) > 2 || Math.abs(stage.clientHeight - h) > 2) {
                      stage.style.width = w + 'px';
                      stage.style.height = h + 'px';
                      window.dispatchEvent(new Event('resize'));
                    }
                  }
                  applyFull();
                  window.addEventListener('resize', applyFull);
                  setTimeout(applyFull, 300);
                  setTimeout(applyFull, 1000);
                  setTimeout(applyFull, 3000);
                })();
                """.trimIndent()
            }
            val parts = ratio.split(":")
            if (parts.size != 2) return "(function(){})();"
            val w = parts[0].toFloatOrNull() ?: return "(function(){})();"
            val h = parts[1].toFloatOrNull() ?: return "(function(){})();"
            val targetRatio = w / h
            return """
            (function(){
              var targetRatio = $targetRatio;
              function applyLetterbox() {
                var sw = window.innerWidth;
                var sh = window.innerHeight;
                if (sw <= 0 || sh <= 0) return;
                // ★ v21: #stage 不存在时(player.html 版本差异/改版)直接以
                //   ruffle-player 元素为 letterbox 容器兜底 —— 旧版在此 return,
                //   只剩黑底 CSS 生效("游戏没有被拉伸,拉伸的全是文本和引擎背景")。
                var stage = document.getElementById('stage');
                var stageIsPlayer = false;
                if (!stage) {
                  stage = document.querySelector('ruffle-player');
                  if (!stage) return;
                  stageIsPlayer = true;
                }
                if (stage.classList.contains('box-custom')) return;

                // 注入或更新 CSS
                var old = document.getElementById('__ruffleAspectRatioStyle');
                if (old) old.remove();
                var style = document.createElement('style');
                style.id = '__ruffleAspectRatioStyle';
                style.textContent = [
                  'html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;background:#000!important;overflow:hidden!important;}',
                  // #stage: fixed 居中,left/top/right/bottom 留 0 让 margin:auto 居中
                  '#stage{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;margin:auto!important;background:#000!important;box-sizing:border-box!important;}',
                  '#stage > ruffle-player{display:block!important;width:100%!important;height:100%!important;margin:0!important;padding:0!important;}'
                ].join('\n');
                document.head.appendChild(style);

                // 计算外框尺寸:在视口内按 targetRatio 居中,留 4px 安全边距
                var availW = Math.max(1, sw - 4);
                var availH = Math.max(1, sh - 4);
                var boxW, boxH;
                if (availW / availH > targetRatio) {
                  boxH = availH;
                  boxW = Math.round(boxH * targetRatio);
                } else {
                  boxW = availW;
                  boxH = Math.round(boxW / targetRatio);
                }
                stage.style.width = boxW + 'px';
                stage.style.height = boxH + 'px';
                if (stageIsPlayer) {
                  // ruffle-player 直接做容器时,#stage 的 CSS 规则够不到它,
                  // 需要 inline fixed 居中定位
                  stage.style.position = 'fixed';
                  stage.style.left = '50%';
                  stage.style.top = '50%';
                  stage.style.transform = 'translate(-50%,-50%)';
                }
                // 触发 ruffle-player 元素 resize 回调,让 Ruffle 下一帧用新 viewport
                window.dispatchEvent(new Event('resize'));
              }
              applyLetterbox();
              window.addEventListener('resize', applyLetterbox);
              // ruffle-player 是异步创建的,#stage 尺寸会被 player.html 脚本改回 100% 100%,
              // 因此需要定时重试几次以确保最终生效。
              setTimeout(applyLetterbox, 100);
              setTimeout(applyLetterbox, 300);
              setTimeout(applyLetterbox, 800);
              setTimeout(applyLetterbox, 2000);
              setTimeout(applyLetterbox, 4000);
            })();
            """.trimIndent()
        }
    }
}
