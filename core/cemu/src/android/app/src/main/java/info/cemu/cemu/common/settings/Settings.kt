package info.cemu.cemu.common.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import info.cemu.cemu.common.ui.localization.DEFAULT_LANGUAGE
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

@Serializable
data class EmulationSettings(
    val gamePadPosition: GamePadPosition = GamePadPosition.OVERLAY,
    val gameSpeedMode: Int = 1,
    // floating GamePad screen overlay (shown when gamePadPosition == OVERLAY)
    // padOverlayScale: pad screen width as fraction of the main screen width
    // padOverlayOpacity: 0..100
    // padOverlayBlur: blur radius in dp (0 = disabled, requires Android 12+)
    // padOverlayCorner: 0 = bottom-left, 1 = bottom-right, 2 = top-left, 3 = top-right
    val padOverlayScale: Float = 0.2f,
    val padOverlayOpacity: Int = 60,
    val padOverlayBlur: Int = 0,
    val padOverlayCorner: Int = 0,
)

@Serializable
data class GuiSettings(
    val language: String = DEFAULT_LANGUAGE,
)

@Serializable
data class InputOverlayRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

@Serializable
data class InputOverlaySettings(
    val isVibrateOnTouchEnabled: Boolean = false,
    // virtual buttons are enabled out of the box (overlay controller 1)
    val isOverlayEnabled: Boolean = true,
    val controllerIndex: Int = 0,
    val alpha: Int = 64,
    val inputVisibilityMap: Map<OverlayInputConfig, Boolean> = emptyMap(),
    val inputOverlayRectMap: Map<OverlayInputConfig, InputOverlayRect> = emptyMap(),
)

@Serializable
data class AppSettings(
    val guiSettings: GuiSettings = GuiSettings(),
    val emulationSettings: EmulationSettings = EmulationSettings(),
    val inputOverlaySettings: InputOverlaySettings = InputOverlaySettings(),
    val hotkeySettings: Map<HotkeyAction, HotkeyCombo> = emptyMap(),
)

object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings = try {
        Json.decodeFromString<AppSettings>(input.readBytes().decodeToString())
    } catch (_: Exception) {
        defaultValue
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(Json.encodeToString(t).encodeToByteArray())
    }
}

object AppSettingsStore {
    private lateinit var _dataStore: DataStore<AppSettings>
    val dataStore: DataStore<AppSettings>
        get() = _dataStore

    fun init(context: Context) {
        _dataStore = MultiProcessDataStoreFactory.create(
            serializer = AppSettingsSerializer,
            corruptionHandler = null,
            produceFile = { context.dataStoreFile("appSettings.json") },
        )
    }
}
