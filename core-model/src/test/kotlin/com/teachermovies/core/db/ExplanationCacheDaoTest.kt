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
class ExplanationCacheDaoTest {
    private lateinit var db: TeacherMoviesDatabase
    private lateinit var dao: ExplanationCacheDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, TeacherMoviesDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.explanationCacheDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(
        movieTitle: String,
        line: String,
        promptVersion: String = "v1",
        explanationJson: String = """{"answer":"$line"}""",
        createdAtEpochMs: Long = 1_000L,
        lastUsedAtEpochMs: Long = 1_000L,
        hitCount: Int = 0,
    ) = ExplanationCacheEntity(
        movieTitle = movieTitle,
        line = line,
        promptVersion = promptVersion,
        explanationJson = explanationJson,
        createdAtEpochMs = createdAtEpochMs,
        lastUsedAtEpochMs = lastUsedAtEpochMs,
        hitCount = hitCount,
    )

    @Test
    fun upsertThenGetReturnsTheRow() =
        runTest {
            val row = entity("Casablanca", "Here's looking at you, kid.", "v2")
            dao.upsert(row)

            assertEquals(row, dao.get("Casablanca", "Here's looking at you, kid.", "v2"))
        }

    @Test
    fun getNeedsAllThreePartsOfTheKey() =
        runTest {
            val row = entity("Casablanca", "line", "v2")
            dao.upsert(row)

            assertEquals(row, dao.get("Casablanca", "line", "v2"))
            assertNull(dao.get("Casablanca", "line", "v1"))
            assertNull(dao.get("Casablanca", "another line", "v2"))
            assertNull(dao.get("The Maltese Falcon", "line", "v2"))
            assertNull(dao.get("missing", "missing", "missing"))
        }

    @Test
    fun theSameLineUnderTwoTitlesAreTwoRows() =
        runTest {
            val casablanca = entity("Casablanca", "line", explanationJson = """{"answer":"casablanca"}""")
            val falcon = entity("The Maltese Falcon", "line", explanationJson = """{"answer":"falcon"}""")
            dao.upsert(casablanca)
            dao.upsert(falcon)

            assertEquals(casablanca, dao.get("Casablanca", "line", "v1"))
            assertEquals(falcon, dao.get("The Maltese Falcon", "line", "v1"))
            assertEquals(2, dao.count())
        }

    @Test
    fun theSameLineUnderTwoPromptVersionsAreTwoRows() =
        runTest {
            val old = entity("Casablanca", "line", "v1", explanationJson = """{"answer":"vieja"}""")
            val newer = entity("Casablanca", "line", "v2", explanationJson = """{"answer":"nueva"}""")
            dao.upsert(old)
            dao.upsert(newer)

            // Storing the new prompt's answer must not evict the old prompt's one.
            assertEquals(old, dao.get("Casablanca", "line", "v1"))
            assertEquals(newer, dao.get("Casablanca", "line", "v2"))
            assertEquals(2, dao.count())
        }

    @Test
    fun upsertReplacesTheRowWithTheSameTitleLineAndPromptVersion() =
        runTest {
            dao.upsert(entity("Casablanca", "line", "v1", explanationJson = """{"answer":"vieja"}"""))
            val replacement = entity("Casablanca", "line", "v1", explanationJson = """{"answer":"nueva"}""")
            dao.upsert(replacement)

            assertEquals(replacement, dao.get("Casablanca", "line", "v1"))
            assertEquals(1, dao.count())
        }

    @Test
    fun touchRefreshesLastUsedAndCountsTheHitLeavingTheRestOfTheRowAlone() =
        runTest {
            val row = entity("Casablanca", "line", "v1", createdAtEpochMs = 500L, hitCount = 3)
            dao.upsert(row)

            assertEquals(1, dao.touch("Casablanca", "line", "v1", now = 9_000L))

            val touched = dao.get("Casablanca", "line", "v1")!!
            assertEquals(row.copy(lastUsedAtEpochMs = 9_000L, hitCount = 4), touched)
            assertEquals(500L, touched.createdAtEpochMs)
        }

    @Test
    fun touchNeedsAllThreePartsOfTheKey() =
        runTest {
            val row = entity("Casablanca", "line", "v1", lastUsedAtEpochMs = 1_000L, hitCount = 1)
            dao.upsert(row)

            assertEquals(0, dao.touch("Casablanca", "line", "v2", now = 9_000L))
            assertEquals(0, dao.touch("Casablanca", "another line", "v1", now = 9_000L))
            assertEquals(0, dao.touch("The Maltese Falcon", "line", "v1", now = 9_000L))
            assertEquals(row, dao.get("Casablanca", "line", "v1"))
        }

