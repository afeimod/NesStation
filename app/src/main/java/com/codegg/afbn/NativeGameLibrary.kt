package com.codegg.afbn

/**
 * ★★ v55 afbn 引擎 JNI 绑定桩（驱动列表查询）。
 *
 * libarcade.so 的 JNI 静态符号按「声明类全限定名」绑定 —— 本类的包名/类名必须
 * 与核心编译时的绑定类一致（com.codegg.afbn.NativeGameLibrary）。
 * 这里只声明路由判定需要的最小子集（nativeGetDriverList），其余入口
 * （nativeCheckRomSet/nativeGetRomInfoList 等）按需在后续版本补声明。
 *
 * 反编译核实（参考 APK NativeGameLibrary.ensureLoaded）：
 * 加载顺序 SDL2 → SDL2_image → SDL2_mixer → SDL2_net → SDL2_ttf →
 * mikmod → smpeg2 → arcade；加载后若已有 pendingSigningDigest 则回调
 * nativeSetAppIntegrity。本应用按同顺序加载（见 AfbnEngine.ensureLoaded），
 * integrity 用 SDLActivity 绑定传递。
 */
class NativeGameLibrary private constructor() {

    companion object {
        /**
         * 驱动列表（原生核心内置表）。
         * 记录分隔符 = 0x1E（\x1e）；字段分隔符 = 0x1F（\x1f）；每条 ≥13 字段：
         * [0]标题 [1]驱动名 [2]父集 [3]注释 [4]厂商 [5]系统 [6]年份
         * [7]状态int [8]按键数int [9]宽 [10]高 [11]屏幕类型 [12]硬件码int
         * （反编译 loadCompatibilityList + RomInfo 构造核实）。
         */
        @JvmStatic external fun nativeGetDriverList(): String?
    }
}
