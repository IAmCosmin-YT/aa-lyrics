package dev.lyricdrive.core.model

/** What the UI should show for the current track. */
sealed interface LyricsState {
    data object Idle : LyricsState

    data class Loading(val track: TrackMetadata) : LyricsState

    data class Ready(val track: TrackMetadata, val lyrics: Lyrics) : LyricsState

    data class NotFound(val track: TrackMetadata) : LyricsState

    /** Lookup failed for a reason that may go away (no signal, server down). Retried automatically. */
    data class Unavailable(val track: TrackMetadata, val reason: String) : LyricsState
}
