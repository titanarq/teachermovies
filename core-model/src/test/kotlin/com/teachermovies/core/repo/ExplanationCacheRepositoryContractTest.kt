package com.teachermovies.core.repo

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Scenarios every [ExplanationCacheRepository] implementation must satisfy, run against both
 * `RoomExplanationCacheRepository` (Robolectric, real Room) and
 * `InMemoryExplanationCacheRepository` (plain JVM). Everything here goes through the interface, so no
 * implementation detail leaks in; the bookkeeping a read cannot show -- `createdAtEpochMs`,
 * `hitCount`, how many rows are really left -- is asserted where the row is reachable, in
 * `RoomExplanationCacheRepositoryTest`.
 *
 * [now] is always an explicit value and never the clock: a test that cannot age a row out on purpose
 * cannot test [CachePruningPolicy] at all. The payload is opaque JSON to `:core-model`, so the tests
 * only ever compare it byte for byte with what was stored.
 */
abstract class ExplanationCacheRepositoryContractTest {
    /** Builds the repository under test, bounded by [pruning]. Called once per test, from [setUp]. */
    protected abstract fun createRepository(pruning: CachePruningPolicy): ExplanationCacheRepository

    /** Released after each test; a no-op unless the implementation holds a resource (e.g. Room). */
    protected open fun releaseRepository() {}

    /** One day in milliseconds, for the policies below whose `maxAgeDays` is 1. */
    protected val dayMs = 24L * 60 * 60 * 1000L

    private lateinit var repository: ExplanationCacheRepository

    @Before
    fun setUp() {
        repository = createRepository(CachePruningPolicy())
    }

    @After
    fun tearDown() {
        releaseRepository()
    }

    /** A payload the tests can tell apart by [seed]; its shape means nothing to `:core-model`. */
    private fun explanation(seed: String): String = "{\"explanation\":\"$seed\"}"

    @Test
    fun storeThenReadReturnsTheExplanation() =
        runTest {
            repository.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            assertEquals(
                explanation("first"),
                repository.explanationFor("The Room", "hello world", "1", now = 2_000L),
            )
        }

    @Test
    fun aLineNobodyStoredIsAMiss() =
        runTest {
            repository.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            assertNull(repository.explanationFor("The Room", "goodbye", "1", now = 2_000L))
        }

    @Test
    fun theSameLineInAnotherFilmIsAMiss() =
        runTest {
            repository.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            assertNull(repository.explanationFor("Oldboy", "hello world", "1", now = 2_000L))
        }

    @Test
    fun theSameLineUnderAnotherPromptVersionIsAMiss() =
        runTest {
            repository.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            assertNull(repository.explanationFor("The Room", "hello world", "2", now = 2_000L))
        }

    @Test
    fun twoPromptVersionsOfOneLineAreTwoExplanations() =
        runTest {
            repository.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            // A new prompt version starts fresh answers instead of overwriting the older ones (#292).
            repository.store("The Room", "hello world", "2", explanation("second"), now = 2_000L)

            assertEquals(
                explanation("first"),
                repository.explanationFor("The Room", "hello world", "1", now = 3_000L),
            )
            assertEquals(
                explanation("second"),
                repository.explanationFor("The Room", "hello world", "2", now = 3_000L),
            )
        }

    @Test
    fun reStoringOneLineReplacesItsExplanation() =
        runTest {
            repository.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            repository.store("The Room", "hello world", "1", explanation("second"), now = 2_000L)

            assertEquals(
                explanation("second"),
                repository.explanationFor("The Room", "hello world", "1", now = 3_000L),
            )
        }

    @Test
    fun paddingAndWhitespaceRunsAreNotPartOfAnyKeyPart() =
        runTest {
            repository.store("  The   Room \n", "\thello   world ", "  1 ", explanation("first"), now = 1_000L)

            assertEquals(
                explanation("first"),
                repository.explanationFor("The Room", "hello world", "1", now = 2_000L),
            )
            assertEquals(
                explanation("first"),
                repository.explanationFor("\nThe\tRoom  ", "  hello world\n", "1", now = 3_000L),
            )
        }

    @Test
    fun twoSpellingsOfOneLineAreOneRowUnderTheCap() =
        runTest {
            val capped = createRepository(CachePruningPolicy(maxRows = 2, maxAgeDays = 90))
            capped.store("The Room", "one", "1", explanation("one"), now = 1_000L)
            capped.store("  The   Room ", "  hello   world ", "1", explanation("two"), now = 2_000L)

            // The same key as the row above, so it replaces it: still two rows, and "one" survives.
            capped.store("The Room", "hello world", "1", explanation("three"), now = 3_000L)

            assertEquals(explanation("one"), capped.explanationFor("The Room", "one", "1", now = 4_000L))
            assertEquals(
                explanation("three"),
                capped.explanationFor("The Room", "hello world", "1", now = 5_000L),
            )
        }

    @Test
    fun aBlankExplanationIsNotStored() =
        runTest {
            repository.store("The Room", "hello world", "1", "   ", now = 1_000L)

            assertNull(repository.explanationFor("The Room", "hello world", "1", now = 2_000L))
        }

