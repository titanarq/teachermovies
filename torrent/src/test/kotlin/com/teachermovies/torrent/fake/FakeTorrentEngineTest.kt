package com.teachermovies.torrent.fake

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.torrent.api.EngineError
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.EngineStatus
import com.teachermovies.torrent.api.FilePriority
import com.teachermovies.torrent.api.TorrentEngineContractTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * [TorrentEngineContractTest] proves [FakeTorrentEngine] satisfies the shared contract; the tests
 * below cover what is specific to it: its test controls and [FakeTorrentEngine.recordedCalls].
 */
class FakeTorrentEngineTest : TorrentEngineContractTest() {
    override fun createEngine(): FakeTorrentEngine = FakeTorrentEngine()

    private val validMagnet = "magnet:?xt=urn:btih:${"a".repeat(40)}&dn=Movie"
    private val validTorrentFile = "d8:announce...".toByteArray()

    @Test
    fun addTorrentFileDerivesTheIdFromItsSha1Hash() =
        runTest {
            val engine = FakeTorrentEngine()
            val expectedId = TorrentId(sha1Hex(validTorrentFile))

            val result = engine.addTorrentFile(validTorrentFile)

            assertEquals(EngineResult.Ok(expectedId), result)
            val snapshot = engine.torrents.value.first { it.id == expectedId }
            assertEquals(DownloadState.FetchingMetadata, snapshot.state)
            assertEquals(expectedId.value, snapshot.name)
        }

    @Test
    fun addTorrentFileRejectsBytesNotStartingWithD() =
        runTest {
            val engine = FakeTorrentEngine()

            val result = engine.addTorrentFile("not a torrent".toByteArray())

            assertEquals(EngineResult.Failure(EngineError.InvalidTorrentFile), result)
        }

    @Test
    fun addTorrentFileRejectsEmptyBytes() =
        runTest {
            val engine = FakeTorrentEngine()

            val result = engine.addTorrentFile(ByteArray(0))

            assertEquals(EngineResult.Failure(EngineError.InvalidTorrentFile), result)
        }

    @Test
    fun addTorrentFileTwiceIsAlreadyExists() =
        runTest {
            val engine = FakeTorrentEngine()
            val id = (engine.addTorrentFile(validTorrentFile) as EngineResult.Ok).value

            val result = engine.addTorrentFile(validTorrentFile)

            assertEquals(EngineResult.Failure(EngineError.AlreadyExists(id)), result)
        }

    @Test
    fun emitMetadataMovesToDownloadingWithTotalBytes() =
        runTest {
            val engine = FakeTorrentEngine()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value

            engine.emitMetadata(id, "Movie Name", listOf("Movie.mkv" to 1_000L, "Movie.srt" to 10L))

            val snapshot = engine.torrents.value.first { it.id == id }
            assertEquals(DownloadState.Downloading, snapshot.state)
            assertEquals("Movie Name", snapshot.name)
            assertEquals(1_010L, snapshot.totalBytes)
            assertTrue(snapshot.hasMetadata)
            val files = (engine.files(id) as EngineResult.Ok).value
            assertEquals(2, files.size)
            assertEquals(FilePriority.Normal, files[0].priority)
        }

    @Test
    fun emitMetadataAppliesTheAutomaticFileSelection() =
        runTest {
            val engine = FakeTorrentEngine()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value

            engine.emitMetadata(
                id,
                "Movie",
                listOf("Movie/Sample/sample.mkv" to 50L, "Movie/Movie.mkv" to 1_000L, "Movie/Movie.srt" to 10L, "Movie/cover.jpg" to 5L),
            )

            val snapshot = engine.torrents.value.first { it.id == id }
            assertEquals(1, snapshot.mainFileIndex)
            assertEquals(1_010L, snapshot.totalBytes)
            val priorities = (engine.files(id) as EngineResult.Ok).value.map { it.priority }
            assertEquals(listOf(FilePriority.Skip, FilePriority.Normal, FilePriority.Normal, FilePriority.Skip), priorities)
        }

