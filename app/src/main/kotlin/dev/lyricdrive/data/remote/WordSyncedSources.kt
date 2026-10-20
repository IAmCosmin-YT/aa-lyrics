package dev.lyricdrive.data.remote

import dev.lyricdrive.core.domain.LyricsSource
import dev.lyricdrive.core.domain.SourceResult
import dev.lyricdrive.core.kpoe.KpoeConverter
import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.match.TrackNormalizer
import dev.lyricdrive.core.match.TrackQuery
import dev.lyricdrive.core.model.RawLyrics
import dev.lyricdrive.core.model.TrackMetadata
import dev.lyricdrive.core.ttml.TtmlConverter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Shared GET helper: body on 200, null on 4xx, throws on network errors / 5xx. */
internal fun httpGet(url: String, userAgent: String, timeoutMs: Int = 10_000): String? {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6_000
        readTimeout = timeoutMs
        setRequestProperty("User-Agent", userAgent)
        setRequestProperty("Accept", "application/json")
    }
    try {
        return when (val code = connection.responseCode) {
            HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use { it.readText() }
            in 400..499 -> null
            else -> throw HttpStatusException(code)
        }
    } finally {
        connection.disconnect()
    }
}

internal fun query(vararg params: Pair<String, String?>): String =
    params.filter { !it.second.isNullOrBlank() }.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

/** The two most useful lookups: cleaned metadata first, then the raw metadata. */
internal fun lookupQueries(track: TrackMetadata): List<TrackQuery> =
    TrackNormalizer.queries(track).let { all -> listOfNotNull(all.getOrNull(1), all.firstOrNull()).distinct() }

/** Runs [attempt] for each query; maps network failure to a retryable error, else a miss. */
internal suspend fun firstHit(track: TrackMetadata, attempt: (TrackQuery) -> RawLyrics?): SourceResult = withContext(Dispatchers.IO) {
    var anySuccess = false
    var lastError: IOException? = null
    for (q in lookupQueries(track)) {
        try {
            val raw = attempt(q)
            anySuccess = true
            if (raw != null) return@withContext SourceResult.Hit(raw)
        } catch (e: IOException) {
            lastError = e
        }
    }
    val error = lastError
    if (!anySuccess && error != null) SourceResult.Error(error, retryable = true) else SourceResult.Miss
}

/**
 * Unison (https://unison.boidu.dev), from the BetterLyrics author: aggregates rich-synced
 * (word-level) and line-synced lyrics, returned as TTML or LRC.
 */
class UnisonSource(private val userAgent: String) : LyricsSource {
    override val name = "Unison"
    override val providesWordTiming = true

    override suspend fun fetch(track: TrackMetadata): SourceResult = firstHit(track) { q ->
        val url = "https://unison.boidu.dev/lyrics?" + query(
            "song" to q.title, "artist" to q.artist, "album" to q.album, "duration" to q.durationMs?.let { (it / 1000).toString() },
        )
        val body = httpGet(url, userAgent) ?: return@firstHit null
        val root = JSONObject(body)
        if (!root.optBoolean("success", false)) return@firstHit null
        val data = root.optJSONObject("data") ?: return@firstHit null
        val lyrics = data.optString("lyrics").takeIf { it.isNotBlank() } ?: return@firstHit null
        when (data.optString("format").lowercase()) {
            "ttml" -> TtmlConverter.toEnhancedLrc(lyrics)?.let { RawLyrics(it, null, false, name, track.durationMs) }
            "lrc" -> lyrics.takeIf(LrcParser::looksLikeLrc)?.let { RawLyrics(it, null, false, name, track.durationMs) }
            else -> RawLyrics(null, lyrics, false, name, track.durationMs)
        }
    }
}

/**
 * A LyricsPlus ("KPoe") server, only if the user configured one in settings (self-hosted, or one
 * whose operator allows it). Serves Apple Music / Musixmatch / QQ syllable-synced lyrics.
 */
class LyricsPlusSource(private val userAgent: String, private val serverUrl: () -> String) : LyricsSource {
    override val name = "LyricsPlus"
    override val providesWordTiming = true

    override suspend fun fetch(track: TrackMetadata): SourceResult {
        val base = serverUrl().trim().trimEnd('/').takeIf { it.startsWith("http") } ?: return SourceResult.Miss
        return firstHit(track) { q ->
            val url = "$base/v2/lyrics/get?" + query(
                "title" to q.title, "artist" to q.artist, "album" to q.album,
                "duration" to q.durationMs?.let { (it / 1000).toString() },
            )
            val body = httpGet(url, userAgent) ?: return@firstHit null
            val result = KpoeConverter.convert(body) ?: return@firstHit null
            val label = result.source?.let { "LyricsPlus · $it" } ?: name
            RawLyrics(result.lrc, result.plain, false, label, track.durationMs)
        }
    }
}
