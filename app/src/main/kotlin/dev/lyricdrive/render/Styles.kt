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

enum class WordEffect {
    /** Kinetic letter engine (every style except the ones below). */
    KINETIC,
    /** Kinetic letters plus a ball hopping between words. */
    BALL,
    /** Letters appear one by one behind a cursor. */
    TYPEWRITER,
}

/**
 * How much of each letter-level effect a style uses (see LyricsPainter.drawKinetic).
 * Scales are fractions of the glyph size; [rotationDeg] is the impact tilt in degrees.
 */
data class Kinetics(
    val wobble: Float = 0.025f,
    val rotationDeg: Float = 2.2f,
    val nudge: Float = 0.038f,
    /** Letter-spacing expansion of the word being sung, as a fraction of each glyph's width. */
    val spacing: Float = 0.05f,
    /** Multi-syllable word growth (0 = off). */
    val crescendo: Float = 1f,
    val waveDp: Float = 3.2f,
    /** Upward lift of the word being sung, as a fraction of the text size. */
    val lift: Float = 0f,
    /** Sustained-note hold (stretch, pulse, glow, spring-back) intensity. */
    val hold: Float = 1f,
    /** Glow intensity for drawn-out syllables. */
    val glow: Float = 1f,
)

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
    /** Constant glow on lit text (on top of the per-letter glow from [kinetics]). */
    val glow: Boolean = false,
    /** Fill the sung part with the theme accent instead of white (when highlight color is Auto). */
    val accentFill: Boolean = false,
    /** Spring for scrolling; null = instant (no motion). */
    val springDamping: Float? = 0.82f,
    val springStiffness: Float = 200f,
    val wordEffect: WordEffect = WordEffect.KINETIC,
    val kinetics: Kinetics = Kinetics(),
    /** Dark outline around the text (classic karaoke). */
    val outline: Boolean = false,
    /** Width of the soft edge at the fill front of line-timed text, in dp. */
    val featherDp: Float = 16f,
    /** Duration of line transitions in focused layouts. */
    val transitionMs: Long = 450,
)

fun AnimationStyle.spec(): LyricStyleSpec = when (this) {
    AnimationStyle.EXPRESSIVE -> LyricStyleSpec(
        inactiveScale = 0.94f, nearAlpha = 0.45f, farAlpha = 0.3f, blurPerStepDp = 1f, activeDimAlpha = 0.35f,
        springDamping = 0.85f, springStiffness = 180f, kinetics = Kinetics(),
    )
    AnimationStyle.BEAUTIFUL -> LyricStyleSpec(
        inactiveScale = 0.92f, nearAlpha = 0.45f, farAlpha = 0.3f, blurPerStepDp = 1.25f, activeDimAlpha = 0.38f,
        springDamping = 0.85f, springStiffness = 170f,
        kinetics = Kinetics(wobble = 0.03f, rotationDeg = 0.8f, nudge = 0.03f, spacing = 0.025f, crescendo = 0.6f, waveDp = 0f, lift = 0.02f),
    )
    AnimationStyle.FLUID -> LyricStyleSpec(
        blurPerStepDp = 1.5f, featherDp = 22f, springDamping = 0.82f, springStiffness = 200f,
        kinetics = Kinetics(wobble = 0.012f, rotationDeg = 0f, nudge = 0.015f, spacing = 0.02f, crescendo = 0.3f, waveDp = 0f, lift = 0.03f, hold = 0.7f, glow = 0.8f),
    )
    AnimationStyle.KARAOKE -> LyricStyleSpec(
        inactiveScale = 1f, nearAlpha = 0.6f, farAlpha = 0.6f, activeDimAlpha = 1f, accentFill = true,
        springDamping = 1f, springStiffness = 400f, outline = true, featherDp = 4f,
        kinetics = Kinetics(wobble = 0.008f, rotationDeg = 0f, nudge = 0f, spacing = 0f, crescendo = 0f, waveDp = 0f, hold = 0.5f, glow = 0.3f),
    )
    AnimationStyle.BOUNCING_BALL -> LyricStyleSpec(
        inactiveScale = 0.9f, activeDimAlpha = 0.55f, accentFill = true, springDamping = 0.85f, springStiffness = 250f,
        wordEffect = WordEffect.BALL, featherDp = 10f,
        kinetics = Kinetics(wobble = 0.03f, rotationDeg = 1.5f, nudge = 0.02f, spacing = 0.02f, crescendo = 0.4f, waveDp = 0f, hold = 0.6f, glow = 0.5f),
    )
    AnimationStyle.TYPEWRITER -> LyricStyleSpec(inactiveScale = 0.9f, activeDimAlpha = 0f, springDamping = 1f, springStiffness = 1500f, wordEffect = WordEffect.TYPEWRITER)
    AnimationStyle.CINEMATIC -> LyricStyleSpec(
        layout = LayoutKind.CINEMATIC, springDamping = 0.9f, springStiffness = 250f, transitionMs = 500, featherDp = 20f,
        kinetics = Kinetics(wobble = 0.015f, rotationDeg = 0.8f, nudge = 0.02f, spacing = 0.03f, crescendo = 0.5f, waveDp = 1.5f, hold = 0.8f, glow = 0.8f),
    )
    // Driving-oriented: steady text, only the gentle fill and a soft hold glow.
    AnimationStyle.GLANCE -> LyricStyleSpec(
        layout = LayoutKind.GLANCE, activeDimAlpha = 0.6f, springDamping = null, transitionMs = 90, featherDp = 10f,
        kinetics = Kinetics(wobble = 0.01f, rotationDeg = 0f, nudge = 0f, spacing = 0f, crescendo = 0f, waveDp = 0f, hold = 0.4f, glow = 0.3f),
    )
    AnimationStyle.SINGLE_LINE -> LyricStyleSpec(
        layout = LayoutKind.SINGLE_LINE, transitionMs = 250, featherDp = 18f,
        kinetics = Kinetics(wobble = 0.015f, rotationDeg = 0.8f, nudge = 0.02f, spacing = 0.03f, crescendo = 0.5f, waveDp = 1.5f, hold = 0.8f, glow = 0.8f),
    )
}

