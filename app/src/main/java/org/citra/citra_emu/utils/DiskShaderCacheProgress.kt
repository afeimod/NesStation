package org.citra.citra_emu.utils

import android.util.Log
import androidx.annotation.Keep

/**
 * Azahar 磁盘着色器缓存进度 —— JNI 契约桩。
 *
 * 原生侧在着色器编译期间通过 id_cache.cpp 调用
 * `loadProgress(Lorg/citra/citra_emu/utils/DiskShaderCacheProgress$LoadCallbackStage;IILjava/lang/String;)V`，
 * 并读取内嵌枚举 LoadCallbackStage 的静态字段 Prepare / Decompile / Build / Complete。
 * NesStation 用日志 + 简单状态输出代替原版进度对话框。
 */
@Keep
object DiskShaderCacheProgress {

    enum class LoadCallbackStage {
        Prepare,
        Decompile,
        Build,
        Complete
    }

    @JvmStatic
    fun loadProgress(stage: LoadCallbackStage, progress: Int, max: Int, obj: String) {
        Log.i("AzaharNative", "ShaderCache[$stage] $progress/$max $obj")
    }
}
