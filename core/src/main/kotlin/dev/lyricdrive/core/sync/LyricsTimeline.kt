package dev.lyricdrive.core.sync

import dev.lyricdrive.core.model.LyricLine
import dev.lyricdrive.core.model.filledCharacters

/**
 * Time lookups over synced lines. All queries are O(log n) so they are cheap enough
 * to run on every display frame.
 */
class LyricsTimeline(val lines: List<LyricLine>) {
    private val starts = LongArray(lines.size) { lines[it].startMs }

    /** Index of the line active at [positionMs], or -1 before the first line. */
    fun activeIndex(positionMs: Long): Int {
        var lo = 0
        var hi = starts.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= positionMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    /**
     * Number of characters of line [index] that should be lit at [positionMs], fractional so the
     * fill glides through each word. Lines without word timing light up fully once active.
     */
    fun filledCharacters(index: Int, positionMs: Long): Float {
        val line = lines.getOrNull(index) ?: return 0f
        return filledCharacters(line.text, line.words, line.startMs, positionMs)
    }

    /** Like [filledCharacters] for the line's backing vocals. */
    fun backingFilled(index: Int, positionMs: Long): Float {
        val backing = lines.getOrNull(index)?.backing ?: return 0f
        return filledCharacters(backing.text, backing.words, backing.startMs, positionMs)
    }

    /**
     * Every line being sung at [positionMs], ascending. Usually just [activeIndex], but in duets
     * and with backing vocals an earlier line can still be sung after the next one has started.
     */
    fun activeIndices(positionMs: Long): List<Int> {
        val primary = activeIndex(positionMs)
        if (primary < 0) return emptyList()
        val out = ArrayList<Int>(2)
        for (i in maxOf(0, primary - MAX_OVERLAP_LOOKBACK) until primary) {
            val line = lines[i]
            if (!line.isInterlude && line.activeUntilMs > positionMs && line.startMs <= positionMs) out += i
        }
        out += primary
        return out
    }

    /** 0..1 progress through line [index] (used for interlude dots and progress hints). */
    fun lineProgress(index: Int, positionMs: Long): Float {
        val line = lines.getOrNull(index) ?: return 0f
        val span = (line.endMs - line.startMs).coerceAtLeast(1)
        return ((positionMs - line.startMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /** First time after [positionMs] at which [activeIndex] changes, or null after the last line. */
    fun nextBoundaryAfter(positionMs: Long): Long? {
        val next = activeIndex(positionMs) + 1
        return starts.getOrNull(next)
    }

    /** Same lines with word timings dropped, so every line lights up whole when it becomes active. */
    fun withoutWordTiming(): LyricsTimeline =
        if (lines.none { it.hasWordTiming || it.backing?.hasWordTiming == true }) this
        else LyricsTimeline(lines.map { it.copy(words = emptyList(), backing = it.backing?.copy(words = emptyList())) })

    /**
     * Inserts interlude rows (rendered as breathing dots) where the vocals pause for at least
     * [minGapMs]: before the first line, and between a line's last sung word and the next line.
     * Needs word timing to know when singing stops; lines without it are left alone.
     */
    fun withInterludes(minGapMs: Long = 4_500): LyricsTimeline {
        if (lines.isEmpty()) return this
        val out = ArrayList<LyricLine>(lines.size + 4)
        val first = lines.first()
        if (!first.isInterlude && first.startMs >= minGapMs) out += LyricLine(0, first.startMs, "")
        lines.forEachIndexed { i, line ->
            val next = lines.getOrNull(i + 1)
            val sungEnd = line.words.lastOrNull()?.endMs?.let { maxOf(it, line.backing?.words?.lastOrNull()?.endMs ?: 0L) }
            if (next != null && sungEnd != null && !line.isInterlude && !next.isInterlude && next.startMs - sungEnd >= minGapMs) {
                out += line.copy(endMs = sungEnd)
                out += LyricLine(sungEnd, next.startMs, "")
            } else {
                out += line
            }
        }
        return if (out.size == lines.size) this else LyricsTimeline(out)
    }

    companion object {
        /** How many earlier lines may still be sung when a new one starts. */
        private const val MAX_OVERLAP_LOOKBACK = 6

        val EMPTY = LyricsTimeline(emptyList())
    }
}
