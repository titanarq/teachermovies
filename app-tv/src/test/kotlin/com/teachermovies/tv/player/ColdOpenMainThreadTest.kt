package com.teachermovies.tv.player

import com.teachermovies.assistant.AssistantSpeechController
import com.teachermovies.assistant.HiddenSubtitleController
import com.teachermovies.assistant.LineCaptureController
import com.teachermovies.assistant.SubtitleEngine
import com.teachermovies.assistant.speech.Speaker
import com.teachermovies.assistant.speech.SpeakerAvailability
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #262 stage 1: a trace of *which thread* a cold open runs on.
 *
 * The panel the ANR was blamed on is not where the blocking is: `TracksPanel` takes an immutable
 * [TracksPanelState] and two callbacks, and neither it nor [TracksPanelState] reaches a file, a
 * player or a parser. What shares its frames is the open chain. [PlayerViewModel.open] launches
 * [PlaybackSession.open] on `viewModelScope` -- `Dispatchers.Main.immediate` -- with no dispatcher
 * hop, and `Player`'s controls are synchronous on the caller's thread (the `VlcPlayer` KDoc says so
 * and warns that `open` belongs off the main thread). So on the main thread of a process that has
 * not loaded `libvlc.so` yet, one open does: the native `open`/`stop`/`play`, two directory scans
 * for sidecars, one `addSlave` plus a full native track re-enumeration per sidecar found, and the
 * whole-file read that parses the hidden English subtitle. The same is true of the way out:
 * [PlaybackSession.close] calls `Player.release`, which is `stop()` plus two native releases, and
 * the `VlcPlayer` KDoc singles `stop()` out as blocking "for as long as the decoder takes to wind
 * down".
 *
 * What the emulator evidence says, and what it does not (this worker has no `adb`, so there is no
 * new repro): no ANR record was ever captured. No `am_anr`, no `ANR in`, no `Input dispatching timed
 * out` and no `/data/anr` stack exists in any logcat under `.cache/emulator-verification/`, so the
 * whole attribution to this panel rests on one sentence, the side finding at
 * `.cache/emulator-verification/results-2026-09-26.md:113`. The same cold captures do show the main
 * thread stalling for seconds -- in `2026-09-26/248-resmoke/logcat-cold.txt`, a burst from
 * `Skipped 50 frames!` at 09-25 22:21:07.167 to `HWUI Davey! duration=2844ms` at 22:21:10.098, whose
 * frame spans `AudioTrack: stop()` at 22:21:09.672 and ends 3.9 s before `Start proc 4358` restarts
 * the app, so it coincides with playback teardown; 2.8 s is over half of the 5 s an input-dispatch
 * ANR waits for. Those stalls are not proof of this chain, though: they also appear in the warm
 * session and in the middle of plain playback, on a swiftshader AVD that software-rasterizes every
 * frame of it. Two candidates can account for the ANR and only a captured stack separates them --
 * this chain, and the cost of software-rendering a full-height 85%-alpha panel over decoding video
 * on every position tick (the call site at `PlayerScreen.kt:207-215` passes `viewModel::selectAudio`
 * and `viewModel::selectSubtitle`, which capture the ViewModel, so the panel call is not skippable
 * and its body re-executes on each tick while it is open).
 *
 * `Dispatchers.setMain(StandardTestDispatcher())` makes "the main thread" whichever thread drives
 * the test scheduler, so these assertions only mean something next to their controls: the same
 * recording shows a *different* thread for the one piece of `open` the production code does move off
 * main (`speech.prepare()` on `prepareDispatcher`) and for an explicit `withContext(Dispatchers.IO)`.
 * They compare `Thread` identities, not names: with the coroutines debug probes on the test
 * classpath a thread's name carries the coroutine it is currently running (`Test worker
 * &#64;coroutine#7`), so the name differs between two blocks on one and the same thread.
 *
 * This is a trace of the code as it stands, not a wish: if the open chain is moved off the main
 * thread -- the fix #262 stage 2 exists to make -- these assertions fail, and the failure message
 * says to update them to the new thread.
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
    private val speaker = ThreadRecordingSpeaker(FakeSpeaker())
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
    fun aColdOpenRunsTheNativeOpenAndTheSidecarScanOnTheMainThread() =
        runTest(dispatcher) {
            val mainThread = withContext(Dispatchers.Main) { Thread.currentThread() }
            val offMain = withContext(Dispatchers.IO) { Thread.currentThread() }
            assertNotEquals(
                "the recorder cannot tell the threads apart, so nothing below would prove anything",
                mainThread,
                offMain,
            )

            seed(movieWithEnglishSubtitles())
            val prepared = CountDownLatch(1)
            speaker.onPrepared = { prepared.countDown() }
            val viewModel = viewModel()

            viewModel.open(id)
            runCurrent()

            // The control: the one hop `open` makes off the main thread really does change thread.
            assertTrue("speech.prepare() never ran", prepared.await(10, TimeUnit.SECONDS))
            assertNotEquals(
                "speech.prepare() is the control: open() moves it to prepareDispatcher, so it must " +
                    "not land on the main thread",
                mainThread,
                speaker.prepareThread,
            )

            assertEquals(
                "player.open ran off the main thread. That is the #262 fix, not a regression: update " +
                    "this trace (and the issue) to the thread the open chain now runs on.",
                listOf(mainThread),
                player.threadsOf("open"),
            )
            assertEquals(
                "the sidecar scan ran off the main thread. That is the #262 fix, not a regression: " +
                    "update this trace to the thread addExternalSubtitle now runs on.",
                listOf(mainThread),
                player.threadsOf("addExternalSubtitle"),
            )
        }

    /** The real controllers over [player], as `PlayerViewModelTest` builds them, minus the panels. */
    private fun TestScope.viewModel(): PlayerViewModel {
        val engine = SubtitleEngine(player.positionMs, backgroundScope)
        return PlayerViewModel(
            PlaybackSession(player, repo, backgroundScope, clock = { 0L }),
            player,
            HiddenSubtitleController(player, engine, backgroundScope, tmp.newFolder("cache")),
            LineCaptureController(player, engine, backgroundScope, clock = { 0L }),
            AssistantSpeechController(speaker, FakeTranslationProvider(), backgroundScope, allSpoken),
            backgroundScope,
            prepareDispatcher = Dispatchers.IO,
        )
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
}

/** [FakePlayer] plus the thread each control was called on: the trace the test asserts on. */
private class ThreadRecordingPlayer(
    private val fake: FakePlayer,
) : Player by fake {
    private val calls = mutableListOf<Pair<String, Thread>>()

    fun threadsOf(control: String): List<Thread> = calls.filter { it.first == control }.map { it.second }

    override fun open(
        file: File,
        startPositionMs: Long,
        growing: Boolean,
    ) {
        calls += "open" to Thread.currentThread()
        fake.open(file, startPositionMs, growing)
    }

    override fun addExternalSubtitle(
        file: File,
        select: Boolean,
    ) {
        calls += "addExternalSubtitle" to Thread.currentThread()
        fake.addExternalSubtitle(file, select)
    }
}

/** [FakeSpeaker] plus the thread `prepare` ran on, which the production code moves off main. */
private class ThreadRecordingSpeaker(
    private val delegate: FakeSpeaker,
) : Speaker by delegate {
    @Volatile
    var prepareThread: Thread? = null
        private set

    @Volatile
    var onPrepared: () -> Unit = {}

    override suspend fun prepare(): SpeakerAvailability {
        prepareThread = Thread.currentThread()
        onPrepared()
        return delegate.prepare()
    }
}
