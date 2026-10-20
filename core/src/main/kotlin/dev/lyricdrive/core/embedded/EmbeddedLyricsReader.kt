package dev.lyricdrive.core.embedded

import dev.lyricdrive.core.lrc.LrcParser
import dev.lyricdrive.core.model.RawLyrics
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Reads lyrics embedded in audio files:
 * - ID3v2.3 / v2.4 `SYLT` (synchronised) and `USLT` (unsynchronised) frames — MP3.
 * - FLAC Vorbis comments `SYNCEDLYRICS`, `LYRICS`, `UNSYNCEDLYRICS`.
 *
 * Only the tag region at the start of the file is read, never the audio payload.
 */
object EmbeddedLyricsReader {
    const val SOURCE = "embedded"
    private const val MAX_TAG_BYTES = 16 * 1024 * 1024

    fun read(input: InputStream): RawLyrics? {
        val data = DataInputStream(input.buffered())
        val magic = ByteArray(4)
        data.readFully(magic, 0, 3)
        return when {
            magic[0] == 'I'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte() ->
                readId3(data)
            magic[0] == 'f'.code.toByte() && magic[1] == 'L'.code.toByte() && magic[2] == 'a'.code.toByte() -> {
                magic[3] = data.readByte()
                if (magic[3] == 'C'.code.toByte()) readFlac(data) else null
            }
            else -> null
        }
    }

    // ---------------------------------------------------------------- ID3v2

    private fun readId3(data: DataInputStream): RawLyrics? {
        val major = data.readUnsignedByte()
        data.readUnsignedByte() // revision
        val flags = data.readUnsignedByte()
        val size = syncsafe(data.readInt())
        if (major !in 3..4 || size <= 0 || size > MAX_TAG_BYTES) return null

        var tag = ByteArray(size).also { data.readFully(it) }
        if (major == 3 && flags and 0x80 != 0) tag = removeUnsynchronisation(tag)

        var pos = 0
        if (flags and 0x40 != 0) {
            val extSize = int32(tag, 0)
            pos = if (major == 4) syncsafe(extSize) else extSize + 4
        }

        var synced: String? = null
        var plain: String? = null
        while (pos + 10 <= tag.size) {
            if (tag[pos] == 0.toByte()) break // padding
            val id = String(tag, pos, 4, Charsets.ISO_8859_1)
            val rawSize = int32(tag, pos + 4)
            val frameSize = if (major == 4) syncsafe(rawSize) else rawSize
            val formatFlags = tag[pos + 9].toInt() and 0xFF
            val bodyStart = pos + 10
            if (frameSize <= 0 || bodyStart + frameSize > tag.size) break
            var body = tag.copyOfRange(bodyStart, bodyStart + frameSize)
            pos = bodyStart + frameSize

            val compressedOrEncrypted = if (major == 4) formatFlags and 0x0C != 0 else formatFlags and 0xC0 != 0
            if (compressedOrEncrypted) continue
            if (major == 4) {
                if (formatFlags and 0x01 != 0) body = body.copyOfRange(4.coerceAtMost(body.size), body.size)
                if (formatFlags and 0x02 != 0) body = removeUnsynchronisation(body)
            }

            when (id) {
                "SYLT" -> if (synced == null) synced = parseSylt(body)
                "USLT" -> if (plain == null) plain = parseUslt(body)
            }
        }
        if (synced == null && plain == null) return null
        return RawLyrics(synced = synced, plain = plain, instrumental = false, source = SOURCE)
    }

    /** SYLT → LRC text. Syllable-mode frames (entries starting with a newline) become enhanced LRC. */
    internal fun parseSylt(body: ByteArray): String? {
        if (body.size < 6) return null
        val encoding = body[0].toInt()
        val timestampFormat = body[4].toInt()
        if (timestampFormat != 2) return null // 1 = MPEG frames: needs the frame rate, unsupported
        var pos = 6
        pos = skipTerminated(body, pos, encoding) // content descriptor

        data class Entry(val text: String, val timeMs: Long)

        val entries = mutableListOf<Entry>()
        while (pos < body.size) {
            val end = findTerminator(body, pos, encoding)
            val text = decode(body, pos, end, encoding)
            pos = end + terminatorLength(encoding)
            if (pos + 4 > body.size) break
            entries += Entry(text, int32(body, pos).toLong() and 0xFFFFFFFFL)
            pos += 4
        }
        if (entries.isEmpty()) return null

        val syllableMode = entries.drop(1).any { it.text.startsWith('\n') || it.text.startsWith('\r') }
        val out = StringBuilder()
        if (!syllableMode) {
            entries.forEach { out.append('[').append(LrcParser.formatTimestamp(it.timeMs)).append(']').append(it.text.trim()).append('\n') }
        } else {
            var lineOpen = false
            entries.forEach { e ->
                val newLine = e.text.startsWith('\n') || e.text.startsWith('\r') || !lineOpen
                val text = e.text.trimStart('\r', '\n')
                if (newLine) {
                    if (lineOpen) out.append('\n')
                    out.append('[').append(LrcParser.formatTimestamp(e.timeMs)).append(']')
                    lineOpen = true
                }
                out.append('<').append(LrcParser.formatTimestamp(e.timeMs)).append('>').append(text)
            }
            out.append('\n')
        }
        return out.toString()
    }

