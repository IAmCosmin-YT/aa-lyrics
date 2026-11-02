package dev.lyricdrive.core.lrc

import dev.lyricdrive.core.model.BackingVocals
import dev.lyricdrive.core.model.LyricLine
import dev.lyricdrive.core.model.LyricWord
import dev.lyricdrive.core.model.Voice

/**
 * Parses LRC text, both plain line-synced (`[mm:ss.xx] text`) and enhanced/word-synced
 * (`[mm:ss.xx] <mm:ss.xx> word <mm:ss.xx> word`). Supports repeated line tags
 * (`[00:10.00][00:42.00]chorus`), the `[offset:±ms]` tag, and 1–3 digit fractions.
 *
 * Multi-vocal extensions (the enhanced-LRC dialect Apple-Music-derived sources use):
 * - `[00:12.00]v1: …` / `v2:` / `v1000:` (or `{agent:v2}`) set the line's [Voice];
 * - a following `[bg: <00:13.00>ooh<00:14.00>]` line holds the backing vocals of the line above;
 * - parenthesised parts of a line become its backing vocals, and a line that is entirely
 *   parenthesised becomes a [LyricLine.secondary] line.
 */
object LrcParser {
    /** How long the final line stays active when the track duration is unknown. */
    const val LAST_LINE_FALLBACK_MS = 6_000L

    /** Fill duration for the last word of a line when no closing word tag exists. */
    const val LAST_WORD_FALLBACK_MS = 1_000L

    private val LINE_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val META_TAG = Regex("""^\[([A-Za-z#]+):(.*)\]$""")
    // Android uses ICU, which rejects the bare closing braces tolerated by the desktop JDK.
    private val VOICE_PREFIX = Regex("""^(?:\{agent:([^}]+)\}|v(\d+)\s*:)\s*""", RegexOption.IGNORE_CASE)
    private val BG_PREFIX = Regex("""^\{bg\}\s*""", RegexOption.IGNORE_CASE)
    private val PAREN_GROUP = Regex("""\(([^()]*)\)""")
    private val SPACES = Regex("""\s+""")

    private data class RawLine(
        val startMs: Long,
        val body: String,
        val order: Int,
        val voice: Voice,
        var backingBody: String? = null,
    )

