package io.github.salmansoltaniyan.lessonsubtitles.phone

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Reads the lesson timeline that is stored inside an MP3: an ID3v2 tag at the start of the file with a
 * `TXXX` frame whose description is `SHADOWING_TIMELINE` and whose value is the timeline JSON.
 *
 * Android's MediaMetadataRetriever does not return TXXX frames, so the tag is parsed here.
 * Supports ID3v2.3 and ID3v2.4.
 */
object Id3Timeline {
    const val DESCRIPTION = "SHADOWING_TIMELINE"

    /** Larger frames are not a timeline; protects against broken files. */
    private const val MAX_VALUE_BYTES = 16 * 1024 * 1024

    /** The timeline JSON in [audioUri], or null if the file has none. */
    fun read(context: Context, audioUri: Uri): String? = try {
        context.contentResolver.openInputStream(audioUri)?.use { find(it, readValue = true) }
    } catch (_: Exception) {
        null
    }

    /** True if [audioUri] has a timeline tag. Reads only the frame headers, so it is fast. */
    fun has(context: Context, audioUri: Uri): Boolean = try {
        context.contentResolver.openInputStream(audioUri)?.use { find(it, readValue = false) } != null
    } catch (_: Exception) {
        false
    }

    /**
     * Walks the ID3v2 frames of [stream]. Returns the TXXX timeline value (or "" when [readValue] is
     * false), or null if there is no such frame.
     */
    internal fun find(stream: InputStream, readValue: Boolean): String? {
        val input = DataInputStream(BufferedInputStream(stream, 8192))

        // Header: "ID3", major version, revision, flags, tag size (syncsafe).
        val header = ByteArray(10)
        try {
            input.readFully(header)
        } catch (_: EOFException) {
            return null
        }
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return null
        }
        val version = header[3].toInt()
        if (version != 3 && version != 4) return null
        val flags = header[5].toInt()
        var remaining = syncsafe(header, 6).toLong()

        // Extended header: skip it.
        if (flags and 0x40 != 0) {
            val sizeBytes = ByteArray(4)
            input.readFully(sizeBytes)
            val extSize = if (version == 4) syncsafe(sizeBytes, 0) - 4 else bigEndian(sizeBytes, 0)
            skipFully(input, extSize.toLong())
            remaining -= 4 + extSize
        }

        val frameHeader = ByteArray(10)
        while (remaining >= 10) {
            input.readFully(frameHeader)
            remaining -= 10
            if (frameHeader[0].toInt() == 0) return null // padding: no more frames

            val id = String(frameHeader, 0, 4, Charsets.ISO_8859_1)
            val size = if (version == 4) syncsafe(frameHeader, 4) else bigEndian(frameHeader, 4)
            if (size < 0 || size > remaining) return null // broken tag
            remaining -= size

            val formatFlags = frameHeader[9].toInt()
            // v2.4: compressed (0x08) or encrypted (0x04); v2.3: compressed (0x80) or encrypted (0x40).
            val unreadable = if (version == 4) formatFlags and 0x0C != 0 else formatFlags and 0xC0 != 0
            if (id != "TXXX" || unreadable || size < 2) {
                skipFully(input, size.toLong())
                continue
            }

            // v2.4 "data length indicator": 4 bytes before the frame body.
            var bodySize = size
            if (version == 4 && formatFlags and 0x01 != 0) {
                skipFully(input, 4)
                bodySize -= 4
            }
            if (bodySize > MAX_VALUE_BYTES) {
                skipFully(input, bodySize.toLong())
                continue
            }
            val body = ByteArray(bodySize)
            input.readFully(body)
            val value = txxxValue(body) ?: continue
            return if (readValue) value else ""
        }
        return null
    }

    /** The value of a TXXX frame body if its description is [DESCRIPTION]. */
    private fun txxxValue(body: ByteArray): String? {
        val encoding = body[0].toInt()
        val charset: Charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16 // with byte order mark
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val wide = encoding == 1 || encoding == 2
        val descEnd = terminator(body, 1, wide)
        if (descEnd < 0) return null
        val description = String(body, 1, descEnd - 1, charset)
        if (description != DESCRIPTION) return null
        val valueStart = descEnd + if (wide) 2 else 1
        // ID3v2.4 allows several values separated by a terminator; the timeline is the first.
        var valueEnd = terminator(body, valueStart, wide)
        if (valueEnd < 0) valueEnd = body.size
        return String(body, valueStart, valueEnd - valueStart, charset).trim()
    }

    /** Index of the first 0x00 (or 0x00 0x00 on an even offset for UTF-16) from [from], or -1. */
    private fun terminator(bytes: ByteArray, from: Int, wide: Boolean): Int {
        if (!wide) {
            for (i in from until bytes.size) if (bytes[i].toInt() == 0) return i
            return -1
        }
        var i = from
        while (i + 1 < bytes.size) {
            if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0) return i
            i += 2
        }
        return -1
    }

    private fun syncsafe(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0x7F shl 21) or (b[at + 1].toInt() and 0x7F shl 14) or
            (b[at + 2].toInt() and 0x7F shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun bigEndian(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
            (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                if (input.read() < 0) throw EOFException()
                left--
            }
        }
    }
}
