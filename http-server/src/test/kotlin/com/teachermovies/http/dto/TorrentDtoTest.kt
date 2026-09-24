package com.teachermovies.http.dto

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.torrent.api.TorrentSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentDtoTest {
    private fun snapshot(
        state: DownloadState = DownloadState.Downloading,
        progressPercent: Double = 0.0,
    ) = TorrentSnapshot(
        id = TorrentId("a".repeat(40)),
        name = "Movie",
        state = state,
        progressPercent = progressPercent,
        downloadedBytes = 18_400_000_000L,
        totalBytes = 25_600_000_000L,
        downloadRateBps = 8_240_000L,
        uploadRateBps = 12_000L,
        peers = 34,
        etaSeconds = 900L,
        ratio = 0.5,
        hasMetadata = true,
        savePath = "/movies/${"a".repeat(40)}",
        errorMessage = null,
        mainFileIndex = 0,
    )

    @Test
    fun `maps every field, including the docs VISION example`() {
        val dto = snapshot(progressPercent = 17.4).toDto()

        assertEquals(
            TorrentDto(
                id = "a".repeat(40),
                name = "Movie",
                state = "downloading",
                progress = 17.4,
                downloadedBytes = 18_400_000_000L,
                totalBytes = 25_600_000_000L,
                downloadSpeed = 8_240_000L,
                uploadSpeed = 12_000L,
                peers = 34,
                etaSeconds = 900L,
                ratio = 0.5,
            ),
            dto,
        )
    }

    @Test
    fun `null eta stays null`() {
        val dto = snapshot().copy(etaSeconds = null).let { it.toDto() }

        assertEquals(null, dto.etaSeconds)
    }

    @Test
    fun `progress rounds to one decimal, ties to even`() {
        assertEquals(17.4, snapshot(progressPercent = 17.44).toDto().progress, 0.0)
        assertEquals(17.4, snapshot(progressPercent = 17.449999).toDto().progress, 0.0)
        assertEquals(17.4, snapshot(progressPercent = 17.45).toDto().progress, 0.0)
        assertEquals(17.6, snapshot(progressPercent = 17.55).toDto().progress, 0.0)
        assertEquals(0.0, snapshot(progressPercent = 0.0).toDto().progress, 0.0)
        assertEquals(100.0, snapshot(progressPercent = 100.0).toDto().progress, 0.0)
    }

    @Test
    fun `every DownloadState maps to its snake_case wire name`() {
        val expected =
            mapOf(
                DownloadState.FetchingMetadata to "fetching_metadata",
                DownloadState.Queued to "queued",
                DownloadState.Downloading to "downloading",
                DownloadState.Paused to "paused",
                DownloadState.Verifying to "verifying",
                DownloadState.Completed to "completed",
                DownloadState.Error to "error",
            )

        expected.forEach { (state, wireName) ->
            assertEquals(wireName, snapshot(state = state).toDto().state)
        }
    }
}
