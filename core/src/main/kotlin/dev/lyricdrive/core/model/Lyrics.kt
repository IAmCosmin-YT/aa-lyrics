package dev.lyricdrive.core.model

/** A single timed word (or syllable). [text] keeps its surrounding whitespace. */
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Word/syllable timings. Empty for line-synced lyrics. Concatenated texts equal [text]. */
    val words: List<LyricWord> = emptyList(),
) {
    val hasWordTiming: Boolean get() = words.isNotEmpty()

    /** Blank timed lines are instrumental breaks; renderers show an interlude marker instead. */
    val isInterlude: Boolean get() = text.isBlank()
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
