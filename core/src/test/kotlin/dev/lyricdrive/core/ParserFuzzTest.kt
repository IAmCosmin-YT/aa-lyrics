package dev.lyricdrive.core

import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.lrc.LrcParser.formatTimestamp
import dev.lyricdrive.core.sync.LyricsTimeline
import dev.lyricdrive.core.sync.WordHighlight
import dev.lyricdrive.core.sync.withWordHighlight
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Random enhanced-LRC (tags, voices, [bg:] lines, parentheses, odd spacing) must never throw, and
 * every parsed line must keep the invariant renderers rely on: word texts concatenate to the text.
 */
class ParserFuzzTest {
    private val pieces = listOf("la", "ooh", "(", ")", "(ooh)", "( yeah )", "me)", "(be", " ", "  ", "-", "'", "é", "日本", ",", "...", "()", "( )", "((", "))")

    private fun randomBody(r: Random, start: Long): String {
        val sb = StringBuilder()
        var t = start
        repeat(r.nextInt(0, 9)) {
            if (r.nextInt(3) > 0) {
                t += r.nextLong(-200, 900)
                sb.append(if (r.nextBoolean()) " " else "").append('<').append(formatTimestamp(t)).append('>')
                if (r.nextInt(4) == 0) sb.append(' ')
            }
            sb.append(pieces[r.nextInt(pieces.size)])
        }
        if (r.nextBoolean()) sb.append('<').append(formatTimestamp(t + r.nextLong(0, 1500))).append('>')
        return sb.toString()
    }

    private fun randomLrc(r: Random): String {
        val sb = StringBuilder()
        var t = r.nextLong(0, 5000)
        repeat(r.nextInt(1, 14)) {
            t += r.nextLong(-1500, 6000)
            if (t < 0) t = 0
            when (r.nextInt(10)) {
                0 -> sb.append("[bg: ").append(randomBody(r, t)).append("]\n")
                1 -> sb.append('[').append(formatTimestamp(t)).append("]{bg}").append(randomBody(r, t)).append('\n')
                2 -> sb.append('[').append(formatTimestamp(t)).append("]\n")
                3 -> sb.append("[offset:").append(r.nextInt(-3000, 3000)).append("]\n")
                else -> {
                    sb.append('[').append(formatTimestamp(t)).append(']')
                    if (r.nextBoolean()) sb.append(listOf("v1: ", "v2:", "v1000: ", "{agent:v2}", "V3 : ").random(r))
                    sb.append(randomBody(r, t)).append('\n')
                }
            }
        }
        return sb.toString()
    }

    @Test
    fun `random lyrics never break parsing or timing`() {
        val r = Random(1234)
        repeat(20_000) { n ->
            val lrc = randomLrc(r)
            try {
                val lines = LrcParser.parse(lrc, if (r.nextBoolean()) r.nextLong(1, 120_000) else null)
                lines.forEach { line ->
                    if (line.words.isNotEmpty()) assertEquals(lrc, line.text, line.words.joinToString("") { it.text })
                    line.backing?.let { b -> if (b.words.isNotEmpty()) assertEquals(lrc, b.text, b.words.joinToString("") { it.text }) }
                }
                WordHighlight.values().forEach { mode ->
                    val tl = LyricsTimeline(lines).withWordHighlight(mode).withInterludes()
                    tl.lines.forEach { line ->
                        if (line.words.isNotEmpty()) assertEquals(lrc, line.text, line.words.joinToString("") { it.text })
                        line.backing?.let { b -> if (b.words.isNotEmpty()) assertEquals(lrc, b.text, b.words.joinToString("") { it.text }) }
                    }
                    for (p in listOf(-1L, 0L, 1_000L, 10_000L, 60_000L, 200_000L)) {
                        tl.activeIndices(p)
                        val a = tl.activeIndex(p)
                        tl.filledCharacters(a, p)
                        tl.backingFilled(a, p)
                        tl.lineProgress(a, p)
                    }
                }
            } catch (e: Throwable) {
                throw AssertionError("case $n failed:\n$lrc", e)
            }
        }
    }

    @Test
    fun `whole-line parentheses keep text and words in step`() {
        val line = LrcParser.parse("[00:01.00]<00:01.00>( Ooh <00:01.50>la  la )<00:02.00>").single()
        assertEquals(true, line.secondary)
        assertEquals(line.text, line.words.joinToString("") { it.text })
        assertEquals("Ooh la  la", line.text)
    }

    @Test
    fun `ttml converts a simple paragraph`() {
        val lrc = dev.lyricdrive.core.ttml.TtmlConverter.toEnhancedLrc(
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1.0" end="2.0"><span begin="1.0" end="2.0">Hi</span></p></div></body></tt>""",
        )
        assertEquals("[00:01.00]<00:01.00>Hi<00:02.00>\n", lrc)
    }
}
