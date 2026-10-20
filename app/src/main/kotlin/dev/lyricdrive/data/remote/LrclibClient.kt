package dev.lyricdrive.data.remote

import dev.lyricdrive.core.match.TrackQuery
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class LrclibTrack(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val durationMs: Long?,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?,
)

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** Minimal client for https://lrclib.net/docs. No API key; asks for a descriptive User-Agent. */
class LrclibClient(
    private val userAgent: String,
    private val baseUrl: String = "https://lrclib.net",
) {
    /** Exact lookup (`/api/get`). Returns null on 404. */
    suspend fun get(query: TrackQuery): LrclibTrack? {
        val params = buildMap {
            put("track_name", query.title)
            put("artist_name", query.artist)
            query.album?.let { put("album_name", it) }
            query.durationMs?.let { put("duration", (it / 1000).toString()) }
        }
        return request("/api/get", params)?.let { parseTrack(JSONObject(it)) }
    }

    /** Fuzzy search (`/api/search`). */
    suspend fun search(title: String, artist: String): List<LrclibTrack> {
        val body = request("/api/search", mapOf("track_name" to title, "artist_name" to artist)) ?: return emptyList()
        val array = JSONArray(body)
        return (0 until array.length()).map { parseTrack(array.getJSONObject(it)) }
    }

    private suspend fun request(path: String, params: Map<String, String>): String? = withContext(Dispatchers.IO) {
        val query = params.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val url = URL("$baseUrl$path?$query")
        try {
            fetchOnce(url)
        } catch (e: HttpStatusException) {
            if (e.code < 500) throw e
            delay(600) // one quick retry for server hiccups
            fetchOnce(url)
        }
    }

    private fun fetchOnce(url: URL): String? {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
        }
        try {
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use { it.readText() }
                HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_BAD_REQUEST -> null
                else -> throw HttpStatusException(code)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTrack(o: JSONObject) = LrclibTrack(
        id = o.optLong("id"),
        trackName = o.optString("trackName"),
        artistName = o.optString("artistName"),
        albumName = o.optStringOrNull("albumName"),
        durationMs = o.optDouble("duration").takeUnless { it.isNaN() }?.let { (it * 1000).toLong() },
        instrumental = o.optBoolean("instrumental", false),
        plainLyrics = o.optStringOrNull("plainLyrics"),
        syncedLyrics = o.optStringOrNull("syncedLyrics"),
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
