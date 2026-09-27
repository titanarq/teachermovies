package com.teachermovies.core.repo

import com.teachermovies.core.model.SubtitleAlignment
import com.teachermovies.core.model.TorrentId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Scenarios every [SubtitleAlignmentRepository] implementation must satisfy, run against both
 * `RoomSubtitleAlignmentRepository` (Robolectric, real Room) and
 * `InMemorySubtitleAlignmentRepository` (plain JVM). One row per movie and Spanish file, so most of
 * it is about [SubtitleAlignmentRepository.bestFor] picking the better-scored file of one movie and
 * never mixing two movies up.
 */
abstract class SubtitleAlignmentRepositoryContractTest {
    /** Builds the repository under test. Called once per `@Test`, from [setUp]. */
    protected abstract fun createRepository(): SubtitleAlignmentRepository

    /** Released after each test; a no-op unless the implementation holds a resource (e.g. Room). */
    protected open fun releaseRepository() {}

    private lateinit var repository: SubtitleAlignmentRepository

    @Before
    fun setUp() {
        repository = createRepository()
    }

    @After
    fun tearDown() {
        releaseRepository()
    }

    private fun id(seed: Char): TorrentId = TorrentId(seed.toString().repeat(40))

    private fun subPath(
        seed: Char,
        name: String = "es",
    ): String = "/storage/Movies/$seed/subs/movie.$name.srt"

    private fun alignment(
        seed: Char,
        subtitlePath: String = subPath(seed),
        offsetMs: Long = 0L,
        frameRateScale: Double = 1.0,
        qualityScore: Double = 0.5,
        computedAtEpochMs: Long = 1_000L,
    ) = SubtitleAlignment(
        torrentId = id(seed),
        subtitlePath = subtitlePath,
        offsetMs = offsetMs,
        frameRateScale = frameRateScale,
        qualityScore = qualityScore,
        computedAtEpochMs = computedAtEpochMs,
    )

    @Test
    fun unknownMovieAndUnknownFileReturnNull() =
        runTest {
            assertNull(repository.get(id('a'), subPath('a')))
            assertNull(repository.bestFor(id('a')))

            repository.save(alignment('a'))

            assertNull(repository.get(id('a'), subPath('a', "other")))
            assertEquals(alignment('a'), repository.bestFor(id('a')))
        }

    @Test
    fun saveThenGetRoundTripsEveryField() =
        runTest {
            val row =
                alignment(
                    seed = 'a',
                    subtitlePath = subPath('a', "es"),
                    offsetMs = -2_500L,
                    frameRateScale = 23.976 / 25,
                    qualityScore = 0.87,
                    computedAtEpochMs = 4_000L,
                )

            repository.save(row)

            val stored = repository.get(id('a'), row.subtitlePath)
            assertEquals(row, stored)
            assertEquals(-2_500L, stored?.offsetMs)
            assertEquals(23.976 / 25, stored?.frameRateScale)
            assertEquals(0.87, stored?.qualityScore)
            assertEquals(4_000L, stored?.computedAtEpochMs)
        }

    @Test
    fun bestForPicksTheHigherScoredFileOfOneMovie() =
        runTest {
            val worse = alignment('a', subPath('a', "es1"), qualityScore = 0.42, computedAtEpochMs = 2_000L)
            val better = alignment('a', subPath('a', "es2"), qualityScore = 0.87, computedAtEpochMs = 1_000L)
            val otherMovie = alignment('b', qualityScore = 0.99, computedAtEpochMs = 3_000L)

            repository.save(worse)
            repository.save(better)
            repository.save(otherMovie)

            // The score decides, not which alignment was computed last, and never another movie's.
            assertEquals(better, repository.bestFor(id('a')))
            assertEquals(otherMovie, repository.bestFor(id('b')))
        }

    @Test
    fun saveReplacesTheAlignmentOfTheSameFileInsteadOfStoringASecondOne() =
        runTest {
            val first = alignment('a', subPath('a', "es1"), qualityScore = 0.9)
            val second = alignment('a', subPath('a', "es2"), qualityScore = 0.5)
            repository.save(first)
            repository.save(second)

            val recomputed = first.copy(offsetMs = 1_500L, qualityScore = 0.2, computedAtEpochMs = 5_000L)
            repository.save(recomputed)

            assertEquals(recomputed, repository.get(id('a'), first.subtitlePath))
            // One row per file: had the old 0.9 row survived, it would still be the best.
            assertEquals(second, repository.bestFor(id('a')))
        }

    @Test
    fun deleteRemovesOnlyThatFileOfTheMovie() =
        runTest {
            val first = alignment('a', subPath('a', "es1"), qualityScore = 0.4)
            val second = alignment('a', subPath('a', "es2"), qualityScore = 0.8)
            repository.save(first)
            repository.save(second)

            repository.delete(id('a'), second.subtitlePath)

            assertNull(repository.get(id('a'), second.subtitlePath))
            assertEquals(first, repository.bestFor(id('a')))
        }

    @Test
    fun deleteOfAnUnknownFileIsANoOp() =
        runTest {
            val row = alignment('a')
            repository.save(row)

            repository.delete(id('a'), subPath('a', "other"))
            repository.delete(id('b'), row.subtitlePath)

            assertEquals(row, repository.bestFor(id('a')))
        }

    @Test
    fun deleteForTorrentRemovesEveryAlignmentOfThatMovieOnly() =
        runTest {
            val first = alignment('a', subPath('a', "es1"), qualityScore = 0.4)
            val second = alignment('a', subPath('a', "es2"), qualityScore = 0.8)
            val otherMovie = alignment('b', qualityScore = 0.3)
            repository.save(first)
            repository.save(second)
            repository.save(otherMovie)

            repository.deleteForTorrent(id('a'))

            assertNull(repository.get(id('a'), first.subtitlePath))
            assertNull(repository.get(id('a'), second.subtitlePath))
            assertNull(repository.bestFor(id('a')))
            assertEquals(otherMovie, repository.bestFor(id('b')))
        }
}
