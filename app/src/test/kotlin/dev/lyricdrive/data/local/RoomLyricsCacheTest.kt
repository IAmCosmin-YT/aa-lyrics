package dev.lyricdrive.data.local

import android.app.Application
import androidx.room.Room
import dev.lyricdrive.core.model.RawLyrics
import dev.lyricdrive.core.model.TrackMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RoomLyricsCacheTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LyricsDatabase::class.java).build()
    private val dao = db.lyricsDao()
    private val cache = RoomLyricsCache(dao)
    private val track = TrackMetadata("Invented song", "Test singer", durationMs = 180_000)
    private val raw = RawLyrics("[00:01.00]Invented line", null, false, "test")

    @After fun close() = db.close()

    @Test fun `memory serves repeats and clear removes both layers`() = runBlocking {
        cache.put(track, raw, 123)
        assertEquals(raw.synced, cache.get(track)?.lyrics?.synced)
        dao.clearAll()
        assertNotNull(cache.get(track)) // A repeat lookup does not need the database.
        cache.clear()
        assertNull(cache.get(track))
    }

    @Test fun `new cache instance restores offline lyrics and rejects other recording`() = runBlocking {
        cache.put(track, raw, 123)
        val restored = RoomLyricsCache(dao)
        assertEquals(raw.synced, restored.get(track)?.lyrics?.synced)
        assertNull(restored.get(track.copy(durationMs = 300_000)))
        assertEquals(1, dao.observeSongCount().first())
    }

    @Test fun `oversized lyrics are not retained in memory`() = runBlocking {
        cache.put(track, raw.copy(plain = "x".repeat(600_000)), 123)
        dao.clearAll()
        assertNull(cache.get(track))
    }

    @Test fun `disk cache keeps the newest 500 lookups`() = runBlocking {
        repeat(502) { cache.put(track.copy(title = "Song $it"), raw, it.toLong()) }
        assertEquals(500, dao.observeSongCount().first())
        cache.trimMemory()
        assertNull(cache.get(track.copy(title = "Song 0")))
        assertNotNull(cache.get(track.copy(title = "Song 501")))
    }
}
