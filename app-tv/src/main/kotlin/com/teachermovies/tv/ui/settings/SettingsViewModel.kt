package com.teachermovies.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teachermovies.core.settings.AppSettings
import com.teachermovies.core.settings.SettingsRepository
import com.teachermovies.http.ServerState
import com.teachermovies.storage.SpaceProvider
import com.teachermovies.storage.StorageVolumeProvider
import com.teachermovies.storage.VolumeInfo
import com.teachermovies.storage.VolumeSelection
import com.teachermovies.storage.VolumeSelector
import com.teachermovies.tv.ui.server.pinRefreshTicker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The ports `SettingsRepository.setHttpPort` accepts: unprivileged, and still a port. */
val VALID_HTTP_PORTS: IntRange = 1024..65535

/** One volume the user can pick as the download destination, with its space right now. */
data class VolumeRow(
    val id: String,
    val label: String,
    val freeBytes: Long,
    val totalBytes: Long,
    val removable: Boolean,
)

/**
 * Everything the Configuración screen shows.
 *
 * [volumes] holds only mounted volumes, the ones a download can go to. [selectedVolumeId] is the
 * volume downloads actually go to -- the persisted choice, or `VolumeSelector`'s fallback while the
 * persisted one is missing -- and null when there is none. [volumeMissing] is true while the
 * persisted volume is not mounted (a USB drive unplugged). [portError] is true after the last
 * [SettingsViewModel.changePort] was rejected, until a valid one succeeds. [serverUrl] is
 * `http://<lan-ip>:<port>` (null without a LAN address), [pin] the pairing PIN and [serverState]
 * the embedded HTTP server's state (#66). [autostartOnBoot] mirrors the persisted "Arrancar al
 * encender la TV" switch (#126), off by default. [bridgeConnected] is whether a laptop bridge holds
 * the job stream open right now (#275), [bridgePaired] whether any bridge token is stored (#270)
 * and [bridgeName] the name the paired bridge gave, null when it gave none (#289). The Anthropic
 * API-key field this screen used to offer is gone (ADR-0005 §9).
 */
data class SettingsUiState(
    val httpPort: Int = AppSettings().httpPort,
    val volumes: List<VolumeRow> = emptyList(),
    val selectedVolumeId: String? = null,
    val volumeMissing: Boolean = false,
    val portError: Boolean = false,
    val serverUrl: String? = null,
    val pin: String = "",
    val serverState: ServerState = ServerState.Stopped,
    val autostartOnBoot: Boolean = AppSettings().autostartOnBoot,
    val bridgeConnected: Boolean = false,
    val bridgePaired: Boolean = false,
    val bridgeName: String? = null,
) {
    override fun toString(): String =
        "SettingsUiState(httpPort=$httpPort, volumes=$volumes, selectedVolumeId=$selectedVolumeId, " +
            "volumeMissing=$volumeMissing, portError=$portError, serverUrl=$serverUrl, pin=<redacted>, " +
            "serverState=$serverState, autostartOnBoot=$autostartOnBoot, " +
            "bridgeConnected=$bridgeConnected, bridgePaired=$bridgePaired, bridgeName=$bridgeName)"
}

