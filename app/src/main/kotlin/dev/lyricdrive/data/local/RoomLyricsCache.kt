package dev.lyricdrive.data.local

import dev.lyricdrive.core.domain.CachedLyrics
import dev.lyricdrive.core.domain.LyricsCache
import dev.lyricdrive.core.match.MatchScorer
import dev.lyricdrive.core.match.TrackNormalizer
import dev.lyricdrive.core.model.RawLyrics
import dev.lyricdrive.core.model.TrackMetadata
import kotlin.math.abs

class RoomLyricsCache(private val dao: LyricsDao) : LyricsCache {
    override suspend fun get(track: TrackMetadata): CachedLyrics? {
        val entity = dao.get(TrackNormalizer.cacheKey(track)) ?: return null
        // Same title/artist but a clearly different recording (live, extended mix): treat as uncached.
        val cached = entity.durationMs
        val current = track.durationMs
        if (cached != null && current != null && abs(cached - current) > MatchScorer.MAX_DURATION_DELTA_MS) return null

        val lyrics = if (entity.found) {
            RawLyrics(entity.syncedLyrics, entity.plainLyrics, entity.instrumental, entity.source ?: "cache", entity.durationMs)
        } else null
        return CachedLyrics(lyrics, entity.fetchedAtMs)
    }

    override suspend fun put(track: TrackMetadata, lyrics: RawLyrics?, fetchedAtMs: Long) {
        dao.upsert(
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
    }
}
