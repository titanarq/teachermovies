package com.teachermovies.assistant.alignment

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SubtitleAlignerTest {
    private val aligner = SubtitleAligner()
    private val english = SyntheticSubtitles.english()

    private fun assertFit(
        fit: AlignmentFit,
        offsetMs: Long,
        scale: Double,
    ) {
        assertTrue("offset ${fit.offsetMs} vs $offsetMs", abs(fit.offsetMs - offsetMs) <= 100)
        assertEquals(scale, fit.frameRateScale, 1e-12)
        assertTrue("quality ${fit.qualityScore}", fit.isGood)
    }

    @Test
    fun `identical tracks align at zero offset and scale 1`() {
        val fit = aligner.align(english, SyntheticSubtitles.spanishOf(english, 0, jitterMs = 0))

        assertEquals(0L, fit.offsetMs)
        assertEquals(1.0, fit.frameRateScale, 0.0)
        assertEquals(1.0, fit.qualityScore, 1e-9)
    }

    @Test
    fun `offset search finds positive and negative shifts across the window`() {
        for (offset in listOf(7_300L, -12_000L, 14_900L, -14_900L, 400L)) {
            assertFit(aligner.align(english, SyntheticSubtitles.spanishOf(english, offset)), offset, 1.0)
        }
    }

    @Test
    fun `offset is found at 100 ms resolution`() {
        val fit = aligner.align(english, SyntheticSubtitles.spanishOf(english, 3_250, jitterMs = 0))

        assertTrue(fit.offsetMs == 3_200L || fit.offsetMs == 3_300L)
        assertEquals(0L, fit.offsetMs % 100)
    }

    @Test
    fun `a shift beyond 15 s is not a good alignment`() {
        val fit = aligner.align(english, SyntheticSubtitles.spanishOf(english, 25_000))

        assertFalse("quality ${fit.qualityScore}", fit.isGood)
    }

    @Test
    fun `PAL speed-up scale is found`() {
        val scale = 25.0 / 23.976
        assertFit(aligner.align(english, SyntheticSubtitles.spanishOf(english, 1_500, scale)), 1_500, scale)
    }

    @Test
    fun `PAL slow-down scale is found`() {
        val scale = 23.976 / 25.0
        assertFit(aligner.align(english, SyntheticSubtitles.spanishOf(english, -2_000, scale)), -2_000, scale)
    }

    @Test
    fun `scale 1 with an offset is found`() {
        assertFit(aligner.align(english, SyntheticSubtitles.spanishOf(english, -800, 1.0)), -800, 1.0)
    }

    @Test
    fun `a scale that is not tried does not align`() {
        val fit = aligner.align(english, SyntheticSubtitles.spanishOf(english, 0, scale = 1.1))

        assertFalse("quality ${fit.qualityScore}", fit.isGood)
    }

    @Test
    fun `a wrong scale drifts out of alignment`() {
        val scale = 25.0 / 23.976
        val only1 = SubtitleAligner(scales = listOf(1.0))

        assertFalse(only1.align(english, SyntheticSubtitles.spanishOf(english, 0, scale)).isGood)
    }

    @Test
    fun `an unrelated Spanish track scores below the threshold`() {
        for (seed in 1..6) {
            val en = SyntheticSubtitles.english(seed = seed)
            val full = aligner.align(en, SyntheticSubtitles.english(seed = 100 + seed))
            val short = aligner.align(en, SyntheticSubtitles.english(cues = 150, seed = 100 + seed))

            assertFalse("seed $seed quality ${full.qualityScore}", full.isGood)
            assertFalse("seed $seed short quality ${short.qualityScore}", short.isGood)
        }
    }

    @Test
    fun `a translation missing a third of its lines with noisy timing still aligns`() {
        val scale = 25.0 / 23.976
        val es = SyntheticSubtitles.spanishOf(english, -4_200, scale, jitterMs = 500)
        val partial = SubtitleTrack(es.cues.filter { it.index % 3 != 0 })

        assertFit(aligner.align(english, partial), -4_200, scale)
    }

    @Test
    fun `two adjacent English cues are joined against one Spanish cue`() {
        val (en, es) = SyntheticSubtitles.joinedPairs(offsetMs = 4_000)

        val fit = aligner.align(en, es)

        assertEquals(4_000L, fit.offsetMs)
        assertEquals(1.0, fit.qualityScore, 1e-9)
    }

    @Test
    fun `merged pairs against an unrelated merged track do not align`() {
        val (en, _) = SyntheticSubtitles.joinedPairs(seed = 1)
        val (_, unrelated) = SyntheticSubtitles.joinedPairs(seed = 51)

        assertFalse(aligner.align(en, unrelated).isGood)
    }

    @Test
    fun `without joining the merged pairs do not align`() {
        val (en, es) = SyntheticSubtitles.joinedPairs(offsetMs = 4_000)

        val fit = SubtitleAligner(maxJoinedCues = 1).align(en, es)

        assertFalse("quality ${fit.qualityScore}", fit.isGood)
    }

    @Test
    fun `a cue overlapping under 30 percent does not match`() {
        val en = SubtitleTrack(listOf(SubtitleCue(0, 0, 1_000, "a")))
        val es29 = SubtitleTrack(listOf(SubtitleCue(0, 0, 3_500, "b")))
        val es30 = SubtitleTrack(listOf(SubtitleCue(0, 0, 3_333, "b")))
        val tight = SubtitleAligner(maxOffsetMs = 0)

        assertEquals(0.0, tight.align(en, es29).qualityScore, 0.0)
        assertEquals(1.0, tight.align(en, es30).qualityScore, 0.0)
    }

    @Test
    fun `empty tracks score zero`() {
        val empty = SubtitleTrack(emptyList())

        assertEquals(AlignmentFit(0, 1.0, 0.0), aligner.align(empty, english))
        assertEquals(AlignmentFit(0, 1.0, 0.0), aligner.align(english, empty))
    }

    @Test
    fun `the quality threshold is 0 point 6`() {
        assertTrue(AlignmentFit(0, 1.0, 0.6).isGood)
        assertFalse(AlignmentFit(0, 1.0, 0.599).isGood)
    }
}
