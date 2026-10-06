package com.nesstation.app.core.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * ★★★★ V4 重写（本轮，回应用户"wii手机体感模拟还是错乱，方向都不对，
 *   应该把手机横放为中心点(非平放)"）★★★★
 *
 * V3 及更早的核心缺陷（本轮根治）：
 *  1.【基准姿态错】V3 把【开启瞬间的握持角】当作 roll/pitch 基准。但体感
 *    开启时机在游戏启动时 —— 用户往往还在竖持（加载画面）或手机平放在
 *    桌上/腿上：竖持基准与横屏游玩姿态差 90° → 进游戏后 roll 恒满幅单侧
 *    输出；平放基准则 pitch=1.0 → 恒满幅后仰。且 V3 的重校准门控（输出
 *    全部 < 0.10 才允许重采）被饱和输出永久阻塞 → 基准永远纠不回来 ——
 *    这就是"方向都不对、错乱"的真正根因（用户强调"非平放"正是指此）。
 *  2.【V4 模型 —— 横放为固定中心点】：
 *    ★ roll（左右倾斜/方向盘）：**不再采样基准，固定锚定横放姿态** ——
 *      屏幕竖直面向自己、重力沿 -Y_s（rollDeg = 180°）。手机横着水平
 *      拿 = 中性（零输出），像真实方向盘：往左转 = 左输出，保持不回正
 *      = 保持输出。任何启动姿态（竖持/平放）都不影响 —— 横屏游玩时
 *      显示旋转已把重力换算到屏幕坐标，180° 锚点自动成立。
 *    ★ pitch（前后倚俯）：保留握持校准（相对基准）—— 手臂自然后仰角
 *      人人不同（绝对零基准会把 ~20-40° 的自然握持后仰当成满幅后倾，
 *      恰好复刻"平放为中心"的错误）。竖持/横持的 gz 分量基准几乎一致，
 *      启动姿态对 pitch 基准无污染。
 *    ★ 静止重校准：只重采 pitch 基准（带输出门控）；roll 恒定锚点 180°
 *      绝不漂移 —— "把手机横放为中心点"的语义。
 *
 * 屏幕坐标系（旋转表实证：X_s 右、Y_s 上、Z_s 出屏朝用户）：
 *   - 左右倾斜（方向盘式，绕 Z_s）：向左转 → rollDev > 0 → tiltLeft
 *   - 前后倚俯（顶部推离/拉近）：pitchFrac > 0 = 前倾 → tiltForward
 *   - 上下挥动：线性加速度 +Y_s = 上挥 → swingUp
 *   - 前后推拉：线性加速度 -Z_s = 向前推 → swingForward
 */
object WiiMotionSensors {

    /** ★★ v1.4 全局灵敏度增益（设置面板「体感灵敏度」写入，默认 1.6 高）：
     *   1.0 = 基准；>1 更灵敏（满程所需倾角/阈值按比例缩小）。存
     *   PadLayoutStore.wiiMotionSensitivity，在各项标尺计算处应用。 */
    @Volatile var sensitivityGain: Float = 1.6f

    /** ★★★ V4：左右倾斜（方向盘式 roll）满程角度（度）。
     *   相对【固定横放锚点 180°】的偏转（见 LANDSCAPE_ROLL_ANCHOR_DEG）。
     *   基准 ≈14° 即满程；增益在 clean() 内除（1.6 默认 → ~9° 满程，轻倾即
     *   大幅输出，回应"灵敏度要高，特别是左右倾斜"）。 */
    private const val FULL_ROLL_DEG = 14.0f

    /** ★★★ V3：前后倚俯（pitch）满程（重力分量比，sin θ）。
     *   0.28 ≈ 16°；增益同除。*/
    private const val FULL_PITCH_FRAC = 0.28f

    /** 死区：roll 角度制（度）——轻微手抖不触发。
     *   ★ V4 固定锚点下，用户自然握持可能带几度小倾斜 —— 死区放宽到 2.5°，
     *   兼容"横着水平拿 = 中性"与手腕自然误差。 */
    private const val DEADZONE_ROLL_DEG = 2.5f

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

    /** ★★ 基线收敛采样数：启动后先积累 K 个原始样本求均值作 pitch 基准，
     *   期间不输出前后倾斜（V4：roll 已固定锚点，启动即输出，不受此门影响）。 */
    private const val CALIB_SAMPLES = 12

