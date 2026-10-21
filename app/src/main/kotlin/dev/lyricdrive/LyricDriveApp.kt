package dev.lyricdrive

import android.app.Application
import dev.lyricdrive.car.CarDiagnostics

class LyricDriveApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Record crashes (e.g. in the Android Auto screen) for Settings → Android Auto diagnostics.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CarDiagnostics.recordError(this, "crash on ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        graph = AppGraph(this)
        // No-op (and retried from the listener / UI) until notification access is granted.
        graph.sessionObserver.start()
    }
}
