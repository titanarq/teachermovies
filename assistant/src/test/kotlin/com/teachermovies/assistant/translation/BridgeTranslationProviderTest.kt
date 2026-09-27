package com.teachermovies.assistant.translation

import com.teachermovies.assistant.speech.SpeechLanguage
import com.teachermovies.assistant.translation.fake.FakeBridgeTranslateGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class BridgeTranslationProviderTest {
    private val gateway = FakeBridgeTranslateGateway()
    private val provider = BridgeTranslationProvider(gateway)

    @Test
    fun `id is bridge`() {
        assertEquals("bridge", provider.id)
    }

    @Test
    fun `an answered job is a translation that came from no cache`() =
        runTest {
            gateway.translations["Hello there"] = "Hola"

            val result = provider.translate("Hello there")

            assertEquals(TranslationResult.Translated("Hola", fromCache = false), result)
        }

    @Test
    fun `the answer is trimmed`() =
        runTest {
            gateway.translations["Hello"] = "  Hola \n"

            val result = provider.translate("Hello")

            assertEquals(TranslationResult.Translated("Hola", fromCache = false), result)
        }

    @Test
    fun `no bridge connected is unavailable, not offline`() =
        runTest {
            gateway.nextOutcome = BridgeTranslateOutcome.NoBridge

            val result = provider.translate("Hello")

            assertEquals(TranslationResult.Unavailable("no bridge connected"), result)
        }

    @Test
    fun `a job nobody answered in time is offline`() =
        runTest {
            gateway.nextOutcome = BridgeTranslateOutcome.TimedOut

            val result = provider.translate("Hello")

            assertEquals(TranslationResult.Offline, result)
        }

    @Test
    fun `a bridge whose stream went away is offline`() =
        runTest {
            gateway.nextOutcome = BridgeTranslateOutcome.Disconnected

            val result = provider.translate("Hello")

            assertEquals(TranslationResult.Offline, result)
        }

    @Test
    fun `a job the bridge failed is unavailable and keeps its reason`() =
        runTest {
            gateway.nextOutcome = BridgeTranslateOutcome.BridgeError("daily cap reached")

            val result = provider.translate("Hello")

            assertEquals(TranslationResult.Unavailable("bridge error: daily cap reached"), result)
        }

    @Test
    fun `an answer with no text in it is unavailable`() =
        runTest {
            gateway.nextOutcome = BridgeTranslateOutcome.Done("   ")

            val result = provider.translate("Hello")

            assertEquals(TranslationResult.Unavailable("empty translation"), result)
        }

    @Test
    fun `blank text and same language never reach the hub`() =
        runTest {
            assertEquals(TranslationResult.Translated("", fromCache = true), provider.translate("  "))
            assertEquals(
                TranslationResult.Translated("Hello", fromCache = true),
                provider.translate(" Hello ", SpeechLanguage.EN, SpeechLanguage.EN),
            )

            assertTrue(gateway.requests.isEmpty())
        }

    @Test
    fun `the direction reaches the hub`() =
        runTest {
            val timed = BridgeTranslationProvider(gateway, timeout = Duration.ofSeconds(3))

            timed.translate("Hola", SpeechLanguage.ES, SpeechLanguage.EN)

            val request = gateway.requests.single()
            assertEquals("Hola", request.text)
            assertEquals(SpeechLanguage.ES, request.from)
            assertEquals(SpeechLanguage.EN, request.to)
            assertEquals(Duration.ofSeconds(3), request.timeout)
        }

    @Test
    fun `the provider's own timeout reaches the hub by default`() =
        runTest {
            gateway.translations["Hello"] = "Hola"

            provider.translate("Hello")

            assertEquals(BridgeTranslationProvider.TIMEOUT, gateway.requests.single().timeout)
        }

    @Test
    fun `a hub that throws becomes unavailable instead of throwing`() =
        runTest {
            val broken =
                BridgeTranslateGateway { _, _, _, _ ->
                    throw IllegalStateException("boom")
                }

            val result = BridgeTranslationProvider(broken).translate("Hello")

            assertEquals(TranslationResult.Unavailable("bridge error: IllegalStateException"), result)
        }
}
