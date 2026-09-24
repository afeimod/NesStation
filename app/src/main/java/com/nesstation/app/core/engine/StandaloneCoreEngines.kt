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
     * 控制模式："ngc" / "wii" / "auto"。
     */
    var controlMode: String

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
