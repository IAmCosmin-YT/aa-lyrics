package dev.lyricdrive.core

import dev.lyricdrive.core.kpoe.KpoeConverter
import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.model.Voice
import dev.lyricdrive.core.paxsenix.PaxsenixConverter
import dev.lyricdrive.core.sync.LyricsTimeline
import dev.lyricdrive.core.sync.WordHighlight
import dev.lyricdrive.core.sync.withWordHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// All lyric text below is invented for the tests.
class MultiVocalTest {
    @Test
    fun `voice prefixes and bg lines`() {
        val lines = LrcParser.parse(
            """
            [00:01.00]v1: <00:01.00>Paper <00:01.50>moons <00:02.00>
            [bg: <00:01.40>(paper <00:01.80>moons)<00:02.30>]
            [00:03.00]v2: <00:03.00>Glass <00:03.40>stars <00:04.00>
            [00:05.00]v1000: Both of us now
            [00:06.00]{agent:v2}curly agent
            """.trimIndent(),
        )
        assertEquals(listOf(Voice.SINGER_1, Voice.SINGER_2, Voice.TOGETHER, Voice.SINGER_2), lines.map { it.voice })
        val bg = lines[0].backing!!
        assertEquals("paper moons", bg.text)
        assertEquals(listOf(1_400L, 1_800L), bg.words.map { it.startMs })
        assertEquals(2_300L, bg.endMs)
        assertEquals("Paper moons", lines[0].text)
    }

    @Test
    fun `parenthesised parts become backing vocals`() {
        val plain = LrcParser.parse("[00:01.00]Stay (stay with me) tonight\n[00:05.00]next").first()
        assertEquals("Stay tonight", plain.text)
        assertEquals("stay with me", plain.backing!!.text)
        assertEquals(1_000L, plain.backing!!.startMs)

        val timed = LrcParser.parse("[00:01.00]<00:01.00>Stay <00:01.40>(stay <00:01.80>with <00:02.10>me) <00:02.50>tonight <00:03.00>").first()
        assertEquals("Stay tonight", timed.text)
        assertEquals(timed.text, timed.words.joinToString("") { it.text })
        assertEquals(listOf("stay ", "with ", "me"), timed.backing!!.words.map { it.text })
        assertEquals(1_400L, timed.backing!!.startMs)

        val whole = LrcParser.parse("[00:01.00](Ooh, yeah)\n[00:03.00]x").first()
        assertTrue(whole.secondary)
        assertEquals("Ooh, yeah", whole.text)
        assertNull(whole.backing)
    }

    @Test
    fun `overlapping lines are active together`() {
        // Line 1 is still sung (until 4.5 s) when line 2 starts at 3 s.
        val lines = LrcParser.parse(
            """
            [00:01.00]v1: <00:01.00>Hold <00:02.00>on <00:04.50>
            [00:03.00]v2: <00:03.00>I'm <00:03.50>here <00:05.00>
            [00:03.00]v1: same start
            [00:08.00]end
            """.trimIndent(),
        )
        val t = LyricsTimeline(lines)
        assertEquals(listOf(0, 1, 2), t.activeIndices(4_000))
        assertEquals(listOf(1, 2), t.activeIndices(4_600))
        // Simultaneous lines don't end each other.
        assertEquals(8_000L, lines[1].endMs)
        assertEquals(8_000L, lines[2].endMs)
    }

    @Test
    fun `backing vocals get estimated word timing and are stripped when off`() {
        val t = LyricsTimeline(LrcParser.parse("[00:01.00]Stay (stay with me) tonight\n[00:06.00]next"))
        val auto = t.withWordHighlight(WordHighlight.AUTO)
        assertTrue(auto.lines[0].backing!!.hasWordTiming)
        assertTrue(auto.withWordHighlight(WordHighlight.OFF).lines[0].backing!!.words.isEmpty())
        assertTrue(auto.backingFilled(0, 2_000) > 0f)
    }

    @Test
    fun `kpoe singers and background syllables`() {
        val json = """
            {"type":"Word","lyrics":[
              {"time":1000,"duration":2000,"text":"Run run","element":{"singer":"v2"},"syllabus":[
                {"time":1000,"duration":400,"text":"Run "},{"time":1400,"duration":600,"text":"run"},
                {"time":1500,"duration":500,"text":"(far)","isBackground":true}]}
            ]}
        """.trimIndent()
        val line = LrcParser.parse(KpoeConverter.convert(json)!!.lrc!!).first()
        assertEquals(Voice.SINGER_2, line.voice)
        assertEquals("Run run", line.text)
        assertEquals("far", line.backing!!.text)
    }

    @Test
    fun `paxsenix duet turns and background text`() {
        val json = """
            {"content":[
              {"timestamp":1000,"endtime":2000,"oppositeTurn":false,"text":[{"text":"Mine","timestamp":1000,"endtime":2000}]},
              {"timestamp":3000,"endtime":4000,"oppositeTurn":true,"text":[{"text":"Yours","timestamp":3000,"endtime":4000}],
               "backgroundText":[{"text":"(ours)","timestamp":3500,"endtime":4200}]}
            ]}
        """.trimIndent()
        val lines = LrcParser.parse(PaxsenixConverter.convert(json)!!.lrc!!)
        assertEquals(listOf(Voice.SINGER_1, Voice.SINGER_2), lines.map { it.voice })
        assertEquals("ours", lines[1].backing!!.text)
        assertEquals(4_200L, lines[1].backing!!.endMs)
    }
}
