package dev.lyricdrive.ui.theme

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** ARGB colors driving the fluid background. [deep] is the darkest base layer. */
data class LyricPalette(
    val primary: Int,
    val secondary: Int,
    val accent: Int,
    val deep: Int,
) {
    companion object {
        val DEFAULT = LyricPalette(
            primary = 0xFF6A3DE8.toInt(),
            secondary = 0xFF2B1A66.toInt(),
            accent = 0xFFFF5E8A.toInt(),
            deep = 0xFF0E0820.toInt(),
        )
    }
}

object AlbumPaletteExtractor {
    suspend fun extract(bitmap: Bitmap?): LyricPalette {
        if (bitmap == null || bitmap.isRecycled) return LyricPalette.DEFAULT
        return withContext(Dispatchers.Default) {
            val palette = Palette.from(bitmap).maximumColorCount(16).resizeBitmapArea(160 * 160).generate()
            val fallback = LyricPalette.DEFAULT
            val primary = palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: fallback.primary
            val secondary = palette.darkVibrantSwatch?.rgb ?: palette.mutedSwatch?.rgb ?: darken(primary, 0.4f)
            val accent = palette.lightVibrantSwatch?.rgb ?: palette.lightMutedSwatch?.rgb ?: fallback.accent
            val deep = palette.darkMutedSwatch?.rgb ?: darken(secondary, 0.6f)
            // Keep the base dark enough for white text in sunlight.
            LyricPalette(primary, secondary, accent, clampLuminance(deep, 0.06))
        }
    }

    private fun darken(color: Int, amount: Float): Int = ColorUtils.blendARGB(color, 0xFF000000.toInt(), amount)

    private fun clampLuminance(color: Int, max: Double): Int {
        var c = color
        repeat(8) { if (ColorUtils.calculateLuminance(c) > max) c = darken(c, 0.25f) }
        return c
    }
}
