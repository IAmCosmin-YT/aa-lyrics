package dev.lyricdrive.core.kpoe

import dev.lyricdrive.core.lrc.LrcParser
import org.json.JSONObject

/**
 * Converts the LyricsPlus ("KPoe") v2 JSON format into enhanced LRC:
 * `{ type: "Word"|"Line"|"None", lyrics: [{ time, duration, text, syllabus: [{ time, duration, text, isBackground }] }] }`
 * with times in milliseconds. Background-vocal syllables are skipped.
 */
object KpoeConverter {
    data class Result(val lrc: String?, val plain: String?, val source: String?)

    fun convert(json: String): Result? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val lines = root.optJSONArray("lyrics") ?: return null
        if (lines.length() == 0) return null
        val type = root.optString("type").lowercase()
        val source = root.optJSONObject("metadata")?.optString("source")?.takeIf { it.isNotBlank() }

        if (type == "none") {
            val plain = (0 until lines.length()).joinToString("\n") { lines.getJSONObject(it).optString("text") }
            return Result(null, plain, source)
        }

        val out = StringBuilder()
        for (i in 0 until lines.length()) {
            val line = lines.getJSONObject(i)
            val start = line.optLong("time", -1)
            if (start < 0) continue
            out.append('[').append(LrcParser.formatTimestamp(start)).append(']')
            val syllables = line.optJSONArray("syllabus")
            var lastEnd = -1L
            var wrote = false
            if (syllables != null) {
                for (k in 0 until syllables.length()) {
                    val syl = syllables.getJSONObject(k)
                    if (syl.optBoolean("isBackground", false)) continue
                    val text = syl.optString("text")
                    if (text.isEmpty()) continue
                    val t = syl.optLong("time", -1)
                    if (t < 0) continue
                    out.append('<').append(LrcParser.formatTimestamp(t)).append('>').append(text.replace(Regex("\\s+"), " "))
                    lastEnd = t + syl.optLong("duration", 0)
                    wrote = true
                }
            }
            if (wrote) {
                out.append('<').append(LrcParser.formatTimestamp(lastEnd)).append('>')
            } else {
                out.append(line.optString("text").trim())
            }
            out.append('\n')
        }
        return Result(out.toString().takeIf { it.isNotBlank() }, null, source)
    }
}
