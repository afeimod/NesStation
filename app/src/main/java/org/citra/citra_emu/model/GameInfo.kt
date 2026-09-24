package org.citra.citra_emu.model

import androidx.annotation.Keep

/**
 * Azahar GameInfo —— JNI 契约桩。
 *
 * 原生侧（src/android/app/src/main/jni/id_cache.cpp）读取 `pointer:J` 字段
 * （原版游戏库列表把 native GameInfo* 包在该字段里）。NesStation 游戏库走
 * 自有 RomStore，不实例化本类，仅保证 FindClass / GetFieldID 成功。
 */
@Keep
class GameInfo {
    @JvmField
    var pointer: Long = 0L
}
