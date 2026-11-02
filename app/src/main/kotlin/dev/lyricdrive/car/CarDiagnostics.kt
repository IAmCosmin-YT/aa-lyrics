package dev.lyricdrive.car

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.text.format.DateUtils
import dev.lyricdrive.BuildConfig
import dev.lyricdrive.service.LyricsMirrorMediaService

/**
 * Records how far Android Auto gets when it opens LyricDrive (service → session → screen →
 * template → surface → first frame) plus the last crash, so a car-side failure can be diagnosed
 * from the phone's Settings without a computer.
 */
object CarDiagnostics {
    /** Native GPU/process crashes bypass Java's uncaught-exception handler. */
    fun checkPreviousNativeCrash(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return
            val recovery = context.getSharedPreferences("recovery", Context.MODE_PRIVATE)
            if (exit.reason != android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                exit.timestamp <= recovery.getLong("native_exit_handled", 0)) return
            recovery.edit().putLong("native_exit_handled", exit.timestamp).putBoolean("pending_crash", true).commit()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ERROR, "Native process crash: ${exit.description.orEmpty()} (status ${exit.status}). Android ended the process outside the Java exception handler.")
                .putLong(KEY_ERROR_AT, exit.timestamp).commit()
        }
    }

    enum class Step(val label: String) {
        SERVICE("Car service created"),
        SESSION("Session created"),
        SCREEN("Screen created"),
        TEMPLATE("Template requested"),
        SURFACE("Drawing surface received"),
        FRAME("First frame drawn"),
    }

    private const val PREFS = "car_diagnostics"
    private const val KEY_ERROR = "last_error"
    private const val KEY_ERROR_AT = "last_error_at"
    private const val GEARHEAD = "com.google.android.projection.gearhead"

    fun mark(context: Context, step: Step) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(step.name, System.currentTimeMillis()).apply()
    }

    fun recordError(context: Context, where: String, error: Throwable) {
        val text = "$where: ${error.stackTraceToString()}".take(3_000)
        // commit(): may be called from a crashing thread.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ERROR, text).putLong(KEY_ERROR_AT, System.currentTimeMillis()).commit()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun report(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        fun ago(t: Long) = if (t == 0L) "never" else DateUtils.getRelativeTimeSpanString(t, now, DateUtils.SECOND_IN_MILLIS).toString()
        val pm = context.packageManager
        fun state(cls: Class<*>) = when (pm.getComponentEnabledSetting(ComponentName(context, cls))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enabled"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disabled"
            else -> "default (enabled)"
        }
        val aa = runCatching { pm.getPackageInfo(GEARHEAD, 0).versionName }.getOrNull() ?: "not installed"
        return buildString {
            appendLine("LyricDrive ${BuildConfig.VERSION_NAME}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Android Auto: $aa")
            val source = InstallSource.info(context)
            appendLine("Installed by: ${source.installedBy ?: "-"} | Requested by: ${source.requestedBy ?: "-"}")
            appendLine("Android Auto can list the car variant: ${if (source.androidAutoReady) "yes" else "no"}")
            appendLine("Variant: ${BuildConfig.FLAVOR}")
            appendLine("Beside-Maps car app (weather): ${state(LyricsWeatherCarAppService::class.java)}")
            appendLine("Full-screen car app (navigation): ${state(LyricsCarAppService::class.java)}")
            appendLine("Media player service: ${state(LyricsMirrorMediaService::class.java)}")
            Step.entries.forEach { appendLine("${it.label}: ${ago(prefs.getLong(it.name, 0))}") }
            val error = prefs.getString(KEY_ERROR, null)
            if (error == null) append("Last error: none") else append("Last error (${ago(prefs.getLong(KEY_ERROR_AT, 0))}):\n$error")
        }
    }
}
