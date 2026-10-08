package com.nesstation.app.core.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * ★★★★ V8 重写（本轮，回应用户"wii手机体感模拟是神经病吧……都说了是固定六个
 *   方向的倾斜角度，根据倾斜角度保证实现对应方向的倾斜度，类似于重力感应，
 *   就像安卓的滚球游戏往哪里倾斜就一直往哪个方向保持倾斜，回正它跟着慢慢
 *   回原位"）★★★★
 *
 * 【V8 模型 —— 固定锚点重力滚球（用户需求原话逐条落实）】：
 *
 *  ★ 固定锚点（"固定"）：以【手机竖直横持、屏幕正对自己】（方向盘位）为
 *    唯一参考中心 —— 物理世界里的固定姿态，与进游戏时的握持姿态【无关】。
 *    V4~V7 全部失败的共同根源：基线捕获/校准/门控把"进游戏时的姿态"当
 *    零点 —— 用户换个姿势进游戏，整个坐标系就歪了（"恒向右偏"、"乱七八糟"、
 *    "晃一下才有反应"全部由此而来）。滚球游戏的零点就是"手机放平"，从来
 *    不需要校准 —— V8 同理：零点就是"手机拿正"，永远不需要校准。
 *
 *  ★ 六个方向（"固定六个方向的倾斜角度"）：
 *      左倾 —— 方向盘向左转（左侧边下沉）      → Tilt 左轴（129）持续输出
 *      右倾 —— 方向盘向右转（右侧边下沉）      → Tilt 右轴（130）持续输出
 *      前倾 —— 顶端推离自己（屏幕向天花板翻）  → Tilt 前轴（127）持续输出
 *      后倾 —— 顶端拉向自己（屏幕向地面翻）    → Tilt 后轴（128）持续输出
 *      上晃 —— 手机快速上提（瞬时动作）        → Swing 上轴（120）
 *      下晃 —— 手机快速下压（瞬时动作）        → Swing 下轴（121）
 *    外加左/右/前/后晃（平移挥动，Swing 122-125）与三轴摇晃（Shake 132-134）。
 *
 *  ★ 角度比例输出（"根据倾斜角度保证实现对应方向的倾斜度"）：
 *    输出强度 = 倾角线性映射（死区 7° → 满程 30°，灵敏度增益可调），
 *    保持姿态就保持输出 —— 与滚球游戏完全一致。
 *
 *  ★ 回正缓收（"回正它跟着慢慢回原位"）：输出用不对称平滑 ——
 *    攻向（倾斜加深）即时跟随，收向（回正）约 300ms 时间常数指数衰减，
 *    手感就是"回正后球慢慢滚回中心"。
 *
 * 【坐标推导（固定方向盘锚点，屏幕坐标系 X_s 右 / Y_s 上 / Z_s 出屏）】：
 *   - 竖直横持时比力（加速度计读数）指向 +Y_s（世界竖直向上）。
 *   - 左转方向盘（左侧下沉）：+X_s 轴翘向天空 → 比力获得 +X_s 分量
 *     → gxS 增大为【左倾】。（V7 把该符号标反 —— 平放锚点的约定搬到了
 *     方向盘锚点上，这是"横放手机左倾斜却输出右倾"的直接根源。）
 *   - 顶端推离（屏幕朝天翻）：+Z_s 轴翘向天空 → gzS 增大为【前倾】。
 *   - 上下晃动 = 世界竖直方向的瞬时平移 → 线性加速度 ±Y_s。
 *
 * 【实现要点】：
 *   - 重力估计：优先 Sensor.TYPE_GRAVITY（系统融合、天然免疫晃动）；
 *     无该传感器时低通加速度计 + 晃动冻结（|总模长-9.81|>3 冻结更新），
 *     双保险保证"晃动不会漏进倾斜"（V7 "晃一下才偶尔右倾"的根因修复）。
 *   - 绝不校准、绝不自适应、绝不门控 —— 零点永远物理固定。
 */
object WiiMotionSensors {

