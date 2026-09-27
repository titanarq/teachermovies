package com.teachermovies.core.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.teachermovies.core.db.TeacherMoviesDatabase
import com.teachermovies.core.db.TranslationCacheDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The contract against real Room, plus what only the DAO can show: the bookkeeping fields a read
 * hides (`createdAtEpochMs`, `hitCount`) and the fact that a pruned row is deleted, not just ignored.
 */
@RunWith(RobolectricTestRunner::class)
class RoomTranslationCacheRepositoryTest : TranslationCacheRepositoryContractTest() {
    private var db: TeacherMoviesDatabase? = null
    private lateinit var dao: TranslationCacheDao

    override fun createRepository(pruning: CachePruningPolicy): TranslationCacheRepository {
        dao = database().translationCacheDao()
        return RoomTranslationCacheRepository(dao, pruning)
    }

    override fun releaseRepository() {
        db?.close()
        db = null
    }

    /**
     * One in-memory database per test, shared by every repository the contract builds, so a test that
     * asks for a second one under a tighter [CachePruningPolicy] still sees the rows it stored.
     */
    private fun database(): TeacherMoviesDatabase = db ?: buildDatabase().also { db = it }

    private fun buildDatabase(): TeacherMoviesDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room
            .inMemoryDatabaseBuilder(context, TeacherMoviesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun aHitRefreshesLastUsedAtAndCountsTheHitWithoutTouchingTheRest() =
        runTest {
            val repository = createRepository(CachePruningPolicy())
            repository.store("hello world", "hola mundo", now = 1_000L)

            repository.translationOf("hello world", now = 2_000L)
            repository.translationOf("hello world", now = 3_000L)

            val row = dao.get("hello world")
            assertEquals("hola mundo", row?.translationEs)
            assertEquals(1_000L, row?.createdAtEpochMs)
            assertEquals(3_000L, row?.lastUsedAtEpochMs)
            assertEquals(2, row?.hitCount)
            assertEquals(1, dao.count())
        }

    @Test
    fun reStoringReplacesThePayloadButKeepsCreatedAtAndHitCount() =
        runTest {
            val repository = createRepository(CachePruningPolicy())
            repository.store("hello world", "hola mundo", now = 1_000L)
            repository.translationOf("hello world", now = 2_000L)

            repository.store("hello world", "hola, mundo", now = 5_000L)

            val row = dao.get("hello world")
            assertEquals("hola, mundo", row?.translationEs)
            assertEquals(1_000L, row?.createdAtEpochMs)
            assertEquals(5_000L, row?.lastUsedAtEpochMs)
            assertEquals(1, row?.hitCount)
            assertEquals(1, dao.count())
        }

    @Test
    fun aMissWritesNothing() =
        runTest {
            val repository = createRepository(CachePruningPolicy())
            repository.store("hello world", "hola mundo", now = 1_000L)

            repository.translationOf("goodbye", now = 2_000L)

            assertEquals(1, dao.count())
            assertEquals(0, dao.get("hello world")?.hitCount)
            assertEquals(1_000L, dao.get("hello world")?.lastUsedAtEpochMs)
        }

    @Test
    fun theStoredKeyIsTheNormalizedText() =
        runTest {
            val repository = createRepository(CachePruningPolicy())

            repository.store("  hello   world \n", "hola mundo", now = 1_000L)

            assertEquals(1, dao.count())
            assertEquals("hola mundo", dao.get("hello world")?.translationEs)
        }

    @Test
    fun theCapPruneDeletesTheRowInsteadOfHidingIt() =
        runTest {
            val capped = createRepository(CachePruningPolicy(maxRows = 2, maxAgeDays = 90))
            capped.store("one", "uno", now = 1_000L)
            capped.store("two", "dos", now = 2_000L)

            capped.store("three", "tres", now = 3_000L)

            assertEquals(2, dao.count())
            assertNull(dao.get("one"))
        }

    @Test
    fun theAgePruneDeletesTheRowInsteadOfHidingIt() =
        runTest {
            val oneDay = createRepository(CachePruningPolicy(maxRows = 10, maxAgeDays = 1))
            oneDay.store("hello world", "hola mundo", now = 1_000L)

            oneDay.store("goodbye", "adios", now = 1_000L + dayMs + 1L)

            assertEquals(1, dao.count())
            assertNull(dao.get("hello world"))
        }

    @Test
    fun aStoreThatStoresNothingWritesNoRow() =
        runTest {
            val repository = createRepository(CachePruningPolicy())

            repository.store("   ", "hola mundo", now = 1_000L)
            repository.store("hello world", "  ", now = 2_000L)

            assertEquals(0, dao.count())
        }
}
