package dev.lyricdrive.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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

    @Query("DELETE FROM lyrics WHERE cacheKey NOT IN (SELECT cacheKey FROM lyrics ORDER BY fetchedAtMs DESC, cacheKey LIMIT :limit)")
    suspend fun trim(limit: Int)

    @Transaction
    suspend fun storeBounded(entity: LyricsEntity) {
        upsert(entity)
        trim(500)
    }

    @Query("SELECT COUNT(*) FROM lyrics WHERE found = 1")
    fun observeSongCount(): Flow<Int>

    @Query("DELETE FROM lyrics")
    suspend fun clearAll()

    @Query("DELETE FROM lyrics WHERE found = 0")
    suspend fun clearMisses()
}

@Database(entities = [LyricsEntity::class], version = 5, exportSchema = true)
abstract class LyricsDatabase : RoomDatabase() {
    abstract fun lyricsDao(): LyricsDao
}
