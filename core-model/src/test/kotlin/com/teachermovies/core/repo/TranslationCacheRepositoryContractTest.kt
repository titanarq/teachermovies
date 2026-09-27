package com.teachermovies.core.repo

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Scenarios every [TranslationCacheRepository] implementation must satisfy, run against both
 * `RoomTranslationCacheRepository` (Robolectric, real Room) and `InMemoryTranslationCacheRepository`
 * (plain JVM). Everything here goes through the interface, so no implementation detail leaks in; the
 * bookkeeping a read cannot show -- `createdAtEpochMs`, `hitCount`, how many rows are really left --
 * is asserted where the row is reachable, in `RoomTranslationCacheRepositoryTest`.
 *
 * [now] is always an explicit value and never the clock: a test that cannot age a row out on purpose
 * cannot test [CachePruningPolicy] at all.
 */
abstract class TranslationCacheRepositoryContractTest {
    /** Builds the repository under test, bounded by [pruning]. Called once per test, from [setUp]. */
    protected abstract fun createRepository(pruning: CachePruningPolicy): TranslationCacheRepository

    /** Released after each test; a no-op unless the implementation holds a resource (e.g. Room). */
    protected open fun releaseRepository() {}

    /** One day in milliseconds, for the policies below whose `maxAgeDays` is 1. */
    protected val dayMs = 24L * 60 * 60 * 1000L

    private lateinit var repository: TranslationCacheRepository

    @Before
    fun setUp() {
        repository = createRepository(CachePruningPolicy())
    }

    @After
    fun tearDown() {
        releaseRepository()
    }

    @Test
    fun storeThenReadReturnsTheTranslation() =
        runTest {
            repository.store("hello world", "hola mundo", now = 1_000L)

            assertEquals("hola mundo", repository.translationOf("hello world", now = 2_000L))
        }

    @Test
    fun aLineNobodyStoredIsAMiss() =
        runTest {
            repository.store("hello world", "hola mundo", now = 1_000L)

            assertNull(repository.translationOf("goodbye", now = 2_000L))
        }

    @Test
    fun reStoringOneLineReplacesItsTranslation() =
        runTest {
            repository.store("hello world", "hola mundo", now = 1_000L)

            repository.store("hello world", "hola, mundo", now = 2_000L)

            assertEquals("hola, mundo", repository.translationOf("hello world", now = 3_000L))
        }

    @Test
    fun paddingAndWhitespaceRunsAreNotPartOfTheKey() =
        runTest {
            repository.store("  hello   world \n", "hola mundo", now = 1_000L)

            assertEquals("hola mundo", repository.translationOf("hello world", now = 2_000L))
            assertEquals("hola mundo", repository.translationOf("\nhello\tworld   ", now = 3_000L))
        }

    @Test
    fun twoSpellingsOfOneLineAreOneRowUnderTheCap() =
        runTest {
            val capped = createRepository(CachePruningPolicy(maxRows = 2, maxAgeDays = 90))
            capped.store("one", "uno", now = 1_000L)
            capped.store("  hello   world ", "hola mundo", now = 2_000L)

            // The same key as the row above, so it replaces it: still two rows, and "one" survives.
            capped.store("hello world", "hola, mundo", now = 3_000L)

            assertEquals("uno", capped.translationOf("one", now = 4_000L))
            assertEquals("hola, mundo", capped.translationOf("hello world", now = 5_000L))
        }

    @Test
    fun aBlankTranslationIsNotStored() =
        runTest {
            repository.store("hello world", "   ", now = 1_000L)

            assertNull(repository.translationOf("hello world", now = 2_000L))
        }

    @Test
    fun aBlankSourceTextStoresNothingAndPrunesNothing() =
        runTest {
            val capped = createRepository(CachePruningPolicy(maxRows = 1, maxAgeDays = 90))
            capped.store("hello world", "hola mundo", now = 1_000L)

            capped.store("   \n ", "otra cosa", now = 2_000L)

            // Were the blank key stored, it would be the newest row and "hello world" would be gone.
            assertEquals("hola mundo", capped.translationOf("hello world", now = 3_000L))
        }

    @Test
    fun readingABlankSourceTextIsAMiss() =
        runTest {
            repository.store("hello world", "hola mundo", now = 1_000L)

            assertNull(repository.translationOf("  ", now = 2_000L))
            assertEquals("hola mundo", repository.translationOf("hello world", now = 2_000L))
        }

    @Test
    fun theLeastRecentlyUsedRowGoesWhenTheCacheIsOverItsCap() =
        runTest {
            val capped = createRepository(CachePruningPolicy(maxRows = 2, maxAgeDays = 90))
            capped.store("one", "uno", now = 1_000L)
            capped.store("two", "dos", now = 2_000L)

            capped.store("three", "tres", now = 3_000L)

            assertNull(capped.translationOf("one", now = 4_000L))
            assertEquals("dos", capped.translationOf("two", now = 4_000L))
            assertEquals("tres", capped.translationOf("three", now = 4_000L))
        }

    @Test
    fun aRowLastUsedExactlyAtTheAgeLimitSurvivesThePrune() =
        runTest {
            val oneDay = createRepository(CachePruningPolicy(maxRows = 10, maxAgeDays = 1))
            oneDay.store("hello world", "hola mundo", now = 1_000L)

            oneDay.store("goodbye", "adios", now = 1_000L + dayMs)

            assertEquals("hola mundo", oneDay.translationOf("hello world", now = 1_000L))
            assertEquals("adios", oneDay.translationOf("goodbye", now = 1_000L + dayMs))
        }

    @Test
    fun aRowLastUsedBeforeTheAgeLimitGoesOnTheNextWrite() =
        runTest {
            val oneDay = createRepository(CachePruningPolicy(maxRows = 10, maxAgeDays = 1))
            oneDay.store("hello world", "hola mundo", now = 1_000L)

            oneDay.store("goodbye", "adios", now = 1_000L + dayMs + 1L)

            assertNull(oneDay.translationOf("hello world", now = 1_000L + dayMs + 1L))
            assertEquals("adios", oneDay.translationOf("goodbye", now = 1_000L + dayMs + 1L))
        }

    @Test
    fun aReadMakesItsRowTheNewestSoItSurvivesTheCap() =
        runTest {
            val capped = createRepository(CachePruningPolicy(maxRows = 2, maxAgeDays = 90))
            capped.store("one", "uno", now = 1_000L)
            capped.store("two", "dos", now = 2_000L)

            // "one" was stored first but is asked for again, so "two" is now the least recent one.
            assertEquals("uno", capped.translationOf("one", now = 3_000L))
            capped.store("three", "tres", now = 4_000L)

            assertEquals("uno", capped.translationOf("one", now = 5_000L))
            assertNull(capped.translationOf("two", now = 5_000L))
            assertEquals("tres", capped.translationOf("three", now = 5_000L))
        }

    @Test
    fun aStoreThatStoresNothingDoesNotPrune() =
        runTest {
            val oneDay = createRepository(CachePruningPolicy(maxRows = 10, maxAgeDays = 1))
            oneDay.store("hello world", "hola mundo", now = 1_000L)

            // An empty answer is not a success: no row is written, and nothing cached is pruned.
            oneDay.store("goodbye", "", now = 1_000L + dayMs + 1L)

            assertEquals("hola mundo", oneDay.translationOf("hello world", now = 2_000L))
        }
}