    internal fun parseUslt(body: ByteArray): String? {
        if (body.size < 5) return null
        val encoding = body[0].toInt()
        val start = skipTerminated(body, 4, encoding)
        if (start >= body.size) return null
        var end = body.size
        // Trim trailing terminators
        while (end > start && body[end - 1] == 0.toByte()) end--
        if (encoding == 1 || encoding == 2) if ((end - start) % 2 != 0) end++
        return decode(body, start, end.coerceAtMost(body.size), encoding).takeIf { it.isNotBlank() }
    }

    private fun charset(encoding: Int): Charset = when (encoding) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }

    private fun terminatorLength(encoding: Int) = if (encoding == 1 || encoding == 2) 2 else 1

    private fun findTerminator(b: ByteArray, from: Int, encoding: Int): Int {
        if (terminatorLength(encoding) == 1) {
            var i = from
            while (i < b.size && b[i] != 0.toByte()) i++
            return i
        }
        var i = from
        while (i + 1 < b.size && !(b[i] == 0.toByte() && b[i + 1] == 0.toByte())) i += 2
        return if (i + 1 < b.size) i else b.size
    }

    private fun skipTerminated(b: ByteArray, from: Int, encoding: Int): Int =
        findTerminator(b, from, encoding) + terminatorLength(encoding)

    private fun decode(b: ByteArray, from: Int, to: Int, encoding: Int): String =
        if (to <= from) "" else String(b, from, to - from, charset(encoding)).removePrefix("﻿")

    private fun removeUnsynchronisation(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun syncsafe(v: Int): Int =
        (v and 0x7F) or ((v shr 8 and 0x7F) shl 7) or ((v shr 16 and 0x7F) shl 14) or ((v shr 24 and 0x7F) shl 21)

    private fun int32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
            (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

    // ---------------------------------------------------------------- FLAC

    private fun readFlac(data: DataInputStream): RawLyrics? {
        while (true) {
            val header = data.readUnsignedByte()
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val length = (data.readUnsignedByte() shl 16) or (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
            if (type == 4) {
                if (length > MAX_TAG_BYTES) return null
                val block = ByteArray(length).also { data.readFully(it) }
                return parseVorbisComments(block)
            }
            var remaining = length.toLong()
            while (remaining > 0) {
                val skipped = data.skip(remaining)
                if (skipped <= 0) return null
                remaining -= skipped
            }
            if (last) return null
        }
    }

    private fun parseVorbisComments(block: ByteArray): RawLyrics? {
        fun le32(at: Int) = (block[at].toInt() and 0xFF) or (block[at + 1].toInt() and 0xFF shl 8) or
            (block[at + 2].toInt() and 0xFF shl 16) or (block[at + 3].toInt() and 0xFF shl 24)

        var pos = 0
        val vendorLength = le32(pos); pos += 4 + vendorLength
        if (pos + 4 > block.size) return null
        val count = le32(pos); pos += 4
        val fields = mutableMapOf<String, String>()
        repeat(count) {
            if (pos + 4 > block.size) return@repeat
            val len = le32(pos); pos += 4
            if (len < 0 || pos + len > block.size) return@repeat
            val comment = String(block, pos, len, Charsets.UTF_8)
            pos += len
            val eq = comment.indexOf('=')
            if (eq > 0) fields.putIfAbsent(comment.substring(0, eq).uppercase(), comment.substring(eq + 1))
        }

        val lyricsField = fields["LYRICS"]
        val synced = fields["SYNCEDLYRICS"] ?: lyricsField?.takeIf(LrcParser::looksLikeLrc)
        val plain = fields["UNSYNCEDLYRICS"] ?: lyricsField?.takeUnless(LrcParser::looksLikeLrc)
        if (synced == null && plain == null) return null
        return RawLyrics(synced = synced, plain = plain, instrumental = false, source = SOURCE)
    }
}
