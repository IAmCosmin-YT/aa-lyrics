package dev.lyricdrive.service

import android.os.Bundle
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import dev.lyricdrive.LyricDriveApp
import dev.lyricdrive.R
import dev.lyricdrive.core.model.Lyrics
import dev.lyricdrive.core.model.LyricsState
import dev.lyricdrive.data.media.toCompatState
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Android Auto allows one navigation app at a time, but the media card/rail sits *next to* it.
 * This service exposes a mirror MediaSession whose title is the current lyric line, so picking
 * LyricDrive as the AA media source shows live lyrics beside Google Maps or Waze. Every transport
 * command is forwarded to the real player; we never play audio ourselves.
 */
class LyricsMirrorMediaService : MediaBrowserServiceCompat() {
    private val scope = MainScope()
    private lateinit var session: MediaSessionCompat
    private val nowPlaying by lazy { (application as LyricDriveApp).graph.nowPlaying }
    private val settings by lazy { (application as LyricDriveApp).graph.settings }
    private val card = LyricsArtCard()
    private var cardJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "LyricDriveMirror").apply {
            setCallback(ForwardingCallback())
        }
        sessionToken = session.sessionToken

        // Re-publish on any change, then at each line boundary while playing.
        combine(
            nowPlaying.player,
            nowPlaying.lyrics,
            nowPlaying.artwork,
            nowPlaying.displaySnapshot,
            combine(settings.settings, nowPlaying.timeline, ::Pair),
        ) { player, lyrics, art, snapshot, (prefs, _) -> MirrorState(player != null, lyrics, art, snapshot.isPlaying, prefs.lyricsArtCard) }
            .onEach { state ->
                cardJob?.cancel()
                cardJob = scope.launch {
                    while (true) {
                        val nextUpdate = publishMetadata(state)
                        if (!state.playing || nextUpdate == null) break
                        delay(nextUpdate.coerceAtLeast(MIN_UPDATE_MS))
                    }
                }
            }
            .launchIn(scope)

        nowPlaying.snapshot.onEach { snapshot ->
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(SUPPORTED_ACTIONS)
                    .setState(snapshot.status.toCompatState(), snapshot.positionMs, snapshot.speed, snapshot.updateTimeElapsedMs)
                    .build(),
            )
        }.launchIn(scope)
    }

    private data class MirrorState(
        val hasPlayer: Boolean,
        val lyrics: LyricsState,
        val art: android.graphics.Bitmap?,
        val playing: Boolean,
        val lyricsCard: Boolean,
    )

    /** Publishes metadata for "now"; returns ms until the next useful refresh, or null if none. */
    private fun publishMetadata(state: MirrorState): Long? {
        session.isActive = state.hasPlayer
        val track = nowPlaying.track.value ?: return null
        val synced = ((state.lyrics as? LyricsState.Ready)?.lyrics as? Lyrics.Synced) != null
        val timeline = nowPlaying.timeline.value
        val position = nowPlaying.displayPositionAt(SystemClock.elapsedRealtime())
        val active = if (synced) timeline.activeIndex(position) else -1
        val activeLine = timeline.lines.getOrNull(active)
        val line = when (val l = state.lyrics) {
            is LyricsState.Ready -> when (l.lyrics) {
                // Index into the timeline (it may contain inserted interlude rows).
                is Lyrics.Synced -> activeLine?.let { if (it.isInterlude) "♪" else it.text }
                is Lyrics.Instrumental -> getString(R.string.instrumental)
                is Lyrics.Unsynced -> null
            }
            else -> null
        }
        val artwork = if (state.lyricsCard && synced) {
            card.render(
                artwork = state.art,
                palette = nowPlaying.palette.value,
                line = activeLine,
                next = timeline.lines.getOrNull(active + 1),
                // Whole line lit: Android Auto fetches and cross-fades each new artwork, which lags
                // behind word-by-word updates, so the card changes once per line.
                filled = activeLine?.text?.length?.toFloat() ?: 0f,
                progress = if (active >= 0) timeline.lineProgress(active, position) else 0f,
            )
        } else state.art
        val title = line ?: track.title
        val subtitle = if (line != null) "${track.title} · ${track.artist}" else track.artist
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs ?: -1L)
                .apply { artwork?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )

        return timeline.nextBoundaryAfter(position)?.let { it - position }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (parentId != ROOT_ID) return result.sendResult(mutableListOf())
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(NOW_PLAYING_ID)
            .setTitle(getString(R.string.mirror_root_title))
            .setSubtitle(nowPlaying.track.value?.let { "${it.title} · ${it.artist}" })
            .build()
        result.sendResult(mutableListOf(MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)))
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    private inner class ForwardingCallback : MediaSessionCompat.Callback() {
        private val controls get() = nowPlaying.player.value?.controls
        override fun onPlay() { controls?.play() }
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) { controls?.play() }
        override fun onPause() { controls?.pause() }
        override fun onStop() { controls?.pause() }
        override fun onSkipToNext() { controls?.skipToNext() }
        override fun onSkipToPrevious() { controls?.skipToPrevious() }
        override fun onSeekTo(pos: Long) = nowPlaying.seekTo(pos)
    }

    private companion object {
        const val ROOT_ID = "root"
        /** At most ~2.5 artwork updates per second. */
        const val MIN_UPDATE_MS = 400L
        const val NOW_PLAYING_ID = "now_playing"
        const val SUPPORTED_ACTIONS = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
    }
}
