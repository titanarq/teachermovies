package com.teachermovies.torrent.api

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compiles the `:torrent` public API contract: every value type constructs, and [EngineResult] (and
 * the [EngineError] it carries) pattern-match exhaustively without an `else` branch, which is what
 * proves the sealed hierarchies are complete for a consumer.
 */
class TorrentEngineApiTest {
    private val id = TorrentId("a".repeat(40))

    @Test
    fun constructsEveryEngineStatus() {
        assertEquals(
            setOf(EngineStatus.Stopped, EngineStatus.Starting, EngineStatus.Running, EngineStatus.Error),
            EngineStatus.entries.toSet(),
        )
    }

    @Test
    fun constructsEveryFilePriority() {
        assertEquals(
            setOf(FilePriority.Skip, FilePriority.Normal, FilePriority.High),
            FilePriority.entries.toSet(),
        )
    }

    @Test
    fun constructsATorrentSnapshot() {
        val snapshot =
            TorrentSnapshot(
                id = id,
                name = "Movie Name",
                state = DownloadState.Downloading,
                progressPercent = 42.5,
                downloadedBytes = 1_000L,
                totalBytes = 2_000L,
                downloadRateBps = 500L,
                uploadRateBps = 10L,
                peers = 7,
                etaSeconds = 120L,
                ratio = 0.5,
                hasMetadata = true,
                savePath = "/movies/$id",
                errorMessage = null,
            )

        assertEquals(id, snapshot.id)
        assertEquals(DownloadState.Downloading, snapshot.state)
        assertEquals(120L, snapshot.etaSeconds)
    }

    @Test
    fun constructsATorrentSnapshotWithoutMetadataYet() {
        val snapshot =
            TorrentSnapshot(
                id = id,
                name = "",
                state = DownloadState.FetchingMetadata,
                progressPercent = 0.0,
                downloadedBytes = 0L,
                totalBytes = 0L,
                downloadRateBps = 0L,
                uploadRateBps = 0L,
                peers = 0,
                etaSeconds = null,
                ratio = 0.0,
                hasMetadata = false,
                savePath = null,
                errorMessage = null,
            )

        assertTrue(!snapshot.hasMetadata)
        assertEquals(null, snapshot.etaSeconds)
        assertEquals(null, snapshot.savePath)
    }

    @Test
    fun constructsATorrentFileInfo() {
        val file =
            TorrentFileInfo(
                index = 0,
                path = "Movie.mkv",
                sizeBytes = 4_000L,
                priority = FilePriority.High,
                downloadedBytes = 1_000L,
            )

        assertEquals(0, file.index)
        assertEquals(FilePriority.High, file.priority)
    }

    @Test
    fun matchesOkExhaustively() {
        val result: EngineResult<TorrentId> = EngineResult.Ok(id)

        val matched =
            when (result) {
                is EngineResult.Ok -> result.value
                is EngineResult.Failure -> error("expected Ok, got ${result.error}")
            }

        assertEquals(id, matched)
    }

    @Test
    fun matchesEveryEngineErrorExhaustively() {
        val errors: List<EngineError> =
            listOf(
                EngineError.InvalidMagnet,
                EngineError.InvalidTorrentFile,
                EngineError.UnknownTorrent,
                EngineError.AlreadyExists(id),
                EngineError.NotReady,
                EngineError.Unsupported,
                EngineError.Io("disk full"),
            )

        val descriptions =
            errors.map { error ->
                when (error) {
                    is EngineError.InvalidMagnet -> "invalid magnet"
                    is EngineError.InvalidTorrentFile -> "invalid torrent file"
                    is EngineError.UnknownTorrent -> "unknown torrent"
                    is EngineError.AlreadyExists -> "already exists: ${error.id}"
                    is EngineError.NotReady -> "not ready"
                    is EngineError.Unsupported -> "unsupported"
                    is EngineError.Io -> "io: ${error.message}"
                }
            }

        assertEquals(errors.size, descriptions.size)
        assertEquals("already exists: $id", descriptions[3])
        assertEquals("io: disk full", descriptions.last())
    }

    @Test
    fun matchesFailureExhaustively() {
        val result: EngineResult<Unit> = EngineResult.Failure(EngineError.NotReady)

        val error =
            when (result) {
                is EngineResult.Ok -> error("expected Failure, got a value")
                is EngineResult.Failure -> result.error
            }

        assertEquals(EngineError.NotReady, error)
    }
}
