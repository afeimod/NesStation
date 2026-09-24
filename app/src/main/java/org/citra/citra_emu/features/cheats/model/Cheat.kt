package org.citra.citra_emu.features.cheats.model

import androidx.annotation.Keep

/**
 * Azahar 金手指模型 —— JNI 契约桩。
 *
 * 原生侧（src/android/app/src/main/jni/id_cache.cpp）在 JNI_OnLoad 时
 * FindClass("org/citra/citra_emu/features/cheats/model/Cheat") 并读取
 * `mPointer:J` 字段 / `(J)V` 构造器。NesStation 宿主不使用原版金手指编辑器，
 * 仅提供契约实现保证库能正常加载。金手指请使用上游 Azahar 应用管理。
 */
@Keep
class Cheat(pointer: Long) {
    @JvmField
    var mPointer: Long = pointer
}
