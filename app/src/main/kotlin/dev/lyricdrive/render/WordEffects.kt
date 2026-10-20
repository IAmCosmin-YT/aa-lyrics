package dev.lyricdrive.render

import dev.lyricdrive.core.model.LyricWord

/** Per-word motion curves, in 0..1, evaluated at playback position [p]. */
object WordEffects {
    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val RISE_MS = 140f
    private const val SETTLE_MS = 380f
    private const val HELD_MIN_MS = 800L
    private const val FLARE_MS = 260f

    /** Floats up as the word starts, stays up while sung, settles back after it ends. */
    fun lift(p: Long, word: LyricWord): Float = when {
        p < word.startMs -> 0f
        p < word.startMs + RISE_MS -> smooth((p - word.startMs) / RISE_MS)
        p <= word.endMs -> 1f
        p < word.endMs + SETTLE_MS -> 1f - smooth((p - word.endMs) / SETTLE_MS)
        else -> 0f
    }

    /** 0..1 swell for sustained notes: grows through a long word, then releases. */
    fun held(p: Long, word: LyricWord): Float {
        val duration = word.endMs - word.startMs
        if (duration < HELD_MIN_MS || p < word.startMs) return 0f
        if (p <= word.endMs) return smooth((p - word.startMs).toFloat() / duration)
        val release = (p - word.endMs) / SETTLE_MS
        return if (release < 1f) 1f - smooth(release) else 0f
    }

    /** Brief neon flare on a newly sung word. */
    fun flare(p: Long, word: LyricWord): Float {
        val x = (p - word.startMs) / FLARE_MS
        return if (x in 0f..1f) (1f - x) * (1f - x) else 0f
    }
}
