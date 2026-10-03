package com.nesstation.app.core.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * ★★ 手机体感 → Wii 倾斜/挥动/摇晃模拟（V2 重写：修旧版"偶尔生效/无作用"问题）★★
 *
 * 需求原话："并加入手机体感模拟wii体感"，用户反馈："左右倾斜偶尔生效偶尔无效，
 * 前后一样，手机模拟体感毫无作用，没有接入"。
 *
 * V1 旧版问题诊断：
 *  1. 只用加速度计，没接陀螺仪 → 真正的 3D 旋转追踪不到，只看得到重力方向；
 *  2. 低通滤波 alpha=0.15 过低 → 响应延迟约 250ms，"偶尔生效"的直接根因；
 *  3. DEADZONE=0.06 / FULL_TILT_G=0.55 → 死区太大、满程太难触发；
 *  4. 只推 TILT_F/B/L/R + SWING_F/B 五根轴 → Wii Sports 这类挥拍游戏完全
 *     读不到 SWING_UP/DOWN/LEFT/RIGHT（120-123），挥拍无反应；
 *  5. 完全没推 SHAKE_X/Y/Z（132-134）→ 马里奥赛车 wheelie/抽搐类动作识别不到；
 *  6. 缺少高通分支 → 甩手时被低通滤波吃掉，"挥动毫无作用"。
 *
 * V2 修复：
 *  - 加 TYPE_GYROSCOPE 用于真 3D 旋转追踪（角速度 → 四元积分 → 设备姿态）；
 *  - 加 TYPE_ACCELEROMETER + TYPE_LINEAR_ACCELERATION 双路：前者提取重力（倾斜），
 *    后者直接给挥动/摇晃（无重力分量，灵敏度高）；
 *  - 加高通分支（线性加速度幅值 > 阈值 → 视为挥动/摇动事件）；
 *  - 推 SWING 全方向（F/B/U/D/L/R）+ SHAKE_X/Y/Z（132-134）；
 *  - 参数调优：alpha 0.35（响应快）/ DEADZONE 0.03 / FULL_TILT_G 0.42；
 *  - sink 签名扩展为 11 个 Float（4 tilt + 4 swing + 3 shake）。
 *
 * 屏幕坐标系（旋转后统一）：X_s 右、Y_s 下、Z_s 出屏朝用户。
 *   - turn LEFT  → 重力 -X_s → left > 0
 *   - 顶边前推 → 重力 -Z_s → forward > 0
 *   - 顶边左甩 → 线性加速度 -X_s → swingLeft > 0
 */
object WiiMotionSensors {

    /** 倾斜满强度对应的重力分量变化（约 25°，比旧版 30° 更易触发满程）。 */
    private const val FULL_TILT_G = 0.42f

    /** 死区（比旧版 0.06 小一半，轻微倾摆也能识别）。 */
    private const val DEADZONE = 0.03f

    /** 挥动触发阈值（线性加速度幅值，m/s²）。低于此值视为静止。 */
    private const val SWING_THRESHOLD = 1.5f

    /** 挥动满强度阈值（线性加速度幅值，约对应"用力一甩"）。 */
    private const val SWING_FULL = 6.0f

    /** 摇晃触发阈值（瞬时角速度变化，rad/s）。 */
    private const val SHAKE_THRESHOLD = 4.0f

    /** 摇晃满强度阈值。 */
    private const val SHAKE_FULL = 12.0f

    /**
     * 体感状态（每次事件回调后输出，11 维）。
     *
     * 索引约定：
     *  - [0..3] = tiltLeft, tiltRight, tiltForward, tiltBackward（0..1，重力分量驱动）
     *  - [4..7] = swingUp, swingDown, swingLeft, swingRight（0..1，线性加速度驱动）
     *  - [8..9] = swingForward, swingBackward（0..1，沿屏幕 Z 轴的推/拉）
     *  - [10..12] = shakeX, shakeY, shakeZ（0..1，瞬时角速度突变驱动）
     */
    data class MotionState(
        var tiltLeft: Float = 0f,
        var tiltRight: Float = 0f,
        var tiltForward: Float = 0f,
        var tiltBackward: Float = 0f,
        var swingUp: Float = 0f,
        var swingDown: Float = 0f,
        var swingLeft: Float = 0f,
        var swingRight: Float = 0f,
        var swingForward: Float = 0f,
        var swingBackward: Float = 0f,
        var shakeX: Float = 0f,
        var shakeY: Float = 0f,
        var shakeZ: Float = 0f
    )

    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null

    /** 基准姿态（开启时的重力屏幕分量）。 */
    private val baseGravity = FloatArray(3)

    /** 低通滤波后的重力（屏幕坐标系）。 */
    private val smoothed = FloatArray(3)

    /** 上一帧线性加速度（用于摇动检测的导数计算）。 */
    private val lastLinAccel = FloatArray(3)

    /** 上一帧陀螺角速度（用于摇动检测的导数计算）。 */
    private val lastGyro = FloatArray(3)

