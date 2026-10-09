package com.nesstation.app.core.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * ★★★★ V10 重写（本轮，回应用户"wii游戏……手机体感模拟有问题，横放手机往上晃动
 *   才偶尔出现，应该是固定六个方向的倾斜角度，根据倾斜角度保证实现对应方向的
 *   倾斜度，类似于重力感应，就像安卓的滚球游戏往哪里倾斜就一直往哪个方向保持
 *   倾斜，回正它跟着慢慢回原位"）★★★★
 *
 * 【V10 模型 —— 大理石滚球（marble）六方向，逐条对齐用户需求原话】：
 *
 *  ★ 六个方向（"固定六个方向的倾斜角度"）—— 全部由【重力角度】连续驱动：
 *      左倾 —— 左侧边下沉（大理石向左滚）  → Tilt 左轴（129）持续输出
 *      右倾 —— 右侧边下沉（大理石向右滚）  → Tilt 右轴（130）持续输出
 *      前倾 —— 顶边下沉（大理石向屏幕上方滚）→ Tilt 前轴（127）持续输出
 *      后倾 —— 顶边抬起（大理石向怀里滚）  → Tilt 后轴（128）持续输出
 *      上晃 —— 手机快速上提（瞬时动作）    → Swing 上轴（120）
 *      下晃 —— 手机快速下压（瞬时动作）    → Swing 下轴（121）
 *    外加左/右/前/后晃（平移挥动，Swing 122-125）与三轴摇晃（Shake 132-134）。
 *
 *  ★ 大理石换算（"根据倾斜角度保证实现对应方向的倾斜度"）：
 *    把重力比力投影到屏幕平面 —— 大理石永远向【低的一侧】滚：
 *      marbleX = -gxS/g（+1 = 向右滚满格），marbleY = -gyS/g（+1 = 向屏幕上方滚满格）
 *    倾角 → 输出线性映射：死区 ~3°(0.05) → 满程（0.34÷灵敏度增益）。
 *    该模型在【横放平贴 / 斜靠 / 立式方向盘位】任意握持角度下左右方向
 *    都成立（gxS 的符号与握持仰角无关），前后方向以"大理石往屏幕上方
 *    还是往怀里滚"为准 —— 与滚球游戏手感完全一致。
 *
 *  ★ 零点策略（V10，"手机不回正他也不回正"）：
 *    零点 neutral = 大理石基准点，只在【首次启动】与【手动回正(recenter)】
 *    时锚定到当前握持姿态；此后零点【固定不漂移】—— 只要手机偏离该姿态，
 *    对应方向输出就一直保持；回到零点附近输出自然归零。不再做静息吸附：
 *    V9 的 2 秒静息重锚会把小幅倾斜（<0.15）当"姿态漂移"洗掉，
 *    正是"左右倾斜没反应"的直接成因。攻向即时跟随、收向约 300ms 时间
 *    常数指数衰减 —— 手感就是"回正后球慢慢滚回中心"。
 *
 *  ★ 传感器看门狗（V9 "晃一下才偶尔有反应"的加固）：
 *    部分机型的 TYPE_GRAVITY（融合重力）注册成功但不出数 —— V9 只在
 *    start() 时按"传感器存在与否"一次性决策，撞上这种机型倾斜通道
 *    整体哑火、只有线性加速度的挥动摇晃偶尔响应。V9 在加速度计事件里
 *    看门狗：启动 600ms 内重力传感器没出过数 → 自动切换加速度计低通
 *    估计重力（含晃动冻结），倾斜通道永不哑火。
 *
 * 【坐标推导（屏幕坐标系 X_s 右 / Y_s 上 / Z_s 出屏，比力 = 静止时的"上"）】：
 *   - 平贴（屏幕朝上）：比力 ≈ (0, 0, +g)。
 *   - 立式横持（屏幕正对自己）：比力 ≈ (0, +g, 0)。
 *   - 左侧下沉 φ：比力获得 +X_s 分量 = +g·sinφ → marbleX = -sinφ < 0 → 向左滚 ✓。
 *   - 顶边下沉 ψ：比力获得 -Y_s 分量 = -g·sinψ → marbleY = +sinψ > 0 → 向上滚 ✓。
 *
 * 【实现要点】：
 *   - 重力估计：优先 Sensor.TYPE_GRAVITY（系统融合、天然免疫晃动）+
 *     看门狗兜底；无该传感器时低通加速度计 + 晃动冻结（|总模长-9.81|>3
 *     冻结更新），保证"晃动不会漏进倾斜"。
 *   - 零点只在首样本与手动回正时锚定，之后固定 —— 倾斜持续保持，
 *     回正才归位。
 */
