package com.teachermovies.assistant

import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.fake.FakePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LineCaptureControllerTest {
    private val first = SubtitleCue(index = 0, startMs = 10_000L, endMs = 12_000L, text = "Hello.")
    private val second = SubtitleCue(index = 1, startMs = 30_000L, endMs = 33_000L, text = "Bye.")
    private val track = SubtitleTrack(cues = listOf(first, second))

    private class Fixture(
        val player: FakePlayer,
        val engine: SubtitleEngine,
        val controllerJob: Job,
        val controller: LineCaptureController,
    ) {
        val activeWatchers: Int
            get() = controllerJob.children.count { it.isActive }
    }

    private fun TestScope.fixture(loadTrack: Boolean = true): Fixture {
        val player = FakePlayer()
        player.open(File("movie.mkv"))
        player.emitDuration(100_000L)
        player.play()
        val engine = SubtitleEngine(player.positionMs, backgroundScope)
        if (loadTrack) engine.load(track)
        // A child scope of its own, so the test can count the controller's coroutines.
        val controllerJob = Job(backgroundScope.coroutineContext.job)
        val controller =
            LineCaptureController(
                player,
                engine,
                CoroutineScope(backgroundScope.coroutineContext + controllerJob),
            )
        return Fixture(player, engine, controllerJob, controller)
    }

    @Test
    fun `capture inside a cue pauses and captures that cue at the current position`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(11_000L)

            assertEquals(CaptureResult.Captured, f.controller.capture())

            assertEquals(PlayerState.Paused, f.player.state.value)
            assertEquals(CapturedLine(first, capturedAtMs = 11_000L), f.controller.captured.value)
        }

    @Test
    fun `capture in a short gap right after a cue captures the previous cue`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(13_500L)

            assertEquals(CaptureResult.Captured, f.controller.capture())

            assertEquals(CapturedLine(first, capturedAtMs = 13_500L), f.controller.captured.value)
        }

    @Test
    fun `capture in a gap longer than maxGapMs returns NoLine and stays paused`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(20_000L)

            assertEquals(CaptureResult.NoLine, f.controller.capture())

            assertNull(f.controller.captured.value)
            assertEquals(PlayerState.Paused, f.player.state.value)
            assertEquals(20_000L, f.player.positionMs.value)
        }

    @Test
    fun `capture with no track loaded returns NoLine and stays paused`() =
        runTest {
            val f = fixture(loadTrack = false)
            f.player.emitPosition(11_000L)

            assertEquals(CaptureResult.NoLine, f.controller.capture())

            assertNull(f.controller.captured.value)
            assertEquals(PlayerState.Paused, f.player.state.value)
        }

    @Test
    fun `replay with nothing captured returns false and does nothing`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(20_000L)
            f.player.pause()

            assertFalse(f.controller.replay())
            runCurrent()

            assertFalse(f.controller.replaying.value)
            assertEquals(20_000L, f.player.positionMs.value)
            assertEquals(PlayerState.Paused, f.player.state.value)
            assertEquals(0, f.activeWatchers)
        }

    @Test
    fun `replay seeks to startMs minus preRollMs and plays`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(11_000L)
            f.controller.capture()

            assertTrue(f.controller.replay())
            runCurrent()

            assertEquals(first.startMs - 300L, f.player.positionMs.value)
            assertEquals(PlayerState.Playing, f.player.state.value)
            assertTrue(f.controller.replaying.value)
            assertEquals(1, f.activeWatchers)
        }

    @Test
    fun `replay never seeks before zero`() =
        runTest {
            val f = fixture()
            val early = SubtitleCue(index = 0, startMs = 100L, endMs = 900L, text = "Hi.")
            f.engine.load(SubtitleTrack(cues = listOf(early)))
            f.player.emitPosition(500L)
            f.controller.capture()

            f.controller.replay()

            assertEquals(0L, f.player.positionMs.value)
        }

    @Test
    fun `the watcher pauses and returns to capturedAtMs once the position passes endMs plus tailMs`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(13_000L)
            f.controller.capture()
            f.controller.replay()
            runCurrent()

            f.player.emitPosition(first.endMs + 199L)
            runCurrent()
            assertTrue(f.controller.replaying.value)
            assertEquals(PlayerState.Playing, f.player.state.value)

            f.player.emitPosition(first.endMs + 200L)
            runCurrent()

            assertFalse(f.controller.replaying.value)
            assertEquals(PlayerState.Paused, f.player.state.value)
            assertEquals(13_000L, f.player.positionMs.value)
            assertEquals(0, f.activeWatchers)
            // The capture survives the replay, so the line can be replayed again.
            assertEquals(CapturedLine(first, capturedAtMs = 13_000L), f.controller.captured.value)
        }

    @Test
    fun `the watcher also ends the replay when playback ends or fails`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(11_000L)
            f.controller.capture()

            f.controller.replay()
            runCurrent()
            f.player.fail("codec")
            runCurrent()

            assertFalse(f.controller.replaying.value)
            assertEquals(11_000L, f.player.positionMs.value)
            assertEquals(0, f.activeWatchers)

            f.controller.replay()
            runCurrent()
            f.player.end()
            runCurrent()

            assertFalse(f.controller.replaying.value)
            assertEquals(11_000L, f.player.positionMs.value)
            assertEquals(PlayerState.Paused, f.player.state.value)
            assertEquals(0, f.activeWatchers)
        }

    @Test
    fun `a second replay restarts the fragment with a single watcher`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(11_000L)
            f.controller.capture()
            f.controller.replay()
            runCurrent()
            f.player.emitPosition(11_500L)
            runCurrent()

            assertTrue(f.controller.replay())
            runCurrent()

            assertEquals(first.startMs - 300L, f.player.positionMs.value)
            assertEquals(PlayerState.Playing, f.player.state.value)
            assertTrue(f.controller.replaying.value)
            assertEquals(1, f.activeWatchers)

            f.player.emitPosition(first.endMs + 200L)
            runCurrent()

            assertFalse(f.controller.replaying.value)
            assertEquals(11_000L, f.player.positionMs.value)
            assertEquals(0, f.activeWatchers)
        }

    @Test
    fun `dismiss restores the captured position and resumes playback`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(11_000L)
            f.controller.capture()
            f.controller.replay()
            runCurrent()
            f.player.emitPosition(10_500L)
            runCurrent()

            f.controller.dismiss()
            runCurrent()

            assertNull(f.controller.captured.value)
            assertFalse(f.controller.replaying.value)
            assertEquals(11_000L, f.player.positionMs.value)
            assertEquals(PlayerState.Playing, f.player.state.value)
            assertEquals(0, f.activeWatchers)
        }

    @Test
    fun `dismiss with resume false leaves the player paused at the captured position`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(11_000L)
            f.controller.capture()
            f.controller.replay()
            runCurrent()

            f.controller.dismiss(resume = false)
            runCurrent()

            assertNull(f.controller.captured.value)
            assertFalse(f.controller.replaying.value)
            assertEquals(11_000L, f.player.positionMs.value)
            assertEquals(PlayerState.Paused, f.player.state.value)
            assertEquals(0, f.activeWatchers)
        }

    @Test
    fun `dismiss with nothing captured is a no-op`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(20_000L)
            f.player.pause()

            f.controller.dismiss()

            assertEquals(20_000L, f.player.positionMs.value)
            assertEquals(PlayerState.Paused, f.player.state.value)
        }
}
