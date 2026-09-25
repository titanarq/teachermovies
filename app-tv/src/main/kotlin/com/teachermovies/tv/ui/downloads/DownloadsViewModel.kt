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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One row of the Descargas table, fully formatted (AGENTS.md: no formatting logic in Compose).
 *
 * [canPause] and [canResume] mirror [DownloadState.canTransitionTo]: pausing is offered whenever
 * the state machine allows moving to [DownloadState.Paused] (which includes a completed torrent,
 * so seeding can be stopped) and the torrent is not paused already; resuming is offered only while
 * paused. [progress] (0..1) only sizes the progress bar; the figure shown is [percent].
 * [canPlay] (#226): the row is downloading or paused and its main (movie) file is known, so it can
 * be played while it downloads.
 */
data class DownloadRow(
    val id: TorrentId,
    val name: String,
    val state: DownloadState,
    val percent: String,
    val progress: Float,
    val sizeText: String,
    val speed: String,
    val peers: Int,
    val eta: String,
    val ratio: String,
    val canPause: Boolean,
    val canResume: Boolean,
    val canPlay: Boolean = false,
)

/** What the action dialog of a row (#70) offers, in the order it lists them. */
enum class DownloadAction {
    /** `Reproducir` (#226): plays the download while it is still in progress; only on a [DownloadRow.canPlay] row. */
    Play,

    /** `Pausar` or `Reanudar`, depending on the row -- see [DownloadsDialog.Actions.pauseResumeLabel]. */
    PauseResume,
    ChooseFiles,
    Delete,
    Cancel,
}

/** One entry of the action dialog; a disabled one is shown but cannot be focused or clicked. */
data class DownloadActionItem(
    val action: DownloadAction,
    val enabled: Boolean,
)

/** The dialog open over the Descargas list, always about [DownloadsUiState.selectedRowId]. */
sealed interface DownloadsDialog {
    /**
     * OK on a row: `Reproducir` (only on a [DownloadRow.canPlay] row, #226), `Pausar`/`Reanudar`,
     * `Elegir archivos`, `Borrar`, `Cancelar`. [focused] is the first enabled item, which the
     * dialog focuses when it opens.
     */
    data class Actions(
        val items: List<DownloadActionItem>,
        val pauseResumeLabel: String,
    ) : DownloadsDialog {
        val focused: DownloadAction = items.first { it.enabled }.action
    }

    /** `Borrar` -> `¿Borrar también los archivos?` (Sí / No / Cancelar). */
    data object ConfirmDelete : DownloadsDialog

    /** `Elegir archivos`; its content is #71. */
    data object ChooseFiles : DownloadsDialog
}

/**
 * Everything the Descargas screen shows: one row per torrent the engine knows about, free space,
 * the address the empty state points the phone to ([serverUrl], null while unknown), and the open
 * dialog, if any, with the row it is about ([selectedRowId]). [selectedRowId] outlives the dialog:
 * it is the row focus returns to when the dialog closes.
 */
data class DownloadsUiState(
    val rows: List<DownloadRow> = emptyList(),
    val freeSpace: String? = null,
    val serverUrl: String? = null,
    val selectedRowId: TorrentId? = null,
    val dialog: DownloadsDialog? = null,
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
 *
 * The screen's dialogs (#70) are state here too: [openActions] on OK, [onAction] for the action
 * dialog, [confirmDelete] for `¿Borrar también los archivos?`, [dismissDialog] for Cancelar/BACK.
 * [serverUrl] (`http://ip:port`) feeds the empty-state hint. `Reproducir` (#226) closes the dialog
 * and emits the row's id on [playRequests], which the screen turns into opening the player.
 */
class DownloadsViewModel(
    private val engine: TorrentEngine,
    private val sync: EngineRepositorySync,
    serverUrl: Flow<String?> = flowOf(null),
    private val space: () -> SpaceInfo?,
) : ViewModel() {
    /** Which row the dialog is about and which dialog is open; rows themselves come from the engine. */
    private data class DialogState(
        val selectedRowId: TorrentId? = null,
        val kind: DialogKind? = null,
    )

    private enum class DialogKind { Actions, ConfirmDelete, ChooseFiles }

    private val dialogState = MutableStateFlow(DialogState())

    private val playChannel = Channel<TorrentId>(Channel.BUFFERED)

    /** Ids the viewer asked to play from the action dialog (#226); each is delivered once. */
    val playRequests: Flow<TorrentId> = playChannel.receiveAsFlow()

    private val listState =
        engine.torrents.map { snapshots ->
            DownloadsUiState(
                rows = snapshots.map { it.toRow() },
                freeSpace = space()?.let { Formatters.bytes(it.freeBytes) },
            )
        }

    val uiState: StateFlow<DownloadsUiState> =
        combine(listState, serverUrl, dialogState) { list, url, dialog ->
            // The dialog is rebuilt from the row as it is now, so a torrent that finishes or is
            // paused from the phone while the dialog is open updates its actions; a row that
            // disappeared (removed from the phone) closes it.
            val row = list.rows.firstOrNull { it.id == dialog.selectedRowId }
            list.copy(
                serverUrl = url,
                selectedRowId = dialog.selectedRowId,
                dialog = row?.let { dialog.kind?.toDialog(it) },
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadsUiState())

    /** OK on row [id]: opens its action dialog. */
    fun openActions(id: TorrentId) {
        dialogState.value = DialogState(selectedRowId = id, kind = DialogKind.Actions)
    }

    /**
     * An item of the action dialog was clicked. A disabled one (the screen never lets it be
     * clicked) is ignored.
     */
    fun onAction(action: DownloadAction) {
        val state = uiState.value
        val dialog = state.dialog as? DownloadsDialog.Actions ?: return
        val id = state.selectedRowId ?: return
        if (dialog.items.none { it.action == action && it.enabled }) return
        val row = state.rows.first { it.id == id }
        when (action) {
            DownloadAction.Play -> {
                dismissDialog()
                playChannel.trySend(id)
            }

            DownloadAction.PauseResume -> {
                if (row.canResume) resume(id) else pause(id)
                dismissDialog()
            }

            DownloadAction.ChooseFiles -> {
                dialogState.value = dialogState.value.copy(kind = DialogKind.ChooseFiles)
            }

            DownloadAction.Delete -> {
                dialogState.value = dialogState.value.copy(kind = DialogKind.ConfirmDelete)
            }

            DownloadAction.Cancel -> {
                dismissDialog()
            }
        }
    }

    /** `Sí` ([deleteFiles] true) or `No` (false) in the delete confirmation; `Cancelar` is [dismissDialog]. */
    fun confirmDelete(deleteFiles: Boolean) {
        val state = uiState.value
        if (state.dialog != DownloadsDialog.ConfirmDelete) return
        val id = state.selectedRowId ?: return
        dismissDialog()
        remove(id, deleteFiles)
    }

    /** Closes whatever dialog is open (`Cancelar`, BACK); [DownloadsUiState.selectedRowId] is kept. */
    fun dismissDialog() {
        dialogState.value = dialogState.value.copy(kind = null)
    }

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

    private fun DialogKind.toDialog(row: DownloadRow): DownloadsDialog =
        when (this) {
            DialogKind.Actions -> actionsFor(row)
            DialogKind.ConfirmDelete -> DownloadsDialog.ConfirmDelete
            DialogKind.ChooseFiles -> DownloadsDialog.ChooseFiles
        }

    /**
     * `Pausar`/`Reanudar` is enabled only while the row allows it (an Error row offers `Pausar`);
     * `Elegir archivos` once the file list is known, i.e. not while fetching metadata.
     */
    private fun actionsFor(row: DownloadRow): DownloadsDialog.Actions =
        DownloadsDialog.Actions(
            items =
                listOfNotNull(
                    DownloadActionItem(DownloadAction.Play, enabled = true).takeIf { row.canPlay },
                    DownloadActionItem(DownloadAction.PauseResume, enabled = row.canPause || row.canResume),
                    DownloadActionItem(
                        DownloadAction.ChooseFiles,
                        enabled = row.state != DownloadState.FetchingMetadata,
                    ),
                    DownloadActionItem(DownloadAction.Delete, enabled = true),
                    DownloadActionItem(DownloadAction.Cancel, enabled = true),
                ),
            pauseResumeLabel = if (row.canResume) "Reanudar" else "Pausar",
        )

    private fun TorrentSnapshot.toRow(): DownloadRow =
        DownloadRow(
            id = id,
            name = name,
            state = state,
            percent = Formatters.percent(progressPercent),
            progress = (progressPercent / 100.0).coerceIn(0.0, 1.0).toFloat(),
            sizeText = Formatters.sizeText(downloadedBytes, totalBytes),
            speed = Formatters.speed(downloadRateBps),
            peers = peers,
            eta = Formatters.eta(etaSeconds),
            ratio = Formatters.ratio(ratio),
            canPause = state != DownloadState.Paused && state.canTransitionTo(DownloadState.Paused),
            canResume = state == DownloadState.Paused,
            canPlay = mainFileIndex != null && state in PLAYABLE_WHILE_DOWNLOADING,
        )

    /** Builds the ViewModel from `AppContainer`'s bindings (ADR-0003 rule 1). */
    class Factory(
        private val engine: TorrentEngine,
        private val sync: EngineRepositorySync,
        private val serverUrl: Flow<String?>,
        private val space: () -> SpaceInfo?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DownloadsViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            return DownloadsViewModel(engine, sync, serverUrl, space) as T
        }
    }

    private companion object {
        /** States in which `Reproducir` streams the download (#226); a completed one plays from Biblioteca. */
        val PLAYABLE_WHILE_DOWNLOADING = setOf(DownloadState.Downloading, DownloadState.Paused)
    }
}
