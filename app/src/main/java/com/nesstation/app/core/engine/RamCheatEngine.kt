package com.nesstation.app.core.engine

/**
 * ★★ v69 全核心 RAM 金手指（RetroArch 同款前端补丁引擎）。
 *
 * 「其他模拟器默认都有金手指（cheat）支持，只是本项目没接入」—— RetroArch /
 * 小马等前端的做法：金手指引擎住在**前端**，核心只暴露内存。本接口就是
 * "核心暴露内存"这一侧的 Kotlin 面：实现方把补丁列表直通到各自核心的
 * JNI（cheatSetList），原生侧在每帧 runFrame 前把补丁重放到 RAM
 * （游戏自己改写 RAM 也会被下一帧再补上 —— 无限血/无限币的标准做法）。
 *
 * 补丁列表布局（5 个 Int 一组，与 core/jni/shared/cheat_engine.h 严格对应）：
 *   [0] enabled (0/1)
 *   [1] 地址 —— libretro 核心 = SYSTEM_RAM/SAVE_RAM 区域偏移（总线地址
 *       已由 UI 层按平台归一化，见 CheatsPanel.normalizeCheatAddress）；
 *       NES(fceux) = CPU 总线地址 $0000-$FFFF
 *   [2] 值
 *   [3] 字节数（1/2/4，小端）
 *   [4] 位掩码（0 = 整段写入；非 0 按位读-改-写，RetroArch
 *       cheat_address_bit_position 语义）
 *
 * 地址越界条目由原生侧静默跳过（绝不写野指针）；空数组 = 清空全部补丁。
 *
 * 实现者：NesEngine(fceux) / SnesEngine(snes9x) / GbaEngine(mgba) /
 * GenesisEngine(genesis_plus_gx) / PceEngine(geargrafx) / PsxEngine(
 * pcsx_rearmed) / DcEngine(flycast) / NdsEngine(melonDS)。
 * UI 入口：CheatsPanel.RamCheatsContent（读 RetroArch .cht 金手指文件）。
 */
interface RamCheatEngine {

    /**
     * 下发补丁列表（覆盖式）。[list] 为空数组时清空全部金手指。
     * 线程安全：原生侧整表换入，模拟线程按旧表或新表重放，均可。
     */
    fun pushCheatPatchList(list: IntArray)
}
