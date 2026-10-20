package dev.lyricdrive.data.remote

import dev.lyricdrive.core.domain.LyricsSource
import dev.lyricdrive.core.domain.SourceResult
import dev.lyricdrive.core.match.Candidate
import dev.lyricdrive.core.match.MatchScorer
import dev.lyricdrive.core.match.TrackNormalizer
import dev.lyricdrive.core.model.RawLyrics
import dev.lyricdrive.core.model.TrackMetadata
import java.io.IOException
import kotlin.math.abs

class LrclibLyricsSource(private val client: LrclibClient) : LyricsSource {
    override val name = "LRCLIB"

    override suspend fun fetch(track: TrackMetadata): SourceResult {
        val queries = TrackNormalizer.queries(track)
        var anySuccess = false
        var lastError: IOException? = null

        suspend fun attempt(block: suspend () -> LrclibTrack?): LrclibTrack? = try {
            block().also { anySuccess = true }
        } catch (e: IOException) {
            lastError = e
            null
        }

        // 1) Exact lookups for the two most specific variants.
        for (query in queries.take(2)) {
            val hit = attempt { client.get(query) }
            if (hit != null && hit.isUsable() && hit.durationMatches(track)) return SourceResult.Hit(hit.toRaw())
        }

        // 2) Fuzzy search, ranked by title/artist similarity, duration and sync availability.
        for (query in queries.distinctBy { it.title to it.artist }.take(2)) {
            val best = attempt {
                val results = client.search(query.title, query.artist)
                MatchScorer.best(query, results.map { Candidate(it.trackName, it.artistName, it.durationMs, it.syncedLyrics != null, it.instrumental, it) })
            }
            if (best != null && best.isUsable()) return SourceResult.Hit(best.toRaw())
        }

        val error = lastError
        return if (!anySuccess && error != null) SourceResult.Error(error, retryable = true) else SourceResult.Miss
    }

    private fun LrclibTrack.isUsable() = instrumental || syncedLyrics != null || plainLyrics != null

    private fun LrclibTrack.durationMatches(track: TrackMetadata): Boolean {
        val a = durationMs ?: return true
        val b = track.durationMs ?: return true
        return abs(a - b) <= MatchScorer.MAX_DURATION_DELTA_MS
    }

    private fun LrclibTrack.toRaw() = RawLyrics(syncedLyrics, plainLyrics, instrumental, name, durationMs)
}
