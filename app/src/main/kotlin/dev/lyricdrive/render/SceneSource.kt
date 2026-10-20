package dev.lyricdrive.render

import android.graphics.Bitmap
import android.os.SystemClock
import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.model.Lyrics
import dev.lyricdrive.core.model.LyricsState
import dev.lyricdrive.core.model.TrackMetadata
import dev.lyricdrive.core.sync.LyricsTimeline
import dev.lyricdrive.core.sync.PlaybackClock
import dev.lyricdrive.core.sync.WordHighlight
import dev.lyricdrive.core.sync.withWordHighlight
import dev.lyricdrive.domain.NowPlayingSession
import dev.lyricdrive.ui.theme.LyricPalette

/** What the lyrics scene draws. Live playback or the settings preview. */
interface SceneSource {
    val track: TrackMetadata?
    val lyrics: LyricsState
    val timeline: LyricsTimeline
    val isPlaying: Boolean
    val palette: LyricPalette
    val artwork: Bitmap?
    val energy: Float
    fun positionMs(nowElapsedMs: Long): Long
    fun seekTo(positionMs: Long)
}

class LiveSceneSource(private val session: NowPlayingSession) : SceneSource {
    override val track get() = session.track.value
    override val lyrics get() = session.lyrics.value
    override val timeline get() = session.timeline.value
    override val isPlaying get() = session.snapshot.value.isPlaying
    override val palette get() = session.palette.value
    override val artwork get() = session.artwork.value
    override val energy get() = session.energy.value
    override fun positionMs(nowElapsedMs: Long) = PlaybackClock.positionAt(session.snapshot.value, nowElapsedMs, track?.durationMs)
    override fun seekTo(positionMs: Long) = session.seekTo(positionMs)
}

/**
 * Looping sample for the settings preview, with real word timing so every style can be seen.
 * Uses the live album colors/artwork when something is playing.
 */
class PreviewSceneSource(
    private val live: SceneSource?,
    private val wordHighlight: () -> WordHighlight,
) : SceneSource {
    private val sample = LrcParser.parse(SAMPLE, LOOP_MS)
    private val baseTimeline = LyricsTimeline(sample)
    private var cachedMode: WordHighlight? = null
    private var cachedTimeline = baseTimeline
    private var origin = SystemClock.elapsedRealtime()

    override val track = TrackMetadata("Preview", "LyricDrive")
    override val lyrics: LyricsState = LyricsState.Ready(track, Lyrics.Synced(sample, "preview"))
    override val timeline: LyricsTimeline
        get() {
            val mode = wordHighlight()
            if (mode != cachedMode) {
                cachedMode = mode
                cachedTimeline = baseTimeline.withWordHighlight(mode)
            }
            return cachedTimeline
        }
    override val isPlaying = true
    override val palette get() = live?.palette ?: LyricPalette.DEFAULT
    override val artwork get() = live?.artwork
    override val energy = 0.6f
    override fun positionMs(nowElapsedMs: Long) = ((nowElapsedMs - origin) % LOOP_MS + LOOP_MS) % LOOP_MS
    override fun seekTo(positionMs: Long) {
        origin = SystemClock.elapsedRealtime() - positionMs
    }

    private companion object {
        const val LOOP_MS = 15_000L
        // Original sample text written for the preview.
        val SAMPLE = """
            [00:00.30]<00:00.30>Headlights <00:00.90>paint <00:01.30>the <00:01.50>empty <00:02.10>road <00:02.80>
            [00:03.20]<00:03.20>Counting <00:03.80>every <00:04.30>mile <00:04.80>marker <00:05.50>home <00:06.20>
            [00:06.60]<00:06.60>Windows <00:07.10>down, <00:07.60>the <00:07.80>radio <00:08.50>hums <00:09.20>
            [00:09.60]<00:09.60>Somewhere <00:10.30>past <00:10.70>the <00:10.90>county <00:11.50>line <00:12.30>
            [00:12.70]
        """.trimIndent()
    }
}
