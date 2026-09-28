package com.teachermovies.tv.subtitles

import com.teachermovies.assistant.subtitles.MovieHashResult
import com.teachermovies.assistant.subtitles.OpenSubtitlesHash
import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.SubtitleFetch
import com.teachermovies.core.model.SubtitleFetchState
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.SubtitleFetchRepository
import com.teachermovies.core.repo.fake.InMemorySubtitleFetchRepository
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleNeedsCoordinatorTest {
    private val repo = InMemoryTorrentRepository()
    private val rows = InMemorySubtitleFetchRepository()

    /** What the coordinator did, in order: a stored row as `row:<language>`, a nudge as `nudge`. */
    private val events = mutableListOf<String>()
    private val notified = mutableListOf<TorrentId>()

    /** What the container probe reports for the movie under test; empty means no embedded track. */
    private var embedded = emptyList<String>()

    /** The movie whose rows cannot be stored, if the test wants the coordinator to hit that path. */
    private var failing: TorrentId? = null

    private val fetches = RecordingFetches(rows, events) { failing }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun TestScope.coordinator() =
        SubtitleNeedsCoordinator(
            library = repo,
            fetches = fetches,
            notify = { id ->
                notified += id
                events += NUDGE
            },
            scope = backgroundScope,
            embeddedLanguages = { embedded },
            nowMs = { NOW },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

    private fun id(hashChar: Char): TorrentId = TorrentId(hashChar.toString().repeat(40))

    /** A movie file of [sizeBytes] bytes in the temporary folder; its content only feeds the hash. */
    private fun movie(
        name: String,
        sizeBytes: Int,
    ): File =
        File(tempFolder.root, name)
            .apply { writeBytes(ByteArray(sizeBytes) { (it % 251).toByte() }) }

    private suspend fun upsert(
        hashChar: Char,
        name: String,
        state: DownloadState,
        at: Long,
        mainFilePath: String = "/vol/Movies/$hashChar/$name.mkv",
    ) {
        repo.upsert(
            Torrent(
                id = id(hashChar),
                name = name,
                state = state,
                progressPercent = if (state == DownloadState.Completed) 100.0 else 10.0,
                downloadedBytes = 1_000L,
                totalBytes = 1_000L,
                savePath = "/vol/Movies/$hashChar",
                mainFileIndex = 0,
                errorMessage = null,
            ),
            mainFilePath,
            at,
        )
    }

    @Test
    fun theLibraryAlreadyThereWhenItStartsIsOnlyRecorded() =
        runTest(UnconfinedTestDispatcher()) {
            upsert('a', "Heat", DownloadState.Completed, 1_000L)
            coordinator().start()

            assertEquals(emptyList<TorrentId>(), notified)

            upsert('b', "Ronin", DownloadState.Completed, 2_000L)
            assertEquals(listOf(id('b')), notified)
        }

    @Test
    fun aMovieIsNotifiedOnceHoweverOftenTheLibraryEmits() =
        runTest(UnconfinedTestDispatcher()) {
            coordinator().start()
            upsert('a', "Heat", DownloadState.Completed, 1_000L)
            repo.updatePlayback(id('a'), positionMs = 60_000L, audioTrackId = null, subtitleTrackId = null)

            assertEquals(listOf(id('a')), notified)
        }

    @Test
    fun aMovieStillDownloadingIsNotifiedWhenItCompletes() =
        runTest(UnconfinedTestDispatcher()) {
            coordinator().start()
            upsert('a', "Heat", DownloadState.Downloading, 1_000L)
            assertEquals(emptyList<TorrentId>(), notified)

            upsert('a', "Heat", DownloadState.Completed, 2_000L)
            assertEquals(listOf(id('a')), notified)
        }

    @Test
    fun aMovieThatLeavesTheLibraryAndCompletesAgainIsNotifiedAgain() =
        runTest(UnconfinedTestDispatcher()) {
            coordinator().start()
            upsert('a', "Heat", DownloadState.Completed, 1_000L)
            assertEquals(listOf(id('a')), notified)

            repo.delete(id('a'))
            assertEquals(listOf(id('a')), notified)
            upsert('a', "Heat", DownloadState.Completed, 2_000L)
            assertEquals(listOf(id('a'), id('a')), notified)
        }

    @Test
    fun aNewlyCompletedMovieGetsASpanishAndAnEnglishPendingRowWithItsHash() =
        runTest(UnconfinedTestDispatcher()) {
            val file = movie("Heat.mkv", HASHABLE_BYTES)
            coordinator().start()
            upsert('a', "Heat", DownloadState.Completed, 1_000L, file.absolutePath)

            // The hash is #279's own; what is asserted here is that the coordinator stores it.
            val expected = (OpenSubtitlesHash.of(file) as MovieHashResult.Computed).hash
            assertEquals(16, expected.length)
            assertEquals(SubtitleFetchState.Pending, rows.get(id('a'), "es")?.state)
            assertEquals(SubtitleFetchState.Pending, rows.get(id('a'), "en")?.state)
            assertEquals(expected, rows.get(id('a'), "es")?.movieHash)
            assertEquals(expected, rows.get(id('a'), "en")?.movieHash)
        }

    @Test
    fun aMovieWithAnEnglishSidecarNextToItGetsOnlyTheSpanishRow() =
        runTest(UnconfinedTestDispatcher()) {
            val file = movie("Heat.mkv", HASHABLE_BYTES)
            File(tempFolder.root, "Heat.en.srt")
                .writeText("1\n00:00:01,000 --> 00:00:02,000\nHello there\n")
            coordinator().start()
            upsert('a', "Heat", DownloadState.Completed, 1_000L, file.absolutePath)

            assertTrue(rows.get(id('a'), "es") != null)
            assertNull(rows.get(id('a'), "en"))
        }

    @Test
    fun aMovieWhoseContainerCarriesAnEnglishTrackGetsOnlyTheSpanishRow() =
        runTest(UnconfinedTestDispatcher()) {
            embedded = listOf("spa", "eng")
            val file = movie("Heat.mkv", HASHABLE_BYTES)
            coordinator().start()
            upsert('a', "Heat", DownloadState.Completed, 1_000L, file.absolutePath)

            assertTrue(rows.get(id('a'), "es") != null)
            assertNull(rows.get(id('a'), "en"))
        }

    @Test
    fun anEmbeddedTrackThatIsNotEnglishLeavesTheEnglishRowThere() =
        runTest(UnconfinedTestDispatcher()) {
            embedded = listOf("spa", "fra")
            val file = movie("Heat.mkv", HASHABLE_BYTES)
            coordinator().start()
            upsert('a', "Heat", DownloadState.Completed, 1_000L, file.absolutePath)

            assertTrue(rows.get(id('a'), "es") != null)
            assertTrue(rows.get(id('a'), "en") != null)
        }

    @Test
    fun theLibraryAlreadyThereWhenItStartsGetsItsRowsWithoutANudge() =
        runTest(UnconfinedTestDispatcher()) {
            val file = movie("Heat.mkv", HASHABLE_BYTES)
            upsert('a', "Heat", DownloadState.Completed, 1_000L, file.absolutePath)
            coordinator().start()

            assertEquals(emptyList<TorrentId>(), notified)
            assertTrue(rows.get(id('a'), "es") != null)
            assertTrue(rows.get(id('a'), "en") != null)
        }

    @Test
    fun aFileTooShortToHashGetsItsRowsWithANullHash() =
        runTest(UnconfinedTestDispatcher()) {
            val file = movie("Short.mkv", TOO_SHORT_BYTES)
            coordinator().start()
            upsert('a', "Short", DownloadState.Completed, 1_000L, file.absolutePath)

            assertTrue(rows.get(id('a'), "es") != null)
            assertTrue(rows.get(id('a'), "en") != null)
            assertNull(rows.get(id('a'), "es")?.movieHash)
            assertNull(rows.get(id('a'), "en")?.movieHash)
        }

    @Test
    fun theNudgeFollowsTheRowWrite() =
        runTest(UnconfinedTestDispatcher()) {
            val file = movie("Heat.mkv", HASHABLE_BYTES)
            coordinator().start()
            upsert('a', "Heat", DownloadState.Completed, 1_000L, file.absolutePath)

            assertEquals(listOf("row:es", "row:en", NUDGE), events)
        }

    @Test
    fun aRowThatAlreadyHasAStateKeepsItWhenTheLibraryEmitsAgain() =
        runTest(UnconfinedTestDispatcher()) {
            val file = movie("Heat.mkv", HASHABLE_BYTES)
            coordinator().start()
            upsert('a', "Heat", DownloadState.Completed, 1_000L, file.absolutePath)
            val spanish = checkNotNull(rows.get(id('a'), "es"))
            val downloaded = spanish.downloadedAt(NOW + 1, "/vol/Movies/a/subs/Heat.es.opensubtitles.srt")
            rows.save(downloaded)

            repo.updatePlayback(id('a'), positionMs = 60_000L, audioTrackId = null, subtitleTrackId = null)

            assertEquals(downloaded, rows.get(id('a'), "es"))
            assertEquals(listOf(id('a')), notified)
        }

    @Test
    fun aMovieWhoseRowsCannotBeStoredDoesNotStopTheCoordinator() =
        runTest(UnconfinedTestDispatcher()) {
            failing = id('a')
            val file = movie("Ronin.mkv", HASHABLE_BYTES)
            coordinator().start()
            upsert('a', "Heat", DownloadState.Completed, 1_000L)
            assertEquals(listOf(NUDGE), events)

            upsert('b', "Ronin", DownloadState.Completed, 2_000L, file.absolutePath)
            assertEquals(listOf(NUDGE, "row:es", "row:en", NUDGE), events)
            assertEquals(listOf(id('a'), id('b')), notified)
        }

    private companion object {
        const val NOW = 5_000L
        const val NUDGE = "nudge"

        /** Long enough for #279's two 64 KiB blocks; one byte shorter and it has no moviehash. */
        const val HASHABLE_BYTES = 300_000
        const val TOO_SHORT_BYTES = 2 * 64 * 1024 - 1
    }
}

/** Records every row the coordinator stores, and fails for one movie when the test asks it to. */
private class RecordingFetches(
    private val delegate: SubtitleFetchRepository,
    private val events: MutableList<String>,
    private val failing: () -> TorrentId?,
) : SubtitleFetchRepository by delegate {
    override suspend fun ensurePending(
        id: TorrentId,
        language: String,
        movieHash: String?,
        now: Long,
    ): SubtitleFetch {
        if (failing() == id) throw IllegalStateException("no room left on the volume")
        events += "row:$language"
        return delegate.ensurePending(id, language, movieHash, now)
    }
}
