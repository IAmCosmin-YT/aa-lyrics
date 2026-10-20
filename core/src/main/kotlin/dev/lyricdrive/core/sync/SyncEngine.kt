package dev.lyricdrive.core.sync

import dev.lyricdrive.core.model.PlaybackSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

/** Everything a renderer needs for one frame. */
data class SyncFrame(
    val positionMs: Long,
    val activeIndex: Int,
    val filledCharacters: Float,
)

object SyncEngine {
    fun frame(timeline: LyricsTimeline, snapshot: PlaybackSnapshot, nowMs: Long, durationMs: Long? = null): SyncFrame {
        val position = PlaybackClock.positionAt(snapshot, nowMs, durationMs)
        val active = timeline.activeIndex(position)
        return SyncFrame(position, active, timeline.filledCharacters(active, position))
    }

    /**
     * Fixed-rate frame ticker (default ~60 fps). Renderers with a VSync source (Compose
     * `withFrameNanos`, `Choreographer`) should prefer that; this is for surfaces without one.
     */
    fun ticker(clock: ElapsedClock, periodMs: Long = 16): Flow<Long> = flow {
        while (true) {
            emit(clock.nowMs())
            delay(periodMs)
        }
    }

    /**
     * Emits the active line index only when it changes, sleeping exactly until the next line
     * boundary instead of polling. Re-arms whenever playback state or lyrics change (seeks,
     * pauses, speed changes). Used by consumers that only care about whole lines.
     */
    fun activeLineChanges(
        timelines: Flow<LyricsTimeline>,
        snapshots: Flow<PlaybackSnapshot>,
        clock: ElapsedClock,
    ): Flow<Int> = combine(timelines, snapshots, ::Pair)
        .transformLatest { (timeline, snapshot) ->
            while (true) {
                val position = PlaybackClock.positionAt(snapshot, clock.nowMs())
                emit(timeline.activeIndex(position))
                if (!snapshot.isPlaying || snapshot.speed <= 0f) break
                val boundary = timeline.nextBoundaryAfter(position) ?: break
                val wait = ((boundary - position) / snapshot.speed).toLong().coerceAtLeast(1)
                delay(wait + 1)
            }
        }
        .distinctUntilChanged()
}
