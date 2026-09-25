package com.teachermovies.mobile.connection

import com.teachermovies.discovery.client.DiscoveredTv
import com.teachermovies.discovery.fake.FakeServiceDiscoverer
import com.teachermovies.mobile.api.ApiFailure
import com.teachermovies.mobile.api.PairOutcome
import com.teachermovies.mobile.api.TvApi
import com.teachermovies.mobile.api.fake.FakeTvApi
import com.teachermovies.mobile.connection.ConnectionUiState.Connected
import com.teachermovies.mobile.connection.ConnectionUiState.Loading
import com.teachermovies.mobile.connection.ConnectionUiState.Pairing
import com.teachermovies.mobile.connection.ConnectionUiState.Searching
import com.teachermovies.mobile.data.PairedTv
import com.teachermovies.mobile.data.PairedTvStore
import com.teachermovies.mobile.data.fake.InMemoryPairedTvStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {
    private val livingRoom = DiscoveredTv("Movie Assistant (Salón)", "192.168.1.20", 8787)
    private val bedroom = DiscoveredTv("Movie Assistant (Dormitorio)", "192.168.1.30", 8787)

    private val discoverer = FakeServiceDiscoverer()
    private val api = FakeTvApi()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(store: PairedTvStore = InMemoryPairedTvStore()) =
        ConnectionViewModel(discoverer, api, store, deviceName = "Pixel 8")

    private fun ConnectionViewModel.pairingWith(tv: DiscoveredTv): ConnectionViewModel {
        selectTv(tv)
        return this
    }

    @Test
    fun `loading until the store answers, then searching`() =
        runTest {
            val answer = CompletableDeferred<PairedTv?>()
            val slowStore =
                object : PairedTvStore by InMemoryPairedTvStore() {
                    override val pairedTv: Flow<PairedTv?> = flow { emit(answer.await()) }
                }
            val vm = viewModel(slowStore)
            assertEquals(Loading, vm.uiState.value)

            answer.complete(null)

            assertEquals(Searching(emptyList()), vm.uiState.value)
        }

    @Test
    fun `searching lists the discovered TVs as they come and go`() =
        runTest {
            val vm = viewModel()

            discoverer.add(livingRoom)
            assertEquals(Searching(listOf(livingRoom)), vm.uiState.value)

            discoverer.add(bedroom)
            assertEquals(Searching(listOf(bedroom, livingRoom)), vm.uiState.value)

            discoverer.remove(bedroom.instanceName)
            assertEquals(Searching(listOf(livingRoom)), vm.uiState.value)
        }

    @Test
    fun `selecting a TV asks for its PIN`() =
        runTest {
            discoverer.add(livingRoom)
            val vm = viewModel()

            vm.selectTv(livingRoom)

            assertEquals(Pairing(livingRoom.instanceName, "http://192.168.1.20:8787"), vm.uiState.value)
        }

    @Test
    fun `a typed host gets the default port`() =
        runTest {
            val vm = viewModel()

            vm.enterAddress(" 192.168.1.50 ")

            assertEquals(Pairing("192.168.1.50:8787", "http://192.168.1.50:8787"), vm.uiState.value)
        }

    @Test
    fun `a typed host and port is used as is`() =
        runTest {
            val vm = viewModel()

            vm.enterAddress("tv.local:9000")

            assertEquals(Pairing("tv.local:9000", "http://tv.local:9000"), vm.uiState.value)
        }

    @Test
    fun `an invalid typed address only sets the address error`() =
        runTest {
            discoverer.add(livingRoom)
            val vm = viewModel()

            for (text in listOf("", "   ", "tv:", "tv:abc", "tv:0", "tv:65536", "tv:1:2", "http://tv", "a b")) {
                vm.enterAddress(text)
                assertEquals(text, Searching(listOf(livingRoom), "Dirección no válida"), vm.uiState.value)
            }

            vm.enterAddress("tv:65535")
            assertEquals(Pairing("tv:65535", "http://tv:65535"), vm.uiState.value)
            assertTrue(api.calls.isEmpty())
        }

    @Test
    fun `a PIN that is not 6 digits never reaches the API`() =
        runTest {
            val vm = viewModel().pairingWith(livingRoom)

            for (pin in listOf("", "12345", "1234567", "12a456", " 123456", "12 456", "١٢٣٤٥٦")) {
                vm.submitPin(pin)
                assertEquals(
                    pin,
                    Pairing(livingRoom.instanceName, livingRoom.baseUrl, error = "El PIN tiene 6 cifras"),
                    vm.uiState.value,
                )
            }
            assertTrue(api.calls.isEmpty())
        }

    @Test
    fun `a paired TV is stored and shown as connected`() =
        runTest {
            val store = InMemoryPairedTvStore()
            api.pairResult = PairOutcome.Paired("secret-token")
            val vm = viewModel(store).pairingWith(livingRoom)

            vm.submitPin("482916")

            val expected = PairedTv(livingRoom.instanceName, livingRoom.baseUrl, "secret-token")
            assertEquals(listOf(FakeTvApi.Call.Pair(livingRoom.baseUrl, "482916", "Pixel 8")), api.calls)
            assertEquals(expected, store.pairedTv.value)
            assertEquals(Connected(expected), vm.uiState.value)
        }

    @Test
    fun `busy while the pair request is in flight`() =
        runTest {
            val answer = CompletableDeferred<PairOutcome>()
            val slowApi =
                object : TvApi by api {
                    override suspend fun pair(
                        baseUrl: String,
                        pin: String,
                        deviceName: String,
                    ): PairOutcome = answer.await()
                }
            val vm = ConnectionViewModel(discoverer, slowApi, InMemoryPairedTvStore(), "Pixel 8")
            vm.selectTv(livingRoom)

            vm.submitPin("482916")
            assertEquals(Pairing(livingRoom.instanceName, livingRoom.baseUrl, busy = true), vm.uiState.value)

            answer.complete(PairOutcome.WrongPin)
            assertEquals(
                Pairing(livingRoom.instanceName, livingRoom.baseUrl, error = "PIN incorrecto"),
                vm.uiState.value,
            )
        }

    @Test
    fun `wrong PIN keeps pairing with its error`() = assertPairError(PairOutcome.WrongPin, "PIN incorrecto")

    @Test
    fun `too many attempts keeps pairing with its error`() =
        assertPairError(PairOutcome.TooManyAttempts, "Demasiados intentos; espera un minuto")

    @Test
    fun `a failed pair keeps pairing with the unreachable error`() =
        assertPairError(PairOutcome.Failed(ApiFailure.Network("timeout")), "No se puede conectar con la TV")

    private fun assertPairError(
        outcome: PairOutcome,
        error: String,
    ) = runTest {
        val store = InMemoryPairedTvStore()
        api.pairResult = outcome
        val vm = viewModel(store).pairingWith(livingRoom)

        vm.submitPin("482916")

        assertEquals(Pairing(livingRoom.instanceName, livingRoom.baseUrl, error = error), vm.uiState.value)
        assertEquals(null, store.pairedTv.value)
    }

    @Test
    fun `a successful retry after an error clears it`() =
        runTest {
            api.pairResult = PairOutcome.WrongPin
            val vm = viewModel().pairingWith(livingRoom)
            vm.submitPin("111111")

            api.pairResult = PairOutcome.Paired("t")
            vm.submitPin("482916")

            assertEquals(Connected(PairedTv(livingRoom.instanceName, livingRoom.baseUrl, "t")), vm.uiState.value)
        }

    @Test
    fun `cancel pairing returns to the current TV list`() =
        runTest {
            val vm = viewModel().pairingWith(livingRoom)
            discoverer.add(livingRoom)
            discoverer.add(bedroom)

            vm.cancelPairing()

            assertEquals(Searching(listOf(bedroom, livingRoom)), vm.uiState.value)
        }

    @Test
    fun `a stored TV starts connected`() =
        runTest {
            val paired = PairedTv(livingRoom.instanceName, livingRoom.baseUrl, "t")

            val vm = viewModel(InMemoryPairedTvStore(paired))

            assertEquals(Connected(paired), vm.uiState.value)
            assertEquals(listOf("_http._tcp"), discoverer.requestedServiceTypes)
        }

    @Test
    fun `a re-resolved address of the paired TV is written back`() =
        runTest {
            val store = InMemoryPairedTvStore(PairedTv(livingRoom.instanceName, livingRoom.baseUrl, "t"))
            val vm = viewModel(store)

            discoverer.add(livingRoom.copy(host = "192.168.1.77"))

            val moved = PairedTv(livingRoom.instanceName, "http://192.168.1.77:8787", "t")
            assertEquals(moved, store.pairedTv.value)
            assertEquals(Connected(moved), vm.uiState.value)
        }

    @Test
    fun `no update when the paired TV resolves to the same address or another TV moves`() =
        runTest {
            val paired = PairedTv(livingRoom.instanceName, livingRoom.baseUrl, "t")
            var updates = 0
            val store =
                object : PairedTvStore by InMemoryPairedTvStore(paired) {
                    override suspend fun updateBaseUrl(baseUrl: String) {
                        updates++
                    }
                }
            val vm = viewModel(store)

            discoverer.add(livingRoom)
            discoverer.add(bedroom)
            discoverer.add(bedroom.copy(host = "192.168.1.31"))

            assertEquals(0, updates)
            assertEquals(Connected(paired), vm.uiState.value)
        }

    @Test
    fun `forgetting the TV clears the store and returns to searching`() =
        runTest {
            val store = InMemoryPairedTvStore(PairedTv(livingRoom.instanceName, livingRoom.baseUrl, "t"))
            discoverer.add(bedroom)
            val vm = viewModel(store)

            vm.forgetTv()

            assertEquals(null, store.pairedTv.first())
            assertEquals(Searching(listOf(bedroom)), vm.uiState.value)
        }
}
