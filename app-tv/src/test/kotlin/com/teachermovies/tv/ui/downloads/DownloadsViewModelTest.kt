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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
        val viewModel =
            DownloadsViewModel(engine, sync) { SpaceInfo(freeBytes = 5_000_000_000, totalBytes = 16_000_000_000) }

        assertTrue(
            viewModel.uiState.value.rows
                .isEmpty(),
        )
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

        val row =
            viewModel.uiState.value.rows
                .single()

        assertEquals(id, row.id)
        assertEquals("Movie", row.name)
        assertEquals(DownloadState.Downloading, row.state)
        assertEquals("72 %", row.percent)
        assertEquals(0.71875f, row.progress, 0.0001f)
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

        val row =
            viewModel.uiState.value.rows
                .single()
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
        assertEquals(
            DownloadState.Paused,
            viewModel.uiState.value.rows
                .single()
                .state,
        )

        viewModel.resume(id)

        assertEquals(
            DownloadState.Downloading,
            viewModel.uiState.value.rows
                .single()
                .state,
        )
    }

    @Test
    fun removeGoesThroughSyncAndDeletesThePersistedRow() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = addMagnet()
        assertTrue(runBlocking { repo.get(id) } != null)

        viewModel.remove(id, deleteFiles = false)

        assertTrue(
            viewModel.uiState.value.rows
                .isEmpty(),
        )
        assertNull(runBlocking { repo.get(id) })
    }

    @Test
    fun noDialogUntilARowIsOpened() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        addMagnet()

        assertNull(viewModel.uiState.value.dialog)
        assertNull(viewModel.uiState.value.selectedRowId)
    }

    @Test
    fun openActionsOnADownloadingRowOffersPlayFirstAndFocusesIt() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()

        viewModel.openActions(id)

        val state = viewModel.uiState.value
        assertEquals(id, state.selectedRowId)
        val dialog = state.dialog as DownloadsDialog.Actions
        assertEquals(
            listOf(
                DownloadAction.Play,
                DownloadAction.PauseResume,
                DownloadAction.ChooseFiles,
                DownloadAction.Delete,
                DownloadAction.Cancel,
            ),
            dialog.items.map { it.action },
        )
        assertTrue(dialog.items.all { it.enabled })
        assertEquals("Pausar", dialog.pauseResumeLabel)
        assertEquals(DownloadAction.Play, dialog.focused)
    }

    // -- Reproducir while downloading (#226) --

    @Test
    fun aPausedRowWithAMainFileCanBePlayed() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()
        viewModel.pause(id)

        viewModel.openActions(id)

        val dialog = viewModel.uiState.value.dialog as DownloadsDialog.Actions
        assertTrue(
            viewModel.uiState.value.rows
                .single()
                .canPlay,
        )
        assertEquals(DownloadAction.Play, dialog.items.first().action)
    }

    @Test
    fun noPlayWithoutAMainFileOrOnceCompleted() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val fetching = addMagnet()

        viewModel.openActions(fetching)
        assertFalse(
            viewModel.uiState.value.rows
                .single()
                .canPlay,
        )
        assertTrue(
            (viewModel.uiState.value.dialog as DownloadsDialog.Actions).items.none {
                it.action ==
                    DownloadAction.Play
            },
        )

        engine.emitMetadata(fetching, "Movie", listOf("Movie.mkv" to 1_000L))
        engine.complete(fetching)
        assertFalse(
            viewModel.uiState.value.rows
                .single()
                .canPlay,
        )
        assertTrue(
            (viewModel.uiState.value.dialog as DownloadsDialog.Actions).items.none {
                it.action ==
                    DownloadAction.Play
            },
        )
    }

    @Test
    fun playClosesTheDialogAndRequestsThePlayerOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = DownloadsViewModel(engine, sync) { null }
            val id = downloadingTorrent()
            val requested = mutableListOf<TorrentId>()
            backgroundScope.launch { viewModel.playRequests.collect { requested += it } }

            viewModel.openActions(id)
            viewModel.onAction(DownloadAction.Play)

            assertNull(viewModel.uiState.value.dialog)
            assertEquals(id, viewModel.uiState.value.selectedRowId)
            assertEquals(listOf(id), requested)
            // Nothing else reached the engine.
            assertTrue(engine.recordedCalls.none { it.startsWith("pause") || it.startsWith("remove") })
        }

    @Test
    fun aPausedRowOffersResume() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()
        viewModel.pause(id)

        viewModel.openActions(id)

        assertEquals("Reanudar", (viewModel.uiState.value.dialog as DownloadsDialog.Actions).pauseResumeLabel)
    }

    @Test
    fun aCompletedRowOffersPauseToStopSeeding() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()
        engine.complete(id)

        viewModel.openActions(id)

        val dialog = viewModel.uiState.value.dialog as DownloadsDialog.Actions
        assertEquals("Pausar", dialog.pauseResumeLabel)
        assertEquals(DownloadAction.PauseResume, dialog.focused)
    }

    @Test
    fun anErrorRowOffersPauseBecauseTheStateMachineAllowsIt() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()
        engine.fail(id, "disk full")

        viewModel.openActions(id)

        // `Error -> Paused` is a legal move since #145, and canPause mirrors canTransitionTo.
        val dialog = viewModel.uiState.value.dialog as DownloadsDialog.Actions
        assertTrue(dialog.items.first { it.action == DownloadAction.PauseResume }.enabled)
        assertEquals("Pausar", dialog.pauseResumeLabel)
        assertEquals(DownloadAction.PauseResume, dialog.focused)

        viewModel.onAction(DownloadAction.PauseResume)

        assertEquals(
            DownloadState.Paused,
            viewModel.uiState.value.rows
                .single()
                .state,
        )
    }

    @Test
    fun chooseFilesIsDisabledWhileFetchingMetadata() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = addMagnet()
        assertEquals(
            DownloadState.FetchingMetadata,
            viewModel.uiState.value.rows
                .single()
                .state,
        )

        viewModel.openActions(id)

        val dialog = viewModel.uiState.value.dialog as DownloadsDialog.Actions
        assertFalse(dialog.items.first { it.action == DownloadAction.ChooseFiles }.enabled)
    }

    @Test
    fun pauseResumeActionPausesThenResumesAndClosesTheDialog() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()

        viewModel.openActions(id)
        viewModel.onAction(DownloadAction.PauseResume)

        assertEquals(
            DownloadState.Paused,
            viewModel.uiState.value.rows
                .single()
                .state,
        )
        assertNull(viewModel.uiState.value.dialog)
        assertEquals(id, viewModel.uiState.value.selectedRowId)

        viewModel.openActions(id)
        viewModel.onAction(DownloadAction.PauseResume)

        assertEquals(
            DownloadState.Downloading,
            viewModel.uiState.value.rows
                .single()
                .state,
        )
        assertNull(viewModel.uiState.value.dialog)
    }

    @Test
    fun theOpenDialogFollowsTheRowState() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()
        viewModel.openActions(id)

        viewModel.pause(id)

        assertEquals("Reanudar", (viewModel.uiState.value.dialog as DownloadsDialog.Actions).pauseResumeLabel)
    }

    @Test
    fun cancelClosesTheDialogAndKeepsTheSelectedRow() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()
        viewModel.openActions(id)

        viewModel.onAction(DownloadAction.Cancel)

        assertNull(viewModel.uiState.value.dialog)
        assertEquals(id, viewModel.uiState.value.selectedRowId)
        assertEquals(
            DownloadState.Downloading,
            viewModel.uiState.value.rows
                .single()
                .state,
        )
    }

    @Test
    fun chooseFilesOpensTheChooseFilesDialog() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()
        viewModel.openActions(id)

        viewModel.onAction(DownloadAction.ChooseFiles)

        assertEquals(DownloadsDialog.ChooseFiles, viewModel.uiState.value.dialog)
        assertEquals(id, viewModel.uiState.value.selectedRowId)
    }

    @Test
    fun deleteAsksForConfirmationWithoutRemovingAnything() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()
        viewModel.openActions(id)

        viewModel.onAction(DownloadAction.Delete)

        assertEquals(DownloadsDialog.ConfirmDelete, viewModel.uiState.value.dialog)
        assertEquals(1, viewModel.uiState.value.rows.size)
    }

    @Test
    fun confirmDeleteYesRemovesTheTorrentAndItsFiles() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()
        viewModel.openActions(id)
        viewModel.onAction(DownloadAction.Delete)

        viewModel.confirmDelete(deleteFiles = true)

        assertTrue("remove(${id.value},true)" in engine.recordedCalls)
        assertTrue(
            viewModel.uiState.value.rows
                .isEmpty(),
        )
        assertNull(viewModel.uiState.value.dialog)
        assertNull(runBlocking { repo.get(id) })
    }

    @Test
    fun confirmDeleteNoRemovesTheTorrentKeepingFiles() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()
        viewModel.openActions(id)
        viewModel.onAction(DownloadAction.Delete)

        viewModel.confirmDelete(deleteFiles = false)

        assertTrue("remove(${id.value},false)" in engine.recordedCalls)
        assertTrue(
            viewModel.uiState.value.rows
                .isEmpty(),
        )
        assertNull(viewModel.uiState.value.dialog)
    }

    @Test
    fun cancelInTheConfirmationRemovesNothing() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()
        viewModel.openActions(id)
        viewModel.onAction(DownloadAction.Delete)

        viewModel.dismissDialog()

        assertNull(viewModel.uiState.value.dialog)
        assertEquals(
            id,
            viewModel.uiState.value.rows
                .single()
                .id,
        )
    }

    @Test
    fun confirmDeleteWithoutTheConfirmationOpenDoesNothing() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()
        viewModel.openActions(id)

        viewModel.confirmDelete(deleteFiles = true)

        assertEquals(
            id,
            viewModel.uiState.value.rows
                .single()
                .id,
        )
    }

    @Test
    fun aRowRemovedElsewhereClosesItsDialog() {
        val viewModel = DownloadsViewModel(engine, sync) { null }
        val id = downloadingTorrent()
        viewModel.openActions(id)

        runBlocking { engine.remove(id, deleteFiles = false) }

        assertNull(viewModel.uiState.value.dialog)
    }

    @Test
    fun serverUrlReachesTheState() {
        val url = MutableStateFlow<String?>(null)
        val viewModel = DownloadsViewModel(engine, sync, url) { null }
        assertNull(viewModel.uiState.value.serverUrl)

        url.value = "http://192.168.1.20:8787"

        assertEquals("http://192.168.1.20:8787", viewModel.uiState.value.serverUrl)
    }

    private fun downloadingTorrent(): TorrentId {
        val id = addMagnet()
        engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to 1_000L))
        engine.advance(id, bytes = 100L, rateBps = 10L, peers = 1)
        return id
    }

    private fun addMagnet(): TorrentId = (runBlocking { engine.addMagnet(validMagnet) } as EngineResult.Ok).value
}
