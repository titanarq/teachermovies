package com.teachermovies.bridge.opensubtitles

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Normalises a downloaded subtitle file to UTF-8 text (#281). OpenSubtitles serves files in
 * whatever encoding they were uploaded in; Spanish ones in particular are often Windows-1252.
 *
 * Detection, in order: a UTF-8, UTF-16LE or UTF-16BE byte-order mark; otherwise strict UTF-8 (any
 * malformed sequence rejects it); otherwise Windows-1252, the superset of ISO-8859-1 that most
 * legacy Western subtitle files use. The byte-order mark is not kept.
 */
object SubtitleText {
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** [bytes] decoded as described above; `text.toByteArray(UTF_8)` is the normalised file. */
    fun normalise(bytes: ByteArray): String =
        when {
            bytes.startsWith(UTF8_BOM) -> decode(bytes, UTF8_BOM.size, Charsets.UTF_8)
            bytes.startsWith(UTF16LE_BOM) -> decode(bytes, UTF16LE_BOM.size, Charsets.UTF_16LE)
            bytes.startsWith(UTF16BE_BOM) -> decode(bytes, UTF16BE_BOM.size, Charsets.UTF_16BE)
            else -> strictUtf8(bytes) ?: String(bytes, WINDOWS_1252)
        }

    private fun decode(
        bytes: ByteArray,
        offset: Int,
        charset: Charset,
    ): String = String(bytes, offset, bytes.size - offset, charset)

    private fun strictUtf8(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
