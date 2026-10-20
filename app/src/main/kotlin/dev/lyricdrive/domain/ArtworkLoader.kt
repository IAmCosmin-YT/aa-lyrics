package dev.lyricdrive.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads artwork URIs for players that publish a URI instead of a bitmap. */
class ArtworkLoader(private val context: Context) {
    private val cache = LruCache<String, Bitmap>(8)

    suspend fun load(uri: String): Bitmap? {
        cache.get(uri)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val parsed = Uri.parse(uri)
                when (parsed.scheme) {
                    "content", "file", "android.resource" -> ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(context.contentResolver, parsed),
                    ) { decoder, _, _ ->
                        decoder.setTargetSize(512, 512)
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    "http", "https" -> {
                        val connection = URL(uri).openConnection() as HttpURLConnection
                        connection.connectTimeout = 4_000
                        connection.readTimeout = 6_000
                        try {
                            val bytes = connection.inputStream.use { it.readBytes() }
                            ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, _, _ ->
                                d.setTargetSize(512, 512)
                                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            }
                        } finally {
                            connection.disconnect()
                        }
                    }
                    else -> null
                }
            }.getOrNull()?.also { cache.put(uri, it) }
        }
    }
}
