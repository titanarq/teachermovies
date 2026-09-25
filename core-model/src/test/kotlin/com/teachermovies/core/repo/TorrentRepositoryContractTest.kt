package com.teachermovies.core.repo

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Scenarios every [TorrentRepository] implementation must satisfy, run against both
 * `RoomTorrentRepository` (Robolectric, real Room) and `InMemoryTorrentRepository` (plain JVM).
 */
abstract class TorrentRepositoryContractTest {
    /** Builds the repository under test. Called once per `@Test`, from [setUp]. */
    protected abstract fun createRepository(): TorrentRepository

    /** Released after each test; a no-op unless the implementation holds a resource (e.g. Room). */
    protected open fun releaseRepository() {}

    private lateinit var repository: TorrentRepository

    @Before
    fun setUp() {
        repository = createRepository()
    }

    @After
    fun tearDown() {
        releaseRepository()
    }

    private fun id(seed: Char): TorrentId = TorrentId(seed.toString().repeat(40))

    private fun torrent(
        seed: Char,
        state: DownloadState = DownloadState.Downloading,
        name: String = "Movie $seed",
        progressPercent: Double = 50.0,
        downloadedBytes: Long = 500L,
        totalBytes: Long = 1_000L,
        savePath: String? = "/storage/Movies/$seed",
        mainFileIndex: Int? = 0,
        errorMessage: String? = null,
    ) = Torrent(
        id = id(seed),
        name = name,
        state = state,
        progressPercent = progressPercent,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        savePath = savePath,
        mainFileIndex = mainFileIndex,
        errorMessage = errorMessage,
    )

    @Test
    fun getUnknownIdReturnsNull() =
        runTest {
            assertNull(repository.get(id('a')))
            assertNull(repository.getLibraryItem(id('a')))
        }

    @Test
    fun upsertThenGetReturnsTheDomainTorrent() =
        runTest {
            val t = torrent('a', state = DownloadState.Downloading)
            repository.upsert(t, mainFilePath = null, now = 1_000L)

            assertEquals(t, repository.get(id('a')))
        }

    @Test
    fun observeDownloadsExcludesCompletedAndObserveLibraryIncludesOnlyCompletedWithMainFile() =
        runTest {
            repository.upsert(torrent('a', state = DownloadState.Downloading), mainFilePath = null, now = 1L)
            repository.upsert(torrent('b', state = DownloadState.Completed), mainFilePath = "b.mkv", now = 2L)
            // Completed but no main file yet: still not part of the library.
            repository.upsert(torrent('c', state = DownloadState.Completed), mainFilePath = null, now = 3L)

            assertEquals(listOf(id('a')), repository.observeDownloads().first().map { it.id })
            assertEquals(listOf(id('b')), repository.observeLibrary().first().map { it.id })
        }

    @Test
    fun observeLibraryIsOrderedNewestCompletedFirst() =
        runTest {
            repository.upsert(torrent('a', state = DownloadState.Completed), mainFilePath = "a.mkv", now = 10L)
            repository.upsert(torrent('b', state = DownloadState.Completed), mainFilePath = "b.mkv", now = 30L)
            repository.upsert(torrent('c', state = DownloadState.Completed), mainFilePath = "c.mkv", now = 20L)

            assertEquals(
                listOf(id('b'), id('c'), id('a')),
                repository.observeLibrary().first().map { it.id },
            )
        }

    @Test
    fun getLibraryItemMirrorsGetForACompletedTorrentWithMainFile() =
        runTest {
            repository.upsert(torrent('a', state = DownloadState.Completed), mainFilePath = "a.mkv", now = 5L)

            val item = repository.getLibraryItem(id('a'))

            assertEquals(id('a'), item?.id)
            assertEquals("a.mkv", item?.mainFilePath)
            assertEquals(5L, item?.completedAtEpochMs)
        }

    @Test
    fun getLibraryItemIsNullForANonCompletedTorrent() =
        runTest {
            repository.upsert(torrent('a', state = DownloadState.Downloading), mainFilePath = null, now = 1L)

            assertNull(repository.getLibraryItem(id('a')))
        }

    @Test
    fun aCompletedTorrentStaysInTheLibraryWhilePausedRecheckingOrSeeding() =
        runTest {
            repository.upsert(torrent('a', state = DownloadState.Downloading), mainFilePath = "a.mkv", now = 1L)
            repository.upsert(torrent('a', state = DownloadState.Completed), mainFilePath = "a.mkv", now = 2L)

            for (state in listOf(DownloadState.Paused, DownloadState.Verifying, DownloadState.Downloading)) {
                repository.upsert(torrent('a', state = state), mainFilePath = "a.mkv", now = 3L)

                assertEquals(state.name, listOf(id('a')), repository.observeLibrary().first().map { it.id })
                val item = repository.getLibraryItem(id('a'))
                assertEquals(state.name, "a.mkv", item?.mainFilePath)
                assertEquals(state.name, 2L, item?.completedAtEpochMs)
            }
        }

