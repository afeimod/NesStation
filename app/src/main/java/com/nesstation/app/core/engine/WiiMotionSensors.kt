package com.nesstation.app.core.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * ★★★★ V5 重写（本轮，回应用户"wii手机体感模拟更乱了……模拟6个方向，不是乱晃"）★★★★
 *
 * V4 的核心缺陷（本轮根治）：
 *  1.【固定锚点错误】V4 把 roll 锚死在绝对横放角 180°，但没有人能全程把手机端得
 *    水平 —— 自然握持天然带 ±5..15° 偏差 → 恒定大幅 roll 输出；握持角随动作
 *    漂移 → 输出无规律大幅摆动 = 用户看到的"乱晃"。固定锚点是"横放为中心"
 *    的错误实现：用户要的是【以自己横放握持的姿态为中心】，不是绝对水平。
 *  2.【V3 的老问题】基准取"开启瞬间姿态"——若开启时还在竖持/平放（加载画面），
 *    中心点被污染 → 方向全错。V4 为此引入固定锚点却引入了更糟的"乱晃"。
 *
 * V5 模型 —— 横放门控基线捕获（"把手机横放为中心点(非平放)"的正确实现）：
 *  ★ 启动后持续采样，**仅当姿态判定为"横放可信"时**（屏幕平面内重力角接近 180°
 *    ± 35° 且平面内重力幅值足够 = 屏幕竖直面向用户，非平放/非竖持）才捕获
 *    roll+pitch 双基线 —— 中心点【必然】是一个横放握持姿态：
 *      - 进游戏前已横握 → 几百毫秒内即完成校准（无感）；
 *      - 竖持/平放启动 → 输出保持 0，直到用户转为横握才校准 —— 平放/竖持
 *        永远不会成为中心点（用户原话"非平放"的直接实现）。
 *  ★ 校准完成后：roll/pitch 均输出【相对自身横放握持姿态】的偏转 —— 手怎么
 *    拿、拿得斜一点都不产生虚假输出，倾斜多少输出多少（6 个方向可控）。
 *  ★ 静止重校准（roll+pitch 双轴）：近静止 600ms 且当前输出接近 0 且姿态
 *    仍横放可信 → 以当前姿态刷新基线（游玩中放下再拿起手机自动回中）。
 *
 * 屏幕坐标系（旋转表实证：X_s 右、Y_s 上、Z_s 出屏朝用户）：
 *   - 左右倾斜（方向盘式，绕 Z_s）：向左转 → rollDev < 0 → tiltLeft
 *   - 前后倚俯（顶部推离/拉近）：gz_s 增大 = 前倾 → tiltForward
 *   - 上下挥动：线性加速度 +Y_s = 上挥 → swingUp
 *   - 前后推拉：线性加速度 -Z_s = 向前推 → swingForward
 */
object WiiMotionSensors {

    /** ★★ v1.4 全局灵敏度增益（设置面板「体感灵敏度」写入，默认 1.6 高）：
     *   1.0 = 基准；>1 更灵敏（满程所需倾角/阈值按比例缩小）。存
     *   PadLayoutStore.wiiMotionSensitivity，在各项标尺计算处应用。 */
    @Volatile var sensitivityGain: Float = 1.6f

    /** ★★★ V5：左右倾斜（方向盘式 roll）满程角度（度）—— 相对【横放握持基线】
     *   的偏转。基准 ≈14° 即满程；增益在 clean() 内除（1.6 默认 → ~9° 满程）。 */
    private const val FULL_ROLL_DEG = 14.0f

    /** ★★★ V5：前后倚俯（pitch）满程（重力分量比，sin θ）。0.28 ≈ 16°。 */
    private const val FULL_PITCH_FRAC = 0.28f

    /** 死区：roll 角度制（度）——轻微手抖不触发。 */
    private const val DEADZONE_ROLL_DEG = 2.0f

    /** 死区：pitch 分量比（≈0.9°）。 */
    private const val DEADZONE_PITCH = 0.015f

    /** ★ 增益下限保护：倾斜满程最低值（防增益过高时标尺退化/噪声满幅）。 */
    private const val FULL_ROLL_MIN_DEG = 7.0f
    private const val FULL_PITCH_MIN = 0.14f

    /** ★ 近平放抑制：屏幕平面内重力 < 此值（m/s²）时 roll 不可信（手机平放，
     *   非约定的横握模型）—— 按平面内重力幅值线性淡出 roll 输出。 */
    private const val ROLL_VALID_MAG = 2.0f
    private const val ROLL_FULL_MAG = 5.0f

