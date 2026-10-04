package com.teachermovies.tv.player

import com.teachermovies.assistant.AssistantSpeechController
import com.teachermovies.assistant.HiddenSubtitleController
import com.teachermovies.assistant.LineCaptureController
import com.teachermovies.assistant.SubtitleEngine
import com.teachermovies.assistant.speech.SpokenOutputSettings
import com.teachermovies.assistant.speech.fake.FakeSpeaker
import com.teachermovies.assistant.translation.fake.FakeTranslationProvider
import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.player.api.Player
import com.teachermovies.player.fake.FakePlayer
import com.teachermovies.player.session.PlaybackSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * #262: a cold open and the close that follows it no longer run on the main thread.
 *
 * The panel the ANR was blamed on was never where the blocking is -- `TracksPanel` takes an
 * immutable [TracksPanelState] and two callbacks, and neither it nor [TracksPanelState] reaches a
 * file, a player or a parser. What shares its frames is the open chain, which stage 1 traced to the
 * main thread: [PlayerViewModel.open] launched [PlaybackSession.open] on `viewModelScope`
 * (`Dispatchers.Main.immediate`) with no dispatcher hop, and `Player`'s controls are synchronous on
 * the caller's thread -- the `VlcPlayer` KDoc says so and warns that `open` belongs off the main
 * thread. So one cold open did, on the main thread of a process that had not loaded `libvlc.so`
 * yet: the native `open`/`stop`/`play`, two directory scans for sidecar subtitles, one `addSlave`
 * plus a full native track re-enumeration per sidecar found, the whole-file read that parses the
 * hidden English subtitle, and on the way out [PlaybackSession.close] -> `Player.release`, which is
 * `stop()` plus two native releases.
 *
 * What moved: `PlaybackSession.open`/`openStreaming`/`close` run everything that reaches the player
 * or the disk on the `blockingDispatcher` they are now built with (`Dispatchers.IO` from
 * [PlayerViewModel.Factory]), and [PlayerViewModel] runs the open's hidden-subtitle scan and parse
 * -- `hidden.start` -- and the release of an open cut while it was still waiting for ranges on its
 * `prepareDispatcher`.
 *
 * What the emulator evidence says, and what it does not (this worker has no `adb`, so there is no
 * new repro): no ANR record was ever captured. No `am_anr`, no `ANR in`, no `Input dispatching timed
 * out` and no `/data/anr` stack exists in any logcat under `.cache/emulator-verification/`, so the
 * attribution to the panel rests on one sentence, the side finding at
 * `.cache/emulator-verification/results-2026-09-26.md:113`. The same cold captures do show the main
 * thread stalling for seconds -- in `2026-09-26/248-resmoke/logcat-cold.txt` a burst from
 * `Skipped 50 frames!` at 09-25 22:21:07.167 to `HWUI Davey! duration=2844ms` at 22:21:10.098, whose
 * frame spans `AudioTrack: stop()` at 22:21:09.672 and ends 3.9 s before `Start proc 4358` restarts
 * the app, so it coincides with playback teardown; 2.8 s is over half of the 5 s an input-dispatch
 * ANR waits for. Those stalls are not proof of this chain: they also appear in the warm session and
 * in the middle of plain playback, on a swiftshader AVD that software-rasterizes every frame, and
 * the other candidate -- software-rendering a full-height 85%-alpha panel over decoding video on
 * each position tick, which the call site at `PlayerScreen.kt:207-215` does make it re-execute --
 * is untouched by this fix and needs a captured stack to rule in or out.
 *
 * How these assertions are made to mean something:
 * - They compare `Thread` identities, not names: with the coroutines debug probes on the test
 *   classpath a thread's name carries the coroutine it is currently running (`Test worker
 *   &#64;coroutine#7`), so the name differs between two blocks on one and the same thread.
 * - They carry a control in the other direction. `Dispatchers.setMain(StandardTestDispatcher())`
 *   makes "the main thread" whichever thread drives the test scheduler, so "off the main thread"
 *   would be vacuous if the recorder never saw the main thread at all: the cold-open test first has
 *   the ViewModel make a call that *does* stay on main -- the tracks panel's own
 *   [PlayerViewModel.selectAudio], a plain `setAudioTrack` -- and asserts it is recorded there.
 * - They wait by pumping the scheduler, not by blocking on it. The chain under test hops to a real
 *   IO thread, and what it does there -- including the cancellation `close()` joins -- only resumes
 *   once this thread drives the test scheduler, so a bare latch await would deadlock. See
 *   [waitUntil].
 *
 * Not asserted: the thread `hidden.start`'s sidecar scan and whole-file parse run on. Nothing
 * observable marks them -- the selection that follows them is also made on the main thread by
 * design, by the controller's own re-assert collector -- so their move rests on the
 * `withContext(prepareDispatcher)` at the call site and on [PlayerViewModelTest], which drives the
 * same open with a test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ColdOpenMainThreadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val id = TorrentId("b".repeat(40))
    private val fake = FakePlayer()
    private val player = ThreadRecordingPlayer(fake)
    private val repo = InMemoryTorrentRepository()
    private val allSpoken: MutableStateFlow<SpokenOutputSettings> =
        MutableStateFlow(SpokenOutputSettings(englishLine = true, spanishLine = true, explanations = true))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aColdOpenRunsTheNativeOpenAndTheSidecarScanOffTheMainThread() =
        runTest(dispatcher) {
            val mainThread = withContext(Dispatchers.Main) { Thread.currentThread() }
            val offMain = withContext(Dispatchers.IO) { Thread.currentThread() }
            assertNotEquals(
                "the recorder cannot tell the threads apart, so nothing below would prove anything",
                mainThread,
                offMain,
            )

            seed(movieWithEnglishSubtitles())
            val viewModel = viewModel()

            // The control: a call the ViewModel makes on the main thread is recorded on it, so the
            // two assertions below measure a hop instead of a recorder that never sees main.
            viewModel.selectAudio("a1")
            assertEquals(listOf("selectAudio"), player.controlsOn(mainThread))
            player.clear()

            viewModel.open(id)
            runCurrent()
            waitUntil("the cold open never reached the player") {
                player.threadsOf("open").isNotEmpty() && player.threadsOf("addExternalSubtitle").isNotEmpty()
            }

            assertNotEquals(
                "player.open ran on the main thread: loading libvlc.so, Media and play block it for " +
                    "as long as a cold device takes (#262). PlaybackSession must run it on the " +
                    "dispatcher it was built with.",
                mainThread,
                player.threadsOf("open").single(),
            )
            assertNotEquals(
                "the sidecar scan ran on the main thread: two directory walks, then one addSlave " +
                    "and a full native track re-enumeration per subtitle file found (#262).",
                mainThread,
                player.threadsOf("addExternalSubtitle").single(),
            )
        }

    @Test
    fun closingTheSessionReleasesThePlayerOffTheMainThread() =
        runTest(dispatcher) {
            val mainThread = withContext(Dispatchers.Main) { Thread.currentThread() }
            seed(movieWithEnglishSubtitles())
            val session = session()

            backgroundScope.launch { session.open(id) }
            runCurrent()
            waitUntil("nothing was opened, so there is no teardown to measure") {
                player.threadsOf("open").isNotEmpty()
            }

            backgroundScope.launch { session.close() }
            runCurrent()
            waitUntil("close() never released the player") { player.threadsOf("release").isNotEmpty() }

            assertNotEquals(
                "player.release ran on the main thread: it is stop() plus two native releases, and " +
                    "the longest main-thread stall in the 2026-09-26 cold captures (2844 ms) spans " +
                    "exactly that teardown (#262).",
                mainThread,
                player.threadsOf("release").single(),
            )
        }

    /** The real controllers over [player], as `PlayerViewModelTest` builds them, minus the panels. */
    private fun TestScope.viewModel(): PlayerViewModel {
        val engine = SubtitleEngine(player.positionMs, backgroundScope)
        return PlayerViewModel(
            session(),
            player,
            HiddenSubtitleController(player, engine, backgroundScope, tmp.newFolder("cache")),
            LineCaptureController(player, engine, backgroundScope, clock = { 0L }),
            AssistantSpeechController(FakeSpeaker(), FakeTranslationProvider(), backgroundScope, allSpoken),
            backgroundScope,
            prepareDispatcher = Dispatchers.IO,
        )
    }

    /** The real session over [player], on the IO dispatcher the app gives it. */
    private fun TestScope.session(): PlaybackSession =
        PlaybackSession(
            player,
            repo,
            backgroundScope,
            clock = { 0L },
            blockingDispatcher = Dispatchers.IO,
        )

    /**
     * Waits for [condition], driving the test scheduler while waiting. The work under test runs on
     * a real IO thread and resumes on this scheduler when it is done, so blocking on the result
     * without pumping would deadlock -- `close()`'s `cancelAndJoin` of the session's collectors
     * cannot complete until the scheduler cancels them.
     */
    private fun TestScope.waitUntil(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition()) {
            runCurrent()
            if (System.nanoTime() >= deadline) fail("$message (waited ${WAIT_SECONDS}s)")
            Thread.sleep(POLL_MS)
        }
        runCurrent()
    }

    private suspend fun seed(movie: File) {
        repo.upsert(
            Torrent(
                id = id,
                name = "Big Movie",
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
    }

    private fun movieFile(): File = tmp.newFolder("Movies", id.value).resolve("Big Movie.mkv").apply { writeText("x") }

    /** A finished movie with one English sidecar next to it, the case the ANR was seen in. */
    private fun movieWithEnglishSubtitles(): File =
        movieFile().also { movie ->
            movie.resolveSibling("Big Movie.en.srt").writeText("1\n00:00:01,000 --> 00:00:03,000\nHello there.\n")
        }

    private companion object {
        /** How long [waitUntil] gives real IO work before it is a failure and not a slow machine. */
        const val WAIT_SECONDS = 10L
        const val POLL_MS = 5L
    }
}

/** [FakePlayer] plus the thread each control was called on: the trace the test asserts on. */
private class ThreadRecordingPlayer(
    private val fake: FakePlayer,
) : Player by fake {
    private val calls = CopyOnWriteArrayList<Pair<String, Thread>>()

    /** The controls recorded on [thread], in call order. */
    fun controlsOn(thread: Thread): List<String> = calls.filter { it.second == thread }.map { it.first }

    fun threadsOf(control: String): List<Thread> = calls.filter { it.first == control }.map { it.second }

    fun clear() {
        calls.clear()
    }

    override fun open(
        file: File,
        startPositionMs: Long,
        growing: Boolean,
    ) {
        calls.add("open" to Thread.currentThread())
        fake.open(file, startPositionMs, growing)
    }

    override fun addExternalSubtitle(
        file: File,
        select: Boolean,
    ) {
        calls.add("addExternalSubtitle" to Thread.currentThread())
        fake.addExternalSubtitle(file, select)
    }

    override fun selectAudio(id: String) {
        calls.add("selectAudio" to Thread.currentThread())
        fake.selectAudio(id)
    }

    override fun release() {
        calls.add("release" to Thread.currentThread())
        fake.release()
    }
}
