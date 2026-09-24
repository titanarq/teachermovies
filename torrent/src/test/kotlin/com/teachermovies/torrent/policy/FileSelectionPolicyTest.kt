package com.teachermovies.torrent.policy

import com.teachermovies.torrent.api.FilePriority
import com.teachermovies.torrent.api.TorrentFileInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Table-driven: each test builds a small file list, calls [FileSelectionPolicy.select] once, and
 * checks both the resulting [Selection.mainFileIndex] and every file's [FilePriority] against the
 * expectations spelled out in issue #49.
 */
class FileSelectionPolicyTest {
    /** A file at [index] with [path] and [sizeBytes]; priority/downloadedBytes don't affect the policy. */
    private fun file(
        index: Int,
        path: String,
        sizeBytes: Long,
    ) = TorrentFileInfo(index = index, path = path, sizeBytes = sizeBytes, priority = FilePriority.Normal, downloadedBytes = 0)

    private fun assertSelection(
        files: List<TorrentFileInfo>,
        expectedMainFileIndex: Int?,
        vararg expectedPriorities: Pair<Int, FilePriority>,
    ) {
        val selection = FileSelectionPolicy.select(files)
        assertEquals(expectedMainFileIndex, selection.mainFileIndex)
        assertEquals(expectedPriorities.toMap(), selection.priorities)
    }

    @Test
    fun singleMkvIsTheMainFile() {
        val movie = file(0, "Movie.2020.mkv", sizeBytes = 4_000_000_000)
        assertSelection(listOf(movie), expectedMainFileIndex = 0, 0 to FilePriority.Normal)
    }

    @Test
    fun movieBeatsASampleFileInItsOwnFolder() {
        val movie = file(0, "Movie.2020.mkv", sizeBytes = 4_000_000_000)
        val sample = file(1, "Sample/sample.mkv", sizeBytes = 50_000_000)
        assertSelection(
            listOf(movie, sample),
            expectedMainFileIndex = 0,
            0 to FilePriority.Normal,
            1 to FilePriority.Skip,
        )
    }

    @Test
    fun subtitleIsKeptButNfoAndArtworkAreSkipped() {
        val movie = file(0, "Movie.2020.mkv", sizeBytes = 4_000_000_000)
        val srt = file(1, "Movie.2020.srt", sizeBytes = 50_000)
        val nfo = file(2, "Movie.2020.nfo", sizeBytes = 2_000)
        val jpg = file(3, "poster.jpg", sizeBytes = 500_000)
        assertSelection(
            listOf(movie, srt, nfo, jpg),
            expectedMainFileIndex = 0,
            0 to FilePriority.Normal,
            1 to FilePriority.Normal,
            2 to FilePriority.Skip,
            3 to FilePriority.Skip,
        )
    }

    @Test
    fun nestedExtrasFolderIsSkippedEvenWithAnInnocuousFileName() {
        val movie = file(0, "Movie/Movie.2020.mkv", sizeBytes = 4_000_000_000)
        val extra = file(1, "Movie/Extras/x.mkv", sizeBytes = 300_000_000)
        assertSelection(
            listOf(movie, extra),
            expectedMainFileIndex = 0,
            0 to FilePriority.Normal,
            1 to FilePriority.Skip,
        )
    }

    @Test
    fun tvLikeTwoLargeVideosPicksTheLargestAsMainAndSkipsTheOther() {
        val episode1 = file(0, "Show.S01E01.mkv", sizeBytes = 1_500_000_000)
        val episode2 = file(1, "Show.S01E02.mkv", sizeBytes = 1_600_000_000)
        assertSelection(
            listOf(episode1, episode2),
            expectedMainFileIndex = 1,
            0 to FilePriority.Skip,
            1 to FilePriority.Normal,
        )
    }

    @Test
    fun onlySubtitlesMeansNoMainFileAndEverythingNormal() {
        val en = file(0, "Movie.en.srt", sizeBytes = 40_000)
        val es = file(1, "Movie.es.ass", sizeBytes = 42_000)
        assertSelection(
            listOf(en, es),
            expectedMainFileIndex = null,
            0 to FilePriority.Normal,
            1 to FilePriority.Normal,
        )
    }

    @Test
    fun extensionMatchingIsCaseInsensitive() {
        val movie = file(0, "MOVIE.2020.MKV", sizeBytes = 4_000_000_000)
        val srt = file(1, "MOVIE.2020.SRT", sizeBytes = 50_000)
        val sample = file(2, "SAMPLE/CLIP.MP4", sizeBytes = 40_000_000)
        assertSelection(
            listOf(movie, srt, sample),
            expectedMainFileIndex = 0,
            0 to FilePriority.Normal,
            1 to FilePriority.Normal,
            2 to FilePriority.Skip,
        )
    }

    @Test
    fun noFilesMeansAnEmptySelection() {
        assertSelection(emptyList(), expectedMainFileIndex = null)
    }

    @Test
    fun whenEveryVideoLooksLikeAnExtraTheLargestOneStillWins() {
        val trailer = file(0, "Movie.Trailer.mkv", sizeBytes = 200_000_000)
        val sample = file(1, "Movie.Sample.mkv", sizeBytes = 60_000_000)
        assertSelection(
            listOf(trailer, sample),
            expectedMainFileIndex = 0,
            0 to FilePriority.Normal,
            1 to FilePriority.Skip,
        )
    }

    @Test
    fun mainFileIsAlwaysNormalEvenIfItsPathLooksLikeBonusMaterial() {
        // Only video in the torrent: it is the main file regardless of its name.
        val onlyVideo = file(0, "Behind.The.Scenes.mkv", sizeBytes = 4_000_000_000)
        assertSelection(listOf(onlyVideo), expectedMainFileIndex = 0, 0 to FilePriority.Normal)
    }

    @Test
    fun unrecognisedExtensionsAreSkipped() {
        val movie = file(0, "Movie.2020.mkv", sizeBytes = 4_000_000_000)
        val exe = file(1, "Setup.exe", sizeBytes = 10_000_000)
        val url = file(2, "site.url", sizeBytes = 200)
        val txt = file(3, "readme.txt", sizeBytes = 500)
        assertSelection(
            listOf(movie, exe, url, txt),
            expectedMainFileIndex = 0,
            0 to FilePriority.Normal,
            1 to FilePriority.Skip,
            2 to FilePriority.Skip,
            3 to FilePriority.Skip,
        )
    }
}
