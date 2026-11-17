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
import kotlin.math.min

/**
 * 动作按钮组：支持动态数量的可配置按键（2~18 个）。
 *
 * - 按键映射来自 [PrefsManager.gamepadKeys]（数量由 [PrefsManager.gamepadKeyCount] 决定）
 * - 按键大小来自 [PrefsManager.gamepadScale]
 * - 支持多指同时按下不同按钮
 * - 按下注入 keydown，松开注入 keyup
 *
 * 布局：自适应网格（≤2 个两列，其余三列），圆形按钮 + 鲜艳多色配色，
 * 与参考图的“深蓝+青色圆角矩阵”刻意区分（颜色与布局均不同）。
 */
class ActionButtonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var targetWebView: GameWebView? = null

    var overlayAlpha: Int = 153
        set(value) { field = value.coerceIn(40, 255); invalidate() }

    /** 拖动编辑模式：开启后触摸用于拖动 View 本身位置 */
    var isDragMode: Boolean = false
        set(value) { field = value; invalidate() }

    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    // === FIX: drag lag (拖慢 / 不跟手) ===
    // Previously every ACTION_MOVE called PrefsManager.sp.edit().putFloat().apply(),
    // which writes to disk 60+ times per second during a drag. On low-end devices
    // this lands the main thread in XmlUtils.writeMapXml() on the prefs I/O thread,
    // causing severe stutter — the button visibly lags behind the finger.
    //
    // Fix: keep the latest position in `pendingPos` and only persist via a
    // debounced Handler.postDelayed (400ms after the last move). The UI still
    // updates immediately via setX/setY, so the button tracks the finger in
    // real time; only the disk write is throttled. ACTION_UP flushes immediately
    // so the final position is always saved.
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var saveRunnable: Runnable? = null
    private var pendingPosX = 0f
    private var pendingPosY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 鲜艳多色调色板（刻意区别于参考图的单色青蓝） */
    private val buttonColors = intArrayOf(
        Color.argb(255, 0xE5, 0x39, 0x35),  // 红
        Color.argb(255, 0x1E, 0x88, 0xE5),  // 蓝
        Color.argb(255, 0x43, 0xA0, 0x47),  // 绿
        Color.argb(255, 0xFF, 0xB3, 0x00),  // 琥珀
        Color.argb(255, 0x8E, 0x24, 0xAA),  // 紫
        Color.argb(255, 0xFF, 0x6E, 0x40),  // 橙
        Color.argb(255, 0x00, 0xBF, 0xC4),  // 青绿
        Color.argb(255, 0xEC, 0x40, 0x7A),  // 粉
        Color.argb(255, 0x7E, 0x57, 0xC2),  // 靛
        Color.argb(255, 0x66, 0xBB, 0x6A),  // 浅绿
        Color.argb(255, 0xFF, 0xA7, 0x26),  // 橙黄
        Color.argb(255, 0x42, 0xA5, 0xF5),  // 浅蓝
        Color.argb(255, 0xEF, 0x53, 0x50),  // 浅红
        Color.argb(255, 0xAB, 0x47, 0xBC),  // 浅紫
        Color.argb(255, 0x26, 0xC6, 0xDA),  // 青
        Color.argb(255, 0x9C, 0xC6, 0x57),  // 黄绿
        Color.argb(255, 0xFF, 0xCA, 0x28),  // 黄
        Color.argb(255, 0xEF, 0x53, 0x50)   // 红
    )
    private val pressedColor = Color.argb(255, 0xFF, 0xFF, 0xFF)

    /** 每个按钮的按下状态：index → 是否按下 */
    private val pressedState = HashMap<Int, Boolean>()
    /** 每个指针 ID → 按下的按钮 index（-1 表示未按下） */
    private val pointerButton = HashMap<Int, Int>()

    /** 网格列数：≤2 个用 2 列，其余用 3 列 */
    private fun columnsOf(count: Int): Int = if (count <= 2) 2 else 3

    /**
     * 当按钮组变为不可见时，释放所有已按下的按键，
     * 防止 Ruffle 角色在隐藏手柄后仍持续移动。
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            releaseAllPressed()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releaseAllPressed()
    }

    /** 释放所有当前按下的按钮对应的按键 */
    private fun releaseAllPressed() {
        val keys = keyCodes()
        pointerButton.values.forEach { btn ->
            if (btn >= 0 && btn < keys.size) {
                targetWebView?.injectKeyUp(keys[btn])
            }
        }
        pressedState.clear()
        pointerButton.clear()
        invalidate()
    }

    private fun keyCodes(): List<Int> {
        return PrefsManager.gamepadKeys.map { KeyMapper.toKeyCode(it) }
    }

    /** 计算每个按钮的圆心和半径（自适应网格） */
    private fun getButtonPositions(): List<Triple<Float, Float, Float>> {
        val w = width.toFloat()
        val h = height.toFloat()
        val count = PrefsManager.gamepadKeyCount
        val columns = columnsOf(count)
        val rows = (count + columns - 1) / columns
        val cellW = w / columns
        val cellH = h / rows
        val r = min(cellW, cellH) * 0.40f
        val positions = mutableListOf<Triple<Float, Float, Float>>()
        for (i in 0 until count) {
            val col = i % columns
            val row = i / columns
            val cx = cellW * (col + 0.5f)
            val cy = cellH * (row + 0.5f)
            positions.add(Triple(cx, cy, r))
        }
        return positions
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()

        val positions = getButtonPositions()
        val keys = PrefsManager.gamepadKeys
        val visible = PrefsManager.gamepadKeyVisible
        val count = PrefsManager.gamepadKeyCount

        for (i in 0 until count) {
            if (i >= positions.size) continue
            if (!visible.getOrElse(i) { true }) continue
            val (cx, cy, r) = positions[i]
            val baseColor = buttonColors[i % buttonColors.size]
            val pressed = pressedState[i] == true
            paint.style = Paint.Style.FILL
            paint.color = if (pressed) {
                Color.argb(overlayAlpha, Color.red(pressedColor), Color.green(pressedColor), Color.blue(pressedColor))
            } else {
                Color.argb(overlayAlpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
            }
            canvas.drawCircle(cx, cy, r, paint)

            // 内圈高光，区别于参考图的纯色块
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = r * 0.12f
            paint.color = Color.argb((overlayAlpha * 0.6f).toInt(), 255, 255, 255)
            canvas.drawCircle(cx, cy, r * 0.82f, paint)

            // 按钮标签：显示实际映射的按键名
            val label = keys.getOrElse(i) { "" }
            paint.color = Color.WHITE
            paint.textSize = r * 0.72f
            paint.textAlign = Paint.Align.CENTER
            paint.style = Paint.Style.FILL
            canvas.drawText(label, cx, cy + r * 0.26f, paint)
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
                    // UI updates immediately — button tracks finger in real time.
                    x = newX
                    y = newY
                    // Debounced disk write (see fix comment above).
                    pendingPosX = newX
                    pendingPosY = newY
                    saveRunnable?.let { saveHandler.removeCallbacks(it) }
                    val r = Runnable {
                        PrefsManager.sp.edit()
                            .putFloat("action_pos_x", pendingPosX)
                            .putFloat("action_pos_y", pendingPosY)
                            .apply()
                    }
                    saveRunnable = r
                    saveHandler.postDelayed(r, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Flush immediately so the final position is always saved.
                    saveRunnable?.let { saveHandler.removeCallbacks(it) }
                    saveRunnable = null
                    PrefsManager.sp.edit()
                        .putFloat("action_pos_x", pendingPosX)
                        .putFloat("action_pos_y", pendingPosY)
                        .apply()
                }
            }
            return true
        }
        val positions = getButtonPositions()
        val keys = keyCodes()
        val visible = PrefsManager.gamepadKeyVisible
        val count = PrefsManager.gamepadKeyCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val x = event.getX(idx)
                val y = event.getY(idx)
                val pid = event.getPointerId(idx)
                val btn = hitButton(x, y, positions, visible, count)
                if (btn >= 0 && btn < keys.size && visible.getOrElse(btn) { true }) {
                    pressedState[btn] = true
                    pointerButton[pid] = btn
                    targetWebView?.injectKeyDown(keys[btn])
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val btn = pointerButton[pid]
                    if (btn != null && btn >= 0 && btn < positions.size) {
                        // 手指已在某按钮上：使用扩展半径(1.3x)检测是否仍在该按钮范围
                        // 滞后机制：手指需明显移出按钮范围才释放，避免边界抖动导致按键串联
                        val (cx, cy, r) = positions[btn]
                        val dist = Math.hypot((x - cx).toDouble(), (y - cy).toDouble())
                        if (dist < r * 1.3f) {
                            // 仍在当前按钮扩展范围内，保持按下（不切换）
                            continue
                        }
                        // 手指已移出当前按钮范围 → 释放
                        pressedState[btn] = false
                        pointerButton.remove(pid)
                        if (btn < keys.size) targetWebView?.injectKeyUp(keys[btn])
                        // 检查是否滑入新按钮的核心区域(0.7x)，排除原按钮
                        val newBtn = hitButtonWithRadius(x, y, positions, visible, count, 0.7f, btn)
                        if (newBtn >= 0 && newBtn < keys.size) {
                            pressedState[newBtn] = true
                            pointerButton[pid] = newBtn
                            targetWebView?.injectKeyDown(keys[newBtn])
                        }
                    } else {
                        // 手指不在任何按钮上：检查是否滑入按钮核心区域(0.7x)
                        val newBtn = hitButtonWithRadius(x, y, positions, visible, count, 0.7f)
                        if (newBtn >= 0 && newBtn < keys.size) {
                            pressedState[newBtn] = true
                            pointerButton[pid] = newBtn
                            targetWebView?.injectKeyDown(keys[newBtn])
                        }
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerButton.values.forEach { btn ->
                    if (btn >= 0 && btn < keys.size) {
                        pressedState[btn] = false
                        targetWebView?.injectKeyUp(keys[btn])
                    }
                }
                pointerButton.clear()
                pressedState.clear()
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(event.actionIndex)
                val btn = pointerButton.remove(pid)
                if (btn != null && btn >= 0 && btn < keys.size) {
                    pressedState[btn] = false
                    targetWebView?.injectKeyUp(keys[btn])
                }
                invalidate()
            }
        }
        return true
    }

    private fun hitButton(
        x: Float, y: Float,
        positions: List<Triple<Float, Float, Float>>,
        visible: List<Boolean>,
        count: Int
    ): Int {
        for (i in 0 until minOf(positions.size, count)) {
            if (!visible.getOrElse(i) { true }) continue
            val (cx, cy, r) = positions[i]
            val dist = Math.hypot((x - cx).toDouble(), (y - cy).toDouble())
            if (dist < r) return i
        }
        return -1
    }

    /**
     * 带半径倍率的按钮命中检测（用于 ACTION_MOVE 滞后机制）。
     * - radiusMultiplier > 1.0：扩展检测范围（判断手指是否仍在当前按钮范围）
     * - radiusMultiplier < 1.0：收缩检测范围（判断手指是否进入新按钮核心区域）
     */
    private fun hitButtonWithRadius(
        x: Float, y: Float,
        positions: List<Triple<Float, Float, Float>>,
        visible: List<Boolean>,
        count: Int,
        radiusMultiplier: Float,
        excludeBtn: Int = -1
    ): Int {
        for (i in 0 until minOf(positions.size, count)) {
            if (i == excludeBtn) continue
            if (!visible.getOrElse(i) { true }) continue
            val (cx, cy, r) = positions[i]
            val dist = Math.hypot((x - cx).toDouble(), (y - cy).toDouble())
            if (dist < r * radiusMultiplier) return i
        }
        return -1
    }
}
