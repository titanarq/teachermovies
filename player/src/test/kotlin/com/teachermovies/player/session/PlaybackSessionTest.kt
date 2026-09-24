package com.teachermovies.player.session

import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.api.Track
import com.teachermovies.player.fake.FakePlayer
import com.teachermovies.player.streaming.StreamPolicy
import com.teachermovies.player.streaming.StreamResult
import com.teachermovies.player.streaming.StreamState
import com.teachermovies.player.streaming.StreamingPlaybackController
import com.teachermovies.torrent.api.EngineError
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.RangeReadiness
import com.teachermovies.torrent.api.TorrentEngine
import com.teachermovies.torrent.api.TorrentFileInfo
import com.teachermovies.torrent.fake.FakeTorrentEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
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

    // -- openStreaming: an in-progress download, supervised by StreamingPlaybackController --------

    // 100 pieces of 1 KiB; the controller needs a 2-piece head, a 4-piece start buffer (merged with
    // the head, at byte 0 while the duration is unknown) and a 1-piece tail before it opens.
    private val piece = 1024
    private val fileSize = piece * 100L
    private val poll = 500L
    private val openRangePieces = (0..3).toSet() + 99
    private val engine = FakeTorrentEngine()

    private fun TestScope.controller(streamEngine: TorrentEngine = engine) =
        StreamingPlaybackController(
            player = player,
            engine = streamEngine,
            scope = backgroundScope,
            policy =
                StreamPolicy(
                    headBytes = 2L * piece,
                    tailBytes = 1L * piece,
                    startBufferBytes = 4L * piece,
                    readAheadBytes = 8L * piece,
                    underrunBytes = 2L * piece,
                    resumeBytes = 6L * piece,
                ),
            pollIntervalMs = poll,
            minWindowMoveBytes = piece.toLong(),
        )

    /** A torrent still downloading, with [movie] as its main file (or none picked yet). */
    private suspend fun seedInProgress(
        movie: File?,
        positionMs: Long = 0L,
        audioTrackId: String? = null,
        subtitleTrackId: String? = null,
    ) {
        repo.upsert(
            Torrent(
                id = id,
                name = "Movie",
                state = DownloadState.Downloading,
                progressPercent = 12.0,
                downloadedBytes = 12L * piece,
                totalBytes = fileSize,
                savePath = tmp.root.path,
                mainFileIndex = movie?.let { 0 },
                errorMessage = null,
            ),
            mainFilePath = movie?.path,
            now = 1L,
        )
        repo.updatePlayback(id, positionMs, audioTrackId, subtitleTrackId)
    }

    /** The engine knows the torrent and its single file, with [pieces] downloaded. */
    private suspend fun engineHas(pieces: Set<Int>) {
        engine.addMagnet("magnet:?xt=urn:btih:${id.value}&dn=movie")
        engine.emitMetadata(id, "Movie", listOf("Movie.mkv" to fileSize))
        engine.setPieces(id, piece, pieces)
    }

    /** Answers every range query with [answer], delegating everything else to [engine]. */
    private class ScriptedEngine(
        delegate: FakeTorrentEngine,
        private val answer: EngineResult<RangeReadiness>,
    ) : TorrentEngine by delegate {
        override suspend fun rangeReadiness(
            id: TorrentId,
            fileIndex: Int,
            byteOffset: Long,
            lengthBytes: Long,
        ): EngineResult<RangeReadiness> = answer

        override suspend fun prioritizeWindow(
            id: TorrentId,
            fileIndex: Int,
            byteOffset: Long,
            lengthBytes: Long,
        ): EngineResult<Unit> = EngineResult.Ok(Unit)
    }

    @Test
    fun openStreamingOpensAGrowingFileOnceItsRangesAreReady() =
        runTest {
            val movie = movieFile()
            seedInProgress(movie)
            engineHas(openRangePieces - 99)
            val result = backgroundScope.async { session().openStreaming(id, controller()) }
            runCurrent()

            assertFalse(result.isCompleted)
            assertNull(player.lastOpen)

            engine.setPieces(id, piece, openRangePieces)
            advanceTimeBy(poll)
            runCurrent()

            assertTrue(result.await() is SessionResult.Opened)
            assertEquals(FakePlayer.OpenCall(movie, 0L, growing = true), player.lastOpen)
            assertEquals(PlayerState.Playing, player.state.value)
        }

    @Test
    fun openStreamingResumesAtThePersistedPositionThroughResumePolicy() =
        runTest {
            val movie = movieFile()
            seedInProgress(movie, positionMs = 600_000L)
            engineHas(openRangePieces)

            val result = session().openStreaming(id, controller())

            assertTrue(result is SessionResult.Opened)
            assertEquals(FakePlayer.OpenCall(movie, 597_000L, growing = true), player.lastOpen)
        }

    @Test
    fun openStreamingReappliesThePersistedTracksAndAddsExternalSubtitles() =
        runTest {
            val movie = movieFile()
            File(movie.parentFile, "Movie.en.srt").writeText("1")
            seedInProgress(movie, audioTrackId = "a1", subtitleTrackId = "s2")
            engineHas(openRangePieces)

            session().openStreaming(id, controller())
            runCurrent()
            assertEquals(listOf("Movie.en.srt"), player.subtitleTracks.value.map { it.name })

            player.emitTracks(audio = listOf(audioEs, audioEn), subs = listOf(subEn, subEs))
            runCurrent()

            assertEquals("a1", player.selectedAudioId.value)
            assertEquals("s2", player.selectedSubtitleId.value)
        }

    @Test
    fun openStreamingSavesProgressAndCloseStopsTheController() =
        runTest {
            seedInProgress(movieFile())
            engineHas(openRangePieces)
            val controller = controller()
            val session = session()
            session.openStreaming(id, controller)
            runCurrent()
            assertEquals(StreamState.Streaming, controller.state.value)

            player.emitPosition(3_000L)
            session.close()

            assertEquals(3_000L, checkNotNull(repo.getPlaybackItem(id)).lastPositionMs)
            assertEquals(StreamState.Idle, controller.state.value)
            assertEquals(PlayerState.Idle, player.state.value)
        }

    @Test
    fun openStreamingReportsNotFoundForAnUnknownTorrent() =
        runTest {
            assertEquals(SessionResult.NotFound, session().openStreaming(id, controller()))
            assertNull(player.lastOpen)
        }

    @Test
    fun openStreamingReportsFileMissingWhileNoMainFileIsSelected() =
        runTest {
            seedInProgress(movie = null)
            engineHas(openRangePieces)

            assertEquals(SessionResult.FileMissing(tmp.root.path), session().openStreaming(id, controller()))
            assertNull(player.lastOpen)
        }

    @Test
    fun openStreamingReportsATorrentTheEngineDoesNotKnow() =
        runTest {
            seedInProgress(movieFile())

            assertEquals(
                SessionResult.StreamingFailed(StreamResult.UnknownTorrent),
                session().openStreaming(id, controller()),
            )
            assertNull(player.lastOpen)
        }

    @Test
    fun openStreamingReportsAnEngineThatCannotStream() =
        runTest {
            seedInProgress(movieFile())
            engineHas(emptySet())
            val scripted = ScriptedEngine(engine, EngineResult.Failure(EngineError.Unsupported))

            assertEquals(
                SessionResult.StreamingFailed(StreamResult.Unsupported),
                session().openStreaming(id, controller(scripted)),
            )
            assertNull(player.lastOpen)
        }

    @Test
    fun openStreamingReportsAnEngineFailure() =
        runTest {
            seedInProgress(movieFile())
            engineHas(emptySet())
            val scripted = ScriptedEngine(engine, EngineResult.Failure(EngineError.Io("disk gone")))

            assertEquals(
                SessionResult.StreamingFailed(StreamResult.Failed("disk gone")),
                session().openStreaming(id, controller(scripted)),
            )
            assertNull(player.lastOpen)
        }

    /** Records every range the controller asks about, delegating to [delegate]. */
    private class RecordingEngine(
        private val delegate: FakeTorrentEngine,
    ) : TorrentEngine by delegate {
        val ranges = mutableListOf<Pair<Long, Long>>()

        override suspend fun rangeReadiness(
            id: TorrentId,
            fileIndex: Int,
            byteOffset: Long,
            lengthBytes: Long,
        ): EngineResult<RangeReadiness> {
            ranges += byteOffset to lengthBytes
            return delegate.rangeReadiness(id, fileIndex, byteOffset, lengthBytes)
        }
    }

    @Test
    fun openStreamingPassesTheMainFilesSizeNotTheTorrentsTotalBytes() =
        runTest {
            val movie = movieFile()
            val extras = 30L * piece
            seedInProgress(movie)
            // The repository's totalBytes counts the sample and the subtitles too.
            repo.upsert(checkNotNull(repo.get(id)).copy(totalBytes = fileSize + extras), movie.path, now = 2L)
            engine.addMagnet("magnet:?xt=urn:btih:${id.value}&dn=movie")
            engine.emitMetadata(
                id,
                "Movie",
                listOf("Movie.mkv" to fileSize, "Sample/sample.mkv" to extras - piece, "Movie.en.srt" to piece.toLong()),
            )
            engine.setPieces(id, piece, (0 until 130).toSet())
            val recording = RecordingEngine(engine)

            val result = session().openStreaming(id, controller(recording))

            assertTrue(result is SessionResult.Opened)
            // The tail range ends at the main file's last byte, not at the torrent's totalBytes.
            assertEquals(fileSize, recording.ranges.maxOf { (offset, length) -> offset + length })
            assertTrue(recording.ranges.contains(fileSize - piece to piece.toLong()))
        }

    @Test
    fun openStreamingReportsFileMissingWhileTheMainFilesSizeIsUnknown() =
        runTest {
            val movie = movieFile()
            seedInProgress(movie)
            // The engine knows the torrent, but its metadata (and so its file list) has not arrived.
            engine.addMagnet("magnet:?xt=urn:btih:${id.value}&dn=movie")

            assertEquals(SessionResult.FileMissing(movie.path), session().openStreaming(id, controller()))
            assertNull(player.lastOpen)
        }

    @Test
    fun openStreamingReportsAnIoFailureFromTheSizeLookup() =
        runTest {
            seedInProgress(movieFile())
            engineHas(openRangePieces)
            val failing =
                object : TorrentEngine by engine {
                    override suspend fun files(id: TorrentId): EngineResult<List<TorrentFileInfo>> =
                        EngineResult.Failure(EngineError.Io("disk gone"))
                }

            assertEquals(
                SessionResult.StreamingFailed(StreamResult.Failed("disk gone")),
                session().openStreaming(id, controller(failing)),
            )
            assertNull(player.lastOpen)
        }
}
