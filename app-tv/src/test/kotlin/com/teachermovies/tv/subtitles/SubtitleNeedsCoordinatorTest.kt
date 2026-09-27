package com.teachermovies.tv.subtitles

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleNeedsCoordinatorTest {
    private val repo = InMemoryTorrentRepository()
    private val notified = mutableListOf<TorrentId>()

    private fun TestScope.coordinator() =
        SubtitleNeedsCoordinator(
            library = repo,
            notify = { notified += it },
            scope = backgroundScope,
        )

    private fun id(hashChar: Char): TorrentId = TorrentId(hashChar.toString().repeat(40))

    private suspend fun upsert(
        hashChar: Char,
        name: String,
        state: DownloadState,
        at: Long,
    ) {
        repo.upsert(
            Torrent(
                id = id(hashChar),
                name = name,
                state = state,
                progressPercent = if (state == DownloadState.Completed) 100.0 else 10.0,
                downloadedBytes = 1_000L,
                totalBytes = 1_000L,
                savePath = "/vol/Movies/$hashChar",
                mainFileIndex = 0,
                errorMessage = null,
            ),
            "/vol/Movies/$hashChar/$name.mkv",
            at,
        )
    }

    @Test
    fun theLibraryAlreadyThereWhenItStartsIsOnlyRecorded() =
        runTest(UnconfinedTestDispatcher()) {
            upsert('a', "Heat", DownloadState.Completed, 1_000L)
            coordinator().start()

            assertEquals(emptyList<TorrentId>(), notified)

            upsert('b', "Ronin", DownloadState.Completed, 2_000L)
            assertEquals(listOf(id('b')), notified)
        }

    @Test
    fun aMovieIsNotifiedOnceHoweverOftenTheLibraryEmits() =
        runTest(UnconfinedTestDispatcher()) {
            coordinator().start()
            upsert('a', "Heat", DownloadState.Completed, 1_000L)
            repo.updatePlayback(id('a'), positionMs = 60_000L, audioTrackId = null, subtitleTrackId = null)

            assertEquals(listOf(id('a')), notified)
        }

    @Test
    fun aMovieStillDownloadingIsNotifiedWhenItCompletes() =
        runTest(UnconfinedTestDispatcher()) {
            coordinator().start()
            upsert('a', "Heat", DownloadState.Downloading, 1_000L)
            assertEquals(emptyList<TorrentId>(), notified)

            upsert('a', "Heat", DownloadState.Completed, 2_000L)
            assertEquals(listOf(id('a')), notified)
        }

    @Test
    fun aMovieThatLeavesTheLibraryAndCompletesAgainIsNotifiedAgain() =
        runTest(UnconfinedTestDispatcher()) {
            coordinator().start()
            upsert('a', "Heat", DownloadState.Completed, 1_000L)
            assertEquals(listOf(id('a')), notified)

            repo.delete(id('a'))
            assertEquals(listOf(id('a')), notified)
            upsert('a', "Heat", DownloadState.Completed, 2_000L)
            assertEquals(listOf(id('a'), id('a')), notified)
        }
}
