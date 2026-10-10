package com.nesstation.app.core.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * ★★★ V12（本轮，回应用户"前后和上下晃动有时候会串，灵敏度也不够，
 *   左右倾斜有时候也会串的"）★★★★
 *
 *  1.【倾斜轴解耦根治】V9..V11 的 marble 模型把屏幕面内重力分量当位移：
 *     marbleX=-u.x / marbleY=-u.y。只有【平贴零点】时轴间才解耦 —— 零点
 *     锚定在任意握持角（斜靠 30°~45°）后，纯横滚（左右倾斜）会让 u.y 按
 *     cos(φ) 缩放 → 前后轴跟着漂移（"左右倾斜偶尔串出前后"）；横滚叠加
 *     时俯仰同理反向串。V12 改为【角度域精确分解】：
 *       φ = asin(u.x)              （横滚，u.x=sinφ 无俯仰耦合）
 *       ψ = asin(-u.y / cos φ)      （俯仰，除以 cosφ 抵消横滚耦合）
 *     推导：姿态 = Rx(ψ)·Ry(φ) 作用于竖直重力 →
 *       u = (sinφ, -cosφ·sinψ, cosφ·cosψ) —— 纯横滚只动 φ、纯俯仰只动 ψ，
 *     任意握持角下两轴数学上严格解耦。输出 = 相对零点的角度线性映射
 *     （死区 2.5° → 满程 16°/增益），小角度线性度也比 marble 模型更好。
 *
 *  2.【挥动防串（前后↔上下）】旧实现六向分量各自独立过阈值：带弧线的
 *     推拉/提压同时点亮相邻两轴；单帧尖峰直接满幅输出。V12 三层治理：
 *       · 主导轴胜出（幅值 < 0.6×最大者的伴生分量清零，真斜向保留）；
 *       · 阈值上移（快速倾斜的旋转伪迹 ≤ ~1.5 m/s²，真实挥动 ≥ 3 ——
 *         阈值 0.8→1.2/满程 3.2，推拉 0.55→0.9/2.6，伪迹落入阈下弱区，
 *         对真实动作反而更灵敏）；
 *       · 攻向即时/收向 120ms 指数衰减包络，单帧毛刺不再直达输出。
 *
 *  3.【灵敏度】收向时间常数 0.6s→0.45s（拖尾感是"灵敏度不够"的体感
 *     成分之一）；满程角 16°/增益（默认 1.6 ≈ 10° 满程，比旧
 *     12.2° 满程更灵敏），死区 5°→2.5°。
 *
 * ------------------------------------------------------------------
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
     * ★ V12 死区角（度）：自然握持的轻微歪斜/低通残留抖动不产生输出。
     * 角度域下 2.5° 死区的中心稳定性优于旧 sin5° 死区（角度分解天然
     * 抑制轴间耦合抖动，可以放心收窄）。
     */
    private const val TILT_DEADZONE_DEG = 2.5f

    /** ★ V12 满程基准角（度，增益 1.0 时），实际满程 = 该值 ÷ 灵敏度增益。 */
    private const val TILT_FULL_DEG = 16f

    /** 满程下限保护（防灵敏度拉满时标尺退化/噪声满幅）。 */
    private const val TILT_FULL_MIN_DEG = 6f

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
     * ★★ 收向时间常数（秒）。回正后输出 ~tau 秒衰减到 37%。
     *
     * V11 用 0.6s —— 本轮（V12）收到 0.45s：用户反馈"灵敏度也不够"，
     * 600ms 拖尾是迟钝感的体感成分之一；0.45s 仍保留"球慢慢滚回中心"
     * 的缓冲，攻向（按住倾斜）依旧即时跟随。
     */
    private const val RELEASE_TAU = 0.45f

    /**
     * ★★ V12 挥动触发阈值（线性加速度幅值，m/s²）。0.8 → 1.2。
     * 依据：快速倾斜/转动手机时传感器伪迹（切向+向心加速度，r≈5-10cm）
     * 峰值 ≤ ~1.5 m/s²；真实挥动平移 3~15 m/s²。旧阈值 0.8 落在伪迹带内
     * —— 正是"倾斜时串出挥动"的成因。真实挥动在 1.2/3.2 标尺下依旧
     * 轻松满幅（对真实动作更灵敏，对伪迹更免疫）。
     */
    private const val SWING_THRESHOLD = 1.2f

    /** 挥动满强度阈值（线性加速度幅值）。 */
    private const val SWING_FULL = 3.2f

    /** ★★ 推/拉（前后晃动的线性加速度分量）独立阈值。0.55/2.2 → 0.9/2.6。 */
    private const val SWING_FB_THRESHOLD = 0.9f
    private const val SWING_FB_FULL = 2.6f

    /** ★★ V12 挥动收向时间常数（秒）：单帧毛刺不再直达输出。 */
    private const val SWING_RELEASE_TAU = 0.12f

    /** 摇晃触发阈值（瞬时角速度变化，rad/s；v1.4 基准 2.2）。 */
    private const val SHAKE_THRESHOLD = 2.2f

    /** 摇晃满强度阈值（v1.4 基准 7.0）。 */
    private const val SHAKE_FULL = 7.0f

    /** ★ 增益下限保护：挥动/摇晃阈值最低值（防高增益时噪声触发）。 */
    private const val SWING_THRESHOLD_MIN = 0.5f
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

    /** ★ V12 零点姿态角（弧度）：首次启动/手动回正时锚定，之后固定。 */
    private var neutralPhi = 0f
    private var neutralPsi = 0f

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

    /** ★ V12 挥动包络状态（U/D/L/R/F/B，攻向即时 / 收向指数衰减）。 */
    private val swingOut = FloatArray(6)

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
        swingOut.fill(0f)
        neutralPhi = 0f
        neutralPsi = 0f
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
    // ★★ V12 核心：角度域解耦六方向解算（倾斜轴互不串扰）
    // ------------------------------------------------------------------

    /**
     * ★★★ V12 倾斜解算重构（"前后和上下晃动会串 / 左右倾斜偶尔也串"根治）★★★
     *
     * V11 及之前的 marble 模型把屏幕面内重力分量直接当位移用：
     *   marbleX = -u.x, marbleY = -u.y（u = 重力/g，屏幕坐标系）
     * 该模型只有【手机平贴、零点锚在平贴位】时轴间才天然解耦。零点锚定
     * 在任意握持角（斜靠 30°~45°，横放手机游戏时的常态）后：
     *   - 纯横滚（左右倾斜）会让 u.y 按 cos(φ) 缩放 → marbleY 漂移
     *     → 左右倾斜串出前后输出（横滚到 30° 时漂移 ~13%，正落在输出
     *       线性区的可感区间）；
     *   - 横滚基础上做俯仰，u.x 也被耦合 → 前后倾反过来串出左右。
     * 这就是用户"前后和上下晃动有时候会串 / 左右倾斜有时候也会串"里
     * 倾斜通道互串的数学根源。
     *
     * V12 改为【角度域精确分解】（刚体姿态学公式，非近似）：
     *   设 u = 重力单位向量（屏幕坐标 X_s 右 / Y_s 上），
     *     横滚角 φ = asin(u.x)                —— u.x = sinφ，严格无俯仰耦合
     *     俯仰角 ψ = asin(-u.y / cos φ)        —— 除以 cosφ 抵消横滚耦合
     *   推导：姿态 = Rx(ψ)·Ry(φ) 作用于竖直重力 →
     *     u = (sinφ, -cosφ·sinψ, cosφ·cosψ)
     *   任意握持角下纯横滚只动 φ、纯俯仰只动 ψ —— 两轴数学上严格解耦。
     *
     * 输出 = (当前角 − 零点角) 的【角度】线性映射（死区 2.5° → 满程
     * 16°/灵敏度增益），比 marble 的 sin 域映射小角度线性度更好。
     * 方向约定与旧版完全一致（左侧下沉 → tiltLeft；顶边下沉 → tiltForward）。
     * 零点策略不变（首次启动 / 手动回正锚定，之后固定不漂移）；
     * 不对称平滑不变（攻向即时 / 收向按 RELEASE_TAU 指数衰减）。
     */
    private fun emitTilt(state: MotionState, sink: (MotionState) -> Unit) {
        // ---- 1) 角度分解（横滚 φ / 俯仰 ψ，弧度）----
        val g = 9.81f
        val ux = (gravity[0] / g).coerceIn(-0.999f, 0.999f)
        val uy = (gravity[1] / g).coerceIn(-0.999f, 0.999f)
        val phi = kotlin.math.asin(ux)
        // φ→±90°（屏立式）时俯仰在数学上退化：除数下限保护 + asin 域钳制。
        // 立式姿态下俯仰零点同位锚定，dPsi 仍正确（见下方减法）。
        val cosPhi = kotlin.math.cos(phi).coerceAtLeast(0.25f)
        val psi = kotlin.math.asin(((-uy) / cosPhi).coerceIn(-0.999f, 0.999f))

        // ---- 2) 零点：首次启动 / 手动回正才锚定；之后固定不漂移 ----
        if (forceRecenter) {
            // 手动回正：零点吸附到当前姿态 → 输出随收向平滑归零
            neutralPhi = phi
            neutralPsi = psi
            forceRecenter = false
            everAnchored = true
        } else if (!everAnchored) {
            // 首次进入：以当前握持姿态为零点（入场姿态不误报为倾斜）
            neutralPhi = phi
            neutralPsi = psi
            everAnchored = true
        }
        // ★ 零点固定后：只要姿态偏离零点，输出就一直保持；
        //   回到零点附近输出自然归零 —— "手机不回正他也不回正" ✓

        // ---- 3) 相对角 → 四方向目标（角度线性映射，方向性激活）----
        // ★ 符号方向性激活（V11 语义保留）：每一时刻对立方向只有
        //   真正加深的那一侧输出非零。
        //   dPhi > 0 = 左侧下沉加深（u.x = sinφ 随左倾增大）
        //   dPsi > 0 = 顶边下沉加深（u.y = -cosφ·sinψ 随前倾更负）
        val radToDeg = 57.29577951f
        val dPhi = (phi - neutralPhi) * radToDeg
        val dPsi = (psi - neutralPsi) * radToDeg
        val fullDeg = (TILT_FULL_DEG / sensitivityGain).coerceAtLeast(TILT_FULL_MIN_DEG)
        fun lin(deg: Float): Float {
            val a = kotlin.math.abs(deg)
            return if (a < TILT_DEADZONE_DEG) 0f
            else ((a - TILT_DEADZONE_DEG) / (fullDeg - TILT_DEADZONE_DEG)).coerceIn(0f, 1f)
        }
        val target = floatArrayOf(
            if (dPhi > 0f) lin(dPhi) else 0f,    // 左倾：左侧下沉
            if (dPhi < 0f) lin(-dPhi) else 0f,   // 右倾：右侧下沉
            if (dPsi > 0f) lin(dPsi) else 0f,    // 前倾：顶边下沉
            if (dPsi < 0f) lin(-dPsi) else 0f    // 后倾：顶边抬起
        )
        // ---- 4) 不对称平滑：攻向即时 / 收向指数衰减（~RELEASE_TAU）----
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
     *
     * ★★ V12 重构（"前后晃动和上下晃动互相串"根治）★★
     *
     * 旧实现把六向分量【各自独立】过阈值输出：带弧线的推拉/提压会同时
     * 点亮相邻两轴（串扰）；单帧尖峰直接满幅输出（快速倾斜的旋转伪迹
     * 直达游戏）。V12 三层治理：
     *
     *   1.【主导轴胜出】六向候选按清洗后幅值比较，只有最大者全额输出，
     *      其余幅值 < 0.6×最大者清零 —— 真实 45° 斜向仍保留次轴（≥0.6×），
     *      单轴动作的伴生毛刺分量被切除（前后↔上下互串的直接修复）；
     *   2.【阈值上移】见 SWING_THRESHOLD 注释 —— 旋转伪迹落阈下弱区；
     *   3.【包络】攻向即时 / 收向 120ms 指数衰减（swingOut 状态）——
     *      单帧尖峰不再直达输出。
     *
     * ★ V3 方向修正保留：屏幕坐标 Y_s 向上 —— lyS > 0 = 向上甩 → swingUp；
     *   lzS > 0 = 向自己拉（Z_s 出屏朝用户）→ swingBackward。
     */
    private fun updateSwingFromLinear(
        lxS: Float, lyS: Float, lzS: Float,
        state: MotionState
    ) {
        fun clean(v: Float, th: Float, full: Float): Float {
            val a = kotlin.math.abs(v)
            return if (a < th) 0f else ((a - th) / (full - th)).coerceIn(0f, 1f)
        }
        // ★ 挥动阈值/满程同除灵敏度增益（钳下限）
        val thU = (SWING_THRESHOLD / sensitivityGain).coerceAtLeast(SWING_THRESHOLD_MIN)
        val fullU = (SWING_FULL / sensitivityGain).coerceAtLeast(thU * 2f)
        val thFb = (SWING_FB_THRESHOLD / sensitivityGain).coerceAtLeast(SWING_THRESHOLD_MIN * 0.6f)
        val fullFb = (SWING_FB_FULL / sensitivityGain).coerceAtLeast(thFb * 2f)

        // 屏幕坐标：X_s 右、Y_s 上、Z_s 出屏朝用户（U/D/L/R/F/B 六候选）
        val target = floatArrayOf(
            clean(lyS, thU, fullU),            // 上挥
            clean(-lyS, thU, fullU),           // 下挥
            clean(-lxS, thU, fullU),           // 左挥
            clean(lxS, thU, fullU),            // 右挥
            clean(-lzS, thFb, fullFb),         // 前推（远离自己）
            clean(lzS, thFb, fullFb)           // 后拉（向自己）
        )
        // ---- 1) 主导轴胜出：伴生分量（< 0.6×最大）清零 ----
        var maxV = 0f
        for (v in target) if (v > maxV) maxV = v
        if (maxV > 0f) {
            for (i in target.indices) {
                if (target[i] < maxV * 0.6f) target[i] = 0f
            }
        }
        // ---- 2) 包络：攻向即时 / 收向 120ms 指数衰减 ----
        val decay = kotlin.math.exp(-sampleDt / SWING_RELEASE_TAU)
        for (i in swingOut.indices) {
            swingOut[i] = if (target[i] >= swingOut[i]) target[i]
            else target[i] + (swingOut[i] - target[i]) * decay
        }
        state.swingUp = swingOut[0]
        state.swingDown = swingOut[1]
        state.swingLeft = swingOut[2]
        state.swingRight = swingOut[3]
        state.swingForward = swingOut[4]
        state.swingBackward = swingOut[5]
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
        swingOut.fill(0f)
        lastLinAccel.fill(0f)
        lastGyro.fill(0f)
        hasGravitySensor = false
        hasLinearAccel = false
        hasGyro = false
        lastTimestampNs = 0L
        sampleDt = 0.02f
        neutralPhi = 0f
        neutralPsi = 0f
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
