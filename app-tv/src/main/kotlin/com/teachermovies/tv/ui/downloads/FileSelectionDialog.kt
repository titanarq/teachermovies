package com.teachermovies.tv.ui.downloads

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Checkbox
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.teachermovies.tv.R

/**
 * The `Elegir archivos` dialog (#71) bound to a [FileSelectionViewModel] built by [factory]. The
 * ViewModel lives in a store owned by this dialog, so each opening reloads the file list and
 * leaving the dialog cancels whatever it was doing. [onClose] runs on `Cancelar`, BACK, or once
 * `Aplicar` has succeeded.
 */
@Composable
fun FileSelectionRoute(factory: ViewModelProvider.Factory, onClose: () -> Unit) {
    val store = remember { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val viewModel = remember(store) { ViewModelProvider(store, factory)[FileSelectionViewModel::class.java] }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.done) { if (uiState.done) onClose() }
    FileSelectionDialog(uiState = uiState, onToggle = viewModel::toggle, onApply = viewModel::apply, onCancel = onClose)
}

/**
 * One D-pad toggle per file (OK flips its checkbox) above `Aplicar` and `Cancelar`.
 *
 * Focus: the first file row once the list is known, otherwise `Cancelar`; UP/DOWN walk the rows and
 * DOWN from the last row lands on `Aplicar` (on `Cancelar` while `Aplicar` is disabled). Focus
 * cannot leave the dialog; BACK is [onCancel].
 */
@Composable
fun FileSelectionDialog(
    uiState: FileSelectionUiState,
    onToggle: (Int) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    val firstRowRequester = remember { FocusRequester() }
    val applyRequester = remember { FocusRequester() }
    val cancelRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.width(DIALOG_WIDTH).padding(32.dp).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = stringResource(R.string.downloads_action_choose_files), style = MaterialTheme.typography.titleLarge)
                val message =
                    when {
                        uiState.notReady -> stringResource(R.string.file_selection_not_ready)
                        uiState.loading -> stringResource(R.string.file_selection_loading)
                        uiState.rows.isEmpty() && uiState.error == null -> stringResource(R.string.file_selection_empty)
                        else -> null
                    }
                if (message != null) Text(text = message, style = MaterialTheme.typography.bodyLarge)
                uiState.error?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
                if (uiState.rows.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(uiState.rows, key = { _, row -> row.index }) { position, row ->
                            val isLast = position == uiState.rows.lastIndex
                            FileRowItem(
                                row = row,
                                onClick = { onToggle(row.index) },
                                modifier =
                                    Modifier
                                        .then(if (position == 0) Modifier.focusRequester(firstRowRequester) else Modifier)
                                        .focusProperties {
                                            if (isLast) down = if (uiState.canApply) applyRequester else cancelRequester
                                        },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onApply,
                        enabled = uiState.canApply,
                        modifier = Modifier.focusRequester(applyRequester).focusProperties { canFocus = uiState.canApply },
                    ) {
                        Text(text = stringResource(R.string.file_selection_apply))
                    }
                    Button(onClick = onCancel, modifier = Modifier.focusRequester(cancelRequester)) {
                        Text(text = stringResource(R.string.downloads_action_cancel))
                    }
                }
            }
        }
    }
    // Entry focus: the first file once there is one (also when metadata arrives while open).
    val hasRows = uiState.rows.isNotEmpty()
    LaunchedEffect(hasRows) {
        withFrameNanos { }
        if (hasRows) firstRowRequester.requestFocus() else cancelRequester.requestFocus()
    }
}

@Composable
private fun FileRowItem(row: FileSelectionRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        selected = row.checked,
        onClick = onClick,
        leadingContent = { Checkbox(checked = row.checked, onCheckedChange = null) },
        headlineContent = { Text(text = row.path, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Text(text = row.sizeText) },
        modifier = modifier,
    )
}

private val DIALOG_WIDTH = 560.dp
private val LIST_MAX_HEIGHT = 360.dp
