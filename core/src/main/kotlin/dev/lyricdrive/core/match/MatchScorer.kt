package dev.lyricdrive.core.match

import kotlin.math.abs

/** A search hit from a provider, reduced to the fields we rank on. */
data class Candidate<T>(
    val title: String,
    val artist: String,
    val durationMs: Long?,
    val hasSynced: Boolean,
    val instrumental: Boolean,
    val payload: T,
)

object MatchScorer {
    /** Durations further apart than this are almost certainly a different recording. */
    const val MAX_DURATION_DELTA_MS = 5_000L

    fun <T> best(query: TrackQuery, candidates: List<Candidate<T>>): T? =
        candidates
            .filter { c ->
                val q = query.durationMs
                q == null || c.durationMs == null || abs(c.durationMs - q) <= MAX_DURATION_DELTA_MS
            }
            .map { it to score(query, it) }
            .filter { (_, score) -> score >= 0.35 }
            .maxByOrNull { (_, score) -> score }
            ?.first
            ?.payload

    fun <T> score(query: TrackQuery, c: Candidate<T>): Double {
        val title = TrackNormalizer.similarity(TrackNormalizer.cleanTitle(c.title), query.title)
        val artist = TrackNormalizer.similarity(
            TrackNormalizer.primaryArtist(c.artist),
            TrackNormalizer.primaryArtist(query.artist),
        )
        val durationBonus = if (query.durationMs != null && c.durationMs != null) {
            0.15 * (1.0 - abs(c.durationMs - query.durationMs).toDouble() / MAX_DURATION_DELTA_MS)
        } else 0.0
        val syncedBonus = if (c.hasSynced) 0.2 else if (c.instrumental) 0.05 else 0.0
        return title * 0.5 + artist * 0.3 + durationBonus + syncedBonus
    }
}
