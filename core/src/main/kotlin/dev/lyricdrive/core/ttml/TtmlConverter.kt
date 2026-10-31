package dev.lyricdrive.core.ttml

import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.model.Voice
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * Converts word-timed TTML (the format used by Apple-Music-style lyric providers such as
 * BetterLyrics / LyricsPlus) into enhanced LRC, so it flows through the same parser, cache and
 * renderers as every other source.
 *
 * - `<p begin end>` = one line; `<span begin end>` inside it = one word or syllable.
 * - Whitespace between spans separates words; adjacent spans with no whitespace are syllables.
 * - `ttm:agent` ("v1", "v2", "v1000") becomes a `v1:`/`v2:`/`v1000:` voice prefix.
 * - Background-vocal spans (`ttm:role="x-bg"`) become a `[bg: …]` line under their line.
 * - Lines without spans become line-synced.
 */
object TtmlConverter {
    private class Word(val start: Long, val end: Long?, var text: String)

    private fun appendWords(out: StringBuilder, words: List<Word>, lineEnd: Long?, stripParens: Boolean = false) {
        words.forEach { w ->
            var text = w.text.replace(Regex("\\s+"), " ")
            // Backing spans usually carry their own "(…)"; the sub-line styling replaces them.
            if (stripParens) text = text.replace("(", "").replace(")", "")
            out.append('<').append(LrcParser.formatTimestamp(w.start)).append('>').append(text)
        }
        val closing = words.last().end ?: lineEnd
        if (closing != null) out.append('<').append(LrcParser.formatTimestamp(closing)).append('>')
    }

    private const val TTM_NS = "http://www.w3.org/ns/ttml#metadata"

    /** `<body dur="...">` when present: lets callers reject a different recording. */
    fun durationMs(ttml: String): Long? =
        Regex("""<body[^>]*\sdur="([^"]+)"""").find(ttml)?.groupValues?.get(1)?.let(::parseTime)

    fun toEnhancedLrc(ttml: String): String? {
        val doc = try {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // Android's parser rejects unknown features (and never loads DTDs anyway).
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }.newDocumentBuilder().parse(InputSource(StringReader(ttml)))
        } catch (e: Exception) {
            return null
        }

        val out = StringBuilder()
        val paragraphs = doc.getElementsByTagNameNS("*", "p")
        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as Element
            val lineStart = parseTime(p.getAttribute("begin")) ?: continue
            val lineEnd = parseTime(p.getAttribute("end"))
            val agent = p.getAttributeNS(TTM_NS, "agent").ifEmpty { p.getAttribute("ttm:agent") }.ifEmpty { p.getAttribute("agent") }

            class Track {
                val words = mutableListOf<Word>()
                val plain = StringBuilder()
                var pendingSpace = false
            }
            val main = Track()
            val bg = Track()

            fun visit(node: Node, target: Track) {
                when (node.nodeType) {
                    Node.TEXT_NODE -> {
                        val text = node.nodeValue
                        if (text.isBlank()) {
                            if (text.isNotEmpty()) target.pendingSpace = true
                        } else {
                            target.plain.append(text)
                        }
                    }
                    Node.ELEMENT_NODE -> {
                        val el = node as Element
                        val isBg = el.localName == "span" && el.getAttributeNS(TTM_NS, "role").ifEmpty { el.getAttribute("ttm:role") } == "x-bg"
                        val into = if (isBg) bg else target
                        val begin = if (el.localName == "span") parseTime(el.getAttribute("begin")) else null
                        val hasChildSpans = (0 until el.childNodes.length).any { (el.childNodes.item(it) as? Element)?.localName == "span" }
                        if (begin != null && !hasChildSpans) {
                            if (into.pendingSpace && into.words.isNotEmpty()) into.words.last().text += " "
                            into.pendingSpace = false
                            val text = el.textContent
                            if (text.isBlank()) return
                            if (text.first().isWhitespace() && into.words.isNotEmpty()) into.words.last().text += " "
                            into.words += Word(begin, parseTime(el.getAttribute("end")), text.trim() + if (text.last().isWhitespace()) " " else "")
                        } else {
                            val children = el.childNodes
                            for (c in 0 until children.length) visit(children.item(c), into)
                        }
                    }
                }
            }
            val children = p.childNodes
            for (c in 0 until children.length) visit(children.item(c), main)

            out.append('[').append(LrcParser.formatTimestamp(lineStart)).append(']')
            out.append(LrcParser.voicePrefix(Voice.fromAgent(agent.ifEmpty { null })))
            if (main.words.isEmpty()) out.append(main.plain.toString().trim()) else appendWords(out, main.words, lineEnd)
            out.append('\n')
            if (bg.words.isNotEmpty()) {
                out.append("[bg: ")
                appendWords(out, bg.words, null, stripParens = true)
                out.append("]\n")
            }
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    /** "12.5", "12.5s", "1250ms", "01:02.345", "1:02:03.4" → milliseconds. */
    fun parseTime(value: String?): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            when {
                v.endsWith("ms") -> v.removeSuffix("ms").toDouble().toLong()
                v.endsWith("s") && !v.contains(':') -> (v.removeSuffix("s").toDouble() * 1000).toLong()
                v.endsWith("m") && !v.contains(':') -> (v.removeSuffix("m").toDouble() * 60_000).toLong()
                v.endsWith("h") && !v.contains(':') -> (v.removeSuffix("h").toDouble() * 3_600_000).toLong()
                v.contains(':') -> {
                    val parts = v.split(':')
                    var seconds = 0.0
                    parts.forEach { seconds = seconds * 60 + it.toDouble() }
                    (seconds * 1000).toLong()
                }
                else -> (v.toDouble() * 1000).toLong()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
