package com.teachermovies.assistant

import com.teachermovies.assistant.fake.FakeSpanishTextSource
import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.fake.FakePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhraseRewindControllerTest {
    private val first = SubtitleCue(index = 0, startMs = 10_000L, endMs = 12_000L, text = "Hello.")
    private val second = SubtitleCue(index = 1, startMs = 30_000L, endMs = 33_000L, text = "Bye.")
    private val third = SubtitleCue(index = 2, startMs = 50_000L, endMs = 53_000L, text = "And?")
    private val fourth = SubtitleCue(index = 3, startMs = 70_000L, endMs = 72_000L, text = "Go.")
    private val track = SubtitleTrack(cues = listOf(first, second, third, fourth))

    private val spanishLines =
        listOf(
            SubtitleCue(index = 0, startMs = 10_000L, endMs = 12_000L, text = "Hola."),
            SubtitleCue(index = 1, startMs = 30_000L, endMs = 33_000L, text = "Adios."),
            SubtitleCue(index = 2, startMs = 50_000L, endMs = 53_000L, text = "Y que?"),
            SubtitleCue(index = 3, startMs = 70_000L, endMs = 72_000L, text = "Ve."),
        )

    /** The controller's injected clock: the wall time the tests move by hand. */
    private var nowMs = 1_000L

    private class Fixture(
        val player: FakePlayer,
        val engine: SubtitleEngine,
        val spanish: FakeSpanishTextSource,
        val controllerJob: Job,
        val controller: PhraseRewindController,
    ) {
        val activeJobs: Int
            get() = controllerJob.children.count { it.isActive }
    }

    private fun TestScope.fixture(
        loadTrack: Boolean = true,
        spanish: List<SubtitleCue> = spanishLines,
    ): Fixture {
        val player = FakePlayer()
        player.open(File("movie.mkv"))
        player.emitDuration(100_000L)
        player.play()
        val engine = SubtitleEngine(player.positionMs, backgroundScope)
        if (loadTrack) engine.load(track)
        val source = FakeSpanishTextSource(spanish)
        // A child scope of its own, so the test can count the controller's coroutines.
        val controllerJob = Job(backgroundScope.coroutineContext.job)
        val controller =
            PhraseRewindController(
                player,
                engine,
                source,
                CoroutineScope(backgroundScope.coroutineContext + controllerJob),
                clock = { nowMs },
            )
        return Fixture(player, engine, source, controllerJob, controller)
    }

    /** Lets the group window close with no further press, which is what seeks. */
    private suspend fun TestScope.settle() {
        advanceTimeBy(PhraseRewindController.REWIND_GROUP_WINDOW_MS)
        runCurrent()
    }

    @Test
    fun `a single press rewinds to its own phrase once the window closes`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(31_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.ENGLISH)
            runCurrent()

            // The language is on screen from the first press on, but nothing is sought yet.
            assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)
            assertEquals("Bye.", f.controller.displayText.value)
            assertEquals(31_000L, f.player.positionMs.value)

            settle()

            assertEquals(second.startMs - 300L, f.player.positionMs.value)
            assertEquals(PlayerState.Playing, f.player.state.value)
            // The pre-roll is before the line starts, so there is nothing to show yet.
            assertNull(f.controller.displayText.value)
            assertEquals(1, f.activeJobs)

            f.player.emitPosition(30_500L)
            runCurrent()

            assertEquals("Bye.", f.controller.displayText.value)
        }

    @Test
    fun `a press from a paused player rewinds and plays`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(31_000L)
            f.player.pause()
            runCurrent()

            f.controller.press(SubtitleDisplay.SPANISH)
            settle()

            assertEquals(second.startMs - 300L, f.player.positionMs.value)
            assertEquals(PlayerState.Playing, f.player.state.value)
            assertEquals(SubtitleDisplay.SPANISH, f.controller.display.value)
        }

    @Test
    fun `nothing is sought while presses keep arriving`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(71_000L)
            runCurrent()

            repeat(4) {
                f.controller.press(SubtitleDisplay.ENGLISH)
                nowMs += 1_000L
                advanceTimeBy(1_000L)
                runCurrent()
                assertEquals(71_000L, f.player.positionMs.value)
            }

            settle()

            // Four presses: three phrases back from the fourth line, which is the first one.
            assertEquals(first.startMs - 300L, f.player.positionMs.value)
            assertEquals(PlayerState.Playing, f.player.state.value)
        }

    @Test
    fun `each press inside the window steps the rewind one more phrase back`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(71_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.ENGLISH)
            nowMs += 400L
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            assertEquals(third.startMs - 300L, f.player.positionMs.value)

            // Playback on its way back to the return point, and two more presses inside a window.
            f.player.emitPosition(51_000L)
            runCurrent()
            nowMs += 2_000L
            f.controller.press(SubtitleDisplay.ENGLISH)
            nowMs += 400L
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            assertEquals(second.startMs - 300L, f.player.positionMs.value)
        }

    @Test
    fun `a press one millisecond inside the window joins the group`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(71_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.ENGLISH)
            nowMs += PhraseRewindController.REWIND_GROUP_WINDOW_MS - 1
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()

            assertEquals(third.startMs - 300L, f.player.positionMs.value)
        }

    @Test
    fun `a gap of exactly the window closes the group and the next press opens a new one`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(71_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.ENGLISH)
            nowMs += PhraseRewindController.REWIND_GROUP_WINDOW_MS
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()

            // A group of its own at the same position, not a second press of the group before it.
            assertEquals(fourth.startMs - 300L, f.player.positionMs.value)
        }

    @Test
    fun `the rewind is clamped at the first phrase of the track`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(31_000L)
            runCurrent()

            repeat(5) {
                nowMs += 100L
                f.controller.press(SubtitleDisplay.ENGLISH)
            }
            settle()

            assertEquals(first.startMs - 300L, f.player.positionMs.value)
        }

    @Test
    fun `the rewind never seeks before the start of the media`() =
        runTest {
            val f = fixture()
            val early = SubtitleCue(index = 0, startMs = 100L, endMs = 900L, text = "Hi.")
            f.engine.load(SubtitleTrack(cues = listOf(early)))
            f.player.emitPosition(500L)
            runCurrent()

            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()

            assertEquals(0L, f.player.positionMs.value)
        }

    @Test
    fun `an English press over a Spanish one shows the English line`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(31_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.SPANISH)
            assertEquals(SubtitleDisplay.SPANISH, f.controller.display.value)
            assertEquals("Adios.", f.controller.displayText.value)
            nowMs += 400L
            f.controller.press(SubtitleDisplay.ENGLISH)

            assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)
            assertEquals("Bye.", f.controller.displayText.value)
            settle()
            assertEquals(first.startMs - 300L, f.player.positionMs.value)
        }

    @Test
    fun `a Spanish press over an English one shows the Spanish line`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(51_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.ENGLISH)
            assertEquals("And?", f.controller.displayText.value)
            nowMs += 400L
            f.controller.press(SubtitleDisplay.SPANISH)

            assertEquals(SubtitleDisplay.SPANISH, f.controller.display.value)
            assertEquals("Y que?", f.controller.displayText.value)
            assertTrue(f.spanish.queries.contains(51_000L))
            settle()
            assertEquals(second.startMs - 300L, f.player.positionMs.value)
        }

    @Test
    fun `an English display before an English press stays as it is`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(71_000L)
            runCurrent()
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            f.player.emitPosition(70_200L)
            runCurrent()
            assertEquals("Go.", f.controller.displayText.value)

            nowMs += 2_000L
            f.controller.press(SubtitleDisplay.ENGLISH)

            // Neither the language nor its line flicker while the group is pressed again.
            assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)
            assertEquals("Go.", f.controller.displayText.value)
        }

    @Test
    fun `the display the run saved comes back once playback returns to its first press`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(71_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.SPANISH)
            settle()
            assertEquals(fourth.startMs - 300L, f.player.positionMs.value)

            f.player.emitPosition(70_999L)
            runCurrent()
            assertEquals(SubtitleDisplay.SPANISH, f.controller.display.value)
            assertEquals(1, f.activeJobs)

            f.player.emitPosition(71_000L)
            runCurrent()

            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertNull(f.controller.displayText.value)
            assertEquals(0, f.activeJobs)
        }

    @Test
    fun `a second group before the return point keeps the return point and the saved display`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(71_000L)
            runCurrent()
            repeat(3) {
                nowMs += 200L
                f.controller.press(SubtitleDisplay.SPANISH)
            }
            settle()
            assertEquals(second.startMs - 300L, f.player.positionMs.value)

            f.player.emitPosition(51_000L)
            runCurrent()
            assertEquals("Y que?", f.controller.displayText.value)

            nowMs += 2_000L
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()

            // One phrase back from where playback is, not from where the run started.
            assertEquals(third.startMs - 300L, f.player.positionMs.value)

            f.player.emitPosition(55_000L)
            runCurrent()
            // Past the second group's own press, short of the run's return point: still tracked.
            assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)
            assertEquals(1, f.activeJobs)

            f.player.emitPosition(71_000L)
            runCurrent()

            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertNull(f.controller.displayText.value)
            assertEquals(0, f.activeJobs)
        }

    @Test
    fun `the Spanish line is null where the source has none`() =
        runTest {
            val f = fixture(spanish = spanishLines.take(3))
            f.player.emitPosition(71_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.SPANISH)

            assertNull(f.controller.displayText.value)
            assertTrue(f.spanish.queries.contains(71_000L))

            settle()
            f.player.emitPosition(51_000L)
            runCurrent()

            assertEquals("Y que?", f.controller.displayText.value)
        }

    @Test
    fun `a press with no track loaded shows the language and then drops the run`() =
        runTest {
            val f = fixture(loadTrack = false)
            f.player.emitPosition(31_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.ENGLISH)
            runCurrent()
            assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)

            settle()

            assertEquals(31_000L, f.player.positionMs.value)
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertNull(f.controller.displayText.value)
            assertEquals(0, f.activeJobs)
        }

    @Test
    fun `a press of OFF is not a rewind press`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(31_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.OFF)
            runCurrent()

            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertNull(f.controller.displayText.value)
            assertEquals(0, f.activeJobs)

            settle()

            assertEquals(31_000L, f.player.positionMs.value)
            assertEquals(0, f.activeJobs)
        }

    @Test
    fun `cancel drops the run and restores the saved display`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(71_000L)
            runCurrent()
            f.controller.press(SubtitleDisplay.SPANISH)
            settle()
            f.player.emitPosition(70_500L)
            runCurrent()
            assertEquals("Ve.", f.controller.displayText.value)

            f.controller.cancel()
            runCurrent()

            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertNull(f.controller.displayText.value)
            assertEquals(0, f.activeJobs)

            // Nothing tracks the return point any more.
            f.player.emitPosition(71_000L)
            runCurrent()
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertEquals(0, f.activeJobs)
        }

    @Test
    fun `cancel before the window closes seeks nothing and leaves no coroutine`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(71_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.ENGLISH)
            runCurrent()
            f.controller.cancel()

            assertEquals(0, f.activeJobs)
            advanceTimeBy(5_000L)
            runCurrent()

            assertEquals(71_000L, f.player.positionMs.value)
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertEquals(0, f.activeJobs)
        }

    @Test
    fun `cancel with nothing tracked is a no-op`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(31_000L)
            f.player.pause()
            runCurrent()

            f.controller.cancel()
            runCurrent()

            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertNull(f.controller.displayText.value)
            assertEquals(31_000L, f.player.positionMs.value)
            assertEquals(PlayerState.Paused, f.player.state.value)
            assertEquals(0, f.activeJobs)
        }

    @Test
    fun `the player failing or ending drops the run and restores the saved display`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(71_000L)
            runCurrent()
            f.controller.press(SubtitleDisplay.SPANISH)
            settle()

            f.player.fail("codec")
            runCurrent()

            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertNull(f.controller.displayText.value)
            assertEquals(0, f.activeJobs)

            f.player.emitPosition(51_000L)
            f.player.play()
            runCurrent()
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()

            f.player.end()
            runCurrent()

            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertNull(f.controller.displayText.value)
            assertEquals(0, f.activeJobs)
        }

    @Test
    fun `the player failing before the window closes seeks nothing`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(71_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.ENGLISH)
            runCurrent()
            f.player.fail("codec")
            runCurrent()

            assertEquals(0, f.activeJobs)
            settle()

            assertEquals(71_000L, f.player.positionMs.value)
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
        }
}
