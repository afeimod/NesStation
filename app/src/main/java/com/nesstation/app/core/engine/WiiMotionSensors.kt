package com.nesstation.app.core.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * ★★★★ V7 重写（本轮，回应用户"wii的手机体感模拟依旧是乱七八糟，
 *   晃一下才有反应……要自己加入手机体感模拟的方式"）★★★★
 *
 * V6 的三个致命缺陷（"乱 + 晃一下才有反应"的全部来源，逐一定位）：
 *  1.【约 3 秒启动静默】V6 的 CALIB_DELAY_MS=2500 + 25 样本(0.5s) ——
 *    进入游戏后前 3 秒倾斜输出恒 0；用户"进去就倾斜试试"没反应，
 *    晃几下（3 秒后基线捕获完成）突然有反应 = "晃一下才有反应"
 *    的直接体感来源。
 *  2.【近平放 roll 淡出误杀】V6 的 ROLL_VALID_MAG/FULL_MAG(2~5 m/s²)
 *    把"平面内重力幅值小"当作不可信淡出 roll —— 但正常俯视握持
 *    （横持 + 屏幕朝上倾斜 10~15°）的平面内重力恰在 1.7~2.5 m/s²
 *    区间 → roll 输出被淡出系数压到 0~17% → 左右倾斜几乎无输出。
 *  3.【atan2 平面角噪声放大】V6 的 rollDegOf = atan2(gxS, gyS) 在俯角
 *    小时 gyS ≈ 0，角度被噪声剧烈放大 —— 这正是引入"淡出"掩盖的
 *    原因；掩盖导致输出雪上加霜。
 *
 * V7 模型 —— 自研"重力分量差线性映射"（不依赖上游任何既有实现）：
 *  ★ 立即校准：首个传感器事件起累计 10 个样本（50Hz ≈ 0.2s）求
 *    重力分量基线（gxS/gyS/gzS 各自均值）= 用户进游戏时的握持姿态
 *    （"横放为中心"由使用习惯保证）。静默期从 ~3 秒缩到 0.2 秒。
 *  ★ 倾斜输出 = 平滑重力的【分量差】直接线性映射（屏幕坐标系）：
 *      - 左右倾（方向盘式，绕屏幕长轴）→ gxS 分量差
 *      - 前后倾（抬/压顶端，绕屏幕短边）→ gzS 分量差
 *    分量差在任何俯角下同样有效（无 atan2 奇异点、无需平面内幅值
 *    判别、无淡出）—— 平坦区线性、可预测、左右前后互不串扰。
 *  ★ 符号约定沿用已实测正确的方向（fix2 三重实证 + 用户确认后未再
 *    报方向反）：gxS 相对基线减小 = 左倾；gzS 相对基线增大 = 前倾。
 *  ★ 满程（m/s² 重力分量差，≈ V5/V6 等效角度标尺）：
 *      roll 2.45 m/s² ≈ 14.5°、pitch 2.75 m/s² ≈ 16.3°（正弦换算），
 *      灵敏度增益继续按设置面板生效（满程除以增益，钳位下限）。
 *  ★ 死区统一 0.5 m/s²（≈3°）：轻微手抖不触发，倾斜即刻有输出。
 *  ★ 【绝不自动重校准】（V5 教训）；[recenter] 手动接口保留，由引擎
 *    在重启/换游戏时调用（立即重新捕获，无延迟窗口）。
 *  ★ 挥动（Swing 6 向）/摇晃（Shake 3 轴）维持 V6 标尺：线性加速度 /
 *    角速度驱动，只响应瞬时动作，与静态倾斜互不干扰。
 *
 * 屏幕坐标系（旋转表实证：X_s 右、Y_s 上、Z_s 出屏朝用户）：
 *   - 左右倾斜（方向盘式）→ gxS 分量变化（朝上俯视握持模型）
 *   - 前后倚俯（顶部推离/拉近）→ gzS 分量变化（增大 = 前倾）
 *   - 上下挥动：线性加速度 +Y_s = 上挥 → swingUp
 *   - 前后推拉：线性加速度 -Z_s = 向前推 → swingForward
 */
object WiiMotionSensors {

    /** ★★ v1.4 全局灵敏度增益（设置面板「体感灵敏度」写入，默认 1.6 高）：
     *   1.0 = 基准；>1 更灵敏（满程所需倾角按比例缩小）。存
     *   PadLayoutStore.wiiMotionSensitivity，在满程计算处应用。 */
    @Volatile var sensitivityGain: Float = 1.6f

    /** ★★★ V7：左右倾满程（m/s² 重力分量差）。2.45 ≈ sin(14.5°)·9.81 ——
     *   与 V5/V6 的 14° 满程等效；增益在 emitTilt 内除（钳下限）。 */
    private const val FULL_ROLL = 2.45f

    /** ★★★ V7：前后倾满程（m/s²）。2.75 ≈ 0.28·9.81（V5 的
     *   FULL_PITCH_FRAC=0.28 等效 ≈16.3°）。 */
    private const val FULL_PITCH = 2.75f

    /** ★★★ V7：统一死区（m/s²，≈3°）—— 轻微手抖不触发，倾斜即刻有输出。 */
    private const val DEADZONE = 0.5f

    /** ★ 增益下限保护（防灵敏度拉满时标尺退化/噪声满幅）。 */
    private const val FULL_ROLL_MIN = 1.10f
    private const val FULL_PITCH_MIN = 1.25f

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

    /** ★★ V7 基线捕获采样数：首个事件起累计（50Hz ≈ 0.2s；无条件、
     *   无延迟窗口 —— V6 的 2.5s 延迟 + 25 样本 = 3 秒静默是
     *   "晃一下才有反应"的主因）。 */
    private const val CALIB_SAMPLES = 10

