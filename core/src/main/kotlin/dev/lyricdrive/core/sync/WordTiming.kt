package dev.lyricdrive.core.sync

import dev.lyricdrive.core.model.LyricLine
import dev.lyricdrive.core.model.LyricWord
import kotlin.math.max
import kotlin.math.min

/** How the active line is filled in. */
enum class WordHighlight {
    /** Whole lines light up at once. */
    OFF,

    /** Word-by-word only when the lyrics carry real word timestamps. */
    TIMED_ONLY,

    /** Real word timestamps when present, otherwise estimated from the line's duration. */
    AUTO,
}

/**
 * Most synced lyrics (including nearly all of LRCLIB) are timed per line only. This spreads a
 * line's words over the time it's likely being sung: roughly [MS_PER_CHAR] per letter, never
 * more than [FILL_FRACTION] of the gap to the next line, weighted by word length.
 */
object WordTimingEstimator {
    const val MS_PER_CHAR = 75L
    const val MIN_SUNG_MS = 600L
    const val FILL_FRACTION = 0.9
    private val TOKEN = Regex("""\S+\s*""")

    fun estimate(line: LyricLine): LyricLine {
        if (line.hasWordTiming || line.isInterlude) return line
        val available = line.endMs - line.startMs
        if (available <= 0) return line
        val tokens = TOKEN.findAll(line.text).map { it.value }.toList()
        if (tokens.isEmpty() || tokens.joinToString("") != line.text) return line

        val letters = line.text.count { !it.isWhitespace() }
        val sung = min((available * FILL_FRACTION).toLong(), max(MIN_SUNG_MS, letters * MS_PER_CHAR)).coerceAtLeast(1)
        val weights = tokens.map { it.trimEnd().length + 1 }
        val total = weights.sum().toDouble()

        var cursor = line.startMs
        var accumulated = 0.0
        val words = tokens.mapIndexed { i, token ->
            accumulated += weights[i]
            val end = line.startMs + (sung * accumulated / total).toLong()
            LyricWord(cursor, end, token).also { cursor = end }
        }
        return line.copy(words = words)
    }
}

/** Applies a [WordHighlight] mode to a timeline. */
fun LyricsTimeline.withWordHighlight(mode: WordHighlight): LyricsTimeline = when (mode) {
    WordHighlight.OFF -> withoutWordTiming()
    WordHighlight.TIMED_ONLY -> this
    WordHighlight.AUTO ->
        if (lines.all { it.hasWordTiming || it.isInterlude }) this
        else LyricsTimeline(lines.map(WordTimingEstimator::estimate))
}
