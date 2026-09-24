package com.teachermovies.assistant.subtitles

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decodes subtitle file bytes as UTF-8, falling back to ISO-8859-1 when the bytes are not valid
 * UTF-8. Subtitle files found in the wild frequently use Latin-1 without declaring an encoding.
 */
internal object SubtitleCharset {
    fun decode(bytes: ByteArray): String {
        val strictUtf8Decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strictUtf8Decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, StandardCharsets.ISO_8859_1)
        }
    }
}
