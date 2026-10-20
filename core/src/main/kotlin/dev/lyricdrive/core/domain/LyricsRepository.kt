package dev.lyricdrive.core.domain

import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.lrc.PlainLyricsParser
import dev.lyricdrive.core.model.Lyrics
import dev.lyricdrive.core.model.RawLyrics
import dev.lyricdrive.core.model.TrackMetadata
import kotlinx.coroutines.CancellationException

sealed interface SourceResult {
    data class Hit(val lyrics: RawLyrics) : SourceResult
    data object Miss : SourceResult
    /** [retryable] = transient (network, 5xx, timeout) as opposed to a definitive failure. */
    data class Error(val cause: Throwable, val retryable: Boolean) : SourceResult
}

/** A lyrics provider (network API, embedded tags, ...). */
interface LyricsSource {
    val name: String
    suspend fun fetch(track: TrackMetadata): SourceResult
}

/** A cached entry; [lyrics] == null records a confirmed miss. */
data class CachedLyrics(val lyrics: RawLyrics?, val fetchedAtMs: Long)

interface LyricsCache {
    suspend fun get(track: TrackMetadata): CachedLyrics?
    suspend fun put(track: TrackMetadata, lyrics: RawLyrics?, fetchedAtMs: Long)
}

sealed interface LyricsResult {
    data class Found(val lyrics: Lyrics) : LyricsResult
    data object NotFound : LyricsResult
    data class Unavailable(val reason: String) : LyricsResult
}

/**
 * Priority fallback: cache → sources in order. Hits are written through to the cache.
 * Confirmed misses are cached for [negativeTtlMs] so we don't re-query every replay, but a miss
 * caused by a transient error is never cached, so it gets retried when the network returns.
 */
class LyricsRepository(
    private val cache: LyricsCache,
    private val sources: List<LyricsSource>,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val negativeTtlMs: Long = 3L * 24 * 60 * 60 * 1000,
) {
    suspend fun lyricsFor(track: TrackMetadata): LyricsResult {
        val cached = runCatching { cache.get(track) }.getOrNull()
        if (cached != null) {
            val parsed = cached.lyrics?.let { toLyrics(it, track) }
            if (parsed != null) return LyricsResult.Found(parsed)
            if (cached.lyrics == null && nowMs() - cached.fetchedAtMs < negativeTtlMs) return LyricsResult.NotFound
        }

        var transientError: Throwable? = null
        for (source in sources) {
            val result = try {
                source.fetch(track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SourceResult.Error(e, retryable = true)
            }
            when (result) {
                is SourceResult.Hit -> {
                    val lyrics = toLyrics(result.lyrics, track) ?: continue
                    runCatching { cache.put(track, result.lyrics, nowMs()) }
                    return LyricsResult.Found(lyrics)
                }
                SourceResult.Miss -> Unit
                is SourceResult.Error -> if (result.retryable) transientError = result.cause
            }
        }

        if (transientError != null) {
            return LyricsResult.Unavailable(transientError.message ?: transientError::class.simpleName.orEmpty())
        }
        runCatching { cache.put(track, null, nowMs()) }
        return LyricsResult.NotFound
    }

    companion object {
        fun toLyrics(raw: RawLyrics, track: TrackMetadata): Lyrics? {
            if (raw.instrumental) return Lyrics.Instrumental(raw.source)
            raw.synced?.takeIf { it.isNotBlank() }?.let { lrc ->
                val lines = LrcParser.parse(lrc, track.durationMs ?: raw.durationMs)
                if (lines.any { !it.isInterlude }) return Lyrics.Synced(lines, raw.source)
            }
            raw.plain?.takeIf { it.isNotBlank() }?.let { text ->
                // Some sources put LRC in the plain field (e.g. USLT frames written by taggers).
                if (LrcParser.looksLikeLrc(text)) {
                    val lines = LrcParser.parse(text, track.durationMs ?: raw.durationMs)
                    if (lines.any { !it.isInterlude }) return Lyrics.Synced(lines, raw.source)
                }
                val lines = PlainLyricsParser.parse(text)
                if (lines.isNotEmpty()) return Lyrics.Unsynced(lines, raw.source)
            }
            return null
        }
    }
}
