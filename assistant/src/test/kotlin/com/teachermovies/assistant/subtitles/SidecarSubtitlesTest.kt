package com.teachermovies.assistant.subtitles

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SidecarSubtitlesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val mediaFile: File
        get() = File(tempFolder.root, "movie.mkv")

    private fun touch(path: String): File {
        val file = File(tempFolder.root, path)
        file.parentFile.mkdirs()
        file.writeText("")
        return file
    }

    @Test
    fun `prefers the base name with the language over every other candidate`() {
        touch("movie.ass")
        touch("movie.srt")
        touch("movie.en.ass")
        touch("other.en.srt")
        val expected = touch("movie.en.srt")

        assertEquals(expected, SidecarSubtitles.findFor(mediaFile))
    }

    @Test
    fun `prefers the language-tagged base name even as ass over a plain srt`() {
        touch("movie.srt")
        val expected = touch("movie.en.ass")

        assertEquals(expected, SidecarSubtitles.findFor(mediaFile))
    }

    @Test
    fun `prefers srt over ass within the same rank`() {
        touch("movie.ass")
        val expected = touch("movie.srt")

        assertEquals(expected, SidecarSubtitles.findFor(mediaFile))
    }

    @Test
    fun `prefers the plain base name over an unrelated language-tagged file`() {
        touch("release-group.en.srt")
        val expected = touch("movie.ass")

        assertEquals(expected, SidecarSubtitles.findFor(mediaFile))
    }

    @Test
    fun `falls back to any language-tagged file`() {
        touch("b_english.en.ass")
        touch("d_english.en.srt")
        val expected = touch("c_english.en.srt")

        assertEquals(expected, SidecarSubtitles.findFor(mediaFile))
    }

    @Test
    fun `finds a sidecar in the Subs subdirectory`() {
        val expected = touch("Subs/movie.en.srt")

        assertEquals(expected, SidecarSubtitles.findFor(mediaFile))
    }

    @Test
    fun `matches the Subs directory name case-insensitively`() {
        val expected = touch("subs/2_English.en.srt")

        assertEquals(expected, SidecarSubtitles.findFor(mediaFile))
    }

    @Test
    fun `matches file names case-insensitively`() {
        val expected = touch("MOVIE.EN.SRT")

        assertEquals(expected, SidecarSubtitles.findFor(mediaFile))
    }

    @Test
    fun `prefers the media directory over Subs within the same rank and extension`() {
        touch("Subs/movie.en.srt")
        val expected = touch("movie.en.srt")

        assertEquals(expected, SidecarSubtitles.findFor(mediaFile))
    }

    @Test
    fun `ranks extension before directory`() {
        touch("movie.en.ass")
        val expected = touch("Subs/movie.en.srt")

        assertEquals(expected, SidecarSubtitles.findFor(mediaFile))
    }

    @Test
    fun `honours a non-default language`() {
        touch("movie.en.srt")
        val expected = touch("movie.es.srt")

        assertEquals(expected, SidecarSubtitles.findFor(mediaFile, language = "es"))
    }

    @Test
    fun `matches the language argument case-insensitively`() {
        val expected = touch("movie.es.srt")

        assertEquals(expected, SidecarSubtitles.findFor(mediaFile, language = "ES"))
    }

    @Test
    fun `returns null when only other languages and unsupported files are present`() {
        touch("movie.es.srt")
        touch("movie.txt")
        touch("movie.en.txt")

        assertNull(SidecarSubtitles.findFor(mediaFile))
    }

    @Test
    fun `returns null when the directory is empty`() {
        assertNull(SidecarSubtitles.findFor(mediaFile))
    }

    @Test
    fun `returns null without throwing when the directory does not exist`() {
        val missing = File(tempFolder.root, "no-such-dir/movie.mkv")

        assertNull(SidecarSubtitles.findFor(missing))
    }

    @Test
    fun `ignores a directory whose name looks like a subtitle file`() {
        File(tempFolder.root, "movie.en.srt").mkdirs()

        assertNull(SidecarSubtitles.findFor(mediaFile))
    }
}