object WiiMotionSensors {

    /** ★★ 全局灵敏度增益（设置面板「体感灵敏度」写入，默认 1.6 高）：
     *   1.0 = 基准；>1 更灵敏（满程所需倾角按比例缩小）。 */
    @Volatile var sensitivityGain: Float = 1.6f

    // ----------------------------------------------------------------------
    // ★ V9 大理石模型标尺（单位 = 大理石位移 / sin(倾角)，1.0 = 45° 等效）
    // ----------------------------------------------------------------------

    /**
     * 死区（≈ sin 5°）：自然握持的轻微歪斜不产生输出。
     * ★ V11.2：从 sin 3° (0.05) 提到 sin 5° (0.087) —— 中心点稳定性加固。
     *   低通滤波后仍有微抖动残留在 sin 3°~5° 之间，扩大死区把这些"几乎垂直
     *   但抖了一下"的状态也压成 0，配合 alpha=0.2 低通后中心点彻底稳定。
     */
    private const val MARBLE_DEADZONE = 0.087f

    /** 满程基准（≈ sin 20°），实际满程 = 该值 ÷ 灵敏度增益（钳下限）。 */
    private const val MARBLE_FULL = 0.34f

    /** 满程下限保护（防灵敏度拉满时标尺退化/噪声满幅）。 */
    private const val MARBLE_FULL_MIN = 0.15f

    /**
     * ★ V10 零点策略：不再做静息漂移吸附。
     * 零点只在【首次启动】与【手动回正(recenter)】时锚定到当前姿态；
     * 此后任何倾斜输出（越过死区）都会保持，直到姿态回到零点 ——
     * 正是"手机不回正他也不回正、回正他慢慢回原位"的语义。
     * 旧 V9 的 2s 静息吸附会把用户的小幅倾斜（<0.15）当"静置"洗掉，
     * 是"左右倾斜没反应"的直接成因。
     */

    /** ★ 看门狗：启动多少 ms 内重力传感器必须出数，否则切换加速度计估计。 */
    private const val GRAVITY_WATCHDOG_MS = 600L

    /** ★★ V8 保留：低通重力估计的晃动冻结阈值 —— 剧烈挥动中冻结重力估计。 */
    private const val GRAVITY_FREEZE_DELTA = 3.0f

    /**
     * ★★ V11：收向时间常数（秒）。回正后输出 ~tau 秒衰减到 37%。
     *
     * V10 用 0.3s（300ms）—— 用户反馈"停不住立刻回正了"。V11 提到 0.6s，
     * 给"球慢慢滚回中心"的手感更长一段缓冲；与 V11 lin() 符号方向性修复
     * 配合后，攻向（按住倾斜）仍即时跟随、收向（松开回正）有约 600ms 的
     * 平滑收尾，自然不突兀。
     */
    private const val RELEASE_TAU = 0.6f

    /** 挥动触发阈值（线性加速度幅值，m/s²）。v1.4 基准 0.8；增益在 clean() 内除。 */
    private const val SWING_THRESHOLD = 0.8f

    /** 挥动满强度阈值（线性加速度幅值；v1.4 基准 3.5）。 */
    private const val SWING_FULL = 3.5f

    /** ★★ 推/拉（前后晃动的线性加速度分量）独立阈值。v1.4 基准 0.55/2.2。 */
    private const val SWING_FB_THRESHOLD = 0.55f
    private const val SWING_FB_FULL = 2.2f

    /** 摇晃触发阈值（瞬时角速度变化，rad/s；v1.4 基准 2.2）。 */
    private const val SHAKE_THRESHOLD = 2.2f

    /** 摇晃满强度阈值（v1.4 基准 7.0）。 */
    private const val SHAKE_FULL = 7.0f

    /** ★ 增益下限保护：挥动/摇晃阈值最低值（防高增益时噪声触发）。 */
    private const val SWING_THRESHOLD_MIN = 0.35f
    private const val SHAKE_THRESHOLD_MIN = 0.8f

    /** ★★ 传感器采样间隔估计（默认 50Hz；按事件时间戳自适应）。 */
    private var lastTimestampNs = 0L

    /** ★★ 最近一次事件间隔（秒，缺省 20ms@50Hz）—— 收向/吸附系数用。 */
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

    /** 低通后的重力（比力）估计（屏幕坐标系：X_s 右、Y_s 上、Z_s 出屏）。 */
    private val gravity = FloatArray(3)

