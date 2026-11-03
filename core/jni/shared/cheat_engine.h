// SPDX-License-Identifier: MIT
//
// ★★ v69 全核心 RAM 金手指（RetroArch 同款前端补丁引擎）
//
// 「其他模拟器默认都有金手指支持，只是本项目没加入」—— RetroArch / 小马等
// 前端的做法：金手指引擎在**前端**，核心只暴露内存（libretro 标准接口
// retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM)；fceux 走 cheat 总线读写）。
// 本头文件就是那个前端引擎，被 8 个核心桥共享（NES/SFC/GB/GBA/MD/PCE/
// PSX/DC/NDS —— 各 .so 一份实例，互不干扰）：
//
//   Kotlin UI（CheatsPanel 的 RamCheatsContent，读 RetroArch .cht 文件）
//     → Native.cheatSetList(IntArray)     —— 一次性下发补丁列表
//     → 每帧 runFrame 前调 cheatApply*    —— 游戏改写 RAM 后下一帧再补上
//
// 列表布局（5 个 int32 一组，与 Kotlin pushCheatPatchList 严格对应）：
//   [0] enabled (0/1)      [1] address（区域偏移 / fceux CPU 总线地址）
//   [2] value              [3] size（1/2/4 字节，小端）
//   [4] bit mask（0 = 整段写入；否则按位读-改-写，RetroArch
//       cheat_address_bit_position 语义）
//
// 地址越界的条目**静默跳过**（RetroArch 同款行为 —— 地址解析不到就无效，
// 绝不写野指针）；不同核心暴露的 RAM 区域大小不同（如 mGBA SYSTEM_RAM =
// EWRAM 256KB，snes9x = WRAM 128KB），总线地址 → 区域偏移的归一化在
// Kotlin 侧按平台完成（见 CheatsPanel.kt normalizeCheatAddress）。
#pragma once

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

namespace coreshared {

// Flat cheat list shared between cheatSetList (writer, UI thread) and
// cheatApply* (reader, emulation thread). A std::mutex guards the swap:
// uncontended lock is ~20ns per frame (negligible), and it removes any
// torn-read window while the UI thread replaces the list mid-frame.
inline std::mutex g_cheatMutex;
inline std::vector<int32_t> g_cheatList;

// Store a new cheat list (JNI export body). `list` may be empty / null to
// clear all cheats (used when leaving a game or resetting the panel).
inline void cheatSetList(JNIEnv* env, jintArray list) {
    std::lock_guard<std::mutex> lk(g_cheatMutex);
    g_cheatList.clear();
    if (!env || !list) return;
    const jsize n = env->GetArrayLength(list);
    if (n <= 0) return;
    std::vector<int32_t> tmp(static_cast<size_t>(n));
    env->GetIntArrayRegion(list, 0, n, tmp.data());
    // Defensive: drop a trailing partial entry (Kotlin always sends ×5).
    tmp.resize(static_cast<size_t>(n) / 5 * 5);
    g_cheatList.swap(tmp);
}

// Fast per-frame check — true when a cheat list is present.
inline bool cheatActive() {
    std::lock_guard<std::mutex> lk(g_cheatMutex);
    return !g_cheatList.empty();
}

// ---------------------------------------------------------------------------
// libretro cores: apply against retro_get_memory_data regions.
//
// System RAM is the primary target (NES $0000-$07FF, SNES WRAM, mGBA EWRAM,
// genesis work RAM, PSX main RAM ...). Save RAM is the fallback so battery
// WRAM cheats (NES $6000-$7FFF on many carts) still land. Both regions come
// from the core itself — NULL/0 for cores that don't implement a region.
// ---------------------------------------------------------------------------
inline void cheatApplyRegions(void* sysRam, size_t sysSize,
                              void* saveRam, size_t saveSize) {
    std::lock_guard<std::mutex> lk(g_cheatMutex);
    if (g_cheatList.empty()) return;
    for (size_t i = 0; i + 4 < g_cheatList.size(); i += 5) {
        if (g_cheatList[i] == 0) continue;
        const int32_t addr  = g_cheatList[i + 1];
        const int32_t value = g_cheatList[i + 2];
        const int32_t sz    = g_cheatList[i + 3];
        const int32_t mask  = g_cheatList[i + 4];
        if (sz != 1 && sz != 2 && sz != 4) continue;
        if (addr < 0) continue;
        uint8_t* base = nullptr;
        if (sysRam && static_cast<size_t>(addr) + static_cast<size_t>(sz) <= sysSize) {
            base = static_cast<uint8_t*>(sysRam);
        } else if (saveRam && static_cast<size_t>(addr) + static_cast<size_t>(sz) <= saveSize) {
            base = static_cast<uint8_t*>(saveRam);
        } else {
            continue;  // resolves to neither region — skip (RetroArch semantics)
        }
        uint8_t* p = base + static_cast<size_t>(addr);
        for (int b = 0; b < sz; b++) {
            const uint8_t laneVal = static_cast<uint8_t>((value >> (8 * b)) & 0xFF);
            const uint8_t laneMask = (mask == 0)
                ? static_cast<uint8_t>(0xFF)
                : static_cast<uint8_t>((mask >> (8 * b)) & 0xFF);
            if (laneMask == 0) continue;
            const uint8_t cur = p[b];
            p[b] = static_cast<uint8_t>((cur & ~laneMask) | (laneVal & laneMask));
        }
    }
}

// ---------------------------------------------------------------------------
// fceux (NES): byte-bus accessors. FCEU_CheatGetByte / FCEU_CheatSetByte are
// the exact entry points fceux's own cheat engine uses — they handle every
// mapper's address decoding (ARead/BWrite tables + cheat RAM pointer pages),
// so NES cheats use plain CPU bus addresses ($0000-$FFFF) with no per-platform
// normalization.
// ---------------------------------------------------------------------------
template <typename ReadByte, typename WriteByte>
inline void cheatApplyByteBus(ReadByte readByte, WriteByte writeByte) {
    std::lock_guard<std::mutex> lk(g_cheatMutex);
    if (g_cheatList.empty()) return;
    for (size_t i = 0; i + 4 < g_cheatList.size(); i += 5) {
        if (g_cheatList[i] == 0) continue;
        const int32_t addr  = g_cheatList[i + 1];
        const int32_t value = g_cheatList[i + 2];
        const int32_t sz    = g_cheatList[i + 3];
        const int32_t mask  = g_cheatList[i + 4];
        if (sz != 1 && sz != 2 && sz != 4) continue;
        if (addr < 0 || addr > 0xFFFF) continue;
        for (int b = 0; b < sz; b++) {
            const uint32_t a = static_cast<uint32_t>(addr + b);
            const uint8_t laneVal = static_cast<uint8_t>((value >> (8 * b)) & 0xFF);
            const uint8_t laneMask = (mask == 0)
                ? static_cast<uint8_t>(0xFF)
                : static_cast<uint8_t>((mask >> (8 * b)) & 0xFF);
            if (laneMask == 0) continue;
            const uint8_t cur = static_cast<uint8_t>(readByte(a));
            writeByte(a, static_cast<uint8_t>((cur & ~laneMask) | (laneVal & laneMask)));
        }
    }
}

}  // namespace coreshared
