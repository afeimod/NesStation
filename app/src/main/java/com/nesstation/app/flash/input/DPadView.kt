package com.nesstation.app.flash.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.nesstation.app.flash.data.PrefsManager
import com.nesstation.app.flash.webview.GameWebView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 虚拟方向控制器，支持三种 UI 模式（[PrefsManager.dpadMode]）：
 *  - "dpad"     : 十字方向键，注入 ↑↓←→ 方向键
 *  - "wasd"     : 十字方向键，注入 W/A/S/D
 *  - "joystick" : 摇杆（外环 + 跟手摇杆头），注入 8 方向 ↑↓←→（适合 2D/3D 游戏）
 *
 * 触摸行为：
 *  - 按下/移动时根据触点相对中心的位置判断方向（上/下/左/右，支持对角线）
 *  - 对新进入的方向注入 down，对离开的方向注入 up
 *  - 抬起时释放全部
 *
 * 方向键事件通过 [GameWebView.injectKeyDown] / [injectKeyUp] 注入，
 * 因此对只监听 keydown/keyup 的 Flash/H5 键盘游戏同样生效。
 */
class DPadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 绑定的目标 WebView，方向键事件将注入到这里 */
    var targetWebView: GameWebView? = null

    /** 透明度 0~255 */
    var overlayAlpha: Int = 100
        set(value) { field = value.coerceIn(40, 255); invalidate() }

    /** 拖动编辑模式：开启后触摸用于拖动 View 本身位置 */
    var isDragMode: Boolean = false
        set(value) { field = value; invalidate() }

    /** 拖动起始触点相对 View 的偏移 */
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    // === FIX: drag lag (拖慢 / 不跟手) — same debounced-save pattern as
    // ActionButtonView. Previously every ACTION_MOVE wrote to SharedPreferences
    // synchronously (60+ writes/sec), causing the button to lag behind the
    // finger. We now debounce disk writes to 400ms after the last move and
    // flush immediately on ACTION_UP.
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var saveRunnable: Runnable? = null
    private var pendingPosX = 0f
    private var pendingPosY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressed = HashSet<Int>()              // 当前按下的方向 KeyCode
    private val pointerDir = HashMap<Int, Int>()      // 指针 ID → 方向 KeyCode（0 表示无）

    // 方向阈值：触点偏离中心超过半径的 15% 才算按下方向
    // ★★ v49：25% → 15% —— 旧版死区过大，轻推不出方向（搓招/快速转向吃亏）；
    //   中心解锁门控（centerUnlockRatio）防抖机制保留，菜单不会跳格。
    private val deadZoneRatio = 0.15f
    // 中心解锁阈值：再次触发同一方向前必须先回到这个半径以内，
    // 防止摇杆头在死区边界抖动时出现"down → up → down"短脉冲，
    // 避免菜单接收两次方向事件而多走几格。
    private val centerUnlockRatio = 0.10f

    private val dirKeys = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
    )

    // ---- 摇杆模式状态 ----
    /** 摇杆头当前坐标（相对 View 中心偏移），未按下时为 (0,0) */
    private var knobDx = 0f
    private var knobDy = 0f
    /** 摇杆是否处于按下态 */
    private var joystickActive = false
    /** 摇杆当前激活的指针 ID */
    private var joystickPointerId = -1
    /**
     * 摇杆上一帧是否处于"在中心解锁区"（norm <= centerUnlockRatio）。
     * 用于中心解锁防抖：必须先回到中心，再次推出到死区外才算新的方向事件，
     * 避免一次完整推杆在死区边界抖动时重复触发 down。
     */
    private var joystickInCenter = true

    private val isJoystick: Boolean get() = PrefsManager.dpadMode == "joystick"

    /**
     * 当 DPad 变为不可见时，释放所有已按下的方向键，
     * 防止 Ruffle 角色在隐藏手柄后仍持续移动。
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE && pressed.isNotEmpty()) {
            pressed.forEach { injectUp(it) }
            pressed.clear()
            pointerDir.clear()
            // 重置摇杆状态
            joystickActive = false
            joystickPointerId = -1
            knobDx = 0f
            knobDy = 0f
            joystickInCenter = true
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // View 被移除时也释放所有按键
        pressed.forEach { injectUp(it) }
        pressed.clear()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 应用位置偏移
        canvas.save()
        canvas.translate(PrefsManager.dpadOffsetX.toFloat(), PrefsManager.dpadOffsetY.toFloat())
        // 应用大小缩放
        val scale = PrefsManager.dpadScale
        canvas.scale(scale, scale, width / 2f, height / 2f)

        if (isJoystick) {
            drawJoystick(canvas)
        } else {
            drawCross(canvas)
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

    /** 十字方向键绘制 */
    private fun drawCross(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 8f

        // 背景圆
        paint.color = Color.argb((overlayAlpha * 0.35f).toInt(), 0, 0, 0)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, r, paint)

        // 十字臂
        val armW = r * 0.42f
        val armL = r * 0.95f

        paint.color = Color.argb(overlayAlpha, 255, 255, 255)
        drawArm(canvas, cx, cy, 0, armW, armL, KeyEvent.KEYCODE_DPAD_UP)
        drawArm(canvas, cx, cy, 1, armW, armL, KeyEvent.KEYCODE_DPAD_DOWN)
        drawArm(canvas, cx, cy, 2, armW, armL, KeyEvent.KEYCODE_DPAD_LEFT)
        drawArm(canvas, cx, cy, 3, armW, armL, KeyEvent.KEYCODE_DPAD_RIGHT)

        // 中心圆
        paint.color = Color.argb(overlayAlpha, 200, 200, 200)
        canvas.drawCircle(cx, cy, armW * 0.6f, paint)
    }

    /** 摇杆绘制：外环 + 可移动摇杆头 */
    private fun drawJoystick(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outerR = min(width, height) / 2f - 8f
        val knobR = outerR * 0.42f

        // 外环背景
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((overlayAlpha * 0.30f).toInt(), 0, 0, 0)
        canvas.drawCircle(cx, cy, outerR, paint)

        // 外环边框
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outerR * 0.06f
        paint.color = Color.argb(overlayAlpha, 255, 255, 255)
        canvas.drawCircle(cx, cy, outerR, paint)

        // 内圈刻度环
        paint.color = Color.argb((overlayAlpha * 0.5f).toInt(), 255, 255, 255)
        paint.strokeWidth = outerR * 0.03f
        canvas.drawCircle(cx, cy, outerR * 0.55f, paint)

        // 方向指示三角（上下左右）
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((overlayAlpha * 0.7f).toInt(), 255, 255, 255)
        val tri = outerR * 0.12f
        val triOff = outerR * 0.80f
        drawTriangle(canvas, cx, cy - triOff, tri, 0)   // 上
        drawTriangle(canvas, cx, cy + triOff, tri, 2)   // 下
        drawTriangle(canvas, cx - triOff, cy, tri, 3)   // 左
        drawTriangle(canvas, cx + triOff, cy, tri, 1)   // 右

        // 摇杆头
        val kx = cx + knobDx
        val ky = cy + knobDy
        paint.style = Paint.Style.FILL
        paint.color = if (joystickActive) {
            Color.argb(overlayAlpha, 0xFF, 0x6E, 0x40) // 激活橙
        } else {
            Color.argb(overlayAlpha, 0xFF, 0xC1, 0x07) // 待机黄
        }
        canvas.drawCircle(kx, ky, knobR, paint)
        // 摇杆头高光
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = knobR * 0.14f
        paint.color = Color.argb((overlayAlpha * 0.6f).toInt(), 255, 255, 255)
        canvas.drawCircle(kx, ky, knobR * 0.7f, paint)
    }

    private fun drawTriangle(canvas: Canvas, cx: Float, cy: Float, size: Float, dir: Int) {
        val p = android.graphics.Path()
        when (dir) {
            0 -> { // 上
                p.moveTo(cx, cy - size); p.lineTo(cx - size, cy + size); p.lineTo(cx + size, cy + size)
            }
            1 -> { // 右
                p.moveTo(cx + size, cy); p.lineTo(cx - size, cy - size); p.lineTo(cx - size, cy + size)
            }
            2 -> { // 下
                p.moveTo(cx, cy + size); p.lineTo(cx - size, cy - size); p.lineTo(cx + size, cy - size)
            }
            3 -> { // 左
                p.moveTo(cx - size, cy); p.lineTo(cx + size, cy - size); p.lineTo(cx + size, cy + size)
            }
        }
        p.close()
        canvas.drawPath(p, paint)
    }

    private fun drawArm(
        canvas: Canvas, cx: Float, cy: Float, dir: Int, armW: Float, armL: Float, keyCode: Int
    ) {
        val color = if (pressed.contains(keyCode)) {
            Color.argb(overlayAlpha, 0xFF, 0x57, 0x22) // 按下高亮橙
        } else {
            Color.argb(overlayAlpha, 0xFF, 0xFF, 0xFF)
        }
        paint.color = color
        when (dir) {
            0 -> canvas.drawRoundRect(cx - armW, cy - armL, cx + armW, cy, armW * 0.5f, armW * 0.5f, paint)
            1 -> canvas.drawRoundRect(cx - armW, cy, cx + armW, cy + armL, armW * 0.5f, armW * 0.5f, paint)
            2 -> canvas.drawRoundRect(cx - armL, cy - armW, cx, cy + armW, armW * 0.5f, armW * 0.5f, paint)
            3 -> canvas.drawRoundRect(cx, cy - armW, cx + armL, cy + armW, armW * 0.5f, armW * 0.5f, paint)
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
                            .putFloat("dpad_pos_x", pendingPosX)
                            .putFloat("dpad_pos_y", pendingPosY)
                            .apply()
                    }
                    saveRunnable = r
                    saveHandler.postDelayed(r, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveRunnable?.let { saveHandler.removeCallbacks(it) }
                    saveRunnable = null
                    PrefsManager.sp.edit()
                        .putFloat("dpad_pos_x", pendingPosX)
                        .putFloat("dpad_pos_y", pendingPosY)
                        .apply()
                }
            }
            return true
        }
        if (isJoystick) {
            handleJoystickTouch(event)
        } else {
            handleCrossTouch(event)
        }
        return true
    }

    // ---------------- 十字方向键触摸 ----------------
    private fun handleCrossTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val active = HashSet<Int>()
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val dirs = computeDirections(x, y)
                    pointerDir[pid]?.let { old -> if (old != 0 && old !in dirs) active.add(old) }
                    dirs.forEach { d -> active.add(d) }
                }
                val toDown = active - pressed
                val toUp = pressed - active
                toDown.forEach { injectDown(it) }
                toUp.forEach { injectUp(it) }
                pressed.clear()
                pressed.addAll(active)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(event.actionIndex)
                pointerDir.remove(pid)
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pressed.forEach { injectUp(it) }
                    pressed.clear()
                    pointerDir.clear()
                } else {
                    val active = HashSet<Int>()
                    for (i in 0 until event.pointerCount) {
                        if (i == event.actionIndex) continue
                        event.getPointerId(i)
                        computeDirections(event.getX(i), event.getY(i)).forEach { d -> active.add(d) }
                    }
                    val toUp = pressed - active
                    toUp.forEach { injectUp(it) }
                    pressed.clear()
                    pressed.addAll(active)
                }
                invalidate()
            }
        }
    }

    /** 根据触点位置返回当前按下的方向集合 */
    private fun computeDirections(x: Float, y: Float): Set<Int> {
        val cx = width / 2f
        val cy = height / 2f
        val r = max(min(width, height) / 2f, 1f)
        val dx = (x - cx) / r
        val dy = (y - cy) / r
        val set = HashSet<Int>()
        if (abs(dx) < deadZoneRatio && abs(dy) < deadZoneRatio) return set // 中心死区
        if (abs(dx) > abs(dy)) {
            set.add(if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        } else {
            set.add(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        }
        return set
    }

    // ---------------- 摇杆触摸 ----------------
    private fun handleJoystickTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (joystickPointerId == -1) {
                    val idx = event.actionIndex
                    joystickPointerId = event.getPointerId(idx)
                    joystickActive = true
                    updateJoystick(event.getX(idx), event.getY(idx))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (joystickPointerId != -1) {
                    val i = event.findPointerIndex(joystickPointerId)
                    if (i >= 0) updateJoystick(event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetJoystick()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(event.actionIndex)
                if (pid == joystickPointerId) resetJoystick()
            }
        }
    }

    /** 根据触点更新摇杆头位置与方向注入 */
    private fun updateJoystick(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val outerR = max(min(width, height) / 2f - 8f, 1f)
        var dx = x - cx
        var dy = y - cy
        val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        // 摇杆头限制在外环内
        val maxDist = outerR * 0.85f
        if (dist > maxDist) {
            val ratio = maxDist / dist
            dx *= ratio
            dy *= ratio
        }
        knobDx = dx
        knobDy = dy

        // 计算方向（8 方向独立判定）。
        // 对角线：两轴都超过 30% 外环时同时激活 X 和 Y；
        // 否则按主轴兜底,避免对角线时两个轴同时触发导致菜单跳两格。
        // ★★ v49：对角线阈值 35% → 30% —— 斜向（↘↙↗↖）更容易触发，
        //   斜跑/搓招跟手；主轴兜底与中心解锁门控不变（菜单不跳格）。
        val active = HashSet<Int>()
        val norm = dist / outerR
        if (norm > deadZoneRatio) {
            val ax = abs(dx)
            val ay = abs(dy)
            val diagRatio = 0.30f
            if (ax > outerR * diagRatio) {
                active.add(if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            }
            if (ay > outerR * diagRatio) {
                active.add(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
            }
            // 若两轴都不足，按主轴方向
            if (active.isEmpty()) {
                if (ax > ay) {
                    active.add(if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                } else {
                    active.add(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
                }
            }
        }

        // 中心解锁门控:必须先回到中心(norm <= centerUnlockRatio),
        // 再次推出到死区外时才算"新的"方向事件,避免一次推杆过程中
        // 在死区边界抖动产生 down -> up -> down 短脉冲(菜单会因此多走 1 格)。
        //
        // 关键:按下期间(pressed 非空)即使摇杆在死区内小幅抖动,
        // 只要没有完全回到中心解锁区,就保留当前 pressed,不重新发 down。
        val nowInCenter = norm <= centerUnlockRatio
        when {
            nowInCenter -> {
                // 完全回到中心:释放所有残留方向,允许下一次推杆重新触发
                if (pressed.isNotEmpty()) {
                    pressed.forEach { injectUp(it) }
                    pressed.clear()
                }
            }
            pressed.isEmpty() -> {
                // 从中心首次推出到死区外:正常发 down
                active.forEach { injectDown(it) }
                pressed.clear()
                pressed.addAll(active)
            }
            // 按下期间(已发出 down)的小幅抖动:忽略小幅回到死区内的帧,
            // 保留 pressed 不变,避免误发 up 后再发新 down。
            // 仍在死区外,但方向变了(从 DOWN 推到 RIGHT):发差量。
            active.isNotEmpty() -> {
                val toDown = active - pressed
                val toUp = pressed - active
                toDown.forEach { injectDown(it) }
                toUp.forEach { injectUp(it) }
                pressed.clear()
                pressed.addAll(active)
            }
            // 按下期间摇杆小幅回到死区内(active 为空,但没完全到中心):保留 pressed,不重复发 down/up
        }
        joystickInCenter = nowInCenter
        invalidate()
    }

    private fun resetJoystick() {
        joystickActive = false
        joystickPointerId = -1
        knobDx = 0f
        knobDy = 0f
        joystickInCenter = true
        pressed.forEach { injectUp(it) }
        pressed.clear()
        invalidate()
    }

    private fun injectDown(keyCode: Int) {
        val mappedKey = mapDirectionKey(keyCode)
        targetWebView?.injectKeyDown(mappedKey)
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun injectUp(keyCode: Int) {
        val mappedKey = mapDirectionKey(keyCode)
        targetWebView?.injectKeyUp(mappedKey)
    }

    /** 根据 dpadMode 将方向键映射为 DPAD_* 或 WASD。
     *  - dpad 模式：注入方向键 ↑↓←→
     *  - wasd 模式：注入 W/A/S/D（十字键 UI）
     *  - joystick 模式：注入 W/A/S/D（摇杆 UI），摇杆为默认模式 */
    private fun mapDirectionKey(keyCode: Int): Int {
        val mode = PrefsManager.dpadMode
        if (mode == "dpad") return keyCode
        // joystick 和 wasd 模式都映射到 WASD
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_W
            KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_S
            KeyEvent.KEYCODE_DPAD_LEFT -> KeyEvent.KEYCODE_A
            KeyEvent.KEYCODE_DPAD_RIGHT -> KeyEvent.KEYCODE_D
            else -> keyCode
        }
    }
}
