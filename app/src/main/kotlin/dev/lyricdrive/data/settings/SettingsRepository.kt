package dev.lyricdrive.data.settings

import android.content.Context
import dev.lyricdrive.core.sync.WordHighlight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How lines move and look as playback advances. */
enum class AnimationStyle {
    /** Scaled-down, blurred neighbours, soft spring scroll. */
    FLUID,

    /** Classic karaoke: flat list, the sung part fills in the album accent color. */
    KARAOKE,

    /** Spotlight beam on the active line; everything else falls into shadow. */
    FOCUS,

    /** Bouncy springs: lines pop in with overshoot. */
    BOUNCY,

    /** Words rise a few dp as they're sung, then settle back like a ripple. */
    WAVE,

    /** Words snap in as they're sung, led by a cursor block. */
    TYPEWRITER,

    /** Two subtitle lines in the lower third; the finished line drifts up and dissolves. */
    CINEMATIC,

    /** High legibility: big centered current line, small grey next line, sub-100 ms snaps. */
    GLANCE,

    /** Only the current line, centered, with a thin progress bar. */
    SINGLE_LINE,

    /** No motion: instant scroll, no scaling or blur. Least distracting. */
    MINIMAL,
}

enum class BackgroundMode {
    /** Drifting blobs in album-art colors. */
    ALBUM_FLUID,

    /** The album art itself, heavily blurred, slowly panning. */
    ALBUM_ART,

    /** Still gradient in album-art colors. */
    ALBUM_GRADIENT,

    /** Fixed palettes with fluid motion, independent of the cover. */
    AURORA,
    SUNSET,
    OCEAN,

    /** Deep-space indigo with drifting clouds and static stars. */
    NEBULA,

    /** 80s grid plane scrolling toward a neon horizon. */
    SYNTHWAVE,

    /** Warm sepia, film grain, breathing vignette and a spinning record. */
    LOFI,

    /** Cycling neon, sweeping spotlights, scanlines; pulses on sung words (stronger when parked). */
    DISCO,

    /** Blurred equalizer silhouette along the bottom, moving with the lyrics' rhythm. */
    SPECTRUM,

    /** Pure black (best for OLED screens at night). */
    BLACK,
}

/** Color of the sung part of the active line. */
enum class HighlightColor(val argb: Int?) {
    /** Chosen by the theme / animation style. */
    AUTO(null),

    /** Complement of the album's dominant hue, for maximum contrast against the artwork. */
    COMPLEMENTARY(null),
    WHITE(0xFFFFFFFF.toInt()),
    AMBER(0xFFFF5A00.toInt()),
    CRIMSON(0xFFE50000.toInt()),
    ICE_BLUE(0xFFA0D8EF.toInt()),
    EMERALD(0xFF00E676.toInt()),
}

enum class NightMode {
    /** Follow the car's night mode (headlights / light sensor) or the phone's dark mode. */
    AUTO,

    /** Phone's ambient light sensor: day, dim (tunnel / overcast) and night. Best with a mounted phone. */
    LIGHT_SENSOR,
    ALWAYS,
    NEVER,
}

data class LyricSettings(
    val wordHighlight: WordHighlight = WordHighlight.AUTO,
    val animationStyle: AnimationStyle = AnimationStyle.FLUID,
    val backgroundMode: BackgroundMode = BackgroundMode.ALBUM_FLUID,
    val nightMode: NightMode = NightMode.AUTO,
    val highlightColor: HighlightColor = HighlightColor.AUTO,
    /** Android Auto: expand to the full-screen layout (cover panel + all lines) when parked. */
    val fullScreenWhenParked: Boolean = true,
)

/** User preferences, persisted in SharedPreferences and exposed as one flow. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<LyricSettings> = _settings.asStateFlow()

    fun update(transform: (LyricSettings) -> LyricSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_WORD_HIGHLIGHT, next.wordHighlight.name)
            .putString(KEY_ANIMATION, next.animationStyle.name)
            .putString(KEY_BACKGROUND, next.backgroundMode.name)
            .putString(KEY_NIGHT, next.nightMode.name)
            .putString(KEY_HIGHLIGHT, next.highlightColor.name)
            .putBoolean(KEY_FULL_SCREEN_PARKED, next.fullScreenWhenParked)
            .apply()
        _settings.value = next
    }

    private fun load(): LyricSettings {
        // v1 stored a boolean "word_sync"; false meant whole lines only.
        val legacyWord = if (prefs.contains(KEY_LEGACY_WORD_SYNC) && !prefs.getBoolean(KEY_LEGACY_WORD_SYNC, true)) WordHighlight.OFF else null
        return LyricSettings(
            wordHighlight = enumOr(KEY_WORD_HIGHLIGHT, legacyWord ?: WordHighlight.AUTO),
            animationStyle = enumOr(KEY_ANIMATION, AnimationStyle.FLUID),
            backgroundMode = enumOr(KEY_BACKGROUND, BackgroundMode.ALBUM_FLUID),
            nightMode = enumOr(KEY_NIGHT, NightMode.AUTO),
            highlightColor = enumOr(KEY_HIGHLIGHT, HighlightColor.AUTO),
            fullScreenWhenParked = prefs.getBoolean(KEY_FULL_SCREEN_PARKED, true),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private companion object {
        const val KEY_LEGACY_WORD_SYNC = "word_sync"
        const val KEY_WORD_HIGHLIGHT = "word_highlight"
        const val KEY_ANIMATION = "animation_style"
        const val KEY_BACKGROUND = "background_mode"
        const val KEY_NIGHT = "night_mode"
        const val KEY_HIGHLIGHT = "highlight_color"
        const val KEY_FULL_SCREEN_PARKED = "full_screen_parked"
    }
}
