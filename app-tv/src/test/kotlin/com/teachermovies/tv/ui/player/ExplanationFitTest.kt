package com.teachermovies.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplanationFitTest {
    private val p720 = ScreenMetrics(1280, 720, 1f)
    private val p1080 = ScreenMetrics(1920, 1080, 1f)
    private val p1080Tvdpi = ScreenMetrics(1920, 1080, 4f / 3f)

    /** Six phrases explained: a summary and three points, long enough to need several lines. */
    private val long =
        listOf(
            "The speakers trade six short lines: a greeting, a complaint about the weather, an invitation " +
                "and a polite refusal, " +
                "and the scene turns on how casually each of them is phrased.",
            "hello there: an informal greeting between people who already know each other, closer to hi than to good morning.",
            "I can't make it: a soft refusal that gives no reason, which sounds friendly but ends the invitation for good.",
            "how about next week: a counter-offer that keeps the invitation alive while moving it to a later date.",
            "The subtitle note says that the Spanish line is shorter than what is actually said in the English audio track.",
        ).joinToString("\n")

    @Test
    fun theLongExplanationFitsAt720pWithoutEllipsis() = assertFits(p720)

    @Test
    fun theLongExplanationFitsAt1080pWithoutEllipsis() = assertFits(p1080)

    @Test
    fun theLongExplanationFitsOnA1080pTvdpiScreen() = assertFits(p1080Tvdpi)

    private fun assertFits(screen: ScreenMetrics) {
        val fit = ExplanationFit.fit(long, screen)
        assertFalse(fit.ellipsis)
        assertTrue(fit.fontSp >= ExplanationFit.minFontSp(screen))
        assertTrue(fit.fontSp <= ExplanationFit.maxFontSp(screen))
        val usableHeight = ExplanationFit.panelHeightDp(screen) - 2 * ExplanationFit.PADDING_DP
        assertTrue(fit.maxLines * fit.lineHeightSp <= usableHeight)
    }

    @Test
    fun aShortTextGetsTheLargestFontAndTheLongOneAtLeastTheMinimum() {
        val short = ExplanationFit.fit("A greeting.", p720)
        assertEquals(ExplanationFit.maxFontSp(p720), short.fontSp, 0.001f)
        val big = ExplanationFit.fit(long, p720)
        assertTrue(big.fontSp < short.fontSp)
        assertTrue(big.lineHeightSp < big.fontSp * 1.4f + 0.001f)
    }

    @Test
    fun aVeryLongTextUsesTheMinimumFontAndTruncatesWithEllipsis() {
        val huge =
            (1..40).joinToString("\n") {
                "Phrase $it: " +
                    "a long winded explanation of the expression. ".repeat(4)
            }
        listOf(p720, p1080, p1080Tvdpi).forEach { screen ->
            val fit = ExplanationFit.fit(huge, screen)
            assertTrue(fit.ellipsis)
            assertEquals(ExplanationFit.minFontSp(screen), fit.fontSp, 0.001f)
            val usableHeight = ExplanationFit.panelHeightDp(screen) - 2 * ExplanationFit.PADDING_DP
            assertTrue(fit.maxLines >= 1)
            assertTrue(fit.maxLines * fit.lineHeightSp <= usableHeight)
        }
    }

    @Test
    fun theMinimumFontStaysLegibleAt720pAndScalesWithTheScreen() {
        assertTrue(ExplanationFit.minFontSp(p720) >= 20f)
        assertEquals(ExplanationFit.minFontSp(p720) * 1.5f, ExplanationFit.minFontSp(p1080), 0.001f)
        // The same physical size on a denser screen is fewer sp, never fewer pixels.
        assertEquals(
            ExplanationFit.minFontSp(p1080) * 1f,
            ExplanationFit.minFontSp(p1080Tvdpi) * p1080Tvdpi.density,
            0.001f,
        )
    }

    @Test
    fun thePanelTakesAtLeast60PercentOfTheWidthAndHalfOfTheHeight() {
        listOf(p720, p1080, p1080Tvdpi).forEach { screen ->
            assertTrue(ExplanationFit.panelWidthDp(screen) >= 0.6f * screen.widthDp)
            assertTrue(ExplanationFit.panelHeightDp(screen) >= 0.5f * screen.heightDp)
        }
    }

    @Test
    fun anEmptyTextStillFitsInOneLine() {
        val fit = ExplanationFit.fit("", p720)
        assertFalse(fit.ellipsis)
        assertEquals(1, fit.maxLines)
    }
}