    @Test
    fun aNeverCompletedPausedTorrentIsNotInTheLibrary() =
        runTest {
            repository.upsert(torrent('a', state = DownloadState.Paused), mainFilePath = "a.mkv", now = 1L)

            assertEquals(emptyList<TorrentId>(), repository.observeLibrary().first().map { it.id })
            assertNull(repository.getLibraryItem(id('a')))
        }

    @Test
    fun deletingACompletedPausedTorrentRemovesItFromTheLibrary() =
        runTest {
            repository.upsert(torrent('a', state = DownloadState.Completed), mainFilePath = "a.mkv", now = 1L)
            repository.upsert(torrent('a', state = DownloadState.Paused), mainFilePath = "a.mkv", now = 2L)

            repository.delete(id('a'))

            assertEquals(emptyList<TorrentId>(), repository.observeLibrary().first().map { it.id })
            assertNull(repository.getLibraryItem(id('a')))
        }

    @Test
    fun getPlaybackItemIncludesADownloadInProgressWithMainFile() =
        runTest {
            repository.upsert(torrent('a', state = DownloadState.Downloading), mainFilePath = "a.mkv", now = 1L)
            repository.updatePlayback(id('a'), 42_000L, "1", "2")

            val item = repository.getPlaybackItem(id('a'))

            assertEquals("a.mkv", item?.mainFilePath)
            assertEquals(42_000L, item?.lastPositionMs)
            assertEquals("1", item?.audioTrackId)
            assertEquals("2", item?.subtitleTrackId)
            assertEquals(0L, item?.completedAtEpochMs)
            assertNull(repository.getLibraryItem(id('a')))
        }

    @Test
    fun getPlaybackItemIsNullWithoutAMainFileOrForAnUnknownId() =
        runTest {
            repository.upsert(torrent('a', state = DownloadState.Downloading), mainFilePath = null, now = 1L)

            assertNull(repository.getPlaybackItem(id('a')))
            assertNull(repository.getPlaybackItem(id('b')))
        }

    @Test
    fun completedAtEpochMsIsSetOnlyTheFirstTimeStateBecomesCompleted() =
        runTest {
            repository.upsert(torrent('a', state = DownloadState.Downloading), mainFilePath = null, now = 1L)
            repository.upsert(torrent('a', state = DownloadState.Completed), mainFilePath = "a.mkv", now = 2L)
            // A later re-verify does not move completedAtEpochMs.
            repository.upsert(torrent('a', state = DownloadState.Verifying), mainFilePath = "a.mkv", now = 3L)
            repository.upsert(torrent('a', state = DownloadState.Completed), mainFilePath = "a.mkv", now = 4L)

            assertEquals(2L, repository.getLibraryItem(id('a'))?.completedAtEpochMs)
        }

    @Test
    fun addedAtIsKeptAcrossUpserts() =
        runTest {
            // `a` is added first (now = 1L); `b` is added later (now = 5L) and so is newer.
            repository.upsert(torrent('a', state = DownloadState.FetchingMetadata), mainFilePath = null, now = 1L)
            repository.upsert(torrent('b', state = DownloadState.Downloading), mainFilePath = null, now = 5L)
            // Re-upserting `a` at a much later `now` must not move its addedAtEpochMs forward, or it
            // would wrongly become newer than `b`.
            repository.upsert(torrent('a', state = DownloadState.Downloading), mainFilePath = null, now = 10L)

            assertEquals(listOf(id('b'), id('a')), repository.observeDownloads().first().map { it.id })
        }

    @Test
    fun updatePlaybackChangesPositionAndTracksButNothingElse() =
        runTest {
            val t = torrent('a', state = DownloadState.Completed)
            repository.upsert(t, mainFilePath = "a.mkv", now = 1L)

            repository.updatePlayback(
                id('a'),
                positionMs = 42_000L,
                audioTrackId = "audio-1",
                subtitleTrackId = "sub-en",
            )

            val item = repository.getLibraryItem(id('a'))
            assertEquals(42_000L, item?.lastPositionMs)
            assertEquals("audio-1", item?.audioTrackId)
            assertEquals("sub-en", item?.subtitleTrackId)
            assertEquals(t, repository.get(id('a')))
        }

    @Test
    fun updatePlaybackOnAnUnknownIdIsANoOp() =
        runTest {
            repository.updatePlayback(id('a'), positionMs = 1L, audioTrackId = null, subtitleTrackId = null)

            assertNull(repository.get(id('a')))
        }

    @Test
    fun deleteRemovesOnlyThatTorrent() =
        runTest {
            repository.upsert(torrent('a'), mainFilePath = null, now = 1L)
            repository.upsert(torrent('b'), mainFilePath = null, now = 2L)

            repository.delete(id('a'))

            assertNull(repository.get(id('a')))
            assertEquals(torrent('b'), repository.get(id('b')))
        }
}
