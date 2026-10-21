package dev.lyricdrive

import android.app.Application
import androidx.room.Room
import dev.lyricdrive.core.domain.LyricsPipeline
import dev.lyricdrive.core.domain.LyricsRepository
import dev.lyricdrive.car.AndroidAutoComponents
import dev.lyricdrive.data.audio.OutputRouteMonitor
import dev.lyricdrive.data.embedded.EmbeddedLyricsSource
import dev.lyricdrive.data.local.LyricsDatabase
import dev.lyricdrive.data.local.RoomLyricsCache
import dev.lyricdrive.data.media.MediaSessionObserver
import dev.lyricdrive.data.net.ConnectivityObserver
import dev.lyricdrive.data.remote.BetterLyricsSource
import dev.lyricdrive.data.remote.LrclibClient
import dev.lyricdrive.data.remote.LyricsPlusSource
import dev.lyricdrive.data.remote.UnisonSource
import dev.lyricdrive.data.remote.LrclibLyricsSource
import dev.lyricdrive.data.sensors.AmbientLightMonitor
import dev.lyricdrive.data.settings.SettingsRepository
import dev.lyricdrive.domain.ArtworkLoader
import dev.lyricdrive.domain.NowPlayingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** Manual DI: one instance per process, created by [LyricDriveApp]. */
class AppGraph(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Schema bumps clear the cache once so songs cached line-only can pick up word-synced sources.
    private val database = Room.databaseBuilder(app, LyricsDatabase::class.java, "lyrics.db")
        .fallbackToDestructiveMigration()
        .build()

    private val userAgent = "LyricDrive/${BuildConfig.VERSION_NAME} (https://github.com/IAmCosmin-YT/aa-lyrics)"

    val sessionObserver = MediaSessionObserver(app)

    val settings = SettingsRepository(app)

    val ambientLight = AmbientLightMonitor(app)

    init {
        // Keep exactly one Android Auto entry enabled, following the setting.
        settings.settings
            .map { it.androidAutoMode }
            .distinctUntilChanged()
            .onEach { AndroidAutoComponents.apply(app, it) }
            .launchIn(appScope)
    }

    val outputRoute = OutputRouteMonitor(app)

    private val repository = LyricsRepository(
        cache = RoomLyricsCache(database.lyricsDao()),
        // Priority: cache (inside the repository) → word-synced sources (BetterLyrics, Unison, optional
        // LyricsPlus) → LRCLIB → embedded tags. A line-synced hit keeps looking for word timing.
        sources = listOf(
            BetterLyricsSource(userAgent),
            UnisonSource(userAgent),
            LyricsPlusSource(userAgent) { settings.settings.value.lyricsPlusServer },
            LrclibLyricsSource(LrclibClient(userAgent)),
            EmbeddedLyricsSource(app),
        ),
    )

    val nowPlaying = NowPlayingSession(
        observer = sessionObserver,
        pipeline = LyricsPipeline(repository, debounceMs = 400),
        connectivity = ConnectivityObserver(app),
        artworkLoader = ArtworkLoader(app),
        settings = settings,
        outputRoute = outputRoute.route,
        scope = appScope,
    )
}
