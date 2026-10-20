package dev.lyricdrive.data.embedded

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dev.lyricdrive.core.domain.LyricsSource
import dev.lyricdrive.core.domain.SourceResult
import dev.lyricdrive.core.embedded.EmbeddedLyricsReader
import dev.lyricdrive.core.model.TrackMetadata
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SYLT/USLT (MP3) and Vorbis-comment (FLAC) lyrics from local files. Streaming apps never expose
 * a readable file, so this only helps local players: either via a readable `METADATA_KEY_MEDIA_URI`
 * or by finding the track in MediaStore (needs READ_MEDIA_AUDIO).
 */
class EmbeddedLyricsSource(private val context: Context) : LyricsSource {
    override val name = EmbeddedLyricsReader.SOURCE

    override suspend fun fetch(track: TrackMetadata): SourceResult = withContext(Dispatchers.IO) {
        val uris = listOfNotNull(track.mediaUri?.let(::readableUri), findInMediaStore(track))
        for (uri in uris) {
            val raw = try {
                context.contentResolver.openInputStream(uri)?.use(EmbeddedLyricsReader::read)
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
            if (raw != null) return@withContext SourceResult.Hit(raw.copy(durationMs = track.durationMs))
        }
        SourceResult.Miss
    }

    private fun readableUri(value: String): Uri? =
        Uri.parse(value).takeIf { it.scheme == "content" || it.scheme == "file" }

    private fun findInMediaStore(track: TrackMetadata): Uri? {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) return null

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DURATION)
        val selection = "${MediaStore.Audio.Media.TITLE} = ? COLLATE NOCASE AND ${MediaStore.Audio.Media.ARTIST} = ? COLLATE NOCASE"
        return try {
            context.contentResolver.query(collection, projection, selection, arrayOf(track.title, track.artist), null)?.use { c ->
                var best: Long? = null
                var bestDelta = Long.MAX_VALUE
                while (c.moveToNext()) {
                    val delta = track.durationMs?.let { abs(c.getLong(1) - it) } ?: 0L
                    if (delta < bestDelta) {
                        bestDelta = delta
                        best = c.getLong(0)
                    }
                }
                best?.let { ContentUris.withAppendedId(collection, it) }
            }
        } catch (e: SecurityException) {
            null
        }
    }
}
