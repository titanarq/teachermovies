package com.teachermovies.player.streaming

import com.teachermovies.core.model.TorrentId
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.fake.FakePlayer
import com.teachermovies.torrent.api.EngineError
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.RangeReadiness
import com.teachermovies.torrent.api.TorrentEngine
import com.teachermovies.torrent.fake.FakeTorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The file is 100 pieces of 1 KiB lasting 100 s, so one millisecond is 1.024 bytes: position
 * `50_000` ms sits at byte `51_200`, the start of piece 50. The policy asks for a 2-piece head, a
 * 1-piece tail and a 4-piece start buffer, prioritises 8 pieces ahead, waits under 2 pieces and
 * resumes at 6 pieces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingPlaybackControllerTest {
    private val piece = 1024
    private val pieces = 100
    private val fileSize = piece.toLong() * pieces
    private val duration = 100_000L
    private val poll = 500L
    private val policy =
        StreamPolicy(
            headBytes = 2L * piece,
            tailBytes = 1L * piece,
            startBufferBytes = 4L * piece,
            readAheadBytes = 8L * piece,
            underrunBytes = 2L * piece,
            resumeBytes = 6L * piece,
        )
    private val id = TorrentId("0123456789abcdef0123456789abcdef01234567")
    private val file = File("movie.mkv")

    private val fakeEngine = FakeTorrentEngine()
    private val player = FakePlayer()

    /** Head 0-1 and start buffer 0-3 merged, plus the tail piece 99. */
    private val openAtZero = (0..3).toSet() + 99

    /** Adds the torrent with its metadata, so [FakeTorrentEngine.rangeReadiness] answers. */
    private suspend fun addTorrent() {
        fakeEngine.addMagnet("magnet:?xt=urn:btih:${id.value}&dn=movie")
        fakeEngine.emitMetadata(id, "movie", listOf("movie.mkv" to fileSize))
    }

    private fun have(pieces: Set<Int>) = fakeEngine.setPieces(id, piece, pieces)

    private fun controller(
        scope: CoroutineScope,
        engine: TorrentEngine = fakeEngine,
    ) = StreamingPlaybackController(
        player = player,
        engine = engine,
        scope = scope,
        policy = policy,
        pollIntervalMs = poll,
        minWindowMoveBytes = piece.toLong(),
    )

    private fun TestScope.startAsync(
        controller: StreamingPlaybackController,
        startPositionMs: Long = 0L,
    ) = backgroundScope.async { controller.start(id, 0, file, fileSize, duration, startPositionMs) }

    /** Answers every range query with [answer], delegating everything else to [fakeEngine]. */
    private class ScriptedEngine(
        private val delegate: FakeTorrentEngine,
        var answer: EngineResult<RangeReadiness>,
    ) : TorrentEngine by delegate {
        override suspend fun rangeReadiness(
            id: TorrentId,
            fileIndex: Int,
            byteOffset: Long,
            lengthBytes: Long,
        ): EngineResult<RangeReadiness> = answer
    }

    // -- The readiness gate --

    @Test
    fun startsIdle() =
        runTest {
            assertEquals(StreamState.Idle, controller(backgroundScope).state.value)
        }

    @Test
    fun aHoleInTheHeadKeepsTheFileClosedUntilTheTickAfterItArrives() =
        runTest {
            addTorrent()
            have(openAtZero - 1)
            val controller = controller(backgroundScope)
            val result = startAsync(controller)
            runCurrent()

            assertEquals(PlayerState.Idle, player.state.value)
            assertEquals(
                StreamState.Preparing(readyBytes = 1L * piece + piece, requiredBytes = 5L * piece),
                controller.state.value,
            )

            advanceTimeBy(3 * poll)
            runCurrent()
            assertEquals(PlayerState.Idle, player.state.value)

            have(openAtZero)
            advanceTimeBy(poll - 1)
            runCurrent()
            assertEquals(PlayerState.Idle, player.state.value)
            advanceTimeBy(1)
            runCurrent()

            assertEquals(StreamResult.Opened, result.await())
            assertEquals(PlayerState.Playing, player.state.value)
            assertEquals(StreamState.Streaming, controller.state.value)
        }

    @Test
    fun aHoleInTheTailKeepsTheFileClosed() =
        runTest {
            addTorrent()
            have(openAtZero - 99)
            val controller = controller(backgroundScope)
            val result = startAsync(controller)
            advanceTimeBy(5 * poll)
            runCurrent()
            assertEquals(PlayerState.Idle, player.state.value)
            assertTrue(controller.state.value is StreamState.Preparing)

            have(openAtZero)
            advanceTimeBy(poll)
            runCurrent()
            assertEquals(StreamResult.Opened, result.await())
            assertEquals(PlayerState.Playing, player.state.value)
        }

    @Test
    fun aHoleInTheStartBufferKeepsTheFileClosedAndOpensAtTheStartPosition() =
        runTest {
            addTorrent()
            val needed = setOf(0, 1, 50, 51, 52, 53, 99)
            have(needed - 52)
            val controller = controller(backgroundScope)
            val result = startAsync(controller, startPositionMs = 50_000L)
            advanceTimeBy(5 * poll)
            runCurrent()
            assertEquals(PlayerState.Idle, player.state.value)

            have(needed)
            advanceTimeBy(poll)
            runCurrent()
            assertEquals(StreamResult.Opened, result.await())
            assertEquals(50_000L, player.positionMs.value)
            assertEquals(PlayerState.Playing, player.state.value)
        }

    @Test
    fun theFirstOpenRangeIsPrioritisedBeforeWaiting() =
        runTest {
            addTorrent()
            val controller = controller(backgroundScope)
            startAsync(controller)
            runCurrent()
            assertEquals(Triple(0, 0L, 4L * piece), fakeEngine.lastWindow(id))
        }

    @Test
    fun anUnknownTorrentAnswersUnknownTorrentWithoutOpening() =
        runTest {
            val controller = controller(backgroundScope)
            val result = startAsync(controller)
            runCurrent()
            assertEquals(StreamResult.UnknownTorrent, result.await())
            assertEquals(PlayerState.Idle, player.state.value)
            assertEquals(StreamState.Idle, controller.state.value)
        }

    @Test
    fun anUnsupportedEngineAnswersUnsupportedWithoutOpening() =
        runTest {
            addTorrent()
            val engine = ScriptedEngine(fakeEngine, EngineResult.Failure(EngineError.Unsupported))
            val result = startAsync(controller(backgroundScope, engine))
            runCurrent()
            assertEquals(StreamResult.Unsupported, result.await())
            assertEquals(PlayerState.Idle, player.state.value)
        }

    @Test
    fun anIoFailureAnswersFailedWithoutOpening() =
        runTest {
            addTorrent()
            val engine = ScriptedEngine(fakeEngine, EngineResult.Failure(EngineError.Io("disk gone")))
            val controller = controller(backgroundScope, engine)
            val result = startAsync(controller)
            runCurrent()
            assertEquals(StreamResult.Failed("disk gone"), result.await())
            assertEquals(StreamState.Failed("disk gone"), controller.state.value)
            assertEquals(PlayerState.Idle, player.state.value)
        }

    @Test
    fun notReadyIsRetriedUntilTheMetadataArrives() =
        runTest {
            fakeEngine.addMagnet("magnet:?xt=urn:btih:${id.value}&dn=movie")
            val controller = controller(backgroundScope)
            val result = startAsync(controller)
            advanceTimeBy(4 * poll)
            runCurrent()
            assertFalse(result.isCompleted)
            assertEquals(PlayerState.Idle, player.state.value)
            assertTrue(controller.state.value is StreamState.Preparing)

            fakeEngine.emitMetadata(id, "movie", listOf("movie.mkv" to fileSize))
            have(openAtZero)
            advanceTimeBy(poll)
            runCurrent()
            assertEquals(StreamResult.Opened, result.await())
            assertEquals(PlayerState.Playing, player.state.value)
        }

    // -- The window feed --

    private val everyPiece = (0 until pieces).toSet()

    /** Opens the file at [startPositionMs] with every piece on disk, so no buffering interferes. */
    private suspend fun TestScope.openFully(startPositionMs: Long = 0L): StreamingPlaybackController {
        addTorrent()
        have(everyPiece)
        val controller = controller(backgroundScope)
        val result = startAsync(controller, startPositionMs)
        runCurrent()
        assertEquals(StreamResult.Opened, result.await())
        return controller
    }

    private fun windowCalls() = fakeEngine.recordedCalls.filter { it.startsWith("prioritizeWindow(") }

    @Test
    fun theWindowAtTheStartPositionIsPrioritisedOnceOpened() =
        runTest {
            openFully()
            assertEquals(Triple(0, 0L, 8L * piece), fakeEngine.lastWindow(id))
        }

    @Test
    fun anIdlePositionSendsNothing() =
        runTest {
            openFully()
            val before = windowCalls().size
            advanceTimeBy(10 * poll)
            runCurrent()
            assertEquals(before, windowCalls().size)
        }

    @Test
    fun aPositionThatBarelyAdvancesSendsNothing() =
        runTest {
            openFully()
            val before = windowCalls().size
            // 999 ms is 1022 bytes, under the 1024-byte minimum move.
            for (ms in listOf(200L, 500L, 800L, 999L)) {
                player.emitPosition(ms)
                runCurrent()
            }
            assertEquals(before, windowCalls().size)
        }

    @Test
    fun theWindowIsPrioritisedOncePerMoveBeyondTheMinimum() =
        runTest {
            openFully()
            val before = windowCalls().size
            player.emitPosition(1_000L)
            runCurrent()
            player.emitPosition(1_500L)
            runCurrent()
            player.emitPosition(2_000L)
            runCurrent()

            val sent = windowCalls().drop(before)
            assertEquals(
                listOf(
                    "prioritizeWindow(${id.value},0,1024,${8 * piece})",
                    "prioritizeWindow(${id.value},0,2048,${8 * piece})",
                ),
                sent,
            )
        }

    @Test
    fun aSeekReprioritisesAtTheNewOffsetWithExactlyOneCall() =
        runTest {
            openFully()
            player.emitDuration(duration)
            runCurrent()
            val before = windowCalls().size

            player.seekTo(50_000L)
            runCurrent()

            assertEquals(listOf("prioritizeWindow(${id.value},0,51200,${8 * piece})"), windowCalls().drop(before))
            assertEquals(Triple(0, 51_200L, 8L * piece), fakeEngine.lastWindow(id))
        }

    // -- The underrun rule --

    /**
     * Opens at 0 with only [openAtZero] on disk, then moves to 2 500 ms (byte 2 560): 1 536 bytes
     * are ready before the hole at piece 4, under the 2-piece underrun threshold.
     */
    private suspend fun TestScope.openAndRunDry(): StreamingPlaybackController {
        addTorrent()
        have(openAtZero)
        val controller = controller(backgroundScope)
        val result = startAsync(controller)
        runCurrent()
        assertEquals(StreamResult.Opened, result.await())
        player.emitDuration(duration)
        player.emitPosition(2_500L)
        tick()
        return controller
    }

    private fun TestScope.tick() {
        advanceTimeBy(poll)
        runCurrent()
    }

    @Test
    fun enoughBytesAheadKeepsStreaming() =
        runTest {
            addTorrent()
            have(openAtZero)
            val controller = controller(backgroundScope)
            startAsync(controller)
            runCurrent()
            // At byte 0 4 096 bytes are ready: between the thresholds, but the decision is Play.
            tick()
            tick()
            assertEquals(StreamState.Streaming, controller.state.value)
            assertEquals(PlayerState.Playing, player.state.value)
        }

    @Test
    fun aHoleUnderTheUnderrunThresholdPausesAndPublishesBuffering() =
        runTest {
            val controller = openAndRunDry()
            assertEquals(PlayerState.Paused, player.state.value)
            assertEquals(StreamState.Buffering(readyBytes = 1_536L, resumeAtBytes = 6L * piece), controller.state.value)
        }

    @Test
    fun refillingPastTheResumeThresholdPlaysAgain() =
        runTest {
            val controller = openAndRunDry()
            have((0..8).toSet() + 99)
            tick()
            assertEquals(PlayerState.Playing, player.state.value)
            assertEquals(StreamState.Streaming, controller.state.value)
        }

    @Test
    fun readinessBetweenTheThresholdsChangesNothingWhileBuffering() =
        runTest {
            val controller = openAndRunDry()
            // Pieces up to 5: 3 584 bytes ready from byte 2 560, above underrun, below resume.
            have((0..5).toSet() + 99)
            tick()
            tick()
            assertEquals(PlayerState.Paused, player.state.value)
            assertEquals(StreamState.Buffering(readyBytes = 3_584L, resumeAtBytes = 6L * piece), controller.state.value)
        }

    @Test
    fun aViewerPauseIsNotUndoneByTheController() =
        runTest {
            addTorrent()
            have(openAtZero)
            val controller = controller(backgroundScope)
            startAsync(controller)
            runCurrent()
            player.pause()
            runCurrent()
            tick()
            tick()
            assertEquals(PlayerState.Paused, player.state.value)

            // An underrun and a refill while the viewer holds the pause: still paused.
            player.emitDuration(duration)
            player.emitPosition(2_500L)
            tick()
            assertTrue(controller.state.value is StreamState.Buffering)
            have((0..8).toSet() + 99)
            tick()
            assertEquals(StreamState.Streaming, controller.state.value)
            assertEquals(PlayerState.Paused, player.state.value)
        }

    @Test
    fun aPauseTheViewerTakesOverWhileBufferingIsNotUndone() =
        runTest {
            val controller = openAndRunDry()
            // The viewer plays into the hole and pauses again: that pause is now the viewer's.
            player.play()
            runCurrent()
            player.pause()
            runCurrent()
            have((0..8).toSet() + 99)
            tick()
            assertEquals(StreamState.Streaming, controller.state.value)
            assertEquals(PlayerState.Paused, player.state.value)
        }

    @Test
    fun theEndOfTheFilePlaysOnInsteadOfBuffering() =
        runTest {
            addTorrent()
            have(openAtZero)
            val controller = controller(backgroundScope)
            startAsync(controller)
            runCurrent()
            player.emitDuration(duration)
            // 97 000 ms is byte 99 328 (piece 97, missing); plus the resume bytes it passes the end.
            player.emitPosition(97_000L)
            tick()
            tick()
            assertEquals(StreamState.Streaming, controller.state.value)
            assertEquals(PlayerState.Playing, player.state.value)
        }

    // -- Stopping --

    @Test
    fun stopClearsTheWindowAndPublishesIdle() =
        runTest {
            val controller = openFully()
            controller.stop()
            runCurrent()
            assertEquals(null, fakeEngine.lastWindow(id))
            assertEquals("clearWindow(${id.value})", fakeEngine.recordedCalls.last())
            assertEquals(StreamState.Idle, controller.state.value)

            // Both loops are gone: a move sends nothing and a hole pauses nothing.
            val before = fakeEngine.recordedCalls.size
            player.emitDuration(duration)
            player.emitPosition(50_000L)
            have(emptySet())
            tick()
            assertEquals(before, fakeEngine.recordedCalls.size)
            assertEquals(PlayerState.Playing, player.state.value)
        }

    @Test
    fun stopOnAnIdleControllerIsANoOp() =
        runTest {
            val controller = controller(backgroundScope)
            controller.stop()
            assertEquals(StreamState.Idle, controller.state.value)
            assertTrue(fakeEngine.recordedCalls.isEmpty())
        }

    @Test
    fun reachingTheEndClearsTheWindowAndPublishesIdle() =
        runTest {
            val controller = openFully()
            player.emitDuration(duration)
            runCurrent()
            player.end()
            runCurrent()
            assertEquals(null, fakeEngine.lastWindow(id))
            assertEquals(StreamState.Idle, controller.state.value)
        }

    @Test
    fun aPlayerErrorClearsTheWindowAndPublishesFailed() =
        runTest {
            val controller = openFully()
            player.fail("codec missing")
            runCurrent()
            assertEquals(null, fakeEngine.lastWindow(id))
            assertEquals(StreamState.Failed("codec missing"), controller.state.value)
        }
}
