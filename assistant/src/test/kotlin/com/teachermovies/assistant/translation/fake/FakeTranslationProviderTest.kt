package com.teachermovies.assistant.translation.fake

import com.teachermovies.assistant.speech.SpeechLanguage
import com.teachermovies.assistant.translation.NullTranslationProvider
import com.teachermovies.assistant.translation.TranslationResult
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTranslationProviderTest {
    private val provider = FakeTranslationProvider()

    @Test
    fun `canned translation is returned and the request recorded`() =
        runTest {
            provider.translations["Hello"] = "Hola"

            assertEquals(TranslationResult.Translated("Hola", fromCache = false), provider.translate("Hello"))
            assertEquals(listOf("Hello"), provider.requests)
        }

    @Test
    fun `unknown text is unavailable`() =
        runTest {
            assertEquals(TranslationResult.Unavailable("no canned translation"), provider.translate("Bye"))
            assertEquals(listOf("Bye"), provider.requests)
        }

    @Test
    fun `nextResult overrides the map once`() =
        runTest {
            provider.translations["Hello"] = "Hola"
            provider.nextResult = TranslationResult.Offline

            assertEquals(TranslationResult.Offline, provider.translate("Hello"))
            assertEquals(TranslationResult.Translated("Hola", fromCache = false), provider.translate("Hello"))
            assertEquals(2, provider.requests.size)
        }

    @Test
    fun `delayMs waits in virtual time`() =
        runTest {
            provider.translations["Hello"] = "Hola"
            provider.delayMs = 1_500

            provider.translate("Hello")

            assertEquals(1_500, currentTime)
        }

    @Test
    fun `blank text is answered without reaching the provider`() =
        runTest {
            assertEquals(TranslationResult.Translated("", fromCache = true), provider.translate("   \n"))
            assertTrue(provider.requests.isEmpty())
        }

    @Test
    fun `same language is answered with the trimmed text without reaching the provider`() =
        runTest {
            provider.nextResult = TranslationResult.Offline

            val result = provider.translate("  Hello there ", SpeechLanguage.EN, SpeechLanguage.EN)

            assertEquals(TranslationResult.Translated("Hello there", fromCache = true), result)
            assertTrue(provider.requests.isEmpty())
        }

    @Test
    fun `null provider is unavailable for real requests`() =
        runTest {
            assertEquals("none", NullTranslationProvider.id)
            assertEquals(
                TranslationResult.Unavailable("no translation provider configured"),
                NullTranslationProvider.translate("Hello"),
            )
            assertEquals(
                TranslationResult.Unavailable("no translation provider configured"),
                NullTranslationProvider.translate("Hola", SpeechLanguage.ES, SpeechLanguage.EN),
            )
        }

    @Test
    fun `null provider still honours the shortcuts`() =
        runTest {
            assertEquals(TranslationResult.Translated("", fromCache = true), NullTranslationProvider.translate(" "))
            assertEquals(
                TranslationResult.Translated("Hi", fromCache = true),
                NullTranslationProvider.translate("Hi", SpeechLanguage.ES, SpeechLanguage.ES),
            )
        }
}
