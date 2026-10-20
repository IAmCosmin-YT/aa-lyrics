package dev.lyricdrive.data.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import dev.lyricdrive.core.model.PlaybackStatus
import dev.lyricdrive.service.LyricsNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Follows system-wide media sessions (Spotify, YT Music, Metrolist, ...). Access to other apps'
 * sessions is granted by having [LyricsNotificationListener] enabled in system settings.
 *
 * All callbacks run on the main looper.
 */
class MediaSessionObserver(private val context: Context) {
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, LyricsNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _player = MutableStateFlow<ObservedPlayer?>(null)
    val player: StateFlow<ObservedPlayer?> = _player.asStateFlow()

    private val _hasAccess = MutableStateFlow(false)
    val hasAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()

    private val tracked = LinkedHashMap<MediaSessionCompat.Token, Tracked>()
    private var followed: Tracked? = null
    private var started = false

    private inner class Tracked(val controller: MediaControllerCompat) : MediaControllerCompat.Callback() {
        val controls = PlayerControls(controller)
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) = reevaluate()
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = reevaluate()
        override fun onSessionDestroyed() = refresh()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onSessionsChanged(controllers.orEmpty())
    }

    /** Idempotent; call again after notification access is granted. */
    fun start() {
        if (started) return refresh()
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            started = true
            _hasAccess.value = true
            refresh()
        } catch (e: SecurityException) {
            Log.i(TAG, "Notification access not granted yet")
            _hasAccess.value = false
        }
    }

    fun stop() {
        if (!started) return
        sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        tracked.values.forEach { it.controller.unregisterCallback(it) }
        tracked.clear()
        followed = null
        _player.value = null
        started = false
    }

    fun refresh() {
        try {
            onSessionsChanged(sessionManager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            _hasAccess.value = false
        }
    }

    private fun onSessionsChanged(controllers: List<MediaController>) {
        // Ignore our own mirror session (LyricsMirrorMediaService) to avoid a feedback loop.
        val external = controllers.filter { it.packageName != context.packageName }
        val tokens = external.map { MediaSessionCompat.Token.fromToken(it.sessionToken) }

        tracked.keys.filter { it !in tokens }.forEach { token ->
            tracked.remove(token)?.let { it.controller.unregisterCallback(it) }
        }
        tokens.forEach { token ->
            if (token !in tracked) {
                val compat = MediaControllerCompat(context, token)
                val entry = Tracked(compat)
                compat.registerCallback(entry, handler)
                tracked[token] = entry
            }
        }
        reevaluate()
    }

    /**
     * Pick the session to follow: a playing one (keeping the current one if it still plays), else
     * the one we were following, else the highest-priority session (system order).
     */
    private fun reevaluate() {
        val candidates = tracked.values.toList()
        val playing = candidates.filter { it.controller.playbackState.toSnapshot().status == PlaybackStatus.PLAYING }
        val current = followed?.takeIf { it in candidates }
        followed = when {
            current != null && current in playing -> current
            playing.isNotEmpty() -> playing.first()
            current != null -> current
            else -> candidates.firstOrNull()
        }
        _player.value = followed?.let(::snapshotOf)
    }

    private fun snapshotOf(entry: Tracked): ObservedPlayer {
        val controller = entry.controller
        val metadata = controller.metadata
        return ObservedPlayer(
            packageName = controller.packageName,
            track = metadata?.toTrack(controller.packageName),
            snapshot = controller.playbackState.toSnapshot(),
            artwork = metadata?.artworkBitmap(),
            artworkUri = metadata?.artworkUri(),
            controls = entry.controls,
        )
    }

    private companion object {
        const val TAG = "MediaSessionObserver"
    }
}
