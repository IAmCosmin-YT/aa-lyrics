package dev.lyricdrive.core.match

import dev.lyricdrive.core.model.TrackMetadata

/** A (title, artist) pair to try against a lyrics provider. */
data class TrackQuery(val title: String, val artist: String, val album: String?, val durationMs: Long?)

/**
 * Cleans up the metadata quirks of streaming players: YouTube-style video titles
 * ("Artist - Song (Official Video)"), "Artist - Topic" / "VEVO" channels, featuring credits
 * and remaster suffixes.
 */
object TrackNormalizer {
    private val NOISE_BRACKETS = Regex(
        """\s*[(\[](?:[^)\]]*?\b(?:official|lyrics?|lyric video|audio|video|visualizer|hd|hq|4k|remaster(?:ed)?|explicit|clean|mv|m/v|feat\.?|ft\.?|featuring|with)\b[^)\]]*)[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val DASH_SUFFIX = Regex(
        """\s+-\s+(?:(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?(?:\s+version)?|single version|radio edit|mono|stereo)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT_INLINE = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val CHANNEL_SUFFIX = Regex("""\s*(?:-\s*topic|vevo|\s+official)$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|;|/|\s+x\s+|\s+and\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+|\s+with\s+)\s*""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("""\s+""")

    fun cleanTitle(title: String): String {
        var t = title
        repeat(3) { t = NOISE_BRACKETS.replace(t, "") }
        t = DASH_SUFFIX.replace(t, "")
        t = FEAT_INLINE.replace(t, "")
        return t.replace(WHITESPACE, " ").trim().ifEmpty { title.trim() }
    }

    fun cleanArtist(artist: String): String =
        CHANNEL_SUFFIX.replace(artist.trim(), "").trim().ifEmpty { artist.trim() }

    fun primaryArtist(artist: String): String =
        cleanArtist(artist).split(ARTIST_SPLIT).firstOrNull { it.isNotBlank() }?.trim() ?: artist.trim()

    /** Query candidates, most specific first, without duplicates. */
    fun queries(track: TrackMetadata): List<TrackQuery> {
        val album = track.album?.takeIf { it.isNotBlank() }
        val duration = track.durationMs?.takeIf { it > 0 }
        val out = LinkedHashSet<TrackQuery>()
        out += TrackQuery(track.title.trim(), track.artist.trim(), album, duration)

        val cleanTitle = cleanTitle(track.title)
        val cleanArtist = cleanArtist(track.artist)
        out += TrackQuery(cleanTitle, cleanArtist, album, duration)
        out += TrackQuery(cleanTitle, primaryArtist(track.artist), album, duration)

        // YouTube video titles: "Artist - Song". Trust the split when the channel name matches
        // the prefix or when the player gave us a generic/channel artist.
        val dash = cleanTitle.indexOf(" - ")
        if (dash > 0) {
            val prefix = cleanTitle.substring(0, dash).trim()
            val rest = cleanTitle(cleanTitle.substring(dash + 3))
            val channelLike = CHANNEL_SUFFIX.containsMatchIn(track.artist) || track.artist.isBlank()
            if (channelLike || similarity(prefix, cleanArtist) > 0.6) {
                out += TrackQuery(rest, prefix, album, duration)
                out += TrackQuery(rest, primaryArtist(prefix), album, duration)
            }
        }
        return out.filter { it.title.isNotBlank() && it.artist.isNotBlank() }
    }

    /** Lower-case alphanumerics only, for comparisons and cache keys. */
    fun canonical(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.replace(WHITESPACE, " ").trim()

    /** Stable cache key independent of video-title noise and featured artists. */
    fun cacheKey(track: TrackMetadata): String =
        canonical(cleanTitle(track.title)) + "|" + canonical(primaryArtist(track.artist))

    /** Token-set Jaccard similarity in 0..1. */
    fun similarity(a: String, b: String): Double {
        val ta = canonical(a).split(' ').filter { it.isNotEmpty() }.toSet()
        val tb = canonical(b).split(' ').filter { it.isNotEmpty() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size
    }
}
