package dev.lyricdrive.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dev.lyricdrive.service.LyricsNotificationListener

object NotificationAccess {
    fun isGranted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun settingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, LyricsNotificationListener::class.java).flattenToString(),
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
}
