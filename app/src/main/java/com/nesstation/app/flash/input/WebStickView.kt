package com.nesstation.app.flash.input

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.nesstation.app.flash.data.PrefsManager
import com.nesstation.app.flash.webview.GameWebView

/**
 * ★★ v49 摇杆 WASD（stick_wasd 模式共享控件）：
 * 仿原生摇杆手感的可改键虚拟摇杆 —— 由 WebGameScreen（在线游戏）抽出为
 * 公共控件，SwfPlayerScreen（SWF 播放器）同步接入。
 *
 * 特性：
 *  - 摇杆头跟手拖动（行程 = 外环 62%），松手回中；
 *  - 8 方向判定：阈值 0.30 归一化（★ v49 由 0.34 调低 —— 斜向双键更容易
 *    触发，格斗/动作游戏的斜向跑更顺手），斜向 = 双键并按；
 *  - 四向键位可改键（web_stick_key_up/down/left/right，默认 W/S/A/D）；
 *  - 外环四向标注当前映射键名（所见即所得）；
 *  - 拖动编辑模式（isDragMode）与 DPadView 同语义；
 *  - 位置按比例持久化（web_stick_pos_x/y），跨设备分辨率无关。
 */
class WebStickView(context: Context) : View(context) {

    /** 目标 WebView（切换标签时宿主同步）。 */
    var targetWebView: GameWebView? = null

    /** 与其他手柄控件一致的透明度（0f..1f）。 */
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isDragMode) {
                    dragAnchorX = event.rawX
                    dragAnchorY = event.rawY
                    return true
                }
                stickHeld = true
                updateKnob(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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

    /** 归一化方向判定（阈值 0.30；斜向区间自然产生双激活）。
     *  ★ v49：阈值 0.34 → 0.30（斜向更容易触发，搓招/斜跑更顺手）。 */
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
        val th = 0.30f
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
