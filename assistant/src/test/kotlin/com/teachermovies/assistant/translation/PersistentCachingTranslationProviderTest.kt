package com.teachermovies.assistant.translation

import com.teachermovies.assistant.speech.SpeechLanguage
import com.teachermovies.assistant.translation.fake.FakeTranslationProvider
import com.teachermovies.core.repo.CachePruningPolicy
import com.teachermovies.core.repo.TranslationCacheRepository
import com.teachermovies.core.repo.fake.InMemoryTranslationCacheRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentCachingTranslationProviderTest {
    private val delegate = FakeTranslationProvider(id = "fake-id")
    private val cache = InMemoryTranslationCacheRepository()
    private var now = 1_000L
    private val provider = PersistentCachingTranslationProvider(delegate, cache, clock = { now })

    @Test
    fun `id is the delegate's`() {
        assertEquals("fake-id", provider.id)
    }

    @Test
    fun `a miss asks the delegate and the answer is still there after a restart`() =
        runTest {
            delegate.translations["Hello there"] = "Hola"

            val first = provider.translate("Hello there")

            // Same repository, new instance: what the app finds when it is started again.
            val restarted = PersistentCachingTranslationProvider(delegate, cache, clock = { now })
            val second = restarted.translate("Hello there")
            assertEquals(TranslationResult.Translated("Hola", fromCache = false), first)
            assertEquals(TranslationResult.Translated("Hola", fromCache = true), second)
            assertEquals(listOf("Hello there"), delegate.requests)
        }

    @Test
    fun `a row already in the cache is a hit that never reaches the delegate`() =
        runTest {
            cache.store("Hello", "Hola", now)

            val result = provider.translate("Hello")

            assertEquals(TranslationResult.Translated("Hola", fromCache = true), result)
            assertTrue(delegate.requests.isEmpty())
        }

    @Test
    fun `the clock's now reaches both cache calls`() =
        runTest {
            val recording = RecordingCache(cache)
            val cached = PersistentCachingTranslationProvider(delegate, recording, clock = { now })
            delegate.translations["Hello"] = "Hola"

            cached.translate("Hello") // miss: one read, one write
            now = 2_500L
            cached.translate("Hello") // hit: one read, no write

            assertEquals(listOf(1_000L, 2_500L), recording.readTimes)
            assertEquals(listOf(1_000L), recording.writeTimes)
        }

    @Test
    fun `offline is not stored and a later call retries`() =
        runTest {
            delegate.translations["Hello"] = "Hola"
            delegate.nextResult = TranslationResult.Offline

            assertEquals(TranslationResult.Offline, provider.translate("Hello"))
            assertNull(cache.translationOf("Hello", now))

            assertEquals(TranslationResult.Translated("Hola", fromCache = false), provider.translate("Hello"))
            assertEquals(listOf("Hello", "Hello"), delegate.requests)
        }

    @Test
    fun `unavailable is not stored and a later call retries`() =
        runTest {
            delegate.translations["Hello"] = "Hola"
            delegate.nextResult = TranslationResult.Unavailable("no bridge connected")

            assertEquals(TranslationResult.Unavailable("no bridge connected"), provider.translate("Hello"))
            assertNull(cache.translationOf("Hello", now))

            assertEquals(TranslationResult.Translated("Hola", fromCache = false), provider.translate("Hello"))
            assertEquals(listOf("Hello", "Hello"), delegate.requests)
        }

    @Test
    fun `a pair other than english to spanish bypasses the cache`() =
        runTest {
            delegate.translations["Hola"] = "Hello"

            provider.translate("Hola", SpeechLanguage.ES, SpeechLanguage.EN)
            provider.translate("Hola", SpeechLanguage.ES, SpeechLanguage.EN)

            assertEquals(2, delegate.requests.size)
            assertNull(cache.translationOf("Hola", now))
        }

    @Test
    fun `the stored key keeps its case`() =
        runTest {
            delegate.translations["Hello"] = "Hola"
            delegate.translations["hello"] = "hola"
            provider.translate("Hello")

            val second = provider.translate("hello")

            // The repository normalizes whitespace only, so this is another line, not a hit.
            assertEquals(TranslationResult.Translated("hola", fromCache = false), second)
            assertEquals(listOf("Hello", "hello"), delegate.requests)
        }

    @Test
    fun `padding around a line is the same row`() =
        runTest {
            delegate.translations["Hello there"] = "Hola"
            provider.translate("Hello there")

            val second = provider.translate("  Hello \t there\n")

            assertEquals(TranslationResult.Translated("Hola", fromCache = true), second)
            assertEquals(listOf("Hello there"), delegate.requests)
        }

    @Test
    fun `blank text and same language reach neither the delegate nor the cache`() =
        runTest {
            assertEquals(TranslationResult.Translated("", fromCache = true), provider.translate("  "))
            assertEquals(
                TranslationResult.Translated("Hello", fromCache = true),
                provider.translate(" Hello ", SpeechLanguage.EN, SpeechLanguage.EN),
            )

            assertTrue(delegate.requests.isEmpty())
            assertNull(cache.translationOf("Hello", now))
        }

    @Test
    fun `the clock's now decides when a stored line goes stale`() =
        runTest {
            val ageing = InMemoryTranslationCacheRepository(CachePruningPolicy(maxRows = 5_000, maxAgeDays = 90))
            val cached = PersistentCachingTranslationProvider(delegate, ageing, clock = { now })
            delegate.translations["Old line"] = "Vieja"
            delegate.translations["New line"] = "Nueva"
            cached.translate("Old line")

            now += 91L * 24 * 60 * 60 * 1000
            cached.translate("New line") // writing prunes, and the older row is now past the limit

            assertEquals(TranslationResult.Translated("Vieja", fromCache = false), cached.translate("Old line"))
            assertEquals(listOf("Old line", "New line", "Old line"), delegate.requests)
        }

    @Test
    fun `a delegate that throws becomes unavailable and stores nothing`() =
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
            val cached = PersistentCachingTranslationProvider(throwing, cache, clock = { now })

            val result = cached.translate("Hello")

            assertTrue(result is TranslationResult.Unavailable)
            assertNull(cache.translationOf("Hello", now))
        }

    @Test
    fun `a cache that cannot be read is a miss, not a failure`() =
        runTest {
            val unreadable =
                object : TranslationCacheRepository {
                    override suspend fun translationOf(
                        sourceText: String,
                        now: Long,
                    ): String? = throw IllegalStateException("disk gone")

                    override suspend fun store(
                        sourceText: String,
                        translationEs: String,
                        now: Long,
                    ) = Unit
                }
            delegate.translations["Hello"] = "Hola"
            val cached = PersistentCachingTranslationProvider(delegate, unreadable, clock = { now })

            val result = cached.translate("Hello")

            assertEquals(TranslationResult.Translated("Hola", fromCache = false), result)
        }

    @Test
    fun `a cache that cannot be written still hands the translation back`() =
        runTest {
            val unwritable =
                object : TranslationCacheRepository {
                    override suspend fun translationOf(
                        sourceText: String,
                        now: Long,
                    ): String? = null

                    override suspend fun store(
                        sourceText: String,
                        translationEs: String,
                        now: Long,
                    ) = throw IllegalStateException("disk full")
                }
            delegate.translations["Hello"] = "Hola"
            val cached = PersistentCachingTranslationProvider(delegate, unwritable, clock = { now })

            val result = cached.translate("Hello")

            assertEquals(TranslationResult.Translated("Hola", fromCache = false), result)
            assertEquals(listOf("Hello"), delegate.requests)
        }

    @Test
    fun `the null provider behind the cache stays unavailable and stores nothing`() =
        runTest {
            val cachedNull = PersistentCachingTranslationProvider(NullTranslationProvider, cache, clock = { now })

            val result = cachedNull.translate("Hi")

            assertEquals("none", cachedNull.id)
            assertEquals(TranslationResult.Unavailable("no translation provider configured"), result)
            assertNull(cache.translationOf("Hi", now))
        }

    /** Records the `now` the provider hands the repository, to see the injected clock arrive. */
    private class RecordingCache(
        private val wrapped: TranslationCacheRepository,
    ) : TranslationCacheRepository {
        val readTimes = mutableListOf<Long>()
        val writeTimes = mutableListOf<Long>()

        override suspend fun translationOf(
            sourceText: String,
            now: Long,
        ): String? {
            readTimes += now
            return wrapped.translationOf(sourceText, now)
        }

        override suspend fun store(
            sourceText: String,
            translationEs: String,
            now: Long,
        ) {
            writeTimes += now
            wrapped.store(sourceText, translationEs, now)
        }
    }
}
