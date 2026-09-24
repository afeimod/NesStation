package org.citra.citra_emu.applets

import android.util.Log
import androidx.annotation.Keep

/**
 * Azahar Mii 选择器小程序 —— JNI 契约桩。
 *
 * 原生侧（jni/applets/mii_selector.cpp）在 InitJNI 时缓存
 * `execute(Lorg/citra/citra_emu/applets/MiiSelector$MiiSelectorConfig;)Lorg/citra/citra_emu/applets/MiiSelector$MiiSelectorData;`
 * 并按字段名反射读写 MiiSelectorConfig（enableCancelButton:Z / title:Ljava/lang/String; /
 * initiallySelectedMiiIndex:J / miiNames:[Ljava/lang/String;）
 * 与 MiiSelectorData（returnCode:J / index:I）。
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

    @JvmStatic
    fun execute(config: MiiSelectorConfig?): MiiSelectorData {
        Log.i("AzaharNative", "MiiSelector executed (cancel stub)")
        return MiiSelectorData().apply { returnCode = RETURN_CODE_CANCEL }
    }
}
