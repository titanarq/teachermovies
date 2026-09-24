package com.teachermovies.assistant.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SubtitleParsersTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parses a well-formed srt file`() {
        val file = tempFolder.newFile("movie.srt")
        file.writeText("1\n00:00:01,000 --> 00:00:02,000\nHello.\n")

        val result = SubtitleParsers.parse(file)

        assertTrue(result is ParseResult.Parsed)
        val track = (result as ParseResult.Parsed).track
        assertEquals(1, track.cues.size)
        assertEquals("Hello.", track.cues[0].text)
    }

    @Test
    fun `parses a well-formed ass file`() {
        val file = tempFolder.newFile("movie.ass")
        file.writeText(
            "[Events]\nFormat: Start, End, Text\nDialogue: 0:00:01.00,0:00:02.00,Hello.\n",
        )

        val result = SubtitleParsers.parse(file)

        assertTrue(result is ParseResult.Parsed)
        val track = (result as ParseResult.Parsed).track
        assertEquals(1, track.cues.size)
        assertEquals("Hello.", track.cues[0].text)
    }

    @Test
    fun `returns Unsupported for an unknown extension`() {
        val file = tempFolder.newFile("notes.txt")
        file.writeText("just some text")

        val result = SubtitleParsers.parse(file)

        assertEquals(ParseResult.Unsupported("txt"), result)
    }

    @Test
    fun `returns Malformed for an empty srt file`() {
        val file = tempFolder.newFile("empty.srt")
        file.writeText("")

        val result = SubtitleParsers.parse(file)

        assertTrue(result is ParseResult.Malformed)
    }

    @Test
    fun `returns Malformed for a file that cannot be read`() {
        val file = File(tempFolder.root, "missing.srt")

        val result = SubtitleParsers.parse(file)

        assertTrue(result is ParseResult.Malformed)
    }

    @Test
    fun `decodes latin-1 subtitle files`() {
        val file = tempFolder.newFile("latin1.srt")
        val text = "1\n00:00:01,000 --> 00:00:02,000\nCafé español\n"
        file.writeBytes(text.toByteArray(Charsets.ISO_8859_1))

        val result = SubtitleParsers.parse(file)

        assertTrue(result is ParseResult.Parsed)
        val track = (result as ParseResult.Parsed).track
        assertEquals("Café español", track.cues[0].text)
    }
}
