package dev.lyricdrive.core

import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.model.PlaybackSnapshot
import dev.lyricdrive.core.model.PlaybackStatus
import dev.lyricdrive.core.sync.ElapsedClock
import dev.lyricdrive.core.sync.LyricsEnergy
import dev.lyricdrive.core.sync.LyricsTimeline
import dev.lyricdrive.core.sync.PlaybackClock
import dev.lyricdrive.core.sync.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncTest {
    private val timeline = LyricsTimeline(
        LrcParser.parse("[00:01.00]<00:01.00>ab<00:02.00>cd<00:03.00>\n[00:05.00]second\n[00:09.00]third"),
    )

    @Test
    fun `interpolates while playing and holds otherwise`() {
        val playing = PlaybackSnapshot(PlaybackStatus.PLAYING, 10_000, 1_000, 1.5f)
        assertEquals(13_000L, PlaybackClock.positionAt(playing, 3_000))
        assertEquals(10_000L, PlaybackClock.positionAt(playing.copy(status = PlaybackStatus.PAUSED), 3_000))
        assertEquals(10_000L, PlaybackClock.positionAt(playing.copy(status = PlaybackStatus.BUFFERING), 3_000))
        assertEquals(12_000L, PlaybackClock.positionAt(playing, 100_000, durationMs = 12_000))
    }

    @Test
    fun `finds active line`() {
        assertEquals(-1, timeline.activeIndex(500))
        assertEquals(0, timeline.activeIndex(1_000))
        assertEquals(0, timeline.activeIndex(4_999))
        assertEquals(1, timeline.activeIndex(5_000))
        assertEquals(2, timeline.activeIndex(60_000))
        assertEquals(5_000L, timeline.nextBoundaryAfter(1_200))
        assertNull(timeline.nextBoundaryAfter(9_500))
    }

    @Test
    fun `fills characters progressively`() {
        assertEquals(0f, timeline.filledCharacters(0, 1_000), 0.001f)
        assertEquals(1f, timeline.filledCharacters(0, 1_500), 0.001f)
        assertEquals(3f, timeline.filledCharacters(0, 2_500), 0.001f)
        assertEquals(4f, timeline.filledCharacters(0, 4_000), 0.001f)
        // Line-synced: fully lit once active
        assertEquals(6f, timeline.filledCharacters(1, 5_001), 0.001f)
    }

    @Test
    fun `active line flow sleeps until boundaries`() = runTest {
        val clock = ElapsedClock { currentTime + 1 }
        val snapshots = MutableStateFlow(PlaybackSnapshot(PlaybackStatus.PLAYING, 0, 1, 1f))
        val changes = SyncEngine.activeLineChanges(flowOf(timeline), snapshots, clock).take(4).toList()
        assertEquals(listOf(-1, 0, 1, 2), changes)
        assertEquals(9_001L, currentTime)
    }

    @Test
    fun `energy reflects line density`() {
        val dense = LrcParser.parse((0 until 20).joinToString("\n") { "[00:%02d.00]x".format(it) })
        val sparse = LrcParser.parse((0 until 8).joinToString("\n") { "[0$it:00.00]x" })
        assertEquals(1f, LyricsEnergy.estimate(dense), 0.001f)
        assertEquals(0f, LyricsEnergy.estimate(sparse), 0.001f)
    }
}
