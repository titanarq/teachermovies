package com.teachermovies.tv.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.teachermovies.core.model.DownloadState
import com.teachermovies.core.model.TorrentId
import com.teachermovies.tv.R
import com.teachermovies.tv.format.Formatters

/**
 * The Descargas section bound to its [DownloadsViewModel] (#70); `Elegir archivos` opens a
 * [FileSelectionRoute] whose ViewModel [fileSelectionFactory] builds for the selected torrent (#71).
 */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    fileSelectionFactory: (TorrentId) -> ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DownloadsContent(
        uiState = uiState,
        onOpenActions = viewModel::openActions,
        onAction = viewModel::onAction,
        onConfirmDelete = viewModel::confirmDelete,
        onDismissDialog = viewModel::dismissDialog,
        chooseFilesContent = { id, onClose ->
            key(id) { FileSelectionRoute(factory = remember { fileSelectionFactory(id) }, onClose = onClose) }
        },
        modifier = modifier,
    )
}

/**
 * `Espacio libre: X` over a vertical list with one row per torrent, or the empty-state hint.
 *
 * Focus: DOWN from the tab row enters on the first row (then on whichever row last had focus);
 * UP/DOWN walk the rows and UP from the first reaches the tab row; OK opens the row's action
 * dialog, whose first enabled action has focus. Closing a dialog leaves focus on the row it was
 * about; if that row is gone (deleted), focus moves to the row now in its place. BACK closes a
 * dialog; outside one it is the shell's (back to the tab row).
 */
@Composable
fun DownloadsContent(
    uiState: DownloadsUiState,
    onOpenActions: (TorrentId) -> Unit,
    onAction: (DownloadAction) -> Unit,
    onConfirmDelete: (Boolean) -> Unit,
    onDismissDialog: () -> Unit,
    chooseFilesContent: @Composable (id: TorrentId, onClose: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowRequesters = remember { mutableMapOf<TorrentId, FocusRequester>() }
    fun requesterFor(id: TorrentId): FocusRequester = rowRequesters.getOrPut(id) { FocusRequester() }
    val listState = rememberLazyListState()
    // The row that has focus and where it sat, so focus can land on a neighbour when it disappears.
    var focusedRowId by remember { mutableStateOf<TorrentId?>(null) }
    var focusedIndex by remember { mutableStateOf(0) }

    Column(
        modifier = modifier.padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text =
                uiState.freeSpace?.let { stringResource(R.string.downloads_free_space, it) }
                    ?: stringResource(R.string.downloads_free_space_unknown),
            style = MaterialTheme.typography.titleMedium,
        )

        if (uiState.rows.isEmpty()) {
            Text(
                text =
                    stringResource(
                        R.string.downloads_empty,
                        uiState.serverUrl ?: stringResource(R.string.downloads_empty_no_address),
                    ),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().focusRestorer(requesterFor(uiState.rows.first().id)).focusGroup(),
            ) {
                itemsIndexed(uiState.rows, key = { _, row -> row.id.value }) { index, row ->
                    DownloadRowItem(
                        row = row,
                        onClick = { onOpenActions(row.id) },
                        modifier =
                            Modifier
                                .focusRequester(requesterFor(row.id))
                                .onFocusChanged { state ->
                                    if (state.isFocused) {
                                        focusedRowId = row.id
                                        focusedIndex = index
                                    } else if (focusedRowId == row.id) {
                                        focusedRowId = null
                                    }
                                },
                    )
                }
            }
        }
    }

    when (val dialog = uiState.dialog) {
        null -> Unit
        is DownloadsDialog.Actions ->
            ActionsDialog(
                title = uiState.rows.firstOrNull { it.id == uiState.selectedRowId }?.name.orEmpty(),
                dialog = dialog,
                onAction = onAction,
                onDismiss = onDismissDialog,
            )
        DownloadsDialog.ConfirmDelete ->
            ConfirmDeleteDialog(
                title = uiState.rows.firstOrNull { it.id == uiState.selectedRowId }?.name.orEmpty(),
                onConfirm = onConfirmDelete,
                onDismiss = onDismissDialog,
            )
        DownloadsDialog.ChooseFiles -> uiState.selectedRowId?.let { chooseFilesContent(it, onDismissDialog) }
    }

    // Focus must never be left on nothing: a focused row that disappears (deleted here or from the
    // phone) hands it to the row now in its place, and a closing dialog hands it back to its row
    // in case the dialog window took it away.
    val rowIds = uiState.rows.map { it.id }
    val dialogOpen = uiState.dialog != null
    var restoreAfterDialog by remember { mutableStateOf(false) }
    LaunchedEffect(rowIds, dialogOpen) {
        rowRequesters.keys.retainAll(rowIds.toSet())
        if (dialogOpen) {
            restoreAfterDialog = true
            return@LaunchedEffect
        }
        val focused = focusedRowId
        val needsFocus = (focused != null && focused !in rowIds) || (focused == null && restoreAfterDialog)
        restoreAfterDialog = false
        if (!needsFocus || rowIds.isEmpty()) return@LaunchedEffect
        val selectedIndex = rowIds.indexOf(uiState.selectedRowId)
        val index = if (focused == null && selectedIndex >= 0) selectedIndex else focusedIndex.coerceAtMost(rowIds.lastIndex)
        focusRow(listState, rowIds, index, ::requesterFor)
    }
}

