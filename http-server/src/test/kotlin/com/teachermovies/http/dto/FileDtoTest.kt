package com.teachermovies.http.dto

import com.teachermovies.torrent.api.FilePriority
import com.teachermovies.torrent.api.TorrentFileInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class FileDtoTest {
    @Test
    fun `maps every field, size from sizeBytes`() {
        val file =
            TorrentFileInfo(
                index = 2,
                path = "Movie/Movie.mkv",
                sizeBytes = 18_123_456_789L,
                priority = FilePriority.High,
                downloadedBytes = 4_000_000L,
            )

        assertEquals(
            FileDto(
                index = 2,
                path = "Movie/Movie.mkv",
                size = 18_123_456_789L,
                priority = "high",
                downloadedBytes = 4_000_000L,
            ),
            file.toDto(),
        )
    }

    @Test
    fun `every FilePriority maps to its lower-case wire name`() {
        val expected =
            mapOf(
                FilePriority.Skip to "skip",
                FilePriority.Normal to "normal",
                FilePriority.High to "high",
            )

        expected.forEach { (priority, wireName) ->
            val file = TorrentFileInfo(index = 0, path = "x", sizeBytes = 0L, priority = priority, downloadedBytes = 0L)
            assertEquals(wireName, file.toDto().priority)
        }
    }
}