    /** ★★ v1.4 全局灵敏度增益（设置面板「体感灵敏度」写入，默认 1.6 高）：
     *   1.0 = 基准；>1 更灵敏（满程所需倾角按比例缩小）。 */
    @Volatile var sensitivityGain: Float = 1.6f

    /** ★★★ V8：左右倾满程重力分量（m/s²）。4.9 ≈ sin(30°)·9.81 ——
     *   倾角 30° 达满强度；增益在 computeTilt 内除（钳下限）。 */
    private const val FULL_ROLL = 4.9f

    /** ★★★ V8：前后倾满程重力分量（m/s²）。同 30° 满程。 */
    private const val FULL_PITCH = 4.9f

    /** ★★★ V8：死区（m/s² ≈ 7°）—— 自然握持的轻微歪斜不产生输出，
     *   有意向的倾斜立刻超过死区开始输出。 */
    private const val DEADZONE = 1.2f

    /** ★ 增益下限保护（防灵敏度拉满时标尺退化/噪声满幅）。 */
    private const val FULL_ROLL_MIN = 2.0f
    private const val FULL_PITCH_MIN = 2.0f

    /** ★★★ V8：收向时间常数（秒）。回正后输出 ~300ms 衰减到 37%，
     *   ~1s 基本归零 —— "回正它跟着慢慢回原位"。攻向不延迟。 */
    private const val RELEASE_TAU = 0.3f

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

    /** ★★ V8：低通重力估计的晃动冻结阈值 —— 加速度计总模长偏离
     *   9.81 超过该值（剧烈挥动中）时冻结重力估计，防止挥动泄漏进倾斜。 */
    private const val GRAVITY_FREEZE_DELTA = 3.0f

    /** ★★ V8：传感器采样间隔估计（默认 50Hz；按事件时间戳自适应）。 */
    private var lastTimestampNs = 0L

    /** ★★ V8：最近一次事件间隔（秒，缺省 20ms@50Hz）—— 收向衰减系数用。 */
    private var sampleDt = 0.02f

    /**
     * 体感状态（每次事件回调后输出，13 维）。
     *
     * 索引约定：
     *  - [0..3] = tiltLeft, tiltRight, tiltForward, tiltBackward（0..1，重力持续驱动）
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

    /** 低通后的重力估计（屏幕坐标系：X_s 右、Y_s 上、Z_s 出屏）。 */
    private val gravity = FloatArray(3)

    /** 首样本尚未初始化（true = gravity 全零，下一帧直接吸附真值）。 */
    private var gravityInitialized = false

    /** 上一帧线性加速度（用于摇动检测的导数计算）。 */
    private val lastLinAccel = FloatArray(3)

    /** 上一帧陀螺角速度（用于摇动检测的导数计算）。 */
    private val lastGyro = FloatArray(3)

    /** V8 输出平滑的当前值（攻向即时 / 收向指数衰减）。 */
    private val tiltOut = FloatArray(4)

    @Volatile private var hasGravitySensor = false
    @Volatile private var hasLinearAccel = false
    @Volatile private var hasGyro = false

