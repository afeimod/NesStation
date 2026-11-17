package com.nesstation.app.ui.swf

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Full-featured [WebViewClient] that provides:
 *
 * 1. **Virtual domain interception** — `https://flash.local/` requests are served
 *    from the app's bundled assets or the on-device SWF file.
 * 2. **Flash plugin polyfill** — [buildFlashInjectScript] generates a script that
 *    fakes Flash plugin support and hooks SWF creation on web pages.
 * 3. **Remote SWF interception** — `.swf` requests are downloaded natively with
 *    CORS headers and cookie forwarding, bypassing WebView cross-origin limits.
 * 4. **Ad blocking** — common ad domains are blocked when enabled.
 *
 * Routing for `flash.local`:
 *  - `flash.local/local.swf`   -> the SWF file referenced by [swfFilePath]
 *  - `flash.local/player.html`  -> assets `player.html`
 *  - `flash.local/waflash.html` -> assets `waflash.html`
 *  - `flash.local/ruffle/...`   -> assets `ruffle/...`
 *  - `flash.local/waflash/...`  -> assets `waflash/...`
 *
 * @param swfFilePath Path of the local SWF file to play (for SwfPlayerScreen).
 *                    Pass `""` when used only for web browsing (WebGameScreen).
 * @param blockAds    Whether to block known ad domains.
 */
