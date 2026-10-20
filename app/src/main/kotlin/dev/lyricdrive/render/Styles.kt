package dev.lyricdrive.render

import android.graphics.Color
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import dev.lyricdrive.data.sensors.AmbientLevel
import dev.lyricdrive.data.settings.AnimationStyle
import dev.lyricdrive.data.settings.BackgroundMode
import dev.lyricdrive.data.settings.HighlightColor
import dev.lyricdrive.data.settings.NightMode
import dev.lyricdrive.ui.theme.LyricPalette

enum class LayoutKind { LIST, CINEMATIC, GLANCE, SINGLE_LINE }

enum class WordEffect { NONE, WAVE, TYPEWRITER, FLARE }

/** Everything that differs between animation styles. Shared by the phone and the car renderer. */
data class LyricStyleSpec(
    val layout: LayoutKind = LayoutKind.LIST,
    val activeScale: Float = 1f,
    val inactiveScale: Float = 0.85f,
    val nearAlpha: Float = 0.5f,
    val farAlpha: Float = 0.4f,
    /** Blur per line of distance from the active line, in dp (capped at 3 steps). */
    val blurPerStepDp: Float = 0f,
    /** Opacity of the not-yet-sung part of the active line. 0 hides unsung words. */
    val activeDimAlpha: Float = 0.45f,
    val glow: Boolean = true,
    /** Fill the sung part with the theme accent instead of white (when highlight color is Auto). */
    val accentFill: Boolean = false,
    /** Spring for scrolling; null = instant (no motion). */
    val springDamping: Float? = 0.82f,
    val springStiffness: Float = 200f,
    val wordEffect: WordEffect = WordEffect.NONE,
    /** Conical light on the active line, heavy shadow above and below. */
    val spotlight: Boolean = false,
    /** Duration of line transitions in focused layouts. */
    val transitionMs: Long = 450,
)

fun AnimationStyle.spec(): LyricStyleSpec = when (this) {
    AnimationStyle.FLUID -> LyricStyleSpec(blurPerStepDp = 1.5f)
    AnimationStyle.KARAOKE -> LyricStyleSpec(inactiveScale = 1f, nearAlpha = 0.6f, farAlpha = 0.6f, activeDimAlpha = 0.95f, glow = false, accentFill = true, springDamping = 1f, springStiffness = 400f)
    AnimationStyle.FOCUS -> LyricStyleSpec(activeScale = 1.06f, inactiveScale = 0.8f, nearAlpha = 0.15f, farAlpha = 0.06f, blurPerStepDp = 3f, activeDimAlpha = 0.35f, springDamping = 0.9f, springStiffness = 120f, spotlight = true)
    AnimationStyle.BOUNCY -> LyricStyleSpec(activeScale = 1.04f, inactiveScale = 0.88f, springDamping = 0.45f, springStiffness = 300f)
    AnimationStyle.WAVE -> LyricStyleSpec(inactiveScale = 0.88f, blurPerStepDp = 1f, springDamping = 0.8f, springStiffness = 220f, wordEffect = WordEffect.WAVE)
    AnimationStyle.TYPEWRITER -> LyricStyleSpec(inactiveScale = 0.9f, activeDimAlpha = 0f, glow = false, springDamping = 1f, springStiffness = 1500f, wordEffect = WordEffect.TYPEWRITER)
    AnimationStyle.CINEMATIC -> LyricStyleSpec(layout = LayoutKind.CINEMATIC, springDamping = 0.9f, springStiffness = 250f, transitionMs = 500)
    AnimationStyle.GLANCE -> LyricStyleSpec(layout = LayoutKind.GLANCE, activeDimAlpha = 0.6f, glow = false, springDamping = null, transitionMs = 90)
    AnimationStyle.SINGLE_LINE -> LyricStyleSpec(layout = LayoutKind.SINGLE_LINE, transitionMs = 250)
    AnimationStyle.MINIMAL -> LyricStyleSpec(inactiveScale = 1f, activeDimAlpha = 0.5f, glow = false, springDamping = null)
}

/** Per-theme look: palette, typography and accents. */
data class ThemeSpec(
    val typeface: Typeface,
    /** Scrim drawn over the background in daylight (0..1). */
    val scrim: Float,
    /** Default fill color when highlight is Auto; null = white / style accent. */
    val highlight: Int? = null,
    /** RGB split on the sung text (synthwave). */
    val chromatic: Boolean = false,
    /** Neon flare on each newly sung word (disco). */
    val flare: Boolean = false,
)

private val BOLD = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
private val MONO = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
private val SERIF = Typeface.create(Typeface.SERIF, Typeface.BOLD)

fun BackgroundMode.theme(): ThemeSpec = when (this) {
    BackgroundMode.SYNTHWAVE -> ThemeSpec(MONO, scrim = 0.12f, highlight = 0xFF5CF6FF.toInt(), chromatic = true)
    BackgroundMode.LOFI -> ThemeSpec(SERIF, scrim = 0.18f, highlight = 0xFFFFE3B0.toInt())
    BackgroundMode.DISCO -> ThemeSpec(BOLD, scrim = 0.18f, flare = true)
    BackgroundMode.NEBULA -> ThemeSpec(BOLD, scrim = 0.1f)
    BackgroundMode.BLACK -> ThemeSpec(BOLD, scrim = 0f)
    else -> ThemeSpec(BOLD, scrim = 0.38f)
}

