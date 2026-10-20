package dev.lyricdrive.core

import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.sync.LyricsTimeline
import dev.lyricdrive.core.sync.WordHighlight
import dev.lyricdrive.core.sync.WordTimingEstimator
import dev.lyricdrive.core.sync.withWordHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimingTest {
    private val lineSynced = LrcParser.parse("[00:10.00]Folding maps in the passenger seat\n[00:20.00]Short\n[00:20.40]Next")

    @Test
    fun `counts syllables`() {
        assertEquals(1, WordTimingEstimator.syllables("drive"))
        assertEquals(2, WordTimingEstimator.syllables("Folding "))
        assertEquals(3, WordTimingEstimator.syllables("passenger"))
        assertEquals(2, WordTimingEstimator.syllables("little"))
        assertEquals(1, WordTimingEstimator.syllables("rhythm"))
        assertEquals(0, WordTimingEstimator.syllables("—"))
        assertEquals(2, WordTimingEstimator.syllables("夜空"))
    }

    @Test
    fun `estimates words by syllables within the line`() {
        val line = WordTimingEstimator.estimate(lineSynced[0], syllableMs = 200)
        assertTrue(line.hasWordTiming)
        assertEquals(line.text, line.words.joinToString("") { it.text })
        assertEquals(10_000L, line.words.first().startMs)
        assertTrue(line.words.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        // Folding(2) maps(1) in(1) the(1) passenger(3) seat(1) = 9 syllables * 200 ms
        assertEquals(11_800L, line.words.last().endMs)
        // "passenger" gets three times the time of "maps".
        val maps = line.words[1]
        val passenger = line.words[4]
        assertEquals(3 * (maps.endMs - maps.startMs), passenger.endMs - passenger.startMs)
    }

    @Test
    fun `fast lines are capped by the gap to the next line`() {
        val line = WordTimingEstimator.estimate(lineSynced[1])
        assertTrue(line.words.last().endMs <= 20_400)
    }

    @Test
    fun `rate adapts to the song`() {
        // Dense lines: ~8 syllables every 1.6 s -> fast rate.
        val fast = LrcParser.parse((0 until 8).joinToString("\n") { "[00:%02d.%02d]rapid little words keep running on".format((it * 16) / 10, (it * 16) % 10 * 10) })
        val slow = LrcParser.parse((0 until 8).joinToString("\n") { "[00:%02d.00]oh so slow".format(it * 6) })
        assertTrue(WordTimingEstimator.syllableRate(fast) < WordTimingEstimator.syllableRate(slow))
        assertEquals(WordTimingEstimator.MAX_SYLLABLE_MS, WordTimingEstimator.syllableRate(slow))
    }

    @Test
    fun `modes`() {
        val timeline = LyricsTimeline(lineSynced)
        assertFalse(timeline.withWordHighlight(WordHighlight.TIMED_ONLY).lines[0].hasWordTiming)
        val auto = timeline.withWordHighlight(WordHighlight.AUTO)
        assertTrue(auto.lines[0].hasWordTiming)
        val mid = auto.filledCharacters(0, 10_600)
        assertTrue(mid > 0f && mid < lineSynced[0].text.length)

        val timed = LyricsTimeline(LrcParser.parse("[00:01.00]<00:01.00>ab <00:02.00>cd"))
        assertTrue(timed.withWordHighlight(WordHighlight.AUTO) === timed)
        assertFalse(timed.withWordHighlight(WordHighlight.OFF).lines[0].hasWordTiming)
    }
}
