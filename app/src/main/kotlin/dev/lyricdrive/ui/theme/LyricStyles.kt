package dev.lyricdrive.ui.theme

import androidx.core.graphics.ColorUtils
import dev.lyricdrive.data.settings.AnimationStyle
import dev.lyricdrive.data.settings.BackgroundMode
import dev.lyricdrive.data.settings.NightMode

/**
 * Everything that differs between animation styles, shared by the Compose phone UI and the
 * car surface painter so both look the same.
 */
data class LyricStyleSpec(
    val activeScale: Float,
    val inactiveScale: Float,
    val nearAlpha: Float,
    val farAlpha: Float,
    /** Blur per line of distance from the active line, in dp (capped at 3 steps). */
    val blurPerStepDp: Float,
    /** Opacity of the not-yet-sung part of the active line. */
    val activeDimAlpha: Float,
    val glow: Boolean,
    /** Fill the sung part with the palette accent instead of white. */
    val accentFill: Boolean,
    /** Spring for scrolling and scale changes; null = instant (no motion). */
    val springDamping: Float?,
    val springStiffness: Float,
)

fun AnimationStyle.spec(): LyricStyleSpec = when (this) {
    AnimationStyle.FLUID -> LyricStyleSpec(1f, 0.85f, 0.5f, 0.4f, 1.5f, 0.45f, glow = true, accentFill = false, springDamping = 0.82f, springStiffness = 200f)
    AnimationStyle.KARAOKE -> LyricStyleSpec(1f, 1f, 0.6f, 0.6f, 0f, 0.95f, glow = false, accentFill = true, springDamping = 1f, springStiffness = 400f)
    AnimationStyle.FOCUS -> LyricStyleSpec(1.06f, 0.8f, 0.25f, 0.12f, 3f, 0.35f, glow = true, accentFill = false, springDamping = 0.9f, springStiffness = 120f)
    AnimationStyle.BOUNCY -> LyricStyleSpec(1.04f, 0.88f, 0.5f, 0.4f, 0f, 0.45f, glow = true, accentFill = false, springDamping = 0.45f, springStiffness = 300f)
    AnimationStyle.MINIMAL -> LyricStyleSpec(1f, 1f, 0.5f, 0.4f, 0f, 0.5f, glow = false, accentFill = false, springDamping = null, springStiffness = 0f)
}

/** Fixed palettes for the cover-independent background modes. */
object PresetPalettes {
    val AURORA = LyricPalette(0xFF19C3A6.toInt(), 0xFF3A2C9E.toInt(), 0xFF7CF2C4.toInt(), 0xFF050B1C.toInt())
    val SUNSET = LyricPalette(0xFFFF6A3D.toInt(), 0xFFB0245C.toInt(), 0xFFFFC15E.toInt(), 0xFF1E0710.toInt())
    val OCEAN = LyricPalette(0xFF1F7AE0.toInt(), 0xFF0B3D6E.toInt(), 0xFF4FD1E8.toInt(), 0xFF020C18.toInt())
    val BLACK = LyricPalette(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
}

/** The palette a background mode actually paints with. */
fun BackgroundMode.palette(album: LyricPalette): LyricPalette = when (this) {
    BackgroundMode.ALBUM_FLUID, BackgroundMode.ALBUM_ART, BackgroundMode.ALBUM_GRADIENT -> album
    BackgroundMode.AURORA -> PresetPalettes.AURORA
    BackgroundMode.SUNSET -> PresetPalettes.SUNSET
    BackgroundMode.OCEAN -> PresetPalettes.OCEAN
    BackgroundMode.BLACK -> PresetPalettes.BLACK
}

val BackgroundMode.isFluid: Boolean
    get() = this == BackgroundMode.ALBUM_FLUID || this == BackgroundMode.AURORA ||
        this == BackgroundMode.SUNSET || this == BackgroundMode.OCEAN

fun NightMode.isNight(systemOrCarIsDark: Boolean): Boolean = when (this) {
    NightMode.AUTO -> systemOrCarIsDark
    NightMode.ALWAYS -> true
    NightMode.NEVER -> false
}

/**
 * Night look: background colors pulled toward black and desaturated so the screen doesn't glow
 * in a dark cabin; text gets dimmer too (see [nightTextAlpha]).
 */
fun LyricPalette.forNight(night: Boolean): LyricPalette {
    if (!night) return this
    fun dim(c: Int, amount: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(c, hsl)
        hsl[1] *= 0.7f
        hsl[2] *= (1f - amount)
        return ColorUtils.HSLToColor(hsl)
    }
    return LyricPalette(dim(primary, 0.55f), dim(secondary, 0.6f), dim(accent, 0.35f), dim(deep, 0.6f))
}

/** Extra scrim (0..1) drawn over the background. */
fun scrimAlpha(night: Boolean): Float = if (night) 0.55f else 0.38f

/** Multiplier for lyric text opacity. */
fun nightTextAlpha(night: Boolean): Float = if (night) 0.82f else 1f

/** Accent fill color made light enough to read on a dark background. */
fun LyricPalette.readableAccent(): Int {
    var c = accent
    repeat(6) { if (ColorUtils.calculateLuminance(c) < 0.35) c = ColorUtils.blendARGB(c, 0xFFFFFFFF.toInt(), 0.25f) }
    return c
}
