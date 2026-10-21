package dev.lyricdrive.car

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.lyricdrive.data.settings.AndroidAutoMode
import dev.lyricdrive.service.LyricsMirrorMediaService

/**
 * Android Auto lists only one entry per app, and when an app has both a media service and a car
 * app it picks the media player. So exactly one of the two is enabled, per the user's choice.
 */
object AndroidAutoComponents {
    fun apply(context: Context, mode: AndroidAutoMode) {
        val pm = context.packageManager
        set(pm, ComponentName(context, LyricsCarAppService::class.java), mode == AndroidAutoMode.FULL_SCREEN)
        set(pm, ComponentName(context, LyricsMirrorMediaService::class.java), mode == AndroidAutoMode.MEDIA_PLAYER)
    }

    private fun set(pm: PackageManager, component: ComponentName, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (pm.getComponentEnabledSetting(component) != state) {
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }
}
