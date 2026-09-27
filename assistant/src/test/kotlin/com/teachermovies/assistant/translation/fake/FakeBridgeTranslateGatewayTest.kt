package com.teachermovies.assistant.translation.fake

import com.teachermovies.assistant.speech.SpeechLanguage
import com.teachermovies.assistant.translation.BridgeTranslateOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class FakeBridgeTranslateGatewayTest {
    private val gateway = FakeBridgeTranslateGateway()
    private val timeout = Duration.ofSeconds(4)

    private suspend fun ask(text: String) = gateway.translate(text, SpeechLanguage.EN, SpeechLanguage.ES, timeout)

    @Test
    fun `a canned text comes back done`() =
        runTest {
            gateway.translations["Hello"] = "Hola"

            assertEquals(BridgeTranslateOutcome.Done("Hola"), ask("Hello"))
        }

    @Test
    fun `an unknown text is a bridge error`() =
        runTest {
            assertEquals(
                BridgeTranslateOutcome.BridgeError(FakeBridgeTranslateGateway.NO_CANNED_TRANSLATION),
                ask("Hello"),
            )
        }

    @Test
    fun `nextOutcome wins once and is then forgotten`() =
        runTest {
            gateway.translations["Hello"] = "Hola"
            gateway.nextOutcome = BridgeTranslateOutcome.NoBridge

            assertEquals(BridgeTranslateOutcome.NoBridge, ask("Hello"))
            assertEquals(BridgeTranslateOutcome.Done("Hola"), ask("Hello"))
        }

    @Test
    fun `every call is recorded with its direction and timeout`() =
        runTest {
            gateway.translate("Hola", SpeechLanguage.ES, SpeechLanguage.EN, timeout)

            assertEquals(
                listOf(
                    FakeBridgeTranslateGateway.Request("Hola", SpeechLanguage.ES, SpeechLanguage.EN, timeout),
                ),
                gateway.requests,
            )
        }

    @Test
    fun `nothing is recorded before a call`() {
        assertTrue(gateway.requests.isEmpty())
    }
}
