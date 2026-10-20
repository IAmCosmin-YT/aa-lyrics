package dev.lyricdrive.core

import dev.lyricdrive.core.match.Candidate
import dev.lyricdrive.core.match.MatchScorer
import dev.lyricdrive.core.match.TrackNormalizer
import dev.lyricdrive.core.match.TrackQuery
import dev.lyricdrive.core.model.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatchingTest {
    @Test
    fun `cleans video noise`() {
        assertEquals("Night Drive", TrackNormalizer.cleanTitle("Night Drive (Official Music Video)"))
        assertEquals("Night Drive", TrackNormalizer.cleanTitle("Night Drive - Remastered 2011"))
        assertEquals("Night Drive", TrackNormalizer.cleanTitle("Night Drive (feat. Someone) [Lyrics]"))
        assertEquals("Night Drive (Live)", TrackNormalizer.cleanTitle("Night Drive (Live)"))
        assertEquals("The Band", TrackNormalizer.cleanArtist("The Band - Topic"))
        assertEquals("TheBand", TrackNormalizer.cleanArtist("TheBandVEVO"))
        assertEquals("Alpha", TrackNormalizer.primaryArtist("Alpha, Beta & Gamma"))
    }

    @Test
    fun `splits youtube style titles`() {
        val queries = TrackNormalizer.queries(TrackMetadata("Alpha - Night Drive (Official Video)", "AlphaVEVO", durationMs = 200_000))
        assertTrue(queries.any { it.title == "Night Drive" && it.artist == "Alpha" })
    }

    @Test
    fun `cache key ignores noise`() {
        assertEquals(
            TrackNormalizer.cacheKey(TrackMetadata("Night Drive", "Alpha")),
            TrackNormalizer.cacheKey(TrackMetadata("Night Drive (Official Audio)", "Alpha feat. Beta")),
        )
    }

    @Test
    fun `scorer prefers synced matches within duration tolerance`() {
        val q = TrackQuery("Night Drive", "Alpha", null, 200_000)
        val picked = MatchScorer.best(
            q,
            listOf(
                Candidate("Night Drive", "Alpha", 260_000, true, false, "wrong-length"),
                Candidate("Night Drive", "Alpha", 201_000, false, false, "plain"),
                Candidate("Night Drive", "Alpha", 199_000, true, false, "synced"),
                Candidate("Other Song", "Zeta", 200_000, true, false, "unrelated"),
            ),
        )
        assertEquals("synced", picked)
    }
}
