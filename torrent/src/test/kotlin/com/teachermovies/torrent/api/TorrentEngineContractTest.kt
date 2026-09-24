package com.teachermovies.torrent.api

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behavioural contract every [TorrentEngine] implementation must satisfy, exercised against
 * whatever [createEngine] returns. [com.teachermovies.torrent.fake.FakeTorrentEngineTest] extends
 * this for [com.teachermovies.torrent.fake.FakeTorrentEngine]; the jlibtorrent-backed engine (#51)
 * is expected to extend it too so both engines are held to the same contract.
 */
abstract class TorrentEngineContractTest {
    /** A fresh engine instance for each test; implementations must not share state across calls. */
    abstract fun createEngine(): TorrentEngine

    private val validMagnet = "magnet:?xt=urn:btih:${"a".repeat(40)}&dn=Movie"
    private val unknownId = TorrentId("b".repeat(40))

    @Test
    fun addingAMagnetStartsFetchingMetadata() =
        runTest {
            val engine = createEngine()

            val result = engine.addMagnet(validMagnet)

            val id = (result as EngineResult.Ok).value
            val snapshot = engine.torrents.value.first { it.id == id }
            assertEquals(DownloadState.FetchingMetadata, snapshot.state)
            assertEquals("Movie", snapshot.name)
        }

    @Test
    fun addingTheSameMagnetTwiceIsAlreadyExists() =
        runTest {
            val engine = createEngine()
            val first = (engine.addMagnet(validMagnet) as EngineResult.Ok).value

            val result = engine.addMagnet(validMagnet)

            assertEquals(EngineResult.Failure(EngineError.AlreadyExists(first)), result)
        }

    @Test
    fun anInvalidMagnetIsRejected() =
        runTest {
            val engine = createEngine()

            val result = engine.addMagnet("not a magnet")

            assertEquals(EngineResult.Failure(EngineError.InvalidMagnet), result)
        }

    @Test
    fun pauseThenResumeBeforeMetadataReturnsToQueued() =
        runTest {
            val engine = createEngine()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value

            assertEquals(EngineResult.Ok(Unit), engine.pause(id))
            assertEquals(DownloadState.Paused, engine.torrents.value.first { it.id == id }.state)

            assertEquals(EngineResult.Ok(Unit), engine.resume(id))
            assertEquals(DownloadState.Queued, engine.torrents.value.first { it.id == id }.state)
        }

    @Test
    fun removingATorrentDropsItFromTheList() =
        runTest {
            val engine = createEngine()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value

            val result = engine.remove(id, deleteFiles = false)

            assertEquals(EngineResult.Ok(Unit), result)
            assertTrue(engine.torrents.value.none { it.id == id })
        }

    @Test
    fun operationsOnAnUnknownIdReturnUnknownTorrent() =
        runTest {
            val engine = createEngine()

            assertEquals(EngineResult.Failure(EngineError.UnknownTorrent), engine.pause(unknownId))
            assertEquals(EngineResult.Failure(EngineError.UnknownTorrent), engine.resume(unknownId))
            assertEquals(EngineResult.Failure(EngineError.UnknownTorrent), engine.remove(unknownId, false))
            assertEquals(EngineResult.Failure(EngineError.UnknownTorrent), engine.files(unknownId))
            assertEquals(
                EngineResult.Failure(EngineError.UnknownTorrent),
                engine.setFilePriorities(unknownId, emptyMap()),
            )
        }
}
