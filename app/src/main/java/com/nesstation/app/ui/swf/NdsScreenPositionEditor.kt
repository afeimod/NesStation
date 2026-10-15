package com.nesstation.app.ui.swf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * NDS 双屏自由布局编辑器：上屏 / 下屏各一个独立矩形，可分别拖动 4 角调整
 * 大小、拖动矩形内部移动位置。交互逻辑复用 [ScreenPositionEditor] 的成熟
 * 方案（5 个 hot zone：4 角 + body），区别是这里同时管理两个矩形：
 * - 触摸先检测前置矩形（默认上屏）的角/主体，再检测后置 —— ★ 切换
 *   前置（[setFrontRect]）后两矩形完全重叠时也能拖到被遮住的那个
 *   （“被挡住无法拖动”修复，NDS/3DS/Wii U 双屏通用）
 * - 上屏用蓝色、下屏用粉红色绘制，便于区分（标签可自定义，
 *   Wii U 用「主屏 / GamePad」）
 * - 参照 melonDS 官方 Android 布局模型：TOP_SCREEN / BOTTOM_SCREEN 各自
 *   一个独立矩形，互不影响
 *
 * 归一化 0..1 坐标，横竖屏分别由上层（EmulatorScreen）注入对应矩形。
 */
class NdsScreenPositionEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        /**
         * @param top    上屏归一化矩形 [x1, y1, x2, y2]
         * @param bottom 下屏归一化矩形 [x1, y1, x2, y2]
         * @param confirm true 在 touch-up 时(可以持久化)，false 在拖动中
         */
        fun onRectChanged(top: FloatArray, bottom: FloatArray, confirm: Boolean)
    }

    var listener: Listener? = null

    // ★ 屏幕标签（Wii U 双屏编辑器用「主屏 / GamePad」）
    private var topLabelText = "上屏"
    private var bottomLabelText = "下屏"

    // ★ 前置矩形（默认 true = 上屏在前）：触摸命中检测前置矩形优先、
    //   绘制前置矩形在后（覆盖在上层）。完全重叠时切换前置即可拖到
    //   被遮住的那个 —— 外部「切换屏幕」按钮消费。
    private var frontIsTop = true

    init {
        isClickable = true
        isFocusable = false
    }

    // 上屏：蓝系
    private val topFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300A3FF")
        style = Paint.Style.FILL
    }
    private val topHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00A3FF")
        style = Paint.Style.FILL
    }
    private val topBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00A3FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    // 下屏：粉红系（触摸屏）
    private val bottomFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33E91E63")
        style = Paint.Style.FILL
    }
    private val bottomHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E91E63")
        style = Paint.Style.FILL
    }
    private val bottomBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E91E63")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val topRect = floatArrayOf(0f, 0f, 1f, 0.48f)
    private val bottomRect = floatArrayOf(0f, 0.52f, 1f, 1f)
    private var activeHandle = HANDLE_NONE

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        // 后置的先画（在下层），前置的后画（覆盖在上）—— 与命中检测顺序一致
        if (frontIsTop) {
            drawRect(canvas, bottomRect, w, h, bottomFillPaint, bottomBorderPaint, bottomHandlePaint, bottomLabelText)
            drawRect(canvas, topRect, w, h, topFillPaint, topBorderPaint, topHandlePaint, topLabelText)
        } else {
            drawRect(canvas, topRect, w, h, topFillPaint, topBorderPaint, topHandlePaint, topLabelText)
            drawRect(canvas, bottomRect, w, h, bottomFillPaint, bottomBorderPaint, bottomHandlePaint, bottomLabelText)
        }
    }

    private fun drawRect(
        canvas: Canvas,
        rect: FloatArray,
        w: Int,
        h: Int,
        fillPaint: Paint,
        borderPaint: Paint,
        handlePaint: Paint,
        label: String
    ) {
        val l = rect[0] * w
        val t = rect[1] * h
        val r = rect[2] * w
        val b = rect[3] * h
        val rf = RectF(l, t, r, b)
        canvas.drawRect(rf, fillPaint)
        canvas.drawRect(rf, borderPaint)

        val radius = HANDLE_RADIUS_PX.toFloat()
        canvas.drawCircle(l, t, radius, handlePaint)
        canvas.drawCircle(r, t, radius, handlePaint)
        canvas.drawCircle(l, b, radius, handlePaint)
        canvas.drawCircle(r, b, radius, handlePaint)
        canvas.drawCircle(l, t, radius, handleStroke)
        canvas.drawCircle(r, t, radius, handleStroke)
        canvas.drawCircle(l, b, radius, handleStroke)
        canvas.drawCircle(r, b, radius, handleStroke)

        // 屏幕标签（居中于矩形中央，避免遮挡角手柄）
        canvas.drawText(label, l + (r - l) / 2f - labelPaint.measureText(label) / 2f, t + (b - t) / 2f, labelPaint)
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
                listener?.onRectChanged(topRect.copyOf(), bottomRect.copyOf(), false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle == HANDLE_NONE) return false
                applyDrag(nx, ny)
                listener?.onRectChanged(topRect.copyOf(), bottomRect.copyOf(), false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = activeHandle != HANDLE_NONE
                activeHandle = HANDLE_NONE
                if (wasDragging) {
                    listener?.onRectChanged(topRect.copyOf(), bottomRect.copyOf(), true)
                }
                return wasDragging
            }
            else -> return false
        }
    }

    fun setRects(top: FloatArray, bottom: FloatArray) {
        setRect(topRect, top)
        setRect(bottomRect, bottom)
        invalidate()
    }

    /** ★ 自定义屏幕标签（Wii U：主屏 / GamePad）。 */
    fun setLabels(top: String, bottom: String) {
        topLabelText = top
        bottomLabelText = bottom
        invalidate()
    }

    /** ★ 切换前置矩形（true = 上屏/主屏在前）：完全重叠时把被遮住的屏幕
     *   换到前面即可拖动。 */
    fun setFrontRect(frontTop: Boolean) {
        frontIsTop = frontTop
        invalidate()
    }

    /** 当前前置是否为上屏（主屏）。 */
    fun isFrontTop(): Boolean = frontIsTop

    fun setTopRect(nx1: Float, ny1: Float, nx2: Float, ny2: Float) {
        setRect(topRect, floatArrayOf(nx1, ny1, nx2, ny2))
        invalidate()
    }

    fun setBottomRect(nx1: Float, ny1: Float, nx2: Float, ny2: Float) {
        setRect(bottomRect, floatArrayOf(nx1, ny1, nx2, ny2))
        invalidate()
    }

    fun getTopRect(): FloatArray = topRect.copyOf()
    fun getBottomRect(): FloatArray = bottomRect.copyOf()

    private fun setRect(dst: FloatArray, src: FloatArray) {
        var nx1 = clamp01(src[0])
        var ny1 = clamp01(src[1])
        var nx2 = clamp01(src[2])
        var ny2 = clamp01(src[3])
        if (nx2 - nx1 < MIN_SIZE) nx2 = clamp01(nx1 + MIN_SIZE)
        if (ny2 - ny1 < MIN_SIZE) ny2 = clamp01(ny1 + MIN_SIZE)
        dst[0] = nx1
        dst[1] = ny1
        dst[2] = nx2
        dst[3] = ny2
    }

    private fun pickHandle(nx: Float, ny: Float): Int {
        // ★ 前置矩形优先（完全重叠时切前置即可拖到被遮住的那个），再检测后置
        val front = if (frontIsTop) topRect else bottomRect
        val back = if (frontIsTop) bottomRect else topRect
        val frontTag = if (frontIsTop) RECT_TOP else RECT_BOTTOM
        val backTag = if (frontIsTop) RECT_BOTTOM else RECT_TOP
        pickInRect(front, nx, ny, frontTag)?.let { return it }
        pickInRect(back, nx, ny, backTag)?.let { return it }
        return HANDLE_NONE
    }

    private fun pickInRect(rect: FloatArray, nx: Float, ny: Float, rectTag: Int): Int? {
        val dxL = kotlin.math.abs(nx - rect[0])
        val dyT = kotlin.math.abs(ny - rect[1])
        val dxR = kotlin.math.abs(nx - rect[2])
        val dyB = kotlin.math.abs(ny - rect[3])

        val tol = 0.06f
        if (dxL < tol && dyT < tol) return handleCode(rectTag, HANDLE_TL)
        if (dxR < tol && dyT < tol) return handleCode(rectTag, HANDLE_TR)
        if (dxL < tol && dyB < tol) return handleCode(rectTag, HANDLE_BL)
        if (dxR < tol && dyB < tol) return handleCode(rectTag, HANDLE_BR)

        if (nx > rect[0] && nx < rect[2] && ny > rect[1] && ny < rect[3]) {
            return handleCode(rectTag, HANDLE_BODY)
        }
        return null
    }

    private fun handleCode(rectTag: Int, handle: Int): Int = (rectTag shl 8) or handle

    private fun rectTagOf(handle: Int): Int = handle ushr 8
    private fun handleOf(handle: Int): Int = handle and 0xFF

    private fun applyDrag(nx: Float, ny: Float) {
        val rect = if (rectTagOf(activeHandle) == RECT_TOP) topRect else bottomRect
        val hand = handleOf(activeHandle)
        val c = clamp01(nx)
        val d = clamp01(ny)
        when (hand) {
            HANDLE_TL -> {
                rect[0] = kotlin.math.min(c, rect[2] - MIN_SIZE)
                rect[1] = kotlin.math.min(d, rect[3] - MIN_SIZE)
            }
            HANDLE_TR -> {
                rect[2] = kotlin.math.max(c, rect[0] + MIN_SIZE)
                rect[1] = kotlin.math.min(d, rect[3] - MIN_SIZE)
            }
            HANDLE_BL -> {
                rect[0] = kotlin.math.min(c, rect[2] - MIN_SIZE)
                rect[3] = kotlin.math.max(d, rect[1] + MIN_SIZE)
            }
            HANDLE_BR -> {
                rect[2] = kotlin.math.max(c, rect[0] + MIN_SIZE)
                rect[3] = kotlin.math.max(d, rect[1] + MIN_SIZE)
            }
            HANDLE_BODY -> {
                val rw = rect[2] - rect[0]
                val rh = rect[3] - rect[1]
                var nx1 = c - rw / 2f
                var ny1 = d - rh / 2f
                if (nx1 < 0f) nx1 = 0f
                if (ny1 < 0f) ny1 = 0f
                if (nx1 + rw > 1f) nx1 = 1f - rw
                if (ny1 + rh > 1f) ny1 = 1f - rh
                rect[0] = nx1
                rect[1] = ny1
                rect[2] = nx1 + rw
                rect[3] = ny1 + rh
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
        private const val RECT_TOP = 0
        private const val RECT_BOTTOM = 1
        private const val HANDLE_NONE = -1
        private const val HANDLE_BODY = 0
        private const val HANDLE_TL = 1
        private const val HANDLE_TR = 2
        private const val HANDLE_BL = 3
        private const val HANDLE_BR = 4
        private const val HANDLE_RADIUS_PX = 60
    }
}