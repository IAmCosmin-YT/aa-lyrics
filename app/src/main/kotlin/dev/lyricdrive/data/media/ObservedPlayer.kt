package dev.lyricdrive.data.media

import android.graphics.Bitmap
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import dev.lyricdrive.core.model.PlaybackSnapshot
import dev.lyricdrive.core.model.TrackMetadata

/** The third-party player we are currently following. */
data class ObservedPlayer(
    val packageName: String,
    val track: TrackMetadata?,
    val snapshot: PlaybackSnapshot,
    val artwork: Bitmap?,
    val artworkUri: String?,
    val controls: PlayerControls,
)

/** Transport controls of the observed player; commands go straight to its media session. */
class PlayerControls(private val controller: MediaControllerCompat) {
    private val transport get() = controller.transportControls

    val canSeek: Boolean
        get() = (controller.playbackState?.actions ?: 0L) and PlaybackStateCompat.ACTION_SEEK_TO != 0L

    fun seekTo(positionMs: Long) = transport.seekTo(positionMs.coerceAtLeast(0))
    fun play() = transport.play()
    fun pause() = transport.pause()
    fun skipToNext() = transport.skipToNext()
    fun skipToPrevious() = transport.skipToPrevious()

    override fun equals(other: Any?) = other is PlayerControls && other.controller.sessionToken == controller.sessionToken
    override fun hashCode() = controller.sessionToken.hashCode()
}
