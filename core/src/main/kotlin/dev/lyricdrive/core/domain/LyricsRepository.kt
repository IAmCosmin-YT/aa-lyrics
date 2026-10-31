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

    /** Sources that can return per-word timing; consulted even after a line-synced hit. */
    val providesWordTiming: Boolean get() = false

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
 * A hit without word timing doesn't end the search while later sources may still have word
 * timing; the best (word-synced) result wins, else the first line-synced one.
 * Confirmed misses are cached for [negativeTtlMs] so we don't re-query every replay, but a miss
 * caused by a transient error is never cached, so it gets retried when the network returns.
 */
class LyricsRepository(
    private val cache: LyricsCache,
    private val sources: List<LyricsSource>,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val negativeTtlMs: Long = 3L * 24 * 60 * 60 * 1000,
    /** Cached line-synced lyrics are re-checked against word-synced sources after this long. */
    private val lineSyncedRecheckMs: Long = 3L * 24 * 60 * 60 * 1000,
) {
    suspend fun lyricsFor(track: TrackMetadata): LyricsResult {
        var lineSynced: Pair<RawLyrics, Lyrics>? = null
        val cached = runCatching { cache.get(track) }.getOrNull()
        if (cached != null) {
            val parsed = cached.lyrics?.let { toLyrics(it, track) }
            if (parsed != null) {
                // Word sources gain coverage over time: re-ask them now and then for songs we only
                // have line timing for. The cached lyrics stay the fallback (and are re-stamped).
                val lineOnly = parsed is Lyrics.Synced && !parsed.hasWordTiming
                val due = nowMs() - cached.fetchedAtMs >= lineSyncedRecheckMs
                if (!lineOnly || !due || sources.none { it.providesWordTiming }) return LyricsResult.Found(parsed)
                lineSynced = cached.lyrics to parsed
            } else if (cached.lyrics == null && nowMs() - cached.fetchedAtMs < negativeTtlMs) {
                return LyricsResult.NotFound
            }
        }

        var transientError: Throwable? = null
        for ((index, source) in sources.withIndex()) {
            if (lineSynced != null && !source.providesWordTiming) continue
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
                    val wordSynced = (lyrics as? Lyrics.Synced)?.hasWordTiming == true
                    val laterWordSources = sources.drop(index + 1).any { it.providesWordTiming }
                    if (wordSynced || lyrics !is Lyrics.Synced || !laterWordSources) {
                        if (!wordSynced && lineSynced != null) return found(track, lineSynced)
                        runCatching { cache.put(track, result.lyrics, nowMs()) }
                        return LyricsResult.Found(lyrics)
                    }
                    if (lineSynced == null) lineSynced = result.lyrics to lyrics
                }
                SourceResult.Miss -> Unit
                is SourceResult.Error -> if (result.retryable) transientError = result.cause
            }
        }

        lineSynced?.let { return found(track, it) }
        if (transientError != null) {
            return LyricsResult.Unavailable(transientError.message ?: transientError::class.simpleName.orEmpty())
        }
        runCatching { cache.put(track, null, nowMs()) }
        return LyricsResult.NotFound
    }

    private suspend fun found(track: TrackMetadata, hit: Pair<RawLyrics, Lyrics>): LyricsResult {
        runCatching { cache.put(track, hit.first, nowMs()) }
        return LyricsResult.Found(hit.second)
    }

    companion object {
        /**
         * Parses [raw]. A parser bug must not crash the app while looking up lyrics: on an
         * exception the text is shown unsynced (tags stripped) instead.
         */
        fun toLyrics(raw: RawLyrics, track: TrackMetadata): Lyrics? =
            try {
                parse(raw, track)
            } catch (e: RuntimeException) {
                fallbackUnsynced(raw)
            }

        private val TAGS = Regex("""\[[^\]]*]|<[^>]*>|\{[^}]*}""")
        private val VOICE = Regex("""^v\d+\s*:\s*""", RegexOption.IGNORE_CASE)

        private fun fallbackUnsynced(raw: RawLyrics): Lyrics? {
            val text = raw.plain?.takeIf { it.isNotBlank() && !LrcParser.looksLikeLrc(it) }
                ?: (raw.synced ?: raw.plain)?.lineSequence()?.filterNot { it.trimStart().startsWith("[bg:") }
                    ?.map { line -> VOICE.replace(TAGS.replace(line.trim(), "").trim(), "") }?.joinToString("\n")
                ?: return null
            val lines = PlainLyricsParser.parse(text)
            return if (lines.isEmpty()) null else Lyrics.Unsynced(lines, raw.source)
        }

        private fun parse(raw: RawLyrics, track: TrackMetadata): Lyrics? {
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
