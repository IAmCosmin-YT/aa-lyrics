package dev.lyricdrive.car

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Installs an APK through the system package installer while asking for the Play Store to be
 * recorded as the installer. On many phones this alone makes Android Auto accept the car
 * variant; where the system ignores the request, the adb command in [InstallSource] finishes it.
 */
object ApkInstaller {
    fun install(context: Context, apk: Uri) {
        val dir = File(context.cacheDir, "apks").apply { mkdirs() }
        val file = File(dir, "LyricDrive-update.apk")
        context.contentResolver.openInputStream(apk)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: error("Can't read the selected file")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.apkprovider", file)
        val play = InstallSource.PLAY_STORE
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, play)
            putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://$play"))
            putExtra(Intent.EXTRA_ORIGINATING_URI, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}"))
        }
        context.startActivity(intent)
    }
}