    /** ★★★ V5 横放可信门 —— 平面内重力角与横放锚点（180°）的最大偏差（度）。
     *   竖持时重力沿 ±X_s（角 ≈ ±90°）→ 距 180° ≈ 90°，必被拒；横握歪 ±35°
     *   以内都认（宽松，避免校准永远不完成）。 */
    private const val LANDSCAPE_MAX_DEV_DEG = 35.0f

    /** ★★★ V5 横放可信门 —— 平面内重力幅值下限（m/s²）：sin(55°)·9.81 ≈ 8，
     *   取 6.5 允许较斜的握持；平放时 ≈ 0 必被拒。 */
    private const val LANDSCAPE_MIN_INPLANE = 6.5f

    /** 挥动触发阈值（线性加速度幅值，m/s²）。v1.4 基准 0.8；增益在 clean() 内除。 */
    private const val SWING_THRESHOLD = 0.8f

    /** 挥动满强度阈值（线性加速度幅值；v1.4 基准 3.5）。 */
    private const val SWING_FULL = 3.5f

    /** ★★ v1.3 推/拉（前后晃动的线性加速度分量）独立阈值。v1.4 基准 0.55/2.2。 */
    private const val SWING_FB_THRESHOLD = 0.55f
    private const val SWING_FB_FULL = 2.2f

    /** 摇晃触发阈值（瞬时角速度变化，rad/s；v1.4 基准 2.2）。 */
    private const val SHAKE_THRESHOLD = 2.2f

    /** 摇晃满强度阈值（v1.4 基准 7.0）。 */
    private const val SHAKE_FULL = 7.0f

    /** ★ 增益下限保护：挥动/摇晃阈值最低值（防高增益时噪声触发）。 */
    private const val SWING_THRESHOLD_MIN = 0.35f
    private const val SHAKE_THRESHOLD_MIN = 0.8f

    /** ★ 静止重校准：判定"近静止"的角速度上限（rad/s）。 */
    private const val STATIONARY_GYRO = 0.06f

    /** ★ 静止重校准：判定"近静止"的线性加速度幅值上限（m/s²）。 */
    private const val STATIONARY_ACCEL = 0.35f

    /** ★ 静止重校准：需持续静止的时长（ms）后重新采集基准。 */
    private const val RECENTER_HOLD_MS = 600L

    /** ★★ V5 基线收敛采样数：横放可信姿态下连续积累 K 个样本求均值作基线。 */
    private const val CALIB_SAMPLES = 12

    /** ★★ V5 重校准门控：当前四向倾斜输出全部低于此值才允许重采基线 ——
     *   防止基准"追赶"用户正在保持的倾斜。 */
    private const val RECENTER_MAX_OUTPUT = 0.10f

    /** ★★★ V5 参考锚点（仅用于"横放可信"判定，不作输出基准）：横放水平
     *   （屏幕竖直面向自己）时屏幕平面内重力沿 -Y_s → rollDeg = 180°。
     *   两种横屏方向（top-left / top-right）经旋转表换算后均为 180°。 */
    private const val LANDSCAPE_ROLL_ANCHOR_DEG = 180.0f

    /**
     * 体感状态（每次事件回调后输出，13 维）。
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

    /** ★★★ V5 基线（校准后的横放握持姿态）：
     *  [0] = roll 基准角（度），[1] = pitch 基准分量比（gz/9.81），
     *  [2] = 诊断用平面内重力幅值。 */
    private val baseGravity = FloatArray(3)

    /** 低通滤波后的重力（屏幕坐标系：X_s 右、Y_s 上、Z_s 出屏）。 */
    private val smoothed = FloatArray(3)

    /** 上一帧线性加速度（用于摇动检测的导数计算）。 */
    private val lastLinAccel = FloatArray(3)

    /** 上一帧陀螺角速度（用于摇动检测的导数计算）。 */
    private val lastGyro = FloatArray(3)

    /** ★ 静止重校准状态：近静止持续时长（计时起点 ms）。 */
    private var stationarySinceMs = 0L

