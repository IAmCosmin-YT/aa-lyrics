package dev.lyricdrive.data.settings

import android.content.Context
import dev.lyricdrive.core.sync.WordHighlight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How lines move and look as playback advances. */
enum class AnimationStyle {
    /** Beautiful-Lyrics-style: syllables swell, lift and glow as they're sung; held notes go letter by letter. */
    BEAUTIFUL,

    /** Apple-Music-like: soft gradient wipe, sung words lift and held notes glow; blurred neighbours. */
    FLUID,

    /** Classic karaoke: outlined text, the sung part wipes in the accent color. */
    KARAOKE,

    /** A ball hops from word to word, landing on each one as it's sung. */
    BOUNCING_BALL,

    /** Letters appear one by one as they're sung, led by a blinking cursor. */
    TYPEWRITER,

    /** Two subtitle lines in the lower third; the finished line drifts up and dissolves. */
    CINEMATIC,

    /** High legibility: big centered current line, small grey next line, sub-100 ms snaps. */
    GLANCE,

    /** Only the current line, centered, with a thin progress bar. */
    SINGLE_LINE,

}

enum class BackgroundMode {
    /** Blurred cover art in slowly rotating discs that blend into each other. */
    COVER_BLEND,

    /** Drifting blobs in album-art colors. */
    ALBUM_FLUID,

    /** The album art itself, heavily blurred, slowly panning. */
    ALBUM_ART,

    /** Still gradient in album-art colors. */
    ALBUM_GRADIENT,

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
    val animationStyle: AnimationStyle = AnimationStyle.BEAUTIFUL,
    val backgroundMode: BackgroundMode = BackgroundMode.COVER_BLEND,
    val nightMode: NightMode = NightMode.AUTO,
    val highlightColor: HighlightColor = HighlightColor.AUTO,
    /** Android Auto: expand to the full-screen layout (cover panel + all lines) when parked. */
    val fullScreenWhenParked: Boolean = true,
    /** Shows lyrics this much later, to match audio that lags (Bluetooth, Android Auto). */
    val audioDelayMs: Int = 0,
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
            .putInt(KEY_AUDIO_DELAY, next.audioDelayMs)
            .apply()
        _settings.value = next
    }

    private fun load(): LyricSettings {
        // v1 stored a boolean "word_sync"; false meant whole lines only.
        val legacyWord = if (prefs.contains(KEY_LEGACY_WORD_SYNC) && !prefs.getBoolean(KEY_LEGACY_WORD_SYNC, true)) WordHighlight.OFF else null
        return LyricSettings(
            wordHighlight = enumOr(KEY_WORD_HIGHLIGHT, legacyWord ?: WordHighlight.AUTO),
            animationStyle = enumOr(KEY_ANIMATION, AnimationStyle.BEAUTIFUL),
            backgroundMode = enumOr(KEY_BACKGROUND, BackgroundMode.COVER_BLEND),
            nightMode = enumOr(KEY_NIGHT, NightMode.AUTO),
            highlightColor = enumOr(KEY_HIGHLIGHT, HighlightColor.AUTO),
            fullScreenWhenParked = prefs.getBoolean(KEY_FULL_SCREEN_PARKED, true),
            audioDelayMs = prefs.getInt(KEY_AUDIO_DELAY, 0),
        )
    }

    private val offsetPrefs = context.getSharedPreferences("song_offsets", Context.MODE_PRIVATE)
    private val _songOffsets = MutableStateFlow(offsetPrefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap())

    /** Per-song lyric offsets in ms, keyed by the normalized track key. */
    val songOffsets: StateFlow<Map<String, Long>> = _songOffsets.asStateFlow()

    fun setSongOffset(key: String, offsetMs: Long) {
        if (offsetMs == 0L) offsetPrefs.edit().remove(key).apply() else offsetPrefs.edit().putLong(key, offsetMs).apply()
        _songOffsets.value = if (offsetMs == 0L) _songOffsets.value - key else _songOffsets.value + (key to offsetMs)
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
        const val KEY_AUDIO_DELAY = "audio_delay_ms"
    }
}
