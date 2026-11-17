package com.nesstation.app.ui.swf

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Collections
import kotlin.math.abs

/**
 * 增强版 WebView：用于在线 Flash 游戏（WebGameScreen）。
 *
 * 核心功能（参考 3.3-fix2 GameWebView）：
 * 1. 预置适配 Flash / H5 游戏的 WebSettings（DOM 存储 / 自动播放 / 跨域 / 硬件加速 / 缩放）
 * 2. 触屏手势：长按 → 屏蔽系统选择菜单；滑动 → 方向键
 * 3. 物理键盘：dispatchKeyEvent 透传游戏键给网页
 * 4. **自动注入 `__gameKeys` JS 状态管理器**（Document Start 注入，每个页面都有）
 *    → 虚拟手柄通过 `injectKey()` 把按键分发到 window/document/activeElement
 *    → Ruffle（监听 window keydown）能可靠收到事件
 * 5. View Transitions API polyfill：解决 SPA 页面切换卡住
 * 6. navigator.plugins / mimeTypes 兜底：避免 Ruffle 引擎崩溃
 * 7. 心跳同步：定时把 Android 端按键状态同步到 JS，防止 Ruffle 角色卡住
 * 8. 释放按键：blur / visibilitychange / 页面切换 / 生命周期暂停时全部释放
 */