object PresetPalettes {
    val AURORA = LyricPalette(0xFF19C3A6.toInt(), 0xFF3A2C9E.toInt(), 0xFF7CF2C4.toInt(), 0xFF050B1C.toInt())
    val SUNSET = LyricPalette(0xFFFF6A3D.toInt(), 0xFFB0245C.toInt(), 0xFFFFC15E.toInt(), 0xFF1E0710.toInt())
    val OCEAN = LyricPalette(0xFF1F7AE0.toInt(), 0xFF0B3D6E.toInt(), 0xFF4FD1E8.toInt(), 0xFF020C18.toInt())
    val NEBULA = LyricPalette(0xFF3B3FA8.toInt(), 0xFF0B1640.toInt(), 0xFF7B5BD6.toInt(), 0xFF04050F.toInt())
    val SYNTHWAVE = LyricPalette(0xFFFF2E97.toInt(), 0xFF6B1FB1.toInt(), 0xFF18E6FF.toInt(), 0xFF0B0420.toInt())
    val LOFI = LyricPalette(0xFFC8803C.toInt(), 0xFF7A4424.toInt(), 0xFFF2C18D.toInt(), 0xFF1E120A.toInt())
    val DISCO = LyricPalette(0xFFFF2BD6.toInt(), 0xFF7A2BFF.toInt(), 0xFF18F0FF.toInt(), 0xFF07020F.toInt())
    val BLACK = LyricPalette(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())

    /** Disco neon cycle: hot magenta, electric cyan, laser lime, deep violet. */
    val NEON = intArrayOf(0xFFFF2BD6.toInt(), 0xFF18F0FF.toInt(), 0xFFB6FF2E.toInt(), 0xFF7A2BFF.toInt())
}

fun BackgroundMode.palette(album: LyricPalette): LyricPalette = when (this) {
    BackgroundMode.ALBUM_FLUID, BackgroundMode.ALBUM_ART, BackgroundMode.ALBUM_GRADIENT, BackgroundMode.SPECTRUM -> album
    BackgroundMode.AURORA -> PresetPalettes.AURORA
    BackgroundMode.SUNSET -> PresetPalettes.SUNSET
    BackgroundMode.OCEAN -> PresetPalettes.OCEAN
    BackgroundMode.NEBULA -> PresetPalettes.NEBULA
    BackgroundMode.SYNTHWAVE -> PresetPalettes.SYNTHWAVE
    BackgroundMode.LOFI -> PresetPalettes.LOFI
    BackgroundMode.DISCO -> PresetPalettes.DISCO
    BackgroundMode.BLACK -> PresetPalettes.BLACK
}

fun resolveAmbient(mode: NightMode, nightSignal: Boolean, sensor: AmbientLevel?): AmbientLevel = when (mode) {
    NightMode.AUTO -> if (nightSignal) AmbientLevel.NIGHT else AmbientLevel.DAY
    NightMode.LIGHT_SENSOR -> sensor ?: if (nightSignal) AmbientLevel.NIGHT else AmbientLevel.DAY
    NightMode.ALWAYS -> AmbientLevel.NIGHT
    NightMode.NEVER -> AmbientLevel.DAY
}

/** Darker, desaturated colors at night (and a little darker when dim) so the cabin isn't lit up. */
fun LyricPalette.forAmbient(level: AmbientLevel): LyricPalette {
    val (sat, light) = when (level) {
        AmbientLevel.DAY -> return this
        AmbientLevel.DIM -> 0.85f to 0.3f
        AmbientLevel.NIGHT -> 0.7f to 0.55f
    }
    fun dim(c: Int, extra: Float = 0f): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(c, hsl)
        hsl[1] *= sat
        hsl[2] *= (1f - (light + extra).coerceAtMost(0.9f))
        return ColorUtils.HSLToColor(hsl)
    }
    return LyricPalette(dim(primary), dim(secondary, 0.05f), dim(accent, -0.2f), dim(deep, 0.05f))
}

/** Scrim over the background: theme base, heavier when dim (tunnel wash) or at night. */
fun scrimFor(theme: ThemeSpec, level: AmbientLevel): Float = when (level) {
    AmbientLevel.DAY -> theme.scrim
    AmbientLevel.DIM -> (theme.scrim + 0.3f).coerceAtMost(0.85f)
    AmbientLevel.NIGHT -> (theme.scrim + 0.2f).coerceAtMost(0.75f)
}

/** Text opacity multiplier; dim keeps full contrast, night eases off the glare. */
fun textAlphaFor(level: AmbientLevel): Float = if (level == AmbientLevel.NIGHT) 0.82f else 1f

/** Makes [color] light enough to read on a dark background. */
fun readable(color: Int): Int {
    var c = color
    repeat(6) { if (ColorUtils.calculateLuminance(c) < 0.35) c = ColorUtils.blendARGB(c, Color.WHITE, 0.25f) }
    return c
}

/** Complementary hue of the album's dominant color, kept vivid and readable. */
fun complementary(color: Int): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    hsl[0] = (hsl[0] + 180f) % 360f
    hsl[1] = hsl[1].coerceAtLeast(0.6f)
    hsl[2] = hsl[2].coerceIn(0.55f, 0.75f)
    return readable(ColorUtils.HSLToColor(hsl))
}

/** Final fill color for the sung text. */
fun resolveHighlight(
    setting: HighlightColor,
    theme: ThemeSpec,
    style: LyricStyleSpec,
    palette: LyricPalette,
    albumPalette: LyricPalette,
    neonIndex: Int,
    mode: BackgroundMode,
): Int = when (setting) {
    HighlightColor.AUTO -> when {
        mode == BackgroundMode.DISCO -> PresetPalettes.NEON[neonIndex.mod(PresetPalettes.NEON.size)]
        theme.highlight != null -> theme.highlight
        style.accentFill -> readable(palette.accent)
        else -> Color.WHITE
    }
    HighlightColor.COMPLEMENTARY -> complementary(albumPalette.primary)
    else -> setting.argb!!
}
