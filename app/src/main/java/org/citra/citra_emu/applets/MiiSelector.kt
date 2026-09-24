package org.citra.citra_emu.applets

import android.util.Log
import androidx.annotation.Keep

/**
 * Azahar Mii 选择器小程序 —— JNI 契约桩。
 *
 * 原生侧（jni/applets/mii_selector.cpp）在 JNI_OnLoad（IDCache 初始化）时缓存
 * `Execute(Lorg/citra/citra_emu/applets/MiiSelector$MiiSelectorConfig;)Lorg/citra/citra_emu/applets/MiiSelector$MiiSelectorData;`
 * —— 注意是 **PascalCase 的 `Execute`**（AzaharPlus fork 的实际契约，经 libazahar.so
 * 反汇编验证；上游官方 azahar 亦为 `Execute`），并按字段名反射读写 MiiSelectorConfig
 * （enableCancelButton:Z / title:Ljava/lang/String; / initiallySelectedMiiIndex:J /
 * miiNames:[Ljava/lang/String;）与 MiiSelectorData（returnCode:J / index:I）。
 * 最小实现：取消（returnCode = 1，与上游 CANCEL 常量一致）。
 */
@Keep
object MiiSelector {

    const val RETURN_CODE_CANCEL: Long = 1L
    const val RETURN_CODE_OK: Long = 0L

    class MiiSelectorConfig {
        @JvmField
        var enableCancelButton: Boolean = false

        @JvmField
        var title: String? = null

        @JvmField
        var initiallySelectedMiiIndex: Long = 0L

        @JvmField
        var miiNames: Array<String?> = arrayOfNulls(0)
    }

    class MiiSelectorData {
        @JvmField
        var returnCode: Long = RETURN_CODE_CANCEL

        @JvmField
        var index: Int = 0
    }

    /**
     * 方法名必须为 `Execute`（PascalCase）—— JNI_OnLoad 按 GetStaticMethodID
     * 字符串名查找，camelCase 的 `execute` 会抛 NoSuchMethodError 并因 pending
     * exception 直接 SIGABRT 闪退（应用启动即崩，无法恢复）。
     */
    @JvmStatic
    fun Execute(config: MiiSelectorConfig?): MiiSelectorData {
        Log.i("AzaharNative", "MiiSelector executed (cancel stub)")
        return MiiSelectorData().apply { returnCode = RETURN_CODE_CANCEL }
    }
}