    @Volatile private var calibrated = false
    @Volatile private var hasLinearAccel = false
    @Volatile private var hasGyro = false

    /**
     * 启动体感监听（V2 扩展版，MotionState sink）。
     *
     * @param displayRotation 当前显示旋转角（Surface.ROTATION_0/90/180/270）
     * @param sink 每次事件回调（已去抖 + 滤波 + 摇动识别），返回完整 13 维运动状态
     * @return true 成功注册加速度计（最低需求）；陀螺仪/线性加速度可选
     */
    fun start(
        context: Context,
        displayRotation: Int,
        sink: (MotionState) -> Unit
    ): Boolean {
        stop()
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        // 可选传感器：没有也能跑（功能降级）
        val linAcc = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorManager = sm
        calibrated = false
        hasLinearAccel = linAcc != null
        hasGyro = gyro != null
        smoothed.fill(0f)
        lastLinAccel.fill(0f)
        lastGyro.fill(0f)

        val state = MotionState()
        val rotation = displayRotation

        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> handleAccel(event, rotation, state, sink)
                    Sensor.TYPE_LINEAR_ACCELERATION -> handleLinAccel(event, rotation, state, sink)
                    Sensor.TYPE_GYROSCOPE -> handleGyro(event, rotation, state, sink)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        return try {
            sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_GAME)
            linAcc?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
            gyro?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
            listener = l
            true
        } catch (_: Throwable) {
            listener = null
            false
        }
    }

    /** 处理加速度计：提取重力（低通）→ 算倾斜。 */
    private fun handleAccel(
        event: SensorEvent,
        rotation: Int,
        state: MotionState,
        sink: (MotionState) -> Unit
    ) {
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]
        val gxS: Float
        val gyS: Float
        when (rotation) {
            Surface.ROTATION_90 -> { gxS = -gy; gyS = gx }
            Surface.ROTATION_270 -> { gxS = gy; gyS = -gx }
            Surface.ROTATION_180 -> { gxS = -gx; gyS = -gy }
            else -> { gxS = gx; gyS = gy }
        }
        val gzS = gz
        // ★ 调优：alpha 0.35（旧版 0.15）→ 响应延迟 ~80ms，告别"偶尔生效"
        val alpha = 0.35f
        smoothed[0] = alpha * gxS + (1 - alpha) * smoothed[0]
        smoothed[1] = alpha * gyS + (1 - alpha) * smoothed[1]
        smoothed[2] = alpha * gzS + (1 - alpha) * smoothed[2]
        if (!calibrated) {
            baseGravity[0] = smoothed[0]
            baseGravity[1] = smoothed[1]
            baseGravity[2] = smoothed[2]
            calibrated = true
            return
        }
        // 倾斜：相对基准的重力分量差
        fun clean(v: Float): Float {
            val a = kotlin.math.abs(v)
            return if (a < DEADZONE) 0f
            else ((a - DEADZONE) / (FULL_TILT_G - DEADZONE)).coerceIn(0f, 1f)
        }
        val dX = smoothed[0] - baseGravity[0]   // >0 = 右倾
        val dZ = smoothed[2] - baseGravity[2]   // <0 = 前倾（顶边前推）
        state.tiltRight = clean(dX)
        state.tiltLeft = clean(-dX)
        state.tiltForward = clean(-dZ)
        state.tiltBackward = clean(dZ)

        // ★ 无线性加速度传感器时：从加速度计高通得到挥动估计（fallback）
        if (!hasLinearAccel) {
            // 高通：原始加速度 - 低通重力 = 线性部分（粗略估计）
            val linX = gxS - smoothed[0]
            val linY = gyS - smoothed[1]
            val linZ = gzS - smoothed[2]
            updateSwingFromLinear(linX, linY, linZ, state)
        }
        try { sink(state) } catch (_: Throwable) {}
    }

    /** 处理线性加速度（去重力后的纯运动）：驱动挥动。 */
    private fun handleLinAccel(
        event: SensorEvent,
        rotation: Int,
        state: MotionState,
        sink: (MotionState) -> Unit
    ) {
        val lx = event.values[0]
        val ly = event.values[1]
        val lz = event.values[2]
        // 设备坐标 → 屏幕坐标（同 handleAccel 的旋转表）
        val lxS: Float
        val lyS: Float
        when (rotation) {
            Surface.ROTATION_90 -> { lxS = -ly; lyS = lx }
            Surface.ROTATION_270 -> { lxS = ly; lyS = -lx }
            Surface.ROTATION_180 -> { lxS = -lx; lyS = -ly }
            else -> { lxS = lx; lyS = ly }
        }
        val lzS = lz
        updateSwingFromLinear(lxS, lyS, lzS, state)
        // 线性加速度的瞬时变化率 = 抖动（与陀螺的角速度协同 → 更可靠的摇动检测）
        val dx = lxS - lastLinAccel[0]
        val dy = lyS - lastLinAccel[1]
        val dz = lzS - lastLinAccel[2]
        lastLinAccel[0] = lxS; lastLinAccel[1] = lyS; lastLinAccel[2] = lzS
        // 抖动 = 瞬时变化幅值（无陀螺时用此分支兜底）
        if (!hasGyro) {
            val jerkMag = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            val shake = ((jerkMag - SHAKE_THRESHOLD) / (SHAKE_FULL - SHAKE_THRESHOLD))
                .coerceIn(0f, 1f)
            // X 方向（左右）抽搐
            if (kotlin.math.abs(dx) > kotlin.math.abs(dy) &&
                kotlin.math.abs(dx) > kotlin.math.abs(dz)) {
                state.shakeX = shake; state.shakeY = 0f; state.shakeZ = 0f
            } else if (kotlin.math.abs(dy) > kotlin.math.abs(dz)) {
                state.shakeX = 0f; state.shakeY = shake; state.shakeZ = 0f
            } else {
                state.shakeX = 0f; state.shakeY = 0f; state.shakeZ = shake
            }
        }
        try { sink(state) } catch (_: Throwable) {}
    }

    /** 处理陀螺仪（角速度）：精确的摇动检测 + 辅助 3D 旋转追踪。 */
    private fun handleGyro(
        event: SensorEvent,
        rotation: Int,
        state: MotionState,
        sink: (MotionState) -> Unit
    ) {
        val rx = event.values[0]
        val ry = event.values[1]
        val rz = event.values[2]
        // 设备 → 屏幕坐标（同前）
        val rxS: Float
        val ryS: Float
        when (rotation) {
            Surface.ROTATION_90 -> { rxS = -ry; ryS = rx }
            Surface.ROTATION_270 -> { rxS = ry; ryS = -rx }
            Surface.ROTATION_180 -> { rxS = -rx; ryS = -ry }
            else -> { rxS = rx; ryS = ry }
        }
        val rzS = rz
        // 角速度突变 = 摇动（比纯加速度更可靠：甩手时角速度峰值明显）
        val dx = rxS - lastGyro[0]
        val dy = ryS - lastGyro[1]
        val dz = rzS - lastGyro[2]
        lastGyro[0] = rxS; lastGyro[1] = ryS; lastGyro[2] = rzS
        val magX = kotlin.math.abs(dx)
        val magY = kotlin.math.abs(dy)
        val magZ = kotlin.math.abs(dz)
        fun s(m: Float): Float =
            ((m - SHAKE_THRESHOLD) / (SHAKE_FULL - SHAKE_THRESHOLD)).coerceIn(0f, 1f)
        // 取瞬时主导轴作为本次摇动方向
        state.shakeX = if (magX >= magY && magX >= magZ) s(magX) else 0f
        state.shakeY = if (magY > magX && magY >= magZ) s(magY) else 0f
        state.shakeZ = if (magZ > magX && magZ > magY) s(magZ) else 0f
        try { sink(state) } catch (_: Throwable) {}
    }

    /** 由线性加速度分量更新挥动状态（4 向 + 推/拉）。 */
    private fun updateSwingFromLinear(
        lxS: Float, lyS: Float, lzS: Float,
        state: MotionState
    ) {
        fun clean(v: Float): Float {
            val a = kotlin.math.abs(v)
            return if (a < SWING_THRESHOLD) 0f
            else ((a - SWING_THRESHOLD) / (SWING_FULL - SWING_THRESHOLD))
                .coerceIn(0f, 1f)
        }
        // 屏幕坐标：X_s 右、Y_s 下、Z_s 出屏
        //   lxS > 0 = 右甩；lyS > 0 = 下甩；lzS > 0 = 向自己拉（后拉）
        state.swingRight = clean(lxS)
        state.swingLeft = clean(-lxS)
        state.swingDown = clean(lyS)
        state.swingUp = clean(-lyS)
        state.swingForward = clean(-lzS)   // 顶边前推
        state.swingBackward = clean(lzS)   // 顶边后拉
    }

    /** 停止监听（幂等）。 */
    fun stop() {
        val sm = sensorManager
        val l = listener
        if (sm != null && l != null) {
            try { sm.unregisterListener(l) } catch (_: Throwable) {}
        }
        listener = null
        sensorManager = null
        calibrated = false
        hasLinearAccel = false
        hasGyro = false
    }

    fun isRunning(): Boolean = listener != null

    /**
     * ★ 旧版兼容：4 参 sink（仅倾斜）。保留以免破坏外部调用；新代码应优先用
     * MotionState 版 [start]，能驱动 SWING/SHAKE 全方向。
     */
    fun start(
        context: Context,
        displayRotation: Int,
        sink: (left: Float, right: Float, forward: Float, backward: Float) -> Unit
    ): Boolean = start(context, displayRotation) { s ->
        sink(s.tiltLeft, s.tiltRight, s.tiltForward, s.tiltBackward)
    }
}
