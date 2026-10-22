package dev.lyricdrive.car

import android.content.Context
import android.os.Build

/**
 * Android Auto only lists Car App Library apps (the full-screen view) when they were installed by
 * Google Play — Play Store, Internal App Sharing or a testing track. Unknown sources doesn't cover them.
 */
object InstallSource {
    private const val PLAY_STORE = "com.android.vending"

    fun installer(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    fun isFromPlay(context: Context): Boolean = installer(context) == PLAY_STORE
}
