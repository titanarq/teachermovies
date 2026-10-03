package com.teachermovies.assistant

import com.teachermovies.assistant.subtitles.SrtWriter
import com.teachermovies.assistant.subtitles.SubtitleCue
import com.teachermovies.assistant.subtitles.SubtitleTrack
import com.teachermovies.player.api.ExternalSubtitleResult
import com.teachermovies.player.api.Player
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.api.SubtitleFormat
import com.teachermovies.player.api.Track
import com.teachermovies.player.fake.FakePlayer
import com.teachermovies.player.session.SubtitleSaveGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The player-side subtitle of a phrase rewind (#358): temporary track, snapshot, hot restore. */
class PhraseRewindSubtitlesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val cues =
        listOf(
            SubtitleCue(index = 0, startMs = 10_000L, endMs = 12_000L, text = "Hello."),
            SubtitleCue(index = 1, startMs = 30_000L, endMs = 33_000L, text = "Bye."),
            SubtitleCue(index = 2, startMs = 50_000L, endMs = 53_000L, text = "And?"),
            SubtitleCue(index = 3, startMs = 70_000L, endMs = 72_000L, text = "Go."),
        )
    private val english = SubtitleTrack(cues)
    private val spanishTrack =
        SubtitleTrack(cues.map { it.copy(text = "ES ${it.text}") })

    private var nowMs = 1_000L
    private var spanishCues: SubtitleTrack? = spanishTrack

    private class RecordingGuard : SubtitleSaveGuard {
        val holds = mutableListOf<Pair<String, String?>>()
        var releases = 0

        override fun hold(
            tempId: String,
            previousId: String?,
        ) {
            holds += tempId to previousId
        }

        override fun release() {
            releases += 1
        }
    }

    private class Fixture(
        val player: FakePlayer,
        val hidden: HiddenSubtitleController,
        val controller: PhraseRewindController,
        val guard: RecordingGuard,
        val job: Job,
        val gated: GatedPlayer,
    ) {
        val activeJobs: Int get() = job.children.count { it.isActive }
    }

    /** A player whose [addExternalSubtitleTrack] waits for [gate] while it is set. */
    private class GatedPlayer(
        private val fake: FakePlayer,
    ) : Player by fake {
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun addExternalSubtitleTrack(file: File): ExternalSubtitleResult {
            gate?.await()
            return fake.addExternalSubtitleTrack(file)
        }
    }

    private val cacheDir get() = File(tempFolder.root, "cache")

    /** A playing movie with hidden English mode on; [viewerId] is the track the viewer had chosen. */
    private suspend fun TestScope.fixture(
        viewerId: String? = null,
        embedded: Boolean = false,
    ): Fixture {
        val player = FakePlayer()
        player.open(File(tempFolder.root, "movie.mkv"))
        player.emitDuration(100_000L)
        player.play()
        val engine = SubtitleEngine(player.positionMs, backgroundScope)
        val hidden = HiddenSubtitleController(player, engine, backgroundScope, cacheDir)
        val tracks = listOfNotNull(viewerId?.let { Track(it, "Viewer", "xx") })
        if (embedded) {
            player.emitTracks(emptyList(), tracks + Track("en1", "English", "en"))
            player.emitExtractionText(SrtWriter.format(english), SubtitleFormat.SRT)
        } else {
            File(tempFolder.root, "movie.en.srt").writeText(SrtWriter.format(english))
            player.emitTracks(emptyList(), tracks)
        }
        viewerId?.let { player.selectSubtitle(it) }
        hidden.start(File(tempFolder.root, "movie.mkv"), viewerSubtitleId = viewerId)
        val gated = GatedPlayer(player)
        val session = RewindSubtitleSession(gated, hidden, { spanishCues }, cacheDir)
        session.setMovie(File(tempFolder.root, "movie.mkv"))
        val guard = RecordingGuard()
        session.attachSaveGuard(guard)
        val job = Job(backgroundScope.coroutineContext.job)
        val controller =
            PhraseRewindController(
                player,
                engine,
                { it.toString() },
                CoroutineScope(backgroundScope.coroutineContext + job),
                clock = { nowMs },
                subtitles = session,
            )
        player.emitPosition(71_000L)
        runCurrent()
        return Fixture(player, hidden, controller, guard, job, gated)
    }

    private suspend fun TestScope.settle() {
        advanceTimeBy(PhraseRewindController.REWIND_GROUP_WINDOW_MS)
        runCurrent()
    }

    private suspend fun TestScope.confirmSeek(
        f: Fixture,
        positionMs: Long,
    ) {
        nowMs += PhraseRewindController.SEEK_CONFIRM_MS + 100L
        f.player.emitPosition(positionMs)
        runCurrent()
    }

    private fun Fixture.selected(): String? = player.selectedSubtitleId.value

    @Test
    fun `LEFT shows the English subtitle on the player and hot-restores no subtitle at the origin`() =
        runTest {
            val f = fixture()
            assertNull(f.selected())

            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()

            val temp = f.selected()
            assertNotNull(temp)
            assertEquals(1, f.player.externalSubtitleCalls.size)
            val first = f.controller.subtitleState.value as RewindSubtitleState.Rewinding
            assertEquals(RewindSnapshot(originMs = 72_000L, previousSubtitleId = null), first.snapshot)
            assertEquals(PlayerState.Playing, f.player.state.value)
            // One rendering path: the player draws it, so there is no overlay line.
            f.player.emitPosition(70_500L)
            runCurrent()
            assertNull(f.controller.displayText.value)

            confirmSeek(f, 70_000L)
            assertTrue(f.controller.subtitleState.value is RewindSubtitleState.PlayingWithTempSubs)
            assertEquals(temp, f.selected())

            f.player.emitPosition(72_000L)
            runCurrent()

            assertNull(f.selected())
            assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)
            // A live restore: the movie was never paused.
            assertEquals(PlayerState.Playing, f.player.state.value)
            assertEquals(1, f.guard.releases)
            assertEquals(0, f.activeJobs)
        }

    private fun TestScope.burst(
        f: Fixture,
        times: Int,
    ) {
        repeat(times) {
            if (it > 0) nowMs += 400L
            f.controller.press(SubtitleDisplay.ENGLISH)
        }
        runCurrent()
    }

    @Test
    fun `a burst of three keeps the selection during the window and restores hot at the end of the phrase`() =
        runTest {
            val f = fixture()
            f.player.emitPosition(51_000L)
            runCurrent()
            burst(f, 3)
            advanceTimeBy(1_000L)
            runCurrent()

            assertNull(f.selected())
            assertTrue(f.player.externalSubtitleCalls.isEmpty())
            assertEquals(PlayerState.Playing, f.player.state.value)

            settle()
            val temp = f.selected()
            assertNotNull(temp)
            assertEquals(9_700L, f.player.positionMs.value)
            confirmSeek(f, 10_500L)
            f.player.emitPosition(52_999L)
            runCurrent()
            assertEquals(temp, f.selected())
            f.player.emitPosition(53_000L)
            runCurrent()

            assertNull(f.selected())
            assertEquals(PlayerState.Playing, f.player.state.value)
            assertEquals(0, f.activeJobs)
        }

    @Test
    fun `a viewer track stays selected during the window and comes back at the origin`() =
        runTest {
            val f = fixture(viewerId = "v1")
            f.player.emitPosition(31_000L)
            runCurrent()
            burst(f, 1)
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals("v1", f.selected())

            settle()
            assertTrue("v1" != f.selected())
            confirmSeek(f, 30_500L)
            f.player.emitPosition(33_000L)
            runCurrent()

            assertEquals("v1", f.selected())
            assertEquals(PlayerState.Playing, f.player.state.value)
        }

    @Test
    fun `RIGHT writes the aligned Spanish cues as an SRT and shows that track`() =
        runTest {
            val f = fixture()

            f.controller.press(SubtitleDisplay.SPANISH)
            settle()

            val file = File(cacheDir, "subtitles/rewind/movie.spanish.srt")
            assertTrue(file.readText().contains("ES Go."))
            assertEquals(listOf(file), f.player.externalSubtitleCalls)
            assertEquals("ext:movie.spanish.srt", f.selected())
        }

    @Test
    fun `English uses the embedded track hidden mode reads when the player lists it`() =
        runTest {
            val f = fixture(embedded = true)

            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()

            assertEquals("en1", f.selected())
            assertTrue(f.player.externalSubtitleCalls.isEmpty())
        }

    @Test
    fun `a viewer-chosen track is restored by its exact id and an extra group keeps the snapshot`() =
        runTest {
            val f = fixture(viewerId = "v1")
            assertEquals("v1", f.selected())

            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            confirmSeek(f, 70_000L)
            f.player.emitPosition(70_500L)
            runCurrent()
            nowMs += 2_000L
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()

            val state = f.controller.subtitleState.value as RewindSubtitleState.Rewinding
            assertEquals(RewindSnapshot(72_000L, "v1"), state.snapshot)
            assertEquals(1, f.player.externalSubtitleCalls.size)

            confirmSeek(f, 69_900L)
            f.player.emitPosition(72_000L)
            runCurrent()

            assertEquals("v1", f.selected())
            assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)
        }

    @Test
    fun `a direction change switches the temporary track live and keeps the snapshot`() =
        runTest {
            val f = fixture(viewerId = "v1")

            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            val englishTrack = f.selected()
            confirmSeek(f, 70_000L)
            f.player.emitPosition(70_500L)
            runCurrent()
            nowMs += 2_000L
            f.controller.press(SubtitleDisplay.SPANISH)
            settle()

            assertEquals("ext:movie.spanish.srt", f.selected())
            assertTrue(englishTrack != f.selected())
            val state = f.controller.subtitleState.value as RewindSubtitleState.Rewinding
            assertEquals(RewindSnapshot(72_000L, "v1"), state.snapshot)
            assertEquals(listOf("ext:movie.spanish.srt" to "v1"), f.guard.holds.takeLast(1))

            f.controller.cancel()
            assertEquals("v1", f.selected())
        }

    @Test
    fun `later runs reuse the file already added to the player`() =
        runTest {
            val f = fixture()

            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            confirmSeek(f, 70_000L)
            f.player.emitPosition(72_000L)
            runCurrent()
            assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)

            nowMs += 5_000L
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()

            assertEquals(1, f.player.externalSubtitleCalls.size)
            assertEquals("ext:movie.english.srt", f.selected())
        }

    @Test
    fun `a stale pre-seek position keeps the temporary track selected`() =
        runTest {
            val f = fixture()
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            val temp = f.selected()

            f.player.emitPosition(72_500L)
            runCurrent()

            assertEquals(temp, f.selected())
            assertTrue(f.controller.subtitleState.value is RewindSubtitleState.Rewinding)
        }

    @Test
    fun `playback reaching the origin without a confirmed seek restores nothing`() =
        runTest {
            val f = fixture()
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            val temp = f.selected()

            // In range, but not yet SEEK_CONFIRM_MS after the seek.
            f.player.emitPosition(70_000L)
            runCurrent()
            f.player.emitPosition(71_500L)
            runCurrent()

            assertEquals(temp, f.selected())
        }

    @Test
    fun `a user seek restores the snapshot at once`() =
        runTest {
            val f = fixture(viewerId = "v1")
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            confirmSeek(f, 70_000L)

            f.controller.onUserSeek()

            assertEquals("v1", f.selected())
            assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)
            assertEquals(0, f.activeJobs)
        }

    @Test
    fun `a pause keeps the temporary state and resuming goes on to the origin`() =
        runTest {
            val f = fixture()
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            confirmSeek(f, 70_000L)
            val temp = f.selected()

            f.player.pause()
            runCurrent()
            assertEquals(temp, f.selected())
            assertTrue(f.controller.subtitleState.value is RewindSubtitleState.PlayingWithTempSubs)

            f.player.play()
            f.player.emitPosition(72_000L)
            runCurrent()

            assertNull(f.selected())
        }

    @Test
    fun `ending or failing before the origin restores the snapshot and leaves no coroutine`() =
        runTest {
            val ended = fixture(viewerId = "v1")
            ended.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            ended.player.end()
            runCurrent()
            assertEquals("v1", ended.selected())
            assertEquals(RewindSubtitleState.Idle, ended.controller.subtitleState.value)
            assertEquals(0, ended.activeJobs)

            nowMs += 5_000L
            val failed = fixture()
            failed.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            failed.player.fail("boom")
            runCurrent()
            assertNull(failed.selected())
            assertEquals(0, failed.activeJobs)
        }

    @Test
    fun `cancel restores the snapshot before anything else runs`() =
        runTest {
            val f = fixture(viewerId = "v1")
            f.controller.press(SubtitleDisplay.SPANISH)
            settle()

            f.controller.cancel()

            assertEquals("v1", f.selected())
            assertEquals(1, f.guard.releases)
            assertEquals(0, f.activeJobs)
        }

    @Test
    fun `a language with no subtitle is reported and leaves the selection alone`() =
        runTest {
            spanishCues = null
            val f = fixture(viewerId = "v1")
            val reported = mutableListOf<SubtitleDisplay>()
            backgroundScope.launch { f.controller.unavailable.collect { reported += it } }
            runCurrent()

            f.controller.press(SubtitleDisplay.SPANISH)
            settle()

            assertEquals(listOf(SubtitleDisplay.SPANISH), reported)
            assertEquals("v1", f.selected())
            assertTrue(f.player.externalSubtitleCalls.isEmpty())
            // The rewind itself still happened and playback goes on.
            assertEquals(69_700L, f.player.positionMs.value)
            assertEquals(PlayerState.Playing, f.player.state.value)

            confirmSeek(f, 70_000L)
            f.player.emitPosition(72_000L)
            runCurrent()
            assertEquals("v1", f.selected())
            assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)
        }

    @Test
    fun `a viewer choice during the temporary state ends it and wins`() =
        runTest {
            val f = fixture()
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            confirmSeek(f, 70_000L)

            f.hidden.selectByViewer("v2")
            runCurrent()

            assertEquals("v2", f.selected())
            assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)
            assertEquals(0, f.activeJobs)
        }

    @Test
    fun `hidden mode does not revert the temporary track and reasserts afterwards`() =
        runTest {
            val f = fixture()
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            runCurrent()
            val temp = f.selected()
            assertNotNull(temp)

            // A default-track policy picking another track is still undone.
            f.player.selectSubtitle("other")
            runCurrent()
            assertNull(f.selected())
        }

    @Test
    fun `a press made while the track selection is in flight is applied and not undone by the old origin`() =
        runTest {
            val f = fixture()
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            confirmSeek(f, 70_000L)

            // The Spanish SRT takes a while to add to the player.
            val gate = CompletableDeferred<Unit>()
            f.gated.gate = gate
            nowMs += 2_000L
            f.controller.press(SubtitleDisplay.SPANISH)
            settle()
            // Playback crosses the old run's origin while the selection is still pending.
            f.player.emitPosition(72_000L)
            runCurrent()
            assertTrue(f.controller.subtitleState.value is RewindSubtitleState.PlayingWithTempSubs)
            assertEquals(2, f.activeJobs)

            gate.complete(Unit)
            runCurrent()

            assertEquals(69_700L, f.player.positionMs.value)
            assertTrue(f.selected().toString(), f.selected()?.contains("spanish") == true)
            assertTrue(f.controller.subtitleState.value is RewindSubtitleState.Rewinding)

            confirmSeek(f, 70_000L)
            f.player.emitPosition(72_000L)
            runCurrent()
            assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)
            assertNull(f.selected())
            assertEquals(0, f.activeJobs)
        }

    /** Every way a run ends leaves the controller as it was before it and nothing pending. */
    private suspend fun TestScope.assertEndedClean(
        f: Fixture,
        expectedSelected: String?,
    ) {
        assertEquals(RewindSubtitleState.Idle, f.controller.subtitleState.value)
        assertEquals(SubtitleDisplay.OFF, f.controller.display.value)
        assertNull(f.hidden.temporaryId.value)
        assertEquals(expectedSelected, f.selected())
        assertEquals(0, f.activeJobs)
        val position = f.player.positionMs.value
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(position, f.player.positionMs.value)
        assertEquals(expectedSelected, f.selected())
        assertEquals(0, f.activeJobs)
    }

    private suspend fun TestScope.runningReplay(): Fixture {
        val f = fixture(viewerId = "v1")
        f.controller.press(SubtitleDisplay.ENGLISH)
        settle()
        confirmSeek(f, 70_000L)
        assertNotNull(f.hidden.temporaryId.value)
        return f
    }

    @Test
    fun `reaching the origin leaves everything as before`() =
        runTest {
            val f = runningReplay()
            f.player.emitPosition(72_000L)
            runCurrent()
            assertEndedClean(f, "v1")
        }

    @Test
    fun `cancel leaves everything as before`() =
        runTest {
            val f = runningReplay()
            f.controller.cancel()
            assertEndedClean(f, "v1")
        }

    @Test
    fun `a hand seek leaves everything as before`() =
        runTest {
            val f = runningReplay()
            f.controller.onUserSeek()
            assertEndedClean(f, "v1")
        }

    @Test
    fun `the video ending leaves everything as before`() =
        runTest {
            val f = runningReplay()
            f.player.end()
            runCurrent()
            assertEndedClean(f, "v1")
        }

    @Test
    fun `a player error leaves everything as before`() =
        runTest {
            val f = runningReplay()
            f.player.fail("codec")
            runCurrent()
            assertEndedClean(f, "v1")
        }

    @Test
    fun `a viewer track change leaves the viewer's choice and nothing pending`() =
        runTest {
            val f = runningReplay()
            f.hidden.selectByViewer("v2")
            runCurrent()
            assertEndedClean(f, "v2")
        }

    @Test
    fun `no cue under the first press leaves everything as before`() =
        runTest {
            val f = fixture(viewerId = "v1")
            f.player.emitPosition(99_000L)
            runCurrent()
            f.controller.press(SubtitleDisplay.ENGLISH)
            settle()
            assertEndedClean(f, "v1")
            assertTrue(f.player.externalSubtitleCalls.isEmpty())
        }
}