    /**
     * 启动体感监听（V8：MotionState sink）。
     *
     * @param displayRotation 当前显示旋转角（Surface.ROTATION_0/90/180/270）
     * @param sink 每次事件回调（已滤波 + 六方向解算），返回完整 13 维运动状态
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
        // ★ V8：优先融合重力传感器（系统级低通，天然免疫挥动泄漏）
        val grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        // 可选传感器：没有也能跑（功能降级）
        val linAcc = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorManager = sm
        hasGravitySensor = grav != null
        hasLinearAccel = linAcc != null
        hasGyro = gyro != null
        gravity.fill(0f)
        gravityInitialized = false
        lastLinAccel.fill(0f)
        lastGyro.fill(0f)
        tiltOut.fill(0f)
        lastTimestampNs = 0L
        sampleDt = 0.02f

        val state = MotionState()
        val rotation = displayRotation

        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY -> handleGravity(event, rotation, state, sink)
                    Sensor.TYPE_ACCELEROMETER ->
                        if (!hasGravitySensor) handleAccel(event, rotation, state, sink)
                        else if (!hasLinearAccel) {
                            // 有融合重力但无线性加速度：加速度计只做挥动高通源
                            handleAccelSwingOnly(event, rotation, state, sink)
                        }
                    Sensor.TYPE_LINEAR_ACCELERATION -> handleLinAccel(event, rotation, state, sink)
                    Sensor.TYPE_GYROSCOPE -> handleGyro(event, rotation, state, sink)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        return try {
            sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_GAME)
            grav?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
            linAcc?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
            gyro?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
            listener = l
            true
        } catch (_: Throwable) {
            listener = null
            false
        }
    }

    /** 设备坐标 → 屏幕坐标（Android 官方旋转表，两种横持方向均适用）。 */
    private fun toScreen(x: Float, y: Float, rotation: Int): Pair<Float, Float> {
        return when (rotation) {
            Surface.ROTATION_90 -> -y to x
            Surface.ROTATION_270 -> y to -x
            Surface.ROTATION_180 -> -x to -y
            else -> x to y
        }
    }

    /**
     * ★★★ V8 倾斜解算（固定方向盘锚点，每事件调用）：
     *   gxS 增大 = 左倾；gzS 增大 = 前倾（推导见类头注释）。
     *   输出不对称平滑：攻向即时，收向按 RELEASE_TAU 指数衰减。
     */
    private fun emitTilt(state: MotionState, sink: (MotionState) -> Unit) {
        val fullRoll = (FULL_ROLL / sensitivityGain).coerceAtLeast(FULL_ROLL_MIN)
        val fullPitch = (FULL_PITCH / sensitivityGain).coerceAtLeast(FULL_PITCH_MIN)
        fun lin(v: Float, full: Float): Float {
            val a = kotlin.math.abs(v)
            return if (a < DEADZONE) 0f
            else ((a - DEADZONE) / (full - DEADZONE)).coerceIn(0f, 1f)
        }
        // ★★ V8：传感器采样间隔（事件时间差，缺省 20ms@50Hz）→ 收向衰减系数
        val decay = kotlin.math.exp(-sampleDt / RELEASE_TAU)

        // 目标值（0..1）：物理方向 → 通道（固定符号，绝不校准）
        val target = floatArrayOf(
            lin(gravity[0], fullRoll),    // 左倾：gxS > 0
            lin(-gravity[0], fullRoll),   // 右倾：gxS < 0
            lin(gravity[2], fullPitch),   // 前倾：gzS > 0（顶端推离 / 屏幕朝天翻）
            lin(-gravity[2], fullPitch)   // 后倾：gzS < 0（顶端拉近 / 屏幕朝地翻）
        )
        for (i in 0 until 4) {
            val cur = tiltOut[i]
            tiltOut[i] = if (target[i] >= cur) target[i]          // 攻向：即时
            else target[i] + (cur - target[i]) * decay            // 收向：指数衰减
        }
        state.tiltLeft = tiltOut[0]
        state.tiltRight = tiltOut[1]
        state.tiltForward = tiltOut[2]
        state.tiltBackward = tiltOut[3]
        try { sink(state) } catch (_: Throwable) {}
    }

    /** 按事件时间戳更新采样间隔（秒）；异常时间戳（复位/乱序）时保持缺省。 */
    private fun updateSampleDt(timestampNs: Long) {
        if (lastTimestampNs > 0L && timestampNs > lastTimestampNs) {
            val dt = (timestampNs - lastTimestampNs) * 1e-9f
            if (dt in 0.001f..0.2f) sampleDt = dt
        }
        lastTimestampNs = timestampNs
    }

    /** 处理融合重力传感器（最优路径：天然分离重力与运动）。 */
    private fun handleGravity(
        event: SensorEvent,
        rotation: Int,
        state: MotionState,
        sink: (MotionState) -> Unit
    ) {
        val (gxS, gyS) = toScreen(event.values[0], event.values[1], rotation)
        gravity[0] = gxS
        gravity[1] = gyS
        gravity[2] = event.values[2]
        updateSampleDt(event.timestamp)
        emitTilt(state, sink)
    }

