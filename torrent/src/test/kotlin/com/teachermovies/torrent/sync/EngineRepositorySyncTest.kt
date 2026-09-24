package com.teachermovies.torrent.sync

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.fake.FakeTorrentEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FakeTorrentEngine] + [InMemoryTorrentRepository], driven with `runTest`'s virtual time (ADR-0003:
 * no real sleeps). [EngineRepositorySync] is always started on `backgroundScope`, which `runTest`
 * cancels for us, and `clock` reads the scheduler's own virtual `currentTime`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineRepositorySyncTest {
    private val validMagnet = "magnet:?xt=urn:btih:${"a".repeat(40)}&dn=Movie"

    @Test
    fun addingATorrentCreatesARowInDownloads() =
        runTest {
            val engine = FakeTorrentEngine()
            val repo = InMemoryTorrentRepository()
            EngineRepositorySync(engine, repo, backgroundScope) { currentTime }.start()

            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value
            runCurrent()

            assertEquals(DownloadState.FetchingMetadata, repo.get(id)?.state)
            assertNull(repo.get(id)?.mainFileIndex)
            assertEquals(listOf(id), repo.observeDownloads().first().map { it.id })
        }

    @Test
    fun metadataResolvesTheMainFileIndexAndSavePathImmediately() =
        runTest {
            val engine = FakeTorrentEngine()
            val repo = InMemoryTorrentRepository()
            EngineRepositorySync(engine, repo, backgroundScope) { currentTime }.start()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value
            runCurrent()

            // "Sample.mkv" is excluded by `FileSelectionPolicy`; "Movie.mkv" (index 0) is the main file.
            engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 1_000L, "Movie.srt" to 10L, "Sample.mkv" to 50L))
            runCurrent()

            val torrent = repo.get(id)
            assertEquals(DownloadState.Downloading, torrent?.state)
            assertEquals(0, torrent?.mainFileIndex)
            assertEquals("/movies/${id.value}", torrent?.savePath)
        }

    @Test
    fun completingResolvesMainFilePathAndMovesTheTorrentToTheLibrary() =
        runTest {
            val engine = FakeTorrentEngine()
            val repo = InMemoryTorrentRepository()
            EngineRepositorySync(engine, repo, backgroundScope) { currentTime }.start()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value
            runCurrent()
            engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 1_000L, "Movie.srt" to 10L))
            runCurrent()

            engine.complete(id)
            runCurrent()

            val item = repo.getLibraryItem(id)
            assertEquals("/movies/${id.value}/Movie.mkv", item?.mainFilePath)
            assertEquals(listOf(id), repo.observeLibrary().first().map { it.id })
        }

    @Test
    fun progressWritesAreThrottledToOncePerFiveSecondsExceptStateChanges() =
        runTest {
            val engine = FakeTorrentEngine()
            val repo = InMemoryTorrentRepository()
            EngineRepositorySync(engine, repo, backgroundScope) { currentTime }.start()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value
            runCurrent()
            engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 1_000L))
            runCurrent()
            // The metadata event changed the state (FetchingMetadata -> Downloading), so it was
            // written immediately, at virtual time 0.
            assertEquals(0.0, repo.get(id)?.progressPercent ?: -1.0, 0.0)

            engine.advance(id, bytes = 100L) // 10 %, same state, still inside the 5 s window: skipped.
            runCurrent()
            assertEquals(0.0, repo.get(id)?.progressPercent ?: -1.0, 0.0)

            advanceTimeBy(2_000)
            engine.advance(id, bytes = 100L) // 20 % at t=2s, still inside the window: skipped.
            runCurrent()
            assertEquals(0.0, repo.get(id)?.progressPercent ?: -1.0, 0.0)

            advanceTimeBy(3_000) // t=5s: the 5 s window has elapsed.
            engine.advance(id, bytes = 100L) // 30 %, written -- coalescing the two skipped updates.
            runCurrent()
            assertEquals(30.0, repo.get(id)?.progressPercent ?: -1.0, 0.0)
        }

    @Test
    fun removeWrapperDeletesTheRowOnlyAfterTheEngineConfirmsRemoval() =
        runTest {
            val engine = FakeTorrentEngine()
            val repo = InMemoryTorrentRepository()
            val sync = EngineRepositorySync(engine, repo, backgroundScope) { currentTime }
            sync.start()
            val id = (engine.addMagnet(validMagnet) as EngineResult.Ok).value
            runCurrent()
            assertTrue(repo.get(id) != null)

            val result = sync.remove(id, deleteFiles = false)
            runCurrent()

            assertEquals(EngineResult.Ok(Unit), result)
            assertNull(repo.get(id))
        }

    @Test
    fun aTorrentMissingAfterAnEngineRestartIsNotDeleted() =
        runTest {
            val repo = InMemoryTorrentRepository()
            val originalEngine = FakeTorrentEngine()
            val originalSync = EngineRepositorySync(originalEngine, repo, backgroundScope) { currentTime }
            originalSync.start()
            val id = (originalEngine.addMagnet(validMagnet) as EngineResult.Ok).value
            runCurrent()
            originalSync.stop()
            assertTrue(repo.get(id) != null)

            // A fresh engine, as after a process restart, that has not reloaded this torrent's
            // resume data yet -- its `torrents` list starts out empty.
            val restartedEngine = FakeTorrentEngine()
            EngineRepositorySync(restartedEngine, repo, backgroundScope) { currentTime }.start()
            runCurrent()

            assertEquals(id, repo.get(id)?.id)
        }
}