    fun parse(lrc: String, trackDurationMs: Long? = null): List<LyricLine> {
        var offsetMs = 0L
        val raw = mutableListOf<RawLine>()

        lrc.lineSequence().forEach { physical ->
            val line = physical.trim()
            if (line.isEmpty()) return@forEach

            META_TAG.matchEntire(line)?.let { meta ->
                when (meta.groupValues[1].lowercase()) {
                    "offset" -> offsetMs = meta.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: 0L
                    // Backing vocals of the line above.
                    "bg" -> raw.lastOrNull()?.let { it.backingBody = listOfNotNull(it.backingBody, meta.groupValues[2].trim()).joinToString(" ") }
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
            var body = line.substring(pos)
            BG_PREFIX.find(body)?.let { m ->
                raw.lastOrNull()?.let { it.backingBody = listOfNotNull(it.backingBody, body.substring(m.range.last + 1)).joinToString(" ") }
                return@forEach
            }
            var voice = Voice.MAIN
            VOICE_PREFIX.find(body)?.let { m ->
                voice = Voice.fromAgent(m.groupValues[1].ifEmpty { m.groupValues[2] })
                body = body.substring(m.range.last + 1)
            }
            stamps.forEach { raw += RawLine(it, body, raw.size, voice) }
        }

        // Positive offset makes lyrics appear sooner (LRC convention).
        val sorted = raw
            .map { it.copy(startMs = (it.startMs - offsetMs).coerceAtLeast(0)) }
            .sortedWith(compareBy({ it.startMs }, { it.order }))

        return sorted.mapIndexed { index, rawLine ->
            // The next line that starts *later*: simultaneous lines (duets) must not end each other.
            val nextStart = sorted.asSequence().drop(index + 1).firstOrNull { it.startMs > rawLine.startMs }?.startMs
            val lineEnd = (nextStart ?: trackDurationMs?.takeIf { it > rawLine.startMs }
                ?: (rawLine.startMs + LAST_LINE_FALLBACK_MS)).coerceAtLeast(rawLine.startMs)
            val (text, words) = parseWords(rawLine.body, rawLine.startMs, lineEnd, offsetMs)
            val backing = rawLine.backingBody?.let { body ->
                val (parsedText, parsedWords) = parseWords(body, rawLine.startMs, lineEnd, offsetMs)
                // Backing vocals often come wrapped in "(…)"; the sub-line styling replaces them.
                val bWords = rebuild(parsedWords.map { it to true }) { true }
                val bText = if (bWords.isNotEmpty()) bWords.joinToString("") { it.text } else parsedText.replace("(", "").replace(")", "").trim()
                bText.takeIf { it.isNotBlank() }?.let {
                    BackingVocals(
                        text = it,
                        words = bWords,
                        startMs = bWords.firstOrNull()?.startMs ?: rawLine.startMs,
                        endMs = bWords.lastOrNull()?.endMs ?: lineEnd,
                    )
                }
            }
            splitParentheses(LyricLine(rawLine.startMs, lineEnd, text, words, rawLine.voice, backing))
        }
    }

    /** Text and (when `<mm:ss>` tags are present) word timings of a line body. */
    private fun parseWords(body: String, lineStart: Long, lineEnd: Long, offsetMs: Long): Pair<String, List<LyricWord>> {
        val tags = WORD_TAG.findAll(body).toList()
        if (tags.isEmpty()) return body.trim() to emptyList()

        data class Piece(val startMs: Long, val text: String)

        val pieces = mutableListOf<Piece>()
        val leading = body.substring(0, tags.first().range.first)
        if (leading.isNotBlank()) pieces += Piece(lineStart, leading)
        tags.forEachIndexed { i, tag ->
            val textEnd = tags.getOrNull(i + 1)?.range?.first ?: body.length
            val start = (tag.toMillis() - offsetMs).coerceAtLeast(0)
            pieces += Piece(start, body.substring(tag.range.last + 1, textEnd))
        }

        // A trailing tag with no text closes the previous word; whitespace-only pieces are dropped.
        val closingTime = pieces.lastOrNull()?.takeIf { it.text.isBlank() }?.startMs
        val texts = pieces.filter { it.text.isNotBlank() }
        if (texts.isEmpty()) return "" to emptyList()

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
        return words.joinToString("") { it.text } to words
    }

    /**
     * Parenthesised parts are backing vocals: "Stay (stay with me) tonight" → main "Stay tonight",
     * backing "stay with me". A line that is entirely in parentheses becomes a secondary line.
     */
    internal fun splitParentheses(line: LyricLine): LyricLine {
        if (line.backing != null || '(' !in line.text) return line
        val trimmed = line.text.trim()
        val groups = PAREN_GROUP.findAll(line.text).toList()
        if (groups.isEmpty()) return line

        // Whole line in parentheses.
        if (groups.size == 1 && groups[0].value.trim() == trimmed) {
            val inner = groups[0].groupValues[1].trim()
            if (inner.isEmpty()) return line
            val words = rebuild(line.words.map { it to true }) { true }
            // Word texts must concatenate to the line text (renderers index letters by offset).
            val text = if (words.isEmpty()) inner else words.joinToString("") { it.text }
            return line.copy(text = text, words = words, secondary = true)
        }

        if (!line.hasWordTiming) {
            val main = PAREN_GROUP.replace(line.text, " ").replace(SPACES, " ").trim()
            val backing = groups.joinToString(" ") { it.groupValues[1].trim() }.trim()
            if (main.isEmpty() || backing.isEmpty()) return line
            return line.copy(text = main, backing = BackingVocals(backing, emptyList(), line.startMs, line.endMs))
        }

        // Word-timed: assign each word to main or backing by where its text sits.
        var offset = 0
        val tagged = line.words.map { w ->
            val lead = w.text.length - w.text.trimStart().length
            val at = offset + lead
            offset += w.text.length
            w to groups.any { at >= it.range.first && at <= it.range.last }
        }
        val mainWords = rebuild(tagged) { !it }
        val backWords = rebuild(tagged) { it }
        if (mainWords.isEmpty() || backWords.isEmpty()) return line
        return line.copy(
            text = mainWords.joinToString("") { it.text },
            words = mainWords,
            backing = BackingVocals(backWords.joinToString("") { it.text }, backWords, backWords.first().startMs, backWords.last().endMs),
        )
    }

    /** Words matching [keep], parentheses removed, re-spaced so the texts concatenate cleanly. */
    private fun rebuild(tagged: List<Pair<LyricWord, Boolean>>, keep: (Boolean) -> Boolean): List<LyricWord> {
        val picked = tagged.withIndex().filter { keep(it.value.second) }
        val out = ArrayList<LyricWord>(picked.size)
        picked.forEachIndexed { i, (index, pair) ->
            val word = pair.first
            var text = word.text.replace("(", "").replace(")", "")
            if (text.isBlank()) return@forEachIndexed
            val next = picked.getOrNull(i + 1)
            // Keep glued syllables glued; separate words that were apart in the original.
            val gap = next != null && (next.index != index + 1 || word.text.last().isWhitespace() || word.text.endsWith(")"))
            text = text.trim() + if (gap && next != null) " " else ""
            out += word.copy(text = text)
        }
        if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(text = out.last().text.trimEnd())
        return out
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

    /** `v1: ` / `v2: ` / `v1000: ` prefix for a line, or empty for the default voice. */
    fun voicePrefix(voice: Voice): String = when (voice) {
        Voice.MAIN -> ""
        Voice.SINGER_1 -> "v1: "
        Voice.SINGER_2 -> "v2: "
        Voice.TOGETHER -> "v1000: "
    }
}

object PlainLyricsParser {
    fun parse(text: String): List<String> =
        text.lines().map { it.trim() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
}
