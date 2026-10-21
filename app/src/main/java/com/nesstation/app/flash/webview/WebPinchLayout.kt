package com.nesstation.app.flash.webview

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ★ v11: 双指缩放仲裁容器（1:1 移植自 AnWind ZoomPinchLayout v2.14.9/v2.19.3，
 *   按本项目结构调整）—— 弥补「页面禁止缩放」导致 WebView 原生捏合完全失效的
 *   引擎级限制。v10 试图用 meta 改写（PINCH_UNLOCK_SCRIPT）解锁，但 4399 等
 *   游戏页的画布 JS 会 preventDefault touchmove / touch-action:none —— meta
 *   解锁救不了被页面脚本吞掉的手势，双指依旧没反应（用户实测否决）。
 *
 * ## 方案（AnWind 已实测验证 + 本项目双向强化）
 * - 手势在【容器层】仲裁：双指落下先观察不拦截（不断流），此后每帧都能
 *   在 onInterceptTouchEvent 里看到完整手势流 —— 页面脚本无法阻止我们读到它；
 * - 若 WebView 原生捏合仍在响应（页面 scale 跟随指距变化）→ 放行给原生
 *   （排版重排/文字回流最平滑）；
 * - 若指距持续变化而页面 scale 纹丝不动（原生被 meta / preventDefault /
 *   touch-action 封死 —— 即 v10 的「双指没反应」）→ 【容器接管】该手势：
 *   WebView 收到 ACTION_CANCEL（页面感知不到本次手势），缩放改由
 *   【动态改写 viewport meta】实现 —— 桌面 Chrome Ctrl+加减号 同源语义，
 *   任何页面都有效（用户要求的“强制缩放”）。
 *
 * ## 缩放域（v11 在 AnWind 基础上扩展为双向 25%~300%）
 * - 【1.0 以下 缩小域】width = 原排版宽/z（画布放宽），同时
 *   minimum-scale = maximum-scale = 接管时页面 scale × z：整页所有内容等比
 *   变小、画布宽 × scale 恒等于窗口宽 —— letterbox 灰边物理上不可能出现；
 *   window.innerWidth 原生变宽 → H5 游戏 resize 监听自动重排；
 * - 【1.0 以上 放大域】width 保持原值（不 reflow），仅把
 *   minimum-scale = maximum-scale = s0×z 钳上去：同布局等比放大、可滚动 ——
 *   与桌面 Chrome Ctrl+加号 完全一致；
 * - 回 100% 按快照逐字还原原 meta（自建的整段移除），页面零残留。
 *
 * ## 与页面缩放百分比设置统一
 * - [setZoomImmediate] 把百分比（25..300 → 0.25..3.0）直接注入同一系统，
 *   【即时生效、无需重载】—— v10 的 setInitialScale+reload 方案在带 viewport
 *   meta 的页面上与 PINCH_UNLOCK/页面 meta 互相打架（“百分比设置没作用”
 *   的根因），v11 起全部走本容器单一通道。
 *
 * ## 注入节流
 * - evaluateJavascript 是跨 JNI 异步调用，逐帧注入（60+/s）会排队堆积。
 *   捏合过程按【间隔 ≥80ms 且增量 ≥0.02】节流；手势结束强制精确应用一次。
 *
 * ## 手势仲裁细则（onInterceptTouchEvent）
 * - 单指点击/滚动/长按全程不拦截，原样透传（游戏不受扰）；
 * - 100% 态：指距变化 ≥5%（任一方向）且页面 scale 连续 2 帧无变化 → 接管；
 *   原生仍在响应（scale 在动）→ 永久放行本次手势（原生域）；
 * - 已在接管域（viewZoom≠1）：指距任一方向实质变化（≥5%）即再次接管
 *   （张开回 100%/继续放大，收缩到 25%）；静止双指不拦截（双拇指游戏不受扰）；
 * - 指数集变化（3 指抬 1 指等）重基线，不丢失后续合法捏合。
 *
 * 注：本项目虚拟手柄/鼠标控件是本容器的【兄弟 View】（FrameLayout 内各管各的
 *   指针），不会进入本容器事件流 —— 无需 AnWind 的手柄指针剥离逻辑。
 */
class WebPinchLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 放大上限（300%，对应页面缩放设置最大档） */
    var maxViewZoom: Float = MAX_ZOOM

    /** 缩小地板（25%，对应页面缩放设置最小档） */
    var minViewZoom: Float = MIN_ZOOM

    /** 缩放变化回调（宿主持久化/展示用） */
    var onZoomChanged: ((Float) -> Unit)? = null

    private var webView: WebView? = null

    // ===== 手势状态机 =====
    private var mode = MODE_IDLE
    /** 本次手势是否已判定为原生域（原生捏合在响应，放行到底） */
    private var passThrough = false
    /** 观察域：true = 已处于注入缩放域（viewZoom≠1），指距实质变化即接管 */
    private var observeInjected = false
    /** 观察期基线指距 */
    private var baseSpan = 0f
    /** 最近一次采样的页面 scale（判断原生是否仍在响应） */
    private var lastScale = 0f
    /** 指距明显变化而页面 scale 连续无变化的采样数 */
    private var deadSamples = 0
    /** 接管瞬间的指距与缩放（增量计算基准） */
    private var seedSpan = 0f
    private var seedZoom = 1f
    /** 指数集变化（PINCH 中途抬指/加指）后待重置基准 */
    private var rebasePending = false

    /** 当前缩放比例（1.0 = 原生/未接管） */
    private var viewZoom = 1f

    // ===== JS 注入节流状态 =====
    private var lastJsApplyAt = 0L
    private var lastJsZoom = 1f

    /**
     * 绑定（或换绑）本容器承载的 WebView。重复调用安全：换绑时先把旧
     * WebView 从本容器剥离。restoreZoom 同步内部状态并立即按值注入
     * （页面缩放百分比设置/页面加载完成基线重放的入口）。
     */
    fun setWebView(target: WebView?, restoreZoom: Float = 1f) {
        viewZoom = restoreZoom.coerceIn(minViewZoom, maxViewZoom)
        if (webView === target) {
            if (target != null && abs(viewZoom - lastJsZoom) > 0.001f) {
                applyZoom(target, viewZoom, force = true)
            }
            return
        }
        webView?.let { old -> if (old.parent === this) removeView(old) }
        webView = target
        if (target != null) {
            (target.parent as? ViewGroup)?.removeView(target)
            addView(target, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            applyZoom(target, viewZoom, force = true)
        }
    }

    /** 组合销毁时解除引用，防泄漏；WebView 生命周期由宿主管理 */
    fun detachWebView() {
        webView?.let { if (it.parent === this) removeView(it) }
        webView = null
    }

    /** 当前缩放比例（宿主展示/同步用） */
    fun currentZoom(): Float = viewZoom

    /**
     * ★ v11: 页面缩放百分比设置的统一入口 —— percent 25..300 → 0.25..3.0。
     * 即时注入（force），无需 reload：手动百分比、捏合、导航基线重放共用
     * 同一系统，彻底替代 v10 的 setInitialScale+reload 双通道。
     */
    fun setZoomImmediate(zoom: Float, force: Boolean = true) {
        viewZoom = zoom.coerceIn(minViewZoom, maxViewZoom)
        val wv = webView ?: return
        if (!force && abs(viewZoom - lastJsZoom) < 0.001f) return
        applyZoom(wv, viewZoom, force = true)
        onZoomChanged?.invoke(viewZoom)
    }

    /**
     * 导航重置（宿主 onPageStarted 调用）：新文档天然无注入（window.__az
     * 是页面级 JS 态，随文档销毁）；此处仅同步内部状态 + 兜底清一次
     * __az（同文档锚点导航/历史回退时把旧注入彻底还原）。
     */
    fun resetForNavigation() {
        viewZoom = 1f
        val wv = webView ?: return
        try {
            wv.evaluateJavascript(RESET_SCRIPT, null)
        } catch (_: Exception) {
        }
    }

    /**
     * 窗口/容器尺寸变化（横竖屏切换）：注入中的 width=原宽/z 已与新窗口失配
     * （画布不再精确铺满、比例漂移）—— 直接重置回 100%，用户重新捏合即可。
     * oldw/oldh>0 守卫：首次布局（0→实际尺寸）不触发。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh) &&
            viewZoom != 1f && webView != null
        ) {
            viewZoom = 1f
            webView?.let { wv ->
                applyZoom(wv, 1f, force = true)
                onZoomChanged?.invoke(1f)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = MODE_IDLE
                passThrough = false
                deadSamples = 0
                baseSpan = 0f
                lastScale = 0f
                observeInjected = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount >= 2) {
                    if (passThrough) return false
                    // 不在第二指落下瞬间拦截 —— 缩放域里运行的双拇指游戏
                    // （两指静止/同步移动）不应被断流；观察指距实际变化后再接管
                    mode = MODE_OBSERVE
                    observeInjected = viewZoom != 1f
                    baseSpan = spanOf(ev)
                    lastScale = currentScale()
                    deadSamples = 0
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_OBSERVE && !passThrough) {
                    val span = spanOf(ev)
                    if (span <= 0f) {
                        mode = MODE_IDLE
                        return false
                    }
                    if (observeInjected) {
                        // 已在注入缩放域（meta 已被钳死 minimum=maximum）：
                        // 原生天然失效，指距任一方向实质变化即接管（双向）
                        if (span < baseSpan * 0.95f || span > baseSpan * 1.05f) {
                            return beginPinch(ev)
                        }
                        return false
                    }
                    val cur = currentScale()
                    // 页面 scale 是否仍在变化（原生捏合在响应中）
                    val scaleMoved =
                        lastScale > 0f && cur > 0f && abs(cur - lastScale) > lastScale * 0.002f
                    lastScale = cur
                    if (span > baseSpan * 1.05f || span < baseSpan * 0.95f) {
                        if (scaleMoved) {
                            // 原生捏合在响应（页面 meta 允许缩放）→ 放行到底，
                            // 本容器不再介入本次手势（原生重排最平滑）
                            deadSamples = 0
                            passThrough = true
                            mode = MODE_IDLE
                        } else {
                            deadSamples++
                            // 连续 2 帧指距明显变化而页面 scale 纹丝不动
                            // = 原生被 meta/preventDefault 封死 → 容器接管
                            //（v10“双指没反应”的根治点，也是用户要求的“强制缩放”）
                            if (deadSamples >= 2) return beginPinch(ev)
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val liftedIdx = ev.actionIndex
                val remaining = ev.pointerCount - 1
                if (remaining < 2) {
                    if (mode == MODE_PINCH) {
                        // 抬指后不足双指：手势实质结束，精确应用最终值
                        finishPinch()
                    }
                    mode = MODE_IDLE
                } else if (mode == MODE_OBSERVE) {
                    // 指数变化（3 指余 2）：重基线，不丢失后续合法捏合
                    baseSpan = spanOf(ev, liftedIdx)
                    lastScale = currentScale()
                    deadSamples = 0
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == MODE_PINCH) {
                    finishPinch()
                }
                mode = MODE_IDLE
                passThrough = false
                deadSamples = 0
                observeInjected = false
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // 本方法只处理被拦截接管的捏合缩放流（MODE_PINCH）；
        // 其余情况返回 false 不消费（WebView 收到完整原生事件流）。
        if (mode != MODE_PINCH) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val span = spanOf(ev)
                if (span > 0f) {
                    if (rebasePending) {
                        // 指数集变化后的首个 MOVE：重置增量基准，
                        // 防新旧指数集的 span 跳变引起缩放跳变
                        rebasePending = false
                        seedSpan = span
                        seedZoom = viewZoom
                    } else if (seedSpan > 0f) {
                        setZoom(seedZoom * (span / seedSpan))
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // PINCH 中途加指：下一帧重置基准
                rebasePending = true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.pointerCount - 1 < 2) {
                    // 抬指后不足双指：手势实质结束，精确应用最终值
                    finishPinch()
                    mode = MODE_IDLE
                } else {
                    // 三指抬一指后捏合继续：下一帧重置基准
                    rebasePending = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finishPinch()
                mode = MODE_IDLE
                rebasePending = false
            }
        }
        return true
    }

    /** 当前页面 scale（WebView.getScale 已废弃但功能正常且无替代） */
    @Suppress("DEPRECATION")
    private fun currentScale(): Float = try {
        webView?.scale ?: 0f
    } catch (_: Exception) {
        0f
    }

    /** 任意两指间最大距离（span）；不足 2 指返回 0 */
    private fun spanOf(ev: MotionEvent, excludeIdx: Int = -1): Float {
        val n = ev.pointerCount
        if (n < 2) return 0f
        var max = 0f
        for (i in 0 until n) {
            if (i == excludeIdx) continue
            for (j in i + 1 until n) {
                if (j == excludeIdx) continue
                val dx = ev.getX(i) - ev.getX(j)
                val dy = ev.getY(i) - ev.getY(j)
                val d = sqrt(dx * dx + dy * dy)
                if (d > max) max = d
            }
        }
        return if (max > 1f) max else 1f
    }

    /** 从观察/空闲态切入接管（此刻 WebView 收到 ACTION_CANCEL，页面感知不到） */
    private fun beginPinch(ev: MotionEvent): Boolean {
        mode = MODE_PINCH
        seedSpan = spanOf(ev)
        seedZoom = viewZoom
        rebasePending = false
        // 接管即应用一次（消除第一跳的延迟感）
        lastJsApplyAt = 0L
        return true
    }

    /** 手势结束：强制精确注入最终 zoom（过程值经过节流） */
    private fun finishPinch() {
        webView?.let { wv ->
            applyZoom(wv, viewZoom, force = true)
            onZoomChanged?.invoke(viewZoom)
        }
    }

    /** 捏合过程中设定目标缩放（节流改写 viewport meta 并回调） */
    private fun setZoom(zoom: Float) {
        viewZoom = zoom.coerceIn(minViewZoom, maxViewZoom)
        val now = android.os.SystemClock.uptimeMillis()
        val settled = abs(viewZoom - lastJsZoom) >= 0.02f
        if (now - lastJsApplyAt >= JS_INTERVAL_MS && settled) {
            webView?.let { wv ->
                applyZoom(wv, viewZoom, force = false)
                onZoomChanged?.invoke(viewZoom)
            }
        }
    }

    /**
     * 把缩放注入到页面。force=true 精确注入当前值（手势结束/百分比设置/
     * 基线重放）；否则受 [JS_INTERVAL_MS] 节流。
     */
    private fun applyZoom(wv: WebView, zoom: Float, force: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force) {
            if (now - lastJsApplyAt < JS_INTERVAL_MS) return
            if (abs(zoom - lastJsZoom) < 0.02f) return
        }
        lastJsApplyAt = now
        lastJsZoom = zoom
        try {
            wv.evaluateJavascript(zoomScript(zoom), null)
        } catch (_: Exception) {
        }
    }

    companion object {
        /** 缩小地板（25% —— 页面缩放设置最小档） */
        const val MIN_ZOOM = 0.25f

        /** 放大上限（300% —— 高于页面缩放设置最大档 200%，捏合余量） */
        const val MAX_ZOOM = 3.0f

        /** viewport meta 注入最小间隔（ms）：防 JNI 排队堆积 */
        private const val JS_INTERVAL_MS = 80L

        /** 手势状态机 */
        private const val MODE_IDLE = 0        // 无双指手势
        private const val MODE_OBSERVE = 1     // 双指观察中（未拦截，原生优先）
        private const val MODE_PINCH = 2       // 已拦截，缩放接管中

        /**
         * 缩放注入脚本（快照缓存于 window.__az，幂等可重入）：
         * - 首次接管快照：原 viewport meta（可能不存在）、原 content 字符串、
         *   原排版宽 cw、接管瞬间页面 scale s0（此后 clientWidth 是注入后的
         *   宽画布值，不可再作基准）；
         * - z<1（缩小域，AnWind v2.14.9 同款）：width = cw/z（排版视口按 1/z
         *   放宽 → 整页等比变小），minimum-scale = maximum-scale = s0*z
         *   （画布宽 × scale = 窗口宽，任何比例下精确铺满窗口宽，letterbox
         *   物理上不可能出现）；innerWidth 原生变宽 → H5 游戏的 resize
         *   监听拿到真实新尺寸自动重排画布；
         * - z>1（放大域，v11 扩展）：width 保持 cw 原值（不触发 reflow ——
         *   桌面 Chrome Ctrl+加号 同款语义），仅把 scale 钳到 s0*z：同布局
         *   等比放大、超出窗口可滚动；
         * - z≈1：走 [RESET_SCRIPT]（按快照逐字还原原 meta，自建的整段移除）。
         */
        fun zoomScript(zoom: Float): String {
            return if (zoom >= 0.999f) RESET_SCRIPT
            else "(function(){try{" +
                "var d=document,z=$zoom;" +
                "if(!d.documentElement)return;" +
                "var S=window.__az;" +
                "if(!S){" +
                // 首次接管：此刻 meta 尚未被改写，layout/scale 均为页面
                // 原生值 —— 快照必须在此刻完成
                "var m=d.querySelector('meta[name=viewport]');" +
                "var cw=(d.documentElement&&d.documentElement.clientWidth)||window.innerWidth;" +
                "var s0=(window.visualViewport&&window.visualViewport.scale)||1;" +
                "if(!(s0>0)||!isFinite(s0))s0=1;" +
                "if(!m){" +
                // 页面无 viewport meta：自建一个，还原时整段移除，零残留
                "m=d.createElement('meta');m.setAttribute('name','viewport');" +
                "(d.head||d.documentElement).appendChild(m);" +
                "S={meta:m,content:null,cw:cw,s0:s0,created:true};" +
                "}else{" +
                "S={meta:m,content:m.getAttribute('content'),cw:cw,s0:s0,created:false};" +
                "}" +
                "window.__az=S;" +
                "}" +
                "if(!(S.cw>0))return;" +
                // 缩小域：画布宽 = cw/z（等比变小且铺满）；
                // 放大域：画布宽保持 cw（不 reflow，仅放大）。
                // 两种域下 scale 都钳到 s0*z（Chromium 按新约束即时生效）
                "var w=(z<1)?Math.round(S.cw/z):Math.round(S.cw);" +
                "var sc=S.s0*z;" +
                "S.meta.setAttribute('content'," +
                "'width='+w+',minimum-scale='+sc+',maximum-scale='+sc+',user-scalable=yes');" +
                "}catch(e){}})()"
        }

        /**
         * 重置缩放脚本：按快照还原 viewport meta（自建的整段移除、原存在的
         * 逐字还原 content），并清 __az。还原后画布宽回到原生值，Chromium
         * 按"窗口宽/画布宽"地板把 scale 钳回初始满宽态。
         */
        const val RESET_SCRIPT: String =
            "(function(){try{" +
                "var d=document,S=window.__az;" +
                "if(S&&S.meta){" +
                "if(S.created){if(S.meta.parentNode)S.meta.parentNode.removeChild(S.meta);}" +
                "else if(S.content==null)S.meta.removeAttribute('content');" +
                "else S.meta.setAttribute('content',S.content);" +
                "}" +
                "window.__az=null;" +
                "}catch(e){}})()"
    }
}