    /** ★★ 重校准门控（V4：仅 pitch）：当前前后倾斜输出全部低于此值才允许
     *   重采 pitch 基准 —— 防止基准“追赶”用户正在保持的倾斜（旧实现只要
     *   静止 600ms 就重采，用户持续前倾时基准被拉向前倾 → 输出逐渐衰减
     *   → "偶尔失效"）。roll 锚点固定，永不参与重校准。 */
    private const val RECENTER_MAX_OUTPUT = 0.10f

    /** ★★★ V4 固定 roll 锚点：横放水平姿态（屏幕竖直面向自己）时屏幕平面内
     *   重力沿 -Y_s → rollDegOf(0, -9.81) = 180°。这就是"把手机横放为中心
     *   点(非平放)"。不随启动姿态/静止重校准漂移 —— 启动时竖持/平放都
     *   不会污染中心点。 */
    private const val LANDSCAPE_ROLL_ANCHOR_DEG = 180.0f

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

    /** 基准姿态（V4）：[0] = pitch 基准分量比（gz/9.81，握持后仰角），
     *  [1] = 诊断用平面内重力幅值。roll 基准 = 固定横放锚点
     *  （[LANDSCAPE_ROLL_ANCHOR_DEG]），不在此存。 */
    private val baseGravity = FloatArray(2)

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
     * 处理加速度计：提取重力（低通）→ 角度制算倾斜（V4 横放固定中心点模型）。
     *
     * 屏幕坐标系（旋转表实证，X_s 右、Y_s 上、Z_s 出屏朝用户）：
     *   - 左右倾斜（方向盘，绕 Z_s）：向左转 = 屏幕平面重力角相对横放锚点
     *     +θ → rollDev > 0 = 左倾 → tiltLeft（锚点 = 横放水平 180°，固定）
     *   - 前后倚俯：顶部推离自己 = gz_s > 0 → pitchDev > 0 = 前倾 → tiltForward
     *     （相对握持校准基准，手臂自然后仰自动归零）
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
        //   建立 pitch 基准（握持后仰角）。V4：roll 用固定横放锚点，
        //   **不受此门阻塞**（收敛期间即输出左右倾斜，开局可用）。
        if (!calibrated) {
            calibSum[0] += gxS; calibSum[1] += gyS; calibSum[2] += gzS
            calibCount++
            if (calibCount < CALIB_SAMPLES) {
                emitTilt(state, sink, gxS, gyS, gzS, rollReady = true, pitchReady = false)
                return   // pitch 未收敛：不输出前后倾斜、不参与重校准
            }
            val meanX = calibSum[0] / calibCount
            val meanY = calibSum[1] / calibCount
            val meanZ = calibSum[2] / calibCount
            // V4：pitch 基准 = 开启时的握持后仰角（手臂自然后仰人人不同，
            //   必须相对校准；roll 基准 = 固定横放锚点，见文件头）。
            baseGravity[0] = meanZ / 9.81f
            baseGravity[1] = kotlin.math.sqrt(meanX * meanX + meanY * meanY)
            calibrated = true
            stationarySinceMs = 0L
            // 收敛后首个事件：把滤波器预热到真实重力，避免首拍跳变
            smoothed[0] = meanX
            smoothed[1] = meanY
            smoothed[2] = meanZ
            return
        }
        emitTilt(state, sink, gxS, gyS, gzS, rollReady = true, pitchReady = true)
    }

    /**
     * ★★★ V4 倾斜输出统一路径（handleAccel 每事件调用）：
     *   - roll（左右倾斜）：固定横放锚点 —— 相对"手机横着水平拿、屏幕竖直
     *     面向自己"的姿态偏转。这就是用户要求的"把手机横放为中心点(非平放)"。
     *     启动时无论竖持/平放，进横屏游玩后中心点自动正确。
     *   - pitch（前后倚俯）：相对握持校准基准（[baseGravity[0]]）。
     */
    private fun emitTilt(
        state: MotionState,
        sink: (MotionState) -> Unit,
        rawGxS: Float, rawGyS: Float, rawGzS: Float,
        rollReady: Boolean,
        pitchReady: Boolean
    ) {
        // ===== 左右倾斜（方向盘式 roll，固定横放锚点）=====
        val inPlaneMag = kotlin.math.sqrt(
            smoothed[0] * smoothed[0] + smoothed[1] * smoothed[1])
        val rollNow = rollDegOf(smoothed[0], smoothed[1])
        var rollDev = rollNow - LANDSCAPE_ROLL_ANCHOR_DEG
        if (rollDev > 180f) rollDev -= 360f
        if (rollDev < -180f) rollDev += 360f
        // 近平放（屏幕朝上/下）时平面内重力太小，roll 角不可信 → 线性淡出
        val rollFade = ((inPlaneMag - ROLL_VALID_MAG) /
            (ROLL_FULL_MAG - ROLL_VALID_MAG)).coerceIn(0f, 1f)
        // ★ v1.4：满程角度除以灵敏度增益（1.6 默认 → ~9° 满程，轻倾即大幅
        //   输出），满程钳位 ≥ FULL_ROLL_MIN_DEG 防标尺退化。
        fun cleanRoll(devDeg: Float): Float {
            val a = kotlin.math.abs(devDeg)
            val full = (FULL_ROLL_DEG / sensitivityGain).coerceAtLeast(FULL_ROLL_MIN_DEG)
            val v = if (a < DEADZONE_ROLL_DEG) 0f
            else ((a - DEADZONE_ROLL_DEG) / (full - DEADZONE_ROLL_DEG)).coerceIn(0f, 1f)
            return v * rollFade
        }
        val tL = cleanRoll(rollDev)     // rollDev > 0 = 左倾（屏幕平面重力角推导）
        val tR = cleanRoll(-rollDev)
        state.tiltRight = if (rollReady) tR else 0f
        state.tiltLeft = if (rollReady) tL else 0f

        // ===== 前后倚俯（pitch 分量比，握持校准基准）=====
        val pitchNow = smoothed[2] / 9.81f
        val pitchDev = pitchNow - baseGravity[0]
        fun cleanPitch(v: Float): Float {
            val a = kotlin.math.abs(v)
            val full = (FULL_PITCH_FRAC / sensitivityGain).coerceAtLeast(FULL_PITCH_MIN)
            return if (a < DEADZONE_PITCH) 0f
            else ((a - DEADZONE_PITCH) / (full - DEADZONE_PITCH)).coerceIn(0f, 1f)
        }
        val tF = cleanPitch(pitchDev)
        val tB = cleanPitch(-pitchDev)
        state.tiltForward = if (pitchReady) tF else 0f
        state.tiltBackward = if (pitchReady) tB else 0f

        // ★★ 静止重校准（防漂移，V4：仅 pitch）：
        //   1) 加速度接近纯重力（总幅值 ≈ 9.8±0.35）且持续 ≥ RECENTER_HOLD_MS；
        //   2) 门控：当前前后输出 < RECENTER_MAX_OUTPUT ——
        //      用户正在保持倾斜时绝不重采基准（角度制下保持倾斜 =
        //      保持输出，"往左倾斜不回正就别停止"的保证）；
        //   3) 重采 = 以当前后仰角为新 pitch 基准。
        //      roll 锚点恒为横放 180°，永不漂移。
        if (pitchReady) {
            val mag = kotlin.math.sqrt(
                smoothed[0] * smoothed[0] + smoothed[1] * smoothed[1] +
                smoothed[2] * smoothed[2])
            val nearStationary = kotlin.math.abs(mag - 9.81f) < STATIONARY_ACCEL
            if (nearStationary && tF < RECENTER_MAX_OUTPUT && tB < RECENTER_MAX_OUTPUT) {
                val now = android.os.SystemClock.uptimeMillis()
                if (stationarySinceMs == 0L) stationarySinceMs = now
                if (now - stationarySinceMs >= RECENTER_HOLD_MS) {
                    baseGravity[0] = pitchNow
                    baseGravity[1] = inPlaneMag
                    stationarySinceMs = now
                }
            } else {
                stationarySinceMs = 0L
            }
        }

        // ★ 无线性加速度传感器时：从加速度计高通得到挥动估计（fallback）
        if (!hasLinearAccel && calibrated) {
            // 高通：原始加速度 - 低通重力 = 线性部分（粗略估计）
            val linX = rawGxS - smoothed[0]
            val linY = rawGyS - smoothed[1]
            val linZ = rawGzS - smoothed[2]
            updateSwingFromLinear(linX, linY, linZ, state)
        }
        try { sink(state) } catch (_: Throwable) {}
    }

    /** 屏幕平面内重力方向角（度）。横放水平（重力沿 -Y_s）时 = 180°
     *  （即 V4 固定锚点）；顶部向左倾为正方向偏移（V3 推导）。 */
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
