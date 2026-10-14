package info.cemu.cemu.nativeinterface

import android.graphics.Bitmap
import org.json.JSONObject

/**
 * [NesStation 集成说明] 上游 Cemu-AZ 此文件使用 kotlinx.serialization 解析
 * QuickSaveInfo JSON；NesStation 未引入 kotlinx-serialization 依赖，这里改为
 * Android 内置 org.json 手工解析 —— JSON 字段名与结构（@SerialName 注解值）
 * 与上游 NativeQuickSave.h 原生侧序列化格式逐字段一致，行为不变。
 * external 方法签名（原生符号绑定）保持与上游完全相同。
 */
object NativeQuickSave {
    // Result codes, matching NativeQuickSave.h on the native side
    const val RESULT_OK: Int = 0
    const val RESULT_ERROR_NOT_RUNNING: Int = 1
    const val RESULT_ERROR_NO_SAVE_DATA: Int = 2
    const val RESULT_ERROR_SLOT_EMPTY: Int = 3
    const val RESULT_ERROR_IO: Int = 4
    const val RESULT_OK_RESTORED_IN_PLACE: Int = 5
    const val RESULT_ERROR_STATE_RESTORE_FAILED: Int = 6

    // restore flags
    const val RESTORE_FLAG_ALLOW_LEGACY: Int = 1

    data class QuickSaveSlot(
        val slot: Int,
        val exists: Boolean,
        val timestamp: String,
        val sizeBytes: Long,
        val hasState: Boolean = false,
        val thumb: Boolean,
    )

    data class QuickSaveAutoBackup(
        val exists: Boolean,
        val timestamp: String,
        val thumb: Boolean,
    )

    data class QuickSaveInfo(
        val titleId: String,
        val titleName: String,
        val maxSlots: Int,
        val slots: List<QuickSaveSlot>,
        val autoBackup: QuickSaveAutoBackup? = null,
    )

    @JvmStatic
    external fun getQuickSaveInfo(): String

    @JvmStatic
    external fun quickSaveSlot(slot: Int): Int

    @JvmStatic
    external fun quickRestoreSlot(slot: Int, flags: Int): Int

    @JvmStatic
    external fun getQuickSaveThumbnail(slot: Int): Bitmap?

    fun parseQuickSaveInfo(jsonString: String): QuickSaveInfo {
        return try {
            val root = JSONObject(jsonString)
            val slotsArr = root.optJSONArray("slots")
            val slots = buildList {
                if (slotsArr != null) {
                    for (i in 0 until slotsArr.length()) {
                        val s = slotsArr.optJSONObject(i) ?: continue
                        add(
                            QuickSaveSlot(
                                slot = s.optInt("slot", i),
                                exists = s.optBoolean("exists", false),
                                timestamp = s.optString("timestamp", ""),
                                sizeBytes = s.optLong("sizeBytes", 0L),
                                hasState = s.optBoolean("hasState", false),
                                thumb = s.optBoolean("thumb", false),
                            )
                        )
                    }
                }
            }
            val backup = root.optJSONObject("autoBackup")?.let {
                QuickSaveAutoBackup(
                    exists = it.optBoolean("exists", false),
                    timestamp = it.optString("timestamp", ""),
                    thumb = it.optBoolean("thumb", false),
                )
            }
            QuickSaveInfo(
                titleId = root.optString("titleId", ""),
                titleName = root.optString("titleName", ""),
                maxSlots = root.optInt("maxSlots", 6),
                slots = slots,
                autoBackup = backup,
            )
        } catch (_: Exception) {
            QuickSaveInfo(titleId = "", titleName = "", maxSlots = 6, slots = emptyList())
        }
    }
}
