package com.nesstation.app.flash.webview

import com.nesstation.app.flash.data.PrefsManager

/**
 * Flash 引擎注入器（双引擎：Ruffle + WAFlash）。
 *
 * 核心原理：
 * 本地引擎文件（ruffle.js / .wasm / simhei.ttf）放在 assets 目录中。
 * 从 https 页面直接用 <script src="file:///android_asset/..."> 加载会被跨域策略阻止。
 * 解决方案：使用虚拟 URL https://flash.local/ 作为脚本地址，
 * GameWebViewClient.shouldInterceptRequest 会拦截 flash.local 的请求并从 assets 返回内容。
 *
 * 引擎选择：
 * - "ruffle"：Ruffle (Rust + WebAssembly)，AS1/2 支持率 95%，AS3 约 60%
 *   ★ 引擎已升级上游 0.7.1（旧版 0.4.1）：包含大量移动端 WebView 的
 *     viewport / Stage.scaleMode / letterbox 渲染修复（"拉伸设置不生效"
 *     的上游修复）。本地模式下自带 simhei.ttf 字体，中文显示正常
 * - "waflash"：WAFlash (AS2/AS3 完整支持, Canvas渲染)
 */
object RuffleInjector {

    /** 虚拟本地资源前缀，shouldInterceptRequest 会拦截此域名的请求 */
    private const val LOCAL_BASE = "https://flash.local/"

    /** 根据引擎返回脚本地址（shouldInterceptRequest 会拦截并从 assets 返回） */
    fun scriptUrl(): String = when (PrefsManager.flashEngine) {
        "waflash" -> "${LOCAL_BASE}waflash/waflash.min.js"
        "swf2js" -> "${LOCAL_BASE}swf2js/swf2js.js"
        else -> when (PrefsManager.flashCdn) {
            "unpkg"  -> "https://unpkg.com/@ruffle-rs/ruffle"
            "local"  -> "${LOCAL_BASE}ruffle/ruffle.js"
            else     -> "https://cdn.jsdelivr.net/npm/@ruffle-rs/ruffle@0.3.0/ruffle.min.js"
        }
    }

    /** ruffle.js 的 publicPath（Ruffle 用此路径加载 core.ruffle.*.js 和 .wasm） */
    fun publicPath(): String = when (PrefsManager.flashEngine) {
        "waflash" -> "${LOCAL_BASE}waflash/"
        else -> when (PrefsManager.flashCdn) {
            "unpkg"  -> "https://unpkg.com/@ruffle-rs/ruffle/"
            "local"  -> "${LOCAL_BASE}ruffle/"
            else     -> "https://cdn.jsdelivr.net/npm/@ruffle-rs/ruffle@0.3.0/"
        }
    }

    /** 画质映射到 Ruffle 的 quality 选项 */
    private fun quality(): String = when (PrefsManager.flashQuality) {
        "low"    -> "low"
        "medium" -> "medium"
        "best"   -> "best"
        else     -> "high"
    }

    /**
     * 用户指定的 scale 归一化为 Ruffle 官方合法值。
     * 合法值参考 ruffle core/src/display_object/stage.rs:1020-1023
     *   showAll / noBorder / exactFit / noScale
     */
    private fun normalizeScale(v: String?): String = when (v) {
        "showAll", "noBorder", "exactFit", "noScale" -> v
        else -> "showAll"
    }

    /**
     * letterbox 字符串归一化为 Ruffle 官方合法值。
     * 合法值参考 ruffle core/src/config.rs:42-50
     *   off / fullscreen / on
     */
    private fun normalizeLetterbox(v: String?): String = when (v) {
        "off", "fullscreen", "on" -> v
        else -> "fullscreen"
    }

    /** 当前 scale 字符串（已归一化） */
    private fun currentScale(): String =
        normalizeScale(PrefsManager.sp.getString("flash_scale", null))

    /**
     * letterbox 优先读 flash_letterbox 偏好（与 flash_scale 同源，applyAspectRatio
     * 写入）—— 旧版完全不读这个键，只从 game_aspect_ratio 自行推导，且对
     * stretch/比例模式错传 "fullscreen"/"on"：Ruffle letterbox 渲染路径在部分
     * 版本里优先于 scale，外层已缩成指定比例框时引擎又叠黑边 = exactFit 永不
     * 生效（polyfill 页面上“拉伸无效果”的直接根因之一）。
     * 偏好缺失时回退推导：stretch/比例模式 → off（外框比例框 + exactFit 填满），
     * auto → fullscreen（保持原比例，黑边属预期）。
     */
    private fun currentLetterbox(): String {
        val pref = PrefsManager.sp.getString("flash_letterbox", null)
        if (pref != null && pref in setOf("on", "off", "fullscreen")) return pref
        return when (PrefsManager.gameAspectRatio) {
            "stretch" -> "off"
            "4:3", "16:9", "16:10", "5:4" -> "off"
            else -> "fullscreen"
        }
    }

