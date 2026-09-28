package com.teachermovies.tv.ui.player

import com.teachermovies.assistant.explanation.Explanation
import com.teachermovies.assistant.explanation.ExplanationPoint
import com.teachermovies.assistant.explanation.ExplanationUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplanationLinesTest {
    private val line = "Break a leg."

    private fun shown(
        points: List<ExplanationPoint>,
        note: String?,
    ) = ExplanationUiState.Shown(
        line,
        Explanation(summary = "Se desea suerte a alguien.", points = points, subtitleNote = note, promptVersion = null),
        fromCache = false,
    )

    @Test
    fun idleDrawsNothing() {
        assertTrue(explanationLines(ExplanationUiState.Idle).isEmpty())
    }

    @Test
    fun thinkingWhileTheRequestIsOnItsWay() {
        assertEquals(listOf("Pensando…"), explanationLines(ExplanationUiState.Thinking(line)))
    }

    @Test
    fun shownDrawsTheSummaryThenEachPointThenTheNote() {
        val lines =
            explanationLines(
                shown(
                    listOf(ExplanationPoint("break a leg", "modismo: \"mucha suerte\" en el teatro.")),
                    note = "El subtítulo lo traduce literalmente.",
                ),
            )

        assertEquals(
            listOf(
                "Se desea suerte a alguien.",
                "break a leg: modismo: \"mucha suerte\" en el teatro.",
                "El subtítulo lo traduce literalmente.",
            ),
            lines,
        )
    }

    @Test
    fun shownWithoutANoteAndAtMostThreePoints() {
        val points = (1..4).map { ExplanationPoint("e$it", "x$it") }

        assertEquals(
            listOf("Se desea suerte a alguien.", "e1: x1", "e2: x2", "e3: x3"),
            explanationLines(shown(points, note = null)),
        )
    }

    @Test
    fun everyFailureReadsTurnOnTheLaptop() {
        listOf(true, false).forEach { retryable ->
            assertEquals(
                listOf("Explicación no disponible (enciende el portátil)"),
                explanationLines(ExplanationUiState.Unavailable(line, retryable, reason = "no bridge")),
            )
        }
    }
}
