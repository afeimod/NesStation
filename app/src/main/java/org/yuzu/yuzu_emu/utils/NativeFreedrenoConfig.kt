// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// ★ NesStation 集成版契约 —— 与上游
//   Java_org_yuzu_yuzu_1emu_utils_NativeFreedrenoConfig_* 对应。
//   Freedreno/Turnip 用户态驱动路径配置（Adreno 设备的 Mesa 系驱动
//   场景）。引擎在 GPU 驱动初始化时调用。

package org.yuzu.yuzu_emu.utils

object NativeFreedrenoConfig {
    external fun setFreedrenoBasePath(path: String)
    external fun initializeFreedrenoConfig()
    external fun saveFreedrenoConfig()
    external fun reloadFreedrenoConfig()
}
