package com.teachermovies.assistant

import com.teachermovies.assistant.fake.FakeSpanishTextSource
import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack
import com.teachermovies.core.log.AppLog
import com.teachermovies.core.log.LogEntry
import com.teachermovies.core.log.LogLevel
import com.teachermovies.core.log.LogSink
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.fake.FakePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
        val counter: CountingPlayer,
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
        val counter = CountingPlayer(player)
        val controller =
            PhraseRewindController(
                counter,
                engine,
                source,
                CoroutineScope(backgroundScope.coroutineContext + controllerJob),
                clock = { nowMs },
            )
        return Fixture(player, counter, engine, source, controllerJob, controller)
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

            // A press paints nothing and seeks nothing: the language waits for the window to close.
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertNull(f.controller.displayText.value)
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

    private fun Fixture.assertQuiet() {
        assertEquals(SubtitleDisplay.OFF, controller.display.value)
        assertNull(controller.displayText.value)
        assertEquals(PlayerState.Playing, player.state.value)
        assertTrue(counter.seeks.isEmpty())
        assertEquals(0, counter.pauses)
    }

    /** Presses LEFT [times] times 400 ms apart from [positionMs] and returns while the window is open. */
    private fun TestScope.burst(
        f: Fixture,
        positionMs: Long,
        times: Int,
        language: SubtitleDisplay = SubtitleDisplay.ENGLISH,
    ) {
        f.player.emitPosition(positionMs)
        runCurrent()
        repeat(times) {
            if (it > 0) nowMs += 400L
            f.controller.press(language)
        }
        runCurrent()
    }

    private fun Fixture.origin(): Long? =
        (controller.subtitleState.value as? RewindSubtitleState.Rewinding)?.snapshot?.originMs

    @Test
    fun `a burst of three leaves the screen alone, seeks once and restores at the end of the phrase`() =
        runTest {
            val f = fixture()
            burst(f, 51_000L, 3)

            // Nothing is painted, paused or sought while the burst is open, and it is still open.
            advanceTimeBy(1_000L)
            runCurrent()
            f.assertQuiet()
            assertEquals(53_000L, f.origin())

            settle()
            assertEquals(listOf(9_700L), f.counter.seeks)
            assertEquals(0, f.counter.pauses)
            assertEquals(PlayerState.Playing, f.player.state.value)
            assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)

            val shown = mutableListOf<String?>()
            for (position in listOf(10_500L, 30_500L, 50_500L)) {
                confirmSeek(f, position)
                shown += f.controller.displayText.value
            }
            assertEquals(listOf<String?>("Hello.", "Bye.", "And?"), shown)

            f.player.emitPosition(52_999L)
            runCurrent()
            assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)
            f.player.emitPosition(53_000L)
            runCurrent()
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertNull(f.controller.displayText.value)
            assertEquals(0, f.counter.pauses)
            assertEquals(0, f.activeJobs)
        }

    @Test
    fun `a single press in the middle of a phrase paints nothing and restores at the end of that phrase`() =
        runTest {
            val f = fixture()
            burst(f, 31_000L, 1)
            advanceTimeBy(1_000L)
            runCurrent()
            f.assertQuiet()

            settle()
            assertEquals(listOf(29_700L), f.counter.seeks)
            confirmSeek(f, 30_500L)
            assertEquals("Bye.", f.controller.displayText.value)
            f.player.emitPosition(32_500L)
            runCurrent()
            assertEquals("Bye.", f.controller.displayText.value)
            assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)
            f.player.emitPosition(33_000L)
            runCurrent()
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertEquals(0, f.counter.pauses)
        }

    @Test
    fun `a press exactly at a phrase start has the end of that phrase as origin`() =
        runTest {
            val f = fixture()
            burst(f, 30_000L, 1)
            assertEquals(33_000L, f.origin())
            settle()
            assertEquals(listOf(29_700L), f.counter.seeks)
            confirmSeek(f, 30_100L)
            f.player.emitPosition(32_999L)
            runCurrent()
            assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)
            f.player.emitPosition(33_000L)
            runCurrent()
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
        }

    @Test
    fun `a press at a phrase end or in a gap has the press position as origin`() =
        runTest {
            for (pressAt in listOf(33_000L, 34_500L)) {
                nowMs += 10_000L
                val f = fixture()
                burst(f, pressAt, 1)
                assertEquals(pressAt, f.origin())
                settle()
                assertEquals(listOf(29_700L), f.counter.seeks)
                confirmSeek(f, 30_500L)
                assertEquals("Bye.", f.controller.displayText.value)
                f.player.emitPosition(pressAt - 1)
                runCurrent()
                assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)
                f.player.emitPosition(pressAt)
                runCurrent()
                assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
                f.controller.cancel()
            }
        }

    @Test
    fun `a video already past the origin when the window closes does not end the run or move the origin`() =
        runTest {
            val f = fixture()
            burst(f, 32_800L, 1)
            f.player.emitPosition(33_500L)
            runCurrent()
            f.player.emitPosition(34_200L)
            runCurrent()
            f.assertQuiet()
            assertEquals(33_000L, f.origin())

            settle()
            assertEquals(listOf(29_700L), f.counter.seeks)
            assertEquals(33_000L, f.origin())
            assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)

            confirmSeek(f, 30_500L)
            assertEquals("Bye.", f.controller.displayText.value)
            f.player.emitPosition(33_000L)
            runCurrent()
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertEquals(0, f.counter.pauses)
        }

    @Test
    fun `later presses, positions and groups never overwrite the origin`() =
        runTest {
            val f = fixture()
            burst(f, 51_000L, 3)
            assertEquals(53_000L, f.origin())
            f.player.emitPosition(60_000L)
            runCurrent()
            assertEquals(53_000L, f.origin())
            settle()
            assertEquals(53_000L, f.origin())

            confirmSeek(f, 30_500L)
            nowMs += 2_000L
            f.controller.press(SubtitleDisplay.ENGLISH)
            assertEquals(53_000L, f.origin())
            settle()
            assertEquals(53_000L, f.origin())
            assertEquals(listOf(9_700L, 29_700L), f.counter.seeks)
        }

    @Test
    fun `a subtitle shown before the run is untouched during the window and back at the origin`() =
        runTest {
            val f = fixture()
            // An earlier run left the English display on; the viewer then pressed again mid-run.
            burst(f, 71_000L, 1)
            settle()
            confirmSeek(f, 70_100L)
            assertEquals("Go.", f.controller.displayText.value)

            nowMs += 2_000L
            f.controller.press(SubtitleDisplay.SPANISH)
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)
            assertEquals("Go.", f.controller.displayText.value)
            settle()
            assertEquals(SubtitleDisplay.SPANISH, f.controller.display.value)

            confirmSeek(f, 70_000L)
            f.player.emitPosition(72_000L)
            runCurrent()
            // The display saved by the first press (OFF) is what comes back.
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
        }

    @Test
    fun `only the replayed phrases are ever published and nothing before the seek`() =
        runTest {
            val f = fixture()
            val published = mutableListOf<String?>()
            backgroundScope.launch { f.controller.displayText.collect { published += it } }
            runCurrent()

            burst(f, 51_000L, 3)
            settle()
            assertEquals(listOf<String?>(null), published)
            for (position in listOf(10_500L, 30_500L, 50_500L, 53_000L)) {
                confirmSeek(f, position)
            }
            val expected = listOf<String?>(null, "Hello.", "Bye.", "And?", null)
            assertEquals(expected, published)
        }

    @Test
    fun `cancel and a user seek during the window seek nothing and never touch play or pause`() =
        runTest {
            for (action in listOf<(PhraseRewindController) -> Unit>({ it.cancel() }, { it.onUserSeek() })) {
                nowMs += 10_000L
                val f = fixture()
                val playsBefore = f.counter.plays
                burst(f, 51_000L, 2)
                action(f.controller)
                settle()
                assertTrue(f.counter.seeks.isEmpty())
                assertEquals(0, f.counter.pauses)
                assertEquals(playsBefore, f.counter.plays)
                assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
                assertEquals(0, f.activeJobs)
            }
        }

    @Test
    fun `no pause across a rewind with no track loaded`() =
        runTest {
            val f = fixture(loadTrack = false)
            burst(f, 31_000L, 2)
            settle()
            assertEquals(0, f.counter.pauses)
            assertTrue(f.counter.seeks.isEmpty())
            assertEquals(PlayerState.Playing, f.player.state.value)
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
        }

    /** Lets [SEEK_CONFIRM_MS][PhraseRewindController.SEEK_CONFIRM_MS] pass and reports [positionMs]. */
    private suspend fun TestScope.confirmSeek(
        f: Fixture,
        positionMs: Long,
    ) {
        nowMs += PhraseRewindController.SEEK_CONFIRM_MS + 100L
        f.player.emitPosition(positionMs)
        runCurrent()
    }

    @Test
    fun `a stale pre-seek position right after the seek does not end the run`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(31_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            assertEquals(second.startMs - 300L, f.player.positionMs.value)

            // libVLC delivers a TimeChanged still carrying the pre-seek time after setTime (H1).
            f.player.emitPosition(32_500L)
            runCurrent()

            assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)
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
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertNull(f.controller.displayText.value)
            nowMs += 400L
            f.controller.press(SubtitleDisplay.ENGLISH)
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)

            // The last press's language wins, applied when the window closes.
            settle()
            assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)
            assertEquals(first.startMs - 300L, f.player.positionMs.value)
            f.player.emitPosition(10_500L)
            runCurrent()
            assertEquals("Hello.", f.controller.displayText.value)
        }

    @Test
    fun `a Spanish press over an English one shows the Spanish line`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(51_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.ENGLISH)
            assertNull(f.controller.displayText.value)
            nowMs += 400L
            f.controller.press(SubtitleDisplay.SPANISH)

            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertNull(f.controller.displayText.value)
            settle()
            assertEquals(SubtitleDisplay.SPANISH, f.controller.display.value)
            assertEquals(second.startMs - 300L, f.player.positionMs.value)
            f.player.emitPosition(30_500L)
            runCurrent()
            assertEquals("Adios.", f.controller.displayText.value)
            assertTrue(f.spanish.queries.contains(30_500L))
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
            confirmSeek(f, 70_000L)

            // The origin is the end of "Go.", not the press position.
            f.player.emitPosition(71_000L)
            runCurrent()
            assertEquals(SubtitleDisplay.SPANISH, f.controller.display.value)
            f.player.emitPosition(71_999L)
            runCurrent()
            assertEquals(SubtitleDisplay.SPANISH, f.controller.display.value)
            assertEquals(1, f.activeJobs)

            f.player.emitPosition(72_000L)
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
            confirmSeek(f, 30_000L)

            f.player.emitPosition(51_000L)
            runCurrent()
            assertEquals("Y que?", f.controller.displayText.value)

            nowMs += 2_000L
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()

            // One phrase back from where playback is, not from where the run started.
            assertEquals(third.startMs - 300L, f.player.positionMs.value)
            confirmSeek(f, 50_000L)

            f.player.emitPosition(55_000L)
            runCurrent()
            // Past the second group's own press, short of the run's return point: still tracked.
            assertEquals(SubtitleDisplay.ENGLISH, f.controller.display.value)
            assertEquals(1, f.activeJobs)

            f.player.emitPosition(72_000L)
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

            settle()
            f.player.emitPosition(51_000L)
            runCurrent()

            assertEquals("Y que?", f.controller.displayText.value)
        }

    @Test
    fun `a press with no track loaded shows nothing and then drops the run`() =
        runTest {
            val f = fixture(loadTrack = false)
            f.player.emitPosition(31_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.ENGLISH)
            runCurrent()
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)

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

    /** Collects what the controller logs through [AppLog]; [use] always restores the global. */
    private class LogCapture : LogSink {
        val lines = mutableListOf<String>()

        override fun record(entry: LogEntry) {
            lines += entry.message
        }

        inline fun use(block: () -> Unit) {
            val previous = AppLog.minLevel
            AppLog.minLevel = LogLevel.DEBUG
            AppLog.install(this)
            try {
                block()
            } finally {
                AppLog.uninstall(this)
                AppLog.minLevel = previous
            }
        }

        fun assertHas(prefix: String) {
            assertTrue("$prefix not in $lines", lines.any { it.startsWith(prefix) })
        }

        fun assertNoSubtitleText() {
            val texts = listOf("Hello.", "Bye.", "And?", "Go.", "Hola.", "Adios.", "movie.mkv")
            lines.forEach { line -> texts.forEach { assertTrue("$line leaks $it", !line.contains(it)) } }
        }
    }

    /** One finished-window run from [positionMs] up to the first confirmed position, origin 53 s. */
    private suspend fun TestScope.startReplay(f: Fixture) {
        f.player.emitPosition(51_000L)
        runCurrent()
        f.controller.press(SubtitleDisplay.ENGLISH)
        settle()
        confirmSeek(f, 50_000L)
    }

    @Test
    fun `a press in the last window before the origin survives the run reaching it`() =
        runTest {
            val f = fixture()
            startReplay(f)
            assertEquals(listOf(49_700L), f.counter.seeks)

            f.player.emitPosition(52_000L)
            runCurrent()
            f.controller.press(SubtitleDisplay.SPANISH)
            runCurrent()
            // The run reaches its origin while the press's window is still open.
            f.player.emitPosition(53_000L)
            runCurrent()
            assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)

            settle()

            assertEquals(listOf(49_700L, 49_700L), f.counter.seeks)
            assertEquals(SubtitleDisplay.SPANISH, f.controller.display.value)
            assertEquals(PlayerState.Playing, f.player.state.value)
            assertEquals(53_000L, f.origin())

            confirmSeek(f, 50_000L)
            f.player.emitPosition(51_000L)
            runCurrent()
            assertEquals("Y que?", f.controller.displayText.value)
            f.player.emitPosition(53_000L)
            runCurrent()
            assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)
            assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
            assertEquals(0, f.activeJobs)
        }

    @Test
    fun `a burst pressed inside the closing run keeps its count`() =
        runTest {
            val f = fixture()
            startReplay(f)
            f.player.emitPosition(52_000L)
            runCurrent()
            f.controller.press(SubtitleDisplay.ENGLISH)
            nowMs += 400L
            f.controller.press(SubtitleDisplay.ENGLISH)
            runCurrent()
            f.player.emitPosition(53_000L)
            runCurrent()

            settle()

            assertEquals(listOf(49_700L, 29_700L), f.counter.seeks)
        }

    @Test
    fun `a press right after a run ended at its origin starts a new run and seeks`() =
        runTest {
            val f = fixture()
            startReplay(f)
            f.player.emitPosition(53_000L)
            runCurrent()
            assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)

            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()

            assertEquals(listOf(49_700L, 49_700L), f.counter.seeks)
            assertEquals(53_000L, f.origin())
        }

    @Test
    fun `a press after a hand seek cancelled the run starts a new one`() =
        runTest {
            val f = fixture()
            startReplay(f)
            f.controller.onUserSeek()
            runCurrent()
            f.player.emitPosition(31_000L)
            runCurrent()

            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()

            assertEquals(listOf(49_700L, 29_700L), f.counter.seeks)
            assertEquals(33_000L, f.origin())
        }

    @Test
    fun `a press inside a window the hand seek cancels is dropped and logged`() =
        runTest {
            val log = LogCapture()
            log.use {
                val f = fixture()
                startReplay(f)
                f.player.emitPosition(52_000L)
                runCurrent()
                f.controller.press(SubtitleDisplay.ENGLISH)
                runCurrent()

                f.controller.onUserSeek()
                settle()

                assertEquals(listOf(49_700L), f.counter.seeks)
                assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)
                assertEquals(0, f.activeJobs)
                log.assertHas("phrase rewind: press dropped reason=user-seek")
                log.assertHas("phrase rewind: end reason=user-seek")
                log.assertNoSubtitleText()
            }
        }

    @Test
    fun `a press while the player is ended seeks back and plays`() =
        runTest {
            val f = fixture()
            f.player.emitDuration(72_500L)
            f.player.emitPosition(72_400L)
            f.player.end()
            runCurrent()

            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()

            assertEquals(listOf(69_700L), f.counter.seeks)
            assertEquals(PlayerState.Playing, f.player.state.value)
            assertEquals(1, f.activeJobs)

            // The video ending again once the run is underway still ends it.
            confirmSeek(f, 70_000L)
            f.player.end()
            runCurrent()
            assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)
            assertEquals(0, f.activeJobs)

            // And the next press after that works too.
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            assertEquals(listOf(69_700L, 69_700L), f.counter.seeks)
            assertEquals(PlayerState.Playing, f.player.state.value)
        }

    @Test
    fun `a press while the player is in error is ignored and logged`() =
        runTest {
            val log = LogCapture()
            log.use {
                val f = fixture()
                f.player.emitPosition(31_000L)
                f.player.fail("codec")
                runCurrent()

                f.controller.press(SubtitleDisplay.ENGLISH)
                settle()

                assertTrue(f.counter.seeks.isEmpty())
                assertEquals(0, f.activeJobs)
                log.assertHas("phrase rewind: press ignored reason=player-error")
            }
        }

    @Test
    fun `ten consecutive cycles seek ten times and end idle each time`() =
        runTest {
            val f = fixture()
            repeat(10) { cycle ->
                f.player.emitPosition(51_000L)
                runCurrent()
                f.controller.press(SubtitleDisplay.ENGLISH)
                settle()
                assertEquals(cycle + 1, f.counter.seeks.size)
                confirmSeek(f, 50_000L)
                f.player.emitPosition(53_000L)
                runCurrent()
                assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)
                assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
                assertEquals(0, f.activeJobs)
            }
            assertEquals(List(10) { 49_700L }, f.counter.seeks)
            assertEquals(0, f.counter.pauses)
        }

    @Test
    fun `a press that resolves to no cue is logged and ends the run`() =
        runTest {
            val log = LogCapture()
            log.use {
                val f = fixture(loadTrack = false)
                f.player.emitPosition(31_000L)
                runCurrent()

                f.controller.press(SubtitleDisplay.ENGLISH)
                settle()

                log.assertHas("phrase rewind: press ignored reason=no-cue")
                log.assertHas("phrase rewind: end reason=no-cue")
                assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)
                assertEquals(0, f.activeJobs)
                log.assertNoSubtitleText()
            }
        }

    @Test
    fun `a press of OFF is logged as ignored`() =
        runTest {
            val log = LogCapture()
            log.use {
                val f = fixture()
                f.controller.press(SubtitleDisplay.OFF)
                log.assertHas("phrase rewind: press ignored reason=off")
            }
        }
}
