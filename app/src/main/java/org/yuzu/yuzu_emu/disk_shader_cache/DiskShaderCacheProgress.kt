// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later
//
// ★ NesStation 集成版契约 —— id_cache.cpp 要求：
//   FindClass("org/yuzu/yuzu_emu/disk_shader_cache/DiskShaderCacheProgress")
//   + $LoadCallbackStage + 静态 loadProgress(III)V。
//   上游通过 EmulationViewModel 驱动加载进度 UI；NesStation 前端把进度
//   转发到自己的着色器编译提示（EdenEngine.shaderCacheProgress StateFlow）。

package org.yuzu.yuzu_emu.disk_shader_cache

import androidx.annotation.Keep

@Keep
object DiskShaderCacheProgress {

    /** 由 EdenEngine 转发的进度（0..100，-1 = 无进行中的编译）。 */
    @JvmStatic
    var buildProgressPercent: Int = -1
        private set

    @JvmStatic
    fun loadProgress(stage: Int, progress: Int, max: Int) {
        val stages = LoadCallbackStage.values()
        val st = if (stage in stages.indices) stages[stage] else LoadCallbackStage.Complete
        when (st) {
            LoadCallbackStage.Prepare -> buildProgressPercent = 0
            LoadCallbackStage.Build ->
                buildProgressPercent =
                    if (max > 0) (progress * 100 + max - 1) / max else 0
            LoadCallbackStage.Complete -> buildProgressPercent = -1
        }
    }

    // Equivalent to VideoCore::LoadCallbackStage
    // ★ 闪退修复：id_cache.cpp 会 FindClass("...DiskShaderCacheProgress$
    //   LoadCallbackStage") 按名查找本枚举。@Keep 标在外层 object 上时，
    //   R8 不会保护嵌套类 —— release 混淆把本枚举改名后 FindClass 抛
    //   ClassNotFoundException → ART "No pending exception expected" SIGABRT
    //   （即"进 Switch 核心设置 / Switch 游戏启动"双闪退根因）。
    //   此处显式 @Keep 双保险（proguard-rules.pro 已整包 keep）。
    @Keep
    enum class LoadCallbackStage {
        Prepare, Build, Complete
    }
}