    /**
     * 处理加速度计（无融合重力传感器时的路径）：
     *   低通重力估计（含晃动冻结）→ 倾斜；高通残差 → 挥动兜底。
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
        val (gxS, gyS) = toScreen(gx, gy, rotation)
        val gzS = gz
        val mag = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
        // ★★ V8 晃动冻结：剧烈运动中（|模长-g| 超阈值）冻结重力估计，
        //   挥动结束后恢复 —— 晃动绝不漏进倾斜（"晃一下才偶尔右倾"根治）。
        val frozen = kotlin.math.abs(mag - 9.81f) > GRAVITY_FREEZE_DELTA
        val alpha = 0.15f
        if (!gravityInitialized) {
            // 首样本直接吸附：避免低通从 0 爬升的初期瞬态
            gravity[0] = gxS; gravity[1] = gyS; gravity[2] = gzS
            gravityInitialized = true
        } else if (!frozen) {
            gravity[0] = alpha * gxS + (1 - alpha) * gravity[0]
            gravity[1] = alpha * gyS + (1 - alpha) * gravity[1]
            gravity[2] = alpha * gzS + (1 - alpha) * gravity[2]
        }
        updateSampleDt(event.timestamp)
        emitTilt(state, sink)
        // 无线性加速度传感器时：高通残差做挥动估计（兜底）
        if (!hasLinearAccel) {
            val linX = gxS - gravity[0]
            val linY = gyS - gravity[1]
            val linZ = gzS - gravity[2]
            updateSwingFromLinear(linX, linY, linZ, state)
            try { sink(state) } catch (_: Throwable) {}
        }
    }

    /** 有融合重力但无独立线性加速度：加速度计只做挥动高通源（倾斜走重力传感器）。 */
    private fun handleAccelSwingOnly(
        event: SensorEvent,
        rotation: Int,
        state: MotionState,
        sink: (MotionState) -> Unit
    ) {
        val (gxS, gyS) = toScreen(event.values[0], event.values[1], rotation)
        val gzS = event.values[2]
        val linX = gxS - gravity[0]
        val linY = gyS - gravity[1]
        val linZ = gzS - gravity[2]
        updateSwingFromLinear(linX, linY, linZ, state)
        try { sink(state) } catch (_: Throwable) {}
    }

    /** 处理线性加速度（去重力后的纯运动）：驱动挥动。 */
    private fun handleLinAccel(
        event: SensorEvent,
        rotation: Int,
        state: MotionState,
        sink: (MotionState) -> Unit
    ) {
        val (lxS, lyS) = toScreen(event.values[0], event.values[1], rotation)
        val lzS = event.values[2]
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

    /** 处理陀螺仪（角速度）：精确的摇动检测。 */
    private fun handleGyro(
        event: SensorEvent,
        rotation: Int,
        state: MotionState,
        sink: (MotionState) -> Unit
    ) {
        val (rxS, ryS) = toScreen(event.values[0], event.values[1], rotation)
        val rzS = event.values[2]
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
        gravity.fill(0f)
        gravityInitialized = false
        tiltOut.fill(0f)
        lastLinAccel.fill(0f)
        lastGyro.fill(0f)
        hasGravitySensor = false
        hasLinearAccel = false
        hasGyro = false
        lastTimestampNs = 0L
        sampleDt = 0.02f
    }

    fun isRunning(): Boolean = listener != null

    /**
     * ★★ V8：兼容保留的空重校准接口。固定锚点模型下【不存在校准语义】——
     *   零点永远是"手机竖直横持"，与任何时刻的握持姿态无关（V4~V7 的
     *   基线捕获/门控重校准正是历轮"方向错乱/恒偏/乱晃"的根源，已全部删除）。
     *   保留方法体以兼容既有调用方，调用为无操作。
     */
    @Deprecated("V8 固定锚点：无校准语义，调用为无操作")
    fun recenter() {
        // 无操作 —— 固定锚点无需校准
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
