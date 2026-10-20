package dev.lyricdrive.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lyricdrive.LyricDriveApp
import dev.lyricdrive.core.model.LyricsState
import dev.lyricdrive.core.model.PlaybackSnapshot
import dev.lyricdrive.core.model.TrackMetadata
import dev.lyricdrive.core.sync.LyricsTimeline
import dev.lyricdrive.data.settings.LyricSettings
import dev.lyricdrive.ui.theme.LyricPalette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class NowPlayingUi(
    val track: TrackMetadata? = null,
    val lyrics: LyricsState = LyricsState.Idle,
    val timeline: LyricsTimeline = LyricsTimeline.EMPTY,
    val snapshot: PlaybackSnapshot = PlaybackSnapshot.NONE,
    val artwork: Bitmap? = null,
    val palette: LyricPalette = LyricPalette.DEFAULT,
    val energy: Float = 0.5f,
)

class LyricsViewModel(app: Application) : AndroidViewModel(app) {
    private val nowPlaying = (app as LyricDriveApp).graph.nowPlaying
    private val settingsRepository = (app as LyricDriveApp).graph.settings

    val settings: StateFlow<LyricSettings> = settingsRepository.settings
    fun updateSettings(transform: (LyricSettings) -> LyricSettings) = settingsRepository.update(transform)

    val ui: StateFlow<NowPlayingUi> = combine(
        combine(nowPlaying.track, nowPlaying.lyrics, nowPlaying.timeline, ::Triple),
        nowPlaying.snapshot,
        nowPlaying.artwork,
        nowPlaying.palette,
        nowPlaying.energy,
    ) { (track, lyrics, timeline), snapshot, artwork, palette, energy ->
        NowPlayingUi(track, lyrics, timeline, snapshot, artwork, palette, energy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingUi())

    private val graph = (app as LyricDriveApp).graph

    /** Output route currently playing (Android Auto, Bluetooth, ...) and its audio delay. */
    val outputRoute = graph.outputRoute.route
    val routeDelayMs: StateFlow<Int> = nowPlaying.routeDelayMs

    fun setRouteDelay(ms: Int) = settingsRepository.update { s ->
        s.copy(routeDelaysMs = s.routeDelaysMs + (outputRoute.value.name to ms))
    }

    /** Current total lyric offset and this song's own nudge. */
    val offsetMs: StateFlow<Long> = nowPlaying.offsetMs
    fun songOffsetMs(): Long = nowPlaying.songOffsetMs()
    fun nudgeSongOffset(deltaMs: Long) = nowPlaying.nudgeSongOffset(deltaMs)
    fun resetSongOffset() = nowPlaying.resetSongOffset()

    fun togglePlayPause() = nowPlaying.togglePlayPause()
    fun skipToNext() = nowPlaying.skipToNext()
    fun skipToPrevious() = nowPlaying.skipToPrevious()
    fun refreshSessions() = nowPlaying.refreshSessions()

}
