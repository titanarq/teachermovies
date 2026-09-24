package com.teachermovies.assistant.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtParserTest {
    private val parser = SrtParser()

    @Test
    fun `supports srt extension case-insensitively, not other extensions`() {
        assertTrue(parser.supports("movie.srt"))
        assertTrue(parser.supports("movie.SRT"))
        assertFalse(parser.supports("movie.ass"))
        assertFalse(parser.supports("movie"))
    }

    @Test
    fun `parses a well-formed file with LF endings`() {
        val srt =
            """
            1
            00:00:01,000 --> 00:00:04,000
            Hello there.

            2
            00:00:05,500 --> 00:00:06,000
            Second cue.

            """.trimIndent()

        val track = parser.parse(srt)

        assertEquals(2, track.cues.size)
        assertEquals(SubtitleCue(0, 1_000L, 4_000L, "Hello there."), track.cues[0])
        assertEquals(SubtitleCue(1, 5_500L, 6_000L, "Second cue."), track.cues[1])
    }

    @Test
    fun `strips a leading BOM`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello.\n"

        val track = parser.parse(srt)

        assertEquals(1, track.cues.size)
        assertEquals("Hello.", track.cues[0].text)
        assertEquals(1_000L, track.cues[0].startMs)
    }

    @Test
    fun `handles CRLF line endings`() {
        val srt =
            "1\r\n00:00:01,000 --> 00:00:02,000\r\nHello.\r\n\r\n" +
                "2\r\n00:00:03,000 --> 00:00:04,000\r\nWorld.\r\n"

        val track = parser.parse(srt)

        assertEquals(2, track.cues.size)
        assertEquals("Hello.", track.cues[0].text)
        assertEquals("World.", track.cues[1].text)
    }

    @Test
    fun `accepts either comma or dot as the millisecond separator`() {
        val srt = "1\n00:00:01,000 --> 00:00:02.500\nComma then dot.\n"

        val track = parser.parse(srt)

        assertEquals(1, track.cues.size)
        assertEquals(1_000L, track.cues[0].startMs)
        assertEquals(2_500L, track.cues[0].endMs)
    }

    @Test
    fun `keeps multi-line cue text joined by newlines`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nFirst line\nSecond line\n"

        val track = parser.parse(srt)

        assertEquals(1, track.cues.size)
        assertEquals("First line\nSecond line", track.cues[0].text)
    }

    @Test
    fun `tolerates extra blank lines between blocks and a missing final blank line`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nOne.\n\n\n\n2\n00:00:03,000 --> 00:00:04,000\nTwo."

        val track = parser.parse(srt)

        assertEquals(2, track.cues.size)
        assertEquals("One.", track.cues[0].text)
        assertEquals("Two.", track.cues[1].text)
    }

    @Test
    fun `renumbers regardless of non-numeric or repeated block indexes`() {
        val srt =
            "abc\n00:00:05,000 --> 00:00:06,000\nLater.\n\n" +
                "abc\n00:00:01,000 --> 00:00:02,000\nEarlier.\n"

        val track = parser.parse(srt)

        assertEquals(2, track.cues.size)
        assertEquals(0, track.cues[0].index)
        assertEquals("Earlier.", track.cues[0].text)
        assertEquals(1, track.cues[1].index)
        assertEquals("Later.", track.cues[1].text)
    }

    @Test
    fun `sorts cues by start time even if the file is out of order`() {
        val srt = "1\n00:00:10,000 --> 00:00:11,000\nSecond.\n\n2\n00:00:01,000 --> 00:00:02,000\nFirst.\n"

        val track = parser.parse(srt)

        assertEquals("First.", track.cues[0].text)
        assertEquals("Second.", track.cues[1].text)
    }

    @Test
    fun `strips italic, bold, underline and font tags`() {
        val srt =
            "1\n00:00:01,000 --> 00:00:02,000\n" +
                "<i>Hello</i> <b>bold</b> <u>underline</u> <font color=\"#ffffff\">colored</font>\n"

        val track = parser.parse(srt)

        assertEquals(1, track.cues.size)
        assertEquals("Hello bold underline colored", track.cues[0].text)
    }

    @Test
    fun `drops cues whose text is empty after stripping tags`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n<i></i>\n\n2\n00:00:03,000 --> 00:00:04,000\nReal cue.\n"

        val track = parser.parse(srt)

        assertEquals(1, track.cues.size)
        assertEquals("Real cue.", track.cues[0].text)
    }

    @Test
    fun `returns an empty track for blank input`() {
        val track = parser.parse("")

        assertEquals(0, track.cues.size)
    }
}
