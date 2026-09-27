package com.teachermovies.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `esMs = enMs * frameRateScale + offsetMs` (ADR-0005 §6), the one formula the subtitle panel (#283)
 * leans on to show the Spanish line a captured English cue belongs to. The scale is a `Double`, so
 * the product is truncated towards zero exactly where the formula says it is -- these expectations are
 * the `Long`s that expression produces, not rounded ones.
 */
class SubtitleAlignmentTest {
    private val id = TorrentId("0123456789abcdef0123456789abcdef01234567")
    private val path = "/storage/Movies/a/subs/movie.es.srt"

    /** A 23.976 fps picture with 25 fps subtitles: English runs ahead, so the scale pulls it back. */
    private val twentyThreeNinetySevenSixOverTwentyFive = 23.976 / 25

    private fun alignment(
        offsetMs: Long = 0L,
        frameRateScale: Double = 1.0,
        qualityScore: Double = 0.9,
    ) = SubtitleAlignment(
        torrentId = id,
        subtitlePath = path,
        offsetMs = offsetMs,
        frameRateScale = frameRateScale,
        qualityScore = qualityScore,
        computedAtEpochMs = 1_000L,
    )

    @Test
    fun aScaleOfOneAndNoOffsetMapsATimestampOntoItself() {
        val aligned = alignment()

        assertEquals(0L, aligned.spanishMsFor(0L))
        assertEquals(1_500L, aligned.spanishMsFor(1_500L))
        assertEquals(72_000_000L, aligned.spanishMsFor(72_000_000L))
    }

    @Test
    fun anOffsetShiftsEveryTimestampByTheSameAmount() {
        val aligned = alignment(offsetMs = 2_500L)

        assertEquals(2_500L, aligned.spanishMsFor(0L))
        assertEquals(4_000L, aligned.spanishMsFor(1_500L))
        assertEquals(72_002_500L, aligned.spanishMsFor(72_000_000L))
    }

    @Test
    fun aNegativeOffsetMovesEveryTimestampEarlier() {
        val aligned = alignment(offsetMs = -2_500L)

        assertEquals(-2_500L, aligned.spanishMsFor(0L))
        assertEquals(7_500L, aligned.spanishMsFor(10_000L))
        // A cue in the first seconds of an English track can land before the Spanish file starts.
        assertEquals(-1_500L, aligned.spanishMsFor(1_000L))
    }

    @Test
    fun aFrameRateScaleOtherThanOneRescalesBeforeTheOffsetIsApplied() {
        val aligned = alignment(frameRateScale = twentyThreeNinetySevenSixOverTwentyFive, offsetMs = 1_000L)

        // (60_000 * 0.95904) is 57_542.4, truncated to 57_542, plus the 1_000 ms offset.
        assertEquals(58_542L, aligned.spanishMsFor(60_000L))
        // (1_234_567 * 0.95904) is 1_183_999.13568, truncated to 1_183_999.
        assertEquals(1_184_999L, aligned.spanishMsFor(1_234_567L))
        assertEquals(1_000L, aligned.spanishMsFor(0L))
    }

    @Test
    fun aFrameRateScaleOtherThanOneAndANegativeOffsetCombine() {
        val aligned = alignment(frameRateScale = twentyThreeNinetySevenSixOverTwentyFive, offsetMs = -1_000L)

        assertEquals(56_542L, aligned.spanishMsFor(60_000L))
        assertEquals(-1_000L, aligned.spanishMsFor(0L))
    }

    @Test
    fun keepsTheNumbersTheAlignerComputed() {
        val aligned =
            alignment(
                offsetMs = -2_500L,
                frameRateScale = twentyThreeNinetySevenSixOverTwentyFive,
                qualityScore = 0.87,
            )

        assertEquals(id, aligned.torrentId)
        assertEquals(path, aligned.subtitlePath)
        assertEquals(-2_500L, aligned.offsetMs)
        assertEquals(twentyThreeNinetySevenSixOverTwentyFive, aligned.frameRateScale, 0.0)
        assertEquals(0.87, aligned.qualityScore, 0.0)
        assertEquals(1_000L, aligned.computedAtEpochMs)
    }
}
