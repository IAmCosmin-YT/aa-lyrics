package dev.lyricdrive.core.lrc

import dev.lyricdrive.core.model.LyricLine
import dev.lyricdrive.core.model.LyricWord

/**
 * Parses LRC text, both plain line-synced (`[mm:ss.xx] text`) and enhanced/word-synced
 * (`[mm:ss.xx] <mm:ss.xx> word <mm:ss.xx> word`). Supports repeated line tags
 * (`[00:10.00][00:42.00]chorus`), the `[offset:±ms]` tag, and 1–3 digit fractions.
 */
object LrcParser {
    /** How long the final line stays active when the track duration is unknown. */
    const val LAST_LINE_FALLBACK_MS = 6_000L

    /** Fill duration for the last word of a line when no closing word tag exists. */
    const val LAST_WORD_FALLBACK_MS = 1_000L

    private val LINE_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val META_TAG = Regex("""^\[([A-Za-z#]+):(.*)]$""")

    private data class RawLine(val startMs: Long, val body: String, val order: Int)

    fun parse(lrc: String, trackDurationMs: Long? = null): List<LyricLine> {
        var offsetMs = 0L
        val raw = mutableListOf<RawLine>()

        lrc.lineSequence().forEach { physical ->
            val line = physical.trim()
            if (line.isEmpty()) return@forEach

            META_TAG.matchEntire(line)?.let { meta ->
                if (meta.groupValues[1].equals("offset", ignoreCase = true)) {
                    offsetMs = meta.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: 0L
                }
                return@forEach
            }

            var pos = 0
            val stamps = mutableListOf<Long>()
            while (true) {
                val tag = LINE_TAG.find(line, pos)?.takeIf { it.range.first == pos } ?: break
                stamps += tag.toMillis()
                pos = tag.range.last + 1
                while (pos < line.length && line[pos] == ' ') pos++
            }
            if (stamps.isEmpty()) return@forEach
            val body = line.substring(pos)
            stamps.forEach { raw += RawLine(it, body, raw.size) }
        }

        // Positive offset makes lyrics appear sooner (LRC convention).
        val sorted = raw
            .map { it.copy(startMs = (it.startMs - offsetMs).coerceAtLeast(0)) }
            .sortedWith(compareBy({ it.startMs }, { it.order }))

        return sorted.mapIndexed { index, rawLine ->
            val nextStart = sorted.getOrNull(index + 1)?.startMs
            val lineEnd = (nextStart ?: trackDurationMs?.takeIf { it > rawLine.startMs }
                ?: (rawLine.startMs + LAST_LINE_FALLBACK_MS)).coerceAtLeast(rawLine.startMs)
            buildLine(rawLine, lineEnd, offsetMs)
        }
    }

    private fun buildLine(raw: RawLine, lineEnd: Long, offsetMs: Long): LyricLine {
        val tags = WORD_TAG.findAll(raw.body).toList()
        if (tags.isEmpty()) {
            return LyricLine(raw.startMs, lineEnd, raw.body.trim())
        }

        data class Piece(val startMs: Long, val text: String)

        val pieces = mutableListOf<Piece>()
        val leading = raw.body.substring(0, tags.first().range.first)
        if (leading.isNotBlank()) pieces += Piece(raw.startMs, leading)
        tags.forEachIndexed { i, tag ->
            val textEnd = tags.getOrNull(i + 1)?.range?.first ?: raw.body.length
            val start = (tag.toMillis() - offsetMs).coerceAtLeast(0)
            pieces += Piece(start, raw.body.substring(tag.range.last + 1, textEnd))
        }

        // A trailing tag with no text closes the previous word; whitespace-only pieces are dropped.
        val closingTime = pieces.lastOrNull()?.takeIf { it.text.isBlank() }?.startMs
        val texts = pieces.filter { it.text.isNotBlank() }
        if (texts.isEmpty()) return LyricLine(raw.startMs, lineEnd, "")

        // Tags are often padded on both sides ("<t> word <t> word"), so normalise to exactly one
        // trailing space between words while keeping syllables of the same word glued together.
        val words = texts.mapIndexed { i, piece ->
            val next = texts.getOrNull(i + 1)
            val spaced = next != null && (piece.text.last().isWhitespace() || next.text.first().isWhitespace())
            val end = next?.startMs
                ?: closingTime
                ?: minOf(lineEnd, piece.startMs + LAST_WORD_FALLBACK_MS)
            LyricWord(piece.startMs, end.coerceAtLeast(piece.startMs), piece.text.trim() + if (spaced) " " else "")
        }
        return LyricLine(raw.startMs, lineEnd, words.joinToString("") { it.text }, words)
    }

    private fun MatchResult.toMillis(): Long {
        val minutes = groupValues[1].toLong()
        val seconds = groupValues[2].toLong()
        val fraction = groupValues[3]
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.toLong()
        }
        return minutes * 60_000 + seconds * 1_000 + fractionMs
    }

    /** True when [text] contains at least one timed LRC line. */
    fun looksLikeLrc(text: String): Boolean =
        text.lineSequence().any { LINE_TAG.find(it.trim())?.range?.first == 0 }

    fun formatTimestamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0)
        val minutes = clamped / 60_000
        val seconds = (clamped / 1_000) % 60
        val centis = (clamped % 1_000) / 10
        return "%02d:%02d.%02d".format(minutes, seconds, centis)
    }
}

object PlainLyricsParser {
    fun parse(text: String): List<String> =
        text.lines().map { it.trim() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
}
