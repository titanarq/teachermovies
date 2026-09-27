package com.teachermovies.core.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.teachermovies.core.db.ExplanationCacheDao
import com.teachermovies.core.db.TeacherMoviesDatabase
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
class RoomExplanationCacheRepositoryTest : ExplanationCacheRepositoryContractTest() {
    private var db: TeacherMoviesDatabase? = null
    private lateinit var dao: ExplanationCacheDao

    override fun createRepository(pruning: CachePruningPolicy): ExplanationCacheRepository {
        dao = database().explanationCacheDao()
        return RoomExplanationCacheRepository(dao, pruning)
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

    /** A payload the tests can tell apart by [seed]; its shape means nothing to `:core-model`. */
    private fun explanation(seed: String): String = "{\"explanation\":\"$seed\"}"

    @Test
    fun aHitRefreshesLastUsedAtAndCountsTheHitWithoutTouchingTheRest() =
        runTest {
            val repository = createRepository(CachePruningPolicy())
            repository.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            repository.explanationFor("The Room", "hello world", "1", now = 2_000L)
            repository.explanationFor("The Room", "hello world", "1", now = 3_000L)

            val row = dao.get("The Room", "hello world", "1")
            assertEquals(explanation("first"), row?.explanationJson)
            assertEquals(1_000L, row?.createdAtEpochMs)
            assertEquals(3_000L, row?.lastUsedAtEpochMs)
            assertEquals(2, row?.hitCount)
            assertEquals(1, dao.count())
        }

    @Test
    fun reStoringReplacesThePayloadButKeepsCreatedAtAndHitCount() =
        runTest {
            val repository = createRepository(CachePruningPolicy())
            repository.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)
            repository.explanationFor("The Room", "hello world", "1", now = 2_000L)

            repository.store("The Room", "hello world", "1", explanation("second"), now = 5_000L)

            val row = dao.get("The Room", "hello world", "1")
            assertEquals(explanation("second"), row?.explanationJson)
            assertEquals(1_000L, row?.createdAtEpochMs)
            assertEquals(5_000L, row?.lastUsedAtEpochMs)
            assertEquals(1, row?.hitCount)
            assertEquals(1, dao.count())
        }

    @Test
    fun aMissWritesNothing() =
        runTest {
            val repository = createRepository(CachePruningPolicy())
            repository.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            repository.explanationFor("The Room", "hello world", "2", now = 2_000L)

            assertEquals(1, dao.count())
            assertEquals(0, dao.get("The Room", "hello world", "1")?.hitCount)
            assertEquals(1_000L, dao.get("The Room", "hello world", "1")?.lastUsedAtEpochMs)
        }

    @Test
    fun theStoredKeyIsTheNormalizedText() =
        runTest {
            val repository = createRepository(CachePruningPolicy())

            repository.store("  The   Room \n", "\thello   world ", "  1 ", explanation("first"), now = 1_000L)

            assertEquals(1, dao.count())
            assertEquals(explanation("first"), dao.get("The Room", "hello world", "1")?.explanationJson)
        }

    @Test
    fun twoPromptVersionsOfOneLineAreTwoRows() =
        runTest {
            val repository = createRepository(CachePruningPolicy())
            repository.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            repository.store("The Room", "hello world", "2", explanation("second"), now = 2_000L)

            assertEquals(2, dao.count())
            assertEquals(explanation("first"), dao.get("The Room", "hello world", "1")?.explanationJson)
            assertEquals(explanation("second"), dao.get("The Room", "hello world", "2")?.explanationJson)
        }

    @Test
    fun aBlankPromptVersionIsStoredUnderTheEmptyKeyPart() =
        runTest {
            val repository = createRepository(CachePruningPolicy())

            repository.store("The Room", "hello world", "", explanation("unversioned"), now = 1_000L)

            assertEquals(1, dao.count())
            assertEquals(
                explanation("unversioned"),
                dao.get("The Room", "hello world", "")?.explanationJson,
            )
        }

    @Test
    fun theCapPruneDeletesTheRowInsteadOfHidingIt() =
        runTest {
            val capped = createRepository(CachePruningPolicy(maxRows = 2, maxAgeDays = 90))
            capped.store("The Room", "one", "1", explanation("one"), now = 1_000L)
            capped.store("The Room", "two", "1", explanation("two"), now = 2_000L)

            capped.store("The Room", "three", "1", explanation("three"), now = 3_000L)

            assertEquals(2, dao.count())
            assertNull(dao.get("The Room", "one", "1"))
        }

    @Test
    fun theAgePruneDeletesTheRowInsteadOfHidingIt() =
        runTest {
            val oneDay = createRepository(CachePruningPolicy(maxRows = 10, maxAgeDays = 1))
            oneDay.store("The Room", "hello world", "1", explanation("first"), now = 1_000L)

            oneDay.store("The Room", "goodbye", "1", explanation("second"), now = 1_000L + dayMs + 1L)

            assertEquals(1, dao.count())
            assertNull(dao.get("The Room", "hello world", "1"))
        }

    @Test
    fun aStoreThatStoresNothingWritesNoRow() =
        runTest {
            val repository = createRepository(CachePruningPolicy())

            repository.store("  ", "hello world", "1", explanation("first"), now = 1_000L)
            repository.store("The Room", "  ", "1", explanation("second"), now = 2_000L)
            repository.store("The Room", "hello world", "1", "   ", now = 3_000L)

            assertEquals(0, dao.count())
        }
}
