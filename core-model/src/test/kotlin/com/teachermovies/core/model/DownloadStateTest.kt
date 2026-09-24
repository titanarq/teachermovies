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
    /** The lifecycle the issue specifies, restated here so the test owns the expectation. */
    private val allowedTransitions =
        mapOf(
            FetchingMetadata to setOf(Queued, Downloading, Paused, Error),
            Queued to setOf(Downloading, Paused, Error),
            Downloading to setOf(Verifying, Completed, Paused, Queued, Error),
            Paused to setOf(Queued, Downloading, FetchingMetadata, Verifying),
            Verifying to setOf(Downloading, Completed, Error),
            Completed to setOf(Verifying, Paused),
            Error to setOf(Queued, FetchingMetadata, Verifying),
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

        // 7x7 pairs minus the 7 self-transitions and the 24 allowed ones.
        assertEquals(18, forbiddenTransitions)
    }

    @Test
    fun rejectsCompletingAMetadataFetchThatNeverDownloaded() {
        assertFalse(FetchingMetadata.canTransitionTo(Completed))
    }

    @Test
    fun rejectsCompletingATorrentStillInTheQueue() {
        assertFalse(Queued.canTransitionTo(Completed))
    }

    @Test
    fun rejectsFetchingMetadataAgainForATorrentAlreadyDownloading() {
        assertFalse(Downloading.canTransitionTo(FetchingMetadata))
    }

    @Test
    fun rejectsPausingWhileTheFileIsBeingVerified() {
        assertFalse(Verifying.canTransitionTo(Paused))
    }

    @Test
    fun rejectsDownloadingAgainACompletedTorrentWithoutVerifyingIt() {
        assertFalse(Completed.canTransitionTo(Downloading))
    }

    @Test
    fun rejectsFailingAPausedTorrent() {
        assertFalse(Paused.canTransitionTo(Error))
    }

    @Test
    fun rejectsDownloadingStraightFromAnErrorWithoutRequeueing() {
        assertFalse(Error.canTransitionTo(Downloading))
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
