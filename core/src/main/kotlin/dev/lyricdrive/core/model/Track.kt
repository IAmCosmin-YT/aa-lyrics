package dev.lyricdrive.core.model

/** Metadata of the track currently playing in some third-party player. */
data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    /** Package name of the player that owns the media session. */
    val sourcePackage: String? = null,
    /** `METADATA_KEY_MEDIA_URI`, when the player exposes one (mostly local players). */
    val mediaUri: String? = null,
    /** `METADATA_KEY_MEDIA_ID`; for YouTube Music / Metrolist this is the YouTube video ID. */
    val mediaId: String? = null,
) {
    /** The YouTube video ID, when the player exposes one as its media ID. */
    val youTubeVideoId: String?
        get() = mediaId?.substringAfterLast('/')?.takeIf { YOUTUBE_ID.matches(it) }

    private companion object {
        val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}

enum class PlaybackStatus { PLAYING, PAUSED, BUFFERING, STOPPED, NONE }

/**
 * One playback-state report from the player. [positionMs] was true at [updateTimeElapsedMs]
 * (a `SystemClock.elapsedRealtime()` timestamp); everything in between is interpolated.
 */
data class PlaybackSnapshot(
    val status: PlaybackStatus,
    val positionMs: Long,
    val updateTimeElapsedMs: Long,
    val speed: Float = 1f,
) {
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING

    companion object {
        val NONE = PlaybackSnapshot(PlaybackStatus.NONE, 0L, 0L, 0f)
    }
}
