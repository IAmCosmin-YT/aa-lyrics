package dev.lyricdrive.core.sync

import dev.lyricdrive.core.model.LyricLine

/**
 * Players don't expose BPM, so the background's motion speed is derived from lyric density:
 * fast, wordy songs move the gradient faster than slow ballads. Returns 0 (calm) .. 1 (energetic).
 */
object LyricsEnergy {
    private const val CALM_INTERVAL_MS = 6_000f
    private const val ENERGETIC_INTERVAL_MS = 1_500f
    const val NEUTRAL = 0.5f

    fun estimate(lines: List<LyricLine>): Float {
        val intervals = lines.filterNot { it.isInterlude }
            .zipWithNext { a, b -> (b.startMs - a.startMs).toFloat() }
            .filter { it > 0f }
            .sorted()
        if (intervals.size < 4) return NEUTRAL
        val median = intervals[intervals.size / 2]
        val t = (CALM_INTERVAL_MS - median) / (CALM_INTERVAL_MS - ENERGETIC_INTERVAL_MS)
        return t.coerceIn(0f, 1f)
    }
}