/** Per-theme look: palette, typography, text colors and accents. */
data class ThemeSpec(
    val typeface: Typeface,
    /** Scrim drawn over the background in daylight (0..1). */
    val scrim: Float,
    /** Default fill color when highlight is Auto; null = white / style accent. */
    val highlight: Int? = null,
    /** Fill color for duet singer 2 when highlight is Auto; null = derived from the palette. */
    val highlight2: Int? = null,
    /** Color of unsung / inactive text. */
    val textColor: Int = Color.WHITE,
    /** Whether lit text may glow. */
    val glow: Boolean = true,
    /** Keeps inactive lines readable: no blur, raised minimum opacity. */
    val highContrast: Boolean = false,
    /** Light background with dark text: no night darkening or scrim, no dark shadows. */
    val light: Boolean = false,
    /** Neon flare on each newly sung word (disco). */
    val flare: Boolean = false,
)

private val BOLD = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
private val SERIF = Typeface.create(Typeface.SERIF, Typeface.BOLD)
private val HEAVY = Typeface.create(Typeface.create("sans-serif-black", Typeface.NORMAL), Typeface.NORMAL)

fun BackgroundMode.theme(): ThemeSpec = when (this) {
    BackgroundMode.LOFI -> ThemeSpec(SERIF, scrim = 0.18f, highlight = 0xFFFFE3B0.toInt())
    BackgroundMode.DISCO -> ThemeSpec(BOLD, scrim = 0.18f, flare = true)
    BackgroundMode.BLACK -> ThemeSpec(BOLD, scrim = 0f)
    BackgroundMode.PRISM -> ThemeSpec(BOLD, scrim = 0.28f, highlight2 = 0xFFFFE08A.toInt())
    BackgroundMode.PULSE -> ThemeSpec(BOLD, scrim = 0.3f)
    BackgroundMode.NEON -> ThemeSpec(HEAVY, scrim = 0.15f, highlight2 = 0xFF5CF6FF.toInt())
    BackgroundMode.OLED_MINIMAL -> ThemeSpec(BOLD, scrim = 0f, highlight = Color.WHITE, highlight2 = 0xFF9FD4FF.toInt(), textColor = 0xFFB4B4B4.toInt(), glow = false)
    BackgroundMode.HIGH_CONTRAST -> ThemeSpec(HEAVY, scrim = 0f, highlight = 0xFFFFE600.toInt(), highlight2 = 0xFF00E5FF.toInt(), glow = false, highContrast = true)
    BackgroundMode.PAPER -> ThemeSpec(
        BOLD, scrim = 0f, highlight = 0xFF111111.toInt(), highlight2 = 0xFFB3261E.toInt(),
        textColor = 0xFF2A2724.toInt(), glow = false, highContrast = true, light = true,
    )
    else -> ThemeSpec(BOLD, scrim = 0.38f)
}

