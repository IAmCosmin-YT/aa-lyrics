package dev.lyricdrive

import android.app.Application
import android.content.Context
import dev.lyricdrive.car.CarDiagnostics

class LyricDriveApp : Application() {
    var startupFailure: Throwable? = null
        private set

    private val graphDelegate = lazy {
        CrashReporter.stage(this, "Initializing playback, settings and cache")
        try {
            AppGraph(this).also { it.sessionObserver.start() }
        } catch (error: RuntimeException) {
            startupFailure = error
            CrashReporter.record(this, "Application graph initialization", error)
            throw error
        } catch (error: LinkageError) {
            startupFailure = error
            CrashReporter.record(this, "Application graph linkage", error)
            throw error
        }
    }
    val graph: AppGraph get() = graphDelegate.value
    val isGraphInitialized: Boolean get() = graphDelegate.isInitialized()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashReporter.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        if (!getProcessName().endsWith(":recovery")) {
            CarDiagnostics.checkPreviousNativeCrash(this)
        }
        // Playback starts only when the normal UI or a playback service needs the graph.
        // Recovery must remain usable even if graph initialization fails.
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (isGraphInitialized) {
            graph.lyricsCache.trimMemory()
            graph.artworkLoader.trimMemory()
        }
    }
}