    @Test
    fun mainFileIndexIsNullBeforeMetadataAndWithoutAVideo() =
        runTest {
            val engine = FakeTorrentEngine()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value
            assertNull(engine.torrents.value.first { it.id == id }.mainFileIndex)

            engine.emitMetadata(id, "Docs", listOf("a.txt" to 1L, "b.pdf" to 2L))

            val snapshot = engine.torrents.value.first { it.id == id }
            assertNull(snapshot.mainFileIndex)
            assertEquals(3L, snapshot.totalBytes)
        }

    @Test
    fun setFilePrioritiesRecomputesTotalBytesExcludingSkipped() =
        runTest {
            val engine = FakeTorrentEngine()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value
            engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 1_000L, "Sample.mkv" to 200L))

            val result = engine.setFilePriorities(id, mapOf(1 to FilePriority.Skip))

            assertEquals(EngineResult.Ok(Unit), result)
            val snapshot = engine.torrents.value.first { it.id == id }
            assertEquals(1_000L, snapshot.totalBytes)
            val files = (engine.files(id) as EngineResult.Ok).value
            assertEquals(FilePriority.Skip, files[1].priority)
            assertEquals(FilePriority.Normal, files[0].priority)
        }

    @Test
    fun advanceUpdatesProgressRatePeersAndEta() =
        runTest {
            val engine = FakeTorrentEngine()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value
            engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 1_000L))

            engine.advance(id, bytes = 500L, rateBps = 100L, peers = 3)

            val snapshot = engine.torrents.value.first { it.id == id }
            assertEquals(500L, snapshot.downloadedBytes)
            assertEquals(50.0, snapshot.progressPercent, 0.0)
            assertEquals(100L, snapshot.downloadRateBps)
            assertEquals(3, snapshot.peers)
            assertEquals(5L, snapshot.etaSeconds)
            assertEquals(DownloadState.Downloading, snapshot.state)
        }

    @Test
    fun advanceNeverExceedsTotalBytes() =
        runTest {
            val engine = FakeTorrentEngine()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value
            engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 1_000L))

            engine.advance(id, bytes = 5_000L)

            val snapshot = engine.torrents.value.first { it.id == id }
            assertEquals(1_000L, snapshot.downloadedBytes)
            assertEquals(100.0, snapshot.progressPercent, 0.0)
        }

    @Test
    fun completeMarksTheTorrentFullyDownloaded() =
        runTest {
            val engine = FakeTorrentEngine()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value
            engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 1_000L))
            engine.advance(id, bytes = 200L, rateBps = 50L)

            engine.complete(id)

            val snapshot = engine.torrents.value.first { it.id == id }
            assertEquals(DownloadState.Completed, snapshot.state)
            assertEquals(1_000L, snapshot.downloadedBytes)
            assertEquals(100.0, snapshot.progressPercent, 0.0)
            assertNull(snapshot.etaSeconds)
        }

    @Test
    fun failMarksTheTorrentInError() =
        runTest {
            val engine = FakeTorrentEngine()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value

            engine.fail(id, "disk full")

            val snapshot = engine.torrents.value.first { it.id == id }
            assertEquals(DownloadState.Error, snapshot.state)
            assertEquals("disk full", snapshot.errorMessage)
        }

    @Test
    fun setEngineStatusUpdatesTheEngineStatusFlow() =
        runTest {
            val engine = FakeTorrentEngine()

            engine.setEngineStatus(EngineStatus.Running)

            assertEquals(EngineStatus.Running, engine.engineStatus.value)
        }

    @Test
    fun prioritizeWindowAndClearWindowRecordTheCall() =
        runTest {
            val engine = FakeTorrentEngine()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value

            val prioritizeResult = engine.prioritizeWindow(id, fileIndex = 0, byteOffset = 100L, windowBytes = 200L)
            val clearResult = engine.clearWindow(id)

            assertEquals(EngineResult.Ok(Unit), prioritizeResult)
            assertEquals(EngineResult.Ok(Unit), clearResult)
            assertEquals(
                listOf("prioritizeWindow(${id.value},0,100,200)", "clearWindow(${id.value})"),
                engine.recordedCalls,
            )
        }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
