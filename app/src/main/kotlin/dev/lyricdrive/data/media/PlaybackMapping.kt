package dev.lyricdrive.data.media

import android.graphics.Bitmap
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import dev.lyricdrive.core.model.PlaybackSnapshot
import dev.lyricdrive.core.model.PlaybackStatus
import dev.lyricdrive.core.model.TrackMetadata

fun MediaMetadataCompat.toTrack(packageName: String): TrackMetadata? {
    val title = getString(MediaMetadataCompat.METADATA_KEY_TITLE)
        ?: getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE)
        ?: return null
    val artist = getString(MediaMetadataCompat.METADATA_KEY_ARTIST)
        ?: getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST)
        ?: getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE)
        ?: ""
    return TrackMetadata(
        title = title,
        artist = artist,
        album = getString(MediaMetadataCompat.METADATA_KEY_ALBUM),
        durationMs = getLong(MediaMetadataCompat.METADATA_KEY_DURATION).takeIf { it > 0 },
        sourcePackage = packageName,
        mediaUri = getString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI),
    )
}

fun MediaMetadataCompat.artworkBitmap(): Bitmap? =
    getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
        ?: getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
        ?: getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON)

fun MediaMetadataCompat.artworkUri(): String? =
    getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI)
        ?: getString(MediaMetadataCompat.METADATA_KEY_ART_URI)
        ?: getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI)

fun PlaybackStateCompat?.toSnapshot(): PlaybackSnapshot {
    if (this == null) return PlaybackSnapshot.NONE
    val status = when (state) {
        PlaybackStateCompat.STATE_PLAYING,
        PlaybackStateCompat.STATE_FAST_FORWARDING,
        PlaybackStateCompat.STATE_REWINDING -> PlaybackStatus.PLAYING
        PlaybackStateCompat.STATE_BUFFERING,
        PlaybackStateCompat.STATE_CONNECTING,
        PlaybackStateCompat.STATE_SKIPPING_TO_NEXT,
        PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM -> PlaybackStatus.BUFFERING
        PlaybackStateCompat.STATE_PAUSED -> PlaybackStatus.PAUSED
        PlaybackStateCompat.STATE_STOPPED,
        PlaybackStateCompat.STATE_ERROR -> PlaybackStatus.STOPPED
        else -> PlaybackStatus.NONE
    }
    // Some players report PLAYING with speed 0 or never stamp the update time.
    val speed = playbackSpeed.takeIf { it != 0f } ?: if (status == PlaybackStatus.PLAYING) 1f else 0f
    val updateTime = lastPositionUpdateTime.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
    return PlaybackSnapshot(status, position.coerceAtLeast(0), updateTime, speed)
}

fun PlaybackStatus.toCompatState(): Int = when (this) {
    PlaybackStatus.PLAYING -> PlaybackStateCompat.STATE_PLAYING
    PlaybackStatus.PAUSED -> PlaybackStateCompat.STATE_PAUSED
    PlaybackStatus.BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
    PlaybackStatus.STOPPED -> PlaybackStateCompat.STATE_STOPPED
    PlaybackStatus.NONE -> PlaybackStateCompat.STATE_NONE
}
