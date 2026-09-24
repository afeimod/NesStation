package org.citra.citra_emu.utils

import androidx.annotation.Keep

/**
 * Azahar CIA 安装进度 —— JNI 契约桩。
 *
 * 原生侧（id_cache.cpp）FindClass 本类并 GetMethodID
 * `setProgressCallback(II)V`（安装进度回调）。NesStation 不使用原版
 * CIA 安装流程，仅保证类与方法存在以通过 JNI_OnLoad。
 */
@Keep
class CiaInstallWorker {
    fun setProgressCallback(progress: Int, max: Int) {
    }
}