/** Scrolls [index] into view (a row must be composed before its requester can take focus) and focuses it. */
private suspend fun focusRow(
    listState: LazyListState,
    rowIds: List<TorrentId>,
    index: Int,
    requesterFor: (TorrentId) -> FocusRequester,
) {
    listState.scrollToItem(index)
    withFrameNanos { }
    requesterFor(rowIds[index]).requestFocus()
}

@Composable
private fun DownloadRowItem(row: DownloadRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(text = row.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text =
                        listOf(
                            row.sizeText,
                            row.speed,
                            pluralStringResource(R.plurals.downloads_peers, row.peers, row.peers),
                            stringResource(R.string.downloads_eta, row.eta),
                            stringResource(R.string.downloads_ratio, row.ratio),
                        ).joinToString(SEPARATOR),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProgressBar(progress = row.progress, state = row.state)
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(text = row.percent, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = Formatters.stateLabel(row.state),
                    color = if (row.state == DownloadState.Error) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        modifier = modifier,
    )
}

/** tv-material has no progress indicator: a track with a filled fraction, dimmed while not active. */
@Composable
private fun ProgressBar(progress: Float, state: DownloadState) {
    val fill =
        when (state) {
            DownloadState.Error -> MaterialTheme.colorScheme.error
            DownloadState.Paused -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.primary
        }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress).background(fill))
    }
}

@Composable
private fun ActionsDialog(
    title: String,
    dialog: DownloadsDialog.Actions,
    onAction: (DownloadAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusedRequester = remember { FocusRequester() }
    DialogFrame(title = title, onDismiss = onDismiss) {
        dialog.items.forEach { item ->
            DialogButton(
                text =
                    when (item.action) {
                        DownloadAction.PauseResume -> dialog.pauseResumeLabel
                        DownloadAction.ChooseFiles -> stringResource(R.string.downloads_action_choose_files)
                        DownloadAction.Delete -> stringResource(R.string.downloads_action_delete)
                        DownloadAction.Cancel -> stringResource(R.string.downloads_action_cancel)
                    },
                enabled = item.enabled,
                onClick = { onAction(item.action) },
                focusRequester = focusedRequester.takeIf { item.action == dialog.focused },
            )
        }
    }
    LaunchedEffect(Unit) { focusedRequester.requestFocus() }
}

/** Focus starts on `Cancelar`: of the three, it is the one an accidental OK cannot hurt. */
@Composable
private fun ConfirmDeleteDialog(title: String, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val cancelRequester = remember { FocusRequester() }
    DialogFrame(title = title, subtitle = stringResource(R.string.downloads_confirm_delete), onDismiss = onDismiss) {
        DialogButton(text = stringResource(R.string.downloads_confirm_yes), onClick = { onConfirm(true) })
        DialogButton(text = stringResource(R.string.downloads_confirm_no), onClick = { onConfirm(false) })
        DialogButton(text = stringResource(R.string.downloads_action_cancel), onClick = onDismiss, focusRequester = cancelRequester)
    }
    LaunchedEffect(Unit) { cancelRequester.requestFocus() }
}

/** A modal window: BACK is [onDismiss]; UP/DOWN walk its buttons and cannot leave it. */
@Composable
private fun DialogFrame(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    buttons: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.width(DIALOG_WIDTH).padding(32.dp).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(text = subtitle, style = MaterialTheme.typography.bodyLarge)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .fillMaxWidth()
                // A disabled action is shown but skipped by the D-pad.
                .focusProperties { canFocus = enabled }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) {
        Text(text = text)
    }
}

private const val SEPARATOR = " · "
private val DIALOG_WIDTH = 420.dp
