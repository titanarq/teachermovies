package com.teachermovies.tv.di

import com.teachermovies.assistant.explanation.BridgeExplainOutcome
import com.teachermovies.assistant.explanation.ExplanationContext
import com.teachermovies.http.bridge.BridgeJob
import com.teachermovies.http.bridge.BridgeOutcome
import com.teachermovies.http.bridge.FakeAssistantBridge
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class HubBridgeExplainGatewayTest {
    private val bridge = FakeAssistantBridge()
    private val gateway = HubBridgeExplainGateway(bridge)
    private val context =
        ExplanationContext(
            title = "Big Movie",
            line = "Break a leg.",
            before = listOf("It's tonight.", "I'm nervous."),
            after = listOf("Thanks."),
            spanishLine = "Mucha mierda.",
        )

    private suspend fun answer(outcome: BridgeOutcome): BridgeExplainOutcome {
        bridge.respond = { outcome }
        return gateway.explain(context, Duration.ofSeconds(5))
    }

    @Test
    fun `submits an explain job with the whole context and renames every hub outcome`() =
        runTest {
            assertEquals(BridgeExplainOutcome.Done("{}"), answer(BridgeOutcome.Done("{}")))
            assertEquals(BridgeExplainOutcome.TimedOut, answer(BridgeOutcome.TimedOut))
            assertEquals(BridgeExplainOutcome.Disconnected, answer(BridgeOutcome.Disconnected))
            assertEquals(BridgeExplainOutcome.Disconnected, answer(BridgeOutcome.Replaced))
            assertEquals(
                BridgeExplainOutcome.BridgeError("daily_cap"),
                answer(BridgeOutcome.BridgeError("daily_cap", "cap reached")),
            )
            assertEquals(
                BridgeExplainOutcome.BridgeError("line too long"),
                answer(BridgeOutcome.Rejected("line too long")),
            )
            assertEquals(
                BridgeJob.Explain(
                    title = "Big Movie",
                    line = "Break a leg.",
                    before = listOf("It's tonight.", "I'm nervous."),
                    after = listOf("Thanks."),
                    spanishLine = "Mucha mierda.",
                ),
                bridge.submitted.first(),
            )
        }

    @Test
    fun `no bridge connected is NoBridge`() =
        runTest {
            bridge.setConnected(false)
            assertEquals(BridgeExplainOutcome.NoBridge, gateway.explain(context, Duration.ofSeconds(5)))
        }
}