    /** ★★ V5 基线收敛状态：横放可信窗口内的样本累计（不可信时整窗丢弃重计）。 */
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
     * 处理加速度计：提取重力（低通）→ 相对【横放握持基线】算倾斜。
     *
     * 屏幕坐标系（旋转表实证，X_s 右、Y_s 上、Z_s 出屏朝用户）：
     *   - 左右倾斜（方向盘，绕 Z_s）：向左转 = 平面内重力角相对基线负偏 → tiltLeft
     *   - 前后倚俯：gz_s 相对基线增大 = 前倾 → tiltForward
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
        // ★ 低通重力估计：alpha 0.5（50Hz 传感器 ≈ 40ms 响应）。
        val alpha = 0.5f
        smoothed[0] = alpha * gxS + (1 - alpha) * smoothed[0]
        smoothed[1] = alpha * gyS + (1 - alpha) * smoothed[1]
        smoothed[2] = alpha * gzS + (1 - alpha) * smoothed[2]
        // ★★ V5 基线收敛（横放门控）：仅当姿态"横放可信"（近 180° 且非平放）
        //   才累计样本；一旦姿态离开可信区 → 整窗丢弃（防止竖持/平放样本
        //   稀释基线）。校准完成前不输出任何倾斜（宁可晚 300ms，不可错中心）。
        if (!calibrated) {
            val inPlaneMag = kotlin.math.sqrt(gxS * gxS + gyS * gyS)
            val rollNow = rollDegOf(gxS, gyS)
            val landscape = isLandscapePlausible(rollNow, inPlaneMag)
            if (!landscape) {
                // 姿态不可信：丢弃整个收敛窗口，等用户转为横握
                calibCount = 0
                calibSum.fill(0f)
                state.tiltLeft = 0f; state.tiltRight = 0f
                state.tiltForward = 0f; state.tiltBackward = 0f
                try { sink(state) } catch (_: Throwable) {}
                return
            }
            calibSum[0] += gxS; calibSum[1] += gyS; calibSum[2] += gzS
            calibCount++
            if (calibCount < CALIB_SAMPLES) {
                try { sink(state) } catch (_: Throwable) {}
                return
            }
            val meanX = calibSum[0] / calibCount
            val meanY = calibSum[1] / calibCount
            val meanZ = calibSum[2] / calibCount
            // ★★★ V5 基线 = 用户自己的横放握持姿态（roll 取平面角、pitch 取 gz 分量比）
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
        emitTilt(state, sink, gxS, gyS, gzS)
    }

    /**
     * ★★★ V5 倾斜输出统一路径（校准完成后每事件调用）：
     *   - roll：相对【横放握持基线角】的偏转（握持多斜都不产生虚假输出）。
     *   - pitch：相对【基线 gz 分量比】的偏移（手臂自然后仰已含在基线里）。
     */
    private fun emitTilt(
        state: MotionState,
        sink: (MotionState) -> Unit,
        rawGxS: Float, rawGyS: Float, rawGzS: Float
    ) {
        // ===== 左右倾斜（方向盘式 roll，相对握持基线）=====
        val inPlaneMag = kotlin.math.sqrt(
            smoothed[0] * smoothed[0] + smoothed[1] * smoothed[1])
        val rollNow = rollDegOf(smoothed[0], smoothed[1])
        var rollDev = rollNow - baseGravity[0]
        if (rollDev > 180f) rollDev -= 360f
        if (rollDev < -180f) rollDev += 360f
        // 近平放（屏幕朝上/下）时平面内重力太小，roll 角不可信 → 线性淡出
        val rollFade = ((inPlaneMag - ROLL_VALID_MAG) /
            (ROLL_FULL_MAG - ROLL_VALID_MAG)).coerceIn(0f, 1f)
        // ★ v1.4：满程角度除以灵敏度增益，满程钳位 ≥ FULL_ROLL_MIN_DEG。
        fun cleanRoll(devDeg: Float): Float {
            val a = kotlin.math.abs(devDeg)
            val full = (FULL_ROLL_DEG / sensitivityGain).coerceAtLeast(FULL_ROLL_MIN_DEG)
            val v = if (a < DEADZONE_ROLL_DEG) 0f
            else ((a - DEADZONE_ROLL_DEG) / (full - DEADZONE_ROLL_DEG)).coerceIn(0f, 1f)
            return v * rollFade
        }
        val tL = cleanRoll(-rollDev)    // rollDev < 0 = 相对基线向左转 → tiltLeft
        val tR = cleanRoll(rollDev)     // rollDev > 0 = 向右转 → tiltRight
        state.tiltLeft = tL
        state.tiltRight = tR

        // ===== 前后倚俯（pitch 分量比，相对握持基线）=====
        val pitchNow = smoothed[2] / 9.81f
        val pitchDev = pitchNow - baseGravity[1]
        fun cleanPitch(v: Float): Float {
            val a = kotlin.math.abs(v)
            val full = (FULL_PITCH_FRAC / sensitivityGain).coerceAtLeast(FULL_PITCH_MIN)
            return if (a < DEADZONE_PITCH) 0f
            else ((a - DEADZONE_PITCH) / (full - DEADZONE_PITCH)).coerceIn(0f, 1f)
        }
        state.tiltForward = cleanPitch(pitchDev)
        state.tiltBackward = cleanPitch(-pitchDev)

        // ★★ 静止重校准（V5：roll+pitch 双轴，防握持角漂移）：
        //   1) 加速度接近纯重力（总幅值 ≈ 9.8±0.35）且持续 ≥ 600ms；
        //   2) 门控：当前四向输出全部 < RECENTER_MAX_OUTPUT（用户正在保持
        //      倾斜时绝不重采基线 —— "往左倾斜不回正就别停止"的保证）；
        //   3) 姿态仍横放可信（防止用户平放手机休息时把平放角采成基线）。
        val mag = kotlin.math.sqrt(
            smoothed[0] * smoothed[0] + smoothed[1] * smoothed[1] +
            smoothed[2] * smoothed[2])
        val nearStationary = kotlin.math.abs(mag - 9.81f) < STATIONARY_ACCEL
        val outputsQuiet = tL < RECENTER_MAX_OUTPUT && tR < RECENTER_MAX_OUTPUT &&
            state.tiltForward < RECENTER_MAX_OUTPUT && state.tiltBackward < RECENTER_MAX_OUTPUT
        val stillLandscape = isLandscapePlausible(rollNow, inPlaneMag)
        if (nearStationary && outputsQuiet && stillLandscape) {
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
            val linX = rawGxS - smoothed[0]
            val linY = rawGyS - smoothed[1]
            val linZ = rawGzS - smoothed[2]
            updateSwingFromLinear(linX, linY, linZ, state)
        }
        try { sink(state) } catch (_: Throwable) {}
    }