@SuppressLint("ViewConstructor")
open class GameWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    /** 是否拦截并屏蔽长按系统菜单（默认 true） */
    var blockLongPressMenu: Boolean = true

    /**
     * 3D 视角旋转模式：开启后页面会注入触摸→鼠标事件脚本，
     * 滑动手势不再注入方向键，避免拖动旋转视角时误触移动。
     */
    var cameraRotationEnabled: Boolean = false

    private var cameraLastX = 0f
    private var cameraLastY = 0f
    private var cameraDragging = false

    /**
     * 当前通过虚拟手柄按下的按键集合（Android KeyCode）。
     * 用于在页面导航、Activity 暂停、手柄隐藏时统一释放。
     */
    private val pressedKeys = Collections.synchronizedSet(HashSet<Int>())

    private val gestureDetector = GestureDetector(context, GestureListener())

    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            syncPressedKeys()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL)
        }
    }

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

        // 注入 Document Start 脚本（在页面任何 JS 之前执行）
        // 必须在 WebView 创建后、首次加载前调用
        injectDocumentStartScripts()
    }

    @Suppress("DEPRECATION")
    private fun configureSettings() {
        val wv = this
        settings.apply {
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
        displayZoomControls = false
        setSupportMultipleWindows(false)
        // 硬件加速渲染
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // Safe Browsing
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(this, true)
        }
        // Cookie
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        }
    }

    /**
     * 注入 Document Start 脚本：在页面任何 JS 之前执行（AndroidX WebKit 1.6.0+）。
     * 必须在 WebView 创建后、首次加载前调用。
     */
    fun injectDocumentStartScripts() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(this, DOCUMENT_START_SCRIPT, setOf("*"))
        }
    }

    // ---------------- 触屏 ----------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_DOWN) performClick()

        if (cameraRotationEnabled) {
            handleCameraRotationTouch(event)
        }

        return super.onTouchEvent(event)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    private fun handleCameraRotationTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cameraLastX = event.x
                cameraLastY = event.y
                cameraDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - cameraLastX
                val dy = event.y - cameraLastY
                if (abs(dx) > 2f || abs(dy) > 2f) {
                    cameraDragging = true
                    evaluateJavascript(
                        "if(window.__cameraRotate){window.__cameraRotate($dx, $dy);}", null
                    )
                    cameraLastX = event.x
                    cameraLastY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cameraDragging = false
            }
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) { /* 屏蔽系统长按菜单 */ }
        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
        ): Boolean {
            if (cameraRotationEnabled) return false
            if (abs(vx) > abs(vy)) {
                injectKey(if (vx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            }
            return true
        }
    }

    // ---------------- 键盘 ----------------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }
        if (event.keyCode in GAME_KEYS) {
            return super.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 注入一次按键 down+up（供虚拟手柄调用）。
     * 通过 JS 按键状态管理器分发到 window / document / activeElement，
     * 确保 Ruffle 必定收到事件。
     */
    fun injectKey(keyCode: Int, flags: Int = 0) {
        injectKeyDown(keyCode)
        injectKeyUp(keyCode)
    }

    fun injectKeyDown(keyCode: Int) {
        pressedKeys.add(keyCode)
        val info = androidKeyToJsInfo(keyCode)
        val js = """
            $KEY_MANAGER_INIT
            window.__gameKeys.down(${info.keyCode},"${info.key}","${info.code}");
        """.trimIndent()
        evaluateJavascript(js, null)
    }

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
     * 在页面导航、Activity 暂停、手柄隐藏时调用。
     */
    fun releaseAllKeys() {
        val keys = synchronized(pressedKeys) {
            val copy = pressedKeys.toSet()
            pressedKeys.clear()
            copy
        }
        Log.d(TAG, "releaseAllKeys: ${keys.size} keys")
        val js = """
            $KEY_MANAGER_INIT
            window.__gameKeys.releaseAll();
        """.trimIndent()
        evaluateJavascript(js, null)
    }

    // ---------------- 按键心跳同步 ----------------
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
        // WASD
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

    companion object {
        private const val TAG = "GameWebView"
        private const val HEARTBEAT_INTERVAL = 300L

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
            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V,
            KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L,
            KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O,
            KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_B,
            KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_M,
            KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y,
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9
        )

        /**
         * Document Start 脚本：在页面任何 JS 之前执行。
         * 注入：
         * 1. View Transitions API polyfill（避免 SPA 卡住）
         * 2. `__gameKeys` JS 状态管理器（虚拟手柄走这个）
         * 3. navigator.plugins / mimeTypes 兜底（避免 Ruffle 崩溃）
         */
        private const val DOCUMENT_START_SCRIPT = """
            (function(){
              // === 1. View Transitions API polyfill ===
              if (!window.__vtPatched) {
                window.__vtPatched = true;
                var vtPolyfill = function(callback) {
                  var result;
                  try { result = callback ? callback() : undefined; }
                  catch(e) { result = Promise.reject(e); }
                  var p = (result && typeof result.then === 'function') ? result : Promise.resolve();
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
                try {
                  Object.defineProperty(Document.prototype, 'startViewTransition', {
                    value: vtPolyfill, writable: true, configurable: true
                  });
                } catch(e) {}
                try { document.startViewTransition = vtPolyfill; } catch(e) {}
              }
              // === 2. navigator.plugins / mimeTypes 兜底 ===
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
              // === 3. __gameKeys JS 按键状态管理器 ===
              if(!window.__gameKeys){
                window.__gameKeys = (function(){
                  var pressed = {};
                  var recentlyReleased = {};
                  function mkEv(type, kc, kv, cv) {
                    var e = new KeyboardEvent(type, {key:kv, code:cv, bubbles:true, cancelable:true, composed:true});
                    Object.defineProperty(e, 'keyCode', {get:function(){return kc;}});
                    Object.defineProperty(e, 'which', {get:function(){return kc;}});
                    Object.defineProperty(e, 'charCode', {get:function(){return 0;}});
                    return e;
                  }
                  function dispatch(type, kc, kv, cv) {
                    var ae = document.activeElement || document.body || document.documentElement;
                    try { ae.dispatchEvent(mkEv(type, kc, kv, cv)); } catch(e) {}
                    try { if (ae !== document) document.dispatchEvent(mkEv(type, kc, kv, cv)); } catch(e) {}
                    try { window.dispatchEvent(mkEv(type, kc, kv, cv)); } catch(e) {}
                  }
                  function down(kc, kv, cv) {
                    pressed[kc] = {key:kv, code:cv};
                    delete recentlyReleased[kc];
                    dispatch('keydown', kc, kv, cv);
                  }
                  function up(kc, kv, cv) {
                    var info = pressed[kc];
                    var akv = kv || (info ? info.key : '');
                    var acv = cv || (info ? info.code : 'Unidentified');
                    delete pressed[kc];
                    recentlyReleased[kc] = {key:akv, code:acv, time:Date.now()};
                    dispatch('keyup', kc, akv, acv);
                    setTimeout(function(){ if(!pressed[kc]) dispatch('keyup', kc, akv, acv); }, 50);
                    setTimeout(function(){ if(!pressed[kc]) dispatch('keyup', kc, akv, acv); }, 150);
                  }
                  function releaseAll() {
                    for (var kc in pressed) {
                      var info = pressed[kc];
                      dispatch('keyup', parseInt(kc), info.key, info.code);
                      recentlyReleased[kc] = {key:info.key, code:info.code, time:Date.now()};
                    }
                    pressed = {};
                  }
                  function sync(androidKeys) {
                    var aSet = {};
                    if (androidKeys) { for (var i = 0; i < androidKeys.length; i++) aSet[androidKeys[i]] = true; }
                    var now = Date.now();
                    for (var kc in pressed) {
                      if (!aSet[kc]) {
                        var info = pressed[kc];
                        dispatch('keyup', parseInt(kc), info.key, info.code);
                        recentlyReleased[kc] = {key:info.key, code:info.code, time:now};
                        delete pressed[kc];
                      }
                    }
                    for (var kc in recentlyReleased) {
                      if (!aSet[kc] && !pressed[kc]) {
                        var info = recentlyReleased[kc];
                        if (now - info.time < 500) dispatch('keyup', parseInt(kc), info.key, info.code);
                        else delete recentlyReleased[kc];
                      } else { delete recentlyReleased[kc]; }
                    }
                  }
                  window.addEventListener('blur', function(){ releaseAll(); });
                  document.addEventListener('visibilitychange', function(){
                    if (document.hidden) releaseAll();
                  });
                  return {down:down, up:up, releaseAll:releaseAll, sync:sync};
                })();
              }
            })();
        """

        /** KeyManager 初始化片段（用于 injectKey 的 JS 字符串拼接） */
        private val KEY_MANAGER_INIT = """
            if(!window.__gameKeys){
              window.__gameKeys = (function(){
                var pressed = {};
                function mkEv(type, kc, kv, cv) {
                  var e = new KeyboardEvent(type, {key:kv, code:cv, bubbles:true, cancelable:true, composed:true});
                  Object.defineProperty(e, 'keyCode', {get:function(){return kc;}});
                  Object.defineProperty(e, 'which', {get:function(){return kc;}});
                  return e;
                }
                function dispatch(type, kc, kv, cv) {
                  var ae = document.activeElement || document.body || document.documentElement;
                  try { ae.dispatchEvent(mkEv(type, kc, kv, cv)); } catch(e) {}
                  try { if (ae !== document) document.dispatchEvent(mkEv(type, kc, kv, cv)); } catch(e) {}
                  try { window.dispatchEvent(mkEv(type, kc, kv, cv)); } catch(e) {}
                }
                function down(kc, kv, cv) { pressed[kc] = {key:kv, code:cv}; dispatch('keydown', kc, kv, cv); }
                function up(kc, kv, cv) {
                  var info = pressed[kc];
                  if (info) { dispatch('keyup', kc, info.key, info.code); delete pressed[kc]; }
                }
                function releaseAll() { for (var kc in pressed) { var info = pressed[kc]; dispatch('keyup', parseInt(kc), info.key, info.code); } pressed = {}; }
                function sync(androidKeys) {
                  var aSet = {};
                  if (androidKeys) for (var i = 0; i < androidKeys.length; i++) aSet[androidKeys[i]] = true;
                  for (var kc in pressed) {
                    if (!aSet[kc]) { var info = pressed[kc]; dispatch('keyup', parseInt(kc), info.key, info.code); delete pressed[kc]; }
                  }
                }
                return {down:down, up:up, releaseAll:releaseAll, sync:sync};
              })();
            }
        """.trimIndent()
    }
}
