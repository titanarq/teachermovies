package com.teachermovies.player.session

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.api.Track
import com.teachermovies.player.fake.FakePlayer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val id = TorrentId("a".repeat(40))
    private val player = FakePlayer()
    private val repo = InMemoryTorrentRepository()
    private var now = 1_000_000L

    private fun TestScope.session(scope: CoroutineScope = backgroundScope) =
        PlaybackSession(player, repo, scope, clock = { now })

    /** A completed library item whose main file is [movie], with the given persisted playback. */
    private suspend fun seed(
        movie: File,
        positionMs: Long = 0L,
        audioTrackId: String? = null,
        subtitleTrackId: String? = null,
    ) {
        repo.upsert(
            Torrent(
                id = id,
                name = "Movie",
                state = DownloadState.Completed,
                progressPercent = 100.0,
                downloadedBytes = 10L,
                totalBytes = 10L,
                savePath = movie.parent,
                mainFileIndex = 0,
                errorMessage = null,
            ),
            mainFilePath = movie.path,
            now = 1L,
        )
        repo.updatePlayback(id, positionMs, audioTrackId, subtitleTrackId)
    }

    private fun movieFile(): File = tmp.newFolder("Movies", id.value).resolve("Movie.mkv").apply { writeText("x") }

    @Test
    fun openReportsNotFoundForAnUnknownItem() =
        runTest {
            assertEquals(SessionResult.NotFound, session().open(id))
            assertEquals(PlayerState.Idle, player.state.value)
        }

    @Test
    fun openReportsFileMissingWhenTheMediaIsGone() =
        runTest {
            val movie = File(tmp.root, "gone/Movie.mkv")
            seed(movie)

            assertEquals(SessionResult.FileMissing(movie.path), session().open(id))
            assertEquals(PlayerState.Idle, player.state.value)
        }

    @Test
    fun openResumesThroughResumePolicy() =
        runTest {
            val movie = movieFile()
            seed(movie, positionMs = 600_000L)

            val result = session().open(id)

            assertTrue(result is SessionResult.Opened)
            assertEquals(PlayerState.Opening, player.state.value)
            assertEquals(597_000L, player.positionMs.value)
        }

    @Test
    fun openStartsOverWhenBarelyStarted() =
        runTest {
            seed(movieFile(), positionMs = 4_000L)

            session().open(id)

            assertEquals(0L, player.positionMs.value)
        }

    @Test
    fun openAddsSubtitlesFromTheMovieFolderAndItsSubsFolderUnselected() =
        runTest {
            val movie = movieFile()
            val folder = movie.parentFile
            File(folder, "Movie.en.srt").writeText("1")
            File(folder, "Movie.ASS").writeText("1")
            File(folder, "notes.txt").writeText("1")
            File(folder, "subs").mkdir()
            File(folder, "subs/Spanish.vtt").writeText("1")
            File(folder, "subs/Signs.ssa").writeText("1")
            File(folder, "other").mkdir()
            File(folder, "other/Elsewhere.srt").writeText("1")
            seed(movie)

            session().open(id)

            assertEquals(
                listOf("Movie.ASS", "Movie.en.srt", "Signs.ssa", "Spanish.vtt"),
                player.subtitleTracks.value.map { it.name },
            )
            assertEquals(null, player.selectedSubtitleId.value)
        }

    private val audioEs = Track("a1", "Spanish", "spa")
    private val audioEn = Track("a2", "English 5.1", "eng")
    private val subEn = Track("s1", "English", "eng")
    private val subEs = Track("s2", "Spanish", "spa")

    private suspend fun persisted() = checkNotNull(repo.getLibraryItem(id))

    @Test
    fun persistedTracksAreReappliedWhenTracksFirstAppear() =
        runTest {
            seed(movieFile(), audioTrackId = "a1", subtitleTrackId = "s2")
            session().open(id)
            runCurrent()
            assertNull(player.selectedAudioId.value)

            player.emitTracks(audio = listOf(audioEs, audioEn), subs = listOf(subEn, subEs))
            runCurrent()

            assertEquals("a1", player.selectedAudioId.value)
            assertEquals("s2", player.selectedSubtitleId.value)
        }

    @Test
    fun withoutPersistedTracksEnglishAudioIsPickedAndSubtitlesStayOff() =
        runTest {
            seed(movieFile())
            session().open(id)
            runCurrent()

            player.emitTracks(audio = listOf(audioEs, audioEn), subs = listOf(subEn))
            runCurrent()

            assertEquals("a2", player.selectedAudioId.value)
            assertNull(player.selectedSubtitleId.value)
        }

    @Test
    fun trackChangeIsSaved() =
        runTest {
            seed(movieFile())
            session().open(id)
            runCurrent()
            player.emitTracks(audio = listOf(audioEs, audioEn), subs = listOf(subEn))
            runCurrent()
            player.emitPosition(42_000L)

            player.selectSubtitle("s1")
            runCurrent()

            val item = persisted()
            assertEquals("a2", item.audioTrackId)
            assertEquals("s1", item.subtitleTrackId)
            assertEquals(42_000L, item.lastPositionMs)
        }

    @Test
    fun progressIsSavedEveryFiveSecondsWhilePlaying() =
        runTest {
            seed(movieFile(), audioTrackId = "a1")
            session().open(id)
            player.play()
            runCurrent()

            now += 1_000L
            player.emitPosition(1_000L)
            runCurrent()
            assertEquals(0L, persisted().lastPositionMs)

            now += 4_000L
            player.emitPosition(5_000L)
            runCurrent()
            assertEquals(5_000L, persisted().lastPositionMs)
            // Tracks not reported yet: the persisted choice is written back, not cleared.
            assertEquals("a1", persisted().audioTrackId)

            now += 1_000L
            player.emitPosition(6_000L)
            runCurrent()
            assertEquals(5_000L, persisted().lastPositionMs)

            now += 4_000L
            player.emitPosition(10_000L)
            runCurrent()
            assertEquals(10_000L, persisted().lastPositionMs)
        }

    @Test
    fun pauseSavesThePosition() =
        runTest {
            seed(movieFile())
            session().open(id)
            player.play()
            runCurrent()
            player.emitPosition(2_000L)
            runCurrent()

            player.pause()
            runCurrent()

            assertEquals(2_000L, persisted().lastPositionMs)
        }

    @Test
    fun endResetsThePositionToZero() =
        runTest {
            seed(movieFile(), positionMs = 600_000L)
            session().open(id)
            player.emitDuration(7_200_000L)
            player.play()
            runCurrent()

            player.end()
            runCurrent()

            assertEquals(0L, persisted().lastPositionMs)
        }

    @Test
    fun closeSavesThePositionAndReleasesThePlayer() =
        runTest {
            seed(movieFile())
            val session = session()
            session.open(id)
            player.play()
            runCurrent()
            player.emitPosition(3_000L)

            session.close()

            assertEquals(3_000L, persisted().lastPositionMs)
            assertEquals(PlayerState.Idle, player.state.value)

            // Nothing is watched any more: later player events write nothing.
            player.open(File("other.mkv"), 0L)
            player.play()
            now += 10_000L
            player.emitPosition(9_000L)
            runCurrent()
            assertEquals(3_000L, persisted().lastPositionMs)
        }
}
