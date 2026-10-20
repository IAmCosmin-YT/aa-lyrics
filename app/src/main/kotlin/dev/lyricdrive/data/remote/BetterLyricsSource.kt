package dev.lyricdrive.data.remote

import dev.lyricdrive.core.domain.LyricsSource
import dev.lyricdrive.core.domain.SourceResult
import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.match.MatchScorer
import dev.lyricdrive.core.match.TrackNormalizer
import dev.lyricdrive.core.model.RawLyrics
import dev.lyricdrive.core.model.TrackMetadata
import dev.lyricdrive.core.ttml.TtmlConverter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Word-synced lyrics from the BetterLyrics API (https://lyrics-api.boidu.dev), which serves
 * Apple-Music-style TTML with per-word/syllable timing, falling back to its KuGou endpoint.
 * TTML is converted to enhanced LRC so it shares the parser, cache and renderers.
 */
class BetterLyricsSource(
    private val userAgent: String,
    private val baseUrl: String = "https://lyrics-api.boidu.dev",
) : LyricsSource {
    override val name = "BetterLyrics"
    override val providesWordTiming = true

    override suspend fun fetch(track: TrackMetadata): SourceResult = withContext(Dispatchers.IO) {
        val queries = TrackNormalizer.queries(track).let { all -> listOfNotNull(all.getOrNull(1), all.firstOrNull()).distinct() }
        var anySuccess = false
        var lastError: IOException? = null

        for (query in queries) {
            for (endpoint in ENDPOINTS) {
                val body = try {
                    request(endpoint, query.title, query.artist, query.album, query.durationMs).also { anySuccess = true }
                } catch (e: IOException) {
                    lastError = e
                    null
                } ?: continue
                val raw = toRaw(body, track) ?: continue
                return@withContext SourceResult.Hit(raw)
            }
        }
        val error = lastError
        if (!anySuccess && error != null) SourceResult.Error(error, retryable = true) else SourceResult.Miss
    }

    private fun toRaw(body: String, track: TrackMetadata): RawLyrics? {
        val trimmed = body.trim()
        val payload = if (trimmed.startsWith("<")) trimmed else runCatching {
            val json = JSONObject(trimmed)
            json.optString("ttml").ifBlank { json.optString("lyrics") }
        }.getOrNull()?.trim().orEmpty()
        if (payload.isBlank()) return null

        val lrc = if (payload.startsWith("<")) {
            // Reject a different recording when the TTML carries its own duration.
            val dur = TtmlConverter.durationMs(payload)
            val expected = track.durationMs
            if (dur != null && expected != null && abs(dur - expected) > MatchScorer.MAX_DURATION_DELTA_MS) return null
            TtmlConverter.toEnhancedLrc(payload)
        } else {
            payload.takeIf(LrcParser::looksLikeLrc)
        } ?: return null
        return RawLyrics(synced = lrc, plain = null, instrumental = false, source = name, durationMs = track.durationMs)
    }

    private fun request(endpoint: String, title: String, artist: String, album: String?, durationMs: Long?): String? {
        val params = buildList {
            add("s" to title)
            add("a" to artist)
            album?.takeIf { it.isNotBlank() }?.let { add("al" to it) }
            durationMs?.takeIf { it > 0 }?.let { add("d" to (it / 1000).toString()) }
        }.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val connection = (URL("$baseUrl/$endpoint?$params").openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", userAgent)
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

    private companion object {
        val ENDPOINTS = listOf("getLyrics", "kugou/getLyrics")
    }
}
