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
import com.teachermovies.player.api.EmbeddedTextTracks
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

    /**
     * A movie file in the temporary folder that is a real Matroska carrying one text subtitle track
     * per entry of [languages], so what the test says the movie has, it has.
     */
    private fun container(
        name: String,
        vararg languages: String,
    ): File =
        File(tempFolder.root, name)
            .apply { writeBytes(MiniMatroska.withSubtitleTracks(*languages)) }

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
    fun aMovieWithAnEnglishSidecarNextToItStillGetsTheEnglishRow() =
        runTest(UnconfinedTestDispatcher()) {
            val file = movie("Heat.mkv", HASHABLE_BYTES)
            File(tempFolder.root, "Heat.en.srt")
                .writeText("1\n00:00:01,000 --> 00:00:02,000\nHello there\n")
            coordinator().start()
            upsert('a', "Heat", DownloadState.Completed, 1_000L, file.absolutePath)

            assertEquals(SubtitleFetchState.Pending, rows.get(id('a'), "es")?.state)
            assertEquals(SubtitleFetchState.Pending, rows.get(id('a'), "en")?.state)
        }

    @Test
    fun aMovieWhoseContainerCarriesAnEnglishTrackStillGetsTheEnglishRow() =
        runTest(UnconfinedTestDispatcher()) {
            val file = container("Heat.mkv", "spa", "eng")
            // The premise, checked with :player's own probe: this movie really does carry English.
            assertEquals(listOf("spa", "eng"), EmbeddedTextTracks.languagesOf(file))
            coordinator().start()
            upsert('a', "Heat", DownloadState.Completed, 1_000L, file.absolutePath)

            assertEquals(SubtitleFetchState.Pending, rows.get(id('a'), "es")?.state)
            assertEquals(SubtitleFetchState.Pending, rows.get(id('a'), "en")?.state)
        }

    @Test
    fun aMovieWhoseContainerCarriesNoEnglishTrackStillGetsBothRows() =
        runTest(UnconfinedTestDispatcher()) {
            val file = container("Heat.mkv", "spa", "fra")
            assertEquals(listOf("spa", "fra"), EmbeddedTextTracks.languagesOf(file))
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
    fun aMovieStoredWithOnlyASpanishRowGetsItsEnglishOneOnStartWithoutANudge() =
        runTest(UnconfinedTestDispatcher()) {
            val file = movie("Heat.mkv", HASHABLE_BYTES)
            upsert('a', "Heat", DownloadState.Completed, 1_000L, file.absolutePath)
            // The library of an app that ran before #359: Spanish was always written, English only
            // when the movie had none, so this one is missing its `"en"` row.
            val spanish = SubtitleFetch.pending(id('a'), "es", "0123456789abcdef", 1_000L)
            rows.save(spanish)

            coordinator().start()

            assertEquals(emptyList<TorrentId>(), notified)
            assertEquals(spanish, rows.get(id('a'), "es"))
            assertEquals(SubtitleFetchState.Pending, rows.get(id('a'), "en")?.state)
            assertEquals(listOf("row:es", "row:en"), events)
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

/**
 * Just enough of a Matroska muxer for a test movie's container: an EBML header, a Segment, and one
 * `S_TEXT/UTF8` subtitle track per language, with no `Info`, no `Cluster` and no media payload --
 * all that `:player`'s header-only probe reads. `:player` keeps its own test muxer inside its test
 * source set, so the few elements this needs are written out here; ids and sizes are RFC 9559's,
 * every size the shortest vint that holds it.
 */
private object MiniMatroska {
    private const val EBML = 0x1A45DFA3L
    private const val DOC_TYPE = 0x4282L
    private const val SEGMENT = 0x18538067L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_NUMBER = 0xD7L
    private const val TRACK_TYPE = 0x83L
    private const val CODEC_ID = 0x86L
    private const val LANGUAGE = 0x22B59CL
    private const val TYPE_SUBTITLE = 0x11L
    private const val DOC_TYPE_MATROSKA = "matroska"
    private const val CODEC_UTF8 = "S_TEXT/UTF8"

    /** The bytes of a whole file whose only tracks are one text subtitle track per [languages] entry. */
    fun withSubtitleTracks(vararg languages: String): ByteArray {
        val tracks = languages.mapIndexed { index, language -> trackEntry(index + 1L, language) }
        return element(EBML, string(DOC_TYPE, DOC_TYPE_MATROSKA)) +
            element(SEGMENT, element(TRACKS, *tracks.toTypedArray()))
    }

    private fun trackEntry(
        number: Long,
        language: String,
    ): ByteArray =
        element(
            TRACK_ENTRY,
            uint(TRACK_NUMBER, number),
            uint(TRACK_TYPE, TYPE_SUBTITLE),
            string(CODEC_ID, CODEC_UTF8),
            string(LANGUAGE, language),
        )

    private fun element(
        id: Long,
        vararg children: ByteArray,
    ): ByteArray {
        val body = children.fold(ByteArray(0)) { whole, child -> whole + child }
        return idBytes(id) + sizeBytes(body.size.toLong()) + body
    }

    private fun uint(
        id: Long,
        value: Long,
    ): ByteArray {
        var bytes = ByteArray(0)
        var rest = value
        do {
            bytes = byteArrayOf((rest and 0xFF).toByte()) + bytes
            rest = rest ushr 8
        } while (rest != 0L)
        return element(id, bytes)
    }

    private fun string(
        id: Long,
        value: String,
    ): ByteArray = element(id, value.toByteArray(Charsets.UTF_8))

    /** An id is written as it stands, its marker bit included: `0x1A45DFA3` is four bytes, `0xD7` one. */
    private fun idBytes(id: Long): ByteArray {
        var bytes = ByteArray(0)
        var rest = id
        while (rest != 0L) {
            bytes = byteArrayOf((rest and 0xFF).toByte()) + bytes
            rest = rest ushr 8
        }
        return bytes
    }

    private fun sizeBytes(size: Long): ByteArray {
        var length = 1
        while (size >= (1L shl (7 * length)) - 1) length++
        val bytes = ByteArray(length)
        var rest = size
        for (i in length - 1 downTo 0) {
            bytes[i] = (rest and 0xFF).toByte()
            rest = rest ushr 8
        }
        bytes[0] = (bytes[0].toInt() or (0x80 ushr (length - 1))).toByte()
        return bytes
    }
}
