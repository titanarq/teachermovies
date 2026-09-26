package com.teachermovies.torrent.service

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.torrent.api.TorrentSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    private fun snapshot(
        id: String,
        state: DownloadState = DownloadState.Downloading,
        downloaded: Long = 0,
        total: Long = 0,
        rate: Long = 0,
    ) = TorrentSnapshot(
        id = TorrentId(id.repeat(40)),
        name = id,
        state = state,
        progressPercent = if (total > 0) downloaded * 100.0 / total else 0.0,
        downloadedBytes = downloaded,
        totalBytes = total,
        downloadRateBps = rate,
        uploadRateBps = 0,
        peers = 0,
        etaSeconds = null,
        ratio = 0.0,
        hasMetadata = total > 0,
        savePath = null,
        errorMessage = null,
    )

    @Test
    fun `no torrents shows no active downloads`() {
        assertEquals("Sin descargas activas", NotificationText.format(emptyList()))
    }

    @Test
    fun `one torrent uses the singular and its own progress and rate`() {
        val one = snapshot("a", downloaded = 45, total = 100, rate = 8_300_000)
        assertEquals("1 descarga · 45 % · 8,3 MB/s", NotificationText.format(listOf(one)))
    }

    @Test
    fun `several torrents combine bytes and add rates`() {
        val text =
            NotificationText.format(
                listOf(
                    snapshot("a", downloaded = 300, total = 1_000, rate = 5_000_000),
                    snapshot("b", downloaded = 600, total = 1_000, rate = 3_300_000),
                ),
            )
        assertEquals("2 descargas · 45 % · 8,3 MB/s", text)
    }

    @Test
    fun `completed and failed torrents are excluded from the active count`() {
        val text =
            NotificationText.format(
                listOf(
                    snapshot("a", downloaded = 45, total = 100, rate = 1_250_000),
                    snapshot("b", state = DownloadState.Completed, downloaded = 100, total = 100),
                    snapshot("c", state = DownloadState.Error, downloaded = 10, total = 100),
                ),
            )
        assertEquals("1 descarga · 45 % · 1,3 MB/s", text)
    }

    @Test
    fun `only completed torrents shows no active downloads`() {
        val done = snapshot("a", state = DownloadState.Completed, downloaded = 100, total = 100)
        assertEquals("Sin descargas activas", NotificationText.format(listOf(done)))
    }

    @Test
    fun `nothing downloading means the idle title and the idle text, never a contradiction`() {
        val idle =
            listOf(
                emptyList(),
                listOf(snapshot("a", state = DownloadState.Completed, downloaded = 100, total = 100)),
                listOf(snapshot("b", state = DownloadState.Error, downloaded = 10, total = 100)),
                listOf(
                    snapshot("c", state = DownloadState.Completed, downloaded = 100, total = 100),
                    snapshot("d", state = DownloadState.Error),
                ),
            )
        idle.forEach { snapshots ->
            assertFalse(NotificationText.hasActiveDownloads(snapshots))
            assertEquals("Sin descargas activas", NotificationText.format(snapshots))
        }
    }

    @Test
    fun `every unfinished state means the downloading title and a text that is not the idle one`() {
        val unfinished = DownloadState.entries.filter { it != DownloadState.Completed && it != DownloadState.Error }
        unfinished.forEach { state ->
            val snapshots = listOf(snapshot("a", state = state))
            assertTrue("$state should download", NotificationText.hasActiveDownloads(snapshots))
            assertNotEquals("Sin descargas activas", NotificationText.format(snapshots))
        }
    }

    @Test
    fun `torrents without metadata count as active and fall back to reported progress`() {
        val text =
            NotificationText.format(
                listOf(
                    snapshot("a", state = DownloadState.FetchingMetadata),
                    snapshot("b", state = DownloadState.FetchingMetadata),
                    snapshot("c", state = DownloadState.Paused),
                ),
            )
        assertEquals("3 descargas · 0 % · 0 B/s", text)
    }

    @Test
    fun `progress is rounded down so it never shows 100 before completion`() {
        val almost = snapshot("a", downloaded = 999, total = 1_000)
        assertEquals("1 descarga · 99 % · 0 B/s", NotificationText.format(listOf(almost)))
    }

    @Test
    fun `speed uses the Spanish decimal comma and scales units`() {
        assertEquals("512 B/s", NotificationText.speed(512))
        assertEquals("1,5 kB/s", NotificationText.speed(1_500))
        assertEquals("8,3 MB/s", NotificationText.speed(8_250_000))
        assertEquals("1,2 GB/s", NotificationText.speed(1_200_000_000))
        assertEquals("0 B/s", NotificationText.speed(-5))
    }
}
