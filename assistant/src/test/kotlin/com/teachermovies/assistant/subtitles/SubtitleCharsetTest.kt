package com.teachermovies.assistant.subtitles

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleCharsetTest {
    @Test
    fun `decodes valid UTF-8 bytes as UTF-8`() {
        val text = "Café con leche — déjà vu"

        val decoded = SubtitleCharset.decode(text.toByteArray(StandardCharsets.UTF_8))

        assertEquals(text, decoded)
    }

    @Test
    fun `falls back to ISO-8859-1 when the bytes are not valid UTF-8`() {
        val text = "Mañana, café"
        val latin1Bytes = text.toByteArray(StandardCharsets.ISO_8859_1)

        val decoded = SubtitleCharset.decode(latin1Bytes)

        assertEquals(text, decoded)
    }
}
