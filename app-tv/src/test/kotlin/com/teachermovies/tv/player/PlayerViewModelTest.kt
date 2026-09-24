package com.teachermovies.tv.player

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.teachermovies.assistant.HiddenSubtitleController
import com.teachermovies.assistant.LineCaptureController
import com.teachermovies.assistant.SubtitleEngine
import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.player.api.PlayerState
import com.teachermovies.player.api.Track
import com.teachermovies.player.fake.FakePlayer
import com.teachermovies.player.session.PlaybackSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [PlayerViewModel] over a real [PlaybackSession] driven by [FakePlayer] and
 * [InMemoryTorrentRepository] (ADR-0003), everything on one [StandardTestDispatcher] so the overlay
 * timer runs on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val id = TorrentId("b".repeat(40))
    private val player = FakePlayer()
    private val repo = InMemoryTorrentRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private lateinit var hidden: HiddenSubtitleController
    private lateinit var capture: LineCaptureController

    /** The real assistant controllers (#83, #85) over [player], on the test's background scope. */
    private fun TestScope.viewModel(): PlayerViewModel {
        val engine = SubtitleEngine(player.positionMs, backgroundScope)
        hidden = HiddenSubtitleController(player, engine, backgroundScope, tmp.newFolder("cache"))
        capture = LineCaptureController(player, engine, backgroundScope)
        return PlayerViewModel(
            PlaybackSession(player, repo, backgroundScope, clock = { 0L }),
            player,
            hidden,
            capture,
            backgroundScope,
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

    /** A movie with an English sidecar: one line, "Hello there.", from 1 s to 3 s. */
    private fun movieWithEnglishSubtitles(): File =
        movieFile().also { movie ->
            movie.resolveSibling("Big Movie.en.srt").writeText("1\n00:00:01,000 --> 00:00:03,000\nHello there.\n")
        }

    private fun TestScope.openedViewModel(): PlayerViewModel {
        val vm = viewModel()
        vm.open(id)
        runCurrent()
        return vm
    }

    @Test
    fun openShowsTheTitleAndFormatsPositionAndDuration() =
        runTest(dispatcher) {
            seed(movieFile())
            val vm = openedViewModel()

            player.emitDuration(3_725_000L)
            player.emitPosition(65_000L)
            player.play()
            runCurrent()

            val state = vm.uiState.value
            assertEquals("Big Movie", state.title)
            assertEquals("1:05", state.positionText)
            assertEquals("1:02:05", state.durationText)
            assertEquals(65_000f / 3_725_000f, state.progress, 1e-6f)
            assertTrue(state.isPlaying)
            assertNull(state.error)
        }

    @Test
    fun aMissingFileShowsTheDiskMessage() =
        runTest(dispatcher) {
            seed(File(tmp.root, "unplugged/Big Movie.mkv"))
            val vm = openedViewModel()

            assertEquals("El archivo no está disponible (¿se ha desconectado el disco?)", vm.uiState.value.error)
            assertEquals(PlayerState.Idle, player.state.value)
        }

    @Test
    fun anUnknownItemShowsNotFound() =
        runTest(dispatcher) {
            val vm = openedViewModel()

            assertEquals(PlayerViewModel.NOT_FOUND, vm.uiState.value.error)
        }

    @Test
    fun aPlayerFailureBecomesTheError() =
        runTest(dispatcher) {
            seed(movieFile())
            val vm = openedViewModel()

            player.fail("codec")
            runCurrent()

            assertEquals("No se puede reproducir: codec", vm.uiState.value.error)
        }

    @Test
    fun actionsAreForwardedToThePlayer() =
        runTest(dispatcher) {
            seed(movieFile())
            val vm = openedViewModel()
            player.emitDuration(600_000L)
            player.emitPosition(100_000L)

            vm.onAction(PlayerAction.Play)
            assertEquals(PlayerState.Playing, player.state.value)
            vm.onAction(PlayerAction.TogglePlayPause)
            assertEquals(PlayerState.Paused, player.state.value)
            vm.onAction(PlayerAction.TogglePlayPause)
            assertEquals(PlayerState.Playing, player.state.value)
            vm.onAction(PlayerAction.Pause)
            assertEquals(PlayerState.Paused, player.state.value)

            vm.onAction(PlayerAction.SeekBy(10_000L))
            assertEquals(110_000L, player.positionMs.value)
            vm.onAction(PlayerAction.SeekBy(-30_000L))
            assertEquals(80_000L, player.positionMs.value)

            vm.onAction(PlayerAction.ShowTracks)
            vm.onAction(null)
            assertEquals(80_000L, player.positionMs.value)
            assertEquals(PlayerState.Paused, player.state.value)
        }

    @Test
    fun anyKeyShowsTheOverlayWhichHidesFourSecondsAfterTheLastKey() =
        runTest(dispatcher) {
            seed(movieFile())
            val vm = openedViewModel()
            assertTrue(vm.uiState.value.overlayVisible)

            advanceTimeBy(PlayerViewModel.OVERLAY_TIMEOUT_MS + 1)
            assertFalse(vm.uiState.value.overlayVisible)

            vm.onAction(null)
            runCurrent()
            assertTrue(vm.uiState.value.overlayVisible)

            advanceTimeBy(3_000L)
            vm.onAction(PlayerAction.SeekBy(10_000L))
            advanceTimeBy(3_000L)
            assertTrue("the second key restarts the timer", vm.uiState.value.overlayVisible)

            advanceTimeBy(1_001L)
            assertFalse(vm.uiState.value.overlayVisible)
        }

    @Test
    fun exitSavesThePositionReleasesThePlayerAndReportsExited() =
        runTest(dispatcher) {
            seed(movieFile())
            val vm = openedViewModel()
            player.emitDuration(600_000L)
            player.play()
            player.emitPosition(120_000L)
            runCurrent()

            vm.onAction(PlayerAction.Exit)
            runCurrent()

            assertTrue(vm.uiState.value.exited)
            assertEquals(PlayerState.Idle, player.state.value)
            assertEquals(120_000L, repo.getLibraryItem(id)?.lastPositionMs)

            vm.onAction(PlayerAction.Play)
            assertEquals("keys are ignored once exiting", PlayerState.Idle, player.state.value)
        }

    @Test
    fun clearingWithoutExitStillClosesTheSession() =
        runTest(dispatcher) {
            seed(movieFile())
            val store = ViewModelStore()
            val vm =
                ViewModelProvider(
                    store,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel() as T
                    },
                )[PlayerViewModel::class.java]
            vm.open(id)
            runCurrent()
            player.play()
            player.emitDuration(600_000L)
            player.emitPosition(42_000L)
            runCurrent()

            store.clear()
            runCurrent()

            assertEquals(PlayerState.Idle, player.state.value)
            assertEquals(42_000L, repo.getLibraryItem(id)?.lastPositionMs)
        }

    private val english = Track("a1", "English", "eng")
    private val spanish = Track("a2", "Castellano", "spa")
    private val subEn = Track("s1", "English", "eng")
    private val subEs = Track("s2", "", "spa")

    /** Opens the movie and lets the session apply its track policy (first audio, subtitles off). */
    private fun TestScope.openedWithTracks(): PlayerViewModel {
        val vm = openedViewModel()
        player.emitTracks(audio = listOf(english, spanish), subs = listOf(subEn, subEs))
        runCurrent()
        return vm
    }

    @Test
    fun showTracksOpensThePanelWithBothListsAndTheCurrentSelectionMarked() =
        runTest(dispatcher) {
            seed(movieFile())
            val vm = openedWithTracks()
            player.selectSubtitle("s2")
            runCurrent()
            assertNull(vm.uiState.value.tracksPanel)

            vm.onAction(PlayerAction.ShowTracks)
            runCurrent()

            val panel = requireNotNull(vm.uiState.value.tracksPanel)
            assertEquals(
                listOf(TrackOption("a1", "English", true), TrackOption("a2", "Castellano (spa)", false)),
                panel.audio,
            )
            assertEquals(
                listOf(
                    TrackOption(null, "Desactivados", false),
                    TrackOption("s1", "English", false),
                    TrackOption("s2", "spa", true),
                ),
                panel.subtitles,
            )
        }

    @Test
    fun selectingAnAudioTrackAppliesItClosesThePanelAndIsPersisted() =
        runTest(dispatcher) {
            seed(movieFile())
            val vm = openedWithTracks()
            vm.onAction(PlayerAction.ShowTracks)
            runCurrent()

            vm.selectAudio("a2")
            runCurrent()

            assertEquals("a2", player.selectedAudioId.value)
            assertNull(vm.uiState.value.tracksPanel)
            assertEquals("a2", repo.getLibraryItem(id)?.audioTrackId)
        }

    @Test
    fun selectingDesactivadosTurnsSubtitlesOffAndIsPersisted() =
        runTest(dispatcher) {
            seed(movieFile())
            val vm = openedWithTracks()
            player.selectSubtitle("s1")
            runCurrent()
            assertEquals("s1", repo.getLibraryItem(id)?.subtitleTrackId)
            vm.onAction(PlayerAction.ShowTracks)
            runCurrent()

            vm.selectSubtitle(null)
            runCurrent()

            assertNull(player.selectedSubtitleId.value)
            assertNull(vm.uiState.value.tracksPanel)
            assertNull(repo.getLibraryItem(id)?.subtitleTrackId)
        }

    @Test
    fun backClosesThePanelWithoutChangingAnythingAndASecondBackExits() =
        runTest(dispatcher) {
            seed(movieFile())
            val vm = openedWithTracks()
            vm.onAction(PlayerAction.ShowTracks)
            runCurrent()

            vm.back()
            runCurrent()

            assertNull(vm.uiState.value.tracksPanel)
            assertFalse(vm.uiState.value.exited)
            assertEquals("a1", player.selectedAudioId.value)
            assertNull(player.selectedSubtitleId.value)

            vm.onAction(PlayerAction.Exit)
            runCurrent()
            assertTrue(vm.uiState.value.exited)
        }

    @Test
    fun thePanelFollowsTrackChangesWhileOpenAndHidesOnError() =
        runTest(dispatcher) {
            seed(movieFile())
            val vm = openedViewModel()
            vm.onAction(PlayerAction.ShowTracks)
            runCurrent()
            assertEquals(emptyList<TrackOption>(), vm.uiState.value.tracksPanel?.audio)
            assertEquals(listOf(TrackOption(null, "Desactivados", true)), vm.uiState.value.tracksPanel?.subtitles)

            player.emitTracks(audio = listOf(english), subs = emptyList())
            runCurrent()
            assertEquals(listOf(TrackOption("a1", "English", true)), vm.uiState.value.tracksPanel?.audio)

            player.fail("codec")
            runCurrent()
            assertNull(vm.uiState.value.tracksPanel)
            vm.back()
            runCurrent()
            assertTrue("BACK exits when the error hides the panel", vm.uiState.value.exited)
        }

    /** Opens the subtitled movie, playing at 2 s (inside the one line). */
    private suspend fun TestScope.playingWithSubtitles(): PlayerViewModel {
        seed(movieWithEnglishSubtitles())
        val vm = openedViewModel()
        player.emitDuration(600_000L)
        player.play()
        player.emitPosition(2_000L)
        runCurrent()
        return vm
    }

    /** What the player screen does with a key: the assistant mapping first, then the transport one. */
    private fun PlayerViewModel.press(keyCode: Int) {
        val assistantAction = AssistantKeyMapper.map(keyCode, overlayOpen = uiState.value.assistant != null)
        if (assistantAction != null) onAssistantAction(assistantAction) else onAction(RemoteKeyMapper.map(keyCode))
    }

    @Test
    fun openingStartsHiddenModeAndTheAssistantIsAvailableWithAnEnglishSidecar() =
        runTest(dispatcher) {
            val vm = playingWithSubtitles()

            assertTrue(vm.uiState.value.assistantAvailable)
            assertTrue(hidden.active.value)
            assertNull(vm.uiState.value.assistant)
            assertEquals(PlayerState.Playing, player.state.value)
        }

    @Test
    fun captureFillsTheOverlayPausesAndHidesTheTransportOverlay() =
        runTest(dispatcher) {
            val vm = playingWithSubtitles()

            vm.press(KeyEvent.KEYCODE_DPAD_DOWN)
            runCurrent()

            assertEquals(AssistantOverlayState("Hello there.", replaying = false), vm.uiState.value.assistant)
            assertEquals(PlayerState.Paused, player.state.value)
            assertFalse(vm.uiState.value.overlayVisible)
        }

    @Test
    fun replayIsForwardedAndReplayingReachesTheUiState() =
        runTest(dispatcher) {
            val vm = playingWithSubtitles()
            vm.press(KeyEvent.KEYCODE_CAPTIONS)
            runCurrent()

            vm.press(KeyEvent.KEYCODE_DPAD_CENTER)
            runCurrent()

            assertEquals(AssistantOverlayState("Hello there.", replaying = true), vm.uiState.value.assistant)
            assertEquals(PlayerState.Playing, player.state.value)
            assertEquals("the fragment starts with its pre-roll", 700L, player.positionMs.value)

            player.emitPosition(3_300L)
            runCurrent()

            assertEquals(AssistantOverlayState("Hello there.", replaying = false), vm.uiState.value.assistant)
            assertEquals(PlayerState.Paused, player.state.value)
            assertEquals(2_000L, player.positionMs.value)
        }

    @Test
    fun dismissClearsTheOverlayAndResumesWhereTheLineWasCaptured() =
        runTest(dispatcher) {
            val vm = playingWithSubtitles()
            vm.press(KeyEvent.KEYCODE_DPAD_DOWN)
            runCurrent()

            vm.press(KeyEvent.KEYCODE_BACK)
            runCurrent()

            assertNull(vm.uiState.value.assistant)
            assertFalse("BACK closes the overlay, not the player", vm.uiState.value.exited)
            assertEquals(PlayerState.Playing, player.state.value)
            assertEquals(2_000L, player.positionMs.value)

            vm.press(KeyEvent.KEYCODE_DPAD_DOWN)
            runCurrent()
            vm.press(KeyEvent.KEYCODE_DPAD_DOWN)
            runCurrent()
            assertNull("DPAD_DOWN toggles the overlay closed too", vm.uiState.value.assistant)
            assertEquals(PlayerState.Playing, player.state.value)
        }

    @Test
    fun noLineShowsItsMessageForThreeSecondsAndResumes() =
        runTest(dispatcher) {
            val vm = playingWithSubtitles()
            player.emitPosition(10_000L)
            runCurrent()

            vm.press(KeyEvent.KEYCODE_DPAD_DOWN)
            runCurrent()

            assertNull(vm.uiState.value.assistant)
            assertEquals(PlayerViewModel.NO_LINE, vm.uiState.value.message)
            assertTrue(vm.uiState.value.overlayVisible)
            assertEquals(PlayerState.Playing, player.state.value)

            advanceTimeBy(PlayerViewModel.MESSAGE_TIMEOUT_MS + 1)
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun withoutEnglishSubtitlesCaptureShowsTheMessageAndDoesNotPause() =
        runTest(dispatcher) {
            seed(movieFile())
            val vm = openedViewModel()
            player.play()
            player.emitPosition(2_000L)
            runCurrent()
            assertFalse(vm.uiState.value.assistantAvailable)

            vm.press(KeyEvent.KEYCODE_CAPTIONS)
            runCurrent()

            assertEquals("Esta película no tiene subtítulos en inglés", vm.uiState.value.message)
            assertNull(vm.uiState.value.assistant)
            assertEquals(PlayerState.Playing, player.state.value)

            advanceTimeBy(PlayerViewModel.MESSAGE_TIMEOUT_MS + 1)
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun transportKeysAreIgnoredWhileTheOverlayIsOpen() =
        runTest(dispatcher) {
            val vm = playingWithSubtitles()
            vm.press(KeyEvent.KEYCODE_DPAD_DOWN)
            runCurrent()

            listOf(
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_SPACE,
            ).forEach { vm.press(it) }
            vm.onAction(PlayerAction.TogglePlayPause)
            runCurrent()

            assertEquals(2_000L, player.positionMs.value)
            assertEquals(PlayerState.Paused, player.state.value)
            assertNull(vm.uiState.value.tracksPanel)
            assertFalse(vm.uiState.value.overlayVisible)
            assertEquals(AssistantOverlayState("Hello there.", replaying = false), vm.uiState.value.assistant)
        }

    @Test
    fun exitWithALineCapturedStopsTheAssistantAndCloses() =
        runTest(dispatcher) {
            val vm = playingWithSubtitles()
            vm.press(KeyEvent.KEYCODE_DPAD_DOWN)
            runCurrent()

            vm.exit()
            runCurrent()

            assertTrue(vm.uiState.value.exited)
            assertNull(capture.captured.value)
            assertFalse(hidden.active.value)
            assertEquals(PlayerState.Idle, player.state.value)
        }
}