    /**
     * 指定具体画面比例时，需要 forceScale=true 防止 SWF 内部
     * Stage.scaleMode = ... 把外层 letterbox 抵消掉（参考 ruffle forceScale 配置项）。
     */
    private fun currentForceScale(): Boolean = PrefsManager.gameAspectRatio != "auto"

    /**
     * 同时需要 forceAlign=true 防止 SWF 内部 Stage.align = "..." 改写我们的对齐。
     * 原因：Ruffle 的 should_letterbox() 要求 align.is_empty()（ruffle core/src/display_object/stage.rs:475），
     * 如果 SWF 在第一帧 Stage.align = "TL" 等，会让 should_letterbox() 返回 false，外框黑边就消失。
     * 参考 ruffle core/src/display_object/stage.rs:415-420 set_align 也是 respect forced_align。
     */
    private fun currentForceAlign(): Boolean = PrefsManager.gameAspectRatio != "auto"

    /** 是否使用本地引擎（本地模式下有 simhei.ttf 字体文件可用） */
    private fun isLocalRuffle(): Boolean =
        PrefsManager.flashEngine != "waflash" &&
        PrefsManager.flashEngine != "swf2js" &&
        PrefsManager.flashCdn == "local"

    /**
     * 生成 fontSources / defaultFonts 配置片段。
     * 仅本地 Ruffle 模式下注入 simhei.ttf 字体，解决 Flash 游戏中文显示问题。
     * CDN 模式下无本地字体文件，跳过字体配置。
     */
    private fun fontConfigScript(): String =
        if (isLocalRuffle()) """
                ,"fontSources": ["${LOCAL_BASE}ruffle/simhei.ttf"]
                ,"defaultFonts": {
                  "sans": ["SimHei"],
                  "serif": ["SimHei"],
                  "typewriter": ["SimHei"],
                  "japaneseGothic": ["SimHei"],
                  "japaneseGothicMono": ["SimHei"],
                  "japaneseMincho": ["SimHei"]
                }""".trimIndent()
        else ""

    /** 引擎配置脚本（在引擎 JS 之前执行）。
     *  WAFlash 不需要 polyfill 配置（使用独立播放器页面）。 */
    fun configScript(): String = when (PrefsManager.flashEngine) {
        "swf2js" -> """
            (function(){
              window.__swf2jsConfig = { autoLoad: true };
            })();
        """.trimIndent()
        "waflash" -> ""  // WAFlash 使用独立播放器页面，不需要页面注入
        else -> """
            (function(){
              window.RufflePlayer = window.RufflePlayer || {};
              window.RufflePlayer.config = {
                "publicPath": "${publicPath()}",
                "polyfills": true,
                "autoplay": "${if (PrefsManager.isFlashAutoplay) "on" else "off"}",
                "unmuteOverlay": "visible",
                "letterbox": "${currentLetterbox()}",
                "upgradeToHttps": true,
                "allowScriptAccess": true,
                "scale": "${currentScale()}",
                "forceScale": ${currentForceScale()},
                "forceAlign": ${currentForceAlign()},
                "quality": "${quality()}",
                // 允许 Ruffle 内部全屏：实际进入全屏走 WebView 的 onShowCustomView 协议
                // （Android 端实现：SwfPlayerScreen.chromeCallback.onShowFullscreen）。
                "allowFullscreen": true,
                "splashScreen": true,
                "preloader": true,
                "logLevel": "warn",
                "maxExecutionDuration": {"secs": 15, "nanos": 0}${fontConfigScript()}
              };
            })();
        """.trimIndent()
    }

