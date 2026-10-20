package dev.lyricdrive.core

import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.ttml.TtmlConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlConverterTest {
    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
          <head><metadata><translations><translation><text for="L1">ignored</text></translation></translations></metadata></head>
          <body dur="3:05.250"><div>
            <p begin="00:10.500" end="00:13.000">
              <span begin="00:10.500" end="00:11.000">Paper</span> <span begin="00:11.000" end="00:11.400">kites</span>
              <span begin="00:11.400" end="00:11.700">over</span> <span begin="00:11.700" end="00:12.000">the</span>
              <span begin="00:12.000" end="00:12.300">riv</span><span begin="00:12.300" end="00:13.000">er</span>
              <span ttm:role="x-bg" begin="00:12.000" end="00:13.000">(ooh)</span>
            </p>
            <p begin="1:05.2" end="1:08">A line with no word timing</p>
          </div></body>
        </tt>
    """.trimIndent()

    @Test
    fun `converts word spans to enhanced lrc`() {
        val lines = LrcParser.parse(TtmlConverter.toEnhancedLrc(ttml)!!)
        assertEquals(2, lines.size)
        val first = lines[0]
        assertTrue(first.hasWordTiming)
        assertEquals("Paper kites over the river", first.text)
        assertEquals(listOf("Paper ", "kites ", "over ", "the ", "riv", "er"), first.words.map { it.text })
        assertEquals(10_500L, first.words[0].startMs)
        assertEquals(13_000L, first.words.last().endMs)
        assertFalse(lines[1].hasWordTiming)
        assertEquals(65_200L, lines[1].startMs)
    }

    @Test
    fun `parses time formats`() {
        assertEquals(12_500L, TtmlConverter.parseTime("12.5s"))
        assertEquals(1_250L, TtmlConverter.parseTime("1250ms"))
        assertEquals(3_723_400L, TtmlConverter.parseTime("1:02:03.4"))
        assertEquals(62_345L, TtmlConverter.parseTime("01:02.345"))
        assertNull(TtmlConverter.parseTime("abc"))
        assertEquals(1_500L, TtmlConverter.parseTime("0.025m"))
        assertNull(TtmlConverter.toEnhancedLrc("not xml"))
        assertEquals(185_250L, TtmlConverter.durationMs(ttml))
    }
}
