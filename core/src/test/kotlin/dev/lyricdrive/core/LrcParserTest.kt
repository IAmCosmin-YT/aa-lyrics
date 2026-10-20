package dev.lyricdrive.core

import dev.lyricdrive.core.lrc.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun `parses line synced lrc and computes end times`() {
        val lines = LrcParser.parse(
            """
            [ti:Test Song]
            [ar:Nobody]
            [00:01.50] Headlights on the empty road
            [00:04.2]Counting mile markers home
            [01:02.123]The radio hums along
            """.trimIndent(),
            trackDurationMs = 70_000,
        )
        assertEquals(3, lines.size)
        assertEquals(1_500L, lines[0].startMs)
        assertEquals(4_200L, lines[0].endMs)
        assertEquals("Headlights on the empty road", lines[0].text)
        assertEquals(4_200L, lines[1].startMs)
        assertEquals(62_123L, lines[2].startMs)
        assertEquals(70_000L, lines[2].endMs)
        assertFalse(lines[0].hasWordTiming)
    }

    @Test
    fun `expands repeated timestamps and sorts`() {
        val lines = LrcParser.parse("[00:20.00][00:05.00]Same chorus line\n[00:10.00]Verse")
        assertEquals(listOf(5_000L, 10_000L, 20_000L), lines.map { it.startMs })
        assertEquals("Same chorus line", lines[0].text)
        assertEquals("Same chorus line", lines[2].text)
        assertEquals(20_000L + LrcParser.LAST_LINE_FALLBACK_MS, lines[2].endMs)
    }

    @Test
    fun `applies offset tag`() {
        val lines = LrcParser.parse("[offset:+500]\n[00:02.00]Early\n[00:00.20]Clamped")
        assertEquals(0L, lines[0].startMs)
        assertEquals(1_500L, lines[1].startMs)
    }

    @Test
    fun `parses enhanced word timing`() {
        val line = LrcParser.parse(
            "[00:10.00] <00:10.00> Paper <00:10.50> planes <00:11.20> fly <00:12.00>\n[00:15.00]Next",
        ).first()
        assertTrue(line.hasWordTiming)
        assertEquals("Paper planes fly", line.text)
        assertEquals(listOf("Paper ", "planes ", "fly"), line.words.map { it.text })
        assertEquals(listOf(10_000L, 10_500L, 11_200L), line.words.map { it.startMs })
        assertEquals(listOf(10_500L, 11_200L, 12_000L), line.words.map { it.endMs })
        assertEquals(line.text, line.words.joinToString("") { it.text })
    }

    @Test
    fun `last word without closing tag gets fallback duration`() {
        val line = LrcParser.parse("[00:01.00]<00:01.00>Hello <00:01.40>there\n[00:09.00]x").first()
        assertEquals(1_400L + LrcParser.LAST_WORD_FALLBACK_MS, line.words.last().endMs)
    }

    @Test
    fun `keeps blank timed lines as interludes and ignores untimed text`() {
        val lines = LrcParser.parse("garbage\n[00:01.00]One\n[00:03.00]\n[00:08.00]Two")
        assertEquals(3, lines.size)
        assertTrue(lines[1].isInterlude)
    }

    @Test
    fun `detects lrc`() {
        assertTrue(LrcParser.looksLikeLrc("[00:01.00]x"))
        assertFalse(LrcParser.looksLikeLrc("just words\n[chorus]"))
    }
}
