package com.nesstation.app.core.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * ★★★ V3 重写（本轮，回应用户"wii的手机体感模拟方向不对…手持手机面向自己的，
 *   也就是横向竖放或者竖向竖放，并不是平放在桌面进行体感而且左右倾斜
 *   上下晃动自己前后晃动都即可生效，灵敏度要高，特别是左右倾斜，
 *   一定要注意不是按钮而且往左倾斜不回正就别停止"）★★★
 *
 * V2 及更早版本的三处根因（全部在本轮根治）：
 *  1.【单位错】FULL_TILT_G=0.26 注释称"≈15°"，但 Android 加速度计单位
 *    是 m/s²（静止时 ≈9.81，代码 STATIONARY 检查也以此为证）—— 0.26
 *    m/s² 对应仅 ~1.6° 就满程！输出瞬间饱和成 0/1 开关 → 手部微小
 *    姿态漂移即永久占满某个方向（"像按钮"、方向乱、基准冻结后
 *    反向打不出）——用户描述的全部症状都由此而来。
 *  2.【方向错】旧模型按"平放桌面"设计（倾角分量制），而用户是
 *    【面向自己竖持】：推导实证（右手系，绕屏幕法向 +Z 旋转）——
 *    手机顶部向左倾 = +θ → gx>0，旧代码却把 dX>0 标为"右倾"；
 *    顶部远离自己（前倾）= gz>0，旧代码却把 dZ>0 标为"后仰"；
 *    旋转表实际给出 Y_s 向上（ROTATION_90: Y_s=+deviceX=世界上），
 *    注释误标"Y_s 下"导致上下挥动反向。
 *  3.【模型错】分量差模型在竖持姿态下饱和特性差；改为【角度制】：
 *    roll = atan2(gx, gy)（屏幕平面内重力方向角，左倾为正），
 *    pitch = gz/9.81（前后倚俯分量，顶部远离自己为正）。
 *    保持倾斜 = 保持输出（角度相对基准恒定）→ "往左倾斜不回正
 *    就别停止"；静止重校准有门控（四向输出 < 0.10 才重采），
 *    持续倾斜期间基准绝不追赶。
 *
 * 屏幕坐标系（旋转表实证：X_s 右、Y_s 上、Z_s 出屏朝用户）：
 *   - 左右倾斜（方向盘式，绕 Z_s）：roll > 0 = 左倾 → tiltLeft
 *   - 前后倚俯（顶部推离/拉近）：pitchFrac > 0 = 前倾 → tiltForward
 *   - 上下挥动：线性加速度 +Y_s = 上挥 → swingUp
 *   - 前后推拉：线性加速度 -Z_s = 向前推 → swingForward
 */
object WiiMotionSensors {

    /** ★★ v1.4 全局灵敏度增益（设置面板「体感灵敏度」写入，默认 1.6 高）：
     *   1.0 = 基准；>1 更灵敏（满程所需倾角/阈值按比例缩小）。存
     *   PadLayoutStore.wiiMotionSensitivity，在各项标尺计算处应用。 */
    @Volatile var sensitivityGain: Float = 1.6f

    /** ★★★ V3：左右倾斜（方向盘式 roll）满程角度（度）。
     *   角度制 —— 相对基准（开启时握持角）的偏转。基准 ≈15° 即满程；
     *   增益在 clean() 内除（1.6 默认 → ~9° 满程，轻倾即大幅输出，
     *   回应"灵敏度要高，特别是左右倾斜"）。 */
    private const val FULL_ROLL_DEG = 14.0f

    /** ★★★ V3：前后倚俯（pitch）满程（重力分量比，sin θ）。
     *   0.28 ≈ 16°；增益同除。*/
    private const val FULL_PITCH_FRAC = 0.28f

    /** 死区：roll 角度制（度）——轻微手抖不触发。 */
    private const val DEADZONE_ROLL_DEG = 1.0f

    /** 死区：pitch 分量比（≈0.9°）。 */
    private const val DEADZONE_PITCH = 0.015f

    /** ★ 增益下限保护：倾斜满程最低值（防增益过高时标尺退化/噪声满幅）。 */
    private const val FULL_ROLL_MIN_DEG = 7.0f
    private const val FULL_PITCH_MIN = 0.14f

