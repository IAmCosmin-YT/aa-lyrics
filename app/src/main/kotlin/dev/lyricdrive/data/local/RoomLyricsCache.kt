package dev.lyricdrive.data.local

import dev.lyricdrive.core.domain.CachedLyrics
import dev.lyricdrive.core.domain.LyricsCache
import dev.lyricdrive.core.match.MatchScorer
import dev.lyricdrive.core.match.TrackNormalizer
import dev.lyricdrive.core.model.RawLyrics
import dev.lyricdrive.core.model.TrackMetadata
import kotlin.math.abs
import android.util.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomLyricsCache(private val dao: LyricsDao) : LyricsCache {
    private val mutex = Mutex()
    private val memory = object : LruCache<String, CachedLyrics>(1024 * 1024) {
        override fun sizeOf(key: String, value: CachedLyrics): Int =
            256 + key.length * 2 + ((value.lyrics?.synced?.length ?: 0) + (value.lyrics?.plain?.length ?: 0)) * 2
    }

    // Keep different recordings apart, including metadata whose duration has not arrived yet.
    private fun memoryKey(track: TrackMetadata) = TrackNormalizer.cacheKey(track) + "|" + track.durationMs

    override suspend fun get(track: TrackMetadata): CachedLyrics? = mutex.withLock {
        val key = memoryKey(track)
        memory.get(key)?.let { return@withLock it }
        val entity = dao.get(TrackNormalizer.cacheKey(track)) ?: return@withLock null
        // Same title/artist but a clearly different recording (live, extended mix): treat as uncached.
        val cached = entity.durationMs
        val current = track.durationMs
        if (cached != null && current != null && abs(cached - current) > MatchScorer.MAX_DURATION_DELTA_MS) return@withLock null

        val lyrics = if (entity.found) {
            RawLyrics(entity.syncedLyrics, entity.plainLyrics, entity.instrumental, entity.source ?: "cache", entity.durationMs)
        } else null
        CachedLyrics(lyrics, entity.fetchedAtMs).also { memory.put(key, it) }
    }

    override suspend fun put(track: TrackMetadata, lyrics: RawLyrics?, fetchedAtMs: Long): Unit = mutex.withLock {
        dao.storeBounded(
            LyricsEntity(
                cacheKey = TrackNormalizer.cacheKey(track),
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs ?: lyrics?.durationMs,
                syncedLyrics = lyrics?.synced,
                plainLyrics = lyrics?.plain,
                instrumental = lyrics?.instrumental ?: false,
                source = lyrics?.source,
                found = lyrics != null,
                fetchedAtMs = fetchedAtMs,
            ),
        )
        // Invalidate other duration variants of this track after replacing its disk entry.
        val prefix = TrackNormalizer.cacheKey(track) + "|"
        memory.snapshot().keys.filter { it.startsWith(prefix) }.forEach { memory.remove(it) }
        memory.put(memoryKey(track), CachedLyrics(lyrics, fetchedAtMs))
    }

    suspend fun clear() = mutex.withLock {
        dao.clearAll()
        memory.evictAll()
    }

    fun trimMemory() = memory.evictAll()
}
