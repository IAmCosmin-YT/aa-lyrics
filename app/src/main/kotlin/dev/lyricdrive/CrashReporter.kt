package dev.lyricdrive

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File

/** Installed before ContentProviders/AndroidX Startup, with no dependency on the app graph. */
object CrashReporter {
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(context, "Uncaught exception on ${thread.name}", error)
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    fun record(context: Context, stage: String, error: Throwable) {
        Log.e("LyricDrive", stage, error)
        runCatching {
            File(context.filesDir, "last-crash.txt").writeText("$stage\n${error.stackTraceToString()}")
            File(context.filesDir, "crash-pending").writeText("1")
        }
    }

    fun stage(context: Context, name: String) {
        runCatching { File(context.filesDir, "startup-stage.txt").writeText(name) }
    }

    fun beginLaunch(context: Context) {
        runCatching { File(context.filesDir, "startup-pending").writeText(Process.myPid().toString()) }
    }

    fun needsRecovery(context: Context): Boolean = runCatching {
        File(context.filesDir, "crash-pending").exists() ||
            File(context.filesDir, "startup-pending").let {
                it.exists() && it.readText() != Process.myPid().toString()
            }
    }.getOrDefault(false)

    fun clearPending(context: Context) {
        runCatching {
            File(context.filesDir, "crash-pending").delete()
            File(context.filesDir, "startup-pending").delete()
        }
    }

    fun report(context: Context): String = buildString {
        appendLine("LyricDrive ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})")
        appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Last startup stage: " + runCatching { File(context.filesDir, "startup-stage.txt").readText() }.getOrDefault("not recorded"))
        appendLine(runCatching { File(context.filesDir, "last-crash.txt").readText() }.getOrDefault("No Java exception was saved. The process may have ended outside Java."))
        if (android.os.Build.VERSION.SDK_INT >= 30) runCatching {
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 5).forEach { exit ->
                appendLine("Process exit: reason=${exit.reason}, status=${exit.status}, time=${exit.timestamp}, description=${exit.description}")
            }
        }
    }
}
