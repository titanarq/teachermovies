package com.teachermovies.mobile.share

import com.teachermovies.mobile.api.AddMagnetOutcome
import com.teachermovies.mobile.api.ApiFailure
import com.teachermovies.mobile.api.fake.FakeTvApi
import com.teachermovies.mobile.data.PairedTv
import com.teachermovies.mobile.data.fake.InMemoryPairedTvStore
import com.teachermovies.mobile.send.MagnetSender
import com.teachermovies.mobile.send.SendOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {
    private val livingRoom = PairedTv("Movie Assistant (Salón)", "http://192.168.1.20:8787", "secret-token")
    private val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Movie"

    private val api = FakeTvApi()
    private val store = InMemoryPairedTvStore(livingRoom)

    @Before
    fun setUp() {
        // `StandardTestDispatcher`, not the `UnconfinedTestDispatcher` the other ViewModel tests use:
        // it queues the send instead of running it inside the constructor, and that queue is the only
        // way to see `Sending` before `Done`.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(text: String?) = ShareViewModel(MagnetSender(api, store), text)

    private fun magnets(): List<FakeTvApi.Call.AddMagnet> = api.calls.filterIsInstance<FakeTvApi.Call.AddMagnet>()

    @Test
    fun `a shared magnet is sent to the paired TV and its outcome shown`() =
        runTest {
            api.addMagnetResult = AddMagnetOutcome.Added(id = "0123456789abcdef", state = "fetching_metadata")
            val vm = viewModel(magnet)

            assertEquals(ShareUiState.Sending, vm.uiState.value)
            assertTrue(magnets().isEmpty())

            runCurrent()

            assertEquals(ShareUiState.Done(SendOutcome.Sent(livingRoom.instanceName)), vm.uiState.value)
            assertEquals(listOf(FakeTvApi.Call.AddMagnet(livingRoom.baseUrl, livingRoom.token, magnet)), magnets())
            assertFalse(vm.uiState.value.offersOpenApp)
        }

    @Test
    fun `the send happens without anybody collecting the state`() =
        runTest {
            val vm = viewModel(magnet)

            advanceUntilIdle()

            assertEquals(listOf(FakeTvApi.Call.AddMagnet(livingRoom.baseUrl, livingRoom.token, magnet)), magnets())
            assertEquals(ShareUiState.Done(SendOutcome.Sent(livingRoom.instanceName)), vm.uiState.value)
        }

    @Test
    fun `a second collection shows the outcome again and does not send the magnet again`() =
        runTest {
            val vm = viewModel(magnet)
            backgroundScope.launch { vm.uiState.collect { } }
            runCurrent()
            assertEquals(1, magnets().size)

            val shown = mutableListOf<ShareUiState>()
            backgroundScope.launch { vm.uiState.collect { shown += it } }
            runCurrent()

            assertEquals(1, magnets().size)
            assertEquals(listOf<ShareUiState>(ShareUiState.Done(SendOutcome.Sent(livingRoom.instanceName))), shown)
        }

    @Test
    fun `shared text without a magnet never reaches the TV`() =
        runTest {
            val vm = viewModel("Mira esta peli https://example.com/movie")

            runCurrent()

            assertTrue(magnets().isEmpty())
            assertEquals(ShareUiState.Done(SendOutcome.NoMagnet), vm.uiState.value)
            assertFalse(vm.uiState.value.offersOpenApp)
        }

    @Test
    fun `a share that carried no text at all never reaches the TV`() =
        runTest {
            val vm = viewModel(null)

            runCurrent()

            assertTrue(magnets().isEmpty())
            assertEquals(ShareUiState.Done(SendOutcome.NoMagnet), vm.uiState.value)
        }

    @Test
    fun `a send with no TV stored offers to open the app`() =
        runTest {
            store.clear()
            val vm = viewModel(magnet)

            runCurrent()

            assertTrue(magnets().isEmpty())
            assertEquals(ShareUiState.Done(SendOutcome.NotPaired), vm.uiState.value)
            assertTrue(vm.uiState.value.offersOpenApp)
        }

    @Test
    fun `a token the TV refuses offers to open the app`() =
        runTest {
            api.addMagnetResult = AddMagnetOutcome.Failed(ApiFailure.Unauthorized)
            val vm = viewModel(magnet)

            runCurrent()

            assertEquals(ShareUiState.Done(SendOutcome.NeedsPairing), vm.uiState.value)
            assertTrue(vm.uiState.value.offersOpenApp)
            assertNull(store.pairedTv.value)
        }

    @Test
    fun `a TV that cannot be reached offers nothing to open`() =
        runTest {
            api.addMagnetResult = AddMagnetOutcome.Failed(ApiFailure.Network("connection refused"))
            val vm = viewModel(magnet)

            runCurrent()

            assertEquals(ShareUiState.Done(SendOutcome.Unreachable), vm.uiState.value)
            assertFalse(vm.uiState.value.offersOpenApp)
            assertEquals(livingRoom, store.pairedTv.value)
        }
}