    @Test
    fun aBlankTitleOrLineStoresNothingAndPrunesNothing() =
        runTest {
            val capped = createRepository(CachePruningPolicy(maxRows = 1, maxAgeDays = 90))
            capped.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            capped.store("  ", "hello world", "1", explanation("second"), now = 2_000L)
            capped.store("The Room", "\n  ", "1", explanation("third"), now = 3_000L)

            // Were either blank key stored it would be the newest row, and the real one would be gone.
            assertEquals(
                explanation("first"),
                capped.explanationFor("The Room", "hello world", "1", now = 4_000L),
            )
        }

    @Test
    fun aBlankPromptVersionIsAKeyPartLikeAnyOther() =
        runTest {
            repository.store("The Room", "hello world", "", explanation("unversioned"), now = 1_000L)

            assertEquals(
                explanation("unversioned"),
                repository.explanationFor("The Room", "hello world", "", now = 2_000L),
            )
            assertNull(repository.explanationFor("The Room", "hello world", "1", now = 2_000L))
        }

    @Test
    fun readingABlankTitleOrLineIsAMiss() =
        runTest {
            repository.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            assertNull(repository.explanationFor("  ", "hello world", "1", now = 2_000L))
            assertNull(repository.explanationFor("The Room", "\n ", "1", now = 2_000L))
            assertEquals(
                explanation("first"),
                repository.explanationFor("The Room", "hello world", "1", now = 3_000L),
            )
        }

    @Test
    fun theLeastRecentlyUsedRowGoesWhenTheCacheIsOverItsCap() =
        runTest {
            val capped = createRepository(CachePruningPolicy(maxRows = 2, maxAgeDays = 90))
            capped.store("The Room", "one", "1", explanation("one"), now = 1_000L)
            capped.store("The Room", "two", "1", explanation("two"), now = 2_000L)

            capped.store("The Room", "three", "1", explanation("three"), now = 3_000L)

            assertNull(capped.explanationFor("The Room", "one", "1", now = 4_000L))
            assertEquals(explanation("two"), capped.explanationFor("The Room", "two", "1", now = 4_000L))
            assertEquals(
                explanation("three"),
                capped.explanationFor("The Room", "three", "1", now = 4_000L),
            )
        }

    @Test
    fun aRowLastUsedExactlyAtTheAgeLimitSurvivesThePrune() =
        runTest {
            val oneDay = createRepository(CachePruningPolicy(maxRows = 10, maxAgeDays = 1))
            oneDay.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            oneDay.store("The Room", "goodbye", "1", explanation("second"), now = 1_000L + dayMs)

            assertEquals(
                explanation("first"),
                oneDay.explanationFor("The Room", "hello world", "1", now = 1_000L),
            )
            assertEquals(
                explanation("second"),
                oneDay.explanationFor("The Room", "goodbye", "1", now = 1_000L + dayMs),
            )
        }

    @Test
    fun aRowLastUsedBeforeTheAgeLimitGoesOnTheNextWrite() =
        runTest {
            val oneDay = createRepository(CachePruningPolicy(maxRows = 10, maxAgeDays = 1))
            oneDay.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            oneDay.store("The Room", "goodbye", "1", explanation("second"), now = 1_000L + dayMs + 1L)

            assertNull(oneDay.explanationFor("The Room", "hello world", "1", now = 1_000L + dayMs + 1L))
            assertEquals(
                explanation("second"),
                oneDay.explanationFor("The Room", "goodbye", "1", now = 1_000L + dayMs + 1L),
            )
        }

    @Test
    fun aReadMakesItsRowTheNewestSoItSurvivesTheCap() =
        runTest {
            val capped = createRepository(CachePruningPolicy(maxRows = 2, maxAgeDays = 90))
            capped.store("The Room", "one", "1", explanation("one"), now = 1_000L)
            capped.store("The Room", "two", "1", explanation("two"), now = 2_000L)

            // "one" was stored first but is asked for again, so "two" is now the least recent one.
            assertEquals(explanation("one"), capped.explanationFor("The Room", "one", "1", now = 3_000L))
            capped.store("The Room", "three", "1", explanation("three"), now = 4_000L)

            assertEquals(explanation("one"), capped.explanationFor("The Room", "one", "1", now = 5_000L))
            assertNull(capped.explanationFor("The Room", "two", "1", now = 5_000L))
            assertEquals(
                explanation("three"),
                capped.explanationFor("The Room", "three", "1", now = 5_000L),
            )
        }

    @Test
    fun aStoreThatStoresNothingDoesNotPrune() =
        runTest {
            val oneDay = createRepository(CachePruningPolicy(maxRows = 10, maxAgeDays = 1))
            oneDay.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            // An empty answer is not a success: no row is written, and nothing cached is pruned.
            oneDay.store("The Room", "goodbye", "1", "", now = 1_000L + dayMs + 1L)

            assertEquals(
                explanation("first"),
                oneDay.explanationFor("The Room", "hello world", "1", now = 2_000L),
            )
        }
}
