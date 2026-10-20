package dev.lyricdrive.core

import dev.lyricdrive.core.embedded.EmbeddedLyricsReader
import dev.lyricdrive.core.lrc.LrcParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedLyricsReaderTest {
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun syncsafe(v: Int) = byteArrayOf((v shr 21 and 0x7F).toByte(), (v shr 14 and 0x7F).toByte(), (v shr 7 and 0x7F).toByte(), (v and 0x7F).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun id3v24(vararg frames: Pair<String, ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (id, data) ->
            body.write(id.toByteArray()); body.write(syncsafe(data.size)); body.write(byteArrayOf(0, 0)); body.write(data)
        }
        body.write(ByteArray(16)) // padding
        val tag = body.toByteArray()
        return "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncsafe(tag.size) + tag + byteArrayOf(0x55, 0x55)
    }

    private fun sylt(vararg entries: Pair<String, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(3)); out.write("eng".toByteArray()); out.write(byteArrayOf(2, 1)); out.write(byteArrayOf(0))
        entries.forEach { (text, ms) -> out.write(text.toByteArray()); out.write(0); out.write(be32(ms)) }
        return out.toByteArray()
    }

    @Test
    fun `reads line-mode SYLT`() {
        val raw = EmbeddedLyricsReader.read(ByteArrayInputStream(id3v24("SYLT" to sylt("Porch light on" to 1_000, "Engine ticking" to 4_500))))!!
        val lines = LrcParser.parse(raw.synced!!)
        assertEquals(listOf(1_000L, 4_500L), lines.map { it.startMs })
        assertEquals("Engine ticking", lines[1].text)
    }

    @Test
    fun `reads syllable-mode SYLT as enhanced lrc`() {
        val raw = EmbeddedLyricsReader.read(ByteArrayInputStream(id3v24("SYLT" to sylt("Low " to 1_000, "tide" to 1_500, "\nHigh " to 3_000, "noon" to 3_400))))!!
        val lines = LrcParser.parse(raw.synced!!)
        assertEquals(2, lines.size)
        assertEquals("Low tide", lines[0].text)
        assertEquals(listOf(3_000L, 3_400L), lines[1].words.map { it.startMs })
    }

    @Test
    fun `reads USLT plain lyrics`() {
        val uslt = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + "Line one\nLine two".toByteArray()
        val raw = EmbeddedLyricsReader.read(ByteArrayInputStream(id3v24("TIT2" to byteArrayOf(3, 65), "USLT" to uslt)))!!
        assertNull(raw.synced)
        assertEquals("Line one\nLine two", raw.plain)
    }

    @Test
    fun `reads flac vorbis comments`() {
        val vendor = "test".toByteArray()
        val comments = listOf("TITLE=x", "LYRICS=[00:02.00]Gravel under tyres").map { it.toByteArray() }
        val block = ByteArrayOutputStream().apply {
            write(le32(vendor.size)); write(vendor); write(le32(comments.size))
            comments.forEach { write(le32(it.size)); write(it) }
        }.toByteArray()
        val streamInfo = ByteArray(34)
        val file = "fLaC".toByteArray() +
            byteArrayOf(0, 0, 0, 34) + streamInfo +
            byteArrayOf(0x84.toByte(), (block.size shr 16).toByte(), (block.size shr 8).toByte(), block.size.toByte()) + block
        val raw = EmbeddedLyricsReader.read(ByteArrayInputStream(file))!!
        assertTrue(raw.synced!!.contains("Gravel"))
    }

    @Test
    fun `ignores files without tags`() {
        assertNull(EmbeddedLyricsReader.read(ByteArrayInputStream(ByteArray(64) { 0x11 })))
    }
}
