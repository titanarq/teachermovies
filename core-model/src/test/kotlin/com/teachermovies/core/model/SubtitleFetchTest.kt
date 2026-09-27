package com.teachermovies.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry rules of ADR-0005 §5 and the moves the bridge (#282) makes on a row, restated here so the
 * test owns the expectation: [SubtitleFetch.isDue] is the only place a fetch loop learns whether it
 * may search, and every move changes exactly the fields it is documented to change.
 */
class SubtitleFetchTest {
    private val id = TorrentId("0123456789abcdef0123456789abcdef01234567")
    private val path = "/storage/Movies/a/subs/movie.en.srt"
    private val reason = "the laptop was offline"

    /** What a movie starts with: pending, no attempts, no file, no clocks running. */
    private val pending = SubtitleFetch.pending(id, "en", movieHash = "moviehash", now = 1_000L)

    @Test
    fun aNotFoundSearchIsRetriedAfterSevenDays() {
        assertEquals(7L * 24 * 60 * 60 * 1000, SubtitleFetch.NOT_FOUND_RETRY_MS)
    }

    @Test
    fun aFreshRowIsPendingWithNoAttemptsNoFileAndNoClocks() {
        val row = SubtitleFetch.pending(id, "es", movieHash = "moviehash", now = 5_000L)

        assertEquals(id, row.torrentId)
        assertEquals("es", row.language)
        assertEquals(SubtitleFetchState.Pending, row.state)
        assertEquals("moviehash", row.movieHash)
        assertNull(row.localPath)
        assertNull(row.variantLabel)
        assertEquals(0, row.attempts)
        assertNull(row.lastAttemptEpochMs)
        assertNull(row.nextRetryEpochMs)
        assertNull(row.errorMessage)
        assertEquals(5_000L, row.updatedAtEpochMs)
        assertTrue(row.isDue(5_000L))
    }

    @Test
    fun aFreshRowNeedsNeitherAMovieHashNorATimestamp() {
        val row = SubtitleFetch.pending(id, "en")

        assertNull(row.movieHash)
        assertEquals(0L, row.updatedAtEpochMs)
    }

    @Test
    fun aPendingRowIsDueWhateverTheClockSays() {
        assertTrue(pending.isDue(0L))
        assertTrue(pending.isDue(1_000L))
        assertTrue(pending.isDue(Long.MAX_VALUE))
    }

    @Test
    fun aFailedRowIsDueAgainOnTheNextPass() {
        val failed = pending.failedAt(2_000L, reason)

        assertTrue(failed.isDue(2_000L))
        assertTrue(failed.isDue(2_001L))
        assertTrue(failed.isDue(Long.MAX_VALUE))
    }

    @Test
    fun aSearchInFlightIsNotDueBecauseTheNewestBridgeStreamOwnsIt() {
        val searching = pending.searchingAt(2_000L)

        assertFalse(searching.isDue(2_000L))
        assertFalse(searching.isDue(2_000L + SubtitleFetch.NOT_FOUND_RETRY_MS * 100))
    }

    @Test
    fun aRowWithAFileOnDiskIsNeverDueAgain() {
        val downloaded = pending.downloadedAt(2_000L, path)

        assertFalse(downloaded.isDue(2_000L))
        assertFalse(downloaded.isDue(Long.MAX_VALUE))
    }

    @Test
    fun aNotFoundRowBecomesDueExactlyWhenItsRetryClockRunsOut() {
        val notFound = pending.notFoundAt(1_000L)
        val retryAt = 1_000L + SubtitleFetch.NOT_FOUND_RETRY_MS

        assertEquals(retryAt, notFound.nextRetryEpochMs)
        assertFalse(notFound.isDue(1_000L))
        assertFalse(notFound.isDue(retryAt - 1L))
        assertTrue(notFound.isDue(retryAt))
        assertTrue(notFound.isDue(retryAt + 1L))
    }

    @Test
    fun aNotFoundRowWithoutARetryClockIsDue() {
        val notFound = pending.notFoundAt(1_000L).copy(nextRetryEpochMs = null)

        assertTrue(notFound.isDue(0L))
    }

    @Test
    fun searchingAtCountsTheAttemptAndClearsThePreviousFailure() {
        val failed = pending.failedAt(1_500L, reason)

        val searching = failed.searchingAt(2_000L)

        assertEquals(SubtitleFetchState.Searching, searching.state)
        assertEquals(1, searching.attempts)
        assertEquals(2_000L, searching.lastAttemptEpochMs)
        assertEquals(2_000L, searching.updatedAtEpochMs)
        assertNull(searching.nextRetryEpochMs)
        assertNull(searching.errorMessage)
    }

    @Test
    fun searchingAtCountsEveryAttemptOfTheSameRow() {
        val third = pending.searchingAt(2_000L).searchingAt(3_000L).searchingAt(4_000L)

        assertEquals(3, third.attempts)
        assertEquals(4_000L, third.lastAttemptEpochMs)
    }

    @Test
    fun searchingAtKeepsTheMovieTheLanguageAndTheFileItAlreadyHad() {
        val downloaded = pending.downloadedAt(1_500L, path, variant = "latino")

        val searching = downloaded.searchingAt(2_000L)

        assertEquals(id, searching.torrentId)
        assertEquals("en", searching.language)
        assertEquals("moviehash", searching.movieHash)
        assertEquals(path, searching.localPath)
        assertEquals("latino", searching.variantLabel)
    }

    @Test
    fun downloadedAtStoresThePathAndTheVariantLabel() {
        val searched = pending.failedAt(1_500L, reason).searchingAt(2_000L)

        val downloaded = searched.downloadedAt(3_000L, path, variant = "latino")

        assertEquals(SubtitleFetchState.Downloaded, downloaded.state)
        assertEquals(path, downloaded.localPath)
        assertEquals("latino", downloaded.variantLabel)
        assertEquals(3_000L, downloaded.updatedAtEpochMs)
        assertNull(downloaded.nextRetryEpochMs)
        assertNull(downloaded.errorMessage)
        // The search that found the file is history: its attempt count and timestamp stay.
        assertEquals(1, downloaded.attempts)
        assertEquals(2_000L, downloaded.lastAttemptEpochMs)
    }

    @Test
    fun downloadedAtLeavesTheVariantLabelNullForThePreferredCastilianFile() {
        val downloaded = pending.downloadedAt(2_000L, "/storage/Movies/a/subs/movie.es.srt")

        assertEquals("/storage/Movies/a/subs/movie.es.srt", downloaded.localPath)
        assertNull(downloaded.variantLabel)
    }

    @Test
    fun notFoundAtStampsTheRetryClockAndClearsTheError() {
        val searched = pending.failedAt(1_500L, reason).searchingAt(2_000L)

        val notFound = searched.notFoundAt(3_000L)

        assertEquals(SubtitleFetchState.NotFound, notFound.state)
        assertEquals(3_000L + SubtitleFetch.NOT_FOUND_RETRY_MS, notFound.nextRetryEpochMs)
        assertEquals(3_000L, notFound.updatedAtEpochMs)
        assertNull(notFound.errorMessage)
        // An empty search is not a new attempt, and it put no file on disk.
        assertEquals(1, notFound.attempts)
        assertEquals(2_000L, notFound.lastAttemptEpochMs)
        assertNull(notFound.localPath)
    }

    @Test
    fun failedAtStoresTheReasonAndLeavesTheRowDueImmediately() {
        val searched = pending.searchingAt(2_000L)

        val failed = searched.failedAt(3_000L, "the opensubtitles quota is spent")

        assertEquals(SubtitleFetchState.Failed, failed.state)
        assertEquals("the opensubtitles quota is spent", failed.errorMessage)
        assertEquals(3_000L, failed.updatedAtEpochMs)
        assertNull(failed.nextRetryEpochMs)
        assertEquals(1, failed.attempts)
        assertEquals(2_000L, failed.lastAttemptEpochMs)
        assertTrue(failed.isDue(3_000L))
    }

    @Test
    fun failedAtStoresNoReasonWhenTheCallerHasNone() {
        assertNull(pending.failedAt(2_000L).errorMessage)
    }

    @Test
    fun noMoveChangesTheMovieOrTheLanguageARowBelongsTo() {
        val moves =
            listOf(
                pending.searchingAt(2_000L),
                pending.downloadedAt(2_000L, path, variant = "latino"),
                pending.notFoundAt(2_000L),
                pending.failedAt(2_000L, reason),
            )

        moves.forEach { row ->
            assertEquals(id, row.torrentId)
            assertEquals("en", row.language)
            assertEquals("moviehash", row.movieHash)
        }
    }
}
