package com.teachermovies.tv.ui.settings

import com.teachermovies.core.settings.AppSettings
import com.teachermovies.core.settings.SettingsRepository
import com.teachermovies.http.ServerState
import com.teachermovies.storage.SpaceInfo
import com.teachermovies.storage.SpaceProvider
import com.teachermovies.storage.StorageVolumeProvider
import com.teachermovies.storage.VolumeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val internal = volume(id = "primary", label = "Almacenamiento interno", primary = true)
    private val usb = volume(id = "USB-1", label = "USB", removable = true)

    private val space =
        FakeSpaceProvider(
            mapOf(
                internal.root to SpaceInfo(freeBytes = 5_000_000_000, totalBytes = 16_000_000_000),
                usb.root to SpaceInfo(freeBytes = 100_000_000_000, totalBytes = 128_000_000_000),
            ),
        )

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
    fun initialStateShowsPersistedPortVolumesAndSpace() {
        val settings = InMemorySettingsRepository(AppSettings(httpPort = 9000, downloadVolumeId = "primary"))
        val viewModel =
            SettingsViewModel(settings, FakeVolumeProvider(listOf(internal, usb)), space, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        val state = viewModel.uiState.value

        assertEquals(9000, state.httpPort)
        assertEquals(
            listOf(
                VolumeRow("primary", "Almacenamiento interno", 5_000_000_000, 16_000_000_000, removable = false),
                VolumeRow("USB-1", "USB", 100_000_000_000, 128_000_000_000, removable = true),
            ),
            state.volumes,
        )
        assertEquals("primary", state.selectedVolumeId)
        assertFalse(state.volumeMissing)
        assertFalse(state.portError)
        assertEquals("482916", state.pin)
        assertEquals(ServerState.Running(8787), state.serverState)
        assertNull(state.serverUrl)
    }

    @Test
    fun serverUrlAndStateFollowTheirSources() {
        val url = MutableStateFlow<String?>("http://192.168.1.50:8787")
        val viewModel =
            SettingsViewModel(
                InMemorySettingsRepository(AppSettings()),
                FakeVolumeProvider(listOf(internal)),
                space,
                pin = { pin },
                serverState = serverState,
                serverUrl = url,
                pinTicks = pinTicks,
            )
        assertEquals("http://192.168.1.50:8787", viewModel.uiState.value.serverUrl)

        url.value = "http://192.168.1.50:9000"
        serverState.value = ServerState.Failed(9000, "Address already in use")

        assertEquals("http://192.168.1.50:9000", viewModel.uiState.value.serverUrl)
        assertEquals(ServerState.Failed(9000, "Address already in use"), viewModel.uiState.value.serverState)

        serverState.value = ServerState.Stopped
        assertEquals(ServerState.Stopped, viewModel.uiState.value.serverState)
    }

    @Test
    fun pinIsReReadOnRefreshServerChangeAndTick() {
        val viewModel =
            SettingsViewModel(
                InMemorySettingsRepository(AppSettings()),
                FakeVolumeProvider(listOf(internal)),
                space,
                pin = { pin },
                serverState = serverState,
                serverUrl = flowOf(null),
                pinTicks = pinTicks,
            )

        pin = "000123"
        viewModel.refreshVolumes()
        assertEquals("000123", viewModel.uiState.value.pin)

        pin = "111111"
        serverState.value = ServerState.Running(9000)
        assertEquals("111111", viewModel.uiState.value.pin)

        pin = "222222"
        runBlocking { pinTicks.emit(Unit) }
        assertEquals("222222", viewModel.uiState.value.pin)
    }

    @Test
    fun initialStateWithoutPersistedVolumeSelectsTheFallback() {
        val settings = InMemorySettingsRepository(AppSettings())
        val viewModel =
            SettingsViewModel(settings, FakeVolumeProvider(listOf(internal, usb)), space, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        assertEquals("USB-1", viewModel.uiState.value.selectedVolumeId)
        assertFalse(viewModel.uiState.value.volumeMissing)
    }

    @Test
    fun validPortIsSaved() {
        val settings = InMemorySettingsRepository(AppSettings())
        val viewModel =
            SettingsViewModel(settings, FakeVolumeProvider(listOf(internal)), space, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        viewModel.changePort(8080)

        assertEquals(8080, settings.current.httpPort)
        assertEquals(8080, viewModel.uiState.value.httpPort)
        assertFalse(viewModel.uiState.value.portError)
    }

    @Test
    fun portOutsideRangeIsNotSavedAndFlagsError() {
        val settings = InMemorySettingsRepository(AppSettings(httpPort = 8787))
        val viewModel =
            SettingsViewModel(settings, FakeVolumeProvider(listOf(internal)), space, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        listOf(0, 80, 1023, 65536).forEach { port ->
            viewModel.changePort(port)

            assertEquals(8787, settings.current.httpPort)
            assertEquals(8787, viewModel.uiState.value.httpPort)
            assertTrue("port $port", viewModel.uiState.value.portError)
        }
        assertEquals(0, settings.setHttpPortCalls)
    }

    @Test
    fun rangeBoundsAreValid() {
        val settings = InMemorySettingsRepository(AppSettings())
        val viewModel =
            SettingsViewModel(settings, FakeVolumeProvider(listOf(internal)), space, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        viewModel.changePort(1024)
        assertEquals(1024, settings.current.httpPort)
        viewModel.changePort(65535)
        assertEquals(65535, settings.current.httpPort)
        assertFalse(viewModel.uiState.value.portError)
    }

    @Test
    fun validPortAfterInvalidOneClearsError() {
        val settings = InMemorySettingsRepository(AppSettings())
        val viewModel =
            SettingsViewModel(settings, FakeVolumeProvider(listOf(internal)), space, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        viewModel.changePort(70000)
        assertTrue(viewModel.uiState.value.portError)

        viewModel.changePort(9090)

        assertFalse(viewModel.uiState.value.portError)
        assertEquals(9090, viewModel.uiState.value.httpPort)
    }

    @Test
    fun selectingVolumePersistsIt() {
        val settings = InMemorySettingsRepository(AppSettings(downloadVolumeId = "primary"))
        val viewModel =
            SettingsViewModel(settings, FakeVolumeProvider(listOf(internal, usb)), space, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        viewModel.selectVolume("USB-1")

        assertEquals("USB-1", settings.current.downloadVolumeId)
        assertEquals("USB-1", viewModel.uiState.value.selectedVolumeId)
        assertFalse(viewModel.uiState.value.volumeMissing)
    }

    @Test
    fun selectingUnknownVolumeIsIgnored() {
        val settings = InMemorySettingsRepository(AppSettings(downloadVolumeId = "primary"))
        val viewModel =
            SettingsViewModel(settings, FakeVolumeProvider(listOf(internal, usb)), space, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        viewModel.selectVolume("gone")

        assertEquals("primary", settings.current.downloadVolumeId)
    }

    @Test
    fun missingPersistedVolumeSetsFlagAndSelectsFallback() {
        val settings = InMemorySettingsRepository(AppSettings(downloadVolumeId = "USB-1"))
        val viewModel =
            SettingsViewModel(settings, FakeVolumeProvider(listOf(internal)), space, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        val state = viewModel.uiState.value

        assertTrue(state.volumeMissing)
        assertEquals("primary", state.selectedVolumeId)
        assertEquals(listOf("primary"), state.volumes.map { it.id })
        // Showing the fallback does not overwrite the user's choice.
        assertEquals("USB-1", settings.current.downloadVolumeId)
    }

    @Test
    fun unmountedPersistedVolumeIsMissingAndNotListed() {
        val settings = InMemorySettingsRepository(AppSettings(downloadVolumeId = "USB-1"))
        val volumes = FakeVolumeProvider(listOf(internal, usb.copy(mounted = false)))
        val viewModel =
            SettingsViewModel(settings, volumes, space, pin = { pin }, serverState = serverState, pinTicks = pinTicks)

        assertTrue(viewModel.uiState.value.volumeMissing)
        assertEquals(
            listOf("primary"),
            viewModel.uiState.value.volumes
                .map { it.id },
        )
    }

    @Test
    fun choosingAnotherVolumeClearsMissingFlag() {
        val settings = InMemorySettingsRepository(AppSettings(downloadVolumeId = "USB-1"))
        val viewModel =
            SettingsViewModel(settings, FakeVolumeProvider(listOf(internal)), space, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        viewModel.selectVolume("primary")

        assertFalse(viewModel.uiState.value.volumeMissing)
        assertEquals("primary", viewModel.uiState.value.selectedVolumeId)
    }

    @Test
    fun refreshPicksUpReattachedVolume() {
        val settings = InMemorySettingsRepository(AppSettings(downloadVolumeId = "USB-1"))
        val volumes = FakeVolumeProvider(listOf(internal))
        val viewModel =
            SettingsViewModel(settings, volumes, space, pin = { pin }, serverState = serverState, pinTicks = pinTicks)
        assertTrue(viewModel.uiState.value.volumeMissing)

        volumes.current = listOf(internal, usb)
        viewModel.refreshVolumes()

        assertFalse(viewModel.uiState.value.volumeMissing)
        assertEquals("USB-1", viewModel.uiState.value.selectedVolumeId)
    }

    @Test
    fun noVolumesAtAll() {
        val settings = InMemorySettingsRepository(AppSettings())
        val viewModel =
            SettingsViewModel(settings, FakeVolumeProvider(emptyList()), space, pin = {
                pin
            }, serverState = serverState, pinTicks = pinTicks)

        assertEquals(emptyList<VolumeRow>(), viewModel.uiState.value.volumes)
        assertNull(viewModel.uiState.value.selectedVolumeId)
        assertFalse(viewModel.uiState.value.volumeMissing)
    }

    private fun volume(
        id: String,
        label: String,
        removable: Boolean = false,
        primary: Boolean = false,
    ) = VolumeInfo(
        id = id,
        label = label,
        root = File("/volumes/$id"),
        removable = removable,
        primary = primary,
        mounted = true,
    )

    /** Mirrors `DataStoreSettingsRepository`: same defaults, same port validation. */
    private class InMemorySettingsRepository(
        initial: AppSettings,
    ) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        var setHttpPortCalls = 0
            private set

        val current: AppSettings get() = state.value

        override val settings: Flow<AppSettings> = state

        override suspend fun setHttpPort(port: Int) {
            setHttpPortCalls++
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
        override fun spaceOf(root: File): SpaceInfo = byRoot[root] ?: SpaceInfo(0, 0)
    }
}
