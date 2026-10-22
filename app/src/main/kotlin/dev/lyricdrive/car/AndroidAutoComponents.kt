package dev.lyricdrive.car

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.lyricdrive.data.settings.AndroidAutoMode
import dev.lyricdrive.service.LyricsMirrorMediaService

/**
 * Enables exactly the one component Android Auto should see for the chosen mode. A service's
 * category is fixed in the manifest, so the weather/navigation twins are switched on and off;
 * Android Auto picks the change up when it next connects.
 */
object AndroidAutoComponents {
    fun apply(context: Context, mode: AndroidAutoMode) {
        val pm = context.packageManager
        set(pm, ComponentName(context, LyricsWeatherCarAppService::class.java), mode == AndroidAutoMode.BESIDE_MAPS)
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