    /** ★ 近平放抑制：屏幕平面内重力 < 此值（m/s²）时 roll 不可信
     *   （手机平放桌面姿态，非用户约定的竖持模型）—— 按平面内重力
     *   幅值 2..5 m/s² 线性淡出 roll 输出。 */
    private const val ROLL_VALID_MAG = 2.0f
    private const val ROLL_FULL_MAG = 5.0f

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

    /** 基准姿态（V3 角度制）：[0] = 基准 roll（度，屏幕平面内重力角），
     *  [1] = 基准 pitch 分量比（gz/9.81），[2] = 基准平面内重力幅值（诊断用）。 */
    private val baseGravity = FloatArray(3)

    /** 低通滤波后的重力（屏幕坐标系：X_s 右、Y_s 上、Z_s 出屏）。 */
    private val smoothed = FloatArray(3)

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

    /**
     * 处理加速度计：提取重力（低通）→ 角度制算倾斜（V3 面向用户竖持模型）。
     *
     * 屏幕坐标系（旋转表实证，X_s 右、Y_s 上、Z_s 出屏朝用户）：
     *   - 左右倾斜（方向盘，绕 Z_s）：顶部向左 = 屏幕平面重力角 +θ
     *     （gx_s > 0）→ rollDev > 0 = 左倾 → tiltLeft
     *   - 前后倚俯：顶部推离自己 = gz_s > 0 → pitchDev > 0 = 前倾 → tiltForward
     *   （旧版按"平放桌面"模型标注，左右/前后/上下三处方向全反，本轮修正）
     */
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
        // ★ 低通重力估计：alpha 0.5（50Hz 传感器 ≈ 40ms 响应）—— 方向盘式
        //   转向对延迟敏感，轻倾即跟手（回应"灵敏度要高，特别是左右倾斜"）。
        val alpha = 0.5f
        smoothed[0] = alpha * gxS + (1 - alpha) * smoothed[0]
        smoothed[1] = alpha * gyS + (1 - alpha) * smoothed[1]
        smoothed[2] = alpha * gzS + (1 - alpha) * smoothed[2]
        // ★★ 基线收敛：启动后先积累 CALIB_SAMPLES 个原始样本求均值，
        //   期间不输出任何倾斜 —— 防开局滤波器未收敛导致假性满幅。
        if (!calibrated) {
            calibSum[0] += gxS; calibSum[1] += gyS; calibSum[2] += gzS
            calibCount++
            if (calibCount < CALIB_SAMPLES) {
                return   // 未收敛：不输出、不参与重校准
            }
            val meanX = calibSum[0] / calibCount
            val meanY = calibSum[1] / calibCount
            val meanZ = calibSum[2] / calibCount
            // V3：基准 = 开启时的握持角（角度制，用户自然握姿即可，
            //   不必绝对竖直）；[0]=基准 roll（度），[1]=基准 pitch 分量比，
            //   [2]=平面内重力幅值（诊断）。
            baseGravity[0] = rollDegOf(meanX, meanY)
            baseGravity[1] = meanZ / 9.81f
            baseGravity[2] = kotlin.math.sqrt(meanX * meanX + meanY * meanY)
            calibrated = true
            stationarySinceMs = 0L
            // 收敛后首个事件：把滤波器预热到真实重力，避免首拍跳变
            smoothed[0] = meanX
            smoothed[1] = meanY
            smoothed[2] = meanZ
            return
        }
        // ===== V3 角度制倾斜（面向用户竖持模型，推导见文件头）=====
        // 左右倾斜（方向盘式 roll）：屏幕平面内重力角，顶部向左 = 正
        val inPlaneMag = kotlin.math.sqrt(
            smoothed[0] * smoothed[0] + smoothed[1] * smoothed[1])
        val rollNow = rollDegOf(smoothed[0], smoothed[1])
        var rollDev = rollNow - baseGravity[0]
        if (rollDev > 180f) rollDev -= 360f
        if (rollDev < -180f) rollDev += 360f
        // 近平放（屏幕朝上/下）时平面内重力太小，roll 角不可信 → 线性淡出
        val rollFade = ((inPlaneMag - ROLL_VALID_MAG) /
            (ROLL_FULL_MAG - ROLL_VALID_MAG)).coerceIn(0f, 1f)
        // ★ v3：满程角度除以灵敏度增益（1.6 默认 → ~9° 满程，轻倾即大幅
        //   输出），满程钳位 ≥ FULL_ROLL_MIN_DEG 防标尺退化。
        fun cleanRoll(devDeg: Float): Float {
            val a = kotlin.math.abs(devDeg)
            val full = (FULL_ROLL_DEG / sensitivityGain).coerceAtLeast(FULL_ROLL_MIN_DEG)
            val v = if (a < DEADZONE_ROLL_DEG) 0f
            else ((a - DEADZONE_ROLL_DEG) / (full - DEADZONE_ROLL_DEG)).coerceIn(0f, 1f)
            return v * rollFade
        }
        val tL = cleanRoll(rollDev)     // rollDev > 0 = 左倾（V3 推导修正）
        val tR = cleanRoll(-rollDev)

