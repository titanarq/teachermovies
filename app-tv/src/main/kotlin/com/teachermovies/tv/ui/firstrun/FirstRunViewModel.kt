package com.teachermovies.tv.ui.firstrun

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teachermovies.core.settings.AppSettings
import com.teachermovies.core.settings.SettingsRepository
import com.teachermovies.storage.DownloadLayout
import com.teachermovies.storage.SpaceProvider
import com.teachermovies.storage.StorageVolumeProvider
import com.teachermovies.storage.VolumeInfo
import com.teachermovies.storage.VolumeSelection
import com.teachermovies.storage.VolumeSelector
import com.teachermovies.torrent.api.EngineStatus
import com.teachermovies.torrent.api.TorrentEngine
import com.teachermovies.tv.net.LanAddressResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything the first-run screen shows.
 *
 * [serverUrl] is `http://<lan-ip>:<port>`, the address a phone opens, or null while the TV has no
 * LAN address. [freeBytes] and [downloadFolder] describe the volume downloads go to (the persisted
 * one, or `VolumeSelector`'s fallback while it is missing): its free space and its `Movies`
 * directory, both null when no volume is available at all.
 */
data class FirstRunUiState(
    val serverUrl: String? = null,
    val freeBytes: Long? = null,
    val downloadFolder: String? = null,
    val engineStatus: EngineStatus = EngineStatus.Stopped,
)

/**
 * State holder for the first-run screen: where the HTTP server can be reached, where downloads
 * land and how much room is left there, and whether the torrent engine is running.
 *
 * The LAN address and the volume list are snapshots, not streams, so both are re-read on
 * [refresh] -- which the screen calls each time it is shown. Settings and engine status are
 * streams and update the state on their own.
 */
class FirstRunViewModel(
    private val settings: SettingsRepository,
    private val volumes: StorageVolumeProvider,
    private val space: SpaceProvider,
    private val engine: TorrentEngine,
    private val lan: LanAddressResolver,
) : ViewModel() {

    private val lanAddress = MutableStateFlow(lan.current())
    private val volumeSnapshot = MutableStateFlow(volumes.volumes())

    val uiState: StateFlow<FirstRunUiState> =
        combine(settings.settings, volumeSnapshot, lanAddress, engine.engineStatus) { appSettings, available, ip, status ->
            buildState(appSettings, available, ip, status)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, FirstRunUiState(engineStatus = engine.engineStatus.value))

    /**
     * Whether first-run setup is done, or null until the persisted settings have been read -- so
     * `MainActivity` shows neither the first-run screen nor the shell before it knows which.
     */
    val firstRunCompleted: StateFlow<Boolean?> =
        settings.settings
            .map<AppSettings, Boolean?> { it.firstRunCompleted }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Re-reads the LAN address and the attached volumes. */
    fun refresh() {
        lanAddress.value = lan.current()
        volumeSnapshot.value = volumes.volumes()
    }

    /** Marks first-run setup as done, so the app opens straight on the shell from now on. */
    fun complete() {
        viewModelScope.launch { settings.setFirstRunCompleted(true) }
    }

    private fun buildState(
        appSettings: AppSettings,
        available: List<VolumeInfo>,
        ip: String?,
        status: EngineStatus,
    ): FirstRunUiState {
        val volume =
            when (val selection = VolumeSelector.select(available, appSettings.downloadVolumeId, space)) {
                is VolumeSelection.Selected -> selection.volume
                is VolumeSelection.PersistedMissing -> selection.fallback
                VolumeSelection.NoneAvailable -> null
            }
        return FirstRunUiState(
            serverUrl = ip?.let { "http://$it:${appSettings.httpPort}" },
            freeBytes = volume?.let { space.spaceOf(it.root).freeBytes },
            downloadFolder = volume?.let { DownloadLayout(it.root).moviesDir().path },
            engineStatus = status,
        )
    }

    /** Builds the ViewModel from `AppContainer`'s bindings (ADR-0003 rule 1). */
    class Factory(
        private val settings: SettingsRepository,
        private val volumes: StorageVolumeProvider,
        private val space: SpaceProvider,
        private val engine: TorrentEngine,
        private val lan: LanAddressResolver,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FirstRunViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            return FirstRunViewModel(settings, volumes, space, engine, lan) as T
        }
    }
}
