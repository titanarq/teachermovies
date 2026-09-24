package com.teachermovies.player.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Uses a small policy (head 10, tail 5, start buffer 20, read-ahead 30) so the expected ranges
 * can be read off by eye.
 */
class StreamWindowCalculatorTest {
    private val policy =
        StreamPolicy(
            headBytes = 10,
            tailBytes = 5,
            startBufferBytes = 20,
            readAheadBytes = 30,
            underrunBytes = 2,
            resumeBytes = 6,
        )

    // -- byteOffsetFor / windowFor -------------------------------------------------------------

    @Test
    fun positionZeroMapsToOffsetZero() {
        assertEquals(0L, StreamWindowCalculator.byteOffsetFor(0, 1_000, 1_000))
        assertEquals(ByteRange(0, 30), StreamWindowCalculator.windowFor(0, 1_000, 1_000, policy))
    }

    @Test
    fun unknownDurationWithNonZeroPositionMapsToOffsetZero() {
        assertEquals(0L, StreamWindowCalculator.byteOffsetFor(500, 0, 1_000))
        assertEquals(ByteRange(0, 30), StreamWindowCalculator.windowFor(500, 0, 1_000, policy))
    }

    @Test
    fun positionHalfwayMapsToHalfTheFile() {
        assertEquals(500L, StreamWindowCalculator.byteOffsetFor(60_000, 120_000, 1_000))
        assertEquals(ByteRange(500, 30), StreamWindowCalculator.windowFor(60_000, 120_000, 1_000, policy))
    }

    @Test
    fun positionPastTheDurationIsClampedToTheFileSize() {
        assertEquals(1_000L, StreamWindowCalculator.byteOffsetFor(200_000, 120_000, 1_000))
        assertEquals(ByteRange(1_000, 0), StreamWindowCalculator.windowFor(200_000, 120_000, 1_000, policy))
    }

    @Test
    fun windowIsTruncatedAtTheEndOfTheFile() {
        // 99% of 1000 bytes = offset 990, only 10 bytes left.
        assertEquals(ByteRange(990, 10), StreamWindowCalculator.windowFor(99, 100, 1_000, policy))
    }

    @Test
    fun largeFileAndLongDurationDoNotOverflow() {
        val size = 50L * 1024 * 1024 * 1024 // 50 GiB
        val duration = 4L * 60 * 60 * 1000 // 4 h
        assertEquals(size / 2, StreamWindowCalculator.byteOffsetFor(duration / 2, duration, size))
    }

    // -- openRanges ----------------------------------------------------------------------------

    @Test
    fun fileSmallerThanHeadPlusTailYieldsOneRangeCoveringTheFile() {
        assertEquals(
            listOf(ByteRange(0, 12)),
            StreamWindowCalculator.openRanges(
                fileSizeBytes = 12,
                startPositionMs = 0,
                durationMs = 100,
                policy = policy,
            ),
        )
    }

    @Test
    fun startBufferOverlappingTheHeadIsMerged() {
        // Start offset 5 -> buffer [5, 25) overlaps head [0, 10); tail is [995, 1000).
        assertEquals(
            listOf(ByteRange(0, 25), ByteRange(995, 5)),
            StreamWindowCalculator.openRanges(
                fileSizeBytes = 1_000,
                startPositionMs = 5,
                durationMs = 1_000,
                policy = policy,
            ),
        )
    }

    @Test
    fun startBufferOverlappingTheTailIsMerged() {
        // Start offset 985 -> buffer [985, 1000) merges with tail [995, 1000).
        assertEquals(
            listOf(ByteRange(0, 10), ByteRange(985, 15)),
            StreamWindowCalculator.openRanges(
                fileSizeBytes = 1_000,
                startPositionMs = 985,
                durationMs = 1_000,
                policy = policy,
            ),
        )
    }

    @Test
    fun startInTheMiddleYieldsThreeAscendingRanges() {
        assertEquals(
            listOf(ByteRange(0, 10), ByteRange(500, 20), ByteRange(995, 5)),
            StreamWindowCalculator.openRanges(
                fileSizeBytes = 1_000,
                startPositionMs = 500,
                durationMs = 1_000,
                policy = policy,
            ),
        )
    }

    @Test
    fun startAtZeroOnlyRequestsHeadAndStartBufferOnce() {
        assertEquals(
            listOf(ByteRange(0, 20), ByteRange(995, 5)),
            StreamWindowCalculator.openRanges(
                fileSizeBytes = 1_000,
                startPositionMs = 0,
                durationMs = 1_000,
                policy = policy,
            ),
        )
    }

    @Test
    fun touchingRangesAreMerged() {
        // Start offset 10 -> buffer [10, 30) touches head [0, 10).
        assertEquals(
            listOf(ByteRange(0, 30), ByteRange(995, 5)),
            StreamWindowCalculator.openRanges(
                fileSizeBytes = 1_000,
                startPositionMs = 10,
                durationMs = 1_000,
                policy = policy,
            ),
        )
    }
}
