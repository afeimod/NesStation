package com.nesstation.app.core.storage

import android.content.Context
import com.nesstation.app.core.model.GameEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Lightweight repository over the Room DAO + a few file-system helpers.
 * Designed to be testable and UI-friendly.
 */
class AppContainer(private val context: Context) {
    private val db = AppDatabase.get(context)
    val roms = db.roms()
    val assets = AssetRepository(context)

    fun observeAll(): Flow<List<GameEntry>> = roms.observeAll().map { list ->
        list.map { it.toModel() }
    }

    fun observeFavorites(): Flow<List<GameEntry>> = roms.observeFavorites().map { list ->
        list.map { it.toModel() }
    }

    suspend fun importRom(file: File): GameEntry = withContext(Dispatchers.IO) {
        val id = hashId(file)
        val entity = RomEntity(
            id = id,
            title = file.nameWithoutExtension,
            path = file.absolutePath
        )
        roms.upsert(entity)
        entity.toModel()
    }

    suspend fun toggleFavorite(id: String) = withContext(Dispatchers.IO) {
        val cur = roms.byId(id) ?: return@withContext
        roms.update(cur.copy(isFavorite = !cur.isFavorite))
    }

    suspend fun touchPlayed(id: String, playTimeMs: Long) = withContext(Dispatchers.IO) {
        val cur = roms.byId(id) ?: return@withContext
        roms.update(cur.copy(lastPlayedAt = System.currentTimeMillis(), playTimeMs = playTimeMs))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { roms.deleteById(id) }

    private fun hashId(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { fis ->
            val buf = ByteArray(8192)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

private fun RomEntity.toModel(): GameEntry = GameEntry(
    id = id,
    title = title,
    romPath = path,
    coverPath = coverPath,
    lastPlayedAt = lastPlayedAt,
    playTimeMs = playTimeMs,
    isFavorite = isFavorite
)