    /** 首样本尚未初始化（true = gravity 全零，下一帧直接吸附真值）。 */
    private var gravityInitialized = false

    /** ★ V9 大理石零点（静息吸附基准；init (0,0) = 平贴零点）。 */
    private var neutralX = 0f
    private var neutralY = 0f

    /** ★ V9 是否已完成首次吸附（首次吸附前无视输出保护 —— 入场姿态错位根治）。 */
    private var everAnchored = false

    /** ★ V9 recenter() 强制吸附标志（下一帧直接吸附到当前姿态）。 */
    @Volatile private var forceRecenter = false

    /** ★ V9 看门狗：重力传感器是否出过数。 */
    @Volatile private var gravityEventSeen = false
    private var startMs = 0L

    /** 上一帧线性加速度（用于摇动检测的导数计算）。 */
    private val lastLinAccel = FloatArray(3)

    /** 上一帧陀螺角速度（用于摇动检测的导数计算）。 */
    private val lastGyro = FloatArray(3)

    /** V9 输出平滑的当前值（攻向即时 / 收向指数衰减）。 */
    private val tiltOut = FloatArray(4)

    @Volatile private var hasGravitySensor = false
    @Volatile private var hasLinearAccel = false
    @Volatile private var hasGyro = false

    /**
     * 启动体感监听（V9：MotionState sink）。
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
        // ★ V9：优先融合重力传感器（系统级低通，天然免疫挥动泄漏）+ 看门狗兜底
        val grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        // 可选传感器：没有也能跑（功能降级）
        val linAcc = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorManager = sm
        hasGravitySensor = grav != null
        hasLinearAccel = linAcc != null
        hasGyro = gyro != null
        gravityEventSeen = false
        startMs = android.os.SystemClock.elapsedRealtime()
        gravity.fill(0f)
        gravityInitialized = false
        lastLinAccel.fill(0f)
        lastGyro.fill(0f)
        tiltOut.fill(0f)
        neutralX = 0f
        neutralY = 0f
        everAnchored = false
        forceRecenter = false
        lastTimestampNs = 0L
        sampleDt = 0.02f

        val state = MotionState()
        val rotation = displayRotation

        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY -> handleGravity(event, rotation, state, sink)
                    Sensor.TYPE_ACCELEROMETER ->
                        if (hasGravitySensor) {
                            // 有融合重力：加速度计只做挥动高通源（+看门狗兜底）
                            handleAccelSwingOnly(event, rotation, state, sink)
                        } else {
                            // 无融合重力：加速度计低通估计重力 + 高通残差挥动
                            handleAccel(event, rotation, state, sink)
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

    // ------------------------------------------------------------------
    // ★★ V9 核心：大理石六方向解算 + 静息重锚
    // ------------------------------------------------------------------

    /**
     * ★★★ V11 倾斜解算修复（"六个方向没有一个对的"根治）★★★
     *
     * V10 及之前版本的核心缺陷：`lin()` 函数对输入取绝对值后做线性映射，
     * 导致 `lin(-dx)` 永远等于 `lin(dx)`，对立方向的输出【始终相等】：
     *   - tiltLeft == tiltRight（无论 dx 符号）
     *   - tiltForward == tiltBackward（无论 dy 符号）
     * 引擎 pushWiiTilt() 把这两根半轴同时下发到核心后，Ishiiruka 内部
     * 的 sBind 表对 Forward/Left 取 -1.0f、对 Backward/Right 取 +1.0f，
     * 差分 (Forward - Backward) 或 (Right - Left)【必然为 0】——
     * 即任何方向倾斜都被对消成零信号，六方向全失效。
     *
     * V11 修复：目标值按【大理石位移的符号】方向性激活，每一时刻对立
     * 方向中只有【真正朝低侧滚动的那一侧】输出非零，另一侧强制清零：
     *   - dx < 0（大理石向左）→ 只激活 tiltLeft，清零 tiltRight
     *   - dx > 0（大理石向右）→ 只激活 tiltRight，清零 tiltLeft
     *   - dy > 0（大理石向屏幕上方滚，顶边下沉）→ 只激活 tiltForward
     *   - dy < 0（大理石向怀里滚，顶边抬起）→ 只激活 tiltBackward
     * 配合 pushWiiTilt 的差分逻辑后，引擎收到带符号的真实方向信号，
     * 游戏读到正确的左右/前后倾斜分量 —— "横放左右倾斜无反应" /
     * "前后翻转变成右倾斜" / "停不住立刻回正" 三大现象均源于此 bug，
     * 一次修复。
     *
     * 模型不变（与 V10 同）：大理石位移 = -(比力屏幕面内分量)/g，
     * 永远向低的一侧滚；零点在首次启动/手动回正时锚定；不对称平滑
     * （攻向即时、收向按 RELEASE_TAU 指数衰减）保留。
     */
    private fun emitTilt(state: MotionState, sink: (MotionState) -> Unit) {
        // ---- 1) 大理石位移（-1..1）----
        val g = 9.81f
        val marbleX = (-gravity[0] / g).coerceIn(-1f, 1f)
        val marbleY = (-gravity[1] / g).coerceIn(-1f, 1f)

        // ---- 2) V10 零点：首次启动 / 手动回正才锚定；之后固定不漂移 ----
        if (forceRecenter) {
            // 手动回正：零点吸附到当前姿态 → 输出随收向平滑归零
            neutralX = marbleX
            neutralY = marbleY
            forceRecenter = false
            everAnchored = true
        } else if (!everAnchored) {
            // 首次进入：以当前握持姿态为零点（入场姿态不误报为倾斜）
            neutralX = marbleX
            neutralY = marbleY
            everAnchored = true
        }
        // ★ 零点固定后：只要姿态偏离零点，输出就一直保持；
        //   回到零点附近输出自然归零 —— "手机不回正他也不回正" ✓

        // ---- 3) 四方向目标值（角度线性映射）----
        // ★★ V11：符号方向性激活 —— 一次只激活对立方向中的一侧，
        //   避免左右/前后同时输出相等值导致引擎差分对消成零。
        val full = (MARBLE_FULL / sensitivityGain).coerceAtLeast(MARBLE_FULL_MIN)
        val dx = marbleX - neutralX
        val dy = marbleY - neutralY
        fun lin(v: Float): Float {
            val a = kotlin.math.abs(v)
            return if (a < MARBLE_DEADZONE) 0f
            else ((a - MARBLE_DEADZONE) / (full - MARBLE_DEADZONE)).coerceIn(0f, 1f)
        }
        // ★ V11 符号方向性激活：lin() 内部对 |v| 做归一化，不再让
        //   lin(-dx) 与 lin(dx) 同时输出；只有真正朝低侧滚的那一侧
        //   才被激活。dx/dy 为正/负的方向见各分支注释。
        val target = floatArrayOf(
            if (dx < 0f) lin(-dx) else 0f,    // 左倾：仅 dx < 0（大理石向左滚）时激活
            if (dx > 0f) lin(dx) else 0f,    // 右倾：仅 dx > 0（大理石向右滚）时激活
            if (dy > 0f) lin(dy) else 0f,    // 前倾：仅 dy > 0（顶边下沉、大理石向屏幕上方滚）时激活
            if (dy < 0f) lin(-dy) else 0f    // 后倾：仅 dy < 0（顶边抬起、大理石向怀里滚）时激活
        )
        // ---- 4) 不对称平滑：攻向即时 / 收向 ~600ms 指数衰减 ----
        val decay = kotlin.math.exp(-sampleDt / RELEASE_TAU)
        for (i in 0 until 4) {
            val cur = tiltOut[i]
            tiltOut[i] = if (target[i] >= cur) target[i]
            else target[i] + (cur - target[i]) * decay
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

    /**
     * 处理融合重力传感器（最优路径：天然分离重力与运动）。
     *
     * ★★ V11.2 中心点稳定性加固：用户反馈"中心点（回正点）不太稳"。
     *   即使是系统融合的 TYPE_GRAVITY 也有传感器噪声（每帧抖动 ~0.1 m/s²），
     *   marble 数学把这种抖动直接放大到倾斜值，导致零点附近持续微抖动。
     *   修复：对融合重力也跑低通滤波（与 handleAccel 一致的 alpha=0.2），
     *   时间常数 ~5 个采样周期 ≈ 100ms @ 50Hz —— 既过滤高频抖动、又保留
     *   用户主动倾斜的实时响应。首样本直接吸附（避免低通从 0 爬升的瞬态）。
     */
    private fun handleGravity(
        event: SensorEvent,
        rotation: Int,
        state: MotionState,
        sink: (MotionState) -> Unit
    ) {
        val (gxS, gyS) = toScreen(event.values[0], event.values[1], rotation)
        val gzS = event.values[2]
        val alpha = 0.2f  // 低通时间常数 ~5 帧 @ 50Hz ≈ 100ms
        if (!gravityInitialized) {
            gravity[0] = gxS; gravity[1] = gyS; gravity[2] = gzS
            gravityInitialized = true
        } else {
            gravity[0] = alpha * gxS + (1 - alpha) * gravity[0]
            gravity[1] = alpha * gyS + (1 - alpha) * gravity[1]
            gravity[2] = alpha * gzS + (1 - alpha) * gravity[2]
        }
        gravityEventSeen = true
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
        // ★★ 晃动冻结：剧烈运动中（|模长-g| 超阈值）冻结重力估计，
        //   挥动结束后恢复 —— 晃动绝不漏进倾斜。
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

    /**
     * ★ V9 重构：加速度计回调统一入口（无论融合重力是否出数都会到达）。
     * 有融合重力但无独立线性加速度时：加速度计只做挥动高通源；
     * 同时跑【看门狗】：启动 GRAVITY_WATCHDOG_MS 内 TYPE_GRAVITY 一直
     * 没出数（注册成功但哑火的机型）→ 自动降级为加速度计低通估计重力，
     * 倾斜通道永不哑火。
     */
    private fun handleAccelSwingOnly(
        event: SensorEvent,
        rotation: Int,
        state: MotionState,
        sink: (MotionState) -> Unit
    ) {
        // ---- 看门狗：融合重力哑火 → 降级 ----
        if (!gravityEventSeen &&
            android.os.SystemClock.elapsedRealtime() - startMs > GRAVITY_WATCHDOG_MS) {
            android.util.Log.w("WiiMotionSensors",
                "TYPE_GRAVITY registered but silent; degrade to accelerometer gravity estimation")
            hasGravitySensor = false
            gravityInitialized = false
            // 落回 handleAccel 路径处理本帧与后续帧
            handleAccel(event, rotation, state, sink)
            return
        }
        val (gxS, gyS) = toScreen(event.values[0], event.values[1], rotation)
        val gzS = event.values[2]
        val linX = gxS - gravity[0]
        val linY = gyS - gravity[1]
        val linZ = gzS - gravity[2]
        if (hasLinearAccel.not()) {
            updateSwingFromLinear(linX, linY, linZ, state)
            try { sink(state) } catch (_: Throwable) {}
        }
    }

    /** 处理线性加速度（去重力后的纯运动）：驱动挥动 + 活动检测。 */
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
            // ★ 摇晃阈值同除灵敏度增益（钳位 ≥ SHAKE_THRESHOLD_MIN）
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

    /** 处理陀螺仪（角速度）：精确的摇动检测 + 活动检测。 */
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
            // ★ 摇晃阈值同除灵敏度增益（钳位 ≥ SHAKE_THRESHOLD_MIN）
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
     * ★ V3 方向修正保留：屏幕坐标 Y_s 向上 —— lyS > 0 = 向上甩 → swingUp；
     *   lzS > 0 = 向自己拉（Z_s 出屏朝用户）→ swingBackward。
     */
    private fun updateSwingFromLinear(
        lxS: Float, lyS: Float, lzS: Float,
        state: MotionState
    ) {
        fun clean(v: Float): Float {
            val a = kotlin.math.abs(v)
            // ★ 挥动阈值/满程同除灵敏度增益
            val th = (SWING_THRESHOLD / sensitivityGain).coerceAtLeast(SWING_THRESHOLD_MIN)
            val full = (SWING_FULL / sensitivityGain).coerceAtLeast(th * 2f)
            return if (a < th) 0f
            else ((a - th) / (full - th))
                .coerceIn(0f, 1f)
        }
        // 屏幕坐标：X_s 右、Y_s 上、Z_s 出屏朝用户
        state.swingRight = clean(lxS)
        state.swingLeft = clean(-lxS)
        state.swingUp = clean(lyS)
        state.swingDown = clean(-lyS)
        state.swingForward = cleanFbSwing(-lzS)   // 向前推（远离自己）
        state.swingBackward = cleanFbSwing(lzS)   // 向自己拉
    }

    /** ★ 推/拉独立标尺（同除灵敏度增益）。 */
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
        neutralX = 0f
        neutralY = 0f
        everAnchored = false
        forceRecenter = false
        gravityEventSeen = false
    }

    fun isRunning(): Boolean = listener != null

    /**
     * ★★ V9：手动回正 —— 立即把零点吸附到当前姿态（输出随收向平滑归零）。
     * V8 时代因固定锚点改为无操作；V9 静息吸附模型下恢复真实语义
     * （用户感觉方向基准歪了时可主动触发，无需退出重进）。
     */
    fun recenter() {
        forceRecenter = true
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
