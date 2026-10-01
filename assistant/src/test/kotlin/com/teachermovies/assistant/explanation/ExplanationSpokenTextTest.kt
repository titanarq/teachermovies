package com.teachermovies.assistant.explanation

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplanationSpokenTextTest {
    private fun explanation(
        summary: String,
        points: List<ExplanationPoint> = emptyList(),
        note: String? = null,
    ) = Explanation(summary, points, note, null)

    @Test
    fun `says the summary, each point's expression and explanation, then the note`() {
        val e =
            explanation(
                "He is leaving.",
                listOf(ExplanationPoint("hit the road", "to leave"), ExplanationPoint("on me", "I pay")),
                "The subtitle is looser.",
            )
        assertEquals(
            "He is leaving. hit the road to leave on me I pay The subtitle is looser.",
            ExplanationUiState.Shown("line", e, fromCache = false).spokenText(),
        )
    }

    @Test
    fun `skips blank parts`() {
        val points = listOf(ExplanationPoint("  ", "explained"), ExplanationPoint("expr", ""))
        val e = explanation("Summary.", points, "   ")
        assertEquals("Summary. explained expr", e.spokenText())
    }

    @Test
    fun `summary only`() {
        assertEquals("Just this.", explanation("Just this.").spokenText())
    }
}
