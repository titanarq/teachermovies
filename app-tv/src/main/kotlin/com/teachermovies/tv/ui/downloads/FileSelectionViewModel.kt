package com.teachermovies.tv.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.teachermovies.core.model.TorrentId
import com.teachermovies.torrent.api.EngineError
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.FilePriority
import com.teachermovies.torrent.api.TorrentEngine
import com.teachermovies.torrent.api.TorrentFileInfo
import com.teachermovies.tv.format.Formatters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One file of the torrent in the `Elegir archivos` dialog (#71); [index] addresses it in the engine. */
data class FileSelectionRow(
    val index: Int,
    val path: String,
    val sizeText: String,
    val checked: Boolean,
)

/**
 * What the `Elegir archivos` dialog shows. [loading] until the first `files()` answer; [notReady]
 * while the engine has no metadata yet (`Esperando metadata…`); [error] is the text of a failed
 * load or apply; [applying] while `setFilePriorities` is in flight; [done] once it succeeded, which
 * is the screen's cue to close the dialog.
 */
data class FileSelectionUiState(
    val rows: List<FileSelectionRow> = emptyList(),
    val loading: Boolean = true,
    val notReady: Boolean = false,
    val applying: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
) {
    /** `Aplicar` is offered only once there is a file list and nothing is being sent. */
    val canApply: Boolean get() = rows.isNotEmpty() && !applying && !done
}

/**
 * State holder of the `Elegir archivos` dialog of torrent [id] (#71).
 *
 * Loads [TorrentEngine.files] into [FileSelectionRow]s (`checked = priority != Skip`); [toggle]
 * flips one row locally; [apply] sends every file to [TorrentEngine.setFilePriorities] (checked ->
 * [FilePriority.Normal], unchecked -> [FilePriority.Skip]). While the engine answers
 * [EngineError.NotReady] the state is [FileSelectionUiState.notReady] and the load is retried as
 * soon as the torrent's snapshot reports metadata.
 */
class FileSelectionViewModel(
    private val engine: TorrentEngine,
    private val id: TorrentId,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileSelectionUiState())
    val uiState: StateFlow<FileSelectionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    /** Flips the checkbox of the file at engine index [index]; ignored while applying or unknown. */
    fun toggle(index: Int) {
        _uiState.update { state ->
            if (state.applying || state.done) return@update state
            state.copy(rows = state.rows.map { if (it.index == index) it.copy(checked = !it.checked) else it })
        }
    }

    /** `Aplicar`: sends the whole selection; on success [FileSelectionUiState.done] becomes true. */
    fun apply() {
        val state = _uiState.value
        if (!state.canApply) return
        val priorities = state.rows.associate { it.index to if (it.checked) FilePriority.Normal else FilePriority.Skip }
        _uiState.value = state.copy(applying = true, error = null)
        viewModelScope.launch {
            when (val result = engine.setFilePriorities(id, priorities)) {
                is EngineResult.Ok -> _uiState.update { it.copy(applying = false, done = true) }
                is EngineResult.Failure -> _uiState.update { it.copy(applying = false, error = errorText(result.error)) }
            }
        }
    }

    private suspend fun load() {
        when (val result = engine.files(id)) {
            is EngineResult.Ok ->
                _uiState.update {
                    it.copy(rows = result.value.map { file -> file.toRow() }, loading = false, notReady = false, error = null)
                }
            is EngineResult.Failure ->
                if (result.error == EngineError.NotReady) {
                    _uiState.update { it.copy(loading = false, notReady = true, error = null) }
                    // Retry once the torrent reports metadata (or give up quietly if it is removed).
                    val snapshot = engine.torrents.first { list -> list.none { it.id == id } || list.any { it.id == id && it.hasMetadata } }
                    if (snapshot.any { it.id == id }) load()
                } else {
                    _uiState.update { it.copy(loading = false, notReady = false, error = errorText(result.error)) }
                }
        }
    }

    private fun TorrentFileInfo.toRow(): FileSelectionRow =
        FileSelectionRow(
            index = index,
            path = path,
            sizeText = Formatters.bytes(sizeBytes),
            checked = priority != FilePriority.Skip,
        )

    private fun errorText(error: EngineError): String =
        when (error) {
            EngineError.UnknownTorrent -> "Esta descarga ya no existe"
            EngineError.NotReady -> "Esperando metadata…"
            is EngineError.Io -> "Error: ${error.message}"
            else -> "No se pudo completar la operación"
        }

    /** Builds the ViewModel for torrent [id] from `AppContainer`'s engine (ADR-0003 rule 1). */
    class Factory(
        private val engine: TorrentEngine,
        private val id: TorrentId,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FileSelectionViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            return FileSelectionViewModel(engine, id) as T
        }
    }
}
