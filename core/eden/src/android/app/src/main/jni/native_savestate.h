// SPDX-FileCopyrightText: Copyright 2026 NesStation / Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// ★★★ NesStation 集成：Switch（Eden）核心即时存档 —— 原生槽位契约 v3 ★★★
//
// v8 更新（详见 native_savestate.cpp 文件头）：
//   · 原生槽位扩到 10 个（与通用 SlotPickerDialog 的 0..9 文件槽位
//     恒等映射 —— 旧版 UI 槽位 6..9 经 %6+1 映射复用原生槽 1..4，
//     造成「5678 槽从没存过却显示有存档」的幽灵槽位；现存 slot1..slot6
//     目录与旧存档完全兼容，无需重新存档）；
//   · 手术物料预建改为「逐块构建-校验-释放」（v7 的全量预建在堆碎片化
//     后 PARun 总数超过 BlockInfo slab 容量（4000）时必失败 —— 同会话
//     「读档失败：状态恢复失败」的主因）；
//   · 撕裂（Torn）状态不再把玩家留在黑屏死核上：只要槽位有 NAND 存档
//     数据就自动回落 legacy 重启恢复；
//   · 新增 forceLegacyRestore：前端看门狗检测到原地恢复后核心无帧输出
//     时直接走 legacy 路径（不再次尝试原地恢复）。
//
// v7 更新（详见 native_savestate.cpp 文件头）：
//   · 同会话读档失败（unmap 0xd401）已修复 —— nvmap 的 DeviceShared
//     簿记由 SavestatePrepareForReplay（内核侧配套）随读档手术复位；
//   · 跨会话（重启游戏后）读档不再必然回落重启 —— 地址空间布局
//     确定性化（内核区域放置 / loader aslr_offset / NCE 预留基址三处
//     配套修改），同一游戏 + 同一配置每次启动布局一致 = Cemu
//     「静态 MMURange」前提成立，旧会话槽位同样原地恢复；
//   · 槽位格式（EDSS v6）与 v6 完全兼容：现存槽位无需重新存档
//     （旧版随机布局时代保存的槽位会因布局不兼容干净回落 legacy）。
//
// v2 新增（对齐 Cemu 的原地恢复语义 —— 与
// core/cemu/src/android/app/src/main/cpp/NativeQuickSave.cpp 逐块对应）：
//
//   · 槽位布局：<EdenDir>/savestates/<programId016x>/{slot1..slot10,autobackup}
//     每槽 = thumb.tga（缩略图）+ info.txt（时间戳）
//            + state.bin（★ v2：进程内全量状态 —— 原地恢复载体）
//            + savedata/（该作 NAND 用户存档快照 —— 冷启动后备，
//              account/device 两种布局均覆盖）
//
//   · 存档（quickSaveSlot）：截图（运行中）→ EmulationFreeze
//     （System::Pause → GPU().WaitForIdle() → GPU().ObtainContext()，
//      ★异步 GPU 同步：yuzu 系 GPU 在独立线程异步执行，必须先排空
//      同步栅栏；再 rasterizer->FlushAll() 把在途 GPU 写回刷入 guest
//      内存，保证捕获一致性）→
//      ① 页表 QueryInfo 遍历全部已映射区段，4KB 页粒度读取并按 zstd
//         压缩写入 state.bin；
//      ② 全部 guest 线程的完整寄存器组（Svc::ThreadContext，0x320 字节：
//         X0-X30/SP/PC/PSTATE/V0-V31/FPCR/FPSR/TPIDR）逐线程落盘。
//     再按标题快照 NAND 存档 → 解冻恢复运行。
//
//   · 读档（quickRestoreSlot）：state.bin 存在 → ★ 原地恢复（首选路径，
//     与 Cemu RestoreStateFromFile 语义一致）：
//       冻结 → 验证魔数/版本/程序 ID → 逐区段解压写回 guest 内存
//       （写回后 rasterizer->InvalidateGPUCache() 使纹理/缓冲等宿主
//       GPU 缓存从还原后的内存重建；逐 ARM 核 ClearInstructionCache()
//       + 逐页 __builtin___clear_cache 保证 NCE/dynarmic 指令缓存一致）
//       → 按 thread_id 匹配写回各线程寄存器组 → 解冻 → 返回
//       RESULT_OK_RESTORED_IN_PLACE —— 游戏从存档时刻继续，不重启。
//     恢复失败：默认报 RESULT_ERROR_STATE_RESTORE_FAILED（不静默破坏），
//     flags 置 RESTORE_FLAG_ALLOW_LEGACY 才回落 legacy 路径。
//
//   · legacy 路径（老槽位无 state.bin）：自动备份当前存档（autobackup）
//     → HaltEmulation + 等待核心关停 → 槽位存档数据写回 NAND →
//     返回 RESULT_OK，由前端 relaunchGame() 进程内重启游戏。
//
// 状态一致性依据（Eden 内核逐行核对）：
//   · KernelCore::SuspendEmulation 挂起全部线程后自旋等待所有核心
//     调度器卸下 guest 线程 —— System::Pause() 返回即代表全部寄存器
//     已停放进 KThread::m_thread_context（KScheduler::Unload →
//     PhysicalCore::SaveContext）。
//   · 恢复时仅需写回 m_thread_context：线程重回核心时
//     KScheduler::Reload → PhysicalCore::LoadContext →
//     interface->SetContext(m_thread_context)（dynarmic/NCE 同一契约）。
//   · HLE 服务（vsync/音频/HID…）运行在宿主真实时间轴上，原地恢复后
//     自然继续触发事件并唤醒等待线程 —— 无需冻结时间轴。
//
// 结果码 / JSON 槽位信息 schema 与 Cemu 的 NativeQuickSave 逐字段一致，
// 前端（EdenEngine / EmulatorScreen）按同一套 RESULT_* 分支处理
// （RESULT_OK_RESTORED_IN_PLACE 分支前端已预置，v2 起真正可用）。

