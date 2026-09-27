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
class TranslationCacheDaoTest {
    private lateinit var db: TeacherMoviesDatabase
    private lateinit var dao: TranslationCacheDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, TeacherMoviesDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.translationCacheDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(
        sourceText: String,
        translationEs: String = "traducción de $sourceText",
        createdAtEpochMs: Long = 1_000L,
        lastUsedAtEpochMs: Long = 1_000L,
        hitCount: Int = 0,
    ) = TranslationCacheEntity(
        sourceText = sourceText,
        translationEs = translationEs,
        createdAtEpochMs = createdAtEpochMs,
        lastUsedAtEpochMs = lastUsedAtEpochMs,
        hitCount = hitCount,
    )

    @Test
    fun upsertThenGetReturnsTheRow() =
        runTest {
            val row = entity("here's looking at you, kid")
            dao.upsert(row)

            assertEquals(row, dao.get("here's looking at you, kid"))
        }

    @Test
    fun getUnknownSourceTextReturnsNull() =
        runTest {
            assertNull(dao.get("missing"))
        }

    @Test
    fun upsertReplacesTheRowWithTheSameSourceText() =
        runTest {
            dao.upsert(entity("line", translationEs = "vieja", lastUsedAtEpochMs = 1L))
            val replacement = entity("line", translationEs = "nueva", lastUsedAtEpochMs = 2L)
            dao.upsert(replacement)

            assertEquals(replacement, dao.get("line"))
            assertEquals(1, dao.count())
        }

    @Test
    fun touchRefreshesLastUsedAndCountsTheHitLeavingTheRestOfTheRowAlone() =
        runTest {
            val row = entity("line", createdAtEpochMs = 500L, lastUsedAtEpochMs = 1_000L, hitCount = 3)
            dao.upsert(row)

            assertEquals(1, dao.touch("line", now = 9_000L))
            assertEquals(row.copy(lastUsedAtEpochMs = 9_000L, hitCount = 4), dao.get("line"))
        }

    @Test
    fun touchUnknownSourceTextReturnsZeroAndStoresNothing() =
        runTest {
            assertEquals(0, dao.touch("missing", now = 9_000L))

            assertEquals(0, dao.count())
        }

    @Test
    fun countFollowsInsertsAndDeletes() =
        runTest {
            assertEquals(0, dao.count())

            dao.upsert(entity("a", lastUsedAtEpochMs = 1L))
            dao.upsert(entity("b", lastUsedAtEpochMs = 2L))
            dao.upsert(entity("c", lastUsedAtEpochMs = 3L))
            assertEquals(3, dao.count())

            dao.deleteUsedBefore(3L)
            assertEquals(1, dao.count())
        }

    @Test
    fun deleteUsedBeforeRemovesOnlyStrictlyOlderRowsAndReturnsHowManyWent() =
        runTest {
            dao.upsert(entity("stale", lastUsedAtEpochMs = 999L))
            val boundary = entity("boundary", lastUsedAtEpochMs = 1_000L)
            val fresh = entity("fresh", lastUsedAtEpochMs = 1_001L)
            dao.upsert(boundary)
            dao.upsert(fresh)

            assertEquals(1, dao.deleteUsedBefore(1_000L))

            assertNull(dao.get("stale"))
            assertEquals(boundary, dao.get("boundary"))
            assertEquals(fresh, dao.get("fresh"))
        }

    @Test
    fun deleteBeyondMostRecentlyUsedKeepsTheNewestRowsAndReturnsHowManyWent() =
        runTest {
            dao.upsert(entity("oldest", lastUsedAtEpochMs = 1L))
            dao.upsert(entity("older", lastUsedAtEpochMs = 2L))
            val third = entity("third", lastUsedAtEpochMs = 3L)
            val newest = entity("newest", lastUsedAtEpochMs = 4L)
            dao.upsert(third)
            dao.upsert(newest)

            assertEquals(2, dao.deleteBeyondMostRecentlyUsed(2))

            assertEquals(newest, dao.get("newest"))
            assertEquals(third, dao.get("third"))
            assertNull(dao.get("older"))
            assertNull(dao.get("oldest"))
            assertEquals(2, dao.count())
        }

    @Test
    fun deleteBeyondMostRecentlyUsedIsANoOpWhenTheTableFits() =
        runTest {
            val a = entity("a", lastUsedAtEpochMs = 1L)
            val b = entity("b", lastUsedAtEpochMs = 2L)
            dao.upsert(a)
            dao.upsert(b)

            assertEquals(0, dao.deleteBeyondMostRecentlyUsed(2))
            assertEquals(0, dao.deleteBeyondMostRecentlyUsed(10))

            assertEquals(a, dao.get("a"))
            assertEquals(b, dao.get("b"))
            assertEquals(2, dao.count())
        }
}
