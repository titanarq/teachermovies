package com.teachermovies.http

import com.teachermovies.storage.DownloadLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Covers [LayoutSubtitleStore] directly (#61): sanitising, writing, and rejecting escape attempts. */
class LayoutSubtitleStoreTest {
    @get:Rule val tmpFolder = TemporaryFolder()

    private val torrentId = "a".repeat(40)
    private val layout by lazy { DownloadLayout(tmpFolder.root) }
    private val store = LayoutSubtitleStore { id -> if (id == torrentId) layout else null }

    @Test
    fun `save writes the bytes under subs and returns the absolute path`() {
        val result = store.save(torrentId, "Movie.srt", "1\n00:00:01,000 --> 00:00:02,000\nHi\n".toByteArray())

        val path = result.getOrThrow()
        val expected = layout.resolveInTorrent(torrentId, "subs/Movie.srt")
        assertEquals(expected.absolutePath, path)
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nHi\n", File(path).readText())
    }

    @Test
    fun `save sanitises the file name to A-Za-z0-9 dot underscore hyphen`() {
        val result = store.save(torrentId, "Le Film (2020) #1?.srt", "x".toByteArray())

        val path = result.getOrThrow()
        assertEquals("LeFilm20201.srt", File(path).name)
    }

    @Test
    fun `save keeps the file inside the torrent's subs directory however the name tries to escape`() {
        for (attempt in listOf("../../evil.srt", "..\\..\\evil.srt", "/etc/passwd.srt", "a/b/../../evil.srt")) {
            val result = store.save(torrentId, attempt, "x".toByteArray())

            val path = File(result.getOrThrow())
            assertTrue(
                "escaped for name \"$attempt\": $path",
                path.absolutePath.startsWith(layout.resolveInTorrent(torrentId, SUBTITLES_SUBDIR).absolutePath),
            )
        }
    }

    @Test
    fun `save fails when the sanitised name is empty, a single dot, or two dots`() {
        for (name in listOf("???.???", ".", "..", "%%%", "***")) {
            assertTrue("name \"$name\" was accepted", store.save(torrentId, name, ByteArray(0)).isFailure)
        }
    }

    @Test
    fun `save fails when there is no layout for the torrent`() {
        val result = store.save("unknown-torrent-id", "movie.srt", ByteArray(0))

        assertTrue(result.isFailure)
    }
}
