package dev.lyricdrive.core.paxsenix

import dev.lyricdrive.core.lrc.LrcParser
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

    fun convert(json: String): Result? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null

        root.optString("ttmlContent").takeIf { it.isNotBlank() }?.let { ttml ->
            TtmlConverter.toEnhancedLrc(ttml)?.let { return Result(it, null) }
        }
        root.optString("elrc").takeIf { it.isNotBlank() && LrcParser.looksLikeLrc(it) }?.let { return Result(it, null) }
        root.optJSONArray("content")?.let(::fromContent)?.let { return Result(it, null) }
        root.optString("plain").takeIf { it.isNotBlank() }?.let { return Result(null, it) }
        return null
    }

    private fun fromContent(lines: JSONArray): String? {
        val out = StringBuilder()
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: continue
            val start = line.optLong("timestamp", -1)
            if (start < 0) continue
            out.append('[').append(LrcParser.formatTimestamp(start)).append(']')
            val syllables = line.optJSONArray("text")
            var lastEnd = -1L
            if (syllables != null) {
                for (k in 0 until syllables.length()) {
                    val syl = syllables.optJSONObject(k) ?: continue
                    val text = syl.optString("text").trim()
                    if (text.isEmpty()) continue
                    val t = syl.optLong("timestamp", -1).takeIf { it >= 0 } ?: continue
                    val continuesWord = syl.optBoolean("part", false)
                    out.append('<').append(LrcParser.formatTimestamp(t)).append('>').append(text)
                    if (!continuesWord && k < syllables.length() - 1) out.append(' ')
                    lastEnd = syl.optLong("endtime", t)
                }
            }
            if (lastEnd >= 0) out.append('<').append(LrcParser.formatTimestamp(lastEnd)).append('>')
            out.append('\n')
        }
        return out.toString().takeIf { it.isNotBlank() }
    }
}