    @Test
    fun touchOnePromptVersionLeavesTheOtherVersionOfTheSameLineAlone() =
        runTest {
            val old = entity("Casablanca", "line", "v1", lastUsedAtEpochMs = 1_000L, hitCount = 4)
            dao.upsert(old)
            dao.upsert(entity("Casablanca", "line", "v2", lastUsedAtEpochMs = 2_000L, hitCount = 1))

            assertEquals(1, dao.touch("Casablanca", "line", "v2", now = 9_000L))

            val untouched = dao.get("Casablanca", "line", "v1")!!
            assertEquals(old, untouched)
            assertEquals(4, untouched.hitCount)
            assertEquals(1_000L, untouched.lastUsedAtEpochMs)

            val touched = dao.get("Casablanca", "line", "v2")!!
            assertEquals(2, touched.hitCount)
            assertEquals(9_000L, touched.lastUsedAtEpochMs)
        }

    @Test
    fun touchUnknownKeyReturnsZeroAndStoresNothing() =
        runTest {
            assertEquals(0, dao.touch("missing", "missing", "missing", now = 9_000L))

            assertEquals(0, dao.count())
        }

    @Test
    fun countFollowsInsertsAndDeletes() =
        runTest {
            assertEquals(0, dao.count())

            dao.upsert(entity("Casablanca", "a", "v1", lastUsedAtEpochMs = 1L))
            dao.upsert(entity("Casablanca", "a", "v2", lastUsedAtEpochMs = 2L))
            dao.upsert(entity("The Maltese Falcon", "b", "v1", lastUsedAtEpochMs = 3L))
            assertEquals(3, dao.count())

            dao.deleteUsedBefore(3L)
            assertEquals(1, dao.count())
        }

    @Test
    fun deleteUsedBeforeRemovesOnlyStrictlyOlderRowsAndReturnsHowManyWent() =
        runTest {
            dao.upsert(entity("Casablanca", "line", "v1", lastUsedAtEpochMs = 999L))
            val boundary = entity("Casablanca", "line", "v2", lastUsedAtEpochMs = 1_000L)
            val fresh = entity("The Maltese Falcon", "fresh", "v1", lastUsedAtEpochMs = 1_001L)
            dao.upsert(boundary)
            dao.upsert(fresh)

            assertEquals(1, dao.deleteUsedBefore(1_000L))

            // The v1 twin of the boundary row went; the row at exactly the argument stayed.
            assertNull(dao.get("Casablanca", "line", "v1"))
            assertEquals(boundary, dao.get("Casablanca", "line", "v2"))
            assertEquals(fresh, dao.get("The Maltese Falcon", "fresh", "v1"))
        }

    // The three-column key cannot be spelled in a `NOT IN`, so the query ranks by `rowid`; all four
    // rows share one `line`, so a prune that ignored part of the key would delete nothing here.
    @Test
    fun deleteBeyondMostRecentlyUsedKeepsTheNewestRowsAndReturnsHowManyWent() =
        runTest {
            dao.upsert(entity("Casablanca", "line", "v1", lastUsedAtEpochMs = 1L))
            dao.upsert(entity("Casablanca", "line", "v2", lastUsedAtEpochMs = 2L))
            val third = entity("The Maltese Falcon", "line", "v1", lastUsedAtEpochMs = 3L)
            val newest = entity("The Maltese Falcon", "line", "v2", lastUsedAtEpochMs = 4L)
            dao.upsert(third)
            dao.upsert(newest)

            assertEquals(2, dao.deleteBeyondMostRecentlyUsed(2))

            assertEquals(newest, dao.get("The Maltese Falcon", "line", "v2"))
            assertEquals(third, dao.get("The Maltese Falcon", "line", "v1"))
            assertNull(dao.get("Casablanca", "line", "v1"))
            assertNull(dao.get("Casablanca", "line", "v2"))
            assertEquals(2, dao.count())
        }

    @Test
    fun deleteBeyondMostRecentlyUsedIsANoOpWhenTheTableFits() =
        runTest {
            val a = entity("Casablanca", "line", "v1", lastUsedAtEpochMs = 1L)
            val b = entity("Casablanca", "line", "v2", lastUsedAtEpochMs = 2L)
            dao.upsert(a)
            dao.upsert(b)

            assertEquals(0, dao.deleteBeyondMostRecentlyUsed(2))
            assertEquals(0, dao.deleteBeyondMostRecentlyUsed(10))

            assertEquals(a, dao.get("Casablanca", "line", "v1"))
            assertEquals(b, dao.get("Casablanca", "line", "v2"))
            assertEquals(2, dao.count())
        }
}
