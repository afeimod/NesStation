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

    /** ★★ v1.4 全局灵敏度增益（本轮新增，回应用户"需要使劲摇手机才有
     *   反应，轻轻的左右前后上下都不能生效"）：
     *   1.0 = 基准灵敏度；>1 更灵敏（满程所需幅度/阈值按比例缩小）。
     *   由设置面板「体感灵敏度」写入（默认 1.6 高），存
     *   PadLayoutStore.wiiMotionSensitivity。增益在各项标尺计算处
     *   应用（满程/阈值除以增益），并对增益钳位防止退化（满程永不低于
     *   FULL_TILT_MIN / 阈值永不低于 SWING/SHAKE_THRESHOLD_MIN）。 */
    @Volatile var sensitivityGain: Float = 1.6f

    /** 倾斜满强度对应的重力分量变化（v1.4 基准 0.26 ≈ 15° 即满程 ——
     *   v1.3 的 0.42（25°）实测"轻倾无反应"；增益在 clean() 内除）。 */
    private const val FULL_TILT_G = 0.26f

    /** ★★ v1.4 前后轴独立满程（基准 ≈ 10° 满程，v1.3 的 0.30 同步下调；
     *   手腕俯仰的自然幅度仍小于尺桡偏，保持独立标尺）。 */
    private const val FULL_TILT_FB_G = 0.18f

    /** 死区（v1.4 再降：0.02 —— 轻微倾摆即出值）。 */
    private const val DEADZONE = 0.02f

    /** ★ 增益下限保护：倾斜满程最低值（防增益过高时标尺退化/噪声满幅）。 */
    private const val FULL_TILT_MIN = 0.10f

    /** ★★ v1.3 前后轴快通道滤波系数：前后"晃动"是 100ms 级快速动态动作，
     *   慢通道 alpha=0.45 会把脉冲峰值削掉约一半（左右"倾斜"是静态保持，
     *   滤波无衰减）—— 同样动作幅度下前后读数只有左右的一半，这是
     *   "前后不灵敏"的滤波根源。快通道 0.70（响应 ~30ms）与慢通道
     *   取大者：静态倾斜由慢通道主导（抗噪），动态晃动由快通道主导（保峰）。 */
    private const val ALPHA_FB_FAST = 0.70f

    /** 挥动触发阈值（线性加速度幅值，m/s²）。v1.4 基准 0.8（v1.3 的 1.5
     *   实测"轻甩无反应"）；增益在 clean() 内除。 */
    private const val SWING_THRESHOLD = 0.8f

    /** 挥动满强度阈值（线性加速度幅值；v1.4 基准 3.5，原 6.0）。 */
    private const val SWING_FULL = 3.5f

    /** ★★ v1.3 推/拉（前后晃动的线性加速度分量）独立阈值：手腕俯仰甩动
     *   在 Z 轴（出屏方向）产生的线性加速度峰值天然低于整臂挥动。
     *   v1.4 基准同步下调（0.55/2.2，原 1.0/4.0）。 */
    private const val SWING_FB_THRESHOLD = 0.55f
    private const val SWING_FB_FULL = 2.2f

    /** 摇晃触发阈值（瞬时角速度变化，rad/s；v1.4 基准 2.2，原 4.0）。 */
    private const val SHAKE_THRESHOLD = 2.2f

    /** 摇晃满强度阈值（v1.4 基准 7.0，原 12.0）。 */
    private const val SHAKE_FULL = 7.0f

    /** ★ 增益下限保护：挥动/摇晃阈值最低值（防高增益时噪声触发）。 */
    private const val SWING_THRESHOLD_MIN = 0.35f
    private const val SHAKE_THRESHOLD_MIN = 0.8f

    /** ★ 静止重校准：判定"近静止"的角速度上限（rad/s）。 */
    private const val STATIONARY_GYRO = 0.06f

    /** ★ 静止重校准：判定"近静止"的线性加速度幅值上限（m/s²）。 */
    private const val STATIONARY_ACCEL = 0.35f

    /** ★ 静止重校准：需持续静止的时长（ms）后重新采集基准重力。
     *   防漂移（用户反馈"左右倾斜有时候会失灵"的成因之一：开启时基准被
     *   手部动作污染 / 持握角度变化后旧基准不再代表中性位 → 偏移越积越大
     *   → 某一方向被永久占满死区外的幅度，反向达不到阈值 = 失灵）。 */
    private const val RECENTER_HOLD_MS = 600L

    /** ★★ 基线收敛采样数（本轮根治）：启动后先积累 K 个原始样本求均值
     *   作为基准，期间不输出任何倾斜 —— 旧实现第 1 个事件就用只滤了一拍
     *   （alpha=0.45，从 0 起步）的 smoothed 当基准，滤波器收敛期间
     *   smoothed-base 漂移高达 0.55g > FULL_TILT_G(0.42) → 开局 0.5~1s
     *   假性满幅倾斜把游戏输入饱和占死（"按钮按好久才生效"的根因）。 */
    private const val CALIB_SAMPLES = 12

    /** ★★ 重校准门控：当前四向倾斜输出全部低于此值才允许重采基准 ——
     *   防止基准“追赶”用户正在保持的倾斜（旧实现只要静止 600ms 就重采，
     *   用户持续左倾时基准被拉向左倾 → 输出逐渐衰减 → "偶尔失效"）。 */
    private const val RECENTER_MAX_OUTPUT = 0.10f

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

    /** ★★ v1.3 前后轴快通道滤波状态（仅 Z 分量，见 ALPHA_FB_FAST 注释）。 */
    private val smoothedFast = FloatArray(3)

    /** 上一帧线性加速度（用于摇动检测的导数计算）。 */
    private val lastLinAccel = FloatArray(3)

    /** 上一帧陀螺角速度（用于摇动检测的导数计算）。 */
    private val lastGyro = FloatArray(3)

    /** ★ 静止重校准状态：近静止持续时长（计时起点 ms）。 */
    private var stationarySinceMs = 0L

    /** ★★ 基线收敛状态：已积累的原始样本数与累计和（本轮根治）。 */
    private var calibCount = 0
    private val calibSum = FloatArray(3)

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
        calibCount = 0
        calibSum.fill(0f)
        hasLinearAccel = linAcc != null
        hasGyro = gyro != null
        smoothed.fill(0f)
        smoothedFast.fill(0f)
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
        // ★ 调优：alpha 0.45（旧版 0.35）→ 响应延迟 ~60ms，进一步降低
        //   "偶尔生效"感；仍保留足够滤波抑制手抖噪声。
        val alpha = 0.45f
        smoothed[0] = alpha * gxS + (1 - alpha) * smoothed[0]
        smoothed[1] = alpha * gyS + (1 - alpha) * smoothed[1]
        smoothed[2] = alpha * gzS + (1 - alpha) * smoothed[2]
        // ★★ v1.3 前后快通道（见 ALPHA_FB_FAST 注释）
        smoothedFast[0] = ALPHA_FB_FAST * gxS + (1 - ALPHA_FB_FAST) * smoothedFast[0]
        smoothedFast[1] = ALPHA_FB_FAST * gyS + (1 - ALPHA_FB_FAST) * smoothedFast[1]
        smoothedFast[2] = ALPHA_FB_FAST * gzS + (1 - ALPHA_FB_FAST) * smoothedFast[2]
        // ★★ 基线收敛（本轮根治）：启动后先积累 CALIB_SAMPLES 个原始样本
        //   求均值作为基准，期间不输出任何倾斜 —— 旧实现第 1 个事件就把
        //   未收敛的 smoothed（从 0 起步只滤了一拍 ≈ 0.45g）当基准，
        //   收敛期间 smoothed-base 漂移高达 0.55g > FULL_TILT_G →
        //   开局假性满幅倾斜，把游戏输入饱和占死（按钮“按好久才生效”
        //   与传感器假倾斜与按钮对向对消（“偶尔失效”）的双重根源）。
        if (!calibrated) {
            calibSum[0] += gxS; calibSum[1] += gyS; calibSum[2] += gzS
            calibCount++
            if (calibCount < CALIB_SAMPLES) {
                return   // 未收敛：不输出、不参与重校准
            }
            baseGravity[0] = calibSum[0] / calibCount
            baseGravity[1] = calibSum[1] / calibCount
            baseGravity[2] = calibSum[2] / calibCount
            calibrated = true
            stationarySinceMs = 0L
            // 收敛后首个事件：把滤波器也预热到真实重力，避免首拍跳变
            smoothed[0] = baseGravity[0]
            smoothed[1] = baseGravity[1]
            smoothed[2] = baseGravity[2]
            smoothedFast[0] = baseGravity[0]
            smoothedFast[1] = baseGravity[1]
            smoothedFast[2] = baseGravity[2]
            return
        }
        // 倾斜：相对基准的重力分量差
        // ★ v1.4：满程除以灵敏度增益（gain≥1 → 满程所需倾角更小），
        //   满程钳位 ≥ FULL_TILT_MIN 防标尺退化。
        fun clean(v: Float): Float {
            val a = kotlin.math.abs(v)
            val full = (FULL_TILT_G / sensitivityGain).coerceAtLeast(FULL_TILT_MIN)
            return if (a < DEADZONE) 0f
            else ((a - DEADZONE) / (full - DEADZONE)).coerceIn(0f, 1f)
        }
        // ★★ v1.3 前后轴：独立满程（FULL_TILT_FB_G）+ 快慢双通道取大。
        //   慢通道主导静态倾斜（抗噪），快通道保住动态晃动的峰值
        //   （0.45 低通对 100ms 脉冲削峰 ~50%，是"前后不灵敏"主因）。
        //   v1.4：同除增益（前后轴是"轻晃不生效"重灾区）。
        fun cleanFb(v: Float): Float {
            val a = kotlin.math.abs(v)
            val full = (FULL_TILT_FB_G / sensitivityGain).coerceAtLeast(FULL_TILT_MIN)
            return if (a < DEADZONE) 0f
            else ((a - DEADZONE) / (full - DEADZONE)).coerceIn(0f, 1f)
        }
        val dX = smoothed[0] - baseGravity[0]   // >0 = 右倾
        val dZ = smoothed[2] - baseGravity[2]   // <0 = 前倾（顶边前推）
        val dZf = smoothedFast[2] - baseGravity[2]   // 快通道（前后专用）
        val tR = clean(dX)
        val tL = clean(-dX)
        val tF = maxOf(cleanFb(-dZ), cleanFb(-dZf))
        val tB = maxOf(cleanFb(dZ), cleanFb(dZf))
        state.tiltRight = tR
        state.tiltLeft = tL
        state.tiltForward = tF
        state.tiltBackward = tB
        // ★★ 静止重校准（防漂移，本轮加固）：
        //   1) 加速度接近纯重力（总幅值 ≈ 9.8±0.35）且持续 ≥ RECENTER_HOLD_MS；
        //   2) ★ 新增门控：当前四向输出全部 < RECENTER_MAX_OUTPUT ——
        //      用户正在保持倾斜时绝不重采基准（防基准追赶导致的
        //      “持续倾斜逐渐衰减/反向失灵”）；
        //   3) ★ 新增缓冲：重采后 500ms 内不再次重采（防高频抖动）。
        val mag = kotlin.math.sqrt(gxS * gxS + gyS * gyS + gzS * gzS)
        val nearStationary = kotlin.math.abs(mag - 9.81f) < STATIONARY_ACCEL
        if (nearStationary && tL < RECENTER_MAX_OUTPUT && tR < RECENTER_MAX_OUTPUT &&
            tF < RECENTER_MAX_OUTPUT && tB < RECENTER_MAX_OUTPUT) {
            val now = android.os.SystemClock.uptimeMillis()
            if (stationarySinceMs == 0L) stationarySinceMs = now
            if (now - stationarySinceMs >= RECENTER_HOLD_MS) {
                baseGravity[0] = smoothed[0]
                baseGravity[1] = smoothed[1]
                baseGravity[2] = smoothed[2]
                stationarySinceMs = now
            }
        } else {
            stationarySinceMs = 0L
        }

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
            // ★ v1.4：摇晃阈值同除灵敏度增益（钳位 ≥ SHAKE_THRESHOLD_MIN）
            val th = (SHAKE_THRESHOLD / sensitivityGain).coerceAtLeast(SHAKE_THRESHOLD_MIN)
            val full = (SHAKE_FULL / sensitivityGain).coerceAtLeast(th * 2f)
            val shake = ((jerkMag - th) / (full - th))
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
        // ★ 陀螺近静止检测也参与重校准门控（角速度低 = 设备真静止，
        //   不只是加速度碰巧接近 g）。
        val gyroMag = kotlin.math.sqrt(rxS * rxS + ryS * ryS + rzS * rzS)
        if (gyroMag > STATIONARY_GYRO) stationarySinceMs = 0L
        // 角速度突变 = 摇动（比纯加速度更可靠：甩手时角速度峰值明显）
        val dx = rxS - lastGyro[0]
        val dy = ryS - lastGyro[1]
        val dz = rzS - lastGyro[2]
        lastGyro[0] = rxS; lastGyro[1] = ryS; lastGyro[2] = rzS
        val magX = kotlin.math.abs(dx)
        val magY = kotlin.math.abs(dy)
        val magZ = kotlin.math.abs(dz)
        fun s(m: Float): Float {
            // ★ v1.4：摇晃阈值同除灵敏度增益（钳位 ≥ SHAKE_THRESHOLD_MIN）
            val th = (SHAKE_THRESHOLD / sensitivityGain).coerceAtLeast(SHAKE_THRESHOLD_MIN)
            val full = (SHAKE_FULL / sensitivityGain).coerceAtLeast(th * 2f)
            return ((m - th) / (full - th)).coerceIn(0f, 1f)
        }
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
            // ★ v1.4：挥动阈值/满程同除灵敏度增益（"轻甩无反应"根治）
            val th = (SWING_THRESHOLD / sensitivityGain).coerceAtLeast(SWING_THRESHOLD_MIN)
            val full = (SWING_FULL / sensitivityGain).coerceAtLeast(th * 2f)
            return if (a < th) 0f
            else ((a - th) / (full - th))
                .coerceIn(0f, 1f)
        }
        // 屏幕坐标：X_s 右、Y_s 下、Z_s 出屏
        //   lxS > 0 = 右甩；lyS > 0 = 下甩；lzS > 0 = 向自己拉（后拉）
        // ★★ v1.3：F/B（推/拉）用独立低阈值（SWING_FB_*），并对消重力泄漏 ——
        //   手腕俯仰时 TYPE_LINEAR_ACCELERATION 的 Z 轴常混入重力分量残留，
        //   只取比慢通道重力估计大的部分（正值化），进一步降噪。
        state.swingRight = clean(lxS)
        state.swingLeft = clean(-lxS)
        state.swingDown = clean(lyS)
        state.swingUp = clean(-lyS)
        state.swingForward = cleanFbSwing(-lzS)   // 顶边前推
        state.swingBackward = cleanFbSwing(lzS)   // 顶边后拉
    }

    /** ★★ v1.3 推/拉独立标尺（见 SWING_FB_THRESHOLD 注释）。
     *  v1.4：同除灵敏度增益。 */
    private fun cleanFbSwing(v: Float): Float {
        val a = kotlin.math.abs(v)
        val th = (SWING_FB_THRESHOLD / sensitivityGain).coerceAtLeast(SWING_THRESHOLD_MIN * 0.6f)
        val full = (SWING_FB_FULL / sensitivityGain).coerceAtLeast(th * 2f)
        return if (a < th) 0f
        else ((a - th) / (full - th)).coerceIn(0f, 1f)
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
        calibCount = 0
        calibSum.fill(0f)
        smoothed.fill(0f)
        smoothedFast.fill(0f)
        hasLinearAccel = false
        hasGyro = false
        stationarySinceMs = 0L
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
    ): Boolean = start(context, displayRotation) { s: MotionState ->
        // ★ 显式标注 lambda 参数类型为 MotionState，确保 Kotlin 重载解析唯一指向
        //   上方 (MotionState) -> Unit 版 start，避免与 (Float, Float, Float, Float) -> Unit
        //   版产生歧义（4 参 vs 1 参按 arity 已可区分，但显式标注更稳）。
        sink(s.tiltLeft, s.tiltRight, s.tiltForward, s.tiltBackward)
    }
}
