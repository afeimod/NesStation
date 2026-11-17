package com.nesstation.app.core.storage

import android.content.Context
import com.nesstation.app.core.model.BoardInfo
import com.nesstation.app.core.model.RegionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Reads bundled reference data (mapper / region / keymap databases) from
 * the `assets/` directory. All public methods are suspending and use the
 * IO dispatcher so they are safe to call from a coroutine.
 */
class AssetRepository(private val context: Context) {

    suspend fun loadBoards(): Map<Int, BoardInfo> = withContext(Dispatchers.IO) {
        val json = readAsset("core/board_database.json")
        val obj = JSONObject(json).getJSONObject("boards")
        buildMap {
            obj.keys().forEach { key ->
                val o = obj.getJSONObject(key)
                put(
                    key.toInt(),
                    BoardInfo(
                        id = key.toInt(),
                        name = o.getString("name"),
                        battery = o.optBoolean("battery", false),
                        prgKbMax = o.optInt("prg_kb_max", 0),
                        chrKbMax = o.optInt("chr_kb_max", 0)
                    )
                )
            }
        }
    }

    suspend fun loadRegions(): Map<Int, RegionInfo> = withContext(Dispatchers.IO) {
        val json = readAsset("core/region_database.json")
        val obj = JSONObject(json).getJSONObject("regions")
        buildMap {
            obj.keys().forEach { key ->
                val o = obj.getJSONObject(key)
                put(
                    key.toInt(),
                    RegionInfo(
                        id = key.toInt(),
                        code = o.getString("code"),
                        fps = o.getDouble("fps"),
                        cpuHz = o.getLong("cpu_hz"),
                        ppuHz = o.getLong("ppu_hz"),
                        sampleRateDefault = o.getInt("sample_rate_default"),
                        frameLines = o.getInt("frame_lines")
                    )
                )
            }
        }
    }

    suspend fun loadKeyMapProfile(profile: String): JSONObject = withContext(Dispatchers.IO) {
        val json = readAsset("keymaps/default.json")
        JSONObject(json).getJSONObject("profiles").getJSONObject(profile)
    }

    suspend fun loadRomScanConfig(): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(readAsset("scanner/rom_directories.json"))
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }
}
