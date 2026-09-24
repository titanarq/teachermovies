package com.teachermovies.tv.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.Torrent
import com.teachermovies.core.model.TorrentId
import com.teachermovies.core.repo.fake.InMemoryTorrentRepository
import com.teachermovies.player.api.PlayerState
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

    private fun TestScope.viewModel(): PlayerViewModel =
        PlayerViewModel(PlaybackSession(player, repo, backgroundScope, clock = { 0L }), player, backgroundScope)

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
}
