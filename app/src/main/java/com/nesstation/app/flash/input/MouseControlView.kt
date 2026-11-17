package com.nesstation.app.flash.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.nesstation.app.flash.data.PrefsManager
import com.nesstation.app.flash.webview.GameWebView
import kotlin.math.abs

/**
 * 鼠标按钮 View：作为可添加的虚拟按钮，和方向键/动作按钮并列。
 *
 * - 左键按钮：短按=鼠标左键单击，长按=持续按住并进入旋转视角模式（拖动移动鼠标）
 * - 右键按钮：短按=鼠标右键单击，长按=持续按住并进入旋转视角模式
 *
 * 布局：两个圆形按钮左右排列，大小跟随 [PrefsManager.gamepadScale]。
 * 位置跟随 [PrefsManager] 的 mouseOffsetX/Y。
 */
class MouseControlView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var targetWebView: GameWebView? = null
    var overlayAlpha: Int = 100

    /** 拖动编辑模式：开启后触摸用于拖动 View 本身位置 */
    var isDragMode: Boolean = false
        set(value) { field = value; invalidate() }

    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    // === FIX: drag lag (拖慢 / 不跟手) — same debounced-save pattern as
    // ActionButtonView / DPadView. Previously every ACTION_MOVE wrote to
    // SharedPreferences synchronously (60+ writes/sec), causing the view
    // to lag behind the finger. We now debounce disk writes to 400ms after
    // the last move and flush immediately on ACTION_UP.
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var saveRunnable: Runnable? = null
    private var pendingPosX = 0f
    private var pendingPosY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 左键按下状态 */
    private var leftPressed = false
    /** 右键按下状态 */
    private var rightPressed = false
    /** 长按阈值（ms） */
    private val longPressThreshold = 300L
    /** 长按移动阈值（像素），超过则进入旋转视角 */
    private val dragThreshold = 10f
    /** 按下时的坐标和时间 */
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    /** 当前是否在拖动旋转视角 */
    private var isDragging = false
    /** 上次移动坐标 */
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    /** 当前按下的按钮：0=无, 1=左键, 2=右键 */
    private var activeButton = 0
    /** 是否已触发长按 */
    private var longPressTriggered = false

    /** 按钮半径 */
    private val btnRadius: Float
        get() = (minOf(width, height) * 0.15f * PrefsManager.gamepadScale).coerceAtLeast(50f)

    /** 左键中心 */
    private val leftCx: Float get() = width * 0.33f
    private val leftCy: Float get() = height * 0.5f

    /** 右键中心 */
    private val rightCx: Float get() = width * 0.66f
    private val rightCy: Float get() = height * 0.5f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 应用位置偏移
        canvas.save()
        canvas.translate(PrefsManager.mouseOffsetX.toFloat(), PrefsManager.mouseOffsetY.toFloat())

        val r = btnRadius

        // 左键按钮
        val leftColor = if (leftPressed) Color.argb(overlayAlpha, 0x64, 0xB5, 0xF6) else Color.argb(overlayAlpha, 0x1E, 0x88, 0xE5)
        paint.style = Paint.Style.FILL
        paint.color = leftColor
        canvas.drawCircle(leftCx, leftCy, r, paint)
        paint.color = Color.WHITE
        paint.textSize = r * 0.45f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("左键", leftCx, leftCy + r * 0.15f, paint)

        // 右键按钮
        val rightColor = if (rightPressed) Color.argb(overlayAlpha, 0xEF, 0x9A, 0x9A) else Color.argb(overlayAlpha, 0xE5, 0x39, 0x35)
        paint.style = Paint.Style.FILL
        paint.color = rightColor
        canvas.drawCircle(rightCx, rightCy, r, paint)
        paint.color = Color.WHITE
        canvas.drawText("右键", rightCx, rightCy + r * 0.15f, paint)

        // 拖动旋转视角提示
        if (isDragging) {
            paint.color = Color.argb(180, 255, 255, 0)
            paint.textSize = 40f
            canvas.drawText("拖动旋转视角", width / 2f, 60f, paint)
        }

        canvas.restore()
        // 拖动模式边框提示
        if (isDragMode) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.argb(200, 255, 255, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 拖动编辑模式：拖动 View 本身
        if (isDragMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragOffsetX = event.rawX - x
                    dragOffsetY = event.rawY - y
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = (event.rawX - dragOffsetX).coerceIn(0f, (parent as View).width - width.toFloat())
                    val newY = (event.rawY - dragOffsetY).coerceIn(0f, (parent as View).height - height.toFloat())
                    x = newX
                    y = newY
                    pendingPosX = newX
                    pendingPosY = newY
                    saveRunnable?.let { saveHandler.removeCallbacks(it) }
                    val r = Runnable {
                        PrefsManager.sp.edit()
                            .putFloat("mouse_pos_x", pendingPosX)
                            .putFloat("mouse_pos_y", pendingPosY)
                            .apply()
                    }
                    saveRunnable = r
                    saveHandler.postDelayed(r, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveRunnable?.let { saveHandler.removeCallbacks(it) }
                    saveRunnable = null
                    PrefsManager.sp.edit()
                        .putFloat("mouse_pos_x", pendingPosX)
                        .putFloat("mouse_pos_y", pendingPosY)
                        .apply()
                }
            }
            return true
        }
        val offsetX = PrefsManager.mouseOffsetX.toFloat()
        val offsetY = PrefsManager.mouseOffsetY.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x - offsetX
                val y = event.y - offsetY
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                longPressTriggered = false
                isDragging = false
                activeButton = hitButton(x, y)
                when (activeButton) {
                    1 -> { leftPressed = true; invalidate() }
                    2 -> { rightPressed = true; invalidate() }
                }
                // 启动长按检测
                if (activeButton > 0) {
                    postDelayed({
                        if (activeButton > 0 && !longPressTriggered) {
                            val elapsed = System.currentTimeMillis() - downTime
                            if (elapsed >= longPressThreshold) {
                                longPressTriggered = true
                                // 长按：注入鼠标按下，进入旋转模式
                                when (activeButton) {
                                    1 -> targetWebView?.injectMouseLeftDown(downX, downY)
                                    2 -> targetWebView?.injectMouseRightClick(downX, downY)
                                }
                                lastMoveX = downX
                                lastMoveY = downY
                                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            }
                        }
                    }, longPressThreshold)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeButton > 0 && longPressTriggered) {
                    // 长按后拖动：旋转视角
                    val dx = event.x - lastMoveX
                    val dy = event.y - lastMoveY
                    if (!isDragging && (abs(dx) > dragThreshold || abs(dy) > dragThreshold)) {
                        isDragging = true
                        invalidate()
                    }
                    if (isDragging) {
                        targetWebView?.injectMouseMove(dx, dy)
                        lastMoveX = event.x
                        lastMoveY = event.y
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeButton > 0) {
                    if (longPressTriggered) {
                        // 长按松开：注入鼠标松开
                        when (activeButton) {
                            1 -> targetWebView?.injectMouseLeftUp(event.x, event.y)
                        }
                        if (isDragging) {
                            // 拖动结束，不需要额外点击
                        } else {
                            // 长按但未拖动，视为按住状态松开
                        }
                    } else {
                        // 短按：单击
                        when (activeButton) {
                            1 -> targetWebView?.injectMouseLeftClick(event.x, event.y)
                            2 -> targetWebView?.injectMouseRightClick(event.x, event.y)
                        }
                    }
                    leftPressed = false
                    rightPressed = false
                    activeButton = 0
                    isDragging = false
                    longPressTriggered = false
                    invalidate()
                }
                return true
            }
        }
        return false
    }

    /** 判断坐标命中哪个按钮：0=无, 1=左键, 2=右键 */
    private fun hitButton(x: Float, y: Float): Int {
        val leftDist = Math.hypot((x - leftCx).toDouble(), (y - leftCy).toDouble())
        if (leftDist < btnRadius) return 1
        val rightDist = Math.hypot((x - rightCx).toDouble(), (y - rightCy).toDouble())
        if (rightDist < btnRadius) return 2
        return 0
    }
}
