package com.teachermovies.mobile.downloads

import com.teachermovies.mobile.api.AddMagnetOutcome
import com.teachermovies.mobile.api.ApiFailure
import com.teachermovies.mobile.api.ApiResult
import com.teachermovies.mobile.api.TorrentSummary
import com.teachermovies.mobile.api.fake.FakeTvApi
import com.teachermovies.mobile.data.PairedTv
import com.teachermovies.mobile.data.fake.InMemoryPairedTvStore
import com.teachermovies.mobile.send.MagnetSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
    private val livingRoom = PairedTv("Movie Assistant (Salón)", "http://192.168.1.20:8787", "secret-token")
    private val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Movie"
    private val sent = "Enviado a ${livingRoom.instanceName}"
    private val noMagnetNotice = "No hay ningún enlace magnet"

    private val movie = torrent("Big Buck Bunny", progress = 72.44)
    private val other = torrent("Sintel", progress = 12.0)

    private val api = FakeTvApi()
    private val store = InMemoryPairedTvStore(livingRoom)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(pollIntervalMs: Long = 3_000) =
        DownloadsViewModel(api, store, MagnetSender(api, store), pollIntervalMs)

    /** Collects the state the way a screen does, and lets the poll that follows run. */
    private fun TestScope.subscribe(vm: DownloadsViewModel): Job {
        val job = backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        return job
    }

    private fun polls(): Int = api.calls.count { it is FakeTvApi.Call.Torrents }

    private fun magnets(): List<FakeTvApi.Call.AddMagnet> = api.calls.filterIsInstance<FakeTvApi.Call.AddMagnet>()

    @Test
    fun `nothing is polled before a screen subscribes`() =
        runTest {
            val vm = viewModel()

            advanceTimeBy(30_000)
            runCurrent()

            assertEquals(0, polls())
            assertEquals(DownloadsUiState.Loading(), vm.uiState.value)
        }

    @Test
    fun `the first poll shows the TV's downloads`() =
        runTest {
            api.torrentsResult = ApiResult.Success(listOf(movie, other))
            val vm = viewModel()
            assertEquals(DownloadsUiState.Loading(), vm.uiState.value)

            subscribe(vm)

            assertEquals(DownloadsUiState.Loaded(listOf(movie, other)), vm.uiState.value)
            assertEquals(listOf(FakeTvApi.Call.Torrents(livingRoom.baseUrl, livingRoom.token)), api.calls)
        }

    @Test
    fun `the list is polled again after the interval, and not before`() =
        runTest {
            api.torrentsResult = ApiResult.Success(listOf(movie))
            val vm = viewModel(pollIntervalMs = 3_000)
            subscribe(vm)
            assertEquals(1, polls())

            advanceTimeBy(2_999)
            runCurrent()
            assertEquals(1, polls())

            api.torrentsResult = ApiResult.Success(listOf(movie, other))
            advanceTimeBy(1)
            runCurrent()

            assertEquals(2, polls())
            assertEquals(DownloadsUiState.Loaded(listOf(movie, other)), vm.uiState.value)
        }

    @Test
    fun `a failed poll keeps the list and marks the TV offline until it answers again`() =
        runTest {
            api.torrentsResult = ApiResult.Success(listOf(movie))
            val vm = viewModel()
            subscribe(vm)

            api.torrentsResult = ApiResult.Failure(ApiFailure.Network("timeout"))
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(DownloadsUiState.Loaded(listOf(movie), offline = true), vm.uiState.value)
            assertEquals(livingRoom, store.pairedTv.value)

            api.torrentsResult = ApiResult.Success(listOf(other))
            advanceTimeBy(3_000)
            runCurrent()

            assertEquals(DownloadsUiState.Loaded(listOf(other)), vm.uiState.value)
        }

    @Test
    fun `a first poll that fails shows an offline list with nothing in it`() =
        runTest {
            api.torrentsResult = ApiResult.Failure(ApiFailure.Network("connection refused"))
            val vm = viewModel()

            subscribe(vm)

            assertEquals(DownloadsUiState.Loaded(emptyList(), offline = true), vm.uiState.value)
        }

    @Test
    fun `a token the TV refuses is forgotten and stops the polling`() =
        runTest {
            api.torrentsResult = ApiResult.Failure(ApiFailure.Unauthorized)
            val vm = viewModel()

            subscribe(vm)

            assertEquals(null, store.pairedTv.value)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(1, polls())
        }

    @Test
    fun `no stored TV is not polled, and pairing one starts it`() =
        runTest {
            store.clear()
            val vm = viewModel()

            subscribe(vm)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(0, polls())
            assertEquals(DownloadsUiState.Loading(), vm.uiState.value)

            store.save(livingRoom)
            runCurrent()

            assertEquals(1, polls())
            assertEquals(DownloadsUiState.Loaded(emptyList()), vm.uiState.value)
        }

    @Test
    fun `unsubscribing stops the polling and subscribing again polls at once`() =
        runTest {
            api.torrentsResult = ApiResult.Success(listOf(movie))
            val vm = viewModel()
            val subscribed = subscribe(vm)
            assertEquals(1, polls())

            subscribed.cancel()
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(1, polls())

            api.torrentsResult = ApiResult.Success(listOf(other))
            subscribe(vm)

            assertEquals(2, polls())
            assertEquals(DownloadsUiState.Loaded(listOf(other)), vm.uiState.value)
        }

    @Test
    fun `a magnet the TV accepts is shown and refreshes the list at once`() =
        runTest {
            api.addMagnetResult = AddMagnetOutcome.Added(id = "0123456789abcdef", state = "fetching_metadata")
            api.torrentsResult = ApiResult.Success(listOf(movie))
            val vm = viewModel()
            subscribe(vm)
            assertEquals(1, polls())

            api.torrentsResult = ApiResult.Success(listOf(movie, other))
            vm.send(magnet)
            runCurrent()

            assertEquals(2, polls())
            assertEquals(listOf(FakeTvApi.Call.AddMagnet(livingRoom.baseUrl, livingRoom.token, magnet)), magnets())
            assertEquals(DownloadsUiState.Loaded(listOf(movie, other), notice = sent), vm.uiState.value)
        }

    @Test
    fun `a magnet the TV rejects is shown once and does not refresh the list`() =
        runTest {
            api.addMagnetResult = AddMagnetOutcome.InvalidMagnet
            api.torrentsResult = ApiResult.Success(listOf(movie))
            val vm = viewModel()
            subscribe(vm)

            vm.send(magnet)
            runCurrent()

            assertEquals(1, polls())
            assertEquals("La TV rechazó el enlace magnet", vm.uiState.value.notice)

            advanceTimeBy(3_000)
            runCurrent()
            assertEquals("La TV rechazó el enlace magnet", vm.uiState.value.notice)

            vm.noticeShown()
            runCurrent()

            assertEquals(DownloadsUiState.Loaded(listOf(movie)), vm.uiState.value)
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(null, vm.uiState.value.notice)
        }

    @Test
    fun `text without a magnet never reaches the TV and says so`() =
        runTest {
            val vm = viewModel()
            subscribe(vm)

            vm.send("https://example.com/movie")
            runCurrent()

            assertTrue(magnets().isEmpty())
            assertEquals(1, polls())
            assertEquals(DownloadsUiState.Loaded(emptyList(), notice = noMagnetNotice), vm.uiState.value)
        }

    @Test
    fun `a send with no TV stored asks to pair first`() =
        runTest {
            store.clear()
            val vm = viewModel()
            subscribe(vm)

            vm.send(magnet)
            runCurrent()

            assertTrue(magnets().isEmpty())
            assertEquals("Empareja primero la TV", vm.uiState.value.notice)
        }

    @Test
    fun `a notice shown while loading survives the first poll`() =
        runTest {
            val vm = viewModel()

            vm.send("not a magnet")
            runCurrent()
            assertEquals(DownloadsUiState.Loading(noMagnetNotice), vm.uiState.value)

            subscribe(vm)

            assertEquals(DownloadsUiState.Loaded(emptyList(), notice = noMagnetNotice), vm.uiState.value)
        }

    private fun torrent(
        name: String,
        progress: Double,
    ) = TorrentSummary(
        id = name.lowercase().replace(' ', '-'),
        name = name,
        state = "downloading",
        progress = progress,
        downloadedBytes = 18_400_000_000,
        totalBytes = 25_600_000_000,
        downloadSpeed = 8_300_000,
        peers = 12,
        etaSeconds = 900,
    )
}