    /**
     * 体感状态（每次事件回调后输出，13 维）。
     *
     * 索引约定：
     *  - [0..3] = tiltLeft, tiltRight, tiltForward, tiltBackward（0..1，重力分量差驱动）
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

    /** ★★★ V7 基线（进游戏时握持姿态的平滑重力，屏幕坐标系三分量）：
     *  [0] = gxS 基线，[1] = gyS 基线，[2] = gzS 基线。 */
    private val baseGravity = FloatArray(3)

    /** 低通滤波后的重力（屏幕坐标系：X_s 右、Y_s 上、Z_s 出屏）。 */
    private val smoothed = FloatArray(3)

    /** 上一帧线性加速度（用于摇动检测的导数计算）。 */
    private val lastLinAccel = FloatArray(3)

    /** 上一帧陀螺角速度（用于摇动检测的导数计算）。 */
    private val lastGyro = FloatArray(3)

    /** ★★ V7 基线捕获状态：首个事件起累计（无条件，无延迟窗口）。 */
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
        baseGravity.fill(0f)
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
     * 处理加速度计：提取重力（低通）→ 【V7 重力分量差线性映射】倾斜。
     *
     * 屏幕坐标系（旋转表实证，X_s 右、Y_s 上、Z_s 出屏朝用户）：
     *   - 左右倾斜（方向盘式）→ gxS 相对基线变化（减小 = 左倾）
     *   - 前后倚俯 → gzS 相对基线变化（增大 = 前倾）
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
        // ★★ V7 基线捕获（无条件、立即）：首个事件起累计 CALIB_SAMPLES
        //   个样本求均值 = 用户进游戏时的握持姿态（~0.2s）。V6 的 2.5s
        //   延迟 + 0.5s 采样 = 3 秒静默（"晃一下才有反应"主因）已删除；
        //   门控拒绝校准（V5）也早已证伪 —— 基线缺失只会让输出恒 0。
        if (!calibrated) {
            calibSum[0] += gxS; calibSum[1] += gyS; calibSum[2] += gzS
            calibCount++
            if (calibCount < CALIB_SAMPLES) {
                try { sink(state) } catch (_: Throwable) {}
                return
            }
            baseGravity[0] = calibSum[0] / calibCount
            baseGravity[1] = calibSum[1] / calibCount
            baseGravity[2] = calibSum[2] / calibCount
            calibrated = true
            // 捕获完成：滤波器预热到基线重力，避免首拍跳变
            smoothed[0] = baseGravity[0]
            smoothed[1] = baseGravity[1]
            smoothed[2] = baseGravity[2]
            return
        }
        emitTilt(state, sink, gxS, gyS, gzS)
    }

    /**
     * ★★★ V7 倾斜输出（重力分量差线性映射，每事件调用）：
     *   - roll 轴（左/右倾）：gxS - baseGx —— 屏幕朝上俯视握持下绕屏幕
     *     长轴的重力分量响应；无 atan2 奇异点、无近平放淡出（V6 两个
     *     噪声/误杀源已删除）。
     *   - pitch 轴（前/后倾）：gzS - baseGz —— 抬/压顶端（绕屏幕短边
     *     俯仰）的重力分量响应；增大 = 前倾。
     *   - 分量差线性映射在任何俯角下同样有效且平坦区线性 —— 倾斜
     *     即刻有输出、保持姿态持续输出（不再"晃一下才有反应"）。
     */
    private fun emitTilt(
        state: MotionState,
        sink: (MotionState) -> Unit,
        rawGxS: Float, rawGyS: Float, rawGzS: Float
    ) {
        // ★ v1.4：满程除以灵敏度增益（钳位下限），死区不随增益缩放
        //   （死区是防抖语义，灵敏度是斜率语义）。
        val fullRoll = (FULL_ROLL / sensitivityGain).coerceAtLeast(FULL_ROLL_MIN)
        val fullPitch = (FULL_PITCH / sensitivityGain).coerceAtLeast(FULL_PITCH_MIN)
        fun lin(v: Float, full: Float): Float {
            val a = kotlin.math.abs(v)
            return if (a < DEADZONE) 0f
            else ((a - DEADZONE) / (full - DEADZONE)).coerceIn(0f, 1f)
        }
        // ===== 左右倾斜（gxS 分量差；减小 = 左倾 —— 已实证符号）=====
        val rollDev = smoothed[0] - baseGravity[0]
        state.tiltLeft = lin(-rollDev, fullRoll)
        state.tiltRight = lin(rollDev, fullRoll)

        // ===== 前后倚俯（gzS 分量差；增大 = 前倾 —— 已实证符号）=====
        val pitchDev = smoothed[2] - baseGravity[2]
        state.tiltForward = lin(pitchDev, fullPitch)
        state.tiltBackward = lin(-pitchDev, fullPitch)

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
    }

    fun isRunning(): Boolean = listener != null

    /**
     * ★★ V7 手动重校准：清除当前基线，监听继续运行时下一个事件起立即
     *   重新捕获（约 0.2s 完成新基线，无 V6 的延迟窗口）。游玩中
     *   【绝不自动触发】（V5 的"静止重校准基线追赶"是方向错乱的
     *   直接来源，已删除）；由引擎在重启游戏 / 换游戏的时机调用。
     */
    fun recenter() {
        synchronized(this) {
            calibrated = false
            calibCount = 0
            calibSum.fill(0f)
        }
    }

    /**
     * ★ 旧版兼容：4 参 sink（仅倾斜）。保留以免破坏外部调用；新代码应优先用
     *   MotionState 版 [start]，能驱动 SWING/SHAKE 全方向。
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
