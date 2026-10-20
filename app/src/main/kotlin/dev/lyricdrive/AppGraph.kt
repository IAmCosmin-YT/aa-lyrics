package dev.lyricdrive

import android.app.Application
import androidx.room.Room
import dev.lyricdrive.core.domain.LyricsPipeline
import dev.lyricdrive.core.domain.LyricsRepository
import dev.lyricdrive.data.embedded.EmbeddedLyricsSource
import dev.lyricdrive.data.local.LyricsDatabase
import dev.lyricdrive.data.local.RoomLyricsCache
import dev.lyricdrive.data.media.MediaSessionObserver
import dev.lyricdrive.data.net.ConnectivityObserver
import dev.lyricdrive.data.remote.LrclibClient
import dev.lyricdrive.data.remote.LrclibLyricsSource
import dev.lyricdrive.domain.ArtworkLoader
import dev.lyricdrive.domain.NowPlayingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual DI: one instance per process, created by [LyricDriveApp]. */
class AppGraph(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val database = Room.databaseBuilder(app, LyricsDatabase::class.java, "lyrics.db").build()

    val sessionObserver = MediaSessionObserver(app)

    private val repository = LyricsRepository(
        cache = RoomLyricsCache(database.lyricsDao()),
        // Priority: cache (inside the repository) → LRCLIB → embedded tags.
        sources = listOf(
            LrclibLyricsSource(LrclibClient(userAgent = "LyricDrive/${BuildConfig.VERSION_NAME} (https://github.com/IAmCosmin-YT/aa-lyrics)")),
            EmbeddedLyricsSource(app),
        ),
    )

    val nowPlaying = NowPlayingSession(
        observer = sessionObserver,
        pipeline = LyricsPipeline(repository, debounceMs = 400),
        connectivity = ConnectivityObserver(app),
        artworkLoader = ArtworkLoader(app),
        scope = appScope,
    )
}
