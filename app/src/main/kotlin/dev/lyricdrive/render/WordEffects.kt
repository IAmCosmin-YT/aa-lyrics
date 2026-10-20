package dev.lyricdrive.render

import dev.lyricdrive.core.model.LyricWord
import kotlin.math.PI
import kotlin.math.sin

/** Per-word motion curves, in 0..1, evaluated at playback position [p]. */
object WordEffects {
    private const val WAVE_MS = 450f
    private const val POP_MS = 120f
    private const val FLARE_MS = 260f

    /** Rise-and-settle bump right after the word starts. */
    fun lift(p: Long, word: LyricWord): Float {
        val x = (p - word.startMs) / WAVE_MS
        return if (x in 0f..1f) sin(PI.toFloat() * x) else 0f
    }

    /** Snappy overshoot as the word appears. */
    fun pop(p: Long, word: LyricWord): Float {
        val x = (p - word.startMs) / POP_MS
        return if (x in 0f..1f) 1f - x else 0f
    }

    /** Brief neon flare on a newly sung word. */
    fun flare(p: Long, word: LyricWord): Float {
        val x = (p - word.startMs) / FLARE_MS
        return if (x in 0f..1f) (1f - x) * (1f - x) else 0f
    }
}