open class FlashWebViewClient(
    private val swfFilePath: String = "",
    private val blockAds: Boolean = false,
    /** Callback for SWF interception and engine selection (matches 3.3-fix2 design). */
    private val callback: Callback? = null
) : WebViewClient() {

    /**
     * 回调接口（参考 3.3-fix2 GameWebViewClient.Callback）。
     * 由 WebGameScreen / SwfPlayerScreen 实现。
     */
    interface Callback {
        /** Flash 引擎是否启用（false → 跳过所有 Flash 注入） */
        fun isFlashEnabled(): Boolean = true
        /** 当前引擎（"ruffle" / "waflash"） */
        fun getFlashEngine(): String = "ruffle"
        /** Ruffle 画质（low/medium/high/best） */
        fun getFlashQuality(): String = "high"
        /** Ruffle 自动播放 */
        fun isFlashAutoplay(): Boolean = true
        /** 给定 URL 是否应该注入 Flash 支持（可排除登录/账号/接口等） */
        fun shouldInjectRuffle(url: String?): Boolean = isFlashEnabled()
        /** 当 .swf 链接被拦截时回调（用户直接点击 swf 文件） */
        fun onSwfIntercepted(swfUrl: String, pageUrl: String) {}
        /** 提取 SWF 列表回调（扫描页面中的 SWF URL） */
        fun onSwfFound(json: String) {}
    }

    companion object {
        private const val TAG = "FlashWebViewClient"

        /** Common ad domains to block */
        private val AD_HOSTS = setOf(
            "googleads.g.doubleclick.net", "pagead2.googlesyndication.com",
            "ad.4399.com", "stat.4399.com", "analytics.4399.com"
        )

        /** CORS headers for all intercepted responses */
        private val CORS_HEADERS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-cache"
        )

        /**
         * Build the Flash support polyfill + engine injection script.
         *
         * This script:
         * 1. Fakes Flash plugin support (navigator.plugins, navigator.mimeTypes, ActiveXObject)
         * 2. Spoofs document.referrer
         * 3. For Ruffle mode: injects Ruffle polyfill configuration
         *    (含 simhei.ttf 中文字体源 + defaultFonts) 并加载 ruffle.js
         * 4. For WAFlash mode: hooks SWF creation methods (swfobject.embedSWF,
         *    createFlash, AC_FL_RunContent) and DOM MutationObserver to detect
         *    SWF content and notify Android via window.Android.openSwf.
         *    目标页选择（waflash.html 还是 player.html）由 Android 端按当前
         *    [com.nesstation.app.ui.swf.FlashPrefs.engine] 决定。
         *
         * @param pageUrl    The page URL (for referrer spoofing)
         * @param engine     "ruffle" or "waflash"
         * @param autoplay   Whether to autoplay Flash content
         * @param quality    画质（low/medium/high/best）
         * @return The injection script as an HTML string
         */
        fun buildFlashInjectScript(
            pageUrl: String,
            engine: String = "ruffle",
            autoplay: Boolean = true,
            quality: String = "high",
            scale: String? = null,
            letterbox: String? = null
        ): String {
            val isWaflash = engine == "waflash"
            val autoplayStr = if (autoplay) "on" else "off"
            // scale/letterbox 归一化：与 Ruffle 官方合法值保持一致
            // 合法值参考 ruffle core/src/config.rs:42-50 (letterbox) +
            //                 ruffle core/src/display_object/stage.rs:1020-1023 (scale)
            val scaleStr = when (scale) {
                "showAll", "noBorder", "exactFit", "noScale" -> scale
                else -> "showAll"
            }
            val letterboxStr = when (letterbox) {
                "off", "fullscreen", "on" -> letterbox
                else -> "on"
            }
            // 通过 flash.local 虚拟域名加载 Ruffle，FlashWebViewClient.shouldInterceptRequest
            // 会从 assets 提供 ruffle.js / core.ruffle.*.js / .wasm / simhei.ttf
            val ruffleScriptUrl = "https://flash.local/ruffle/ruffle.js"
            val ruffleConfigObj = """
                {
                  publicPath: 'https://flash.local/ruffle/',
                  polyfills: true,
                  autoplay: '$autoplayStr',
                  unmuteOverlay: 'visible',
                  letterbox: '$letterboxStr',
                  backgroundColor: '#000000',
                  upgradeToHttps: true,
                  allowScriptAccess: true,
                  scale: '$scaleStr',
                  // 通用 4399 页面不强制 forceScale，避免破坏 SWF 内部 stage.scaleMode 行为。
                  // 具体画面比例约束由 buildRuffleAspectRatioScript + Ruffle letterbox 配合完成。
                  quality: '$quality',
                  allowFullscreen: false,
                  splashScreen: true,
                  preloader: true,
                  logLevel: 'warn',
                  maxExecutionDuration: 30,
                  fontSources: ["https://flash.local/ruffle/simhei.ttf"],
                  defaultFonts: {
                    sans: ['SimHei'],
                    serif: ['SimHei'],
                    typewriter: ['SimHei'],
                    japaneseGothic: ['SimHei'],
                    japaneseGothicMono: ['SimHei'],
                    japaneseMincho: ['SimHei'],
                    chineseSimplified: ['SimHei']
                  }
                }
            """.trimIndent()

            return """
            <script>
            (function(){
              if(window.__flashPolyfilled)return;window.__flashPolyfilled=true;

              // === 1. Fake Flash plugin support (must run before page JS) ===
              try {
                var fp = {name:'Shockwave Flash',filename:'libflashplayer.so',
                  description:'Shockwave Flash 32.0 r0',length:1,
                  0:{type:'application/x-shockwave-flash',suffixes:'swf',description:'Shockwave Flash'}};
                fp.namedItem = function(n){ return (n === 'Shockwave Flash') ? fp : null; };
                fp.item = function(i){ return i === 0 ? fp : null; };
                fp.refresh = function(){};
                var _plugins = navigator.plugins || {};
                if (_plugins.namedItem) {
                  fp.namedItem = function(n){ return (n === 'Shockwave Flash') ? fp : _plugins.namedItem.call(_plugins, n); };
                }
                if (_plugins.item) {
                  fp.item = function(i){ return i === 0 ? fp : _plugins.item.call(_plugins, i); };
                }
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
                var fm = {type:'application/x-shockwave-flash',suffixes:'swf',
                  description:'Shockwave Flash',enabledPlugin:fp};
                var _mimes = navigator.mimeTypes || {};
                Object.defineProperty(navigator,'mimeTypes',{
                  get:function(){
                    try { if (!_mimes['application/x-shockwave-flash'])
                      _mimes['application/x-shockwave-flash'] = fm; } catch(e) {}
                    return _mimes;
                  },
                  configurable: true
                });
                window.ActiveXObject = function(n){
                  if(n&&/ShockwaveFlash/i.test(n))
                    return {SetVariable:function(){},Variable:function(){return ''}};
                  throw new Error('x');
                };
              } catch(e) {}

              // === 2. Spoof document.referrer ===
              try {
                Object.defineProperty(document,'referrer',
                  {get:function(){return '$pageUrl';},configurable:true});
              } catch(e) {}

              // === 3. Engine-specific injection ===
              ${if (!isWaflash) """
              // --- Ruffle polyfill ---
              // 关键修复 1：Ruffle config 必须包含 publicPath（指向 ruffle 资源目录），
              //            否则 Ruffle 找不到 core.ruffle.*.js 和 .wasm
              // 关键修复 2：fontSources/defaultFonts 必须在 Ruffle 引擎初始化前注入，
              //            否则中文字体不会显示（4399 中文 Flash 游戏会变成方块）
              window.RufflePlayer = window.RufflePlayer || {};
              window.RufflePlayer.config = $ruffleConfigObj;
              var ruffleScript = document.createElement('script');
              ruffleScript.src = '$ruffleScriptUrl';
              ruffleScript.async = true;
              ruffleScript.onload = function() {
                console.log('[Ruffle] 引擎加载完成');
                try {
                  if (window.RufflePlayer && window.RufflePlayer.newest) {
                    var r = window.RufflePlayer.newest();
                    if (r && r.init) r.init();
                  }
                } catch(e) { console.warn('[Ruffle] init:', e); }
              };
              ruffleScript.onerror = function(e) {
                console.error('[Ruffle] 加载失败:', '$ruffleScriptUrl', e);
              };
              document.head.appendChild(ruffleScript);
              """ else """
              // --- WAFlash mode: hook Flash creation, notify Android ---
              // 当前模式：用户在菜单里选了 WAFlash。检测到 SWF 时通知 Android 端
              // window.Android.openSwf(url, baseUrl)；Android 端按当前引擎设置
              // 决定跳到 waflash.html 还是 player.html，并保留 swfUrl + baseUrl。
              // 这里不要直接 location.href 跳走（会丢失 baseUrl、来源页面等信息）。
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
                }
                // 兜底：Android 端接口未注入时，构造一个完整 URL 走默认 WAFlash 路径
                else {
                  window.location.href = 'https://flash.local/waflash.html?swf=' + encodeURIComponent(swfUrl);
                }
              }
              // hook swfobject.embedSWF
              if (window.swfobject && window.swfobject.embedSWF) {
                var oe = window.swfobject.embedSWF;
                window.swfobject.embedSWF = function(){
                  __wafRedirect(arguments[0],window.location.href);
                  return oe.apply(this,arguments);
                };
              } else {
                var _swo;
                Object.defineProperty(window,'swfobject',{
                  configurable:true,
                  get:function(){return _swo;},
                  set:function(v){
                    _swo=v;
                    if(v&&v.embedSWF){
                      var o=v.embedSWF;
                      v.embedSWF=function(){
                        __wafRedirect(arguments[0],window.location.href);
                        return o.apply(this,arguments);
                      };
                    }
                  }
                });
              }
              // hook createFlash (mflash-player)
              function __hookCF(obj){
                if(!obj||!obj.createFlash||obj.__wafH)return;
                obj.__wafH=true;
                var o=obj.createFlash;
                obj.createFlash=function(){
                  var u=null;
                  if(typeof arguments[0]==='string')u=arguments[0];
                  else if(arguments[0]&&typeof arguments[0]==='object')
                    u=arguments[0].url||arguments[0].src||arguments[0].swf||arguments[0].movie;
                  if(u)__wafRedirect(u,window.location.href);
                  return o.apply(this,arguments);
                };
              }
              if(window['mflash-player'])__hookCF(window['mflash-player']);
              var _mp;
              Object.defineProperty(window,'mflash-player',{
                configurable:true,
                get:function(){return _mp;},
                set:function(v){_mp=v;__hookCF(v);}
              });
              // hook AC_FL_RunContent
              if(window.AC_FL_RunContent){
                var oAC=window.AC_FL_RunContent;
                window.AC_FL_RunContent=function(){
                  var a=Array.prototype.slice.call(arguments);
                  for(var i=0;i<a.length-1;i++){
                    if((a[i]==='src'||a[i]==='movie')&&a[i+1])
                      __wafRedirect(a[i+1],window.location.href);
                  }
                  return oAC.apply(this,arguments);
                };
              }
              // DOM detection
              function __checkFlash(){
                if(__wafRedirected)return;
                var sel='object[type="application/x-shockwave-flash"],' +
                        'embed[type="application/x-shockwave-flash"],' +
                        'object[data$=".swf" i],embed[src$=".swf" i],' +
                        'object[classid*="D27CDB6E" i]';
                var els=document.querySelectorAll(sel);
                for(var i=0;i<els.length;i++){
                  var s=els[i].getAttribute('data')||els[i].getAttribute('src')||'';
                  if(!s){
                    var ps=els[i].querySelectorAll('param[name="movie"],param[name="src"]');
                    for(var j=0;j<ps.length;j++){
                      var v=ps[j].getAttribute('value')||'';
                      if(v){s=v;break;}
                    }
                  }
                  if(s)__wafRedirect(s,window.location.href);
                }
              }
              __checkFlash();
              if(window.MutationObserver){
                var mo=new MutationObserver(function(){
                  if(__wafRedirected){mo.disconnect();return;}
                  __checkFlash();
                });
                try{
                  mo.observe(document.documentElement||document.body||document,
                    {childList:true,subtree:true});
                }catch(e){}
                setTimeout(function(){mo.disconnect();},15000);
              }
              """}
            })();
            </script>
            """.trimIndent()
        }

        /**
         * SWF 嗅探脚本（参考 3.3-fix2 GameActivity.SWF_SNIFFER_SCRIPT）。
         *
         * 用法：在 WebView 中 evaluateJavascript(SWF_SNIFFER_SCRIPT) 执行。
         * 脚本会扫描页面 DOM、iframe、Performance API，找到所有 .swf URL，
         * 通过 window.Android.onSwfFound(jsonArray) 回调到原生。
         */
        const val SWF_SNIFFER_SCRIPT = """
            (function(){
              try {
                var results = [];
                var seen = {};
                function add(url, title) {
                  if (!url) return;
                  if (seen[url]) return;
                  seen[url] = true;
                  results.push({url: url, title: title || '', size: ''});
                }
                // 1. 扫描 <object>/<embed> 元素
                try {
                  var sels = [
                    'object[type="application/x-shockwave-flash"]',
                    'embed[type="application/x-shockwave-flash"]',
                    'object[data\$=".swf" i]',
                    'embed[src\$=".swf" i]',
                    'object[classid*="D27CDB6E" i]'
                  ];
                  var els = document.querySelectorAll(sels.join(','));
                  for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    var s = el.getAttribute('data') || el.getAttribute('src') || el.getAttribute('movie') || '';
                    if (!s) {
                      var ps = el.querySelectorAll('param[name="movie"],param[name="src"]');
                      for (var j = 0; j < ps.length; j++) {
                        var v = ps[j].getAttribute('value') || '';
                        if (v) { s = v; break; }
                      }
                    }
                    if (s) add(s, el.getAttribute('title') || el.getAttribute('name') || '');
                  }
                } catch(e) {}
                // 2. 扫描 iframe 中的 Flash
                try {
                  var iframes = document.querySelectorAll('iframe');
                  for (var k = 0; k < iframes.length; k++) {
                    try {
                      var doc = iframes[k].contentDocument || (iframes[k].contentWindow && iframes[k].contentWindow.document);
                      if (!doc) continue;
                      var sels2 = ['object[type="application/x-shockwave-flash"]','embed[type="application/x-shockwave-flash"]','object[data\$=".swf" i]','embed[src\$=".swf" i]'];
                      var els2 = doc.querySelectorAll(sels2.join(','));
                      for (var m = 0; m < els2.length; m++) {
                        var s2 = els2[m].getAttribute('data') || els2[m].getAttribute('src') || els2[m].getAttribute('movie') || '';
                        if (s2) add(s2, els2[m].getAttribute('title') || '');
                      }
                    } catch(ee) {}
                  }
                } catch(e) {}
                // 3. 扫描 Performance API 的网络请求
                try {
                  if (window.performance && performance.getEntries) {
                    var entries = performance.getEntries();
                    for (var n = 0; n < entries.length; n++) {
                      var u = entries[n].name || '';
                      if ((/\.swf(\?|$)/i).test(u) || (/\/dw-\d+/).test(u)) {
                        add(u, '');
                      }
                    }
                  }
                } catch(e) {}
                // 4. 兜底：扫描所有 script src / link href / a href
                try {
                  var sels3 = document.querySelectorAll('script[src*=".swf"],link[href*=".swf"],a[href*=".swf"]');
                  for (var p = 0; p < sels3.length; p++) {
                    var u2 = sels3[p].getAttribute('src') || sels3[p].getAttribute('href') || '';
                    if (u2) add(u2, '');
                  }
                } catch(e) {}
                // 5. 上报
                if (window.Android && window.Android.onSwfFound) {
                  try { window.Android.onSwfFound(JSON.stringify(results)); } catch(e) { console.error('[SWF sniffer] onSwfFound:', e); }
                } else {
                  console.log('[SWF sniffer] found ' + results.length + ' swf(s):', results);
                }
              } catch(e) {
                console.error('[SWF sniffer]', e);
              }
            })();
        """
    }

    // -------------------------------------------------------------------------
    // shouldOverrideUrlLoading — intercept .swf links
    // -------------------------------------------------------------------------

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString() ?: return false
        // Intercept direct .swf links
        if (url.endsWith(".swf", ignoreCase = true)) {
            Log.d(TAG, "Intercepted SWF link: $url")
            // 优先用 callback（3.3 模式），否则兜底走 JS 接口
            if (callback != null) {
                callback.onSwfIntercepted(url, view.url ?: url)
            } else {
                view.evaluateJavascript(
                    "if(window.Android&&window.Android.openSwf){window.Android.openSwf('$url',window.location.href);}",
                    null
                )
            }
            return true
        }
        return false
    }

    // -------------------------------------------------------------------------
    // shouldInterceptRequest — virtual domain, SWF, ad blocking
    // -------------------------------------------------------------------------

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url?.toString() ?: return null

        // 1. Ad blocking
        if (blockAds && AD_HOSTS.any { url.contains(it) }) {
            return WebResourceResponse("text/plain", "UTF-8",
                ByteArrayInputStream(ByteArray(0)))
        }

        // 2. Intercept flash.local virtual domain
        if (url.contains("flash.local")) {
            val path = url.substringAfter("flash.local/").substringBefore("?")
            if (path.isEmpty()) return null

            return when {
                path == "local.swf" -> {
                    // 仅在设置了 swfFilePath 时才拦截（SwfPlayerScreen 用，WebGameScreen 不用）
                    if (swfFilePath.isEmpty()) null else interceptLocalSwf(view)
                }
                else -> interceptAsset(view, path)
            }
        }

        // 3. Intercept file:///android_asset/waflash/ and file:///android_asset/ruffle/
        if (url.startsWith("file:///android_asset/")) {
            val assetPath = url.removePrefix("file:///android_asset/").substringBefore("?")
            return interceptAsset(view, assetPath)
        }

        // 4. Intercept local SWF files (content:// or file://)
        if (swfFilePath.isNotEmpty() &&
            (url.startsWith("content://") || url.startsWith("file://"))) {
            return interceptLocalFile(view, url)
        }

        // 5. Intercept remote SWF requests (native download with CORS + cookies)
        if (isSwfRequest(url)) {
            return interceptRemoteSwf(view, url, request)
        }

        return super.shouldInterceptRequest(view, request)
    }

    /** Check if a URL is a SWF resource request */
    private fun isSwfRequest(url: String): Boolean {
        return url.endsWith(".swf", ignoreCase = true) ||
            url.contains(".swf?", ignoreCase = true) ||
            (url.contains("4399.com") && (url.contains("/dw-") ||
                url.contains("flash_tm3") || url.contains("flash20")))
    }

    // -------------------------------------------------------------------------
    // Local SWF file
    // -------------------------------------------------------------------------

    private fun interceptLocalSwf(view: WebView): WebResourceResponse? {
        return try {
            val input = openSwfStream(view)
            WebResourceResponse(
                "application/x-shockwave-flash", null, 200, "OK",
                corsHeaders(), input
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun openSwfStream(view: WebView): InputStream {
        return when {
            swfFilePath.startsWith("content://") -> {
                val resolver: ContentResolver = view.context.contentResolver
                resolver.openInputStream(Uri.parse(swfFilePath))
                    ?: throw FileNotFoundException(swfFilePath)
            }
            swfFilePath.startsWith("file://") -> {
                val resolved = Uri.parse(swfFilePath).path
                    ?: swfFilePath.removePrefix("file://")
                FileInputStream(File(resolved))
            }
            else -> FileInputStream(File(swfFilePath))
        }
    }

    // -------------------------------------------------------------------------
    // Local file interception (content:// or file://)
    // -------------------------------------------------------------------------

    private fun interceptLocalFile(view: WebView, url: String): WebResourceResponse? {
        return try {
            Log.d(TAG, "Reading local file: $url")
            val uri = Uri.parse(url)
            val data = view.context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw FileNotFoundException(url)
            Log.d(TAG, "Local file read: ${data.size} bytes")
            WebResourceResponse(
                "application/x-shockwave-flash", null, 200, "OK",
                corsHeaders() + ("Content-Type" to "application/x-shockwave-flash"),
                ByteArrayInputStream(data)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Local file read failed: ${e.message}")
            WebResourceResponse(
                "application/x-shockwave-flash", null, 404, "Not Found",
                corsHeaders(), ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    // -------------------------------------------------------------------------
    // Remote SWF interception (native download with CORS + cookies)
    // -------------------------------------------------------------------------

    private val swfCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private fun interceptRemoteSwf(
        webView: WebView,
        url: String,
        request: WebResourceRequest
    ): WebResourceResponse? {
        // Check cache first (Ruffle may request the same SWF multiple times)
        swfCache[url]?.let { data ->
            Log.d(TAG, "SWF cache hit: $url (${data.size} bytes)")
            return WebResourceResponse(
                "application/x-shockwave-flash", null, 200, "OK",
                corsHeaders(), ByteArrayInputStream(data)
            )
        }

        return try {
            Log.d(TAG, "Downloading SWF: $url")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = "GET"

            // Forward cookies from WebView
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            if (cookies != null) {
                conn.setRequestProperty("Cookie", cookies)
            }

            // Forward request headers
            val requestHeaders = request.requestHeaders
            requestHeaders["Referer"]?.let { conn.setRequestProperty("Referer", it) }
            requestHeaders["User-Agent"]?.let { conn.setRequestProperty("User-Agent", it) }

            conn.connect()
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val data = conn.inputStream.use { it.readBytes() }
                Log.d(TAG, "SWF downloaded: ${data.size} bytes")

                // Cache the SWF
                swfCache[url] = data

                WebResourceResponse(
                    "application/x-shockwave-flash", null, 200, "OK",
                    corsHeaders() + ("Content-Type" to "application/x-shockwave-flash"),
                    ByteArrayInputStream(data)
                )
            } else {
                Log.w(TAG, "SWF download failed: HTTP $responseCode")
                WebResourceResponse(
                    "application/x-shockwave-flash", null, responseCode,
                    conn.responseMessage ?: "Error",
                    corsHeaders(), ByteArrayInputStream(ByteArray(0))
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "SWF download error: ${e.message}")
            WebResourceResponse(
                "application/x-shockwave-flash", null, 502, "Bad Gateway",
                corsHeaders(), ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    // -------------------------------------------------------------------------
    // Assets
    // -------------------------------------------------------------------------

    private fun interceptAsset(view: WebView, assetPath: String): WebResourceResponse? {
        return try {
            val input = view.context.assets.open(assetPath)
            val (mime, charset) = mimeFor(assetPath)
            WebResourceResponse(mime, charset, 200, "OK", corsHeaders(), input)
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun mimeFor(assetPath: String): Pair<String, String?> {
        return when {
            assetPath.endsWith(".wasm", ignoreCase = true) -> "application/wasm" to null
            assetPath.endsWith(".js", ignoreCase = true) -> "application/javascript" to "UTF-8"
            assetPath.endsWith(".html", ignoreCase = true) -> "text/html" to "UTF-8"
            assetPath.endsWith(".css", ignoreCase = true) -> "text/css" to "UTF-8"
            assetPath.endsWith(".ttf", ignoreCase = true) -> "font/ttf" to null
            assetPath.endsWith(".woff", ignoreCase = true) -> "font/woff" to null
            assetPath.endsWith(".woff2", ignoreCase = true) -> "font/woff2" to null
            assetPath.endsWith(".otf", ignoreCase = true) -> "font/otf" to null
            assetPath.endsWith(".data", ignoreCase = true) -> "application/octet-stream" to null
            assetPath.endsWith(".swf", ignoreCase = true) -> "application/x-shockwave-flash" to null
            else -> "application/octet-stream" to null
        }
    }

    private fun corsHeaders(): Map<String, String> = CORS_HEADERS
}
