package dev.lyricdrive.core.kpoe

import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.model.Voice
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
            val singer = line.optJSONObject("element")?.optString("singer")?.takeIf { it.isNotBlank() }
            out.append('[').append(LrcParser.formatTimestamp(start)).append(']').append(LrcParser.voicePrefix(Voice.fromAgent(singer)))
            val syllables = line.optJSONArray("syllabus")
            val main = StringBuilder()
            val bg = StringBuilder()
            var mainEnd = -1L
            var bgEnd = -1L
            if (syllables != null) {
                for (k in 0 until syllables.length()) {
                    val syl = syllables.getJSONObject(k)
                    val text = syl.optString("text")
                    if (text.isEmpty()) continue
                    val t = syl.optLong("time", -1)
                    if (t < 0) continue
                    val end = t + syl.optLong("duration", 0)
                    if (syl.optBoolean("isBackground", false)) {
                        bg.append('<').append(LrcParser.formatTimestamp(t)).append('>').append(text.replace(Regex("\\s+"), " ").replace("(", "").replace(")", ""))
                        bgEnd = end
                    } else {
                        main.append('<').append(LrcParser.formatTimestamp(t)).append('>').append(text.replace(Regex("\\s+"), " "))
                        mainEnd = end
                    }
                }
            }
            if (mainEnd >= 0) {
                out.append(main).append('<').append(LrcParser.formatTimestamp(mainEnd)).append('>')
            } else {
                out.append(line.optString("text").trim())
            }
            out.append('\n')
            if (bgEnd >= 0) out.append("[bg: ").append(bg).append('<').append(LrcParser.formatTimestamp(bgEnd)).append(">]\n")
        }
        return Result(out.toString().takeIf { it.isNotBlank() }, null, source)
    }
}
