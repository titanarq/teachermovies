package com.teachermovies.tv.ui.library

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [InMemoryTorrentRepository] (ADR-0003) behind an unconfined main dispatcher, so every repository
 * write is visible in `uiState` synchronously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val repo = InMemoryTorrentRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun emptyRepositoryMeansNoCards() {
        val viewModel = LibraryViewModel(repo)

        assertTrue(viewModel.uiState.value.items.isEmpty())
    }

    @Test
    fun aCompletedMovieBecomesAFormattedCardWithoutResumeText() {
        val viewModel = LibraryViewModel(repo)
        store(id(1), "Movie", DownloadState.Completed, totalBytes = 25_600_000_000L, now = 10)

        val card = viewModel.uiState.value.items.single()

        assertEquals(LibraryCard(id = id(1), title = "Movie", sizeText = "25,6 GB", resumeText = null), card)
    }

    @Test
    fun aStoredPositionBecomesResumeText() {
        val viewModel = LibraryViewModel(repo)
        store(id(1), "Movie", DownloadState.Completed, now = 10)
        runBlocking { repo.updatePlayback(id(1), positionMs = 3_725_000L, audioTrackId = null, subtitleTrackId = null) }

        assertEquals("Continuar en 1 h 2 min", viewModel.uiState.value.items.single().resumeText)
    }

    @Test
    fun aZeroPositionMeansNoResumeText() {
        val viewModel = LibraryViewModel(repo)
        store(id(1), "Movie", DownloadState.Completed, now = 10)
        runBlocking { repo.updatePlayback(id(1), positionMs = 0L, audioTrackId = null, subtitleTrackId = null) }

        assertNull(viewModel.uiState.value.items.single().resumeText)
    }

    @Test
    fun onlyCompletedMoviesAppearNewestCompletedFirst() {
        val viewModel = LibraryViewModel(repo)
        store(id(1), "Older", DownloadState.Completed, now = 10)
        store(id(2), "Still downloading", DownloadState.Downloading, now = 20)
        store(id(3), "Newer", DownloadState.Completed, now = 30)

        assertEquals(listOf("Newer", "Older"), viewModel.uiState.value.items.map { it.title })
    }

    @Test
    fun aDownloadThatCompletesAppearsAndADeletedOneDisappears() {
        val viewModel = LibraryViewModel(repo)
        store(id(1), "Movie", DownloadState.Downloading, now = 10)
        assertTrue(viewModel.uiState.value.items.isEmpty())

        store(id(1), "Movie", DownloadState.Completed, now = 20)
        assertEquals(listOf(id(1)), viewModel.uiState.value.items.map { it.id })

        runBlocking { repo.delete(id(1)) }
        assertTrue(viewModel.uiState.value.items.isEmpty())
    }

    private fun id(n: Int) = TorrentId(n.toString().padStart(40, '0'))

    private fun store(
        id: TorrentId,
        name: String,
        state: DownloadState,
        totalBytes: Long = 1_000_000_000L,
        now: Long,
    ) = runBlocking {
        repo.upsert(
            Torrent(
                id = id,
                name = name,
                state = state,
                progressPercent = if (state == DownloadState.Completed) 100.0 else 50.0,
                downloadedBytes = totalBytes,
                totalBytes = totalBytes,
                savePath = "/movies/${id.value}",
                mainFileIndex = 0,
                errorMessage = null,
            ),
            mainFilePath = "/movies/${id.value}/$name.mkv",
            now = now,
        )
    }
}
