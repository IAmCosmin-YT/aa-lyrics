package dev.lyricdrive.service

import android.service.notification.NotificationListenerService
import dev.lyricdrive.LyricDriveApp

/**
 * Exists only so the user can grant notification access, which is what lets a regular app call
 * `MediaSessionManager.getActiveSessions()`. Notifications themselves are ignored.
 */
class LyricsNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        (application as LyricDriveApp).graph.sessionObserver.start()
    }
}
