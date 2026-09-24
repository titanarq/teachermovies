package com.teachermovies.player.mkv

import java.io.ByteArrayOutputStream

/**
 * Just enough of an EBML/Matroska muxer to build tiny test files in memory, so the tests carry no
 * binary fixtures. Element ids are written as given (marker bits included); sizes use the shortest
 * vint, or the reserved all-ones "unknown size" when asked.
 */
internal object EbmlWriter {
    const val EBML = 0x1A45DFA3L
    const val DOC_TYPE = 0x4282L
    const val SEGMENT = 0x18538067L
    const val INFO = 0x1549A966L
    const val TIMECODE_SCALE = 0x2AD7B1L
    const val TRACKS = 0x1654AE6BL
    const val TRACK_ENTRY = 0xAEL
    const val TRACK_NUMBER = 0xD7L
    const val TRACK_TYPE = 0x83L
    const val CODEC_ID = 0x86L
    const val LANGUAGE = 0x22B59CL
    const val LANGUAGE_IETF = 0x22B59DL
    const val NAME = 0x536EL
    const val CODEC_PRIVATE = 0x63A2L
    const val CONTENT_ENCODINGS = 0x6D80L
    const val CONTENT_ENCODING = 0x6240L
    const val CONTENT_ENCODING_SCOPE = 0x5032L
    const val CONTENT_COMPRESSION = 0x5034L
    const val CONTENT_COMP_ALGO = 0x4254L
    const val CONTENT_COMP_SETTINGS = 0x4255L
    const val CONTENT_ENCRYPTION = 0x5035L
    const val CLUSTER = 0x1F43B675L
    const val TIMECODE = 0xE7L
    const val SIMPLE_BLOCK = 0xA3L
    const val BLOCK_GROUP = 0xA0L
    const val BLOCK = 0xA1L
    const val BLOCK_DURATION = 0x9BL
    const val CUES = 0x1C53BB6BL
    const val VOID = 0xECL

    const val TYPE_VIDEO = 1L
    const val TYPE_AUDIO = 2L
    const val TYPE_SUBTITLE = 0x11L

    fun element(
        id: Long,
        vararg children: ByteArray,
    ): ByteArray {
        val body = concat(*children)
        return concat(idBytes(id), sizeBytes(body.size.toLong()), body)
    }

    fun unknownSize(
        id: Long,
        vararg children: ByteArray,
    ): ByteArray = concat(idBytes(id), byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1), *children)

    fun uint(
        id: Long,
        value: Long,
    ): ByteArray {
        var bytes = ByteArray(0)
        var rest = value
        do {
            bytes = byteArrayOf((rest and 0xFF).toByte()) + bytes
            rest = rest ushr 8
        } while (rest != 0L)
        return element(id, bytes)
    }

    fun string(
        id: Long,
        value: String,
    ): ByteArray = element(id, value.toByteArray(Charsets.UTF_8))

    fun binary(
        id: Long,
        value: ByteArray,
    ): ByteArray = element(id, value)

    fun ebmlHeader(docType: String = "matroska"): ByteArray = element(EBML, string(DOC_TYPE, docType))

    fun info(timecodeScale: Long): ByteArray = element(INFO, uint(TIMECODE_SCALE, timecodeScale))

    fun trackEntry(
        number: Long,
        type: Long,
        codecId: String,
        language: String? = null,
        languageIetf: String? = null,
        name: String? = null,
        codecPrivate: ByteArray? = null,
        encodings: ByteArray? = null,
    ): ByteArray =
        element(
            TRACK_ENTRY,
            uint(TRACK_NUMBER, number),
            uint(TRACK_TYPE, type),
            string(CODEC_ID, codecId),
            language?.let { string(LANGUAGE, it) } ?: ByteArray(0),
            languageIetf?.let { string(LANGUAGE_IETF, it) } ?: ByteArray(0),
            name?.let { string(NAME, it) } ?: ByteArray(0),
            codecPrivate?.let { binary(CODEC_PRIVATE, it) } ?: ByteArray(0),
            encodings ?: ByteArray(0),
        )

    fun compression(
        algorithm: Long,
        settings: ByteArray? = null,
    ): ByteArray =
        element(
            CONTENT_ENCODINGS,
            element(
                CONTENT_ENCODING,
                element(
                    CONTENT_COMPRESSION,
                    uint(CONTENT_COMP_ALGO, algorithm),
                    settings?.let { binary(CONTENT_COMP_SETTINGS, it) } ?: ByteArray(0),
                ),
            ),
        )

    fun encryption(): ByteArray =
        element(CONTENT_ENCODINGS, element(CONTENT_ENCODING, uint(0x5033L, 1), element(CONTENT_ENCRYPTION)))

    fun cluster(
        timecode: Long,
        vararg blocks: ByteArray,
    ): ByteArray = element(CLUSTER, uint(TIMECODE, timecode), *blocks)

    /** Track number (one-byte vint), signed 16-bit relative timecode, flags, then the frame. */
    fun blockBody(
        track: Long,
        relativeTimecode: Int,
        payload: ByteArray,
        flags: Int = 0x80,
    ): ByteArray =
        concat(
            byteArrayOf((0x80 or track.toInt()).toByte()),
            byteArrayOf((relativeTimecode shr 8).toByte(), relativeTimecode.toByte(), flags.toByte()),
            payload,
        )

    fun simpleBlock(
        track: Long,
        relativeTimecode: Int,
        payload: ByteArray,
        flags: Int = 0x80,
    ): ByteArray = element(SIMPLE_BLOCK, blockBody(track, relativeTimecode, payload, flags))

    fun simpleBlock(
        track: Long,
        relativeTimecode: Int,
        text: String,
    ): ByteArray = simpleBlock(track, relativeTimecode, text.toByteArray(Charsets.UTF_8))

    fun blockGroup(
        track: Long,
        relativeTimecode: Int,
        payload: ByteArray,
        duration: Long?,
    ): ByteArray =
        element(
            BLOCK_GROUP,
            element(BLOCK, blockBody(track, relativeTimecode, payload, flags = 0)),
            duration?.let { uint(BLOCK_DURATION, it) } ?: ByteArray(0),
        )

    fun blockGroup(
        track: Long,
        relativeTimecode: Int,
        text: String,
        duration: Long?,
    ): ByteArray = blockGroup(track, relativeTimecode, text.toByteArray(Charsets.UTF_8), duration)

    /** A whole file: EBML header, then a Segment of `Info` (when [timecodeScale]), `Tracks` and [body]. */
    fun mkv(
        tracks: List<ByteArray>,
        vararg body: ByteArray,
        timecodeScale: Long? = null,
        docType: String = "matroska",
    ): ByteArray =
        concat(
            ebmlHeader(docType),
            element(
                SEGMENT,
                timecodeScale?.let { info(it) } ?: ByteArray(0),
                element(TRACKS, *tracks.toTypedArray()),
                *body,
            ),
        )

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private fun idBytes(id: Long): ByteArray {
        var bytes = ByteArray(0)
        var rest = id
        while (rest != 0L) {
            bytes = byteArrayOf((rest and 0xFF).toByte()) + bytes
            rest = rest ushr 8
        }
        return bytes
    }

    private fun sizeBytes(size: Long): ByteArray {
        var length = 1
        while (size >= (1L shl (7 * length)) - 1) length++
        val bytes = ByteArray(length)
        var rest = size
        for (i in length - 1 downTo 0) {
            bytes[i] = (rest and 0xFF).toByte()
            rest = rest ushr 8
        }
        bytes[0] = (bytes[0].toInt() or (0x80 ushr (length - 1))).toByte()
        return bytes
    }
}
