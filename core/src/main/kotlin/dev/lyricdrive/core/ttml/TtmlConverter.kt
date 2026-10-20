package dev.lyricdrive.core.ttml

import dev.lyricdrive.core.lrc.LrcParser
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
 * - Background-vocal spans (`ttm:role="x-bg"`) are skipped.
 * - Lines without spans become line-synced.
 */
object TtmlConverter {
    private const val TTM_NS = "http://www.w3.org/ns/ttml#metadata"

    /** `<body dur="...">` when present: lets callers reject a different recording. */
    fun durationMs(ttml: String): Long? =
        Regex("""<body[^>]*\sdur="([^"]+)"""").find(ttml)?.groupValues?.get(1)?.let(::parseTime)

    fun toEnhancedLrc(ttml: String): String? {
        val doc = try {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
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

            data class Word(val start: Long, val end: Long?, var text: String)

            val words = mutableListOf<Word>()
            val plain = StringBuilder()
            var pendingSpace = false
            fun visit(node: Node) {
                when (node.nodeType) {
                    Node.TEXT_NODE -> {
                        val text = node.nodeValue
                        if (text.isBlank()) {
                            if (text.isNotEmpty()) pendingSpace = true
                        } else {
                            plain.append(text)
                        }
                    }
                    Node.ELEMENT_NODE -> {
                        val el = node as Element
                        if (el.localName == "span" && el.getAttributeNS(TTM_NS, "role").ifEmpty { el.getAttribute("ttm:role") } == "x-bg") return
                        val begin = if (el.localName == "span") parseTime(el.getAttribute("begin")) else null
                        if (begin != null) {
                            if (pendingSpace && words.isNotEmpty()) words.last().text += " "
                            pendingSpace = false
                            val text = el.textContent
                            if (text.isNotEmpty() && text.first().isWhitespace() && words.isNotEmpty()) words.last().text += " "
                            words += Word(begin, parseTime(el.getAttribute("end")), text.trim() + if (text.last().isWhitespace()) " " else "")
                        } else {
                            val children = el.childNodes
                            for (c in 0 until children.length) visit(children.item(c))
                        }
                    }
                }
            }
            val children = p.childNodes
            for (c in 0 until children.length) visit(children.item(c))

            out.append('[').append(LrcParser.formatTimestamp(lineStart)).append(']')
            if (words.isEmpty()) {
                out.append(plain.toString().trim())
            } else {
                words.forEach { w -> out.append('<').append(LrcParser.formatTimestamp(w.start)).append('>').append(w.text.replace(Regex("\\s+"), " ")) }
                val closing = words.last().end ?: lineEnd
                if (closing != null) out.append('<').append(LrcParser.formatTimestamp(closing)).append('>')
            }
            out.append('\n')
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
