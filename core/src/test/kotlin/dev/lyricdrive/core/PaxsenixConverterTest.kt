package dev.lyricdrive.core

import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.model.TrackMetadata
import dev.lyricdrive.core.paxsenix.PaxsenixConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaxsenixConverterTest {
    @Test
    fun `converts syllable content array`() {
        val json = """
            {"type":"Syllable","content":[
              {"timestamp":1000,"endtime":3000,"text":[
                {"text":"Win","timestamp":1000,"endtime":1300,"part":true},
                {"text":"dows","timestamp":1300,"endtime":1700,"part":false},
                {"text":"down","timestamp":1700,"endtime":2400,"part":false}]}
            ]}
        """.trimIndent()
        val lines = LrcParser.parse(PaxsenixConverter.convert(json)!!.lrc!!)
        assertEquals("Windows down", lines[0].text)
        assertEquals(listOf("Win", "dows ", "down"), lines[0].words.map { it.text })
        assertEquals(2_400L, lines[0].words.last().endMs)
    }

    @Test
    fun `prefers elrc over content and falls back to plain`() {
        assertEquals("[00:01.00]<00:01.00>a <00:02.00>b", PaxsenixConverter.convert("""{"elrc":"[00:01.00]<00:01.00>a <00:02.00>b","content":[]}""")!!.lrc)
        assertEquals("just text", PaxsenixConverter.convert("""{"plain":"just text"}""")!!.plain)
        assertNull(PaxsenixConverter.convert("""{}"""))
    }

    @Test
    fun `recognises youtube video ids`() {
        assertEquals("dQw4w9WgXcQ", TrackMetadata("t", "a", mediaId = "dQw4w9WgXcQ").youTubeVideoId)
        assertNull(TrackMetadata("t", "a", mediaId = "spotify:track:4uLU6hMCjMI75M1A2tKUQC").youTubeVideoId)
    }
}
