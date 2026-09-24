package com.teachermovies.tv.ui.downloads

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.storage.SpaceInfo
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.fake.FakeTorrentEngine
import com.teachermovies.torrent.sync.EngineRepositorySync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FakeTorrentEngine] + [InMemoryTorrentRepository] (ADR-0003), both driven through an unconfined
 * test dispatcher so every emission -- engine state change or repository write -- is visible
 * synchronously, the same style as `FirstRunViewModelTest`: no virtual-time pump needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    private val validMagnet = "magnet:?xt=urn:btih:${"a".repeat(40)}&dn=Movie"

    private val engine = FakeTorrentEngine()
    private val repo = InMemoryTorrentRepository()
    private val syncScope = CoroutineScope(UnconfinedTestDispatcher())
    private val sync =
        EngineRepositorySync(engine, repo, syncScope) { System.currentTimeMillis() }.also { it.start() }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun emptyEngineMeansNoRowsAndFreeSpaceFromSpace() {
        val viewModel = DownloadsViewModel(engine, sync) { SpaceInfo(freeBytes = 5_000_000_000, totalBytes = 16_000_000_000) }

        assertTrue(viewModel.uiState.value.rows.isEmpty())
        assertEquals("5,0 GB", viewModel.uiState.value.freeSpace)
    }

    @Test
    fun nullSpaceMeansNoFreeSpaceText() {
        val viewModel = DownloadsViewModel(engine, sync) { null }

        assertNull(viewModel.uiState.value.freeSpace)
    }

    @Test
    fun aDownloadingTorrentBecomesAFormattedRow() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = addMagnet()
        engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 25_600_000_000L))
        engine.advance(id, bytes = 18_400_000_000L, rateBps = 8_300_000L, peers = 4)

        val row = viewModel.uiState.value.rows.single()

        assertEquals(id, row.id)
        assertEquals("Movie", row.name)
        assertEquals(DownloadState.Downloading, row.state)
        assertEquals("72 %", row.percent)
        assertEquals("18,4 / 25,6 GB", row.sizeText)
        assertEquals("8,3 MB/s", row.speed)
        assertEquals(4, row.peers)
        assertEquals("0,00", row.ratio)
        assertTrue(row.canPause)
        assertFalse(row.canResume)
    }

    @Test
    fun aPausedTorrentCanBeResumedButNotPausedAgain() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = addMagnet()
        engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 1_000L))

        viewModel.pause(id)

        val row = viewModel.uiState.value.rows.single()
        assertEquals(DownloadState.Paused, row.state)
        assertFalse(row.canPause)
        assertTrue(row.canResume)
    }

    @Test
    fun resumeMovesAPausedTorrentBackToDownloading() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = addMagnet()
        engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 1_000L))
        viewModel.pause(id)
        assertEquals(DownloadState.Paused, viewModel.uiState.value.rows.single().state)

        viewModel.resume(id)

        assertEquals(DownloadState.Downloading, viewModel.uiState.value.rows.single().state)
    }

    @Test
    fun removeGoesThroughSyncAndDeletesThePersistedRow() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = addMagnet()
        assertTrue(runBlocking { repo.get(id) } != null)

        viewModel.remove(id, deleteFiles = false)

        assertTrue(viewModel.uiState.value.rows.isEmpty())
        assertNull(runBlocking { repo.get(id) })
    }

    private fun addMagnet(): TorrentId = (runBlocking { engine.addMagnet(validMagnet) } as EngineResult.Ok).value
}