#pragma once

#include <jni.h>

namespace native_savestate {

// Result codes —— 与 Cemu NativeQuickSave 契约一致
// （Java 侧 org.yuzu.yuzu_emu.NativeLibrary 常量一一对应）
constexpr int RESULT_OK = 0;
constexpr int RESULT_ERROR_NOT_RUNNING = 1;
constexpr int RESULT_ERROR_NO_SAVE_DATA = 2;
constexpr int RESULT_ERROR_SLOT_EMPTY = 3;
constexpr int RESULT_ERROR_IO = 4;
// v2 已实现：state.bin 进程内全量原地恢复（= Cemu RestoreStateFromFile）
constexpr int RESULT_OK_RESTORED_IN_PLACE = 5;
constexpr int RESULT_ERROR_STATE_RESTORE_FAILED = 6;
// v4 新增：当前会话的内存布局/线程集与存档不一致，原地恢复不可行，
// native 已自动回落 legacy 路径（关停游戏 + NAND 还原，语义同 RESULT_OK
// —— 前端同样走 relaunchGame()），以此码让 toast 如实提示
// 「内存布局已变化，已重启游戏加载」。
constexpr int RESULT_OK_LEGACY_RESTART = 7;

// v6 新增：槽位格式来自旧版本（v≤5），原地读档不可行 —— 需要重新
// 存档一次升级格式。不回落 legacy、不重启，前端提示「请重新存档」。
constexpr int RESULT_ERROR_STATE_TOO_OLD = 8;

// v8 新增：强制 legacy 恢复入口（forceLegacyRestore）的返回码约定与
// quickRestoreSlot 的 legacy 尾段完全一致（RESULT_OK = 数据已写回 NAND、
// 核心已关停，前端 relaunchGame()）。供前端「原地恢复成功但核心无帧
// 输出」看门狗使用：不再尝试原地恢复，直接还原存档数据 + 重启。

// restore flags
constexpr int RESTORE_FLAG_ALLOW_LEGACY = 1;

// v8：原生槽位 1..10 —— 与通用 SlotPickerDialog 的 UI 槽位 0..9 恒等
// 映射（ui + 1）。旧版为 6（UI 6..9 经 %6+1 复用 1..4 → 幽灵存档）。
// 现存 slot1..slot6 目录数据不动，slot7..slot10 为新增空槽。
constexpr int kMaxSlots = 10;
constexpr int kAutoBackupSlot = -1; // 读档覆盖前的安全备份槽

} // namespace native_savestate
