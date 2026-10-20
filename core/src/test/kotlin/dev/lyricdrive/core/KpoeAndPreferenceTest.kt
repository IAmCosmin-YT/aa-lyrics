package dev.lyricdrive.core

import dev.lyricdrive.core.domain.CachedLyrics
import dev.lyricdrive.core.domain.LyricsCache
import dev.lyricdrive.core.domain.LyricsRepository
import dev.lyricdrive.core.domain.LyricsResult
import dev.lyricdrive.core.domain.LyricsSource
import dev.lyricdrive.core.domain.SourceResult
import dev.lyricdrive.core.kpoe.KpoeConverter
import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.model.Lyrics
import dev.lyricdrive.core.model.RawLyrics
import dev.lyricdrive.core.model.TrackMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KpoeAndPreferenceTest {
    @Test
    fun `converts kpoe syllables`() {
        val json = """
            {"type":"Word","metadata":{"source":"Apple"},"lyrics":[
              {"time":1000,"duration":2000,"text":"Paper kites","syllabus":[
                {"time":1000,"duration":400,"text":"Pa"},{"time":1400,"duration":300,"text":"per "},
                {"time":1700,"duration":600,"text":"kites"},{"time":1900,"duration":500,"text":"(ooh)","isBackground":true}]},
              {"time":4000,"duration":1000,"text":"No syllables here","syllabus":[]}
            ]}
        """.trimIndent()
        val result = KpoeConverter.convert(json)!!
        assertEquals("Apple", result.source)
        val lines = LrcParser.parse(result.lrc!!)
        assertEquals("Paper kites", lines[0].text)
        assertEquals(listOf("Pa", "per ", "kites"), lines[0].words.map { it.text })
        assertEquals(2_300L, lines[0].words.last().endMs)
        assertEquals("No syllables here", lines[1].text)
    }

    private class NoCache : LyricsCache {
        override suspend fun get(track: TrackMetadata): CachedLyrics? = null
        override suspend fun put(track: TrackMetadata, lyrics: RawLyrics?, fetchedAtMs: Long) = Unit
    }

    private class Source(override val name: String, override val providesWordTiming: Boolean, val lrc: String?) : LyricsSource {
        var calls = 0
        override suspend fun fetch(track: TrackMetadata): SourceResult {
            calls++
            return lrc?.let { SourceResult.Hit(RawLyrics(it, null, false, name)) } ?: SourceResult.Miss
        }
    }

    private val track = TrackMetadata("Song", "Artist")

    @Test
    fun `keeps looking for word timing after a line-synced hit`() = runTest {
        val lineOnly = Source("a", true, "[00:01.00]line")
        val words = Source("b", true, "[00:01.00]<00:01.00>word <00:01.50>")
        val lrclib = Source("c", false, "[00:01.00]other")
        val result = LyricsRepository(NoCache(), listOf(lineOnly, words, lrclib)).lyricsFor(track) as LyricsResult.Found
        assertTrue((result.lyrics as Lyrics.Synced).hasWordTiming)
        assertEquals("b", result.lyrics.source)
        assertEquals(0, lrclib.calls)
    }

    @Test
    fun `falls back to the first line-synced hit`() = runTest {
        val lineOnly = Source("a", true, "[00:01.00]line")
        val miss = Source("b", true, null)
        val lrclib = Source("c", false, "[00:01.00]other")
        val result = LyricsRepository(NoCache(), listOf(lineOnly, miss, lrclib)).lyricsFor(track) as LyricsResult.Found
        assertEquals("a", result.lyrics.source)
        assertEquals(0, lrclib.calls)
    }
}
