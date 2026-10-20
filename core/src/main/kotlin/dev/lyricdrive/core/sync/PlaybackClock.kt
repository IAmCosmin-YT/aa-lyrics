package dev.lyricdrive.core.sync

import dev.lyricdrive.core.model.PlaybackSnapshot
import dev.lyricdrive.core.model.PlaybackStatus

/** Monotonic clock in milliseconds; `SystemClock::elapsedRealtime` on Android. */
fun interface ElapsedClock {
    fun nowMs(): Long
}

object PlaybackClock {
    /**
     * `position = last_reported + (now - last_update) * speed`, only while PLAYING.
     * Buffering and paused players hold their last reported position.
     */
    fun positionAt(snapshot: PlaybackSnapshot, nowElapsedMs: Long, durationMs: Long? = null): Long {
        val base = snapshot.positionMs
        val position = if (snapshot.status == PlaybackStatus.PLAYING && snapshot.updateTimeElapsedMs > 0) {
            val elapsed = (nowElapsedMs - snapshot.updateTimeElapsedMs).coerceAtLeast(0)
            base + (elapsed * snapshot.speed).toLong()
        } else {
            base
        }
        val upper = durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
        return position.coerceIn(0, upper)
    }

    /** Optimistic snapshot after we asked the player to seek, so the UI jumps before the player confirms. */
    fun seeked(snapshot: PlaybackSnapshot, targetMs: Long, nowElapsedMs: Long): PlaybackSnapshot =
        snapshot.copy(positionMs = targetMs.coerceAtLeast(0), updateTimeElapsedMs = nowElapsedMs)
}