object PresetPalettes {
    val LOFI = LyricPalette(0xFFC8803C.toInt(), 0xFF7A4424.toInt(), 0xFFF2C18D.toInt(), 0xFF1E120A.toInt())
    val DISCO = LyricPalette(0xFFFF2BD6.toInt(), 0xFF7A2BFF.toInt(), 0xFF18F0FF.toInt(), 0xFF07020F.toInt())
    val BLACK = LyricPalette(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
    val PRISM = LyricPalette(0xFFFF4D8D.toInt(), 0xFF5B5BFF.toInt(), 0xFF2EE6C8.toInt(), 0xFF07060F.toInt())
    val NEON = LyricPalette(0xFFFF2BD6.toInt(), 0xFF6A2BFF.toInt(), 0xFF18F0FF.toInt(), 0xFF05030C.toInt())
    val PAPER = LyricPalette(0xFFE9E2D3.toInt(), 0xFFDCD2BF.toInt(), 0xFFB3261E.toInt(), 0xFFF5F1E8.toInt())

    /** Disco neon cycle: hot magenta, electric cyan, laser lime, deep violet. */
    val NEON_CYCLE = intArrayOf(0xFFFF2BD6.toInt(), 0xFF18F0FF.toInt(), 0xFFB6FF2E.toInt(), 0xFF7A2BFF.toInt())
}

fun BackgroundMode.palette(album: LyricPalette): LyricPalette = when (this) {
    BackgroundMode.COVER_BLEND, BackgroundMode.ALBUM_FLUID, BackgroundMode.ALBUM_ART, BackgroundMode.ALBUM_GRADIENT,
    BackgroundMode.SPECTRUM, BackgroundMode.PULSE -> album
    BackgroundMode.LOFI -> PresetPalettes.LOFI
    BackgroundMode.DISCO -> PresetPalettes.DISCO
    BackgroundMode.BLACK, BackgroundMode.OLED_MINIMAL, BackgroundMode.HIGH_CONTRAST -> PresetPalettes.BLACK
    BackgroundMode.PRISM -> PresetPalettes.PRISM
    BackgroundMode.NEON -> PresetPalettes.NEON
    BackgroundMode.PAPER -> PresetPalettes.PAPER
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
        mode == BackgroundMode.DISCO -> PresetPalettes.NEON_CYCLE[neonIndex.mod(PresetPalettes.NEON_CYCLE.size)]
        theme.highlight != null -> theme.highlight
        style.accentFill -> readable(palette.accent)
        else -> Color.WHITE
    }
    HighlightColor.COMPLEMENTARY -> complementary(albumPalette.primary)
    else -> setting.argb!!
}

/**
 * Fill color for duet singer 2: the theme's choice, otherwise the palette accent when it's
 * clearly different from [primary], otherwise the complementary hue of [primary].
 */
fun resolveHighlight2(theme: ThemeSpec, palette: LyricPalette, primary: Int, setting: HighlightColor): Int {
    if (setting == HighlightColor.AUTO) theme.highlight2?.let { return it }
    val accent = if (theme.light) palette.accent else readable(palette.accent)
    val distinct = colorDistance(accent, primary) > 0.25f
    return if (distinct) accent else complementary(if (primary == Color.WHITE) palette.primary else primary)
}

private fun colorDistance(a: Int, b: Int): Float {
    val dr = (Color.red(a) - Color.red(b)) / 255f
    val dg = (Color.green(a) - Color.green(b)) / 255f
    val db = (Color.blue(a) - Color.blue(b)) / 255f
    return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
}
