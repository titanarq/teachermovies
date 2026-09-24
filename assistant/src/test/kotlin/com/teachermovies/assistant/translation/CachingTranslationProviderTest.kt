package com.teachermovies.assistant.translation

import com.teachermovies.assistant.speech.SpeechLanguage
import com.teachermovies.assistant.translation.fake.FakeTranslationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CachingTranslationProviderTest {
    private val fake = FakeTranslationProvider(id = "fake-id")
    private val caching = CachingTranslationProvider(fake, maxEntries = 3)

    @Test
    fun `id is the delegate's`() {
        assertEquals("fake-id", caching.id)
    }

    @Test
    fun `a miss asks the delegate and a second call is a hit`() =
        runTest {
            fake.translations["Hello there"] = "Hola"

            assertEquals(TranslationResult.Translated("Hola", fromCache = false), caching.translate("Hello there"))
            assertEquals(TranslationResult.Translated("Hola", fromCache = true), caching.translate("Hello there"))
            assertEquals(listOf("Hello there"), fake.requests)
        }

    @Test
    fun `a hit ignores spacing and casing differences`() =
        runTest {
            fake.translations["Hello there"] = "Hola"
            caching.translate("Hello there")

            val result = caching.translate("  hello \t  THERE\n")

            assertEquals(TranslationResult.Translated("Hola", fromCache = true), result)
            assertEquals(1, fake.requests.size)
        }

    @Test
    fun `the language pair is part of the key`() =
        runTest {
            fake.translations["Hola"] = "Hello"
            caching.translate("Hola", SpeechLanguage.ES, SpeechLanguage.EN)

            caching.translate("Hola", SpeechLanguage.EN, SpeechLanguage.ES)

            assertEquals(2, fake.requests.size)
        }

    @Test
    fun `offline is not cached and a later call retries`() =
        runTest {
            fake.translations["Hello"] = "Hola"
            fake.nextResult = TranslationResult.Offline

            assertEquals(TranslationResult.Offline, caching.translate("Hello"))
            assertEquals(TranslationResult.Translated("Hola", fromCache = false), caching.translate("Hello"))
            assertEquals(2, fake.requests.size)
        }

    @Test
    fun `unavailable is not cached and a later call retries`() =
        runTest {
            assertEquals(TranslationResult.Unavailable("no canned translation"), caching.translate("Hello"))
            fake.translations["Hello"] = "Hola"

            assertEquals(TranslationResult.Translated("Hola", fromCache = false), caching.translate("Hello"))
            assertEquals(2, fake.requests.size)
        }

    @Test
    fun `the least recently used entry is evicted beyond maxEntries`() =
        runTest {
            listOf("a", "b", "c", "d").forEach { fake.translations[it] = it.uppercase() }
            caching.translate("a")
            caching.translate("b")
            caching.translate("c")
            caching.translate("a") // hit: "b" is now the least recently used

            caching.translate("d") // evicts "b"

            assertEquals(TranslationResult.Translated("A", fromCache = true), caching.translate("a"))
            assertEquals(TranslationResult.Translated("C", fromCache = true), caching.translate("c"))
            assertEquals(TranslationResult.Translated("D", fromCache = true), caching.translate("d"))
            assertEquals(TranslationResult.Translated("B", fromCache = false), caching.translate("b"))
            assertEquals(listOf("a", "b", "c", "d", "b"), fake.requests)
        }

    @Test
    fun `two concurrent calls for the same text reach the delegate once`() =
        runTest {
            fake.translations["Hello"] = "Hola"
            fake.delayMs = 1_000

            val first = async { caching.translate("Hello") }
            val second = async { caching.translate("hello") }

            assertEquals(TranslationResult.Translated("Hola", fromCache = false), first.await())
            assertEquals(TranslationResult.Translated("Hola", fromCache = true), second.await())
            assertEquals(listOf("Hello"), fake.requests)
        }

    @Test
    fun `blank text and same language are shortcuts that never reach the delegate`() =
        runTest {
            assertEquals(TranslationResult.Translated("", fromCache = true), caching.translate("  "))
            assertEquals(
                TranslationResult.Translated("Hello", fromCache = true),
                caching.translate(" Hello ", SpeechLanguage.EN, SpeechLanguage.EN),
            )
            assertTrue(fake.requests.isEmpty())
        }

    @Test
    fun `a throwing delegate becomes unavailable`() =
        runTest {
            val throwing =
                object : TranslationProvider {
                    override val id = "broken"

                    override suspend fun translate(
                        text: String,
                        from: SpeechLanguage,
                        to: SpeechLanguage,
                    ): TranslationResult = throw IllegalStateException("boom")
                }

            val result = CachingTranslationProvider(throwing).translate("Hello")

            assertTrue(result is TranslationResult.Unavailable)
        }

    @Test
    fun `null provider behind the cache stays unavailable`() =
        runTest {
            val cachedNull = CachingTranslationProvider(NullTranslationProvider)

            assertEquals("none", cachedNull.id)
            assertEquals(TranslationResult.Unavailable("no translation provider configured"), cachedNull.translate("Hi"))
            assertEquals(TranslationResult.Unavailable("no translation provider configured"), cachedNull.translate("Hi"))
        }
}
