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

    private class MapCache(val now: () -> Long) : LyricsCache {
        val map = mutableMapOf<String, CachedLyrics>()
        override suspend fun get(track: TrackMetadata) = map[track.title]
        override suspend fun put(track: TrackMetadata, lyrics: RawLyrics?, fetchedAtMs: Long) {
            map[track.title] = CachedLyrics(lyrics, fetchedAtMs)
        }
    }

    @Test
    fun `re-checks stale line-synced cache entries against word sources`() = runTest {
        var now = 0L
        val cache = MapCache { now }
        cache.put(track, RawLyrics("[00:01.00]line", null, false, "old"), 0)
        val words = Source("w", true, "[00:01.00]<00:01.00>word <00:01.50>")
        val repo = LyricsRepository(cache, listOf(words), nowMs = { now }, lineSyncedRecheckMs = 1_000)

        now = 500 // fresh: served from cache, no lookup
        assertEquals("old", ((repo.lyricsFor(track) as LyricsResult.Found).lyrics).source)
        assertEquals(0, words.calls)

        now = 2_000 // stale: word source consulted and wins
        assertEquals("w", ((repo.lyricsFor(track) as LyricsResult.Found).lyrics).source)
        assertEquals(1, words.calls)
    }

    @Test
    fun `stale line-synced entry is kept when no word timing exists`() = runTest {
        var now = 0L
        val cache = MapCache { now }
        cache.put(track, RawLyrics("[00:01.00]line", null, false, "old"), 0)
        val miss = Source("w", true, null)
        val repo = LyricsRepository(cache, listOf(miss), nowMs = { now }, lineSyncedRecheckMs = 1_000)
        now = 2_000
        assertEquals("old", ((repo.lyricsFor(track) as LyricsResult.Found).lyrics).source)
        assertEquals(2_000L, cache.map[track.title]!!.fetchedAtMs) // re-stamped: no lookup for a while
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
