package com.teachermovies.torrent.jlib

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.torrent.api.FilePriority
import com.teachermovies.torrent.api.TorrentFileInfo
import com.teachermovies.torrent.policy.RawPhase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusSampleMapperTest {
    private val id = TorrentId("c9e15763f722f23e98a29decdfae341b98d53056")
    private val pending = JlibMappers.addedSnapshot(id, "Movie", hasMetadata = false, totalBytes = 0, savePath = null)

    private val downloading =
        StatusSample(
            phase = RawPhase.Downloading,
            paused = false,
            autoManaged = false,
            hasMetadata = true,
            isFinished = false,
            errorMessage = null,
            progress = 0.25f,
            totalWanted = 4_000,
            totalWantedDone = 1_000,
            downloadRate = 500,
            uploadRate = 50,
            numPeers = 7,
            allTimeUpload = 300,
            allTimeDownload = 1_200,
            savePath = "/movies/x",
        )

    @Test
    fun progressIsAPercentage() {
        assertEquals(25.0, StatusSampleMapper.apply(pending, downloading).progressPercent, 1e-4)
        assertEquals(100.0, StatusSampleMapper.apply(pending, downloading.copy(progress = 1f)).progressPercent, 1e-9)
        assertEquals(0.0, StatusSampleMapper.apply(pending, downloading.copy(progress = 0f)).progressPercent, 1e-9)
    }

    @Test
    fun wantedBytesRatesAndPeersAreCopied() {
        val snapshot = StatusSampleMapper.apply(pending, downloading)

        assertEquals(DownloadState.Downloading, snapshot.state)
        assertEquals(1_000L, snapshot.downloadedBytes)
        assertEquals(4_000L, snapshot.totalBytes)
        assertEquals(500L, snapshot.downloadRateBps)
        assertEquals(50L, snapshot.uploadRateBps)
        assertEquals(7, snapshot.peers)
        assertTrue(snapshot.hasMetadata)
        assertEquals("/movies/x", snapshot.savePath)
        assertNull(snapshot.errorMessage)
    }

    @Test
    fun etaIsRemainingWantedBytesOverTheDownloadRate() {
        assertEquals(6L, StatusSampleMapper.apply(pending, downloading).etaSeconds)
        assertNull(StatusSampleMapper.apply(pending, downloading.copy(downloadRate = 0)).etaSeconds)
    }

    @Test
    fun etaIsUnknownBeforeMetadata() {
        val fetching = downloading.copy(phase = RawPhase.DownloadingMetadata, hasMetadata = false, totalWanted = 0, totalWantedDone = 0)

        val snapshot = StatusSampleMapper.apply(pending, fetching)

        assertEquals(DownloadState.FetchingMetadata, snapshot.state)
        assertNull(snapshot.etaSeconds)
        assertNull(snapshot.savePath)
    }

    @Test
    fun ratioIsAllTimeUploadOverAllTimeDownload() {
        assertEquals(0.25, StatusSampleMapper.apply(pending, downloading).ratio, 1e-9)
    }

    @Test
    fun ratioDividesByOneWhenNothingWasDownloaded() {
        assertEquals(0.0, StatusSampleMapper.ratio(0, 0), 1e-9)
        assertEquals(300.0, StatusSampleMapper.ratio(300, 0), 1e-9)
    }

    @Test
    fun anErrorMessageMakesTheTorrentErrored() {
        val snapshot = StatusSampleMapper.apply(pending, downloading.copy(errorMessage = "disk full"))

        assertEquals(DownloadState.Error, snapshot.state)
        assertEquals("disk full", snapshot.errorMessage)
    }

    @Test
    fun pausedIsPausedUnlessAutoManaged() {
        assertEquals(DownloadState.Paused, StatusSampleMapper.apply(pending, downloading.copy(paused = true)).state)
        assertEquals(
            DownloadState.Queued,
            StatusSampleMapper.apply(pending, downloading.copy(paused = true, autoManaged = true)).state,
        )
    }

    @Test
    fun finishedIsCompleted() {
        assertEquals(DownloadState.Completed, StatusSampleMapper.apply(pending, downloading.copy(phase = RawPhase.Seeding)).state)
    }

    @Test
    fun theMainFileIndexSurvivesATick() {
        val selected = pending.copy(mainFileIndex = 2)

        assertEquals(2, StatusSampleMapper.apply(selected, downloading).mainFileIndex)
    }

    @Test
    fun skipNormalHighMapToLibtorrentZeroFourSeven() {
        assertEquals(0, StatusSampleMapper.libPriorityOf(FilePriority.Skip))
        assertEquals(4, StatusSampleMapper.libPriorityOf(FilePriority.Normal))
        assertEquals(7, StatusSampleMapper.libPriorityOf(FilePriority.High))
    }

    @Test
    fun libtorrentPrioritiesMapBack() {
        assertEquals(FilePriority.Skip, StatusSampleMapper.filePriorityOf(0))
        assertEquals(FilePriority.Normal, StatusSampleMapper.filePriorityOf(1))
        assertEquals(FilePriority.Normal, StatusSampleMapper.filePriorityOf(4))
        assertEquals(FilePriority.High, StatusSampleMapper.filePriorityOf(6))
        assertEquals(FilePriority.High, StatusSampleMapper.filePriorityOf(7))
        for (priority in FilePriority.entries) {
            assertEquals(priority, StatusSampleMapper.filePriorityOf(StatusSampleMapper.libPriorityOf(priority)))
        }
    }

    @Test
    fun autoSelectionSkipsExtrasAndKeepsTheMovieAndSubtitles() {
        val files =
            listOf(
                file(0, "Movie/Sample/sample.mkv", 50),
                file(1, "Movie/Movie.mkv", 1_000),
                file(2, "Movie/Movie.en.srt", 5),
                file(3, "Movie/cover.jpg", 1),
            )

        val selection = StatusSampleMapper.autoSelection(files)

        assertEquals(1, selection.mainFileIndex)
        assertArrayEquals(intArrayOf(0, 4, 4, 0), selection.libPriorities)
    }

    @Test
    fun autoSelectionWithoutAVideoDownloadsEverything() {
        val selection = StatusSampleMapper.autoSelection(listOf(file(0, "a.txt", 1), file(1, "b.pdf", 2)))

        assertNull(selection.mainFileIndex)
        assertArrayEquals(intArrayOf(4, 4), selection.libPriorities)
    }

    @Test
    fun fileInfosCombinePathsSizesPrioritiesAndProgress() {
        val files =
            StatusSampleMapper.fileInfos(
                paths = listOf("m/Movie.mkv", "m/sample.mkv"),
                sizes = listOf(1_000L, 50L),
                libPriorities = listOf(7, 0),
                progress = listOf(400L, 0L),
            )

        assertEquals(
            listOf(
                TorrentFileInfo(index = 0, path = "m/Movie.mkv", sizeBytes = 1_000, priority = FilePriority.High, downloadedBytes = 400),
                TorrentFileInfo(index = 1, path = "m/sample.mkv", sizeBytes = 50, priority = FilePriority.Skip, downloadedBytes = 0),
            ),
            files,
        )
    }

    @Test
    fun fileInfosDefaultMissingPriorityAndProgress() {
        val file = StatusSampleMapper.fileInfos(listOf("a.mkv"), listOf(10L), emptyList(), emptyList()).single()

        assertEquals(FilePriority.Normal, file.priority)
        assertEquals(0L, file.downloadedBytes)
    }

    private fun file(
        index: Int,
        path: String,
        size: Long,
    ) = TorrentFileInfo(index = index, path = path, sizeBytes = size, priority = FilePriority.Normal, downloadedBytes = 0)
}
