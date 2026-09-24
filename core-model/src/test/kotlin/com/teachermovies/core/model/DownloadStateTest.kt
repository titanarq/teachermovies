package com.teachermovies.core.model

import com.teachermovies.core.model.DownloadState.Completed
import com.teachermovies.core.model.DownloadState.Downloading
import com.teachermovies.core.model.DownloadState.Error
import com.teachermovies.core.model.DownloadState.FetchingMetadata
import com.teachermovies.core.model.DownloadState.Paused
import com.teachermovies.core.model.DownloadState.Queued
import com.teachermovies.core.model.DownloadState.Verifying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateTest {
    /**
     * The lifecycle the issue specifies, restated here so the test owns the expectation: every move
     * is allowed except going back to [FetchingMetadata] once the metadata is known.
     */
    private val allowedTransitions =
        mapOf(
            FetchingMetadata to setOf(Queued, Downloading, Paused, Verifying, Completed, Error),
            Queued to setOf(FetchingMetadata, Downloading, Paused, Verifying, Completed, Error),
            Downloading to setOf(Queued, Paused, Verifying, Completed, Error),
            Paused to setOf(FetchingMetadata, Queued, Downloading, Verifying, Completed, Error),
            Verifying to setOf(Queued, Downloading, Paused, Completed, Error),
            Completed to setOf(Queued, Downloading, Paused, Verifying, Error),
            Error to setOf(FetchingMetadata, Queued, Downloading, Paused, Verifying, Completed),
        )

    @Test
    fun declaresATransitionTableEntryForEveryState() {
        assertEquals(DownloadState.entries.toSet(), allowedTransitions.keys)
    }

    @Test
    fun allowsEveryTransitionOfTheTable() {
        allowedTransitions.forEach { (from, targets) ->
            targets.forEach { to -> assertTrue("$from -> $to", from.canTransitionTo(to)) }
        }
    }

    @Test
    fun allowsEveryStateToStayWhereItIs() {
        DownloadState.entries.forEach { state ->
            assertTrue("$state -> $state", state.canTransitionTo(state))
        }
    }

    @Test
    fun rejectsEveryTransitionOutsideTheTable() {
        var forbiddenTransitions = 0
        DownloadState.entries.forEach { from ->
            DownloadState.entries.forEach { to ->
                val expected = from == to || to in allowedTransitions.getValue(from)
                assertEquals("$from -> $to", expected, from.canTransitionTo(to))
                if (!expected) forbiddenTransitions++
            }
        }

        // 7x7 pairs minus the 7 self-transitions and the 39 allowed ones.
        assertEquals(3, forbiddenTransitions)
    }

    @Test
    fun rejectsFetchingMetadataAgainForATorrentAlreadyDownloading() {
        assertFalse(Downloading.canTransitionTo(FetchingMetadata))
    }

    @Test
    fun rejectsFetchingMetadataAgainForATorrentBeingVerified() {
        assertFalse(Verifying.canTransitionTo(FetchingMetadata))
    }

    @Test
    fun rejectsFetchingMetadataAgainForACompletedTorrent() {
        assertFalse(Completed.canTransitionTo(FetchingMetadata))
    }

    @Test
    fun allowsCompletingAMetadataFetchWhenAFinishedTorrentIsReAdded() {
        assertTrue(FetchingMetadata.canTransitionTo(Completed))
    }

    @Test
    fun allowsCompletingATorrentStillInTheQueue() {
        assertTrue(Queued.canTransitionTo(Completed))
    }

    @Test
    fun allowsVerifyingAQueuedTorrentThatStartsCheckingFiles() {
        assertTrue(Queued.canTransitionTo(Verifying))
    }

    @Test
    fun allowsDownloadingAgainACompletedTorrentWhenASkippedFileIsUnSkipped() {
        assertTrue(Completed.canTransitionTo(Downloading))
    }

    @Test
    fun allowsErrorPausedAndQueuedFromEveryState() {
        DownloadState.entries.forEach { from ->
            listOf(Error, Paused, Queued).forEach { to ->
                assertTrue("$from -> $to", from.canTransitionTo(to))
            }
        }
    }

    @Test
    fun allowsThePauseAndResumeRoundTrip() {
        assertTrue(Downloading.canTransitionTo(Paused))
        assertTrue(Paused.canTransitionTo(Queued))
        assertTrue(Queued.canTransitionTo(Downloading))
        assertTrue(Paused.canTransitionTo(Downloading))
    }

    @Test
    fun allowsRetryingAnErrorThroughTheQueue() {
        assertTrue(Error.canTransitionTo(Queued))
        assertTrue(Error.canTransitionTo(FetchingMetadata))
        assertTrue(Queued.canTransitionTo(Downloading))
    }
}
