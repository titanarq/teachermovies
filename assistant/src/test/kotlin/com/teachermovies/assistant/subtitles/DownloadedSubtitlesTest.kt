package com.teachermovies.assistant.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadedSubtitlesTest {
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
    fun `finds the name the bridge gives this movie in the subs directory`() {
        val expected = touch("subs/movie.en.opensubtitles.srt")

        assertEquals(expected, DownloadedSubtitles.findFor(mediaFile))
    }

    @Test
    fun `finds a download in the media file's own directory`() {
        val expected = touch("movie.en.opensubtitles.srt")

        assertEquals(expected, DownloadedSubtitles.findFor(mediaFile))
    }

    @Test
    fun `prefers the download named after this movie over one for another base`() {
        touch("release-group.en.opensubtitles.srt")
        val expected = touch("subs/movie.en.opensubtitles.srt")

        assertEquals(expected, DownloadedSubtitles.findFor(mediaFile))
    }

    @Test
    fun `falls back to a download of another base`() {
        val expected = touch("subs/release-group.en.opensubtitles.srt")

        assertEquals(expected, DownloadedSubtitles.findFor(mediaFile))
    }

    @Test
    fun `prefers srt over ass within the same rank`() {
        touch("movie.en.opensubtitles.ass")
        val expected = touch("movie.en.opensubtitles.srt")

        assertEquals(expected, DownloadedSubtitles.findFor(mediaFile))
    }

    @Test
    fun `prefers the media directory over Subs within the same rank and extension`() {
        touch("subs/movie.en.opensubtitles.srt")
        val expected = touch("movie.en.opensubtitles.srt")

        assertEquals(expected, DownloadedSubtitles.findFor(mediaFile))
    }

    @Test
    fun `ranks extension before directory`() {
        touch("movie.en.opensubtitles.ass")
        val expected = touch("subs/movie.en.opensubtitles.srt")

        assertEquals(expected, DownloadedSubtitles.findFor(mediaFile))
    }

    @Test
    fun `matches file names case-insensitively`() {
        val expected = touch("MOVIE.EN.OPENSUBTITLES.SRT")

        assertEquals(expected, DownloadedSubtitles.findFor(mediaFile))
    }

    @Test
    fun `matches the Subs directory name case-insensitively`() {
        val expected = touch("Subs/movie.en.opensubtitles.srt")

        assertEquals(expected, DownloadedSubtitles.findFor(mediaFile))
    }

    @Test
    fun `honours a non-default language`() {
        touch("movie.en.opensubtitles.srt")
        val expected = touch("movie.es.opensubtitles.srt")

        assertEquals(expected, DownloadedSubtitles.findFor(mediaFile, language = "es"))
    }

    @Test
    fun `matches the language argument case-insensitively`() {
        val expected = touch("movie.es.opensubtitles.srt")

        assertEquals(expected, DownloadedSubtitles.findFor(mediaFile, language = "ES"))
    }

    @Test
    fun `never answers the Spanish download for English`() {
        touch("subs/movie.es.opensubtitles.srt")
        touch("movie.es.opensubtitles.ass")

        assertNull(DownloadedSubtitles.findFor(mediaFile))
    }

    @Test
    fun `never answers the Spanish download of a movie whose name starts like the English code`() {
        // `entangled` starts with `en`: only the language part of the name may match it.
        val entangled = File(tempFolder.root, "entangled.mkv")
        touch("subs/entangled.es.opensubtitles.srt")

        assertNull(DownloadedSubtitles.findFor(entangled))
    }

    @Test
    fun `ignores a sidecar the movie shipped with`() {
        touch("movie.en.srt")
        touch("Subs/english.ass")

        assertNull(DownloadedSubtitles.findFor(mediaFile))
    }

    @Test
    fun `ignores a download in an extension this module cannot parse`() {
        touch("movie.en.opensubtitles.vtt")
        touch("movie.en.opensubtitles.txt")

        assertNull(DownloadedSubtitles.findFor(mediaFile))
    }

    @Test
    fun `returns null when the directory is empty`() {
        assertNull(DownloadedSubtitles.findFor(mediaFile))
    }

    @Test
    fun `returns null without throwing when the directory does not exist`() {
        val missing = File(tempFolder.root, "no-such-dir/movie.mkv")

        assertNull(DownloadedSubtitles.findFor(missing))
    }

    @Test
    fun `ignores a directory whose name looks like a download`() {
        File(tempFolder.root, "movie.en.opensubtitles.srt").mkdirs()

        assertNull(DownloadedSubtitles.findFor(mediaFile))
    }

    @Test
    fun `a downloaded name is one carrying the marker as a whole part`() {
        assertTrue(DownloadedSubtitles.isDownloadedName("movie.en.opensubtitles.srt"))
        assertTrue(DownloadedSubtitles.isDownloadedName("movie.es.opensubtitles.ass"))
        assertFalse(DownloadedSubtitles.isDownloadedName("movie.en.srt"))
        assertFalse(DownloadedSubtitles.isDownloadedName("opensubtitles.srt"))
    }
}
