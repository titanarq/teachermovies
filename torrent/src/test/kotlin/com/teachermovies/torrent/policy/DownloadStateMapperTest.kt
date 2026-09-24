package com.teachermovies.torrent.policy

import com.teachermovies.core.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadStateMapperTest {
    /** A raw status with no flag set and a phase that would otherwise map to [DownloadState.Downloading]. */
    private fun baseline(
        phase: RawPhase = RawPhase.Downloading,
        paused: Boolean = false,
        autoManagedQueued: Boolean = false,
        hasMetadata: Boolean = true,
        hasError: Boolean = false,
        isFinished: Boolean = false,
    ) = RawStatus(
        phase = phase,
        paused = paused,
        autoManagedQueued = autoManagedQueued,
        hasMetadata = hasMetadata,
        hasError = hasError,
        isFinished = isFinished,
    )

    // -- one test per rule --------------------------------------------------------------------

    @Test
    fun mapsAnErroredStatusToError() {
        assertEquals(DownloadState.Error, DownloadStateMapper.map(baseline(hasError = true)))
    }

    @Test
    fun mapsAPausedStandaloneStatusToPaused() {
        val raw = baseline(paused = true, autoManagedQueued = false)
        assertEquals(DownloadState.Paused, DownloadStateMapper.map(raw))
    }

    @Test
    fun mapsAPausedAutoManagedQueuedStatusToQueued() {
        val raw = baseline(paused = true, autoManagedQueued = true)
        assertEquals(DownloadState.Queued, DownloadStateMapper.map(raw))
    }

    @Test
    fun mapsMissingMetadataToFetchingMetadata() {
        val raw = baseline(hasMetadata = false, phase = RawPhase.Downloading)
        assertEquals(DownloadState.FetchingMetadata, DownloadStateMapper.map(raw))
    }

    @Test
    fun mapsTheDownloadingMetadataPhaseToFetchingMetadataEvenWithMetadataFlagSet() {
        val raw = baseline(hasMetadata = true, phase = RawPhase.DownloadingMetadata)
        assertEquals(DownloadState.FetchingMetadata, DownloadStateMapper.map(raw))
    }

    @Test
    fun mapsCheckingFilesToVerifying() {
        assertEquals(DownloadState.Verifying, DownloadStateMapper.map(baseline(phase = RawPhase.CheckingFiles)))
    }

    @Test
    fun mapsCheckingResumeDataToVerifying() {
        assertEquals(
            DownloadState.Verifying,
            DownloadStateMapper.map(baseline(phase = RawPhase.CheckingResumeData)),
        )
    }

    @Test
    fun mapsFinishedToCompleted() {
        assertEquals(DownloadState.Completed, DownloadStateMapper.map(baseline(phase = RawPhase.Finished)))
    }

    @Test
    fun mapsSeedingToCompleted() {
        assertEquals(DownloadState.Completed, DownloadStateMapper.map(baseline(phase = RawPhase.Seeding)))
    }

    @Test
    fun mapsIsFinishedFlagToCompletedRegardlessOfPhase() {
        val raw = baseline(phase = RawPhase.Downloading, isFinished = true)
        assertEquals(DownloadState.Completed, DownloadStateMapper.map(raw))
    }

    @Test
    fun mapsDownloadingToDownloading() {
        assertEquals(DownloadState.Downloading, DownloadStateMapper.map(baseline(phase = RawPhase.Downloading)))
    }

    @Test
    fun mapsUnknownToQueued() {
        assertEquals(DownloadState.Queued, DownloadStateMapper.map(baseline(phase = RawPhase.Unknown)))
    }

    // -- rule order -----------------------------------------------------------------------------

    @Test
    fun errorBeatsPaused() {
        val raw = baseline(hasError = true, paused = true, autoManagedQueued = false)
        assertEquals(DownloadState.Error, DownloadStateMapper.map(raw))
    }

    @Test
    fun errorBeatsPausedAutoManagedQueued() {
        val raw = baseline(hasError = true, paused = true, autoManagedQueued = true)
        assertEquals(DownloadState.Error, DownloadStateMapper.map(raw))
    }

    @Test
    fun pausedBeatsMissingMetadata() {
        val raw = baseline(paused = true, autoManagedQueued = false, hasMetadata = false)
        assertEquals(DownloadState.Paused, DownloadStateMapper.map(raw))
    }

    @Test
    fun pausedAutoManagedQueuedBeatsMissingMetadata() {
        val raw = baseline(paused = true, autoManagedQueued = true, hasMetadata = false)
        assertEquals(DownloadState.Queued, DownloadStateMapper.map(raw))
    }

    @Test
    fun missingMetadataBeatsVerifyingPhases() {
        val raw = baseline(hasMetadata = false, phase = RawPhase.CheckingFiles)
        assertEquals(DownloadState.FetchingMetadata, DownloadStateMapper.map(raw))
    }

    @Test
    fun verifyingBeatsCompleted() {
        // isFinished is set, but the phase is still checking resume data: verifying must win.
        val raw = baseline(phase = RawPhase.CheckingResumeData, isFinished = true)
        assertEquals(DownloadState.Verifying, DownloadStateMapper.map(raw))
    }

    // -- etaSeconds -------------------------------------------------------------------------------

    @Test
    fun etaSecondsIsNullWhenRateIsZero() {
        assertNull(etaSeconds(remainingBytes = 1_000, rateBps = 0))
    }

    @Test
    fun etaSecondsIsNullWhenRateIsNegative() {
        assertNull(etaSeconds(remainingBytes = 1_000, rateBps = -1))
    }

    @Test
    fun etaSecondsRoundsUpAPartialSecond() {
        assertEquals(2L, etaSeconds(remainingBytes = 101, rateBps = 100))
    }

    @Test
    fun etaSecondsIsExactWhenTheRemainderDividesEvenly() {
        assertEquals(10L, etaSeconds(remainingBytes = 1_000, rateBps = 100))
    }

    @Test
    fun etaSecondsIsZeroWhenNothingRemains() {
        assertEquals(0L, etaSeconds(remainingBytes = 0, rateBps = 100))
    }
}
