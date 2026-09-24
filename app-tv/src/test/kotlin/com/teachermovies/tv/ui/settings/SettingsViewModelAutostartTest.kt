package com.teachermovies.tv.ui.settings

import com.teachermovies.core.settings.AppSettings
import com.teachermovies.core.settings.fake.InMemorySettingsRepository
import com.teachermovies.http.ServerState
import com.teachermovies.storage.SpaceInfo
import com.teachermovies.storage.SpaceProvider
import com.teachermovies.storage.StorageVolumeProvider
import com.teachermovies.storage.VolumeInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The "Arrancar al encender la TV" switch (#126), over `:core-model`'s in-memory settings. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelAutostartTest {

    private val noVolumes =
        object : StorageVolumeProvider {
            override fun volumes(): List<VolumeInfo> = emptyList()
        }

    private val noSpace =
        object : SpaceProvider {
            override fun spaceOf(root: File): SpaceInfo = SpaceInfo(freeBytes = 0, totalBytes = 0)
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(settings: InMemorySettingsRepository) =
        SettingsViewModel(
            settings = settings,
            volumes = noVolumes,
            space = noSpace,
            pin = { "000000" },
            serverState = MutableStateFlow<ServerState>(ServerState.Stopped),
            pinTicks = MutableSharedFlow(),
        )

    @Test
    fun switchIsOffByDefault() {
        assertFalse(viewModel(InMemorySettingsRepository()).uiState.value.autostartOnBoot)
    }

    @Test
    fun switchReflectsThePersistedSettingAndItsChanges() {
        val settings = InMemorySettingsRepository(AppSettings(autostartOnBoot = true))
        val viewModel = viewModel(settings)

        assertTrue(viewModel.uiState.value.autostartOnBoot)

        runBlocking { settings.setAutostartOnBoot(false) }
        assertFalse(viewModel.uiState.value.autostartOnBoot)
    }

    @Test
    fun togglingTheSwitchWritesTheSetting() {
        val settings = InMemorySettingsRepository()
        val viewModel = viewModel(settings)

        viewModel.setAutostartOnBoot(true)
        assertTrue(settings.current.autostartOnBoot)
        assertTrue(viewModel.uiState.value.autostartOnBoot)

        viewModel.setAutostartOnBoot(false)
        assertFalse(settings.current.autostartOnBoot)
        assertFalse(viewModel.uiState.value.autostartOnBoot)
    }
}
