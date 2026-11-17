package com.nesstation.app.ui.swf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 自由布局编辑器:让用户拖动游戏画面 4 个角 + 整个矩形来调整位置/大小。
 *
 * 实现参考 eden-an(ScreenPositionEditor.java/Kt,GPL-3.0),按 Yuzu Eden 的成熟方案:
 * - View 自身 onDraw + onTouchEvent,不依赖父容器拦截触摸
 * - 5 个 hot zone:4 个角 (HANDLE_TL/TR/BL/BR,容差 0.06) + body (中间区域,整体拖动)
 * - 归一化 0..1 坐标,父容器 resize 不会破坏位置
 * - 圆角手柄用 HANDLE_RADIUS_PX (80px) 容差,手指容易点中
 *
 * 用法:
 * 1. 在 FrameLayoutSwfContainer 里 addView(ScreenPositionEditor) 覆盖整个容器
 * 2. 初始 visibility = GONE,WebView 正常接收触摸
 * 3. 进入编辑模式:visibility = VISIBLE + setRect(x1,y1,x2,y2) + bringToFront()
 * 4. listener 回调实时收到 onRectChanged(confirm=true 在 up 时)
 */
class ScreenPositionEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        /**
         * @param confirm true 在 touch-up 时(可以持久化),false 在拖动中
         */
        fun onRectChanged(x1: Float, y1: Float, x2: Float, y2: Float, confirm: Boolean)
    }

    var listener: Listener? = null

    init {
        // 必须设 clickable=true,View 才会接收触摸事件
        isClickable = true
        isFocusable = false
    }

    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 选中矩形内部半透明粉红
        color = Color.parseColor("#33E91E63")
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 角手柄粉红实心
        color = Color.parseColor("#E91E63")
        style = Paint.Style.FILL
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E91E63")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var x1 = 0f
    private var y1 = 0f
    private var x2 = 1f
    private var y2 = 1f
    private var activeHandle = HANDLE_NONE

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val l = x1 * w
        val t = y1 * h
        val r = x2 * w
        val b = y2 * h

        // 矩形内部 + 边框
        canvas.drawRect(RectF(l, t, r, b), rectPaint)
        canvas.drawRect(RectF(l, t, r, b), borderPaint)

        // 4 个角圆手柄
        val radius = HANDLE_RADIUS_PX.toFloat()
        canvas.drawCircle(l, t, radius, handlePaint)
        canvas.drawCircle(r, t, radius, handlePaint)
        canvas.drawCircle(l, b, radius, handlePaint)
        canvas.drawCircle(r, b, radius, handlePaint)
        canvas.drawCircle(l, t, radius, handleStroke)
        canvas.drawCircle(r, t, radius, handleStroke)
        canvas.drawCircle(l, b, radius, handleStroke)
        canvas.drawCircle(r, b, radius, handleStroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return false

        val nx = event.x / w
        val ny = event.y / h

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = pickHandle(nx, ny)
                if (activeHandle == HANDLE_NONE) {
                    return false
                }
                applyDrag(nx, ny)
                listener?.onRectChanged(x1, y1, x2, y2, false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle == HANDLE_NONE) return false
                applyDrag(nx, ny)
                listener?.onRectChanged(x1, y1, x2, y2, false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = activeHandle != HANDLE_NONE
                activeHandle = HANDLE_NONE
                if (wasDragging) {
                    listener?.onRectChanged(x1, y1, x2, y2, true)
                }
                return wasDragging
            }
            else -> return false
        }
    }

    fun setRect(nx1: Float, ny1: Float, nx2: Float, ny2: Float) {
        x1 = clamp01(nx1)
        y1 = clamp01(ny1)
        x2 = clamp01(nx2)
        y2 = clamp01(ny2)
        if (x2 - x1 < MIN_SIZE) x2 = clamp01(x1 + MIN_SIZE)
        if (y2 - y1 < MIN_SIZE) y2 = clamp01(y1 + MIN_SIZE)
        invalidate()
    }

    fun getRect(): FloatArray = floatArrayOf(x1, y1, x2, y2)

    private fun pickHandle(nx: Float, ny: Float): Int {
        val dxL = kotlin.math.abs(nx - x1)
        val dyT = kotlin.math.abs(ny - y1)
        val dxR = kotlin.math.abs(nx - x2)
        val dyB = kotlin.math.abs(ny - y2)

        val tol = 0.06f
        if (dxL < tol && dyT < tol) return HANDLE_TL
        if (dxR < tol && dyT < tol) return HANDLE_TR
        if (dxL < tol && dyB < tol) return HANDLE_BL
        if (dxR < tol && dyB < tol) return HANDLE_BR

        // body: 在矩形内部
        if (nx > x1 && nx < x2 && ny > y1 && ny < y2) {
            return HANDLE_BODY
        }
        return HANDLE_NONE
    }

    private fun applyDrag(nx: Float, ny: Float) {
        val cnx = clamp01(nx)
        val cny = clamp01(ny)
        when (activeHandle) {
            HANDLE_TL -> {
                x1 = kotlin.math.min(cnx, x2 - MIN_SIZE)
                y1 = kotlin.math.min(cny, y2 - MIN_SIZE)
            }
            HANDLE_TR -> {
                x2 = kotlin.math.max(cnx, x1 + MIN_SIZE)
                y1 = kotlin.math.min(cny, y2 - MIN_SIZE)
            }
            HANDLE_BL -> {
                x1 = kotlin.math.min(cnx, x2 - MIN_SIZE)
                y2 = kotlin.math.max(cny, y1 + MIN_SIZE)
            }
            HANDLE_BR -> {
                x2 = kotlin.math.max(cnx, x1 + MIN_SIZE)
                y2 = kotlin.math.max(cny, y1 + MIN_SIZE)
            }
            HANDLE_BODY -> {
                val rw = x2 - x1
                val rh = y2 - y1
                var nx1 = cnx - rw / 2f
                var ny1 = cny - rh / 2f
                if (nx1 < 0f) nx1 = 0f
                if (ny1 < 0f) ny1 = 0f
                if (nx1 + rw > 1f) nx1 = 1f - rw
                if (ny1 + rh > 1f) ny1 = 1f - rh
                x1 = nx1
                y1 = ny1
                x2 = x1 + rw
                y2 = y1 + rh
            }
        }
    }

    private fun clamp01(v: Float): Float = when {
        v < 0f -> 0f
        v > 1f -> 1f
        else -> v
    }

    companion object {
        private const val MIN_SIZE = 0.05f
        private const val HANDLE_NONE = -1
        private const val HANDLE_BODY = 0
        private const val HANDLE_TL = 1
        private const val HANDLE_TR = 2
        private const val HANDLE_BL = 3
        private const val HANDLE_BR = 4
        private const val HANDLE_RADIUS_PX = 80
    }
}
