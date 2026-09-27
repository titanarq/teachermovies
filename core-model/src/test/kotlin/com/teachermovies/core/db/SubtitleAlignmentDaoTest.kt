package com.teachermovies.core.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubtitleAlignmentDaoTest {
    private lateinit var db: TeacherMoviesDatabase
    private lateinit var dao: SubtitleAlignmentDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, TeacherMoviesDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.subtitleAlignmentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(
        infoHash: String,
        subtitlePath: String,
        offsetMs: Long = 250L,
        frameRateScale: Double = 1.0,
        qualityScore: Double = 0.9,
        computedAtEpochMs: Long = 1_000L,
    ) = SubtitleAlignmentEntity(
        infoHash = infoHash,
        subtitlePath = subtitlePath,
        offsetMs = offsetMs,
        frameRateScale = frameRateScale,
        qualityScore = qualityScore,
        computedAtEpochMs = computedAtEpochMs,
    )

    @Test
    fun upsertThenGetReturnsTheRow() =
        runTest {
            val row = entity("aaa", "/storage/Movies/aaa/subs/es.srt", offsetMs = -1_200L, frameRateScale = 1.001)
            dao.upsert(row)

            assertEquals(row, dao.get("aaa", "/storage/Movies/aaa/subs/es.srt"))
        }

    @Test
    fun getNeedsBothPartsOfTheKey() =
        runTest {
            val row = entity("aaa", "subs/es.srt")
            dao.upsert(row)

            assertEquals(row, dao.get("aaa", "subs/es.srt"))
            assertNull(dao.get("aaa", "subs/es-latino.srt"))
            assertNull(dao.get("bbb", "subs/es.srt"))
            assertNull(dao.get("missing", "missing"))
        }

    @Test
    fun upsertReplacesTheRowForTheSamePair() =
        runTest {
            dao.upsert(entity("aaa", "subs/es.srt", qualityScore = 0.95))
            val replacement = entity("aaa", "subs/es.srt", qualityScore = 0.5, offsetMs = 100L)
            dao.upsert(replacement)

            assertEquals(replacement, dao.get("aaa", "subs/es.srt"))
            // A leftover duplicate of the pair would still outscore the replacement here.
            assertEquals(replacement, dao.bestForTorrent("aaa"))
        }

    @Test
    fun bestForTorrentReturnsTheHighestScoredRowOfThatMovie() =
        runTest {
            dao.upsert(entity("aaa", "subs/es-low.srt", qualityScore = 0.40))
            val best = entity("aaa", "subs/es-best.srt", qualityScore = 0.95)
            dao.upsert(best)
            dao.upsert(entity("aaa", "subs/es-middle.srt", qualityScore = 0.70))

            assertEquals(best, dao.bestForTorrent("aaa"))
        }

    @Test
    fun bestForTorrentIgnoresOtherMoviesRows() =
        runTest {
            val best = entity("aaa", "subs/es.srt", qualityScore = 0.60)
            dao.upsert(best)
            val otherMovie = entity("bbb", "subs/es.srt", qualityScore = 1.0)
            dao.upsert(otherMovie)

            assertEquals(best, dao.bestForTorrent("aaa"))
            assertEquals(otherMovie, dao.bestForTorrent("bbb"))
        }

    @Test
    fun bestForTorrentUnknownMovieReturnsNull() =
        runTest {
            assertNull(dao.bestForTorrent("missing"))

            dao.upsert(entity("aaa", "subs/es.srt"))

            assertNull(dao.bestForTorrent("bbb"))
        }

    @Test
    fun deleteRemovesOnlyThatRow() =
        runTest {
            val doomed = entity("aaa", "subs/es-doomed.srt", qualityScore = 0.95)
            val kept = entity("aaa", "subs/es-kept.srt", qualityScore = 0.40)
            val otherMovie = entity("bbb", "subs/es.srt")
            dao.upsert(doomed)
            dao.upsert(kept)
            dao.upsert(otherMovie)

            dao.delete("aaa", "subs/es-doomed.srt")

            assertNull(dao.get("aaa", "subs/es-doomed.srt"))
            assertEquals(kept, dao.get("aaa", "subs/es-kept.srt"))
            assertEquals(kept, dao.bestForTorrent("aaa"))
            assertEquals(otherMovie, dao.bestForTorrent("bbb"))
        }

    @Test
    fun deleteForTorrentRemovesEveryRowOfOneMovieOnly() =
        runTest {
            dao.upsert(entity("aaa", "subs/es.srt"))
            dao.upsert(entity("aaa", "subs/es-latino.srt"))
            val otherMovie = entity("bbb", "subs/es.srt")
            dao.upsert(otherMovie)

            dao.deleteForTorrent("aaa")

            assertNull(dao.get("aaa", "subs/es.srt"))
            assertNull(dao.get("aaa", "subs/es-latino.srt"))
            assertNull(dao.bestForTorrent("aaa"))
            assertEquals(otherMovie, dao.get("bbb", "subs/es.srt"))
        }
}