    /** ★★★ V5 横放可信判定：平面内重力角近横放锚点（±35°）且幅值足够（非平放）。
     *  竖持（角 ≈ ±90°）与平放（平面内幅值 ≈ 0）都判不可信。 */
    private fun isLandscapePlausible(rollDeg: Float, inPlaneMag: Float): Boolean {
        var dev = rollDeg - LANDSCAPE_ROLL_ANCHOR_DEG
        if (dev > 180f) dev -= 360f
        if (dev < -180f) dev += 360f
        return kotlin.math.abs(dev) <= LANDSCAPE_MAX_DEV_DEG && inPlaneMag >= LANDSCAPE_MIN_INPLANE
    }

    /** 屏幕平面内重力方向角（度）。横放水平（重力沿 -Y_s）时 = 180°。 */
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
        // ★ 陀螺近静止检测也参与重校准门控（角速度低 = 设备真静止）。
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
     * ★★ V3 方向修正：旋转表实证 Y_s 向上 —— lyS > 0 = 向上甩 → swingUp；
     *   lzS > 0 = 向自己拉（Z_s 出屏朝用户）→ swingBackward。
     */
    private fun updateSwingFromLinear(
        lxS: Float, lyS: Float, lzS: Float,
        state: MotionState
    ) {
        fun clean(v: Float): Float {
            val a = kotlin.math.abs(v)
            // ★ v1.4：挥动阈值/满程同除灵敏度增益
            val th = (SWING_THRESHOLD / sensitivityGain).coerceAtLeast(SWING_THRESHOLD_MIN)
            val full = (SWING_FULL / sensitivityGain).coerceAtLeast(th * 2f)
            return if (a < th) 0f
            else ((a - th) / (full - th))
                .coerceIn(0f, 1f)
        }
        // 屏幕坐标（V3 实证）：X_s 右、Y_s 上、Z_s 出屏朝用户
        state.swingRight = clean(lxS)
        state.swingLeft = clean(-lxS)
        state.swingUp = clean(lyS)
        state.swingDown = clean(-lyS)
        state.swingForward = cleanFbSwing(-lzS)   // 向前推（远离自己）
        state.swingBackward = cleanFbSwing(lzS)   // 向自己拉
    }

    /** ★★ v1.3 推/拉独立标尺。v1.4：同除灵敏度增益。 */
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
        //   上方 (MotionState) -> Unit 版 start。
        sink(s.tiltLeft, s.tiltRight, s.tiltForward, s.tiltBackward)
    }
}
