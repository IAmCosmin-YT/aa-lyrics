package dev.lyricdrive.core

import dev.lyricdrive.core.domain.CachedLyrics
import dev.lyricdrive.core.domain.LyricsCache
import dev.lyricdrive.core.domain.LyricsPipeline
import dev.lyricdrive.core.domain.LyricsRepository
import dev.lyricdrive.core.domain.LyricsResult
import dev.lyricdrive.core.domain.LyricsSource
import dev.lyricdrive.core.domain.SourceResult
import dev.lyricdrive.core.match.TrackNormalizer
import dev.lyricdrive.core.model.Lyrics
import dev.lyricdrive.core.model.LyricsState
import dev.lyricdrive.core.model.RawLyrics
import dev.lyricdrive.core.model.TrackMetadata
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryTest {
    private class MemoryCache : LyricsCache {
        val map = mutableMapOf<String, CachedLyrics>()
        override suspend fun get(track: TrackMetadata) = map[TrackNormalizer.cacheKey(track)]
        override suspend fun put(track: TrackMetadata, lyrics: RawLyrics?, fetchedAtMs: Long) {
            map[TrackNormalizer.cacheKey(track)] = CachedLyrics(lyrics, fetchedAtMs)
        }
    }

    private class FakeSource(override val name: String, var result: () -> SourceResult) : LyricsSource {
        var calls = 0
        override suspend fun fetch(track: TrackMetadata): SourceResult { calls++; return result() }
    }

    private val track = TrackMetadata("Night Drive", "Alpha", durationMs = 200_000)
    private val synced = RawLyrics("[00:01.00]Streetlights blur", null, false, "net")

    @Test
    fun `falls back through sources and caches the hit`() = runTest {
        val cache = MemoryCache()
        val first = FakeSource("a") { SourceResult.Miss }
        val second = FakeSource("b") { SourceResult.Hit(synced) }
        val repo = LyricsRepository(cache, listOf(first, second))

        val result = repo.lyricsFor(track)
        assertTrue((result as LyricsResult.Found).lyrics is Lyrics.Synced)
        repo.lyricsFor(track)
        assertEquals(1, second.calls) // second lookup served from cache
    }

    @Test
    fun `transient errors are not cached, misses are`() = runTest {
        val cache = MemoryCache()
        val source = FakeSource("a") { SourceResult.Error(IOException("offline"), retryable = true) }
        val repo = LyricsRepository(cache, listOf(source))
        assertTrue(repo.lyricsFor(track) is LyricsResult.Unavailable)
        assertTrue(cache.map.isEmpty())

        source.result = { SourceResult.Miss }
        assertEquals(LyricsResult.NotFound, repo.lyricsFor(track))
        assertEquals(LyricsResult.NotFound, repo.lyricsFor(track))
        assertEquals(2, source.calls)
    }

    @Test
    fun `instrumental and plain fallbacks`() {
        assertTrue(LyricsRepository.toLyrics(RawLyrics(null, null, true, "x"), track) is Lyrics.Instrumental)
        assertTrue(LyricsRepository.toLyrics(RawLyrics(null, "line one\nline two", false, "x"), track) is Lyrics.Unsynced)
        assertTrue(LyricsRepository.toLyrics(RawLyrics(null, "[00:01.00]in plain field", false, "x"), track) is Lyrics.Synced)
    }

    @Test
    fun `pipeline debounces bursts and retries when back online`() = runTest {
        val source = FakeSource("net") { SourceResult.Error(IOException("no signal"), retryable = true) }
        val pipeline = LyricsPipeline(LyricsRepository(MemoryCache(), listOf(source)))
        val online = MutableStateFlow(false)
        val tracks = flow {
            emit(TrackMetadata("Night", "")) // partial metadata burst
            emit(TrackMetadata("Night Drive", "Alpha"))
            emit(track)
            awaitCancellation()
        }

        val states = mutableListOf<LyricsState>()
        val job = backgroundScope.launch { pipeline.states(tracks, online).collect { states += it } }
        testScheduler.advanceTimeBy(1_000)
        assertEquals(1, source.calls)
        assertTrue(states.last() is LyricsState.Unavailable)

        source.result = { SourceResult.Hit(synced) }
        online.value = true
        testScheduler.advanceTimeBy(100)
        assertTrue(states.last() is LyricsState.Ready)
        assertEquals(2, source.calls)
        job.cancel()
    }
}
