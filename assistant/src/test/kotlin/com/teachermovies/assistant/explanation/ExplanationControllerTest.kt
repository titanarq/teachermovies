package com.teachermovies.assistant.explanation

import com.teachermovies.assistant.explanation.fake.FakeBridgeExplainGateway
import com.teachermovies.core.repo.fake.InMemoryExplanationCacheRepository
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplanationControllerTest {
    private val gateway = FakeBridgeExplainGateway()

    private fun TestScope.controller(): ExplanationController {
        val explainer = LineExplainer(gateway, InMemoryExplanationCacheRepository(), clock = { 0L })
        return ExplanationController(explainer, backgroundScope)
    }

    /** Runs everything due within the next minute of virtual time, including `backgroundScope` work. */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun context(line: String) = ExplanationContext("Heat", line, emptyList(), emptyList(), null)

    private fun document(summary: String) = """{"resumen": "$summary", "puntos": []}"""

    @Test
    fun `starts idle, thinks, then shows the explanation`() =
        runTest {
            val controller = controller()
            assertEquals(ExplanationUiState.Idle, controller.state.value)
            gateway.delayMs = 1_000
            gateway.documents["one"] = document("Uno")

            controller.explain(context("one"))
            assertEquals(ExplanationUiState.Thinking("one"), controller.state.value)
            settle()

            val shown = controller.state.value as ExplanationUiState.Shown
            assertEquals("one", shown.line)
            assertEquals("Uno", shown.explanation.summary)
            assertEquals(false, shown.fromCache)
        }

    @Test
    fun `a new line cancels the previous request and only one is ever in flight`() =
        runTest {
            val controller = controller()
            gateway.delayMs = 1_000
            gateway.documents["one"] = document("Uno")
            gateway.documents["two"] = document("Dos")

            controller.explain(context("one"))
            runCurrent()
            advanceTimeBy(500)
            controller.explain(context("two"))
            assertEquals(ExplanationUiState.Thinking("two"), controller.state.value)
            settle()

            assertEquals("Dos", (controller.state.value as ExplanationUiState.Shown).explanation.summary)
            assertEquals(listOf("one", "two"), gateway.requests.map { it.context.line })
            assertEquals(1, gateway.maxInFlight)
        }

    @Test
    fun `many quick lines in a row still keep one request in flight and show the last`() =
        runTest {
            val controller = controller()
            gateway.delayMs = 1_000
            (1..5).forEach { gateway.documents["l$it"] = document("S$it") }
            (1..5).forEach {
                controller.explain(context("l$it"))
                runCurrent()
                advanceTimeBy(100)
            }
            settle()
            assertEquals("S5", (controller.state.value as ExplanationUiState.Shown).explanation.summary)
            assertEquals(1, gateway.maxInFlight)
        }

    @Test
    fun `asking again for the line already on its way sends nothing new`() =
        runTest {
            val controller = controller()
            gateway.delayMs = 1_000
            gateway.documents["one"] = document("Uno")
            controller.explain(context("one"))
            runCurrent()
            controller.explain(context("one"))
            settle()
            assertEquals(1, gateway.requests.size)
            assertTrue(controller.state.value is ExplanationUiState.Shown)

            // Once shown, asking again is a new request, answered from the cache.
            controller.explain(context("one"))
            settle()
            assertEquals(true, (controller.state.value as ExplanationUiState.Shown).fromCache)
            assertEquals(1, gateway.requests.size)
        }

    @Test
    fun `dismiss cancels the request and a late answer is never shown`() =
        runTest {
            val controller = controller()
            gateway.delayMs = 1_000
            gateway.documents["one"] = document("Uno")
            controller.explain(context("one"))
            runCurrent()
            controller.dismiss()
            assertEquals(ExplanationUiState.Idle, controller.state.value)
            settle()
            assertEquals(ExplanationUiState.Idle, controller.state.value)
        }

    @Test
    fun `failures become unavailable, retryable only when the laptop may answer next time`() =
        runTest {
            val controller = controller()
            gateway.nextOutcome = BridgeExplainOutcome.TimedOut
            controller.explain(context("one"))
            settle()
            assertEquals(
                ExplanationUiState.Unavailable("one", retryable = true, reason = ExplanationController.OFFLINE_REASON),
                controller.state.value,
            )

            gateway.nextOutcome = BridgeExplainOutcome.NoBridge
            controller.explain(context("two"))
            settle()
            assertEquals(
                ExplanationUiState.Unavailable("two", retryable = false, reason = LineExplainer.NO_BRIDGE_REASON),
                controller.state.value,
            )
        }

    @Test
    fun `nothing is asked until a line is explained`() =
        runTest {
            controller()
            settle()
            assertEquals(0, gateway.requests.size)
        }
}
