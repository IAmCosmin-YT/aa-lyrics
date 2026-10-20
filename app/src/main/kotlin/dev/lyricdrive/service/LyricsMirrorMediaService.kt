package dev.lyricdrive.service

import android.os.Bundle
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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

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

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "LyricDriveMirror").apply {
            setCallback(ForwardingCallback())
        }
        sessionToken = session.sessionToken

        combine(
            nowPlaying.player,
            nowPlaying.lyrics,
            nowPlaying.artwork,
            nowPlaying.activeLine.onStart { emit(-1) },
        ) { player, lyrics, art, active -> MirrorState(player != null, lyrics, art, active) }
            .onEach(::publishMetadata)
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
        val activeLine: Int,
    )

    private fun publishMetadata(state: MirrorState) {
        session.isActive = state.hasPlayer
        val track = nowPlaying.track.value ?: return
        val line = when (val l = state.lyrics) {
            is LyricsState.Ready -> when (val lyrics = l.lyrics) {
                // Index into the timeline (it may contain inserted interlude rows).
                is Lyrics.Synced -> nowPlaying.timeline.value.lines.getOrNull(state.activeLine)?.let { if (it.isInterlude) "♪" else it.text }
                is Lyrics.Instrumental -> getString(R.string.instrumental)
                is Lyrics.Unsynced -> null
            }
            else -> null
        }
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
                .apply { state.art?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
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
        const val NOW_PLAYING_ID = "now_playing"
        const val SUPPORTED_ACTIONS = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
    }
}
