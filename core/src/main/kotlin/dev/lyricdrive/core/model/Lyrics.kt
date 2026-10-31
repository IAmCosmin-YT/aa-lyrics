package dev.lyricdrive.core.model

/** A single timed word (or syllable). [text] keeps its surrounding whitespace. */
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** Who sings a line. Duets put [SINGER_1] on the left and [SINGER_2] on the right. */
enum class Voice {
    MAIN, SINGER_1, SINGER_2, TOGETHER;

    companion object {
        /** Maps agent ids used by TTML / enhanced LRC ("v1", "voice2", "v1000") to a voice. */
        fun fromAgent(id: String?): Voice {
            val n = id?.trim()?.lowercase()?.removePrefix("voice")?.removePrefix("v")?.takeIf { it.isNotEmpty() } ?: return MAIN
            return when (n) {
                "1" -> SINGER_1
                "2" -> SINGER_2
                else -> TOGETHER
            }
        }
    }
}

/** Backing vocals sung alongside a line, shown as a smaller sub-line beneath it. */
data class BackingVocals(
    val text: String,
    /** Concatenated texts equal [text]; empty when only line-timed. */
    val words: List<LyricWord>,
    val startMs: Long,
    val endMs: Long,
) {
    val hasWordTiming: Boolean get() = words.isNotEmpty()
}

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Word/syllable timings. Empty for line-synced lyrics. Concatenated texts equal [text]. */
    val words: List<LyricWord> = emptyList(),
    val voice: Voice = Voice.MAIN,
    val backing: BackingVocals? = null,
    /** The whole line is a backing vocal (it was wrapped in parentheses): drawn smaller and subdued. */
    val secondary: Boolean = false,
) {
    val hasWordTiming: Boolean get() = words.isNotEmpty()

    /** Blank timed lines are instrumental breaks; renderers show an interlude marker instead. */
    val isInterlude: Boolean get() = text.isBlank() && backing == null

    /**
     * Until when the line is still being sung. Usually [endMs] (the next line's start), but in
     * duets and with backing vocals the singing can run past the next line's start.
     */
    val activeUntilMs: Long
        get() = maxOf(endMs, words.lastOrNull()?.endMs ?: 0L, backing?.endMs ?: 0L)
}

/** Number of characters lit at [positionMs] (fractional so the fill glides through a word). */
fun filledCharacters(text: String, words: List<LyricWord>, startMs: Long, positionMs: Long): Float {
    if (positionMs < startMs) return 0f
    if (words.isEmpty()) return text.length.toFloat()
    var chars = 0f
    for (word in words) {
        when {
            positionMs >= word.endMs -> chars += word.text.length
            positionMs <= word.startMs -> return chars
            else -> {
                val span = (word.endMs - word.startMs).coerceAtLeast(1)
                return chars + word.text.length * (positionMs - word.startMs).toFloat() / span
            }
        }
    }
    return chars
}

sealed interface Lyrics {
    val source: String

    data class Synced(val lines: List<LyricLine>, override val source: String) : Lyrics {
        val hasWordTiming: Boolean = lines.any { it.hasWordTiming }
    }

    data class Unsynced(val lines: List<String>, override val source: String) : Lyrics

    data class Instrumental(override val source: String) : Lyrics
}

/** Raw lyrics as delivered by a source or stored in the cache, before parsing. */
data class RawLyrics(
    val synced: String?,
    val plain: String?,
    val instrumental: Boolean,
    val source: String,
    val durationMs: Long? = null,
)
