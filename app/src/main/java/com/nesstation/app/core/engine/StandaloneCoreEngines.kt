package com.nesstation.app.core.engine

/**
 * Azahar（3DS）核心引擎契约 —— 在 [EmulatorEngine] 之上补充 3DS 专属能力。
 *
 * 坐标 / 取值约定：
 *  - [setTouchInput] 接收**游戏视图像素坐标**（与上游 Azahar EmulationActivity 的
 *    行为一致 —— 原生侧根据 framebuffer 布局自行把视图坐标映射到下屏触摸区，
 *    因此任何屏幕布局 / 自定义布局都天然正确）。
 *  - [setAnalogAxes] lx/ly/rx/ry ∈ [-1, 1]，ly / ry 采用**屏幕坐标约定**
 *    （向上为负）；引擎负责转换为 Azahar 的 joystick 语义。
 */
interface AzaharCoreEngine : EmulatorEngine {

    /**
     * 3DS 下屏触摸输入（视图像素坐标）。
     */
    fun setTouchInput(x: Float, y: Float, pressed: Boolean)

    /**
     * 3DS 下屏触摸移动（视图像素坐标）。
     */
    fun setTouchMoved(x: Float, y: Float)

    /**
     * 推送双摇杆：CirclePad → (lx, ly)，C-Stick → (rx, ry)，取值 -1..1。
     */
    fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float)

    /**
     * 上下屏交换（游戏内菜单/热键用）。
     */
    fun swapScreens()

    /**
     * 核心是否已加载完成（.so 存在且 System.loadLibrary 成功）。
     */
    fun isCoreAvailable(): Boolean
}

/**
 * Ishiruka（NGC/Wii）核心引擎契约 —— 在 [EmulatorEngine] 之上补充 NGC/Wii 专属能力。
 *
 * 控制模式（[controlMode]）：
 *  - "ngc"  ：GameCube 手柄 —— ABXY/Z + L/R 模拟扳机 + Start + 主摇杆 + C 摇杆 + 十字键
 *  - "wii"  ：Wii Remote（可选扩展）—— A/B/1/2/+/−/HOME + 十字键 + IR 指针 +
 *             双节棍（C/Z + 摇杆）或经典手柄
 *  - "auto" ：按 [isGameCubeGame] 自动选择
 *
 * 坐标 / 取值约定：
 *  - [setPointer] nx/ny ∈ [0, 1]，为游戏视图内的归一化坐标（左上原点），
 *    引擎转换为 Wiimote IR 的六轴绝对输入。
 *  - [setAnalogAxes] lx/ly/rx/ry ∈ [-1, 1]（屏幕坐标约定，向上为负）；
 *    NGC 模式：主摇杆 = (lx, ly)，C 摇杆 = (rx, ry)；
 *    Wii 模式：双节棍摇杆 = (lx, ly)，(rx, ry) 忽略。
 */
interface NgcWiiCoreEngine : EmulatorEngine {

    /**
     * 推送双摇杆（语义随控制模式变化，见类注释）。
     */
    fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float)

    /**
     * Wii IR 指针（Wii 模式；NGC 模式忽略）。nx/ny ∈ [0,1]，pressed=false 时指针复位。
     */
    fun setPointer(nx: Float, ny: Float, pressed: Boolean)

    /**
     * ★★ 手机体感 → Wii 倾斜/晃动模拟。值域各方向独立 0..1：
     * left/right/forward/backward。
     * 引擎与虚拟按键的 L2/R2（左右倾斜）/L3/R3（前后晃动）**叠加**后
     * 推给核心：left/right → Wiimote Tilt 左右轴；forward/backward →
     * 同时驱动 Tilt 前后轴与 Swing 前后轴（前后"晃动"语义，兼容只读
     * Tilt 或只读 Swing 的游戏）。NGC 模式下由实现自行忽略。
     */
    fun setWiiMotionTilt(left: Float, right: Float, forward: Float, backward: Float)

    /**
     * ★★ V2 全维度体感（倾斜 + 挥动 + 摇晃）—— WiiMotionSensors.MotionState 直通。
     *
     * 比 [setWiiMotionTilt] 多驱动：
     *  - SWING_UP/DOWN/LEFT/RIGHT（120-123）—— Wii Sports 系挥拍类游戏必读轴；
     *  - SWING_FORWARD/BACKWARD（124-125）—— 顶边推/拉；
     *  - SHAKE_X/Y/Z（132-134）—— 抽搐/摇动类游戏（马里奥赛车 wheelie 等）。
     *
     * 实现应把所有非零字段与按钮倾斜叠加后一次性推送给核心（避免分批推送
     * 在核心侧产生半帧状态）。NGC 模式由实现自行忽略。
     *
     * 默认实现转调 [setWiiMotionTilt]（保持兼容），新核心引擎（如 IshirukaEngine）
     * 应覆盖此方法以驱动全部轴。
     */
    fun setWiiMotion(state: WiiMotionSensors.MotionState) {
        setWiiMotionTilt(state.tiltLeft, state.tiltRight, state.tiltForward, state.tiltBackward)
    }

    /**
     * 控制模式："ngc" / "wii" / "auto"。
     */
    var controlMode: String

    /**
     * Wii 扩展手柄（wii 模式）："nunchuk" / "classic" / "none"。
     * 决定虚拟按键布局与核心输入路由（双节棍 / 经典手柄 / 纯 Wiimote）。
     */
    var wiiExtension: String

    /**
     * 当前 ROM 是否为 GameCube 游戏（auto 模式的判定依据）。
     */
    fun isGameCubeGame(): Boolean

    /**
     * 当前生效的控制模式（解析 auto 后）。
     */
    fun effectiveMode(): String

    /**
     * 核心是否已加载完成。
     */
    fun isCoreAvailable(): Boolean
}

/**
 * Flycast（DC/NAOMI/AtomisWave）核心引擎契约 —— 在 [EmulatorEngine] 之上
 * 补充 DC 手柄模拟摇杆能力。
 *
 * Dreamcast 手柄只有一颗左侧模拟摇杆（无右摇杆）：[setAnalogAxes] 的
 * (lx, ly) 喂左摇杆，(rx, ry) 预留（外设/轮式控制器用，当前忽略）。
 * 取值 lx/ly/rx/ry ∈ [-1, 1]，屏幕坐标约定（向上为负）；引擎负责转换为
 * libretro int16 轴（−32768..32767，0 居中）。
 */
interface DcCoreEngine : EmulatorEngine {

    /**
     * 推送 DC 手柄摇杆：(lx, ly) = 左摇杆，(rx, ry) 预留位。
     */
    fun setAnalogAxes(lx: Float, ly: Float, rx: Float, ry: Float)

    /**
     * 前端帧数限制（Hz）。0 = 跟随游戏制式（NTSC 59.94 / PAL 50）不限速；
     * >0 时把模拟线程步进频率硬限制到该值（不高于核心刷新率）。
     * 部分游戏在满速下逻辑超速，用户可降到 30/50 帧恢复原手感。
     */
    fun setFrameLimit(hz: Int)
}
