package com.teachermovies.tv.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.storage.SpaceInfo
import com.teachermovies.torrent.api.TorrentEngine
import com.teachermovies.torrent.api.TorrentSnapshot
import com.teachermovies.torrent.sync.EngineRepositorySync
import com.teachermovies.tv.format.Formatters
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One row of the Descargas table, fully formatted (AGENTS.md: no formatting logic in Compose).
 *
 * [canPause] and [canResume] mirror [DownloadState.canTransitionTo]: pausing is offered whenever
 * the state machine allows moving to [DownloadState.Paused] (which includes a completed torrent,
 * so seeding can be stopped) and the torrent is not paused already; resuming is offered only while
 * paused.
 */
data class DownloadRow(
    val id: TorrentId,
    val name: String,
    val state: DownloadState,
    val percent: String,
    val sizeText: String,
    val speed: String,
    val peers: Int,
    val eta: String,
    val ratio: String,
    val canPause: Boolean,
    val canResume: Boolean,
)

/** Everything the Descargas screen shows: one row per torrent the engine knows about, plus free space. */
data class DownloadsUiState(
    val rows: List<DownloadRow> = emptyList(),
    val freeSpace: String? = null,
)

/**
 * State holder for the Descargas screen (#69).
 *
 * [TorrentEngine.torrents] is mapped straight into formatted [DownloadRow]s; [space] is a snapshot,
 * not a stream (like `SpaceProvider` elsewhere), so it is re-read on every torrents update -- which
 * happens often enough while anything is downloading that the free-space figure stays current
 * without a dedicated refresh.
 *
 * [pause] and [resume] go straight to [engine]. [remove] goes through [sync] instead, so the
 * persisted row (`TorrentRepository`, #68) is deleted only once the engine confirms the torrent is
 * actually gone.
 */
class DownloadsViewModel(
    private val engine: TorrentEngine,
    private val sync: EngineRepositorySync,
    private val space: () -> SpaceInfo?,
) : ViewModel() {

    val uiState: StateFlow<DownloadsUiState> =
        engine.torrents
            .map { snapshots ->
                DownloadsUiState(
                    rows = snapshots.map { it.toRow() },
                    freeSpace = space()?.let { Formatters.bytes(it.freeBytes) },
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadsUiState())

    /** Pauses torrent [id]. The engine result is not surfaced; a failure leaves the row unchanged. */
    fun pause(id: TorrentId) {
        viewModelScope.launch { engine.pause(id) }
    }

    /** Resumes torrent [id]. The engine result is not surfaced; a failure leaves the row unchanged. */
    fun resume(id: TorrentId) {
        viewModelScope.launch { engine.resume(id) }
    }

    /** Removes torrent [id] via [EngineRepositorySync.remove], deleting [deleteFiles] as well when true. */
    fun remove(
        id: TorrentId,
        deleteFiles: Boolean,
    ) {
        viewModelScope.launch { sync.remove(id, deleteFiles) }
    }

    private fun TorrentSnapshot.toRow(): DownloadRow =
        DownloadRow(
            id = id,
            name = name,
            state = state,
            percent = Formatters.percent(progressPercent),
            sizeText = Formatters.sizeText(downloadedBytes, totalBytes),
            speed = Formatters.speed(downloadRateBps),
            peers = peers,
            eta = Formatters.eta(etaSeconds),
            ratio = Formatters.ratio(ratio),
            canPause = state != DownloadState.Paused && state.canTransitionTo(DownloadState.Paused),
            canResume = state == DownloadState.Paused,
        )

    /** Builds the ViewModel from `AppContainer`'s bindings (ADR-0003 rule 1). */
    class Factory(
        private val engine: TorrentEngine,
        private val sync: EngineRepositorySync,
        private val space: () -> SpaceInfo?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DownloadsViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            return DownloadsViewModel(engine, sync, space) as T
        }
    }
}
