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

    /** Spotlight on the active line; everything else fades far back. */
    FOCUS,

    /** Bouncy springs: lines pop in with overshoot. */
    BOUNCY,

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

    /** Pure black (best for OLED screens at night). */
    BLACK,
}

enum class NightMode {
    /** Follow the car's night mode (headlights / light sensor) or the phone's dark mode. */
    AUTO,
    ALWAYS,
    NEVER,
}

data class LyricSettings(
    val wordHighlight: WordHighlight = WordHighlight.AUTO,
    val animationStyle: AnimationStyle = AnimationStyle.FLUID,
    val backgroundMode: BackgroundMode = BackgroundMode.ALBUM_FLUID,
    val nightMode: NightMode = NightMode.AUTO,
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
    }
}
