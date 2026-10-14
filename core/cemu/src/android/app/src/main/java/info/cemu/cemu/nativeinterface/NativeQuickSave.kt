package info.cemu.cemu.nativeinterface

import android.graphics.Bitmap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

    @Serializable
    data class QuickSaveSlot(
        @SerialName("slot") val slot: Int,
        @SerialName("exists") val exists: Boolean,
        @SerialName("timestamp") val timestamp: String,
        @SerialName("sizeBytes") val sizeBytes: Long,
        @SerialName("hasState") val hasState: Boolean = false,
        @SerialName("thumb") val thumb: Boolean,
    )

    @Serializable
    data class QuickSaveAutoBackup(
        @SerialName("exists") val exists: Boolean,
        @SerialName("timestamp") val timestamp: String,
        @SerialName("thumb") val thumb: Boolean,
    )

    @Serializable
    data class QuickSaveInfo(
        @SerialName("titleId") val titleId: String,
        @SerialName("titleName") val titleName: String,
        @SerialName("maxSlots") val maxSlots: Int,
        @SerialName("slots") val slots: List<QuickSaveSlot>,
        @SerialName("autoBackup") val autoBackup: QuickSaveAutoBackup? = null,
    )

    private val json = Json {
        ignoreUnknownKeys = true
    }

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
            json.decodeFromString<QuickSaveInfo>(jsonString)
        } catch (_: Exception) {
            QuickSaveInfo(titleId = "", titleName = "", maxSlots = 6, slots = emptyList())
        }
    }
}
