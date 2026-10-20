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
    fun `estimates words within the line`() {
        val line = WordTimingEstimator.estimate(lineSynced[0])
        assertTrue(line.hasWordTiming)
        assertEquals(line.text, line.words.joinToString("") { it.text })
        assertEquals(10_000L, line.words.first().startMs)
        assertTrue(line.words.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        val sung = line.words.last().endMs - 10_000
        val letters = line.text.count { !it.isWhitespace() }
        assertEquals(minOf(9_000L, letters * WordTimingEstimator.MS_PER_CHAR), sung)
    }

    @Test
    fun `fast lines are capped by the gap to the next line`() {
        val line = WordTimingEstimator.estimate(lineSynced[1])
        assertTrue(line.words.last().endMs <= 20_400)
    }

    @Test
    fun `modes`() {
        val timeline = LyricsTimeline(lineSynced)
        assertFalse(timeline.withWordHighlight(WordHighlight.TIMED_ONLY).lines[0].hasWordTiming)
        assertTrue(timeline.withWordHighlight(WordHighlight.AUTO).lines[0].hasWordTiming)
        // Fill now progresses through the line instead of jumping to full.
        val auto = timeline.withWordHighlight(WordHighlight.AUTO)
        val mid = auto.filledCharacters(0, 11_000)
        assertTrue(mid > 0f && mid < lineSynced[0].text.length)

        val timed = LyricsTimeline(LrcParser.parse("[00:01.00]<00:01.00>ab <00:02.00>cd"))
        assertTrue(timed.withWordHighlight(WordHighlight.AUTO) === timed)
        assertFalse(timed.withWordHighlight(WordHighlight.OFF).lines[0].hasWordTiming)
    }
}
