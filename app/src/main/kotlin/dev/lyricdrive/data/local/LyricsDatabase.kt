package dev.lyricdrive.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val cacheKey: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val instrumental: Boolean,
    val source: String?,
    /** false = confirmed miss (negative cache entry). */
    val found: Boolean,
    val fetchedAtMs: Long,
)

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE cacheKey = :key")
    suspend fun get(key: String): LyricsEntity?

    @Upsert
    suspend fun upsert(entity: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE found = 0")
    suspend fun clearMisses()
}

@Database(entities = [LyricsEntity::class], version = 2, exportSchema = true)
abstract class LyricsDatabase : RoomDatabase() {
    abstract fun lyricsDao(): LyricsDao
}
