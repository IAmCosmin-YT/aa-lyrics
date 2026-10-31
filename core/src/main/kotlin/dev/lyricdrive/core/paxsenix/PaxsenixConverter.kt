package dev.lyricdrive.core.paxsenix

import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.model.Voice
import dev.lyricdrive.core.ttml.TtmlConverter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts a Paxsenix Apple Music lyrics response into enhanced LRC. Preference order:
 * TTML (`ttmlContent`), enhanced LRC (`elrc`), the syllable `content` array, then plain text.
 *
 * `content` items: `{ timestamp, endtime, text: [{ text, timestamp, endtime, part }] }` in ms, where
 * `part = true` means the syllable continues the same word (no space after it).
 */
object PaxsenixConverter {
    data class Result(val lrc: String?, val plain: String?)

    private fun appendSyllables(out: StringBuilder, syllables: JSONArray?, stripParens: Boolean) {
        if (syllables == null) return
        var lastEnd = -1L
        for (k in 0 until syllables.length()) {
            val syl = syllables.optJSONObject(k) ?: continue
            var text = syl.optString("text").trim()
            if (stripParens) text = text.replace("(", "").replace(")", "")
            if (text.isEmpty()) continue
            val t = syl.optLong("timestamp", -1).takeIf { it >= 0 } ?: continue
            out.append('<').append(LrcParser.formatTimestamp(t)).append('>').append(text)
            if (!syl.optBoolean("part", false) && k < syllables.length() - 1) out.append(' ')
            lastEnd = syl.optLong("endtime", t)
        }
        if (lastEnd >= 0) out.append('<').append(LrcParser.formatTimestamp(lastEnd)).append('>')
    }

    fun convert(json: String): Result? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null

        root.optString("ttmlContent").takeIf { it.isNotBlank() }?.let { ttml ->
            TtmlConverter.toEnhancedLrc(ttml)?.let { return Result(it, null) }
        }
        // Multi-person ELRC carries v1:/v2: voices and [bg: …] backing lines.
        root.optString("elrcMultiPerson").takeIf { it.isNotBlank() && LrcParser.looksLikeLrc(it) }?.let { return Result(it, null) }
        root.optString("elrc").takeIf { it.isNotBlank() && LrcParser.looksLikeLrc(it) }?.let { return Result(it, null) }
        root.optJSONArray("content")?.let(::fromContent)?.let { return Result(it, null) }
        root.optString("plain").takeIf { it.isNotBlank() }?.let { return Result(null, it) }
        return null
    }

    private fun fromContent(lines: JSONArray): String? {
        val out = StringBuilder()
        // oppositeTurn marks the second singer of a duet; only use voices when it occurs.
        val duet = (0 until lines.length()).any { lines.optJSONObject(it)?.optBoolean("oppositeTurn", false) == true }
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: continue
            val start = line.optLong("timestamp", -1)
            if (start < 0) continue
            out.append('[').append(LrcParser.formatTimestamp(start)).append(']')
            if (duet) out.append(LrcParser.voicePrefix(if (line.optBoolean("oppositeTurn", false)) Voice.SINGER_2 else Voice.SINGER_1))
            appendSyllables(out, line.optJSONArray("text"), stripParens = false)
            out.append('\n')
            val bg = line.optJSONArray("backgroundText")
            if (bg != null && bg.length() > 0) {
                out.append("[bg: ")
                appendSyllables(out, bg, stripParens = true)
                out.append("]\n")
            }
        }
        return out.toString().takeIf { it.isNotBlank() }
    }
}
