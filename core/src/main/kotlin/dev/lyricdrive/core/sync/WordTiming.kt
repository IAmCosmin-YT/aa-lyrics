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
 * Most synced lyrics (including nearly all of LRCLIB) are timed per line only. This estimates
 * word timing from syllables:
 *
 * 1. Count syllables per word (vowel groups for Latin scripts, one per character for CJK).
 * 2. Measure the song's own singing rate: the median of (gap to next line / syllables) across
 *    lines, so rap gets fast words and ballads slow ones.
 * 3. Spread each line's syllables at that rate, never past [FILL_FRACTION] of the gap to the next
 *    line, each word getting time proportional to its syllables.
 */
object WordTimingEstimator {
    const val DEFAULT_SYLLABLE_MS = 220L
    const val MIN_SYLLABLE_MS = 110L
    const val MAX_SYLLABLE_MS = 450L
    const val FILL_FRACTION = 0.95
    /** Share of the gap that singing typically occupies, used when measuring the rate. */
    private const val SUNG_SHARE = 0.85
    private const val MAX_RATE_GAP_MS = 10_000L
    private const val MIN_SUNG_MS = 350L
    private val TOKEN = Regex("""\S+\s*""")
    private val VOWELS = Regex("""[aeiouyàáâãäåæèéêëìíîïòóôõöøùúûüÿąęėįųōūăâîșțœ]+""")

    fun syllables(word: String): Int {
        val lower = word.lowercase()
        val cjk = lower.count { Character.UnicodeScript.of(it.code).let { s -> s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL } }
        if (cjk > 0) return cjk
        val letters = lower.filter { it.isLetter() }
        if (letters.isEmpty()) return if (lower.any { it.isDigit() }) lower.count { it.isDigit() }.coerceAtMost(4) else 0
        var count = VOWELS.findAll(letters).count()
        // Silent trailing "e" (e.g. "drive", "home"), but not "-le" ("little").
        if (count > 1 && letters.endsWith('e') && !letters.endsWith("le") && !letters.endsWith("ee")) count--
        return count.coerceAtLeast(1)
    }

    /** Per-song syllable duration measured from line gaps. */
    fun syllableRate(lines: List<LyricLine>): Long {
        val ratios = lines.mapNotNull { line ->
            if (line.isInterlude) return@mapNotNull null
            val gap = line.endMs - line.startMs
            val syl = TOKEN.findAll(line.text).sumOf { syllables(it.value) }
            if (gap <= 0 || gap > MAX_RATE_GAP_MS || syl == 0) null else gap * SUNG_SHARE / syl
        }.sorted()
        if (ratios.size < 3) return DEFAULT_SYLLABLE_MS
        return ratios[ratios.size / 2].toLong().coerceIn(MIN_SYLLABLE_MS, MAX_SYLLABLE_MS)
    }

    fun estimate(line: LyricLine, syllableMs: Long = DEFAULT_SYLLABLE_MS): LyricLine {
        if (line.hasWordTiming || line.isInterlude) return line
        val available = line.endMs - line.startMs
        if (available <= 0) return line
        val tokens = TOKEN.findAll(line.text).map { it.value }.toList()
        if (tokens.isEmpty() || tokens.joinToString("") != line.text) return line

        val weights = tokens.map { syllables(it).coerceAtLeast(1) }
        val total = weights.sum()
        val cap = (available * FILL_FRACTION).toLong()
        val sung = min(cap, max(MIN_SUNG_MS, total * syllableMs)).coerceAtLeast(1)

        var cursor = line.startMs
        var accumulated = 0
        val words = tokens.mapIndexed { i, token ->
            accumulated += weights[i]
            val end = line.startMs + sung * accumulated / total
            LyricWord(cursor, end, token).also { cursor = end }
        }
        return line.copy(words = words)
    }

    fun estimateAll(lines: List<LyricLine>): List<LyricLine> {
        val rate = syllableRate(lines)
        return lines.map { estimate(it, rate) }
    }
}

/** Applies a [WordHighlight] mode to a timeline. */
fun LyricsTimeline.withWordHighlight(mode: WordHighlight): LyricsTimeline = when (mode) {
    WordHighlight.OFF -> withoutWordTiming()
    WordHighlight.TIMED_ONLY -> this
    WordHighlight.AUTO ->
        if (lines.all { it.hasWordTiming || it.isInterlude }) this
        else LyricsTimeline(WordTimingEstimator.estimateAll(lines))
}
