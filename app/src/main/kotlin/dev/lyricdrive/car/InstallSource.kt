package dev.lyricdrive.car

import android.content.Context
import android.os.Build

/**
 * How the system recorded this app's installation. Android Auto only lists Car App Library apps
 * (the car variant) when **Installed by** is the Play Store and **Requested by** is a package
 * installer or the Play Store — never the adb shell.
 */
object InstallSource {
    const val PLAY_STORE = "com.android.vending"
    private const val SHELL = "com.android.shell"

    data class Info(val installedBy: String?, val requestedBy: String?) {
        val installedByPlay get() = installedBy == PLAY_STORE
        val requestedByShell get() = requestedBy == SHELL
        val requestedByInstaller get() = requestedBy == PLAY_STORE || requestedBy?.contains("packageinstaller") == true

        /** What Android Auto needs to list the car variant. */
        val androidAutoReady get() = installedByPlay && !requestedByShell && (requestedByInstaller || requestedBy == null)
    }

    fun info(context: Context): Info = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = context.packageManager.getInstallSourceInfo(context.packageName)
            Info(i.installingPackageName, i.initiatingPackageName)
        } else {
            @Suppress("DEPRECATION")
            Info(context.packageManager.getInstallerPackageName(context.packageName), null)
        }
    }.getOrDefault(Info(null, null))

    fun installer(context: Context): String? = info(context).installedBy

    fun isFromPlay(context: Context): Boolean = info(context).installedByPlay

    /** adb command that marks the app as installed by the Play Store (shell may do this on most phones). */
    fun adbCommand(context: Context) = "adb shell cmd package set-installer ${context.packageName} $PLAY_STORE"
}
