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
         *  ★★ v61 语义核实（libpgm3 二进制反汇编）：nativeRun 是【同步阻塞】
         *  调用 —— 在调用线程内跑完整场模拟，直到会话结束（游戏退出/
         *  nativeStop）才返回：0 = 会话正常结束；-1 = 初始化失败（详情见
         *  <root>/interactive.log）；-2 = 已有会话在运行（核心互斥标志）。
         *  必须放在独立工作线程调用（参考实现 Thread("KOV3HD") 同语义）。
         *  nativeStatus 亦可在运行中查询。 */
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

        /** ★★ v59 记忆卡子系统配置（必须在 nativeRun 之前调用 —— 对齐参考实现
         *  Pgm3CardDialog.configure：dir = 记忆卡目录（如 <root>/memcard/kov3hd），
         *  返回 false = 初始化失败，参考实现直接中止启动）。 */
        @JvmStatic external fun nativeCardConfigure(dir: String): Boolean

        /** 记忆卡子系统最近一次错误描述（nativeCardConfigure 失败时给出原因）。 */
        @JvmStatic external fun nativeCardError(): String?

        /** ★★ v59 金手指配置（nativeRun 前调用 —— 对齐参考实现 Pgm3CheatDialog
         *  .configure：configPath = 金手指 ini 路径，文件不存在是合法状态）。 */
        @JvmStatic external fun nativeCheatConfigure(configPath: String): Boolean

        // ★★ v68 金手指运行时（对齐参考实现 Pgm3CheatDialog 的游戏内菜单）：
        //   签名经 libpgm3.so 反汇编核实（Java_..._nativeCheatGetList 调
        //   Cheats::list → JSON 串；nativeCheatSetOption(entry, option) 调
        //   Cheats::select；nativeCheatResetAll 调 Cheats::reset）。

        /** 当前游戏可用金手指清单（JSON）：
         *  {"path":"...","error":"...","entries":[
         *    {"name":"...","options":["...","..."],"selected":N,"source":"..."}]}
         *  entries 顺序 = ini 里 cheat 声明顺序；selected = 当前选项下标。
         *  必须在会话运行中调用（核心 attach 到游戏 ELF 后才有内容）。 */
        @JvmStatic external fun nativeCheatGetList(): String?

        /** 选中金手指条目 [entry] 的第 [option] 个选项（0 = 通常为关闭）。
         *  返回 false = 参数越界 / 应用失败（原因见 nativeCheatError）。 */
        @JvmStatic external fun nativeCheatSetOption(entry: Int, option: Int): Boolean

        /** 全部金手指恢复默认（默认通常 = 0 = 关闭）。 */
        @JvmStatic external fun nativeCheatResetAll()

        /** 金手指子系统最近一次错误描述（诊断用）。 */
        @JvmStatic external fun nativeCheatError(): String?

        // ★★ v68 虚拟插卡（IGS 记忆卡 —— 三国战记3HD 的武将进度/装备/功勋
        //   存在街机 IC 卡上，本接口 = 虚拟 IC 卡的建/选/插/弹/存，对齐参考
        //   实现 Pgm3CardDialog。签名经 libpgm3.so 反汇编核实：
        //   nativeCardAction(action, player, name) 分派
        //   createVirtualCard / enableVirtualCard(true|false) / saveVirtualCard /
        //   selectVirtualCard；nativeCardStatus 返回状态位掩码；
        //   nativeCardPath 返回当前卡文件路径。）

        /** 卡操作。[action]：0 = 新建卡（[name] = 完整卡文件路径）；
         *  1 = 插卡（启用当前选中的卡）；2 = 弹卡；3 = 存卡（落盘）；
         *  4 = 选卡（[name] = 已存在的卡文件路径）。
         *  [player] 0/1（1P/2P 各一张）。[name] 仅 0/4 需要。
         *  返回 false = 失败（原因见 nativeCardError）。 */
        @JvmStatic external fun nativeCardAction(action: Int, player: Int, name: String?): Boolean

        /** 卡状态位掩码：bit0 = 有卡名、bit1 = 已插入、bit2/3 = 子系统状态。
         *  0 = 该玩家位无卡。 */
        @JvmStatic external fun nativeCardStatus(player: Int): Int

        /** 当前选中的卡文件路径（无卡 = 空串）。 */
        @JvmStatic external fun nativeCardPath(player: Int): String?

        /** ★★ v62 应用完整性初始化（对齐参考实现 afbn Pgm3Activity.onCreate：
         *  loadLibrary 成功后立即 nativeLuaConfigure(AppIntegrity.getSigningDigest(this),
         *  packageName) —— 32 字节签名摘要 + 包名；核心据此开启受保护 Lua 脚本
         *  功能。签名不匹配时核心仅关闭受保护脚本，不影响游戏本体运行；
         *  本应用与 afbn 签名不同属预期，调用失败静默忽略）。 */
        @JvmStatic external fun nativeLuaConfigure(digest: ByteArray?, packageName: String?)
    }
}