        // 前后倚俯（pitch 分量比）：顶部推离自己 = 正 = 前倾（V3 修正）
        val pitchNow = smoothed[2] / 9.81f
        val pitchDev = pitchNow - baseGravity[1]
        fun cleanPitch(v: Float): Float {
            val a = kotlin.math.abs(v)
            val full = (FULL_PITCH_FRAC / sensitivityGain).coerceAtLeast(FULL_PITCH_MIN)
            return if (a < DEADZONE_PITCH) 0f
            else ((a - DEADZONE_PITCH) / (full - DEADZONE_PITCH)).coerceIn(0f, 1f)
        }
        val tF = cleanPitch(pitchDev)
        val tB = cleanPitch(-pitchDev)
        state.tiltRight = tR
        state.tiltLeft = tL
        state.tiltForward = tF
        state.tiltBackward = tB
        // ★★ 静止重校准（防漂移）：
        //   1) 加速度接近纯重力（总幅值 ≈ 9.8±0.35）且持续 ≥ RECENTER_HOLD_MS；
        //   2) 门控：当前四向输出全部 < RECENTER_MAX_OUTPUT ——
        //      用户正在保持倾斜时绝不重采基准（角度制下保持倾斜 =
        //      保持输出，"往左倾斜不回正就别停止"的保证）；
        //   3) 重采 = 以当前角度为新基准。
        val mag = kotlin.math.sqrt(gxS * gxS + gyS * gyS + gzS * gzS)
        val nearStationary = kotlin.math.abs(mag - 9.81f) < STATIONARY_ACCEL
        if (nearStationary && tL < RECENTER_MAX_OUTPUT && tR < RECENTER_MAX_OUTPUT &&
            tF < RECENTER_MAX_OUTPUT && tB < RECENTER_MAX_OUTPUT) {
            val now = android.os.SystemClock.uptimeMillis()
            if (stationarySinceMs == 0L) stationarySinceMs = now
            if (now - stationarySinceMs >= RECENTER_HOLD_MS) {
                baseGravity[0] = rollNow
                baseGravity[1] = pitchNow
                baseGravity[2] = inPlaneMag
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

    /** 屏幕平面内重力方向角（度）。竖持时 ≈0；顶部向左倾为正（V3 推导）。 */
    private fun rollDegOf(gxS: Float, gyS: Float): Float =
        Math.toDegrees(kotlin.math.atan2(gxS, gyS).toDouble()).toFloat()

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

    /**
     * 由线性加速度分量更新挥动状态（4 向 + 推/拉）。
     * ★★ V3 方向修正：旋转表实证 Y_s 向上（非旧注释误标的"Y_s 下"）——
     *   lyS > 0 = 向上甩（加速度沿 +Y_s）→ swingUp；旧实现上下颠倒。
     *   lzS > 0 = 向自己拉（Z_s 出屏朝用户）→ swingBackward（不变）。
     */
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
        // 屏幕坐标（V3 实证）：X_s 右、Y_s 上、Z_s 出屏朝用户
        //   lxS > 0 = 右甩；lyS > 0 = 上甩；lzS > 0 = 向自己拉
        state.swingRight = clean(lxS)
        state.swingLeft = clean(-lxS)
        state.swingUp = clean(lyS)       // ★ V3：Y_s 向上，旧实现颠倒
        state.swingDown = clean(-lyS)
        state.swingForward = cleanFbSwing(-lzS)   // 向前推（远离自己）
        state.swingBackward = cleanFbSwing(lzS)   // 向自己拉
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
