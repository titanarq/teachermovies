package com.teachermovies.player.session

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.fake.FakePlayer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
