package dev.lyricdrive.domain

import android.graphics.Bitmap
import android.os.SystemClock
import dev.lyricdrive.core.domain.LyricsPipeline
import dev.lyricdrive.core.model.Lyrics
import dev.lyricdrive.core.model.LyricsState
import dev.lyricdrive.core.model.PlaybackSnapshot
import dev.lyricdrive.core.model.TrackMetadata
import dev.lyricdrive.core.sync.ElapsedClock
import dev.lyricdrive.core.sync.LyricsEnergy
import dev.lyricdrive.core.sync.LyricsTimeline
import dev.lyricdrive.core.sync.PlaybackClock
import dev.lyricdrive.core.sync.SyncEngine
import dev.lyricdrive.data.media.MediaSessionObserver
import dev.lyricdrive.data.media.ObservedPlayer
import dev.lyricdrive.data.net.ConnectivityObserver
import dev.lyricdrive.data.settings.SettingsRepository
import dev.lyricdrive.ui.theme.AlbumPaletteExtractor
import dev.lyricdrive.ui.theme.LyricPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * App-wide "now playing" state shared by the phone UI, the car screen and the media-card mirror,
 * so there is exactly one lyrics lookup and one playback clock per track.
 */
class NowPlayingSession(
    private val observer: MediaSessionObserver,
    pipeline: LyricsPipeline,
    connectivity: ConnectivityObserver,
    private val artworkLoader: ArtworkLoader,
    settings: SettingsRepository,
    scope: CoroutineScope,
) {
    val clock = ElapsedClock(SystemClock::elapsedRealtime)

    val player: StateFlow<ObservedPlayer?> = observer.player

    val track: StateFlow<TrackMetadata?> = observer.player
        .map { it?.track }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    val lyrics: StateFlow<LyricsState> = pipeline.states(track, connectivity.online)
        .stateIn(scope, SharingStarted.Eagerly, LyricsState.Idle)

    private val fullTimeline: Flow<LyricsTimeline> = lyrics
        .map { state -> ((state as? LyricsState.Ready)?.lyrics as? Lyrics.Synced)?.let { LyricsTimeline(it.lines) } ?: LyricsTimeline.EMPTY }

    /** Timeline used by every renderer; word timings are stripped when word sync is turned off. */
    val timeline: StateFlow<LyricsTimeline> = combine(fullTimeline, settings.wordSync) { timeline, wordSync ->
        if (wordSync) timeline else timeline.withoutWordTiming()
    }
        .stateIn(scope, SharingStarted.Eagerly, LyricsTimeline.EMPTY)

    val energy: StateFlow<Float> = timeline
        .map { LyricsEnergy.estimate(it.lines) }
        .stateIn(scope, SharingStarted.Eagerly, LyricsEnergy.NEUTRAL)

    /** Player-reported state, overridden optimistically right after we send a seek. */
    private val _snapshot = MutableStateFlow(PlaybackSnapshot.NONE)
    val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    val artwork: StateFlow<Bitmap?> = observer.player
        .map { it?.artwork to it?.artworkUri }
        .distinctUntilChanged()
        .mapLatest { (bitmap, uri) -> bitmap ?: uri?.let { artworkLoader.load(it) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val palette: StateFlow<LyricPalette> = artwork
        .mapLatest { AlbumPaletteExtractor.extract(it) }
        .stateIn(scope, SharingStarted.Eagerly, LyricPalette.DEFAULT)

    /** Active-line index, emitted only on change (sleeps until each line boundary). */
    val activeLine: Flow<Int> = SyncEngine.activeLineChanges(timeline, snapshot, clock)

    init {
        observer.player
            .map { it?.snapshot ?: PlaybackSnapshot.NONE }
            .distinctUntilChanged()
            .onEach { _snapshot.value = it }
            .launchIn(scope)
    }

    fun positionNow(): Long = PlaybackClock.positionAt(_snapshot.value, clock.nowMs(), track.value?.durationMs)

    /**
     * Click-to-resync: tell the player to seek and move our clock immediately so the UI jumps to
     * the line without waiting for the player's next PlaybackState.
     */
    fun seekTo(positionMs: Long) {
        val controls = player.value?.controls ?: return
        controls.seekTo(positionMs)
        _snapshot.value = PlaybackClock.seeked(_snapshot.value, positionMs, clock.nowMs())
    }

    fun togglePlayPause() {
        val p = player.value ?: return
        if (_snapshot.value.isPlaying) p.controls.pause() else p.controls.play()
    }

    fun skipToNext() { player.value?.controls?.skipToNext() }
    fun skipToPrevious() { player.value?.controls?.skipToPrevious() }

    fun refreshSessions() = observer.start()
}
