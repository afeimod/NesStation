package com.nesstation.app.core.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * ★★ 手机体感 → Wii 倾斜模拟（本轮新增）★★
 *
 * 需求原话："并加入手机体感模拟wii体感"。
 *
 * 实现：加速度计低通滤波得到重力方向 → 按屏幕旋转角换算到**屏幕坐标系**
 * → 与开启时的基准姿态求差 → 输出四个方向的倾斜强度（0..1）：
 *   - 左右倾（滚转）：像方向盘一样转动手机 → left / right；
 *   - 前后倾（俯仰）：手机顶端向前推/向后拉 → forward / backward。
 *
 * 屏幕坐标推导（旋转后统一，见 start 注释）：
 *   - turn LEFT  (从用户视角逆时针绕屏幕法线) → 重力获得 -X_s 分量 → left > 0；
 *   - 手机顶边前推（屏幕朝上躺平方向）→ 重力获得 -Z_s 分量 → forward > 0。
 *
 * 使用：EmulatorScreen 在 NGC/WII + 设置开启时 start()，回调里调
 * [NgcWiiCoreEngine.setWiiMotionTilt]；离开时 stop()。全程 fail-soft
 * （无传感器的设备上 start 返回 false，功能自然退化为按钮倾斜）。
 */
object WiiMotionSensors {

    /** 倾斜满强度对应的重力分量变化（约 30°，避免小幅抖动误触发）。 */
    private const val FULL_TILT_G = 0.55f

    /** 死区（重力分量变化小于此值视为回中，防抖）。 */
    private const val DEADZONE = 0.06f

    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null

    /** 基准姿态（开启时的重力屏幕分量，玩家自然握持角）。 */
    private val baseGravity = FloatArray(3)

    /** 低通滤波后的重力（屏幕坐标系）。 */
    private val smoothed = FloatArray(3)

    @Volatile private var calibrated = false

    /**
     * 启动体感监听。
     *
     * @param displayRotation 当前显示旋转角（Surface.ROTATION_0/90/180/270），
     *        用于把设备坐标重力换算到屏幕坐标（玩家视角固定，转屏后方向
     *        依然正确）。
     * @param sink 每次传感器事件回调（已去抖），四个方向 0..1。
     * @return true 成功注册（设备有加速度计）；false 无传感器，调用方按退化处理。
     */
    fun start(context: Context, displayRotation: Int, sink: (left: Float, right: Float, forward: Float, backward: Float) -> Unit): Boolean {
        stop()
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        sensorManager = sm
        calibrated = false
        smoothed.fill(0f)
        // 屏幕坐标 → 设备坐标换算表（把重力向量旋转到屏幕视角）：
        //   屏幕坐标系：X_s 右、Y_s 下、Z_s 出屏朝用户。
        //   turn LEFT → 重力 +(-X_s)（左倾）；顶边前推 → 重力 +(-Z_s)（前倾）。
        // 由设备系 (gx, gy, gz) 得屏幕系分量（推导见类注释）：
        //   ROTATION_0  : gxS =  gx;            gyS =  gy
        //   ROTATION_90 : gxS = -gy;            gyS =  gx
        //   ROTATION_270: gxS =  gy;            gyS = -gx
        //   ROTATION_180: gxS = -gx;            gyS = -gy
        //   gzS = gz（屏幕法线不随旋转变化）
        val rotation = displayRotation
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
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
                // 低通滤波（时间常数 ~0.25s @50Hz）：隔离线性加速度（甩手）
                val alpha = 0.15f
                smoothed[0] = alpha * gxS + (1 - alpha) * smoothed[0]
                smoothed[1] = alpha * gyS + (1 - alpha) * smoothed[1]
                smoothed[2] = alpha * gzS + (1 - alpha) * smoothed[2]
                // 首帧 = 基准姿态（玩家自然握持角，相对倾斜由此起算）
                if (!calibrated) {
                    baseGravity[0] = smoothed[0]
                    baseGravity[1] = smoothed[1]
                    baseGravity[2] = smoothed[2]
                    calibrated = true
                    return
                }
                // 相对基准的差值
                fun clean(v: Float): Float {
                    val a = kotlin.math.abs(v)
                    return if (a < DEADZONE) 0f else (a - DEADZONE) / (FULL_TILT_G - DEADZONE)
                }
                val dX = smoothed[0] - baseGravity[0]   // >0 = 右倾
                val dZ = smoothed[2] - baseGravity[2]   // <0 = 前倾（顶边前推）
                val right = clean(dX)
                val left = clean(-dX)
                val forward = clean(-dZ)
                val backward = clean(dZ)
                try { sink(left, right, forward, backward) } catch (_: Throwable) {}
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        return try {
            sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_GAME)
            listener = l
            true
        } catch (_: Throwable) {
            listener = null
            false
        }
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
    }

    fun isRunning(): Boolean = listener != null
}
