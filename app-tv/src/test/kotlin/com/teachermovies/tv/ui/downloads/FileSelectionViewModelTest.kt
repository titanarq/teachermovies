package com.teachermovies.tv.ui.downloads

import com.teachermovies.core.model.TorrentId
import com.teachermovies.torrent.api.EngineError
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.FilePriority
import com.teachermovies.torrent.api.TorrentEngine
import com.teachermovies.torrent.api.TorrentFileInfo
import com.teachermovies.torrent.fake.FakeTorrentEngine
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

/** [FakeTorrentEngine] on an unconfined main dispatcher, like `DownloadsViewModelTest`. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileSelectionViewModelTest {
    private val magnet = "magnet:?xt=urn:btih:${"b".repeat(40)}&dn=Movie"
    private val engine = FakeTorrentEngine()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun addWithMetadata(): TorrentId {
        val id = runBlocking { (engine.addMagnet(magnet) as EngineResult.Ok).value }
        engine.emitMetadata(
            id,
            name = "Movie",
            files =
                listOf(
                    "Movie/Movie.mkv" to 2_000_000_000L,
                    "Movie/Sample.mkv" to 50_000_000L,
                    "Movie/Movie.en.srt" to 80_000L,
                ),
        )
        return id
    }

    @Test
    fun loadsFilesAsRowsCheckedUnlessSkipped() {
        val id = addWithMetadata()

        val state = FileSelectionViewModel(engine, id).uiState.value

        assertFalse(state.loading)
        assertFalse(state.notReady)
        assertEquals(listOf(0, 1, 2), state.rows.map { it.index })
        assertEquals(listOf("Movie/Movie.mkv", "Movie/Sample.mkv", "Movie/Movie.en.srt"), state.rows.map { it.path })
        assertEquals("2,0 GB", state.rows[0].sizeText)
        // FileSelectionPolicy: movie + subtitle selected, sample skipped.
        assertEquals(listOf(true, false, true), state.rows.map { it.checked })
        assertTrue(state.canApply)
    }

    @Test
    fun toggleFlipsOnlyThatRow() {
        val id = addWithMetadata()
        val viewModel = FileSelectionViewModel(engine, id)

        viewModel.toggle(1)
        assertEquals(
            listOf(true, true, true),
            viewModel.uiState.value.rows
                .map { it.checked },
        )
        viewModel.toggle(0)
        viewModel.toggle(1)
        assertEquals(
            listOf(false, false, true),
            viewModel.uiState.value.rows
                .map { it.checked },
        )
        assertTrue("toggling is local until Aplicar", engine.recordedCalls.none { it.startsWith("setFilePriorities") })
    }

    @Test
    fun applySendsCheckedAsNormalAndUncheckedAsSkip() {
        val id = addWithMetadata()
        val viewModel = FileSelectionViewModel(engine, id)
        viewModel.toggle(1)
        viewModel.toggle(2)

        viewModel.apply()

        assertEquals(
            listOf("setFilePriorities(${id.value},{0=Normal, 1=Normal, 2=Skip})"),
            engine.recordedCalls.filter { it.startsWith("setFilePriorities") },
        )
        val files = (runBlocking { engine.files(id) } as EngineResult.Ok).value
        assertEquals(listOf(FilePriority.Normal, FilePriority.Normal, FilePriority.Skip), files.map { it.priority })
        val state = viewModel.uiState.value
        assertTrue(state.done)
        assertFalse(state.applying)
        assertNull(state.error)
        assertFalse(state.canApply)
    }

    @Test
    fun applyFailureShowsErrorAndStaysOpen() {
        val id = addWithMetadata()
        val viewModel = FileSelectionViewModel(engine, id)
        runBlocking { engine.remove(id, deleteFiles = false) }

        viewModel.apply()

        val state = viewModel.uiState.value
        assertFalse(state.done)
        assertEquals("Esta descarga ya no existe", state.error)
        assertTrue(state.canApply)
    }

    @Test
    fun notReadyWaitsForMetadataThenLoads() {
        val notReadyEngine = NotReadyUntilMetadata(engine)
        val id = runBlocking { (engine.addMagnet(magnet) as EngineResult.Ok).value }
        val viewModel = FileSelectionViewModel(notReadyEngine, id)

        val waiting = viewModel.uiState.value
        assertTrue(waiting.notReady)
        assertFalse(waiting.loading)
        assertTrue(waiting.rows.isEmpty())
        assertFalse(waiting.canApply)
        viewModel.apply()
        assertTrue(engine.recordedCalls.none { it.startsWith("setFilePriorities") })

        engine.emitMetadata(id, name = "Movie", files = listOf("Movie.mkv" to 1_000_000L))

        val loaded = viewModel.uiState.value
        assertFalse(loaded.notReady)
        assertEquals(listOf("Movie.mkv"), loaded.rows.map { it.path })
    }

    /** The real engine answers `NotReady` before metadata; the fake returns an empty list instead. */
    private class NotReadyUntilMetadata(
        private val fake: FakeTorrentEngine,
    ) : TorrentEngine by fake {
        override suspend fun files(id: TorrentId): EngineResult<List<TorrentFileInfo>> =
            if (fake.torrents.value.any { it.id == id && it.hasMetadata }) {
                fake.files(id)
            } else {
                EngineResult.Failure(EngineError.NotReady)
            }
    }
}
