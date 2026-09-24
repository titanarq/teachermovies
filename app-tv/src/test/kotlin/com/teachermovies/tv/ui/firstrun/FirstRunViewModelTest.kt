package com.teachermovies.tv.ui.firstrun

import com.teachermovies.core.settings.AppSettings
import com.teachermovies.core.settings.SettingsRepository
import com.teachermovies.http.ServerState
import com.teachermovies.storage.SpaceInfo
import com.teachermovies.storage.SpaceProvider
import com.teachermovies.storage.StorageVolumeProvider
import com.teachermovies.storage.VolumeInfo
import com.teachermovies.torrent.api.EngineStatus
import com.teachermovies.torrent.fake.FakeTorrentEngine
import com.teachermovies.tv.net.LanAddressResolver
import com.teachermovies.tv.net.NetIf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunViewModelTest {
    private val internal = volume(id = "primary", primary = true)
    private val usb = volume(id = "USB-1", removable = true)

    private val space =
        FakeSpaceProvider(
            mapOf(
                internal.root to SpaceInfo(freeBytes = 5_000_000_000, totalBytes = 16_000_000_000),
                usb.root to SpaceInfo(freeBytes = 100_000_000_000, totalBytes = 128_000_000_000),
            ),
        )

    private val engine = FakeTorrentEngine()
    private var interfaces = listOf(wlan("192.168.1.50"))
    private val lan = LanAddressResolver(interfaces = { interfaces })

    // Fake `HttpServerController.state` and `PairingManager.currentPin`: the ViewModel only reads them.
    private val serverState = MutableStateFlow<ServerState>(ServerState.Running(8787))
    private var pin = "482916"
    private val pinTicks = MutableSharedFlow<Unit>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialStateShowsServerUrlSpaceFolderAndEngineStatus() {
        val settings = InMemorySettingsRepository(AppSettings(httpPort = 8787, downloadVolumeId = "primary"))
        val viewModel =
            FirstRunViewModel(settings, FakeVolumeProvider(listOf(internal, usb)), space, engine, lan, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        assertEquals(
            FirstRunUiState(
                serverUrl = "http://192.168.1.50:8787",
                freeBytes = 5_000_000_000,
                downloadFolder = File(internal.root, "Movies").path,
                engineStatus = EngineStatus.Stopped,
                pin = "482916",
                serverState = ServerState.Running(8787),
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun serverStateFollowsTheController() {
        val viewModel =
            FirstRunViewModel(
                InMemorySettingsRepository(
                    AppSettings(),
                ),
                FakeVolumeProvider(listOf(internal)),
                space,
                engine,
                lan,
                pin = {
                    pin
                },
                serverState = serverState,
                pinTicks = pinTicks,
            )

        serverState.value = ServerState.Failed(8787, "Address already in use")
        assertEquals(ServerState.Failed(8787, "Address already in use"), viewModel.uiState.value.serverState)

        serverState.value = ServerState.Stopped
        assertEquals(ServerState.Stopped, viewModel.uiState.value.serverState)
    }

    @Test
    fun pinIsReReadOnRefreshServerChangeAndTick() {
        val viewModel =
            FirstRunViewModel(
                InMemorySettingsRepository(
                    AppSettings(),
                ),
                FakeVolumeProvider(listOf(internal)),
                space,
                engine,
                lan,
                pin = {
                    pin
                },
                serverState = serverState,
                pinTicks = pinTicks,
            )
        assertEquals("482916", viewModel.uiState.value.pin)

        pin = "000123"
        viewModel.refresh()
        assertEquals("000123", viewModel.uiState.value.pin)

        pin = "111111"
        serverState.value = ServerState.Running(9000)
        assertEquals("111111", viewModel.uiState.value.pin)

        pin = "222222"
        runBlocking { pinTicks.emit(Unit) }
        assertEquals("222222", viewModel.uiState.value.pin)
    }

    @Test
    fun serverUrlFollowsThePersistedPort() {
        val settings = InMemorySettingsRepository(AppSettings(httpPort = 9000))
        val viewModel =
            FirstRunViewModel(settings, FakeVolumeProvider(listOf(internal)), space, engine, lan, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        assertEquals("http://192.168.1.50:9000", viewModel.uiState.value.serverUrl)
    }

    @Test
    fun noLanAddressMeansNoServerUrl() {
        interfaces = emptyList()
        val viewModel =
            FirstRunViewModel(
                InMemorySettingsRepository(
                    AppSettings(),
                ),
                FakeVolumeProvider(listOf(internal)),
                space,
                engine,
                lan,
                pin = {
                    pin
                },
                serverState = serverState,
                pinTicks = pinTicks,
            )

        assertNull(viewModel.uiState.value.serverUrl)
    }

    @Test
    fun refreshPicksUpANewLanAddressAndVolume() {
        interfaces = emptyList()
        val volumes = FakeVolumeProvider(listOf(internal))
        val viewModel =
            FirstRunViewModel(InMemorySettingsRepository(AppSettings()), volumes, space, engine, lan, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        interfaces = listOf(wlan("192.168.1.50"))
        volumes.current = listOf(internal, usb)
        viewModel.refresh()

        assertEquals("http://192.168.1.50:8787", viewModel.uiState.value.serverUrl)
        // With no persisted choice the largest-free removable volume is the fallback.
        assertEquals(100_000_000_000, viewModel.uiState.value.freeBytes)
        assertEquals(File(usb.root, "Movies").path, viewModel.uiState.value.downloadFolder)
    }

    @Test
    fun missingPersistedVolumeShowsTheFallback() {
        val settings = InMemorySettingsRepository(AppSettings(downloadVolumeId = "USB-1"))
        val viewModel =
            FirstRunViewModel(settings, FakeVolumeProvider(listOf(internal)), space, engine, lan, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        assertEquals(5_000_000_000, viewModel.uiState.value.freeBytes)
        assertEquals(File(internal.root, "Movies").path, viewModel.uiState.value.downloadFolder)
    }

    @Test
    fun noVolumeMeansNoSpaceAndNoFolder() {
        val viewModel =
            FirstRunViewModel(
                InMemorySettingsRepository(
                    AppSettings(),
                ),
                FakeVolumeProvider(emptyList()),
                space,
                engine,
                lan,
                pin = {
                    pin
                },
                serverState = serverState,
                pinTicks = pinTicks,
            )

        assertNull(viewModel.uiState.value.freeBytes)
        assertNull(viewModel.uiState.value.downloadFolder)
    }

    @Test
    fun engineStatusFollowsTheEngine() {
        val viewModel =
            FirstRunViewModel(
                InMemorySettingsRepository(
                    AppSettings(),
                ),
                FakeVolumeProvider(listOf(internal)),
                space,
                engine,
                lan,
                pin = {
                    pin
                },
                serverState = serverState,
                pinTicks = pinTicks,
            )

        engine.setEngineStatus(EngineStatus.Starting)
        assertEquals(EngineStatus.Starting, viewModel.uiState.value.engineStatus)

        engine.setEngineStatus(EngineStatus.Running)
        assertEquals(EngineStatus.Running, viewModel.uiState.value.engineStatus)

        engine.setEngineStatus(EngineStatus.Error)
        assertEquals(EngineStatus.Error, viewModel.uiState.value.engineStatus)
    }

    @Test
    fun firstRunCompletedMirrorsTheSetting() {
        val settings = InMemorySettingsRepository(AppSettings(firstRunCompleted = false))
        val viewModel =
            FirstRunViewModel(settings, FakeVolumeProvider(listOf(internal)), space, engine, lan, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        assertEquals(false, viewModel.firstRunCompleted.value)
    }

    @Test
    fun completePersistsFirstRunCompleted() {
        val settings = InMemorySettingsRepository(AppSettings(firstRunCompleted = false))
        val viewModel =
            FirstRunViewModel(settings, FakeVolumeProvider(listOf(internal)), space, engine, lan, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)
        assertFalse(settings.current.firstRunCompleted)

        viewModel.complete()

        assertTrue(settings.current.firstRunCompleted)
        assertEquals(true, viewModel.firstRunCompleted.value)
    }

    private fun volume(
        id: String,
        removable: Boolean = false,
        primary: Boolean = false,
    ) = VolumeInfo(
        id = id,
        label = id,
        root = File("/volumes/$id"),
        removable = removable,
        primary = primary,
        mounted = true,
    )

    private fun wlan(ip: String) =
        NetIf(name = "wlan0", isUp = true, isLoopback = false, addresses = listOf(InetAddress.getByName(ip)))

    /** Mirrors `DataStoreSettingsRepository`: same defaults, same port validation. */
    private class InMemorySettingsRepository(
        initial: AppSettings,
    ) : SettingsRepository {
        private val state = MutableStateFlow(initial)

        val current: AppSettings get() = state.value

        override val settings: Flow<AppSettings> = state

        override suspend fun setHttpPort(port: Int) {
            require(port in 1024..65535)
            state.update { it.copy(httpPort = port) }
        }

        override suspend fun setDownloadVolumeId(id: String?) {
            state.update { it.copy(downloadVolumeId = id) }
        }

        override suspend fun addAuthTokenHash(hash: String) {
            state.update { it.copy(authTokenHashes = it.authTokenHashes + hash) }
        }

        override suspend fun clearAuthTokenHashes() {
            state.update { it.copy(authTokenHashes = emptySet()) }
        }

        override suspend fun setFirstRunCompleted(done: Boolean) {
            state.update { it.copy(firstRunCompleted = done) }
        }

        override suspend fun setAutostartOnBoot(enabled: Boolean) {
            state.update { it.copy(autostartOnBoot = enabled) }
        }
    }

    private class FakeVolumeProvider(
        var current: List<VolumeInfo>,
    ) : StorageVolumeProvider {
        override fun volumes(): List<VolumeInfo> = current
    }

    private class FakeSpaceProvider(
        private val byRoot: Map<File, SpaceInfo>,
    ) : SpaceProvider {
        override fun spaceOf(root: File): SpaceInfo = byRoot[root] ?: SpaceInfo(freeBytes = 0, totalBytes = 0)
    }
}