/**
 * State holder for the Configuración screen: the HTTP port and the download volume, both persisted
 * through [SettingsRepository], with the volume picked by [VolumeSelector] over what [volumes]
 * currently reports.
 *
 * [StorageVolumeProvider] is a snapshot, not a stream, so the list is re-read on every settings
 * change and on [refreshVolumes] -- which the screen calls each time it is shown, so a USB drive
 * plugged in meanwhile appears. A port change here is persisted only; `HttpServerController`
 * follows the setting and restarts the server on it (#65), which [serverState] then reports.
 *
 * [pin] is read on creation, on [refreshVolumes] (each time the screen is shown), whenever
 * [serverState] changes and on every `pinTicks` emission.
 *
 * [bridgeConnected] is the bridge job hub's connection state; [disconnectBridge] ends its open
 * stream, and [forgetBridge] calls it after forgetting the bridge tokens.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val volumes: StorageVolumeProvider,
    private val space: SpaceProvider,
    private val pin: () -> String,
    private val serverState: StateFlow<ServerState>,
    serverUrl: Flow<String?> = flowOf(null),
    pinTicks: Flow<Unit> = pinRefreshTicker(),
    bridgeConnected: Flow<Boolean> = flowOf(false),
    private val disconnectBridge: () -> Unit = {},
) : ViewModel() {
    private val portError = MutableStateFlow(false)
    private val volumeSnapshot = MutableStateFlow(volumes.volumes())
    private val currentPin = MutableStateFlow(pin())

    private val server = combine(serverUrl, currentPin, serverState) { url, p, state -> Triple(url, p, state) }

    val uiState: StateFlow<SettingsUiState> =
        combine(
            settings.settings,
            volumeSnapshot,
            portError,
            server,
            bridgeConnected,
        ) { appSettings, available, error, (url, p, state), connected ->
            buildState(appSettings, available, error)
                .copy(serverUrl = url, pin = p, serverState = state, bridgeConnected = connected)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SettingsUiState(pin = currentPin.value, serverState = serverState.value),
        )

    init {
        serverState.onEach { currentPin.value = pin() }.launchIn(viewModelScope)
        pinTicks.onEach { currentPin.value = pin() }.launchIn(viewModelScope)
    }

    /**
     * Persists [port] if it is in [VALID_HTTP_PORTS] and clears `portError`; otherwise saves
     * nothing and sets `portError`, leaving the persisted port as it was.
     */
    fun changePort(port: Int) {
        if (port !in VALID_HTTP_PORTS) {
            portError.value = true
            return
        }
        viewModelScope.launch {
            settings.setHttpPort(port)
            portError.value = false
        }
    }

    /**
     * Persists [id] as the download volume. An id that names no mounted volume is ignored: the
     * list only ever offers mounted ones, so such an id can only come from a stale row.
     */
    fun selectVolume(id: String) {
        if (volumeSnapshot.value.none { it.id == id && it.mounted }) return
        viewModelScope.launch { settings.setDownloadVolumeId(id) }
    }

    /** Persists whether the app starts its services by itself when the TV boots (#126). */
    fun setAutostartOnBoot(enabled: Boolean) {
        viewModelScope.launch { settings.setAutostartOnBoot(enabled) }
    }

    /**
     * "Olvidar portátil" (#289, ADR-0005 §4): forgets every bridge token and the bridge's name --
     * the phones stay paired -- and only then ends the open bridge stream, so the laptop's
     * reconnect is refused rather than let back in on a token that is still stored.
     */
    fun forgetBridge() {
        viewModelScope.launch {
            settings.clearBridgeTokenHashes()
            disconnectBridge()
        }
    }

    /** Re-reads the attached volumes and their free space, and the PIN. */
    fun refreshVolumes() {
        currentPin.value = pin()
        volumeSnapshot.update { volumes.volumes() }
    }

    private fun buildState(
        appSettings: AppSettings,
        available: List<VolumeInfo>,
        error: Boolean,
    ): SettingsUiState {
        val selection = VolumeSelector.select(available, appSettings.downloadVolumeId, space)
        val selectedId =
            when (selection) {
                is VolumeSelection.Selected -> selection.volume.id
                is VolumeSelection.PersistedMissing -> selection.fallback?.id
                VolumeSelection.NoneAvailable -> null
            }
        return SettingsUiState(
            httpPort = appSettings.httpPort,
            volumes = available.filter { it.mounted }.map { it.toRow() },
            selectedVolumeId = selectedId,
            volumeMissing = selection is VolumeSelection.PersistedMissing,
            portError = error,
            autostartOnBoot = appSettings.autostartOnBoot,
            bridgePaired = appSettings.bridgeTokenHashes.isNotEmpty(),
            bridgeName = appSettings.bridgeDeviceName,
        )
    }

    private fun VolumeInfo.toRow(): VolumeRow {
        val spaceInfo = space.spaceOf(root)
        return VolumeRow(
            id = id,
            label = label,
            freeBytes = spaceInfo.freeBytes,
            totalBytes = spaceInfo.totalBytes,
            removable = removable,
        )
    }

    /** Builds the ViewModel from `AppContainer`'s bindings (ADR-0003 rule 1). */
    class Factory(
        private val settings: SettingsRepository,
        private val volumes: StorageVolumeProvider,
        private val space: SpaceProvider,
        private val pin: () -> String,
        private val serverState: StateFlow<ServerState>,
        private val serverUrl: Flow<String?>,
        private val bridgeConnected: Flow<Boolean>,
        private val disconnectBridge: () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            return SettingsViewModel(
                settings = settings,
                volumes = volumes,
                space = space,
                pin = pin,
                serverState = serverState,
                serverUrl = serverUrl,
                bridgeConnected = bridgeConnected,
                disconnectBridge = disconnectBridge,
            ) as T
        }
    }
}
