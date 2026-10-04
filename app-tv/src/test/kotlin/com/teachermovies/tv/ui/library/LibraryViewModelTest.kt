package com.teachermovies.tv.ui.library

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.SubtitleFetch
import com.teachermovies.core.model.SubtitleFetchState
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemorySubtitleFetchRepository
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
 * [InMemoryTorrentRepository] and [InMemorySubtitleFetchRepository] (ADR-0003) behind an unconfined
 * main dispatcher, so every repository write is visible in `uiState` synchronously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val repo = InMemoryTorrentRepository()
    private val subtitleFetches = InMemorySubtitleFetchRepository()

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
        val viewModel = LibraryViewModel(repo, subtitleFetches)

        assertTrue(
            viewModel.uiState.value.items
                .isEmpty(),
        )
    }

    @Test
    fun aCompletedMovieBecomesAFormattedCardWithoutResumeText() {
        val viewModel = LibraryViewModel(repo, subtitleFetches)
        store(id(1), "Movie", DownloadState.Completed, totalBytes = 25_600_000_000L, now = 10)

        val card =
            viewModel.uiState.value.items
                .single()

        assertEquals(LibraryCard(id = id(1), title = "Movie", sizeText = "25,6 GB", resumeText = null), card)
    }

    @Test
    fun aStoredPositionBecomesResumeText() {
        val viewModel = LibraryViewModel(repo, subtitleFetches)
        store(id(1), "Movie", DownloadState.Completed, now = 10)
        runBlocking { repo.updatePlayback(id(1), positionMs = 3_725_000L, audioTrackId = null, subtitleTrackId = null) }

        assertEquals(
            "Continuar en 1 h 2 min",
            viewModel.uiState.value.items
                .single()
                .resumeText,
        )
    }

    @Test
    fun aZeroPositionMeansNoResumeText() {
        val viewModel = LibraryViewModel(repo, subtitleFetches)
        store(id(1), "Movie", DownloadState.Completed, now = 10)
        runBlocking { repo.updatePlayback(id(1), positionMs = 0L, audioTrackId = null, subtitleTrackId = null) }

        assertNull(
            viewModel.uiState.value.items
                .single()
                .resumeText,
        )
    }

    @Test
    fun onlyCompletedMoviesAppearNewestCompletedFirst() {
        val viewModel = LibraryViewModel(repo, subtitleFetches)
        store(id(1), "Older", DownloadState.Completed, now = 10)
        store(id(2), "Still downloading", DownloadState.Downloading, now = 20)
        store(id(3), "Newer", DownloadState.Completed, now = 30)

        assertEquals(
            listOf("Newer", "Older"),
            viewModel.uiState.value.items
                .map { it.title },
        )
    }

    @Test
    fun aDownloadThatCompletesAppearsAndADeletedOneDisappears() {
        val viewModel = LibraryViewModel(repo, subtitleFetches)
        store(id(1), "Movie", DownloadState.Downloading, now = 10)
        assertTrue(
            viewModel.uiState.value.items
                .isEmpty(),
        )

        store(id(1), "Movie", DownloadState.Completed, now = 20)
        assertEquals(
            listOf(id(1)),
            viewModel.uiState.value.items
                .map { it.id },
        )

        runBlocking { repo.delete(id(1)) }
        assertTrue(
            viewModel.uiState.value.items
                .isEmpty(),
        )
    }

    @Test
    fun aMovieWithoutSubtitleRowsHasNoBadges() {
        val viewModel = LibraryViewModel(repo, subtitleFetches)
        store(id(1), "Movie", DownloadState.Completed, now = 10)

        assertNoBadges(viewModel)
    }

    @Test
    fun aPendingSearchHasNoBadges() {
        assertNoBadgesFor(SubtitleFetchState.Pending)
    }

    @Test
    fun aSearchInFlightHasNoBadges() {
        assertNoBadgesFor(SubtitleFetchState.Searching)
    }

    @Test
    fun anEmptySearchHasNoBadges() {
        assertNoBadgesFor(SubtitleFetchState.NotFound)
    }

    @Test
    fun aFailedSearchHasNoBadges() {
        assertNoBadgesFor(SubtitleFetchState.Failed)
    }

    @Test
    fun aDownloadedEnglishSubtitleBadgesEnglishAlone() {
        val viewModel = LibraryViewModel(repo, subtitleFetches)
        store(id(1), "Movie", DownloadState.Completed, now = 10)
        save(id(1), "en", SubtitleFetchState.Downloaded, now = 20)

        assertEquals(
            listOf("EN"),
            viewModel.uiState.value.items
                .single()
                .subtitleBadges,
        )
    }

    @Test
    fun aDownloadedSpanishSubtitleBadgesSpanishAlone() {
        val viewModel = LibraryViewModel(repo, subtitleFetches)
        store(id(1), "Movie", DownloadState.Completed, now = 10)
        save(id(1), "es", SubtitleFetchState.Downloaded, now = 20)

        assertEquals(
            listOf("ES"),
            viewModel.uiState.value.items
                .single()
                .subtitleBadges,
        )
    }

    @Test
    fun bothDownloadedSubtitlesBadgeEnglishFirstThenSpanish() {
        val viewModel = LibraryViewModel(repo, subtitleFetches)
        store(id(1), "Movie", DownloadState.Completed, now = 10)
        save(id(1), "es", SubtitleFetchState.Downloaded, now = 20)
        save(id(1), "en", SubtitleFetchState.Downloaded, now = 30)

        assertEquals(
            listOf("EN", "ES"),
            viewModel.uiState.value.items
                .single()
                .subtitleBadges,
        )
    }

    @Test
    fun aSubtitleDownloadedWhileTheScreenIsOpenAddsItsBadge() {
        val viewModel = LibraryViewModel(repo, subtitleFetches)
        store(id(1), "Movie", DownloadState.Completed, now = 10)
        assertNoBadges(viewModel)

        save(id(1), "es", SubtitleFetchState.Downloaded, now = 20)
        assertEquals(
            listOf("ES"),
            viewModel.uiState.value.items
                .single()
                .subtitleBadges,
        )

        save(id(1), "en", SubtitleFetchState.Downloaded, now = 30)
        assertEquals(
            listOf("EN", "ES"),
            viewModel.uiState.value.items
                .single()
                .subtitleBadges,
        )
    }

    @Test
    fun rowsOfMoviesThatAreNotInTheLibraryBadgeNothing() {
        val viewModel = LibraryViewModel(repo, subtitleFetches)
        store(id(1), "Movie", DownloadState.Completed, now = 10)
        store(id(2), "Still downloading", DownloadState.Downloading, now = 20)
        save(id(2), "en", SubtitleFetchState.Downloaded, now = 30)
        save(id(3), "es", SubtitleFetchState.Downloaded, now = 40)

        assertNoBadges(viewModel)
    }

    private fun assertNoBadgesFor(state: SubtitleFetchState) {
        val viewModel = LibraryViewModel(repo, subtitleFetches)
        store(id(1), "Movie", DownloadState.Completed, now = 10)
        save(id(1), "en", state, now = 20)
        save(id(1), "es", state, now = 20)

        assertNoBadges(viewModel)
    }

    private fun assertNoBadges(viewModel: LibraryViewModel) {
        val badges =
            viewModel.uiState.value.items
                .single()
                .subtitleBadges
        assertTrue("expected no badges, got $badges", badges.isEmpty())
    }

    /** The movie's [language] row in [state], written the way the model's own transitions write it. */
    private fun save(
        id: TorrentId,
        language: String,
        state: SubtitleFetchState,
        now: Long,
    ) {
        val pending = SubtitleFetch.pending(torrentId = id, language = language, now = now)
        val row =
            when (state) {
                SubtitleFetchState.Pending -> pending
                SubtitleFetchState.Searching -> pending.searchingAt(now)
                SubtitleFetchState.Downloaded -> pending.downloadedAt(now, "/movies/${id.value}/subs/$language.srt")
                SubtitleFetchState.NotFound -> pending.notFoundAt(now)
                SubtitleFetchState.Failed -> pending.failedAt(now, "sin cuota")
            }
        runBlocking { subtitleFetches.save(row) }
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
