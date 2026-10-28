package com.codegg.afbn.pgm3

/**
 * ★★ v52 PGM3（IGS M027 / 三国战记3 HD）独立核心 JNI 绑定桩。
 *
 * libpgm3.so（预编译，随 jniLibs/arm64-v8a 分发，38MB 自包含 —— 仅依赖
 * 系统 libandroid / libEGL / libGLESv2 / libOpenSLES / libmediandk，零 SDL）
 * 的 JNI 静态符号按「声明类全限定名」绑定 —— 本类的包名/类名必须与核心
 * 编译时的绑定类一致（com.codegg.afbn.pgm3.Pgm3Activity），与真实的
 * "Activity" 概念无关（它就是一组静态 native 方法的宿主类）。
 *
 * JNI 惰性解析：Java_ 符号在方法首次调用时才 dlsym —— 只声明实际用到的
 * 子集即可（核心另有 nativeCheat、nativeLua、nativeCard、nativeMacro、
 * nativeVideoEffect 等入口，本桩不调用，不声明也不影响加载）。
 *
 * 对齐反编译参考实现（afbn Pgm3Activity）的调用语义：
 *  - nativeRun(root, romZip, romChd, perfLog)：0 = 启动成功；
 *    非 0 时失败详情在 <root>/interactive.log（nativeStatus 亦可查询）；
 *  - nativeInput(player, mcuPad)：MCU 位格式（0x1=Start 0x2=Up 0x4=Down
 *    0x8=Left 0x10=Right 0x20=攻击1 0x40=攻击2 0x80=攻击3 0x100=攻击4）；
 *  - 投币不走 pad 位 —— Select 键按下沿触发 nativeCoin(player)；
 *  - nativeSurface(surface)：核心直接持有 ANativeWindow 用 GL 渲染；
 *    传 null 解绑（Activity 销毁前必须做）。
 */
class Pgm3Activity private constructor() {

    companion object {
        /** 启动模拟（root = 运行根目录：内含 runtime/ 数学协处理器固件）。
         *  返回 0 = 成功；非 0 = 失败（详情见 <root>/interactive.log）。 */
        @JvmStatic external fun nativeRun(root: String, romZip: String, romChd: String, perfLog: String): Int

        /** 推送玩家输入（player 0/1；mcuPad = MCU 位格式，见类注释）。 */
        @JvmStatic external fun nativeInput(player: Int, mcuPad: Int)

        /** 投币（按下沿调用一次 —— PGM3 投币不走 pad 位，走专用通道）。 */
        @JvmStatic external fun nativeCoin(player: Int)

        /** 开始脉冲（测试/服务用，正常游戏不调用）。 */
        @JvmStatic external fun nativeStartPulse(player: Int)

        /** 绑定/解绑渲染目标 Surface（GL 直接渲染；null = 解绑）。 */
        @JvmStatic external fun nativeSurface(surface: android.view.Surface?)

        /** 暂停/恢复（后台/无 Surface 时置 true —— 与参考实现同一策略）。 */
        @JvmStatic external fun nativePause(pause: Boolean)

        /** 复位核心。 */
        @JvmStatic external fun nativeReset(): Boolean

        /** 停止并卸载（退出游戏时调用；随后应 nativeSurface(null)）。 */
        @JvmStatic external fun nativeStop()

        /** 当前模拟帧号（FPS/诊断用）。 */
        @JvmStatic external fun nativeFrameCount(): Int

        /** 核心状态文本（诊断）。 */
        @JvmStatic external fun nativeStatus(): String?

        /** 快进开关（需 nativeFastForwardReady() 为 true 才生效）。 */
        @JvmStatic external fun nativeFastForward(on: Boolean)

        /** 快进是否就绪（ROM 内部引导完成后才允许快进）。 */
        @JvmStatic external fun nativeFastForwardReady(): Boolean
    }
}
