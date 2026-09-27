package com.teachermovies.bridge.opensubtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTextTest {
    private val spanish = "1\n00:00:01,000 --> 00:00:02,000\n¿Qué pasó? ¡Año niño!\n"

    @Test
    fun `plain UTF-8 is kept as is`() {
        assertEquals(spanish, SubtitleText.normalise(spanish.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a UTF-8 byte-order mark is dropped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + spanish.toByteArray(Charsets.UTF_8)

        assertEquals(spanish, SubtitleText.normalise(bytes))
    }

    @Test
    fun `UTF-16 with a byte-order mark is decoded, either byte order`() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + spanish.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + spanish.toByteArray(Charsets.UTF_16BE)

        assertEquals(spanish, SubtitleText.normalise(le))
        assertEquals(spanish, SubtitleText.normalise(be))
    }

    @Test
    fun `anything that is not valid UTF-8 is read as Windows-1252`() {
        val legacy = "“Hola”, dijo el niño — ¿qué tal?\n"

        assertEquals(legacy, SubtitleText.normalise(legacy.toByteArray(charset("windows-1252"))))
        assertEquals(spanish, SubtitleText.normalise(spanish.toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `an empty file is an empty text`() {
        assertEquals("", SubtitleText.normalise(ByteArray(0)))
    }
}