    /**
     * 加载器脚本：动态注入引擎 JS。
     * 使用 <script> 标签加载引擎，shouldInterceptRequest 会拦截 flash.local 请求。
     * 加载完成后自动执行 polyfill，替换页面上的 <object>/<embed>。
     */
    fun loaderScript(): String = when (PrefsManager.flashEngine) {
        "swf2js" -> """
            (function(){
              if (window.__swf2jsLoaded) return;
              var s = document.createElement('script');
              s.src = "${scriptUrl()}";
              s.async = true;
              s.onload = function(){
                window.__swf2jsLoaded = true;
                setTimeout(function() {
                  var objects = document.querySelectorAll('object[type="application/x-shockwave-flash"], embed[type="application/x-shockwave-flash"], object[data$=".swf"], embed[src$=".swf"]');
                  objects.forEach(function(el) {
                    var src = el.getAttribute('data') || el.getAttribute('src') || el.getAttribute('movie');
                    if (src) {
                      console.log('[swf2js] load: ' + src);
                      try {
                        var container = document.createElement('div');
                        container.style.cssText = 'width:100%;height:100%;position:relative;';
                        el.parentNode.replaceChild(container, el);
                        swf2js.load(src, {tag: container});
                      } catch(e) { console.error('[swf2js]', e); }
                    }
                  });
                }, 300);
                window.__playSwf = function(url, base){
                  var container = document.createElement('div');
                  container.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;z-index:9999;background:#000;';
                  document.body.appendChild(container);
                  try { swf2js.load(url, {tag: container}); } catch(e) { console.error(e); }
                };
                document.dispatchEvent(new CustomEvent('flashEngineReady'));
              };
              s.onerror = function(e){ console.error('swf2js load failed: ' + "${scriptUrl()}" + ' ' + e); };
              document.head.appendChild(s);
            })();
        """.trimIndent()
        "waflash" -> ""  // WAFlash 使用独立播放器页面，不注入到 4399 页面
        else -> """
            (function(){
              if (window.__ruffleLoaded) return;
              function onReady(cb){
                if (window.RufflePlayer && window.RufflePlayer.newest) cb();
                else document.addEventListener('ruffleReady', function(){ cb(); });
              }
              var s = document.createElement('script');
              s.src = "${scriptUrl()}";
              s.async = true;
              s.onload = function(){
                window.__ruffleLoaded = true;
                try {
                  var r = window.RufflePlayer.newest();
                  if (r && r.init) r.init();
                } catch(e){ console.warn('Ruffle init:', e); }
                window.__playSwf = function(url, base){
                  onReady(function(){
                    var ruffle = window.RufflePlayer.newest();
                    var player = ruffle.createPlayer();
                    player.style.position = 'fixed';
                    player.style.left = '0'; player.style.top = '0';
                    player.style.width = '100%'; player.style.height = '100%';
                    player.style.zIndex = '9999';
                    player.style.background = '#000';
                    document.body.appendChild(player);
                    // ★ v4.1 per-load 补带缩放配置（与 player.html v26 同款）：
                    //   Ruffle load() 合并顺序 {...默认, ...全局config, ...perLoad}，
                    //   perLoad 优先级最高。旧版只传 {url, base}，若全局 config
                    //   被页面脚本/ruffle.js 自身重建时机覆盖，用户设置的比例
                    //   模式（exactFit/off）就全部丢失 → 拉伸失效。
                    var cfg = window.RufflePlayer.config || {};
                    var opt = { url: url };
                    if (cfg.scale) opt.scale = cfg.scale;
                    if (cfg.letterbox) opt.letterbox = cfg.letterbox;
                    if (typeof cfg.forceScale === 'boolean') opt.forceScale = cfg.forceScale;
                    if (typeof cfg.forceAlign === 'boolean') opt.forceAlign = cfg.forceAlign;
                    if (cfg.quality) opt.quality = cfg.quality;
                    if (base) opt.base = base;
                    player.ruffle().load(opt);
                  });
                };
                document.dispatchEvent(new CustomEvent('ruffleReady'));
                document.dispatchEvent(new CustomEvent('flashEngineReady'));
              };
              s.onerror = function(e){ console.error('Ruffle load failed: ' + "${scriptUrl()}" + ' ' + e); };
              document.head.appendChild(s);
            })();
        """.trimIndent()
    }

    /** 一键注入：配置 + 加载器 */
    fun fullInjection(): String = configScript() + "\n" + loaderScript()

    /**
     * 直接播放 SWF 的脚本。
     */
    fun playSwfScript(swfUrl: String, base: String? = null): String {
        val baseArg = base?.let { ", '$it'" } ?: ""
        return """
            (function(){
              if (window.__playSwf) { window.__playSwf('$swfUrl'$baseArg); }
              else { document.addEventListener('flashEngineReady', function(){
                window.__playSwf('$swfUrl'$baseArg);
              }); }
            })();
        """.trimIndent()
    }
}
