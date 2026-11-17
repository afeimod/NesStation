package com.nesstation.app.core.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "roms")
data class RomEntity(
    @PrimaryKey val id: String,                 // hash of path
    val title: String,
    val path: String,
    @ColumnInfo(name = "cover_path") val coverPath: String? = null,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long = 0L,
    @ColumnInfo(name = "play_time_ms") val playTimeMs: Long = 0L,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis()
)

@Dao
interface RomDao {
    @Query("SELECT * FROM roms ORDER BY last_played_at DESC")
    fun observeAll(): Flow<List<RomEntity>>

    @Query("SELECT * FROM roms WHERE is_favorite = 1 ORDER BY title")
    fun observeFavorites(): Flow<List<RomEntity>>

    @Query("SELECT * FROM roms WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): RomEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rom: RomEntity)

    @Update
    suspend fun update(rom: RomEntity)

    @Query("DELETE FROM roms WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM roms")
    suspend fun count(): Int
}
