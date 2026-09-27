package com.teachermovies.core.repo

import com.teachermovies.core.model.SubtitleFetch
import com.teachermovies.core.model.SubtitleFetchState
import com.teachermovies.core.model.TorrentId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Scenarios every [SubtitleFetchRepository] implementation must satisfy, run against both
 * `RoomSubtitleFetchRepository` (Robolectric, real Room) and `InMemorySubtitleFetchRepository` (plain
 * JVM). Every `now` is an explicit value: neither implementation may look at a real clock.
 */
abstract class SubtitleFetchRepositoryContractTest {
    /** Builds the repository under test. Called once per `@Test`, from [setUp]. */
    protected abstract fun createRepository(): SubtitleFetchRepository

    /** Released after each test; a no-op unless the implementation holds a resource (e.g. Room). */
    protected open fun releaseRepository() {}

    private lateinit var repository: SubtitleFetchRepository

    @Before
    fun setUp() {
        repository = createRepository()
    }

    @After
    fun tearDown() {
        releaseRepository()
    }

    private fun id(seed: Char): TorrentId = TorrentId(seed.toString().repeat(40))

    private fun fetch(
        seed: Char,
        language: String = "en",
        state: SubtitleFetchState = SubtitleFetchState.Pending,
        movieHash: String? = "moviehash-$seed",
        localPath: String? = null,
        variantLabel: String? = null,
        attempts: Int = 0,
        lastAttemptEpochMs: Long? = null,
        nextRetryEpochMs: Long? = null,
        errorMessage: String? = null,
        updatedAtEpochMs: Long = 1_000L,
    ) = SubtitleFetch(
        torrentId = id(seed),
        language = language,
        state = state,
        movieHash = movieHash,
        localPath = localPath,
        variantLabel = variantLabel,
        attempts = attempts,
        lastAttemptEpochMs = lastAttemptEpochMs,
        nextRetryEpochMs = nextRetryEpochMs,
        errorMessage = errorMessage,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun subPath(
        seed: Char,
        language: String,
    ): String = "/storage/Movies/$seed/subs/movie.$language.srt"

    @Test
    fun getUnknownMovieReturnsNull() =
        runTest {
            assertNull(repository.get(id('a'), "en"))
            assertEquals(emptyList<SubtitleFetch>(), repository.observeAll().first())
        }

    @Test
    fun saveThenGetRoundTripsEveryField() =
        runTest {
            val row =
                fetch(
                    seed = 'a',
                    language = "es",
                    state = SubtitleFetchState.Downloaded,
                    movieHash = "moviehash-a",
                    localPath = subPath('a', "es"),
                    variantLabel = "latino",
                    attempts = 3,
                    lastAttemptEpochMs = 4_000L,
                    nextRetryEpochMs = 5_000L,
                    errorMessage = "an earlier search had failed",
                    updatedAtEpochMs = 6_000L,
                )

            repository.save(row)

            val stored = repository.get(id('a'), "es")
            assertEquals(row, stored)
            assertEquals("moviehash-a", stored?.movieHash)
            assertEquals("latino", stored?.variantLabel)
            assertEquals("an earlier search had failed", stored?.errorMessage)
            assertEquals(5_000L, stored?.nextRetryEpochMs)
        }

    @Test
    fun twoLanguagesOfOneMovieAreTwoRows() =
        runTest {
            val english = fetch('a', language = "en", updatedAtEpochMs = 1_000L)
            val spanish = fetch('a', language = "es", updatedAtEpochMs = 2_000L)

            repository.save(english)
            repository.save(spanish)

            assertEquals(english, repository.get(id('a'), "en"))
            assertEquals(spanish, repository.get(id('a'), "es"))
            assertEquals(listOf(spanish, english), repository.observeAll().first())
        }

    @Test
    fun saveReplacesTheRowOfTheSameMovieAndLanguage() =
        runTest {
            repository.save(fetch('a', updatedAtEpochMs = 1_000L))
            val searching = fetch('a').searchingAt(2_000L)

            repository.save(searching)

            assertEquals(listOf(searching), repository.observeAll().first())
        }

    @Test
    fun observeAllIsOrderedMostRecentlyUpdatedFirst() =
        runTest {
            val oldest = fetch('a', updatedAtEpochMs = 1_000L)
            val newest = fetch('b', updatedAtEpochMs = 3_000L)
            val middle = fetch('c', updatedAtEpochMs = 2_000L)

            repository.save(oldest)
            repository.save(newest)
            repository.save(middle)

            assertEquals(listOf(newest, middle, oldest), repository.observeAll().first())
        }

    @Test
    fun dueForFetchSkipsADownloadedAndAnInFlightRow() =
        runTest {
            val pending = fetch('a', state = SubtitleFetchState.Pending, updatedAtEpochMs = 1_000L)
            val failed = fetch('b', state = SubtitleFetchState.Failed, updatedAtEpochMs = 2_000L)
            val downloaded = fetch('c', state = SubtitleFetchState.Downloaded, updatedAtEpochMs = 3_000L)
            val searching = fetch('d', state = SubtitleFetchState.Searching, updatedAtEpochMs = 4_000L)

            repository.save(pending)
            repository.save(failed)
            repository.save(downloaded)
            repository.save(searching)

            // Most recently updated first, and only the two rows `SubtitleFetch.isDue` accepts.
            assertEquals(listOf(failed, pending), repository.dueForFetch(5_000L))
        }

    @Test
    fun dueForFetchIsOrderedMostRecentlyUpdatedFirst() =
        runTest {
            val oldest = fetch('a', updatedAtEpochMs = 1_000L)
            val newest = fetch('b', updatedAtEpochMs = 3_000L)
            val middle = fetch('c', updatedAtEpochMs = 2_000L)

            repository.save(oldest)
            repository.save(newest)
            repository.save(middle)

            assertEquals(listOf(newest, middle, oldest), repository.dueForFetch(9_000L))
        }

    @Test
    fun dueForFetchWaitsOutTheNotFoundRetryClock() =
        runTest {
            val notFound = SubtitleFetch.pending(id('a'), "en", "moviehash-a", 1_000L).notFoundAt(1_000L)
            repository.save(notFound)
            val retryAt = 1_000L + SubtitleFetch.NOT_FOUND_RETRY_MS

            assertEquals(retryAt, notFound.nextRetryEpochMs)
            assertEquals(emptyList<SubtitleFetch>(), repository.dueForFetch(retryAt - 1L))
            assertEquals(listOf(notFound), repository.dueForFetch(retryAt))
            assertEquals(listOf(notFound), repository.dueForFetch(retryAt + 1_000L))
        }

    @Test
    fun ensurePendingCreatesAFreshRowWhenTheMovieHasNone() =
        runTest {
            val created = repository.ensurePending(id('a'), "en", movieHash = "moviehash-a", now = 1_000L)

            assertEquals(SubtitleFetch.pending(id('a'), "en", "moviehash-a", 1_000L), created)
            assertEquals(created, repository.get(id('a'), "en"))
            assertEquals(listOf(created), repository.observeAll().first())
            assertEquals(listOf(created), repository.dueForFetch(1_000L))
        }

    @Test
    fun ensurePendingCreatesAFreshRowWithoutAMovieHashWhenTheCallerHasNone() =
        runTest {
            val created = repository.ensurePending(id('a'), "en", movieHash = null, now = 1_000L)

            assertNull(created.movieHash)
            assertEquals(created, repository.get(id('a'), "en"))
        }

    @Test
    fun ensurePendingLeavesADownloadedRowAlone() =
        runTest {
            val downloaded = fetch('a').downloadedAt(2_000L, subPath('a', "en"), variant = "latino")
            repository.save(downloaded)

            val ensured = repository.ensurePending(id('a'), "en", movieHash = "moviehash-a", now = 9_000L)

            assertEquals(downloaded, ensured)
            assertEquals(downloaded, repository.get(id('a'), "en"))
            assertEquals(emptyList<SubtitleFetch>(), repository.dueForFetch(9_000L))
        }

    @Test
    fun ensurePendingLeavesAnInFlightAndANotFoundRowAlone() =
        runTest {
            val searching = fetch('a').searchingAt(2_000L)
            val notFound = fetch('b').notFoundAt(2_000L)
            repository.save(searching)
            repository.save(notFound)

            assertEquals(searching, repository.ensurePending(id('a'), "en", "moviehash-a", 3_000L))
            assertEquals(notFound, repository.ensurePending(id('b'), "en", "moviehash-b", 3_000L))
        }

    @Test
    fun ensurePendingFillsInOnlyAMissingMovieHash() =
        runTest {
            val failed =
                fetch(
                    seed = 'a',
                    movieHash = null,
                    state = SubtitleFetchState.Failed,
                    attempts = 2,
                    lastAttemptEpochMs = 2_000L,
                    nextRetryEpochMs = 7_000L,
                    errorMessage = "the laptop was offline",
                    updatedAtEpochMs = 3_000L,
                )
            repository.save(failed)

            val ensured = repository.ensurePending(id('a'), "en", movieHash = "moviehash-a", now = 9_000L)

            assertEquals(failed.copy(movieHash = "moviehash-a"), ensured)
            assertEquals(ensured, repository.get(id('a'), "en"))
            // Only the hash moved: the state, the attempts and the retry clock are untouched.
            assertEquals(SubtitleFetchState.Failed, ensured.state)
            assertEquals(2, ensured.attempts)
            assertEquals(2_000L, ensured.lastAttemptEpochMs)
            assertEquals(7_000L, ensured.nextRetryEpochMs)
            assertEquals("the laptop was offline", ensured.errorMessage)
            assertEquals(3_000L, ensured.updatedAtEpochMs)
        }

    @Test
    fun ensurePendingKeepsTheMovieHashARowAlreadyHas() =
        runTest {
            val existing = fetch('a', movieHash = "stored-hash", updatedAtEpochMs = 1_000L)
            repository.save(existing)

            val ensured = repository.ensurePending(id('a'), "en", movieHash = "another-hash", now = 9_000L)

            assertEquals(existing, ensured)
            assertEquals("stored-hash", repository.get(id('a'), "en")?.movieHash)
        }

    @Test
    fun ensurePendingOfOneLanguageLeavesTheOtherAlone() =
        runTest {
            val spanish = fetch('a', language = "es", updatedAtEpochMs = 1_000L)
            repository.save(spanish)

            val english = repository.ensurePending(id('a'), "en", movieHash = "moviehash-a", now = 2_000L)

            assertEquals(listOf(english, spanish), repository.observeAll().first())
        }

    @Test
    fun deleteRemovesOnlyThatLanguageOfTheMovie() =
        runTest {
            val english = fetch('a', language = "en", updatedAtEpochMs = 1_000L)
            val spanish = fetch('a', language = "es", updatedAtEpochMs = 2_000L)
            repository.save(english)
            repository.save(spanish)

            repository.delete(id('a'), "en")

            assertNull(repository.get(id('a'), "en"))
            assertEquals(spanish, repository.get(id('a'), "es"))
            assertEquals(listOf(spanish), repository.observeAll().first())
        }

    @Test
    fun deleteOfAnUnknownRowIsANoOp() =
        runTest {
            val english = fetch('a', language = "en", updatedAtEpochMs = 1_000L)
            repository.save(english)

            repository.delete(id('a'), "es")
            repository.delete(id('b'), "en")

            assertEquals(listOf(english), repository.observeAll().first())
        }

    @Test
    fun deleteForTorrentRemovesEveryLanguageOfThatMovieOnly() =
        runTest {
            val english = fetch('a', language = "en", updatedAtEpochMs = 1_000L)
            val spanish = fetch('a', language = "es", updatedAtEpochMs = 2_000L)
            val otherMovie = fetch('b', language = "en", updatedAtEpochMs = 3_000L)
            repository.save(english)
            repository.save(spanish)
            repository.save(otherMovie)

            repository.deleteForTorrent(id('a'))

            assertNull(repository.get(id('a'), "en"))
            assertNull(repository.get(id('a'), "es"))
            assertEquals(listOf(otherMovie), repository.observeAll().first())
            assertEquals(listOf(otherMovie), repository.dueForFetch(9_000L))
        }
}
