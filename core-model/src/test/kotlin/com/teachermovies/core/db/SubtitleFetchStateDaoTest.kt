package com.teachermovies.core.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubtitleFetchStateDaoTest {
    private lateinit var db: TeacherMoviesDatabase
    private lateinit var dao: SubtitleFetchStateDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, TeacherMoviesDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.subtitleFetchStateDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(
        infoHash: String,
        language: String,
        state: String = "Pending",
        movieHash: String? = "moviehash-$infoHash",
        localPath: String? = null,
        variantLabel: String? = null,
        attempts: Int = 0,
        lastAttemptEpochMs: Long? = null,
        nextRetryEpochMs: Long? = null,
        errorMessage: String? = null,
        updatedAtEpochMs: Long = 1_000L,
    ) = SubtitleFetchStateEntity(
        infoHash = infoHash,
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

    @Test
    fun upsertThenGetReturnsTheRow() =
        runTest {
            val row =
                entity(
                    infoHash = "aaa",
                    language = "es",
                    state = "Downloaded",
                    localPath = "/storage/Movies/aaa/subs/es.srt",
                    variantLabel = "latino",
                    attempts = 2,
                    lastAttemptEpochMs = 900L,
                    updatedAtEpochMs = 1_234L,
                )
            dao.upsert(row)

            assertEquals(row, dao.get("aaa", "es"))
        }

    @Test
    fun getNeedsBothPartsOfTheKey() =
        runTest {
            val row = entity("aaa", "es")
            dao.upsert(row)

            assertEquals(row, dao.get("aaa", "es"))
            assertNull(dao.get("aaa", "en"))
            assertNull(dao.get("bbb", "es"))
            assertNull(dao.get("missing", "missing"))
        }

    @Test
    fun twoLanguagesOfOneMovieAreTwoRows() =
        runTest {
            val spanish = entity("aaa", "es", state = "Downloaded")
            val english = entity("aaa", "en", state = "NotFound", nextRetryEpochMs = 5_000L)
            dao.upsert(spanish)
            dao.upsert(english)

            assertEquals(spanish, dao.get("aaa", "es"))
            assertEquals(english, dao.get("aaa", "en"))
            assertEquals(2, dao.getAll().size)
        }

    @Test
    fun upsertReplacesTheRowWithTheSameInfoHashAndLanguage() =
        runTest {
            dao.upsert(entity("aaa", "es", state = "Pending"))
            val replacement =
                entity(
                    "aaa",
                    "es",
                    state = "Failed",
                    attempts = 1,
                    lastAttemptEpochMs = 2_000L,
                    errorMessage = "laptop offline",
                    updatedAtEpochMs = 2_000L,
                )
            dao.upsert(replacement)

            assertEquals(replacement, dao.get("aaa", "es"))
            assertEquals(1, dao.getAll().size)
        }

    @Test
    fun observeAllIsOrderedMostRecentlyUpdatedFirst() =
        runTest {
            dao.upsert(entity("old", "es", updatedAtEpochMs = 1L))
            dao.upsert(entity("newest", "es", updatedAtEpochMs = 3L))
            dao.upsert(entity("middle", "en", updatedAtEpochMs = 2L))

            assertEquals(
                listOf("newest", "middle", "old"),
                dao.observeAll().first().map { it.infoHash },
            )
        }

    @Test
    fun observeAllEmitsTheNewRowAfterAnUpsert() =
        runTest {
            dao.upsert(entity("aaa", "es", state = "Pending", updatedAtEpochMs = 1L))

            dao.upsert(entity("aaa", "es", state = "Downloaded", updatedAtEpochMs = 2L))

            assertEquals(listOf("Downloaded"), dao.observeAll().first().map { it.state })
        }

    @Test
    fun getAllReturnsEveryRow() =
        runTest {
            val aaaEs = entity("aaa", "es")
            val aaaEn = entity("aaa", "en")
            val bbbEs = entity("bbb", "es")
            dao.upsert(aaaEs)
            dao.upsert(aaaEn)
            dao.upsert(bbbEs)

            // getAll carries no ORDER BY, so compare the rows as a set.
            assertEquals(3, dao.getAll().size)
            assertEquals(setOf(aaaEs, aaaEn, bbbEs), dao.getAll().toSet())
        }

    @Test
    fun deleteRemovesOnlyThatLanguageRow() =
        runTest {
            val spanish = entity("aaa", "es")
            val english = entity("aaa", "en")
            val otherMovie = entity("bbb", "es")
            dao.upsert(spanish)
            dao.upsert(english)
            dao.upsert(otherMovie)

            dao.delete("aaa", "es")

            assertNull(dao.get("aaa", "es"))
            assertEquals(english, dao.get("aaa", "en"))
            assertEquals(otherMovie, dao.get("bbb", "es"))
        }

    @Test
    fun deleteForTorrentRemovesEveryLanguageOfOneMovieOnly() =
        runTest {
            dao.upsert(entity("aaa", "es"))
            dao.upsert(entity("aaa", "en"))
            val otherMovie = entity("bbb", "es")
            dao.upsert(otherMovie)

            dao.deleteForTorrent("aaa")

            assertNull(dao.get("aaa", "es"))
            assertNull(dao.get("aaa", "en"))
            assertEquals(listOf(otherMovie), dao.getAll())
        }
}
