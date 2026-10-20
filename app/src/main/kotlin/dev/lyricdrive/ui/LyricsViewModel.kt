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
import dev.lyricdrive.ui.theme.LyricPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    val ui: StateFlow<NowPlayingUi> = combine(
        combine(nowPlaying.track, nowPlaying.lyrics, nowPlaying.timeline, ::Triple),
        nowPlaying.snapshot,
        nowPlaying.artwork,
        nowPlaying.palette,
        nowPlaying.energy,
    ) { (track, lyrics, timeline), snapshot, artwork, palette, energy ->
        NowPlayingUi(track, lyrics, timeline, snapshot, artwork, palette, energy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingUi())

    /** False while the user is browsing; auto-scroll resumes 4 s after they let go. */
    private val _following = MutableStateFlow(true)
    val following: StateFlow<Boolean> = _following.asStateFlow()
    private var resumeJob: Job? = null

    fun onUserScrollStarted() {
        resumeJob?.cancel()
        _following.value = false
    }

    fun onUserScrollEnded() {
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            delay(RESUME_DELAY_MS)
            _following.value = true
        }
    }

    fun resumeFollowing() {
        resumeJob?.cancel()
        _following.value = true
    }

    fun onLineTapped(startMs: Long) {
        nowPlaying.seekTo(startMs)
        resumeFollowing()
    }

    fun togglePlayPause() = nowPlaying.togglePlayPause()
    fun skipToNext() = nowPlaying.skipToNext()
    fun skipToPrevious() = nowPlaying.skipToPrevious()
    fun refreshSessions() = nowPlaying.refreshSessions()

    companion object {
        const val RESUME_DELAY_MS = 4_000L
    }
}
