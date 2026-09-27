package com.teachermovies.tv.di

import com.teachermovies.assistant.speech.SpeechLanguage
import com.teachermovies.assistant.translation.BridgeTranslateOutcome
import com.teachermovies.http.bridge.BridgeJob
import com.teachermovies.http.bridge.BridgeOutcome
import com.teachermovies.http.bridge.FakeAssistantBridge
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class HubBridgeTranslateGatewayTest {
    private val bridge = FakeAssistantBridge()
    private val gateway = HubBridgeTranslateGateway(bridge)

    private suspend fun answer(outcome: BridgeOutcome): BridgeTranslateOutcome {
        bridge.respond = { outcome }
        return gateway.translate("Hi.", SpeechLanguage.EN, SpeechLanguage.ES, Duration.ofSeconds(5))
    }

    @Test
    fun `submits a translate job and renames every hub outcome`() =
        runTest {
            assertEquals(BridgeTranslateOutcome.Done("Hola."), answer(BridgeOutcome.Done("Hola.")))
            assertEquals(BridgeTranslateOutcome.TimedOut, answer(BridgeOutcome.TimedOut))
            assertEquals(BridgeTranslateOutcome.Disconnected, answer(BridgeOutcome.Disconnected))
            assertEquals(BridgeTranslateOutcome.Disconnected, answer(BridgeOutcome.Replaced))
            assertEquals(
                BridgeTranslateOutcome.BridgeError("daily_cap"),
                answer(BridgeOutcome.BridgeError("daily_cap", "cap reached")),
            )
            assertEquals(
                BridgeTranslateOutcome.BridgeError("line too long"),
                answer(BridgeOutcome.Rejected("line too long")),
            )
            assertEquals(BridgeJob.Translate("Hi."), bridge.submitted.first())
        }

    @Test
    fun `no bridge connected is NoBridge`() =
        runTest {
            bridge.setConnected(false)
            assertEquals(
                BridgeTranslateOutcome.NoBridge,
                gateway.translate("Hi.", SpeechLanguage.EN, SpeechLanguage.ES, Duration.ofSeconds(5)),
            )
        }
}
