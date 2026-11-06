// SPDX-FileCopyrightText: Copyright 2026 NesStation / Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// ★★★ NesStation 集成：Switch（Eden）核心即时存档 —— 原生槽位契约 ★★★
//
// 架构移植自 NesStation 的 Cemu（Wii U）集成
// （core/cemu/src/android/app/src/main/cpp/NativeQuickSave.{h,cpp}，
//  上游 Cemu-AZ QuickSaveSlotsDialog 语义），适配 yuzu 系（Eden）核心：
//
//   · 槽位布局：<EdenDir>/savestates/<programId016x>/{slot1..slot6,autobackup}
//     每槽 = thumb.tga（缩略图）+ info.txt（时间戳）+ savedata/（该作
//     NAND 用户存档的按标题快照 —— account/device 两种布局均覆盖）。
//
//   · 存档（quickSaveSlot）：截图（运行中）→ EmulationFreeze
//     （System::Pause → GPU().WaitForIdle() → GPU().ObtainContext()，
//      ★异步 GPU 同步：yuzu 系 GPU 在独立线程异步执行，必须先排空
//      同步栅栏再拷贝存档数据，保证数据落在 NAND 的时刻一致）→
//      按标题拷贝存档 → 解冻恢复运行。
//
//   · 读档（quickRestoreSlot）：自动备份当前存档（autobackup 槽）→
//     HaltEmulation + 等待核心关停（游戏释放全部存档文件句柄，防止
//     写回与拷贝竞争）→ 槽位存档数据写回 NAND → 返回 RESULT_OK，
//     由前端（EdenEngine.relaunchGame）进程内重启游戏加载恢复的存档
//     （= Cemu legacy 恢复路径语义）。
//
// 结果码 / JSON 槽位信息 schema 与 Cemu 的 NativeQuickSave 逐字段一致，
// 前端（EdenEngine / EmulatorScreen）按同一套 RESULT_* 分支处理。

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
// 预留：核心未来支持进程内全量状态恢复（state.bin）时启用
constexpr int RESULT_OK_RESTORED_IN_PLACE = 5;
constexpr int RESULT_ERROR_STATE_RESTORE_FAILED = 6;

// restore flags
constexpr int RESTORE_FLAG_ALLOW_LEGACY = 1;

constexpr int kMaxSlots = 6;
constexpr int kAutoBackupSlot = -1; // 读档覆盖前的安全备份槽

} // namespace native_savestate
