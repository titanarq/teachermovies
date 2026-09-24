package com.teachermovies.player.policy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table-driven: each test picks a `(lastPositionMs, durationMs)` pair, calls
 * [ResumePolicy.startPosition] once, and checks the result against the rule spelled out in
 * issue #76.
 */
class ResumePolicyTest {
    @Test
    fun zeroPositionStartsFromTheBeginning() {
        assertEquals(0L, ResumePolicy.startPosition(lastPositionMs = 0L, durationMs = 120_000L))
    }

    @Test
    fun positionUnderTenSecondsStartsFromTheBeginning() {
        assertEquals(0L, ResumePolicy.startPosition(lastPositionMs = 9_999L, durationMs = 120_000L))
    }

    @Test
    fun positionAtExactlyTenSecondsResumesRewoundByThreeSeconds() {
        assertEquals(7_000L, ResumePolicy.startPosition(lastPositionMs = 10_000L, durationMs = null))
    }

    @Test
    fun aNormalMidMovieResumeRewindsByThreeSeconds() {
        assertEquals(92_000L, ResumePolicy.startPosition(lastPositionMs = 95_000L, durationMs = 600_000L))
    }

    @Test
    fun unknownDurationNeverCountsAsNearTheEnd() {
        assertEquals(497_000L, ResumePolicy.startPosition(lastPositionMs = 500_000L, durationMs = null))
    }

    @Test
    fun pastTheLastMinuteOfAKnownDurationStartsOver() {
        // duration - 60s = 60_000; 100_000 is past that.
        assertEquals(0L, ResumePolicy.startPosition(lastPositionMs = 100_000L, durationMs = 120_000L))
    }

    @Test
    fun exactlyAtTheEndOfMediaMarginStillResumes() {
        // duration - 60s = 60_000 exactly; the rule is strictly greater-than, so this resumes.
        assertEquals(57_000L, ResumePolicy.startPosition(lastPositionMs = 60_000L, durationMs = 120_000L))
    }

    @Test
    fun justOneMillisecondPastTheEndMarginStartsOver() {
        assertEquals(0L, ResumePolicy.startPosition(lastPositionMs = 60_001L, durationMs = 120_000L))
    }

    @Test
    fun aMediaShorterThanTheEndMarginAlwaysStartsOver() {
        // duration - 60s is negative, so any position past the 10s floor still counts as "near
        // the end" for a clip this short.
        assertEquals(0L, ResumePolicy.startPosition(lastPositionMs = 20_000L, durationMs = 30_000L))
    }
}
