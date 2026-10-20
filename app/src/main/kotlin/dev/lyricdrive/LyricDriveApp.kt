package dev.lyricdrive

import android.app.Application

class LyricDriveApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // No-op (and retried from the listener / UI) until notification access is granted.
        graph.sessionObserver.start()
    }
}
