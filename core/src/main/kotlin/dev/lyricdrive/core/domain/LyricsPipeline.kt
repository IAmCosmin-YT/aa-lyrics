package dev.lyricdrive.core.domain

import dev.lyricdrive.core.match.TrackNormalizer
import dev.lyricdrive.core.model.LyricsState
import dev.lyricdrive.core.model.TrackMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Track metadata → lyrics state.
 *
 * - Debounced (players often publish title, then artist, then duration in quick succession,
 *   and skipping through a playlist shouldn't fire a request per track).
 * - Keyed on the normalized track, so position/art updates don't refetch.
 * - `flatMapLatest` cancels the in-flight lookup when the track changes.
 * - Transient failures retry on connectivity recovery or with capped backoff.
 */
class LyricsPipeline(
    private val repository: LyricsRepository,
    private val debounceMs: Long = 400,
    private val retryBackoffMs: List<Long> = listOf(15_000, 30_000, 60_000, 120_000, 300_000),
) {
    fun states(tracks: Flow<TrackMetadata?>, online: Flow<Boolean>): Flow<LyricsState> =
        tracks
            .debounce(debounceMs)
            .distinctUntilChanged { a, b -> a?.let(::identity) == b?.let(::identity) }
            .flatMapLatest { track -> if (track == null) flowOf(LyricsState.Idle) else lookup(track, online) }

    private fun lookup(track: TrackMetadata, online: Flow<Boolean>): Flow<LyricsState> = flow {
        emit(LyricsState.Loading(track))
        var attempt = 0
        while (true) {
            when (val result = repository.lyricsFor(track)) {
                is LyricsResult.Found -> return@flow emit(LyricsState.Ready(track, result.lyrics))
                LyricsResult.NotFound -> return@flow emit(LyricsState.NotFound(track))
                is LyricsResult.Unavailable -> emit(LyricsState.Unavailable(track, result.reason))
            }
            val backoff = retryBackoffMs[attempt.coerceAtMost(retryBackoffMs.lastIndex)]
            attempt++
            // Wake early when connectivity drops and comes back; otherwise retry after backoff.
            withTimeoutOrNull(backoff) { online.dropWhile { it }.first { it } }
        }
    }

    private fun identity(track: TrackMetadata) =
        TrackNormalizer.cacheKey(track) + "|" + TrackNormalizer.canonical(track.album.orEmpty())
}
