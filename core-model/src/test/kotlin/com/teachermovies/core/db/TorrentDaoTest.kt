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
class TorrentDaoTest {
    private lateinit var db: TeacherMoviesDatabase
    private lateinit var dao: TorrentDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, TeacherMoviesDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.torrentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(
        infoHash: String,
        addedAt: Long = 1_000L,
        state: String = "Downloading",
        name: String = "Movie $infoHash",
    ) = TorrentEntity(
        infoHash = infoHash,
        name = name,
        state = state,
        progressPercent = 12.5,
        downloadedBytes = 125L,
        totalBytes = 1_000L,
        savePath = "/storage/Movies/$infoHash",
        mainFileIndex = 0,
        mainFilePath = "movie.mkv",
        audioTrackId = null,
        subtitleTrackId = null,
        addedAtEpochMs = addedAt,
        completedAtEpochMs = null,
        errorMessage = null,
    )

    @Test
    fun upsertThenGetReturnsTheRow() =
        runTest {
            val row = entity("aaa")
            dao.upsert(row)

            assertEquals(row, dao.get("aaa"))
            assertEquals(0L, dao.get("aaa")!!.lastPositionMs)
        }

    @Test
    fun getUnknownInfoHashReturnsNull() =
        runTest {
            assertNull(dao.get("missing"))
        }

    @Test
    fun upsertOverwritesTheRowWithTheSameInfoHash() =
        runTest {
            dao.upsert(entity("aaa", name = "Old"))
            val replacement = entity("aaa", name = "New", state = "Completed")
            dao.upsert(replacement)

            assertEquals(replacement, dao.get("aaa"))
            assertEquals(1, dao.observeAll().first().size)
        }

    @Test
    fun observeAllIsOrderedNewestFirst() =
        runTest {
            dao.upsert(entity("old", addedAt = 1L))
            dao.upsert(entity("newest", addedAt = 3L))
            dao.upsert(entity("middle", addedAt = 2L))

            assertEquals(
                listOf("newest", "middle", "old"),
                dao.observeAll().first().map { it.infoHash },
            )
        }

    @Test
    fun observeByStateReturnsOnlyMatchingRows() =
        runTest {
            dao.upsert(entity("a", state = "Downloading", addedAt = 1L))
            dao.upsert(entity("b", state = "Completed", addedAt = 2L))
            dao.upsert(entity("c", state = "Downloading", addedAt = 3L))

            assertEquals(
                listOf("c", "a"),
                dao.observeByState("Downloading").first().map { it.infoHash },
            )
            assertEquals(listOf("b"), dao.observeByState("Completed").first().map { it.infoHash })
            assertEquals(emptyList<TorrentEntity>(), dao.observeByState("Paused").first())
        }

    @Test
    fun updateProgressChangesOnlyProgressColumns() =
        runTest {
            val original = entity("aaa")
            dao.upsert(original)

            dao.updateProgress(
                infoHash = "aaa",
                state = "Completed",
                progressPercent = 100.0,
                downloadedBytes = 1_000L,
                totalBytes = 1_000L,
            )

            assertEquals(
                original.copy(
                    state = "Completed",
                    progressPercent = 100.0,
                    downloadedBytes = 1_000L,
                    totalBytes = 1_000L,
                ),
                dao.get("aaa"),
            )
        }

    @Test
    fun updatePlaybackChangesOnlyPlaybackColumns() =
        runTest {
            val original = entity("aaa")
            dao.upsert(original)

            dao.updatePlayback(
                infoHash = "aaa",
                lastPositionMs = 42_000L,
                audioTrackId = "audio-1",
                subtitleTrackId = "sub-en",
            )

            assertEquals(
                original.copy(
                    lastPositionMs = 42_000L,
                    audioTrackId = "audio-1",
                    subtitleTrackId = "sub-en",
                ),
                dao.get("aaa"),
            )
        }

    @Test
    fun deleteRemovesOnlyThatRow() =
        runTest {
            dao.upsert(entity("aaa", addedAt = 1L))
            dao.upsert(entity("bbb", addedAt = 2L))

            dao.delete("aaa")

            assertNull(dao.get("aaa"))
            assertEquals(listOf("bbb"), dao.observeAll().first().map { it.infoHash })
        }
}
